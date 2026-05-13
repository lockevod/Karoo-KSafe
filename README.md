# KSafe — Safety Extension for Karoo

> [!IMPORTANT]
> The **Health** and **Fueling** tabs (medical-episode detection, wellness monitor, carb/hydration tracker, FIT export) require KSafe **v2.0 or newer**. Earlier installs only ship the safety / SOS / messaging features.

> [!WARNING]
> KSafe can send emergency alerts to your contacts. Test it carefully before relying on it.

KSafe is a free, open-source safety extension for Karoo 3 (Karoo OS 1.527+). It works on two layers:

- **Reactive** — detects crashes (accelerometer + gyroscope), sudden speed drops, missed check-ins and, optionally with an HR sensor, medical-episode patterns (HR flatline, HR collapse) and wellness alerts (sustained / critical HR, HR–power decoupling). A manual SOS button is always one tap away.
- **Preventive** *(v2.0)* — carb and hydration tracker with sensor-aware targets that warn you before bonking or dehydration impair your judgment on the bike.

It also sends ride-start / ride-end notifications with an optional Karoo Live tracking link, exposes three custom-message buttons for one-tap status updates ("I'm OK", "Heading home"…), and two webhook slots to fire any HTTP endpoint from a hardware button (garage door, Home Assistant, IFTTT…).

> [!IMPORTANT]
> KSafe sends messages through your **phone's internet connection** via the Hammerhead Companion app. Without an active phone connection no alerts are sent.

> [!NOTE]
> To help improve crash detection, enable the **anonymous calibration data** toggle in the Settings tab and send the log after your rides. No GPS, no messages, no identifiers — see [docs/calibration-logging.md](docs/calibration-logging.md).

## Messaging providers

| Provider | Cost | Notes |
|----------|------|-------|
| **Telegram** | Free, unlimited | Best free option |
| **ntfy** | Free, unlimited | Quickest setup — no account |
| **CallMeBot (WhatsApp)** | Free | If your contacts use WhatsApp |
| **Pushover** | ~$5 one-time | Most reliable push delivery |

You can save credentials for all four; only the **selected** one is used. Setup steps: [docs/messaging-providers.md](docs/messaging-providers.md).

## Installation

1. Open `https://github.com/lockevod/Karoo-KSafe/releases/latest/download/ksafe.apk` on your phone.
2. Share the file with the Hammerhead Companion app and install.
3. **Restart the Karoo** (shut down and start again).
4. Open KSafe — it will prompt for **"Draw over other apps"** permission (required for the SOS cancel overlay). Toggle KSafe → Allow and press Back. If you miss the prompt, a yellow banner inside the app lets you grant it later.

Without the overlay permission the SOS overlay won't appear, but you can still cancel via the SOS/Timer data fields or a hardware button.

## Cancelling an emergency

When a countdown is active you have three ways to cancel:

1. **Overlay** — a red full-screen **CANCEL** button is drawn on top of any screen. Works everywhere; requires the overlay permission (recommended).
2. **Data field** — tap the **SOS** or **Safety Timer** field. Requires that field to be visible on the current screen.
3. **Hardware button** — assign **KSafe: Cancel Emergency** to a SRAM AXS shifter via *Sensors → AXS → Configure Controls*. Works from any screen.

If the countdown completes, KSafe obtains a GPS fix and sends the configured emergency message via the active provider, then returns to idle monitoring.

## Data fields

KSafe exposes **16 custom data fields**. Add any combination from the Karoo profile editor; configure them inside the KSafe app.

### Safety (2)

| Field | Idle | Tap action | Active states |
|-------|------|-----------|---------------|
| **SOS** | `SAFE` (green) | Trigger SOS countdown | Orange `CANCEL` + seconds · Red `ALERT SENT` |
| **Safety Timer** | Remaining time, green→yellow→red | Reset check-in ("I'm OK") | Orange `CANCEL` during any emergency · `Timer OFF` |

The Safety Timer **pauses automatically** when the ride is paused.

### Actions (5)

| Field | Tap action | Active states |
|-------|-----------|---------------|
| **Custom Message 1 / 2 / 3** | Send the slot's preset text — no countdown | Orange `SENDING…` · Green `SENT ✓` · Red `ERR retry` |
| **Webhook 1 / 2** | Fire the configured HTTP request, with optional geo-fence and on-screen ride alert | — |

### Fueling — logging (5, v2.0)

| Field | Tap action |
|-------|-----------|
| **Carb log 1 / 2 / 3** | Log one serving of the slot's configured grams |
| **Hydration log 1 / 2** | Log one serving of the slot's configured ml |

### Fueling — status (4, v2.0)

| Field | Shows |
|-------|-------|
| **Carb status** | Current carb deficit (grams behind target) |
| **Carb burn rate** | Instantaneous g/h based on HR / power |
| **Carbs burned** | Cumulative carbs burned this ride |
| **Hydration status** | Current fluid deficit (ml behind target) |

