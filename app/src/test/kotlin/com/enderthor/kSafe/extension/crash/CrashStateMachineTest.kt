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
        ax: Double = 0.0,
        ay: Double = 0.0,
        az: Double = 0.0,
    ) = SensorSample(
        rawMagnitude = raw,
        smoothedMagnitude = smoothed,
        peakMagnitude = peak,
        gyroMag = gyro,
        timestampMs = time,
        gpsStale = gpsStale,
        accelX = ax,
        accelY = ay,
        accelZ = az,
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

    // ── Orientation: silence-duration selection ──────────────────────────────

    /**
     * Helper: get a state machine into SILENCE_CHECK with a pre-impact reference
     * already injected. Impact at t=1000, SILENCE_CHECK entered at t=2000 (a
     * 1000 ms gap — well within [Thresholds.delayedStopGapMs], so the orientation
     * regime applies). Returns the SM; the caller manages the time cursor.
     */
    private fun smInSilenceCheck(
        thresholds: Thresholds = Thresholds(),
        preRef: PreImpactRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
    ): CrashStateMachine {
        val (sm, _) = newSm(thresholds)
        sm.onSpeedUpdate(20.0)
        // Enter IMPACT.
        sm.onSample(sample(time = 1000, peak = 60.0, smoothed = 30.0))
        sm.setPreImpactReference(preRef)
        // Drive into SILENCE_CHECK: rider stops + accel calms.
        sm.onSpeedUpdate(0.0)
        sm.onSample(sample(time = 2000, peak = 0.0, smoothed = 9.81, raw = 9.81,
            gyro = 0.1))
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        return sm
    }

    // ── Regime tests: gap + pre-impact orientation silence-window decision ───

    // Drive an SM from MONITORING into SILENCE_CHECK with a chosen impact→silence gap.
    // Returns the SM already inside SILENCE_CHECK. Quiet still samples have magnitude
    // ~GRAVITY so the IMPACT→SILENCE gates pass; az defaults to gravity (upright).
    private fun smEnteringSilence(
        gapMs: Long,
        preRef: PreImpactRef,
        silenceAz: Double = 9.81,
        silenceAx: Double = 0.0,
    ): Pair<CrashStateMachine, ClockHandle> {
        val (sm, h) = newSm()
        sm.onSpeedUpdate(20.0)
        // Impact at t=0 (relative); use a high base time to clear the cold-start guard.
        val base = 1_000_000L
        sm.onSample(sample(time = base, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        sm.setPreImpactReference(preRef)
        // Stay in IMPACT until `gapMs` has elapsed: speed still high → speed gate blocks.
        var t = base + 1000L
        while (t < base + gapMs) {
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.1))
            t += 1000L
        }
        // Drop speed so the IMPACT→SILENCE_CHECK gate opens, then one settling sample.
        sm.onSpeedUpdate(0.0)
        sm.onSample(sample(time = base + gapMs, raw = 9.81, smoothed = 9.81, gyro = 0.1,
            ax = silenceAx, az = silenceAz))
        return sm to h
    }

    @Test
    fun `gap regime - long gap forces the 20s window even when bike is on-side`() {
        // On-side silence vector (az≈0, ax≈9.81) would normally give 4.5s, but a
        // 12s gap must override that and require 20s.
        val (sm, _) = smEnteringSilence(
            gapMs = 12_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 0.0, silenceAx = 9.81,
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        // Feed 6 s of continuous stillness — must NOT confirm (20s required).
        var t = 1_012_000L
        repeat(6) {
            t += 1000L
            val d = sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
            assertEquals(CrashStateMachine.Decision.None, d)
        }
    }

    @Test
    fun `orientation regime - prompt stop on-side confirms at 4_5s`() {
        val (sm, _) = smEnteringSilence(
            gapMs = 2_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 0.0, silenceAx = 9.81,   // ~90° from the upright reference
        )
        // Feed stillness; Confirm must arrive once ~4.5 s of silence elapsed.
        var t = 1_002_000L
        var confirmed = false
        repeat(7) {
            t += 1000L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
                    is CrashStateMachine.Decision.Confirm) confirmed = true
        }
        assertTrue("expected Confirm within ~5s of silence", confirmed)
        assertEquals(4_500L, sm.lastConfirmedSilenceMs)
    }

    @Test
    fun `orientation regime - prompt stop upright requires the 20s window`() {
        val (sm, _) = smEnteringSilence(
            gapMs = 2_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 9.81, silenceAx = 0.0,   // same as the reference → upright
        )
        var t = 1_002_000L
        repeat(6) {
            t += 1000L
            val d = sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 9.81, ax = 0.0))
            assertEquals(CrashStateMachine.Decision.None, d)  // 6s < 20s
        }
    }

    @Test
    fun `orientation regime - invalid reference on a prompt stop falls back to 4_5s`() {
        val (sm, _) = smEnteringSilence(
            gapMs = 2_000L,
            preRef = PreImpactRef.INVALID,
            silenceAz = 9.81,
        )
        var t = 1_002_000L
        var confirmed = false
        repeat(7) {
            t += 1000L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 9.81))
                    is CrashStateMachine.Decision.Confirm) confirmed = true
        }
        assertTrue(confirmed)
        assertEquals(4_500L, sm.lastConfirmedSilenceMs)
    }

    // ── Regression: silence-window accumulator resets on silence-break ───────

    @Test
    fun `orientation accumulator resets when stillness is broken`() {
        val t = Thresholds(
            silenceDurationMs = 4_500L,
            silenceDurationUprightMs = 20_000L,
            uprightAngleThresholdDegrees = 45.0,
            minSpeedForCrashKmh = 3,
            crashConfirmSpeedKmh = 3,
        )
        val sm = smInSilenceCheck(t)  // pre-impact ref along Z, currently in SILENCE_CHECK

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

        // Now feed UPRIGHT quiet samples (gravity along Z, matching the upright pre-impact reference).
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
            silenceDurationMs = 4_500L,
            silenceDurationUprightMs = 20_000L,
            uprightAngleThresholdDegrees = 45.0,
        )
        val sm = smInSilenceCheck(t)  // already in SILENCE_CHECK with pre-impact ref along Z

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
        // On-side samples: gravity along X, pre-impact ref along Z → angle ≈ 90° > 45°
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
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))
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

    // ── Lifecycle: resumeForRide resets per-event state ──────────────────────

    @Test
    fun `resumeForRide resets per-event pre-impact state`() {
        val (sm, _) = newSm()
        // Drive an event so per-event state is non-default: enter IMPACT, inject a
        // valid pre-impact reference, then reach SILENCE_CHECK so firstSilenceGapMs > 0.
        sm.onSpeedUpdate(20.0)
        sm.onSample(sample(time = 1000, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))
        sm.onSpeedUpdate(2.0)
        sm.onSample(sample(time = 1600, peak = QUIET, smoothed = QUIET, gyro = 0.5))
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        assertTrue("firstSilenceGapMs should be > 0 after reaching SILENCE_CHECK",
            sm.firstSilenceGapMs > 0L)
        assertTrue("pre-impact reference should be valid before resume",
            sm.preImpactReference.valid)

        // Resume must reset the new-mechanism per-event state.
        sm.resumeForRide()
        assertEquals(CrashStateMachine.State.MONITORING, sm.state)
        assertEquals(0L, sm.firstSilenceGapMs)
        assertEquals(PreImpactRef.INVALID, sm.preImpactReference)
    }

    @Test
    fun `resumeForRide clears the silence-window accumulator`() {
        val t = Thresholds(
            silenceDurationMs = 4_500L,
            silenceDurationUprightMs = 20_000L,
            uprightAngleThresholdDegrees = 45.0,
        )
        val sm = smInSilenceCheck(t)

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
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))
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

    // ── Diagnostics: lastConfirmedSilenceMs reflects the actual window ───────

    @Test
    fun `lastConfirmedSilenceMs reflects legacy window when on-side fired`() {
        val t = Thresholds(
            silenceDurationMs = 4_500L,
            silenceDurationUprightMs = 20_000L,
            uprightAngleThresholdDegrees = 45.0,
        )
        // Pre-impact ref along Z, on-side silence samples along X → angle ≈ 90°.
        val sm = smInSilenceCheck(t)

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
            silenceDurationMs = 4_500L,
            silenceDurationUprightMs = 20_000L,
            uprightAngleThresholdDegrees = 45.0,
            minSpeedForCrashKmh = 3,
            crashConfirmSpeedKmh = 3,
        )
        val (sm, _) = newSm(t)

        sm.onSpeedUpdate(30.0)

        // Crash IMPACT
        sm.onSample(sample(
            time = 1_000L, peak = 80.0, smoothed = 65.0, raw = 80.0, gyro = 6.0,
        ).copy(accelX = 0.0, accelY = 0.0, accelZ = 80.0))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        // Inject a valid upright pre-impact reference (gravity along Z).
        // The silence window will have gravity along X (bike on side) → angle ≈ 90° ≥ 45°
        // → orientation regime selects the on-side (legacy 4.5 s) window.
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))
        sm.onSpeedUpdate(0.0)

        // Bike on side: gravity along X (ax = 9.81, az = 0.0), pre-impact ref was along Z
        // → angle ≈ 90° ≥ uprightAngleThresholdDegrees (45°) → on-side → 4.5 s window.
        var tNow = 1_000L
        val step = 20L
        while (tNow < 1_000L + 4_500L + 1_000L) {  // +1s margin to allow IMPACT→SILENCE transition
            tNow += step
            val d = sm.onSample(sample(
                time = tNow, peak = 0.0, smoothed = 9.81, raw = 9.81, gyro = 0.05,
                ax = 9.81, az = 0.0,
            ))
            if (d == CrashStateMachine.Decision.Confirm) {
                // Confirmed — assert it happened within the legacy 4.5s + IMPACT slack window.
                assertTrue("confirm too late: tNow=$tNow", tNow <= 1_000L + 4_500L + 1_000L)
                return
            }
        }
        org.junit.Assert.fail("Expected Decision.Confirm within 4.5s + slack of impact, never fired")
    }

    // ── Integration: the 60a27a false positive ──────────────────────────────

    @Test
    fun `scenario - bump then 17s ride then upright stop does not confirm at 4_5s`() {
        // Reproduces ksafe_v1.2.0_60a27a_k24.csv event 1: hard spike, 17 s of
        // continued riding, then a still upright stop. Gap regime → 20 s window.
        val (sm, _) = newSm()
        sm.onSpeedUpdate(20.0)
        val base = 1_000_000L
        sm.onSample(sample(time = base, peak = 60.0, smoothed = 30.0, gyro = 0.8))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        // Pre-impact reference: upright (the rider was cruising before the bump).
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))
        // 17 s of riding — speed stays high so IMPACT does not advance.
        var t = base + 1000L
        while (t < base + 17_000L) {
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.2))
            t += 1000L
        }
        // Rider stops, bike upright.
        sm.onSpeedUpdate(0.0)
        sm.onSample(sample(time = base + 17_000L, raw = 9.81, smoothed = 9.81, az = 9.81))
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        // 6 s of perfect stillness must NOT confirm — the 20 s gap window applies.
        t = base + 17_000L
        repeat(6) {
            t += 1000L
            val d = sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 9.81))
            assertEquals(CrashStateMachine.Decision.None, d)
        }
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
    }

    @Test
    fun `scenario - real crash prompt stop on-side confirms at 4_5s`() {
        val (sm, _) = newSm()
        sm.onSpeedUpdate(25.0)
        val base = 1_000_000L
        sm.onSample(sample(time = base, peak = 70.0, smoothed = 40.0, gyro = 3.0))
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))  // was upright
        // Prompt stop ~2 s later, bike on its side (az≈0, ax≈9.81).
        sm.onSpeedUpdate(0.0)
        var t = base + 2_000L
        sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        var confirmed = false
        repeat(7) {
            t += 1000L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
                    is CrashStateMachine.Decision.Confirm) confirmed = true
        }
        assertTrue("on-side prompt stop should confirm at 4.5s", confirmed)
        assertEquals(4_500L, sm.lastConfirmedSilenceMs)
    }

    // ── Latch: upright 20s window must not collapse if orientation drifts ─────

    @Test
    fun `latched 20s upright window does not collapse if orientation drifts past 45deg`() {
        // Scenario: prompt stop (gap = 2 s, well under delayedStopGapMs = 8 s) so the
        // orientation regime — not the gap regime — decides the silence window.
        // Pre-impact reference is upright (gravity along Z). The silence window starts
        // upright (az = 9.81, ax = 0.0) → angle < 45° → orientation regime latches 20 s.
        // Then the gravity vector drifts past 45° (ax ≈ 8.96, az ≈ 4.0, angle ≈ 66°).
        // Without the latch, computeEffectiveSilenceMs would re-evaluate to 4.5 s and
        // the already-elapsed ~6 s of silence would fire a spurious Confirm.
        // With the latch, the 20 s window is frozen and no Confirm fires within 6 s.
        val (sm, _) = smEnteringSilence(
            gapMs = 2_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 9.81, silenceAx = 0.0,  // upright — first sample sets direction
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)

        // Base timestamp right after entering SILENCE_CHECK (impact at base=1_000_000,
        // gap = 2_000 → SILENCE_CHECK entered at 1_002_000).
        var t = 1_002_000L

        // Feed at least MIN_ORIENTATION_SAMPLES (= 5, private const) upright still samples
        // so the orientation regime triggers and latches the 20 s window.
        repeat(5 + 2) {
            t += 200L
            val d = sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81,
                az = 9.81, ax = 0.0))
            assertEquals("should not confirm during upright latch phase", CrashStateMachine.Decision.None, d)
        }

        // Now feed several still samples whose gravity vector has drifted past 45°:
        // ax ≈ 8.96, az ≈ 4.0  →  angle from upright reference ≈ 66° ≥ 45°.
        // The bike is still "still" (magnitude ≈ sqrt(8.96² + 4.0²) ≈ 9.81) so no
        // silence break occurs and the latch must hold the 20 s window.
        repeat(20) {
            t += 200L
            val d = sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81,
                az = 4.0, ax = 8.96))
            assertEquals(
                "Confirm must NOT fire at t=$t — latched 20s window must hold even after orientation drifts past 45°",
                CrashStateMachine.Decision.None, d
            )
        }
        // Sanity: total elapsed so far is (5+2+20)*200 = 5400ms < 20000ms → no confirm expected.
    }
}
