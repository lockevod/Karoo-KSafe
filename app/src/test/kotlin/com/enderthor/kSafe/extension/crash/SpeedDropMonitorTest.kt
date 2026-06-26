package com.enderthor.kSafe.extension.crash

import com.enderthor.kSafe.extension.util.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeedDropMonitorTest {

    @Test
    fun `zero speed before any movement does not begin a window`() = runTest {
        // Standing at the start line right after pressing record must NOT arm the
        // watchdog — otherwise faffing for N minutes confirms a "crash" at the trailhead.
        val fixture = newFixture()
        fixture.monitor.start(stoppedMinutesRequired = 5)
        fixture.monitor.onSpeedUpdate(0.0)

        assertEquals("watchdog stays disarmed until the bike has moved", false, fixture.monitor.isTracking())
    }

    @Test
    fun `a stop after the bike has moved begins the window`() = runTest {
        val fixture = newFixture()
        fixture.monitor.start(stoppedMinutesRequired = 5)
        fixture.monitor.onSpeedUpdate(20.0) // rider rides off — arms the watchdog
        fixture.monitor.onSpeedUpdate(0.0)   // then stops

        assertEquals("a stop after real movement opens a window", true, fixture.monitor.isTracking())
    }

    @Test
    fun `speed rising above zero clears the window`() = runTest {
        val fixture = newFixture()
        fixture.monitor.start(5)
        fixture.monitor.onSpeedUpdate(20.0) // arm via movement
        fixture.monitor.onSpeedUpdate(0.0)
        fixture.monitor.onSpeedUpdate(5.0)

        assertEquals(false, fixture.monitor.isTracking())
    }

    @Test
    fun `eligible window plus stable accelerometer plus cooldown ok confirms once`() = runTest {
        var now = 1_000_000L
        var stillSince = 0L
        val clock = Clock { now }
        val confirms = mutableListOf<Unit>()
        val monitor = SpeedDropMonitor(
            scope = this.backgroundScope,
            clock = clock,
            accelStillSinceProvider = { stillSince },
            cooldownGate = { true },
            onConfirm = { confirms += Unit },
        )

        monitor.start(stoppedMinutesRequired = 5)
        monitor.onSpeedUpdate(20.0) // rider rides off — arms the watchdog
        monitor.onSpeedUpdate(0.0)
        stillSince = now // accel went still at the same instant

        // 5 minutes pass and the accelerometer has been stable for >= 60s
        now += 5 * 60_000L + 1_000L
        advanceTimeBy(SpeedDropMonitor.POLL_INTERVAL_MS + 1L)
        runCurrent()

        assertEquals(1, confirms.size)
        assertEquals("monitor clears its window after confirm", false, monitor.isTracking())
    }

    @Test
    fun `eligible window but accelerometer not stable does not confirm`() = runTest {
        var now = 1_000_000L
        val clock = Clock { now }
        val confirms = mutableListOf<Unit>()
        val monitor = SpeedDropMonitor(
            scope = this.backgroundScope,
            clock = clock,
            accelStillSinceProvider = { 0L },   // accelerometer never went still
            cooldownGate = { true },
            onConfirm = { confirms += Unit },
        )

        monitor.start(5)
        monitor.onSpeedUpdate(20.0) // arm via movement
        monitor.onSpeedUpdate(0.0)

        now += 5 * 60_000L + 1_000L
        advanceTimeBy(SpeedDropMonitor.POLL_INTERVAL_MS + 1L)
        runCurrent()

        assertEquals(0, confirms.size)
        assertEquals(true, monitor.isTracking()) // window still open, waiting for stable accel
    }

    @Test
    fun `cooldown gate blocks the confirm`() = runTest {
        var now = 1_000_000L
        val clock = Clock { now }
        val confirms = mutableListOf<Unit>()
        val monitor = SpeedDropMonitor(
            scope = this.backgroundScope,
            clock = clock,
            accelStillSinceProvider = { now },
            cooldownGate = { false },               // cooldown blocks
            onConfirm = { confirms += Unit },
        )

        monitor.start(5)
        monitor.onSpeedUpdate(20.0) // arm via movement
        monitor.onSpeedUpdate(0.0)

        now += 5 * 60_000L + 60_000L + 1_000L
        advanceTimeBy(SpeedDropMonitor.POLL_INTERVAL_MS + 1L)
        runCurrent()

        assertEquals(0, confirms.size)
    }

    @Test
    fun `confirm does not re-arm while the bike stays stopped`() = runTest {
        var now = 1_000_000L
        var stillSince = 0L
        val clock = Clock { now }
        val confirms = mutableListOf<Unit>()
        val monitor = SpeedDropMonitor(
            scope = this.backgroundScope,
            clock = clock,
            accelStillSinceProvider = { stillSince },
            cooldownGate = { true },
            onConfirm = { confirms += Unit },
        )

        monitor.start(stoppedMinutesRequired = 5)
        monitor.onSpeedUpdate(20.0) // rider rides off — arms the watchdog
        monitor.onSpeedUpdate(0.0)
        stillSince = now

        // First confirm at ~5 min.
        now += 5 * 60_000L + 1_000L
        advanceTimeBy(SpeedDropMonitor.POLL_INTERVAL_MS + 1L)
        runCurrent()
        assertEquals(1, confirms.size)

        // Rider cancelled and is sitting at the café — bike still motionless.
        // Feeding more zero-speed samples must NOT re-open a window: the UI promises
        // "minutes stopped before alert" (one alert per stop), not a repeat every N min.
        monitor.onSpeedUpdate(0.0)
        assertEquals("watchdog must stay disarmed while the bike has not moved", false, monitor.isTracking())

        // Another 5 minutes of standing still must NOT produce a second confirm.
        now += 5 * 60_000L + 1_000L
        advanceTimeBy(SpeedDropMonitor.POLL_INTERVAL_MS + 1L)
        runCurrent()
        assertEquals("no repeated confirm at the same stop", 1, confirms.size)
    }

    @Test
    fun `confirm re-arms after the bike moves again`() = runTest {
        var now = 1_000_000L
        var stillSince = 0L
        val clock = Clock { now }
        val confirms = mutableListOf<Unit>()
        val monitor = SpeedDropMonitor(
            scope = this.backgroundScope,
            clock = clock,
            accelStillSinceProvider = { stillSince },
            cooldownGate = { true },
            onConfirm = { confirms += Unit },
        )

        monitor.start(stoppedMinutesRequired = 5)
        monitor.onSpeedUpdate(20.0) // rider rides off — arms the watchdog
        monitor.onSpeedUpdate(0.0)
        stillSince = now
        now += 5 * 60_000L + 1_000L
        advanceTimeBy(SpeedDropMonitor.POLL_INTERVAL_MS + 1L)
        runCurrent()
        assertEquals(1, confirms.size)

        // Rider gets back on and rides away — genuine movement (>= 3.5 km/h) re-arms.
        monitor.onSpeedUpdate(20.0)
        // A new stop later in the ride opens a fresh window.
        monitor.onSpeedUpdate(0.0)
        stillSince = now
        assertEquals("a new stop after movement opens a fresh window", true, monitor.isTracking())

        now += 5 * 60_000L + 1_000L
        advanceTimeBy(SpeedDropMonitor.POLL_INTERVAL_MS + 1L)
        runCurrent()
        assertEquals("a genuinely new stop can confirm again", 2, confirms.size)
    }

    @Test
    fun `gpsStale forced-zero reading does not re-arm after a confirm`() = runTest {
        var now = 1_000_000L
        var stillSince = 0L
        val clock = Clock { now }
        val confirms = mutableListOf<Unit>()
        val monitor = SpeedDropMonitor(
            scope = this.backgroundScope,
            clock = clock,
            accelStillSinceProvider = { stillSince },
            cooldownGate = { true },
            onConfirm = { confirms += Unit },
        )

        monitor.start(stoppedMinutesRequired = 5)
        monitor.onSpeedUpdate(20.0) // rider rides off — arms the watchdog
        monitor.onSpeedUpdate(0.0)
        stillSince = now
        now += 5 * 60_000L + 1_000L
        advanceTimeBy(SpeedDropMonitor.POLL_INTERVAL_MS + 1L)
        runCurrent()
        assertEquals(1, confirms.size)

        // GPS lock lost: the SDK replays the last pre-stop speed (here a high value) but
        // gpsStale forces effective speed to 0 — this is NOT movement and must NOT re-arm.
        monitor.onSpeedUpdate(speedKmh = 20.0, gpsStale = true)
        monitor.onSpeedUpdate(0.0)
        assertEquals("a gpsStale forced-zero reading must not re-arm the watchdog", false, monitor.isTracking())

        now += 5 * 60_000L + 1_000L
        advanceTimeBy(SpeedDropMonitor.POLL_INTERVAL_MS + 1L)
        runCurrent()
        assertEquals("no re-fire while GPS is stale at the same stop", 1, confirms.size)
    }

    @Test
    fun `onPause clears the window`() = runTest {
        val fixture = newFixture()
        fixture.monitor.start(5)
        fixture.monitor.onSpeedUpdate(20.0) // arm via movement
        fixture.monitor.onSpeedUpdate(0.0)
        assertEquals(true, fixture.monitor.isTracking())

        fixture.monitor.onPause()

        assertEquals(false, fixture.monitor.isTracking())
    }

    @Test
    fun `stop cancels the job and clears the window`() = runTest {
        val fixture = newFixture()
        fixture.monitor.start(5)
        fixture.monitor.onSpeedUpdate(20.0) // arm via movement
        fixture.monitor.onSpeedUpdate(0.0)
        fixture.monitor.stop()
        assertEquals(false, fixture.monitor.isTracking())
    }

    private fun TestScope.newFixture(
        nowMs: Long = 1_000_000L,
        accelStillSinceMs: Long = 0L,
        cooldownOk: Boolean = true,
    ): Fixture {
        var currentTime = nowMs
        val clock = Clock { currentTime }
        val confirms = mutableListOf<Unit>()
        val monitor = SpeedDropMonitor(
            scope = this.backgroundScope,
            clock = clock,
            accelStillSinceProvider = { accelStillSinceMs },
            cooldownGate = { cooldownOk },
            onConfirm = { confirms += Unit },
        )
        return Fixture(monitor, confirms) { advanceMs -> currentTime += advanceMs }
    }

    private data class Fixture(
        val monitor: SpeedDropMonitor,
        val confirms: MutableList<Unit>,
        val advance: (Long) -> Unit,
    )
}
