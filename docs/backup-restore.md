# Backup and Restore

> KSafe lets you export and restore your entire configuration (API keys, tokens, messages, all settings) from the **Settings tab**, at the bottom of the screen. This page covers the file format and the recommended ADB workflow.
>
> The README has a one-line pointer; the full procedure lives here because it is a power-user workflow consulted once at setup or when migrating between devices, not while riding.

## Exporting your configuration

Tap **Export** in the Settings tab. KSafe writes your configuration to:

```
/sdcard/Android/data/com.enderthor.kSafe/files/ksafe_export.json
```

You can retrieve this file with ADB:

```bash
adb pull /sdcard/Android/data/com.enderthor.kSafe/files/ksafe_export.json
```

## Restoring a configuration

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

## Easiest way to enter API keys and tokens

Typing long tokens (Pushover App Token, Telegram Bot Token, etc.) on the Karoo touchscreen is tedious and error-prone. The fastest workflow is to export the configuration, edit the JSON on your computer, and import it back.

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

   > **Telegram note**: the Chat ID is required — the bot needs to know which chat/group/channel to deliver to (a bot can be in many chats at once). See [messaging-providers.md](messaging-providers.md) for how to get yours.

4. Save the file and push it back as `ksafe_import.json`:
   ```bash
   adb push ksafe_export.json /sdcard/Android/data/com.enderthor.kSafe/files/ksafe_import.json
   ```
5. Tap **Import** in KSafe — all keys are applied instantly.

> [!TIP]
> Use the same workflow to back up your configuration before updating the app, or to copy your setup to another Karoo device.

> [!NOTE]
> The import is tolerant: you can fill in only the providers you use and leave the rest empty. Unknown or extra fields are silently ignored, so imports from older or newer versions of KSafe always work.
