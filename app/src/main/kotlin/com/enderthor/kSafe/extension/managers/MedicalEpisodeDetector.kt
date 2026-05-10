package com.enderthor.kSafe.extension.managers

import com.enderthor.kSafe.data.EmergencyReason
import com.enderthor.kSafe.data.KSafeConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * HR-based medical episode detection. Two parallel sub-detectors, both fed by a single
 * HR stream:
 *
 *  - **A) Flatline** — HR below [HR_FLATLINE_MAX_BPM] sustained for [HR_FLATLINE_DURATION_SEC]
 *    while the rider has been recently active. Catches asystole and severe bradycardia.
 *  - **B) Collapse** — current HR has dropped by ≥ [HR_COLLAPSE_DROP_FRACTION] vs. the
 *    5-min rolling baseline within the last [HR_COLLAPSE_WINDOW_SEC]. Catches vasovagal
 *    syncope and other events where the heart keeps beating at a low rate.
 *
 * Both sub-detectors short-circuit when:
 *  - No HR data has been received yet (`hrDataReceived = false`)
 *  - HR data is stale (`now - lastHrUpdateMs > HR_STALE_MS`)
 *  - Rider has not been active recently (`now - lastSpeedAboveActiveMs > ACTIVE_RECENT_MS`)
 *
 * Emissions go to [onIncident] with the appropriate sub-kind reason
 * ([EmergencyReason.MEDICAL_FLATLINE] or [EmergencyReason.MEDICAL_COLLAPSE]).
 *
 * Threading:
 *  - [updateHr] / [updateSpeed] are called from Karoo SDK callbacks — they only write
 *    `@Volatile` fields and append to a single per-detector ArrayDeque.
 *  - The monitor coroutine ticks every [MONITOR_TICK_MS] on [scope] and reads those
 *    fields. Stale reads are tolerated: detection latency is ~tick interval anyway.
 */
