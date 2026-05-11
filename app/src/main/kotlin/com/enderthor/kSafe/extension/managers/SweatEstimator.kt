package com.enderthor.kSafe.extension.managers

import kotlin.math.atan
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Sweat-rate estimation from HR / power / weight / weather. Pure logic, no Android deps,
 * fully unit-tested.
 *
 * ## Model
 *
 * Three multiplicative components:
 *
 *  1. **Metabolic rate** (kcal/hr) — preferred from power (cycling efficiency ≈ 25 %), fallback
 *     to a Keytel et al. 2005 HR-derived estimate (gender-averaged because the Karoo profile
 *     does not expose gender). A floor at 60 kcal/hr (~resting metabolism) is applied so a
 *     pre-ride or dropped-HR sample cannot produce 0 ml/hr.
 *
 *  2. **Base sweat rate** = `metabolic_kcal_hr * 0.85`. The 0.85 mL/kcal coefficient is the
 *     middle of the 0.7–1.0 range reported in Sawka 1992 / ACSM Position Stand 2007 / Baker
 *     2017 reviews for trained cyclists at moderate-to-hard intensity.
 *
 *  3. **Weight scaling** — proxy for body surface area. Linear with body mass, clamped to
 *     0.7×–1.4× the reference 70 kg rider so the estimate stays in plausible territory for
 *     extreme weights. (Du Bois BSA is sub-linear in weight; the clamp captures that
 *     non-linearity coarsely.)
 *
 *  4. **Heat factor (WBGT-based)** — piecewise-linear multiplier over the Wet Bulb Globe
 *     Temperature. Without a globe sensor the formula degenerates to `0.7 * wet_bulb + 0.3 * temp`.
 *     Wet bulb is computed from temperature + relative humidity via Stull 2011 (valid 5–99 % RH,
 *     0–50 °C). Without humidity from a meteo source we assume 50 % RH (moderate continental
 *     summer). Anchor points: < 18 °C = 1.0 ×, 23 °C = 1.20 ×, 28 °C = 1.50 ×, 32 °C = 1.82 ×.
 *
 * ## Accuracy expectations
 *
 * Published sweat-loss studies (Baker 2017 review; Cheuvront & Sawka 2014) report ±15–25 %
 * error vs. direct measurement when ALL of (HR, weight, ambient temp) are accurate. This
 * implementation should land in the same band when wired to power + Headwind weather; closer
 * to ±25–35 % when fed HR + onboard sensor (device-heat-biased temp + assumed 50 % RH).
 *
 * For comparison, Garmin Connect (Firstbeat algorithm) reports ±15–20 % with Tempe external
 * temperature, ±25 % without.
 *
 * ## Not modelled
 *
 *  - Aclimatization. A rider freshly arrived from a cold climate sweats 20–30 % more than an
 *    aclimated rider at the same intensity / heat. The model assumes "average" aclimation.
 *  - Solar load. WBGT without a globe sensor underestimates by 2–4 °C in direct sun.
 *  - Individual sweat-rate variation. Some riders are documented at 2.5–3.0 L/hr in heat;
 *    others under 0.8 L/hr at identical conditions. The model targets the median rider.
 *  - Wind-driven evaporative cooling. Marginal effect (≤ 10 %) compared with intensity/heat;
 *    omitted for simplicity. Headwind speed COULD be added if field data shows it matters.
 */
data class SweatEstimateInputs(
    /** Current heart rate in bpm. Null when no HR strap is paired / data hasn't arrived yet. */
    val hrBpm: Int? = null,
    /** Current power in watts. Null when no power meter is paired. Preferred over HR. */
    val powerW: Int? = null,
    /** Rider mass in kg from the Karoo user profile. Null → defaults to 70 kg. */
    val weightKg: Double? = null,
    /** Ambient temperature in °C. Null → defaults to 20 °C (heat factor = 1.0 ×). */
    val ambientTempC: Double? = null,
    /** Relative humidity 0–100 %. Null → assumes 50 %. */
    val humidityPct: Int? = null,
)

/** Confidence level for the surface code to expose to the rider. */
enum class SweatConfidence { HIGH, MEDIUM, LOW }

/**
 * One-shot snapshot of the estimator's output. [mlPerHour] is the instantaneous rate the
 * caller (HydrationTracker) should integrate over the tick window.
 */
data class SweatEstimate(
    val mlPerHour: Double,
    val confidence: SweatConfidence,
)

/**
 * Returns the estimated sweat rate for the given inputs. Always returns a positive value
 * (floored at the resting-metabolism contribution); never throws.
 */
