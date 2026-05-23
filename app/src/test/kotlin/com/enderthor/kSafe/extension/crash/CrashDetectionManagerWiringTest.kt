package com.enderthor.kSafe.extension.crash

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import com.enderthor.kSafe.extension.util.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

/**
 * Narrow, facade-level wiring tests for [CrashDetectionManager]. Two contracts
 * verified by inspection elsewhere are pinned here:
 *
 *  - **T1 ([clearCrashCooldown] zeroes [CrashDetectionManager.lastCrashTime]).**
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
    fun `clearCrashCooldown zeroes lastCrashTime so the cooldown gate releases`() {
        val clock = FakeClock(now = 5_000_000L)
        val manager = newManager(clock)
        // Seed the cooldown field as if a previous confirmation had just landed.
        // Anything > 0 is enough to engage the gate in confirmCrash:
        //   if ((now - lastCrashTime) <= crashCooldownMs) suppress
        manager.lastCrashTime = clock.now - 1_000L  // 1 s ago — well inside cooldown
        check(manager.lastCrashTime != 0L)

        manager.clearCrashCooldown()

        assertEquals(
            "clearCrashCooldown() must zero lastCrashTime, otherwise a real crash within " +
                "crashCooldownMs of a cancelled false-positive countdown is suppressed by " +
                "confirmCrash. The wiring in KSafeExtension routes the user-cancel callback " +
                "(EmergencyManager.cancelEmergency, CRASH_DETECTED branch) into this method.",
            0L,
            manager.lastCrashTime,
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

}
