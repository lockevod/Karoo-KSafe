# Calibration-log ground-truth annotations

Labeled crash-detection events from real KSafe rides. Each entry is a ground-truth
label assigned by the rider after the ride and meant to be used as a named
regression seed for `CrashStateMachineTest` and related JVM tests.

The actual `.csv` log files referenced here are **not** in the repo — they live
under `logs/` (gitignored) or in the rider's local Telegram downloads. The labels
in this document survive even if the CSVs do not, so a future test author can
reconstruct or replay them when needed.

The Python analyser at `scripts/analyze_calibration_logs.py` understands the
event schema; the helper `_row_iter` parses any row referenced below.

The 6-char session IDs below (e.g. `58ee00`) are **random hex** generated fresh
on each logging session — no device identifier and no personal data (see
`CalibrationLogger.sessionId`). They are used here purely as regression-seed
names.

---

## Monday 2026-05-25 — session `58ee00`

- **App version:** v2.0.0
- **Profile / preset:** GRAVEL / LOW
- **Device:** k24 (Karoo 2)
- **Log file:** `calibration.csv` (262 KB, ~260 min, copied directly off device).
  The Telegram chunks `ksafe_v2.0.0_…_58ee00_k24*.csv` cover only the first
  ~155 min — anything past that is only in `calibration.csv`.
- **Annotated by:** Sergi, 2026-05-28.

### FN #1 — simulated impact, stale-cadence miss

| Field | Value |
|---|---|
| Elapsed | 97:83 min |
| Event sequence | `IMPACT_IN` → `SIL_TMO` @98:85 |
| `raw` / `smooth` | 82.5 / 36.2 m/s² |
| `speed` | 8.9 km/h |
| `cadence` | **39 rpm** (ghost) |
| `grade` | −11.9 % |
| Nature | **Simulated** (rider induced the impact for testing — not a real fall) |

The detector entered IMPACT correctly but silence-check timed out because the
cadence sensor kept reading 39 rpm after the rider was already "down". The
cadence-staleness path was identified as the root cause from this event and
fixed after this session. Because the impact is reproducible (controlled
stimulus), this is the easiest TP case to re-run when validating future
crash-detection changes.

### FN #2 — real fall, two impacts (stale cadence again)

| Field | A (122:88) | B (123:55) |
|---|---|---|
| Event | `IMPACT_IN` | `IMPACT_IN` |
| `raw` / `smooth` | 93.3 / 43.6 | 94.4 / 49.2 |
| `speed` | 6.7 | 5.8 |
| `cadence` | 0 | **88** (ghost) |
| `grade` | −13.6 | −11.5 |
| `gyro` | 2.00 | 4.65 |
| Outcome | SIL_BRK loop | SIL_BRK loop |

