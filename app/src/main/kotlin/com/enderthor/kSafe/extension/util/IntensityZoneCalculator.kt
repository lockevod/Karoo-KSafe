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

/** Gut absorption ceiling (g/h). The integrator clamps `base × multiplier` to this
 *  value so the cumulative target never advances faster than a typical recreational
 *  rider can actually consume. 90 g/h is the established ceiling for a glucose+fructose
 *  mix with an un-trained gut (Jeukendrup 2014, ISSN 2017). Race-trained riders push
 *  120-150 g/h but only after months of gut adaptation — they can manually raise the
 *  base target to compensate. Top-level so [CarbsTracker] can reference it directly. */
const val ABSORPTION_CAP_GPH = 90f

object IntensityZoneCalculator {

    // Multiplier range — tracks actual carb burn rate across the rider's intensity
    // zones. Real cycling carb burn (Brooks 2018, Romijn 1993, Coyle 1997):
    //
    //   Z1 (~50% VO2max) ≈ 20-25 g/h  (mostly fat oxidation)  → ratio ≈ 0.4 of Z3
    //   Z3 (~70%)        ≈ 50-60 g/h                          → 1.00 (reference)
    //   Z5 (~90%)        ≈ 80-90 g/h (~80% carb, gut-limited) → 1.50
    //
    // MIN_MULT 0.4 mirrors actual Z1 burn at a 50 g/h base target (= 20 g/h, real Z1).
    // MAX_MULT 1.5 mirrors actual Z5 burn at the same base (= 75 g/h, close to real Z5).
    // The gut-absorption ceiling (≈ 90 g/h single-transportable, see ISSN 2017) is
    // enforced as a hard absolute cap in `CarbsTracker.tick()` so a Race-preset base
    // (75 g/h) × top-zone multiplier (1.5) still integrates at 90 g/h, not 112 g/h.
    //
    // Earlier 0.7-1.3 band over-fueled recovery periods by ~70 %. This change moves
    // KSafe from "anti-bonk safety buffer" to "burn-rate tracker", which is what the
    // user research and recent ISSN/IOC consensus recommends for recreational riders.
    private const val MIN_MULT = 0.4f
    private const val MAX_MULT = 1.5f

    fun calculate(profile: UserProfile?, currentHr: Int?, currentPowerW: Int?): ZoneSnapshot {
        if (profile != null && currentPowerW != null && profile.powerZones.isNotEmpty()) {
            val zones = profile.powerZones
            val idx = zones.indexOfFirst { currentPowerW in it.min..it.max }
            if (idx >= 0) return snapshot(ZoneSource.POWER, idx, zones.size)
            // Out-of-range — clamp to NEAREST zone. Below the lowest zone → Z1 (recovery).
            // Above the topmost zone → top zone (neuromuscular). In the GAP between two
            // adjacent zones (common when zones come from rounded FTP percentages — e.g.
            // Z3.max=250W and Z4.min=300W leave a 50W hole), pick the closer side rather
            // than defaulting to the top zone. Without this fix, an in-gap reading of
            // 275W was treated as Z7 (multiplier 1.5×) and pushed CarbsTracker to the
            // 90 g/h absorption cap, over-targeting carbs by ~50%.
            val clampedIdx = clampToNearestZone(currentPowerW, zones)
            return snapshot(ZoneSource.POWER, clampedIdx, zones.size)
        }
        if (profile != null && currentHr != null && profile.heartRateZones.isNotEmpty()) {
            val zones = profile.heartRateZones
            val idx = zones.indexOfFirst { currentHr in it.min..it.max }
            if (idx >= 0) return snapshot(ZoneSource.HR, idx, zones.size)
            val clampedIdx = clampToNearestZone(currentHr, zones)
            return snapshot(ZoneSource.HR, clampedIdx, zones.size)
        }
        return ZoneSnapshot(ZoneSource.NONE, -1, 0, 1.0f)
    }

    /**
     * Picks the zone whose configured range is closest to [value] when [value] doesn't
     * sit inside any zone. Handles below-Z1, above-top-zone, AND in-gap-between-zones.
     * For a value sitting in the gap between two adjacent zones, picks the closer side
     * (distance to the nearer of zone.min / zone.max).
     */
    private fun clampToNearestZone(value: Int, zones: List<UserProfile.Zone>): Int =
        zones.indices.minBy { i ->
            val z = zones[i]
            when {
                value < z.min -> z.min - value
                value > z.max -> value - z.max
                else -> 0
            }
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
 *  - [multiplier]: [MIN_MULT]..[MAX_MULT] within configured zones, 1.0 when NONE (neutral fallback).
 */
data class ZoneSnapshot(
    val source: ZoneSource,
    val index: Int,
    val total: Int,
    val multiplier: Float,
)

enum class ZoneSource { HR, POWER, NONE }
