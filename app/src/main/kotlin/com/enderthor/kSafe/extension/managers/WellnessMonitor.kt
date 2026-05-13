package com.enderthor.kSafe.extension.managers

import com.enderthor.kSafe.data.EmergencyReason
import com.enderthor.kSafe.data.KSafeConfig
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale

/**
 * Wellness monitor with three independent tiers, each addressing a distinct physiological signal:
 *
 *  1. **Critical HR** — HR > [KSafeConfig.wellnessCriticalThresholdBpm] (or % equivalent) sustained
 *     for [KSafeConfig.wellnessCriticalDurationMinutes]. Catches acute overexertion early
 *     (default: 95 % maxHR / 5 min).
 *  2. **Sustained HR** — the original tier — HR > [KSafeConfig.wellnessHighHrThreshold] (or %
 *     equivalent) sustained for [KSafeConfig.wellnessHighHrDurationMinutes]. Catches long-tail
 *     fatigue (default: 92 % maxHR / 30 min).
 *  3. **Cardiac decoupling** — HR / power ratio drift > [KSafeConfig.wellnessDecouplingThresholdPct]
 *     vs the baseline established in the first 10 min of stable riding, sustained for
 *     [KSafeConfig.wellnessDecouplingDurationMinutes]. Catches dehydration / heat stress / fatigue
 *     before either of the absolute-threshold tiers fires (default: 7 % drift / 10 min).
 *     Requires a power meter; auto-skipped if power data is absent.
 *
 * Each tier has its own enable toggle. The master [KSafeConfig.wellnessEnabled] gates all three
 * — when off, the monitor doesn't run at all.
 *
 * All three tiers fire as `WARNING`-level [InRideAlert]s via the dispatcher in EmergencyManager.
 * Each tier emits a distinct [EmergencyReason] so the user-visible title differs.
 */
