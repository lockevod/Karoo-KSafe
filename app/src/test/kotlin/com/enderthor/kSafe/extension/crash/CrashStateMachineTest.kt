package com.enderthor.kSafe.extension.crash

import com.enderthor.kSafe.extension.util.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for [CrashStateMachine].
 *
 * All tests are **synchronous** — the state machine owns no coroutines, no delays, no I/O.
 * Time is driven from two sources:
 *   - [SensorSample.timestampMs] for the state-machine internal clock (impact window,
 *     silence elapsed). Tests pass timestamps explicitly via [sample].
 *   - A mutable wall-clock variable for the cold-start guard / speed-update timestamps.
 *     Most tests pre-advance the wall clock past `coldStartGuardMs` so the guard is
 *     inert; the cold-start tests explicitly leave it inside the window.
 */
class CrashStateMachineTest {

    /** Quiet-frame smoothed magnitude (essentially gravity). */
    private val QUIET = 9.80

    /**
     * Build a state machine with a mutable clock the caller can advance. The clock starts
     * advanced beyond `coldStartGuardMs` so most tests don't trip the cold-start guard.
     */
    private fun newSm(
        thresholds: Thresholds = Thresholds(),
        // Default the wall clock far enough past 0 that the cold-start guard is inert.
        initialWallMs: Long = 1_000_000L,
    ): Pair<CrashStateMachine, ClockHandle> {
        val handle = ClockHandle(initialWallMs)
        val sm = CrashStateMachine(thresholds = thresholds, clock = handle.clock)
        return sm to handle
    }

    private class ClockHandle(initial: Long) {
        var t: Long = initial
        val clock: Clock = Clock { t }
        fun advance(ms: Long) { t += ms }
    }

    /** Build a [SensorSample]. `peak`, `smoothed`, `raw` default to each other. */
    private fun sample(
        time: Long,
        peak: Double = 0.0,
        smoothed: Double = peak,
        raw: Double = peak,
        gyro: Double = 0.0,
        gpsStale: Boolean = false,
    ) = SensorSample(
        rawMagnitude = raw,
        smoothedMagnitude = smoothed,
        peakMagnitude = peak,
        gyroMag = gyro,
        timestampMs = time,
        gpsStale = gpsStale,
    )

    // ── 1. Sample below thresholds stays in MONITORING ───────────────────────

    @Test
    fun `sample below thresholds stays in MONITORING`() {
        val (sm, _) = newSm()
        sm.onSpeedUpdate(20.0)
        val d = sm.onSample(sample(time = 1000, peak = 5.0))
        assertEquals(CrashStateMachine.Decision.None, d)
        assertEquals(CrashStateMachine.State.MONITORING, sm.state)
    }

    // ── 2. Peak above threshold + speed gate OK → IMPACT ─────────────────────

