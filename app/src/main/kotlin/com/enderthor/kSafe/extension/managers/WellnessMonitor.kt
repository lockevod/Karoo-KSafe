package com.enderthor.kSafe.extension.managers

import com.enderthor.kSafe.data.EmergencyReason
import com.enderthor.kSafe.data.KSafeConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Sustained-high-HR monitor. Single rule:
 *
 *   HR has stayed >= [KSafeConfig.wellnessHighHrThreshold] continuously for at least
 *   [KSafeConfig.wellnessHighHrDurationMinutes] minutes. Any drop below the threshold
 *   resets the streak (continuous, not cumulative).
 *
 * Cooldown after firing is `wellnessHighHrDurationMinutes` (one mental model — the
 * configured duration drives both the streak length and the inter-alert gap). The
 * re-arm rule (`hrAboveThresholdSinceMs = 0L` after firing) further requires HR to
 * fall below the threshold and rise again before another alert can accumulate.
 */
class WellnessMonitor(
    private val scope: CoroutineScope,
    private val onIncident: (EmergencyReason) -> Unit,
    private val calibLogger: CalibrationLogger? = null,
) {

    private val HR_STALE_MS    = 15_000L
    private val MONITOR_TICK_MS = 30_000L

    @Volatile private var currentHrBpm           = 0
    @Volatile private var lastHrUpdateMs         = 0L
    @Volatile private var hrAboveThresholdSinceMs = 0L
    @Volatile private var lastTriggerMs          = 0L

    private var monitorJob: Job? = null
    @Volatile private var config = KSafeConfig()

    fun start(config: KSafeConfig) {
        this.config = config
        if (!config.wellnessEnabled) return
        monitorJob?.cancel()
        hrAboveThresholdSinceMs = 0L
        lastTriggerMs = 0L
        monitorJob = scope.launch {
            while (true) {
                delay(MONITOR_TICK_MS)
                tick()
            }
        }
        Timber.d("WellnessMonitor started (threshold=${config.wellnessHighHrThreshold}, duration=${config.wellnessHighHrDurationMinutes}min)")
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        Timber.d("WellnessMonitor stopped")
    }

    fun updateConfig(config: KSafeConfig) {
        val wasEnabled = this.config.wellnessEnabled
        this.config = config
        if (!wasEnabled && config.wellnessEnabled) start(config)
        else if (wasEnabled && !config.wellnessEnabled) stop()
    }

    fun updateHr(bpm: Int) {
        val now = System.currentTimeMillis()
        currentHrBpm = bpm
        lastHrUpdateMs = now
        // Maintain the streak start. Reset when HR drops below threshold.
        if (bpm >= config.wellnessHighHrThreshold) {
            if (hrAboveThresholdSinceMs == 0L) hrAboveThresholdSinceMs = now
        } else {
            hrAboveThresholdSinceMs = 0L
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        if (now - lastHrUpdateMs > HR_STALE_MS) return        // sensor silent
        if (hrAboveThresholdSinceMs == 0L) return             // no active streak
        val cooldownMs = config.wellnessHighHrDurationMinutes * 60_000L
        if (now - lastTriggerMs < cooldownMs) return          // cooldown active
        val sustainedFor = now - hrAboveThresholdSinceMs
        if (sustainedFor < cooldownMs) return                 // duration not reached

        val sustainedMin = sustainedFor / 60_000L
        Timber.d(">>> WELLNESS_HIGH_HR fired: bpm=$currentHrBpm sustained=${sustainedMin}min")
        calibLogger?.log(CalibrationLogger.Event.WELLNESS_FIRED) {
            "bpm=$currentHrBpm,threshold=${config.wellnessHighHrThreshold},sustained_min=$sustainedMin,duration_setting=${config.wellnessHighHrDurationMinutes}"
        }
        lastTriggerMs = now
        hrAboveThresholdSinceMs = 0L  // re-arm
        onIncident(EmergencyReason.WELLNESS_HIGH_HR)
    }
}
