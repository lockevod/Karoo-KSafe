package com.enderthor.kSafe.extension.managers

import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.data.WEBHOOK_SLOT_COUNT
import com.enderthor.kSafe.data.webhookSlot
import com.enderthor.kSafe.extension.httpRequest
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Fires a single HTTP request for a configured webhook action.
 * Uses the Karoo network bridge — works via Bluetooth tether when no direct Wi-Fi is available.
 *
 * Supported services: Home Assistant, ntfy, IFTTT, n8n, Make, Zapier, or any plain HTTP webhook.
 */
class WebhookManager(private val karooSystem: KarooSystemService) {

    data class WebhookResult(val success: Boolean, val message: String)

    suspend fun trigger(slot: Int, config: KSafeConfig, timeoutMs: Long = 15_000L): WebhookResult {
        if (slot !in 1..WEBHOOK_SLOT_COUNT) {
            return WebhookResult(false, "Unknown webhook slot $slot")
        }
        val s = config.webhookSlot(slot)
        val enabled = s.enabled
        val label = s.label.ifBlank { "Action $slot" }
        val url = s.url
        val method = s.method.ifBlank { "POST" }
        val headersRaw = s.headers
        val body = s.body

        if (!enabled) return WebhookResult(false, "Webhook $slot not enabled")
        if (url.isBlank()) return WebhookResult(false, "No URL configured for $label")

        val headers = parseWebhookHeaders(headersRaw)

        val upperMethod = method.trim().uppercase()
        val bodyBytes = if (upperMethod == "POST" && body.isNotBlank()) body.toByteArray() else null

        return try {
            val response = withTimeoutOrNull(timeoutMs) {
                karooSystem.httpRequest(upperMethod, url.trim(), headers, bodyBytes)
            } ?: return WebhookResult(false, "Timeout — no response after ${timeoutMs / 1000}s")

            if (response.statusCode in 200..299) {
                Timber.d("Webhook $slot [$label] OK — HTTP ${response.statusCode}")
                WebhookResult(true, "$label ✓")
            } else {
                val errBody = response.body?.toString(Charsets.UTF_8)?.take(80) ?: ""
                Timber.w("Webhook $slot [$label] HTTP ${response.statusCode}: $errBody")
                WebhookResult(false, "HTTP ${response.statusCode}: ${errBody.ifBlank { "error" }}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Webhook $slot [$label] exception")
            WebhookResult(false, e.message ?: "Unknown error")
        }
    }
}

/**
 * Parse one or more `Key: Value` header lines, one per line. Newlines `\n` separate
 * entries. Blank lines and lines without a colon are skipped silently so a stray
 * empty line in the rider's input doesn't fail the request. Common case from a
 * copy-pasted curl example:
 * ```
 * Authorization: Bearer xyz
 * Content-Type: application/json
 * ```
 *
 * Extracted as a top-level helper so it can be exercised by a pure unit test
 * (see `WebhookHeaderParserTest`) — a regression that silently drops a critical
 * `Authorization` header line would otherwise only surface on a real device.
 *
 * Duplicate keys: the last occurrence wins (Map semantics). Riders authoring
 * headers don't reasonably need duplicate keys; this matches HTTP-client
 * convention where header-list-to-map flattening is common.
 */
internal fun parseWebhookHeaders(headersRaw: String): Map<String, String> {
    val headers = mutableMapOf<String, String>()
    if (headersRaw.isBlank()) return headers
    headersRaw.lineSequence().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@forEach
        val colonIdx = trimmed.indexOf(':')
        if (colonIdx > 0) {
            headers[trimmed.substring(0, colonIdx).trim()] =
                trimmed.substring(colonIdx + 1).trim()
        }
    }
    return headers
}

