# Health & Fueling Tabs — Full Reference

> Full configuration reference for the **Health** tab (HR-based detectors) and the **Fueling** tab (carb / hydration tracker). The README has a one-paragraph overview of each; this page covers every field, every tier, every alert mode, and the FIT-file integration.
>
> For the underlying algorithms (thresholds, baselines, hysteresis) see:
> - [Medical episode & wellness algorithms](medical-wellness-algorithm.md)
> - [Nutrition & hydration algorithms](fueling-algorithm.md)

---

## Health tab

> [!NOTE]
> **Available from v2.0.** The Health tab and all HR-based detectors (medical episodes, three-tier wellness monitor) require KSafe v2.0 or newer.

The Health tab adds two **HR-based detectors** that complement the accelerometer-based crash detection. Both are **optional** and only fire when a heart-rate sensor (ANT+ or BLE) is paired to the Karoo. Without HR data the detectors stay completely silent — no false negatives, no false positives.

> [!IMPORTANT]
> A heart-rate sensor is **never required** for KSafe to work. Crash detection, manual SOS, check-in timer, speed drop, and webhook actions all work without HR. The Health tab is purely additive — turn it on if you ride with a chest strap or optical HR monitor and want the extra detection layer.

### Medical episode detection

Watches the rider's HR for two patterns that strongly indicate a medical incident:

- **Flatline** — HR drops below 30 bpm for 30 continuous seconds while the rider is active. Catches asystole and severe bradycardia.
- **Collapse** — HR drops by ≥ 40% from the 5-minute rolling average within 10 seconds. Catches vasovagal syncope and other events where the heart keeps beating at a low rate (where flatline alone wouldn't fire).

Both checks have built-in guards: HR data must be fresh (sensor connected within the last 15 s), and the rider must have been moving above 5 km/h within the last 60 seconds. This avoids spurious alerts when the Karoo is sitting on a desk with a paired strap nearby, or when the sensor briefly disconnects.

**Default**: enabled. Response level **Emergency** — same flow as a crash detection (configurable countdown + alert to contacts). The alert message uses your standard emergency message template; the `{reason}` placeholder reads "Medical episode detected".

You can also override the on-screen popup's title and detail per rider — see [Custom alert text](#custom-alert-text) below.

### Wellness monitor

A three-tier HR-based fatigue / overexertion / heat-stress monitor. Each tier is independent and can be turned on or off in the Health tab; the master toggle gates the whole monitor. Defaults are conservative — turn on the tier(s) that match your goals.

| Tier | What it watches | Default trigger | Why |
|---|---|---|---|
| **Critical HR** | HR very high for a short time | 95 % of max HR (or 175 bpm absolute) sustained for **5 min** | Acute overexertion — early warning. |
| **Sustained HR** | HR moderately high for a long time | 92 % of max HR (or 180 bpm absolute) sustained for **30 min** | Long-tail fatigue — same rule that existed before, kept for back-compat. |
| **Cardiac decoupling** | HR / power ratio drift vs. baseline | 7 % drift sustained for **10 min** (after a 10-min baseline) | Clinical indicator of dehydration / heat stress; requires a paired power meter (auto-skips when no power data). |

The HR-based tiers can use either **absolute bpm** thresholds (the legacy mode) or **% of max HR** read from the Karoo profile (auto-scales across riders, no biometric entry needed). The decoupling tier always uses % drift relative to the rider's own ride-specific baseline, so the absolute / % toggle does not apply to it.

The streak resets if the watched signal drops below the tier's threshold even briefly — only **continuous** sustained excursions trigger the alert, not cumulative time. Per-tier cooldowns prevent spam during long climbs or sustained interval workouts.

**Default**: master toggle off (opt-in — the right thresholds depend on rider age and fitness). When the master is on, all three tiers default to enabled. Response level **Warning** by default — on-screen notification + beep, **never** sent to emergency contacts unless you explicitly raise the response level to Emergency.

### Custom alert text

Every HR-based on-screen alert (medical, plus each of the three wellness tiers) has its own customisable **title** and **detail** in the Health tab. The fields show the built-in default text as a placeholder when empty — leave blank to use the default, or write your own message. The text is rendered with `{token}` placeholders substituted at runtime so you can include the live data in your own wording:

| Alert | Tokens you can use in title / detail |
|---|---|
| Medical | `{bpm}` |
| Wellness Critical / Sustained | `{bpm}`, `{threshold}`, `{minutes}` |
| Wellness Decoupling | `{drift}` (% drift, 1 decimal), `{minutes}` |

For example, a critical-HR detail of `"HR at {bpm} bpm — slow down ({threshold} for {minutes} min)"` will render at fire time as something like *"HR at 178 bpm — slow down (175 for 5 min)"*.

### Response levels

Each detector has a configurable response level:

| Level | What happens |
|-------|-------------|
| **Silent** | Logged to calibration data only — useful for testing without producing UI noise |
| **Warning** | On-screen notification + beep on the Karoo, no alert to emergency contacts. The beep is **configurable** — see [Beep patterns](#beep-patterns) — including Off if you want the alert overlay without any sound. |
| **Emergency** | Full crash flow: countdown + alert to contacts. Beep stays on the urgent default — not configurable by design, this is the safety-critical path. |

### Beep patterns

Karoo's SDK has no sound catalogue — every "different sound" is a sequence of synthesised tones. KSafe exposes a small set of presets so you can pick a different cadence per category rather than the same single long beep for everything:

| Preset | Pattern |
|---|---|
| **Off** | No sound — visual `InRideAlert` only |
| **Single long beep** | 880 Hz × 800 ms (default — historical behaviour) |
| **Double short pip** | 880 Hz × 150 ms, 100 ms gap, again |
| **Rising triple chime** | 660 → 880 → 1100 Hz × 200 ms each |
| **Urgent pulse** | pip-pause-pip-pause-rising (same shape the emergency countdown uses) |

Three independent pickers:

- **Fueling tab → Carbs → Sound**: applies to carb deficit / time alerts.
- **Fueling tab → Hydration → Sound**: applies to hydration deficit / time alerts.
- **Health tab → wellness → Sound**: applies to all WARNING-level alerts (wellness sustained / critical / decoupling, plus any medical incident the rider downgraded to Warning).

Emergency-level countdown beeps (crash, medical-collapse on Emergency, the in-emergency urgent pulses) stay on the urgent default. Making them rider-mutable would defeat the safety guarantee that the Karoo grabs your attention when something serious happens.

### Privacy

Heart-rate readings are consumed only by the on-device detectors. They are **never sent to your emergency contacts** unless a medical episode actually triggers an alert — and even then, the alert message is your standard emergency template. Your raw HR value itself is not included in the outgoing message.

When calibration logging is enabled, anonymised HR data does appear in the local CSV (same handling as the accelerometer / cadence / grade data already collected) — see [Calibration Logging](calibration-logging.md).

---

## Fueling tab

> [!NOTE]
> **Available from v2.0.** The Fueling tab — carb / hydration tracker, log-slot fields, customisable alert templates, FIT export — requires KSafe v2.0 or newer.

> [!TIP]
> The Fueling tab is KSafe's **preventive safety layer**. The other safety features (crash detection, medical episodes, SOS) react *after* something has gone wrong. Fueling tries to keep things from going wrong in the first place: a rider who is properly fueled and hydrated has clearer judgment, faster reaction time, and fewer mistakes — and is much less likely to crash, blow up, or need to be rescued. Bonking and dehydration are real, common causes of cycling incidents, not just performance problems.

The Fueling tab is **fully optional**. It is **disabled by default** because the right targets depend on each rider. When you enable it, KSafe begins integrating a per-second carb and fluid target while you ride, watches what you log, and warns you when you fall behind.

### How it works (no biometrics required)

KSafe **does not measure your blood glucose or hydration in real time** — there is no sensor on a bike that can. Instead the model compares two numbers and alerts when they diverge too much:

| What | How it's obtained |
|------|---|
| **Target so far** | The per-hour rate you configure (e.g. 60 g/h for carbs, 750 ml/h for hydration), **integrated** over elapsed ride time. For carbs the rate is auto-modulated by HR / power zone (0.7×–1.3×). For hydration, optionally by the dynamic sweat-rate estimator (HR + power + weight + ambient temperature + humidity). |
| **Logged so far** | The sum of every log-slot tap you make during the ride. |

The difference is the **deficit**. When it exceeds the threshold you configure (defaults: 25 g for carbs, 300 ml for hydration) KSafe fires a beep + on-screen alert.

Three consequences worth understanding *before* you trust the alerts:

1. **The reference is the target you configure** — not a biometric measurement of you. Without a sensible per-hour target there is nothing to be behind on. The defaults (60 g/h, 750 ml/h) cover a 2–3 h endurance ride in mild weather, but they are not personal. See [Setting your initial targets](#how-to-pick-your-per-hour-targets) for the starting tables, then refine them with the [calibration workflow](#how-to-calibrate-against-a-real-ride) after 2–3 real rides.
2. **If you eat or drink without tapping a slot, KSafe doesn't know.** The integrated target keeps climbing; the deficit grows until you tap. A missed log is indistinguishable from a missed gel. The slot's [on-screen undo](#logging-in-ride) (a second tap within ~5 s) protects against the opposite mistake — an accidental tap that didn't correspond to a real intake.
3. **The model assumes you're riding "around" your configured intensity.** The zone multiplier handles surges and recoveries within the typical range, but if you spend two hours coasting downhill the integrated target overstates your actual burn, and a "deficit" alert isn't really telling you to eat. Likewise the hydration estimator is biased toward over-targeting in heat — see [SweatEstimator's accuracy notes](fueling-algorithm.md) for the rationale.

### How the carb target adapts to your effort

Carb burning depends heavily on intensity. KSafe reads your **HR zones** (5 zones, configured in your Karoo) and **power zones** (7 zones) directly from the Karoo's user profile — no manual entry of weight, FTP, max HR or anything else. From those zones it derives a real-time multiplier between **0.7×** (recovery / Z1) and **1.3×** (top zone) and applies it to your configured base target (e.g. 60 g/h):

| Setup | Multiplier | Notes |
|-------|------------|-------|
| Power meter + power zones configured | 0.7..1.3 from your power zone | Most accurate — power is the cleanest intensity proxy |
| HR sensor + HR zones configured (no power) | 0.7..1.3 from your HR zone | Good fallback |
| Out-of-range readings (below Z1 or above the last zone) | Clamped to nearest edge | Coasting at low HR → recovery rate; sprinting above last zone → top rate |
| Neither sensor present | 1.0 (neutral) | Tracker reverts to flat target × time — equivalent to "remind me every X min based on g/h" |

### How the hydration target adapts to your effort and the weather

Hydration is more dynamic than carbs because evaporative cooling depends on heat, humidity and intensity, not just time. KSafe offers two modes:

- **Flat per-hour target** (default) — same number every hour. You raise it manually for hot days.
- **Dynamic estimate** (optional toggle) — target varies with HR / power, body weight, ambient temperature and humidity. Uses [karoo-headwind](https://github.com/timklge/karoo-headwind) weather data when that extension is installed; otherwise the Karoo onboard temperature sensor (biased +3–8 °C) and 50 % assumed humidity. Expect ±25–35 % accuracy in the common HR + onboard configuration; closer to ±20 % with power meter + Headwind.

The dynamic mode biases **high** in hot conditions by design: the estimator's job is to set a hydration *target*, not measure your sweat. Under-targeting risks dehydration (heat illness, cramps, performance collapse); over-targeting costs at most a few extra sips. See `SweatEstimator.kt` for the full bias rationale.

### How to pick your per-hour targets

KSafe asks you for a base **carb target (g/h)** and a base **hydration target (ml/h)**, and then modulates the carbs automatically by intensity zone and (optionally) the hydration by HR/power + weather. Default values out of the box are **60 g/h** for carbs and **750 ml/h** for hydration — sensible for a 2–3 h endurance ride in mild weather, but **not optimal for everyone**. Here is how to ballpark your own numbers.

#### Carb target (g/h)

Sports-nutrition guidance scales with ride duration and intensity:

| Ride duration | Target g/h | Notes |
|---|---|---|
| < 60 min | 0–30 | Glycogen stores cover it; only fuel if you're going all-out from minute one |
| 1–2 h | 30–60 | Single-source glucose works (gel, drink, banana) |
| 2–3 h | 60–90 | Endurance standard. **KSafe default (60) is the bottom of this range** |
| 3–5 h | 75–100 | Need a glucose+fructose mix (multi-sugar) to break the 60 g/h gut-transporter limit |
| > 5 h / ultra | 90–120 | Only realistic with gut training; most riders top out at 80–95 |

**Bodyweight ceiling**: research puts the gut-trained max at roughly **1.0–1.2 g/kg/h**. A 75 kg rider can sustainably absorb up to ~90 g/h; a 60 kg rider up to ~70 g/h. Use `bodyweight_kg × 1.0` as a "going-hard" target.

**Intensity scaling is automatic**: once you set the base, KSafe's 0.7×–1.3× zone multiplier (table above) does the per-second adjustment. You don't need to bump the base for hard intervals — KSafe sees the HR or power and integrates more grams while you're in zone 4-5. The per-hour number you configure is **your target at threshold-ish effort**, not your peak.

#### Hydration target (ml/h)

Hydration is dominated by heat, humidity and bodyweight, much more than by intensity. Field-tested rate by conditions:

| Temperature | Suggested ml/h |
|---|---|
| < 15 °C (cool) | 400–600 |
| 15–22 °C (mild) | 600–800 |
| 22–28 °C (warm) | 800–1100 |
| 28–32 °C (hot) | 1100–1400 |
| > 32 °C (very hot) | 1400–1800 |

**KSafe default (750)** suits a mild day. For a summer MTB ride at 14:00 with the sun on your back, bump it manually to 1000–1200 — or, better, enable the **Dynamic estimate** toggle and let the sweat-rate model derive it from your HR/power, weight (from your Karoo profile), and the temperature/humidity stream. With [karoo-headwind](https://github.com/timklge/karoo-headwind) installed you also get real meteo humidity; without it KSafe falls back to the Karoo onboard temperature sensor (biased high by device self-heating — the estimator compensates by erring on the high side because under-targeting hydration is far more dangerous than over-targeting).

**Bodyweight scaling**: ~10 ml/kg/h in moderate heat, scaling up to ~20 ml/kg/h in extreme heat for a heavy sweater. A 60 kg rider in 25 °C might need 600 ml/h; a 90 kg rider in the same weather might need 900–1000 ml/h.

#### How to calibrate against a real ride

Defaults are a starting point — your true rate is rider-specific. The data fields make tuning straightforward:

1. **Set the base targets and ride** with the carb-status and hydration-status fields visible (and optionally the burn-rate + carbs-burned fields too).
2. **At 2 h, check the screen**: KSafe shows `cum target` (what it integrated), `cum logged` (what you actually consumed), and the deficit between them.
3. **Tune for the next ride**:
   - Felt **bonk-y** despite hitting your logged target → your true burn rate is higher than KSafe integrated. Increase the base 10–15 g/h.
   - Finished with a **full belly** and unused gels → drop the base 5–10 g/h.
   - **Light-headed / cramping** in warm conditions → increase the hydration target 100–200 ml/h, or enable Dynamic estimate.
   - **No symptoms, low deficit consistently** → defaults are working; no change needed.

After two or three calibration rides in varied conditions you'll have personalised numbers. The defaults are a starting hypothesis, not a prescription.

#### Measuring your personal sweat rate

The cheapest accurate calibration for hydration is to weigh yourself before and after a 1–2 h ride in representative conditions:

```
sweat_rate (L/h) = ((kg_before - kg_after) + litres_consumed) / hours
```

Subtract any urination volume if relevant. Repeat in cool, mild and hot conditions to build a personal curve — sweat rate can vary 2–3× between a 12 °C ride and a 32 °C ride for the same rider.

#### References and further reading

The tables above synthesise the following sources, which are the standard citations in the sports-nutrition literature and in the product copy of any serious hydration / fuelling brand:

**Carbohydrates**
- **Jeukendrup A. (2014)**. *A step towards personalized sports nutrition: carbohydrate intake during exercise.* Sports Medicine 44(Suppl 1):S25–S33. Open access. Origin of the duration-keyed ladder (30 → 60 → 90 → 120 g/h) and the multi-transportable-carbs rationale.
- **Thomas DT, Erdman KA, Burke LM (2016)**. *Position of the Academy of Nutrition and Dietetics, Dietitians of Canada, and the American College of Sports Medicine: Nutrition and Athletic Performance.* MSSE 48(3):543–568. The joint position statement most often cited in cycling federations' nutrition guidance.
- **[mysportscience.com](https://www.mysportscience.com)** — Jeukendrup's accessible blog; the carb-ladder infographics widely reused in cycling content originate here.

**Hydration**
- **Sawka MN et al. (2007)**. *ACSM Position Stand: Exercise and Fluid Replacement.* MSSE 39(2):377–390. Source of the "<2 % bodyweight loss" rule and of the by-intensity / by-environment ranges that this document's table mirrors.
- **Baker LB (2017)**. *Sweating Rate and Sweat Na+ Concentration in Athletes: A Review of Methodology and Intra/Interindividual Variability.* Sports Medicine 47(Suppl 1):111–128. Open access. Documents the 0.5–2.5 L/h real-world spread and supports the dynamic-estimator's deliberate upper-bound bias.

**Practical calculators (free)**
- **Precision Fuel & Hydration Knowledge Hub** — `precisionhydration.com/knowledge` — articles by Andy Blow, free online sweat-test that returns a personalised ml/h and a sodium ladder.
- **TrainingPeaks blog** — overlapping divulgation of the same methodology.

These are starting hypotheses, not prescriptions. KSafe's `SweatEstimator` (see [fueling-algorithm.md](fueling-algorithm.md)) explicitly biases the hydration target toward the upper end of the literature because under-targeting fluids carries far more risk (heat illness, cramps, judgment collapse) than over-targeting (a few extra sips).

### Two combinable alert modes

For each category (carbs, hydration) independently:

- **Alert by deficit**: silent until `(target_so_far − logged) > threshold` (e.g. >25 g for carbs, >300 ml for hydration). Then beep + on-screen `InRideAlert` (full-screen overlay, auto-dismissable in 10 s).
- **Alert by time**: silent until `time_since_last_log > interval` (e.g. >25 min for carbs, >20 min for hydration). Same alert UX.

The time alert also has a per-category **initial delay** (default 30 min, configurable, set to 0 to disable). Most riders don't eat or drink in the first 20 minutes; the initial delay prevents a nag at minute 25 of an otherwise good ride. After the first alert fires or the user logs an item, normal interval logic takes over.

Both modes can be on at the same time. A 5-minute cooldown prevents the two from firing within seconds of each other; once one fires, neither will fire again until the cooldown elapses.

#### Picking the deficit threshold

Defaults are **25 g** for carbs and **300 ml** for hydration. To pick a value that fires when you actually want it to, think in two reference frames:

**As "minutes of intake behind"** — divide the threshold by your per-hour target. A 25 g threshold at a 60 g/h target = ~25 min behind; the same 25 g at a 90 g/h target = ~17 min behind. A 300 ml threshold at a 750 ml/h target = ~24 min behind; at 1200 ml/h = ~15 min. **The sweet spot is 15–25 min of buffer** — under 10 min you get false alarms every time you delay a sip by one descent, over 40 min the alert fires only once performance is already noticeably degrading.

**As "one typical refuel item"** — a standard energy gel is ~22–30 g carbs, a sip-bag is ~30–35 g, a third of a 750 ml bottle is ~250 ml. Setting the threshold ≈ one refuel item means the first alert lands when you are *one item behind schedule* — actionable and unambiguous.

| Rider profile | Carb threshold | Hydration threshold | Behaviour |
|---|---|---|---|
| Early-warner (long events, anti-bonk priority) | 15–20 g | 200–250 ml | Fires after one missed gel / one missed sip — tight monitoring |
| **Default (most riders)** | **25 g** | **300 ml** | Fires after about one gel / a third of a bottle behind — the shipped defaults |
| Late-warner (short rides, hates nags) | 35–45 g | 400–500 ml | Fires only after roughly two missed items — minimal interruption |

**Two important guardrails:**
- For hydration, the ACSM **"keep loss under 2 % body weight"** rule sets the upper bound. For a 75 kg rider that's 1500 ml total; a 300 ml threshold leaves a huge safety margin. Setting it above ~700 ml for a typical rider defeats the point of the alert.
- For carbs, the bonk-protective ceiling is roughly **45–50 g of cumulative deficit**. Beyond that, blood glucose stability is already compromised and any "alert" arrives late. Don't set the threshold above ~40 g unless you're consciously running a fasted / low-carb ride.

If your target is set sensibly (see the tables above), the default 25 g / 300 ml will land in the actionable window for the great majority of rides.

Both the **alert title** ("Eat something" / "Drink something") and the **detail line** ("Behind by 25g" / "30 min since last log") are customisable per category. Each field shows the default as placeholder when empty — leave blank to use the default, or write your own template. Tokens are substituted at fire time:

| Token | Substituted with |
|---|---|
| `{deficit}` | Current deficit (g for carbs, ml for hydration) |
| `{elapsed}` | Minutes since the last log entry |
| `{target}` | Configured per-hour target |

For example, a custom carb detail of `"You're {deficit}g down — eat now ({elapsed} min)"` renders at fire time as *"You're 35g down — eat now (15 min)"*. When the field is empty, the source-specific defaults are used (deficit alerts get *"Behind by Xg"*, time alerts get *"X min since last log"*).

### Logging in-ride

Two complementary mechanisms:

- **Data fields**: 3 carb log slots + 2 drink log slots, each with its own configurable **label** (e.g. *"Gel"*, *"Bar"*, *"Bottle"*), **amount** (g or ml), **idle background colour** (Karoo default auto day/night, or any of 20 dark hues — see [field-colours.md](field-colours.md)) and **icon** (emoji like 🍫 / 🥤 / 💧, or one of the two bundled vector drawables for sports gel pouch and cyclist bidón — Unicode has no good emoji for those shapes). One tap = one log. The slot flashes green for **5 seconds** showing `+Xg ✓` (or `+Xml`) with the hint `TAP UNDO`, then returns to its idle label. Add as many or as few slots to your ride profile as you want.
  - **On-screen undo**: a **second tap on the same slot during the 5 s green window reverses the log**. The slot then flashes red `−Xg ✓` (or `−Xml`) for ~1.5 s as confirmation and returns to idle. Per-slot and one-shot: a third tap is a no-op until the next log populates the slot again. Undo restores the time-alert clock to its value before the wrong tap, so the next time-based alert isn't shifted by the bad entry.
- **Hardware buttons (BonusActions, SRAM AXS only)**: KSafe registers two extra actions, *"KSafe: Log Carb"* and *"KSafe: Log Drink"*, both wired to slot 1 of each category. Map them to your AXS shifter buttons so you can log without looking at the screen.

When the master Carb / Hydration toggle is off, the corresponding log fields render in grey with `OFF` and tap is disabled — the data field is still visible on the ride profile but clearly inactive, so a stray tap does nothing instead of silently no-op'ing. Re-enable the master in the Fueling tab and the colour / emoji come back.

There are also four **status data fields** for carbs (deficit, burn rate, cumulative burned) plus one for hydration (deficit). All are optional — only the ones you add to your ride profile actually appear:

| Field | What it shows |
|---|---|
| **KSafe Carb Status** (`carb-status`) | Current carb deficit (target − logged), colour-coded green / amber / red |
| **KSafe Carb Burn Rate** (`carb-burn-rate`) | Instantaneous burn rate in g/h (`target × zone multiplier`). Shows `---` until the first HR/power sample arrives (otherwise the field would display `base × 1.0`, which riders consistently read as "the app already thinks I'm burning 60 g/h before I've clipped in"). Once a zone is computed it switches to the live zone-modulated rate. |
| **KSafe Carbs Burned** (`carbs-burned`) | Cumulative g the body should have burned this session — companion to the deficit field, lets you see "total need" alongside "currently behind". |
| **KSafe Hydration Status** (`hyd-status`) | Current hydration deficit (target − logged), colour-coded |

### Post-ride summary

When you stop the recording, KSafe shows an `InRideAlert` with totals: *"Carbs: 85/120g (71%) • Hyd: 1100/1500ml (73%)"*. Configurable on/off; nothing is sent to your contacts.

### Carbs and hydration in your FIT file (Strava / Intervals.icu / TrainingPeaks)

KSafe writes the cumulative carbohydrates and hydration you log into the Karoo's FIT file as developer fields. When you upload the activity to Strava / Intervals.icu / TrainingPeaks, your fueling appears as **two extra graphs alongside HR / power / cadence** — coaches can correlate fueling with effort, and you can answer questions like *"I bonked at hour 4 → looking at the FIT, I had 0 g carbs that hour"* from the data instead of guessing.

| Field | Type | Where in the FIT |
|---|---|---|
| `ksafe_carbs_g` | float32, units `"g"` | Per-second `record` (timeline graph) + `session` summary (activity header) — cumulative carbs **logged** by tap or AXS button |
| `ksafe_carbs_burned_g` | float32, units `"g"` | Same — cumulative carbs the body **should have burned** at the zone-aware target rate |
| `ksafe_carb_burn_rate_gph` | float32, units `"g/h"` | Same — **instantaneous** burn rate (zone-modulated) |
| `ksafe_hyd_ml`  | float32, units `"ml"` | Same — cumulative hydration logged |

The logged / burned / hydration fields are cumulative step curves. The burn rate is instantaneous, so it tracks intensity changes — drop in HR or power, rate drops. Tools that prefer rate from cumulative data can still derive a logged-rate (g/h) locally by differencing.

> [!NOTE]
> Field-definition numbers 5 (`ksafe_carbs_burned_g`) and 6 (`ksafe_carb_burn_rate_gph`) join the original 0–4 as **immutable once shipped** — historical FIT files reference them by number, so they cannot be repurposed.

Toggleable via the **"Write to FIT"** switch in the **Settings** tab. Default ON because the cost is negligible (~0.05 % battery over a 5 h ride, no perceptible CPU). Riders who don't want extra developer columns in their FIT can opt out cleanly.

Pacing aligns with the Karoo's native 1 Hz Record sampling, so the developer fields land on the same timestamps as HR / power. Outside `Recording` (Idle / Paused) nothing is written.

### What you configure

Per category (Carbs, Hydration) the Fueling tab lets you:

- Enable / disable the tracker (master toggle — when off, the rest of the fields collapse for a tidier screen)
- Set the per-hour target
- Toggle the deficit alert + threshold
- Toggle the time alert + interval + initial delay
- Customise the alert **title** and **detail** templates (optional — placeholder shows the default; leave blank to use it, or write your own with `{deficit}` / `{elapsed}` / `{target}` tokens)
- Pick the **alert sound** (`Off` / `Single long` / `Double pip` / `Rising chime` / `Urgent pulse`) — see [Beep patterns](#beep-patterns)
- Configure each slot's label, amount, idle background colour (Karoo default or one of 20 dark hues — see [field-colours.md](field-colours.md)), and icon (emoji or one of the bundled vector drawables for sports gel and bidón)
- For hydration only: toggle the **dynamic estimate** mode
- Toggle FIT export (default on; controls whether your fueling appears as developer fields in the Karoo's FIT file)

That's it — no biometric data, no FTP, no zone numbers, no max HR. KSafe reads all of that from the Karoo profile.

### Privacy

Carb and hydration logs **never leave your Karoo** unless calibration logging is enabled (in which case anonymised counters and HR/power zone snapshots appear in the local CSV — same handling as the existing crash and HR data).
