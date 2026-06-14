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

    @Test fun `stale speed escalates even if value is high (no FN)`() {
        val v = mv(); v.arm(0)
        assertEquals(MovingVigilance.Outcome.ESCALATE, v.onTick(800, 20.0, false))
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
