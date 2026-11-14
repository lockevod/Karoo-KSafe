package com.enderthor.kSafe.extension.pacing

import io.hammerhead.karooext.models.OnNavigationState
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PacingManagerTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 0L
    private val alerts = mutableListOf<PacingAlert>()

    private fun route(): OnNavigationState.NavigationState.NavigatingRoute =
        OnNavigationState.NavigationState.NavigatingRoute(
            routePolyline = encodeLatLng(listOf(0.0 to 0.0, 0.0 to 0.2)),          // ~22 km east
            routeDistance = 22_264.0,
            routeElevationPolyline = encodeElev(listOf(0.0 to 100.0, 10_000.0 to 100.0, 15_000.0 to 450.0, 22_264.0 to 450.0)),
            rejoinPolyline = null, rejoinDistance = null, name = "t", reversed = false, breadcrumb = false,
            pois = emptyList(), climbs = emptyList(),
        )

    private fun manager() =
        PacingManager(TestScope(), tmp.root, null, clock = { now }, alertSink = { alerts += it }, kneeKjKgOverride = 3.0)

    /** 20 min at 30 km/h on the flat; [power] null = no power meter; [accuracyM] of every fix. */
    private fun ride(m: PacingManager, power: Int?, accuracyM: Double) {
        for (s in 0 until 1200) {
            now += 1000; m.onSpeed(30.0); power?.let { m.onPower(it) }; m.onHr(150)
            m.onLocation(0.0, (s * 8.33) / 111_320.0, accuracyM)
            if (s % 30 == 0) m.tick()
        }
    }

    @Test fun `shadow mode never alerts and publishes a trusted view`() {
        // knee 3 kJ/kg (test override) so 300 W for the rest of the route deterministically projects over it.
        val m = manager()
        m.onUserProfile(250, 70.0); m.onRoutingPreference("ROAD"); m.onNavigation(route())
        ride(m, power = 300, accuracyM = 5.0)
        assertTrue(alerts.isEmpty())
        val v = m.view.value; assertNotNull(v); assertTrue(v!!.trusted)
        assertEquals(PacingState.CONSERVE, v.state)              // projected finish > 3 kJ/kg test knee
        assertNotNull(v.nextBlockKm)                              // the 10–15 km climb is ahead
    }

    @Test fun `untrusted position publishes no projections`() {
        val m = manager()
        m.onUserProfile(250, 70.0); m.onNavigation(route())
        ride(m, power = 300, accuracyM = 100.0)                   // every fix too poor to project
        val v = m.view.value!!
        assertFalse(v.trusted); assertNull(v.nextBlockKm)
        // kJ/kg is over the knee but every projection is null, so nothing can enter CONSERVE.
        assertEquals(PacingState.KEEP, v.state)
    }

    @Test fun `heart rate only stays KEEP`() {
        val m = manager()
        m.onUserProfile(250, 70.0); m.onNavigation(route())
        ride(m, power = null, accuracyM = 5.0)
        val v = m.view.value!!
        assertTrue(v.trusted); assertEquals(PacingState.KEEP, v.state)
        assertTrue(alerts.isEmpty())
    }

    // Helpers: same encoder as ElevationProfileTest (precision 1) and a precision-5 lat/lng encoder.
    private fun encodeElev(p: List<Pair<Double, Double>>) = encode(p, 10.0)
    private fun encodeLatLng(p: List<Pair<Double, Double>>) = encode(p, 1e5)
    private fun encode(pts: List<Pair<Double, Double>>, f: Double): String {
        val sb = StringBuilder(); var pa = 0; var pb = 0
        fun enc(v: Int) { var x = if (v < 0) (v shl 1).inv() else v shl 1
            while (x >= 0x20) { sb.append(((0x20 or (x and 0x1f)) + 63).toChar()); x = x shr 5 }
            sb.append((x + 63).toChar()) }
        for ((a, b) in pts) { val ia = Math.round(a * f).toInt(); val ib = Math.round(b * f).toInt()
            enc(ia - pa); enc(ib - pb); pa = ia; pb = ib }
        return sb.toString()
    }
}
