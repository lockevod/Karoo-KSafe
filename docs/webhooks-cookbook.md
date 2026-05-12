# Webhook Cookbook

> Ready-to-paste examples for the most common Webhook Action targets. The README has the overview and configuration steps; this page is the recipe collection — copy a row into the Actions tab and adapt.

KSafe sends webhook requests **via the Karoo network bridge** — the same mechanism used for emergency alerts. They work over Bluetooth tether even when the Karoo is not on Wi-Fi.

Each example below maps directly to the four fields you configure per webhook slot in the Actions tab: **URL**, **Method**, **Header**, **Body**.

---

## 🏠 Home Assistant — toggle a cover (garage door)

Home Assistant exposes a REST API for every entity. To toggle a cover (garage door, roller shutter, etc.):

| Field | Value |
|-------|-------|
| **URL** | `https://your-ha-instance.com/api/services/cover/toggle` |
| **Method** | POST |
| **Header** | `Authorization: Bearer YOUR_LONG_LIVED_TOKEN` |
| **Body** | `{"entity_id": "cover.garage_door"}` |

Replace `your-ha-instance.com` with your Home Assistant URL (local or via Nabu Casa), and `cover.garage_door` with your entity ID. Get a Long-Lived Access Token from **Home Assistant → Profile → Long-Lived Access Tokens**.

> You can use any Home Assistant service: `light.toggle`, `switch.turn_on`, `script.my_script`, `input_boolean.toggle`, etc. The entity ID is found in HA under **Settings → Devices & Services → Entities**.

---

## 🔌 Shelly — toggle a relay (garage door motor)

Shelly devices expose a simple local HTTP API — no cloud required.

### Shelly 1 / 1PM / Plus 1 (local API)

| Field | Value |
|-------|-------|
| **URL** | `http://192.168.1.50/relay/0?turn=toggle` |
| **Method** | GET |
| **Header** | *(leave empty if no password set)* |
| **Body** | *(not needed for GET)* |

Replace `192.168.1.50` with your Shelly's local IP address. The Shelly must be on the same Wi-Fi network as your phone (the request goes through the Karoo → phone bridge).

### Shelly via cloud (if not on the same network)

| Field | Value |
|-------|-------|
| **URL** | `https://shelly-103-eu.shelly.cloud/device/relay/control` |
| **Method** | POST |
| **Header** | `Content-Type: application/x-www-form-urlencoded` |
| **Body** | `auth_key=YOUR_AUTH_KEY&id=YOUR_DEVICE_ID&channel=0&turn=on&timer=1` |

> ⚠️ The Shelly Cloud API expects **form-urlencoded**, not JSON. Make sure the `Content-Type` header is set exactly as above and the body uses the `key=value&key=value` format.

> **`timer=1` is key for garage doors** — it activates the relay for 1 second then releases it, which is exactly what a garage door push-button needs. Without it the relay stays on.

**How to get your credentials from the Shelly app:**

1. Open the **Shelly app** → tap your device → **Settings → User Settings → Authorization Cloud Key**.
2. Note down:
   - **`auth_key`** — your cloud authorisation key.
   - **Cloud server** — shown as something like `shelly-103-eu.shelly.cloud` (use this as the hostname in the URL).
   - **`id`** — the device ID shown in the device info screen.

> [!TIP]
> Test the call with `curl` or Postman before setting it up in KSafe:
> ```bash
> curl -X POST https://shelly-103-eu.shelly.cloud/device/relay/control \
>   -H "Content-Type: application/x-www-form-urlencoded" \
>   -d "auth_key=YOUR_AUTH_KEY&id=YOUR_DEVICE_ID&channel=0&turn=on&timer=1"
> ```
> You should hear the relay click. If it works there, it will work from KSafe.

---

## 📬 ntfy — send a push notification to yourself

ntfy lets you send notifications to your own phone without any external service. Useful for confirming the action was triggered.

| Field | Value |
|-------|-------|
| **URL** | `https://ntfy.sh/your-topic-name` |
| **Method** | POST |
| **Header** | `Title: KSafe Action` |
| **Body** | `Garage door toggled from the bike!` |

Replace `your-topic-name` with the topic you subscribed to in the ntfy app. The `Header` field can only hold one header — for ntfy the `Title` header sets the notification title. The body is the notification text.

---

## ⚡ IFTTT Webhook (Applets)

IFTTT supports incoming webhooks to trigger any applet:

| Field | Value |
|-------|-------|
| **URL** | `https://maker.ifttt.com/trigger/YOUR_EVENT/with/key/YOUR_IFTTT_KEY` |
| **Method** | POST |
| **Header** | `Content-Type: application/json` |
| **Body** | `{"value1": "triggered from KSafe"}` |

Replace `YOUR_EVENT` with your IFTTT event name and `YOUR_IFTTT_KEY` with your Webhooks key (found at [ifttt.com/maker_webhooks](https://ifttt.com/maker_webhooks)).

---

## 🔁 n8n / Make (generic webhook)

Both n8n and Make expose webhook URLs that accept any HTTP request:

| Field | Value |
|-------|-------|
| **URL** | Your webhook URL from n8n / Make |
| **Method** | POST |
| **Header** | *(optional, e.g. `Authorization: Bearer token` if protected)* |
| **Body** | `{"source": "ksafe", "action": "button1"}` |

Both platforms let you trigger any automation flow when the webhook fires.

---

## Hardware button assignment

After configuring a webhook slot:

1. On the Karoo, go to **Sensors → [your AXS groupset] → Configure Controls**.
2. Press the physical SRAM AXS shifter button you want to assign.
3. Select **KSafe: Webhook Action 1** or **KSafe: Webhook Action 2** from the actions list. **Short Press** and **Long Press** can be configured independently on the same button.
4. Press the button during a ride — the HTTP request fires instantly and you get an in-ride notification.

> [!NOTE]
> BonusActions are **exclusive to SRAM AXS controllers** (RED/Force AXS shifters with the additional button). Other ANT+ remotes or Garmin controllers do not expose this feature. If you do not have a SRAM AXS groupset, these actions cannot be assigned to any button. See the [official Hammerhead guide on SRAM AXS controllers](https://support.hammerhead.io/hc/en-us/articles/25672636525979-Karoo-OS-Controlling-Karoo-with-SRAM-AXS-Controllers) for full details.

---

## Geo-fence and ride-alert per slot

In addition to the URL / method / headers / body, each webhook slot has two optional toggles in the Actions tab:

- **Only trigger when near location** — prevents accidental triggers. Tap **Use current GPS location as target** while you are at the spot (or enter coordinates manually) and set a radius in metres. The webhook is blocked if the device is further away.
- **Show ride alert when triggered** — a `SystemNotification` with your custom text appears every time the action fires. Useful to notice accidental button presses immediately.

Both work for any of the recipes above.
