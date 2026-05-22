# Crash Detection — Pre-Impact Orientation Reference

> **Date:** 2026-05-22
> **Branch:** `feature/crash-detection-reliability`
> **Affects:** `CrashStateMachine`, `SensorReader`, `CrashDetectionManager`, `Thresholds`
> **Supersedes:** the Revision 5 "orientation-aware silence" learned-baseline mechanism

## Problem

The May-2026 calibration log `ksafe_v1.2.0_60a27a_k24.csv` contains a confirmed
crash (`CRASH_OK`) that is almost certainly a **false positive** — the textbook
*bump + brake + stop* scenario:

- A single isolated accelerometer spike on gravel (`buf=7.5|24.9|54.5`, peak 54.5,
  smoothed only 28.9), high background noise (4.19), low rotation (gyro 0.78).
- **17.1 s** of continued riding after the spike (`time_since_impact_ms=17148`),
  decelerating gradually from 18.9 → 1.1 km/h — a normal stop, not a crash stop.
- The bike then stood still and upright; the algorithm confirmed at the legacy
  4.5 s silence window.

The Revision 5 "orientation-aware silence" feature was meant to catch exactly
this. It does not, because it depends on a **learned baseline** gravity vector
that requires ~1500 cruising samples gated by `speed ≥ 15 km/h AND
accelStdDev < 1.5`.

Empirical proof from the v2.0.0 calibration logs:

- On `profile=GRAVEL`, **99 of 105** `PERIODIC` samples (94 %) are below 15 km/h.
- The 6 samples at ≥ 15 km/h **all** have `noise > 1.5`.
- → **Zero** gravel samples satisfy the baseline-learning gate. The baseline
  never becomes ready off-road.
- The only `ORIENT_BASE` event observed across 26 v2.0.0 logs is on `profile=ROAD`,
  and it took ~2084 s (≈ 35 min) of riding.

**Conclusion:** the learned-baseline mechanism is dead code off-road, and slow
on-road. The false positive is unprotected on gravel/MTB — the primary use case.

## Root cause

The orientation *idea* is sound — a crash changes the bike's posture, a
false positive does not. The flaw is the **reference** the silence window is
compared against: a 30-minute learned cruising average. That reference is what
dies on gravel (speed/noise gates) and what is slow on road.

## Solution overview

Replace the learned baseline with a **local pre-impact reference**: the average
gravity-vector direction over the ~2 s immediately preceding the impact. The
bike was being ridden then — a fresh, terrain-independent "upright" reference
needing no learning and available from the first minute of any ride.

Two independent signals decide the silence-window duration, each authoritative
in its own regime:

| Signal | Measures | Authoritative when |
|--------|----------|--------------------|
| **Gap** (`firstSilenceGapMs`) | Time from impact to first stillness — impact→stop causality | Always available; owns *delayed* stops |
| **Orientation** (pre-impact vs silence angle) | Whether the bike changed posture | Owns *prompt* stops, when a valid reference exists |

### Decision table

```
gap LONG  (> 8 s, rode on after impact)      → 20 s   [gap regime; orientation ignored]
gap SHORT (≤ 8 s, stopped with the impact):
     orientation = on-side  (angle ≥ 45°)    → 4.5 s  [clear crash, fast alert]
     orientation = upright  (angle < 45°)    → 20 s   [ambiguous, wait]
     orientation = unknown  (no valid ref)   → 4.5 s  [abrupt stop after impact → lean crash]
```

- The **gap** does the heavy false-positive lifting. The bump+brake+stop FP has
  a long gap (17 s) → 20 s, with no dependency on orientation or terrain. This
  is what fixes gravel/MTB.
- The **orientation** does scoped work: among *prompt* stops, it distinguishes a
  crash (bike went down → fast 4.5 s alert) from an ambiguous upright stop
  (→ 20 s).
- A real crash always has a short gap (the bike hits the ground; speed collapses
  in 1–4 s), so the gap rule **never delays a real crash**.
- The 20 s window only ever costs a delay for an event mis-classified as a
  delayed stop; for a true FP "waiting" means the rider rides off and nothing
  fires — zero cost. The 20 s value itself is unchanged from Revision 5.

