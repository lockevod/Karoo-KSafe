package com.enderthor.kSafe.extension.crash

/** One timestamped raw accelerometer vector (m/s², gravity included). */
data class TimedVec3(val x: Double, val y: Double, val z: Double, val tsMs: Long)

/**
 * The average gravity-vector direction of the bike in the window just before an
 * impact — a fresh, terrain-independent "upright" reference. [valid] is false when
 * not enough samples were available (cold start, or < ~2 s after a resume).
 */
data class PreImpactRef(
    val x: Double,
    val y: Double,
    val z: Double,
    val valid: Boolean,
) {
    companion object {
        val INVALID = PreImpactRef(0.0, 0.0, 0.0, valid = false)
    }
}

/**
 * Pure computation of the pre-impact reference. Kept free of Android types so it is
 * unit-testable on the JVM without constructing a `SensorEvent`.
 */
object PreImpactReference {
    /** Guard before the impact that excludes the impact transient (~60 ms rise). */
    const val GUARD_MS = 250L
    /** Averaging window length. */
    const val WINDOW_MS = 2_000L
    /** Minimum samples in the slice for the reference to be trusted (~1 s at 50 Hz). */
    const val MIN_SAMPLES = 50

    /**
     * Average the X/Y/Z of every sample whose timestamp falls in
     * `[impactTsMs - GUARD_MS - WINDOW_MS , impactTsMs - GUARD_MS]`.
     * Returns [PreImpactRef.INVALID] when fewer than [MIN_SAMPLES] qualify.
     */
    fun compute(
        buffer: List<TimedVec3>,
        impactTsMs: Long,
        windowMs: Long = WINDOW_MS,
        guardMs: Long = GUARD_MS,
        minSamples: Int = MIN_SAMPLES,
    ): PreImpactRef {
        val hi = impactTsMs - guardMs
        val lo = hi - windowMs
        var sx = 0.0; var sy = 0.0; var sz = 0.0; var n = 0
        for (s in buffer) {
            if (s.tsMs in lo..hi) {
                sx += s.x; sy += s.y; sz += s.z; n++
            }
        }
        if (n < minSamples) return PreImpactRef.INVALID
        return PreImpactRef(sx / n, sy / n, sz / n, valid = true)
    }
}
