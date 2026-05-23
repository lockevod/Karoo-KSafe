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
    fun `single tick above sustained threshold does not yet fire`() = runTest {
        // Honest version of what this test ACTUALLY covers: the accumulator advances by
        // one MONITOR_TICK_MS step but the sustained-duration condition (60 s with our
        // 1-min config) is not satisfied. No fire. The fire path itself depends on
        // System.currentTimeMillis advancing across multiple ticks, which is awkward to
        // simulate deterministically in a JVM unit test — exercised in field testing via
        // the calibration log instead.
        val (mon, incidents) = newMonitor(sustainedBpm = 160)

        mon.updateHr(170)
        mon.tick()

        assertEquals(30_000L, mon.getSummary().cumMsSustainedAbove)
        assertEquals(170, mon.getSummary().maxHrBpm)
        assertEquals(0, incidents.size)
        assertEquals(0, mon.getSummary().sustainedFires)
    }

    // ── HE1: decoupling baseline-stability guard ─────────────────────────────

    /** Builds a monitor with the decoupling tier enabled and rewinds `sessionStartMs` so
     *  the establishment-time condition (`now - sessionStartMs >= 10 min`) is already met.
     *  Tests can then drive a single tick to evaluate baseline establishment. */
    private fun TestScope.newDecouplingMonitor():
        Pair<WellnessMonitor, MutableList<Pair<EmergencyReason, Map<String, String>>>>
    {
        val incidents = mutableListOf<Pair<EmergencyReason, Map<String, String>>>()
        val monitor = WellnessMonitor(
            scope = this.backgroundScope as CoroutineScope,
            onIncident = { reason, payload -> incidents += reason to payload },
        )
        monitor.start(
            KSafeConfig(
                wellnessEnabled = true,
                wellnessCriticalEnabled = false,
                wellnessSustainedEnabled = false,
                wellnessDecouplingEnabled = true,
            )
        )
        // Rewind session start so the "wait 10 min" gate has passed but we're still well
        // under the 25-min hard defer cap.
        monitor.setSessionStartForTest(System.currentTimeMillis() - 11L * 60_000L)
        return monitor to incidents
    }

    @Test
    fun `decoupling baseline establishes when power is stable`() = runTest {
        val (mon, _) = newDecouplingMonitor()
        // Pre-load the ratio buffer with >= DECOUPLING_MIN_SAMPLES (8) ticks of fresh data
        // at steady HR/power so the rolling 5-min average is well-formed.
        mon.updateHr(140)
        // Stable power: 200 W ± a few watts — CV well below the 0.30 threshold.
        repeat(60) { mon.updatePower(200 + (it % 5)) }
        repeat(10) { mon.tick() }

        // Baseline must be frozen (non-zero), no decoupling alert fired yet.
        assertEquals(true, mon.decouplingBaselineForTest() > 0f)
    }

    @Test
    fun `decoupling baseline deferred when power is unstable in establishment window`() = runTest {
        val (mon, incidents) = newDecouplingMonitor()
        mon.updateHr(140)
        // Bouncing power: 80, 320, 80, 320, ... → mean ~200 W, stddev ~120 W → CV ~0.6,
        // well above the 0.30 stability threshold.
        repeat(60) { mon.updatePower(if (it % 2 == 0) 80 else 320) }
        repeat(10) { mon.tick() }

        // Baseline must NOT be established (still 0). No alert fired.
        assertEquals(0f, mon.decouplingBaselineForTest(), 0.0001f)
        assertEquals(0, incidents.size)
    }

    @Test
    fun `decoupling baseline does not establish without a power signal`() = runTest {
        val (mon, _) = newDecouplingMonitor()
        mon.updateHr(140)
        // No updatePower calls — rider has no power meter. `evaluateDecouplingTier`
        // returns at the `lastPowerW ?: return` gate; ratio buffer stays empty, baseline
        // never establishes. This matches the legacy behaviour for power-less riders and
        // confirms the HE1 guard is bypassed cleanly when there's no power data to gate on.
        repeat(10) { mon.tick() }

        assertEquals(0f, mon.decouplingBaselineForTest(), 0.0001f)
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
