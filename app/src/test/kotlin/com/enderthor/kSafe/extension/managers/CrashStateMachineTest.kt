package com.enderthor.kSafe.extension.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashStateMachineTest {

    private fun newSm(
        nowMs: Long = 1_000_000L,
        thresholds: Thresholds = Thresholds(),
    ): Pair<CrashStateMachine, () -> Unit> {
        var t = nowMs
        val sm = CrashStateMachine(thresholds = thresholds, clock = { t })
        return sm to { t += 1L }   // not used; tests pass timestamps explicitly via samples
    }

    private fun sample(
        time: Long,
        peak: Double = 0.0,
        smoothed: Double = peak,
        raw: Double = peak,
        gyro: Double = 0.0,
    ) = SensorSample(
        rawMagnitude = raw,
        smoothedMagnitude = smoothed,
        peakMagnitude = peak,
        gyroMag = gyro,
        timestampMs = time,
    )

    @Test
    fun `sample below thresholds stays in MONITORING`() {
        val (sm, _) = newSm()
        val d = sm.onSample(sample(time = 1000, peak = 5.0))
        assertEquals(CrashStateMachine.Decision.None, d)
        assertEquals(CrashStateMachine.State.MONITORING, sm.state)
    }

    @Test
    fun `peak above threshold with speed gate ok enters IMPACT`() {
        val (sm, _) = newSm()
        sm.onSpeedUpdate(20.0, gpsStale = false)
        val d = sm.onSample(sample(time = 1000, peak = 50.0, smoothed = 30.0, gyro = 10.0))
        assertTrue(d is CrashStateMachine.Decision.EnterImpact)
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
    }

    @Test
    fun `peak above threshold but speed below gate stays in MONITORING`() {
        val (sm, _) = newSm()
        sm.onSpeedUpdate(2.0, gpsStale = false) // below default minSpeedForCrashKmh=10
        val d = sm.onSample(sample(time = 1000, peak = 50.0, smoothed = 30.0, gyro = 10.0))
        assertEquals(CrashStateMachine.Decision.None, d)
        assertEquals(CrashStateMachine.State.MONITORING, sm.state)
    }

    @Test
    fun `IMPACT plus sustained silence transitions to SILENCE_CHECK`() {
        val (sm, _) = newSm()
        sm.onSpeedUpdate(20.0, gpsStale = false)
        sm.onSample(sample(time = 1000, peak = 50.0, smoothed = 30.0, gyro = 10.0))
        // accel is now quiet for >= silenceRequiredMs
        sm.onSample(sample(time = 1500, peak = 9.5))
        sm.onSample(sample(time = 2000, peak = 9.6))
        val d = sm.onSample(sample(time = 2700, peak = 9.5))    // 1700 ms of silence
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        // Decision may still be None at this point — confirmation needs the speed check too.
        assertTrue(d is CrashStateMachine.Decision.None || d is CrashStateMachine.Decision.Confirm)
    }

    @Test
    fun `SILENCE_CHECK with speed dropped and cadence quiet confirms`() {
        val (sm, _) = newSm()
        sm.onSpeedUpdate(20.0, gpsStale = false)
        sm.onCadenceUpdate(80.0)
        sm.onSample(sample(time = 1000, peak = 50.0, smoothed = 30.0, gyro = 10.0))
        sm.onSample(sample(time = 1500, peak = 9.5))
        sm.onSample(sample(time = 2700, peak = 9.5))
        sm.onSpeedUpdate(2.0, gpsStale = false)
        sm.onCadenceUpdate(0.0)
        val d = sm.onSample(sample(time = 3000, peak = 9.5))
        assertEquals(CrashStateMachine.Decision.Confirm, d)
    }

    @Test
    fun `IMPACT but rider keeps pedaling returns to MONITORING`() {
        val (sm, _) = newSm()
        sm.onSpeedUpdate(20.0, gpsStale = false)
        sm.onCadenceUpdate(80.0)
        sm.onSample(sample(time = 1000, peak = 50.0, smoothed = 30.0, gyro = 10.0))
        sm.onCadenceUpdate(75.0)   // still pedaling at the time of silence-check entry
        sm.onSample(sample(time = 2700, peak = 9.5))
        // Decision must NOT confirm; state should be back to MONITORING because cadence
        // active during silence-check window means the rider is fine.
        val d = sm.onSample(sample(time = 3000, peak = 9.5))
        assertEquals(CrashStateMachine.State.MONITORING, sm.state)
        assertTrue(d !is CrashStateMachine.Decision.Confirm)
    }

    @Test
    fun `stale cadence is treated as zero so it does not save the rider`() {
        // Cadence sensor disconnected mid-ride: last update was 80 rpm but it's now
        // older than thresholds.cadenceStaleThresholdMs. The state machine must NOT
        // use that stale reading to short-circuit the silence check.
        val (sm, _) = newSm(thresholds = Thresholds(cadenceStaleThresholdMs = 1_000L))
        sm.onSpeedUpdate(20.0, gpsStale = false)
        sm.onCadenceUpdate(80.0)
        sm.onSample(sample(time = 0, peak = 50.0, smoothed = 30.0, gyro = 10.0))
        // 2 seconds later (> cadenceStaleThresholdMs) — cadence is stale
        sm.onSample(sample(time = 2000, peak = 9.5))
        sm.onSpeedUpdate(2.0, gpsStale = false)
        val d = sm.onSample(sample(time = 3000, peak = 9.5))
        assertEquals(CrashStateMachine.Decision.Confirm, d)
    }

    @Test
    fun `onPause while in IMPACT resets to MONITORING`() {
        val (sm, _) = newSm()
        sm.onSpeedUpdate(20.0, gpsStale = false)
        sm.onSample(sample(time = 1000, peak = 50.0, smoothed = 30.0, gyro = 10.0))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        sm.onPause()
        assertEquals(CrashStateMachine.State.MONITORING, sm.state)
    }

    @Test
    fun `GPS stale plus crashConfirmSpeed equals 0 still confirms`() {
        // Documented behaviour in the algorithm doc: if the user accepts the trade-off of
        // setting crashConfirmSpeed=0 and the GPS is stale, the speed gate is treated as
        // "passing" so an outside-ride impact can still confirm.
        val (sm, _) = newSm(thresholds = Thresholds(crashConfirmSpeedKmh = 0))
        sm.onSpeedUpdate(0.0, gpsStale = true)
        sm.onSample(sample(time = 1000, peak = 50.0, smoothed = 30.0, gyro = 10.0))
        sm.onSample(sample(time = 1500, peak = 9.5))
        val d = sm.onSample(sample(time = 3000, peak = 9.5))
        assertEquals(CrashStateMachine.Decision.Confirm, d)
    }
}
