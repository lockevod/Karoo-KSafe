# Pre-Impact Orientation Reference — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the crash-detector's learned-baseline orientation mechanism (dead off-road) with a local pre-impact gravity reference plus an impact-to-stop gap signal.

**Architecture:** `SensorReader` keeps a small ring buffer of recent accelerometer vectors and exposes the ~2 s pre-impact average. The facade injects that average into `CrashStateMachine` when an impact is detected. `computeEffectiveSilenceMs` picks the silence-window duration from two regimes — a long gap forces the 20 s window; otherwise the angle between the pre-impact reference and the silence-window gravity vector decides 4.5 s (on-side) vs 20 s (upright).

**Tech Stack:** Kotlin, Android (`SensorManager`), JUnit 4 unit tests (`./gradlew :app:testDebugUnitTest`). No `androidTest` source set — all tests JVM-runnable.

**Prerequisite:** A valid `local.properties` (with `sdk.dir`, `gpr.user`, `gpr.key`) must exist or Gradle fails hard. See `CLAUDE.md`.

**Design spec:** `docs/specs/2026-05-22-crash-detection-pre-impact-orientation-design.md`

---

## File structure

| File | Responsibility |
|------|----------------|
| `app/src/main/kotlin/com/enderthor/kSafe/extension/crash/PreImpactReference.kt` | **New.** `PreImpactRef` data class + pure `compute()` averaging function. |
| `app/src/test/kotlin/com/enderthor/kSafe/extension/crash/PreImpactReferenceTest.kt` | **New.** Tests for `PreImpactReference.compute`. |
| `app/src/main/kotlin/com/enderthor/kSafe/extension/crash/SensorReader.kt` | Add timestamped X/Y/Z ring buffer + `preImpactReference()`. |
| `app/src/test/kotlin/com/enderthor/kSafe/extension/crash/SensorReaderTest.kt` | Add ring-buffer / `preImpactReference` tests. |
| `app/src/main/kotlin/com/enderthor/kSafe/extension/crash/Thresholds.kt` | Add `delayedStopGapMs`; later remove baseline fields. |
| `app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachine.kt` | Pre-impact ref + gap capture + rewritten `computeEffectiveSilenceMs`; later remove baseline machinery. |
| `app/src/test/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachineTest.kt` | Replace orientation-baseline tests with regime tests + integration scenarios. |
| `app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashDetectionManager.kt` | Wire pre-impact ref on `EnterImpact`; remove baseline feed; update calibration logging. |
| `app/src/main/kotlin/com/enderthor/kSafe/extension/managers/CalibrationLogger.kt` | Remove `ORIENTATION_BASELINE` event. |
| `docs/crash-detection-algorithm.md` | Update the algorithm doc. |

---

## Task 1: PreImpactRef + pure averaging function

**Files:**
- Create: `app/src/main/kotlin/com/enderthor/kSafe/extension/crash/PreImpactReference.kt`
- Test: `app/src/test/kotlin/com/enderthor/kSafe/extension/crash/PreImpactReferenceTest.kt`

- [ ] **Step 1: Write the failing test**

Create `PreImpactReferenceTest.kt`:

```kotlin
package com.enderthor.kSafe.extension.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreImpactReferenceTest {

    /** Build a buffer of constant-vector samples at 20 ms spacing (50 Hz) ending at [endTs]. */
    private fun constantBuffer(
        x: Double, y: Double, z: Double,
        count: Int, endTs: Long, stepMs: Long = 20L,
    ): List<TimedVec3> = (0 until count).map { i ->
        TimedVec3(x, y, z, endTs - (count - 1 - i) * stepMs)
    }

    @Test
    fun `averages the slice excluding the guard window`() {
        // 200 samples (4 s) of an upright vector ending exactly at the impact.
        val buf = constantBuffer(0.0, 0.0, 9.81, count = 200, endTs = 100_000L)
        val ref = PreImpactReference.compute(buf, impactTsMs = 100_000L)
        assertTrue(ref.valid)
        assertEquals(0.0, ref.x, 1e-9)
        assertEquals(0.0, ref.y, 1e-9)
        assertEquals(9.81, ref.z, 1e-9)
    }

    @Test
    fun `returns invalid when too few samples in the window`() {
        // Only 10 samples — below the 50-sample minimum.
        val buf = constantBuffer(0.0, 0.0, 9.81, count = 10, endTs = 100_000L)
        val ref = PreImpactReference.compute(buf, impactTsMs = 100_000L)
        assertFalse(ref.valid)
    }

    @Test
    fun `the guard window excludes the impact transient`() {
        // 200 upright samples, then 5 huge "transient" samples in the last 100 ms.
        val upright = constantBuffer(0.0, 0.0, 9.81, count = 200, endTs = 99_900L)
        val transient = (0 until 5).map { TimedVec3(50.0, 50.0, 50.0, 99_920L + it * 20L) }
        val ref = PreImpactReference.compute(upright + transient, impactTsMs = 100_000L)
        // The transient samples fall inside the 250 ms guard and must be excluded.
        assertTrue(ref.valid)
        assertEquals(9.81, ref.z, 1e-9)
        assertEquals(0.0, ref.x, 1e-9)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.enderthor.kSafe.extension.crash.PreImpactReferenceTest"`
