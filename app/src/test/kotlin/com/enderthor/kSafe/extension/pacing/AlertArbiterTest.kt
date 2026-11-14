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

    @Test fun `ease-off cue never blocks its own block's pacing advice`() {
        val a = AlertArbiter(); a.recordFired(AlertSource.PACING, "INTENSITY#5", 0.5, 0)
        assertEquals("gap:PACING", a.evaluate(AlertCandidate(AlertSource.PACING, "CONSERVE#5", 0.3), 2 * min, false).suppressedBy)
        val v = a.evaluate(AlertCandidate(AlertSource.PACING, "CONSERVE#5", 0.3), 4 * min, false)
        assertTrue(v.fire); assertEquals("after_cue", v.bypass)
        assertTrue(a.evaluate(AlertCandidate(AlertSource.PACING, "HIGH_MARGIN#5", 0.5), 4 * min, false).fire)
        // Another block keeps the rate limit (not harder), as does a plain pacing alert before.
        assertEquals("pacing_rate", a.evaluate(AlertCandidate(AlertSource.PACING, "CONSERVE#6", 0.3), 4 * min, false).suppressedBy)
        val b = AlertArbiter(); b.recordFired(AlertSource.PACING, "CONSERVE#5", 0.5, 0)
        val w = b.evaluate(AlertCandidate(AlertSource.PACING, "HIGH_MARGIN#5", 0.5), 4 * min, false)
        assertEquals("pacing_rate", w.suppressedBy); assertEquals(null, w.bypass)
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
