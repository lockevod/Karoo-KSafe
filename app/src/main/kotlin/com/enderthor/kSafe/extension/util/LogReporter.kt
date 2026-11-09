package com.enderthor.kSafe.extension.util

import com.enderthor.kSafe.BuildConfig
import com.enderthor.kSafe.extension.httpRequest
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Sends calibration CSV log files to the developer's Telegram chat through a write-only relay.
 *
 * Design goals:
 *  - **Zero overhead when logging is disabled**: this object is stateless; if the caller never
 *    invokes [sendLogFile], there is no background work, no allocations, no I/O.
 *  - **Minimal overhead when enabled**: a single multipart HTTP POST is launched in a
 *    fire-and-forget coroutine; it does not block the sensor or UI threads.
 *  - **No credentials in the APK**: string constants survive R8 verbatim, so a bot token in
 *    BuildConfig is readable from the public release APK. The token + chat id live as secrets
 *    in a Cloudflare Worker that only accepts `sendDocument` to that fixed chat; the APK ships
 *    only the relay URL (`calib.relay_url` in local.properties), which is not secret.
 */
object LogReporter {

    // Relay URL from local.properties: calib.relay_url=https://…workers.dev
    private val RELAY_URL: String get() = BuildConfig.LOG_RELAY_URL
    private const val APP_ID     = "ksafe"   // must be in the Worker's ALLOWED_APPS
    private const val TIMEOUT_MS = 60_000L   // generous timeout for larger files over LTE

    /**
     * Sends [content] as a Telegram document named [fileName] to the hardcoded developer chat.
     *
     * [caption] is attached as the message text alongside the document — shown immediately
     * in the Telegram chat so the developer can identify the session without opening the file.
     * Pass an empty string to send without a caption.
     *
     * The body is encoded as `multipart/form-data` and built entirely from the in-memory
     * [content] string — no temporary files are created.
     *
     * Returns a [SendResult] — `SendResult.Success(message)` on a successful HTTP 2xx with
     * Telegram's confirmation payload, otherwise `SendResult.Failure(message)` with a
     * human-readable cause (no coverage, expired bot token, file > 50 MB, etc.). Callers
     * branch on `result is SendResult.Success` (the `result.ok` shorthand below); the
     * `message` field is what to surface in the rider-facing UI.
     *
     * This function returns `SendResult.Failure` when credentials are empty or still the
     * default placeholders, so a misconfigured build fails gracefully rather than crashing.
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
        if (!RELAY_URL.startsWith("https://")) {
            Timber.w("LogReporter: relay URL not set in local.properties — skipping automatic log send")
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
                    RELAY_URL,
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
                    401 -> "Relay rejected by Telegram (Worker BOT_TOKEN secret wrong)."
                    400 -> tgDesc ?: "Bad request (Worker CHAT_ID secret wrong?)."
                    403 -> tgDesc ?: "App not allowed by the log relay (ALLOWED_APPS)."
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
     * Builds the `multipart/form-data` body the relay forwards to Telegram `sendDocument`
     * (the relay picks the chat; we only name the app). No external dependencies required — the bytes are assembled in-memory.
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
            // app part — the relay checks it against ALLOWED_APPS and ignores any chat_id
            append("--$boundary$CRLF")
            append("Content-Disposition: form-data; name=\"app\"$CRLF")
            append(CRLF)
            append(APP_ID)
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
