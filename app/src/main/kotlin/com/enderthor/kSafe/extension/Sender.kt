package com.enderthor.kSafe.extension

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

    suspend fun sendToPhone(phoneNumber: String, message: String, provider: ProviderType): Boolean {
        return sendWithRetry(phoneNumber, message, provider)
    }

    /** Returns true if this provider does not require a phone number (sends via account key). */
    fun isAccountBased(provider: ProviderType) = provider == ProviderType.PUSHOVER

    // ─── Retry logic ──────────────────────────────────────────────────────────

    private suspend fun sendWithRetry(phone: String, message: String, provider: ProviderType): Boolean {
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
                        attemptSendMessage(phone, message, provider)
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

    private suspend fun attemptSendMessage(phone: String, message: String, provider: ProviderType): Boolean {
        val configs = configManager.loadSenderConfigFlow().first()
        val config = configs.find { it.provider == provider } ?: return false

        return when (provider) {
            ProviderType.CALLMEBOT -> {
                val encodedMsg = java.net.URLEncoder.encode(message, "UTF-8")
                val url = "https://api.callmebot.com/whatsapp.php?phone=${phone.trim()}&text=$encodedMsg&apikey=${config.apiKey}"
                val response = karooSystem.httpRequest("GET", url)
                val body = response.body?.toString(Charsets.UTF_8) ?: ""
                val ok = response.statusCode in 200..299 && !body.contains("ERROR")
                if (!ok) Timber.e("CallMeBot error ${response.statusCode}: $body")
                ok
            }

            ProviderType.WHAPI -> {
                val formattedPhone = phone.trim().removePrefix("+").filter { it.isDigit() || it == '-' }
                val jsonBody = buildJsonObject {
                    put("to", formattedPhone)
                    put("body", message)
                }.toString()
                val headers = mapOf(
                    "Content-Type" to "application/json",
                    "Authorization" to "Bearer ${config.apiKey}"
                )
                val response = karooSystem.httpRequest("POST", "https://gate.whapi.cloud/messages/text", headers, jsonBody.toByteArray())
                val body = response.body?.toString(Charsets.UTF_8) ?: ""
                val ok = response.statusCode in 200..299 || body.contains("message_id")
                if (!ok) Timber.e("Whapi error ${response.statusCode}: $body")
                ok
            }

            ProviderType.PUSHOVER -> {
                // Pushover ignores `phone` — uses the stored user key
                val configs = configManager.loadSenderConfigFlow().first()
                val cfg = configs.find { it.provider == ProviderType.PUSHOVER } ?: return false
                val jsonBody = buildJsonObject {
                    put("token", cfg.apiKey)    // app token
                    put("user", cfg.userKey)    // user/group key
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
                if (!ok) Timber.e("Pushover error ${response.statusCode}: $body")
                ok
            }
        }
    }
}
