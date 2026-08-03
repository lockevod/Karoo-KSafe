# KSafe — Easy Setup (start here)

> **New to KSafe? Read this first.** It's the plain-English version: what to tap, what to
> set, and what to ignore. No formulas, no jargon. When you want the full detail on any
> topic, each section links down to the in-depth guide.
>
> 🇪🇸 ¿Prefieres español? → [easy-setup.es.md](easy-setup.es.md)

KSafe is a free safety app for your Karoo. It does two jobs:

1. **If something goes wrong** — a crash, a sudden stop, a missed check-in, or (with a
   heart-rate strap) a medical event — it can **send an alert to a person you choose**.
2. **Before things go wrong** — it can **remind you to eat and drink** so you don't bonk
   or get dehydrated.

You don't need to understand how any of it works. You need to do three small things, below.

---

## ✅ The 5-minute safe setup

Do just this and you're protected. Everything else is optional polish.

### 1. Choose ONE way to send alerts (the "Provider")

Open the **Provider** tab and pick the one that fits you:

- **Just want it free and fast?** → use **ntfy** or **Telegram**.
- **Your contact lives in WhatsApp?** → use **CallMeBot**.
- **Want the most reliable phone notification and don't mind a one-time ~$5?** → **Pushover**.
- **Already have an Apprise instance running (or want alerts fanned out to many services at once)?** → **Apprise** (self-hosted, advanced).

