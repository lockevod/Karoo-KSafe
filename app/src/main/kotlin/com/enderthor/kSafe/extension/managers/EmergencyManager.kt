package com.enderthor.kSafe.extension.managers

import android.content.Context
import com.enderthor.kSafe.R
import com.enderthor.kSafe.data.EmergencyReason
import com.enderthor.kSafe.data.EmergencyState
import com.enderthor.kSafe.data.EmergencyStatus
import com.enderthor.kSafe.data.IncidentResponseLevel
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.extension.Sender
import com.enderthor.kSafe.extension.util.ALERT_DETAIL_MAX_CHARS
import com.enderthor.kSafe.extension.util.ALERT_TITLE_MAX_CHARS
import com.enderthor.kSafe.extension.util.renderAlertText
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.SystemNotification
import io.hammerhead.karooext.models.TurnScreenOn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

private val BEEP_LONG = PlayBeepPattern(listOf(
    PlayBeepPattern.Tone(frequency = 880, durationMs = 800)
))
private val BEEP_URGENT = PlayBeepPattern(listOf(
    PlayBeepPattern.Tone(frequency = 880, durationMs = 200),
    PlayBeepPattern.Tone(frequency = null, durationMs = 100),
    PlayBeepPattern.Tone(frequency = 880, durationMs = 200),
    PlayBeepPattern.Tone(frequency = null, durationMs = 100),
    PlayBeepPattern.Tone(frequency = 1100, durationMs = 500),
))

/**
 * Pure substitution kernel used by every outbound message path. Extracted from
 * [EmergencyManager.substituteTokens] so it stays JVM-unit-testable — no Android
 * `Context`, no [LocationManager], no coroutines.
 *
 * Tokens not present in [template] are simply not replaced; tokens like `{foo}`
 * that this function doesn't know about are left literal on purpose (same
 * convention as [com.enderthor.kSafe.extension.util.renderAlertText]).
 *
 * The final `.trim()` cleans up the dangling space that appears when a template
 * such as `"Track me: {livetrack}"` is rendered without a configured livetrack
 * key — preserves the behaviour the old `sendRideStartNotification` had inline.
 */
internal fun substituteAlertTokens(
    template: String,
    locationLink: String,
    liveTrackLink: String,
    reasonLabel: String,
): String =
    template
        .replace("{location}", locationLink)
        .replace("{reason}", reasonLabel)
        .replace("{livetrack}", liveTrackLink)
        .trim()

