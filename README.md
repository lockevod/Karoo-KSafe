# KSafe - Safety Extension for Karoo

> [!IMPORTANT]
> This app is currently in early stage. If you want to improve the app, you can activate the toggle in the app (in settings) and send me the calibration data after your rides (anonymous). The more data I get, the better I can tune the crash detection algorithm for all users. See the [Calibration Logging](#calibration-logging-optional) section for details.
> [!WARNING]
> This extension can send emergency alerts to your contacts. Please test it carefully before relying on it in real situations.

KSafe is a free, open-source safety extension for Karoo GPS devices. It monitors your ride and automatically alerts your emergency contacts if something goes wrong — crash detected, no check-in, or speed suddenly drops — and allows you to manually trigger an SOS from your ride screen.

Beyond emergency alerts, KSafe can also notify your contacts when you **start or finish a ride**, optionally including a real-time Karoo Live tracking link. This is a better alternative to the default email notification that Karoo already offers: KSafe sends it via WhatsApp, Telegram, or push notification, directly to your contacts' phones.

Compatible with Karoo 3 running Karoo OS version 1.527 and later.

> [!IMPORTANT]
> KSafe uses your **phone's internet connection** (via the Hammerhead Companion app) to send messages. Without an active connection between your Karoo and your phone, no alerts or notifications will be sent. Make sure your Karoo is paired and connected to the companion app before riding.

## Features

- **Crash detection**: Uses the Karoo's accelerometer and gyroscope to detect sudden impacts automatically.
- **Manual SOS**: Tap the SOS data field to trigger an emergency alert manually.
- **Check-in timer**: Set a periodic check-in interval. If you don't tap "OK" before the timer expires, an alert is sent automatically. **The timer pauses automatically when the ride is paused** (coffee stop, etc.) and resets when you resume — no alerts during planned breaks.
- **Speed drop detection**: Detects when your speed drops suddenly and remains low for a configurable time window.
- **Emergency countdown with cancel**: All triggers start a configurable countdown (default 30s) so you can cancel false alarms before alerts are sent. A **red overlay with a Cancel button** appears on top of the ride screen — visible from any screen, no matter which data field is active.
- **Location included**: Your GPS coordinates are automatically included in the alert message as a Google Maps link.
- **Multiple messaging providers**: WhatsApp via CallMeBot (free), push notification via Pushover, free unlimited push via ntfy, or Telegram bot messages (free, unlimited).
- **Ride start notification**: Optionally sends a message to your contacts when you start a ride, including a Karoo Live real-time tracking link. Sent **only once** when the ride truly begins — resuming after a pause does **not** trigger it again.
- **Ride end notification**: Optionally sends a configurable message to your contacts when you finish a ride (recording stops completely).
- **Custom message buttons**: Send any custom text message instantly via a hardware button or the app — no countdown, no emergency. Useful for "I'm OK", "Starting now", or any quick status update to your contacts. **Up to three independent message buttons** are available, each with its own configurable label and text.
- **Five data fields**: SOS field, Safety Timer field, and up to three Custom Message fields — add any combination to your ride profile.
- **Help improve KSafe** *(optional)*: Enable anonymous calibration data sending to help tune the crash detection algorithm. See [Calibration Logging](#calibration-logging-optional) for details.

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

KSafe registers two actions you can assign to hardware controller buttons:

| Action | What it does |
|--------|-------------|
| **KSafe: Cancel Emergency** | Cancels the active emergency countdown from any screen |
| **KSafe: Send Custom Message** | Sends your configured custom message instantly — no countdown, no emergency screen |

Both work from **any screen**, with no need to look at the display.

> [!NOTE]
> `BonusAction` availability depends on your controller hardware/firmware. It is commonly used with compatible remotes (e.g. SRAM AXS controls). If your controller does not expose extension bonus actions, this option will not appear in the button assignment screen.

To configure either action:
1. Go to Karoo **Settings → Controller** (or your ANT+ remote settings).
2. Find the button you want to assign the action to.
3. Select **KSafe: Cancel Emergency** or **KSafe: Send Custom Message** from the action list.

Once configured, pressing the button activates the action immediately from any screen.

## Data Fields

KSafe provides five custom data fields you can add to your ride profiles:

### SOS Field
- Shows **SAFE** (green) when everything is OK.
- **Tap** to manually trigger an SOS emergency countdown.
- During countdown shows remaining seconds in orange — **tap again to cancel**.
- Shows **ALERT SENT** (red) briefly when alerts have been dispatched.

### Safety Timer Field
- Shows remaining check-in time (green/yellow/red depending on urgency).
- **Tap** to reset the timer (= "I'm OK" check-in).
- During any active emergency countdown, shows **CANCEL** with remaining seconds — **tap to cancel**.
- Shows **Timer OFF** when check-in is disabled.
- The timer **pauses automatically when the ride is paused** and resets to the full interval when recording resumes.

### Custom Message Fields (1, 2 and 3)

KSafe provides **three independent custom message fields** — **KSafe Message 1**, **KSafe Message 2**, and **KSafe Message 3**. Each field works identically and independently:

- Shows the **configured button label** (blue) when ready — e.g. *"OK👍"*, *"HOME"*, *"CREW"*.
- **Tap** to send that field's configured message instantly — no countdown, no emergency.
- Shows **SENDING…** (orange) while the message is being sent.
- Shows **SENT ✓** (green) on success, then returns to ready after a few seconds.
- Shows **ERR retry** (red) if sending failed — **tap again to retry**.

Each field has its own label (shown on the button) and message text (what gets sent). You can add one, two, or all three to any ride screen. Message 1 is also assignable to a hardware button (see [Hardware button via BonusAction](#3--hardware-button-via-bonusaction-optional)).

Add one or more fields to your Karoo ride profile from the profile editor.

> [!TIP]
> Tapping a data field is one of three ways to cancel an emergency countdown. See the [Cancelling an Emergency](#cancelling-an-emergency) section for all three methods and their trade-offs.

## Configuration

Open the KSafe app on your Karoo to configure it.

### Settings Tab

- **Active**: Enable or disable the extension entirely.
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
- **Custom messages**: Enable up to three independent message buttons. For each one:
  - Toggle **Enable message N** to activate it.
  - Enter a **button label** (max 5 characters) — this is what appears on the Karoo field (e.g. *"OK👍"*, *"HOME"*, *"SAFE"*). Defaults to `MSG`, `MSG2`, `MSG3`.
  - Enter the **message text** that will be sent when the field is tapped.
  - Tap **Send Message N** to send it immediately from the app to verify it works.
  - **Message 1** can also be assigned to a hardware button (**KSafe: Send Custom Message**) in Karoo controller settings.

### Calibration Logging (optional)

At the very bottom of the Settings tab you will find the **"Help improve KSafe"** section with a single toggle: **Send anonymous calibration data**.

This feature is **disabled by default** and completely optional. Enabling it helps the developer improve and calibrate the crash detection algorithm over time, using real-world data from different riding styles and terrain types.

#### What data is collected

When enabled, KSafe records detailed sensor events to a local CSV file:

| Data recorded | Examples |
|---|---|
| Accelerometer magnitude values | `raw=52.3 m/s²`, `smooth=49.1 m/s²` |
| Detection thresholds in use | `threshold=45 m/s²`, `peakThreshold=50 m/s²` |
| GPS speed at the moment of each event | `speed=28.4 km/h` |
| Crash detection state | `MONITORING`, `IMPACT`, `SILENCE_CHECK` |
| Sensitivity preset active | `preset=MEDIUM` |
| Gyroscope magnitude | `gyro=0.82 rad/s` |
| GPS stale flag | `gps_stale=false` |
| Elapsed ride time | `elapsed_s=1247.3` |

#### What is NOT collected

- ❌ GPS coordinates — no location data, no maps, no tracking
- ❌ Emergency messages or contact information
- ❌ Device identifiers, account data, or any user-identifiable information
- ❌ Anything related to who you are or where you ride

The data consists exclusively of raw sensor readings and algorithm states — the same numbers the crash detection algorithm reads internally. It is not possible to identify you, your location, your route, or your contacts from this data.

#### How the data is sent

The CSV file is sent automatically to the developer via Telegram (a private bot) when you:
- **Disable** the calibration logging toggle, or
- **Finish a ride** (if logging was active during the ride)

You can also tap **Send now** to transmit the current log immediately. The file is typically 50–400 KB for a 2–4 hour ride session.

#### Why this helps

Crash detection thresholds (impact magnitudes, silence durations, speed gates) need to be tuned to real-world conditions across different riding disciplines — MTB, gravel, road, velodrome. Each discipline generates a different noise floor and a different impact distribution. The calibration data allows the developer to:

- Understand the terrain noise distribution at different speed/terrain combinations
- Identify conditions where the speed gate is too aggressive (misses real crashes)
- Identify conditions that produce false positives (terrain spikes that look like crashes)
- Tune the SILENCE_CHECK duration and deviation thresholds to real post-crash physics

This data is processed by the developer and never shared with third parties.

### Provider Tab

Select which provider will be used to send alerts. You can configure credentials for all four providers — they are saved independently and switching between them does not erase anything. Only the **selected (active) provider** will be used when an alert is triggered.

KSafe supports **four providers**:

| Provider | Cost | Best for |
|----------|------|----------|
| **Telegram** | Free, unlimited | Best free option — no limits, no account needed beyond a bot |
| **ntfy** | Free, unlimited | Quickest setup — no account, just pick a topic name |
| **CallMeBot (WhatsApp)** | Free | Recipients already use WhatsApp |
| **Pushover** | Free trial, ~$5 one-time | Most reliable push notifications |

#### ntfy (free and unlimited — easiest setup)

ntfy.sh is the simplest option: no account, no registration, no limits. Just pick a topic name and subscribe to it in the ntfy app. Notifications are delivered instantly, for free, with no monthly caps.

> [!NOTE]
> The topic name acts like a "channel" — anyone who knows it can subscribe and receive your alerts. Use a long, random-looking name (e.g. `ksafe-alerts-r4nd0m42`) to keep it private. You can also self-host an ntfy server for full privacy.

**Step 1 — Install ntfy and subscribe to your topic**

1. Install the **ntfy** app on the phone that will receive alerts:
   - [Android](https://play.google.com/store/apps/details?id=io.heckel.ntfy)
   - [iOS](https://apps.apple.com/app/ntfy/id1625396347)
2. Open the app and tap **+** to add a new subscription.
3. Choose a **topic name** (e.g. `ksafe-alerts-myname`). You can use any name — just make it unique enough that others are unlikely to guess it.
4. Tap **Subscribe**.

**Step 2 — Configure KSafe**

1. In the **Provider** tab, select **ntfy**.
2. Enter the same **topic name** you subscribed to in Step 1.
3. Tap **Test Send** — you should receive a push notification in the ntfy app immediately.

> You can add the same topic on multiple phones to alert several people at once. Each person just subscribes to the same topic name in their ntfy app.

---

#### WhatsApp via CallMeBot (free and easy)

CallMeBot lets you send WhatsApp messages for free using a simple API. **Important: the setup must be done from the phone that will RECEIVE the alerts** (your emergency contact's phone), not yours.

**Step 1 — Activate CallMeBot from the contact's phone**

1. Open WhatsApp on the **destination phone** (your emergency contact's).
2. Add the number `+34 644 59 77 96` to their contacts (save it as "CallMeBot" or any name).
3. Send this exact message to that number via WhatsApp:
   ```
   I allow callmebot to send me messages
   ```
4. Within a few minutes CallMeBot will reply with your **API key** (a numeric code). Save it.

> If the contact doesn't receive a reply, try again after a few minutes. The number may be busy. You can also try via the [CallMeBot website](https://www.callmebot.com/blog/free-api-whatsapp-messages/) for alternative activation methods.

**Step 2 — Configure KSafe**

1. In the **Provider** tab, select **CallMeBot**.
2. Enter the **recipient's phone number** with the international prefix but **without the `+` sign** (e.g. `34675123123`) in the phone field.
3. Enter the **API key** received in Step 1.
4. Tap **Test Send** — your contact should receive a WhatsApp message within seconds.

---

#### Telegram (free and unlimited)

Telegram lets you send messages for free through a bot you create yourself. There are no rate limits or monthly caps — it is a great option if you want a reliable free provider with no restrictions.

**Step 1 — Create a Telegram bot and get your Bot Token**

1. Open Telegram and search for **@BotFather**.
2. Start a chat and send `/newbot`.
3. Follow the instructions: choose a name and a username for your bot (username must end in `bot`, e.g. `MySafetyBot`).
4. BotFather will give you a **Bot Token** (e.g. `7123456789:AAFxxxxxxxxxxxxxxxxxxxxxx`). Copy it.

**Step 2 — Get your Chat ID**

The Chat ID tells the bot where to deliver the message. You can send alerts to a **personal chat**, a **group**, or a **channel**.

For a **personal chat** (easiest):
1. Search for your new bot in Telegram and tap **Start** (`/start`).
2. **Send any message to the bot** (e.g. `hello`) — this is required so the bot has an update to return.
3. Go to `https://api.telegram.org/bot<BOT_TOKEN>/getUpdates` in a browser (replace `<BOT_TOKEN>` with your bot token).
4. Look for `"chat":{"id":XXXXXXX}` in the response — that number is your **Chat ID**.

For a **group** or **channel**:
1. Add the bot to the group/channel as an administrator.
2. Send a message in the group, then fetch `getUpdates` as above — the Chat ID will be a negative number (e.g. `-1001234567890`).

**Step 3 — Configure KSafe**

1. In the **Provider** tab, select **Telegram**.
2. Enter your **Bot Token** in the first field.
3. Enter your **Chat ID** (recipient 1) in the second field.
4. Optionally enter a second and third Chat ID to alert additional chats. In most cases one Chat ID is enough — if you want to alert multiple people at once, simply add the bot to a **Telegram group** and use the group's Chat ID.
5. Tap **Test Send** — all configured chats should receive a message immediately.

> If you don't receive the test message, make sure you have started a conversation with the bot first (send `/start` to it in Telegram).

---

#### Pushover (recommended for reliability)

Pushover delivers push notifications instantly to any phone or tablet. It works independently of WhatsApp — the emergency contact only needs the free Pushover app installed. Each user sets up their own independent account.

Pushover is **free to try for 30 days**. After that, a **one-time payment of ~$5** is required to continue using it without restrictions (unlimited messages, emergency priority alerts, no monthly limits). This payment goes directly to Pushover — KSafe itself is always free and open-source. If you prefer a free alternative with no payment, use CallMeBot (WhatsApp) or ntfy (free, unlimited push notifications).

Pushover requires **two separate keys** — this is a common point of confusion:

| Key | What it is | Where to find it |
|-----|-----------|-----------------|
| **App Token** | Identifies the *application* sending the alert (KSafe) | Created once at pushover.net/apps/build |
| **User Key** | Identifies the *device/account* that will receive the alert | Found in the Pushover app on each recipient's phone → Settings |

Both are mandatory. Without the App Token, Pushover doesn't know which app is sending. Without the User Key, it doesn't know where to deliver the notification.

KSafe supports **up to 3 User Keys** — one per field in the Provider tab. Each key belongs to a different person or device. The App Token is shared: you only create it once and use it for all recipients.

**Step 1 — Create a Pushover account and get your User Key**

1. Go to [pushover.net](https://pushover.net/) and create a free account.
2. Install the **Pushover** app on the phone that will receive alerts (your emergency contact's phone or your own) and log in.
3. Open the **Pushover app** on that phone → go to **Settings** — the **User Key** is shown there (e.g. `uQiRzpo4DXghDmr9QzzfQu`). Copy it. This key is tied to that specific device/account and is needed so KSafe knows where to deliver the notification.

**Step 2 — Create a Pushover application and get your App Token**

Each person who uses KSafe needs to create their own Pushover application (it is free and takes 1 minute):

1. Go to [pushover.net/apps/build](https://pushover.net/apps/build) while logged in.
2. Fill in:
   - **Name**: `KSafe` (or anything you like)
   - **Type**: Application
   - **Description**: optional
   - **URL**: optional
3. Click **Create Application**. You will get an **App Token** (e.g. `azGDORePK8gMaC0QOYAMyEEuzJnyUi`). Copy it. This key identifies KSafe as the sender.

**Step 3 — Configure KSafe**

1. In the **Provider** tab, select **Pushover**.
2. Enter your **App Token** in the first field (the application key from Step 2).
3. Enter the **User Key** of the first recipient in the second field (from Step 1).
4. Optionally enter a second and third User Key if you want to alert multiple people. Each recipient needs their own Pushover account and User Key — but they all share the same App Token you created in Step 2.
5. Tap **Test Send** — all configured recipients should receive a push notification immediately.

> Notifications are delivered even in silent/do-not-disturb mode when sent at high priority (which KSafe uses for emergencies).

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

**Why this works:** after hitting a pothole, bump, or terrain-change edge, a cyclist continues pedalling — the GPS keeps showing movement and the gyroscope never stays below 1 rad/s long enough to confirm a crash. On a slow climb the gyroscope can be very calm, but the GPS speed gate ensures this cannot be mistaken for a crash. After a real crash, the device lies on the ground with near-zero gyroscope and near-zero GPS speed for several seconds. The confirm speed is preset-aware: **3 km/h for Low** (MTB/gravel, where crashes at very low speed are common and post-crash sliding is slower) and **5 km/h for Medium/High** (road riding, where post-crash sliding at 4–5 km/h is plausible and should still trigger confirmation).

### Choosing the right sensitivity level

> **Naming convention**: "High sensitivity" means a *lower* impact threshold — the system reacts to lighter impacts. This follows the standard sensor convention (higher sensitivity = detects smaller signals). It does NOT mean "better" or "safer" in all contexts.

| Level | Impact threshold | Peak threshold | Min. speed | Confirm speed | Best for                                          |
|-------|-----------------|----------------|------------|---------------|---------------------------------------------------|
| ⛰ **Low** | 55 m/s² (~5.5g) | 60 m/s² | 3 km/h | 3 km/h | MTB, enduro,  technical terrain                   |
| 🚴 **Medium** | 45 m/s² (~4.5g) | 50 m/s² | 10 km/h | 5 km/h | Road, MTB mixed, Gravel **(recommended default)** |
| 🏁 **High** | 35 m/s² (~3.5g) | 40 m/s² | 15 km/h | 5 km/h | Smooth road only (velodrome, closed circuit)      |
| 🔧 **Custom** | 20–70 m/s² slider | thr + 5 m/s² | You choose | You choose | Any specific use case                             |

**Impact window** (time allowed between impact and stillness confirmation):
- Low: 25 s — MTB bike may slide or tumble down a slope for a while
- Medium: 20 s — mixed terrain
- High: 15 s — road crashes settle quickly

#### ⛰ Low — for MTB, gravel, enduro

Requires a **very hard impact (~5.5g)** to trigger. MTB jump landings and drops typically generate 3–5g, staying safely below this threshold. Only a serious crash — hitting the ground hard at speed — reaches 5.5g+.

- Min. speed **3 km/h**: technical climbs (*trialeras*) happen at walking pace. Crashes on steep technical sections can occur below 4 km/h.
- Longer impact window (25 s): the bike may keep rolling down a slope after the crash before coming to rest.
- If you still experience false positives on very rough terrain (e.g. large drops, aggressive landings with full stop), use **Custom** with a threshold of 60–65 m/s².

#### 🚴 Medium — recommended default for most riders

**Balanced threshold (~4.5g)**. A sensible starting point for road cyclists, gravel riders, and anyone combining road and trail. Normal road vibration, cobblestones, and moderate bumps stay well below 4.5g. Large potholes or expansion joints at high speed can reach this range — but with the continuous silence requirement, continued riding prevents any false alarm.

- Min. speed **10 km/h**: filters out false positives from handling the bike at slow speeds (putting it on the car, slow track stands).
- If you ride mostly MTB: lower the min. speed to 3 km/h or switch to **Low**.
- If you ride exclusively on smooth tarmac and experience any residual false positives: raise min. speed to 15 km/h or switch to **High**.

#### 🏁 High — smooth road only

**More sensitive threshold (~3.5g)** — designed for perfectly smooth surfaces (velodromes, closed circuits, pristine tarmac) where normal riding never generates impacts above 3.5g. On such surfaces, any 3.5g+ impact is genuinely suspicious and likely a crash.

> [!WARNING]
> Do **not** use High on MTB trails, gravel, or roads with potholes/expansion joints. Any jump, drop, or large bump will regularly exceed 3.5g and trigger the impact detection. Even with the continuous silence check, a brief stop after a bump could produce false alarms.

- Min. speed **15 km/h**: road crashes at very low speed are extremely rare.
- Useful for: triathlon on closed circuits, velodrome training, road racing on premium tarmac.

#### 🔧 Custom threshold

Set the exact impact threshold with the slider (20–70 m/s²):
- **Lower values** → more sensitive, triggers on lighter impacts.
- **Higher values** → harder impact required, fewer false positives.

Useful when no preset fits exactly — for example an aggressive enduro rider who wants something between Low and Medium, or a gravel rider on smoother surfaces.

### Real-world scenarios

| Scenario | Result | Why |
|----------|--------|-----|
| Hard crash on a descent, bike slides for a few seconds | ✅ Detected | Impact → brief movement → bike settles → 4.5 s continuous stillness confirmed |
| Hard crash, bike stops immediately | ✅ Detected | Impact → quick stillness → confirmed |
| Crash on descent, body slides at 4–5 km/h before stopping | ✅ Detected | Confirm speed is 5 km/h (Medium/High) — covers post-crash sliding |
| Crash on a technical MTB climb at 3 km/h (Low) | ✅ Detected | Low threshold + 3 km/h min. speed + 3 km/h confirm speed — designed for this |
| Large pothole at 40 km/h, continue riding | ✅ No false alarm | Impact threshold possibly exceeded, but GPS speed stays high + gyro keeps resetting the silence timer |
| Expansion joint or speed bump, continue riding | ✅ No false alarm | Same as above — continuous movement prevents silence confirmation |
| Transition dirt → asphalt (jolt), continue riding | ✅ No false alarm | Single-sample spike is smoothed by the 60 ms sliding window; never reaches sustained impact level |
| Slow climb, handlebar bump while still pedalling | ✅ No false alarm | GPS speed gate: still moving → cannot enter or confirm silence check |
| MTB jump landing, continue riding immediately | ✅ No false alarm | Impact → movement never stops → window expires → reset |
| MTB jump landing, stop perfectly still for 5+ seconds | ⚠️ Possible false alarm | Impact + genuine stillness → algorithm may confirm; **the 30 s countdown is your safety net** |
| Cobblestones or very rough road | ✅ No false alarm | Sustained vibration resets silence timer continuously |
| Dropping bike carelessly while stopped | ✅ No false alarm | Minimum speed check: below threshold, impact ignored |
| Pausing ride at a café, check-in timer was running | ✅ Timer paused | Check-in timer stops when ride is paused; restarts from zero when ride resumes |

**Key principle**: the algorithm requires both a large sudden impact AND prolonged, uninterrupted stillness. Either one alone is not enough. The countdown (default 30 s) is the final line of defence — if a detection happens while you are fine, just tap CANCEL.

### Minimum speed reference

| Level | Default min. speed | Why |
|-------|--------------------|-----|
| Low | 3 km/h | MTB crashes happen at walking pace on technical sections |
| Medium | 10 km/h | Road + mixed use — filters slow-speed handling |
| High | 15 km/h | Pure road — crashes at low speed are extremely rare |
| Custom | Your choice | Adjust to your riding style |

You can always override the min. speed manually after selecting a level. Setting it to 0 disables the check entirely (useful for testing).

## Testing

KSafe provides test buttons, all of which work **without an active ride**:

| Button | Where | What it does |
|--------|-------|--------------|
| **Test Send** | Provider tab | Sends a test message via the active provider. Shows a specific error (invalid key, missing credentials, no connection…) if something is wrong. |
| **Simulate Crash** | Settings tab | Sends your emergency message immediately — no countdown, no waiting. Use this to verify the full message (location, livetrack link) reaches your contact correctly. |
| **Test ride start notification** | Settings tab | Sends the ride-start message. Only works if the feature is enabled. |
| **Test ride end notification** | Settings tab | Sends the ride-end message. Only works if the feature is enabled. |
| **Send Message 1 / 2 / 3** | Settings tab | Sends each custom message immediately. Only works if that message slot is enabled. |

> **Simulate Crash** sends a real alert to your configured contact. Let them know you are testing, or use **Test Send** instead if you only want to verify connectivity.

## Custom Messages

KSafe provides **three independent custom message buttons** — you can add one, two or all three to your ride screens. Each sends a different text and shows a different label on the field button. No countdown, no emergency — just a quick status update.

**Use cases:**
- *"OK👍"* → sends *"I'm OK! 👍"* — reassure your contacts after a long silent stretch
- *"HOME"* → sends *"Heading home, ETA 45min"*
- *"CREW"* → sends a message to your support crew
- *"START"* → manual ride start notification without Karoo Live
- Any short status you want to send on demand

### Configuration

1. Open KSafe → **Settings tab** → **Custom Messages** section.
2. For each message slot (1, 2, 3):
   - Toggle **Enable message N**.
   - Enter a **button label** (max 5 characters) — this appears on the Karoo field button. Examples: `OK👍`, `HOME`, `SAFE`, `CREW`. Defaults: `MSG`, `MSG2`, `MSG3`.
   - Enter the **message text** that will be sent when the field is tapped (any length).
   - Tap **Send Message N** to test it immediately from the app.
3. Add **KSafe Message 1**, **KSafe Message 2**, and/or **KSafe Message 3** as data fields in your Karoo ride profile.

> [!TIP]
> You can put all three fields on the same screen — they are fully independent and each sends its own message when tapped.

### Hardware button

Assign **KSafe: Send Custom Message** to a controller button in **Karoo → Settings → Controller**. Once configured, a single press sends **Message 1** instantly — no need to unlock the screen or navigate to the app.

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

   > **Telegram note**: the Chat ID is required — the bot needs to know which chat/group/channel to deliver to (a bot can be in many chats at once). See the Telegram setup section above to get yours.

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
- KSafe has no warranties. If you do not agree with this, please do not use it.

### Calibration logging (optional, disabled by default)

If you enable the **"Send anonymous calibration data"** toggle in Settings, KSafe will record and transmit sensor data (accelerometer values, GPS speed, gyroscope readings, detection algorithm states) to the developer via a private Telegram bot. This data is used exclusively to improve and calibrate the crash detection algorithm.

**No personal information is ever included**: no GPS coordinates, no location data, no emergency messages, no contact information, no device identifiers. The data contains only raw sensor readings and algorithm states — it is not possible to identify you, your location, or your ride from it.

This feature is **disabled by default**. Enabling it is entirely voluntary and helps make KSafe more accurate for all riders.

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

