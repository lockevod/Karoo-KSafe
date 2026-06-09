package com.enderthor.kSafe.extension.util

import com.enderthor.kSafe.data.CarbFuelingState
import com.enderthor.kSafe.data.HydFuelingState

/**
 * Pure decision logic for the fueling-persistence loop in
 * [com.enderthor.kSafe.extension.KSafeExtension]. Extracted so the three gates
 * (zero-skip, unchanged-skip, deadband) can be unit-tested without a service /
 * DataStore harness — the inline version had no automated guard, and two real
 * bugs (a calories-only ride never persisting; the deadband being defeated when
 * calories were on) lived here undetected until an adversarial review.
 *
 * Persisted state is restored after a process kill (see `FUELING_RESTORE_MAX_AGE_MS`)
 * so each gate trades DataStore write frequency against worst-case loss on an
 * unexpected kill.
 */
object FuelingPersistPolicy {

    /** Deadband on `CarbFuelingState.cumBurnedG`. At 50 g/h the integrator advances
     *  ~0.42 g per 30 s cycle; 5 g gives ~6 min between writes at moderate intensity
     *  and bounds worst-case loss to ≤ 5 g — well below the burn estimator's own
     *  10-15 % error, so rider-invisible. */
    const val PERSIST_CARB_BURN_DEADBAND_G: Float = 5.0f

    /** Deadband on `HydFuelingState.cumTargetMl`. At the default 750 ml/h the
     *  integrator advances ~6.25 ml per 30 s cycle; 60 ml = ~4.8 min between writes,
     *  worst-case loss ≤ 60 ml (within the SweatEstimator's ±20 %). */
    const val PERSIST_HYD_TARGET_DEADBAND_ML: Float = 60.0f

    /** Deadband on `CarbFuelingState.cumKcal`. kcal accrues faster than carb grams
     *  (~10 kcal per 30 s cycle at 600 kcal/h), so a 10 kcal band keeps the deadband
     *  from firing on a near-stationary rider while bounding worst-case loss to
     *  ≤ 10 kcal. Without a calorie term a calories-only ride would either never
     *  persist (zero-skip) or write every cycle (deadband defeated). */
    const val PERSIST_KCAL_DEADBAND_KCAL: Float = 10.0f

    /**
     * Whether the current fueling state should be written to DataStore this cycle.
     *
     * @param prevCarb / [prevHyd] the last SUCCESSFULLY-persisted state (null before
     *   the first write). Deltas are measured against the last write — NOT the last
     *   cycle — so a monotonic accumulator that grows a little every cycle still
     *   triggers a write once the cumulative delta exceeds the band (loss stays
     *   bounded by the band, never unbounded).
     */
    fun shouldPersist(
        prevCarb: CarbFuelingState?,
        prevHyd: HydFuelingState?,
        curCarb: CarbFuelingState,
        curHyd: HydFuelingState,
    ): Boolean {
        // Zero-skip: nothing accumulated worth persisting yet. cumKcal MUST be part
        // of this — a calories-only ride has cumBurnedG / cumLoggedG / hydration all
        // at zero, so omitting cumKcal here means it never persists at all.
        if (curCarb.cumBurnedG <= 0f && curCarb.cumLoggedG == 0 &&
            curCarb.cumKcal <= 0f &&
            curHyd.cumTargetMl <= 0f && curHyd.cumLoggedMl == 0
        ) return false

        // Unchanged-skip: identical to the last write.
        if (curCarb == prevCarb && curHyd == prevHyd) return false

        // Deadband: write deferred while the ONLY changes are small accumulator
        // deltas within their bands. The "other fields" comparison forces each
        // free-running accumulator (cumBurnedG, activeIntegrationMs, cumKcal,
        // cumTargetMl) equal to the prior value, so it reflects only the rider-
        // visible / event-driven fields; the per-accumulator bounds gate the rest.
        if (prevCarb != null && prevHyd != null) {
            val burnDelta = curCarb.cumBurnedG - prevCarb.cumBurnedG
            val targetDelta = curHyd.cumTargetMl - prevHyd.cumTargetMl
            val kcalDelta = curCarb.cumKcal - prevCarb.cumKcal
            val carbOtherUnchanged = curCarb.copy(
                cumBurnedG = prevCarb.cumBurnedG,
                activeIntegrationMs = prevCarb.activeIntegrationMs,
                cumKcal = prevCarb.cumKcal,
            ) == prevCarb
            val hydOtherUnchanged = curHyd.copy(cumTargetMl = prevHyd.cumTargetMl) == prevHyd
            val withinDeadband = carbOtherUnchanged && hydOtherUnchanged &&
                burnDelta in 0f..PERSIST_CARB_BURN_DEADBAND_G &&
                targetDelta in 0f..PERSIST_HYD_TARGET_DEADBAND_ML &&
                kcalDelta in 0f..PERSIST_KCAL_DEADBAND_KCAL
            if (withinDeadband) return false
        }
        return true
    }
}