class EmergencyManager(
    private val context: Context,
    private val karooSystem: KarooSystemService,
    private val configManager: ConfigurationManager,
    private val locationManager: LocationManager,
    private val sender: Sender,
    private val scope: CoroutineScope,
    private val calibLogger: CalibrationLogger? = null,
    /**
     * Optional bridge to the Karoo's physical buzzer via the private HAL service. When
     * non-null AND the rider has [KSafeConfig.buzzerOnEmergencyEnabled] turned on, the
     * emergency-class beeps (last 5 s of countdown + ALERTING entry) are duplicated through
     * this channel so the device is heard even when audio alerts are muted. Null-safe —
     * if the bind has failed or the rider has opted out, the SDK [PlayBeepPattern] path
     * continues to operate as the only audio channel.
     */
    private val buzzerClient: BuzzerClient? = null,
    /**
     * Invoked when a CRASH-triggered emergency is cancelled by the rider. Wired by
     * KSafeExtension to clear the crash cooldown — a cancelled countdown sent no
     * alert, so crash detection must be fully re-armed.
     */
    private val onCrashEmergencyCancelled: (() -> Unit)? = null,
) {
    companion object {

        /** In-memory state flow — updated synchronously on every state change.
         *  DataTypes collect from this instead of DataStore to avoid write latency. */
        val uiState: StateFlow<EmergencyState> get() = _uiState
        private val _uiState = MutableStateFlow(EmergencyState())

        /** Duration of the mini-confirm shown when resuming after a missed deadline. */
        private const val MINI_CONFIRM_SECONDS = 10

        /**
         * How long the field UI / persisted state stays in ALERTING before reverting
         * to IDLE. The sender's retry loop continues running in the background past
         * this window — the rollback only governs what the rider sees and whether
         * a follow-up incident (check-in expiry, medical event) can re-trigger
         * (`triggerEmergency` gates on `currentStatus != IDLE`). Without this the
         * field is locked in ALERTING for the full multi-cycle retry (~30 min).
         */
        private const val ALERTING_VISIBLE_MS = 5_000L
    }

    private val sosOverlay = SosOverlayManager(context)

    init {
        // Reset the static state flow on every manager construction. The flow lives in the
        // companion object because DataTypes read it through `EmergencyManager.uiState`
        // without a manager reference — but that means a service rebind without process
        // death would otherwise leave the flow holding the previous instance's state,
        // disagreeing with `currentStatus` (which is instance-scoped, starts at IDLE here).
        _uiState.value = EmergencyState()
    }

    private var countdownJob: Job? = null
    /** Outgoing sender retry job. Kept here so [cancelEmergency] / [stopAll] can
     *  abort an in-flight emergency alert. Without this, a rider who taps Cancel
     *  right after the countdown reaches 0 would still have the sender retry for
     *  up to ~30 minutes (3 cycles × 3 attempts × 60-180 s back-offs + 5/10 min
     *  inter-cycle delays) — defeating the cancel button.
     *
     *  `@Volatile` so cross-thread reads from [cancelEmergency] (which may run on
     *  any coroutine scope launched from the UI) see writes from [sendAlerts]
     *  (running on the service `scope`) without an explicit lock. */
    @Volatile private var alertJob: Job? = null
    private var checkinJob: Job? = null
    private var checkinWarningJob: Job? = null
    /** Logical start of the live check-in interval. Preserved across ride pauses so
     *  [resumeCheckinTimer] can re-arm with the REMAINING interval instead of a fresh
     *  one — Karoo autopause fires Recording→Paused→Recording at every traffic light,
     *  and re-starting from zero on each micro-pause meant CHECKIN_EXPIRED could never
     *  fire on a stop-start ride. 0L = not running. */
    private var checkinStartTimeMs: Long = 0L
    /** Wall-clock instant the check-in was paused (0L = not paused). [resumeCheckinTimer]
     *  shifts [checkinStartTimeMs] forward by the pause duration so the countdown
     *  effectively freezes during the pause rather than continuing to elapse. */
    private var checkinPausedAtMs: Long = 0L
    var currentStatus = EmergencyStatus.IDLE
        private set
    private var currentReason: EmergencyReason? = null
    /** Timestamp when the current countdown started — used to compute how_long_ms in CRASH_NO log. */
    private var countdownStartedAt = 0L

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Exposes whether an outbound alert retry job is still running after the visible
     * ALERTING window collapsed back to IDLE. Used by the SOS field tap handler to
     * route taps during the background retry to cancelEmergency() instead of arming
     * a fresh emergency. See `handleSOSTap`'s IDLE branch.
     */
    fun alertJobActive(): Boolean = alertJob?.isActive == true

    fun triggerEmergency(reason: EmergencyReason, config: KSafeConfig) {
        if (currentStatus != EmergencyStatus.IDLE) {
            Timber.d("Emergency already in progress, ignoring new trigger")
            calibLogger?.log(CalibrationLogger.Event.INCIDENT_SUPPRESSED) {
                "reason=${reason.label},level=EMERGENCY,blocked_by=$currentStatus"
            }
            return
        }
        Timber.d("Emergency triggered: $reason")
        calibLogger?.log(CalibrationLogger.Event.EMERGENCY_TRIGGERED) {
            "reason=${reason.name},countdown_s=${config.countdownSeconds}"
        }
        startCountdown(reason, config)
    }

    suspend fun cancelEmergency(config: KSafeConfig? = null) {
        // Allow cancelling from COUNTDOWN (normal case) OR from ALERTING (rider
        // realises they're fine right after the countdown hits 0 and the sender
        // has just kicked off). After [ALERTING_VISIBLE_MS] the visible state has
        // rolled to IDLE but the alertJob may still be retrying in the background
        // for up to ~30 min — in that case currentStatus is IDLE but alertJob is
        // alive, and the rider must still be able to abort. Treat a live alertJob
        // as an implicit ALERTING for cancellation purposes.
        val alertJobAlive = alertJob?.isActive == true
        if (currentStatus != EmergencyStatus.COUNTDOWN &&
            currentStatus != EmergencyStatus.ALERTING &&
            !alertJobAlive) return

        // Capture reason before clearing — needed for CRASH_NO calibration log.
        val cancelledReason = currentReason
        val howLongMs = if (countdownStartedAt > 0L) System.currentTimeMillis() - countdownStartedAt else 0L

        countdownJob?.cancel()
        // Capture-and-null before cancelling so a concurrent assignment from sendAlerts
        // can't clobber the reference and leak a running job. The sendAlerts finally
        // block runs on cancellation and resets currentStatus/uiState/DataStore back
        // to IDLE — we still set them here defensively in case the cancel races with
        // a brand-new sendAlerts launch.
        val previousAlertJob = alertJob
        alertJob = null
        previousAlertJob?.cancel()
        currentStatus = EmergencyStatus.IDLE
        currentReason = null
        countdownStartedAt = 0L
        sosOverlay.removeOverlay()

        // If the user cancelled a detector-triggered countdown → confirmed false positive.
        // how_long_ms near 0 = immediate cancel (obvious FP); longer = hesitation.
        when (cancelledReason) {
            EmergencyReason.CRASH_DETECTED -> {
                calibLogger?.log(CalibrationLogger.Event.CRASH_CANCELLED) {
                    "how_long_ms=$howLongMs,reason=${cancelledReason.label}"
                }
                onCrashEmergencyCancelled?.invoke()
            }
            EmergencyReason.MEDICAL_FLATLINE,
            EmergencyReason.MEDICAL_COLLAPSE -> calibLogger?.log(CalibrationLogger.Event.MEDICAL_CANCELLED) {
                "how_long_ms=$howLongMs,subkind=${cancelledReason.name}"
            }
            // B16 — wellness / check-in / SOS / speed-drop cancellations also produce a
            // calibration-log row so post-incident analysis can quantify false-positive
            // rates for these reasons too. Pre-B16 only CRASH/MEDICAL cancels were logged,
            // making rider reports of "wellness alert fires too often" unverifiable from
            // the CSV. `null` (cancel with no captured reason — shouldn't happen in
            // practice) skips the log row rather than emit a useless `subkind=null`.
            null -> Unit
            else -> calibLogger?.log(CalibrationLogger.Event.INCIDENT_CANCELLED) {
                "how_long_ms=$howLongMs,subkind=${cancelledReason.name}"
            }
        }

        // Update UI state synchronously — DataTypes react immediately, no DataStore wait.
        _uiState.value = EmergencyState()

        // Persist to DataStore (async is fine here — UI already updated above).
        // J2 — wrapped: a disk-full IOException from the cancel persist must NOT
        // propagate out of cancelEmergency (that would leave the in-memory state
        // IDLE but the persisted state COUNTDOWN/ALERTING, and the next process
        // boot would call resumeCountdown / resumeAfterDeadline → re-fire the
        // alert the rider just cancelled). Log and proceed — in-memory state is
        // canonical for the current session; the persisted divergence will heal
        // on the next successful saveEmergencyState.
        try {
            configManager.saveEmergencyState(EmergencyState())
        } catch (e: Exception) {
            Timber.e(e, "Failed to persist IDLE state after cancel; in-memory state already cleared")
        }
        if (config?.checkinEnabled == true) {
            startCheckinJobs(config)
        }

        Timber.d("Emergency cancelled by user (reason=$cancelledReason, after ${howLongMs}ms)")
    }

    fun startCheckinTimer(config: KSafeConfig) {
        if (!config.checkinEnabled) return
        checkinJob?.cancel()
        checkinWarningJob?.cancel()
        // startCheckinJobs stamps checkinStartTimeMs / clears checkinPausedAtMs.
        startCheckinJobs(config, System.currentTimeMillis())
        Timber.d("Check-in timer started: ${config.checkinIntervalMinutes}min")
    }

    /**
     * Suspends the check-in countdown on a ride pause WITHOUT resetting it. Cancels
     * the warning/expiry jobs but preserves [checkinStartTimeMs] and stamps the pause
     * instant, so [resumeCheckinTimer] can re-arm with the remaining interval. This
     * is what makes Karoo autopause (Recording→Paused→Recording at every light) safe:
     * the previous stopCheckinTimer-on-pause + startCheckinTimer-on-every-Recording
     * pairing re-armed a full fresh interval on each micro-pause, so CHECKIN_EXPIRED
     * could never fire on a stop-start ride — silently defeating the dead-man's-switch.
     * The countdown freezes during the pause, matching the intent that a check-in must
     * not fire while the rider is deliberately stopped.
     */
    fun pauseCheckinTimer() {
        if (checkinStartTimeMs == 0L) return            // not running — nothing to suspend
        checkinJob?.cancel()
        checkinWarningJob?.cancel()
        if (checkinPausedAtMs == 0L) checkinPausedAtMs = System.currentTimeMillis()
        // Mirror the old display behaviour: while suspended the timer field reads
        // neutral/OFF rather than a frozen countdown that would eventually look
        // "expired" during a long café stop. ONLY when no emergency countdown is on
        // screen — a crash (or check-in) COUNTDOWN active during the pause owns
        // _uiState and must stay visible (an autopause IS the crash signature).
        if (currentStatus == EmergencyStatus.IDLE) _uiState.value = EmergencyState()
        Timber.d("Check-in timer paused (preserving elapsed)")
    }

    /**
     * Re-arms the check-in countdown after a pause, preserving the time elapsed before
     * the pause. Shifts [checkinStartTimeMs] forward by the pause duration so the
     * remaining interval is what was left when the ride paused, not a fresh full one.
     * Falls back to a fresh [startCheckinTimer] if the timer was never running (e.g.
     * check-in enabled mid-ride, or a resume with no preceding pause).
     */
    fun resumeCheckinTimer(config: KSafeConfig) {
        if (!config.checkinEnabled) return
        if (checkinStartTimeMs == 0L) { startCheckinTimer(config); return }
        val now = System.currentTimeMillis()
        val pauseDuration = if (checkinPausedAtMs > 0L) (now - checkinPausedAtMs).coerceAtLeast(0L) else 0L
        checkinStartTimeMs += pauseDuration
        // startCheckinJobs re-stamps checkinStartTimeMs (to this shifted value) and clears
        // checkinPausedAtMs — pauseDuration was already captured above.
        startCheckinJobs(config, checkinStartTimeMs)
        Timber.d("Check-in timer resumed (paused ${pauseDuration}ms)")
    }

    /**
     * Schedules checkin warning + expiry jobs; saves state at the start of the job.
     * Delays are computed RELATIVE to [startTime] vs now, so a resume with a shifted
     * (earlier) start re-arms with only the remaining interval. A fresh start passes
     * `startTime == now`, giving the full interval (identical to the original behaviour).
     */
    private fun startCheckinJobs(config: KSafeConfig, startTime: Long = System.currentTimeMillis()) {
        checkinJob?.cancel()
        checkinWarningJob?.cancel()
        // Single source of truth for the resume math: EVERY arming path (startCheckinTimer,
        // resumeCheckinTimer, and cancelEmergency's post-cancel re-arm) funnels through here,
        // so stamp the live logical start and clear the pause marker HERE rather than relying
        // on each caller. cancelEmergency previously re-armed via this function without
        // updating the fields, leaving a stale checkinStartTimeMs that made the next autopause
        // resume compute elapsed ≈ ∞ → an instant spurious CHECKIN_EXPIRED.
        checkinStartTimeMs = startTime
        checkinPausedAtMs = 0L

        // J4 — defense-in-depth clamp. The Settings UI clamps on commit but a
        // corrupted DataStore (file edited externally, backup with bad value,
        // migration bug) could surface 0 here. `delay(0)` fires CHECKIN_EXPIRED
        // immediately → false SOS to contacts seconds after the ride starts.
        // 10 min matches the UI minimum.
        val safeIntervalMinutes = config.checkinIntervalMinutes.coerceAtLeast(10)
        val intervalMs = safeIntervalMinutes * 60_000L

        // Elapsed since the (possibly shifted) logical start. Clamp to [0, intervalMs]
        // so a backward wall-clock jump (NTP correction) can't produce a delay longer
        // than the full interval, and a resume past the deadline fires promptly.
        val elapsed = (System.currentTimeMillis() - startTime).coerceIn(0L, intervalMs)
        val expiryDelay = intervalMs - elapsed
        val warningDelay = (intervalMs - 10 * 60_000L) - elapsed

        // Update UI state synchronously so TimerDataType sees the checkin state immediately.
        _uiState.value = EmergencyState(
            checkinEnabled = true,
            checkinStartTime = startTime,
            checkinIntervalMinutes = config.checkinIntervalMinutes
        )

        checkinWarningJob = scope.launch {
            if (warningDelay > 0) {
                delay(warningDelay)
                if (currentStatus == EmergencyStatus.IDLE) {
                    karooSystem.dispatch(TurnScreenOn)
                    karooSystem.dispatch(BEEP_LONG)
                    karooSystem.dispatch(
                        SystemNotification(
                            // Unique-per-fire suffix: a rider who restarts the check-in
                            // countdown twice in quick succession (e.g. test mode) would
                            // otherwise re-dispatch the same id and risk crashing the
                            // host's notification tracker.
                            id = "ksafe-checkin-warn-${System.currentTimeMillis()}",
                            message = "Check-in in 10 min",
                            header = context.getString(R.string.app_name),
                        )
                    )
                }
            }
        }

        checkinJob = scope.launch {
            configManager.saveEmergencyState(
                EmergencyState(
                    checkinEnabled = true,
                    checkinStartTime = startTime,
                    checkinIntervalMinutes = config.checkinIntervalMinutes
                )
            )
            delay(expiryDelay)
            if (currentStatus == EmergencyStatus.IDLE) {
                Timber.d("Check-in timer expired!")
                triggerEmergency(EmergencyReason.CHECKIN_EXPIRED, config)
            }
        }
    }

    fun resetCheckinTimer(config: KSafeConfig) {
        checkinJob?.cancel()
        checkinWarningJob?.cancel()
        if (!config.checkinEnabled) return
        Timber.d("Check-in timer reset by user")
        startCheckinTimer(config)
    }

    fun stopCheckinTimer() {
        checkinJob?.cancel()
        checkinWarningJob?.cancel()
        checkinStartTimeMs = 0L
        checkinPausedAtMs = 0L
        _uiState.value = EmergencyState()
        scope.launch { configManager.saveEmergencyState(EmergencyState()) }
    }

    fun stopAll() {
        countdownJob?.cancel()
        // Capture-and-null before cancel — see cancelEmergency for rationale.
        val previousAlertJob = alertJob
        alertJob = null
        previousAlertJob?.cancel()
        checkinJob?.cancel()
        checkinWarningJob?.cancel()
        checkinStartTimeMs = 0L
        checkinPausedAtMs = 0L
        currentStatus = EmergencyStatus.IDLE
        currentReason = null
        countdownStartedAt = 0L
        sosOverlay.removeOverlay()
        _uiState.value = EmergencyState()
        scope.launch { configManager.saveEmergencyState(EmergencyState()) }
    }

    /**
     * Cancels an active check-in emergency countdown when the ride is paused.
     * The user intentionally paused the ride (coffee stop, etc.) — a check-in
     * countdown running in the background should not fire during a pause.
     * Crash-related countdowns are NOT cancelled here (crash detection stays active while paused).
     */
    fun cancelCheckinEmergencyOnPause() {
        if (currentStatus == EmergencyStatus.COUNTDOWN && currentReason == EmergencyReason.CHECKIN_EXPIRED) {
            countdownJob?.cancel()
            // The check-in already expired and its countdown is being cancelled by the
            // pause. Clear the timer fields so the next Recording resume re-arms a FRESH
            // full interval (the resumeCheckinTimer → startCheckinTimer fallback) instead
            // of immediately re-firing CHECKIN_EXPIRED — the elapsed-since-original-start
            // would otherwise still exceed the interval and expiryDelay would be 0.
            checkinStartTimeMs = 0L
            checkinPausedAtMs = 0L
            currentStatus = EmergencyStatus.IDLE
            currentReason = null
            countdownStartedAt = 0L
            sosOverlay.removeOverlay()
            _uiState.value = EmergencyState()
            scope.launch { configManager.saveEmergencyState(EmergencyState()) }
            Timber.d("Check-in emergency cancelled on ride pause")
        }
    }

    // ─── Incident dispatch (medical / wellness detectors) ────────────────────

    /**
     * Generic dispatcher for incidents emitted by [MedicalEpisodeDetector] and [WellnessMonitor].
     *
     * Behaviour by [level]:
     *  - [IncidentResponseLevel.SILENT]    → log only.
     *  - [IncidentResponseLevel.WARNING]   → on-screen [InRideAlert] + beep, no countdown.
     *  - [IncidentResponseLevel.EMERGENCY] → delegates to [triggerEmergency], full countdown + alert.
     *
     * Drops the call (logs to Timber) if a previous emergency is already in progress —
     * we don't want a wellness alert interrupting an active crash countdown, and the
     * existing emergency's flow is already self-logged.
     */
    fun handleIncident(
        reason: EmergencyReason,
        level: IncidentResponseLevel,
        config: KSafeConfig,
        tokens: Map<String, String> = emptyMap(),
    ) {
        if (currentStatus != EmergencyStatus.IDLE) {
            Timber.d("Incident $reason ignored — emergency already in progress (status=$currentStatus)")
            // Record the drop in the calibration trail so post-incident audit can see
            // that a medical / wellness / SOS event coincided with another emergency.
            // Without this row, the exported CSV shows only the winning emergency and
            // the co-occurring detector vanishes from history.
            calibLogger?.log(CalibrationLogger.Event.INCIDENT_SUPPRESSED) {
                "reason=${reason.label},level=$level,blocked_by=$currentStatus"
            }
            return
        }
        when (level) {
            IncidentResponseLevel.SILENT -> {
                calibLogger?.log(CalibrationLogger.Event.INCIDENT_SILENT) { "reason=${reason.label}" }
                Timber.d("Silent incident: $reason")
            }
            IncidentResponseLevel.WARNING -> {
                val titleTemplate = customTitleFor(reason, config).ifBlank { defaultTitleFor(reason) }
                val detailTemplate = customDetailFor(reason, config).ifBlank { defaultDetailFor(reason) }
                // Rider-configurable beep — applies to all WARNING-level alerts (wellness tiers
                // and any medical incident downgraded to WARNING). Emergency-level alerts use
                // the hardcoded urgent BEEP_LONG + BEEP_URGENT sequence further down.
                config.wellnessBeepPattern.toPlayBeepPattern()?.let { karooSystem.dispatch(it) }
                karooSystem.dispatch(InRideAlert(
                    // Unique-per-fire id: re-dispatching the same id while the host
                    // still tracks the previous overlay can crash the Karoo ride app.
                    // WARNING-level incidents have no per-reason cooldown so two
                    // back-to-back fires of the same reason are reachable.
                    id = "ksafe-warning-${reason.name.lowercase()}-${System.currentTimeMillis()}",
                    icon = com.enderthor.kSafe.R.drawable.ic_ksafe,
                    title = renderAlertText(titleTemplate, tokens, maxLength = ALERT_TITLE_MAX_CHARS),
                    detail = renderAlertText(detailTemplate, tokens, maxLength = ALERT_DETAIL_MAX_CHARS),
                    autoDismissMs = 10_000L,
                    // SDK contract — see colors.xml: backgroundColor/textColor are @ColorRes,
                    // not @ColorInt. Passing 0xFFE65100 here crashed the ride app with
                    // Resources$NotFoundException.
                    backgroundColor = com.enderthor.kSafe.R.color.alert_orange,
                    textColor = com.enderthor.kSafe.R.color.alert_text_white,
                ))
                calibLogger?.log(CalibrationLogger.Event.INCIDENT_WARNING) {
                    "reason=${reason.label},beep=${config.wellnessBeepPattern}"
                }
                Timber.d("Warning incident dispatched: $reason")
            }
            IncidentResponseLevel.EMERGENCY -> {
                triggerEmergency(reason, config)
            }
        }
    }

    private fun customTitleFor(reason: EmergencyReason, c: KSafeConfig): String = when (reason) {
        EmergencyReason.MEDICAL_FLATLINE,
        EmergencyReason.MEDICAL_COLLAPSE      -> c.medicalCustomTitle
        EmergencyReason.WELLNESS_HIGH_HR      -> c.wellnessSustainedCustomTitle
        EmergencyReason.WELLNESS_CRITICAL_HR  -> c.wellnessCriticalCustomTitle
        EmergencyReason.WELLNESS_DECOUPLING   -> c.wellnessDecouplingCustomTitle
        else -> ""
    }

    private fun customDetailFor(reason: EmergencyReason, c: KSafeConfig): String = when (reason) {
        EmergencyReason.MEDICAL_FLATLINE,
        EmergencyReason.MEDICAL_COLLAPSE      -> c.medicalCustomDetail
        EmergencyReason.WELLNESS_HIGH_HR      -> c.wellnessSustainedCustomDetail
        EmergencyReason.WELLNESS_CRITICAL_HR  -> c.wellnessCriticalCustomDetail
        EmergencyReason.WELLNESS_DECOUPLING   -> c.wellnessDecouplingCustomDetail
        else -> ""
    }

    private fun defaultTitleFor(reason: EmergencyReason): String = when (reason) {
        EmergencyReason.WELLNESS_HIGH_HR     -> context.getString(R.string.warning_wellness_high_hr_title)
        EmergencyReason.WELLNESS_CRITICAL_HR -> context.getString(R.string.warning_wellness_critical_hr_title)
        EmergencyReason.WELLNESS_DECOUPLING  -> context.getString(R.string.warning_wellness_decoupling_title)
        EmergencyReason.MEDICAL_FLATLINE,
        EmergencyReason.MEDICAL_COLLAPSE     -> context.getString(R.string.warning_medical_title)
        else -> context.getString(R.string.app_name)
    }

    private fun defaultDetailFor(reason: EmergencyReason): String = when (reason) {
        EmergencyReason.WELLNESS_HIGH_HR     -> context.getString(R.string.warning_wellness_high_hr_detail)
        EmergencyReason.WELLNESS_CRITICAL_HR -> context.getString(R.string.warning_wellness_critical_hr_detail)
        EmergencyReason.WELLNESS_DECOUPLING  -> context.getString(R.string.warning_wellness_decoupling_detail)
        EmergencyReason.MEDICAL_FLATLINE,
        EmergencyReason.MEDICAL_COLLAPSE     -> context.getString(R.string.warning_medical_detail)
        else -> reason.label
    }

    // ─── Countdown ────────────────────────────────────────────────────────────

    private fun startCountdown(reason: EmergencyReason, config: KSafeConfig) {
        currentStatus = EmergencyStatus.COUNTDOWN
        currentReason = reason
        val startTime = System.currentTimeMillis()
        countdownStartedAt = startTime

        // Defense-in-depth clamp computed ONCE — the Settings UI clamps to [5, 120] on
        // commit, but a corrupted DataStore (file edited externally, restore from an old
        // backup with a bad value, future migration bug) could surface 0/negative here.
        // The SAME clamped value must drive both the persisted state and the countdown
        // loop below: persisting the raw `config.countdownSeconds` (e.g. 0) made
        // `countdownDeadlineMs() = startTime + 0` already-in-the-past, so a process-kill
        // recovery routed to discard/after-deadline and dropped a countdown the rider
        // could still see ticking locally. Lower bound 5 matches the UI minimum.
        val totalSeconds = config.countdownSeconds.coerceIn(5, 300)

        // Update UI state synchronously so DataTypes react immediately (no DataStore latency).
        val countdownState = EmergencyState(
            status = EmergencyStatus.COUNTDOWN,
            reason = reason.label,
            reasonEnum = reason,                               // persisted so decideResume() can recover
            countdownStartTime = startTime,
            countdownDurationSeconds = totalSeconds,
            checkinEnabled = config.checkinEnabled,
            checkinIntervalMinutes = config.checkinIntervalMinutes
        )
        _uiState.value = countdownState

        countdownJob = scope.launch {
            // H1 — saveEmergencyState wrapped: a disk-full / DataStore IOException
            // must NOT kill the entire countdown coroutine. In-memory state
            // (_uiState, currentStatus, currentReason) is the canonical source for
            // detection/UX; the persisted copy is only for cross-process recovery,
            // so a write failure should degrade gracefully rather than silently
            // dropping the beeps, overlay, and outbound alert.
            try {
                configManager.saveEmergencyState(countdownState)
            } catch (e: Exception) {
                Timber.e(e, "Failed to persist COUNTDOWN state; continuing with in-memory state only")
            }
            karooSystem.dispatch(TurnScreenOn)
            // B19 — initial countdown beep also routes through playEmergencyBeep so
            // the muted-Karoo HAL bypass fires for the most common scenario: a
            // FRESH crash/medical countdown on a muted Karoo. Pre-B19 this raw
            // dispatch was silent until the ≤5 s tick window — riders who muted
            // their Karoo got no "emergency starting" cue despite enabling
            // buzzerOnEmergencyEnabled, defeating the whole point of the bypass.
            // The resume/resumeAfterDeadline paths were migrated in B13;
            // this completes the symmetry on the fresh-countdown path.
            playEmergencyBeep(config, BEEP_LONG, BuzzerClient.COUNTDOWN_START)

            // `totalSeconds` is the clamped value hoisted above (shared with the
            // persisted countdownDurationSeconds). A `for (n in 0 downTo 1)` empty range
            // — which the raw unclamped 0 would produce — would skip the entire
            // overlay/beep/cancel-window loop and fire sendAlerts with no abort window.
            for (remaining in totalSeconds downTo 1) {
                // Show/update the overlay every second — injected directly into the
                // Karoo ride Activity view hierarchy (ki2 approach, no special permissions).
                sosOverlay.showOrUpdate(reason, remaining) {
                    scope.launch { cancelEmergency(config) }
                }

                if (remaining % 5 == 0 || remaining <= 10) {
                    if (remaining <= 10) karooSystem.dispatch(TurnScreenOn)
                    if (remaining <= 5) {
                        // Single channel per tick — playEmergencyBeep picks HAL when the
                        // rider opted into bypass, SDK otherwise. COUNTDOWN_TICK is a short
                        // ~200 ms tone so successive ticks don't step on each other.
                        playEmergencyBeep(config, BEEP_URGENT, BuzzerClient.COUNTDOWN_TICK)
                    }
                }
                delay(1_000L)
            }

            sendAlerts(config, reason)
        }
    }

    /**
     * Play an emergency-class beep, with automatic SDK fallback if the HAL bypass fails.
     * The Karoo's buzzer is a single piezo — letting both channels fire produces a chaotic
     * overlap — so the happy path is exactly ONE channel:
     *
     *  - [KSafeConfig.buzzerOnEmergencyEnabled] **on** (default) AND the HAL bind is live →
     *    fire the HAL bypass [halPattern]. Mute-immune. Predictable timing.
     *  - **off**, or HAL bind unavailable → fire the SDK [sdkPattern] via
     *    [KarooSystemService.dispatch]. Respects the rider's mute toggle (so if the rider
     *    muted the Karoo deliberately, no sound — same as pre-buzzer KSafe behaviour).
     *
     * Plus the OTA safety net: if Hammerhead later gates the HAL service so [BuzzerClient.beep]
     * starts returning false ([BuzzerClient.BeepResult.GATED_BY_SECURITY],
     * [BuzzerClient.BeepResult.TRANSACT_THREW], etc.), this method automatically falls back
     * to the SDK pattern so the rider still hears SOMETHING — pre-bypass behaviour. The
     * worst case in practice is a brief overlap of the failed HAL attempt (silent because
     * gated) with the SDK pattern: acceptable trade for the fallback guarantee.
     */
    private fun playEmergencyBeep(
        config: KSafeConfig,
        sdkPattern: PlayBeepPattern,
        halPattern: List<BuzzerClient.Tone>,
    ) {
        val client = buzzerClient
        // ensureReady() (not isReady()) so a binding that died over the device's
        // multi-day uptime gets re-established — otherwise the bypass stayed dead
        // until process restart. The rebind is async; this beep may still fall
        // back to SDK, but later beeps in the countdown use the recovered binder.
        val tryBypass = config.buzzerOnEmergencyEnabled && client != null && client.ensureReady()
        if (tryBypass && client!!.beep(halPattern)) {
            return                            // HAL bypass dispatched successfully
        }
        // Fallback: rider opted out, bind not ready, OR transact failed (likely an OTA
        // gating the bypass). Use SDK PlayBeepPattern — subject to the rider's mute toggle
        // exactly like pre-buzzer KSafe, so at minimum a non-muted Karoo still beeps.
        karooSystem.dispatch(sdkPattern)
    }

    /**
     * Re-attach to an in-progress countdown that survived a process kill. Called from
     * [KSafeExtension.initializeSystem] when a persisted [EmergencyState] indicates the
     * countdown was running and its deadline has not yet passed.
     *
     * The remaining time is computed from the persisted [EmergencyState.countdownDeadlineMs];
     * a new countdownJob is launched with that remaining duration so the rider sees the same
     * cancel UI as if the process had never been killed.
     */
    fun resumeCountdown(state: EmergencyState, config: KSafeConfig) {
        val reason = state.reasonEnum ?: return    // legacy state without enum — can't safely resume
        val deadline = state.countdownDeadlineMs()
        val now = System.currentTimeMillis()
        val remainingMs = (deadline - now).coerceAtLeast(0L)
        if (remainingMs == 0L) {
            // shouldn't reach here — initializeSystem branches to resumeAfterDeadline instead
            return
        }

        currentStatus = EmergencyStatus.COUNTDOWN
        currentReason = reason
        countdownStartedAt = now - (state.countdownDurationSeconds * 1_000L - remainingMs)
        _uiState.value = state.copy()

        countdownJob?.cancel()
        countdownJob = scope.launch {
            // No re-save to DataStore on resume — the existing persisted state is the source of truth.
            karooSystem.dispatch(TurnScreenOn)
            // B13 — route through [playEmergencyBeep] so the muted-Karoo HAL
            // bypass fires when the rider opted in. Resume is the most safety-
            // critical scenario for the bypass: Android killed the extension
            // mid-countdown, the rider may not even know the process died.
            // Raw `karooSystem.dispatch(BEEP_LONG)` here was silent on muted
            // Karoos despite `buzzerOnEmergencyEnabled=true` — defeating the
            // whole reason the bypass exists.
            playEmergencyBeep(config, BEEP_LONG, BuzzerClient.COUNTDOWN_START)

            // H4 — clamp before .toInt() so a corrupted persisted deadline (e.g.
            // milliseconds accidentally stored where seconds were expected by a
            // future migration) cannot overflow Int via the cast and produce a
            // tiny / negative loop count that collapses the rider's cancel window
            // to a single tick. Upper bound 24 h is far above any legitimate
            // countdownSeconds (max ~120 s) — anything larger is a corrupted save.
            val totalRemainingSeconds = (remainingMs / 1_000L)
                .coerceIn(1L, 24 * 60 * 60L)
                .toInt()
            for (remaining in totalRemainingSeconds downTo 1) {
                sosOverlay.showOrUpdate(reason, remaining) {
                    scope.launch { cancelEmergency(config) }
                }
                if (remaining % 5 == 0 || remaining <= 10) {
                    if (remaining <= 10) karooSystem.dispatch(TurnScreenOn)
                    // B13 — same rationale: ≤5 s ticks are the last-chance audio
                    // cue for the rider to cancel. Mirrors the [startCountdown]
                    // line ~512 path which already uses playEmergencyBeep.
                    if (remaining <= 5) playEmergencyBeep(config, BEEP_URGENT, BuzzerClient.COUNTDOWN_TICK)
                }
                delay(1_000L)
            }
            sendAlerts(config, reason)
        }
    }

    /**
     * The persisted countdown deadline has already passed (process was dead longer than the
     * remaining countdown). Rather than firing the alert silently — which is harsh, since
     * "process killed" is usually Android low-memory, not "rider in peril" — show a 10s
     * SystemAlertWindow mini-confirm with cancel / send buttons. Default on timeout: send.
     *
     * This deliberately differs from resumeCountdown so a normal post-impact rider who has
     * already moved on with their life is not greeted by an alert dispatched against contacts
     * 30 minutes later for a crash they cancelled mid-flight.
     */
    fun resumeAfterDeadline(reason: EmergencyReason, config: KSafeConfig) {
        currentStatus = EmergencyStatus.COUNTDOWN
        currentReason = reason
        val startTime = System.currentTimeMillis()
        countdownStartedAt = startTime
        _uiState.value = EmergencyState(
            status = EmergencyStatus.COUNTDOWN,
            reason = reason.label,
            reasonEnum = reason,
            countdownStartTime = startTime,
            countdownDurationSeconds = MINI_CONFIRM_SECONDS,
        )
        countdownJob?.cancel()
        countdownJob = scope.launch {
            karooSystem.dispatch(TurnScreenOn)
            // B13 — see resumeCountdown for the same rationale: the mini-confirm
            // appears after a process kill, so the bypass is more important here
            // than during a fresh countdown.
            playEmergencyBeep(config, BEEP_LONG, BuzzerClient.COUNTDOWN_START)
            for (remaining in MINI_CONFIRM_SECONDS downTo 1) {
                sosOverlay.showOrUpdate(reason, remaining) {
                    scope.launch { cancelEmergency(config) }
                }
                if (remaining <= 5) playEmergencyBeep(config, BEEP_URGENT, BuzzerClient.COUNTDOWN_TICK)
                delay(1_000L)
            }
            sendAlerts(config, reason)
        }
    }

    // ─── Message builder ──────────────────────────────────────────────────────

    /**
     * Substitutes the common alert tokens (`{location}`, `{livetrack}`, `{reason}`) in
     * [template] using a fresh GPS fix and the current config. Used by every outbound
     * non-test message path (emergency, ride start/end, custom message) so the rider
     * sees the same placeholder semantics everywhere.
     *
     *  - `{location}` ← fresh GPS link (5 s timeout) or the `location_unavailable`
     *    string if no fix is available — identical to the emergency contract.
     *  - `{livetrack}` ← Karoo Live URL when [KSafeConfig.karooLiveKey] is set, else
     *    empty string (which is then trimmed away by [substituteAlertTokens]).
     *  - `{reason}` ← [EmergencyReason.label] when [reason] is non-null, else empty
     *    string. Non-emergency callers pass `null`; unknown tokens like `{foo}` are
     *    left literal.
     *
     * Length capping is intentionally NOT done here — [renderAlertText] callers own
     * the InRideAlert title/detail limits ([ALERT_TITLE_MAX_CHARS] / [ALERT_DETAIL_MAX_CHARS]).
     */
    suspend fun substituteTokens(
        template: String,
        config: KSafeConfig,
        reason: EmergencyReason? = null,
    ): String {
        val locationLink = locationManager.getFreshLocationLink()
            ?: context.getString(R.string.location_unavailable)
        val liveTrackLink = if (config.karooLiveKey.isNotBlank())
            com.enderthor.kSafe.data.KAROO_LIVE_BASE_URL + config.karooLiveKey.trim()
        else ""
        return substituteAlertTokens(template, locationLink, liveTrackLink, reason?.label ?: "")
    }

    /**
     * Builds the outgoing emergency message by substituting all placeholders.
     * The livetrack link is appended automatically if a key is configured,
     * even when {livetrack} is not present in the template — this auto-append
     * behaviour is emergency-only on purpose (the rider's contacts must always
     * be able to follow them live during an alert).
     */
    suspend fun buildMessage(config: KSafeConfig, reason: EmergencyReason): String {
        var message = substituteTokens(config.emergencyMessage, config, reason)

        // Emergency-only: always append livetrack link if a key is set and it's not
        // already in the message. Non-emergency callers (custom, ride start/end) get
        // the strict token-replacement semantics from substituteTokens above.
        val liveTrackLink = if (config.karooLiveKey.isNotBlank())
            com.enderthor.kSafe.data.KAROO_LIVE_BASE_URL + config.karooLiveKey.trim()
        else ""
        if (liveTrackLink.isNotBlank() && !message.contains(liveTrackLink)) {
            message = "$message $liveTrackLink"
        }

        return message
    }

    private suspend fun sendAlerts(config: KSafeConfig, reason: EmergencyReason) {
        currentStatus = EmergencyStatus.ALERTING
        sosOverlay.removeOverlay()
        val alertingState = EmergencyState(status = EmergencyStatus.ALERTING, reason = reason.label)
        _uiState.value = alertingState
        // H1 — see startCountdown for rationale: disk-full IOException from
        // saveEmergencyState must NOT abort the entire sendAlerts coroutine before
        // the alertJob is launched. The persisted state is recovery metadata; the
        // outbound alert is the safety-critical work and must always be attempted.
        try {
            configManager.saveEmergencyState(alertingState)
        } catch (e: Exception) {
            Timber.e(e, "Failed to persist ALERTING state; continuing with in-memory state only")
        }

        val message = buildMessage(config, reason)

        Timber.d("Sending emergency alert via ${config.activeProvider}")

        // Capture the local Job reference for identity-guarded cleanup in finally.
        // Without this, when sendAlerts is called twice in a row (emergency 1 still
        // retrying when emergency 2 fires), emergency 1's finally would run later
        // and find currentStatus==ALERTING (set by emergency 2), clobbering emergency
        // 2's state AND nulling out the alertJob field that now points at job 2 —
        // orphaning the live retry job so cancelEmergency can no longer abort it.
        lateinit var myJob: kotlinx.coroutines.Job
        myJob = scope.launch {
            try {
                val outcome = sender.sendAlert(message, config.activeProvider)
                if (!outcome.anyOk) {
                    // H7 — ALWAYS log the delivery failure to the calibration trail.
                    // Even when this emergency has been superseded by a newer one (so
                    // the rider-facing notification is suppressed to avoid wrong-
                    // attribution UI), post-incident audit must still be able to see
                    // that the original emergency was never delivered. Tag with a
                    // `superseded` marker so analysers can distinguish the two paths.
                    val supersededByNewer = alertJob !== myJob
                    calibLogger?.log(CalibrationLogger.Event.ALERT_DELIVERY_FAILED) {
                        "provider=${config.activeProvider},reason=${reason.label},superseded=$supersededByNewer"
                    }
                    // G6 — only fire the rider-facing failure notification when WE
                    // are still the registered alertJob. A previous emergency that
                    // finished its ~30-min retry loop LONG after a newer emergency
                    // took over alertJob would otherwise dispatch a beep + red
                    // InRideAlert labelled with the OLD emergency's reason —
                    // interrupting or overlaying the newer emergency's UI with the
                    // wrong attribution.
                    if (!supersededByNewer) {
                        // Outbound delivery FAILED across all retry cycles (no
                        // coverage, blank/expired credentials, provider down).
                        // Without an explicit rider-facing signal the on-device
                        // sequence looks identical to a successful delivery
                        // (5 s ALERTING then SAFE), so a rider whose crash alert
                        // never reached contacts would have no way to know.
                        notifyDeliveryFailure(config, reason)
                    } else {
                        Timber.d("Delivery failure for $reason notification suppressed — alertJob superseded by newer emergency")
                    }
                } else if (outcome.partial) {
                    // Reached ≥1 but not every emergency contact (e.g. 1 of 3 — a contact in
                    // a coverage gap or with an expired key). The SOS DID get out, so this is
                    // NOT the red delivery-FAILED path; surface a softer amber "reached X of
                    // Y" notice so the rider knows some contacts may not have been alerted.
                    // ALWAYS log to the calibration trail (even when superseded) — symmetric
                    // with the ALERT_DELIVERY_FAILED branch — so post-incident audit can see
                    // that some contacts were missed. Only paint UI when WE are still the
                    // registered alertJob (same identity guard as the failure path) so a
                    // superseded emergency can't attribute the notice to the wrong reason.
                    calibLogger?.log(CalibrationLogger.Event.ALERT_DELIVERY_PARTIAL) {
                        "provider=${config.activeProvider},reason=${reason.label},reached=${outcome.delivered},total=${outcome.eligible},superseded=${alertJob !== myJob}"
                    }
                    Timber.w("Emergency partial delivery: reached ${outcome.delivered}/${outcome.eligible} contacts via ${config.activeProvider} for ${reason.label}")
                    if (alertJob === myJob) {
                        notifyPartialDelivery(config, reason, outcome.delivered, outcome.eligible)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelled via cancelEmergency / stopAll — propagate so the
                // structured concurrency contract is honoured.
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Error sending emergency alert")
            } finally {
                // Identity guard — only act on shared state if WE are still the
                // registered alertJob. A newer sendAlerts call overwrites alertJob
                // before our finally runs; that newer emergency owns the state.
                if (alertJob === myJob) {
                    // Secondary rollback — only fires if the timed rollback below
                    // didn't run first (e.g. sender returned before
                    // ALERTING_VISIBLE_MS). Same guard as the timed rollback: don't
                    // clobber state if cancelEmergency / stopAll already moved us
                    // out of ALERTING.
                    if (currentStatus == EmergencyStatus.ALERTING) {
                        currentStatus = EmergencyStatus.IDLE
                        currentReason = null
                        _uiState.value = EmergencyState()
                        // Wrapped like every other persist site (H1/J2): a disk-full
                        // IOException here must NOT skip `alertJob = null` below — that
                        // would leak a stale reference to this already-completed job.
                        try {
                            configManager.saveEmergencyState(EmergencyState())
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to persist IDLE after alert job finished; in-memory state already cleared")
                        }
                    }
                    alertJob = null
                }
            }
        }
        alertJob = myJob

        karooSystem.dispatch(TurnScreenOn)
        // Single channel — playEmergencyBeep picks HAL bypass (rising-urgency) when the
        // rider opted in, SDK BEEP_LONG otherwise. Never both, so the buzzer doesn't
        // serialise two patterns into a muddled overlap.
        playEmergencyBeep(config, BEEP_LONG, BuzzerClient.EMERGENCY_PATTERN)

        // Roll back the visible ALERTING state after a short fixed window so the
        // rider's field UI doesn't stay locked for the full ~30-min sender retry
        // cycle and a follow-up incident (check-in expiry, medical event) can
        // re-trigger triggerEmergency. The alertJob continues retrying in the
        // background — the finally above is a secondary catch-all for the path
        // where the sender wraps up before this delay completes.
        delay(ALERTING_VISIBLE_MS)
        // Identity guard (same as the alertJob `finally`): roll back ONLY if we are still
        // the registered alert. If this emergency's sender returned fast, its `finally`
        // already set IDLE while THIS outer delay was still pending; a follow-up emergency
        // can then arm and reach ALERTING within the window. Without `alertJob === myJob`
        // this stale rollback would stomp the NEWER emergency's ALERTING state to IDLE.
        if (alertJob === myJob && currentStatus == EmergencyStatus.ALERTING) {
            currentStatus = EmergencyStatus.IDLE
            // G3 — DO NOT null currentReason here. The alertJob may still be retrying
            // in the background; if the rider later cancels during that window,
            // cancelEmergency captures `cancelledReason = currentReason` and the
            // `when (cancelledReason)` switch routes to onCrashEmergencyCancelled
            // (which clears the crash cooldown). Nulling currentReason on rollback
            // would silently disable that path — the crash cooldown would stay armed
            // for ~countdown+30 s, suppressing a real follow-up crash. The alertJob's
            // finally clears currentReason when the job actually completes.
            _uiState.value = EmergencyState()
            // Wrapped like every other persist site (H1/J2): in-memory state is already
            // IDLE above, so a disk-full IOException here must not propagate out of the
            // sendAlerts coroutine — the persisted copy heals on the next successful write.
            try {
                configManager.saveEmergencyState(EmergencyState())
            } catch (e: Exception) {
                Timber.e(e, "Failed to persist IDLE after timed ALERTING rollback; in-memory state already cleared")
            }
        }
    }

    /**
     * Fires when [Sender.sendAlert] returns an outcome that reached nobody (`!anyOk`) after
     * exhausting every retry cycle.
     * Without this the rider has no on-device way to distinguish "alert delivered to
     * contacts" from "alert silently dropped on every attempt" — the visible field
     * sequence (5 s ALERTING then SAFE) is identical for both cases. The notification
     * combines a distinct beep with a 20-s persistent InRideAlert so a rider in a
     * tunnel / coverage gap learns immediately that they cannot rely on the alert.
     */
    private fun notifyDeliveryFailure(config: KSafeConfig, reason: EmergencyReason) {
        val provider = config.activeProvider
        // Distinct descending beep pattern — audibly different from EMERGENCY_PATTERN
        // (rising, "alert fired") so a rider can tell "delivery failed" from
        // "alert sent" without looking at the screen.
        //
        // B13 — route through [playEmergencyBeep] so the muted-Karoo HAL bypass
        // engages here too. Delivery failure is exactly the case where the rider
        // CANNOT rely on the alert getting out; if their Karoo is muted, the
        // raw SDK dispatch was silent and the rider would never learn until
        // they checked the screen. The HAL pattern [BuzzerClient.
        // DELIVERY_FAILED_PATTERN] mirrors the SDK descending shape so the
        // audible identity holds whether or not the bypass dispatched.
        playEmergencyBeep(
            config = config,
            sdkPattern = PlayBeepPattern(listOf(
                PlayBeepPattern.Tone(frequency = 600, durationMs = 300),
                PlayBeepPattern.Tone(frequency = null, durationMs = 150),
                PlayBeepPattern.Tone(frequency = 500, durationMs = 300),
                PlayBeepPattern.Tone(frequency = null, durationMs = 150),
                PlayBeepPattern.Tone(frequency = 400, durationMs = 600),
            )),
            halPattern = BuzzerClient.DELIVERY_FAILED_PATTERN,
        )
        // Unique-per-fire suffix on both ids (InRideAlert AND SystemNotification):
        // the sender's retry loop can call notifyDeliveryFailure multiple times for
        // the same provider+reason across its ~30 min retry window. Re-dispatching
        // the same id has been observed to crash the Karoo ride app's overlay
        // tracker.
        val failureDispatchedAtMs = System.currentTimeMillis()
        karooSystem.dispatch(InRideAlert(
            id = "ksafe-alert-delivery-failed-${reason.name.lowercase()}-$failureDispatchedAtMs",
            icon = com.enderthor.kSafe.R.drawable.ic_ksafe,
            title = context.getString(R.string.alert_delivery_failed_title),
            detail = context.getString(R.string.alert_delivery_failed_detail, provider.name),
            autoDismissMs = 20_000L,
            backgroundColor = com.enderthor.kSafe.R.color.alert_red,
            textColor = com.enderthor.kSafe.R.color.alert_text_white,
        ))
        // SystemNotification fallback — InRideAlert only renders inside the Karoo
        // ride app. The sender's retry loop runs up to ~30 min, so the failure
        // notification can fire LONG after the rider has ended the ride and the
        // device is on the launcher / Settings / off. Without the system-tray
        // fallback, a rider in a tunnel whose ride ends before the sender gives
        // up would just hear an unfamiliar beep with no on-screen explanation.
        karooSystem.dispatch(SystemNotification(
            id = "ksafe-alert-delivery-failed-sys-${reason.name.lowercase()}-$failureDispatchedAtMs",
            message = context.getString(R.string.alert_delivery_failed_detail, provider.name),
            header = context.getString(R.string.alert_delivery_failed_title),
        ))
    }

    /**
     * Fires when [Sender.sendAlert] reached at least one but not every emergency contact
     * (e.g. 1 of 3 — a contact in a coverage gap or with an expired key). Distinct from
     * [notifyDeliveryFailure]: the SOS DID get out, so this is an amber "heads-up", not the
     * red total-failure alarm. A two-tone "partial" beep plus a 15-s InRideAlert (and a
     * system-notification fallback for after the ride) tells the rider that some contacts
     * may not have been alerted, without implying the alert failed outright.
     */
    private fun notifyPartialDelivery(
        config: KSafeConfig,
        reason: EmergencyReason,
        reached: Int,
        total: Int,
    ) {
        val provider = config.activeProvider
        // Two equal mid-tone bursts — deliberately neither the rising EMERGENCY_PATTERN
        // ("alert fired") nor the descending DELIVERY_FAILED_PATTERN ("alert FAILED"), so a
        // muted-Karoo rider hears partial delivery as its own identity. Routed through
        // playEmergencyBeep so the HAL bypass engages on a muted device (a partial delivery
        // is still safety-relevant). The HAL pattern mirrors this SDK shape.
        playEmergencyBeep(
            config = config,
            sdkPattern = PlayBeepPattern(listOf(
                PlayBeepPattern.Tone(frequency = 700, durationMs = 250),
                PlayBeepPattern.Tone(frequency = null, durationMs = 150),
                PlayBeepPattern.Tone(frequency = 700, durationMs = 250),
            )),
            halPattern = BuzzerClient.PARTIAL_DELIVERY_PATTERN,
        )
        // Unique-per-fire suffix on both ids — same rationale as notifyDeliveryFailure
        // (re-dispatching a duplicate id has crashed the ride app's overlay tracker).
        val dispatchedAtMs = System.currentTimeMillis()
        karooSystem.dispatch(InRideAlert(
            id = "ksafe-alert-delivery-partial-${reason.name.lowercase()}-$dispatchedAtMs",
            icon = com.enderthor.kSafe.R.drawable.ic_ksafe,
            title = context.getString(R.string.alert_delivery_partial_title),
            detail = context.getString(R.string.alert_delivery_partial_detail, reached, total, provider.name),
            autoDismissMs = 15_000L,
            backgroundColor = com.enderthor.kSafe.R.color.alert_orange,
            textColor = com.enderthor.kSafe.R.color.alert_text_white,
        ))
        karooSystem.dispatch(SystemNotification(
            id = "ksafe-alert-delivery-partial-sys-${reason.name.lowercase()}-$dispatchedAtMs",
            message = context.getString(R.string.alert_delivery_partial_detail, reached, total, provider.name),
            header = context.getString(R.string.alert_delivery_partial_title),
        ))
    }
}
