package com.enderthor.kSafe.extension.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [FuelingAlertScheduler] — pinned the contracts that were
 * load-bearing for v17 (time-grid alignment, initial-delay filter,
 * configurable deficit reminder cooldown) and v18 (coincidence resolution
 * via tick consumption).
 *
 * These tests caught the FuelingAlertScheduler refactor and would catch
 * future regressions on the trackers' alert-scheduling shape without
 * requiring a Robolectric harness.
 */
class FuelingAlertSchedulerTest {

    // ── currentDueTimeTick ────────────────────────────────────────────────────

    @Test
    fun `time alert fires at sessionStart + N × interval regardless of logs`() {
        // interval=20 min, sessionStartMs=0, no initial delay, no logs.
        // Expected ticks at 20, 40, 60, 80 …
        val interval = 20L * 60_000L
        var lastFire = 0L
        for (n in 1..5) {
            val now = n * interval
            val tick = FuelingAlertScheduler.currentDueTimeTick(
                enabled = true,
                intervalMs = interval,
                sessionStartMs = 0L,
                lastTimeAlertFireMs = lastFire,
                initialDelayMs = 0L,
                cumLogged = 0,
                now = now,
            )
            assertEquals("tick $n should fire at $now ms", now, tick)
            lastFire = now
        }
    }

    @Test
    fun `time alert does NOT fire before the first grid point`() {
        val interval = 20L * 60_000L
        // 0, 5, 10, 15, 19 min — all below the first grid point at 20 min.
        for (mins in listOf(0L, 5L, 10L, 15L, 19L)) {
            val tick = FuelingAlertScheduler.currentDueTimeTick(
                enabled = true, intervalMs = interval, sessionStartMs = 0L,
                lastTimeAlertFireMs = 0L, initialDelayMs = 0L, cumLogged = 0,
                now = mins * 60_000L,
            )
            assertEquals("no tick due at $mins min", 0L, tick)
        }
    }

    @Test
    fun `time alert FIRES at the first tick of session when no initial delay`() {
        val interval = 20L * 60_000L
        val tick = FuelingAlertScheduler.currentDueTimeTick(
            enabled = true, intervalMs = interval, sessionStartMs = 0L,
            lastTimeAlertFireMs = 0L, initialDelayMs = 0L, cumLogged = 0,
            now = interval,  // exactly 20 min
        )
        assertEquals(interval, tick)
    }

    @Test
    fun `time alert does not re-fire same tick within the interval`() {
        val interval = 20L * 60_000L
        // Already fired at 20 min. Subsequent calls at 25 / 30 / 35 / 39 min
        // must NOT fire because the next tick is at 40.
        for (mins in listOf(20L, 25L, 30L, 35L, 39L)) {
            val tick = FuelingAlertScheduler.currentDueTimeTick(
                enabled = true, intervalMs = interval, sessionStartMs = 0L,
                lastTimeAlertFireMs = 20L * 60_000L,
                initialDelayMs = 0L, cumLogged = 0,
                now = mins * 60_000L,
            )
            assertEquals("no re-fire at $mins (already fired at 20)", 0L, tick)
        }
    }

    @Test
    fun `rider logging does NOT reset the grid`() {
        // v17 contract: rider logged at minute 22 (mid-interval). The next
        // alert must still fire at minute 40 (next grid point), NOT minute 42.
        val interval = 20L * 60_000L
        // Already fired at 20. cumLogged > 0 (rider logged after).
        val tick40 = FuelingAlertScheduler.currentDueTimeTick(
            enabled = true, intervalMs = interval, sessionStartMs = 0L,
            lastTimeAlertFireMs = 20L * 60_000L,
            initialDelayMs = 0L,  // doesn't matter once cumLogged > 0
            cumLogged = 25,  // rider logged something
            now = 40L * 60_000L,
        )
        assertEquals("rider log must not shift the grid", 40L * 60_000L, tick40)
    }

    // ── Initial-delay FILTER (v17 user-facing contract) ──────────────────────

