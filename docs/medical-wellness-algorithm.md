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

`WellnessMonitor.kt` is a **three-tier system** built around the observation that a single sustained-HR rule is not enough to reliably warn the rider *before* complications appear:

- A 30-minute sustained-HR alert fires when fatigue is already setting in.
- An acute overexertion (very high HR for ~5 min) is a different physiological signal and deserves an earlier dedicated alert.
- The textbook clinical indicator of dehydration / heat stress is **cardiac drift** — the HR / power ratio rising over time even at constant effort. This requires a power meter but is more meaningful than any absolute-threshold rule.

The monitor therefore implements three independent tiers, each with its own enable toggle. The master `wellnessEnabled` switch gates all three.

### Tier 1 — Critical HR (early warning)

Acute overexertion. Fires earlier and at a higher threshold than the sustained tier.

```
condition = (currentHrBpm >= effectiveCriticalThreshold)
            AND sustained for wellnessCriticalDurationMinutes minutes
            AND cooldown elapsed (= the duration setting itself, same convention as before)
```

| Default | Value | Rationale |
|---|---|---|
| `wellnessCriticalThresholdBpm` | 175 | When `wellnessUseMaxHrPercent = false`. |
| `wellnessCriticalThresholdPct` | 95 % | When `wellnessUseMaxHrPercent = true` — top of zone 5 (VO2max → anaerobic). |
| `wellnessCriticalDurationMinutes` | 5 | Sustained 5 min above 95 % maxHR is real overexertion / cardiac risk. Shorter than the sustained tier on purpose. |

### Tier 2 — Sustained HR (long-tail fatigue)

The original tier — preserved for back-compat. Fires when HR is **moderately** elevated for a **long** time. Single rule:

```
HR has stayed >= effectiveSustainedThreshold continuously for
   wellnessHighHrDurationMinutes minutes
AND cooldown of duration_minutes has elapsed since the last fire
```

The streak resets on **any** drop below the effective threshold. It is **continuous, not cumulative** — interval workouts with brief valleys don't accumulate towards the trigger.

| Default | Value | Rationale |
|---|---|---|
| `wellnessHighHrThreshold` | 180 bpm | Absolute mode default. |
| `wellnessHighHrPercent` | 92 % | When `wellnessUseMaxHrPercent = true` — top of zone 5 (VO2max boundary). |
| `wellnessHighHrDurationMinutes` | 30 | Long enough to reject hard interval work; short enough to be actionable advice. |

### Tier 3 — Cardiac Decoupling (heat stress / dehydration)

The clinically meaningful early-warning of dehydration / heat stress / endurance fatigue is **cardiac drift**: at constant power output, HR rises over time as the rider becomes physiologically stressed. This tier requires a paired power meter; it auto-skips when power data is absent.

#### Algorithm

```
Phase 1 — Baseline establishment (only once per session):
  Wait until session has been active >= 10 min AND we have >= 8 valid samples
  (samples are only collected when power >= 50 W, to skip coasting / descents).
  baseline = average HR/W ratio over the rolling 5-min window at that moment.
  Logged as a calibration event with subkind = decoupling_baseline.

Phase 2 — Drift evaluation (every tick, after baseline):
  current = average HR/W ratio over the rolling 5-min window
  drift_pct = (current / baseline - 1) × 100

  If drift_pct >= wellnessDecouplingThresholdPct:
    if streak just started: decouplingExceededSinceMs = now
    if streak duration >= wellnessDecouplingDurationMinutes minutes
        AND cooldown of 30 min elapsed since last decoupling fire:
      → fire WELLNESS_DECOUPLING
  Else:
    streak resets (decouplingExceededSinceMs = 0)
```

#### Constants

| Name | Value | Rationale |
|---|---|---|
| `DECOUPLING_BASELINE_WAIT_MS` | 10 min | Wait long enough for the rider to settle into a stable effort and for the rolling window to be representative. |
| `DECOUPLING_ROLLING_WINDOW_MS` | 5 min | Smooths over short power variations (sprints, surges, traffic lights) while still being responsive to a multi-minute drift. |
| `DECOUPLING_MIN_POWER_W` | 50 W | Skip coasting / descending samples — including them would flood the average with high HR/W ratios that don't reflect cardiac state. |
| `DECOUPLING_MIN_SAMPLES` | 8 | Need at least 8 samples in the rolling buffer to compute a meaningful average. With a 30 s tick, that's 4 minutes of stable riding. |
| `DECOUPLING_COOLDOWN_MS` | 30 min | Once decoupling fires, heat stress / dehydration doesn't go away in 5 minutes. Long cooldown prevents re-firing in spam. |

