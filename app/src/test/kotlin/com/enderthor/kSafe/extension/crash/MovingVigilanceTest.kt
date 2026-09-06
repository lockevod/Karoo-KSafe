package com.enderthor.kSafe.extension.crash

import org.junit.Assert.assertEquals
import org.junit.Test

class MovingVigilanceTest {
    private fun mv() = MovingVigilance(Thresholds(movingVigilanceWindowMs = 4_000L, movingVigilanceSpeedKmh = 8.0))

    @Test fun `not armed returns pending`() {
        assertEquals(MovingVigilance.Outcome.PENDING, mv().onTick(100, 20.0, true))
    }

    @Test fun `sustained fresh speed for the full window clears`() {
        val v = mv(); v.arm(0)
        assertEquals(MovingVigilance.Outcome.PENDING, v.onTick(1_000, 18.0, true))
        assertEquals(MovingVigilance.Outcome.PENDING, v.onTick(3_999, 16.0, true))
        assertEquals(MovingVigilance.Outcome.CLEAR, v.onTick(4_000, 15.0, true))
    }

    @Test fun `speed below floor at any sample escalates`() {
        val v = mv(); v.arm(0)
        assertEquals(MovingVigilance.Outcome.PENDING, v.onTick(500, 18.0, true))
        assertEquals(MovingVigilance.Outcome.ESCALATE, v.onTick(1_200, 3.0, true))
    }

    @Test fun `stale speed mid-window defers the verdict instead of escalating`() {
        val v = mv(); v.arm(0)
        assertEquals(MovingVigilance.Outcome.PENDING, v.onTick(800, 20.0, false))
    }

    @Test fun `speed still stale at window end escalates (no FN)`() {
        val v = mv(); v.arm(0)
        assertEquals(MovingVigilance.Outcome.PENDING, v.onTick(800, 20.0, false))
        assertEquals(MovingVigilance.Outcome.ESCALATE, v.onTick(4_000, 20.0, false))
    }

    // The 2026-09-06 field batch: 6 of 12 real escalates were armed on a momentarily
    // stale speed that was fresh again by window end — deferring the staleness verdict
    // is what silences them.
    @Test fun `speed that regains freshness by window end clears`() {
        val v = mv(); v.arm(0)
        assertEquals(MovingVigilance.Outcome.PENDING, v.onTick(800, 20.0, false))
        assertEquals(MovingVigilance.Outcome.PENDING, v.onTick(2_000, 19.0, false))
        assertEquals(MovingVigilance.Outcome.CLEAR, v.onTick(4_000, 18.0, true))
    }

    @Test fun `floor breach still escalates immediately while speed is stale`() {
        val v = mv(); v.arm(0)
        assertEquals(MovingVigilance.Outcome.ESCALATE, v.onTick(900, 2.0, false))
    }

    // Production passes `clock.monotonicMs()`, which cannot step, so this branch is unreachable
    // there — it is defence in depth for a caller that supplies a non-monotonic clock. The test
    // pins the fail-safe DIRECTION: a negative elapsed must resolve as ESCALATE, never strand an
    // armed window that can only escalate. (The wall-clock dependency that remains is in the
    // freshness predicate, not here — see isSpeedFreshForVigilance's negative-age guard.)
    @Test fun `a backward clock step resolves the window as escalate, never strands it`() {
        val v = mv(); v.arm(10_000)
        assertEquals(MovingVigilance.Outcome.ESCALATE, v.onTick(2_000, 20.0, true))
    }

    @Test fun `outcome resets arming so a later tick is pending`() {
        val v = mv(); v.arm(0)
        v.onTick(800, 2.0, true) // escalate
        assertEquals(MovingVigilance.Outcome.PENDING, v.onTick(900, 20.0, true))
    }

    @Test fun `speed exactly at the floor does not escalate (strict less-than)`() {
        val v = mv(); v.arm(0)
        assertEquals(MovingVigilance.Outcome.PENDING, v.onTick(1_000, 8.0, true))
        assertEquals(MovingVigilance.Outcome.CLEAR, v.onTick(4_000, 8.0, true))
    }
}
