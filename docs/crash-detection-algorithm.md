# KSafe — Crash Detection Algorithm

> **Version:** May 2026 (revision 7 — IMPACT-phase on-side relaxation)
> **File:** `CrashDetectionManager.kt`
> **Sensors:** Android SensorManager (accelerometer + gyroscope) + Karoo SDK (speed, cadence, grade)

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
| **GPS/speed** (Karoo SDK `TYPE_SPEED_ID`) | Variable | Speed drop confirmation in all phases |
| **Cadence** (Karoo SDK `TYPE_CADENCE_ID`) | Variable (~1 Hz) | **False-positive gate in SILENCE_CHECK**: if cadence > 20 RPM the rider is still pedalling → immediate false-alarm exit. Optional — algorithm falls back gracefully when no cadence sensor is paired. Two guards refine the gate: (1) **freshness-by-change** (`isCadenceActive`) — the gate only fires when the cadence value has actually *fluctuated* recently (≤ 10 s since the last bit-exact change, and at least one genuine change observed since session start); a sensor repeating its last value after signal loss, or one that never changed since connecting, is treated as stuck and ignored. (2) **On-side suppression** (`lastCadenceGateSuppressed`, 2026-05-25 FN fix) — when the live orientation angle vs the pre-impact reference is ≥ 45° (decisively non-upright), CAD_GATE is suppressed: a bike on its side cannot be pedalled, so a "fresh" post-impact cadence reading is phantom (SDK echo or impact-induced revolutions). Each suppression emits a rate-limited `CAD_GATE_SUPPRESSED` calibration row. |
| **Road grade** (Karoo SDK `TYPE_ELEVATION_GRADE_ID`) | Variable (~1 Hz) | **Proactive descent boost**: the peak-impact threshold is raised on descents (grade < −4 %) to suppress terrain-noise spikes before the reactive TERRAIN_CLUSTER mechanism fires. |

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

### Per-profile crash overrides

Each Karoo ride profile (Road / Gravel / MTB / custom) can carry its own crash config, auto-learned when KSafe first sees the profile become active. Controlled by `KSafeConfig.crashProfileSettings` (`List<CrashProfileSetting>`, default empty).

> **Discovery and timing (user-facing).** A profile only appears in the Safety-tab list **after you have started a ride with it** — the ride-profile stream is what triggers the auto-learn, and it is only active during a ride (unless "monitor outside ride" is on). The override you save is applied to the live detector at the **start of the next ride** with that profile, so configure it before the ride you want it to take effect on. In the UI each profile card collapses to a one-line summary (Global / Custom · level / Off) to keep a long list manageable.

Resolution (`CrashProfileResolver.resolveEffectiveCrashConfig`): (1) empty list → global config for all profiles; (2) matching entry (by `RideProfile.id`) with `useGlobal=true` → global unchanged; (3) `useGlobal=false` → the per-profile `crashSensitivity / customCrashThreshold / minSpeedForCrashKmh / crashConfirmSpeedKmh` FULLY replace the global ones (all-or-nothing; per-profile `crashDetectionEnabled` is AND-ed with the global kill-switch — it can only further disable). (4) Auto-learn: each profile switch calls `learnProfile` — new id → appends a `useGlobal=true` stub; rename → updates the stored name; stale same-name/different-id orphans pruned. (5) Applied to the live detector immediately on profile switch via `reapplyEffectiveCrash`.

The per-profile feature does NOT change any global preset thresholds, impact windows, or silence constants — it only routes a different `KSafeConfig` into the same lookup maps.

### Impact thresholds by sensitivity preset (smoothed magnitude)

The **smoothed** threshold is the 3-sample moving average of total acceleration vector magnitude. At rest this baseline is ~9.8 m/s² (1g).

| Preset | Smoothed threshold | G equivalent | Typical use |
|--------|-------------------|-------------|-------------|
| **LOW** | 55 m/s² | ~5.5g | MTB, gravel — hard terrain, many bumps |
| **MEDIUM** | 45 m/s² | ~4.5g | Mixed road + light gravel |
| **HIGH** | 35 m/s² | ~3.5g | Road bike — clean crashes, high sensitivity |
| **CUSTOM** | 20–70 m/s² | 2–7g | User-defined |

### Peak thresholds by sensitivity preset (single sample)

The **peak** detector fires on a single raw sample exceeding this bar, without the 3-sample smoothing. It captures sharp, rigid impacts that last only 10–20ms (one frame at 50 Hz).

| Preset | Peak threshold | G equivalent | Calibration basis |
|--------|---------------|-------------|------------------|
| **LOW** | **60 m/s²** | ~6g | Real ride logs: normal MTB riding tops at ~67–69 m/s²; pthr=60 adds 2–3 extra IMPACT cycles/session (all IMPACT_TMO, none reach CRASH_OK) |
| **MEDIUM** | **50 m/s²** | ~5g | Real ride logs: normal riding peaked at ~46 m/s² in MEDIUM; pthr=50 has zero overlap with observed noise distribution |
| **HIGH** | **40 m/s²** | ~4g | Consistent thr+5 principle; road riding rarely generates isolated spikes above 35 m/s² |
| **CUSTOM** | 1.3 × `customCrashThreshold`, capped at 80 m/s² | varies | — |

> **Calibration note (revision 3):** previous peak thresholds (70/60/50 m/s²) created a 15 m/s² blind-spot window per preset where a single-frame crash spike between `smoothedThreshold` and `peakThreshold` was invisible to both detectors. The new values use `pthr = smoothedThr + 5` as a uniform principle, closing that gap. Validated against 83 minutes of real MTB ride data (v1 log) and a separate road/climb session (v2 log).

> **Why two detectors:** the smoothed path rejects single-sample noise from cobblestones, dirt-to-asphalt transitions or speed bumps. But a real rigid impact (handlebar against asphalt, direct collision with an obstacle) can produce a peak that lasts only 10–20ms — a single sample at 50 Hz. Smoothing alone would dilute a 70 m/s² peak with two surrounding 15 m/s² samples down to ~33 m/s², below the MEDIUM/HIGH smoothed thresholds, causing a silent false negative. The peak path catches these short events.

> **Peak-threshold deliberation (not applied):** a candidate change considered widening the peak thresholds to 68/56/45 (LOW/MED/HIGH) after FP reports. Subsequent commit-history review attributed those reports to the v1.2.0-era gyro entry path (a third entry branch `gyro > 6 rad/s` that triggered on hard cornering / shoulder checks). That branch was removed in commit `ab69a40` before v2.0.0 shipped, so v2.0+ riders are not exposed to the FP pattern those reports described. The peak-threshold widening was reverted; future tuning will be data-driven from v2.0+ FP logs (now that the calibration logger reliably preserves them across crashes).

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
val deviation             = abs(magnitude - GRAVITY)
val accelOk               = deviation <= effectiveDeviationMax
val effectiveSilenceMs    = computeEffectiveSilenceMs(gpsStale)   // see below; may latch
val onSideRelaxed         = lockedEffectiveSilenceMs > 0 &&
                            lastOrientationAngleDeg >= onSideRelaxationAngleDeg   // 60°, R7-B
val isStill               = if (onSideRelaxed) accelOk
                            else accelOk && isSpeedDropConfirmed()
```

The `onSideRelaxed` carve-out (R7-B): once the silence-window latch has decided AND the latched orientation angle is ≥ `onSideRelaxationAngleDeg` (60° — decisively on the ground), the speed-drop gate is dropped and `isStill = accelOk` alone, **at any speed** — a bike on its side cannot be ridden, so a speed rise is the bike rolling, not the rider riding. There is no speed ceiling on this carve-out (the 25 km/h ceiling applies only at the IMPACT-phase gate — see the on-side relaxation sections below).

| Mode | Deviation max | Required stillness | Notes |
|------|--------------|-------------------|-------|
| GPS fresh (normal) | 4.0 m/s² | 4,500 / 20,000 ms | Duration chosen by `computeEffectiveSilenceMs` (see orientation section) |
| GPS stale (>10s without a value change) | 1.5 m/s² | 8,000 / 20,000 ms | Hardened deviation; gap/orientation regimes still apply |

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
| `!isStill` AND `now - silenceCheckEnteredTime <= impactWindow × 2` | Reset `silenceStartTime = now` — stillness must be **continuous**, not cumulative |
| `!isStill` AND `now - silenceCheckEnteredTime > impactWindow × 2` | False alarm → `resetState()` → MONITORING |

The doubled timeout (`impactWindow × 2`) is the **retry budget for achieving stillness** and is measured **from the moment SILENCE_CHECK was entered** (`silenceCheckEnteredTime`), not from the impact. This matters because the effective silence window can be as long as 20 s (gap regime, upright orientation regime): if the budget were measured from the impact, a late SILENCE_CHECK entry (large impact→stillness gap) would leave insufficient budget for even one 20 s window — and a single non-still sample from a dazed/injured rider twitching, or wind rocking the bike, would drop a genuine crash to MONITORING with no alert. Anchoring the budget to SILENCE_CHECK entry avoids that false-negative: a continuously-still rider always reaches the confirm branch (the give-up condition only fires on non-still samples). The budget bounds how long the machine keeps retrying to achieve stillness — it does not guarantee tolerance of arbitrarily late stillness breaks. For the LOW preset this gives up to **50 seconds** of retries from SILENCE_CHECK entry before finally giving up.

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
├─ GPS stale? (speedLastChangeMs > 0 AND now - speedLastChangeMs > 10,000ms)
│   speedLastChangeMs is stamped only when the speed VALUE actually changes —
│   not on every emission — plus an explicit-zero whitelist (a rider stopped at
│   lights re-emits 0.0 bit-exact and still counts as fresh) and a first-emission
│   bootstrap. The SDK replays the last known speed bit-exact when GPS lock is
│   lost, so a stretch of identical non-zero emissions is the staleness tell-tale
│   that the old emission-stamped detector could never trip on. The flag is
│   computed in the facade (CrashDetectionManager.isGpsStale) and pushed into the
│   state machine on transition (setSpeedGpsStale).
│   └─ return true  ← bypass speed gate, accel takes over with HARDENED thresholds
│      ⚠ Caller (SILENCE_CHECK) is expected to switch to GPS_STALE_DEVIATION_MAX
│        and GPS_STALE_SILENCE_DURATION_MS to compensate.
│
└─ return (crashConfirmSpeedKmh == 0  OR  currentSpeedKmh < crashConfirmSpeedKmh)
    ├─ LOW preset  → < 3.0 km/h
    └─ MEDIUM/HIGH → < 5.0 km/h
    └─ crashConfirmSpeedKmh = 0 → gate disabled explicitly (not the same as minSpeedForCrashKmh = 0)
```

