package com.enderthor.kSafe.extension.crash

/**
 * Speed-gated verification for an on-side confirm whose orientation was read mid-motion.
 * Silent-clears ONLY on riding speed that is above the floor for the whole window AND fresh when
 * the window closes; anything else escalates to the normal cancellable countdown. A speed-floor
 * breach escalates on the sample that sees it, so the bike actually stopping still gets an
 * immediate countdown; only the staleness verdict waits for window end (see [onTick]). The
 * accelerometer is deliberately NOT consulted here (it is the noisy signal that caused the FP).
 *
 * **This is FN-safe against a PERSISTING loss of speed, not against a transient one.** Deferring
 * the staleness verdict deliberately spends a false-negative budget: a GPS that goes quiet and
 * recovers before the window closes now clears where it used to escalate. That is the change's
 * purpose, not a side effect — see [onTick] — and it is a design bet, not a proven-safe
 * transformation. Anyone retuning [Thresholds.movingVigilanceWindowMs] or
 * [Thresholds.movingVigilanceSpeedFreshMs] is spending that budget and should re-measure it.
 */
class MovingVigilance(private val thresholds: Thresholds) {

    enum class Outcome { PENDING, CLEAR, ESCALATE }

    @Volatile private var armedAtMs: Long = NOT_ARMED

    val isArmed: Boolean get() = armedAtMs != NOT_ARMED

    fun arm(nowMs: Long) { armedAtMs = nowMs }

    fun reset() { armedAtMs = NOT_ARMED }

    /**
     * Drive once per sensor sample while armed. [speedFresh] must be `!isGpsStale(now)`.
     * Returns [Outcome.CLEAR]/[Outcome.ESCALATE] exactly once (self-resets), else PENDING.
     *
     * The two doubts are NOT treated alike:
     *  - a **speed-floor breach** is the bike genuinely stopping → escalate on the sample
     *    that sees it, so a real fall keeps its immediate countdown;
     *  - **stale speed** is a measurement doubt, not evidence of a stop → defer the verdict
     *    to window end and judge freshness there.
     *
     * Escalating on the first stale sample was the residual FP: a GPS that freezes for a
     * moment mid-descent, then resumes, read as a crash. The VIGIL_SHADOW probe shipped in
     * 2.2.2 measured this exact rule against the field — of 12 real escalates in the
     * 2026-09-06 batch, 6 carried `would_be=CLEAR`.
     *
     * What that evidence does and does not establish: all 6 were rider-cancelled within 3–9 s and
     * all 6 riders were back above 18 km/h shortly after, so all 6 were false positives — the
     * budget was spent entirely on FPs in that batch. It does NOT establish that the deferred rule
     * is FN-free: "no escalate the shadow judged ESCALATE would be lost" is true by construction,
     * since the shadow computes this very rule. The FN side is a design bet resting on a downed
     * rider not sustaining a reported ≥ floor speed across the whole window.
     */
    fun onTick(nowMs: Long, speedKmh: Double, speedFresh: Boolean): Outcome {
        if (!isArmed) return Outcome.PENDING
        if (speedKmh < thresholds.movingVigilanceSpeedKmh) {
            reset(); return Outcome.ESCALATE
        }
        // [nowMs] MUST come from a monotonic source — the caller passes `clock.monotonicMs()`,
        // NOT the wall clock the sensor samples are stamped with. On the wall clock an NTP/GPS
        // step would either close the window early (a forward step clears a real crash without
        // ever observing 4 s of riding) or strand it in the future (a backward step leaves
        // `elapsed >= window` unsatisfiable, losing an alert that can only ESCALATE).
        //
        // The negative-elapsed branch below is defence in depth for a caller that gets that
        // wrong; with a monotonic source it is unreachable. It resolves as ESCALATE because the
        // old rule's escalate had no clock dependency at all — only CLEAR may require a real
        // elapsed window, since delaying a CLEAR is fail-safe.
        val elapsed = nowMs - armedAtMs
        if (elapsed < 0L || elapsed >= thresholds.movingVigilanceWindowMs) {
            reset(); return if (elapsed >= 0L && speedFresh) Outcome.CLEAR else Outcome.ESCALATE
        }
        return Outcome.PENDING
    }

    private companion object { const val NOT_ARMED = -1L }
}
