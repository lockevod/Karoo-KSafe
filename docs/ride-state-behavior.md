# What KSafe does in each ride state

Karoo has three ride states the app reacts to: **Idle** (no recording),
**Recording**, and **Paused**. Each subsystem behaves slightly differently
depending on which state you're in. This page is a single reference for what
is and isn't running, so you can predict the field readouts and battery cost
without having to dig through the source.

> Naming note: the table refers to "Idle" the way Karoo's SDK does (no
> recording in progress). When you have a route loaded but haven't pressed
> Start yet, you're still in Idle.

## Quick table

| Subsystem | Idle (no recording) | Recording (moving) | Recording + Paused |
|---|---|---|---|
| **Carbs / Hydration trackers** | Stopped — status fields show `---` | Running + integrating | Running, **movement gate freezes integration** (cumulative totals stay) |
| **Medical detector** (HR-driven cardiac events) | Stopped | Active | **Active** (a medical event during a coffee stop is still detected) |
| **Wellness monitor** (sustained HR + decoupling) | Stopped | Active | Active |
| **Crash detection** (accelerometer) | **Depends on the `Monitor crash outside ride` setting** (off by default) | Active | **Active** (a fall while paused is still detected) |
| **Speed-drop watchdog** | Same as crash detection | Active | Zero-speed accumulator reset on pause (intentional pauses don't false-trigger) |
| **Power / HR / Temperature / Headwind / UserProfile sensor streams** | **Not subscribed** (battery win — the SDK isn't keeping radios alive) | **Only the ones an enabled feature reads** (v2.2.3) | Stay subscribed across pause/resume |
| **Speed / Cadence / Grade / RideProfile streams** | Subscribed (needed by crash detection if monitor-outside-ride is on) | Subscribed | Subscribed |
| **Check-in timer** | Stopped | Active, counting down | **Frozen — elapsed preserved, resumes from where it left off** (does NOT reset; any active warning/countdown cancelled) |
| **SOS button + emergency flow** | Available | Available | Available |
| **GPS location sampler** | Once every 2 min | Once every 2 min | Once every 2 min |

## Why each state behaves this way

### Idle (no recording)

This is the most battery-friendly state. The fueling trackers, medical
detector and wellness monitor are all stopped — they have nothing to
integrate against. The Power / HR / Temperature / Headwind / UserProfile
sensor streams are cancelled, so the Karoo SDK doesn't keep BLE / ANT+
radios alive for consumers that don't exist. Significantly lighter than
the previous behaviour where every stream was subscribed at extension
boot regardless of ride state.

Crash detection is a special case: it has a `Monitor crash outside ride`
toggle in the Safety tab. If you enable that, the accelerometer pipeline
keeps running with the configured minimum speed (or any speed, if the
"any-speed" variant is also on). Otherwise, even crash detection is off
while you're not recording.

You can still tap the SOS data field at any time. The emergency flow
doesn't depend on ride state.

### Recording (moving)

Everything is on. Trackers integrate carbs / hydration / wellness
according to your HR + power signal (or fall back to time-proportional
mode when no sensors are paired). Crash, medical and wellness detectors
are all active. The check-in timer counts down from whatever interval
you configured.

The cumulative carb/hydration totals you see in the status fields are
"what your body should have used since the start of the ride". They only
advance when you're actually moving (≥ 2 km/h) — if you're spinning
backwards on a turbo or sitting at a traffic light, the trackers freeze.

### Recording + Paused

When Karoo autopauses (or you press Pause manually), KSafe treats this
as "Recording-light":

- **Trackers keep ticking** but the movement gate stops them integrating.
  So your cumulative carbs/hydration freeze at whatever value they had
  when you stopped. They resume from there when you start moving again.
- **Crash detection stays active**. If you fall while paused (or the bike
  falls over with you on it), the accelerometer pipeline still fires.
- **The speed-drop watchdog resets its zero-speed accumulator** the
  moment you pause. Without this, intentionally stopping at a café for 10
  min would falsely trigger "rider has been stopped too long".
- **Medical / wellness detection keeps running**. If you have a cardiac
  event mid-pause, KSafe still fires the alert flow.
- **The check-in timer freezes — it does NOT reset — and any active warning
  countdown is cancelled**. The elapsed time is preserved and the timer
  resumes from exactly where it left off when you start riding again. So you
  won't get nagged with "5 min to check in" while you're ordering coffee, but
  the dead-man's-switch is not silently disarmed either. This freeze/resume
  semantics matters a lot with auto-pause — see
  [the dedicated section below](#the-check-in-dead-mans-switch-across-pauses).
- **When you unpause**, the trackers use `resume()` (not `start()`) — the
  carbs and hydration you logged before the pause are preserved across
  the coffee stop. This wasn't always true; older versions reset the
  totals on every pause/resume cycle.

The sensor streams (Power, HR, Temperature, Headwind, UserProfile) stay
subscribed across pause cycles to avoid the cost of resubscribing every
time Karoo autopauses on a long downhill or at every traffic light.

### Which streams Recording actually opens (v2.2.3)

Recording no longer subscribes to all six sensor streams unconditionally —
each one is opened only if a feature that reads it is enabled:

| Stream | Opened when |
|---|---|
| **Power** | Fueling (carbs or HR calories), Hydration, Wellness or Medical is on |
| **Heart rate** | same set as Power |
| **UserProfile** (zones, physiology) | Fueling, Hydration or Wellness is on |
| **Temperature + Headwind ambient** | Hydration is on |

Every emission is a Binder round-trip that wakes the process roughly once a
second, so this matters on long rides. A default install (medical episode on,
everything else off) opens two streams instead of six; a rider using KSafe
purely for crash detection opens none. Enabling a feature mid-ride rebuilds
the block, so it still receives its inputs immediately.

If the Karoo SDK ends a stream mid-ride (the producer completing or erroring —
for the Headwind streams that producer is a third-party extension), KSafe now
resubscribes with backoff instead of staying silently dead for the rest of the
ride. Before v2.2.3 a dropped HR stream disabled medical detection and a
dropped speed stream could reject every impact until the ride ended.

## The check-in dead-man's-switch across pauses

The **check-in timer** is the "prove you're still conscious" feature: every
*X* minutes it raises a cancellable SOS countdown, and you cancel it to say
"I'm fine". The subtle part is what it should do when the ride pauses — and it
interacts badly with **auto-pause** if you get it wrong. There are three
possible behaviours, and only the third is correct:

| Behaviour | Long café stop | Frequent auto-pause (traffic lights) |
|---|---|---|
| Keep counting through the pause | ❌ fires while you're in the café | ✅ |
| **Reset** to a full interval on every resume | ✅ | ❌ **never fires** on a stop-start ride |
| **Freeze + resume** (what KSafe does) | ✅ no spurious fire | ✅ keeps advancing, still fires |

KSafe **freezes** the timer on pause and **resumes from where it left off** —
it neither keeps counting nor resets:

- **On pause:** the elapsed time is preserved (the logical start timestamp is
  kept) and the warning + expiry jobs are cancelled. Nothing fires while
  you're stopped.
- **On resume:** the logical start is shifted forward by the pause duration,
  so the remaining interval is exactly what was left when you stopped — not a
  fresh full interval.

The bug this avoids: an earlier version re-armed a **full** interval on every
`Recording` entry. With auto-pause firing at every light, the timer reset
every couple of minutes and **could never reach expiry** — the dead-man's-
switch was silently dead. Freeze-and-resume fixes that while still keeping the
café quiet.

**Consequence — the check-in measures riding time, not wall-clock time.** A
120-minute check-in on a stop-and-go ride fires after 120 minutes of *actual
movement* (which might be ~140 minutes of wall-clock once you subtract 20
minutes of stops). That is the only way to satisfy both columns of the table
above: not spamming at the café **and** still working with auto-pause enabled.

**What about collapsing at a stop?** If you fall and the bike stops, auto-pause
kicks in and the check-in freezes — so the check-in itself won't fire. That
case is deliberately left to the detectors that **stay active during a pause**:
crash detection, the speed-drop watchdog (sustained zero speed), and the
medical FLATLINE / COLLAPSE detectors. The check-in is specifically the
"moving rider, prove you're awake" timer; the others cover the
"stopped / collapsed" case.

## Edge case: stuck GPS during a pause

A subtle issue: when you pause inside a tunnel or dense forest, the
Karoo SDK doesn't always emit `speed = 0`. Sometimes it just keeps
re-emitting the last known speed (e.g. 25 km/h) until GPS recovers. The
naive movement gate would think you're still riding and keep integrating
ghost carbs / hydration.

KSafe has a second guard for this case: if the SDK has been emitting the
exact same speed value for more than 10 seconds, the staleness check
freezes integration regardless of what the number says. So a long pause
with no GPS lock correctly freezes your totals.

## Side effects you might notice

- **Status fields read `---` more often than you'd expect.** Any of these
  situations show `---`: tracker not running yet, master switch off,
  movement gate blocking, GPS stale, or fewer than ~one tick of real
  data received. The three fueling status fields (carbs burned, burn
  rate, deficit) are always coherent: if one shows `---`, the others do
  too.
- **The Safety Timer field shows `Timer\nOFF`** as soon as you pause,
  even mid-ride, because the check-in clock is paused. It snaps back to
  the regular countdown when you resume.
- **The SOS field stays available across every state.** If you press it
  while no ride is recording, you still get the full countdown +
  cancellable emergency flow.

## Bonus: field visibility across day / night theme

If you set a tappable field to the **Karoo-theme auto** swatch (the
black/white half-and-half entry at the start of the colour picker), KSafe
detects whether the Karoo is currently in day or night mode and flips the
text + icon colour accordingly:

- **Day mode** — the host paints a white field background. Text renders
  in black, and the bundled gel / bottle drawables (the `<gel>` / `<bottle>`
  entries at the start of the carb / hydration icon pickers) switch to a
  dark-fill variant so they don't vanish against white.
- **Night mode** — host paints a dark background. Text is white, drawables
  use their original white-fill version.

The other 20 colour swatches are always dark, so any field set to one of
those uses white text + white drawables across day / night without
needing the theme to flip anything.

The emoji icons (🍌, 🍫, ⚡, 💧 …) are rendered by Android's colour-emoji
font, so they keep their natural colours regardless of background. Even
on a white field in day mode, the colour glyphs are visible.

This applies to: SOS, Safety Timer, Custom Message (3 slots), Webhook (2),
Carb Log (3) and Hydration Log (2). The four passive status fields
(Carb Status, Carbs Burned, Carb Burn Rate, Hydration Status) always use
the Karoo-theme look and don't expose a colour picker — they're meant to
read like native Karoo data fields.

## See also

- [crash-detection-algorithm.md](crash-detection-algorithm.md) — the
  state machine that runs whenever crash detection is active.
- [fueling-algorithm.md](fueling-algorithm.md) — how the carb / hydration
  trackers integrate against HR + power.
- [medical-wellness-algorithm.md](medical-wellness-algorithm.md) — what
  the medical detector and wellness monitor each catch.
- [configuration-reference.md](configuration-reference.md) — every
  config field that affects the behaviour above.
