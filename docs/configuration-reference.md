# Configuration Reference — Settings Tab

> Field-by-field reference for the **Settings** tab. The README has a short summary of what each group does; this page is the exhaustive list used to look up a specific setting.
>
> Other tabs have their own deep-dive docs:
> - **Health & Fueling tabs**: [docs/health-fueling.md](health-fueling.md)
> - **Provider tab setup walkthroughs**: [docs/messaging-providers.md](messaging-providers.md)
> - **Actions tab** (Custom Messages + Webhooks): see the corresponding sections in the README, plus [docs/webhooks-cookbook.md](webhooks-cookbook.md) for recipes.

## Master switch and field colours

- **Active**: Enable or disable the extension entirely. When OFF, all monitoring stops (crash, speed-drop, check-in, Health, Fueling) and configured notifications (ride start/end, custom messages, webhooks) are suppressed. Cancel paths for an in-flight emergency stay available so a rider can always stop an active alert.
- **SOS field colour**: Idle background colour for the SOS data field (shown in SAFE state). Palette of 16 preset dark hues with white text — see [field-colours.md](field-colours.md).
- **Timer field colour**: Idle colour for the Safety Timer field (shown when the timer is running normally). Warning (yellow) and expired (red) state colours are always preserved regardless of this setting.

## Emergency message and notifications

- **Emergency message**: The message sent to your contacts. Available placeholders:
  - `{location}` — GPS coordinates as a Google Maps link.
  - `{reason}` — reason for the alert (crash / check-in expired / manual SOS / speed drop).
  - `{livetrack}` — Karoo Live real-time tracking link (only if a key is configured).
- **Notify contacts on ride start**: Toggle to enable/disable a notification when the ride starts. Sent **only once per ride** — resuming from a pause does not send it again. Use `{livetrack}` in the message to include the tracking link (requires a Karoo Live key).
- **Karoo Live key**: Enter only the key part of your Karoo Live URL. For example, from `https://dashboard.hammerhead.io/live/3738Ag` enter `3738Ag`. Leave empty to send a plain start message without a tracking link.
- **Ride start message**: The text sent when the ride starts. Use `{livetrack}` to insert the tracking link.
- **Notify contacts on ride end**: Toggle to enable/disable a notification when the ride recording stops completely. Does not require a Karoo Live key — any message text works.
- **Ride end message**: The text sent when the ride ends.

> The `{livetrack}` placeholder also works in your emergency message — if a key is set, emergency alerts will include the tracking link too.

- **Countdown seconds**: How long the cancellation countdown lasts before alerts are sent (default: 30 s).

## Crash detection

- **Crash detection**: Enable/disable automatic crash detection. Configure sensitivity and minimum speed (see the [Crash Detection](../README.md#crash-detection) section of the README for which preset to pick).
- **Max. speed to confirm crash**: The GPS speed below which the rider is considered stopped after an impact (default: **3 km/h** for Low, **5 km/h** for Medium/High). Increase to 8 km/h for MTB/gravel where sliding after a crash is common; lower to 3 km/h for strict road use.
- **Monitor crash when not riding**: Keeps crash detection active even when no ride is recording. Useful for warm-ups or quick spins without starting a recording.
- **Monitor crash when not riding — any speed**: Same as above but ignores the minimum speed threshold (detects crashes even while stationary). ⚠ More false positives — use with caution.
- **Speed drop detection**: Enable/disable detection of prolonged speed drops. Configure the time window (minutes) with no movement before triggering.

## Check-in timer

- **Check-in timer**: Enable/disable periodic check-ins. Configure the interval in minutes (default: 120 min). A warning beep fires 10 minutes before expiry. **The timer pauses automatically when the ride is paused** (coffee stop, traffic light, etc.) and resets to the full interval when you resume. Any active check-in countdown is also cancelled on pause.

## Bottom of the tab

- **FIT export** *(v2.0)*: Toggles whether KSafe's fueling stream (cumulative carbs logged, cumulative carbs burned, current burn rate g/h, hydration ml) and wellness stream (HR drift %, max drift, alert count) are written to the Karoo's FIT file as developer fields. Default ON. Details in [health-fueling.md](health-fueling.md).
- **Help improve KSafe**: Optional anonymous calibration data toggle (disabled by default). Full disclosure in [calibration-logging.md](calibration-logging.md).
- **Export / Import**: Configuration backup and restore — see [backup-restore.md](backup-restore.md) for the file format, the recommended ADB workflow, and the JSON schema.
