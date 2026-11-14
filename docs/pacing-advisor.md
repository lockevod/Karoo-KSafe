# Pacing advisor (beta)

Optional, **off by default**, guidance only. Enable it in the Settings tab, section **Pacing advice (beta)** (above FIT export). There are no other settings: FTP, weight and HR/power zones come from your Karoo rider profile, and W′ uses a default by bike type (15 kJ road/gravel, 20 kJ MTB, from the ride profile's routing preference).

## Requirements
Every alert needs a **route loaded** on the Karoo. Without a trusted GPS fix, or when you are off-route, there is no advice. What else each alert needs:

| Alert | Needs |
|---|---|
| **Save it for the climb** / **Save energy** (Conserva) | A power meter **or** a heart-rate strap. With HR only the work is estimated: wider margin, no watts ceiling |
| **Ease off before the climb** (Suelta antes del puerto) | A heart-rate strap + HR zones in your Karoo rider profile |
| **High margin** (Margen alto) | A power meter |
| **Recover before the climb** (Recupera antes de subir) | A power meter + FTP in your Karoo rider profile |
| **Good moment to eat** (Buen momento para comer) | Carb tracking on (no power needed) |

The HR-only estimate also needs **age and sex** (Carbs tab → Rider physiology) and your **weight** in the Karoo rider profile, and HR for at least 90 % of the moving time. With a power meter sending, the work always comes from power, never from HR.

## How it works
It looks at the climbs ahead — the Karoo route climbs, confirmed against the route elevation; on reversed routes KSafe detects climbs itself — and at your measured state: accumulated work, HR/power drift on steady efforts and W′ balance. It also learns your speed per gradient from your rides (stored locally on the Karoo) to time the alerts.

Without power, accumulated work is estimated from heart rate (Keytel, HR + age + sex + weight — the same formula as the carb tracker), reading 1 kcal burned as about 1 kJ of work. Being an estimate, it raises Conserva only when the projection is 10 % past the limit; drift, High margin and W′ stay power-only.

The ease-off cue is separate and works with or without power: when your mean HR over the last 5 moving minutes is at or above the lower bound of your Karoo HR zone 4, a climb is about 5 min ahead and over 40 % of the ride is left. It is not raised for a climb already flagged Conserva or High margin.

## Alerts
| Alert | Meaning |
|---|---|
| **Save it for the climb** / **Save energy** (Conserva) | Save energy for the climb ahead; with power, a watts ceiling when it is a plausible ease-off |
| **Ease off before the climb** (Suelta antes del puerto) | Your HR has been high (zone 4+) going into a climb: ease the pace |
| **High margin** (Margen alto) | You have margin. Descriptive; it never tells you to push |
| **Recover before the climb** (Recupera antes de subir) | W′ is low before a climb |
| **Good moment to eat** (Buen momento para comer) | Fuel hint before a climb (when carb tracking is on) |
| **Pace OK** (Ritmo correcto) | You are leaving conserve |

Alerts are rare:
- Pacing and W′ alerts keep at least 3 min from any other KSafe alert.
- At most one pacing alert at a time, and at most one per 10 min (a harder later climb may come after 3 min; so may that climb's Conserva / High margin after an ease-off cue).
- No double "eat": the fuel hint is skipped when the carb tracker reminded you in the last 15 min or a timed carb reminder is due within 15 min. Once shown, the hint counts as the carb deficit reminder: that reminder's interval restarts from the hint.
- Once per climb.
- Never while stopped or off-route, never during an SOS, countdown or SOS retry, nor within 3 min after it.

Advice is withheld when data is missing or stale.

## Reserve data field
`CONS` / `OK` / `MARGIN` / `W′!`, with W′ % or km to the next climb as a hint. Grey `OFF` when the switch is off; `---` while waiting for data. With HR only it shows `CONS` / `OK` (once the HR estimate is usable) and km to the next climb; `MARGIN` and W′ need power.

## Limits
Beta: thresholds are initial values and will be calibrated from calibration logs. Guidance only, not coaching.

## Privacy
Everything runs on the Karoo. With calibration logging on, the advisor also runs in shadow and writes `PACE_*` rows, which travel with the anonymous calibration log — see [calibration-logging.md](calibration-logging.md).