> **Important (revision 3):** `crashConfirmSpeedKmh` is now **independent** of `minSpeedForCrashKmh`.
> Previously, setting `minSpeedForCrashKmh = 0` (test mode: detect at any speed) also bypassed
> the CRASH_OK confirmation speed gate, allowing a false positive when cycling slowly uphill
> with `minSpeed=0`: between pedal strokes, accel deviation can drop below 4.0 m/s² for >4.5s
> while speed is still ~6–7 km/h. The fix: only `crashConfirmSpeedKmh = 0` disables the
> confirmation gate. `minSpeedForCrashKmh = 0` only disables the IMPACT entrance gate.

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

## Moving-Vigilance Trust Gate

When an on-side SILENCE_CHECK confirm fires, the facade checks one extra condition before routing it to the normal cancellable countdown:

**Was the silence-window orientation sampled at rest?**

The orientation angle is the angle between the pre-impact reference gravity vector and the averaged in-silence gravity vector. That angle is only meaningful if the in-silence vector really points along gravity — i.e. the bike was genuinely resting when the samples were collected. The vector magnitude should be ≈ 9.81 m/s² (1g). Field false positives have shown in-silence magnitudes as low as 8.49 and 1.78 m/s² — both sampled mid-motion, where the centripetal and dynamic force components move the vector away from gravity and the computed angle is untrustworthy.

### Trust gate (`onSideTrustMinAccel = 8.83 m/s²`)

```kotlin
val mag = sqrt(silOrientX² + silOrientY² + silOrientZ²)
val readInMotion = (mag < onSideTrustMinAccel)   // 8.83 ≈ 0.90 × GRAVITY
```

If `readInMotion = true` the confirm is NOT fired immediately. Instead, `movingVigilance.arm(now)` is called and the confirm becomes a **pending verification**.

If `readInMotion = false` (magnitude close to gravity → bike was at rest → angle is trustworthy) the confirm is routed directly to `confirmCrash` as before.

If the silence-window orientation is `NaN` (too few samples — cold start, brief pause) the motion check returns `false` (cannot assess → confirm as today — FN-safe by construction).

### The verification window

Once armed, `MovingVigilance.onTick(now, speedKmh, speedFresh)` is called on every subsequent sensor sample (~50 Hz). It resolves to one of three outcomes:

| Outcome | Condition | Action |
|---------|-----------|--------|
| `CLEAR` | `speedFresh && speedKmh ≥ movingVigilanceSpeedKmh` for the full `movingVigilanceWindowMs` (4 000 ms) | Log `VIGIL_CLEAR`. Disarm. No alert. Rider was continuously, freshly riding at ≥ 8 km/h → the earlier accel pattern was a riding FP. |
| `ESCALATE` | `!speedFresh` OR `speedKmh < movingVigilanceSpeedKmh` at ANY sample before the window closes | Log `VIGIL_ESCALATE`. Disarm. Route to `confirmCrash` — the same cancellable countdown as a normal detection. |
| `PENDING` | Window has not yet closed and every sample so far was fast+fresh | Continue watching. |

**FN-safe by construction**: the only path that suppresses an alert is `CLEAR`, which requires every single sensor sample in a 4 s window to show the rider is actively moving above 8 km/h with fresh GPS. Any doubt (one slow sample, one stale GPS tick, a GPS drop, or any pause) escalates. The accelerometer is NOT consulted to clear — it is the source of the untrustworthy reading; consulting it again would be circular.

### Escalate-on-abandon (pause while armed)

