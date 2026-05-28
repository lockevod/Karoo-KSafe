package com.enderthor.kSafe.extension.managers

import com.enderthor.kSafe.data.EmergencyReason
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.extension.util.Clock
import com.enderthor.kSafe.extension.util.SystemClock
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    /**
     * Injectable clock. Production passes [SystemClock]; tests pass a fake to drive the
     * duration / cooldown windows (critical / sustained / decoupling) deterministically
     * without waiting wall-clock minutes inside a coroutine `delay`.
     */
    private val clock: Clock = SystemClock,
) {

    // ─── Constants ──────────────────────────────────────────────────────────
    private val HR_STALE_MS                  = 15_000L
    private val MONITOR_TICK_MS              = 30_000L
    private val DECOUPLING_BASELINE_WAIT_MS  = 10L * 60_000L   // wait 10 min before establishing baseline
    private val DECOUPLING_ROLLING_WINDOW_MS = 5L  * 60_000L   // 5 min rolling avg
    private val DECOUPLING_MIN_POWER_W       = 50              // skip coasting / descending samples
    private val DECOUPLING_MIN_SAMPLES       = 8               // need at least 8 samples in the buffer to evaluate
    private val DECOUPLING_COOLDOWN_MS       = 30L * 60_000L   // once decoupling fires, wait 30 min before re-fire

    // ── Baseline-stability guard (HE1) ──────────────────────────────────────
    // Power-stability gating for the decoupling baseline. If the rider's power has been
    // bouncing all over the place during the establishment window (warm-up at z1 then
    // race-day ramp, hot lead-out then steady tempo, interval workout opening), freezing
    // the baseline at exactly minute 10 anchors it to an atypical "fresh state". Every
    // subsequent drift % then references the wrong anchor — HR rise from genuine effort
    // change is read as decoupling and the WARNING fires.
    //
    // The guard collects a 2-min ring buffer of 1 Hz power samples (pushed by
    // `updatePower`). At each baseline-establishment attempt we compute the coefficient
    // of variation (stddev / mean) over that window. If CV > [POWER_STABILITY_CV_MAX]
    // we DEFER establishment by [BASELINE_RETRY_INTERVAL_MS] (2 min) and try again, up
    // to [BASELINE_MAX_DEFER_MS] (25 min) after `sessionStartMs`. Past that cap, we
    // establish with whatever we have — better a stable-ish late baseline than none.
    //
    // No power meter → `evaluateDecouplingTier` returns at the `lastPowerW ?: return`
    // gate long before reaching the establishment branch, so this guard is naturally
    // bypassed: the legacy fixed-time path is unchanged for power-less riders.
    private val POWER_BUFFER_WINDOW_MS       = 2L * 60_000L    // 2 min of 1 Hz samples
    private val POWER_BUFFER_MAX_SIZE        = 150             // hard cap (~2.5 min at 1 Hz)
    private val POWER_STABILITY_MIN_SAMPLES  = 30              // need >= 30 s of data to judge stability
    private val POWER_STABILITY_CV_MAX       = 0.30f           // stddev / mean threshold
    private val BASELINE_RETRY_INTERVAL_MS   = 2L * 60_000L    // re-attempt every 2 min after first defer
    private val BASELINE_MAX_DEFER_MS        = 25L * 60_000L   // hard cap from sessionStartMs

    // ─── Live data (push from KSafeExtension) ────────────────────────────────
    @Volatile private var lastHrBpm: Int? = null
    @Volatile private var lastPowerW: Int? = null
    @Volatile private var lastHrUpdateMs = 0L
    @Volatile private var lastUserProfile: UserProfile? = null

    // ─── Session state (reset by start()) ────────────────────────────────────
    // ALL timestamps below (and lastHrUpdateMs above, powerSamples/ratioSamples keys) are
    // taken from `clock.monotonicMs()` (elapsedRealtime), NOT wall-clock. They are purely
    // in-memory session durations/streaks/cooldowns/windows — never persisted — and are
    // only ever compared against each other as differences. Using a monotonic source makes
    // them immune to NTP corrections / manual date changes (a backward jump would make a
    // streak elapsed go negative → never fire; a forward jump would fire prematurely). This
    // mirrors the MedicalEpisodeDetector D2 fix. Tests inject a Clock whose monotonicMs()
    // delegates to nowMs(), so deterministic advancement keeps working.
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
    /** Running sum of [ratioSamples]'s `.second` values. Maintained incrementally on
     *  add + eviction so we never need a per-tick `sumOf` iteration. Buffer size is
     *  small (~10 entries over a 5-min window at the 30-s tick) so the absolute saving
     *  is modest, but the running-sum pattern is consistent with the larger Medical
     *  detector optimisation and removes a per-tick autoboxing pass over the Pairs. */
    private var ratioRunningSum = 0.0

    // Baseline-stability guard state (HE1). [powerSamples] is fed from `updatePower`
    // at ~1 Hz (the rate of the SDK power stream). [lastBaselineAttemptMs] gates the
    // re-attempt cadence so we only re-evaluate stability every BASELINE_RETRY_INTERVAL_MS,
    // not on every tick.
    //
    // [powerSamples] is touched from three different threads — the SDK power-callback
    // thread (updatePower), the scope coroutine (tick → shouldDeferBaseline), and Main
    // (start/resume's clear()). Every access goes through [powerSamplesLock] so the
    // tick-side iteration cannot ConcurrentModificationException-kill the monitor loop
    // and cannot read a torn snapshot when computing baseline statistics.
    private val powerSamplesLock = Any()
    /** Primitive ring buffer for the baseline-stability guard's 2-min power window.
     *  Pre-B30 this was `ArrayDeque<Pair<Long, Int>>` — every [updatePower] call
     *  boxed both the Long timestamp and the Int watts (~3 allocations / Hz, plus
     *  the per-tick `.toList()` snapshot for statistics). 18 000 allocs / hour
     *  while a power meter is paired. The primitive ring mirrors the
     *  [MedicalEpisodeDetector] `HrHistory` pattern: capacity power of two, head
     *  + size + mask, in-place sum / sumSq iteration via [wattAt] indices —
     *  zero allocations on the add or read paths. */
    private val powerSamples = PowerHistory(capacity = 256)  // >= POWER_BUFFER_MAX_SIZE, power of two
    @Volatile private var lastBaselineAttemptMs = 0L

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

    // ─── Status publisher (B29) ──────────────────────────────────────────────
    /** Push-based [WellnessSummary] snapshot, mirroring the pattern in
     *  [CarbsTracker.statusFlow] / [HydrationTracker.statusFlow]. The FIT
     *  writer in `KSafeExtension.startFit` reads `.value` on every 1 Hz
     *  ELAPSED_TIME tick — pre-B29 it called `getSummary()` instead, which
     *  allocated a fresh `WellnessSummary` per tick (~3 600/h) on top of the
     *  estimator chain. Publishing here once per [tick] (30 s) drops it to
     *  the actual rate of change of the wellness signals. `null` means
     *  "no summary published yet" — readers fall back to a default. */
    private val _summaryFlow = MutableStateFlow<WellnessSummary?>(null)
    val summaryFlow: StateFlow<WellnessSummary?> get() = _summaryFlow
    private fun publishSummary() { _summaryFlow.value = getSummary() }

    // ─── Public API ──────────────────────────────────────────────────────────

    fun start(config: KSafeConfig) {
        this.config = config
        if (!config.wellnessEnabled) return
        // Same cancelAndJoin pattern as the other trackers — guarantees the previous monitor
        // is fully gone before the new one runs.
        val oldJob = monitorJob
        val now = clock.monotonicMs()
        sessionStartMs = now
        criticalSinceMs = 0L
        sustainedSinceMs = 0L
        decouplingBaselineHrPerW = 0f
        decouplingExceededSinceMs = 0L
        lastCriticalTriggerMs = 0L
        lastSustainedTriggerMs = 0L
        lastDecouplingTriggerMs = 0L
        ratioSamples.clear()
        ratioRunningSum = 0.0
        synchronized(powerSamplesLock) { powerSamples.clear() }  // B30 ring
        lastBaselineAttemptMs = 0L
        // Reset session accumulators — fresh ride, fresh totals.
        sessionMaxHr = 0
        cumMsCriticalAbove = 0L
        cumMsSustainedAbove = 0L
        currentDriftPct = 0f
        maxDriftPct = 0f
        criticalFires = 0
        sustainedFires = 0
        decouplingFires = 0
        // B29 — publish a zeroed summary so the FIT writer reads a sensible
        // value from `summaryFlow.value` from the first tick onwards (instead
        // of `null` until the first tick has run).
        publishSummary()
        monitorJob = scope.launch {
            oldJob?.cancelAndJoin()
            // H3 — defensive try/catch: a single uncaught throw from tick() would
            // terminate the loop silently and disable wellness detection for the ride.
            while (true) {
                delay(MONITOR_TICK_MS)
                try { tick() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { Timber.e(e, "WellnessMonitor.tick threw — continuing") }
            }
        }
        Timber.d("WellnessMonitor started — tiers: critical=${config.wellnessCriticalEnabled}, sustained=${config.wellnessSustainedEnabled}, decoupling=${config.wellnessDecouplingEnabled}")
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        // B29 — clear the published summary on stop so a stale value can't be
        // read after the monitor is no longer running.
        _summaryFlow.value = null
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
        // Same continuity argument as the streak timers: the OFF period invalidates the
        // power stability window, so the guard re-evaluates from scratch on resume.
        synchronized(powerSamplesLock) { powerSamples.clear() }  // B30 ring
        // Clear the HR/W ratio rolling window too — without this, the re-established
        // baseline averages pre-OFF samples (potentially a high-effort interval) with
        // post-ON samples (fresh steady state), anchoring the baseline on a contaminated
        // window and producing false WELLNESS_DECOUPLING WARNING fires later in the ride.
        ratioSamples.clear()
        ratioRunningSum = 0.0
        lastBaselineAttemptMs = 0L
        monitorJob = scope.launch {
            oldJob?.cancelAndJoin()
            // H3 — defensive try/catch: a single uncaught throw from tick() would
            // terminate the loop silently and disable wellness detection for the ride.
            while (true) {
                delay(MONITOR_TICK_MS)
                try { tick() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { Timber.e(e, "WellnessMonitor.tick threw — continuing") }
            }
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
        lastHrUpdateMs = clock.monotonicMs()
        // Track session peak. Called every HR callback (~1 Hz), more precise than tick().
        if (bpm > sessionMaxHr) sessionMaxHr = bpm
    }

    fun updatePower(w: Int) {
        lastPowerW = w
        // Feed the 2-min ring buffer used by the baseline-stability guard. Sampled at
        // whatever rate the SDK pushes power (~1 Hz). The buffer is double-bounded:
        // by time (POWER_BUFFER_WINDOW_MS) and by absolute count via the ring's own
        // capacity (256 ≥ POWER_BUFFER_MAX_SIZE; the ring's tail-overwrite handles
        // the count cap automatically). B30 — primitive ring, no Pair allocation.
        val now = clock.monotonicMs()
        synchronized(powerSamplesLock) {
            powerSamples.add(now, w)
            powerSamples.trimOlderThan(now - POWER_BUFFER_WINDOW_MS)
        }
    }
    fun updateUserProfile(p: UserProfile) { lastUserProfile = p }

    // ─── Per-tier evaluation (runs on `scope`, every MONITOR_TICK_MS) ────────
    // Exposed as `internal` so JVM unit tests in the same package can drive it
    // synchronously without spinning up the coroutine loop — avoids the wall-clock
    // vs virtual-time interaction quirks of runTest + advanceTimeBy.
    internal fun tick() {
        val now = clock.monotonicMs()
        if (now - lastHrUpdateMs > HR_STALE_MS) {
            // Sensor silent — every per-tier evaluation will return early. Reset the streak
            // accumulators so a transient disconnect doesn't carry forward stale state.
            // Time-in-zone buckets are NOT touched: if the HR strap drops out we just don't
            // add anything during stale ticks — neither over- nor under-counts.
            criticalSinceMs = 0L
            sustainedSinceMs = 0L
            decouplingExceededSinceMs = 0L
            // B29 — still refresh the published snapshot (drift / max may have moved
            // since the last tick before the strap went silent). The FIT writer
            // reads `summaryFlow.value` once per second so it must stay current.
            publishSummary()
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
        // B29 — publish the post-tick snapshot for the FIT writer's StateFlow read.
        publishSummary()
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
        // Update the running sum on add + on eviction so the per-tick average is O(1).
        ratioSamples.addLast(now to ratio)
        ratioRunningSum += ratio.toDouble()
        while (ratioSamples.isNotEmpty() && now - ratioSamples.first().first > DECOUPLING_ROLLING_WINDOW_MS) {
            ratioRunningSum -= ratioSamples.first().second.toDouble()
            ratioSamples.removeFirst()
        }

        // Establish baseline ONCE per session — after BASELINE_WAIT_MS of riding accumulated
        // enough samples in the rolling window. Captures the rider's "fresh" ratio.
        //
        // HE1: gate the establishment moment on power stability. If the rider's power is
        // bouncing (warm-up + ramp, intervals, hot lead-out) at minute 10, freezing the
        // baseline here anchors it to an atypical state and every subsequent drift % is
        // referenced against the wrong "fresh" — surfacing as false WARNING fires later
        // in the ride from genuine effort change. Defer + re-attempt; cap defers so a
        // rider whose power is unstable for the whole hour still gets some baseline.
        if (decouplingBaselineHrPerW == 0f) {
            if (now - sessionStartMs >= DECOUPLING_BASELINE_WAIT_MS && ratioSamples.size >= DECOUPLING_MIN_SAMPLES) {
                if (shouldDeferBaseline(now)) {
                    lastBaselineAttemptMs = now
                    return
                }
                decouplingBaselineHrPerW = (ratioRunningSum / ratioSamples.size).toFloat()
                calibLogger?.log(CalibrationLogger.Event.WELLNESS_FIRED) {
                    String.format(Locale.US, "subkind=decoupling_baseline,baseline_hr_per_w=%.4f,samples=%d", decouplingBaselineHrPerW, ratioSamples.size)
                }
                Timber.d(String.format(Locale.US, "WellnessMonitor: decoupling baseline established hr/w=%.4f from %d samples", decouplingBaselineHrPerW, ratioSamples.size))
            }
            return
        }

        if (ratioSamples.size < DECOUPLING_MIN_SAMPLES) return  // not enough current data

        val currentAvg = (ratioRunningSum / ratioSamples.size).toFloat()
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

    /**
     * HE1 baseline-stability guard. Returns `true` when establishment should be deferred
     * because the rider's power over the last [POWER_BUFFER_WINDOW_MS] is too variable to
     * yield a representative HR/W "fresh" anchor.
     *
     * Decision tree:
     * - Past the hard defer cap ([BASELINE_MAX_DEFER_MS] from session start) → **never defer**,
     *   establish whatever we have. Better a stable-ish late baseline than indefinite waiting.
     * - First attempt at the establishment moment (i.e. `lastBaselineAttemptMs == 0L`) → evaluate.
     *   Subsequent attempts must be ≥ [BASELINE_RETRY_INTERVAL_MS] apart so we don't burn CPU on
     *   every 30-s tick re-running stddev over the same buffer.
     * - Not enough power samples to judge ([POWER_STABILITY_MIN_SAMPLES]) → don't defer.
     *   In practice this only happens if the power stream just connected; treat the absence
     *   of evidence as a pass rather than blocking forever.
     * - Coefficient of variation (stddev / mean) > [POWER_STABILITY_CV_MAX] → defer.
     *
     * Covered by JVM unit tests in [WellnessMonitorTest] — time is driven through the
     * injected [Clock] so the 10-min establishment wait and the duration / cooldown
     * gates can be exercised deterministically.
     */
    private fun shouldDeferBaseline(now: Long): Boolean {
        // Hard cap reached — establish now regardless of stability.
        if (now - sessionStartMs >= BASELINE_MAX_DEFER_MS) return false
        // Rate-limit re-attempts so we evaluate at fixed 2-min intervals, not every tick.
        if (lastBaselineAttemptMs != 0L && now - lastBaselineAttemptMs < BASELINE_RETRY_INTERVAL_MS) {
            return true
        }
        // B30 — compute statistics in place under the lock. Pre-B30 took a `.toList()`
        // snapshot (Pair allocations) then iterated; now we walk the primitive ring
        // by index with zero allocations. The lock-held window is identical in
        // duration (n ≈ 120 iterations of a Double add).
        var n = 0
        var sum = 0.0
        var sumSq = 0.0
        synchronized(powerSamplesLock) {
            n = powerSamples.size
            // E5 fix — when we have fewer than POWER_STABILITY_MIN_SAMPLES, DEFER (return
            // true) rather than establish the baseline from a thin warmup window. Without
            // this, a power meter that wakes up mid-warmup (~8 min into the ride) accumulated
            // only ~10 samples by the 10-min DECOUPLING_BASELINE_WAIT_MS — the original
            // `return false` anchored the baseline on the warmup ramp's HR/W (low HR,
            // decent power), so 20 min later at steady tempo the drift evaluation fired
            // a false WELLNESS_DECOUPLING warning. The hard cap at BASELINE_MAX_DEFER_MS
            // above guarantees we will eventually establish even on a permanently-thin
            // buffer, so the rider with a late-paired power meter just gets a slightly
            // later (but trustworthy) baseline.
            if (n >= POWER_STABILITY_MIN_SAMPLES) {
                // Single-pass mean + variance using the running-sum / sum-of-squares form.
                // Cheap and good enough for n ≈ 120 with all-positive integer watts; we
                // don't need a numerically-stable Welford pass for this magnitude range.
                var i = 0
                while (i < n) {
                    val wd = powerSamples.wattAt(i).toDouble()
                    sum += wd
                    sumSq += wd * wd
                    i++
                }
            }
        }
        if (n < POWER_STABILITY_MIN_SAMPLES) return true
        val mean = sum / n
        if (mean <= 0.0) return false  // all zeros / degenerate — let establishment proceed
        val variance = (sumSq / n) - (mean * mean)
        val stddev = if (variance > 0.0) kotlin.math.sqrt(variance) else 0.0
        val cv = (stddev / mean).toFloat()
        val defer = cv > POWER_STABILITY_CV_MAX
        if (defer) {
            Timber.d(String.format(
                Locale.US,
                "WellnessMonitor: decoupling baseline DEFERRED — power unstable (cv=%.2f, mean=%.0fW, n=%d)",
                cv, mean, n,
            ))
        }
        return defer
    }


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

/**
 * Primitive ring buffer for (timestamp, watts) samples — used by the
 * WellnessMonitor decoupling-baseline stability guard. Mirrors the design of
 * `HrHistory` in MedicalEpisodeDetector (B30 follow-up): power-of-two
 * capacity for bitmask wrap, LongArray + IntArray backing, no Pair / box
 * allocations on add or read.
 *
 * Thread safety: caller (WellnessMonitor) serialises every access through
 * `powerSamplesLock`. This class itself is single-threaded by contract.
 */
internal class PowerHistory(private val capacity: Int) {
    init { require(capacity > 0 && capacity and (capacity - 1) == 0) { "capacity must be a power of two" } }
    private val mask = capacity - 1
    private val times = LongArray(capacity)
    private val watts = IntArray(capacity)
    private var head = 0      // index of the oldest sample
    var size: Int = 0
        private set

    fun add(timeMs: Long, w: Int) {
        val idx = (head + size) and mask
        times[idx] = timeMs
        watts[idx] = w
        if (size < capacity) {
            size++
        } else {
            // Tail overwrite when full — also serves as the absolute-count cap
            // that the previous ArrayDeque-based code maintained via the
            // `size > POWER_BUFFER_MAX_SIZE` while-loop. Capacity is sized
            // above POWER_BUFFER_MAX_SIZE so the time-based trim is the
            // primary eviction path in practice.
            head = (head + 1) and mask
        }
    }

    /** Drop entries whose timestamp is ≤ [cutoffMs]. Amortised O(1) per [add]. */
    fun trimOlderThan(cutoffMs: Long) {
        while (size > 0 && times[head] <= cutoffMs) {
            head = (head + 1) and mask
            size--
        }
    }

    /** Watts at logical index `[0, size)`. Used by the in-place statistics
     *  loop in `shouldDeferBaseline` — replaces the prior `.toList()` snapshot
     *  + destructured `for ((_, w) in snapshot)` iteration. */
    fun wattAt(i: Int): Int = watts[(head + i) and mask]

    fun clear() { head = 0; size = 0 }
}
