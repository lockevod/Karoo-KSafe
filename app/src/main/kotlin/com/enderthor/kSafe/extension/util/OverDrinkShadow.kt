package com.enderthor.kSafe.extension.util

private const val MIN_RIDE_MS = 90 * 60_000L
private const val MIN_SWEAT_ML = 1000f
private const val LEVEL_STEP_ML = 500

/**
 * Excess level (floor(excess/500)) to LOG now, or 0. Never returns a level <= [lastLoggedLevel].
 * Shadow only: the caller logs the row; nothing here alerts.
 */
fun overDrinkShadowLevel(
    rideTimeMs: Long?, coveredMs: Long, cumSweatMl: Float, cumLoggedMl: Int,
    confidenceNow: SweatConfidence, integrating: Boolean, lastLoggedLevel: Int,
): Int {
    if (rideTimeMs == null || rideTimeMs < MIN_RIDE_MS || cumSweatMl < MIN_SWEAT_ML) return 0
    if (!integrating || confidenceNow == SweatConfidence.LOW || !coverageOk(coveredMs, rideTimeMs)) return 0
    val excess = cumLoggedMl - cumSweatMl
    if (excess < maxOf(LEVEL_STEP_ML.toFloat(), 0.35f * cumSweatMl)) return 0
    val level = (excess / LEVEL_STEP_ML).toInt()
    return if (level > lastLoggedLevel) level else 0
}