If a Karoo ride pause arrives while `movingVigilance.isArmed`, the vigilance window is NEVER silently dropped. Manual pause and autopause are not reliably distinguishable, and a downed rider cannot be assumed conscious — so `confirmCrash` is called before `movingVigilance.reset()`. Log event: `VIGIL_ESCALATE` with `reason=manual_pause` (autopause does not reach `onPause`'s manual branch; only the manual branch adds this escalation).

### Calibration events

| Event tag | When |
|-----------|------|
| `VIGIL_ARM` | Arm: trust gate tripped. Fields: `sil_mag`, `trust_min`, `speed`, `window_ms`. |
| `VIGIL_CLEAR` | Clear: rider sustained ≥ 8 km/h fresh for the full window. Fields: `speed`, `window_ms`. |
| `VIGIL_ESCALATE` | Escalate to countdown. Fields: `speed`, `gps_stale` (on tick path) or `reason=manual_pause` (on pause path). |

### Tuning knobs (`Thresholds.kt`)

| Field | Default | Purpose |
|-------|---------|---------|
| `onSideTrustMinAccel` | 8.83 m/s² | Minimum silence-orientation magnitude to trust the angle. Below this the sample was mid-motion. ≈ 0.90 × GRAVITY; calibrated against observed FP magnitudes (8.49, 1.78 m/s²) and left above them with margin. |
| `movingVigilanceWindowMs` | 4 000 ms | Duration that speed must hold above the floor to clear. Long enough to exclude a brief burst of speed during a crash sequence; short enough not to delay a real-crash escalation noticeably. |
| `movingVigilanceSpeedKmh` | 8.0 km/h | "Clearly riding" speed floor. A rider stopped at a red light is < 1 km/h; a downed rider's bike rolling gently is < 5 km/h; 8 km/h clears walking-pace ambiguity. |

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

- `onSpeedUpdate()` starts the zero-speed window when `effective speed < SPEED_DROP_WINDOW_KMH (3.5 km/h)`. The threshold sits in the valley between consumer-GPS jitter on a stationary bike (typically <2 km/h, worst-case multipath ~3 km/h) and slow hike-a-bike (median ~4 km/h). With `gpsStale=true` (SDK replaying the last known speed because GPS lock is lost in a tunnel/dense forest), the effective speed is forced to 0 so the window opens regardless of the replayed value.
- The window closes when speed rises back above the threshold.
- Time accounting uses `Clock.monotonicMs()` (Android `SystemClock.elapsedRealtime`) so an NTP step or user-driven date change can't shift the 5-minute deadline.
- `onPause()` clears the window when the Karoo ride is paused — prevents alarms at cafés, red lights, mechanical stops.

#### Calibration telemetry

Two events let post-ride analysis tune the threshold from real-world data:

| Event tag | When | Fields |
|---|---|---|
| `SPDRP_WSTART` | Window opens (speed crosses below 3.5 km/h) | `trigger_speed_kmh`, `gps_stale`, `threshold_kmh` |
| `SPDRP_WCLOSE` | Window closes (speed recovers / paused / stopped / confirmed) | `reason` (`speed_recovered` \| `paused` \| `stopped` \| `confirmed`), `elapsed_ms`, `trigger_speed_kmh`, `max_speed_kmh`, `gps_stale`, `recovered_at_kmh` (only on `speed_recovered`) |

`max_speed_kmh` is the peak speed observed inside the window. A histogram across many rides separates "GPS-jitter while at rest" (`max_speed_kmh < 1`) from "near-threshold hike-a-bike" (`max_speed_kmh > 3`) so the threshold can be moved down to 3.0 km/h if telemetry justifies it.

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
| Rough terrain single-frame spikes on descents | Grade-aware proactive peak boost (+2/+5/+8 m/s² at −4/−7/−10% grade) |
| Rider still pedalling during crash confirmation | Cadence gate: >20 RPM in SILENCE_CHECK → instant false-alarm exit (only when the value is fresh-by-change — stuck sensors ignored — and the bike is not decisively on its side; see the Sensors table) |
| Bump+brake+stop (gravel / off-road) | Gap regime: impact→stop gap > 8 s → 20 s silence window; rider who rides on after the bump does not confirm |
| Upright stop after impact (traffic light, check) | Orientation regime: bike still upright vs pre-impact reference → 20 s window |
| Real crash, bike rolls past auto-pause (auto-resume residual) | IMPACT-phase + SILENCE_CHECK on-side relaxation (revision 7): decisive on-side evidence (angle ≥ 60°, ≥ 25 samples in IMPACT or latched in SILENCE_CHECK) bypasses the speed gate so a rolling bike does not block confirmation |

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
                       │  • Normal:  dev ≤ 4.0 + GPS<thr         │◄── NOT still ──► reset timer
                       │  • Stale:   dev ≤ 1.5                   │                    (or timeout × 2
                       │  • Duration: 4.5s / 8.0s / 20s          │                     → MONITORING)
                       │    (gap + orientation regimes)           │
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
| Peak thresholds (single sample) | **40 / 50 / 60 m/s²** (HIGH / MEDIUM / LOW) — *revised in rev 3* |
| Minimum time from impact to SILENCE entry | 500ms |
| IMPACT timeout (LOW) | 25,000ms |
| IMPACT timeout (MEDIUM) | 20,000ms |
| IMPACT timeout (HIGH) | 15,000ms |
| SILENCE_CHECK required duration — on-side / invalid ref (GPS fresh) | 4,500ms |
| SILENCE_CHECK required duration — upright or delayed stop (GPS fresh) | 20,000ms |
| SILENCE_CHECK required duration — on-side / invalid ref (GPS stale) | 8,000ms |
| SILENCE_CHECK required duration — upright or delayed stop (GPS stale) | 20,000ms |
| Delayed-stop gap threshold (`delayedStopGapMs`) | 8,000ms |
| Upright angle threshold (`uprightAngleThresholdDegrees`) | 45° |
| GAP-regime upright veto cone (`gapVetoUprightAngleDeg`, R6-F) | 25° |
| Prompt-stop upright veto gyro gate (`nonGapUprightVetoMaxGyroRadS`, R6-G) | 3.0 rad/s |
| Pre-impact reference window (`WINDOW_MS`) | 2,000ms |
| Pre-impact reference guard before impact (`GUARD_MS`) | 250ms |
| SILENCE_CHECK deviation max (GPS fresh) | 4.0 m/s² |
| SILENCE_CHECK deviation max (GPS stale) | 1.5 m/s² |
| Hard abort (SILENCE_CHECK, LOW) | 50,000ms (2× impact window) |
| Post-crash cooldown | `countdown + 30,000ms` (60s with default config) |
| Cold-start guard | 8,000ms |
| GPS stale threshold | 10,000ms |
| Speed-drop check interval | 30,000ms |
| Speed-drop confirmation duration | configurable (default 5 min) |
| Speed-drop stable-stillness requirement | 60,000ms |
| Moving-vigilance trust gate (`onSideTrustMinAccel`) | 8.83 m/s² (≈ 0.90 × gravity) |
| Moving-vigilance window (`movingVigilanceWindowMs`) | 4,000ms |
| Moving-vigilance riding-speed floor (`movingVigilanceSpeedKmh`) | 8.0 km/h |

---

## Threading & Concurrency Notes

Since the refactor, the algorithm is split across five collaborating classes:

- **`CrashDetectionManager`** — the facade. Owns config, cooldown, threshold rebuilds, calibration logging, and wires the rest together.
- **`CrashStateMachine`** — the pure MONITORING → IMPACT → SILENCE_CHECK state machine (no Android imports, no coroutines, no I/O).
- **`SensorReader`** — `SensorEventListener` registration, the smoothing/variance/pre-impact ring buffers, and the stable-stillness tracker.
- **`SpeedDropMonitor`** — the independent zero-speed watchdog coroutine.
- **`Thresholds`** — immutable data class of tunables, swapped atomically through a `@Volatile` reference (a partially-written value is impossible).

Threads in play:

- **Sensor callbacks** are registered with `handler = null`, so Android delivers them on the **main looper**. Together with the Karoo SDK flows (`updateSpeed()`, `updateCadence()`, ride-state callbacks) collected on `Dispatchers.Main` and the lifecycle methods (`start()`, `stop()`, `updateConfig()`), this means **all state-machine access is main-thread-confined**.
- **The speed-drop coroutine on `scope`** polls every 30 s and reads cross-thread state (`accelStillSinceMs`, window timestamps) through `@Volatile` fields.

Shared mutable fields are marked `@Volatile` for cross-thread visibility and atomicity of Long/Double reads. One lock exists: the rolling TMO-cluster deque is accessed under `synchronized(recentTmoTimestamps)` — the read-modify-write in the IMPACT_TIMEOUT branch vs `clear()` in the start/resume/stop lifecycle methods — so the cluster bookkeeping cannot be torn. The pre-impact vector ring needs no lock: the sensor thread is its sole mutator, and pause-time invalidation only raises a `@Volatile` floor timestamp (see the lifecycle section).

Everything else tolerates slightly stale reads across threads (e.g. an accelerometer event firing immediately after a speed update may evaluate against the previous speed value), which is acceptable given the 50 Hz sample rate and the multi-sample confirmation pipeline.

---

## Change Log (vs. previous revision)

### Revision 8 — June 2026 (moving-vigilance trust gate + escalate-on-abandon)

| ID | Change | Status |
|----|--------|--------|
| **R8-A** | **Moving-vigilance trust gate (`onSideTrustMinAccel = 8.83 m/s²`).** An on-side SILENCE_CHECK confirm is only fired immediately when the averaged in-silence acceleration magnitude is close to gravity (‖sil‖ ≥ 8.83 m/s²), indicating the orientation angle was sampled at rest. Field FPs showed magnitudes of 8.49 and 1.78 m/s² — sampled mid-motion, making the computed angle untrustworthy. Below the floor, the confirm is diverted into the moving-vigilance window (R8-B) rather than directly firing. If the orientation samples are `NaN` (too few) the check returns false and the confirm proceeds as today — cannot assess → FN-safe default. | ✅ Implemented (`CrashDetectionManager.confirmReadInMotion`, `Thresholds.onSideTrustMinAccel`) |
| **R8-B** | **Moving-vigilance window (`movingVigilanceWindowMs = 4 000 ms`, `movingVigilanceSpeedKmh = 8.0 km/h`).** When the trust gate trips, `MovingVigilance.arm(now)` is called. On every subsequent sensor sample (~50 Hz), `onTick` checks: if speed is fresh AND ≥ 8 km/h for the full 4 s window → `CLEAR` (log `VIGIL_CLEAR`, no alert — rider was continuously riding, reading was a FP); if any sample has stale GPS OR speed < 8 km/h → `ESCALATE` (log `VIGIL_ESCALATE`, route to the normal cancellable countdown). The accelerometer is NOT consulted for the clear decision — it is the source of the untrustworthy reading. Calibration events: `VIGIL_ARM` / `VIGIL_CLEAR` / `VIGIL_ESCALATE`. | ✅ Implemented (`MovingVigilance.kt`) |
| **R8-C** | **Escalate-on-abandon: armed vigilance window on pause.** When a Karoo ride pause arrives while `movingVigilance.isArmed`, the window is never silently dropped. Manual pause and autopause are not reliably distinguishable and a downed rider cannot be assumed conscious, so `confirmCrash(IMPACT_CONFIRMED, alreadyLogged=true)` is called before `movingVigilance.reset()`. Log event: `VIGIL_ESCALATE` with `reason=manual_pause`. Autopause does not reach the manual branch of `onPause` and is unaffected (the in-flight state machine is preserved and the vigilance window continues ticking). | ✅ Implemented (`CrashDetectionManager.onPause`) |

### Revision 7 — May 2026 (auto-resume residual + IMPACT-phase on-side relaxation)

| ID | Change | Status |
|----|--------|--------|
| **R7-A** | **I3: preserve mid-IMPACT / mid-SILENCE_CHECK across auto-pause + auto-resume.** Auto-pause no longer wipes the state machine; only an explicit manual pause does. A real crash whose bike rolls long enough to trip Karoo autopause now continues its in-flight detection on resume instead of dropping back to MONITORING. | ✅ Implemented |
| **R7-B** | **SILENCE_CHECK on-side speed-rise relaxation.** Once the orientation latch fires on-side (angle ≥ `onSideRelaxationAngleDeg = 60°`), `isStill` ignores the speed-drop gate inside SILENCE_CHECK — `isStill = accelOk` alone, **at any speed** while GPS data is fresh. The `onSideRelaxationMaxSpeedKmh = 25 km/h` ceiling applies ONLY at the IMPACT → SILENCE_CHECK gate (R7-C / `handleImpact`), not here. The residual asymmetry is deliberate and FN-safe: the latch already required decisive on-side evidence to engage, and re-imposing a speed ceiling mid-window would drop a genuine crash whose escaped bike accelerates downhill. Closes the residual where the bike rolls after the rider goes down. CR2 preserves the on-side latch across within-budget silence breaks for the same reason. | ✅ Implemented |
| **R7-C** | **IMPACT-phase on-side relaxation.** `handleImpact`'s IMPACT → SILENCE_CHECK gate becomes `accelOk && gyroOk && timeOk && (speedDropOk || onSideRelaxed)`, where `onSideRelaxed = orientationSampleCount >= 25 && angle ≥ 60° && (lastSpeedKmh < onSideRelaxationMaxSpeedKmh || gpsStale)` — the 25 km/h ceiling lives at this gate only. Allows the transition when the bike has been decisively flat for ~500 ms even with speed still up — closes the autoresume-mid-IMPACT residual. New companion-object constant `IMPACT_RELAXATION_MIN_SAMPLES = 25`; new `Thresholds` field `onSideRelaxationAngleDeg = 60.0`. The orientation accumulator (`orientationSum*` / `orientationSampleCount`, renamed from `silenceWindow*`) now fills during IMPACT (gated on `accelOk`) in addition to SILENCE_CHECK. The transition carries the accumulator forward on the on-side relaxation path so the SILENCE_CHECK latch is reachable while the bike is still rolling. | ✅ Implemented |
| **R7-D** | **CR1: IMPACT-timeout accumulator reset.** On `timeSinceImpact > impactWindowMs`, the accumulator is wiped along with the timers. Without this, the next impact's `currentOrientationAngleDeg()` would compute against stale X/Y/Z averages bound to the previous pre-impact reference, and the IMPACT relaxation could fire with as few as ~5 fresh samples. | ✅ Implemented |
| **R7-E** | **MedicalEpisode: FLATLINE suppression on active pedalling/power.** FLATLINE is not raised when cadence > threshold or power > threshold — a flat-HR rider who is still actively pedalling is a sensor-loss case, not a medical event. | ✅ Implemented (MedicalEpisodeDetector) |
| **R7-F** | **Actions debounce.** SOS arming and webhook / custom-message taps are debounced to prevent double-tap self-cancel and double-fire on the field-tap broadcast path. | ✅ Implemented (FieldTapReceiver / EmergencyManager) |
| **R7-G** | **Perf: route `gpsStale` via state-machine field.** `lastSpeedGpsStale` set by `setSpeedGpsStale` instead of carried on each `SensorSample`; eliminates the per-tick `sample.copy()` that allocated ~14 MB/h during GPS-stale stretches. | ✅ Implemented |

### Revision 6 — May 2026 (pre-impact orientation reference)

| ID | Change | Status |
|----|--------|--------|
| **R6-A** | **Replaced learned-baseline orientation with pre-impact orientation reference.** `SensorReader` now keeps a ~150-entry ring buffer of timestamped accelerometer vectors. On every `Decision.EnterImpact`, the facade averages the slice `[impactTs − 250 ms − 2 000 ms, impactTs − 250 ms]` into a `PreImpactRef(x, y, z, valid)` and injects it into `CrashStateMachine.setPreImpactReference`. No speed/noise learning gates — available from the first 2 s of any ride and on any terrain. | ✅ Implemented |
| **R6-B** | **Gap regime in `computeEffectiveSilenceMs`.** If `firstSilenceGapMs > delayedStopGapMs (8 s)` the stop is classified as delayed (rider kept riding after the impact) and the 20 s window is required (window choice needs no orientation; **R6-F** later adds an orientation veto at the confirm gate). Fixes the gravel bump+brake+stop FP whose observed gap was 17 s. | ✅ Implemented |
| **R6-C** | **Orientation regime for prompt stops.** If the gap is ≤ 8 s: angle ≥ 45° → on-side → 4.5 s (fast alert); angle < 45° → still upright → 20 s (wait); invalid reference → 4.5 s (conservative). `delayedStopGapMs = 8 000 ms` added to `Thresholds`. | ✅ Implemented |
| **R6-D** | **Removed learned-baseline machinery.** `feedBaselineSample`, `isBaselineReady`, `baselineVector`, the EMA cap logic, the cruising-speed/std-dev learning gate in the facade, and the `ORIENTATION_BASELINE` / `ORIENT_BASE` calibration event are all deleted. `baselineMinSamples` and `baselineCruisingMinSpeedKmh` removed from `Thresholds`. | ✅ Removed |
| **R6-E** | **Calibration log fields updated.** `SILENCE_ENTER` gains `gap_ms`, `pre_valid`, `pre_x/y/z`. `CRASH_CONFIRMED` gains `gap_ms`, `pre_impact_angle`, `decided_by` (`GAP` / `ORIENT_UPRIGHT` / `ORIENT_ONSIDE` / `UNKNOWN`). `IMPACT_TIMEOUT` gains `pre_valid`. `ORIENT_BASE` event removed. | ✅ Implemented |
| **R6-F** | **GAP-regime upright veto.** The gap regime (R6-B) was the only confirm path that ignored orientation — it confirmed on 20 s of stillness alone. A real-world FP (gravel bump → coast to a stop, gap 9.9 s → stand motionless and **upright** 20 s) confirmed as a crash. At the confirm gate, when `firstSilenceGapMs > delayedStopGapMs`, the silence-window orientation angle is now computed: if `0 ≤ angle < gapVetoUprightAngleDeg` the confirm is **vetoed** and the machine returns to MONITORING. The veto cone is a **dedicated, tight 15°** — NOT the 45° `uprightAngleThresholdDegrees` (which is a *timing* threshold where both sides still confirm). A veto suppresses an SOS and a false negative is far worse than a false positive, so the veto only engages when the bike is almost identical to its riding orientation. The safety rationale: a bike cannot hold ≤15°-from-upright **and** stay perfectly still for 20 s without a conscious rider balancing it (an unsupported bike falls over in 1–2 s; a crash victim's bike ends on-side or is displaced > 15°), so the trigger condition is itself strong evidence of a non-crash. A bike merely tilted to 15–45° is left to confirm. Non-upright (`angle ≥ 15°`) and not-computable (`-1.0`: invalid ref / too few samples) still confirm — the on-side paths and the no-orientation-data safety net are untouched. The veto reads the LIVE silence accumulator, which on the CR3 IMPACT-relax→gap path is dominated by on-side samples (≥60°), so a genuine on-side rolling crash is not vetoed. New `GAP_UPRIGHT_VETO` (`GAP_VETO`) calibration event records each veto (angle, veto threshold, speed, deviation, cadence) so field data can measure the real silence-orientation angle (the old gap regime never logged it), count vetoes vs `CRASH_OK`, and catch any FN via a paired `MANUAL_SOS`. Regression seeds in `CrashStateMachineTest`: upright (~0°) vetoes incl. a real-data replay of the FP session; ~30° tilted, on-side, and invalid-ref still confirm. | ✅ Implemented |
| **R6-G** | **Prompt-stop (non-gap) upright veto.** R6-F's "lever to revisit" (below), implemented after the 2026-06-03 session `effa0e`: a bump at 12 km/h → coast to a stop in ~7 s (gap ≤ `delayedStopGapMs` → prompt-stop regime) → stand motionless and **upright** for the full 20 s window confirmed as a crash (FP, rider cancelled in 3.5 s). The R6-F veto did not cover it (gap regime only). R6-G extends the upright veto to the prompt-stop regime, with one EXTRA guard the gap regime does not need: the impact must have produced **no violent rotation** (`peakGyroSinceImpactRadS < nonGapUprightVetoMaxGyroRadS`, 3.0 rad/s — tracked from impact entry through the silence window). Rationale: the prompt stop is the more crash-like regime, so vetoing there is riskier; an over-the-bars / endo that ends wheels-up (≈ upright) spikes the gyro (the 2026-06-03 on-side crash `27baa0` hit 9.65 rad/s vs `effa0e`'s ~1.7) and is left to confirm, as is any toppled on-side crash (≥ 15° cone). The "unconscious rider, bike upright" FN is not feasible — a laterally-unstable bike cannot stay within 15° without a conscious rider balancing it; incapacitation topples it (on-side → confirms) or tumbles it (high gyro → confirms). The `GAP_VETO` row gains `regime=GAP\|PROMPT` and `gyro_peak`/`gyro_thr`. Regression seeds in `CrashStateMachineTest`: `effa0e` low-rotation upright → vetoed (PROMPT regime); high-rotation (endo) upright → confirms; on-side / invalid-ref / GPS-stale unaffected. | ✅ Implemented |

### Revision 4 — May 2026 (contextual sensor data)

| ID | Change | Status |
|----|--------|--------|
| **C5** | **Cadence gate in SILENCE_CHECK.** If `cadenceDataReceived && cadence > 20 RPM`, the rider is actively pedalling → instant false-alarm exit with `CAD_GATE` log event. Guard: only active when a cadence sensor/provider has been detected at least once (first data point lifts `cadenceDataReceived`). No cadence sensor = algorithm unchanged. Later refinements: `isCadenceActive` adds a freshness-by-change stuck-sensor guard (the value must have genuinely fluctuated, ≤ 10 s ago in sample time — a sensor repeating its last value bit-exact after signal loss is ignored), and the 2026-05-25 FN fix added on-side CAD_GATE suppression (`lastCadenceGateSuppressed`: live orientation ≥ 45° vs the pre-impact reference → the gate is unsafe and is skipped; see the Sensors table). | ✅ Implemented |
| **C6** | **Grade-aware proactive peak boost.** The single-frame peak threshold is raised by +2/+5/+8 m/s² for descents of −4/−7/−10% respectively. This pre-empts the first TERRAIN_CLUSTER false alarm on bad descents. The smooth threshold is untouched — a real crash on a descent still fires via the smooth path. | ✅ Implemented |
| **C7** | **Deceleration tracking in `updateSpeed()`.** `lastDecelerationKmhPerS = Δspeed/Δt` is computed on every speed update and logged at `IMPACT_ENTER`. Not used as a gate (GPS ~1 Hz is too coarse), but large negative values (< −10 km/h/s) at impact are strong calibration evidence. | ✅ Implemented (logging only) |
| **C8** | **Enriched calibration logs.** All major events (`IMPACT_ENTER`, `IMPACT_TMO`, `CRASH_CONFIRMED`, `PERIODIC`, `HIGH_MAG_NORISING`) now include `grade`, `cadence`, `grade_boost`, and `decel` fields. Gives full contextual picture for each event. | ✅ Implemented |

### Revision 4 — May 2026 (FP field reports)

| ID | Change | Status |
|----|--------|--------|
| **C1.1** | **Peak thresholds widening — considered but reverted.** A candidate change widened pthr to `smoothedThr + 8..+13` after FP reports. Commit-history review re-attributed those reports to the v1.2.0-era gyro entry path (removed in `ab69a40` before v2.0.0). Since v2.0+ riders are not exposed to that pattern, the widening was reverted to preserve the FN-protection rationale of the original `pthr = smoothedThr + 5` design. | ↩️ Reverted (kept rev-3 values) |
| **C9** | **TERRAIN_CLUSTER engages earlier** — `CLUSTER_MIN_TMO` lowered from 3 to 2. Two confirmed-then-timed-out impacts within the 2-min cluster window now activate the +8 m/s² peak boost (`POST_CLUSTER_COOLDOWN_MS = 60 s`). Bikepark / MTB descent riders accumulate IMPACT_TMOs faster than recreational rides, and the third TMO was often where the FP fired. The boost only adds to the peak threshold, not the smoothed one, so a real sustained crash on rough terrain still triggers via the smoothed path. | ✅ Implemented |

### Revision 3 — April 2026 (post-calibration, real ride logs)

| ID | Change | Status |
|----|--------|--------|
| **C1** | **Peak thresholds reduced** from 70/60/50 m/s² to **60/50/40 m/s²** (uniform `pthr = smoothedThr + 5` principle). Closes the 15 m/s² blind-spot between `smoothedThreshold` and old `peakThreshold`. Validated against 83 min MTB log (v1) and road/climb log (v2). | ✅ Implemented |
| **C2** | **FP fix: `crashConfirmSpeedKmh` decoupled from `minSpeedForCrashKmh`**. Root cause: uphill cycling at 6.8 km/h with `minSpeed=0` (test mode) was falsely confirmed because `minSpeed==0` also bypassed the confirmation speed gate. Fix: confirmation gate is now controlled exclusively by `crashConfirmSpeedKmh == 0`. | ✅ Implemented |
| **C3** | **Config migration v0→v2** (`configVersion` field added to `KSafeConfig`). On first load after upgrade, stale generic defaults (minSpeed=10, confirmSpeed=5) are replaced with preset-canonical values for LOW (3/3) and HIGH (15/5). MEDIUM users see no change (canonical=old default). Migration is idempotent and logged via Timber. | ✅ Implemented |
| **C4** | `CRASH_OK` log event now includes `confirm_spd_thr` field — makes it easy to verify which speed threshold was active at confirmation time. | ✅ Implemented |

### Revision 2 — April 2026 (post-review)

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

### Empirical calibration status (updated revision 3)

| Parameter | Value | Status |
|-----------|-------|--------|
| **Peak thresholds** | **60 / 50 / 40 m/s²** | ✅ **Data-validated** for LOW (83 min MTB log) and MEDIUM (road/climb log). HIGH is inferred from the thr+5 principle — no road-bike log yet. |
| `GPS_STALE_DEVIATION_MAX` | 1.5 m/s² | ⚠ Order-of-magnitude estimate. Needs validation. |
| `GPS_STALE_SILENCE_DURATION_MS` | 8,000 ms | ⚠ ~2× nominal; chosen conservatively. No GPS-stale scenario in logs yet. |
| `SPEED_DROP_ACCEL_STILL_MS` | 60,000 ms | ⚠ Long enough to filter normal human activity, short relative to a 5 min ride pause. |

### Soft-fall detectability ceiling

From real log analysis (v1, MTB session): a soft fall at 13–16 km/h generated raw=43.7 m/s² peak and smooth=21.7 m/s². Normal riding in the same session generated raw values up to 67.7 m/s², with 29 events in the 40–50 m/s² range. The fall signal is **within the normal riding noise distribution** — no threshold adjustment can cleanly separate it from terrain events using acceleration magnitude alone.

This is a fundamental limitation of accelerometer-based detection for low-energy impacts. The RST_SNAP event (logged 2s after every pipeline auto-reset) is the diagnostic tool for identifying these cases in future logs.

### Residual risks not yet addressed

1. **MONITORING gate uses stale `currentSpeedKmh`** — when GPS is stale, the entry condition `currentSpeedKmh >= minSpeedForCrashKmh` is evaluated against the frozen last-known value. In most cases this is conservative (frozen at riding speed → allows evaluation), but it remains conceptually inconsistent with the GPS-stale handling elsewhere. Not currently mitigated.

2. **Cold-start may exceed 8s** in cold-start GPS scenarios (urban canyon, overcast). If the guard expires without speed data, MONITORING is effectively blocked because `currentSpeedKmh = 0.0` won't pass `minSpeed`. This is fail-safe (no false positives) but means there is no detection during the first ~30s of a cold start. Documented limitation.

3. **`SensorManager` event batching** — `SENSOR_DELAY_GAME` does not guarantee 50 Hz. Android may batch events under load, in which case the 3-sample smoothing window may cover more than 60ms. Has not been observed in practice but worth measuring. Workaround if needed: use sample timestamps instead of fixed-count windows.

---

## Post-kill resume (countdown persistence)

Once a crash is confirmed, the rider enters a cancel **countdown** of `countdownSeconds`. If Android kills the KSafe process during that window — usually because of low-memory pressure from a parallel app — the in-memory `countdownJob` dies with it. Without persistence, the alert is silently abandoned.

Phase 3 of the reliability work persists enough state to re-attach the countdown on next boot:

- `EmergencyState.reasonEnum: EmergencyReason?` — written by `startCountdown`, cleared on cancel / alert / idle. `null` for legacy installs before the schema bump.
- `EmergencyState.countdownDeadlineMs()` — derived from the existing `countdownStartTime + countdownDurationSeconds * 1000`. No new stored field.

### Decision (`EmergencyResumeDecision.decideResume`)

`KSafeExtension.initializeSystem` reads the persisted state right after the first config load and branches on `decideResume(state, now)`:

| Condition | Result | Action |
|-----------|--------|--------|
| `status != COUNTDOWN` OR `reasonEnum == null` | `Nothing` | No-op |
| `now < deadline` | `Active(remainingMs)` | Call `resumeCountdown` — re-launches the countdown job with the same beep / overlay / cancel UX as `startCountdown`, with the remaining duration |
| `now >= deadline` AND `age <= 24h` | `AfterDeadline` | Show a **10 s mini-confirm** (`SystemAlertWindow`) with cancel button. Default on timeout: send. The persisted state is cleared BEFORE the mini-confirm starts so a second process kill during the 10 s window cannot create an infinite re-trigger loop on next boot |
| `now >= deadline` AND `age > 24h` | `DiscardStale(age)` | Clear the persisted state silently. The countdown is too old to act on |

### Why a mini-confirm rather than direct send

Process kill is overwhelmingly an Android low-memory event, not a "rider in peril" event. Firing alerts against contacts dozens of minutes after a crash the rider may have already cancelled mentally would be a worse failure mode than the original bug (silent abandonment). The 10 s mini-confirm strikes the balance: a real emergency still goes out by default, but the rider gets a cancel opportunity if they pick up the Karoo and see the overlay.

### Single-shot semantics

A second process kill during the 10 s mini-confirm loses the emergency entirely. This is intentional: the persisted state is cleared before the mini-confirm runs, so the second-kill scenario looks like "no emergency in progress" to the next boot. If we kept the state alive across the mini-confirm, we'd risk re-triggering on every reboot until the rider explicitly cancels.

### Cooldown interaction

`lastCrashTime` lives **in memory** on `CrashDetectionManager`, not in DataStore. After the process dies and restarts, it resets to 0 along with the rest of the manager state. Consequence: during the resumed countdown the cooldown window from the original crash is gone, so a NEW crash detected mid-resume would NOT be suppressed.

This is an accepted gap. The scenarios that cause process kill mid-countdown — Android low-memory pressure, force-stop, or an unrelated crash in the app — are infrequent, and the probability of a real second physical crash arriving within the few seconds of a resumed countdown is extraordinarily small. Persisting `lastCrashTime` would harden this corner but the implementation cost is not justified by the risk it eliminates.

`resumeCountdown` and `resumeAfterDeadline` therefore dispatch the alert through `sendAlerts(config, reason)` directly without going through the `confirmCrash` gate. The gate exists to deduplicate fresh detections; the resumed countdown is a continuation of a detection that already passed it.

### Calibration / observability

The resume paths log to `Timber` at `INFO`/`WARN`:

- `Resuming countdown after process restart, remaining=<ms>ms` (Active path)
- `Resuming countdown after deadline — mini-confirm` (AfterDeadline path)
- `Discarding stale countdown, age=<ms>ms` (DiscardStale path)

No new `CalibrationLogger.Event` was added — the resume is an emergency-management behaviour, not a detection-pipeline event.

---

## Revision 6 — Pre-impact orientation reference (2026-05-22)

**Problem solved:** The textbook *bump + brake + stop* false positive — a gravel or off-road rider hits a single isolated spike, brakes in reaction, and comes to a complete stop that satisfies the silence gate. The previous Revision 5 "orientation-aware silence" mechanism attempted to address this using a **learned baseline** gravity vector accumulated during cruising. That approach was dead code off-road: on `profile=GRAVEL`, empirical log analysis showed that 99 of 105 periodic samples (94 %) were below the 15 km/h speed gate, and the 6 remaining samples all had `accelStdDev > 1.5` — the rough-terrain gate. Zero gravel samples ever satisfied the learning criteria. On road it worked but required ~35 min of cruising before becoming ready.

### Solution: two independent signal regimes

The learned-baseline approach is replaced by a **pre-impact orientation reference**: the average gravity-vector direction over the ~2 s immediately before the impact. This reference is always available within 2 s of ride start, requires no learning gates, and is terrain-independent (averaging over ~100 gravel-bounce samples converges to the true orientation because bounce is zero-mean).

Two signals decide the silence-window duration, each authoritative in its own regime:

| Signal | Measures | Authoritative when |
|--------|----------|--------------------|
| **Gap** (`firstSilenceGapMs`) | Time from impact to first stillness — impact→stop causality | Always available; owns delayed stops |
| **Orientation** (pre-impact vs silence angle) | Whether the bike changed posture | Owns prompt stops, when a valid reference exists |

#### Decision table

```
gap > 8 s  (rider kept moving after impact — delayed stop):
    → 20 s window  [gap regime; orientation NOT used to pick the window]
       · but at the 20 s confirm gate, the R6-F upright veto applies
         (orientation < 25° → suppress confirm) — see "Two uses of orientation" below

gap ≤ 8 s  (stopped with the impact — prompt stop):
    angle ≥ 45°  (on-side or significantly tilted)  → 4.5 s  [clear crash, fast alert]
    angle < 45°  (upright relative to pre-impact)   → 20 s   [ambiguous, wait]
    invalid pre-impact reference                    → 4.5 s  [abrupt stop → lean crash]
```

- The **gap** regime does the heavy false-positive lifting. A bump+brake+stop FP has a long gap (10–20 s of continued riding) → 20 s window, with no dependency on orientation or terrain. A real crash always has a short gap (1–4 s) so the gap rule never delays a genuine emergency.
- The **orientation** regime does scoped work among prompt stops: a crash lays the bike on its side (angle ≥ 45° → fast 4.5 s alert); an ambiguous upright stop at a traffic light or after a non-crash bump requires 20 s.
- The 20 s window only costs a delay if an event is ambiguous; for a true false positive the rider rides off and nothing fires — zero cost. The 20 s value is unchanged from the earlier orientation attempt.

#### Two uses of orientation — window choice (timing) vs confirm veto (R6-F / R6-G)

Orientation degrees feed **two distinct decisions**, and conflating them causes confusion ("didn't we already handle tilt?"):

1. **Window duration — *timing* (R6-C, the decision table above).** In the **prompt-stop** regime (gap ≤ 8 s) the angle picks *how long to wait*: ≥ 45° (on-side) → 4.5 s fast alert, < 45° (upright) → 20 s. **Both outcomes still CONFIRM** — the window choice never suppresses an alert. The **gap regime does not use orientation for the window** (always 20 s).

2. **Confirm veto — *fire / no-fire* (R6-F gap + R6-G prompt).** At the 20 s confirm gate, if the silence-window orientation is within a **tight, dedicated 25° cone** (`gapVetoUprightAngleDeg`, **not** the 45° timing threshold) of the pre-impact reference, the confirm is **vetoed** → return to MONITORING, no alert. In the **gap** regime this engages on orientation alone (R6-F); in the **prompt-stop** regime it additionally requires that the impact produced **no violent rotation** (`peakGyroSinceImpactRadS < nonGapUprightVetoMaxGyroRadS`, R6-G). This is the only place orientation *suppresses* a confirm. (The cone was originally 15°, widened to 25° after field session 2ab57f confirmed an FP at ~18.6° tilt.)

**Why the prompt-stop veto needs the extra gyro gate** (the gap veto does not):

| Regime | What it means physically | Upright + still 20 s → |
|--------|--------------------------|------------------------|
| **Gap > 8 s** | Rider kept riding ~10 s after the bump, *then* stopped | **Vetoed (R6-F)** — you do not keep riding for 10 s after crashing, so a delayed upright stop is almost certainly a benign rest. Orientation alone suffices. |
| **Gap ≤ 8 s, low rotation** | Stopped promptly, no tumble | **Vetoed (R6-G)** — a bike held < 25°-from-upright and motionless for 20 s is being balanced by a conscious rider; an incapacitated rider cannot keep a laterally-unstable bike upright (it topples → on-side, or tumbles → high gyro). Session `effa0e`. |
| **Gap ≤ 8 s, high rotation** | Stopped promptly, *with* a tumble (endo / over-the-bars) | **Still confirms** — a violent rotation ending wheels-up is crash-consistent; the gyro gate (3.0 rad/s) keeps this path firing. FN ≫ FP. |

So R6-C, R6-F and R6-G are **not redundant**: R6-C *times* the confirm using orientation in the prompt-stop regime; R6-F/R6-G *suppress* the confirm using a tighter cone — R6-F in the gap regime (orientation alone), R6-G in the prompt-stop regime (orientation **and** no-tumble). The original "lever to revisit" — a hard-brake → track-stand upright 20 s FP at gap ≤ 8 s — was observed in the field (`effa0e`, 2026-06-03) and is now closed by R6-G.

### Pre-impact reference capture (`SensorReader` + `PreImpactReference`)

On the sensor thread, every accelerometer sample is appended to a primitive ring buffer of `(x, y, z, timestampMs)`, ~150 entries (~3 s at 50 Hz). When `handleMonitoring` returns `Decision.EnterImpact`, the facade calls `sensorReader.preImpactReference(impactTs)`, which is delegated to `PreImpactReference.compute`.

`PreImpactReference.compute` averages the slice `[impactTs − GUARD_MS − WINDOW_MS , impactTs − GUARD_MS]`:

- **`GUARD_MS = 250 ms`** — excludes the impact transient (the peak rises over ~60 ms; the 250 ms guard provides margin).
- **`WINDOW_MS = 2 000 ms`** — a robust average that absorbs gravel/MTB bounce.
- Returns `PreImpactRef.INVALID` when fewer than `MIN_SAMPLES = 50` samples qualify (cold start, or < ~1 s after a resume).

`PreImpactRef` is an immutable data class `(x, y, z, valid)`. The averaging is extracted as a pure function so it is unit-testable on the JVM without constructing `SensorEvent` (which is not instantiable in unit tests).

The ring is never cleared on pause. The listener stays registered across a pause, and the facade's `onPause` (BOTH auto and manual) instead calls `invalidateVectorRing()`, which raises a `@Volatile` floor timestamp (`vectorRingFloorMs`) — entries captured before the floor are simply ignored when computing the reference. This keeps the sensor thread the ring's sole mutator (lock-free, hot path untouched), and because the ring keeps filling with the bike's stationary samples during the pause, a valid reference is available essentially immediately after any pause longer than the ~2.25 s averaging window. Only `stop()` (which unregisters the listener first) actually clears the ring and resets the floor. An impact within ~1–2 s of ride start, or within ~2.25 s of a very brief pause, yields `valid = false`.

### Gap capture (`CrashStateMachine`)

On the **first** IMPACT → SILENCE_CHECK transition of an event, the state machine records:

```kotlin
firstSilenceGapMs = silenceStartedMs - impactStartedMs
```

It is not recomputed on subsequent silence breaks — a break is micro-movement within the stillness phase, not "riding on". It is reset when the state machine returns to MONITORING.

### `computeEffectiveSilenceMs` (rewritten)

```
if (lockedEffectiveSilenceMs > 0) return lockedEffectiveSilenceMs        // latch

legacyShort = if (gpsStale) gpsStaleSilenceDurationMs (8000)
              else silenceDurationMs (4500)

// Gap regime — delayed stop. Needs no orientation.
if (firstSilenceGapMs > delayedStopGapMs (8000)):
    latch(silenceDurationUprightMs)        // 20000
    return 20000

// Orientation regime — prompt stop.
if (!preImpactRef.valid):
    latch(legacyShort)                     // never becomes valid → latch immediately
    return legacyShort

if (orientationSampleCount < MIN_ORIENTATION_SAMPLES (5)):
    return legacyShort                     // unlatched: count may grow

angle = angleBetween(preImpactRef, orientationAverage)     // acos of normalised dot product
chosen = if (angle >= uprightAngleThresholdDegrees (45°))
             legacyShort
         else
             silenceDurationUprightMs (20000)
latch(chosen)
return chosen
```

`orientationAverage` is computed from the `orientationSumX/Y/Z / orientationSampleCount` accumulators. Those accumulators are filled during BOTH IMPACT (on samples that satisfy `accelOk`, to skip the impact transient and post-impact tumble) and SILENCE_CHECK (unconditional — a non-still sample resets the accumulator via `resetSilenceWindow()` anyway). The IMPACT-phase fill is what allows the on-side relaxation gate in `handleImpact` to bypass the speed check; see *IMPACT-phase on-side relaxation* below. The accumulator is named `orientation*` rather than `silenceWindow*` so the broader scope is obvious at the call site.

**Latch (`lockedEffectiveSilenceMs`):** once the decision is made it is frozen for the remainder of the window. Without the latch, a rider standing upright with the 20 s window could lean the bike past 45° at second 19 — the running-average gravity vector would cross the threshold, `computeEffectiveSilenceMs` would return 4 500, the elapsed-time check would instantly satisfy, and Confirm would fire. The latch protects the full safety margin. The latch is cleared by `resetSilenceWindow` on every entry, exit, or break of SILENCE_CHECK so each event decides fresh.

### Orientation accumulator (IMPACT + SILENCE_CHECK)

The orientation accumulator (`orientationSumX/Y/Z`, `orientationSampleCount`) starts filling in IMPACT — `handleImpact` adds the X/Y/Z of every sample whose accelerometer deviation already satisfies `accelOk` (< `silenceDeviationMax`). Gating on `accelOk` skips the impact transient and any post-impact tumble so the average reflects the bike's actual resting orientation. SILENCE_CHECK then continues to accumulate every sample it sees (unconditional — a `!isStill` sample either resets via the within-budget reset path or wipes the accumulator via `resetSilenceWindow()`, so an extra per-sample gate is redundant).

`resetSilenceWindow` clears the accumulators on:
- Entry into SILENCE_CHECK on the **speed-drop path** — SILENCE_CHECK starts fresh and the latch decision is rebuilt from its own samples.
- Silence-break (`!isStill` within the doubled window — silence clock restarts) — exception: see *IMPACT-phase on-side relaxation* below for the within-budget on-side preservation rule.
- Exit from SILENCE_CHECK (Confirm, false-alarm, cadence-gate)
- IMPACT timeout (false alarm — accumulator is bound to the THIS impact's pre-impact reference and must not bleed into the next event)
- Pause (via `onPause`)

The IMPACT → SILENCE_CHECK transition on the **on-side relaxation path** does NOT reset the accumulator (only the latch marker is cleared). That preservation is the point of the on-side relaxation — see the next section.

### Lifecycle

| Event | Effect on pre-impact reference | Effect on silence window / orientation accumulator |
|-------|-------------------------------|----------------------------------------------------|
| First `Recording` of session | RESET (ring cleared by the previous `stop()`; floor reset) | RESET |
| `Paused (manual) → Recording` resume | Ring floor was raised at pause; the ring kept filling during the pause, so a valid reference is available right after resume. The SM's captured reference was wiped at pause | RESET (manual pause wipes the state machine — I3 fix) |
| `Paused (auto) → Recording` resume | Captured reference PRESERVED (auto-pause does not call `onPause` on the SM); ring floor was raised at pause but the ring refills during it | **PRESERVED** — mid-IMPACT or mid-SILENCE_CHECK survives the autopause + auto-resume cycle (I3 + auto-resume residual fixes). A real crash whose bike rolls long enough to trip autopause continues its in-flight detection on resume |
| `Recording → Paused (manual)` | Ring INVALIDATED (floor raised in `invalidateVectorRing` — listener stays registered, ring NOT cleared); SM's captured reference wiped by `stateMachine.onPause()` | RESET |
| `Recording → Paused (auto)` | Ring INVALIDATED (the facade raises the floor on auto pauses too — harmless to an in-flight event, whose reference was already captured at IMPACT entry); SM's captured reference PRESERVED | PRESERVED |
| Impact detected (`Decision.EnterImpact`) | **CAPTURED** from ring buffer at this moment | Orientation accumulator starts filling (gated on `accelOk`) |
| IMPACT → SILENCE_CHECK (speed-drop path) | Reference already set; gap is recorded | Accumulator **reset** — SILENCE_CHECK rebuilds the latch from its own samples |
| IMPACT → SILENCE_CHECK (on-side relaxation path) | Reference already set; gap is recorded | Accumulator **carries forward** — the ≥25 on-side IMPACT samples are durable evidence; only `lockedEffectiveSilenceMs` and `lastOrientationAngleDeg` are cleared so the latch re-evaluates cleanly from the preserved data |
| Silence break, within-budget, on-side latched | Reference unchanged; gap unchanged | Accumulator **carries forward** (CR2 preserve-on-side rule — the within-budget bump must not wipe the latch, for the same reason the on-side relaxation transition doesn't) |
| Silence break, within-budget, NOT on-side | Reference unchanged; gap unchanged | Accumulator resets via `resetSilenceWindow()` |
| Silence break, beyond `impactWindowMs × 2` | RESET (returning to MONITORING) | RESET |
| IMPACT timeout | RESET (returning to MONITORING) | RESET — accumulator is bound to the THIS impact's pre-impact reference; leaving it filled would let the next impact's on-side gate fire with ≈5 fresh samples instead of the documented 25 (CR1 fix) |
| Process restart / Karoo reboot | RESET (no cross-process persistence) | RESET |

### False-negative analysis

The on-side branch uses the same 4.5 s threshold as Revision 4 and prior. Real crashes that lay the bike on its side (the majority — ~70–85 % per cycling-incident literature) confirm with the same latency as before. The rare crash where the bike stays upright (pinned against a wall or car, OTB with bike standing) is treated by where it lands relative to the R6-G prompt-stop upright veto: if the bike ends within 25° of its pre-impact orientation AND the impact produced no violent rotation (peak gyro < 3.0 rad/s), the 20 s confirm is now **vetoed** — that case no longer confirms at all, and the SpeedDropMonitor (if enabled) is the only remaining backstop. A high-rotation OTB that ends wheels-up still confirms (the gyro gate keeps it firing), and a bike knocked to 25–45° still confirms at ~20 s instead of ~4.5 s — a ~20 s delay, well within the irrelevant range for emergency response.

The `silenceDurationUprightMs` requirement is reset by ANY accel deviation > `silenceDeviationMax` (4.0 m/s²): a rider in an upright-bike crash who shifts position even slightly is detected; a rider stopped at a traffic light typically shifts within 20 s.

A real crash with a long slide (gap > 8 s, e.g. steep descent) gets the 20 s window — a ~15 s delay vs the fast path. The rider is down and will not move, so it still confirms. Judged acceptable: the case is rare and already ambiguous, and the alternative is leaving the bump+brake+stop FP unprotected.

The false-alarm retry budget (`impactWindow × 2`) is measured **from SILENCE_CHECK entry** — not from the impact (see *Outcomes on each accelerometer sample*). This avoids a false-negative that appeared in an earlier revision that doubled the silence window but left the cutoff impact-relative: a late SILENCE_CHECK entry plus a 20 s silence window left insufficient budget for even one full window, so a single non-still sample dropped the event to MONITORING with no alert. Anchoring to SILENCE_CHECK entry means a continuously-still rider always confirms (the give-up condition only fires on non-still samples). The budget bounds how long the machine retries to achieve stillness; a rider with repeated stillness breaks in the 20 s regime can still encounter a long tail before confirm-or-giveup.

Edge cases that yield delayed-but-not-blocked confirm:
- Impact within ~1–2 s of ride start or resume — reference invalid (`valid = false`) → 4.5 s (same as Revision 4). Interpreted as a prompt on-side crash; any ambiguous case at this point has not warmed up enough to apply orientation reasoning.
- Mid-corner impact — the pre-impact reference reflects the bike leaned. The gap regime handles this: a corner-exit bump followed by riding on has a long gap → 20 s regardless of orientation.

### Performance

- Pre-impact ring buffer: ~4 Long + 3 Double ops per cruising sample at 50 Hz. Negligible CPU; the ring is a fixed-size primitive array.
- `computeEffectiveSilenceMs`: latch short-circuits after the first decision (~5 samples per window). Before-latch path: 2 sqrt + 1 acos + dot product (~12 FP ops); after-latch: 1 Long comparison.
- `PreImpactReference.compute` runs once per impact event (~100 ring entries); cost is negligible relative to the ~50 Hz impact detection overhead.

### Calibration data

The following events gain new fields:

- **`SILENCE_ENTER`** (`SIL_IN`) — gains `gap_ms` (impact→stillness gap), `pre_valid` (boolean), `pre_x / pre_y / pre_z` (pre-impact reference vector).
- **`CRASH_CONFIRMED`** (`CRASH_OK`) — gains `gap_ms`, `pre_impact_angle` (degrees between pre-impact reference and silence-window gravity average), `decided_by` (one of `GAP` / `ORIENT_UPRIGHT` / `ORIENT_ONSIDE` / `UNKNOWN`). The existing `effective_silence_ms` field is retained, alongside `silence_path` (`UPRIGHT` / `GPS_STALE` / `LEGACY` / `UNKNOWN`, derived from which window duration fired). Since commit `d1df70e` the row also carries `sil_x / sil_y / sil_z` — the averaged silence-window gravity vector — next to `pre_x / pre_y / pre_z`, so `pre_impact_angle` is independently verifiable from the raw geometry (the gap regime used to log only `pre_impact_angle=-1.0`).
- **`GAP_UPRIGHT_VETO`** (`GAP_VETO`) — since `d1df70e` carries `sil_x / sil_y / sil_z` too, plus `regime` (`GAP` = R6-F delayed stop \| `PROMPT` = R6-G prompt stop) and `gyro_peak` / `gyro_thr` (the impact→silence peak rotation vs the R6-G gate; informational only on a GAP veto), alongside the original `angle`, `veto_thr`, `speed`, `deviation`, `cadence`.
- **`IMPACT_TIMEOUT`** (`IMPACT_TMO`) — gains `pre_valid`.

The `ORIENTATION_BASELINE` (`ORIENT_BASE`) event no longer exists — there is no learned baseline to announce.

### Tuning knobs (`Thresholds.kt`)

| Field | Default | Purpose |
|-------|---------|---------|
| `silenceDurationMs` | 4 500 ms | Silence required when bike is on-side or reference invalid (GPS fresh) |
| `silenceDurationUprightMs` | 20 000 ms | Silence required when bike is upright or stop is delayed |
| `gpsStaleSilenceDurationMs` | 8 000 ms | On-side / invalid-reference duration when GPS is stale |
| `uprightAngleThresholdDegrees` | 45.0° | Angle below which the bike is classified as "still upright" |
| `delayedStopGapMs` | 8 000 ms | Impact→stillness gap above which the stop is treated as delayed (long 20 s window) |
| `onSideRelaxationAngleDeg` | 60.0° | Angle from the pre-impact reference above which the bike is "decisively on the ground". Used to bypass the speed gate in BOTH the SILENCE_CHECK speed-rise relaxation AND the IMPACT-phase on-side relaxation. Stricter than `uprightAngleThresholdDegrees` so merely-leaned bikes do not relax the speed check. |
| `onSideRelaxationMaxSpeedKmh` | 25.0 km/h | Speed ceiling on the **IMPACT-phase** on-side relaxation (`handleImpact` only): even when the bike is decisively on-side (≥ 25 samples, angle ≥ 60°), the IMPACT → SILENCE_CHECK speed-gate bypass only engages while `lastSpeedKmh < 25 km/h` OR while GPS is stale. Above this threshold the standard speed gate re-engages — a sustained ≥ 25 km/h "on-side" reading through the whole IMPACT window is a rough-descent forward-lean artefact, not a rolling escaped bike (2026-05-25 FP at 34.7 km/h; the four real falls in the same log were all < 15 km/h). The GPS-stale bypass is preserved so a rider whose GPS drops out at high speed during a crash still benefits from the relaxation. The ceiling is **not** applied inside SILENCE_CHECK: once the on-side latch has engaged there, `isStill = accelOk` at any speed with fresh GPS — a deliberate, FN-safe asymmetry (the latch needed decisive evidence to engage, and a mid-window ceiling would drop a genuine crash whose bike accelerates downhill). |

Companion-object constants (in `CrashStateMachine`, not `Thresholds`):

| Constant | Value | Purpose |
|----------|-------|---------|
| `MIN_ORIENTATION_SAMPLES` | 5 | Samples needed before the SILENCE_CHECK latch's orientation classification kicks in (~100 ms at 50 Hz). |
| `IMPACT_RELAXATION_MIN_SAMPLES` | 25 | Samples of sustained on-side `accelOk` accumulation required before the IMPACT-phase relaxation can bypass the speed gate (~500 ms at 50 Hz). Stricter than `MIN_ORIENTATION_SAMPLES` because bypassing the speed gate at IMPACT→SILENCE_CHECK entry is more consequential than the in-SILENCE_CHECK latch decision. |

---

## IMPACT-phase on-side relaxation (revision 7)

The SILENCE_CHECK on-side speed-rise relaxation introduced earlier in this branch closes the auto-resume residual when the state machine has already reached SILENCE_CHECK before the bike starts rolling. A narrower residual remains when the bike keeps moving long enough that the Karoo autopause + auto-resume cycle unfolds entirely **inside IMPACT**: `handleImpact`'s standard transition gate requires `speedDropOk` (`currentSpeedKmh < crashConfirmSpeedKmh`), so a bike still rolling above 5 km/h cannot reach SILENCE_CHECK on the fast path. The IMPACT window times out at 15–25 s, the event drops to MONITORING, and detection falls back to the `SpeedDropMonitor` (~5 min + 60 s stillness) — not always reliable in environments with light vibration (windy pass, traffic shoulder).

The IMPACT-phase relaxation closes that gap symmetrically:

```
onSideRelaxed = (orientationSampleCount >= IMPACT_RELAXATION_MIN_SAMPLES)
              AND (currentOrientationAngleDeg() >= onSideRelaxationAngleDeg)   // 60°
              AND (lastSpeedKmh < onSideRelaxationMaxSpeedKmh OR gpsStale)     // 25 km/h ceiling
gateOk        = accelOk AND gyroOk AND timeOk AND (speedDropOk OR onSideRelaxed)
```

Decisive on-side evidence (≥25 samples = ~500 ms of sustained `accelOk` on-side accumulation, angle ≥ 60° from the pre-impact reference) allows the IMPACT → SILENCE_CHECK transition to bypass the speed gate, as long as the current speed is below the `onSideRelaxationMaxSpeedKmh` ceiling (25 km/h; GPS-stale bypasses the ceiling). This ceiling exists only at this IMPACT-phase gate — the SILENCE_CHECK on-side relaxation has none (see the tuning table). Rationale: a bike that is decisively on the ground cannot be ridden — if the bike is moving while flat, the bike has escaped the downed rider; but an escaped bike decelerates within seconds, so a sustained ≥ 25 km/h reading through the IMPACT window is a rough-descent artefact instead.

**Why this preserves false-positive safety:**

- The other gates stay intact. `accelOk` (< 4 m/s² deviation) blocks a rider still handling the bike; `gyroOk` (< 2 rad/s) blocks sustained cornering rotation; `timeOk` (> 500 ms since impact) enforces a settle period; the 60° angle threshold rejects merely-leaned bikes (the upright angle threshold is 45° for the latch — 60° is reserved for "decisively flat").
- The 25-sample minimum prevents a transient angle flicker from triggering the bypass. ~500 ms of sustained on-side accumulation is required.
- The pre-impact reference must be valid. An invalid reference (cold start, < ~1 s after a resume) makes `currentOrientationAngleDeg()` return the `-1.0` sentinel and the gate cannot engage.

**Accumulator preservation on the relaxation path.** On the on-side relaxation transition, the orientation accumulator is **carried forward** into SILENCE_CHECK rather than reset. The 25 samples that proved the bike is on its side are still valid evidence; resetting them would force SILENCE_CHECK to rebuild the latch from scratch while speed is still high — and each non-still sample resets the accumulator before it can reach `MIN_ORIENTATION_SAMPLES`, making the latch unreachable. Only `lockedEffectiveSilenceMs` is cleared so SILENCE_CHECK re-evaluates its own window choice cleanly. `lastOrientationAngleDeg` is **stamped** with the just-computed relaxation angle (CR3, May 2026) so it composes with the SILENCE_CHECK gap regime — see the next paragraph. The speed-drop transition path retains the previous behaviour (full `resetSilenceWindow()`) because there is no on-side evidence to protect on that path.

**Composition with the gap regime (CR3 — May 2026).** A long-tumble crash (bike rolls/bounces for ~9 s before accel settling) enters SILENCE_CHECK via the IMPACT-relax path with `firstSilenceGapMs > delayedStopGapMs (8 s)`. SILENCE_CHECK's `computeEffectiveSilenceMs` then takes the gap-regime branch and latches the 20 s upright window WITHOUT touching `lastOrientationAngleDeg`. If the IMPACT-relax transition cleared the angle to the `-1.0` sentinel, the SILENCE_CHECK on-side relaxation gate (`lockedEffectiveSilenceMs > 0 && angle >= 60°`) would evaluate false for the entire 20 s window, `isStill` would require `speedDropOk`, and the rolling bike would burn the `impactWindowMs × 2` retry budget — the crash would fall through to the SpeedDropMonitor backstop. Stamping the just-computed IMPACT-relax angle on the transition (already ≥ 60°, the strict relaxation gate — not the 45° latch threshold) lets the SILENCE_CHECK relaxation engage on the very first sample even when the gap regime is in force. A marginal lean (45° ≤ angle < 60°) cannot exploit this path because the IMPACT-relax `onSideRelaxed` precondition required the angle to be ≥ 60°.

**Rationale (auto-resume residual closure).** Together with the auto-resume SILENCE_CHECK relaxation, the IMPACT-phase relaxation closes the "bike continues moving when Karoo autopause fires mid-IMPACT" residual. End-to-end trace on a downhill crash where the bike rolls:

1. Impact spike → IMPACT entered. Accelerometer noisy for ~100-300 ms.
2. Bike settles on its side. `accelOk` starts holding. Orientation accumulator begins filling in IMPACT.
3. Karoo autopause fires (~3-6 s after speed → 0). The I3 fix preserves the in-flight IMPACT.
4. Bike rolls down slope; speed picks up. Auto-resume fires (~3-5 km/h sustained). Auto-resume-residual fix preserves the in-flight IMPACT.
5. By ~500 ms of accel-still in IMPACT, `orientationSampleCount >= 25` and `currentOrientationAngleDeg() >= 60°`.
6. New gate fires: `gateOk = accelOk && gyroOk && timeOk && onSideRelaxed = true`. Transition to SILENCE_CHECK with the accumulator preserved and the on-side angle stamped (CR3).
7. SILENCE_CHECK's silence-window selection depends on `firstSilenceGapMs`:
   - **Gap ≤ 8 s** (prompt stop branch): orientation regime fires immediately on the preserved accumulator (already > MIN_ORIENTATION_SAMPLES on-side samples) → 4.5 s window. SILENCE_CHECK relaxation engages on the angle just stamped.
   - **Gap > 8 s** (delayed stop branch, e.g. long tumble): gap regime latches the 20 s window. The CR3 angle stamp keeps SILENCE_CHECK relaxation engaged so `isStill = accelOk` alone.
   Either way, a speed rise (bike still rolling) does not break stillness.
8. Continuous 4.5 s (gap ≤ 8 s) or 20 s (gap > 8 s) of accel-still → Confirm → emergency countdown.

Total time from impact to confirm: roughly 1-2 s (settle) + 4.5 s silence ≈ 5-7 s on the prompt-stop fast path, or ~9 s tumble + 20 s silence ≈ 29 s on the long-tumble path — vs the SpeedDropMonitor backstop's ~5 min in the cases where that backstop is even reliable.
