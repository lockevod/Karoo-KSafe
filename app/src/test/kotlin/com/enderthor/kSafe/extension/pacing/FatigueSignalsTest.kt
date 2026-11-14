package com.enderthor.kSafe.extension.pacing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FatigueSignalsTest {
    private fun ride(f: FatigueSignals, p: Int, hr: Int, seconds: Int, start: Long): Long {
        var t = start; repeat(seconds) { t += 1000; f.onSample(p, hr, t, moving = true) }; return t
    }

    @Test fun `drift unknown until baseline and a second window`() {
        val f = FatigueSignals(250, 70.0)
        val t = ride(f, 175, 140, 15 * 60, 0)                  // 0.70 FTP
        assertNull(f.driftPct(t))
    }

    @Test fun `drift measured on matched intensity`() {
        val f = FatigueSignals(250, 70.0)
        // +1 sample: the first sample only anchors time (dt = 0), so 1201 samples = 1200 s of moving time
        var t = ride(f, 175, 140, 20 * 60 + 1, 0)              // baseline window closes after 15 min
        t = ride(f, 175, 154, 5 * 60, t)                       // +10 % HR at same power
        assertEquals(9.09, f.driftPct(t)!!, 0.2)
    }

    @Test fun `windows outside the band do not count and drift goes stale`() {
        val f = FatigueSignals(250, 70.0)
        var t = ride(f, 175, 140, 25 * 60, 0)
        t = ride(f, 280, 170, 10 * 60, t)                      // 1.12 FTP: not matched
        assertNull(f.driftPct(t))
    }

    @Test fun `no FTP never yields drift`() {
        val f = FatigueSignals(0, 70.0); val t = ride(f, 175, 140, 40 * 60, 0); assertNull(f.driftPct(t))
    }

    @Test fun `window with half its HR missing is discarded`() {
        val f = FatigueSignals(250, 70.0)
        var t = ride(f, 175, 140, 10 * 60 + 1, 0)              // 600 s moving, no baseline yet
        repeat(300) { i -> t += 1000; f.onSample(175, if (i % 2 == 0) 140 else null, t, moving = true) }
        assertNull(f.driftPct(t))                              // HR-incomplete window: no baseline, no drift
        t = ride(f, 175, 140, 5 * 60, t)                       // complete window becomes the baseline only
        assertNull(f.driftPct(t))
    }

    @Test fun `zero weight accumulates no kJ per kg`() {
        val f = FatigueSignals(250, 0.0); ride(f, 200, 140, 600, 0)
        assertEquals(0.0, f.kjPerKg, 0.0); assertEquals(0.0, f.meanMovingPowerW, 0.0)
    }

    @Test fun `kJ per kg accumulates moving work`() {
        val f = FatigueSignals(250, 70.0); ride(f, 200, 140, 3600, 0)
        assertEquals(720.0 / 70.0, f.kjPerKg, 0.05)
    }
}
