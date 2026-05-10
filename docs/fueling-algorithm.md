# KSafe — Nutrition & Hydration Algorithms

> **Version:** May 2026 (revision 1 — initial release with v2.1.0)
> **Files:** `CarbsTracker.kt`, `HydrationTracker.kt`, `IntensityZoneCalculator.kt`
> **Sensors:** Karoo SDK heart-rate stream + power stream + `streamUserProfile()` (HR / power zones)

---

## Overview & positioning

The nutrition and hydration tracker is KSafe's **preventive safety layer**. The reactive layers (crash detection, manual SOS, medical episode) catch incidents *after* something has gone wrong. The fueling layer tries to prevent the underlying *causes* of many incidents:

- **Bonking** (carb depletion → blood glucose drop → impaired cognition, balance, reaction time)
- **Dehydration** (fatigue, cramps, heat stress, loss of focus)

A rider who is properly fueled and hydrated has clearer judgment and faster reaction time, and is less likely to crash, blow up, or need to be rescued. This is the framing that justifies bundling fueling into a *safety* extension.

```
┌───────────────────────────────────────────────────────────────┐
│ Rider's effort (HR or power)                                  │
└──────┬────────────────────────────────────────────────────────┘
       │
       ▼
┌──────────────────────────┐    ┌──────────────────────────┐
│ IntensityZoneCalculator  │    │ Configured per-hour      │
│  → 0.7..1.3 multiplier   │    │ targets (g/h, ml/h)      │
└──────┬───────────────────┘    └──────┬───────────────────┘
       │                               │
       ▼                               ▼
┌──────────────────────────┐    ┌──────────────────────────┐
│ CarbsTracker             │    │ HydrationTracker         │
│  cumTargetG = ∫ rate dt  │    │  cumTargetMl = rate × t  │
│  (multiplier-aware)      │    │  (flat per-hour)         │
└──────┬───────────────────┘    └──────┬───────────────────┘
       │                               │
       ▼                               ▼
   Deficit alert + Time alert + InRideAlert dispatch (each tracker independently)
```

Both trackers are **fully optional** and **disabled by default**. The right targets depend on each rider; we don't ship aggressive defaults that would surprise users.

---

## Sensors & Data Sources

| Source | Used by | Used for |
|--------|---------|----------|
| `streamDataFlow(HEART_RATE)` | Carbs (multiplier when no power) | Zone classification via HR zones |
| `streamDataFlow(POWER)` | Carbs (preferred multiplier source) | Zone classification via power zones (more accurate) |
| `streamUserProfile()` | Carbs | Read HR zones (5) and power zones (7) directly from the Karoo's user profile — **no manual entry** of weight, FTP, max HR, or anything else |
| (none) | Hydration | Pure time integration; no sensor input |

The Karoo profile is the **single source of truth** for the rider's biometric configuration. KSafe never asks for weight, FTP, max HR, or anything similar — the rider has already configured these in their Karoo, and we read them at runtime.

---

## IntensityZoneCalculator

A pure helper (`IntensityZoneCalculator.kt`) that maps the rider's current HR or power against their configured zones and returns a `ZoneSnapshot` with a multiplier.

### API

```kotlin
data class ZoneSnapshot(
    val source: ZoneSource,    // POWER | HR | NONE
    val index: Int,            // 0-based; 0 = Z1; -1 if NONE
    val total: Int,            // typically 5 for HR, 7 for power; 0 if NONE
    val multiplier: Float,     // 0.7..1.3 within configured zones, 1.0 if NONE
)
```

### Source preference

```
1) POWER   if profile + currentPowerW + powerZones available, return POWER zone
2) HR      else if profile + currentHr + heartRateZones available, return HR zone
3) NONE    else (no sensors, no zones) — multiplier 1.0 fallback
```

Power is preferred because it's a cleaner intensity proxy than HR (HR lags effort by 30–60 s, drifts over long rides, and is influenced by hydration / temperature).

### Zone-to-multiplier mapping

The multiplier scales linearly across the rider's configured zones from **0.7** (recovery) to **1.3** (top zone). The Karoo uses a 5-zone HR model and a 7-zone power model — both fit the same formula because we use **zone index relative to the total**:

```kotlin
ratio      = idx / (total - 1)               // 0.0 .. 1.0
multiplier = MIN_MULT + ratio × (MAX_MULT - MIN_MULT)
```

