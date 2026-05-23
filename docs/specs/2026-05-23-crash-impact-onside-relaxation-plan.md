# IMPACT-phase On-Side Relaxation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the last false-negative residual — a crashed bike still moving when the Karoo autopause/auto-resume fires before SILENCE_CHECK is reached — by extending the on-side speed-rise relaxation principle into `handleImpact`.

**Architecture:** Rename the orientation accumulator (`silenceWindow*` → `orientation*`) so its name reflects the broader scope; start filling it during IMPACT (only on `accelOk` samples to filter post-impact noise); factor out a `currentOrientationAngleDeg()` helper; modify `handleImpact`'s gate to permit IMPACT→SILENCE_CHECK transition when decisive on-side evidence exists (`angle ≥ 60°` over ≥ 25 samples), even if the speed gate is open. Other gates (`accelOk`, `gyroOk`, `timeOk`) stay as the FP guards.

**Tech Stack:** Kotlin 2.0, Android, JUnit 4. Tests: `./gradlew :app:testDebugUnitTest`. JDK 17 required — `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` before any gradle command.

**Design spec:** `docs/specs/2026-05-23-crash-impact-onside-relaxation-design.md`

**Note on test scope:** `CrashStateMachine` is fully JVM-testable; this entire layer gets TDD coverage. No facade changes — `CrashDetectionManager` is untouched.

---

## File structure

| File | Change |
|------|--------|
| `app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachine.kt` | Rename accumulator fields; add `IMPACT_RELAXATION_MIN_SAMPLES = 25` companion constant; factor `currentOrientationAngleDeg()` helper; add IMPACT-phase accumulation in `handleImpact`; modify the gate. |
| `app/src/test/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachineTest.kt` | Five regression tests + one integration test for the IMPACT relaxation. |

---

## Task 1: Rename orientation accumulator fields

**Files:**
- Modify: `app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachine.kt`

Pure mechanical rename. The accumulator now spans IMPACT + SILENCE_CHECK so the old "silence-window" name is misleading. No behaviour change; all existing tests must continue to pass.

- [ ] **Step 1: Apply the rename**

In `CrashStateMachine.kt`, find/replace inside the file (4 field declarations + every usage site):
- `silenceWindowSumX` → `orientationSumX`
- `silenceWindowSumY` → `orientationSumY`
- `silenceWindowSumZ` → `orientationSumZ`
- `silenceWindowCount` → `orientationSampleCount`

This touches:
- The four `@Volatile private var ...` declarations.
- `handleSilenceCheck` (the accumulation block + the angle math).
- `resetSilenceWindow()` (the reset block).
- The KDoc comment block describing the accumulator.
- Any other place these names appear in `CrashStateMachine.kt`.

Also update the KDoc on the accumulator fields (and on `resetSilenceWindow()` if it references the old name) so the prose matches the new name. The accumulator description should mention that it now spans IMPACT + SILENCE_CHECK, but the actual extension to IMPACT is Task 3 — for this task, only the naming changes.

Confirm there are no remaining references with: `grep -n "silenceWindowSum\|silenceWindowCount" app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachine.kt`
Expected: no matches.

- [ ] **Step 2: Verify the full suite passes**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass with identical counts as before the rename.

- [ ] **Step 3: Commit**

Commit message exactly: `refactor(crash): rename silenceWindow accumulator to orientation` — NO Claude co-author trailer, no "Generated with Claude" text.

```bash
git add app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachine.kt
git commit -m "refactor(crash): rename silenceWindow accumulator to orientation"
```

---

## Task 2: Factor `currentOrientationAngleDeg()` helper

**Files:**
- Modify: `app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachine.kt`

Pure refactor. The angle math currently lives inline in `computeEffectiveSilenceMs`. Extract it into a private method so Task 4's IMPACT relaxation gate can reuse it. No behaviour change.

- [ ] **Step 1: Add the helper method**

In `CrashStateMachine.kt`, place this method somewhere among the other `private fun ...` helpers (e.g. near `resetSilenceWindow` / `resetTimers` — read the file for placement consistent with existing style):

