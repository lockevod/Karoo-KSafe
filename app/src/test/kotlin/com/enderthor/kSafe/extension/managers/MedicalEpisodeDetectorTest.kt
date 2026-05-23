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
        assertTrue("flatline should have fired: ${f.captured}", f.captured != null)
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
        // H2 — the COLLAPSE detector now requires a concurrent, non-stale speed signal.
        // Stream speed in lockstep with HR, varying the value so the GPS-stale check
        // (which keys off bit-exact identical values) does not trip.
        for (i in 0 until 250) {
            f.clock.nowMs += 1_000L
            f.hr(160)
            f.speed(20.0 + (i % 5) * 0.1)
        }
        for (i in 0 until 15) {
            f.clock.nowMs += 1_000L
            f.hr(80)                        // 50 % drop, well past the 40 % gate
            f.speed(20.0 + (i % 5) * 0.1)
        }
        f.detector.tick()
        assertTrue("collapse should fire: ${f.captured}", f.captured != null)
        assertEquals(EmergencyReason.MEDICAL_COLLAPSE, f.captured!!.first)
    }

    @Test
    fun `collapse does NOT fire when history is too short (cold-start guard)`() {
        val f = Fixture()
        // Only 30 s of history — far below the 4-min minimum. Stream speed in lockstep
        // so the test exercises the history guard, not the H2 concurrent-speed gate.
        for (i in 0 until 30) {
            f.clock.nowMs += 1_000L
            f.hr(160)
            f.speed(20.0 + (i % 5) * 0.1)
        }
        for (i in 0 until 15) {
            f.clock.nowMs += 1_000L
            f.hr(60)                        // would be a huge drop
            f.speed(20.0 + (i % 5) * 0.1)
        }
        f.detector.tick()
        assertNull("not enough history → no fire", f.captured)
    }

    @Test
    fun `collapse does NOT fire when drop is below 40 percent`() {
        val f = Fixture()
        for (i in 0 until 250) {
            f.clock.nowMs += 1_000L
            f.hr(160)
            f.speed(20.0 + (i % 5) * 0.1)
        }
        for (i in 0 until 15) {
            f.clock.nowMs += 1_000L
            f.hr(115)                       // ~28 % drop — under the 40 % gate
            f.speed(20.0 + (i % 5) * 0.1)
        }
        f.detector.tick()
        assertNull("28% drop → no fire", f.captured)
    }

    @Test
    fun `collapse cooldown blocks a second fire within the 4-min window`() {
        val f = Fixture()
        // Build 4 minutes of baseline + a 50 % drop and let it fire once. Stream speed
        // continuously so the H2 concurrent-speed gate is satisfied during both ticks.
        for (i in 0 until 250) {
            f.clock.nowMs += 1_000L
            f.hr(160)
            f.speed(20.0 + (i % 5) * 0.1)
        }
        for (i in 0 until 15) {
            f.clock.nowMs += 1_000L
            f.hr(80)
            f.speed(20.0 + (i % 5) * 0.1)
        }
        f.detector.tick()
        val firstFire = f.captured
        assertTrue("first fire required", firstFire != null)
        f.captured = null
        // Immediately re-tick (within the cooldown) — should NOT refire even though the
        // recent-window average is still well below baseline.
        f.detector.tick()
        assertNull("cooldown should block a refire", f.captured)
    }

    // ── H2 — MEDICAL_COLLAPSE concurrent speed gate ─────────────────────────────

    @Test
    fun `H2 - collapse does NOT fire when the rider has just stopped at a cafe`() {
        // 4 min of riding at 160 bpm / 20 km/h, then the rider arrives at a café and
        // stops. HR drops to 80 bpm over 60 s as parasympathetic recovery kicks in —
        // that's a 50 % drop, easily past the 40 % gate. The pre-H2 detector would
        // fire MEDICAL_COLLAPSE here (real false-positive path) because lastSpeedAboveActiveMs
        // was set within the past 60 s. With H2 the concurrent gate requires speed ≥ 5 km/h
        // RIGHT NOW — and the rider is stationary, so the detector must NOT fire.
        val f = Fixture()
        for (i in 0 until 250) {
            f.clock.nowMs += 1_000L
            f.hr(160)
            f.speed(20.0 + (i % 5) * 0.1)
        }
        // Rider stops — speed drops to 0 and stays there. HR decays from 160 → 80.
        for (i in 0 until 60) {
            f.clock.nowMs += 1_000L
            // Linear HR fade from 160 down to ~80 over 60 s.
            val bpm = (160 - (i + 1) * 80 / 60).coerceAtLeast(80)
            f.hr(bpm)
            f.speed(0.0)
        }
        f.detector.tick()
        assertNull(
            "post-effort café stop must not fire COLLAPSE — concurrent speed gate (H2) blocks: ${f.captured}",
            f.captured,
        )
    }

    @Test
    fun `H2 - collapse still fires when the HR drop happens while riding`() {
        // Same HR trajectory as the café-stop test, but the rider stays at ≥20 km/h
        // throughout (e.g. a real vasovagal syncope while still pedalling downhill).
        // The H2 fix must preserve this true-positive path.
        val f = Fixture()
        for (i in 0 until 250) {
            f.clock.nowMs += 1_000L
            f.hr(160)
            f.speed(20.0 + (i % 5) * 0.1)
        }
        for (i in 0 until 60) {
            f.clock.nowMs += 1_000L
            val bpm = (160 - (i + 1) * 80 / 60).coerceAtLeast(80)
            f.hr(bpm)
            f.speed(20.0 + (i % 5) * 0.1)   // still moving — vary value so speed stays fresh
        }
        f.detector.tick()
        assertTrue(
            "mid-ride HR collapse must still fire: ${f.captured}",
            f.captured != null,
        )
        assertEquals(EmergencyReason.MEDICAL_COLLAPSE, f.captured!!.first)
    }

    @Test
    fun `H2 - collapse does NOT fire when speed signal is GPS-stale`() {
        // Same HR drop, rider is supposedly moving (lastSpeedKmh ≥ ACTIVE_SPEED_KMH)
        // but the speed value is bit-exact identical across every emission — the SDK's
        // GPS-lost signature. H2's isSpeedSignalStale must treat this as "not concurrently
        // active" and suppress the fire (bias toward FP reduction over true-positive
        // recall during GPS outages).
        val f = Fixture()
        // Seed the baseline normally so we get to evaluateCollapse on the final tick.
        for (i in 0 until 250) {
            f.clock.nowMs += 1_000L
            f.hr(160)
            f.speed(20.0 + (i % 5) * 0.1)
        }
        // From here on, hold speed at a stuck 20.0 — bit-exact identical → SPEED_STALE_MS
        // (10 s) trip threshold reached within the 60 s recent window.
        for (i in 0 until 60) {
            f.clock.nowMs += 1_000L
            val bpm = (160 - (i + 1) * 80 / 60).coerceAtLeast(80)
            f.hr(bpm)
            f.speed(20.0)                   // stuck — H2 isSpeedSignalStale → true
        }
        f.detector.tick()
        assertNull(
            "GPS-stale speed signal must block COLLAPSE: ${f.captured}",
            f.captured,
        )
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
        // Seed 4 min of baseline samples — stream speed in lockstep so the COLLAPSE
        // detector's H2 concurrent-speed gate stays satisfied during the seeding phase.
        for (i in 0 until 250) {
            f.clock.nowMs += 1_000L
            f.hr(160)
            f.speed(20.0 + (i % 5) * 0.1)
        }
        f.detector.stop()
        // After stop the rolling history must be empty — verified indirectly by the
        // hasEnoughHistory guard: a single very-low HR right after stop must NOT be
        // interpreted as a "huge drop vs previous baseline".
        f.detector.applyConfig(KSafeConfig(medicalEpisodeEnabled = true))
        for (i in 0 until 15) {
            f.clock.nowMs += 1_000L
            f.hr(60)
            f.speed(20.0 + (i % 5) * 0.1)   // satisfy H2 gate so the history clear is what's exercised
        }
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
    // Pass isRecording = false so the auto-start branch never fires under tests — tests
    // that need an active monitor call start() directly. See the updateConfig contract.
    this.updateConfig(config, isRecording = false)
}
