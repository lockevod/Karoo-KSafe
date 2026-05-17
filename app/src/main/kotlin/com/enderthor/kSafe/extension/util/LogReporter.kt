package com.enderthor.kSafe.extension.util

import com.enderthor.kSafe.BuildConfig
import com.enderthor.kSafe.extension.httpRequest
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Sends calibration CSV log files to the developer via a Telegram bot.
 *
 * Design goals:
 *  - **Zero overhead when logging is disabled**: this object is stateless; if the caller never
 *    invokes [sendLogFile], there is no background work, no allocations, no I/O.
 *  - **Minimal overhead when enabled**: a single multipart HTTP POST is launched in a
 *    fire-and-forget coroutine; it does not block the sensor or UI threads.
 *  - **Credentials never in source code**: BOT_TOKEN and CHAT_ID are injected at compile
 *    time from local.properties (gitignored) via BuildConfig fields — they never appear
 *    in the repository or in any settings screen.
 */
object LogReporter {

    // ── Developer Telegram credentials ───────────────────────────────────────
    // Values come from BuildConfig, which reads local.properties at compile time.
    // To configure, add these lines to your local.properties (never commit them):
    //   calib.bot_token=<your bot token from @BotFather>
    //   calib.chat_id=<your developer chat ID>
    private val BOT_TOKEN: String get() = BuildConfig.CALIB_BOT_TOKEN
    private val CHAT_ID:   String get() = BuildConfig.CALIB_CHAT_ID
    // ─────────────────────────────────────────────────────────────────────────

    private const val API_BASE   = "https://api.telegram.org/bot"
    private const val TIMEOUT_MS = 60_000L   // generous timeout for larger files over LTE

    /**
     * Sends [content] as a Telegram document named [fileName] to the hardcoded developer chat.
     *
     * [caption] is attached as the message text alongside the document — shown immediately
     * in the Telegram chat so the developer can identify the session without opening the file.
     * Pass an empty string to send without a caption.
     *
     * The body is encoded as `multipart/form-data` and built entirely from the in-memory
     * [content] string — no temporary files are created. Returns `true` on success.
     *
     * This function is a no-op (returns `false`) when credentials are empty or still the
     * default placeholders, so a misconfigured build fails silently rather than crashing.
     */
    /**
     * Result of a [sendLogFile] attempt. Carrying a human-readable [message] on failure
     * lets the Settings UI tell the rider what actually went wrong instead of "check
     * Telegram credentials or connection" — which is misleading when the real cause is
     * a 50 MB file size limit or a 60 s timeout on a slow phone tether.
     */
    sealed class SendResult(val message: String) {
        class Success(message: String) : SendResult(message)
        class Failure(message: String) : SendResult(message)
        val ok: Boolean get() = this is Success
    }

    suspend fun sendLogFile(
        content: String,
        fileName: String = "ksafe_calibration.csv",
        caption: String = "",
        karooSystem: KarooSystemService,
    ): SendResult {
        if (BOT_TOKEN.isBlank() || BOT_TOKEN.startsWith("REPLACE") ||
            CHAT_ID.isBlank()   || CHAT_ID.startsWith("REPLACE")) {
            Timber.w("LogReporter: credentials not set in local.properties — skipping automatic log send")
            return SendResult.Failure("Logging credentials not configured in this build.")
        }
        if (content.isBlank()) {
            Timber.d("LogReporter: nothing to send (empty log)")
            return SendResult.Failure("Nothing to send (log is empty).")
        }

        return try {
            val boundary = "KSafeBoundary_${System.currentTimeMillis()}"
            val body     = buildMultipart(boundary, fileName, content, caption)
            val kb       = body.size / 1024

            Timber.d("LogReporter: sending ${body.size} bytes to Telegram (session caption: ${caption.take(60)}…)")

            val response: HttpResponseState.Complete? = withTimeoutOrNull(TIMEOUT_MS) {
                karooSystem.httpRequest(
                    "POST",
                    "$API_BASE$BOT_TOKEN/sendDocument",
                    mapOf("Content-Type" to "multipart/form-data; boundary=$boundary"),
                    body,
                )
            }

            if (response == null) {
                Timber.w("LogReporter: request timed out (${kb} KB payload, ${TIMEOUT_MS / 1000}s timeout)")
                return SendResult.Failure("Timeout after ${TIMEOUT_MS / 1000}s — file is ${kb} KB. " +
                    "Karoo Companion may have lost connection or the file is too large.")
            }

            val respBody = response.body?.toString(Charsets.UTF_8) ?: ""
            val ok = response.statusCode in 200..299 && respBody.contains("\"ok\":true")

            if (ok) {
                Timber.i("LogReporter: calibration log delivered ✓ (${content.length} chars, ${kb} KB)")
                SendResult.Success("Sent ✓ (${kb} KB)")
            } else {
                Timber.w("LogReporter: delivery failed — HTTP ${response.statusCode}: ${respBody.take(300)}")
                // Try to extract Telegram's error description from the JSON for a useful hint.
                val tgDesc = Regex("\"description\"\\s*:\\s*\"([^\"]+)\"").find(respBody)?.groupValues?.getOrNull(1)
                val hint = when (response.statusCode) {
                    401 -> "Bot token rejected (check CALIB_BOT_TOKEN in local.properties)."
                    400 -> tgDesc ?: "Bad request (check chat ID)."
                    413 -> "File too big (Telegram limit is 50 MB). Try clearing the log between sends."
                    429 -> "Rate-limited by Telegram. Wait a minute and try again."
                    in 500..599 -> "Telegram server error (try again in a moment)."
                    else -> tgDesc ?: "HTTP ${response.statusCode}"
                }
                SendResult.Failure("Send failed: $hint")
            }
        } catch (e: Exception) {
            Timber.e(e, "LogReporter: unexpected error during send")
            SendResult.Failure("Send error: ${e.javaClass.simpleName} — ${e.message ?: "no message"}")
        }
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    /**
     * Builds a `multipart/form-data` body compatible with the Telegram Bot API `sendDocument`
     * endpoint. No external dependencies required — the bytes are assembled in-memory.
     *
     * [caption] is included as an optional `caption` field — shown as the message text
     * alongside the document in the Telegram chat. Empty string = no caption.
     */
    private fun buildMultipart(
        boundary: String,
        fileName: String,
        fileContent: String,
        caption: String = "",
    ): ByteArray {
        val CRLF = "\r\n"
        return buildString {
            // chat_id part
            append("--$boundary$CRLF")
            append("Content-Disposition: form-data; name=\"chat_id\"$CRLF")
            append(CRLF)
            append(CHAT_ID)
            append(CRLF)
            // optional caption part — shown as the message text alongside the file in Telegram
            if (caption.isNotBlank()) {
                append("--$boundary$CRLF")
                append("Content-Disposition: form-data; name=\"caption\"$CRLF")
                append(CRLF)
                append(caption)
                append(CRLF)
            }
            // document part
            append("--$boundary$CRLF")
            append("Content-Disposition: form-data; name=\"document\"; filename=\"$fileName\"$CRLF")
            append("Content-Type: text/csv; charset=UTF-8$CRLF")
            append(CRLF)
            append(fileContent)
            append(CRLF)
            // closing boundary
            append("--$boundary--$CRLF")
        }.toByteArray(Charsets.UTF_8)
    }
}
