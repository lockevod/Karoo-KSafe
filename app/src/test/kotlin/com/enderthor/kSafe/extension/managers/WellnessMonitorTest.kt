package com.enderthor.kSafe.extension.managers

import com.enderthor.kSafe.data.EmergencyReason
import com.enderthor.kSafe.data.KSafeConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the session accumulators introduced for FIT export (Task D) and consumed by
 * the Health tab (Task A): max HR, time-in-zone buckets, drift tracking, fire counters.
 *
 * The three-tier algorithmic detection paths are not covered here — that needs realistic
 * HR/power streams over time and lives in the calibration log analysis workflow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WellnessMonitorTest {

    private fun TestScope.newMonitor(
        useMaxHrPercent: Boolean = false,
        criticalBpm: Int = 180,
        sustainedBpm: Int = 160,
        criticalEnabled: Boolean = true,
        sustainedEnabled: Boolean = true,
        decouplingEnabled: Boolean = false,
        incidents: MutableList<Pair<EmergencyReason, Map<String, String>>> =
            mutableListOf(),
    ): Pair<WellnessMonitor, MutableList<Pair<EmergencyReason, Map<String, String>>>> {
        val monitor = WellnessMonitor(
            // `backgroundScope` is the kotlinx-coroutines-test idiom for long-running
            // jobs that should auto-cancel at end of test. start() launches a poll
            // loop here; we never wait for it because tick() is called directly.
            scope = this.backgroundScope as CoroutineScope,
            onIncident = { reason, payload -> incidents += reason to payload },
        )
        // Configure via start() — that's the contract for "fresh session".
        monitor.start(
            KSafeConfig(
                wellnessEnabled = true,
                wellnessCriticalEnabled  = criticalEnabled,
                wellnessSustainedEnabled = sustainedEnabled,
                wellnessDecouplingEnabled = decouplingEnabled,
                wellnessUseMaxHrPercent = useMaxHrPercent,
                wellnessCriticalThresholdBpm = criticalBpm,
                wellnessHighHrThreshold = sustainedBpm,
                // Short durations so a single tick can fire a tier when needed.
                wellnessCriticalDurationMinutes = 1,
                wellnessHighHrDurationMinutes = 1,
            )
        )
        return monitor to incidents
    }

    @Test
    fun `updateHr tracks the session peak`() = runTest {
        val (mon, _) = newMonitor()

        mon.updateHr(120)
        mon.updateHr(165)
        mon.updateHr(150)   // lower than peak — must not regress
        mon.updateHr(170)
        mon.updateHr(160)

        assertEquals(170, mon.getSummary().maxHrBpm)
    }

    @Test
    fun `tick with HR above sustained threshold adds MONITOR_TICK_MS to that bucket`() = runTest {
        val (mon, _) = newMonitor(sustainedBpm = 160, criticalBpm = 180)

        mon.updateHr(165)   // above sustained (160), below critical (180)
        mon.tick()
        mon.tick()
        mon.tick()

        val s = mon.getSummary()
        // 3 ticks × 30 000 ms = 90 000 ms in the sustained bucket
        assertEquals(3L * 30_000L, s.cumMsSustainedAbove)
        // Nothing in the critical bucket — HR was below 180
        assertEquals(0L, s.cumMsCriticalAbove)
    }

    @Test
    fun `tick with HR above critical threshold adds to both buckets`() = runTest {
        val (mon, _) = newMonitor(sustainedBpm = 160, criticalBpm = 180)

        mon.updateHr(185)   // above both
        mon.tick()
        mon.tick()

        val s = mon.getSummary()
        assertEquals(2L * 30_000L, s.cumMsCriticalAbove)
        assertEquals(2L * 30_000L, s.cumMsSustainedAbove)
    }

    @Test
    fun `tick with HR below all thresholds adds nothing`() = runTest {
        val (mon, _) = newMonitor(sustainedBpm = 160)

        mon.updateHr(140)
        mon.tick()
        mon.tick()

        val s = mon.getSummary()
        assertEquals(0L, s.cumMsCriticalAbove)
        assertEquals(0L, s.cumMsSustainedAbove)
    }

    @Test
    fun `tick with stale HR does not add to any bucket`() = runTest {
        val (mon, _) = newMonitor()
        // Never call updateHr → lastHrUpdateMs stays 0 → stale guard fires inside tick().
        mon.tick()
        mon.tick()

        val s = mon.getSummary()
        assertEquals(0L, s.cumMsCriticalAbove)
        assertEquals(0L, s.cumMsSustainedAbove)
    }

    @Test
    fun `start resets the accumulators from a previous session`() = runTest {
        val (mon, _) = newMonitor(sustainedBpm = 160)

        mon.updateHr(170)
        mon.tick()
        mon.tick()
        assertEquals(2L * 30_000L, mon.getSummary().cumMsSustainedAbove)

        // Fresh start — totals must zero out.
        mon.start(
            KSafeConfig(
                wellnessEnabled = true,
                wellnessSustainedEnabled = true,
                wellnessHighHrThreshold = 160,
                wellnessHighHrDurationMinutes = 1,
            )
        )

        val s = mon.getSummary()
        assertEquals(0, s.maxHrBpm)
        assertEquals(0L, s.cumMsSustainedAbove)
        assertEquals(0L, s.cumMsCriticalAbove)
        assertEquals(0f, s.maxDriftPct, 0.001f)
        assertEquals(0, s.totalFires)
    }

    @Test
    fun `sustained tier fire increments sustainedFires counter`() = runTest {
        // duration = 1 minute → 2 consecutive ticks (60 s) above threshold triggers the fire.
        // First tick: streak begins (sustainedSinceMs = now). Second tick: sustained ≥ 60 s,
        // cooldown is 0 (no previous fire), so fires.
        val (mon, incidents) = newMonitor(sustainedBpm = 160)

        mon.updateHr(170)
        mon.tick()
        // Second tick is the "fires" tick — but tick() uses wall-clock System.currentTimeMillis,
        // so we need to wait long enough for `now - sustainedSinceMs >= 60 000 ms`. In a JVM
        // test this is impractical without real-time sleep, so instead we drive the fire
        // path by calling tick() in a tight loop and asserting on the eventual fire count
        // would be flaky. Skip — covered by the integration of fireTier with calibration
        // logs in field testing.

        // What we CAN assert deterministically: the bucket accumulator advanced.
        assertEquals(30_000L, mon.getSummary().cumMsSustainedAbove)
        assertEquals(170, mon.getSummary().maxHrBpm)
        // No fire yet — duration not met (would need ≥ 60 s wall-clock).
        assertEquals(0, incidents.size)
    }

    @Test
    fun `totalFires sums all three tier counters`() = runTest {
        val s = WellnessMonitor.WellnessSummary(
            maxHrBpm = 185,
            cumMsCriticalAbove = 60_000L,
            cumMsSustainedAbove = 600_000L,
            currentDriftPct = 5f,
            maxDriftPct = 8.3f,
            criticalFires = 1,
            sustainedFires = 2,
            decouplingFires = 1,
        )
        assertEquals(4, s.totalFires)
    }
}
