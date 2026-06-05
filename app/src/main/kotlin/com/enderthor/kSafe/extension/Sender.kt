package com.enderthor.kSafe.extension

import android.net.Uri
import com.enderthor.kSafe.data.ProviderType
import com.enderthor.kSafe.data.SenderConfig
import com.enderthor.kSafe.extension.managers.ConfigurationManager
import com.enderthor.kSafe.extension.util.recipientsToSend
import com.enderthor.kSafe.extension.util.scopeForSlot
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber

/**
 * Why an emergency send reached nobody. Recorded verbatim on the `ALERT_FAIL`
 * calibration row (see [com.enderthor.kSafe.extension.managers.EmergencyManager])
 * so a post-incident audit can tell apart the failure modes WITHOUT reverse-
 * engineering them from the `ALERT_FAIL` timestamp:
 *
 *  - [NO_CREDENTIALS] / [NO_CONFIG] fail FAST (pre-flight, ~no time) — the row lands
 *    ~1 s after the countdown ends. This is a rider misconfiguration (provider
 *    selected but token/contact blank), NOT a connectivity problem.
 *  - [TIMEOUT] / [EXHAUSTED] land ~30 min later — the full 3×3 retry budget ran.
 *    [TIMEOUT] = no usable connection (every attempt hit the block timeout);
 *    [EXHAUSTED] = the provider was reachable but rejected every attempt
 *    (e.g. 401 invalid token, chat-not-found).
 *
 * The 2026-06-03 calibration session `27baa0` showed the original `ALERT_FAIL`
 * (`provider,reason,superseded` only) forced exactly that timestamp guesswork.
 */
enum class FailureCause {
    /** Not a failure — success / partial / legitimate scope no-op. */
    NONE,
    /** No [SenderConfig] found for the active provider. */
    NO_CONFIG,
    /** Blank/missing credentials — failed the pre-flight before any network attempt. */
    NO_CREDENTIALS,
    /** Retries exhausted; the final attempt hit the block-level timeout (no usable connection). */
    TIMEOUT,
    /** Retries exhausted; provider reachable but rejected every attempt. */
    EXHAUSTED,
    /** Failure of an unexpected/defensive path that could not be classified. */
    UNKNOWN,
}

/**
 * Classifies a finished provider attempt for the post-incident cause tag (see [FailureCause]).
 * Returns [FailureCause.TIMEOUT] only when nothing was delivered AND no recipient ever got an
 * HTTP response (every attempt timed out → genuinely no usable connection, the "rider in a
 * tunnel / no coverage" case). If any server responded — even with an error code — there *was*
 * a connection, so the failure is a rejection: return [FailureCause.NONE] and let
 * [Sender.sendWithRetry]'s terminal mapping record it as EXHAUSTED. Top-level + internal so the
 * truth table is unit-testable without constructing a [Sender] / KarooSystemService.
 */
internal fun timeoutOrNone(delivered: Int, anyResponse: Boolean, anyTimeout: Boolean): FailureCause =
    if (delivered == 0 && anyTimeout && !anyResponse) FailureCause.TIMEOUT else FailureCause.NONE

/**
 * Result of a send across a provider's eligible recipients.
 *
 * - [delivered] — recipients that returned success.
 * - [eligible]  — recipients actually attempted (the post-scope-filter set).
 * - [hardFail]  — this send reached nobody and it is a GENUINE failure, NOT a legitimate
 *   scope-filtered info no-op. Set for blank/missing credentials, no configured contact, AND
 *   for a transient total failure (block-level timeout / unexpected exception in the retry
 *   loop). Its only job is to stop [infoSuccess] from treating a zero-recipient FAILURE like a
 *   zero-recipient no-op. `eligible` is not read on any failure path (only [partial] reads it,
 *   and that requires `delivered >= 1`), so the timeout case carrying `eligible == 0` is benign.
 * - [cause]     — for a failure (`!anyOk`), WHY it reached nobody. [FailureCause.NONE] on any
 *   delivered/partial/no-op outcome. [attemptSend] tags [FailureCause.TIMEOUT] when an attempt
 *   reached nobody with no server response at all (genuine no-connection, via [timeoutOrNone]);
 *   otherwise [Sender.sendWithRetry]'s terminal mapping classifies the exhausted failure
 *   (TIMEOUT carried forward from the last attempt, else EXHAUSTED).
 */
