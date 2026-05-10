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
        /** User logged a carb intake via tap or BonusAction. */
        FUELING_CARB_LOGGED("CARB_LOG"),
        /** Carb tracker fired an alert (deficit or time-interval). */
        FUELING_CARB_FIRED("CARB_FIRE"),
        /** Periodic 2-minute snapshot of carb tracker state, correlable with PERIODIC by timestamp. */
        FUELING_CARB_PERIODIC("CARB_PERIODIC"),
        /** User logged a hydration intake via tap or BonusAction. */
        FUELING_HYDRATION_LOGGED("HYD_LOG"),
        /** Hydration tracker fired an alert (deficit or time-interval). */
        FUELING_HYDRATION_FIRED("HYD_FIRE"),
        /** Periodic 2-minute snapshot of hydration tracker state. */
        FUELING_HYDRATION_PERIODIC("HYD_PERIODIC"),
        /** Marker written when logging is disabled — session end boundary. */
        LOG_END("LOG_END"),
    }

    companion object {
        /** Maximum entries kept in the in-memory buffer between flushes. */
        const val MAX_BUFFER = 500
        /** Flush in-memory buffer to disk every 60 seconds. */
        const val FLUSH_INTERVAL_MS = 60_000L
        /** Output CSV file name, written to the app's external files dir. */
        const val FILE_NAME = "ksafe_calibration.csv"
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

    private val buffer = ArrayDeque<String>()
    @Volatile private var startTime = 0L
    private var flushJob: Job? = null

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    fun enable() {
        startTime = System.currentTimeMillis()
        // Generate a new random session ID for this logging session.
        // Uses lower 24 bits of current time (ms) XORed with a pseudo-random salt so two
        // sessions started at nearly the same millisecond still get different IDs.
        sessionId = "%06x".format((startTime xor (startTime.ushr(16))) and 0xFFFFFFL)
        synchronized(buffer) { buffer.clear() }
        isEnabled = true
        // Reset output file: delete old data and write a fresh header so this session is clean.
        try {
            val dir = context.getExternalFilesDir(null)
            if (dir != null) {
                dir.mkdirs()
                File(dir, FILE_NAME).writeText("$HEADER\n")
            }
        } catch (e: Exception) {
            Timber.w(e, "CalibrationLogger: failed to reset log file on enable")
        }
        flushJob?.cancel()
        flushJob = scope.launch(Dispatchers.IO) {
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
        addEntryDirect(Event.LOG_END, "session_end,duration_s=${"%.0f".format((System.currentTimeMillis() - startTime) / 1_000f)}")
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
        val line = "$now,${"%.1f".format(elapsed)},${event.tag},$csvData"
        synchronized(buffer) {
            if (buffer.size >= MAX_BUFFER) buffer.removeFirst()
            buffer.addLast(line)
        }
    }

    // ─── Data access ─────────────────────────────────────────────────────────

    fun getEntryCount(): Int = synchronized(buffer) { buffer.size }

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
            context.getExternalFilesDir(null)?.let { File(it, FILE_NAME).delete() }
        } catch (e: Exception) {
            Timber.w(e, "CalibrationLogger: failed to delete log file")
        }
        Timber.i("CalibrationLogger: buffer and file cleared")
    }

    fun getLogFile(): File? =
        context.getExternalFilesDir(null)?.let { dir ->
            File(dir, FILE_NAME).takeIf { it.exists() }
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
            if (buffer.isEmpty()) return
            lines = buffer.toList()
            buffer.clear()
        }
        try {
            val dir = context.getExternalFilesDir(null) ?: return
            dir.mkdirs()
            val file = File(dir, FILE_NAME)
            // Append mode — the header was written on enable(), we just add rows.
            file.appendText(lines.joinToString("\n", postfix = "\n"))
            Timber.d("CalibrationLogger: appended ${lines.size} entries to $FILE_NAME")
        } catch (e: Exception) {
            Timber.w(e, "CalibrationLogger: flush failed (${lines.size} entries lost)")
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