Expected: FAIL — `TimedVec3` / `PreImpactReference` unresolved.

- [ ] **Step 3: Write the implementation**

Create `PreImpactReference.kt`:

```kotlin
package com.enderthor.kSafe.extension.crash

/** One timestamped raw accelerometer vector (m/s², gravity included). */
data class TimedVec3(val x: Double, val y: Double, val z: Double, val tsMs: Long)

/**
 * The average gravity-vector direction of the bike in the window just before an
 * impact — a fresh, terrain-independent "upright" reference. [valid] is false when
 * not enough samples were available (cold start, or < ~2 s after a resume).
 */
data class PreImpactRef(
    val x: Double,
    val y: Double,
    val z: Double,
    val valid: Boolean,
) {
    companion object {
        val INVALID = PreImpactRef(0.0, 0.0, 0.0, valid = false)
    }
}

/**
 * Pure computation of the pre-impact reference. Kept free of Android types so it is
 * unit-testable on the JVM without constructing a `SensorEvent`.
 */
object PreImpactReference {
    /** Guard before the impact that excludes the impact transient (~60 ms rise). */
    const val GUARD_MS = 250L
    /** Averaging window length. */
    const val WINDOW_MS = 2_000L
    /** Minimum samples in the slice for the reference to be trusted (~1 s at 50 Hz). */
    const val MIN_SAMPLES = 50

    /**
     * Average the X/Y/Z of every sample whose timestamp falls in
     * `[impactTsMs - GUARD_MS - WINDOW_MS , impactTsMs - GUARD_MS]`.
     * Returns [PreImpactRef.INVALID] when fewer than [MIN_SAMPLES] qualify.
     */
    fun compute(
        buffer: List<TimedVec3>,
        impactTsMs: Long,
        windowMs: Long = WINDOW_MS,
        guardMs: Long = GUARD_MS,
        minSamples: Int = MIN_SAMPLES,
    ): PreImpactRef {
        val hi = impactTsMs - guardMs
        val lo = hi - windowMs
        var sx = 0.0; var sy = 0.0; var sz = 0.0; var n = 0
        for (s in buffer) {
            if (s.tsMs in lo..hi) {
                sx += s.x; sy += s.y; sz += s.z; n++
            }
        }
        if (n < minSamples) return PreImpactRef.INVALID
        return PreImpactRef(sx / n, sy / n, sz / n, valid = true)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.enderthor.kSafe.extension.crash.PreImpactReferenceTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/enderthor/kSafe/extension/crash/PreImpactReference.kt \
        app/src/test/kotlin/com/enderthor/kSafe/extension/crash/PreImpactReferenceTest.kt
git commit -m "feat(crash): pre-impact reference data class + pure averaging"
```

---

## Task 2: SensorReader pre-impact ring buffer

**Files:**
- Modify: `app/src/main/kotlin/com/enderthor/kSafe/extension/crash/SensorReader.kt`
- Test: `app/src/test/kotlin/com/enderthor/kSafe/extension/crash/SensorReaderTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `SensorReaderTest.kt` (inside the existing test class):

```kotlin
@Test
fun `preImpactReference averages the recent vector window`() {
    // Drive 200 synthetic accelerometer samples of a constant upright vector
    // through the reader, 20 ms apart, then ask for the pre-impact reference.
    val reader = newReaderForVectorTest()  // see helper below
    var t = 1_000_000L
    repeat(200) {
        reader.pushAccelForTest(x = 0f, y = 0f, z = 9.81f, tsMs = t)
        t += 20L
    }
    val impactTs = t  // just after the last sample
    val ref = reader.preImpactReference(impactTs)
    assertTrue(ref.valid)
    assertEquals(9.81, ref.z, 1e-3)
    assertEquals(0.0, ref.x, 1e-3)
}

