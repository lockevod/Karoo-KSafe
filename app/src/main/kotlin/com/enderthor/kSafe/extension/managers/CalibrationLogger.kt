package com.enderthor.kSafe.extension.managers

import android.content.Context
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
        /** Entered SILENCE_CHECK. */
        SILENCE_ENTER("SIL_IN"),
        /** Stillness condition broken inside SILENCE_CHECK — timer reset. */
        SILENCE_BROKEN("SIL_BRK"),
        /** Crash confirmed — full pipeline completed. */
        CRASH_CONFIRMED("CRASH_OK"),
        /** Speed-drop monitor evaluated (every 30 s, only when timer is active). */
        SPEEDDROP_EVAL("SPDRP_EVAL"),
        /** GPS stale condition detected (informational marker). */
        GPS_STALE("GPS_STALE"),
        /** Periodic ride-context snapshot — speed, accel deviation, gyro, state (every 5 min). */
        PERIODIC("PERIODIC"),
        /** Marker written when logging is enabled — anchor for elapsed_s calculations. */
        LOGGER_START("LOG_START"),
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
    }

    // ─── State ───────────────────────────────────────────────────────────────

    @Volatile var isEnabled = false
        private set

    private val buffer = ArrayDeque<String>()
    @Volatile private var startTime = 0L
    private var flushJob: Job? = null

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    fun enable() {
        startTime = System.currentTimeMillis()
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
        // Write a marker so the CSV has an anchor timestamp for all elapsed_s values
        addEntryDirect(Event.LOGGER_START, "logging_enabled,version=2")
        Timber.i("CalibrationLogger enabled — new session started")
    }

    fun disable() {
        isEnabled = false
        flushJob?.cancel()
        flushJob = null
        flush()   // write any remaining buffer entries to disk
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
        val line = "$now,${"%.1f".format(elapsed)},${event.tag},$data"
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
        val line = "$now,0.0,${event.tag},$data"
        synchronized(buffer) {
            if (buffer.size >= MAX_BUFFER) buffer.removeFirst()
            buffer.addLast(line)
        }
    }
}
