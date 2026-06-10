package com.enderthor.kSafe.extension.managers

import android.content.Context
import com.enderthor.kSafe.R
import com.enderthor.kSafe.data.HydFuelingState
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.data.fuelingAlertColorRes
import com.enderthor.kSafe.extension.util.ALERT_DETAIL_MAX_CHARS
import com.enderthor.kSafe.extension.util.ALERT_TITLE_MAX_CHARS
import com.enderthor.kSafe.extension.util.CarbIntegrator
import com.enderthor.kSafe.extension.util.FuelingAlertScheduler
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

    /** Movement gate + GPS-stale window read from [CarbIntegrator] — the canonical
     *  home for these constants post-v18.1. Keeps both trackers (and
     *  [MedicalEpisodeDetector]) in lockstep so a future tune touches one place. */
    private val MOVING_GATE_KMH = CarbIntegrator.MOVING_GATE_KMH
    private val SPEED_STALE_MS  = CarbIntegrator.SPEED_STALE_MS
    /** See [CarbsTracker.SENSOR_STALE_MS]. A HR/power sample older than this is
     *  treated as "sensor gone" by the sweat-rate estimator, so a dead sensor's
     *  last value can't keep driving the dynamic hydration rate. */
    private val SENSOR_STALE_MS = 15_000L
    private val PERIODIC_LOG_INTERVAL_MS = 120_000L

    // InRideAlert color contract: SDK expects @ColorRes IDs, NOT packed ARGB ints —
    // passing 0xFFE65100 here used to crash the ride app with
    // `Resources$NotFoundException: Resource ID #0xffe65100`. Use R.color.* resources
    // defined in res/values/colors.xml.
    // BG colour is rider-configurable via config.hydrationAlertBgColor — see fireAlert.
    private val ALERT_TX_COLOR = R.color.alert_text_white
    private val AUTO_DISMISS_MS = 10_000L

    // ─── Session state (reset by start()) ────────────────────────────────────
    @Volatile private var cumTargetMl = 0f
    @Volatile private var cumLoggedMl = 0
    @Volatile private var sessionStartMs = 0L
    @Volatile private var lastTickMs = 0L
    /**
     * Wall-clock ms of the last rider log OR the last time-alert fire (F1 fix —
     * see [evaluateTimeAlert]). Drives the time-alert interval gate, so treating
     * an unacknowledged fire as a soft "time mark" is what keeps "alert me every
     * N minutes" honest when the rider misses logs (without it the interval gate
     * stays latched-open after the first fire and the 5-min cooldown becomes the
     * de-facto cadence).
     */
    @Volatile private var lastLogMs = 0L
    /** See [CarbsTracker.lastRealLogMs] — same field, hydration side. Tracks the last
     *  REAL log (not time-alert fire) so `{elapsed}` reflects time-since-real-log. */
    @Volatile private var lastRealLogMs = 0L
    // v18 L1: `lastAlertMs` removed — see CarbsTracker comment for rationale.
    /**
     * Wall-clock ms when a TIME-source alert last fired in this session. Drives the
     * pure-interval gate in [evaluateTimeAlert]:
     * `now - lastTimeAlertFireMs >= intervalMs`. **Critically not updated by rider
     * logs** — the v17 user-facing semantics is "remind me every N minutes",
     * independent of when the rider last drank. 0 = no time alert has fired yet
     * this session; the first-fire gate then uses the initial-delay logic.
     */
    @Volatile private var lastTimeAlertFireMs = 0L
    /**
     * Wall-clock ms when a DEFICIT-source alert last fired in this session. Drives
     * the configurable reminder cooldown (`config.hydrationDeficitReminderIntervalMin
     * * 60_000`). Independent of [lastTimeAlertFireMs] — each source has its own
     * cooldown clock so the two alert kinds don't throttle each other.
     */
    @Volatile private var lastDeficitAlertFireMs = 0L
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
    /** See [CarbsTracker.lastHrUpdateMs] — wall-clock of the last HR / power
     *  emission, used to drop a stale sensor's frozen value from the sweat-rate
     *  estimate so the dynamic rate falls back to the live sensor (or to its
     *  documented defaults when both are gone) instead of riding a dead reading. */
    @Volatile private var lastHrUpdateMs = 0L
    @Volatile private var lastPowerUpdateMs = 0L
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
    /** See [CarbsTracker.lastRealLogMsBeforeBySlot] — same pattern, hydration side. */
    private val lastRealLogMsBeforeBySlot = LongArray(4)

    @Volatile private var config = KSafeConfig()
    private var monitorJob: Job? = null

    // ─── Status publisher (see CarbsTracker._statusFlow for the rationale) ──
    private val _statusFlow = MutableStateFlow<HydrationStatus?>(null)
    val statusFlow: StateFlow<HydrationStatus?> get() = _statusFlow

    private fun publishStatus() { _statusFlow.value = getStatus() }

    // ─── Public API ──────────────────────────────────────────────────────────

    fun start(config: KSafeConfig, restoreFrom: HydFuelingState? = null) {
        this.config = config
        if (!config.hydrationTrackerEnabled) return
        // Same restart pattern as CarbsTracker — wait for the previous monitor to fully
        // stop inside the new coroutine to avoid late ticks producing spurious log rows.
        val oldJob = monitorJob
        val now = System.currentTimeMillis()
        if (restoreFrom != null) {
            // Extension was killed mid-ride — see [CarbsTracker.start] for full rationale.
            cumTargetMl = restoreFrom.cumTargetMl
            cumLoggedMl = restoreFrom.cumLoggedMl
            sessionStartMs = restoreFrom.sessionStartMs.takeIf { it > 0 } ?: now
            lastLogMs = restoreFrom.lastLogMs.takeIf { it > 0 } ?: now
            // See CarbsTracker.start — pre-I8 snapshots have lastRealLogMs = 0, in which case
            // the legacy lastLogMs (which under v14 / pre-F1 meant "last real log") is the
            // best available proxy.
            lastRealLogMs = restoreFrom.lastRealLogMs.takeIf { it > 0 } ?: lastLogMs
            // v17 new fields. Old snapshots have 0 → treat as "never fired this session"
            // and let the first-fire initial-delay gate run normally on resume.
            lastTimeAlertFireMs = restoreFrom.lastTimeAlertFireMs
            lastDeficitAlertFireMs = restoreFrom.lastDeficitAlertFireMs
        } else {
            cumTargetMl = 0f
            cumLoggedMl = 0
            sessionStartMs = now
            lastLogMs = now
            lastRealLogMs = now
            lastTimeAlertFireMs = 0L
            lastDeficitAlertFireMs = 0L
        }
        lastTickMs = 0L
        lastPeriodicLogMs = 0L
        for (i in lastLoggedMlBySlot.indices) {
            lastLoggedMlBySlot[i] = 0
            lastLogMsBeforeBySlot[i] = 0L
            lastRealLogMsBeforeBySlot[i] = 0L
        }
        monitorJob = scope.launch {
            oldJob?.cancelAndJoin()
            // H3 — defensive try/catch around tick() so a single throw doesn't
            // disable hydration integration and alerts for the rest of the ride.
            while (true) {
                delay(MONITOR_TICK_MS)
                try { tick() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { Timber.e(e, "HydrationTracker.tick threw — continuing") }
            }
        }
        calibLogger?.log(CalibrationLogger.Event.FUELING_HYDRATION_START) {
            "base_ml_h=${config.hydrationTargetMlPerHour}," +
                "dynamic=${config.hydrationDynamicEstimateEnabled}," +
                "deficit_alert=${config.hydrationDeficitAlertEnabled}," +
                "deficit_threshold_ml=${config.hydrationDeficitThresholdMl}," +
                "deficit_initial_delay_min=${config.hydrationDeficitInitialDelayMin}," +
                "time_alert=${config.hydrationTimeAlertEnabled}," +
                "time_interval_min=${config.hydrationTimeIntervalMin}," +
                "time_initial_delay_min=${config.hydrationTimeInitialDelayMin}," +
                "beep=${config.hydBeepPattern}," +
                "restored=${restoreFrom != null}"
        }
        Timber.d("HydrationTracker started, target=${config.hydrationTargetMlPerHour} ml/h, restored=${restoreFrom != null}")
        publishStatus()
    }

    /** See [CarbsTracker.getPersistableState] — same contract for the hydration tracker. */
    fun getPersistableState(): HydFuelingState = HydFuelingState(
        cumTargetMl = cumTargetMl,
        cumLoggedMl = cumLoggedMl,
        sessionStartMs = sessionStartMs,
        lastLogMs = lastLogMs,
        lastRealLogMs = lastRealLogMs,
        lastTimeAlertFireMs = lastTimeAlertFireMs,
        lastDeficitAlertFireMs = lastDeficitAlertFireMs,
    )

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
            // H3 — defensive try/catch around tick() so a single throw doesn't
            // disable hydration integration and alerts for the rest of the ride.
            while (true) {
                delay(MONITOR_TICK_MS)
                try { tick() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { Timber.e(e, "HydrationTracker.tick threw — continuing") }
            }
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
        val old = this.config
        val wasEnabled = old.hydrationTrackerEnabled
        this.config = config
        if (!wasEnabled && config.hydrationTrackerEnabled && isRecording) start(config)
        else if (wasEnabled && !config.hydrationTrackerEnabled) stop()
        // See CarbsTracker.updateConfig — immediate re-publish on display-relevant enable
        // flips, gated on a prior publish so pre-ride fields keep their '---' state.
        if (_statusFlow.value != null && (
                old.isActive != config.isActive ||
                old.hydrationTrackerEnabled != config.hydrationTrackerEnabled)
        ) publishStatus()
    }

    // ─── Dynamic-estimate input updaters ────────────────────────────────────
    // Called from KSafeExtension whenever a stream emits. All are no-ops unless
    // [KSafeConfig.hydrationDynamicEstimateEnabled] is true at tick time.

    fun updateHr(bpm: Int)            { lastHrBpm = bpm; lastHrUpdateMs = System.currentTimeMillis() }
    fun updatePower(w: Int)           { lastPowerW = w; lastPowerUpdateMs = System.currentTimeMillis() }
    fun updateSpeed(kmh: Double) {
        // D6/G2 fix — drop NaN AND Infinity samples; see MedicalEpisodeDetector
        // for the IEEE-754 taint mechanism this guards against.
        if (!kmh.isFinite()) return
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
    fun updateAmbientTemp(c: Double)  {
        // K5 — drop NaN / Infinity. A bad TYPE_EXT::karoo-headwind::temperature
        // emission or onboard sensor glitch can deliver NaN. Without the guard
        // lastAmbientTempC is latched NaN, every downstream sweat-estimate
        // computation (heatFactor, mlPerHour) propagates NaN, and the
        // HydrationStatusDataType shows "NaN ml" forever — alerts never re-fire
        // because NaN comparisons against thresholds always return false.
        if (!c.isFinite()) return
        lastAmbientTempC = c
    }
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
        lastRealLogMsBeforeBySlot[slot] = lastRealLogMs
        lastLoggedMlBySlot[slot] = ml
        cumLoggedMl += ml
        val now = System.currentTimeMillis()
        lastLogMs = now
        lastRealLogMs = now
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
        // E8 fix — only roll [lastLogMs] / [lastRealLogMs] back to the pre-slot snapshot
        // when no OTHER slot has logged since this slot did. See
        // [CarbsTracker.undoLastForSlot] for the rationale and the interleave check.
        val snapLog = lastLogMsBeforeBySlot[slot]
        val snapReal = lastRealLogMsBeforeBySlot[slot]
        if (snapLog > 0L && lastLogMs >= snapLog && !hasInterleavedLogAfter(slot, snapLog)) {
            lastLogMs = snapLog
            lastRealLogMs = snapReal
        }
        lastLoggedMlBySlot[slot] = 0
        lastLogMsBeforeBySlot[slot] = 0L
        lastRealLogMsBeforeBySlot[slot] = 0L
        calibLogger?.log(CalibrationLogger.Event.FUELING_HYDRATION_UNDONE) {
            "slot=$slot,ml=-$ml,cum_logged=$cumLoggedMl,cum_target=${cumTargetMl.toInt()}"
        }
        publishStatus()
        return ml
    }

    /** Time of the most recent [logAmount] (combined-field) log. Lets [hasInterleavedLogAfter]
     *  see a combined log it would otherwise miss (logAmount carries no slot), so a per-slot
     *  [undoLastForSlot] can't roll the {elapsed} timer back past an interleaved combined log. */
    @Volatile private var lastAmountLogMs = 0L

    /** Log an EXPLICIT [ml] (used by the combined fuel-log field, which carries its own amount
     *  rather than a slot's config). Feeds the same [cumLoggedMl] total as [logEntry]. Returns
     *  the ml logged (0 if non-positive). The caller records the amount for undo via [undoAmount]. */
    fun logAmount(ml: Int): Int {
        if (ml <= 0) return 0
        cumLoggedMl += ml
        val now = System.currentTimeMillis()
        lastLogMs = now
        lastRealLogMs = now
        lastAmountLogMs = now
        calibLogger?.log(CalibrationLogger.Event.FUELING_HYDRATION_LOGGED) {
            "slot=combined,ml=$ml,cum_logged=$cumLoggedMl,cum_target=${cumTargetMl.toInt()}"
        }
        publishStatus()
        return ml
    }

    /** Reverse a previous [logAmount] of exactly [ml]. Clamps the total at >= 0.
     *  Rolls back only [cumLoggedMl] (the deficit-relevant total), NOT the log timestamps —
     *  see CarbsTracker.undoAmount for the rationale (not bumping on log would fire a false
     *  "you haven't drunk" alert right after a genuine combined log). */
    fun undoAmount(ml: Int) {
        if (ml <= 0) return
        cumLoggedMl = (cumLoggedMl - ml).coerceAtLeast(0)
        calibLogger?.log(CalibrationLogger.Event.FUELING_HYDRATION_UNDONE) {
            "slot=combined,ml=-$ml,cum_logged=$cumLoggedMl,cum_target=${cumTargetMl.toInt()}"
        }
        publishStatus()
    }

    /** Mirrors [CarbsTracker.hasInterleavedLogAfter] — returns true if any slot OTHER
     *  than [excludeSlot] logged after [thresholdMs]. */
    private fun hasInterleavedLogAfter(excludeSlot: Int, thresholdMs: Long): Boolean {
        // A combined-field logAmount carries no slot, so it isn't in the per-slot arrays below;
        // check its timestamp explicitly or a per-slot undo could roll the timer back past it.
        if (lastAmountLogMs >= thresholdMs) return true
        for (i in 1..2) {
            if (i == excludeSlot) continue
            if (lastLoggedMlBySlot[i] > 0 && lastLogMsBeforeBySlot[i] >= thresholdMs) return true
        }
        return false
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
        // Mirror fireAlert: before the first real estimate (lastSweatRateMlHr == 0.0)
        // report the configured target rather than a misleading "0 ml/h".
        currentRateMlPerHour = if (config.hydrationDynamicEstimateEnabled && lastSweatRateMlHr > 0.0)
                                   lastSweatRateMlHr.toInt()
                               else config.hydrationTargetMlPerHour,
        // LIVE confidence (not the cached lastSweatConfidence, which only updates on
        // moving ticks): computed from the current fresh sensor inputs so the "~"
        // marker is right while stopped and at ride start. See [freshSweatInputs].
        estimateConfidence = if (config.hydrationDynamicEstimateEnabled)
                                 estimateSweatRate(freshSweatInputs(System.currentTimeMillis())).confidence
                             else null,
        // See CarbsTracker.getStatus — mirrors the movement + staleness gate in
        // tick() so UI consumers stay coherent with the integrator.
        isIntegrating = monitorJob != null && run {
            val speed = lastSpeedKmh ?: return@run false
            val stale = lastSpeedChangeMs > 0 &&
                (System.currentTimeMillis() - lastSpeedChangeMs) > SPEED_STALE_MS
            !stale && speed >= MOVING_GATE_KMH
        },
        masterEnabled = config.isActive,
        hydrationEnabled = config.hydrationTrackerEnabled,
    )

    fun getSummary(): HydrationSummary = HydrationSummary(
        cumTargetMl = cumTargetMl.toInt(),
        cumLoggedMl = cumLoggedMl,
        deficitMl = (cumTargetMl - cumLoggedMl).toInt(),
        percentageHit = if (cumTargetMl > 0f) ((cumLoggedMl / cumTargetMl) * 100f).toInt() else 0,
    )

    // ─── Internals ───────────────────────────────────────────────────────────

    /** Sweat-estimate inputs with HR/power gated on freshness: a sensor silent for
     *  longer than [SENSOR_STALE_MS] is passed as null (see
     *  [CarbsTracker.lastHrUpdateMs]). Shared by [tick] (drives integration) and by
     *  [getStatus] (the LIVE confidence the hydration field's "~" low-confidence
     *  marker reads) so both agree exactly — and so the marker reflects the current
     *  sensor state rather than the cached [lastSweatConfidence], which only refreshes
     *  on moving ticks and would otherwise show a stale/incorrect "~" while stopped
     *  or at ride start. */
    private fun freshSweatInputs(now: Long) = SweatEstimateInputs(
        hrBpm        = lastHrBpm?.takeIf  { lastHrUpdateMs    > 0L && now - lastHrUpdateMs    <= SENSOR_STALE_MS },
        powerW       = lastPowerW?.takeIf { lastPowerUpdateMs > 0L && now - lastPowerUpdateMs <= SENSOR_STALE_MS },
        weightKg     = lastWeightKg,
        ambientTempC = lastAmbientTempC,
        humidityPct  = lastHumidityPct,
    )

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
                // Pull all available signals into the estimator on every tick, with
                // HR/power gated on freshness (a dead sensor's frozen value is dropped
                // to null so the rate falls back to the live sensor / documented
                // defaults). See [freshSweatInputs].
                val estimate = estimateSweatRate(freshSweatInputs(now))
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

        // v17 coincidence resolution: when a deficit alert and a time-grid tick are
        // both due in the same physical tick, the deficit alert wins (it carries the
        // more actionable info — a numeric deficit beats "X min since last drink").
        // The time-grid tick is "consumed" silently so the next tick doesn't re-fire
        // the time alert back-to-back. Without this, both `evaluateXxxAlert` calls
        // would dispatch their own `InRideAlert`: the Karoo SDK would overlay them
        // (the time alert visually replacing the deficit one) and the rider would
        // hear two beeps but read only the less informative message — worst of both
        // worlds. Same shape in `CarbsTracker.tick`.
        val deficitFired = evaluateDeficitAlert(now)
        if (deficitFired) {
            if (currentDueTimeTick(now) != 0L) {
                // Mark the time tick as consumed. Setting `lastTimeAlertFireMs = now`
                // is sufficient because `currentDueTimeTick` requires
                // `lastTimeAlertFireMs < currentTickAt` to consider a tick due, and
                // `now >= currentTickAt` is the entry condition of `currentDueTimeTick`
                // returning non-zero. The next time tick (at sessionStartMs + (N+1) *
                // interval) is unaffected because `lastTimeAlertFireMs` will then be
                // strictly less than that newer `currentTickAt`.
                lastTimeAlertFireMs = now
            }
        } else {
            evaluateTimeAlert(now)
        }
        maybePeriodicLog(now)
        // See CarbsTracker.tick — re-publish to drive the status data fields off
        // a push channel (15-s cadence) instead of the old 1-Hz polling loops.
        publishStatus()
    }

    /** Returns true when an alert was actually dispatched in this call. The caller
     *  in [tick] uses the return value to coordinate coincidence resolution with
     *  the time-alert path (deficit wins; if a time tick was due in the same
     *  tick it gets consumed silently). */
    private fun evaluateDeficitAlert(now: Long): Boolean {
        // v18.2 B9 — gate delegated to [FuelingAlertScheduler.shouldFireDeficit]
        // so the carb and hydration deficit logic is single-sourced and unit-
        // tested in `FuelingAlertSchedulerTest`. Mirrors `CarbsTracker.evaluateDeficitAlert`.
        val deficit = (cumTargetMl - cumLoggedMl).toInt()
        val fire = FuelingAlertScheduler.shouldFireDeficit(
            enabled                = config.hydrationDeficitAlertEnabled,
            deficit                = deficit,
            deficitThreshold       = config.hydrationDeficitThresholdMl,
            lastDeficitAlertFireMs = lastDeficitAlertFireMs,
            reminderIntervalMs     = config.hydrationDeficitReminderIntervalMin * 60_000L,
            initialDelayMs         = config.hydrationDeficitInitialDelayMin * 60_000L,
            cumLogged              = cumLoggedMl,
            sessionStartMs         = sessionStartMs,
            now                    = now,
        )
        if (!fire) return false
        fireAlert("deficit", deficit, elapsedMinutesSinceRealLog(now))
        lastDeficitAlertFireMs = now
        return true
    }

    /** See [CarbsTracker.elapsedMinutesSinceRealLog] for the contract and the B33
     *  rationale. Same defensive guards: sentinel-zero on uninitialised
     *  `lastRealLogMs`, clamp on backwards NTP step. */
    private fun elapsedMinutesSinceRealLog(now: Long): Long =
        if (lastRealLogMs <= 0L) 0L
        else (now - lastRealLogMs).coerceAtLeast(0L) / 60_000

    /** See [CarbsTracker.elapsedMinutesSinceLastTimeAlert] for the rationale —
     *  same two-anchor pattern (last fire or session start) and same defensive
     *  guards. Used by [evaluateTimeAlert] so `{elapsed}` reads as "min since
     *  last reminder", aligned with the interval-grid fire schedule. */
    private fun elapsedMinutesSinceLastTimeAlert(now: Long): Long {
        val anchor = if (lastTimeAlertFireMs > 0L) lastTimeAlertFireMs else sessionStartMs
        if (anchor <= 0L) return 0L
        return (now - anchor).coerceAtLeast(0L) / 60_000
    }

    /**
     * Returns the grid-tick timestamp that would fire in this call, or `0L` when
     * no tick is currently due (alert disabled, before the first tick, already
     * fired this tick, or filtered by the initial-delay window). Pure read —
     * mutates nothing. Used by both [evaluateTimeAlert] (to gate firing) and by
     * [tick]'s coincidence resolution (to detect that a time tick is being
     * silently consumed in favour of a same-tick deficit alert).
     *
     * Grid-aligned: ticks are at `sessionStartMs + N * intervalMs` for
     * N = 1, 2, 3, …; rider logs do NOT shift the grid. Initial-delay FILTERS
     * ticks whose timestamp is earlier than `sessionStartMs + initialDelay`
     * (grid stays anchored to session start — interval=20 + initialDelay=30
     * fires at 40, 60, 80, …, not 30, 50, 70).
     */
    private fun currentDueTimeTick(now: Long): Long = FuelingAlertScheduler.currentDueTimeTick(
        enabled = config.hydrationTimeAlertEnabled,
        intervalMs = config.hydrationTimeIntervalMin * 60_000L,
        sessionStartMs = sessionStartMs,
        lastTimeAlertFireMs = lastTimeAlertFireMs,
        initialDelayMs = config.hydrationTimeInitialDelayMin * 60_000L,
        cumLogged = cumLoggedMl,
        now = now,
    )

    /** See [evaluateDeficitAlert] return-value note — same contract on the time side. */
    private fun evaluateTimeAlert(now: Long): Boolean {
        if (currentDueTimeTick(now) == 0L) return false
        // See CarbsTracker.evaluateTimeAlert — time alert is interval-driven,
        // so `{elapsed}` measures since the last reminder, not since last log.
        fireAlert("time", (cumTargetMl - cumLoggedMl).toInt(), elapsedMinutesSinceLastTimeAlert(now))
        lastTimeAlertFireMs = now
        // I8 — `lastRealLogMs` stays untouched on alert fires; it tracks the
        // rider's last actual log so `{elapsed}` reports time-since-real-log.
        return true
    }

    private fun fireAlert(source: String, deficitMl: Int, elapsedMin: Long) {
        // v18 L1 — see CarbsTracker.fireAlert for rationale.
        val dispatchedAtMs = System.currentTimeMillis()
        // In dynamic-estimate mode the {target} placeholder must report the live
        // estimator output, not the fixed config value — a rider on a 30 °C ride
        // configured for 750 ml/h but estimating 1300 ml/h would otherwise see the
        // wrong number in their custom template. BUT until the first MEDIUM-or-better
        // estimate arrives, `lastSweatRateMlHr` is still 0.0 (the LOW default is
        // intentionally withheld from the UI/alert path — see the guard in tick()).
        // Falling back to the configured target avoids rendering a misleading
        // "0 ml/h" on an early deficit/time alert fired before HR/power connect.
        val effectiveTarget = if (config.hydrationDynamicEstimateEnabled && lastSweatRateMlHr > 0.0)
            lastSweatRateMlHr.toInt()
        else
            config.hydrationTargetMlPerHour
        val tokens = mapOf(
            "deficit" to deficitMl.toString(),
            "elapsed" to elapsedMin.toString(),
            "target"  to effectiveTarget.toString(),
        )
        val customDetail = if (source == "deficit") config.hydrationAlertCustomDetailDeficit
                           else                     config.hydrationAlertCustomDetailTime
        val detailTemplate = customDetail.ifBlank {
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
            // Unique-per-fire ID — see CarbsTracker.fireAlert for the rationale.
            id = "ksafe-hyd-alert-$source-$dispatchedAtMs",
            icon = R.drawable.ic_ksafe,
            title = title,
            detail = detail,
            autoDismissMs = AUTO_DISMISS_MS,
            backgroundColor = fuelingAlertColorRes(config.hydrationAlertBgColor),
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
    /** See [CarbStatus.masterEnabled] — false when the extension master switch is OFF;
     *  the hydration field renders the disabled "OFF" state instead of a stale snapshot. */
    val masterEnabled: Boolean = true,
    /** See [CarbStatus.carbsEnabled] — false when the hydration feature toggle is off. */
    val hydrationEnabled: Boolean = true,
)

/** Totals captured at end-of-ride for the post-ride summary InRideAlert. */
data class HydrationSummary(
    val cumTargetMl: Int,
    val cumLoggedMl: Int,
    val deficitMl: Int,
    val percentageHit: Int,
)
