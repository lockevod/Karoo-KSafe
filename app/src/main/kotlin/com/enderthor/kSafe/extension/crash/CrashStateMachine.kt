package com.enderthor.kSafe.extension.crash

import com.enderthor.kSafe.extension.util.Clock
import com.enderthor.kSafe.extension.util.SystemClock
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Pure state machine: MONITORING → IMPACT → SILENCE_CHECK → (CRASH_CONFIRMED | back to MONITORING).
 *
 * Faithful re-expression of the algorithm specified in `docs/crash-detection-algorithm.md`
 * (May 2026, revision 5) and the production logic in [CrashDetectionManager].
 *
 * The state machine is **pure**: no Android imports, no coroutines, no time.sleep, no I/O.
 * It owns no buffers — those live in `SensorReader`/[CrashDetectionManager]. Each call to
 * [onSample] is a discrete event that may produce a [Decision] for the facade to act on.
 *
 * ### Time domains
 * All timing inside this class uses [SensorSample.timestampMs] — the timestamp carried on
 * each sensor sample. This keeps the state machine deterministic and easily testable
 * without wall-clock dependencies.
 *
 * [clock] is used **only** for the cold-start guard (`startTimeMs`), set on the first
 * [onSample] call. Cadence freshness checks also live in the sample time domain — see
 * [onCadenceUpdate].
 *
 * ### What the state machine does NOT own
 *  - Crash cooldown (`crashCooldownMs`): lives in the facade ([CrashDetectionManager]).
 *  - Speed-drop monitor: independent component ([SpeedDropMonitor]).
 *  - Grade-aware peak boost / TERRAIN_CLUSTER boost: facade.
 *  - Smoothing / variance buffering: `SensorReader`.
 *
 * The state machine just decides "Confirm" / "Enter IMPACT" / "Return to MONITORING".
 */
