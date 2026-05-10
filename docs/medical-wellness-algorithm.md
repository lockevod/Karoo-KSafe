# KSafe — Medical Episode & Wellness Algorithms

> **Version:** May 2026 (revision 2 — % of max HR option, extended collapse window)
> **Files:** `MedicalEpisodeDetector.kt`, `WellnessMonitor.kt`
> **Sensors:** Karoo SDK heart-rate stream + `streamUserProfile()` (max HR, HR zones)

---

## Overview

KSafe's HR-based detectors complement the accelerometer-based crash detector by catching incidents that don't produce a hard impact:

```
                       ┌──────────────────────────────┐
crash / impact ───────►│ CrashDetectionManager        │ ── reactive emergency, with cancel countdown
(accel + gyro)         └──────────────────────────────┘

HR data ──────► ┌──────────────────────────────┐
                │ MedicalEpisodeDetector       │ ── reactive emergency, with cancel countdown
                │   • HR-flatline              │
                │   • HR-collapse              │
                └──────────────────────────────┘

HR data ──────► ┌──────────────────────────────┐
                │ WellnessMonitor              │ ── on-screen warning (no countdown, no contacts)
                │   sustained high HR          │
                └──────────────────────────────┘
```

The **medical episode** detector triggers a full emergency flow (countdown + alert to contacts) for genuine cardiac / syncopal events. The **wellness monitor** issues a non-cancellable on-screen `InRideAlert` when sustained high HR suggests overheating, overtraining, or onset of fatigue — informational only, not routed to contacts unless the rider explicitly raises the response level.

Both are **fully optional** and silent without a paired heart-rate sensor.

---

## Sensors & Data Sources

| Source | Rate | Used for |
|--------|------|----------|
| **Heart rate** (Karoo SDK `streamDataFlow(HEART_RATE)`) | Variable (~1 Hz) | The only sensor input both detectors consume |
| **Speed** (Karoo SDK `streamDataFlow(SPEED)`) | Variable | Activity-recent guard for medical detector |
| **User profile** (Karoo SDK `streamUserProfile()`) | On change | Max HR for the wellness `% of max HR` mode |

### HR data notes

- Heart rate is reported in **bpm** as a single-value `StreamState.Streaming` data point. KSafe exposes it via `StreamState.heartRateBpm()` extension.
- If no HR sensor is paired, the SDK simply doesn't emit. Detectors stay idle (`hrDataReceived = false`).
- If the sensor disconnects mid-ride, the SDK stops emitting. Detectors detect this via `now - lastHrUpdateMs > HR_STALE_MS` (15 s) and skip evaluation while stale. **A stale HR is NOT treated as low HR** — that would create a guaranteed false negative every time the strap loses contact.

---

## Medical Episode Detector

`MedicalEpisodeDetector.kt` runs two parallel sub-detectors. Either one firing is enough to trigger the medical-emergency flow.

### Sub-detector A — HR Flatline

Catches **asystole** and **severe bradycardia** — the absolute-low end of cardiac events.

```
condition = (currentHrBpm < HR_FLATLINE_MAX_BPM)
            AND sustained for HR_FLATLINE_DURATION_SEC seconds continuously
            AND HR data is fresh (now - lastHrUpdateMs <= HR_STALE_MS)
            AND rider was active recently (speed >= 5 km/h within last 60 s)
```

| Constant | Value | Rationale |
|---|---|---|
| `HR_FLATLINE_MAX_BPM` | 30 | A HR < 30 bpm in an active rider is pathological. Healthy resting HR is 50–90; even elite athletes rarely sit below 35. |
| `HR_FLATLINE_DURATION_SEC` | 30 | Sustained 30 s rejects single-beat artefacts (sensor contact loss, sweat) while still allowing fast emergency response. |
| `HR_STALE_MS` | 15 000 | If no HR update for 15 s, sensor is treated as disconnected, NOT as flat. |
| `ACTIVE_RECENT_MS` | 60 000 | Speed must have been ≥ 5 km/h within the last 60 s. Prevents false trigger when the Karoo is sitting on a desk with a paired strap nearby. |

