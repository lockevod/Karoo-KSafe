# Crash Reliability Follow-ups — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close three pre-existing false-negative paths in the crash detector — autopause wiping in-flight detection (I3), the cooldown armed on a cancelled countdown (C1), and a stuck cadence sensor vetoing SILENCE_CHECK (C2).

**Architecture:** I3 splits the `RideState.Paused` handler on the SDK's `auto` flag so an autopause no longer wipes in-flight IMPACT/SILENCE state. C1 adds a `clearCrashCooldown()` call on the emergency-cancel path. C2 adds bit-exact change-tracking for cadence (mirroring the existing speed staleness logic) so a stuck sensor reads as inactive.

**Tech Stack:** Kotlin 2.0, Android, JUnit 4. Tests: `./gradlew :app:testDebugUnitTest`. JDK 17 required — run `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` before any gradle command (the system default JDK is incompatible with the Kotlin 2.0 compiler). A valid `local.properties` already exists.

**Design spec:** `docs/specs/2026-05-22-crash-reliability-followups-design.md`

**Note on test scope:** `CrashStateMachine` is a pure JVM-testable class with a full test suite (`CrashStateMachineTest.kt`). `CrashDetectionManager`, `KSafeExtension` and `EmergencyManager` are Android-coupled and have no unit-test harness in this project — changes to them are verified by `./gradlew :app:compileDebugKotlin` plus the full suite staying green, consistent with prior facade-wiring tasks on this branch.

---

## File structure

| File | Change |
|------|--------|
| `app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashDetectionManager.kt` | `onPause()` → `onPause(auto: Boolean)`; add `clearCrashCooldown()`. |
| `app/src/main/kotlin/com/enderthor/kSafe/extension/KSafeExtension.kt` | `handleRideState` passes `state.auto`; wire the `onCrashEmergencyCancelled` callback. |
| `app/src/main/kotlin/com/enderthor/kSafe/extension/managers/EmergencyManager.kt` | New optional constructor callback `onCrashEmergencyCancelled`; invoke it in `cancelEmergency()` for the `CRASH_DETECTED` branch. |
| `app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachine.kt` | Add `cadenceLastChangeMs`; update `onCadenceUpdate`, `isCadenceActive`, `reset()`, `resumeForRide()`. |
| `app/src/test/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachineTest.kt` | Tests for I3 (continue-through-pause) and C2 (cadence staleness). |

---

## Task 1: I3 — autopause preserves in-flight detection

**Files:**
- Modify: `app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashDetectionManager.kt`
- Modify: `app/src/main/kotlin/com/enderthor/kSafe/extension/KSafeExtension.kt`
- Test: `app/src/test/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachineTest.kt`

- [ ] **Step 1: Write the failing test (state-machine half of I3)**

Add to `CrashStateMachineTest.kt`. This test proves the state machine confirms a crash when it is NOT interrupted by an `onPause()` call — i.e. the behaviour an autopause must preserve. Read the file first for the existing `smEnteringSilence` / `sample` / `newSm` helpers and match their signatures; the code below uses the same helper shape the other regime tests use.

```kotlin
    @Test
    fun `autopause scenario - uninterrupted SILENCE_CHECK confirms (no onPause call)`() {
        // An autopause must NOT call onPause(); the state machine keeps running on
        // the accelerometer stream and an in-flight on-side crash confirms.
        val (sm, _) = smEnteringSilence(
            gapMs = 2_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 0.0, silenceAx = 9.81,   // on-side -> 4.5 s window
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        var t = 1_002_000L
        var confirmed = false
        repeat(7) {
            t += 1000L
            if (sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
                    is CrashStateMachine.Decision.Confirm) confirmed = true
        }
        assertTrue("uninterrupted SILENCE_CHECK must confirm", confirmed)
    }

    @Test
    fun `manual pause scenario - onPause mid-SILENCE_CHECK wipes to MONITORING`() {
        // A manual pause DOES call onPause(); the in-flight state is wiped.
        val (sm, _) = smEnteringSilence(
            gapMs = 2_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 0.0, silenceAx = 9.81,
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        sm.onPause()
        assertEquals(CrashStateMachine.State.MONITORING, sm.state)
    }
```

