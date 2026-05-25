package com.enderthor.kSafe.extension.util

import com.enderthor.kSafe.data.RiderSex
import io.hammerhead.karooext.models.UserProfile

/**
 * Pure helper that estimates the rider's instantaneous CARB BURN RATE (g/h)
 * from whatever physiological inputs are available, plus a confidence label
 * the caller can surface to the rider.
 *
 * The model is the standard sports-nutrition pipeline:
 *
 *   1. Total energy expenditure (kcal/h) from the best available data:
 *      - Tier 1 — Power meter: `kcal/h = power_W × 3.6`. Derives from gross
 *        cycling efficiency ~22 % and 1 kJ ≈ 0.239 kcal; the two factors
 *        cancel and every major training app (Strava, TrainingPeaks,
 *        Intervals.icu, Wahoo) uses this simplified form. Error ~5-10 %.
 *      - Tier 2 — Keytel et al. 2005 (HR + age + sex + weight). The gold
 *        standard for HR-only energy-expenditure estimation in cycling;
 *        ~10-15 % error against indirect calorimetry between 50-80 % VO2max.
 *      - Tier 3 — Swain & Leutholtz 1997 (HRR → METs). Fallback when age/sex
 *        unknown but maxHr + restingHr + weight are available. ~20-30 % error
 *        in cycling (METs ACSM table is calibrated for general activity, not
 *        sport-specific).
 *      - Tier 4 — None of the above: return 0 with confidence = NONE; the
 *        caller stops integrating.
 *   2. Carbohydrate fraction (% of kcal coming from CHO) from the current
 *      intensity zone. Maps zone index ∈ [0, total-1] linearly to
 *      [CHO_MIN_FRACTION, CHO_MAX_FRACTION]:
 *      - Z1 (recovery, ~50 % VO2max): 0.30 — mostly fat oxidation
 *      - Z3 (tempo, ~70 % VO2max): ~0.62
 *      - Z5 (VO2max+, ≥ 95 %): 0.95 — almost pure CHO
 *      Anchors come from Romijn et al. 1993, Achten & Jeukendrup 2003,
 *      Jeukendrup 2014.
 *   3. Carb burn rate: `g/h = kcal/h × CHO_fraction / 4` (1 g CHO = 4 kcal).
 *
 * Stateless and side-effect-free — safe to call on every tick.
 *
 * References:
 *  - Keytel LR et al. (2005). "Prediction of energy expenditure from heart
 *    rate monitoring during submaximal exercise." J Sports Sci 23(3):289-297.
 *  - Swain DP, Leutholtz BC (1997). "Heart rate reserve is equivalent to
 *    %VO2 reserve, not to %VO2max." Med Sci Sports Exerc 29(3):410-414.
 *  - Romijn JA et al. (1993). "Regulation of endogenous fat and carbohydrate
 *    metabolism in relation to exercise intensity and duration." Am J Physiol
 *    265(3 Pt 1):E380-91.
 *  - Achten J, Jeukendrup AE (2003). "Maximal fat oxidation during exercise
 *    in trained men." Int J Sports Med 24(8):603-608.
 *  - Jeukendrup AE (2014). "A step towards personalized sports nutrition:
 *    carbohydrate intake during exercise." Sports Med 44 Suppl 1:S25-33.
 *  - Coyle EF et al. (1992). "Cycling efficiency is related to the percentage
 *    of type I muscle fibers." Med Sci Sports Exerc 24(7):782-788.
 */
object CarbBurnEstimator {

    /** Lower anchor for the CHO fraction at the lowest configured zone (~recovery). */
    const val CHO_MIN_FRACTION = 0.30
    /** Upper anchor for the CHO fraction at the highest configured zone (~VO2max). */
    const val CHO_MAX_FRACTION = 0.95
    /** 1 g of carbohydrate yields ~4 kcal of metabolic energy (Atwater factor). */
    const val KCAL_PER_GRAM_CHO = 4.0
    /** kcal/h per watt of mechanical cycling power — see Tier 1 doc above. */
    const val KCAL_PER_W_PER_HOUR = 3.6
    /** Multiplier in `kcal/h = METs × weight_kg` — direct from the METs definition
     *  (1 MET = 1 kcal/kg/h ≈ 3.5 mL O2/kg/min). */
    const val KCAL_PER_MET_PER_KG = 1.0

    /**
     * Result of [estimate]: instantaneous burn rate and the inputs that produced
     * it. `kcalPerHour` and `choFraction` are exposed alongside `gph` so the
     * facade can surface them to calibration logs and the Settings UI without
     * recomputing.
     */
    data class BurnEstimate(
        val gph: Double,
        val kcalPerHour: Double,
        val choFraction: Double,
        val confidence: Confidence,
        /**
         * The zone classification that produced [choFraction]. Exposed so a
         * caller (e.g. [com.enderthor.kSafe.extension.managers.CarbsTracker])
         * can use the same `IntensityZoneCalculator` output it would otherwise
         * have to compute itself — avoids classifying twice per tick.
         */
        val zoneSnapshot: ZoneSnapshot,
    ) {
        companion object {
            val NONE = BurnEstimate(0.0, 0.0, 0.0, Confidence.NONE, ZoneSnapshot(ZoneSource.NONE, -1, 0, 1.0f))
        }
    }

