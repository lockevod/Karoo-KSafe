package com.enderthor.kSafe.extension.managers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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

    private fun TestScope.newFixture(
        nowMs: Long = 1_000_000L,
        accelStillSinceMs: Long = 0L,
        cooldownOk: Boolean = true,
    ): Fixture {
        var currentTime = nowMs
        val clock = Clock { currentTime }
        val confirms = mutableListOf<Unit>()
        val monitor = SpeedDropMonitor(
            scope = this,
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