## Design

### Component changes

The change respects the existing separation of concerns: **`SensorReader`
buffers, the facade orchestrates, `CrashStateMachine` is pure.**

| Component | Change |
|-----------|--------|
| `SensorReader` | **+** ring buffer of `(x, y, z, timestampMs)`, ~150 entries. **+** `preImpactReference(impactTs)`. |
| `CrashStateMachine` | **−** all learned-baseline machinery. **+** `setPreImpactReference()`, `firstSilenceGapMs` capture. **~** `computeEffectiveSilenceMs` rewritten. |
| `CrashDetectionManager` | **~** on `EnterImpact`, fetch the reference from `SensorReader` and inject it into the state machine. **−** baseline-feed block, cruising gate, `ORIENT_BASE` logging. **~** calibration-log fields. |
| `Thresholds` | **−** `baselineMinSamples`, `baselineCruisingMinSpeedKmh`. **+** `delayedStopGapMs = 8_000`. **=** `silenceDurationMs` (4.5 s), `silenceDurationUprightMs` (20 s), `uprightAngleThresholdDegrees` (45°) unchanged. |
| Tests | **−** baseline tests. **+** pre-impact-reference and regime tests. |

`SensorSample` is unchanged — it already carries `accelX/Y/Z`.

### Pre-impact reference capture (`SensorReader`)

On the sensor thread, every accelerometer sample is appended to a primitive
ring buffer of `(x, y, z, timestampMs)`, ~150 entries (~3 s at 50 Hz),
zero allocation per sample like the existing magnitude/variance rings.

`preImpactReference(impactTs: Long): PreImpactRef` averages the slice
`[impactTs − 250ms − 2000ms , impactTs − 250ms]`:

- the **250 ms guard** excludes the impact transient (the peak rises over
  ~60 ms — see `buf=7.5|24.9|54.5`);
- the **2000 ms window** is a robust average that absorbs gravel/MTB bounce
  (bounce is zero-mean around the true orientation, so averaging converges);
- returns `PreImpactRef(x, y, z, valid=false)` when the ring does not hold
  enough samples in that range (cold start, or < 2 s after a resume).

`PreImpactRef` is an immutable data class `(x, y, z, valid)`. The averaging is
**pure arithmetic**, extracted so it is unit-testable on the JVM without
constructing `SensorEvent` (which is not instantiable in unit tests).

The ring is cleared in `onPause()` and `stop()`. An impact within ~2 s of ride
start or of a resume yields `valid = false`.

### Wiring (`CrashDetectionManager`)

When `handleMonitoring` returns `Decision.EnterImpact`, the facade calls
`sensorReader.preImpactReference(sample.timestampMs)` and passes the result to
`stateMachine.setPreImpactReference(ref)`. Computed once per impact event;
summing ~100 ring entries is negligible.

### Gap capture (`CrashStateMachine`)

On the **first** IMPACT → SILENCE_CHECK transition of an event, the state
machine records `firstSilenceGapMs = silenceStartedMs − impactStartedMs`. It is
**not** recomputed on subsequent silence breaks — a break is micro-movement
*within* the stillness phase, not "riding on". It is reset when the state
machine returns to MONITORING.

### `computeEffectiveSilenceMs` (rewritten)

```
if (lockedEffectiveSilenceMs > 0) return lockedEffectiveSilenceMs        // latch

legacyShort = gpsStale ? gpsStaleSilenceDurationMs(8000) : silenceDurationMs(4500)

// Gap regime — delayed stop. Needs no orientation.
if (firstSilenceGapMs > delayedStopGapMs(8000)) {
    latch(silenceDurationUprightMs)        // 20000
    return 20000
}

// Orientation regime — prompt stop.
if (!preImpactRef.valid) { latch(legacyShort); return legacyShort }      // never improves
if (silenceWindowCount < MIN_ORIENTATION_SAMPLES) return legacyShort     // unlatched: count may grow
angle = angleBetween(preImpactRef, silenceAverage)                       // acos of normalised dot product
chosen = if (angle >= uprightAngleThresholdDegrees(45)) legacyShort else silenceDurationUprightMs
latch(chosen); return chosen
```