```kotlin
    /**
     * Live orientation angle (degrees) between the accumulated gravity-vector
     * average and the pre-impact reference. Returns the `-1.0` sentinel when:
     *  - fewer than [MIN_ORIENTATION_SAMPLES] have been accumulated,
     *  - the pre-impact reference is invalid,
     *  - either vector magnitude is degenerate (< EPSILON).
     *
     * Consumed by [computeEffectiveSilenceMs] (latch decision in SILENCE_CHECK)
     * and by [handleImpact] (on-side relaxation gate — Task 4).
     */
    private fun currentOrientationAngleDeg(): Double {
        if (orientationSampleCount < MIN_ORIENTATION_SAMPLES) return -1.0
        if (!preImpactRef.valid) return -1.0
        val n = orientationSampleCount.toDouble()
        val curX = orientationSumX / n
        val curY = orientationSumY / n
        val curZ = orientationSumZ / n
        val curMag = sqrt(curX * curX + curY * curY + curZ * curZ)
        val refMag = sqrt(
            preImpactRef.x * preImpactRef.x +
            preImpactRef.y * preImpactRef.y +
            preImpactRef.z * preImpactRef.z
        )
        if (curMag < EPSILON || refMag < EPSILON) return -1.0
        val cosAngle = ((curX * preImpactRef.x + curY * preImpactRef.y + curZ * preImpactRef.z)
                       / (curMag * refMag)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cosAngle))
    }
```

- [ ] **Step 2: Refactor `computeEffectiveSilenceMs` to use the helper**

Find the block in `computeEffectiveSilenceMs` that computes the angle inline (it currently reads the accumulator, divides, sqrt's, dot-products, coerces, acos's). Read the file for the exact surrounding context. Replace the inline computation with a call to the helper. Concretely, the original block (verify against the file) is roughly:

```kotlin
        if (silenceWindowCount < MIN_ORIENTATION_SAMPLES) return legacyShort  // may still grow

        val n = silenceWindowCount.toDouble()
        val curX = silenceWindowSumX / n
        ...
        val cosAngle = ((...) / (curMag * refMag)).coerceIn(-1.0, 1.0)
        val angleDeg = Math.toDegrees(acos(cosAngle))
        lastOrientationAngleDeg = angleDeg
```

(Note: after Task 1 the field names are already `orientation*`.) Replace those lines with:

```kotlin
        val angleDeg = currentOrientationAngleDeg()
        if (angleDeg < 0.0) return legacyShort  // not enough samples / invalid ref / degenerate
        lastOrientationAngleDeg = angleDeg
```

The downstream `chosen = if (angleDeg >= ...) ... else ...` logic stays unchanged. The `latch(...)` / return logic stays unchanged.

Confirm: the helper's `MIN_ORIENTATION_SAMPLES` and the invalid-ref / degenerate-magnitude returns of `-1.0` all map back to the same `legacyShort` return that the inline code used. So behaviour is preserved exactly.

- [ ] **Step 3: Run the full suite**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass with identical counts.

- [ ] **Step 4: Commit**

Commit message exactly: `refactor(crash): factor currentOrientationAngleDeg() helper`

```bash
git add app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachine.kt
git commit -m "refactor(crash): factor currentOrientationAngleDeg() helper"
```

---

## Task 3: Add IMPACT-phase orientation accumulation

**Files:**
- Modify: `app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachine.kt`

Start filling the orientation accumulator during IMPACT on samples where `accelOk` holds. Add `resetSilenceWindow()` on the IMPACT→SILENCE_CHECK transition so SILENCE_CHECK starts with a clean accumulator (preserving the existing latch semantics). No new gate behaviour yet — `speedDropOk` is still the only path to SILENCE_CHECK. All existing tests must continue to pass with identical assertions.

- [ ] **Step 1: Add the IMPACT-phase accumulation**

In `CrashStateMachine.kt`, in `handleImpact`, find the existing local computations (`val timeSinceImpact = ...`, `val accelOk = ...`, `val gyroOk = ...`, `val timeOk = ...`, `val speedDropOk = ...`). Read the file for the exact surrounding code. Immediately after `accelOk` is computed (and before the gate check), insert:

```kotlin
        // Accumulate orientation during IMPACT on settled samples only. accelOk
        // filters the impact transient and any post-impact tumble noise, so the
        // average reflects the bike's actual resting orientation. The accumulator
        // is cleared on transition to SILENCE_CHECK below so SILENCE_CHECK starts
        // with a fresh count (preserving the latch's MIN_ORIENTATION_SAMPLES
        // semantics).
        if (accelOk) {
            orientationSumX += sample.accelX
            orientationSumY += sample.accelY
            orientationSumZ += sample.accelZ
            orientationSampleCount++
        }
```

- [ ] **Step 2: Reset the accumulator on IMPACT → SILENCE_CHECK transition**

