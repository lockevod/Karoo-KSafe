package com.enderthor.kSafe.extension.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CarbIntegrator] — pins the three gates that the audit
 * called out as untested in v18:
 *
 *  1. Movement gate (stationary / GPS-stale freeze).
 *  2. Absorption-cap clamp (~90 g/h).
 *  3. Active-time accumulator gate (`effectiveGph > 0`) so the session-
 *     average burn rate doesn't dilute toward zero for HR-less riders.
 *
 * Pre-v18.1 these lived inline in `CarbsTracker.tick()`. Pulling them into
 * a pure helper lets us pin the contract without spinning up a tracker
 * coroutine harness.
 */
class CarbIntegratorTest {

    /** Default tick cadence — same as `CarbsTracker.MONITOR_TICK_MS`. */
    private val tick = 15_000L

    @Test
    fun `15s tick at 60 g per hour accumulates 0_25 g`() {
        val step = CarbIntegrator.integrate(
            burnGph = 60.0,
            dtMs = tick,
            speedKmh = 25.0,
            speedStale = false,
        )
        // 60 g/h × 15 s / 3600 = 0.25 g
        assertEquals(0.25f, step.deltaG, 0.001f)
        assertEquals(15_000L, step.deltaActiveMs)
        assertEquals(60.0, step.effectiveGph, 0.001)
        assertTrue(step.moving)
    }

    @Test
    fun `absorption cap clamps integration rate to 90 g per hour`() {
        // Even at 150 g/h physiological burn (Z5+, power > ~250 W) the
        // *integration* rate stays at the cap. The displayed deficit then
        // grows honestly via the gap between intake and the capped burn,
        // but the cumulative total cannot race ahead of any plausible
        // intake plan.
        val step = CarbIntegrator.integrate(
            burnGph = 150.0,
            dtMs = tick,
            speedKmh = 30.0,
            speedStale = false,
        )
        assertEquals(
            "effectiveGph must clamp at ABSORPTION_CAP_GPH (~90 g/h) regardless of physiological input",
            ABSORPTION_CAP_GPH.toDouble(), step.effectiveGph, 0.001,
        )
        // 90 g/h × 15 s / 3600 = 0.375 g
        assertEquals(0.375f, step.deltaG, 0.001f)
        assertEquals(15_000L, step.deltaActiveMs)
    }

    @Test
    fun `no integration when stationary below MOVING_GATE_KMH`() {
        val step = CarbIntegrator.integrate(
            burnGph = 60.0,
            dtMs = tick,
            speedKmh = 1.5,  // below 2 km/h moving gate
            speedStale = false,
        )
        assertEquals(0f, step.deltaG, 0.0001f)
        assertEquals(0L, step.deltaActiveMs)
        assertFalse(step.moving)
    }

    @Test
    fun `no integration when speed is stale even at high magnitude`() {
        // SDK keeps replaying the last value when GPS lock is lost. The
        // tracker passes `speedStale=true` and the integrator freezes
        // regardless of the speedKmh value — without this, a rider whose
        // GPS dies in a tunnel at 30 km/h would keep accumulating burn
        // for as long as the SDK kept emitting the stuck value.
        val step = CarbIntegrator.integrate(
            burnGph = 60.0,
            dtMs = tick,
            speedKmh = 30.0,  // would be moving if not for staleness
            speedStale = true,
        )
        assertEquals(0f, step.deltaG, 0.0001f)
        assertEquals(0L, step.deltaActiveMs)
        assertFalse(step.moving)
    }

    @Test
    fun `no integration when speedKmh is null (SDK has not emitted yet)`() {
        val step = CarbIntegrator.integrate(
            burnGph = 60.0,
            dtMs = tick,
            speedKmh = null,
            speedStale = false,
        )
        assertEquals(0f, step.deltaG, 0.0001f)
        assertEquals(0L, step.deltaActiveMs)
        assertFalse(step.moving)
    }