The two easiest are spelled out step by step further down: [Senders made simple](#-senders-made-simple).

### 2. Press **Test Send**

After you fill in the provider, tap **Test Send**. Your contact (or your own phone) should
get a message within seconds. **If nothing arrives, don't skip this** — go to
[Alerts not arriving?](#-alerts-not-arriving-fix-the-3-common-causes). A safety app that
wasn't tested isn't a safety app.

### 3. Write your emergency message

In the **Safety** tab, edit the **emergency message** so your contact knows it's you and
what to do (e.g. *"This is an automatic alert from Pete's bike. I may have crashed. My
location: {location}"*). The `{location}` tag is replaced with a live map link automatically.

**That's the whole minimum.** Crash detection is already on with sensible defaults. The rest
of this guide is "nice to have", tab by tab.

> ⚠️ KSafe sends messages through **your phone's internet** (via the Hammerhead Companion
> app). No phone signal = no alert. This is true of every alert provider.

---

## 📑 Tab by tab, in plain English

Your Karoo shows six tabs. Here's what each is for, what to set, and what to leave alone.

### 🛡️ Safety — the core (set this)

This is the heart of KSafe and it works out of the box. The things worth touching:

- **Crash detection sensitivity** — pick the one that matches your riding:
  - **Low** — ⛰ MTB / gravel / enduro. Only a very hard hit triggers it. Use this if rough
    trails set off false alarms.
  - **Medium** — 🚴 the recommended default. Good for road and mixed riding.
  - **High** — 🏁 smooth road / track only. Catches lighter crashes, but rough ground will
    cause false alarms — don't use it off-road.
- **Emergency message** — the text your contact receives (see step 3 above).
- **Check-in timer** *(optional)* — KSafe asks you to tap a button every so often; if you
  don't (e.g. you're hurt and can't), it raises the alarm. Good for solo rides. Off by default.

You can leave everything except the message at its default and be well protected.
*(One-tap "I'm OK" buttons and ride start/end notifications live in the **Actions** tab — see below.)*

### ❤️ Health — only if you ride with a heart-rate strap (optional)

Skip this whole tab if you don't use a heart-rate sensor. If you do:

- **Medical detection** — watches for your heart rate flat-lining or collapsing, and treats
  it like a crash (alerts your contact). Sensible to leave on if you have a strap.
- **Wellness monitor** — warns *you* (beep + on-screen, **not** sent to contacts) if your
  heart rate stays dangerously high or drifts — a sign of overexertion or heat. Off by
  default; turn on if you want it.

Full detail: [Health & Fueling reference](health-fueling.md).

### 🍫 Fueling — eat & drink reminders (optional, see the simple version below)

This is the tab people find most confusing, so it has its own plain-English section:
[Fueling made simple](#-fueling-made-simple). Short version: you **don't** type in a calorie
or carb number — KSafe works it out. You just tell it your age and sex, and how often you
want a nudge.

### 🔘 Actions — extra buttons & notifications (optional)

Three things live here, all optional:

- **Custom message buttons** — one-tap *"I'm OK"* / *"Heading home"* messages you fire from a
  data field on your ride screen. Handy, easy to set up.
- **Ride start / end notifications** — sends a *"ride started / finished"* note to your
  contact, with an optional live-tracking link.
- **Webhooks** *(advanced)* — fire any internet command from a button on your Karoo (open your
  garage door, trigger Home Assistant, etc.). Skip this one unless you specifically want it.
  Detail: [Webhooks cookbook](webhooks-cookbook.md).

### 📨 Provider — who gets your alerts (set this — it's step 1 above)

Where you choose and configure how alerts are sent. Covered in
[Senders made simple](#-senders-made-simple).

One thing worth knowing: each contact has a "what this contact receives" setting —
**All** (default), **Emergency**, or **Info**. Most people leave it on **All**.

### ⚙️ Settings — housekeeping (mostly leave alone)

- **Language** follows your Karoo automatically (English or Spanish) — there's no switch.
- **Help improve crash detection** — an optional toggle that sends anonymous sensor data
  after rides (no GPS, no messages, no names). Turning it on genuinely helps make crash
  detection better. Your call.
- **Backup / restore** your settings, and **Write fueling to FIT** (off by default) — both
  optional.

---

## 📨 Senders made simple

You only need **one**. Here are the two easiest, fully spelled out. (For CallMeBot/WhatsApp,
Pushover, and Apprise, see the full [Messaging providers guide](messaging-providers.md) — same
idea, a couple more steps.)

### Easiest: ntfy (free, no account)

ntfy is a free notification app. You pick a secret "channel name" and KSafe shouts into it.

1. On the phone that should receive alerts, install the **ntfy** app (Android or iPhone).
2. In ntfy, tap **+**, type a **topic name** that's hard to guess
   (e.g. `ksafe-pete-7x4k9`), and **Subscribe**.
3. On the Karoo: **Provider** tab → choose **ntfy** → type the **same** topic name → **Test Send**.

That's it. To alert several people, they each subscribe to the same topic name.

### Also easy: Telegram (free, unlimited)

1. In Telegram, search **@BotFather**, send `/newbot`, and follow the prompts. It gives you
   a long **Bot Token** — copy it.
2. Get the **Chat ID** of whoever should receive alerts: on **their** phone, search
   **@userinfobot** in Telegram and send `/start`. It replies with a number — that's the Chat ID.
3. **The one thing people forget:** that person must open Telegram, find **your** new bot,
   and press **Start** once. Until they do, messages silently fail.
4. On the Karoo: **Provider** tab → **Telegram** → paste the **Bot Token** → put the **Chat ID**
   in Recipient 1 → **Test Send**.

> You can save settings for all five providers at once — only the one you select is used,
> and switching never erases the others.

---

## 🍫 Fueling made simple

The technical guide talks about burn estimators, tiers and gut-absorption ceilings. Forget
all of that. Here's what you actually do.

### The key idea (read this once)

KSafe can't measure the sugar in your blood — no bike sensor can. So instead it **estimates
how much you've burned** from your heart rate or power meter, **counts what you've eaten**
(every time you tap a "log" button), and **nudges you when you fall behind**. You don't set
a target number for carbs — KSafe figures the burn out for you.

### Carbs: 3 things to do

1. **Pair a sensor.** Best is a **power meter**; next best is a **heart-rate strap**. Without
   one, KSafe can't estimate carb burn (the field shows *"Pair HR/Pwr"*).
2. **If you only have heart rate:** in the **Fueling** tab, fill in your **Age** and **Sex**.
   That's the only personal info it needs — it makes the estimate noticeably more accurate.
3. **Pick how often to be reminded.** Leave the defaults (remind me when I'm ~25 g behind,
   and no more than every 10 min) unless you find them too chatty or too quiet.

### Hydration: 1 thing to do

Set a **drink target per hour**. Use this rough guide by weather:

| Weather | Drink per hour |
|---|---|
| Cool (under 15 °C) | 400–600 ml |
| Mild (15–22 °C) | 600–800 ml *(default 750)* |
| Warm (22–28 °C) | 800–1100 ml |
| Hot (28–32 °C) | 1100–1400 ml |
| Very hot (over 32 °C) | 1400–1800 ml |

The default (750 ml/h) suits a mild day — bump it up for summer. If you'd rather not think
about it, turn on **Dynamic estimate** and KSafe adjusts the target from your effort and the
temperature.

### "What do I tap?"

KSafe only knows you ate or drank **when you tap a log button**. Add the log fields you want
to your ride screen (in the Karoo profile editor):

- **Carb log slots** (e.g. labelled *"Gel"*, *"Bar"*) — one tap = one item logged.
- **Drink log slots** (e.g. *"Bottle"*) — one tap = one drink logged.
- **Combined button** (called *Fuel Combo* in the profile editor) — logs a drink *and* its
  carbs in one tap.

Tapped by mistake? Tap the same slot again within ~5 seconds to undo it.

That's the whole thing: pair a sensor, fill age/sex, set a drink target, and tap when you
eat or drink. Full science and calibration tips: [Health & Fueling reference](health-fueling.md).

---

## 🔧 Alerts not arriving? Fix the 3 common causes

If **Test Send** didn't arrive, it's almost always one of these:

1. **No phone connection.** KSafe sends through your phone's internet via the Hammerhead
   Companion app. Make sure your phone is connected and Companion is running.
2. **Telegram: the contact never pressed Start.** A Telegram bot can only message someone
   who has opened it and pressed **Start** at least once. This is the #1 cause. (See the
   Telegram steps above.)
3. **CallMeBot: set up on the wrong phone.** CallMeBot must be activated **from the
   contact's WhatsApp**, not yours, and each contact has their **own** key. See the
   [Messaging providers guide](messaging-providers.md).

Still stuck? The full provider guide has a troubleshooting note for each option:
[messaging-providers.md](messaging-providers.md).

---

> Want the deep detail on any topic? Start from the [README](../README.md), which links every
> in-depth guide: crash detection, medical/wellness, fueling science, webhooks, and more.
