# Field Colours and Alignment

Nine of KSafe's 16 ride fields have a **rider-pickable idle background colour** (SOS, Safety Timer, Custom Message 1–3, Webhook 1–2, Carb Log 1–3, Hydration Log 1–2). The remaining four — Carb Burn Rate, Carbs Burned, Carb Status, Hydration Status — have no picker; their background is determined automatically (always Karoo theme for the two info readouts, deficit-driven semaphore for the two status fields). Two of those four (Carb Burn Rate and Carbs Burned) also follow the **per-field alignment** (left / center / right) the rider sets in the Karoo profile editor; everything else is always centered.

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

## Fields without a picker

Four fields have **no rider-pickable colour** — their background is determined automatically by what the field shows.

**Always Karoo theme** (passive numeric readouts that should look native):

- **Carb Burn Rate** — instantaneous burn rate (g/h).
- **Carbs Burned** — cumulative session target (g).

These two also follow the **per-field horizontal alignment** the rider sets in the Karoo profile editor — they read as data alongside native Karoo fields, so blending in is the point.

**Always deficit-driven semaphore** (colour conveys the value):

- **Carb status** — blue ahead, green within margin, amber approaching threshold, red over threshold. White text on the coloured bg, centered.
- **Hydration status** — same scheme in ml.

A picker would make no sense on these two: the colour *is* the information. They show `---` in their normal colour (not grey) while waiting for the first tracker tick — see the state-driven table below for the contract.

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

Only two fields follow the **per-field alignment** (`LEFT` / `CENTER` / `RIGHT`) the rider sets in the Karoo profile editor:

- **Carb burn rate**
- **Carbs burned**

These are passive numeric readouts that sit alongside native Karoo data fields (HR, power, speed…); inheriting the rider's alignment choice lets them blend in instead of standing out as misaligned.

Every other field is **always centered**. They are either tap targets (SOS, Safety Timer, Custom Messages, Webhooks, Carb/Hydration log slots) where center balances the touch surface, or coloured state indicators (Carb status, Hydration status) where the deficit text reads better centered inside the semaphore-coloured field.

For the two alignment-respecting fields, the default if the rider hasn't changed it is `RIGHT` (matches Hammerhead's own default for native fields). karoo-ext < 1.1.2 didn't surface alignment to extensions; KSafe falls back to `RIGHT` on those devices.

## Karoo-theme text colour (implementation note)

When a field is in **Karoo default** mode (no painted background, theme-driven appearance), KSafe sets the text colour explicitly at render time based on the system-wide night-mode flag (`Configuration.UI_MODE_NIGHT_MASK`): **white text in night mode, black in day mode** — matching whatever background the Karoo OS will draw underneath the field. We do not rely on theme attributes (`?android:attr/textColorPrimary`) baked into the layout because on Karoo hardware they did not resolve to a contrasting colour in testing (riders reported white text on a white field — invisible).

## Where to change idle colours

- **SOS field** → **Safety tab** → *SOS field colour* swatch row (near the top, under Countdown seconds).
- **Safety Timer field** → **Safety tab** → *Timer field colour* swatch row (just below the Check-in interval setting).
- **Custom Message 1 / 2 / 3** → **Actions tab** → expand the message slot → colour swatches below the message text field.
- **Webhook 1 / 2** → **Actions tab** → expand the webhook slot → colour swatches below the label field.
- **Carb / Hydration log slots** *(v2.0)* → **Fueling tab** → expand the slot → colour swatches alongside the icon picker.

In every picker the **first swatch** is the Karoo-default (auto day/night). Selecting it stores a sentinel value internally; existing saved colours (any non-sentinel ARGB int) remain valid forever and keep rendering exactly as before.