After firing, the streak resets (`flatlineSinceMs = 0L`). The next flatline can only accumulate if HR rises above threshold and falls below again.

### Sub-detector B — HR Collapse

Catches **vasovagal syncope**, **hypoglycemic episodes**, and other events where the heart keeps beating at a reduced rate (so flatline never triggers).

```
baseline = average HR over [now - 240 s, now - 15 s]      (rolling 4-min baseline, excluding recent)
recent   = average HR over [now - 15 s,  now]              (recent 15 s window)

drop_pct = (baseline - recent) / baseline

condition = (drop_pct >= HR_COLLAPSE_DROP_FRACTION)
            AND HR data is fresh
            AND rider was active recently
            AND collapse cooldown elapsed (4 min after the previous fire)
            AND we have ≥ 4 min of HR history (cold-start guard)
```

| Constant | Value | Rationale |
|---|---|---|
| `HR_COLLAPSE_DROP_FRACTION` | 0.40 | A 40 % drop from the rolling baseline is rare in normal riding. Recovery from a sprint drops gradually over 1–2 min, not abruptly. A real cardiac event drops 40–60 %. |
| `HR_COLLAPSE_WINDOW_SEC` | **15** *(was 10 in revision 1)* | The recent-average window. Extended in revision 2 to 15 s so that a brief 1–3 s artefact can't pull the average down enough to cross 40 %. Detection latency increases by 5 s — negligible for the emergency response timeline. |
| `HR_COLLAPSE_MIN_HISTORY_SEC` | 240 | Cold-start guard. Collapse can't fire until 4 min of HR data is available — ensures the baseline average is stable. Also used as the post-fire cooldown. |

### Why both sub-detectors

| Scenario | Flatline catches | Collapse catches |
|---|---|---|
| Asystole (HR → 0) | ✅ HR drops below 30 | ✅ Drop > 40 % from any baseline |
| Severe bradycardia (HR → 25) | ✅ HR < 30 sustained | ✅ Drop > 40 % from typical ride HR (~150) |
| Vasovagal syncope (HR → 70 from 150) | ❌ Doesn't reach 30 | ✅ Drop ≈ 53 % |
| Hypoglycemic with mild HR depression | ❌ HR stays normal | ⚠ Depends on magnitude |
| Sensor artefact (1–3 readings drop) | Rejected — duration guard | Rejected — 15 s window guard |

Without **A**, vasovagal events go undetected for up to 5 minutes (until the speed-drop monitor in `CrashDetectionManager` fires). Without **B**, asystole still triggers but a vasovagal where HR settles at 60–70 bpm flies under the radar entirely. Both together provide layered coverage.

### Edge Cases

| Scenario | Behaviour |
|----------|-----------|
| No HR sensor paired | Both sub-detectors short-circuit on `!hrDataReceived`. Zero false positives, zero false negatives (HR-based detection is impossible without HR). |
| Sensor disconnects mid-ride | `lastHrUpdateMs` ages past 15 s → both sub-detectors skip. Logged once via `HR_STALE` calibration event. |
| Café stop at low HR | `lastSpeedAboveActiveMs` ages past 60 s → flatline disabled regardless of HR. Collapse: gradual drop, won't cross 40 % within 15 s. |
| Cold start of ride | Collapse disabled until 4 min of history. Flatline active immediately but its threshold is so conservative (30 bpm) that premature firing is implausible. |
| Sprint → recovery | Recovery typically drops 25–30 % over 1–2 min. The 15 s collapse window does not match this profile. |
| Trained athlete with resting HR ≈ 35 | Threshold of 30 still gives margin. If a use case ever emerges for elite riders with resting < 30, expose the threshold via config; not in v1. |

---

## Wellness Monitor