Still in `handleImpact`, find the existing gate-success branch (the `if (accelOk && gyroOk && timeOk && speedDropOk) { ... }` block — the gate is still single-condition at this point, Task 4 modifies it). Inside that branch, after the existing assignments (`state = State.SILENCE_CHECK`, `silenceStartedMs = now`, `firstSilenceGapMs = ...`, `silenceCheckEnteredMs = now`) and before `return Decision.None`, add a call to `resetSilenceWindow()`:

```kotlin
        if (accelOk && gyroOk && timeOk && speedDropOk) {
            state = State.SILENCE_CHECK
            silenceStartedMs = now
            firstSilenceGapMs = now - impactStartedMs
            silenceCheckEnteredMs = now
            resetSilenceWindow()   // SILENCE_CHECK starts with fresh accumulator
            return Decision.None
        }
```

`resetSilenceWindow()` already clears `orientationSumX/Y/Z`, `orientationSampleCount`, `lockedEffectiveSilenceMs`, and `lastOrientationAngleDeg`. At this transition point, the latter two are already at their defaults (0L and -1.0) from prior MONITORING-return clearing, so the only meaningful effect is zeroing the orientation accumulator.

- [ ] **Step 3: Run the full suite — existing tests must pass unchanged**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all existing tests pass with identical counts (no new tests yet).

Reasoning for "behaviour preserved": the new accumulation in `handleImpact` adds samples to a counter that is then cleared at the IMPACT→SILENCE_CHECK transition. SILENCE_CHECK still starts with `orientationSampleCount = 0` and accumulates exactly as before. No existing code path reads the accumulator during IMPACT (Task 4 adds the only reader). So the change is purely setup for Task 4 — no observable behaviour difference yet.

- [ ] **Step 4: Commit**

Commit message exactly: `feat(crash): accumulate orientation during IMPACT phase`

```bash
git add app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachine.kt
git commit -m "feat(crash): accumulate orientation during IMPACT phase"
```

---

## Task 4: IMPACT on-side relaxation — gate change + tests

**Files:**
- Modify: `app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachine.kt`
- Test: `app/src/test/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachineTest.kt`

The core behavioural change. TDD: write the failing tests, add the new threshold + the gate modification, verify pass. Then add the remaining guard tests.

- [ ] **Step 1: Write the load-bearing failing test (the "engages" case)**

Add to `app/src/test/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachineTest.kt`. READ the file first for the `newSm`, `sample`, `PreImpactRef.INVALID` helpers and match their signatures. The default `Thresholds()` provides `crashConfirmSpeedKmh = 5`, `minSpeedForCrashKmh = 10`, `minTimeSinceImpactMs = 500`, `gyroMovingMax = 2.0`, `onSideRelaxationAngleDeg = 60.0`, `silenceDurationMs = 4500`.

```kotlin
    @Test
    fun `IMPACT on-side relaxation engages with sustained on-side and speed high`() {
        // Bike crashes and continues moving (rolls); speed never drops below
        // crashConfirmSpeedKmh = 5. Without the IMPACT relaxation, the IMPACT
        // gate would never open (speedDropOk stays false) and IMPACT_TIMEOUT
        // would fire. With the relaxation, ≥25 on-side samples (~500 ms) of
        // accel-still accumulation in IMPACT triggers the bypass.
        val (sm, _) = newSm()
        sm.onSpeedUpdate(25.0)   // satisfy minSpeedForCrashKmh = 10 at impact entry
        val base = 1_000_000L
        sm.onSample(sample(time = base, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))
        sm.onSpeedUpdate(10.0)   // bike rolling — speed stays above crashConfirmSpeedKmh = 5
        var t = base + 1000L     // beyond minTimeSinceImpactMs = 500
        var transitioned = false
        // 30 still on-side samples at 50 Hz (~600 ms) — past the 25-sample threshold.
        repeat(30) {
            t += 20L
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.5,
                az = 0.0, ax = 9.81))
            if (sm.state == CrashStateMachine.State.SILENCE_CHECK) transitioned = true
        }
        assertTrue(
            "IMPACT relaxation must transition to SILENCE_CHECK after ≥25 on-side samples",
            transitioned,
        )
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :app:testDebugUnitTest --tests "com.enderthor.kSafe.extension.crash.CrashStateMachineTest.IMPACT*"`
Expected: FAIL — without the new gate logic, `speedDropOk` stays false (speed=10), the gate never opens, IMPACT_TIMEOUT fires somewhere within `impactWindowMs = 20_000`, the SM returns to MONITORING. The `transitioned` flag stays `false` → assertion fails.

- [ ] **Step 3: Add the `IMPACT_RELAXATION_MIN_SAMPLES` constant**

