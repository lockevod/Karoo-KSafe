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

Each file arrives in the developer's Telegram with:

- A **descriptive filename** — e.g. `ksafe_v1.5.3_a3f9c2_Karoo-3.csv`
- A **caption** in Telegram — e.g. `📊 kSafe Calibration Log | Session: a3f9c2 | Karoo 3 | v1.5.3 | 1247 rows`

This makes it easy for the developer to identify and organise logs from multiple testers without any personal information.

## Why this helps

Crash detection thresholds (impact magnitudes, silence durations, speed gates) need to be tuned to real-world conditions across different riding disciplines — MTB, gravel, road, velodrome. Each discipline generates a different noise floor and a different impact distribution. The calibration data allows the developer to:

- Understand the terrain noise distribution at different speed/terrain combinations
- Identify conditions where the speed gate is too aggressive (misses real crashes)
- Identify conditions that produce false positives (terrain spikes that look like crashes)
- Tune the `SILENCE_CHECK` duration and deviation thresholds to real post-crash physics

This data is processed by the developer and never shared with third parties.
