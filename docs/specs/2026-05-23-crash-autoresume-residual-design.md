# Crash Detection — Auto-Resume Residual

> **Date:** 2026-05-23
> **Branch:** `feature/crash-detection-reliability`
> **Affects:** `CrashDetectionManager`, `CrashStateMachine`, `Thresholds`
> **Companion to:** the I3 autopause fix (`docs/specs/2026-05-22-crash-reliability-followups-design.md`)

## Problem

The I3 fix made `CrashDetectionManager.onPause(auto)` preserve in-flight crash
detection through a Karoo autopause. The symmetric auto-resume path is still
broken: on `Paused → Recording` (auto-resume) `KSafeExtension.handleRideState`
calls `crashManager.resume(...)` → `stateMachine.resumeForRide()` → full reset.
An in-flight IMPACT/SILENCE_CHECK state is wiped.

Even with the reset fixed, a second issue remains: the speed rising at
auto-resume makes `isSpeedDropConfirmed()` false → `isStill` false → silence
breaks → eventual false-alarm exit. So the residual has two parts that must be
fixed together.

**Reachability** — estimated impact (qualitative, not measured): road ≈
negligible; gravel ≈ ~5 %; MTB descents ≈ 10–20 % of real crashes. Narrow but
non-trivial for an off-road safety device; entirely false-negative (a real
crash unalerted).

The most realistic scenario: a downhill crash leaves the bike on its side, the
bike rolls down the slope, accelerates above the auto-resume threshold within
the silence window (4.5 s on-side / 20 s otherwise), and the in-flight
detection is wiped before it confirms.

## Scope

This spec closes the **on-side** half of the residual — the unambiguous case
where the bike is decisively on the ground and moving (a real bike escape).
The upright-bike-rolling-without-rider case stays open: it lacks a clean
discriminator (a coasting rider with no cadence input looks the same as an
escaping bike), and the realistic exposure is much narrower (a crashed bike
landing perfectly upright and then rolling needs unusual geometry).

## Solution overview

Two small changes, in lock-step with the I3 design:

| Part | Change |
|------|--------|
| **A. Skip resume-reset when mid-event** | `CrashDetectionManager.resume()` gates the call to `stateMachine.resumeForRide()` on `stateMachine.state == MONITORING`. If the state machine is mid-IMPACT or mid-SILENCE_CHECK, the reset is skipped — the in-flight detection survives. Mirrors the I3 logic on the pause side. |
| **B. On-side speed-rise relaxation** | In `CrashStateMachine.handleSilenceCheck`, when orientation has decisively locked on-side (angle ≥ `onSideRelaxationAngleDeg = 60°`, a stricter threshold than the regular 45° on-side gate), `isStill` requires only `accelOk`, not `speedDropOk`. A bike decisively on its side cannot be ridden — if it is moving, the bike has escaped the downed rider, and accel stillness alone is sufficient evidence to confirm. |

Both parts are needed: A alone preserves the state but the speed-rise still
breaks silence; B alone has nothing to relax because the state was reset.

## Design

### Part A — `CrashDetectionManager.resume()` gating

Current `resume()` runs (in order): config refresh, `startTime` reset,
`speedDataReceived = false`, log timing resets, `postImpactBoostUntil = 0L`,
`recentTmoTimestamps.clear()`, `resetWindowAccumulators()`,
`rebuildThresholds()`, `stateMachine.resumeForRide()`, `sensorReader.start()`,
`speedDropMonitor.start()`.

Only the last state-machine reset destructively wipes in-flight detection.
Every other reset is facade-level: it reinitialises log timers, terrain-cluster
state, and threshold caches — none of which the in-flight event in the state
machine depends on for correctness (the state machine carries its own
`startTimeMs`, `impactStartedMs`, `silenceCheckEnteredMs`, `preImpactRef`, the
silence window accumulator and the latch). Re-running the facade resets is
therefore safe even mid-event.

The change is a conditional around the one destructive call:

```kotlin
val preserveInFlight = stateMachine.state != CrashStateMachine.State.MONITORING
if (preserveInFlight) {
    Timber.d("CrashDetectionManager RESUMED — preserving in-flight ${stateMachine.state} (auto-resume mid-crash)")
} else {
    stateMachine.resumeForRide()
}
```

We do not need to know whether the resume is "auto" vs "manual" — the state
machine's own state is the right signal. After a manual pause the state
machine was wiped by `onPause(auto=false)`, so it is in MONITORING at resume
time and the reset is a no-op anyway. After an autopause it may or may not be
mid-event; if mid-event, preserve.

### Part B — on-side relaxation in `handleSilenceCheck`

The pre-impact orientation regime already classifies the silence-window gravity
vector against the pre-impact reference, locking the silence duration at 4.5 s
(on-side, angle ≥ 45°) or 20 s (upright, angle < 45°) once the orientation has
been computed and latched.

For the speed-rise relaxation we use a **stricter** angle threshold —
`onSideRelaxationAngleDeg = 60°` — so a bike that is merely tilted does not
qualify. Real crashes that leave the bike flat are typically at 80–95°; a
threshold of 60° captures these while excluding partial-fall and lean cases
(parked-leaning, the rider holding the bike, a partial slip):

| Angle | Physical reading | On-side regime (4.5 s vs 20 s) | Speed-rise relaxation |
|-------|------------------|--------------------------------|-----------------------|
| 85–95° | Flat on the ground (real crash) | Active (4.5 s) | **Active** |
| 60–80° | Strongly on its side | Active (4.5 s) | **Active** |
| 45–60° | Tilted but not fallen flat (lean / partial) | Active (4.5 s) | Not active |
| < 45° | Upright | Inactive (20 s) | Not active |

The change to `isStill`:

```kotlin
val accelOk = deviation <= deviationMax
val speedDropOk = isSpeedDropConfirmed()
// Once the orientation regime has LOCKED decisively on-side (angle ≥
// onSideRelaxationAngleDeg, a stricter threshold than the regular on-side
// gate), a bike on the ground cannot be ridden — if it is moving, the bike
// has escaped the downed rider. Accel stillness alone is sufficient evidence
// of a real crash; the speed rise is the bike rolling, not the rider riding.
val onSideRelaxed = lockedEffectiveSilenceMs > 0L &&
                    lastOrientationAngleDeg >= thresholds.onSideRelaxationAngleDeg
val isStill = if (onSideRelaxed) accelOk else (accelOk && speedDropOk)
```

The relaxation engages only when:
- the orientation regime has already latched (`lockedEffectiveSilenceMs > 0`),
  which means the gap was ≤ 8 s (prompt stop) and a real angle was computed,
- and the locked angle is ≥ the stricter 60° threshold.

The gap regime (`firstSilenceGapMs > delayedStopGapMs`) and the invalid-reference
fallback both leave `lastOrientationAngleDeg` at its `-1.0` sentinel, so the
relaxation does not engage for either — only the prompt-stop, valid-reference,
decisively-on-side case.

### New threshold

```kotlin
data class Thresholds(
    ...
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
    ...
)
```

Wire it through `CrashDetectionManager.buildThresholds()` from a new companion
constant `ON_SIDE_RELAXATION_ANGLE_DEG = 60.0`, matching the existing pattern
used by the other threshold constants on this branch.

### False-positive risk analysis

The relaxation removes the speed-drop requirement from `isStill` only when the
bike is decisively on the ground. For a false positive to fire (SOS actually
sent) the following must all hold:

1. an IMPACT spike not caused by a real crash,
2. speed drops below `crashConfirmSpeedKmh` and SILENCE_CHECK is entered,
3. the bike's silence-window orientation is ≥ 60° from the pre-impact reference,
4. accelerometer deviation stays < `silenceDeviationMax (4 m/s²)` for 4.5 s,
5. speed rises during those 4.5 s,
6. the rider is not present to cancel the countdown for 20–30 s after confirm.

Realistic combined likelihood is very low. The accelerometer remains the strong
guard: any handling of the bike (picking it up, holding it, walking it)
generates `deviation > 4 m/s²` and breaks silence regardless of speed. The
known marginal cases:

- A bike dropped on a smooth slope that rolls undisturbed for ~5 s: triggers a
  countdown the rider cancels because they are right there. FP of countdown,
  not of sent SOS.
- A leaning parked bike (45–60°): excluded by the 60° threshold.
- Cornering interactions: a bike leaned in a fast corner has a pre-impact
  reference of ~30°. Post-crash flat gives ~60° change → engages. Post-bump
  upright gives ~30° change → does not engage (upright regime, 20 s). Both
  correct.

Net trade-off: closes ~80% of the on-side residual (a real false negative on
MTB descents and gravel) at the cost of a very narrow countdown-FP class with
no sent-alert risk.

### Out of scope

- **Upright bike escape.** A crashed rider whose bike lands upright and rolls
  away on a slope still loses fast-path detection. No clean discriminator
  exists (coasting rider with no cadence input is indistinguishable from
  escaping bike). The SpeedDropMonitor backstop remains.
- **IMPACT-phase auto-resume.** If the autopause/auto-resume cycle straddles
  the IMPACT phase (impact lands within the ~3-6 s autopause-trigger window),
  the state machine is still in IMPACT at resume time. The relaxation in this
  spec lives in `handleSilenceCheck`, so it does not apply yet; `handleImpact`
  still requires `speedDropOk` to transition to SILENCE_CHECK, which the
  rising post-resume speed blocks. The IMPACT window then times out into a
  false-alarm and the SpeedDropMonitor backstop is the only remaining path.
  Narrow exposure (impact must land in that brief window) and a backstop
  covers it; left for a future iteration.
- The `updateGrade` mid-ride threshold-rebuild race (pre-existing, flagged in
  review, not a defect).
- `CrashDetectionManager` and `EmergencyManager` unit-test harness (no facade
  test infrastructure exists in the project).

## Testing

Pure JVM tests on `CrashStateMachine` (the relaxation is testable directly):

- **on-side relaxation engages**: drive into SILENCE_CHECK with a valid
  upright pre-impact reference and silence-window samples at ≥ 60° (e.g.
  `az = 0.0, ax = 9.81`, angle ≈ 90°). After the orientation locks, raise the
  speed above `crashConfirmSpeedKmh` while keeping accelerometer deviation <
  4 m/s². Assert `Decision.None` continues (silence not broken by speed rise),
  and `Decision.Confirm` eventually fires at the 4.5 s on-side window.
- **60° threshold guard**: same setup but with a silence-window angle in the
  45–60° band (e.g. `az = 5.0, ax = 8.45` — angle ≈ 60° boundary, or just
  below). Confirm the relaxation does NOT engage — a speed rise breaks silence
  via `speedDropOk`. This documents the angle bar.
- **upright regime no relaxation**: silence-window upright (angle < 45°),
  20 s window, speed rises → silence breaks. The upright residual stays open.
- **gap regime no relaxation**: long gap (gap > 8 s) so the gap regime fires
  (`lastOrientationAngleDeg = -1.0` sentinel), speed rises → silence breaks.
- **accel-motion still breaks even on-side relaxed**: orientation locked at
  ≥60°, but feed a sample with `deviation > 4` (e.g. bike being picked up) →
  silence breaks. Documents that the accelerometer remains the strong guard.

The resume-gating change is verified by review (no facade test harness).
