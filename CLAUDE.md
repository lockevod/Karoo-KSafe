# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

KSafe is a free, open-source safety extension for Hammerhead Karoo cycling computers (Karoo 3, Karoo OS 1.527+). Single-module Android Gradle project (`:app`) written in Kotlin + Jetpack Compose. The APK is both a settings UI (`MainActivity`) and a Karoo extension service (`KSafeExtension`) registered via the Hammerhead Karoo SDK. There is no separate library module.

## Toolchain

- AGP 8.5.0, Kotlin 2.0.0, Compose compiler plugin, kotlinx.serialization plugin.
- JDK 17 (`sourceCompatibility`/`kotlinOptions.jvmTarget = 17`).
- `compileSdk = 34`, `targetSdk = 34`, `minSdk = 23`.
- Sources live under `app/src/main/kotlin/...` (note: `kotlin/`, not `java/`). Resources under `app/src/main/res/`.
- The Hammerhead Karoo SDK (`io.hammerhead:karoo-ext`) is pulled from a private Maven repo (`maven.pkg.github.com/hammerheadnav/karoo-ext`). `settings.gradle.kts` reads `gpr.user`/`gpr.key` from `local.properties` and **fails the build hard** (`error("File from not found")`) if `local.properties` is missing — even for a clean checkout. A valid `local.properties` with `sdk.dir`, `gpr.user`, `gpr.key` is therefore required to do anything with Gradle.
- `app/build.gradle.kts` also reads `calib.bot_token` and `calib.chat_id` from `local.properties` and exposes them as `BuildConfig` fields. Empty values are fine — `LogReporter` then no-ops the calibration log upload. `local.properties` is in `.gitignore`; never commit it.

## Common commands

```bash
# Build (debug + release APKs land in app/build/outputs/apk/...)
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease

# Lint / static analysis (Android Lint only — no detekt/ktlint/spotless configured)
./gradlew :app:lint

# Install onto a connected device or emulator
./gradlew :app:installDebug

# Clean
./gradlew clean
```

JVM unit tests live under `app/src/test/kotlin/...` and run via `./gradlew :app:testDebugUnitTest`. The suite uses JUnit 4 + `kotlinx-coroutines-test` + Mockito (testImplementation dependencies in `libs.versions.toml`). There is no `androidTest` instrumentation source set — tests must be JVM-runnable. To run a focused subset: `./gradlew :app:testDebugUnitTest --tests "com.enderthor.kSafe.extension.crash.*"`.

The release build is configured to sign with the **debug** signing config and runs R8 minification (`isMinifyEnabled = true`) — keep ProGuard rules in `app/proguard-rules.pro` in mind when adding reflection-based code.

## High-level architecture

The whole app revolves around the Karoo extension service. Internet, GPS, ride state, sensor streams, and HTTP all go through the `KarooSystemService` provided by `karoo-ext` — there are no direct OkHttp/Retrofit calls and no GPS code outside `LocationManager`.

### Service layer (`extension/`)

- **`KSafeExtension`** (`extension/KSafeExtension.kt`) — the central orchestrator. Subclasses `io.hammerhead.karooext.extension.KarooExtension`, exposes the `DataType`s, owns the `KarooSystemService`, instantiates every manager, and implements `onBonusAction` for hardware buttons. All long-running work runs on `Dispatchers.Main + SupervisorJob` belonging to this service. A static `getInstance()` lets `DataType` callbacks reach back into the service when they cannot be passed dependencies through Karoo's callback API.
- **`Sender`** (`extension/Sender.kt`) — sends messages via the four supported providers (CallMeBot/WhatsApp, Pushover, ntfy, Telegram). Three entry points: `sendAlert` (emergency, retries with 3 cycles × 3 attempts × 60/120/180s backoff), `sendInfo` (single attempt, used for ride start/end and custom messages), `testSend` (single attempt, returns a human-readable string for the Settings UI).
- **`Extensions.kt`** — `KarooSystemService` extension functions that wrap the SDK's callback API into `Flow`s (`streamRide`, `streamDataFlow`, `streamRideProfile`, `httpRequest`, …). Anywhere you need a Karoo data stream as a Flow, add it here rather than reimplementing the callback boilerplate.

### Managers (`extension/managers/`)

