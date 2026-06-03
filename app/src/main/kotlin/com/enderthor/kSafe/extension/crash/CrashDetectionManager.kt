package com.enderthor.kSafe.extension.crash

import android.content.Context
import android.hardware.SensorManager
import com.enderthor.kSafe.BuildConfig
import com.enderthor.kSafe.data.CrashSensitivity
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.extension.managers.CalibrationLogger
import com.enderthor.kSafe.extension.util.Clock
import com.enderthor.kSafe.extension.util.SystemClock
import com.enderthor.kSafe.extension.util.formatUs
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
     *
     * **History note:** a v2.1 candidate widened these by +5..+8 m/s² in response to FP
     * reports. The reports were re-attributed to a v1.2.0-era bug (gyro entry path, fixed
     * in commit ab69a40 before v2.0.0) so the widening was reverted — v2.0.0 already
     * shipped without the gyro entry path that produced those FPs. Future tuning should
     * wait for a v2.0+ FP log with the (now-working) calibration logger so the change
     * is targeted at the actual trigger path (peak vs smooth vs gyro-stale-fix corner case)
     * rather than guesswork.
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
        // "No crash yet / cooldown inactive" sentinel for [lastCrashTime]. The cooldown
        // gate is monotonic — `(clock.monotonicMs() - lastCrashTime) > crashCooldownMs`
        // — and `monotonicMs()` is `elapsedRealtime()`, which is SMALL right after boot.
        // A `0L` sentinel therefore reads as "cooldown active" for the first
        // `crashCooldownMs` of device uptime (`now - 0 <= cooldown`), suppressing a real
        // impact in that window. A large-negative sentinel makes `now - sentinel` always
        // exceed any cooldown, so the gate is correctly OPEN until a real confirmation
        // stamps `lastCrashTime`. Half of MIN_VALUE keeps `now - sentinel` clear of
        // Long overflow for any plausible `elapsedRealtime()`.
        const val COOLDOWN_INACTIVE = Long.MIN_VALUE / 2
        // Single source of truth: alias the public constant on [SensorReader] (used as its
        // default constructor argument and exposed for tests). Both the manager-side
        // boundary checks and the SensorReader's per-sample variance-buffer reference now
        // dereference the same const, so they can never drift apart.
        val SILENCE_DEVIATION_MAX = SensorReader.SILENCE_DEVIATION_MAX
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
        // Was 3 in v2.0 — lowered to 2 in v2.1 so the cluster boost engages earlier on
        // genuinely rough terrain (bikepark, MTB descents). Two confirmed-then-timed-out
        // impacts within 2 min is already a strong "the terrain is launching the
        // accelerometer over the threshold without a real crash" signal. The boost is
        // additive (+8 m/s² to peak threshold for the cooldown window) and the smoothed
        // path is unchanged, so a real crash on rough terrain still triggers through the
        // sustained-impact path.
        const val CLUSTER_MIN_TMO = 2

        // ── Orientation / silence-duration tuning ────────────────────────────
        /** Minimum impact→stillness gap (ms) that triggers the delayed-stop regime
         *  (20 s upright window). Matches the default in [Thresholds.delayedStopGapMs]. */
        const val DELAYED_STOP_GAP_MS = 8_000L

        /** Silence-window duration (ms) when the bike is assessed as upright after
         *  the impact (orientation regime). Matches [Thresholds.silenceDurationUprightMs]. */
        const val SILENCE_DURATION_UPRIGHT_MS = 20_000L

        /** Angle threshold (degrees) above which the current gravity vector is
         *  classified as on-side (short window); below it the bike is deemed upright
         *  (long window). Matches [Thresholds.uprightAngleThresholdDegrees]. */
        const val UPRIGHT_ANGLE_THRESHOLD_DEGREES = 45.0

        /** Angle (deg) below which the GAP-regime confirm is vetoed (R6-F). A tight
         *  cone — a veto suppresses an SOS, and an FN is worse than an FP. Matches
         *  [Thresholds.gapVetoUprightAngleDeg]. */
        const val GAP_VETO_UPRIGHT_ANGLE_DEG = 15.0

        /** Peak gyro (rad/s) below which the non-gap (prompt-stop) upright veto (R6-G)
         *  may engage — distinguishes a benign stand from an endo that ends upright.
         *  Matches [Thresholds.nonGapUprightVetoMaxGyroRadS]. */
        const val NON_GAP_UPRIGHT_VETO_MAX_GYRO_RAD_S = 3.0

        /** Angle (deg) above which the SILENCE_CHECK speed-rise relaxation engages. */
        const val ON_SIDE_RELAXATION_ANGLE_DEG = 60.0

        /** Speed (km/h) ceiling above which the IMPACT-phase on-side relaxation does
         *  NOT fire — see [Thresholds.onSideRelaxationMaxSpeedKmh] for the full
         *  motivation. Briefly: the relaxation is meant for "bike rolled after the
         *  rider went down", which is incompatible with sustained 25+ km/h. The FP
         *  at elapsed 14141.2 s on the 2026-05-25 ride sat at 34.7 km/h sustained
         *  through 7+ s of "silence" — clear separation from the four real falls in
         *  the same ride, all under 15 km/h. GPS-stale bypasses the ceiling. */
        const val ON_SIDE_RELAXATION_MAX_SPEED_KMH = 25.0

        // ── CrashStateMachine "sample timestamp" base ────────────────────────
        // The state machine treats `sample.timestampMs` as the authoritative time
        // for IMPACT/SILENCE windows. We pass wall-clock so production semantics
        // (e.g. timeSinceImpact) match the monolith verbatim.
    }

    // ─── State ────────────────────────────────────────────────────────────────

    @Volatile private var config = KSafeConfig()
    // `internal` (was `private`) so unit tests can pin the cooldown contract — see
    // `clearCrashCooldown` and CrashDetectionManagerWiringTest. The field still has
    // no public API surface (module-internal at runtime). No production caller reads
    // or writes it from outside this class.
    @Volatile internal var lastCrashTime = COOLDOWN_INACTIVE
    @Volatile private var currentSpeedKmh = 0.0
    /** Timestamp of the most recent [updateSpeed] emission — any emission, even one that
     *  carries the same value as the previous one. Used for dv/dt math in [updateSpeed]. */
    @Volatile private var speedLastEmissionMs = 0L
    /** Timestamp of the most recent emission where the speed value actually CHANGED,
     *  plus a whitelist for explicit-zero emissions (rider stopped at lights with GPS
     *  still alive). [isGpsStale] keys off this — the SDK returns the LAST known speed
     *  bit-exact when GPS lock is lost, so a stretch of identical non-zero emissions
     *  is the tell-tale sign of stale GPS. Replaces the old `speedLastUpdatedTime` which
     *  was stamped on every emission and therefore could never trip the staleness check. */
    @Volatile private var speedLastChangeMs = 0L
    @Volatile private var speedDataReceived = false
    @Volatile private var startTime = 0L
    @Volatile private var lastDecelerationKmhPerS = 0.0

    @Volatile private var currentCadence = 0.0
    @Volatile private var cadenceDataReceived = false
    @Volatile private var currentGrade = 0.0
    @Volatile private var currentRoutingPreference = "ROAD"

    // ─── Boost & calibration tracking ────────────────────────────────────────
    @Volatile private var postImpactBoostUntil = 0L
    // Cluster-detection rolling queue. CO1 fix — accessed from the sensor thread
    // (addLast/first/removeFirst/size inside the IMPACT_TIMEOUT branch) AND from Main
    // (clear() in start/stop/resume lifecycle methods). All mutations and reads MUST be
    // wrapped in `synchronized(recentTmoTimestamps) { ... }` to avoid a torn-state read
    // that would silently corrupt the cluster bookkeeping (cluster detection misfiring or
    // missing). The sensor-thread try/catch in SensorReader would mask the symptom.
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

    // `internal` (was `private`) so unit tests can drive the state machine into IMPACT /
    // SILENCE_CHECK and assert the facade's onPause(auto) preservation logic — see
    // CrashDetectionManagerWiringTest. Read-only from outside this class.
    internal val stateMachine = CrashStateMachine(
        thresholds = buildThresholds(config, effectivePeakThr = cachedEffectivePeakThr),
        clock = clock,
    )

    private val speedDropMonitor = SpeedDropMonitor(
        scope = scope,
        clock = clock,
        accelStillSinceProvider = { sensorReader.accelStillSinceMs },
        cooldownGate = { (clock.monotonicMs() - lastCrashTime) > crashCooldownMs },
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
        synchronized(recentTmoTimestamps) { recentTmoTimestamps.clear() }
        resetWindowAccumulators()

        rebuildThresholds(boostActive = false)
        stateMachine.reset()
        sensorReader.start(handler = null)

        Timber.d("CrashDetectionManager STARTED (sensitivity=${config.crashSensitivity}, threshold=${cachedSmoothedThr}m/s²)")
        if (config.speedDropDetectionEnabled) speedDropMonitor.start(config.speedDropMinutes)
    }

    /**
     * Resume crash detection after a ride pause. Equivalent to [start] for the
     * current implementation; kept as a distinct entry point so the facade can
     * express "ride resume" intent (vs a fresh ride) and route the state
     * machine through [CrashStateMachine.resumeForRide].
     */
    fun resume(config: KSafeConfig) {
        this.config = config
        if (!config.crashDetectionEnabled) {
            Timber.d("CrashDetection disabled in config, skipping resume")
            return
        }
        startTime = clock.nowMs()
        speedDataReceived = false
        lastPeriodicLogMs = 0L
        lastLoggedSensitivity = config.crashSensitivity
        postImpactBoostUntil = 0L
        synchronized(recentTmoTimestamps) { recentTmoTimestamps.clear() }
        resetWindowAccumulators()
        rebuildThresholds(boostActive = false)
        // If the state machine is mid-IMPACT or mid-SILENCE_CHECK at resume time
        // (auto-resume during an in-flight crash detection — the symmetric case
        // to I3's autopause preservation), do NOT reset it. The accelerometer
        // pipeline has been running through the pause; the in-flight detection
        // must be allowed to complete and confirm. After a manual pause the
        // state machine was wiped by onPause(auto=false) and is already in
        // MONITORING, so this conditional is a no-op for that case.
        if (stateMachine.state != CrashStateMachine.State.MONITORING) {
            // I-NEW-1: the SM carries its prior [lastSpeedGpsStale] across the pause.
            // If the facade reset its own [lastGpsStaleState] to a fixed value here,
            // the transition gate in [onSensorSample] (`gpsCurrentlyStale != lastGpsStaleState`)
            // could evaluate false on the first post-resume sample and the SM would
            // never get a fresh push — leaving it in stale-mode (8 s silence window)
            // when GPS is actually fresh (4.5 s window). Re-read the current staleness
            // and push it into the SM so both views are coherent from sample 1.
            val staleNow = isGpsStale(clock.nowMs())
            lastGpsStaleState = staleNow
            stateMachine.setSpeedGpsStale(staleNow)
            Timber.d("CrashDetectionManager RESUMED — preserving in-flight ${stateMachine.state} (auto-resume mid-crash), re-synced gps_stale=$staleNow")
        } else {
            // Fresh-monitoring branch: the SM is about to be reset, so it correctly
            // starts with `lastSpeedGpsStale = false`. The facade flag follows suit.
            lastGpsStaleState = false
            stateMachine.resumeForRide()
            Timber.d("CrashDetectionManager RESUMED — state machine reset (manual-pause resume or no in-flight detection)")
        }
        sensorReader.start(handler = null)
        if (config.speedDropDetectionEnabled) speedDropMonitor.start(config.speedDropMinutes)
    }

    fun stop() {
        sensorReader.stop()
        speedDropMonitor.stop()
        stateMachine.reset()
        resetWindowAccumulators()
        // Clear the rolling TMO-cluster deque (cleared in start()/resume() too) so a
        // stop leaves no stale cluster state behind.
        synchronized(recentTmoTimestamps) { recentTmoTimestamps.clear() }
        // Clear the post-confirmation cooldown gate. Otherwise a rider who disables crash
        // detection mid-cooldown then re-enables it within ~30 s would have a legitimate
        // impact suppressed by stale cooldown state from the previous session.
        lastCrashTime = COOLDOWN_INACTIVE
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
        // D6/G2 fix — drop NaN AND Infinity samples. NaN equality is non-reflexive
        // (`NaN != currentSpeedKmh` is always true) so a single NaN would stamp
        // speedLastChangeMs forever and disable GPS-stale detection. Infinity passes
        // the NaN guard but latches `lastSpeedKmh = Infinity` in the state machine,
        // making `lastSpeedKmh < crashConfirmSpeedKmh` always false → IMPACT can't
        // transition to SILENCE_CHECK via the speed-drop branch until the GPS-stale
        // fallback engages 10 s later.
        if (!speedKmh.isFinite()) return
        val now = clock.nowMs()
        val elapsed = if (speedLastEmissionMs > 0) (now - speedLastEmissionMs) / 1000.0 else 0.0
        if (elapsed >= 0.2 && speedDataReceived) {
            lastDecelerationKmhPerS = (speedKmh - currentSpeedKmh) / elapsed
        }
        if (!speedDataReceived) {
            speedDataReceived = true
            Timber.d("Cold-start guard lifted: first speed data received (%.1f km/h)", speedKmh)
        }
        val changed = speedKmh != currentSpeedKmh
        currentSpeedKmh = speedKmh
        speedLastEmissionMs = now
        // Stamp speedLastChangeMs on real value changes, on explicit-zero emissions
        // (rider stopped — GPS still alive even if value is bit-exact 0.0 across
        // emissions), and on the very first emission (bootstrap). A stuck non-zero
        // value across emissions is exactly the SDK's GPS-lost behaviour, so leaving
        // speedLastChangeMs untouched is what trips [isGpsStale] after GPS_STALE_MS.
        if (changed || speedKmh == 0.0 || speedLastChangeMs == 0L) speedLastChangeMs = now

        // Push to the state machine — this is the ONLY path that marks "real speed update
        // received", which the cold-start guard keys off. The staleness view is pushed
        // separately to the SM via `stateMachine.setSpeedGpsStale` on transition (see
        // [onSensorSample]). [SensorSample] no longer carries a per-tick `gpsStale` field
        // as of P1 — the SM holds its own `lastSpeedGpsStale` flag updated by that setter.
        stateMachine.onSpeedUpdate(speedKmh)
        speedDropMonitor.onSpeedUpdate(speedKmh, isGpsStale(now))
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

    /**
     * Clear the crash cooldown so the next genuine crash is not suppressed.
     *
     * `lastCrashTime` is stamped by [confirmCrash] at confirmation and gates a
     * ~60 s cooldown that de-duplicates the accelerometer pipeline against the
     * speed-drop watchdog. When the rider CANCELS a crash-triggered countdown no
     * alert is sent — there is nothing to de-duplicate — so the cooldown must be
     * dropped, otherwise a real crash within the window is silently suppressed.
     * A false positive and a real crash can be correlated (same rough descent),
     * so this is a reachable false-negative path.
     */
    fun clearCrashCooldown() {
        lastCrashTime = COOLDOWN_INACTIVE
    }

    /**
     * Handle a ride pause. [auto] is `RideState.Paused.auto` — the Karoo SDK's flag
     * for an automatic pause (speed reached 0) vs a manual pause (rider tapped pause).
     *
     * The pre-impact vector ring is always invalidated (a floor timestamp so samples
     * captured before the pause are ignored) — harmless to an in-flight event, whose
     * pre-impact reference was already captured at IMPACT entry, and correct for a
     * café-stop autopause so a later impact does not average pre-pause samples.
     *
     * On a **manual** pause the rider deliberately stopped — conscious and fine — so
     * the in-flight IMPACT/SILENCE_CHECK state is wiped (`stateMachine.onPause()`).
     *
     * On an **automatic** pause the bike stopped on its own, which is exactly what a
     * real crash does. The in-flight state is NOT wiped: the state machine keeps
     * running on the always-on accelerometer stream so a crash-in-progress confirms
     * during the pause. Without this, an autopause (~3-6 s after speed hits 0) would
     * erase the detection of the very crash that caused the stop.
     */
    fun onPause(auto: Boolean) {
        sensorReader.invalidateVectorRing()
        if (auto) {
            Timber.d("CrashDetectionManager: autopause — in-flight detection preserved")
        } else {
            stateMachine.onPause()
            Timber.d("CrashDetectionManager: manual pause — state machine reset")
        }
    }

    // ─── Internal: per-sample callback from SensorReader ─────────────────────

    private fun onSensorSample(rawSample: SensorSample) {
        val now = rawSample.timestampMs

        // Speed staleness check, computed once per sample.
        val gpsCurrentlyStale = isGpsStale(now)
        if (gpsCurrentlyStale != lastGpsStaleState) {
            lastGpsStaleState = gpsCurrentlyStale
            // P1 — push the new staleness view to the SM only on transition; otherwise
            // the per-sample volatile write is wasted bandwidth. The SM reads the field
            // lazily inside IMPACT / SILENCE_CHECK only.
            stateMachine.setSpeedGpsStale(gpsCurrentlyStale)
            if (gpsCurrentlyStale) {
                calibLogger?.log(CalibrationLogger.Event.GPS_STALE) {
                    "stale=true,since_ms=${now - speedLastChangeMs},last_speed=%.1f,state=${stateMachine.state}".formatUs(currentSpeedKmh)
                }
            }
        }
        // P1 — no per-tick sample.copy. Use rawSample directly; the SM picks up
        // staleness from the volatile field set on transition above.
        val sample = rawSample

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
        //
        // D2 fix — uses [Clock.monotonicMs] (elapsedRealtime on Android) instead of
        // sample.timestampMs / clock.nowMs (wall-clock). Without this, an NTP step
        // mid-ride or a manual date change can make (now - lastCrashTime) suddenly
        // exceed crashCooldownMs and lift the cooldown early (duplicate emergency)
        // or go negative and extend it (briefly suppressed). The cooldown is purely
        // in-memory so monotonic time is the right domain.
        val cooldownOk = (clock.monotonicMs() - lastCrashTime) > crashCooldownMs

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

        // ─── Diagnostic: CAD_GATE suppression (FN fix, 2026-05-25) ──────────
        // The state machine reports per-sample whether CAD_GATE was about to fire
        // but was suppressed by the on-side orientation evidence. Log this once
        // per occurrence so calibration data shows the suppression context (the
        // decision and the values at the moment of suppression).
        if (stateMachine.lastCadenceGateSuppressed) {
            calibLogger?.log(CalibrationLogger.Event.CADENCE_GATE_SUPPRESSED) {
                // Use the LIVE angle the state machine captured at suppression
                // time (set just before `lastCadenceGateSuppressed = true`).
                // `lastOrientationAngleDeg` is the latched silence-window value,
                // which is still -1.0 on the very first suppression sample
                // because `computeEffectiveSilenceMs` (which writes the latch)
                // runs AFTER the cadence-gate branch in `handleSilenceCheck`.
                val angle = stateMachine.lastCadenceGateSuppressedAngleDeg
                val dev = abs(sample.rawMagnitude - GRAVITY)
                "cadence=%.0f,speed=%.1f,deviation=%.2f,grade=%.1f,angle=%.1f,upright_thr=${stateMachine.thresholds.uprightAngleThresholdDegrees}".formatUs(
                    currentCadence, currentSpeedKmh, dev, currentGrade, angle)
            }
        }

        // ─── Diagnostic: GAP-regime upright veto (R6-F, FP #2 fix) ──────────
        // A delayed stop reached the 20 s confirm gate but orientation showed the
        // bike decisively upright → the gap regime's confirm was vetoed (benign
        // stop, not a crash). Logged once per occurrence so calibration data can
        // count vetoes vs CRASH_OK and catch any real-crash FN (paired MANUAL_SOS).
        if (stateMachine.lastGapUprightVeto) {
            calibLogger?.log(CalibrationLogger.Event.GAP_UPRIGHT_VETO) {
                val dev = abs(sample.rawMagnitude - GRAVITY)
                // regime=GAP (R6-F delayed stop) vs PROMPT (R6-G prompt stop). gyro_peak is
                // the impact→silence rotation that passed the PROMPT gyro gate (gyro_thr);
                // on a GAP veto it is informational only (the gap regime ignores rotation).
                val regime = if (stateMachine.lastUprightVetoGapRegime) "GAP" else "PROMPT"
                "angle=%.1f,veto_thr=${stateMachine.thresholds.gapVetoUprightAngleDeg},regime=$regime,gyro_peak=%.2f,gyro_thr=${stateMachine.thresholds.nonGapUprightVetoMaxGyroRadS},speed=%.1f,deviation=%.2f,cadence=%.0f,grade=%.1f,preset=${config.crashSensitivity}".formatUs(
                    stateMachine.lastGapUprightVetoAngleDeg, stateMachine.peakGyroSinceImpactRadS, currentSpeedKmh, dev, currentCadence, currentGrade)
            }
        }

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
                // Capture the ~2 s pre-impact orientation reference and hand it to
                // the state machine before the SILENCE_CHECK phase consumes it.
                stateMachine.setPreImpactReference(
                    sensorReader.preImpactReference(sample.timestampMs)
                )
                logImpactEnter(sample, decision.reason, boostActive)
            }
            is CrashStateMachine.Decision.Confirm -> {
                logCrashConfirmed(sample)
                // alreadyLogged=true: logCrashConfirmed already emitted the canonical
                // CRASH_CONFIRMED row with full context. confirmCrash must NOT emit a
                // duplicate (gate-level) CRASH_CONFIRMED for the IMPACT_CONFIRMED path
                // or downstream consumers that count crashes will double-count.
                confirmCrash(CrashSource.IMPACT_CONFIRMED, alreadyLogged = true)
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
                "state=${stateMachine.state},speed=%.1f,accel_dev=%.2f,gyro=%.2f,grade=%.1f,cadence=%.0f,noise=%.2f,profile=$currentRoutingPreference,preset=${config.crashSensitivity},thr=%.1f,pthr=%.1f,eff_pthr=%.1f,grade_boost=%.0f,min_spd=${config.minSpeedForCrashKmh},gps_stale=$gpsCurrentlyStale,boost_s_left=$boostLeft".formatUs(
                    currentSpeedKmh, abs(sample.rawMagnitude - GRAVITY), sample.gyroMag,
                    currentGrade, currentCadence, sensorReader.accelStdDev(),
                    cachedSmoothedThr, cachedPeakThr, cachedEffectivePeakThr, gBoost
                )
            }
        }
    }

    // Strict `>` matches the SM entry comparator (see CrashStateMachine.handleMonitoring
    // and commit 08d8e91). With `>=` the IMPACT_SPEED_REJECTED audit log would fire at
    // exact-equality boundaries where the SM did not actually treat the sample as an impact.
    private fun sampleWouldImpact(sample: SensorSample): Boolean =
        sample.peakMagnitude > cachedEffectivePeakThr ||
        sample.smoothedMagnitude > cachedSmoothedThr

    // ─── Calibration log emission ────────────────────────────────────────────

    private fun logImpactEnter(sample: SensorSample, reason: String, boostActive: Boolean) {
        resetWindowAccumulators(seedSmoothed = sample.smoothedMagnitude,
                                seedSpeed = currentSpeedKmh,
                                seedDeviation = abs(sample.rawMagnitude - GRAVITY))
        Timber.d(">>> IMPACT detected! raw=%.1f smooth=%.1fm/s² (thr=%.1f eff_peak_thr=%.1f boost=%b) speed=%.1fkm/h",
            sample.rawMagnitude, sample.smoothedMagnitude, cachedSmoothedThr,
            cachedEffectivePeakThr, boostActive, currentSpeedKmh)
        calibLogger?.log(CalibrationLogger.Event.IMPACT_ENTER) {
            val bufStr = sensorReader.magnitudeBufferSnapshot().joinToString("|") { "%.1f".formatUs(it) }
            val gBoost = gradeBoost(currentGrade)
            val ref = stateMachine.preImpactReference
            "source=$reason,raw=%.1f,smooth=%.1f,thr=%.1f,pthr=%.1f,eff_pthr=%.1f,speed=%.1f,decel=%.1f,grade=%.1f,grade_boost=%.0f,cadence=%.0f,gyro=%.2f,buf=$bufStr,noise=%.2f,profile=$currentRoutingPreference,preset=${config.crashSensitivity},boost_active=$boostActive,ax=%.2f,ay=%.2f,az=%.2f,pre_valid=${ref.valid},pre_x=%.2f,pre_y=%.2f,pre_z=%.2f".formatUs(
                sample.rawMagnitude, sample.smoothedMagnitude, cachedSmoothedThr,
                cachedPeakThr, cachedEffectivePeakThr, currentSpeedKmh, lastDecelerationKmhPerS,
                currentGrade, gBoost, currentCadence, sample.gyroMag, sensorReader.accelStdDev(),
                sample.accelX, sample.accelY, sample.accelZ, ref.x, ref.y, ref.z
            )
        }
    }

    private fun logSilenceEnter(sample: SensorSample) {
        val deviation = abs(sample.rawMagnitude - GRAVITY)
        Timber.d(">>> SILENCE_CHECK started (deviation=%.2f gyro=%.2f speed=%.1fkm/h)",
            deviation, sample.gyroMag, currentSpeedKmh)
        calibLogger?.log(CalibrationLogger.Event.SILENCE_ENTER) {
            val ref = stateMachine.preImpactReference
            "deviation=%.2f,gyro=%.2f,speed=%.1f,gps_stale=${lastGpsStaleState},gap_ms=${stateMachine.firstSilenceGapMs},pre_valid=${ref.valid},pre_x=%.2f,pre_y=%.2f,pre_z=%.2f".formatUs(
                deviation, sample.gyroMag, currentSpeedKmh, ref.x, ref.y, ref.z)
        }
    }

    private fun logCrashConfirmed(sample: SensorSample) {
        val deviation = abs(sample.rawMagnitude - GRAVITY)
        val gpsStale = lastGpsStaleState
        val effectiveDevMax = if (gpsStale) GPS_STALE_DEVIATION_MAX else SILENCE_DEVIATION_MAX
        // Read the ACTUAL silence window that fired from the state machine
        // (not derived from constants). This correctly reflects the upright
        // 20s path vs the legacy 4.5s / GPS-stale 8s paths.
        val effectiveSilenceMs = stateMachine.lastConfirmedSilenceMs
        val silencePath = when (effectiveSilenceMs) {
            stateMachine.thresholds.silenceDurationUprightMs -> "UPRIGHT"
            stateMachine.thresholds.gpsStaleSilenceDurationMs -> "GPS_STALE"
            stateMachine.thresholds.silenceDurationMs -> "LEGACY"
            else -> "UNKNOWN"
        }
        Timber.d(">>> CRASH CONFIRMED (accel dev=%.2f speed=%.1fkm/h gyro=%.2f gpsStale=%b silence_ms=%d path=%s)",
            deviation, currentSpeedKmh, sample.gyroMag, gpsStale, effectiveSilenceMs, silencePath)
        val gapMs = stateMachine.lastConfirmedGapMs
        val angle = stateMachine.lastConfirmedAngleDeg
        val ref = stateMachine.preImpactReference
        val decidedBy = when {
            gapMs > stateMachine.thresholds.delayedStopGapMs -> "GAP"
            !ref.valid -> "UNKNOWN"
            angle < 0.0 -> "UNKNOWN"
            angle >= stateMachine.thresholds.uprightAngleThresholdDegrees -> "ORIENT_ONSIDE"
            else -> "ORIENT_UPRIGHT"
        }
        calibLogger?.log(CalibrationLogger.Event.CRASH_CONFIRMED) {
            "deviation=%.2f,speed=%.1f,confirm_spd_thr=${config.crashConfirmSpeedKmh},grade=%.1f,cadence=%.0f,gps_stale=$gpsStale,preset=${config.crashSensitivity},effective_dev_max=$effectiveDevMax,effective_silence_ms=$effectiveSilenceMs,silence_path=$silencePath,countdown_s=${config.countdownSeconds},gap_ms=$gapMs,pre_impact_angle=%.1f,decided_by=$decidedBy".formatUs(
                deviation, currentSpeedKmh, currentGrade, currentCadence, angle)
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
            // Cluster detection on rolling TMO queue. CO1 — read-modify-write under the
            // deque monitor so a concurrent lifecycle-clear from Main can't tear the state.
            val tmoCount = synchronized(recentTmoTimestamps) {
                recentTmoTimestamps.addLast(now)
                while (recentTmoTimestamps.isNotEmpty() &&
                       now - recentTmoTimestamps.first() > CLUSTER_WINDOW_MS) {
                    recentTmoTimestamps.removeFirst()
                }
                recentTmoTimestamps.size
            }
            val isCluster = tmoCount >= CLUSTER_MIN_TMO
            val boostMs = if (isCluster) POST_CLUSTER_COOLDOWN_MS else POST_TMO_COOLDOWN_MS
            postImpactBoostUntil = now + boostMs
            // Force a threshold rebuild so the boost takes effect on the next sample.
            rebuildThresholds(boostActive = true)

            Timber.d("Impact window timeout (%dms) → false alarm, resetting", windowMs)
            // Coerce the "not yet set" sentinels: minSpeedInWindow / minDeviationInWindow
            // are seeded to Double.MAX_VALUE, and if the in-IMPACT accumulator never
            // lowered them that 300-digit value would land in the CSV. Log -1.0 as a
            // clean "not recorded" marker instead. Accumulator logic is unchanged.
            val minSpdLog = if (minSpeedInWindow == Double.MAX_VALUE) -1.0 else minSpeedInWindow
            val minDevLog = if (minDeviationInWindow == Double.MAX_VALUE) -1.0 else minDeviationInWindow
            calibLogger?.log(CalibrationLogger.Event.IMPACT_TIMEOUT) {
                "window_ms=$windowMs,speed=%.1f,gyro=%.2f,deviation=%.2f,grade=%.1f,cadence=%.0f,preset=${config.crashSensitivity},why_no_silence=$whyNoSilence,max_smooth=%.1f,min_spd=%.1f,min_dev=%.2f,boost_s=%.0f,cluster=$isCluster,pre_valid=${stateMachine.preImpactReference.valid}".formatUs(
                    currentSpeedKmh, sample.gyroMag, deviation,
                    currentGrade, currentCadence,
                    maxSmoothedInWindow, minSpdLog, minDevLog,
                    boostMs / 1000f, isCluster
                )
            }
            if (isCluster) {
                calibLogger?.log(CalibrationLogger.Event.TERRAIN_CLUSTER) {
                    // Use the snapshot taken under the lock above (tmoCount) rather than
                    // a fresh .size read that could be torn by a concurrent lifecycle clear.
                    "count=$tmoCount,window_s=${CLUSTER_WINDOW_MS / 1000},boost_s=${boostMs / 1000}"
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
                    "cadence=%.0f,speed=%.1f,deviation=%.2f,grade=%.1f".formatUs(
                        currentCadence, currentSpeedKmh, deviation, currentGrade)
                }
            } else if (stateMachine.lastGapUprightVeto) {
                // R6-F: the 20 s silence WAS achieved — the confirm was vetoed on
                // upright orientation (benign delayed stop), NOT a timeout. The
                // GAP_UPRIGHT_VETO row was already emitted this tick; suppress the
                // misleading SILENCE_TIMEOUT but still snapshot 2 s later so the
                // calibration trail shows whether the bike stayed upright/still.
                Timber.d("GAP-regime upright veto → benign delayed stop, resetting")
                schedulePostResetSnapshot("GAP_VETO")
            } else {
                Timber.d("Silence never achieved → false alarm, resetting")
                calibLogger?.log(CalibrationLogger.Event.SILENCE_TIMEOUT) {
                    val effDev = if (lastGpsStaleState) GPS_STALE_DEVIATION_MAX else SILENCE_DEVIATION_MAX
                    "window_ms=$windowMs,deviation=%.2f,eff_max=$effDev,speed=%.1f,gps_stale=${lastGpsStaleState},preset=${config.crashSensitivity}".formatUs(
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
                    "raw=%.1f,smooth=%.1f,thr=%.1f,speed=%.1f,min_speed=$minSpeed,preset=${config.crashSensitivity}".formatUs(
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
                        "raw=%.1f,smooth=%.1f,thr=%.1f,pthr=%.1f,eff_pthr=%.1f,speed=%.1f,gyro=%.2f,grade=%.1f,grade_boost=%.0f,preset=${config.crashSensitivity},boost=$boostActive".formatUs(
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
                    "gyro=%.2f,gyro_max=$GYRO_MOVING_MAX,deviation=%.2f,speed=%.1f".formatUs(
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
                    "deviation=%.2f,eff_max=$effDev,speed=%.1f,gps_stale=${lastGpsStaleState}".formatUs(
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
            delayedStopGapMs = DELAYED_STOP_GAP_MS,
            silenceDurationUprightMs = SILENCE_DURATION_UPRIGHT_MS,
            uprightAngleThresholdDegrees = UPRIGHT_ANGLE_THRESHOLD_DEGREES,
            gapVetoUprightAngleDeg = GAP_VETO_UPRIGHT_ANGLE_DEG,
            nonGapUprightVetoMaxGyroRadS = NON_GAP_UPRIGHT_VETO_MAX_GYRO_RAD_S,
            onSideRelaxationAngleDeg = ON_SIDE_RELAXATION_ANGLE_DEG,
            onSideRelaxationMaxSpeedKmh = ON_SIDE_RELAXATION_MAX_SPEED_KMH,
        )
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun isGpsStale(now: Long): Boolean =
        speedLastChangeMs > 0 && (now - speedLastChangeMs) > GPS_STALE_MS

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
                "cancelled_by=$cancelledBy,speed=%.1f,gyro=%.2f,state=${stateMachine.state},gps_stale=${isGpsStale(clock.nowMs())}".formatUs(
                    currentSpeedKmh, sensorReader.lastGyroMag)
            }
        }
    }

    /**
     * Single exit for every confirmation path. Applies cooldown and updates [lastCrashTime].
     *
     * [alreadyLogged] indicates whether the caller already emitted a rich CRASH_CONFIRMED
     * row via [logCrashConfirmed]. The IMPACT_CONFIRMED path does, so we MUST NOT emit a
     * second CRASH_CONFIRMED here (downstream consumers count rows). The SPEED_DROP path
     * does not — it routes straight here, so we emit the canonical row from this gate.
     *
     * Cooldown-suppressed events are emitted under CRASH_GATE_SUPPRESSED so the
     * calibration log captures the "would-confirm-but-cooldown" event distinctly
     * without inflating confirmed-crash counts.
     */
    private fun confirmCrash(source: CrashSource, alreadyLogged: Boolean = false) {
        // D2 — cooldown stamps + reads use monotonic time so NTP / date changes
        // can't shift the gate (see [Thresholds]-side comment in onSensorSample).
        val now = clock.monotonicMs()
        if ((now - lastCrashTime) <= crashCooldownMs) {
            Timber.d("Confirm $source suppressed — within cooldown window")
            calibLogger?.log(CalibrationLogger.Event.CRASH_GATE_SUPPRESSED) {
                "source=$source,suppressed_by=cooldown,since_last_ms=${now - lastCrashTime}"
            }
            return
        }
        lastCrashTime = now
        if (!alreadyLogged) {
            calibLogger?.log(CalibrationLogger.Event.CRASH_CONFIRMED) { "source=$source" }
        }
        scope.launch { onCrashDetected() }
    }
}