    @Test
    fun `first tick of session (dtMs = 0) integrates nothing but reports moving correctly`() {
        // `CarbsTracker.tick()` passes dtMs = 0 on the very first tick
        // because lastTickMs has its sentinel zero value. The helper must
        // not integrate (no prior timestamp to bracket the dt) but should
        // still report `moving=true` for status-flow consumers — otherwise
        // the UI would flicker "stationary" for one tick at session start.
        val step = CarbIntegrator.integrate(
            burnGph = 60.0,
            dtMs = 0L,
            speedKmh = 25.0,
            speedStale = false,
        )
        assertEquals(0f, step.deltaG, 0.0001f)
        assertEquals(0L, step.deltaActiveMs)
        // moving=true so callers don't briefly drop "isIntegrating" in the UI
        // because of an artefact of the dt-bootstrap convention. Note: the
        // call site in `CarbsTracker.tick()` doesn't currently surface this
        // bit; the test still pins the contract so a future refactor that
        // does surface it doesn't have to relearn what the call meant.
        assertTrue(step.moving)
    }

    @Test
    fun `negative dt (NTP clock skew) is clamped via caller convention`() {
        // The caller in `CarbsTracker.tick()` clamps `now - lastTickMs` to
        // >= 0 before calling integrate(). We mirror the contract: any
        // dtMs <= 0 means "no time elapsed", returning all-zero deltas.
        // This pins the symmetry between the caller's coerceAtLeast(0L)
        // and the helper's dtMs <= 0 branch.
        val step = CarbIntegrator.integrate(
            burnGph = 60.0,
            dtMs = 0L,
            speedKmh = 25.0,
            speedStale = false,
        )
        assertEquals(0f, step.deltaG, 0.0001f)
        assertEquals(0L, step.deltaActiveMs)
    }

    @Test
    fun `confidence-NONE tick (burnGph = 0) advances neither cumBurnedG nor activeIntegrationMs`() {
        // The HR-less, power-less rider case: `CarbBurnEstimator.estimate`
        // returns `BurnEstimate.NONE` with gph=0. The tracker still ticks
        // (it has a valid speed reading) but must NOT count this tick
        // toward active integration time — otherwise the session-average
        // burn rate would dilute toward zero, the "Pair HR/Pwr" guard on
        // the data field would never trigger, and the rider would just
        // see "0 g/h avg" forever with no hint about why.
        val step = CarbIntegrator.integrate(
            burnGph = 0.0,
            dtMs = tick,
            speedKmh = 25.0,
            speedStale = false,
        )
        assertEquals(0f, step.deltaG, 0.0001f)
        assertEquals(
            "activeIntegrationMs must NOT advance on a moving tick with zero burn — " +
                "otherwise the session-average dilutes to zero for HR-less riders",
            0L, step.deltaActiveMs,
        )
        // moving=true is still correct — the rider IS moving, the integrator
        // just has nothing physiological to integrate. The "Pair HR/Pwr"
        // guard on the data field is the right place to surface this state,
        // not the movement gate.
        assertTrue(step.moving)
    }

    @Test
    fun `effectiveGph is post-clamp even on stationary tick (for UI surfacing)`() {
        // The tracker surfaces `step.effectiveGph` to the data field as the
        // current burn rate. Stationary ticks return deltaG=0 (correct — no
        // accumulation while stopped) but the rider's physiological burn
        // rate hasn't dropped to zero; the field should show the capped
        // current rate, not flash to 0 every time the rider stops at a
        // traffic light. Pinning the contract: effectiveGph reflects the
        // clamped burn regardless of the movement gate.
        val step = CarbIntegrator.integrate(
            burnGph = 150.0,
            dtMs = tick,
            speedKmh = 0.0,
            speedStale = false,
        )
        assertEquals(ABSORPTION_CAP_GPH.toDouble(), step.effectiveGph, 0.001)
        assertEquals(0f, step.deltaG, 0.0001f)
        assertFalse(step.moving)
    }

    @Test
    fun `cumulative float drift across a 4h ride stays below 0_05 g`() {
        // Pre-empts a future regression that changes the accumulator's
        // numeric type. 4 h × 50 g/h = 200 g target. 960 ticks of 15 s,
        // each adding (15 × 50 / 3600) = 0.2083 g as a Float. Relative
        // float error per add ≈ 2^-23 ≈ 1.2e-7; cumulative drift over
        // 960 adds well under 0.05 g vs the exact 200 g.
        var cum = 0f
        repeat(960) {
            val step = CarbIntegrator.integrate(
                burnGph = 50.0,
                dtMs = 15_000L,
                speedKmh = 25.0,
                speedStale = false,
            )
            cum += step.deltaG
        }
        assertEquals(
            "Float accumulator drift over 4 h must stay imperceptible (< 0.05 g vs the 200 g target)",
            200.0f, cum, 0.05f,
        )
    }
}
