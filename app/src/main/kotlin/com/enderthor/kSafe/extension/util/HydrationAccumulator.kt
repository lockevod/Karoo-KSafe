package com.enderthor.kSafe.extension.util

/** Milligrams of sodium per mmol. */
const val NA_MG_PER_MMOL = 22.99

/** Running per-session hydration totals (all accumulated while integrating). */
data class HydAccum(
    val cumTargetMl: Float = 0f,
    val cumSweatBaseMl: Float = 0f,
    val cumSweatMl: Float = 0f,
    val cumSodiumMg: Float = 0f,
    val coveredMs: Long = 0L,
    val lowConfMs: Long = 0L,
)

data class HydTickInput(
    val dtMs: Long,
    val integrating: Boolean,
    val baseSweatMlPerHour: Double,
    val confidence: SweatConfidence,
    val dynamicMode: Boolean,
    val staticTargetMlPerHour: Int,
    val multiplierPct: Int,
    val replacementPct: Int,
    val naMmolL: Int,
)

/** [drinkRateMlPerHour] is 0 when the tick did not integrate. */
data class HydTickResult(val accum: HydAccum, val drinkRateMlPerHour: Float)

/** Pure per-tick integrator. The multiplier scales sweat/target but never the base total. */
fun hydrationStep(acc: HydAccum, input: HydTickInput): HydTickResult {
    if (!input.integrating) return HydTickResult(acc, 0f)
    val hours = input.dtMs / 3_600_000.0
    val sweatRate = input.baseSweatMlPerHour * input.multiplierPct.coerceIn(50, 200) / 100.0
    val drinkRate = if (input.dynamicMode) {
        sweatRate * input.replacementPct.coerceIn(50, 100) / 100.0
    } else {
        input.staticTargetMlPerHour.toDouble()
    }
    val dSweat = sweatRate * hours
    val low = input.confidence == SweatConfidence.LOW
    return HydTickResult(
        HydAccum(
            cumTargetMl = acc.cumTargetMl + (drinkRate * hours).toFloat(),
            cumSweatBaseMl = acc.cumSweatBaseMl + (input.baseSweatMlPerHour * hours).toFloat(),
            cumSweatMl = acc.cumSweatMl + dSweat.toFloat(),
            cumSodiumMg = acc.cumSodiumMg + (dSweat * input.naMmolL * NA_MG_PER_MMOL / 1000.0).toFloat(),
            coveredMs = acc.coveredMs + if (low) 0L else input.dtMs,
            lowConfMs = acc.lowConfMs + if (low) input.dtMs else 0L,
        ),
        drinkRate.toFloat(),
    )
}
