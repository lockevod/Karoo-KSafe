# Messaging Providers — Full Setup Guide

> Step-by-step setup for each of the four messaging providers KSafe supports. The README has a one-paragraph overview and the comparison table; this page is the deep dive with screenshots / commands / where-to-click instructions.

KSafe supports **four providers**. Pick one based on cost and reliability:

| Provider | Cost | Best for |
|----------|------|----------|
| **Telegram** | Free, unlimited | Best free option — no limits, no account needed beyond a bot |
| **ntfy** | Free, unlimited | Quickest setup — no account, just pick a topic name |
| **CallMeBot (WhatsApp)** | Free | Recipients already use WhatsApp |
| **Pushover** | Free trial, ~$5 one-time | Most reliable push notifications |

You can configure credentials for all four — they are saved independently and switching between them does not erase anything. Only the **selected (active) provider** will be used when an alert is triggered.

---

## ntfy (free and unlimited — easiest setup)

ntfy.sh is the simplest option: no account, no registration, no limits. Just pick a topic name and subscribe to it in the ntfy app. Notifications are delivered instantly, for free, with no monthly caps.

> [!NOTE]
> The topic name acts like a "channel" — anyone who knows it can subscribe and receive your alerts. Use a long, random-looking name (e.g. `ksafe-alerts-r4nd0m42`) to keep it private. You can also self-host an ntfy server for full privacy.

### Step 1 — Install ntfy and subscribe to your topic