@Test
fun `preImpactReference is invalid before enough samples arrive`() {
    val reader = newReaderForVectorTest()
    var t = 1_000_000L
    repeat(10) {
        reader.pushAccelForTest(x = 0f, y = 0f, z = 9.81f, tsMs = t)
        t += 20L
    }
    val ref = reader.preImpactReference(t)
    assertFalse(ref.valid)
}
```

Note: `SensorReader` cannot be fed real `SensorEvent`s in a JVM test (the constructor is package-private and the stub `android.jar` throws `Stub!`). The implementation below therefore adds a package-private `pushAccelForTest(...)` that exercises the exact same buffering path as `processAccel`, and a `newReaderForVectorTest()` helper that constructs a reader with a mocked `SensorManager`. If `SensorReaderTest` already has a mocked-`SensorManager` constructor helper, reuse it; otherwise add:

```kotlin
private fun newReaderForVectorTest(): SensorReader {
    val sm = org.mockito.Mockito.mock(android.hardware.SensorManager::class.java)
    return SensorReader(sensorManager = sm, onSample = {})
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.enderthor.kSafe.extension.crash.SensorReaderTest"`
Expected: FAIL — `pushAccelForTest` / `preImpactReference` unresolved.

- [ ] **Step 3: Write the implementation**

In `SensorReader.kt`, add a ring buffer next to the existing buffers. After the line
`private val varianceBuffer = DoubleRingBuffer(VARIANCE_WINDOW)` add:

```kotlin
    /**
     * Ring buffer of the most recent timestamped accelerometer vectors, used to
     * compute the pre-impact orientation reference. ~150 entries ≈ 3 s at 50 Hz —
     * covers the 2 s averaging window plus the 250 ms guard with margin.
     * Sensor-thread-only, like the other buffers.
     */
    private val vectorBuffer = Vec3RingBuffer(PRE_IMPACT_RING_CAPACITY)
```

In `processAccel`, immediately after `varianceBuffer.add(rawMagnitude)`, add:

```kotlin
        // Pre-impact orientation ring — raw X/Y/Z plus timestamp.
        vectorBuffer.add(x.toDouble(), y.toDouble(), z.toDouble(), clock.nowMs())
```

In `stop()`, after `varianceBuffer.clear()`, add:

```kotlin
        vectorBuffer.clear()
```

Add the public accessor (place it next to `magnitudeBufferSnapshot`):

```kotlin
    /**
     * The pre-impact orientation reference for an impact detected at [impactTsMs].
     * Snapshots the vector ring and delegates to the pure [PreImpactReference.compute].
     * Called once per impact event (rare) — the O(capacity) snapshot is negligible.
     */
    fun preImpactReference(impactTsMs: Long): PreImpactRef =
        PreImpactReference.compute(vectorBuffer.snapshot(), impactTsMs)

    /**
     * Drop the pre-impact vector ring. Called by the facade when the ride pauses
     * so an impact within ~2 s of resume yields an invalid (rather than stale)
     * reference. The reader stays registered across a pause, so the ring is NOT
     * cleared by [stop] in that case.
     */
    fun clearVectorBuffer() {
        vectorBuffer.clear()
    }

    /** Test-only: exercise the buffering path without a real SensorEvent. */
    internal fun pushAccelForTest(x: Float, y: Float, z: Float, tsMs: Long) {
        vectorBuffer.add(x.toDouble(), y.toDouble(), z.toDouble(), tsMs)
    }
```

Add the capacity constant to the `companion object`:

```kotlin
        /** Capacity of the pre-impact vector ring (~3 s at 50 Hz). */
        const val PRE_IMPACT_RING_CAPACITY = 150
```

At the end of the file, after the `DoubleRingBuffer` class, add:

```kotlin
/**
 * Fixed-capacity ring of timestamped 3-axis vectors. Backed by primitive arrays —
 * zero allocation per [add] on the 50 Hz sensor thread. NOT thread-safe; touched
 * only from the sensor callback thread, like [DoubleRingBuffer].
 */
internal class Vec3RingBuffer(private val capacity: Int) {
    init { require(capacity > 0) { "capacity must be positive" } }
    private val xs = DoubleArray(capacity)
    private val ys = DoubleArray(capacity)
    private val zs = DoubleArray(capacity)
    private val ts = LongArray(capacity)
    private var head = 0
    private var size = 0

    fun add(x: Double, y: Double, z: Double, tsMs: Long) {
        val idx = (head + size) % capacity
        if (size < capacity) {
            size++
        } else {
            head = (head + 1) % capacity
        }
        xs[idx] = x; ys[idx] = y; zs[idx] = z; ts[idx] = tsMs
    }

    fun clear() { head = 0; size = 0 }

    /** Snapshot of live entries in insertion order. Allocates — call off the hot path. */
    fun snapshot(): List<TimedVec3> {
        val out = ArrayList<TimedVec3>(size)
        var i = 0
        while (i < size) {
            val idx = (head + i) % capacity
            out.add(TimedVec3(xs[idx], ys[idx], zs[idx], ts[idx]))
            i++
        }
        return out
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.enderthor.kSafe.extension.crash.SensorReaderTest"`
Expected: PASS (all existing tests + the 2 new ones).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/enderthor/kSafe/extension/crash/SensorReader.kt \
        app/src/test/kotlin/com/enderthor/kSafe/extension/crash/SensorReaderTest.kt
git commit -m "feat(crash): SensorReader pre-impact vector ring + preImpactReference"
```

---

## Task 3: Add `delayedStopGapMs` to Thresholds

**Files:**
- Modify: `app/src/main/kotlin/com/enderthor/kSafe/extension/crash/Thresholds.kt`

- [ ] **Step 1: Add the field**

In `Thresholds.kt`, inside the `data class Thresholds(...)` constructor, after the cadence-gate block (`cadenceStaleThresholdMs`), add:

```kotlin
    // ── Delayed-stop gap (pre-impact orientation revision) ───────────────────
    /**
     * Gap (ms) between the impact and first stillness above which the stop is
     * treated as "delayed" — the rider kept riding after the impact, so the
     * event is false-positive-prone and the long [silenceDurationUprightMs]
     * window is required regardless of orientation. A real crash stops within
     * 1–4 s of the impact, comfortably below this. See the design spec.
     */
    val delayedStopGapMs: Long = 8_000L,
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (`buildThresholds` in `CrashDetectionManager` does not set this field — it uses the default, which is intended).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/enderthor/kSafe/extension/crash/Thresholds.kt
git commit -m "feat(crash): add delayedStopGapMs threshold (8s)"
```

---

## Task 4: CrashStateMachine — pre-impact ref, gap, rewritten silence decision

**Files:**
- Modify: `app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachine.kt`
- Test: `app/src/test/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachineTest.kt`

This task adds the new behaviour. The learned-baseline fields/methods are left in place (dead) and removed in Task 6, so the build stays green.

- [ ] **Step 1: Extend the test `sample()` helper with X/Y/Z**

In `CrashStateMachineTest.kt`, replace the `sample(...)` helper with one that also accepts axes:

```kotlin
    /** Build a [SensorSample]. `peak`, `smoothed`, `raw` default to each other. */
    private fun sample(
        time: Long,
        peak: Double = 0.0,
        smoothed: Double = peak,
        raw: Double = peak,
        gyro: Double = 0.0,
        gpsStale: Boolean = false,
        ax: Double = 0.0,
        ay: Double = 0.0,
        az: Double = 0.0,
    ) = SensorSample(
        rawMagnitude = raw,
        smoothedMagnitude = smoothed,
        peakMagnitude = peak,
        gyroMag = gyro,
        timestampMs = time,
        gpsStale = gpsStale,
        accelX = ax,
        accelY = ay,
        accelZ = az,
    )
```

- [ ] **Step 2: Write the failing tests for the two regimes**

Add to `CrashStateMachineTest.kt`:

```kotlin
    // Drive an SM from MONITORING into SILENCE_CHECK with a chosen impact→silence gap.
    // Returns the SM already inside SILENCE_CHECK. Quiet still samples have magnitude
    // ~GRAVITY so the IMPACT→SILENCE gates pass; az defaults to gravity (upright).
    private fun smEnteringSilence(
        gapMs: Long,
        preRef: PreImpactRef,
        silenceAz: Double = 9.81,
        silenceAx: Double = 0.0,
    ): Pair<CrashStateMachine, ClockHandle> {
        val (sm, h) = newSm()
        sm.onSpeedUpdate(20.0)
        // Impact at t=0 (relative); use a high base time to clear the cold-start guard.
        val base = 1_000_000L
        sm.onSample(sample(time = base, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        sm.setPreImpactReference(preRef)
        // Stay in IMPACT until `gapMs` has elapsed: speed still high → speed gate blocks.
        var t = base + 1000L
        while (t < base + gapMs) {
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.1))
            t += 1000L
        }
        // Drop speed so the IMPACT→SILENCE_CHECK gate opens, then one settling sample.
        sm.onSpeedUpdate(0.0)
        sm.onSample(sample(time = base + gapMs, raw = 9.81, smoothed = 9.81, gyro = 0.1,
            ax = silenceAx, az = silenceAz))
        return sm to h
    }

    @Test
    fun `gap regime - long gap forces the 20s window even when bike is on-side`() {
        // On-side silence vector (az≈0, ax≈9.81) would normally give 4.5s, but a
        // 12s gap must override that and require 20s.
        val (sm, _) = smEnteringSilence(
            gapMs = 12_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 0.0, silenceAx = 9.81,
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        // Feed 6 s of continuous stillness — must NOT confirm (20s required).
        var t = 1_012_000L
        repeat(6) {
            t += 1000L
            val d = sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
            assertEquals(CrashStateMachine.Decision.None, d)
        }
    }

    @Test
    fun `orientation regime - prompt stop on-side confirms at 4_5s`() {
        val (sm, _) = smEnteringSilence(
            gapMs = 2_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 0.0, silenceAx = 9.81,   // ~90° from the upright reference
        )
        // Feed stillness; Confirm must arrive once ~4.5 s of silence elapsed.
        var t = 1_002_000L
        var confirmed = false
        repeat(7) {
            t += 1000L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
                    is CrashStateMachine.Decision.Confirm) confirmed = true
        }
        assertTrue("expected Confirm within ~5s of silence", confirmed)
        assertEquals(4_500L, sm.lastConfirmedSilenceMs)
    }

    @Test
    fun `orientation regime - prompt stop upright requires the 20s window`() {
        val (sm, _) = smEnteringSilence(
            gapMs = 2_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 9.81, silenceAx = 0.0,   // same as the reference → upright
        )
        var t = 1_002_000L
        repeat(6) {
            t += 1000L
            val d = sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 9.81, ax = 0.0))
            assertEquals(CrashStateMachine.Decision.None, d)  // 6s < 20s
        }
    }

    @Test
    fun `orientation regime - invalid reference on a prompt stop falls back to 4_5s`() {
        val (sm, _) = smEnteringSilence(
            gapMs = 2_000L,
            preRef = PreImpactRef.INVALID,
            silenceAz = 9.81,
        )
        var t = 1_002_000L
        var confirmed = false
        repeat(7) {
            t += 1000L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 9.81))
                    is CrashStateMachine.Decision.Confirm) confirmed = true
        }
        assertTrue(confirmed)
        assertEquals(4_500L, sm.lastConfirmedSilenceMs)
    }
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.enderthor.kSafe.extension.crash.CrashStateMachineTest"`
Expected: FAIL — `setPreImpactReference` unresolved (plus the old baseline tests still present; they are removed in Step 6).

