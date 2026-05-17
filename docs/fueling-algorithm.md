# KSafe — Nutrition & Hydration Algorithms

> **Version:** May 2026 (revision 2 — burn-rate-tracking multiplier; ride-type presets)
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
│  → 0.4..1.5 multiplier   │    │ targets (g/h, ml/h)      │
│  + 90 g/h gut cap        │    │                          │
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
    val multiplier: Float,     // 0.4..1.5 within configured zones, 1.0 if NONE
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

The multiplier scales linearly across the rider's configured zones from **0.4** (recovery) to **1.5** (top zone), with an absolute **90 g/h gut-absorption cap** applied at integration time so the cumulative target can never advance faster than a recreational rider can absorb. The Karoo uses a 5-zone HR model and a 7-zone power model — both fit the same formula because we use **zone index relative to the total**:

```kotlin
ratio      = idx / (total - 1)               // 0.0 .. 1.0
multiplier = MIN_MULT + ratio × (MAX_MULT - MIN_MULT)
// effective g/h = min(ABSORPTION_CAP_GPH, baseTarget × multiplier)
```

Concrete values:

| HR zone (5-zone model) | Multiplier | Power zone (7-zone Coggan) | Multiplier |
|---|---|---|---|
| Z1 (recovery) | 0.40 | Z1 (Active recovery) | 0.40 |
| Z2 (endurance) | 0.68 | Z2 (Endurance) | 0.58 |
| Z3 (tempo) | 0.95 | Z3 (Tempo) | 0.77 |
| Z4 (threshold) | 1.23 | Z4 (Lactate threshold) | 0.95 |
| Z5 (VO2max) | 1.50 | Z5 (VO2max) | 1.13 |
| — | — | Z6 (Anaerobic) | 1.32 |
| — | — | Z7 (Neuromuscular) | 1.50 |

### Out-of-range readings

If `currentHr` is below `heartRateZones[0].min` (rider coasting at very low HR) or above the last zone's max (sprinting beyond configured Z5), the calculator **clamps to the nearest zone edge**:

- Below Z1: returns Z1 multiplier (= 0.4) with `source = HR/POWER`
- Above last zone: returns last-zone multiplier (= 1.5) with `source = HR/POWER`

This is meaningfully different from `source = NONE`. Calibration analysis can distinguish "no sensor data" (NONE) from "sensor present, just outside configured zones" (HR/POWER, clamped index).

---

## Multiplier rationale — burn-rate tracking with a gut-absorption ceiling

> This is the most important conceptual point in the whole design. The multiplier targets the rider's **actual carb burn rate** while a hard ceiling at 90 g/h keeps the per-tick rate within what the gut can absorb. Read this section before tuning the constants.

Real cycling carb burn rates (Brooks 2018; Romijn 1993; Coyle 1997):

| Zone | Approx burn (g/h) | Notes |
|---|---|---|
| Z1 (~50% VO2max) | ~20-25 | Mostly fat oxidation — carb use is low |
| Z3 (~70% VO2max) | ~50-60 | Balanced 50 / 50 fat + carb |
| Z5 (~90% VO2max) | ~80-90 | Almost all carb, but gut absorption capped at ≈90 g/h |

KSafe maps these directly via the **0.4 to 1.5** multiplier band. At the default base target of 50 g/h (Endurance preset):

- Z1: 0.4 × 50 = **20 g/h** — matches real recovery burn
- Z3: 1.0 × 50 = **50 g/h** — matches real tempo burn
- Z5: 1.5 × 50 = **75 g/h** — close to real sprint burn

### Why a ceiling, not a wider top end

Modern recreational gut-absorption ceiling for a glucose+fructose mix is **~90 g/h** (Jeukendrup 2014; ISSN 2017; IOC 2019 consensus). Race-trained gut adapts to 120-150 g/h after months of training, but recreational riders cannot absorb more than ~90 g/h sustainably without GI distress. The integrator clamps `base × multiplier` at this ceiling so a Race preset (75 g/h base) × top-zone multiplier (1.5) still integrates at 90 g/h, not 112 g/h. Riders who have done genuine gut training can manually raise the base further; the clamp protects everyone else.

### Why a low floor

