# KSafe — Crash Detection Algorithm

> **Version:** April 2026 (revision 2 — post-review)
> **File:** `CrashDetectionManager.kt`
> **Sensors:** Android SensorManager (accelerometer + gyroscope) + Karoo SDK (speed)

---

## Overview

KSafe uses a **multi-stage confirmation pipeline** to detect bicycle crashes. A single sensor reading is never enough to trigger an alert — the algorithm requires a sequence of corroborating evidence across time and multiple data sources, designed to balance false-positive minimization against missing real crashes.

The pipeline has three sequential phases:

```
MONITORING ──[impact: smoothed OR peak]──► IMPACT ──[settling]──► SILENCE_CHECK ──[continuous still]──► CRASH CONFIRMED
                │                              │
                │                              └──[timeout: 15–25s] ──► MONITORING  (false alarm: kept riding)
                │
                └── Speed-drop monitor running in parallel (independent coroutine, with stable-stillness guard)
```

Two new mechanisms in this revision:
- **Dual impact detector** at MONITORING entry — a smoothed-magnitude path and a single-sample peak path, OR-combined.
- **GPS-stale fallback** with hardened accelerometer thresholds when speed data is frozen mid-ride.

---

## Sensors & Data Sources

| Source | Rate | Used for |
|--------|------|----------|
| **Accelerometer** (`TYPE_ACCELEROMETER`) | ~50 Hz (`SENSOR_DELAY_GAME`) | Primary crash trigger + stillness confirmation |
| **Gyroscope** (`TYPE_GYROSCOPE`) | ~50 Hz | Gate between IMPACT → SILENCE_CHECK |
| **GPS/speed** (Karoo SDK) | Variable | Speed drop confirmation in all phases |

> Note: `TYPE_ACCELEROMETER` is required (includes gravity). The whole stillness logic compares magnitude against 9.81 m/s². If anyone ever changes this to `TYPE_LINEAR_ACCELERATION`, the algorithm breaks silently.

### Speed data notes
- The Karoo SDK delivers speed in **m/s (SI units)**; the app converts internally to **km/h**.
- If GPS lock is lost (or there is no ANT+ speed sensor), the SDK returns the **last known value** (not zero). This is a known SDK behavior and the algorithm now compensates for it via the **GPS-stale fallback** (see below).
- If no GPS fix has ever been acquired, speed defaults to `0.0 km/h` — protected by the **cold-start guard**.

---

## Configuration Parameters

All parameters live in `KSafeConfig` and are user-configurable via the Settings screen.

| Parameter | Default | Description |
|-----------|---------|-------------|
| `crashDetectionEnabled` | `true` | Master enable switch |
| `crashSensitivity` | `MEDIUM` | Preset: LOW / MEDIUM / HIGH / CUSTOM |
| `minSpeedForCrashKmh` | `10` km/h | Minimum speed for impact to be considered. `0` = always detect. **See Open Items** |
| `customCrashThreshold` | `45` m/s² | Smoothed-magnitude threshold when sensitivity = CUSTOM (range 20–70) |
| `crashConfirmSpeedKmh` | `5` km/h (3 for LOW) | Max GPS speed to consider rider stopped during confirmation |
| `crashMonitorOutsideRide` | `false` | Keep detection active when no Karoo ride is running |
| `crashMonitorOutsideRideAnySpeed` | `false` | Force `minSpeed = 0` outside rides (⚠ more false positives) |
| `countdownSeconds` | `30` | Duration of the cancel window before alert is sent |

### Impact thresholds by sensitivity preset (smoothed magnitude)

The **smoothed** threshold is the 3-sample moving average of total acceleration vector magnitude. At rest this baseline is ~9.8 m/s² (1g).

| Preset | Smoothed threshold | G equivalent | Typical use |
|--------|-------------------|-------------|-------------|
| **LOW** | 55 m/s² | ~5.5g | MTB, gravel — hard terrain, many bumps |
| **MEDIUM** | 45 m/s² | ~4.5g | Mixed road + light gravel |
| **HIGH** | 35 m/s² | ~3.5g | Road bike — clean crashes, high sensitivity |
| **CUSTOM** | 20–70 m/s² | 2–7g | User-defined |

