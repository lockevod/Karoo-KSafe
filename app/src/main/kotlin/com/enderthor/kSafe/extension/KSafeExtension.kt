package com.enderthor.kSafe.extension

import com.enderthor.kSafe.BuildConfig
import com.enderthor.kSafe.data.EmergencyReason
import com.enderthor.kSafe.data.EmergencyStatus
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.data.ProviderType
import com.enderthor.kSafe.datatype.SafetyTimerDataType
import com.enderthor.kSafe.datatype.SOSDataType
import com.enderthor.kSafe.extension.managers.ConfigurationManager
import com.enderthor.kSafe.extension.managers.CrashDetectionManager
import com.enderthor.kSafe.extension.managers.EmergencyManager
import com.enderthor.kSafe.extension.managers.LocationManager
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.RideState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.coroutines.CoroutineContext

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
        emergencyManager = EmergencyManager(
            applicationContext, karooSystem, configManager, locationManager, sender, this
        )
        crashManager = CrashDetectionManager(applicationContext, this) {
            Timber.d("Crash detected by sensor!")
            if (activeConfig.isActive) {
                emergencyManager.triggerEmergency(EmergencyReason.CRASH_DETECTED, activeConfig)
            }
        }

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
                // Keep crash detection active while paused (rider may have crashed)
                emergencyManager.stopCheckinTimer()
                // If a check-in countdown was running when the user paused, cancel it —
                // they intentionally stopped (coffee break) and should not get an alert.
                emergencyManager.cancelCheckinEmergencyOnPause()
                rideWasActive = true
            }
            is RideState.Idle -> {
                val wasActive = rideWasActive
                emergencyManager.stopAll()
                applyIdleMonitoring(activeConfig)
                // Reset per-ride flags
                rideStartNotificationSent = false
                rideWasActive = false
                // Send ride-end notification if there was an active ride
                if (wasActive) {
                    sendRideEndNotification()
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

    /** Sends a ride-end notification if the feature is enabled. */
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
     * Called from SettingsScreen to test the full emergency flow without a real ride.
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

    override fun onDestroy() {
        crashManager.stop()
        locationManager.stop()
        emergencyManager.stopAll()
        karooSystem.disconnect()
        job.cancel()
        instance = null
        super.onDestroy()
    }
}