- [ ] **Step 2: Run the tests to verify they pass already / compile**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :app:testDebugUnitTest --tests "com.enderthor.kSafe.extension.crash.CrashStateMachineTest"`
Expected: PASS. These two tests assert the state machine's *current* correct behaviour (they are regression guards for I3 — the bug is in the facade, not the state machine). If `smEnteringSilence` has a different signature, adapt the call to match; do not change the assertions.

- [ ] **Step 3: Change `CrashDetectionManager.onPause()` to `onPause(auto: Boolean)`**

In `CrashDetectionManager.kt`, the current function is:

```kotlin
    fun onPause() {
        stateMachine.onPause()
        sensorReader.invalidateVectorRing()
        Timber.d("CrashDetectionManager: state machine paused")
    }
```

Replace it (and its KDoc) with:

```kotlin
    /**
     * Handle a ride pause. [auto] is `RideState.Paused.auto` — the Karoo SDK's flag
     * for an automatic pause (speed reached 0) vs a manual pause (rider tapped pause).
     *
     * The pre-impact vector ring is always invalidated (a floor timestamp so samples
     * captured before the pause are ignored) — harmless to an in-flight event, whose
     * pre-impact reference was already captured at IMPACT entry, and correct for a
     * café-stop autopause so a later impact does not average pre-pause samples.
     *
     * On a **manual** pause the rider deliberately stopped — conscious and fine — so
     * the in-flight IMPACT/SILENCE_CHECK state is wiped (`stateMachine.onPause()`).
     *
     * On an **automatic** pause the bike stopped on its own, which is exactly what a
     * real crash does. The in-flight state is NOT wiped: the state machine keeps
     * running on the always-on accelerometer stream so a crash-in-progress confirms
     * during the pause. Without this, an autopause (~3-6 s after speed hits 0) would
     * erase the detection of the very crash that caused the stop.
     */
    fun onPause(auto: Boolean) {
        sensorReader.invalidateVectorRing()
        if (auto) {
            Timber.d("CrashDetectionManager: autopause — in-flight detection preserved")
        } else {
            stateMachine.onPause()
            Timber.d("CrashDetectionManager: manual pause — state machine reset")
        }
    }
```

- [ ] **Step 4: Update `KSafeExtension.handleRideState` to pass `state.auto`**

In `KSafeExtension.kt`, in `handleRideState`, the `is RideState.Paused ->` branch currently contains:

```kotlin
                // Drop any in-flight IMPACT/SILENCE state — a crash that happens during
                // the pause should start clean from MONITORING, not mid-IMPACT or mid-
                // SILENCE_CHECK based on whatever the state machine was tracking when
                // the rider tapped pause. Baseline gravity vector is preserved.
                crashManager.onPause()
```

Replace those lines with:

```kotlin
                // Pass the SDK's auto flag: a MANUAL pause wipes any in-flight
                // IMPACT/SILENCE state (the rider deliberately stopped — conscious and
                // fine); an AUTOMATIC pause does NOT — the bike stopping on its own is
                // exactly a crash signature, so the in-flight detection must survive
                // and confirm during the pause.
                crashManager.onPause(state.auto)
```

The `is RideState.Paused ->` branch already smart-casts `state` to `RideState.Paused`, so `state.auto` is accessible. Leave the other lines in that branch (`resetSpeedDropOnPause()`, the check-in timer calls, `rideWasActive = true`) unchanged.

- [ ] **Step 5: Verify compile + full suite**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass. (No other call site of `crashManager.onPause` exists — this is the only one. Confirm with `grep -rn "\.onPause(" app/src/main/kotlin/com/enderthor/kSafe/extension/KSafeExtension.kt` if unsure.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashDetectionManager.kt \
        app/src/main/kotlin/com/enderthor/kSafe/extension/KSafeExtension.kt \
        app/src/test/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachineTest.kt
git commit -m "fix(crash): autopause preserves in-flight crash detection"
```

(No `Co-Authored-By: Claude` trailer, no "Generated with Claude" line — the commit message must be exactly the single line above.)

---

## Task 2: C1 — clear the crash cooldown on cancel