### Peak thresholds by sensitivity preset (single sample)

The **peak** detector fires on a single raw sample exceeding a higher bar, without the 3-sample smoothing. It captures sharp, rigid impacts that last only 10–20ms (one frame at 50 Hz).

| Preset | Peak threshold | G equivalent |
|--------|---------------|-------------|
| **LOW** | 70 m/s² | ~7g |
| **MEDIUM** | 60 m/s² | ~6g |
| **HIGH** | 50 m/s² | ~5g |
| **CUSTOM** | 1.3 × `customCrashThreshold`, capped at 80 m/s² | varies |

> **Why two detectors:** the smoothed path rejects single-sample noise from cobblestones, dirt-to-asphalt transitions or speed bumps. But a real rigid impact (handlebar against asphalt, direct collision with an obstacle) can produce a peak that lasts only 10–20ms — a single sample at 50 Hz. Smoothing alone would dilute a 70 m/s² peak with two surrounding 15 m/s² samples down to ~33 m/s², below the MEDIUM/HIGH smoothed thresholds, causing a silent false negative. The peak path catches these short events; the higher per-preset bar compensates for the lower statistical reliability of a single-sample reading.

> **Reference (IEEE Accident Detection literature):**
> Normal bumps / hard braking → up to ~1.5g (14.7 m/s²)
> MTB jump landing → 3–5g, typically followed by continued movement
> Real crash → 4–7g followed by sustained stillness

### Impact confirmation window by preset

Maximum time from impact detection to crash confirmation. If the device never settles within this window, it is treated as a false alarm.

| Preset | Window |
|--------|--------|
| **LOW** | **25 seconds** |
| **MEDIUM** | **20 seconds** |
| **HIGH** | **15 seconds** |
| **CUSTOM** | 20 seconds |

MTB/LOW gets a longer window because after a real crash on a slope, the bike may slide or tumble for several seconds before coming to rest.

### Confirm speed by preset

Maximum GPS speed (km/h) to consider the rider "stopped" during crash confirmation. Auto-set when changing preset.

| Preset | Confirm speed |
|--------|--------------|
| **LOW** | 3 km/h |
| **MEDIUM** | 5 km/h |
| **HIGH** | 5 km/h |
| **CUSTOM** | User-configured |

---

## Phase 1 — MONITORING (baseline state)

The accelerometer listener runs continuously at ~50 Hz. On every sensor event:

### Step 1: Compute raw and smoothed magnitude

```
magnitude         = √(x² + y² + z²)              // single-sample raw vector
smoothedMagnitude = avg(last 3 magnitudes)       // ~60ms window at 50 Hz
```

### Step 2: Dual-detector impact gate

The impact is detected when **either** detector fires:

```
impactDetected = (smoothedMagnitude > smoothedThreshold) OR (magnitude > peakThreshold)
```

Both paths feed into the same downstream confirmation sequence (IMPACT → SILENCE_CHECK).

### Step 3: Additional gate conditions (all must be true to advance to IMPACT)

| Condition | Value | Rationale |
|-----------|-------|-----------|
| `impactDetected` | dual gate above | sustained energy or sharp peak |
| `currentSpeedKmh >= minSpeedForCrashKmh` | default 10 km/h | avoid triggering at rest / picking up bike |
| `now - lastCrashTime > crashCooldownMs` | dynamic, see below | no duplicate alerts during active countdown |

If all three pass → transition to **IMPACT**, record `impactTime = now`.

> **Caveat about the `minSpeed` gate when GPS is stale:** the `currentSpeedKmh` value used here may be the SDK's last-known value, not a current reading. This is a known limitation — see Open Items.

---

## Phase 2 — IMPACT (waiting for the situation to settle)