In `CrashStateMachine.kt`, in the `private companion object`, add next to `MIN_ORIENTATION_SAMPLES`:

```kotlin
        /** Minimum samples in the IMPACT-phase orientation accumulator before
         *  the on-side speed-rise relaxation can engage. At ~50 Hz this is
         *  ~500 ms of sustained on-side accel-still — stricter than
         *  [MIN_ORIENTATION_SAMPLES] because bypassing the speed gate at
         *  IMPACT→SILENCE_CHECK transition is more consequential than the
         *  in-SILENCE_CHECK latch decision. */
        const val IMPACT_RELAXATION_MIN_SAMPLES: Int = 25
```

- [ ] **Step 4: Modify the `handleImpact` gate**

In `CrashStateMachine.kt`, in `handleImpact`, find the gate-success branch added/preserved in Task 3:

```kotlin
        if (accelOk && gyroOk && timeOk && speedDropOk) {
            state = State.SILENCE_CHECK
            silenceStartedMs = now
            firstSilenceGapMs = now - impactStartedMs
            silenceCheckEnteredMs = now
            resetSilenceWindow()
            return Decision.None
        }
```

Replace it with the relaxation-aware version:

```kotlin
        // On-side relaxation: bypass the speed gate when there is decisive
        // evidence the bike is on the ground. A bike on its side cannot be
        // ridden, so if the bike is moving (speed-drop gate is open because
        // the bike rolled after the rider went down), accelerometer
        // stillness plus on-side orientation is sufficient to transition
        // into SILENCE_CHECK. Other gates (accelOk, gyroOk, timeOk) remain
        // as the strong false-positive guards — handling motion, sustained
        // rotation (cornering), or insufficient settle time all block this
        // path. The 25-sample minimum prevents a transient angle flicker
        // from bypassing speed; ~500 ms of sustained on-side evidence is
        // required.
        val onSideRelaxed = orientationSampleCount >= IMPACT_RELAXATION_MIN_SAMPLES &&
                            currentOrientationAngleDeg() >= thresholds.onSideRelaxationAngleDeg
        val gateOk = accelOk && gyroOk && timeOk && (speedDropOk || onSideRelaxed)
        if (gateOk) {
            state = State.SILENCE_CHECK
            silenceStartedMs = now
            firstSilenceGapMs = now - impactStartedMs
            silenceCheckEnteredMs = now
            resetSilenceWindow()
            return Decision.None
        }
```

