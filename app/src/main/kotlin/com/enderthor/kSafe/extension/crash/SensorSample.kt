package com.enderthor.kSafe.extension.crash

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
     * Raw accelerometer X axis at sample time, m/s² (includes gravity component).
     * Used by [CrashStateMachine] to build the pre-impact orientation reference
     * (averaged over ~2 s before the impact) and to classify the bike's orientation
     * during SILENCE_CHECK. Default 0.0 keeps the legacy magnitude-only
     * state-machine tests behaviourally equivalent — when X/Y/Z are absent or all
     * zero the orientation gate degrades gracefully: the pre-impact reference is
     * marked invalid and the shorter legacy silence window is used instead.
     *
     * Note: the GPS-staleness flag used to live on this sample; as of P1 (May 2026)
     * staleness is pushed to [CrashStateMachine.setSpeedGpsStale] on the facade side
     * to keep this hot-path allocation-free (no per-tick `.copy()` during a stale stretch).
     */
    val accelX: Double = 0.0,
    /** Raw accelerometer Y axis at sample time, m/s² (includes gravity component). */
    val accelY: Double = 0.0,
    /** Raw accelerometer Z axis at sample time, m/s² (includes gravity component). */
    val accelZ: Double = 0.0,
)
