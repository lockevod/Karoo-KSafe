package com.enderthor.kSafe.extension.pacing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacingAlertTextTest {
    private val conserve = PacingAlert(AlertSource.PACING, PacingState.CONSERVE, "CONSERVE#1", 2.1, 6.0, 7.0, 210, 0.8, null, false)
    private val fuel = PacingAlert(AlertSource.FUEL_HINT, null, "FUEL#1", 6.0, null, null, null, null, 80, false)
    private val wPrime = PacingAlert(AlertSource.W_PRIME, null, "W#1", 1.5, 2.0, 6.0, 250, 0.35, null, false)

    @Test fun `conserve puts the ceiling first`() {
        val t = PacingAlertText.render(conserve, es = true)
        assertEquals("Conserva para el puerto", t.title)
        assertEquals("Puerto 2,1 km: ≤210 W", t.detail)
        assertEquals("Climb 2.1 km: ≤210 W", PacingAlertText.render(conserve, es = false).detail)
    }

    @Test fun `conserve without a ceiling or block falls back`() {
        assertEquals("Climb 2.1 km: 6.0 km at 7%", PacingAlertText.render(conserve.copy(ceilingW = null), false).detail)
        assertEquals("Save energy", PacingAlertText.render(conserve.copy(blockKm = null, key = "CONSERVE#cond"), false).title)
    }

    @Test fun `high margin never says push`() {
        for (es in listOf(true, false)) {
            val t = PacingAlertText.render(conserve.copy(state = PacingState.HIGH_MARGIN, key = "HIGH_MARGIN#2", ceilingW = 260), es)
            assertEquals(if (es) "Margen alto" else "High margin", t.title)
            assertEquals(if (es) "Puerto 2,1 km: hasta ~260 W" else "Climb 2.1 km: up to ~260 W", t.detail)
            for (w in listOf("push", "aprieta", "ataca")) assertFalse(t.detail.contains(w, ignoreCase = true))
        }
    }

    @Test fun `unlogged fuel wording is cautious`() {
        assertEquals("~80 g sin registrar. Come", PacingAlertText.render(fuel, true).detail)
        assertEquals("Deficit ~80 g: eat now", PacingAlertText.render(fuel.copy(withLogs = true), false).detail)
    }

    @Test fun `w prime shows the balance percent`() {
        assertEquals("W′ 35 %: suelta antes", PacingAlertText.render(wPrime, true).detail)
        assertEquals("W′ 35%: ease off first", PacingAlertText.render(wPrime, false).detail)
    }

    @Test fun `every detail fits the 34 chars the device shows`() {
        val alerts = listOf(
            conserve, conserve.copy(blockKm = 123.4, ceilingW = 1260), conserve.copy(ceilingW = null, blockKm = 99.9, blockLenKm = 25.3, blockGradePct = 12.4),
            conserve.copy(blockKm = null), conserve.copy(state = PacingState.HIGH_MARGIN, blockKm = 123.4, ceilingW = 1260),
            conserve.copy(state = PacingState.HIGH_MARGIN, ceilingW = null), conserve.copy(state = PacingState.KEEP),
            fuel, fuel.copy(grams = 1200), fuel.copy(grams = 1200, withLogs = true), fuel.copy(grams = null, blockKm = 123.4),
            wPrime, wPrime.copy(wPct = 1.0), wPrime.copy(wPct = null),
        )
        for (a in alerts) for (es in listOf(true, false)) {
            val t = PacingAlertText.render(a, es)
            assertTrue("'${t.detail}' (${t.detail.length})", t.detail.length <= 34)
            assertTrue(t.title.length <= 40)
        }
    }

    @Test fun `detail is hard-capped at 90`() {
        val d = PacingAlertText.render(conserve.copy(blockKm = 1.23456789e100), true).detail
        assertTrue(d.length <= 90); assertTrue(d.endsWith("…"))
    }
}