`WellnessMonitor.kt` watches for **sustained high HR** — useful as a fatigue / heat-stress / overtraining flag on long rides. It is informational, not a medical alert: the rider sees a notification on the Karoo and decides what to do.

### Algorithm

Single rule:

```
HR has stayed >= effectiveThreshold() continuously for
   wellnessHighHrDurationMinutes minutes
AND cooldown of duration_minutes has elapsed since the last fire
```

The streak resets on **any** drop below the effective threshold. It is **continuous, not cumulative** — interval workouts with brief valleys don't accumulate towards the trigger.

| Constant | Default | Rationale |
|---|---|---|
| `wellnessHighHrDurationMinutes` | 30 | Long enough to reject hard interval work (rarely sustains >30 min uninterrupted at the top zone), short enough to be actionable advice. |
| `MONITOR_TICK_MS` | 30 000 | Wellness is not time-critical; checking every 30 s is plenty. |
| `HR_STALE_MS` | 15 000 | Same as medical — sensor disconnect skips evaluation. |

### Effective threshold — two modes

The threshold against which the streak is computed depends on `wellnessUseMaxHrPercent`:

```kotlin
private fun effectiveThreshold(): Int {
    if (wellnessUseMaxHrPercent && profile != null && profile.maxHr > 0) {
        return (profile.maxHr * wellnessHighHrPercent) / 100
    }
    return wellnessHighHrThreshold   // absolute bpm fallback
}
```

| Mode | When to use | Default |
|---|---|---|
| **Absolute bpm** *(default)* | Rider knows their max HR and prefers a fixed number | 180 bpm |
| **% of max HR** | Auto-scale across riders / no manual tuning | 92 % of `userProfile.maxHr` |

The percent mode reads `userProfile.maxHr` from the Karoo — no manual entry required, no biometric data in our config. If the profile isn't available yet (first seconds of an extension restart), the algorithm falls back to the absolute value so the feature can't silently disable itself.

**92 % corresponds to the upper boundary of zone 5** for the typical 5-zone HR model (`60 / 70 / 80 / 90 / 95`). Above this for 30+ min is genuinely worth flagging on most rides.

### Cooldown semantics

The cooldown (= `wellnessHighHrDurationMinutes`) and the re-arm rule (`hrAboveThresholdSinceMs = 0L` after firing, requires HR to fall below the threshold and rise again) together produce this behaviour:

| Scenario | Result |
|---|---|
| Sustained zone for 30 min, fires, then HR stays high | No re-fire — re-arm requires a drop below threshold. |
| Sustained for 30 min, fires, drops briefly, rises again, sustained another 30 min | Re-fires — re-arm + cooldown elapsed. |
| Interval work: peaks at threshold, valleys below | Streak resets each valley, never accumulates to 30 min uninterrupted. |
| HR sensor disconnects mid-ride | Stale guard skips evaluation, no spurious fire. |

### Edge Cases

| Scenario | Behaviour |
|----------|-----------|
| Rider just started, HR climbed quickly | First 30 min must elapse before any alert can fire. |
| Trained athlete with high natural sustained HR | Use absolute mode and set their tolerance, OR use % mode at 95 % to push the threshold higher. |
| Older rider with low max HR | % mode auto-scales — 92 % of 165 = 152 bpm is a sensible alert level for them. |
| Rider config has `wellnessUseMaxHrPercent = true` but no profile yet | Falls back to absolute `wellnessHighHrThreshold` until the profile arrives (typically <1 s after extension start). |

---

## Threading & Concurrency

Both detectors use the same model:

- **Sensor input writes** (`updateHr`, `updateSpeed`, `updateUserProfile`) happen on Karoo SDK callback threads. They write only `@Volatile` fields — no allocation, no I/O, no lock contention.
- **Tick coroutines** run on the extension's `Main + SupervisorJob` scope, every 5 s (medical) or 30 s (wellness). They read the same `@Volatile` fields and emit alerts via `karooSystem.dispatch(...)` and the `onIncident` callback.
- **`@Volatile` is required** for cross-thread visibility. JVM does NOT guarantee that `Long`/`Double` reads/writes are atomic without volatile, and even `Int`/`Boolean` reads can be served from a stale CPU cache indefinitely.
- **No locks**. The algorithms tolerate slightly stale reads — at worst, an alert fires one tick later than ideal. Acceptable for non-time-critical (wellness) and acceptable for medical given the 5 s tick is small relative to the 30 s flatline / 15 s collapse window.

The `start()` pattern uses the **`cancelAndJoin` inside the new coroutine** trick (same as `CarbsTracker`) so that an old monitor coroutine is fully stopped before a new tick can run. This eliminates the race where a stale tick could observe partially-reset state during a config-change-driven restart.

---

## Calibration Logging

When `calibrationLoggingEnabled` is on, the detectors emit timestamped CSV rows to the calibration log. Useful for post-ride threshold analysis.

| Event | Fields |
|---|---|
| `HR_FLATLINE` | `bpm, duration_s, speed, threshold` |
| `HR_COLLAPSE` | `bpm, avg5min, drop_pct, window_s, speed` |
| `HR_PERIODIC` | `bpm, avg5min, speed, active_recent, flatline_for_s, collapse_armed` (every 2 min) |
| `HR_STALE` | `last_bpm, since_ms` (once per fresh→stale transition) |
| `MEDICAL_CANCELLED` | `how_long_ms, subkind` (FP marker — paired with the original CRASH_OK-like fire) |
| `WELLNESS_FIRED` | `bpm, threshold, mode (abs|pct), sustained_min, duration_setting` |

The lambdas passed to `calibLogger?.log { ... }` are inert (no allocation, no string formatting) when calibration logging is disabled. Zero hot-path cost.

---

## Open Items / Future Work

- **HR-based confirmation gate for crash detection.** The original spec considered using a sudden HR drop after an accelerometer impact as additional evidence to shorten the SILENCE_CHECK window in `CrashDetectionManager`. Dropped from v1 because the savings (~2.5 s on a 35 s total flow) were marginal and the change would have broken the "crash code untouched" guarantee. Re-evaluate if real-world FP/FN data shows a clear win.
- **Adaptive collapse threshold.** The 40 % drop fraction is a fixed constant. A future iteration could lower it for riders whose ride HR is naturally more variable (e.g. interval-heavy workouts). Defer until calibration data shows the constant produces FP / FN.
- **HR-based fall detection.** A soft fall (low-energy impact below the smoothed accel threshold) is a documented FN limitation of the crash detector. HR data combined with sudden orientation change could help. Out of scope for v1; needs more research.
- **Sensor-disconnect persistence.** Currently `HR_STALE` fires once per fresh→stale transition. If the user wants to know "the strap is still disconnected 2 min later", we'd need periodic re-emission. Not currently a request.

---

## Configuration Reference

All config fields live in `KSafeConfig` (`data/ConfigData.kt`).

### Medical episode

| Field | Default | UI exposed |
|---|---|---|
| `medicalEpisodeEnabled` | `true` | ✅ Switch — Health tab |
| `medicalResponseLevel` | `EMERGENCY` | ✅ Chips (Warning / Emergency) — Health tab |

Internal thresholds (`HR_FLATLINE_MAX_BPM`, `HR_COLLAPSE_DROP_FRACTION`, etc.) are NOT exposed. Calibrated in code from spec-defined values.

### Wellness

| Field | Default | UI exposed |
|---|---|---|
| `wellnessEnabled` | `false` (opt-in) | ✅ |
| `wellnessResponseLevel` | `WARNING` | ✅ |
| `wellnessUseMaxHrPercent` | `false` | ✅ |
| `wellnessHighHrThreshold` | 180 bpm | ✅ (when % mode off) |
| `wellnessHighHrPercent` | 92 % | ✅ (when % mode on) |
| `wellnessHighHrDurationMinutes` | 30 min | ✅ |