Real fall, logged twice (re-trigger 40 s apart). Both saw SIL_BRK loops and
neither confirmed. The second carries a particularly clear cadence ghost
(88 rpm during a stationary post-impact phase). Followed by `MANUAL_SOS`
@125:19 (rider's manual mark).

### FN #3 — third crash, manually marked

| Field | Value |
|---|---|
| Elapsed | 255:36 min |
| Event | `IMPACT_IN` (did not progress to confirm) |
| `raw` / `smooth` | 78.2 / 33.8 m/s² |
| `speed` | 15.0 km/h |
| `cadence` | 64 rpm |
| `grade` | +3.9 % |
| `gyro` | 2.93 |
| Marked by | `MANUAL_SOS` @256:10 |

Real crash similar in pattern to FN #2 (cadence ghost suspected). Rider
launched a second manual SOS to mark the event.

### FP #1 — fast descent + bump(s), surprising confirm

| Field | Value |
|---|---|
| Elapsed | 235:69 min |
| Event sequence | `IMPACT_IN` @235:57 → `CRASH_OK` @235:69 → `CRASH_NO` @235:81 |
| Pre-impact `raw` / `smooth` | 59.8 / **57.0** m/s² (smoothed-only, did not cross peak threshold 60) |
| `speed` at confirm | 36.6 km/h |
| `cadence` at confirm | 0 (rider not pedalling) |
| `grade` at confirm | −8.2 % |
| `deviation` at silence-check entry | 3.07 (very still) |
| Countdown | 30 s |
| Cancelled by rider | `how_long_ms` = 7222 ms |

False positive. Smoothed barely crossed the LOW threshold (×1.04) — but the
crossing was incidental. The actual mechanism that produced the confirm is
documented verbatim in `Thresholds.kt` (the `onSideRelaxationMaxSpeedKmh`
KDoc):

> Forward lean during the 2.2 s IMPACT phase accumulated ≥25 samples at ~75°
> vs the pre-impact upright reference, which engaged the on-side speed-rise
> relaxation. That collapsed the SILENCE window from the long upright path
> (20 s) to the short on-side path (4.5 s). CR2 on-side bump preservation
> then absorbed the descent bumps, so deviation stayed below `silenceDeviationMax`
> for the whole short window. CRASH_OK fired while the rider was still riding
> at 36 km/h.

**Rider account:** fast descent, **light braking (not hard)**, hit some
bump(s); the confirm was very surprising.

**Fix in current code:** `Thresholds.onSideRelaxationMaxSpeedKmh = 25.0`
(default). The on-side relaxation now refuses to engage when the IMPACT-entry
speed is ≥ 25 km/h (GPS-stale bypasses this). Empirically anchored on this
exact ride: all 4 real falls had IMPACT speeds < 25 km/h (8.9 / 6.7 / 5.8 /
15.0); the FP sat at 34.7 km/h — a clean 10 km/h gap.

**Regression test in code:** `CrashStateMachineTest.kt` →
`FP fix - onSideRelaxed does NOT fire when speed is at or above
onSideRelaxationMaxSpeedKmh` (the test already cites elapsed 14141.2 s ≈
235:69 min on this ride).

### Terrain (legit gate rejections, NOT crashes)

The following IMPACT_IN clusters are confirmed terrain and should remain
no-confirm under any future tuning:

- 8 × `IMPACT_IN` between @191:26 and @209:04 (descent series, no confirm)
- 3 × `IMPACT_IN` between @258:77 and @259:88 (post-incident, no confirm)

---

## 2026-05-28 evening — session `22d1d3` (v1.2.0)

- **App version:** v1.2.0 (pre-locale-fix — see the data-quality note below)
- **Profile / preset:** GRAVEL / MEDIUM
- **Device:** k24 (Karoo 2)
- **Log file:** `ksafe_v1.2.0_22d1d3_k24.csv` (44 KB, ~70 min — 19:58→21:08,
  Telegram download). Not in the repo (gitignored corpus).
- **Annotated by:** Sergi, 2026-05-29.
- **Ground-truth label:** **TRUE NEGATIVE** — uneventful gravel ride, no fall, no
  manual SOS. Everything the detector did here should stay no-confirm.

### Aggregate (from `analyze_calibration_logs.py`)

| Metric | Value |
|---|---|
| Duration | ~70.4 min |
| `HIGH_MAG` | 184 (2.61/min — gravel chatter) |
| `IMPACT_IN` | 12 (10.2/h) |
| `IMPACT_TMO` | 12 / 12 (100 %), **reason = SPEED** |
| `CRASH_CONFIRMED` | **0** |
| HIGH_MAG raw p50 / p95 / max | 25.5 / 40.1 / 49.0 m/s² |
| HIGH_MAG smooth p50 / p95 / max | 14.2 / 19.9 / 28.3 m/s² |
| IMPACT_TMO `min_spd` during window p50 | 24.6 km/h (rider still riding through the jolt) |

### Terrain (legit gate rejections, NOT crashes)

All 12 `IMPACT_IN` events are gravel jolts that entered IMPACT and timed out on
the speed gate (`reason = SPEED` — the rider never slowed; `min_spd` p50 24.6 km/h
through the window). 0 false positives on MEDIUM. This is a clean
**gravel-chatter true-negative**: any future tuning must keep all 12 no-confirm.

**Threshold headroom (MEDIUM).** The loudest 5 % of gravel spikes (raw p95 40.1 /
smooth p95 19.9) sit well under the v2.0.0 MEDIUM thresholds — ×1.25 raw and
×2.26 smooth headroom. Retro-projection: 0/184 HIGH_MAG would cross MEDIUM in
v2.0.0; the 12 `IMPACT_IN` would still enter IMPACT but be rejected by the same
speed gate. No regression, no FP.

### Data-quality note — locale corruption (validates the v2.x fix)

The raw CSV is **comma-decimal corrupted**: `elapsed_s=274,2`, `speed=0,0`,
`accel_dev=0,57` — this Karoo 2 runs a comma-decimal (es/fr/de-style) locale and
v1.2.0 formatted floats with the JVM default locale, so the decimal comma
collides with the CSV field separator and shifts every fractional row's columns.
This is the exact bug fixed in v2.x (`String.formatUs` / `Locale.US` across the
calibration writers, incl. `SpeedDropMonitor`); logs from this same device on
v2.x are clean. `analyze_calibration_logs.py` tolerates it by normalising commas,
but a future author replaying this file directly must account for the shift.

---

## 2026-05-29 — session `764b66` (v1.2.0)

- **App version:** v1.2.0 (pre-locale-fix — comma-decimal corrupted, same as `22d1d3`).
- **Profile / preset:** MTB / LOW.
- **Device:** k24 (Karoo 2).
- **Log file:** `ksafe_v1.2.0_764b66_k24.csv` (the `(2)` Telegram copy, 7.4 KB,
  ~24 min span, Telegram download). Likely a partial chunk (only 2 `PERIODIC`
  rows logged), but the impact burst is self-contained. Not in the repo.
- **Annotated by:** Sergi, 2026-05-29.
- **Ground-truth label:** **TRUE NEGATIVE** — stationary device handling
  (bike knocked / shaken / picked up while parked). No ride, no fall.

### Aggregate (from `analyze_calibration_logs.py`)

| Metric | Value |
|---|---|
| Duration | ~23.7 min (bike never moved — `speed=0` on every row) |
| `HIGH_MAG` | 3 |
| `SPD_REJECT` (`IMPACT_SPEED_REJECTED`) | ~64, all in a ~1.4 s burst @ ~23.7 min |
| `IMPACT_IN` | **0** |
| `CRASH_CONFIRMED` | **0** |
| Burst `raw` range | 25.9 → **126.9** m/s² (~13 g) |
| Burst `smooth` range | 51.0 → **117.3** m/s² |
| `gyro` peak | **15.2 rad/s** |
| `speed` throughout | 0 km/h (< `min_speed=3`) |

### Why it stayed no-confirm (speed gate, NOT magnitude)

The burst magnitudes **dwarf** every real fall in the `58ee00` ground-truth ride
(those peaked raw 78–94 / smooth 33–49 m/s²). A magnitude-only detector would
have fired hard. It did not, because the bike was stationary: `speed=0` failed
the speed gate (`minSpeedForCrashKmh`, the `IMPACT_SPEED_REJECTED` path in
`CrashDetectionManager.kt`), so the state machine never even entered IMPACT —
hence 0 `IMPACT_IN` despite ~64 supra-threshold samples.

This is the canonical **"I picked up / knocked my parked bike" true-negative**:
the speed gate is the sole and sufficient defence. Any future tuning that
weakens or removes the at-rest speed gate must keep this session at 0 confirms.

**v2.0.0 no regression.** The speed gate is unchanged in v2.0.0
(`CrashStateMachine.kt:514`, `crashConfirmSpeedKmh` / `minSpeedForCrashKmh`
still gate IMPACT entry and confirm). With `speed=0` the entire burst is
rejected identically regardless of preset — the retro-projection's magnitude
crossings (MEDIUM 1/3, HIGH 2/3 of the 3 `HIGH_MAG` rows) are moot because the
speed gate fires first.

---

## How these become tests

Each labeled event above is a candidate named test seed. The convention is:

- **FN events** → "after stale-cadence fix, this exact sample sequence must
  reach `Decision.Confirm`"
- **FP events** → "must NOT reach `Decision.Confirm`"
- **Terrain clusters** → batched aggregate replay, asserting no confirms

The aggregated test architecture (named per-event seeds vs replay sweep) is
discussed alongside the broader test strategy, not duplicated here.
