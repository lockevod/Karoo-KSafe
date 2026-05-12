package com.enderthor.kSafe.extension.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        job?.cancel()
        job = scope.launch {
            while (true) {
                delay(pollIntervalMs)
                evaluate()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        startedAtMs = 0L
    }

    fun onSpeedUpdate(speedKmh: Double) {
        if (speedKmh <= 0.0) {
            if (startedAtMs == 0L) startedAtMs = clock.nowMs()
        } else {
            startedAtMs = 0L
        }
    }

    fun onPause() {
        startedAtMs = 0L
    }

    private fun evaluate() {
        val started = startedAtMs
        if (started == 0L) return

        val now = clock.nowMs()
        val elapsedMs = now - started
        if (elapsedMs < stoppedMinutesRequired * 60_000L) return

        val stillSince = accelStillSinceProvider()
        val stillStableFor = if (stillSince > 0L) now - stillSince else 0L
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
        startedAtMs = 0L
        onConfirm()
    }

    companion object {
        const val POLL_INTERVAL_MS: Long = 30_000L
        const val SPEED_DROP_ACCEL_STILL_MS: Long = 60_000L
    }
}