Idle colours for every field are picked from a 16-hue palette in the corresponding tab. Reserved state colours (red error, orange countdown, amber warning, green success, grey OFF) can't be selected — they belong to the state machine.

## Hardware buttons (SRAM AXS)

KSafe registers **9 BonusActions** assignable to SRAM AXS shifters via *Sensors → AXS → Configure Controls*. Short Press and Long Press can be bound independently.

| Action | What it does |
|--------|-------------|
| **Cancel Emergency** | Cancels the active countdown from any screen |
| **Send Custom Message** | Sends the message in slot 1 immediately |
| **Webhook Action 1 / 2** | Fires the configured HTTP request |
| **Log Carb 1 / 2 / 3** *(v2.0)* | Logs one serving from the corresponding carb slot |
| **Log Drink 1 / 2** *(v2.0)* | Logs one serving from the corresponding hydration slot |

> [!NOTE]
> BonusActions only appear if a SRAM AXS groupset is paired and the Karoo has been restarted after installing KSafe. See the [official Hammerhead guide](https://support.hammerhead.io/hc/en-us/articles/25672636525979-Karoo-OS-Controlling-Karoo-with-SRAM-AXS-Controllers).

## Configuration

The KSafe app has **six tabs**, in this order:

1. **Safety** — emergency message + tokens (`{location}`, `{reason}`, `{livetrack}`), countdown duration, SOS / Timer field colours, crash detection (sensitivity preset + custom slider, min speed, confirm speed, monitor-outside-ride toggles), speed-drop window, check-in interval.
2. **Health** *(v2.0)* — HR-based detectors: medical episode (HR flatline / collapse) and wellness monitor (critical HR, sustained HR, HR–power decoupling). Each has Silent / Warning / Emergency response level and customisable `{bpm}` / `{threshold}` / `{minutes}` / `{drift}` templates. Requires a paired HR sensor.
3. **Fueling** *(v2.0)* — per-second carb and fluid targets that adapt to your effort via HR / power zones (0.7×–1.3×). Two combinable alert modes per category (**deficit** and **time**), each with customisable templates (`{deficit}`, `{elapsed}`, `{target}`) and a per-category beep pattern. Optional dynamic sweat-rate estimate (HR, power, weight, temp, humidity) for hydration. Post-ride summary.
4. **Actions** — three sub-blocks: **Karoo Live** (ride-start / ride-end toggles + messages, Karoo Live key, test buttons), **Custom Messages 1–3** (enable, 7-char button label, message text, idle colour), and **Webhook 1–2** (URL, GET/POST, headers, body, optional geo-fence, optional on-screen alert).
5. **Provider** — pick the active messaging provider and enter credentials. All four configurations are saved independently.
6. **Settings** — master kill switch, **test buttons** (Simulate Crash, Test ride start/end), **FIT export** *(v2.0)* of logged + burned carbs / burn rate / hydration / wellness drift as developer fields for Strava / Intervals.icu / TrainingPeaks, **anonymous calibration logging** *(opt-in)*, **Backup / Restore**.

Detailed field references:
- 📘 [Safety / Settings — field reference](docs/configuration-reference.md)
- 📘 [Health & Fueling — full reference](docs/health-fueling.md) (tier thresholds, FIT schema, alert tokens)

## Testing

All test buttons work **without an active ride**:

| Button | Where | What it does |
|--------|-------|--------------|
| **Test Send** | Provider | Sends a test message via the active provider, with a precise error string on failure |
| **Simulate Crash** | Settings | Sends your real emergency message immediately — no countdown |
| **Test ride start / end** | Actions | Sends the configured ride-start or ride-end message |
| **Send Message 1 / 2 / 3** | Actions | Sends each custom message directly from the app |

> **Simulate Crash** sends a real alert to your contact. Warn them first, or use **Test Send** for connectivity-only checks.

## Crash detection

The algorithm is based on the same approach as Garmin's incident detection: **large impact followed by genuine stillness**. Four presets:

| Preset | Best for |
|--------|----------|
| ⛰ **Low** | MTB, enduro, technical terrain (lower sensitivity, lower confirm-speed gate) |
| 🚴 **Medium** | Road, gravel, mixed — **recommended default** |
| 🏁 **High** | Smooth road only (velodrome, closed circuit) — do **not** use on MTB / gravel |
| 🔧 **Custom** | 20–70 m/s² threshold slider plus speed gates |

You can override min-speed manually after picking a preset (`0` disables the speed gate — useful for testing).

**When it fires and when it doesn't.** An impact only confirms as a crash if the device then stays genuinely still — accelerometer near gravity, gyroscope ≤ 2 rad/s, GPS speed below the confirm threshold — for **4.5 continuous seconds**. Any movement in that window resets the countdown. After hitting a pothole, expansion joint or a small jump, a rider keeps pedalling — the GPS keeps moving and the gyro never settles, so no alert fires. After a real crash the device lies on the ground with near-zero motion for several seconds and the countdown starts. Without a GPS fix (tunnel, dense tree cover) the speed gate degrades and only the inertial checks remain; **High** sensitivity is therefore unsafe on MTB / gravel because a hard landing followed by a brief pause can confirm.

📘 Full pipeline (`MONITORING → IMPACT → SILENCE_CHECK → CRASH_CONFIRMED`), per-preset thresholds, real-world scenarios (pothole at 40 km/h, MTB jump landing, expansion joint…) and the rationale for every constant: [docs/crash-detection-algorithm.md](docs/crash-detection-algorithm.md).

## Webhooks

The two webhook slots fire any HTTP endpoint (GET or POST) through the Karoo network bridge — same path used for emergency alerts, works over Bluetooth tether. Each slot supports an optional **geo-fence** (only fire near a configured location) and an optional **on-screen ride alert** so you notice accidental presses.

📘 Copy-paste recipes for Home Assistant, Shelly (local + cloud), ntfy, IFTTT, n8n / Make: [docs/webhooks-cookbook.md](docs/webhooks-cookbook.md).

## Backup, restore and easy token entry

Export and Import buttons (Settings tab) write/read `ksafe_export.json` / `ksafe_import.json` on the device. ADB-pulling the file, editing it on your computer and pushing it back is also the easiest way to enter long tokens (Pushover, Telegram) without typing them on the Karoo. Unknown fields are silently ignored, so imports across versions always work.

📘 Procedure, ADB commands and JSON schema: [docs/backup-restore.md](docs/backup-restore.md).

## Known issues

- No phone connection at the moment of the emergency → no alert.
- A large pothole or expansion joint followed by a complete stop for several seconds *can* trigger a false positive. The countdown is your safety net — tap CANCEL.
- Without GPS fix (tunnel, dense tree cover) the speed gate degrades; gyroscope + accelerometer remain the only guard.
- Each provider has its own rate limits and free-tier rules.
- Crash detection only runs during an active ride unless you enable **"Monitor crash when not riding"** in the Safety tab.

## Disclaimer

KSafe is a **safety aid, not a safety guarantee**. It can fail to detect an incident or deliver an alert for many reasons — phone disconnected or not carried, GPS lost, provider outage, undetected impact pattern, misconfiguration, device failure, no internet.

KSafe is provided **"as is"**, without warranty. The developer (lockevod) accepts no responsibility or liability for any harm arising from its use or inability to use it. **By installing KSafe you accept full responsibility for your own safety.** Always carry your phone, keep it connected to the Karoo, and let someone know your planned route — regardless of whether KSafe is active.

## Privacy

- All configuration is stored locally on the Karoo.
- Message content and identifiers (phone number, chat ID, user key…) are shared with whichever third-party provider you select. Read their terms.
- KSafe has no relationship with any of these providers.
- The opt-in **anonymous calibration data** toggle records sensor / algorithm data only — no GPS, no messages, no personal identifier. Full disclosure: [docs/calibration-logging.md](docs/calibration-logging.md).

## Documentation

User guides:
- [Safety / Settings — field reference](docs/configuration-reference.md)
- [Messaging providers — full setup](docs/messaging-providers.md)
- [Health & Fueling — full reference](docs/health-fueling.md)
- [Webhook cookbook](docs/webhooks-cookbook.md)
- [Backup and restore](docs/backup-restore.md)
- [Field colours](docs/field-colours.md)
- [Calibration logging](docs/calibration-logging.md)

Algorithm internals (for contributors):
- [Crash detection algorithm](docs/crash-detection-algorithm.md)
- [Medical episode & wellness algorithms](docs/medical-wellness-algorithm.md)
- [Nutrition & hydration algorithms](docs/fueling-algorithm.md)

## Credits

- Developed by EnderThor.
- Uses the [Karoo Extensions Framework](https://github.com/hammerheadnav/karoo-ext) by Hammerhead.
- Optionally talks to Telegram, ntfy, CallMeBot (WhatsApp) or Pushover. Each has its own terms; KSafe has no affiliation.
- Thanks to Hammerhead for the Karoo and the extensions API.

## Useful links

- [Karoo Extensions Framework](https://github.com/hammerheadnav/karoo-ext)
- [CallMeBot WhatsApp API](https://www.callmebot.com/blog/free-api-whatsapp-messages/)
- [Telegram BotFather](https://t.me/BotFather)
- [DC Rainmaker sideloading guide](https://www.dcrainmaker.com/2021/02/how-to-sideload-android-apps-on-your-hammerhead-karoo-1-karoo-2.html)