**Files:**
- Modify: `app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashDetectionManager.kt`
- Modify: `app/src/main/kotlin/com/enderthor/kSafe/extension/managers/EmergencyManager.kt`
- Modify: `app/src/main/kotlin/com/enderthor/kSafe/extension/KSafeExtension.kt`

This task is facade/manager wiring — verified by compile + the full suite. There is no unit-test harness for these classes.

- [ ] **Step 1: Add `clearCrashCooldown()` to `CrashDetectionManager`**

In `CrashDetectionManager.kt`, add this method (place it next to `resetSpeedDropOnPause()` / `onPause()`, near the other lifecycle methods). `lastCrashTime` is the existing `@Volatile private var lastCrashTime = 0L` field that gates the cooldown:

```kotlin
    /**
     * Clear the crash cooldown so the next genuine crash is not suppressed.
     *
     * `lastCrashTime` is stamped by [confirmCrash] at confirmation and gates a
     * ~60 s cooldown that de-duplicates the accelerometer pipeline against the
     * speed-drop watchdog. When the rider CANCELS a crash-triggered countdown no
     * alert is sent — there is nothing to de-duplicate — so the cooldown must be
     * dropped, otherwise a real crash within the window is silently suppressed.
     * A false positive and a real crash can be correlated (same rough descent),
     * so this is a reachable false-negative path.
     */
    fun clearCrashCooldown() {
        lastCrashTime = 0L
    }
```

- [ ] **Step 2: Add the cancel callback to `EmergencyManager`**

In `EmergencyManager.kt`, add a new optional constructor parameter. Read the current constructor first; add the parameter as the LAST constructor parameter with a `= null` default so no existing call site or test breaks:

```kotlin
    /**
     * Invoked when a CRASH-triggered emergency is cancelled by the rider. Wired by
     * KSafeExtension to clear the crash cooldown — a cancelled countdown sent no
     * alert, so crash detection must be fully re-armed.
     */
    private val onCrashEmergencyCancelled: (() -> Unit)? = null,
```

In `cancelEmergency()`, there is an existing `when (cancelledReason) { ... }` block with an `EmergencyReason.CRASH_DETECTED ->` branch that logs `CalibrationLogger.Event.CRASH_CANCELLED`. Add the callback invocation inside that same `CRASH_DETECTED` branch (after the existing `calibLogger?.log(...)` call). If the branch body is a single expression, convert it to a block `{ ... }` containing both the existing log call and:

```kotlin
                onCrashEmergencyCancelled?.invoke()
```

- [ ] **Step 3: Wire the callback in `KSafeExtension`**

In `KSafeExtension.kt`, find where `EmergencyManager(...)` is constructed. Add the new argument:

```kotlin
            onCrashEmergencyCancelled = { crashManager.clearCrashCooldown() },
```

`crashManager` is a property of `KSafeExtension`; the lambda captures it and is only invoked at cancel time (long after construction), so construction order does not matter — but verify `crashManager` is a member property (not a local) so the capture is valid. If `EmergencyManager` is constructed before `crashManager` is assigned, the lambda is still correct because it is lazy.

- [ ] **Step 4: Verify compile + full suite**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass. If any `EmergencyManager(...)` construction site or test fails to compile, it means the new parameter was not added last or without a default — fix so all existing call sites compile unchanged.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashDetectionManager.kt \
        app/src/main/kotlin/com/enderthor/kSafe/extension/managers/EmergencyManager.kt \
        app/src/main/kotlin/com/enderthor/kSafe/extension/KSafeExtension.kt
git commit -m "fix(crash): clear the crash cooldown when the rider cancels"
```

(Commit message exactly the single line above — no Claude trailer.)

---

## Task 3: C2 — cadence repeat-value staleness

**Files:**
- Modify: `app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachine.kt`
- Test: `app/src/test/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachineTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `CrashStateMachineTest.kt`. These drive the SM into SILENCE_CHECK, then exercise the cadence gate (`isCadenceActive` is consulted in `handleSilenceCheck`: an active cadence is an instant `Decision.ReturnToMonitoring` false-alarm exit). Read the file for the `smEnteringSilence` helper and the `sample` / `onCadenceUpdate` API; adapt the helper call shape if its signature differs, but keep the assertions.

