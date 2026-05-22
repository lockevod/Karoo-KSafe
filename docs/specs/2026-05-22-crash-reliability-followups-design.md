# Crash Detection — Reliability Follow-ups (autopause, cooldown, cadence)

> **Date:** 2026-05-22
> **Branch:** `feature/crash-detection-reliability`
> **Affects:** `KSafeExtension`, `CrashDetectionManager`, `CrashStateMachine`, `EmergencyManager`

## Problem

A broad review of the crash-detection subsystem surfaced three pre-existing
false-negative paths — a real crash going unalerted. None were introduced by the
pre-impact-orientation work; all three are addressed here.

- **I3 — autopause wipes in-flight detection.** The Karoo fires an automatic pause
  ~3-6 s after speed reaches 0. A real crash stops the bike, which is exactly what
  triggers an autopause. `handleRideState` treats `RideState.Paused` without
  inspecting its `auto` flag and calls `CrashDetectionManager.onPause()`, which runs
  `CrashStateMachine.onPause()` and **wipes the in-flight IMPACT / SILENCE_CHECK
  state** — the detection of the crash that caused the pause. The accelerometer
  pipeline then never re-detects (the rider is down, no new impact spike). This also
  silently defeats the pre-impact-orientation feature in the common case: crash →
  bike stops → autopause within ~5 s → wipe.
- **C1 — the crash cooldown is armed even when the rider cancels.** `confirmCrash`
  stamps `lastCrashTime`, arming a ~60 s cooldown that suppresses subsequent
  confirmations. If the rider cancels the countdown (no alert sent), the cooldown
  stays armed; a real crash within 60 s is suppressed. A false positive and a real
  crash can be correlated (same rough descent), so this is reachable.
- **C2 — a stuck cadence sensor vetoes a real crash.** `CrashStateMachine.isCadenceActive()`
  treats cadence > 20 RPM with a recent update as "pedalling" → instant SILENCE_CHECK
  false-alarm exit. An ANT+ cadence sensor that loses signal repeats its last
  non-zero value; the SDK keeps emitting it, so it always looks fresh. After a real
  crash the rider is not pedalling, but the stuck reading makes `isCadenceActive`
  permanently true → SILENCE_CHECK is rejected on every sample → the crash never
  confirms via the main pipeline.

## Solution overview

| Issue | Fix |
|-------|-----|
| I3 | Split the `Paused` handler on `RideState.Paused.auto`. On a **manual** pause keep current behaviour (wipe in-flight state — the rider is conscious). On an **autopause** do **not** wipe — let the state machine keep running on the always-on accelerometer stream so an in-flight detection completes and confirms during the pause. |
| C1 | Clear `lastCrashTime` (`CrashDetectionManager.clearCrashCooldown()`) when a crash-triggered emergency is cancelled. No alert was sent → nothing to de-duplicate → re-arm fully. |
| C2 | Add bit-exact change-tracking for cadence (`cadenceLastChangeMs`), mirroring the existing speed staleness logic. `isCadenceActive` additionally requires the cadence value to have changed within `cadenceStaleThresholdMs` — a stuck repeating reading reads as stale (not active). |

## Design

### I3 — autopause preserves in-flight detection

`RideState.Paused` carries a `boolean auto` field (`getAuto()`) — the Karoo SDK
distinguishes an automatic pause (speed reached 0) from a manual pause (the rider
tapped pause). This is the enabler.

**`CrashDetectionManager.onPause()` becomes `onPause(auto: Boolean)`:**
- Always: `sensorReader.invalidateVectorRing()` — harmless to an in-flight event
  (its pre-impact reference was already captured at IMPACT entry); correct for a
  café-stop autopause (a future impact should not use pre-pause samples).
- Only when `!auto` (manual pause): `stateMachine.onPause()` — wipe the in-flight
  IMPACT / SILENCE_CHECK state.

**`handleRideState`'s `Paused` branch passes `state.auto`:**
- `crashManager.onPause(state.auto)`.
- `crashManager.resetSpeedDropOnPause()` — unchanged, runs on both pause kinds
  (the SpeedDropMonitor's café-stop false-positive mitigation is intentionally
  retained; see "Out of scope").
- The check-in timer handling is unchanged.

**Why the resume path does not interfere.** A real crash confirms *during* the
pause (~5-25 s after the impact, depending on regime) — a downed rider never
resumes, so `crashManager.resume()` (which resets the state machine) is never
reached. A rider who was fine (bump+brake+stop) resumes → `resume()` resets →
correct. Suppression of that false positive is still done by the gap/orientation
mechanism, exactly as while moving.

**Why preserving in-flight state through an autopause introduces no new false
positive.** An autopause is semantically "the rider stopped" — a state the machine
already handles natively (it is the SILENCE_CHECK confirm condition). A bump
followed by an upright stop reaches the same upright/gap regime it would reach
without the pause; the rider fidgets, silence breaks, no confirmation. A real
on-side crash confirms. Behaviour is identical to the no-pause case.

