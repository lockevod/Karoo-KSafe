package com.enderthor.kSafe.extension.managers

/**
 * One processed sample emitted by [SensorReader] downstream to [CrashStateMachine].
 *
 * The reader does the smoothing, peak detection and variance buffering before emitting
 * here so the state machine stays pure — no buffers, no filters, just decisions.
 */
data class SensorSample(
    /** Raw accelerometer magnitude in m/s² (includes gravity). */
    val rawMagnitude: Double,
    /** Low-pass-filtered magnitude. */
    val smoothedMagnitude: Double,
    /** Maximum raw magnitude seen in the last `peakWindowMs`. */
    val peakMagnitude: Double,
    /** Magnitude of the gyroscope vector at the same instant, rad/s. */
    val gyroMag: Double,
    /** Wall-clock timestamp for the sample, in ms. */
    val timestampMs: Long,
)
