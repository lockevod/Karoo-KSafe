package com.enderthor.kSafe.extension.managers

import android.content.Context
import com.enderthor.kSafe.data.HydFuelingState
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.extension.util.CalibrationInput
import com.enderthor.kSafe.extension.util.CalibrationRejection
import com.enderthor.kSafe.extension.util.CalibrationResult
import com.enderthor.kSafe.extension.util.Clock
import com.enderthor.kSafe.extension.util.FuelingAlertRequest
import com.enderthor.kSafe.extension.util.SweatEstimateInputs
import com.enderthor.kSafe.extension.util.calibrate
import com.enderthor.kSafe.extension.util.coverageOk
import com.enderthor.kSafe.extension.util.estimateSweatRate
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

/**
 * Production-path tests for [HydrationTracker]: real `start` / `stop` / `resume` / log paths,
 * driven by a fake [Clock] and [HydrationTracker.tickForTest]. The monitor coroutine is
 * launched on a [StandardTestDispatcher] that is never advanced, so only explicit ticks run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HydrationTrackerTest {

    private class TestClock(var now: Long = 1_000_000_000L) : Clock {
        override fun nowMs(): Long = now
    }

    private val TICK_MS = 15_000L
    private val HOUR_MS = 3_600_000L

    private val clock = TestClock()
    private val karoo: KarooSystemService = mock(KarooSystemService::class.java)
    private val alerts = mutableListOf<FuelingAlertRequest>()
    private var recording = true
    private var rideTime: Long? = null
    private var speedToggle = false

    private val tracker = HydrationTracker(
        scope = TestScope(StandardTestDispatcher()),
        karooSystem = karoo,
        context = mock(Context::class.java),
        onFuelingAlert = { alerts += it },
        isEmergencyActive = { false },
        isRecording = { recording },
        rideTimeMs = { rideTime },
        clock = clock,
    )

    /** Alerts off by default: these tests are about the accumulators, not the scheduler. */
    private fun cfg(dynamic: Boolean = true, alerts: Boolean = false) = KSafeConfig(
        hydrationTrackerEnabled = true,
        hydrationDynamicEstimateEnabled = dynamic,
        hydrationDeficitAlertEnabled = alerts,
        hydrationTimeAlertEnabled = false,
    )

    /** One 15-s tick while moving (speed alternates so the GPS-stale gate never trips). */
    private fun step(hr: Boolean = true) {
        clock.now += TICK_MS
        speedToggle = !speedToggle
        tracker.updateSpeed(if (speedToggle) 20.0 else 20.5)
        if (hr) tracker.updateHr(150)
        tracker.tickForTest()
    }

    private fun ride(ms: Long, hr: Boolean = true) = repeat((ms / TICK_MS).toInt()) { step(hr) }

    /** Fresh session plus the anchor tick (the first tick after start never integrates). */
    private fun startRide(config: KSafeConfig = cfg(), restore: HydFuelingState? = null) {
        tracker.start(config, restore)
        tracker.updateAmbientTemp(25.0)   // HR + temp = MEDIUM confidence
        step()
    }

    @Test
    fun `paused time does not integrate even if moving`() {
        startRide()
        ride(5 * 60_000L)
        val before = tracker.getPersistableState()
        assertTrue(before.coveredMs > 0)

        recording = false
        ride(10 * 60_000L)
        assertEquals(before, tracker.getPersistableState())

        // The Recording transition re-anchors: its own tick integrates nothing, the next one
        // integrates exactly one tick — no paused span leaks into dt.
        recording = true
        step()
        assertEquals(before.coveredMs, tracker.getPersistableState().coveredMs)
        step()
        assertEquals(before.coveredMs + TICK_MS, tracker.getPersistableState().coveredMs)
    }

    @Test
    fun `static to dynamic toggle mid-ride keeps accumulating sweat`() {
        startRide(cfg(dynamic = false))
        ride(30 * 60_000L)
        val mid = tracker.getPersistableState()
        assertTrue("static mode accumulates sweat", mid.cumSweatMl > 100f)
        assertEquals(750f * 0.5f, mid.cumTargetMl, 5f)

        tracker.updateConfig(cfg(dynamic = true), isRecording = true)
        ride(30 * 60_000L)
        val end = tracker.getPersistableState()
        assertEquals(2 * mid.cumSweatMl, end.cumSweatMl, mid.cumSweatMl * 0.02f)
        assertEquals(HOUR_MS, end.coveredMs)
        // Dynamic half integrates 0.8 × sweat on top of the static half.
        assertEquals(mid.cumTargetMl + 0.8f * (end.cumSweatMl - mid.cumSweatMl), end.cumTargetMl, 2f)
    }

    @Test
    fun `hr dropout counts as low confidence`() {
        startRide()
        ride(10 * 60_000L)
        val before = tracker.getPersistableState()
        ride(10 * 60_000L, hr = false)
        val after = tracker.getPersistableState()
        // The tick exactly SENSOR_STALE_MS after the last HR sample still counts as fresh.
        assertTrue(after.coveredMs - before.coveredMs <= TICK_MS)
        assertTrue(after.lowConfMs - before.lowConfMs >= 10 * 60_000L - TICK_MS)
    }

    @Test
    fun `legacy restore has zero coverage`() {
        startRide(restore = HydFuelingState(
            cumTargetMl = 900f, cumLoggedMl = 600, sessionStartMs = clock.now - HOUR_MS,
        ))
        ride(HOUR_MS)
        rideTime = 2 * HOUR_MS
        val snap = tracker.lastRideSnapshot()!!
        assertTrue(snap.cumSweatMl > 0f)
        assertEquals(0.5, snap.coveredMs.toDouble() / snap.rideTimeMs, 0.01)
        assertFalse(coverageOk(snap.coveredMs, snap.rideTimeMs))
        assertTrue(tracker.getPersistableState().cumTargetMl > 900f)
    }

    @Test
    fun `one hour disable gap leaves coverage below threshold`() {
        startRide()
        ride(HOUR_MS)
        tracker.stop()
        clock.now += HOUR_MS
        tracker.resume(cfg())
        step()                          // re-anchor tick after resume integrates nothing
        ride(HOUR_MS)
        rideTime = 3 * HOUR_MS
        val snap = tracker.lastRideSnapshot()!!
        assertEquals(2.0 / 3.0, snap.coveredMs.toDouble() / snap.rideTimeMs, 0.01)
        assertFalse(coverageOk(snap.coveredMs, snap.rideTimeMs))
    }

    @Test
    fun `replacement fraction drives deficit rate`() {
        startRide()
        ride(HOUR_MS)
        val s = tracker.getPersistableState()
        assertEquals(0.8f * s.cumSweatMl, s.cumTargetMl, s.cumSweatMl * 0.01f)
        // Displayed rate is the drink rate (sweat × mult × repl), not the sweat rate.
        val sweat = estimateSweatRate(SweatEstimateInputs(hrBpm = 150, ambientTempC = 25.0)).mlPerHour
        assertEquals((sweat * 0.8).toInt().toFloat(), tracker.getStatus().currentRateMlPerHour.toFloat(), 1f)
        assertEquals(s.cumSweatMl.toInt(), tracker.getStatus().cumSweatMl)
    }

    @Test
    fun `undo after log keeps accumulators consistent`() {
        startRide()
        ride(10 * 60_000L)
        val before = tracker.getPersistableState()
        assertEquals(100, tracker.logEntry(1))
        assertEquals(before.cumLoggedMl + 100, tracker.getPersistableState().cumLoggedMl)
        assertEquals(100, tracker.undoLastForSlot(1))
        val after = tracker.getPersistableState()
        assertEquals(before.cumLoggedMl, after.cumLoggedMl)
        assertEquals(before.cumSweatMl, after.cumSweatMl)
        assertEquals(before.cumTargetMl, after.cumTargetMl)
    }

    @Test
    fun `ride ended while ride time is unknown is rejected as NO_RIDE_TIME`() {
        // Master switch OFF mid-ride tears down the ELAPSED_TIME collector and clears ride time.
        startRide()
        rideTime = 2 * HOUR_MS
        ride(2 * HOUR_MS)
        tracker.stop()
        rideTime = null
        val snap = tracker.lastRideSnapshot()!!
        assertEquals(0L, snap.rideTimeMs)
        val input = CalibrationInput(70.0, 69.0, 600, 0, false, snap.endedAtMs)
        assertEquals(
            CalibrationResult.Rejected(CalibrationRejection.NO_RIDE_TIME),
            calibrate(snap, snap.rideId, input, emptyList()),
        )
    }

    @Test
    fun `lastRideSnapshot is null without a live session`() {
        assertNull("no session yet", tracker.lastRideSnapshot())
        startRide()
        ride(10 * 60_000L)
        assertNotNull(tracker.lastRideSnapshot())
        tracker.stop(endOfSession = true)
        assertNull("after end of session", tracker.lastRideSnapshot())
        // Ride B with hydration disabled must not re-snapshot ride A's totals.
        tracker.start(cfg().copy(hydrationTrackerEnabled = false))
        assertNull("ride B with hydration disabled", tracker.lastRideSnapshot())
    }

    @Test
    fun `shadow never dispatches`() {
        rideTime = 0L
        startRide(cfg(alerts = true))     // deficit alert live: it must stay silent too
        tracker.logAmount(3000)           // logged far above any plausible sweat
        repeat((2 * HOUR_MS / TICK_MS).toInt()) {
            rideTime = rideTime!! + TICK_MS
            step()
        }
        assertTrue("shadow level recorded", tracker.getPersistableState().overShadowLevel > 0)
        verifyNoInteractions(karoo)
        assertTrue(alerts.isEmpty())
    }
}
