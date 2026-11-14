package com.enderthor.kSafe.extension.pacing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ElevationProfileTest {
    // 0 m @100, 1000 m @150, 2000 m @150, 3000 m @100
    private val p = ElevationProfile(doubleArrayOf(0.0, 1000.0, 2000.0, 3000.0), doubleArrayOf(100.0, 150.0, 150.0, 100.0))

    @Test fun `interpolates elevation`() = assertEquals(125.0, p.eleAt(500.0), 1e-9)
    @Test fun `grade is percent over the window`() = assertEquals(5.0, p.gradeAt(500.0), 1e-6)
    @Test fun `gain counts only climbing`() = assertEquals(50.0, p.gainBetween(0.0, 3000.0), 1e-6)
    @Test fun `reversed mirrors distance`() {
        val r = p.reversed()
        assertEquals(3000.0, r.totalM, 1e-9)
        assertEquals(125.0, r.eleAt(2500.0), 1e-9)
        assertEquals(-5.0, r.gradeAt(2500.0), 1e-6)
    }
    @Test fun `reversed flips an asymmetric ramp`() {
        val ramp = ElevationProfile(doubleArrayOf(0.0, 1000.0), doubleArrayOf(100.0, 200.0))
        assertEquals(200.0, ramp.reversed().eleAt(0.0), 1e-9)
        assertEquals(100.0, ramp.reversed().eleAt(1000.0), 1e-9)
        assertEquals(-10.0, ramp.reversed().gradeAt(500.0), 1e-6)
    }
    @Test fun `null or degenerate input gives null`() {
        assertNull(ElevationProfile.fromKaroo(null))
        assertNull(ElevationProfile.fromKaroo(""))
    }
    @Test fun `decodes a karoo precision-1 polyline`() {
        // encode (0,100),(1000,150) at precision 1 → values ×10
        val enc = TestPolyline.elev(listOf(0.0 to 100.0, 1000.0 to 150.0))
        val e = ElevationProfile.fromKaroo(enc)!!
        assertEquals(1000.0, e.totalM, 0.1); assertEquals(150.0, e.eleAt(1000.0), 0.1)
    }
    @Test(timeout = 2000) fun `gain with non-finite or out-of-range bounds returns promptly`() {
        assertEquals(0.0, p.gainBetween(0.0, Double.POSITIVE_INFINITY), 0.0)
        assertEquals(0.0, p.gainBetween(Double.NaN, 100.0), 0.0)
        assertEquals(0.0, p.gainBetween(2000.0, 1000.0), 0.0)
        assertEquals(50.0, p.gainBetween(-1e15, 3000.0), 1e-6)
        assertEquals(50.0, p.gainBetween(0.0, 1e12), 1e-6)
    }
    @Test(timeout = 2000) fun `seconds between with non-finite or huge bounds returns promptly`() {
        val m = GradePaceModel()
        assertEquals(0.0, m.secondsBetween(BikeKind.ROAD, p, 0.0, Double.POSITIVE_INFINITY), 0.0)
        assertEquals(0.0, m.secondsBetween(BikeKind.ROAD, p, Double.NaN, 10.0), 0.0)
        assertEquals(0.0, m.secondsBetween(BikeKind.ROAD, p, 2000.0, 1000.0), 0.0)
        assertEquals(m.secondsBetween(BikeKind.ROAD, p, 0.0, 3000.0), m.secondsBetween(BikeKind.ROAD, p, -1e15, 1e12), 1e-6)
    }
}
