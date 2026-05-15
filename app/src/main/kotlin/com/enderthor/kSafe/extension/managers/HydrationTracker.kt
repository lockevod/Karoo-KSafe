package com.enderthor.kSafe.extension.managers

import android.content.Context
import com.enderthor.kSafe.R
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.extension.util.ALERT_DETAIL_MAX_CHARS
import com.enderthor.kSafe.extension.util.ALERT_TITLE_MAX_CHARS
import com.enderthor.kSafe.extension.util.SweatConfidence
import com.enderthor.kSafe.extension.util.SweatEstimateInputs
import com.enderthor.kSafe.extension.util.estimateSweatRate
import com.enderthor.kSafe.extension.util.renderAlertText
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Hydration tracker. Same dual-mode alert structure as [CarbsTracker].
 *
 * Two target modes selectable via [KSafeConfig.hydrationDynamicEstimateEnabled]:
 *  - **Flat** (default): integrates [KSafeConfig.hydrationTargetMlPerHour] verbatim — the
 *    rider compensates for hot weather by bumping the per-hour target pre-ride.
 *  - **Dynamic**: feeds HR/power + weight + temperature + humidity into [SweatEstimator]
 *    every tick and integrates whatever rate the model returns. Biases high in hot
 *    conditions by design (under-targeting hydration is far more dangerous than
 *    over-targeting). See `references/health-fueling.md`.
 */
