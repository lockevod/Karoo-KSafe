package com.enderthor.kSafe.extension.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

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

    fun start(stoppedMinutesRequired: Int): Unit = TODO()
    fun stop(): Unit = TODO()
    fun onSpeedUpdate(speedKmh: Double): Unit = TODO()
    fun onPause(): Unit = TODO()

    companion object {
        const val POLL_INTERVAL_MS: Long = 30_000L
        const val SPEED_DROP_ACCEL_STILL_MS: Long = 60_000L
    }
}
