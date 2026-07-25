# Calibration Logging

> Detailed reference for the **"Send anonymous calibration data"** toggle in the Settings tab. The README has a short summary; this page is the full disclosure of what is collected, how it is sent, and why it helps.

Calibration logging is **disabled by default** and completely optional. Enabling it helps the developer improve and calibrate the crash detection algorithm over time, using real-world data from different riding styles and terrain types.

## What data is collected

When enabled, KSafe records detailed sensor events to a local CSV file:

| Data recorded | Examples |
|---|---|
| Accelerometer magnitude values | `raw=52.3 m/s²`, `smooth=49.1 m/s²` |
| Detection thresholds in use | `threshold=45 m/s²`, `peakThreshold=50 m/s²` |
| GPS speed at the moment of each event | `speed=28.4 km/h` |
| Crash detection state | `MONITORING`, `IMPACT`, `SILENCE_CHECK` |
| Sensitivity preset active | `preset=MEDIUM` |
| Gyroscope magnitude | `gyro=0.82 rad/s` |
| GPS stale flag | `gps_stale=false` |
| Elapsed ride time | `elapsed_s=1247.3` |
| Road grade (slope) | `grade=-5.2` (% descent) |
| Pedalling cadence | `cadence=82 RPM` |
| Heart rate *(when paired)* | `bpm=152`, `avg5min=149` |
| Terrain noise level | `noise=2.4 m/s²` (std-dev over 5 s) |
| Ride profile type | `profile=GRAVEL` (from Karoo profile) |
| **Anonymous session ID** | `session=a3f9c2` (random, per-session) |
| Device model | `device=Karoo-3` |
| App version | `app_version=1.5.3` |

## What is NOT collected

- ❌ GPS coordinates — no location data, no maps, no tracking
- ❌ Emergency messages or contact information
- ❌ Account data, email, phone number, or any personal identifier
- ❌ Anything that reveals who you are, where you ride, or when

**Session ID**: a random 6-character code (e.g. `a3f9c2`) generated fresh each time you enable logging. It contains no timestamp, no location, and no device fingerprint — it is a random discriminator so multiple logs sent to the developer can be told apart. Two sessions from the same device will have completely different IDs.

The data consists exclusively of raw sensor readings and algorithm states — the same numbers the crash detection algorithm reads internally. It is not possible to identify you, your location, your route, or your contacts from this data.

## How the data is sent

The CSV file is sent automatically to the developer via Telegram (a private bot) when you:

- **Disable** the calibration logging toggle, or
- **Finish a ride** (if logging was active during the ride)

You can also tap **Send now** to transmit the current log immediately. The file is typically 50–400 KB for a 2–4 hour ride session.

