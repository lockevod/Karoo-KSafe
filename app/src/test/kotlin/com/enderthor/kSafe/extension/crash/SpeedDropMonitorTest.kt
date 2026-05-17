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
    fun `start with zero speed begins the window`() = runTest {
        val fixture = newFixture()
        fixture.monitor.start(stoppedMinutesRequired = 5)
        fixture.monitor.onSpeedUpdate(0.0)

        assertEquals("monitor should be tracking after onSpeedUpdate(0.0)", true, fixture.monitor.isTracking())
    }

    @Test
    fun `speed rising above zero clears the window`() = runTest {
        val fixture = newFixture()
        fixture.monitor.start(5)
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
        monitor.onSpeedUpdate(0.0)

        now += 5 * 60_000L + 60_000L + 1_000L
        advanceTimeBy(SpeedDropMonitor.POLL_INTERVAL_MS + 1L)
        runCurrent()

        assertEquals(0, confirms.size)
    }

    @Test
    fun `onPause clears the window`() = runTest {
        val fixture = newFixture()
        fixture.monitor.start(5)
        fixture.monitor.onSpeedUpdate(0.0)
        assertEquals(true, fixture.monitor.isTracking())

        fixture.monitor.onPause()

        assertEquals(false, fixture.monitor.isTracking())
    }

    @Test
    fun `stop cancels the job and clears the window`() = runTest {
        val fixture = newFixture()
        fixture.monitor.start(5)
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
