package com.enderthor.kSafe.extension.pacing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacingAlertTextTest {
    private val conserve = PacingAlert(AlertSource.PACING, PacingState.CONSERVE, "CONSERVE#1", 4.2, 6.0, 7.0, 210, 0.8, null, false)

    @Test fun `spanish conserve`() {
        val t = PacingAlertText.render(conserve, es = true)
        assertEquals("Conserva para el puerto", t.title)
        assertEquals("En 4.2 km: 6.0 km al 7 %. Súbelo ≤210 W.", t.detail)
    }

    @Test fun `conserve without a block or ceiling drops the missing clause`() {
        assertEquals("Save energy", PacingAlertText.render(conserve.copy(blockKm = null, key = "CONSERVE#cond"), false).title)
        assertEquals("In 4.2 km: 6.0 km at 7 %.", PacingAlertText.render(conserve.copy(ceilingW = null), false).detail)
    }

    @Test fun `high margin never says push`() {
        for (es in listOf(true, false)) {
            val t = PacingAlertText.render(conserve.copy(state = PacingState.HIGH_MARGIN, key = "HIGH_MARGIN#2", ceilingW = 260), es)
            assertEquals(if (es) "Margen alto" else "High margin", t.title)
            for (w in listOf("push", "aprieta", "ataca")) assertFalse(t.detail.contains(w, ignoreCase = true))
            assertTrue(t.detail.contains("260"))
        }
    }

    @Test fun `unlogged fuel wording is cautious`() {
        val f = PacingAlert(AlertSource.FUEL_HINT, null, "FUEL#1", 6.0, null, null, null, null, 80, false)
        assertEquals("~80 g quemados sin registrar comida.", PacingAlertText.render(f, true).detail.substringAfter(". "))
        assertEquals("Climb in 6.0 km. Deficit ~80 g.", PacingAlertText.render(f.copy(withLogs = true), false).detail)
    }

    @Test fun `w prime shows the balance percent`() {
        val w = PacingAlert(AlertSource.W_PRIME, null, "W#1", 1.5, 2.0, 6.0, 250, 0.35, null, false)
        assertEquals("W′ 35 %. Ease off before the climb (1.5 km).", PacingAlertText.render(w, false).detail)
    }

    @Test fun `leaving conserve says keep`() {
        assertEquals("Ritmo correcto", PacingAlertText.render(conserve.copy(state = PacingState.KEEP, key = "KEEP#1"), true).title)
    }

    @Test fun `detail is clamped to 90`() {
        val long = conserve.copy(blockKm = 1.23456789e40, blockLenKm = 9.87654321e40)
        val d = PacingAlertText.render(long, true).detail
        assertTrue(d.length <= 90); assertTrue(d.endsWith("…"))
    }
}
