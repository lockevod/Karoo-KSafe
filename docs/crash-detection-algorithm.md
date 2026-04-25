# KSafe — Crash Detection Algorithm

> **Version:** April 2026  
> **File:** `CrashDetectionManager.kt`  
> **Sensors:** Android SensorManager (accelerometer + gyroscope) + Karoo SDK (speed)

---

## Overview

KSafe uses a **multi-stage confirmation pipeline** to detect bicycle crashes. A single sensor reading is never enough to trigger an alert — the algorithm requires a sequence of corroborating evidence across time and multiple data sources, specifically designed to minimize false positives while ensuring real crashes are never missed.

The pipeline has three sequential phases:

```
MONITORING ──[impact spike]──► IMPACT ──[settling]──► SILENCE_CHECK ──[4.5s still]──► CRASH CONFIRMED
                │                  │
                │                  └──[timeout: 15–25s] ──► MONITORING  (false alarm: kept riding)
                │
                └── Speed-drop monitor running in parallel (independent coroutine)
```

---

## Sensors & Data Sources

| Source | Rate | Used for |
|--------|------|----------|
| **Accelerometer** (`TYPE_ACCELEROMETER`) | ~50 Hz (`SENSOR_DELAY_GAME`) | Primary crash trigger + stillness confirmation |
| **Gyroscope** (`TYPE_GYROSCOPE`) | ~50 Hz | Gate between IMPACT → SILENCE_CHECK |
| **GPS/speed** (Karoo SDK) | Variable | Speed drop confirmation in all phases |

