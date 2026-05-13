# KSafe v2.1.0 — Health & Fueling

> [!IMPORTANT]
> This release **adds two new tabs** (Health and Fueling) to the existing safety extension. All previous features (crash detection, SOS, check-in timer, speed-drop, custom messages, webhooks) continue to work unchanged. Updating from v2.0.0 / earlier is safe — your provider credentials, contacts, custom messages and webhook config are preserved across the upgrade thanks to a hardened DataStore decode.

## Highlights

### Health tab *(new — optional, requires HR sensor)*

- **Medical episode detection.** Watches HR for two patterns that strongly indicate a medical incident: **flatline** (HR < 30 bpm for 30 s while moving, catches asystole / severe bradycardia) and **collapse** (HR drops ≥ 40 % from the 5-min rolling average, catches vasovagal syncope). Default response level is **Emergency** — same flow as a crash (countdown + alert to contacts). Stays silent if no HR sensor is paired — no false alarms when you don't wear a chest strap.

- **Three-tier wellness monitor.** Each tier is independently toggleable; all three default ON when the master is on:
  - **Critical HR** — 5 min over 95 % of max HR (or 175 bpm absolute). Early-warning for acute overexertion.
  - **Sustained HR** — 30 min over 92 % of max HR (or 180 bpm absolute). Long-tail fatigue / heat-stress reminder.
  - **Cardiac decoupling** — 10 min of > 7 % drift in the HR/power ratio vs the rider's own ride-specific baseline (requires power meter; auto-skips otherwise). Clinical indicator of dehydration / heat stress.

- **Customisable alert templates.** The popup title and detail line for every HR-based alert can be overridden per rider. Empty fields show the built-in default pre-filled for direct editing; type your own with `{bpm}` / `{threshold}` / `{minutes}` / `{drift}` placeholders. Rendered output is capped to fit the popup, with surrogate-pair-safe truncation so emoji-bearing templates never produce a broken character before the ellipsis.

### Fueling tab *(new — preventive safety, optional)*

- **Adaptive carb target.** Reads your HR zones (5) and power zones (7) from the Karoo's user profile and derives a real-time multiplier between 0.7× (recovery) and 1.3× (top zone) applied to your configured per-hour target. Power meter wins when both are paired; HR is the fallback. No biometric entry — KSafe reads everything from the Karoo profile.

- **Hydration target.** Flat per-hour value because temperature isn't exposed by the SDK. Raise manually for hot days.

- **Two combinable alert modes** per category (carbs, hydration):
  - **By deficit** — fires when `target_so_far − logged > threshold`.
  - **By time** — fires when `time_since_last_log > interval`, with optional per-category initial delay (default 30 min) so the rider isn't nagged at minute 25 of a fresh ride.
  - Customisable title and detail templates with `{deficit}` / `{elapsed}` / `{target}` tokens, same approach as the Health alerts.

- **Log slots as tappable data fields.** Up to 3 carb slots + 2 drink slots, each with its own configurable label, amount, idle background colour (12-hue palette with reserved state colours excluded), and icon — either a system emoji (cyclist-themed palettes per category) or one of two bundled vector drawables (sports gel pouch, cyclist bidón). One tap = one log; green flash confirmation; the field renders grey "OFF" when the master toggle is off so a stray tap can never mis-fire.

- **Hardware buttons.** Two new BonusActions (`KSafe: Log Carb`, `KSafe: Log Drink`) wired to slot 1 of each category — map them to your SRAM AXS shifter buttons to log without looking at the screen.

- **FIT-file export.** Cumulative carbs (g) and hydration (ml) written into every Record message in the FIT, plus the session summary, as float32 developer fields. Your activity in Strava / Intervals.icu / TrainingPeaks shows fueling as **two extra graphs alongside HR / power / cadence** — coaches can correlate fueling with effort directly. Default ON, toggleable. Cost is negligible (~0.05 % battery over a 5 h ride).

- **Post-ride summary.** A single InRideAlert with totals vs target ("Carbs: 85/120 g (71 %) • Hyd: 1100/1500 ml (73 %)") when the ride stops. On / off toggle; never sent to contacts.

### Cross-cutting fixes

- **Tolerant config decode.** A single stale enum value or removed field in a saved config no longer wipes the rest to defaults — kotlinx-serialization is now configured with `coerceInputValues = true`, so each problematic field falls back to its constructor default independently while the rest decodes normally. Diagnostic logging in the catch path makes future decode failures debuggable from logcat.
- **Compact reusable pickers.** Settings UI shows colour and emoji picks as compact buttons that open a dialog with the full palette grid, replacing the previous inline row. Saves vertical space and reads better when stacked side-by-side.
- **ProGuard rules refreshed** to wildcard-keep all DataTypes and add the standard enum rule (required by the new tolerant decode).

## Compatibility

- **Karoo 2 and Karoo 3**, both supported.
- **No data loss when upgrading.** Provider credentials, contacts, custom messages, webhooks, and crash-detection settings are all preserved.
- `CONFIG_VERSION` bumped to 7 with a migration that only stamps the version — none of the existing fields are altered.

## What is **not** affected

- Crash detection, manual SOS, check-in timer, speed-drop detection, ride-start / ride-end notifications, custom message buttons, webhook actions and the four message providers (WhatsApp via CallMeBot, Telegram, Pushover, ntfy) all behave exactly as in v2.0.0.

## Install / update

- New installs: long-press the APK link below on your phone, share to **Hammerhead Companion**, install.
- Existing installs (v2.0.0 or earlier): the Companion app should offer the update automatically via the manifest URL. Otherwise, sideload the APK following the same procedure.

## Known limitations

- Cardiac decoupling tier requires a paired power meter; otherwise it self-disables. The other two wellness tiers and medical detection work with HR alone.
- The bundled vector drawables for gel / bidón are designed for the dark coloured backgrounds of the data field; if you pair them with a very light idle colour they may not read well — pick a darker palette colour for that slot.

## Verification before relying on the new features

The Health and Fueling features have been built and code-reviewed but **on-device field testing is still recommended** before relying on them on a long ride. The reactive safety layer (crash detection, SOS, check-in timer) is unchanged and already proven.