    @Test
    fun `peak above threshold with speed gate ok enters IMPACT`() {
        val (sm, _) = newSm()
        sm.onSpeedUpdate(20.0)
        val d = sm.onSample(sample(time = 1000, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        assertTrue("expected EnterImpact, got $d", d is CrashStateMachine.Decision.EnterImpact)
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
    }

    // ── 3. Peak above threshold, speed below minSpeed, NOT GPS-stale → MONITORING

    @Test
    fun `peak above threshold but speed below gate stays in MONITORING even though gpsStale=false`() {
        val (sm, _) = newSm()
        sm.onSpeedUpdate(2.0) // below default minSpeedForCrashKmh=10
        val d = sm.onSample(sample(time = 1000, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        assertEquals(CrashStateMachine.Decision.None, d)
        assertEquals(CrashStateMachine.State.MONITORING, sm.state)
    }

    // ── 3b. GPS-stale must NOT bypass the MONITORING speed gate ──────────────

    @Test
    fun `MONITORING entry gate is NOT bypassed when gpsStale=true`() {
        val (sm, _) = newSm()
        // Below minSpeed; staleness travels on the sample. Per doc, no bypass at MONITORING entry.
        sm.onSpeedUpdate(2.0)
        val d = sm.onSample(sample(time = 1000, peak = 60.0, smoothed = 30.0, gyro = 0.5, gpsStale = true))
        assertEquals(CrashStateMachine.Decision.None, d)
        assertEquals(CrashStateMachine.State.MONITORING, sm.state)
    }

    // ── 4. IMPACT + timeSinceImpact <= 500ms blocks SILENCE_CHECK entry ──────

    @Test
    fun `IMPACT with timeSinceImpact below 500ms cannot transition to SILENCE_CHECK`() {
        val (sm, _) = newSm()
        sm.onSpeedUpdate(2.0) // speed dropped, satisfies isSpeedDropConfirmed
        // Enter IMPACT at t=1000 with raised speed first so MONITORING entry passes
        sm.onSpeedUpdate(20.0)
        sm.onSample(sample(time = 1000, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        // Now drop speed and feed a quiet sample 200ms later (< 500ms gate)
        sm.onSpeedUpdate(2.0)
        val d = sm.onSample(sample(time = 1200, peak = QUIET, smoothed = QUIET, gyro = 0.5))
        assertEquals(CrashStateMachine.Decision.None, d)
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
    }

    // ── 5. IMPACT + all four conditions met → SILENCE_CHECK ──────────────────

    @Test
    fun `IMPACT with all four conditions met enters SILENCE_CHECK`() {
        val (sm, _) = newSm()
        sm.onSpeedUpdate(20.0)
        sm.onSample(sample(time = 1000, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        sm.onSpeedUpdate(2.0)
        // 600 ms after impact: deviation < 4.0, gyro < 2.0, time > 500, speed < confirm
        val d = sm.onSample(sample(time = 1600, peak = QUIET, smoothed = QUIET, gyro = 0.5))
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        assertEquals(CrashStateMachine.Decision.None, d)
    }

    // ── 6. SILENCE_CHECK + sustained isStill for silenceDurationMs → Confirm ─

    @Test
    fun `SILENCE_CHECK with continuous stillness for full silenceDurationMs confirms`() {
        val (sm, _) = newSm()
        sm.onSpeedUpdate(20.0)
        sm.onSample(sample(time = 0, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        sm.onSpeedUpdate(2.0)
        // Enter SILENCE_CHECK at t=600 (timeSinceImpact > 500ms)
        sm.onSample(sample(time = 600, peak = QUIET, smoothed = QUIET, gyro = 0.5))
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        // Tick quiet frames inside the silence window.
        sm.onSample(sample(time = 2000, peak = QUIET, smoothed = QUIET, gyro = 0.5))
        sm.onSample(sample(time = 4000, peak = QUIET, smoothed = QUIET, gyro = 0.5))
        // silenceDurationMs = 4500; silenceStartedMs = 600 → confirm at >= 5100
        val d = sm.onSample(sample(time = 5200, peak = QUIET, smoothed = QUIET, gyro = 0.5))
        assertEquals(CrashStateMachine.Decision.Confirm, d)
    }

    // ── 7. SILENCE_CHECK GPS-stale uses hardened thresholds ──────────────────

    @Test
    fun `SILENCE_CHECK GPS-stale path uses gpsStaleSilenceDurationMs and gpsStaleSilenceDeviationMax`() {
        // With crashConfirmSpeedKmh=0 the speed gate is disabled — required so we can
        // get past the MONITORING entry gate at speed=0. Staleness is carried on each
        // sample so the IMPACT/SILENCE_CHECK path uses hardened thresholds.
        val (sm, _) = newSm(
            thresholds = Thresholds(crashConfirmSpeedKmh = 0, minSpeedForCrashKmh = 0)
        )
        sm.onSpeedUpdate(0.0)
        sm.onSample(sample(time = 0, peak = 60.0, smoothed = 30.0, gyro = 0.5, gpsStale = true))
        // Magnitude well inside the GPS-stale 1.5 deviation max (|9.80-9.81|=0.01)
        sm.onSample(sample(time = 600, peak = QUIET, smoothed = QUIET, gyro = 0.5, gpsStale = true))
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)

        // Just shy of gpsStaleSilenceDurationMs (8000) — must NOT confirm yet.
        val notYet = sm.onSample(sample(time = 8000, peak = QUIET, smoothed = QUIET, gyro = 0.5, gpsStale = true))
        // silenceStartedMs = 600; elapsed at t=8000 → 7400 < 8000 → no confirm
        assertEquals(CrashStateMachine.Decision.None, notYet)

        // Past the gpsStaleSilenceDurationMs window — should confirm.
        val confirmed = sm.onSample(sample(time = 8700, peak = QUIET, smoothed = QUIET, gyro = 0.5, gpsStale = true))
        assertEquals(CrashStateMachine.Decision.Confirm, confirmed)
    }

    // ── 7b. SILENCE_CHECK GPS-stale uses stricter deviation max (1.5 m/s²) ───

    @Test
    fun `SILENCE_CHECK GPS-stale rejects samples deviating more than the hardened max`() {
        val (sm, _) = newSm(
            thresholds = Thresholds(crashConfirmSpeedKmh = 0, minSpeedForCrashKmh = 0)
        )
        sm.onSpeedUpdate(0.0)
        sm.onSample(sample(time = 0, peak = 60.0, smoothed = 30.0, gyro = 0.5, gpsStale = true))
        // Magnitude = 11.5 → deviation = 1.69 > 1.5 (gpsStaleSilenceDeviationMax)
        // Must NOT enter SILENCE_CHECK.
        sm.onSample(sample(time = 600, peak = 11.5, smoothed = 11.5, gyro = 0.5, gpsStale = true))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
    }

    // ── 8. SILENCE_CHECK isStill interrupted mid-way → timer resets ──────────

    @Test
    fun `SILENCE_CHECK isStill interrupted resets silence timer but stays in SILENCE_CHECK`() {
        val (sm, _) = newSm()
        sm.onSpeedUpdate(20.0)
        sm.onSample(sample(time = 0, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        sm.onSpeedUpdate(2.0)
        sm.onSample(sample(time = 600, peak = QUIET, smoothed = QUIET, gyro = 0.5))
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)

        // Spike mid-silence at t=2000 (deviation > 4.0) — resets timer.
        sm.onSample(sample(time = 2000, peak = 15.0, smoothed = 15.0, gyro = 0.5))
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)

        // At t=5500 — elapsed since reset = 3500 ms < 4500 → still NOT confirmed.
        // Continuous (not cumulative) stillness required.
        val notYet = sm.onSample(sample(time = 5500, peak = QUIET, smoothed = QUIET, gyro = 0.5))
        assertEquals(CrashStateMachine.Decision.None, notYet)
    }

    // ── 9. SILENCE_CHECK never settles within doubled window → MONITORING ────

    @Test
    fun `SILENCE_CHECK not-still beyond impactWindow times two returns to MONITORING`() {
        val (sm, _) = newSm()
        sm.onSpeedUpdate(20.0)
        sm.onSample(sample(time = 0, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        sm.onSpeedUpdate(2.0)
        sm.onSample(sample(time = 600, peak = QUIET, smoothed = QUIET, gyro = 0.5))
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)

        // impactWindowMs = 20_000; doubled window = 40_000.
        // Keep hitting the timer-reset branch within the doubled window…
        sm.onSample(sample(time = 20_000, peak = 15.0, smoothed = 15.0, gyro = 0.5))
        // …then a !isStill sample BEYOND the doubled window.
        val d = sm.onSample(sample(time = 40_100, peak = 15.0, smoothed = 15.0, gyro = 0.5))
        assertEquals(CrashStateMachine.Decision.ReturnToMonitoring, d)
        assertEquals(CrashStateMachine.State.MONITORING, sm.state)
    }

    // ── 10. IMPACT but rider keeps pedalling → confirmation blocked ──────────

    @Test
    fun `cadence active in SILENCE_CHECK forces false-alarm exit`() {
        val (sm, _) = newSm()
        sm.onSpeedUpdate(20.0)
        sm.onCadenceUpdate(80.0)  // fresh, > 20 RPM
        sm.onSample(sample(time = 0, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        sm.onSpeedUpdate(2.0)
        // Touch cadence again so its sample-time is current (cadence stays fresh).
        sm.onCadenceUpdate(75.0)
        sm.onSample(sample(time = 600, peak = QUIET, smoothed = QUIET, gyro = 0.5))
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        sm.onCadenceUpdate(75.0)
        val d = sm.onSample(sample(time = 1000, peak = QUIET, smoothed = QUIET, gyro = 0.5))
        // Cadence > 20 RPM in SILENCE_CHECK → instant false-alarm exit.
        assertEquals(CrashStateMachine.State.MONITORING, sm.state)
        assertNotEquals(CrashStateMachine.Decision.Confirm, d)
    }

    // ── 11. Stale cadence treated as zero — does NOT save the rider ──────────

    @Test
    fun `stale cadence age over threshold is treated as zero and does not block confirmation`() {
        // Exercises the `age > cadenceStaleThresholdMs` branch of isCadenceActive
        // specifically. We must first fire a sample so lastSampleMs > 0; only then
        // does onCadenceUpdate stamp lastCadenceUpdateMs to that non-zero sample
        // time and the staleness *age* computation becomes meaningful.
        val (sm, _) = newSm(thresholds = Thresholds(cadenceStaleThresholdMs = 1_000L))
        sm.onSpeedUpdate(20.0)
        // 1. Fire an early sample first → lastSampleMs = 1000.
        sm.onSample(sample(time = 1_000, peak = 5.0, smoothed = 5.0, gyro = 0.5))
        // 2. NOW write cadence — it gets stamped with lastSampleMs = 1000.
        sm.onCadenceUpdate(80.0)
        // 3. Now drive the impact. Speed must be high enough to pass the gate.
        sm.onSample(sample(time = 1_100, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        sm.onSpeedUpdate(2.0)
        // 4. Enter SILENCE_CHECK at t=1700 (timeSinceImpact = 600 > 500).
        //    Cadence age here is 1700-1000 = 700 < 1000 → still fresh, but we're
        //    leaving the cadence-active branch via the >20 RPM check just by entering.
        //    Wait — fresh cadence at 80 RPM would force false-alarm exit. So we need
        //    a sample where cadence is already STALE before entering SILENCE_CHECK.
        //
        //    Drive sample-time forward to 2_500 so cadence age = 1_500 > 1_000 (stale).
        sm.onSample(sample(time = 2_500, peak = QUIET, smoothed = QUIET, gyro = 0.5))
        // We are now in SILENCE_CHECK with cadence age 1500 > 1000 — treated as zero.
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        // silenceStartedMs = 2_500 → confirm at >= 2_500 + 4_500 = 7_000.
        val d = sm.onSample(sample(time = 7_100, peak = QUIET, smoothed = QUIET, gyro = 0.5))
        assertEquals(CrashStateMachine.Decision.Confirm, d)
    }

    // ── 11b. Cadence never received in-ride — separate branch worth covering ─

    @Test
    fun `no cadence received in-ride is treated as zero and does not block confirmation`() {
        // Cadence is updated BEFORE any sample is processed, so lastCadenceUpdateMs
        // stays at 0 (the "never received in-ride" sentinel). isCadenceActive must
        // return false via that branch — distinct from the age>threshold branch.
        val (sm, _) = newSm(thresholds = Thresholds(cadenceStaleThresholdMs = 1_000L))
        sm.onSpeedUpdate(20.0)
        sm.onCadenceUpdate(80.0)  // logged at lastSampleMs == 0 (never-received sentinel)
        sm.onSample(sample(time = 0, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        sm.onSpeedUpdate(2.0)
        // Even though cadence value is 80 RPM, the sentinel makes isCadenceActive=false.
        sm.onSample(sample(time = 600, peak = QUIET, smoothed = QUIET, gyro = 0.5))
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        // silenceStartedMs = 600 → need ≥ 5100 for 4500-ms window.
        val d = sm.onSample(sample(time = 5_200, peak = QUIET, smoothed = QUIET, gyro = 0.5))
        assertEquals(CrashStateMachine.Decision.Confirm, d)
    }

    // ── 12. Cold-start guard blocks confirmation when speed never received ───

    @Test
    fun `cold-start guard blocks confirmation when no speed update received yet`() {
        // Start with the wall clock at 0 so we are INSIDE the cold-start guard window.
        val (sm, _) = newSm(
            thresholds = Thresholds(minSpeedForCrashKmh = 0),
            initialWallMs = 0L,
        )
        // No onSpeedUpdate call at all → speedLastUpdatedAtMs stays NEVER.
        sm.onSample(sample(time = 0, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        // Even with all other conditions met, isSpeedDropConfirmed must return false.
        // Quiet samples for 5+ seconds in sample-time, but wall-clock is still 0 (clock fixed).
        sm.onSample(sample(time = 600, peak = QUIET, smoothed = QUIET, gyro = 0.5))
        // Should NOT have entered SILENCE_CHECK — speed-drop guard blocks the 4th condition.
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        val d = sm.onSample(sample(time = 5_500, peak = QUIET, smoothed = QUIET, gyro = 0.5))
        assertNotEquals(CrashStateMachine.Decision.Confirm, d)
    }

    // ── 12b. Cold-start guard with production wiring (samples carry gpsStale) ─

    @Test
    fun `cold-start guard holds when only samples flow and no speed update arrives`() {
        // Production-wire emulation: the facade only calls onSpeedUpdate from updateSpeed
        // (real SDK speed reading). Samples carry their own gpsStale field — which must
        // NOT bump the "speed update received" sentinel. With no real speed update for
        // the full 8 s guard window, confirmation must stay blocked.
        val (sm, handle) = newSm(
            thresholds = Thresholds(minSpeedForCrashKmh = 0),
            initialWallMs = 0L,
        )
        // Fire 5 samples spanning 6 seconds of sample time. The wall clock starts at 0
        // and we tick it only slightly so we stay inside coldStartGuardMs (8s).
        sm.onSample(sample(time = 0, peak = 60.0, smoothed = 30.0, gyro = 0.5, gpsStale = false))
        handle.advance(1_000)
        sm.onSample(sample(time = 1_500, peak = QUIET, smoothed = QUIET, gyro = 0.5, gpsStale = false))
        handle.advance(1_500)
        sm.onSample(sample(time = 3_000, peak = QUIET, smoothed = QUIET, gyro = 0.5, gpsStale = false))
        handle.advance(1_500)
        sm.onSample(sample(time = 4_500, peak = QUIET, smoothed = QUIET, gyro = 0.5, gpsStale = false))
        handle.advance(1_500)
        // Even after sample-time 6 s and silenceDurationMs (4500) elapsed since entering
        // SILENCE_CHECK, the cold-start guard must still block confirmation because
        // onSpeedUpdate was never called. Wall clock now at ~5.5s — still < 8 s guard.
        val d = sm.onSample(sample(time = 6_000, peak = QUIET, smoothed = QUIET, gyro = 0.5, gpsStale = false))
        assertNotEquals(CrashStateMachine.Decision.Confirm, d)
        // SILENCE_CHECK entry itself must not have happened either (isSpeedDropConfirmed
        // is false for the IMPACT→SILENCE_CHECK transition too).
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
    }

    // ── 13. crashConfirmSpeedKmh = 0 disables the confirm gate ───────────────

    @Test
    fun `crashConfirmSpeedKmh equal to zero disables the confirmation speed gate`() {
        val (sm, _) = newSm(thresholds = Thresholds(crashConfirmSpeedKmh = 0))
        sm.onSpeedUpdate(20.0)  // speed never drops!
        sm.onSample(sample(time = 0, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        // Keep speed high — the speed gate is disabled, so this must not block confirmation.
        sm.onSample(sample(time = 600, peak = QUIET, smoothed = QUIET, gyro = 0.5))
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        val d = sm.onSample(sample(time = 5_200, peak = QUIET, smoothed = QUIET, gyro = 0.5))
        assertEquals(CrashStateMachine.Decision.Confirm, d)
    }

    // ── 14. minSpeedForCrashKmh = 0 lets IMPACT entry happen even at speed=0 ─

    @Test
    fun `minSpeedForCrashKmh equal to zero disables the MONITORING entry speed gate`() {
        val (sm, _) = newSm(thresholds = Thresholds(minSpeedForCrashKmh = 0))
        sm.onSpeedUpdate(0.0)
        val d = sm.onSample(sample(time = 1000, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        assertTrue("expected EnterImpact, got $d", d is CrashStateMachine.Decision.EnterImpact)
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
    }

    // ── 15. onPause resets state machine to MONITORING ───────────────────────

    @Test
    fun `onPause while in IMPACT resets to MONITORING`() {
        val (sm, _) = newSm()
        sm.onSpeedUpdate(20.0)
        sm.onSample(sample(time = 1000, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        sm.onPause()
        assertEquals(CrashStateMachine.State.MONITORING, sm.state)
    }

    // ── 16. IMPACT timeout → MONITORING ──────────────────────────────────────

    @Test
    fun `IMPACT timeout after impactWindowMs returns to MONITORING`() {
        // Speed never drops, so SILENCE_CHECK entry is blocked. Should time out.
        val (sm, _) = newSm()
        sm.onSpeedUpdate(20.0)
        sm.onSample(sample(time = 0, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        // 20_001 ms later — past impactWindowMs default 20_000.
        val d = sm.onSample(sample(time = 20_001, peak = QUIET, smoothed = QUIET, gyro = 0.5))
        assertEquals(CrashStateMachine.Decision.ReturnToMonitoring, d)
        assertEquals(CrashStateMachine.State.MONITORING, sm.state)
    }

    // ── 17. Gyro > gyroMovingMax blocks IMPACT → SILENCE_CHECK entry ────────

    @Test
    fun `gyro above gyroMovingMax blocks SILENCE_CHECK entry`() {
        val (sm, _) = newSm()
        sm.onSpeedUpdate(20.0)
        sm.onSample(sample(time = 0, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        sm.onSpeedUpdate(2.0)
        // Accel quiet, speed dropped, time>500 — but gyro = 3.0 > 2.0 → blocked.
        sm.onSample(sample(time = 600, peak = QUIET, smoothed = QUIET, gyro = 3.0))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
    }

    // ── Orientation: baseline learner ────────────────────────────────────────

    @Test
    fun `baseline is not ready before minSamples cruising samples have been fed`() {
        val (sm, _) = newSm(Thresholds(baselineMinSamples = 100))
        repeat(99) { sm.feedBaselineSample(0.0, 0.0, 9.81) }
        assertEquals(false, sm.isBaselineReady())
    }

    @Test
    fun `baseline becomes ready after minSamples cruising samples`() {
        val (sm, _) = newSm(Thresholds(baselineMinSamples = 100))
        repeat(100) { sm.feedBaselineSample(0.0, 0.0, 9.81) }
        assertEquals(true, sm.isBaselineReady())
    }

    @Test
    fun `baseline learner ignores samples while in IMPACT state`() {
        val (sm, _) = newSm(Thresholds(baselineMinSamples = 10))
        sm.onSpeedUpdate(20.0)
        // Enter IMPACT
        sm.onSample(sample(time = 1000, peak = 60.0, smoothed = 30.0))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        // Try to feed baseline samples — must be ignored.
        repeat(100) { sm.feedBaselineSample(0.0, 0.0, 9.81) }
        assertEquals(false, sm.isBaselineReady())
    }

    @Test
    fun `baseline averages multiple samples`() {
        val (sm, _) = newSm(Thresholds(baselineMinSamples = 2))
        sm.feedBaselineSample(0.0, 0.0, 10.0)
        sm.feedBaselineSample(2.0, 0.0, 8.0)
        // Average vector: (1, 0, 9). Magnitude ≈ sqrt(82) ≈ 9.055.
        val (x, y, z) = sm.baselineVector()
        assertEquals(1.0, x, 1e-9)
        assertEquals(0.0, y, 1e-9)
        assertEquals(9.0, z, 1e-9)
    }

    @Test
    fun `reset clears the baseline state`() {
        val (sm, _) = newSm(Thresholds(baselineMinSamples = 10))
        repeat(15) { sm.feedBaselineSample(0.0, 0.0, 9.81) }
        assertEquals(true, sm.isBaselineReady())
        sm.reset()
        assertEquals(false, sm.isBaselineReady())
        val (x, y, z) = sm.baselineVector()
        assertEquals(0.0, x, 1e-9)
        assertEquals(0.0, y, 1e-9)
        assertEquals(0.0, z, 1e-9)
    }

    // ── Orientation: silence-duration selection ──────────────────────────────

    /**
     * Helper: get a state machine into SILENCE_CHECK with a baseline already learned.
     * Returns the SM (clock and time cursor are managed by the caller).
     */
    private fun smInSilenceCheckWithBaseline(
        thresholds: Thresholds = Thresholds(baselineMinSamples = 10),
        baselineVector: Triple<Double, Double, Double> = Triple(0.0, 0.0, 9.81),
    ): CrashStateMachine {
        val (sm, _) = newSm(thresholds)
        sm.onSpeedUpdate(20.0)
        // Feed enough samples to make baseline ready.
        repeat(thresholds.baselineMinSamples) {
            sm.feedBaselineSample(baselineVector.first, baselineVector.second, baselineVector.third)
        }
        // Enter IMPACT.
        sm.onSample(sample(time = 1000, peak = 60.0, smoothed = 30.0))
        // Drive into SILENCE_CHECK: rider stops + accel calms.
        sm.onSpeedUpdate(0.0)
        sm.onSample(sample(time = 2000, peak = 0.0, smoothed = 9.81, raw = 9.81,
            gyro = 0.1))
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        return sm
    }

    @Test
    fun `silence_check uses upright duration when bike orientation matches baseline`() {
        val t = Thresholds(
            baselineMinSamples = 10,
            silenceDurationMs = 4_500L,
            silenceDurationUprightMs = 20_000L,
            uprightAngleThresholdDegrees = 45.0,
        )
        val sm = smInSilenceCheckWithBaseline(t)
        // Feed quiet samples WITH upright orientation (matches baseline (0,0,9.81)).
        // Total elapsed since silenceStartedMs = 4_500ms is NOT enough for upright (20s).
        var t0 = 2000L
        repeat(219) {  // 219 samples × ~20ms each = ~4.38s
            t0 += 20
            val d = sm.onSample(sample(
                time = t0, peak = 0.0, smoothed = 9.81, raw = 9.81, gyro = 0.1,
            ).copy(accelX = 0.0, accelY = 0.0, accelZ = 9.81))
            // Must NOT confirm yet — upright threshold is 20s, not 4.5s.
            assertNotEquals("Decision.Confirm should not fire before upright window",
                CrashStateMachine.Decision.Confirm, d)
        }
        // Now jump past 20s. Confirm must fire.
        t0 = 22_500L
        val d = sm.onSample(sample(
            time = t0, peak = 0.0, smoothed = 9.81, raw = 9.81, gyro = 0.1,
        ).copy(accelX = 0.0, accelY = 0.0, accelZ = 9.81))
        assertEquals(CrashStateMachine.Decision.Confirm, d)
    }

    @Test
    fun `silence_check uses legacy duration when bike is on its side`() {
        val t = Thresholds(
            baselineMinSamples = 10,
            silenceDurationMs = 4_500L,
            silenceDurationUprightMs = 20_000L,
            uprightAngleThresholdDegrees = 45.0,
        )
        val sm = smInSilenceCheckWithBaseline(t)
        // Feed quiet samples with the bike laid 90° on its side: gravity along X axis,
        // baseline along Z. Angle ≈ 90° > 45° → use legacy 4.5s window.
        var t0 = 2000L
        repeat(224) {  // ~4.48s, just under the legacy threshold
            t0 += 20
            sm.onSample(sample(
                time = t0, peak = 0.0, smoothed = 9.81, raw = 9.81, gyro = 0.1,
            ).copy(accelX = 9.81, accelY = 0.0, accelZ = 0.0))
        }
        // At t≈6500 we should have crossed 4.5s of silence → next quiet sample confirms.
        t0 = 7_500L
        val d = sm.onSample(sample(
            time = t0, peak = 0.0, smoothed = 9.81, raw = 9.81, gyro = 0.1,
        ).copy(accelX = 9.81, accelY = 0.0, accelZ = 0.0))
        assertEquals(CrashStateMachine.Decision.Confirm, d)
    }

    @Test
    fun `silence_check falls back to legacy duration when baseline not ready`() {
        val t = Thresholds(
            // High threshold + no baseline samples → baseline never ready.
            baselineMinSamples = 1_500,
            silenceDurationMs = 4_500L,
            silenceDurationUprightMs = 20_000L,
        )
        val (sm, _) = newSm(t)
        sm.onSpeedUpdate(20.0)
        // Enter IMPACT and SILENCE_CHECK WITHOUT feeding baseline.
        sm.onSample(sample(time = 1000, peak = 60.0, smoothed = 30.0))
        sm.onSpeedUpdate(0.0)
        sm.onSample(sample(time = 2000, peak = 0.0, smoothed = 9.81, raw = 9.81, gyro = 0.1))
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        // 4.5s of quiet — should confirm at legacy threshold since no baseline.
        var t0 = 2000L
        repeat(224) {
            t0 += 20
            sm.onSample(sample(
                time = t0, peak = 0.0, smoothed = 9.81, raw = 9.81, gyro = 0.1,
            ).copy(accelX = 0.0, accelY = 0.0, accelZ = 9.81))
        }
        t0 = 7_500L
        val d = sm.onSample(sample(
            time = t0, peak = 0.0, smoothed = 9.81, raw = 9.81, gyro = 0.1,
        ).copy(accelX = 0.0, accelY = 0.0, accelZ = 9.81))
        assertEquals(CrashStateMachine.Decision.Confirm, d)
    }

    // ── Scenario: bump + brake + stop upright must not confirm under 20s ─────

    @Test
    fun `scenario bump plus brake plus stop upright does not confirm before 20s`() {
        val t = Thresholds(
            baselineMinSamples = 10,
            silenceDurationMs = 4_500L,
            silenceDurationUprightMs = 20_000L,
            uprightAngleThresholdDegrees = 45.0,
            // Use LOW-preset-equivalent thresholds for this scenario.
            smoothedImpactThreshold = 55.0,
            peakImpactThreshold = 60.0,
            minSpeedForCrashKmh = 3,
            crashConfirmSpeedKmh = 3,
        )
        val (sm, _) = newSm(t)

        // ── Phase 1: cruise at 30 km/h upright for baseline learning ─────────
        sm.onSpeedUpdate(30.0)
        repeat(t.baselineMinSamples + 5) {
            sm.feedBaselineSample(0.0, 0.0, 9.81)
        }
        assertEquals("baseline must be ready", true, sm.isBaselineReady())

        // ── Phase 2: bump → IMPACT entry ─────────────────────────────────────
        val tBump = 1_000L
        val bump = sample(
            time = tBump, peak = 70.0, smoothed = 60.0, raw = 70.0, gyro = 4.0,
        ).copy(accelX = 0.0, accelY = 0.0, accelZ = 70.0)
        val d1 = sm.onSample(bump)
        assertTrue("bump must enter IMPACT, got $d1",
            d1 is CrashStateMachine.Decision.EnterImpact)
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)

        // ── Phase 3: braking — speed drops 30 → 0, accel noisy ───────────────
        // 2s of braking, samples ~50Hz; speed update each 200ms.
        var tNow = tBump
        val brakeMs = 2_000L
        val brakeStep = 20L  // 50Hz
        var stepsDone = 0
        while (tNow < tBump + brakeMs) {
            tNow += brakeStep
            stepsDone++
            // Speed decreases linearly: 30 → 0 over 2 s.
            val speed = 30.0 * (1.0 - (tNow - tBump).toDouble() / brakeMs)
            if (stepsDone % 10 == 0) sm.onSpeedUpdate(speed.coerceAtLeast(0.0))
            // Accel noise from brake force ~ 5-10 m/s² deviation, bike upright.
            sm.onSample(sample(
                time = tNow, peak = 11.0, smoothed = 11.0, raw = 11.0, gyro = 0.5,
            ).copy(accelX = 0.0, accelY = -7.0, accelZ = 9.0))
        }
        sm.onSpeedUpdate(0.0)
        // Rider may have not yet entered SILENCE_CHECK due to ongoing noise.

        // ── Phase 4: rider fully stopped — quiet upright samples for 19.5s ──
        val tStopStart = tNow
        val quietStep = 20L
        while (tNow < tStopStart + 19_500L) {
            tNow += quietStep
            val d = sm.onSample(sample(
                time = tNow, peak = 0.0, smoothed = 9.81, raw = 9.81, gyro = 0.05,
            ).copy(accelX = 0.0, accelY = 0.0, accelZ = 9.81))
            assertNotEquals(
                "Decision.Confirm should not fire before upright window (t=$tNow)",
                CrashStateMachine.Decision.Confirm, d
            )
        }
    }

    // ── Regression: silence-window accumulator resets on silence-break ───────

    @Test
    fun `orientation accumulator resets when stillness is broken`() {
        val t = Thresholds(
            baselineMinSamples = 10,
            silenceDurationMs = 4_500L,
            silenceDurationUprightMs = 20_000L,
            uprightAngleThresholdDegrees = 45.0,
            minSpeedForCrashKmh = 3,
            crashConfirmSpeedKmh = 3,
        )
        val sm = smInSilenceCheckWithBaseline(t)  // baseline along Z, currently in SILENCE_CHECK

        // Feed ~1s of on-side samples so the orientation accumulator points along X.
        var tNow = 2_000L
        repeat(50) {
            tNow += 20
            sm.onSample(sample(
                time = tNow, peak = 0.0, smoothed = 9.81, raw = 9.81, gyro = 0.05,
            ).copy(accelX = 9.81, accelY = 0.0, accelZ = 0.0))
        }

        // Inject a spike (deviation > 4) to break stillness. This must trigger
        // the silence-break branch and reset the accumulator + silence clock.
        tNow += 20
        sm.onSample(sample(
            time = tNow, peak = 15.0, smoothed = 15.0, raw = 15.0, gyro = 0.5,
        ).copy(accelX = 9.81, accelY = 0.0, accelZ = 0.0))

        // Now feed UPRIGHT quiet samples (gravity along Z, matching baseline).
        // If the accumulator was NOT reset, the orientation average would still
        // be polluted with the on-side X-axis history → angle would stay > 45°
        // → legacy 4.5s window → confirm too soon.
        // If the accumulator IS reset (correct behaviour) → orientation immediately
        // reflects the new upright posture → 20s window → must NOT confirm before that.

        // Run for 4.5s + slack of upright quiet samples. Confirm must NOT fire.
        val tBreak = tNow
        while (tNow < tBreak + 5_500L) {  // 5.5s — well past legacy 4.5s threshold
            tNow += 20
            val d = sm.onSample(sample(
                time = tNow, peak = 0.0, smoothed = 9.81, raw = 9.81, gyro = 0.05,
            ).copy(accelX = 0.0, accelY = 0.0, accelZ = 9.81))
            assertNotEquals(
                "Decision.Confirm fired at t=$tNow — accumulator must have leaked on-side history",
                CrashStateMachine.Decision.Confirm, d
            )
        }
    }

    // ── Regression: onPause clears the silence-window accumulator ────────────

    @Test
    fun `onPause clears silence-window accumulator`() {
        val t = Thresholds(
            baselineMinSamples = 10,
            silenceDurationMs = 4_500L,
            silenceDurationUprightMs = 20_000L,
            uprightAngleThresholdDegrees = 45.0,
        )
        val sm = smInSilenceCheckWithBaseline(t)  // already in SILENCE_CHECK with baseline along Z

        // Feed 500 upright (Z-axis) samples while staying in SILENCE_CHECK.
        // These are quiet and upright, so isStill=true; elapsed (10 s) < uprightSilenceDurationMs
        // (20 s) → the SM keeps returning None without confirming. The silence-window
        // accumulator now holds 500 upright Z-axis samples.
        var tNow = 2_000L
        repeat(500) {
            tNow += 20
            sm.onSample(sample(
                time = tNow, peak = 0.0, smoothed = 9.81, raw = 9.81, gyro = 0.05,
            ).copy(accelX = 0.0, accelY = 0.0, accelZ = 9.81))
        }
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)

        // Pause — must clear the accumulator AND return state to MONITORING.
        sm.onPause()
        assertEquals(CrashStateMachine.State.MONITORING, sm.state)

        // Re-enter IMPACT (rider is now on-side after a real crash), then SILENCE_CHECK.
        // On-side samples: gravity along X, baseline along Z → angle ≈ 90° > 45°
        // → legacy 4.5 s silence threshold should apply.
        //
        // WITHOUT the fix: the 500 stale upright Z samples inflate the accumulator's
        // Z component, keeping the apparent angle < 45° for hundreds of new samples
        // → computeEffectiveSilenceMs returns 20 s instead of 4.5 s → crash never
        // confirmed in a 5 s window (false negative).
        //
        // WITH the fix: the accumulator is reset on onPause(), so the new SILENCE_CHECK
        // sees only on-side X-axis samples → angle ≈ 90° → 4.5 s threshold → confirm
        // fires around 4.5 s after entering the new SILENCE_CHECK.
        sm.onSpeedUpdate(20.0)
        sm.onSample(sample(time = tNow + 1_000, peak = 60.0, smoothed = 30.0))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        sm.onSpeedUpdate(0.0)
        sm.onSample(sample(time = tNow + 2_000, peak = 0.0, smoothed = 9.81,
            raw = 9.81, gyro = 0.1).copy(accelX = 9.81, accelY = 0.0, accelZ = 0.0))
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)

        // Feed 5 s of on-side quiet samples. With the accumulator correctly cleared,
        // the orientation reads as on-side (angle ≈ 90°) → legacy 4.5 s threshold →
        // Decision.Confirm must fire before the loop ends.
        val tEnter = tNow + 2_000
        var tQuiet = tEnter
        var confirmed = false
        while (tQuiet < tEnter + 5_000L) {
            tQuiet += 20
            val d = sm.onSample(sample(
                time = tQuiet, peak = 0.0, smoothed = 9.81, raw = 9.81, gyro = 0.05,
            ).copy(accelX = 9.81, accelY = 0.0, accelZ = 0.0))
            if (d == CrashStateMachine.Decision.Confirm) {
                confirmed = true
                break
            }
        }
        assertEquals(
            "Decision.Confirm did not fire within 5 s — accumulator likely leaked upright " +
                "Z samples across onPause, inflating effectiveSilenceMs from 4.5 s to 20 s",
            true, confirmed
        )
    }

    // ── Lifecycle: resumeForRide preserves baseline ──────────────────────────

    @Test
    fun `resumeForRide preserves baseline and counter`() {
        val (sm, _) = newSm(Thresholds(baselineMinSamples = 10))
        // Build a baseline.
        repeat(20) { sm.feedBaselineSample(0.0, 0.0, 9.81) }
        assertEquals(true, sm.isBaselineReady())
        val (bx, by, bz) = sm.baselineVector()

        // Simulate pause-resume on the state machine.
        sm.resumeForRide()

        // Baseline must survive.
        assertEquals(true, sm.isBaselineReady())
        val (bx2, by2, bz2) = sm.baselineVector()
        assertEquals(bx, bx2, 1e-9)
        assertEquals(by, by2, 1e-9)
        assertEquals(bz, bz2, 1e-9)

        // But timing/state must reset to MONITORING with clean clocks.
        assertEquals(CrashStateMachine.State.MONITORING, sm.state)
    }

    @Test
    fun `resumeForRide clears the silence-window accumulator`() {
        val t = Thresholds(
            baselineMinSamples = 10,
            silenceDurationMs = 4_500L,
            silenceDurationUprightMs = 20_000L,
            uprightAngleThresholdDegrees = 45.0,
        )
        val sm = smInSilenceCheckWithBaseline(t)

        // Feed some on-side samples so the accumulator is non-empty.
        var tNow = 2_000L
        repeat(50) {
            tNow += 20
            sm.onSample(sample(
                time = tNow, peak = 0.0, smoothed = 9.81, raw = 9.81, gyro = 0.05,
            ).copy(accelX = 9.81, accelY = 0.0, accelZ = 0.0))
        }

        // Resume mid-SILENCE_CHECK — accumulator must be cleared, state must reset.
        sm.resumeForRide()
        assertEquals(CrashStateMachine.State.MONITORING, sm.state)

        // Re-enter IMPACT → SILENCE_CHECK with upright samples. If accumulator
        // was NOT cleared, on-side history would inflate effectiveSilenceMs to
        // legacy 4.5s for a "really upright now" rider. With it cleared, the
        // new upright orientation gives 20s, so 5s of upright stillness must
        // NOT confirm.
        sm.onSpeedUpdate(20.0)
        sm.onSample(sample(time = tNow + 1_000, peak = 60.0, smoothed = 30.0))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        sm.onSpeedUpdate(0.0)
        sm.onSample(sample(time = tNow + 2_000, peak = 0.0, smoothed = 9.81,
            raw = 9.81, gyro = 0.1).copy(accelX = 0.0, accelY = 0.0, accelZ = 9.81))
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)

        val tEnter = tNow + 2_000
        var tQuiet = tEnter
        while (tQuiet < tEnter + 5_000L) {
            tQuiet += 20
            val d = sm.onSample(sample(
                time = tQuiet, peak = 0.0, smoothed = 9.81, raw = 9.81, gyro = 0.05,
            ).copy(accelX = 0.0, accelY = 0.0, accelZ = 9.81))
            assertNotEquals(
                "Decision.Confirm fired at t=$tQuiet — accumulator leaked across resumeForRide",
                CrashStateMachine.Decision.Confirm, d
            )
        }
    }

    // ── Orientation: silence duration latches across mid-window drift ────────

    @Test
    fun `silence_check upright duration does not collapse if orientation drifts past 45deg mid-window`() {
        // Revised test design: short upright phase (1s = ~50 samples at 50Hz) so the
        // running average can be flipped past 45° within ~1.5s of strong tilt.
        // With 50 upright (Z=9.81) samples and N tilt (X=9.5,Z=2.5, |mag|≈9.82) samples:
        //   avg_z crosses avg_x at N≈71 (≈1.4s). So 5s of tilt reliably flips the average.
        // Bug behaviour: once running average crosses 45°, computeEffectiveSilenceMs drops
        //   to 4500ms. With silenceStartedMs=2000, elapsed=4500 is satisfied at t=6500,
        //   i.e. during the 5s drift phase (which ends at t=8000). Confirm fires too early.
        // Fix behaviour: duration is latched at 20000ms after MIN_ORIENTATION_SAMPLES in
        //   Phase 1, so Confirm must NOT fire before t = silenceStartedMs + 20000 = 22000.
        val t = Thresholds(
            baselineMinSamples = 10,
            silenceDurationMs = 4_500L,
            silenceDurationUprightMs = 20_000L,
            uprightAngleThresholdDegrees = 45.0,
        )
        val sm = smInSilenceCheckWithBaseline(t)  // baseline along Z, in SILENCE_CHECK
        // silenceStartedMs = 2000 (set by smInSilenceCheckWithBaseline at t=2000)

        // Phase 1: 1s of fully upright stillness — enough for latch to engage (MIN_ORIENTATION_SAMPLES=5).
        var tNow = 2_000L
        val tDriftStart = tNow + 1_000L
        while (tNow < tDriftStart) {
            tNow += 20
            val d = sm.onSample(sample(
                time = tNow, peak = 0.0, smoothed = 9.81, raw = 9.81, gyro = 0.05,
            ).copy(accelX = 0.0, accelY = 0.0, accelZ = 9.81))
            assertNotEquals(
                "Decision.Confirm fired prematurely at t=$tNow (still inside upright window)",
                CrashStateMachine.Decision.Confirm, d
            )
        }

        // Phase 2: 5s of strong tilt (accelX=9.5, accelZ=2.5, |mag|≈9.82 — well within
        // deviation gate). After ~1.4s the cumulative average gravity vector crosses 45°
        // from the baseline. Without the latch, computeEffectiveSilenceMs would drop from
        // 20000ms to 4500ms, and the elapsed-time check (tNow - 2000 >= 4500) would satisfy
        // at tNow=6500 — still inside this drift phase (ends at 8000).
        // With the latch, the 20s duration is frozen and Confirm must NOT fire here.
        val tEnd = tDriftStart + 5_000L  // ends at t=8000, well before the 20s mark (t=22000)
        while (tNow < tEnd) {
            tNow += 20
            // Strong tilt: gravity mainly along X. |v| = sqrt(9.5²+2.5²) ≈ 9.82 ≈ 9.81.
            val d = sm.onSample(sample(
                time = tNow, peak = 0.0, smoothed = 9.82, raw = 9.82, gyro = 0.05,
            ).copy(accelX = 9.5, accelY = 0.0, accelZ = 2.5))
            assertNotEquals(
                "Decision.Confirm fired at t=$tNow — latched upright window collapsed when orientation drifted past 45°",
                CrashStateMachine.Decision.Confirm, d
            )
        }
    }

    // ── Baseline: capped running average adapts to mount remount ─────────────

    @Test
    fun `baseline adapts to new orientation when bike is remounted mid-ride`() {
        val t = Thresholds(baselineMinSamples = 100)
        val (sm, _) = newSm(t)
        // Phase 1: 5000 samples (50x baselineMinSamples) of pure upright Z=9.81.
        // Without the EMA cap, the baseline would be so locked that 200 strong
        // post-remount samples could only nudge it by ~4% — baseline stays
        // essentially "old orientation".
        repeat(5000) { sm.feedBaselineSample(0.0, 0.0, 9.81) }
        val (bx1, by1, bz1) = sm.baselineVector()
        // Baseline should be very close to (0, 0, 9.81) at this point.
        assertEquals(0.0, bx1, 0.01)
        assertEquals(9.81, bz1, 0.01)

        // Phase 2: rider remounts Karoo rotated 45° around Y axis. New gravity
        // direction is (6.94, 0, 6.94) (mag ≈ 9.81). Feed 500 samples
        // (5x baselineMinSamples) of the new orientation.
        repeat(500) { sm.feedBaselineSample(6.94, 0.0, 6.94) }
        val (bx2, _, bz2) = sm.baselineVector()

        // With the EMA cap at 100, alpha = 1/100 = 0.01, half-life ≈ 69 samples.
        // After 500 samples = ~7 half-lives → baseline should be within ~1%
        // of the new orientation (6.94, 0, 6.94).
        //
        // Without the cap, after 5500 total samples the per-sample weight is
        // 1/5500 ≈ 0.0182%, and 500 new samples nudge the average by only
        // ~500*(6.94-0)/5500 ≈ 0.63 on the X axis — baseline X stays around
        // 0.63 instead of approaching 6.94. The test fails on buggy code.
        assertEquals("baseline X should have adapted toward 6.94", 6.94, bx2, 0.5)
        assertEquals("baseline Z should have adapted toward 6.94", 6.94, bz2, 0.5)
    }

    // ── Diagnostics: lastConfirmedSilenceMs reflects the actual window ───────

    @Test
    fun `lastConfirmedSilenceMs reflects upright window when fired`() {
        val t = Thresholds(
            baselineMinSamples = 10,
            silenceDurationMs = 4_500L,
            silenceDurationUprightMs = 20_000L,
            uprightAngleThresholdDegrees = 45.0,
        )
        val sm = smInSilenceCheckWithBaseline(t)

        // Feed upright quiet samples until confirm fires. With the 20s upright
        // window, we need ~1000 samples × 20ms = 20s of stillness.
        var tNow = 2_000L
        var confirmed = false
        while (!confirmed && tNow < 25_000L) {
            tNow += 20
            val d = sm.onSample(sample(
                time = tNow, peak = 0.0, smoothed = 9.81, raw = 9.81, gyro = 0.05,
            ).copy(accelX = 0.0, accelY = 0.0, accelZ = 9.81))
            if (d == CrashStateMachine.Decision.Confirm) confirmed = true
        }
        assertEquals("expected upright window to confirm", true, confirmed)
        assertEquals(20_000L, sm.lastConfirmedSilenceMs)
    }

    @Test
    fun `lastConfirmedSilenceMs reflects legacy window when on-side fired`() {
        val t = Thresholds(
            baselineMinSamples = 10,
            silenceDurationMs = 4_500L,
            silenceDurationUprightMs = 20_000L,
            uprightAngleThresholdDegrees = 45.0,
        )
        val sm = smInSilenceCheckWithBaseline(t)

        // Feed on-side quiet samples until confirm fires (~4.5s).
        var tNow = 2_000L
        var confirmed = false
        while (!confirmed && tNow < 10_000L) {
            tNow += 20
            val d = sm.onSample(sample(
                time = tNow, peak = 0.0, smoothed = 9.81, raw = 9.81, gyro = 0.05,
            ).copy(accelX = 9.81, accelY = 0.0, accelZ = 0.0))
            if (d == CrashStateMachine.Decision.Confirm) confirmed = true
        }
        assertEquals("expected on-side window to confirm", true, confirmed)
        assertEquals(4_500L, sm.lastConfirmedSilenceMs)
    }

    // ── Regression: crash on-side still confirms at 4.5s ─────────────────────

    @Test
    fun `scenario crash with bike on side confirms at 4_5s as before`() {
        val t = Thresholds(
            baselineMinSamples = 10,
            silenceDurationMs = 4_500L,
            silenceDurationUprightMs = 20_000L,
            uprightAngleThresholdDegrees = 45.0,
            minSpeedForCrashKmh = 3,
            crashConfirmSpeedKmh = 3,
        )
        val (sm, _) = newSm(t)

        sm.onSpeedUpdate(30.0)
        repeat(t.baselineMinSamples + 5) {
            sm.feedBaselineSample(0.0, 0.0, 9.81)
        }

        // Crash IMPACT
        sm.onSample(sample(
            time = 1_000L, peak = 80.0, smoothed = 65.0, raw = 80.0, gyro = 6.0,
        ).copy(accelX = 0.0, accelY = 0.0, accelZ = 80.0))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        sm.onSpeedUpdate(0.0)

        // Bike on side: gravity along X, baseline was along Z → angle ≈ 90°.
        // 4.5s of quiet should be enough.
        var tNow = 1_000L
        val step = 20L
        while (tNow < 1_000L + 4_500L + 1_000L) {  // +1s margin to allow IMPACT→SILENCE transition
            tNow += step
            val d = sm.onSample(sample(
                time = tNow, peak = 0.0, smoothed = 9.81, raw = 9.81, gyro = 0.05,
            ).copy(accelX = 9.81, accelY = 0.0, accelZ = 0.0))
            if (d == CrashStateMachine.Decision.Confirm) {
                // Confirmed — assert it happened within the legacy 4.5s + IMPACT slack window.
                assertTrue("confirm too late: tNow=$tNow", tNow <= 1_000L + 4_500L + 1_000L)
                return
            }
        }
        org.junit.Assert.fail("Expected Decision.Confirm within 4.5s + slack of impact, never fired")
    }
}