    @Test
    fun `initial delay FILTERS early ticks - interval 20 + delay 30 fires at 40`() {
        val interval = 20L * 60_000L
        val initialDelay = 30L * 60_000L
        // Tick at minute 20: filtered (20 < 30).
        val tick20 = FuelingAlertScheduler.currentDueTimeTick(
            enabled = true, intervalMs = interval, sessionStartMs = 0L,
            lastTimeAlertFireMs = 0L, initialDelayMs = initialDelay,
            cumLogged = 0, now = 20L * 60_000L,
        )
        assertEquals("tick at 20 min filtered by 30 min initial delay", 0L, tick20)
        // Tick at minute 40: passes filter (40 >= 30). First fire of the session.
        val tick40 = FuelingAlertScheduler.currentDueTimeTick(
            enabled = true, intervalMs = interval, sessionStartMs = 0L,
            lastTimeAlertFireMs = 0L, initialDelayMs = initialDelay,
            cumLogged = 0, now = 40L * 60_000L,
        )
        assertEquals("tick at 40 min passes filter — first fire", 40L * 60_000L, tick40)
    }

    @Test
    fun `initial delay does NOT shift the grid forward by the delay`() {
        // PRE-v17 BUG: initial delay 30 + interval 20 used to fire at 30, then
        // 30+20=50, then 70. Grid was shifted. v17 contract: fires at 40, 60, 80.
        val interval = 20L * 60_000L
        val initialDelay = 30L * 60_000L
        // After firing at 40, the next due tick should be 60, NOT 60 (40+20=60
        // is correct grid-aligned). Test that a query at 50 doesn't fire.
        val tick50 = FuelingAlertScheduler.currentDueTimeTick(
            enabled = true, intervalMs = interval, sessionStartMs = 0L,
            lastTimeAlertFireMs = 40L * 60_000L,
            initialDelayMs = initialDelay, cumLogged = 0,
            now = 50L * 60_000L,
        )
        assertEquals("tick at 50 not on grid (grid points at 40, 60, 80)", 0L, tick50)
        val tick60 = FuelingAlertScheduler.currentDueTimeTick(
            enabled = true, intervalMs = interval, sessionStartMs = 0L,
            lastTimeAlertFireMs = 40L * 60_000L,
            initialDelayMs = initialDelay, cumLogged = 0,
            now = 60L * 60_000L,
        )
        assertEquals("tick at 60 fires (grid stays anchored)", 60L * 60_000L, tick60)
    }

    @Test
    fun `initial delay releases as soon as rider logs anything`() {
        // Rider logged at minute 5 (within the 30 min initial delay). The
        // first tick at minute 20 must fire — initial delay no longer applies
        // because the rider is engaged.
        val interval = 20L * 60_000L
        val tick = FuelingAlertScheduler.currentDueTimeTick(
            enabled = true, intervalMs = interval, sessionStartMs = 0L,
            lastTimeAlertFireMs = 0L,
            initialDelayMs = 30L * 60_000L,
            cumLogged = 25,  // rider logged
            now = 20L * 60_000L,
        )
        assertEquals(20L * 60_000L, tick)
    }

    @Test
    fun `disabled time alert never produces a tick`() {
        val tick = FuelingAlertScheduler.currentDueTimeTick(
            enabled = false, intervalMs = 20L * 60_000L, sessionStartMs = 0L,
            lastTimeAlertFireMs = 0L, initialDelayMs = 0L, cumLogged = 0,
            now = 1_000_000L,
        )
        assertEquals(0L, tick)
    }

    @Test
    fun `zero or negative interval produces no ticks (defensive guard)`() {
        for (interval in listOf(0L, -1L)) {
            val tick = FuelingAlertScheduler.currentDueTimeTick(
                enabled = true, intervalMs = interval, sessionStartMs = 0L,
                lastTimeAlertFireMs = 0L, initialDelayMs = 0L, cumLogged = 0,
                now = 1_000_000L,
            )
            assertEquals("guard interval=$interval", 0L, tick)
        }
    }

    // ── Coincidence resolution: time tick consumption (caller responsibility) ─

