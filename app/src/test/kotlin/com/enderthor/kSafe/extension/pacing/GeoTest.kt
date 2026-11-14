package com.enderthor.kSafe.extension.pacing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    @Test fun `decodes the Google reference polyline`() {
        val pts = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].lat, 1e-5); assertEquals(-120.2, pts[0].lng, 1e-5)
        assertEquals(43.252, pts[2].lat, 1e-5); assertEquals(-126.453, pts[2].lng, 1e-5)
    }

    @Test fun `truncated polyline does not throw`() {
        val pts = Polyline.decode("_p~iF~ps|U_ulL")
        assertEquals(1, pts.size)
    }

    @Test fun `projects onto a straight east-west line`() {
        val path = PolylinePath(listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.01)))   // ~1113 m
        val p = path.nearestProjection(LatLng(0.0001, 0.005))
        assertEquals(556.6, p.distanceAlongM, 2.0)
        assertEquals(11.1, p.perpDistM, 1.0)
    }

    @Test fun `window keeps the current pass of an out-and-back`() {
        // out 0→0.01 then back 0.01→0 on the same line: 2 passes over every point
        val path = PolylinePath(listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.01), LatLng(0.0, 0.0)))
        val rider = LatLng(0.0, 0.002)                       // 222 m on pass 1, 2004 m on pass 2
        val near = path.nearestProjectionInWindowOrNull(rider, 1900.0, 120.0, 300.0)
        assertNotNull(near); assertEquals(2004.0, near!!.distanceAlongM, 5.0)
        // Window [880,1300] cuts the turnaround segment 0 (0..1113 m), which still contains the rider's
        // foot point at 222 m, so it projects there (perp ~0) although distanceAlong is outside the window.
        // Null only when no segment intersects the window at all.
        assertEquals(0.0, path.nearestProjectionInWindowOrNull(rider, 1000.0, 120.0, 300.0)!!.perpDistM, 1.0)
        assertNull(path.nearestProjectionInWindowOrNull(rider, 50_000.0, 120.0, 300.0))
    }
}
