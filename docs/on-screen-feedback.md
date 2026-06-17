# On-screen feedback, notifications & sounds

Every moment KSafe shows or sounds *something* to the rider, and through which channel.
This is the single reference for "what does the rider see/hear, and when". Outbound
messages to emergency contacts (the actual SOS / ride-start / ride-end texts) are covered
in [messaging-providers.md](messaging-providers.md); this document is about **on-device**
feedback.

> Source of truth: `EmergencyManager`, `KSafeExtension`, `SosOverlayManager`,
> `FuelingOverlayManager`, `CarbsTracker` / `HydrationTracker`, and the `datatype/` fields. If you change a dispatch
> site, update the matching row here.

## Channels (legend)

| Channel | What it is | Visible when |
|---|---|---|
| **InRideAlert** | Karoo SDK popover over the ride screen. Display-only (not tappable). Title ≤ ~40 chars, detail ≤ ~90. Colours are `@ColorRes`. | Only while the ride app is foregrounded (Recording / Paused) |
| **System overlay** | Full-width window drawn by `SosOverlayManager` via `SYSTEM_ALERT_WINDOW`. Two modes: **countdown** (`showOrUpdate`, with Cancel) and **info** (`showInfo`, with Dismiss). | Over *any* screen — ride app, launcher, Settings. Needs the "Draw over other apps" permission |
| **SystemNotification** | Android notification drawer entry. | Anywhere, but easy to miss — the rider rarely opens the Karoo drawer (especially mid-ride). Used only as a fallback |
| **Buzzer / beep** | Audible tone. Two paths: SDK `PlayBeepPattern` (respects the device mute) and the HAL bypass via `playEmergencyBeep` (can sound even when muted — see [the mute contract](#the-mute-contract)). | Always audible if not muted; emergency-class beeps also when muted (if the buzzer override is on) |
| **TurnScreenOn** | Wakes the Karoo screen. | Paired with the most urgent moments so a passive rider sees the alert |
| **Data-field colour** | A KSafe data field changes colour/text (passive, continuous — not a one-shot alert). | Whenever that field is on an active data page |

## The mute contract

Beeps split into two classes, and this split is promised to the rider in two Settings hints
(`safety_buzzer_mute_hint`, `settings_buzzer_bypass_hint`):

- **Emergency-class → bypass mute** (via `playEmergencyBeep`, only when the rider enabled
  *Buzzer on emergency*): crash/check-in/speed-drop **countdown**, **ALERTING**, **delivery
  failed**, **delivery partial**, and the **−1 min check-in warning**.
- **Non-emergency → respect mute** (plain SDK `PlayBeepPattern`): ride start (readiness),
  **wellness / medical-Warning** alerts, **fueling** alerts, and the **−5 min check-in
  warning**.

If the buzzer override is OFF (or the HAL bind fails / is gated by an OTA), `playEmergencyBeep`
falls back to the SDK path, so emergency beeps then also respect mute — i.e. worst case is
"same as a phone with no override", never silent-by-bug.

---

## 1. Emergency & countdown

| Moment | Channels |
|---|---|
| **Countdown** (crash / check-in expiry / speed-drop / SOS button) | **System overlay** (countdown mode, Cancel button, sticky) · **TurnScreenOn** at start and again at ≤10 s · **beeps**: `COUNTDOWN_START` at start, `COUNTDOWN_TICK` for the final ≤5 s, via `playEmergencyBeep` (**mute bypass**). The **SOS data field** turns orange (`COUNTDOWN`). No InRideAlert — the overlay *is* the interactive surface. |
| **Medical collapse → 10 s mini-confirm** | Same **system overlay** countdown (10 s) + beeps, before escalating to the full emergency. |
| **Alert firing (`ALERTING`)** | Beep `EMERGENCY_PATTERN` (rising, "sending now", **bypass**). SOS field turns red (`ALERTING`). |
| **Delivery FAILED** (SOS reached nobody, after all retries) | Beep `DELIVERY_FAILED` (descending, **bypass**) + **exactly one** visual channel by ride state: **InRideAlert** (red) if on the ride screen, **system overlay** (info mode, Dismiss) if off it, **SystemNotification** if overlay permission is missing. |
| **Delivery PARTIAL** (some but not all contacts reached) | Beep `PARTIAL` (two-tone, **bypass**) + **exactly one** visual channel by ride state (same routing as Delivery FAILED): **InRideAlert** (amber) if on the ride screen, **system overlay** (info mode) if off it, **SystemNotification** if overlay permission is missing. |

> Cancelling: the countdown overlay's Cancel button (and a tap on the SOS / Timer field)
> aborts the emergency, including during `ALERTING` (cancels the in-flight retry job).

## 2. Check-in (dead-man's-switch)

The check-in timer escalates to a full emergency countdown if the rider doesn't tap the
Check-in field within the interval. Two escalating pre-expiry warnings nudge them first:

| Moment | Channels |
|---|---|
| **−5 min** | Beep `BEEP_URGENT` (SDK, **respects mute**) + **InRideAlert** (amber, "Check-in in 5 min"). |
| **−1 min** | Beep `BEEP_URGENT` via `playEmergencyBeep` (**mute bypass**) + **InRideAlert** (amber) + **TurnScreenOn**. It's the last audible nudge before the SOS countdown. |
| Last 10 min | **SafetyTimer data field** turns amber (passive). |

The rider resets by **tapping the Check-in field** — the warnings are deliberately *not*
cancellable popups (that gesture would mimic the crash-cancel flow). See
[configuration-reference.md](configuration-reference.md) for the interval setting.

## 3. Health incidents (wellness / medical) — Warning level

| Moment | Channels |
|---|---|
| Wellness (high HR / critical HR / cardiac decoupling) or a medical incident set to **Warning** | **InRideAlert** (title/detail rider-configurable) + a **configurable** beep (`wellnessBeepPattern`, SDK, respects mute). |
| Same incidents set to **Silent** | **Nothing on screen** — logged only. |
| Same incidents set to **Emergency** | Routed to the §1 countdown. |

These only fire while recording (they need a live HR/power stream), so InRideAlert is always
the right channel — there is no off-ride fallback by design.

## 4. Fueling (carbs & hydration)

| Moment | Channels |
|---|---|
| Carb alert (deficit or time) | **InRideAlert** ("Eat something") + configurable beep (`carbBeepPattern`, SDK) — **or**, when the in-alert log button is enabled (`fuelingAlertButtonMode` = `LOG` / `LOG_UNDO`) and the "Draw over other apps" permission is granted, a tappable **system overlay** with a one-tap **✓ Log** button (`FuelingOverlayManager`). |
| Hydration alert (deficit or time) | **InRideAlert** ("Drink something") + configurable beep (`hydBeepPattern`, SDK) — or the same in-alert **✓ Log** overlay when enabled. |
| Logging a carb/drink/combo (field tap **or** overlay Log) | The corresponding **data field** flashes its logged state, then reverts. In `LOG_UNDO` mode the overlay then shows a brief **↶ Undo** (~4 s). |

> The fueling overlay never competes with the SOS screen: it is **suppressed** while an emergency is
> active, and **torn down** the instant an emergency starts **or** the ride ends (`RideState.Idle`).
> Detail: [fueling-algorithm.md → In-alert logging button](fueling-algorithm.md#in-alert-logging-button-overlay).

## 5. Ride start

| Moment | Channels |
|---|---|
| **Readiness advice** (if enabled, and only if the last rides warrant it) | **InRideAlert** colour-coded by level (green/amber/red), auto-dismiss 15 s. No beep. Silent when fully recovered. |
| Ride-start contact message | **Outbound only** (`sendInfo`) — no on-device feedback. |

## 6. Ride end

**Nothing is shown on the Karoo screen at ride end.** Everything is background / outbound:

| Action | Channel | On-device feedback |
|---|---|---|
| Ride-end contact message (if enabled) | Outbound `sendInfo` (single attempt, no retry) | None |
| Calibration log upload (if logging was on) | Outbound to the calibration bot (IO) | None |
| Fueling/wellness totals (carbs logged & burned, hydration, HR drift, alert count) | Written to the **FIT session** → shows as the activity summary in Strava / Intervals.icu / TrainingPeaks | Not a Karoo popup; also visible live in the in-ride status fields during the ride |

There is intentionally no end-of-ride summary popup. See
[ride-state-behavior.md](ride-state-behavior.md) for the full per-state subsystem behaviour.

## 7. Rider-initiated actions (webhook / custom message)

Feedback channel is picked by ride state (`dispatchWebhookFeedback`): **InRideAlert** when on
the ride screen (Recording / Paused), **SystemNotification** when Idle (launcher / Settings,
where the drawer is visible). The Webhook / Custom-Message **data fields** also flash their
state (FIRING / OK / ERROR).

Webhook **rejections** use the same channel with a specific message: master switch off, slot
disabled, no URL configured, no GPS fix yet, no target location, or blocked by the geo-fence
("Blocked — Nm away (max Mm)").

---

## Localisation note

All of the strings above live in `res/values/strings.xml` (English, default) and
`res/values-es/strings.xml` (Spanish). The app does **not** force a locale, so it follows the
Karoo's system language and falls back to English for any other language. Default config
*message templates* (emergency / ride-start / ride-end / custom messages) stay English by
design — riders who want to change them already edit them.
