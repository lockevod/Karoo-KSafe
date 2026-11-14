package com.enderthor.kSafe.extension.pacing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelHintTest {
    @Test fun `real deficit with logs fires in the window`() {
        val r = FuelHint.decide(true, 60, 160, 100, 60, 90.0, inFuelWindow = true, msSinceCarbAlert = null)
        assertTrue(r.fire); assertTrue(r.withLogs); assertEquals(100, r.grams)
    }
    @Test fun `no logs uses burned grams after an hour`() {
        val r = FuelHint.decide(true, 0, 80, 80, 60, 70.0, true, null)
        assertTrue(r.fire); assertFalse(r.withLogs); assertEquals(80, r.grams)
    }
    @Test fun `no logs too early does not fire`() = assertFalse(FuelHint.decide(true, 0, 80, 80, 60, 40.0, true, null).fire)
    @Test fun `recent carb alert drops the hint`() = assertFalse(FuelHint.decide(true, 0, 90, 90, 60, 90.0, true, 10 * 60_000L).fire)
    @Test fun `carb reminder due soon drops the hint`() {
        val r = FuelHint.decide(true, 60, 160, 100, 60, 90.0, true, null, carbReminderDueSoon = true)
        assertFalse(r.fire); assertEquals("carb_reminder_due", r.reason)
    }
    @Test fun `disabled carb tracking never fires`() = assertFalse(FuelHint.decide(false, 0, 200, 200, 60, 200.0, true, null).fire)
    @Test fun `outside the window never fires`() = assertFalse(FuelHint.decide(true, 0, 200, 200, 60, 200.0, false, null).fire)
}
