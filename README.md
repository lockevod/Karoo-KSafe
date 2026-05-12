# KSafe - Safety Extension for Karoo

> [!IMPORTANT]
> **Available from v2.0** — the **Health tab** (medical episode detection, three-tier wellness / HR monitor, customisable HR alert templates) and the **Fueling tab** (carb and hydration tracker, log slots with custom colours and icons, customisable alert templates, FIT-file export) require KSafe **v2.0 or newer**. Earlier installs only ship the safety / SOS / messaging features. Update via the Hammerhead Companion app once v2.0 is released.

> [!NOTE]
> If you want to **help improve crash detection**, enable the anonymous calibration data toggle in the app (Settings tab) and send the data after your rides. This data is completely anonymous. The more data collected, the better the algorithm gets tuned for all users. See [Calibration Logging](docs/calibration-logging.md) for details.

> [!WARNING]
> This extension can send emergency alerts to your contacts. Please test it carefully before relying on it in real situations.

KSafe is a free, open-source safety and notification extension for Karoo GPS devices. It works on **two complementary layers**:

**1) Reactive layer — detect when something goes wrong.** While you ride, KSafe watches for any signal of trouble — **crash detected**, **sudden speed drop**, **missed check-in**, **heart-rate flatline or sudden collapse**, **sustained high heart rate** — and alerts your emergency contacts automatically. A manual **SOS button** on the ride screen is always one tap away.

**2) Preventive layer — avoid the conditions that cause incidents.** Many cycling accidents have an underlying cause that is entirely preventable: **bonking** (carb depletion impairs cognition, balance and reaction time), **dehydration** (fatigue, cramps, heat stress, loss of focus), **overtraining** (sustained high HR leading to errors). KSafe's nutrition and hydration tracker monitors your intake during the ride and proactively reminds you to eat and drink before these conditions degrade your judgment on the bike. Lower your odds of needing the reactive layer in the first place.

KSafe also gives you:

- **Ride start / end notifications** to your contacts, with an optional real-time Karoo Live tracking link — delivered directly to their phones via WhatsApp, Telegram, ntfy or Pushover. A faster and more reliable alternative to Karoo's default email notification.
- **Custom message buttons** (up to three independent slots) — send *"I'm OK"*, *"Starting now"*, or any preset message in a single tap from a ride field or a hardware button. No countdown, no emergency.
- **Webhook actions** — fire any HTTP endpoint from a Karoo button: open your garage door (Home Assistant, Shelly), trigger an IFTTT / n8n / Make automation, send a custom push notification, or call any REST API. Two independent slots with optional geo-fencing per slot (e.g. only open the garage when you are actually near home).

The heart-rate-based detectors and the nutrition / hydration tracker are **completely optional** — KSafe works fully without a chest strap or a power meter. Pair one if you want the extra layer of medical-episode monitoring or sensor-aware fueling targets; otherwise the rest of the features run unchanged.

Compatible with Karoo 3 running Karoo OS version 1.527 and later.

> [!IMPORTANT]
> KSafe uses your **phone's internet connection** (via the Hammerhead Companion app) to send messages. Without an active connection between your Karoo and your phone, no alerts or notifications will be sent. Make sure your Karoo is paired and connected to the companion app before riding.

## Features

- **Reactive safety**: automatic crash detection (accelerometer + gyroscope), speed-drop detection, periodic check-in timer (auto-pauses with the ride), manual SOS field, and a configurable countdown with a red full-screen Cancel overlay visible from any screen.
- **HR-based detection** *(v2.0, optional, requires HR sensor)*: medical episode detection (HR flatline / collapse) and a three-tier wellness monitor for fatigue, overexertion, and HR/power decoupling.
- **Preventive nutrition & hydration tracker** *(v2.0, optional)*: per-second carb and fluid targets that adapt to your effort via HR/power zones, two combinable alert modes (deficit and time), 3 carb + 2 drink log slots, FIT-file export so Strava / Intervals.icu / TrainingPeaks plot your fueling alongside HR/power.
- **Four messaging providers**: Telegram (free, unlimited), ntfy (free, unlimited), CallMeBot (WhatsApp, free), Pushover (~$5 one-time). Switch between them without losing credentials.
- **Ride-start and ride-end notifications** to your contacts, with optional real-time Karoo Live tracking link.
- **Custom message buttons** (3 independent slots) — send *"I'm OK"*, *"Heading home"* or any preset text in one tap. No countdown.
- **Webhook actions** (2 slots) — fire any HTTP endpoint from a hardware button (Home Assistant, Shelly, IFTTT, n8n…) with optional **geo-fence** and **on-screen ride alert** per slot.
- **Customisable colours, labels and templates**: every data field has a configurable idle colour from a 12-hue palette; every alert (HR, fueling) has its own customisable title/detail with `{token}` substitution.
- **Anonymous calibration logging** *(opt-in)* — record sensor and algorithm data to help tune crash detection for everyone. See [docs/calibration-logging.md](docs/calibration-logging.md).

