# KSafe - Safety Extension for Karoo

> [!WARNING]
> This app is currently in early stage and its main features might not work at all. If you want to test it and encounter issues, please report them in the GitHub issues, ideally with adb logs attached.
> This extension can send emergency alerts to your contacts. Please test it carefully before relying on it in real situations.

KSafe is a free, open-source safety extension for Karoo GPS devices. It monitors your ride and automatically alerts your emergency contacts if something goes wrong — crash detected, no check-in, or speed suddenly drops — and allows you to manually trigger an SOS from your ride screen.

Beyond emergency alerts, KSafe can also notify your contacts when you **start a ride**, including a real-time Karoo Live tracking link. This is a better alternative to the default email notification that Karoo already offers: KSafe sends it via WhatsApp, Telegram, or push notification, directly to your contacts' phones, so they can follow your ride from the very first moment.

Compatible with Karoo 3 running Karoo OS version 1.527 and later.

> [!IMPORTANT]
> KSafe uses your **phone's internet connection** (via the Hammerhead Companion app) to send messages. Without an active connection between your Karoo and your phone, no alerts or notifications will be sent. Make sure your Karoo is paired and connected to the companion app before riding.

<a href="https://www.buymeacoffee.com/enderthor" target="_blank"><img src="https://cdn.buymeacoffee.com/buttons/default-orange.png" alt="Buy Me A Coffee" height="41" width="174"></a>

## Features

- **Crash detection**: Uses the Karoo's accelerometer and gyroscope to detect sudden impacts automatically.
- **Manual SOS**: Tap the SOS data field to trigger an emergency alert manually.
- **Check-in timer**: Set a periodic check-in interval. If you don't tap "OK" before the timer expires, an alert is sent automatically.
- **Speed drop detection**: Detects when your speed drops suddenly and remains low for a configurable time window.
- **Emergency countdown with cancel**: All triggers start a configurable countdown (default 30s) so you can cancel false alarms before alerts are sent. A **red overlay with a Cancel button** appears on top of the ride screen — visible from any screen, no matter which data field is active.
- **Location included**: Your GPS coordinates are automatically included in the alert message as a Google Maps link.
- **Multiple messaging providers**: WhatsApp via CallMeBot (free), push notification via Pushover, free push via SimplePush, or Telegram bot messages (free, unlimited).
- **Ride start notification**: Optionally sends a message to your contacts when you start a ride, including a Karoo Live real-time tracking link — a better alternative to Karoo's built-in email notification.
- **Two data fields**: SOS field and Safety Timer field — add either or both to your ride profile.

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

KSafe registers a **"KSafe: Cancel Emergency"** action that you can assign to a compatible hardware controller button. This works from **any screen**, with no need to look at the display.

> [!NOTE]
> `BonusAction` availability depends on your controller hardware/firmware. It is commonly used with compatible remotes (e.g. SRAM AXS controls). If your controller does not expose extension bonus actions, this option will not appear in the button assignment screen.

To configure it:
1. Go to Karoo **Settings → Controller** (or your ANT+ remote settings).
2. Find the button you want to assign the action to.
3. Select **KSafe: Cancel Emergency** from the action list.

Once configured, pressing that button during an active countdown will cancel it immediately.

## Data Fields

KSafe provides two custom data fields you can add to your ride profiles:

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

Add one or both fields to your Karoo ride profile from the profile editor.

