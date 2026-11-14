package com.enderthor.kSafe.extension.pacing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GradePaceModelTest {
    @Test fun `defaults before learning`() {
        val m = GradePaceModel()
        assertEquals(28.0, m.speedKmh(BikeKind.ROAD, 0.0), 0.01)
        assertEquals(18.0, m.speedKmh(BikeKind.MTB, 0.0), 0.01)
        assertFalse(m.isConfident(BikeKind.ROAD, 0.0))
    }
    @Test fun `learned bin overrides default once confident`() {
        val m = GradePaceModel()
        m.learn(BikeKind.ROAD, 6.2, 500.0, 120.0)        // 15 km/h in the 6 % bin
        assertTrue(m.isConfident(BikeKind.ROAD, 6.0))
        assertEquals(15.0, m.speedKmh(BikeKind.ROAD, 5.6), 0.01)
    }
    @Test fun `grade clamps to the outer bins`() {
        val m = GradePaceModel(); m.learn(BikeKind.ROAD, 35.0, 500.0, 600.0)
        assertEquals(3.0, m.speedKmh(BikeKind.ROAD, 20.0), 0.01)
    }
    @Test fun `seconds between integrates over the profile`() {
        val flat = ElevationProfile(doubleArrayOf(0.0, 2800.0), doubleArrayOf(0.0, 0.0))
        assertEquals(360.0, GradePaceModel().secondsBetween(BikeKind.ROAD, flat, 0.0, 2800.0), 1.0)  // 28 km/h
    }
    @Test fun `dto round trip`() {
        val m = GradePaceModel(); m.learn(BikeKind.MTB, -3.0, 800.0, 100.0)
        assertEquals(m.speedKmh(BikeKind.MTB, -3.0), GradePaceModel.fromDto(m.toDto()).speedKmh(BikeKind.MTB, -3.0), 1e-9)
    }
}
