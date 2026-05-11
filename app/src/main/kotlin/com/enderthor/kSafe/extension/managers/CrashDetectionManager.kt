package com.enderthor.kSafe.extension.managers

import android.content.Context
import android.hardware.SensorManager
import com.enderthor.kSafe.BuildConfig
import com.enderthor.kSafe.data.CrashSensitivity
import com.enderthor.kSafe.data.KSafeConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.abs

/**
 * Facade over [SensorReader] (Task 2.4), [CrashStateMachine] (Task 2.3) and
 * [SpeedDropMonitor] (Task 1.x). Owns the lifecycle, the crash cooldown, the
 * config → [Thresholds] mapping (including the dynamic peak boost), and the rich
 * calibration-log surface that the tuning workflow relies on.
 *
 * The algorithm itself lives in [CrashStateMachine]. This class:
 *  - Maps [KSafeConfig] sensitivity preset → numeric thresholds (verbatim tables
 *    from the previous monolithic implementation).
 *  - Computes the dynamic peak boost (POST_TMO + grade-aware) and rebuilds the
 *    [Thresholds] passed to the state machine when the effective threshold changes.
 *  - Observes [CrashStateMachine.Decision] transitions to emit the contextual
 *    IMPACT_ENTER / IMPACT_TIMEOUT / SILENCE_ENTER / SILENCE_TIMEOUT / CRASH_CONFIRMED
 *    rows with the surrounding ride context (speed, grade, cadence, gyro, buffer
 *    snapshot, noise) — these used to live inline in `processAccelerometer` and
 *    drive offline calibration.
 *  - Maintains the `lastCrashTime` cooldown gate; both the accelerometer pipeline
 *    and the speed-drop monitor route their confirmations through [confirmCrash].
 *
 * The public surface is unchanged from the pre-refactor implementation so
 * [com.enderthor.kSafe.extension.KSafeExtension] does not need to change.
 */
