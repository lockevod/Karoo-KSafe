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
        // 10 samples genuinely inside the window (ts 99_570..99_750) — below MIN_SAMPLES=50.
        val buf = constantBuffer(0.0, 0.0, 9.81, count = 10, endTs = 99_750L)
        val ref = PreImpactReference.compute(buf, impactTsMs = 100_000L)
        assertFalse(ref.valid)
    }

    @Test
    fun `returns invalid when the buffer holds no samples inside the window`() {
        // All samples sit inside the 250 ms guard — none qualify.
        val buf = constantBuffer(0.0, 0.0, 9.81, count = 10, endTs = 100_000L)
        val ref = PreImpactReference.compute(buf, impactTsMs = 100_000L)
        assertFalse(ref.valid)
    }

    @Test
    fun `notBeforeMs excludes entries below the floor from the average`() {
        // Window for impact 100_000 is [97_750, 99_750]. "pre-pause" samples at z=9.81
        // all sit below the floor of 98_000; "post-resume" samples at z=5.00 sit above
        // it. With the floor applied, only the post-resume samples qualify — enough
        // (>= MIN_SAMPLES) to stay valid, and the average reflects only z=5.00.
        val pre = constantBuffer(0.0, 0.0, 9.81, count = 150, endTs = 97_980L)
        // 80 post-resume samples spanning ts 98_000..99_580 — all inside the window
        // and at/above the 98_000 floor.
        val post = (0 until 80).map { TimedVec3(0.0, 0.0, 5.00, 98_000L + it * 20L) }
        val ref = PreImpactReference.compute(
            pre + post, impactTsMs = 100_000L, notBeforeMs = 98_000L,
        )
        assertTrue(ref.valid)
        assertEquals(5.00, ref.z, 1e-9)
        assertEquals(0.0, ref.x, 1e-9)
    }

    @Test
    fun `notBeforeMs drives the result to invalid when too few entries remain above the floor`() {
        // 200 upright samples in the window, but the floor sits so late that only ~10
        // samples remain above it — below MIN_SAMPLES=50 → INVALID.
        val buf = constantBuffer(0.0, 0.0, 9.81, count = 200, endTs = 99_750L)
        // Window hi = 100_000-250 = 99_750. Floor at 99_570 leaves ts 99_570..99_750
        // = 10 samples (20 ms spacing).
        val ref = PreImpactReference.compute(
            buf, impactTsMs = 100_000L, notBeforeMs = 99_570L,
        )
        assertFalse(ref.valid)
    }

    @Test
    fun `omitting notBeforeMs behaves exactly as before - no floor applied`() {
        // Same buffer as `averages the slice excluding the guard window`; with the
        // default notBeforeMs (Long.MIN_VALUE) every sample in the window qualifies.
        val buf = constantBuffer(0.0, 0.0, 9.81, count = 200, endTs = 100_000L)
        val withDefault = PreImpactReference.compute(buf, impactTsMs = 100_000L)
        val withExplicitMin =
            PreImpactReference.compute(buf, impactTsMs = 100_000L, notBeforeMs = Long.MIN_VALUE)
        assertTrue(withDefault.valid)
        assertEquals(withDefault.x, withExplicitMin.x, 0.0)
        assertEquals(withDefault.y, withExplicitMin.y, 0.0)
        assertEquals(withDefault.z, withExplicitMin.z, 0.0)
        assertEquals(9.81, withDefault.z, 1e-9)
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