Concrete values:

| HR zone (5-zone model) | Multiplier | Power zone (7-zone Coggan) | Multiplier |
|---|---|---|---|
| Z1 (recovery) | 0.70 | Z1 (Active recovery) | 0.70 |
| Z2 (endurance) | 0.85 | Z2 (Endurance) | 0.80 |
| Z3 (tempo) | 1.00 | Z3 (Tempo) | 0.90 |
| Z4 (threshold) | 1.15 | Z4 (Lactate threshold) | 1.00 |
| Z5 (VO2max) | 1.30 | Z5 (VO2max) | 1.10 |
| — | — | Z6 (Anaerobic) | 1.20 |
| — | — | Z7 (Neuromuscular) | 1.30 |

### Out-of-range readings

If `currentHr` is below `heartRateZones[0].min` (rider coasting at very low HR) or above the last zone's max (sprinting beyond configured Z5), the calculator **clamps to the nearest zone edge**:

- Below Z1: returns Z1 multiplier (≈ 0.7) with `source = HR/POWER`
- Above last zone: returns last-zone multiplier (≈ 1.3) with `source = HR/POWER`

This is meaningfully different from `source = NONE`. Calibration analysis can distinguish "no sensor data" (NONE) from "sensor present, just outside configured zones" (HR/POWER, clamped index).

---

## Multiplier rationale — target rate vs. burn rate

> This is the most important conceptual point in the whole design. The multiplier is a **fueling strategy**, not a **physiological match**. Read this section before tuning the constants.

A rider's **actual carb burn rate** in each zone is roughly:

| Zone | Approx burn (g/h) | Notes |
|---|---|---|
| Z1 (recovery) | ~25 | Mostly fat metabolism — carb use is low |
| Z3 (tempo) | ~60 | Balanced 50 / 50 fat + carb |
| Z5+ (VO2max / sprint) | ~90+ | Almost all carb, but the gut absorbs ≤ 90 g/h regardless |

If the multiplier reflected burn rate strictly, it would need a wider range — roughly **0.4 to 1.5** (a ~3× spread between low and high effort).

KSafe deliberately uses a **narrower** range (**0.7 to 1.3**, ~2× spread) for one reason: **anti-bonk priority**.

The reasoning:

1. When you're cruising in Z1 (descending, recovering between climbs, drafting), you don't need many carbs *for the current effort*. But you should still be eating, because:
   - Glycogen is finite (~500 g total in muscles + liver). Once depleted, ride performance collapses (the *bonk*).
   - The rider doesn't know in advance when intensity will rise. Eating consistently across the ride keeps glycogen ready for the next climb / attack / surge.
   - Modern endurance fueling guidance (Asker Jeukendrup, ISSN, etc.) advises **steady intake throughout the ride at near-target rate**, not "match what you're burning right now".

2. If the multiplier were 0.4 in Z1, the rider on a long descent would consume only 24 g/h (0.4 × 60). Combined with the 4–5 hours required for a typical century, that's a meaningful glycogen deficit accumulating before the next climb.

3. The ceiling at 1.3 is similarly intentional. The gut absorbs ≤ 90 g/h regardless of demand. Pushing the target above ~80 g/h would set the rider up for **GI distress**, not better fueling.

**Practical examples** with `target = 60 g/h`:

| Effort | Multiplier | g/h target |
|---|---|---|
| Z1 recovery / descent | 0.70 | 42 g/h |
| Z3 tempo (e.g. group ride pace) | 1.00 | 60 g/h |
| Z5 sprint / hard climb | 1.30 | 78 g/h |

This is the range modern coaches recommend. The rider is told to keep eating reasonably even when easy, and to slightly increase intake when working hard — **not** to perfectly match burn rate.

If a future revision wants to lean further into burn-rate matching (e.g. for ultra-distance riders with stronger fat oxidation training), widening to 0.5–1.5 is a single-line change. Defer until calibration data justifies it.

---

## CarbsTracker

`CarbsTracker.kt` integrates the carb target rate over time, tracks logged intake, and dispatches `InRideAlert`s when the rider falls behind.

### State

