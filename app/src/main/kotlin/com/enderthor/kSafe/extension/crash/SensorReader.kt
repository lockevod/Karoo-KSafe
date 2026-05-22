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
import timber.log.Timber

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
 * Buffers (`magnitudeBuffer`, `varianceBuffer`, `vectorBuffer`) are mutated only on the
 * sensor thread. [stop] unregisters the listener **before** clearing the buffers — this
 * eliminates the data race documented as item 11 in the reliability diagnostic, where a
 * clear() on Main could collide with an addLast() on the sensor thread. After
 * unregisterListener returns, no further callbacks will arrive on this listener instance,
 * so the subsequent clear is safe.
 *
 * The pre-impact vector ring is also invalidated on ride pause via [invalidateVectorRing],
 * but the listener stays REGISTERED across a pause. To avoid a Main-vs-sensor-thread race
 * there, [invalidateVectorRing] does NOT mutate the ring — it raises a `@Volatile` floor
 * timestamp ([vectorRingFloorMs]) that [preImpactReference] uses to discard pre-pause
 * samples. The sensor thread remains the sole mutator of the ring.
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
    // Primitive ring buffers — replace the old `ArrayDeque<Double>` to eliminate
    // autoboxing on the 50 Hz sensor thread. Each addLast/removeFirst on the deque
    // boxed one Double per op; over a 4 h ride that was ~1.4 M short-lived `Double`
    // objects across the two buffers. DoubleArray-backed rings cost zero alloc on
    // the hot path. Sizes match the existing constants exactly.
    private val magnitudeBuffer = DoubleRingBuffer(IMPACT_FILTER_WINDOW)
    private val varianceBuffer = DoubleRingBuffer(VARIANCE_WINDOW)

    /**
     * Ring buffer of the most recent timestamped accelerometer vectors, used to
     * compute the pre-impact orientation reference. ~150 entries ≈ 3 s at 50 Hz —
     * covers the 2 s averaging window plus the 250 ms guard with margin.
     * Sensor-thread-only, like the other buffers.
     */
    private val vectorBuffer = Vec3RingBuffer(PRE_IMPACT_RING_CAPACITY)

    /**
     * Lock-free invalidation floor for [vectorBuffer]. Any ring entry with
     * `tsMs < vectorRingFloorMs` is ignored when computing the pre-impact reference.
     *
     * The main thread (ride pause) only ever *raises* this floor — it never touches
     * the ring's `head`/`size`, so the sensor thread stays the sole mutator of the
     * ring and the 50 Hz hot path is untouched. See [invalidateVectorRing].
     *
     * @Volatile: written by the main thread, read by [preImpactReference].
     */
    @Volatile private var vectorRingFloorMs: Long = Long.MIN_VALUE

    @Volatile private var registered = false

    /**
     * Wall-clock timestamp of the last sensor-thread exception logged from
     * [onSensorChanged]. Used to rate-limit the error log so a fault that recurs on
     * every ~50 Hz sample cannot flood the log. See [onSensorChanged].
     *
     * @Volatile: written and read only on the sensor thread, but kept volatile for
     * consistency with the other cross-thread fields here.
     */
    @Volatile private var lastSensorErrorLogMs = 0L

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
        // Safe to mutate the ring here — the listener is unregistered, so no sensor
        // callback can be inside vectorBuffer.add(...) concurrently. Also reset the
        // invalidation floor so a fresh start() after a stop() has no stale floor.
        vectorBuffer.clear()
        vectorRingFloorMs = Long.MIN_VALUE
        accelStillSinceMs = 0L
        lastGyroMag = 0.0
    }

    /**
     * Standard deviation of the magnitudes in [varianceBuffer] (~5 s window).
     *
     * Mirrors `CrashDetectionManager.accelStdDev()` exactly: returns 0 if fewer than 10
     * samples have been collected, else the population std-dev. O(1) via the running
     * sum/sum-sq accumulators in [DoubleRingBuffer.stdDev]; previous implementation
     * was O(N=250) per call, which mattered after Task 6 wired this call into the
     * 50 Hz cruising-gate path.
     */
    fun accelStdDev(): Double {
        val buf = varianceBuffer
        if (buf.size < 10) return 0.0
        return buf.stdDev()
    }

    /**
     * Snapshot of the current 3-sample magnitude buffer in chronological order. Used by
     * the calibration logger's `buf=` field on IMPACT_ENTER. Returns an immutable copy
     * so callers cannot mutate the internal deque.
     */
    fun magnitudeBufferSnapshot(): List<Double> = magnitudeBuffer.snapshot()

    /**
     * The pre-impact orientation reference for an impact detected at [impactTsMs].
     * Snapshots the vector ring and delegates to the pure [PreImpactReference.compute],
     * passing [vectorRingFloorMs] so any entry captured before the last
     * [invalidateVectorRing] call is ignored.
     * Called once per impact event (rare) — the O(capacity) snapshot is negligible.
     */
    fun preImpactReference(impactTsMs: Long): PreImpactRef =
        PreImpactReference.compute(
            vectorBuffer.snapshot(),
            impactTsMs,
            notBeforeMs = vectorRingFloorMs,
        )

    /**
     * Invalidate the pre-impact vector ring. Called from the **main thread** when the
     * ride pauses.
     *
     * Lock-free: this does NOT mutate the ring's `head`/`size` — it only raises the
     * [vectorRingFloorMs] floor to the current instant. The sensor thread therefore
     * stays the sole mutator of the ring and the 50 Hz hot path is untouched, so there
     * is no data race even though the accelerometer listener stays registered across a
     * ride pause.
     *
     * Effect: every pre-impact reference computed afterwards ignores samples captured
     * before this instant. Because the listener stays registered, the ring continues
     * filling with the bike's stationary samples during the pause. After a pause
     * longer than the ~2.25 s averaging window (`GUARD_MS + WINDOW_MS`), those
     * stationary samples fill the ring and a valid reference is available essentially
     * immediately after resume. The "impact yields an invalid reference" outcome
     * applies to a cold start or to an impact within ~2.25 s of a very brief pause
     * where the ring does not yet hold enough post-floor samples.
     *
     * The reader stays registered across a pause, so the ring is NOT cleared by [stop]
     * in that case — only [stop] (which unregisters the listener first) actually clears
     * the ring.
     */
    fun invalidateVectorRing() {
        vectorRingFloorMs = clock.nowMs()
    }

    /** Test-only: exercise the buffering path without a real SensorEvent. */
    internal fun pushAccelForTest(x: Float, y: Float, z: Float, tsMs: Long) {
        vectorBuffer.add(x.toDouble(), y.toDouble(), z.toDouble(), tsMs)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        // Hard guard around the entire 50 Hz dispatch path. A single uncaught
        // exception here — in processAccel/processGyro, in the downstream onSample
        // callback, or in a calibration-log formatting lambda — would otherwise
        // propagate out of onSensorChanged and permanently, silently stop crash
        // detection for the rest of the ride. Catching Throwable and returning
        // means detection SURVIVES the bad sample and keeps processing subsequent
        // ones. The error log is rate-limited to at most once per ~5 s so a fault
        // that recurs on every sample cannot flood the log.
        try {
            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> processGyro(event)
                Sensor.TYPE_ACCELEROMETER -> processAccel(event)
            }
        } catch (t: Throwable) {
            val now = clock.nowMs()
            if (now - lastSensorErrorLogMs > SENSOR_ERROR_LOG_INTERVAL_MS) {
                lastSensorErrorLogMs = now
                Timber.e(t, "Exception on sensor thread — sample dropped, detection continues")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun processGyro(event: SensorEvent) {
        // Defensive: the Android contract guarantees ≥3 values for gyro, but an
        // exotic HAL returning fewer would throw ArrayIndexOutOfBoundsException here.
        if (event.values.size < 3) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        lastGyroMag = sqrt((x * x + y * y + z * z).toDouble())
    }

    private fun processAccel(event: SensorEvent) {
        // Defensive: the Android contract guarantees ≥3 values for the accelerometer,
        // but an exotic HAL returning fewer would throw ArrayIndexOutOfBoundsException.
        if (event.values.size < 3) return
        // Snapshot the clock once so the vector-ring timestamp and the emitted
        // SensorSample.timestampMs describe the SAME instant for this sample.
        val nowMs = clock.nowMs()
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
            accelStillSinceMs = nowMs
        }

        // Sliding 3-sample average (~60 ms at 50 Hz) — same as production.
        magnitudeBuffer.add(rawMagnitude)
        val smoothedMagnitude = magnitudeBuffer.average()

        // Terrain-noise variance buffer (250 samples ≈ 5 s @ 50 Hz). std-dev is
        // computed only on demand via [accelStdDev], never on the hot path.
        varianceBuffer.add(rawMagnitude)

        // Pre-impact orientation ring — raw X/Y/Z plus timestamp.
        vectorBuffer.add(x.toDouble(), y.toDouble(), z.toDouble(), nowMs)

        // Production has no rolling peak window — its peak detector compares the raw
        // single sample against the peak threshold. Emit peakMagnitude == rawMagnitude
        // so CrashStateMachine reproduces the existing behaviour exactly.
        onSample(
            SensorSample(
                rawMagnitude = rawMagnitude,
                smoothedMagnitude = smoothedMagnitude,
                peakMagnitude = rawMagnitude,
                gyroMag = lastGyroMag,
                timestampMs = nowMs,
                accelX = x.toDouble(),
                accelY = y.toDouble(),
                accelZ = z.toDouble(),
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

        /** Capacity of the pre-impact vector ring (~3 s at 50 Hz). */
        const val PRE_IMPACT_RING_CAPACITY = 150

        /**
         * Minimum interval between sensor-thread exception logs in [onSensorChanged].
         * Rate-limits the error log so a fault that recurs on every ~50 Hz sample
         * cannot flood the log.
         */
        const val SENSOR_ERROR_LOG_INTERVAL_MS = 5_000L
    }
}

/**
 * Fixed-capacity primitive-double ring buffer used by [SensorReader] for the
 * smoothed-magnitude window and the terrain-noise variance window. Replaces
 * `ArrayDeque<Double>` to eliminate autoboxing on the 50 Hz sensor thread.
 *
 * NOT thread-safe — [SensorReader]'s buffers are touched only from the sensor
 * callback thread, so no synchronisation is needed.
 */
internal class DoubleRingBuffer(@PublishedApi internal val capacity: Int) {
    init { require(capacity > 0) { "capacity must be positive" } }
    @PublishedApi internal val data = DoubleArray(capacity)
    @PublishedApi internal var head = 0      // index of oldest element
    var size: Int = 0
        internal set

    /**
     * Incrementally maintained sum and sum-of-squares of the live elements.
     * Lets [stdDev] run in O(1) instead of O(N). The orientation-baseline
     * cruising gate that originally drove this call on every sample (~50 Hz)
     * has been removed; [accelStdDev] is now invoked only from the
     * rate-limited PERIODIC calibration log and the IMPACT_ENTER log, so
     * O(1) is a nice-to-have rather than a strict hot-path requirement.
     *
     * Numerical note: `variance = sumSq/N - mean*mean` is the textbook
     * "two-pass" formula and suffers catastrophic cancellation when the
     * variance is tiny relative to mean*mean. For accelerometer magnitudes
     * (mean ≈ 9.81 m/s², variance ≈ 0.01-2 m²/s⁴) we lose ~2-3 decimal
     * digits of precision — acceptable for a noise metric used only in
     * calibration logs. Welford's incremental algorithm would be more
     * accurate but is much harder to apply with a fixed-window ring (every
     * eviction requires a full revisit), so we keep the simple form and
     * `coerceAtLeast(0.0)` the variance to guard against tiny negative
     * results when std-dev is essentially zero.
     */
    internal var runningSum: Double = 0.0
        private set
    internal var runningSumSq: Double = 0.0
        private set

    /** Append [value]; evicts the oldest when the buffer is full. O(1). */
    fun add(value: Double) {
        val valueSq = value * value
        if (size < capacity) {
            data[(head + size) % capacity] = value
            size++
            runningSum += value
            runningSumSq += valueSq
        } else {
            val evicted = data[head]
            data[head] = value
            head = (head + 1) % capacity
            runningSum += value - evicted
            runningSumSq += valueSq - evicted * evicted
        }
    }

    fun clear() {
        head = 0
        size = 0
        runningSum = 0.0
        runningSumSq = 0.0
    }

    /** Mean of live elements, or `0.0` when empty. Matches `Iterable<Double>.average()` semantics. */
    fun average(): Double {
        if (size == 0) return 0.0
        return runningSum / size
    }

    /**
     * Population std-dev of live elements in O(1) using the running
     * sum/sum-sq accumulators. Returns `0.0` for an empty buffer.
     * See the numerical-stability note on [runningSum] for caveats.
     */
    fun stdDev(): Double {
        if (size == 0) return 0.0
        val mean = runningSum / size
        val variance = (runningSumSq / size - mean * mean).coerceAtLeast(0.0)
        return sqrt(variance)
    }

    /** Snapshot of live elements in insertion order. Allocates — call only off the hot path. */
    fun snapshot(): List<Double> {
        if (size == 0) return emptyList()
        val out = ArrayList<Double>(size)
        forEach { out.add(it) }
        return out
    }

    /** Inline iteration in insertion order; primitive-double param means no boxing. */
    inline fun forEach(action: (Double) -> Unit) {
        val n = size
        val h = head
        val cap = capacity
        val arr = data
        var i = 0
        while (i < n) {
            action(arr[(h + i) % cap])
            i++
        }
    }
}

/**
 * Fixed-capacity ring of timestamped 3-axis vectors. Backed by primitive arrays —
 * zero allocation per [add] on the 50 Hz sensor thread. NOT thread-safe; touched
 * only from the sensor callback thread, like [DoubleRingBuffer].
 */
internal class Vec3RingBuffer(private val capacity: Int) {
    init { require(capacity > 0) { "capacity must be positive" } }
    private val xs = DoubleArray(capacity)
    private val ys = DoubleArray(capacity)
    private val zs = DoubleArray(capacity)
    private val ts = LongArray(capacity)
    private var head = 0
    private var size = 0

    fun add(x: Double, y: Double, z: Double, tsMs: Long) {
        // When full, (head + size) % capacity == head — the write lands on the
        // oldest slot, which head then vacates. When not full, it is the next free slot.
        val idx = (head + size) % capacity
        if (size < capacity) {
            size++
        } else {
            head = (head + 1) % capacity
        }
        xs[idx] = x; ys[idx] = y; zs[idx] = z; ts[idx] = tsMs
    }

    fun clear() { head = 0; size = 0 }

    /** Snapshot of live entries in insertion order. Allocates — call off the hot path. */
    fun snapshot(): List<TimedVec3> {
        val out = ArrayList<TimedVec3>(size)
        var i = 0
        while (i < size) {
            val idx = (head + i) % capacity
            out.add(TimedVec3(xs[idx], ys[idx], zs[idx], ts[idx]))
            i++
        }
        return out
    }
}
