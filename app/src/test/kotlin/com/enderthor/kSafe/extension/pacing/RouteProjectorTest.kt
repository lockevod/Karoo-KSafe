package com.enderthor.kSafe.extension.pacing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteProjectorTest {
    private val line = PolylinePath(listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.05)))      // ~5.6 km
    private val outBack = PolylinePath(listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.01), LatLng(0.0, 0.0)))

    @Test fun `bootstraps globally then tracks`() {
        val p = RouteProjector(line)
        assertEquals(PosReason.OK, p.update(LatLng(0.0, 0.001), 5.0, 0, 0.0, false).reason)
        val r = p.update(LatLng(0.0, 0.002), 5.0, 0, 111.0, false)
        assertEquals(222.6, r.distanceM!!, 2.0)
    }

    @Test fun `out-and-back stays on the return pass`() {
        val p = RouteProjector(outBack)
        var odo = 0.0
        // ride out in 100 m steps to the turn, then back
        for (i in 1..10) { odo += 111.3; p.update(LatLng(0.0, i * 0.001), 5.0, 0, odo, false) }
        for (i in 9 downTo 2) { odo += 111.3; p.update(LatLng(0.0, i * 0.001), 5.0, 0, odo, false) }
        assertEquals(1113.0 + 8 * 111.3, p.lastGoodM!!, 30.0)
    }

    @Test fun `rejoin stale and poor accuracy are untrusted`() {
        val p = RouteProjector(line)
        p.update(LatLng(0.0, 0.001), 5.0, 0, 0.0, false)
        assertEquals(PosReason.REJOIN, p.update(LatLng(0.0, 0.002), 5.0, 0, 100.0, true).reason)
        assertEquals(PosReason.STALE_FIX, p.update(LatLng(0.0, 0.002), 5.0, 6_000, 100.0, false).reason)
        assertEquals(PosReason.POOR_ACCURACY, p.update(LatLng(0.0, 0.002), 40.0, 0, 100.0, false).reason)
    }

    @Test fun `far from the line is off route`() {
        val p = RouteProjector(line)
        val r = p.update(LatLng(0.01, 0.001), 5.0, 0, 0.0, false)   // ~1.1 km north
        assertEquals(PosReason.OFF_ROUTE, r.reason); assertNull(r.distanceM)
    }

    @Test fun `foot point is clipped to the window at the turn`() {
        val p = RouteProjector(outBack)
        var odo = 0.0
        for (i in 1..10) { odo += 111.3; p.update(LatLng(0.0, i * 0.001), 5.0, 0, odo, false) }
        // Just past the turn on the return pass: the unclipped foot would be the outbound ~1100 m.
        odo += 30.0
        val r = p.update(LatLng(0.0, 0.0097), 5.0, 0, odo, false)
        assertEquals(1113.0 + 33.4, r.distanceM!!, 15.0)
    }
}
