package com.enderthor.kSafe.extension.util

/**
 * Pure helper extracted from [com.enderthor.kSafe.extension.managers.CarbsTracker]
 * and [com.enderthor.kSafe.extension.managers.HydrationTracker]. Both trackers
 * implement the same grid-aligned time-alert + cooldown-gated deficit-alert
 * machinery; lifting it out unblocks unit testing without a Robolectric harness.
 *
 * Two contracts pinned by tests, each implemented as a pure function:
 *
 *  1. **Time-grid alignment** (`currentDueTimeTick`). The schedule is fixed at
 *     `sessionStartMs + N × intervalMs` for N = 1, 2, 3, … Rider logs do NOT
 *     shift the grid. Each tick fires at most once.
 *
 *  2. **Initial-delay FILTER** (not shift). When the rider hasn't logged
 *     anything yet, ticks earlier than `sessionStartMs + initialDelayMs` are
 *     silently dropped — the grid stays anchored to session start. Once the
 *     rider has logged at least one item the filter releases.
 *
 *  3. **Deficit reminder cooldown** (`shouldFireDeficit`). Once the deficit
 *     crosses the rider-configured threshold, subsequent reminder alerts are
 *     gated by a configurable cooldown (`reminderIntervalMs`). The first
 *     alert in a session may additionally be gated by `initialDelayMs` if the
 *     rider hasn't logged anything yet.
 *
 *  4. **Unacknowledged back-off** (`unackedFires`). The flat cooldown alone has
 *     no ceiling: field logs (2026-07-22 sweep) show one rider getting 13
 *     hydration prompts in an hour and another 14 across a 4h45 ride, none
 *     acknowledged — the deficit only grows, so it re-fires until the ride ends.
 *     The cooldown is therefore multiplied by 1 / 2 / 4 as unacknowledged fires
 *     accumulate, capped at ×4 so the reminder never goes fully silent
 *     (dehydration matters most on the long rides where this triggers). Any log
 *     resets the caller's counter, so the next reminder is back at the base
 *     interval.
 *
 * **Coincidence resolution** (deficit + time tick on the same call) is NOT
 * handled here — it's the tracker's responsibility because resolving it
 * involves stamping `lastTimeAlertFireMs` to consume the time tick, which is
 * a mutation outside this object's pure-function contract. See
 * `CarbsTracker.tick` / `HydrationTracker.tick` for the deficit-wins logic.
 */
internal object FuelingAlertScheduler {

    /**
     * Returns the timestamp of the time-alert grid tick that's due to fire
     * right now, or `0L` when no tick is due (alert disabled, before the first
     * grid point, already fired this tick, or filtered by the initial-delay
     * window).
     *
     * **Pure**: every input is a parameter; the function mutates nothing and
     * has no side effects.
     */
    fun currentDueTimeTick(
        enabled: Boolean,
        intervalMs: Long,
        sessionStartMs: Long,
        lastTimeAlertFireMs: Long,
        initialDelayMs: Long,
        cumLogged: Int,
        now: Long,
    ): Long {
        if (!enabled) return 0L
        if (intervalMs <= 0L) return 0L
        val sinceStart = now - sessionStartMs
        if (sinceStart < intervalMs) return 0L  // before the first grid point
        val ticksElapsed = sinceStart / intervalMs
        val currentTickAt = sessionStartMs + ticksElapsed * intervalMs
        if (lastTimeAlertFireMs >= currentTickAt) return 0L  // already fired this tick
        // Initial-delay FILTER: ticks earlier than `sessionStartMs + initialDelayMs`
        // are silently dropped. The grid stays anchored — interval=20 + initialDelay=30
        // fires at 40 / 60 / 80, not 30 / 50 / 70.
        val effectiveInitialDelayMs = if (cumLogged == 0) initialDelayMs else 0L
        if (currentTickAt < sessionStartMs + effectiveInitialDelayMs) return 0L
        return currentTickAt
    }

    /**
     * Multiplier ladder for [shouldFireDeficit]'s cooldown, indexed by
     * unacknowledged fires: the first two reminders keep the rider's configured
     * interval, then it doubles, then caps at ×4. Capped (not unbounded, not a
     * hard stop) so a rider who never logs still gets a prompt every 4 intervals.
     */
    private const val MAX_BACKOFF_SHIFT = 2

    /**
     * Returns `true` when the deficit alert should fire on the current tick.
     *
     * Conditions:
     *  - Alert enabled.
     *  - Cumulative deficit ≥ threshold.
     *  - Either (a) no previous deficit fire AND outside the initial-delay
     *    grace, or (b) the backed-off cooldown has elapsed since the last fire.
     *
     * The initial-delay grace only applies to the FIRST deficit fire AND only
     * when the rider hasn't logged anything yet (a logged item is implicit
     * acknowledgement that the rider is engaged with fueling).
     *
     * [unackedFires] is how many deficit alerts have fired since the rider last
     * logged anything. 0 or 1 → the configured [reminderIntervalMs]; 2 → ×2;
     * 3 or more → ×4 (the cap). The caller owns the counter and resets it on any
     * log — see `HydrationTracker.evaluateDeficitAlert`.
     */
    fun shouldFireDeficit(
        enabled: Boolean,
        deficit: Int,
        deficitThreshold: Int,
        lastDeficitAlertFireMs: Long,
        reminderIntervalMs: Long,
        initialDelayMs: Long,
        cumLogged: Int,
        sessionStartMs: Long,
        now: Long,
        unackedFires: Int = 0,
    ): Boolean {
        if (!enabled) return false
        // Initial-delay grace: only blocks the first fire AND only while no log
        // has been recorded yet.
        if (lastDeficitAlertFireMs == 0L && cumLogged == 0 && initialDelayMs > 0L) {
            if (now - sessionStartMs < initialDelayMs) return false
        }
        if (deficit < deficitThreshold) return false
        val backoffShift = (unackedFires - 1).coerceIn(0, MAX_BACKOFF_SHIFT)
        if (now - lastDeficitAlertFireMs < (reminderIntervalMs shl backoffShift)) return false
        return true
    }
}
