# Pacing advisor (beta)

Optional, **off by default**, guidance only. Enable it in the Settings tab, section **Pacing advice (beta)** (above FIT export). There are no other settings: FTP, weight and HR/power zones come from your Karoo rider profile, and W′ uses a default by bike type (15 kJ road/gravel, 20 kJ MTB, from the ride profile's routing preference).

## Requirements
- A **power meter** (no power = no advice; HR alone gives none).
- A **route loaded** on the Karoo. Without a trusted GPS fix, or when you are off-route, there is no route advice.

## How it works
It looks at the climbs ahead — the Karoo route climbs, confirmed against the route elevation; on reversed routes KSafe detects climbs itself — and at your measured state: accumulated work, HR/power drift on steady efforts and W′ balance. It also learns your speed per gradient from your rides (stored locally on the Karoo) to time the alerts.

## Alerts
| Alert | Meaning |
|---|---|
| **Conserva** | Save energy for the climb ahead, with a power ceiling |
| **Margen alto** | You have margin. Descriptive; it never tells you to push |
| **Recupera antes de subir** | W′ is low before a climb |
| **Buen momento para comer** | Fuel hint before a climb (when carb tracking is on) |
| **Ritmo correcto** | You are leaving conserve |

Alerts are rare: at least 3 min from other KSafe alerts, at most one pacing alert per 10 min, once per climb. Never during an emergency, never while stopped. Advice is withheld when data is missing or stale.

## Reserve data field
`CONS` / `OK` / `MARGIN` / `W′!`, with W′ % or km to the next climb as a hint. Grey `OFF` when the switch is off; `---` while waiting for data.

## Limits
Beta: thresholds are initial values and will be calibrated from calibration logs. Guidance only, not coaching.

## Privacy
Everything runs on the Karoo; nothing is sent. With calibration logging on, the advisor also runs in shadow and writes `PACE_*` rows — see [calibration-logging.md](calibration-logging.md).
