package com.enderthor.kSafe.extension.managers

/**
 * Pure state machine: MONITORING → IMPACT → SILENCE_CHECK → (CRASH_CONFIRMED | back to MONITORING).
 *
 * No Android dependencies. Receives discrete events and returns [Decision] values. The owner
 * of this state machine (today: [CrashDetectionManager]) decides what to do with each
 * [Decision.Confirm] — typically routing to `confirmCrash(IMPACT_CONFIRMED)`.
 */
class CrashStateMachine(
    private val thresholds: Thresholds,
    private val clock: Clock = SystemClock,
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

    fun onSample(sample: SensorSample): Decision = TODO()
    fun onSpeedUpdate(speedKmh: Double, gpsStale: Boolean): Unit = TODO()
    fun onCadenceUpdate(cadenceRpm: Double): Unit = TODO()
    fun onPause(): Unit = TODO()
    fun reset(): Unit = TODO()
}