> [!TIP]
> Tapping a data field is one of three ways to cancel an emergency countdown. See the [Cancelling an Emergency](#cancelling-an-emergency) section for all three methods and their trade-offs.

## Configuration

Open the KSafe app on your Karoo to configure it.

### Settings Tab

- **Active**: Enable or disable the extension entirely.
- **Emergency message**: The message sent to your contacts. Available placeholders:
  - `{location}` — GPS coordinates as a Google Maps link.
  - `{reason}` — reason for the alert (crash / check-in expired / manual SOS / speed drop).
  - `{livetrack}` — Karoo Live real-time tracking link (only if a Karoo Live key is configured).
- **Karoo Live tracking on ride start**: Toggle to enable/disable sending a notification to your contacts when the ride starts, including a real-time tracking link. **Requires a Karoo Live key** — if no key is configured, nothing is sent even if the toggle is on.
- **Karoo Live key**: Enter only the key part of your Karoo Live URL. For example, from `https://dashboard.hammerhead.io/live/3738Ag` enter `3738Ag`.
- **Ride start message**: The message sent when the ride starts. Use `{livetrack}` to insert the tracking link.
- The `{livetrack}` placeholder also works in your emergency message — if a key is set, emergency alerts will include the tracking link too.
- **Countdown seconds**: How long the cancellation countdown lasts before alerts are sent (default: 30s).
- **Crash detection**: Enable/disable automatic crash detection via sensors. Configure sensitivity (Low / Medium / High) and minimum speed threshold (0 = detect at any speed; recommended: 10 km/h for real rides).
- **Monitor crash when not riding**: Keeps crash detection active even when no ride is recording, using the minimum speed you have configured. Useful if you want protection on a quick spin or warm-up without starting a recording.
- **Monitor crash when not riding — any speed**: Same as above but ignores the minimum speed threshold, detecting crashes even while completely stationary. ⚠ This setting can produce **more false positives** (e.g., picking up or dropping the device may trigger an alert). Use with caution.
- **Speed drop detection**: Enable/disable detection of sudden prolonged speed drops. Configure the time window (minutes) with no speed before triggering.
- **Check-in timer**: Enable/disable periodic check-ins. Configure the interval in minutes (default: 120 min). A warning beep and alert appear 10 minutes before the timer expires.

### Provider Tab

Select which provider will be used to send alerts. You can configure credentials for all four providers — they are saved independently and switching between them does not erase anything. Only the **selected (active) provider** will be used when an alert is triggered.

KSafe supports **four providers**:

| Provider | Cost | Best for |
|----------|------|----------|
| **Telegram** | Free, unlimited | Best free option — no limits, no account needed beyond a bot |
| **SimplePush** | Free (10 msg/month) | Quickest setup — install app, paste key |
| **CallMeBot (WhatsApp)** | Free | Recipients already use WhatsApp |
| **Pushover** | Free trial, ~$5 one-time | Most reliable push notifications |

#### SimplePush (free up to 10 messages/month — easiest setup)

SimplePush is the simplest option: no account, no registration, just install the app and paste one key. The free tier allows **10 messages per month**, which may be enough if you only use it for occasional emergency alerts. If you ride frequently or have multiple contacts, consider Pushover or CallMeBot instead.

**Step 1 — Install SimplePush and get your key**

1. Install the **SimplePush** app on the phone that will receive alerts:
   - [Android](https://play.google.com/store/apps/details?id=io.github.norbipeti.simplepush)
   - [iOS](https://apps.apple.com/app/simplepush/id1448718895)
2. Open the app — your **Channel Key** is displayed on the main screen (e.g. `HuxRj4`). Copy it.

**Step 2 — Configure KSafe**

1. In the **Provider** tab, select **SimplePush**.
2. Enter the **Channel Key** from the app.
3. Tap **Test Send** — you should receive a push notification immediately.

> No phone number is needed in the Contacts tab when using SimplePush. The free tier covers **10 messages/month**. Check [simplepush.io](https://simplepush.io) for paid plans if you need more.

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
2. Enter the **recipient's phone number** (with country code, e.g. `+34675123123`) in the phone field.
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
1. Search for your new bot in Telegram and tap **Start**.
2. Go to `https://api.telegram.org/bot<YOUR_TOKEN>/getUpdates` in a browser (replace `<YOUR_TOKEN>` with your actual token).
3. Send any message to the bot, then refresh the page — look for `"chat":{"id":XXXXXXX}`. That number is your **Chat ID**.

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

Pushover is **free to try for 30 days**. After that, a **one-time payment of ~$5** is required to continue using it without restrictions (unlimited messages, emergency priority alerts, no monthly limits). This payment goes directly to Pushover — KSafe itself is always free and open-source. If you prefer a free alternative with no payment, use CallMeBot (WhatsApp) or SimplePush (push, 10 msg/month).

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

Crash detection uses the Karoo's built-in accelerometer and gyroscope directly (no external sensor required). The algorithm is based on the same approach used by Garmin's incident detection: **large impact followed by no movement**.

### Algorithm

1. **Impact**: A sudden acceleration spike above the sensitivity threshold is detected.
2. **Silence check**: After impact, both the accelerometer and gyroscope must settle — the device must stop moving AND stop rotating. This is the key differentiator between a crash and a normal MTB jump or hard landing.
3. **Confirmed**: If the device remains still for ~4.5 seconds, the emergency countdown starts.

A jump or drop in MTB generates a high impact spike but is immediately followed by continued movement (continued riding). A real crash generates a high impact spike followed by prolonged stillness of both sensors. The gyroscope integration specifically prevents false positives from jumps where the landing is hard but the rider continues moving.

### Sensitivity levels

| Level | Threshold | Approx. | Impact window | Min. speed | Best for |
|-------|-----------|---------|---------------|------------|----------|
| Low | 55 m/s² | ~5.5g | **25 s** | **3 km/h** | MTB, gravel, technical terrain |
| Medium | 45 m/s² | ~4.5g | **20 s** | **5 km/h** | Road + MTB, balanced (default) |
| High | 35 m/s² | ~3.5g | **15 s** | **10 km/h** | Road cycling on smooth surfaces |
| Custom | 20–70 m/s² | 2–7g | **20 s** | You decide | Any use case |

Normal hard braking and bumps produce ~1.5g (14.7 m/s²), well below all thresholds. Thresholds are based on cycling crash detection literature (IEEE accident detection studies and probabilistic crash classification research).

**Impact window**: after a detected impact, KSafe waits up to this long for the device to come to rest. A longer window allows the bike to slide or tumble down a slope after the crash before confirming. If brief movement is detected during the stillness check (e.g. the bike rolls a little), KSafe goes back to watching — it does not discard the crash unless movement is sustained for the full window.

### Real-world scenarios

These are the most common situations you will encounter and how the algorithm handles each one:

| Scenario | Result | Why |
|----------|--------|-----|
| Hard crash on a descent, bike slides for a few seconds | ✅ Detected | Impact spike → brief movement tolerated → bike settles → 4.5 s stillness confirmed |
| Hard crash, bike stops immediately | ✅ Detected | Impact spike → quick stillness → confirmed |
| Crash on a technical climb at 3 km/h | ✅ Detected (Low sensitivity) | Low threshold + low min. speed — designed for this |
| MTB jump landing, continue riding immediately | ✅ No false alarm | Impact → movement never stops → window expires → reset |
| MTB jump landing, stop to rest briefly | ✅ No false alarm (probably) | Impact → you are moving while braking → stillness after braking is brief if you shift position; also the threshold at Low (5.5g) is rarely exceeded by clean landings |
| MTB jump landing, stop perfectly still for 5+ seconds | ⚠️ Possible false alarm | Impact + clean stillness → algorithm may confirm crash; **the countdown is your safety net** — tap SOS field or assigned button to cancel |
| Hard landing on a jump, stop to watch others | ⚠️ Possible false alarm | Same as above — **cancel with the countdown if it starts** |
| Riding over rough rocky terrain | ✅ No false alarm | Multiple small bumps below threshold; no single sustained spike + stillness pattern |
| Dropping bike carelessly while stopped | ✅ No false alarm | Min. speed check: you are stationary so impact is ignored |
| Riding on cobblestones or very rough road | ✅ No false alarm (Low/Medium) | Sustained vibration never reaches stillness check |

**Key principle**: the algorithm requires two things together — a large sudden impact AND prolonged stillness afterwards. Either one alone is not enough. The countdown (default 30 s) is the last line of defence against false positives: if a detection happens while you are fine, just tap the SOS field or the cancel button.

#### Minimum speed and MTB

The minimum speed threshold filters out crashes detected while stationary (e.g. picking up the bike, putting it in a car). For MTB it should be set low — **3 km/h or even 0** — because:

- Technical climbs (*trialeras*) are ridden at walking pace or slower.
- Jump approach sections may have low speed before the jump.
- A crash on a steep technical section can happen below 4 km/h.

For road cycling, 10 km/h is a sensible default since road crashes at standstill are extremely rare. Selecting a sensitivity level automatically applies the recommended minimum speed, but you can always adjust it manually.

#### Custom sensitivity

The **Custom** level lets you set the exact impact threshold using a slider (20–70 m/s²). Use this if the preset levels don't match your riding style — for example, an aggressive enduro rider may want something between Low and Medium, or a triathlete on a very smooth course may want to go below High.

- **Lower value** (towards 20 m/s²) = more sensitive, triggers on lighter impacts.
- **Higher value** (towards 70 m/s²) = less sensitive, requires a harder impact.

## Testing

KSafe provides three test buttons, all of which work **without an active ride**:

| Button | Where | What it does |
|--------|-------|--------------|
| **Test Send** | Provider tab | Sends a test message via the active provider. Shows a specific error (invalid key, missing credentials, no connection…) if something is wrong. |
| **Simulate Crash** | Settings tab | Sends your emergency message immediately — no countdown, no waiting. Use this to verify the full message (location, livetrack link) reaches your contact correctly. |
| **Test ride start notification** | Settings tab | Sends the ride-start message with the Karoo Live link. Only works if Karoo Live is enabled and a key is configured. |

> **Simulate Crash** sends a real alert to your configured contact. Let them know you are testing, or use **Test Send** instead if you only want to verify connectivity.

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
3. Open `ksafe_export.json` in any text editor. Find the `senderConfigs` array and fill in your keys. The field names are shared across providers:

   | JSON field | CallMeBot | Pushover | Telegram | SimplePush |
   |------------|-----------|----------|----------|------------|
   | `apiKey` | API key | App Token | **Bot Token** | Channel Key |
   | `userKey` | *(unused)* | User Key 1 | **Chat ID 1** | *(unused)* |
   | `userKey2` | *(unused)* | User Key 2 | **Chat ID 2** | *(unused)* |
   | `userKey3` | *(unused)* | User Key 3 | **Chat ID 3** | *(unused)* |
   | `phoneNumber` | Phone number | *(unused)* | *(unused)* | *(unused)* |

   Example after editing:
   ```json
   [
     { "provider": "TELEGRAM",  "apiKey": "7123456789:AAFxxxxxxxxxxxx", "userKey": "123456789",                    "userKey2": "", "userKey3": "", "phoneNumber": "" },
     { "provider": "PUSHOVER",  "apiKey": "azGDORePK8gMaC0QOYAMyEEuzJnyUi", "userKey": "uQiRzpo4DXghDmr9QzzfQu", "userKey2": "", "userKey3": "", "phoneNumber": "" },
     { "provider": "CALLMEBOT", "apiKey": "1234567",                     "userKey": "",                            "userKey2": "", "userKey3": "", "phoneNumber": "+34612345678" },
     { "provider": "SIMPLEPUSH","apiKey": "HuxRj4",                      "userKey": "",                            "userKey2": "", "userKey3": "", "phoneNumber": "" }
   ]
   ```

   > **Telegram note**: the Chat ID is required — the bot needs to know which chat/group/channel to deliver to (a bot can be in many chats at once). See the Telegram setup section above to get yours.

4. Save the file and push it back as `ksafe_import.json`:
   ```bash
   adb push ksafe_export.json /sdcard/Android/data/com.enderthor.kSafe/files/ksafe_import.json
   ```
5. Tap **Import** in KSafe — all keys are applied instantly.

> [!TIP]
> You can also use this workflow to back up your configuration before updating the app, or to copy your setup to another Karoo device.

## Known Issues

- Alerts will not be sent if the Karoo has no internet connection at the time of the emergency.
- Crash detection may produce false positives on very rough surfaces. Increase the minimum speed threshold or use Low sensitivity if this happens.
- Each messaging provider has its own rate limits and free tier restrictions. Check provider documentation.
- By default, the extension only monitors during an active ride (Recording state). Crash detection remains active when the ride is paused. Use the **"Monitor crash when not riding"** options in Settings to enable monitoring outside of a recorded ride.

## Privacy

- KSafe does not collect or transmit any personal data beyond what is strictly necessary to send your emergency alerts (location and the message you configure).
- All configuration is stored locally on your Karoo device.
- When you use a third-party provider (CallMeBot, Pushover, SimplePush, Telegram), your message content and identifiers (phone number, chat ID, user key…) can be shared with that provider. Please read and accept their terms and privacy policies before using KSafe.
- KSafe has no relationship or partnership with any of these providers.
- KSafe has no warranties. If you do not agree with this, please do not use it.

## Credits

- Developed by EnderThor.
- Uses the Karoo Extensions Framework by Hammerhead.
- Can use CallMeBot for WhatsApp message delivery, Pushover for push notifications, SimplePush for free push notifications, and Telegram Bot API for Telegram messages. These services have their own rules and agreements. KSafe has no relationship with any of them.
- Thanks to Hammerhead for the Karoo device and extensions API.

## Useful Links

- [Karoo Extensions Framework](https://github.com/hammerheadnav/karoo-ext)
- [CallMeBot WhatsApp API](https://www.callmebot.com/blog/free-api-whatsapp-messages/)
- [Telegram BotFather](https://t.me/BotFather)
- [DC Rainmaker sideloading guide](https://www.dcrainmaker.com/2021/02/how-to-sideload-android-apps-on-your-hammerhead-karoo-1-karoo-2.html)


