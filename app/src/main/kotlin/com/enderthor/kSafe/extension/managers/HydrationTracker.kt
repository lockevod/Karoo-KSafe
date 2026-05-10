package com.enderthor.kSafe.extension.managers

import android.content.Context
import com.enderthor.kSafe.R
import com.enderthor.kSafe.data.KSafeConfig
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.PlayBeepPattern
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Hydration tracker. Same dual-mode alert structure as [CarbsTracker] but with a flat per-hour
 * target (no intensity multiplier). The rider compensates for hot weather by raising
 * [KSafeConfig.hydrationTargetMlPerHour] pre-ride.
 */
class HydrationTracker(
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

    // ─── Session state (reset by start()) ────────────────────────────────────
    @Volatile private var cumTargetMl = 0f
    @Volatile private var cumLoggedMl = 0
    @Volatile private var sessionStartMs = 0L
    @Volatile private var lastTickMs = 0L
    @Volatile private var lastLogMs = 0L
    @Volatile private var lastAlertMs = 0L
    @Volatile private var lastPeriodicLogMs = 0L

    @Volatile private var config = KSafeConfig()
    private var monitorJob: Job? = null

    // ─── Public API ──────────────────────────────────────────────────────────

    fun start(config: KSafeConfig) {
        this.config = config
        if (!config.hydrationTrackerEnabled) return
        // Same restart pattern as CarbsTracker — wait for the previous monitor to fully
        // stop inside the new coroutine to avoid late ticks producing spurious log rows.
        val oldJob = monitorJob
        val now = System.currentTimeMillis()
        cumTargetMl = 0f
        cumLoggedMl = 0
        sessionStartMs = now
        lastTickMs = 0L
        lastLogMs = now
        lastAlertMs = 0L
        lastPeriodicLogMs = 0L
        monitorJob = scope.launch {
            oldJob?.cancelAndJoin()
            while (true) { delay(MONITOR_TICK_MS); tick() }
        }
        Timber.d("HydrationTracker started, target=${config.hydrationTargetMlPerHour} ml/h")
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        Timber.d("HydrationTracker stopped")
        // State is intentionally retained so getSummary() / getStatus() remain readable
        // for the post-ride summary.
    }

    fun updateConfig(config: KSafeConfig) {
        val wasEnabled = this.config.hydrationTrackerEnabled
        this.config = config
        if (!wasEnabled && config.hydrationTrackerEnabled) start(config)
        else if (wasEnabled && !config.hydrationTrackerEnabled) stop()
    }

    /** Log a single tap on slot 1 or 2. Adds the configured millilitres to the cumulative log. */
    fun logEntry(slot: Int) {
        val ml = when (slot) {
            1 -> config.drink1Ml
            2 -> config.drink2Ml
            else -> return
        }
        cumLoggedMl += ml
        lastLogMs = System.currentTimeMillis()
        calibLogger?.log(CalibrationLogger.Event.FUELING_HYDRATION_LOGGED) {
            "slot=$slot,ml=$ml,cum_logged=$cumLoggedMl,cum_target=${cumTargetMl.toInt()}"
        }
    }

    /**
     * Builds an immutable snapshot of the current tracker state. Volatile field reads make
     * the snapshot internally consistent to within one tick's worth of integration.
     */
    fun getStatus(): HydrationStatus = HydrationStatus(
        cumTargetMl = cumTargetMl.toInt(),
        cumLoggedMl = cumLoggedMl,
        deficitMl = (cumTargetMl - cumLoggedMl).toInt(),
        deficitThresholdMl = config.hydrationDeficitThresholdMl,
    )

    fun getSummary(): HydrationSummary = HydrationSummary(
        cumTargetMl = cumTargetMl.toInt(),
        cumLoggedMl = cumLoggedMl,
        deficitMl = (cumTargetMl - cumLoggedMl).toInt(),
        percentageHit = if (cumTargetMl > 0f) ((cumLoggedMl / cumTargetMl) * 100f).toInt() else 0,
    )

    // ─── Internals ───────────────────────────────────────────────────────────

    private fun tick() {
        val now = System.currentTimeMillis()
        if (lastTickMs != 0L) {
            val dtSec = (now - lastTickMs) / 1000f
            val ratePerSec = config.hydrationTargetMlPerHour / 3600f
            cumTargetMl += dtSec * ratePerSec
        }
        lastTickMs = now

        evaluateDeficitAlert(now)
        evaluateTimeAlert(now)
        maybePeriodicLog(now)
    }

    private fun evaluateDeficitAlert(now: Long) {
        if (!config.hydrationDeficitAlertEnabled) return
        val deficit = (cumTargetMl - cumLoggedMl).toInt()
        if (deficit < config.hydrationDeficitThresholdMl) return
        if (now - lastAlertMs < ALERT_COOLDOWN_MS) return
        fireAlert("deficit", deficit, (now - lastLogMs) / 60_000)
    }

    private fun evaluateTimeAlert(now: Long) {
        if (!config.hydrationTimeAlertEnabled) return
        val intervalMs = config.hydrationTimeIntervalMin * 60_000L
        if (now - lastLogMs < intervalMs) return
        if (now - lastAlertMs < ALERT_COOLDOWN_MS) return
        fireAlert("time", (cumTargetMl - cumLoggedMl).toInt(), (now - lastLogMs) / 60_000)
    }

    private fun fireAlert(source: String, deficitMl: Int, elapsedMin: Long) {
        lastAlertMs = System.currentTimeMillis()
        val detail = if (source == "deficit") "Behind by ${deficitMl}ml"
                     else                     "$elapsedMin min since last log"
        karooSystem.dispatch(BEEP_LONG)
        karooSystem.dispatch(InRideAlert(
            id = "ksafe-hyd-alert-$source",
            icon = R.drawable.ic_ksafe,
            title = context.getString(R.string.fueling_hyd_alert_title),
            detail = detail,
            autoDismissMs = AUTO_DISMISS_MS,
            backgroundColor = ALERT_BG_COLOR,
            textColor = ALERT_TX_COLOR,
        ))
        calibLogger?.log(CalibrationLogger.Event.FUELING_HYDRATION_FIRED) {
            "source=$source,deficit_ml=$deficitMl,since_log_min=$elapsedMin,cum_target=${cumTargetMl.toInt()},cum_logged=$cumLoggedMl"
        }
        Timber.d(">>> Hydration alert fired ($source): deficit=${deficitMl}ml elapsed=${elapsedMin}min")
    }

    private fun maybePeriodicLog(now: Long) {
        if (calibLogger == null || !calibLogger.isEnabled) return
        if (now - lastPeriodicLogMs < PERIODIC_LOG_INTERVAL_MS) return
        lastPeriodicLogMs = now
        val deficit = (cumTargetMl - cumLoggedMl).toInt()
        calibLogger.log(CalibrationLogger.Event.FUELING_HYDRATION_PERIODIC) {
            "cum_target=${cumTargetMl.toInt()},cum_logged=$cumLoggedMl,deficit=$deficit"
        }
    }
}

/**
 * Snapshot of the hydration tracker state. The status data field polls [HydrationTracker.getStatus]
 * once per second; it is also safe to read on demand from any thread (Volatile field reads).
 */
data class HydrationStatus(
    val cumTargetMl: Int,
    val cumLoggedMl: Int,
    val deficitMl: Int,
    val deficitThresholdMl: Int,
)

/** Totals captured at end-of-ride for the post-ride summary InRideAlert. */
data class HydrationSummary(
    val cumTargetMl: Int,
    val cumLoggedMl: Int,
    val deficitMl: Int,
    val percentageHit: Int,
)
