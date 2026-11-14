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
        powerCoverage = 1.0,
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
        assertEquals(PacingState.KEEP, PacingAdvisor.decide(ok.copy(powerCoverage = null), PacingState.KEEP).state)
        assertEquals(PacingState.KEEP, PacingAdvisor.decide(ok.copy(powerCoverage = 0.85), PacingState.KEEP).state)
    }

    @Test fun `no power means no advice`() {
        val d = PacingAdvisor.decide(base.copy(hasPower = false, projectedKjKgFinish = 40.0), PacingState.KEEP)
        assertEquals(PacingState.KEEP, d.state); assertEquals("no_power", d.reason)
        assertFalse(d.recoverW); assertFalse(d.alertWindowPacing)
    }

    @Test fun `stopped keeps previous state and opens no window`() {
        val d = PacingAdvisor.decide(base.copy(moving = false, secondsToNextBlock = 200.0, metresToNextBlock = 1500.0), PacingState.CONSERVE)
        assertEquals(PacingState.CONSERVE, d.state)
        assertFalse(d.alertWindowPacing); assertFalse(d.alertWindowW); assertFalse(d.alertWindowFuel)
    }

    @Test fun `stopped drops high margin to keep`() =
        assertEquals(PacingState.KEEP, PacingAdvisor.decide(base.copy(moving = false), PacingState.HIGH_MARGIN).state)

    @Test fun `recover W when projected W at block start is low`() {
        val d = PacingAdvisor.decide(base.copy(secondsToNextBlock = 250.0, metresToNextBlock = 1500.0, projectedWAtNextBlockPct = 0.3), PacingState.KEEP)
        assertTrue(d.recoverW); assertTrue(d.alertWindowW)
    }

    @Test fun `recover W has hysteresis between 0_40 and 0_50`() {
        fun rw(proj: Double, prev: Boolean) =
            PacingAdvisor.decide(base.copy(projectedWAtNextBlockPct = proj), PacingState.KEEP, prevRecoverW = prev).recoverW
        assertFalse(rw(0.45, prev = false))                    // not entered above 0.40
        assertTrue(rw(0.39, prev = false))                     // enters below 0.40
        assertTrue(rw(0.45, prev = true))                      // held between 0.40 and 0.50
        assertTrue(rw(0.50, prev = true))
        assertFalse(rw(0.51, prev = true))                     // leaves only above 0.50
    }

    private val hrOnly = base.copy(hasPower = false, hrWork = true, powerCoverage = null)

    @Test fun `heart-rate work enters CONSERVE only past knee x 1_10 and leaves below knee x 0_9`() {
        assertEquals(PacingState.KEEP, PacingAdvisor.decide(hrOnly.copy(projectedKjKgFinish = 32.0), PacingState.KEEP).state)
        val d = PacingAdvisor.decide(hrOnly.copy(projectedKjKgFinish = 34.0), PacingState.KEEP)
        assertEquals(PacingState.CONSERVE, d.state); assertEquals("hr_proj_over_knee", d.reason)
        assertEquals(PacingState.CONSERVE, PacingAdvisor.decide(hrOnly.copy(projectedKjKgFinish = 28.0), PacingState.CONSERVE).state)
        assertEquals(PacingState.KEEP, PacingAdvisor.decide(hrOnly.copy(projectedKjKgFinish = 26.0), PacingState.CONSERVE).state)
    }

    @Test fun `heart-rate work never gives HIGH_MARGIN, W' or drift advice`() {
        val margin = hrOnly.copy(remainingMovingS = 1800.0, blocksAhead = 1, projectedKjKgFinish = 18.0,
            secondsToNextBlock = 250.0, metresToNextBlock = 1500.0, projectedWAtNextBlockPct = 0.3)
        val d = PacingAdvisor.decide(margin, PacingState.KEEP)
        assertEquals(PacingState.KEEP, d.state); assertFalse(d.recoverW); assertFalse(d.alertWindowW)
        assertTrue("the pacing window still opens for an HR-sourced CONSERVE", d.alertWindowPacing)
        assertEquals(PacingState.KEEP, PacingAdvisor.decide(hrOnly.copy(driftPct = 15.0, driftStableMs = null), PacingState.KEEP).state)
    }

    @Test fun `no power and no heart-rate work is no_power`() =
        assertEquals("no_power", PacingAdvisor.decide(hrOnly.copy(hrWork = false, projectedKjKgFinish = 40.0), PacingState.KEEP).reason)

    @Test fun `intensity cue needs Z4 heart rate going into a climb with most of the ride left`() {
        fun cue(moving: Boolean = true, trusted: Boolean = true, toS: Double? = 250.0, toM: Double? = 2000.0, rem: Double? = 3600.0,
                elapsed: Double = 1800.0, fresh: Boolean = true, hr5: Double? = 160.0, z4: Int? = 153) =
            PacingAdvisor.intensityCue(moving, trusted, toS, toM, rem, elapsed, fresh, hr5, z4)
        assertTrue(cue())
        assertTrue(cue(hr5 = 153.0))                             // at the Z4 lower bound
        assertFalse(cue(hr5 = 152.9))
        assertFalse(cue(z4 = null)); assertFalse(cue(hr5 = null)); assertFalse(cue(fresh = false))
        assertFalse(cue(moving = false)); assertFalse(cue(trusted = false))
        assertFalse(cue(toS = 400.0))                            // outside the 5-min pacing lead window
        assertFalse(cue(rem = 1200.0, elapsed = 1800.0))         // 40 % left: not more than 0.4
        assertFalse(cue(rem = null)); assertFalse(cue(rem = 0.0, elapsed = 0.0))
    }

    @Test fun `lead window rules`() {
        assertTrue(PacingAdvisor.inLeadWindow(290.0, 2000.0, 300.0))
        assertFalse(PacingAdvisor.inLeadWindow(400.0, 2000.0, 300.0))
        assertTrue(PacingAdvisor.inLeadWindow(900.0, 280.0, 300.0))      // closer than 300 m → open
        assertFalse(PacingAdvisor.inLeadWindow(10.0, 80.0, 300.0))        // inside 100 m → too late
        assertFalse(PacingAdvisor.inLeadWindow(200.0, 12_000.0, 300.0))   // beyond 10 km clamp
        assertFalse(PacingAdvisor.inLeadWindow(100.0, Double.NaN, 300.0))
    }
}
