package com.enderthor.kSafe.extension.pacing

import com.enderthor.kSafe.extension.managers.CalibrationLogger
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.OnNavigationState.NavigationState.Climb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.coroutines.CoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

class PacingManagerTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 0L
    private val alerts = mutableListOf<PacingAlert>()

    private fun route(): OnNavigationState.NavigationState.NavigatingRoute =
        OnNavigationState.NavigationState.NavigatingRoute(
            routePolyline = TestPolyline.latLng(listOf(0.0 to 0.0, 0.0 to 0.2)),          // ~22 km east
            routeDistance = 22_264.0,
            routeElevationPolyline = TestPolyline.elev(listOf(0.0 to 100.0, 10_000.0 to 100.0, 15_000.0 to 450.0, 22_264.0 to 450.0)),
            rejoinPolyline = null, rejoinDistance = null, name = "t", reversed = false, breadcrumb = false,
            pois = emptyList(), climbs = emptyList(),
        )

    private fun manager(knee: Double = 3.0) =
        PacingManager(TestScope(), tmp.root, null, clock = { now }, alertSink = { alerts.add(it) }, kneeKjKgOverride = knee)

    /** Rows the manager logged, as "TAG,data". */
    private val logRows = mutableListOf<String>()

    private fun loggingManager(knee: Double = 3.0): PacingManager {
        val log = Mockito.mock(CalibrationLogger::class.java)
        Mockito.`when`(log.isEnabled).thenReturn(true)
        Mockito.doAnswer { logRows += "${(it.arguments[0] as CalibrationLogger.Event).tag},${it.arguments[1]}"; null }
            .`when`(log).addEntry(Mockito.any(CalibrationLogger.Event::class.java) ?: CalibrationLogger.Event.PACE_ADV, Mockito.anyString())
        return PacingManager(TestScope(), tmp.root, log, clock = { now }, alertSink = { alerts.add(it) }, kneeKjKgOverride = knee)
    }

    /** 30 km/h east along the route for [seconds] (default 20 min) starting at second [from];
     *  [power] null = no power meter; [hr] null = no HR strap; [accuracyM] of every fix. */
    private fun ride(m: PacingManager, power: Int?, accuracyM: Double, seconds: Int = 1200, from: Int = 0, hr: Int? = 150) {
        for (s in from until from + seconds) {
            now += 1000; m.onSpeed(30.0); power?.let { m.onPower(it) }; hr?.let { m.onHr(it) }
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
        routePolyline = TestPolyline.latLng(listOf(0.0 to 0.0, 0.0 to 0.25)), routeDistance = 27_830.0,
        routeElevationPolyline = TestPolyline.elev(listOf(0.0 to 100.0, 1_000.0 to 100.0, 3_000.0 to 250.0, 9_000.0 to 250.0,
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
        ride(m, power = 300, accuracyM = 5.0, seconds = 660)    // projections start after 600 s of power
        assertEquals(PacingState.CONSERVE, m.view.value!!.state)
        ride(m, power = null, accuracyM = 5.0, seconds = 20, from = 660)
        m.tick()                                                  // 20 s without power: no_power → KEEP
        assertEquals(PacingState.KEEP, m.view.value!!.state)
    }

    @Test fun `live alerts reach the sink once`() {
        val m = manager(knee = 0.5)
        m.setLiveAlerts(true, null)
        m.onUserProfile(250, 70.0); m.onNavigation(route())
        ride(m, power = 300, accuracyM = 5.0, seconds = 750)     // CONSERVE from 600 s, #cond 90 s later; no block window yet
        assertEquals(listOf("CONSERVE#cond"), alerts.map { it.key })
    }

    @Test fun `a block alert carries the distance ahead, not the route position`() {
        val m = manager(knee = 0.5)
        m.onUserProfile(250, 70.0); m.onNavigation(route())
        ride(m, power = 300, accuracyM = 5.0, seconds = 750)     // shadow: CONSERVE#cond is spent here
        m.setLiveAlerts(true, null)
        ride(m, power = 300, accuracyM = 5.0, seconds = 450, from = 750)   // ends ~10 m before the 10 km climb
        val a = alerts.single { it.key == "CONSERVE#${Math.round(m.blocks.single().startM)}" }
        // Fired in the lead window ~2 km out; the climb itself starts at ~9.9 km on the route.
        assertTrue("blockKm=${a.blockKm}", a.blockKm!! in 0.5..4.0)
    }

    /** Rides 1 s at a time until the published state is CONSERVE; returns the next second. */
    private fun rideUntilConserve(m: PacingManager, power: Int, from: Int, hr: Int = 150, limit: Int = 3000): Int {
        var s = from
        while (m.view.value?.state != PacingState.CONSERVE) {
            check(s < limit) { "never reached CONSERVE" }
            ride(m, power = power, accuracyM = 5.0, seconds = 1, from = s, hr = hr); s++
        }
        return s
    }

    @Test fun `a cafe stop inside the sustain window does not fire CONSERVE cond`() {
        val m = manager(knee = 0.5)
        m.setLiveAlerts(true, null)
        m.onUserProfile(250, 70.0); m.onNavigation(route())
        var s = rideUntilConserve(m, power = 300, from = 0)       // CONSERVE entered at T
        ride(m, power = 300, accuracyM = 5.0, seconds = 20, from = s); s += 20   // T+20 s: the rider stops
        val lng = (s * 8.33) / 111_320.0
        for (i in 1..100) {                                       // stopped past T+90 s (ticks at T+50/80/110)
            now += 1000; m.onSpeed(0.0); m.onPower(0); m.onHr(100); m.onLocation(0.0, lng, 5.0)
            if (i % 30 == 0) m.tick()
        }
        assertTrue("no cond alert to a stopped rider: ${alerts.map { it.key }}", alerts.none { it.key == "CONSERVE#cond" })
        // Back on the bike: the sustain clock restarts, so the cond alert still comes after 90 s of riding.
        ride(m, power = 300, accuracyM = 5.0, seconds = 150, from = s)
        assertEquals(listOf("CONSERVE#cond"), alerts.map { it.key })
    }

    @Test fun `drift-held CONSERVE off route does not fire CONSERVE cond`() {
        val m = manager(knee = 30.0)                              // the projection never crosses: drift only
        m.setLiveAlerts(true, null)
        m.onUserProfile(250, 70.0); m.onNavigation(longRoute())
        ride(m, power = 175, accuracyM = 5.0, seconds = 905)     // HR 150 baseline at 900 s
        val s = rideUntilConserve(m, power = 175, from = 905, hr = 180)   // ~17 % decoupling: drift_high
        ride(m, power = 175, accuracyM = 100.0, seconds = 150, from = s, hr = 180)   // lost position, drift holds CONSERVE
        assertEquals(PacingState.CONSERVE, m.view.value!!.state)
        assertTrue("no cond alert off route: ${alerts.map { it.key }}", alerts.none { it.key == "CONSERVE#cond" })
    }

    private fun carbDeficit(): com.enderthor.kSafe.extension.managers.CarbStatus =
        Mockito.mock(com.enderthor.kSafe.extension.managers.CarbStatus::class.java).also {
            Mockito.`when`(it.carbsEnabled).thenReturn(true); Mockito.`when`(it.masterEnabled).thenReturn(true)
            Mockito.`when`(it.cumLoggedG).thenReturn(40); Mockito.`when`(it.cumBurnedG).thenReturn(140)
            Mockito.`when`(it.deficitG).thenReturn(100); Mockito.`when`(it.deficitThresholdG).thenReturn(30)
        }

    @Test fun `one tick fires at most one alert, the most important first`() {
        // Shadow run: find the second whose tick raises the block's CONSERVE candidate.
        val probe = manager(knee = 0.5)
        probe.onUserProfile(250, 70.0); probe.onNavigation(route())
        val key = "CONSERVE#${Math.round(probe.blocks.single().startM)}"
        var s = 0
        while (key !in probe.evaluatedCandidateKeys) { check(s < 1200); ride(probe, power = 200, accuracyM = 5.0, seconds = 1, from = s); s++ }
        val tickSecond = s - 1
        // Live run, same ride; the fuel hint becomes due on that same tick. #cond is dropped by the sink
        // (see the test below) so the pacing rate does not hide the block alert.
        now = 0; alerts.clear()
        val m = PacingManager(TestScope(), tmp.root, null, clock = { now },
            alertSink = { a -> (a.key != "CONSERVE#cond").also { if (it) alerts += a } }, kneeKjKgOverride = 0.5)
        m.setLiveAlerts(true, null)
        m.onUserProfile(250, 70.0); m.onNavigation(route())
        ride(m, power = 200, accuracyM = 5.0, seconds = tickSecond)
        m.onCarbStatus(carbDeficit())
        ride(m, power = 200, accuracyM = 5.0, seconds = 1, from = tickSecond)
        val fuelKey = "FUEL#${key.substringAfter('#')}"
        val tickKeys = m.evaluatedCandidateKeys.takeLast(2)
        assertEquals(listOf(key, fuelKey), tickKeys)                         // PACING before FUEL_HINT
        assertEquals(listOf(key), alerts.map { it.key })                     // the fuel hint is gapped
    }

    @Test fun `an alert the sink drops is not recorded as fired`() {
        // CONSERVE#cond is dropped by the sink (e.g. emergency re-check); had it been recorded, the
        // pacing spacing would suppress the block alert that follows (see the live-sink tests).
        val m = PacingManager(TestScope(), tmp.root, null, clock = { now },
            alertSink = { a -> (a.key != "CONSERVE#cond").also { if (it) alerts += a } }, kneeKjKgOverride = 0.5)
        m.setLiveAlerts(true, null)
        m.onUserProfile(250, 70.0); m.onNavigation(route())
        ride(m, power = 300, accuracyM = 5.0)                    // ends ~10 m before the 10 km climb
        assertTrue("CONSERVE#cond" in m.evaluatedCandidateKeys)
        assertEquals(listOf("CONSERVE#${Math.round(m.blocks.single().startM)}"), alerts.map { it.key })
    }

    @Test fun `live alerts are blocked during an emergency`() {
        val m = manager(knee = 0.5)
        m.setLiveAlerts(true, null); m.setEmergencyActive(true)
        m.onUserProfile(250, 70.0); m.onNavigation(route())
        ride(m, power = 300, accuracyM = 5.0, seconds = 750)
        assertTrue(alerts.isEmpty())
        assertTrue(m.evaluatedCandidateKeys.isNotEmpty())        // a candidate existed and was suppressed
    }

    @Test fun `an SOS still retrying after its ALERTING window blocks live alerts`() {
        val m = PacingManager(TestScope(), tmp.root, null, clock = { now }, alertSink = { alerts.add(it) },
            kneeKjKgOverride = 0.5, emergencyProbe = { true })   // status IDLE, alert job alive
        m.setLiveAlerts(true, null)
        m.onUserProfile(250, 70.0); m.onNavigation(route())
        ride(m, power = 300, accuracyM = 5.0, seconds = 750)
        assertTrue(alerts.isEmpty())
        assertTrue(m.evaluatedCandidateKeys.isNotEmpty())
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

    @Test fun `heart rate goes unknown when the strap stops sending`() {
        val m = manager()
        m.onUserProfile(250, 70.0); m.onNavigation(route())
        ride(m, power = 175, accuracyM = 5.0, seconds = 15 * 60 + 1)          // baseline window at 15 min
        ride(m, power = 175, accuracyM = 5.0, seconds = 6 * 60, from = 15 * 60 + 1, hr = null)
        assertNull("a window without fresh HR must be discarded", m.fatigue!!.driftPct(now))
    }

    /** ~44.5 km: flat to 35 km, then one 350 m climb (35–40 km). */
    private fun longRoute() = OnNavigationState.NavigationState.NavigatingRoute(
        routePolyline = TestPolyline.latLng(listOf(0.0 to 0.0, 0.0 to 0.4)), routeDistance = 44_528.0,
        routeElevationPolyline = TestPolyline.elev(listOf(0.0 to 100.0, 35_000.0 to 100.0, 40_000.0 to 450.0, 44_528.0 to 450.0)),
        rejoinPolyline = null, rejoinDistance = null, name = "long", reversed = false, breadcrumb = false,
        pois = emptyList(), climbs = emptyList(),
    )

    @Test fun `steady endurance ride reaches HIGH_MARGIN`() {         // control for the coverage gate below
        val m = manager(knee = 30.0)
        m.onUserProfile(250, 70.0); m.onNavigation(longRoute())
        ride(m, power = 175, accuracyM = 5.0, seconds = 2700)
        assertEquals(PacingState.HIGH_MARGIN, m.view.value!!.state)
    }

    @Test fun `a long power gap blocks HIGH_MARGIN`() {
        val m = manager(knee = 30.0)
        m.onUserProfile(250, 70.0); m.onNavigation(longRoute())
        ride(m, power = 175, accuracyM = 5.0, seconds = 600)
        ride(m, power = null, accuracyM = 5.0, seconds = 600, from = 600)     // 10 min of unmetered riding
        ride(m, power = 175, accuracyM = 5.0, seconds = 1500, from = 1200)
        assertEquals(PacingState.KEEP, m.view.value!!.state)                  // power covers ~78 % of moving time
    }

    @Test fun `no projection before ten minutes of power`() {
        val m = manager()                                                     // knee 3
        m.onUserProfile(250, 70.0); m.onNavigation(route())
        ride(m, power = 600, accuracyM = 5.0, seconds = 20)                   // a 20 s sprint off the start
        m.tick()
        assertEquals(PacingState.KEEP, m.view.value!!.state)
    }

    @Test fun `a weight edit rescales kJ per kg instead of wiping the ride`() {
        val m = loggingManager()
        m.onUserProfile(250, 70.0); m.onNavigation(route())
        ride(m, power = 200, accuracyM = 5.0)
        val before = m.fatigue!!.kjPerKg; val movingBefore = m.fatigue!!.movingSeconds
        m.onUserProfile(250, 80.0)
        assertEquals(before * 70.0 / 80.0, m.fatigue!!.kjPerKg, 1e-9)
        assertEquals(movingBefore, m.fatigue!!.movingSeconds, 0.0)
        assertTrue(logRows.any { it.startsWith("PACE_RESET,") && "reason=weight" in it })
    }

    @Test fun `an FTP edit keeps work and moving time`() {
        val m = loggingManager()
        m.onUserProfile(250, 70.0); m.onNavigation(route())
        ride(m, power = 200, accuracyM = 5.0)
        val kj = m.fatigue!!.kjPerKg; val moving = m.fatigue!!.movingSeconds
        m.onUserProfile(260, 70.0)
        assertEquals(kj, m.fatigue!!.kjPerKg, 0.0); assertEquals(moving, m.fatigue!!.movingSeconds, 0.0)
        assertTrue(logRows.any { it.startsWith("PACE_RESET,") && "reason=ftp" in it })
    }

    @Test fun `a bike-kind change keeps the W' balance and its stale FTP flag`() {
        val m = loggingManager()
        m.onUserProfile(250, 70.0); m.onRoutingPreference("ROAD"); m.onNavigation(route())
        ride(m, power = 300, accuracyM = 5.0, seconds = 1500)    // 20-min mean above FTP: stale
        val wp = m.wPrime!!; assertTrue(wp.ftpStale)
        m.onRoutingPreference("MTB")
        assertTrue(m.wPrime === wp); assertTrue(m.wPrime!!.ftpStale)
        assertTrue(logRows.any { it.startsWith("PACE_RESET,") && "reason=kind" in it })
    }

    @Test fun `a bike-kind change before any power takes the new default W'`() {
        val m = manager()
        m.onUserProfile(250, 70.0); m.onRoutingPreference("ROAD")
        m.onRoutingPreference("MTB")
        assertEquals(WPrimeBalance.defaultWPrimeJ(BikeKind.MTB), m.wPrime!!.wJ, 0.0)
    }

    @Test fun `unknown weight logs kJ per kg as na`() {
        val m = loggingManager()
        m.onUserProfile(250, 0.0); m.onNavigation(route()); m.start()
        ride(m, power = 400, accuracyM = 5.0, seconds = 700)     // W′ drains: recover_w flips, a PACE_ADV row is logged
        m.stop(endOfRide = true)
        val adv = logRows.filter { it.startsWith("PACE_ADV,") }
        assertTrue(adv.isNotEmpty())
        assertTrue(adv.all { ",kjkg=na," in it })
        assertTrue(logRows.single { it.startsWith("PACE_END,") }.contains("actual_kjkg=na"))
    }

    @Test fun `a new route restarts the projected-finish record`() {
        val m = loggingManager()
        m.onUserProfile(250, 70.0); m.onNavigation(route()); m.start()
        ride(m, power = 300, accuracyM = 5.0, seconds = 700)     // route A projects a finish
        m.onNavigation(threeClimbRoute())
        ride(m, power = 300, accuracyM = 100.0, seconds = 60, from = 700)   // route B: never trusted
        m.stop(endOfRide = true)
        assertTrue(logRows.single { it.startsWith("PACE_END,") }.contains("proj_finish_first=na,proj_finish_last=na"))
    }

    @Test fun `a route with the same polyline but other climbs is reloaded`() {
        val m = manager()
        m.onUserProfile(250, 70.0)
        m.onNavigation(route().copy(climbs = listOf(Climb(10_000.0, 5_000.0, 7.0, 350.0))))
        assertEquals(10_000.0, m.blocks.single().startM, 0.0)
        m.onNavigation(route().copy(climbs = listOf(Climb(12_000.0, 3_000.0, 7.0, 210.0))))
        assertEquals(12_000.0, m.blocks.single().startM, 0.0)
    }

    @Test fun `a same-ride resume keeps the ride and only the real ride end writes PACE_END`() {
        val m = loggingManager()
        m.onUserProfile(250, 70.0); m.onNavigation(route()); m.start()
        ride(m, power = 200, accuracyM = 5.0, seconds = 300)
        val kj = m.fatigue!!.kj; val odo = m.odometerM
        m.stop(reason = "logging_off")
        m.start(resume = true)
        assertEquals(kj, m.fatigue!!.kj, 0.0); assertEquals(odo, m.odometerM, 0.0)
        ride(m, power = 200, accuracyM = 5.0, seconds = 60, from = 300)
        m.stop(endOfRide = true)
        assertEquals(listOf("reason=fresh", "reason=resume"),
            logRows.filter { it.startsWith("PACE_START,") }.map { it.substringAfter(',').substringBefore(',') })
        assertTrue(logRows.single { it.startsWith("PACE_STOP,") }.contains("reason=logging_off"))
        assertEquals(1, logRows.count { it.startsWith("PACE_END,") })
    }

    @Test fun `a resume after the ride ended starts fresh`() {
        val m = loggingManager()
        m.onUserProfile(250, 70.0); m.onNavigation(route()); m.start()
        ride(m, power = 200, accuracyM = 5.0, seconds = 300)
        m.stop(endOfRide = true)
        m.start(resume = true)
        assertEquals(0.0, m.fatigue!!.kj, 0.0); assertEquals(0.0, m.odometerM, 0.0)
        assertEquals("reason=fresh", logRows.filter { it.startsWith("PACE_START,") }.last().substringAfter(',').substringBefore(','))
    }

    @Test fun `a ride end after a mid-ride stop still writes one PACE_END`() {
        val m = loggingManager()
        m.onUserProfile(250, 70.0); m.onNavigation(route()); m.start()
        ride(m, power = 200, accuracyM = 5.0, seconds = 120)
        m.stop(reason = "master_off")
        m.stop(endOfRide = true)
        m.stop(endOfRide = true)
        assertEquals(1, logRows.count { it.startsWith("PACE_END,") })
        assertEquals(1, logRows.count { it.startsWith("PACE_STOP,") })
    }

    /** A manager whose scope and IO dispatcher run on one test scheduler; [ioDispatches] counts IO hops. */
    private var ioDispatches = 0
    private fun ioManager(scope: TestScope): PacingManager {
        val inner = StandardTestDispatcher(scope.testScheduler)
        val io = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { ioDispatches++; inner.dispatch(context, block) }
        }
        return PacingManager(scope, tmp.root, null, clock = { now }, ioDispatcher = io)
    }

    private fun learned40() = GradePaceModel().apply { learn(BikeKind.ROAD, 0.0, 1000.0, 90.0) }   // 40 km/h, confident

    @Test fun `the learned model is loaded on IO and applied before learning`() {
        GradePaceStore(tmp.root).save(learned40())
        val scope = TestScope(); val m = ioManager(scope)
        m.start()
        assertFalse("start must not read the file on the caller thread", m.gradePace.isConfident(BikeKind.ROAD, 0.0))
        scope.testScheduler.runCurrent()
        assertTrue(ioDispatches > 0)
        assertEquals(40.0, m.gradePace.speedKmh(BikeKind.ROAD, 0.0), 0.01)
        m.stop(endOfRide = true)
    }

    @Test fun `the model is saved every ten minutes of moving time`() {
        val scope = TestScope(); val m = ioManager(scope)
        m.onUserProfile(250, 70.0); m.onNavigation(route()); m.start()
        scope.testScheduler.runCurrent()
        ride(m, power = 200, accuracyM = 5.0, seconds = 11 * 60)
        val before = ioDispatches
        scope.testScheduler.runCurrent()
        assertTrue(ioDispatches > before)
        assertTrue(GradePaceStore(tmp.root).load()!!.isConfident(BikeKind.ROAD, 0.0))
    }

    @Test fun `an unreadable model file survives the ride as bad`() {
        java.io.File(tmp.root, GradePaceStore.FILE_NAME).writeText("{nope")
        val scope = TestScope(); val m = ioManager(scope)
        m.onUserProfile(250, 70.0); m.onNavigation(route()); m.start()
        scope.testScheduler.runCurrent()
        ride(m, power = 200, accuracyM = 5.0, seconds = 120)
        m.stop(endOfRide = true); scope.testScheduler.runCurrent()
        assertEquals("{nope", java.io.File(tmp.root, "${GradePaceStore.FILE_NAME}.bad").readText())
        assertNotNull(GradePaceStore(tmp.root).load())                       // the fresh model was saved beside it
    }

    @Test fun `power without speed is not moving and never lifts coverage over one`() {
        val m = manager(knee = 30.0)
        m.onUserProfile(250, 70.0); m.onNavigation(longRoute())
        ride(m, power = 175, accuracyM = 5.0, seconds = 600)
        ride(m, power = null, accuracyM = 5.0, seconds = 600, from = 600)     // coverage ~50 %: KEEP
        for (s in 1200 until 2700) {                                         // speed sensor drops, power keeps coming
            now += 1000; m.onPower(175); m.onHr(150); m.onLocation(0.0, (1200 * 8.33) / 111_320.0, 5.0)
            if (s % 30 == 0) { m.tick(); assertTrue(m.powerCoverage()!! <= 1.0); assertTrue(m.view.value!!.state != PacingState.HIGH_MARGIN) }
        }
    }

    @Test fun `a W' override change waits for the next fresh start`() {
        val m = manager()
        m.onUserProfile(250, 70.0); m.onNavigation(route()); m.start()
        ride(m, power = 300, accuracyM = 5.0, seconds = 120)
        val wp = m.wPrime!!
        m.setLiveAlerts(false, 30_000.0)
        assertTrue("no silent mid-ride W' rebuild", m.wPrime === wp)
        m.stop(endOfRide = true); m.start()
        assertEquals(30_000.0, m.wPrime!!.wJ, 0.0)
    }

    @Test fun `climb blocks recompute on the next tick after a bike-kind change`() {
        val m = manager()
        m.onUserProfile(250, 70.0); m.onRoutingPreference("ROAD")
        m.onNavigation(route().copy(climbs = listOf(Climb(10_000.0, 1_500.0, 6.7, 100.0))))   // MTB-significant only
        assertEquals(0, m.blocks.size)
        now += 1000
        m.onRoutingPreference("MTB")
        m.tick()
        assertEquals(1, m.blocks.size)
    }

    @Test fun `a model file that cannot be moved aside is never overwritten`() {
        val f = java.io.File(tmp.root, GradePaceStore.FILE_NAME).apply { writeText("{nope") }
        val scope = TestScope(); val m = ioManager(scope)
        m.onUserProfile(250, 70.0); m.onNavigation(route())
        tmp.root.setWritable(false)
        try { m.start(); scope.testScheduler.runCurrent() } finally { tmp.root.setWritable(true) }
        ride(m, power = 200, accuracyM = 5.0, seconds = 11 * 60)              // past the periodic save point
        m.stop(endOfRide = true); scope.testScheduler.runCurrent()
        assertEquals("{nope", f.readText())
    }
}
