# Crash Detection — IMPACT-phase On-Side Relaxation

> **Date:** 2026-05-23
> **Branch:** `feature/crash-detection-reliability`
> **Affects:** `CrashStateMachine`, `Thresholds`
> **Companion to:** the auto-resume residual fix (`docs/specs/2026-05-23-crash-autoresume-residual-design.md`)

## Problem

The auto-resume residual fix added an on-side speed-rise relaxation inside
`handleSilenceCheck`: once orientation has decisively locked on-side (angle
≥ 60°), `isStill` ignores `speedDropOk`. That closes the residual for crashes
where the state machine had already reached SILENCE_CHECK before the bike
started rolling.

But there is a narrower residual that the SILENCE_CHECK relaxation does NOT
close: a real crash where the bike continues moving briefly (rolls, slides,
bounces) so the Karoo autopause fires while the state machine is **still in
IMPACT**, not yet in SILENCE_CHECK. The autopause→auto-resume cycle then
unfolds entirely inside the IMPACT phase. `handleImpact`'s transition-to-
SILENCE_CHECK gate requires `speedDropOk` (`currentSpeedKmh < crashConfirmSpeedKmh`,
default 5), so when the bike rolls fast enough to trigger auto-resume the
speed gate stays closed and SILENCE_CHECK is never entered. The IMPACT
window times out at `impactWindowMs` (15–25 s depending on preset) and the
event drops to MONITORING. The crash is lost via the fast path.

The `SpeedDropMonitor` backstop catches the rider — but only after the
configured `stoppedMinutesRequired` (default 5 min) of zero-speed AND 60 s of
continuous accel-stillness. In some environments (windy mountain pass, traffic
shoulder, light vibration) the 60 s stillness can repeatedly reset, so the
backstop is not always reliable. For a severe crash where every minute of
delay matters, closing this residual on the fast path is valuable.

**Reachability** (qualitative): MTB descents ≈ 2-5% of real crashes, gravel
≈ 1-2%, road < 1%. The residual is small in probability but large in
consequence: it is the only remaining false-negative path the multi-stage
review surfaced. In road environments where the SpeedDropMonitor itself can
fail, the residual is operationally serious for the affected cases.

## Solution overview

Extend the on-side relaxation principle into `handleImpact`: when there is
decisive evidence the bike is on the ground (angle from the pre-impact
reference ≥ `onSideRelaxationAngleDeg = 60°`, sustained over enough samples),
allow the IMPACT → SILENCE_CHECK transition even if the speed gate is open.
A bike that is decisively on the ground cannot be ridden — if the bike is
moving while flat, the bike has escaped the downed rider.

The change is symmetric with the SILENCE_CHECK relaxation introduced in the
previous spec and reuses the same threshold. The orientation accumulator
that today lives only in `handleSilenceCheck` is extended to start filling
in `handleImpact` (only on samples where the accelerometer has already
settled — `accelOk` — so the post-impact transient and tumble noise do not
corrupt the average).

Other IMPACT gates — `accelOk` (deviation < 4 m/s²), `gyroOk` (gyro < 2 rad/s),
`timeOk` (> 500 ms since impact) — stay intact. The relaxation only bypasses
the speed gate, with decisive orientation evidence required.

## Design

### Orientation accumulator: rename and extend

The existing accumulator fields are renamed to reflect their broader scope:

| Old name | New name |
|----------|----------|
| `silenceWindowSumX` | `orientationSumX` |
| `silenceWindowSumY` | `orientationSumY` |
| `silenceWindowSumZ` | `orientationSumZ` |
| `silenceWindowCount` | `orientationSampleCount` |

The accumulator now spans **IMPACT and SILENCE_CHECK**. Filling logic:

- In `handleImpact`, on every sample where `accelOk` holds, accumulate the
  current X/Y/Z and increment the count. This filters out the impact
  transient and any post-impact tumble where the accelerometer is still
  noisy — by the time `accelOk` holds, the bike has settled enough that the
  orientation read is meaningful.
- In `handleSilenceCheck`, accumulation continues as today (unconditional —
  any `!accelOk` sample breaks silence and resets the accumulator via
  `resetSilenceWindow()` anyway).
- On the IMPACT → SILENCE_CHECK transition, `resetSilenceWindow()` is called
  to clear the accumulator — SILENCE_CHECK then starts with a fresh count.
  This preserves the existing SILENCE_CHECK latch semantics (latch fires
  after the first `MIN_ORIENTATION_SAMPLES = 5` SILENCE_CHECK samples,
  identical to current behaviour).
- All existing reset paths (`resetSilenceWindow` on silence break, `reset`,
  `resumeForRide`, `onPause`) clear the renamed fields unchanged.

