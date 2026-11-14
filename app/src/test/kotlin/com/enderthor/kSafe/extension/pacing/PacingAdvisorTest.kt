package com.enderthor.kSafe.extension.pacing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacingAdvisorTest {
    private val base = PacingInput(
        nowMs = 10_000_000, moving = true, hasPower = true, positionTrusted = true, climbsKnown = true,
        remainingMovingS = 7200.0, elapsedMovingS = 7200.0, blocksAhead = 2, secondsToNextBlock = 1800.0,
        metresToNextBlock = 12_000.0, nextBlockId = 1, kjPerKg = 15.0, projectedKjKgFinish = 25.0,
        projectedKjKgNextTop = 18.0, kneeKjKg = 30.0, driftPct = 2.0, driftStableMs = 20 * 60_000L,
        wTrusted = true, wPct = 0.9, projectedWAtNextBlockPct = 0.9, msSinceWellnessOrMedical = null,
    )

    @Test fun `keep when nothing is wrong`() = assertEquals(PacingState.KEEP, PacingAdvisor.decide(base, PacingState.KEEP).state)

    @Test fun `conserve when projected finish exceeds the knee`() =
        assertEquals(PacingState.CONSERVE, PacingAdvisor.decide(base.copy(projectedKjKgFinish = 33.0), PacingState.KEEP).state)

    @Test fun `conserve on high drift with much route left`() =
        assertEquals(PacingState.CONSERVE, PacingAdvisor.decide(base.copy(driftPct = 11.0, driftStableMs = null), PacingState.KEEP).state)

    @Test fun `value hysteresis keeps conserve until clearly below`() {
        assertEquals(PacingState.CONSERVE, PacingAdvisor.decide(base.copy(projectedKjKgFinish = 28.0), PacingState.CONSERVE).state)
        assertEquals(PacingState.KEEP, PacingAdvisor.decide(base.copy(projectedKjKgFinish = 26.0), PacingState.CONSERVE).state)
    }

    @Test fun `high margin needs every guard`() {
        val ok = base.copy(remainingMovingS = 1800.0, blocksAhead = 1, projectedKjKgFinish = 18.0)
        assertEquals(PacingState.HIGH_MARGIN, PacingAdvisor.decide(ok, PacingState.KEEP).state)
        assertEquals(PacingState.KEEP, PacingAdvisor.decide(ok.copy(driftPct = null), PacingState.KEEP).state)
        assertEquals(PacingState.KEEP, PacingAdvisor.decide(ok.copy(wTrusted = false), PacingState.KEEP).state)
        assertEquals(PacingState.KEEP, PacingAdvisor.decide(ok.copy(climbsKnown = false), PacingState.KEEP).state)
        assertEquals(PacingState.KEEP, PacingAdvisor.decide(ok.copy(msSinceWellnessOrMedical = 10 * 60_000L), PacingState.KEEP).state)
        assertEquals(PacingState.KEEP, PacingAdvisor.decide(ok.copy(driftStableMs = 5 * 60_000L), PacingState.KEEP).state)
    }

    @Test fun `no power means no advice`() {
        val d = PacingAdvisor.decide(base.copy(hasPower = false, projectedKjKgFinish = 40.0), PacingState.KEEP)
        assertEquals(PacingState.KEEP, d.state); assertEquals("no_power", d.reason)
    }

    @Test fun `stopped keeps previous state and opens no window`() {
        val d = PacingAdvisor.decide(base.copy(moving = false, secondsToNextBlock = 200.0), PacingState.CONSERVE)
        assertEquals(PacingState.CONSERVE, d.state); assertFalse(d.alertWindowPacing)
    }

    @Test fun `recover W when projected W at block start is low`() {
        val d = PacingAdvisor.decide(base.copy(secondsToNextBlock = 250.0, metresToNextBlock = 1500.0, projectedWAtNextBlockPct = 0.3), PacingState.KEEP)
        assertTrue(d.recoverW); assertTrue(d.alertWindowW)
    }

    @Test fun `lead window rules`() {
        assertTrue(PacingAdvisor.inLeadWindow(290.0, 2000.0, 300.0))
        assertFalse(PacingAdvisor.inLeadWindow(400.0, 2000.0, 300.0))
        assertTrue(PacingAdvisor.inLeadWindow(900.0, 280.0, 300.0))      // closer than 300 m → open
        assertFalse(PacingAdvisor.inLeadWindow(10.0, 80.0, 300.0))        // inside 100 m → too late
        assertFalse(PacingAdvisor.inLeadWindow(200.0, 12_000.0, 300.0))   // beyond 10 km clamp
    }
}