class HydrationTracker(
    private val scope: CoroutineScope,
    private val karooSystem: KarooSystemService,
    private val context: Context,
    private val calibLogger: CalibrationLogger? = null,
) {

    // ─── Constants ───────────────────────────────────────────────────────────
    // Tick at 15 s — alert cooldown is 5 min and target accumulation is monotonic, so
    // a coarser cadence loses < 1 % cumulative precision over a 5 h ride while halving
    // the wakeup count vs. the original 5 s. The deficit and time-alert thresholds
    // both have minute-level granularity downstream, so 15 s polling is fine.
    private val MONITOR_TICK_MS         = 15_000L

    /** See [CarbsTracker.MOVING_GATE_KMH] — same rationale and value for symmetry. */
    private val MOVING_GATE_KMH = 2.0

    /** See [CarbsTracker.SPEED_STALE_MS]. */
    private val SPEED_STALE_MS = 10_000L
    private val ALERT_COOLDOWN_MS       = 5L * 60_000L
    private val PERIODIC_LOG_INTERVAL_MS = 120_000L

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

    // ─── Dynamic-estimate inputs (push from KSafeExtension, all optional) ────
    // When [KSafeConfig.hydrationDynamicEstimateEnabled] is true the tick() integrator
    // queries [SweatEstimator] each tick instead of using the fixed target rate. These
    // fields are the latest known values from each stream; null means "no data yet".
    //
    // Threading note: each field is individually volatile so single reads are atomic, but
    // the snapshot tick() composes from them is NOT atomic across fields — HR can be
    // from tick N while power is from tick N+1. In practice the streams emit at <10 Hz
    // and the tick runs every MONITOR_TICK_MS (15 s), so the inconsistency window vs.
    // the integration step is negligible. The estimator is monotonic in each input
    // within its smooth band, so a mixed snapshot just lands between the "true" values
    // for adjacent ticks.
    @Volatile private var lastHrBpm: Int? = null
    @Volatile private var lastPowerW: Int? = null
    /** Latest speed reading in km/h. `null` until the SDK first emits — used by the
     *  movement gate in [tick] to skip integration when stationary. */
    @Volatile private var lastSpeedKmh: Double? = null
    /** See [CarbsTracker.lastSpeedChangeMs] — staleness tracking for the SDK's
     *  last-known-value behaviour when GPS lock is lost. */
    @Volatile private var lastSpeedChangeMs: Long = 0L
    @Volatile private var lastWeightKg: Double? = null
    @Volatile private var lastAmbientTempC: Double? = null
    @Volatile private var lastHumidityPct: Int? = null
    /** Most recent estimator output, exposed via [getStatus] for logging / future UI. */
    @Volatile private var lastSweatRateMlHr: Double = 0.0
    @Volatile private var lastSweatConfidence: SweatConfidence = SweatConfidence.LOW

    // ─── Per-slot undo state ────────────────────────────────────────────────
    // See [CarbsTracker] for the same pattern. Hydration currently uses slots 1..2
    // (slot 0 unused); sized to 4 for symmetry with CarbsTracker so a future third
    // hydration slot can be added without touching the bookkeeping.
    private val lastLoggedMlBySlot = IntArray(4)
    private val lastLogMsBeforeBySlot = LongArray(4)

    @Volatile private var config = KSafeConfig()
    private var monitorJob: Job? = null

    // ─── Status publisher (see CarbsTracker._statusFlow for the rationale) ──
    private val _statusFlow = MutableStateFlow<HydrationStatus?>(null)
    val statusFlow: StateFlow<HydrationStatus?> get() = _statusFlow

    private fun publishStatus() { _statusFlow.value = getStatus() }

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
        for (i in lastLoggedMlBySlot.indices) { lastLoggedMlBySlot[i] = 0; lastLogMsBeforeBySlot[i] = 0L }
        monitorJob = scope.launch {
            oldJob?.cancelAndJoin()
            while (true) { delay(MONITOR_TICK_MS); tick() }
        }
        calibLogger?.log(CalibrationLogger.Event.FUELING_HYDRATION_START) {
            "base_ml_h=${config.hydrationTargetMlPerHour}," +
                "dynamic=${config.hydrationDynamicEstimateEnabled}," +
                "deficit_alert=${config.hydrationDeficitAlertEnabled}," +
                "deficit_threshold_ml=${config.hydrationDeficitThresholdMl}," +
                "time_alert=${config.hydrationTimeAlertEnabled}," +
                "time_interval_min=${config.hydrationTimeIntervalMin}," +
                "time_initial_delay_min=${config.hydrationTimeInitialDelayMin}," +
                "beep=${config.hydBeepPattern}"
        }
        Timber.d("HydrationTracker started, target=${config.hydrationTargetMlPerHour} ml/h")
        publishStatus()
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        Timber.d("HydrationTracker stopped")
        // State is intentionally retained so getSummary() / getStatus() remain readable
        // for the post-ride summary.
        publishStatus()
    }

    /**
     * Re-launch the monitor without resetting accumulators. Used by the master-switch
     * mid-ride OFF→ON transition: the rider's cumulative target and logged volumes are
     * preserved so a brief toggle does not erase the ride's totals. The first tick after
     * resume skips integration (lastTickMs = 0L sentinel) so the OFF window is not
     * integrated as if it had been a ride segment.
     */
    fun resume(config: KSafeConfig) {
        this.config = config
        if (!config.hydrationTrackerEnabled) return
        val oldJob = monitorJob
        lastTickMs = 0L
        monitorJob = scope.launch {
            oldJob?.cancelAndJoin()
            while (true) { delay(MONITOR_TICK_MS); tick() }
        }
        Timber.d("HydrationTracker resumed (cumTargetMl=${cumTargetMl.toInt()}, cumLoggedMl=$cumLoggedMl)")
        publishStatus()
    }

    /**
     * Adopt a new config. The auto-start branch (`disabled → enabled`) requires
     * [isRecording] = true so a config flow emission at extension boot — or any
     * config save the rider makes while the bike is idle — does NOT spin up the
     * integration coroutine outside a ride. Without this gate the deficit field
     * grew steadily on the apps screen even though the rider had not pressed Start.
     *
     * Stop is always honoured regardless of ride state: an `enabled → disabled`
     * mid-ride must take effect immediately.
     */
    fun updateConfig(config: KSafeConfig, isRecording: Boolean) {
        val wasEnabled = this.config.hydrationTrackerEnabled
        this.config = config
        if (!wasEnabled && config.hydrationTrackerEnabled && isRecording) start(config)
        else if (wasEnabled && !config.hydrationTrackerEnabled) stop()
    }

    // ─── Dynamic-estimate input updaters ────────────────────────────────────
    // Called from KSafeExtension whenever a stream emits. All are no-ops unless
    // [KSafeConfig.hydrationDynamicEstimateEnabled] is true at tick time.

    fun updateHr(bpm: Int)            { lastHrBpm = bpm }
    fun updatePower(w: Int)           { lastPowerW = w }
    fun updateSpeed(kmh: Double) {
        val prev = lastSpeedKmh
        // See CarbsTracker.updateSpeed — stamp on value change OR explicit zero OR
        // bootstrap so a stopped rider isn't misclassified as GPS-stale.
        if (prev == null || prev != kmh || kmh == 0.0) {
            lastSpeedChangeMs = System.currentTimeMillis()
        }
        // Publish only on movement-gate crossings — see CarbsTracker.updateSpeed.
        val wasMoving = prev != null && prev >= MOVING_GATE_KMH
        val isMoving = kmh >= MOVING_GATE_KMH
        lastSpeedKmh = kmh
        if (wasMoving != isMoving) publishStatus()
    }
    fun updateUserProfile(p: UserProfile) {
        if (p.weight > 0) lastWeightKg = p.weight.toDouble()
    }
    fun updateAmbientTemp(c: Double)  { lastAmbientTempC = c }
    fun updateHumidity(pct: Int)      { lastHumidityPct = pct }

    /**
     * Log a single tap on slot 1 or 2. Adds the configured millilitres to the cumulative
     * log. Returns the ml actually added — see [CarbsTracker.logEntry] for the rationale.
     * Returns `0` for an invalid slot.
     */
    fun logEntry(slot: Int): Int {
        val ml = when (slot) {
            1 -> config.drink1Ml
            2 -> config.drink2Ml
            else -> return 0
        }
        // Save what we're about to mutate so an undo within the on-screen window can
        // reverse exactly this entry — same pattern as CarbsTracker.
        lastLogMsBeforeBySlot[slot] = lastLogMs
        lastLoggedMlBySlot[slot] = ml
        cumLoggedMl += ml
        lastLogMs = System.currentTimeMillis()
        calibLogger?.log(CalibrationLogger.Event.FUELING_HYDRATION_LOGGED) {
            "slot=$slot,ml=$ml,cum_logged=$cumLoggedMl,cum_target=${cumTargetMl.toInt()}"
        }
        publishStatus()
        return ml
    }

    /**
     * Reverse the most recent [logEntry] for [slot]. Returns the ml undone, or `0` if the
     * slot has nothing left to undo. See [CarbsTracker.undoLastForSlot] for the contract.
     */
    fun undoLastForSlot(slot: Int): Int {
        if (slot !in 1..2) return 0
        val ml = lastLoggedMlBySlot[slot]
        if (ml <= 0) return 0
        cumLoggedMl = (cumLoggedMl - ml).coerceAtLeast(0)
        lastLogMs = lastLogMsBeforeBySlot[slot]
        lastLoggedMlBySlot[slot] = 0
        lastLogMsBeforeBySlot[slot] = 0L
        calibLogger?.log(CalibrationLogger.Event.FUELING_HYDRATION_UNDONE) {
            "slot=$slot,ml=-$ml,cum_logged=$cumLoggedMl,cum_target=${cumTargetMl.toInt()}"
        }
        publishStatus()
        return ml
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
        currentRateMlPerHour = if (config.hydrationDynamicEstimateEnabled) lastSweatRateMlHr.toInt()
                               else config.hydrationTargetMlPerHour,
        estimateConfidence = if (config.hydrationDynamicEstimateEnabled) lastSweatConfidence else null,
        // See CarbsTracker.getStatus — mirrors the movement + staleness gate in
        // tick() so UI consumers stay coherent with the integrator.
        isIntegrating = monitorJob != null && run {
            val speed = lastSpeedKmh ?: return@run false
            val stale = lastSpeedChangeMs > 0 &&
                (System.currentTimeMillis() - lastSpeedChangeMs) > SPEED_STALE_MS
            !stale && speed >= MOVING_GATE_KMH
        },
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
        // Movement gate — see CarbsTracker.tick() for the full rationale and the
        // GPS-staleness branch. Same shape: stop integrating when stationary OR when
        // the SDK's last reading has been stuck unchanged for SPEED_STALE_MS.
        val speed = lastSpeedKmh
        val stale = lastSpeedChangeMs > 0 && (now - lastSpeedChangeMs) > SPEED_STALE_MS
        val moving = !stale && speed != null && speed >= MOVING_GATE_KMH
        if (lastTickMs != 0L && moving) {
            // Clamp negative dt — see CarbsTracker.tick() for rationale (NTP correction).
            val dtSec = (now - lastTickMs).coerceAtLeast(0L) / 1000f
            val ratePerHour: Float = if (config.hydrationDynamicEstimateEnabled) {
                // Pull all available signals into the estimator on every tick. Inputs that
                // have not been received are passed as null so the estimator falls back to
                // its documented defaults (50 % RH, 20 °C, 70 kg, moderate ride).
                val estimate = estimateSweatRate(SweatEstimateInputs(
                    hrBpm = lastHrBpm,
                    powerW = lastPowerW,
                    weightKg = lastWeightKg,
                    ambientTempC = lastAmbientTempC,
                    humidityPct = lastHumidityPct,
                ))
                lastSweatConfidence = estimate.confidence
                // Only publish the LOW-confidence default (~298 ml/h) to the UI/alert path
                // after at least one MEDIUM-or-better tick has happened. Without this guard,
                // the very first integration with no HR/power yet would bleed a misleading
                // "≈300 ml/h" through `getStatus().currentRateMlPerHour` and into the
                // `{target}` token of the first alert. Internal integration still uses the
                // LOW estimate (better than nothing while the sensors wake up).
                if (estimate.confidence != SweatConfidence.LOW || lastSweatRateMlHr > 0.0) {
                    lastSweatRateMlHr = estimate.mlPerHour
                }
                estimate.mlPerHour.toFloat()
            } else {
                config.hydrationTargetMlPerHour.toFloat()
            }
            val ratePerSec = ratePerHour / 3600f
            cumTargetMl += dtSec * ratePerSec
        }
        lastTickMs = now

        evaluateDeficitAlert(now)
        evaluateTimeAlert(now)
        maybePeriodicLog(now)
        // See CarbsTracker.tick — re-publish to drive the status data fields off
        // a push channel (15-s cadence) instead of the old 1-Hz polling loops.
        publishStatus()
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
        // Initial-delay grace period — same semantics as CarbsTracker.evaluateTimeAlert.
        val isFirstAlert = lastAlertMs == 0L && cumLoggedMl == 0
        if (isFirstAlert && config.hydrationTimeInitialDelayMin > 0) {
            val initialDelayMs = config.hydrationTimeInitialDelayMin * 60_000L
            if (now - sessionStartMs < initialDelayMs) return
        }
        val intervalMs = config.hydrationTimeIntervalMin * 60_000L
        if (now - lastLogMs < intervalMs) return
        if (now - lastAlertMs < ALERT_COOLDOWN_MS) return
        fireAlert("time", (cumTargetMl - cumLoggedMl).toInt(), (now - lastLogMs) / 60_000)
    }

    private fun fireAlert(source: String, deficitMl: Int, elapsedMin: Long) {
        lastAlertMs = System.currentTimeMillis()
        // In dynamic-estimate mode the {target} placeholder must report the live
        // estimator output, not the fixed config value — a rider on a 30 °C ride
        // configured for 750 ml/h but estimating 1300 ml/h would otherwise see the
        // wrong number in their custom template.
        val effectiveTarget = if (config.hydrationDynamicEstimateEnabled)
            lastSweatRateMlHr.toInt()
        else
            config.hydrationTargetMlPerHour
        val tokens = mapOf(
            "deficit" to deficitMl.toString(),
            "elapsed" to elapsedMin.toString(),
            "target"  to effectiveTarget.toString(),
        )
        val detailTemplate = config.hydrationAlertCustomDetail.ifBlank {
            if (source == "deficit") context.getString(R.string.fueling_hyd_alert_detail_deficit)
            else                     context.getString(R.string.fueling_hyd_alert_detail_time)
        }
        val detail = renderAlertText(detailTemplate, tokens, maxLength = ALERT_DETAIL_MAX_CHARS)
        val title = renderAlertText(
            config.hydrationAlertCustomTitle.ifBlank { context.getString(R.string.fueling_hyd_alert_title) },
            tokens,
            maxLength = ALERT_TITLE_MAX_CHARS,
        )
        config.hydBeepPattern.toPlayBeepPattern()?.let { karooSystem.dispatch(it) }
        karooSystem.dispatch(InRideAlert(
            id = "ksafe-hyd-alert-$source",
            icon = R.drawable.ic_ksafe,
            title = title,
            detail = detail,
            autoDismissMs = AUTO_DISMISS_MS,
            backgroundColor = ALERT_BG_COLOR,
            textColor = ALERT_TX_COLOR,
        ))
        calibLogger?.log(CalibrationLogger.Event.FUELING_HYDRATION_FIRED) {
            "source=$source,deficit_ml=$deficitMl,since_log_min=$elapsedMin,cum_target=${cumTargetMl.toInt()},cum_logged=$cumLoggedMl,beep=${config.hydBeepPattern}"
        }
        Timber.d(">>> Hydration alert fired ($source): deficit=${deficitMl}ml elapsed=${elapsedMin}min")
    }

    private fun maybePeriodicLog(now: Long) {
        if (calibLogger == null || !calibLogger.isEnabled) return
        if (now - lastPeriodicLogMs < PERIODIC_LOG_INTERVAL_MS) return
        lastPeriodicLogMs = now
        val deficit = (cumTargetMl - cumLoggedMl).toInt()
        val mode = if (config.hydrationDynamicEstimateEnabled) "dynamic" else "fixed"
        calibLogger.log(CalibrationLogger.Event.FUELING_HYDRATION_PERIODIC) {
            "mode=$mode,rate_ml_h=${if (config.hydrationDynamicEstimateEnabled) lastSweatRateMlHr.toInt() else config.hydrationTargetMlPerHour}," +
                "conf=$lastSweatConfidence,hr=${lastHrBpm ?: -1},pwr=${lastPowerW ?: -1}," +
                "temp=${lastAmbientTempC ?: Double.NaN},rh=${lastHumidityPct ?: -1}," +
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
    /** Currently active per-hour rate. Equals [KSafeConfig.hydrationTargetMlPerHour] in fixed
     *  mode; equals the latest [SweatEstimator] output in dynamic mode. */
    val currentRateMlPerHour: Int = 0,
    /** Confidence of the dynamic estimate. Null when in fixed mode. */
    val estimateConfidence: SweatConfidence? = null,
    /** See [CarbStatus.isIntegrating] — true when the movement gate is passing and
     *  the tracker is running. Lets a future hydration-rate field stay coherent
     *  with the burn-rate field on the carbs side. */
    val isIntegrating: Boolean = false,
)

/** Totals captured at end-of-ride for the post-ride summary InRideAlert. */
data class HydrationSummary(
    val cumTargetMl: Int,
    val cumLoggedMl: Int,
    val deficitMl: Int,
    val percentageHit: Int,
)
