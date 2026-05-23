package com.enderthor.kSafe.extension.managers

import com.enderthor.kSafe.data.EmergencyReason
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.extension.util.Clock
import com.enderthor.kSafe.extension.util.SystemClock
import com.enderthor.kSafe.extension.util.formatUs
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
    /**
     * Injectable clock. Production passes [SystemClock]; tests pass a fake to drive
     * sub-detector windows (flatline duration, collapse 4-min history) without waiting
     * real-time seconds inside a coroutine `delay`.
     */
    private val clock: Clock = SystemClock,
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

    /** Matches [CrashDetectionManager.GPS_STALE_MS]: when speed-value bytes have not changed
     *  for this long the Karoo SDK is replaying the last known value (GPS lock lost). For the
     *  COLLAPSE concurrent-speed gate we treat a stale stream as "not moving" to bias toward
     *  FP reduction — see H2 fix. */
    private val SPEED_STALE_MS             = 10_000L

    /** H3 fix — cross-check thresholds before FLATLINE. If cadence is above this OR power is
     *  above [POWER_ACTIVE_W] the rider is clearly still pedalling under load and the HR
     *  reading is almost certainly a strap dropout / contact loss, not asystole. The numbers
     *  are intentionally generous: a slow tourist climbing soft-pedalling sits around 40 RPM
     *  / 80 W, far above either floor. */
    private val CADENCE_ACTIVE_RPM         = 10.0
    private val POWER_ACTIVE_W             = 30

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

    /** H2 fix — timestamp of the most recent speed emission whose VALUE changed (or was an
     *  explicit zero, or the very first emission). Mirrors [CrashDetectionManager.speedLastChangeMs]
     *  — the SDK replays the last-known speed bit-exact while GPS is lost, so a stretch of
     *  identical non-zero values is the canonical staleness signature. */
    @Volatile private var speedLastChangeMs   = 0L

    // H3 fix — cadence + power cross-check inputs. Both are optional (no sensor paired is
    // common, especially power). Their "received" flags are sticky for the session: once a
    // sensor has emitted at least one sample we trust its absence-of-recent-value as a real
    // signal that the rider stopped pedalling. Before any sample arrives we treat the
    // cross-check as "not plumbed" and fall through to the existing FLATLINE logic.
    @Volatile private var currentCadenceRpm   = 0.0
    @Volatile private var cadenceDataReceived = false
    @Volatile private var currentPowerW       = 0
    @Volatile private var powerDataReceived   = false

    /**
     * Rolling HR history used by [computeAverageHrInWindow] for the COLLAPSE baseline
     * vs. recent-window comparison.
     *
     * Backed by two parallel primitive arrays (`LongArray` for timestamps + `IntArray`
     * for bpm) as a fixed-capacity ring — replaces the previous `ArrayDeque<Pair<Long,Int>>`.
     * The Pair-based deque allocated a fresh `Pair<Long, Int>` (and boxed the Int) on every
     * [updateHr] (~1 Hz) — for a 6 h ride that's ~22 k short-lived objects + ~22 k boxed
     * Integers per ride, all GC'd as samples age out of the 5-min window. The ring buffer
     * has zero allocations on the hot path.
     *
     * Capacity: HR rate is typically 1 Hz (ANT+ / BLE), occasionally 2 Hz on some straps.
     * 5 min × 2 Hz = 600 samples; 1024 leaves margin and a power-of-two for the head pointer.
     *
     * Threading: same `synchronized(this)`-style monitor pattern as before — all access goes
     * through the [hrHistoryLock] companion (the array refs themselves are stable, but
     * `head` / `size` / array contents must be coherent across the HR-callback thread and
     * the monitor coroutine on [scope]).
     */
    private val hrSamples = HrHistory(capacity = 1024)
    private val hrHistoryLock = Any()
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
        synchronized(hrHistoryLock) { hrSamples.clear() }
        hrDataReceived = false
        currentHrBpm = 0
        lastHrUpdateMs = 0L
        // H2 fix — reset speed-staleness bookkeeping so a new ride does not inherit a
        // stuck speedLastChangeMs from the previous ride (would either falsely report
        // stale forever or, worse, falsely report fresh during the new ride's cold start).
        speedLastChangeMs = 0L
        lastSpeedKmh = 0.0
        lastSpeedAboveActiveMs = 0L
        // H3 fix — reset cross-check inputs. The "sensor was paired" sticky flags belong
        // to a single ride session: if the rider unpairs / re-pairs between rides we want
        // the fresh ride to bootstrap cleanly.
        cadenceDataReceived = false
        currentCadenceRpm = 0.0
        powerDataReceived = false
        currentPowerW = 0
        Timber.d("MedicalEpisodeDetector stopped")
    }

    /**
     * See [HydrationTracker.updateConfig] for the rationale of the [isRecording] gate
     * on the auto-start branch. Same shape, same reason.
     */
    @Suppress("unused") // called from KSafeExtension.initializeSystem() config-change flow
    fun updateConfig(config: KSafeConfig, isRecording: Boolean) {
        val wasEnabled = this.config.medicalEpisodeEnabled
        this.config = config
        if (!wasEnabled && config.medicalEpisodeEnabled && isRecording) start(config)
        else if (wasEnabled && !config.medicalEpisodeEnabled) stop()
    }

    /**
     * Push a new HR reading. Idempotent w.r.t. the algorithm — duplicate bpm values are
     * allowed; the monitor coroutine derives state from timestamps.
     */
    fun updateHr(bpm: Int) {
        val now = clock.nowMs()
        if (!hrDataReceived) {
            hrDataReceived = true
            Timber.d("MedicalEpisodeDetector: first HR reading $bpm bpm")
        }
        currentHrBpm = bpm
        lastHrUpdateMs = now
        synchronized(hrHistoryLock) {
            hrSamples.add(now, bpm)
            hrSamples.trimOlderThan(now - HR_HISTORY_RETAIN_MS)
        }
    }

    fun updateSpeed(kmh: Double) {
        val now = clock.nowMs()
        // H2 fix — stamp [speedLastChangeMs] on real value changes, on explicit-zero
        // emissions (rider stopped at a light: GPS still alive even though the value is
        // bit-exact 0.0 across emissions), and on the very first emission (bootstrap).
        // A stuck non-zero value across emissions is the SDK's GPS-lost behaviour, so
        // leaving the timestamp untouched is what trips [isSpeedSignalStale] after
        // SPEED_STALE_MS. Same shape as CrashDetectionManager.updateSpeed.
        val changed = kmh != lastSpeedKmh
        if (changed || kmh == 0.0 || speedLastChangeMs == 0L) speedLastChangeMs = now
        lastSpeedKmh = kmh
        if (kmh >= ACTIVE_SPEED_KMH) lastSpeedAboveActiveMs = now
    }

    /**
     * H3 fix — optional cadence input for the FLATLINE cross-check. A bike cadence sensor
     * (ANT+/BLE) typically emits at 1–4 Hz; absent sensor → method never called and the
     * cross-check falls through to the existing FLATLINE logic (graceful degradation).
     */
    fun updateCadence(rpm: Double) {
        if (!cadenceDataReceived) {
            cadenceDataReceived = true
            Timber.d("MedicalEpisodeDetector: first cadence reading $rpm RPM")
        }
        currentCadenceRpm = rpm
    }

    /**
     * H3 fix — optional power input for the FLATLINE cross-check. Power meters emit at
     * 1 Hz typically; absent sensor → method never called and the cross-check is skipped
     * (see [updateCadence] for the graceful-degradation rationale).
     */
    fun updatePower(w: Int) {
        if (!powerDataReceived) {
            powerDataReceived = true
            Timber.d("MedicalEpisodeDetector: first power reading $w W")
        }
        currentPowerW = w
    }

    /**
     * H2 fix — staleness for the concurrent speed gate. Returns `true` iff we have ever
     * received a speed sample AND the value has not changed (and was non-zero) for
     * [SPEED_STALE_MS] — the canonical signature of the SDK replaying its last-known
     * value while GPS lock is lost. Conservative on cold start: if no speed has arrived
     * yet, we report stale (no concurrent confirmation possible).
     */
    private fun isSpeedSignalStale(now: Long): Boolean {
        if (speedLastChangeMs == 0L) return true
        return (now - speedLastChangeMs) > SPEED_STALE_MS
    }

    // ─── Monitor tick (runs on `scope`, every MONITOR_TICK_MS) ────────────────────────────

    /**
     * Internal so unit tests in the same package can drive ticks deterministically against
     * a fake [Clock] without having to wait on the production [delay]-based monitor coroutine.
     * Production still drives this from [start]'s `monitorJob`.
     */
    internal fun tick() {
        val now = clock.nowMs()

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
                "bpm=$currentHrBpm,avg5min=$avg5min,speed=%.1f,active_recent=$activeRecent,flatline_for_s=$flatlineFor,collapse_armed=$collapseArmed".formatUs(lastSpeedKmh)
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
                // H3 fix — cross-check against cadence / power before firing. A loose HR
                // strap (sweat, jersey shift, ANT+ dropout) routinely emits readings under
                // 30 bpm for 30 s+ while the rider is still pedalling normally; without
                // a cross-check that's enough to satisfy FLATLINE and trigger an
                // EMERGENCY-level countdown + outbound SOS. Cadence and power are both
                // optional inputs: if neither sensor has ever published, fall through to
                // the original behaviour (graceful degradation — riders without a power
                // meter or cadence sensor still have HR straps, so the detector must
                // still work for them). If EITHER signal is plumbed and indicates the
                // rider is still active (cadence > 10 RPM or power > 30 W), suppress
                // the fire and reset the timer.
                val cadenceSaysActive = cadenceDataReceived && currentCadenceRpm > CADENCE_ACTIVE_RPM
                val powerSaysActive = powerDataReceived && currentPowerW > POWER_ACTIVE_W
                if (cadenceSaysActive || powerSaysActive) {
                    Timber.d(
                        "HR_FLATLINE suppressed by cross-check: bpm=$currentHrBpm " +
                                "cadence=%.0f power=$currentPowerW (cadence_data=$cadenceDataReceived power_data=$powerDataReceived)"
                            .formatUs(currentCadenceRpm)
                    )
                    calibLogger?.log(CalibrationLogger.Event.HR_FLATLINE) {
                        "bpm=$currentHrBpm,suppressed=true,reason=cross_check,cadence=%.0f,power=$currentPowerW,duration_s=${durationMs / 1000}".formatUs(currentCadenceRpm)
                    }
                    // Re-arm: the rider is patently still riding — treat as if HR had
                    // never dropped. A subsequent genuine drop must accumulate its own
                    // 30 s window.
                    flatlineSinceMs = 0L
                    return
                }
                Timber.d(">>> HR_FLATLINE fired: bpm=$currentHrBpm sustained for ${durationMs / 1000}s")
                calibLogger?.log(CalibrationLogger.Event.HR_FLATLINE) {
                    "bpm=$currentHrBpm,duration_s=${durationMs / 1000},speed=%.1f,threshold=$HR_FLATLINE_MAX_BPM,cadence=%.0f,power=$currentPowerW,cadence_data=$cadenceDataReceived,power_data=$powerDataReceived".formatUs(lastSpeedKmh, currentCadenceRpm)
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
        // H2 fix — concurrent speed gate. The previous "active in the last 60 s" window
        // (`now - lastSpeedAboveActiveMs > ACTIVE_RECENT_MS`) routinely false-fired on a
        // trained cyclist finishing a hard effort and stopping: a 160 → 80 bpm drop in
        // 45–60 s is normal post-exercise parasympathetic rebound, easily crossing the
        // 40 % gate while the residual 60 s window still considers the rider "active".
        // Require the rider to be moving NOW (≥ ACTIVE_SPEED_KMH) with a fresh speed
        // signal. A stale speed signal (GPS lost) is treated as not-moving to bias
        // toward FP suppression — losing a true mid-ride collapse to GPS loss is the
        // less harmful failure mode than dispatching emergency services for a café stop.
        if (lastSpeedKmh < ACTIVE_SPEED_KMH || isSpeedSignalStale(now)) return
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
            Timber.d(">>> HR_COLLAPSE fired: baseline=$baseline recent=$recent drop=${"%.2f".formatUs(drop)}")
            calibLogger?.log(CalibrationLogger.Event.HR_COLLAPSE) {
                "bpm=$currentHrBpm,avg5min=$baseline,drop_pct=%.1f,window_s=$HR_COLLAPSE_WINDOW_SEC,speed=%.1f".formatUs(drop * 100f, lastSpeedKmh)
            }
            collapseCooldownUntilMs = now + HR_COLLAPSE_MIN_HISTORY_SEC * 1000L
            onIncident(EmergencyReason.MEDICAL_COLLAPSE, mapOf(
                "bpm" to currentHrBpm.toString(),
                "baseline" to baseline.toString(),
            ))
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────────────

    private fun hasEnoughHistoryFor(now: Long): Boolean = synchronized(hrHistoryLock) {
        if (hrSamples.size < 60) return@synchronized false  // ~1 sample/s × 60s minimum
        val oldest = hrSamples.firstTimeOrZero()
        oldest > 0 && now - oldest >= HR_COLLAPSE_MIN_HISTORY_SEC * 1000L
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
        synchronized(hrHistoryLock) {
            hrSamples.forEachInRange(fromMs, toMs) { bpm ->
                sum += bpm
                count++
            }
        }
        return if (count == 0) 0 else (sum / count).toInt()
    }
}

/**
 * Fixed-capacity ring buffer of (timestamp, bpm) pairs, stored in two parallel primitive
 * arrays. Zero allocations on add / iterate. NOT thread-safe — callers must serialise
 * access externally (see [MedicalEpisodeDetector.hrHistoryLock]).
 *
 * Capacity is a power of two so the modulo can be a bitwise mask. Designed for the
 * write-once-read-many access pattern of the COLLAPSE detector — `forEachInRange` walks
 * the live range using indices, no iterator allocation.
 */
internal class HrHistory(private val capacity: Int) {
    init { require(capacity > 0 && capacity and (capacity - 1) == 0) { "capacity must be a power of two" } }
    private val mask = capacity - 1
    private val times = LongArray(capacity)
    private val bpms = IntArray(capacity)
    private var head = 0      // index of the oldest sample
    var size: Int = 0
        private set

    fun add(timeMs: Long, bpm: Int) {
        val idx = (head + size) and mask
        times[idx] = timeMs
        bpms[idx] = bpm
        if (size < capacity) {
            size++
        } else {
            // Buffer full — overwrite oldest. In production with capacity 1024 and a 5-min
            // retention this branch never fires (5 min × 2 Hz max = 600 < 1024), but the
            // guard keeps the ring safe under any sensor rate.
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

    /** Timestamp of the oldest live sample, or `0` if the ring is empty. */
    fun firstTimeOrZero(): Long = if (size == 0) 0L else times[head]

    /** Calls [action] with each bpm whose timestamp lies in `[fromMs, toMs)`. */
    inline fun forEachInRange(fromMs: Long, toMs: Long, action: (bpm: Int) -> Unit) {
        // Walk live entries: index from `head`, `size` items, wrapping via bitmask.
        // We expose `head` / `size` indirectly through the companion accessors so this
        // remains an inline function; using the public read-only fields is the trade-off.
        val h = headIndex()
        val n = size
        val cap = capacityValue()
        val m = cap - 1
        val t = timesArray()
        val b = bpmsArray()
        var i = 0
        while (i < n) {
            val idx = (h + i) and m
            val ts = t[idx]
            if (ts in fromMs until toMs) action(b[idx])
            i++
        }
    }

    fun clear() { head = 0; size = 0 }

    // Exposed for inline access by forEachInRange — see Kotlin's "publishedApi" pattern.
    // Callers must hold the external lock; these are NOT a public stable API.
    @PublishedApi internal fun headIndex(): Int = head
    @PublishedApi internal fun capacityValue(): Int = capacity
    @PublishedApi internal fun timesArray(): LongArray = times
    @PublishedApi internal fun bpmsArray(): IntArray = bpms
}
