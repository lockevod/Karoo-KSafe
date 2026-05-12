package com.enderthor.kSafe.extension.crash

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import com.enderthor.kSafe.extension.util.Clock
import com.enderthor.kSafe.extension.util.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Encapsulates the [SensorEventListener] registration, the smoothing sliding-window buffer,
 * the terrain-noise variance buffer and the "stable stillness" tracker that the speed-drop
 * monitor consumes. Emits one [SensorSample] per accelerometer tick to the supplied
 * [onSample] callback.
 *
 * ## Behaviour preservation
 *
 * The constants and computations here are lifted **verbatim** from
 * [CrashDetectionManager] so that wiring the reader in (Task 2.5) is a pure refactor:
 *
 *  - `IMPACT_FILTER_WINDOW = 3` — the 3-sample sliding window (~60 ms at 50 Hz) whose
 *    average is the `smoothedMagnitude` output.
 *  - `VARIANCE_WINDOW = 250` — the ~5 s rolling buffer used by [accelStdDev] for the
 *    terrain-noise metric (PERIODIC / IMPACT_ENTER calibration log fields).
 *  - `SILENCE_DEVIATION_MAX = 4.0` — deviation-from-gravity threshold that drives
 *    [accelStillSinceMs]. The speed-drop monitor reads this field to require continuous
 *    stillness (≥ `SPEED_DROP_ACCEL_STILL_MS`) before firing. Aliased by
 *    `CrashDetectionManager.SILENCE_DEVIATION_MAX` (single source of truth lives here).
 *  - Gravity reference `GRAVITY = 9.81` (m/s²) for the deviation calculation.
 *
 * Notes:
 *  - Production code does **not** use a true low-pass IIR filter (no alpha constant).
 *    The "smoothed" value is the arithmetic mean over the last 3 raw samples.
 *  - Production code does **not** maintain a rolling peak window. Its "peak detector"
 *    is the single raw sample compared against the peak threshold. To preserve that
 *    semantic, [SensorSample.peakMagnitude] is emitted equal to the raw magnitude.
 *    The downstream [CrashStateMachine] then compares it against its own peak threshold
 *    exactly the way the production code does today (`magnitude > peakThreshold`).
 *
 * ## Thread safety
 *
 * Buffers (`magnitudeBuffer`, `varianceBuffer`) are mutated only on the sensor thread.
 * [stop] unregisters the listener **before** clearing the buffers — this eliminates the
 * data race documented as item 11 in the reliability diagnostic, where a clear() on Main
 * could collide with an addLast() on the sensor thread. After unregisterListener returns,
 * no further callbacks will arrive on this listener instance, so the subsequent clear is
 * safe.
 *
 * @property accelStillDeviationMax Deviation-from-gravity threshold under which the
 *   accelerometer is considered "still". Defaults to [SILENCE_DEVIATION_MAX]. Note this is
 *   the same numeric value as the silence-check deviation threshold by historical
 *   coincidence; the speed-drop path consumes [accelStillSinceMs] independently of the
 *   silence-check path.
 */
