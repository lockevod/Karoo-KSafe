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
        // Keep speed emissions alive (with explicit 0.0 — rider crashed, no movement) so
        // the H2-extended FLATLINE staleness guard sees a fresh speed signal. In production
        // the Karoo SDK emits speed at ~1 Hz regardless of motion; without these calls the
        // test simulates "GPS dead" which the guard correctly treats as inactive.
        for (sec in 0..35) {
            f.clock.nowMs += 1_000L
            f.hr(20)
            f.speed(0.0)
            if (sec % 5 == 0) f.detector.tick()
        }
        assertTrue("flatline should have fired: ${f.captured}", f.captured != null)
        assertEquals(EmergencyReason.MEDICAL_FLATLINE, f.captured!!.first)
        assertEquals("20", f.captured!!.second["bpm"])
    }

    @Test
    fun `flatline fires only ONCE while HR stays low — recovery latch blocks re-fire until HR rises`() {
        val f = Fixture()
        // Keep the rider continuously active with a fresh, changing speed signal (>= 5 km/h)
        // throughout, so re-firing is governed by the recovery latch, not the activity gate.
        fun feed(seconds: Int, bpm: Int) {
            for (sec in 0 until seconds) {
                f.clock.nowMs += 1_000L
                f.hr(bpm)
                f.speed(if (sec % 2 == 0) 20.0 else 21.0)
                f.detector.tick()
            }
        }

        // Episode 1: HR <30 sustained for 30 s → fires once.
        feed(35, 20)
        assertEquals(EmergencyReason.MEDICAL_FLATLINE, f.captured?.first)

        // HR stays stuck low for another 90 s (a dead/loose strap) → must NOT re-fire.
        f.captured = null
        feed(90, 20)
        assertNull("stuck-low HR must not re-fire FLATLINE while the recovery latch is held", f.captured)

        // HR recovers to/above the threshold → latch clears.
        feed(5, 70)

        // A genuine NEW drop after recovery → must fire again.
        f.captured = null
        feed(35, 20)
        assertEquals(
            "a new sustained drop after HR recovery must fire again",
            EmergencyReason.MEDICAL_FLATLINE, f.captured?.first,
        )
    }

    @Test
    fun `flatline recovery clears the latch even while the rider is idle — recovery is gated on HR, not speed`() {
        val f = Fixture()
        fun feedActive(seconds: Int, bpm: Int) {
            for (sec in 0 until seconds) {
                f.clock.nowMs += 1_000L
                f.hr(bpm)
                f.speed(if (sec % 2 == 0) 20.0 else 21.0)
                f.detector.tick()
            }
        }

        // Episode 1 fires (rider active).
        feedActive(35, 20)
        assertEquals(EmergencyReason.MEDICAL_FLATLINE, f.captured?.first)

        // Rider STOPS (no active speed → the activity gate would block FLATLINE eval) but HR
        // recovers to 70 for 70 s. The recovery latch must still clear, because the clear is at
        // the top of evaluateFlatline (gated on HR alone). Pre-fix, the clear lived only in the
        // gated else branch, so this idle recovery left the latch stuck.
        f.captured = null
        for (sec in 0 until 70) {
            f.clock.nowMs += 1_000L
            f.hr(70)
            f.speed(0.0)            // not active
            f.detector.tick()
        }

        // Rider resumes and immediately has a genuine sustained-low episode → must fire again.
        f.captured = null
        feedActive(35, 20)
        assertEquals(
            "recovery while idle must clear the latch so a later real episode still fires",
            EmergencyReason.MEDICAL_FLATLINE, f.captured?.first,
        )
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
    fun `flatline does NOT fire when speed signal is stale (GPS-lost replay)`() {
        // H2-extended FLATLINE guard: GPS lost in a tunnel/forest, SDK keeps emitting
        // the last-known non-zero speed bit-exact. lastSpeedAboveActiveMs keeps getting
        // refreshed (kmh >= ACTIVE_SPEED_KMH) but speedLastChangeMs goes stale because
        // the value never changes. Without the guard, sweat dropping HR strap under
        // 30 bpm fires a false MEDICAL_FLATLINE EMERGENCY for a healthy seated rider.
        val f = Fixture()
        f.speed(18.0)
        for (sec in 0..35) {
            f.clock.nowMs += 1_000L
            f.hr(20)
            f.speed(18.0)               // stuck non-zero replay — never changes
            if (sec % 5 == 0) f.detector.tick()
        }
        assertNull(
            "stuck-speed GPS replay must suppress FLATLINE: ${f.captured}",
            f.captured,
        )
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
    fun `collapse does NOT fire right after a Paused-Recording resume - start clears the HR baseline`() {
        val f = Fixture()
        val cfg = KSafeConfig(medicalEpisodeEnabled = true)
        // Build a full high-HR baseline + active speed (the firing test above proves this
        // setup + a low-HR drop fires COLLAPSE).
        for (i in 0 until 250) {
            f.clock.nowMs += 1_000L
            f.hr(160)
            f.speed(20.0 + (i % 5) * 0.1)
        }
        // Simulate an autopause→resume: KSafeExtension calls start() unconditionally on
        // every Recording entry (no preceding stop on the resume path). start() MUST clear
        // the 5-min HR ring so the pre-pause 160 bpm baseline can't contaminate the
        // post-resume comparison. Without the reset, the low remount HR below reads as a
        // ~50% drop vs the stale 160 baseline and fires a FALSE MEDICAL_COLLAPSE (a bogus
        // EMERGENCY SOS to the rider's contacts). This pins that resetSessionState() runs.
        f.detector.start(cfg)
        // Remount at low HR — only a short burst of fresh history exists after the reset.
        for (i in 0 until 15) {
            f.clock.nowMs += 1_000L
            f.hr(80)
            f.speed(20.0 + (i % 5) * 0.1)
        }
        f.detector.tick()
        assertNull("resume must start from a clean HR baseline — no false COLLAPSE: ${f.captured}", f.captured)
        f.detector.stop()   // cancel the parked monitor coroutine launched by start()
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

    // ── H3 — MEDICAL_FLATLINE cadence/power cross-check ─────────────────────────

    @Test
    fun `H3 - flatline does NOT fire when cadence indicates the rider is still pedalling`() {
        // HR strap drops below 30 bpm for 35 s (sweat / contact loss). The rider is
        // clearly still riding — cadence fluctuates naturally around 60 RPM throughout
        // (a real pedalling cadence varies revolution-to-revolution; the I2 staleness
        // check requires value-change emissions to consider the sensor fresh). With H3
        // the cross-check suppresses the FLATLINE fire.
        val f = Fixture()
        f.speed(20.0)
        f.detector.updateCadence(60.0)      // seed: well above CADENCE_ACTIVE_RPM = 10
        for (sec in 0..35) {
            f.clock.nowMs += 1_000L
            f.hr(20)
            // Vary the cadence each sample so I2's freshness-by-change keeps the
            // sensor classified as fresh — same shape as a genuinely pedalling rider.
            f.detector.updateCadence(60.0 + (sec % 5))
            if (sec % 5 == 0) f.detector.tick()
        }
        assertNull(
            "cadence cross-check must suppress FLATLINE: ${f.captured}",
            f.captured,
        )
    }

    @Test
    fun `H3 - flatline does NOT fire when power indicates the rider is still riding`() {
        // Same shape — HR strap drops out but power reads ~200 W with natural
        // fluctuation (real power-meter output varies pedal stroke to pedal stroke,
        // which the I2 freshness-by-change check uses to classify the sensor as fresh).
        // Cross-check suppresses.
        val f = Fixture()
        f.speed(20.0)
        f.detector.updatePower(200)         // well above POWER_ACTIVE_W = 30
        for (sec in 0..35) {
            f.clock.nowMs += 1_000L
            f.hr(20)
            // Fluctuate so I2 freshness-by-change keeps the sensor classified as fresh.
            f.detector.updatePower(200 + (sec % 5))
            if (sec % 5 == 0) f.detector.tick()
        }
        assertNull(
            "power cross-check must suppress FLATLINE: ${f.captured}",
            f.captured,
        )
    }

    @Test
    fun `H3 - flatline fires when both cadence and power confirm zero`() {
        // Both sensors are plumbed and both confirm the rider has stopped pedalling —
        // cadence = 0 RPM, power = 0 W. Combined with HR < 30 for 30 s+ this is the
        // canonical asystole scenario; the H3 cross-check must NOT suppress.
        val f = Fixture()
        f.speed(20.0)
        f.detector.updateCadence(0.0)
        f.detector.updatePower(0)
        for (sec in 0..35) {
            f.clock.nowMs += 1_000L
            f.hr(20)
            f.speed(0.0)            // keep speed signal fresh — see fluent SDK emission rationale above
            f.detector.updateCadence(0.0)
            f.detector.updatePower(0)
            if (sec % 5 == 0) f.detector.tick()
        }
        assertTrue(
            "FLATLINE must still fire when both cadence + power are zero: ${f.captured}",
            f.captured != null,
        )
        assertEquals(EmergencyReason.MEDICAL_FLATLINE, f.captured!!.first)
    }

    @Test
    fun `H3 - flatline fires when no cadence or power signal is plumbed`() {
        // Graceful degradation — no power meter, no cadence sensor (a common rider
        // setup: just HR strap). The detector must fall through to the original
        // FLATLINE behaviour so a real flatline on a basic setup is still detected.
        val f = Fixture()
        f.speed(20.0)
        // Deliberately do NOT call updateCadence / updatePower.
        for (sec in 0..35) {
            f.clock.nowMs += 1_000L
            f.hr(20)
            f.speed(0.0)            // keep speed signal fresh — see fluent SDK emission rationale above
            if (sec % 5 == 0) f.detector.tick()
        }
        assertTrue(
            "FLATLINE must fire when no cross-check signal is plumbed: ${f.captured}",
            f.captured != null,
        )
        assertEquals(EmergencyReason.MEDICAL_FLATLINE, f.captured!!.first)
    }

    // ── I2 — stuck cadence/power sensor staleness ──────────────────────────────

    @Test
    fun `I2 - H3 FLATLINE fires when cadence sensor is stuck (stale)`() {
        // A known ANT+ pathology: the cadence magnet sits just inside the sensor's
        // sensing range and the sensor keeps emitting the last reading bit-exact long
        // after the wheel has stopped. Pre-I2, the sticky `cadenceDataReceived` plus
        // the stuck 60 RPM kept `cadenceSaysActive = true` indefinitely, silently
        // suppressing every genuine FLATLINE that lined up with a stuck sensor. With
        // I2 the freshness-by-VALUE-CHANGE check ages past the 10 s window and the
        // cross-check correctly falls through to the underlying HR signal.
        val f = Fixture()
        // Phase 1 — bouncing cadence for 30 s seeds [cadenceLastChangeMs].
        // HR is still healthy here (no FLATLINE accumulating). Keep speed fresh
        // every sample so the ACTIVE_RECENT_MS (60 s) gate never trips during
        // phase 2's accumulation window.
        var cadenceVal = 50.0
        for (sec in 0 until 30) {
            f.clock.nowMs += 1_000L
            f.hr(120)
            f.speed(20.0 + (sec % 5) * 0.1)
            cadenceVal = when (sec % 3) { 0 -> 50.0; 1 -> 55.0; else -> 60.0 }
            f.detector.updateCadence(cadenceVal)
            if (sec % 5 == 0) f.detector.tick()
        }
        // Phase 2 — sensor freezes at 60 RPM AND HR drops below 30 bpm. After
        // CADENCE_POWER_STALE_MS (10 s) without a value change the sensor goes stale;
        // after HR_FLATLINE_DURATION_SEC (30 s) under threshold the FLATLINE fires.
        for (sec in 0 until 35) {
            f.clock.nowMs += 1_000L
            f.hr(20)
            f.speed(20.0 + (sec % 5) * 0.1)
            f.detector.updateCadence(60.0)  // stuck — bit-exact each call
            if (sec % 5 == 0) f.detector.tick()
        }
        assertTrue(
            "stuck cadence must NOT suppress FLATLINE — I2 staleness gate should kick in: ${f.captured}",
            f.captured != null,
        )
        assertEquals(EmergencyReason.MEDICAL_FLATLINE, f.captured!!.first)
    }

    @Test
    fun `I2 - H3 FLATLINE fires when power meter is stuck (stale)`() {
        // Same shape as the cadence case — a frozen power meter (strain-gauge dropout
        // that keeps reporting the last value) must not silently mask a real FLATLINE.
        val f = Fixture()
        var powerVal = 150
        for (sec in 0 until 30) {
            f.clock.nowMs += 1_000L
            f.hr(120)
            f.speed(20.0 + (sec % 5) * 0.1)
            powerVal = when (sec % 3) { 0 -> 150; 1 -> 175; else -> 200 }
            f.detector.updatePower(powerVal)
            if (sec % 5 == 0) f.detector.tick()
        }
        for (sec in 0 until 35) {
            f.clock.nowMs += 1_000L
            f.hr(20)
            f.speed(20.0 + (sec % 5) * 0.1)
            f.detector.updatePower(200)     // stuck — bit-exact each call
            if (sec % 5 == 0) f.detector.tick()
        }
        assertTrue(
            "stuck power meter must NOT suppress FLATLINE — I2 staleness gate should kick in: ${f.captured}",
            f.captured != null,
        )
        assertEquals(EmergencyReason.MEDICAL_FLATLINE, f.captured!!.first)
    }

    @Test
    fun `I2 - H3 FLATLINE blocked when cadence is genuinely fresh and active`() {
        // Regression guard for the I2 change: a real pedalling rider whose cadence
        // varies every revolution must STILL have FLATLINE suppressed by the H3 cross-
        // check. This is the "I2 must not break H3" invariant — staleness only kicks
        // in when the value has actually stopped changing.
        val f = Fixture()
        f.speed(20.0)
        for (sec in 0..35) {
            f.clock.nowMs += 1_000L
            f.hr(20)
            // Bounce through 50 / 55 / 60 / 65 — every sample is a value change, so
            // [cadenceLastChangeMs] stays current and [cadenceFresh] stays true.
            val rpm = when (sec % 4) { 0 -> 50.0; 1 -> 55.0; 2 -> 60.0; else -> 65.0 }
            f.detector.updateCadence(rpm)
            if (sec % 5 == 0) f.detector.tick()
        }
        assertNull(
            "fresh-changing cadence must still suppress FLATLINE (H3 invariant): ${f.captured}",
            f.captured,
        )
    }

    @Test
    fun `I2 - cadence staleness uses 10s threshold from last value change`() {
        // Two halves with separate fixtures to exercise both sides of the 10 s
        // freshness-by-change threshold. Separate fixtures because [evaluateFlatline]'s
        // suppression branch resets [flatlineSinceMs] (re-arm semantics) — running both
        // halves on the same instance would force the second half to re-accumulate the
        // full 30 s after the first half's suppressive tick.
        //
        // Part 1 — frozen for 9 s ⇒ fresh ⇒ suppress.
        //   Seed two distinct cadence values 1 s apart, then freeze. Accumulate 30 s of
        //   HR < 30, ticking only at the very end when the last value change is just
        //   under 10 s ago (re-stamped at the final second via a 50 → 60 transition).
        val f1 = Fixture()
        f1.detector.updateCadence(40.0)
        f1.clock.nowMs += 1_000L
        f1.detector.updateCadence(50.0)        // stamps cadenceLastChangeMs
        // 29 s of HR < 30 with frozen cadence; refresh cadence once on the very last
        // second so the final tick lands ~0 s past the latest change (well inside the
        // 10 s fresh window).
        for (sec in 0 until 29) {
            f1.clock.nowMs += 1_000L
            f1.hr(20)
            f1.speed(20.0 + (sec % 5) * 0.1)
            // No tick during accumulation — let flatlineSinceMs build uninterrupted.
            f1.detector.updateCadence(50.0)    // identical — no change stamped
        }
        // Final second — re-stamp cadenceLastChangeMs by changing the value, then tick.
        f1.clock.nowMs += 1_000L
        f1.hr(20)
        f1.speed(25.0)
        f1.detector.updateCadence(60.0)        // value change → cadenceLastChangeMs = now
        f1.detector.tick()
        assertNull(
            "≤10 s since last cadence change → cross-check fresh → must suppress: ${f1.captured}",
            f1.captured,
        )

        // Part 2 — frozen for 11 s ⇒ stale ⇒ fire.
        //   Same seed; freeze for 11 s BEFORE HR drops to keep the flatline timer at
        //   zero during the freeze. Then 35 s of HR < 30 with cadence still frozen —
        //   the cross-check stays stale across the entire FLATLINE window so the fire
        //   is not suppressed.
        val f2 = Fixture()
        f2.detector.updateCadence(40.0)
        f2.clock.nowMs += 1_000L
        f2.detector.updateCadence(50.0)        // stamps cadenceLastChangeMs
        for (sec in 0 until 11) {
            f2.clock.nowMs += 1_000L
            f2.speed(20.0 + (sec % 5) * 0.1)
            f2.detector.updateCadence(50.0)    // identical
        }
        // Cross-check is now stale (11 s since last change). Drive 35 s of HR < 30.
        for (sec in 0 until 35) {
            f2.clock.nowMs += 1_000L
            f2.hr(20)
            f2.speed(20.0 + (sec % 5) * 0.1)
            f2.detector.updateCadence(50.0)    // identical — still stale
            if (sec % 5 == 0) f2.detector.tick()
        }
        assertTrue(
            ">10 s since last cadence change → cross-check stale → FLATLINE must fire: ${f2.captured}",
            f2.captured != null,
        )
        assertEquals(EmergencyReason.MEDICAL_FLATLINE, f2.captured!!.first)
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
