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

### Hammerhead's Karoo Extensions store *(recommended)*

KSafe is **officially distributed by Hammerhead** in the Karoo's built-in Extensions store. This is the supported path for the great majority of riders: no APK file, no sideloading, no Companion-app workaround, no developer options, no ADB. Hammerhead handles signing, hosting and update delivery, so installs and version bumps land on the device the same way every other Karoo-native feature does.

On the Karoo:

1. Open the **Extensions store** from the main menu.
2. Find **KSafe** in the list, tap **Install**.
3. **Restart the Karoo** (shut down and start again).
4. Open KSafe — it will prompt for **"Draw over other apps"** permission (required for the SOS cancel overlay). Toggle KSafe → Allow and press Back. If you miss the prompt, a yellow banner inside the app lets you grant it later.

Updates published by Hammerhead arrive in the same store entry — no manual re-install needed.

### Sideloading *(advanced / pre-release builds)*

For riders who want a pre-release build, a custom fork, or who need to install before a version reaches the store:

1. Open `https://github.com/lockevod/Karoo-KSafe/releases/latest/download/ksafe.apk` on your phone.
2. Share the file with the Hammerhead Companion app and install.
3. **Restart the Karoo** (shut down and start again).
4. Grant the **"Draw over other apps"** permission as above.

Without the overlay permission the SOS overlay won't appear, but you can still cancel via the SOS/Timer data fields or a hardware button — regardless of which install method you used.

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
| **Webhook 1 / 2** | Fire the configured HTTP request, with optional geo-fence and on-screen ride alert | Orange `firing…` · Green `OK ✓` · Red `ERR retry` |

### Fueling — logging (5, v2.0)

| Field | Tap action |
|-------|-----------|
| **Carb log 1 / 2 / 3** | Log one serving of the slot's configured grams. A second tap on the same slot within ~5 s of the green `+Xg` flash **undoes** the entry (red `−Xg` confirmation) |
| **Hydration log 1 / 2** | Log one serving of the slot's configured ml. Same on-screen undo: second tap within ~5 s reverses the log |

### Fueling — status (4, v2.0)

| Field | Shows |
|-------|-------|
| **Carb status** | Current carb deficit (grams behind target). Shows `---` until the tracker has integrated any data, then `0g` / `−Xg` / `+Xg` colour-coded by deficit level |
| **Carb burn rate** | Instantaneous g/h based on HR / power. Shows `---` until the first HR / power sample arrives, then switches to the live zone-modulated rate |
| **Carbs burned** | Cumulative carbs burned this ride. Shows `---` until the tracker is running |
| **Hydration status** | Current fluid deficit (ml behind target). Same `---` waiting behaviour as Carb status |

**Nine of the 16 fields have a rider-pickable idle background** — SOS, Safety Timer, Custom Message 1–3, Webhook 1–2, Carb Log 1–3, Hydration Log 1–2 — picked from a palette in the corresponding tab. The first entry is **Karoo default (auto day/night)** — the new default for fresh installs — which makes the field render with no custom background and theme-aware text (black on white during the day, white on black at night) so it matches native Karoo fields. Below it sits a 20-hue painted palette for riders who want a coloured tap target. Reserved state colours (red error, orange countdown, amber warning, green success, grey OFF) can't be selected — they belong to the state machine. The remaining 4 fields have no picker: **Carb burn rate** and **Carbs burned** are always Karoo-theme (passive readouts that should look native); **Carb status** and **Hydration status** are always coloured by deficit level (blue ahead / green within margin / amber approaching threshold / red over). The two **Carb burn rate** and **Carbs burned** fields also respect the **per-field horizontal alignment** (left / center / right) the rider sets in the Karoo profile editor; every other field is always centered because they're tap targets or coloured state indicators where alignment makes the field look off-balance next to its neighbours.

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
3. **Fueling** *(v2.0, revised v2.1)* — per-second carb and fluid targets that adapt to your effort via HR / power zones (0.4×–1.5×, capped at the 90 g/h gut-absorption ceiling). **Three quick presets** (Casual 30 / Endurance 50 / Race 75 g/h) backed by the Jeukendrup 2014 / ISSN 2017 / IOC 2019 consensus on cycling carb intake. Two combinable alert modes per category (**deficit** and **time**), each with customisable templates (`{deficit}`, `{elapsed}`, `{target}`), a per-category beep pattern, and a **per-category alert background colour** (carbs default amber, hydration default **blue** for water — both pickable from a 6-colour palette). Optional dynamic sweat-rate estimate (HR, power, weight, temp, humidity) for hydration. Post-ride summary.

   > [!NOTE]
   > **How the alerts know you are "low"** — KSafe doesn't measure your blood glucose or hydration directly (no bike sensor can). It **integrates the per-hour target you configure over ride time** (modulated for carbs by HR / power zone, optionally for hydration by the sweat-rate estimator) and compares the total to what you have tapped on the log slots. The gap is the **deficit**; the alert fires when it crosses your threshold. So the reference is the target *you* set — pick it well using the [starting-target tables](docs/health-fueling.md#how-to-pick-your-per-hour-targets) and calibrate it in 2–3 real rides. If you eat or drink without tapping a slot, KSafe can't see it: a missed log looks identical to a missed gel.