After an impact is detected, the system waits for the device to approach a resting state. This is the **primary discriminator between a real crash and a jump/bump that continues into normal riding**.

### Conditions to advance to SILENCE_CHECK (all must be true)

| Condition | Threshold | Meaning |
|-----------|-----------|---------|
| `\|magnitude − 9.81\| < 4.0 m/s²` | `SILENCE_DEVIATION_MAX = 4.0` | Accelerometer is close to gravity → device lying still |
| `lastGyroMag < 2.0 rad/s` | `GYRO_MOVING_MAX = 2.0` | Gyroscope indicates device is not actively spinning / riding (~115°/s) |
| `timeSinceImpact > 500ms` | hardcoded | Minimum half-second after impact (prevents instant re-trigger) |
| `isSpeedDropConfirmed()` | preset-dependent | GPS speed (or stale fallback) confirms rider has slowed / stopped |

→ All four pass: transition to **SILENCE_CHECK**, record `silenceStartTime = now`.

### Timeout (false alarm path)

If the conditions above are **never all satisfied** within the impact window:

```
timeSinceImpact > impactWindowMs  →  resetState()  →  MONITORING
```

This handles the most common false alarm: a jump or very hard bump where the rider continues riding.

---

## Phase 3 — SILENCE_CHECK (continuous stillness)

Once the device has appeared to settle, the algorithm requires **uninterrupted stillness** for a duration that depends on whether GPS data is fresh or stale.

### Stillness condition (`isStill`)

```kotlin
val gpsStale = isGpsStale()
val effectiveDeviationMax = if (gpsStale) GPS_STALE_DEVIATION_MAX else SILENCE_DEVIATION_MAX
val effectiveSilenceMs    = if (gpsStale) GPS_STALE_SILENCE_DURATION_MS else SILENCE_DURATION_MS
val deviation             = abs(magnitude - GRAVITY)
val isStill               = deviation <= effectiveDeviationMax && isSpeedDropConfirmed()
```

| Mode | Deviation max | Required stillness | Notes |
|------|--------------|-------------------|-------|
| GPS fresh (normal) | 4.0 m/s² | 4,500 ms | Standard, GPS gate is doing most of the discrimination |
| GPS stale (>10s no update) | 1.5 m/s² | 8,000 ms | Hardened — accel is now the only discriminator |

### Why the gyroscope is intentionally NOT evaluated in SILENCE_CHECK