```kotlin
@Volatile private var cumTargetG = 0f       // float for integration precision
@Volatile private var cumLoggedG = 0        // int — exact sum of logs
@Volatile private var sessionStartMs = 0L
@Volatile private var lastTickMs = 0L       // for dt
@Volatile private var lastLogMs = 0L        // for time-based alert
@Volatile private var lastAlertMs = 0L      // shared cooldown anchor
@Volatile private var lastZoneSnapshot = ZoneSnapshot(NONE, -1, 0, 1f)
@Volatile private var lastPeriodicLogMs = 0L
```

All fields are `@Volatile` because they're written from Karoo SDK callbacks and read from the tick coroutine.

### Per-tick integration

Every 5 s, the tick coroutine:

```kotlin
val zone = IntensityZoneCalculator.calculate(profile, hr, power)
if (lastTickMs != 0L) {
    val dtSec = (now - lastTickMs) / 1000f
    val ratePerSec = config.carbTargetGperHour / 3600f
    cumTargetG += dtSec * ratePerSec * zone.multiplier
}
lastTickMs = now
```

The `lastTickMs == 0L` guard prevents a spurious 5 s spike on the first tick after `start()`.

### Alert evaluation

**Two combinable alert modes**, both gated by a single 5-minute cooldown so they can't fire within seconds of each other:

```
Deficit alert:
   if carbDeficitAlertEnabled
      AND (cumTargetG - cumLoggedG) >= carbDeficitThresholdG
      AND (now - lastAlertMs) >= ALERT_COOLDOWN_MS (5 min)
   → fire

Time alert:
   if carbTimeAlertEnabled
      AND (now - lastLogMs) >= carbTimeIntervalMin minutes
      AND (now - lastAlertMs) >= ALERT_COOLDOWN_MS
      AND (initial-delay grace passed; see below)
   → fire
```

### Initial delay (time alert only)

The time-based alert has a **per-tracker initial grace period**. The motivation: most riders don't eat or drink in the first 20 minutes of a multi-hour ride; firing a "time to eat!" alert at minute 25 of a 4-hour effort is a nag, not safety.

```kotlin
val isFirstAlert = lastAlertMs == 0L && cumLoggedG == 0
if (isFirstAlert && carbTimeInitialDelayMin > 0) {
    if ((now - sessionStartMs) < carbTimeInitialDelayMin × 60_000) return
}
```

The grace period only applies to the **first** alert in a session. Once any alert fires (`lastAlertMs > 0`) or the rider logs an item (`cumLoggedG > 0`), the regular interval logic takes over for subsequent alerts.

| Default | Effect |
|---|---|
| `carbTimeInitialDelayMin = 30` | First time-alert can't fire before minute 30 of the session |
| `carbTimeInitialDelayMin = 0` | Disabled — first alert fires after `carbTimeIntervalMin` from session start (original behaviour) |

### Custom alert title and detail

Both the **title** and the **detail line** of the `InRideAlert` are per-rider customisable, with the rider's templates rendered through `extension/managers/AlertTextRenderer.renderAlertText` at fire time:

| Config field | Default | Purpose |
|---|---|---|
| `carbAlertCustomTitle` | `""` → `R.string.fueling_carb_alert_title` (*"Eat something"*) | Title shown at the top of the popup. |
| `carbAlertCustomDetail` | `""` → source-specific defaults (`fueling_carb_alert_detail_deficit` *"Behind by {deficit}g"* / `fueling_carb_alert_detail_time` *"{elapsed} min since last log"*) | Detail line. When the rider sets a custom template it is used for **both** alert sources (deficit and time); the source-specific defaults only apply when the field is empty. |

#### Tokens

The renderer substitutes `{token}` placeholders with current data when the alert fires. Tokens not supplied are left literal so a typo is visible to the rider rather than silently blanked.

| Token | Substituted with |
|---|---|
| `{deficit}` | Current carb deficit in grams (`cumTargetG − cumLoggedG`, integer) |
| `{elapsed}` | Minutes since last log entry |
| `{target}` | Configured `carbTargetGperHour` |

Examples:
- Default deficit alert at 35 g behind → *"Behind by 35g"*.
- Custom template `"You're {deficit}g down — eat something now"` at 25 g behind → *"You're 25g down — eat something now"*.

The same pattern applies to hydration via `hydrationAlertCustomTitle` / `hydrationAlertCustomDetail` with the same token vocabulary (`{deficit}` in ml, `{elapsed}` in min, `{target}` in ml/h).

