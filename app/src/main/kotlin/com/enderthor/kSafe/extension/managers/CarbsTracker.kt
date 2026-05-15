package com.enderthor.kSafe.extension.managers

import android.content.Context
import com.enderthor.kSafe.R
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.extension.util.ALERT_DETAIL_MAX_CHARS
import com.enderthor.kSafe.extension.util.ALERT_TITLE_MAX_CHARS
import com.enderthor.kSafe.extension.util.IntensityZoneCalculator
import com.enderthor.kSafe.extension.util.ZoneSnapshot
import com.enderthor.kSafe.extension.util.ZoneSource
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
    // Tick at 15 s — same reasoning as HydrationTracker: alert cooldown is 5 min, the
    // deficit / time thresholds have minute granularity downstream, and the zone-aware
    // target rate changes slowly relative to a 15 s tick. Halves wakeups vs. 5 s.
    private val MONITOR_TICK_MS         = 15_000L
    private val ALERT_COOLDOWN_MS       = 5L * 60_000L
    private val PERIODIC_LOG_INTERVAL_MS = 120_000L

    /**
     * Speed below which the rider is treated as stationary and carb integration
     * pauses. 2 km/h sits below a slow walking pace, so any actual riding (even
     * pushing the bike up a hill) keeps integrating. Bench tests and traffic-light
     * stops correctly freeze the target. Same threshold used in [HydrationTracker].
     */
    private val MOVING_GATE_KMH = 2.0

    /**
     * If the SDK has been emitting the same bit-exact speed value for longer than
     * this, treat as stale (probable GPS lock loss in a tunnel / forest) and stop
     * integrating. Real GPS readings vary by ≥0.1 km/h between emissions even at
     * cruise, so cruise control doesn't hit this. Matches [CrashDetectionManager.GPS_STALE_MS].
     */
    private val SPEED_STALE_MS = 10_000L

    private val ALERT_BG_COLOR = 0xFFE65100.toInt()  // amber
    private val ALERT_TX_COLOR = 0xFFFFFFFF.toInt()  // white
    private val AUTO_DISMISS_MS = 10_000L

    // ─── Live data (push from KSafeExtension) ────────────────────────────────
    @Volatile private var lastUserProfile: UserProfile? = null
    @Volatile private var lastHrBpm: Int? = null
    @Volatile private var lastPowerW: Int? = null
    /** Latest speed reading in km/h. `null` until the SDK first emits — used by the
     *  movement gate in [tick] to skip integration when stationary. */
    @Volatile private var lastSpeedKmh: Double? = null
    /** Wall-clock timestamp (ms) of the most recent emission whose value actually
     *  changed (or the first emission ever). The SDK keeps re-emitting the last value
     *  bit-exact when GPS is lost, so a stretch without changes here is the GPS-stale
     *  signal — see [SPEED_STALE_MS] and the gate inside [tick]. */
    @Volatile private var lastSpeedChangeMs: Long = 0L

    // ─── Session state (reset by start()) ────────────────────────────────────
    @Volatile private var cumTargetG = 0f
    @Volatile private var cumLoggedG = 0
    @Volatile private var sessionStartMs = 0L
    @Volatile private var lastTickMs = 0L
    @Volatile private var lastLogMs = 0L
    @Volatile private var lastAlertMs = 0L
    @Volatile private var lastZoneSnapshot = ZoneSnapshot(ZoneSource.NONE, -1, 0, 1f)
    @Volatile private var lastPeriodicLogMs = 0L

    // ─── Per-slot undo state ────────────────────────────────────────────────
    // After [logEntry] for slot N, [lastLoggedGramsBySlot][N] holds the grams added
    // and [lastLogMsBeforeBySlot][N] holds the [lastLogMs] timestamp BEFORE the add,
    // so [undoLastForSlot] can reverse exactly what was added and restore the time-alert
    // clock to the value it had before the wrong tap. After a successful undo the slot's
    // entry is zeroed out — a second undo on the same slot is therefore a no-op until
    // a new [logEntry] populates it again. Indices 1..3; slot 0 is unused.
    private val lastLoggedGramsBySlot = IntArray(4)
    private val lastLogMsBeforeBySlot = LongArray(4)

    @Volatile private var config = KSafeConfig()
    private var monitorJob: Job? = null

    // ─── Status publisher ───────────────────────────────────────────────────
    /**
     * Push-based status feed for the carb / burn-rate / burned data fields. Replaces
     * the previous 1-Hz polling loop they each ran independently:
     *  - 4 fields × 3600 polls/h × N hours of riding got expensive on Karoo.
     *  - getStatus() allocated a fresh CarbStatus each poll regardless of whether
     *    anything had changed.
     *
     * Published from every place that mutates the integrator state: [tick],
     * [logEntry], [undoLastForSlot], [start], [resume], [stop], and from
     * [updateSpeed] but only on transitions across the movement gate so the
     * frequent same-state speed updates don't pull us back to 1-Hz wakeups.
     *
     * `null` means "no published snapshot yet" — consumers must render `---`
     * (same convention as the old polling code: `tracker?.getStatus()`).
     */
    private val _statusFlow = MutableStateFlow<CarbStatus?>(null)
    val statusFlow: StateFlow<CarbStatus?> get() = _statusFlow

    private fun publishStatus() { _statusFlow.value = getStatus() }

    // ─── Public API ──────────────────────────────────────────────────────────

    fun start(config: KSafeConfig) {
        this.config = config
        if (!config.carbsTrackerEnabled) return
        // Snapshot the previous monitor before resetting state so we can join() it inside
        // the new coroutine — guarantees the old tick loop is fully gone before the new one
        // runs, eliminating the late-tick race that could otherwise emit a spurious PERIODIC
        // log row with all-zero state right after a restart.
        val oldJob = monitorJob
        val now = System.currentTimeMillis()
        cumTargetG = 0f
        cumLoggedG = 0
        sessionStartMs = now
        lastTickMs = 0L                  // 0 = "no previous tick"; first tick won't accumulate
        lastLogMs = now                  // first time-based alert counts from session start
        lastAlertMs = 0L
        lastPeriodicLogMs = 0L
        lastZoneSnapshot = ZoneSnapshot(ZoneSource.NONE, -1, 0, 1f)
        for (i in lastLoggedGramsBySlot.indices) { lastLoggedGramsBySlot[i] = 0; lastLogMsBeforeBySlot[i] = 0L }
        monitorJob = scope.launch {
            oldJob?.cancelAndJoin()
            while (true) { delay(MONITOR_TICK_MS); tick() }
        }
        calibLogger?.log(CalibrationLogger.Event.FUELING_CARB_START) {
            "base_gph=${config.carbTargetGperHour}," +
                "deficit_alert=${config.carbDeficitAlertEnabled}," +
                "deficit_threshold_g=${config.carbDeficitThresholdG}," +
                "time_alert=${config.carbTimeAlertEnabled}," +
                "time_interval_min=${config.carbTimeIntervalMin}," +
                "time_initial_delay_min=${config.carbTimeInitialDelayMin}," +
                "beep=${config.carbBeepPattern}"
        }
        Timber.d("CarbsTracker started, target=${config.carbTargetGperHour} g/h")
        publishStatus()
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        Timber.d("CarbsTracker stopped")
        // State is intentionally retained so getSummary() / getStatus() remain readable
        // for the post-ride summary. Reset happens on the next start().
        // Publish so subscribers see isIntegrating = false (monitorJob is now null).
        publishStatus()
    }

    /**
     * Re-launch the monitor without resetting accumulators. Used by the master-switch
     * mid-ride OFF→ON transition so the rider's cumulative target/logged grams survive
     * a brief toggle. The first tick after resume skips integration (lastTickMs = 0L)
     * so the OFF window is not double-counted.
     */
    fun resume(config: KSafeConfig) {
        this.config = config
        if (!config.carbsTrackerEnabled) return
        val oldJob = monitorJob
        lastTickMs = 0L
        monitorJob = scope.launch {
            oldJob?.cancelAndJoin()
            while (true) { delay(MONITOR_TICK_MS); tick() }
        }
        Timber.d("CarbsTracker resumed (cumTargetG=${cumTargetG.toInt()}, cumLoggedG=$cumLoggedG)")
        publishStatus()
    }

    /**
     * See [HydrationTracker.updateConfig] for the rationale of the [isRecording] gate
     * on the auto-start branch. Same shape, same reason.
     */
    fun updateConfig(config: KSafeConfig, isRecording: Boolean) {
        val wasEnabled = this.config.carbsTrackerEnabled
        this.config = config
        if (!wasEnabled && config.carbsTrackerEnabled && isRecording) start(config)
        else if (wasEnabled && !config.carbsTrackerEnabled) stop()
    }

    fun updateUserProfile(p: UserProfile) { lastUserProfile = p }
    fun updateHr(bpm: Int)                { lastHrBpm = bpm }
    fun updatePower(w: Int)               { lastPowerW = w }
    fun updateSpeed(kmh: Double) {
        val prev = lastSpeedKmh
        // Stamp lastSpeedChangeMs on real value changes, on explicit-zero emissions
        // (rider stopped at the lights — GPS is alive even if 0.0 repeats bit-exact),
        // and on the very first emission. Mirrors CrashDetectionManager.updateSpeed
        // so the "stopped at lights" case isn't misclassified as "GPS-stuck stale".
        if (prev == null || prev != kmh || kmh == 0.0) {
            lastSpeedChangeMs = System.currentTimeMillis()
        }
        // Detect movement-gate crossings so the status flow re-publishes when the
        // integrator's isIntegrating bit flips. We deliberately do NOT publish on
        // every speed emission (~1 Hz) — that would defeat the polling→push
        // optimisation. Inside-gate transitions and staleness transitions are
        // picked up by the 15-s tick() publish.
        val wasMoving = prev != null && prev >= MOVING_GATE_KMH
        val isMoving = kmh >= MOVING_GATE_KMH
        lastSpeedKmh = kmh
        if (wasMoving != isMoving) publishStatus()
    }

    /**
     * Log a single tap on slot 1, 2 or 3. Adds the configured grams to the cumulative
     * log. Returns the grams that were actually added — the caller uses it to render
     * the LOGGED flash with the value frozen at log time, so a later config edit to
     * the slot's grams doesn't desync the UI from what's stored. Returns `0` for an
     * invalid slot.
     */
    fun logEntry(slot: Int): Int {
        val grams = when (slot) {
            1 -> config.carb1Grams
            2 -> config.carb2Grams
            3 -> config.carb3Grams
            else -> return 0
        }
        // Save what we're about to mutate so an undo within the on-screen window can
        // reverse exactly this entry without affecting unrelated state.
        lastLogMsBeforeBySlot[slot] = lastLogMs
        lastLoggedGramsBySlot[slot] = grams
        cumLoggedG += grams
        lastLogMs = System.currentTimeMillis()
        calibLogger?.log(CalibrationLogger.Event.FUELING_CARB_LOGGED) {
            "slot=$slot,grams=$grams,cum_logged=$cumLoggedG,cum_target=${cumTargetG.toInt()}"
        }
        publishStatus()
        return grams
    }

    /**
     * Reverse the most recent [logEntry] for [slot]. Returns the grams undone, or `0` if
     * the slot has nothing left to undo (already undone, or never logged this session).
     * Per-slot, single-shot — after an undo the slot's saved entry is cleared, so a
     * second undo on the same slot before the next log is a no-op.
     */
    fun undoLastForSlot(slot: Int): Int {
        if (slot !in 1..3) return 0
        val grams = lastLoggedGramsBySlot[slot]
        if (grams <= 0) return 0
        cumLoggedG = (cumLoggedG - grams).coerceAtLeast(0)
        lastLogMs = lastLogMsBeforeBySlot[slot]
        lastLoggedGramsBySlot[slot] = 0
        lastLogMsBeforeBySlot[slot] = 0L
        calibLogger?.log(CalibrationLogger.Event.FUELING_CARB_UNDONE) {
            "slot=$slot,grams=-$grams,cum_logged=$cumLoggedG,cum_target=${cumTargetG.toInt()}"
        }
        publishStatus()
        return grams
    }

    /**
     * Builds an immutable snapshot of the current tracker state. Each field is read once;
     * because the underlying fields are @Volatile, the snapshot is internally consistent
     * to within one tick's worth of integration (well under UX tolerance).
     */
    fun getStatus(): CarbStatus = CarbStatus(
        cumTargetG = cumTargetG.toInt(),
        cumLoggedG = cumLoggedG,
        deficitG = (cumTargetG - cumLoggedG).toInt(),
        deficitThresholdG = config.carbDeficitThresholdG,
        zoneSnapshot = lastZoneSnapshot,
        burnRateGph = computeBurnRateGph(),
        // Integration is happening iff the monitor loop is alive AND the movement
        // gate is currently passing AND the speed reading is fresh (not stuck on a
        // last-known value from a lost GPS fix). Mirrors the gate inside tick().
        isIntegrating = monitorJob != null && run {
            val speed = lastSpeedKmh ?: return@run false
            val stale = lastSpeedChangeMs > 0 &&
                (System.currentTimeMillis() - lastSpeedChangeMs) > SPEED_STALE_MS
            !stale && speed >= MOVING_GATE_KMH
        },
    )

    /**
     * Instantaneous carb burn rate in g/h, equal to the configured base target modulated by
     * the current zone multiplier. Single source of truth — the data field, the calibration
     * log fire payload and the periodic log row all read through this helper so future
     * tweaks to the formula stay in lock-step.
     */
    private fun computeBurnRateGph(): Int =
        (config.carbTargetGperHour * lastZoneSnapshot.multiplier).toInt()

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

        // Movement gate — no integration when stationary. Cycling: you only burn the
        // carbs you need to replace when moving (pedalling OR coasting). Bench tests
        // and traffic-light stops correctly freeze the cumulative target.
        //
        // Two ways to be "not moving":
        //  1. SDK reading is below MOVING_GATE_KMH (stopped or never started).
        //  2. SDK reading is stale — the value hasn't changed for SPEED_STALE_MS, which
        //     means GPS lock is lost and the SDK is repeating the last known value.
        //     If we trusted the stuck value we'd integrate during a long tunnel even
        //     after the rider has stopped inside it. Pessimistic: stale ⇒ frozen.
        //     Resumes automatically on the first emission with a new value.
        val speed = lastSpeedKmh
        val stale = lastSpeedChangeMs > 0 && (now - lastSpeedChangeMs) > SPEED_STALE_MS
        val moving = !stale && speed != null && speed >= MOVING_GATE_KMH

        if (lastTickMs != 0L && moving) {
            // coerceAtLeast(0L): a wall-clock NTP correction can push `now` backwards by
            // seconds — we never want cumTargetG to decrease, so clamp negative dt to 0.
            val dtSec = (now - lastTickMs).coerceAtLeast(0L) / 1000f
            val ratePerSec = config.carbTargetGperHour / 3600f
            cumTargetG += dtSec * ratePerSec * zone.multiplier
        }
        // Update lastTickMs on every tick (moving or not) so a stationary→moving
        // transition doesn't claim the entire stationary period in one big dt.
        lastTickMs = now

        evaluateDeficitAlert(now)
        evaluateTimeAlert(now)
        maybePeriodicLog(now)
        // Re-publish at the end so subscribers see the updated deficit, burn rate,
        // and isIntegrating (covers staleness transitions that updateSpeed can't see
        // because no value change triggers them). 15-s cadence — the carb / hyd
        // data fields used to poll at 1 Hz, so this is a 95 % wakeup reduction.
        publishStatus()
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
        // Initial-delay grace period — only applies to the FIRST alert in the session and only
        // if the user hasn't logged anything yet. Once any alert fires or the user logs an item,
        // the regular interval logic takes over.
        val isFirstAlert = lastAlertMs == 0L && cumLoggedG == 0
        if (isFirstAlert && config.carbTimeInitialDelayMin > 0) {
            val initialDelayMs = config.carbTimeInitialDelayMin * 60_000L
            if (now - sessionStartMs < initialDelayMs) return
        }
        val intervalMs = config.carbTimeIntervalMin * 60_000L
        if (now - lastLogMs < intervalMs) return
        if (now - lastAlertMs < ALERT_COOLDOWN_MS) return
        val deficit = (cumTargetG - cumLoggedG).toInt()
        fireAlert(source = "time", deficit = deficit, elapsedMin = (now - lastLogMs) / 60_000)
    }

    private fun fireAlert(source: String, deficit: Int, elapsedMin: Long) {
        lastAlertMs = System.currentTimeMillis()
        val tokens = mapOf(
            "deficit" to deficit.toString(),
            "elapsed" to elapsedMin.toString(),
            "target"  to config.carbTargetGperHour.toString(),
        )
        val detailTemplate = config.carbAlertCustomDetail.ifBlank {
            if (source == "deficit") context.getString(R.string.fueling_carb_alert_detail_deficit)
            else                     context.getString(R.string.fueling_carb_alert_detail_time)
        }
        val detail = renderAlertText(detailTemplate, tokens, maxLength = ALERT_DETAIL_MAX_CHARS)
        val title = renderAlertText(
            config.carbAlertCustomTitle.ifBlank { context.getString(R.string.fueling_carb_alert_title) },
            tokens,
            maxLength = ALERT_TITLE_MAX_CHARS,
        )
        config.carbBeepPattern.toPlayBeepPattern()?.let { karooSystem.dispatch(it) }
        karooSystem.dispatch(InRideAlert(
            id = "ksafe-carb-alert-$source",
            icon = R.drawable.ic_ksafe,
            title = title,
            detail = detail,
            autoDismissMs = AUTO_DISMISS_MS,
            backgroundColor = ALERT_BG_COLOR,
            textColor = ALERT_TX_COLOR,
        ))
        val burnRateGph = computeBurnRateGph()
        calibLogger?.log(CalibrationLogger.Event.FUELING_CARB_FIRED) {
            // Locale.US: the calibration CSV uses comma as field separator, so we must NOT
            // let the default Locale turn "1.15" into "1,15" on es/fr/de devices.
            String.format(
                java.util.Locale.US,
                "source=%s,deficit_g=%d,since_log_min=%d,cum_target=%d,cum_logged=%d,burn_rate_gph=%d,zone=%s/%d/%d,multiplier=%.2f,beep=%s",
                source, deficit, elapsedMin,
                cumTargetG.toInt(), cumLoggedG, burnRateGph,
                lastZoneSnapshot.source, lastZoneSnapshot.index, lastZoneSnapshot.total,
                lastZoneSnapshot.multiplier, config.carbBeepPattern,
            )
        }
        Timber.d(">>> Carb alert fired ($source): deficit=${deficit}g elapsed=${elapsedMin}min")
    }

    private fun maybePeriodicLog(now: Long) {
        if (calibLogger == null || !calibLogger.isEnabled) return
        if (now - lastPeriodicLogMs < PERIODIC_LOG_INTERVAL_MS) return
        lastPeriodicLogMs = now
        val deficit = (cumTargetG - cumLoggedG).toInt()
        val burnRateGph = computeBurnRateGph()
        calibLogger.log(CalibrationLogger.Event.FUELING_CARB_PERIODIC) {
            // Locale.US — see fireAlert above.
            String.format(
                java.util.Locale.US,
                "cum_target=%d,cum_logged=%d,deficit=%d,burn_rate_gph=%d,zone_source=%s,zone_idx=%d,zone_total=%d,multiplier=%.2f,hr=%d,power=%d",
                cumTargetG.toInt(), cumLoggedG, deficit, burnRateGph,
                lastZoneSnapshot.source, lastZoneSnapshot.index, lastZoneSnapshot.total,
                lastZoneSnapshot.multiplier,
                lastHrBpm ?: -1, lastPowerW ?: -1,
            )
        }
    }
}

/**
 * Snapshot of the carb tracker state. The status data field polls [CarbsTracker.getStatus]
 * once per second; it is also safe to read on demand from any thread (Volatile field reads).
 */
data class CarbStatus(
    val cumTargetG: Int,
    val cumLoggedG: Int,
    val deficitG: Int,
    val deficitThresholdG: Int,
    val zoneSnapshot: ZoneSnapshot,
    val burnRateGph: Int,
    /** True when the tracker is actively integrating right now (movement gate
     *  passing + tracker running). Used by [CarbBurnRateDataType] to decide
     *  whether to display the live rate or `---`, keeping all three carb fields
     *  (burn rate, burned, status) coherent: if integration is paused, every
     *  field is frozen; if it's running, every field shows a live number. */
    val isIntegrating: Boolean,
)

/** Totals captured at end-of-ride for the post-ride summary InRideAlert. */
data class CarbSummary(
    val cumTargetG: Int,
    val cumLoggedG: Int,
    val deficitG: Int,
    val percentageHit: Int,
)