class CrashDetectionManager(
    context: Context,
    private val scope: CoroutineScope,
    private val onCrashDetected: () -> Unit,
    private val calibLogger: CalibrationLogger? = null,
    private val clock: Clock = SystemClock,
) {
    // ─── Sensitivity preset → numeric thresholds (verbatim from monolith) ─────

    /** Impact magnitude thresholds for preset levels: total acceleration vector (gravity ~9.8 included) */
    private val impactThresholds = mapOf(
        CrashSensitivity.LOW    to 55.0,  // ~5.5g — hard impacts; MTB/gravel friendly
        CrashSensitivity.MEDIUM to 45.0,  // ~4.5g — balanced road + MTB
        CrashSensitivity.HIGH   to 35.0,  // ~3.5g — road bike, more sensitive
        // CUSTOM → reads config.customCrashThreshold at runtime
    )

    /**
     * Single-sample peak thresholds — a parallel detector that fires on a single raw sample
     * exceeding this bar, without requiring the 3-sample sliding average.
     * All presets use pthr = smooth_thr + 5.
     */
    private val peakImpactThresholds = mapOf(
        CrashSensitivity.LOW    to 60.0,
        CrashSensitivity.MEDIUM to 50.0,
        CrashSensitivity.HIGH   to 40.0,
        // CUSTOM → 1.3× customCrashThreshold, capped at 80 m/s²
    )

    /** Impact window per sensitivity level: max time from impact to confirm crash. */
    private val impactWindowMs = mapOf(
        CrashSensitivity.LOW    to 25_000L,  // MTB: bike may keep rolling after a hard crash
        CrashSensitivity.MEDIUM to 20_000L,  // gravel/mixed
        CrashSensitivity.HIGH   to 15_000L,  // road bike: crashes settle quickly
        CrashSensitivity.CUSTOM to 20_000L   // reasonable default for custom
    )

    private companion object {
        const val GRAVITY = 9.81
        // Gyro entry threshold — kept high enough to stay effectively dormant in production:
        // typical riding gyro peaks at ~2.0 rad/s; default 6.0 means MONITORING gyro branch
        // is inert, matching the pre-refactor behaviour.
        const val GYRO_IMPACT_THRESHOLD = 6.0
        // Stillness reference threshold — matches SensorReader.SILENCE_DEVIATION_MAX.
        const val SILENCE_DEVIATION_MAX = 4.0
        const val SILENCE_DURATION_MS = 4_500L
        const val GYRO_MOVING_MAX = 2.0
        const val GPS_STALE_DEVIATION_MAX = 1.5
        const val GPS_STALE_SILENCE_DURATION_MS = 8_000L
        const val GPS_STALE_MS = 10_000L
        const val COLD_START_GUARD_MS = 8_000L
        const val MIN_TIME_SINCE_IMPACT_MS = 500L
        const val CADENCE_QUIET_RPM = 20.0
        const val CADENCE_STALE_MS = 10_000L
        const val LOG_INTERVAL_MS = 2_000L
        const val PERIODIC_LOG_INTERVAL_MS = 120_000L  // 2 min — finer timeline resolution

        // ─── Post-IMPACT_TMO dynamic peak-threshold boost ────────────────────
        const val POST_TMO_BOOST = 8.0
        const val POST_TMO_COOLDOWN_MS = 30_000L
        const val POST_CLUSTER_COOLDOWN_MS = 60_000L
        const val CLUSTER_WINDOW_MS = 120_000L
        const val CLUSTER_MIN_TMO = 3

        // ── CrashStateMachine "sample timestamp" base ────────────────────────
        // The state machine treats `sample.timestampMs` as the authoritative time
        // for IMPACT/SILENCE windows. We pass wall-clock so production semantics
        // (e.g. timeSinceImpact) match the monolith verbatim.
    }

    // ─── State ────────────────────────────────────────────────────────────────

    @Volatile private var config = KSafeConfig()
    @Volatile private var lastCrashTime = 0L
    @Volatile private var currentSpeedKmh = 0.0
    @Volatile private var speedLastUpdatedTime = 0L
    @Volatile private var speedDataReceived = false
    @Volatile private var startTime = 0L
    @Volatile private var lastDecelerationKmhPerS = 0.0

    @Volatile private var currentCadence = 0.0
    @Volatile private var cadenceDataReceived = false
    @Volatile private var currentGrade = 0.0
    @Volatile private var currentRoutingPreference = "ROAD"

    // ─── Boost & calibration tracking ────────────────────────────────────────
    @Volatile private var postImpactBoostUntil = 0L
    private val recentTmoTimestamps = ArrayDeque<Long>(8)

    // Window-progress accumulators (re-emitted in IMPACT_TIMEOUT calibration row)
    @Volatile private var maxSmoothedInWindow   = 0.0
    @Volatile private var minSpeedInWindow      = Double.MAX_VALUE
    @Volatile private var minDeviationInWindow  = Double.MAX_VALUE
    @Volatile private var gyroBlockedCnt        = 0
    @Volatile private var speedReachedInWindow  = false

    // Rate-limit timestamps
    @Volatile private var lastHighMagLogMs = 0L
    @Volatile private var lastSilenceBrokenMs = 0L
    @Volatile private var lastGyroBlockedLogMs = 0L
    @Volatile private var lastPeriodicLogMs = 0L
    @Volatile private var lastLogTime = 0L
    @Volatile private var lastGpsStaleState = false
    @Volatile private var lastLoggedSensitivity = config.crashSensitivity

    // Cached effective thresholds applied to the SM (recomputed when boost / grade change)
    @Volatile private var cachedSmoothedThr = 45.0
    @Volatile private var cachedPeakThr     = 50.0
    @Volatile private var cachedWindowMs    = 20_000L
    @Volatile private var cachedEffectivePeakThr = 50.0

    private val crashCooldownMs get() = (config.countdownSeconds * 1_000L) + 30_000L

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // ─── Composition ──────────────────────────────────────────────────────────

    private val sensorReader = SensorReader(
        sensorManager = sensorManager,
        clock = clock,
        accelStillDeviationMax = SILENCE_DEVIATION_MAX,
        onSample = { sample -> onSensorSample(sample) },
    )

    private val stateMachine = CrashStateMachine(
        thresholds = buildThresholds(config, effectivePeakThr = cachedEffectivePeakThr),
        clock = clock,
        calibLogger = null,  // facade emits the rich events with full context
    )

    private val speedDropMonitor = SpeedDropMonitor(
        scope = scope,
        clock = clock,
        accelStillSinceProvider = { sensorReader.accelStillSinceMs },
        cooldownGate = { (clock.nowMs() - lastCrashTime) > crashCooldownMs },
        onConfirm = { confirmCrash(CrashSource.SPEED_DROP) },
        calibLogger = calibLogger,
    )

    // ─── Public API (unchanged from pre-refactor) ────────────────────────────

    fun start(config: KSafeConfig) {
        this.config = config
        if (!config.crashDetectionEnabled) {
            Timber.d("CrashDetection disabled in config, skipping start")
            return
        }
        startTime = clock.nowMs()
        speedDataReceived = false
        lastPeriodicLogMs = 0L
        lastGpsStaleState = false
        lastLoggedSensitivity = config.crashSensitivity
        postImpactBoostUntil = 0L
        recentTmoTimestamps.clear()
        resetWindowAccumulators()

        rebuildThresholds(boostActive = false)
        stateMachine.reset()
        sensorReader.start(handler = null)

        Timber.d("CrashDetectionManager STARTED (sensitivity=${config.crashSensitivity}, threshold=${cachedSmoothedThr}m/s²)")
        if (config.speedDropDetectionEnabled) speedDropMonitor.start(config.speedDropMinutes)
    }

    fun stop() {
        sensorReader.stop()
        speedDropMonitor.stop()
        stateMachine.reset()
        resetWindowAccumulators()
        Timber.d("CrashDetectionManager STOPPED")
    }

    fun updateConfig(newConfig: KSafeConfig) {
        val wasEnabled = this.config.crashDetectionEnabled
        val oldSensitivity = this.config.crashSensitivity
        val oldCustomThr = this.config.customCrashThreshold
        this.config = newConfig

        // Log mid-ride config changes so the CSV captures the moment thresholds shifted.
        if (calibLogger != null && calibLogger.isEnabled &&
            (newConfig.crashSensitivity != oldSensitivity || newConfig.customCrashThreshold != oldCustomThr)) {
            val newThr = smoothedThresholdFor(newConfig)
            calibLogger.log(CalibrationLogger.Event.PERIODIC) {
                "config_change=true,old_preset=$oldSensitivity,new_preset=${newConfig.crashSensitivity},new_thr=$newThr,min_spd=${newConfig.minSpeedForCrashKmh}"
            }
            lastLoggedSensitivity = newConfig.crashSensitivity
        }

        rebuildThresholds(boostActive = clock.nowMs() < postImpactBoostUntil)

        // If crash detection was toggled, restart listener
        if (wasEnabled && !newConfig.crashDetectionEnabled) stop()
        else if (!wasEnabled && newConfig.crashDetectionEnabled) start(newConfig)
    }

    fun updateSpeed(speedKmh: Double) {
        val now = clock.nowMs()
        val elapsed = if (speedLastUpdatedTime > 0) (now - speedLastUpdatedTime) / 1000.0 else 0.0
        if (elapsed >= 0.2 && speedDataReceived) {
            lastDecelerationKmhPerS = (speedKmh - currentSpeedKmh) / elapsed
        }
        if (!speedDataReceived) {
            speedDataReceived = true
            Timber.d("Cold-start guard lifted: first speed data received (%.1f km/h)", speedKmh)
        }
        currentSpeedKmh = speedKmh
        speedLastUpdatedTime = now

        // Push to the state machine. gpsStale is computed against the wall clock.
        stateMachine.onSpeedUpdate(speedKmh, gpsStale = false)  // never stale at the moment of update
        speedDropMonitor.onSpeedUpdate(speedKmh)
    }

    fun updateCadence(cadenceRpm: Double) {
        if (!cadenceDataReceived) {
            cadenceDataReceived = true
            Timber.d("Cadence sensor online: first reading %.0f RPM", cadenceRpm)
        }
        currentCadence = cadenceRpm
        stateMachine.onCadenceUpdate(cadenceRpm)
    }

    fun updateGrade(gradePercent: Double) {
        val previous = currentGrade
        currentGrade = gradePercent
        // Grade-aware peak boost changes when grade crosses one of the discrete bands.
        if (gradeBoost(previous) != gradeBoost(gradePercent)) {
            rebuildThresholds(boostActive = clock.nowMs() < postImpactBoostUntil)
        }
    }

    fun updateRideProfile(routingPreference: String) {
        currentRoutingPreference = routingPreference
        Timber.d("Ride profile routing preference updated: $routingPreference")
    }

    fun resetSpeedDropOnPause() {
        speedDropMonitor.onPause()
        Timber.d("Speed-drop timer reset on ride pause")
    }

    // ─── Internal: per-sample callback from SensorReader ─────────────────────

    private fun onSensorSample(sample: SensorSample) {
        val now = sample.timestampMs

        // Speed staleness check, computed once per sample.
        val gpsCurrentlyStale = isGpsStale(now)
        if (gpsCurrentlyStale != lastGpsStaleState) {
            lastGpsStaleState = gpsCurrentlyStale
            if (gpsCurrentlyStale) {
                calibLogger?.log(CalibrationLogger.Event.GPS_STALE) {
                    "stale=true,since_ms=${now - speedLastUpdatedTime},last_speed=%.1f,state=${stateMachine.state}".format(currentSpeedKmh)
                }
            }
        }
        // Refresh the state machine's gpsStale view continuously — without this it would
        // only update on speed pings.
        stateMachine.onSpeedUpdate(currentSpeedKmh, gpsStale = gpsCurrentlyStale)

        // Periodic debug log (debug builds only).
        if (BuildConfig.DEBUG && now - lastLogTime > LOG_INTERVAL_MS) {
            lastLogTime = now
            Timber.v("Accel raw=%.2f smooth=%.2fm/s² state=%s thr=%.1f peak_thr=%.1f gyro=%.2f",
                sample.rawMagnitude, sample.smoothedMagnitude, stateMachine.state,
                cachedSmoothedThr, cachedEffectivePeakThr, sample.gyroMag)
        }

        // Recompute boost (timed cool-down) — when boost expires, drop the SM threshold.
        val boostActive = now < postImpactBoostUntil
        val effPeakNow = computeEffectivePeak(boostActive)
        if (abs(effPeakNow - cachedEffectivePeakThr) > 0.0001) {
            cachedEffectivePeakThr = effPeakNow
            stateMachine.setThresholds(buildThresholds(config, effectivePeakThr = effPeakNow))
        }

        // Cooldown gate: in MONITORING, if we're still inside the post-confirm cooldown
        // window the SM must not enter IMPACT. Pre-filter by suppressing the sample's
        // peak/smoothed/gyro magnitudes — this avoids the SM seeing a "spike" during
        // cooldown that would otherwise lead to a re-trigger immediately after the
        // user cancels.
        val cooldownOk = (now - lastCrashTime) > crashCooldownMs

        // Track the state before submitting the sample so we can detect transitions
        // and emit rich calibration events for them.
        //
        // priorImpactDetected reads the *raw* sample (not the cooldown-quieted version
        // built below). This is deliberate: the calibration log wants to know that a
        // would-be impact arrived during cooldown so the rider/dev can audit whether
        // the cooldown formula is too aggressive. If we ran sampleWouldImpact on the
        // quieted version, every cooldown sample would falsely look "non-impact".
        val priorState = stateMachine.state
        val priorImpactDetected = sampleWouldImpact(sample)

        val sampleForSm = if (priorState == CrashStateMachine.State.MONITORING && !cooldownOk) {
            // Replace with a "quiet" sample so the SM stays in MONITORING.
            sample.copy(
                rawMagnitude = 9.81,
                smoothedMagnitude = 9.81,
                peakMagnitude = 0.0,
                gyroMag = 0.0,
            )
        } else {
            sample
        }

        val decision = stateMachine.onSample(sampleForSm)

        // ─── Window-progress accumulators ───────────────────────────────────
        if (stateMachine.state == CrashStateMachine.State.IMPACT) {
            val deviation = abs(sample.rawMagnitude - GRAVITY)
            if (sample.smoothedMagnitude > maxSmoothedInWindow) maxSmoothedInWindow = sample.smoothedMagnitude
            if (currentSpeedKmh < minSpeedInWindow)  minSpeedInWindow  = currentSpeedKmh
            if (deviation < minDeviationInWindow)    minDeviationInWindow = deviation
            if (isSpeedDropConfirmed(now)) speedReachedInWindow = true
        }

        // ─── React to state transitions / decisions ──────────────────────────
        when (decision) {
            is CrashStateMachine.Decision.EnterImpact -> {
                logImpactEnter(sample, decision.reason, boostActive)
            }
            is CrashStateMachine.Decision.Confirm -> {
                logCrashConfirmed(sample)
                confirmCrash(CrashSource.IMPACT_CONFIRMED)
            }
            is CrashStateMachine.Decision.ReturnToMonitoring -> {
                handleReturnToMonitoring(priorState, sample, now)
            }
            CrashStateMachine.Decision.None -> {
                // Inside MONITORING: log HIGH_MAG_NORISING and IMPACT_SPEED_REJECTED.
                if (priorState == CrashStateMachine.State.MONITORING) {
                    handleMonitoringMisses(sample, priorImpactDetected, cooldownOk, now)
                }
                // Inside IMPACT: log GYRO_BLOCKED if accel quiet + speed dropped + gyro too high.
                else if (priorState == CrashStateMachine.State.IMPACT &&
                         stateMachine.state == CrashStateMachine.State.IMPACT) {
                    handleImpactGyroBlock(sample, now)
                }
                // Inside SILENCE_CHECK: log SILENCE_BROKEN / SILENCE_ENTER transition.
                if (priorState == CrashStateMachine.State.IMPACT &&
                    stateMachine.state == CrashStateMachine.State.SILENCE_CHECK) {
                    logSilenceEnter(sample)
                }
                if (priorState == CrashStateMachine.State.SILENCE_CHECK &&
                    stateMachine.state == CrashStateMachine.State.SILENCE_CHECK) {
                    handleSilenceBroken(sample, now)
                }
            }
        }

        // ─── Periodic ride-context snapshot ──────────────────────────────────
        if (calibLogger != null && calibLogger.isEnabled &&
            (now - lastPeriodicLogMs) > PERIODIC_LOG_INTERVAL_MS) {
            lastPeriodicLogMs = now
            val boostLeft = ((postImpactBoostUntil - now).coerceAtLeast(0L)) / 1000L
            val gBoost = gradeBoost(currentGrade)
            calibLogger.log(CalibrationLogger.Event.PERIODIC) {
                "state=${stateMachine.state},speed=%.1f,accel_dev=%.2f,gyro=%.2f,grade=%.1f,cadence=%.0f,noise=%.2f,profile=$currentRoutingPreference,preset=${config.crashSensitivity},thr=%.1f,pthr=%.1f,eff_pthr=%.1f,grade_boost=%.0f,min_spd=${config.minSpeedForCrashKmh},gps_stale=$gpsCurrentlyStale,boost_s_left=$boostLeft".format(
                    currentSpeedKmh, abs(sample.rawMagnitude - GRAVITY), sample.gyroMag,
                    currentGrade, currentCadence, sensorReader.accelStdDev(),
                    cachedSmoothedThr, cachedPeakThr, cachedEffectivePeakThr, gBoost
                )
            }
        }
    }

    private fun sampleWouldImpact(sample: SensorSample): Boolean =
        sample.peakMagnitude >= cachedEffectivePeakThr ||
        sample.smoothedMagnitude >= cachedSmoothedThr

    // ─── Calibration log emission ────────────────────────────────────────────

    private fun logImpactEnter(sample: SensorSample, reason: String, boostActive: Boolean) {
        resetWindowAccumulators(seedSmoothed = sample.smoothedMagnitude,
                                seedSpeed = currentSpeedKmh,
                                seedDeviation = abs(sample.rawMagnitude - GRAVITY))
        Timber.d(">>> IMPACT detected! raw=%.1f smooth=%.1fm/s² (thr=%.1f eff_peak_thr=%.1f boost=%b) speed=%.1fkm/h",
            sample.rawMagnitude, sample.smoothedMagnitude, cachedSmoothedThr,
            cachedEffectivePeakThr, boostActive, currentSpeedKmh)
        calibLogger?.log(CalibrationLogger.Event.IMPACT_ENTER) {
            val bufStr = sensorReader.magnitudeBufferSnapshot().joinToString("|") { "%.1f".format(it) }
            val gBoost = gradeBoost(currentGrade)
            "source=$reason,raw=%.1f,smooth=%.1f,thr=%.1f,pthr=%.1f,eff_pthr=%.1f,speed=%.1f,decel=%.1f,grade=%.1f,grade_boost=%.0f,cadence=%.0f,gyro=%.2f,buf=$bufStr,noise=%.2f,profile=$currentRoutingPreference,preset=${config.crashSensitivity},boost_active=$boostActive".format(
                sample.rawMagnitude, sample.smoothedMagnitude, cachedSmoothedThr,
                cachedPeakThr, cachedEffectivePeakThr, currentSpeedKmh, lastDecelerationKmhPerS,
                currentGrade, gBoost, currentCadence, sample.gyroMag, sensorReader.accelStdDev()
            )
        }
    }

    private fun logSilenceEnter(sample: SensorSample) {
        val deviation = abs(sample.rawMagnitude - GRAVITY)
        Timber.d(">>> SILENCE_CHECK started (deviation=%.2f gyro=%.2f speed=%.1fkm/h)",
            deviation, sample.gyroMag, currentSpeedKmh)
        calibLogger?.log(CalibrationLogger.Event.SILENCE_ENTER) {
            "deviation=%.2f,gyro=%.2f,speed=%.1f,gps_stale=${lastGpsStaleState}".format(
                deviation, sample.gyroMag, currentSpeedKmh)
        }
    }

    private fun logCrashConfirmed(sample: SensorSample) {
        val deviation = abs(sample.rawMagnitude - GRAVITY)
        val gpsStale = lastGpsStaleState
        val effectiveDevMax = if (gpsStale) GPS_STALE_DEVIATION_MAX else SILENCE_DEVIATION_MAX
        val effectiveSilenceMs = if (gpsStale) GPS_STALE_SILENCE_DURATION_MS else SILENCE_DURATION_MS
        Timber.d(">>> CRASH CONFIRMED (accel dev=%.2f speed=%.1fkm/h gyro=%.2f gpsStale=%b)",
            deviation, currentSpeedKmh, sample.gyroMag, gpsStale)
        calibLogger?.log(CalibrationLogger.Event.CRASH_CONFIRMED) {
            "deviation=%.2f,speed=%.1f,confirm_spd_thr=${config.crashConfirmSpeedKmh},grade=%.1f,cadence=%.0f,gps_stale=$gpsStale,preset=${config.crashSensitivity},effective_dev_max=$effectiveDevMax,effective_silence_ms=$effectiveSilenceMs,countdown_s=${config.countdownSeconds}".format(
                deviation, currentSpeedKmh, currentGrade, currentCadence)
        }
    }

    private fun handleReturnToMonitoring(
        priorState: CrashStateMachine.State,
        sample: SensorSample,
        now: Long,
    ) {
        val deviation = abs(sample.rawMagnitude - GRAVITY)
        val windowMs = cachedWindowMs

        if (priorState == CrashStateMachine.State.IMPACT) {
            // IMPACT_TIMEOUT — never settled before window expired.
            val whyNoSilence = when {
                !speedReachedInWindow -> "SPEED"
                minDeviationInWindow > SILENCE_DEVIATION_MAX -> "ACCEL"
                gyroBlockedCnt > 0 -> "GYRO"
                else -> "UNKNOWN"
            }
            // Cluster detection on rolling TMO queue
            recentTmoTimestamps.addLast(now)
            while (recentTmoTimestamps.isNotEmpty() &&
                   now - recentTmoTimestamps.first() > CLUSTER_WINDOW_MS) {
                recentTmoTimestamps.removeFirst()
            }
            val isCluster = recentTmoTimestamps.size >= CLUSTER_MIN_TMO
            val boostMs = if (isCluster) POST_CLUSTER_COOLDOWN_MS else POST_TMO_COOLDOWN_MS
            postImpactBoostUntil = now + boostMs
            // Force a threshold rebuild so the boost takes effect on the next sample.
            rebuildThresholds(boostActive = true)

            Timber.d("Impact window timeout (%dms) → false alarm, resetting", windowMs)
            calibLogger?.log(CalibrationLogger.Event.IMPACT_TIMEOUT) {
                "window_ms=$windowMs,speed=%.1f,gyro=%.2f,deviation=%.2f,grade=%.1f,cadence=%.0f,preset=${config.crashSensitivity},why_no_silence=$whyNoSilence,max_smooth=%.1f,min_spd=%.1f,min_dev=%.2f,boost_s=%.0f,cluster=$isCluster".format(
                    currentSpeedKmh, sample.gyroMag, deviation,
                    currentGrade, currentCadence,
                    maxSmoothedInWindow, minSpeedInWindow, minDeviationInWindow,
                    boostMs / 1000f, isCluster
                )
            }
            if (isCluster) {
                calibLogger?.log(CalibrationLogger.Event.TERRAIN_CLUSTER) {
                    "count=${recentTmoTimestamps.size},window_s=${CLUSTER_WINDOW_MS / 1000},boost_s=${boostMs / 1000}"
                }
            }
            resetWindowAccumulators()
            schedulePostResetSnapshot("IMPACT_TMO")
        } else if (priorState == CrashStateMachine.State.SILENCE_CHECK) {
            // Two sub-paths:
            //   1. Cadence gate fired (rider pedalling > 20 RPM) — instant exit.
            //   2. Silence never achieved within doubled window — SILENCE_TIMEOUT.
            val isPedalling = cadenceDataReceived && currentCadence > CADENCE_QUIET_RPM
            if (isPedalling) {
                Timber.d("CADENCE gate: rider pedalling %.0f RPM during SILENCE_CHECK → not a crash, resetting",
                    currentCadence)
                calibLogger?.log(CalibrationLogger.Event.CADENCE_GATE) {
                    "cadence=%.0f,speed=%.1f,deviation=%.2f,grade=%.1f".format(
                        currentCadence, currentSpeedKmh, deviation, currentGrade)
                }
            } else {
                Timber.d("Silence never achieved → false alarm, resetting")
                calibLogger?.log(CalibrationLogger.Event.SILENCE_TIMEOUT) {
                    val effDev = if (lastGpsStaleState) GPS_STALE_DEVIATION_MAX else SILENCE_DEVIATION_MAX
                    "window_ms=$windowMs,deviation=%.2f,eff_max=$effDev,speed=%.1f,gps_stale=${lastGpsStaleState},preset=${config.crashSensitivity}".format(
                        deviation, currentSpeedKmh)
                }
                schedulePostResetSnapshot("SIL_TMO")
            }
            resetWindowAccumulators()
        }
    }

    private fun handleMonitoringMisses(
        sample: SensorSample, impactDetected: Boolean, cooldownOk: Boolean, now: Long
    ) {
        if (!cooldownOk) return  // suppressed during cooldown, don't spam logs

        val minSpeed = config.minSpeedForCrashKmh
        val speedOk = minSpeed == 0 || currentSpeedKmh >= minSpeed
        when {
            // Crossed threshold but speed gate rejected.
            impactDetected && !speedOk -> {
                calibLogger?.log(CalibrationLogger.Event.IMPACT_SPEED_REJECTED) {
                    "raw=%.1f,smooth=%.1f,thr=%.1f,speed=%.1f,min_speed=$minSpeed,preset=${config.crashSensitivity}".format(
                        sample.rawMagnitude, sample.smoothedMagnitude, cachedSmoothedThr, currentSpeedKmh)
                }
            }
            // High magnitude under both thresholds — terrain noise distribution. Rate-limited 1/s.
            !impactDetected && sample.rawMagnitude > CalibrationLogger.HIGH_MAG_MIN -> {
                if ((now - lastHighMagLogMs) > CalibrationLogger.HIGH_MAG_INTERVAL_MS) {
                    lastHighMagLogMs = now
                    val gBoost = gradeBoost(currentGrade)
                    val boostActive = now < postImpactBoostUntil
                    calibLogger?.log(CalibrationLogger.Event.HIGH_MAG_NORISING) {
                        "raw=%.1f,smooth=%.1f,thr=%.1f,pthr=%.1f,eff_pthr=%.1f,speed=%.1f,gyro=%.2f,grade=%.1f,grade_boost=%.0f,preset=${config.crashSensitivity},boost=$boostActive".format(
                            sample.rawMagnitude, sample.smoothedMagnitude, cachedSmoothedThr,
                            cachedPeakThr, cachedEffectivePeakThr, currentSpeedKmh, sample.gyroMag,
                            currentGrade, gBoost)
                    }
                }
            }
        }
    }

    private fun handleImpactGyroBlock(sample: SensorSample, now: Long) {
        val deviation = abs(sample.rawMagnitude - GRAVITY)
        if (deviation < SILENCE_DEVIATION_MAX &&
            sample.gyroMag >= GYRO_MOVING_MAX &&
            isSpeedDropConfirmed(now)) {
            gyroBlockedCnt++
            if ((now - lastGyroBlockedLogMs) > CalibrationLogger.GYRO_BLOCKED_INTERVAL_MS) {
                lastGyroBlockedLogMs = now
                calibLogger?.log(CalibrationLogger.Event.GYRO_BLOCKED) {
                    "gyro=%.2f,gyro_max=$GYRO_MOVING_MAX,deviation=%.2f,speed=%.1f".format(
                        sample.gyroMag, deviation, currentSpeedKmh)
                }
            }
        }
    }

    private fun handleSilenceBroken(sample: SensorSample, now: Long) {
        val deviation = abs(sample.rawMagnitude - GRAVITY)
        val effDev = if (lastGpsStaleState) GPS_STALE_DEVIATION_MAX else SILENCE_DEVIATION_MAX
        if (deviation > effDev) {
            if (now - lastSilenceBrokenMs > CalibrationLogger.SILENCE_BROKEN_INTERVAL_MS) {
                lastSilenceBrokenMs = now
                calibLogger?.log(CalibrationLogger.Event.SILENCE_BROKEN) {
                    "deviation=%.2f,eff_max=$effDev,speed=%.1f,gps_stale=${lastGpsStaleState}".format(
                        deviation, currentSpeedKmh)
                }
            }
        }
    }

    // ─── Threshold management ────────────────────────────────────────────────

    private fun smoothedThresholdFor(cfg: KSafeConfig): Double =
        if (cfg.crashSensitivity == CrashSensitivity.CUSTOM)
            cfg.customCrashThreshold.toDouble().coerceIn(20.0, 70.0)
        else
            impactThresholds[cfg.crashSensitivity] ?: 45.0

    private fun peakThresholdFor(cfg: KSafeConfig): Double =
        if (cfg.crashSensitivity == CrashSensitivity.CUSTOM)
            (cfg.customCrashThreshold.toDouble() * 1.3).coerceIn(25.0, 80.0)
        else
            peakImpactThresholds[cfg.crashSensitivity] ?: 60.0

    private fun gradeBoost(grade: Double): Double = when {
        grade < -10.0 -> 8.0
        grade < -7.0 -> 5.0
        grade < -4.0 -> 2.0
        else -> 0.0
    }

    private fun computeEffectivePeak(boostActive: Boolean): Double {
        val base = cachedPeakThr
        val withTmo = if (boostActive) base + POST_TMO_BOOST else base
        return withTmo + gradeBoost(currentGrade)
    }

    private fun rebuildThresholds(boostActive: Boolean) {
        cachedSmoothedThr = smoothedThresholdFor(config)
        cachedPeakThr     = peakThresholdFor(config)
        cachedWindowMs    = impactWindowMs[config.crashSensitivity] ?: 20_000L
        cachedEffectivePeakThr = computeEffectivePeak(boostActive)
        stateMachine.setThresholds(buildThresholds(config, effectivePeakThr = cachedEffectivePeakThr))
    }

    private fun buildThresholds(cfg: KSafeConfig, effectivePeakThr: Double): Thresholds {
        // Apply the crashMonitorOutsideRideAnySpeed override: when enabled the caller can
        // pass `cfg.copy(minSpeedForCrashKmh = 0)` directly, which is what KSafeExtension
        // already does in applyIdleMonitoring. We honour whatever cfg has at this point.
        return Thresholds(
            smoothedImpactThreshold = smoothedThresholdFor(cfg),
            peakImpactThreshold = effectivePeakThr,
            gyroImpactThreshold = GYRO_IMPACT_THRESHOLD,
            silenceDeviationMax = SILENCE_DEVIATION_MAX,
            silenceDurationMs = SILENCE_DURATION_MS,
            gyroMovingMax = GYRO_MOVING_MAX,
            gpsStaleSilenceDeviationMax = GPS_STALE_DEVIATION_MAX,
            gpsStaleSilenceDurationMs = GPS_STALE_SILENCE_DURATION_MS,
            impactWindowMs = impactWindowMs[cfg.crashSensitivity] ?: 20_000L,
            minTimeSinceImpactMs = MIN_TIME_SINCE_IMPACT_MS,
            minSpeedForCrashKmh = cfg.minSpeedForCrashKmh,
            crashConfirmSpeedKmh = cfg.crashConfirmSpeedKmh,
            coldStartGuardMs = COLD_START_GUARD_MS,
            gpsStaleThresholdMs = GPS_STALE_MS,
            cadenceQuietThresholdRpm = CADENCE_QUIET_RPM,
            cadenceStaleThresholdMs = CADENCE_STALE_MS,
        )
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun isGpsStale(now: Long): Boolean =
        speedLastUpdatedTime > 0 && (now - speedLastUpdatedTime) > GPS_STALE_MS

    private fun isSpeedDropConfirmed(now: Long): Boolean {
        if (!speedDataReceived && (now - startTime) < COLD_START_GUARD_MS) return false
        if (isGpsStale(now)) return true
        return config.crashConfirmSpeedKmh == 0 || currentSpeedKmh < config.crashConfirmSpeedKmh.toDouble()
    }

    private fun resetWindowAccumulators(
        seedSmoothed: Double = 0.0,
        seedSpeed: Double = Double.MAX_VALUE,
        seedDeviation: Double = Double.MAX_VALUE,
    ) {
        maxSmoothedInWindow  = seedSmoothed
        minSpeedInWindow     = seedSpeed
        minDeviationInWindow = seedDeviation
        gyroBlockedCnt       = 0
        speedReachedInWindow = false
    }

    private fun schedulePostResetSnapshot(cancelledBy: String) {
        if (calibLogger == null || !calibLogger.isEnabled) return
        scope.launch {
            delay(2_000L)
            calibLogger.log(CalibrationLogger.Event.POST_RESET_SNAP) {
                "cancelled_by=$cancelledBy,speed=%.1f,gyro=%.2f,state=${stateMachine.state},gps_stale=${isGpsStale(clock.nowMs())}".format(
                    currentSpeedKmh, sensorReader.lastGyroMag)
            }
        }
    }

    /**
     * Single exit for every confirmation path. Applies cooldown and updates [lastCrashTime].
     */
    private fun confirmCrash(source: CrashSource) {
        val now = clock.nowMs()
        if ((now - lastCrashTime) <= crashCooldownMs) {
            Timber.d("Confirm $source suppressed — within cooldown window")
            calibLogger?.log(CalibrationLogger.Event.CRASH_CONFIRMED) {
                "source=$source,suppressed_by=cooldown,since_last_ms=${now - lastCrashTime}"
            }
            return
        }
        lastCrashTime = now
        calibLogger?.log(CalibrationLogger.Event.CRASH_CONFIRMED) { "source=$source" }
        scope.launch { onCrashDetected() }
    }
}
