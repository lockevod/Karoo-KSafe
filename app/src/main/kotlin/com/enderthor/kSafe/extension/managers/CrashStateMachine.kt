package com.enderthor.kSafe.extension.managers

/**
 * Pure state machine: MONITORING → IMPACT → SILENCE_CHECK → (CRASH_CONFIRMED | back to MONITORING).
 *
 * No Android dependencies. Receives discrete events and returns [Decision] values. The owner
 * of this state machine (today: [CrashDetectionManager]) decides what to do with each
 * [Decision.Confirm] — typically routing to `confirmCrash(IMPACT_CONFIRMED)`.
 *
 * ### Time domains
 * All timing inside this class uses [SensorSample.timestampMs] — the ride-relative timestamp
 * carried on each sensor sample. This keeps the state machine deterministic and easily testable
 * without wall-clock dependencies.
 *
 * [clock] is used **only** when the caller needs a wall-clock timestamp for an event that has
 * no associated sample (e.g. [onCadenceUpdate] which arrives from the Karoo SDK stream, not from
 * the accelerometer pipeline). To stay in the same time domain as [SensorSample.timestampMs],
 * [onCadenceUpdate] records the timestamp of the **last processed sample** ([lastSampleMs]) as a
 * proxy, rather than calling [clock.nowMs()]. This means "cadence last seen at the same moment
 * as the most recent accel frame", which is accurate enough for the ~10 s staleness window.
 */
