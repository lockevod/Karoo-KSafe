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
     * exceeding a higher bar, without requiring the 3-sample sliding average.
     *
     * Rationale: sharp, rigid impacts (handlebar hitting asphalt, direct obstacle collision)
     * can produce a real crash peak lasting only 10–20ms (1 sample at 50 Hz). The sliding-window
     * average would reduce a single 70 m/s² spike at ~1.3× to below the smoothed threshold,
     * causing a silent false negative. The peak detector captures these short-duration events.
     *
     * The higher per-preset bar compensates for lower statistical reliability vs. the smoothed
     * signal: a single sample can be noise; if it passes this higher gate AND is followed by
     * the normal settling sequence, it is a real crash.
     */
    private val peakImpactThresholds = mapOf(
        CrashSensitivity.LOW    to 70f,  // ~7g — single-frame peak variant of LOW
        CrashSensitivity.MEDIUM to 60f,  // ~6g — single-frame peak variant of MEDIUM
        CrashSensitivity.HIGH   to 50f   // ~5g — single-frame peak variant of HIGH
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

    /**
     * Timestamp (ms) since which the accelerometer has been continuously below
     * [SILENCE_DEVIATION_MAX]. Reset to 0 whenever a sample exceeds the threshold.
     * Used by the speed-drop monitor to require STABLE stillness, not a single lucky
     * snapshot at the polling boundary. See [SPEED_DROP_ACCEL_STILL_MS].
     */
    @Volatile private var accelStillSinceMs = 0L

    private var speedDropJob: Job? = null
    @Volatile private var speedDropStartTime = 0L
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
    private val PERIODIC_LOG_INTERVAL_MS = 300_000L  // 5 minutes
    /** Tracks the previous GPS-stale state to log only when the condition changes. */
    @Volatile private var lastGpsStaleState = false
    /** Tracks the last sensitivity preset to detect mid-ride config changes. */
    @Volatile private var lastLoggedSensitivity = config.crashSensitivity

    // ─── Public API ───────────────────────────────────────────────────────────

    fun start(config: KSafeConfig) {
        this.config = config
        if (!config.crashDetectionEnabled) {
            Timber.d("CrashDetection disabled in config, skipping start")
            return
        }
        resetState()
        speedDataReceived = false
        startTime = System.currentTimeMillis()
        // Reset periodic log timer so the first sensor sample fires an immediate config snapshot.
        // This captures preset, thresholds and initial state at the start of each session.
        lastPeriodicLogMs = 0L
        lastGpsStaleState = false
        lastLoggedSensitivity = config.crashSensitivity

        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            ?: Timber.w("No accelerometer found on this device!")
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }

        Timber.d("CrashDetectionManager STARTED (sensitivity=${config.crashSensitivity}, threshold=${impactThresholds[config.crashSensitivity]}m/s²)")

        if (config.speedDropDetectionEnabled) startSpeedDropMonitor()
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        speedDropJob?.cancel()
        resetState()
        Timber.d("CrashDetectionManager STOPPED")
    }

    fun updateConfig(config: KSafeConfig) {
        val wasEnabled = this.config.crashDetectionEnabled
        val oldSensitivity = this.config.crashSensitivity
        val oldCustomThr = this.config.customCrashThreshold
        this.config = config
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
        if (!speedDataReceived) {
            speedDataReceived = true
            Timber.d("Cold-start guard lifted: first speed data received (%.1f km/h)", speedKmh)
        }
        currentSpeedKmh = speedKmh
        speedLastUpdatedTime = System.currentTimeMillis()

        if (config.speedDropDetectionEnabled) {
            if (speedKmh < SPEED_THRESHOLD_KMH) {
                if (speedDropStartTime == 0L) speedDropStartTime = System.currentTimeMillis()
            } else {
                speedDropStartTime = 0L
            }
        }
    }

    /**
     * Resets the speed-drop accumulator when the ride is paused.
     * While stopped at a café the speed is 0, which would otherwise trigger speed-drop
     * detection after the configured window — even though the rider intentionally paused.
     */
    fun resetSpeedDropOnPause() {
        speedDropStartTime = 0L
        Timber.d("Speed-drop timer reset on ride pause")
    }

    // ─── Speed drop confirmation ──────────────────────────────────────────────

    /**
     * True if the speed reading hasn't been refreshed for longer than [GPS_STALE_MS].
     * When stale, the SDK is likely returning a frozen last-known value and the
     * accelerometer must take over with a HARDENED threshold (see [GPS_STALE_DEVIATION_MAX]).
     */
    private fun isGpsStale(): Boolean =
        speedLastUpdatedTime > 0 &&
                (System.currentTimeMillis() - speedLastUpdatedTime) > GPS_STALE_MS

    /**
     * Returns true if the rider's GPS speed is low enough to confirm a crash.
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
    private fun isSpeedDropConfirmed(): Boolean {
        // Guard: no data yet AND within the cold-start window → not safe to confirm
        if (!speedDataReceived && (System.currentTimeMillis() - startTime) < COLD_START_GUARD_MS) {
            Timber.d("Cold-start guard active — speed confirmation blocked (no data yet, %.1fs elapsed)",
                (System.currentTimeMillis() - startTime) / 1000.0)
            return false
        }
        // Stale GPS: SDK is returning a frozen last-known value. Bypass the GPS gate and
        // let the accel decide — but the SILENCE_CHECK uses a stricter deviation threshold
        // and a longer required duration when this happens (see GPS_STALE_DEVIATION_MAX
        // and GPS_STALE_SILENCE_DURATION_MS).
        if (isGpsStale()) {
            Timber.w("GPS stale (%.1fs since last update) — speed gate bypassed, accel hardened",
                (System.currentTimeMillis() - speedLastUpdatedTime) / 1000.0)
            return true
        }
        val minSpeed = config.minSpeedForCrashKmh
        // minSpeed == 0 means user wants detection at any speed → treat as always stopped
        return minSpeed == 0 || currentSpeedKmh < speedCrashConfirmKmh
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
            accelStillSinceMs = System.currentTimeMillis()
        }

        // Sliding-window average (3 samples ≈ 60ms at SENSOR_DELAY_GAME).
        // Filters single-sample terrain-edge spikes (dirt→asphalt, cobblestones)
        // while preserving sustained crash impacts.
        magnitudeBuffer.addLast(magnitude)
        if (magnitudeBuffer.size > IMPACT_FILTER_WINDOW) magnitudeBuffer.removeFirst()
        val smoothedMagnitude = magnitudeBuffer.average().toFloat()

        val threshold = if (config.crashSensitivity == CrashSensitivity.CUSTOM)
            config.customCrashThreshold.toFloat().coerceIn(20f, 70f)
        else
            impactThresholds[config.crashSensitivity] ?: 45f

        // Peak threshold: single-sample variant with a higher bar (see peakImpactThresholds).
        // Captures short-duration rigid impacts (10–20ms) that the sliding average would dilute.
        val peakThreshold = if (config.crashSensitivity == CrashSensitivity.CUSTOM)
            (config.customCrashThreshold.toFloat() * 1.3f).coerceIn(25f, 80f)
        else
            peakImpactThresholds[config.crashSensitivity] ?: 60f

        val now = System.currentTimeMillis()

        // Periodic debug logging — only in debug builds (hot path: runs on every sensor event)
        if (BuildConfig.DEBUG && now - lastLogTime > LOG_INTERVAL_MS) {
            lastLogTime = now
            Timber.v("Accel raw=%.2f smooth=%.2fm/s² state=%s thr=%.1f peak_thr=%.1f gyro=%.2f", magnitude, smoothedMagnitude, state, threshold, peakThreshold, lastGyroMag)
        }

        // ── GPS stale transition — log once when stale state changes ──────────
        // isGpsStale() is computed once here and reused throughout to avoid
        // multiple System.currentTimeMillis() calls on the hot sensor path.
        val gpsCurrentlyStale = isGpsStale()
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
                val impactDetected = smoothedMagnitude > threshold || magnitude > peakThreshold
                when {
                    impactDetected && speedOk && cooldownOk -> {
                        state = CrashState.IMPACT
                        impactTime = now
                        val source = when {
                            smoothedMagnitude > threshold && magnitude > peakThreshold -> "BOTH"
                            smoothedMagnitude > threshold -> "SMOOTH"
                            else -> "PEAK"
                        }
                        Timber.d(">>> IMPACT detected! raw=%.1f smooth=%.1fm/s² (thr=%.1f peak_thr=%.1f) speed=%.1fkm/h", magnitude, smoothedMagnitude, threshold, peakThreshold, currentSpeedKmh)
                        // ── Calibration: which detector fired, at what levels, at what speed, gyro + buffer
                        // buf: the 3 sliding-window samples in chronological order — helps calibrate IMPACT_FILTER_WINDOW.
                        // gyro: rotation during impact — high gyro on real crashes vs. bike handling events.
                        calibLogger?.log(CalibrationLogger.Event.IMPACT_ENTER) {
                            val bufStr = magnitudeBuffer.joinToString("|") { "%.1f".format(it) }
                            "source=$source,raw=%.1f,smooth=%.1f,thr=%.1f,pthr=%.1f,speed=%.1f,gyro=%.2f,buf=$bufStr,preset=${config.crashSensitivity}".format(magnitude, smoothedMagnitude, threshold, peakThreshold, currentSpeedKmh, lastGyroMag)
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
                                // gyro included: high gyro = device moving/handling = not terrain noise
                                "raw=%.1f,smooth=%.1f,thr=%.1f,pthr=%.1f,speed=%.1f,gyro=%.2f,preset=${config.crashSensitivity}".format(magnitude, smoothedMagnitude, threshold, peakThreshold, currentSpeedKmh, lastGyroMag)
                            }
                        }
                    }
                }
            }

            CrashState.IMPACT -> {
                val timeSince = now - impactTime
                val deviation = abs(magnitude - GRAVITY)
                val windowMs  = impactWindowMs[config.crashSensitivity] ?: 20_000L
                val speedHasDropped = isSpeedDropConfirmed()

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
                        if ((now - lastGyroBlockedLogMs) > CalibrationLogger.GYRO_BLOCKED_INTERVAL_MS) {
                            lastGyroBlockedLogMs = now
                            calibLogger?.log(CalibrationLogger.Event.GYRO_BLOCKED) {
                                "gyro=%.2f,gyro_max=$GYRO_MOVING_MAX,deviation=%.2f,speed=%.1f,time_since_impact_ms=$timeSince".format(lastGyroMag, deviation, currentSpeedKmh)
                            }
                        }
                    }
                    timeSince > windowMs -> {
                        Timber.d("Impact window timeout (%dms) → false alarm, resetting", windowMs)
                        // ── Calibration: timeout — how long in IMPACT and why it didn't settle
                        calibLogger?.log(CalibrationLogger.Event.IMPACT_TIMEOUT) {
                            "time_in_impact_ms=$timeSince,window_ms=$windowMs,speed=%.1f,gyro=%.2f,deviation=%.2f,preset=${config.crashSensitivity}".format(currentSpeedKmh, lastGyroMag, deviation)
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
                val windowMs = impactWindowMs[config.crashSensitivity] ?: 20_000L

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
                val speedStillOk = isSpeedDropConfirmed()
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
                            "total_ms=$totalMs,deviation=%.2f,speed=%.1f,gps_stale=$gpsStale,preset=${config.crashSensitivity},effective_dev_max=$effectiveDeviationMax,effective_silence_ms=$effectiveSilenceMs,countdown_s=${config.countdownSeconds}".format(deviation, currentSpeedKmh)
                        }
                        lastCrashTime = now
                        resetState()
                        scope.launch { onCrashDetected() }
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
                // thr/pthr: exact impact thresholds active right now (changes with preset/custom)
                // min_spd: minimum speed configured for crash detection
                "state=$state,speed=%.1f,accel_dev=%.2f,gyro=%.2f,preset=${config.crashSensitivity},thr=%.1f,pthr=%.1f,min_spd=${config.minSpeedForCrashKmh},gps_stale=$gpsCurrentlyStale".format(currentSpeedKmh, lastAccelDeviation, lastGyroMag, threshold, peakThreshold)
            }
        }
    }

    private fun resetState() {
        state = CrashState.MONITORING
        impactTime = 0L
        silenceStartTime = 0L
        magnitudeBuffer.clear()
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
            kotlinx.coroutines.delay(2_000L)
            calibLogger.log(CalibrationLogger.Event.POST_RESET_SNAP) {
                "cancelled_by=$cancelledBy,speed=%.1f,accel_dev=%.2f,gyro=%.2f,state=$state,gps_stale=${isGpsStale()}".format(currentSpeedKmh, lastAccelDeviation, lastGyroMag)
            }
        }
    }

    // ─── Speed drop monitor ───────────────────────────────────────────────────

    private fun startSpeedDropMonitor() {
        speedDropJob?.cancel()
        speedDropJob = scope.launch {
            while (true) {
                delay(30_000L)
                if (speedDropStartTime > 0) {
                    val now = System.currentTimeMillis()
                    val stoppedFor = (now - speedDropStartTime) / 60_000
                    if (stoppedFor >= config.speedDropMinutes) {
                        // Additional guard: require accelerometer to have been continuously near
                        // gravity for at least SPEED_DROP_ACCEL_STILL_MS — not just at this
                        // single polling instant. A snapshot read at the 30s boundary can land on
                        // either side of the rider's activity by chance, producing both false
                        // positives (caller phone but bike happened to be still) and false
                        // negatives (caller stopped fidgeting just as we polled).
                        //
                        // accelStillSinceMs is reset to 0 in processAccelerometer whenever a
                        // sample exceeds SILENCE_DEVIATION_MAX. A non-zero value means the
                        // accel has been quiet since that timestamp.
                        val stillSince = accelStillSinceMs
                        val stillStableFor = if (stillSince > 0) now - stillSince else 0L
                        val accelOk = stillStableFor >= SPEED_DROP_ACCEL_STILL_MS
                        // ── Calibration point 5: log every evaluation so you can tune the 60s threshold
                        calibLogger?.log(CalibrationLogger.Event.SPEEDDROP_EVAL) {
                            "stopped_min=$stoppedFor,still_stable_s=${stillStableFor/1_000},need_s=${SPEED_DROP_ACCEL_STILL_MS/1_000},accel_ok=$accelOk"
                        }
                        if (accelOk) {
                            Timber.d("Speed drop confirmed: stopped for ${stoppedFor}min, accel stable for %.0fs → alert",
                                stillStableFor / 1000.0)
                            speedDropStartTime = 0L
                            onCrashDetected()
                        } else {
                            Timber.d("Speed drop timer elapsed but accel not stably still (stable for %.0fs, need %.0fs) — waiting",
                                stillStableFor / 1000.0, SPEED_DROP_ACCEL_STILL_MS / 1000.0)
                        }
                    }
                }
            }
        }
    }
}
