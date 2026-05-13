# Field Colours and Alignment

Each KSafe ride field has a customisable **idle background colour** and respects the **per-field alignment** (left / center / right) the rider sets in the Karoo profile editor.

## Idle background colour

When the field is in its normal/ready state (no countdown, no flash, no error), the background is whatever the rider picked in KSafe. There are **21 entries** in the picker:

- **1 × Karoo default (auto day/night)** — the first swatch, rendered as a half-white / half-black circle. Selecting it makes the field render with **no custom background and theme-driven text colour**, so it looks native — black-on-white during the day, white-on-black at night. This is the new **default for fresh installs** and the recommended choice for riders who want their pages of data to look uniform with Hammerhead's own fields. It also matches the visual KDouble's neutral fields use.
- **20 × dark hues** — the painted palette, laid out as a 4 × 5 grid below the Auto swatch. Picked for legibility with white text on a Karoo display in direct sunlight (≥4.5:1 contrast).

> [!NOTE]
> The painted palette is **dark hues only**, with the state-driven colours (red, orange, yellow, green, grey) deliberately excluded — see the table further down. That way a custom field can never collide with a meaningful runtime signal regardless of which hue you pick.

### Painted-palette layout

The 20 hues are arranged 4 columns × 5 rows in the picker dialog, walking the rainbow row-by-row, light → dark within each row. The exact swatches and the rationale for each one live in `app/src/main/kotlin/com/enderthor/kSafe/data/ConfigData.kt` under `FIELD_COLOR_PALETTE`.

| Row | Hue family |
|-----|------------|
| 1 | Warm earth + green (browns + olive + forest green — the warm slot is constrained because orange/red/yellow are reserved by the state machine) |
| 2 | Cyan / teal → first blue |
| 3 | Blues → indigos |
| 4 | Purples → first pink |
| 5 | Pink → wine → neutrals (slates) |

All entries are at Material 700–900 luminance, so white text stays at ≥7:1 contrast (the threshold for primary numbers per the design guide).

## Always-Karoo-default fields (no picker)

Two status fields have **no rider-pickable colour** — they always render in Karoo theme:

- **Carb Burn Rate** — passive readout of the instantaneous burn rate (g/h).
- **Carbs Burned** — passive readout of the cumulative session target (g).

They were intentionally left without a picker because they're informational data fields, not action surfaces. Rendering them in auto theme makes them look identical to the rest of the rider's data page.

## State-driven colours (always preserved)

These override your idle pick (whether painted or Auto) whenever the field enters the corresponding state:

| State | Colour | Applies to |
|-------|--------|------------|
| Countdown (SOS / Timer) | Orange | All fields |
| Alert sent | Red | SOS |
| Timer warning (within 10 min of expiry) | Yellow | Safety Timer |
| Timer expired | Red | Safety Timer |
| Sending | Orange | Custom Messages |
| Sent ✓ | Green | Custom Messages |
| Error | Red | Custom Messages |
| Logged | Green (`+Xg` / `+Xml`) | Carb / Hydration log slots |
| Undone | Red (`−Xg` / `−Xml`) | Carb / Hydration log slots |
| Webhook firing / success / error | Orange / Green / Red | Webhooks |
| Disabled in config | Grey (`OFF`) | All toggleable fields |

> [!IMPORTANT]
> **Grey is reserved for "disabled in config".** A status field that is just *waiting for data* (e.g. Carb Status before you press Start) renders with `---` text in its **normal** colour, never grey. Grey on a status field would read as "I accidentally turned this off"; keeping the normal colour signals "ready, no data yet". See [docs/health-fueling.md](health-fueling.md#how-it-works-no-biometrics-required) for the four-state contract.

## Alignment (horizontal)

KSafe respects the **per-field alignment** (`LEFT` / `CENTER` / `RIGHT`) the rider sets in the Karoo profile editor for every field — same convention native Karoo fields and KDouble follow. The default if you haven't changed it is `RIGHT`, which matches Hammerhead's own default.

The setting applies to the main text and the hint line. State branches (LOGGED flash, SENDING…, OFF, etc.) follow the same alignment, so the field stays visually coherent across states.

Older karoo-ext SDKs (pre-1.1.2) did not surface alignment to extensions; KSafe falls back to `RIGHT` on those devices.

## Where to change idle colours

- **SOS field** → **Safety tab** → *SOS field colour* swatch row (near the top, under Countdown seconds).
- **Safety Timer field** → **Safety tab** → *Timer field colour* swatch row (just below the Check-in interval setting).
- **Custom Message 1 / 2 / 3** → **Actions tab** → expand the message slot → colour swatches below the message text field.
- **Webhook 1 / 2** → **Actions tab** → expand the webhook slot → colour swatches below the label field.
- **Carb / Hydration log slots** *(v2.0)* → **Fueling tab** → expand the slot → colour swatches alongside the icon picker.

In every picker the **first swatch** is the Karoo-default (auto day/night). Selecting it stores a sentinel value internally; existing saved colours (any non-sentinel ARGB int) remain valid forever and keep rendering exactly as before.
