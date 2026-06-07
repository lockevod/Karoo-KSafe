# Configuration Reference — Safety, Actions (Karoo Live) and Settings tabs

> Field-by-field reference for every toggle and text field in the **Safety**, **Actions** (the Karoo Live block only — Custom Messages and Webhooks are covered in the README) and **Settings** tabs.
>
> Other tabs have their own deep-dive docs:
> - **Health & Fueling tabs**: [docs/health-fueling.md](health-fueling.md)
> - **Provider tab setup walkthroughs**: [docs/messaging-providers.md](messaging-providers.md)
> - **Webhook recipes**: [docs/webhooks-cookbook.md](webhooks-cookbook.md)

---

## Safety tab

Everything that decides **when** and **how** an emergency fires lives here.

### Field colours

- **SOS field colour**: Idle background colour for the SOS data field (shown in SAFE state). Picker offers Karoo default (auto day/night) plus 20 dark hues with white text — see [field-colours.md](field-colours.md).
- **Timer field colour**: Idle colour for the Safety Timer field (shown when the timer is running normally). Warning (yellow) and expired (red) state colours are always preserved regardless of this setting.

### Emergency message

- **Emergency message**: The message sent to your contacts when an emergency fires. Available placeholders:
  - `{location}` — GPS coordinates as a Google Maps link.
  - `{reason}` — reason for the alert (crash / check-in expired / manual SOS / speed drop).
  - `{livetrack}` — Karoo Live real-time tracking link (only if a key is configured in the Actions tab).
- **Countdown seconds**: How long the cancellation countdown lasts before alerts are sent (default: **30 s**, clamped to **[5, 120]**). The post-crash cooldown is derived as `countdown + 30 s`, so changing this also changes how long the impact detector ignores new spikes after a confirmed crash (15 s countdown → 45 s cooldown; 60 s countdown → 90 s cooldown). Shorter values (15–20 s) get the alert out faster after a real crash but leave less time to cancel a false positive; longer values (45–60 s) tolerate rough terrain better but delay real alerts. The default 30 s matches Garmin / Wahoo conventions and is the recommended starting point. Values outside the clamp are coerced on save (a typed "0" is treated as the lower bound 5 — without the clamp, 0 would skip the entire cancel UI and fire immediately).

### Crash detection

