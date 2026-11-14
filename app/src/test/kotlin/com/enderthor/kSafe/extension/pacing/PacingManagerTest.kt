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

    private fun manager(knee: Double = 3.0) =
        PacingManager(TestScope(), tmp.root, null, clock = { now }, alertSink = { alerts += it }, kneeKjKgOverride = knee)

    /** 30 km/h east along the route for [seconds] (default 20 min) starting at second [from];
     *  [power] null = no power meter; [accuracyM] of every fix. */
    private fun ride(m: PacingManager, power: Int?, accuracyM: Double, seconds: Int = 1200, from: Int = 0) {
        for (s in from until from + seconds) {
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

    @Test fun `CONSERVE already active before the window gives one candidate for the block`() {
        val m = manager(knee = 0.5)                               // CONSERVE from the first projection on
        m.onUserProfile(250, 70.0); m.onNavigation(route())
        ride(m, power = 300, accuracyM = 5.0)                    // ends ~10 m before the 10 km climb
        val blockKey = Math.round(m.blocks.single().startM)
        val pacingForBlock = m.evaluatedCandidateKeys.filter {
            it.endsWith("#$blockKey") && !it.startsWith("W#") && !it.startsWith("FUEL#") }
        assertEquals(listOf("CONSERVE#$blockKey"), pacingForBlock)
    }

    @Test fun `stop before start leaves the learned grade pace file untouched`() {
        val learned = GradePaceModel().apply { learn(BikeKind.ROAD, 0.0, 1000.0, 90.0) }   // 40 km/h, confident
        GradePaceStore(tmp.root).save(learned)
        manager().stop()
        val loaded = GradePaceStore(tmp.root).load()!!
        assertTrue(loaded.isConfident(BikeKind.ROAD, 0.0))
        assertEquals(40.0, loaded.speedKmh(BikeKind.ROAD, 0.0), 0.01)
    }

    /** Three 150 m climbs (A 1–3 km, B 9–11 km, C 20–22 km) on a ~28 km route. */
    private fun threeClimbRoute() = OnNavigationState.NavigationState.NavigatingRoute(
        routePolyline = encodeLatLng(listOf(0.0 to 0.0, 0.0 to 0.25)), routeDistance = 27_830.0,
        routeElevationPolyline = encodeElev(listOf(0.0 to 100.0, 1_000.0 to 100.0, 3_000.0 to 250.0, 9_000.0 to 250.0,
            11_000.0 to 400.0, 20_000.0 to 400.0, 22_000.0 to 550.0, 25_000.0 to 550.0)),
        rejoinPolyline = null, rejoinDistance = null, name = "t3", reversed = false, breadcrumb = false,
        pois = emptyList(), climbs = emptyList(),
    )

    @Test fun `a block renumbered by a recompute is not alerted twice`() {
        val m = manager(knee = 0.5)
        m.onUserProfile(250, 70.0); m.onNavigation(threeClimbRoute())
        assertEquals(3, m.blocks.size)                           // A–B gap ~6 km ≈ 770 s at default pace: separate
        ride(m, power = 300, accuracyM = 5.0, seconds = 2250)    // into C's lead window; CONSERVE#C already raised
        val cStart = m.blocks[2].startM
        // Learned 45 km/h: the ~5.9 km A–B gap drops to ~470 s and merges (C's id 2 → 1, same start);
        // the ~8.9 km B–C gap stays > 600 s.
        m.gradePace = GradePaceModel().apply { for (g in -5..10) learn(BikeKind.ROAD, g.toDouble(), 1000.0, 80.0) }
        m.recomputeBlocks()
        assertEquals(2, m.blocks.size); assertEquals(cStart, m.blocks[1].startM, 0.0)
        ride(m, power = 300, accuracyM = 5.0, seconds = 120, from = 2250)
        val keys = m.evaluatedCandidateKeys.filter { it.startsWith("CONSERVE#") && it != "CONSERVE#cond" }
        assertEquals(1, keys.count { it == "CONSERVE#${Math.round(cStart)}" })
        assertEquals(keys.toSet().size, keys.size)
    }

    @Test fun `stale power drops to KEEP`() {
        val m = manager(knee = 0.5)
        m.onUserProfile(250, 70.0); m.onNavigation(route())
        ride(m, power = 300, accuracyM = 5.0, seconds = 600)
        assertEquals(PacingState.CONSERVE, m.view.value!!.state)
        ride(m, power = null, accuracyM = 5.0, seconds = 20, from = 600)
        m.tick()                                                  // 20 s without power: no_power → KEEP
        assertEquals(PacingState.KEEP, m.view.value!!.state)
    }

    @Test fun `live alerts reach the sink once`() {
        val m = manager(knee = 0.5)
        m.setLiveAlerts(true, null)
        m.onUserProfile(250, 70.0); m.onNavigation(route())
        ride(m, power = 300, accuracyM = 5.0, seconds = 300)     // CONSERVE#cond only; no block window yet
        assertEquals(listOf("CONSERVE#cond"), alerts.map { it.key })
    }

    @Test fun `live alerts are blocked during an emergency`() {
        val m = manager(knee = 0.5)
        m.setLiveAlerts(true, null); m.setEmergencyActive(true)
        m.onUserProfile(250, 70.0); m.onNavigation(route())
        ride(m, power = 300, accuracyM = 5.0, seconds = 300)
        assertTrue(alerts.isEmpty())
        assertTrue(m.evaluatedCandidateKeys.isNotEmpty())        // a candidate existed and was suppressed
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

    @Test fun `a throwing tick does not kill the loop`() {
        val scope = TestScope()
        val m = PacingManager(scope, tmp.root, null, clock = { now })
        var calls = 0
        m.tickHook = { calls++; if (calls == 1) throw IllegalStateException("boom") }
        m.start()
        scope.testScheduler.advanceTimeBy(61_000); scope.testScheduler.runCurrent()
        m.stop()
        assertEquals("the loop ticked again after the first tick threw", 2, calls)
    }
}