## Requirements

- Karoo 3 with Karoo OS version 1.527 or later.
- Phone paired and connected via the **Hammerhead Companion app** (required for internet access to send alerts).
- At least one configured messaging provider.
- **"Draw over other apps" permission** granted to KSafe (required for the SOS cancel overlay — see Installation).

## Installation

1. Open the APK download link on your mobile: `https://github.com/lockevod/Karoo-KSafe/releases/latest/download/ksafe.apk`
2. Share the file with the Hammerhead Companion app.
3. Install through the Hammerhead Companion app.

**It is mandatory to restart the Karoo after installation (shut down and start again).**

### Grant "Draw over other apps" permission (required)

KSafe shows a full-screen SOS cancel overlay during an emergency countdown so you can cancel it from **any screen**, not just when the SOS data field is visible. This requires the **"Draw over other apps"** (overlay) permission — the same permission used by ki2 and karoo-powerbar for their overlays.

**First time you open KSafe after installing:**

The app will automatically open the Android settings screen for this permission. Toggle **KSafe → Allow** and press Back to return to the app.

If you miss it or need to grant it later:

1. Open the **KSafe app** on the Karoo.
2. A yellow warning banner will appear at the top if the permission is not yet granted.
3. Tap **"Enable now"** — it opens the settings screen directly.
4. Toggle **KSafe → Allow** and go back.
5. The banner disappears automatically once the permission is granted.

> [!NOTE]
> Without this permission, the SOS cancel overlay will not appear during a countdown. You can still cancel by tapping the SOS or Safety Timer data field, or using a configured hardware action button.

## Cancelling an Emergency

When a countdown is active you have **three ways** to cancel it:

### 1 — Overlay button (primary, recommended)

KSafe displays a **red overlay** on top of whatever screen is currently visible with a large **CANCEL** button. This works from **any screen** — you don't need to navigate anywhere. Just tap CANCEL.

> [!IMPORTANT]
> The overlay requires the **"Draw over other apps"** permission. Grant it the first time you open the app, or tap **"Enable now"** in the warning banner inside the app. Without this permission the overlay will not appear and you will need to use one of the methods below.

### 2 — Data fields (SOS or Safety Timer)

Both the **SOS field** and the **Safety Timer field** show a CANCEL button with the remaining countdown seconds during an active emergency. Tapping either field cancels immediately.

> [!NOTE]
> This method requires navigating to a ride screen that contains one of those fields. If neither field is visible at the moment of the countdown, you will need to swipe to the right screen before you can tap. For this reason it is recommended to keep the SOS or Timer field on your primary ride screen, or rely on the overlay (method 1) which is always visible.

### 3 — Hardware button via BonusAction (optional)

KSafe registers four actions you can assign to hardware controller buttons or in-ride menu slots:

| Action | What it does |
|--------|-------------|
| **KSafe: Cancel Emergency** | Cancels the active emergency countdown from any screen |
| **KSafe: Send Custom Message** | Sends your configured custom message instantly — no countdown, no emergency screen |
| **KSafe: Webhook Action 1** | Fires the configured HTTP request for webhook slot 1 |
| **KSafe: Webhook Action 2** | Fires the configured HTTP request for webhook slot 2 |

All four actions work from **any screen**, with no need to look at the display. To assign one: on the Karoo go to **Sensors → [your AXS groupset] → Configure Controls**, press the physical shifter button, and pick the KSafe action. **Short Press** and **Long Press** can be assigned independently.

