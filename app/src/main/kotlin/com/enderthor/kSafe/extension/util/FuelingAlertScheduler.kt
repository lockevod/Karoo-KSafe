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
     * Returns `true` when the deficit alert should fire on the current tick.
     *
     * Conditions:
     *  - Alert enabled.
     *  - Cumulative deficit ≥ threshold.
     *  - Either (a) no previous deficit fire AND outside the initial-delay
     *    grace, or (b) `reminderIntervalMs` has elapsed since the last fire.
     *
     * The initial-delay grace only applies to the FIRST deficit fire AND only
     * when the rider hasn't logged anything yet (a logged item is implicit
     * acknowledgement that the rider is engaged with fueling).
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
    ): Boolean {
        if (!enabled) return false
        // Initial-delay grace: only blocks the first fire AND only while no log
        // has been recorded yet.
        if (lastDeficitAlertFireMs == 0L && cumLogged == 0 && initialDelayMs > 0L) {
            if (now - sessionStartMs < initialDelayMs) return false
        }
        if (deficit < deficitThreshold) return false
        if (now - lastDeficitAlertFireMs < reminderIntervalMs) return false
        return true
    }
}