### Factored helper: `currentOrientationAngleDeg()`

The angle computation, today inline in `computeEffectiveSilenceMs`, becomes
a private method:

```kotlin
/**
 * Live orientation angle (degrees) between the accumulated gravity-vector
 * average (IMPACT + SILENCE_CHECK accumulator) and the pre-impact reference.
 * Returns -1.0 sentinel when fewer than [MIN_ORIENTATION_SAMPLES] have been
 * accumulated, the pre-impact reference is invalid, or either vector
 * magnitude is degenerate (< EPSILON).
 *
 * Consumed by [computeEffectiveSilenceMs] (latch decision in SILENCE_CHECK)
 * and by [handleImpact] (on-side relaxation gate).
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

`computeEffectiveSilenceMs` is thinned: its inline angle computation is
replaced with `val angleDeg = currentOrientationAngleDeg(); if (angleDeg < 0.0) return legacyShort`,
and the existing `lastOrientationAngleDeg = angleDeg` line stays.

### `handleImpact` gate with on-side relaxation

The existing transition gate:

```kotlin
if (accelOk && gyroOk && timeOk && speedDropOk) {
    state = State.SILENCE_CHECK
    silenceStartedMs = now
    firstSilenceGapMs = now - impactStartedMs
    silenceCheckEnteredMs = now
    return Decision.None
}
```

becomes:

```kotlin
// On-side relaxation: bypass the speed gate when there is decisive evidence
// the bike is on the ground. A bike on its side cannot be ridden, so if it
// is moving (speed gate is open because the bike rolled), the bike has
// escaped the downed rider. Other gates (accelOk, gyroOk, timeOk) remain
// as the strong false-positive guards.
val onSideRelaxed = orientationSampleCount >= IMPACT_RELAXATION_MIN_SAMPLES &&
                    currentOrientationAngleDeg() >= thresholds.onSideRelaxationAngleDeg
