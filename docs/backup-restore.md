# Backup and Restore

> KSafe lets you export and restore your entire configuration (API keys, tokens, messages, all settings) from the **Settings tab**, at the bottom of the screen. This page covers the file format and the recommended ADB workflow.
>
> The README has a one-line pointer; the full procedure lives here because it is a power-user workflow consulted once at setup or when migrating between devices, not while riding.

## Permission required (first use only)

> KSafe writes to `/sdcard/KSafe/`, a shared folder outside the app sandbox. On the first tap of **Export** or **Import** the app requests "All files access" (`MANAGE_EXTERNAL_STORAGE`) and opens the system settings screen to grant it (on Android 9 and below a standard storage-permission dialog is shown instead). If your Karoo does not expose that screen, grant it via ADB:
> ```bash
> adb shell appops set com.enderthor.kSafe MANAGE_EXTERNAL_STORAGE allow
> ```
> The grant persists across app updates.

> [!WARNING]
> **The backup is stored in clear text in shared storage.** `/sdcard/KSafe/ksafe_export.json` holds your messaging credentials (CallMeBot API keys, Pushover app token + user keys, ntfy topic, Telegram bot token) **and your emergency-contact phone numbers / chat IDs**, all unencrypted. Any app on the Karoo with storage access can read it, and the folder deliberately persists after uninstall. This is an intentional trade-off: the backup must survive a clean reinstall **and** stay editable on your computer (the whole point of the export-edit-import workflow below), both of which encryption would break. The Karoo is a closed cycling computer where you typically install very few apps, so real-world exposure is low — but treat `ksafe_export.json` like a password file: don't share it, and delete it from any shared computer after you finish migrating.

## Exporting your configuration

Tap **Export** in the Settings tab. KSafe writes your configuration to:

```
/sdcard/KSafe/ksafe_export.json
```

> The `/sdcard/KSafe/` folder is not wiped when KSafe is uninstalled or updated, so your backup survives a clean reinstall.

You can retrieve this file with ADB:

```bash
adb pull /sdcard/KSafe/ksafe_export.json
```

## Restoring a configuration

To import a configuration, place the file at this exact path **with this exact name**:

```
/sdcard/KSafe/ksafe_import.json
```

You can push it with ADB:

```bash
adb push ksafe_export.json /sdcard/KSafe/ksafe_import.json
```

Then tap **Import** in the Settings tab. KSafe will read `ksafe_import.json` and apply the configuration immediately.

> If you are migrating from an older KSafe version and still have `ksafe_import.json` at the old location (`/sdcard/Android/data/com.enderthor.kSafe/files/`), Import finds it there automatically as a one-version fallback — move it to `/sdcard/KSafe/` for future use.

> The export and import files have intentionally different names so there is no risk of accidentally overwriting a backup you just made.

## Easiest way to enter API keys and tokens

Typing long tokens (Pushover App Token, Telegram Bot Token, etc.) on the Karoo touchscreen is tedious and error-prone. The fastest workflow is to export the configuration, edit the JSON on your computer, and import it back.

1. Open KSafe on your Karoo and tap **Export** (Settings tab, bottom of screen).
2. Pull the file to your computer with ADB:
   ```bash
   adb pull /sdcard/KSafe/ksafe_export.json
   ```
3. Open `ksafe_export.json` in any text editor. The exported file has a dedicated block for each provider, each with only the fields that provider actually uses:

   | Provider block | Field | Description |
   |----------------|-------|-------------|
   | `callmebot` | `apiKey` / `apiKey2` / `apiKey3` | Up to 3 API keys, one per recipient (each WhatsApp number gets its own key from callmebot.com) |
   | `callmebot` | `phoneNumber` / `phoneNumber2` / `phoneNumber3` | Up to 3 recipient WhatsApp numbers with international prefix, no `+` (e.g. `34612345678`) |
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
       "phoneNumber": "34612345678",
       "apiKey2": "",
       "phoneNumber2": "",
       "apiKey3": "",
       "phoneNumber3": ""
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
   adb push ksafe_export.json /sdcard/KSafe/ksafe_import.json
   ```
5. Tap **Import** in KSafe — all keys are applied instantly.

> [!TIP]
> Use the same workflow to back up your configuration before updating the app, or to copy your setup to another Karoo device.

> [!NOTE]
> The import is tolerant: you can fill in only the providers you use and leave the rest empty. Unknown or extra fields are silently ignored, so imports from older or newer versions of KSafe always work.

## Editing alert messages from the export

The 12 customisable alert texts (carb / hydration title + detail, medical title + detail, and the three wellness tier title + details) all fall back to a built-in default at runtime when the configured value is empty. To make those defaults visible and editable from the JSON, **the export pre-fills any empty field with its current localised default text**. You see something like:

```json
"carbAlertCustomTitle": "Eat something",
"carbAlertCustomDetail": "Behind by {deficit}g — eat now",
"medicalCustomTitle": "Possible medical episode",
```

instead of a sea of empty strings. Edit the wording in place, save, push back, import. No need to guess what the default looks like before changing it.

> [!TIP]
> Tokens like `{deficit}`, `{elapsed}`, `{target}` (fueling) or the runtime substitutions in medical / wellness messages keep working exactly the same after you customise. See [health-fueling.md](health-fueling.md) for the token reference.

The trade-off: once an alert field has been materialised in your exported / re-imported config, it no longer picks up future updates to the default text (e.g. translation fixes shipped in a later KSafe release) for that specific field. The same is true for anyone who has explicitly customised an alert — which is the intent of materialisation. Clear the field back to empty in the JSON before importing if you want to revert that one to "always follow whatever the current default is".