Earlier versions used a 0.7 floor as an anti-bonk reserve: at recovery the rider was told to keep eating ~70% of base target even on long descents, to preserve glycogen for the next climb. Field testing (and a closer reading of the ISSN/IOC guidance) showed this **over-fueled recovery periods by 50-80%** vs. actual burn. The modern recommendation is closer to "fuel for the work you're doing, not for the work you anticipate", with the periodic time-alert acting as the anti-bonk safety net for riders who genuinely forget to eat. The 0.4 floor lands within the range modern coaches recommend for recreational riders and aligns with the burn-rate research.

### Ride-type presets

The Fueling tab exposes three quick presets that set `carbTargetGperHour` to a sensible value for the rider's intent. The presets are one-shot apply actions — tapping fills the target, and manual edits afterwards leave the target wherever the rider moved it (no stored "current preset" state).

| Preset | base g/h | Z1 effective | Z3 | Z5 (with cap) | Use case |
|---|---|---|---|---|---|
| **Casual / Recovery** | 30 | 12 | 30 | 45 | Sub-2h easy rides, recovery |
| **Endurance** (default) | 50 | 20 | 50 | 75 | Typical 2-3h training |
| **Race / Long ride** | 75 | 30 | 75 | **90** (clamped) | Long events, partially-trained gut |

The presets and floor / ceiling values land within the ranges in **Jeukendrup 2014** (Sports Medicine 44 Suppl 1), **ISSN 2017** (JISSN 14:33), and the **IOC 2019** consensus statement on nutrition for endurance and ultra-endurance athletes.

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
    // Effective rate = base × multiplier, capped at ABSORPTION_CAP_GPH (90 g/h)
    // so a Race-preset base × top-zone multiplier still integrates within
    // gut-absorbable limits.
    val effectiveGph = (config.carbTargetGperHour * zone.multiplier)
        .coerceAtMost(ABSORPTION_CAP_GPH)
    val ratePerSec = effectiveGph / 3600f
    cumTargetG += dtSec * ratePerSec
}
lastTickMs = now
```

The `lastTickMs == 0L` guard prevents a spurious 5 s spike on the first tick after `start()`.

### Alert evaluation

**Two combinable alert modes**. The deficit alert is gated by a fixed 5-minute cooldown; the time alert's cooldown is `min(5 min, interval)` so a 1-min interval (typical for testing) truly fires every minute as configured.

```
Deficit alert:
   if carbDeficitAlertEnabled
      AND (cumTargetG - cumLoggedG) >= carbDeficitThresholdG
      AND (now - lastAlertMs) >= ALERT_COOLDOWN_MS (5 min)
      AND (deficit-initial-delay grace passed; see below)
   → fire

Time alert:
   if carbTimeAlertEnabled
      AND (now - lastLogMs) >= carbTimeIntervalMin minutes
      AND (now - lastAlertMs) >= min(ALERT_COOLDOWN_MS, intervalMs)
      AND (time-initial-delay grace passed; see below)
   → fire
