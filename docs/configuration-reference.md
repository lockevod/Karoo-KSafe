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

- **SOS field colour**: Idle background colour for the SOS data field (shown in SAFE state). Palette of 16 preset dark hues with white text — see [field-colours.md](field-colours.md).
- **Timer field colour**: Idle colour for the Safety Timer field (shown when the timer is running normally). Warning (yellow) and expired (red) state colours are always preserved regardless of this setting.

### Emergency message

- **Emergency message**: The message sent to your contacts when an emergency fires. Available placeholders:
  - `{location}` — GPS coordinates as a Google Maps link.
  - `{reason}` — reason for the alert (crash / check-in expired / manual SOS / speed drop).
  - `{livetrack}` — Karoo Live real-time tracking link (only if a key is configured in the Actions tab).
- **Countdown seconds**: How long the cancellation countdown lasts before alerts are sent (default: **30 s**). The post-crash cooldown is derived as `countdown + 30 s`, so changing this also changes how long the impact detector ignores new spikes after a confirmed crash (15 s countdown → 45 s cooldown; 60 s countdown → 90 s cooldown). Shorter values (15–20 s) get the alert out faster after a real crash but leave less time to cancel a false positive; longer values (45–60 s) tolerate rough terrain better but delay real alerts. The default 30 s matches Garmin / Wahoo conventions and is the recommended starting point.

### Crash detection

- **Crash detection**: Enable/disable automatic crash detection. Configure sensitivity and minimum speed (see the [Crash Detection](../README.md#crash-detection) section of the README for which preset to pick).
- **Max. speed to confirm crash**: The GPS speed below which the rider is considered stopped after an impact. Defaults are preset-keyed: **3 km/h** for Low, **5 km/h** for Medium / High. Framework to pick a value:
  - **3 km/h** (strict road) — a crashed rider on tarmac is essentially motionless. Use only on smooth road where sliding is unlikely.
  - **5 km/h** (mixed road + gravel) — allows a few extra metres of slide before confirming. Default for general use.
  - **8 km/h** (MTB / enduro) — on dirt and descents a crashed bike + rider can drift further from the impact point before stopping. Set higher to avoid the silence check timing out before the rider has truly stopped.
  
  Higher values trade a small extra false-positive rate (a rider who hits a bump and brakes hard *might* dip below 8 km/h for the 4.5 s silence window without crashing) against fewer missed alerts on sliding crashes. Lower values do the opposite trade-off.
- **Monitor crash when not riding**: Keeps crash detection active even when no ride is recording. Useful for warm-ups or quick spins without starting a recording.
- **Monitor crash when not riding — any speed**: Same as above but ignores the minimum speed threshold (detects crashes even while stationary). ⚠ More false positives — use with caution.
- **Speed drop detection**: Enable/disable detection of prolonged speed drops. Configure the time window (minutes) with no movement before triggering.

### Check-in timer

- **Check-in timer**: Enable/disable periodic check-ins. Configure the interval in minutes (default: 120 min). A warning beep fires 10 minutes before expiry. **The timer pauses automatically when the ride is paused** (coffee stop, traffic light, etc.) and resets to the full interval when you resume. Any active check-in countdown is also cancelled on pause.

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

---

## Settings tab

Master switch, calibration and housekeeping.

- **Active**: Enable or disable the extension entirely. When OFF, all monitoring stops (crash, speed-drop, check-in, Health, Fueling) and configured notifications (ride start/end, custom messages, webhooks) are suppressed. Cancel paths for an in-flight emergency stay available so a rider can always stop an active alert.
- **Simulate Crash**: Sends the configured emergency message immediately — no countdown, no waiting — so you can verify the full message (location, livetrack link) reaches your contact. Sends a **real alert**; warn your contact first.
- **FIT export** *(v2.0)*: Toggles whether KSafe's fueling stream (cumulative carbs logged, cumulative carbs burned, current burn rate g/h, hydration ml) and wellness stream (HR drift %, max drift, alert count) are written to the Karoo's FIT file as developer fields. Default ON. Details in [health-fueling.md](health-fueling.md).
- **Help improve KSafe**: Optional anonymous calibration data toggle (disabled by default). Full disclosure in [calibration-logging.md](calibration-logging.md).
- **Export / Import**: Configuration backup and restore — see [backup-restore.md](backup-restore.md) for the file format, the recommended ADB workflow, and the JSON schema.