- **`ConfigurationManager`** — DataStore Preferences wrapper. Stores three JSON blobs under string keys (`ksafeconfig`, `sender`, `emergencystate`). Reads run through `migrateToLatest()` (see `data/ConfigData.kt` — `CONFIG_VERSION` is bumped when defaults change) and through inline string substitutions for renamed enums (e.g. `SIMPLEPUSH` → `NTFY`). Use `jsonWithUnknownKeys` for reads and `jsonForExport` for user-visible exports.
- **`CrashDetectionManager`** — Android `SensorManager` listener (accelerometer + gyroscope, `SENSOR_DELAY_GAME` ≈ 50 Hz). Implements the multi-stage state machine described in `docs/crash-detection-algorithm.md`: `MONITORING → IMPACT → SILENCE_CHECK → CRASH_CONFIRMED`, with a parallel speed-drop monitor and a dual smoothed/peak detector. Reads speed/cadence/grade/ride-profile from streams pushed in by `KSafeExtension`. **Do not change the sensor type to `TYPE_LINEAR_ACCELERATION`** — the stillness logic compares magnitude against gravity (~9.81 m/s²) and silently breaks otherwise.
- **`EmergencyManager`** — owns the cancellation countdown, check-in timer, beep patterns, the SOS overlay, and the actual outbound alert. Exposes `EmergencyManager.uiState: StateFlow<EmergencyState>` as the **canonical in-memory state** — `DataType` callbacks must read from this StateFlow, not from DataStore, because DataStore writes are async and racy with the ~1 Hz countdown ticks.
- **`LocationManager`** — keeps `lastLat`/`lastLng` from the SDK GPS stream. Used to fill `{location}` in alerts and to enforce the per-webhook geo-fence (`KSafeExtension.handleWebhookTap` calls `distanceMeters` against the saved target).
- **`WebhookManager`** — fires arbitrary HTTP requests through `KarooSystemService.httpRequest` so traffic uses the Karoo's tethered phone connection. Two slots, each with its own URL, method, headers, body, optional geo-fence and optional on-screen alert.
- **`CalibrationLogger` + `LogReporter`** — opt-in CSV recorder for crash-detection sensor data. `CalibrationLogger` buffers in memory and flushes to disk; `LogReporter` ships the file to a Telegram bot using the `BuildConfig.CALIB_BOT_TOKEN` / `CALIB_CHAT_ID` injected at build time. The log is automatically sent at ride end (or when the user turns logging off), routed through `Dispatchers.IO` to avoid blocking Main.
- **`SosOverlayManager`** — draws the full-screen red Cancel overlay using `SYSTEM_ALERT_WINDOW`. Required permission flow is handled in `MainActivity.requestOverlayPermissionIfNeeded()`.

### Data field layer (`datatype/`)

Each `DataType` implements a Karoo data field:

- `SOSDataType`, `SafetyTimerDataType`, `CustomMessageDataType` (3 instances, slots 1–3), `WebhookDataType` (2 instances, slots 1–2).
- All seven instances are listed in `KSafeExtension.types` and declared in `app/src/main/res/xml/extension_info.xml`. **The `typeId` strings in code and XML must match exactly** — `extension_info.xml` is also where the four `BonusAction` entries are registered.
- Field taps go through `FieldTapReceiver` (a `BroadcastReceiver`) using one distinct intent action per slot. The unique action names are required so Android creates genuinely different `PendingIntent`s and no Activity launches during a ride. Per-slot state for animation/colour transitions lives in object singletons like `WebhookState` and `CustomMessageState`, observed by the data fields.

### UI layer (`screens/` + `activity/`)

- `MainActivity` hosts `TabLayout` (Compose) with six tabs in order: **Safety**, **Health**, **Fueling**, **Actions**, **Provider**, **Settings** (`SafetyScreen`, `HealthScreen`, `FuelingScreen`, `ActionsScreen` / `WebhookScreen`, `ProviderScreen`, `SettingsScreen`). The DataStore is exposed once via `Context.dataStore by preferencesDataStore("settings")` in `activity/MainViewModel.kt` and reused everywhere — do not create another DataStore on the same `Context`.
- `CancelEmergencyActivity` is a transparent `Theme.NoDisplay` activity used as a fallback Cancel route (e.g. for future deep links). It immediately calls `KSafeExtension.getInstance()?.cancelEmergency()` and finishes.

### Distribution / OTA

`AndroidManifest.xml` contains:
```xml
<meta-data android:name="io.hammerhead.karooext.MANIFEST_URL"
    android:value="https://github.com/lockevod/Karoo-KSafe/releases/latest/download/manifest.json" />
```
The Hammerhead Companion app uses this URL to discover updates. When bumping `versionCode`/`versionName` in `app/build.gradle.kts`, the matching `manifest.json` published on GitHub Releases is what actually drives in-the-field updates.

## Things to know before changing code

- **`activeConfig` is the source of truth for the live service.** It is updated by the flow in `KSafeExtension.initializeSystem()`. Never read config directly from DataStore inside hot paths (sensor callbacks, countdown ticks); read from `activeConfig` or `EmergencyManager.uiState`.
- **Enum/field renames are migrations.** When renaming a `ProviderType` value, an enum string in any saved JSON, or a serialized field, follow the existing pattern in `ConfigurationManager.loadConfigFlow()` (raw-string substitution) **and** bump `CONFIG_VERSION` and add a branch to `migrateToLatest()` in `data/ConfigData.kt`.
- **`extension_info.xml` is part of the public Karoo API surface.** Adding/removing a `DataType` or `BonusAction` requires changes here, in `KSafeExtension.types` and (for BonusActions) in `KSafeExtension.onBonusAction`. Tap-driven fields also need a corresponding intent-filter action on `FieldTapReceiver` in `AndroidManifest.xml`.
- **The crash-detection algorithm is documented separately** in `docs/crash-detection-algorithm.md`. Read it before tuning thresholds, the impact window, the silence check, the GPS-stale fallback, or the speed-drop monitor — the threshold tables in `CrashDetectionManager` and the doc must stay in sync.
- **Logging uses Timber.** `KSafeApplication` plants a `Timber.DebugTree`. Use `Timber.d/w/e`; do not use `android.util.Log` directly.