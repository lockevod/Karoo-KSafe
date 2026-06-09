package com.enderthor.kSafe.extension.util

import io.hammerhead.karooext.models.UserProfile

/**
 * Calorie-only last-resort energy estimate for the HR-calorie counter.
 *
 * [CarbBurnEstimator] returns `Confidence.NONE` (and 0 kcal/h) when neither power,
 * nor Keytel (HR+age+sex+weight), nor Swain (HR+maxHr+restingHr+weight) can fire.
 * This helper covers that gap for the CALORIE field only — it never feeds the carb
 * burn / deficit path, so carb behaviour and its tests are unaffected.
 *
 * Reuses the documented Swain `%HRR → METs` relationship (see [CarbBurnEstimator]
 * Tier 3) but DEFAULTS the inputs Swain requires:
 *  - maxHr  = profile.maxHr, else `220 − age`, else 190.
 *  - restHr = profile.restingHr, else 60.
 * So it needs only **HR + weight**. Returns 0.0 when either is missing — the only
 * case where the calorie field shows `---`.
 */
object HrCalorieFallback {

    private const val DEFAULT_RESTING_HR = 60
    private const val DEFAULT_MAX_HR = 190

    fun kcalPerHour(hrBpm: Int?, profile: UserProfile?, riderAge: Int): Double {
        if (hrBpm == null || hrBpm <= 0) return 0.0
        if (profile == null || !profile.weight.isFinite() || profile.weight <= 0f) return 0.0

        var maxHr = profile.maxHr.takeIf { it > 0 }
            ?: if (riderAge > 0) 220 - riderAge else DEFAULT_MAX_HR
        val restHr = profile.restingHr.takeIf { it > 0 } ?: DEFAULT_RESTING_HR
        if (maxHr <= restHr) maxHr = restHr + 1   // degenerate guard

        val hrr = ((hrBpm - restHr).toDouble() / (maxHr - restHr).toDouble()).coerceIn(0.0, 1.0)
        val mets = 6.0 * hrr + 1.0
        return mets * profile.weight.toDouble() * CarbBurnEstimator.KCAL_PER_MET_PER_KG
    }
}

/**
 * Which model produced the calorie figure. Maps [CarbBurnEstimator.Confidence]
 * (POWER/KEYTEL/SWAIN/NONE) plus [HRMAX] when the [HrCalorieFallback] supplied the
 * value. Kept separate from `CarbBurnEstimator.Confidence` so the carb path's
 * branching is provably untouched.
 */
enum class CalorieSource {
    POWER, KEYTEL, SWAIN, HRMAX, NONE;

    companion object {
        fun from(confidence: CarbBurnEstimator.Confidence, fallbackKcalPerHour: Double): CalorieSource =
            when (confidence) {
                CarbBurnEstimator.Confidence.POWER  -> POWER
                CarbBurnEstimator.Confidence.KEYTEL -> KEYTEL
                CarbBurnEstimator.Confidence.SWAIN  -> SWAIN
                CarbBurnEstimator.Confidence.NONE   -> if (fallbackKcalPerHour > 0.0) HRMAX else NONE
            }
    }
}