- [ ] **Step 4: Add the pre-impact ref + gap fields**

In `CrashStateMachine.kt`, after the orientation accumulator fields (after `lockedEffectiveSilenceMs`), add:

```kotlin
    // ── Pre-impact orientation reference (pre-impact-revision) ───────────────
    /**
     * The bike's gravity-vector direction averaged over ~2 s before the impact.
     * Set by the facade via [setPreImpactReference] when an impact is detected.
     * [PreImpactRef.INVALID] means no usable pre-impact data (cold start / just
     * after a resume) — the orientation regime then falls back to the legacy
     * short window.
     */
    @Volatile private var preImpactRef: PreImpactRef = PreImpactRef.INVALID

    /**
     * Gap between the impact and the FIRST time the state machine reached
     * SILENCE_CHECK for this event, in the sample-time domain. `0L` until that
     * first transition. Used by [computeEffectiveSilenceMs]'s gap regime.
     */
    @Volatile var firstSilenceGapMs: Long = 0L
        private set

    /**
     * Angle (degrees) between the pre-impact reference and the silence-window
     * gravity vector, as computed on the last [computeEffectiveSilenceMs] call
     * that reached the orientation branch. `-1.0` when not computed (gap regime,
     * invalid reference, or too few silence samples). For calibration logging only.
     */
    @Volatile var lastOrientationAngleDeg: Double = -1.0
        private set
```

