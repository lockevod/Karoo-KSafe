# KSafe — Nutrition & Hydration Algorithms

> **Version:** May 2026 (revision 3 — **carb burn from physiology**)
> **Files:** `CarbsTracker.kt`, `HydrationTracker.kt`, `IntensityZoneCalculator.kt`, `CarbBurnEstimator.kt`
> **Sensors:** Karoo SDK heart-rate stream + power stream + `streamUserProfile()` (weight / maxHr / restingHr / FTP / HR zones / power zones) + rider-entered age + sex

> [!IMPORTANT]
> **Model rewrite (May 2026)** — the carb tracker no longer multiplies a rider-configured "target g/h" by an intensity multiplier. It now computes **real carb burn** from physiology:
>
> 1. **kcal/h** from the highest-confidence tier whose sensors are paired:
>    - **Tier 1 — power**: `kcal/h = power_W × 3.6` (standard cycling formula; Coyle 1992, Moseley & Jeukendrup 2001; ~5-10 % error).
>    - **Tier 2 — Keytel et al. 2005**: HR + age + sex + weight (~10-15 % error in cycling 50-80 % VO2max). Requires the rider's age and sex from Settings.
>    - **Tier 3 — Swain & Leutholtz 1997**: HRR → METs → `kcal/h = METs × weight` (~20-30 % error; fallback when age/sex are not entered).
>    - **Tier 4 — none**: no usable inputs; the integrator stops and the data fields display `Pair HR/Pwr`.
> 2. **CHO fraction** from the current intensity zone, mapped linearly from 0.30 (Z1) to 0.95 (Z5+) — anchors from Romijn 1993, Achten & Jeukendrup 2003, Jeukendrup 2014.
> 3. **g/h carb burn = kcal/h × CHO_fraction / 4**, integrated over active movement time.
>
> The deleted `carbTargetGperHour` config field used to drive the integrator (multiplied by `IntensityZoneCalculator.multiplier`). That model produced misleading data ("burned" was actually "planned intake") and required the rider to set a target that conflated their fueling plan with their physiology. The new model has no rider-tunable rate input on the carb side; the rider only sets the **deficit threshold** (when to alert) and **reminder cadence** (how often).
>
> Hydration retains the older target-based model — there is no biosensor for sweat rate to replace it with. The optional dynamic estimator (HR + power + weight + ambient temperature + humidity) still applies. See `SweatEstimator.kt`.
>
> `IntensityZoneCalculator.multiplier` is no longer read by the carb integrator but is kept in the data class for backwards compatibility; the classifier itself (source / index / total) is still used to derive the CHO fraction.

---

## Overview & positioning

The nutrition and hydration tracker is KSafe's **preventive safety layer**. The reactive layers (crash detection, manual SOS, medical episode) catch incidents *after* something has gone wrong. The fueling layer tries to prevent the underlying *causes* of many incidents:

- **Bonking** (carb depletion → blood glucose drop → impaired cognition, balance, reaction time)
- **Dehydration** (fatigue, cramps, heat stress, loss of focus)

A rider who is properly fueled and hydrated has clearer judgment and faster reaction time, and is less likely to crash, blow up, or need to be rescued. This is the framing that justifies bundling fueling into a *safety* extension.

```
┌──────────────────────────────────────────────────────────────────────┐
│ Rider physiology + sensors                                            │
│  - power (preferred)      - HR + age + sex + weight (Keytel)          │
│  - HR + maxHr + restingHr + weight (Swain HRR fallback)               │
└──────┬───────────────────────────────────────────────────────────────┘
       │
       ▼
┌──────────────────────────┐    ┌──────────────────────────┐
│ CarbBurnEstimator        │    │ Configured per-hour      │
│  → kcal/h  (Tier 1-4)    │    │ target (ml/h)            │
│  → × CHO_fraction(zone)  │    │  (no biosensor for sweat │
│  → ÷ 4 kcal/g            │    │   rate — flat per-hour)  │
│  = real burn (g/h)       │    │                          │
│  + 90 g/h gut cap        │    │                          │
└──────┬───────────────────┘    └──────┬───────────────────┘
       │                               │
       ▼                               ▼
┌──────────────────────────┐    ┌──────────────────────────┐
│ CarbsTracker             │    │ HydrationTracker         │
│  cumBurnedG = ∫ burn dt  │    │  cumTargetMl = rate × t  │
│  (movement-gated)        │    │  (movement-gated)        │
└──────┬───────────────────┘    └──────┬───────────────────┘
       │                               │
       ▼                               ▼
   Deficit alert + Time alert + InRideAlert dispatch (each tracker independently)
```

Both trackers are **fully optional** and **disabled by default**. The carb side has no rider-tunable rate — burn comes from physiology directly. Riders set their **deficit threshold** (g behind which an alert fires) and **reminder cadence** (how often the same alert can re-fire). The hydration side retains a rider-set `hydrationTargetMlPerHour` until a sensor for sweat rate exists.

---

## Sensors & Data Sources

| Source | Used by | Used for |
|--------|---------|----------|
| `streamDataFlow(POWER)` | Carbs (Tier 1) | `kcal/h = W × 3.6` — highest-confidence burn estimate |
| `streamDataFlow(HEART_RATE)` | Carbs (Tier 2/3) | Keytel kcal/h with rider's weight + age + sex; Swain METs fallback when age/sex not entered |
| `streamUserProfile()` | Carbs | Weight (Keytel/Swain), max HR + resting HR (Swain HRR), HR zones (5) + power zones (7) for the CHO fraction |
| `riderAge` (Settings) | Carbs (Tier 2) | Required input to Keytel — entered in Settings → Fueling |
| `riderSex` (Settings) | Carbs (Tier 2) | Required input to Keytel — entered in Settings → Fueling |
| (none) | Hydration | Pure time integration; no sensor input |

The Karoo profile is the **single source of truth** for weight, FTP, max HR and zones — KSafe never asks for those. The rider only adds **age** and **sex** in Settings (Keytel needs them; without them the carb estimator falls back to the rougher Swain Tier 3). When neither HR nor power is paired, the data fields render *"Pair HR/Pwr"* and the integrator freezes.

---

## CarbBurnEstimator — physiology-based burn rate

A pure helper (`CarbBurnEstimator.kt`) computes the rider's instantaneous **carb burn rate** in g/h from whichever physiological inputs are available. The model has three steps:

1. **kcal/h** from the highest-confidence tier whose inputs are paired (see Tier 1-4 below).
2. **CHO fraction** (% of kcal coming from carbs) read from the current intensity zone — linear from 0.30 (Z1 recovery, mostly fat) to 0.95 (Z5+, almost pure carb).
3. **g/h = kcal/h × CHO_fraction / 4** (1 g CHO ≈ 4 kcal Atwater factor).

The output is `BurnEstimate(gph, kcalPerHour, choFraction, confidence, zoneSnapshot)` — the tracker integrates `gph` over active movement time and surfaces `confidence` to the rider via the data field's *"Pair HR/Pwr"* label when no usable tier fires.

### The four tiers

| Tier | Inputs | Formula | Typical error | When it runs |
|---|---|---|---|---|
| **POWER** (1) | Power meter | `kcal/h = power_W × 3.6` (gross efficiency ~22 %; Coyle 1992, Moseley & Jeukendrup 2001) | 5–10 % | Power meter paired AND emitting |
| **KEYTEL** (2) | HR + age + sex + weight | Keytel et al. 2005 sex-specific HR regressions (output in kJ/min, converted to kcal/h) | 10–15 % @ 50-80 % VO2max | No power; HR + rider age + sex + weight from Karoo profile all available |
| **SWAIN** (3) | HR + maxHr + restingHr + weight | `%HRR ≈ %VO2R` (Swain & Leutholtz 1997); `METs = 6 × %HRR + 1`; `kcal/h = METs × weight` | 20–30 % | No power; rider hasn't entered age/sex yet |
| **NONE** (4) | — | `gph = 0`, integrator frozen | — | None of HR / power paired or no zones configured |

### Keytel coefficients

```
Male:   EE_kJ_per_min = -55.0969 + 0.6309·HR + 0.1988·W + 0.2017·A
Female: EE_kJ_per_min = -20.4022 + 0.4472·HR - 0.1263·W + 0.0740·A
kcal/h = EE_kJ_per_min × 60 / 4.184
```

The formula is validated for HR 90–170 bpm, weight 40–120 kg, age 18–65. Outside this range the regression still returns a number, but error grows — the data field surfaces `Confidence.KEYTEL` regardless so the rider knows the tier. Keytel can produce a negative kJ/min at very low HR with atypical weight; that case clamps to null and the estimator falls through to Swain.

### CHO fraction by zone

The CHO fraction maps the rider's intensity zone index linearly to [0.30, 0.95]:

```kotlin
ratio       = zone.index / (zone.total - 1)        // 0.0 .. 1.0
choFraction = 0.30 + ratio × (0.95 − 0.30)
```

For a 5-zone HR model: Z1 → 0.30, Z2 → 0.4625, Z3 → 0.625, Z4 → 0.7875, Z5 → 0.95. For a 7-zone Coggan power model: Z1 → 0.30, Z4 → ~0.625, Z7 → 0.95. The end-anchors come from Romijn et al. 1993 (substrate utilization at 25/65/85 % VO2max), Achten & Jeukendrup 2003 (maximal fat oxidation), and Jeukendrup 2014 (review).

When no zones are classifiable (NONE source), the CHO fraction falls back to the midpoint (0.625) so a rider with power but no configured zones still gets a sensible carb estimate.

### Worked examples

| Scenario | Inputs | Tier | kcal/h | CHO | g/h |
|---|---|---|---|---|---|
| Strong rider on tempo | power=200 W, HR irrelevant, Z3 (index 2 of 5) | POWER | 200 × 3.6 = 720 | 0.625 | **112** → clamped to **90** |
| Rider with HR only, age/sex entered | HR=150, W=70 kg, age 40, MALE, Z3 | KEYTEL | 882 | 0.625 | **138** → clamped to **90** |
| Rider with HR only, no age/sex | HR=150, W=70, maxHR=190, restHR=50, Z3 | SWAIN | 370 | 0.625 | **58** |
| No sensors | — | NONE | 0 | — | **0** (data field shows *"Pair HR/Pwr"*) |

### IntensityZoneCalculator (still used, for zones only)

`IntensityZoneCalculator.kt` continues to classify the rider's current intensity zone from HR or power against the user profile's zone table. Power is preferred when available (cleaner intensity proxy than HR, which lags 30–60 s and drifts on long rides). The returned `ZoneSnapshot(source, index, total, multiplier)` still carries a legacy `multiplier` field for backwards-compatibility with the calibration CSV column — it is no longer read by the integrator and will be removed in a future schema bump. Only `source / index / total` feed the new CHO-fraction lookup.

### Why a gut-absorption ceiling