4. **Actions** — three sub-blocks: **Karoo Live** (ride-start / ride-end toggles + messages, Karoo Live key, test buttons), **Custom Messages 1–3** (enable, 7-char button label, message text, idle colour), and **Webhook 1–2** (URL, GET/POST, headers, body, optional geo-fence, optional on-screen alert, idle colour).
5. **Provider** — pick the active messaging provider and enter credentials. All four configurations are saved independently.
6. **Settings** — master kill switch, **test buttons** (Simulate Crash, Test ride start/end), **FIT export** *(v2.0)* of logged + burned carbs / burn rate / hydration / wellness drift as developer fields for Strava / Intervals.icu / TrainingPeaks, **anonymous calibration logging** *(opt-in)*, **Backup / Restore**.

Detailed field references:
- 📘 [Safety / Settings — field reference](docs/configuration-reference.md)
- 📘 [Health & Fueling — full reference](docs/health-fueling.md) (tier thresholds, FIT schema, alert tokens)
- 📘 [Setting your initial fueling targets](docs/health-fueling.md#how-to-pick-your-per-hour-targets) — g/h by ride duration, ml/h by temperature, pre/post-ride weigh-in formula, ACSM / Jeukendrup / Sawka references
- 📘 [What KSafe does in each ride state](docs/ride-state-behavior.md) — Idle / Recording / Paused: which subsystems run, what the status fields show, day/night theme handling

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

- **The Karoo has a buzzer, not a speaker.** Alerts beep through that buzzer using the only audio API the Karoo SDK exposes (`PlayBeepPattern`). If you mute the device, every KSafe sound — emergency countdown, fueling reminders, wellness alerts — goes silent. The Karoo SDK does not expose the mute state to extensions, so KSafe cannot detect or override it. **Keep the Karoo unmuted if you want to hear emergency alerts.**
- No phone connection for the whole retry window → no alert. Emergency alerts retry automatically: 3 cycles of 3 attempts each (60 / 120 / 180 s between attempts inside a cycle, 5 min and 10 min between cycles — up to ~30 min total). If your phone reconnects within that window the alert still goes out; only a sustained disconnect across all 9 attempts fails completely.
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
- Thanks to **[Tim Kluge (timklge)](https://github.com/timklge)** for [**karoo-headwind**](https://github.com/timklge/karoo-headwind). When installed alongside KSafe, headwind publishes real meteo data (ambient temperature, humidity) as Karoo streams that the **Fueling tab's dynamic sweat-rate estimator** consumes instead of the device's heat-biased onboard temperature sensor — a noticeably better hydration target on hot rides. KSafe degrades cleanly to the onboard sensor when headwind isn't installed.

## Useful links

- [Karoo Extensions Framework](https://github.com/hammerheadnav/karoo-ext)
- [CallMeBot WhatsApp API](https://www.callmebot.com/blog/free-api-whatsapp-messages/)
- [Telegram BotFather](https://t.me/BotFather)
- [DC Rainmaker sideloading guide](https://www.dcrainmaker.com/2021/02/how-to-sideload-android-apps-on-your-hammerhead-karoo-1-karoo-2.html)
