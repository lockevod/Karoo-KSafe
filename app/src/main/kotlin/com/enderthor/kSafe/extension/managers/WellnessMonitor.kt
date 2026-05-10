package com.enderthor.kSafe.extension.managers

import com.enderthor.kSafe.data.EmergencyReason
import com.enderthor.kSafe.data.KSafeConfig
import io.hammerhead.karooext.models.UserProfile
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
    /** Latest Karoo user profile — needed when [KSafeConfig.wellnessUseMaxHrPercent] is true so
     *  the threshold can be computed as a percentage of the rider's max HR. */
    @Volatile private var lastUserProfile: UserProfile? = null

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
        val mode = if (config.wellnessUseMaxHrPercent) "%maxHr=${config.wellnessHighHrPercent}" else "abs=${config.wellnessHighHrThreshold}"
        Timber.d("WellnessMonitor started ($mode, duration=${config.wellnessHighHrDurationMinutes}min)")
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

    /** Latest Karoo profile — pushed from KSafeExtension's streamUserProfile() collector. */
    fun updateUserProfile(p: UserProfile) { lastUserProfile = p }

    fun updateHr(bpm: Int) {
        val now = System.currentTimeMillis()
        currentHrBpm = bpm
        lastHrUpdateMs = now
        // Maintain the streak start. Reset when HR drops below threshold.
        if (bpm >= effectiveThreshold()) {
            if (hrAboveThresholdSinceMs == 0L) hrAboveThresholdSinceMs = now
        } else {
            hrAboveThresholdSinceMs = 0L
        }
    }

    /**
     * Computes the threshold (bpm) that the streak compares against. Either:
     *  - `userProfile.maxHr * wellnessHighHrPercent / 100` when `wellnessUseMaxHrPercent` is true
     *    AND the profile is available with a valid maxHr (> 0)
     *  - the absolute `wellnessHighHrThreshold` otherwise (back-compat default).
     *
     * Falls back to the absolute value if the profile is missing — avoids "feature silently
     * disabled" when the SDK profile stream hasn't emitted yet.
     */
    private fun effectiveThreshold(): Int {
        if (config.wellnessUseMaxHrPercent) {
            val profile = lastUserProfile
            if (profile != null && profile.maxHr > 0) {
                return (profile.maxHr * config.wellnessHighHrPercent) / 100
            }
        }
        return config.wellnessHighHrThreshold
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
        val effective = effectiveThreshold()
        Timber.d(">>> WELLNESS_HIGH_HR fired: bpm=$currentHrBpm sustained=${sustainedMin}min")
        calibLogger?.log(CalibrationLogger.Event.WELLNESS_FIRED) {
            "bpm=$currentHrBpm,threshold=$effective,mode=${if (config.wellnessUseMaxHrPercent) "pct" else "abs"},sustained_min=$sustainedMin,duration_setting=${config.wellnessHighHrDurationMinutes}"
        }
        lastTriggerMs = now
        hrAboveThresholdSinceMs = 0L  // re-arm
        onIncident(EmergencyReason.WELLNESS_HIGH_HR)
    }
}