    /**
     * Confidence label exposed to the rider so they know how much to trust the
     * burn estimate (the four tiers documented at the top of this file).
     */
    enum class Confidence { POWER, KEYTEL, SWAIN, NONE }

    /**
     * Compute the instantaneous carb burn rate in g/h. All physiological inputs
     * are optional — the function picks the highest-confidence tier whose data
     * is available and falls through otherwise.
     *
     * @param hrBpm Current heart rate (bpm). Null when no HR sensor is paired.
     * @param powerW Current cycling power (W). Null when no power meter is paired.
     * @param profile Karoo's [UserProfile] — for weight, maxHr, restingHr, ftp,
     *   plus the rider's configured HR / power zones used by [ZoneClassifier].
     * @param riderAge Rider age (years). 0 = not entered → Tier 2 unavailable.
     * @param riderSex Rider biological sex. [RiderSex.NOT_SET] → Tier 2 unavailable.
     */
    fun estimate(
        hrBpm: Int?,
        powerW: Int?,
        profile: UserProfile?,
        riderAge: Int,
        riderSex: RiderSex,
    ): BurnEstimate {
        // Classify the intensity zone ONCE per call. The result is exposed via
        // [BurnEstimate.zoneSnapshot] so a caller (CarbsTracker) can read the
        // zone for its own UI / calibration-log surface without classifying
        // again. M2 fix — pre-v18.1 the tracker invoked IntensityZoneCalculator
        // directly AND this function classified internally, doubling the work.
        val zoneSnapshot = IntensityZoneCalculator.calculate(profile, hrBpm, powerW)
        val zone = if (zoneSnapshot.source == ZoneSource.NONE) {
            ZoneClassifier.Zone.NONE
        } else {
            ZoneClassifier.Zone(zoneSnapshot.source, zoneSnapshot.index, zoneSnapshot.total)
        }

        // Step 1 — kcal/h from the best available tier.
        val tier1 = tier1PowerKcalPerHour(powerW)
        val tier2 = if (tier1 == null) tier2KeytelKcalPerHour(hrBpm, profile, riderAge, riderSex) else null
        val tier3 = if (tier1 == null && tier2 == null) tier3SwainKcalPerHour(hrBpm, profile) else null

        val kcalPerHour: Double
        val confidence: Confidence
        when {
            tier1 != null -> { kcalPerHour = tier1; confidence = Confidence.POWER }
            tier2 != null -> { kcalPerHour = tier2; confidence = Confidence.KEYTEL }
            tier3 != null -> { kcalPerHour = tier3; confidence = Confidence.SWAIN }
            else -> return BurnEstimate.NONE.copy(zoneSnapshot = zoneSnapshot)
        }

        // Step 2 — CHO fraction at the current intensity zone. Falls back to a
        // neutral mid-zone when no zones could be classified (the early return
        // above usually handles that case via confidence=NONE; the fallback
        // keeps the function total).
        val choFraction = choFractionForZone(zone)

        // Step 3 — convert to g/h.
        val gph = kcalPerHour * choFraction / KCAL_PER_GRAM_CHO

        return BurnEstimate(
            gph = gph,
            kcalPerHour = kcalPerHour,
            choFraction = choFraction,
            confidence = confidence,
            zoneSnapshot = zoneSnapshot,
        )
    }

    // ─── Tier 1: power meter ─────────────────────────────────────────────────

    private fun tier1PowerKcalPerHour(powerW: Int?): Double? {
        if (powerW == null || powerW <= 0) return null
        return powerW * KCAL_PER_W_PER_HOUR
    }

    // ─── Tier 2: Keytel 2005 HR-based ────────────────────────────────────────

