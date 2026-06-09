package com.enderthor.kSafe.extension.util

import com.enderthor.kSafe.data.CarbFuelingState
import com.enderthor.kSafe.data.HydFuelingState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [FuelingPersistPolicy]. The two regression-locked cases are the real bugs an
 * adversarial review found in the inline version this helper replaced:
 *  - a calories-only ride (cumKcal > 0, everything else 0) must still persist;
 *  - the cumKcal deadband must bound loss vs the LAST WRITE (not the last cycle),
 *    so a slow monotonic climb can't defer forever.
 */
class FuelingPersistPolicyTest {

    private val zeroCarb = CarbFuelingState()
    private val zeroHyd = HydFuelingState()

    @Test
    fun `all-zero state is not persisted`() {
        assertFalse(FuelingPersistPolicy.shouldPersist(null, null, zeroCarb, zeroHyd))
    }

    @Test
    fun `calories-only ride persists even with carbs and hydration at zero`() {
        // The round-1 bug: cumKcal was omitted from the zero-skip, so this returned
        // false forever and the calories-only session was never saved.
        val carb = CarbFuelingState(cumKcal = 120f)
        assertTrue(FuelingPersistPolicy.shouldPersist(null, null, carb, zeroHyd))
    }

    @Test
    fun `unchanged state vs last write is not persisted`() {
        val carb = CarbFuelingState(cumBurnedG = 50f, cumKcal = 300f)
        val hyd = HydFuelingState(cumTargetMl = 400f)
        assertFalse(FuelingPersistPolicy.shouldPersist(carb, hyd, carb.copy(), hyd.copy()))
    }

    @Test
    fun `small kcal-only delta within the band defers`() {
        val prev = CarbFuelingState(cumKcal = 100f)
        val cur = CarbFuelingState(cumKcal = 108f)   // +8 < 10 band
        assertFalse(FuelingPersistPolicy.shouldPersist(prev, zeroHyd, cur, zeroHyd))
    }

    @Test
    fun `kcal delta over the band persists`() {
        val prev = CarbFuelingState(cumKcal = 100f)
        val cur = CarbFuelingState(cumKcal = 112f)   // +12 > 10 band
        assertTrue(FuelingPersistPolicy.shouldPersist(prev, zeroHyd, cur, zeroHyd))
    }

    @Test
    fun `kcal loss is bounded vs last write, not last cycle`() {
        // Slow climber: +8 kcal/cycle. Deltas are measured against the last WRITE,
        // so two deferred cycles accumulate to +16 and force a write — loss can
        // never grow unbounded.
        val lastWrite = CarbFuelingState(cumKcal = 100f)
        val cycle1 = CarbFuelingState(cumKcal = 108f)
        assertFalse(FuelingPersistPolicy.shouldPersist(lastWrite, zeroHyd, cycle1, zeroHyd))
        // lastWrite is NOT advanced on a defer, so the next cycle is compared to it.
        val cycle2 = CarbFuelingState(cumKcal = 116f)
        assertTrue(FuelingPersistPolicy.shouldPersist(lastWrite, zeroHyd, cycle2, zeroHyd))
    }

    @Test
    fun `carb burn delta within band defers, over band persists`() {
        val prev = CarbFuelingState(cumBurnedG = 50f)
        assertFalse(FuelingPersistPolicy.shouldPersist(prev, zeroHyd, CarbFuelingState(cumBurnedG = 53f), zeroHyd)) // +3 < 5
        assertTrue(FuelingPersistPolicy.shouldPersist(prev, zeroHyd, CarbFuelingState(cumBurnedG = 57f), zeroHyd))  // +7 > 5
    }

    @Test
    fun `an event-driven field change breaks the deadband even within accumulator bands`() {
        // A rider log bumps cumLoggedG — that must persist immediately regardless of
        // how small the burn/kcal deltas are.
        val prev = CarbFuelingState(cumBurnedG = 50f, cumKcal = 100f, cumLoggedG = 30)
        val cur = CarbFuelingState(cumBurnedG = 51f, cumKcal = 105f, cumLoggedG = 60)
        assertTrue(FuelingPersistPolicy.shouldPersist(prev, zeroHyd, cur, zeroHyd))
    }

    @Test
    fun `first write with accumulation persists (no prior baseline)`() {
        val carb = CarbFuelingState(cumBurnedG = 2f, cumKcal = 15f)
        assertTrue(FuelingPersistPolicy.shouldPersist(null, null, carb, zeroHyd))
    }

    @Test
    fun `hydration target delta within band defers, over band persists`() {
        val prevHyd = HydFuelingState(cumTargetMl = 400f)
        val carb = CarbFuelingState(cumBurnedG = 50f)
        assertFalse(FuelingPersistPolicy.shouldPersist(carb, prevHyd, carb.copy(), HydFuelingState(cumTargetMl = 440f))) // +40 < 60
        assertTrue(FuelingPersistPolicy.shouldPersist(carb, prevHyd, carb.copy(), HydFuelingState(cumTargetMl = 480f)))  // +80 > 60
    }
}