class CrashStateMachine(
    thresholds: Thresholds,
    private val clock: Clock = SystemClock,
) {
    /**
     * Mutable thresholds reference so the facade can apply mid-ride adjustments
     * (sensitivity-preset config changes, POST_TMO peak boost, grade-aware boost)
     * without re-instantiating the state machine. Reads are unsynchronised: writes
     * happen on Main, reads on the sensor thread. The data class is immutable so
     * a partially-written value is impossible — @Volatile ensures visibility.
     */
    @Volatile var thresholds: Thresholds = thresholds
        private set

    /** Atomically swap the thresholds. Called from the facade on config / boost changes. */
    fun setThresholds(t: Thresholds) {
        thresholds = t
    }

    /** Inject the pre-impact orientation reference. Called by the facade on impact entry. */
    fun setPreImpactReference(ref: PreImpactRef) {
        preImpactRef = ref
    }
    private companion object {
        /** Gravity reference, m/s². The whole stillness logic compares against this. */
        const val GRAVITY = 9.81

        /** Sentinel for "no speed update received yet" — distinguishes from a legit `0.0`. */
        const val SPEED_UPDATE_NEVER = 0L

        /** Minimum samples in the silence window before orientation classification kicks in.
         *  At 50 Hz this is ~100 ms — enough to filter the very first sample of jitter. */
        const val MIN_ORIENTATION_SAMPLES: Int = 5

        /** Magnitude floor below which a gravity vector is treated as numerically degenerate. */
        const val EPSILON: Double = 1e-6
    }

    enum class State { MONITORING, IMPACT, SILENCE_CHECK }

    sealed class Decision {
        data object None : Decision()
        data class EnterImpact(val reason: String) : Decision()
        data object Confirm : Decision()
        data object ReturnToMonitoring : Decision()
    }

    var state: State = State.MONITORING
        private set

    // ── Speed / GPS ──────────────────────────────────────────────────────────
    @Volatile private var lastSpeedKmh: Double = 0.0
    /**
     * Most recent GPS-stale view, sourced from [SensorSample.gpsStale] on each [onSample].
     * Lives in the sample domain so the staleness view stays current between speed pings
     * (an accel sample arrives ~50 Hz; speed pings ~1 Hz).
     */
    @Volatile private var lastSpeedGpsStale: Boolean = false
    /**
     * Wall-clock timestamp of the most recent [onSpeedUpdate]. `0L` means "never received";
     * used by the cold-start guard together with [startTimeMs].
     *
     * Updated **only** from [onSpeedUpdate] — i.e. only when a real speed reading arrives.
     * The per-sample staleness refresh in [onSample] must not touch this sentinel.
     */
    @Volatile private var speedLastUpdatedAtMs: Long = SPEED_UPDATE_NEVER

    // ── Cadence ──────────────────────────────────────────────────────────────
    @Volatile private var lastCadenceRpm: Double = 0.0
    /**
     * Sample-domain timestamp at which cadence was last received (0 = never received).
     * Set to [lastSampleMs] in [onCadenceUpdate] so it lives in the same time domain as
     * [SensorSample.timestampMs], making staleness checks meaningful.
     */
    @Volatile private var lastCadenceUpdateMs: Long = 0L

    /** Timestamp of the last sample processed by [onSample]; used by [onCadenceUpdate]. */
    @Volatile private var lastSampleMs: Long = 0L

    // ── State-machine timing ─────────────────────────────────────────────────
    /** Sample timestamp at which the IMPACT phase was entered (or 0 when MONITORING). */
    @Volatile private var impactStartedMs: Long = 0L

    /**
     * Sample timestamp at which the current uninterrupted stillness window began.
     * Reset to `now` on each `!isStill` sample (continuous stillness, not cumulative).
     */
    @Volatile private var silenceStartedMs: Long = 0L

    /**
     * Wall-clock timestamp of the first [onSample] call (or 0 before any sample has been
     * processed). Used by [isSpeedDropConfirmed] for the cold-start guard.
     */
    @Volatile private var startTimeMs: Long = 0L

    // ── Orientation: silence-window accumulator ──────────────────────────────
    /**
     * Sum of accel X/Y/Z over samples received while inside SILENCE_CHECK,
     * used to compute the average gravity-vector direction of the current
     * stillness phase. Reset every time the state machine enters or leaves
     * SILENCE_CHECK so the orientation reading reflects the current event,
     * not a stale one.
     */
    @Volatile private var silenceWindowSumX: Double = 0.0
    @Volatile private var silenceWindowSumY: Double = 0.0
    @Volatile private var silenceWindowSumZ: Double = 0.0
    @Volatile private var silenceWindowCount: Int = 0

    /**
     * Latched silence-duration choice for the current SILENCE_CHECK window.
     * `0L` means "not yet decided" (cold start or just-reset window). Once
     * `silenceWindowCount` crosses [MIN_ORIENTATION_SAMPLES] with a valid
     * pre-impact reference, [computeEffectiveSilenceMs] freezes the chosen
     * duration here for the rest of the window. Reset to `0L` by
     * [resetSilenceWindow] on every entry/exit/break of SILENCE_CHECK so each
     * event decides fresh.
     *
     * Why latch: without this, a rider standing upright for 18s with the
     * 20s window engaged could see the running average gravity vector drift
     * past the 45° threshold (slow lean, foot-down posture shift) on
     * sample 901. computeEffectiveSilenceMs would return 4500 instead of
     * 20000, the elapsed-time check would instantly satisfy, and Confirm
     * would fire — collapsing the 20s safety into "any tilt past 45° at
     * any point in the window confirms." Latching protects the safety
     * margin for the entire window.
     */
    @Volatile private var lockedEffectiveSilenceMs: Long = 0L

    /**
     * The `effectiveSilenceMs` value that was in force at the moment the last
     * `Decision.Confirm` fired. Captured BEFORE `resetSilenceWindow()` clears
     * the latch, so the facade can read it in CRASH_CONFIRMED calibration
     * rows and field analysts can tell whether the algorithm waited the
     * legacy 4.5 s, the GPS-stale 8 s, or the upright-aware 20 s.
     *
     * `0L` until the first Confirm in this ride. NOT reset between rides
     * intentionally — a fresh ride's first Confirm overwrites it.
     */
    @Volatile var lastConfirmedSilenceMs: Long = 0L
        private set

    /**
     * The `firstSilenceGapMs` value at the moment the last `Decision.Confirm` fired.
     * Captured BEFORE `resetTimers()` zeroes `firstSilenceGapMs`, so the facade
     * can read the correct gap in CRASH_CONFIRMED calibration rows.
     *
     * `0L` until the first Confirm in this ride. NOT reset between rides
     * intentionally — a fresh ride's first Confirm overwrites it.
     */
    @Volatile var lastConfirmedGapMs: Long = 0L
        private set

    /**
     * The `lastOrientationAngleDeg` value at the moment the last `Decision.Confirm` fired.
     * Captured BEFORE `resetSilenceWindow()` resets `lastOrientationAngleDeg` to -1.0,
     * so the facade can read the correct angle in CRASH_CONFIRMED calibration rows.
     *
     * `-1.0` until the first Confirm that used the orientation regime. NOT reset between
     * rides intentionally — a fresh ride's first Confirm overwrites it.
     */
    @Volatile var lastConfirmedAngleDeg: Double = -1.0
        private set

    // ── Pre-impact orientation reference (pre-impact-revision) ───────────────
    /**
     * The bike's gravity-vector direction averaged over ~2 s before the impact.
     * Set by the facade via [setPreImpactReference] when an impact is detected.
     * [PreImpactRef.INVALID] means no usable pre-impact data (cold start / just
     * after a resume) — the orientation regime then falls back to the legacy
     * short window.
     */
    @Volatile private var preImpactRef: PreImpactRef = PreImpactRef.INVALID

    /**
     * Gap between the impact and the first (and only) time the state machine reached
     * SILENCE_CHECK for this event, in the sample-time domain. `0L` until that
     * first transition. Used by [computeEffectiveSilenceMs]'s gap regime.
     */
    @Volatile var firstSilenceGapMs: Long = 0L
        private set

    /**
     * Sample-time at which the state machine FIRST entered SILENCE_CHECK for the
     * current event. `0L` until that first transition.
     *
     * This is the anchor for the false-alarm "give up" retry budget in
     * [handleSilenceCheck]. The budget MUST be measured from SILENCE_CHECK entry,
     * NOT from the impact: with a long impact→stillness gap the silence window can
     * be up to 20 s (gap / upright regimes), and an impact-relative cutoff would
     * expire before a real crash could ever complete a full silence window after
     * the latest stillness break — dropping a genuine crash to MONITORING with no
     * alert (false negative). Anchoring on entry guarantees a full silence window
     * always fits after the latest break, regardless of how late SILENCE_CHECK
     * was reached.
     */
    @Volatile var silenceCheckEnteredMs: Long = 0L
        private set

    /**
     * Angle (degrees) between the pre-impact reference and the silence-window
     * gravity vector, as computed on the last [computeEffectiveSilenceMs] call
     * that reached the orientation branch. `-1.0` when not computed (gap regime,
     * invalid reference, or too few silence samples). For calibration logging only.
     */
    @Volatile var lastOrientationAngleDeg: Double = -1.0
        private set

    /** Snapshot of the current pre-impact reference. For calibration logging. */
    val preImpactReference: PreImpactRef get() = preImpactRef

    // ── Public API ───────────────────────────────────────────────────────────

    fun onSample(sample: SensorSample): Decision {
        val now = sample.timestampMs
        lastSampleMs = now
        // Refresh the staleness view on every sample. This MUST NOT touch
        // [speedLastUpdatedAtMs] — that sentinel marks "real speed update received"
        // and powers the cold-start guard.
        lastSpeedGpsStale = sample.gpsStale
        if (startTimeMs == 0L) startTimeMs = clock.nowMs()

        return when (state) {
            State.MONITORING -> handleMonitoring(sample, now)
            State.IMPACT -> handleImpact(sample, now)
            State.SILENCE_CHECK -> handleSilenceCheck(sample, now)
        }
    }

    /**
     * Real speed-update event handler. Call this **only** when an actual speed reading
     * arrived (from the SDK speed stream) — not on every accel sample. The per-sample
     * staleness signal travels on [SensorSample.gpsStale] instead.
     */
    fun onSpeedUpdate(speedKmh: Double) {
        lastSpeedKmh = speedKmh
        speedLastUpdatedAtMs = clock.nowMs()
    }

    fun onCadenceUpdate(cadenceRpm: Double) {
        lastCadenceRpm = cadenceRpm
        // Use lastSampleMs (sample-domain) so staleness checks stay in the same time domain
        // as the sensor samples. If no sample has been processed yet, lastSampleMs is 0 —
        // which serves as the "never received in-ride" sentinel for [isCadenceActive].
        lastCadenceUpdateMs = lastSampleMs
    }

    fun onPause() {
        state = State.MONITORING
        impactStartedMs = 0L
        silenceStartedMs = 0L
        // Drop accumulated orientation data — a pause invalidates the current
        // silence window. Without this, a SILENCE_CHECK that was in progress
        // when the rider paused would pollute the next SILENCE_CHECK event
        // with stale gravity-vector samples.
        resetSilenceWindow()
        preImpactRef = PreImpactRef.INVALID
        firstSilenceGapMs = 0L
        silenceCheckEnteredMs = 0L
        lastOrientationAngleDeg = -1.0
    }

    fun reset() {
        state = State.MONITORING
        impactStartedMs = 0L
        silenceStartedMs = 0L
        lastSpeedKmh = 0.0
        lastSpeedGpsStale = false
        speedLastUpdatedAtMs = SPEED_UPDATE_NEVER
        // Note: lastSpeedGpsStale will be refreshed from the next sample's gpsStale.
        lastCadenceRpm = 0.0
        lastCadenceUpdateMs = 0L
        lastSampleMs = 0L
        startTimeMs = 0L
        silenceWindowSumX = 0.0
        silenceWindowSumY = 0.0
        silenceWindowSumZ = 0.0
        silenceWindowCount = 0
        lockedEffectiveSilenceMs = 0L
        preImpactRef = PreImpactRef.INVALID
        firstSilenceGapMs = 0L
        silenceCheckEnteredMs = 0L
        lastOrientationAngleDeg = -1.0
    }

    /**
     * Reset timing and per-event state (impact window, silence window, cadence,
     * speed, pre-impact reference) for a ride RESUME after pause. A pause-resume
     * in the middle of a ride (traffic light, regroup, mechanical) is NOT a
     * fresh ride, but every per-event signal — the impact/silence windows, the
     * silence-window orientation accumulator and the pre-impact reference —
     * belongs to a specific impact event and must start clean.
     *
     * Functionally equivalent to [reset] for the current implementation; kept
     * as a distinct entry point so the facade can express "ride resume" intent
     * and so future ride-scoped (vs event-scoped) state can diverge here.
     */
    fun resumeForRide() {
        state = State.MONITORING
        impactStartedMs = 0L
        silenceStartedMs = 0L
        lastSpeedKmh = 0.0
        lastSpeedGpsStale = false
        speedLastUpdatedAtMs = SPEED_UPDATE_NEVER
        lastCadenceRpm = 0.0
        lastCadenceUpdateMs = 0L
        lastSampleMs = 0L
        startTimeMs = 0L
        silenceWindowSumX = 0.0
        silenceWindowSumY = 0.0
        silenceWindowSumZ = 0.0
        silenceWindowCount = 0
        lockedEffectiveSilenceMs = 0L
        preImpactRef = PreImpactRef.INVALID
        firstSilenceGapMs = 0L
        silenceCheckEnteredMs = 0L
        lastOrientationAngleDeg = -1.0
    }

    // ── State handlers ───────────────────────────────────────────────────────

    /**
     * Phase 1 — MONITORING.
     *
     * Entry to IMPACT requires:
     *   1. impactDetected = (smoothed > smoothedThr) OR (peak > peakThr)
     *   2. Speed gate: `currentSpeedKmh >= minSpeedForCrashKmh` OR `minSpeedForCrashKmh == 0`.
     *
     * Strict `>` is verbatim with the pre-refactor monolith
     * (`smoothedMagnitude > threshold || magnitude > effectivePeakThreshold`).
     *
     * The speed gate intentionally **does not** bypass on `gpsStale=true`. The doc only
     * bypasses speed in [isSpeedDropConfirmed] (used in IMPACT/SILENCE_CHECK), not in the
     * MONITORING spike gate.
     *
     * Gyro is intentionally NOT an entry path: hard cornering / shoulder checks routinely
     * produce 4–10 rad/s on a bike and would create false positives. Production code never
     * used a gyro entry branch.
     */
    private fun handleMonitoring(sample: SensorSample, now: Long): Decision {
        val isImpact =
            sample.peakMagnitude > thresholds.peakImpactThreshold ||
            sample.smoothedMagnitude > thresholds.smoothedImpactThreshold
        if (!isImpact) return Decision.None

        val speedOk = thresholds.minSpeedForCrashKmh == 0 ||
            lastSpeedKmh >= thresholds.minSpeedForCrashKmh.toDouble()
        if (!speedOk) return Decision.None

        state = State.IMPACT
        impactStartedMs = now
        silenceStartedMs = 0L

        val reason = when {
            sample.peakMagnitude > thresholds.peakImpactThreshold &&
                sample.smoothedMagnitude > thresholds.smoothedImpactThreshold -> "BOTH"
            sample.peakMagnitude > thresholds.peakImpactThreshold -> "PEAK"
            else -> "SMOOTH"
        }
        return Decision.EnterImpact(reason)
    }

    /**
     * Phase 2 — IMPACT.
     *
     * Transition to SILENCE_CHECK requires **all four** of:
     *   1. `|magnitude - 9.81| < silenceDeviationMax` (GPS-fresh) or `< gpsStaleSilenceDeviationMax` (GPS-stale)
     *   2. `gyroMag < gyroMovingMax`
     *   3. `timeSinceImpact > minTimeSinceImpactMs`
     *   4. `isSpeedDropConfirmed()`
     *
     * Per the doc, the gyro gate appears here at IMPACT → SILENCE_CHECK entry but
     * is **intentionally not re-evaluated inside SILENCE_CHECK** (freewheel case).
     *
     * Times out to MONITORING when `timeSinceImpact > impactWindowMs`.
     */
    private fun handleImpact(sample: SensorSample, now: Long): Decision {
        val timeSinceImpact = now - impactStartedMs
        val gpsStale = lastSpeedGpsStale
        val deviationMax = if (gpsStale) thresholds.gpsStaleSilenceDeviationMax
                           else thresholds.silenceDeviationMax

        // Use rawMagnitude — production CrashDetectionManager.processAccelerometer() uses
        // `abs(magnitude - GRAVITY)` (raw, not smoothed). Behavioural-equivalence requirement.
        val deviation = abs(sample.rawMagnitude - GRAVITY)

        val accelOk = deviation < deviationMax
        val gyroOk = sample.gyroMag < thresholds.gyroMovingMax
        val timeOk = timeSinceImpact > thresholds.minTimeSinceImpactMs
        val speedDropOk = isSpeedDropConfirmed()

        if (accelOk && gyroOk && timeOk && speedDropOk) {
            state = State.SILENCE_CHECK
            silenceStartedMs = now
            // First (and only) IMPACT → SILENCE_CHECK transition of this event:
            // freeze how long the rider kept moving after the impact.
            firstSilenceGapMs = now - impactStartedMs
            // Anchor the false-alarm retry budget to SILENCE_CHECK entry, so a late
            // entry still gets a full budget and a full silence window can complete
            // after the latest stillness break.
            silenceCheckEnteredMs = now
            return Decision.None
        }

        if (timeSinceImpact > thresholds.impactWindowMs) {
            // False alarm: never settled within the impact window.
            resetTimers()
            state = State.MONITORING
            return Decision.ReturnToMonitoring
        }

        return Decision.None
    }

    /**
     * Phase 3 — SILENCE_CHECK.
     *
     * `isStill = (deviation <= effective_max) AND isSpeedDropConfirmed()`
     *
     * Per the doc, gyro is intentionally **not** part of `isStill` — the freewheel case
     * (bike on its side, rear wheel spinning) would otherwise block a valid crash.
     *
     * Outcomes:
     *   - `isStill` AND elapsed >= effectiveSilenceMs → Decision.Confirm
     *   - `isStill` AND not yet elapsed → keep counting (None)
     *   - `!isStill` AND within `impactWindowMs * 2` of SILENCE_CHECK entry → reset
     *     silence clock to `now` (retry)
     *   - `!isStill` AND beyond `impactWindowMs * 2` of SILENCE_CHECK entry → false
     *     alarm, return to MONITORING
     *
     * The retry budget is measured from SILENCE_CHECK ENTRY ([silenceCheckEnteredMs]),
     * not from the impact. A long impact→stillness gap can engage a 20 s silence
     * window; an impact-relative cutoff would expire before a genuinely-injured rider
     * could complete one full silence window after their latest twitch — a false
     * negative. Entry-relative anchoring guarantees a full window always fits.
     *
     * Cadence gate: per doc Revision 4 C5, cadence > 20 RPM during SILENCE_CHECK is an
     * instant false-alarm exit (an unconscious rider cannot pedal).
     */
    private fun handleSilenceCheck(sample: SensorSample, now: Long): Decision {
        // Cadence gate (instant false-alarm exit): only when cadence sensor present + active.
        if (isCadenceActive(now)) {
            resetTimers()
            resetSilenceWindow()
            state = State.MONITORING
            return Decision.ReturnToMonitoring
        }

        val gpsStale = lastSpeedGpsStale
        val deviationMax = if (gpsStale) thresholds.gpsStaleSilenceDeviationMax
                           else thresholds.silenceDeviationMax

        // Accumulate X/Y/Z for orientation classification (Revision 5).
        silenceWindowSumX += sample.accelX
        silenceWindowSumY += sample.accelY
        silenceWindowSumZ += sample.accelZ
        silenceWindowCount++

        // Use rawMagnitude — production CrashDetectionManager.processAccelerometer() uses
        // `abs(magnitude - GRAVITY)` (raw, not smoothed). Behavioural-equivalence requirement.
        val deviation = abs(sample.rawMagnitude - GRAVITY)
        val accelOk = deviation <= deviationMax
        val speedDropOk = isSpeedDropConfirmed()
        val isStill = accelOk && speedDropOk

        val effectiveSilenceMs = computeEffectiveSilenceMs(gpsStale)

        return when {
            isStill && (now - silenceStartedMs) >= effectiveSilenceMs -> {
                // CONFIRMED. Capture the actual silence window that fired
                // before resetSilenceWindow() clears the latch — the facade
                // reads this for CRASH_CONFIRMED diagnostic logging.
                lastConfirmedSilenceMs = effectiveSilenceMs
                // Snapshot gap and angle BEFORE resetTimers()/resetSilenceWindow() zero them,
                // so the facade reads the values that were in force at confirmation time.
                lastConfirmedGapMs = firstSilenceGapMs
                lastConfirmedAngleDeg = lastOrientationAngleDeg
                resetTimers()
                resetSilenceWindow()
                state = State.MONITORING
                Decision.Confirm
            }
            isStill -> Decision.None  // keep counting
            !isStill && (now - silenceCheckEnteredMs) <= thresholds.impactWindowMs * 2 -> {
                // Stillness must be continuous: restart silence clock on every break.
                silenceStartedMs = now
                // Drop accumulated orientation data — the new silence window starts now.
                resetSilenceWindow()
                Decision.None
            }
            else -> {
                // Beyond doubled window — false alarm.
                resetTimers()
                resetSilenceWindow()
                state = State.MONITORING
                Decision.ReturnToMonitoring
            }
        }
    }

    /**
     * Decide the silence-window duration from two regimes (see the design spec):
     *   - Gap regime: a long impact→stillness gap means the rider kept riding
     *     after the impact → false-positive-prone → require the 20 s window.
     *   - Orientation regime (prompt stops only): the angle between the
     *     pre-impact reference and the silence-window gravity vector decides
     *     on-side (legacy short window) vs upright (20 s window).
     * The chosen value is latched for the rest of the window.
     */
    private fun computeEffectiveSilenceMs(gpsStale: Boolean): Long {
        if (lockedEffectiveSilenceMs > 0L) return lockedEffectiveSilenceMs

        val legacyShort = if (gpsStale) thresholds.gpsStaleSilenceDurationMs
                          else thresholds.silenceDurationMs

        // Gap regime — delayed stop. Needs no orientation data.
        if (firstSilenceGapMs > thresholds.delayedStopGapMs) {
            lockedEffectiveSilenceMs = thresholds.silenceDurationUprightMs
            return lockedEffectiveSilenceMs
        }

        // Orientation regime — prompt stop.
        if (!preImpactRef.valid) {
            lockedEffectiveSilenceMs = legacyShort   // never becomes valid → latch now
            return legacyShort
        }
        if (silenceWindowCount < MIN_ORIENTATION_SAMPLES) return legacyShort  // may still grow

        val n = silenceWindowCount.toDouble()
        val curX = silenceWindowSumX / n
        val curY = silenceWindowSumY / n
        val curZ = silenceWindowSumZ / n
        val curMag = sqrt(curX * curX + curY * curY + curZ * curZ)
        val refMag = sqrt(
            preImpactRef.x * preImpactRef.x +
            preImpactRef.y * preImpactRef.y +
            preImpactRef.z * preImpactRef.z
        )
        // Degenerate vector — intentionally re-entrant defensive guard. A gravity-laden
        // accelerometer average cannot have magnitude < EPSILON (1e-6) in the field;
        // this branch is physically unreachable while gravity is present. It is left
        // un-latched deliberately: if a transient numerical anomaly ever produced a
        // near-zero average, the next valid sample would still reach the orientation
        // branch and produce a real decision, rather than being forever locked into a
        // degenerate latch. This is the only post-MIN_ORIENTATION_SAMPLES path that
        // returns without setting lockedEffectiveSilenceMs.
        if (curMag < EPSILON || refMag < EPSILON) return legacyShort

        val cosAngle = ((curX * preImpactRef.x + curY * preImpactRef.y + curZ * preImpactRef.z)
                       / (curMag * refMag)).coerceIn(-1.0, 1.0)
        val angleDeg = Math.toDegrees(acos(cosAngle))
        lastOrientationAngleDeg = angleDeg

        val chosen = if (angleDeg >= thresholds.uprightAngleThresholdDegrees) legacyShort
                     else thresholds.silenceDurationUprightMs
        lockedEffectiveSilenceMs = chosen
        return chosen
    }

    private fun resetSilenceWindow() {
        silenceWindowSumX = 0.0
        silenceWindowSumY = 0.0
        silenceWindowSumZ = 0.0
        silenceWindowCount = 0
        lockedEffectiveSilenceMs = 0L
        lastOrientationAngleDeg = -1.0
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Mirror of `CrashDetectionManager.isSpeedDropConfirmed()`.
     *
     *  1. **Cold-start guard**: if no speed update has been received and
     *     `clock.nowMs() - startTimeMs < coldStartGuardMs`, return `false` to block
     *     confirmation. We can't trust `currentSpeedKmh = 0.0` (its uninitialised
     *     default) yet.
     *  2. **GPS stale**: returns `true`, bypassing the speed gate. The caller is
     *     expected to apply hardened deviation/duration thresholds.
     *  3. **Default**: `(crashConfirmSpeedKmh == 0)` disables the gate explicitly,
     *     otherwise `currentSpeedKmh < crashConfirmSpeedKmh`.
     *
     * Uses **wall-clock** (`clock.nowMs()`) intentionally for the elapsed-since-start
     * computation: the rest of the state machine works in sample-time, but the cold-start
     * guard is about "the SDK has had time to send us a real speed event", which is a
     * wall-clock concept and lives in the same domain as `speedLastUpdatedAtMs`.
     * No `nowMs` parameter — pass-through callers don't need to pre-snapshot wall-clock.
     */
    private fun isSpeedDropConfirmed(): Boolean {
        val wall = clock.nowMs()
        // 1. Cold-start guard — block when no speed received and still inside the guard window.
        if (speedLastUpdatedAtMs == SPEED_UPDATE_NEVER &&
            (wall - startTimeMs) < thresholds.coldStartGuardMs) {
            return false
        }
        // 2. GPS-stale: bypass speed gate (caller hardens accel thresholds to compensate).
        if (lastSpeedGpsStale) return true
        // 3. Speed gate proper. crashConfirmSpeedKmh == 0 explicitly disables the gate.
        return thresholds.crashConfirmSpeedKmh == 0 ||
            lastSpeedKmh < thresholds.crashConfirmSpeedKmh.toDouble()
    }

    /**
     * Returns true when fresh cadence data indicates the rider is actively pedalling.
     *
     * Returns false in three cases:
     *   - [lastCadenceUpdateMs] == 0L: no cadence data has been received this session
     *     (no sensor paired, or cadence callback fired before the first sample).
     *   - Age of last update exceeds `cadenceStaleThresholdMs`: sensor disconnected.
     *   - [lastCadenceRpm] <= `cadenceQuietThresholdRpm`: rider coasting / not pedalling.
     */
    private fun isCadenceActive(nowSampleMs: Long): Boolean {
        if (lastCadenceUpdateMs == 0L) return false
        val age = nowSampleMs - lastCadenceUpdateMs
        if (age > thresholds.cadenceStaleThresholdMs) return false
        return lastCadenceRpm > thresholds.cadenceQuietThresholdRpm
    }

    private fun resetTimers() {
        impactStartedMs = 0L
        silenceStartedMs = 0L
        firstSilenceGapMs = 0L
        silenceCheckEnteredMs = 0L
    }
}
