package com.enderthor.kSafe.extension.managers

import com.enderthor.kSafe.data.EmergencyReason
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.extension.util.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MedicalEpisodeDetector]. Drives time deterministically via an injected
 * [Clock]; never calls `start()` (the production monitor coroutine uses real `delay`s and
 * would make the suite slow and racy). Instead the tests call the internal [tick] entry
 * point directly after seeding HR / speed history through the public `updateHr` /
 * `updateSpeed` methods.
 *
 * The sub-detectors are gated on:
 *   1. HR data received (any `updateHr` before tick).
 *   2. HR not stale (last HR update less than HR_STALE_MS ago — 15 s).
 *   3. Rider was active recently (speed ≥ 5 km/h within last ACTIVE_RECENT_MS — 60 s).
 *
 * Each test snapshots what the detector should emit by inspecting a captured `onIncident`
 * lambda; absence of capture asserts the detector did not fire.
 */
class MedicalEpisodeDetectorTest {

    /** Mutable clock the tests advance step by step. */
    private class TestClock(var nowMs: Long = 0L) : Clock {
        override fun nowMs(): Long = nowMs
    }

    /** Each test fixture builds a fresh detector wrapped around a fresh capture slot. */
    private class Fixture(
        startNowMs: Long = 1_000_000L,
    ) {
        val clock = TestClock(startNowMs)
        var captured: Pair<EmergencyReason, Map<String, String>>? = null
        val detector = MedicalEpisodeDetector(
            scope = CoroutineScope(Dispatchers.Unconfined),  // unused — we never start()
            onIncident = { reason, tokens -> captured = reason to tokens },
            calibLogger = null,
            clock = clock,
        ).also { it.applyConfig(KSafeConfig(medicalEpisodeEnabled = true)) }

        /** Push a single HR reading at the current clock time. */
        fun hr(bpm: Int) {
            detector.updateHr(bpm)
        }

        /** Push a speed reading at the current clock time — anything ≥5 km/h marks rider active. */
        fun speed(kmh: Double) {
            detector.updateSpeed(kmh)
        }

        /** Advance the clock and call the detector tick. */
        fun tick(advanceMs: Long = 0L) {
            clock.nowMs += advanceMs
            detector.tick()
        }
    }

    // ── A) HR flatline sub-detector ────────────────────────────────────────────

    @Test
    fun `flatline fires when HR stays below 30 bpm for 30s while rider was recently active`() {
        val f = Fixture()
        // Mark rider as active.
        f.speed(20.0)
        // Push HR samples consistently below 30 over a 35 s span at 1 Hz, ticking every 5 s.
        for (sec in 0..35) {
            f.clock.nowMs += 1_000L
            f.hr(20)
            if (sec % 5 == 0) f.detector.tick()
        }
        assertTrue("flatline should have fired: $${f.captured}", f.captured != null)
        assertEquals(EmergencyReason.MEDICAL_FLATLINE, f.captured!!.first)
        assertEquals("20", f.captured!!.second["bpm"])
    }

    @Test
    fun `flatline does NOT fire when HR is fresh but rider has been idle longer than ACTIVE_RECENT_MS`() {
        val f = Fixture()
        // Brief activity to seed lastSpeedAboveActiveMs, then idle out beyond the 60 s window.
        f.speed(10.0)
        f.clock.nowMs += 65_000L            // > ACTIVE_RECENT_MS
        // Now feed flatline-quality HR.
        for (sec in 0..35) {
            f.clock.nowMs += 1_000L
            f.hr(20)
            if (sec % 5 == 0) f.detector.tick()
        }
        assertNull("rider idle → no fire", f.captured)
    }

    @Test
    fun `flatline does NOT fire when HR data is stale even if rider was recently active`() {
        val f = Fixture()
        f.speed(20.0)
        // Single old HR reading — the detector treats it as stale after HR_STALE_MS.
        f.hr(20)
        f.clock.nowMs += 20_000L            // 20 s — past HR_STALE_MS (15 s)
        f.detector.tick()
        assertNull("HR stale → no fire", f.captured)
    }

    @Test
    fun `flatline does NOT fire when HR is above threshold`() {
        val f = Fixture()
        f.speed(20.0)
        for (sec in 0..35) {
            f.clock.nowMs += 1_000L
            f.hr(40)                        // above HR_FLATLINE_MAX_BPM = 30
            if (sec % 5 == 0) f.detector.tick()
        }
        assertNull("HR above threshold → no fire", f.captured)
    }