Add the public getter for the reference (so the facade can log it), next to the fields above:

```kotlin
    /** Snapshot of the current pre-impact reference. For calibration logging. */
    val preImpactReference: PreImpactRef get() = preImpactRef
```

- [ ] **Step 5: Add `setPreImpactReference`, capture the gap, rewrite `computeEffectiveSilenceMs`**

Add the setter near `setThresholds`:

```kotlin
    /** Inject the pre-impact orientation reference. Called by the facade on impact entry. */
    fun setPreImpactReference(ref: PreImpactRef) {
        preImpactRef = ref
    }
```

In `handleImpact`, in the branch that transitions to SILENCE_CHECK, capture the gap.
Replace:

```kotlin
        if (accelOk && gyroOk && timeOk && speedDropOk) {
            state = State.SILENCE_CHECK
            silenceStartedMs = now
            return Decision.None
        }
```

with:

```kotlin
        if (accelOk && gyroOk && timeOk && speedDropOk) {
            state = State.SILENCE_CHECK
            silenceStartedMs = now
            // First (and only) IMPACT → SILENCE_CHECK transition of this event:
            // freeze how long the rider kept moving after the impact.
            firstSilenceGapMs = now - impactStartedMs
            return Decision.None
        }
```

Replace the entire `computeEffectiveSilenceMs` function body with:

```kotlin
    /**
     * Decide the silence-window duration from two regimes (see the design spec):
     *   - Gap regime: a long impact→stillness gap means the rider kept riding
     *     after the impact → false-positive-prone → require the 20 s window.
     *   - Orientation regime (prompt stops only): the angle between the
     *     pre-impact reference and the silence-window gravity vector decides
     *     on-side (legacy short window) vs upright (20 s window).
     * The chosen value is latched for the rest of the window.
     */
    private fun computeEffectiveSilenceMs(gpsStale: Boolean): Long {
        if (lockedEffectiveSilenceMs > 0L) return lockedEffectiveSilenceMs

        val legacyShort = if (gpsStale) thresholds.gpsStaleSilenceDurationMs
                          else thresholds.silenceDurationMs

        // Gap regime — delayed stop. Needs no orientation data.
        if (firstSilenceGapMs > thresholds.delayedStopGapMs) {
            lockedEffectiveSilenceMs = thresholds.silenceDurationUprightMs
            return lockedEffectiveSilenceMs
        }

        // Orientation regime — prompt stop.
        if (!preImpactRef.valid) {
            lockedEffectiveSilenceMs = legacyShort   // never becomes valid → latch now
            return legacyShort
        }
        if (silenceWindowCount < MIN_ORIENTATION_SAMPLES) return legacyShort  // may still grow

        val n = silenceWindowCount.toDouble()
        val curX = silenceWindowSumX / n
        val curY = silenceWindowSumY / n
        val curZ = silenceWindowSumZ / n
        val curMag = sqrt(curX * curX + curY * curY + curZ * curZ)
        val refMag = sqrt(
            preImpactRef.x * preImpactRef.x +
            preImpactRef.y * preImpactRef.y +
            preImpactRef.z * preImpactRef.z
        )
        // Degenerate vector — cannot classify; use the short window without latching
        // (a later sample may yield a usable average).
        if (curMag < EPSILON || refMag < EPSILON) return legacyShort

        val cosAngle = ((curX * preImpactRef.x + curY * preImpactRef.y + curZ * preImpactRef.z)
                       / (curMag * refMag)).coerceIn(-1.0, 1.0)
        val angleDeg = Math.toDegrees(acos(cosAngle))
        lastOrientationAngleDeg = angleDeg

        val chosen = if (angleDeg >= thresholds.uprightAngleThresholdDegrees) legacyShort
                     else thresholds.silenceDurationUprightMs
        lockedEffectiveSilenceMs = chosen
        return chosen
    }
```

In `resetTimers()`, reset the gap:

```kotlin
    private fun resetTimers() {
        impactStartedMs = 0L
        silenceStartedMs = 0L
        firstSilenceGapMs = 0L
    }
```

In `resetSilenceWindow()`, reset the angle sentinel (add the line):

```kotlin
        lockedEffectiveSilenceMs = 0L
        lastOrientationAngleDeg = -1.0
```

