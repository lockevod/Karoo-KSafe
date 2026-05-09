package com.enderthor.kSafe.extension.managers

import android.content.Context
import com.enderthor.kSafe.R
import com.enderthor.kSafe.data.KSafeConfig
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Carb consumption tracker. Integrates a per-second target rate (modulated by the rider's current
 * intensity zone via [IntensityZoneCalculator]), tracks the rider's logged intake, and dispatches
 * [InRideAlert]s when either the deficit threshold or the time-since-last-log threshold is crossed.
 *
 * Both alert mechanisms are independently configurable and combinable; a single 5-minute cooldown
 * prevents both from firing within seconds of each other.
 *
 * The tracker is decoupled from [EmergencyManager] — fueling alerts are informational, not cancellable
 * emergencies, so they bypass the countdown / contact-alert pipeline and go straight to the Karoo's
 * in-ride alert overlay.
 */
class CarbsTracker(
    private val scope: CoroutineScope,
    private val karooSystem: KarooSystemService,
    private val context: Context,
    private val calibLogger: CalibrationLogger? = null,
) {

    // ─── Constants ───────────────────────────────────────────────────────────
    private val MONITOR_TICK_MS         = 5_000L
    private val ALERT_COOLDOWN_MS       = 5L * 60_000L
    private val PERIODIC_LOG_INTERVAL_MS = 120_000L

    private val BEEP_LONG = PlayBeepPattern(listOf(
        PlayBeepPattern.Tone(frequency = 880, durationMs = 800)
    ))
    private val ALERT_BG_COLOR = 0xFFE65100.toInt()  // amber
    private val ALERT_TX_COLOR = 0xFFFFFFFF.toInt()  // white
    private val AUTO_DISMISS_MS = 10_000L

    // ─── Live data (push from KSafeExtension) ────────────────────────────────
    @Volatile private var lastUserProfile: UserProfile? = null
    @Volatile private var lastHrBpm: Int? = null
    @Volatile private var lastPowerW: Int? = null

    // ─── Session state (reset by start()) ────────────────────────────────────
    @Volatile private var cumTargetG = 0f
    @Volatile private var cumLoggedG = 0
    @Volatile private var sessionStartMs = 0L
    @Volatile private var lastTickMs = 0L
    @Volatile private var lastLogMs = 0L
    @Volatile private var lastAlertMs = 0L
    @Volatile private var lastZoneSnapshot = ZoneSnapshot(ZoneSource.NONE, -1, 0, 1f)
    @Volatile private var lastPeriodicLogMs = 0L

    // ─── Status flow consumed by CarbStatusDataType ──────────────────────────
    private val _statusFlow = MutableStateFlow(CarbStatus(0, 0, 0, 25, lastZoneSnapshot))
    val statusFlow: StateFlow<CarbStatus> = _statusFlow.asStateFlow()

    @Volatile private var config = KSafeConfig()
    private var monitorJob: Job? = null

    // ─── Public API ──────────────────────────────────────────────────────────

    fun start(config: KSafeConfig) {
        this.config = config
        if (!config.carbsTrackerEnabled) return
        monitorJob?.cancel()
        val now = System.currentTimeMillis()
        cumTargetG = 0f
        cumLoggedG = 0
        sessionStartMs = now
        lastTickMs = 0L                  // 0 = "no previous tick"; first tick won't accumulate
        lastLogMs = now                  // first time-based alert counts from session start
        lastAlertMs = 0L
        lastPeriodicLogMs = 0L
        lastZoneSnapshot = ZoneSnapshot(ZoneSource.NONE, -1, 0, 1f)
        publishStatus()
        monitorJob = scope.launch {
            while (true) { delay(MONITOR_TICK_MS); tick() }
        }
        Timber.d("CarbsTracker started, target=${config.carbTargetGperHour} g/h")
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        Timber.d("CarbsTracker stopped")
        // State is intentionally retained so getSummary() / statusFlow remain readable
        // for the post-ride summary. Reset happens on the next start().
    }

    fun updateConfig(config: KSafeConfig) {
        val wasEnabled = this.config.carbsTrackerEnabled
        this.config = config
        if (!wasEnabled && config.carbsTrackerEnabled) start(config)
        else if (wasEnabled && !config.carbsTrackerEnabled) stop()
    }

    fun updateUserProfile(p: UserProfile) { lastUserProfile = p }
    fun updateHr(bpm: Int)                { lastHrBpm = bpm }
    fun updatePower(w: Int)               { lastPowerW = w }

    /** Log a single tap on slot 1, 2 or 3. Adds the configured grams to the cumulative log. */
    fun logEntry(slot: Int) {
        val grams = when (slot) {
            1 -> config.carb1Grams
            2 -> config.carb2Grams
            3 -> config.carb3Grams
            else -> return
        }
        cumLoggedG += grams
        lastLogMs = System.currentTimeMillis()
        publishStatus()
        calibLogger?.log(CalibrationLogger.Event.FUELING_CARB_LOGGED) {
            "slot=$slot,grams=$grams,cum_logged=$cumLoggedG,cum_target=${cumTargetG.toInt()}"
        }
    }

    fun getStatus(): CarbStatus = _statusFlow.value

    fun getSummary(): CarbSummary = CarbSummary(
        cumTargetG = cumTargetG.toInt(),
        cumLoggedG = cumLoggedG,
        deficitG = (cumTargetG - cumLoggedG).toInt(),
        percentageHit = if (cumTargetG > 0f) ((cumLoggedG / cumTargetG) * 100f).toInt() else 0,
    )

    // ─── Internals ───────────────────────────────────────────────────────────

    private fun tick() {
        val now = System.currentTimeMillis()
        val zone = IntensityZoneCalculator.calculate(lastUserProfile, lastHrBpm, lastPowerW)
        lastZoneSnapshot = zone

        if (lastTickMs != 0L) {
            val dtSec = (now - lastTickMs) / 1000f
            val ratePerSec = config.carbTargetGperHour / 3600f
            cumTargetG += dtSec * ratePerSec * zone.multiplier
        }
        lastTickMs = now

        publishStatus()
        evaluateDeficitAlert(now)
        evaluateTimeAlert(now)
        maybePeriodicLog(now)
    }

    private fun publishStatus() {
        _statusFlow.value = CarbStatus(
            cumTargetG = cumTargetG.toInt(),
            cumLoggedG = cumLoggedG,
            deficitG = (cumTargetG - cumLoggedG).toInt(),
            deficitThresholdG = config.carbDeficitThresholdG,
            zoneSnapshot = lastZoneSnapshot,
        )
    }

    private fun evaluateDeficitAlert(now: Long) {
        if (!config.carbDeficitAlertEnabled) return
        val deficit = (cumTargetG - cumLoggedG).toInt()
        if (deficit < config.carbDeficitThresholdG) return
        if (now - lastAlertMs < ALERT_COOLDOWN_MS) return
        fireAlert(source = "deficit", deficit = deficit, elapsedMin = (now - lastLogMs) / 60_000)
    }

    private fun evaluateTimeAlert(now: Long) {
        if (!config.carbTimeAlertEnabled) return
        val intervalMs = config.carbTimeIntervalMin * 60_000L
        if (now - lastLogMs < intervalMs) return
        if (now - lastAlertMs < ALERT_COOLDOWN_MS) return
        val deficit = (cumTargetG - cumLoggedG).toInt()
        fireAlert(source = "time", deficit = deficit, elapsedMin = (now - lastLogMs) / 60_000)
    }

    private fun fireAlert(source: String, deficit: Int, elapsedMin: Long) {
        lastAlertMs = System.currentTimeMillis()
        val detail = if (source == "deficit") "Behind by ${deficit}g"
                     else                     "$elapsedMin min since last log"
        karooSystem.dispatch(BEEP_LONG)
        karooSystem.dispatch(InRideAlert(
            id = "ksafe-carb-alert-$source",
            icon = R.drawable.ic_ksafe,
            title = "Eat something", // TODO: revert in Task 12 — fueling_carb_alert_title
            detail = detail,
            autoDismissMs = AUTO_DISMISS_MS,
            backgroundColor = ALERT_BG_COLOR,
            textColor = ALERT_TX_COLOR,
        ))
        calibLogger?.log(CalibrationLogger.Event.FUELING_CARB_FIRED) {
            "source=$source,deficit_g=$deficit,since_log_min=$elapsedMin,cum_target=${cumTargetG.toInt()},cum_logged=$cumLoggedG,zone=${lastZoneSnapshot.source}/${lastZoneSnapshot.index}/${lastZoneSnapshot.total},multiplier=%.2f".format(lastZoneSnapshot.multiplier)
        }
        Timber.d(">>> Carb alert fired ($source): deficit=${deficit}g elapsed=${elapsedMin}min")
    }

    private fun maybePeriodicLog(now: Long) {
        if (calibLogger == null || !calibLogger.isEnabled) return
        if (now - lastPeriodicLogMs < PERIODIC_LOG_INTERVAL_MS) return
        lastPeriodicLogMs = now
        val deficit = (cumTargetG - cumLoggedG).toInt()
        calibLogger.log(CalibrationLogger.Event.FUELING_CARB_PERIODIC) {
            "cum_target=${cumTargetG.toInt()},cum_logged=$cumLoggedG,deficit=$deficit,zone_source=${lastZoneSnapshot.source},zone_idx=${lastZoneSnapshot.index},zone_total=${lastZoneSnapshot.total},multiplier=%.2f,hr=${lastHrBpm ?: -1},power=${lastPowerW ?: -1}".format(lastZoneSnapshot.multiplier)
        }
    }
}

/**
 * Snapshot of the carb tracker state, published on every tick and on every logEntry.
 * The status data field collects [CarbsTracker.statusFlow] and renders accordingly.
 */
data class CarbStatus(
    val cumTargetG: Int,
    val cumLoggedG: Int,
    val deficitG: Int,
    val deficitThresholdG: Int,
    val zoneSnapshot: ZoneSnapshot,
)

/** Totals captured at end-of-ride for the post-ride summary InRideAlert. */
data class CarbSummary(
    val cumTargetG: Int,
    val cumLoggedG: Int,
    val deficitG: Int,
    val percentageHit: Int,
)