### Speed data notes
- The Karoo SDK always delivers speed in **m/s (SI units)**; the app converts internally to **km/h**.
- If GPS lock is lost (or you don't have ANT+ speed sensor) the SDK returns the **last known value** (not zero).
- If no GPS fix has ever been acquired (or not ANT+), speed defaults to `0.0 km/h` — protected by the **cold-start guard** (see below).

---

## Configuration Parameters

All parameters live in `KSafeConfig` and are user-configurable via the Settings screen.

| Parameter | Default | Description |
|-----------|---------|-------------|
| `crashDetectionEnabled` | `true` | Master enable switch |
| `crashSensitivity` | `MEDIUM` | Preset: LOW / MEDIUM / HIGH / CUSTOM |
| `minSpeedForCrashKmh` | `10` km/h | Minimum speed when impact is considered valid. `0` = always detect |
| `customCrashThreshold` | `45` m/s² | Impact threshold when sensitivity = CUSTOM (range 20–70) |
| `crashConfirmSpeedKmh` | `5` km/h (3 for LOW) | Max GPS speed to consider rider stopped during confirmation |
| `crashMonitorOutsideRide` | `false` | Keep detection active when no Karoo ride is running |
| `crashMonitorOutsideRideAnySpeed` | `false` | Force `minSpeed = 0` outside rides (⚠ more false positives) |
| `countdownSeconds` | `30` | Duration of the cancel window before alert is sent |

### Impact thresholds by sensitivity preset

The threshold is the **smoothed total acceleration vector magnitude** (m/s²). At rest, this is ~9.8 m/s² (1g). The thresholds below represent peak forces during a crash.

| Preset | Threshold | G equivalent | Typical use |
|--------|-----------|-------------|-------------|
| **LOW** | 55 m/s² | ~5.5g | MTB, gravel — hard terrain, many bumps |
| **MEDIUM** | 45 m/s² | ~4.5g | Mixed road + light gravel |
| **HIGH** | 35 m/s² | ~3.5g | Road bike — clean crashes, high sensitivity |
| **CUSTOM** | 20–70 m/s² | 2–7g | User-defined |

> **Reference (IEEE Accident Detection literature):**  
> Normal bumps / hard braking → up to ~1.5g (14.7 m/s²)  
> MTB jump landing → 3–5g but always followed by continued movement  
> Real crash → 4–7g followed by sustained stillness  
> Garmin's approach (similar): "large G spike + no movement afterwards"

### Impact confirmation window by preset

Maximum time from impact detection to crash confirmation. If the device never "settles" within this window, it is treated as a false alarm (e.g. a jump followed by continued riding).

| Preset | Window |
|--------|--------|
| **LOW** | **25 seconds** |
| **MEDIUM** | **20 seconds** |
| **HIGH** | **15 seconds** |
| **CUSTOM** | 20 seconds |

MTB/LOW gets a longer window because after a real crash on a slope, the bike may slide or tumble for several seconds before coming to rest.

### Confirm speed by preset

Maximum GPS speed (km/h) for the rider to be considered "stopped" during crash confirmation. Auto-set when changing preset.

| Preset | Confirm speed |
|--------|--------------|
| **LOW** | 3 km/h |
| **MEDIUM** | 5 km/h |
| **HIGH** | 5 km/h |
| **CUSTOM** | User-configured |

---

## Phase 1 — MONITORING (baseline state)

The accelerometer listener runs continuously at ~50 Hz. On every sensor event:

### Step 1: Sliding-window smoothing

```
magnitude = √(x² + y² + z²)   // total acceleration vector
```

The last **3 magnitude samples** (≈ 60ms at 50 Hz) are averaged into `smoothedMagnitude`.

**Why:** A single-sample spike from hitting a cobblestone, a curb, or a speed bump at speed can easily reach crash-level magnitudes for 1–2 frames. The 3-sample window requires the energy to be **sustained** across ~60ms, which genuine crash impacts are — and terrain micro-spikes are not.

### Step 2: Gate conditions (all must be true to advance to IMPACT)

| Condition | Value | Rationale |
|-----------|-------|-----------|
| `smoothedMagnitude > threshold` | preset-dependent | sustained impact energy |
| `currentSpeedKmh >= minSpeedForCrashKmh` | default 10 km/h | avoid triggering at rest / picking up bike |
| `now - lastCrashTime > 30s` | 30 second cooldown | no duplicate alerts during active countdown |

If all three pass → transition to **IMPACT**, record `impactTime = now`.

---

## Phase 2 — IMPACT (waiting for the situation to settle)

After an impact is detected, the system waits for the device to approach a resting state. This is the **primary discriminator between a real crash and a jump/bump that continues into normal riding**.

On every accelerometer sample, the system evaluates whether to enter SILENCE_CHECK:

### Conditions to advance to SILENCE_CHECK (all must be true)

| Condition | Threshold | Meaning |
|-----------|-----------|---------|
| `\|magnitude − 9.81\| < 4.0 m/s²` | `SILENCE_DEVIATION_MAX = 4.0` | Accelerometer is close to gravity → device lying still |
| `lastGyroMag < 2.0 rad/s` | `GYRO_MOVING_MAX = 2.0` | Gyroscope indicates device is not actively spinning / riding (~115°/s) |
| `timeSinceImpact > 500ms` | hardcoded | Minimum half-second after impact (prevents instant re-trigger) |
| `isSpeedDropConfirmed()` | preset-dependent | GPS speed confirms rider has slowed / stopped |

→ All four pass: transition to **SILENCE_CHECK**, record `silenceStartTime = now`.

### Timeout (false alarm path)

If the conditions above are **never all satisfied** within the impact window:

```
timeSinceImpact > impactWindowMs  →  resetState()  →  MONITORING
```

This handles the most common false alarm: **a jump or very hard bump**, where the large G-spike is followed by the rider continuing to ride (moving at speed, gyro still spinning). After 15–25 seconds without settling, the algorithm concludes it was not a crash.

### Why the gyroscope is used here but not later

The gyroscope gate (`lastGyroMag < 2.0 rad/s`) at this stage prevents entering SILENCE_CHECK while the rider is clearly still moving, turning, or pedaling hard. However, once in SILENCE_CHECK, the bike may continue sliding after a crash without the rider — requiring the gyroscope to be still would then cause a **false negative** (real crash never confirmed). See Phase 3.

---

## Phase 3 — SILENCE_CHECK (continuous stillness for 4.5 seconds)

Once the device has appeared to settle, the algorithm requires **uninterrupted stillness** for `SILENCE_DURATION_MS = 4,500ms` before confirming a crash.

### Stillness condition (`isStill`)

```kotlin
val deviation = abs(magnitude - GRAVITY)           // how far from resting gravity
val speedStillOk = isSpeedDropConfirmed()           // GPS speed below threshold
val isStill = deviation <= SILENCE_DEVIATION_MAX && speedStillOk
```

| Condition | Threshold | Meaning |
|-----------|-----------|---------|
| `\|magnitude − 9.81\| ≤ 4.0 m/s²` | `SILENCE_DEVIATION_MAX = 4.0` | Device lying still (gravity-aligned) |
| `isSpeedDropConfirmed()` | 3 or 5 km/h | GPS confirms rider is not moving |

**The gyroscope is intentionally NOT evaluated here.**

> **Why:** The Karoo is mounted on the handlebars. After a crash, the **rider** may be on the ground and completely still, but the **bike** can keep sliding or rolling — especially on a slope. The gyroscope on the Karoo would spin with the bike. If the algorithm required `gyro < threshold` during SILENCE_CHECK, any crash where the bike keeps moving would **never be confirmed** → dangerous false negative (no alert sent when the rider is actually injured). The GPS speed is the definitive "rider has stopped" discriminator at this stage.

### Outcomes on each accelerometer sample

| Scenario | Action |
|----------|--------|
| `isStill` AND `now - silenceStartTime >= 4,500ms` | **🚨 CRASH CONFIRMED** → fire alert |
| `isStill` AND silence timer not yet elapsed | No action — keep counting |
| `!isStill` AND `timeSinceImpact <= impactWindow × 2` | Reset `silenceStartTime = now` — stillness must be **continuous**, not cumulative |
| `!isStill` AND `timeSinceImpact > impactWindow × 2` | False alarm → `resetState()` → MONITORING |

The doubled timeout (`impactWindow × 2`) gives a generous total window: for LOW preset, up to **50 seconds** from impact before finally giving up. This covers extreme scenarios (bike rolling down a long slope) while still eventually expiring to prevent the algorithm from locking up indefinitely.

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
├─ minSpeedForCrashKmh == 0?
│   └─ return true  ← "detect at any speed" mode, skip speed gate
│
└─ return (currentSpeedKmh < crashConfirmSpeedKmh)
    ├─ LOW preset  → < 3.0 km/h
    └─ MEDIUM/HIGH → < 5.0 km/h
```

### Cold-start guard

**Problem:** When the app starts, `currentSpeedKmh` defaults to `0.0`. Without protection, `isSpeedDropConfirmed()` would immediately return `true`, and the algorithm could confirm a crash just from the phone being placed on the bike (vibration during mounting).

**Solution:** A `COLD_START_GUARD_MS = 8,000ms` window blocks speed-drop confirmation if no real GPS/speed data has been received yet.

**Behavior:**
- `speedDataReceived` starts as `false` on every `start()` call
- First `updateSpeed()` call: sets `speedDataReceived = true` → guard lifts **immediately** (even mid-window)
- If no speed data arrives within 8 seconds: guard **expires automatically** → algorithm continues normally (for devices with no GPS / no ANT+ speed sensor)
- `speedDataReceived` is **NOT reset** on `resetState()` — only on `start()`. This ensures a false-alarm reset during normal riding never re-introduces the 8-second vulnerability window.

---

## Post-crash Cooldown

```
CRASH_COOLDOWN_MS = 30,000ms
```

After a crash is confirmed and `onCrashDetected()` is called, the MONITORING phase ignores new impact spikes for 30 seconds. This prevents:
- Duplicate alerts while the rider is still on the ground moving slightly
- Multiple alerts while the 30-second cancel countdown is running

---

## Speed-Drop Detection (independent system)

A completely separate mechanism that runs as a background coroutine, checking every 30 seconds:

```
Every 30s:
  if (speedDropStartTime > 0):
    stoppedFor = (now - speedDropStartTime) / 60,000
    if stoppedFor >= config.speedDropMinutes (default 5 min):
      → onCrashDetected()
```

**Triggering:** `updateSpeed()` starts the `speedDropStartTime` timer when `speedKmh < SPEED_THRESHOLD_KMH (5 km/h)`. Timer resets when speed rises above 5 km/h again.

**Pause reset:** When the Karoo ride is paused, `resetSpeedDropOnPause()` clears the timer — prevents false alarms at cafés, red lights, or mechanical stops.

**Use case:** Captures the scenario where the rider falls and is unconscious at low speed (e.g. medical episode while climbing), where the accelerometer impact may be gentle but the GPS speed goes to zero and stays there.

---

## False Alarm Mitigation Summary

| Scenario | Mitigation mechanism |
|----------|---------------------|
| Cobblestone / speed bump | Sliding-window average (3 frames ≈ 60ms) |
| MTB jump landing (rider continues) | Impact window timeout (15–25s of no settling) |
| Hard braking | `minSpeedForCrash` gate + acceleration below threshold |
| Picking up the stationary bike | `minSpeedForCrash` gate (must be moving first) |
| App startup / cold start | Cold-start guard (8s until first speed data) |
| Slow climbing bump | GPS speed gate in SILENCE_CHECK |
| Double alert after confirmed crash | 30-second post-crash cooldown |
| Paused at café / traffic light | Speed-drop timer reset on ride pause |
| Bike keeps sliding without rider | Gyroscope removed from SILENCE_CHECK (only GPS counts) |

---

## State Machine Diagram

```
                       ┌─────────────────────────────────────────┐
                       │               MONITORING                 │
                       │  • Running at ~50 Hz                    │
                       │  • Watching for smoothedMag > threshold │
                       │  • Speed ≥ minSpeedForCrash             │
                       │  • Cooldown elapsed (30s)               │
                       └───────────────┬─────────────────────────┘
                                       │ sustained G spike
                                       ▼
                       ┌─────────────────────────────────────────┐
                       │                 IMPACT                   │
                       │  • Clock starts (impactTime)            │
                       │  • Waiting for device to settle         │
                       │  • Gate: accel≈gravity                  │◄── timeout (15–25s) ──► MONITORING
                       │          gyro < 2.0 rad/s               │
                       │          GPS < confirmSpeed             │
                       │          > 500ms elapsed                │
                       └───────────────┬─────────────────────────┘
                                       │ all gates pass
                                       ▼
                       ┌─────────────────────────────────────────┐
                       │             SILENCE_CHECK                │
                       │  • silenceStartTime = now               │
                       │  • isStill = accel≈gravity              │◄── NOT still ──► reset timer
                       │             AND GPS < confirmSpeed      │                    (or timeout × 2
                       │  • Must stay still for 4,500ms          │                     → MONITORING)
                       └───────────────┬─────────────────────────┘
                                       │ 4.5s continuous stillness
                                       ▼
                              🚨 CRASH CONFIRMED
                         (30s cancel countdown begins)
```

---

## Timing Summary

| Parameter | Value |
|-----------|-------|
| Sensor sampling rate | ~50 Hz |
| Smoothing window | 3 samples ≈ 60ms |
| Minimum time from impact to SILENCE entry | 500ms |
| IMPACT timeout (LOW) | 25,000ms |
| IMPACT timeout (MEDIUM) | 20,000ms |
| IMPACT timeout (HIGH) | 15,000ms |
| SILENCE_CHECK required duration | 4,500ms |
| Max total time to confirm (LOW) | ~29.5s (25s window + 4.5s silence) |
| Max total time to confirm (HIGH) | ~19.5s (15s window + 4.5s silence) |
| Hard abort (SILENCE_CHECK, LOW) | 50,000ms (2× impact window) |
| Post-crash cooldown | 30,000ms |
| Cold-start guard | 8,000ms |
| Speed-drop check interval | 30,000ms |
| Speed-drop confirmation | configurable (default 5 min) |

---

*Document auto-generated from source code. For the canonical implementation, refer to `CrashDetectionManager.kt`.*