#### Defaults

| Default | Value | Rationale |
|---|---|---|
| `wellnessDecouplingThresholdPct` | 7 % | Sport science: drift > 5 % over an hour = mild dehydration, > 10 % = significant. 7 % at 10 min sustained catches the early window before it becomes critical. |
| `wellnessDecouplingDurationMinutes` | 10 | Sustained drift for 10 min rules out transient causes (steep climb, brief surge) — by then the drift is real. |

#### Edge Cases

| Scenario | Behaviour |
|---|---|
| No power meter paired | `lastPowerW = null` → tier auto-skips. No false fires. |
| Long descent / stop | Power < 50 W → samples are not collected; the rolling window naturally ages out. Tier doesn't fire on coasting. |
| Steep climb causing temporary spike | Drift might briefly exceed 7 %, but the 10-minute sustained window rules out short bursts. |
| Power meter glitch | A single bad reading is averaged across the rolling 5-min window — minimal impact on the current avg. |
| Rider starts cold and warms up gradually | Baseline is established at minute 10, by which point HR/W has stabilised. Drift is measured against this warm-baseline. |
| Long ride with no rest stops | Cooldown of 30 min between fires; rider could see 1-2 alerts on a 4-hour ride if drift is really severe. Acceptable. |

### Effective threshold — two modes (Critical & Sustained tiers)

The HR threshold for tiers 1 and 2 depends on `wellnessUseMaxHrPercent`:

```kotlin
private fun effectiveThreshold(): Int {
    if (wellnessUseMaxHrPercent && profile != null && profile.maxHr > 0) {
        return (profile.maxHr * percent) / 100
    }
    return absoluteBpm                                // back-compat fallback
}
```

| Mode | When to use |
|---|---|
| **Absolute bpm** *(default)* | Rider knows their max HR and prefers a fixed number |
| **% of max HR** | Auto-scale across riders / no manual tuning. Reads `userProfile.maxHr` from the Karoo — no biometric entry in our config. |

If `wellnessUseMaxHrPercent = true` but the profile isn't available yet (first seconds of an extension restart, or no profile configured on the Karoo), `effectiveCriticalThreshold()` / `effectiveSustainedThreshold()` return `Int.MAX_VALUE` — the tier silently waits and cannot fire until the profile arrives. We deliberately do NOT fall back to the absolute `*Threshold` value in pct mode, because a rider who configured 95 % of max HR expects scaling with their max HR; using the unrelated bpm fallback would produce a wrong threshold without warning. Absolute mode (the default) has no profile dependency.

**Tier 3 (decoupling) does NOT use this**: drift is a relative measurement against the rider's own ride-specific baseline, so the absolute / % toggle does not apply.

### Custom title and detail templates

Every `WARNING`-level `InRideAlert` from the medical detector and from each wellness tier can have its title and detail string overridden per rider. Empty config field → built-in default (the strings under `R.string.warning_*`); non-empty field → rider's template, rendered through `extension/managers/AlertTextRenderer.renderAlertText` at fire time so `{token}` placeholders are substituted with the data the alert is reporting.

| Reason | Custom-title field | Custom-detail field | Tokens available |
|---|---|---|---|
| `MEDICAL_FLATLINE` / `MEDICAL_COLLAPSE` | `medicalCustomTitle` | `medicalCustomDetail` | `{bpm}` |
| `WELLNESS_CRITICAL_HR` | `wellnessCriticalCustomTitle` | `wellnessCriticalCustomDetail` | `{bpm}`, `{threshold}`, `{minutes}` |
| `WELLNESS_HIGH_HR` (Sustained) | `wellnessSustainedCustomTitle` | `wellnessSustainedCustomDetail` | `{bpm}`, `{threshold}`, `{minutes}` |
| `WELLNESS_DECOUPLING` | `wellnessDecouplingCustomTitle` | `wellnessDecouplingCustomDetail` | `{drift}` (% drift, 1 decimal), `{minutes}` |

Tokens are supplied at the fire site:

- `MedicalEpisodeDetector` passes `{bpm}` as the current HR reading on flatline / collapse.
- `WellnessMonitor.fireTier` passes `{bpm}`, `{threshold}` and `{minutes}` for the critical and sustained tiers.
- The decoupling fire site passes `{drift}` (formatted to 1 decimal place) and `{minutes}` (sustained streak).

The medical default strings now use a dedicated `R.string.warning_medical_title` ("Medical alert") and `R.string.warning_medical_detail` rather than the generic `R.string.app_name` they used before. Riders who have set a custom override are unaffected.