The case being protected against is the bike **lying on its side after a crash with the rear wheel still spinning freely in the air (freewheel)**. In that state:
- GPS = 0 (the bike isn't translating in space)
- Accelerometer ≈ gravity (the device frame is stationary)
- **Gyroscope reads high rotation** (the wheel is spinning the buje)

If the algorithm required `gyro < threshold` here, that perfectly valid crash would never be confirmed.

> Note: both GPS and gyro measure the Karoo device (mounted on the bike), not the rider. The previous documentation was incorrect in framing the gyro removal as "the rider may be still while the bike keeps moving" — in the bike-keeps-moving scenario, GPS would also be moving and is what blocks confirmation. The actual scenario (and the one that justifies removing the gyro) is the freewheel case described above.

### Outcomes on each accelerometer sample

| Scenario | Action |
|----------|--------|
| `isStill` AND `now - silenceStartTime >= effectiveSilenceMs` | **🚨 CRASH CONFIRMED** → fire alert |
| `isStill` AND silence timer not yet elapsed | No action — keep counting |
| `!isStill` AND `timeSinceImpact <= impactWindow × 2` | Reset `silenceStartTime = now` — stillness must be **continuous**, not cumulative |
| `!isStill` AND `timeSinceImpact > impactWindow × 2` | False alarm → `resetState()` → MONITORING |

The doubled timeout (`impactWindow × 2`) gives a generous total window: for LOW preset, up to **50 seconds** from impact before finally giving up.

---

## `isSpeedDropConfirmed()` — Detailed logic

This helper is called in both IMPACT (gate check) and SILENCE_CHECK (stillness check).

```
isSpeedDropConfirmed():
│
├─ Cold-start guard active?
│   (no speed data received yet AND now - startTime < 8,000ms)
│   └─ return false  ← block confirmation, not safe to trust speed=0.0
│
├─ GPS stale? (speedLastUpdatedTime > 0 AND now - speedLastUpdatedTime > 10,000ms)
│   └─ return true  ← bypass speed gate, accel takes over with HARDENED thresholds
│      ⚠ Caller (SILENCE_CHECK) is expected to switch to GPS_STALE_DEVIATION_MAX
│        and GPS_STALE_SILENCE_DURATION_MS to compensate.
│
├─ minSpeedForCrashKmh == 0?
│   └─ return true  ← "detect at any speed" mode, skip speed gate
│
└─ return (currentSpeedKmh < crashConfirmSpeedKmh)
    ├─ LOW preset  → < 3.0 km/h
    └─ MEDIUM/HIGH → < 5.0 km/h
```

### Cold-start guard

A `COLD_START_GUARD_MS = 8,000ms` window blocks speed-drop confirmation if no real GPS/speed data has been received yet, preventing false confirmations from `currentSpeedKmh = 0.0` defaults.

- `speedDataReceived` starts as `false` on every `start()` call
- First `updateSpeed()` call: sets `speedDataReceived = true` → guard lifts immediately
- If no speed data arrives within 8 seconds: guard expires automatically (fallback for devices with no GPS / no ANT+)
- `speedDataReceived` is **not** reset on `resetState()` — only on `start()` — so a false-alarm reset during normal riding never re-introduces the cold-start window

### GPS-stale fallback (NEW)

The Karoo SDK returns the last-known speed value when GPS lock is lost mid-ride (tunnels, dense forest, cable disconnect). Without mitigation, this could:
- **Block confirmation indefinitely** if last-known was high (e.g. 35 km/h before entering tunnel) — `isSpeedDropConfirmed` would always return `false`.
- **Falsely confirm** if last-known was 0 km/h before the GPS dropout.

When `now - speedLastUpdatedTime > GPS_STALE_MS (10s)`:
1. `isSpeedDropConfirmed()` returns `true` so the accel can take over.
2. SILENCE_CHECK switches to **stricter** thresholds: deviation ≤ 1.5 m/s² (vs 4.0) and stillness ≥ 8 s (vs 4.5 s).

This reduces the chance that passive coasting between bumps with frozen GPS is misread as the rider on the ground, while still allowing real crashes to be confirmed.

---

## Post-crash Cooldown

```
crashCooldownMs = (countdownSeconds * 1000) + 30,000
```

After a crash is confirmed, MONITORING ignores new impact spikes for this duration. The formula guarantees the cooldown is **strictly greater** than the cancel countdown — preventing a re-trigger immediately after the user cancels.

| `countdownSeconds` | Cooldown |
|-------------------|----------|
| 30 (default) | 60 s |
| 60 | 90 s |
| 15 | 45 s |

This prevents:
- Duplicate alerts while the rider is still on the ground moving slightly
- Multiple alerts while the cancel countdown is running
- Re-triggers in the seconds after a manual cancel (the previous fixed 30s value left only ~1s margin)

---

## Speed-Drop Detection (independent system)

A completely separate mechanism that runs as a background coroutine, checking every 30 seconds:

```
Every 30s:
  if (speedDropStartTime > 0):
    stoppedFor = (now - speedDropStartTime) / 60,000

    if stoppedFor >= config.speedDropMinutes (default 5 min):

      // NEW: stable-stillness guard
      stillStableFor = if (accelStillSinceMs > 0) now - accelStillSinceMs else 0
      accelOk = stillStableFor >= SPEED_DROP_ACCEL_STILL_MS (60 s)

      if accelOk:
        → onCrashDetected()
      else:
        → log and wait
```

### Stable-stillness accumulator

`accelStillSinceMs` is a timestamp maintained on every accelerometer event:
- Reset to 0 whenever `lastAccelDeviation > SILENCE_DEVIATION_MAX` (any movement).
- Set to `now` when stillness begins.

The speed-drop monitor reads `now - accelStillSinceMs` to know how long the device has been **continuously** still. This replaces the previous snapshot-based check, which could land on either side of the rider's activity by chance and produce both false positives (rider on a phone call but bike happened to be still at the moment of polling) and false negatives.

### Triggering and reset

- `updateSpeed()` starts the `speedDropStartTime` timer when `speedKmh < SPEED_THRESHOLD_KMH (5 km/h)`.
- Timer resets when speed rises above 5 km/h again.
- `resetSpeedDropOnPause()` clears the timer when the Karoo ride is paused — prevents alarms at cafés, red lights, mechanical stops.

### Use case

Captures the scenario where the rider falls and is unconscious at low speed (e.g. medical episode while climbing), where the accelerometer impact may be too gentle for the main pipeline but the GPS speed goes to zero and stays there.

> **Latency impact:** with the stable-stillness guard, the alert now requires `speedDropMinutes` AND `SPEED_DROP_ACCEL_STILL_MS` — so the worst-case latency is `5 min + 60 s` from the actual incident. This is a deliberate trade-off against false positives during normal long stops.

---

## False Alarm Mitigation Summary

| Scenario | Mitigation mechanism |
|----------|---------------------|
| Cobblestone / speed bump | Sliding-window average (3 frames ≈ 60ms) on smoothed path |
| Sensor noise on single sample | Higher per-preset bar on peak path |
| MTB jump landing (rider continues) | Impact window timeout (15–25s of no settling) |
| Hard braking | `minSpeedForCrash` gate + acceleration below threshold |
| Picking up the stationary bike | `minSpeedForCrash` gate (must be moving first) |
| App startup / cold start | Cold-start guard (8s until first speed data) |
| Slow climbing bump | GPS speed gate in SILENCE_CHECK |
| Double alert after confirmed crash | `crashCooldownMs > countdownSeconds` |
| Paused at café / traffic light | Speed-drop timer reset on ride pause |
| Bike keeps sliding without rider | Gyroscope removed from SILENCE_CHECK final check (only GPS counts) |
| GPS frozen at last known value mid-ride | GPS-stale fallback: bypass speed gate, harden accel thresholds |
| Speed-drop poll lands on lucky moment | Stable-stillness accumulator (60s continuous) |

---

## State Machine Diagram

```
                       ┌─────────────────────────────────────────┐
                       │               MONITORING                 │
                       │  • Running at ~50 Hz                    │
                       │  • Watching for: smoothed > thr OR      │
                       │                  raw peak > peak_thr    │
                       │  • Speed ≥ minSpeedForCrash             │
                       │  • Cooldown elapsed (countdown + 30s)   │
                       └───────────────┬─────────────────────────┘
                                       │ either detector fires
                                       ▼
                       ┌─────────────────────────────────────────┐
                       │                 IMPACT                   │
                       │  • Clock starts (impactTime)            │
                       │  • Waiting for device to settle         │
                       │  • Gate: accel≈gravity                  │◄── timeout (15–25s) ──► MONITORING
                       │          gyro < 2.0 rad/s               │
                       │          GPS < confirmSpeed (or stale)  │
                       │          > 500ms elapsed                │
                       └───────────────┬─────────────────────────┘
                                       │ all gates pass
                                       ▼
                       ┌─────────────────────────────────────────┐
                       │             SILENCE_CHECK                │
                       │  • silenceStartTime = now               │
                       │  • Normal:  dev ≤ 4.0 + GPS<thr  (4.5s) │◄── NOT still ──► reset timer
                       │  • Stale:   dev ≤ 1.5            (8.0s) │                    (or timeout × 2
                       │                                          │                     → MONITORING)
                       └───────────────┬─────────────────────────┘
                                       │ continuous stillness for required duration
                                       ▼
                              🚨 CRASH CONFIRMED
                         (countdown begins, then alert)
```

---

## Timing Summary

| Parameter | Value |
|-----------|-------|
| Sensor sampling rate | ~50 Hz |
| Smoothing window | 3 samples ≈ 60ms |
| Smoothed thresholds | 35 / 45 / 55 m/s² (HIGH / MEDIUM / LOW) |
| Peak thresholds (single sample) | 50 / 60 / 70 m/s² (HIGH / MEDIUM / LOW) |
| Minimum time from impact to SILENCE entry | 500ms |
| IMPACT timeout (LOW) | 25,000ms |
| IMPACT timeout (MEDIUM) | 20,000ms |
| IMPACT timeout (HIGH) | 15,000ms |
| SILENCE_CHECK required duration (GPS fresh) | 4,500ms |
| SILENCE_CHECK required duration (GPS stale) | 8,000ms |
| SILENCE_CHECK deviation max (GPS fresh) | 4.0 m/s² |
| SILENCE_CHECK deviation max (GPS stale) | 1.5 m/s² |
| Hard abort (SILENCE_CHECK, LOW) | 50,000ms (2× impact window) |
| Post-crash cooldown | `countdown + 30,000ms` (60s with default config) |
| Cold-start guard | 8,000ms |
| GPS stale threshold | 10,000ms |
| Speed-drop check interval | 30,000ms |
| Speed-drop confirmation duration | configurable (default 5 min) |
| Speed-drop stable-stillness requirement | 60,000ms |

---

## Threading & Concurrency Notes

The class is accessed from multiple threads:
- **Sensor thread** — `onSensorChanged` callbacks for accel and gyro
- **Karoo SDK thread** — `updateSpeed()`, `resetSpeedDropOnPause()`
- **Coroutine on `scope`** — speed-drop monitor (reads accel and speed state)
- **Main thread** — `start()`, `stop()`, `updateConfig()`

All shared mutable fields are marked `@Volatile`:

```
state, impactTime, silenceStartTime, lastCrashTime,
currentSpeedKmh, config, lastLogTime,
speedDataReceived, startTime,
lastGyroMag, lastAccelDeviation, accelStillSinceMs,
speedDropStartTime, speedLastUpdatedTime
```

This guarantees cross-thread visibility (writes are not held in a CPU cache) and atomicity of Long/Double reads — neither of which the JVM guarantees by default.

The class does not use locks. The algorithm tolerates slightly stale reads across threads (e.g. an accelerometer event firing immediately after a speed update may evaluate against the previous speed value), which is acceptable given the 50 Hz sample rate and the multi-sample confirmation pipeline.

---

## Change Log (vs. previous revision)

### Closed

| ID | Change | Status |
|----|--------|--------|
| **P1** | Dual impact detector: smoothed OR single-sample peak. Per-preset peak thresholds 50/60/70 m/s². Captures short rigid impacts that the smoother would dilute below threshold. | ✅ Implemented |
| **P2** | Gyroscope removal from SILENCE_CHECK now correctly justified: the protected case is the freewheel (rear wheel spinning while bike is still). Previous justification ("rider on ground but bike rolling") was technically wrong because GPS would also detect that motion. | ✅ Doc + code comments updated |
| **P3** | GPS-stale fallback: when no speed update for >10s, `isSpeedDropConfirmed` returns `true` so accel takes over, AND SILENCE_CHECK switches to stricter thresholds (1.5 m/s² deviation, 8s duration) to compensate for the loss of the GPS discriminator. | ✅ Implemented |
| **P6** | `crashCooldownMs = countdownSeconds * 1000 + 30s` instead of fixed 30s. Guarantees cooldown > countdown so a manual cancel cannot be immediately followed by a re-trigger. | ✅ Implemented |
| **P7** | Speed-drop monitor now requires `accelStillSinceMs` to indicate ≥60s of continuous accelerometer stillness, not just a snapshot at the polling boundary. Eliminates false positives/negatives from poll-timing luck. | ✅ Implemented |
| **Threading** | All cross-thread mutable fields marked `@Volatile` (14 fields). Guarantees visibility and atomicity for Long/Double in the JVM memory model. | ✅ Implemented |

### Documentation-only fixes

- "MTB jump landing → 3–5g but always followed by continued movement" — removed the "always" wording, which overstates the empirical rule.
- Added explicit `TYPE_ACCELEROMETER` requirement (vs `TYPE_LINEAR_ACCELERATION`) in the Sensors section.

---

## Open Items / Known Limitations

### P4 — `minSpeedForCrashKmh` gate at low speeds (PARTIALLY ADDRESSED, REMAINING OPEN)

**Current state:** `minSpeedForCrashKmh` IS already modulated per preset by the Settings screen when the user selects a chip:

| Preset | `minSpeedForCrash` set by chip | `crashConfirmSpeed` |
|--------|-------------------------------|---------------------|
| LOW | **3 km/h** | 3 km/h |
| MEDIUM | 10 km/h | 5 km/h |
| HIGH | 15 km/h | 5 km/h |

The LOW preset at 3 km/h already covers the most common low-speed MTB scenarios (technical climb, slow singletrack). The speed-drop monitor catches the unconscious-rider case at any speed after 5+ min.

**Remaining concern:** MEDIUM uses 10 km/h as the impact gate. This excludes:
- Track-stand failure at a traffic light, foot stuck in cleat (0 km/h) for MEDIUM/HIGH users.
- A confident MEDIUM user who falls at 6 km/h on a gravel track.

**Options under consideration:**
- Lower MEDIUM from 10 to 5 km/h (risk: more false positives from hard braking at low speed).
- Replace the binary gate with a soft cutoff: at low speed, require a higher impact threshold instead of skipping entirely (more complex, eliminates the hard cutoff problem).

**Status:** LOW is considered resolved. MEDIUM/HIGH binary cutoff remains a known limitation. No code change until empirical false-positive data is available to justify the trade-off.

### Empirical calibration needed

Several thresholds introduced in this revision are educated guesses, not data-derived:

| Parameter | Value | Justification |
|-----------|-------|--------------|
| Peak thresholds | 70 / 60 / 50 m/s² | ~1.3× the smoothed thresholds; needs validation against real impact logs |
| `GPS_STALE_DEVIATION_MAX` | 1.5 m/s² | Order-of-magnitude estimate for "device truly motionless on the ground" |
| `GPS_STALE_SILENCE_DURATION_MS` | 8,000 ms | ~2× the nominal duration; chosen conservatively |
| `SPEED_DROP_ACCEL_STILL_MS` | 60,000 ms | Long enough to filter normal human activity, short relative to a 5 min ride pause |

Recommendation: log raw accel peaks, smoothed peaks, and `accelStillSinceMs` durations during a calibration period (1–2 months across diverse riders), then re-evaluate.

### Residual risks not yet addressed

1. **MONITORING gate uses stale `currentSpeedKmh`** — when GPS is stale, the entry condition `currentSpeedKmh >= minSpeedForCrashKmh` is evaluated against the frozen last-known value. In most cases this is conservative (frozen at riding speed → allows evaluation), but it remains conceptually inconsistent with the GPS-stale handling elsewhere. Not currently mitigated.

2. **Cold-start may exceed 8s** in cold-start GPS scenarios (urban canyon, overcast). If the guard expires without speed data, MONITORING is effectively blocked because `currentSpeedKmh = 0.0` won't pass `minSpeed`. This is fail-safe (no false positives) but means there is no detection during the first ~30s of a cold start. Documented limitation.

3. **`SensorManager` event batching** — `SENSOR_DELAY_GAME` does not guarantee 50 Hz. Android may batch events under load, in which case the 3-sample smoothing window may cover more than 60ms. Has not been observed in practice but worth measuring. Workaround if needed: use sample timestamps instead of fixed-count windows.

---