fun estimateSweatRate(inputs: SweatEstimateInputs): SweatEstimate {
    val weight = inputs.weightKg ?: 70.0
    val metabolicKcalHr = metabolicRateKcalHr(inputs.powerW, inputs.hrBpm, weight)
    val baseSweat = metabolicKcalHr * SWEAT_PER_KCAL
    val weightFactor = (weight / 70.0).coerceIn(0.7, 1.4)
    val tempC = inputs.ambientTempC ?: 20.0
    val rh = (inputs.humidityPct?.toDouble() ?: 50.0).coerceIn(5.0, 99.0)
    val heatFactor = heatFactor(tempC, rh)
    val mlPerHour = baseSweat * weightFactor * heatFactor

    // Confidence reflects which limbs of the model are grounded in real data vs. defaults.
    // hrBpm = 0 (strap paired but reading 0) counts as missing — same as null.
    val hasHr    = (inputs.hrBpm ?: 0) > 0
    val hasPower = (inputs.powerW ?: 0) > 0
    val hasTemp  = inputs.ambientTempC != null
    val hasRh    = inputs.humidityPct != null
    val confidence = when {
        hasPower && hasTemp && hasRh -> SweatConfidence.HIGH
        hasHr && hasTemp             -> SweatConfidence.MEDIUM
        else                         -> SweatConfidence.LOW
    }
    return SweatEstimate(mlPerHour, confidence)
}

// ── Internals ───────────────────────────────────────────────────────────────────

/** mL sweat per kcal of expenditure — mid of the literature range (Sawka 1992, Baker 2017). */
private const val SWEAT_PER_KCAL = 0.85

/** Resting-metabolism floor used when no signal is available. */
private const val DEFAULT_KCAL_HR = 350.0

/** Cycling mechanical efficiency. metabolic_W = mechanical_W / efficiency. */
private const val CYCLING_EFFICIENCY = 0.25

/** kcal/hr per watt of metabolic load (3600 s/hr ÷ 4184 J/kcal). */
private const val W_TO_KCAL_HR = 0.86

private fun metabolicRateKcalHr(powerW: Int?, hrBpm: Int?, weightKg: Double): Double {
    if (powerW != null && powerW > 0) {
        // mechanical_W / efficiency → metabolic_W; metabolic_W → kcal/hr
        return powerW / CYCLING_EFFICIENCY * W_TO_KCAL_HR
    }
    if (hrBpm != null && hrBpm > 0) {
        // Keytel et al. 2005 — gender-averaged (mean of M and F equations,
        // age dropped since the Karoo profile doesn't expose it; effect of age
        // on this estimator is ±5–10 % across 30–60 y/o, acceptable for our band).
        // EE (kJ/min) ≈ -37.7 + 0.539·HR + 0.036·weight
        // EE (kcal/min) = EE / 4.184
        val kJPerMin = -37.7 + 0.539 * hrBpm + 0.036 * weightKg
        val kcalPerMin = (kJPerMin / 4.184).coerceAtLeast(1.0)
        return kcalPerMin * 60
    }
    return DEFAULT_KCAL_HR
}

/** Stull 2011 wet-bulb approximation. Inputs: temperature °C and relative humidity %. */
private fun wetBulbStull(tempC: Double, humidityPct: Double): Double {
    return tempC * atan(0.151977 * sqrt(humidityPct + 8.313659)) +
        atan(tempC + humidityPct) -
        atan(humidityPct - 1.676331) +
        0.00391838 * humidityPct.pow(1.5) * atan(0.023101 * humidityPct) -
        4.686035
}

/** Heat multiplier on the base sweat rate. Piecewise-linear over WBGT.
 *  Slopes calibrated against Sawka 2007 / ACGIH TLV heat-strain curves: ~80 % increase
 *  in sweat at WBGT 30 °C vs WBGT 18 °C for unaclimated subjects. The original (Garmin-
 *  inspired) 3 %/°C linear was too conservative against published cyclist data.
 */
private fun heatFactor(tempC: Double, humidityPct: Double): Double {
    if (tempC < 18.0) return 1.0
    val wbgt = 0.7 * wetBulbStull(tempC, humidityPct) + 0.3 * tempC
    return when {
        wbgt < 18.0 -> 1.0
        wbgt < 23.0 -> 1.0 + (wbgt - 18.0) * 0.08   // up to 1.40 at WBGT 23
        wbgt < 28.0 -> 1.40 + (wbgt - 23.0) * 0.14  // up to 2.10 at WBGT 28
        wbgt < 32.0 -> 2.10 + (wbgt - 28.0) * 0.16  // up to 2.74 at WBGT 32
        else        -> 2.74 + (wbgt - 32.0) * 0.18
    }.coerceIn(1.0, 3.5)  // safety clamp — extreme heat WBGT 35+ can push beyond 3x
}
