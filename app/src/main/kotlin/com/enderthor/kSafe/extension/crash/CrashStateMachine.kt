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
 * (May 2026, revision 7 — IMPACT-phase on-side relaxation) and the production logic
 * in [CrashDetectionManager].
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

        /** Sentinel for [cadenceLastChangeMs]: the cadence value has never changed. */
        const val CADENCE_CHANGE_NEVER = -1L

        /** Minimum samples in the silence window before orientation classification kicks in.
         *  At 50 Hz this is ~100 ms — enough to filter the very first sample of jitter. */
        const val MIN_ORIENTATION_SAMPLES: Int = 5

        /** Minimum samples in the IMPACT-phase orientation accumulator before
         *  the on-side speed-rise relaxation can engage. At ~50 Hz this is
         *  ~500 ms of sustained on-side accel-still — stricter than
         *  [MIN_ORIENTATION_SAMPLES] because bypassing the speed gate at
         *  IMPACT→SILENCE_CHECK transition is more consequential than the
         *  in-SILENCE_CHECK latch decision. */
        const val IMPACT_RELAXATION_MIN_SAMPLES: Int = 25

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

    @Volatile var state: State = State.MONITORING
        private set

    // ── Speed / GPS ──────────────────────────────────────────────────────────
    @Volatile private var lastSpeedKmh: Double = 0.0
    /**
     * Most recent GPS-stale view, pushed in by the facade via [setSpeedGpsStale] whenever
     * its derived staleness flag flips (per the SDK speed-staleness model — the value-
     * change-based detector in [CrashDetectionManager]). Read by IMPACT / SILENCE_CHECK on
     * every sample.
     *
     * **P1 fix (May 2026)** — decoupled from [onSample]: previously each accel tick read
     * [SensorSample.gpsStale] which forced the facade to `.copy()` the sample whenever
     * the staleness flag disagreed with the raw sample. During a GPS-stale stretch
     * (tunnel, dense forest) the disagreement is permanent and the per-tick copy was
     * allocating ~14 MB / hour of young-gen GC. With the setter pattern the hot path
     * stays allocation-free.
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
    /**
     * Most recent cadence value (RPM). Initialised to [Double.NaN] — a sentinel
     * meaning "no cadence value received in this session." [NaN] can never equal
     * any real cadence reading, so the first [onCadenceUpdate] call is NOT counted
     * as a value change; only subsequent calls that carry a DIFFERENT value from
     * the previous one set [cadenceLastChangeMs]. This prevents an ANT+ cadence
     * sensor that has just (re)connected from being immediately classified as
     * "actively pedalling" before we have seen it fluctuate.
     */
    @Volatile private var lastCadenceRpm: Double = Double.NaN
    /**
     * Sample-domain timestamp at which cadence was last received (0 = never received).
     * Set to [lastSampleMs] in [onCadenceUpdate] so it lives in the same time domain as
     * [SensorSample.timestampMs], making staleness checks meaningful.
     */
    @Volatile private var lastCadenceUpdateMs: Long = 0L
    /**
     * Sample-domain timestamp at which the cadence VALUE last changed (bit-exact).
     * Sentinel `-1L` = "no value change has ever been observed in this session."
     *
     * A cadence sensor that has lost signal repeats its last value bit-exact while
     * the SDK keeps emitting it — so [lastCadenceUpdateMs] (freshness-by-emission)
     * stays current and cannot detect the stall. This field tracks
     * freshness-by-change instead: a genuinely pedalling rider's cadence fluctuates
     * every revolution, a stuck sensor's does not.
     *
     * [isCadenceActive] returns `false` when this is `-1L` (sensor never actually
     * changed its value → stuck sensor from the start of the session) and also when
     * `nowSampleMs - cadenceLastChangeMs > cadenceStaleThresholdMs` (sensor stopped
     * changing mid-ride → post-signal-loss stuck reading).
     */
    @Volatile private var cadenceLastChangeMs: Long = CADENCE_CHANGE_NEVER

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

    // ── Orientation: accumulator (IMPACT + SILENCE_CHECK) ───────────────────
    /**
     * Running sum of accel X/Y/Z over samples received during the current
     * orientation-accumulation window. Accumulated during IMPACT (gated on
     * `accelOk` to filter post-impact noise — see [handleImpact]) and during
     * SILENCE_CHECK (unconditional — `!isStill` samples drop the window via
     * [resetSilenceWindow], so a separate per-sample gate is redundant there).
     *
     * On the IMPACT→SILENCE_CHECK transition the accumulator is conditionally
     * reset: full reset on the `speedDropOk` path; carried forward on the
     * `onSideRelaxed` path so the latch can fire on the first SILENCE_CHECK
     * sample (see the asymmetric branch in [handleImpact]).
     */
    @Volatile private var orientationSumX: Double = 0.0
    @Volatile private var orientationSumY: Double = 0.0
    @Volatile private var orientationSumZ: Double = 0.0
    @Volatile private var orientationSampleCount: Int = 0

    /**
     * Latched silence-duration choice for the current SILENCE_CHECK window.
     * `0L` means "not yet decided" (cold start or just-reset window). Once
     * `orientationSampleCount` crosses [MIN_ORIENTATION_SAMPLES] with a valid
     * pre-impact reference, [computeEffectiveSilenceMs] freezes the chosen
     * duration here for the rest of the window. Reset to `0L` either by
     * [resetSilenceWindow] (every entry/exit/break of SILENCE_CHECK on the
     * regular paths) or directly by the `onSideRelaxed` branch of
     * [handleImpact] (which clears just the latch markers while preserving
     * the accumulator). Either way: each SILENCE_CHECK event decides fresh.
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
     * [handleSilenceCheck]. The budget (`impactWindowMs * 2`) is measured from
     * SILENCE_CHECK entry, NOT from the impact, so a late entry is not penalised
     * (an impact-relative cutoff would expire before a rider with a long
     * impact→stillness gap could complete a full silence window). The budget
     * bounds how long the machine keeps retrying to achieve uninterrupted
     * stillness: a continuously-still rider always reaches the confirm branch
     * because the give-up condition only fires on non-still samples. A rider who
     * breaks stillness again after the budget edge can still be dropped to
     * MONITORING — the budget does not guarantee that an arbitrarily late
     * stillness break is tolerated.
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

    /**
     * Set to `true` on the most recent [onSample] call when CAD_GATE would have
     * fired ([isCadenceActive] returned `true`) but suppression engaged because
     * the live orientation evidence shows the device is decisively non-upright
     * (`currentOrientationAngleDeg() >= uprightAngleThresholdDegrees`).
     *
     * Rationale: a bike on its side or upside down **cannot** be pedalled at any
     * RPM. When the SDK still reports a non-zero cadence post-impact, the most
     * likely explanations are (a) the sensor was changing value before the crash
     * and the SDK is echoing the last live reading, (b) crank/wheel rotation
     * during the impact registered a few phantom revolutions, or (c) the value
     * fluctuated within the sensor's noise floor — none of which represent an
     * awake rider pedalling. Allowing CAD_GATE to fire in this state produced
     * the FN2 / FN3 misses on the 2026-05-25 long ride (bike decisively on side
     * post-fall, cadence sensor still reading 65–88 RPM, CAD_GATE killed the
     * silence check in <1 s).
     *
     * Read by the facade once per [onSample] return to emit a diagnostic
     * `CAD_GATE_SUPPRESSED` calibration row. The state machine clears the flag
     * automatically on the next sample (it is a per-sample edge, not a
     * latched state) — the facade must read it BEFORE the next [onSample] call.
     */
    @Volatile var lastCadenceGateSuppressed: Boolean = false
        private set

    /**
     * Live angle that triggered the CAD_GATE suppression on the most recent
     * [onSample] call (degrees vs pre-impact reference). Captured BEFORE the
     * silence-window orientation latch updates `lastOrientationAngleDeg`,
     * which is what made the original diagnostic log read -1.0 on the very
     * first suppression sample (the suppression decision uses the live angle;
     * the latch is set later by `computeEffectiveSilenceMs`).
     *
     * `-1.0` when no suppression fired this tick. Read by the facade only
     * when [lastCadenceGateSuppressed] is `true`.
     */
    @Volatile var lastCadenceGateSuppressedAngleDeg: Double = -1.0
        private set

    /** Snapshot of the current pre-impact reference. For calibration logging. */
    val preImpactReference: PreImpactRef get() = preImpactRef

    // ── Public API ───────────────────────────────────────────────────────────

    fun onSample(sample: SensorSample): Decision {
        val now = sample.timestampMs
        lastSampleMs = now
        // P1 — staleness no longer flows on the sample; it's pushed in via
        // [setSpeedGpsStale] by the facade on transition. Keeps the 50 Hz hot path
        // allocation-free (avoids a sample.copy on every tick during GPS-stale stretches).
        if (startTimeMs == 0L) startTimeMs = clock.nowMs()
        // Per-sample edge — diagnostic flags. Clear before any handler runs so the
        // facade can read them after onSample returns and not see a stale value
        // from a previous sample. Handlers set them to true when their condition
        // triggers; if no handler sets them, they stay false for this tick.
        lastCadenceGateSuppressed = false
        lastCadenceGateSuppressedAngleDeg = -1.0

        return when (state) {
            State.MONITORING -> handleMonitoring(sample, now)
            State.IMPACT -> handleImpact(sample, now)
            State.SILENCE_CHECK -> handleSilenceCheck(sample, now)
        }
    }

    /**
     * Real speed-update event handler. Call this **only** when an actual speed reading
     * arrived (from the SDK speed stream) — not on every accel sample. The staleness
     * signal is pushed separately via [setSpeedGpsStale].
     */
    fun onSpeedUpdate(speedKmh: Double) {
        lastSpeedKmh = speedKmh
        speedLastUpdatedAtMs = clock.nowMs()
    }

    /**
     * Update the GPS-staleness view used by IMPACT / SILENCE_CHECK. Called by the facade
     * whenever its derived staleness flag changes (or on every sample — the write is a
     * cheap volatile store and the facade currently debounces via `lastGpsStaleState`).
     *
     * MUST NOT touch [speedLastUpdatedAtMs] — that sentinel marks "real speed update
     * received" and powers the cold-start guard. P1 fix decouples staleness from
     * [SensorSample] so the accel hot path can stay allocation-free.
     */
    fun setSpeedGpsStale(stale: Boolean) {
        lastSpeedGpsStale = stale
    }

    fun onCadenceUpdate(cadenceRpm: Double) {
        // J5 — drop NaN / Infinity from the SDK. The Double.NaN sentinel below is
        // SUPPOSED to mean "no prior reading". An incoming NaN would be stored as
        // lastCadenceRpm via line 356, conflating it with the "never seen" state
        // and disabling the freshness-by-change cross-check for the rest of the
        // ride. Infinity would similarly never match a comparison (IEEE 754).
        if (!cadenceRpm.isFinite()) return
        // Record a change only when the value actually moved AND we already have a real
        // prior value ([lastCadenceRpm] is NOT [Double.NaN]). The NaN sentinel prevents
        // the very first cadence reading (which always differs from NaN) from counting
        // as a "change" — a stuck sensor that first appears at 68 RPM must accumulate
        // at least one genuine fluctuation before the change-time is stamped.
        //
        // Invariant: [cadenceLastChangeMs] is stamped with [lastSampleMs] (the sample-time
        // domain), so it is only meaningful after at least one [onSample] has advanced
        // [lastSampleMs] beyond 0. Before the first [onSample], [lastCadenceUpdateMs]
        // remains 0 — which is the "never received in-ride" sentinel — and
        // [isCadenceActive]'s first guard (`lastCadenceUpdateMs == 0L`) short-circuits
        // before [cadenceLastChangeMs] is ever consulted.
        if (!lastCadenceRpm.isNaN() && cadenceRpm != lastCadenceRpm) {
            cadenceLastChangeMs = lastSampleMs
        }
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
        lastCadenceRpm = Double.NaN
        lastCadenceUpdateMs = 0L
        cadenceLastChangeMs = CADENCE_CHANGE_NEVER
        lastSampleMs = 0L
        startTimeMs = 0L
        orientationSumX = 0.0
        orientationSumY = 0.0
        orientationSumZ = 0.0
        orientationSampleCount = 0
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
        lastCadenceRpm = Double.NaN
        lastCadenceUpdateMs = 0L
        cadenceLastChangeMs = CADENCE_CHANGE_NEVER
        lastSampleMs = 0L
        startTimeMs = 0L
        orientationSumX = 0.0
        orientationSumY = 0.0
        orientationSumZ = 0.0
        orientationSampleCount = 0
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

        // Accumulate orientation during IMPACT on settled samples only. accelOk
        // filters the impact transient and any post-impact tumble noise, so the
        // average reflects the bike's actual resting orientation. The transition
        // to SILENCE_CHECK below resets the accumulator on the speed-drop path
        // (so SILENCE_CHECK starts with a fresh count, preserving the latch's
        // MIN_ORIENTATION_SAMPLES semantics) — but DELIBERATELY carries it
        // forward on the on-side relaxation path. The conditional reset is
        // explained at the gate-success branch; do not "simplify" by unifying
        // the two paths.
        if (accelOk) {
            orientationSumX += sample.accelX
            orientationSumY += sample.accelY
            orientationSumZ += sample.accelZ
            orientationSampleCount++
        }

        // On-side relaxation: bypass the speed gate when there is decisive
        // evidence the bike is on the ground. A bike on its side cannot be
        // ridden, so if the bike is moving (speed-drop gate is open because
        // the bike rolled after the rider went down), accelerometer
        // stillness plus on-side orientation is sufficient to transition
        // into SILENCE_CHECK. Other gates (accelOk, gyroOk, timeOk) remain
        // as the strong false-positive guards — handling motion, sustained
        // rotation (cornering), or insufficient settle time all block this
        // path. The 25-sample minimum prevents a transient angle flicker
        // from bypassing speed; ~500 ms of sustained on-side evidence is
        // required.
        // Note: a SILENCE_CHECK-scoped `onSideRelaxed` with the same name exists
        // in handleSilenceCheck — same physical meaning ("decisive on-side
        // evidence"), but THIS one is recomputed live from the IMPACT accumulator
        // while the SILENCE_CHECK one reads the latched value. Intentional name
        // sharing for conceptual unity across phases.
        //
        // Capture the live orientation angle here so we can both (a) gate the
        // relaxation and (b) propagate the same value to SILENCE_CHECK on the
        // relaxation transition (CR3 fix below). Re-evaluating
        // currentOrientationAngleDeg() after the transition is not safe — the
        // first SILENCE_CHECK sample feeds new X/Y/Z into the accumulator and
        // can shift the running average enough to fall below the 60° gate
        // before the latch can read it.
        val angleNow = currentOrientationAngleDeg()
        // FP-fix (2026-05-25, elapsed 14141.2 s): also gate the relaxation on
        // current speed. The relaxation's stated purpose is "the bike rolled
        // after the rider went down" — a scenario that decelerates within a
        // few seconds. A bike maintaining ≥ 25 km/h sustained for the entire
        // IMPACT window is incompatible with that scenario; what actually
        // happens at that speed is a rider on a rough descent crossing the
        // SMOOTH gate marginally, with the IMPACT-phase accumulator filling
        // with on-side samples from sustained forward lean.
        //
        // GPS-stale bypasses the ceiling — when the SDK speed reading isn't
        // trustworthy (tunnels, dense cover, post-crash GPS loss with a
        // pinned device) the orientation evidence alone is allowed to carry
        // the decision, matching `isSpeedDropConfirmed`'s own GPS-stale
        // bypass.
        //
        // Empirical separation from the 2026-05-25 ride log: four real falls
        // all had IMPACT speeds 5.8 / 6.7 / 8.9 / 15.0 km/h; the FP sat at
        // 34.7 km/h sustained — clean 10 km/h gap. The 25 km/h ceiling is
        // configurable via [Thresholds.onSideRelaxationMaxSpeedKmh].
        val onSideRelaxedSpeedOk = lastSpeedGpsStale ||
            lastSpeedKmh < thresholds.onSideRelaxationMaxSpeedKmh
        val onSideRelaxed = orientationSampleCount >= IMPACT_RELAXATION_MIN_SAMPLES &&
                            angleNow >= thresholds.onSideRelaxationAngleDeg &&
                            onSideRelaxedSpeedOk
        val gateOk = accelOk && gyroOk && timeOk && (speedDropOk || onSideRelaxed)
        if (gateOk) {
            state = State.SILENCE_CHECK
            silenceStartedMs = now
            // First (and only) IMPACT → SILENCE_CHECK transition of this event:
            // freeze how long the rider kept moving after the impact.
            firstSilenceGapMs = now - impactStartedMs
            // Anchor the false-alarm retry budget to SILENCE_CHECK entry so a late
            // entry is not penalised. A continuously-still rider always confirms;
            // the budget bounds how long the machine retries to achieve stillness.
            silenceCheckEnteredMs = now
            if (onSideRelaxed) {
                // Carry the IMPACT-phase orientation accumulator into SILENCE_CHECK.
                // The 25 on-side samples that proved the bike is on its side are still
                // valid evidence; resetting them would force SILENCE_CHECK to rebuild
                // the orientation latch from scratch while speed is still high — making
                // the latch unreachable (each non-still sample resets the accumulator
                // before it can reach MIN_ORIENTATION_SAMPLES). Only the latch marker
                // is cleared so that SILENCE_CHECK re-evaluates its own window choice
                // cleanly from the preserved orientation data.
                //
                // Asymmetric-reset family: the same "on-side latch is durable evidence"
                // principle drives the SILENCE_CHECK within-budget-break path
                // (`previousLatchWasOnSide` in [handleSilenceCheck]'s !isStill branch).
                // A within-budget bump while the bike is still rolling must NOT wipe
                // the latch either, for the same reason it must not be wiped here at
                // the IMPACT→SILENCE_CHECK transition. Both branches together form the
                // "real crash with bike still moving" safety net (CR2, May 2026).
                lockedEffectiveSilenceMs = 0L
                // CR3 (May 2026) — propagate the decisive-evidence angle that just
                // fired the IMPACT-relax gate so it composes with the SILENCE_CHECK
                // gap regime. When firstSilenceGapMs > delayedStopGapMs (e.g. the
                // bike rolled / tumbled for 9 s after impact before accel settled),
                // computeEffectiveSilenceMs takes the gap-regime branch and latches
                // the 20 s window WITHOUT touching lastOrientationAngleDeg. If we
                // cleared the angle here to -1.0, the SILENCE_CHECK on-side
                // relaxation gate (`lockedEffectiveSilenceMs > 0L && angle >= 60°`)
                // would evaluate false on every sample for the entire 20 s window,
                // isStill would require speedDropOk, and a rolling bike would burn
                // the entire impactWindowMs*2 budget retrying — losing the crash
                // on the fast path and falling through to the SpeedDropMonitor
                // backstop. Stamping the just-computed angle (already ≥ 60°, the
                // stricter relaxation threshold — not the 45° latch threshold) lets
                // the SILENCE_CHECK relaxation engage on the very first sample even
                // when gap regime is in force. A marginal lean (45° < angle < 60°)
                // cannot exploit this path because onSideRelaxed required ≥ 60°.
                //
                // Cross-event handoff: angleNow is captured BEFORE the state
                // transition. The first SILENCE_CHECK sample (handleSilenceCheck)
                // adds one more X/Y/Z entry to the accumulator before checking the
                // latch, which can shift the running average by ~1/26 of one sample
                // — enough to fall below the 60° gate in pathological cases. Using
                // the just-computed angleNow guarantees the relaxation gate sees
                // exactly the evidence the IMPACT-relax decision was based on.
                lastOrientationAngleDeg = angleNow
            } else {
                resetSilenceWindow()   // SILENCE_CHECK starts with fresh accumulator
            }
            return Decision.None
        }

        if (timeSinceImpact > thresholds.impactWindowMs) {
            // False alarm: never settled within the impact window. Reset BOTH the timing
            // fields AND the orientation accumulator — the accumulator carries X/Y/Z sums
            // gated against the pre-impact reference of THIS impact; leaving them filled
            // would cause the next impact's `currentOrientationAngleDeg()` to compute
            // `new_ref · stale_sums`, a geometrically incoherent average that can fire the
            // IMPACT on-side relaxation with as few as ~5 fresh samples instead of the
            // documented 25. CR1 fix.
            resetTimers()
            resetSilenceWindow()
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
     * The retry budget (`impactWindowMs * 2`) is measured from SILENCE_CHECK ENTRY
     * ([silenceCheckEnteredMs]), not from the impact, so a late entry (large
     * impact→stillness gap) is not penalised. A continuously-still rider always
     * reaches the confirm branch because the give-up branch only fires on non-still
     * samples. The budget bounds how long the machine keeps retrying to achieve
     * stillness — it does not guarantee tolerance of arbitrarily late breaks.
     *
     * Cadence gate: per doc Revision 4 C5, cadence > 20 RPM during SILENCE_CHECK is an
     * instant false-alarm exit (an unconscious rider cannot pedal).
     */
    private fun handleSilenceCheck(sample: SensorSample, now: Long): Decision {
        // Cadence gate (instant false-alarm exit): only when cadence sensor present + active.
        //
        // FN-fix (2026-05-25): suppress CAD_GATE when the live orientation evidence
        // shows the device is decisively non-upright (angle vs pre-impact reference
        // ≥ uprightAngleThresholdDegrees, i.e. 45° by default). A bike on its side
        // or upside down cannot be pedalled at any RPM, so any "fresh" cadence
        // reading in that orientation must be phantom (sensor echoing pre-crash
        // value via SDK last-known-value, or impact-induced spurious revolution).
        //
        // The angle is computed against the **carried-forward IMPACT-phase
        // accumulator** on the onSideRelaxed path (already 25+ on-side samples at
        // ≥60° by definition — see [handleImpact]'s gateOk branch and the CR3 angle
        // stamp at line 612). On the speedDropOk path the accumulator is reset and
        // [currentOrientationAngleDeg] returns -1.0 → suppression does NOT engage
        // and CAD_GATE behaves exactly as before, preserving the existing
        // "rider stopped at a light, pedalling resumed → bail" semantics for
        // upright stops. The suppression therefore only fires when there is
        // already strong evidence of a non-upright crash.
        if (isCadenceActive(now)) {
            val orientationAngle = currentOrientationAngleDeg()
            if (orientationAngle >= thresholds.uprightAngleThresholdDegrees) {
                // Decisive on-side → CAD_GATE is unsafe. Stay in SILENCE_CHECK
                // and let the accel/orientation gates decide. Capture the LIVE
                // angle here (not `lastOrientationAngleDeg`, which is the
                // latched value that the silence-window updates AFTER this
                // gate) so the facade's CAD_GATE_SUPPRESSED row shows the
                // angle that actually drove the suppression decision.
                lastCadenceGateSuppressed = true
                lastCadenceGateSuppressedAngleDeg = orientationAngle
            } else {
                resetTimers()
                resetSilenceWindow()
                state = State.MONITORING
                return Decision.ReturnToMonitoring
            }
        }

        val gpsStale = lastSpeedGpsStale
        val deviationMax = if (gpsStale) thresholds.gpsStaleSilenceDeviationMax
                           else thresholds.silenceDeviationMax

        // Use rawMagnitude — production CrashDetectionManager.processAccelerometer() uses
        // `abs(magnitude - GRAVITY)` (raw, not smoothed). Behavioural-equivalence requirement.
        val deviation = abs(sample.rawMagnitude - GRAVITY)
        val accelOk = deviation <= deviationMax

        // Accumulate X/Y/Z for orientation classification (Revision 5), gated on
        // accelOk — mirroring the IMPACT-phase accumulation in [handleImpact].
        //
        // FP-fix (2026-05-25): a previous comment block claimed unconditional
        // accumulation was safe because "any !isStill sample triggers
        // resetSilenceWindow", but that is wrong for the CR2 on-side preservation
        // path (the within-budget bump branch below explicitly **does not** reset
        // the accumulator when the latch was on-side). On a rough-terrain descent
        // the FP-2 incident from 2026-05-25 (elapsed 14141.2 s) crossed the
        // SMOOTH threshold by 2 m/s² above the floor, transitioned via
        // onSideRelaxed (the IMPACT-phase accumulator was already polluted by
        // sustained off-axis tilt during a 2.2 s impact→silence gap), then
        // accumulated `deviation = 26.13`, `16.92`, `5.63` samples whose
        // direction-of-impact reading skewed the running gravity-vector average
        // to a 75.9° "on side" classification → 4.5 s LEGACY window confirmed at
        // 36.6 km/h, cancelled by rider in 7.2 s.
        //
        // Gating accumulation by accelOk ensures only samples whose magnitude is
        // close to gravity contribute to the direction average. Bumps whose
        // magnitude is dominated by impact force (rather than gravity) no longer
        // pollute the orientation latch. The accelOk threshold (4 m/s² default,
        // 8 m/s² GPS-stale) is the same one used for `isStill` below, so the
        // accumulation and decision criteria stay in lockstep.
        if (accelOk) {
            orientationSumX += sample.accelX
            orientationSumY += sample.accelY
            orientationSumZ += sample.accelZ
            orientationSampleCount++
        }

        val speedDropOk = isSpeedDropConfirmed()
        // Compute the silence-window duration first — this may also LATCH the
        // orientation regime (set lockedEffectiveSilenceMs and lastOrientationAngleDeg)
        // on the sample that crosses MIN_ORIENTATION_SAMPLES. Reading onSideRelaxed
        // AFTER this ensures the relaxation can engage on the very sample that
        // establishes the latch, not just on subsequent samples.
        val effectiveSilenceMs = computeEffectiveSilenceMs(gpsStale)
        // Once the orientation regime has LOCKED decisively on-side (angle ≥
        // onSideRelaxationAngleDeg, a stricter threshold than the regular 45°
        // on-side gate), a bike on the ground cannot be ridden — if it is moving,
        // the bike has escaped the downed rider. Accel stillness alone is sufficient
        // evidence; the speed rise is the bike rolling, not the rider riding. The
        // accel gate remains the strong FP guard.
        val onSideRelaxed = lockedEffectiveSilenceMs > 0L &&
                            lastOrientationAngleDeg >= thresholds.onSideRelaxationAngleDeg
        // Note: when SILENCE_CHECK is entered via the regular speedDropOk path,
        // computeEffectiveSilenceMs's gap-regime branch never touches
        // lastOrientationAngleDeg — it stays at the -1.0 sentinel and this gate
        // evaluates false, which is the intended "gap regime → 20 s upright
        // window, no relaxation" behaviour for a rider who took 9 s to coast
        // to a stop.
        //
        // CR3 (May 2026) carve-out: when SILENCE_CHECK is entered via the
        // IMPACT-relax path (bike still rolling, ≥25 samples on-side at ≥60°),
        // [handleImpact] stamps lastOrientationAngleDeg with the angle that
        // fired the relaxation — so this gate engages on the very first
        // SILENCE_CHECK sample EVEN when the gap regime latches the 20 s
        // window. Intended: that path already proved the bike is decisively
        // on the ground, and we need the relaxation to keep isStill = accelOk
        // (not && speedDropOk) so the rolling bike does not burn the entire
        // retry budget. The ≥ 60° threshold here is the same gate that fired
        // IMPACT-relax — a marginal lean cannot exploit the composed path.
        val isStill = if (onSideRelaxed) accelOk else (accelOk && speedDropOk)

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
                // CR2 (May 2026) — asymmetric within-budget reset. Companion to the
                // IMPACT→SILENCE_CHECK asymmetric reset at the `gateOk` branch in
                // [handleImpact] (search this file for `onSideRelaxed` in
                // handleImpact): a decisive on-side latch is durable evidence (a
                // bike on its side cannot be ridden), so a small within-budget bump
                // must NOT undo it. The 25-sample on-side accumulator that fired
                // the latch is still valid; wiping it would force a rebuild from
                // scratch while the bike is still rolling (`speedDropOk` false),
                // the accumulator would never refill to the lock threshold, the
                // latch would never re-engage, and the SM would fall through to
                // SILENCE_TIMEOUT despite a genuine crash. Mirrors the IMPACT-side
                // rationale: see the comment block at the `gateOk` branch in
                // [handleImpact].
                //
                // Narrow scope: ONLY the on-side path (angle ≥
                // [Thresholds.onSideRelaxationAngleDeg], a stricter threshold than
                // the 45° upright gate) bypasses the reset. The upright (45° to
                // 60° + invalid-ref legacy short) and gap regimes still get the
                // full [resetSilenceWindow]: their evidence is weaker (a rider
                // tilted forward at a light, a rider who took 9 s to stop) and
                // an orientation-pollution flush remains the right behaviour for
                // those regimes.
                //
                // The silence-elapsed anchor [silenceStartedMs] is ALSO preserved
                // on the on-side path so that `(now - silenceStartedMs)` continues
                // to grow across the bump and Confirm fires at the originally
                // latched 4.5 s mark — not at bump + 4.5 s. Otherwise a real
                // crash + small bump scenario would still confirm, just delayed,
                // wasting the latch's evidence value.
                val previousLatchWasOnSide = lockedEffectiveSilenceMs > 0L &&
                    lastOrientationAngleDeg >= thresholds.onSideRelaxationAngleDeg
                if (!previousLatchWasOnSide) {
                    // Upright / gap / no-latch path: existing behaviour — restart
                    // the silence clock AND wipe the orientation accumulator + latch.
                    silenceStartedMs = now
                    resetSilenceWindow()
                }
                // On-side path: leave silenceStartedMs, the accumulator and the
                // latch in place. The next still sample resumes the cumulative
                // silence window where the bump interrupted it.
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

        val angleDeg = currentOrientationAngleDeg()
        if (angleDeg < 0.0) return legacyShort  // not enough samples / invalid ref / degenerate
        lastOrientationAngleDeg = angleDeg

        val chosen = if (angleDeg >= thresholds.uprightAngleThresholdDegrees) legacyShort
                     else thresholds.silenceDurationUprightMs
        lockedEffectiveSilenceMs = chosen
        return chosen
    }

    /**
     * Live orientation angle (degrees) between the accumulated gravity-vector
     * average and the pre-impact reference. Returns the `-1.0` sentinel when:
     *  - fewer than [MIN_ORIENTATION_SAMPLES] have been accumulated,
     *  - the pre-impact reference is invalid,
     *  - either vector magnitude is degenerate (< EPSILON).
     *
     * Consumed by [computeEffectiveSilenceMs] (latch decision in SILENCE_CHECK)
     * and by [handleImpact] (on-side relaxation gate — Task 4).
     */
    private fun currentOrientationAngleDeg(): Double {
        if (orientationSampleCount < MIN_ORIENTATION_SAMPLES) return -1.0
        if (!preImpactRef.valid) return -1.0
        val n = orientationSampleCount.toDouble()
        val curX = orientationSumX / n
        val curY = orientationSumY / n
        val curZ = orientationSumZ / n
        val curMag = sqrt(curX * curX + curY * curY + curZ * curZ)
        val refMag = sqrt(
            preImpactRef.x * preImpactRef.x +
            preImpactRef.y * preImpactRef.y +
            preImpactRef.z * preImpactRef.z
        )
        if (curMag < EPSILON || refMag < EPSILON) return -1.0
        val cosAngle = ((curX * preImpactRef.x + curY * preImpactRef.y + curZ * preImpactRef.z)
                       / (curMag * refMag)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cosAngle))
    }

    private fun resetSilenceWindow() {
        orientationSumX = 0.0
        orientationSumY = 0.0
        orientationSumZ = 0.0
        orientationSampleCount = 0
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
     * Returns false in five cases:
     *   1. [lastCadenceUpdateMs] == 0L: no cadence data has been received this session
     *      (no sensor paired, or cadence callback fired before the first sample).
     *   2. Age of last emission exceeds `cadenceStaleThresholdMs`: sensor disconnected.
     *   3. [cadenceLastChangeMs] == [CADENCE_CHANGE_NEVER]: the cadence value has never
     *      fluctuated since the session started — the sensor is reporting a static value
     *      (stuck from the first reading; not yet verified to be live).
     *   4. Time since the cadence value last changed exceeds `cadenceStaleThresholdMs`:
     *      sensor stopped changing mid-ride (repeating its last value after signal loss).
     *   5. [lastCadenceRpm] not above `cadenceQuietThresholdRpm`: rider coasting / not
     *      pedalling.
     */
    private fun isCadenceActive(nowSampleMs: Long): Boolean {
        if (lastCadenceUpdateMs == 0L) return false
        val age = nowSampleMs - lastCadenceUpdateMs
        if (age > thresholds.cadenceStaleThresholdMs) return false
        // Freshness-by-CHANGE: CADENCE_CHANGE_NEVER means the cadence value has NEVER
        // fluctuated since the session started — the sensor is reporting a static value
        // (stuck reading after signal loss, or sensor just connected and not yet verified
        // to be live). An actively pedalling rider's cadence changes every revolution;
        // a stuck sensor's does not.
        if (cadenceLastChangeMs == CADENCE_CHANGE_NEVER) return false
        val sinceChange = nowSampleMs - cadenceLastChangeMs
        if (sinceChange > thresholds.cadenceStaleThresholdMs) return false
        return lastCadenceRpm > thresholds.cadenceQuietThresholdRpm
    }

    private fun resetTimers() {
        impactStartedMs = 0L
        silenceStartedMs = 0L
        firstSilenceGapMs = 0L
        silenceCheckEnteredMs = 0L
    }
}
