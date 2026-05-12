package com.enderthor.kSafe.extension.util

import io.hammerhead.karooext.models.UserProfile

/**
 * Pure helper. Given the rider's Karoo profile (5 HR zones, 7 power zones — both lists are
 * configured by the rider in the Karoo's settings), the current HR (bpm) and the current
 * power (W), returns a [ZoneSnapshot] that the [CarbsTracker] uses to modulate the carb
 * target rate.
 *
 * Source preference: POWER (more accurate intensity proxy) > HR > NONE. The multiplier scales
 * linearly across the rider's configured zones from [MIN_MULT] (recovery) to [MAX_MULT] (top zone).
 *
 * Stateless and side-effect-free — safe to call on every tick.
 */
object IntensityZoneCalculator {

    private const val MIN_MULT = 0.7f
    private const val MAX_MULT = 1.3f

    fun calculate(profile: UserProfile?, currentHr: Int?, currentPowerW: Int?): ZoneSnapshot {
        if (profile != null && currentPowerW != null && profile.powerZones.isNotEmpty()) {
            val zones = profile.powerZones
            val idx = zones.indexOfFirst { currentPowerW in it.min..it.max }
            if (idx >= 0) return snapshot(ZoneSource.POWER, idx, zones.size)
            // Out-of-range — clamp to nearest zone edge so a rider coasting below zone 1
            // gets MIN_MULT (recovery) rather than the same neutral 1.0 we give to "no sensor".
            // Source stays POWER so calibration analysis can distinguish "below range" from NONE.
            val clamped = if (currentPowerW < zones[0].min) 0 else zones.size - 1
            return snapshot(ZoneSource.POWER, clamped, zones.size)
        }
        if (profile != null && currentHr != null && profile.heartRateZones.isNotEmpty()) {
            val zones = profile.heartRateZones
            val idx = zones.indexOfFirst { currentHr in it.min..it.max }
            if (idx >= 0) return snapshot(ZoneSource.HR, idx, zones.size)
            val clamped = if (currentHr < zones[0].min) 0 else zones.size - 1
            return snapshot(ZoneSource.HR, clamped, zones.size)
        }
        return ZoneSnapshot(ZoneSource.NONE, -1, 0, 1.0f)
    }

    private fun snapshot(source: ZoneSource, idx: Int, total: Int): ZoneSnapshot {
        val ratio = idx.toFloat() / (total - 1).coerceAtLeast(1)
        val multiplier = MIN_MULT + ratio * (MAX_MULT - MIN_MULT)
        return ZoneSnapshot(source, idx, total, multiplier)
    }
}

/**
 * The result of [IntensityZoneCalculator.calculate].
 *  - [source]: which sensor stream the snapshot was derived from, or NONE when no zones could be matched.
 *  - [index]: 0-based zone index; -1 when source = NONE.
 *  - [total]: number of configured zones for the source (typically 5 for HR, 7 for power); 0 when NONE.
 *  - [multiplier]: 0.7..1.3 within configured zones, 1.0 when NONE (neutral fallback).
 */
data class ZoneSnapshot(
    val source: ZoneSource,
    val index: Int,
    val total: Int,
    val multiplier: Float,
)

enum class ZoneSource { HR, POWER, NONE }
