package com.enderthor.kSafe.extension.managers

import android.content.Context
import com.enderthor.kSafe.R
import com.enderthor.kSafe.data.EmergencyReason
import com.enderthor.kSafe.data.EmergencyState
import com.enderthor.kSafe.data.EmergencyStatus
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.extension.Sender
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.PlayBeepPattern
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
    private val scope: CoroutineScope
) {
    companion object {
        /** In-memory state flow — updated synchronously on every state change.
         *  DataTypes collect from this instead of DataStore to avoid write latency. */
        val uiState: StateFlow<EmergencyState> get() = _uiState
        private val _uiState = MutableStateFlow(EmergencyState())
    }

    private var countdownJob: Job? = null
    private var checkinJob: Job? = null
    private var checkinWarningJob: Job? = null
    var currentStatus = EmergencyStatus.IDLE
        private set

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
        countdownJob?.cancel()
        currentStatus = EmergencyStatus.IDLE

        // Update UI state synchronously — DataTypes react immediately, no DataStore wait.
        _uiState.value = EmergencyState()

        // Persist to DataStore (async is fine here — UI already updated above).
        if (config?.checkinEnabled == true) {
            configManager.saveEmergencyState(EmergencyState())
            startCheckinJobs(config)
        } else {
            configManager.saveEmergencyState(EmergencyState())
        }

        karooSystem.dispatch(
            InRideAlert(
                id = "ksafe-cancel",
                icon = R.drawable.ic_ksafe,
                title = context.getString(R.string.app_name),
                detail = "Cancelled",
                autoDismissMs = 3000L,
                backgroundColor = R.color.alert_green,
                textColor = R.color.alert_text_white
            )
        )
        Timber.d("Emergency cancelled by user")
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
                        InRideAlert(
                            id = "ksafe-checkin-warn",
                            icon = R.drawable.ic_ksafe,
                            title = context.getString(R.string.app_name),
                            detail = "Check-in in 10 min",
                            autoDismissMs = 8000L,
                            backgroundColor = R.color.alert_yellow,
                            textColor = R.color.alert_text_white
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
        _uiState.value = EmergencyState()
        scope.launch { configManager.saveEmergencyState(EmergencyState()) }
    }

    // ─── Countdown ────────────────────────────────────────────────────────────

    private fun startCountdown(reason: EmergencyReason, config: KSafeConfig) {
        currentStatus = EmergencyStatus.COUNTDOWN
        val startTime = System.currentTimeMillis()

        // Update UI state synchronously so DataTypes react immediately (no DataStore latency).
        val countdownState = EmergencyState(
            status = EmergencyStatus.COUNTDOWN,
            reason = reason.label,
            countdownStartTime = startTime,
            countdownDurationSeconds = config.countdownSeconds,
            checkinEnabled = config.checkinEnabled,
            checkinIntervalMinutes = config.checkinIntervalMinutes
        )
        _uiState.value = countdownState

        // State save is inside countdownJob so that cancelling the job before the
        // save runs avoids a race where a stale COUNTDOWN overwrites a fresh IDLE state.
        countdownJob = scope.launch {
            configManager.saveEmergencyState(countdownState)
            karooSystem.dispatch(TurnScreenOn)
            karooSystem.dispatch(BEEP_LONG)

            val totalSeconds = config.countdownSeconds

            for (remaining in totalSeconds downTo 1) {
                if (remaining % 5 == 0 || remaining <= 10) {
                    // TurnScreenOn only at start (already dispatched above) and last 10s
                    // InRideAlert itself wakes the screen — avoid redundant wake calls mid-countdown
                    if (remaining <= 10) karooSystem.dispatch(TurnScreenOn)
                    karooSystem.dispatch(
                        InRideAlert(
                            id = "ksafe-sos",
                            icon = R.drawable.ic_ksafe,
                            title = "SOS: ${remaining}s — ${reason.label}",
                            detail = "Field / button to cancel",
                            autoDismissMs = 6_000L,
                            backgroundColor = R.color.alert_red,
                            textColor = R.color.alert_text_white
                        )
                    )
                    if (remaining <= 5) karooSystem.dispatch(BEEP_URGENT)
                }
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
    fun buildMessage(config: KSafeConfig, reason: EmergencyReason): String {
        val locationLink = locationManager.getLocationLink()
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
        karooSystem.dispatch(
            InRideAlert(
                id = "ksafe-sent",
                icon = R.drawable.ic_ksafe,
                title = context.getString(R.string.app_name),
                detail = "Alert sent!",
                autoDismissMs = 15_000L,
                backgroundColor = R.color.alert_red,
                textColor = R.color.alert_text_white
            )
        )

        delay(5_000L)
        currentStatus = EmergencyStatus.IDLE
        _uiState.value = EmergencyState()
        configManager.saveEmergencyState(EmergencyState())
    }
}
