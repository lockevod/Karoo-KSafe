package com.enderthor.kSafe.extension.managers

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.enderthor.kSafe.R
import com.enderthor.kSafe.data.HydFuelingState
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.data.LastHydrationRide
import com.enderthor.kSafe.data.mmolL
import com.enderthor.kSafe.data.fuelingAlertColorRes
import com.enderthor.kSafe.extension.util.ALERT_DETAIL_MAX_CHARS
import com.enderthor.kSafe.extension.util.ALERT_TITLE_MAX_CHARS
import com.enderthor.kSafe.extension.util.CarbIntegrator
import com.enderthor.kSafe.extension.util.Clock
import com.enderthor.kSafe.extension.util.FuelingAlertScheduler
import com.enderthor.kSafe.extension.util.HydAccum
import com.enderthor.kSafe.extension.util.HydTickInput
import com.enderthor.kSafe.extension.util.SweatConfidence
import com.enderthor.kSafe.extension.util.SweatEstimate
import com.enderthor.kSafe.extension.util.SweatEstimateInputs
import com.enderthor.kSafe.extension.util.SystemClock
import com.enderthor.kSafe.extension.util.estimateSweatRate
import com.enderthor.kSafe.extension.util.hydrationStep
import com.enderthor.kSafe.extension.util.overDrinkShadowLevel
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
    private val onFuelingAlert: (com.enderthor.kSafe.extension.util.FuelingAlertRequest) -> Unit,
    /** True while a crash / SOS / check-in is active. A fueling alert that comes due then is
     *  DEFERRED (not fired, cooldown not consumed) so it doesn't beep over the SOS and re-fires
     *  once the emergency clears. Injected so the tracker stays decoupled from EmergencyManager. */
    private val isEmergencyActive: () -> Boolean,
    /** Strictly Recording (not Paused). Nothing accrues while paused, even if the bike moves. */
    private val isRecording: () -> Boolean = { true },
    /** Karoo ride time (excludes pauses); null = unknown. Coverage denominator for the shadow
     *  and the Last-ride record. */
    private val rideTimeMs: () -> Long? = { null },
    private val clock: Clock = SystemClock,
    private val calibLogger: CalibrationLogger? = null,
) {

    // ─── Constants ───────────────────────────────────────────────────────────
    // Tick at 15 s — alert cooldown is 5 min and target accumulation is monotonic, so
    // a coarser cadence loses < 1 % cumulative precision over a 5 h ride while halving
    // the wakeup count vs. the original 5 s. The deficit and time-alert thresholds
    // both have minute-level granularity downstream, so 15 s polling is fine.
    private val MONITOR_TICK_MS         = 15_000L
    /** How long a Headwind humidity reading stays usable. Sized against Headwind's own
     *  fetch cadence (it refreshes on movement of a few km, or hourly at worst), so a
     *  healthy but quiet stream is not treated as stale. */
    private val HUMIDITY_MAX_AGE_MS     = 75L * 60_000L

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
    /** All per-session hydration totals (drink target, sweat, sodium, coverage) — see [HydAccum]. */
    @Volatile private var accum = HydAccum()
    /** Last HYD_OVER_SHADOW level logged this session. SHADOW only: never drives an alert. */
    @Volatile private var overShadowLevel = 0
    /** [isRecording] as seen by the previous [tick]; a change re-anchors dt (Recording↔Paused). */
    private var wasRecording = true
    /** Deficit growth over the last tick (ml/ms), for the hold's first-crossing projection. */
    private var deficitRatePerMs = 0.0
    @Volatile private var cumLoggedMl = 0
    @Volatile private var sessionStartMs = 0L
    @Volatile private var lastTickMs = 0L
    /**
     * Wall-clock ms of the last rider log OR the last time-alert fire (F1 fix —
     * see [fireTimeAlert]). Drives the time-alert interval gate, so treating
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
     * pure-interval gate in [currentDueTimeTick]:
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
    /**
     * Deficit alerts fired since the rider last logged anything. Feeds
     * [FuelingAlertScheduler.shouldFireDeficit]'s back-off ladder (×1 / ×2 / ×4).
     *
     * ponytail: in-memory only, and that is deliberate rather than a gap. [resume]
     * (RideState pause→resume, master-switch OFF→ON) touches neither this counter nor
     * [lastRealLogMs], so the ladder is **preserved** across a pause — correct, since
     * it is the same rider on the same ride still not logging. It resets only where it
     * should: a new session ([start] reseeds [lastRealLogMs], so [backoffAnchorLogMs]
     * mismatches), or a process restart. Persisting it in [HydFuelingState] would only
     * cover the process-restart case and costs a CONFIG_VERSION bump — not worth it
     * unless field logs show that case mattering.
     */
    @Volatile private var deficitFiresSinceLog = 0
    /** Value of [lastRealLogMs] the back-off counter was last synced against; a
     *  mismatch means the rider logged (or undid) since the previous evaluation,
     *  which resets [deficitFiresSinceLog]. */
    @Volatile private var backoffAnchorLogMs = 0L
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
    /** Wall-clock ms of the last humidity update, or 0 if none this session.
     *
     *  Humidity comes only from the Headwind extension — there is no onboard sensor to fall
     *  back to — so without an expiry a single reading was used by the sweat estimator
     *  forever: past the stream dying, past the end of the ride, and into the next ride in
     *  the same process. [SweatEstimator] already accepts a null humidity and degrades its
     *  confidence, which is the honest answer once the reading is old. */
    @Volatile private var lastHumidityAtMs: Long = 0L
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
        val now = clock.nowMs()
        if (restoreFrom != null) {
            // Extension was killed mid-ride — see [CarbsTracker.start] for full rationale.
            // A legacy snapshot restores coveredMs = 0, which is what keeps it out of
            // calibration and the shadow (coverage is measured against ride time).
            accum = HydAccum(
                cumTargetMl = restoreFrom.cumTargetMl,
                cumSweatBaseMl = restoreFrom.cumSweatBaseMl,
                cumSweatMl = restoreFrom.cumSweatMl,
                cumSodiumMg = restoreFrom.cumSodiumMg,
                coveredMs = restoreFrom.coveredMs,
                lowConfMs = restoreFrom.lowConfMs,
            )
            overShadowLevel = restoreFrom.overShadowLevel
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
            accum = HydAccum()
            overShadowLevel = 0
            cumLoggedMl = 0
            sessionStartMs = now
            lastLogMs = now
            lastRealLogMs = now
            lastTimeAlertFireMs = 0L
            lastDeficitAlertFireMs = 0L
        }
        sessionEnded = false             // a live session now exists for this ride
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
                "restored=${restoreFrom != null}," +
                "mult=${config.hydrationSweatMultiplierPct}," +
                "repl=${config.hydrationReplacementPct}," +
                "na=${naMmolL()}"
        }
        Timber.d("HydrationTracker started, target=${config.hydrationTargetMlPerHour} ml/h, restored=${restoreFrom != null}")
        publishStatus()
    }

    /** See [CarbsTracker.getPersistableState] — same contract for the hydration tracker. */
    fun getPersistableState(): HydFuelingState = accum.let { a -> HydFuelingState(
        cumTargetMl = a.cumTargetMl,
        cumLoggedMl = cumLoggedMl,
        sessionStartMs = sessionStartMs,
        lastLogMs = lastLogMs,
        lastRealLogMs = lastRealLogMs,
        lastTimeAlertFireMs = lastTimeAlertFireMs,
        lastDeficitAlertFireMs = lastDeficitAlertFireMs,
        cumSweatBaseMl = a.cumSweatBaseMl,
        cumSweatMl = a.cumSweatMl,
        cumSodiumMg = a.cumSodiumMg,
        coveredMs = a.coveredMs,
        lowConfMs = a.lowConfMs,
        overShadowLevel = overShadowLevel,
    ) }

    /**
     * Totals for the post-ride "Last ride" record, or null when there is no live session for
     * THIS ride (hydration disabled, already ended, or never started) so a later ride can't
     * re-snapshot a previous ride's retained totals. Coverage is judged by the consumer.
     */
    fun lastRideSnapshot(): LastHydrationRide? {
        if (!config.hydrationTrackerEnabled || sessionEnded || sessionStartMs <= 0L) return null
        val a = accum
        return LastHydrationRide(
            rideId = sessionStartMs,
            endedAtMs = clock.nowMs(),
            rideTimeMs = rideTimeMs() ?: 0L,
            coveredMs = a.coveredMs,
            lowConfMs = a.lowConfMs,
            cumSweatBaseMl = a.cumSweatBaseMl,
            cumSweatMl = a.cumSweatMl,
            cumLoggedMl = cumLoggedMl,
            cumSodiumMg = a.cumSodiumMg,
            naMmolL = naMmolL(),
            multiplierPctAtRide = config.hydrationSweatMultiplierPct,
            dynamicMode = config.hydrationDynamicEstimateEnabled,
        )
    }

    /** See [CarbsTracker.sessionEnded] — same live-session bookkeeping. */
    @Volatile private var sessionEnded = true

    /** See [CarbsTracker.stop] — `endOfSession = true` only on the ride-end path. */
    fun stop(endOfSession: Boolean = false) {
        monitorJob?.cancel()
        monitorJob = null
        if (endOfSession) sessionEnded = true
        Timber.d("HydrationTracker stopped (endOfSession=$endOfSession)")
        // State is intentionally retained so getStatus() / lastRideSnapshot() remain
        // readable after the ride ends.
        publishStatus()
    }

    /**
     * Re-launch the monitor without resetting accumulators. Used by the master-switch
     * mid-ride OFF→ON transition: the rider's cumulative target and logged volumes are
     * preserved so a brief toggle does not erase the ride's totals. dt is anchored at the
     * resume instant (lastTickMs = now), so the OFF / paused window is never integrated
     * while the first tick's interval after resume still counts.
     */
    fun resume(config: KSafeConfig) {
        this.config = config
        if (!config.hydrationTrackerEnabled) return
        // See [CarbsTracker.resume] — no live session for THIS ride means a resume would
        // revive the previous ride's retained totals (or run with sessionStartMs == 0,
        // firing a spurious time alert on the first tick). Fresh start instead.
        if (sessionEnded || sessionStartMs == 0L) {
            Timber.d("HydrationTracker.resume with no live session — starting fresh")
            start(config)
            return
        }
        val oldJob = monitorJob
        lastTickMs = clock.nowMs()
        wasRecording = isRecording()
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
        Timber.d("HydrationTracker resumed (cumTargetMl=${accum.cumTargetMl.toInt()}, cumLoggedMl=$cumLoggedMl)")
        publishStatus()
    }

    /**
     * Ride went Recording → Paused. Closes the open Recording interval (last tick → now) so
     * it isn't dropped, then marks the tracker paused so neither the paused span nor the
     * next tick integrates anything; [resume] re-anchors on the way back. No-op when the
     * monitor isn't running. The in-tick Recording↔Paused re-anchor stays as a safety net.
     */
    fun onPaused() {
        if (monitorJob == null) return
        integrateTo(clock.nowMs(), recording = true)
        wasRecording = false
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
        // OFF→ON mid-ride is a RESUME (preserves session totals; falls back to start()
        // when no live session exists) — see CarbsTracker.updateConfig.
        if (!wasEnabled && config.hydrationTrackerEnabled && isRecording) resume(config)
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

    fun updateHr(bpm: Int)            { lastHrBpm = bpm; lastHrUpdateMs = clock.nowMs() }
    fun updatePower(w: Int)           { lastPowerW = w; lastPowerUpdateMs = clock.nowMs() }
    fun updateSpeed(kmh: Double) {
        // D6/G2 fix — drop NaN AND Infinity samples; see MedicalEpisodeDetector
        // for the IEEE-754 taint mechanism this guards against.
        if (!kmh.isFinite()) return
        val prev = lastSpeedKmh
        // See CarbsTracker.updateSpeed — stamp on value change OR explicit zero OR
        // bootstrap so a stopped rider isn't misclassified as GPS-stale.
        if (prev == null || prev != kmh || kmh == 0.0) {
            lastSpeedChangeMs = clock.nowMs()
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
    fun updateHumidity(pct: Int) {
        lastHumidityPct = pct
        lastHumidityAtMs = clock.nowMs()
    }

    /** Humidity, or null once it is older than [HUMIDITY_MAX_AGE_MS]. Read at the point of
     *  use rather than expired on a timer, so no extra tick is needed. */
    private fun freshHumidityPct(): Int? {
        val stamp = lastHumidityAtMs
        if (stamp == 0L) return null
        val age = clock.nowMs() - stamp
        // Non-negative age required: a wall-clock step backwards would otherwise read a
        // future stamp as maximal freshness and pin a stale value indefinitely.
        return if (age in 0 until HUMIDITY_MAX_AGE_MS) lastHumidityPct else null
    }

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
        val now = clock.nowMs()
        lastLogMs = now
        lastRealLogMs = now
        calibLogger?.log(CalibrationLogger.Event.FUELING_HYDRATION_LOGGED) {
            "slot=$slot,ml=$ml,cum_logged=$cumLoggedMl,cum_target=${accum.cumTargetMl.toInt()}"
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
            "slot=$slot,ml=-$ml,cum_logged=$cumLoggedMl,cum_target=${accum.cumTargetMl.toInt()}"
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
        val now = clock.nowMs()
        lastLogMs = now
        lastRealLogMs = now
        lastAmountLogMs = now
        calibLogger?.log(CalibrationLogger.Event.FUELING_HYDRATION_LOGGED) {
            "slot=combined,ml=$ml,cum_logged=$cumLoggedMl,cum_target=${accum.cumTargetMl.toInt()}"
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
            "slot=combined,ml=-$ml,cum_logged=$cumLoggedMl,cum_target=${accum.cumTargetMl.toInt()}"
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
        cumTargetMl = accum.cumTargetMl.toInt(),
        cumSweatMl = accum.cumSweatMl.toInt(),
        cumSodiumMg = accum.cumSodiumMg.toInt(),
        cumLoggedMl = cumLoggedMl,
        deficitMl = (accum.cumTargetMl - cumLoggedMl).toInt(),
        deficitThresholdMl = config.hydrationDeficitThresholdMl,
        currentRateMlPerHour = drinkRateMlPerHour(),
        // LIVE confidence (not the cached lastSweatConfidence, which only updates on
        // moving ticks): computed from the current fresh sensor inputs so the "~"
        // marker is right while stopped and at ride start. See [freshSweatInputs].
        estimateConfidence = if (config.hydrationDynamicEstimateEnabled)
                                 estimateSweatRate(freshSweatInputs(clock.nowMs())).confidence
                             else null,
        // See CarbsTracker.getStatus — mirrors the movement + staleness gate in
        // tick() so UI consumers stay coherent with the integrator.
        isIntegrating = monitorJob != null && isRecording() && run {
            val speed = lastSpeedKmh ?: return@run false
            val stale = lastSpeedChangeMs > 0 &&
                (clock.nowMs() - lastSpeedChangeMs) > SPEED_STALE_MS
            !stale && speed >= MOVING_GATE_KMH
        },
        masterEnabled = config.isActive,
        hydrationEnabled = config.hydrationTrackerEnabled,
    )

    // ─── Internals ───────────────────────────────────────────────────────────

    private fun naMmolL(): Int = config.sweatSodiumProfile.mmolL(config.sweatSodiumMeasuredMmolL)

    /**
     * The per-hour DRINK rate shown to the rider (status field and the `{target}` token).
     * Dynamic mode: latest sweat estimate × multiplier × replacement — the same rate the
     * integrator uses. Before the first MEDIUM-or-better estimate (`lastSweatRateMlHr == 0.0`,
     * see the guard in [tick]) fall back to the configured target × replacement (spec A8)
     * rather than a misleading "0 ml/h". Clamps mirror [hydrationStep].
     */
    private fun drinkRateMlPerHour(): Int {
        if (!config.hydrationDynamicEstimateEnabled) return config.hydrationTargetMlPerHour
        val repl = config.hydrationReplacementPct.coerceIn(50, 100) / 100.0
        return if (lastSweatRateMlHr > 0.0)
            (lastSweatRateMlHr * config.hydrationSweatMultiplierPct.coerceIn(50, 200) / 100.0 * repl).toInt()
        else (config.hydrationTargetMlPerHour * repl).toInt()
    }

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
        humidityPct  = freshHumidityPct(),
    )

    /**
     * Integrates [lastTickMs, now] into [accum] when [recording] and moving, then re-anchors
     * [lastTickMs] to now. Shared by [tick] and [onPaused]. Returns whether it integrated and
     * the sweat estimate used.
     */
    private fun integrateTo(now: Long, recording: Boolean): Pair<Boolean, SweatEstimate> {
        // Movement gate — see CarbsTracker.tick() for the full rationale and the
        // GPS-staleness branch. Same shape: stop integrating when stationary OR when
        // the SDK's last reading has been stuck unchanged for SPEED_STALE_MS.
        val speed = lastSpeedKmh
        val stale = lastSpeedChangeMs > 0 && (now - lastSpeedChangeMs) > SPEED_STALE_MS
        val moving = !stale && speed != null && speed >= MOVING_GATE_KMH
        // Recording only (spec A3). A Recording↔Paused change re-anchors dt: that tick
        // integrates nothing, so no paused span leaks into the next tick's dt.
        val recordingChanged = recording != wasRecording
        wasRecording = recording
        val integrating = lastTickMs != 0L && !recordingChanged && recording && moving
        // Clamp negative dt — see CarbsTracker.tick() for rationale (NTP correction).
        val dtMs = if (integrating) (now - lastTickMs).coerceAtLeast(0L) else 0L
        // The estimate runs in BOTH modes so the sweat / sodium / coverage accumulators
        // work for every rider (spec D1). HR/power are gated on freshness: a dead sensor's
        // frozen value is dropped to null — see [freshSweatInputs].
        val estimate = estimateSweatRate(freshSweatInputs(now))
        val step = hydrationStep(accum, HydTickInput(
            dtMs = dtMs,
            integrating = integrating,
            baseSweatMlPerHour = estimate.mlPerHour,
            confidence = estimate.confidence,
            dynamicMode = config.hydrationDynamicEstimateEnabled,
            staticTargetMlPerHour = config.hydrationTargetMlPerHour,
            multiplierPct = config.hydrationSweatMultiplierPct,
            replacementPct = config.hydrationReplacementPct,
            naMmolL = naMmolL(),
        ))
        accum = step.accum
        // The replacement fraction is already inside the drink rate, so the scheduler's
        // hold / lookahead projects exactly the rate that is integrated.
        deficitRatePerMs = step.drinkRateMlPerHour / 3600.0 / 1000.0
        if (integrating) {
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
        }
        lastTickMs = now
        return integrating to estimate
    }

    @VisibleForTesting internal fun tickForTest() = tick()

    private fun tick() {
        val now = clock.nowMs()
        val (integrating, estimate) = integrateTo(now, isRecording())
        val dynamic = config.hydrationDynamicEstimateEnabled

        // Over-drink SHADOW (spec D5): log a new excess level, nothing else — no alert,
        // no beep, no overlay. Going live is a separate change through the scheduler.
        val rideMs = rideTimeMs()
        val level = overDrinkShadowLevel(
            rideTimeMs = rideMs, coveredMs = accum.coveredMs, cumSweatMl = accum.cumSweatMl,
            cumLoggedMl = cumLoggedMl, confidenceNow = estimate.confidence,
            integrating = integrating, lastLoggedLevel = overShadowLevel,
        )
        if (level > 0) {
            overShadowLevel = level
            calibLogger?.log(CalibrationLogger.Event.FUELING_HYDRATION_OVER_SHADOW) {
                "excess=${(cumLoggedMl - accum.cumSweatMl).toInt()},cum_logged=$cumLoggedMl," +
                    "cum_sweat=${accum.cumSweatMl.toInt()},mult=${config.hydrationSweatMultiplierPct}," +
                    "conf=${estimate.confidence},cov_pct=${coveragePct(rideMs)}," +
                    "mode=${if (dynamic) "dynamic" else "fixed"},ride_min=${rideMs?.div(60_000) ?: -1}"
            }
        }

        // Alert channels. The decision — emergency deferral, deficit wins a same-tick
        // coincidence (v17), quiet window after a deficit alert, the 2.2.4 hold (2.2.5: also on the first threshold crossing) of a time tick
        // whose deficit alert is about to replace it, and dropping a tick the rider logged after —
        // lives in the pure [FuelingAlertScheduler.resolveTick] so it is unit-tested with a
        // multi-tick simulation. This only stamps and dispatches. Same shape in `CarbsTracker.tick`.
        //
        // Back-off bookkeeping first (2026-07-22 field sweep: 13 unacknowledged prompts in one
        // hour on install `1136e7`). Anchoring on `lastRealLogMs` rather than resetting inside
        // every log path means logEntry / logAmount / undo are all covered automatically.
        if (lastRealLogMs != backoffAnchorLogMs) {
            backoffAnchorLogMs = lastRealLogMs
            deficitFiresSinceLog = 0
        }
        val timeTickAt = currentDueTimeTick(now)
        when (FuelingAlertScheduler.resolveTick(
            deficitDueWithin = { deficitDue(now, lookaheadMs = it) },
            timeTickAtMs = timeTickAt,
            lastDeficitAlertFireMs = lastDeficitAlertFireMs,
            lastRealLogMs = lastRealLogMs,
            emergencyActive = isEmergencyActive(),
            now = now,
        )) {
            FuelingAlertScheduler.TickAction.FIRE_DEFICIT -> {
                fireDeficitAlert(now)
                if (timeTickAt != 0L) {
                    // Consume the coinciding (or held) time tick so the rider hears one beep.
                    // `lastTimeAlertFireMs = now` satisfies currentDueTimeTick's "already fired
                    // this tick" guard and leaves the next grid point untouched.
                    calibLogger?.log(CalibrationLogger.Event.FUELING_ALERT_QUIETED) {
                        "kind=hyd,reason=deficit_wins"
                    }
                    lastTimeAlertFireMs = now
                }
            }
            FuelingAlertScheduler.TickAction.FIRE_TIME -> fireTimeAlert(now)
            FuelingAlertScheduler.TickAction.QUIET_AFTER_DEFICIT -> {
                calibLogger?.log(CalibrationLogger.Event.FUELING_ALERT_QUIETED) {
                    "kind=hyd,since_deficit_ms=${now - lastDeficitAlertFireMs}"
                }
                // Consuming the tick also re-anchors `{elapsed}` of the NEXT time alert to a
                // reminder the rider never heard. Pre-existing from the v17 same-tick rule;
                // not worth a second anchor field for one rendered token.
                lastTimeAlertFireMs = now
            }
            FuelingAlertScheduler.TickAction.QUIET_LOGGED -> {
                calibLogger?.log(CalibrationLogger.Event.FUELING_ALERT_QUIETED) {
                    "kind=hyd,reason=logged_after_tick"
                }
                lastTimeAlertFireMs = now
            }
            FuelingAlertScheduler.TickAction.NONE -> { /* nothing due, a held tick, or deferred by an emergency */ }
        }
        maybePeriodicLog(now)
        // See CarbsTracker.tick — re-publish to drive the status data fields off
        // a push channel (15-s cadence) instead of the old 1-Hz polling loops.
        publishStatus()
    }

    /** Pure read: is the deficit alert due now, or within [lookaheadMs]? Gate delegated to
     *  [FuelingAlertScheduler.shouldFireDeficit] (v18.2 B9) so carbs and hydration share it
     *  and it is unit-tested; the back-off counter is refreshed in [tick] before this runs. */
    private fun deficitDue(now: Long, lookaheadMs: Long): Boolean {
        val deficit = (accum.cumTargetMl - cumLoggedMl).toInt()
        return FuelingAlertScheduler.shouldFireDeficit(
            enabled                = config.hydrationDeficitAlertEnabled,
            deficit                = deficit,
            deficitThreshold       = config.hydrationDeficitThresholdMl,
            lastDeficitAlertFireMs = lastDeficitAlertFireMs,
            reminderIntervalMs     = config.hydrationDeficitReminderIntervalMin * 60_000L,
            initialDelayMs         = config.hydrationDeficitInitialDelayMin * 60_000L,
            cumLogged              = cumLoggedMl,
            sessionStartMs         = sessionStartMs,
            now                    = now,
            unackedFires           = deficitFiresSinceLog,
            lastRealLogMs          = lastRealLogMs,
            lookaheadMs            = lookaheadMs,
            deficitPerMs           = deficitRatePerMs,
            exactDeficit           = (accum.cumTargetMl - cumLoggedMl).toDouble(),
        )
    }

    /** Dispatch the deficit alert and stamp its cooldown. Only [tick] calls this, after
     *  [FuelingAlertScheduler.resolveTick] said so (never during an emergency). */
    private fun fireDeficitAlert(now: Long) {
        val deficit = (accum.cumTargetMl - cumLoggedMl).toInt()
        fireAlert("deficit", deficit, elapsedMinutesSinceRealLog(now))
        lastDeficitAlertFireMs = now
        deficitFiresSinceLog++
    }

    /** See [CarbsTracker.elapsedMinutesSinceRealLog] for the contract and the B33
     *  rationale. Same defensive guards: sentinel-zero on uninitialised
     *  `lastRealLogMs`, clamp on backwards NTP step. */
    private fun elapsedMinutesSinceRealLog(now: Long): Long =
        if (lastRealLogMs <= 0L) 0L
        else (now - lastRealLogMs).coerceAtLeast(0L) / 60_000

    /** See [CarbsTracker.elapsedMinutesSinceLastTimeAlert] for the rationale —
     *  same two-anchor pattern (last fire or session start) and same defensive
     *  guards. Used by [fireTimeAlert] so `{elapsed}` reads as "min since
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
     * mutates nothing. Used by [tick] (to gate firing, via [FuelingAlertScheduler.resolveTick]) and by
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
        lastRealLogMs = lastRealLogMs,
    )

    /** Dispatch the time-grid alert and stamp the tick. Only [tick] calls this, after
     *  [FuelingAlertScheduler.resolveTick] said so. `{elapsed}` measures since the last
     *  reminder (interval-driven), and `lastRealLogMs` stays untouched (I8). */
    private fun fireTimeAlert(now: Long) {
        fireAlert("time", (accum.cumTargetMl - cumLoggedMl).toInt(), elapsedMinutesSinceLastTimeAlert(now))
        lastTimeAlertFireMs = now
    }

    private fun buildAlertRequest(source: String, deficitMl: Int, elapsedMin: Long): com.enderthor.kSafe.extension.util.FuelingAlertRequest {
        // v18 L1 — see CarbsTracker.fireAlert for rationale.
        val dispatchedAtMs = clock.nowMs()
        // In dynamic-estimate mode the {target} placeholder must report the live drink
        // rate, not the fixed config value — a rider on a 30 °C ride configured for
        // 750 ml/h but estimating 1300 ml/h would otherwise see the wrong number in their
        // custom template. See [drinkRateMlPerHour] for the pre-estimate fallback.
        val effectiveTarget = drinkRateMlPerHour()
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
        val slots = listOf(
            com.enderthor.kSafe.extension.util.FuelSlot(1, config.drink1Label, config.drink1Ml),
            com.enderthor.kSafe.extension.util.FuelSlot(2, config.drink2Label, config.drink2Ml),
        )
        // null when no slot is usable (all drink sizes 0) — the presenter then shows no LOG
        // button (a plain InRideAlert) instead of a button that would log a phantom 0 ml entry.
        val item = com.enderthor.kSafe.extension.util.pickFuelItem(if (source == "deficit") deficitMl else null, slots)
        return com.enderthor.kSafe.extension.util.FuelingAlertRequest(
            title = title, detail = detail,
            // Factory — only built if the presenter takes the InRideAlert branch.
            inRideAlert = {
                InRideAlert(
                    // Unique-per-fire ID — see CarbsTracker.fireAlert for the rationale.
                    id = "ksafe-hyd-alert-$source-$dispatchedAtMs",
                    icon = R.drawable.ic_ksafe,
                    title = title,
                    detail = detail,
                    autoDismissMs = AUTO_DISMISS_MS,
                    backgroundColor = fuelingAlertColorRes(config.hydrationAlertBgColor),
                    textColor = ALERT_TX_COLOR,
                )
            },
            channel = com.enderthor.kSafe.extension.util.FuelingChannel.HYDRATION, item = item,
        )
    }

    fun buildPreviewRequest(): com.enderthor.kSafe.extension.util.FuelingAlertRequest =
        buildAlertRequest(source = "time", deficitMl = 0, elapsedMin = config.hydrationTimeIntervalMin.toLong())

    private fun fireAlert(source: String, deficitMl: Int, elapsedMin: Long) {
        config.hydBeepPattern.toPlayBeepPattern()?.let { karooSystem.dispatch(it) }
        onFuelingAlert(buildAlertRequest(source, deficitMl, elapsedMin))
        calibLogger?.log(CalibrationLogger.Event.FUELING_HYDRATION_FIRED) {
            "source=$source,deficit_ml=$deficitMl,since_log_min=$elapsedMin,cum_target=${accum.cumTargetMl.toInt()},cum_logged=$cumLoggedMl,beep=${config.hydBeepPattern}"
        }
        Timber.d(">>> Hydration alert fired ($source): deficit=${deficitMl}ml elapsed=${elapsedMin}min")
    }

    private fun maybePeriodicLog(now: Long) {
        if (calibLogger == null || !calibLogger.isEnabled) return
        if (now - lastPeriodicLogMs < PERIODIC_LOG_INTERVAL_MS) return
        lastPeriodicLogMs = now
        val deficit = (accum.cumTargetMl - cumLoggedMl).toInt()
        val mode = if (config.hydrationDynamicEstimateEnabled) "dynamic" else "fixed"
        val a = accum
        val integratedMs = a.coveredMs + a.lowConfMs
        calibLogger.log(CalibrationLogger.Event.FUELING_HYDRATION_PERIODIC) {
            "mode=$mode,rate_ml_h=${if (config.hydrationDynamicEstimateEnabled) lastSweatRateMlHr.toInt() else config.hydrationTargetMlPerHour}," +
                "conf=$lastSweatConfidence,hr=${lastHrBpm ?: -1},pwr=${lastPowerW ?: -1}," +
                "temp=${lastAmbientTempC ?: Double.NaN},rh=${lastHumidityPct ?: -1}," +
                "cum_target=${accum.cumTargetMl.toInt()},cum_logged=$cumLoggedMl,deficit=$deficit," +
                "mult=${config.hydrationSweatMultiplierPct},repl=${config.hydrationReplacementPct}," +
                "sweat=${a.cumSweatMl.toInt()},base=${a.cumSweatBaseMl.toInt()}," +
                "na_mg=${a.cumSodiumMg.toInt()},cov_pct=${coveragePct(rideTimeMs())}," +
                "low_pct=${if (integratedMs > 0) a.lowConfMs * 100 / integratedMs else -1}," +
                "spd=${lastSpeedKmh ?: -1.0}"
        }
    }

    /** coveredMs as a % of the Karoo ride time, or -1 when the ride time is unknown. */
    private fun coveragePct(rideMs: Long?): Long =
        if (rideMs == null || rideMs <= 0L) -1L else accum.coveredMs * 100 / rideMs
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
    /** Estimated sweat lost this session, after the personal multiplier (ml). */
    val cumSweatMl: Int = 0,
    /** Estimated sodium lost in sweat this session (mg). */
    val cumSodiumMg: Int = 0,
)