```

### Initial delay (both deficit and time alerts)

Both alert paths have a **per-tracker initial grace period**. The motivation:

- **Time alert**: most riders don't eat or drink in the first 20-30 minutes of a multi-hour ride; firing a "time to eat!" alert at minute 25 of a 4-hour effort is a nag, not safety.
- **Deficit alert**: the integrator runs from t=0, so on a fresh ride the deficit crosses threshold purely from elapsed time without any rider misconduct. Without this gate the rider sees a "behind 25 g" nag at minute ~25 of a fresh ride, which reads as the app malfunctioning.

```kotlin
// Same shape for both alert sources:
val isFirstAlert = lastAlertMs == 0L && cumLoggedG == 0
if (isFirstAlert && initialDelayMin > 0) {
    if ((now - sessionStartMs) < initialDelayMin × 60_000) return
}
```

The grace period only applies to the **first** alert in a session. Once any alert fires (`lastAlertMs > 0`) or the rider logs an item (`cumLoggedG > 0`), the regular logic takes over for subsequent alerts.

| Field | Default | Effect when default |
|---|---|---|
| `carbTimeInitialDelayMin` | 30 | First time-alert can't fire before minute 30 of the session |
| `carbDeficitInitialDelayMin` | 30 | First deficit-alert can't fire before minute 30 of the session |
| `hydrationTimeInitialDelayMin` | 30 | (mirror for hydration) |
| `hydrationDeficitInitialDelayMin` | 30 | (mirror for hydration) |

All four can be set to `0` to disable the grace and fire as soon as the trigger condition is met (original pre-v11 behaviour).

### Custom alert title and detail

Both the **title** and the **detail line** of the `InRideAlert` are per-rider customisable, with the rider's templates rendered through `extension/managers/AlertTextRenderer.renderAlertText` at fire time:

| Config field | Default | Purpose |
|---|---|---|
| `carbAlertCustomTitle` | `""` → `R.string.fueling_carb_alert_title` (*"Eat something"*) | Title shown at the top of the popup. |
| `carbAlertCustomDetail` | `""` → source-specific defaults (`fueling_carb_alert_detail_deficit` *"Behind by {deficit}g"* / `fueling_carb_alert_detail_time` *"{elapsed} min since last log"*) | Detail line. When the rider sets a custom template it is used for **both** alert sources (deficit and time); the source-specific defaults only apply when the field is empty. |

#### Tokens

The renderer substitutes `{token}` placeholders with current data when the alert fires. Tokens not supplied are left literal so a typo is visible to the rider rather than silently blanked.

After substitution, the rendered string is capped at the call site so the popup cannot run off the Karoo screen: titles at `ALERT_TITLE_MAX_CHARS = 40` and details at `ALERT_DETAIL_MAX_CHARS = 90` (defined in `extension/managers/AlertTextRenderer.kt`). When the cap kicks in the last visible char is replaced with `…`. The cap applies only to the on-screen `InRideAlert` — the outgoing emergency message sent through the configured provider (Pushover / Telegram / ntfy / CallMeBot) uses its own separate template (`config.message` etc.) and has no such limit.

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
| `carbNColor` (N=1..3) | `FIELD_COLOR_AUTO` (the Karoo-theme passthrough sentinel = `Color.TRANSPARENT` = 0) | `CarbLogDataType.idleColorFromConfig` for the `IDLE` state's background. When the value is the AUTO sentinel the field inflates `field_view_auto.xml` and skips `setBackgroundColor`; any other value uses `field_view.xml` and paints the bg with that ARGB int. |
| `carbNIcon` (N=1..3) | `FUEL_GEL_DRAWABLE`, `🍫`, `🍌` | Prepended to the field's main label (`"$emoji $label"`); empty string = no prefix; the `FUEL_GEL_DRAWABLE` sentinel renders as a real vector drawable instead — see below. |
| `drinkNColor` (N=1..2) | `FIELD_COLOR_AUTO` | `HydrationLogDataType.idleColorFromConfig`. Same AUTO-vs-painted layout-switch as carbs. |
| `drinkNIcon` (N=1..2) | `💧`, `FUEL_BOTTLE_DRAWABLE` | Same prefix logic, with the bottle sentinel rendering the bidón vector drawable. |

The colour palette is shared across the whole app (`FIELD_COLOR_PALETTE` = 1 Karoo-default sentinel + 20 dark hues — see [field-colours.md](field-colours.md) for the exact swatches and per-row layout). The reserved state colours (bright red / orange / amber / bright dark green / mid grey — used by SOS, Timer, CustomMessage's SENT/SENDING/ERROR/OFF flashes and the `LOGGED` flash here) are deliberately excluded so a rider's idle pick can never collide with a state-machine signal.

The emoji palettes (`FUEL_EMOJI_CARB`, `FUEL_EMOJI_DRINK`) sit in `data/ConfigData.kt` and start with `""` so riders can opt out of the prefix entirely. Emojis render in colour even though the surrounding TextView is white, so they pop against the coloured background without drawable bundling.

#### Bundled drawables (the two exceptions)

Unicode has no emoji that resembles a sports gel pouch or a cyclist's bidón, so KSafe ships two custom vector drawables for those specific shapes — the only items in the palette that aren't standard emoji:

| Sentinel constant | Resource | Default for | Rendered |
|---|---|---|---|
| `FUEL_GEL_DRAWABLE = "<gel>"` | `res/drawable/ic_fuel_gel.xml` | `carb1Icon` (slot label "Gel") | First entry of `FUEL_EMOJI_CARB` after `""`. |
| `FUEL_BOTTLE_DRAWABLE = "<bottle>"` | `res/drawable/ic_fuel_bottle.xml` | `drink2Icon` (slot label "Bottle") | First entry of `FUEL_EMOJI_DRINK` after `""`. |

Mechanism: when `iconFromConfig(c)` returns one of the sentinels:
1. `labelFromConfig` skips the emoji prefix — the drawable is the icon, not text.
2. `buildView` sets the corresponding drawable as the main `TextView`'s left compound drawable via `RemoteViews.setTextViewCompoundDrawables(R.id.field_text_main, leftDrawableRes, 0, 0, 0)`. Setting all four sides explicitly clears any drawable carried over from a previous IDLE→LOGGED transition, so the green "+25g ✓" flash never carries the gel icon along.
3. The IDLE state passes the drawable resource id; the OFF and LOGGED states pass `0`.

In `screens/FieldEmojiPicker`, a private `drawableForSentinel(s: String): Int?` helper centralises the sentinel→resource mapping, so both the trigger preview and the dialog grid render the bundled drawables consistently. Adding a third bundled icon later (e.g. a real granola bar shape) is a one-line `when` extension plus the new SVG.

The sentinel strings are angle-bracketed (`<gel>`, `<bottle>`) so they can never collide with a real emoji codepoint sequence and so the alert-text token-substitution renderer (`extension/managers/AlertTextRenderer.renderAlertText`, which does `String.replace("{$k}", v)`) cannot accidentally consume them as tokens. Once shipped these strings are saved verbatim into rider DataStore configs — they MUST NOT change in future versions or existing riders' slot icons would silently revert to the default.

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

Tap behaviour: a `PendingIntent` fires a unique broadcast action (`com.enderthor.kSafe.TAP_CARB_LOG_$slot`) → `FieldTapReceiver` → `KSafeExtension.handleCarbLogTap(slot)`. The state machine for the slot is `IDLE → LOGGED (5 s window, tappable, hint "TAP UNDO") → IDLE` on timeout, or `IDLE → LOGGED → (tap during window) → UNDONE (1.5 s red flash) → IDLE` if the rider taps the same slot a second time within the 5 s window. The undo path calls `tracker.undoLastForSlot(slot)` which reverses the cumulative grams **and** restores the previous `lastLogMs` so a time-based alert clock isn't perturbed by the bad entry. The pending revert `Job` is stored per slot in `KSafeExtension.carbTapRevertJobs[]` and cancelled before launching a new one, so a stale `LOGGED → IDLE` timer from an earlier tap cannot clobber a fresher state set by a subsequent tap on the same slot.

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

## FIT export — cumulative carbs and hydration in the ride file

KSafe writes per-second cumulative carbohydrates (g) and hydration (ml) into the Karoo's FIT file as developer fields, so the rider's activity in Strava / Intervals.icu / TrainingPeaks carries native graphs of fueling alongside HR / power / cadence — coaches can correlate fueling with effort directly without exporting a separate CSV.

### SDK surface

The `karoo-ext` SDK exposes `KarooExtension.startFit(emitter: Emitter<FitEffect>)`. The Karoo OS calls this once at FIT-pipeline start (typically a moment before `RideState.Recording`); the extension keeps emitting `FitEffect` instances for as long as the ride lives, and registers a cancellation hook for tear-down. Two effect types are used:

| Effect | When | Lands in |
|---|---|---|
| `WriteToRecordMesg(values)` | Each Recording tick | A FIT `record` message — the per-second sample alongside HR / power |
| `WriteToSessionMesg(values)` | Each Recording tick (overwriting) | The FIT `session` message — the activity's headline / summary entry |

Both take a `List<FieldValue>`, where each `FieldValue(developerField, value: Double)` pairs a custom field with its current value.

### Developer fields

```kotlin
val carbField = DeveloperField(
    fieldDefinitionNumber = 0,
    fitBaseTypeId = 136,        // float32 (= 0x88) — same convention as nomride
    fieldName = "ksafe_carbs_g",
    units = "g",
    nativeFieldNum = null,
    developerDataIndex = 0,
)
val hydField = DeveloperField(
    fieldDefinitionNumber = 1,
    fitBaseTypeId = 136,
    fieldName = "ksafe_hyd_ml",
    units = "ml",
    nativeFieldNum = null,
    developerDataIndex = 0,
)
```

Notes:
- **Float32** rather than uint16 so future enhancements (running burn-rate average, fractional values) don't need a schema migration. Integer values up to a single ride's load (~1500 g, ~65 L) convert to float32 exactly.
- Field definition numbers are stable identifiers within KSafe's developer-data namespace. They MUST NOT change once shipped — riders' historical FIT files would otherwise become uninterpretable to tools that learned the names from earlier rides.
- `nativeFieldNum = null` because no native FIT field carries "carbs eaten" / "fluid drunk" semantics; these are pure developer fields.

### Cadence: ELAPSED_TIME stream, not a `delay()` loop

The collector pulses on the Karoo's `DataType.Type.ELAPSED_TIME` stream:

```kotlin
karooSystem.streamDataFlow(DataType.Type.ELAPSED_TIME)
    .mapNotNull { (it as? StreamState.Streaming)?.dataPoint?.singleValue }
    .collect { … }
