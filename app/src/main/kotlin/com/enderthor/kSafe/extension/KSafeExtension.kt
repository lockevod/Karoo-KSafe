package com.enderthor.kSafe.extension

import com.enderthor.kSafe.BuildConfig
import com.enderthor.kSafe.data.EmergencyReason
import com.enderthor.kSafe.data.EmergencyStatus
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.data.ProviderType
import com.enderthor.kSafe.datatype.CustomMessageDataType
import com.enderthor.kSafe.datatype.CustomMessageState
import com.enderthor.kSafe.datatype.SafetyTimerDataType
import com.enderthor.kSafe.datatype.SOSDataType
import com.enderthor.kSafe.extension.managers.CalibrationLogger
import com.enderthor.kSafe.extension.managers.ConfigurationManager
import com.enderthor.kSafe.extension.managers.CrashDetectionManager
import com.enderthor.kSafe.extension.managers.EmergencyManager
import com.enderthor.kSafe.extension.managers.LocationManager
import com.enderthor.kSafe.extension.managers.LogReporter
import com.enderthor.kSafe.extension.managers.WebhookManager
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.SystemNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.coroutines.CoroutineContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class KSafeExtension : KarooExtension("ksafe", BuildConfig.VERSION_NAME), CoroutineScope {

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    lateinit var karooSystem: KarooSystemService
    private lateinit var configManager: ConfigurationManager
    private lateinit var locationManager: LocationManager
    private lateinit var crashManager: CrashDetectionManager
    private lateinit var emergencyManager: EmergencyManager
    private lateinit var sender: Sender
    private lateinit var calibLogger: CalibrationLogger
    private lateinit var webhookManager: WebhookManager

    private var activeConfig = KSafeConfig()
    private var currentRideState: RideState? = null
    /** True once the ride-start notification has been sent for the current recording session. */
    private var rideStartNotificationSent = false
    /** True if there was an active ride (Recording or Paused) — used to detect ride end. */
    private var rideWasActive = false

    companion object {
        private var instance: KSafeExtension? = null
        fun getInstance(): KSafeExtension? = instance
        internal fun setInstance(ext: KSafeExtension) { instance = ext }
    }

    override val types by lazy {
        listOf(
            SOSDataType("sos-field", applicationContext, karooSystem),
            SafetyTimerDataType("timer-field", applicationContext, karooSystem),
            CustomMessageDataType("custom-message-field", applicationContext, karooSystem, slot = 1),
            CustomMessageDataType("custom-message-field-2", applicationContext, karooSystem, slot = 2),
            CustomMessageDataType("custom-message-field-3", applicationContext, karooSystem, slot = 3),
        )
    }

    override fun onCreate() {
        super.onCreate()
        setInstance(this)
        Timber.d("KSafeExtension created")

        karooSystem = KarooSystemService(applicationContext)
        configManager = ConfigurationManager(applicationContext)
        locationManager = LocationManager(karooSystem, this)
        sender = Sender(karooSystem, configManager)
        calibLogger = CalibrationLogger(applicationContext, this)
        webhookManager = WebhookManager(karooSystem)
        emergencyManager = EmergencyManager(
            applicationContext, karooSystem, configManager, locationManager, sender, this,
            calibLogger
        )
        crashManager = CrashDetectionManager(applicationContext, this, {
            Timber.d("Crash detected by sensor!")
            if (activeConfig.isActive) {
                emergencyManager.triggerEmergency(EmergencyReason.CRASH_DETECTED, activeConfig)
            }
        }, calibLogger)

        karooSystem.connect { connected ->
            if (connected) {
                Timber.d("Connected to Karoo system")
                locationManager.start()
                initializeSystem()
            } else {
                Timber.w("Disconnected from Karoo system")
            }
        }
    }

    private fun initializeSystem() {
        launch {
            // Observe config changes
            configManager.loadConfigFlow().collect { config ->
                activeConfig = config
                crashManager.updateConfig(config)
                // Toggle calibration logging based on config
                if (config.calibrationLoggingEnabled && !calibLogger.isEnabled) {
                    calibLogger.enable()
                } else if (!config.calibrationLoggingEnabled && calibLogger.isEnabled) {
                    // disable() flushes the remaining buffer to disk before returning
                    calibLogger.disable()
                    // Read the full CSV (all flushed chunks) and send via Telegram (fire-and-forget)
                    val logContent = calibLogger.getFileContent()
                    launch {
                        LogReporter.sendLogFile(logContent, karooSystem = karooSystem)
                    }
                }
                // Re-evaluate crash monitoring if idle (ride start/pause is handled by handleRideState)
                if (currentRideState is RideState.Idle) {
                    applyIdleMonitoring(config)
                }
                Timber.d("Config updated: active=${config.isActive}, crash=${config.crashDetectionEnabled}, outsideRide=${config.crashMonitorOutsideRide}, anySpeed=${config.crashMonitorOutsideRideAnySpeed}")
            }
        }

        launch {
            // Observe ride state
            karooSystem.streamRide()
                .map { it }
                .distinctUntilChanged()
                .collect { state ->
                    handleRideState(state)
                }
        }

        launch {
            // Stream speed to crash detector
            karooSystem.streamDataFlow(io.hammerhead.karooext.models.DataType.Type.SPEED)
                .collect { streamState ->
                    val speedKmh = streamState.speedKmh() ?: return@collect
                    crashManager.updateSpeed(speedKmh)
                }
        }
    }

    private fun handleRideState(state: RideState) {
        currentRideState = state
        Timber.d("Ride state: $state")
        when (state) {
            is RideState.Recording -> {
                if (activeConfig.isActive) {
                    crashManager.start(activeConfig)
                    emergencyManager.startCheckinTimer(activeConfig)
                    // Only send the start notification on the very first Recording event.
                    // Resuming from Pause also triggers Recording — we skip it there.
                    if (!rideStartNotificationSent) {
                        rideStartNotificationSent = true
                        sendRideStartNotification()
                    }
                    rideWasActive = true
                }
            }
            is RideState.Paused -> {
                // Keep crash detection active while paused (rider may have crashed).
                // BUT reset the speed-drop accumulator — while stopped at a café the speed
                // is 0, which would otherwise trigger speed-drop detection after N minutes
                // even though the rider intentionally paused.
                crashManager.resetSpeedDropOnPause()
                // Stop the check-in timer and cancel any active check-in countdown.
                emergencyManager.stopCheckinTimer()
                emergencyManager.cancelCheckinEmergencyOnPause()
                rideWasActive = true
            }
            is RideState.Idle -> {
                val wasActive = rideWasActive
                val wasLogging = calibLogger.isEnabled
                emergencyManager.stopAll()
                applyIdleMonitoring(activeConfig)
                // Reset per-ride flags
                rideStartNotificationSent = false
                rideWasActive = false
                // Send ride-end notification if there was an active ride
                if (wasActive) {
                    sendRideEndNotification()
                }
                // Auto-send calibration log if it was active during the ride
                // (only if the user hasn't already turned off logging — that path sends its own copy)
                if (wasActive && wasLogging && calibLogger.isEnabled) {
                    // Read the full file (all previously flushed chunks + current buffer)
                    val logContent = calibLogger.getFileContent()
                    launch {
                        LogReporter.sendLogFile(logContent, karooSystem = karooSystem)
                    }
                }
            }
        }
    }

    /**
     * Starts or stops crash monitoring when the ride is not active (Idle state),
     * based on the two "monitor outside ride" options in config.
     */
    private fun applyIdleMonitoring(config: KSafeConfig) {
        if (config.isActive && config.crashDetectionEnabled) {
            when {
                config.crashMonitorOutsideRideAnySpeed -> {
                    // Override speed threshold to 0 — detect at any speed (more false positives)
                    crashManager.start(config.copy(minSpeedForCrashKmh = 0))
                    Timber.d("Idle crash monitoring STARTED (any speed)")
                }
                config.crashMonitorOutsideRide -> {
                    crashManager.start(config)
                    Timber.d("Idle crash monitoring STARTED (min speed=${config.minSpeedForCrashKmh} km/h)")
                }
                else -> {
                    crashManager.stop()
                }
            }
        } else {
            crashManager.stop()
        }
    }

    /**
     * Called when the user presses the hardware button assigned to the "cancel-emergency"
     * BonusAction in Karoo controller settings. Works regardless of which data fields are visible.
     */
    override fun onBonusAction(actionId: String) {
        when (actionId) {
            "cancel-emergency" -> {
                Timber.d("BonusAction: cancel-emergency triggered")
                launch { emergencyManager.cancelEmergency(activeConfig) }
            }
            "send-custom-message" -> {
                Timber.d("BonusAction: send-custom-message triggered")
                launch { sendCustomMessage() }
            }
            "trigger-webhook-1" -> {
                Timber.d("BonusAction: trigger-webhook-1 triggered")
                handleWebhookTap(1)
            }
            "trigger-webhook-2" -> {
                Timber.d("BonusAction: trigger-webhook-2 triggered")
                handleWebhookTap(2)
            }
        }
    }

    /** Direct cancel — called from CancelEmergencyActivity. */
    fun cancelEmergency() {
        launch { emergencyManager.cancelEmergency(activeConfig) }
    }

    /** Sends a ride-start notification with the Karoo Live link if the feature is enabled and a key is set. */
    private fun sendRideStartNotification() {
        val config = activeConfig
        if (!config.karooLiveEnabled) return
        if (config.karooLiveKey.isBlank()) {
            Timber.d("Karoo Live: feature enabled but no key configured, skipping ride start notification")
            return
        }
        val liveLink = com.enderthor.kSafe.data.KAROO_LIVE_BASE_URL + config.karooLiveKey.trim()
        val message = config.karooLiveStartMessage.replace("{livetrack}", liveLink)

        Timber.d("Karoo Live: sending ride start notification")
        launch {
            try {
                sender.sendInfo(message, config.activeProvider)
            } catch (e: Exception) {
                Timber.e(e, "Karoo Live: error sending ride start notification")
            }
        }
    }

    /** Sends the ride-end notification if the feature is enabled. */
    private fun sendRideEndNotification() {
        val config = activeConfig
        if (!config.karooLiveEndEnabled) return
        val message = config.karooLiveEndMessage
        if (message.isBlank()) return

        Timber.d("KSafe: sending ride end notification")
        launch {
            try {
                sender.sendInfo(message, config.activeProvider)
            } catch (e: Exception) {
                Timber.e(e, "KSafe: error sending ride end notification")
            }
        }
    }

    /**
     * Sends the custom message for the given [slot] (1, 2 or 3) immediately (no countdown).
     * Triggered by data field tap, BonusAction, or "Send" button in Settings.
     * Updates CustomMessageState for that slot so the data field reflects the result.
     * Returns a human-readable result string for display in the UI.
     */
    suspend fun sendCustomMessage(slot: Int = 1): String {
        val config = activeConfig
        val enabled = when (slot) {
            2 -> config.customMessage2Enabled
            3 -> config.customMessage3Enabled
            else -> config.customMessageEnabled
        }
        val message = when (slot) {
            2 -> config.customMessage2
            3 -> config.customMessage3
            else -> config.customMessage
        }
        if (!enabled) {
            CustomMessageState.update(slot, CustomMessageState.ERROR)
            launch { kotlinx.coroutines.delay(3_000L); CustomMessageState.update(slot, CustomMessageState.IDLE) }
            return "Custom message $slot is disabled — enable it in Settings first."
        }
        if (message.isBlank()) {
            CustomMessageState.update(slot, CustomMessageState.ERROR)
            launch { kotlinx.coroutines.delay(3_000L); CustomMessageState.update(slot, CustomMessageState.IDLE) }
            return "No text configured for message $slot."
        }
        Timber.d("Sending custom message slot=$slot via ${config.activeProvider}")
        CustomMessageState.update(slot, CustomMessageState.SENDING)
        val ok = sender.sendInfo(message, config.activeProvider)
        return if (ok) {
            CustomMessageState.update(slot, CustomMessageState.SENT)
            launch { kotlinx.coroutines.delay(4_000L); CustomMessageState.update(slot, CustomMessageState.IDLE) }
            "Custom message sent! ✓"
        } else {
            CustomMessageState.update(slot, CustomMessageState.ERROR)
            "Send failed — check your provider configuration."
        }
    }

    /** Called from CustomMessageActionCallback / BonusAction (slot 1 only). */
    fun handleCustomMessageTap() {
        launch {
            val result = sendCustomMessage(1)
            Timber.d("handleCustomMessageTap result: $result")
        }
    }

    /**
     * Sends the complete CSV calibration log to the developer via Telegram.
     * Called from the Settings UI "Send now" button for manual trigger.
     * The file is sent regardless of whether logging is currently active.
     */
    suspend fun sendCalibrationLog(): String {
        val logContent = calibLogger.getFileContent()
        if (logContent.isBlank()) return "No calibration data on disk yet."
        Timber.d("Sending calibration log manually via Telegram")
        val ok = LogReporter.sendLogFile(logContent, karooSystem = karooSystem)
        return if (ok) "Calibration log sent via Telegram ✓"
               else "Send failed — check Telegram credentials or connection."
    }

    /** Returns a string with file location info for display in the Settings UI. */
    fun getCalibrationLogInfo(): String {
        val count = calibLogger.getEntryCount()
        val file = calibLogger.getLogFile()
        return if (file != null) "$count entries | ${file.path}"
               else "$count entries (not yet flushed to disk)"
    }

    fun clearCalibrationLog() {
        calibLogger.clear()
    }

    /** Called from SettingsScreen to test the full emergency flow without a real ride.
     * Returns a message to display in the UI.
     */
    suspend fun simulateCrash(): String {
        if (!activeConfig.isActive) return "Extension is disabled — enable it in Settings first."
        Timber.d("Simulated crash test: sending alert directly (no countdown)")
        val config = activeConfig
        val message = emergencyManager.buildMessage(config, EmergencyReason.CRASH_DETECTED)
        val ok = sender.sendAlert(message, config.activeProvider)
        return if (ok) "Test alert sent successfully! Check your device."
               else "Send failed — check your provider configuration."
    }

    /**
     * Called from ProviderScreen to verify messaging provider is correctly configured.
     * Returns a human-readable result string (success or specific error).
     * Works regardless of ride state — this is a configuration test.
     */
    suspend fun sendTestMessage(provider: ProviderType): String {
        Timber.d("Sending test message via $provider")
        return sender.testSend(provider)
    }

    /**
     * Called from SettingsScreen to test the ride-start notification.
     * Returns a result message to display in the UI.
     * Works regardless of ride state — this is a configuration test.
     */
    suspend fun sendTestRideStart(): String {
        val config = activeConfig
        if (!config.karooLiveEnabled) return "Karoo Live is disabled — enable it in Settings first."
        if (config.karooLiveKey.isBlank()) return "No Karoo Live key configured."
        val liveLink = com.enderthor.kSafe.data.KAROO_LIVE_BASE_URL + config.karooLiveKey.trim()
        val message = config.karooLiveStartMessage.replace("{livetrack}", liveLink)
        Timber.d("Sending test ride start notification via ${config.activeProvider}")
        val ok = sender.sendInfo(message, config.activeProvider)
        return if (ok) "Ride start message sent successfully! Check your device."
               else "Send failed — check your provider configuration."
    }

    /**
     * Called from SettingsScreen to test the ride-end notification.
     * Returns a result message to display in the UI.
     */
    suspend fun sendTestRideEnd(): String {
        val config = activeConfig
        if (!config.karooLiveEndEnabled) return "Ride end notification is disabled — enable it in Settings first."
        if (config.karooLiveEndMessage.isBlank()) return "No ride end message configured."
        Timber.d("Sending test ride end notification via ${config.activeProvider}")
        val ok = sender.sendInfo(config.karooLiveEndMessage, config.activeProvider)
        return if (ok) "Ride end message sent successfully! Check your device."
               else "Send failed — check your provider configuration."
    }

    // ─── Actions called from DataType callbacks ───────────────────────────────

    /**
     * Fire-and-forget webhook trigger. Shows a SystemNotification with the result.
     * Called from BonusAction or directly from the settings UI test path.
     * When geo-fence is enabled for the slot, the request is blocked if the device
     * is further than the configured radius from the target coordinates.
     */
    fun handleWebhookTap(slot: Int) {
        launch {
            val config = activeConfig
            val label = if (slot == 1) config.webhook1Label.ifBlank { "Action 1" }
                        else config.webhook2Label.ifBlank { "Action 2" }

            // ── Geo-fence check ───────────────────────────────────────────────
            val geoEnabled = if (slot == 1) config.webhook1GeoEnabled else config.webhook2GeoEnabled
            if (geoEnabled) {
                val targetLat = if (slot == 1) config.webhook1GeoLat else config.webhook2GeoLat
                val targetLon = if (slot == 1) config.webhook1GeoLon else config.webhook2GeoLon
                val radiusM   = if (slot == 1) config.webhook1GeoRadiusM else config.webhook2GeoRadiusM
                val curLat = locationManager.lastLat
                val curLon = locationManager.lastLng
                if (curLat == 0.0 && curLon == 0.0) {
                    karooSystem.dispatch(SystemNotification(
                        id = "ksafe-webhook-$slot-geo-nofix",
                        header = "KSafe",
                        message = "$label blocked — no GPS fix yet"
                    ))
                    return@launch
                }
                if (targetLat == 0.0 && targetLon == 0.0) {
                    karooSystem.dispatch(SystemNotification(
                        id = "ksafe-webhook-$slot-geo-nocfg",
                        header = "KSafe",
                        message = "$label blocked — no target location configured"
                    ))
                    return@launch
                }
                val distance = distanceMeters(curLat, curLon, targetLat, targetLon)
                if (distance > radiusM) {
                    karooSystem.dispatch(SystemNotification(
                        id = "ksafe-webhook-$slot-geo-far",
                        header = "KSafe",
                        message = "$label blocked — ${distance.toInt()}m away (max ${radiusM}m)"
                    ))
                    Timber.d("Webhook $slot geo-fenced: ${distance.toInt()}m > ${radiusM}m")
                    return@launch
                }
                Timber.d("Webhook $slot geo-fence passed: ${distance.toInt()}m ≤ ${radiusM}m")
            }

            val result = webhookManager.trigger(slot, config)
            karooSystem.dispatch(
                SystemNotification(
                    id = "ksafe-webhook-$slot-${if (result.success) "ok" else "err"}",
                    header = "KSafe",
                    message = if (result.success) "$label sent ✓" else result.message,
                )
            )
        }
    }

    /**
     * Test a webhook from the Settings UI.
     * Returns a human-readable result string.
     */
    suspend fun testWebhook(slot: Int): String {
        val config = activeConfig
        val enabled = if (slot == 1) config.webhook1Enabled else config.webhook2Enabled
        val url = if (slot == 1) config.webhook1Url else config.webhook2Url
        if (!enabled) return "Webhook $slot is disabled — enable it first."
        if (url.isBlank()) return "No URL configured."
        val result = webhookManager.trigger(slot, config)
        return if (result.success) result.message else "Failed: ${result.message}"
    }

    fun handleSOSTap() {
        if (!activeConfig.isActive) return
        // Use in-memory currentStatus — reading DataStore here can race with
        // the async COUNTDOWN save inside countdownJob, causing cancels to be
        // misidentified as new triggers.
        launch {
            when (emergencyManager.currentStatus) {
                EmergencyStatus.IDLE ->
                    emergencyManager.triggerEmergency(EmergencyReason.MANUAL_SOS, activeConfig)
                EmergencyStatus.COUNTDOWN ->
                    emergencyManager.cancelEmergency(activeConfig)
                EmergencyStatus.ALERTING -> { /* ignore tap while alerting */ }
            }
        }
    }

    fun handleCheckinTap() {
        if (!activeConfig.isActive) return
        launch {
            when (emergencyManager.currentStatus) {
                EmergencyStatus.COUNTDOWN -> {
                    Timber.d("Emergency cancelled via Timer field tap")
                    emergencyManager.cancelEmergency(activeConfig)
                }
                EmergencyStatus.ALERTING -> { /* ignore tap while alerting */ }
                EmergencyStatus.IDLE -> {
                    if (activeConfig.checkinEnabled) {
                        emergencyManager.resetCheckinTimer(activeConfig)
                        Timber.d("Check-in performed by user tap")
                    }
                }
            }
        }
    }

    /**
     * Returns the last known GPS position as (latitude, longitude).
     * Returns (0.0, 0.0) if no fix has been stored yet.
     * Used by the Settings UI to pre-fill the geo-fence target coordinates.
     */
    fun getCurrentLocation(): Pair<Double, Double> =
        Pair(locationManager.lastLat, locationManager.lastLng)

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Haversine distance between two GPS coordinates, in metres.
     */
    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    override fun onDestroy() {
        crashManager.stop()
        locationManager.stop()
        emergencyManager.stopAll()
        calibLogger.disable()
        karooSystem.disconnect()
        job.cancel()
        instance = null
        super.onDestroy()
    }
}