Modern recreational gut-absorption ceiling for a glucose+fructose mix is **~90 g/h** (Jeukendrup 2014; ISSN 2017; IOC 2019 consensus). Race-trained gut adapts to 120-150 g/h after months of training, but recreational riders cannot absorb more than ~90 g/h sustainably without GI distress. The integrator clamps the computed burn rate at this ceiling (`ABSORPTION_CAP_GPH = 90 g/h`) — see [CarbIntegrator](#carbintegrator) below. If the rider's actual physiological burn exceeds 90 g/h (very common at Z4-Z5 with power > ~150 W), the on-screen deficit just keeps growing honestly; clamping the *integration rate* prevents the cumulative total from racing ahead of any plausible intake plan.

### Why no rider-tunable target

Pre-v18 KSafe asked the rider to set a `carbTargetGperHour` value (Casual/Endurance/Race presets) and multiplied it by an intensity multiplier. That model produced misleading numbers — "burned" was actually "planned intake adjusted for effort", and required the rider to conflate their fueling *plan* with their physiology. v18 removes the field entirely. Burn is real now; the only carb-side knob the rider sets is **how many grams behind to alert at** (`carbDeficitThresholdG`) and **how often to repeat that alert** (`carbDeficitReminderIntervalMin`). The hydration side keeps its target field because there's no biosensor for sweat rate.

---

## CarbsTracker

`CarbsTracker.kt` integrates the carb target rate over time, tracks logged intake, and dispatches `InRideAlert`s when the rider falls behind.

### State

```kotlin
@Volatile private var cumBurnedG = 0f         // float for integration precision
@Volatile private var cumLoggedG = 0          // int — exact sum of logs
@Volatile private var sessionStartMs = 0L
@Volatile private var lastTickMs = 0L         // for dt
@Volatile private var lastRealLogMs = 0L      // last actual rider log (drives `{elapsed}`)
@Volatile private var lastLogMs = 0L          // bumped on logs AND time-alert fires (F1)
@Volatile private var lastTimeAlertFireMs = 0L     // pure time-grid clock (v17)
@Volatile private var lastDeficitAlertFireMs = 0L  // deficit reminder cooldown clock (v17)
@Volatile private var activeIntegrationMs = 0L     // for session-average burn rate
@Volatile private var lastZoneSnapshot = ZoneSnapshot(NONE, -1, 0, 1f)
@Volatile private var lastPeriodicLogMs = 0L
```

All fields are `@Volatile` because they're written from Karoo SDK callbacks and read from the tick coroutine. `lastRealLogMs` and `lastLogMs` deliberately diverge: a time-alert fire bumps `lastLogMs` (so the next interval gate measures from the alert, not from the last meal — F1) while `lastRealLogMs` only moves when the rider actually taps a log, so the `{elapsed}` token in alert text reflects time since the rider last ate.

### Per-tick integration

Every 15 s the tick coroutine delegates the gates to two pure helpers — `CarbBurnEstimator` for the burn rate and `CarbIntegrator` for the movement gate, GPS-stale freeze, absorption-cap clamp, and the active-time accumulator:

```kotlin
val burn = CarbBurnEstimator.estimate(
    hrBpm    = lastHrBpm,
    powerW   = lastPowerW,
    profile  = lastUserProfile,
    riderAge = config.riderAge,
    riderSex = config.riderSex,
)
lastZoneSnapshot = burn.zoneSnapshot

val stale = lastSpeedChangeMs > 0 && (now - lastSpeedChangeMs) > SPEED_STALE_MS
val dtMs  = if (lastTickMs == 0L) 0L else (now - lastTickMs).coerceAtLeast(0L)
val step  = CarbIntegrator.integrate(
    burnGph    = burn.gph,
    dtMs       = dtMs,
    speedKmh   = lastSpeedKmh,
    speedStale = stale,
)
cumBurnedG          += step.deltaG
activeIntegrationMs += step.deltaActiveMs
lastTickMs = now
```

The `lastTickMs == 0L` guard makes `CarbIntegrator` return all-zero deltas on the first tick (no prior timestamp to bracket the dt). NTP stepping the clock backwards is also handled — `dtMs.coerceAtLeast(0L)` floors the dt at 0.

### CarbIntegrator gates

| Gate | Effect | Why |
|---|---|---|
| **Movement** (`speedKmh >= 2.0`) | Below 2 km/h → freeze | Cycling only burns what you replace when moving. Bench tests and traffic-light stops don't accumulate. |
| **GPS-stale** (`now − lastSpeedChangeMs > 10 s`) | Stuck speed → freeze | The Karoo SDK replays the last known value when GPS lock is lost in a tunnel / forest. Trusting the stuck value would integrate during a long tunnel even after the rider stopped inside it. |
| **Absorption cap** | `effectiveGph = min(burn.gph, 90)` | Recreational gut ceiling. |
| **Active-time gate** | `activeIntegrationMs +=` only when `effectiveGph > 0` | A movement-gate-passing tick with confidence=NONE adds 0 to cumBurnedG AND must NOT count toward the session-average denominator, or the "Pair HR/Pwr" hint never fires for HR-less riders. |

### Alert evaluation

**Two combinable alert modes**, each with its own cooldown clock. Per-source clocks were introduced in v17 — pre-v17 a single shared `lastAlertMs` meant a deficit fire reset the time-alert clock and vice versa.

```
Deficit alert (CarbsTracker.evaluateDeficitAlert):
   if carbDeficitAlertEnabled
      AND (cumBurnedG − cumLoggedG) >= carbDeficitThresholdG
      AND (now − lastDeficitAlertFireMs) >= carbDeficitReminderIntervalMin × 60_000 × backoff
                                            (default 10 min; backoff = 1 / 2 / 4, see below)
      AND (deficit-initial-delay grace passed; see below)
   → fire

Time alert (CarbsTracker.evaluateTimeAlert via FuelingAlertScheduler):
   Pure-interval grid: ticks at sessionStartMs + N × intervalMs.
   The rider's logs no longer shift the grid. The initial-delay
   FILTERS ticks whose timestamp would be earlier than
   sessionStartMs + initialDelayMs (the grid stays anchored to
   session start).
   if carbTimeAlertEnabled AND a grid tick is due AND no fire stamped at this tick
   → fire
```

### Unacknowledged back-off (deficit alerts only)

The reminder cooldown alone has no ceiling. The deficit only ever grows for a rider who doesn't log, so every interval past the threshold re-fires for the rest of the ride. The 2026-07-22 field sweep measured it across the whole corpus, counting only `source=deficit` fires with no intervening log:

| unacknowledged deficit alerts in one run | span | install / session |
|---|---|---|
| 37 (carb) + 36 (hydration) | ~3 h | `f16a4e_878bca` (v2.1.7) |
| 31 (carb) + 30 (hydration) | ~2.5 h | `327846_cc0b6f` (v2.1.4) |
| 23 (hydration) | 3 h 41 | `bd5fc1_50c9b5` (v2.0.0) |
| 13 (hydration) | 60.5 min | `1136e7_b6777a` (v2.2.0) |

**101 of the 122 sessions with fueling events have a run of ≥4.** The `f16a4e` case is the ceiling: both channels on a 5-min cadence, ~73 prompts in three hours — roughly one every 2.4 minutes. This is not a corner case, it's the default experience of a rider who doesn't log. See `docs/calibration-annotations.md`.

`FuelingAlertScheduler.shouldFireDeficit` therefore multiplies the cooldown by an unacknowledged-fire ladder:

| deficit alerts since the rider's last log | effective cooldown | with the 10-min default |
|---|---|---|
| 0 or 1 | ×1 | 10 min |
| 2 | ×2 | 20 min |
| 3 or more | ×4 (cap) | 40 min |

- **Capped, never silenced.** A rider who never logs still gets a prompt every 4 intervals — dehydration matters most on exactly the long rides where the back-off engages. There is no "stop after N" mode.
- **Any log resets it.** The trackers hold `deficitFiresSinceLog` and re-zero it whenever `lastRealLogMs` moves — anchoring on that timestamp (rather than resetting inside each log path) covers `logEntry`, `logAmount`, the in-alert LOG button and `undoLastForSlot` for free, including any log path added later. Verified: `lastRealLogMs` is written **only** by `start` (session seed / restore), `logEntry`, `logAmount` and `undoLastForSlot` — no alert path touches it (I8), so a fire can never silently reset its own back-off.
- **Scope: deficit alerts only.** The time-alert grid is deliberately untouched. "Remind me every N minutes" is an explicit rider contract and the grid is documented as log-independent; backing it off would break both. A rider who wants fewer time reminders lengthens the interval. (Corpus split: 1261 deficit fires vs 283 time fires, so the deficit channel is where the noise lives.)
- **The two channels back off independently.** A rider running carb + hydration deficit alerts on the same cadence still gets two ladders, so the combined rate is halved rather than quartered. Cross-channel coordination would mean merging the two trackers' cooldown state — not worth it unless the field logs say otherwise.
- **Lifetime.** The trackers are instantiated once by `KSafeExtension` and `start()`ed per ride, and the counter is in-memory. A **new ride** reseeds `lastRealLogMs`, so the anchor mismatches and the ladder resets — correct. A **pause/resume** (RideState pause→resume, master-switch OFF→ON) goes through `resume()`, which touches neither the counter nor `lastRealLogMs`, so the anchor still matches and the ladder is *preserved* — also correct (same rider, same ride, still not logging). The one exception is `resume()`'s no-live-session fallback, which delegates to `start()` and therefore resets. Only a process restart drops it otherwise. Persisting it would need a `CarbFuelingState`/`HydFuelingState` field plus a `CONFIG_VERSION` bump; not worth it for that one case.
- **Upgrade path (ponytail ceiling).** `MAX_BACKOFF_SHIFT = 2` caps at ×4. On a 5-min base that is a prompt every 20 min, which still leaves the `f16a4e` profile at ~11 prompts per channel over 3 h. If the next sweep still shows runs ≥10 on v2.2.0+, raise it to 3 (×8) — one constant, no other change.

**Coincidence resolution.** When both a deficit AND a time tick are due in the same physical tick, the deficit alert wins — its numeric "behind N g" is more actionable than a "X min since last" reminder, and both ask for the same rider action (eat). The time tick is consumed silently (the grid is still advanced) so the rider doesn't hear two beeps in quick succession.

### Initial delay (both deficit and time alerts)

Both alert paths have a **per-tracker initial grace period**. The motivation:

- **Time alert**: most riders don't eat or drink in the first 20-30 minutes of a multi-hour ride; firing a "time to eat!" alert at minute 25 of a 4-hour effort is a nag, not safety.
- **Deficit alert**: the integrator runs from t=0, so on a fresh ride the deficit crosses threshold purely from elapsed time without any rider misconduct. Without this gate the rider sees a "behind 25 g" nag at minute ~25 of a fresh ride, which reads as the app malfunctioning.

```kotlin
// Deficit alert (CarbsTracker.evaluateDeficitAlert):
val isFirstDeficitAlert = lastDeficitAlertFireMs == 0L && cumLoggedG == 0
if (isFirstDeficitAlert && carbDeficitInitialDelayMin > 0) {
    if ((now - sessionStartMs) < carbDeficitInitialDelayMin × 60_000) return
}

// Time alert (via FuelingAlertScheduler.currentDueTimeTick):
// initialDelayMs FILTERS grid ticks earlier than (sessionStartMs + initialDelayMs).
// The grid itself stays anchored at sessionStartMs + N × intervalMs — rider logs
// do not shift it.
```

**Per-source clocks (v17).** The grace period applies to the **first alert of each source**. A deficit fire bumps `lastDeficitAlertFireMs` only; the time-alert initial-delay gate continues to evaluate against `lastTimeAlertFireMs == 0L` independently. Pre-v17 a single shared `lastAlertMs` meant a deficit fire effectively released the time-alert gate too — that coupling is gone, the source clocks are fully independent. The shared release condition is still rider logging: once `cumLoggedG > 0` (or `cumLoggedMl > 0`), the grace gate of BOTH sources falls open because the rider is now actively fueling and the "fresh-ride grace" rationale no longer applies.

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

After substitution, the rendered string is capped at the call site so the popup cannot run off the Karoo screen: titles at `ALERT_TITLE_MAX_CHARS = 40` and details at `ALERT_DETAIL_MAX_CHARS = 34` (defined in `extension/util/AlertTextRenderer.kt`; the detail cap was reduced from 90 after on-hardware measurement of where the popup truncates). When the cap kicks in the last visible char is replaced with `…`. The cap applies only to the on-screen `InRideAlert` — the outgoing emergency message sent through the configured provider (Pushover / Telegram / ntfy / CallMeBot / Apprise) uses its own separate template (`config.message` etc.) and has no such limit.

| Token | Substituted with |
|---|---|
| `{deficit}` | Current carb deficit in grams (`cumBurnedG − cumLoggedG`, integer) |
| `{elapsed}` | Minutes since last **real** rider log (`lastRealLogMs`, not bumped by time-alert fires) |
| `{target}` | Instantaneous burn rate in g/h (post-absorption-cap). Pre-v18 this was the rider-configured `carbTargetGperHour`; that field is gone — `{target}` now reflects the **physiological burn the rider is producing right now**, which is the closest semantic match. |

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

`HydrationTracker.kt` mirrors `CarbsTracker` structurally but is **simpler** — there is no biosensor for sweat rate so the model stays target-based:

- **No physiological burn estimator.** The rider sets `hydrationTargetMlPerHour` (default 750 ml/h) directly. Raising it for hot days remains a manual step.
- **Optional dynamic estimator.** `dynamicHydrationEnabled` switches on `SweatEstimator` (HR + power + weight + ambient temperature + humidity from Headwind, when available); otherwise the flat per-hour rate applies. Anchors target the literature median (Sawka 2007 / Baker 2017) with a small (~5–10 %) conservative bias — comparable to Garmin's Firstbeat HeatStress targeting. See `SweatEstimator.kt` `heatFactor` for the WBGT-anchored curve.
- **No HR / power consumed in the default path** (the dynamic estimator does consume them).
- **2 logging slots** instead of 3.
- Same dual-mode alerts (deficit + time) with the same configurable reminder cooldown (`hydrationDeficitReminderIntervalMin`, default 10 min), the same unacknowledged back-off ladder, and the same grid-aligned time alert.
- Same initial-delay grace period and same custom-title option as carbs, with their own per-tracker config fields.

### Per-tick integration

```kotlin
if (lastTickMs != 0L && moving) {
    val dtSec = (now - lastTickMs).coerceAtLeast(0L) / 1000f
    val ratePerSec = effectiveMlPerHour / 3600f       // flat target OR SweatEstimator
    cumTargetMl += dtSec * ratePerSec
}
```

`effectiveMlPerHour` is `hydrationTargetMlPerHour` in the static path; in the dynamic path it comes from `SweatEstimator.estimate(...)`. The movement gate (`speedKmh >= 2.0`) and GPS-stale freeze apply on the hydration side too — same rationale as carbs. The two trackers are kept as separate classes because the burn-estimator path materially differs between them (real physiology on the carb side, target on the hydration side).

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

Tap behaviour: a `PendingIntent` fires a unique broadcast action (`com.enderthor.kSafe.TAP_CARB_LOG_$slot`) → `FieldTapReceiver` → `KSafeExtension.handleCarbLogTap(slot)`. The state machine for the slot is `IDLE → LOGGED (6 s window, tappable, hint "TAP UNDO") → IDLE` on timeout, or `IDLE → LOGGED → (tap during window) → UNDONE (1.5 s red flash) → IDLE` if the rider taps the same slot a second time within the 6 s window. The undo path calls `tracker.undoLastForSlot(slot)` which reverses the cumulative grams **and** restores the previous `lastLogMs` so a time-based alert clock isn't perturbed by the bad entry. The pending revert `Job` is stored per slot in `KSafeExtension.carbTapRevertJobs[]` and cancelled before launching a new one, so a stale `LOGGED → IDLE` timer from an earlier tap cannot clobber a fresher state set by a subsequent tap on the same slot.

### Hardware buttons (BonusActions, SRAM AXS only)

Two BonusActions registered: *"KSafe: Log Carb"* and *"KSafe: Log Drink"*, both wired to **slot 1** of each category. The rider maps them to AXS shifter buttons. Logging without looking at the screen.

### In-alert logging button (overlay)

A fueling alert can optionally surface as a **tappable overlay** with a one-tap **LOG** button (and an optional **UNDO** follow-up), so the rider logs the suggested item straight from the alert without finding its field. Controlled by `KSafeConfig.fuelingAlertButtonMode` (`OFF` / `LOG` / `LOG_UNDO`; default `OFF`, which preserves the legacy InRideAlert-only behaviour — added in config **v22**).

Wiring: each tracker's `fireAlert` builds a `FuelingAlertRequest` (title, detail, a **lazy** `inRideAlert` factory, the `FuelingChannel`, and the chosen `item`) and hands it to `KSafeExtension.presentFuelingAlert` via the `onFuelingAlert` callback. The `item` is the whole `FuelSlot` (slot index + label + size) returned by `util/FuelItemSelector.pickFuelItem` — the enabled slot whose size is closest to the current deficit (lowest index on ties), or the first usable slot for a time-based alert; `null` when no slot has size > 0. Carrying the `FuelSlot` (not a bare slot index) lets the presenter format the prompt from `item.label`/`item.size` without re-deriving the slot→config mapping, and freezes the shown amount at fire time.

`util/FuelingPresentation.decideFuelingPresentation(mode, canDrawOverlays, emergencyIdle, hasUsableSlot)` (pure, unit-tested) picks the channel:

| Condition | Result |
|---|---|
| `!emergencyIdle` (a crash / SOS countdown / alert is active) | **`SUPPRESS`** — present nothing |
| `!canDrawOverlays` **or** `!hasUsableSlot` (no overlay permission / no loggable slot) | `INRIDE_ALERT` |
| `mode == OFF` | `INRIDE_ALERT` |
| `mode == LOG` | `OVERLAY_LOG` |
| `mode == LOG_UNDO` | `OVERLAY_LOG_UNDO` |

Key behaviours:

- **Emergency priority.** A fueling alert that comes due while `EmergencyManager.uiState.status != IDLE` is **deferred at the tracker**: `evaluateDeficitAlert` / `evaluateTimeAlert` check the injected `isEmergencyActive` *after* deciding the alert is due and return without firing — so **no beep**, and the cooldown timestamp is **not** stamped, so the alert re-fires on the next tick once the emergency clears (held back, not lost). Per-tick integration keeps running, so the deficit stays accurate. As defense-in-depth, `decideFuelingPresentation` still returns `SUPPRESS` (no overlay, no InRideAlert) if an alert ever reaches the presenter during an emergency, `FuelingOverlayManager.showPrompt` re-checks `emergencyActive() || !rideActive()` inside its deferred main-thread post (`abortIf`), and a `uiState` collector tears down any overlay shown in the instant before the transition.
- **Field state stays in sync.** The overlay LOG/UNDO routes through the **same** helpers as a field tap (`logCarbSlot` / `undoCarbSlot` and the hydration equivalents — thin wrappers over a shared `logFuelSlot` / `undoFuelSlot`), so the on-ride `CarbLog`/`HydrationLog` field flashes `LOGGED`/`UNDONE` and the 6 s / 1.5 s revert jobs are managed (pending one cancelled first) identically whether the rider logged from the field or the overlay.
- **Named item.** The overlay detail leads with the exact item the button will record (e.g. *"Gel 25 g — <reason>"*, `fueling_overlay_log_detail`), and the `LOG_UNDO` follow-up confirms it (*"Logged Gel 25 g"*, `fueling_overlay_logged`), so the rider is never tapping a blind "Log".
- **No phantom log (empty slot).** When `pickFuelItem` returns `null` (all slots size 0) the alert falls back to a plain `InRideAlert` with no button, instead of logging a 0 g/ml entry into slot 1.
- **No post-ride log.** The overlay is torn down when the ride ends (`RideState.Idle`, mirroring the emergency teardown), and every fuel-write — `logFuelSlot` / `undoFuelSlot` and the combined-field `handleCombinedLogTap` — is gated on `rideActive()` (`currentRideState` is `Recording`/`Paused`, set synchronously at the top of `handleRideState`). So a tap landing in the sub-frame window before the async overlay removal runs is a no-op instead of logging into the just-closed session. The gate is the **real ride state**, deliberately not the persisted master switch (`activeConfig.isActive`, still `true` after a normal ride end) nor the per-tracker `enabled` flag (gating UNDO on it would strand the logged grams in the cumulative total).

`FuelingOverlayManager` is a standalone `SYSTEM_ALERT_WINDOW` overlay, deliberately separate from `SosOverlayManager` so the safety-critical SOS overlay cannot be affected by changes here.

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

## FIT export — fueling + wellness developer fields

KSafe writes seven developer fields into the Karoo's FIT file so the rider's activity in Strava / Intervals.icu / TrainingPeaks carries native graphs of fueling and cardiac decoupling alongside HR / power / cadence — coaches can correlate substrate / hydration / wellness with effort directly without exporting a separate CSV.

### SDK surface

The `karoo-ext` SDK exposes `KarooExtension.startFit(emitter: Emitter<FitEffect>)`. The Karoo OS calls this once at FIT-pipeline start (typically a moment before `RideState.Recording`); the extension keeps emitting `FitEffect` instances for as long as the ride lives, and registers a cancellation hook for tear-down. Two effect types are used:

| Effect | When | Lands in |
|---|---|---|
| `WriteToRecordMesg(values)` | Tracker / wellness value changes | A FIT `record` message — a per-timestamp sample alongside HR / power |
| `WriteToSessionMesg(values)` | Tracker / wellness summary changes | The FIT `session` message — the activity's headline / summary entry (last value wins) |

Both take a `List<FieldValue>`, where each `FieldValue(developerField, value: Double)` pairs a custom field with its current value.

### Developer fields

All seven fields are float32 (`fitBaseTypeId = 136`) and live in developer-data index 0. The field-definition numbers are **public API** — once shipped they cannot move because tools that learned the schema from a rider's earlier FIT file would otherwise misinterpret new files.

| # | Field name | Units | In record | In session | Source |
|---|---|---|---|---|---|
| 0 | `ksafe_carbs_g` | g | ✅ | ✅ | `CarbsTracker.cumLoggedG` — total rider-logged carbs |
| 1 | `ksafe_hyd_ml` | ml | ✅ | ✅ | `HydrationTracker.cumLoggedMl` — total rider-logged fluid |
| 2 | `ksafe_hr_drift_pct` | % | ✅ | — | `WellnessMonitor.currentDriftPct` — instantaneous cardiac decoupling |
| 3 | `ksafe_max_drift_pct` | % | — | ✅ | Peak cardiac decoupling reached during the ride |
| 4 | `ksafe_wellness_fires` | count | — | ✅ | Number of wellness alerts that fired |
| 5 | `ksafe_carbs_burned_g` | g | ✅ | ✅ | `CarbsTracker.cumBurnedG` — total estimated physiological carb burn |
| 6 | `ksafe_carb_burn_rate_gph` | g/h | ✅ | — | `CarbsTracker.burnRateGph` — instantaneous burn rate (post-cap) |

`#7` is **reserved** — the session-average burn rate is derivable downstream from the `#6` time series, so writing it again would just duplicate information for 4 bytes.

### Write-on-change throttle

The collector pulses on the Karoo's `DataType.Type.ELAPSED_TIME` stream (1 Hz native cadence — same tick as the HR / power records), but each write is **gated on actual value change**:

```kotlin
val recChanged =
    carbsG != lastRecCarbsG       ||
    hydMl  != lastRecHydMl        ||
    carbsBurnedG != lastRecCarbsBurnedG ||
    burnRateGph  != lastRecBurnRateGph  ||
    driftPct     != lastRecDriftPct
if (recChanged) {
    emitter.onNext(WriteToRecordMesg(listOf(...)))
    // ... update cached values ...
}
```

The cache initial value is `Double.NaN` — `NaN != NaN` is true in IEEE 754, so the first tick of every ride always emits. The session message uses the same idiom against its own cache.

Why write-on-change instead of 1 Hz:
- `cumLoggedG` / `cumLoggedMl` are **step curves** — they only move on rider taps. Re-writing the same value every second produces ~18 000 identical records per 5 h ride.
- `cumBurnedG` / `burnRateGph` only update every 15 s (the integrator's tick cadence). 14 of every 15 same-second writes carry no new information.
- `currentDriftPct` updates every 30 s (`WellnessMonitor.MONITOR_TICK_MS`). 29 of 30 same-second writes are redundant.
- FIT consumers (Strava, Intervals.icu, TrainingPeaks) plot developer fields at the emitted timestamps and interpolate. A sparse series renders identically to a dense series that repeats values — but the dense series wastes the FIT file size and the per-record allocation budget.
- Audit 2026-05-25: pre-throttle the FIT writer accounted for ~50 K allocations/hour (record + session messages + 10 FieldValue per tick + listOf wrappers). Write-on-change brings this to ~3 K allocations/hour without touching the contract.

### Auto-pause / Idle handling

ELAPSED_TIME stops emitting while `RideState.Paused`, so a Paused-only branch would never fire. Both write paths sit inside the `Recording` branch; the session message therefore writes from the same tick as the record. Whatever value is current at FIT close is what becomes the activity-summary header in Strava et al. A side benefit of streaming off ELAPSED_TIME (rather than a `delay()` loop) is that when the Karoo sits idle on a desk between rides, no work happens at all — the previous `delay(1_000L)` ran ~86 k iterations/day even outside a ride.

Notes:
- **Float32** rather than uint16 so future enhancements (additional decimals, ride-fraction percentages) don't need a schema migration. Integer values up to a single ride's load (~1500 g, ~65 L) convert to float32 exactly.
- `nativeFieldNum = null` on every field because no native FIT field carries these semantics — they're pure developer fields.

### Tracker null-safety

`startFit` may be called before the rider opted into fueling — the `CarbsTracker`, `HydrationTracker` and `WellnessMonitor` are still null in that case. The collector reads via the existing `*OrNull()` accessors with `?: 0` fallback:

```kotlin
val carbsG       = (carbStatus?.cumLoggedG ?: 0).toDouble()
val carbsBurnedG = (carbStatus?.cumBurnedG ?: 0).toDouble()
val burnRateGph  = (carbStatus?.burnRateGph ?: 0).toDouble()
val hydMl  = (hydrationTrackerOrNull()?.getStatus()?.cumLoggedMl ?: 0).toDouble()
val driftPct    = wellness?.currentDriftPct?.toDouble() ?: 0.0
```

A flat-zero column in the FIT is honest data ("no fueling logged") and lets a rider who enables fueling mid-season backfill cleanly without a config drift.

### Toggle and hot-toggle limitation

`KSafeConfig.fuelingFitExportEnabled` (default `true`) gates the writer. When false, `startFit` calls `setCancellable { }` and returns immediately — no coroutine spawned. The toggle is sampled once at FIT-pipeline start; flipping it mid-ride takes effect only on the next ride. A hot-toggle would be premature complexity for a setting riders almost never flip mid-ride.

### Cost

| Concept | Per ride (5 h, post-throttle) |
|---|---|
| Record + session IPCs (write-on-change, ~1.2 K record + ~0.3 K session writes) | ~0.1 s CPU total |
| Allocations from FIT writer (`FieldValue` + `WriteToRecordMesg/SessionMesg` + lists) | ~3 K/hour (was ~50 K/hour before write-on-change) |
| Disk: ~28 bytes extra per FIT record (5 float32) | ~3 KB total — sparse series |
| Battery overhead | <0.05 % (imperceptible) |

Negligible against the ride app's own write throughput. The toggle exists for riders who don't want extra developer columns in their FIT, not for battery reasons.

---

## Concurrency, lifecycle, performance

### Threading

Same model as the medical/wellness detectors:

- **Sensor input writes** (`updateHr`, `updatePower`, `updateUserProfile`, `updateSpeed`) happen on Karoo SDK callback threads. Volatile-only writes.
- **Tick coroutine** (every 15 s) runs on the extension's `Main + SupervisorJob` scope. Reads Volatiles, calls `CarbBurnEstimator.estimate` + `CarbIntegrator.integrate`, evaluates alerts.
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

- Tick allocation: one `BurnEstimate` + one `ZoneSnapshot` + one `IntegrationStep` per tick (carbs only) + at most one `String` per alert dispatch. Hot path is <100 µs. The zone classifier runs **once per tick** — pre-v18.1 it ran twice (CarbsTracker called it directly and `CarbBurnEstimator.estimate` re-classified internally); fixed by `BurnEstimate.zoneSnapshot`.
- Status data field updates: push-based via `CarbsTracker.statusFlow` / `HydrationTracker.statusFlow`. Emissions are tick-rate (15 s) plus event-driven (rider logs, movement-gate transitions). Pre-v18.0 the data fields polled at 1 Hz and allocated a fresh `CarbStatus` every second; the push flow cut that to ~4 emissions/min per visible field.
- `CarbBurnEstimator.estimate()` / `CarbIntegrator.integrate()`: pure functions, no state, no I/O.
- Calibration logging lambdas: inert when disabled (Volatile boolean check, lambda body never evaluated).

---

## Calibration Logging

| Event (CSV tag) | Fields |
|---|---|
| `FUELING_CARB_START` (`CARB_START`) | `cum_burned_g, cum_logged_g, tier_at_start` |
| `FUELING_CARB_LOGGED` (`CARB_LOG`) | `slot, grams, cum_logged, cum_burned` |
| `FUELING_CARB_UNDONE` (`CARB_UNDO`) | `slot, grams (negative — the reversal amount), cum_logged, cum_burned` |
| `FUELING_CARB_FIRED` (`CARB_FIRE`) | `source(deficit|time), deficit_g, since_log_min, cum_burned, cum_logged, zone, confidence, kcal_h, cho_fraction` |
| `FUELING_CARB_PERIODIC` (`CARB_PERIODIC`) | every 2 min: `cum_burned, cum_logged, deficit, zone_source, zone_idx, zone_total, confidence, kcal_h, cho_fraction, hr, power` |
| `FUELING_HYDRATION_LOGGED` (`HYD_LOG`) | `slot, ml, cum_logged, cum_target` |
| `FUELING_HYDRATION_UNDONE` (`HYD_UNDO`) | `slot, ml (negative — the reversal amount), cum_logged, cum_target` |
| `FUELING_HYDRATION_FIRED` (`HYD_FIRE`) | `source, deficit_ml, since_log_min, cum_target, cum_logged` |
| `FUELING_HYDRATION_PERIODIC` (`HYD_PERIODIC`) | every 2 min: `cum_target, cum_logged, deficit` |

v18: the legacy `multiplier=` field is gone from `CARB_FIRE` and `CARB_PERIODIC`. It was vestigial after the integrator switched from `base × multiplier` to the physiological estimator; the new load-bearing signals are `confidence` (which tier ran), `kcal_h` (the kcal/h that drove the integration step) and `cho_fraction` (Romijn / Jeukendrup table lookup at the current zone). The CSV column header was updated; older logs still parse — the column slots a `multiplier` value into a `confidence` header which is wrong but is also recognizable as legacy v17 data.

> **Counting intakes:** a parser that wants "how many times did the rider tap log" should filter by the `_LOGGED` tags only — `_UNDONE` rows are reversals, not intakes. A parser that sums `grams` / `ml` across **both** `_LOGGED` and `_UNDONE` rows nets out correctly (the negative undo cancels the original positive log). The distinct tag exists precisely so the two analyses don't conflict.

The 2-minute cadence of `*_PERIODIC` matches the existing crash-detection `PERIODIC` event so calibration analysis can correlate timelines by timestamp without modifying crash code.

---

## Open Items / Future Work

- **Soft-fall detection via fueling state.** A rider with high carb deficit + low recent intake who suddenly has an accel impact below the smoothed crash threshold could be a candidate for HR-confirmed soft-fall handling. Requires expanding the crash detector's trigger paths — out of scope for v1.
- **Power-meter battery awareness.** If the power meter sensor reports low battery, the burn estimator should explicitly downgrade from Tier 1 (POWER) to Tier 2/3 (HR-based). Currently it uses whichever data is flowing; a dying power meter that emits 0 W is read as "rider is freewheeling", under-counting burn.
- **Adaptive deficit threshold.** A future iteration could learn from logged intake across rides ("you consistently let the deficit grow to 40 g before logging — consider lowering threshold"). Out of scope for v1.
- **Dynamic sweat-rate refinement.** `SweatEstimator` already accepts ambient temperature and humidity when Headwind is paired. Future: validate the formula against real-rider field data and expose tuning knobs.
- **GI-distress upper bound.** Currently nothing alerts the rider if they over-consume. The intestinal absorption ceiling is ~90 g/h; sustained intake above that often causes GI issues. Out of scope per the original spec, but worth re-evaluating with calibration data.
- **`ZoneSnapshot.multiplier` field removal.** Still present in the data class for backwards compat with the v17 calibration CSV column header. Schedule for removal once enough v18-shipped logs have accumulated that the historical analysis pipeline can drop the legacy column.
- **Inter-app integration.** Other Karoo extensions might want to consume the carb / hydration state. Requires a defined contract — see future spec.

---

## Configuration Reference

All config fields live in `KSafeConfig` (`data/ConfigData.kt`).

### Rider physiology (Tier 2 Keytel inputs)

| Field | Default | UI exposed |
|---|---|---|
| `riderAge` | `0` (= not entered → Tier 2 unavailable, falls back to Tier 3 Swain) | ✅ — Settings → Fueling |
| `riderSex` | `RiderSex.NOT_SET` (= same fallback as above) | ✅ — radio (Male / Female / Unset) |

Riders who don't fill these in still get a useful carb estimate via Swain. Both fields landed in v18; old saved configs deserialise with both at the default and the tracker silently runs Tier 3 until the rider opens Settings.

### Carbs tracker

| Field | Default | UI exposed |
|---|---|---|
| `carbsTrackerEnabled` | `false` (opt-in master — gates all sub-fields and collapses them when off) | ✅ |
| `fuelingAlertButtonMode` | `OFF` (**global** — applies to carb **and** hydration alerts; `OFF` / `LOG` / `LOG_UNDO`; added v22) | ✅ — Fueling tab, own card. See [In-alert logging button](#in-alert-logging-button-overlay). |
| `carbDeficitAlertEnabled` | `true` | ✅ |
| `carbDeficitThresholdG` | 25 g | ✅ |
| `carbDeficitInitialDelayMin` | 30 | ✅ (0 = off — fire as soon as threshold crossed) |
| `carbDeficitReminderIntervalMin` | 10 | ✅ (5 / 10 / 15 / 20 / 30 — replaces the pre-v17 hard-coded 5 min cooldown) |
| `carbTimeAlertEnabled` | `false` | ✅ |
| `carbTimeIntervalMin` | 25 | ✅ (1-60 min) |
| `carbTimeInitialDelayMin` | 30 | ✅ (0 = off; filters grid ticks below this offset) |
| `carbAlertBgColor` | `FUELING_ALERT_COLOR_ORANGE` | ✅ — swatch picker (6 colours) |
| `carbBeepPattern` | `SINGLE_LONG` | ✅ — beep pattern picker |
| `carbAlertCustomTitle` | `""` (use default) | ✅ |
| `carbAlertCustomDetailTime` | `""` (use source-specific default) | ✅ — supports `{deficit}`, `{elapsed}`, `{target}` |
| `carbAlertCustomDetailDeficit` | `""` (use source-specific default) | ✅ — same tokens |
| `carb1Label` / `carb1Grams` / `carb1Color` / `carb1Icon` | "Gel" / 25 / palette-blue / 🧴 | ✅ (per-slot row + colour & icon pickers) |
| `carb2Label` / `carb2Grams` / `carb2Color` / `carb2Icon` | "Bar" / 30 / palette-blue / 🍫 | ✅ |
| `carb3Label` / `carb3Grams` / `carb3Color` / `carb3Icon` | "Fruit" / 20 / palette-blue / 🍌 | ✅ |

**Removed in v18:** `carbTargetGperHour`, `CarbRidePreset` enum, the Casual / Endurance / Race preset chips. The rider no longer sets a per-hour target — burn comes from physiology.

### Hydration tracker

| Field | Default | UI exposed |
|---|---|---|
| `hydrationTrackerEnabled` | `false` (opt-in master — same gating as carbs) | ✅ |
| `hydrationTargetMlPerHour` | 750 | ✅ |
| `dynamicHydrationEnabled` | `false` | ✅ — switches to `SweatEstimator` (HR + power + weight + ambient temperature + humidity from Headwind, when paired) |
| `hydrationDeficitAlertEnabled` | `true` | ✅ |
| `hydrationDeficitThresholdMl` | 300 ml | ✅ |
| `hydrationDeficitInitialDelayMin` | 30 | ✅ (0 = off) |
| `hydrationDeficitReminderIntervalMin` | 10 | ✅ (5 / 10 / 15 / 20 / 30 — mirror of carb side) |
| `hydrationTimeAlertEnabled` | `false` | ✅ |
| `hydrationTimeIntervalMin` | 20 | ✅ (1-60 min) |
| `hydrationTimeInitialDelayMin` | 30 | ✅ (0 = off) |
| `hydrationAlertBgColor` | `FUELING_ALERT_COLOR_BLUE` (water-coloured by default) | ✅ — swatch picker (6 colours) |
| `hydBeepPattern` | `SINGLE_LONG` | ✅ — beep pattern picker |
| `hydrationAlertCustomTitle` | `""` | ✅ |
| `hydrationAlertCustomDetailTime` | `""` (use source-specific default) | ✅ — supports `{deficit}`, `{elapsed}`, `{target}` |
| `hydrationAlertCustomDetailDeficit` | `""` (use source-specific default) | ✅ — same tokens |
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

Internal: developer-field definitions and pacing live in `extension/KSafeExtension.startFit`. Field names `ksafe_carbs_g` / `ksafe_hyd_ml` / `ksafe_hr_drift_pct` / `ksafe_max_drift_pct` / `ksafe_wellness_fires` / `ksafe_carbs_burned_g` / `ksafe_carb_burn_rate_gph` and field definition numbers `0..6` are stable identifiers — do not change once shipped. `#7` is reserved.

Internal constants (`CarbIntegrator.MOVING_GATE_KMH`, `CarbIntegrator.SPEED_STALE_MS`, `ABSORPTION_CAP_GPH`, `MONITOR_TICK_MS`, `PERIODIC_LOG_INTERVAL_MS`, the Keytel / Swain coefficients in `CarbBurnEstimator`, etc.) are NOT exposed. Calibrated in code from the spec-defined values described above.