In `reset()`, `resumeForRide()` and `onPause()`, reset the reference and gap.
Add to each of those three functions:

```kotlin
        preImpactRef = PreImpactRef.INVALID
        firstSilenceGapMs = 0L
        lastOrientationAngleDeg = -1.0
```

(`reset()` and `resumeForRide()` already null `lockedEffectiveSilenceMs`; `onPause()`
calls `resetSilenceWindow()`. Adding the three lines above to each is harmless and
explicit.)

- [ ] **Step 6: Remove the obsolete orientation-baseline tests**

In `CrashStateMachineTest.kt`, delete these `@Test` functions (they assert the old
learned-baseline `computeEffectiveSilenceMs` behaviour) and the `smInSilenceCheckWithBaseline`
helper:

- `silence_check uses upright duration when bike orientation matches baseline`
- `silence_check falls back to legacy duration when baseline not ready`
- `scenario bump plus brake plus stop upright does not confirm before 20s`
- `silence_check upright duration does not collapse if orientation drifts past 45deg mid-window`
- `lastConfirmedSilenceMs reflects upright window when fired`
- the `smInSilenceCheckWithBaseline(...)` private helper

The test `orientation accumulator resets when stillness is broken` is **kept** — the
silence-window accumulator still resets on a break in the new design. If its setup calls
`feedBaselineSample`, adapt it to use `setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81,
valid = true))` instead; the assertion (accumulator cleared after a break) is unchanged.

Leave the pure baseline-learner tests (`baseline is not ready...`, `baseline becomes ready...`,
`baseline learner ignores samples...`, `baseline averages...`, `reset clears the baseline...`,
`resumeForRide preserves baseline...`, `baseline adapts to new orientation...`) for now —
Task 6 deletes them together with the learner.

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.enderthor.kSafe.extension.crash.CrashStateMachineTest"`
Expected: PASS — the 4 new regime tests plus all retained tests.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachine.kt \
        app/src/test/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachineTest.kt
git commit -m "feat(crash): gap + pre-impact orientation silence-window decision"
```

---

## Task 5: Facade — inject the pre-impact reference on impact

**Files:**
- Modify: `app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashDetectionManager.kt`

The facade has no JVM unit test (Android dependencies); verify via compile + the full
suite. The integration coverage for the behaviour lives in Task 8.

- [ ] **Step 1: Wire the reference on `EnterImpact`**

In `CrashDetectionManager.kt`, in the `when (decision)` block, change the `EnterImpact`
branch from:

```kotlin
            is CrashStateMachine.Decision.EnterImpact -> {
                logImpactEnter(sample, decision.reason, boostActive)
            }
```

to:

```kotlin
            is CrashStateMachine.Decision.EnterImpact -> {
                // Capture the ~2 s pre-impact orientation reference and hand it to
                // the state machine before the SILENCE_CHECK phase consumes it.
                stateMachine.setPreImpactReference(
                    sensorReader.preImpactReference(sample.timestampMs)
                )
                logImpactEnter(sample, decision.reason, boostActive)
            }
```

- [ ] **Step 2: Clear the vector ring on ride pause**

In `CrashDetectionManager.onPause()`, add a call so the ring drops its pre-pause
vectors. Change:

```kotlin
    fun onPause() {
        stateMachine.onPause()
        Timber.d("CrashDetectionManager: state machine paused (baseline preserved)")
    }
```

to:

```kotlin
    fun onPause() {
        stateMachine.onPause()
        sensorReader.clearVectorBuffer()
        Timber.d("CrashDetectionManager: state machine paused")
    }
```

- [ ] **Step 3: Verify compile + full suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — all tests pass (no behavioural test for the facade wiring;
this guards against a compile/regression break).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashDetectionManager.kt
git commit -m "feat(crash): facade injects pre-impact reference on impact"
```

---

## Task 6: Delete the learned-baseline machinery

**Files:**
- Modify: `app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachine.kt`
- Modify: `app/src/main/kotlin/com/enderthor/kSafe/extension/crash/Thresholds.kt`
- Modify: `app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashDetectionManager.kt`
- Modify: `app/src/main/kotlin/com/enderthor/kSafe/extension/managers/CalibrationLogger.kt`
- Modify: `app/src/test/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachineTest.kt`

- [ ] **Step 1: Remove the baseline tests**

In `CrashStateMachineTest.kt`, delete these `@Test` functions and any baseline-only
helper they use:

- `baseline is not ready before minSamples cruising samples have been fed`
- `baseline becomes ready after minSamples cruising samples`
- `baseline learner ignores samples while in IMPACT state`
- `baseline averages multiple samples`
- `reset clears the baseline state` — if it also asserts non-baseline reset behaviour,
  keep that part and drop only the baseline assertions.
- `resumeForRide preserves baseline and counter` — rewrite to assert `resumeForRide`
  resets `firstSilenceGapMs`/`preImpactReference` instead, or delete if redundant.
- `baseline adapts to new orientation when bike is remounted mid-ride`

- [ ] **Step 2: Remove baseline machinery from `CrashStateMachine`**