    @Test
    fun `consumed time tick prevents same-tick re-fire on subsequent call`() {
        // The trackers stamp `lastTimeAlertFireMs = now` when a coincident
        // deficit alert wins. Verify that the consumption correctly prevents
        // the same tick from re-firing on the very next call, but leaves the
        // next grid point available.
        val interval = 20L * 60_000L
        val now = 20L * 60_000L  // tick at 20 min
        val tick1 = FuelingAlertScheduler.currentDueTimeTick(
            enabled = true, intervalMs = interval, sessionStartMs = 0L,
            lastTimeAlertFireMs = 0L, initialDelayMs = 0L, cumLogged = 0, now = now,
        )
        assertEquals("tick due at 20", now, tick1)
        // Tracker stamps now (coincidence consumption).
        val tickAfterConsume = FuelingAlertScheduler.currentDueTimeTick(
            enabled = true, intervalMs = interval, sessionStartMs = 0L,
            lastTimeAlertFireMs = now, initialDelayMs = 0L, cumLogged = 0,
            now = now + 15_000L,  // 15 s later (next tracker tick)
        )
        assertEquals("consumed tick — no re-fire same window", 0L, tickAfterConsume)
        // Next grid point at 40 min — must fire normally.
        val tickAt40 = FuelingAlertScheduler.currentDueTimeTick(
            enabled = true, intervalMs = interval, sessionStartMs = 0L,
            lastTimeAlertFireMs = now,
            initialDelayMs = 0L, cumLogged = 0,
            now = 40L * 60_000L,
        )
        assertEquals("next grid point at 40 fires", 40L * 60_000L, tickAt40)
    }

    // ── shouldFireDeficit ────────────────────────────────────────────────────

    @Test
    fun `deficit alert fires when over threshold and cooldown elapsed`() {
        val fire = FuelingAlertScheduler.shouldFireDeficit(
            enabled = true,
            deficit = 30, deficitThreshold = 25,
            lastDeficitAlertFireMs = 0L,
            reminderIntervalMs = 10L * 60_000L,
            initialDelayMs = 0L, cumLogged = 0,
            sessionStartMs = 0L, now = 60L * 60_000L,
        )
        assertTrue(fire)
    }

    @Test
    fun `deficit alert does NOT fire when below threshold`() {
        val fire = FuelingAlertScheduler.shouldFireDeficit(
            enabled = true,
            deficit = 10, deficitThreshold = 25,
            lastDeficitAlertFireMs = 0L,
            reminderIntervalMs = 10L * 60_000L,
            initialDelayMs = 0L, cumLogged = 0,
            sessionStartMs = 0L, now = 60L * 60_000L,
        )
        assertFalse(fire)
    }

    @Test
    fun `deficit alert reminder cooldown respected (v17 configurable cadence)`() {
        // Last fire at minute 30, reminder = 10 min, threshold crossed.
        // Should fire at minute 40 but NOT at 35.
        val reminderInterval = 10L * 60_000L
        val lastFire = 30L * 60_000L
        val tick35 = FuelingAlertScheduler.shouldFireDeficit(
            enabled = true,
            deficit = 30, deficitThreshold = 25,
            lastDeficitAlertFireMs = lastFire,
            reminderIntervalMs = reminderInterval,
            initialDelayMs = 0L, cumLogged = 50,
            sessionStartMs = 0L, now = 35L * 60_000L,
        )
        assertFalse("only 5 min since last fire", tick35)
        val tick40 = FuelingAlertScheduler.shouldFireDeficit(
            enabled = true,
            deficit = 30, deficitThreshold = 25,
            lastDeficitAlertFireMs = lastFire,
            reminderIntervalMs = reminderInterval,
            initialDelayMs = 0L, cumLogged = 50,
            sessionStartMs = 0L, now = 40L * 60_000L,
        )
        assertTrue("10 min elapsed — reminder due", tick40)
    }

