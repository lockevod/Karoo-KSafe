package com.enderthor.kSafe.extension.managers

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.enderthor.kSafe.BuildConfig
import com.enderthor.kSafe.data.CrashSensitivity
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.data.SPEED_THRESHOLD_KMH
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Detects crashes using Android's SensorManager (accelerometer + gyroscope).
 *
 * Algorithm:
 *  1. MONITORING    — watching for large acceleration spike
 *  2. IMPACT        — spike detected, wait for device to settle
 *  3. SILENCE_CHECK — device must remain still (≈ gravity) for SILENCE_DURATION_MS
 *  4. CRASH_CONFIRMED → onCrashDetected()
 *
 * The `wasMoving` check is intentionally NOT required — a crash can happen even
 * at low speed or when GPS/speed data isn't streaming yet.
 */
class CrashDetectionManager(
    context: Context,
    private val scope: CoroutineScope,
    private val onCrashDetected: () -> Unit,
    private val calibLogger: CalibrationLogger? = null,
    private val clock: Clock = SystemClock,
) : SensorEventListener {

    // ─── Thresholds (m/s²) ────────────────────────────────────────────────────
    //
    // Reference (literature):
    //  - Normal riding bumps / hard braking: ~1.5g (14.7 m/s²) — NOT a crash
    //  - MTB jump landing: 3–5g but typically followed by continued movement (jump crash is detectable)
    //  - Real crash: 4–7g followed by stillness (Garmin's approach: "large G + no movement")
    //  - Source: IEEE Accident Detection, probabilistic crash classification studies
    //
    // These thresholds are tuned for a handlebar-mounted device (more exposed than a phone).
    // The SILENCE_CHECK is the primary differentiator between a jump and a real crash.

    /** Impact magnitude thresholds for preset levels: total acceleration vector (gravity ~9.8 included) */
    private val impactThresholds = mapOf(
        CrashSensitivity.LOW    to 55f,  // ~5.5g — hard impacts; MTB/gravel friendly
        CrashSensitivity.MEDIUM to 45f,  // ~4.5g — balanced road + MTB
        CrashSensitivity.HIGH   to 35f   // ~3.5g — road bike, more sensitive
        // CUSTOM → reads config.customCrashThreshold at runtime
    )

    /**
     * Single-sample peak thresholds — a parallel detector that fires on a single raw sample
     * exceeding this bar, without requiring the 3-sample sliding average.
     *
     * Rationale: sharp, rigid impacts (handlebar hitting asphalt, direct obstacle collision)
     * can produce a real crash peak lasting only 10–20ms (1 sample at 50 Hz). The sliding-window
     * average would reduce a single spike to roughly 1/3 its true value, causing a silent false
     * negative if we only relied on the smoothed threshold. The peak detector captures these
     * short-duration events independently.
     *
     * Calibration (from real ride logs):
     *  - LOW (MTB/gravel): normal riding tops out at ~67–69 m/s² raw in rough terrain.
     *    pthr=60 activates on 2–3 events/session (64–69 m/s²) that all end in IMPACT_TMO
     *    (rider still moving). Captures rigid crashes at 60–69 m/s² that the smooth avg misses.
     *  - MEDIUM (road+gravel): normal riding peaks at ~46 m/s². pthr=50 has zero overlap
     *    with the observed normal-riding distribution — very safe.
     *  - HIGH (road): pthr=40 follows the same thr+5 principle; road riding rarely produces
     *    isolated smooth spikes above 35 m/s².
     *
     * All presets use pthr = smooth_thr + 5, closing the previous 15 m/s² blind-spot window
     * where a single-frame crash spike between thr and old_pthr would be invisible to both detectors.
     */
    private val peakImpactThresholds = mapOf(
        CrashSensitivity.LOW    to 60f,  // thr+5: was 70; real logs show safe at 60 (2-3 IMPACT_TMO/session)
        CrashSensitivity.MEDIUM to 50f,  // thr+5: was 60; MEDIUM riding noise tops at ~46 m/s²
        CrashSensitivity.HIGH   to 40f   // thr+5: was 50; road riding rarely spikes above 35 m/s²
        // CUSTOM → 1.3× customCrashThreshold, capped at 80 m/s²
    )

    /**
     * Impact window per sensitivity level: max time from impact to confirm crash.
     * MTB/LOW needs more time — bike can slide or tumble down a slope before settling.
     * Road/HIGH crashes are cleaner and settle faster.
     */
    private val impactWindowMs = mapOf(
        CrashSensitivity.LOW    to 25_000L,  // MTB: bike may keep rolling after a hard crash
        CrashSensitivity.MEDIUM to 20_000L,  // gravel/mixed
        CrashSensitivity.HIGH   to 15_000L,  // road bike: crashes settle quickly
        CrashSensitivity.CUSTOM to 20_000L   // reasonable default for custom
    )

    private val GRAVITY = 9.81f

    // Stillness check: how close to gravity (~9.8 m/s²) the accel must stay
    private val SILENCE_DEVIATION_MAX = 4.0f   // m/s² — tolerant of slight movements while lying down

    // Gyroscope threshold (rad/s) — used ONLY in the IMPACT→SILENCE_CHECK gate.
    // NOTE: GYRO_STILL_MAX is intentionally removed: requiring the gyro to be still
    // during SILENCE_CHECK caused false negatives when the bike kept sliding after a crash
    // while the rider was already on the ground.  GPS speed is the definitive gate there.
    private val GYRO_MOVING_MAX  = 2.0f   // rad/s — device is clearly still moving/riding (≈ 115°/s)

    /**
     * Maximum GPS speed (km/h) to consider the rider as truly "stopped" during crash confirmation.
     * Configurable via [KSafeConfig.crashConfirmSpeedKmh] (default 5 km/h).
     * Higher values (e.g. 8) cover post-crash sliding on slopes; lower values (3) are stricter.
     */
    private val speedCrashConfirmKmh get() = config.crashConfirmSpeedKmh.toDouble()

    private val SILENCE_DURATION_MS =  4_500L  // must be continuously still for 4.5s (literature uses 5s)
    private val LOG_INTERVAL_MS     =  2_000L  // log magnitude every 2s for debugging

    // ─── Post-IMPACT_TMO dynamic peak-threshold boost ─────────────────────────
    //
    // Problem observed in v3 calibration logs: rough terrain (cobblestones, gravel,
    // badly paved descents) produces clusters of 3–5 consecutive IMPACT_IN/IMPACT_TMO
    // events within 60–120 seconds, all from source=PEAK with raw=50–64 m/s².
    // The smooth average never exceeds the threshold (terrain spikes are single-frame),
    // and the speed never drops (rider is still moving at 25–45 km/h), so all resolve
    // as IMPACT_TMO — but they create repeated false alarms and wear on the user.
    //
    // Solution: after each IMPACT_TMO, temporarily raise the effective peak threshold
    // by POST_TMO_BOOST for POST_TMO_COOLDOWN_MS.  If a rough-terrain CLUSTER is
    // detected (≥ CLUSTER_MIN_TMO timeouts within CLUSTER_WINDOW_MS), the boost
    // period is extended to POST_CLUSTER_COOLDOWN_MS.
    //
    // Safety: the smooth threshold (smoothedMagnitude > threshold) is NEVER boosted.
    // Only the single-frame peak path is elevated.  A real crash at speed still
    // produces smooth > 45 m/s² and will enter IMPACT regardless of the boost.
    // The IMPACT_ENTER log records boost_active=true so we can verify no real crash
    // was missed during a boosted window.

    /** Extra m/s² added to the peak threshold during cooldown following an IMPACT_TMO. */
    private val POST_TMO_BOOST         = 8f
    /** Duration of the per-event cooldown after a single IMPACT_TMO (ms). */
    private val POST_TMO_COOLDOWN_MS   = 30_000L
    /** Duration of the extended cooldown when a terrain cluster is detected (ms). */
    private val POST_CLUSTER_COOLDOWN_MS = 60_000L
    /** Rolling window for clustering consecutive IMPACT_TMO events (ms). */
    private val CLUSTER_WINDOW_MS      = 120_000L
    /** Number of IMPACT_TMO events within CLUSTER_WINDOW_MS to declare a cluster. */
    private val CLUSTER_MIN_TMO        = 3

    /** Timestamp until which the peak threshold boost is active (epoch ms). 0 = inactive. */
    @Volatile private var postImpactBoostUntil = 0L
    /** Recent IMPACT_TMO timestamps used for cluster detection (sensor thread only). */
    private val recentTmoTimestamps = ArrayDeque<Long>(8)

    /**
     * Post-crash cooldown: ignore new IMPACT triggers after a confirmed crash.
     * Must be strictly GREATER than the cancel-countdown duration to avoid a re-trigger
     * immediately after the user cancels.  Formula: countdown + 30s safety margin.
     * E.g. default 30s countdown → 60s cooldown.
     */
    private val crashCooldownMs get() = (config.countdownSeconds * 1_000L) + 30_000L

    /**
     * How long without a GPS speed update before the speed reading is considered stale.
     * Mid-ride GPS loss (tunnel, dense forest) can leave [currentSpeedKmh] frozen at the
     * last known value — either falsely blocking crash confirmation (last value was high)
     * or — less commonly — falsely helping it (last value was already 0).
     * When stale, [isSpeedDropConfirmed] returns true so that the accelerometer alone
     * can confirm the crash rather than the detection being blocked indefinitely.
     *
     * IMPORTANT: when GPS is stale, the accel-only confirmation is HARDENED — see
     * [GPS_STALE_DEVIATION_MAX] and [GPS_STALE_SILENCE_DURATION_MS] below — to compensate
     * for the loss of the GPS gate as a discriminator.
     */
    private val GPS_STALE_MS = 10_000L

    /**
     * Stricter accel deviation threshold used in SILENCE_CHECK when GPS is stale.
     * Without GPS, the accelerometer is the only discriminator between "rider on the
     * ground" and "rider coasting passively downhill with accel oscillating near gravity".
     * The normal threshold (4.0 m/s²) is too permissive in that scenario.
     * 1.5 m/s² approximates a device truly motionless on the ground.
     */
    private val GPS_STALE_DEVIATION_MAX = 1.5f

    /**
     * Extended SILENCE_CHECK duration when GPS is stale: 8s instead of 4.5s.
     * Reduces the chance that a transient accel-quiet window between bumps (e.g. coasting
     * down a forest descent without GPS) is misread as the rider lying still.
     */
    private val GPS_STALE_SILENCE_DURATION_MS = 8_000L

    // ─── Impact window progress tracking ─────────────────────────────────────
    //
    // Tracked from IMPACT_ENTER to IMPACT_TMO/SILENCE_ENTER.
    // Provides richer IMPACT_TMO log rows for post-ride calibration analysis:
    //   max_smooth_in_win: peak smoothed magnitude seen during the window
    //   min_spd_in_win:    minimum GPS speed during the window (did it ever drop?)
    //   min_dev_in_win:    minimum accel deviation from gravity (did it ever settle?)
    //   gyro_blocked_cnt:  how many times gyro blocked SILENCE_CHECK entry
    //   speed_reached:     did the speed gate ever pass (isSpeedDropConfirmed)?
    // Together these explain WHY a given impact timed out: SPEED / ACCEL / GYRO / UNKNOWN.

    @Volatile private var maxSmoothedInWindow   = 0f
    @Volatile private var minSpeedInWindow      = Double.MAX_VALUE
    @Volatile private var minDeviationInWindow  = Float.MAX_VALUE
    @Volatile private var gyroBlockedCnt        = 0
    @Volatile private var speedReachedInWindow  = false

    /**
     * Speed-drop monitor: minimum time the accelerometer must have been continuously near
     * gravity before the speed-drop alert can fire. Prevents a single lucky snapshot at
     * the 30s polling boundary from triggering a false positive while the rider is actively
     * handling the bike (puncture repair, phone call, mechanical adjustment).
     * 60s of stable stillness is conservative enough that a real medical episode (rider
     * collapsed, motionless) clears it easily, while normal stops do not.
     */
    private val SPEED_DROP_ACCEL_STILL_MS = 60_000L

    /**
     * Short sliding-window average for the MONITORING phase.
     * A real impact is sustained energy across several sensor frames; a terrain-edge spike
     * (dirt→asphalt, cobblestone, speed bump) is typically 1-2 raw samples.
     * Window of 3 samples at SENSOR_DELAY_GAME (~50 Hz) = ~60ms — enough to smooth noise
     * without delaying real crash detection.
     */
    private val IMPACT_FILTER_WINDOW = 3
    private val magnitudeBuffer = ArrayDeque<Float>(IMPACT_FILTER_WINDOW)

    /**
     * Rolling window of raw acceleration magnitudes used to compute the terrain-noise metric
     * [accelStdDev].  250 samples at SENSOR_DELAY_GAME (~50 Hz) ≈ 5 seconds of signal.
     *
     * This is NOT redundant with the 3-sample [magnitudeBuffer]:
     *  - [magnitudeBuffer] smooths instantaneous spikes for the impact detector
     *  - [varianceBuffer] captures the statistical spread of magnitudes over 5 s to quantify
     *    how rough the terrain currently is (σ ≈ 1–3 m/s² on smooth asphalt vs 8–12+ on cobblestones)
     *
     * Updated on every accelerometer event (O(1) amortized ArrayDeque operations).
     * Std-dev is computed only on demand (PERIODIC log / IMPACT_ENTER), so it never runs
     * on the hot sensor path.
     */
    private val VARIANCE_WINDOW = 250  // ~5 s at 50 Hz
    private val varianceBuffer = ArrayDeque<Float>(VARIANCE_WINDOW)

    // ─── Cached hot-path thresholds ───────────────────────────────────────────
    //
    // Threshold and impact-window values depend only on config, which changes rarely
    // (user interaction only). Re-computing them from a HashMap + conditional on every
    // 50 Hz sensor event wastes CPU.  These are updated once in start() / updateConfig()
    // and read as cheap field reads on the hot path.
    @Volatile private var cachedThreshold     = 45f
    @Volatile private var cachedPeakThreshold = 60f
    @Volatile private var cachedWindowMs      = 20_000L

    // ─── State ────────────────────────────────────────────────────────────────
    //
    // All fields below are read/written from multiple threads:
    //   - Sensor thread (onSensorChanged)
    //   - Karoo SDK thread (updateSpeed, resetSpeedDropOnPause)
    //   - Coroutine on `scope` (speed-drop monitor)
    //   - Main thread (start, stop, updateConfig)
    //
    // @Volatile is required for cross-thread visibility. JVM does NOT guarantee that
    // Long/Double reads/writes are atomic without volatile, and even Int/Boolean reads
    // can be served from a stale CPU cache indefinitely. This is cheap insurance against
    // hard-to-reproduce Heisenbugs.

    private enum class CrashState { MONITORING, IMPACT, SILENCE_CHECK }

    @Volatile private var state = CrashState.MONITORING
    @Volatile private var impactTime  = 0L
    @Volatile private var silenceStartTime = 0L
    @Volatile private var lastCrashTime = 0L
    @Volatile private var currentSpeedKmh = 0.0
    @Volatile private var config = KSafeConfig()
    @Volatile private var lastLogTime = 0L

    /**
     * Cold-start guard: if no speed data has arrived yet AND we are within the guard window,
     * the speed-drop condition is treated as NOT met — preventing a false crash confirmation
     * caused by [currentSpeedKmh] being 0.0 (its uninitialized default value).
     *
     * The guard expires automatically after [COLD_START_GUARD_MS] so that devices without a
     * speed source (no GPS lock, no ANT+ sensor) fall back to normal behavior without
     * permanently blocking crash detection.
     *
     * [speedDataReceived] is only reset when [start] is called (NOT on [resetState]) so that a
     * false-alarm reset during normal riding never re-introduces the cold-start window.
     */
    private val COLD_START_GUARD_MS = 8_000L
    @Volatile private var speedDataReceived = false
    @Volatile private var startTime = 0L

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope     = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    @Volatile private var lastGyroMag   = 0f
    @Volatile private var lastAccelDeviation = 0f  // |accel - gravity|, updated on every sensor event

    // ─── Contextual sensor data ───────────────────────────────────────────────
    //
    // These supplement the crash pipeline with environmental context.
    // They are optional: if the sensor/data-source is absent the guarded fields stay at
    // their safe defaults and the algorithm behaves exactly as before.

    /**
     * Current pedalling cadence in RPM (from Karoo SDK ANT+/BLE cadence sensor).
     *
     * Used in SILENCE_CHECK: if the rider is actively pedalling (cadence > 20 RPM) it is
     * definitively NOT a crash — an unconscious rider cannot pedal. This is the highest-confidence
     * false-positive filter available without extra infrastructure.
     *
     * Thread: Karoo SDK callback → [updateCadence] (Volatile read/write only).
     */
    @Volatile private var currentCadence = 0.0
    /** True once at least one cadence data point has been received (sensor/provider present). */
    @Volatile private var cadenceDataReceived = false

    /**
     * Current road grade in percent (from Karoo SDK barometric elevation grade stream).
     * Negative = downhill, positive = uphill. E.g. -8.0 = 8 % descent.
     *
     * Used to apply a proactive grade-aware boost to the single-frame peak threshold:
     * on descents the road vibration noise floor is elevated so we pre-emptively raise
     * the peak bar rather than waiting for a TERRAIN_CLUSTER to trigger the reactive boost.
     * The smooth threshold is intentionally NOT affected — a real crash on a descent
     * still triggers via the smooth path.
     *
     * Thread: Karoo SDK callback → [updateGrade] (Volatile read/write only).
     */
    @Volatile private var currentGrade = 0.0

    /**
     * Routing preference of the currently active Karoo ride profile.
     * One of: "ROAD", "GRAVEL", "MTB" (matches [RideProfile.routingPreference]).
     * Defaults to "ROAD" until the first [updateRideProfile] call.
     *
     * Logged in PERIODIC and IMPACT_ENTER rows so post-ride analysis can correlate
     * false-positive rates with the type of riding the user was doing.
     * Not used as a gate or threshold modifier — use the reactive TERRAIN_CLUSTER boost
     * and grade-aware boost for real-time adaptation instead.
     *
     * Thread: Karoo SDK callback → [updateRideProfile] (Volatile read/write only).
     */
    @Volatile private var currentRoutingPreference = "ROAD"

    /**
     * Instantaneous deceleration in km/h per second — computed in [updateSpeed] from consecutive
     * speed readings. Negative = braking / crash deceleration. Positive = accelerating.
     *
     * Not used as a gate (GPS speed updates are too infrequent at ~1 Hz for reliable decel
     * estimation), but logged at every IMPACT_ENTER for post-ride calibration analysis.
     * Large negative values (< −10 km/h/s) at impact confirm a genuine speed event.
     */
    @Volatile private var lastDecelerationKmhPerS = 0.0

    /**
     * Timestamp (ms) since which the accelerometer has been continuously below
     * [SILENCE_DEVIATION_MAX]. Reset to 0 whenever a sample exceeds the threshold.
     * Used by the speed-drop monitor to require STABLE stillness, not a single lucky
     * snapshot at the polling boundary. See [SPEED_DROP_ACCEL_STILL_MS].
     */
    @Volatile private var accelStillSinceMs = 0L

    private val speedDropMonitor = SpeedDropMonitor(
        scope = scope,
        clock = clock,
        accelStillSinceProvider = { accelStillSinceMs },
        cooldownGate = { (clock.nowMs() - lastCrashTime) > crashCooldownMs },
        onConfirm = { confirmCrash(CrashSource.SPEED_DROP) },
        calibLogger = calibLogger,
    )
    @Volatile private var speedLastUpdatedTime = 0L  // timestamp of last updateSpeed() call — stale GPS detection

    // ── Calibration rate-limit timestamps (sensor thread) ────────────────────
    /** Last time a HIGH_MAG_NORISING event was logged — rate-limited to 1/s. */
    @Volatile private var lastHighMagLogMs = 0L
    /** Last time a SILENCE_BROKEN event was logged — rate-limited to 1/2s. */
    @Volatile private var lastSilenceBrokenMs = 0L
    /** Last time a GYRO_BLOCKED event was logged — rate-limited to 1/s. */
    @Volatile private var lastGyroBlockedLogMs = 0L
    /** Last time a PERIODIC snapshot was logged — once per 5 min. */
    @Volatile private var lastPeriodicLogMs = 0L
    private val PERIODIC_LOG_INTERVAL_MS = 120_000L  // 2 minutes — finer timeline resolution
    /** Tracks the previous GPS-stale state to log only when the condition changes. */
    @Volatile private var lastGpsStaleState = false
    /** Tracks the last sensitivity preset to detect mid-ride config changes. */
    @Volatile private var lastLoggedSensitivity = config.crashSensitivity

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Recomputes and caches the threshold constants derived from [cfg].
     * Must be called whenever [config] changes (start / updateConfig) so the hot sensor
     * path can read plain field values instead of doing HashMap lookups every 20 ms.
     */
    private fun updateCachedThresholds(cfg: KSafeConfig) {
        cachedThreshold = if (cfg.crashSensitivity == CrashSensitivity.CUSTOM)
            cfg.customCrashThreshold.toFloat().coerceIn(20f, 70f)
        else
            impactThresholds[cfg.crashSensitivity] ?: 45f
        cachedPeakThreshold = if (cfg.crashSensitivity == CrashSensitivity.CUSTOM)
            (cfg.customCrashThreshold.toFloat() * 1.3f).coerceIn(25f, 80f)
        else
            peakImpactThresholds[cfg.crashSensitivity] ?: 60f
        cachedWindowMs = impactWindowMs[cfg.crashSensitivity] ?: 20_000L
    }

    fun start(config: KSafeConfig) {
        this.config = config
        if (!config.crashDetectionEnabled) {
            Timber.d("CrashDetection disabled in config, skipping start")
            return
        }
        updateCachedThresholds(config)
        resetState()
        speedDataReceived = false
        startTime = clock.nowMs()
        // Reset periodic log timer so the first sensor sample fires an immediate config snapshot.
        // This captures preset, thresholds and initial state at the start of each session.
        lastPeriodicLogMs = 0L
        lastGpsStaleState = false
        lastLoggedSensitivity = config.crashSensitivity

        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            ?: Timber.w("No accelerometer found on this device!")
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }

        Timber.d("CrashDetectionManager STARTED (sensitivity=${config.crashSensitivity}, threshold=${cachedThreshold}m/s²)")

        if (config.speedDropDetectionEnabled) speedDropMonitor.start(config.speedDropMinutes)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        speedDropMonitor.stop()
        resetState()
        Timber.d("CrashDetectionManager STOPPED")
    }

    fun updateConfig(config: KSafeConfig) {
        val wasEnabled = this.config.crashDetectionEnabled
        val oldSensitivity = this.config.crashSensitivity
        val oldCustomThr = this.config.customCrashThreshold
        this.config = config
        updateCachedThresholds(config)
        // Log config change mid-ride so the CSV captures the exact moment thresholds shifted.
        // This is critical for calibration: events before and after must be analysed with
        // the thresholds that were active at the time.
        if (calibLogger != null && calibLogger.isEnabled &&
            (config.crashSensitivity != oldSensitivity || config.customCrashThreshold != oldCustomThr)) {
            val newThr = if (config.crashSensitivity == CrashSensitivity.CUSTOM)
                config.customCrashThreshold.toFloat().coerceIn(20f, 70f)
            else
                impactThresholds[config.crashSensitivity] ?: 45f
            calibLogger.log(CalibrationLogger.Event.PERIODIC) {
                // Re-use PERIODIC tag but with a "config_change=true" marker so it's easy to filter
                "config_change=true,old_preset=$oldSensitivity,new_preset=${config.crashSensitivity},new_thr=$newThr,min_spd=${config.minSpeedForCrashKmh}"
            }
            lastLoggedSensitivity = config.crashSensitivity
        }
        // If crash detection was toggled, restart listener
        if (wasEnabled && !config.crashDetectionEnabled) stop()
        else if (!wasEnabled && config.crashDetectionEnabled) start(config)
    }

    fun updateSpeed(speedKmh: Double) {
        val now = clock.nowMs()
        // Deceleration tracking: negative = braking/crash. Computed before we overwrite
        // currentSpeedKmh so the delta is always (new − old). Only valid when a previous
        // reading exists AND at least 200 ms have elapsed (avoids noisy near-zero Δt division).
        val elapsed = if (speedLastUpdatedTime > 0) (now - speedLastUpdatedTime) / 1000.0 else 0.0
        if (elapsed >= 0.2 && speedDataReceived) {
            lastDecelerationKmhPerS = (speedKmh - currentSpeedKmh) / elapsed
        }

        if (!speedDataReceived) {
            speedDataReceived = true
            Timber.d("Cold-start guard lifted: first speed data received (%.1f km/h)", speedKmh)
        }
        currentSpeedKmh = speedKmh
        speedLastUpdatedTime = now

        speedDropMonitor.onSpeedUpdate(speedKmh)
    }

    /**
     * Update the current pedalling cadence (RPM) from the Karoo SDK cadence stream.
     * Call this whenever a new cadence data point arrives. The first call marks
     * [cadenceDataReceived] = true, lifting the "no sensor present" guard.
     */
    fun updateCadence(cadenceRpm: Double) {
        if (!cadenceDataReceived) {
            cadenceDataReceived = true
            Timber.d("Cadence sensor online: first reading %.0f RPM", cadenceRpm)
        }
        currentCadence = cadenceRpm
    }

    /**
     * Update the current road grade (%) from the Karoo SDK elevation-grade stream.
     * Negative = downhill, positive = uphill.
     */
    fun updateGrade(gradePercent: Double) {
        currentGrade = gradePercent
    }

    /**
     * Update the active Karoo ride profile routing preference.
     * Call this whenever [streamRideProfile] emits.
     *
     * [routingPreference]: one of "ROAD", "GRAVEL", "MTB" from [RideProfile.routingPreference].
     */
    fun updateRideProfile(routingPreference: String) {
        currentRoutingPreference = routingPreference
        Timber.d("Ride profile routing preference updated: $routingPreference")
    }

    /**
     * Resets the speed-drop accumulator when the ride is paused.
     * While stopped at a café the speed is 0, which would otherwise trigger speed-drop
     * detection after the configured window — even though the rider intentionally paused.
     */
    fun resetSpeedDropOnPause() {
        speedDropMonitor.onPause()
        Timber.d("Speed-drop timer reset on ride pause")
    }

    // ─── Speed drop confirmation ──────────────────────────────────────────────

    /**
     * True if the speed reading hasn't been refreshed for longer than [GPS_STALE_MS].
     * Accepts [now] so callers that already hold `clock.nowMs()` avoid a
     * redundant JNI call.
     */
    private fun isGpsStale(now: Long = clock.nowMs()): Boolean =
        speedLastUpdatedTime > 0 &&
                (now - speedLastUpdatedTime) > GPS_STALE_MS

    /**
     * Returns true if the rider's GPS speed is low enough to confirm a crash.
     *
     * Accepts [now] so callers that already hold `clock.nowMs()` avoid extra
     * JNI calls.  All decision logic is pure field reads + arithmetic — safe to call on
     * the sensor thread at 50 Hz (IMPACT state only).
     *
     * Cold-start guard: blocks confirmation for [COLD_START_GUARD_MS] if no speed data has
     * been received yet — prevents false alarms caused by [currentSpeedKmh] defaulting to 0.0.
     * The guard is transparent once data arrives (even 1 sample lifts it immediately) or once
     * the timeout expires (fallback for devices with no speed source).
     *
     * Stale GPS: when GPS has not updated in [GPS_STALE_MS], this returns true so the
     * accelerometer alone can confirm the crash. The caller is responsible for hardening
     * the accel threshold accordingly (see SILENCE_CHECK).
     */
    private fun isSpeedDropConfirmed(now: Long = clock.nowMs()): Boolean {
        // Guard: no data yet AND within the cold-start window → not safe to confirm
        if (!speedDataReceived && (now - startTime) < COLD_START_GUARD_MS) return false
        // Stale GPS: let the accel decide — SILENCE_CHECK uses GPS_STALE_DEVIATION_MAX / GPS_STALE_SILENCE_DURATION_MS
        // to compensate. The GPS-stale transition is already logged once via the gpsCurrentlyStale
        // transition detector in processAccelerometer — no need to log here on every sensor event.
        if (isGpsStale(now)) return true
        // crashConfirmSpeedKmh == 0 → user explicitly disabled the confirmation speed gate
        return config.crashConfirmSpeedKmh == 0 || currentSpeedKmh < speedCrashConfirmKmh
    }

    // ─── SensorEventListener ─────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> processAccelerometer(event)
            Sensor.TYPE_GYROSCOPE -> {
                val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
                lastGyroMag = sqrt(x*x + y*y + z*z)   // Float overload — no Double conversion
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ─── Core algorithm ───────────────────────────────────────────────────────

    private fun processAccelerometer(event: SensorEvent) {
        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
        val magnitude = sqrt(x*x + y*y + z*z)   // Float overload — no Double conversion

        // Always keep a current snapshot of deviation from gravity (used by speed-drop monitor)
        lastAccelDeviation = abs(magnitude - GRAVITY)

        // Maintain "stable stillness" accumulator for the speed-drop monitor.
        // Reset whenever the device moves; start the clock when stillness begins.
        // The monitor checks (now - accelStillSinceMs) >= SPEED_DROP_ACCEL_STILL_MS before firing.
        if (lastAccelDeviation > SILENCE_DEVIATION_MAX) {
            accelStillSinceMs = 0L
        } else if (accelStillSinceMs == 0L) {
            accelStillSinceMs = clock.nowMs()
        }

        // Sliding-window average (3 samples ≈ 60ms at SENSOR_DELAY_GAME).
        // Filters single-sample terrain-edge spikes (dirt→asphalt, cobblestones)
        // while preserving sustained crash impacts.
        magnitudeBuffer.addLast(magnitude)
        if (magnitudeBuffer.size > IMPACT_FILTER_WINDOW) magnitudeBuffer.removeFirst()
        val smoothedMagnitude = magnitudeBuffer.average().toFloat()

        // Terrain-noise buffer: 250 samples (~5 s) for accel_variance (std-dev) logging.
        // O(1) amortized — negligible hot-path cost. Std-dev is computed only when logging.
        varianceBuffer.addLast(magnitude)
        if (varianceBuffer.size > VARIANCE_WINDOW) varianceBuffer.removeFirst()

        val threshold = cachedThreshold
        // Peak threshold: single-sample variant with a higher bar (see peakImpactThresholds).
        // Captures short-duration rigid impacts (10–20ms) that the sliding average would dilute.
        val peakThreshold = cachedPeakThreshold

        // Dynamic post-IMPACT_TMO boost: temporarily raises the effective peak threshold after a false
        // alarm to suppress repeated triggers on the same rough terrain section.
        // The smooth threshold is intentionally NOT boosted — a sustained real crash still fires.
        val now = clock.nowMs()
        val boostActive = now < postImpactBoostUntil

        // Grade-aware proactive boost: on descents the road-surface noise floor (potholes, gravel,
        // cobblestones) is elevated and single-frame peak spikes are common.  Pre-emptively raise
        // the peak threshold based on slope so the FIRST TMO on a descent doesn't happen before
        // the reactive cluster boost can take effect.  Only the peak path is raised; the smooth
        // threshold is unaffected so a real crash on a descent still fires via the smooth detector.
        val gradeBoost = when {
            currentGrade < -10.0 -> 8f   // very steep descent (>10%): maximum terrain noise
            currentGrade < -7.0  -> 5f   // steep descent (7–10%)
            currentGrade < -4.0  -> 2f   // moderate descent (4–7%): slight uplift
            else                 -> 0f   // flat or climbing: no adjustment
        }
        val effectivePeakThreshold = (if (boostActive) peakThreshold + POST_TMO_BOOST else peakThreshold) + gradeBoost


        // Periodic debug logging — only in debug builds (hot path: runs on every sensor event)
        if (BuildConfig.DEBUG && now - lastLogTime > LOG_INTERVAL_MS) {
            lastLogTime = now
            Timber.v("Accel raw=%.2f smooth=%.2fm/s² state=%s thr=%.1f peak_thr=%.1f gyro=%.2f", magnitude, smoothedMagnitude, state, threshold, peakThreshold, lastGyroMag)
        }

        // ── GPS stale transition — log once when stale state changes ──────────
        // isGpsStale() is computed once here and reused throughout to avoid
        // multiple clock.nowMs() calls on the hot sensor path.
        val gpsCurrentlyStale = isGpsStale(now)
        if (gpsCurrentlyStale != lastGpsStaleState) {
            lastGpsStaleState = gpsCurrentlyStale
            if (gpsCurrentlyStale) {
                // GPS just became stale — accel-hardened mode will now be used in SILENCE_CHECK
                calibLogger?.log(CalibrationLogger.Event.GPS_STALE) {
                    "stale=true,since_ms=${now - speedLastUpdatedTime},last_speed=%.1f,state=$state".format(currentSpeedKmh)
                }
            }
        }

        when (state) {
            CrashState.MONITORING -> {
                val minSpeed = config.minSpeedForCrashKmh
                val speedOk = minSpeed == 0 || currentSpeedKmh >= minSpeed
                val cooldownOk = (now - lastCrashTime) > crashCooldownMs
                // Dual detector: smoothed magnitude guards against terrain-edge noise;
                // raw peak magnitude captures short rigid impacts the average would dilute.
                // Either path is sufficient — both require the same settling sequence after.
                // effectivePeakThreshold may be raised by POST_TMO_BOOST during cooldown.
                val impactDetected = smoothedMagnitude > threshold || magnitude > effectivePeakThreshold
                when {
                    impactDetected && speedOk && cooldownOk -> {
                        state = CrashState.IMPACT
                        impactTime = now
                        val source = when {
                            smoothedMagnitude > threshold && magnitude > effectivePeakThreshold -> "BOTH"
                            smoothedMagnitude > threshold -> "SMOOTH"
                            else -> "PEAK"
                        }
                        // Initialise window-progress accumulators
                        maxSmoothedInWindow  = smoothedMagnitude
                        minSpeedInWindow     = currentSpeedKmh
                        minDeviationInWindow = abs(magnitude - GRAVITY)
                        gyroBlockedCnt       = 0
                        speedReachedInWindow = false
                        Timber.d(">>> IMPACT detected! raw=%.1f smooth=%.1fm/s² (thr=%.1f eff_peak_thr=%.1f boost=%b) speed=%.1fkm/h", magnitude, smoothedMagnitude, threshold, effectivePeakThreshold, boostActive, currentSpeedKmh)
                        // ── Calibration: which detector fired, at what levels, at what speed, gyro + buffer
                        // buf: the 3 sliding-window samples in chronological order — helps calibrate IMPACT_FILTER_WINDOW.
                        // gyro: rotation during impact — high gyro on real crashes vs. bike handling events.
                        // boost_active: whether peak threshold was elevated by post-TMO cooldown.
                        calibLogger?.log(CalibrationLogger.Event.IMPACT_ENTER) {
                            val bufStr = magnitudeBuffer.joinToString("|") { "%.1f".format(it) }
                            "source=$source,raw=%.1f,smooth=%.1f,thr=%.1f,pthr=%.1f,eff_pthr=%.1f,speed=%.1f,decel=%.1f,grade=%.1f,grade_boost=%.0f,cadence=%.0f,gyro=%.2f,buf=$bufStr,noise=%.2f,profile=$currentRoutingPreference,preset=${config.crashSensitivity},boost_active=$boostActive".format(magnitude, smoothedMagnitude, threshold, peakThreshold, effectivePeakThreshold, currentSpeedKmh, lastDecelerationKmhPerS, currentGrade, gradeBoost, currentCadence, lastGyroMag, accelStdDev())
                        }
                    }
                    impactDetected && !speedOk && cooldownOk -> {
                        // Impact magnitude crossed threshold but speed gate blocked it.
                        // Key calibration data: was this a real crash that the speed gate missed?
                        calibLogger?.log(CalibrationLogger.Event.IMPACT_SPEED_REJECTED) {
                            "raw=%.1f,smooth=%.1f,thr=%.1f,speed=%.1f,min_speed=$minSpeed,preset=${config.crashSensitivity}".format(magnitude, smoothedMagnitude, threshold, currentSpeedKmh)
                        }
                    }
                    !impactDetected && magnitude > CalibrationLogger.HIGH_MAG_MIN -> {
                        // High magnitude below both thresholds — terrain noise distribution.
                        // Rate-limited: max 1 log/s to avoid saturating the buffer on rough terrain.
                        if ((now - lastHighMagLogMs) > CalibrationLogger.HIGH_MAG_INTERVAL_MS) {
                            lastHighMagLogMs = now
                            calibLogger?.log(CalibrationLogger.Event.HIGH_MAG_NORISING) {
                                // grade/grade_boost: terrain context for the noise distribution
                                "raw=%.1f,smooth=%.1f,thr=%.1f,pthr=%.1f,eff_pthr=%.1f,speed=%.1f,gyro=%.2f,grade=%.1f,grade_boost=%.0f,preset=${config.crashSensitivity},boost=$boostActive".format(magnitude, smoothedMagnitude, threshold, peakThreshold, effectivePeakThreshold, currentSpeedKmh, lastGyroMag, currentGrade, gradeBoost)
                            }
                        }
                    }
                }
            }

            CrashState.IMPACT -> {
                val timeSince = now - impactTime
                val deviation = abs(magnitude - GRAVITY)
                val windowMs  = cachedWindowMs
                val speedHasDropped = isSpeedDropConfirmed(now)

                // ── Update window-progress accumulators ───────────────────────
                if (smoothedMagnitude > maxSmoothedInWindow) maxSmoothedInWindow = smoothedMagnitude
                if (currentSpeedKmh  < minSpeedInWindow)    minSpeedInWindow     = currentSpeedKmh
                if (deviation        < minDeviationInWindow) minDeviationInWindow = deviation
                if (speedHasDropped) speedReachedInWindow = true

                when {
                    deviation < SILENCE_DEVIATION_MAX && lastGyroMag < GYRO_MOVING_MAX && timeSince > 500 && speedHasDropped -> {
                        state = CrashState.SILENCE_CHECK
                        silenceStartTime = now
                        Timber.d(">>> SILENCE_CHECK started (deviation=%.2f gyro=%.2f speed=%.1fkm/h)", deviation, lastGyroMag, currentSpeedKmh)
                        // ── Calibration: silence entry context — use gpsCurrentlyStale (already computed)
                        calibLogger?.log(CalibrationLogger.Event.SILENCE_ENTER) {
                            "time_since_impact_ms=$timeSince,deviation=%.2f,gyro=%.2f,speed=%.1f,gps_stale=$gpsCurrentlyStale".format(deviation, lastGyroMag, currentSpeedKmh)
                        }
                    }
                    // Gyro gate blocking: accel is quiet AND speed dropped, but device is still rotating.
                    // Logged rate-limited (1/s) — key for calibrating GYRO_MOVING_MAX (2.0 rad/s).
                    // If this fires repeatedly on confirmed real crashes, GYRO_MOVING_MAX may be too strict.
                    deviation < SILENCE_DEVIATION_MAX && lastGyroMag >= GYRO_MOVING_MAX && timeSince > 500 && speedHasDropped -> {
                        gyroBlockedCnt++
                        if ((now - lastGyroBlockedLogMs) > CalibrationLogger.GYRO_BLOCKED_INTERVAL_MS) {
                            lastGyroBlockedLogMs = now
                            calibLogger?.log(CalibrationLogger.Event.GYRO_BLOCKED) {
                                "gyro=%.2f,gyro_max=$GYRO_MOVING_MAX,deviation=%.2f,speed=%.1f,time_since_impact_ms=$timeSince".format(lastGyroMag, deviation, currentSpeedKmh)
                            }
                        }
                    }
                    timeSince > windowMs -> {
                        Timber.d("Impact window timeout (%dms) → false alarm, resetting", windowMs)

                        // ── Determine why SILENCE_CHECK was never reached ─────
                        val whyNoSilence = when {
                            !speedReachedInWindow                    -> "SPEED"   // speed never dropped
                            minDeviationInWindow > SILENCE_DEVIATION_MAX -> "ACCEL"   // accel never settled
                            gyroBlockedCnt > 0                       -> "GYRO"    // gyro was too high
                            else                                     -> "UNKNOWN"
                        }

                        // ── Cluster detection: update rolling TMO queue ───────
                        recentTmoTimestamps.addLast(now)
                        while (recentTmoTimestamps.isNotEmpty() &&
                               now - recentTmoTimestamps.first() > CLUSTER_WINDOW_MS) {
                            recentTmoTimestamps.removeFirst()
                        }
                        val isCluster = recentTmoTimestamps.size >= CLUSTER_MIN_TMO
                        val boostMs   = if (isCluster) POST_CLUSTER_COOLDOWN_MS else POST_TMO_COOLDOWN_MS
                        postImpactBoostUntil = now + boostMs

                        // ── Calibration: enriched IMPACT_TMO row ─────────────
                        calibLogger?.log(CalibrationLogger.Event.IMPACT_TIMEOUT) {
                            "time_in_impact_ms=$timeSince,window_ms=$windowMs,speed=%.1f,gyro=%.2f,deviation=%.2f,grade=%.1f,cadence=%.0f,preset=${config.crashSensitivity},why_no_silence=$whyNoSilence,max_smooth=%.1f,min_spd=%.1f,min_dev=%.2f,boost_s=%.0f,cluster=$isCluster".format(
                                currentSpeedKmh, lastGyroMag, deviation,
                                currentGrade, currentCadence,
                                maxSmoothedInWindow, minSpeedInWindow, minDeviationInWindow,
                                boostMs / 1000f, isCluster
                            )
                        }
                        if (isCluster) {
                            calibLogger?.log(CalibrationLogger.Event.TERRAIN_CLUSTER) {
                                "count=${recentTmoTimestamps.size},window_s=${CLUSTER_WINDOW_MS / 1000},boost_s=${boostMs / 1000}"
                            }
                        }
                        resetState()
                        // Schedule a post-reset snapshot 2s later.
                        // If the device is still by then (speed=0, low deviation), this was likely a real crash.
                        schedulePostResetSnapshot("IMPACT_TMO")
                    }
                }
            }

            CrashState.SILENCE_CHECK -> {
                val deviation = abs(magnitude - GRAVITY)
                val timeSinceImpact = now - impactTime
                val windowMs = cachedWindowMs

                // ── Cadence gate: if the rider is actively pedalling, it is definitively NOT
                // a crash — an unconscious rider cannot maintain cadence.
                // We only apply this gate when cadence data has been received at least once
                // (sensor present), so a missing cadence sensor never causes false negatives.
                val isClearlyPedaling = cadenceDataReceived && currentCadence > 20.0
                if (isClearlyPedaling) {
                    Timber.d("CADENCE gate: rider pedalling %.0f RPM during SILENCE_CHECK → not a crash, resetting", currentCadence)
                    calibLogger?.log(CalibrationLogger.Event.CADENCE_GATE) {
                        "cadence=%.0f,speed=%.1f,deviation=%.2f,grade=%.1f,time_since_impact_ms=${now - impactTime}".format(
                            currentCadence, currentSpeedKmh, deviation, currentGrade)
                    }
                    resetState()
                    return   // skip periodic log for this sample — negligible, timer-driven
                }
                // Stillness check: accel near gravity AND GPS speed low.
                //
                // NOTE: gyro is intentionally NOT part of the final SILENCE_CHECK condition.
                //
                // Reason: the specific case we protect against is the bike lying on its side
                // after a crash with the rear wheel still spinning freely in the air (freewheel).
                // In that state the gyro reads high rotation while GPS = 0 km/h and accel ≈ gravity.
                // Requiring gyro to be still here would block confirmation of a perfectly valid crash.
                //
                // Both GPS and gyro measure the Karoo device (mounted on the bike), not the rider.
                // The GPS speed gate is already the definitive "device has stopped translating" check.
                // If the device is still moving (bike rolling downhill), GPS will be above the
                // confirm threshold and isSpeedDropConfirmed() will return false — the crash is
                // not confirmed regardless of the gyro value.
                //
                // EXCEPTION: when GPS is stale (tunnel, dense forest, cable disconnect),
                // isSpeedDropConfirmed() returns true unconditionally. To compensate we
                // tighten the accel deviation threshold and require a longer stillness window
                // so a passive coast between bumps is not mistaken for the rider on the ground.
                //
                // The gyro is already used in the IMPACT→SILENCE_CHECK gate above
                // (lastGyroMag < GYRO_MOVING_MAX) to avoid entering this phase while
                // still actively riding/pedaling.  Once inside SILENCE_CHECK the GPS speed gate
                // is sufficient.
                val gpsStale = gpsCurrentlyStale
                val effectiveDeviationMax = if (gpsStale) GPS_STALE_DEVIATION_MAX else SILENCE_DEVIATION_MAX
                val effectiveSilenceMs    = if (gpsStale) GPS_STALE_SILENCE_DURATION_MS else SILENCE_DURATION_MS
                val speedStillOk = isSpeedDropConfirmed(now)
                val isStill = deviation <= effectiveDeviationMax && speedStillOk

                when {
                    // Confirmed: CONTINUOUS stillness for the required duration → crash
                    isStill && (now - silenceStartTime) >= effectiveSilenceMs -> {
                        val totalMs = now - impactTime
                        Timber.d(">>> CRASH CONFIRMED after %dms (accel dev=%.2f speed=%.1fkm/h gyro=%.2f[ignored] gpsStale=%b)", totalMs, deviation, currentSpeedKmh, lastGyroMag, gpsStale)
                        // ── Calibration: crash confirmed — full context for validation
                        // countdown_s: user has this many seconds to cancel before the emergency is sent.
                        // If the user cancels, CRASH_NO will follow → that row identifies this as a false positive.
                        calibLogger?.log(CalibrationLogger.Event.CRASH_CONFIRMED) {
                            "total_ms=$totalMs,deviation=%.2f,speed=%.1f,confirm_spd_thr=${config.crashConfirmSpeedKmh},grade=%.1f,cadence=%.0f,gps_stale=$gpsStale,preset=${config.crashSensitivity},effective_dev_max=$effectiveDeviationMax,effective_silence_ms=$effectiveSilenceMs,countdown_s=${config.countdownSeconds}".format(deviation, currentSpeedKmh, currentGrade, currentCadence)
                        }
                        resetState()
                        confirmCrash(CrashSource.IMPACT_CONFIRMED)
                    }
                    !isStill -> {
                        if (timeSinceImpact > windowMs * 2) {
                            Timber.d("Silence never achieved after %dms → false alarm, resetting", timeSinceImpact)
                            // ── Calibration: entered SILENCE_CHECK but never achieved stillness
                            // Distinct from IMPACT_TMO (that fires before reaching SILENCE stage).
                            calibLogger?.log(CalibrationLogger.Event.SILENCE_TIMEOUT) {
                                "time_since_impact_ms=$timeSinceImpact,window_ms=$windowMs,deviation=%.2f,eff_max=$effectiveDeviationMax,speed=%.1f,gps_stale=$gpsStale,preset=${config.crashSensitivity}".format(deviation, currentSpeedKmh)
                            }
                            resetState()
                            // Schedule a post-reset snapshot 2s later.
                            // If device is quiet and speed=0 shortly after, this was likely a real crash.
                            schedulePostResetSnapshot("SIL_TMO")
                        } else {
                            // ── Calibration point 4: silence broken — rate-limited to 1/2s
                            if (now - lastSilenceBrokenMs > CalibrationLogger.SILENCE_BROKEN_INTERVAL_MS) {
                                lastSilenceBrokenMs = now
                                val silenceElapsed = now - silenceStartTime
                                calibLogger?.log(CalibrationLogger.Event.SILENCE_BROKEN) {
                                    "deviation=%.2f,eff_max=$effectiveDeviationMax,speed=%.1f,gps_stale=$gpsStale,silence_elapsed_ms=$silenceElapsed".format(deviation, currentSpeedKmh)
                                }
                            }
                            // Reset the silence clock — stillness must be uninterrupted.
                            silenceStartTime = now
                        }
                    }
                    // else: still but silence not yet long enough → keep waiting (no action)
                }
            }
        }

        // ── Calibration: periodic ride-context snapshot ───────────────────────
        // Fires every 5 min regardless of crash state — gives the ride timeline so
        // you can see: at what speed was the rider? was GPS stale? which preset was active?
        // Also fires immediately on the first sensor sample after start() — acts as a
        // config snapshot (captures active thresholds, sensitivity, min speed).
        // The check is cheap (two @Volatile reads + comparison) when logging is disabled.
        if (calibLogger != null && calibLogger.isEnabled &&
            (now - lastPeriodicLogMs) > PERIODIC_LOG_INTERVAL_MS) {
            lastPeriodicLogMs = now
            calibLogger.log(CalibrationLogger.Event.PERIODIC) {
                // grade/cadence: contextual data for this snapshot — helps correlate false alarms
                // with terrain type (descent? climbing?) and rider activity (pedalling?)
                // noise: accel_variance (std-dev over ~5s) — terrain roughness metric
                // profile: Karoo routing preference (ROAD/GRAVEL/MTB) selected by user
                val boostLeft = ((postImpactBoostUntil - now).coerceAtLeast(0L)) / 1000L
                "state=$state,speed=%.1f,accel_dev=%.2f,gyro=%.2f,grade=%.1f,cadence=%.0f,noise=%.2f,profile=$currentRoutingPreference,preset=${config.crashSensitivity},thr=%.1f,pthr=%.1f,eff_pthr=%.1f,grade_boost=%.0f,min_spd=${config.minSpeedForCrashKmh},gps_stale=$gpsCurrentlyStale,boost_s_left=$boostLeft".format(currentSpeedKmh, lastAccelDeviation, lastGyroMag, currentGrade, currentCadence, accelStdDev(), threshold, peakThreshold, effectivePeakThreshold, gradeBoost)
            }
        }
    }

    /**
     * Computes the standard deviation of acceleration magnitudes in [varianceBuffer] (≈5 s window).
     *
     * Returns 0 if the buffer has fewer than 10 samples (not enough data yet).
     * This is the terrain-noise metric: σ≈1–3 m/s² on smooth asphalt, 8–12+ on cobblestones.
     * It is NOT redundant with [magnitudeBuffer] (3-sample smoother for impact detection):
     *  - [magnitudeBuffer] averages out single-sample spikes to guard the impact gate
     *  - [varianceBuffer] measures the statistical spread over 5 s to quantify terrain roughness
     *
     * Called ONLY from calibration log branches (PERIODIC, IMPACT_ENTER) — never on the hot path.
     */
    private fun accelStdDev(): Float {
        val buf = varianceBuffer
        if (buf.size < 10) return 0f
        val mean = buf.average().toFloat()
        var sumSq = 0f
        for (v in buf) { val d = v - mean; sumSq += d * d }
        return sqrt(sumSq / buf.size)
    }

    private fun resetState() {
        state = CrashState.MONITORING   // ← MUST be first: without this, the next sensor callback
        // re-enters IMPACT/SILENCE_CHECK with impactTime=0 / silenceStartTime=0, creating an
        // infinite fire-loop (IMPACT_TMO at 50 Hz) or an immediate crash re-trigger
        // (silenceStartTime=0 → now−0 >> effectiveSilenceMs → crash auto-confirmed).
        impactTime = 0L
        silenceStartTime = 0L
        magnitudeBuffer.clear()
        // NOTE: varianceBuffer intentionally NOT cleared — it represents terrain roughness context
        // that persists across false-alarm resets.  Clearing it here would cause an artificial
        // low-noise reading immediately after every reset, masking the rough-terrain signature.
        // Reset window-tracking accumulators
        maxSmoothedInWindow  = 0f
        minSpeedInWindow     = Double.MAX_VALUE
        minDeviationInWindow = Float.MAX_VALUE
        gyroBlockedCnt       = 0
        speedReachedInWindow = false
    }

    /**
     * Fires a POST_RESET_SNAP calibration event 2 seconds after an internal pipeline reset.
     *
     * Purpose: determine whether the auto-cancelled detection was a real crash or not.
     * Logic: if speed is near zero AND accel deviation is low 2s after the reset, the device
     * is motionless — strongly suggests a real crash was missed (false negative).
     * If speed is non-zero and deviation is high, the reset was correct (false alarm gone).
     *
     * The 2s delay allows the accel signal to settle after the impact window / silence timeout.
     * [cancelledBy]: tag of the event that triggered the reset (IMPACT_TMO or SIL_TMO).
     */
    private fun schedulePostResetSnapshot(cancelledBy: String) {
        if (calibLogger == null || !calibLogger.isEnabled) return
        scope.launch {
            delay(2_000L)
            calibLogger.log(CalibrationLogger.Event.POST_RESET_SNAP) {
                "cancelled_by=$cancelledBy,speed=%.1f,accel_dev=%.2f,gyro=%.2f,state=$state,gps_stale=${isGpsStale()}".format(currentSpeedKmh, lastAccelDeviation, lastGyroMag)
            }
        }
    }

    /**
     * Single exit for every confirmation path. Applies cooldown and updates [lastCrashTime].
     *
     * Why all paths must go through here: before this gate existed the speed-drop monitor
     * could re-fire seconds after a confirmed-and-cancelled main-pipeline crash because it
     * never read [lastCrashTime]. Routing both paths through one method makes that race
     * impossible by construction.
     */
    private fun confirmCrash(source: CrashSource) {
        val now = clock.nowMs()
        if ((now - lastCrashTime) <= crashCooldownMs) {
            Timber.d("Confirm $source suppressed — within cooldown window")
            calibLogger?.log(CalibrationLogger.Event.CRASH_CONFIRMED) {
                "source=$source,suppressed_by=cooldown,since_last_ms=${now - lastCrashTime}"
            }
            return
        }
        lastCrashTime = now
        calibLogger?.log(CalibrationLogger.Event.CRASH_CONFIRMED) { "source=$source" }
        scope.launch { onCrashDetected() }
    }

}