**Edge case (documented, not fixed):** if a crashed bike rolls (e.g. down a slope)
after the crash, speed rises, the Karoo auto-resumes, and `resume()` resets the
in-flight detection. The rolling bike also breaks SILENCE_CHECK by motion anyway.
This is a rare scenario and out of scope.

### C1 — clear the cooldown on cancel

`EmergencyManager.cancelEmergency()` already computes `cancelledReason`. Add an
optional constructor callback `onCrashEmergencyCancelled: (() -> Unit)? = null`,
invoked inside `cancelEmergency()` when `cancelledReason == EmergencyReason.CRASH_DETECTED`.

`CrashDetectionManager` gains `fun clearCrashCooldown() { lastCrashTime = 0L }`.

`KSafeExtension` wires the callback to `crashManager.clearCrashCooldown()`. Because
every cancel path (the in-`EmergencyManager` overlay/tap handlers and the external
`KSafeExtension.cancelEmergency()`) routes through `cancelEmergency()`, this single
hook covers all of them.

This does not break the two legitimate cooldown cases — a duplicate confirmation
during the countdown, and the SpeedDropMonitor confirming the same crash ~5 min
later — because neither involves a cancellation; the cooldown remains armed for a
genuinely sent alert.

### C2 — cadence repeat-value staleness

Mirror the speed staleness pattern (`speedLastChangeMs` in `CrashDetectionManager`,
stamped only on a value change).

In `CrashStateMachine`:
- Add `@Volatile private var cadenceLastChangeMs: Long = 0L`.
- In `onCadenceUpdate(cadenceRpm)`: stamp `cadenceLastChangeMs = lastSampleMs` only
  when `cadenceRpm` differs (bit-exact) from the previous `lastCadenceRpm`, and on
  the first update (bootstrap). The existing `lastCadenceRpm` / `lastCadenceUpdateMs`
  writes stay.
- In `isCadenceActive(nowSampleMs)`: in addition to the existing checks (sensor
  present, update fresh, RPM above the quiet threshold), require
  `nowSampleMs - cadenceLastChangeMs < cadenceStaleThresholdMs` — the value must
  have *changed* recently, not merely been *re-emitted* recently.
- Reset `cadenceLastChangeMs` to `0L` wherever `lastCadenceUpdateMs` is reset
  (`reset`, `resumeForRide`; `onPause` if it resets cadence state — match the
  existing `lastCadenceUpdateMs` treatment exactly).

**Why bit-exact change-tracking is correct here.** Real cadence from a pedalling
rider fluctuates sample-to-sample (78, 81, 79, 80…) — pedalling is never perfectly
metronomic; road buzz and terrain guarantee variation. A stuck sensor emits the
identical value bit-exact. So "value unchanged for `cadenceStaleThresholdMs`"
reliably distinguishes a stuck sensor from an actively pedalling rider.

**Why the error direction is safe.** If the cadence gate fails to fire when it
should (a hypothetical perfectly-metronomic rider), SILENCE_CHECK simply proceeds
normally — the motion of pedalling breaks the silence via the accelerometer
deviation anyway. A false negative on the *gate* never costs a missed crash; it
only forgoes an optimisation. Erring toward "treat as stale" is therefore the safe
choice.

## Testing

Pure JVM tests, TDD.

- **I3** — `CrashDetectionManager.onPause(auto)`: with `auto = true`, an in-flight
  IMPACT/SILENCE_CHECK state survives the call (the state machine stays in its
  phase); with `auto = false`, it is wiped (state returns to MONITORING). The
  continue-through-pause-to-confirmation behaviour is covered at the `CrashStateMachine`
  level (already testable — feed samples without calling `onPause`, assert it
  confirms).
- **C1** — confirm a crash, cancel it, confirm again within the cooldown window:
  the second confirmation must NOT be suppressed (because `clearCrashCooldown()`
  ran). And the control: confirm, do NOT cancel, confirm again within the window →
  still suppressed.
- **C2** — `CrashStateMachineTest`: a cadence stream stuck bit-exact at 68 RPM for
  longer than `cadenceStaleThresholdMs` → `isCadenceActive` becomes false → a
  SILENCE_CHECK in progress can confirm; a fluctuating cadence stream (78/81/79…)
  → `isCadenceActive` stays true → the cadence gate fires (instant false-alarm
  exit). Assertion bands tight, no loose ranges.

## Out of scope

- The SpeedDropMonitor reset on autopause (`resetSpeedDropOnPause()`) is retained.
  It is a deliberate café-stop false-positive mitigation; removing it reintroduces
  a known false positive. A no-impact-spike crash that triggers an autopause is
  still caught by the SpeedDropMonitor — delayed by one re-accumulation cycle, not
  lost.
- The `updateGrade` mid-ride threshold-rebuild race (peak threshold non-deterministic
  on undulating terrain) — flagged in review as surprising but not a defect; not
  addressed here.
- A crashed bike that rolls and triggers an auto-resume (see I3 edge case).