class SensorReader(
    private val sensorManager: SensorManager,
    private val clock: Clock = SystemClock,
    private val accelStillDeviationMax: Double = SILENCE_DEVIATION_MAX,
    private val onSample: (SensorSample) -> Unit,
) : SensorEventListener {

    /**
     * Timestamp (ms) since which the accelerometer has been continuously below
     * [accelStillDeviationMax]. Reset to 0 whenever a sample exceeds the threshold.
     *
     * Wire as `accelStillSinceProvider = { reader.accelStillSinceMs }` into
     * [SpeedDropMonitor]. The monitor checks
     * `(now - accelStillSinceMs) >= SPEED_DROP_ACCEL_STILL_MS` before firing.
     *
     * @Volatile: written by the sensor thread, read by the speed-drop coroutine.
     */
    @Volatile var accelStillSinceMs: Long = 0L
        private set

    /**
     * Last gyroscope magnitude (rad/s). Used by the state machine as the
     * `gyro_moving` gate on IMPACT → SILENCE_CHECK entry.
     */
    @Volatile var lastGyroMag: Double = 0.0
        private set

    // ── Buffers (sensor-thread only, mutated under no lock) ──────────────────
    private val magnitudeBuffer = ArrayDeque<Double>(IMPACT_FILTER_WINDOW)
    private val varianceBuffer = ArrayDeque<Double>(VARIANCE_WINDOW)

    @Volatile private var registered = false

    /**
     * Register the accelerometer (mandatory) and gyroscope (optional) at
     * `SENSOR_DELAY_GAME` (~50 Hz) with hardware-FIFO batching enabled
     * ([BATCH_MAX_LATENCY_US] = 100 ms). Idempotent.
     *
     * ## Battery: why we batch
     *
     * Without batching, the kernel wakes the CPU once per sensor sample — ~50 wakeups
     * per second per sensor. With `maxReportLatencyUs = 100_000` the kernel buffers up
     * to 100 ms of samples in the sensor IC's FIFO and delivers them in a batch:
     * ~10 CPU wakeups per second instead of 50. The samples themselves are still
     * delivered one at a time to [onSensorChanged] in the original order with full
     * timestamps; only the inter-batch wakeup cadence changes.
     *
     * ## Why batching does not affect detection
     *
     * The state machine reads per-sample magnitude (raw, smoothed, peak) and gyro for
     * the impact-entry gate. None of those depend on the timing between samples —
     * only on the values, which are unchanged by batching. The silence-check timer
     * counts elapsed milliseconds over a 4500 ms window; 100 ms of batch granularity
     * is < 3 % jitter, well inside the existing tick noise. Worst-case impact-to-fire
     * latency increases by up to 100 ms — operationally invisible for an emergency
     * with a 30 s cancel countdown.
     *
     * ## When the batch latency hint is ignored
     *
     * `maxReportLatencyUs` is a hint to Android; some SoCs honour it, others coalesce
     * with global wakeup schedules. The fallback is "behave as if not batching" — no
     * regression risk relative to unbatched.
     *
     * @param handler Optional handler on which to deliver sensor callbacks. When null,
     *   the SDK uses the main looper. Production wires this from
     *   `CrashDetectionManager.start()` without a handler — the reader inherits the
     *   same default.
     */
    fun start(handler: Handler? = null) {
        if (registered) return
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        sensorManager.registerListener(
            this, accel,
            SensorManager.SENSOR_DELAY_GAME,
            BATCH_MAX_LATENCY_US,
            handler,
        )
        gyro?.let {
            sensorManager.registerListener(
                this, it,
                SensorManager.SENSOR_DELAY_GAME,
                BATCH_MAX_LATENCY_US,
                handler,
            )
        }
        registered = true
    }

    /**
     * Unregister the listener and then clear the buffers.
     *
     * Order matters: `unregisterListener` must come first so no more `onSensorChanged`
     * callbacks can fire concurrently with the `clear()` calls below. This fixes the
     * data race where buffer.clear() on Main could collide with buffer.addLast() on
     * the sensor thread (item 11 in the reliability diagnostic).
     */
    fun stop() {
        if (!registered) return
        sensorManager.unregisterListener(this)
        registered = false
        // Safe to clear now — no more callbacks will arrive.
        magnitudeBuffer.clear()
        // NOTE: in production CrashDetectionManager.resetState() leaves varianceBuffer
        // intact across false-alarm resets (terrain-roughness context persists). But
        // on full stop (ride end / extension teardown) the buffer should be cleared
        // so a future restart begins with a clean window.
        varianceBuffer.clear()
        accelStillSinceMs = 0L
        lastGyroMag = 0.0
    }

    /**
     * Standard deviation of the magnitudes in [varianceBuffer] (~5 s window).
     *
     * Mirrors `CrashDetectionManager.accelStdDev()` exactly: returns 0 if fewer than 10
     * samples have been collected, else the population std-dev (`sumSq / N`, not
     * `sumSq / (N-1)` — same as production).
     */
    fun accelStdDev(): Double {
        val buf = varianceBuffer
        if (buf.size < 10) return 0.0
        val mean = buf.average()
        var sumSq = 0.0
        for (v in buf) {
            val d = v - mean
            sumSq += d * d
        }
        return sqrt(sumSq / buf.size)
    }

    /**
     * Snapshot of the current 3-sample magnitude buffer in chronological order. Used by
     * the calibration logger's `buf=` field on IMPACT_ENTER. Returns an immutable copy
     * so callers cannot mutate the internal deque.
     */
    fun magnitudeBufferSnapshot(): List<Double> = magnitudeBuffer.toList()

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> processGyro(event)
            Sensor.TYPE_ACCELEROMETER -> processAccel(event)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun processGyro(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        lastGyroMag = sqrt((x * x + y * y + z * z).toDouble())
    }

    private fun processAccel(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val rawMagnitude = sqrt((x * x + y * y + z * z).toDouble())

        val deviation = abs(rawMagnitude - GRAVITY)

        // Maintain "stable stillness" accumulator for the speed-drop monitor.
        // Reset whenever the device moves; start the clock when stillness begins.
        // Verbatim from CrashDetectionManager.processAccelerometer().
        if (deviation > accelStillDeviationMax) {
            accelStillSinceMs = 0L
        } else if (accelStillSinceMs == 0L) {
            accelStillSinceMs = clock.nowMs()
        }

        // Sliding 3-sample average (~60 ms at 50 Hz) — same as production.
        magnitudeBuffer.addLast(rawMagnitude)
        if (magnitudeBuffer.size > IMPACT_FILTER_WINDOW) magnitudeBuffer.removeFirst()
        val smoothedMagnitude = magnitudeBuffer.average()

        // Terrain-noise variance buffer (250 samples ≈ 5 s @ 50 Hz). std-dev is
        // computed only on demand via [accelStdDev], never on the hot path.
        varianceBuffer.addLast(rawMagnitude)
        if (varianceBuffer.size > VARIANCE_WINDOW) varianceBuffer.removeFirst()

        // Production has no rolling peak window — its peak detector compares the raw
        // single sample against the peak threshold. Emit peakMagnitude == rawMagnitude
        // so CrashStateMachine reproduces the existing behaviour exactly.
        onSample(
            SensorSample(
                rawMagnitude = rawMagnitude,
                smoothedMagnitude = smoothedMagnitude,
                peakMagnitude = rawMagnitude,
                gyroMag = lastGyroMag,
                timestampMs = clock.nowMs(),
            )
        )
    }

    companion object {
        /** Sliding window for the smoothed-magnitude detector (verbatim from CDM). */
        const val IMPACT_FILTER_WINDOW = 3

        /** Rolling buffer size for the terrain-noise std-dev metric (verbatim from CDM). */
        const val VARIANCE_WINDOW = 250

        /** Reference gravity in m/s² for the deviation calculation (verbatim from CDM). */
        const val GRAVITY = 9.81

        /**
         * Deviation-from-gravity threshold under which the accelerometer is considered
         * "still" for the speed-drop monitor's stable-stillness accumulator.
         * Single source of truth — `CrashDetectionManager.SILENCE_DEVIATION_MAX` aliases this.
         * If you change one, change both — they MUST stay numerically equal.
         */
        const val SILENCE_DEVIATION_MAX = 4.0

        /**
         * Sensor FIFO batch latency. 100 ms = up to 5 samples buffered per wakeup at
         * SENSOR_DELAY_GAME (~50 Hz). See [start] for the reasoning.
         */
        const val BATCH_MAX_LATENCY_US = 100_000
    }
}
