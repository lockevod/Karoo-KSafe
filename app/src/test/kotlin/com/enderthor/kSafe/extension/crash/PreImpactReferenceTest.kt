package com.enderthor.kSafe.extension.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreImpactReferenceTest {

    /** Build a buffer of constant-vector samples at 20 ms spacing (50 Hz) ending at [endTs]. */
    private fun constantBuffer(
        x: Double, y: Double, z: Double,
        count: Int, endTs: Long, stepMs: Long = 20L,
    ): List<TimedVec3> = (0 until count).map { i ->
        TimedVec3(x, y, z, endTs - (count - 1 - i) * stepMs)
    }

    @Test
    fun `averages the slice excluding the guard window`() {
        // 200 samples (4 s) of an upright vector ending exactly at the impact.
        val buf = constantBuffer(0.0, 0.0, 9.81, count = 200, endTs = 100_000L)
        val ref = PreImpactReference.compute(buf, impactTsMs = 100_000L)
        assertTrue(ref.valid)
        assertEquals(0.0, ref.x, 1e-9)
        assertEquals(0.0, ref.y, 1e-9)
        assertEquals(9.81, ref.z, 1e-9)
    }

    @Test
    fun `returns invalid when too few samples in the window`() {
        // Only 10 samples — below the 50-sample minimum.
        val buf = constantBuffer(0.0, 0.0, 9.81, count = 10, endTs = 100_000L)
        val ref = PreImpactReference.compute(buf, impactTsMs = 100_000L)
        assertFalse(ref.valid)
    }

    @Test
    fun `the guard window excludes the impact transient`() {
        // 200 upright samples, then 5 huge "transient" samples in the last 100 ms.
        val upright = constantBuffer(0.0, 0.0, 9.81, count = 200, endTs = 99_900L)
        val transient = (0 until 5).map { TimedVec3(50.0, 50.0, 50.0, 99_920L + it * 20L) }
        val ref = PreImpactReference.compute(upright + transient, impactTsMs = 100_000L)
        // The transient samples fall inside the 250 ms guard and must be excluded.
        assertTrue(ref.valid)
        assertEquals(9.81, ref.z, 1e-9)
        assertEquals(0.0, ref.x, 1e-9)
    }
}