> [!NOTE]
> BonusActions are **exclusive to SRAM AXS controllers** (RED/Force AXS shifters). The option only appears if a SRAM AXS groupset is paired and the Karoo has been restarted after installing KSafe. See the [official Hammerhead guide](https://support.hammerhead.io/hc/en-us/articles/25672636525979-Karoo-OS-Controlling-Karoo-with-SRAM-AXS-Controllers) for details.

## Data Fields

KSafe provides five custom data fields you can add to your ride profiles. Add any combination from the Karoo profile editor; configure them in the KSafe app.

| Field | Idle state | Tap action | Active states |
|-------|-----------|------------|---------------|
| **SOS** | `SAFE` (default green) | Trigger an SOS emergency countdown | Orange countdown with seconds, tap again to cancel · Red `ALERT SENT` briefly after dispatch |
| **Safety Timer** | Remaining check-in time, green→yellow→red as it nears expiry | Reset the check-in timer ("I'm OK") | Orange `CANCEL` + seconds during any emergency · `Timer OFF` if check-in disabled |
| **Custom Message 1 / 2 / 3** | Configured label (default blue), e.g. `OK👍`, `HOME`, `CREW` | Send that slot's configured message immediately — no countdown | Orange `SENDING…` · Green `SENT ✓` · Red `ERR retry` (tap again to retry) |

**Notes:**

- The Safety Timer **pauses automatically** when the ride is paused and resets to the full interval when recording resumes — no false check-in alerts during planned breaks.
- The three Custom Message fields are fully independent (each has its own label, text and idle colour). You can put all three on the same screen.
- Message 1 is also assignable to a hardware button (see [Hardware button via BonusAction](#3--hardware-button-via-bonusaction-optional)).
- Idle colours are picked in the **Settings tab** (SOS, Timer) or the **Actions tab** (Custom Messages).

> [!TIP]
> Tapping a data field is one of three ways to cancel an emergency countdown. See [Cancelling an Emergency](#cancelling-an-emergency) for all three methods and their trade-offs.

## Configuration

Open the KSafe app on your Karoo to configure it. The app has five tabs:

- **Provider** — select and configure the messaging provider (Telegram, ntfy, WhatsApp, Pushover).
- **Settings** — all safety settings: crash detection, check-in timer, speed drop, emergency message, and calibration logging.
- **Actions** — configure custom message buttons (slots 1–3) and webhook actions (slots 1–2) to trigger messages or HTTP endpoints from hardware buttons.
- **Health** — HR-based incident detection: medical episodes and wellness monitor (both optional, both require a paired heart-rate sensor).
- **Fueling** — preventive safety layer: carb and hydration tracking with sensor-aware targets, deficit and time-based reminders, post-ride summary.

### Settings Tab

- **Active**: Enable or disable the extension entirely. When OFF, all monitoring stops (crash, speed-drop, check-in, Health, Fueling) and configured notifications (ride start/end, custom messages, webhooks) are suppressed.
- **SOS field colour**: Choose the idle background colour for the SOS data field (shown when in SAFE state). Select from a palette of 8 preset dark colours with white text.
- **Timer field colour**: Choose the idle background colour for the Safety Timer field (shown when the timer is running normally). Warning (yellow) and expired (red) state colours are always preserved regardless of this setting.
- **Emergency message**: The message sent to your contacts. Available placeholders:
  - `{location}` — GPS coordinates as a Google Maps link.
  - `{reason}` — reason for the alert (crash / check-in expired / manual SOS / speed drop).
  - `{livetrack}` — Karoo Live real-time tracking link (only if a key is configured).
- **Notify contacts on ride start**: Toggle to enable/disable a notification when the ride starts. Sent **only once per ride** — resuming from a pause does not send it again. Use `{livetrack}` in the message to include the tracking link (requires a Karoo Live key).
- **Karoo Live key**: Enter only the key part of your Karoo Live URL. For example, from `https://dashboard.hammerhead.io/live/3738Ag` enter `3738Ag`. Leave empty to send a plain start message without a tracking link.
- **Ride start message**: The text sent when the ride starts. Use `{livetrack}` to insert the tracking link.
- **Notify contacts on ride end**: Toggle to enable/disable a notification when the ride recording stops completely. Does not require a Karoo Live key — any message text works.
- **Ride end message**: The text sent when the ride ends.
- The `{livetrack}` placeholder also works in your emergency message — if a key is set, emergency alerts will include the tracking link too.
- **Countdown seconds**: How long the cancellation countdown lasts before alerts are sent (default: 30s).
- **Crash detection**: Enable/disable automatic crash detection. Configure sensitivity and minimum speed (see [Crash Detection](#crash-detection) for guidance on which level to choose).
- **Max. speed to confirm crash**: The GPS speed below which the rider is considered stopped after an impact (default: **3 km/h** for Low, **5 km/h** for Medium/High). Increase to 8 km/h for MTB/gravel where sliding after a crash is common; lower to 3 km/h for strict road use.
- **Monitor crash when not riding**: Keeps crash detection active even when no ride is recording. Useful for warm-ups or quick spins without starting a recording.
- **Monitor crash when not riding — any speed**: Same as above but ignores the minimum speed threshold (detects crashes even while stationary). ⚠ More false positives — use with caution.
- **Speed drop detection**: Enable/disable detection of prolonged speed drops. Configure the time window (minutes) with no movement before triggering.
- **Check-in timer**: Enable/disable periodic check-ins. Configure the interval in minutes (default: 120 min). A warning beep fires 10 minutes before expiry. **The timer pauses automatically when the ride is paused** (coffee stop, traffic light, etc.) and resets to the full interval when you resume. Any active check-in countdown is also cancelled on pause.

At the bottom of the Settings tab you will find the optional **"Help improve KSafe"** toggle (anonymous calibration data) — disabled by default. See [Calibration Logging](docs/calibration-logging.md) for the full breakdown of what is recorded and how it is sent.

### Health Tab

The Health tab adds two **HR-based detectors** that complement the accelerometer-based crash detection. Both are **optional** and only fire when a heart-rate sensor (ANT+ or BLE) is paired. Without HR data they stay silent.

| Detector | What it watches | Default |
|----------|-----------------|---------|
| **Medical episode** | HR flatline (< 30 bpm × 30 s) or HR collapse (≥ 40 % drop in 10 s) while riding | Enabled, Emergency response |
| **Wellness — Critical HR** | HR ≥ 95 % of max (or 175 bpm) for ≥ 5 min continuous | Off (opt-in), Warning response |
| **Wellness — Sustained HR** | HR ≥ 92 % of max (or 180 bpm) for ≥ 30 min continuous | Off (opt-in), Warning response |
| **Wellness — Cardiac decoupling** | HR/power drift ≥ 7 % vs. ride baseline for ≥ 10 min (requires power meter) | Off (opt-in), Warning response |

Every alert has its own customisable **title** and **detail** template with `{bpm}` / `{threshold}` / `{minutes}` / `{drift}` tokens. Response level per detector: **Silent** (calibration log only), **Warning** (on-screen + beep), or **Emergency** (full crash flow).

📘 **Full reference: [docs/health-fueling.md](docs/health-fueling.md)** — tier-by-tier thresholds, % of max HR vs absolute bpm modes, hysteresis, cooldowns, custom alert text, privacy.

### Fueling Tab

KSafe's **preventive safety layer**: a per-second carb and fluid target while you ride, watches what you log, and warns you when you fall behind. **Disabled by default** because the right targets depend on each rider.

The carb target adapts in real time to your effort using HR / power zones from your Karoo profile (multiplier 0.7×–1.3×). Hydration uses a flat per-hour target by default, with an optional **dynamic estimate** mode that varies with HR, power, weight, temperature and humidity.

| Setup | Carb multiplier source | Notes |
|-------|------------------------|-------|
| Power meter + power zones | 0.7..1.3 from your power zone | Most accurate |
| HR sensor + HR zones (no power) | 0.7..1.3 from your HR zone | Good fallback |
| Neither sensor present | 1.0 (neutral) | Flat target × time |

Two combinable alert modes per category (**deficit** vs **time**), each with customisable title / detail templates and tokens (`{deficit}`, `{elapsed}`, `{target}`). Logging via 3 carb + 2 drink data fields (one tap = one log) and 2 SRAM AXS hardware buttons. Post-ride summary. **Cumulative carbs and hydration are written to the FIT file as developer fields** so they appear as graphs in Strava / Intervals.icu / TrainingPeaks.

📘 **Full reference: [docs/health-fueling.md](docs/health-fueling.md)** — intensity multiplier curves, dynamic sweat-rate estimator with safety bias, alert modes, FIT export schema, privacy.

### Provider Tab

Select which provider will be used to send alerts. You can configure credentials for all four providers — they are saved independently and switching between them does not erase anything. Only the **selected (active) provider** will be used when an alert is triggered.

| Provider | Cost | Best for |
|----------|------|----------|
| **Telegram** | Free, unlimited | Best free option — no limits, no account needed beyond a bot |
| **ntfy** | Free, unlimited | Quickest setup — no account, just pick a topic name |
| **CallMeBot (WhatsApp)** | Free | Recipients already use WhatsApp |
| **Pushover** | Free trial, ~$5 one-time | Most reliable push notifications |

📘 **Setup guides: [docs/messaging-providers.md](docs/messaging-providers.md)** — step-by-step for each provider (where to click, what to copy, how to test).

## How It Works

1. KSafe runs in the background while you ride.
2. When a trigger occurs (crash detected, check-in expired, speed drop, or manual SOS tap), a countdown starts with audible beeps and a **red SOS overlay** appears immediately on top of whatever screen is currently visible, with a large **CANCEL** button.
3. During the countdown, you can cancel by: tapping the **CANCEL button on the overlay** (works from any screen), tapping the **SOS field** or **Safety Timer field** (requires navigating to the screen with the field), or pressing the configured **KSafe: Cancel Emergency** hardware button (works from any screen).
4. If the countdown completes without cancellation, KSafe obtains your current GPS location and sends the configured emergency message via the configured provider.
5. After sending, the extension returns to idle state and normal monitoring resumes.

> [!NOTE]
> The SOS overlay requires the **"Draw over other apps"** permission. See the [Installation](#installation) section for setup instructions. Without it, you can still cancel via the SOS/Timer data fields or a hardware button.

## Crash Detection

Crash detection uses the Karoo's built-in accelerometer and gyroscope directly (no external sensor required). The algorithm is based on the same approach used by Garmin's incident detection: **large impact followed by genuine stillness**.

### Algorithm

1. **Impact**: A sudden acceleration spike above the sensitivity threshold is detected (while above the minimum speed). To reject single-sample noise — such as the jolt when transitioning from dirt to asphalt or hitting a small stone — the algorithm uses a short sliding-window average (~60 ms). A genuine impact is sustained energy across multiple sensor frames; a terrain-edge spike is typically just 1–2 raw samples and gets smoothed out.
2. **Speed check**: The impact phase only advances to silence check if the GPS speed has already dropped below the configured **max. confirm speed** (default **3 km/h** for Low, **5 km/h** for Medium/High — configurable in Settings). If the rider is still moving after the impact, the algorithm keeps waiting or resets — a real crash victim cannot continue riding.
3. **Silence check**: After the impact, both the accelerometer and gyroscope must settle completely. The device must stop moving AND stop rotating, AND the GPS speed must remain below the configured threshold. **The stillness must be continuous** — any movement (gyro ≥ 2 rad/s, acceleration deviation > 4 m/s² from gravity, or GPS speed above threshold) resets the 4.5 s countdown from scratch. This is the key differentiator between a real crash and any other event (pothole, bump, jump, terrain change) followed by continued riding.
4. **Confirmed**: If the device remains genuinely still for **4.5 consecutive seconds**, the emergency countdown starts.
5. **Cooldown**: After a confirmed crash, impact detection is paused for **countdown + 30 s** to avoid duplicate triggers while the emergency countdown is already running (e.g. 60 s with the default 30 s countdown).

**Why this works:** after hitting a pothole, bump, or terrain-change edge, a cyclist continues pedalling — the GPS keeps showing movement and the gyroscope never stays below 1 rad/s long enough to confirm a crash. After a real crash, the device lies on the ground with near-zero gyroscope and near-zero GPS speed for several seconds.

### Choosing the right sensitivity level

> **Naming convention**: "High sensitivity" means a *lower* impact threshold — the system reacts to lighter impacts. This follows the standard sensor convention (higher sensitivity = detects smaller signals). It does NOT mean "better" or "safer" in all contexts.

| Level | Impact threshold | Peak threshold | Min. speed | Confirm speed | Impact window | Best for |
|-------|-----------------|----------------|------------|---------------|---------------|----------|
| ⛰ **Low** | 55 m/s² (~5.5g) | 60 m/s² | 3 km/h | 3 km/h | 25 s | MTB, enduro, technical terrain |
| 🚴 **Medium** | 45 m/s² (~4.5g) | 50 m/s² | 10 km/h | 5 km/h | 20 s | Road, MTB mixed, gravel **(recommended default)** |
| 🏁 **High** | 35 m/s² (~3.5g) | 40 m/s² | 15 km/h | 5 km/h | 15 s | Smooth road only (velodrome, closed circuit) |
| 🔧 **Custom** | 20–70 m/s² slider | thr + 5 m/s² | You choose | You choose | Preset-aware | Any specific use case |

> [!WARNING]
> Do **not** use High on MTB trails, gravel, or roads with potholes/expansion joints. Any jump or large bump regularly exceeds 3.5g and would trigger the impact detector. Even with the continuous-silence check, a brief stop after a bump could produce a false alarm.

You can always override the min. speed manually after selecting a preset (setting it to 0 disables the speed gate entirely — useful for testing).

📘 **Per-preset commentary, real-world scenarios (pothole at 40 km/h, MTB jump landing, expansion joint, slow technical climb…) and the full algorithm internals: [docs/crash-detection-algorithm.md](docs/crash-detection-algorithm.md).**

## Testing

KSafe provides test buttons, all of which work **without an active ride**:

| Button | Where | What it does |
|--------|-------|--------------|
| **Test Send** | Provider tab | Sends a test message via the active provider. Shows a specific error (invalid key, missing credentials, no connection…) if something is wrong. |
| **Simulate Crash** | Settings tab | Sends your emergency message immediately — no countdown, no waiting. Use this to verify the full message (location, livetrack link) reaches your contact correctly. |
| **Test ride start notification** | Settings tab | Sends the ride-start message. Only works if the feature is enabled. |
| **Test ride end notification** | Settings tab | Sends the ride-end message. Only works if the feature is enabled. |
| **Send Message 1 / 2 / 3** | Actions tab | Sends each custom message immediately. Only works if that message slot is enabled. |

> **Simulate Crash** sends a real alert to your configured contact. Let them know you are testing, or use **Test Send** instead if you only want to verify connectivity.

## Actions Tab

The **Actions tab** contains two independent feature groups: **Custom Messages** (message buttons 1–3) and **Webhook Actions** (HTTP request slots 1–2). Both are configured in the same tab and can be assigned to Karoo hardware controller buttons via BonusAction.

### Custom Messages

KSafe provides **three independent custom message buttons** — you can add one, two or all three to your ride screens. Each sends a different text and shows a different label on the field button. No countdown, no emergency — just a quick status update.

**Use cases:**

- *"OK👍"* → sends *"I'm OK! 👍"* — reassure your contacts after a long silent stretch
- *"HOME"* → sends *"Heading home, ETA 45min"*
- *"CREW"* → sends a message to your support crew
- *"START"* → manual ride start notification without Karoo Live
- Any short status you want to send on demand

**Configuration:**

1. Open KSafe → **Actions tab**.
2. For each message slot (1, 2, 3):
   - Toggle **Enable message N**. When the slot is disabled, the rest of the row collapses for a tidier screen, and the on-ride field renders in grey with `OFF` (tap is suppressed).
   - Enter a **button label** (max 7 characters) — appears on the Karoo field button. Examples: `OK👍`, `HOME`, `SAFE`, `CREW`. Defaults: `MSG`, `MSG2`, `MSG3`.
   - Enter the **message text** that will be sent when the field is tapped (any length).
   - Choose the **idle colour** for the field using the colour swatch button (12 dark hues; reserved state colours are excluded so your pick can never collide with a state-machine signal).
   - Tap **Send Message N** to test it immediately from the app.
3. Add **KSafe Message 1**, **KSafe Message 2**, and/or **KSafe Message 3** as data fields in your Karoo ride profile.

To assign **KSafe: Send Custom Message** (slot 1 only) to a SRAM AXS button: **Sensors → [your AXS groupset] → Configure Controls**, press the shifter button, select the KSafe action. **Short Press** and **Long Press** can be bound independently.

### Webhook Actions

KSafe provides **two configurable webhook action buttons** that you can assign to Karoo hardware controller buttons. Each fires an HTTP request to any URL you configure — useful for triggering automations while riding without touching your phone.

**Use cases:**

- Open your garage door (Home Assistant, Shelly, any smart relay)
- Send yourself a push notification (ntfy)
- Trigger an IFTTT / n8n / Make automation
- Toggle any smart home device
- Call any REST API or webhook

The requests are sent **via the Karoo network bridge** — the same mechanism used for emergency alerts. They work over Bluetooth tether even when the Karoo is not on Wi-Fi.

**Configuration:**

1. Open KSafe → **Actions tab**.
2. For each webhook slot (1 and 2):
   - Toggle **Enable Webhook N**.
   - Enter a **label** — shown in the in-ride notification when the action fires.
   - Enter the **URL** of the endpoint to call.
   - Choose **GET** or **POST**.
   - Optionally enter a **header** (one line, format `Key: Value`) — required for authentication with many services.
   - For POST requests, optionally enter a **body** (JSON or `key=value` form-encoded).
   - Choose the **idle colour** for the webhook field.
   - **Only trigger when near location** *(optional)*: enable the geo-fence toggle, tap **Use current GPS location as target** (or enter coordinates manually), and set a radius in metres.
   - **Show ride alert when triggered** *(optional)*: enable the alert toggle and enter a custom text. An on-screen notification appears every time the action fires — useful to notice accidental button presses.
   - Tap **Test Webhook N** to verify it works before assigning to a button.
3. Assign to a SRAM AXS button via **Sensors → [your AXS groupset] → Configure Controls**.

When you press the configured button during a ride, the HTTP request fires immediately and you get an in-ride notification showing success or failure. If the webhook is disabled or the URL is blank, you receive a notification explaining why — the field is always tappable so you can confirm at a glance.

📘 **Recipe collection: [docs/webhooks-cookbook.md](docs/webhooks-cookbook.md)** — copy-paste examples for Home Assistant, Shelly (local + cloud), ntfy, IFTTT, n8n / Make.

## Field Colours

Every KSafe ride field has a customisable **idle background colour** picked from a palette of 8 dark hues (legible with white text on a Karoo display). State-driven colours (red error, orange countdown, amber warning, green success, grey OFF) are **reserved by the state machine** so your idle pick can never collide with a runtime signal.

Idle colours are configured in the **Settings tab** (SOS, Safety Timer), the **Actions tab** (Custom Messages, Webhooks) and the **Fueling tab** (carb / drink slots, v2.0).

📘 **Full palette, state-driven colour table, per-field configuration paths: [docs/field-colours.md](docs/field-colours.md)**

## Backup and Restore

KSafe lets you export and restore your entire configuration (API keys, tokens, messages, all settings) from the **Settings tab**, at the bottom of the screen.

### Exporting your configuration

Tap **Export** — KSafe writes your configuration to:

```
/sdcard/Android/data/com.enderthor.kSafe/files/ksafe_export.json
```

You can retrieve this file with ADB:

```bash
adb pull /sdcard/Android/data/com.enderthor.kSafe/files/ksafe_export.json
```

### Restoring a configuration

To import a configuration, place the file at this exact path **with this exact name**:

```
/sdcard/Android/data/com.enderthor.kSafe/files/ksafe_import.json
```

You can push it with ADB:

```bash
adb push ksafe_export.json /sdcard/Android/data/com.enderthor.kSafe/files/ksafe_import.json
```

Then tap **Import** in the Settings tab. KSafe will read `ksafe_import.json` and apply the configuration immediately.

> The export and import files have intentionally different names so there is no risk of accidentally overwriting a backup you just made.

## Tips

### Easiest way to enter API keys and tokens

Typing long tokens (Pushover App Token, Telegram Bot Token, etc.) on the Karoo touchscreen is tedious and error-prone. The fastest workflow is:

1. Open KSafe on your Karoo and tap **Export** (Settings tab, bottom of screen).
2. Pull the file to your computer with ADB:
   ```bash
   adb pull /sdcard/Android/data/com.enderthor.kSafe/files/ksafe_export.json
   ```
3. Open `ksafe_export.json` in any text editor. The exported file has a dedicated block for each provider, each with only the fields that provider actually uses:

   | Provider block | Field | Description |
   |----------------|-------|-------------|
   | `callmebot` | `apiKey` | API key obtained from callmebot.com |
   | `callmebot` | `phoneNumber` | Recipient WhatsApp number with international prefix, no `+` (e.g. `34612345678`) |
   | `pushover` | `appToken` | Application token from pushover.net |
   | `pushover` | `userKey` / `userKey2` / `userKey3` | Up to 3 recipient user/group keys |
   | `ntfy` | `topic` | Topic name chosen by you (e.g. `ksafe-alerts-myname`) |
   | `telegram` | `botToken` | Bot token from @BotFather |
   | `telegram` | `chatId` / `chatId2` / `chatId3` | Up to 3 chat / channel / group IDs |

   Example after editing (showing Telegram and Pushover):

   ```json
   {
     "config": { "isActive": true, "crashDetectionEnabled": true },
     "callmebot": {
       "apiKey": "1234567",
       "phoneNumber": "34612345678"
     },
     "pushover": {
       "appToken": "azGDORePK8gMaC0QP344AMyzxxxx",
       "userKey": "uQiRzpo4DXghDm3xxxxfQu",
       "userKey2": "",
       "userKey3": ""
     },
     "ntfy": {
       "topic": "ksafe-alerts-myname"
     },
     "telegram": {
       "botToken": "7123456789:AAFxxxxxxxxxxxx",
       "chatId": "123456789",
       "chatId2": "",
       "chatId3": ""
     }
   }
   ```

   > **Telegram note**: the Chat ID is required — the bot needs to know which chat/group/channel to deliver to (a bot can be in many chats at once). See [docs/messaging-providers.md](docs/messaging-providers.md) for how to get yours.

4. Save the file and push it back as `ksafe_import.json`:
   ```bash
   adb push ksafe_export.json /sdcard/Android/data/com.enderthor.kSafe/files/ksafe_import.json
   ```
5. Tap **Import** in KSafe — all keys are applied instantly.

> [!TIP]
> You can also use this workflow to back up your configuration before updating the app, or to copy your setup to another Karoo device.

> [!NOTE]
> The import is tolerant: you can fill in only the providers you use and leave the rest empty. Unknown or extra fields are silently ignored, so imports from older or newer versions of KSafe always work.

## Known Issues

- Alerts will not be sent if the Karoo has no internet connection at the time of the emergency.
- Crash detection can produce false positives if you stop completely for several seconds right after hitting a large pothole or expansion joint. The 30 s countdown is your safety net — tap CANCEL if you are fine.
- If your GPS signal is lost or delayed (tunnel, tree cover), the speed gate may not fire correctly. In that case the gyroscope and accelerometer checks are the only active guard.
- Each messaging provider has its own rate limits and free tier restrictions. Check provider documentation.
- By default, the extension only monitors during an active ride (Recording state). Crash detection remains active when the ride is paused (the rider may have crashed). Use the **"Monitor crash when not riding"** options in Settings to enable monitoring outside of a recorded ride.

## Disclaimer

KSafe is designed to **improve your safety** while cycling, but it is **not a substitute for professional emergency services** and cannot guarantee that an alert will be sent in every situation.

There are many conditions under which KSafe may fail to detect an incident or deliver an alert, including but not limited to:

- **No phone connectivity** — the Karoo must be paired and connected to the Hammerhead Companion app. If the phone is out of range, turned off, has no internet access, or the companion app is not running, no alert will be sent.
- **Phone not carried** — if you ride without your phone, no alerts can be sent.
- **Undetected crash** — crash detection is based on accelerometer and gyroscope thresholds. Some crashes may not generate a detectable impact pattern, and some non-crash events may trigger a false alert.
- **GPS unavailable** — if the device has no GPS fix, the location included in the alert will be unavailable.
- **Provider outages** — third-party messaging services (Telegram, ntfy, CallMeBot, Pushover) may be unavailable due to outages outside of KSafe's control.
- **Misconfiguration** — incorrect API keys, tokens, or phone numbers will prevent alerts from being delivered.
- **Device failure** — if the Karoo device loses power, crashes, or malfunctions, no alerts will be sent.

**KSafe is provided "as is", without warranty of any kind, express or implied.** The developer (lockevod) accepts no responsibility or liability for any harm, injury, loss, or damage arising from the use or inability to use this application, including cases where an emergency alert was not sent or not received.

**By installing and using KSafe you accept full responsibility for your own safety.** If you do not agree with these terms, do not use this application.

> [!WARNING]
> KSafe is a safety aid, not a safety guarantee. Always carry your phone, ensure it is connected to the Karoo, and let someone know your planned route — regardless of whether KSafe is active.

## Privacy

- KSafe does not collect or transmit any personal data beyond what is strictly necessary to send your emergency alerts (location and the message you configure).
- All configuration is stored locally on your Karoo device.
- When you use a third-party provider (CallMeBot, Pushover, ntfy, Telegram), your message content and identifiers (phone number, chat ID, user key…) can be shared with that provider. Please read and accept their terms and privacy policies before using KSafe.
- KSafe has no relationship or partnership with any of these providers.
- The optional **anonymous calibration data** toggle (Settings tab, disabled by default) records sensor / algorithm data only — no GPS coordinates, no messages, no personal identifier. See [docs/calibration-logging.md](docs/calibration-logging.md) for the full disclosure.
- KSafe has no warranties. If you do not agree with this, please do not use it.

## Documentation

### User guides (`docs/`)

- **[Messaging providers — full setup](docs/messaging-providers.md)** — step-by-step for ntfy, CallMeBot, Telegram, Pushover.
- **[Health & Fueling — full reference](docs/health-fueling.md)** — every field of the Health and Fueling tabs, tier thresholds, FIT-file export schema, custom alert tokens.
- **[Webhook cookbook](docs/webhooks-cookbook.md)** — copy-paste recipes for Home Assistant, Shelly, ntfy, IFTTT, n8n / Make + hardware button assignment.
- **[Field colours](docs/field-colours.md)** — idle palette, reserved state colours, and where to change each field's colour.
- **[Calibration logging](docs/calibration-logging.md)** — full disclosure of what the opt-in sensor logger records and how it is sent.

### Algorithm internals (for contributors and curious riders)

If you want to understand exactly how each detection / tracking algorithm works — the thresholds, the rationale behind each constant, the edge cases handled, and the open items — three companion documents cover the technical details:

- **[Crash detection algorithm](docs/crash-detection-algorithm.md)** — multi-stage confirmation pipeline (`MONITORING → IMPACT → SILENCE_CHECK → CRASH_CONFIRMED`), dual smoothed/peak detector, GPS-stale fallback, terrain-cluster boost, cadence gate, calibration history.
- **[Medical episode & wellness algorithms](docs/medical-wellness-algorithm.md)** — HR-flatline + HR-collapse sub-detectors, the `% of max HR` wellness mode, why both sub-detectors complement each other, sensor-disconnect handling.
- **[Nutrition & hydration algorithms](docs/fueling-algorithm.md)** — `IntensityZoneCalculator` (HR / power zones from the Karoo profile, 0.7×–1.3× multiplier), the **target-rate vs burn-rate** explanation (why the multiplier range is anti-bonk strategy rather than pure physiology), dual-mode alerts, initial delay, post-ride summary.

These docs are aimed at contributors and at riders who want to understand the *why* behind the defaults before tuning their own setup. Reading them is not required to use KSafe.

## Credits

- Developed by EnderThor.
- Uses the Karoo Extensions Framework by Hammerhead.
- Can use CallMeBot for WhatsApp message delivery, Pushover for push notifications, ntfy for free push notifications, and Telegram Bot API for Telegram messages. These services have their own rules and agreements. KSafe has no relationship with any of them.
- Thanks to Hammerhead for the Karoo device and extensions API.

## Useful Links

- [Karoo Extensions Framework](https://github.com/hammerheadnav/karoo-ext)
- [CallMeBot WhatsApp API](https://www.callmebot.com/blog/free-api-whatsapp-messages/)
- [Telegram BotFather](https://t.me/BotFather)
- [DC Rainmaker sideloading guide](https://www.dcrainmaker.com/2021/02/how-to-sideload-android-apps-on-your-hammerhead-karoo-1-karoo-2.html)
