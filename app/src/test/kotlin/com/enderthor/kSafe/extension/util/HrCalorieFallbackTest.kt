package com.enderthor.kSafe.extension.util

import io.hammerhead.karooext.models.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [HrCalorieFallback] (calorie-only %HRmax fallback) and the
 * [CalorieSource] mapping. The fallback must never feed the carb path; here we
 * only pin its own numbers + the "never `---` while HR + weight present" contract.
 */
class HrCalorieFallbackTest {

    /** Minimal real UserProfile — the fallback only reads weight / maxHr / restingHr.
     *  The project constructs real profiles in tests (see CarbBurnEstimatorTest);
     *  UserProfile is final so it can't be mocked with plain mockito-core. */
    private fun profile(weight: Float, maxHr: Int = 0, restingHr: Int = 0) = UserProfile(
        weight = weight,
        preferredUnit = UserProfile.PreferredUnit(
            distance = UserProfile.PreferredUnit.UnitType.METRIC,
            temperature = UserProfile.PreferredUnit.UnitType.METRIC,
            elevation = UserProfile.PreferredUnit.UnitType.METRIC,
            weight = UserProfile.PreferredUnit.UnitType.METRIC,
        ),
        maxHr = maxHr,
        restingHr = restingHr,
        heartRateZones = emptyList(),
        ftp = 0,
        powerZones = emptyList(),
    )

    @Test
    fun `zero for missing HR`() {
        assertEquals(0.0, HrCalorieFallback.kcalPerHour(null, profile(70f), 30), 0.0)
        assertEquals(0.0, HrCalorieFallback.kcalPerHour(0, profile(70f), 30), 0.0)
    }

    @Test
    fun `zero for missing or non-positive weight`() {
        assertEquals(0.0, HrCalorieFallback.kcalPerHour(140, null, 30), 0.0)
        assertEquals(0.0, HrCalorieFallback.kcalPerHour(140, profile(0f), 30), 0.0)
    }

    @Test
    fun `uses profile maxHr and resting when present`() {
        // hrr = (150-50)/(190-50) = 0.714; mets = 6*0.714+1 = 5.29; kcal/h = 5.29*70 = 370
        val kcal = HrCalorieFallback.kcalPerHour(150, profile(70f, maxHr = 190, restingHr = 50), 30)
        assertEquals(370.0, kcal, 8.0)
    }

    @Test
    fun `defaults maxHr from age and resting to 60 when profile lacks them`() {
        // maxHr = 220-30 = 190; resting default 60; hrr = (150-60)/(190-60) = 0.692;
        // mets = 6*0.692+1 = 5.15; kcal/h = 5.15*70 = 361
        val kcal = HrCalorieFallback.kcalPerHour(150, profile(70f), 30)
        assertEquals(361.0, kcal, 8.0)
    }

    @Test
    fun `defaults maxHr to 190 when age also unknown`() {
        val kcal = HrCalorieFallback.kcalPerHour(150, profile(70f), 0)
        assertTrue(kcal > 0.0)
    }

    @Test
    fun `CalorieSource maps all confidence tiers`() {
        assertEquals(CalorieSource.POWER,  CalorieSource.from(CarbBurnEstimator.Confidence.POWER, 100.0))
        assertEquals(CalorieSource.KEYTEL, CalorieSource.from(CarbBurnEstimator.Confidence.KEYTEL, 100.0))
        assertEquals(CalorieSource.SWAIN,  CalorieSource.from(CarbBurnEstimator.Confidence.SWAIN, 100.0))
        assertEquals(CalorieSource.HRMAX,  CalorieSource.from(CarbBurnEstimator.Confidence.NONE, 100.0))
        assertEquals(CalorieSource.NONE,   CalorieSource.from(CarbBurnEstimator.Confidence.NONE, 0.0))
    }
}
