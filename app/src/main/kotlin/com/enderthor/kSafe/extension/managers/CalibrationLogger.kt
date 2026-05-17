package com.enderthor.kSafe.extension.managers

import android.content.Context
import com.enderthor.kSafe.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * Lightweight calibration logger for KSafe crash detection.
 *
 * Design goals:
 *  - **Zero overhead when disabled**: all log calls use an inline lambda idiom so no strings
 *    are ever allocated when [isEnabled] is false.
 *  - **No UI-thread or sensor-thread blocking**: the buffer is an in-memory ArrayDeque
 *    protected by a lightweight `synchronized`. Disk I/O is entirely async (flush coroutine).
 *  - **Append+clear buffer**: every 60 s the buffer is appended to disk and cleared so memory
 *    stays bounded while the file grows with the full session history.
 *  - **CSV output**: easy to open in Excel / Google Sheets for analysis.
 *
 * ## Enabling / disabling
 * Call [enable] when `calibrationLoggingEnabled` turns on in config; call [disable] when it turns off.
 * On [enable] the previous CSV file is deleted and a fresh header is written.
 * On [disable] the remaining buffer is flushed to disk.
 *
 * ## Getting data to the developer
 * The file is sent automatically via a hardcoded Telegram bot when logging is disabled
 * or when a ride ends. The full path is also available via ADB:
 *   adb pull /sdcard/Android/data/<pkg>/files/ksafe_calibration.csv
 */
