package com.enderthor.kSafe.extension.managers

/**
 * All numeric tunables that the state machine reads. Lifted out of [CrashDetectionManager]
 * so unit tests can construct a known set without touching DataStore.
 *
 * Defaults match the MEDIUM sensitivity preset documented in
 * `docs/crash-detection-algorithm.md`. Production code in
 * [CrashDetectionManager.updateConfig] builds an instance from the active [KSafeConfig].
 */
data class Thresholds(
    val smoothedImpactThreshold: Double = 25.0,
    val peakImpactThreshold: Double = 40.0,
    val gyroImpactThreshold: Double = 8.0,
    val silenceDeviationMax: Double = 1.0,
    val silenceRequiredMs: Long = 1_500L,
    val impactWindowMs: Long = 6_000L,
    val minSpeedForCrashKmh: Int = 10,
    val crashConfirmSpeedKmh: Int = 5,
    val cadenceQuietThresholdRpm: Double = 5.0,
    val cadenceStaleThresholdMs: Long = 10_000L,
)
