package com.enderthor.kSafe.extension.crash

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.extension.util.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.util.concurrent.atomic.AtomicInteger

/**
 * Narrow, facade-level wiring tests for [CrashDetectionManager]. Two contracts
 * verified by inspection elsewhere are pinned here:
 *
 *  - **T1 ([clearCrashCooldown] releases the monotonic cooldown gate).**
 *    Wired up in [com.enderthor.kSafe.extension.KSafeExtension] as
 *    `onCrashEmergencyCancelled = { crashManager.clearCrashCooldown() }`.
 *    The load-bearing scenario: rider cancels a false-positive countdown, then
 *    a real crash within the ~60 s `crashCooldownMs` window is otherwise
 *    silently suppressed because [confirmCrash] gates on `lastCrashTime`.
 *
 *  - **T3 ([onPause] preserves an in-flight detection on auto-pause).**
 *    The Karoo SDK auto-pauses the ride ~3–6 s after speed hits 0, which is
 *    exactly what a real crash does. If `onPause(auto = true)` reset the state
 *    machine, the autopause would erase the very detection of the crash that
 *    caused the stop. The manual-pause branch must still wipe the SM so a rider
 *    who deliberately stopped after a false IMPACT doesn't trip a phantom
 *    confirmation when they resume.
 *
 * Test harness shape (all three tests):
 *  1. Mock [Context] and [SensorManager] so the manager constructs without an
 *     OS. The accelerometer/gyroscope sensors are mocked but never registered
 *     because we never call `start()` — we only need the SM and the cooldown
 *     field. This keeps the test JVM-runnable.
 *  2. Inject a fake [Clock] so we can advance time deterministically.
 *  3. Use [TestScope] for the coroutine scope (no flush of pending jobs is
 *     required — none of the methods exercised here launch coroutines).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrashDetectionManagerWiringTest {

    /** Mutable time source for the manager's cooldown / state-machine math. */
    private class FakeClock(var now: Long = 1_000_000L) : Clock {
        override fun nowMs(): Long = now
    }

    private fun newManager(clock: FakeClock = FakeClock()): CrashDetectionManager {
        val sensorManager = mock(SensorManager::class.java)
        val accel = mock(Sensor::class.java).also {
            `when`(it.type).thenReturn(Sensor.TYPE_ACCELEROMETER)
        }
        val gyro = mock(Sensor::class.java).also {
            `when`(it.type).thenReturn(Sensor.TYPE_GYROSCOPE)
        }
        `when`(sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)).thenReturn(accel)
        `when`(sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)).thenReturn(gyro)
        val context = mock(Context::class.java)
        `when`(context.getSystemService(Context.SENSOR_SERVICE)).thenReturn(sensorManager)
        return CrashDetectionManager(
            context = context,
            scope = TestScope() as CoroutineScope,
            onCrashDetected = { /* no-op — these tests don't reach confirm */ },
            calibLogger = null,
            clock = clock,
        )
    }

    /**
     * Build a manager whose [onCrashDetected] increments [counter] and whose coroutine
     * scope is the [TestScope] supplied by [runTest] so [advanceUntilIdle] drains the
     * `scope.launch { onCrashDetected() }` call inside [CrashDetectionManager.confirmCrash].
     */
    private fun newManagerForVigilance(
        testScope: TestScope,
        counter: AtomicInteger,
        clock: FakeClock = FakeClock(),
    ): CrashDetectionManager {
        val sensorManager = mock(SensorManager::class.java)
        val accel = mock(Sensor::class.java).also {
            `when`(it.type).thenReturn(Sensor.TYPE_ACCELEROMETER)
        }
        val gyro = mock(Sensor::class.java).also {
            `when`(it.type).thenReturn(Sensor.TYPE_GYROSCOPE)
        }
        `when`(sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)).thenReturn(accel)
        `when`(sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)).thenReturn(gyro)
        val context = mock(Context::class.java)
        `when`(context.getSystemService(Context.SENSOR_SERVICE)).thenReturn(sensorManager)
        return CrashDetectionManager(
            context = context,
            scope = testScope as CoroutineScope,
            onCrashDetected = { counter.incrementAndGet() },
            calibLogger = null,
            clock = clock,
        )
    }

    /**
     * Arm [MovingVigilance] on [manager] via reflection. The field is `private` — there is
     * NO public API path that arms it without injecting a real sensor event through the
     * Android SensorManager (whose [SensorEvent] constructor is a JVM stub). Reflection is
     * the only way to set up the vigilance-armed precondition so the downstream escalate
     * paths in [CrashDetectionManager.stop] and [CrashDetectionManager.onPause] can be
     * tested against a truthful armed state.
     *
     * This helper does NOT exercise the `Decision.Confirm → movingVigilance.arm()`
     * divert in [CrashDetectionManager.onSensorSample]. That path is unreachable from
     * any public surface in the JVM harness; the tests that call this helper are
     * explicitly testing the ESCALATE-ON-ABANDON and ESCALATE-ON-MANUAL-PAUSE paths,
     * not the divert trigger. See the DONE_WITH_CONCERNS note on tests 3 & 4 for the
     * full blockage description.
     */
    private fun armVigilanceViaReflection(manager: CrashDetectionManager, nowMs: Long) {
        val field = CrashDetectionManager::class.java.getDeclaredField("movingVigilance")
        field.isAccessible = true
        val vigilance = field.get(manager) as MovingVigilance
        vigilance.arm(nowMs)
        check(vigilance.isArmed) { "reflection arm() failed — vigilance not armed" }
    }

    // ── T1: clearCrashCooldown ───────────────────────────────────────────────

    /**
     * Pins the cooldown-clear contract.
     *
     * If you revert commit 2d8a57f (the `clearCrashCooldown()` body), this test
     * fails because the field stays at its non-zero seed: the second-crash
     * suppression path is silent in production but observable here via
     * [CrashDetectionManager.lastCrashTime].
     */
    @Test
    fun `clearCrashCooldown releases the cooldown gate`() {
        val clock = FakeClock(now = 5_000_000L)
        val manager = newManager(clock)
        // Seed the cooldown field as if a previous confirmation had just landed.
        // Anything close to `now` engages the gate in confirmCrash:
        //   if ((now - lastCrashTime) <= crashCooldownMs) suppress
        manager.lastCrashTime = clock.now - 1_000L  // 1 s ago — well inside cooldown

        manager.clearCrashCooldown()

        // The contract is SEMANTIC: after clearing, the gate must be open, i.e.
        // `(now - lastCrashTime)` exceeds any plausible crashCooldownMs (max ≈
        // countdown 300 s + 30 s = 330 s). The sentinel is deliberately NOT 0L: the
        // gate is monotonic (`clock.monotonicMs()` == elapsedRealtime, which is small
        // shortly after boot), so a 0L sentinel would read as "cooldown active" for
        // the first crashCooldownMs of device uptime and SUPPRESS a real crash. A
        // large-negative sentinel makes the elapsed effectively infinite. Assert the
        // behaviour (huge elapsed), not the magic value, so this survives the exact
        // sentinel choice.
        val elapsedSinceLastCrash = clock.now - manager.lastCrashTime
        assertTrue(
            "clearCrashCooldown() must release the gate so a real crash within the cooldown " +
                "window of a cancelled false-positive is not suppressed by confirmCrash. " +
                "Elapsed-since-last-crash was $elapsedSinceLastCrash ms, which does not clear " +
                "the max cooldown. Wired in KSafeExtension as the CRASH_DETECTED cancel callback.",
            elapsedSinceLastCrash > 24L * 60 * 60 * 1000,   // > 1 day ≫ any cooldown
        )
    }

    // ── T3: onPause(auto) preservation ───────────────────────────────────────

    /**
     * Auto-pause must NOT wipe an in-flight IMPACT / SILENCE_CHECK detection.
     *
     * Reverting the I3 fix (changing `if (auto) { ... } else { stateMachine.onPause() }`
     * back to an unconditional `stateMachine.onPause()`) makes this test fail
     * because the SM is forced back to MONITORING by the facade.
     */
    @Test
    fun `onPause auto=true preserves in-flight IMPACT state on the state machine`() {
        val clock = FakeClock(now = 1_000_000L)
        val manager = newManager(clock)
        // Drive the SM to IMPACT directly. The facade's onSensorSample is private and
        // the real SensorReader cannot deliver events under a mocked SensorManager,
        // so we go through the SM (which is now internal — see the visibility note
        // on the field for the rationale).
        manager.stateMachine.onSpeedUpdate(20.0)
        val d = manager.stateMachine.onSample(
            SensorSample(
                rawMagnitude = 60.0,
                smoothedMagnitude = 30.0,
                peakMagnitude = 60.0,
                gyroMag = 0.5,
                timestampMs = clock.now,
                accelX = 0.0, accelY = 0.0, accelZ = 60.0,
            )
        )
        check(d is CrashStateMachine.Decision.EnterImpact) { "test setup: expected EnterImpact, got $d" }
        assertEquals(CrashStateMachine.State.IMPACT, manager.stateMachine.state)

        manager.onPause(auto = true)

        assertNotEquals(
            "onPause(auto = true) must NOT reset the state machine — a Karoo autopause " +
                "(~3–6 s after speed hits 0) is exactly what a real crash produces, and " +
                "the in-flight IMPACT/SILENCE_CHECK detection must be allowed to confirm " +
                "during the pause.",
            CrashStateMachine.State.MONITORING,
            manager.stateMachine.state,
        )
        // Specifically: still in IMPACT (no transition fired between the EnterImpact
        // decision above and the pause call).
        assertEquals(CrashStateMachine.State.IMPACT, manager.stateMachine.state)
    }

    /**
     * Manual-pause must still wipe the SM.
     *
     * Distinguishes T3's preservation from the original onPause() semantics: the
     * deliberate-stop case (rider taps pause) wipes the in-flight state so that
     * a phantom mid-IMPACT does not survive into a fresh resume.
     */
    @Test
    fun `onPause auto=false resets in-flight IMPACT state on the state machine`() {
        val clock = FakeClock(now = 1_000_000L)
        val manager = newManager(clock)
        manager.stateMachine.onSpeedUpdate(20.0)
        manager.stateMachine.onSample(
            SensorSample(
                rawMagnitude = 60.0,
                smoothedMagnitude = 30.0,
                peakMagnitude = 60.0,
                gyroMag = 0.5,
                timestampMs = clock.now,
                accelX = 0.0, accelY = 0.0, accelZ = 60.0,
            )
        )
        check(manager.stateMachine.state == CrashStateMachine.State.IMPACT) {
            "test setup: failed to reach IMPACT"
        }

        manager.onPause(auto = false)

        assertEquals(
            "onPause(auto = false) is a manual pause — the rider is consciously stopping " +
                "and the in-flight detection must be wiped so an unrelated mid-IMPACT does " +
                "not survive into the next resume.",
            CrashStateMachine.State.MONITORING,
            manager.stateMachine.state,
        )
    }

    // ── I-NEW-1: resume re-syncs gps_stale into the SM on preserve branch ───

    /**
     * Auto-resume after auto-pause during an in-flight IMPACT / SILENCE_CHECK must
     * keep the state machine in its preserved state AND re-sync the staleness view
     * the SM carries (`lastSpeedGpsStale`) with what the facade now actually sees.
     *
     * Before the I-NEW-1 fix, [CrashDetectionManager.resume] reset only the facade's
     * `lastGpsStaleState` to `false` (without pushing into the SM). The SM kept its
     * prior stale view across the pause. If the actual GPS was fresh on the first
     * post-resume sample, the transition gate in `onSensorSample`
     * (`gpsCurrentlyStale != lastGpsStaleState`) saw `false != false` and never called
     * `stateMachine.setSpeedGpsStale(false)` — the SM stayed in stale-mode for the rest
     * of the in-flight SILENCE_CHECK (`gpsStaleSilenceDurationMs = 8000` instead of
     * `silenceDurationMs = 4500`), delaying confirmation by ~3.5 s.
     *
     * Note on observability: `CrashStateMachine.lastSpeedGpsStale` and the facade's
     * `lastGpsStaleState` are both private (intentionally — not widened for the test).
     * What is reachable here is the SM **state** field, which lets us pin the
     * preservation invariant on the resume side. The staleness re-sync itself is an
     * inspection-only contract — see [CrashDetectionManager.resume] for the code that
     * enforces it. Reverting I-NEW-1 would not fail this test; the test exists to
     * document the contract and to lock in resume-side state preservation symmetric to
     * the existing pause-side test.
     */
    @Test
    fun `resume preserves in-flight IMPACT state and triggers gps_stale re-sync`() {
        val clock = FakeClock(now = 1_000_000L)
        val manager = newManager(clock)
        // Drive SM into IMPACT (same pattern as the auto-pause test).
        manager.stateMachine.onSpeedUpdate(20.0)
        manager.stateMachine.onSample(
            SensorSample(
                rawMagnitude = 60.0,
                smoothedMagnitude = 30.0,
                peakMagnitude = 60.0,
                gyroMag = 0.5,
                timestampMs = clock.now,
                accelX = 0.0, accelY = 0.0, accelZ = 60.0,
            )
        )
        check(manager.stateMachine.state == CrashStateMachine.State.IMPACT) {
            "test setup: failed to reach IMPACT"
        }
        // Seed: the SM holds a stale view from before the pause...
        manager.stateMachine.setSpeedGpsStale(true)
        // ...and the facade's [isGpsStale] would now return false (speedLastChangeMs
        // freshly stamped, well within GPS_STALE_MS). updateSpeed is the only public
        // entry point that stamps speedLastChangeMs to clock.now.
        manager.updateSpeed(15.0)

        // Auto-pause then auto-resume — the in-flight IMPACT must survive both.
        manager.onPause(auto = true)
        assertEquals(
            "Sanity: auto-pause must preserve the in-flight IMPACT (T3 contract).",
            CrashStateMachine.State.IMPACT,
            manager.stateMachine.state,
        )

        manager.resume(KSafeConfig())

        assertEquals(
            "resume() must preserve the in-flight IMPACT — auto-resume mid-detection " +
                "is the symmetric case to T3's auto-pause preservation, and reverting it " +
                "would erase the very detection of the crash that caused the pause.",
            CrashStateMachine.State.IMPACT,
            manager.stateMachine.state,
        )
        // Inspection-only follow-up: `stateMachine.lastSpeedGpsStale` is now false
        // (re-read from `isGpsStale(now) == false` and pushed via setSpeedGpsStale).
        // Field is private — not asserted here. See I-NEW-1 in resume() for the wiring.
    }

    // ── MV-1: stop() while vigilance armed escalates ─────────────────────────

    /**
     * Pins the escalate-on-abandon contract: when [MovingVigilance] is armed and the ride
     * stops (KSafe extension stopped, app killed, user disabled crash detection), the
     * manager must call [CrashDetectionManager.confirmCrash] rather than silently dropping
     * the suspected event. Silence = false negative for an incapacitated rider.
     *
     * Wiring: [CrashDetectionManager.stop] checks `movingVigilance.isArmed` and routes
     * through `confirmCrash(CrashSource.IMPACT_CONFIRMED)` before resetting state.
     *
     * **Harness note**: [MovingVigilance] is armed via reflection because there is no
     * public API path that arms it without injecting real sensor events through the
     * Android [SensorManager] (its [android.hardware.SensorEvent] constructor is a JVM
     * stub). The reflection call sets up a truthful armed state; the code exercised is the
     * production stop() → confirmCrash() → onCrashDetected() chain. The divert that
     * normally arms vigilance (Decision.Confirm inside onSensorSample, requiring a
     * sub-gravity silence orientation) is unreachable from the JVM harness — see the
     * DONE_WITH_CONCERNS section on tests MV-3/MV-4.
     */
    @Test
    fun `stop() while vigilance armed escalates to onCrashDetected`() = runTest {
        val crashCount = AtomicInteger(0)
        val clock = FakeClock(now = 1_000_000L)
        val manager = newManagerForVigilance(this, crashCount, clock)

        // Arm vigilance to represent a mid-motion on-side confirm that is still
        // under verification. Reflection is required because the arm path runs
        // through the private onSensorSample callback (see helper KDoc).
        armVigilanceViaReflection(manager, nowMs = clock.now)
        // Verify the precondition: vigilance IS armed before stop().
        val mvField = CrashDetectionManager::class.java.getDeclaredField("movingVigilance")
        mvField.isAccessible = true
        val vigilance = mvField.get(manager) as MovingVigilance
        assertTrue("precondition: vigilance must be armed before stop()", vigilance.isArmed)

        manager.stop()
        // confirmCrash launches onCrashDetected on the TestScope — drain it.
        advanceUntilIdle()

        assertEquals(
            "stop() with armed vigilance must escalate — the rider may be down; " +
                "silently abandoning an armed MovingVigilance is a false negative.",
            1, crashCount.get()
        )
    }

    // ── MV-2: manual pause while vigilance armed escalates ───────────────────

    /**
     * Pins the escalate-on-manual-pause contract: an [onPause] call with `auto=false`
     * (rider tapped pause) while [MovingVigilance] is armed must still escalate rather
     * than drop the suspected event. A downed rider cannot be assumed conscious merely
     * because a pause signal arrived; a false negative is unacceptable.
     *
     * Contrast with an auto-pause: [onPause(auto=true)] does NOT wipe [MovingVigilance]
     * — auto-pause fires when speed reaches 0, which is also what a real crash does.
     * The manual-pause branch explicitly escalates because it wipes the state machine.
     *
     * Wiring: [CrashDetectionManager.onPause] with `auto=false` reaches
     * `if (movingVigilance.isArmed) confirmCrash(...)` before `movingVigilance.reset()`.
     *
     * [lastCrashTime] is a reliable side-effect indicator here because [onPause] does NOT
     * clear it at the end (unlike [stop]). A non-COOLDOWN_INACTIVE value proves
     * confirmCrash was called. The [onCrashDetected] counter additionally confirms the
     * coroutine was dispatched.
     */
    @Test
    fun `manual pause while vigilance armed escalates to onCrashDetected`() = runTest {
        val crashCount = AtomicInteger(0)
        val clock = FakeClock(now = 2_000_000L)
        val manager = newManagerForVigilance(this, crashCount, clock)

        // Arm vigilance (reflection required — see MV-1 KDoc).
        armVigilanceViaReflection(manager, nowMs = clock.now)
        val mvField = CrashDetectionManager::class.java.getDeclaredField("movingVigilance")
        mvField.isAccessible = true
        val vigilance = mvField.get(manager) as MovingVigilance
        assertTrue("precondition: vigilance must be armed before onPause(auto=false)", vigilance.isArmed)

        manager.onPause(auto = false)
        advanceUntilIdle()

        assertEquals(
            "onPause(auto=false) with armed vigilance must escalate — a manual pause " +
                "wipes the state machine; any armed MovingVigilance must not be silently " +
                "discarded since the downed rider cannot be assumed conscious.",
            1, crashCount.get()
        )
        // Bonus: lastCrashTime was stamped by confirmCrash (onPause does NOT clear it, unlike stop).
        // COOLDOWN_INACTIVE = Long.MIN_VALUE / 2; a real stamped value is near clock.now.
        val lastCrash = manager.lastCrashTime
        assertTrue(
            "lastCrashTime must be near clock.now after confirm (not COOLDOWN_INACTIVE=${ Long.MIN_VALUE / 2 }), was $lastCrash",
            lastCrash > 0L
        )
    }

    // ── Freshness gate unit tests (isSpeedFreshForVigilance) ────────────────────

    /**
     * A GPS frozen at a non-zero value stops changing its reported speed value while
     * still reading "not stale" for up to GPS_STALE_MS (10 s). The freshness gate catches
     * this: when [speedLastChangeMs] has not advanced for >= [freshMs], the gate returns
     * false and vigilance escalates rather than clearing.
     *
     * These tests drive [CrashDetectionManager.isSpeedFreshForVigilance] directly — now
     * that the inline expression has been extracted into a companion function, no sensor
     * event injection is required.
     */

    /**
     * Frozen GPS value: speedLastChangeMs did NOT advance, elapsed >= freshMs.
     * gpsStale=false (within GPS_STALE_MS window) but value stopped changing → false
     * (escalate, not clear).
     */
    @Test
    fun `isSpeedFreshForVigilance returns false when speed value frozen beyond freshMs`() {
        val freshMs = 4_000L
        val nowMs = 100_000L
        val speedLastChangeMs = nowMs - freshMs   // exactly at boundary → elapsed == freshMs, strict < → false
        val result = CrashDetectionManager.isSpeedFreshForVigilance(
            nowMs = nowMs,
            gpsStale = false,
            speedLastChangeMs = speedLastChangeMs,
            freshMs = freshMs,
        )
        assertEquals(
            "A frozen speed value (elapsed == freshMs) must return false — " +
                "the gate is strict < so equality does NOT count as fresh.",
            false, result,
        )
    }

    /**
     * Genuinely fresh speed: speedLastChangeMs advanced recently (elapsed < freshMs),
     * gpsStale=false → true (clear is allowed).
     */
    @Test
    fun `isSpeedFreshForVigilance returns true when speed recently changed and GPS not stale`() {
        val freshMs = 4_000L
        val nowMs = 100_000L
        val speedLastChangeMs = nowMs - (freshMs - 1)   // elapsed = freshMs-1, strictly inside window
        val result = CrashDetectionManager.isSpeedFreshForVigilance(
            nowMs = nowMs,
            gpsStale = false,
            speedLastChangeMs = speedLastChangeMs,
            freshMs = freshMs,
        )
        assertEquals(
            "A recently-changed speed (elapsed < freshMs, GPS not stale) must return true — " +
                "the vigilance window should be clearable.",
            true, result,
        )
    }

    /**
     * GPS stale: even when speedLastChangeMs is very recent, gpsStale=true must force
     * the gate to false so a GPS that went stale mid-ride never clears vigilance.
     */
    @Test
    fun `isSpeedFreshForVigilance returns false when GPS is stale regardless of recency`() {
        val freshMs = 4_000L
        val nowMs = 100_000L
        val speedLastChangeMs = nowMs - 1L   // 1 ms ago — maximally recent
        val result = CrashDetectionManager.isSpeedFreshForVigilance(
            nowMs = nowMs,
            gpsStale = true,
            speedLastChangeMs = speedLastChangeMs,
            freshMs = freshMs,
        )
        assertEquals(
            "gpsStale=true must return false regardless of how recent speedLastChangeMs is — " +
                "a stale GPS cannot be trusted to confirm motion.",
            false, result,
        )
    }

    /**
     * Boundary: elapsed == freshMs is NOT fresh (strict < check). This pins the
     * off-by-one so a future change to >= does not silently pass.
     */
    @Test
    fun `isSpeedFreshForVigilance boundary at exactly freshMs returns false`() {
        val freshMs = 3_500L
        val nowMs = 200_000L
        // elapsed = nowMs - speedLastChangeMs = freshMs exactly
        val speedLastChangeMs = nowMs - freshMs
        val result = CrashDetectionManager.isSpeedFreshForVigilance(
            nowMs = nowMs,
            gpsStale = false,
            speedLastChangeMs = speedLastChangeMs,
            freshMs = freshMs,
        )
        assertEquals(
            "Elapsed exactly equal to freshMs must return false — the gate is strict <, " +
                "so at-boundary is not considered fresh.",
            false, result,
        )
    }
}
