package com.enderthor.kSafe.extension.crash

import com.enderthor.kSafe.extension.managers.CalibrationLogger
import com.enderthor.kSafe.extension.util.Clock
import com.enderthor.kSafe.extension.util.SystemClock
import kotlin.math.abs

/**
 * Pure state machine: MONITORING → IMPACT → SILENCE_CHECK → (CRASH_CONFIRMED | back to MONITORING).
 *
 * Faithful re-expression of the algorithm specified in `docs/crash-detection-algorithm.md`
 * (May 2026, revision 4) and the production logic in [CrashDetectionManager].
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
    private val calibLogger: CalibrationLogger? = null,
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
    private companion object {
        /** Gravity reference, m/s². The whole stillness logic compares against this. */
        const val GRAVITY = 9.81

        /** Sentinel for "no speed update received yet" — distinguishes from a legit `0.0`. */
        const val SPEED_UPDATE_NEVER = 0L
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
        calibLogger?.log(CalibrationLogger.Event.IMPACT_ENTER) {
            "peak=%.2f,smoothed=%.2f,gyro=%.2f,speed=%.1f,gps_stale=$lastSpeedGpsStale,reason=$reason"
                .format(sample.peakMagnitude, sample.smoothedMagnitude, sample.gyroMag, lastSpeedKmh)
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
     *   - `!isStill` AND within `impactWindowMs * 2` → reset silence clock to `now`
     *   - `!isStill` AND beyond `impactWindowMs * 2` → false alarm, return to MONITORING
     *
     * Cadence gate: per doc Revision 4 C5, cadence > 20 RPM during SILENCE_CHECK is an
     * instant false-alarm exit (an unconscious rider cannot pedal).
     */
    private fun handleSilenceCheck(sample: SensorSample, now: Long): Decision {
        // Cadence gate (instant false-alarm exit): only when cadence sensor present + active.
        if (isCadenceActive(now)) {
            resetTimers()
            state = State.MONITORING
            return Decision.ReturnToMonitoring
        }

        val gpsStale = lastSpeedGpsStale
        val deviationMax = if (gpsStale) thresholds.gpsStaleSilenceDeviationMax
                           else thresholds.silenceDeviationMax
        val effectiveSilenceMs = if (gpsStale) thresholds.gpsStaleSilenceDurationMs
                                 else thresholds.silenceDurationMs

        // Use rawMagnitude — production CrashDetectionManager.processAccelerometer() uses
        // `abs(magnitude - GRAVITY)` (raw, not smoothed). Behavioural-equivalence requirement.
        val deviation = abs(sample.rawMagnitude - GRAVITY)
        val accelOk = deviation <= deviationMax
        val speedDropOk = isSpeedDropConfirmed()
        val isStill = accelOk && speedDropOk

        val timeSinceImpact = now - impactStartedMs

        return when {
            isStill && (now - silenceStartedMs) >= effectiveSilenceMs -> {
                // CONFIRMED.
                calibLogger?.log(CalibrationLogger.Event.CRASH_CONFIRMED) {
                    "total_ms=$timeSinceImpact,deviation=%.2f,speed=%.1f,gps_stale=$gpsStale"
                        .format(deviation, lastSpeedKmh)
                }
                resetTimers()
                state = State.MONITORING
                Decision.Confirm
            }
            isStill -> Decision.None  // keep counting
            !isStill && timeSinceImpact <= thresholds.impactWindowMs * 2 -> {
                // Stillness must be continuous: restart silence clock on every break.
                silenceStartedMs = now
                Decision.None
            }
            else -> {
                // Beyond doubled window — false alarm.
                resetTimers()
                state = State.MONITORING
                Decision.ReturnToMonitoring
            }
        }
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
    }
}
