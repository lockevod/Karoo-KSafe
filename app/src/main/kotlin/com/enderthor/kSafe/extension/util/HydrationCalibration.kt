package com.enderthor.kSafe.extension.util

import com.enderthor.kSafe.data.LastHydrationRide
import kotlin.math.roundToInt

const val MIN_COVERAGE = 0.85

private const val HOUR_MS = 3_600_000L
private const val MAX_RIDE_AGE_MS = 6 * HOUR_MS
private const val MIN_RIDE_MS = HOUR_MS
private const val MIN_MEASURED_ML = 800
/** Share of body-mass loss that is sweat (rest: respiratory water, metabolic mass loss). */
private const val BODY_MASS_SWEAT_FACTOR = 0.92

/**
 * Model-covered share of the ride; null or non-positive ride time never qualifies. Covered time
 * above 105 % of ride time is implausible (cross-ride restore, forward wall-clock jump) and fails.
 */
fun coverageOk(coveredMs: Long, rideTimeMs: Long?): Boolean =
    rideTimeMs != null && rideTimeMs > 0 && coveredMs.toDouble() / rideTimeMs >= MIN_COVERAGE &&
        coveredMs * 100 <= rideTimeMs * 105

data class CalibrationInput(
    val preKg: Double, val postKg: Double, val drinkMl: Int, val foodG: Int,
    val urinated: Boolean, val nowMs: Long,
)

enum class CalibrationRejection {
    NO_RIDE, RIDE_CHANGED, ALREADY_CALIBRATED, TOO_OLD, URINATED, NO_RIDE_TIME, TOO_SHORT,
    LOW_COVERAGE, IMPLAUSIBLE_WEIGHT, TOO_LITTLE_SWEAT, RATIO_OUT_OF_RANGE,
}

sealed interface CalibrationResult {
    data class Accepted(
        val measuredMl: Int, val ratio: Float, val newRatios: List<Float>, val newMultiplierPct: Int,
    ) : CalibrationResult
    data class Rejected(val reason: CalibrationRejection) : CalibrationResult
}

/** Why this ride's record can never be calibrated whatever the weigh-in, or null if it can. */
fun rideCalibrationBlocker(ride: LastHydrationRide): CalibrationRejection? = when {
    ride.rideTimeMs <= 0 -> CalibrationRejection.NO_RIDE_TIME
    ride.rideTimeMs < MIN_RIDE_MS -> CalibrationRejection.TOO_SHORT
    !coverageOk(ride.coveredMs, ride.rideTimeMs) -> CalibrationRejection.LOW_COVERAGE
    else -> null
}

/** Weigh-in calibration. The first failing check (in enum order) wins. */
fun calibrate(
    ride: LastHydrationRide?,
    expectedRideId: Long,
    input: CalibrationInput,
    existingRatios: List<Float>,
): CalibrationResult {
    fun reject(r: CalibrationRejection) = CalibrationResult.Rejected(r)
    if (ride == null) return reject(CalibrationRejection.NO_RIDE)
    if (ride.rideId != expectedRideId) return reject(CalibrationRejection.RIDE_CHANGED)
    if (ride.calibrated) return reject(CalibrationRejection.ALREADY_CALIBRATED)
    if (input.nowMs - ride.endedAtMs > MAX_RIDE_AGE_MS) return reject(CalibrationRejection.TOO_OLD)
    if (input.urinated) return reject(CalibrationRejection.URINATED)
    rideCalibrationBlocker(ride)?.let { return reject(it) }
    val lossKg = input.preKg - input.postKg
    if (input.preKg !in 30.0..200.0 || lossKg !in -1.0..5.0) return reject(CalibrationRejection.IMPLAUSIBLE_WEIGHT)
    val measured = (lossKg * 1000 * BODY_MASS_SWEAT_FACTOR + input.drinkMl + input.foodG).roundToInt()
    if (measured < MIN_MEASURED_ML) return reject(CalibrationRejection.TOO_LITTLE_SWEAT)
    if (ride.cumSweatBaseMl <= 0f) return reject(CalibrationRejection.RATIO_OUT_OF_RANGE)
    val ratio = measured / ride.cumSweatBaseMl
    if (ratio !in 0.3f..3.0f) return reject(CalibrationRejection.RATIO_OUT_OF_RANGE)
    val ratios = (existingRatios + ratio).takeLast(3)
    return CalibrationResult.Accepted(measured, ratio, ratios, multiplierFromRatios(ratios))
}

fun multiplierFromRatios(ratios: List<Float>): Int =
    if (ratios.isEmpty()) 100 else (ratios.average() * 100).roundToInt().coerceIn(50, 200)

data class SodiumAdvice(
    val mgLost: Int, val mgPerHour: Int, val partial: Boolean,
    val bottleMgPerL: Int?, val recommended: Boolean, val fluidLimited: Boolean,
)

/** Post-ride sodium summary; the bottle line needs a whole-ride record (coverage) of >= 2 h. */
fun sodiumAdvice(ride: LastHydrationRide): SodiumAdvice {
    val partial = !coverageOk(ride.coveredMs, ride.rideTimeMs)
    val hours = ride.rideTimeMs.toDouble() / HOUR_MS
    val r = if (ride.cumSweatMl > 0f) ride.cumLoggedMl.toDouble() / ride.cumSweatMl else 0.0
    val bottle = if (partial || ride.rideTimeMs < 2 * HOUR_MS || ride.cumSweatMl <= 0f || r < 0.5) null
    else (0.5 * ride.naMmolL * NA_MG_PER_MMOL / r).roundToInt()
    return SodiumAdvice(
        mgLost = ride.cumSodiumMg.roundToInt(),
        mgPerHour = if (ride.rideTimeMs > 0) (ride.cumSodiumMg / hours).roundToInt() else 0,
        partial = partial,
        bottleMgPerL = bottle,
        recommended = bottle != null && ride.naMmolL >= 50 && r > 0.8,
        fluidLimited = !partial && ride.cumSweatMl > 0f && r < 0.5,
    )
}
