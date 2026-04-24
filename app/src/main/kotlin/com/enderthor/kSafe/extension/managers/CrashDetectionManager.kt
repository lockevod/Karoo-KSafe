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
    private val onCrashDetected: () -> Unit
) : SensorEventListener {

    // ─── Thresholds (m/s²) ────────────────────────────────────────────────────
    //
    // Reference (literature):
    //  - Normal riding bumps / hard braking: ~1.5g (14.7 m/s²) — NOT a crash
    //  - MTB jump landing: 3–5g but followed by continued movement
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
     * Post-crash cooldown: ignore new IMPACT triggers for 30s after a confirmed crash.
     * Prevents duplicate alerts when the emergency countdown is already running.
     */
    private val CRASH_COOLDOWN_MS = 30_000L

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

    private enum class CrashState { MONITORING, IMPACT, SILENCE_CHECK }

    private var state = CrashState.MONITORING
    private var impactTime  = 0L
    private var silenceStartTime = 0L
    private var lastCrashTime = 0L
    private var currentSpeedKmh = 0.0
    private var config = KSafeConfig()
    private var lastLogTime = 0L

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
    private var speedDataReceived = false
    private var startTime = 0L

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope     = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var lastGyroMag   = 0f

    private var speedDropJob: Job? = null
    private var speedDropStartTime = 0L

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
        this.config = config
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
     * Returns true if the rider's GPS speed is low enough to confirm a crash.
     *
     * Cold-start guard: blocks confirmation for [COLD_START_GUARD_MS] if no speed data has
     * been received yet — prevents false alarms caused by [currentSpeedKmh] defaulting to 0.0.
     * The guard is transparent once data arrives (even 1 sample lifts it immediately) or once
     * the timeout expires (fallback for devices with no speed source).
     */
    private fun isSpeedDropConfirmed(): Boolean {
        // Guard: no data yet AND within the cold-start window → not safe to confirm
        if (!speedDataReceived && (System.currentTimeMillis() - startTime) < COLD_START_GUARD_MS) {
            Timber.d("Cold-start guard active — speed confirmation blocked (no data yet, %.1fs elapsed)",
                (System.currentTimeMillis() - startTime) / 1000.0)
            return false
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
        val now = System.currentTimeMillis()

        // Periodic debug logging — only in debug builds (hot path: runs on every sensor event)
        if (BuildConfig.DEBUG && now - lastLogTime > LOG_INTERVAL_MS) {
            lastLogTime = now
            Timber.v("Accel raw=%.2f smooth=%.2fm/s² state=%s threshold=%.1f gyro=%.2f", magnitude, smoothedMagnitude, state, threshold, lastGyroMag)
        }

        when (state) {
            CrashState.MONITORING -> {
                val minSpeed = config.minSpeedForCrashKmh
                val speedOk = minSpeed == 0 || currentSpeedKmh >= minSpeed
                val cooldownOk = (now - lastCrashTime) > CRASH_COOLDOWN_MS
                // Use smoothed magnitude — rejects single-sample noise spikes
                if (smoothedMagnitude > threshold && speedOk && cooldownOk) {
                    state = CrashState.IMPACT
                    impactTime = now
                    Timber.d(">>> IMPACT detected! raw=%.1f smooth=%.1fm/s² (threshold=%.1f) speed=%.1fkm/h", magnitude, smoothedMagnitude, threshold, currentSpeedKmh)
                }
            }

            CrashState.IMPACT -> {
                val timeSince = now - impactTime
                val deviation = abs(magnitude - GRAVITY)
                val windowMs  = impactWindowMs[config.crashSensitivity] ?: 20_000L
                // GPS speed gate: only enter silence check if the rider has stopped.
                // Configurable via crashConfirmSpeedKmh (default 5 km/h).
                // At slow climbing speeds the device can feel "quiet" after a bump
                // but the rider is still moving — GPS is the definitive check here.
                val speedHasDropped = isSpeedDropConfirmed()

                when {
                    // Device settling (accel near gravity AND gyro calming down AND GPS speed low) → begin silence check
                    deviation < SILENCE_DEVIATION_MAX && lastGyroMag < GYRO_MOVING_MAX && timeSince > 500 && speedHasDropped -> {
                        state = CrashState.SILENCE_CHECK
                        silenceStartTime = now
                        Timber.d(">>> SILENCE_CHECK started (deviation=%.2f gyro=%.2f speed=%.1fkm/h)", deviation, lastGyroMag, currentSpeedKmh)
                    }
                    // Timeout: never settled after impact → false alarm (MTB jump that continued riding)
                    timeSince > windowMs -> {
                        Timber.d("Impact window timeout (%dms) → false alarm, resetting", windowMs)
                        resetState()
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
                // Reason: the device is mounted on the bike (Karoo on handlebars).
                // After a crash the RIDER may be stopped, but the BIKE can keep sliding or
                // rolling (especially on slopes), which would make the gyro spin even though
                // the rider is already on the ground.  If we required the gyro to be still
                // here, a real crash where the bike keeps moving would NEVER be confirmed
                // → no alert sent → dangerous false negative.
                //
                // The gyro is already used in the IMPACT→SILENCE_CHECK gate above
                // (lastGyroMag < GYRO_MOVING_MAX) to avoid entering this phase while
                // still actively riding.  Once inside SILENCE_CHECK the GPS speed gate
                // is the definitive "rider has stopped" discriminator.
                val speedStillOk = isSpeedDropConfirmed()
                val isStill = deviation <= SILENCE_DEVIATION_MAX && speedStillOk

                when {
                    // Confirmed: CONTINUOUS stillness for the required duration → crash
                    isStill && (now - silenceStartTime) >= SILENCE_DURATION_MS -> {
                        val totalMs = now - impactTime
                        Timber.d(">>> CRASH CONFIRMED after %dms (accel dev=%.2f speed=%.1fkm/h gyro=%.2f[ignored])", totalMs, deviation, currentSpeedKmh, lastGyroMag)
                        lastCrashTime = now
                        resetState()
                        scope.launch { onCrashDetected() }
                    }
                    // Not still → reset continuous-silence timer.
                    // If we have been waiting for too long (2× impact window) without ever
                    // achieving the required stillness, give up and treat as a false alarm.
                    !isStill -> {
                        if (timeSinceImpact > windowMs * 2) {
                            Timber.d("Silence never achieved after %dms → false alarm, resetting", timeSinceImpact)
                            resetState()
                        } else {
                            // Reset the silence clock — stillness must be uninterrupted.
                            silenceStartTime = now
                        }
                    }
                    // else: still but silence not yet long enough → keep waiting (no action)
                }
            }
        }
    }

    private fun resetState() {
        state = CrashState.MONITORING
        impactTime = 0L
        silenceStartTime = 0L
        magnitudeBuffer.clear()
    }

    // ─── Speed drop monitor ───────────────────────────────────────────────────

    private fun startSpeedDropMonitor() {
        speedDropJob?.cancel()
        speedDropJob = scope.launch {
            while (true) {
                delay(30_000L)
                if (speedDropStartTime > 0) {
                    val stoppedFor = (System.currentTimeMillis() - speedDropStartTime) / 60_000
                    if (stoppedFor >= config.speedDropMinutes) {
                        Timber.d("Speed drop confirmed: stopped for ${stoppedFor}min")
                        speedDropStartTime = 0L
                        onCrashDetected()
                    }
                }
            }
        }
    }
}
