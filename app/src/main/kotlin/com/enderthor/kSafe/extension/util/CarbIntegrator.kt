package com.enderthor.kSafe.extension.util

/**
 * Pure helper for the carb-tracker's per-tick integration step.
 *
 * Owns three coupled decisions that used to live inline in
 * [com.enderthor.kSafe.extension.managers.CarbsTracker.tick]:
 *
 *  1. **Movement gate** — no integration when the rider is stationary or when
 *     the SDK speed is stale (probable GPS lock loss inside a tunnel / dense
 *     forest, where the SDK keeps repeating the last known value bit-exact).
 *  2. **Absorption-cap clamp** — the displayed burn rate and the rate that
 *     drives the running total are clamped to [ABSORPTION_CAP_GPH] (~90 g/h
 *     for an untrained gut, Jeukendrup 2014 / ISSN 2017). If the rider's
 *     physiological burn is higher (very common at Z4-Z5 with power
 *     > ~150 W), the deficit grows honestly — clamping the *integration*
 *     rate prevents the cumulative total from racing ahead of any plausible
 *     intake plan.
 *  3. **Active-time accumulator gate** — `activeIntegrationMs` advances only
 *     when something is being integrated (`effectiveGph > 0`). A movement-
 *     gate-passing tick with confidence=NONE adds 0 to the cumulative burn
 *     AND must NOT count toward active time — otherwise the session-average
 *     burn rate (which divides cumBurnedG by activeIntegrationMs) would
 *     dilute toward zero for riders without HR / power and the "Pair HR/Pwr"
 *     guard on the data field would never fire intuitively.
 *
 * Stateless and side-effect-free — the caller is responsible for accumulating
 * the returned deltas onto its own `cumBurnedG` / `activeIntegrationMs`.
 */
object CarbIntegrator {

    /** Speed below which the rider is treated as stationary and carb
     *  integration pauses. 2 km/h sits below a slow walking pace, so any
     *  actual riding (even pushing the bike up a hill) keeps integrating.
     *  Mirrored on the hydration side — the trackers stay in lockstep. */
    const val MOVING_GATE_KMH = 2.0

    /** If the SDK has been emitting the same bit-exact speed value for longer
     *  than this, treat as stale (probable GPS lock loss) and stop integrating.
     *  Real GPS readings vary by ≥0.1 km/h between emissions even at cruise,
     *  so cruise control doesn't hit this. */
    const val SPEED_STALE_MS = 10_000L

    data class IntegrationStep(
        /** Grams to add to cumBurnedG this step. 0 if the movement gate blocked
         *  integration or if there is no burn rate to integrate. */
        val deltaG: Float,
        /** Milliseconds to add to activeIntegrationMs this step. 0 unless the
         *  movement gate passed AND `effectiveGph > 0` — see the class KDoc. */
        val deltaActiveMs: Long,
        /** The post-clamp burn rate used for [deltaG]. Useful for tests + for
         *  the caller's downstream surface (logs, alerts) that wants the same
         *  number the integrator saw. */
        val effectiveGph: Double,
        /** True iff the movement gate let this step through (used by tests
         *  and by callers that want to surface "is integrating" to the UI). */
        val moving: Boolean,
        /** Movement-gated dt for THIS step, INDEPENDENT of whether burn > 0.
         *  Equals [dtMs] when the movement gate passed and dt > 0; 0 otherwise
         *  (stationary, GPS-stale, or first tick). Used by the calorie
         *  accumulator, which must keep advancing in the fallback regime where
         *  carb burn (and therefore [deltaActiveMs]) is 0. */
        val gatedDtMs: Long,
    )

    /**
     * Compute one tick's deltas. The caller passes the current burn rate
     * (raw, pre-clamp), the wall-clock dt since the previous tick, and the
     * raw speed inputs; the helper applies the gates and the cap and returns
     * the deltas to accumulate.
     *
     * @param burnGph Current burn rate in g/h (typically from
     *   [CarbBurnEstimator.estimate]). May exceed [ABSORPTION_CAP_GPH]; the
     *   helper clamps it.
     * @param dtMs Wall-clock ms since the previous tick. Clamped to 0 if
     *   negative (NTP can push the clock backwards by seconds).
     * @param speedKmh Current speed reading. `null` means the SDK hasn't
     *   emitted yet — treated as stationary.
     * @param speedStale True when the SDK has been repeating the same value
     *   for longer than [SPEED_STALE_MS]. Treated as stationary regardless
     *   of [speedKmh] magnitude.
     */
    fun integrate(
        burnGph: Double,
        dtMs: Long,
        speedKmh: Double?,
        speedStale: Boolean,
    ): IntegrationStep {
        val moving = !speedStale && speedKmh != null && speedKmh >= MOVING_GATE_KMH
        if (!moving || dtMs <= 0L) {
            // Either the rider is stationary / GPS-stale, or this is the first
            // tick of the session (caller passes dtMs <= 0 to signal "no
            // previous timestamp"). Return all-zero — but still report the
            // post-clamp effectiveGph so the caller can surface it in the UI
            // even while frozen.
            return IntegrationStep(
                deltaG = 0f,
                deltaActiveMs = 0L,
                effectiveGph = burnGph.coerceAtMost(ABSORPTION_CAP_GPH.toDouble()),
                moving = moving,
                gatedDtMs = 0L,
            )
        }
        val dtSec = dtMs / 1000f
        val effectiveGph = burnGph.coerceAtMost(ABSORPTION_CAP_GPH.toDouble())
        val ratePerSec = effectiveGph / 3600.0
        val deltaG = (dtSec * ratePerSec).toFloat()
        // `effectiveGph > 0`: confidence=NONE produces gph=0, which yields
        // deltaG=0; that tick must NOT count toward active-integration time.
        // Use the original Long `dtMs` instead of round-tripping through
        // `dtSec` (Float): the Float→Long path truncates any sub-1000 ms
        // residue, e.g. dtMs=1001 → dtSec=1.001f → *1000.0 → 1001.0 →
        // .toLong()=1001 nominally, but the Float quantum at ~1.0 is
        // ~1.2e-7 so values that look exact in Double drift after the
        // Float cast and a 6+ hour ride can drop ~50-100 ms of
        // active-integration time. `dtMs` is already > 0 by the early-
        // return at the top of the function, no coerce needed.
        val deltaActiveMs = if (effectiveGph > 0.0) dtMs else 0L
        return IntegrationStep(
            deltaG = deltaG,
            deltaActiveMs = deltaActiveMs,
            effectiveGph = effectiveGph,
            moving = true,
            gatedDtMs = dtMs,
        )
    }
}
