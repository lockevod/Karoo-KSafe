package com.enderthor.kSafe.extension

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards [KSafeExtension.sessionDeadbandExceeded], the FIT session write gate for sweat / sodium. */
class SessionDeadbandTest {

    @Test fun `first tick always writes`() {
        assertTrue(KSafeExtension.sessionDeadbandExceeded(0.0, Double.NaN, 10.0))
    }

    @Test fun `movement under the deadband does not write`() {
        assertFalse(KSafeExtension.sessionDeadbandExceeded(109.0, 100.0, 10.0))
    }

    @Test fun `movement of exactly the deadband writes`() {
        assertTrue(KSafeExtension.sessionDeadbandExceeded(110.0, 100.0, 10.0))
    }

    @Test fun `a drop of the deadband also writes`() {
        assertTrue(KSafeExtension.sessionDeadbandExceeded(90.0, 100.0, 10.0))
    }
}
