# KSafe - Safety Extension for Karoo

> [!WARNING]
> This app is currently in early stage and its main features might not work at all. If you want to test it and encounter issues, please report them in the GitHub issues, ideally with adb logs attached.
> This extension can send emergency alerts to your contacts. Please test it carefully before relying on it in real situations.

KSafe is a free, open-source safety extension for Karoo GPS devices. It monitors your ride and automatically alerts your emergency contacts if something goes wrong — crash detected, no check-in, or speed suddenly drops — and allows you to manually trigger an SOS from your ride screen.

Beyond emergency alerts, KSafe can also notify your contacts when you **start a ride**, including a real-time Karoo Live tracking link. This is a better alternative to the default email notification that Karoo already offers: KSafe sends it via WhatsApp or push notification, directly to your contacts' phones, so they can follow your ride from the very first moment.

Compatible with Karoo 3 running Karoo OS version 1.527 and later.

> [!IMPORTANT]
> KSafe uses your **phone's internet connection** (via the Hammerhead Companion app) to send messages. Without an active connection between your Karoo and your phone, no alerts or notifications will be sent. Make sure your Karoo is paired and connected to the companion app before riding.

<a href="https://www.buymeacoffee.com/enderthor" target="_blank"><img src="https://cdn.buymeacoffee.com/buttons/default-orange.png" alt="Buy Me A Coffee" height="41" width="174"></a>

## Features

- **Crash detection**: Uses the Karoo's accelerometer and gyroscope to detect sudden impacts automatically.
- **Manual SOS**: Tap the SOS data field to trigger an emergency alert manually.
- **Check-in timer**: Set a periodic check-in interval. If you don't tap "OK" before the timer expires, an alert is sent automatically.
- **Speed drop detection**: Detects when your speed drops suddenly and remains low for a configurable time window.
- **Emergency countdown with cancel**: All triggers start a configurable countdown (default 30s) so you can cancel false alarms before alerts are sent.
- **Location included**: Your GPS coordinates are automatically included in the alert message as a Google Maps link.
- **Multiple messaging providers**: WhatsApp via CallMeBot (free), push notification via Pushover, or free push via SimplePush.
- **Ride start notification**: Optionally sends a message to your contacts when you start a ride, including a Karoo Live real-time tracking link — a better alternative to Karoo's built-in email notification.
- **Two data fields**: SOS field and Safety Timer field — add either or both to your ride profile.

## Requirements

- Karoo 3 with Karoo OS version 1.527 or later.
- Phone paired and connected via the **Hammerhead Companion app** (required for internet access to send alerts).
- At least one configured messaging provider.

## Installation

1. Open the APK download link on your mobile: `https://github.com/lockevod/Karoo-KSafe/releases/latest/download/ksafe.apk`
2. Share the file with the Hammerhead Companion app.
3. Install through the Hammerhead Companion app.

**It is mandatory to restart the Karoo after installation (shut down and start again).**

## Cancel Emergency Button (recommended)

KSafe registers a **"KSafe: Cancel Emergency"** action that you can assign to any hardware button on your Karoo (or an ANT+ remote). This is the most reliable way to cancel a countdown because it works regardless of which data fields you have visible on screen.

To configure it:
1. Go to Karoo **Settings → Controller** (or your ANT+ remote settings).
2. Find the button you want to assign the action to.
3. Select **KSafe: Cancel Emergency** from the action list.

Once configured, pressing that button during an active emergency countdown will cancel it immediately.

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
- **Speed drop detection**: Enable/disable detection of sudden prolonged speed drops. Configure the time window (minutes) with no speed before triggering.
- **Check-in timer**: Enable/disable periodic check-ins. Configure the interval in minutes (default: 120 min). A warning beep and alert appear 10 minutes before the timer expires.

### Provider Tab

Select the messaging provider and enter your credentials. All configuration (API keys, tokens, and phone number for CallMeBot) is done here. KSafe supports:

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

#### Pushover (recommended for reliability)

Pushover delivers push notifications instantly to any phone or tablet. It works independently of WhatsApp — the emergency contact only needs the free Pushover app installed. Each user sets up their own independent account.

Pushover is **free to try for 30 days**. After that, a **one-time payment of ~$5** is required to continue using it without restrictions (unlimited messages, emergency priority alerts, no monthly limits). This payment goes directly to Pushover — KSafe itself is always free and open-source. If you prefer a free alternative with no payment, use CallMeBot (WhatsApp) or SimplePush (push, 10 msg/month).

**Step 1 — Create a Pushover account**

