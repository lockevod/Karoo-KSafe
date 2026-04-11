package com.enderthor.kSafe.extension.managers

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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

    // Gyroscope thresholds (rad/s)
    // Literature: crash confirmation requires rotation near zero post-impact (~45°/s = 0.785 rad/s)
    private val GYRO_STILL_MAX   = 1.0f   // rad/s — device is not rotating (≈ 57°/s)
    private val GYRO_MOVING_MAX  = 3.0f   // rad/s — device is clearly still moving/riding

    private val SILENCE_DURATION_MS =  4_500L  // must be still for 4.5s (literature uses 5s)
    private val LOG_INTERVAL_MS     =  2_000L  // log magnitude every 2s for debugging

    // ─── State ────────────────────────────────────────────────────────────────

    private enum class CrashState { MONITORING, IMPACT, SILENCE_CHECK }

    private var state = CrashState.MONITORING
    private var impactTime  = 0L
    private var silenceStartTime = 0L
    private var currentSpeedKmh = 0.0
    private var config = KSafeConfig()
    private var lastLogTime = 0L

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
        currentSpeedKmh = speedKmh

        if (config.speedDropDetectionEnabled) {
            if (speedKmh < SPEED_THRESHOLD_KMH) {
                if (speedDropStartTime == 0L) speedDropStartTime = System.currentTimeMillis()
            } else {
                speedDropStartTime = 0L
            }
        }
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
        val threshold = if (config.crashSensitivity == CrashSensitivity.CUSTOM)
            config.customCrashThreshold.toFloat().coerceIn(20f, 70f)
        else
            impactThresholds[config.crashSensitivity] ?: 45f
        val now = System.currentTimeMillis()

        // Periodic debug logging so you can see values in logcat
        if (now - lastLogTime > LOG_INTERVAL_MS) {
            lastLogTime = now
            Timber.v("Accel magnitude=%.2fm/s² state=$state threshold=%.1f gyro=%.2f".format(magnitude, threshold, lastGyroMag))
        }

        when (state) {
            CrashState.MONITORING -> {
                val minSpeed = config.minSpeedForCrashKmh
                val speedOk = minSpeed == 0 || currentSpeedKmh >= minSpeed
                if (magnitude > threshold && speedOk) {
                    state = CrashState.IMPACT
                    impactTime = now
                    Timber.d(">>> IMPACT detected! magnitude=%.1fm/s² (threshold=%.1f) speed=%.1fkm/h minSpeed=$minSpeed".format(magnitude, threshold, currentSpeedKmh))
                }
            }

            CrashState.IMPACT -> {
                val timeSince = now - impactTime
                val deviation = abs(magnitude - GRAVITY)
                val windowMs  = impactWindowMs[config.crashSensitivity] ?: 20_000L

                when {
                    // Device settling (accel near gravity AND gyro calming down) → begin silence check
                    deviation < SILENCE_DEVIATION_MAX && lastGyroMag < GYRO_MOVING_MAX && timeSince > 500 -> {
                        state = CrashState.SILENCE_CHECK
                        silenceStartTime = now
                        Timber.d(">>> SILENCE_CHECK started (deviation=%.2f gyro=%.2f)".format(deviation, lastGyroMag))
                    }
                    // Timeout: never settled after impact → false alarm (MTB jump that continued riding)
                    timeSince > windowMs -> {
                        Timber.d("Impact window timeout (${windowMs}ms) → false alarm, resetting")
                        resetState()
                    }
                }
            }

            CrashState.SILENCE_CHECK -> {
                val deviation = abs(magnitude - GRAVITY)
                val silenceDuration = now - silenceStartTime
                val timeSinceImpact = now - impactTime
                val windowMs = impactWindowMs[config.crashSensitivity] ?: 20_000L

                // Device moved significantly (riding again) or rotating (still moving)
                val accelMoving = deviation > SILENCE_DEVIATION_MAX * 1.5f
                val gyroMoving  = lastGyroMag > GYRO_MOVING_MAX

                when {
                    accelMoving || gyroMoving -> {
                        if (timeSinceImpact < windowMs) {
                            // Slight movement (bike sliding/rolling after crash) — go back to
                            // IMPACT state and wait for the device to settle again rather than
                            // resetting completely to MONITORING (which would discard the crash).
                            state = CrashState.IMPACT
                            Timber.d("Brief movement during silence (dev=%.2f gyro=%.2f) → back to IMPACT, waiting to settle".format(deviation, lastGyroMag))
                        } else {
                            // Impact window expired with too much movement → genuine false alarm
                            Timber.d("Sustained movement during silence → false alarm, resetting".format())
                            resetState()
                        }
                    }
                    // Both accel AND gyro confirm stillness for required duration → CRASH CONFIRMED
                    silenceDuration >= SILENCE_DURATION_MS && lastGyroMag < GYRO_STILL_MAX -> {
                        val totalMs = now - impactTime
                        Timber.d(">>> CRASH CONFIRMED after ${totalMs}ms (accel dev=%.2f gyro=%.2f)".format(deviation, lastGyroMag))
                        resetState()
                        scope.launch { onCrashDetected() }
                    }
                }
            }
        }
    }

    private fun resetState() {
        state = CrashState.MONITORING
        impactTime = 0L
        silenceStartTime = 0L
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
