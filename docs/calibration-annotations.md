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

---

## Monday 2026-05-25 — session `58ee00`, install `1308ea`

- **App version:** v2.0.0
- **Profile / preset:** GRAVEL / LOW
- **Device:** k24 (Karoo 2)
- **Log file:** `calibration.csv` (262 KB, ~260 min, copied directly off device).
  The Telegram chunks `ksafe_v2.0.0_1308ea_58ee00_k24*.csv` cover only the first
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

## How these become tests

Each labeled event above is a candidate named test seed. The convention is:

- **FN events** → "after stale-cadence fix, this exact sample sequence must
  reach `Decision.Confirm`"
- **FP events** → "must NOT reach `Decision.Confirm`"
- **Terrain clusters** → batched aggregate replay, asserting no confirms

The aggregated test architecture (named per-event seeds vs replay sweep) is
discussed alongside the broader test strategy, not duplicated here.
