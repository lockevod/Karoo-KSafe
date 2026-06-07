package com.enderthor.kSafe.extension

import com.enderthor.kSafe.data.ProviderType
import com.enderthor.kSafe.data.SenderConfig

/**
 * Local (no-network) readiness of the messaging provider's credentials — "can KSafe even
 * attempt to send an alert with what the rider has entered?".
 *
 * Motivated by calibration session `327846_40d50a` (2026-06-07): a crash emergency fired but
 * the CallMeBot alert failed in ~1 s via the `hasUsableCredentials` pre-flight — the rider had
 * CallMeBot selected but not fully configured, so EVERY emergency silently fast-fails. This
 * surfaces that misconfiguration to the rider (Provider-tab banner + ride-start warning).
 *
 * The result is a STABLE enum reason (no Android `Context`) so this stays JVM-unit-testable;
 * the UI / notification layers map [Missing] → a localized string. [Sender.hasUsableCredentials]
 * delegates here so the retry-loop fast-fail and the rider-facing warning can never disagree.
 */
sealed interface ProviderReadiness {
    /** At least one deliverable recipient is fully configured. */
    data object Ready : ProviderReadiness

    /** A required credential field is missing — alerts cannot be sent. */
    data class Incomplete(val missing: Missing) : ProviderReadiness

    enum class Missing {
        CALLMEBOT_PHONE_OR_KEY,
        PUSHOVER_APP_TOKEN,
        PUSHOVER_USER_KEY,
        NTFY_TOPIC,
        TELEGRAM_BOT_TOKEN,
        TELEGRAM_CHAT_ID,
    }
}

/**
 * Mirrors [Sender.hasUsableCredentials] exactly, but returns the specific missing field instead
 * of a Boolean. A blank/default [config] yields the provider's primary-missing reason.
 */
fun providerReadiness(provider: ProviderType, config: SenderConfig): ProviderReadiness {
    fun ok(s: String) = s.trim().isNotBlank()
    val userKeys = listOf(config.userKey, config.userKey2, config.userKey3)
    return when (provider) {
        ProviderType.CALLMEBOT -> {
            // CallMeBot needs a (phone, apiKey) PAIR in at least one of the three slots.
            val anyPair = listOf(
                config.phoneNumber  to config.apiKey,
                config.phoneNumber2 to config.apiKey2,
                config.phoneNumber3 to config.apiKey3,
            ).any { (p, k) -> ok(p) && ok(k) }
            if (anyPair) ProviderReadiness.Ready
            else ProviderReadiness.Incomplete(ProviderReadiness.Missing.CALLMEBOT_PHONE_OR_KEY)
        }
        ProviderType.PUSHOVER -> when {
            !ok(config.apiKey)        -> ProviderReadiness.Incomplete(ProviderReadiness.Missing.PUSHOVER_APP_TOKEN)
            userKeys.none { ok(it) }  -> ProviderReadiness.Incomplete(ProviderReadiness.Missing.PUSHOVER_USER_KEY)
            else                      -> ProviderReadiness.Ready
        }
        ProviderType.NTFY ->
            if (ok(config.apiKey)) ProviderReadiness.Ready
            else ProviderReadiness.Incomplete(ProviderReadiness.Missing.NTFY_TOPIC)
        ProviderType.TELEGRAM -> when {
            !ok(config.apiKey)        -> ProviderReadiness.Incomplete(ProviderReadiness.Missing.TELEGRAM_BOT_TOKEN)
            userKeys.none { ok(it) }  -> ProviderReadiness.Incomplete(ProviderReadiness.Missing.TELEGRAM_CHAT_ID)
            else                      -> ProviderReadiness.Ready
        }
    }
}

/**
 * True when [a] and [b] carry the same delivery CREDENTIALS (the 9 token/phone/key fields) —
 * scope and [SenderConfig.lastSuccessfulSendMs] are ignored. Used to decide whether a config save
 * should clear the last-successful-send timestamp: editing a credential invalidates a prior
 * successful send, but changing only an alert scope does not. Pure → unit-testable.
 */
fun sameCredentials(a: SenderConfig, b: SenderConfig): Boolean =
    a.apiKey == b.apiKey &&
    a.userKey == b.userKey && a.userKey2 == b.userKey2 && a.userKey3 == b.userKey3 &&
    a.phoneNumber == b.phoneNumber && a.phoneNumber2 == b.phoneNumber2 && a.phoneNumber3 == b.phoneNumber3 &&
    a.apiKey2 == b.apiKey2 && a.apiKey3 == b.apiKey3

/** Staleness window for the ride-start "please re-test your provider" reminder: 30 days. */
const val SEND_STALENESS_WINDOW_MS = 30L * 24 * 60 * 60 * 1000

/**
 * True when this provider has worked before ([lastSuccessfulSendMs] > 0) but the last successful
 * send is older than [windowMs] — i.e. the provider has gone quiet long enough that its
 * credentials may have rotted (revoked CallMeBot key, deleted bot, expired Pushover trial) and a
 * fresh Test Send is worth prompting. A provider that has NEVER sent (== 0) is NOT "stale" — that
 * is the Provider-tab "not verified" nudge instead. Pure → unit-testable.
 */
fun isSendStale(lastSuccessfulSendMs: Long, nowMs: Long, windowMs: Long = SEND_STALENESS_WINDOW_MS): Boolean =
    lastSuccessfulSendMs > 0L && (nowMs - lastSuccessfulSendMs) > windowMs