### Per-slot field appearance — colour and icon

Each of the three carb slots and two hydration slots carries an idle background colour and an optional emoji icon prefix, both customisable in the Fueling tab via `screens/FieldColorPicker` and `screens/FieldEmojiPicker`:

| Config field | Default | Used by |
|---|---|---|
| `carbNColor` (N=1..3) | `0xFF1565C0` (palette dark blue) | `CarbLogDataType.idleColorFromConfig` for the `IDLE` state's background. |
| `carbNIcon` (N=1..3) | `🧴`, `🍫`, `🍌` | Prepended to the field's main label (`"$emoji $label"`); empty string = no prefix. |
| `drinkNColor` (N=1..2) | `0xFF1565C0` | `HydrationLogDataType.idleColorFromConfig`. |
| `drinkNIcon` (N=1..2) | `💧`, `🥤` | Same prefix logic. |

The colour palette is shared across the whole app (`FIELD_COLOR_PALETTE`, 12 dark hues organised as 6 families × 2 shades). The reserved state colours (bright red / orange / amber / bright dark green / mid grey — used by SOS, Timer, CustomMessage's SENT/SENDING/ERROR/OFF flashes and the `LOGGED` flash here) are deliberately excluded so a rider's idle pick can never collide with a state-machine signal.

The emoji palettes (`FUEL_EMOJI_CARB`, `FUEL_EMOJI_DRINK`) sit in `data/ConfigData.kt` and start with `""` so riders can opt out of the prefix entirely. Emojis render in colour even though the surrounding TextView is white, so they pop against the coloured background without drawable bundling.

When the master tracker toggle is off, `CarbLogDataType` / `HydrationLogDataType` short-circuit the state machine and render a gray `OFF` non-clickable view — the rider sees that the field exists but cannot interact with it, and the in-app Fueling settings collapse the now-irrelevant sub-fields. This matches the same disabled-state pattern used by Custom Messages and Webhook fields.

### Logging API

```kotlin
fun logEntry(slot: Int) {     // slot ∈ {1, 2, 3}
    cumLoggedG += grams(slot)
    lastLogMs = now
}
```

Called from the data-field tap or the SRAM AXS BonusAction (slot 1 only).

### Status & summary

```kotlin
fun getStatus(): CarbStatus      // for the on-ride status data field
fun getSummary(): CarbSummary    // for the post-ride summary InRideAlert
```

`getStatus()` builds a snapshot from current Volatile reads. Internally consistent within one tick's worth of integration (~5 s). Polled by `CarbStatusDataType` once per second.

`getSummary()` is read after `stop()` for the post-ride summary. State is intentionally retained across `stop()` so the summary can read final totals; the next `start()` resets everything.

---

## HydrationTracker

`HydrationTracker.kt` mirrors `CarbsTracker` structurally but is **simpler**:

- **No intensity multiplier.** Sweat rate depends mostly on ambient temperature (which the SDK doesn't expose), so we don't try to model it. The rider compensates by raising `hydrationTargetMlPerHour` for hot days.
- **No sensor input** (no HR / power / user profile consumed).
- **2 logging slots** instead of 3.
- Same dual-mode alerts (deficit + time) with the same 5-minute cooldown.
- Same initial-delay grace period and same custom-title option as carbs, with their own per-tracker config fields.

### Per-tick integration

```kotlin
if (lastTickMs != 0L) {
    val dtSec = (now - lastTickMs) / 1000f
    val ratePerSec = config.hydrationTargetMlPerHour / 3600f
    cumTargetMl += dtSec * ratePerSec   // no multiplier
}
```

Everything else is identical to carbs: alert evaluation, initial delay, custom title, logging API, status/summary. The two trackers are kept as separate classes (instead of a parameterised abstraction) because the multiplier path materially differs and the code stays clearer with parallel structure than with branching on category.

---

## Logging UX (in-ride)

Two complementary mechanisms:

### Data fields (graphical, tappable)

| Field type | Slots | Display | Tap action |
|---|---|---|---|
| `CarbLogDataType` | 3 (carb-log-1 / -2 / -3) | Configured label + amount (e.g. *"Gel 25g"*) | Logs configured grams to its slot |
| `HydrationLogDataType` | 2 (hyd-log-1 / -2) | Same pattern in ml | Logs configured ml |
| `CarbStatusDataType` | 1 (carb-status) | Current deficit (color-coded) | Read-only |
| `HydrationStatusDataType` | 1 (hyd-status) | Same in ml | Read-only |

Tap behaviour: a `PendingIntent` fires a unique broadcast action (`com.enderthor.kSafe.TAP_CARB_LOG_$slot`) → `FieldTapReceiver` → `KSafeExtension.handleCarbLogTap(slot)` → `tracker.logEntry(slot)` → `CarbLogState` flips to LOGGED for 2 s (green flash) → back to IDLE.

### Hardware buttons (BonusActions, SRAM AXS only)

Two BonusActions registered: *"KSafe: Log Carb"* and *"KSafe: Log Drink"*, both wired to **slot 1** of each category. The rider maps them to AXS shifter buttons. Logging without looking at the screen.

---

## Post-Ride Summary

When `RideState` transitions to `Idle`, KSafe captures totals (before stopping the trackers) and dispatches a single `InRideAlert`:

```
"Carbs: 85/120g (71%) • Hyd: 1100/1500ml (73%)"
```

- Fires only if `fuelingPostRideSummaryEnabled` is on AND at least one tracker had a non-zero target during the session.
- No beep, no contact alert — purely a personal recap.
- Auto-dismiss after 15 s.

> **Open question:** the `InRideAlert` SDK class name suggests in-ride use. Whether the alert reliably renders during the brief Recording → Idle transition is verified by on-device testing. If on-device testing shows the summary is sometimes dropped, the fallback is to use a `SystemNotification` for the summary specifically (during-ride alerts continue to use `InRideAlert`).

---

## Concurrency, lifecycle, performance

### Threading

Same model as the medical/wellness detectors:

- **Sensor input writes** (`updateHr`, `updatePower`, `updateUserProfile`) happen on Karoo SDK callback threads. Volatile-only writes.
- **Tick coroutine** (every 5 s) runs on the extension's `Main + SupervisorJob` scope. Reads Volatiles, integrates the float, evaluates booleans for alerts.
- **`@Volatile`** is required for cross-thread visibility. No locks.

### Restart safety (`start()` race)

```kotlin
fun start(config: KSafeConfig) {
    val oldJob = monitorJob              // snapshot before reset
    // ... reset state inline (Volatile writes, immediately visible) ...
    monitorJob = scope.launch {
        oldJob?.cancelAndJoin()          // wait for previous tick loop to fully stop
        while (true) { delay(MONITOR_TICK_MS); tick() }
    }
}
```

The `cancelAndJoin` inside the *new* coroutine ensures the previous tick loop is fully stopped before the new one runs. Without this, a stale tick from the cancelled coroutine could observe partially-reset state and emit a spurious calibration log row.

### Performance

- Tick allocation: one `ZoneSnapshot` per tick (carbs only) + at most one `String` per alert dispatch. Hot path is <100 µs.
- Status data field polling: 1 Hz, allocates one `CarbStatus`/`HydrationStatus` per second per visible field. Negligible on Karoo (~80 bytes/s of garbage with both fields visible).
- `IntensityZoneCalculator.calculate()`: pure function, called once per tick. No state, no I/O.
- Calibration logging lambdas: inert when disabled (Volatile boolean check, lambda body never evaluated).

---

## Calibration Logging

| Event | Fields |
|---|---|
| `FUELING_CARB_LOGGED` | `slot, grams, cum_logged, cum_target` |
| `FUELING_CARB_FIRED` | `source(deficit|time), deficit_g, since_log_min, cum_target, cum_logged, zone, multiplier` |
| `FUELING_CARB_PERIODIC` | every 2 min: `cum_target, cum_logged, deficit, zone_source, zone_idx, zone_total, multiplier, hr, power` |
| `FUELING_HYDRATION_LOGGED` | `slot, ml, cum_logged, cum_target` |
| `FUELING_HYDRATION_FIRED` | `source, deficit_ml, since_log_min, cum_target, cum_logged` |
| `FUELING_HYDRATION_PERIODIC` | every 2 min: `cum_target, cum_logged, deficit` |

The 2-minute cadence of `*_PERIODIC` matches the existing crash-detection `PERIODIC` event so calibration analysis can correlate timelines by timestamp without modifying crash code.

---

## Open Items / Future Work

- **Soft-fall detection via fueling state.** A rider with high carb deficit + low recent intake who suddenly has an accel impact below the smoothed crash threshold could be a candidate for HR-confirmed soft-fall handling. Requires expanding the crash detector's trigger paths — out of scope for v1.
- **Power-meter battery awareness.** If the power meter sensor reports low battery, multiplier could fall back to HR mode automatically. Currently we just use whichever data is flowing.
- **Adaptive targets.** A future iteration could learn from logged intake across rides ("you consistently hit only 70 % of target — consider lowering target or improving fueling discipline"). Out of scope for v1.
- **Sweat-rate adjustment for hydration.** Requires temperature data from the SDK, which is not currently exposed. Track for a future Karoo SDK version.
- **GI-distress upper bound.** Currently nothing alerts the rider if they over-consume. The intestinal absorption ceiling is ~90 g/h; sustained intake above that often causes GI issues. Out of scope per the original spec, but worth re-evaluating with calibration data.
- **Inter-app integration.** Other Karoo extensions might want to consume the carb / hydration state. Requires a defined contract — see future spec.

---

## Configuration Reference

All config fields live in `KSafeConfig` (`data/ConfigData.kt`).

### Carbs tracker

| Field | Default | UI exposed |
|---|---|---|
| `carbsTrackerEnabled` | `false` (opt-in master — gates all sub-fields and collapses them when off) | ✅ |
| `carbTargetGperHour` | 60 | ✅ |
| `carbDeficitAlertEnabled` | `true` | ✅ |
| `carbDeficitThresholdG` | 25 g | ✅ |
| `carbTimeAlertEnabled` | `false` | ✅ |
| `carbTimeIntervalMin` | 25 | ✅ |
| `carbTimeInitialDelayMin` | 30 | ✅ (0 = off) |
| `carbAlertCustomTitle` | `""` (use default) | ✅ |
| `carbAlertCustomDetail` | `""` (use source-specific default) | ✅ — supports `{deficit}`, `{elapsed}`, `{target}` |
| `carb1Label` / `carb1Grams` / `carb1Color` / `carb1Icon` | "Gel" / 25 / palette-blue / 🧴 | ✅ (per-slot row + colour & icon pickers) |
| `carb2Label` / `carb2Grams` / `carb2Color` / `carb2Icon` | "Bar" / 30 / palette-blue / 🍫 | ✅ |
| `carb3Label` / `carb3Grams` / `carb3Color` / `carb3Icon` | "Fruit" / 20 / palette-blue / 🍌 | ✅ |

### Hydration tracker

| Field | Default | UI exposed |
|---|---|---|
| `hydrationTrackerEnabled` | `false` (opt-in master — same gating as carbs) | ✅ |
| `hydrationTargetMlPerHour` | 750 | ✅ |
| `hydrationDeficitAlertEnabled` | `true` | ✅ |
| `hydrationDeficitThresholdMl` | 300 ml | ✅ |
| `hydrationTimeAlertEnabled` | `false` | ✅ |
| `hydrationTimeIntervalMin` | 20 | ✅ |
| `hydrationTimeInitialDelayMin` | 30 | ✅ (0 = off) |
| `hydrationAlertCustomTitle` | `""` | ✅ |
| `hydrationAlertCustomDetail` | `""` (use source-specific default) | ✅ — supports `{deficit}`, `{elapsed}`, `{target}` |
| `drink1Label` / `drink1Ml` / `drink1Color` / `drink1Icon` | "Sip" / 100 / palette-blue / 💧 | ✅ |
| `drink2Label` / `drink2Ml` / `drink2Color` / `drink2Icon` | "Bottle" / 500 / palette-blue / 🥤 | ✅ |

### Post-ride summary

| Field | Default | UI exposed |
|---|---|---|
| `fuelingPostRideSummaryEnabled` | `true` | ✅ |

Internal constants (`MIN_MULT`, `MAX_MULT`, `ALERT_COOLDOWN_MS`, `MONITOR_TICK_MS`, `PERIODIC_LOG_INTERVAL_MS`, etc.) are NOT exposed. Calibrated in code from the spec-defined values described above.