A long ride is uploaded in several **size-capped chunks** (and periodically during the
ride, so a flat battery or a crash mid-ride can't lose the data already recorded). Each
file arrives in the developer's Telegram with:

- A **descriptive filename** — e.g. `ksafe_v2.0.0_a3f9c2_b4e8d1_c000_Karoo-3.csv`. The
  parts are: app version, an opaque per-install grouping tag, the random session ID, an
  incrementing **chunk index** (`c000`, `c001`, …) and the device model. The chunk index
  keeps the pieces ordered and prevents the chat from collapsing them onto one repeated
  name. There is deliberately **no date, time or location** in the name (see "What is NOT
  collected") — Telegram's own per-message arrival time already orders them, with nothing
  about *when* you rode embedded in the file.
- A **caption** in Telegram — e.g. `📊 kSafe Calibration Log | Session: a3f9c2 | Karoo 3 | v2.0.0 | 1247 rows`

This makes it easy for the developer to identify and organise logs from multiple testers without any personal information.

## Why this helps

Crash detection thresholds (impact magnitudes, silence durations, speed gates) need to be tuned to real-world conditions across different riding disciplines — MTB, gravel, road, velodrome. Each discipline generates a different noise floor and a different impact distribution. The calibration data allows the developer to:

- Understand the terrain noise distribution at different speed/terrain combinations
- Identify conditions where the speed gate is too aggressive (misses real crashes)
- Identify conditions that produce false positives (terrain spikes that look like crashes)
- Tune the `SILENCE_CHECK` duration and deviation thresholds to real post-crash physics

This data is processed by the developer and never shared with third parties.

## Event catalogue

Every CSV row is tagged with a short event identifier. The current catalogue:

### Crash detection pipeline
| Tag | Meaning |
|---|---|
| `IMPACT_IN` / `IMPACT_TMO` | Entered IMPACT phase / IMPACT timed out without confirming |
| `SIL_IN` / `SIL_TMO` / `SIL_BRK` | Entered SILENCE_CHECK / timed out / broken by motion |
| `CRASH_OK` | Crash CONFIRMED — alert dispatched |
| `CRASH_NO` | Crash CANCELLED by rider during countdown — labelled false positive |
| `CRASH_GATE_SUPPRESSED` | Confirm landed inside the cooldown window of a previous confirm |
| `CAD_GATE` | Cadence-active exit from SILENCE_CHECK (rider still pedalling) |
| `CAD_GATE_SUPPRESSED` | Cadence-active gate WOULD have fired but was suppressed because the live orientation evidence shows the device decisively non-upright (angle ≥ uprightAngleThresholdDegrees). A bike on its side cannot be pedalled; the "fresh" cadence is therefore phantom/stale. Payload: `cadence`, `speed`, `deviation`, `grade`, `angle`, `upright_thr` |
| `GYRO_BLK` | IMPACT→SILENCE_CHECK blocked because gyro is still high |
| `RST_SNAP` | Post-reset snapshot — see `docs/crash-detection-algorithm.md` |
| `HIGH_MAG` | Sample crossed the peak threshold but didn't enter IMPACT |
| `SPD_REJECT` | Speed-gate rejection (sample below minSpeedForCrashKmh) |
| `TERRAIN_CLUST` | ≥3 IMPACT_TMOs within the rough-terrain window — cluster detected |
| `GPS_STALE` | Detected GPS-stale entry / exit |
| `POST_TMO_BOOST` | Peak-threshold boost active after recent IMPACT_TMO |
| `GAP_VETO` | An upright confirm was suppressed by the orientation veto. Payload: `angle`, `veto_thr`, `regime`, `gyro_peak`, `speed`, `deviation`, `cadence`, pre-impact and in-silence gravity vectors |
| `VIGIL_ARM` | An on-side confirm was read mid-motion (‖silence orientation‖ below the trust floor) and diverted into a 4 s speed-verification window instead of alerting. Payload: `sil_mag`, `trust_min`, `speed`, `window_ms`, `spd_age_ms`, `floor_kmh`, `fresh_thr_ms` |
| `VIGIL_CLEAR` | That window ended with the rider verifiably still riding — no alert was raised |
| `VIGIL_ESCALATE` | That window ended in doubt — speed collapsed or GPS went stale (`reason` absent), the ride was paused (`reason=manual_pause`), or the ride was stopped mid-verification (`reason=ride_stop`) — routed to the normal cancellable countdown |
| `VIGIL_SHADOW` | Diagnostic only, changes nothing: records what a candidate alternative rule *would* have decided at the end of the window, so a rule change can be judged on real data before being shipped. Payload: `would_be`, `floor_breach`, `speed`, `spd_age_ms`, `gps_stale` |

### Speed-drop watchdog (L1)
| Tag | Meaning |
|---|---|
| `SPDRP_EVAL` | Per-30 s evaluation while window is open (only emitted when timer is active) |
| `SPDRP_WSTART` | Zero-speed window opened. Payload: `trigger_speed_kmh`, `gps_stale`, `threshold_kmh` |
| `SPDRP_WCLOSE` | Zero-speed window closed. Payload: `reason` ∈ `{speed_recovered, paused, stopped, confirmed}`, `elapsed_ms`, `trigger_speed_kmh`, `max_speed_kmh`, `gps_stale`, `recovered_at_kmh` (only on `speed_recovered`) |

### Medical / wellness / fueling
| Tag | Meaning |
|---|---|
| `HR_FLAT` / `HR_COLLAPSE` | Medical detector fired |
| `MED_NO` | Medical countdown CANCELLED by rider |
| `INC_NO` | Any OTHER incident countdown CANCELLED by rider (wellness / check-in / SOS / speed-drop). Payload: `how_long_ms`, `subkind=<EmergencyReason.name>`. Lets post-incident analysis quantify false-positive rates across all six emergency reasons, not just CRASH/MEDICAL. |
| `WLNS_HR` | Wellness tier fired (critical / sustained / decoupling) |
| `WARN` / `SILENT` | Generic incident dispatched at WARNING / SILENT level |
| `INC_SUPP` | An incident arrived while another emergency was in progress and was dropped (audit trail for co-occurring detectors) |
| `HR_STALE` / `HR_PERIODIC` | HR signal staleness transition / 2-min periodic snapshot |
| `CARB_START` / `CARB_LOG` / `CARB_UNDO` / `CARB_DEFICIT` / `CARB_TIME` | Carbs tracker events |
| `HYD_START` / `HYD_LOG` / `HYD_UNDO` / `HYD_DEFICIT` / `HYD_TIME` | Hydration tracker events |

### Emergency dispatch
| Tag | Meaning |
|---|---|
| `EMERG_TRIG` | `triggerEmergency` fired (countdown started) |
| `ALERT_FAIL` | Outbound alert delivery FAILED across every retry cycle. Payload: `provider`, `reason`, `superseded` (true = this alert was overridden by a newer emergency; the rider-facing fallback notification was suppressed but the audit row is always logged) |
| `ALERT_PARTIAL` | Outbound alert reached at least one but NOT every eligible contact (e.g. 1 of 3 — a contact in a coverage gap or with an expired key). The amber rider-facing partial-delivery notice fires alongside. Payload: `provider`, `reason`, `reached`, `total`, `superseded`. Always logged (even when superseded) so a contact reporting they never got an alert is correlatable |
| `LOG_START` / `LOG_END` | CSV session boundaries |
