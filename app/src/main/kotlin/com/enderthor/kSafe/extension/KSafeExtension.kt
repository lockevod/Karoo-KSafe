package com.enderthor.kSafe.extension

import com.enderthor.kSafe.BuildConfig
import com.enderthor.kSafe.R
import com.enderthor.kSafe.data.EmergencyReason
import com.enderthor.kSafe.data.EmergencyState
import com.enderthor.kSafe.data.EmergencyStatus
import com.enderthor.kSafe.extension.util.EmergencyResume
import com.enderthor.kSafe.extension.util.decideResume
import com.enderthor.kSafe.extension.util.formatUs
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.data.ProviderType
import com.enderthor.kSafe.data.RideWellnessRecord
import android.content.res.Configuration
import com.enderthor.kSafe.datatype.CustomMessageDataType
import com.enderthor.kSafe.datatype.CustomMessageState
import com.enderthor.kSafe.datatype.isKarooNightMode
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
import com.enderthor.kSafe.extension.util.learnProfile
import com.enderthor.kSafe.extension.util.resolveEffectiveCrashConfig
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.coroutines.CoroutineContext
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val FUELING_PERSIST_INTERVAL_MS: Long = 30_000L
/** Hard cap on how long the ride-state collector waits for the first config emission
 *  (configSeeded) before proceeding anyway. The wait only avoids a brief default-config
 *  window; 5 s is far longer than a healthy DataStore first read, so if it elapses the
 *  config load is broken and we must NOT keep crash detection / ride handling blocked. */
private const val CONFIG_SEED_TIMEOUT_MS: Long = 5_000L
/** Coarse poll interval for the background loops while their work-gate is unmet
 *  (no ride recording / calibration logging disabled). The loops still wake to
 *  re-check the gate, but at ~2 min instead of their active 30 s / 60 s cadence —
 *  so an extension sitting on the dock (or any rider who never enables calibration
 *  logging, i.e. nearly all of them) stops paying ~120 wakeups/h for no work. The
 *  only cost is up to one idle interval of latency before the first persist after a
 *  ride starts / before the health-check resumes after logging is enabled, both of
 *  which are non-critical. The ACTIVE cadence is unchanged once the gate is met. */
private const val BACKGROUND_IDLE_POLL_MS: Long = 2L * 60_000L
/** Auto-send the calibration log every 20 minutes while a ride is recording so a
 *  long ride with intermittent coverage still trickles data out instead of waiting
 *  for the post-ride upload (which may itself fail). On success, the file is
 *  truncated and the next 20-minute window accumulates fresh. */
private const val CALIBRATION_PERIODIC_SEND_INTERVAL_MS: Long = 20L * 60_000L
/** Health-check the calibration logger every minute while recording — restart the
 *  flush coroutine if it has stopped writing. Cheap (one atomic read + age compare). */
private const val CALIBRATION_HEALTH_CHECK_INTERVAL_MS: Long = 60_000L

/** Maximum CSV-content size for a single calibration-log chunk sent through
 *  `KarooSystemService.httpRequest`. The SDK marshals the request body across the
 *  Android Binder transaction buffer; the kernel limit is ~1 MB shared system-wide
 *  but the practical safe size per call is much lower because the buffer is
 *  contended. Empirical 2026-05-25 data: 77 KB succeeded reliably (file
 *  `(7)` of session 58ee00), 262 KB failed with `IllegalArgumentException:
 *  Request too large` and never recovered for the remaining 2 hours of the
 *  ride. 72 KB sits comfortably below the known-working ceiling and leaves
 *  margin for multipart overhead (boundary + Content-Disposition headers +
 *  caption ≈ 500 bytes) plus Binder buffer pressure from other apps. */
private const val CALIBRATION_MAX_CHUNK_BYTES: Int = 72_000

/** Per-cycle ceiling on the number of chunks the periodic loop will send back-to-back
 *  when catching up after one or more failed windows. Without a cap a rider whose
 *  Karoo accumulated 10 windows of data while offline would block the periodic
 *  coroutine through 10 sequential HTTP round-trips (~5 minutes total). The cap
 *  drains the backlog gradually across several cycles instead of starving the
 *  coroutine on one cycle. The end-of-ride / manual-send / disable-logging paths
 *  pass `Int.MAX_VALUE` because they want to drain fully before returning. */
private const val CALIBRATION_PERIODIC_MAX_CHUNKS_PER_CYCLE: Int = 6

/** Deadband on `cumBurnedG` for FIT session-message writes. The session message
 *  is "last write wins" — only the value at FIT close becomes the activity
 *  header in Strava et al., so intra-ride session writes only matter for
 *  resilience to a sudden FIT-close. A 5 g threshold means the header is at
 *  most 5 g behind the true total (sub-2 % error on a typical 300 g ride) and
 *  the session-write rate drops ~5× vs writing on every gram increment. */
private const val SESSION_BURN_DEADBAND_G: Double = 5.0

/** Deadband on `CarbFuelingState.cumBurnedG` for the fueling-persistence loop.
 *  Persisted state is restored after a process kill (FUELING_RESTORE_MAX_AGE_MS).
 *
 *  Sizing: at 50 g/h moderate-intensity, the integrator advances ~0.42 g per
 *  30 s persist cycle. The deadband must comfortably exceed the per-cycle delta
 *  or it never fires while moving — that's the [B5] fix territory. 5 g is the
 *  binding choice: it gives ~6 minutes of integration between writes at moderate
 *  intensity, ~3.3 minutes at the 90 g/h absorption-cap. Worst-case loss on an
 *  unexpected process kill is therefore ≤ 5 g of carb burn — well below the
 *  10-15 % error band of the burn estimator itself (Keytel / Swain), so it's
 *  rider-invisible noise. Pre-v18.2 this was 1 g, which over-targeted accuracy
 *  vs DataStore writes — ~400 persists/5h ride instead of ~50. */
private const val PERSIST_CARB_BURN_DEADBAND_G: Float = 5.0f

/** Same as [PERSIST_CARB_BURN_DEADBAND_G] for the hydration target accumulator.
 *  At the default 750 ml/h, the integrator advances ~6.25 ml per 30 s cycle.
 *  60 ml = ~4.8 minutes between writes at default rate, well above the
 *  per-cycle delta. Pre-v18.2 this was 10 ml — too tight to be the binding
 *  constraint (write fired every ~48 s, dominating the persist rate even after
 *  the carb deadband was raised). Worst-case loss on process kill: ≤ 60 ml,
 *  within the SweatEstimator's ±20 % accuracy on a 750 ml/h baseline. */