class MedicalEpisodeDetector(
    private val scope: CoroutineScope,
    private val onIncident: (EmergencyReason, Map<String, String>) -> Unit,
    private val calibLogger: CalibrationLogger? = null,
) {

    // ─── Constants (calibrated conservatively; expose to config only if real data justifies it) ──
    private val HR_FLATLINE_MAX_BPM        = 30
    private val HR_FLATLINE_DURATION_SEC   = 30
    private val HR_COLLAPSE_DROP_FRACTION  = 0.40f
    /** Recent window for the collapse detector. 15 s (was 10 s) — extending this requires the
     *  drop to be sustained for the full window before triggering, which filters out brief
     *  HR-strap artefacts (1–3 bad readings due to sweat / contact loss) that would otherwise
     *  pull a 10 s average down enough to cross 40 %. Detection latency for a real cardiac
     *  event grows by 5 s, which is negligible for the emergency response timeline. */
    private val HR_COLLAPSE_WINDOW_SEC     = 15
    private val HR_COLLAPSE_MIN_HISTORY_SEC = 240   // 4 min — cold-start guard for the rolling baseline
    private val HR_STALE_MS                = 15_000L
    private val ACTIVE_RECENT_MS           = 60_000L
    private val MONITOR_TICK_MS            = 5_000L
    private val ACTIVE_SPEED_KMH           = 5.0
    private val PERIODIC_LOG_INTERVAL_MS   = 120_000L  // 2 min, matching CrashDetectionManager.PERIODIC

    // ─── State (all `@Volatile` fields are read from the monitor coroutine) ──────────────
    @Volatile private var currentHrBpm        = 0
    @Volatile private var lastHrUpdateMs      = 0L
    @Volatile private var hrDataReceived      = false
    @Volatile private var lastSpeedKmh        = 0.0
    @Volatile private var lastSpeedAboveActiveMs = 0L
    @Volatile private var flatlineSinceMs     = 0L
    @Volatile private var collapseCooldownUntilMs = 0L
    @Volatile private var lastHrStaleState    = false
    @Volatile private var lastPeriodicLogMs   = 0L

    /** Rolling HR history. Push from [updateHr] (~1 Hz). Trim from the monitor coroutine. */
    private val hrSamples = ArrayDeque<Pair<Long, Int>>()
    private val HR_HISTORY_RETAIN_MS = 5L * 60_000L

    private var monitorJob: Job? = null
    @Volatile private var config = KSafeConfig()

    // ─── Public API ───────────────────────────────────────────────────────────────────────

    fun start(config: KSafeConfig) {
        this.config = config
        if (!config.medicalEpisodeEnabled) return
        monitorJob?.cancel()
        // Reset session-scoped state. Persistent fields (currentHrBpm) keep their last value
        // so reconnects between rides don't re-issue cold-start guards.
        flatlineSinceMs = 0L
        collapseCooldownUntilMs = 0L
        lastPeriodicLogMs = 0L
        lastHrStaleState = false
        monitorJob = scope.launch {
            while (true) {
                delay(MONITOR_TICK_MS)
                tick()
            }
        }
        Timber.d("MedicalEpisodeDetector started")
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        // Clear cross-session state so a new ride starts with a fresh baseline.
        // Otherwise a stop()→start() cycle can preserve stale HR samples that taint
        // the 5-min rolling baseline and trigger a false MEDICAL_COLLAPSE EMERGENCY
        // when the new ride starts at a much lower HR than the previous one ended.
        hrSamples.clear()
        hrDataReceived = false
        currentHrBpm = 0
        lastHrUpdateMs = 0L
        Timber.d("MedicalEpisodeDetector stopped")
    }

    @Suppress("unused") // called from KSafeExtension.initializeSystem() config-change flow
    fun updateConfig(config: KSafeConfig) {
        val wasEnabled = this.config.medicalEpisodeEnabled
        this.config = config
        if (!wasEnabled && config.medicalEpisodeEnabled) start(config)
        else if (wasEnabled && !config.medicalEpisodeEnabled) stop()
    }

    /**
     * Push a new HR reading. Idempotent w.r.t. the algorithm — duplicate bpm values are
     * allowed; the monitor coroutine derives state from timestamps.
     */
    fun updateHr(bpm: Int) {
        val now = System.currentTimeMillis()
        if (!hrDataReceived) {
            hrDataReceived = true
            Timber.d("MedicalEpisodeDetector: first HR reading $bpm bpm")
        }
        currentHrBpm = bpm
        lastHrUpdateMs = now
        hrSamples.addLast(now to bpm)
        // Trim is cheap and amortized O(1)
        while (hrSamples.isNotEmpty() && now - hrSamples.first().first > HR_HISTORY_RETAIN_MS) {
            hrSamples.removeFirst()
        }
    }

    fun updateSpeed(kmh: Double) {
        lastSpeedKmh = kmh
        if (kmh >= ACTIVE_SPEED_KMH) lastSpeedAboveActiveMs = System.currentTimeMillis()
    }

    // ─── Monitor tick (runs on `scope`, every MONITOR_TICK_MS) ────────────────────────────

    private fun tick() {
        val now = System.currentTimeMillis()

        // ── HR stale transition logging (once per change) ─────────────────────
        val isStale = hrDataReceived && (now - lastHrUpdateMs > HR_STALE_MS)
        if (isStale != lastHrStaleState) {
            lastHrStaleState = isStale
            if (isStale) {
                calibLogger?.log(CalibrationLogger.Event.HR_STALE) {
                    "last_bpm=$currentHrBpm,since_ms=${now - lastHrUpdateMs}"
                }
            }
        }

        // ── Sub-detector A: flatline ─────────────────────────────────────────
        evaluateFlatline(now, isStale)

        // ── Sub-detector B: collapse ─────────────────────────────────────────
        evaluateCollapse(now, isStale)

        // ── Periodic snapshot every 2 min ────────────────────────────────────
        if (calibLogger != null && calibLogger.isEnabled &&
            (now - lastPeriodicLogMs) > PERIODIC_LOG_INTERVAL_MS) {
            lastPeriodicLogMs = now
            val activeRecent = now - lastSpeedAboveActiveMs <= ACTIVE_RECENT_MS
            val collapseArmed = hasEnoughHistoryFor(now)
            val flatlineFor = if (flatlineSinceMs > 0) (now - flatlineSinceMs) / 1000 else 0
            val avg5min = computeAverageHr(now)
            calibLogger.log(CalibrationLogger.Event.HR_PERIODIC) {
                "bpm=$currentHrBpm,avg5min=$avg5min,speed=%.1f,active_recent=$activeRecent,flatline_for_s=$flatlineFor,collapse_armed=$collapseArmed".format(lastSpeedKmh)
            }
        }
    }

    private fun evaluateFlatline(now: Long, isStale: Boolean) {
        if (!hrDataReceived || isStale) {
            flatlineSinceMs = 0L
            return
        }
        if (now - lastSpeedAboveActiveMs > ACTIVE_RECENT_MS) {
            flatlineSinceMs = 0L
            return
        }
        if (currentHrBpm < HR_FLATLINE_MAX_BPM) {
            if (flatlineSinceMs == 0L) flatlineSinceMs = now
            val durationMs = (now - flatlineSinceMs)
            if (durationMs >= HR_FLATLINE_DURATION_SEC * 1000L) {
                Timber.d(">>> HR_FLATLINE fired: bpm=$currentHrBpm sustained for ${durationMs / 1000}s")
                calibLogger?.log(CalibrationLogger.Event.HR_FLATLINE) {
                    "bpm=$currentHrBpm,duration_s=${durationMs / 1000},speed=%.1f,threshold=$HR_FLATLINE_MAX_BPM".format(lastSpeedKmh)
                }
                flatlineSinceMs = 0L  // re-arm: requires HR to rise above threshold then fall again
                onIncident(EmergencyReason.MEDICAL_FLATLINE, mapOf("bpm" to currentHrBpm.toString()))
            }
        } else {
            flatlineSinceMs = 0L
        }
    }

    private fun evaluateCollapse(now: Long, isStale: Boolean) {
        if (!hrDataReceived || isStale) return
        if (now - lastSpeedAboveActiveMs > ACTIVE_RECENT_MS) return
        if (now < collapseCooldownUntilMs) return
        if (!hasEnoughHistoryFor(now)) return

        val baseline = computeAverageHrInWindow(
            now - HR_COLLAPSE_MIN_HISTORY_SEC * 1000L,
            now - HR_COLLAPSE_WINDOW_SEC * 1000L,
        )
        val recent   = computeAverageHrInWindow(
            now - HR_COLLAPSE_WINDOW_SEC * 1000L,
            now,
        )
        if (baseline <= 0 || recent <= 0) return

        val drop = (baseline - recent).toFloat() / baseline.toFloat()
        if (drop >= HR_COLLAPSE_DROP_FRACTION) {
            Timber.d(">>> HR_COLLAPSE fired: baseline=$baseline recent=$recent drop=${"%.2f".format(drop)}")
            calibLogger?.log(CalibrationLogger.Event.HR_COLLAPSE) {
                "bpm=$currentHrBpm,avg5min=$baseline,drop_pct=%.1f,window_s=$HR_COLLAPSE_WINDOW_SEC,speed=%.1f".format(drop * 100f, lastSpeedKmh)
            }
            collapseCooldownUntilMs = now + HR_COLLAPSE_MIN_HISTORY_SEC * 1000L
            onIncident(EmergencyReason.MEDICAL_COLLAPSE, mapOf(
                "bpm" to currentHrBpm.toString(),
                "baseline" to baseline.toString(),
            ))
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────────────

    private fun hasEnoughHistoryFor(now: Long): Boolean {
        if (hrSamples.size < 60) return false  // ~1 sample/s × 60s minimum
        val oldest = hrSamples.first().first
        return now - oldest >= HR_COLLAPSE_MIN_HISTORY_SEC * 1000L
    }

    private fun computeAverageHr(now: Long): Int =
        computeAverageHrInWindow(now - HR_COLLAPSE_MIN_HISTORY_SEC * 1000L, now)

    /**
     * Average bpm over [fromMs, toMs) — half-open on the upper bound so a sample whose
     * timestamp lands exactly on the boundary between the baseline and recent windows
     * is counted in only one of them (the lower / earlier window).
     */
    private fun computeAverageHrInWindow(fromMs: Long, toMs: Long): Int {
        var sum = 0L
        var count = 0
        for ((t, bpm) in hrSamples) {
            if (t in fromMs until toMs) {
                sum += bpm
                count++
            }
        }
        return if (count == 0) 0 else (sum / count).toInt()
    }
}