`silenceAverage` is computed from the existing `silenceWindowSumX/Y/Z` /
`silenceWindowCount` accumulators (unchanged). The `MIN_ORIENTATION_SAMPLES = 5`
guard is retained.

### Edge cases

- **Invalid reference** (cold start / < 2 s after resume) + short gap → `legacyShort`,
  latched immediately (it will never become valid).
- **Degenerate vectors** (magnitude < `EPSILON`) → `legacyShort` (existing guard).
- **GPS-stale** changes only `legacyShort` (→ 8 s). The `isStill` deviation
  thresholds (`gpsStaleSilenceDeviationMax`) are unchanged.
- **Latch** is cleared on every entry/exit/break of SILENCE_CHECK, as today —
  each continuous window decides fresh. After a break the already-captured
  `firstSilenceGapMs` still applies.
- `onSample` / `handleImpact` / `handleSilenceCheck` keep their structure;
  `handleSilenceCheck` still accumulates `silenceWindowSumX/Y/Z`.

### Removals

`baselineX/Y/Z`, `baselineSampleCount`, `feedBaselineSample`, `isBaselineReady`,
`baselineVector`, the capped-divisor EMA logic; in the facade the baseline-feed
block and cruising gate, `BASELINE_CRUISING_MAX_STDDEV`, `loggedBaselineReady`;
in `Thresholds` `baselineMinSamples` and `baselineCruisingMinSpeedKmh`; the
`ORIENTATION_BASELINE` / `ORIENT_BASE` calibration event.

### Calibration logging

Events `SIL_IN`, `CRASH_OK`, `IMPACT_TMO` gain: `pre_x` / `pre_y` / `pre_z`,
`pre_valid`, `pre_impact_angle` (degrees, pre-impact vs silence), `gap_ms`, and
`decided_by = GAP | ORIENT_UPRIGHT | ORIENT_ONSIDE | UNKNOWN`. This keeps future
logs auditable the way the `60a27a` log was. The `ORIENT_BASE` event is removed.

## Testing

Pure JVM tests (no `androidTest` source set), TDD:

- **`SensorReaderTest`** — ring fills and wraps correctly; `preImpactReference`
  averages the correct slice; returns `valid = false` on insufficient data; the
  250 ms guard genuinely excludes the transient. The averaging is tested as a
  pure function (no `SensorEvent`).
- **`CrashStateMachineTest`** — one case per regime: gap > 8 s → 20 s (asserted
  with *both* on-side and upright orientation, proving orientation is ignored);
  gap ≤ 8 s + on-side angle → 4.5 s; gap ≤ 8 s + upright angle → 20 s;
  gap ≤ 8 s + invalid reference → 4.5 s; GPS-stale variants → 8 s; latch and
  silence break/restart behaviour. Baseline tests are removed.
- **Integration scenario** — replay of the `60a27a` false positive (17 s gap)
  must resolve to 20 s and **not** confirm; a synthetic crash (short gap + large
  orientation change) must resolve to 4.5 s.

Assertion bands are tight (±15 %), not "any plausible number".

## Out of scope

- Tuning the 20 s / 4.5 s / 45° / 8 s values beyond the defaults above — they
  are named constants, adjustable once future logs are available.
- The second event in `60a27a` (`IMPACT_TMO`, `why_no_silence=SPEED`) is already
  handled correctly and is unchanged.
- The parallel speed-drop monitor, grade-aware peak boost and TERRAIN_CLUSTER
  mechanisms are untouched.

## Risks

- A real crash whose bike keeps moving > 8 s after the impact (e.g. a long slide
  on a steep descent) gets the 20 s window instead of 4.5 s — a ~15 s delay. The
  rider is down and will not move, so it still confirms. Judged acceptable: the
  case is rare and ambiguous, and the alternative is leaving the FP unprotected.
- The pre-impact reference can be skewed if the impact happens mid-corner (the
  bike is leaned). This is mitigated by the gap regime — a mid-corner bump
  followed by riding on has a long gap → 20 s regardless of orientation.