1. Install the **ntfy** app on the phone that will receive alerts:
   - [Android](https://play.google.com/store/apps/details?id=io.heckel.ntfy)
   - [iOS](https://apps.apple.com/app/ntfy/id1625396347)
2. Open the app and tap **+** to add a new subscription.
3. Choose a **topic name** (e.g. `ksafe-alerts-myname`). You can use any name — just make it unique enough that others are unlikely to guess it.
4. Tap **Subscribe**.

### Step 2 — Configure KSafe

1. In the **Provider** tab, select **ntfy**.
2. Enter the same **topic name** you subscribed to in Step 1.
3. Tap **Test Send** — you should receive a push notification in the ntfy app immediately.

> You can add the same topic on multiple phones to alert several people at once. Each person just subscribes to the same topic name in their ntfy app.

---

## WhatsApp via CallMeBot (free and easy)

CallMeBot lets you send WhatsApp messages for free using a simple API. **Important: the setup must be done from the phone that will RECEIVE the alerts** (your emergency contact's phone), not yours.

### Step 1 — Activate CallMeBot from the contact's phone

1. Open WhatsApp on the **destination phone** (your emergency contact's).
2. Add the number `+34 644 31 95 65` to their contacts (save it as "CallMeBot" or any name).
3. Send this exact message to that number via WhatsApp:
   ```
   I allow callmebot to send me messages
   ```
4. Within a few minutes CallMeBot will reply with your **API key** (a numeric code). Save it.

> If the contact doesn't receive a reply, try again after a few minutes. The number may be busy. You can also try via the [CallMeBot website](https://www.callmebot.com/blog/free-api-whatsapp-messages/) for alternative activation methods.

### Step 2 — Configure KSafe

1. In the **Provider** tab, select **CallMeBot**.
2. Enter the **Recipient 1 phone number** with the international prefix but **without the `+` sign** (e.g. `34675123123`).
3. Enter the **Recipient 1 API Key** received in Step 1.
4. Tap **Test Send** — your contact should receive a WhatsApp message within seconds.

### Optional — add a second and third recipient

CallMeBot supports a single recipient per request, so to alert several people KSafe sends one WhatsApp message per recipient. Each extra recipient needs **their own phone number and their own API key**: repeat Step 1 from each contact's phone (CallMeBot returns a different key for each WhatsApp number) and fill in the **Recipient 2** and **Recipient 3** fields. Leave them blank if you only want to alert one person.

A failed delivery to one recipient does not block the others, and the **Test Send** result is reported per recipient so you can spot a misconfigured slot.

---

## Telegram (free and unlimited)

Telegram lets you send messages for free through a bot you create yourself. There are no rate limits or monthly caps — it is a great option if you want a reliable free provider with no restrictions.

### Step 1 — Create a Telegram bot and get your Bot Token

1. Open Telegram and search for **@BotFather**.
2. Start a chat and send `/newbot`.
3. Follow the instructions: choose a name and a username for your bot (username must end in `bot`, e.g. `MySafetyBot`).
4. BotFather will give you a **Bot Token** (e.g. `7123456789:AAFxxxxxxxxxxxxxxxxxxxxxx`). Copy it.

### Step 2 — Get your Chat ID

The Chat ID tells the bot where to deliver the message. You can send alerts to a **personal chat**, a **group**, or a **channel**.

For a **personal chat** (easiest):

1. Search for your new bot in Telegram and tap **Start** (`/start`).
2. **Send any message to the bot** (e.g. `hello`) — this is required so the bot has an update to return.
3. Go to `https://api.telegram.org/bot<BOT_TOKEN>/getUpdates` in a browser (replace `<BOT_TOKEN>` with your bot token).
4. Look for `"chat":{"id":XXXXXXX}` in the response — that number is your **Chat ID**.

For a **group** or **channel**:

1. Add the bot to the group/channel as an administrator.
2. Send a message in the group, then fetch `getUpdates` as above — the Chat ID will be a negative number (e.g. `-1001234567890`).

### Step 3 — Configure KSafe

1. In the **Provider** tab, select **Telegram**.
2. Enter your **Bot Token** in the first field.
3. Enter your **Chat ID** (recipient 1) in the second field.
4. Optionally enter a second and third Chat ID to alert additional chats. In most cases one Chat ID is enough — if you want to alert multiple people at once, simply add the bot to a **Telegram group** and use the group's Chat ID.
5. Tap **Test Send** — all configured chats should receive a message immediately.

> If you don't receive the test message, make sure you have started a conversation with the bot first (send `/start` to it in Telegram).

---

## Pushover (recommended for reliability)

Pushover delivers push notifications instantly to any phone or tablet. It works independently of WhatsApp — the emergency contact only needs the free Pushover app installed. Each user sets up their own independent account.

Pushover is **free to try for 30 days**. After that, a **one-time payment of ~$5** is required to continue using it without restrictions (unlimited messages, emergency priority alerts, no monthly limits). This payment goes directly to Pushover — KSafe itself is always free and open-source. If you prefer a free alternative with no payment, use CallMeBot (WhatsApp) or ntfy (free, unlimited push notifications).

Pushover requires **two separate keys** — this is a common point of confusion:

| Key | What it is | Where to find it |
|-----|-----------|-----------------|
| **App Token** | Identifies the *application* sending the alert (KSafe) | Created once at pushover.net/apps/build |
| **User Key** | Identifies the *device/account* that will receive the alert | Found in the Pushover app on each recipient's phone → Settings |

Both are mandatory. Without the App Token, Pushover doesn't know which app is sending. Without the User Key, it doesn't know where to deliver the notification.

KSafe supports **up to 3 User Keys** — one per field in the Provider tab. Each key belongs to a different person or device. The App Token is shared: you only create it once and use it for all recipients.

### Step 1 — Create a Pushover account and get your User Key

1. Go to [pushover.net](https://pushover.net/) and create a free account.
2. Install the **Pushover** app on the phone that will receive alerts (your emergency contact's phone or your own) and log in.
3. Open the **Pushover app** on that phone → go to **Settings** — the **User Key** is shown there (e.g. `uQiRzpo4DXghDmr9QzzfQu`). Copy it. This key is tied to that specific device/account and is needed so KSafe knows where to deliver the notification.

### Step 2 — Create a Pushover application and get your App Token

Each person who uses KSafe needs to create their own Pushover application (it is free and takes 1 minute):

1. Go to [pushover.net/apps/build](https://pushover.net/apps/build) while logged in.
2. Fill in:
   - **Name**: `KSafe` (or anything you like)
   - **Type**: Application
   - **Description**: optional
   - **URL**: optional
3. Click **Create Application**. You will get an **App Token** (e.g. `azGDORePK8gMaC0QOYAMyEEuzJnyUi`). Copy it. This key identifies KSafe as the sender.

### Step 3 — Configure KSafe

1. In the **Provider** tab, select **Pushover**.
2. Enter your **App Token** in the first field (the application key from Step 2).
3. Enter the **User Key** of the first recipient in the second field (from Step 1).
4. Optionally enter a second and third User Key if you want to alert multiple people. Each recipient needs their own Pushover account and User Key — but they all share the same App Token you created in Step 2.
5. Tap **Test Send** — all configured recipients should receive a push notification immediately.

> Notifications are delivered even in silent/do-not-disturb mode when sent at high priority (which KSafe uses for emergencies).
