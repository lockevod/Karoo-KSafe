package com.enderthor.kSafe.extension.managers

import android.content.Context
import com.enderthor.kSafe.R
import com.enderthor.kSafe.data.EmergencyReason
import com.enderthor.kSafe.data.EmergencyState
import com.enderthor.kSafe.data.EmergencyStatus
import com.enderthor.kSafe.data.IncidentResponseLevel
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.extension.Sender
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

class EmergencyManager(
    private val context: Context,
    private val karooSystem: KarooSystemService,
    private val configManager: ConfigurationManager,
    private val locationManager: LocationManager,
    private val sender: Sender,
    private val scope: CoroutineScope,
    private val calibLogger: CalibrationLogger? = null,
) {
    companion object {

        /** In-memory state flow — updated synchronously on every state change.
         *  DataTypes collect from this instead of DataStore to avoid write latency. */
        val uiState: StateFlow<EmergencyState> get() = _uiState
        private val _uiState = MutableStateFlow(EmergencyState())

        /** Duration of the mini-confirm shown when resuming after a missed deadline. */
        private const val MINI_CONFIRM_SECONDS = 10
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
    private var checkinJob: Job? = null
    private var checkinWarningJob: Job? = null
    var currentStatus = EmergencyStatus.IDLE
        private set
    private var currentReason: EmergencyReason? = null
    /** Timestamp when the current countdown started — used to compute how_long_ms in CRASH_NO log. */
    private var countdownStartedAt = 0L

    // ─── Public API ───────────────────────────────────────────────────────────

    fun triggerEmergency(reason: EmergencyReason, config: KSafeConfig) {
        if (currentStatus != EmergencyStatus.IDLE) {
            Timber.d("Emergency already in progress, ignoring new trigger")
            return
        }
        Timber.d("Emergency triggered: $reason")
        startCountdown(reason, config)
    }

    suspend fun cancelEmergency(config: KSafeConfig? = null) {
        if (currentStatus != EmergencyStatus.COUNTDOWN) return

        // Capture reason before clearing — needed for CRASH_NO calibration log.
        val cancelledReason = currentReason
        val howLongMs = if (countdownStartedAt > 0L) System.currentTimeMillis() - countdownStartedAt else 0L

        countdownJob?.cancel()
        currentStatus = EmergencyStatus.IDLE
        currentReason = null
        countdownStartedAt = 0L
        sosOverlay.removeOverlay()

        // If the user cancelled a detector-triggered countdown → confirmed false positive.
        // how_long_ms near 0 = immediate cancel (obvious FP); longer = hesitation.
        when (cancelledReason) {
            EmergencyReason.CRASH_DETECTED -> calibLogger?.log(CalibrationLogger.Event.CRASH_CANCELLED) {
                "how_long_ms=$howLongMs,reason=${cancelledReason.label}"
            }
            EmergencyReason.MEDICAL_FLATLINE,
            EmergencyReason.MEDICAL_COLLAPSE -> calibLogger?.log(CalibrationLogger.Event.MEDICAL_CANCELLED) {
                "how_long_ms=$howLongMs,subkind=${cancelledReason.name}"
            }
            else -> Unit
        }

        // Update UI state synchronously — DataTypes react immediately, no DataStore wait.
        _uiState.value = EmergencyState()

        // Persist to DataStore (async is fine here — UI already updated above).
        configManager.saveEmergencyState(EmergencyState())
        if (config?.checkinEnabled == true) {
            startCheckinJobs(config)
        }

        Timber.d("Emergency cancelled by user (reason=$cancelledReason, after ${howLongMs}ms)")
    }

    fun startCheckinTimer(config: KSafeConfig) {
        if (!config.checkinEnabled) return
        checkinJob?.cancel()
        checkinWarningJob?.cancel()
        val startTime = System.currentTimeMillis()
        startCheckinJobs(config, startTime)
        Timber.d("Check-in timer started: ${config.checkinIntervalMinutes}min")
    }

    /** Schedules checkin warning + expiry jobs; saves state at the start of the job. */
    private fun startCheckinJobs(config: KSafeConfig, startTime: Long = System.currentTimeMillis()) {
        checkinJob?.cancel()
        checkinWarningJob?.cancel()

        val intervalMs = config.checkinIntervalMinutes * 60_000L

        // Update UI state synchronously so TimerDataType sees the checkin state immediately.
        _uiState.value = EmergencyState(
            checkinEnabled = true,
            checkinStartTime = startTime,
            checkinIntervalMinutes = config.checkinIntervalMinutes
        )

        checkinWarningJob = scope.launch {
            val warningMs = intervalMs - (10 * 60_000L)
            if (warningMs > 0) {
                delay(warningMs)
                if (currentStatus == EmergencyStatus.IDLE) {
                    karooSystem.dispatch(TurnScreenOn)
                    karooSystem.dispatch(BEEP_LONG)
                    karooSystem.dispatch(
                        SystemNotification(
                            id = "ksafe-checkin-warn",
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
            delay(intervalMs)
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
        _uiState.value = EmergencyState()
        scope.launch { configManager.saveEmergencyState(EmergencyState()) }
    }

    fun stopAll() {
        countdownJob?.cancel()
        checkinJob?.cancel()
        checkinWarningJob?.cancel()
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
                    id = "ksafe-warning-${reason.name.lowercase()}",
                    icon = com.enderthor.kSafe.R.drawable.ic_ksafe,
                    title = renderAlertText(titleTemplate, tokens, maxLength = ALERT_TITLE_MAX_CHARS),
                    detail = renderAlertText(detailTemplate, tokens, maxLength = ALERT_DETAIL_MAX_CHARS),
                    autoDismissMs = 10_000L,
                    backgroundColor = 0xFFE65100.toInt(),
                    textColor = 0xFFFFFFFF.toInt(),
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

        // Update UI state synchronously so DataTypes react immediately (no DataStore latency).
        val countdownState = EmergencyState(
            status = EmergencyStatus.COUNTDOWN,
            reason = reason.label,
            reasonEnum = reason,                               // persisted so decideResume() can recover
            countdownStartTime = startTime,
            countdownDurationSeconds = config.countdownSeconds,
            checkinEnabled = config.checkinEnabled,
            checkinIntervalMinutes = config.checkinIntervalMinutes
        )
        _uiState.value = countdownState

        countdownJob = scope.launch {
            configManager.saveEmergencyState(countdownState)
            karooSystem.dispatch(TurnScreenOn)
            karooSystem.dispatch(BEEP_LONG)

            val totalSeconds = config.countdownSeconds

            for (remaining in totalSeconds downTo 1) {
                // Show/update the overlay every second — injected directly into the
                // Karoo ride Activity view hierarchy (ki2 approach, no special permissions).
                sosOverlay.showOrUpdate(reason, remaining) {
                    scope.launch { cancelEmergency(config) }
                }

                if (remaining % 5 == 0 || remaining <= 10) {
                    if (remaining <= 10) karooSystem.dispatch(TurnScreenOn)
                    if (remaining <= 5) karooSystem.dispatch(BEEP_URGENT)
                }
                delay(1_000L)
            }

            sendAlerts(config, reason)
        }
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
            karooSystem.dispatch(BEEP_LONG)

            val totalRemainingSeconds = (remainingMs / 1_000L).toInt().coerceAtLeast(1)
            for (remaining in totalRemainingSeconds downTo 1) {
                sosOverlay.showOrUpdate(reason, remaining) {
                    scope.launch { cancelEmergency(config) }
                }
                if (remaining % 5 == 0 || remaining <= 10) {
                    if (remaining <= 10) karooSystem.dispatch(TurnScreenOn)
                    if (remaining <= 5) karooSystem.dispatch(BEEP_URGENT)
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
            karooSystem.dispatch(BEEP_LONG)
            for (remaining in MINI_CONFIRM_SECONDS downTo 1) {
                sosOverlay.showOrUpdate(reason, remaining) {
                    scope.launch { cancelEmergency(config) }
                }
                if (remaining <= 5) karooSystem.dispatch(BEEP_URGENT)
                delay(1_000L)
            }
            sendAlerts(config, reason)
        }
    }

    // ─── Message builder ──────────────────────────────────────────────────────

    /**
     * Builds the outgoing emergency message by substituting all placeholders.
     * The livetrack link is appended automatically if a key is configured,
     * even when {livetrack} is not present in the template.
     */
    suspend fun buildMessage(config: KSafeConfig, reason: EmergencyReason): String {
        val locationLink = locationManager.getFreshLocationLink()
            ?: context.getString(R.string.location_unavailable)

        val liveTrackLink = if (config.karooLiveKey.isNotBlank())
            com.enderthor.kSafe.data.KAROO_LIVE_BASE_URL + config.karooLiveKey.trim()
        else ""

        var message = config.emergencyMessage
            .replace("{location}", locationLink)
            .replace("{reason}", reason.label)
            .replace("{livetrack}", liveTrackLink)
            .trim()

        // Always append livetrack link if key is set and it's not already in the message
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
        configManager.saveEmergencyState(alertingState)

        val message = buildMessage(config, reason)

        Timber.d("Sending emergency alert via ${config.activeProvider}")

        scope.launch {
            try {
                sender.sendAlert(message, config.activeProvider)
            } catch (e: Exception) {
                Timber.e(e, "Error sending emergency alert")
            }
        }

        karooSystem.dispatch(TurnScreenOn)
        karooSystem.dispatch(BEEP_LONG)

        delay(5_000L)
        currentStatus = EmergencyStatus.IDLE
        currentReason = null
        _uiState.value = EmergencyState()
        configManager.saveEmergencyState(EmergencyState())
    }
}
