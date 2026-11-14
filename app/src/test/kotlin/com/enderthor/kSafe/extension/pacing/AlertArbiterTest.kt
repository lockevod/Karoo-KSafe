package com.enderthor.kSafe.extension.pacing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertArbiterTest {
    private val min = 60_000L

    @Test fun `emergency blocks everything`() =
        assertEquals("emergency", AlertArbiter().evaluate(AlertCandidate(AlertSource.PACING, "CONSERVE#1", 0.5), 0, true).suppressedBy)

    @Test fun `recent higher priority blocks lower but not the reverse`() {
        val a = AlertArbiter(); a.recordExternal(AlertSource.MEDICAL, 0)
        assertEquals("gap:MEDICAL", a.evaluate(AlertCandidate(AlertSource.PACING, "CONSERVE#1", 0.5), 2 * min, false).suppressedBy)
        val b = AlertArbiter(); b.recordExternal(AlertSource.FUEL_TIME, 0)
        assertTrue(b.evaluate(AlertCandidate(AlertSource.FUEL_HINT, "FUEL#1", 0.5), 1 * min, false).fire)
    }

    @Test fun `pacing and W' keep the gap after any alert, whatever its priority`() {
        val a = AlertArbiter(); a.recordExternal(AlertSource.FUEL_TIME, 0)
        assertEquals("gap:FUEL_TIME", a.evaluate(AlertCandidate(AlertSource.PACING, "CONSERVE#1", 0.5), 1 * min, false).suppressedBy)
        assertEquals("gap:FUEL_TIME", a.evaluate(AlertCandidate(AlertSource.W_PRIME, "W#1", 0.5), 1 * min, false).suppressedBy)
        assertTrue(a.evaluate(AlertCandidate(AlertSource.PACING, "CONSERVE#1", 0.5), 4 * min, false).fire)
        val b = AlertArbiter(); b.recordFired(AlertSource.FUEL_HINT, "FUEL#1", 0.5, 0)
        assertEquals("gap:FUEL_HINT", b.evaluate(AlertCandidate(AlertSource.PACING, "CONSERVE#1", 0.5), 1 * min, false).suppressedBy)
        val c = AlertArbiter(); c.recordFired(AlertSource.PACING, "CONSERVE#1", 0.5, 0)
        assertEquals("gap:PACING", c.evaluate(AlertCandidate(AlertSource.W_PRIME, "W#1", 0.9), 1 * min, false).suppressedBy)
    }

    @Test fun `pacing rate limit with harder later block exception`() {
        val a = AlertArbiter(); a.recordFired(AlertSource.PACING, "CONSERVE#1", 0.3, 0)
        assertEquals("pacing_rate", a.evaluate(AlertCandidate(AlertSource.PACING, "CONSERVE#1", 0.3), 5 * min, false).suppressedBy)
        assertTrue(a.evaluate(AlertCandidate(AlertSource.PACING, "CONSERVE#2", 0.6), 5 * min, false).fire)
    }

    @Test fun `same key repeats only when worse`() {
        val a = AlertArbiter(); a.recordFired(AlertSource.PACING, "CONSERVE#1", 0.3, 0)
        assertEquals("repeat", a.evaluate(AlertCandidate(AlertSource.PACING, "CONSERVE#1", 0.35), 20 * min, false).suppressedBy)
        assertTrue(a.evaluate(AlertCandidate(AlertSource.PACING, "CONSERVE#1", 0.45), 20 * min, false).fire)
        assertTrue(a.evaluate(AlertCandidate(AlertSource.PACING, "CONSERVE#1", 0.3), 31 * min, false).fire)
    }

    @Test fun `fuel hint is blocked by a recent pacing alert`() {
        val a = AlertArbiter(); a.recordFired(AlertSource.PACING, "CONSERVE#1", 0.3, 0)
        assertFalse(a.evaluate(AlertCandidate(AlertSource.FUEL_HINT, "FUEL#1", 0.5), 2 * min, false).fire)
    }
}
