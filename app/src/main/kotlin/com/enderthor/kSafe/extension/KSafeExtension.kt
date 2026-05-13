package com.enderthor.kSafe.extension

import com.enderthor.kSafe.BuildConfig
import com.enderthor.kSafe.R
import com.enderthor.kSafe.data.EmergencyReason
import com.enderthor.kSafe.data.EmergencyState
import com.enderthor.kSafe.data.EmergencyStatus
import com.enderthor.kSafe.extension.util.EmergencyResume
import com.enderthor.kSafe.extension.util.decideResume
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.data.ProviderType
import com.enderthor.kSafe.data.RideWellnessRecord
import com.enderthor.kSafe.datatype.CustomMessageDataType
import com.enderthor.kSafe.datatype.CustomMessageState
import com.enderthor.kSafe.datatype.WebhookState
import com.enderthor.kSafe.datatype.SafetyTimerDataType
import com.enderthor.kSafe.datatype.SOSDataType
import com.enderthor.kSafe.datatype.WebhookDataType
import com.enderthor.kSafe.extension.managers.CalibrationLogger
import com.enderthor.kSafe.extension.managers.ConfigurationManager
import com.enderthor.kSafe.extension.crash.CrashDetectionManager
import com.enderthor.kSafe.extension.managers.EmergencyManager
import com.enderthor.kSafe.extension.managers.LocationManager
import com.enderthor.kSafe.extension.util.LogReporter
import com.enderthor.kSafe.extension.managers.MedicalEpisodeDetector
import com.enderthor.kSafe.extension.util.ReadinessAdvice
import com.enderthor.kSafe.extension.util.ReadinessLevel
import com.enderthor.kSafe.extension.managers.WebhookManager
import com.enderthor.kSafe.extension.managers.WellnessMonitor
import com.enderthor.kSafe.extension.util.decideReadiness
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.DeveloperField
import io.hammerhead.karooext.models.FieldValue
import io.hammerhead.karooext.models.FitEffect
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.SystemNotification
import io.hammerhead.karooext.models.WriteToRecordMesg
import io.hammerhead.karooext.models.WriteToSessionMesg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.coroutines.CoroutineContext
import kotlin.math.PI
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
    private lateinit var medicalDetector: MedicalEpisodeDetector
    private lateinit var wellnessMonitor: WellnessMonitor
    private lateinit var carbsTracker: com.enderthor.kSafe.extension.managers.CarbsTracker
    private lateinit var hydrationTracker: com.enderthor.kSafe.extension.managers.HydrationTracker

    private var activeConfig = KSafeConfig()
    private var currentRideState: RideState? = null
    /** True once the ride-start notification has been sent for the current recording session. */
    private var rideStartNotificationSent = false
    /** Set true once the Headwind extension publishes a temperature reading for this session.
     *  When set, we ignore the onboard temperature sensor (device-heat biased) and trust
     *  Headwind's meteo data. Reset implicitly on process restart — Headwind re-emits early
     *  on subscription so we re-flip within seconds if it's still installed. */
    @Volatile private var hasHeadwindTemp = false
    /** True if there was an active ride (Recording or Paused) — used to detect ride end. */
    private var rideWasActive = false

    companion object {
        // @Volatile: written from onCreate / onDestroy on the Main thread but read from
        // FieldTapReceiver (binder thread), DataType polling coroutines (Dispatchers.Default),
        // and the BeepPatternPicker preview (Compose's recomposition dispatcher). Without the
        // volatile annotation a stale-cached null is theoretically possible after the service
        // first starts up on architectures with relaxed memory ordering.
        @Volatile private var instance: KSafeExtension? = null
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
            WebhookDataType("webhook-field-1", applicationContext, karooSystem, slot = 1),
            WebhookDataType("webhook-field-2", applicationContext, karooSystem, slot = 2),
            com.enderthor.kSafe.datatype.CarbLogDataType("carb-log-1", applicationContext, karooSystem, slot = 1),
            com.enderthor.kSafe.datatype.CarbLogDataType("carb-log-2", applicationContext, karooSystem, slot = 2),
            com.enderthor.kSafe.datatype.CarbLogDataType("carb-log-3", applicationContext, karooSystem, slot = 3),
            com.enderthor.kSafe.datatype.CarbStatusDataType("carb-status", applicationContext, karooSystem),
            com.enderthor.kSafe.datatype.CarbBurnRateDataType("carb-burn-rate", applicationContext, karooSystem),
            com.enderthor.kSafe.datatype.CarbsBurnedDataType("carbs-burned", applicationContext, karooSystem),
            com.enderthor.kSafe.datatype.HydrationLogDataType("hyd-log-1", applicationContext, karooSystem, slot = 1),
            com.enderthor.kSafe.datatype.HydrationLogDataType("hyd-log-2", applicationContext, karooSystem, slot = 2),
            com.enderthor.kSafe.datatype.HydrationStatusDataType("hyd-status", applicationContext, karooSystem),
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
        medicalDetector = MedicalEpisodeDetector(
            scope = this,
            onIncident = { reason, tokens ->
                launch {
                    emergencyManager.handleIncident(reason, activeConfig.medicalResponseLevel, activeConfig, tokens)
                }
            },
            calibLogger = calibLogger,
        )
        wellnessMonitor = WellnessMonitor(
            scope = this,
            onIncident = { reason, tokens ->
                launch {
                    emergencyManager.handleIncident(reason, activeConfig.wellnessResponseLevel, activeConfig, tokens)
                }
            },
            calibLogger = calibLogger,
        )
        carbsTracker = com.enderthor.kSafe.extension.managers.CarbsTracker(
            scope = this,
            karooSystem = karooSystem,
            context = applicationContext,
            calibLogger = calibLogger,
        )
        hydrationTracker = com.enderthor.kSafe.extension.managers.HydrationTracker(
            scope = this,
            karooSystem = karooSystem,
            context = applicationContext,
            calibLogger = calibLogger,
        )

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
                val prevActive = activeConfig.isActive
                activeConfig = config
                crashManager.updateConfig(config)
                // Auto-start branch of the four trackers is gated on the current ride state
                // so a config emission at extension boot (or a settings save while idle) does
                // NOT spin up integration / monitoring coroutines outside a ride. Crash is
                // intentionally not gated here — its updateConfig never auto-starts; ride
                // lifecycle and applyIdleMonitoring own the start/stop calls instead.
                val isRecording = currentRideState is RideState.Recording
                medicalDetector.updateConfig(config, isRecording)
                wellnessMonitor.updateConfig(config, isRecording)
                carbsTracker.updateConfig(config, isRecording)
                hydrationTracker.updateConfig(config, isRecording)
                // Toggle calibration logging based on config
                if (config.calibrationLoggingEnabled && !calibLogger.isEnabled) {
                    calibLogger.enable()
                } else if (!config.calibrationLoggingEnabled && calibLogger.isEnabled) {
                    // disable() flushes the remaining buffer to disk before returning
                    calibLogger.disable()
                    // Read the full CSV on IO and send via Telegram (fire-and-forget)
                    launch(Dispatchers.IO) {
                        val logContent = calibLogger.getFileContent()
                        if (logContent.isNotBlank()) {
                            LogReporter.sendLogFile(
                                content  = logContent,
                                fileName = calibLogger.fileNameForSession,
                                caption  = calibLogger.captionForSession(logContent.count { it == '\n' }),
                                karooSystem = karooSystem,
                            )
                        }
                    }
                }
                // Re-evaluate monitoring based on current ride state.
                // Idle: applyIdleMonitoring already honors isActive.
                // Recording: enforce master switch transitions — stop everything if the
                // master was just turned OFF, restart everything if it was just turned ON.
                // An in-flight emergency countdown is left alone — cancel via SOS/cancel button.
                when (currentRideState) {
                    is RideState.Idle -> applyIdleMonitoring(config)
                    is RideState.Recording -> applyMasterSwitchTransition(prevActive)
                    else -> { /* Paused: leave as-is */ }
                }
                Timber.d("Config updated: active=${config.isActive}, crash=${config.crashDetectionEnabled}, outsideRide=${config.crashMonitorOutsideRide}, anySpeed=${config.crashMonitorOutsideRideAnySpeed}")
            }
        }

        launch {
            // Resume any countdown that survived a process kill — see EmergencyResumeDecision.
            // We read config first (to ensure activeConfig is populated) then load the persisted
            // emergency state. Both .first() calls suspend only until DataStore emits once, so
            // this block completes quickly on startup before any ride state arrives.
            val initialConfig = configManager.loadConfigFlow().first()
            activeConfig = initialConfig
            val state = configManager.loadEmergencyStateFlow().first()
            val decision = decideResume(state, System.currentTimeMillis())
            // Master switch acts as a hard stop — if the user toggled isActive OFF
            // between the process kill and this boot, discard any persisted countdown
            // rather than resuming it.
            if (!initialConfig.isActive && decision !is EmergencyResume.Nothing) {
                Timber.w("Discarding persisted emergency state — master switch is OFF")
                configManager.saveEmergencyState(EmergencyState())
            } else when (decision) {
                EmergencyResume.Nothing -> { /* no-op */ }
                is EmergencyResume.Active -> {
                    Timber.i("Resuming countdown after process restart, remaining=${decision.remainingMs}ms")
                    emergencyManager.resumeCountdown(state, activeConfig)
                }
                EmergencyResume.AfterDeadline -> {
                    val reason = state.reasonEnum
                    if (reason != null) {
                        Timber.i("Resuming countdown after deadline — mini-confirm")
                        // IMPORTANT: clear the persisted state BEFORE starting the mini-confirm.
                        // Otherwise a second process kill during the 10s window would trigger
                        // resumeAfterDeadline again on next boot — infinite re-trigger loop.
                        configManager.saveEmergencyState(EmergencyState())
                        emergencyManager.resumeAfterDeadline(reason, activeConfig)
                    }
                }
                is EmergencyResume.DiscardStale -> {
                    Timber.w("Discarding stale countdown, age=${decision.ageMs}ms")
                    configManager.saveEmergencyState(EmergencyState())
                }
            }
        }

        launch {
            // Observe ride state
            karooSystem.streamRide()
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
                    medicalDetector.updateSpeed(speedKmh)
                }
        }

        launch {
            // Stream cadence to crash detector.
            // Cadence > 20 RPM during SILENCE_CHECK = rider is still pedalling → instant false-alarm exit.
            // This stream is optional: if no cadence sensor is paired the flow emits nothing and
            // cadenceDataReceived stays false, so the gate never blocks a real crash.
            karooSystem.streamDataFlow(io.hammerhead.karooext.models.DataType.Type.CADENCE)
                .collect { streamState ->
                    val cadenceRpm = streamState.cadenceRpm() ?: return@collect
                    crashManager.updateCadence(cadenceRpm)
                }
        }

        launch {
            // Stream road grade (%) to crash detector.
            // Used for a proactive peak-threshold boost on descents to reduce terrain-noise false alarms
            // before the reactive TERRAIN_CLUSTER mechanism can engage.
            karooSystem.streamDataFlow(io.hammerhead.karooext.models.DataType.Type.ELEVATION_GRADE)
                .collect { streamState ->
                    val grade = streamState.gradePercent() ?: return@collect
                    crashManager.updateGrade(grade)
                }
        }

        launch {
            // Stream the active Karoo ride profile to the crash detector.
            // routingPreference (ROAD/GRAVEL/MTB) is logged in PERIODIC and IMPACT_ENTER rows
            // for post-ride calibration analysis. It is not used as a gate or threshold modifier
            // at runtime — the reactive cluster boost and grade-aware boost handle that.
            karooSystem.streamRideProfile()
                .collect { profile ->
                    crashManager.updateRideProfile(profile.routingPreference)
                }
        }

        launch {
            // Power meter stream — optional. carbsTracker uses it for the zone multiplier; the
            // wellnessMonitor's cardiac-decoupling tier uses it for the HR/W ratio; the
            // hydrationTracker uses it as the preferred metabolic-rate input for the sweat
            // estimator. If absent, the carb tracker falls back to HR zones, decoupling
            // auto-skips, and hydration falls back to HR-derived metabolic rate.
            karooSystem.streamDataFlow(io.hammerhead.karooext.models.DataType.Type.POWER)
                .collect { streamState ->
                    val w = streamState.powerW() ?: return@collect
                    carbsTracker.updatePower(w)
                    wellnessMonitor.updatePower(w)
                    hydrationTracker.updatePower(w)
                }
        }

        launch {
            // Rider profile (weight, max HR, FTP, HR zones, power zones). Read continuously —
            // if the rider edits their profile in the Karoo settings mid-ride, the new values
            // propagate immediately. The carb tracker uses it for HR/power zone multiplier,
            // the wellness monitor for the optional % of max HR threshold mode, and the
            // hydration tracker for the body-mass scaling factor in the sweat estimator.
            karooSystem.streamUserProfile()
                .collect { profile ->
                    carbsTracker.updateUserProfile(profile)
                    wellnessMonitor.updateUserProfile(profile)
                    hydrationTracker.updateUserProfile(profile)
                }
        }

        launch {
            // HR stream (ANT+/BLE). Optional: silent when no sensor is paired.
            karooSystem.streamDataFlow(io.hammerhead.karooext.models.DataType.Type.HEART_RATE)
                .collect { streamState ->
                    val hr = streamState.heartRateBpm() ?: return@collect
                    medicalDetector.updateHr(hr)
                    wellnessMonitor.updateHr(hr)
                    carbsTracker.updateHr(hr)
                    hydrationTracker.updateHr(hr)
                }
        }

        // ── HydrationTracker — ambient temperature + humidity streams ─────────
        // Power, user profile and HR are pushed into the hydration tracker from the
        // collectors above. The remaining inputs (temperature, humidity) live here
        // because the hydration tracker is their only consumer.
        launch {
            // Onboard Karoo temperature sensor. Device-heat biased (typically reads
            // +3–8 °C above ambient when in direct sun / after warm-up), but always
            // available — used as fallback when Headwind isn't publishing.
            karooSystem.streamDataFlow(io.hammerhead.karooext.models.DataType.Type.TEMPERATURE)
                .collect { streamState ->
                    val s = streamState as? io.hammerhead.karooext.models.StreamState.Streaming
                        ?: return@collect
                    val tempC = s.dataPoint.singleValue ?: return@collect
                    if (!hasHeadwindTemp) hydrationTracker.updateAmbientTemp(tempC)
                }
        }
        // ── Headwind extension streams (TYPE_EXT::karoo-headwind::xxx) ────────
        // If the karoo-headwind extension is installed on the rider's Karoo, prefer its
        // weather data (real meteo API) over the onboard sensor. The streams below are
        // silent on devices without Headwind — no error, just no emissions.
        // TypeId convention documented at https://github.com/timklge/karoo-headwind.
        launch {
            karooSystem.streamDataFlow("TYPE_EXT::karoo-headwind::temperature")
                .collect { streamState ->
                    val s = streamState as? io.hammerhead.karooext.models.StreamState.Streaming
                        ?: return@collect
                    val tempC = s.dataPoint.singleValue ?: return@collect
                    hasHeadwindTemp = true
                    hydrationTracker.updateAmbientTemp(tempC)
                }
        }
        launch {
            karooSystem.streamDataFlow("TYPE_EXT::karoo-headwind::relativeHumidity")
                .collect { streamState ->
                    val s = streamState as? io.hammerhead.karooext.models.StreamState.Streaming
                        ?: return@collect
                    val rh = s.dataPoint.singleValue ?: return@collect
                    hydrationTracker.updateHumidity(rh.toInt().coerceIn(0, 100))
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
                    medicalDetector.start(activeConfig)
                    wellnessMonitor.start(activeConfig)
                    carbsTracker.start(activeConfig)
                    hydrationTracker.start(activeConfig)
                    emergencyManager.startCheckinTimer(activeConfig)
                    // Only send the start notification on the very first Recording event.
                    // Resuming from Pause also triggers Recording — we skip it there.
                    if (!rideStartNotificationSent) {
                        rideStartNotificationSent = true
                        sendRideStartNotification()
                        // Readiness advice from the last 10 rides' wellness summaries.
                        // Silent when RECOVERED (decideReadiness returns null) — no per-ride spam.
                        if (activeConfig.readinessAtRideStartEnabled) {
                            launch {
                                val history = configManager.loadWellnessHistoryFlow().first()
                                val advice = decideReadiness(history, System.currentTimeMillis())
                                if (advice != null) fireReadinessAdvice(advice)
                            }
                        }
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
                // Snapshot wellness BEFORE stop() so a future change to stop() that resets
                // accumulators can't silently erase the per-ride summary we need to persist.
                val wellnessSnapshot = if (wasActive && activeConfig.wellnessEnabled)
                    wellnessMonitor.getSummary() else null
                emergencyManager.stopAll()
                medicalDetector.stop()
                wellnessMonitor.stop()
                carbsTracker.stop()
                hydrationTracker.stop()
                applyIdleMonitoring(activeConfig)
                // Reset per-ride flags
                rideStartNotificationSent = false
                rideWasActive = false
                // Send ride-end notification if there was an active ride.
                // Suppressed when the master switch is OFF (per settings_master_hint:
                // "Notifications already configured (ride start/end, …) are also suppressed").
                if (wasActive && activeConfig.isActive) {
                    sendRideEndNotification()
                    // Persist the wellness summary for the next ride's readiness advice.
                    if (wellnessSnapshot != null) persistWellnessSummary(wellnessSnapshot)
                }
                // Auto-send calibration log if it was active during the ride
                // (only if the user hasn't already turned off logging — that path sends its own copy)
                if (wasActive && wasLogging && calibLogger.isEnabled) {
                    // Move the file-read to IO so we don't block the Main dispatcher
                    launch(Dispatchers.IO) {
                        val logContent = calibLogger.getFileContent()
                        if (logContent.isNotBlank()) {
                            LogReporter.sendLogFile(
                                content  = logContent,
                                fileName = calibLogger.fileNameForSession,
                                caption  = calibLogger.captionForSession(logContent.count { it == '\n' }),
                                karooSystem = karooSystem,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Called when the master switch flips while a ride is Recording. Stops all monitoring
     * when master goes ON→OFF; restarts everything when master goes OFF→ON. No-op for
     * non-transitions.
     *
     * The OFF→ON path uses `resume()` on the accumulating trackers (wellness, carbs,
     * hydration) so the rider's session totals are preserved across a brief toggle — a
     * fat-finger does not erase a ride's cumulative grams/ml/zone-time. Crash and medical
     * detectors are point-in-time, so they get a fresh start().
     */
    private fun applyMasterSwitchTransition(prevActive: Boolean) {
        val nowActive = activeConfig.isActive
        if (prevActive == nowActive) return
        if (prevActive && !nowActive) {
            Timber.d("Master switch OFF mid-ride — stopping all monitoring")
            crashManager.stop()
            medicalDetector.stop()
            wellnessMonitor.stop()
            carbsTracker.stop()
            hydrationTracker.stop()
            emergencyManager.stopCheckinTimer()
        } else {
            Timber.d("Master switch ON mid-ride — resuming monitoring (preserving session totals)")
            crashManager.start(activeConfig)
            medicalDetector.start(activeConfig)
            wellnessMonitor.resume(activeConfig)
            carbsTracker.resume(activeConfig)
            hydrationTracker.resume(activeConfig)
            emergencyManager.startCheckinTimer(activeConfig)
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
        // cancel-emergency is always allowed so the rider can stop an in-flight alert
        // even after toggling the master switch off. All other BonusActions are
        // suppressed when the master switch is OFF (per settings_master_hint).
        if (actionId == "cancel-emergency") {
            Timber.d("BonusAction: cancel-emergency triggered")
            launch { emergencyManager.cancelEmergency(activeConfig) }
            return
        }
        if (!activeConfig.isActive) {
            Timber.d("BonusAction $actionId ignored — master switch OFF")
            return
        }
        when (actionId) {
            "send-custom-message" -> {
                Timber.d("BonusAction: send-custom-message triggered")
                launch { sendCustomMessage() }
            }
            "trigger-webhook-1" -> {
                Timber.d("BonusAction: trigger-webhook-1 triggered")
                launch { handleWebhookTap(1) }
            }
            "trigger-webhook-2" -> {
                Timber.d("BonusAction: trigger-webhook-2 triggered")
                launch { handleWebhookTap(2) }
            }
            "log-carb-1" -> {
                Timber.d("BonusAction: log-carb-1 triggered")
                handleCarbLogTap(1)
            }
            "log-carb-2" -> {
                Timber.d("BonusAction: log-carb-2 triggered")
                handleCarbLogTap(2)
            }
            "log-carb-3" -> {
                Timber.d("BonusAction: log-carb-3 triggered")
                handleCarbLogTap(3)
            }
            "log-drink-1" -> {
                Timber.d("BonusAction: log-drink-1 triggered")
                handleHydrationLogTap(1)
            }
            "log-drink-2" -> {
                Timber.d("BonusAction: log-drink-2 triggered")
                handleHydrationLogTap(2)
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
     * Persists the wellness summary at the end of an active ride so the next ride start
     * can compute readiness advice. Appends to the rolling 10-record history in DataStore.
     */
    private fun persistWellnessSummary(snapshot: WellnessMonitor.WellnessSummary) {
        launch {
            val current = configManager.loadWellnessHistoryFlow().first()
            val updated = current.append(RideWellnessRecord(
                endedAtMs = System.currentTimeMillis(),
                maxHrBpm = snapshot.maxHrBpm,
                cumMsCriticalAbove = snapshot.cumMsCriticalAbove,
                cumMsSustainedAbove = snapshot.cumMsSustainedAbove,
                maxDriftPct = snapshot.maxDriftPct,
                criticalFires = snapshot.criticalFires,
                sustainedFires = snapshot.sustainedFires,
                decouplingFires = snapshot.decouplingFires,
            ))
            configManager.saveWellnessHistory(updated)
            Timber.d("KSafe: appended wellness record (history size ${updated.records.size})")
        }
    }

    /**
     * Fires the readiness InRideAlert at the start of a ride. Colour-coded by level:
     * CAUTION (amber) for the milder rules, TAKE_IT_EASY (red) for the high-drift rule.
     * RECOVERED never reaches here — [decideReadiness] returns null and the caller skips.
     */
    private fun fireReadinessAdvice(advice: ReadinessAdvice) {
        val (titleRes, bgColor) = when (advice.level) {
            ReadinessLevel.RECOVERED -> return   // never fires — defensive
            ReadinessLevel.CAUTION -> R.string.readiness_alert_caution_title to 0xFFEF6C00.toInt()
            ReadinessLevel.TAKE_IT_EASY -> R.string.readiness_alert_take_easy_title to 0xFFB71C1C.toInt()
        }
        karooSystem.dispatch(InRideAlert(
            id = "ksafe-readiness-${System.currentTimeMillis()}",
            icon = R.drawable.ic_ksafe,
            title = getString(titleRes),
            detail = advice.reasons.joinToString(" • "),
            autoDismissMs = 15_000L,
            backgroundColor = bgColor,
            textColor = 0xFFFFFFFF.toInt(),
        ))
    }

    /**
     * Sends the custom message for the given [slot] (1, 2 or 3) immediately (no countdown).
     * Triggered by data field tap, BonusAction, or "Send" button in Settings.
     * Updates CustomMessageState for that slot so the data field reflects the result.
     * Returns a human-readable result string for display in the UI.
     */
    suspend fun sendCustomMessage(slot: Int = 1): String {
        val config = activeConfig
        if (!config.isActive) {
            CustomMessageState.update(slot, CustomMessageState.ERROR)
            launch { kotlinx.coroutines.delay(4_000L); CustomMessageState.update(slot, CustomMessageState.IDLE) }
            return "Extension is disabled — enable it in Settings first."
        }
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
        val ok = LogReporter.sendLogFile(
            content  = logContent,
            fileName = calibLogger.fileNameForSession,
            caption  = calibLogger.captionForSession(logContent.count { it == '\n' }),
            karooSystem = karooSystem,
        )
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
     * Picks the right user-feedback channel based on ride state:
     *  - Recording → [InRideAlert] so the message lands on top of whatever ride screen
     *                the rider is on (map, data field grid, climb, …) instead of being
     *                pushed to the Karoo's notification drawer where they won't see it.
     *  - Idle / Paused → [SystemNotification], same as before — they're not actively
     *                    looking at the screen so the notification queue is fine.
     *
     * Background: per the in-house design guide system notifications should not fire
     * mid-ride. The webhook tap is rider-initiated so suppression isn't the right call
     * (the rider IS expecting feedback) — switching channel is.
     */
    private fun dispatchWebhookFeedback(id: String, header: String, message: String, bgColor: Int = 0xFF263238.toInt()) {
        if (currentRideState is RideState.Recording) {
            karooSystem.dispatch(InRideAlert(
                id = id,
                icon = R.drawable.ic_ksafe,
                title = header,
                detail = message,
                autoDismissMs = 4_000L,
                backgroundColor = bgColor,
                textColor = 0xFFFFFFFF.toInt(),
            ))
        } else {
            karooSystem.dispatch(SystemNotification(id = id, header = header, message = message))
        }
    }

    /**
     * Fire-and-forget webhook trigger. Shows a SystemNotification (out-of-ride) or an
     * InRideAlert (recording) with the result. Called from BonusAction or directly from
     * the settings UI test path. When geo-fence is enabled for the slot, the request is
     * blocked if the device is further than the configured radius from the target
     * coordinates.
     */
    suspend fun handleWebhookTap(slot: Int) {
        Timber.d("handleWebhookTap called slot=$slot")
        try {
            val config = activeConfig
            val label = if (slot == 1) config.webhook1Label.ifBlank { "Action 1" }
                        else config.webhook2Label.ifBlank { "Action 2" }

            if (!config.isActive) {
                Timber.d("handleWebhookTap slot=$slot blocked — master switch OFF")
                WebhookState.update(slot, WebhookState.ERROR, "disabled")
                launch { kotlinx.coroutines.delay(4_000L); WebhookState.update(slot, WebhookState.IDLE) }
                dispatchWebhookFeedback(
                    id = "ksafe-webhook-$slot-master-off",
                    header = label,
                    message = "Extension is disabled — enable it in Settings first.",
                    bgColor = 0xFFB71C1C.toInt(),
                )
                return
            }

            // ── Enabled check ─────────────────────────────────────────────────
            val enabled = if (slot == 1) config.webhook1Enabled else config.webhook2Enabled
            if (!enabled) {
                Timber.d("handleWebhookTap slot=$slot disabled")
                WebhookState.update(slot, WebhookState.ERROR, "disabled")
                launch { kotlinx.coroutines.delay(4_000L); WebhookState.update(slot, WebhookState.IDLE) }
                dispatchWebhookFeedback(
                    id = "ksafe-webhook-$slot-disabled",
                    header = label,
                    message = "Webhook disabled — enable it in the Actions tab",
                    bgColor = 0xFFB71C1C.toInt(),
                )
                return
            }

            // ── URL check ─────────────────────────────────────────────────────
            val url = if (slot == 1) config.webhook1Url else config.webhook2Url
            if (url.isBlank()) {
                Timber.d("handleWebhookTap slot=$slot no URL")
                WebhookState.update(slot, WebhookState.ERROR, "no URL")
                launch { kotlinx.coroutines.delay(4_000L); WebhookState.update(slot, WebhookState.IDLE) }
                dispatchWebhookFeedback(
                    id = "ksafe-webhook-$slot-nourl",
                    header = label,
                    message = "No URL configured — set one in the Actions tab",
                    bgColor = 0xFFB71C1C.toInt(),
                )
                return
            }

            // ── Geo-fence check ───────────────────────────────────────────────
            val geoEnabled = if (slot == 1) config.webhook1GeoEnabled else config.webhook2GeoEnabled
            if (geoEnabled) {
                val targetLat = if (slot == 1) config.webhook1GeoLat else config.webhook2GeoLat
                val targetLon = if (slot == 1) config.webhook1GeoLon else config.webhook2GeoLon
                val radiusM   = if (slot == 1) config.webhook1GeoRadiusM else config.webhook2GeoRadiusM
                val curLat = locationManager.lastLat
                val curLon = locationManager.lastLng
                if (curLat == 0.0 && curLon == 0.0) {
                    WebhookState.update(slot, WebhookState.ERROR, "no GPS")
                    launch { kotlinx.coroutines.delay(4_000L); WebhookState.update(slot, WebhookState.IDLE) }
                    dispatchWebhookFeedback(
                        id = "ksafe-webhook-$slot-geo-nofix",
                        header = label,
                        message = "Blocked — no GPS fix yet",
                        bgColor = 0xFFE65100.toInt(),
                    )
                    return
                }
                if (targetLat == 0.0 && targetLon == 0.0) {
                    WebhookState.update(slot, WebhookState.ERROR, "no target")
                    launch { kotlinx.coroutines.delay(4_000L); WebhookState.update(slot, WebhookState.IDLE) }
                    dispatchWebhookFeedback(
                        id = "ksafe-webhook-$slot-geo-nocfg",
                        header = label,
                        message = "Blocked — no target location configured",
                        bgColor = 0xFFE65100.toInt(),
                    )
                    return
                }
                val distance = distanceMeters(curLat, curLon, targetLat, targetLon)
                if (distance > radiusM) {
                    val distKm = if (distance >= 1000) "${"%.1f".format(distance/1000)}km" else "${distance.toInt()}m"
                    WebhookState.update(slot, WebhookState.ERROR, "geo $distKm")
                    launch { kotlinx.coroutines.delay(5_000L); WebhookState.update(slot, WebhookState.IDLE) }
                    dispatchWebhookFeedback(
                        id = "ksafe-webhook-$slot-geo-far",
                        header = label,
                        message = "Blocked — ${distance.toInt()}m away (max ${radiusM}m)",
                        bgColor = 0xFFE65100.toInt(),
                    )
                    Timber.d("Webhook $slot geo-fenced: ${distance.toInt()}m > ${radiusM}m")
                    return
                }
                Timber.d("Webhook $slot geo-fence passed: ${distance.toInt()}m <= ${radiusM}m")
            }

            Timber.d("handleWebhookTap slot=$slot firing HTTP request")
            WebhookState.update(slot, WebhookState.FIRING, "firing…")
            val result = webhookManager.trigger(slot, config)
            Timber.d("handleWebhookTap slot=$slot result=${result.success} msg=${result.message}")
            val resultMsg = if (result.success) "OK ✓" else "ERR"
            WebhookState.update(slot, if (result.success) WebhookState.SUCCESS else WebhookState.ERROR, resultMsg)
            launch { kotlinx.coroutines.delay(4_000L); WebhookState.update(slot, WebhookState.IDLE) }

            dispatchWebhookFeedback(
                id = "ksafe-webhook-$slot-${if (result.success) "ok" else "err"}",
                header = label,
                message = if (result.success) "$label ✓" else result.message,
                bgColor = if (result.success) 0xFF1B5E20.toInt() else 0xFFB71C1C.toInt(),
            )
            if (result.success) {
                val alertEnabled = if (slot == 1) config.webhook1AlertEnabled else config.webhook2AlertEnabled
                val alertText    = if (slot == 1) config.webhook1AlertText    else config.webhook2AlertText
                if (alertEnabled && alertText.isNotBlank()) {
                    dispatchWebhookFeedback(
                        id = "ksafe-webhook-$slot-alert",
                        header = label,
                        message = alertText,
                        bgColor = 0xFF1565C0.toInt(),
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "handleWebhookTap slot=$slot EXCEPTION: ${e.message}")
            WebhookState.update(slot, WebhookState.ERROR, "exception")
            launch { kotlinx.coroutines.delay(4_000L); WebhookState.update(slot, WebhookState.IDLE) }
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

    /** Returns the carbs tracker, or null if not yet initialised (called from data fields). */
    fun carbsTrackerOrNull(): com.enderthor.kSafe.extension.managers.CarbsTracker? =
        if (this::carbsTracker.isInitialized) carbsTracker else null

    /** Returns the hydration tracker, or null if not yet initialised. */
    fun hydrationTrackerOrNull(): com.enderthor.kSafe.extension.managers.HydrationTracker? =
        if (this::hydrationTracker.isInitialized) hydrationTracker else null

    /** Returns the wellness monitor, or null if not yet initialised (called from FIT writer). */
    fun wellnessMonitorOrNull(): com.enderthor.kSafe.extension.managers.WellnessMonitor? =
        if (this::wellnessMonitor.isInitialized) wellnessMonitor else null

    fun handleCarbLogTap(slot: Int) {
        Timber.d("handleCarbLogTap slot=$slot")
        if (!activeConfig.isActive) return
        if (!activeConfig.carbsTrackerEnabled) return
        if (!this::carbsTracker.isInitialized) return
        val state = com.enderthor.kSafe.datatype.CarbLogState.flowForSlot(slot).value
        if (state == com.enderthor.kSafe.datatype.CarbLogState.LOGGED) {
            // Second tap within the undo window — reverse the previous entry.
            val undone = carbsTracker.undoLastForSlot(slot)
            if (undone > 0) {
                com.enderthor.kSafe.datatype.CarbLogState.update(slot, com.enderthor.kSafe.datatype.CarbLogState.UNDONE)
                launch {
                    kotlinx.coroutines.delay(1_500L)
                    if (com.enderthor.kSafe.datatype.CarbLogState.flowForSlot(slot).value
                        == com.enderthor.kSafe.datatype.CarbLogState.UNDONE) {
                        com.enderthor.kSafe.datatype.CarbLogState.update(slot, com.enderthor.kSafe.datatype.CarbLogState.IDLE)
                    }
                }
            } else {
                com.enderthor.kSafe.datatype.CarbLogState.update(slot, com.enderthor.kSafe.datatype.CarbLogState.IDLE)
            }
            return
        }
        carbsTracker.logEntry(slot)
        // 5 s window: long enough to notice a wrong tap and undo, short enough that a
        // legitimate second log on the same slot isn't an annoying wait.
        com.enderthor.kSafe.datatype.CarbLogState.update(slot, com.enderthor.kSafe.datatype.CarbLogState.LOGGED)
        launch {
            kotlinx.coroutines.delay(5_000L)
            // Guard against races with a subsequent tap that already changed the state.
            if (com.enderthor.kSafe.datatype.CarbLogState.flowForSlot(slot).value
                == com.enderthor.kSafe.datatype.CarbLogState.LOGGED) {
                com.enderthor.kSafe.datatype.CarbLogState.update(slot, com.enderthor.kSafe.datatype.CarbLogState.IDLE)
            }
        }
    }

    fun handleHydrationLogTap(slot: Int) {
        Timber.d("handleHydrationLogTap slot=$slot")
        if (!activeConfig.isActive) return
        if (!activeConfig.hydrationTrackerEnabled) return
        if (!this::hydrationTracker.isInitialized) return
        val state = com.enderthor.kSafe.datatype.HydrationLogState.flowForSlot(slot).value
        if (state == com.enderthor.kSafe.datatype.HydrationLogState.LOGGED) {
            val undone = hydrationTracker.undoLastForSlot(slot)
            if (undone > 0) {
                com.enderthor.kSafe.datatype.HydrationLogState.update(slot, com.enderthor.kSafe.datatype.HydrationLogState.UNDONE)
                launch {
                    kotlinx.coroutines.delay(1_500L)
                    if (com.enderthor.kSafe.datatype.HydrationLogState.flowForSlot(slot).value
                        == com.enderthor.kSafe.datatype.HydrationLogState.UNDONE) {
                        com.enderthor.kSafe.datatype.HydrationLogState.update(slot, com.enderthor.kSafe.datatype.HydrationLogState.IDLE)
                    }
                }
            } else {
                com.enderthor.kSafe.datatype.HydrationLogState.update(slot, com.enderthor.kSafe.datatype.HydrationLogState.IDLE)
            }
            return
        }
        hydrationTracker.logEntry(slot)
        com.enderthor.kSafe.datatype.HydrationLogState.update(slot, com.enderthor.kSafe.datatype.HydrationLogState.LOGGED)
        launch {
            kotlinx.coroutines.delay(5_000L)
            if (com.enderthor.kSafe.datatype.HydrationLogState.flowForSlot(slot).value
                == com.enderthor.kSafe.datatype.HydrationLogState.LOGGED) {
                com.enderthor.kSafe.datatype.HydrationLogState.update(slot, com.enderthor.kSafe.datatype.HydrationLogState.IDLE)
            }
        }
    }

    fun handleSOSTap() {
        // Use in-memory currentStatus — reading DataStore here can race with
        // the async COUNTDOWN save inside countdownJob, causing cancels to be
        // misidentified as new triggers.
        // Cancel-path is always allowed (mirrors onBonusAction cancel-emergency):
        // a rider must always be able to stop an in-flight alert, even if the
        // master switch was toggled off after the countdown started.
        launch {
            when (emergencyManager.currentStatus) {
                EmergencyStatus.COUNTDOWN ->
                    emergencyManager.cancelEmergency(activeConfig)
                EmergencyStatus.IDLE -> {
                    if (!activeConfig.isActive) return@launch
                    emergencyManager.triggerEmergency(EmergencyReason.MANUAL_SOS, activeConfig)
                }
                EmergencyStatus.ALERTING -> { /* ignore tap while alerting */ }
            }
        }
    }

    fun handleCheckinTap() {
        launch {
            when (emergencyManager.currentStatus) {
                EmergencyStatus.COUNTDOWN -> {
                    // Cancel path always allowed — see handleSOSTap rationale.
                    Timber.d("Emergency cancelled via Timer field tap")
                    emergencyManager.cancelEmergency(activeConfig)
                }
                EmergencyStatus.ALERTING -> { /* ignore tap while alerting */ }
                EmergencyStatus.IDLE -> {
                    if (!activeConfig.isActive) return@launch
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
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLon = (lon2 - lon1) * PI / 180.0
        val a = sin(dLat / 2).pow(2) +
                cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    /**
     * Writes cumulative carbs (g) and hydration (ml) values into the FIT file as
     * developer fields, so the rider's activity in Strava / Intervals.icu /
     * TrainingPeaks carries native graphs of fueling alongside HR / power / cadence.
     *
     * Two channels written:
     *  - **Record messages** (per-second) while `RideState.Recording` → step curves
     *    over the ride's timeline. Aligned with native HR / power samples.
     *  - **Session message** (whole-ride summary) updated on every Recording tick
     *    → whichever value is current when the ride closes becomes the activity
     *    summary header in Strava / Intervals.icu / TrainingPeaks. Written from
     *    the Recording branch (NOT Paused) because the ELAPSED_TIME stream stops
     *    emitting while paused, so a Paused-only write would never actually fire.
     *
     * Both fields use `fitBaseTypeId = 136` (= `0x88` = float32). Float gives
     * room for future enhancements like fractional values (e.g. carb burn rate)
     * without needing a schema migration. Matches the nomride convention so the
     * field type is consistent across cycling-fueling extensions.
     *
     * Cadence is driven by the `ELAPSED_TIME` data stream, NOT a fixed `delay()`
     * loop. ELAPSED_TIME emits exactly when the ride app advances its 1 Hz Record
     * timer, so our writes align perfectly and there's zero drift. The stream
     * pauses when the ride pauses, so paused minutes don't accumulate phantom
     * record samples — exactly the semantics we want.
     *
     * Trackers may not be initialised when the FIT pipeline starts (rider hasn't
     * opted into fueling). We fall back to 0 safely — the column appears in the
     * FIT but stays flat at zero, which is honest data and lets a rider who
     * enables fueling mid-season backfill cleanly.
     *
     * Toggleable via `KSafeConfig.fuelingFitExportEnabled` (default ON). The cost
     * is negligible (~0.05 % battery over a 5 h ride, no perceptible CPU) but
     * riders who don't want extra columns in their FIT can opt out. The toggle
     * is sampled once at FIT-pipeline start (typically next ride start); changes
     * made mid-ride don't take effect until the next ride. A hot-toggle would
     * be premature complexity for a setting riders almost never flip mid-ride.
     */
    override fun startFit(emitter: Emitter<FitEffect>) {
        if (!activeConfig.fuelingFitExportEnabled) {
            emitter.setCancellable { }
            return
        }

        val carbField = DeveloperField(
            fieldDefinitionNumber = 0,
            fitBaseTypeId = 136,            // float32 — same convention as nomride
            fieldName = "ksafe_carbs_g",
            units = "g",
            nativeFieldNum = null,
            developerDataIndex = 0,
        )
        val hydField = DeveloperField(
            fieldDefinitionNumber = 1,
            fitBaseTypeId = 136,
            fieldName = "ksafe_hyd_ml",
            units = "ml",
            nativeFieldNum = null,
            developerDataIndex = 0,
        )
        // ── Wellness developer fields ──────────────────────────────────────
        // fieldDefinitionNumbers 2..4 are now PUBLIC API — historical FIT files reference
        // them by number, so these are immutable once shipped. `ksafe_hr_drift_pct` is a
        // per-record stream (the only KSafe value not derivable from native FIT data —
        // Strava does not compute cardiac decoupling on its own). `ksafe_max_drift_pct`
        // and `ksafe_wellness_fires` are session totals that show up in the activity
        // header alongside the fueling totals.
        val hrDriftField = DeveloperField(
            fieldDefinitionNumber = 2,
            fitBaseTypeId = 136,
            fieldName = "ksafe_hr_drift_pct",
            units = "%",
            nativeFieldNum = null,
            developerDataIndex = 0,
        )
        val maxDriftField = DeveloperField(
            fieldDefinitionNumber = 3,
            fitBaseTypeId = 136,
            fieldName = "ksafe_max_drift_pct",
            units = "%",
            nativeFieldNum = null,
            developerDataIndex = 0,
        )
        val firesField = DeveloperField(
            fieldDefinitionNumber = 4,
            fitBaseTypeId = 136,
            fieldName = "ksafe_wellness_fires",
            units = "count",
            nativeFieldNum = null,
            developerDataIndex = 0,
        )
        // Carb burn-rate / cumulative-burned developer fields. Numbers 5 and 6 are
        // immutable once shipped, same contract as fields 2..4 above.
        val carbsBurnedField = DeveloperField(
            fieldDefinitionNumber = 5,
            fitBaseTypeId = 136,
            fieldName = "ksafe_carbs_burned_g",
            units = "g",
            nativeFieldNum = null,
            developerDataIndex = 0,
        )
        val burnRateField = DeveloperField(
            fieldDefinitionNumber = 6,
            fitBaseTypeId = 136,
            fieldName = "ksafe_carb_burn_rate_gph",
            units = "g/h",
            nativeFieldNum = null,
            developerDataIndex = 0,
        )

        calibLogger.log(CalibrationLogger.Event.FIT_WRITER_START) {
            // Field-definition numbers are public-API once shipped; record them so the CSV
            // can be cross-referenced with the developer-field schema in the resulting FIT.
            "fields=0,1,2,3,4,5,6"
        }
        val job: Job = launch {
            karooSystem.streamDataFlow(DataType.Type.ELAPSED_TIME)
                .mapNotNull { (it as? StreamState.Streaming)?.dataPoint?.singleValue }
                .collect {
                    val carbStatus = carbsTrackerOrNull()?.getStatus()
                    val carbsG       = (carbStatus?.cumLoggedG ?: 0).toDouble()
                    val carbsBurnedG = (carbStatus?.cumTargetG ?: 0).toDouble()
                    val burnRateGph  = (carbStatus?.burnRateGph ?: 0).toDouble()
                    val hydMl  = (hydrationTrackerOrNull()?.getStatus()?.cumLoggedMl ?: 0).toDouble()
                    val wellness = wellnessMonitorOrNull()?.getSummary()
                    val driftPct    = wellness?.currentDriftPct?.toDouble() ?: 0.0
                    val maxDriftPct = wellness?.maxDriftPct?.toDouble() ?: 0.0
                    val fires       = wellness?.totalFires?.toDouble() ?: 0.0
                    val values = listOf(
                        FieldValue(carbField,         carbsG),
                        FieldValue(hydField,          hydMl),
                        FieldValue(hrDriftField,      driftPct),
                        FieldValue(maxDriftField,     maxDriftPct),
                        FieldValue(firesField,        fires),
                        FieldValue(carbsBurnedField,  carbsBurnedG),
                        FieldValue(burnRateField,     burnRateGph),
                    )
                    when (currentRideState) {
                        is RideState.Recording -> {
                            // Per-second record sample — lands on the same FIT timestamp as
                            // the native HR / power / cadence sample for that tick.
                            emitter.onNext(WriteToRecordMesg(values))
                            // Session totals — every Recording tick overwrites the running
                            // value; whatever is current when the ride ends becomes the
                            // activity summary header in Strava etc. Must be written here
                            // (not in the Paused branch as nomride does) because
                            // ELAPSED_TIME stops emitting while the ride is paused, so a
                            // Paused-only write would never actually fire.
                            emitter.onNext(WriteToSessionMesg(values))
                        }
                        else -> { /* Paused / Idle / null: don't emit */ }
                    }
                }
        }
        emitter.setCancellable {
            job.cancel()
            calibLogger.log(CalibrationLogger.Event.FIT_WRITER_STOP) { "" }
        }
    }

    override fun onDestroy() {
        crashManager.stop()
        medicalDetector.stop()
        wellnessMonitor.stop()
        carbsTracker.stop()
        hydrationTracker.stop()
        locationManager.stop()
        emergencyManager.stopAll()
        calibLogger.disable()
        karooSystem.disconnect()
        job.cancel()
        instance = null
        super.onDestroy()
    }
}
