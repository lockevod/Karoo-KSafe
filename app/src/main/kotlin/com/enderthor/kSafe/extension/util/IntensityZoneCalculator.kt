package com.enderthor.kSafe.extension.util

import io.hammerhead.karooext.models.UserProfile

/**
 * Pure helper. Given the rider's Karoo profile (5 HR zones, 7 power zones — both lists
 * are configured by the rider in the Karoo's settings), the current HR (bpm) and the
 * current power (W), returns a [ZoneSnapshot] identifying the rider's current intensity
 * zone (source / index / total).
 *
 * Source preference: POWER (more accurate intensity proxy) > HR > NONE.
 *
 * **v18 note**: the [ZoneSnapshot.multiplier] field is **vestigial** and no longer
 * read by the integrator. In v17 and earlier it scaled the rider-configured
 * `carbTargetGperHour` to track intensity changes; v18 replaced that with the
 * physiological [com.enderthor.kSafe.extension.util.CarbBurnEstimator] which derives
 * burn directly from power or HR. The classifier itself (zone source / index / total)
 * is still used by the estimator to pick a CHO fraction from the
 * Romijn / Jeukendrup table. The multiplier is kept on the data class so existing
 * calibration logs that reference the field continue to compile; it can be removed
 * in a future cleanup.
 *
 * Stateless and side-effect-free — safe to call on every tick.
 */

/** Gut absorption ceiling (g/h). The v18 [CarbsTracker.tick] integrator clamps the
 *  physiological burn estimate to this value so the cumulative-burn curve never
 *  advances faster than a typical recreational rider can actually consume. 90 g/h
 *  is the established ceiling for a glucose+fructose mix with an un-trained gut
 *  (Jeukendrup 2014, ISSN 2017). Race-trained riders push 120-150 g/h but only after
 *  months of gut adaptation. Top-level so [CarbsTracker] can reference it directly. */
const val ABSORPTION_CAP_GPH = 90f

object IntensityZoneCalculator {

    // Multiplier range — vestigial in v18 (kept for backwards compat on the
    // ZoneSnapshot data class). v17 used the multiplier to scale the
    // rider-configured `carbTargetGperHour` between recovery and top zone:
    //
    //   Z1 (~50% VO2max) ≈ 20-25 g/h carb burn → ratio 0.4 of Z3
    //   Z3 (~70%)        ≈ 50-60 g/h           → 1.00 (reference)
    //   Z5 (~90%)        ≈ 80-90 g/h           → 1.50
    //
    // v18 derives burn from physiology instead; CHO fraction by zone replaces the
    // multiplier on the carb-tracker side. The numeric range here is preserved so
    // any external tool that reads the calibration CSV's historical `multiplier=`
    // column (now removed from v18 fire / periodic rows) can still cross-reference
    // older data without redefinition.
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
        // `minByOrNull` (not `minBy`) — both callers gate on `zones.isNotEmpty()`,
        // so the `?: 0` fallback is unreachable in practice, but using
        // `minByOrNull` keeps the call total instead of relying on the
        // non-empty precondition being respected by every future caller.
        zones.indices.minByOrNull { i ->
            val z = zones[i]
            when {
                value < z.min -> z.min - value
                value > z.max -> value - z.max
                else -> 0
            }
        } ?: 0

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
 *  - [multiplier]: **vestigial in v18** — 1.0..1.5 (from MIN_MULT..MAX_MULT) within
 *    configured zones, 1.0 when NONE. No longer read by `CarbsTracker` (the
 *    physiological burn estimator replaced the multiplier-scaling model in v18);
 *    kept on the data class only so any external tool still reading the field's
 *    historical CSV column shape parses correctly. Future cleanup: remove the
 *    field and the `MIN_MULT`/`MAX_MULT` constants once we are confident no
 *    downstream calibration analysis depends on the column.
 */
data class ZoneSnapshot(
    val source: ZoneSource,
    val index: Int,
    val total: Int,
    val multiplier: Float,
)

enum class ZoneSource { HR, POWER, NONE }