1. Go to [pushover.net](https://pushover.net/) and create a free account.
2. Install the **Pushover** app on the phone that will receive alerts (your emergency contact's phone or your own).
3. Log in — your **User Key** is shown at the top of the dashboard. Copy it.

**Step 2 — Create a Pushover application**

Each person who uses KSafe needs to create their own Pushover application (it is free and takes 1 minute):

1. Go to [pushover.net/apps/build](https://pushover.net/apps/build) while logged in.
2. Fill in:
   - **Name**: `KSafe` (or anything you like)
   - **Type**: Application
   - **Description**: optional
   - **URL**: optional
3. Click **Create Application**. You will get an **App Token** (a string like `azGDORePK8gMaC0QOYAMyEEuzJnyUi`). Copy it.

**Step 3 — Configure KSafe**

1. In the **Provider** tab, select **Pushover**.
2. Enter your **App Token** in the first field.
3. Enter your **User Key** in the second field.
4. Tap **Test Send** — you should receive a push notification on your phone immediately.

> Notifications are delivered even in silent/do-not-disturb mode when sent at high priority (which KSafe uses for emergencies).

## How It Works

1. KSafe runs in the background while you ride.
2. When a trigger occurs (crash detected, check-in expired, speed drop, or manual SOS tap), a countdown starts with audible beeps and a screen alert.
3. During the countdown, you can cancel by tapping the **SOS field** or the **Safety Timer field**.
4. If the countdown completes without cancellation, KSafe obtains your current GPS location and sends the configured emergency message via the configured provider.
5. After sending, the extension returns to idle state and normal monitoring resumes.

## Crash Detection

Crash detection uses the Karoo's built-in accelerometer and gyroscope directly (no external sensor required). The algorithm is based on the same approach used by Garmin's incident detection: **large impact followed by no movement**.

### Algorithm

1. **Impact**: A sudden acceleration spike above the sensitivity threshold is detected.
2. **Silence check**: After impact, both the accelerometer and gyroscope must settle — the device must stop moving AND stop rotating. This is the key differentiator between a crash and a normal MTB jump or hard landing.
3. **Confirmed**: If the device remains still for ~4.5 seconds, the emergency countdown starts.

A jump or drop in MTB generates a high impact spike but is immediately followed by continued movement (continued riding). A real crash generates a high impact spike followed by prolonged stillness of both sensors. The gyroscope integration specifically prevents false positives from jumps where the landing is hard but the rider continues moving.

### Sensitivity levels

| Level | Threshold | Approx. | Best for |
|-------|-----------|---------|----------|
| Low | 55 m/s² | ~5.5g | MTB, gravel, rough terrain |
| Medium | 45 m/s² | ~4.5g | Road + MTB, balanced (default) |
| High | 35 m/s² | ~3.5g | Road cycling, smoother terrain |

Normal hard braking and bumps produce ~1.5g (14.7 m/s²), well below all thresholds. Thresholds are based on cycling crash detection literature (IEEE accident detection studies and probabilistic crash classification research).

Set **minimum speed** to `0` for testing at rest. For real rides, `10 km/h` or higher is recommended to avoid false alarms at stops.

## Testing

Use the **"Simulate Crash"** button in the Settings tab to test the full emergency flow without a real ride. This triggers the countdown and, if not cancelled, will send real alerts to your contacts — so make sure your contacts know you are testing, or cancel before the countdown ends.

Use the **"Test Send"** button in the Provider tab to verify that your messaging provider is correctly configured without triggering a full emergency.

## Known Issues

- Alerts will not be sent if the Karoo has no internet connection at the time of the emergency.
- Crash detection may produce false positives on very rough surfaces. Increase the minimum speed threshold or use Low sensitivity if this happens.
- Each messaging provider has its own rate limits and free tier restrictions. Check provider documentation.
- The extension only monitors during an active ride (Recording state). It stops when the ride is idle. Crash detection remains active when the ride is paused.

## Privacy

- KSafe does not collect or transmit any personal data beyond what is strictly necessary to send your emergency alerts (location and the message you configure).
- All configuration is stored locally on your Karoo device.
- When you use a third-party provider (CallMeBot, Whapi, Pushover, SimplePush), your phone number and message content can be shared with that provider. Please read and accept their terms and privacy policies before using KSafe.
- KSafe has no relationship or partnership with any of these providers.
- KSafe has no warranties. If you do not agree with this, please do not use it.

## Credits

- Developed by EnderThor.
- Uses the Karoo Extensions Framework by Hammerhead.
- Can Use CallMeBot and Whapi for WhatsApp message delivery, and Pushover for push notifications. These services are their own rules and agreements. KSafe hasn't any relation with them.
- Thanks to Hammerhead for the Karoo device and extensions API.

## Useful Links

- [Karoo Extensions Framework](https://github.com/hammerheadnav/karoo-ext)
- [CallMeBot WhatsApp API](https://www.callmebot.com/blog/free-api-whatsapp-messages/)
- [Whapi Documentation](https://docs.whapi.io/)
- [DC Rainmaker sideloading guide](https://www.dcrainmaker.com/2021/02/how-to-sideload-android-apps-on-your-hammerhead-karoo-1-karoo-2.html)
