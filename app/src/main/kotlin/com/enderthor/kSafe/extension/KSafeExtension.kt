package com.enderthor.kSafe.extension

import com.enderthor.kSafe.BuildConfig
import com.enderthor.kSafe.data.EmergencyContact
import com.enderthor.kSafe.data.EmergencyReason
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
import kotlinx.coroutines.flow.first
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
                Timber.d("Config updated: active=${config.isActive}, crash=${config.crashDetectionEnabled}")
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
        Timber.d("Ride state: $state")
        when (state) {
            is RideState.Recording -> {
                if (activeConfig.isActive) {
                    crashManager.start(activeConfig)
                    emergencyManager.startCheckinTimer(activeConfig)
                    sendRideStartNotification()
                }
            }
            is RideState.Paused -> {
                // Keep crash detection active while paused (rider may have crashed)
                emergencyManager.stopCheckinTimer()
            }
            is RideState.Idle -> {
                crashManager.stop()
                emergencyManager.stopAll()
            }
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
            config.contacts.filter { it.isValid }.forEach { contact ->
                try {
                    if (contact.hasPhone || sender.isAccountBased(config.activeProvider))
                        sender.sendToPhone(contact.phone, message, config.activeProvider)
                } catch (e: Exception) {
                    Timber.e(e, "Karoo Live: error sending ride start to ${contact.name}")
                }
            }
        }
    }

    /** Called from SettingsScreen to test the full emergency flow without a real ride. */
    fun simulateCrash() {
        if (!activeConfig.isActive) return
        Timber.d("Simulated crash triggered from app")
        emergencyManager.triggerEmergency(EmergencyReason.CRASH_DETECTED, activeConfig)
    }

    /** Called from ProviderScreen to verify messaging provider is correctly configured. */
    fun sendTestMessage(contact: EmergencyContact, provider: ProviderType) {
        launch {
            val message = "KSafe test message — your emergency alerts are configured correctly."
            Timber.d("Sending test message to ${contact.name} via $provider")
            if (contact.hasPhone || sender.isAccountBased(provider))
                sender.sendToPhone(contact.phone, message, provider)
        }
    }

    // ─── Actions called from DataType callbacks ───────────────────────────────

    fun handleSOSTap() {
        if (!activeConfig.isActive) return
        launch {
            val state = configManager.loadEmergencyStateFlow().first()
            when (state.status) {
                com.enderthor.kSafe.data.EmergencyStatus.IDLE ->
                    emergencyManager.triggerEmergency(EmergencyReason.MANUAL_SOS, activeConfig)
                com.enderthor.kSafe.data.EmergencyStatus.COUNTDOWN ->
                    emergencyManager.cancelEmergency(activeConfig)
                com.enderthor.kSafe.data.EmergencyStatus.ALERTING -> { /* ignore tap while alerting */ }
            }
        }
    }

    fun handleCheckinTap() {
        if (!activeConfig.isActive) return
        launch {
            val state = configManager.loadEmergencyStateFlow().first()
            when (state.status) {
                // If countdown is active, tapping timer field also cancels it
                com.enderthor.kSafe.data.EmergencyStatus.COUNTDOWN -> {
                    Timber.d("Emergency cancelled via Timer field tap")
                    emergencyManager.cancelEmergency(activeConfig)
                }
                // Otherwise normal check-in behaviour
                else -> {
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