Tokens that appear in a template but are not in the supplied map are kept literal (e.g. `{nonsense}` stays as `{nonsense}`) — this surfaces typos to the rider rather than producing oddly-blanked strings.

After substitution, the rendered string is capped at the call site so it fits on the popup: titles at `ALERT_TITLE_MAX_CHARS = 40` and details at `ALERT_DETAIL_MAX_CHARS = 90` (in `extension/managers/AlertTextRenderer.kt`). When the cap kicks in the last visible char is replaced with `…`. This cap only affects the on-screen `InRideAlert` — long messages dispatched through the emergency provider (Pushover / Telegram / etc., when `responseLevel = EMERGENCY`) use a separate template and remain unconstrained.

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
| `WELLNESS_FIRED` | `tier (critical|sustained|decoupling), bpm, threshold, mode (abs|pct), sustained_min, duration_setting` |
| `WELLNESS_DECOUPLING_BASELINE` | `baseline_hr_per_w, samples, elapsed_min` (one-shot, when baseline established) |

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
| `medicalCustomTitle` | `""` (use `warning_medical_title`) | ✅ |
| `medicalCustomDetail` | `""` (use `warning_medical_detail`) | ✅ — token `{bpm}` |

Internal thresholds (`HR_FLATLINE_MAX_BPM`, `HR_COLLAPSE_DROP_FRACTION`, etc.) are NOT exposed. Calibrated in code from spec-defined values.

### Wellness — Master & shared

| Field | Default | UI exposed |
|---|---|---|
| `wellnessEnabled` | `false` (opt-in master switch — gates all three tiers) | ✅ |
| `wellnessResponseLevel` | `WARNING` | ✅ |
| `wellnessUseMaxHrPercent` | `false` (applies to tiers 1 & 2; tier 3 ignores) | ✅ |

### Wellness — Tier 1 (Critical HR)

| Field | Default | UI exposed |
|---|---|---|
| `wellnessCriticalEnabled` | `true` | ✅ Sub-switch (only when master on) |
| `wellnessCriticalThresholdBpm` | 175 bpm | ✅ (when % mode off) |
| `wellnessCriticalThresholdPct` | 95 % | ✅ (when % mode on) |
| `wellnessCriticalDurationMinutes` | 5 min | ✅ |
| `wellnessCriticalCustomTitle` | `""` (use `warning_wellness_critical_hr_title`) | ✅ |
| `wellnessCriticalCustomDetail` | `""` (use `warning_wellness_critical_hr_detail`) | ✅ — tokens `{bpm}`, `{threshold}`, `{minutes}` |

### Wellness — Tier 2 (Sustained HR)

| Field | Default | UI exposed |
|---|---|---|
| `wellnessSustainedEnabled` | `true` | ✅ Sub-switch (only when master on) |
| `wellnessHighHrThreshold` | 180 bpm | ✅ (when % mode off) |
| `wellnessHighHrPercent` | 92 % | ✅ (when % mode on) |
| `wellnessHighHrDurationMinutes` | 30 min | ✅ |
| `wellnessSustainedCustomTitle` | `""` (use `warning_wellness_high_hr_title`) | ✅ |
| `wellnessSustainedCustomDetail` | `""` (use `warning_wellness_high_hr_detail`) | ✅ — tokens `{bpm}`, `{threshold}`, `{minutes}` |

### Wellness — Tier 3 (Cardiac Decoupling)

| Field | Default | UI exposed |
|---|---|---|
| `wellnessDecouplingEnabled` | `true` | ✅ Sub-switch (only when master on) |
| `wellnessDecouplingThresholdPct` | 7 % | ✅ |
| `wellnessDecouplingDurationMinutes` | 10 min | ✅ |
| `wellnessDecouplingCustomTitle` | `""` (use `warning_wellness_decoupling_title`) | ✅ |
| `wellnessDecouplingCustomDetail` | `""` (use `warning_wellness_decoupling_detail`) | ✅ — tokens `{drift}`, `{minutes}` |

Decoupling internal constants (`DECOUPLING_BASELINE_WAIT_MS`, `DECOUPLING_ROLLING_WINDOW_MS`, `DECOUPLING_MIN_POWER_W`, `DECOUPLING_MIN_SAMPLES`, `DECOUPLING_COOLDOWN_MS`) are NOT exposed.

### Migration

`CONFIG_VERSION = 7` adds the eight new wellness fields. Riders upgrading from v6 (single-tier wellness) get the new tiers enabled by default but the master switch retains their existing `wellnessEnabled` value — opt-in stays opt-in.
