package com.enderthor.kSafe.extension.pacing

data class FuelHintResult(val fire: Boolean, val withLogs: Boolean, val grams: Int, val reason: String)

/** Axis B. Never produces CONSERVE. Dropped if the carb tracker alerted < 15 min ago (no double "eat"). */
object FuelHint {
    fun decide(
        carbsEnabled: Boolean, cumLoggedG: Int, cumBurnedG: Int, deficitG: Int, deficitThresholdG: Int,
        movingMin: Double, inFuelWindow: Boolean, msSinceCarbAlert: Long?,
    ): FuelHintResult {
        if (!carbsEnabled) return FuelHintResult(false, false, 0, "carbs_off")
        if (!inFuelWindow) return FuelHintResult(false, cumLoggedG > 0, 0, "no_window")
        if (msSinceCarbAlert != null && msSinceCarbAlert < 15 * 60_000L) return FuelHintResult(false, cumLoggedG > 0, 0, "recent_carb_alert")
        return if (cumLoggedG > 0) {
            if (deficitG >= deficitThresholdG) FuelHintResult(true, true, deficitG, "deficit")
            else FuelHintResult(false, true, deficitG, "below_threshold")
        } else {
            if (cumBurnedG >= 60 && movingMin >= 60.0) FuelHintResult(true, false, cumBurnedG, "unlogged")
            else FuelHintResult(false, false, cumBurnedG, "unlogged_early")
        }
    }
}
