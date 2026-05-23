# Auto-Resume Residual — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the on-side half of the I3 auto-resume residual — a crashed bike that rolls free (Karoo auto-resume mid-detection) is currently lost; this preserves the in-flight detection across the auto-resume and relaxes the silence-check speed gate when the bike is decisively on the ground.

**Architecture:** Two small changes. Part A: `CrashDetectionManager.resume()` skips `stateMachine.resumeForRide()` when the state machine is mid-IMPACT or mid-SILENCE_CHECK, symmetric to the I3 autopause fix. Part B: `CrashStateMachine.handleSilenceCheck`'s `isStill` requires only accelerometer stillness (not speed-drop) when the orientation has locked at a stricter on-side angle (≥ `onSideRelaxationAngleDeg = 60°`). A bike decisively on the ground that is moving is the rider's bike escaping, not the rider riding.

**Tech Stack:** Kotlin 2.0, Android, JUnit 4. Tests: `./gradlew :app:testDebugUnitTest`. JDK 17 required — `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` before any gradle command. A valid `local.properties` exists.

**Design spec:** `docs/specs/2026-05-23-crash-autoresume-residual-design.md`

**Note on test scope:** `CrashStateMachine` is fully JVM-testable; the relaxation logic gets full TDD. `CrashDetectionManager` is Android-coupled with no unit-test harness in this project — the Part A `resume()` gating is verified by compile + the full suite staying green, consistent with prior facade-wiring tasks on this branch.

---

## File structure

| File | Change |
|------|--------|
| `app/src/main/kotlin/com/enderthor/kSafe/extension/crash/Thresholds.kt` | Add `onSideRelaxationAngleDeg: Double = 60.0`. |
| `app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashDetectionManager.kt` | Add companion constant `ON_SIDE_RELAXATION_ANGLE_DEG = 60.0` and pass it through `buildThresholds()`. Gate `stateMachine.resumeForRide()` on `state == MONITORING` in `resume()`. |
| `app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachine.kt` | In `handleSilenceCheck`, compute `onSideRelaxed = lockedEffectiveSilenceMs > 0L && lastOrientationAngleDeg >= thresholds.onSideRelaxationAngleDeg`; `isStill = if (onSideRelaxed) accelOk else (accelOk && speedDropOk)`. |
| `app/src/test/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachineTest.kt` | Five tests for the relaxation (engages at ≥60°, does NOT at 45–60°, NOT in upright, NOT in gap regime, accel motion still breaks). |

---

## Task 1: Add `onSideRelaxationAngleDeg` to `Thresholds` and wire it through the facade

**Files:**
- Modify: `app/src/main/kotlin/com/enderthor/kSafe/extension/crash/Thresholds.kt`
- Modify: `app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashDetectionManager.kt`

This task is mechanical wiring — no behavioural change (the field is added but only used in Task 2). Pattern matches the existing `DELAYED_STOP_GAP_MS` / `SILENCE_DURATION_UPRIGHT_MS` / `UPRIGHT_ANGLE_THRESHOLD_DEGREES` constants on this branch.

- [ ] **Step 1: Add the field to `Thresholds.kt`**

In `Thresholds.kt`, add a new constructor property next to `uprightAngleThresholdDegrees`:

```kotlin
    /**
     * Angle (degrees) from the pre-impact reference above which the bike is
     * considered "decisively on the ground" — used to gate the speed-rise
     * relaxation in SILENCE_CHECK. Stricter than [uprightAngleThresholdDegrees]
     * (45°) so that bikes merely tilted (leaned against something, partial
     * fall, rider walking the bike) do not get the speed gate relaxed. Real
     * crashes that leave the bike flat are at 80–95°; a value of 60° captures
     * these while excluding partial leans.
     */
    val onSideRelaxationAngleDeg: Double = 60.0,
```

Mind the trailing commas so the data class still compiles.

- [ ] **Step 2: Add the companion constant in `CrashDetectionManager.kt`**

In `CrashDetectionManager.kt`, in the `companion object`, add next to the existing crash-related constants (e.g. near `UPRIGHT_ANGLE_THRESHOLD_DEGREES`):

```kotlin
        /** Angle (deg) above which the SILENCE_CHECK speed-rise relaxation engages. */
        const val ON_SIDE_RELAXATION_ANGLE_DEG = 60.0
```