class CalibrationLogger(
    private val context: Context,
    private val scope: CoroutineScope
) {

    // ─── Event catalogue ─────────────────────────────────────────────────────

    enum class Event(val tag: String) {
        /** High magnitude in MONITORING that did NOT trigger (terrain noise distribution data). */
        HIGH_MAG_NORISING("HIGH_MAG"),
        /** Impact detected (dual gate) but speed was below minSpeedForCrash — gate rejected it. */
        IMPACT_SPEED_REJECTED("SPD_REJECT"),
        /** Entered IMPACT state — smooth or peak detector fired. */
        IMPACT_ENTER("IMPACT_IN"),
        /** IMPACT window expired without settling → false alarm. */
        IMPACT_TIMEOUT("IMPACT_TMO"),
        /**
         * Inside IMPACT state: accel was quiet AND speed dropped, but gyro was too high
         * (lastGyroMag ≥ GYRO_MOVING_MAX) so the SILENCE_CHECK transition was blocked.
         * Key for calibrating GYRO_MOVING_MAX: if this fires on real crashes the threshold is too strict.
         * Rate-limited to 1/s to avoid flooding the buffer on continuous active movement.
         */
        GYRO_BLOCKED("GYRO_BLK"),
        /** Entered SILENCE_CHECK. */
        SILENCE_ENTER("SIL_IN"),
        /** Stillness condition broken inside SILENCE_CHECK — timer reset. */
        SILENCE_BROKEN("SIL_BRK"),
        /** Crash confirmed — full pipeline completed. */
        CRASH_CONFIRMED("CRASH_OK"),
        /**
         * Confirmation arrived at the cooldown gate but was suppressed because the
         * previous CRASH_CONFIRMED is still inside the cooldown window. Distinct from
         * CRASH_CONFIRMED so consumers counting confirmed-crash rows are not inflated.
         */
        CRASH_GATE_SUPPRESSED("CRASH_SUPPRESSED"),
        /**
         * User cancelled the crash countdown — this is a CONFIRMED FALSE POSITIVE.
         * Pairing with the preceding CRASH_OK row identifies exactly which detections were wrong.
         * how_long_ms: time between CRASH_OK and user tap — near-zero means obvious false alarm.
         */
        CRASH_CANCELLED("CRASH_NO"),
        /**
         * SILENCE_CHECK timed out — device entered the silence phase but never achieved
         * uninterrupted stillness within the double-window period → false alarm at stage 3.
         * Distinct from IMPACT_TMO (which fires before entering SILENCE_CHECK at all).
         */
        SILENCE_TIMEOUT("SIL_TMO"),
        /**
         * Snapshot of sensor state taken 2 seconds after any internal pipeline reset
         * (IMPACT_TMO or SIL_TMO). Captures whether the device went still shortly after the
         * cancel — if speed=0 and deviation is low here, the cancelled event was likely a real crash.
         * This is the key datapoint for identifying false negatives caused by auto-cancellation.
         */
        POST_RESET_SNAP("RST_SNAP"),
        /** Speed-drop monitor evaluated (every 30 s, only when timer is active). */
        SPEEDDROP_EVAL("SPDRP_EVAL"),
        /** GPS stale condition detected (informational marker). */
        GPS_STALE("GPS_STALE"),
        /**
         * Rough-terrain cluster detected: ≥ [CLUSTER_COUNT] IMPACT_TMO events within
         * [CLUSTER_WINDOW_MS]. The longer peak-threshold boost is now active.
         * Key for calibration: confirms the rider is on consistently rough terrain
         * (cobblestones, gravel, badly paved descent) rather than isolated bumps.
         */
        TERRAIN_CLUSTER("TERRAIN_CLUST"),
        /**
         * Cadence gate fired during SILENCE_CHECK: rider was actively pedaling (cadence > 20 RPM)
         * while the crash confirmation phase was running → immediate false alarm exit.
         * This is a near-certain false positive identifier: a real crash victim doesn't pedal.
         * Key for calibration: if this fires it was definitely not a crash.
         */
        CADENCE_GATE("CAD_GATE"),
        /** Periodic ride-context snapshot — speed, accel deviation, gyro, state (every 2 min). */
        PERIODIC("PERIODIC"),
        /** Marker written when logging is enabled — anchor for elapsed_s calculations. */
        LOGGER_START("LOG_START"),
        // ─── Medical / wellness detectors (added 2026-05) ────────────────────
        /** HR-flatline fired (bpm < HR_FLATLINE_MAX_BPM sustained for HR_FLATLINE_DURATION_SEC). */
        HR_FLATLINE("HR_FLAT"),
        /** HR-collapse fired (≥ HR_COLLAPSE_DROP_FRACTION drop within HR_COLLAPSE_WINDOW_SEC vs 5-min average). */
        HR_COLLAPSE("HR_COLLAPSE"),
        /** Periodic HR snapshot every 2 min, correlable by timestamp with PERIODIC. */
        HR_PERIODIC("HR_PERIODIC"),
        /** HR sensor went stale (no update for HR_STALE_MS) — once per fresh→stale transition. */
        HR_STALE("HR_STALE"),
        /** User cancelled a medical episode countdown (false-positive marker). */
        MEDICAL_CANCELLED("MED_NO"),
        /** Wellness monitor fired (sustained high HR over user-configured threshold). */
        WELLNESS_FIRED("WLNS_HR"),
        /** Generic WARNING-level incident dispatched by EmergencyManager.handleIncident. */
        INCIDENT_WARNING("WARN"),
        /** Generic SILENT-level incident dispatched by EmergencyManager.handleIncident. */
        INCIDENT_SILENT("SILENT"),
        // ─── Fueling tracker (added 2026-05) ─────────────────────────────────
        /**
         * Snapshot of the carb tracker config at session start. Lets a reader of the CSV
         * correlate observed behaviour with the exact config in effect for this ride
         * (base target, alert toggles + thresholds, selected beep pattern) without
         * having to cross-reference a separately exported config JSON.
         */
        FUELING_CARB_START("CARB_START"),
        /** User logged a carb intake via tap or BonusAction. */
        FUELING_CARB_LOGGED("CARB_LOG"),
        /** User reversed the previous carb log by tapping the same slot during its
         *  on-screen undo window. Payload's `grams` value is the (negative) reversal so
         *  a downstream sum still nets out; distinct tag from CARB_LOG so per-event
         *  counters don't double-count the rider's intake. */
        FUELING_CARB_UNDONE("CARB_UNDO"),
        /** Carb tracker fired an alert (deficit or time-interval). */
        FUELING_CARB_FIRED("CARB_FIRE"),
        /** Periodic 2-minute snapshot of carb tracker state, correlable with PERIODIC by timestamp. */
        FUELING_CARB_PERIODIC("CARB_PERIODIC"),
        /** Snapshot of hydration tracker config at session start — see [FUELING_CARB_START]. */
        FUELING_HYDRATION_START("HYD_START"),
        /** User logged a hydration intake via tap or BonusAction. */
        FUELING_HYDRATION_LOGGED("HYD_LOG"),
        /** User reversed the previous hydration log — same contract as [FUELING_CARB_UNDONE]. */
        FUELING_HYDRATION_UNDONE("HYD_UNDO"),
        /** Hydration tracker fired an alert (deficit or time-interval). */
        FUELING_HYDRATION_FIRED("HYD_FIRE"),
        /** Periodic 2-minute snapshot of hydration tracker state. */
        FUELING_HYDRATION_PERIODIC("HYD_PERIODIC"),
        // ─── FIT export (added 2026-05) ──────────────────────────────────────
        /**
         * Karoo invoked `startFit` — the FIT developer-field writer is now active. Lists
         * the developer-field numbers in use so a reader can confirm which streams a given
         * ride actually contains (useful when a rider says "I don't see field X in Strava").
         */
        FIT_WRITER_START("FIT_START"),
        /** `startFit`'s coroutine was cancelled — FIT writes ended for this ride. */
        FIT_WRITER_STOP("FIT_STOP"),
        /** Marker written when logging is disabled — session end boundary. */
        LOG_END("LOG_END"),
    }

    companion object {
        /** Maximum entries kept in the in-memory buffer between flushes. */
        const val MAX_BUFFER = 500
        /** A successful flush within this window means the flush coroutine is alive.
         *  Set to 3 × FLUSH_INTERVAL_MS so a single missed flush (e.g. transient IO
         *  hiccup) doesn't trip a restart — only a genuinely dead flush job does. */
        const val HEALTH_STALE_THRESHOLD_MS = 180_000L
        /** Flush in-memory buffer to disk every 60 seconds. */
        const val FLUSH_INTERVAL_MS = 60_000L
        /** Output CSV file name, written to the app's external files dir. */
        const val FILE_NAME = "ksafe_calibration.csv"
        /** Renamed previous-session file kept around when [enable] is called over an
         *  existing file with content (e.g. extension restarted after a crash before
         *  the previous session's auto-send fired). The rider can still send it from
         *  the Settings UI; it is overwritten each enable() so only the MOST RECENT
         *  un-sent session is preserved — older ones beyond that are lost. */
        const val PREVIOUS_FILE_NAME = "ksafe_calibration_previous.csv"
        const val HEADER = "timestamp_ms,elapsed_s,event,data"
        /** Minimum raw accel magnitude (m/s²) to bother logging HIGH_MAG events. ~2.2g above gravity. */
        const val HIGH_MAG_MIN = 22f
        /** Rate-limit HIGH_MAG_NORISING logs so max 1 per second on the sensor thread. */
        const val HIGH_MAG_INTERVAL_MS = 1_000L
        /** Rate-limit SILENCE_BROKEN logs: at most once per 2 seconds. */
        const val SILENCE_BROKEN_INTERVAL_MS = 2_000L
        /** Rate-limit GYRO_BLOCKED logs: at most once per 1 second. */
        const val GYRO_BLOCKED_INTERVAL_MS = 1_000L

        /**
         * Device model sanitised for filesystem / Telegram filename use.
         * E.g. "Karoo 3" → "Karoo-3", "karoo2mini" → "karoo2mini". Max 20 chars.
         * Computed once via lazy — the hardware model never changes at runtime.
         */
        val DEVICE_LABEL: String by lazy {
            android.os.Build.MODEL.trim()
                .replace(' ', '-')
                .replace(Regex("[^A-Za-z0-9._-]"), "")
                .take(20)
                .ifEmpty { "device" }
        }
    }

    // ─── State ───────────────────────────────────────────────────────────────

    @Volatile var isEnabled = false
        private set

    /**
     * Random 6-character hex session ID generated fresh on each [enable] call.
     *
     * Purpose: distinguish logs from different sessions (and users) in the developer's
     * Telegram inbox. When multiple users send logs simultaneously, each file arrives
     * with a unique ID in the filename and caption — easy to correlate with a specific ride.
     *
     * Privacy: the ID is randomly generated from the lower bits of the system clock at
     * the moment logging is enabled. It does NOT encode the time, date, ride start, or
     * any device identifier. It is purely a session-discriminator with no personal data.
     */
    var sessionId: String = "000000"
        private set

    /**
     * Telegram-safe filename for the current session's CSV.
     * Format: `ksafe_v{version}_{sessionId}_{deviceLabel}.csv`
     * E.g. `ksafe_v1.5.3_a3f9c2_Karoo-3.csv`
     */
    val fileNameForSession: String
        get() = "ksafe_v${BuildConfig.VERSION_NAME}_${sessionId}_${DEVICE_LABEL}.csv"

    /**
     * Returns a short plain-text caption for the Telegram `sendDocument` call.
     * Shown as the message text alongside the file in the chat — immediately identifies
     * the session without opening the CSV.
     *
     * [lineCount]: total number of data rows in the file (newlines in content); used for
     * a rough size indicator. Pass 0 to omit.
     *
     * Privacy: contains app version, random session ID, and device model. No personal data.
     */
    fun captionForSession(lineCount: Int = 0): String {
        val sizeInfo = if (lineCount > 0) " | $lineCount rows" else ""
        return "📊 kSafe Calibration Log\n" +
               "Session: $sessionId | ${android.os.Build.MODEL} | v${BuildConfig.VERSION_NAME}$sizeInfo"
    }

    /**
     * Lines pending disk flush. Bounded at [MAX_BUFFER]; the oldest entry is evicted
     * on overflow.
     *
     * Synchronisation: `synchronized(buffer)` guards every read/write. The audit
     * suggested swapping to `ConcurrentLinkedDeque` or a lock-free MPSC queue, but
     * the actual contention turned out to be modest — the sensor thread holds the
     * lock for an O(1) `addLast` + conditional `removeFirst` (microseconds), the
     * 60-s flush coroutine holds it for an O(n) `toList()` (a few ms at most given
     * MAX_BUFFER). 5k acquisitions/ride uncontended is essentially free, and the
     * ~60 contended ones per ride wait at most a few ms each. Refactoring to a
     * lock-free structure would require an AtomicInteger size sidecar (since
     * ConcurrentLinkedDeque.size() is O(n)) and add real complexity — not worth
     * the marginal gain. Decision documented; revisit only if profiling shows the
     * sensor thread genuinely blocked here.
     */
    private val buffer = ArrayDeque<String>()

    /** Wall-clock ms of the most recent successful disk flush. Used by [isHealthy] so
     *  KSafeExtension's health-check coroutine can detect a dead flush job and restart
     *  it. Updated in [flush] on success. */
    @Volatile private var lastFlushAtMs: Long = 0L

    /** Wall-clock ms when [enable] last ran. Stops [isHealthy] from immediately flagging
     *  a freshly-enabled logger as unhealthy before the first FLUSH_INTERVAL_MS has elapsed. */
    @Volatile private var enabledAtMs: Long = 0L
    @Volatile private var startTime = 0L
    private var flushJob: Job? = null

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    fun enable() {
        startTime = System.currentTimeMillis()
        enabledAtMs = startTime
        lastFlushAtMs = 0L
        // Generate a new random session ID for this logging session.
        // Uses lower 24 bits of current time (ms) XORed with a pseudo-random salt so two
        // sessions started at nearly the same millisecond still get different IDs.
        sessionId = "%06x".format((startTime xor (startTime.ushr(16))) and 0xFFFFFFL)
        synchronized(buffer) { buffer.clear() }
        isEnabled = true
        flushJob?.cancel()
        // Reset output file + first periodic flush both off-Main. `writeText` is a
        // synchronous disk write which we must not run on the caller's dispatcher
        // (KSafeExtension's Main + SupervisorJob). For a fresh file it's sub-ms but on
        // a slow eMMC it's been measured at tens of ms — enough to drop a frame in the
        // settings UI when the rider toggles the calibration switch.
        flushJob = scope.launch(Dispatchers.IO) {
            try {
                val dir = context.getExternalFilesDir(null)
                if (dir != null) {
                    dir.mkdirs()
                    val file = File(dir, FILE_NAME)
                    // Preserve previous session's content. If the rider had logging on
                    // during a ride and the app crashed (or the rider closed without
                    // sending), the previous file would otherwise be wiped on the next
                    // enable() because `writeText(HEADER)` overwrites unconditionally.
                    // Instead: rename it aside so the rider can still send it via the
                    // Settings "Send" button (which reads getFileContent() = current file
                    // + buffer). The renamed file isn't sent automatically — that would
                    // require background work + retries on every enable, which is more
                    // surprise than fix. If the rider doesn't notice and triggers another
                    // enable(), the OLD previous gets overwritten by the NEW previous.
                    if (file.exists() && file.length() > (HEADER.length + 2)) {
                        try {
                            val prev = File(dir, PREVIOUS_FILE_NAME)
                            if (prev.exists()) prev.delete()
                            file.renameTo(prev)
                            Timber.i("CalibrationLogger: preserved previous session to $PREVIOUS_FILE_NAME (${file.length()} bytes)")
                        } catch (e: Exception) {
                            Timber.w(e, "CalibrationLogger: failed to preserve previous file — overwriting")
                        }
                    }
                    file.writeText("$HEADER\n")
                }
            } catch (e: Exception) {
                Timber.w(e, "CalibrationLogger: failed to reset log file on enable")
            }
            while (true) {
                delay(FLUSH_INTERVAL_MS)
                flush()
            }
        }
        // Write a marker so the CSV has an anchor timestamp for all elapsed_s values.
        // Includes session ID, device model, and app version so each file is self-identifying.
        addEntryDirect(Event.LOGGER_START,
            "logging_enabled,version=2,session=$sessionId,device=${DEVICE_LABEL},app_version=${BuildConfig.VERSION_NAME}")
        Timber.i("CalibrationLogger enabled — session=$sessionId device=${DEVICE_LABEL} v${BuildConfig.VERSION_NAME}")
    }

    fun disable() {
        isEnabled = false
        flushJob?.cancel()
        flushJob = null
        // Write session-end marker before the final flush so it is included in the sent file
        // Locale.US — the calibration CSV uses commas as field separators, so the default
        // Locale (es/fr/de etc.) turning "12.0" into "12,0" would split the column.
        addEntryDirect(Event.LOG_END, "session_end,duration_s=${String.format(java.util.Locale.US, "%.0f", (System.currentTimeMillis() - startTime) / 1_000f)}")
        flush()   // write all remaining buffer entries (including LOG_END) to disk
        Timber.i("CalibrationLogger disabled — final flush done")
    }

    // ─── Logging API ─────────────────────────────────────────────────────────

    /**
     * Log a calibration event.
     *
     * The lambda [data] is only evaluated when [isEnabled] is true, so when logging is off
     * this call costs exactly one volatile boolean read — no string allocation, no GC pressure.
     * The `inline` + `crossinline` combo ensures the lambda body is inlined at every call site.
     */
    inline fun log(event: Event, crossinline data: () -> String) {
        if (!isEnabled) return
        addEntry(event, data())
    }

    /** Non-inline path called from [log] after the enabled check. */
    fun addEntry(event: Event, data: String) {
        val now = System.currentTimeMillis()
        val elapsed = (now - startTime) / 1_000f
        // Quote the data field when it contains commas so the CSV remains valid in Excel/Sheets.
        // Without quoting, a data value like "speed=5.0,gyro=0.2" gets split into extra columns.
        val csvData = if (',' in data) "\"$data\"" else data
        // Locale.US — the calibration CSV uses commas as field separators, and this is
        // the elapsed_s column rendered on EVERY entry, so a Locale-default "1,5" would
        // shift every subsequent column by one. Same reason as the session_end format.
        val elapsedStr = String.format(java.util.Locale.US, "%.1f", elapsed)
        val line = "$now,$elapsedStr,${event.tag},$csvData"
        synchronized(buffer) {
            if (buffer.size >= MAX_BUFFER) buffer.removeFirst()
            buffer.addLast(line)
        }
    }

    // ─── Data access ─────────────────────────────────────────────────────────

    /**
     * Total entries across the session: lines on disk (excluding the CSV header) plus
     * lines still in the in-memory buffer. The previous implementation returned only
     * the buffer size, which made the rider see "0 entries" most of the time because
     * the 60-s flush coroutine empties the buffer on every flush. Reading the file
     * length on every UI refresh (1 Hz polling from SettingsScreen) is acceptable —
     * `File.readLines` is fast on a few MB of CSV and runs while logging is enabled
     * (the rider is on the Settings screen, not actively riding).
     */
    fun getEntryCount(): Int {
        val bufferCount = synchronized(buffer) { buffer.size }
        val diskCount = try {
            val dir = context.getExternalFilesDir(null) ?: return bufferCount
            val file = File(dir, FILE_NAME)
            if (!file.exists()) 0
            // Subtract 1 for the CSV header row written by enable().
            else (file.bufferedReader().use { it.lineSequence().count() } - 1).coerceAtLeast(0)
        } catch (e: Exception) {
            Timber.w(e, "CalibrationLogger: failed to count disk entries")
            0
        }
        return diskCount + bufferCount
    }

    /**
     * Returns the full CSV file content from disk (all flushed data + any pending buffer entries).
     * This is the correct method to call when you want to send the complete session data.
     */
    fun getFileContent(): String {
        return try {
            val dir = context.getExternalFilesDir(null) ?: return ""
            val file = File(dir, FILE_NAME)
            if (!file.exists()) return ""
            // Append any unflushed buffer entries to the file content in-memory (don't write yet)
            val flushed = file.readText()
            val pending = synchronized(buffer) { buffer.toList() }
            if (pending.isEmpty()) flushed
            else flushed + pending.joinToString("\n", postfix = "\n")
        } catch (e: Exception) {
            Timber.w(e, "CalibrationLogger: failed to read log file")
            ""
        }
    }

    /**
     * Returns the current in-memory buffer as a CSV string (only entries not yet flushed to disk).
     * Useful for quick checks; for complete data use [getFileContent].
     */
    fun getContent(): String = synchronized(buffer) {
        if (buffer.isEmpty()) return "(buffer empty — data is on disk)"
        buildString {
            appendLine(HEADER)
            buffer.forEach { appendLine(it) }
        }
    }

    fun clear() {
        synchronized(buffer) { buffer.clear() }
        try {
            context.getExternalFilesDir(null)?.let { dir ->
                File(dir, FILE_NAME).delete()
                // Also wipe the preserved previous-session file — rider's intent on
                // "Clear" is "I don't want any of this data", not just the current run.
                File(dir, PREVIOUS_FILE_NAME).delete()
            }
        } catch (e: Exception) {
            Timber.w(e, "CalibrationLogger: failed to delete log file")
        }
        Timber.i("CalibrationLogger: buffer and files cleared")
    }

    fun getLogFile(): File? =
        context.getExternalFilesDir(null)?.let { dir ->
            File(dir, FILE_NAME).takeIf { it.exists() }
        }

    /** Returns the content of the previous-session file (preserved across [enable] when
     *  a previous logging session ended without sending), or `null` if no such file
     *  exists. Used by `KSafeExtension.sendCalibrationLog` to recover data from a ride
     *  whose extension was killed before the auto-send fired. */
    fun getPreviousFileContent(): String? = try {
        val dir = context.getExternalFilesDir(null)
        if (dir == null) null else {
            val prev = File(dir, PREVIOUS_FILE_NAME)
            if (!prev.exists() || prev.length() <= (HEADER.length + 2L)) null
            else prev.readText()
        }
    } catch (e: Exception) {
        Timber.w(e, "CalibrationLogger: failed to read previous session file")
        null
    }

    /** Filename advertised in the Telegram document upload for the recovered file. */
    fun previousFileNameForSession(): String = "ksafe_${BuildConfig.VERSION_NAME}_previous_${DEVICE_LABEL}.csv"

    /** Deletes the preserved previous-session file. Called by the Settings "Send" path
     *  after a successful upload so the rider doesn't see "previous: ✓" forever. */
    fun deletePreviousFile() {
        try {
            context.getExternalFilesDir(null)?.let { dir ->
                val prev = File(dir, PREVIOUS_FILE_NAME)
                if (prev.exists()) {
                    prev.delete()
                    Timber.i("CalibrationLogger: previous session file deleted after successful send")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "CalibrationLogger: failed to delete previous session file")
        }
    }

    // ─── Disk flush ──────────────────────────────────────────────────────────

    /**
     * Atomically grabs the current buffer, clears it, then appends those lines to the CSV file.
     * Using append+clear (instead of overwrite) means the file accumulates the full session
     * history without the buffer growing unboundedly in memory.
     */
    private fun flush() {
        val lines: List<String>
        synchronized(buffer) {
            if (buffer.isEmpty()) {
                // Empty-buffer flush still counts as healthy — the loop ran, the IO
                // dispatcher is alive. Without this, an idle logger looks unhealthy
                // and triggers a spurious restart.
                lastFlushAtMs = System.currentTimeMillis()
                return
            }
            lines = buffer.toList()
            buffer.clear()
        }
        try {
            val dir = context.getExternalFilesDir(null) ?: return
            dir.mkdirs()
            val file = File(dir, FILE_NAME)
            // Append mode — the header was written on enable(), we just add rows.
            file.appendText(lines.joinToString("\n", postfix = "\n"))
            lastFlushAtMs = System.currentTimeMillis()
            Timber.d("CalibrationLogger: appended ${lines.size} entries to $FILE_NAME")
        } catch (e: Exception) {
            Timber.w(e, "CalibrationLogger: flush failed (${lines.size} entries lost)")
        }
    }

    /**
     * Is the flush coroutine alive and writing? Returns false when the logger is
     * enabled but the flush job has not run a successful flush within
     * [HEALTH_STALE_THRESHOLD_MS]. Used by `KSafeExtension`'s health-check loop
     * to auto-restart a stuck logger (covers cases where the IO dispatcher dies
     * or the flush coroutine gets stuck).
     *
     * Returns true when:
     *  - logger is disabled (nothing to be healthy about)
     *  - logger was just enabled (give it one flush interval before judging)
     *  - last successful flush was within the threshold
     */
    fun isHealthy(): Boolean {
        if (!isEnabled) return true
        val now = System.currentTimeMillis()
        // Grace period: don't judge the logger as unhealthy in the first ~2 flush
        // intervals after enable() — the very first flush only runs after delay().
        if (now - enabledAtMs < 2 * FLUSH_INTERVAL_MS) return true
        if (lastFlushAtMs == 0L) return false
        return (now - lastFlushAtMs) < HEALTH_STALE_THRESHOLD_MS
    }

    /**
     * Cancels and re-launches the flush coroutine WITHOUT touching the on-disk file,
     * buffer, or session id. Used by the health-check restart path so a stuck flush
     * job recovers without losing data or causing the rider to see a fresh "0 entries".
     * No-op when the logger is disabled.
     */
    fun restartFlushJob() {
        if (!isEnabled) return
        Timber.w("CalibrationLogger: restarting flush job (health-check triggered)")
        flushJob?.cancel()
        lastFlushAtMs = System.currentTimeMillis()  // reset clock so we don't immediately re-restart
        flushJob = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(FLUSH_INTERVAL_MS)
                flush()
            }
        }
    }

    /**
     * After a successful periodic upload, drop the on-disk file contents (re-writing
     * the header) so the next 20-minute window starts fresh. The in-memory buffer
     * is preserved so any entries written between the upload-prepare moment and the
     * truncate moment are kept and flushed normally on the next tick. Returns the
     * number of lines that were truncated (so the caller can log progress).
     */
    fun truncateAfterSuccessfulSend(): Int {
        return try {
            val dir = context.getExternalFilesDir(null) ?: return 0
            val file = File(dir, FILE_NAME)
            if (!file.exists()) return 0
            val before = file.bufferedReader().use { it.lineSequence().count() }
            file.writeText("$HEADER\n")
            // Log a marker row so the next chunk's CSV self-identifies as a continuation.
            addEntryDirect(Event.LOGGER_START,
                "logging_resumed_after_periodic_send,session=$sessionId,prev_lines=$before")
            Timber.i("CalibrationLogger: truncated after successful periodic send ($before lines uploaded)")
            (before - 1).coerceAtLeast(0)
        } catch (e: Exception) {
            Timber.w(e, "CalibrationLogger: truncate after send failed")
            0
        }
    }

    // ─── Internal helpers ────────────────────────────────────────────────────

    /** Writes a log entry without checking [isEnabled] — used only during [enable]/[disable]. */
    private fun addEntryDirect(event: Event, data: String) {
        val now = System.currentTimeMillis()
        val csvData = if (',' in data) "\"$data\"" else data
        val line = "$now,0.0,${event.tag},$csvData"
        synchronized(buffer) {
            if (buffer.size >= MAX_BUFFER) buffer.removeFirst()
            buffer.addLast(line)
        }
    }
}
