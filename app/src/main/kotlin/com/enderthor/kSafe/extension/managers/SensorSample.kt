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
    /**
     * Whether the GPS speed reading is considered stale at the moment of this sample.
     * Filled in by the facade ([CrashDetectionManager]) on every sample so the state
     * machine sees an up-to-date staleness view between speed pings — without that
     * being conflated with a "real speed update arrived" signal (which would defeat
     * the cold-start guard).
     */
    val gpsStale: Boolean = false,
)