- [ ] **Step 5: Run the load-bearing test to verify it now passes**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :app:testDebugUnitTest --tests "com.enderthor.kSafe.extension.crash.CrashStateMachineTest.IMPACT*"`
Expected: PASS — the relaxation engages at sample 25 (~`base + 1500` ms), the SM transitions to SILENCE_CHECK, `transitioned` becomes true.

- [ ] **Step 6: Add the four guard tests + the integration test**

Add these to `CrashStateMachineTest.kt`, alongside the test from Step 1:

```kotlin
    @Test
    fun `IMPACT relaxation does NOT engage with fewer than 25 samples accumulated`() {
        // Only 20 on-side accel-still samples — short of the 25-sample threshold.
        val (sm, _) = newSm()
        sm.onSpeedUpdate(25.0)
        val base = 1_000_000L
        sm.onSample(sample(time = base, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))
        sm.onSpeedUpdate(10.0)
        var t = base + 1000L
        repeat(20) {   // < IMPACT_RELAXATION_MIN_SAMPLES = 25
            t += 20L
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.5,
                az = 0.0, ax = 9.81))
        }
        assertEquals(
            "fewer than 25 samples must keep IMPACT relaxation off",
            CrashStateMachine.State.IMPACT, sm.state,
        )
    }

    @Test
    fun `IMPACT relaxation does NOT engage when gyro is high (cornering)`() {
        // gyro > 2.0 rad/s simulates sustained cornering. gyroOk must block
        // the relaxation regardless of orientation.
        val (sm, _) = newSm()
        sm.onSpeedUpdate(25.0)
        val base = 1_000_000L
        sm.onSample(sample(time = base, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))
        sm.onSpeedUpdate(10.0)
        var t = base + 1000L
        repeat(30) {
            t += 20L
            // High gyro every sample — gyroOk = false → gate blocked.
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 3.0,
                az = 0.0, ax = 9.81))
        }
        assertEquals(
            "high gyro must block IMPACT relaxation (cornering safeguard)",
            CrashStateMachine.State.IMPACT, sm.state,
        )
    }

    @Test
    fun `IMPACT relaxation does NOT engage when orientation is upright`() {
        // Upright accel vector matches the pre-impact reference — angle ~0°,
        // well below the 60° relaxation threshold. Without speedDropOk, the
        // gate stays closed.
        val (sm, _) = newSm()
        sm.onSpeedUpdate(25.0)
        val base = 1_000_000L
        sm.onSample(sample(time = base, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))
        sm.onSpeedUpdate(10.0)
        var t = base + 1000L
        repeat(30) {
            t += 20L
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.5,
                az = 9.81, ax = 0.0))   // upright — angle ≈ 0°
        }
        assertEquals(
            "upright orientation must not engage the IMPACT relaxation",
            CrashStateMachine.State.IMPACT, sm.state,
        )
    }

    @Test
    fun `IMPACT relaxation does NOT engage when pre-impact reference is invalid`() {
        // With an invalid pre-impact reference, currentOrientationAngleDeg()
        // returns -1.0 and the relaxation cannot fire.
        val (sm, _) = newSm()
        sm.onSpeedUpdate(25.0)
        val base = 1_000_000L
        sm.onSample(sample(time = base, peak = 60.0, smoothed = 30.0, gyro = 0.5))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        sm.setPreImpactReference(PreImpactRef.INVALID)
        sm.onSpeedUpdate(10.0)
        var t = base + 1000L
        repeat(30) {
            t += 20L
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.5,
                az = 0.0, ax = 9.81))
        }
        assertEquals(
            "invalid pre-impact reference must block IMPACT relaxation",
            CrashStateMachine.State.IMPACT, sm.state,
        )
    }

    @Test
    fun `IMPACT on-side relaxation chains into SILENCE_CHECK relaxation for full crash-and-roll`() {
        // Full chain: real crash → IMPACT phase → bike rolls (speed stays high)
        // → IMPACT relaxation transitions to SILENCE_CHECK → SILENCE_CHECK
        // relaxation keeps the silence window running through the persisting
        // speed rise → Confirm fires at the 4.5 s on-side window.
        // Reproduces the MTB-descent residual scenario end-to-end.
        val (sm, _) = newSm()
        sm.onSpeedUpdate(25.0)
        val base = 1_000_000L
        sm.onSample(sample(time = base, peak = 70.0, smoothed = 40.0, gyro = 1.0))
        assertEquals(CrashStateMachine.State.IMPACT, sm.state)
        sm.setPreImpactReference(PreImpactRef(0.0, 0.0, 9.81, valid = true))
        sm.onSpeedUpdate(10.0)   // bike rolling, well above crashConfirmSpeedKmh = 5
        var t = base + 1000L
        // Phase 1: 30 IMPACT samples → IMPACT relaxation engages → SILENCE_CHECK.
        var transitioned = false
        repeat(30) {
            t += 20L
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.5,
                az = 0.0, ax = 9.81))
            if (sm.state == CrashStateMachine.State.SILENCE_CHECK) transitioned = true
        }
        assertTrue("phase 1: IMPACT relaxation must transition to SILENCE_CHECK", transitioned)
        // Phase 2: SILENCE_CHECK on-side relaxation keeps the silence window
        // running through the speed rise; Confirm fires at the 4.5 s window.
        // 250 samples at 20 ms = 5 s — past the 4.5 s confirm window.
        var confirmed = false
        repeat(250) {
            t += 20L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, gyro = 0.5,
                    az = 0.0, ax = 9.81)) is CrashStateMachine.Decision.Confirm) {
                confirmed = true
            }
        }
        assertTrue("phase 2: SILENCE_CHECK on-side relaxation must allow Confirm", confirmed)
        assertEquals(4_500L, sm.lastConfirmedSilenceMs)
    }
```

- [ ] **Step 7: Run the full suite**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass (the 5 new IMPACT-relaxation tests + 1 integration test + all existing tests).

- [ ] **Step 8: Commit**

Commit message exactly: `feat(crash): IMPACT-phase on-side relaxation closes auto-resume residual`

```bash
git add app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachine.kt \
        app/src/test/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachineTest.kt
git commit -m "feat(crash): IMPACT-phase on-side relaxation closes auto-resume residual"
```

---

## Final verification

- [ ] `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :app:testDebugUnitTest` — BUILD SUCCESSFUL, whole suite green.
- [ ] `grep -n "silenceWindowSum\|silenceWindowCount" app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachine.kt` — no matches (rename complete).
- [ ] `grep -n "IMPACT_RELAXATION_MIN_SAMPLES\|currentOrientationAngleDeg\|orientationSum" app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachine.kt` — references present in the expected places.
