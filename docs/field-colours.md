# Field Colours

Each KSafe ride field has a customisable **idle background colour** — the colour shown when the field is in its normal/ready state. Eight dark colours are available, all chosen for legibility with white text on a Karoo display.

> [!NOTE]
> The configurable idle palette is **dark hues only**. The state-driven colours (red, orange, yellow, green) are reserved by the state machine — see the table below — so your pick can never collide with a meaningful runtime signal.

## Idle palette

| Swatch | Colour | Default for |
|--------|--------|-------------|
| 🔵 | Blue | Webhooks, Custom Messages |
| 🟢 | Forest Green | SOS (SAFE state), Safety Timer (OK state) |
| 🟣 | Deep Purple | — |
| 🩵 | Teal | — |
| 🍷 | Wine / Dark Pink | — |
| 🟤 | Brown | — |
| ⬛ | Slate Grey | — |
| 🌌 | Midnight Blue | — |

## State-driven colours (always preserved)

These colours override your idle choice when the field enters the corresponding state:

| State | Colour | Applies to |
|-------|--------|------------|
| Countdown (SOS / Timer) | Orange | All fields |
| Alert sent | Red | SOS |
| Timer warning | Yellow | Safety Timer |
| Timer expired | Red | Safety Timer |
| Sending | Orange | Custom Messages |
| Sent ✓ | Green | Custom Messages |
| Error | Red | Custom Messages |
| Disabled | Grey (`OFF`) | All toggleable fields |

## Where to change idle colours

- **SOS field** → **Settings tab** → *SOS field colour* swatch row (near the top, under Countdown seconds).
- **Safety Timer field** → **Settings tab** → *Timer field colour* swatch row (just below the Check-in interval setting).
- **Custom Message 1 / 2 / 3** → **Actions tab** → expand the message slot → colour swatches below the message text field.
- **Webhook 1 / 2** → **Actions tab** → expand the webhook slot → colour swatches below the label field.
- **Carb / Hydration log slots** *(v2.0)* → **Fueling tab** → expand the slot → colour swatches alongside the icon picker.
