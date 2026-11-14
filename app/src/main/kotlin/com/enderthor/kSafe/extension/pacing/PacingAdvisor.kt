package com.enderthor.kSafe.extension.pacing

enum class PacingState { KEEP, CONSERVE, HIGH_MARGIN }

data class PacingInput(
    val nowMs: Long, val moving: Boolean, val hasPower: Boolean, val positionTrusted: Boolean, val climbsKnown: Boolean,
    val remainingMovingS: Double?, val elapsedMovingS: Double, val blocksAhead: Int,
    val secondsToNextBlock: Double?, val metresToNextBlock: Double?, val nextBlockId: Int?,
    val kjPerKg: Double, val projectedKjKgFinish: Double?, val projectedKjKgNextTop: Double?, val kneeKjKg: Double,
    val driftPct: Double?, val driftStableMs: Long?, val wTrusted: Boolean, val wPct: Double?,
    val projectedWAtNextBlockPct: Double?, val msSinceWellnessOrMedical: Long?,
    /** Share of moving time with power samples; null = unknown (under a minute moving). */
    val powerCoverage: Double? = null,
)

data class PacingDecision(
    val state: PacingState, val recoverW: Boolean, val reason: String,
    val alertWindowPacing: Boolean, val alertWindowW: Boolean, val alertWindowFuel: Boolean,
)

/** Thresholds are INITIAL values to be fitted from phase-1a PACE_* logs (spec §3.6). */
object PacingAdvisor {
    const val LEAD_FUEL_S = 900.0
    const val LEAD_PACING_S = 300.0
    const val LEAD_W_S = 270.0
    const val MIN_LEAD_M = 300.0
    const val TOO_LATE_M = 100.0
    const val MAX_LEAD_M = 10_000.0
    const val MIN_POWER_COVERAGE = 0.9

    fun inLeadWindow(secondsToBlock: Double?, metresToBlock: Double?, leadS: Double): Boolean {
        if (secondsToBlock == null || metresToBlock == null || !secondsToBlock.isFinite() || !metresToBlock.isFinite()) return false
        if (metresToBlock < TOO_LATE_M || metresToBlock > MAX_LEAD_M) return false
        return secondsToBlock <= leadS || metresToBlock <= MIN_LEAD_M
    }

    fun decide(i: PacingInput, prev: PacingState): PacingDecision {
        if (!i.moving) return PacingDecision(if (prev == PacingState.HIGH_MARGIN) PacingState.KEEP else prev, false, "stopped", false, false, false)
        val fuelWin = i.positionTrusted && inLeadWindow(i.secondsToNextBlock, i.metresToNextBlock, LEAD_FUEL_S)
        if (!i.hasPower) return PacingDecision(PacingState.KEEP, false, "no_power", false, false, fuelWin)

        val remainingFrac = i.remainingMovingS?.let { it / (it + i.elapsedMovingS) }
        val worstProj = listOfNotNull(i.projectedKjKgFinish, i.projectedKjKgNextTop).maxOrNull()
        val drift = i.driftPct

        val conserveEnter = (worstProj != null && worstProj > i.kneeKjKg) ||
            (drift != null && drift > 10.0 && (remainingFrac ?: 0.0) > 0.4)
        val conserveStay = prev == PacingState.CONSERVE &&
            ((worstProj != null && worstProj >= i.kneeKjKg * 0.9) || (drift != null && drift >= 8.0))

        val highMargin = i.positionTrusted && i.climbsKnown && i.wTrusted &&
            i.powerCoverage != null && i.powerCoverage >= MIN_POWER_COVERAGE &&
            (i.wPct ?: 0.0) >= 0.6 && drift != null && drift < 5.0 &&
            (i.driftStableMs ?: 0L) >= 10 * 60_000L &&
            i.projectedKjKgFinish != null && i.projectedKjKgFinish < 0.7 * i.kneeKjKg &&
            ((remainingFrac != null && remainingFrac <= 0.3) || i.blocksAhead == 1) &&
            (i.msSinceWellnessOrMedical == null || i.msSinceWellnessOrMedical > 30 * 60_000L)

        val (state, reason) = when {
            conserveEnter -> PacingState.CONSERVE to (if (worstProj != null && worstProj > i.kneeKjKg) "proj_over_knee" else "drift_high")
            conserveStay -> PacingState.CONSERVE to "hysteresis"
            highMargin -> PacingState.HIGH_MARGIN to "margin"
            else -> PacingState.KEEP to "keep"
        }
        val recoverW = i.wTrusted && (i.projectedWAtNextBlockPct ?: 1.0) < 0.4
        return PacingDecision(
            state, recoverW, reason,
            alertWindowPacing = i.positionTrusted && inLeadWindow(i.secondsToNextBlock, i.metresToNextBlock, LEAD_PACING_S),
            alertWindowW = recoverW && i.positionTrusted && inLeadWindow(i.secondsToNextBlock, i.metresToNextBlock, LEAD_W_S),
            alertWindowFuel = fuelWin,
        )
    }
}
