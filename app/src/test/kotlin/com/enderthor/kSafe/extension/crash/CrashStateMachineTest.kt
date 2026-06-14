package com.enderthor.kSafe.extension.crash

import com.enderthor.kSafe.extension.util.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** Build a [SensorSample]. `peak`, `smoothed`, `raw` default to each other.
     *
     *  Note: as of P1 (May 2026) `gpsStale` is no longer on [SensorSample] — push it via
     *  `sm.setSpeedGpsStale(true)` before processing the stale samples. The four
     *  GPS-stale tests in this file follow that pattern (search for `setSpeedGpsStale`).
     */
    private fun sample(
        time: Long,
        peak: Double = 0.0,
        smoothed: Double = peak,
        raw: Double = peak,
        gyro: Double = 0.0,
        ax: Double = 0.0,
        ay: Double = 0.0,
        az: Double = 0.0,
    ) = SensorSample(
        rawMagnitude = raw,
        smoothedMagnitude = smoothed,
        peakMagnitude = peak,
        gyroMag = gyro,
        timestampMs = time,
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
        // Below minSpeed; staleness now pushed via setSpeedGpsStale (P1). Per doc, no bypass at MONITORING entry.
        sm.setSpeedGpsStale(true)
        sm.onSpeedUpdate(2.0)
        val d = sm.onSample(sample(time = 1000, peak = 60.0, smoothed = 30.0, gyro = 0.5))
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
        // get past the MONITORING entry gate at speed=0. Staleness pushed via
        // setSpeedGpsStale (P1) so the IMPACT/SILENCE_CHECK path uses hardened thresholds.
        val (sm, _) = newSm(
            thresholds = Thresholds(crashConfirmSpeedKmh = 0, minSpeedForCrashKmh = 0)
        )
        sm.setSpeedGpsStale(true)
        sm.onSpeedUpdate(0.0)
        sm.onSample(sample(time = 0, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        // Magnitude well inside the GPS-stale 1.5 deviation max (|9.80-9.81|=0.01)
        sm.onSample(sample(time = 600, peak = QUIET, smoothed = QUIET, gyro = 0.5))
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)

        // Just shy of gpsStaleSilenceDurationMs (8000) — must NOT confirm yet.
        val notYet = sm.onSample(sample(time = 8000, peak = QUIET, smoothed = QUIET, gyro = 0.5))
        // silenceStartedMs = 600; elapsed at t=8000 → 7400 < 8000 → no confirm
        assertEquals(CrashStateMachine.Decision.None, notYet)

        // Past the gpsStaleSilenceDurationMs window — should confirm.
        val confirmed = sm.onSample(sample(time = 8700, peak = QUIET, smoothed = QUIET, gyro = 0.5))
        assertEquals(CrashStateMachine.Decision.Confirm, confirmed)
    }

    // ── 7b. SILENCE_CHECK GPS-stale uses stricter deviation max (1.5 m/s²) ───

    @Test
    fun `SILENCE_CHECK GPS-stale rejects samples deviating more than the hardened max`() {
        val (sm, _) = newSm(
            thresholds = Thresholds(crashConfirmSpeedKmh = 0, minSpeedForCrashKmh = 0)
        )
        sm.setSpeedGpsStale(true)
        sm.onSpeedUpdate(0.0)
        sm.onSample(sample(time = 0, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        // Magnitude = 11.5 → deviation = 1.69 > 1.5 (gpsStaleSilenceDeviationMax)
        // Must NOT enter SILENCE_CHECK.
        sm.onSample(sample(time = 600, peak = 11.5, smoothed = 11.5, gyro = 0.5))
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

        // impactWindowMs = 20_000; doubled retry budget = 40_000, measured from
        // SILENCE_CHECK entry (t = 600). Give-up therefore needs now > 40_600.
        // Keep hitting the timer-reset branch within the budget…
        sm.onSample(sample(time = 20_000, peak = 15.0, smoothed = 15.0, gyro = 0.5))
        // …then a !isStill sample BEYOND the entry-relative doubled budget.
        val d = sm.onSample(sample(time = 40_700, peak = 15.0, smoothed = 15.0, gyro = 0.5))
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
        sm.onSample(sample(time = 0, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        handle.advance(1_000)
        sm.onSample(sample(time = 1_500, peak = QUIET, smoothed = QUIET, gyro = 0.5))
        handle.advance(1_500)
        sm.onSample(sample(time = 3_000, peak = QUIET, smoothed = QUIET, gyro = 0.5))
        handle.advance(1_500)
        sm.onSample(sample(time = 4_500, peak = QUIET, smoothed = QUIET, gyro = 0.5))
        handle.advance(1_500)
        // Even after sample-time 6 s and silenceDurationMs (4500) elapsed since entering
        // SILENCE_CHECK, the cold-start guard must still block confirmation because
        // onSpeedUpdate was never called. Wall clock now at ~5.5s — still < 8 s guard.
        val d = sm.onSample(sample(time = 6_000, peak = QUIET, smoothed = QUIET, gyro = 0.5))
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
        impactGyro: Double = 0.5,   // R6-G: peak gyro at the impact sample. Default (0.5, low)
                                    // models a benign jolt; pass > nonGapUprightVetoMaxGyroRadS
                                    // (3.0) to model a tumble/endo (a non-gap upright confirm is
                                    // then NOT vetoed by R6-G).
    ): Pair<CrashStateMachine, ClockHandle> {
        val (sm, h) = newSm()
        sm.onSpeedUpdate(20.0)
        // Impact at t=0 (relative); use a high base time to clear the cold-start guard.
        val base = 1_000_000L
        sm.onSample(sample(time = base, peak = 60.0, smoothed = 30.0, gyro = impactGyro))
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

    // ── R6-F: GAP-regime upright veto (FP #2, 2026-05-31 session 9e5679) ─────
    // A gravel bump → coast to a stop (gap > delayedStopGapMs) → stand motionless
    // and UPRIGHT for 20 s. The gap regime confirms on stillness alone (it never
    // consults orientation); the upright veto must suppress that confirm. Non-
    // upright (on-side) and no-orientation-data stops must still confirm.

    @Test
    fun `R6-F gap regime upright stop is vetoed and does NOT confirm`() {
        val (sm, _) = smEnteringSilence(
            gapMs = 12_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 9.81, silenceAx = 0.0,   // upright, matches the reference → angle ~0°
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        // Feed >20 s of continuous upright stillness. Must NEVER confirm; the veto
        // must instead return to MONITORING with the diagnostic flag set.
        var t = 1_012_000L
        var sawConfirm = false
        var sawVeto = false
        repeat(25) {
            t += 1000L
            val d = sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 9.81, ax = 0.0))
            if (d is CrashStateMachine.Decision.Confirm) sawConfirm = true
            if (d is CrashStateMachine.Decision.ReturnToMonitoring && !sawVeto) {
                sawVeto = true
                assertTrue("veto flag must be set for the GAP_VETO calibration row",
                    sm.lastGapUprightVeto)
                assertTrue("veto angle must be within the tight cone (0 ≤ angle < 25°), was ${sm.lastGapUprightVetoAngleDeg}",
                    sm.lastGapUprightVetoAngleDeg >= 0.0 &&
                    sm.lastGapUprightVetoAngleDeg < 25.0)
            }
        }
        assertFalse("upright delayed stop must NOT confirm (R6-F veto)", sawConfirm)
        assertTrue("veto must fire and return to MONITORING", sawVeto)
        assertEquals(CrashStateMachine.State.MONITORING, sm.state)
    }

    @Test
    fun `R6-F gap regime on-side stop still confirms (veto does not engage)`() {
        val (sm, _) = smEnteringSilence(
            gapMs = 12_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 0.0, silenceAx = 9.81,   // on-side, ~90° from the reference
        )
        var t = 1_012_000L
        var confirmed = false
        repeat(25) {
            t += 1000L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
                    is CrashStateMachine.Decision.Confirm) confirmed = true
        }
        assertTrue("on-side delayed stop must still confirm at 20 s", confirmed)
        assertEquals(20_000L, sm.lastConfirmedSilenceMs)
        assertFalse("veto must NOT fire on an on-side stop", sm.lastGapUprightVeto)
        // Blind-spot fix: a gap-regime confirm must now record the REAL measured
        // silence angle (~90° on-side), not the -1.0 sentinel the old gap regime
        // logged (the FP that motivated R6-F had pre_impact_angle=-1.0).
        assertTrue("gap-regime confirm must record the measured angle (~90°), was ${sm.lastConfirmedAngleDeg}",
            sm.lastConfirmedAngleDeg in 80.0..100.0)
    }

    @Test
    fun `R6-F gap regime tilted-but-not-flat stop still confirms (tighter than the 45 timing cone)`() {
        // A bike tilted ~30° from the riding orientation: OUTSIDE the 25° veto cone but
        // INSIDE the old 45° timing threshold. The veto must NOT fire here — a 30° posture
        // is consistent with a real crash (knocked over, not held upright), so it must
        // still confirm. This pins the deliberate FN-safety choice: the veto only suppresses
        // a near-identical-to-riding orientation, never a clearly-displaced one.
        // Silence vector (ax=4.9, az=8.5): angle from upright ref (0,0,9.81) = acos(8.5/9.81) ≈ 30°.
        val (sm, _) = smEnteringSilence(
            gapMs = 12_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 8.5, silenceAx = 4.9,
        )
        var t = 1_012_000L
        var confirmed = false
        repeat(25) {
            t += 1000L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 8.5, ax = 4.9))
                    is CrashStateMachine.Decision.Confirm) confirmed = true
        }
        assertTrue("a ~30° tilted delayed stop must still confirm (outside the 25° veto cone)", confirmed)
        assertEquals(20_000L, sm.lastConfirmedSilenceMs)
        assertFalse("veto must NOT fire at ~30° (only the ≤25° upright cone is vetoed)", sm.lastGapUprightVeto)
    }

    @Test
    fun `R6-F gap regime 18-6deg tilted stop is vetoed after widening cone to 25deg (field session 2ab57f)`() {
        // Seed: stop-and-stand FP from field session 2ab57f, settled bike tilt ~18.6°.
        // With the old 15° cone: 18.6° > 15° → NOT vetoed → confirms (FP).
        // With the new 25° cone: 18.6° < 25° → VETOED → returns to MONITORING (correct).
        // Silence vector at ~18.6° from upright: ax = 9.81 * sin(18.6°) ≈ 3.13, az = 9.81 * cos(18.6°) ≈ 9.29.
        // acos(9.29 / 9.81) ≈ 18.6°. Magnitude = sqrt(3.13² + 9.29²) ≈ 9.80 ≈ gravity, so isStill fires.
        val (sm, _) = smEnteringSilence(
            gapMs = 12_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 9.29, silenceAx = 3.13,
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        var t = 1_012_000L
        var sawConfirm = false
        var sawVeto = false
        repeat(25) {
            t += 1000L
            val d = sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 9.29, ax = 3.13))
            if (d is CrashStateMachine.Decision.Confirm) sawConfirm = true
            if (d is CrashStateMachine.Decision.ReturnToMonitoring && !sawVeto) {
                sawVeto = true
                assertTrue("veto flag must be set for the 18.6° stop", sm.lastGapUprightVeto)
            }
        }
        assertFalse("18.6° upright stop must NOT confirm after cone widens to 25° (R6-F veto)", sawConfirm)
        assertTrue("veto must fire and return to MONITORING for 18.6° stop", sawVeto)
    }

    @Test
    fun `R6-F gap regime with invalid reference still confirms (no orientation data)`() {
        // Fallback safety net: when orientation is not computable the gap regime
        // keeps its original confirm-on-stillness behaviour.
        val (sm, _) = smEnteringSilence(
            gapMs = 12_000L,
            preRef = PreImpactRef.INVALID,
            silenceAz = 9.81, silenceAx = 0.0,
        )
        var t = 1_012_000L
        var confirmed = false
        repeat(25) {
            t += 1000L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 9.81, ax = 0.0))
                    is CrashStateMachine.Decision.Confirm) confirmed = true
        }
        assertTrue("invalid-ref gap stop must still confirm (orientation unavailable)", confirmed)
        assertEquals(20_000L, sm.lastConfirmedSilenceMs)
    }

    @Test
    fun `R6-F real-data replay - FP session 9e5679 gravel-bump delayed upright stop is vetoed`() {
        // Faithful replay of the field FALSE POSITIVE that motivated R6-F.
        // Source: calibration log session 9e5679 (install f5ca95), 2026-05-31, GRAVEL/MEDIUM.
        //   IMPACT_IN @4384.4s  source=PEAK raw=67.1 speed=25.0  pre=(-0.64, 4.37, 8.94) valid
        //   SIL_IN    @4394.3s  deviation=0.13 gyro=0.11 speed=0  gap_ms=9906
        //   CRASH_OK  @4414.3s  deviation=0.07 silence_path=UPRIGHT decided_by=GAP pre_impact_angle=-1.0
        //   → EMERG_TRIG CRASH_DETECTED → CRASH_NO @4416.4s (rider cancelled in 2.17 s)
        //
        // LIMITATION (and the reason R6-F also adds the GAP_VETO event): the old gap
        // regime never computed orientation, so the log records NO silence-phase gravity
        // vector — only the pre-impact reference. The rider coasted to a stop and stood
        // motionless (speed=0, deviation≈0.1, gyro≈0.1), so the physically-faithful
        // reconstruction is that the bike held its pre-impact orientation through the
        // silence window → silence gravity vector == pre-impact reference → angle ≈ 0°.
        // Under that reconstruction the veto must fire (no CRASH_OK). The angle assertion
        // (≈0°) shows the FP is caught with a wide margin — it would still be vetoed by a
        // much tighter cone than the current 45° threshold.
        val (sm, h) = newSm()
        sm.onSpeedUpdate(25.0)                                   // real IMPACT speed
        val base = 1_000_000L
        sm.onSample(sample(time = base, peak = 67.1, smoothed = 31.3, gyro = 0.31))  // real PEAK impact
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        sm.setPreImpactReference(PreImpactRef(-0.64, 4.37, 8.94, valid = true))       // real pre-impact ref
        // Keep moving for the real 9906 ms impact→stillness gap (speed high → gate blocked).
        var t = base + 1000L
        while (t < base + 9906L) {
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.1))
            t += 1000L
        }
        // Coast to a full stop; bike keeps the pre-impact orientation (rider stands still).
        // raw magnitude 9.97 = |(-0.64,4.37,8.94)| → deviation 0.16, matching the real ~0.1.
        sm.onSpeedUpdate(0.0)
        sm.onSample(sample(time = base + 9906L, raw = 9.97, smoothed = 9.97, gyro = 0.11,
            ax = -0.64, ay = 4.37, az = 8.94))
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        // Stand motionless and upright for the full 20 s window.
        var confirmed = false
        var vetoed = false
        t = base + 9906L
        repeat(22) {
            t += 1000L
            val d = sm.onSample(sample(time = t, raw = 9.97, smoothed = 9.97, gyro = 0.11,
                ax = -0.64, ay = 4.37, az = 8.94))
            if (d is CrashStateMachine.Decision.Confirm) confirmed = true
            if (d is CrashStateMachine.Decision.ReturnToMonitoring && !vetoed) {
                vetoed = true
                assertTrue("real-data silence orientation must read upright (angle≈0°, was ${sm.lastGapUprightVetoAngleDeg})",
                    sm.lastGapUprightVetoAngleDeg in 0.0..5.0)
            }
        }
        assertFalse("FP session 9e5679 must NOT confirm under R6-F", confirmed)
        assertTrue("the veto must fire on the reconstructed real-data scenario", vetoed)
        assertEquals(CrashStateMachine.State.MONITORING, sm.state)
    }

    // ── R6-F: real-data replay — the three v2.0.0 field FPs of 2026-06-06 ────────
    // These three CRASH_OK events came from v2.0.0 logs (the GAP veto did NOT exist
    // yet — it landed post-v2.0.0 on this branch). All three share the FP signature:
    // decided_by=GAP, silence_path=UPRIGHT, speed=0, deviation≈0, pre_impact_angle=-1.0
    // (a v2.0.0 PLACEHOLDER: that build set lastConfirmedAngleDeg=lastOrientationAngleDeg,
    // which is -1.0 in the gap regime — it never measured the gap-regime silence angle).
    // The riders kept riding afterwards (benign stops), yet two of the three were NOT
    // cancelled and the alert dispatched. These tests answer "would v2.1.0 still fire?":
    // under the physically-faithful reconstruction (bike upright pre-impact + dead-still
    // upright through silence ⇒ silence gravity vector == pre-impact reference ⇒ angle
    // ≈ 0°), R6-F vetoes all three. The pre-impact reference vectors are the verbatim
    // pre_x/pre_y/pre_z logged at the impact that led to each confirm.
    //
    // Shared driver: impact → keep moving for the real impact→stillness gap → coast to a
    // stop holding the pre-impact orientation → stand motionless for the 20 s gap window.
    private fun assertField20260606FpIsVetoed(
        impactSpeedKmh: Double,
        impactPeak: Double,
        impactSmoothed: Double,
        impactGyro: Double,
        gapMs: Long,
        preRef: PreImpactRef,
    ) {
        val (sm, _) = newSm()
        sm.onSpeedUpdate(impactSpeedKmh)
        val base = 1_000_000L
        sm.onSample(sample(time = base, peak = impactPeak, smoothed = impactSmoothed, gyro = impactGyro))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        sm.setPreImpactReference(preRef)
        // Silence gravity vector == the pre-impact reference (bike held its orientation).
        val mag = Math.sqrt(preRef.x * preRef.x + preRef.y * preRef.y + preRef.z * preRef.z)
        // Keep moving for the real impact→stillness gap (speed high → IMPACT→SILENCE gate blocked).
        var t = base + 1000L
        while (t < base + gapMs) {
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.1))
            t += 1000L
        }
        // Coast to a full stop; the bike keeps the pre-impact orientation.
        sm.onSpeedUpdate(0.0)
        sm.onSample(sample(time = base + gapMs, raw = mag, smoothed = mag, gyro = 0.11,
            ax = preRef.x, ay = preRef.y, az = preRef.z))
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        // Stand motionless and upright for the full 20 s gap-regime window.
        var confirmed = false
        var vetoed = false
        t = base + gapMs
        repeat(24) {
            t += 1000L
            val d = sm.onSample(sample(time = t, raw = mag, smoothed = mag, gyro = 0.11,
                ax = preRef.x, ay = preRef.y, az = preRef.z))
            if (d is CrashStateMachine.Decision.Confirm) confirmed = true
            if (d is CrashStateMachine.Decision.ReturnToMonitoring && !vetoed) {
                vetoed = true
                assertTrue("silence orientation must read upright (angle≈0°, was ${sm.lastGapUprightVetoAngleDeg})",
                    sm.lastGapUprightVetoAngleDeg in 0.0..5.0)
                assertTrue("veto regime must be GAP", sm.lastUprightVetoGapRegime)
                // Blind-spot fix: the silence orientation vector must now be recorded
                // (NaN/-1.0 was the v2.0.0 blind spot) and match the fed silence gravity.
                assertEquals("sil_z must record the silence orientation", preRef.z, sm.lastSilenceOrientZ, 0.05)
                assertEquals("sil_y must record the silence orientation", preRef.y, sm.lastSilenceOrientY, 0.05)
                assertEquals("sil_x must record the silence orientation", preRef.x, sm.lastSilenceOrientX, 0.05)
            }
        }
        assertFalse("v2.0.0 field FP must NOT confirm under v2.1.0 R6-F", confirmed)
        assertTrue("R6-F must veto this reconstructed v2.0.0 FP", vetoed)
        assertEquals(CrashStateMachine.State.MONITORING, sm.state)
    }

    @Test
    fun `R6-F field replay - 2026-06-06 FP f5ca95 gravel delayed upright stop is vetoed`() {
        // IMPACT_IN @2420.6s raw=58.1 smooth=28.2 speed=19.3 gyro=1.01 pre=(-0.67,3.61,9.50)
        // SIL_IN @2435.7s gap_ms=15096 ; CRASH_OK @2457.8s decided_by=GAP pre_impact_angle=-1.0
        assertField20260606FpIsVetoed(
            impactSpeedKmh = 19.3, impactPeak = 58.1, impactSmoothed = 28.2, impactGyro = 1.01,
            gapMs = 15_096L, preRef = PreImpactRef(-0.67, 3.61, 9.50, valid = true),
        )
    }

    @Test
    fun `R6-F field replay - 2026-06-06 FP bd5fc1 road delayed upright stop is vetoed`() {
        // IMPACT_IN @4579.3s raw=51.1 smooth=21.0 speed=26.9 gyro=0.16 pre=(-0.22,0.34,9.81)
        // SIL_IN @4589.3s gap_ms=10000 ; CRASH_OK @4609.4s decided_by=GAP pre_impact_angle=-1.0
        // This is the one that was NOT cancelled → alert dispatched → ALERT_FAIL CallMeBot.
        assertField20260606FpIsVetoed(
            impactSpeedKmh = 26.9, impactPeak = 51.1, impactSmoothed = 21.0, impactGyro = 0.16,
            gapMs = 10_000L, preRef = PreImpactRef(-0.22, 0.34, 9.81, valid = true),
        )
    }

    @Test
    fun `R6-F field replay - 2026-06-06 FP 786c16 road delayed upright stop is vetoed`() {
        // IMPACT_IN @11914.6s raw=80.5 smooth=46.0 speed=15.8 gyro=1.70 pre=(1.01,1.04,10.05)
        // SIL_IN @11926.9s gap_ms=12247 ; CRASH_OK @11947.4s decided_by=GAP pre_impact_angle=-1.0
        // (gap regime ignores gyro, so the 1.70 rad/s impact rotation does not block the veto.)
        assertField20260606FpIsVetoed(
            impactSpeedKmh = 15.8, impactPeak = 80.5, impactSmoothed = 46.0, impactGyro = 1.70,
            gapMs = 12_247L, preRef = PreImpactRef(1.01, 1.04, 10.05, valid = true),
        )
    }

    @Test
    fun `gap-regime confirm records the silence orientation vector (sil_x_y_z blind-spot fix)`() {
        // The snapshot must be captured on the CONFIRM path too, not only the veto path,
        // so a genuine on-side crash logs WHERE the bike was (sil_x/y/z) — the v2.0.0 gap
        // regime recorded neither the angle nor the vector. On-side silence (ax≈9.81,
        // az≈0) confirms at 20 s; the recorded vector must match the fed silence gravity.
        val (sm, _) = smEnteringSilence(
            gapMs = 12_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 0.0, silenceAx = 9.81,   // on-side → confirms (outside the 25° GAP cone)
        )
        var t = 1_012_000L
        var confirmed = false
        repeat(25) {
            t += 1000L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
                    is CrashStateMachine.Decision.Confirm) confirmed = true
        }
        assertTrue("on-side gap stop must confirm at 20 s", confirmed)
        assertEquals("sil_x must record the on-side silence orientation", 9.81, sm.lastSilenceOrientX, 0.05)
        assertEquals("sil_z must record the on-side silence orientation", 0.0, sm.lastSilenceOrientZ, 0.05)
    }

    // ── R6-F: exact veto-cone boundary for the GAP regime (gapVetoUprightAngleDeg = 25°) ──
    // These three tests pin the EXACT edge so a future change to the constant, or to
    // currentOrientationAngleDeg's geometry, cannot drift it unnoticed — the single most
    // important regression guard for a check that SUPPRESSES an SOS.
    // ~24° (just inside 25°) → vetoed; ~26° (just outside 25°) → confirms; ~30° still confirms.

    @Test
    fun `R6-F gap regime 24deg tilt is vetoed (just inside the 25deg GAP cone)`() {
        // Silence vector (ax=9.81*sin(24°), az=9.81*cos(24°)) = (ax≈3.99, az≈8.96):
        // angle from upright ref (0,0,9.81) = acos(8.96/9.81) ≈ 24.0° — just INSIDE the
        // 25° GAP cone → must veto. Pins the upper edge so widening the constant is caught.
        val ax24 = (9.81 * Math.sin(Math.toRadians(24.0))).toFloat().toDouble()  // ≈ 3.99
        val az24 = (9.81 * Math.cos(Math.toRadians(24.0))).toFloat().toDouble()  // ≈ 8.96
        val (sm, _) = smEnteringSilence(
            gapMs = 12_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = az24, silenceAx = ax24,
        )
        var t = 1_012_000L
        var sawConfirm = false
        var sawVeto = false
        repeat(25) {
            t += 1000L
            val d = sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = az24, ax = ax24))
            if (d is CrashStateMachine.Decision.Confirm) sawConfirm = true
            if (d is CrashStateMachine.Decision.ReturnToMonitoring && !sawVeto) {
                sawVeto = true
                assertTrue("veto angle must sit just inside the 25° GAP cone, was ${sm.lastGapUprightVetoAngleDeg}",
                    sm.lastGapUprightVetoAngleDeg in 22.0..25.0)
            }
        }
        assertFalse("a ~24° GAP delayed stop is inside the cone → must NOT confirm", sawConfirm)
        assertTrue("the veto must fire just inside 25° (GAP cone)", sawVeto)
        assertEquals(CrashStateMachine.State.MONITORING, sm.state)
    }

    @Test
    fun `R6-F gap regime 26deg tilt confirms (just outside the 25deg GAP cone)`() {
        // Silence vector at ~26°: ax = 9.81*sin(26°) ≈ 4.30, az = 9.81*cos(26°) ≈ 8.81.
        // acos(8.81/9.81) ≈ 26° — just OUTSIDE the 25° GAP cone → must confirm (not vetoed).
        // Pins the lower edge so tightening the constant (accidentally blocking crashes) is caught.
        val ax26 = (9.81 * Math.sin(Math.toRadians(26.0))).toFloat().toDouble()  // ≈ 4.30
        val az26 = (9.81 * Math.cos(Math.toRadians(26.0))).toFloat().toDouble()  // ≈ 8.81
        val (sm, _) = smEnteringSilence(
            gapMs = 12_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = az26, silenceAx = ax26,
        )
        var t = 1_012_000L
        var confirmed = false
        repeat(25) {
            t += 1000L
            val d = sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = az26, ax = ax26))
            if (d is CrashStateMachine.Decision.Confirm) confirmed = true
            assertFalse("a ~26° GAP stop must NOT be vetoed (outside the 25° cone)", sm.lastGapUprightVeto)
        }
        assertTrue("a ~26° GAP delayed stop must confirm at the 20 s window", confirmed)
    }

    @Test
    fun `R6-F gap regime 16deg tilt is vetoed (inside the 25deg GAP cone)`() {
        // Silence vector (ax=2.70, az=9.43): angle ≈ 16.0° — INSIDE the 25° GAP cone → VETOED.
        // Domain rationale: a real crash always tips the bike well past 25°; 16° is
        // consistent with a resting conscious rider, not a crash victim.
        val (sm, _) = smEnteringSilence(
            gapMs = 12_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 9.43, silenceAx = 2.70,
        )
        var t = 1_012_000L
        var sawConfirm = false
        var sawVeto = false
        repeat(25) {
            t += 1000L
            val d = sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 9.43, ax = 2.70))
            if (d is CrashStateMachine.Decision.Confirm) sawConfirm = true
            if (d is CrashStateMachine.Decision.ReturnToMonitoring && !sawVeto) {
                sawVeto = true
                assertTrue("veto flag must be set for the 16° GAP stop", sm.lastGapUprightVeto)
            }
        }
        assertFalse("a ~16° delayed stop must NOT confirm (R6-F veto, inside the 25° GAP cone)", sawConfirm)
        assertTrue("veto must fire at ~16° (inside the 25° GAP cone)", sawVeto)
    }

    // ── R6-G: PROMPT cone boundary — 20° confirms (outside the 15° PROMPT cone, FN safety pin) ──

    @Test
    fun `R6-G prompt regime 20deg tilt confirms (outside the 15deg PROMPT cone - FN safety pin)`() {
        // FN-safety pin: a PROMPT-regime stop (short gap, 2 s) with the bike tilted ~20° and
        // LOW gyro (0.5 rad/s — below the 3.0 veto gate). 20° is OUTSIDE the 15° PROMPT cone,
        // so R6-G must NOT veto it — it must CONFIRM at the 20 s upright window.
        // This pins that the PROMPT cone remained at 15° (not accidentally widened to 25°).
        // Silence vector at ~20°: ax = 9.81*sin(20°) ≈ 3.36, az = 9.81*cos(20°) ≈ 9.22.
        // acos(9.22/9.81) ≈ 20.0°.
        val ax20 = (9.81 * Math.sin(Math.toRadians(20.0))).toFloat().toDouble()  // ≈ 3.36
        val az20 = (9.81 * Math.cos(Math.toRadians(20.0))).toFloat().toDouble()  // ≈ 9.22
        val (sm, _) = smEnteringSilence(
            gapMs = 2_000L,                       // prompt stop — non-gap regime (short gap)
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = az20, silenceAx = ax20,
            impactGyro = 0.5,                     // low rotation — below the 3.0 veto gate
        )
        var t = 1_002_000L
        var confirmed = false
        repeat(25) {
            t += 1000L
            val d = sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = az20, ax = ax20))
            if (d is CrashStateMachine.Decision.Confirm) confirmed = true
            assertFalse("a ~20° PROMPT stop must NOT be vetoed (outside the 15° PROMPT cone)",
                sm.lastGapUprightVeto)
        }
        assertTrue("a ~20° PROMPT stop with low gyro must confirm (FN safety pin — PROMPT cone = 15°, not 25°)",
            confirmed)
    }

    // ── R6-F: the veto is gap-regime-only — a non-gap (prompt) stop is never vetoed ──

    @Test
    fun `R6-G non-gap upright stop with low rotation is vetoed (effa0e FP)`() {
        // 2026-06-03 session effa0e: bump at 12 km/h → coast to a stop in ~7 s (gap <
        // delayedStopGapMs → prompt-stop / non-gap regime) → stand motionless and UPRIGHT
        // (~2°) for the full 20 s window, no pedalling, peak gyro ≈ 1.7 rad/s (no tumble).
        // Pre-R6-G this confirmed (FP — the rider cancelled in 3.5 s). R6-G now vetoes it:
        // an upright prompt stop with no violent rotation is a balanced-conscious stand.
        val (sm, _) = smEnteringSilence(
            gapMs = 7_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 9.81, silenceAx = 0.0,   // upright, ≈ pre-impact reference → angle ~0°
            impactGyro = 1.7,                     // benign jolt — below the 3.0 veto gate
        )
        var t = 1_007_000L
        var decision: CrashStateMachine.Decision? = null
        var vetoFired = false
        var vetoRegimeGap = true
        repeat(25) {
            // lastGapUprightVeto is a per-sample edge cleared at the top of each onSample,
            // so snapshot it AT the firing sample, not after the loop. Stop feeding once the
            // terminal decision lands (the veto returns to MONITORING).
            if (decision == null) {
                t += 1000L
                val d = sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 9.81, ax = 0.0))
                if (d != CrashStateMachine.Decision.None) {
                    decision = d
                    vetoFired = sm.lastGapUprightVeto
                    vetoRegimeGap = sm.lastUprightVetoGapRegime
                }
            }
        }
        assertEquals("low-rotation upright prompt stop must be vetoed, not confirmed",
            CrashStateMachine.Decision.ReturnToMonitoring, decision)
        assertTrue("the upright veto must have fired", vetoFired)
        assertFalse("veto regime must be PROMPT (non-gap), not GAP", vetoRegimeGap)
    }

    @Test
    fun `R6-G non-gap upright stop with high rotation still confirms (endo not vetoed)`() {
        // FN protection: a real over-the-bars / endo that ends wheels-up (≈ upright) spikes
        // the gyro. The veto must NOT engage — the SOS must fire. Same geometry as the FP
        // above; only the impact rotation differs (9.0 vs 1.7 rad/s).
        val (sm, _) = smEnteringSilence(
            gapMs = 7_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 9.81, silenceAx = 0.0,
            impactGyro = 9.0,                     // tumble — above the 3.0 veto gate
        )
        var t = 1_007_000L
        var confirmed = false
        repeat(25) {
            t += 1000L
            val d = sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 9.81, ax = 0.0))
            if (d is CrashStateMachine.Decision.Confirm) confirmed = true
            assertFalse("a high-rotation (endo) upright stop must NOT be vetoed", sm.lastGapUprightVeto)
        }
        assertTrue("high-rotation upright prompt stop must confirm at the 20 s window", confirmed)
        assertEquals(20_000L, sm.lastConfirmedSilenceMs)
    }

    @Test
    fun `R6-G non-gap on-side stop with low rotation still confirms (cone protects on-side)`() {
        // FN protection (load-bearing): a real crash that ends ON ITS SIDE (~90°) must confirm
        // even with a calm post-impact (low gyro). The 15° PROMPT-regime upright cone excludes
        // it from the veto BEFORE the gyro gate is even consulted — on-side is the most common
        // real-crash orientation, so this is the primary guard that R6-G did not narrow coverage.
        val (sm, _) = smEnteringSilence(
            gapMs = 2_000L,                       // prompt stop — non-gap regime
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 0.0, silenceAx = 9.81,    // on-side ≈ 90° → outside the 15° PROMPT cone
            impactGyro = 0.5,                      // low rotation — must NOT rescue it from confirming
        )
        var t = 1_002_000L
        var confirmed = false
        repeat(7) {
            t += 1000L
            val d = sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
            if (d is CrashStateMachine.Decision.Confirm) confirmed = true
            assertFalse("an on-side stop must NEVER be vetoed, regardless of rotation",
                sm.lastGapUprightVeto)
        }
        assertTrue("on-side prompt stop must confirm at the 4.5 s window", confirmed)
        assertEquals(4_500L, sm.lastConfirmedSilenceMs)
    }

    // ── R6-F: on-side IMPACT-relaxation (CR3) carry-forward must not be vetoed ──

    @Test
    fun `R6-F on-side relaxation carry-forward into the gap regime still confirms (CR3 not vetoed)`() {
        // Enters SILENCE_CHECK via the IMPACT on-side relaxation path (NOT the speed-drop
        // path the other R6-F tests use), which DELIBERATELY carries the IMPACT orientation
        // accumulator forward. With a long impact→stillness gap the gap regime applies, so
        // the veto's currentOrientationAngleDeg() reads the carried-forward + silence
        // accumulator. A genuine on-side rolling crash keeps that average on-side (~90°), so
        // the veto must NOT engage and the SOS confirms. Guards the "do not reset the
        // accumulator on the CR3 entry" invariant the state-machine comment warns about.
        val (sm, _) = newSm()
        sm.onSpeedUpdate(25.0)                        // ≥ minSpeedForCrashKmh at impact entry
        val base = 1_000_000L
        sm.onSample(sample(time = base, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))
        sm.onSpeedUpdate(10.0)                         // rolling: 5 (crashConfirm) < 10 < 25 (ceiling)
        // Feed on-side accel-still samples at a coarse 400 ms cadence so the 25-sample
        // relaxation threshold is crossed only after ~10 s → firstSilenceGapMs > 8 s (the
        // gap regime), while staying inside the 20 s impact window.
        var t = base + 1000L
        var entered = false
        repeat(40) {
            if (!entered) {
                t += 400L
                sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.5, az = 0.0, ax = 9.81))
                if (sm.state == CrashStateMachine.State.SILENCE_CHECK) entered = true
            }
        }
        assertTrue("IMPACT on-side relaxation must reach SILENCE_CHECK", entered)
        // Stand on-side and still for the full 20 s window.
        var confirmed = false
        repeat(25) {
            t += 1000L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
                    is CrashStateMachine.Decision.Confirm) confirmed = true
        }
        assertTrue("an on-side rolling crash (carry-forward, gap regime) must still confirm", confirmed)
        assertEquals("gap regime must drive the 20 s window", 20_000L, sm.lastConfirmedSilenceMs)
        assertFalse("the veto must NOT engage on a genuine on-side crash", sm.lastGapUprightVeto)
    }

    // ── Regression: silence-window accumulator resets on silence-break ───────

    @Test
    fun `orientation accumulator resets when stillness is broken in the upright regime`() {
        // Original "leak protection" regression. CR2 (May 2026) introduced an
        // asymmetric within-budget reset: an on-side latch survives a small
        // bump, while the upright and gap regimes still get a full reset.
        // This test pins the UPRIGHT-regime side of that asymmetry — orientation
        // pollution across a silence-break must still flush so that a recovering
        // rider switching from upright to on-side can re-latch the appropriate
        // window. The on-side side of the asymmetry is covered by the CR2 tests
        // further down (`within-budget break preserves on-side latch and accumulator`
        // et al).
        val t = Thresholds(
            silenceDurationMs = 4_500L,
            silenceDurationUprightMs = 20_000L,
            uprightAngleThresholdDegrees = 45.0,
            minSpeedForCrashKmh = 3,
            crashConfirmSpeedKmh = 3,
        )
        val sm = smInSilenceCheck(t)  // pre-impact ref along Z, currently in SILENCE_CHECK

        // Feed ~1s of UPRIGHT samples so the orientation accumulator points along Z.
        // Angle vs the upright pre-impact reference is ~0° → upright regime locks
        // the 20 s window. `lastOrientationAngleDeg` stays well below the 60°
        // on-side relaxation gate, so the CR2 preservation does NOT engage.
        var tNow = 2_000L
        repeat(50) {
            tNow += 20
            sm.onSample(sample(
                time = tNow, peak = 0.0, smoothed = 9.81, raw = 9.81, gyro = 0.05,
            ).copy(accelX = 0.0, accelY = 0.0, accelZ = 9.81))
        }

        // Inject a spike (deviation > 4) to break stillness. Upright latch was in
        // force at the moment of the break → resetSilenceWindow() must fire and
        // drop accumulator + latch (the CR2 preservation only kicks in when the
        // previous latch was decisively on-side).
        tNow += 20
        sm.onSample(sample(
            time = tNow, peak = 15.0, smoothed = 15.0, raw = 15.0, gyro = 0.5,
        ).copy(accelX = 0.0, accelY = 0.0, accelZ = 9.81))

        // Now feed ON-SIDE quiet samples (gravity along X). With the upright
        // accumulator wiped, the orientation immediately rebuilds along X →
        // angle ~90° → orientation regime latches the 4.5 s on-side window →
        // Confirm fires ~4.5 s after the bump. If the accumulator had leaked
        // its upright Z history, the angle would stay below 45° for a long
        // time, the 20 s window would be re-latched, and no Confirm would fire
        // within 5.5 s.
        val tBreak = tNow
        var confirmed = false
        while (!confirmed && tNow < tBreak + 5_500L) {
            tNow += 20
            val d = sm.onSample(sample(
                time = tNow, peak = 0.0, smoothed = 9.81, raw = 9.81, gyro = 0.05,
            ).copy(accelX = 9.81, accelY = 0.0, accelZ = 0.0))
            if (d is CrashStateMachine.Decision.Confirm) confirmed = true
        }
        assertTrue(
            "Confirm must fire within 5.5 s of the bump — upright accumulator must have " +
                "been flushed so the new on-side posture can latch the 4.5 s window",
            confirmed,
        )
        assertEquals(
            "Confirm must use the on-side 4.5 s window after the upright accumulator flush",
            4_500L, sm.lastConfirmedSilenceMs,
        )
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

    // ── Regression: false-alarm retry budget anchored to SILENCE_CHECK entry ─

    /**
     * Drive the SM from MONITORING into SILENCE_CHECK with an explicit impact→silence
     * gap, choosing thresholds where the gap regime engages (so the 20 s window is in
     * force). `impactWindowMs` is small (4 s) so `impactWindowMs * 2` arithmetic is
     * easy; `delayedStopGapMs` is small (1 s) so any gap > 1 s engages the gap regime
     * without needing IMPACT to survive longer than its 4 s timeout.
     *
     * Impact fires at `base`; the rider keeps moving (speed high → IMPACT→SILENCE gate
     * blocked) until `base + gapMs`, where one settling sample drops speed and crosses
     * into SILENCE_CHECK. Returns the SM already in SILENCE_CHECK plus `base`.
     */
    private fun smEnteringSilenceGapRegime(
        gapMs: Long,
    ): Pair<CrashStateMachine, Long> {
        val thr = Thresholds(impactWindowMs = 4_000L, delayedStopGapMs = 1_000L)
        val (sm, _) = newSm(thr)
        sm.onSpeedUpdate(20.0)
        val base = 1_000_000L
        sm.onSample(sample(time = base, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))
        // Stay in IMPACT until `gapMs` elapsed: speed high → speed-drop gate blocks.
        var t = base + 500L
        while (t < base + gapMs) {
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.1))
            t += 500L
        }
        // Drop speed; one settling sample crosses IMPACT → SILENCE_CHECK.
        sm.onSpeedUpdate(0.0)
        sm.onSample(sample(time = base + gapMs, raw = 9.81, smoothed = 9.81, gyro = 0.1,
            az = 9.81))
        return sm to base
    }

    @Test
    fun `false-alarm break past impact-relative cutoff but within entry budget is a retry not a give-up`() {
        // Test A — the regression. impactWindowMs = 4_000 → doubled budget = 8_000.
        // delayedStopGapMs = 1_000 → a 2_000 ms gap engages the gap regime (20 s window).
        // SILENCE_CHECK entered at base + 2_000.
        val (sm, base) = smEnteringSilenceGapRegime(gapMs = 2_000L)
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)

        // A non-still sample at base + 9_000:
        //   impact-relative  = 9_000 > 8_000  → OLD code: give up → ReturnToMonitoring
        //   entry-relative   = 7_000 ≤ 8_000  → NEW code: retry  → stay SILENCE_CHECK
        val d = sm.onSample(sample(time = base + 9_000L, peak = 15.0, smoothed = 15.0,
            raw = 15.0, gyro = 0.5))
        assertNotEquals(
            "a stillness break within the entry-relative retry budget must be a retry, " +
                "not a give-up — dropping a real crash here is a false negative",
            CrashStateMachine.Decision.ReturnToMonitoring, d
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
    }

    @Test
    fun `still rider in late-entry long-gap scenario still confirms with the 20s window`() {
        // Test B — the genuine crash. Same gap-regime setup; a still rider must still
        // CONFIRM, with the 20 s gap window recorded. The silence orientation is ON-SIDE
        // (az≈0, ax≈9.81 vs the upright pre-impact reference → ~90°): a real crash leaves
        // the bike down, which the R6-F upright veto allows to confirm. The benign
        // UPRIGHT delayed-stop (the FP R6-F suppresses) is covered separately by
        // `R6-F gap regime upright stop is vetoed and does NOT confirm`.
        val (sm, base) = smEnteringSilenceGapRegime(gapMs = 2_000L)
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        // SILENCE_CHECK entered at base + 2_000; 20 s window → confirm at >= base + 22_000.
        var confirmed = false
        var t = base + 2_000L
        while (!confirmed && t < base + 30_000L) {
            t += 1_000L
            val d = sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
            if (d == CrashStateMachine.Decision.Confirm) confirmed = true
        }
        assertTrue("a continuously-still on-side rider must still confirm in the gap regime", confirmed)
        assertEquals(20_000L, sm.lastConfirmedSilenceMs)
    }

    @Test
    fun `false-alarm break past the entry-relative budget still returns to MONITORING`() {
        // Test C — the give-up path still works. A non-still sample beyond
        // impactWindowMs * 2 of SILENCE_CHECK entry must still give up.
        val (sm, base) = smEnteringSilenceGapRegime(gapMs = 2_000L)
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)

        // Keep the silence clock alive with a retry inside the budget first.
        sm.onSample(sample(time = base + 6_000L, peak = 15.0, smoothed = 15.0,
            raw = 15.0, gyro = 0.5))
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)

        // Non-still sample at base + 11_000: entry-relative = 9_000 > 8_000 → give up.
        val d = sm.onSample(sample(time = base + 11_000L, peak = 15.0, smoothed = 15.0,
            raw = 15.0, gyro = 0.5))
        assertEquals(CrashStateMachine.Decision.ReturnToMonitoring, d)
        assertEquals(CrashStateMachine.State.MONITORING, sm.state)
    }

    // ── New regression tests ─────────────────────────────────────────────────

    // ── R1. GPS-stale + orientation regime: on-side picks gpsStaleSilenceDurationMs ─

    @Test
    fun `GPS-stale on-side orientation regime uses gpsStaleSilenceDurationMs not silenceDurationMs`() {
        // Covers the combination that had NO test: gpsStale=true AND the orientation regime.
        // When gpsStale=true, legacyShort = gpsStaleSilenceDurationMs (8000), not silenceDurationMs
        // (4500). An on-side silence vector (angle ≥ 45° from the upright pre-impact ref) should
        // route through the legacyShort branch and pick 8000, proving the GPS-stale value is used.
        //
        // Setup mirrors the existing GPS-stale tests: crashConfirmSpeedKmh=0 + minSpeedForCrashKmh=0
        // so the MONITORING entry gate passes at speed=0, and gpsStale bypasses the confirm gate.
        // Silence samples use QUIET magnitude (|9.80-9.81|=0.01 ≤ gpsStaleSilenceDeviationMax 1.5)
        // so isStill=true passes the hardened deviation check.
        val (sm, _) = newSm(
            thresholds = Thresholds(crashConfirmSpeedKmh = 0, minSpeedForCrashKmh = 0)
        )
        sm.setSpeedGpsStale(true)
        sm.onSpeedUpdate(0.0)

        // Impact at t=0 (gpsStale=true via setSpeedGpsStale above).
        sm.onSample(sample(time = 0, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)

        // Inject a valid upright pre-impact reference (gravity along Z — same as smEnteringSilence).
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))

        // Prompt stop at t=2000 (gap = 2000 ms < delayedStopGapMs = 8000) → orientation regime.
        // On-side silence vector: gravity along X (ax=9.81, az=0) → angle ≈ 90° ≥ 45°
        // → on-side branch → legacyShort = gpsStaleSilenceDurationMs (8000).
        // Magnitude = QUIET = 9.80; deviation = 0.01 ≤ gpsStaleSilenceDeviationMax (1.5) → isStill.
        sm.onSample(sample(time = 2000, peak = QUIET, smoothed = QUIET, gyro = 0.5,
            ax = 9.81, ay = 0.0, az = 0.0))
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)

        // Accumulate well above MIN_ORIENTATION_SAMPLES (5) on-side still samples
        // so the orientation regime latches legacyShort=8000 (not 4500).
        var tNow = 2000L
        repeat(8) {
            tNow += 200L
            sm.onSample(sample(time = tNow, peak = QUIET, smoothed = QUIET, gyro = 0.1,
                ax = 9.81, ay = 0.0, az = 0.0))
        }

        // silenceStartedMs = 2000; 4500ms would be at t=6500. Feed a sample at t=7000
        // (elapsed = 5000 ms) — must NOT confirm (8000 ms window required).
        val notYet = sm.onSample(sample(time = 7000, peak = QUIET, smoothed = QUIET, gyro = 0.1,
            ax = 9.81, ay = 0.0, az = 0.0))
        assertEquals("must not confirm before 8 s with GPS-stale on-side orientation",
            CrashStateMachine.Decision.None, notYet)

        // Past 8000 ms of silence: t=2000+8000+100=10100 → elapsed=8100 ≥ 8000 → Confirm.
        var confirmed = false
        while (tNow < 12_000L) {
            tNow += 200L
            if (sm.onSample(sample(time = tNow, peak = QUIET, smoothed = QUIET, gyro = 0.1,
                    ax = 9.81, ay = 0.0, az = 0.0)) is CrashStateMachine.Decision.Confirm) {
                confirmed = true
                break
            }
        }
        assertTrue("GPS-stale on-side orientation regime must confirm", confirmed)
        assertEquals(
            "lastConfirmedSilenceMs must be gpsStaleSilenceDurationMs (8000), not silenceDurationMs (4500)",
            8000L, sm.lastConfirmedSilenceMs
        )
    }

    // ── R2. MIN_ORIENTATION_SAMPLES boundary: upright vector latches the 20s window ─

    @Test
    fun `orientation regime upright prompt stop with valid reference confirms at 20s window`() {
        // Guards the MIN_ORIENTATION_SAMPLES (= 5, private const) boundary.
        //
        // With a valid pre-impact reference and a PROMPT stop (gap < delayedStopGapMs),
        // the orientation regime must classify the silence vector and choose the appropriate
        // window. When the silence-window gravity vector is UPRIGHT (matching the pre-impact
        // reference, angle < uprightAngleThresholdDegrees = 45°), the SM must require the
        // 20s silence window — NOT the 4.5s legacy window.
        //
        // The practical observable through the public API: feed well above MIN_ORIENTATION_SAMPLES
        // upright still samples, and assert that:
        //   a) the SM does NOT confirm before 20 s have elapsed (ruling out 4.5s path), and
        //   b) it DOES confirm once 20 s of continuous stillness have elapsed.
        //
        // Boundary note: before MIN_ORIENTATION_SAMPLES (5) upright samples have accumulated
        // the state machine returns legacyShort without latching (may still grow). Once the
        // 5th qualifying still sample lands, computeEffectiveSilenceMs computes the angle
        // and latches the chosen window. This test drives well past that boundary to confirm
        // the latch holds the 20s window for the full silence period.
        // R6-G: a HIGH-rotation impact (endo/tumble that ends wheels-up ≈ upright) — so the
        // non-gap upright veto does NOT engage and the window-selection observable (confirm at
        // 20s) still holds. This doubles as an FN-protection test: a real over-the-bars crash
        // ending upright must still confirm. The low-rotation (benign-stand) counterpart is
        // vetoed — see the dedicated R6-G tests below.
        val (sm, _) = smEnteringSilence(
            gapMs = 2_000L,                                 // prompt stop — orientation regime
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 9.81, silenceAx = 0.0,              // upright → angle ≈ 0° < 45° → 20s
            impactGyro = 9.0,                               // tumble → above the 3.0 veto gate
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)

        // Feed 8 s of continuous upright stillness (well above MIN_ORIENTATION_SAMPLES = 5).
        // None of these samples must trigger Confirm — 8s < 20s window.
        var t = 1_002_000L
        repeat(8) {
            t += 1_000L
            val d = sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 9.81, ax = 0.0))
            assertEquals(
                "must not confirm at t=$t — upright orientation requires 20s window (not 4.5s)",
                CrashStateMachine.Decision.None, d
            )
        }

        // Now drive past 20 s of silence and assert Confirm fires.
        var confirmed = false
        while (t < 1_002_000L + 22_000L) {
            t += 1_000L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 9.81, ax = 0.0))
                    is CrashStateMachine.Decision.Confirm) {
                confirmed = true
                break
            }
        }
        assertTrue("upright prompt stop must confirm at the 20s window", confirmed)
        assertEquals(20_000L, sm.lastConfirmedSilenceMs)
    }

    // ── R3. setPreImpactReference after onPause takes effect on the next event ─

    @Test
    fun `setPreImpactReference after onPause drives the next event with the fresh reference`() {
        // Guards a gap in lifecycle coverage: no existing test verifies that a reference set
        // AFTER onPause() is actually honoured (vs the INVALID that onPause writes leaking through).
        //
        // Scenario:
        //   1. Drive a first event into SILENCE_CHECK so the SM has non-default internal state.
        //   2. Call onPause() — resets preImpactRef to INVALID and clears all event state.
        //   3. Start a NEW event: impact → setPreImpactReference(valid upright ref) →
        //      prompt stop → UPRIGHT silence vector (angle ≈ 0° < 45°).
        //   4. Assert lastConfirmedSilenceMs == 20000L (silenceDurationUprightMs).
        //
        // WHY 20000 is the load-bearing value:
        //   - A valid upright reference + upright silence vector → angle ≈ 0° < 45° →
        //     orientation regime selects silenceDurationUprightMs = 20000ms.
        //   - If the fresh reference had NOT taken effect (INVALID leaked through), the
        //     !preImpactRef.valid branch would return the legacyShort window (4500ms) instead.
        //   - Therefore 20000 is ONLY reachable when the fresh reference is actually used;
        //     an INVALID reference makes it impossible to reach this value.
        val (sm, _) = newSm()
        sm.onSpeedUpdate(20.0)

        // ── Event 1: drive into SILENCE_CHECK ───────────────────────────────
        sm.onSample(sample(time = 1000, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))
        sm.onSpeedUpdate(2.0)
        sm.onSample(sample(time = 1600, peak = QUIET, smoothed = QUIET, gyro = 0.5))
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)

        // ── onPause resets to MONITORING and invalidates preImpactRef ────────
        sm.onPause()
        assertEquals(CrashStateMachine.State.MONITORING, sm.state)
        assertEquals(PreImpactRef.INVALID, sm.preImpactReference)

        // ── Event 2: fresh crash after resume ────────────────────────────────
        sm.onSpeedUpdate(20.0)
        val base2 = 5_000L    // fresh time domain for the second event

        // R6-G: high-rotation impact so the upright prompt-stop confirm is NOT vetoed — this
        // test asserts the 20s window-SELECTION reached via the fresh reference, not the veto.
        sm.onSample(sample(time = base2, peak = 60.0, smoothed = 30.0, gyro = 9.0))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)

        // Inject a FRESH valid UPRIGHT reference AFTER onPause — this is what must take effect.
        // With an INVALID reference the SM would return legacyShort (4500); only a valid
        // reference lets computeEffectiveSilenceMs reach the angle-comparison branch and
        // return silenceDurationUprightMs (20000) for an upright silence vector.
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))
        assertTrue("fresh reference must be valid after setPreImpactReference post-pause",
            sm.preImpactReference.valid)

        // Prompt stop (gap = 2000 ms < delayedStopGapMs = 8000) → orientation regime.
        // UPRIGHT silence vector (az = 9.81, ax = 0.0) matches the pre-impact reference:
        // angle ≈ 0° < uprightAngleThresholdDegrees (45°) → silenceDurationUprightMs = 20000ms.
        sm.onSpeedUpdate(0.0)
        sm.onSample(sample(time = base2 + 2000, peak = QUIET, smoothed = QUIET, gyro = 0.5,
            ax = 0.0, ay = 0.0, az = 9.81))
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)

        // Accumulate well above MIN_ORIENTATION_SAMPLES (5) upright still samples so the
        // orientation regime latches the 20s window, then drive past 20s of silence.
        var tNow = base2 + 2000L
        repeat(5 + 2) {  // 5 = MIN_ORIENTATION_SAMPLES (private const in CrashStateMachine)
            tNow += 200L
            sm.onSample(sample(time = tNow, peak = QUIET, smoothed = QUIET, gyro = 0.05,
                ax = 0.0, ay = 0.0, az = 9.81))
        }

        // Drive past the 20s silence window and assert Confirm fires.
        var confirmed = false
        while (tNow < base2 + 2000L + 22_000L) {
            tNow += 500L
            val d = sm.onSample(sample(time = tNow, peak = QUIET, smoothed = QUIET, gyro = 0.05,
                ax = 0.0, ay = 0.0, az = 9.81))
            if (d == CrashStateMachine.Decision.Confirm) {
                confirmed = true
                break
            }
        }
        assertTrue(
            "second event must confirm — fresh post-pause reference must drive the upright orientation decision",
            confirmed
        )
        // 20000 is ONLY reachable when the fresh reference is valid and angle < 45°.
        // An INVALID reference would have returned 4500 (legacyShort), so this assertion
        // is genuinely load-bearing: it fails iff the fresh reference did NOT take effect.
        assertEquals(
            "lastConfirmedSilenceMs must be silenceDurationUprightMs (20000) — " +
                "only a valid fresh reference can produce this value; INVALID would give 4500",
            20_000L, sm.lastConfirmedSilenceMs
        )
    }

    // ── I3 fix: autopause must not wipe in-flight SILENCE_CHECK state ───────────

    @Test
    fun `autopause scenario - uninterrupted SILENCE_CHECK confirms (no onPause call)`() {
        // An autopause must NOT call onPause(); the state machine keeps running on
        // the accelerometer stream and an in-flight on-side crash confirms.
        val (sm, _) = smEnteringSilence(
            gapMs = 2_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 0.0, silenceAx = 9.81,   // on-side -> 4.5 s window
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        var t = 1_002_000L
        var confirmed = false
        repeat(7) {
            t += 1000L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
                    is CrashStateMachine.Decision.Confirm) confirmed = true
        }
        assertTrue("uninterrupted SILENCE_CHECK must confirm", confirmed)
    }

    @Test
    fun `manual pause scenario - onPause mid-SILENCE_CHECK wipes to MONITORING`() {
        // A manual pause DOES call onPause(); the in-flight state is wiped.
        val (sm, _) = smEnteringSilence(
            gapMs = 2_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 0.0, silenceAx = 9.81,
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        sm.onPause()
        assertEquals(CrashStateMachine.State.MONITORING, sm.state)
    }

    // ── Stuck cadence sensor (C2 fix) ────────────────────────────────────────

    @Test
    fun `stuck cadence sensor repeating one value goes inactive and does not veto SILENCE_CHECK`() {
        // A cadence sensor that lost signal repeats its last value bit-exact. After
        // cadenceStaleThresholdMs with no CHANGE it must read as inactive, so it
        // cannot veto a real crash. On-side crash -> 4.5 s window.
        //
        // Anchors `docs/calibration-annotations.md` → FN #1 (simulated impact at
        // 1h37 of the 2026-05-25 ride: cadence stuck bit-exact at 39 RPM from
        // pre-impact through the silence window — the SIL_TMO observed in the
        // raw log is exactly the regression this guard prevents).
        val (sm, _) = smEnteringSilence(
            gapMs = 2_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 0.0, silenceAx = 9.81,
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        var t = 1_002_000L
        var confirmed = false
        // Feed 12 s of stillness; cadence sensor stuck bit-exact at 68 RPM the whole time.
        repeat(12) {
            t += 1000L
            val d = sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
            sm.onCadenceUpdate(68.0)   // same value every tick -> stuck sensor
            if (d is CrashStateMachine.Decision.Confirm) confirmed = true
        }
        assertTrue("a stuck cadence reading must not block confirmation", confirmed)
    }

    @Test
    fun `cadence sensor stuck after fluctuating goes inactive via the change-staleness guard`() {
        // Exercises guard (4) of isCadenceActive specifically:
        //   `sinceChange > cadenceStaleThresholdMs` (sensor WAS fluctuating, then lost
        //   signal mid-ride and repeats its last value bit-exact).
        //
        // Anchors `docs/calibration-annotations.md` → FN #2 and FN #3 (real falls
        // on the 2026-05-25 ride: cadence fluctuated normally during the ride,
        // then stuck bit-exact at 88 RPM after the FN #2 fall at 2h03 and at
        // 64 RPM after the FN #3 fall at 4h15. Both events resulted in
        // SIL_BRK loops + no confirm in the raw log — this guard is the fix).
        //
        // Why this is distinct from the existing "stuck cadence" test:
        //   The existing test feeds a bit-exact 68.0 from the very first call, so
        //   cadenceLastChangeMs stays at CADENCE_CHANGE_NEVER and the test passes via
        //   guard (3). Guard (4) — the realistic production case — is only reachable
        //   when cadenceLastChangeMs holds a REAL (non-sentinel) timestamp, which
        //   requires the cadence value to have actually changed at least once.
        //
        // Time-domain mechanics:
        //   onCadenceUpdate stamps lastCadenceUpdateMs = lastSampleMs (current sample
        //   domain). The test calls onCadenceUpdate AFTER each onSample, following the
        //   production pattern. So when onSample(T) runs, lastCadenceUpdateMs reflects
        //   the previous tick (age = one step, always fresh). cadenceLastChangeMs is
        //   only updated when the RPM VALUE changes, not on every emission.
        //
        //   cadenceStaleThresholdMs = 3_000. cadenceLastChangeMs is stamped at
        //   lastSampleMs = 200 (step 4 below). A large time-skip puts the impact
        //   well past that stamp, so by the time SILENCE_CHECK ticks begin:
        //     sinceChange = nowSampleMs − 200 >> 3_000   → guard (4) fires
        //     age          = nowSampleMs − prevSampleMs = step (500) < 3_000  → guard (2) passes
        //   Guard (4) therefore fires while emission is still fresh, exactly the
        //   production scenario (sensor keeps emitting but its value never changes).
        //
        //   silenceStartedMs = 4_600; effectiveSilenceMs = 4_500 → confirm at >= 9_100.
        val (sm, _) = newSm(thresholds = Thresholds(cadenceStaleThresholdMs = 3_000L))
        sm.onSpeedUpdate(20.0)

        // ── MONITORING phase: stamp a real cadenceLastChangeMs ───────────────
        // Step 1: advance lastSampleMs to 100 (quiet, below impact threshold).
        sm.onSample(sample(time = 100, peak = 5.0, smoothed = 5.0))
        // Step 2: first cadence call — NaN → 62.0. The NaN guard
        //   (`!lastCadenceRpm.isNaN() && rpm != last`) is false (isNaN), so
        //   cadenceLastChangeMs stays CADENCE_CHANGE_NEVER. lastCadenceUpdateMs = 100.
        sm.onCadenceUpdate(62.0)

        // Step 3: advance lastSampleMs to 200 (still quiet).
        sm.onSample(sample(time = 200, peak = 5.0, smoothed = 5.0))
        // Step 4: second cadence call — 62.0 → 67.0: the guard fires and stamps
        //   cadenceLastChangeMs = lastSampleMs = 200.  lastCadenceUpdateMs = 200.
        //   From this point guard (3) (CADENCE_CHANGE_NEVER) is forever bypassed;
        //   only guard (4) (sinceChange > cadenceStaleThresholdMs) can now deactivate
        //   the sensor.
        sm.onCadenceUpdate(67.0)

        // ── Time-skip into impact — cadence NOT called between 200 and 4_100 ─
        // Impact at t = 4_100. lastCadenceUpdateMs is still 200.
        // sinceChange at impact = 4_100 − 200 = 3_900 > 3_000 → guard (4) already holds
        // (but the cadence gate is only evaluated inside SILENCE_CHECK, so this
        // transition to IMPACT is unaffected).
        sm.onSample(sample(time = 4_100, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)

        // ── On-side pre-impact reference; drop speed; enter SILENCE_CHECK ────
        // Call onCadenceUpdate(67.0) right after the impact sample so that
        //   lastCadenceUpdateMs = 4_100 (fresh relative to the upcoming SILENCE ticks).
        // cadenceLastChangeMs stays at 200 (value did not change).
        sm.onCadenceUpdate(67.0)
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))
        sm.onSpeedUpdate(0.0)
        // timeSinceImpact = 4_700 − 4_100 = 600 ms > minTimeSinceImpactMs (500) → time gate passes.
        // Gap = 600 ms < default delayedStopGapMs (8_000) → orientation regime.
        // On-side vector (ax = 9.81, az = 0) vs upright reference (z = 9.81) →
        // angle ≈ 90° ≥ uprightAngleThresholdDegrees (45°) → 4.5s window.
        sm.onSample(sample(time = 4_700, peak = QUIET, smoothed = QUIET, gyro = 0.1,
            ax = 9.81, az = 0.0))
        // isCadenceActive(4_700): lastCadenceUpdateMs=4_100, age=600<3_000 (fresh);
        //   cadenceLastChangeMs=200, sinceChange=4_500>3_000 → guard (4) fires → false.
        // Cadence gate inactive → SILENCE_CHECK entry succeeds.
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)

        // ── Stuck sensor phase: feed stuck 67.0 on every tick ────────────────
        // Each tick: onSample(T) → isCadenceActive(T): age = T − (T−500) = 500 (fresh);
        //   sinceChange = T − 200 >> 3_000 → guard (4) fires → false → no veto.
        // silenceStartedMs = 4_700; confirm at >= 9_200.
        var t = 4_700L
        var confirmed = false
        while (t < 13_000L && !confirmed) {
            t += 500L
            val d = sm.onSample(sample(time = t, peak = QUIET, smoothed = QUIET, gyro = 0.1,
                ax = 9.81, az = 0.0))
            // Keep emission fresh (age = 500 < 3_000) while value stays 67.0 (no re-stamp).
            sm.onCadenceUpdate(67.0)
            if (d == CrashStateMachine.Decision.Confirm) confirmed = true
        }
        assertTrue(
            "a cadence sensor stuck after fluctuating must go inactive via the change-staleness " +
                "guard (guard 4) and must NOT veto crash confirmation",
            confirmed
        )
    }

    @Test
    fun `fluctuating cadence keeps the gate active and exits SILENCE_CHECK as a false alarm`() {
        // A genuinely pedalling rider's cadence fluctuates every revolution. The
        // cadence gate must stay active and trigger the false-alarm exit.
        val (sm, _) = smEnteringSilence(
            gapMs = 2_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 0.0, silenceAx = 9.81,
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        var t = 1_002_000L
        val rpms = listOf(78.0, 81.0, 79.0, 82.0, 80.0, 83.0)
        var returned = false
        for (rpm in rpms) {
            t += 1000L
            val d = sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
            sm.onCadenceUpdate(rpm)   // changes every tick -> genuine pedalling
            if (d is CrashStateMachine.Decision.ReturnToMonitoring) returned = true
        }
        assertTrue("a fluctuating (real pedalling) cadence must trigger the gate", returned)
    }

    // ── On-side speed-rise relaxation ───────────────────────────────────────────

    @Test
    fun `on-side relaxation - speed rise above confirm threshold does not break silence (bike escaping)`() {
        // On-side prompt stop (angle ~90°), orientation locks at 4.5 s window.
        // After the lock, raise the speed above crashConfirmSpeedKmh (default 5) —
        // without the relaxation this would break silence; with it, the accel-only
        // isStill keeps the silence window running and Confirm fires.
        val (sm, _) = smEnteringSilence(
            gapMs = 2_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 0.0, silenceAx = 9.81,   // angle ~90° from upright reference
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        var t = 1_002_000L
        // 5 still on-side samples to drive the orientation lock (>= MIN_ORIENTATION_SAMPLES).
        repeat(5) {
            t += 200L
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
        }
        // Bike rolls — speed rises above the confirm threshold.
        sm.onSpeedUpdate(10.0)
        // Keep feeding still on-side samples for > 4.5 s of accumulated silence.
        var confirmed = false
        repeat(7) {
            t += 1000L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
                    is CrashStateMachine.Decision.Confirm) confirmed = true
        }
        assertTrue("on-side relaxation must allow Confirm despite speed rise", confirmed)
        assertEquals(4_500L, sm.lastConfirmedSilenceMs)
    }

    @Test
    fun `on-side relaxation - 45 to 60 degree band does NOT relax (only strong on-side qualifies)`() {
        // Angle ~55° (between the 45° on-side gate and the 60° relaxation gate):
        // orientation regime still locks the 4.5 s on-side window, but the
        // relaxation does NOT engage — a speed rise breaks silence as before.
        // Construct silence vector with magnitude ~9.81: az = 5.63, ax = 8.04 gives
        // angle ≈ acos(5.63/9.81) ≈ 55°, magnitude ≈ sqrt(31.7+64.6) ≈ 9.81.
        val (sm, _) = smEnteringSilence(
            gapMs = 2_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 5.63, silenceAx = 8.04,
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        var t = 1_002_000L
        repeat(5) {
            t += 200L
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 5.63, ax = 8.04))
        }
        sm.onSpeedUpdate(10.0)   // speed rise that should break silence (no relax in 45-60° band)
        var confirmed = false
        repeat(7) {
            t += 1000L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 5.63, ax = 8.04))
                    is CrashStateMachine.Decision.Confirm) confirmed = true
        }
        assertEquals(
            "speed rise must keep breaking silence when angle is below the 60° relaxation gate",
            false, confirmed,
        )
    }

    @Test
    fun `on-side relaxation - upright regime does NOT relax`() {
        // Prompt stop, upright silence vector — orientation locks at 20 s (upright).
        // lastOrientationAngleDeg ~0° < 60°, no relaxation, speed rise breaks silence.
        val (sm, _) = smEnteringSilence(
            gapMs = 2_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 9.81, silenceAx = 0.0,
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        var t = 1_002_000L
        repeat(5) {
            t += 200L
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 9.81, ax = 0.0))
        }
        sm.onSpeedUpdate(10.0)
        var confirmed = false
        repeat(25) {
            t += 1000L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 9.81, ax = 0.0))
                    is CrashStateMachine.Decision.Confirm) confirmed = true
        }
        assertEquals(
            "upright regime must not engage the relaxation — speed rise still breaks silence",
            false, confirmed,
        )
    }

    @Test
    fun `on-side relaxation - gap regime does NOT relax (no orientation evidence)`() {
        // Long gap (> delayedStopGapMs 8 s) forces the gap regime; lastOrientationAngleDeg
        // stays at the -1.0 sentinel — relaxation must not engage.
        val (sm, _) = smEnteringSilence(
            gapMs = 12_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 0.0, silenceAx = 9.81,   // would be on-side BUT gap regime ignores
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        var t = 1_012_000L
        repeat(5) {
            t += 200L
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
        }
        sm.onSpeedUpdate(10.0)
        var confirmed = false
        // Feed 25 s of stillness while speed is high. With correct behaviour, the speed-drop
        // requirement keeps breaking silence indefinitely — no Confirm fires. If the relaxation
        // incorrectly engaged (bug), the 4.5 s legacy window would confirm within ~5 s of
        // the speed rise, well before the 25 s mark, and the assertion below would catch it.
        repeat(25) {
            t += 1000L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
                    is CrashStateMachine.Decision.Confirm) confirmed = true
        }
        assertEquals(
            "gap regime must not engage the relaxation — speed rise still breaks silence",
            false, confirmed,
        )
    }

    @Test
    fun `on-side relaxation - accel motion within budget tolerated by latch preservation`() {
        // CR2 (May 2026) updated this test. Pre-CR2 the silence clock RESET on every
        // !isStill sample even when on-side relaxation was engaged, so Confirm fired
        // at T_break + 4.5 s. CR2 makes the within-budget reset asymmetric: when the
        // latch was decisively on-side the bump is tolerated — accumulator, latch
        // AND silenceStartedMs all survive — and the silence-elapsed measurement keeps
        // growing across the bump. Confirm therefore fires at the originally latched
        // 4.5 s from SILENCE_CHECK entry, not from the bump.
        //
        // What this test still pins (its real value): the SM stays in SILENCE_CHECK
        // after a within-budget bump (retry, not give-up) AND Confirm eventually
        // fires from the on-side regime. The strong false-positive guards remain in
        // place at the budget boundary (see the companion test
        // `out-of-budget break still gives up regardless of on-side latch`).
        //
        // Timing arithmetic for the assertions below.
        // smEnteringSilence(gapMs = 2_000): impact at base = 1_000_000; SILENCE_CHECK
        // entered at base + 2_000 = 1_002_000 (silenceStartedMs = 1_002_000).
        // 5 on-side lock samples at +200 ms steps end at t = 1_003_000.
        // Speed raised; motion sample at t = 1_004_000 → T_break = 1_004_000.
        // 4.5 s from original entry = 1_006_500.
        val (sm, _) = smEnteringSilence(
            gapMs = 2_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 0.0, silenceAx = 9.81,
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        var t = 1_002_000L
        // Accumulate >= MIN_ORIENTATION_SAMPLES (5) on-side still samples to lock the
        // 4.5 s window and engage the on-side relaxation.
        repeat(5) {
            t += 200L
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
        }
        // t = 1_003_000 here.
        sm.onSpeedUpdate(10.0)   // speed rise (relaxation would normally ignore this)
        // Motion sample (raw = 16.0, |16.0 - 9.81| = 6.19 > silenceDeviationMax 4.0).
        // Under CR2 this is a within-budget on-side break → tolerated, no reset.
        t += 1000L               // t = 1_004_000
        sm.onSample(sample(time = t, raw = 16.0, smoothed = 9.81, az = 0.0, ax = 9.81))
        val tBreak = t           // = 1_004_000
        // After the break, the SM must stay in SILENCE_CHECK (retry, not give-up).
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)

        // Drop speed for the still samples (the on-side relaxation already keeps
        // isStill = accelOk, so this is belt-and-braces; left in to match the original
        // intent of "the bike came to rest after the bump").
        sm.onSpeedUpdate(0.0)

        // Walk forward in 500 ms steps and check exactly when Confirm fires.
        // Under CR2 silenceStartedMs = 1_002_000 was preserved across the bump.
        // First post-bump sample (t = 1_004_500): elapsed = 2_500 ms < 4_500.
        // Sample at t = 1_006_500: elapsed = 4_500 → Confirm.
        var firstConfirmAt = 0L
        while (firstConfirmAt == 0L && t < tBreak + 6_000L) {
            t += 500L
            val d = sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
            if (d is CrashStateMachine.Decision.Confirm) firstConfirmAt = t
        }
        assertTrue("Confirm must fire within 6 s of T_break", firstConfirmAt > 0L)
        // Confirm must arrive at ~entry + 4_500 = 1_006_500, NOT at T_break + 4_500 =
        // 1_008_500 (the broken pre-CR2 alternative). Allow a small window of slack
        // for the 500 ms step granularity used above.
        assertTrue(
            "Confirm must fire ~4.5 s from SILENCE_CHECK entry (silenceStartedMs preserved " +
                "across the on-side bump), got firstConfirmAt = $firstConfirmAt",
            firstConfirmAt in 1_006_500L..1_007_000L,
        )
        assertEquals(4_500L, sm.lastConfirmedSilenceMs)
    }

    // ── Diagnostics: lastConfirmedGapMs / lastConfirmedAngleDeg snapshots ──────

    /**
     * After a confirmed crash via the ORIENTATION regime (prompt stop, bike on-side,
     * valid pre-impact reference), the snapshot fields must hold the values that were
     * in force AT confirmation time — NOT the post-reset zeroes/sentinels.
     *
     * Specifically:
     *  - [CrashStateMachine.lastConfirmedGapMs] must equal the gap the SM saw when it
     *    entered SILENCE_CHECK (firstSilenceGapMs at that moment, NOT 0 after reset).
     *  - [CrashStateMachine.lastConfirmedAngleDeg] must be a real non-negative angle
     *    computed from the orientation regime (NOT -1.0 after reset).
     */
    @Test
    fun `lastConfirmedGapMs and lastConfirmedAngleDeg hold pre-reset values after Confirm`() {
        // Orientation regime: prompt stop (gap < delayedStopGapMs), valid pre-impact ref,
        // bike on-side (gravity along X → angle ≈ 90° from upright Z reference).
        // The gap used is 2000ms; the on-side angle should be ~90°.
        val gapMs = 2_000L
        val (sm, _) = smEnteringSilence(
            gapMs = gapMs,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 0.0, silenceAx = 9.81,   // on-side: ~90° from upright Z reference
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)

        // Drive to confirm: on-side → orientation regime → 4.5s silence window.
        var t = 1_000_000L + gapMs
        var confirmed = false
        repeat(10) {
            t += 1_000L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
                    is CrashStateMachine.Decision.Confirm) confirmed = true
        }
        assertTrue("Expected Decision.Confirm within 10s of silence_check entry", confirmed)

        // After confirm, resetTimers() zeroes firstSilenceGapMs → would be 0 if not snapshotted.
        // After confirm, resetSilenceWindow() sets lastOrientationAngleDeg = -1.0 if not snapshotted.
        // The snapshot fields must hold the pre-reset values.
        assertEquals(
            "lastConfirmedGapMs must equal the gap at confirmation time, not 0 after reset",
            gapMs, sm.lastConfirmedGapMs
        )
        assertTrue(
            "lastConfirmedAngleDeg must be a real angle (>=0), not the -1.0 reset sentinel; got ${sm.lastConfirmedAngleDeg}",
            sm.lastConfirmedAngleDeg >= 0.0
        )
        // The on-side scenario (ax=9.81, az=0 vs ref az=9.81) yields ~90°, well above 0.
        assertTrue(
            "lastConfirmedAngleDeg should be close to 90° for on-side crash; got ${sm.lastConfirmedAngleDeg}",
            sm.lastConfirmedAngleDeg > 45.0
        )
    }

    @Test
    fun `on-side relaxation - engages at the exact 60 degree boundary`() {
        // Vector (8.496, 0, 4.905): magnitude = sqrt(72.18 + 24.06) ≈ 9.81;
        // dot with reference (0,0,9.81) = 4.905·9.81 ≈ 48.12; cos(angle) =
        // 48.12 / (9.81·9.81) = 0.5 → angle = 60.00° exactly. The relaxation
        // gate uses `>=`, so 60° must engage.
        val (sm, _) = smEnteringSilence(
            gapMs = 2_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 4.905, silenceAx = 8.496,
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        var t = 1_002_000L
        repeat(5) {
            t += 200L
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 4.905, ax = 8.496))
        }
        sm.onSpeedUpdate(10.0)
        var confirmed = false
        repeat(7) {
            t += 1000L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 4.905, ax = 8.496))
                    is CrashStateMachine.Decision.Confirm) confirmed = true
        }
        assertTrue("relaxation must engage at the exact 60° boundary (>=)", confirmed)
        assertEquals(4_500L, sm.lastConfirmedSilenceMs)
    }

    // ── IMPACT-phase on-side relaxation ─────────────────────────────────────────

    @Test
    fun `IMPACT on-side relaxation engages with sustained on-side and speed high`() {
        // Bike crashes and continues moving (rolls); speed never drops below
        // crashConfirmSpeedKmh = 5. Without the IMPACT relaxation, the IMPACT
        // gate would never open (speedDropOk stays false) and IMPACT_TIMEOUT
        // would fire. With the relaxation, ≥25 on-side samples (~500 ms) of
        // accel-still accumulation in IMPACT triggers the bypass.
        val (sm, _) = newSm()
        sm.onSpeedUpdate(25.0)   // satisfy minSpeedForCrashKmh = 10 at impact entry
        val base = 1_000_000L
        sm.onSample(sample(time = base, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))
        sm.onSpeedUpdate(10.0)   // bike rolling — speed stays above crashConfirmSpeedKmh = 5
        var t = base + 1000L     // beyond minTimeSinceImpactMs = 500
        var transitioned = false
        // 30 still on-side samples at 50 Hz (~600 ms) — past the 25-sample threshold.
        repeat(30) {
            t += 20L
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.5,
                az = 0.0, ax = 9.81))
            if (sm.state == CrashStateMachine.State.SILENCE_CHECK) transitioned = true
        }
        assertTrue(
            "IMPACT relaxation must transition to SILENCE_CHECK after ≥25 on-side samples",
            transitioned,
        )
    }

    @Test
    fun `IMPACT relaxation does NOT engage with fewer than 25 samples accumulated`() {
        // Only 20 on-side accel-still samples — short of the 25-sample threshold.
        val (sm, _) = newSm()
        sm.onSpeedUpdate(25.0)
        val base = 1_000_000L
        sm.onSample(sample(time = base, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))
        sm.onSpeedUpdate(10.0)
        var t = base + 1000L
        repeat(20) {   // < IMPACT_RELAXATION_MIN_SAMPLES = 25
            t += 20L
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.5,
                az = 0.0, ax = 9.81))
        }
        assertEquals(
            "fewer than 25 samples must keep IMPACT relaxation off",
            CrashStateMachine.State.IMPACT, sm.state,
        )
    }

    @Test
    fun `IMPACT relaxation does NOT engage when gyro is high (cornering)`() {
        // gyro > 2.0 rad/s simulates sustained cornering. gyroOk must block
        // the relaxation regardless of orientation.
        val (sm, _) = newSm()
        sm.onSpeedUpdate(25.0)
        val base = 1_000_000L
        sm.onSample(sample(time = base, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))
        sm.onSpeedUpdate(10.0)
        var t = base + 1000L
        repeat(30) {
            t += 20L
            // High gyro every sample — gyroOk = false → gate blocked.
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 3.0,
                az = 0.0, ax = 9.81))
        }
        assertEquals(
            "high gyro must block IMPACT relaxation (cornering safeguard)",
            CrashStateMachine.State.IMPACT, sm.state,
        )
    }

    @Test
    fun `IMPACT relaxation does NOT engage when orientation is upright`() {
        // Upright accel vector matches the pre-impact reference — angle ~0°,
        // well below the 60° relaxation threshold. Without speedDropOk, the
        // gate stays closed.
        val (sm, _) = newSm()
        sm.onSpeedUpdate(25.0)
        val base = 1_000_000L
        sm.onSample(sample(time = base, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))
        sm.onSpeedUpdate(10.0)
        var t = base + 1000L
        repeat(30) {
            t += 20L
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.5,
                az = 9.81, ax = 0.0))   // upright — angle ≈ 0°
        }
        assertEquals(
            "upright orientation must not engage the IMPACT relaxation",
            CrashStateMachine.State.IMPACT, sm.state,
        )
    }

    @Test
    fun `IMPACT relaxation does NOT engage when pre-impact reference is invalid`() {
        // With an invalid pre-impact reference, currentOrientationAngleDeg()
        // returns -1.0 and the relaxation cannot fire.
        val (sm, _) = newSm()
        sm.onSpeedUpdate(25.0)
        val base = 1_000_000L
        sm.onSample(sample(time = base, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        sm.setPreImpactReference(PreImpactRef.INVALID)
        sm.onSpeedUpdate(10.0)
        var t = base + 1000L
        repeat(30) {
            t += 20L
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.5,
                az = 0.0, ax = 9.81))
        }
        assertEquals(
            "invalid pre-impact reference must block IMPACT relaxation",
            CrashStateMachine.State.IMPACT, sm.state,
        )
    }

    @Test
    fun `IMPACT on-side relaxation chains into SILENCE_CHECK relaxation for full crash-and-roll`() {
        // Full chain: real crash → IMPACT phase → bike rolls (speed stays high)
        // → IMPACT relaxation transitions to SILENCE_CHECK → SILENCE_CHECK
        // relaxation keeps the silence window running through the persisting
        // speed rise → Confirm fires at the 4.5 s on-side window.
        // Reproduces the MTB-descent residual scenario end-to-end.
        val (sm, _) = newSm()
        sm.onSpeedUpdate(25.0)
        val base = 1_000_000L
        sm.onSample(sample(time = base, peak = 70.0, smoothed = 40.0, gyro = 1.0))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))
        sm.onSpeedUpdate(10.0)   // bike rolling, well above crashConfirmSpeedKmh = 5
        var t = base + 1000L
        // Phase 1: 30 IMPACT samples → IMPACT relaxation engages → SILENCE_CHECK.
        var transitioned = false
        repeat(30) {
            t += 20L
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.5,
                az = 0.0, ax = 9.81))
            if (sm.state == CrashStateMachine.State.SILENCE_CHECK) transitioned = true
        }
        assertTrue("phase 1: IMPACT relaxation must transition to SILENCE_CHECK", transitioned)
        // Phase 2: SILENCE_CHECK on-side relaxation keeps the silence window
        // running through the speed rise; Confirm fires at the 4.5 s window.
        // 250 samples at 20 ms = 5 s — past the 4.5 s confirm window.
        var confirmed = false
        repeat(250) {
            t += 20L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.5,
                    az = 0.0, ax = 9.81)) is CrashStateMachine.Decision.Confirm) {
                confirmed = true
            }
        }
        assertTrue("phase 2: SILENCE_CHECK on-side relaxation must allow Confirm", confirmed)
        assertEquals(4_500L, sm.lastConfirmedSilenceMs)
    }

    @Test
    fun `on-side relaxation - speed rise BEFORE the orientation latch breaks silence`() {
        // While orientationSampleCount < MIN_ORIENTATION_SAMPLES the orientation
        // regime has not yet latched (lockedEffectiveSilenceMs is still 0L),
        // so onSideRelaxed is false. A speed rise in that pre-lock window
        // must break silence via the regular speed-drop gate, the silence
        // accumulator never reaches the lock threshold, and no confirm fires.
        val (sm, _) = smEnteringSilence(
            gapMs = 2_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 0.0, silenceAx = 9.81,
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        // Speed rises IMMEDIATELY after entry — before any lock samples accumulate.
        sm.onSpeedUpdate(10.0)
        var t = 1_002_000L
        var confirmed = false
        // 25 s of on-side samples: with no relaxation engaged, the speed-drop gate
        // breaks silence on every sample (clock keeps resetting), the count
        // never reaches MIN_ORIENTATION_SAMPLES, the latch never fires, and
        // the relaxation never engages.
        repeat(25) {
            t += 1000L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
                    is CrashStateMachine.Decision.Confirm) confirmed = true
        }
        assertEquals(
            "pre-lock speed rise must keep breaking silence (no relaxation possible)",
            false, confirmed,
        )
    }

    // ── CR2: within-budget silence break preserves on-side latch ────────────────
    //
    // Companion to the existing IMPACT→SILENCE_CHECK asymmetric reset (lines 533-546
    // in CrashStateMachine.kt). In SILENCE_CHECK a within-budget break used to call
    // resetSilenceWindow() unconditionally, wiping the orientation accumulator AND
    // the latch. For a real on-side crash where the bike rolls fast and bounces over
    // a small obstacle ~2 s into the silence window, the bounce wipes the on-side
    // latch, the bike is still rolling (speedDropOk false), isStill never holds for
    // long enough to rebuild the latch, and the SM falls through to SILENCE_TIMEOUT.
    //
    // CR2 makes the within-budget reset asymmetric: an on-side latch is durable
    // evidence (a bike on its side cannot be ridden, so a small bump while it rolls
    // does not undo what the latch established), and survives the break. The upright
    // and gap regimes still get the full reset — their evidence is weaker.

    @Test
    fun `within-budget break preserves on-side latch and accumulator`() {
        // Drive into SILENCE_CHECK with an on-side latch firmly set (angle ~90°).
        // Speed is held high (10 km/h, above crashConfirmSpeedKmh = 5) so that
        // without the latch surviving, isStill = accelOk && speedDropOk would
        // require speedDropOk → never satisfied → SILENCE_TIMEOUT. WITH the fix,
        // the latch survives the bump, onSideRelaxed stays true, isStill = accelOk
        // alone, and Confirm fires within the 4.5 s window.
        val (sm, _) = smEnteringSilence(
            gapMs = 2_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 0.0, silenceAx = 9.81,   // angle ~90° vs upright Z reference
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        // SILENCE_CHECK entered at t = 1_002_000. silenceStartedMs = 1_002_000.
        var t = 1_002_000L
        // Build the on-side latch: ≥ MIN_ORIENTATION_SAMPLES (5) of on-side stillness.
        repeat(10) {
            t += 20L
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
        }
        // Bike rolls — speed rises above the confirm threshold. With on-side
        // relaxation engaged this does not break silence by itself.
        sm.onSpeedUpdate(10.0)
        // Phase A — accumulate ~2 s of accel-still on-side samples post-latch.
        repeat(100) {   // 100 * 20ms = 2 s
            t += 20L
            assertEquals(
                CrashStateMachine.Decision.None,
                sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81)),
            )
        }
        // ~2 s into the silence window. Feed ONE bump sample (deviation > 4).
        // Without the fix this would wipe the latch and the accumulator — speed
        // is still high, so the latch can never re-engage.
        t += 20L
        val bumpTime = t
        sm.onSample(sample(time = t, raw = 16.0, smoothed = 9.81, az = 0.0, ax = 9.81))
        assertEquals(
            "within-budget break must stay in SILENCE_CHECK (retry, not give-up)",
            CrashStateMachine.State.SILENCE_CHECK, sm.state,
        )

        // Phase B — sustained accel-still samples post-bump. With CR2 fix:
        // silenceStartedMs preserved → elapsed at bumpTime + 2.5 s = 4.5 s+
        // total from entry → Confirm. Without the fix: latch wiped, speedDropOk
        // false, isStill never holds → no Confirm in this window.
        var confirmed = false
        repeat(150) {   // 150 * 20ms = 3 s — plenty of slack
            t += 20L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
                    is CrashStateMachine.Decision.Confirm) {
                confirmed = true
                return@repeat
            }
        }
        assertTrue(
            "Confirm must fire after the bump — the on-side latch must survive a within-budget " +
                "break so the bike-rolling speed-rise does not block the orientation regime",
            confirmed,
        )
        assertEquals(
            "Confirm must fire on the on-side (4.5 s) window — the latched value the SM " +
                "had decided before the bump",
            4_500L, sm.lastConfirmedSilenceMs,
        )
        // Bump consumed ~20 ms; sanity-check that Confirm fired close to the latched
        // 4.5 s of elapsed silence rather than 9 s (the broken alternative where the
        // silence clock had restarted from scratch after the bump).
        assertTrue(
            "Confirm must fire within ~5 s of SILENCE_CHECK entry (silenceStartedMs " +
                "preserved across the bump), got t=$t",
            t <= 1_002_000L + 5_500L,
        )
    }

    @Test
    fun `within-budget break with upright latch DOES reset accumulator and latch`() {
        // Pins the upright side of the asymmetric reset. With an upright latch
        // (angle ~0°, well below the 60° onSideRelaxationAngleDeg gate), the CR2
        // preservation does NOT apply — the within-budget break still calls
        // resetSilenceWindow(). After the bump the SM must REBUILD the latch from
        // scratch from whatever orientation evidence arrives next.
        val (sm, _) = smEnteringSilence(
            gapMs = 2_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 9.81, silenceAx = 0.0,   // upright: same direction as ref
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        var t = 1_002_000L
        // Build the upright latch.
        repeat(10) {
            t += 20L
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 9.81, ax = 0.0))
        }
        // Confirm the SM has chosen the 20 s window (upright regime).
        // Speed stays low so the on-side relaxation does not change isStill below.
        sm.onSpeedUpdate(0.0)
        // The bump.
        t += 20L
        sm.onSample(sample(time = t, raw = 16.0, smoothed = 9.81, az = 9.81, ax = 0.0))
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)

        // Now feed on-side samples (gravity along X). If the accumulator had been
        // preserved (i.e. the CR2 preservation incorrectly fired for upright), the
        // ten upright Z samples would dominate for a long time, keeping the angle
        // below 45° and the 20 s window in force. With the correct reset, the
        // orientation rebuilds along X within ~5 samples (~100 ms), angle ~90° →
        // the orientation regime re-latches the 4.5 s on-side window and Confirm
        // fires ~4.5 s after the bump.
        val tBreak = t
        var confirmed = false
        while (!confirmed && t < tBreak + 5_500L) {
            t += 20L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
                    is CrashStateMachine.Decision.Confirm) confirmed = true
        }
        assertTrue(
            "Confirm must fire within 5.5 s — upright latch must have been wiped on the " +
                "bump so the new on-side posture can latch the 4.5 s window",
            confirmed,
        )
        assertEquals(
            "Confirm must use the on-side 4.5 s window after the upright reset",
            4_500L, sm.lastConfirmedSilenceMs,
        )
    }

    @Test
    fun `within-budget break with gap regime DOES reset accumulator and latch`() {
        // Pins the gap-regime side of the asymmetric reset. In the gap regime the
        // SM never computes lastOrientationAngleDeg (it stays at the -1.0 sentinel),
        // so previousLatchWasOnSide is false and the existing reset behaviour
        // applies: silenceStartedMs is moved to the bump time AND the orientation
        // accumulator + latch are wiped. The 20 s window is then re-latched fresh
        // from the gap-regime path (firstSilenceGapMs survives resetSilenceWindow
        // — only resetTimers() zeroes it).
        //
        // The load-bearing observable is the Confirm TIMING relative to the bump:
        //   - Correct path: silenceStartedMs reset to bump time, 20 s window
        //     re-latched → Confirm fires ~20 s post-bump.
        //   - Broken "preserve everything" path: silenceStartedMs preserved at
        //     SILENCE_CHECK entry time (base + 2 000), 20 s window already latched
        //     → silenceElapsed crosses 20 s at clock = base + 22 000, i.e. ~16 s
        //     post-bump. Confirm fires ~4 s earlier than the correct path.
        //
        // We assert no Confirm in the first 19 s post-bump (distinguishes the two
        // paths: broken would confirm at ~16 s) and then a Confirm by 21 s
        // post-bump (the correct path's expected time, with a small slack).
        //
        // Verified load-bearing by temporarily removing the `if
        // (!previousLatchWasOnSide) { silenceStartedMs = now; resetSilenceWindow() }`
        // block (i.e. always preserve): the first assertion fails — Confirm fires
        // at ~16 s post-bump from the carried-over silenceStartedMs.
        val (sm, base) = smEnteringSilenceGapRegime(gapMs = 2_000L)
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        // The gap-regime SM uses Thresholds(impactWindowMs = 4_000) → retry budget
        // = 8 000 ms. lockedEffectiveSilenceMs = 20 000, lastOrientationAngleDeg = -1.0
        // sentinel → previousLatchWasOnSide = false.
        // Feed a stillness break inside the budget (entry at base + 2_000;
        // bump at base + 6_000 → entry-relative = 4_000 ≤ 8_000).
        val bumpT = base + 6_000L
        val d = sm.onSample(sample(time = bumpT, peak = 15.0, smoothed = 15.0,
            raw = 15.0, gyro = 0.5, az = 0.0, ax = 9.81))
        assertNotEquals(
            "within-budget break must be a retry, not a give-up",
            CrashStateMachine.Decision.ReturnToMonitoring, d,
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)

        // Drive on-side accel-still samples post-bump. Sample every 20 ms.
        //   - 19 s window (950 samples): correct path silenceElapsed = 19 s < 20 s
        //     → no Confirm. Broken path silenceElapsed = 19 s + 4 s = 23 s ≥ 20 s
        //     → Confirm already fired around the 16 s mark → FAIL here.
        //   - Additional 2 s window (100 samples → total 21 s post-bump): correct
        //     path crosses silenceElapsed = 20 s at the 20-s post-bump mark →
        //     Confirm fires inside this extension.
        var t = bumpT
        var earlyConfirm = false
        repeat(950) {   // 950 × 20 ms = 19 s
            t += 20L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
                    is CrashStateMachine.Decision.Confirm) {
                earlyConfirm = true
                return@repeat
            }
        }
        assertEquals(
            "Confirm must NOT fire within 19 s post-bump — the broken 'preserve everything' " +
                "path would confirm at ~16 s post-bump because silenceStartedMs carries over " +
                "from SILENCE_CHECK entry; the correct reset resets it to the bump time so a " +
                "fresh 20 s window has to elapse",
            false, earlyConfirm,
        )

        // Now keep driving stillness for another 2 s — total 21 s post-bump. The
        // correct reset path must finally confirm here, exercising both the
        // "asymmetric reset re-arms the silence clock" and "gap regime re-latches
        // the 20 s window from firstSilenceGapMs" effects.
        var lateConfirmT = 0L
        repeat(100) {   // 100 × 20 ms = 2 s
            t += 20L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
                    is CrashStateMachine.Decision.Confirm) {
                lateConfirmT = t
                return@repeat
            }
        }
        assertNotEquals(
            "Confirm must fire by 21 s post-bump — the gap regime re-latches the 20 s " +
                "window from firstSilenceGapMs after resetSilenceWindow() and the bump-relative " +
                "silence clock crosses 20 s",
            0L, lateConfirmT,
        )
        // The latched window is the gap-regime 20 s window — pinned by the facade
        // observable that survives the Confirm.
        assertEquals(
            "Confirm must use the gap-regime 20 s window",
            20_000L, sm.lastConfirmedSilenceMs,
        )
    }

    @Test
    fun `within-budget break on-side allows confirm via accumulated time across the bump`() {
        // CR2 integration test: 1 s pre-bump + 0.1 s bump + 3.5 s post-bump =
        // ~4.6 s wall-clock from SILENCE_CHECK entry. Because the on-side latch
        // is preserved AND silenceStartedMs is preserved, the silence-elapsed
        // measurement (now - silenceStartedMs) keeps growing across the bump,
        // and Confirm fires at the ~4.5 s mark within ~4.6 s wall-clock — NOT at
        // 9 s (which is what would happen if the silence clock had been reset to
        // the bump time).
        val (sm, _) = smEnteringSilence(
            gapMs = 2_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 0.0, silenceAx = 9.81,
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        val tEntry = 1_002_000L
        var t = tEntry
        // Latch + 1 s of accel-still on-side samples.
        repeat(50) {   // 50 * 20 ms = 1 s
            t += 20L
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
        }
        // Speed rises (bike rolling). On-side relaxation is engaged, so this
        // alone does not break silence.
        sm.onSpeedUpdate(10.0)
        // The bump: ~100 ms of accel motion, single sample for simplicity.
        t += 100L
        sm.onSample(sample(time = t, raw = 16.0, smoothed = 9.81, az = 0.0, ax = 9.81))
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        // Post-bump accel-still samples: feed until Confirm or 3.5 s elapse,
        // expecting Confirm well before the 3.5 s ceiling.
        val tPostBump = t
        var confirmed = false
        var confirmT = 0L
        while (t < tPostBump + 3_500L) {
            t += 20L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
                    is CrashStateMachine.Decision.Confirm) {
                confirmed = true
                confirmT = t
                break
            }
        }
        assertTrue("Confirm must fire within 3.5 s of the bump", confirmed)
        val elapsedFromEntry = confirmT - tEntry
        // The silence clock must have survived the bump — Confirm fires roughly
        // 4.5 s after SILENCE_CHECK entry, NOT 4.5 s after the bump (which would
        // be ~5.6 s) and certainly NOT 9 s (entry-to-bump 1.1 s + bump-to-Confirm
        // 4.5 s + re-latch overhead ≈ 5.7 s under the more naive "reset clock but
        // keep latch" variant; this assertion catches both broken alternatives).
        assertTrue(
            "Confirm must fire at ~4.5 s from SILENCE_CHECK entry (silenceStartedMs preserved), " +
                "got elapsed=${elapsedFromEntry}ms — must be in [4500, 4900]ms",
            elapsedFromEntry in 4_500L..4_900L,
        )
    }

    @Test
    fun `out-of-budget break still gives up regardless of on-side latch`() {
        // CR2 backstop sanity check. The preservation only applies WITHIN the
        // retry budget — past it, the SM must still ReturnToMonitoring even if
        // the latch was on-side. The budget protects against falling into an
        // infinite stillness-retry loop.
        val (sm, _) = smEnteringSilence(
            gapMs = 2_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 0.0, silenceAx = 9.81,
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        // SILENCE_CHECK entered at t = 1_002_000. impactWindowMs = 20_000 →
        // entry-relative budget = 40_000.
        var t = 1_002_000L
        // Build the on-side latch.
        repeat(10) {
            t += 20L
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
        }
        sm.onSpeedUpdate(10.0)   // bike rolling
        // Jump past entry + 40_000 with one decisive motion sample.
        t = 1_002_000L + 41_000L
        val d = sm.onSample(sample(time = t, raw = 16.0, smoothed = 9.81, az = 0.0, ax = 9.81))
        assertEquals(
            "out-of-budget break must give up regardless of latch state",
            CrashStateMachine.Decision.ReturnToMonitoring, d,
        )
        assertEquals(CrashStateMachine.State.MONITORING, sm.state)
    }

    // ── CR3: IMPACT-relax composes with SILENCE_CHECK gap regime ─────────────
    //
    // Without the angle propagation in [CrashStateMachine.handleImpact]'s
    // IMPACT-relax branch, a real crash where the bike rolls/tumbles for >8 s
    // before accel-settling enters SILENCE_CHECK with `firstSilenceGapMs > 8000`,
    // gap regime latches the 20 s window WITHOUT touching `lastOrientationAngleDeg`,
    // the SILENCE_CHECK on-side relaxation evaluates false (angle still -1.0),
    // isStill needs speedDropOk while the bike is still rolling → never still →
    // the impactWindowMs*2 retry budget is exhausted → ReturnToMonitoring. The
    // SpeedDropMonitor backstop is the only remaining defence.
    //
    // The fix stamps the just-computed on-side angle (≥60° — the IMPACT-relax
    // gate threshold) so the SILENCE_CHECK relaxation engages on the very first
    // sample even when the gap regime is in force, and isStill = accelOk alone.

    @Test
    fun `IMPACT-relax with gap above 8s confirms via SILENCE_CHECK on-side relaxation`() {
        // Default thresholds: impactWindowMs = 20_000, delayedStopGapMs = 8_000,
        // silenceDurationUprightMs = 20_000, onSideRelaxationAngleDeg = 60°,
        // crashConfirmSpeedKmh = 5. Impact at base. The bike then tumbles for
        // ~9 s — no samples reach the IMPACT accumulator (`accelOk = false`).
        // At base + 9_000 the bike settles on its side and starts emitting
        // 50 Hz on-side accel-still samples. The 25th such sample (~500 ms
        // later, at base + 9_500) fires IMPACT-relax with
        // firstSilenceGapMs = 9_500 > 8_000 → SILENCE_CHECK gap regime.
        // Speed stays at 10 km/h throughout (bike rolling), so speedDropOk
        // never holds. WITHOUT the angle propagation: SILENCE_CHECK relax
        // gate never engages, isStill needs speedDropOk → never confirms.
        // WITH it: relax gate engages on first sample, isStill = accelOk →
        // Confirm fires ~20 s into SILENCE_CHECK on the gap-regime window.
        val (sm, _) = newSm()
        sm.onSpeedUpdate(25.0)                              // gate minSpeedForCrashKmh = 10
        val base = 1_000_000L
        sm.onSample(sample(time = base, peak = 70.0, smoothed = 40.0, gyro = 1.0))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))
        sm.onSpeedUpdate(10.0)                              // bike rolling — speedDropOk stays false
        // Skip ~9 s of tumble (no samples → no IMPACT accumulation). Start
        // emitting settled on-side samples at base + 9_000.
        var t = base + 9_000L
        var transitioned = false
        // Drive ≥25 on-side samples; IMPACT-relax fires around base + 9_500.
        repeat(30) {
            t += 20L
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.5,
                az = 0.0, ax = 9.81))
            if (sm.state == CrashStateMachine.State.SILENCE_CHECK) transitioned = true
        }
        assertTrue("IMPACT-relax must transition to SILENCE_CHECK after ~9.5 s gap",
            transitioned)
        // firstSilenceGapMs must be > delayedStopGapMs so the gap regime engages.
        assertTrue(
            "firstSilenceGapMs=${sm.firstSilenceGapMs} must exceed 8_000 ms for gap regime",
            sm.firstSilenceGapMs > 8_000L,
        )

        // Drive 21 s of on-side accel-still samples. With the fix the SILENCE_CHECK
        // on-side relaxation engages on the first sample (lastOrientationAngleDeg
        // already ≥ 60° from the IMPACT-relax stamp), isStill = accelOk, and Confirm
        // fires ~20 s into SILENCE_CHECK. Without the fix the retry budget
        // (impactWindowMs * 2 = 40 s) eventually exhausts on the first non-still
        // sample — and even if no break happened, isStill would never hold because
        // speedDropOk stays false.
        var confirmed = false
        repeat(1_050) {   // 1_050 * 20 ms = 21 s
            t += 20L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.5,
                    az = 0.0, ax = 9.81)) is CrashStateMachine.Decision.Confirm) {
                confirmed = true
            }
        }
        assertTrue(
            "Confirm must fire within 21 s of SILENCE_CHECK entry — without the angle " +
                "propagation the SILENCE_CHECK relaxation cannot engage when the gap regime " +
                "is in force, and isStill needs speedDropOk which the rolling bike never satisfies",
            confirmed,
        )
        // The gap regime latched the 20 s window.
        assertEquals(20_000L, sm.lastConfirmedSilenceMs)
        assertTrue(
            "lastConfirmedAngleDeg=${sm.lastConfirmedAngleDeg} must reflect the on-side " +
                "evidence stamped at IMPACT-relax (≥ 60°)",
            sm.lastConfirmedAngleDeg >= 60.0,
        )
    }

    @Test
    fun `IMPACT-relax with gap above 8s and marginal angle does NOT confirm`() {
        // Same gap timing as the previous test, but the on-side angle is ~55° —
        // BELOW the 60° onSideRelaxationAngleDeg gate, ABOVE the 45° upright
        // threshold. With angle < 60°, the IMPACT-relax `onSideRelaxed` gate
        // never fires (speedDropOk stays false because the bike is still rolling),
        // IMPACT_TIMEOUT eventually triggers a ReturnToMonitoring with no confirm.
        // Pins the safety boundary: the composed path requires the strict 60°
        // evidence the IMPACT-relax decision is based on; a 55° marginal lean
        // cannot bypass the speed gate.
        //
        // Vector (8.039, 0, 5.625): magnitude = sqrt(64.6 + 31.6) ≈ 9.81;
        // dot with reference (0,0,9.81) = 5.625·9.81 ≈ 55.18; cos(angle) =
        // 55.18 / (9.81·9.81) ≈ 0.5735 → angle ≈ 55°.
        val (sm, _) = newSm()
        sm.onSpeedUpdate(25.0)
        val base = 1_000_000L
        sm.onSample(sample(time = base, peak = 70.0, smoothed = 40.0, gyro = 1.0))
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))
        sm.onSpeedUpdate(10.0)
        var t = base + 9_000L
        var transitioned = false
        // 30 settled marginal-lean samples — IMPACT-relax must NOT fire (angle < 60°).
        repeat(30) {
            t += 20L
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.5,
                az = 5.625, ax = 8.039))
            if (sm.state == CrashStateMachine.State.SILENCE_CHECK) transitioned = true
        }
        assertEquals(
            "marginal-lean angle (<60°) must not bypass the speed gate at IMPACT-relax",
            false, transitioned,
        )
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        // Sanity: drive past impactWindowMs (= 20_000) to confirm IMPACT_TIMEOUT eventually
        // gives up (no confirm fires).
        val tTimeout = base + 20_500L
        val d = sm.onSample(sample(time = tTimeout, raw = 9.81, smoothed = 9.81,
            gyro = 0.5, az = 5.625, ax = 8.039))
        assertEquals(CrashStateMachine.Decision.ReturnToMonitoring, d)
        assertEquals(CrashStateMachine.State.MONITORING, sm.state)
    }

    @Test
    fun `IMPACT-relax with gap below 8s confirms via orientation regime relaxation`() {
        // Control case for the CR3 fix. Same IMPACT-relax mechanism, but the
        // gap (5 s) is below delayedStopGapMs (8 s), so SILENCE_CHECK takes
        // the orientation regime branch instead of the gap regime. The
        // orientation regime overwrites `lastOrientationAngleDeg` with the
        // freshly-computed silence-window angle, so this test passes both
        // before and after the CR3 fix (the angle propagation is a no-op when
        // the orientation regime fires). Asserts the on-side 4.5 s window
        // confirms — not the 20 s gap window — and the relaxation lets isStill
        // hold while the bike is rolling.
        val (sm, _) = newSm()
        sm.onSpeedUpdate(25.0)
        val base = 1_000_000L
        sm.onSample(sample(time = base, peak = 70.0, smoothed = 40.0, gyro = 1.0))
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))
        sm.onSpeedUpdate(10.0)                              // bike rolling
        // Tumble silently for ~5 s, then settle on-side. IMPACT-relax fires at
        // ~base + 5_500 → firstSilenceGapMs = 5_500 < 8_000 → orientation regime.
        var t = base + 5_000L
        var transitioned = false
        repeat(30) {
            t += 20L
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.5,
                az = 0.0, ax = 9.81))
            if (sm.state == CrashStateMachine.State.SILENCE_CHECK) transitioned = true
        }
        assertTrue("IMPACT-relax must transition at ~5.5 s gap", transitioned)
        assertTrue(
            "firstSilenceGapMs=${sm.firstSilenceGapMs} must be < 8_000 to engage orientation regime",
            sm.firstSilenceGapMs in 5_000L..7_999L,
        )
        // 5 s of on-side accel-still — orientation regime + on-side relaxation lets
        // Confirm fire at the 4.5 s mark while speed stays above the confirm gate.
        var confirmed = false
        repeat(250) {   // 250 * 20 ms = 5 s
            t += 20L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.5,
                    az = 0.0, ax = 9.81)) is CrashStateMachine.Decision.Confirm) {
                confirmed = true
            }
        }
        assertTrue("Confirm must fire on the orientation-regime 4.5 s window", confirmed)
        assertEquals(
            "orientation regime must latch the 4.5 s on-side window — not the 20 s gap window",
            4_500L, sm.lastConfirmedSilenceMs,
        )
    }

    // ── I5: IMPACT_TIMEOUT resets the orientation accumulator ────────────────
    //
    // Pins commit 1a5ffcb's `resetSilenceWindow()` call in the IMPACT_TIMEOUT
    // branch of [CrashStateMachine.handleImpact]. The existing IMPACT_TIMEOUT
    // test asserts only the state transition; nothing pins the accumulator
    // clear. Without the reset, a marginal-lean IMPACT that accumulated 25+
    // samples on-side but never satisfied the relax/speed gates leaves the
    // accumulator filled — and the very next IMPACT's first sample would see
    // a 26-sample accumulator (potentially with the wrong orientation against
    // the new pre-impact ref), letting IMPACT-relax engage on essentially no
    // fresh evidence.

    @Test
    fun `IMPACT_TIMEOUT resets orientation accumulator so next IMPACT starts fresh`() {
        // First impact: 30 on-side samples accumulate at ~55° (above 45° upright
        // threshold so it would latch SILENCE_CHECK upright, but BELOW the 60°
        // IMPACT-relax gate). Speed stays high (bike rolling) → speedDropOk false,
        // onSideRelaxed false → gate stays closed for the entire impact window
        // until IMPACT_TIMEOUT fires. Then a SECOND impact: assert that 26
        // on-side fresh samples are required to engage IMPACT-relax — meaning
        // the accumulator MUST have been cleared at IMPACT_TIMEOUT (otherwise
        // the leftover 30 samples + 1 = 31 ≥ 25 would trip the relax gate on
        // the first sample of impact 2).
        val (sm, _) = newSm()
        sm.onSpeedUpdate(25.0)
        val base1 = 1_000_000L
        // Impact 1.
        sm.onSample(sample(time = base1, peak = 70.0, smoothed = 40.0, gyro = 1.0))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))
        sm.onSpeedUpdate(10.0)
        // 30 marginal-lean samples (~55°, below the 60° relax gate). accelOk
        // holds → accumulator fills to 30. gateOk stays false (no speedDrop,
        // no relax). State stays IMPACT.
        var t = base1 + 1_000L
        repeat(30) {
            t += 20L
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.5,
                az = 5.625, ax = 8.039))
        }
        assertEquals(
            "marginal lean must not engage IMPACT-relax — state stays IMPACT",
            CrashStateMachine.State.IMPACT, sm.state,
        )

        // Trigger IMPACT_TIMEOUT: jump past impactWindowMs (20_000 default) with
        // any sample. Production resetSilenceWindow() in the timeout branch
        // wipes the 30-sample accumulator.
        val tTimeout = base1 + 20_500L
        val d = sm.onSample(sample(time = tTimeout, raw = 9.81, smoothed = 9.81,
            gyro = 0.5, az = 5.625, ax = 8.039))
        assertEquals(CrashStateMachine.Decision.ReturnToMonitoring, d)
        assertEquals(CrashStateMachine.State.MONITORING, sm.state)

        // Second impact, far enough in the future that the cold-start guard is
        // long past. Speed gate satisfied (lastSpeedKmh still 10 from earlier,
        // need >= minSpeedForCrashKmh = 10).
        sm.onSpeedUpdate(25.0)
        val base2 = tTimeout + 10_000L
        sm.onSample(sample(time = base2, peak = 70.0, smoothed = 40.0, gyro = 1.0,
            az = 0.0, ax = 9.81))   // ON-SIDE on the impact spike sample itself
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))
        sm.onSpeedUpdate(10.0)

        // Load-bearing assertion: with a clean accumulator post-timeout, the
        // first IMPACT sample of impact 2 — using ON-SIDE orientation (would
        // trip the relax gate IF the accumulator had carried over) — must NOT
        // transition to SILENCE_CHECK. The relax gate requires 25 fresh
        // samples; one sample is not enough.
        val tFirst = base2 + 600L   // beyond minTimeSinceImpactMs = 500
        sm.onSample(sample(time = tFirst, raw = 9.81, smoothed = 9.81, gyro = 0.5,
            az = 0.0, ax = 9.81))
        assertEquals(
            "first sample of impact 2 must not fire IMPACT-relax — accumulator must " +
                "have been cleared at IMPACT_TIMEOUT (load-bearing for commit 1a5ffcb)",
            CrashStateMachine.State.IMPACT, sm.state,
        )

        // Stronger pin: feed 20 more on-side samples (count would reach 21 under
        // the fix — still under 25, no relax). State must still be IMPACT. Under
        // the buggy variant, count would be 51 and the SM would have transitioned
        // long ago.
        var t2 = tFirst
        repeat(20) {
            t2 += 20L
            sm.onSample(sample(time = t2, raw = 9.81, smoothed = 9.81, gyro = 0.5,
                az = 0.0, ax = 9.81))
        }
        assertEquals(
            "with a clean accumulator, 21 on-side samples (< 25) must not engage IMPACT-relax",
            CrashStateMachine.State.IMPACT, sm.state,
        )

        // Final check: 5 more samples (count = 26 under the fix) — NOW the relax
        // gate must fire and transition to SILENCE_CHECK. Guards against an
        // over-corrected variant that would never let the relaxation fire again.
        repeat(5) {
            t2 += 20L
            sm.onSample(sample(time = t2, raw = 9.81, smoothed = 9.81, gyro = 0.5,
                az = 0.0, ax = 9.81))
        }
        assertEquals(
            "26 fresh on-side samples post-reset must engage IMPACT-relax → SILENCE_CHECK",
            CrashStateMachine.State.SILENCE_CHECK, sm.state,
        )
    }

    // ── 2026-05-25 calibration-log regressions (FN2 / FN3 / FP) ───────────────
    //
    // Source data: a 4 h 31 min ride that produced three FNs (one simulated, two
    // real) and one FP. The two real-fall FNs (elapsed 7372.9 s and 15321.9 s)
    // had the device decisively on side / upside down post-impact (ax = 73 m/s²
    // and az = -81 m/s² in the IMPACT sample respectively), but a `fresh` cadence
    // reading from the SDK still showed 65–88 RPM and CAD_GATE killed SILENCE_CHECK
    // in < 1 s on the first SILENCE sample. The FP (elapsed 14141.2 s) fired on a
    // rough-terrain bump barrage at 36 km/h, in part because the SILENCE_CHECK
    // orientation accumulator absorbed deviation-26 / deviation-17 bumps that
    // would never represent gravity-only readings.
    //
    // Both fixes live in CrashStateMachine.handleSilenceCheck. The tests below pin:
    //   (a) FN-fix: CAD_GATE is suppressed when the live orientation angle vs the
    //       pre-impact reference is ≥ uprightAngleThresholdDegrees. The flag
    //       `lastCadenceGateSuppressed` is set so the facade can log a
    //       CAD_GATE_SUPPRESSED diagnostic.
    //   (b) FN-regression: an upright SILENCE_CHECK with active cadence still
    //       returns to MONITORING (the gate is suppressed ONLY on decisive
    //       on-side evidence).
    //   (c) FP-fix: samples whose deviation > silenceDeviationMax do NOT contribute
    //       to the SILENCE_CHECK orientation accumulator — only `accelOk` samples
    //       count, matching the IMPACT-phase accumulator gating.

    /** Helper: drive into SILENCE_CHECK via the onSideRelaxed (IMPACT-phase) path.
     *
     * - pre-impact reference along +Z (upright)
     * - 30 on-side accumulated samples in IMPACT (gravity along +X, dev = 0, gyro = 0.1)
     * - speed stays HIGH (20 km/h, > crashConfirmSpeedKmh = 5) → speedDropOk = false
     * - the relax gate (≥ 25 samples at ≥ 60°) fires on sample 25+ → SILENCE_CHECK
     *
     * Returns (sm, lastSampleTime). Caller continues feeding from `t + 20L`.
     */
    private fun smInSilenceCheckViaOnSideRelax(
        thresholds: Thresholds = Thresholds(),
    ): Pair<CrashStateMachine, Long> {
        val (sm, _) = newSm(thresholds = thresholds)
        sm.onSpeedUpdate(20.0)
        // Enter IMPACT with an off-axis spike (impact pushed the device on its side).
        val t0 = 1_000_000L
        sm.onSample(sample(time = t0, peak = 80.0, smoothed = 30.0, gyro = 1.0,
            ax = 9.81, ay = 0.0, az = 0.0))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))
        // Speed stays HIGH so speedDropOk cannot open the gate — onSideRelaxed is
        // the only way through. minTimeSinceImpactMs = 500 → start accumulating
        // post-500 ms so the first accumulator sample also satisfies timeOk.
        var t = t0 + 520L  // > 500 ms post-impact
        // 30 on-side quiet samples; the relax gate fires once count ≥ 25.
        var transitioned = false
        repeat(30) {
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.1,
                ax = 9.81, ay = 0.0, az = 0.0))
            if (sm.state == CrashStateMachine.State.SILENCE_CHECK) transitioned = true
            t += 20L
        }
        assertTrue(
            "helper precondition: SM must have transitioned via onSideRelaxed " +
                "(speed held high so speedDropOk cannot open the gate)",
            transitioned,
        )
        return sm to (t - 20L)
    }

    @Test
    fun `FN2 fix - on-side IMPACT-relax + active cadence does NOT fire CAD_GATE`() {
        // Reproduces FN2 (elapsed 7412.8 s): rider crashed, device upside-down,
        // bike still rolling (speed > 5 km/h), but the cadence SDK kept reading
        // 88 RPM. Without the fix, CAD_GATE fires on the first SILENCE_CHECK
        // sample and the SM returns to MONITORING → silent FN.
        val (sm, tEnter) = smInSilenceCheckViaOnSideRelax()
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)

        // Make cadence freshness pass all four guards:
        //   - lastCadenceUpdateMs != 0  (set on the 2nd onCadenceUpdate)
        //   - age <= cadenceStaleThresholdMs (10 s default)
        //   - cadenceLastChangeMs != CADENCE_CHANGE_NEVER (set after a value change)
        //   - sinceChange <= cadenceStaleThresholdMs
        //   - lastCadenceRpm > cadenceQuietThresholdRpm (20)
        sm.onCadenceUpdate(80.0)  // first value: stamps NaN → 80, no change recorded
        sm.onCadenceUpdate(85.0)  // value changes → cadenceLastChangeMs = lastSampleMs
        // Drive ONE more SILENCE_CHECK sample while cadence is active AND on-side.
        // The on-side latch was carried forward from IMPACT — currentOrientationAngleDeg
        // reads ~90° (impact accumulator was 30 samples along +X vs upright ref).
        val t = tEnter + 20L
        val d = sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.1,
            ax = 9.81, ay = 0.0, az = 0.0))

        assertEquals(
            "CAD_GATE must be suppressed when orientation is decisively on-side — " +
                "FN2/FN3 root cause from the 2026-05-25 ride log",
            CrashStateMachine.State.SILENCE_CHECK, sm.state,
        )
        assertNotEquals(CrashStateMachine.Decision.ReturnToMonitoring, d)
        assertTrue(
            "lastCadenceGateSuppressed must be set so the facade can log the diagnostic",
            sm.lastCadenceGateSuppressed,
        )
    }

    @Test
    fun `FN regression - upright SILENCE_CHECK + active cadence still fires CAD_GATE`() {
        // Pins the OTHER side of the suppression: when orientation is upright (or
        // not yet known), CAD_GATE must behave exactly as before. A rider stopped
        // at a traffic light who resumes pedalling must still bail the SM out of
        // SILENCE_CHECK promptly.
        val sm = smInSilenceCheck(
            thresholds = Thresholds(),
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
        )
        // Upright SILENCE samples — angle stays ~0° vs upright ref.
        // smInSilenceCheck already fed one settling sample (az = 9.81); send a few
        // more so the orientation accumulator is well-populated upright. None of
        // these should latch ORIENT_ONSIDE.
        var t = 2_020L
        repeat(8) {
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.1,
                ax = 0.0, ay = 0.0, az = 9.81))
            t += 20L
        }
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)

        // Now activate cadence. lastSampleMs is non-zero (we've processed
        // samples), so both onCadenceUpdate calls land in the live time domain.
        sm.onCadenceUpdate(80.0)
        sm.onCadenceUpdate(85.0)  // change → cadenceLastChangeMs stamped

        val d = sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.1,
            ax = 0.0, ay = 0.0, az = 9.81))

        assertEquals(
            "CAD_GATE must still fire on an upright SILENCE_CHECK — suppression " +
                "must engage ONLY for decisively non-upright orientations",
            CrashStateMachine.Decision.ReturnToMonitoring, d,
        )
        assertEquals(CrashStateMachine.State.MONITORING, sm.state)
        assertFalse(
            "lastCadenceGateSuppressed must remain false on the upright path",
            sm.lastCadenceGateSuppressed,
        )
    }

    @Test
    fun `FN regression - CAD_GATE suppressed when carried-forward accumulator is on-side, even without latch yet`() {
        // Edge case: the suppression check runs BEFORE any SILENCE_CHECK sample
        // has fed the accumulator (it reads the carried-forward IMPACT-phase
        // accumulator). With the onSideRelaxed transition, the accumulator
        // arrives with ≥ 25 on-side samples and the live angle is ~90°. The
        // suppression must engage on the VERY FIRST SILENCE_CHECK sample,
        // before the latch is computed for the first time.
        val (sm, tEnter) = smInSilenceCheckViaOnSideRelax()

        sm.onCadenceUpdate(60.0)
        sm.onCadenceUpdate(65.0)  // ensure freshness-by-change

        // The very first onSample inside SILENCE_CHECK that runs after the
        // cadence has been made active.
        val d = sm.onSample(sample(time = tEnter + 20L, raw = 9.81, smoothed = 9.81,
            gyro = 0.1, ax = 9.81, ay = 0.0, az = 0.0))

        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        assertTrue(sm.lastCadenceGateSuppressed)
        assertNotEquals(CrashStateMachine.Decision.ReturnToMonitoring, d)
    }

    @Test
    fun `FN end-to-end - on-side IMPACT-relax + active cadence still confirms via Decision_Confirm`() {
        // The hard regression: not only must CAD_GATE be suppressed, the SM must
        // also reach a Decision.Confirm afterwards. Mirrors FN2 / FN3 where the
        // device was decisively on-side AND the rider was unconscious — the
        // crash must be confirmed despite the phantom cadence.
        val (sm, tEnter) = smInSilenceCheckViaOnSideRelax()
        sm.onCadenceUpdate(80.0)
        sm.onCadenceUpdate(85.0)
        // Sustained on-side stillness with cadence still reporting bogus 85 RPM.
        // onSideRelaxed gate (lastOrientationAngleDeg ≥ 60°) bypasses speedDropOk,
        // so a high speed never blocks confirmation here.
        var t = tEnter + 20L
        var confirmed = false
        // Need ≥ 4500 ms (LEGACY) of cumulative stillness from silenceStartedMs.
        // silenceStartedMs was set when the SM entered SILENCE_CHECK in the helper.
        repeat(300) {  // 300 * 20 ms = 6 s of headroom
            // Touch cadence each iteration so age stays fresh and the suppression
            // is exercised on every sample.
            sm.onCadenceUpdate(if (it % 2 == 0) 84.0 else 86.0)
            val d = sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81,
                gyro = 0.1, ax = 9.81, ay = 0.0, az = 0.0))
            if (d is CrashStateMachine.Decision.Confirm) {
                confirmed = true
                return@repeat
            }
            t += 20L
        }
        assertTrue(
            "Confirm must fire — CAD_GATE suppression unblocks the on-side LEGACY 4.5 s window",
            confirmed,
        )
        assertEquals(4_500L, sm.lastConfirmedSilenceMs)
    }

    @Test
    fun `FP fix - bump samples with deviation gt silenceDeviationMax do not enter the orientation accumulator`() {
        // Behavioural pin for the FP fix: in SILENCE_CHECK, only `accelOk`
        // samples should contribute to the orientation accumulator. Mirrors the
        // IMPACT-phase accumulator gating (handleImpact line ~524).
        //
        // Set-up: enter SILENCE_CHECK upright via the speedDropOk path so the
        // accumulator starts EMPTY (no IMPACT-phase carry-forward). Feed 4
        // quiet upright samples — orientationSampleCount climbs to 4, still
        // below MIN_ORIENTATION_SAMPLES (5). Then feed multiple bumps with the
        // direction set along +X.
        //
        // WITHOUT the fix: bumps would accumulate; count would cross 5 with
        // mixed direction; the latch could fire ORIENT_ONSIDE.
        // WITH the fix: bumps are filtered; count stays at 4; the next quiet
        // sample brings it to 5 with PURE upright direction → angle ~0° →
        // latch picks the 20 s upright window.
        val t = Thresholds(
            silenceDurationMs = 4_500L,
            silenceDurationUprightMs = 20_000L,
            uprightAngleThresholdDegrees = 45.0,
            silenceDeviationMax = 4.0,
        )
        val sm = smInSilenceCheck(t, preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true))
        // smInSilenceCheck already fed one quiet sample (count = 1). Feed 3
        // more quiet upright samples → count = 4, still below MIN = 5.
        var tNow = 2_020L
        repeat(3) {
            sm.onSample(sample(time = tNow, raw = 9.81, smoothed = 9.81, gyro = 0.1,
                ax = 0.0, ay = 0.0, az = 9.81))
            tNow += 20L
        }
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        assertEquals(
            "latch must not yet have engaged (count < MIN)",
            -1.0, sm.lastOrientationAngleDeg, 0.0,
        )

        // Bumps with deviation = 16.0 - 9.81 = 6.19 m/s² (well above 4) and
        // direction strongly along +X. Each bump triggers the within-budget
        // !isStill reset path (no on-side latch yet, so resetSilenceWindow
        // fires AND wipes the accumulator). After the reset, the next quiet
        // sample arrives with count = 0 again. Without the FP-fix accelOk
        // gate, the bump would have added a +X sample before the reset wiped
        // it — but a wipe loses it anyway. So the failure mode this test pins
        // is more subtle: after several bump/quiet cycles, the rebuilt
        // accumulator must consist of upright Z-axis samples only.
        repeat(3) {
            sm.onSample(sample(time = tNow, raw = 16.0, smoothed = 16.0, gyro = 0.1,
                ax = 9.81, ay = 0.0, az = 0.0))
            tNow += 20L
            // Refill with quiet upright between bumps so the SM stays in
            // SILENCE_CHECK (within-budget retry path).
            repeat(2) {
                sm.onSample(sample(time = tNow, raw = 9.81, smoothed = 9.81, gyro = 0.1,
                    ax = 0.0, ay = 0.0, az = 9.81))
                tNow += 20L
            }
        }
        // Feed enough quiet upright samples to cross MIN_ORIENTATION_SAMPLES
        // post-rebuild. The latch must fire on UPRIGHT (angle ~ 0°), NOT
        // ORIENT_ONSIDE.
        repeat(8) {
            sm.onSample(sample(time = tNow, raw = 9.81, smoothed = 9.81, gyro = 0.1,
                ax = 0.0, ay = 0.0, az = 9.81))
            tNow += 20L
        }
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        assertTrue(
            "latch must have engaged after enough quiet upright samples (got " +
                "${sm.lastOrientationAngleDeg}°)",
            sm.lastOrientationAngleDeg >= 0.0,
        )
        assertTrue(
            "latched angle must be UPRIGHT (< 45°) after a bump-quiet cycle — " +
                "bumps must not contaminate the accumulator post-reset (got " +
                "${sm.lastOrientationAngleDeg}°)",
            sm.lastOrientationAngleDeg < t.uprightAngleThresholdDegrees,
        )
    }

    // ── 2026-05-25 FP-fix: speed ceiling on onSideRelaxed ─────────────────────
    //
    // The FP at elapsed 14141.2 s on the 2026-05-25 ride confirmed at 36 km/h
    // sustained — well above any plausible "bike rolling after rider down"
    // scenario. The on-side relaxation path now gates on a speed ceiling
    // (default 25 km/h, GPS-stale bypasses).
    //
    // Three regression tests:
    //   (a) FP block: sustained ≥ 25 km/h must NOT engage relaxation even with
    //       25 on-side samples at ≥ 60°. Falls back to speedDropOk path —
    //       which is `false` at 36 km/h → state stays in IMPACT until window
    //       expires (IMPACT_TIMEOUT, no confirm).
    //   (b) FN preservation: < 25 km/h still engages relaxation as before.
    //   (c) GPS-stale bypass: high speed BUT gps_stale → relaxation re-enables.

    /** Drive the SM through enough on-side IMPACT samples that the relaxation
     *  WOULD fire if the speed gate allowed it. Returns the SM at the next
     *  sample boundary (still in IMPACT phase; whether it transitions to
     *  SILENCE_CHECK depends on the gate). Caller controls speed and gpsStale.
     */
    private fun driveImpactWithOnSideSamples(
        thresholds: Thresholds,
        sampleCount: Int = 30,
    ): Triple<CrashStateMachine, ClockHandle, Long> {
        val (sm, h) = newSm(thresholds = thresholds)
        // Speed must be > minSpeedForCrashKmh on the impact-entry sample.
        sm.onSpeedUpdate(40.0)
        val t0 = 1_000_000L
        sm.onSample(sample(time = t0, peak = 80.0, smoothed = 30.0, gyro = 1.0,
            ax = 9.81, ay = 0.0, az = 0.0))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))
        var t = t0 + 520L
        repeat(sampleCount) {
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.1,
                ax = 9.81, ay = 0.0, az = 0.0))
            t += 20L
        }
        return Triple(sm, h, t)
    }

    @Test
    fun `FP fix - onSideRelaxed does NOT fire when speed is at or above onSideRelaxationMaxSpeedKmh`() {
        // Reproduces the FP signature: speed 36 km/h sustained, IMPACT-phase
        // accumulator filled with 30 on-side samples (would otherwise fire
        // relaxation). With the speed ceiling (25 km/h, default), the gate
        // remains closed and the SM stays in IMPACT. Eventually IMPACT_TIMEOUT
        // fires — no confirm, no FP.
        //
        // Anchors `docs/calibration-annotations.md` → FP #1 (the 2026-05-25
        // ride, elapsed 14141.2 s ≈ 235:69 min: rider sustained 36.6 km/h on a
        // fast descent with forward lean, on-side accumulator reached 75° vs
        // upright reference, relaxation fired and CRASH_OK confirmed; rider
        // cancelled at 7.2 s. The 25 km/h ceiling closes this exact path).
        val thresholds = Thresholds(
            onSideRelaxationMaxSpeedKmh = 25.0,
            crashConfirmSpeedKmh = 5,
        )
        val (sm, _) = newSm(thresholds = thresholds)
        sm.onSpeedUpdate(40.0)
        val t0 = 1_000_000L
        sm.onSample(sample(time = t0, peak = 80.0, smoothed = 30.0, gyro = 1.0,
            ax = 9.81, ay = 0.0, az = 0.0))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))
        // Speed STAYS HIGH — the FP signature. With the ceiling gate, all 30
        // on-side samples should NOT fire the relaxation. State must remain
        // IMPACT throughout.
        sm.onSpeedUpdate(36.0)
        var t = t0 + 520L
        repeat(30) {
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.1,
                ax = 9.81, ay = 0.0, az = 0.0))
            t += 20L
        }
        assertEquals(
            "onSideRelaxed must NOT fire at sustained 36 km/h — the FP signature " +
                "from elapsed 14141.2 s on the 2026-05-25 ride. State must stay in IMPACT.",
            CrashStateMachine.State.IMPACT, sm.state,
        )
    }

    @Test
    fun `FN preservation - onSideRelaxed still fires below onSideRelaxationMaxSpeedKmh`() {
        // Pins the OTHER side of the ceiling: a real fall at 20 km/h (below the
        // 25 km/h ceiling) must still engage the relaxation — matching the
        // existing helper-based on-side tests that this regression must not
        // disturb. 20 km/h is the speed used by smInSilenceCheckViaOnSideRelax
        // and by the CR2 on-side preservation tests.
        val thresholds = Thresholds(
            onSideRelaxationMaxSpeedKmh = 25.0,
            crashConfirmSpeedKmh = 5,
        )
        val (sm, _) = newSm(thresholds = thresholds)
        sm.onSpeedUpdate(20.0)  // below ceiling AND above minSpeedForCrashKmh
        val t0 = 1_000_000L
        sm.onSample(sample(time = t0, peak = 80.0, smoothed = 30.0, gyro = 1.0,
            ax = 9.81, ay = 0.0, az = 0.0))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))
        // Speed stays at 20 (< 25 ceiling). 30 on-side samples must fire
        // relaxation and transition to SILENCE_CHECK.
        var t = t0 + 520L
        var transitioned = false
        repeat(30) {
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.1,
                ax = 9.81, ay = 0.0, az = 0.0))
            if (sm.state == CrashStateMachine.State.SILENCE_CHECK) transitioned = true
            t += 20L
        }
        assertTrue(
            "onSideRelaxed must fire at 20 km/h (below 25 km/h ceiling) — real " +
                "falls in the 2026-05-25 ride all had IMPACT speeds < 25 km/h",
            transitioned,
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
    }

    @Test
    fun `FN preservation - GPS-stale bypasses the speed ceiling on onSideRelaxed`() {
        // FN3 from the 2026-05-25 ride had the stale-GPS trap: device pinned
        // on its side reading 0.15 m/s² deviation, yet speed=15.1 km/h echoed
        // from a pre-crash SDK reading (resolved 3 s later via SPDRP_WSTART
        // trigger_speed=0.00). If FN3's IMPACT speed had instead been > 25,
        // the literal ceiling would have blocked relaxation — but GPS-stale
        // bypasses this. The orientation evidence alone carries the decision
        // when the speed reading isn't trustworthy.
        val thresholds = Thresholds(
            onSideRelaxationMaxSpeedKmh = 25.0,
            crashConfirmSpeedKmh = 5,
        )
        val (sm, _) = newSm(thresholds = thresholds)
        sm.onSpeedUpdate(40.0)  // above ceiling — but we'll flag stale
        val t0 = 1_000_000L
        sm.onSample(sample(time = t0, peak = 80.0, smoothed = 30.0, gyro = 1.0,
            ax = 9.81, ay = 0.0, az = 0.0))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))
        // Mark GPS stale BEFORE accumulating samples — the bypass clause is
        // evaluated on every IMPACT sample.
        sm.setSpeedGpsStale(true)
        var t = t0 + 520L
        var transitioned = false
        repeat(30) {
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.1,
                ax = 9.81, ay = 0.0, az = 0.0))
            if (sm.state == CrashStateMachine.State.SILENCE_CHECK) transitioned = true
            t += 20L
        }
        assertTrue(
            "GPS-stale bypass must re-enable onSideRelaxed even at 40 km/h " +
                "indicated speed — FN3 stale-GPS scenario",
            transitioned,
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
    }

    @Test
    fun `FP fix - bump on first SILENCE_CHECK sample of onSideRelaxed transition does not contaminate the on-side latch`() {
        // Strongest observable test of the accelOk gating in handleSilenceCheck.
        //
        // Scenario: rider crashes on a rough section. The IMPACT-relax path fires
        // (≥ 25 on-side samples accumulated in IMPACT phase), and the very FIRST
        // SILENCE_CHECK sample happens to be a terrain bump WITH a strong +Z
        // component (e.g. the bike landed flat after a small drop, or a rebound
        // of the rear wheel). Without the fix, this bump's +Z contribution
        // pulls the accumulator's mean direction back toward upright — the
        // first computeEffectiveSilenceMs of the SILENCE_CHECK phase computes a
        // sub-45° angle and latches ORIENT_UPRIGHT (20 s window). The bump then
        // !isStill-resets (no on-side latch any more — latch is 20 s upright)
        // and the accumulator is wiped. silenceStartedMs is reset. With the
        // bike still rolling (speedDropOk false), the SM never re-acquires the
        // latch — Confirm never fires within the budget.
        //
        // With the fix: the bump is NOT accumulated. The first
        // computeEffectiveSilenceMs sees the clean carried-forward
        // 25-on-side-samples accumulator → angle ~90° → legacy 4.5 s window
        // latched + ORIENT_ONSIDE. The bump's !isStill is then absorbed by the
        // CR2 on-side preservation path (latch + accumulator survive). The
        // next quiet on-side samples confirm within ~4.5 s of SILENCE_CHECK
        // entry. The crash is detected.
        val (sm, tEnter) = smInSilenceCheckViaOnSideRelax()
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)

        // Feed a non-still sample with a strong +Z direction so a leaked
        // accumulator entry would notably shift the mean toward upright.
        // raw = 300 → deviation = 290 ≫ silenceDeviationMax = 4.
        var t = tEnter + 20L
        sm.onSample(sample(time = t, raw = 300.0, smoothed = 300.0, gyro = 0.1,
            ax = 0.0, ay = 0.0, az = 300.0))
        t += 20L

        // After the bump, the latch must reflect the clean on-side evidence.
        // Without the fix, the bump's +Z contamination would have driven the
        // latched angle below 45° and resetSilenceWindow would have wiped
        // lastOrientationAngleDeg to -1.0 — failing the assertion either way.
        assertTrue(
            "latched orientation angle must remain on-side (>= 45°) after a +Z bump on " +
                "the first SILENCE_CHECK sample — without the FP fix the bump would " +
                "pollute the accumulator and either flip the latch to ORIENT_UPRIGHT or " +
                "trigger the within-budget reset that clears the angle to -1. Got " +
                "${sm.lastOrientationAngleDeg}",
            sm.lastOrientationAngleDeg >= 45.0,
        )

        // Stronger pin: with the fix, the CR2 on-side preservation absorbed the
        // bump. Continue feeding quiet on-side samples — Confirm must fire near
        // the 4.5 s mark from SILENCE_CHECK entry, NOT push out to budget exit.
        var confirmed = false
        repeat(280) {  // 280 * 20 ms = 5.6 s — plenty of slack past 4.5 s
            val d = sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81,
                gyro = 0.1, ax = 9.81, ay = 0.0, az = 0.0))
            if (d is CrashStateMachine.Decision.Confirm) {
                confirmed = true
                return@repeat
            }
            t += 20L
        }
        assertTrue(
            "Confirm must fire — the on-side latch must have survived the bump so the " +
                "rolling-bike scenario does not block confirmation",
            confirmed,
        )
        assertEquals(
            "Confirm must use the legacy 4.5 s window (on-side), not 20 s (upright)",
            4_500L, sm.lastConfirmedSilenceMs,
        )
    }
}