```kotlin
    @Test
    fun `stuck cadence sensor repeating one value goes inactive and does not veto SILENCE_CHECK`() {
        // A cadence sensor that lost signal repeats its last value bit-exact. After
        // cadenceStaleThresholdMs with no CHANGE it must read as inactive, so it
        // cannot veto a real crash. Upright on-side crash -> 4.5 s window.
        val (sm, _) = smEnteringSilence(
            gapMs = 2_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 0.0, silenceAx = 9.81,
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        var t = 1_002_000L
        var confirmed = false
        // Feed 12 s of stillness; cadence sensor stuck bit-exact at 68 RPM the whole time.
        repeat(12) {
            t += 1000L
            val d = sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
            sm.onCadenceUpdate(68.0)   // same value every tick -> stuck sensor
            if (d is CrashStateMachine.Decision.Confirm) confirmed = true
        }
        assertTrue("a stuck cadence reading must not block confirmation", confirmed)
    }

    @Test
    fun `fluctuating cadence keeps the gate active and exits SILENCE_CHECK as a false alarm`() {
        // A genuinely pedalling rider's cadence fluctuates every revolution. The
        // cadence gate must stay active and trigger the false-alarm exit.
        val (sm, _) = smEnteringSilence(
            gapMs = 2_000L,
            preRef = PreImpactRef(0.0, 0.0, 9.81, valid = true),
            silenceAz = 0.0, silenceAx = 9.81,
        )
        assertEquals(CrashStateMachine.State.SILENCE_CHECK, sm.state)
        var t = 1_002_000L
        val rpms = listOf(78.0, 81.0, 79.0, 82.0, 80.0, 83.0)
        var returned = false
        for (rpm in rpms) {
            t += 1000L
            val d = sm.onSample(sample(time = t, raw = 9.81, smoothed = 9.81, az = 0.0, ax = 9.81))
            sm.onCadenceUpdate(rpm)   // changes every tick -> genuine pedalling
            if (d is CrashStateMachine.Decision.ReturnToMonitoring) returned = true
        }
        assertTrue("a fluctuating (real pedalling) cadence must trigger the gate", returned)
    }
```

Note on ordering: `onCadenceUpdate` stamps its timestamps from `lastSampleMs`, which is set by `onSample`. So each tick must call `onSample(t)` BEFORE `onCadenceUpdate(rpm)` — the test code above already does this. The default `cadenceStaleThresholdMs` is 10_000 ms, so 12 ticks of 1 s with no change crosses it.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :app:testDebugUnitTest --tests "com.enderthor.kSafe.extension.crash.CrashStateMachineTest"`
Expected: `stuck cadence sensor repeating one value...` FAILS — without the fix, a stuck 68 RPM reading keeps `isCadenceActive` true (it only checks freshness-by-emission, not freshness-by-change), so the cadence gate fires `ReturnToMonitoring` and the crash never confirms. The `fluctuating cadence...` test should already PASS (current behaviour). If the stuck-cadence test unexpectedly passes, re-check the test drives ≥ `cadenceStaleThresholdMs` of identical readings.

- [ ] **Step 3: Add the `cadenceLastChangeMs` field**

In `CrashStateMachine.kt`, next to the existing cadence fields (`lastCadenceRpm`, `lastCadenceUpdateMs` — around line 104-110), add:

```kotlin
    /**
     * Sample-domain timestamp at which the cadence VALUE last changed (bit-exact),
     * or `0L` if never. A cadence sensor that has lost signal repeats its last
     * value bit-exact while the SDK keeps emitting it — so [lastCadenceUpdateMs]
     * (freshness-by-emission) stays current and cannot detect the stall. This
     * field tracks freshness-by-change instead: a genuinely pedalling rider's
     * cadence fluctuates every revolution, a stuck sensor's does not.
     */
    @Volatile private var cadenceLastChangeMs: Long = 0L
```

- [ ] **Step 4: Update `onCadenceUpdate` to stamp the change-time**

Replace the current `onCadenceUpdate`:

```kotlin
    fun onCadenceUpdate(cadenceRpm: Double) {
        lastCadenceRpm = cadenceRpm
        // Use lastSampleMs (sample-domain) so staleness checks stay in the same time domain
        // as the sensor samples. If no sample has been processed yet, lastSampleMs is 0 —
        // which serves as the "never received in-ride" sentinel for [isCadenceActive].
        lastCadenceUpdateMs = lastSampleMs
    }