In `CrashStateMachine.kt` delete:
- the fields `baselineX`, `baselineY`, `baselineZ`, `baselineSampleCount`;
- the methods `feedBaselineSample`, `isBaselineReady`, `baselineVector`;
- the baseline-field resets inside `reset()` (`baselineX = 0.0` … `baselineSampleCount = 0`);
- the `import kotlin.math.acos` stays (still used by `computeEffectiveSilenceMs`);
  `sqrt` stays. Remove no other imports.

`resumeForRide()` already keeps no baseline lines to remove beyond its comment — update
the KDoc of `reset()` / `resumeForRide()` that mentions "baseline" to drop the reference.

- [ ] **Step 3: Remove baseline fields from `Thresholds`**

In `Thresholds.kt` delete the "Orientation-aware silence (revision 5)" fields
`baselineMinSamples` and `baselineCruisingMinSpeedKmh`. **Keep** `silenceDurationUprightMs`
and `uprightAngleThresholdDegrees` — both are still used. Update the surrounding KDoc
block so it no longer describes baseline learning.

- [ ] **Step 4: Remove baseline feed + logging from the facade**

In `CrashDetectionManager.kt`:
- Delete the entire `// ─── Orientation: feed baseline learner ───` block (the
  `if (priorState == ... feedBaselineSample(...))` statement) and the
  `// Emit the baseline-ready event exactly once per ride.` block
  (`if (!loggedBaselineReady ...) { ... ORIENTATION_BASELINE ... }`).
- Delete the field `@Volatile private var loggedBaselineReady = false` and its reset
  (`loggedBaselineReady = false`).
- Delete the `const val BASELINE_CRUISING_MAX_STDDEV` companion constant and its KDoc.
- In `logImpactEnter`, swap the baseline fields for the pre-impact reference. Replace
  the two lines
  `val (bx, by, bz) = stateMachine.baselineVector()` and
  `val baselineReady = stateMachine.isBaselineReady()`
  with the single line `val ref = stateMachine.preImpactReference`. In the format string
  replace the suffix `,base_ready=$baselineReady,base_x=%.2f,base_y=%.2f,base_z=%.2f`
  with `,pre_valid=${ref.valid},pre_x=%.2f,pre_y=%.2f,pre_z=%.2f`, and replace the trailing
  `formatUs` arguments `bx, by, bz` with `ref.x, ref.y, ref.z`. (The reference is already
  set on the state machine by Task 5 before `logImpactEnter` runs.)

- [ ] **Step 5: Remove the `ORIENTATION_BASELINE` calibration event**

In `CalibrationLogger.kt`, delete the `ORIENTATION_BASELINE` entry from the `Event` enum.
Confirm no other reference remains:

Run: `grep -rn "ORIENTATION_BASELINE\|feedBaselineSample\|isBaselineReady\|baselineVector\|baselineMinSamples\|BASELINE_CRUISING" app/src`
Expected: no matches.

- [ ] **Step 6: Run the full suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — all tests pass.

- [ ] **Step 7: Commit**

```bash
git add app/src
git commit -m "refactor(crash): delete the learned-baseline orientation machinery"
```

---

## Task 7: Calibration logging — new fields

**Files:**
- Modify: `app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashDetectionManager.kt`

Adds the fields that keep future logs auditable. No behavioural change.

- [ ] **Step 1: SILENCE_ENTER — add gap + pre-impact reference**

In `logSilenceEnter`, replace the `calibLogger?.log(...)` body with:

```kotlin
        calibLogger?.log(CalibrationLogger.Event.SILENCE_ENTER) {
            val ref = stateMachine.preImpactReference
            "deviation=%.2f,gyro=%.2f,speed=%.1f,gps_stale=${lastGpsStaleState},gap_ms=${stateMachine.firstSilenceGapMs},pre_valid=${ref.valid},pre_x=%.2f,pre_y=%.2f,pre_z=%.2f".formatUs(
                deviation, sample.gyroMag, currentSpeedKmh, ref.x, ref.y, ref.z)
        }
```

- [ ] **Step 2: CRASH_CONFIRMED — add gap, angle, decided_by**

In `logCrashConfirmed`, after the existing `silencePath` computation, add the
`decided_by` derivation and extend the log row. Insert before the
`calibLogger?.log(CalibrationLogger.Event.CRASH_CONFIRMED)` call:

```kotlin
        val gapMs = stateMachine.firstSilenceGapMs
        val angle = stateMachine.lastOrientationAngleDeg
        val ref = stateMachine.preImpactReference
        val decidedBy = when {
            gapMs > stateMachine.thresholds.delayedStopGapMs -> "GAP"
            !ref.valid -> "UNKNOWN"
            angle < 0.0 -> "UNKNOWN"
            angle >= stateMachine.thresholds.uprightAngleThresholdDegrees -> "ORIENT_ONSIDE"
            else -> "ORIENT_UPRIGHT"
        }
```

Then extend the existing CRASH_CONFIRMED log row. The format string currently ends with
`...,countdown_s=${config.countdownSeconds}` immediately before the closing `"`. Make two
edits to that single `.formatUs(...)` statement, leaving every existing field and argument
unchanged:

1. In the format string, immediately after `countdown_s=${config.countdownSeconds}` and
   before the closing `"`, append: `,gap_ms=$gapMs,pre_impact_angle=%.1f,decided_by=$decidedBy`
2. In the `formatUs(...)` argument list, append `angle` as the final argument (it fills
   the new `%.1f` placeholder; the existing arguments keep their order).

