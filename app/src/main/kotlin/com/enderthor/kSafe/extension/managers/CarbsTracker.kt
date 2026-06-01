package com.enderthor.kSafe.extension.managers

import android.content.Context
import com.enderthor.kSafe.R
import com.enderthor.kSafe.data.CarbFuelingState
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.data.fuelingAlertColorRes
import com.enderthor.kSafe.extension.util.ABSORPTION_CAP_GPH
import com.enderthor.kSafe.extension.util.ALERT_DETAIL_MAX_CHARS
import com.enderthor.kSafe.extension.util.ALERT_TITLE_MAX_CHARS
import com.enderthor.kSafe.extension.util.CarbBurnEstimator
import com.enderthor.kSafe.extension.util.CarbIntegrator
import com.enderthor.kSafe.extension.util.FuelingAlertScheduler
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
    private val onFuelingAlert: (com.enderthor.kSafe.extension.util.FuelingAlertRequest) -> Unit,
    /** True while a crash / SOS / check-in is active. A fueling alert that comes due then is
     *  DEFERRED (not fired, cooldown not consumed) so it doesn't beep over the SOS and re-fires
     *  once the emergency clears. Injected so the tracker stays decoupled from EmergencyManager. */
    private val isEmergencyActive: () -> Boolean,
    private val calibLogger: CalibrationLogger? = null,
) {

    // ─── Constants ───────────────────────────────────────────────────────────
    // Tick at 15 s — same reasoning as HydrationTracker: alert cooldown is 5 min, the
    // deficit / time thresholds have minute granularity downstream, and the zone-aware
    // target rate changes slowly relative to a 15 s tick. Halves wakeups vs. 5 s.
    private val MONITOR_TICK_MS         = 15_000L
    private val PERIODIC_LOG_INTERVAL_MS = 120_000L

    /** Speed gate + GPS-stale window are owned by [CarbIntegrator] now so the
     *  pure integration logic can be tested without a tracker harness. The
     *  per-tick math reads them from there; the legacy fields stayed in this
     *  class only because [updateSpeed] needs the same gate for its
     *  publishStatus transition logic. */
    private val MOVING_GATE_KMH = CarbIntegrator.MOVING_GATE_KMH
    private val SPEED_STALE_MS  = CarbIntegrator.SPEED_STALE_MS
    /** A HR / power sample older than this is treated as "sensor gone" by the
     *  burn estimator. 15 s tolerates a few missed ~1 Hz samples (brief BLE
     *  hiccup) without flicker, but reacts well within one 15 s tick when a
     *  sensor truly drops. Matches the medical detector's HR_STALE_MS feel. */
    private val SENSOR_STALE_MS = 15_000L

    // See HydrationTracker — InRideAlert.backgroundColor / .textColor are @ColorRes,
    // not @ColorInt. Use R.color.* resources or the host's getColor() crashes.
    // BG colour is rider-configurable via config.carbAlertBgColor — see fireAlert.
    private val ALERT_TX_COLOR = R.color.alert_text_white
    private val AUTO_DISMISS_MS = 10_000L

    // ─── Live data (push from KSafeExtension) ────────────────────────────────
    @Volatile private var lastUserProfile: UserProfile? = null
    @Volatile private var lastHrBpm: Int? = null
    @Volatile private var lastPowerW: Int? = null
    /** Wall-clock of the last HR / power emission. A sensor that stops emitting
     *  (battery dies, BLE drops) leaves [lastHrBpm] / [lastPowerW] frozen at its
     *  last value forever — the stream's `?: return` never pushes a "gone" signal.
     *  These timestamps let [currentBurnEstimate] treat a value older than
     *  [SENSOR_STALE_MS] as absent, so: (a) if one sensor dies but the other is
     *  live the estimate falls back to the live tier, and (b) if BOTH are stale
     *  the estimate drops to confidence=NONE → burn rate 0 → the instantaneous
     *  field stops showing a stale number and the cumulative / average freeze
     *  (CarbIntegrator skips active-time accrual when effectiveGph == 0). */
    @Volatile private var lastHrUpdateMs = 0L
    @Volatile private var lastPowerUpdateMs = 0L
    /** Latest speed reading in km/h. `null` until the SDK first emits — used by the
     *  movement gate in [tick] to skip integration when stationary. */
    @Volatile private var lastSpeedKmh: Double? = null
    /** Wall-clock timestamp (ms) of the most recent emission whose value actually
     *  changed (or the first emission ever). The SDK keeps re-emitting the last value
     *  bit-exact when GPS is lost, so a stretch without changes here is the GPS-stale
     *  signal — see [SPEED_STALE_MS] and the gate inside [tick]. */
    @Volatile private var lastSpeedChangeMs: Long = 0L

    // ─── Session state (reset by start()) ────────────────────────────────────
    @Volatile private var cumBurnedG = 0f
    @Volatile private var cumLoggedG = 0
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
    /**
     * Wall-clock ms of the last REAL rider log (independent of time-alert fires). Updated only
     * by [logEntry] (and rolled back by [undoLastForSlot]) so `(now - lastRealLogMs)` always
     * reflects the true time since the rider last logged carbs. Used to compute the
     * `{elapsed}` token in alert templates.
     *
     * I8 fix: the F1 fix repurposed [lastLogMs] as a "time mark" that also bumps on
     * time-alert fires, which is correct for the interval gate but wrong for `{elapsed}`
     * in a subsequent deficit alert (the rider had not actually logged anything, but
     * `{elapsed}` would otherwise show "time since last alert" instead of "time since
     * last real log"). Splitting the two responsibilities into separate fields keeps
     * both behaviours correct.
     *
     * Note on testing: the F1+I8 interaction is verified by inspection rather than by a
     * unit test — the observable signal (the rendered `{elapsed}` text in an [InRideAlert])
     * is produced via [fireAlert] which dispatches through [KarooSystemService] and reads
     * resource strings via [android.content.Context]. The JVM unit-test harness in this
     * project does not mock either (no Robolectric, no fueling-tracker harness), so a
     * faithful end-to-end assertion would require infrastructure out of scope for this
     * fix. The contract is short enough to verify by reading [evaluateDeficitAlert] /
     * [evaluateTimeAlert] / [logEntry] / [undoLastForSlot] together.
     */
    @Volatile private var lastRealLogMs = 0L
    // v18 L1: `lastAlertMs` removed — was used only to build the `InRideAlert.id`
    // ("ksafe-carb-alert-$source-${lastAlertMs}") and tracked the most recent
    // dispatch wall-clock. Replaced by a local `System.currentTimeMillis()` at
    // dispatch time in [fireAlert] — the cooldown gates are now all per-source
    // (`lastTimeAlertFireMs`, `lastDeficitAlertFireMs`), so the shared field
    // had no remaining consumer. Removed from [CarbFuelingState] persistence
    // too; old snapshots with the field are silently dropped by
    // `ignoreUnknownKeys = true`.
    /**
     * Wall-clock ms when a TIME-source alert last fired in this session. Drives
     * the pure-interval gate in [evaluateTimeAlert]:
     * `now - lastTimeAlertFireMs >= intervalMs`. **Not updated by rider logs** —
     * the v17 user-facing semantics is "remind me every N minutes", independent
     * of when the rider last ate. 0 = no time alert has fired yet this session.
     */
    @Volatile private var lastTimeAlertFireMs = 0L
    /**
     * Wall-clock ms when a DEFICIT-source alert last fired in this session. Drives
     * the configurable reminder cooldown (`config.carbDeficitReminderIntervalMin *
     * 60_000`). Independent of [lastTimeAlertFireMs].
     */
    @Volatile private var lastDeficitAlertFireMs = 0L
    /**
     * Cumulative milliseconds the tracker spent actively integrating (movement
     * gate passing + burn > 0). Drives [computeAvgBurnRateGph]: avg over only
     * the active portion of the ride is much more representative than avg over
     * total elapsed time (which would be diluted by traffic-light stops, café
     * stops, etc.). Persisted in [CarbFuelingState] so the average survives an
     * extension restart mid-ride.
     */
    @Volatile private var activeIntegrationMs: Long = 0L
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
    /**
     * Per-slot snapshot of [lastRealLogMs] taken at log time so [undoLastForSlot] can roll
     * it back exactly. Parallel to [lastLogMsBeforeBySlot] — kept as a separate array because
     * [lastLogMs] is now bumped by time-alert fires too (F1) while [lastRealLogMs] is bumped
     * only by real logs (I8); the two snapshots can therefore legitimately differ.
     */
    private val lastRealLogMsBeforeBySlot = LongArray(4)

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

    fun start(config: KSafeConfig, restoreFrom: CarbFuelingState? = null) {
        this.config = config
        if (!config.carbsTrackerEnabled) return
        // Snapshot the previous monitor before resetting state so we can join() it inside
        // the new coroutine — guarantees the old tick loop is fully gone before the new one
        // runs, eliminating the late-tick race that could otherwise emit a spurious PERIODIC
        // log row with all-zero state right after a restart.
        val oldJob = monitorJob
        val now = System.currentTimeMillis()
        if (restoreFrom != null) {
            // Extension was killed mid-ride and we're restoring from persisted snapshot.
            // Keep accumulators + alert/log timestamps so the rider doesn't lose their
            // session totals; the next tick won't integrate (lastTickMs = 0L sentinel),
            // so the OFF window between the crash and now isn't double-counted.
            cumBurnedG = restoreFrom.cumBurnedG
            cumLoggedG = restoreFrom.cumLoggedG
            sessionStartMs = restoreFrom.sessionStartMs.takeIf { it > 0 } ?: now
            lastLogMs = restoreFrom.lastLogMs.takeIf { it > 0 } ?: now
            // I8 migration — pre-fix snapshots have no lastRealLogMs (defaults to 0). Under
            // v14 / pre-F1 semantics, lastLogMs was "last real log", so fall back to it when
            // the persisted lastRealLogMs is absent. New snapshots write both fields and the
            // takeIf below picks the saved value directly.
            lastRealLogMs = restoreFrom.lastRealLogMs.takeIf { it > 0 } ?: lastLogMs
            // v17 new fields. Old snapshots have 0 → "never fired this session";
            // the first-fire initial-delay gate runs on resume.
            lastTimeAlertFireMs = restoreFrom.lastTimeAlertFireMs
            lastDeficitAlertFireMs = restoreFrom.lastDeficitAlertFireMs
            // v18 — preserve active-integration time so the session-average
            // burn rate doesn't get artificially inflated after a restart.
            activeIntegrationMs = restoreFrom.activeIntegrationMs
        } else {
            cumBurnedG = 0f
            cumLoggedG = 0
            sessionStartMs = now
            lastLogMs = now                  // {elapsed} fallback when no real log yet
            lastRealLogMs = now              // {elapsed} measured from session start until first log
            lastTimeAlertFireMs = 0L
            lastDeficitAlertFireMs = 0L
            activeIntegrationMs = 0L
        }
        lastTickMs = 0L                  // 0 = "no previous tick"; first tick won't accumulate
        lastPeriodicLogMs = 0L
        lastZoneSnapshot = ZoneSnapshot(ZoneSource.NONE, -1, 0, 1f)
        for (i in lastLoggedGramsBySlot.indices) {
            lastLoggedGramsBySlot[i] = 0
            lastLogMsBeforeBySlot[i] = 0L
            lastRealLogMsBeforeBySlot[i] = 0L
        }
        monitorJob = scope.launch {
            oldJob?.cancelAndJoin()
            // H3 — defensive try/catch around tick() so a single throw doesn't
            // disable carb integration and alerts for the rest of the ride.
            while (true) {
                delay(MONITOR_TICK_MS)
                try { tick() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { Timber.e(e, "CarbsTracker.tick threw — continuing") }
            }
        }
        // v18 M5: include the tier the burn estimator picked AT SESSION START so
        // post-mortem analysis of the CSV can tell "no HR/Pwr at start → Swain"
        // apart from "POWER from the start". The tier may change later in the ride
        // (e.g. HR connects mid-ride, promoting Tier 3 → Tier 2) — every FIRE /
        // PERIODIC row carries its own `confidence=` value for that.
        val startTier = currentBurnEstimate().confidence
        calibLogger?.log(CalibrationLogger.Event.FUELING_CARB_START) {
            "model=v18_physiology," +
                "tier_at_start=$startTier," +
                "age=${config.riderAge}," +
                "sex=${config.riderSex}," +
                "deficit_alert=${config.carbDeficitAlertEnabled}," +
                "deficit_threshold_g=${config.carbDeficitThresholdG}," +
                "deficit_initial_delay_min=${config.carbDeficitInitialDelayMin}," +
                "time_alert=${config.carbTimeAlertEnabled}," +
                "time_interval_min=${config.carbTimeIntervalMin}," +
                "time_initial_delay_min=${config.carbTimeInitialDelayMin}," +
                "beep=${config.carbBeepPattern}," +
                "restored=${restoreFrom != null}"
        }
        Timber.d("CarbsTracker started, tier=$startTier, age=${config.riderAge}, sex=${config.riderSex}, restored=${restoreFrom != null}")
        publishStatus()
    }

    /**
     * Snapshot of fields that must survive an extension restart. Read on demand by
     * [KSafeExtension]'s persistence loop; safe to call from any thread (Volatile reads).
     * Returns the current accumulators + cooldown / session timestamps so a restored
     * tracker can resume mid-ride without losing fueling totals or re-firing an alert
     * that already fired before the crash.
     */
    fun getPersistableState(): CarbFuelingState = CarbFuelingState(
        cumBurnedG = cumBurnedG,
        cumLoggedG = cumLoggedG,
        sessionStartMs = sessionStartMs,
        lastLogMs = lastLogMs,
        lastRealLogMs = lastRealLogMs,
        lastTimeAlertFireMs = lastTimeAlertFireMs,
        lastDeficitAlertFireMs = lastDeficitAlertFireMs,
        activeIntegrationMs = activeIntegrationMs,
    )

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
            // H3 — defensive try/catch around tick() so a single throw doesn't
            // disable carb integration and alerts for the rest of the ride.
            while (true) {
                delay(MONITOR_TICK_MS)
                try { tick() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { Timber.e(e, "CarbsTracker.tick threw — continuing") }
            }
        }
        Timber.d("CarbsTracker resumed (cumBurnedG=${cumBurnedG.toInt()}, cumLoggedG=$cumLoggedG)")
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
    fun updateHr(bpm: Int)                { lastHrBpm = bpm; lastHrUpdateMs = System.currentTimeMillis() }
    fun updatePower(w: Int)               { lastPowerW = w; lastPowerUpdateMs = System.currentTimeMillis() }
    fun updateSpeed(kmh: Double) {
        // D6/G2 fix — drop NaN AND Infinity samples; see MedicalEpisodeDetector
        // for the IEEE-754 taint mechanism this guards against.
        if (!kmh.isFinite()) return
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
        lastRealLogMsBeforeBySlot[slot] = lastRealLogMs
        lastLoggedGramsBySlot[slot] = grams
        cumLoggedG += grams
        val now = System.currentTimeMillis()
        lastLogMs = now
        lastRealLogMs = now
        calibLogger?.log(CalibrationLogger.Event.FUELING_CARB_LOGGED) {
            "slot=$slot,grams=$grams,cum_logged=$cumLoggedG,cum_burned=${cumBurnedG.toInt()}"
        }
        publishStatus()
        return grams
    }

    /** Time of the most recent [logAmount] (combined-field) log. Lets [hasInterleavedLogAfter]
     *  see a combined log it would otherwise miss (logAmount carries no slot), so a per-slot
     *  [undoLastForSlot] can't roll the {elapsed} timer back past an interleaved combined log. */
    @Volatile private var lastAmountLogMs = 0L

    /** Log an EXPLICIT [grams] (combined fuel-log field). Feeds the same [cumLoggedG] total as
     *  [logEntry]. Returns the grams logged (0 if non-positive). */
    fun logAmount(grams: Int): Int {
        if (grams <= 0) return 0
        cumLoggedG += grams
        val now = System.currentTimeMillis()
        lastLogMs = now
        lastRealLogMs = now
        lastAmountLogMs = now
        calibLogger?.log(CalibrationLogger.Event.FUELING_CARB_LOGGED) {
            "slot=combined,grams=$grams,cum_logged=$cumLoggedG,cum_burned=${cumBurnedG.toInt()}"
        }
        publishStatus()
        return grams
    }

    /** Reverse a previous [logAmount] of exactly [grams]. Clamps the total at >= 0.
     *  By design this rolls back only [cumLoggedG] (the deficit-relevant total), NOT
     *  [lastRealLogMs] / [lastLogMs]: after a rare log-then-undo the `{elapsed}` token may
     *  read from the undone tap until the next real log. We deliberately do not restore the
     *  timestamps — the alternative (not bumping them on [logAmount]) would fire a false
     *  "you haven't fueled" alert right after a genuine combined log, which is worse. */
    fun undoAmount(grams: Int) {
        if (grams <= 0) return
        cumLoggedG = (cumLoggedG - grams).coerceAtLeast(0)
        calibLogger?.log(CalibrationLogger.Event.FUELING_CARB_UNDONE) {
            "slot=combined,grams=-$grams,cum_logged=$cumLoggedG,cum_burned=${cumBurnedG.toInt()}"
        }
        publishStatus()
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
        // E2 fix — only roll [lastLogMs] / [lastRealLogMs] back to the pre-slot snapshot
        // when no OTHER slot has logged since this slot did. We can detect that by
        // checking whether the current value is still strictly greater than the snapshot:
        // an unmodified snapshot means our `logEntry` stamp is the most recent. If
        // another slot has logged in between, its stamp is now the live value and the
        // snapshot would silently roll the {elapsed} token back past it — making the
        // alert lie about how long it has been since the LAST log of any kind.
        val snapLog = lastLogMsBeforeBySlot[slot]
        val snapReal = lastRealLogMsBeforeBySlot[slot]
        if (snapLog > 0L && lastLogMs >= snapLog &&
            // No interleaved log means our post-stamp is still the live one. We don't
            // store post-stamps, but we know `lastLogMs == lastLogMs at logEntry` only
            // if nothing has touched it since — and the same is true of lastRealLogMs.
            // The safe-rollback predicate is "the snapshot is strictly older than the
            // current live value by ONLY the gap this slot's logEntry introduced". We
            // approximate that by comparing live to the slot's saved post-stamp; since
            // we don't store the post-stamp, use the snapshots-of-record pair: if any
            // other slot has interleaved, AT LEAST one of the cross-slot snapshots will
            // also be > snapLog. That's tracked by lastLoggedGramsBySlot for the OTHER
            // slots — if any of them is > 0 with a snapshot > snapLog, an interleave
            // happened and we must NOT roll back.
            !hasInterleavedLogAfter(slot, snapLog)) {
            lastLogMs = snapLog
            lastRealLogMs = snapReal
        }
        lastLoggedGramsBySlot[slot] = 0
        lastLogMsBeforeBySlot[slot] = 0L
        lastRealLogMsBeforeBySlot[slot] = 0L
        calibLogger?.log(CalibrationLogger.Event.FUELING_CARB_UNDONE) {
            "slot=$slot,grams=-$grams,cum_logged=$cumLoggedG,cum_burned=${cumBurnedG.toInt()}"
        }
        publishStatus()
        return grams
    }

    /** Returns true if a slot OTHER than [excludeSlot] has logged after [thresholdMs] —
     *  i.e. its [lastLogMsBeforeBySlot] is at or after the timestamp we'd be rolling back
     *  to. When true, undoing [excludeSlot]'s timestamp would silently destroy the time
     *  mark of that interleaved log. */
    private fun hasInterleavedLogAfter(excludeSlot: Int, thresholdMs: Long): Boolean {
        // A combined-field logAmount carries no slot, so it isn't in the per-slot arrays below;
        // check its timestamp explicitly or a per-slot undo could roll the timer back past it.
        if (lastAmountLogMs >= thresholdMs) return true
        for (i in 1..3) {
            if (i == excludeSlot) continue
            if (lastLoggedGramsBySlot[i] > 0 && lastLogMsBeforeBySlot[i] >= thresholdMs) return true
        }
        return false
    }

    /**
     * Builds an immutable snapshot of the current tracker state. Each field is read once;
     * because the underlying fields are @Volatile, the snapshot is internally consistent
     * to within one tick's worth of integration (well under UX tolerance).
     */
    fun getStatus(): CarbStatus {
        // v18 M1: read the burn estimator ONCE per status snapshot. Previously
        // `burnRateGph = computeBurnRateGph()` (= one estimator call) plus
        // `burnConfidence = currentBurnEstimate().confidence` (another call)
        // ran the full Keytel/Swain/zone-classify pipeline twice for every
        // poll. The data fields collect at 1 Hz from `statusFlow`, so the
        // duplication cost was ~3600 redundant estimator calls per hour.
        val burn = currentBurnEstimate()
        val burnRateGph = burn.gph
            .coerceAtMost(ABSORPTION_CAP_GPH.toDouble())
            .toInt()
        return CarbStatus(
            cumBurnedG = cumBurnedG.toInt(),
            cumLoggedG = cumLoggedG,
            deficitG = (cumBurnedG - cumLoggedG).toInt(),
            deficitThresholdG = config.carbDeficitThresholdG,
            zoneSnapshot = lastZoneSnapshot,
            burnRateGph = burnRateGph,
            avgBurnRateGph = computeAvgBurnRateGph(),
            burnConfidence = burn.confidence,
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
    }

    /**
     * Session-average carb burn rate in g/h, averaged across only the time the
     * tracker was actively integrating (NOT total elapsed time — café stops and
     * traffic-light idle periods are excluded so the number reflects the
     * rider's average effort, not their stop-light luck). Returns 0 until at
     * least one tick of integration has happened.
     */
    private fun computeAvgBurnRateGph(): Int {
        if (activeIntegrationMs <= 0L) return 0
        val hours = activeIntegrationMs / 3_600_000.0
        return (cumBurnedG / hours).toInt()
    }

    /**
     * Instantaneous carb burn rate in g/h. v18: comes from the physiological
     * [CarbBurnEstimator] (power tier 1 / Keytel tier 2 / Swain tier 3) modulated
     * by the CHO fraction at the current intensity zone, clamped to the gut
     * absorption ceiling so the displayed value can never imply intake the
     * rider cannot physically absorb. Single source of truth — the data field,
     * the calibration log payload and the periodic log row all read through
     * this helper so future tweaks stay in lock-step.
     */
    private fun computeBurnRateGph(): Int = currentBurnEstimate().gph
        .coerceAtMost(ABSORPTION_CAP_GPH.toDouble())
        .toInt()

    /** Helper: shared CarbBurnEstimator call so [computeBurnRateGph] and [tick]'s
     *  integrator agree exactly. Reads the latest live inputs — HR, power, profile,
     *  and the rider's configured age + sex. Returns [CarbBurnEstimator.BurnEstimate.NONE]
     *  when none of the three tiers can fire. */
    private fun currentBurnEstimate(): CarbBurnEstimator.BurnEstimate {
        // Only feed the estimator sensors that emitted within SENSOR_STALE_MS.
        // A dead sensor's last value lingers in lastHrBpm/lastPowerW forever, so
        // gating on freshness is what makes the tier fallback (use whichever is
        // still live) and the "no live sensor → NONE" behaviour work — see the
        // lastHrUpdateMs KDoc.
        val now = System.currentTimeMillis()
        val freshHr = lastHrBpm?.takeIf { lastHrUpdateMs > 0L && now - lastHrUpdateMs <= SENSOR_STALE_MS }
        val freshPower = lastPowerW?.takeIf { lastPowerUpdateMs > 0L && now - lastPowerUpdateMs <= SENSOR_STALE_MS }
        return CarbBurnEstimator.estimate(
            hrBpm = freshHr,
            powerW = freshPower,
            profile = lastUserProfile,
            riderAge = config.riderAge,
            riderSex = config.riderSex,
        )
    }

    fun getSummary(): CarbSummary = CarbSummary(
        cumBurnedG = cumBurnedG.toInt(),
        cumLoggedG = cumLoggedG,
        deficitG = (cumBurnedG - cumLoggedG).toInt(),
        percentageHit = if (cumBurnedG > 0f) ((cumLoggedG / cumBurnedG) * 100f).toInt() else 0,
    )

    // ─── Internals ───────────────────────────────────────────────────────────

    private fun tick() {
        val now = System.currentTimeMillis()
        // M2: classify the zone ONCE per tick. The burn estimator computes the
        // classification internally and exposes it via `BurnEstimate.zoneSnapshot`,
        // so the tracker can reuse it for `lastZoneSnapshot` (surfaced to data
        // fields) without a separate `IntensityZoneCalculator.calculate` call.
        // Pre-M2 the classifier ran twice per tick: once here, once inside
        // CarbBurnEstimator.estimate. Cost was small but the duplication was
        // genuinely wasted work.
        val burn = currentBurnEstimate()
        lastZoneSnapshot = burn.zoneSnapshot

        // Delegate the movement gate + cap + active-time gate to the pure
        // integrator helper. See [CarbIntegrator] for the contract and the
        // physiological rationale behind each gate.
        val stale = lastSpeedChangeMs > 0 && (now - lastSpeedChangeMs) > SPEED_STALE_MS
        val dtMs = if (lastTickMs == 0L) 0L else (now - lastTickMs).coerceAtLeast(0L)
        val step = CarbIntegrator.integrate(
            burnGph = burn.gph,
            dtMs = dtMs,
            speedKmh = lastSpeedKmh,
            speedStale = stale,
        )
        cumBurnedG += step.deltaG
        activeIntegrationMs += step.deltaActiveMs
        // Update lastTickMs on every tick (moving or not) so a stationary→moving
        // transition doesn't claim the entire stationary period in one big dt.
        lastTickMs = now

        // v17 coincidence resolution: when a deficit alert AND a time-grid tick
        // are both due in the same physical tick, the deficit alert wins (its
        // numeric "behind N g" is more actionable than a "X min since last"
        // reminder, and both ask for the same rider action). The time-grid tick
        // is consumed silently — without that, the rider would hear two beeps
        // in quick succession and see only the time alert (which visually
        // overlays the deficit one in the Karoo SDK's alert area). Mirrors the
        // same logic in `HydrationTracker.tick`.
        val deficitFired = evaluateDeficitAlert(now)
        if (deficitFired) {
            if (currentDueTimeTick(now) != 0L) {
                // Mark the time tick consumed: `now >= currentTickAt` (otherwise
                // currentDueTimeTick would have returned 0L), so setting
                // `lastTimeAlertFireMs = now` satisfies the "already fired this
                // tick" guard on subsequent calls. The next grid point
                // (sessionStartMs + (N+1)·interval) is unaffected because the
                // stored value will then be strictly less than it.
                lastTimeAlertFireMs = now
            }
        } else {
            evaluateTimeAlert(now)
        }
        maybePeriodicLog(now)
        // Re-publish at the end so subscribers see the updated deficit, burn rate,
        // and isIntegrating (covers staleness transitions that updateSpeed can't see
        // because no value change triggers them). 15-s cadence — the carb / hyd
        // data fields used to poll at 1 Hz, so this is a 95 % wakeup reduction.
        publishStatus()
    }

    /** Returns true when an alert was actually dispatched in this call. The caller
     *  in [tick] uses the return value to coordinate coincidence resolution with
     *  the time-alert path (deficit wins; if a time tick was due in the same
     *  tick it gets consumed silently). */
    private fun evaluateDeficitAlert(now: Long): Boolean {
        // v18.2 B9 — gate delegated to [FuelingAlertScheduler.shouldFireDeficit]
        // (pure helper) so the same shape is single-sourced with the hydration
        // tracker and unit-tested in `FuelingAlertSchedulerTest`. The deficit is
        // still computed here because the helper needs an Int and the conversion
        // is type-bound to the tracker's `cumBurnedG: Float` accumulator.
        val deficit = (cumBurnedG - cumLoggedG).toInt()
        val fire = FuelingAlertScheduler.shouldFireDeficit(
            enabled                = config.carbDeficitAlertEnabled,
            deficit                = deficit,
            deficitThreshold       = config.carbDeficitThresholdG,
            lastDeficitAlertFireMs = lastDeficitAlertFireMs,
            reminderIntervalMs     = config.carbDeficitReminderIntervalMin * 60_000L,
            initialDelayMs         = config.carbDeficitInitialDelayMin * 60_000L,
            cumLogged              = cumLoggedG,
            sessionStartMs         = sessionStartMs,
            now                    = now,
        )
        if (!fire) return false
        // Defer during an emergency: don't beep over the SOS, and DON'T stamp the cooldown,
        // so the alert re-fires on the next tick once the emergency clears (not lost).
        if (isEmergencyActive()) {
            Timber.d("Carb deficit alert due but emergency active — deferring (not fired, cooldown intact)")
            return false
        }
        fireAlert(source = "deficit", deficit = deficit, elapsedMin = elapsedMinutesSinceRealLog(now))
        lastDeficitAlertFireMs = now
        return true
    }

    /**
     * Minutes since the rider's last real log, with two defensive guards (B33):
     *  1. `lastRealLogMs <= 0L` → return 0 instead of "minutes since epoch" (28M+).
     *     Today every [start] / [resume] seeds `lastRealLogMs` to `now` so the
     *     zero sentinel is unreachable, but a future refactor that called
     *     [evaluateDeficitAlert] / [evaluateTimeAlert] before [start] would
     *     otherwise render "Eat now — 28815555 min since last" in the rider
     *     alert. Cheap to defend against.
     *  2. `coerceAtLeast(0L)` — a backwards NTP step between two ticks could
     *     make `now &lt; lastRealLogMs`. The `{elapsed}` token in the alert
     *     template would then render a negative integer. Clamp to 0.
     *
     *  Used by the DEFICIT alert path — the deficit framing is "you haven't
     *  logged in a while AND you're behind", so anchoring on the last actual
     *  log is the right semantic. The TIME alert path uses
     *  [elapsedMinutesSinceLastTimeAlert] instead because its fire schedule
     *  is an interval grid anchored on session start (see
     *  [FuelingAlertScheduler.currentDueTimeTick]), independent of whether
     *  the rider logged anything in between.
     */
    private fun elapsedMinutesSinceRealLog(now: Long): Long =
        if (lastRealLogMs <= 0L) 0L
        else (now - lastRealLogMs).coerceAtLeast(0L) / 60_000

    /**
     * Minutes since the last TIME-alert fire — or since session start before the
     * first fire. Used by [evaluateTimeAlert] so the `{elapsed}` token in the
     * time-alert template reads "min since last reminder" (matching the rider's
     * mental model of an interval-based reminder), not "min since last log"
     * (which is what [elapsedMinutesSinceRealLog] reports for the deficit path).
     *
     * Why two helpers: the time-alert fire schedule is a grid anchored on
     * [sessionStartMs] with spacing `config.carbTimeIntervalMin`; it fires
     * regardless of how recently the rider logged. Reporting "min since last
     * log" inside a time-alert message is therefore semantically off — it's
     * not the reason the alert fired.
     *
     * Anchor selection:
     *  - First fire of the session: anchor on [sessionStartMs] → for an
     *    interval=30 first tick the message reads "30 min since last".
     *  - Subsequent fires: anchor on [lastTimeAlertFireMs] → "30 min since
     *    last" measured against the prior fire.
     *
     * Same defensive guards as the real-log helper: sentinel-zero on
     * uninitialised anchor, clamp on backwards NTP step.
     */
    private fun elapsedMinutesSinceLastTimeAlert(now: Long): Long {
        val anchor = if (lastTimeAlertFireMs > 0L) lastTimeAlertFireMs else sessionStartMs
        if (anchor <= 0L) return 0L
        return (now - anchor).coerceAtLeast(0L) / 60_000
    }

    /** See [HydrationTracker.currentDueTimeTick] — same contract, carb side.
     *  Pure read; never mutates state. */
    private fun currentDueTimeTick(now: Long): Long = FuelingAlertScheduler.currentDueTimeTick(
        enabled = config.carbTimeAlertEnabled,
        intervalMs = config.carbTimeIntervalMin * 60_000L,
        sessionStartMs = sessionStartMs,
        lastTimeAlertFireMs = lastTimeAlertFireMs,
        initialDelayMs = config.carbTimeInitialDelayMin * 60_000L,
        cumLogged = cumLoggedG,
        now = now,
    )

    /** See [evaluateDeficitAlert] return-value note — same contract on the time side. */
    private fun evaluateTimeAlert(now: Long): Boolean {
        if (currentDueTimeTick(now) == 0L) return false
        // Defer during an emergency (see evaluateDeficitAlert) — not fired, tick not consumed.
        if (isEmergencyActive()) {
            Timber.d("Carb time alert due but emergency active — deferring")
            return false
        }
        val deficit = (cumBurnedG - cumLoggedG).toInt()
        // `elapsedMinutesSinceLastTimeAlert` (not `…SinceRealLog`) — the time
        // alert fires on an interval grid, not in response to the rider's
        // last log, so `{elapsed}` should read as "min since last reminder".
        fireAlert(source = "time", deficit = deficit, elapsedMin = elapsedMinutesSinceLastTimeAlert(now))
        lastTimeAlertFireMs = now
        // I8 — `lastRealLogMs` stays untouched on alert fires; it tracks the
        // rider's last actual log so `{elapsed}` reports time-since-real-log.
        return true
    }

    private fun fireAlert(source: String, deficit: Int, elapsedMin: Long) {
        // v18 L1: dispatch timestamp inlined here (was a tracker-level `lastAlertMs`
        // field that survived persistence for no reason — only the InRideAlert.id
        // below ever read it, and that read is local to this function).
        val dispatchedAtMs = System.currentTimeMillis()
        // v18: `{target}` token now binds to the rider's CURRENT instantaneous
        // burn rate (g/h) — the physiologically real "what your body is asking
        // for right now". The legacy token name is kept so existing custom alert
        // templates still substitute something useful instead of leaving a
        // literal "{target}" in the message. Riders who want a different number
        // can edit their template.
        val tokens = mapOf(
            "deficit" to deficit.toString(),
            "elapsed" to elapsedMin.toString(),
            "target"  to computeBurnRateGph().toString(),
        )
        val customDetail = if (source == "deficit") config.carbAlertCustomDetailDeficit
                           else                     config.carbAlertCustomDetailTime
        val detailTemplate = customDetail.ifBlank {
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
        val slots = listOf(
            com.enderthor.kSafe.extension.util.FuelSlot(1, config.carb1Label, config.carb1Grams),
            com.enderthor.kSafe.extension.util.FuelSlot(2, config.carb2Label, config.carb2Grams),
            com.enderthor.kSafe.extension.util.FuelSlot(3, config.carb3Label, config.carb3Grams),
        )
        // null when no slot is usable (all carb sizes 0) — the presenter then shows no LOG
        // button (a plain InRideAlert) instead of a button that would log a phantom 0 g entry.
        val slot = com.enderthor.kSafe.extension.util.pickFuelItem(if (source == "deficit") deficit else null, slots)?.slot
        onFuelingAlert(com.enderthor.kSafe.extension.util.FuelingAlertRequest(
            title = title, detail = detail,
            // Factory — only built if the presenter takes the InRideAlert branch.
            inRideAlert = {
                InRideAlert(
                    // Unique-per-fire ID: re-dispatching an InRideAlert with the same id while
                    // the host still has the previous overlay tracked has been observed to crash
                    // the Karoo ride app when the alert re-fires after the per-source cooldown.
                    // Appending the wall-clock timestamp guarantees a fresh id per fire.
                    id = "ksafe-carb-alert-$source-$dispatchedAtMs",
                    icon = R.drawable.ic_ksafe,
                    title = title,
                    detail = detail,
                    autoDismissMs = AUTO_DISMISS_MS,
                    backgroundColor = fuelingAlertColorRes(config.carbAlertBgColor),
                    textColor = ALERT_TX_COLOR,
                )
            },
            channel = com.enderthor.kSafe.extension.util.FuelingChannel.CARB, slot = slot,
        ))
        val burn = currentBurnEstimate()
        val burnRateGph = burn.gph.coerceAtMost(ABSORPTION_CAP_GPH.toDouble()).toInt()
        calibLogger?.log(CalibrationLogger.Event.FUELING_CARB_FIRED) {
            // Locale.US: the calibration CSV uses comma as field separator, so we must NOT
            // let the default Locale turn "1.15" into "1,15" on es/fr/de devices.
            // v18: replaced legacy `multiplier=` (vestigial after the integrator switched
            // to the physiological estimator) with the new load-bearing signals:
            // `confidence` (which tier ran), `cho_fraction` (Romijn/Jeukendrup table
            // value at this zone), and `kcal_h` (raw energy expenditure before the
            // CHO split). Tuning workflows now see exactly what produced the burn rate.
            String.format(
                java.util.Locale.US,
                "source=%s,deficit_g=%d,since_log_min=%d,cum_burned=%d,cum_logged=%d,burn_rate_gph=%d," +
                    "confidence=%s,kcal_h=%.0f,cho_fraction=%.2f,zone=%s/%d/%d,beep=%s",
                source, deficit, elapsedMin,
                cumBurnedG.toInt(), cumLoggedG, burnRateGph,
                burn.confidence, burn.kcalPerHour, burn.choFraction,
                lastZoneSnapshot.source, lastZoneSnapshot.index, lastZoneSnapshot.total,
                config.carbBeepPattern,
            )
        }
        Timber.d(">>> Carb alert fired ($source): deficit=${deficit}g elapsed=${elapsedMin}min")
    }

    private fun maybePeriodicLog(now: Long) {
        if (calibLogger == null || !calibLogger.isEnabled) return
        if (now - lastPeriodicLogMs < PERIODIC_LOG_INTERVAL_MS) return
        lastPeriodicLogMs = now
        val deficit = (cumBurnedG - cumLoggedG).toInt()
        val burn = currentBurnEstimate()
        val burnRateGph = burn.gph.coerceAtMost(ABSORPTION_CAP_GPH.toDouble()).toInt()
        calibLogger.log(CalibrationLogger.Event.FUELING_CARB_PERIODIC) {
            // Locale.US — see fireAlert above. v18 payload mirrors FUELING_CARB_FIRED:
            // confidence + kcal_h + cho_fraction replace the vestigial `multiplier`.
            String.format(
                java.util.Locale.US,
                "cum_burned=%d,cum_logged=%d,deficit=%d,burn_rate_gph=%d," +
                    "confidence=%s,kcal_h=%.0f,cho_fraction=%.2f,zone_source=%s,zone_idx=%d,zone_total=%d,hr=%d,power=%d",
                cumBurnedG.toInt(), cumLoggedG, deficit, burnRateGph,
                burn.confidence, burn.kcalPerHour, burn.choFraction,
                lastZoneSnapshot.source, lastZoneSnapshot.index, lastZoneSnapshot.total,
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
    val cumBurnedG: Int,
    val cumLoggedG: Int,
    val deficitG: Int,
    val deficitThresholdG: Int,
    val zoneSnapshot: ZoneSnapshot,
    val burnRateGph: Int,
    /** Session-average carb burn rate in g/h, computed over only the active
     *  integration time (not total elapsed). 0 until any integration has
     *  happened. Drives the new `CarbAvgBurnRateDataType` field. */
    val avgBurnRateGph: Int,
    /** Which tier of [CarbBurnEstimator] is currently producing the burn rate.
     *  Used by data fields to surface "Pair HR/Pwr" when [Confidence.NONE]
     *  rather than displaying a misleading 0. */
    val burnConfidence: CarbBurnEstimator.Confidence,
    /** True when the tracker is actively integrating right now (movement gate
     *  passing + tracker running). Used by [CarbBurnRateDataType] to decide
     *  whether to display the live rate or `---`, keeping all three carb fields
     *  (burn rate, burned, status) coherent: if integration is paused, every
     *  field is frozen; if it's running, every field shows a live number. */
    val isIntegrating: Boolean,
)

/** Totals captured at end-of-ride for the post-ride summary InRideAlert. */
data class CarbSummary(
    val cumBurnedG: Int,
    val cumLoggedG: Int,
    val deficitG: Int,
    val percentageHit: Int,
)
