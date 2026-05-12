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
| **Warning** | On-screen notification + beep on the Karoo, no alert to emergency contacts |
| **Emergency** | Full crash flow: countdown + alert to contacts |

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

### Two combinable alert modes

For each category (carbs, hydration) independently:

- **Alert by deficit**: silent until `(target_so_far − logged) > threshold` (e.g. >25 g for carbs, >300 ml for hydration). Then beep + on-screen `InRideAlert` (full-screen overlay, auto-dismissable in 10 s).
- **Alert by time**: silent until `time_since_last_log > interval` (e.g. >25 min for carbs, >20 min for hydration). Same alert UX.

The time alert also has a per-category **initial delay** (default 30 min, configurable, set to 0 to disable). Most riders don't eat or drink in the first 20 minutes; the initial delay prevents a nag at minute 25 of an otherwise good ride. After the first alert fires or the user logs an item, normal interval logic takes over.

Both modes can be on at the same time. A 5-minute cooldown prevents the two from firing within seconds of each other; once one fires, neither will fire again until the cooldown elapses.

Both the **alert title** ("Eat something" / "Drink something") and the **detail line** ("Behind by 25g" / "30 min since last log") are customisable per category. Each field shows the default as placeholder when empty — leave blank to use the default, or write your own template. Tokens are substituted at fire time:

| Token | Substituted with |
|---|---|
| `{deficit}` | Current deficit (g for carbs, ml for hydration) |
| `{elapsed}` | Minutes since the last log entry |
| `{target}` | Configured per-hour target |

For example, a custom carb detail of `"You're {deficit}g down — eat now ({elapsed} min)"` renders at fire time as *"You're 35g down — eat now (15 min)"*. When the field is empty, the source-specific defaults are used (deficit alerts get *"Behind by Xg"*, time alerts get *"X min since last log"*).

### Logging in-ride

Two complementary mechanisms:

- **Data fields**: 3 carb log slots + 2 drink log slots, each with its own configurable **label** (e.g. *"Gel"*, *"Bar"*, *"Bottle"*), **amount** (g or ml), **idle background colour** (palette of 12 dark hues) and **icon** (emoji like 🍫 / 🥤 / 💧, or one of the two bundled vector drawables for sports gel pouch and cyclist bidón — Unicode has no good emoji for those shapes). One tap = one log. The slot flashes green for 2 seconds as confirmation, then returns to its idle label. Add as many or as few slots to your ride profile as you want.
- **Hardware buttons (BonusActions, SRAM AXS only)**: KSafe registers two extra actions, *"KSafe: Log Carb"* and *"KSafe: Log Drink"*, both wired to slot 1 of each category. Map them to your AXS shifter buttons so you can log without looking at the screen.

When the master Carb / Hydration toggle is off, the corresponding log fields render in grey with `OFF` and tap is disabled — the data field is still visible on the ride profile but clearly inactive, so a stray tap does nothing instead of silently no-op'ing. Re-enable the master in the Fueling tab and the colour / emoji come back.

There are also two **status data fields** (carb status and hydration status) that show your current deficit at a glance, color-coded green / amber / red. Optional — if you don't add them to your ride profile, they don't appear.

### Post-ride summary

When you stop the recording, KSafe shows an `InRideAlert` with totals: *"Carbs: 85/120g (71%) • Hyd: 1100/1500ml (73%)"*. Configurable on/off; nothing is sent to your contacts.

### Carbs and hydration in your FIT file (Strava / Intervals.icu / TrainingPeaks)

KSafe writes the cumulative carbohydrates and hydration you log into the Karoo's FIT file as developer fields. When you upload the activity to Strava / Intervals.icu / TrainingPeaks, your fueling appears as **two extra graphs alongside HR / power / cadence** — coaches can correlate fueling with effort, and you can answer questions like *"I bonked at hour 4 → looking at the FIT, I had 0 g carbs that hour"* from the data instead of guessing.

| Field | Type | Where in the FIT |
|---|---|---|
| `ksafe_carbs_g` | float32, units `"g"` | Per-second `record` (timeline graph) + `session` summary (activity header) |
| `ksafe_hyd_ml`  | float32, units `"ml"` | Same |

The values are cumulative — a step curve growing across the ride. Tools that prefer rate (g/h) can derive it locally with whatever averaging window suits the analysis.

Toggleable via the **"Write to FIT"** switch in the Fueling tab. Default ON because the cost is negligible (~0.05 % battery over a 5 h ride, no perceptible CPU). Riders who don't want extra developer columns in their FIT can opt out cleanly.

Pacing aligns with the Karoo's native 1 Hz Record sampling, so the developer fields land on the same timestamps as HR / power. Outside `Recording` (Idle / Paused) nothing is written.

### What you configure

Per category (Carbs, Hydration) the Fueling tab lets you:

- Enable / disable the tracker (master toggle — when off, the rest of the fields collapse for a tidier screen)
- Set the per-hour target
- Toggle the deficit alert + threshold
- Toggle the time alert + interval + initial delay
- Customise the alert **title** and **detail** templates (optional — placeholder shows the default; leave blank to use it, or write your own with `{deficit}` / `{elapsed}` / `{target}` tokens)
- Configure each slot's label, amount, idle background colour, and icon (emoji or one of the bundled vector drawables for sports gel and bidón)
- For hydration only: toggle the **dynamic estimate** mode
- Toggle FIT export (default on; controls whether your fueling appears as developer fields in the Karoo's FIT file)

That's it — no biometric data, no FTP, no zone numbers, no max HR. KSafe reads all of that from the Karoo profile.

### Privacy

Carb and hydration logs **never leave your Karoo** unless calibration logging is enabled (in which case anonymised counters and HR/power zone snapshots appear in the local CSV — same handling as the existing crash and HR data).
