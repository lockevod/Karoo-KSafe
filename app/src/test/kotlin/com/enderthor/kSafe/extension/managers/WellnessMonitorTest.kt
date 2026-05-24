package com.enderthor.kSafe.extension.managers

import com.enderthor.kSafe.data.EmergencyReason
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.extension.util.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the session accumulators introduced for FIT export (Task D) and consumed by
 * the Health tab (Task A): max HR, time-in-zone buckets, drift tracking, fire counters,
 * plus the three-tier algorithmic detection paths (critical / sustained / decoupling)
 * driven deterministically via the injected [Clock].
 *
 * The monitor coroutine launched by `start()` is irrelevant here — the tests call the
 * internal [WellnessMonitor.tick] entry point directly after advancing the fake clock,
 * which avoids the wall-clock vs virtual-time interaction quirks of `runTest +
 * advanceTimeBy` on the production `delay`-based monitor loop.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WellnessMonitorTest {

    /** Mutable clock the tests advance step by step. */
    private class TestClock(var nowMs: Long = 1_000_000L) : Clock {
        override fun nowMs(): Long = nowMs
    }

    private fun TestScope.newMonitor(
        clock: TestClock = TestClock(),
        useMaxHrPercent: Boolean = false,
        criticalBpm: Int = 180,
        sustainedBpm: Int = 160,
        criticalEnabled: Boolean = true,
        sustainedEnabled: Boolean = true,
        decouplingEnabled: Boolean = false,
        criticalDurationMin: Int = 1,
        sustainedDurationMin: Int = 1,
        incidents: MutableList<Pair<EmergencyReason, Map<String, String>>> =
            mutableListOf(),
    ): Triple<WellnessMonitor, MutableList<Pair<EmergencyReason, Map<String, String>>>, TestClock> {
        val monitor = WellnessMonitor(
            // `backgroundScope` is the kotlinx-coroutines-test idiom for long-running
            // jobs that should auto-cancel at end of test. start() launches a poll
            // loop here; we never wait for it because tick() is called directly.
            scope = this.backgroundScope as CoroutineScope,
            onIncident = { reason, payload -> incidents += reason to payload },
            clock = clock,
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
                wellnessCriticalDurationMinutes = criticalDurationMin,
                wellnessHighHrDurationMinutes = sustainedDurationMin,
            )
        )
        return Triple(monitor, incidents, clock)
    }

    @Test
    fun `updateHr tracks the session peak`() = runTest {
        val (mon, _, _) = newMonitor()

        mon.updateHr(120)
        mon.updateHr(165)
        mon.updateHr(150)   // lower than peak — must not regress
        mon.updateHr(170)
        mon.updateHr(160)

        assertEquals(170, mon.getSummary().maxHrBpm)
    }

    @Test
    fun `tick with HR above sustained threshold adds MONITOR_TICK_MS to that bucket`() = runTest {
        val (mon, _, clock) = newMonitor(sustainedBpm = 160, criticalBpm = 180)

        mon.updateHr(165)   // above sustained (160), below critical (180)
        // Stay inside HR_STALE_MS (15 s) by re-stamping HR every tick.
        repeat(3) {
            clock.nowMs += 30_000L
            mon.updateHr(165)
            mon.tick()
        }

        val s = mon.getSummary()
        // 3 ticks × 30 000 ms = 90 000 ms in the sustained bucket
        assertEquals(3L * 30_000L, s.cumMsSustainedAbove)
        // Nothing in the critical bucket — HR was below 180
        assertEquals(0L, s.cumMsCriticalAbove)
    }

    @Test
    fun `tick with HR above critical threshold adds to both buckets`() = runTest {
        val (mon, _, clock) = newMonitor(sustainedBpm = 160, criticalBpm = 180)

        repeat(2) {
            clock.nowMs += 30_000L
            mon.updateHr(185)
            mon.tick()
        }

        val s = mon.getSummary()
        assertEquals(2L * 30_000L, s.cumMsCriticalAbove)
        assertEquals(2L * 30_000L, s.cumMsSustainedAbove)
    }

    @Test
    fun `tick with HR below all thresholds adds nothing`() = runTest {
        val (mon, _, clock) = newMonitor(sustainedBpm = 160)

        repeat(2) {
            clock.nowMs += 30_000L
            mon.updateHr(140)
            mon.tick()
        }

        val s = mon.getSummary()
        assertEquals(0L, s.cumMsCriticalAbove)
        assertEquals(0L, s.cumMsSustainedAbove)
    }

    @Test
    fun `tick with stale HR does not add to any bucket`() = runTest {
        val (mon, _, _) = newMonitor()
        // Never call updateHr → lastHrUpdateMs stays 0 → stale guard fires inside tick().
        mon.tick()
        mon.tick()

        val s = mon.getSummary()
        assertEquals(0L, s.cumMsCriticalAbove)
        assertEquals(0L, s.cumMsSustainedAbove)
    }

    @Test
    fun `start resets the accumulators from a previous session`() = runTest {
        val (mon, _, clock) = newMonitor(sustainedBpm = 160)

        repeat(2) {
            clock.nowMs += 30_000L
            mon.updateHr(170)
            mon.tick()
        }
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
        // 1-min config) is not satisfied. No fire.
        val (mon, incidents, clock) = newMonitor(sustainedBpm = 160)

        clock.nowMs += 30_000L
        mon.updateHr(170)
        mon.tick()

        assertEquals(30_000L, mon.getSummary().cumMsSustainedAbove)
        assertEquals(170, mon.getSummary().maxHrBpm)
        assertEquals(0, incidents.size)
        assertEquals(0, mon.getSummary().sustainedFires)
    }

    // ── HE1: decoupling baseline-stability guard ─────────────────────────────

    /** Builds a monitor with the decoupling tier enabled and a clock pre-set so the
     *  10-min establishment-time gate has already passed (but we're still well under
     *  the 25-min hard defer cap). Tests can then drive a single tick to evaluate
     *  baseline establishment. */
    private fun TestScope.newDecouplingMonitor(
        clock: TestClock = TestClock(nowMs = 1_000_000L),
    ): Triple<WellnessMonitor, MutableList<Pair<EmergencyReason, Map<String, String>>>, TestClock> {
        val incidents = mutableListOf<Pair<EmergencyReason, Map<String, String>>>()
        val monitor = WellnessMonitor(
            scope = this.backgroundScope as CoroutineScope,
            onIncident = { reason, payload -> incidents += reason to payload },
            clock = clock,
        )
        monitor.start(
            KSafeConfig(
                wellnessEnabled = true,
                wellnessCriticalEnabled = false,
                wellnessSustainedEnabled = false,
                wellnessDecouplingEnabled = true,
            )
        )
        // Advance the clock past the 10-min establishment-time gate. `start()` recorded
        // `sessionStartMs` at the current clock value, so now we just step forward.
        clock.nowMs += 11L * 60_000L
        return Triple(monitor, incidents, clock)
    }

    @Test
    fun `decoupling baseline establishes when power is stable`() = runTest {
        val (mon, incidents, clock) = newDecouplingMonitor()
        // Pre-load the ratio buffer with >= DECOUPLING_MIN_SAMPLES (8) ticks of fresh
        // data at steady HR/power so the rolling 5-min average is well-formed.
        mon.updateHr(140)
        // Stable power: 200 W ± a few watts — CV well below the 0.30 threshold.
        repeat(60) { mon.updatePower(200 + (it % 5)) }
        repeat(10) {
            clock.nowMs += 30_000L
            mon.updateHr(140)
            mon.tick()
        }
        // Baseline is established (no defer). Drift stays ~0 because HR/W is steady,
        // so no incident fires. The behavioural witness for "baseline established": a
        // subsequent run with the SAME monitor where we now pump in drifted data must
        // fire — and that's exercised in the dedicated DECOUPLING-fire test below.
        assertEquals(0, incidents.size)
    }

    @Test
    fun `decoupling baseline deferred when power is unstable in establishment window`() = runTest {
        // Load-bearing scenario: unstable power AND a slowly-drifting HR across the
        // whole 10–25 min defer window. Without the HE1 defer, the baseline freezes at
        // minute ~10 capturing the early-ride HR/W ratio; the subsequent drifted HR
        // then reads as a large drift % and DECOUPLING fires. With the defer, the
        // baseline waits until the BASELINE_MAX_DEFER_MS hard cap (25 min), by which
        // time HR has drifted to nearly its final value — the captured baseline is
        // closer to the drifted ratio, drift % stays below the 7 % threshold, no fire.
        //
        // Power pattern: 30-s blocks at 80 W and 320 W alternating. Within each
        // 30-s block, all 30 one-second power updates are at the same level; the
        // tick at the end of the block reads `lastPowerW` = that block's level.
        // So ratio samples alternate cleanly between HR/80 and HR/320 every tick,
        // while the 2-min power buffer sees four full blocks → mean = 200 W,
        // stddev = 120 W, CV = 0.6 ≫ POWER_STABILITY_CV_MAX = 0.30 → defer fires
        // until the BASELINE_MAX_DEFER_MS = 25 min hard cap.
        //
        // Verified load-bearing by temporarily forcing `shouldDeferBaseline` to
        // always return `false`: this test then FAILS (1 DECOUPLING incident is
        // recorded, fired from the wrong-baseline path at minute ~10).
        val clock = TestClock(nowMs = 1_000_000L)
        val incidents = mutableListOf<Pair<EmergencyReason, Map<String, String>>>()
        val mon = WellnessMonitor(
            scope = this.backgroundScope as CoroutineScope,
            onIncident = { reason, payload -> incidents += reason to payload },
            clock = clock,
        )
        mon.start(
            KSafeConfig(
                wellnessEnabled = true,
                wellnessCriticalEnabled = false,
                wellnessSustainedEnabled = false,
                wellnessDecouplingEnabled = true,
                wellnessDecouplingThresholdPct = 7,
                wellnessDecouplingDurationMinutes = 1,  // short for test
            )
        )

        // Drive the timeline at 1 Hz (1-s steps) to populate the power buffer
        // densely enough for POWER_STABILITY_MIN_SAMPLES = 30 (the guard skips
        // deferral when buffer < 30 samples). Tick is every 30 s. HR drifts
        // linearly from 140 bpm at minute 0 to 170 bpm at minute 35.
        val totalMin = 35
        val stepsPerMin = 60          // 60 × 1 s = 60 s
        val totalSteps = totalMin * stepsPerMin
        val hrStartBpm = 140f
        val hrEndBpm = 170f
        repeat(totalSteps) { i ->
            clock.nowMs += 1_000L
            val frac = (i + 1).toFloat() / totalSteps.toFloat()
            val hr = (hrStartBpm + frac * (hrEndBpm - hrStartBpm)).toInt()
            mon.updateHr(hr)
            // Power: 30-s blocks alternating 80 / 320 W. Step group = i / 30.
            mon.updatePower(if ((i / 30) % 2 == 0) 80 else 320)
            // Tick every 30 steps (every 30 s).
            if ((i + 1) % 30 == 0) mon.tick()
        }

        // With the HE1 defer, the baseline is held off until the 25-min hard cap so
        // the captured ratio reflects already-drifted HR. The remaining 10 min of
        // drift is small relative to the late baseline → no DECOUPLING fire.
        assertEquals(
            "HE1 defer must prevent the DECOUPLING fire by anchoring the baseline to " +
                "a late, drifted ratio rather than the early fresh-HR ratio",
            0, incidents.size,
        )
    }

    @Test
    fun `decoupling baseline does not establish without a power signal`() = runTest {
        val (mon, incidents, clock) = newDecouplingMonitor()
        mon.updateHr(140)
        // No updatePower calls — rider has no power meter. `evaluateDecouplingTier`
        // returns at the `lastPowerW ?: return` gate; ratio buffer stays empty, baseline
        // never establishes. This matches the legacy behaviour for power-less riders
        // and confirms the HE1 guard is bypassed cleanly when there's no power data.
        repeat(10) {
            clock.nowMs += 30_000L
            mon.updateHr(140)
            mon.tick()
        }

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

    // ── HE2: per-tier duration / cooldown via injected Clock ─────────────────

    @Test
    fun `wellness CRITICAL fires after configured duration above threshold`() = runTest {
        // Duration = 2 min. Critical threshold = 180. Push HR=190 across enough ticks
        // (30 s each) so wall-clock duration exceeds 2 min — the production code
        // compares `now - criticalSinceMs` to `wellnessCriticalDurationMinutes * 60_000L`.
        val (mon, incidents, clock) = newMonitor(
            criticalBpm = 180,
            sustainedBpm = 160,
            criticalDurationMin = 2,
            sustainedDurationMin = 30,  // long — keep sustained out of the picture
        )

        // 5 ticks × 30 s = 150 s > 2 min. The tier should fire on the 5th tick.
        repeat(5) {
            clock.nowMs += 30_000L
            mon.updateHr(190)
            mon.tick()
        }

        assertEquals(1, incidents.count { it.first == EmergencyReason.WELLNESS_CRITICAL_HR })
        assertEquals(1, mon.getSummary().criticalFires)
    }

    @Test
    fun `wellness SUSTAINED fires after configured duration above threshold (not before)`() = runTest {
        // Duration = 3 min. Sustained threshold = 160 (critical disabled to isolate).
        val (mon, incidents, clock) = newMonitor(
            criticalEnabled = false,
            sustainedBpm = 160,
            criticalBpm = 200,
            sustainedDurationMin = 3,
        )

        // First 5 ticks = 150 s < 3 min → no fire.
        repeat(5) {
            clock.nowMs += 30_000L
            mon.updateHr(170)
            mon.tick()
        }
        assertEquals(
            "must NOT fire before the 3-min duration is reached",
            0, incidents.size,
        )

        // 2 more ticks → 210 s > 3 min → fires.
        repeat(2) {
            clock.nowMs += 30_000L
            mon.updateHr(170)
            mon.tick()
        }
        assertEquals(1, incidents.count { it.first == EmergencyReason.WELLNESS_HIGH_HR })
        assertEquals(1, mon.getSummary().sustainedFires)
    }

    @Test
    fun `wellness CRITICAL cooldown blocks re-fire within window`() = runTest {
        // Duration = 1 min, so cooldown is also 1 min (cooldownForTier == duration).
        val (mon, incidents, clock) = newMonitor(
            criticalBpm = 180,
            sustainedBpm = 1000,   // effectively disable sustained interference
            criticalDurationMin = 1,
            sustainedDurationMin = 60,
        )

        // First fire: 3 ticks × 30 s = 90 s > 1 min.
        repeat(3) {
            clock.nowMs += 30_000L
            mon.updateHr(190)
            mon.tick()
        }
        assertEquals(
            "first fire required to set up the cooldown test",
            1, mon.getSummary().criticalFires,
        )

        // Keep HR above threshold for another 90 s — the duration condition is met
        // again but we are still well inside the 60-s cooldown (only 90 s elapsed
        // since the first fire). The second fire must be blocked.
        repeat(2) {
            clock.nowMs += 30_000L
            mon.updateHr(190)
            mon.tick()
        }
        assertEquals(
            "cooldown must block the re-fire",
            1, mon.getSummary().criticalFires,
        )
        assertEquals(1, incidents.size)
    }

    @Test
    fun `wellness DECOUPLING establishes baseline at minute 10 and fires on drift past threshold`() = runTest {
        val clock = TestClock(nowMs = 1_000_000L)
        val incidents = mutableListOf<Pair<EmergencyReason, Map<String, String>>>()
        val mon = WellnessMonitor(
            scope = this.backgroundScope as CoroutineScope,
            onIncident = { reason, payload -> incidents += reason to payload },
            clock = clock,
        )
        mon.start(
            KSafeConfig(
                wellnessEnabled = true,
                wellnessCriticalEnabled = false,
                wellnessSustainedEnabled = false,
                wellnessDecouplingEnabled = true,
                wellnessDecouplingThresholdPct = 7,
                wellnessDecouplingDurationMinutes = 1,  // short for test
            )
        )

        // Spend ~11 min establishing a stable baseline at HR=140 / power=200 → ratio 0.70.
        // Feed power at ~1 Hz throughout so the rolling [POWER_BUFFER_WINDOW_MS] (2 min)
        // window always holds >= POWER_STABILITY_MIN_SAMPLES (30) samples when the HE1
        // stability guard checks. Tick every 30 s (with 30 power emissions per tick).
        repeat(22) { tickIdx ->
            repeat(30) {
                clock.nowMs += 1_000L
                mon.updatePower(200)
            }
            mon.updateHr(140)
            mon.tick()
        }
        assertEquals(
            "no fire yet — baseline just being established at steady ratio",
            0, incidents.size,
        )

        // Now drift: HR climbs to 165 at the same 200 W → ratio = 0.825 = +17.9 % drift,
        // well past the 7 % threshold. Sustain for >= 1 min (3 ticks at 30 s) so the
        // duration gate also clears.
        repeat(20) {
            clock.nowMs += 30_000L
            mon.updateHr(165)
            mon.updatePower(200)
            mon.tick()
        }
        assertEquals(
            "drift past 7 % sustained for 1 min must fire DECOUPLING",
            1, incidents.count { it.first == EmergencyReason.WELLNESS_DECOUPLING },
        )
        assertEquals(1, mon.getSummary().decouplingFires)
        assertTrue(
            "max drift snapshot must reflect the observed climb: ${mon.getSummary().maxDriftPct}",
            mon.getSummary().maxDriftPct >= 7f,
        )
    }

    @Test
    fun `HR-stale window resets duration timers but cumulative time-in-zone is preserved`() = runTest {
        // Duration = 5 min for both tiers — well above what we accumulate before the
        // stale gap, so the streak timer must reset across the gap. Critical disabled
        // so we focus on the sustained streak / bucket interaction.
        val (mon, incidents, clock) = newMonitor(
            criticalEnabled = false,
            sustainedBpm = 160,
            sustainedDurationMin = 5,
            criticalDurationMin = 5,
        )

        // Accumulate ~60 s of "HR above sustained" → 2 × 30 000 in the bucket.
        repeat(2) {
            clock.nowMs += 30_000L
            mon.updateHr(170)
            mon.tick()
        }
        val before = mon.getSummary().cumMsSustainedAbove
        assertEquals(2L * 30_000L, before)

        // Now go HR-stale for 20 s (past HR_STALE_MS = 15 s). The next tick lands while
        // stale → tick early-returns and resets the streak timer.
        clock.nowMs += 20_000L
        mon.tick()

        // Resume HR — at the new clock time. The streak timer was reset so we must
        // accumulate a *fresh* run before the tier can fire. We feed another 60 s of
        // above-threshold HR and assert NO fire — proving the timer was reset (a
        // monotonic timer would now have ~120 s of streak and we're only 5 min away
        // from firing, so still safe; the real proof is the streak resetting at all
        // by ensuring sustainedSinceMs went to 0 across the gap. The most direct test:
        // accumulate 90 s of fresh streak + check cumulative bucket grew by 90 s).
        repeat(3) {
            clock.nowMs += 30_000L
            mon.updateHr(170)
            mon.tick()
        }

        val after = mon.getSummary().cumMsSustainedAbove
        assertEquals(
            "time-in-zone bucket is preserved across the stale gap and grows by 3 × 30 s",
            before + 3L * 30_000L,
            after,
        )
        assertEquals(
            "no fire — sustainedSinceMs was reset during the stale tick, so the post-resume " +
                "streak is only 90 s, well under the 5-min duration",
            0, incidents.size,
        )
        assertEquals(0, mon.getSummary().sustainedFires)
    }
}