class CrashStateMachine(
    private val thresholds: Thresholds,
    @Suppress("UNUSED_PARAMETER") clock: Clock = SystemClock,
    private val calibLogger: CalibrationLogger? = null,
) {
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
    @Volatile private var lastSpeedGpsStale: Boolean = false

    // ── Cadence ──────────────────────────────────────────────────────────────
    @Volatile private var lastCadenceRpm: Double = 0.0
    /**
     * Sample-domain timestamp at which cadence was last received (0 = never received).
     * Set to [lastSampleMs] in [onCadenceUpdate] so it lives in the same time domain as
     * [SensorSample.timestampMs], making staleness checks (nowMs − lastCadenceUpdateMs)
     * meaningful.
     */
    @Volatile private var lastCadenceUpdateMs: Long = 0L
    /** Timestamp of the last sample processed by [onSample]; used by [onCadenceUpdate]. */
    @Volatile private var lastSampleMs: Long = 0L

    // ── State-machine timing ─────────────────────────────────────────────────
    @Volatile private var impactStartedMs: Long = 0L
    /**
     * Sample timestamp from which continuous silence has been measured.
     * Initialised to [impactStartedMs] on MONITORING→IMPACT so that silence is counted from
     * the moment of impact, not from the first quiet frame.
     *
     * A value of [SILENCE_UNSET] (-1) means the silence clock has been reset by a not-quiet
     * sample and will restart from the next quiet frame. Using -1 (not 0) as the "unset"
     * sentinel avoids a collision with legitimate impact timestamps of 0ms.
     */
    @Volatile private var silenceStartedMs: Long = SILENCE_UNSET

    companion object {
        /** Sentinel: silence clock not yet running (reset after a not-quiet sample). */
        private const val SILENCE_UNSET = -1L
    }

    fun onSample(sample: SensorSample): Decision {
        val now = sample.timestampMs
        lastSampleMs = now

        return when (state) {

            // ── MONITORING ───────────────────────────────────────────────────
            State.MONITORING -> {
                val isImpact =
                    sample.peakMagnitude >= thresholds.peakImpactThreshold ||
                    sample.smoothedMagnitude >= thresholds.smoothedImpactThreshold ||
                    sample.gyroMag >= thresholds.gyroImpactThreshold
                if (!isImpact) return Decision.None
                if (!speedGatePasses()) return Decision.None

                state = State.IMPACT
                impactStartedMs = now
                // Start the silence clock from the moment of impact.  If the device stays quiet
                // continuously from here, elapsed silence = now - impactStartedMs.  If a
                // not-quiet sample is seen, the IMPACT block resets silenceStartedMs to
                // SILENCE_UNSET and it restarts from the next quiet frame.
                silenceStartedMs = now
                calibLogger?.log(CalibrationLogger.Event.IMPACT_ENTER) {
                    "peak=%.2f,smoothed=%.2f,gyro=%.2f,speed=%.1f,gps_stale=$lastSpeedGpsStale"
                        .format(sample.peakMagnitude, sample.smoothedMagnitude, sample.gyroMag, lastSpeedKmh)
                }
                Decision.EnterImpact("peak=${sample.peakMagnitude}")
            }

            // ── IMPACT ───────────────────────────────────────────────────────
            State.IMPACT -> {
                val elapsedSinceImpact = now - impactStartedMs
                if (elapsedSinceImpact > thresholds.impactWindowMs) {
                    state = State.MONITORING
                    impactStartedMs = 0L
                    silenceStartedMs = SILENCE_UNSET
                    return Decision.ReturnToMonitoring
                }

                // Rider clearly fine → bail back to MONITORING.
                if (isCadenceActive(now)) {
                    state = State.MONITORING
                    impactStartedMs = 0L
                    silenceStartedMs = SILENCE_UNSET
                    return Decision.ReturnToMonitoring
                }

                val isQuiet = sample.smoothedMagnitude < (9.81 + thresholds.silenceDeviationMax) &&
                    sample.smoothedMagnitude > (9.81 - thresholds.silenceDeviationMax)

                if (!isQuiet) {
                    // Device still moving — mark silence clock as unset so it restarts fresh
                    // on the next quiet frame.
                    silenceStartedMs = SILENCE_UNSET
                    return Decision.None
                }

                // First quiet frame after a not-quiet period: restart silence clock.
                if (silenceStartedMs == SILENCE_UNSET) silenceStartedMs = now

                if (now - silenceStartedMs >= thresholds.silenceRequiredMs) {
                    // Sufficient sustained silence — advance to SILENCE_CHECK and evaluate
                    // conditions immediately with the current sample (no extra round-trip needed).
                    state = State.SILENCE_CHECK
                    return evaluateSilenceCheck(sample, now)
                }
                Decision.None
            }

            // ── SILENCE_CHECK ────────────────────────────────────────────────
            State.SILENCE_CHECK -> evaluateSilenceCheck(sample, now)
        }
    }

    /**
     * Evaluate SILENCE_CHECK conditions on [sample].  Called both when first entering the state
     * and on every subsequent sample while in SILENCE_CHECK.
     */
    private fun evaluateSilenceCheck(sample: SensorSample, now: Long): Decision {
        val accelOk = sample.smoothedMagnitude < (9.81 + thresholds.silenceDeviationMax) &&
            sample.smoothedMagnitude > (9.81 - thresholds.silenceDeviationMax)

        if (!accelOk) {
            // Device moved again — drop back to IMPACT to wait for renewed stillness.
            state = State.IMPACT
            silenceStartedMs = SILENCE_UNSET
            return Decision.None
        }

        val speedDropOk = lastSpeedKmh <= thresholds.crashConfirmSpeedKmh.toDouble() ||
            (thresholds.crashConfirmSpeedKmh == 0 && lastSpeedGpsStale)
        val cadenceOk = !isCadenceActive(now)

        if (speedDropOk && cadenceOk) {
            state = State.MONITORING
            impactStartedMs = 0L
            silenceStartedMs = SILENCE_UNSET
            return Decision.Confirm
        }

        // Wait for speed / cadence to settle, but give up if the impact window has expired.
        val elapsedSinceImpact = now - impactStartedMs
        if (elapsedSinceImpact > thresholds.impactWindowMs) {
            state = State.MONITORING
            impactStartedMs = 0L
            silenceStartedMs = SILENCE_UNSET
            return Decision.ReturnToMonitoring
        }
        return Decision.None
    }

    fun onSpeedUpdate(speedKmh: Double, gpsStale: Boolean) {
        lastSpeedKmh = speedKmh
        lastSpeedGpsStale = gpsStale
    }

    fun onCadenceUpdate(cadenceRpm: Double) {
        lastCadenceRpm = cadenceRpm
        // Use lastSampleMs (the sample-domain clock) rather than a wall-clock value so that
        // staleness checks in isCadenceActive stay in the same time domain as the sensor samples.
        // If no sample has been processed yet lastSampleMs is 0, which serves as the
        // "never received in-ride" sentinel and causes isCadenceActive to return false.
        lastCadenceUpdateMs = lastSampleMs
    }

    fun onPause() {
        state = State.MONITORING
        impactStartedMs = 0L
        silenceStartedMs = SILENCE_UNSET
    }

    fun reset() {
        state = State.MONITORING
        impactStartedMs = 0L
        silenceStartedMs = SILENCE_UNSET
        lastSpeedKmh = 0.0
        lastSpeedGpsStale = false
        lastCadenceRpm = 0.0
        lastCadenceUpdateMs = 0L
        lastSampleMs = 0L
    }

    /**
     * Returns true when the current speed allows an impact to be counted.
     *
     * Bypasses the speed gate entirely when GPS is stale — the SDK's last-known value is
     * frozen and unreliable, so we let the accelerometer be the sole discriminator.  The
     * confirmation phase will then apply stricter accel thresholds to compensate.
     */
    private fun speedGatePasses(): Boolean {
        if (lastSpeedGpsStale) return true
        return lastSpeedKmh >= thresholds.minSpeedForCrashKmh.toDouble() ||
            thresholds.minSpeedForCrashKmh == 0
    }

    /**
     * Returns true when cadence data is fresh and above the quiet threshold, indicating
     * the rider is actively pedalling (strong false-alarm signal).
     *
     * Returns false in three cases:
     * - [lastCadenceUpdateMs] == 0: no cadence data has been received this ride
     *   (sensor not paired or update arrived before the first sensor sample).
     * - Age of last cadence update exceeds [Thresholds.cadenceStaleThresholdMs]: sensor
     *   disconnected mid-ride.
     * - [lastCadenceRpm] ≤ [Thresholds.cadenceQuietThresholdRpm]: rider is coasting.
     */
    private fun isCadenceActive(nowMs: Long): Boolean {
        if (lastCadenceUpdateMs == 0L) return false
        val age = nowMs - lastCadenceUpdateMs
        if (age > thresholds.cadenceStaleThresholdMs) return false
        return lastCadenceRpm > thresholds.cadenceQuietThresholdRpm
    }
}