val gateOk = accelOk && gyroOk && timeOk && (speedDropOk || onSideRelaxed)
if (gateOk) {
    state = State.SILENCE_CHECK
    silenceStartedMs = now
    firstSilenceGapMs = now - impactStartedMs
    silenceCheckEnteredMs = now
    resetSilenceWindow()   // SILENCE_CHECK starts with a fresh orientation accumulator
    return Decision.None
}
```

`IMPACT_RELAXATION_MIN_SAMPLES = 25` is a new companion-object constant (in
`CrashStateMachine`, alongside `MIN_ORIENTATION_SAMPLES = 5`). It corresponds
to ~500 ms of sustained accel-still on-side accumulation. Stricter than the
SILENCE_CHECK latch's 5-sample threshold because entering SILENCE_CHECK is
the more consequential decision — bypassing the speed gate must require
sustained evidence, not a transient angle flicker.

The new accumulation block goes immediately after the existing `accelOk` /
`gyroOk` / `speedDropOk` locals are computed (so `accelOk` is in scope):

```kotlin
if (accelOk) {
    orientationSumX += sample.accelX
    orientationSumY += sample.accelY
    orientationSumZ += sample.accelZ
    orientationSampleCount++
}
```

### Why this works: end-to-end trace

A crash on a downhill where the bike rolls:

1. Impact spike → IMPACT entered. Accelerometer noisy for ~100-300 ms (bouncing).
2. Bike settles on its side. `accelOk` starts holding. Orientation accumulator
   begins filling.
3. Karoo autopause fires (~3-6 s after speed → 0). I3 fix preserves IMPACT.
4. Bike rolls down slope; speed picks up. Auto-resume fires (~3-5 km/h
   sustained). Auto-resume residual fix (Part A) preserves the in-flight
   IMPACT.
5. Bike continues rolling, but lies on its side throughout. By ~500 ms of
   accel-still in IMPACT, `orientationSampleCount >= 25` and
   `currentOrientationAngleDeg() >= 60°` (the bike is flat).
6. The new gate fires: `gateOk = accelOk && gyroOk && timeOk && onSideRelaxed = true`.
   Transition to SILENCE_CHECK. `resetSilenceWindow()` clears the accumulator;
   SILENCE_CHECK starts fresh.
7. SILENCE_CHECK accumulates 5 samples → orientation latches at on-side ≥60°
   → 4.5 s window AND the existing SILENCE_CHECK relaxation engages (speed
   rise no longer breaks silence).
8. Continuous 4.5 s of accel-still → Confirm → emergency countdown.

Total time from impact to confirm: roughly 1-2 s (settle) + 4.5 s (silence
window) = ~5-7 s. Compared to the SpeedDropMonitor backstop's ~5 min — and
the cases where the backstop never fires due to environmental motion — a
material improvement.

### False-positive risk

The relaxation only bypasses the speed gate. The other gates stay strong:

- **`accelOk` (deviation < 4 m/s²)**: the bike must have settled to a near-
  gravity reading. A rider riding away after a bump generates handling
  motion → `accelOk` fails → relaxation does not even consider the gate.
- **`gyroOk` (gyro < 2 rad/s)**: the bike must not be rotating. A rider in
  a sustained corner has yaw rate from the turn → likely `gyroOk` fails
  → relaxation does not consider the gate. This is the primary cornering
  FP guard.
- **`timeOk` (> 500 ms since impact)**: enforces minimum settle time.
- **60° angle threshold**: the bike must be decisively on the ground, not
  merely leaned. Mortal riders cornering at <60° lean → angle from
  upright pre-impact reference < 60° → relaxation does not engage.
- **25-sample sustained evidence**: ~500 ms of continuous accel-still
  on-side accumulation. A transient angle spike from a single sample
  cannot trigger.

Realistic FP scenarios traced:

| Scenario | Outcome |
|----------|---------|
| Rider hits a curb, stays upright, keeps riding | gyro and accel motion from continued riding → no IMPACT-→SILENCE transition; no FP |
| Rider hits a curb, brakes hard, stops upright | angle ≈ 0° from upright ref → relaxation does not engage; standard speed-gate path applies |
| Rider drops bike on slope, walks back | bike flat (angle ≥ 60°), accel-still ≥ 500 ms, bike rolls slowly: relaxation engages → IMPACT-→SILENCE → SILENCE-CHECK relaxation (Fix B) → Confirm → countdown. **Rider is present, cancels.** FP of countdown, not of sent SOS (same risk class as the SILENCE_CHECK relaxation already accepted). |
| Cornering crash (real crash mid-corner) | pre-impact ref leaned ~30°, post-crash bike flat ~90° → angle ≈ 60° → relaxation may engage. Real crash, correctly detected. |
| Cornering bump (rider survives, keeps cornering) | gyro stays high from cornering → gyroOk fails → relaxation does not engage |

Net trade-off: closes the IMPACT-phase residual cleanly. The single added FP
class (rider drops bike on smooth slope, walks away) is the same class the
SILENCE_CHECK relaxation already accepted; the rider is present to cancel.

## Testing

Pure JVM tests in `CrashStateMachineTest`:

- **`IMPACT on-side relaxation engages with sustained on-side and speed high`**:
  enter IMPACT with on-side silence-window samples (angle ~90°), keep speed
  high (10 km/h, `speedDropOk = false`), feed ≥25 `accelOk` samples; assert
  transition to SILENCE_CHECK occurs and Confirm fires (the SILENCE_CHECK
  relaxation then handles the continued speed-rise).
- **`gyro gate still blocks transition even with on-side relaxation`**: same
  setup but feed samples with gyro > 2.0 rad/s; assert SM stays in IMPACT.
  Documents that the relaxation does not bypass `gyroOk`.
- **`fewer than IMPACT_RELAXATION_MIN_SAMPLES does not engage`**: 20
  on-side samples + speed high → still IMPACT.
- **`upright IMPACT does not engage relaxation`**: angle < 60° → speed-gate
  remains the gating condition; SM stays in IMPACT until speed drops or
  IMPACT_TIMEOUT.
- **`invalid pre-impact reference does not engage relaxation`**: with
  `preImpactRef.valid = false`, `currentOrientationAngleDeg() = -1.0` →
  relaxation cannot engage; standard speed-gate path applies.
- **Integration: `MTB-style auto-resume mid-IMPACT confirms via relaxation
  chain`**: drives the full sequence — IMPACT → simulated autopause
  (no `onPause()` called for `auto=true`) → speed rises (simulating
  auto-resume) → IMPACT relaxation opens SILENCE_CHECK → SILENCE_CHECK
  relaxation keeps the silence window running → Confirm.

Assertion bands tight (no loose ranges).

## Out of scope

- Renaming follow-up: any test or doc that references `silenceWindowSum*`
  / `silenceWindowCount` by name (none in the test suite — verified) would
  be touched. Documentation in `crash-detection-algorithm.md` may carry
  the old names; updating it is part of this work but is mechanical.
- The pre-existing `updateGrade` threshold-rebuild race exposing the new
  `IMPACT_RELAXATION_MIN_SAMPLES` constant if it is ever made configurable
  per-preset — currently a fixed companion constant, no exposure.
- `CrashDetectionManager` / `EmergencyManager` test harness — no facade
  unit-test infrastructure exists; review-verification continues for
  facade-level changes (no such changes in this spec).