- **Crash detection**: Enable/disable automatic crash detection. Configure sensitivity and minimum speed (see the [Crash Detection](../README.md#crash-detection) section of the README for which preset to pick).
- **Max. speed to confirm crash**: The GPS speed below which the rider is considered stopped after an impact. Defaults are preset-keyed: **3 km/h** for Low, **5 km/h** for Medium / High. Framework to pick a value:
  - **3 km/h** (strict road) — a crashed rider on tarmac is essentially motionless. Use only on smooth road where sliding is unlikely.
  - **5 km/h** (mixed road + gravel) — allows a few extra metres of slide before confirming. Default for general use.
  - **8 km/h** (MTB / enduro) — on dirt and descents a crashed bike + rider can drift further from the impact point before stopping. Set higher to avoid the silence check timing out before the rider has truly stopped.
  
  Higher values trade a small extra false-positive rate (a rider who hits a bump and brakes hard *might* dip below 8 km/h for the 4.5 s silence window without crashing) against fewer missed alerts on sliding crashes. Lower values do the opposite trade-off.
- **Monitor crash when not riding**: Keeps crash detection active even when no ride is recording. Useful for warm-ups or quick spins without starting a recording.
- **Monitor crash when not riding — any speed**: Same as above but ignores the minimum speed threshold (detects crashes even while stationary). ⚠ More false positives — use with caution.
- **Per-profile crash settings**: Override the crash configuration per Karoo ride profile (Road / Gravel / MTB / custom). Each profile can either keep the **Use global** toggle (inherit the global crash config) or define its own independent preset / enabled / sensitivity / min-speed / confirm-speed set. The override is **all-or-nothing** — turning off Use-global replaces the whole crash config for that profile, not individual fields (a per-profile *disable* can only further restrict the global kill-switch, never re-enable it). A Use-global stub is **auto-created** the first time KSafe sees a profile become active, so the list fills itself in as you ride — a profile only appears after you have **started a ride** with it. The saved override takes effect at the **start of your next ride** with that profile, so configure it before the ride you want it to apply to. Entries are **keyed by profile id**, so renaming a profile preserves its settings; entries for deleted profiles are pruned automatically. In the Safety tab each profile card collapses to a one-line summary so a long list stays manageable.
- **Speed drop detection**: Enable/disable detection of prolonged speed drops. Configure the time window (minutes, clamped to **[1, 60]**, default 5) with no movement before triggering. The detector opens its zero-speed window when effective speed falls below **3.5 km/h** (the threshold sits in the valley between consumer-GPS jitter on a stationary bike and slow hike-a-bike — see `crash-detection-algorithm.md`).

### Check-in timer

- **Check-in timer**: Enable/disable periodic check-ins. Configure the interval in minutes (default: 120 min, clamped to **[10, 1440]**). A warning beep fires 10 minutes before expiry. **The timer pauses automatically when the ride is paused** (coffee stop, traffic light, etc.) and resets to the full interval when you resume. Any active check-in countdown is also cancelled on pause. Values outside the clamp are coerced on save (a typed "0" would otherwise schedule `delay(0)` and fire CHECKIN_EXPIRED immediately on every ride start).

---

## Actions tab — Karoo Live block

> The Custom Messages and Webhook slots are described in the README. This section covers only the **Karoo Live** group (ride-start / ride-end notifications), which also lives in the Actions tab.

- **Notify contacts on ride start**: Toggle to enable/disable a notification when the ride starts. Sent **only once per ride** — resuming from a pause does not send it again. Use `{livetrack}` in the message to include the tracking link (requires a Karoo Live key).
- **Karoo Live key**: Enter only the key part of your Karoo Live URL. For example, from `https://dashboard.hammerhead.io/live/3738Ag` enter `3738Ag`. Leave empty to send a plain start message without a tracking link.
- **Ride start message**: The text sent when the ride starts. Use `{livetrack}` to insert the tracking link.
- **Notify contacts on ride end**: Toggle to enable/disable a notification when the ride recording stops completely. Does not require a Karoo Live key — any message text works.
- **Ride end message**: The text sent when the ride ends.
- **Test ride start / Test ride end**: Send the configured ride-start or ride-end message immediately, without needing an active ride. Useful to verify the message reaches your contact before the first real ride of the season.

> The `{livetrack}` placeholder also works in the Safety tab's emergency message — if a key is set here, emergency alerts will include the tracking link too.

### Per-contact alert scope

Each configured contact (provider slots 1/2/3) carries an alert-scope filter, stored on the provider config as `recipient1Alerts` / `recipient2Alerts` / `recipient3Alerts` of type `RecipientAlertScope`:

- **ALL** (default) — the contact receives both emergencies (crash / SOS / check-in / speed-drop / medical) and info messages (ride start/end + custom messages).
- **EMERGENCY_ONLY** — only emergencies.
- **INFO_ONLY** — only info messages.

> Safety net: if the per-contact filters would leave an **emergency** with no recipients, KSafe falls back to sending the emergency to **all** contacts. A misconfigured filter can never silence a real emergency. (For NTFY, only slot 1 applies — single destination.)

---

## Settings tab

Master switch, calibration and housekeeping.

- **Active**: Enable or disable the extension entirely. When OFF, all monitoring stops (crash, speed-drop, check-in, Health, Fueling) and configured notifications (ride start/end, custom messages, webhooks) are suppressed. Cancel paths for an in-flight emergency stay available so a rider can always stop an active alert.
- **Simulate Crash**: Sends the configured emergency message immediately — no countdown, no waiting — so you can verify the full message (location, livetrack link) reaches your contact. Sends a **real alert**; warn your contact first.
- **FIT export** *(v2.0)*: Toggles whether KSafe's fueling stream (cumulative carbs logged, cumulative carbs burned, current burn rate g/h, hydration ml) and wellness stream (HR drift %, max drift, alert count) are written to the Karoo's FIT file as developer fields. Default **OFF** (opt-in). Details in [health-fueling.md](health-fueling.md).
- **Help improve KSafe**: Optional anonymous calibration data toggle (disabled by default). Full disclosure in [calibration-logging.md](calibration-logging.md).
- **Export / Import**: Configuration backup and restore — see [backup-restore.md](backup-restore.md) for the file format, the recommended ADB workflow, and the JSON schema.

---

## Combined fuel-log field (Fueling)

The combined drink+carbs tap field logs a drink volume **and** its carbs in a single tap (for riders running a carb-loaded drink mix). Configured via these fields (full Fueling reference in [health-fueling.md](health-fueling.md)):

- **`combinedCarbConcentrationPer500ml`** (default **60**): Carbs (g) per 500 ml of your drink mix. Drives the auto-fill of each combined button's carbs from its volume (`ml × concentration / 500`). Editable; only used by the Fueling screen to pre-fill — the per-button carbs value is what actually gets logged.
- **`combined1Label` / `combined1Ml` / `combined1Carbs` / `combined1Color`** (defaults `"Sip"` / 250 ml / 30 g / Auto) and **`combined2Label` / `combined2Ml` / `combined2Carbs` / `combined2Color`** (defaults `"Bottle"` / 500 ml / 60 g / Auto): The two combined buttons' label, volume, carbs, and idle background colour. Carbs auto-fill from `ml × combinedCarbConcentrationPer500ml / 500` but are independently editable. The field is active when **either** the carbs or hydration tracker is enabled (it logs only the enabled side) and greyed when both are off — there is no separate enable flag. The icon is fixed (not configurable).