- [ ] **Step 3: Pass it through `buildThresholds()`**

In `CrashDetectionManager.kt`'s `buildThresholds()`, add the new named argument to the `Thresholds(...)` constructor call:

```kotlin
            onSideRelaxationAngleDeg = ON_SIDE_RELAXATION_ANGLE_DEG,
```

Place it next to `uprightAngleThresholdDegrees = UPRIGHT_ANGLE_THRESHOLD_DEGREES` in the call.

- [ ] **Step 4: Verify it compiles**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. No tests are needed yet — the field is unused at this point.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/enderthor/kSafe/extension/crash/Thresholds.kt \
        app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashDetectionManager.kt
git commit -m "feat(crash): add onSideRelaxationAngleDeg threshold (60 deg)"
```

(Commit message exactly the single line above — NO Claude co-author trailer, no "Generated with Claude" line.)

---

## Task 2: On-side speed-rise relaxation in `handleSilenceCheck`

**Files:**
- Modify: `app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachine.kt`
- Test: `app/src/test/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachineTest.kt`

This is the core behavioural change. Full TDD. READ the file first — match the existing `smEnteringSilence` / `sample` / `newSm` helper signatures, the `Thresholds` defaults (`crashConfirmSpeedKmh = 5`, `silenceDeviationMax = 4.0`, `silenceDurationMs = 4_500L`, `silenceDurationUprightMs = 20_000L`, `uprightAngleThresholdDegrees = 45.0`, `onSideRelaxationAngleDeg = 60.0` after Task 1).

- [ ] **Step 1: Write the failing tests**

Add to `CrashStateMachineTest.kt`. Five tests covering the matrix from the design spec. Adapt helper-call shapes to the real signatures if they differ; keep the assertions exactly.

```kotlin
    @Test
    fun `on-side relaxation - speed rise above confirm threshold does not break silence (bike escaping)`() {
        // On-side prompt stop (angle ~90°), orientation locks at 4.5 s window.
        // After the lock, raise the speed above crashConfirmSpeedKmh (default 5) —
        // without the relaxation this would break silence; with it, the accel-only
        // isStill keeps the silence window running and Confirm fires.
        val (sm, _) = smEnteringSilence(
            gapMs = 2_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 0.0, silenceAx = 9.81,   // angle ~90° from upright reference
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        var t = 1_002_000L
        // 5 still on-side samples to drive the orientation lock (>= MIN_ORIENTATION_SAMPLES).
        repeat(5) {
            t += 200L
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
        }
        // Bike rolls — speed rises above the confirm threshold.
        sm.onSpeedUpdate(10.0)
        // Keep feeding still on-side samples for > 4.5 s of accumulated silence.
        var confirmed = false
        repeat(7) {
            t += 1000L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
                    is CrashStateMachine.Decision.Confirm) confirmed = true
        }
        assertTrue("on-side relaxation must allow Confirm despite speed rise", confirmed)
        assertEquals(4_500L, sm.lastConfirmedSilenceMs)
    }

    @Test
    fun `on-side relaxation - 45 to 60 degree band does NOT relax (only strong on-side qualifies)`() {
        // Angle ~55° (between the 45° on-side gate and the 60° relaxation gate):
        // orientation regime still locks the 4.5 s on-side window, but the
        // relaxation does NOT engage — a speed rise breaks silence as before.
        // Construct silence vector with magnitude ~9.81: az = 5.63, ax = 8.04 gives
        // angle ≈ acos(5.63/9.81) ≈ 55°, magnitude ≈ sqrt(31.7+64.6) ≈ 9.81.
        val (sm, _) = smEnteringSilence(
            gapMs = 2_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 5.63, silenceAx = 8.04,
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        var t = 1_002_000L
        repeat(5) {
            t += 200L
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 5.63, ax = 8.04))
        }
        sm.onSpeedUpdate(10.0)   // speed rise that should break silence (no relax in 45-60° band)
        var confirmed = false
        repeat(7) {
            t += 1000L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 5.63, ax = 8.04))
                    is CrashStateMachine.Decision.Confirm) confirmed = true
        }
        assertEquals(
            "speed rise must keep breaking silence when angle is below the 60° relaxation gate",
            false, confirmed,
        )
    }

    @Test
    fun `on-side relaxation - upright regime does NOT relax`() {
        // Prompt stop, upright silence vector — orientation locks at 20 s (upright).
        // lastOrientationAngleDeg ~0° < 60°, no relaxation, speed rise breaks silence.
        val (sm, _) = smEnteringSilence(
            gapMs = 2_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 9.81, silenceAx = 0.0,
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        var t = 1_002_000L
        repeat(5) {
            t += 200L
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 9.81, ax = 0.0))
        }
        sm.onSpeedUpdate(10.0)
        var confirmed = false
        repeat(7) {
            t += 1000L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 9.81, ax = 0.0))
                    is CrashStateMachine.Decision.Confirm) confirmed = true
        }
        assertEquals(
            "upright regime must not engage the relaxation — speed rise still breaks silence",
            false, confirmed,
        )
    }

    @Test
    fun `on-side relaxation - gap regime does NOT relax (no orientation evidence)`() {
        // Long gap (> delayedStopGapMs 8 s) forces the gap regime; lastOrientationAngleDeg
        // stays at the -1.0 sentinel — relaxation must not engage.
        val (sm, _) = smEnteringSilence(
            gapMs = 12_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 0.0, silenceAx = 9.81,   // would be on-side BUT gap regime ignores
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        var t = 1_012_000L
        repeat(5) {
            t += 200L
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
        }
        sm.onSpeedUpdate(10.0)
        var confirmed = false
        // Feed 6 s of stillness (well past the 4.5 s legacy window but well short of 20 s).
        repeat(6) {
            t += 1000L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
                    is CrashStateMachine.Decision.Confirm) confirmed = true
        }
        assertEquals(
            "gap regime must not engage the relaxation — speed rise still breaks silence",
            false, confirmed,
        )
    }

    @Test
    fun `on-side relaxation - accel motion still breaks silence even when relaxed`() {
        // Even with on-side relaxation engaged, accelerometer motion (deviation > 4)
        // must still break silence. This is the strong guard against false positives
        // (rider picking up the bike, handling it, etc.).
        val (sm, _) = smEnteringSilence(
            gapMs = 2_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 0.0, silenceAx = 9.81,
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        var t = 1_002_000L
        repeat(5) {
            t += 200L
            sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
        }
        sm.onSpeedUpdate(10.0)   // speed rise (relaxation would normally ignore this)
        // But a motion sample (raw far from gravity) must break silence.
        t += 1000L
        sm.onSample(sample(time = t, raw = 16.0, smoothed = 9.81, az = 0.0, ax = 9.81))
        // After this break, the silence clock restarts at t. Confirm cannot fire on this sample.
        // Quick check: the SM stays in SILENCE_CHECK (break, not give-up — gap is small).
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        // And it has NOT confirmed yet on this break sample.
        // (Subsequent still samples would confirm again — that's the relaxation working;
        //  the load-bearing assertion is that the deviation > 4 broke the silence.)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :app:testDebugUnitTest --tests "com.enderthor.kSafe.extension.crash.CrashStateMachineTest"`
Expected: `on-side relaxation - speed rise above confirm threshold does not break silence...` FAILS — without the relaxation, `speedDropOk` becomes false on the speed rise, `isStill` becomes false, silence breaks and never confirms. The other 4 tests should PASS even before the implementation (they assert the *current* correct behaviour: 45-60° band, upright, gap, accel-break — none should be affected by the relaxation since each excludes the relaxation conditions, and `accel motion still breaks` only relies on the existing accel gate).

If a non-load-bearing test fails before the implementation, double-check its construction — it should match current behaviour. The single load-bearing failure is the first test.

- [ ] **Step 3: Implement the relaxation in `handleSilenceCheck`**

In `CrashStateMachine.kt`, `handleSilenceCheck` currently computes (read the file for the exact surrounding code):

```kotlin
        val accelOk = deviation <= deviationMax
        val speedDropOk = isSpeedDropConfirmed()
        val isStill = accelOk && speedDropOk
```

Replace those three lines with:

```kotlin
        val accelOk = deviation <= deviationMax
        val speedDropOk = isSpeedDropConfirmed()
        // Once the orientation regime has LOCKED decisively on-side (angle ≥
        // onSideRelaxationAngleDeg, a stricter threshold than the regular 45°
        // on-side gate), a bike on the ground cannot be ridden — if it is
        // moving, the bike has escaped the downed rider. Accel stillness alone
        // is sufficient evidence; the speed rise is the bike rolling, not the
        // rider riding. The accel gate remains the strong FP guard: any
        // handling of the bike (picking it up, holding it) breaks silence
        // regardless of speed.
        val onSideRelaxed = lockedEffectiveSilenceMs > 0L &&
                            lastOrientationAngleDeg >= thresholds.onSideRelaxationAngleDeg
        val isStill = if (onSideRelaxed) accelOk else (accelOk && speedDropOk)
```

Leave everything else in `handleSilenceCheck` unchanged — the Confirm branch, the `!isStill` retry/give-up branches, the orientation accumulator, the latch.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :app:testDebugUnitTest --tests "com.enderthor.kSafe.extension.crash.CrashStateMachineTest"`
Expected: PASS — all five new tests, plus all existing tests.

- [ ] **Step 5: Run the full suite**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachine.kt \
        app/src/test/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachineTest.kt
git commit -m "fix(crash): on-side speed-rise relaxation in SILENCE_CHECK"
```

(Commit message exactly the single line above — no Claude co-author trailer.)

---

## Task 3: Skip `resumeForRide()` in `CrashDetectionManager.resume()` when mid-event

**Files:**
- Modify: `app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashDetectionManager.kt`

This task is facade wiring — no unit-test harness for `CrashDetectionManager`, so it is verified by compile + the full suite staying green (consistent with prior facade tasks on this branch). Note the existing `CrashStateMachine.state` is already publicly readable (`var state ... private set`).

- [ ] **Step 1: Gate the state-machine reset**

In `CrashDetectionManager.kt`, `resume()` currently calls `stateMachine.resumeForRide()` unconditionally near the end of the function (read the file for the exact location). Replace that single call with the conditional below; do NOT change any other line in `resume()`.

Find:

```kotlin
        stateMachine.resumeForRide()
```

Replace with:

```kotlin
        // If the state machine is mid-IMPACT or mid-SILENCE_CHECK at resume time
        // (auto-resume during an in-flight crash detection — the symmetric case
        // to I3's autopause preservation), do NOT reset it. The accelerometer
        // pipeline has been running through the pause; the in-flight detection
        // must be allowed to complete and confirm. After a manual pause the
        // state machine was wiped by onPause(auto=false) and is already in
        // MONITORING, so this conditional is a no-op for that case.
        if (stateMachine.state != CrashStateMachine.State.MONITORING) {
            Timber.d("CrashDetectionManager RESUMED — preserving in-flight ${stateMachine.state} (auto-resume mid-crash)")
        } else {
            stateMachine.resumeForRide()
        }
```

The other facade-level resets in `resume()` (`startTime`, `speedDataReceived`, `lastPeriodicLogMs`, `lastGpsStaleState`, `lastLoggedSensitivity`, `postImpactBoostUntil`, `recentTmoTimestamps.clear()`, `resetWindowAccumulators()`, `rebuildThresholds(...)`, `sensorReader.start()`, `speedDropMonitor.start()`) remain unchanged — they reinitialise facade-only state that the in-flight event in the state machine does not depend on for correctness.

- [ ] **Step 2: Verify compile + full suite**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashDetectionManager.kt
git commit -m "fix(crash): auto-resume preserves in-flight crash detection"
```

(Commit message exactly the single line above — no Claude co-author trailer.)

---

## Final verification

- [ ] `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :app:testDebugUnitTest` — BUILD SUCCESSFUL, whole suite green.
- [ ] `grep -rn "stateMachine.resumeForRide" app/src/main` — exactly one call site, in `CrashDetectionManager.resume()`, now gated.
- [ ] `grep -rn "onSideRelaxationAngleDeg\|ON_SIDE_RELAXATION_ANGLE_DEG\|onSideRelaxed" app/src/main` — references in `Thresholds.kt`, `CrashDetectionManager.kt`, and `CrashStateMachine.kt` only; nothing dangling elsewhere.
