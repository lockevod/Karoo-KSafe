package com.enderthor.kSafe.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure truth-table coverage for [SendOutcome]'s derived properties — the logic that drives
 * the emergency partial-delivery notification (`EmergencyManager.notifyPartialDelivery`) and
 * the info-send success mapping (`Sender.sendInfo`).
 */
class SendOutcomeTest {

    @Test
    fun `anyOk reflects at least one delivery`() {
        assertFalse(SendOutcome(0, 3).anyOk)
        assertTrue(SendOutcome(1, 3).anyOk)
        assertTrue(SendOutcome(3, 3).anyOk)
    }

    @Test
    fun `partial is true only when reached some but not all`() {
        assertFalse("none delivered is not partial", SendOutcome(0, 3).partial)
        assertTrue("1 of 3 is partial", SendOutcome(1, 3).partial)
        assertTrue("2 of 3 is partial", SendOutcome(2, 3).partial)
        assertFalse("all delivered is not partial", SendOutcome(3, 3).partial)
        assertFalse("single recipient reached is not partial", SendOutcome(1, 1).partial)
        assertFalse("zero eligible is not partial", SendOutcome(0, 0).partial)
    }

    @Test
    fun `infoSuccess treats scope-filtered no-op as success but hard fail as failure`() {
        // Deliverable + reached someone → success.
        assertTrue(SendOutcome(1, 3).infoSuccess)
        assertTrue(SendOutcome(2, 3).infoSuccess)
        // Deliverable but scope-filtered to zero recipients → legitimate no-op success.
        assertTrue(SendOutcome.NO_OP.infoSuccess)
        assertTrue(SendOutcome(0, 0).infoSuccess)
        // Deliverable, recipients attempted, none reached → failure.
        assertFalse(SendOutcome(0, 3).infoSuccess)
        // Single recipient (ntfy / one-contact Telegram), delivery failed → failure. Guards
        // against a `delivered >= 0`-style mutation that would wrongly pass the (0,3) case.
        assertFalse(SendOutcome(0, 1).infoSuccess)
        assertTrue(SendOutcome(1, 1).infoSuccess)
        // Non-deliverable config (blank creds / no contact) → failure even though eligible==0.
        assertFalse(SendOutcome.HARD_FAIL.infoSuccess)
    }

    @Test
    fun `hard fail constant carries the flag and is not a no-op`() {
        assertTrue(SendOutcome.HARD_FAIL.hardFail)
        assertFalse(SendOutcome.HARD_FAIL.anyOk)
        assertFalse(SendOutcome.HARD_FAIL.partial)
        assertFalse(SendOutcome.NO_OP.hardFail)
    }

    @Test
    fun `no-op and hard fail are distinguishable despite identical counts`() {
        // Both have delivered==0, eligible==0 — the hardFail flag is the only differentiator,
        // and it is exactly what lets sendInfo tell a scope no-op apart from a real failure.
        assertEquals(SendOutcome.NO_OP.delivered, SendOutcome.HARD_FAIL.delivered)
        assertEquals(SendOutcome.NO_OP.eligible, SendOutcome.HARD_FAIL.eligible)
        assertTrue(SendOutcome.NO_OP.infoSuccess)
        assertFalse(SendOutcome.HARD_FAIL.infoSuccess)
    }
}
