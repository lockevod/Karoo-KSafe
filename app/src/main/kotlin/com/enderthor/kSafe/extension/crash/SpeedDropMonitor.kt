package com.enderthor.kSafe.extension.crash

import com.enderthor.kSafe.extension.managers.CalibrationLogger
import com.enderthor.kSafe.extension.util.Clock
import com.enderthor.kSafe.extension.util.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Independent watchdog: confirms a crash when the bike has been at zero speed for
 * the configured number of minutes AND the accelerometer has been quiet long enough
 * to rule out the rider being still on the bike fiddling with controls.
 *
 * Routes its confirmation through a [cooldownGate] supplied by the manager so the
 * unified [CrashSource.SPEED_DROP] path cannot bypass the same cooldown that gates
 * the accelerometer pipeline.
 */
class SpeedDropMonitor(
    private val scope: CoroutineScope,
    private val clock: Clock = SystemClock,
    private val accelStillSinceProvider: () -> Long,
    private val cooldownGate: () -> Boolean,
    private val onConfirm: () -> Unit,
    private val calibLogger: CalibrationLogger? = null,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val accelStillRequiredMs: Long = SPEED_DROP_ACCEL_STILL_MS,
) {
    @Volatile private var startedAtMs: Long = 0L
    @Volatile private var stoppedMinutesRequired: Int = 5
    /** Peak speed seen inside the current zero-speed window. Stamped on every
     *  [onSpeedUpdate] inside the window, reset to 0 on every window open/close.
     *  Logged on SPEEDDROP_WIN_CLOSE so the calibration analyst can tell apart
     *  "true at-rest GPS jitter (< 1 km/h)" from "near-threshold hike-a-bike". */
    @Volatile private var maxSpeedInWindowKmh: Double = 0.0
    /** Trigger speed of the current window — value that first crossed below threshold. */
    @Volatile private var triggerSpeedKmh: Double = 0.0
    /** Whether the current window started under gpsStale=true (forced-zero path). */
    @Volatile private var triggerGpsStale: Boolean = false
    private var job: Job? = null

    /** True iff the monitor is currently inside a zero-speed window. Used only by tests. */
    fun isTracking(): Boolean = startedAtMs > 0L

    fun start(stoppedMinutesRequired: Int) {
        this.stoppedMinutesRequired = stoppedMinutesRequired
        // Reset the zero-speed window state from the previous session, otherwise the
        // first poll of the new ride could find a stale `startedAtMs` (carried over from
        // an old session that ended mid-zero-speed) and fire a spurious SPEED_DROP within
        // the first 30 s of recording.
        startedAtMs = 0L
        maxSpeedInWindowKmh = 0.0
        triggerSpeedKmh = 0.0
        triggerGpsStale = false
        job?.cancel()
        job = scope.launch {
            while (true) {
                delay(pollIntervalMs)
                // H2 — defensive try/catch around evaluate(). The watchdog is the
                // accelerometer-pipeline backstop for crashes where the impact
                // spike is missed or below threshold. A single uncaught throw from
                // evaluate (e.g. an unexpected exception in the cooldownGate lambda
                // during a service-scope teardown race, a calibration-log formatting
                // edge case) would terminate the polling coroutine, silently
                // disabling the watchdog for the rest of the ride. SupervisorJob
                // isolates the throw to this scope but does not auto-restart the
                // loop. CancellationException is re-thrown so structured-concurrency
                // shutdown of the parent scope still cancels us cleanly.
                try {
                    evaluate()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "SpeedDropMonitor.evaluate threw — continuing watchdog loop")
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        if (startedAtMs > 0L) closeWindow("stopped", elapsedMs = clock.monotonicMs() - startedAtMs)
        startedAtMs = 0L
    }

    /**
     * @param speedKmh raw SDK speed reading
     * @param gpsStale `true` when the manager detected that the SDK is repeating the
     *   last known value (GPS lock lost). Treat the reading as zero in that case —
     *   without this the watchdog can never start a zero-speed window for a rider who
     *   crashed in a tunnel / dense forest, because the SDK keeps emitting the last
     *   pre-crash speed bit-exact and the `speedKmh > 0` branch always wins.
     *
     * Uses `< SPEED_DROP_WINDOW_KMH` (3.5 km/h) rather than `<= 0.0` because consumer
     * GPS chipsets commonly report 1–3 km/h of residual noise on a stationary bike
     * even with a fresh fix (gpsStale=false). A bit-exact zero requirement would
     * silently lose the accelerometer-only backstop for those riders. 3.5 km/h sits
     * in the valley between the GPS-noise distribution (mostly < 2 km/h, worst-case
     * multipath ~3 km/h) and the slow hike-a-bike distribution (median ~4 km/h).
     * The `stoppedMinutesRequired` window (typically 5 min) plus the 60 s
     * accel-stillness gate already prevent coasting / red-light FPs from confirming.
     */
    fun onSpeedUpdate(speedKmh: Double, gpsStale: Boolean = false) {
        val effective = if (gpsStale) 0.0 else speedKmh
        if (effective < SPEED_DROP_WINDOW_KMH) {
            // D2 — use monotonic time for the zero-speed window so an NTP step or
            // user date change cannot make the elapsed-since-stop computation go
            // negative (silently delaying confirm) or jump forward (firing a
            // premature confirm). Read with the same domain in [evaluate].
            if (startedAtMs == 0L) {
                startedAtMs = clock.monotonicMs()
                triggerSpeedKmh = speedKmh
                triggerGpsStale = gpsStale
                maxSpeedInWindowKmh = speedKmh
                calibLogger?.log(CalibrationLogger.Event.SPEEDDROP_WIN_START) {
                    "trigger_speed_kmh=%.2f,gps_stale=$gpsStale,threshold_kmh=%.1f"
                        .format(speedKmh, SPEED_DROP_WINDOW_KMH)
                }
            } else if (speedKmh > maxSpeedInWindowKmh) {
                maxSpeedInWindowKmh = speedKmh
            }
        } else {
            if (startedAtMs > 0L) {
                val elapsedMs = clock.monotonicMs() - startedAtMs
                closeWindow("speed_recovered", elapsedMs, recoveredAtKmh = speedKmh)
            }
            startedAtMs = 0L
        }
    }

    fun onPause() {
        if (startedAtMs > 0L) closeWindow("paused", elapsedMs = clock.monotonicMs() - startedAtMs)
        startedAtMs = 0L
    }

    private fun closeWindow(reason: String, elapsedMs: Long, recoveredAtKmh: Double? = null) {
        calibLogger?.log(CalibrationLogger.Event.SPEEDDROP_WIN_CLOSE) {
            val recovered = recoveredAtKmh?.let { ",recovered_at_kmh=%.2f".format(it) } ?: ""
            "reason=$reason,elapsed_ms=$elapsedMs,trigger_speed_kmh=%.2f,max_speed_kmh=%.2f,gps_stale=$triggerGpsStale$recovered"
                .format(triggerSpeedKmh, maxSpeedInWindowKmh)
        }
        maxSpeedInWindowKmh = 0.0
        triggerSpeedKmh = 0.0
        triggerGpsStale = false
    }

    private fun evaluate() {
        val started = startedAtMs
        if (started == 0L) return

        // D2 — paired with the monotonic write in onSpeedUpdate. Two `now` values
        // are needed because [accelStillSinceProvider] returns a wall-clock timestamp
        // from SensorReader (clock.nowMs). Mixing monotonic + wall-clock would produce
        // a huge negative `stillStableFor` on real hardware (monotonicMs is
        // elapsed-since-boot ≈ 1e6–1e9 ms; nowMs is the epoch wall-clock ≈ 1.7e12 ms)
        // and the still-stability gate would never open.
        val monoNow = clock.monotonicMs()
        val wallNow = clock.nowMs()
        val elapsedMs = monoNow - started
        if (elapsedMs < stoppedMinutesRequired * 60_000L) return

        val stillSince = accelStillSinceProvider()
        val stillStableFor = if (stillSince > 0L) wallNow - stillSince else 0L
        if (stillStableFor < accelStillRequiredMs) {
            calibLogger?.log(CalibrationLogger.Event.SPEEDDROP_EVAL) {
                "elapsed_ms=$elapsedMs,still_stable_ms=$stillStableFor,need_ms=$accelStillRequiredMs,accel_ok=false"
            }
            return
        }

        if (!cooldownGate()) {
            calibLogger?.log(CalibrationLogger.Event.SPEEDDROP_EVAL) {
                "elapsed_ms=$elapsedMs,still_stable_ms=$stillStableFor,cooldown_blocked=true"
            }
            return
        }

        calibLogger?.log(CalibrationLogger.Event.SPEEDDROP_EVAL) {
            "elapsed_ms=$elapsedMs,still_stable_ms=$stillStableFor,confirm=true"
        }
        closeWindow("confirmed", elapsedMs)
        startedAtMs = 0L
        onConfirm()
    }

    companion object {
        const val POLL_INTERVAL_MS: Long = 30_000L
        const val SPEED_DROP_ACCEL_STILL_MS: Long = 60_000L

        /**
         * Threshold below which the watchdog starts its zero-speed window. Chosen by
         * sitting in the valley between two real-world distributions:
         *  - Consumer GPS jitter on a stationary bike (most chipsets < 2 km/h; worst-case
         *    urban-canyon / dense-canopy multipath ~3 km/h). Must be ABOVE this to keep
         *    the accelerometer-only backstop alive for riders whose GPS reports noise.
         *  - Slow hike-a-bike pushing uphill (median ~4 km/h, 5th percentile ~2.5 km/h).
         *    Must be BELOW this to avoid a false positive when the rider walks the bike.
         *
         * 3.5 km/h is the deliberate midpoint. Calibration telemetry
         * ([CalibrationLogger.Event.SPEEDDROP_WIN_START] /
         * [CalibrationLogger.Event.SPEEDDROP_WIN_CLOSE]) records every window so this
         * value can be re-tuned from real-world ride data without code-only guessing.
         */
        const val SPEED_DROP_WINDOW_KMH: Double = 3.5
    }
}