class WellnessMonitor(
    private val scope: CoroutineScope,
    private val onIncident: (EmergencyReason, Map<String, String>) -> Unit,
    private val calibLogger: CalibrationLogger? = null,
) {

    // ─── Constants ──────────────────────────────────────────────────────────
    private val HR_STALE_MS                  = 15_000L
    private val MONITOR_TICK_MS              = 30_000L
    private val DECOUPLING_BASELINE_WAIT_MS  = 10L * 60_000L   // wait 10 min before establishing baseline
    private val DECOUPLING_ROLLING_WINDOW_MS = 5L  * 60_000L   // 5 min rolling avg
    private val DECOUPLING_MIN_POWER_W       = 50              // skip coasting / descending samples
    private val DECOUPLING_MIN_SAMPLES       = 8               // need at least 8 samples in the buffer to evaluate
    private val DECOUPLING_COOLDOWN_MS       = 30L * 60_000L   // once decoupling fires, wait 30 min before re-fire

    // ─── Live data (push from KSafeExtension) ────────────────────────────────
    @Volatile private var lastHrBpm: Int? = null
    @Volatile private var lastPowerW: Int? = null
    @Volatile private var lastHrUpdateMs = 0L
    @Volatile private var lastUserProfile: UserProfile? = null

    // ─── Session state (reset by start()) ────────────────────────────────────
    @Volatile private var sessionStartMs = 0L
    // Critical tier
    @Volatile private var criticalSinceMs = 0L
    @Volatile private var lastCriticalTriggerMs = 0L
    // Sustained tier
    @Volatile private var sustainedSinceMs = 0L
    @Volatile private var lastSustainedTriggerMs = 0L
    // Decoupling tier
    @Volatile private var decouplingBaselineHrPerW = 0f          // 0 = not yet established
    @Volatile private var decouplingExceededSinceMs = 0L
    @Volatile private var lastDecouplingTriggerMs = 0L
    private val ratioSamples = ArrayDeque<Pair<Long, Float>>()    // (timestamp, hr/w)

    // ─── Session accumulators (consumed by FIT export + Health tab) ─────────
    // Granularity is MONITOR_TICK_MS (~30 s) for the time-in-zone buckets — exact
    // enough for post-ride analysis without sub-tick HR sampling.
    @Volatile private var sessionMaxHr: Int = 0
    @Volatile private var cumMsCriticalAbove: Long = 0L
    @Volatile private var cumMsSustainedAbove: Long = 0L
    @Volatile private var currentDriftPct: Float = 0f
    @Volatile private var maxDriftPct: Float = 0f
    @Volatile private var criticalFires: Int = 0
    @Volatile private var sustainedFires: Int = 0
    @Volatile private var decouplingFires: Int = 0

    @Volatile private var config = KSafeConfig()
    private var monitorJob: Job? = null

    // ─── Public API ──────────────────────────────────────────────────────────

    fun start(config: KSafeConfig) {
        this.config = config
        if (!config.wellnessEnabled) return
        // Same cancelAndJoin pattern as the other trackers — guarantees the previous monitor
        // is fully gone before the new one runs.
        val oldJob = monitorJob
        val now = System.currentTimeMillis()
        sessionStartMs = now
        criticalSinceMs = 0L
        sustainedSinceMs = 0L
        decouplingBaselineHrPerW = 0f
        decouplingExceededSinceMs = 0L
        lastCriticalTriggerMs = 0L
        lastSustainedTriggerMs = 0L
        lastDecouplingTriggerMs = 0L
        ratioSamples.clear()
        // Reset session accumulators — fresh ride, fresh totals.
        sessionMaxHr = 0
        cumMsCriticalAbove = 0L
        cumMsSustainedAbove = 0L
        currentDriftPct = 0f
        maxDriftPct = 0f
        criticalFires = 0
        sustainedFires = 0
        decouplingFires = 0
        monitorJob = scope.launch {
            oldJob?.cancelAndJoin()
            while (true) { delay(MONITOR_TICK_MS); tick() }
        }
        Timber.d("WellnessMonitor started — tiers: critical=${config.wellnessCriticalEnabled}, sustained=${config.wellnessSustainedEnabled}, decoupling=${config.wellnessDecouplingEnabled}")
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        Timber.d("WellnessMonitor stopped")
    }

    /**
     * Re-launch the monitor without resetting session totals. Used by the master-switch
     * mid-ride OFF→ON transition: cumulative HR-zone time, max-HR snapshot, drift
     * statistics and per-tier fire counters survive a brief toggle. The "continuous
     * violation" timers (criticalSinceMs, sustainedSinceMs, decouplingExceededSinceMs)
     * are reset because their semantics require an uninterrupted observation window —
     * the OFF period broke that continuity.
     */
    fun resume(config: KSafeConfig) {
        this.config = config
        if (!config.wellnessEnabled) return
        val oldJob = monitorJob
        criticalSinceMs = 0L
        sustainedSinceMs = 0L
        decouplingExceededSinceMs = 0L
        decouplingBaselineHrPerW = 0f
        monitorJob = scope.launch {
            oldJob?.cancelAndJoin()
            while (true) { delay(MONITOR_TICK_MS); tick() }
        }
        Timber.d("WellnessMonitor resumed (sessionMaxHr=$sessionMaxHr, criticalFires=$criticalFires, sustainedFires=$sustainedFires)")
    }

    /**
     * See [HydrationTracker.updateConfig] for the rationale of the [isRecording] gate
     * on the auto-start branch. Same shape, same reason.
     */
    fun updateConfig(config: KSafeConfig, isRecording: Boolean) {
        val wasEnabled = this.config.wellnessEnabled
        this.config = config
        if (!wasEnabled && config.wellnessEnabled && isRecording) start(config)
        else if (wasEnabled && !config.wellnessEnabled) stop()
    }

    fun updateHr(bpm: Int) {
        lastHrBpm = bpm
        lastHrUpdateMs = System.currentTimeMillis()
        // Track session peak. Called every HR callback (~1 Hz), more precise than tick().
        if (bpm > sessionMaxHr) sessionMaxHr = bpm
    }

    fun updatePower(w: Int) { lastPowerW = w }
    fun updateUserProfile(p: UserProfile) { lastUserProfile = p }

    // ─── Per-tier evaluation (runs on `scope`, every MONITOR_TICK_MS) ────────
    // Exposed as `internal` so JVM unit tests in the same package can drive it
    // synchronously without spinning up the coroutine loop — avoids the wall-clock
    // vs virtual-time interaction quirks of runTest + advanceTimeBy.
    internal fun tick() {
        val now = System.currentTimeMillis()
        if (now - lastHrUpdateMs > HR_STALE_MS) {
            // Sensor silent — every per-tier evaluation will return early. Reset the streak
            // accumulators so a transient disconnect doesn't carry forward stale state.
            // Time-in-zone buckets are NOT touched: if the HR strap drops out we just don't
            // add anything during stale ticks — neither over- nor under-counts.
            criticalSinceMs = 0L
            sustainedSinceMs = 0L
            decouplingExceededSinceMs = 0L
            return
        }
        // Feed the time-in-zone buckets at MONITOR_TICK_MS granularity. The bucket attribution
        // is "HR at this instant" — a fluctuation within the 30 s window between ticks gets
        // rounded to whichever side of the threshold the sample landed on. Good enough for
        // post-ride analysis; not a real-time precision tool.
        val bpm = lastHrBpm
        if (bpm != null) {
            if (bpm >= effectiveCriticalThreshold())   cumMsCriticalAbove  += MONITOR_TICK_MS
            if (bpm >= effectiveSustainedThreshold()) cumMsSustainedAbove += MONITOR_TICK_MS
        }
        evaluateCriticalTier(now)
        evaluateSustainedTier(now)
        evaluateDecouplingTier(now)
    }

    // ── Tier 1 — Critical HR ────────────────────────────────────────────────

    private fun evaluateCriticalTier(now: Long) {
        if (!config.wellnessCriticalEnabled) return
        val bpm = lastHrBpm ?: return
        val threshold = effectiveCriticalThreshold()
        if (bpm >= threshold) {
            if (criticalSinceMs == 0L) criticalSinceMs = now
            val sustainedMs = now - criticalSinceMs
            val needMs = config.wellnessCriticalDurationMinutes * 60_000L
            if (sustainedMs >= needMs && now - lastCriticalTriggerMs >= cooldownForTier(config.wellnessCriticalDurationMinutes)) {
                fireTier(now,
                    reason = EmergencyReason.WELLNESS_CRITICAL_HR,
                    bpm = bpm,
                    threshold = threshold,
                    sustainedMin = sustainedMs / 60_000L,
                    tierName = "critical",
                )
                lastCriticalTriggerMs = now
                criticalSinceMs = 0L  // re-arm: HR must drop below threshold and rise again
            }
        } else {
            criticalSinceMs = 0L
        }
    }

    // ── Tier 2 — Sustained HR (existing tier, semantically) ─────────────────

    private fun evaluateSustainedTier(now: Long) {
        if (!config.wellnessSustainedEnabled) return
        val bpm = lastHrBpm ?: return
        val threshold = effectiveSustainedThreshold()
        if (bpm >= threshold) {
            if (sustainedSinceMs == 0L) sustainedSinceMs = now
            val sustainedMs = now - sustainedSinceMs
            val needMs = config.wellnessHighHrDurationMinutes * 60_000L
            if (sustainedMs >= needMs && now - lastSustainedTriggerMs >= cooldownForTier(config.wellnessHighHrDurationMinutes)) {
                fireTier(now,
                    reason = EmergencyReason.WELLNESS_HIGH_HR,
                    bpm = bpm,
                    threshold = threshold,
                    sustainedMin = sustainedMs / 60_000L,
                    tierName = "sustained",
                )
                lastSustainedTriggerMs = now
                sustainedSinceMs = 0L
            }
        } else {
            sustainedSinceMs = 0L
        }
    }

    // ── Tier 3 — Cardiac decoupling (HR / power drift) ──────────────────────

    private fun evaluateDecouplingTier(now: Long) {
        if (!config.wellnessDecouplingEnabled) return
        val hr = lastHrBpm ?: return
        val w = lastPowerW ?: return                 // no power → silently skip (decoupling impossible)
        if (w < DECOUPLING_MIN_POWER_W) return       // skip coasting / descents (would dilute the avg)

        val ratio = hr.toFloat() / w.toFloat()

        // Maintain a rolling 5-min window of HR/W samples, taken once per tick (every 30 s).
        ratioSamples.addLast(now to ratio)
        while (ratioSamples.isNotEmpty() && now - ratioSamples.first().first > DECOUPLING_ROLLING_WINDOW_MS) {
            ratioSamples.removeFirst()
        }

        // Establish baseline ONCE per session — after BASELINE_WAIT_MS of riding accumulated
        // enough samples in the rolling window. Captures the rider's "fresh" ratio.
        if (decouplingBaselineHrPerW == 0f) {
            if (now - sessionStartMs >= DECOUPLING_BASELINE_WAIT_MS && ratioSamples.size >= DECOUPLING_MIN_SAMPLES) {
                val sum = ratioSamples.sumOf { it.second.toDouble() }
                decouplingBaselineHrPerW = (sum / ratioSamples.size).toFloat()
                calibLogger?.log(CalibrationLogger.Event.WELLNESS_FIRED) {
                    String.format(Locale.US, "subkind=decoupling_baseline,baseline_hr_per_w=%.4f,samples=%d", decouplingBaselineHrPerW, ratioSamples.size)
                }
                Timber.d(String.format(Locale.US, "WellnessMonitor: decoupling baseline established hr/w=%.4f from %d samples", decouplingBaselineHrPerW, ratioSamples.size))
            }
            return
        }

        if (ratioSamples.size < DECOUPLING_MIN_SAMPLES) return  // not enough current data

        val currentSum = ratioSamples.sumOf { it.second.toDouble() }
        val currentAvg = (currentSum / ratioSamples.size).toFloat()
        val driftPct = ((currentAvg / decouplingBaselineHrPerW) - 1f) * 100f

        // Expose the current drift for FIT export + Health tab. Also track the session peak.
        currentDriftPct = driftPct
        if (driftPct > maxDriftPct) maxDriftPct = driftPct

        if (driftPct >= config.wellnessDecouplingThresholdPct.toFloat()) {
            if (decouplingExceededSinceMs == 0L) decouplingExceededSinceMs = now
            val sustainedMs = now - decouplingExceededSinceMs
            val needMs = config.wellnessDecouplingDurationMinutes * 60_000L
            if (sustainedMs >= needMs && now - lastDecouplingTriggerMs >= DECOUPLING_COOLDOWN_MS) {
                val sustainedMin = sustainedMs / 60_000L
                Timber.d(String.format(Locale.US, ">>> WELLNESS_DECOUPLING fired: drift=%.1f%% (current=%.4f / baseline=%.4f), sustained=%dmin", driftPct, currentAvg, decouplingBaselineHrPerW, sustainedMin))
                calibLogger?.log(CalibrationLogger.Event.WELLNESS_FIRED) {
                    String.format(Locale.US, "subkind=decoupling,drift_pct=%.1f,current_hr_per_w=%.4f,baseline_hr_per_w=%.4f,sustained_min=%d,hr=%d,power=%d", driftPct, currentAvg, decouplingBaselineHrPerW, sustainedMin, hr, w)
                }
                lastDecouplingTriggerMs = now
                decouplingExceededSinceMs = 0L
                decouplingFires++
                onIncident(EmergencyReason.WELLNESS_DECOUPLING, mapOf(
                    "drift" to String.format(Locale.US, "%.1f", driftPct),
                    "minutes" to sustainedMin.toString(),
                ))
            }
        } else {
            decouplingExceededSinceMs = 0L
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Threshold (bpm) for the critical tier, accounting for the absolute-vs-% mode.
     *
     *  In percent mode, requires `lastUserProfile.maxHr` to be available — without it, the
     *  rider's intended scaling cannot be honoured. Returns [Int.MAX_VALUE] in that case so
     *  no HR reading can ever exceed the threshold (tier silently waits for the profile to
     *  arrive). The previous behaviour of falling back to `wellnessCriticalThresholdBpm`
     *  was surprising for riders who configured 95 % expecting it to scale with their max HR.
     *  In absolute mode (the default) there is no profile dependency, so the threshold is
     *  always available. */
    private fun effectiveCriticalThreshold(): Int {
        if (!config.wellnessUseMaxHrPercent) return config.wellnessCriticalThresholdBpm
        val maxHr = lastUserProfile?.maxHr ?: 0
        if (maxHr <= 0) return Int.MAX_VALUE
        return (maxHr * config.wellnessCriticalThresholdPct) / 100
    }

    /** Threshold (bpm) for the sustained tier — keeps using the existing
     *  wellnessHighHrThreshold / wellnessHighHrPercent fields for back-compat. Same
     *  profile-missing semantics as [effectiveCriticalThreshold]: in pct mode without a
     *  profile, returns [Int.MAX_VALUE] to prevent firing with the wrong threshold. */
    private fun effectiveSustainedThreshold(): Int {
        if (!config.wellnessUseMaxHrPercent) return config.wellnessHighHrThreshold
        val maxHr = lastUserProfile?.maxHr ?: 0
        if (maxHr <= 0) return Int.MAX_VALUE
        return (maxHr * config.wellnessHighHrPercent) / 100
    }

    /** Per-tier cooldown — keeps the existing convention that the cooldown matches the duration
     *  setting, so the rider's "alert me every X min if still high" mental model is preserved. */
    private fun cooldownForTier(durationMinutes: Int): Long = durationMinutes * 60_000L

    private fun fireTier(
        now: Long,
        reason: EmergencyReason,
        bpm: Int,
        threshold: Int,
        sustainedMin: Long,
        tierName: String,
    ) {
        Timber.d(">>> Wellness tier=$tierName fired: bpm=$bpm threshold=$threshold sustained=${sustainedMin}min")
        calibLogger?.log(CalibrationLogger.Event.WELLNESS_FIRED) {
            "subkind=$tierName,bpm=$bpm,threshold=$threshold,mode=${if (config.wellnessUseMaxHrPercent) "pct" else "abs"},sustained_min=$sustainedMin"
        }
        when (tierName) {
            "critical"  -> criticalFires++
            "sustained" -> sustainedFires++
        }
        onIncident(reason, mapOf(
            "bpm" to bpm.toString(),
            "threshold" to threshold.toString(),
            "minutes" to sustainedMin.toString(),
        ))
    }

    // ─── Public snapshot for FIT export and the Health tab ──────────────────

    /**
     * Immutable snapshot of the session-wide wellness state. Cheap to construct
     * (read-only field reads); call from any thread.
     */
    data class WellnessSummary(
        val maxHrBpm: Int,
        val cumMsCriticalAbove: Long,
        val cumMsSustainedAbove: Long,
        val currentDriftPct: Float,
        val maxDriftPct: Float,
        val criticalFires: Int,
        val sustainedFires: Int,
        val decouplingFires: Int,
    ) {
        val totalFires: Int get() = criticalFires + sustainedFires + decouplingFires
    }

    fun getSummary(): WellnessSummary = WellnessSummary(
        maxHrBpm           = sessionMaxHr,
        cumMsCriticalAbove = cumMsCriticalAbove,
        cumMsSustainedAbove = cumMsSustainedAbove,
        currentDriftPct    = currentDriftPct,
        maxDriftPct        = maxDriftPct,
        criticalFires      = criticalFires,
        sustainedFires     = sustainedFires,
        decouplingFires    = decouplingFires,
    )
}