```

This is the same pattern used by the official Hammerhead sample app and by `nomride`. Two reasons it beats a `delay(1_000L)` loop:

1. **Aligns with native FIT 1 Hz** — the Karoo's ride app writes a record message each second; ELAPSED_TIME emits on the same tick, so our developer-field values land in the same record as the native HR / power sample. Zero drift over a multi-hour ride.
2. **Auto-pauses with the ride** — ELAPSED_TIME stops emitting while `RideState.Paused`. A naive `delay()` loop would continue ticking and either accumulate phantom record samples during the pause or have to gate on `currentRideState` itself with the same timing risk. The stream-driven approach removes the question entirely.

A side benefit: when the Karoo sits idle on a desk between rides, no work happens at all. The previous `delay(1000L)` loop ran ~86k iterations/day even outside a ride.

### State branch: emit only on Recording

```kotlin
when (currentRideState) {
    is RideState.Recording -> {
        emitter.onNext(WriteToRecordMesg(values))     // per-second timeline
        emitter.onNext(WriteToSessionMesg(values))    // running session totals
    }
    else -> { /* Paused / Idle / null: don't emit */ }
}
```

The session message is written **from the Recording branch, not the Paused branch**. This is contrary to a naive reading of nomride (which has a Paused branch for `WriteToSessionMesg`) but it's the one that actually works: ELAPSED_TIME stops emitting while Paused, so a Paused-only session write would never fire. Writing on every Recording tick is cheap (one extra IPC per second alongside the Record write) and means whatever value is current at FIT close becomes the activity summary header in Strava etc.

### Tracker null-safety

`startFit` may be called before the rider opted into fueling — the `CarbsTracker` and `HydrationTracker` are still null in that case. The collector reads via the existing `carbsTrackerOrNull()` / `hydrationTrackerOrNull()` accessors with `?: 0` fallback:

```kotlin
val carbsG = (carbsTrackerOrNull()?.getStatus()?.cumLoggedG ?: 0).toDouble()
val hydMl  = (hydrationTrackerOrNull()?.getStatus()?.cumLoggedMl ?: 0).toDouble()
```

A flat-zero column in the FIT is honest data ("no fueling logged") and lets a rider who enables fueling mid-season backfill cleanly without a config drift.

### Toggle and hot-toggle limitation

`KSafeConfig.fuelingFitExportEnabled` (default `true`) gates the writer. When false, `startFit` calls `setCancellable { }` and returns immediately — no coroutine spawned. The toggle is sampled once at FIT-pipeline start; flipping it mid-ride takes effect only on the next ride. A hot-toggle would be premature complexity for a setting riders almost never flip mid-ride.

### Cost

| Concept | Per ride (5 h) |
|---|---|
| 7200 record IPCs + 7200 session IPCs × ~0.1 ms CPU | ~1.5 s CPU total |
| Disk: ~8 bytes extra per FIT record (two float32) | ~28 KB |
| Battery overhead | <0.05 % (imperceptible) |

Negligible against the ride app's own write throughput. The toggle exists for riders who don't want extra developer columns in their FIT, not for battery reasons.

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

| Event (CSV tag) | Fields |
|---|---|
| `FUELING_CARB_LOGGED` (`CARB_LOG`) | `slot, grams, cum_logged, cum_target` |
| `FUELING_CARB_UNDONE` (`CARB_UNDO`) | `slot, grams (negative — the reversal amount), cum_logged, cum_target` |
| `FUELING_CARB_FIRED` (`CARB_FIRE`) | `source(deficit|time), deficit_g, since_log_min, cum_target, cum_logged, zone, multiplier` |
| `FUELING_CARB_PERIODIC` (`CARB_PERIODIC`) | every 2 min: `cum_target, cum_logged, deficit, zone_source, zone_idx, zone_total, multiplier, hr, power` |
| `FUELING_HYDRATION_LOGGED` (`HYD_LOG`) | `slot, ml, cum_logged, cum_target` |
| `FUELING_HYDRATION_UNDONE` (`HYD_UNDO`) | `slot, ml (negative — the reversal amount), cum_logged, cum_target` |
| `FUELING_HYDRATION_FIRED` (`HYD_FIRE`) | `source, deficit_ml, since_log_min, cum_target, cum_logged` |
| `FUELING_HYDRATION_PERIODIC` (`HYD_PERIODIC`) | every 2 min: `cum_target, cum_logged, deficit` |

> **Counting intakes:** a parser that wants "how many times did the rider tap log" should filter by the `_LOGGED` tags only — `_UNDONE` rows are reversals, not intakes. A parser that sums `grams` / `ml` across **both** `_LOGGED` and `_UNDONE` rows nets out correctly (the negative undo cancels the original positive log). The distinct tag exists precisely so the two analyses don't conflict.

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
| `carbTargetGperHour` | 50 | ✅ — preset chips (Casual 30 / Endurance 50 / Race 75) above the field |
| `carbDeficitAlertEnabled` | `true` | ✅ |
| `carbDeficitThresholdG` | 25 g | ✅ |
| `carbDeficitInitialDelayMin` | 30 | ✅ (0 = off — fire as soon as threshold crossed) |
| `carbTimeAlertEnabled` | `false` | ✅ |
| `carbTimeIntervalMin` | 25 | ✅ (1-60 min) |
| `carbTimeInitialDelayMin` | 30 | ✅ (0 = off) |
| `carbAlertBgColor` | `FUELING_ALERT_COLOR_ORANGE` | ✅ — swatch picker (6 colours) |
| `carbBeepPattern` | `SINGLE_LONG` | ✅ — beep pattern picker |
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
| `hydrationDeficitInitialDelayMin` | 30 | ✅ (0 = off) |
| `hydrationTimeAlertEnabled` | `false` | ✅ |
| `hydrationTimeIntervalMin` | 20 | ✅ (1-60 min) |
| `hydrationTimeInitialDelayMin` | 30 | ✅ (0 = off) |
| `hydrationAlertBgColor` | `FUELING_ALERT_COLOR_BLUE` (water-coloured by default) | ✅ — swatch picker (6 colours) |
| `hydBeepPattern` | `SINGLE_LONG` | ✅ — beep pattern picker |
| `hydrationAlertCustomTitle` | `""` | ✅ |
| `hydrationAlertCustomDetail` | `""` (use source-specific default) | ✅ — supports `{deficit}`, `{elapsed}`, `{target}` |
| `drink1Label` / `drink1Ml` / `drink1Color` / `drink1Icon` | "Sip" / 100 / palette-blue / 💧 | ✅ |
| `drink2Label` / `drink2Ml` / `drink2Color` / `drink2Icon` | "Bottle" / 500 / palette-blue / 🥤 | ✅ |

### Post-ride summary

| Field | Default | UI exposed |
|---|---|---|
| `fuelingPostRideSummaryEnabled` | `true` | ✅ Switch — Fueling tab |

### FIT export

| Field | Default | UI exposed |
|---|---|---|
| `fuelingFitExportEnabled` | `true` | ✅ Switch — Settings tab. Sampled once at FIT-pipeline start; mid-ride toggle takes effect on the next ride. |

Internal: developer-field definitions and pacing live in `extension/KSafeExtension.startFit`. Field names `ksafe_carbs_g` / `ksafe_hyd_ml` and field definition numbers `0` / `1` are stable identifiers — do not change once shipped.

Internal constants (`MIN_MULT`, `MAX_MULT`, `ALERT_COOLDOWN_MS`, `MONITOR_TICK_MS`, `PERIODIC_LOG_INTERVAL_MS`, etc.) are NOT exposed. Calibrated in code from the spec-defined values described above.