    @Test
    fun `deficit initial-delay grace only applies to first fire and when no logs`() {
        // Initial delay 30 min, no log, no previous deficit fire — must NOT
        // fire at minute 20 even if deficit is huge.
        val tick20 = FuelingAlertScheduler.shouldFireDeficit(
            enabled = true,
            deficit = 50, deficitThreshold = 25,
            lastDeficitAlertFireMs = 0L,
            reminderIntervalMs = 10L * 60_000L,
            initialDelayMs = 30L * 60_000L,
            cumLogged = 0,
            sessionStartMs = 0L, now = 20L * 60_000L,
        )
        assertFalse("initial delay holds first fire", tick20)
        // Same conditions but rider has logged — grace lifts.
        val tick20Logged = FuelingAlertScheduler.shouldFireDeficit(
            enabled = true,
            deficit = 50, deficitThreshold = 25,
            lastDeficitAlertFireMs = 0L,
            reminderIntervalMs = 10L * 60_000L,
            initialDelayMs = 30L * 60_000L,
            cumLogged = 25,  // rider logged → grace lifts
            sessionStartMs = 0L, now = 20L * 60_000L,
        )
        assertTrue("rider engaged — initial delay no longer applies", tick20Logged)
    }

    @Test
    fun `deficit alert disabled never fires`() {
        val fire = FuelingAlertScheduler.shouldFireDeficit(
            enabled = false,
            deficit = 100, deficitThreshold = 1,
            lastDeficitAlertFireMs = 0L,
            reminderIntervalMs = 1L,
            initialDelayMs = 0L, cumLogged = 0,
            sessionStartMs = 0L, now = 1_000_000L,
        )
        assertFalse(fire)
    }

    // ── unacknowledged back-off (2026-07-22 field sweep) ─────────────────────

    /** Helper: 10-min base interval, threshold crossed, no initial delay. */
    private fun deficitDueAfter(minutesSinceLastFire: Long, unacked: Int): Boolean =
        FuelingAlertScheduler.shouldFireDeficit(
            enabled = true,
            deficit = 500, deficitThreshold = 300,
            lastDeficitAlertFireMs = 30L * 60_000L,
            reminderIntervalMs = 10L * 60_000L,
            initialDelayMs = 0L, cumLogged = 0,
            sessionStartMs = 0L,
            now = (30L + minutesSinceLastFire) * 60_000L,
            unackedFires = unacked,
        )

    @Test
    fun `first two unacknowledged reminders keep the configured interval`() {
        assertFalse("9 min < base interval", deficitDueAfter(9, unacked = 0))
        assertTrue("base interval, nothing ignored yet", deficitDueAfter(10, unacked = 0))
        assertTrue("one ignored — still base cadence", deficitDueAfter(10, unacked = 1))
    }

    @Test
    fun `back-off doubles then caps at four times the interval`() {
        assertFalse("2 ignored → 20 min, not due at 19", deficitDueAfter(19, unacked = 2))
        assertTrue("2 ignored → due at 20", deficitDueAfter(20, unacked = 2))
        assertFalse("3 ignored → 40 min, not due at 39", deficitDueAfter(39, unacked = 3))
        assertTrue("3 ignored → due at 40", deficitDueAfter(40, unacked = 3))
    }

    @Test
    fun `back-off never goes silent — cap holds at four intervals however many are ignored`() {
        // The 2026-07-22 worst case: 13 unacknowledged hydration prompts in an hour.
        // However deep the counter goes, the reminder must still arrive at ×4.
        assertFalse("still gated below the cap", deficitDueAfter(39, unacked = 13))
        assertTrue("capped at ×4 — reminder still fires", deficitDueAfter(40, unacked = 13))
        assertTrue("cap holds at absurd counts", deficitDueAfter(40, unacked = 999))
    }

    @Test
    fun `a log resets the ladder — caller passes zero and base cadence returns`() {
        // The trackers zero their counter when `lastRealLogMs` moves; from the
        // scheduler's side that is simply unackedFires = 0 again.
        assertFalse("backed off at ×4", deficitDueAfter(20, unacked = 5))
        assertTrue("after a log, 20 min is well past the base interval", deficitDueAfter(20, unacked = 0))
    }
}
