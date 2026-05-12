# Field Colours

Each KSafe ride field has a customisable **idle background colour** — the colour shown when the field is in its normal/ready state. Sixteen dark hues are available, all chosen for legibility with white text on a Karoo display in direct sunlight.

> [!NOTE]
> The configurable idle palette is **dark hues only**. The state-driven colours (red, orange, yellow, green) are reserved by the state machine — see the table below — so your pick can never collide with a meaningful runtime signal.

## Idle palette

Picker arranged 4 × 4. Row 4 was added in v2.x to widen the hue spread after riders reported the original 12 were too easy to confuse.

| Row | Swatches | Notes |
|-----|----------|-------|
| 1 | Blue, Deep Blue, Teal, Deep Teal | cool blues / blue-greens; Blue is the default for Webhooks and Custom Messages |
| 2 | Forest Green, Olive Green, Purple, Deep Purple | Forest Green is the default for SOS (SAFE) and the Safety Timer (OK) |
| 3 | Pink, Magenta, Slate, Deep Slate | warm cool-pinks and near-black slates |
| 4 | **Indigo, Brown, Dark Cyan, Burgundy** | new distinct hues — brown is the only warm earth tone, indigo / burgundy / dark cyan fill the gaps the original 12 left in the colour wheel |

All sixteen are at Material 700-900 luminance, so white text stays at ≥7 : 1 contrast (the threshold for primary numbers on the Karoo per the design guide).

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