private const val PERSIST_HYD_TARGET_DEADBAND_ML: Float = 60.0f

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
    /** Persistent bind to the Karoo's HAL beeper service for emergency mute-bypass.
     *  See [BuzzerClient]. Bind is fire-and-forget — if the HAL package isn't visible
     *  or the bind fails, every call site degrades to a no-op. */
    private lateinit var buzzerClient: com.enderthor.kSafe.extension.managers.BuzzerClient
    private lateinit var medicalDetector: MedicalEpisodeDetector
    private lateinit var wellnessMonitor: WellnessMonitor
    private lateinit var carbsTracker: com.enderthor.kSafe.extension.managers.CarbsTracker
    private lateinit var hydrationTracker: com.enderthor.kSafe.extension.managers.HydrationTracker
    /** On-screen fueling-alert overlay (SYSTEM_ALERT_WINDOW). Lazy because it needs the
     *  Application context and is only touched when a fueling alert is presented as an
     *  overlay (mode != OFF + overlay permission + no active emergency). The [Lazy] handle
     *  is kept so teardown/emergency paths can check [Lazy.isInitialized] and avoid
     *  instantiating a WindowManager-holding manager that was never used this session. */
    private val fuelingOverlayLazy = lazy { com.enderthor.kSafe.extension.managers.FuelingOverlayManager(applicationContext) }
    private val fuelingOverlay by fuelingOverlayLazy

    private var activeConfig = KSafeConfig()
    /** Completed on the first config emission from DataStore so the ride-state collector
     *  never runs [handleRideState] against KSafeConfig() defaults (isActive / crash /
     *  medical all ON) when the extension (re)connects while the Karoo is already Recording. */
    private val configSeeded = CompletableDeferred<Unit>()
    private var currentRideState: RideState? = null
    @Volatile private var activeProfileId: String? = null
    /** Whether the crash detector is currently running under the effective config. Kept in
     *  sync by [reapplyEffectiveCrash] so a profile switch can reconcile start/stop without
     *  double-registering the sensor listener (CrashDetectionManager.start re-registers). */
    @Volatile private var crashEffectiveRunning = false
    /** True once the ride-start notification has been sent for the current recording session. */
    private var rideStartNotificationSent = false
    /** Set true once the Headwind extension publishes a temperature reading for this session.
     *  When set, we ignore the onboard temperature sensor (device-heat biased) and trust
     *  Headwind's meteo data. Reset implicitly on process restart — Headwind re-emits early
     *  on subscription so we re-flip within seconds if it's still installed. */
    @Volatile private var hasHeadwindTemp = false

    /** Parent Job for the "Recording-only" stream collectors (POWER, HR, TEMPERATURE,
     *  Headwind temp + humidity, UserProfile). Their consumers — the fueling trackers,
     *  WellnessMonitor, MedicalEpisodeDetector — only do real work during a recording,
     *  so the upstream SDK subscriptions waste IPC + collector wakes outside a ride.
     *  Cancelled on the Idle transition; (re-)launched on the first Recording entry.
     *  Idempotent — extra calls to [startRecordingCollectors] while already active
     *  are no-ops. */
    @Volatile private var recordingCollectorsJob: kotlinx.coroutines.Job? = null
    /** Tracks the in-flight calibration-log periodic-send drain. The 20-min cycle
     *  skips re-launching when this job is still active so two parallel periodic
     *  drains can't read the same first chunk before the first finishes
     *  truncating (would duplicate an upload under slow-LTE conditions).
     *  See also [calibSendMutex] for the cross-callsite guard. */
    @Volatile private var periodicSendJob: kotlinx.coroutines.Job? = null
    /** Serialises EVERY calibration-log drain across the four entry points
     *  (periodic loop, ride-end, logging-disabled, manual). Without it, a
     *  periodic drain in-flight when the rider stops the ride could race the
     *  ride-end drain: both read the same chunk1 (no truncate had happened
     *  yet), both ship chunk1 to Telegram, both then truncate `linesIncluded`
     *  lines — the first truncate drops the real chunk1, the second drops
     *  what is now chunk2. Net: chunk1 sent twice, chunk2 silently lost.
     *  [periodicSendJob] only guards the periodic-vs-periodic case; this
     *  mutex covers every other cross-pair. Held for the entire drain
     *  loop (multi-chunk if catching up); other callsites await rather
     *  than skip — manual / ride-end / logging-disabled all want to drain
     *  the file completely, not silently no-op. */
    private val calibSendMutex = Mutex()
    /** SPEED / CADENCE / ELEVATION_GRADE / ride-profile collector group. These
     *  feed [crashManager], [medicalDetector] and the fueling trackers. They
     *  run whenever the rider is on the bike (Recording / Paused) OR the rider
     *  has [KSafeConfig.crashMonitorOutsideRide] enabled in Idle. When the
     *  Karoo sits on the dock between rides without the outside-ride toggle,
     *  these are cancelled — drops 4 IPC consumers + their per-emission
     *  collector wakeups for as long as the device is idle. Mirrors
     *  [recordingCollectorsJob] (POWER / HR / TEMPERATURE / Headwind /
     *  UserProfile), which already had this lifecycle. */
    @Volatile private var crashSensorCollectorsJob: kotlinx.coroutines.Job? = null
    /** True if there was an active ride (Recording or Paused) — used to detect ride end. */
    private var rideWasActive = false

    /** Snapshot of the persisted fueling state loaded once on extension boot. Consumed by
     *  the first `Recording` transition to restore an in-flight ride that was interrupted
     *  by an extension crash (OOM / update / Android process kill). Cleared after consumption
     *  so a Paused→Recording resume doesn't re-apply it on top of in-memory state. */
    @Volatile private var pendingFuelingRestore: com.enderthor.kSafe.data.FuelingState? = null

    /** Wall-clock timestamp (ms) of the most recent SOS field-tap that armed an emergency
     *  from IDLE. Used by [handleSOSTap] to debounce BOTH the IDLE→trigger transition AND
     *  the immediate COUNTDOWN→cancel transition that follows it.
     *
     *  A nervous rider can double-tap the SOS field within ~100–500 ms before the field
     *  re-renders to clickable=false. Tap 1 arms the countdown synchronously (currentStatus
     *  flips to COUNTDOWN before Tap 2 dispatches on the same single-threaded Main scope),
     *  so Tap 2 falls into the COUNTDOWN branch — not IDLE. Without a debounce on the
     *  cancel path too, Tap 2 would then call cancelEmergency and silently void the rider's
     *  intended alert.
     *
     *  Both branches read this same timestamp and skip when `now - lastSosTriggerMs <
     *  SOS_RETAP_DEBOUNCE_MS`. A legitimate cancel-after-realisation (rider taps after
     *  1+ s of seeing the countdown) still works — only the 750 ms post-arm window is
     *  protected. Hardware-button cancel via onBonusAction("cancel-emergency") is
     *  intentional and is NOT routed through handleSOSTap, so it remains undebounced.
     *  See [SOS_RETAP_DEBOUNCE_MS]. */
    @Volatile private var lastSosTriggerMs: Long = 0L

    /** Per-slot tap-feedback timer jobs (LOGGED→IDLE / UNDONE→IDLE delayed reverts).
     *  Cancelled before a new launch so a stale timer from an earlier tap cannot
     *  clobber a fresher state set by a subsequent tap on the same slot. Indices 1..3
     *  for carbs, 1..2 for hydration; index 0 unused. Touched only from handleCarbLogTap /
     *  handleHydrationLogTap, which run on the extension's Main dispatcher, so plain
     *  arrays (no @Volatile) are safe. */
    private val carbTapRevertJobs: Array<kotlinx.coroutines.Job?> = arrayOfNulls(4)
    private val hydTapRevertJobs: Array<kotlinx.coroutines.Job?> = arrayOfNulls(3)
    private val combinedTapRevertJobs: Array<kotlinx.coroutines.Job?> = arrayOfNulls(3)

    /** Per-slot revert-to-IDLE jobs for webhook and custom-message field state.
     *  Same problem the carb/hyd arrays solve: every ERROR / SUCCESS branch in
     *  handleWebhookTap and sendCustomMessage schedules a delayed `update(slot, IDLE)`.
     *  Without per-slot tracking a job from an earlier tap can outlive the 4 s wait
     *  and stomp a fresher state set by a subsequent tap on the same slot —
     *  e.g. an early-error tap at T+0 schedules IDLE at T+4 s, the rider toggles
     *  master ON at T+1, re-taps at T+2 and the new attempt reaches FIRING, then
     *  the T+0 revert job fires at T+4 s and clobbers FIRING mid-HTTP. The
     *  cancel-before-launch pattern (see [scheduleWebhookRevert] / [scheduleCustomRevert])
     *  closes the race.
     *  Webhook has slots 1..2 (array size 3, index 0 unused); custom message has
     *  slots 1..3 (array size 4, index 0 unused). Touched only on the Main
     *  dispatcher, so plain arrays are safe. */
    private val webhookRevertJobs: Array<kotlinx.coroutines.Job?> = arrayOfNulls(3)
    private val customRevertJobs: Array<kotlinx.coroutines.Job?> = arrayOfNulls(4)

    /** Schedules a delayed revert of the webhook slot's field state to IDLE, cancelling
     *  any previously scheduled revert for the same slot first. Closes the
     *  early-error-stomps-fresh-FIRING race documented on [webhookRevertJobs]. */
    private fun scheduleWebhookRevert(slot: Int, delayMs: Long) {
        webhookRevertJobs[slot]?.cancel()
        webhookRevertJobs[slot] = launch {
            kotlinx.coroutines.delay(delayMs)
            WebhookState.update(slot, WebhookState.IDLE)
            webhookRevertJobs[slot] = null
        }
    }

    /** Schedules a delayed revert of the custom-message slot's field state to IDLE,
     *  cancelling any previously scheduled revert for the same slot first. Mirrors
     *  [scheduleWebhookRevert]; see [customRevertJobs] for the stomp scenario. */
    private fun scheduleCustomRevert(slot: Int, delayMs: Long) {
        customRevertJobs[slot]?.cancel()
        customRevertJobs[slot] = launch {
            kotlinx.coroutines.delay(delayMs)
            CustomMessageState.update(slot, CustomMessageState.IDLE)
            customRevertJobs[slot] = null
        }
    }

    companion object {
        // @Volatile: written from onCreate / onDestroy on the Main thread but read from
        // FieldTapReceiver (binder thread), DataType polling coroutines (Dispatchers.Default),
        // and the BeepPatternPicker preview (Compose's recomposition dispatcher). Without the
        // volatile annotation a stale-cached null is theoretically possible after the service
        // first starts up on architectures with relaxed memory ordering.
        @Volatile private var instance: KSafeExtension? = null
        fun getInstance(): KSafeExtension? = instance
        internal fun setInstance(ext: KSafeExtension) { instance = ext }

        /**
         * Tracker readiness signals. The status DataTypes (CarbStatus, CarbsBurned,
         * CarbBurnRate, HydrationStatus) used to spin a `while (tracker == null)
         * delay(1_000)` loop in their startView until the extension finished
         * initialising — burning a wake-per-second per field, and re-running on
         * every `startView` re-entry (page swap, profile change, etc.).
         *
         * Now: the extension publishes the live tracker references here as soon as
         * they're constructed. DataTypes do `.filterNotNull().first()` once and then
         * collect from the tracker's own `statusFlow` indefinitely — a single
         * suspension instead of N polls.
         *
         * Survives across extension restarts: a destroyed extension nulls these out
         * in [onDestroy] so a stale reference can't outlive its service.
         */
        val carbsTrackerFlow = kotlinx.coroutines.flow.MutableStateFlow<
            com.enderthor.kSafe.extension.managers.CarbsTracker?>(null)
        val hydrationTrackerFlow = kotlinx.coroutines.flow.MutableStateFlow<
            com.enderthor.kSafe.extension.managers.HydrationTracker?>(null)

        /** Current Karoo night-mode (dark) state, republished by the service's
         *  [onConfigurationChanged]. The combine-based AUTO-colour data fields merge this so
         *  they re-render on a day↔night flip — they otherwise only re-emit on a state/config
         *  change and would keep stale (e.g. black-on-black, invisible) text after a
         *  sunset/sunrise theme switch while idle. Seeded in onCreate; rarely changes. */
        val nightModeFlow = kotlinx.coroutines.flow.MutableStateFlow(false)

        /** Active Karoo ride-profile id, mirrored for the Settings UI so the per-profile
         *  "active" badge updates reactively (and without a polling loop) on a profile switch.
         *  Updated by the streamRideProfile collector alongside [activeProfileId]. */
        val activeProfileIdFlow = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

        /** Minimum gap (ms) between two SOS field taps before the second tap is honoured.
         *  Protects BOTH directions around the IDLE→COUNTDOWN flip:
         *   - IDLE→trigger: a phantom retap within the window cannot re-arm.
         *   - COUNTDOWN→cancel: a phantom retap within the window cannot self-cancel the
         *     just-armed countdown (the underlying bug — Tap 1 arms COUNTDOWN
         *     synchronously, Tap 2 dispatches on the same Main scope, reads COUNTDOWN,
         *     and would otherwise fall straight into cancelEmergency).
         *  Covers the typical queued broadcast window (~100–500 ms). A legitimate
         *  cancel-after-realisation (rider taps after 1+ s of seeing the countdown) still
         *  goes through unchanged. The hardware-button cancel-emergency BonusAction is
         *  intentionally NOT routed through handleSOSTap and is never debounced.
         *  NOTE: no facade test harness for KSafeExtension exists today; if one is added,
         *  add tests: "SOS retap within $SOS_RETAP_DEBOUNCE_MS ms is debounced",
         *  "COUNTDOWN cancel within $SOS_RETAP_DEBOUNCE_MS ms is debounced",
         *  "after the window is honoured", "cancel-emergency BonusAction is never
         *  debounced". */
        const val SOS_RETAP_DEBOUNCE_MS: Long = 750L
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
            com.enderthor.kSafe.datatype.CarbBurnRateDataType("carb-burn-rate", applicationContext),
            com.enderthor.kSafe.datatype.CarbAvgBurnRateDataType("carb-avg-burn-rate", applicationContext),
            com.enderthor.kSafe.datatype.CarbsBurnedDataType("carbs-burned", applicationContext),
            com.enderthor.kSafe.datatype.HydrationLogDataType("hyd-log-1", applicationContext, karooSystem, slot = 1),
            com.enderthor.kSafe.datatype.HydrationLogDataType("hyd-log-2", applicationContext, karooSystem, slot = 2),
            com.enderthor.kSafe.datatype.CombinedFuelLogDataType("combined-log-1", applicationContext, karooSystem, slot = 1),
            com.enderthor.kSafe.datatype.CombinedFuelLogDataType("combined-log-2", applicationContext, karooSystem, slot = 2),
            com.enderthor.kSafe.datatype.HydrationStatusDataType("hyd-status", applicationContext, karooSystem),
        )
    }

    override fun onCreate() {
        super.onCreate()
        Timber.d("KSafeExtension created")
        // Seed the night-mode flow so AUTO-colour fields start with the correct text colour.
        nightModeFlow.value = isKarooNightMode()

        karooSystem = KarooSystemService(applicationContext)
        configManager = ConfigurationManager(applicationContext)
        locationManager = LocationManager(karooSystem, this)
        sender = Sender(karooSystem, configManager)
        calibLogger = CalibrationLogger(applicationContext, this, configManager)
        webhookManager = WebhookManager(karooSystem)
        // Bind the HAL buzzer up-front so the binder is ready when the first emergency
        // fires. The bind is async; connect() returns immediately and onServiceConnected
        // populates the binder in the background. If the HAL package isn't visible or
        // refuses the bind, every later beep() call no-ops silently.
        buzzerClient = com.enderthor.kSafe.extension.managers.BuzzerClient(applicationContext)
        val buzzerDiag = buzzerClient.connect()
        Timber.d("BuzzerClient connect: %s", buzzerDiag)
        emergencyManager = EmergencyManager(
            applicationContext, karooSystem, configManager, locationManager, sender, this,
            calibLogger,
            buzzerClient = buzzerClient,
            onCrashEmergencyCancelled = { crashManager.clearCrashCooldown() },
            // Recording OR Paused = rider is on the ride screen (autopause keeps it up), so a
            // delivery-failure InRideAlert is visible there; off it, EmergencyManager uses the
            // overlay instead. Same predicate as dispatchWebhookFeedback's channel switch.
            isOnRideScreen = {
                currentRideState is RideState.Recording || currentRideState is RideState.Paused
            },
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
            onFuelingAlert = ::presentFuelingAlert,
            isEmergencyActive = ::emergencyActive,
            calibLogger = calibLogger,
        )
        hydrationTracker = com.enderthor.kSafe.extension.managers.HydrationTracker(
            scope = this,
            karooSystem = karooSystem,
            context = applicationContext,
            onFuelingAlert = ::presentFuelingAlert,
            isEmergencyActive = ::emergencyActive,
            calibLogger = calibLogger,
        )
        // Publish tracker references so the status DataTypes can suspend on the flow
        // instead of polling getInstance every second. See companion's tracker-flow
        // docs for the rationale.
        carbsTrackerFlow.value = carbsTracker
        hydrationTrackerFlow.value = hydrationTracker

        // Emergency priority: a fueling overlay must never obscure a crash / SOS
        // countdown or alert. The moment the emergency state leaves IDLE, tear down
        // any fueling overlay on screen so the SOS Cancel overlay is unobstructed.
        // Only touch the overlay if it was ever instantiated — otherwise the first
        // emergency would needlessly build a WindowManager-holding manager that has
        // no overlay to remove. (presentFuelingAlert already suppresses new fueling
        // overlays while non-IDLE; this collector is the belt-and-suspenders teardown
        // for one shown in the instant before the transition.)
        launch {
            com.enderthor.kSafe.extension.managers.EmergencyManager.uiState.collect { st ->
                if (st.status != com.enderthor.kSafe.data.EmergencyStatus.IDLE &&
                    fuelingOverlayLazy.isInitialized()) fuelingOverlay.remove()
            }
        }

        // Publish the singleton ONLY after every lateinit manager is constructed.
        // getInstance() is reached from FieldTapReceiver taps and DataType callbacks;
        // publishing `this` before the managers exist would let a caller in that window
        // hit a not-yet-initialised `lateinit` (UninitializedPropertyAccessException).
        // Broadcasts/startView arrive after onCreate returns (Main thread) so the window
        // is effectively unreachable today, but ordering this last removes the latent trap.
        setInstance(this)

        // Warm the install ID cache off-Main so the Settings UI and
        // CalibrationLogger.enable() can read it without blocking on cold
        // DataStore I/O. The lazy in CalibrationLogger.installId runs
        // runBlocking(Dispatchers.IO) on first access — triggering it here
        // from a background coroutine means subsequent Main-thread reads
        // hit the cached value (microsecond field read).
        launch(Dispatchers.IO) {
            // Touch the lazy to force evaluation on the IO dispatcher.
            calibLogger.installId
        }

        karooSystem.connect { connected ->
            if (connected) {
                Timber.d("Connected to Karoo system")
                locationManager.start()
                // Idempotent — the SDK may re-fire `connected=true` on transient
                // reconnects within the same service lifecycle. Without this guard,
                // every reconnect would spawn an additional copy of every collector
                // and `while(true)` loop launched inside initializeSystem, doubling
                // emission handlers + battery cost per reconnect (only onDestroy's
                // job.cancel() ever releases them).
                if (systemInitialized) {
                    Timber.d("Karoo reconnect — initializeSystem already running, skipping respawn")
                } else {
                    systemInitialized = true
                    initializeSystem()
                }
            } else {
                Timber.w("Disconnected from Karoo system")
            }
        }
    }

    @Volatile private var systemInitialized = false

    private fun initializeSystem() {
        launch {
            // Observe config changes
            configManager.loadConfigFlow().collect { config ->
                val prevActive = activeConfig.isActive
                activeConfig = config
                // Idempotent: unblock the ride-state collector's first handleRideState so it
                // never runs against KSafeConfig() defaults on a mid-ride (re)connect.
                configSeeded.complete(Unit)
                crashManager.updateConfig(effectiveCrashConfig(config))
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
                    // disableAsync() adds the LOG_END marker synchronously and dispatches
                    // the final buffer flush to Dispatchers.IO so this Main-thread collector
                    // is not blocked by eMMC writes (up to ~500 lines / tens of ms on Karoo).
                    calibLogger.disableAsync()
                    // Drain the file in size-capped chunks on IO so each `httpRequest`
                    // body fits in the Karoo SDK Binder transaction. Logging was just
                    // turned off, so we want to flush everything — no per-cycle cap.
                    // Fire-and-forget: a partial drain leaves the unsent tail on disk
                    // for the next manual send / the start-of-ride previous-file pick-up.
                    launch(Dispatchers.IO) {
                        sendCalibrationLogInChunks(captionPrefix = "Logging disabled")
                    }
                }
                // Re-evaluate monitoring based on current ride state.
                // Idle: applyIdleMonitoring already honors isActive.
                // Recording: enforce master switch transitions — stop everything if the
                // master was just turned OFF, restart everything if it was just turned ON.
                // Paused: same OFF→stop semantics as Recording, but DON'T restart on
                // OFF→ON until the next Recording resume (the rider is paused; nothing
                // to resume into). Without this, master-OFF during autopause leaves
                // crashManager / medicalDetector / wellnessMonitor / trackers running
                // and any in-flight emergency countdown ticking — contradicting the
                // rider's explicit "disable all safety alerts" intent.
                // An in-flight emergency countdown is left alone for Recording-active
                // transitions — cancel via SOS/cancel button.
                when (currentRideState) {
                    is RideState.Idle -> {
                        val effIdle = effectiveCrashConfig(config)
                        applyIdleMonitoring(effIdle)
                        crashEffectiveRunning = crashShouldBeRunningNow(effIdle)
                    }
                    is RideState.Recording -> applyMasterSwitchTransition(prevActive)
                    is RideState.Paused -> applyMasterSwitchTransitionPaused(prevActive)
                    else -> { /* null: not yet observed, leave as-is */ }
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
                EmergencyResume.DiscardAlerting -> {
                    // H9 — persisted ALERTING means a previous process was killed
                    // mid-dispatch. The in-flight alertJob died with the process;
                    // there's no retry to resume. Clear the phantom state so the
                    // next ride starts clean.
                    Timber.w("Discarding orphan ALERTING state — previous process was killed mid-dispatch")
                    configManager.saveEmergencyState(EmergencyState())
                }
            }
        }

        launch {
            // Load persisted fueling snapshot BEFORE we start observing ride state — the
            // first Recording event must see `pendingFuelingRestore` already populated so
            // the restore-vs-fresh-start decision in [handleRideState] picks the right
            // branch. A snapshot older than FUELING_RESTORE_MAX_AGE_MS is discarded as
            // stale (rider stopped the ride deliberately, or device sat unused).
            val persisted = configManager.loadFuelingState()
            val age = System.currentTimeMillis() - persisted.savedAtMs
            pendingFuelingRestore = if (persisted.savedAtMs > 0 &&
                age in 0..com.enderthor.kSafe.data.FUELING_RESTORE_MAX_AGE_MS) {
                Timber.i("FuelingState eligible for restore: age=${age / 1000}s, " +
                    "carb_burned=${persisted.carb.cumBurnedG.toInt()}g, " +
                    "hyd_target=${persisted.hyd.cumTargetMl.toInt()}ml")
                persisted
            } else {
                if (persisted.savedAtMs > 0) {
                    Timber.i("FuelingState too old to restore: age=${age / 1000}s (max ${com.enderthor.kSafe.data.FUELING_RESTORE_MAX_AGE_MS / 1000}s)")
                    configManager.clearFuelingState()
                }
                null
            }
        }

        launch {
            // Periodic persistence loop. Runs forever; the inner check gates writes on
            // "tracker actively integrating AND something changed since last write" so
            // we don't burn DataStore writes when no ride is in progress and we don't
            // re-encode the same JSON every 30 s when the rider is stopped at a long
            // traffic light. 30 s window means a worst-case extension crash loses at
            // most ~30 s of integrated target — well under one tracker tick.
            //
            // Two-tier throttle on the persistence loop:
            //  1. Strict skip when the tracker slices are bit-identical to the last
            //     persisted snapshot (rider stationary, no events).
            //  2. Deadband skip when the only difference between this cycle and the
            //     last persist is a small accumulator delta in cumBurnedG / cumTargetMl
            //     (< PERSIST_*_DEADBAND_*). Pre-v18.2 the loop wrote ~480 times per 5 h
            //     ride because the Float integrators advance by ~0.21 g and ~3 ml per
            //     30 s cycle while moving — strict equality always failed. The deadband
            //     widens that to 5 g (or 60 ml) before considering it "worth writing",
            //     which drops the write rate ~80% in steady-state. (Note: pre-B5 fix
            //     the deadband was a no-op while moving because `activeIntegrationMs`
            //     advanced every tick and broke the `==` check below; fixed by also
            //     normalising that field in the `.copy()` call.)
            //
            // Worst-case loss on process kill is bounded by the deadband: ≤ 5 g of carb
            // burn and ≤ 60 ml of hydration target, plus the 30 s loop interval. Well
            // within the 10-15 % accuracy band of the burn estimator (Keytel / Swain) and
            // the ±20 % band of the SweatEstimator at the 750 ml/h default — rider-
            // invisible noise. Alerts and rider logs trigger outside the deadband
            // immediately (cumLoggedG / lastLogMs / lastRealLogMs / lastTimeAlertFireMs /
            // lastDeficitAlertFireMs all break the deadband path because they're not the
            // deadband-protected fields).
            var lastPersistedCarb: com.enderthor.kSafe.data.CarbFuelingState? = null
            var lastPersistedHyd:  com.enderthor.kSafe.data.HydFuelingState?  = null
            while (true) {
                // Idle backoff: nothing to persist outside a ride — poll coarsely until
                // Recording, then persist at the active 30 s cadence. Only the FIRST persist
                // after a ride starts is delayed (by ≤ one idle interval); the accumulation
                // in that window is small and within the deadband loss bound documented above.
                if (currentRideState !is RideState.Recording) {
                    kotlinx.coroutines.delay(BACKGROUND_IDLE_POLL_MS)
                    continue
                }
                kotlinx.coroutines.delay(FUELING_PERSIST_INTERVAL_MS)
                if (currentRideState !is RideState.Recording) continue
                if (!this@KSafeExtension::carbsTracker.isInitialized) continue
                val carbState = carbsTracker.getPersistableState()
                val hydState  = hydrationTracker.getPersistableState()
                // Skip the write if both trackers are at zero — no accumulation worth
                // persisting yet (e.g. rider just pressed Start, hasn't moved).
                if (carbState.cumBurnedG <= 0f && carbState.cumLoggedG == 0 &&
                    hydState.cumTargetMl <= 0f && hydState.cumLoggedMl == 0) continue
                // Skip if neither tracker has changed since the last successful write.
                // Note: we deliberately compare the trackers' state slices, NOT the
                // wrapper FuelingState, because `savedAtMs` would otherwise force a
                // write on every cycle.
                if (carbState == lastPersistedCarb && hydState == lastPersistedHyd) continue
                // Deadband: if the ONLY thing that changed is a small accumulator
                // delta, defer the write. Compare a normalised copy where the
                // protected field(s) are forced equal to the prior value — if that
                // copy matches the prior snapshot exactly, the real diff is the
                // accumulator alone, and it's within the deadband.
                //
                // B5 fix (post-v18.2 audit): `CarbFuelingState.activeIntegrationMs`
                // also advances every tick (one tick worth of ms when moving) and
                // is NOT itself deadband-protected. Without normalising it, the
                // data-class equality below would ALWAYS fail while moving and the
                // deadband would silently never fire — exactly the case it's meant
                // to optimise. Force it equal to the prior value alongside
                // `cumBurnedG` so the comparison sees only the rider-visible /
                // event-driven fields (cumLoggedG, lastTimeAlertFireMs, etc.).
                // Worst case on process kill is now bounded by the deadband + the
                // 30 s cycle: ≤ 5 g of burn AND ≤ 30 s of integration-time loss.
                val prevCarb = lastPersistedCarb
                val prevHyd  = lastPersistedHyd
                if (prevCarb != null && prevHyd != null) {
                    val burnDelta   = carbState.cumBurnedG - prevCarb.cumBurnedG
                    val targetDelta = hydState.cumTargetMl - prevHyd.cumTargetMl
                    val carbOtherUnchanged = carbState.copy(
                        cumBurnedG = prevCarb.cumBurnedG,
                        activeIntegrationMs = prevCarb.activeIntegrationMs,
                    ) == prevCarb
                    val hydOtherUnchanged  = hydState.copy(cumTargetMl = prevHyd.cumTargetMl) == prevHyd
                    val withinDeadband = carbOtherUnchanged && hydOtherUnchanged &&
                        burnDelta   in 0f..PERSIST_CARB_BURN_DEADBAND_G &&
                        targetDelta in 0f..PERSIST_HYD_TARGET_DEADBAND_ML
                    if (withinDeadband) continue
                }
                configManager.saveFuelingState(
                    com.enderthor.kSafe.data.FuelingState(
                        carb = carbState,
                        hyd = hydState,
                        savedAtMs = System.currentTimeMillis(),
                    )
                )
                lastPersistedCarb = carbState
                lastPersistedHyd  = hydState
            }
        }

        launch {
            // Calibration log periodic auto-send. Fires every 20 min while a ride is
            // Recording AND logging is enabled. Drains the on-disk log in chunks
            // capped at CALIBRATION_MAX_CHUNK_BYTES so each `httpRequest` body fits
            // in the Karoo SDK Binder transaction (see [CALIBRATION_MAX_CHUNK_BYTES]
            // — the 2026-05-25 ride's 262 KB file failed permanently because each
            // retry grew further past the ~80 KB practical limit). On a failure the
            // unsent tail stays on disk and the next window retries from there.
            // Per-cycle chunk cap (CALIBRATION_PERIODIC_MAX_CHUNKS_PER_CYCLE) bounds
            // how long the loop spends catching up after a backlog so the coroutine
            // doesn't stall N sequential HTTP round-trips. Idle/Paused are skipped
            // because there's no fresh data accumulating that we'd lose by waiting
            // for the end-of-ride upload.
            //
            // [periodicSendJob] tracks the in-flight drain so a 20-min cycle that
            // finds the previous drain still running (slow LTE, transient HTTP
            // errors stretching one chunk past the window) skips this cycle rather
            // than launching a second parallel drain. Without this, two drains
            // could read the same first chunk from disk before the first finishes
            // truncating, leading to a duplicate upload. `fileLock` already
            // prevents corruption — this guard avoids the duplicate.
            while (true) {
                kotlinx.coroutines.delay(CALIBRATION_PERIODIC_SEND_INTERVAL_MS)
                if (currentRideState !is RideState.Recording) continue
                if (!this@KSafeExtension::calibLogger.isInitialized) continue
                if (!calibLogger.isEnabled) continue
                if (periodicSendJob?.isActive == true) {
                    Timber.i("Calibration periodic send: previous drain still active, skipping this cycle")
                    continue
                }
                periodicSendJob = launch(Dispatchers.IO) {
                    val result = sendCalibrationLogInChunks(
                        captionPrefix = "Periodic",
                        maxChunks = CALIBRATION_PERIODIC_MAX_CHUNKS_PER_CYCLE,
                    )
                    when {
                        result.anySent && result.fullyDrained ->
                            Timber.d("Calibration periodic send: ${result.chunksSent} chunk(s), " +
                                    "${result.totalLinesSent} lines, ${result.totalBytesSent / 1024} KB")
                        result.anySent && result.hasMore ->
                            Timber.i("Calibration periodic send: ${result.chunksSent} chunk(s) sent, " +
                                    "more pending for next window" +
                                    (result.lastError?.let { " (last: $it)" } ?: ""))
                        result.lastError != null ->
                            Timber.w("Calibration periodic send failed without progress: ${result.lastError}")
                        // result.anySent == false && lastError == null → file was already empty,
                        // nothing to log.
                    }
                }
            }
        }

        launch {
            // Calibration logger health-check. If the rider has logging enabled and the
            // flush coroutine has stopped writing for HEALTH_STALE_THRESHOLD_MS, restart
            // the flush job in place (no buffer / file wipe, just relaunch the loop).
            // Covers the "logger seems on but isn't writing — disable+enable fixes it"
            // pattern that's been reported anecdotally.
            while (true) {
                // Idle backoff: when logging is disabled (the default for ~all riders) there
                // is nothing to health-check — poll coarsely instead of waking every 60 s for
                // the whole multi-day service lifetime. Resumes the 60 s cadence within one
                // idle interval of the rider enabling logging (a deliberate Settings action).
                if (!this@KSafeExtension::calibLogger.isInitialized || !calibLogger.isEnabled) {
                    kotlinx.coroutines.delay(BACKGROUND_IDLE_POLL_MS)
                    continue
                }
                kotlinx.coroutines.delay(CALIBRATION_HEALTH_CHECK_INTERVAL_MS)
                if (!this@KSafeExtension::calibLogger.isInitialized || !calibLogger.isEnabled) continue
                if (!calibLogger.isHealthy()) {
                    Timber.w("Calibration logger unhealthy — restarting flush job")
                    calibLogger.restartFlushJob()
                }
            }
        }

        launch {
            // Observe ride state. Wait until activeConfig has been seeded from DataStore
            // (configSeeded) before handling the first transition: on a mid-ride (re)connect
            // streamRide() can emit Recording before the config collector's first emission,
            // and handleRideState would otherwise run against KSafeConfig() defaults
            // (isActive / crash / medical all ON) — briefly starting detectors the rider had
            // disabled. The deferred completes on the first DataStore emission (~ms).
            //
            // BOUNDED await — this is an OPTIMISATION (skip the ~100 ms default-config window),
            // so it must NEVER make things worse than not awaiting at all. configSeeded is
            // completed by the config collector's first emission, which depends on the shared
            // DataStore flow emitting; if that upstream dies before its first value (DataStore
            // IOException at cold boot, file corruption), configSeeded would never complete and
            // ride-state handling — hence crash detection — would hang for the whole process.
            // Cap the wait and fall back to the original immediate behaviour on timeout.
            if (withTimeoutOrNull(CONFIG_SEED_TIMEOUT_MS) { configSeeded.await() } == null) {
                Timber.w("configSeeded not ready after ${CONFIG_SEED_TIMEOUT_MS}ms — proceeding so ride-state/crash handling is never blocked by a stalled config load")
            }
            karooSystem.streamRide()
                .distinctUntilChanged()
                .collect { state ->
                    handleRideState(state)
                }
        }

        // The SPEED / CADENCE / ELEVATION_GRADE / ride-profile collectors live
        // in [startCrashSensorCollectors] now so they can be cancelled when the
        // Karoo is sitting idle on the dock without `crashMonitorOutsideRide`
        // enabled. The first launch happens here so an initial Idle state with
        // outside-ride monitoring active already has streams flowing; subsequent
        // ride-state transitions and master-switch flips re-evaluate the gate
        // via [applyIdleMonitoring] / [handleRideState] / [applyMasterSwitchTransition].
        startCrashSensorCollectors()

        // The POWER, HR, TEMPERATURE, Headwind temp/humidity and UserProfile streams
        // are gated by RideState — their consumers (fueling trackers, WellnessMonitor,
        // MedicalEpisodeDetector) only do real work during a recording, and an idle
        // device sitting on the dock has no reason to wake the collector coroutines
        // ~once per second per stream. Started via [startRecordingCollectors] from
        // [handleRideState] on the Recording transition and cancelled on Idle.
    }

    /**
     * Launches the Recording-only stream collectors (POWER, HR, TEMPERATURE,
     * Headwind temp+humidity, UserProfile). Idempotent — extra calls while a job
     * is already active are no-ops. Cancellation is via [stopRecordingCollectors]
     * on the Idle transition, which lets the upstream SDK subscriptions close
     * cleanly so they stop consuming IPC bandwidth while the device is on the dock.
     */
    private fun startRecordingCollectors() {
        if (recordingCollectorsJob?.isActive == true) return
        // K8 — CoroutineExceptionHandler so a failing inner collector under
        // supervisorScope produces a Timber.e line instead of dying into the
        // default JVM uncaught-exception handler (logcat-only, no Timber tree).
        // Without this, J7's supervisorScope correctly isolates sibling launches
        // but the cause of the failure is invisible to anyone reading the
        // calibration logs / production diagnostics.
        val collectorHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
            Timber.e(throwable, "Recording collector failed (sibling collectors continue under supervisorScope)")
        }
        recordingCollectorsJob = launch {
            // J7 — supervisorScope so a thrown exception from ANY inner collector
            // (a corrupted Headwind payload, an SDK rebind mid-collect, an
            // unexpected provider-side cast failure) doesn't cancel the parent
            // and tear down the OTHER sibling collectors. Without supervisorScope,
            // a one-off bad emission on temperature could silently kill HR /
            // power / user-profile collection for the rest of the ride,
            // disabling FLATLINE / COLLAPSE / WELLNESS / carbs / hydration
            // detection. supervisorScope still propagates external cancellation
            // (recordingCollectorsJob.cancel() on master-switch OFF / onDestroy)
            // down to its children correctly.
            kotlinx.coroutines.withContext(collectorHandler) {
            kotlinx.coroutines.supervisorScope {
            // Power meter stream — optional. carbsTracker uses it for the zone multiplier; the
            // wellnessMonitor's cardiac-decoupling tier uses it for the HR/W ratio; the
            // hydrationTracker uses it as the preferred metabolic-rate input for the sweat
            // estimator. If absent, the carb tracker falls back to HR zones, decoupling
            // auto-skips, and hydration falls back to HR-derived metabolic rate.
            launch {
                karooSystem.streamDataFlow(io.hammerhead.karooext.models.DataType.Type.POWER)
                    .collect { streamState ->
                        val w = streamState.powerW() ?: return@collect
                        carbsTracker.updatePower(w)
                        wellnessMonitor.updatePower(w)
                        hydrationTracker.updatePower(w)
                        // H3 fix — fan out to the medical detector's FLATLINE cross-check.
                        // Optional sensor: most rider setups have HR but not power, so the
                        // detector treats absence-of-power as "cross-check not plumbed" and
                        // falls through to the original FLATLINE path.
                        medicalDetector.updatePower(w)
                    }
            }

            // Rider profile (weight, max HR, FTP, HR zones, power zones). Read continuously
            // while recording — if the rider edits their profile in the Karoo settings
            // mid-ride, the new values propagate immediately. The carb tracker uses it for
            // HR/power zone multiplier, the wellness monitor for the optional % of max HR
            // threshold mode, and the hydration tracker for the body-mass scaling factor
            // in the sweat estimator.
            launch {
                karooSystem.streamUserProfile()
                    .collect { profile ->
                        carbsTracker.updateUserProfile(profile)
                        wellnessMonitor.updateUserProfile(profile)
                        hydrationTracker.updateUserProfile(profile)
                    }
            }

            // HR stream (ANT+/BLE). Optional: silent when no sensor is paired.
            launch {
                karooSystem.streamDataFlow(io.hammerhead.karooext.models.DataType.Type.HEART_RATE)
                    .collect { streamState ->
                        val hr = streamState.heartRateBpm() ?: return@collect
                        medicalDetector.updateHr(hr)
                        wellnessMonitor.updateHr(hr)
                        carbsTracker.updateHr(hr)
                        hydrationTracker.updateHr(hr)
                    }
            }

            // Onboard Karoo temperature sensor. Device-heat biased (typically reads
            // +3–8 °C above ambient when in direct sun / after warm-up), but always
            // available — used as fallback when Headwind isn't publishing.
            launch {
                karooSystem.streamDataFlow(io.hammerhead.karooext.models.DataType.Type.TEMPERATURE)
                    .collect { streamState ->
                        val s = streamState as? io.hammerhead.karooext.models.StreamState.Streaming
                            ?: return@collect
                        val tempC = s.dataPoint.singleValue ?: return@collect
                        if (!hasHeadwindTemp) hydrationTracker.updateAmbientTemp(tempC)
                    }
            }

            // ── Headwind extension streams (TYPE_EXT::karoo-headwind::xxx) ────
            // If the karoo-headwind extension is installed on the rider's Karoo, prefer
            // its weather data (real meteo API) over the onboard sensor. The streams
            // below are silent on devices without Headwind — no error, just no emissions.
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
            }  // end supervisorScope (J7)
            }  // end withContext(collectorHandler) (K8)
        }
    }

    private fun stopRecordingCollectors() {
        recordingCollectorsJob?.cancel()
        recordingCollectorsJob = null
        // Reset Headwind detection — if the rider's setup changes between rides
        // (uninstalls Headwind, for instance) we want the onboard temperature
        // fallback to engage cleanly on the next ride.
        hasHeadwindTemp = false
    }

    /**
     * Launches the SPEED / CADENCE / ELEVATION_GRADE / ride-profile collectors
     * that feed crash detection, the medical detector and the fueling trackers.
     * Lifecycle is "alive whenever a downstream consumer is doing real work":
     *
     *  - Recording / Paused: trackers + crash + medical all want updates.
     *  - Idle with `crashMonitorOutsideRide` (or `crashMonitorOutsideRideAnySpeed`):
     *    crash detection keeps running so it needs SPEED / CADENCE / GRADE /
     *    ride-profile too.
     *  - Idle without the outside-ride toggle: every consumer is stopped, so
     *    the upstream SDK subscriptions are wasted — cancel them.
     *
     * Idempotent — calls while the job is already active are no-ops.
     */
    private fun startCrashSensorCollectors() {
        if (crashSensorCollectorsJob?.isActive == true) return
        // B10 — supervisorScope + CoroutineExceptionHandler mirroring
        // [startRecordingCollectors]. Without these, a single bad emission on any
        // of the four inner streams (e.g. a Karoo OTA changes the ride-profile JSON
        // shape and the SDK decoder throws) propagates up the parent `launch` and
        // cancels the three sibling collectors. Net effect: crash detection silently
        // loses SPEED / CADENCE / GRADE for the rest of the ride — the rider keeps
        // riding without protection and the only trace is a JVM uncaught-exception
        // line in logcat (no Timber tree, no calibration-log row). Asymmetric vs
        // `startRecordingCollectors` which already had this guard; both arms of the
        // ride-time sensor pipeline must survive a one-off bad emission identically.
        val crashCollectorHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
            Timber.e(throwable, "Crash-sensor collector failed (siblings continue under supervisorScope)")
        }
        crashSensorCollectorsJob = launch {
            kotlinx.coroutines.withContext(crashCollectorHandler) {
            kotlinx.coroutines.supervisorScope {
            launch {
                // Stream speed to crash detector + fueling trackers. The fueling trackers
                // gate integration on speed (no accumulation when stationary), so they need
                // every emission too — fan out here rather than duplicating the stream.
                //
                // NOTE: do NOT apply `distinctUntilChanged` upstream. The downstream
                // GPS-stale detection in CrashDetectionManager + SpeedDropMonitor + fueling
                // trackers relies on the *absence* of value changes to detect that the SDK
                // is repeating its last known value (the canonical GPS-lost signature).
                // Filtering identical emissions upstream would defeat that — the consumers
                // would never see the repeats and the gpsStale signal would never propagate.
                karooSystem.streamDataFlow(io.hammerhead.karooext.models.DataType.Type.SPEED)
                    .collect { streamState ->
                        val speedKmh = streamState.speedKmh() ?: return@collect
                        crashManager.updateSpeed(speedKmh)
                        medicalDetector.updateSpeed(speedKmh)
                        if (this@KSafeExtension::carbsTracker.isInitialized) carbsTracker.updateSpeed(speedKmh)
                        if (this@KSafeExtension::hydrationTracker.isInitialized) hydrationTracker.updateSpeed(speedKmh)
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
                        // H3 fix — fan out to the medical detector's FLATLINE cross-check.
                        // Optional sensor: if no cadence is paired the flow emits nothing and
                        // the detector falls through to the original FLATLINE path. See
                        // MedicalEpisodeDetector.updateCadence for graceful-degradation notes.
                        medicalDetector.updateCadence(cadenceRpm)
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
                    // Karoo re-emits the current profile on every SDK reconnect (≈daily +
                    // every lifecycle bounce). Dedup so an unchanged profile doesn't trigger a
                    // redundant DataStore.edit + reapplyEffectiveCrash on Main.
                    .distinctUntilChanged()
                    .collect { profile ->
                        crashManager.updateRideProfile(profile.routingPreference)
                        activeProfileId = profile.id
                        activeProfileIdFlow.value = profile.id   // reactive mirror for the Settings UI
                        // Atomic read-modify-write (see ConfigurationManager.updateConfig):
                        // profile-learning runs from the service process and must not clobber a
                        // concurrent UI settings save. updateConfig skips the write when nothing
                        // was learned, so this stays a no-op on every unchanged profile emission.
                        // Guarded: a DataStore write failure must NOT kill this collector, or
                        // profile switches would stop updating activeProfileId / crash config for
                        // the rest of the ride.
                        try {
                            configManager.updateConfig { latest ->
                                latest.copy(crashProfileSettings = learnProfile(latest.crashProfileSettings, profile.id, profile.name))
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.w(e, "learnProfile persist failed for profile ${profile.id} — continuing")
                        }
                        // Guarded too: a crashManager start/stop/updateConfig fault while re-applying
                        // must not kill this collector, or every LATER profile switch would stop
                        // re-applying crash config for the rest of the ride.
                        try {
                            reapplyEffectiveCrash()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.w(e, "reapplyEffectiveCrash failed for profile ${profile.id} — continuing")
                        }
                    }
            }
            }  // end supervisorScope (B10)
            }  // end withContext(crashCollectorHandler) (B10)
        }
    }

    private fun stopCrashSensorCollectors() {
        crashSensorCollectorsJob?.cancel()
        crashSensorCollectorsJob = null
    }

    private fun handleRideState(state: RideState) {
        currentRideState = state
        Timber.d("Ride state: $state")
        when (state) {
            is RideState.Recording -> {
                if (activeConfig.isActive) {
                    // Spin up the Recording-only sensor streams (POWER, HR, TEMPERATURE,
                    // Headwind, UserProfile). Idempotent — a Paused→Recording resume hits
                    // this same branch and the call is a no-op if collectors are still alive.
                    startRecordingCollectors()
                    // Ensure SPEED / CADENCE / GRADE / ride-profile collectors are alive.
                    // Idempotent — already running if the rider had `crashMonitorOutsideRide`
                    // enabled while Idle, or if we're transitioning from Paused.
                    startCrashSensorCollectors()
                    // Distinguish the very first Recording event of a session (where the
                    // accumulating trackers must do a clean start() and reset cum* state)
                    // from a Paused→Recording resume (where resume() preserves the rider's
                    // logged carbs/ml and the cumulative target so a café stop doesn't
                    // wipe an hour of fueling work). Crash + medical have no rider-visible
                    // accumulator, so .start() is safe in either case.
                    val isResumeFromPause = rideStartNotificationSent
                    val eff = effectiveCrashConfig()
                    if (isResumeFromPause) crashManager.resume(eff)
                    else crashManager.start(eff)
                    crashEffectiveRunning = crashShouldBeRunningNow(eff)
                    medicalDetector.start(activeConfig)
                    if (isResumeFromPause) {
                        wellnessMonitor.resume(activeConfig)
                        carbsTracker.resume(activeConfig)
                        hydrationTracker.resume(activeConfig)
                    } else {
                        wellnessMonitor.start(activeConfig)
                        // Consume the pending fueling restore (if any) on the FIRST Recording
                        // event after extension boot. `null` falls through to the fresh-start
                        // path inside each tracker. We zero out the field after this branch
                        // so a future Recording (new ride) does NOT re-apply the same totals.
                        val restore = pendingFuelingRestore
                        pendingFuelingRestore = null
                        carbsTracker.start(activeConfig, restore?.carb)
                        hydrationTracker.start(activeConfig, restore?.hyd)
                    }
                    // Same first-start vs resume distinction as the trackers above:
                    // on a Paused→Recording resume the check-in must continue with its
                    // REMAINING interval, not restart from zero. Restarting on every
                    // autopause (traffic light, café) meant CHECKIN_EXPIRED could never
                    // fire on a stop-start ride — the dead-man's-switch was silently dead.
                    if (isResumeFromPause) emergencyManager.resumeCheckinTimer(activeConfig)
                    else emergencyManager.startCheckinTimer(activeConfig)
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
                // BUT reset the speed-drop accumulator — speed is 0 on any pause (manual
                // or automatic), so without this reset the speed-drop watchdog would fire
                // spuriously during a long café stop or any other stationary pause.
                crashManager.resetSpeedDropOnPause()
                // Pass the SDK's auto flag: a MANUAL pause wipes any in-flight
                // IMPACT/SILENCE state (the rider deliberately stopped — conscious and
                // fine); an AUTOMATIC pause does NOT — the bike stopping on its own is
                // exactly a crash signature, so the in-flight detection must survive
                // and confirm during the pause.
                crashManager.onPause(state.auto)
                // Suspend (don't reset) the check-in timer and cancel any active
                // check-in countdown. pauseCheckinTimer preserves the elapsed interval
                // so the Recording resume above continues from where it left off
                // instead of re-arming a fresh full interval on every autopause.
                emergencyManager.pauseCheckinTimer()
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
                // Ride ended cleanly — drop the persisted fueling snapshot so the next ride
                // starts from zero. Fire-and-forget on IO; if the write loses to a process
                // kill the next boot's stale-age check (FUELING_RESTORE_MAX_AGE_MS) catches it.
                launch(Dispatchers.IO) { configManager.clearFuelingState() }
                // Cancel the Recording-only sensor streams now that no consumer needs
                // them. The SPEED / CADENCE / GRADE / RideProfile collectors keep
                // running because crash detection may continue outside the ride
                // (crashMonitorOutsideRide).
                stopRecordingCollectors()
                val eff = effectiveCrashConfig()
                applyIdleMonitoring(eff)
                crashEffectiveRunning = crashShouldBeRunningNow(eff)
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
                    // Drain in size-capped chunks on IO so each body fits the SDK
                    // Binder transaction. Ride just ended — drain everything (no
                    // per-cycle cap). A partial drain leaves the tail on disk for
                    // the next manual send.
                    launch(Dispatchers.IO) {
                        sendCalibrationLogInChunks(captionPrefix = "Ride end")
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
            crashEffectiveRunning = false
            medicalDetector.stop()
            wellnessMonitor.stop()
            carbsTracker.stop()
            hydrationTracker.stop()
            // No consumer for POWER/HR/TEMPERATURE while the master switch is off,
            // so cancel those collectors too. They restart on the ON branch below.
            stopRecordingCollectors()
            // SPEED / CADENCE / GRADE / ride-profile have no consumers either while
            // master is off — crashManager.stop() above already drops the main one.
            // Cancel until master flips back ON.
            stopCrashSensorCollectors()
            // stopAll() (NOT stopCheckinTimer) — a crash/medical countdown actively
            // ticking down when the rider flips the master switch OFF must be aborted
            // along with everything else. The previous stopCheckinTimer-only call
            // cancelled the check-in jobs but left countdownJob ticking, so the
            // sendAlerts dispatch fired despite the rider's explicit "disable all
            // safety alerts" intent. stopAll() also cancels any in-flight alertJob.
            emergencyManager.stopAll()
        } else {
            Timber.d("Master switch ON mid-ride — resuming monitoring (preserving session totals)")
            // Re-arm the Recording-only collectors (idempotent if they were never
            // cancelled, e.g. brief flicker before the OFF branch reached this).
            startRecordingCollectors()
            // Re-arm SPEED / CADENCE / GRADE / ride-profile too — crashManager
            // and the trackers about to resume all need them.
            startCrashSensorCollectors()
            // Master-switch ON inside an already-Recording ride is semantically
            // a resume — preserve the baseline.
            val eff = effectiveCrashConfig()
            if (currentRideState is RideState.Recording) crashManager.resume(eff)
            else crashManager.start(eff)
            crashEffectiveRunning = crashShouldBeRunningNow(eff)
            medicalDetector.start(activeConfig)
            wellnessMonitor.resume(activeConfig)
            carbsTracker.resume(activeConfig)
            hydrationTracker.resume(activeConfig)
            emergencyManager.startCheckinTimer(activeConfig)
        }
    }

    /**
     * Same OFF semantics as [applyMasterSwitchTransition] but for Paused state:
     * when the rider toggles the master switch OFF during an autopause (or any
     * pause), every monitoring subsystem must stop so a stuck-in-Paused rider
     * gets no further alerts. The ON branch is deliberately a no-op — there is
     * no live ride to resume into; the next Recording transition will re-arm
     * everything through `handleRideState.Recording`.
     *
     * Without this branch, master-OFF during autopause was a silent contract
     * violation: crash + medical + wellness + trackers kept running, and any
     * in-flight emergency countdown continued ticking despite the rider's
     * explicit "disable all safety alerts" intent.
     */
    private fun applyMasterSwitchTransitionPaused(prevActive: Boolean) {
        val nowActive = activeConfig.isActive
        if (prevActive == nowActive) return
        if (prevActive && !nowActive) {
            Timber.d("Master switch OFF during Paused — stopping all monitoring")
            crashManager.stop()
            crashEffectiveRunning = false
            medicalDetector.stop()
            wellnessMonitor.stop()
            carbsTracker.stop()
            hydrationTracker.stop()
            stopRecordingCollectors()
            stopCrashSensorCollectors()
            // Mirrors the Recording path: stopAll() (NOT stopCheckinTimer) so a
            // crash/medical countdown actively ticking inside the autopause is
            // aborted along with everything else.
            emergencyManager.stopAll()
        } else {
            // OFF→ON during Paused: leave as-is. The rider has no live monitoring
            // session to re-attach to (`crashManager.resume` etc. would observe
            // stale state). The next Recording transition takes the fresh-start
            // path via `handleRideState.Recording` once the rider unpauses.
            Timber.d("Master switch ON during Paused — deferring re-arm to next Recording")
        }
    }

    /** Global config (activeConfig) merged with the active profile's crash override. */
    private fun effectiveCrashConfig(base: KSafeConfig = activeConfig): KSafeConfig =
        resolveEffectiveCrashConfig(base, activeProfileId)

    /** Whether crash detection should be running right now under [eff] and the current
     *  ride state. Single source of truth for the crashEffectiveRunning bookkeeping. */
    private fun crashShouldBeRunningNow(eff: KSafeConfig): Boolean =
        eff.isActive && eff.crashDetectionEnabled && when (currentRideState) {
            is RideState.Recording, is RideState.Paused -> true
            else -> eff.crashMonitorOutsideRide || eff.crashMonitorOutsideRideAnySpeed
        }

    /**
     * Single owner of crash start/stop/threshold reconciliation under the effective
     * (per-profile) config. Safe to call repeatedly and from any context — touches ONLY
     * the crash manager, never the trackers/medical/check-in.
     */
    private fun reapplyEffectiveCrash() {
        val eff = effectiveCrashConfig()
        // Mirror applyIdleMonitoring's "any speed" override: when monitoring crashes
        // outside a ride at any speed, the speed gate is forced to 0. Without this, a
        // profile switch while Idle (which reaches the updateConfig-only path) would
        // restore the configured minimum speed and silently defeat anySpeed monitoring.
        val applied = if (currentRideState is RideState.Idle && eff.isActive && eff.crashMonitorOutsideRideAnySpeed)
                          eff.copy(minSpeedForCrashKmh = 0)
                      else eff
        crashManager.updateConfig(applied)   // live threshold swap; never starts/stops
        val shouldRun = crashShouldBeRunningNow(eff)
        if (shouldRun && !crashEffectiveRunning) {
            when (currentRideState) {
                is RideState.Recording -> crashManager.start(eff)
                is RideState.Paused    -> crashManager.resume(eff)
                else -> applyIdleMonitoring(eff)   // handles minSpeed=0 outside-ride variant
            }
            crashEffectiveRunning = shouldRun
        } else if (!shouldRun && crashEffectiveRunning) {
            crashManager.stop()
            crashEffectiveRunning = false
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
                    // crashManager needs SPEED / CADENCE / GRADE / ride-profile while
                    // it's active. Idempotent if the collectors were already running.
                    startCrashSensorCollectors()
                    Timber.d("Idle crash monitoring STARTED (any speed)")
                }
                config.crashMonitorOutsideRide -> {
                    crashManager.start(config)
                    startCrashSensorCollectors()
                    Timber.d("Idle crash monitoring STARTED (min speed=${config.minSpeedForCrashKmh} km/h)")
                }
                else -> {
                    crashManager.stop()
                    // No consumer needs SPEED / CADENCE / GRADE / ride-profile while
                    // the device sits idle — cancel the IPC subscriptions until the
                    // next Recording transition or outside-ride toggle re-enables them.
                    stopCrashSensorCollectors()
                }
            }
        } else {
            crashManager.stop()
            stopCrashSensorCollectors()
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
                launch {
                    // H6 — same SENDING-stuck guard as handleCustomMessageTap.
                    try {
                        sendCustomMessage()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.e(e, "BonusAction sendCustomMessage threw — reverting slot 1 to ERROR")
                        CustomMessageState.update(1, CustomMessageState.ERROR)
                        scheduleCustomRevert(1, 4_000L)
                    }
                }
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

    /**
     * Sends a ride-start notification when the feature is enabled. The Karoo Live key
     * is optional — if present, `{livetrack}` is substituted with the live-tracking
     * URL; if absent, the placeholder is stripped so the rider doesn't receive a
     * literal `{livetrack}` token. Mirrors [sendRideEndNotification]: a blank
     * resulting message is skipped silently. Token substitution is shared with
     * the emergency path via [EmergencyManager.substituteTokens] so a rider who
     * embeds `{location}` in the start message gets the same Maps link the
     * emergency contacts would receive — no more literal `{location}` strings.
     */
    private fun sendRideStartNotification() {
        val config = activeConfig
        if (!config.karooLiveEnabled) return

        Timber.d("KSafe: sending ride start notification")
        launch {
            try {
                val message = emergencyManager.substituteTokens(
                    template = config.karooLiveStartMessage,
                    config = config,
                )
                if (message.isBlank()) return@launch
                sender.sendInfo(message, config.activeProvider)
            } catch (e: Exception) {
                Timber.e(e, "KSafe: error sending ride start notification")
            }
        }
    }

    /**
     * Sends the ride-end notification if the feature is enabled. Routes through
     * [EmergencyManager.substituteTokens] so `{location}`, `{livetrack}` and the
     * other common tokens get substituted (previously they were sent literally).
     */
    private fun sendRideEndNotification() {
        val config = activeConfig
        if (!config.karooLiveEndEnabled) return
        if (config.karooLiveEndMessage.isBlank()) return

        Timber.d("KSafe: sending ride end notification")
        launch {
            try {
                val message = emergencyManager.substituteTokens(
                    template = config.karooLiveEndMessage,
                    config = config,
                )
                if (message.isBlank()) return@launch
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
        // InRideAlert color contract: see res/values/colors.xml. backgroundColor and
        // textColor are @ColorRes — packed ARGB ints crash the ride app.
        val (titleRes, bgColorRes) = when (advice.level) {
            ReadinessLevel.RECOVERED -> return   // never fires — defensive
            ReadinessLevel.CAUTION -> R.string.readiness_alert_caution_title to R.color.alert_orange_light
            ReadinessLevel.TAKE_IT_EASY -> R.string.readiness_alert_take_easy_title to R.color.alert_red
        }
        // B26 — render structured ReadinessReason values to localised strings here,
        // at the UI boundary. The decideReadiness() function returns sealed-class
        // payloads only, keeping the pure decision layer free of presentation
        // concerns and localisation-ready.
        val detail = advice.reasons.joinToString(" • ") { reason ->
            when (reason) {
                is com.enderthor.kSafe.extension.util.ReadinessReason.CardiacDrift ->
                    getString(R.string.readiness_reason_cardiac_drift, reason.percent)
                is com.enderthor.kSafe.extension.util.ReadinessReason.WellnessAlerts ->
                    getString(R.string.readiness_reason_wellness_alerts, reason.count)
                is com.enderthor.kSafe.extension.util.ReadinessReason.MinutesAboveCritical ->
                    getString(R.string.readiness_reason_minutes_critical, reason.minutes)
                is com.enderthor.kSafe.extension.util.ReadinessReason.RidesIn72h ->
                    getString(R.string.readiness_reason_rides_72h, reason.count)
            }
        }
        karooSystem.dispatch(InRideAlert(
            id = "ksafe-readiness-${System.currentTimeMillis()}",
            icon = R.drawable.ic_ksafe,
            title = getString(titleRes),
            detail = detail,
            autoDismissMs = 15_000L,
            backgroundColor = bgColorRes,
            textColor = R.color.alert_text_white,
        ))
    }

    /**
     * Sends the custom message for the given [slot] (1, 2 or 3) immediately (no countdown).
     * Triggered by data field tap, BonusAction, or "Send" button in Settings.
     * Updates CustomMessageState for that slot so the data field reflects the result.
     * Returns a human-readable result string for display in the UI.
     */
    suspend fun sendCustomMessage(slot: Int = 1): String {
        // Double-tap guard: bail if a previous tap on the same slot is still in flight.
        // A Karoo data-field tap re-fires within ~100–500 ms before the field re-renders
        // to clickable=false, queueing a second FieldTapReceiver broadcast that would
        // launch its own coroutine and send the message twice. The per-slot state flow
        // is updated synchronously to SENDING below before any suspend, so reading it
        // here at function entry reliably catches the queued tap.
        // NOTE: no facade test harness for KSafeExtension exists today; if one is added,
        // add a "send during SENDING is ignored" test against this guard.
        val inFlight = CustomMessageState.flowForSlot(slot).value
        if (inFlight == CustomMessageState.SENDING || inFlight == CustomMessageState.SENT) {
            Timber.d("Custom message slot=$slot tap ignored — already in $inFlight")
            return "Already sending — please wait."
        }
        val config = activeConfig
        if (!config.isActive) {
            CustomMessageState.update(slot, CustomMessageState.ERROR)
            scheduleCustomRevert(slot, 4_000L)
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
            scheduleCustomRevert(slot, 3_000L)
            return "Custom message $slot is disabled — enable it in Settings first."
        }
        if (message.isBlank()) {
            CustomMessageState.update(slot, CustomMessageState.ERROR)
            scheduleCustomRevert(slot, 3_000L)
            return "No text configured for message $slot."
        }
        Timber.d("Sending custom message slot=$slot via ${config.activeProvider}")
        CustomMessageState.update(slot, CustomMessageState.SENDING)
        // Resolve {location}/{livetrack}/{reason} before send — same substitution surface
        // the emergency path uses, so a custom message of "I'm at {location}" actually
        // sends the Maps link instead of the literal token text.
        val resolved = emergencyManager.substituteTokens(template = message, config = config)
        // Post-resolve blank guard — a non-blank template can still resolve to empty
        // (e.g. template literally "{livetrack}" with karooLiveKey unset). Mirrors the
        // post-substitution check in sendRideStart/EndNotification. Without it the
        // provider receives an empty body — Pushover errors out, Telegram silently
        // sends nothing — and the rider gets no feedback. Skip the send and surface
        // ERROR so the field flashes the operator that nothing went out.
        if (resolved.isBlank()) {
            Timber.w("Custom message slot $slot resolved to blank — skipping send")
            CustomMessageState.update(slot, CustomMessageState.ERROR)
            scheduleCustomRevert(slot, 4_000L)
            return "Message resolved to empty — check tokens (e.g. {livetrack} requires a Karoo Live key)."
        }
        val outcome = sender.sendInfoOutcome(resolved, config.activeProvider)
        return when {
            outcome.delivered > 0 -> {
                CustomMessageState.update(slot, CustomMessageState.SENT)
                scheduleCustomRevert(slot, 4_000L)
                "Custom message sent! ✓"
            }
            // Deliverable config but the per-contact alert scopes filtered every recipient
            // out (e.g. all set to Emergency-only). Nobody received it — do NOT flash "sent ✓".
            outcome.infoSuccess -> {
                Timber.w("Custom message slot $slot: no recipients (all scopes exclude info messages)")
                CustomMessageState.update(slot, CustomMessageState.ERROR)
                scheduleCustomRevert(slot, 4_000L)
                "No recipients for this message — check each contact's alert scope (set to Emergency-only?)."
            }
            else -> {
                CustomMessageState.update(slot, CustomMessageState.ERROR)
                "Send failed — check your provider configuration."
            }
        }
    }

    /**
     * Called from CustomMessageActionCallback / BonusAction / FieldTapReceiver for
     * any slot 1..3. Wraps sendCustomMessage in the try/catch the bare suspend
     * call needs — sendCustomMessage sets CustomMessageState.SENDING synchronously
     * BEFORE the first suspend, so an unhandled throw from substituteTokens or
     * the Sender HTTP layer would otherwise leave the field stuck in SENDING with
     * no revert until extension restart.
     */
    fun handleCustomMessageTap(slot: Int = 1) {
        if (slot !in 1..3) {
            Timber.w("handleCustomMessageTap: invalid slot $slot")
            return
        }
        launch {
            try {
                val result = sendCustomMessage(slot)
                Timber.d("handleCustomMessageTap(slot=$slot) result: $result")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "handleCustomMessageTap(slot=$slot) threw — reverting slot $slot to ERROR")
                CustomMessageState.update(slot, CustomMessageState.ERROR)
                scheduleCustomRevert(slot, 4_000L)
            }
        }
    }

    /**
     * Aggregate result of one drain call from [sendCalibrationLogInChunks]: how many
     * chunks landed, how many bytes total, whether anything is still on disk, and the
     * last error string (null on full success). Callers decide what to surface — the
     * periodic loop just logs `lastError`, the manual-send path returns a user-facing
     * string built from `fullyDrained` and `lastError`.
     */
    private data class CalibrationSendResult(
        val chunksSent: Int,
        val totalLinesSent: Int,
        val totalBytesSent: Long,
        val hasMore: Boolean,
        val lastError: String?,
    ) {
        val anySent: Boolean get() = chunksSent > 0
        val fullyDrained: Boolean get() = !hasMore && lastError == null
    }

    /**
     * Drains the calibration log in size-capped chunks via Telegram. Stops on (a)
     * the first failed chunk, (b) an empty file, or (c) [maxChunks] chunks sent —
     * whichever comes first. Each chunk is a self-contained CSV (HEADER + N rows)
     * sized to fit a single Karoo-SDK `httpRequest` Binder transaction, bypassing
     * the 2026-05-25 "Request too large" failure that occurred once the unchunked
     * file crossed ~80 KB (see [CALIBRATION_MAX_CHUNK_BYTES] for the empirical
     * cap derivation).
     *
     * Failure semantics: a chunk that fails to send leaves the unsent tail on disk
     * (no [CalibrationLogger.truncateAfterSuccessfulSend] call), so the next periodic
     * / end-of-ride / manual invocation resumes from exactly the same point. The
     * file's `LOGGER_START "logging_resumed_after_periodic_send"` markers thread
     * the chunks together so the developer can stitch a full ride's data back from
     * the sequence of received files.
     *
     * MUST be called from a coroutine on [Dispatchers.IO] — file IO and the
     * `truncateAfterSuccessfulSend` rewrite are blocking eMMC operations that
     * would stall Main.
     */
    private suspend fun sendCalibrationLogInChunks(
        captionPrefix: String,
        maxChunks: Int = Int.MAX_VALUE,
    ): CalibrationSendResult = calibSendMutex.withLock {
        var chunksSent = 0
        var totalLines = 0
        var totalBytes = 0L
        var hasMore = false
        var lastError: String? = null

        while (chunksSent < maxChunks) {
            val chunk = calibLogger.getFileContentChunked(CALIBRATION_MAX_CHUNK_BYTES)
                ?: break  // file is empty / unreadable — done
            val caption = buildString {
                append(captionPrefix)
                append(" — ")
                append(calibLogger.captionForSession(chunk.linesIncluded))
                if (chunk.hasMore) append(" (chunk ${chunksSent + 1}, more pending)")
                else if (chunksSent > 0) append(" (chunk ${chunksSent + 1}, final)")
            }
            // Name each chunk uniquely + sortably (…_c000_, _c001_, …) so the
            // receiving inbox keeps multi-chunk / multi-cycle sessions orderable
            // instead of collapsing them onto one repeated "(2)(3)…" filename.
            val result = LogReporter.sendLogFile(
                content = chunk.content,
                fileName = calibLogger.chunkFileName(calibLogger.uploadedChunks),
                caption = caption,
                karooSystem = karooSystem,
            )
            if (!result.ok) {
                lastError = result.message
                hasMore = true  // file still has the unsent tail (and likely more)
                Timber.w("Calibration chunk send failed (chunk ${chunksSent + 1}, " +
                        "${chunk.content.length} bytes): ${result.message}")
                break
            }
            calibLogger.truncateAfterSuccessfulSend(chunk.linesIncluded)
            chunksSent++
            totalLines += chunk.linesIncluded
            totalBytes += chunk.content.length
            if (!chunk.hasMore) {
                hasMore = false
                break
            }
            hasMore = true
        }
        CalibrationSendResult(chunksSent, totalLines, totalBytes, hasMore, lastError)
    }

    /**
     * Sends the complete CSV calibration log to the developer via Telegram.
     * Called from the Settings UI "Send now" button for manual trigger.
     * The file is sent regardless of whether logging is currently active.
     */
    suspend fun sendCalibrationLog(): String {
        // 1) Previous-session file (preserved by enable() when the rider had logging on
        //    during a previous ride that crashed before sending). Drained in
        //    size-capped chunks for the same reason the current-session path is
        //    chunked — a long crashed ride can leave a 200 KB+ recovered file
        //    that would otherwise hit the Binder transaction limit and fail
        //    permanently. The final chunk's truncate clears the on-disk file
        //    so the rider doesn't see "previous: ✓" repeatedly.
        val previousDrain = withContext(Dispatchers.IO) {
            sendPreviousCalibrationLogInChunks()
        }

        // 2) Current session — drained in size-capped chunks on IO so each body
        //    fits the Karoo SDK Binder transaction. The drain runs to completion
        //    (no per-cycle cap) since this is a user-initiated manual send and we
        //    want the rider to see everything land in one go.
        Timber.d("Sending calibration log manually via Telegram (chunked)")
        val drain = withContext(Dispatchers.IO) {
            sendCalibrationLogInChunks(captionPrefix = "Manual")
        }
        // Surface a useful message based on the drain outcome and the optional
        // previous-session attempt. Cases (in priority order):
        //   - Nothing on disk + no previous-send → "No data".
        //   - Fully drained + previous OK → "Sent ✓".
        //   - Fully drained, no previous → "Sent ✓".
        //   - Some chunks sent but a later one failed → "Sent N chunks, then …".
        //   - Zero chunks sent but data exists → first error from drain.
        val anyCurrent = drain.chunksSent > 0
        val anyPrevious = previousDrain.chunksSent > 0
        val previousStatus = when {
            !anyPrevious && previousDrain.lastError == null -> null  // no previous on disk
            previousDrain.fullyDrained -> "previous: ✓"
            else -> "previous: failed"
        }
        return when {
            !anyCurrent && !anyPrevious && drain.lastError == null && previousDrain.lastError == null ->
                "No calibration data on disk yet."
            !anyCurrent && drain.lastError != null ->
                drain.lastError
            drain.fullyDrained && previousStatus != null ->
                "Calibration log sent ✓ (${drain.chunksSent} chunk(s); $previousStatus)"
            drain.fullyDrained ->
                "Calibration log sent ✓ (${drain.chunksSent} chunk(s), " +
                    "${drain.totalBytesSent / 1024} KB)"
            // Partial success: at least one chunk landed but a later one failed
            else -> "Sent ${drain.chunksSent} chunk(s) (${drain.totalBytesSent / 1024} KB); " +
                "remaining tail kept on disk. Last error: ${drain.lastError ?: "unknown"}"
        }
    }

    /**
     * Drain the preserved previous-session file in size-capped chunks, mirroring
     * [sendCalibrationLogInChunks]. Same failure semantics: a failed chunk leaves
     * the unsent tail on disk for the next manual send. On success the previous
     * file is deleted (see [CalibrationLogger.truncatePreviousAfterSuccessfulSend]).
     * MUST run on Dispatchers.IO.
     */
    private suspend fun sendPreviousCalibrationLogInChunks(): CalibrationSendResult = calibSendMutex.withLock {
        var chunksSent = 0
        var totalLines = 0
        var totalBytes = 0L
        var hasMore = false
        var lastError: String? = null

        while (true) {
            val chunk = calibLogger.getPreviousFileContentChunked(CALIBRATION_MAX_CHUNK_BYTES)
                ?: break
            val caption = buildString {
                append("Recovered previous session — ")
                append("${chunk.linesIncluded} lines")
                if (chunk.hasMore) append(" (chunk ${chunksSent + 1}, more pending)")
                else if (chunksSent > 0) append(" (chunk ${chunksSent + 1}, final)")
            }
            val result = LogReporter.sendLogFile(
                content = chunk.content,
                fileName = calibLogger.previousChunkFileName(calibLogger.uploadedPreviousChunks),
                caption = caption,
                karooSystem = karooSystem,
            )
            if (!result.ok) {
                lastError = result.message
                hasMore = true
                Timber.w("Previous-session chunk send failed (chunk ${chunksSent + 1}, " +
                        "${chunk.content.length} bytes): ${result.message}")
                break
            }
            calibLogger.truncatePreviousAfterSuccessfulSend(chunk.linesIncluded)
            chunksSent++
            totalLines += chunk.linesIncluded
            totalBytes += chunk.content.length
            if (!chunk.hasMore) { hasMore = false; break }
            hasMore = true
        }
        CalibrationSendResult(chunksSent, totalLines, totalBytes, hasMore, lastError)
    }

    /**
     * Expose the persistent install ID for the Settings UI.
     * The lazy [CalibrationLogger.installId] is initialised on first access via
     * a [kotlinx.coroutines.runBlocking] call on Dispatchers.IO — effectively
     * instant after the first ride-start. Non-suspend because the underlying
     * value is already a plain [String] once the lazy is resolved.
     */
    fun getInstallIdForUi(): String = calibLogger.installId

    /** Active Karoo ride-profile id for the Settings UI's per-profile crash section. */
    fun getActiveProfileIdForUi(): String? = activeProfileId

    /** Returns a string with file location info for display in the Settings UI. */
    fun getCalibrationLogInfo(): String {
        val count = calibLogger.getEntryCount()
        val file = calibLogger.getLogFile()
        val previousPending = calibLogger.getPreviousFileContent() != null
        val base = if (file != null) "$count entries | ${file.path}"
                   else "$count entries (not yet flushed to disk)"
        return if (previousPending) "$base\n⚠ Previous unsent session detected — tap Send to recover."
               else base
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
        val outcome = sender.sendAlert(message, config.activeProvider)
        return when {
            outcome.partial -> "Test alert reached ${outcome.delivered} of ${outcome.eligible} contacts — check the others' configuration."
            outcome.anyOk   -> "Test alert sent successfully! Check your device."
            else            -> "Send failed — check your provider configuration."
        }
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
        Timber.d("Sending test ride start notification via ${config.activeProvider}")
        // Route through the shared token substitution (same as sendRideStartNotification)
        // so {location}, {livetrack} etc. are resolved — the test must exercise the exact
        // message the rider's contacts would receive, not a literal-token preview.
        val message = emergencyManager.substituteTokens(
            template = config.karooLiveStartMessage,
            config = config,
        )
        // Parity with sendTestRideEnd / the production sendRideStartNotification: don't send
        // (and don't report success for) an empty message if the rider blanked the template.
        if (message.isBlank()) return "Ride start message is empty — set it in Settings first."
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
        // Route through the shared token substitution (same as sendRideEndNotification)
        // so {location} / {livetrack} are resolved instead of sent literally.
        val message = emergencyManager.substituteTokens(
            template = config.karooLiveEndMessage,
            config = config,
        )
        val ok = sender.sendInfo(message, config.activeProvider)
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
     *
     * @param bgColorRes Android @ColorRes ID (e.g. `R.color.alert_red`). NOT a packed
     *   ARGB int — the Karoo SDK's InRideAlert.backgroundColor passes its argument to
     *   `Context.getColor()`, which interprets a packed int as a resource ID and crashes
     *   the ride app with `Resources$NotFoundException`. See `res/values/colors.xml`.
     */
    private fun dispatchWebhookFeedback(
        id: String, header: String, message: String,
        bgColorRes: Int = R.color.alert_slate,
    ) {
        // Both Recording AND Paused count as "rider is on the ride screen" — autopause
        // (traffic light, café stop) keeps the data screen up, so InRideAlert is still
        // the visible-feedback channel. Previously this only checked Recording, which
        // meant a webhook tap during autopause silently fell through to SystemNotification
        // (drawer-only) and the rider thought the action had failed.
        val onRideScreen = currentRideState is RideState.Recording ||
                           currentRideState is RideState.Paused
        // Unique-per-fire suffix on the id: callers pass stable ids like
        // "ksafe-webhook-1-ok" so the field state-flow can correlate the
        // tap with the resulting feedback, but the same id re-dispatched
        // while the host still tracks a prior overlay/notification crashes
        // the Karoo ride app. Each callsite (webhook taps, custom-message
        // feedback) can fire rapidly when a rider re-taps a slot.
        val uniqueId = "$id-${System.currentTimeMillis()}"
        if (onRideScreen) {
            karooSystem.dispatch(InRideAlert(
                id = uniqueId,
                icon = R.drawable.ic_ksafe,
                title = header,
                detail = message,
                autoDismissMs = 4_000L,
                backgroundColor = bgColorRes,
                textColor = R.color.alert_text_white,
            ))
        } else {
            // Idle — rider is in the launcher / KSafe Settings. SystemNotification is
            // visible there. A system overlay (SosOverlayManager-style) would be richer
            // but requires SYSTEM_ALERT_WINDOW permission; the rider may not have granted
            // it. Keep SystemNotification as the Idle fallback for now — the design-guide
            // "no system notifications mid-ride" rule (see KDoc above) only forbids the
            // Recording / Paused branch, which already routes to InRideAlert.
            karooSystem.dispatch(SystemNotification(id = uniqueId, header = header, message = message))
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
        // Double-tap guard: bail if a previous tap on the same slot is still in flight.
        // A nervous rider can double-tap the Karoo field within ~100–500 ms before the
        // field re-renders to clickable=false; the second FieldTapReceiver broadcast
        // launches its own coroutine and would otherwise re-run geo-fence + auth + HTTP.
        // For external integrations (Home Assistant unlock, Pushover broadcast) this
        // duplicated request is genuinely bad. WebhookState transitions to FIRING below
        // (and to SUCCESS / ERROR on completion, before the auto-reset to IDLE) so the
        // queued tap reliably sees a non-IDLE/ERROR state and exits.
        // NOTE: no facade test harness for KSafeExtension exists today; if one is added,
        // add "webhook tap during in-flight FIRING is ignored" + "after success returns
        // to IDLE then fires normally" tests against this guard.
        val inFlight = WebhookState.flowForSlot(slot).value.state
        if (inFlight == WebhookState.FIRING || inFlight == WebhookState.SUCCESS) {
            Timber.d("Webhook slot=$slot tap ignored — already in $inFlight")
            return
        }
        try {
            val config = activeConfig
            val label = if (slot == 1) config.webhook1Label.ifBlank { "Action 1" }
                        else config.webhook2Label.ifBlank { "Action 2" }

            if (!config.isActive) {
                Timber.d("handleWebhookTap slot=$slot blocked — master switch OFF")
                WebhookState.update(slot, WebhookState.ERROR, "disabled")
                scheduleWebhookRevert(slot, 4_000L)
                dispatchWebhookFeedback(
                    id = "ksafe-webhook-$slot-master-off",
                    header = label,
                    message = getString(R.string.webhook_blocked_master_off),
                    bgColorRes = R.color.alert_red,
                )
                return
            }

            // ── Enabled check ─────────────────────────────────────────────────
            val enabled = if (slot == 1) config.webhook1Enabled else config.webhook2Enabled
            if (!enabled) {
                Timber.d("handleWebhookTap slot=$slot disabled")
                WebhookState.update(slot, WebhookState.ERROR, "disabled")
                scheduleWebhookRevert(slot, 4_000L)
                dispatchWebhookFeedback(
                    id = "ksafe-webhook-$slot-disabled",
                    header = label,
                    message = getString(R.string.webhook_blocked_disabled),
                    bgColorRes = R.color.alert_red,
                )
                return
            }

            // ── URL check ─────────────────────────────────────────────────────
            val url = if (slot == 1) config.webhook1Url else config.webhook2Url
            if (url.isBlank()) {
                Timber.d("handleWebhookTap slot=$slot no URL")
                WebhookState.update(slot, WebhookState.ERROR, "no URL")
                scheduleWebhookRevert(slot, 4_000L)
                dispatchWebhookFeedback(
                    id = "ksafe-webhook-$slot-nourl",
                    header = label,
                    message = getString(R.string.webhook_blocked_no_url),
                    bgColorRes = R.color.alert_red,
                )
                return
            }

            // ── Geo-fence check ───────────────────────────────────────────────
            val geoEnabled = if (slot == 1) config.webhook1GeoEnabled else config.webhook2GeoEnabled
            if (geoEnabled) {
                val targetLat = if (slot == 1) config.webhook1GeoLat else config.webhook2GeoLat
                val targetLon = if (slot == 1) config.webhook1GeoLon else config.webhook2GeoLon
                val radiusM   = if (slot == 1) config.webhook1GeoRadiusM else config.webhook2GeoRadiusM
                // G8 — gate on nullability rather than coordinate-equality with (0,0).
                // Aliasing 'no fix' with 'fix at Null Island' permanently locks riders
                // physically near (0,0) Gulf of Guinea out of geo-fenced webhooks, AND
                // during the brief GPS cold-start window where some MTK/Broadcom
                // chipsets report (0,0) before locking, a legitimate fix at any other
                // location would still be misclassified.
                //
                // G9 — use `getFreshFix(3_000L)` instead of `currentFix()`. The
                // persistent collector sample-rate is 2 min, so a rider who has just
                // arrived at the target would otherwise hit a stale cache and see
                // "Blocked — 200-400 m away" until the next sample tick. The fresh
                // fetch reuses the cached fix when it's < 5 s old (rapid double-tap)
                // and falls back to a recent-enough cache on timeout. Webhook taps are rider-
                // initiated and infrequent — the per-tap 1-3 s IPC round-trip is
                // negligible against the safety win.
                val curFix = locationManager.getFreshFix(3_000L)
                if (curFix == null) {
                    WebhookState.update(slot, WebhookState.ERROR, "no GPS")
                    scheduleWebhookRevert(slot, 4_000L)
                    dispatchWebhookFeedback(
                        id = "ksafe-webhook-$slot-geo-nofix",
                        header = label,
                        message = getString(R.string.webhook_blocked_no_gps),
                        bgColorRes = R.color.alert_orange,
                    )
                    return
                }
                val curLat = curFix.lat
                val curLon = curFix.lng
                if (targetLat == 0.0 && targetLon == 0.0) {
                    WebhookState.update(slot, WebhookState.ERROR, "no target")
                    scheduleWebhookRevert(slot, 4_000L)
                    dispatchWebhookFeedback(
                        id = "ksafe-webhook-$slot-geo-nocfg",
                        header = label,
                        message = getString(R.string.webhook_blocked_no_target),
                        bgColorRes = R.color.alert_orange,
                    )
                    return
                }
                val distance = distanceMeters(curLat, curLon, targetLat, targetLon)
                if (distance > radiusM) {
                    val distKm = if (distance >= 1000) "${"%.1f".formatUs(distance/1000)}km" else "${distance.toInt()}m"
                    WebhookState.update(slot, WebhookState.ERROR, "geo $distKm")
                    scheduleWebhookRevert(slot, 5_000L)
                    dispatchWebhookFeedback(
                        id = "ksafe-webhook-$slot-geo-far",
                        header = label,
                        message = getString(R.string.webhook_blocked_too_far, distance.toInt(), radiusM),
                        bgColorRes = R.color.alert_orange,
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
            scheduleWebhookRevert(slot, 4_000L)

            dispatchWebhookFeedback(
                id = "ksafe-webhook-$slot-${if (result.success) "ok" else "err"}",
                header = label,
                message = if (result.success) "$label ✓" else result.message,
                bgColorRes = if (result.success) R.color.alert_green else R.color.alert_red,
            )
            if (result.success) {
                val alertEnabled = if (slot == 1) config.webhook1AlertEnabled else config.webhook2AlertEnabled
                val alertText    = if (slot == 1) config.webhook1AlertText    else config.webhook2AlertText
                if (alertEnabled && alertText.isNotBlank()) {
                    dispatchWebhookFeedback(
                        id = "ksafe-webhook-$slot-alert",
                        header = label,
                        message = alertText,
                        bgColorRes = R.color.alert_blue,
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "handleWebhookTap slot=$slot EXCEPTION: ${e.message}")
            WebhookState.update(slot, WebhookState.ERROR, "exception")
            scheduleWebhookRevert(slot, 4_000L)
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

    /** True while a crash / SOS / check-in is in progress. Canonical in-memory source —
     *  [EmergencyManager.uiState], NOT DataStore (which is async and racy with the ticks). */
    private fun emergencyActive(): Boolean =
        com.enderthor.kSafe.extension.managers.EmergencyManager.uiState.value.status !=
            com.enderthor.kSafe.data.EmergencyStatus.IDLE

    /**
     * Present a fueling alert through the channel chosen by [decideFuelingPresentation]:
     *  - Active emergency → SUPPRESS: present nothing (no overlay, no InRideAlert) so the
     *    fueling alert can never compete with the SOS countdown/alert on any surface.
     *  - OFF mode → plain [InRideAlert] (behaviour preserved from before this feature).
     *  - LOG / LOG_UNDO mode (with overlay permission + no active emergency) → on-screen
     *    overlay with a one-tap LOG button (and, in LOG_UNDO mode, a brief UNDO follow-up).
     * Called by the carb / hydration trackers via their `onFuelingAlert` callback; the beep
     * has already fired inside the tracker by the time we get here, so all paths stay audible.
     *
     * The overlay paths pass `abortIf = ::emergencyActive`: the SUPPRESS check above is a
     * synchronous snapshot, but showPrompt defers the addView to a later main-loop turn, so
     * the guard is re-checked there to catch an emergency that starts in that gap.
     */
    private fun presentFuelingAlert(req: com.enderthor.kSafe.extension.util.FuelingAlertRequest) {
        val mode = activeConfig.fuelingAlertButtonMode
        val canOverlay = android.provider.Settings.canDrawOverlays(applicationContext)
        // The exact item the button will log/undo, e.g. "Gel 25 g" (#3). Empty on non-overlay
        // paths (slot null) — cheap string ops, only the InRideAlert is built lazily (#7).
        val item = fuelingItemLabel(req.channel, req.slot)
        // LOG-prompt detail: leads with the item so it survives the 2-line ellipsize, then the
        // alert rationale. UNDO-prompt detail: confirms what was just logged (#6).
        val logDetail = getString(R.string.fueling_overlay_log_detail, item, req.detail)
        val loggedDetail = getString(R.string.fueling_overlay_logged, item)
        when (com.enderthor.kSafe.extension.util.decideFuelingPresentation(
                mode, canOverlay, !emergencyActive(), hasUsableSlot = req.slot != null)) {
            com.enderthor.kSafe.extension.util.FuelingPresentation.SUPPRESS ->
                Timber.d("Fueling alert suppressed — emergency active")
            com.enderthor.kSafe.extension.util.FuelingPresentation.INRIDE_ALERT ->
                karooSystem.dispatch(req.inRideAlert())
            com.enderthor.kSafe.extension.util.FuelingPresentation.OVERLAY_LOG ->
                fuelingOverlay.showPrompt(req.title, logDetail, getString(R.string.fueling_overlay_log), 15_000L,
                    abortIf = ::emergencyActive) {
                    logFuelingSlot(req.channel, req.slot); fuelingOverlay.remove()
                }
            com.enderthor.kSafe.extension.util.FuelingPresentation.OVERLAY_LOG_UNDO ->
                fuelingOverlay.showPrompt(req.title, logDetail, getString(R.string.fueling_overlay_log), 15_000L,
                    abortIf = ::emergencyActive) {
                    logFuelingSlot(req.channel, req.slot)
                    fuelingOverlay.remove()
                    // Confirm what was logged (not the original "you should fuel" message) so the
                    // UNDO button reads as "undo THIS", not as a fresh fueling nag.
                    fuelingOverlay.showPrompt(req.title, loggedDetail, getString(R.string.fueling_overlay_undo), 4_000L,
                        abortIf = ::emergencyActive) {
                        undoFuelingSlot(req.channel, req.slot); fuelingOverlay.remove()
                    }
                }
        }
    }

    /** Human-readable name of the slot an overlay LOG/UNDO will record — e.g. "Gel 25 g" /
     *  "Bottle 500 ml" — read from activeConfig so the rider sees exactly what the button logs
     *  (#3). Empty when [slot] is null (the non-overlay branches never use it). */
    private fun fuelingItemLabel(channel: com.enderthor.kSafe.extension.util.FuelingChannel, slot: Int?): String {
        if (slot == null) return ""
        val c = activeConfig
        return when (channel) {
            com.enderthor.kSafe.extension.util.FuelingChannel.CARB -> when (slot) {
                1 -> "${c.carb1Label} ${c.carb1Grams} g"
                2 -> "${c.carb2Label} ${c.carb2Grams} g"
                else -> "${c.carb3Label} ${c.carb3Grams} g"
            }
            com.enderthor.kSafe.extension.util.FuelingChannel.HYDRATION -> when (slot) {
                1 -> "${c.drink1Label} ${c.drink1Ml} ml"
                else -> "${c.drink2Label} ${c.drink2Ml} ml"
            }
        }
    }

    /** Route an in-alert overlay LOG to the right tracker AND its on-ride field-state machine
     *  (logCarbSlot / logHydrationSlot) so the overlay log flashes the field and opens the undo
     *  window exactly like a field tap. No-op if [slot] is null (decideFuelingPresentation never
     *  reaches an overlay path without a usable slot — defensive). */
    private fun logFuelingSlot(channel: com.enderthor.kSafe.extension.util.FuelingChannel, slot: Int?) {
        if (slot == null) return
        when (channel) {
            com.enderthor.kSafe.extension.util.FuelingChannel.CARB -> logCarbSlot(slot)
            com.enderthor.kSafe.extension.util.FuelingChannel.HYDRATION -> logHydrationSlot(slot)
        }
    }

    /** UNDO counterpart of [logFuelingSlot]. */
    private fun undoFuelingSlot(channel: com.enderthor.kSafe.extension.util.FuelingChannel, slot: Int?) {
        if (slot == null) return
        when (channel) {
            com.enderthor.kSafe.extension.util.FuelingChannel.CARB -> undoCarbSlot(slot)
            com.enderthor.kSafe.extension.util.FuelingChannel.HYDRATION -> undoHydrationSlot(slot)
        }
    }

    fun handleCarbLogTap(slot: Int) {
        Timber.d("handleCarbLogTap slot=$slot")
        if (!activeConfig.isActive) return
        if (!activeConfig.carbsTrackerEnabled) return
        if (!this::carbsTracker.isInitialized) return
        if (slot !in 1..3) return
        // Toggle: a tap inside the undo window reverses the last entry; otherwise it logs.
        // Both halves are shared with the in-alert overlay LOG/UNDO buttons (logCarbSlot /
        // undoCarbSlot) so the field stays in sync whichever surface acted.
        if (com.enderthor.kSafe.datatype.CarbLogState.flowForSlot(slot).value
                is com.enderthor.kSafe.datatype.CarbLogState.LOGGED) undoCarbSlot(slot)
        else logCarbSlot(slot)
    }

    /**
     * Log one carb entry for [slot] and flash the on-ride CarbLog field LOGGED with a 6 s undo
     * window. Shared by the field tap and the in-alert overlay LOG button so the field state
     * never diverges from the tracker's accounting regardless of which surface logged.
     */
    private fun logCarbSlot(slot: Int) {
        if (slot !in 1..3 || !this::carbsTracker.isInitialized) return
        // Cancel any pending revert from a previous action on this slot. Without this a
        // log → undo → log sequence within ~6 s could leave a stale LOGGED→IDLE timer that
        // fires later and clobbers the latest LOGGED flash before the rider sees it.
        carbTapRevertJobs[slot]?.cancel()
        carbTapRevertJobs[slot] = null
        val logged = carbsTracker.logEntry(slot)
        // 6 s window: ample for a glove-friendly undo on rough terrain, short enough not to
        // feel "locked" before a legitimate back-to-back log. History: 5 s → 8 s → 6 s.
        com.enderthor.kSafe.datatype.CarbLogState.update(slot, com.enderthor.kSafe.datatype.CarbLogState.LOGGED(logged))
        carbTapRevertJobs[slot] = launch {
            kotlinx.coroutines.delay(6_000L)
            com.enderthor.kSafe.datatype.CarbLogState.update(slot, com.enderthor.kSafe.datatype.CarbLogState.IDLE)
            carbTapRevertJobs[slot] = null
        }
    }

    /** Reverse the last carb entry for [slot] and flash the field UNDONE (1.5 s) then IDLE.
     *  Shared by the field tap (second tap in the window) and the overlay UNDO button. */
    private fun undoCarbSlot(slot: Int) {
        if (slot !in 1..3 || !this::carbsTracker.isInitialized) return
        carbTapRevertJobs[slot]?.cancel()
        carbTapRevertJobs[slot] = null
        val undone = carbsTracker.undoLastForSlot(slot)
        if (undone > 0) {
            com.enderthor.kSafe.datatype.CarbLogState.update(slot, com.enderthor.kSafe.datatype.CarbLogState.UNDONE(undone))
            carbTapRevertJobs[slot] = launch {
                kotlinx.coroutines.delay(1_500L)
                com.enderthor.kSafe.datatype.CarbLogState.update(slot, com.enderthor.kSafe.datatype.CarbLogState.IDLE)
                carbTapRevertJobs[slot] = null
            }
        } else {
            com.enderthor.kSafe.datatype.CarbLogState.update(slot, com.enderthor.kSafe.datatype.CarbLogState.IDLE)
        }
    }

    fun handleHydrationLogTap(slot: Int) {
        Timber.d("handleHydrationLogTap slot=$slot")
        if (!activeConfig.isActive) return
        if (!activeConfig.hydrationTrackerEnabled) return
        if (!this::hydrationTracker.isInitialized) return
        if (slot !in 1..2) return
        // Toggle — same model as handleCarbLogTap; halves shared with the overlay buttons.
        if (com.enderthor.kSafe.datatype.HydrationLogState.flowForSlot(slot).value
                is com.enderthor.kSafe.datatype.HydrationLogState.LOGGED) undoHydrationSlot(slot)
        else logHydrationSlot(slot)
    }

    /** Hydration counterpart of [logCarbSlot] — shared by the field tap and the overlay LOG. */
    private fun logHydrationSlot(slot: Int) {
        if (slot !in 1..2 || !this::hydrationTracker.isInitialized) return
        hydTapRevertJobs[slot]?.cancel()
        hydTapRevertJobs[slot] = null
        val logged = hydrationTracker.logEntry(slot)
        com.enderthor.kSafe.datatype.HydrationLogState.update(slot, com.enderthor.kSafe.datatype.HydrationLogState.LOGGED(logged))
        hydTapRevertJobs[slot] = launch {
            kotlinx.coroutines.delay(6_000L)
            com.enderthor.kSafe.datatype.HydrationLogState.update(slot, com.enderthor.kSafe.datatype.HydrationLogState.IDLE)
            hydTapRevertJobs[slot] = null
        }
    }

    /** Hydration counterpart of [undoCarbSlot] — shared by the field tap and the overlay UNDO. */
    private fun undoHydrationSlot(slot: Int) {
        if (slot !in 1..2 || !this::hydrationTracker.isInitialized) return
        hydTapRevertJobs[slot]?.cancel()
        hydTapRevertJobs[slot] = null
        val undone = hydrationTracker.undoLastForSlot(slot)
        if (undone > 0) {
            com.enderthor.kSafe.datatype.HydrationLogState.update(slot, com.enderthor.kSafe.datatype.HydrationLogState.UNDONE(undone))
            hydTapRevertJobs[slot] = launch {
                kotlinx.coroutines.delay(1_500L)
                com.enderthor.kSafe.datatype.HydrationLogState.update(slot, com.enderthor.kSafe.datatype.HydrationLogState.IDLE)
                hydTapRevertJobs[slot] = null
            }
        } else {
            com.enderthor.kSafe.datatype.HydrationLogState.update(slot, com.enderthor.kSafe.datatype.HydrationLogState.IDLE)
        }
    }

    fun handleCombinedLogTap(slot: Int) {
        Timber.d("handleCombinedLogTap slot=$slot")
        if (!activeConfig.isActive) return
        val carbsOn = activeConfig.carbsTrackerEnabled && this::carbsTracker.isInitialized
        val hydOn = activeConfig.hydrationTrackerEnabled && this::hydrationTracker.isInitialized
        if (!carbsOn && !hydOn) return
        if (slot !in 1..2) return
        combinedTapRevertJobs[slot]?.cancel()
        combinedTapRevertJobs[slot] = null

        val St = com.enderthor.kSafe.datatype.CombinedFuelLogState
        val state = St.flowForSlot(slot).value
        if (state is com.enderthor.kSafe.datatype.CombinedFuelLogState.LOGGED) {
            // Reverse exactly what THIS log added (carried in the state), not what the live
            // toggles say now. If the rider disables a tracker during the 6 s undo window,
            // re-checking carbsOn/hydOn here would skip the reversal and leave that amount
            // stuck in the cumulative total. state.grams>0 already implies it was logged, so
            // undoAmount (clamped at 0) is safe; the isInitialized guard is belt-and-braces.
            if (state.grams > 0 && this::carbsTracker.isInitialized) carbsTracker.undoAmount(state.grams)
            if (state.ml > 0 && this::hydrationTracker.isInitialized) hydrationTracker.undoAmount(state.ml)
            St.update(slot, com.enderthor.kSafe.datatype.CombinedFuelLogState.UNDONE(state.ml, state.grams))
            combinedTapRevertJobs[slot] = launch {
                kotlinx.coroutines.delay(1_500L)
                St.update(slot, com.enderthor.kSafe.datatype.CombinedFuelLogState.IDLE)
                combinedTapRevertJobs[slot] = null
            }
            return
        }
        val ml = if (slot == 2) activeConfig.combined2Ml else activeConfig.combined1Ml
        val grams = if (slot == 2) activeConfig.combined2Carbs else activeConfig.combined1Carbs
        val loggedMl = if (hydOn) hydrationTracker.logAmount(ml) else 0
        val loggedG = if (carbsOn) carbsTracker.logAmount(grams) else 0
        St.update(slot, com.enderthor.kSafe.datatype.CombinedFuelLogState.LOGGED(loggedMl, loggedG))
        combinedTapRevertJobs[slot] = launch {
            kotlinx.coroutines.delay(6_000L)
            St.update(slot, com.enderthor.kSafe.datatype.CombinedFuelLogState.IDLE)
            combinedTapRevertJobs[slot] = null
        }
    }

    fun handleSOSTap() {
        // Use in-memory currentStatus — reading DataStore here can race with
        // the async COUNTDOWN save inside countdownJob, causing cancels to be
        // misidentified as new triggers.
        // Cancel-path is allowed in general (mirrors onBonusAction cancel-emergency):
        // a rider must always be able to stop an in-flight alert, even if the
        // master switch was toggled off after the countdown started — but a phantom
        // retap landing inside the 750 ms post-arm window is treated as part of the
        // same gesture and suppressed. See lastSosTriggerMs / SOS_RETAP_DEBOUNCE_MS.
        launch {
            when (emergencyManager.currentStatus) {
                EmergencyStatus.COUNTDOWN -> {
                    // COUNTDOWN→cancel debounce — Tap 1 arms COUNTDOWN synchronously
                    // (currentStatus flips before this coroutine dispatches), so a
                    // double-tap from the field re-render race lands here, NOT in IDLE.
                    // Without this guard Tap 2 would self-cancel the just-armed alert.
                    // A legitimate cancel-after-realisation (rider taps after 1+ s of
                    // seeing the countdown) is unaffected — only the 750 ms post-arm
                    // window is protected.
                    val now = System.currentTimeMillis()
                    if (now - lastSosTriggerMs < SOS_RETAP_DEBOUNCE_MS) {
                        Timber.d("SOS cancel ignored — within $SOS_RETAP_DEBOUNCE_MS ms of arm (phantom retap)")
                        return@launch
                    }
                    // K3 — stamp BEFORE cancel so a duplicate-broadcast Tap 2 arriving
                    // shortly after this cancel sees `now - lastSosTriggerMs < 750ms`
                    // in the IDLE branch's debounce guard. Without this stamp, the
                    // ORIGINAL lastSosTriggerMs (set at arm time, possibly seconds ago)
                    // would let Tap 2 bypass the debounce and trigger a brand-new
                    // emergency right after the rider cancelled.
                    lastSosTriggerMs = now
                    emergencyManager.cancelEmergency(activeConfig)
                }
                EmergencyStatus.IDLE -> {
                    // The 5 s ALERTING_VISIBLE_MS rollback flips status to IDLE while
                    // the sender's retry loop keeps running in the background for up to
                    // ~30 min. A rider tap during that window expresses "abort the
                    // alert I just sent", NOT "send a new emergency". Without this
                    // branch, handleSOSTap would silently arm a SECOND emergency and
                    // both messages would reach contacts.
                    if (emergencyManager.alertJobActive()) {
                        Timber.d("SOS field tap during background retry — cancelling in-flight alertJob")
                        // Stamp the trigger-time so a phantom retap (the second of
                        // a queued double-broadcast) lands inside SOS_RETAP_DEBOUNCE_MS
                        // and is suppressed by the gate below — without this, the
                        // second broadcast would arm a brand-new emergency seconds
                        // after the cancel because alertJobActive() is now false.
                        lastSosTriggerMs = System.currentTimeMillis()
                        emergencyManager.cancelEmergency(activeConfig)
                        return@launch
                    }
                    // IDLE→trigger debounce — see KDoc on lastSosTriggerMs for the
                    // double-tap broadcast scenario. Stamping the timestamp here is
                    // what gates the COUNTDOWN branch above on subsequent taps.
                    val now = System.currentTimeMillis()
                    if (now - lastSosTriggerMs < SOS_RETAP_DEBOUNCE_MS) {
                        Timber.d("SOS tap ignored — within $SOS_RETAP_DEBOUNCE_MS ms of previous trigger")
                        return@launch
                    }
                    if (!activeConfig.isActive) return@launch
                    lastSosTriggerMs = now
                    emergencyManager.triggerEmergency(EmergencyReason.MANUAL_SOS, activeConfig)
                }
                EmergencyStatus.ALERTING -> {
                    // Allow cancel from the field while the sender is retrying.
                    // EmergencyManager.cancelEmergency accepts ALERTING and aborts
                    // the in-flight retry loop. Without this branch the only
                    // Cancel surfaces during the (up to ~30 min) retry window are
                    // the hardware BonusAction button (if mapped) and the SOS
                    // overlay (which already dismissed itself when the countdown
                    // ended) — a rider who realises they're fine has no way to
                    // stop the alert from the field they triggered it on.
                    Timber.d("Emergency cancelled via SOS field tap during ALERTING")
                    emergencyManager.cancelEmergency(activeConfig)
                }
            }
        }
    }

    fun handleCheckinTap() {
        launch {
            when (emergencyManager.currentStatus) {
                EmergencyStatus.COUNTDOWN -> {
                    // Cancel path always allowed — see handleSOSTap rationale.
                    // K4 — stamp lastSosTriggerMs before cancel so a duplicate-
                    // broadcast Tap 2 falls within the post-cancel debounce. Without
                    // it, the second broadcast lands in the IDLE branch and (if
                    // checkin is enabled) silently resets the check-in timer; the
                    // rider's "cancel" gesture is then re-interpreted as a check-in.
                    lastSosTriggerMs = System.currentTimeMillis()
                    Timber.d("Emergency cancelled via Timer field tap")
                    emergencyManager.cancelEmergency(activeConfig)
                }
                EmergencyStatus.ALERTING -> {
                    // Same rationale as handleSOSTap ALERTING — let the rider
                    // cancel a still-retrying alert from the Timer field.
                    Timber.d("Emergency cancelled via Timer field tap during ALERTING")
                    emergencyManager.cancelEmergency(activeConfig)
                }
                EmergencyStatus.IDLE -> {
                    // Same background-retry-cancel branch as handleSOSTap: a tap during
                    // the post-rollback window is an abort, not a check-in.
                    if (emergencyManager.alertJobActive()) {
                        Timber.d("Timer field tap during background retry — cancelling in-flight alertJob")
                        // Stamp so a phantom retap (queued double-broadcast) lands
                        // within SOS_RETAP_DEBOUNCE_MS and is suppressed — without
                        // this, the second broadcast would arm a brand-new emergency.
                        lastSosTriggerMs = System.currentTimeMillis()
                        emergencyManager.cancelEmergency(activeConfig)
                        return@launch
                    }
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
        locationManager.currentFix()?.let { Pair(it.lat, it.lng) } ?: Pair(0.0, 0.0)

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
        // Note: there is no `ksafe_carb_avg_burn_rate_gph` developer field. The
        // session average is derivable from the per-record `ksafe_carb_burn_rate_gph`
        // time series by any downstream analysis tool (Intervals.icu, fitparse,
        // etc.) — writing it again would duplicate information for 4 bytes saved.
        // The average is computed live by [CarbsTracker.computeAvgBurnRateGph]
        // and shown on the Karoo via the `carb-avg-burn-rate` data field during
        // the ride. fieldDefinitionNumber=7 is therefore reserved (not used).

        calibLogger.log(CalibrationLogger.Event.FIT_WRITER_START) {
            // Field-definition numbers are public-API once shipped; record them so the CSV
            // can be cross-referenced with the developer-field schema in the resulting FIT.
            "fields=0,1,2,3,4,5,6"
        }
        val job: Job = launch {
            // Per-ride caches of the last value written to each FIT field. The FIT
            // record/session messages are throttled to **write-on-change** so a 5 h
            // ride doesn't emit 18 000 record-writes per Recording-second on fields
            // whose underlying source-of-truth changes far less often. Real cadence
            // post-throttle (post-merge audit Nov 2026):
            //   - Record-message writes per 5 h ride: ~4 000-8 000 (down from 18 000;
            //     ~55-78 % saving). Driven mostly by `burnRateGph` and `driftPct`,
            //     both of which carry per-second noise from live HR/power even at
            //     "steady" intensity. NOT the ~1 200 originally claimed —
            //     `burnRateGph.toInt()` rounds at every g/h step which is reached
            //     several times per minute on a varied ride.
            //   - Session-message writes per 5 h ride: ~75-100 (down from 18 000)
            //     thanks to the 5 g deadband on cumBurnedG below.
            //
            // FIT consumer behaviour: Strava / Intervals.icu / TrainingPeaks plot
            // developer-field time series at the emitted timestamps and interpolate
            // between them. A sparse series therefore renders identically to a
            // dense series that repeats values — but the dense series wastes the
            // FIT file size and the host's per-record allocation budget on the
            // Karoo (the 2026-05-25 audit quantified ~50K allocations/hour from
            // this writer pre-throttle, of which ~75 % are now skipped).
            //
            // Sentinel: `Double.NaN`. `NaN != NaN` is true in IEEE 754, so the
            // first comparison after `startFit` is always "changed" and the
            // first tick always emits. Each subsequent tick compares the new
            // value to the cached one and only re-emits if any field moved.
            // Session-message uses the same idiom on its own cache because the
            // session activity-header contract is "last write wins" — emitting
            // identical values mid-ride doesn't change what Strava reads at the
            // end, but it does churn allocations.
            var lastRecCarbsG       = Double.NaN
            var lastRecHydMl        = Double.NaN
            var lastRecCarbsBurnedG = Double.NaN
            var lastRecBurnRateGph  = Double.NaN
            var lastRecDriftPct     = Double.NaN
            var lastSesCarbsG       = Double.NaN
            var lastSesHydMl        = Double.NaN
            var lastSesCarbsBurnedG = Double.NaN
            var lastSesMaxDriftPct  = Double.NaN
            var lastSesFires        = Double.NaN

            karooSystem.streamDataFlow(DataType.Type.ELAPSED_TIME)
                .mapNotNull { (it as? StreamState.Streaming)?.dataPoint?.singleValue }
                .collect {
                    // B29 — read the trackers' / monitor's published StateFlow snapshot
                    // instead of calling `getStatus()` / `getSummary()` every second.
                    // Each `.value` access is a single volatile read of an already-
                    // computed data class; the old call path allocated a fresh
                    // CarbStatus + BurnEstimate + ZoneSnapshot + HydrationStatus +
                    // WellnessSummary per ELAPSED_TIME tick (~5 objects/sec × 3600/h
                    // ≈ 720 KB young-gen/h) AND re-ran the full Keytel/Swain estimator
                    // pipeline for the carb tracker. The published flows update on the
                    // trackers' own tick cadence (15 s for fueling, 30 s for wellness),
                    // which is the actual rate of change of the underlying signals.
                    val carbStatus = carbsTrackerOrNull()?.statusFlow?.value
                    val carbsG       = (carbStatus?.cumLoggedG ?: 0).toDouble()
                    val carbsBurnedG = (carbStatus?.cumBurnedG ?: 0).toDouble()
                    val burnRateGph  = (carbStatus?.burnRateGph ?: 0).toDouble()
                    val hydMl  = (hydrationTrackerOrNull()?.statusFlow?.value?.cumLoggedMl ?: 0).toDouble()
                    val wellness = wellnessMonitorOrNull()?.summaryFlow?.value
                    val driftPct    = wellness?.currentDriftPct?.toDouble() ?: 0.0
                    val maxDriftPct = wellness?.maxDriftPct?.toDouble() ?: 0.0
                    val fires       = wellness?.totalFires?.toDouble() ?: 0.0
                    when (currentRideState) {
                        is RideState.Recording -> {
                            // Records (per-second time series): only fields that have a
                            // meaningful instantaneous reading or trace a useful curve over
                            // the ride.
                            //  - cumLoggedG / cumLoggedMl / cumBurnedG: step / accumulator
                            //    curves — graphable in Strava et al. as a "fuel taken /
                            //    target burned over time" line.
                            //  - burnRateGph: instantaneous burn rate at this moment.
                            //  - hrDriftPct: instantaneous cardiac-decoupling reading.
                            //
                            // Deliberately excluded from records: maxDriftPct (monotonic
                            // running max — uninteresting as a per-second time series)
                            // and totalFires (just a counter). Both belong in the session
                            // summary only. See FIT-writer audit 2026-05-25.
                            val recChanged =
                                carbsG       != lastRecCarbsG       ||
                                hydMl        != lastRecHydMl        ||
                                carbsBurnedG != lastRecCarbsBurnedG ||
                                burnRateGph  != lastRecBurnRateGph  ||
                                driftPct     != lastRecDriftPct
                            if (recChanged) {
                                emitter.onNext(WriteToRecordMesg(listOf(
                                    FieldValue(carbField,         carbsG),
                                    FieldValue(hydField,          hydMl),
                                    FieldValue(carbsBurnedField,  carbsBurnedG),
                                    FieldValue(burnRateField,     burnRateGph),
                                    FieldValue(hrDriftField,      driftPct),
                                )))
                                lastRecCarbsG       = carbsG
                                lastRecHydMl        = hydMl
                                lastRecCarbsBurnedG = carbsBurnedG
                                lastRecBurnRateGph  = burnRateGph
                                lastRecDriftPct     = driftPct
                            }
                            // Session (single-value activity-header summary): totals at
                            // ride end + ride-max statistics. Each tick overwrites the
                            // running value; whatever is current at FIT-close becomes the
                            // Strava / Hammerhead / Intervals.icu activity header.
                            //  - cumLoggedG / cumLoggedMl: total carbs / hyd taken.
                            //  - cumBurnedG: total estimated burn for the whole ride.
                            //  - maxDriftPct: peak cardiac-decoupling reached.
                            //  - totalFires: number of wellness alerts that fired.
                            //
                            // Deliberately excluded from session: burnRateGph and
                            // hrDriftPct — those are instantaneous; storing the LAST
                            // tick's value as a "summary" is misleading. The session
                            // average for burn rate is NOT written either: it's
                            // derivable from the per-record `ksafe_carb_burn_rate_gph`
                            // time series by any downstream analysis tool, so
                            // duplicating it in the session record would just add
                            // 4 bytes for the same information. The Karoo data
                            // field `carb-avg-burn-rate` shows it live during the
                            // ride.
                            //
                            // Must be written from the Recording branch (NOT a Paused
                            // branch as nomride does) because ELAPSED_TIME stops emitting
                            // while the ride is paused — a Paused-only write would never
                            // fire. All 7 DeveloperField definitions are public API once
                            // shipped: keep the declarations even though fewer of them
                            // appear in each message now.
                            // Session-message cadence is deliberately COARSER than the record
                            // message. The session message contract is "last write wins" — the
                            // value at FIT close becomes the activity header in Strava et al.
                            // We don't need 15 s granularity on this end; we just need the
                            // value to be approximately current at FIT close.
                            //
                            // Deadband on the cumulative burn driver: only emit when the burn
                            // total has moved at least SESSION_BURN_DEADBAND_G (5 g) since the
                            // last session write. Other event fields (rider taps via cumLoggedG /
                            // cumLoggedMl, wellness peaks via maxDriftPct, alerts via fires) still
                            // trigger immediately so the activity header reflects them on the
                            // next FIT-close. Worst case: the final cumBurnedG in the header is
                            // up to 5 g less than the true ride total — sub-2 % error on a
                            // typical 300 g ride.
                            val burnDelta = if (lastSesCarbsBurnedG.isNaN()) Double.POSITIVE_INFINITY
                                            else carbsBurnedG - lastSesCarbsBurnedG
                            val burnSignificant = burnDelta >= SESSION_BURN_DEADBAND_G
                            val otherSesChanged =
                                carbsG      != lastSesCarbsG      ||
                                hydMl       != lastSesHydMl       ||
                                maxDriftPct != lastSesMaxDriftPct ||
                                fires       != lastSesFires
                            if (burnSignificant || otherSesChanged) {
                                emitter.onNext(WriteToSessionMesg(listOf(
                                    FieldValue(carbField,         carbsG),
                                    FieldValue(hydField,          hydMl),
                                    FieldValue(carbsBurnedField,  carbsBurnedG),
                                    FieldValue(maxDriftField,     maxDriftPct),
                                    FieldValue(firesField,        fires),
                                )))
                                lastSesCarbsG       = carbsG
                                lastSesHydMl        = hydMl
                                lastSesCarbsBurnedG = carbsBurnedG
                                lastSesMaxDriftPct  = maxDriftPct
                                lastSesFires        = fires
                            }
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Republish night-mode on a day↔night flip. resources.configuration is already
        // updated by the time this fires, so isKarooNightMode() reads the new value. The
        // combine-based AUTO-colour fields merge nightModeFlow and re-render off this.
        val dark = isKarooNightMode()
        if (nightModeFlow.value != dark) {
            nightModeFlow.value = dark
            Timber.d("KSafe night-mode changed → dark=%b", dark)
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
        // Only if it was ever shown — avoids instantiating the lazy manager at teardown.
        if (fuelingOverlayLazy.isInitialized()) runCatching { fuelingOverlay.remove() }
        calibLogger.disable()
        // Unbind the HAL service before the karooSystem disconnect so we don't leak a
        // ServiceConnection across extension restarts. Safe to call even if connect()
        // failed — disconnect() is a no-op when not bound.
        if (::buzzerClient.isInitialized) buzzerClient.disconnect()
        // Cancel our own collectors BEFORE dropping the Karoo connection. A streamRide /
        // config collector suspended inside its callbackFlow would otherwise be able to
        // resume on a final SDK event AFTER disconnect() and run handleRideState / dispatch
        // against a dead connection; flipping the job to "cancelling" first means any such
        // pending emission resolves as a CancellationException and no business logic runs.
        // (cancel() is non-blocking — onDestroy can't cancelAndJoin on Main — so each
        // callbackFlow's awaitClose/removeConsumer still runs after this returns; the goal
        // here is only to stop post-disconnect event PROCESSING, not to order consumer removal.)
        job.cancel()
        karooSystem.disconnect()
        // Null out the published tracker references so any DataType that re-enters
        // `startView` after a service restart sees the cleared state and waits for
        // the new extension instance to republish them.
        carbsTrackerFlow.value = null
        hydrationTrackerFlow.value = null
        instance = null
        super.onDestroy()
    }
}
