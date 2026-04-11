package com.enderthor.kSafe.extension

import android.net.Uri
import com.enderthor.kSafe.data.ProviderType
import com.enderthor.kSafe.extension.managers.ConfigurationManager
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber

class Sender(
    private val karooSystem: KarooSystemService,
    private val configManager: ConfigurationManager
) {

    private val maxCycles = 3
    private val attemptsPerCycle = 3
    private val delaySeconds = listOf(60, 120, 180)
    private val cycleDelayMinutes = listOf(5, 10)

    // ─── Entry points ─────────────────────────────────────────────────────────

    /** Sends [message] via [provider]. All credentials and phone are read from stored config. */
    suspend fun sendAlert(message: String, provider: ProviderType): Boolean =
        sendWithRetry(message, provider)

    /**
     * Single-attempt send for configuration tests.
     * Returns a human-readable result string — never retries.
     */
    suspend fun testSend(provider: ProviderType): String {
        val configs = configManager.loadSenderConfigFlow().first()
        val config  = configs.find { it.provider == provider }
            ?: return "Provider not configured."

        return try {
            when (provider) {
                ProviderType.CALLMEBOT -> {
                    if (config.phoneNumber.isBlank()) return "Missing phone number."
                    if (config.apiKey.isBlank())      return "Missing API key."
                    val url = "https://api.callmebot.com/whatsapp.php" +
                        "?phone=${config.phoneNumber.trim()}" +
                        "&text=${Uri.encode("KSafe test — alerts are configured correctly.")}" +
                        "&apikey=${config.apiKey}"
                    val response = withTimeoutOrNull(15_000L) { karooSystem.httpRequest("GET", url) }
                        ?: return "No response — check your internet connection."
                    val body = response.body?.toString(Charsets.UTF_8) ?: ""
                    when {
                        response.statusCode in 200..299 && !body.contains("ERROR") ->
                            "Test sent! Check your WhatsApp."
                        body.contains("not authorized", ignoreCase = true) ||
                        body.contains("apikey", ignoreCase = true) ->
                            "Invalid API key — re-check it in CallMeBot."
                        else -> "Error ${response.statusCode}: ${body.take(120)}"
                    }
                }

                ProviderType.PUSHOVER -> {
                    if (config.apiKey.isBlank())  return "Missing App Token."
                    if (config.userKey.isBlank())  return "Missing User Key."
                    val userKeys = listOf(config.userKey, config.userKey2, config.userKey3)
                        .filter { it.isNotBlank() }
                    val results = mutableListOf<String>()
                    for ((i, key) in userKeys.withIndex()) {
                        val label = "Recipient ${i + 1}"
                        val jsonBody = buildJsonObject {
                            put("token",   config.apiKey)
                            put("user",    key)
                            put("title",   "KSafe Test")
                            put("message", "KSafe test — alerts are configured correctly.")
                            put("priority", 1)
                        }.toString()
                        val response = withTimeoutOrNull(15_000L) {
                            karooSystem.httpRequest(
                                "POST", "https://api.pushover.net/1/messages.json",
                                mapOf("Content-Type" to "application/json"),
                                jsonBody.toByteArray()
                            )
                        }
                        if (response == null) {
                            results.add("$label: no response — check connection.")
                        } else {
                            val body = response.body?.toString(Charsets.UTF_8) ?: ""
                            when {
                                response.statusCode in 200..299 && body.contains("\"status\":1") ->
                                    results.add("$label: sent ✓")
                                response.statusCode == 429 ->
                                    results.add("$label: rate limited — try again later.")
                                else -> {
                                    // Extract first error from Pushover's {"errors":["..."]} array
                                    val pushoverMsg = body
                                        .substringAfter("\"errors\":[\"", "")
                                        .substringBefore("\"", "")
                                        .trim()
                                    val detail = if (pushoverMsg.isNotBlank()) pushoverMsg
                                                 else "HTTP ${response.statusCode}"
                                    results.add("$label: $detail")
                                }
                            }
                        }
                    }
                    results.joinToString("\n")
                }

                ProviderType.SIMPLEPUSH -> {
                    if (config.apiKey.isBlank()) return "Missing Channel Key."
                    val url = "https://api.simplepush.io/send/${config.apiKey.trim()}" +
                        "/${Uri.encode("KSafe Test")}" +
                        "/${Uri.encode("KSafe test — alerts are configured correctly.")}"
                    val response = withTimeoutOrNull(15_000L) { karooSystem.httpRequest("GET", url) }
                        ?: return "No response — check your internet connection."
                    val body = response.body?.toString(Charsets.UTF_8) ?: ""
                    when {
                        response.statusCode in 200..299 && body.contains("\"status\":\"OK\"") ->
                            "Test sent! Check your SimplePush app."
                        response.statusCode == 404 ->
                            "Channel key not found — verify it in the SimplePush app."
                        else -> "Error ${response.statusCode}: ${body.take(120)}"
                    }
                }
            }
        } catch (e: Exception) {
            "Unexpected error: ${e.message}"
        }
    }

    // ─── Retry logic ──────────────────────────────────────────────────────────

    private suspend fun sendWithRetry(message: String, provider: ProviderType): Boolean {
        var totalAttempts = 0
        var currentCycle = 0

        return try {
            while (currentCycle < maxCycles) {
                repeat(attemptsPerCycle) { _ ->
                    totalAttempts++
                    if (totalAttempts > 1) {
                        val waitSeconds = delaySeconds[currentCycle]
                        Timber.d("Retry attempt $totalAttempts, waiting ${waitSeconds}s")
                        delay(waitSeconds * 1000L)
                    }
                    val result = withTimeoutOrNull(30_000L) {
                        attemptSend(message, provider)
                    } == true

                    if (result) {
                        Timber.d("Message sent on attempt $totalAttempts")
                        return true
                    }
                }

                if (currentCycle < maxCycles - 1) {
                    val waitMinutes = cycleDelayMinutes[currentCycle]
                    Timber.d("Cycle ${currentCycle + 1} failed, waiting ${waitMinutes}min")
                    delay(waitMinutes * 60 * 1000L)
                }
                currentCycle++
            }
            Timber.e("Message failed after $totalAttempts attempts")
            false
        } catch (e: Exception) {
            Timber.e(e, "Retry error: ${e.message}")
            false
        }
    }

    // ─── Provider implementations ─────────────────────────────────────────────

    private suspend fun attemptSend(message: String, provider: ProviderType): Boolean {
        val configs = configManager.loadSenderConfigFlow().first()
        val config = configs.find { it.provider == provider } ?: return false

        return when (provider) {
            ProviderType.CALLMEBOT -> {
                if (config.phoneNumber.isBlank() || config.apiKey.isBlank()) return false
                val encodedMsg = Uri.encode(message)
                val url = "https://api.callmebot.com/whatsapp.php?phone=${config.phoneNumber.trim()}&text=$encodedMsg&apikey=${config.apiKey}"
                val response = karooSystem.httpRequest("GET", url)
                val body = response.body?.toString(Charsets.UTF_8) ?: ""
                val ok = response.statusCode in 200..299 && !body.contains("ERROR")
                if (!ok) Timber.e("CallMeBot error ${response.statusCode}: $body")
                ok
            }

            ProviderType.PUSHOVER -> {
                if (config.apiKey.isBlank() || config.userKey.isBlank()) return false
                val userKeys = listOf(config.userKey, config.userKey2, config.userKey3)
                    .filter { it.isNotBlank() }
                var anyOk = false
                for (key in userKeys) {
                    val jsonBody = buildJsonObject {
                        put("token", config.apiKey)
                        put("user", key)
                        put("title", "KSafe Emergency")
                        put("message", message)
                        put("priority", 1)          // high priority — bypasses quiet hours
                    }.toString()
                    val response = karooSystem.httpRequest(
                        "POST", "https://api.pushover.net/1/messages.json",
                        mapOf("Content-Type" to "application/json"),
                        jsonBody.toByteArray()
                    )
                    val body = response.body?.toString(Charsets.UTF_8) ?: ""
                    val ok = response.statusCode in 200..299 && body.contains("\"status\":1")
                    if (ok) anyOk = true
                    else Timber.e("Pushover error (userKey=$key) ${response.statusCode}: $body")
                }
                anyOk
            }

            ProviderType.SIMPLEPUSH -> {
                if (config.apiKey.isBlank()) return false
                val encodedTitle = Uri.encode("KSafe Emergency")
                val encodedMsg   = Uri.encode(message)
                val url = "https://api.simplepush.io/send/${config.apiKey.trim()}/$encodedTitle/$encodedMsg"
                val response = karooSystem.httpRequest("GET", url)
                val body = response.body?.toString(Charsets.UTF_8) ?: ""
                val ok = response.statusCode in 200..299 && body.contains("\"status\":\"OK\"")
                if (!ok) Timber.e("SimplePush error ${response.statusCode}: $body")
                ok
            }
        }
    }
}