```

with:

```kotlin
    fun onCadenceUpdate(cadenceRpm: Double) {
        // Stamp the change-time only when the value actually moved (bit-exact), and
        // on the first update (bootstrap, when lastCadenceUpdateMs is still 0L). A
        // stuck sensor repeats its value bit-exact; real pedalling fluctuates — so
        // this distinguishes a dead sensor from an actively pedalling rider.
        if (cadenceRpm != lastCadenceRpm || lastCadenceUpdateMs == 0L) {
            cadenceLastChangeMs = lastSampleMs
        }
        lastCadenceRpm = cadenceRpm
        // Use lastSampleMs (sample-domain) so staleness checks stay in the same time domain
        // as the sensor samples. If no sample has been processed yet, lastSampleMs is 0 —
        // which serves as the "never received in-ride" sentinel for [isCadenceActive].
        lastCadenceUpdateMs = lastSampleMs
    }
```

The `lastCadenceUpdateMs == 0L` check must run BEFORE `lastCadenceUpdateMs` is reassigned — the code above already orders it correctly.

- [ ] **Step 5: Update `isCadenceActive` to require freshness-by-change**

The current method:

```kotlin
    private fun isCadenceActive(nowSampleMs: Long): Boolean {
        if (lastCadenceUpdateMs == 0L) return false
        val age = nowSampleMs - lastCadenceUpdateMs
        if (age > thresholds.cadenceStaleThresholdMs) return false
        return lastCadenceRpm > thresholds.cadenceQuietThresholdRpm
    }
```

Replace it with:

```kotlin
    private fun isCadenceActive(nowSampleMs: Long): Boolean {
        if (lastCadenceUpdateMs == 0L) return false
        val age = nowSampleMs - lastCadenceUpdateMs
        if (age > thresholds.cadenceStaleThresholdMs) return false
        // Freshness-by-CHANGE: a cadence value that has not moved within the stale
        // window is a sensor repeating its last reading after signal loss, not an
        // actively pedalling rider — treat it as inactive so it cannot veto a real
        // crash in SILENCE_CHECK.
        val sinceChange = nowSampleMs - cadenceLastChangeMs
        if (sinceChange > thresholds.cadenceStaleThresholdMs) return false
        return lastCadenceRpm > thresholds.cadenceQuietThresholdRpm
    }
```

- [ ] **Step 6: Reset `cadenceLastChangeMs` in the lifecycle methods**

In `reset()`, there is a line `lastCadenceUpdateMs = 0L`. Immediately after it add:

```kotlin
        cadenceLastChangeMs = 0L
```

In `resumeForRide()`, there is likewise a `lastCadenceUpdateMs = 0L` line. Immediately after it add the same line:

```kotlin
        cadenceLastChangeMs = 0L
```

Do NOT add it to `onPause()` — `onPause()` does not touch the cadence fields (`lastCadenceRpm` / `lastCadenceUpdateMs` are not reset there), so `cadenceLastChangeMs` must not be either, to stay consistent.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :app:testDebugUnitTest --tests "com.enderthor.kSafe.extension.crash.CrashStateMachineTest"`
Expected: PASS — both new tests, plus all existing tests. In particular `stuck cadence sensor repeating one value...` now passes because after 10 s of bit-exact 68 RPM, `isCadenceActive` returns false (the `sinceChange` guard), so the cadence gate no longer vetoes and the on-side crash confirms.

- [ ] **Step 8: Run the full suite**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachine.kt \
        app/src/test/kotlin/com/enderthor/kSafe/extension/crash/CrashStateMachineTest.kt
git commit -m "fix(crash): treat a stuck repeating cadence reading as inactive"
```

(Commit message exactly the single line above — no Claude trailer.)

---

## Final verification

- [ ] `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :app:testDebugUnitTest` — BUILD SUCCESSFUL, whole suite green.
- [ ] `./gradlew :app:compileDebugKotlin` — confirms the facade/manager changes (Tasks 1 & 2) compile.
- [ ] `grep -rn "crashManager.onPause(" app/src/main` — only the single updated call site in `KSafeExtension`, now passing `state.auto`.
