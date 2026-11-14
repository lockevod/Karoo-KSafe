package com.enderthor.kSafe.extension.pacing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WPrimeBalanceTest {
    private fun feed(w: WPrimeBalance, power: Int, seconds: Int, startMs: Long = 0L): Long {
        var t = startMs; repeat(seconds) { t += 1000; w.onPower(power, t) }; return t
    }

    @Test fun `above CP depletes linearly`() {
        val w = WPrimeBalance(250, 15_000.0); w.onPower(250, 0)
        feed(w, 350, 60)                                   // 100 W over CP × 60 s = 6 kJ
        assertEquals(9_000.0, w.wJ, 1.0); assertEquals(0.6, w.pct, 1e-3)
    }

    @Test fun `recovers toward full below CP`() {
        val w = WPrimeBalance(250, 15_000.0); w.onPower(250, 0)
        val t = feed(w, 400, 60)                           // 9 kJ spent → 6 kJ
        feed(w, 100, 600, t)
        assertTrue(w.wJ > 13_000.0); assertTrue(w.wJ < 15_000.0)
    }

    @Test fun `dropout recovers as zero watts, not last value`() {
        val w = WPrimeBalance(250, 15_000.0); w.onPower(400, 0); w.onPower(400, 1000)
        val before = w.wJ
        w.onPower(400, 61_000)                             // 60 s gap, then 400 W: a last-value bug would deplete
        assertTrue(w.wJ > before)
    }

    @Test fun `mean below CP keeps FTP trusted`() {
        val w = WPrimeBalance(250, 15_000.0); w.onPower(200, 0)
        feed(w, 200, 1200)
        assertFalse(w.ftpStale); assertTrue(w.trusted)
    }

    @Test fun `ftp stale when 20-minute mean exceeds CP`() {
        val w = WPrimeBalance(200, 15_000.0); w.onPower(200, 0)
        feed(w, 230, 1200)
        assertTrue(w.ftpStale); assertFalse(w.trusted)
    }

    @Test fun `no FTP is never trusted`() = assertFalse(WPrimeBalance(0, 15_000.0).trusted)

    @Test fun `projection below CP recovers`() {
        val w = WPrimeBalance(250, 15_000.0); w.onPower(250, 0); feed(w, 400, 60)
        assertTrue(w.projectPct(300.0, 150.0) > w.pct)
    }
}