data class SendOutcome(
    val delivered: Int,
    val eligible: Int,
    val hardFail: Boolean = false,
    val cause: FailureCause = FailureCause.NONE,
) {
    /** At least one recipient was reached. */
    val anyOk: Boolean get() = delivered > 0
    /** Reached ≥1 but not every eligible recipient — the emergency partial-delivery signal. */
    val partial: Boolean get() = delivered in 1 until eligible
    /** Info-send success: a deliverable config that either reached someone or had a
     *  legitimate zero-recipient scope no-op (`eligible == 0` and not a hard failure). */
    val infoSuccess: Boolean get() = !hardFail && (delivered > 0 || eligible == 0)

    companion object {
        /** Reached nobody, GENUINE failure (not a no-op): blank/missing credentials, no
         *  configured contact, or a transient total failure (timeout / unexpected exception). */
        val HARD_FAIL = SendOutcome(0, 0, hardFail = true)
        /** A [HARD_FAIL] tagged with a specific [FailureCause] for the calibration audit trail. */
        fun hardFail(cause: FailureCause) = SendOutcome(0, 0, hardFail = true, cause = cause)
        /** Deliverable but scope-filtered to zero recipients (legitimate info no-op). */
        val NO_OP = SendOutcome(0, 0)
    }
}

class Sender(
    private val karooSystem: KarooSystemService,
    private val configManager: ConfigurationManager
) {

    private val maxCycles = 3
    private val attemptsPerCycle = 3
    private val delaySeconds = listOf(60, 120, 180)
    /**
     * Wait between cycles. Length contract: **must be `maxCycles - 1`** because the loop
     * skips this list on the last cycle (no point waiting for a cycle that won't run).
     */
    private val cycleDelayMinutes = listOf(5, 10)

    companion object {
        /** Per-HTTP-request timeout for a single recipient call (test path + every
         *  recipient in the multi-recipient retry block). */
        private const val ATTEMPT_TIMEOUT_MS = 15_000L
        /** Hard cap on the WHOLE multi-recipient `attemptSend` block. Each recipient
         *  has its own [ATTEMPT_TIMEOUT_MS] now, so this must be at least
         *  `MAX_RECIPIENTS * ATTEMPT_TIMEOUT_MS + slack`. 3 recipients × 15 s = 45 s →
         *  60 s leaves headroom for JSON building, log writes, and the brief gap
         *  between recipient calls without prematurely killing the third recipient. */
        private const val ATTEMPT_BLOCK_TIMEOUT_MS = 60_000L
    }

    // ─── Entry points ─────────────────────────────────────────────────────────

    /**
     * Sends an emergency [message] via [provider] (high priority, retries on failure).
     * Returns the [SendOutcome] of the terminal attempt so the caller can distinguish
     * full delivery from partial delivery (reached some but not all emergency contacts)
     * and total failure.
     */
    suspend fun sendAlert(message: String, provider: ProviderType): SendOutcome =
        sendWithRetry(message, provider, isEmergency = true)

    /** Sends an informational [message] via [provider] (normal priority, single attempt).
     *  Wrapped: [attemptSend] can throw (RemoteException / IllegalStateException from a
     *  momentarily-unbound KarooSystemService) — map that to a clean `false` instead of
     *  letting it crash the caller (ride-start/end + custom-message paths). */
    suspend fun sendInfo(message: String, provider: ProviderType): Boolean =
        sendInfoOutcome(message, provider).infoSuccess

    /** Like [sendInfo] but returns the full [SendOutcome] so a rider-initiated path (e.g. a
     *  custom-message tap) can distinguish a real delivery (`delivered > 0`) from a legitimate
     *  zero-recipient scope no-op (`eligible == 0`, not `hardFail`) — the latter must NOT be
     *  reported to the rider as "sent ✓". A thrown exception maps to [SendOutcome.HARD_FAIL]. */
    suspend fun sendInfoOutcome(message: String, provider: ProviderType): SendOutcome =
        try {
            attemptSend(message, provider, isEmergency = false)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "sendInfo failed for $provider — treating as not delivered")
            SendOutcome.HARD_FAIL
        }

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
                    val recipients = callMeBotRecipients(config)
                    if (recipients.isEmpty()) return "Missing phone number or API key."
                    val results = mutableListOf<String>()
                    for ((slot, phone, key) in recipients) {
                        val label = "Recipient ${slot + 1}"
                        val url = "https://api.callmebot.com/whatsapp.php" +
                            "?phone=${Uri.encode(phone)}" +
                            "&text=${Uri.encode("KSafe test — alerts are configured correctly.")}" +
                            "&apikey=${Uri.encode(key)}"
                        val response = withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) { karooSystem.httpRequest("GET", url) }
                        if (response == null) {
                            results.add("$label: no response — check connection.")
                            continue
                        }
                        val body = response.body?.toString(Charsets.UTF_8) ?: ""
                        // J1 — use the same robust success predicate as attemptSend
                        // so a misconfigured CallMeBot fails BOTH paths consistently
                        // (testSend reporting "sent ✓" while sendAlert silently fails
                        // is the worst-of-both-worlds UX).
                        when {
                            isCallMeBotSuccess(response.statusCode, body) ->
                                results.add("$label: sent ✓")
                            body.contains("not authorized", ignoreCase = true) ||
                            body.contains("apikey", ignoreCase = true) ->
                                results.add("$label: invalid API key.")
                            else -> results.add("$label: HTTP ${response.statusCode} ${body.take(80)}")
                        }
                    }
                    results.joinToString("\n")
                }

                ProviderType.PUSHOVER -> {
                    if (config.apiKey.isBlank())  return "Missing App Token."
                    if (listOf(config.userKey, config.userKey2, config.userKey3).all { it.isBlank() }) return "Missing User Key."
                    val userKeys = listOf(config.userKey, config.userKey2, config.userKey3)
                    val results = mutableListOf<String>()
                    for ((i, key) in userKeys.withIndex()) {
                        if (key.isBlank()) continue
                        val label = "Recipient ${i + 1}"
                        val jsonBody = buildJsonObject {
                            put("token",   config.apiKey)
                            put("user",    key)
                            put("title",   "KSafe Test")
                            put("message", "KSafe test — alerts are configured correctly.")
                            put("priority", 1)
                        }.toString()
                        val response = withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) {
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
                                // K2 — anchored check, see attemptSend Pushover branch.
                                response.statusCode in 200..299 &&
                                    (body.contains("\"status\":1,") || body.contains("\"status\":1}")) ->
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

                ProviderType.NTFY -> {
                    if (config.apiKey.isBlank()) return "Missing Topic."
                    val response = withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) {
                        karooSystem.httpRequest(
                            "POST",
                            "https://ntfy.sh/${config.apiKey.trim()}",
                            mapOf(
                                "Content-Type" to "text/plain",
                                "Title" to "KSafe Test",
                            ),
                            "KSafe test — alerts are configured correctly.".toByteArray()
                        )
                    } ?: return "No response — check your internet connection."
                    when {
                        response.statusCode in 200..299 ->
                            "Test sent! Open the ntfy app and check your topic."
                        response.statusCode == 403 ->
                            "Access denied — the topic may be protected or reserved."
                        else -> "Error ${response.statusCode}: ${response.body?.toString(Charsets.UTF_8)?.take(120) ?: ""}"
                    }
                }

                ProviderType.TELEGRAM -> {
                    if (config.apiKey.isBlank()) return "Missing Bot Token."
                    if (listOf(config.userKey, config.userKey2, config.userKey3).all { it.isBlank() }) return "Missing Chat ID."
                    val chatIds = listOf(config.userKey, config.userKey2, config.userKey3)
                    val results = mutableListOf<String>()
                    for ((i, chatId) in chatIds.withIndex()) {
                        if (chatId.isBlank()) continue
                        val label = "Chat ${i + 1}"
                        val jsonBody = buildJsonObject {
                            put("chat_id", chatId.trim())
                            put("text", "KSafe test — alerts are configured correctly.")
                        }.toString()
                        val response = withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) {
                            karooSystem.httpRequest(
                                "POST",
                                "https://api.telegram.org/bot${config.apiKey.trim()}/sendMessage",
                                mapOf("Content-Type" to "application/json"),
                                jsonBody.toByteArray()
                            )
                        }
                        if (response == null) {
                            results.add("$label: no response — check connection.")
                        } else {
                            val body = response.body?.toString(Charsets.UTF_8) ?: ""
                            when {
                                response.statusCode in 200..299 && body.contains("\"ok\":true") ->
                                    results.add("$label: sent ✓")
                                response.statusCode == 401 ->
                                    results.add("$label: invalid Bot Token.")
                                response.statusCode == 400 && body.contains("chat not found", ignoreCase = true) ->
                                    results.add("$label: Chat ID not found — make sure the bot is added to the chat.")
                                else -> {
                                    val desc = body
                                        .substringAfter("\"description\":\"", "")
                                        .substringBefore("\"", "")
                                        .trim()
                                    results.add("$label: ${desc.ifBlank { "HTTP ${response.statusCode}" }}")
                                }
                            }
                        }
                    }
                    results.joinToString("\n")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Never swallow cancellation — it must propagate so the calling coroutine
            // (e.g. the user pressing Back while the test send is in flight) actually
            // exits instead of being told "Unexpected error: …".
            throw e
        } catch (e: Exception) {
            "Unexpected error: ${e.message}"
        }
    }

    // ─── Retry logic ──────────────────────────────────────────────────────────

    private suspend fun sendWithRetry(message: String, provider: ProviderType, isEmergency: Boolean): SendOutcome {
        // Load config ONCE before the retry loop — avoids up to 9 DataStore reads + JSON
        // deserialisations (one per attempt) for a value that cannot change mid-emergency.
        val configs = configManager.loadSenderConfigFlow().first()
        val config  = configs.find { it.provider == provider } ?: run {
            Timber.e("sendWithRetry: no config found for $provider")
            return SendOutcome.hardFail(FailureCause.NO_CONFIG)
        }

        // Pre-flight credential validation — every provider's attemptSend short-circuits
        // with `return false` on blank credentials, but without this check the retry
        // loop would burn the full 9-attempt × ~30 min budget calling that same
        // short-circuit. Fail fast so the rider's delivery-failure notification fires
        // immediately instead of after half an hour.
        if (!hasUsableCredentials(provider, config)) {
            Timber.e("sendWithRetry: blank/missing credentials for $provider — failing fast without retries")
            return SendOutcome.hardFail(FailureCause.NO_CREDENTIALS)
        }

        var totalAttempts = 0
        var currentCycle = 0
        // Outcome of the most recent attempt — returned on exhaustion so a caller still
        // sees the eligible count even when nothing was delivered.
        var lastOutcome: SendOutcome = SendOutcome.HARD_FAIL

        return try {
            while (currentCycle < maxCycles) {
                repeat(attemptsPerCycle) { attemptInCycle ->
                    totalAttempts++
                    // Inter-attempt wait applies WITHIN a cycle only. The first attempt of
                    // each cycle must not pre-delay: cycle 0's first attempt fires
                    // immediately, and cycles 1/2's first attempt already waited the
                    // cycleDelayMinutes gap below. Guarding on the global totalAttempts
                    // counter (instead of this per-cycle index) double-charged that first
                    // attempt an extra delaySeconds[cycle] — worst case ~41 min vs the
                    // intended ~30.
                    if (attemptInCycle > 0) {
                        val waitSeconds = delaySeconds[currentCycle]
                        Timber.d("Retry attempt $totalAttempts, waiting ${waitSeconds}s")
                        delay(waitSeconds * 1000L)
                    }
                    // Per-attempt try/catch — without this, a synchronous throw from
                    // attemptSend (RemoteException / IllegalStateException from a
                    // momentarily-unbound karooSystem, or any provider-specific glitch
                    // not handled inside attemptSend) would escape withTimeoutOrNull,
                    // escape the repeat/while, and hit the OUTER catch(Exception) below
                    // — collapsing the entire 9-attempt × 30-min retry budget into one
                    // failed try and silently dropping the rider's emergency alert.
                    val outcome = try {
                        withTimeoutOrNull(ATTEMPT_BLOCK_TIMEOUT_MS) {
                            attemptSend(message, provider, isEmergency, config)
                        } ?: SendOutcome.hardFail(FailureCause.TIMEOUT)   // block-level timeout
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Cancellation must still propagate (caller scope tear-down).
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "Attempt $totalAttempts threw — treating as failed, continuing retry chain")
                        SendOutcome.HARD_FAIL
                    }
                    lastOutcome = outcome

                    if (outcome.anyOk) {
                        Timber.d("Message sent on attempt $totalAttempts (delivered=${outcome.delivered}/${outcome.eligible})")
                        return outcome
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
            // Tag the terminal failure cause for the post-incident calibration trail
            // (ALERT_FAIL payload). A block-level timeout on the FINAL attempt → TIMEOUT
            // (no usable connection); any other non-OK terminal outcome → EXHAUSTED (the
            // provider was reachable but rejected every attempt — e.g. 401 / chat-not-found,
            // or a per-attempt exception). Reaching here always means delivered == 0 (the
            // loop returns early on anyOk), so copying cause onto lastOutcome is safe.
            val terminalCause =
                if (lastOutcome.cause == FailureCause.TIMEOUT) FailureCause.TIMEOUT
                else FailureCause.EXHAUSTED
            lastOutcome.copy(cause = terminalCause)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancellation must propagate (caller scope tear-down). Swallowing here
            // would leave the parent coroutine running past the cancellation point.
            throw e
        } catch (e: Exception) {
            // Defensive backstop — per-attempt catch above now handles the per-attempt
            // throw path; this remains for any unexpected exception escaping the
            // surrounding control flow (delay between cycles, config load, etc.).
            Timber.e(e, "Retry error: ${e.message}")
            SendOutcome.hardFail(FailureCause.UNKNOWN)
        }
    }

    /**
     * Pre-flight check called once from [sendWithRetry] so an unconfigured provider
     * fails the retry loop in microseconds instead of burning ~30 minutes calling
     * attemptSend's `return false` short-circuit. Mirrors the per-provider blank
     * checks at the start of each attemptSend branch.
     *
     * J3 — checks ALL configured slots (1..3) for providers that support multi-recipient
     * (CallMeBot, Pushover, Telegram). A rider who deliberately blanks slot 1 (e.g. to
     * avoid self-notification) and configures contacts in slot 2/3 must still be able
     * to send — the original slot-1-only check would have made the pre-flight return
     * false and silently swallow the entire retry budget.
     */
    private fun hasUsableCredentials(provider: ProviderType, config: SenderConfig): Boolean = when (provider) {
        ProviderType.CALLMEBOT -> callMeBotRecipients(config).isNotEmpty()
        ProviderType.PUSHOVER  -> config.apiKey.isNotBlank() &&
            listOf(config.userKey, config.userKey2, config.userKey3).any { it.isNotBlank() }
        ProviderType.NTFY      -> config.apiKey.isNotBlank()
        ProviderType.TELEGRAM  -> config.apiKey.isNotBlank() &&
            listOf(config.userKey, config.userKey2, config.userKey3).any { it.isNotBlank() }
    }

    /**
     * CallMeBot success predicate. The provider returns HTTP 200 even on most failure
     * modes — the body string is the discriminator. Documented body patterns:
     *  - Success: "Message Sent", "Message queued", standalone "OK"
     *  - Failure: "APIKEY_INVALID", "WhatsApp Number not found", "You need to authorize
     *    this number", "ERROR: ...", "Forbidden", "Token expired", "Revoked", etc.
     *
     * K1 — the previous version had two false-positive vectors:
     *  1. The whitelist contained the bare 2-char substring "ok", which also matches
     *     inside common English words found in failure bodies (`tOKen`, `revOKed`,
     *     `looKup`). A failure body like "Token expired" silently returned true.
     *  2. `body.isBlank()` treated an empty 2xx response as success, but an empty body
     *     is a tell-tale signature of a captive-portal / proxy interception, NOT
     *     a real CallMeBot success — those always include a non-empty status string.
     *
     * Robust check: require an explicit success marker as a multi-word phrase OR
     * standalone-"OK" via trim+equals (so "OK" by itself works, but "tOKen" doesn't).
     * Blacklist still runs first as a defense-in-depth catch for known failure modes.
     */
    /** Internal visibility for unit tests — see `CallMeBotSuccessTest`. The K1
     *  rationale below documents historical false-positive bugs that the tests
     *  pin against regressions. */
    internal fun isCallMeBotSuccess(statusCode: Int, body: String): Boolean {
        if (statusCode !in 200..299) return false
        if (body.isBlank()) return false   // captive portal / proxy intercept — never trust.
        val lower = body.lowercase()
        val knownFailures = listOf(
            "error",
            "apikey_invalid",
            "not authorized",
            "not found",
            "you need to",
            "forbidden",
            "invalid",
            "expired",
            "revoked",
            "limit",         // rate-limit hits ("daily limit reached", "limit exceeded")
            "denied",
        )
        if (knownFailures.any { lower.contains(it) }) return false
        // Whitelist — multi-word phrases that can't accidentally appear inside other
        // English words. Standalone "OK" (trim+equals, case-insensitive) covers the
        // legacy minimal-success endpoint without the substring fragility.
        if (body.trim().equals("OK", ignoreCase = true)) return true
        val knownSuccess = listOf("message sent", "message queued")
        return knownSuccess.any { lower.contains(it) }
    }

    // ─── Provider implementations ─────────────────────────────────────────────

    private suspend fun attemptSend(message: String, provider: ProviderType, isEmergency: Boolean): SendOutcome {
        val configs = configManager.loadSenderConfigFlow().first()
        val config = configs.find { it.provider == provider } ?: return SendOutcome.HARD_FAIL
        return attemptSend(message, provider, isEmergency, config)
    }

    private suspend fun attemptSend(
        message: String,
        provider: ProviderType,
        isEmergency: Boolean,
        config: SenderConfig,
    ): SendOutcome {

        return when (provider) {
            ProviderType.CALLMEBOT -> {
                val encodedMsg = Uri.encode(message)
                val recipients = callMeBotRecipients(config)
                val send = recipientsToSend(recipients.map { it.first }, config::scopeForSlot, isEmergency)
                // info filtered to zero = intentional no-op (success); emergency with no
                // configured contact = hard failure (the scope fallback already widened to
                // all configured slots, so an empty set means there is genuinely no one).
                if (send.isEmpty()) return if (isEmergency) SendOutcome.HARD_FAIL else SendOutcome.NO_OP
                var delivered = 0
                var anyResponse = false
                var anyTimeout = false
                for ((slot, phone, key) in recipients) {
                    if (slot !in send) continue
                    // URL-encode phone + apikey: an international phone entered with a leading
                    // '+' would otherwise be decoded server-side as a space (the emergency alert
                    // silently fails to deliver). text is already encoded above.
                    val url = "https://api.callmebot.com/whatsapp.php?phone=${Uri.encode(phone)}&text=$encodedMsg&apikey=${Uri.encode(key)}"
                    // Per-recipient timeout so a hung first recipient doesn't starve
                    // recipients 2/3 of the outer attempt's 30 s block.
                    val response = withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) {
                        karooSystem.httpRequest("GET", url)
                    }
                    if (response == null) {
                        Timber.e("CallMeBot timeout (phone=$phone)")
                        anyTimeout = true
                        continue
                    }
                    anyResponse = true
                    val body = response.body?.toString(Charsets.UTF_8) ?: ""
                    // J1 — CallMeBot returns HTTP 200 with various failure bodies that
                    // do NOT contain the literal word "ERROR" (e.g. "APIKEY_INVALID",
                    // "WhatsApp Number not found", "You need to authorize this number").
                    // The previous `!body.contains("ERROR")` would treat all of these
                    // as success, so a misconfigured / expired CallMeBot setup silently
                    // returned true and the rider's contacts never received the alert.
                    // Require a positive success marker ("Message Sent" or "Message
                    // queued"), then double-check no known failure substring is present.
                    val ok = isCallMeBotSuccess(response.statusCode, body)
                    if (ok) delivered++
                    else Timber.e("CallMeBot error (phone=$phone) ${response.statusCode}: $body")
                }
                SendOutcome(delivered, send.size, cause = timeoutOrNone(delivered, anyResponse, anyTimeout))
            }

            ProviderType.PUSHOVER -> {
                if (config.apiKey.isBlank()) return SendOutcome.HARD_FAIL
                val allKeys = listOf(config.userKey, config.userKey2, config.userKey3)
                val configuredSlots = allKeys.indices.filter { allKeys[it].isNotBlank() }
                val send = recipientsToSend(configuredSlots, config::scopeForSlot, isEmergency)
                if (send.isEmpty()) return if (isEmergency) SendOutcome.HARD_FAIL else SendOutcome.NO_OP
                var delivered = 0
                var anyResponse = false
                var anyTimeout = false
                for (slot in send) {
                    val key = allKeys[slot]
                    val jsonBody = buildJsonObject {
                        put("token", config.apiKey)
                        put("user", key)
                        put("title", if (isEmergency) "KSafe Emergency" else "KSafe")
                        put("message", message)
                        // Emergency: priority 1 (high, bypasses quiet hours)
                        // Info: priority 0 (normal)
                        put("priority", if (isEmergency) 1 else 0)
                    }.toString()
                    // Per-recipient timeout so a hung first recipient doesn't starve 2/3.
                    val response = withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) {
                        karooSystem.httpRequest(
                            "POST", "https://api.pushover.net/1/messages.json",
                            mapOf("Content-Type" to "application/json"),
                            jsonBody.toByteArray()
                        )
                    }
                    if (response == null) {
                        Timber.e("Pushover timeout (userKey=$key)")
                        anyTimeout = true
                        continue
                    }
                    anyResponse = true
                    val body = response.body?.toString(Charsets.UTF_8) ?: ""
                    // K2 — anchored substring check. The previous `"status":1` matched
                    // both `"status":1,` (real success) AND `"status":10,` / `"status":11,`
                    // (hypothetical future Pushover codes). Pushover documents only 0/1
                    // today so it's not triggerable yet, but the J1 round-7 finding proved
                    // this fragility class ships silently for years. Require a JSON value
                    // terminator (`,` for non-last field, `}` for the last field).
                    val ok = response.statusCode in 200..299 &&
                        (body.contains("\"status\":1,") || body.contains("\"status\":1}"))
                    if (ok) delivered++
                    else Timber.e("Pushover error (userKey=$key) ${response.statusCode}: $body")
                }
                SendOutcome(delivered, send.size, cause = timeoutOrNone(delivered, anyResponse, anyTimeout))
            }

            ProviderType.NTFY -> {
                if (config.apiKey.isBlank()) return SendOutcome.HARD_FAIL
                if (recipientsToSend(listOf(0), config::scopeForSlot, isEmergency).isEmpty()) {
                    Timber.d("ntfy: skipped by per-recipient filter (scope=${config.recipient1Alerts})")
                    return if (isEmergency) SendOutcome.HARD_FAIL else SendOutcome.NO_OP
                }
                val title    = if (isEmergency) "KSafe Emergency" else "KSafe"
                val priority = if (isEmergency) "urgent" else "default"
                val response = withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) {
                    karooSystem.httpRequest(
                        "POST",
                        "https://ntfy.sh/${config.apiKey.trim()}",
                        mapOf(
                            "Content-Type" to "text/plain",
                            "Title"        to title,
                            "Priority"     to priority,
                        ),
                        message.toByteArray()
                    )
                }
                if (response == null) {
                    Timber.e("ntfy timeout")
                    // Single recipient: a null response means the one attempt never reached the
                    // server → no usable connection. Tag TIMEOUT so the terminal cause is accurate
                    // (else it would be misreported as EXHAUSTED = "reachable but rejected").
                    return SendOutcome(0, 1, cause = FailureCause.TIMEOUT)
                }
                val ok = response.statusCode in 200..299
                if (!ok) Timber.e("ntfy error ${response.statusCode}: ${response.body?.toString(Charsets.UTF_8)}")
                SendOutcome(if (ok) 1 else 0, 1)
            }

            ProviderType.TELEGRAM -> {
                if (config.apiKey.isBlank()) return SendOutcome.HARD_FAIL
                val allChatIds = listOf(config.userKey, config.userKey2, config.userKey3)
                val configuredSlots = allChatIds.indices.filter { allChatIds[it].isNotBlank() }
                val send = recipientsToSend(configuredSlots, config::scopeForSlot, isEmergency)
                if (send.isEmpty()) return if (isEmergency) SendOutcome.HARD_FAIL else SendOutcome.NO_OP
                var delivered = 0
                var anyResponse = false
                var anyTimeout = false
                for (slot in send) {
                    val chatId = allChatIds[slot]
                    val jsonBody = buildJsonObject {
                        put("chat_id", chatId.trim())
                        put("text", message)
                    }.toString()
                    // Per-recipient timeout — see CallMeBot branch for the rationale.
                    val response = withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) {
                        karooSystem.httpRequest(
                            "POST",
                            "https://api.telegram.org/bot${config.apiKey.trim()}/sendMessage",
                            mapOf("Content-Type" to "application/json"),
                            jsonBody.toByteArray()
                        )
                    }
                    if (response == null) {
                        Timber.e("Telegram timeout (chatId=$chatId)")
                        anyTimeout = true
                        continue
                    }
                    anyResponse = true
                    val body = response.body?.toString(Charsets.UTF_8) ?: ""
                    val ok = response.statusCode in 200..299 && body.contains("\"ok\":true")
                    if (ok) delivered++
                    else Timber.e("Telegram error (chatId=$chatId) ${response.statusCode}: $body")
                }
                SendOutcome(delivered, send.size, cause = timeoutOrNone(delivered, anyResponse, anyTimeout))
            }
        }
    }

    /**
     * Builds the list of `(slot, phone, apiKey)` triples to deliver a CallMeBot message to.
     * CallMeBot cannot fan-out a single request, so every recipient needs its own
     * credential pair. Slots with either half blank are dropped — three slots total,
     * mirroring Pushover / Telegram. The slot index (0/1/2) is preserved so the
     * per-recipient alert-scope filter can gate individual recipients.
     */
    private fun callMeBotRecipients(config: SenderConfig): List<Triple<Int, String, String>> {
        fun entry(slot: Int, phone: String, key: String): Triple<Int, String, String>? {
            val p = phone.trim()
            val k = key.trim()
            return if (p.isNotBlank() && k.isNotBlank()) Triple(slot, p, k) else null
        }
        return listOfNotNull(
            entry(0, config.phoneNumber,  config.apiKey),
            entry(1, config.phoneNumber2, config.apiKey2),
            entry(2, config.phoneNumber3, config.apiKey3),
        )
    }
}