    // ── B) HR collapse sub-detector ────────────────────────────────────────────

    @Test
    fun `collapse fires when current HR drops by 40+ percent vs the 4-min baseline`() {
        val f = Fixture()
        f.speed(20.0)
        // 4 minutes of baseline HR ~160 bpm at 1 Hz.
        for (i in 0 until 250) {
            f.clock.nowMs += 1_000L
            f.hr(160)
        }
        // Then 15 s of collapsed HR ~80 bpm — 50 % drop, well past the 40 % gate.
        for (i in 0 until 15) {
            f.clock.nowMs += 1_000L
            f.hr(80)
        }
        // Re-mark rider as recently active so the activity gate doesn't trip.
        f.speed(20.0)
        f.detector.tick()
        assertTrue("collapse should fire: $${f.captured}", f.captured != null)
        assertEquals(EmergencyReason.MEDICAL_COLLAPSE, f.captured!!.first)
    }

    @Test
    fun `collapse does NOT fire when history is too short (cold-start guard)`() {
        val f = Fixture()
        f.speed(20.0)
        // Only 30 s of history — far below the 4-min minimum.
        for (i in 0 until 30) {
            f.clock.nowMs += 1_000L
            f.hr(160)
        }
        for (i in 0 until 15) {
            f.clock.nowMs += 1_000L
            f.hr(60)                        // would be a huge drop
        }
        f.speed(20.0)
        f.detector.tick()
        assertNull("not enough history → no fire", f.captured)
    }

    @Test
    fun `collapse does NOT fire when drop is below 40 percent`() {
        val f = Fixture()
        f.speed(20.0)
        for (i in 0 until 250) {
            f.clock.nowMs += 1_000L
            f.hr(160)
        }
        for (i in 0 until 15) {
            f.clock.nowMs += 1_000L
            f.hr(115)                       // ~28 % drop — under the 40 % gate
        }
        f.speed(20.0)
        f.detector.tick()
        assertNull("28% drop → no fire", f.captured)
    }

    @Test
    fun `collapse cooldown blocks a second fire within the 4-min window`() {
        val f = Fixture()
        f.speed(20.0)
        // Build 4 minutes of baseline + a 50 % drop and let it fire once.
        for (i in 0 until 250) { f.clock.nowMs += 1_000L; f.hr(160) }
        for (i in 0 until 15)  { f.clock.nowMs += 1_000L; f.hr(80) }
        f.speed(20.0)
        f.detector.tick()
        val firstFire = f.captured
        assertTrue("first fire required", firstFire != null)
        f.captured = null
        // Immediately re-tick (within the cooldown) — should NOT refire even though the
        // recent-window average is still well below baseline.
        f.detector.tick()
        assertNull("cooldown should block a refire", f.captured)
    }

    // ── Universal guards ────────────────────────────────────────────────────────

    @Test
    fun `no fire when no HR data has been received at all`() {
        val f = Fixture()
        f.speed(20.0)
        // Tick repeatedly without any updateHr call — `hrDataReceived` stays false.
        repeat(10) { f.tick(5_000L) }
        assertNull("no HR data → no fire", f.captured)
    }

    @Test
    fun `stop clears HR history so the next start cannot reuse the previous baseline`() {
        val f = Fixture()
        f.speed(20.0)
        // Seed 4 min of baseline samples.
        for (i in 0 until 250) { f.clock.nowMs += 1_000L; f.hr(160) }
        f.detector.stop()
        // After stop the rolling history must be empty — verified indirectly by the
        // hasEnoughHistory guard: a single very-low HR right after stop must NOT be
        // interpreted as a "huge drop vs previous baseline".
        f.detector.applyConfig(KSafeConfig(medicalEpisodeEnabled = true))
        f.speed(20.0)
        for (i in 0 until 15) { f.clock.nowMs += 1_000L; f.hr(60) }
        f.detector.tick()
        assertNull("stop must clear baseline → no fire on the new ride", f.captured)
    }
}

/**
 * Thin wrapper so tests can mark the detector as enabled without needing to call
 * `start()` (which would launch the production monitor coroutine).
 */
private fun MedicalEpisodeDetector.applyConfig(config: KSafeConfig) {
    // The detector reads `this.config` in evaluate paths via volatile reads. Setting it
    // through the public `updateConfig` is benign because both branches (start/stop) are
    // gated on the disabled→enabled or enabled→disabled transition, and a fresh detector
    // starts with `medicalEpisodeEnabled = false` in the default KSafeConfig.
    this.updateConfig(config)
}