    /**
     * Keytel et al. (2005) regression. Inputs and validity range as published:
     *  - HR: 90-170 bpm typical; usable to ~ HR_MAX with reduced accuracy.
     *  - Weight: 40-120 kg.
     *  - Age: 18-65 years.
     *  - Sex: MALE / FEMALE (two distinct regressions).
     *
     * Outside these ranges the formula still returns a number but the error
     * grows quickly — the rider's UI surfaces `Confidence.KEYTEL` regardless
     * so they know which tier produced it.
     *
     * Equations (output in kJ/min, converted to kcal/h here):
     *  - Male:   EE = -55.0969 + 0.6309·HR + 0.1988·W + 0.2017·A
     *  - Female: EE = -20.4022 + 0.4472·HR - 0.1263·W + 0.0740·A
     *
     * 1 kJ = 0.2390 kcal → kcal/h = kJ/min × 60 / 4.184.
     */
    private fun tier2KeytelKcalPerHour(
        hrBpm: Int?,
        profile: UserProfile?,
        riderAge: Int,
        riderSex: RiderSex,
    ): Double? {
        if (hrBpm == null || hrBpm <= 0) return null
        // `isFinite()` symmetry with HydrationTracker.updateAmbientTemp: the SDK is
        // believed to never emit NaN / ±Infinity for weight, but the guard costs
        // one comparison and removes a "displays NaN forever" failure mode if it
        // ever did. Same rationale on Tier 3 below.
        if (profile == null || !profile.weight.isFinite() || profile.weight <= 0f) return null
        if (riderAge <= 0) return null
        if (riderSex == RiderSex.NOT_SET) return null

        val hr = hrBpm.toDouble()
        val w = profile.weight.toDouble()
        val a = riderAge.toDouble()
        val kjPerMin = when (riderSex) {
            RiderSex.MALE   -> -55.0969 + 0.6309 * hr + 0.1988 * w + 0.2017 * a
            RiderSex.FEMALE -> -20.4022 + 0.4472 * hr - 0.1263 * w + 0.0740 * a
            RiderSex.NOT_SET -> return null  // unreachable — guarded above
        }
        // Keytel can return negative values at very low HR / atypical weight.
        // Clamp to a small positive number so downstream math (deficit, alerts)
        // doesn't get fooled by a "burning negative kcal" reading.
        if (kjPerMin <= 0.0) return null
        return kjPerMin * 60.0 / 4.184
    }

    // ─── Tier 3: Swain HRR → METs ────────────────────────────────────────────

    /**
     * Swain & Leutholtz (1997): `%HRR ≈ %VO2R` (within ~10 % for healthy
     * adults). We then approximate `METs ≈ 6 × %HRR + 1` (an ACSM-style linear
     * fit for cycling that matches the documented relationship between %HRR
     * and energy expenditure in published cycle-ergometer studies), and
     * `kcal/h = METs × weight_kg` from the MET definition.
     *
     * Cruder than Keytel because it has no sex / age inputs and the linear
     * fit understates cycling-specific expenditure at higher intensities by
     * 15-25 %. Used only as a last resort when the rider hasn't entered age
     * and sex in Settings.
     */
    private fun tier3SwainKcalPerHour(hrBpm: Int?, profile: UserProfile?): Double? {
        if (hrBpm == null || hrBpm <= 0) return null
        if (profile == null) return null
        if (!profile.weight.isFinite() || profile.weight <= 0f) return null
        if (profile.maxHr <= 0) return null
        if (profile.restingHr <= 0) return null
        if (profile.maxHr <= profile.restingHr) return null  // degenerate

        val hrr = (hrBpm - profile.restingHr).toDouble() /
                  (profile.maxHr - profile.restingHr).toDouble()
        val hrrClamped = hrr.coerceIn(0.0, 1.0)
        val mets = 6.0 * hrrClamped + 1.0
        return mets * profile.weight.toDouble() * KCAL_PER_MET_PER_KG
    }

    // ─── Zone → CHO fraction ─────────────────────────────────────────────────

    /**
     * Linear map of zone index to CHO fraction. With 5 HR zones a rider in Z1
     * gets 0.30, in Z5 gets 0.95; with 7 power zones the same end-anchors
     * apply and intermediate zones interpolate. Matches the standard
     * physiology curve qualitatively while staying agnostic to the rider's
     * specific zone count.
     *
     * Returns the upper anchor when [zone] has source NONE (no usable zones
     * configured). This branch is unreachable from [estimate] (it returns
     * NONE earlier when no physiological data is present) but the fallback
     * keeps the function total.
     */
    fun choFractionForZone(zone: ZoneClassifier.Zone): Double {
        if (zone.source == ZoneSource.NONE || zone.total <= 1) {
            // Single-zone or unknown — pick the midpoint so we neither over-
            // nor under-estimate against a generic ride.
            return (CHO_MIN_FRACTION + CHO_MAX_FRACTION) / 2.0
        }
        val ratio = zone.index.toDouble() / (zone.total - 1).toDouble()
        return CHO_MIN_FRACTION + ratio * (CHO_MAX_FRACTION - CHO_MIN_FRACTION)
    }
}

/**
 * Compact zone descriptor consumed by [CarbBurnEstimator.choFractionForZone].
 * Carries only the fields the CHO-fraction lookup needs (source / index /
 * total) — distinct from [ZoneSnapshot] which retains the vestigial
 * `multiplier` field for backwards compat with the calibration-CSV column.
 *
 * Built inline by [CarbBurnEstimator.estimate] from the result of
 * [IntensityZoneCalculator.calculate] — there is no separate classifier
 * function, just this constructor + the [NONE] sentinel.
 */
object ZoneClassifier {
    data class Zone(val source: ZoneSource, val index: Int, val total: Int) {
        companion object {
            val NONE = Zone(ZoneSource.NONE, -1, 0)
        }
    }
}