- [ ] **Step 3: IMPACT_TIMEOUT — add pre_valid**

In `handleReturnToMonitoring`, in the `IMPACT` branch's `IMPACT_TIMEOUT` log, append
`,pre_valid=${stateMachine.preImpactReference.valid}` to the end of the format string.
No new `formatUs` argument is needed (it is a plain interpolation).

- [ ] **Step 4: Verify compile + full suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — all tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashDetectionManager.kt
git commit -m "feat(crash): log gap, pre-impact reference and decision regime"
```

---

## Task 8: Integration scenario tests

**Files:**
- Modify: `app/src/test/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachineTest.kt`

- [ ] **Step 1: Write the failing scenario tests**

Add to `CrashStateMachineTest.kt`:

```kotlin
    // ── Integration: the 60a27a false positive ──────────────────────────────

    @Test
    fun `scenario - bump then 17s ride then upright stop does not confirm at 4_5s`() {
        // Reproduces ksafe_v1.2.0_60a27a_k24.csv event 1: hard spike, 17 s of
        // continued riding, then a still upright stop. Gap regime → 20 s window.
        val (sm, _) = newSm()
        sm.onSpeedUpdate(20.0)
        val base = 1_000_000L
        sm.onSample(sample(time = base, peak = 60.0, smoothed = 30.0, gyro = 0.8))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        // Pre-impact reference: upright (the rider was cruising before the bump).
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))
        // 17 s of riding — speed stays high so IMPACT does not advance.
        var t = base + 1000L
        while (t < base + 17_000L) {
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.2))
            t += 1000L
        }
        // Rider stops, bike upright.
        sm.onSpeedUpdate(0.0)
        sm.onSample(sample(time = base + 17_000L, raw = 9.81, smoothed = 9.81, az = 9.81))
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        // 6 s of perfect stillness must NOT confirm — the 20 s gap window applies.
        t = base + 17_000L
        repeat(6) {
            t += 1000L
            val d = sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 9.81))
            assertEquals(CrashStateMachine.Decision.None, d)
        }
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
    }

    @Test
    fun `scenario - real crash prompt stop on-side confirms at 4_5s`() {
        val (sm, _) = newSm()
        sm.onSpeedUpdate(25.0)
        val base = 1_000_000L
        sm.onSample(sample(time = base, peak = 70.0, smoothed = 40.0, gyro = 3.0))
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))  // was upright
        // Prompt stop ~2 s later, bike on its side (az≈0, ax≈9.81).
        sm.onSpeedUpdate(0.0)
        var t = base + 2_000L
        sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        var confirmed = false
        repeat(7) {
            t += 1000L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
                    is CrashStateMachine.Decision.Confirm) confirmed = true
        }
        assertTrue("on-side prompt stop should confirm at 4.5s", confirmed)
        assertEquals(4_500L, sm.lastConfirmedSilenceMs)
    }
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.enderthor.kSafe.extension.crash.CrashStateMachineTest"`
Expected: PASS — both scenarios. (If `scenario - bump then 17s...` fails by confirming,
the gap regime is not engaging — re-check Task 4 Step 5.)

- [ ] **Step 3: Commit**

```bash
git add app/src/test/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachineTest.kt
git commit -m "test(crash): integration scenarios for the gap + orientation regimes"
```

---

## Task 9: Update the algorithm doc

**Files:**
- Modify: `docs/crash-detection-algorithm.md`

- [ ] **Step 1: Rewrite the orientation section**

In `docs/crash-detection-algorithm.md`, replace any description of the learned-baseline
"orientation-aware silence" (Revision 5) with the new mechanism:

- the pre-impact orientation reference (2 s window, 250 ms guard, captured at impact);
- the two regimes — gap (`delayedStopGapMs = 8 s` → 20 s window) and orientation
  (prompt stops: on-side → 4.5 s, upright → 20 s, invalid reference → 4.5 s);
- remove all mention of `baselineMinSamples`, the cruising gates, and the
  `ORIENTATION_BASELINE` calibration event;
- update the calibration-log field list with `gap_ms`, `pre_x/y/z`, `pre_valid`,
  `pre_impact_angle`, `decided_by`.

Bump the doc's revision header (it currently reads "revision 4 — contextual sensor
data") to a new revision line naming the pre-impact orientation reference.

- [ ] **Step 2: Verify the threshold tables match the code**

Cross-check every numeric value in the doc against `Thresholds.kt` (`silenceDurationMs`
4.5 s, `silenceDurationUprightMs` 20 s, `uprightAngleThresholdDegrees` 45°,
`delayedStopGapMs` 8 s). `CLAUDE.md` requires the doc and the threshold tables to stay
in sync.

- [ ] **Step 3: Commit**

```bash
git add docs/crash-detection-algorithm.md
git commit -m "docs(crash): document the pre-impact orientation reference"
```

---

## Final verification

- [ ] Run the whole suite: `./gradlew :app:testDebugUnitTest` — BUILD SUCCESSFUL.
- [ ] `grep -rn "baseline\|ORIENTATION_BASELINE" app/src/main` — only incidental matches, no learned-baseline code.
- [ ] Confirm the four crash-package source files still compile: `./gradlew :app:compileDebugKotlin`.
