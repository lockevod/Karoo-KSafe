package com.enderthor.kSafe.extension

import com.enderthor.kSafe.data.ProviderType
import com.enderthor.kSafe.data.RecipientAlertScope
import com.enderthor.kSafe.data.SenderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [providerReadiness] — the local (no-network) credential check that drives both the
 * Sender retry-loop fast-fail and the rider-facing "provider incomplete" warning (Provider tab
 * banner + ride-start InRideAlert). Motivated by calibration session 327846_40d50a where a
 * CallMeBot emergency fast-failed because the provider was selected but not fully configured.
 */
class ProviderReadinessTest {

    private fun incomplete(r: ProviderReadiness): ProviderReadiness.Missing {
        assertTrue("expected Incomplete but was $r", r is ProviderReadiness.Incomplete)
        return (r as ProviderReadiness.Incomplete).missing
    }

    // ── CallMeBot ──
    @Test fun callmebot_ready_when_slot1_has_phone_and_key() {
        val cfg = SenderConfig(provider = ProviderType.CALLMEBOT, phoneNumber = "34600111222", apiKey = "123456")
        assertEquals(ProviderReadiness.Ready, providerReadiness(ProviderType.CALLMEBOT, cfg))
    }

    @Test fun callmebot_ready_when_only_slot2_has_a_pair() {
        val cfg = SenderConfig(provider = ProviderType.CALLMEBOT, phoneNumber2 = "34600111222", apiKey2 = "123456")
        assertEquals(ProviderReadiness.Ready, providerReadiness(ProviderType.CALLMEBOT, cfg))
    }

    @Test fun callmebot_incomplete_when_phone_only() {
        val cfg = SenderConfig(provider = ProviderType.CALLMEBOT, phoneNumber = "34600111222")
        assertEquals(ProviderReadiness.Missing.CALLMEBOT_PHONE_OR_KEY,
            incomplete(providerReadiness(ProviderType.CALLMEBOT, cfg)))
    }

    @Test fun callmebot_incomplete_when_key_only() {
        val cfg = SenderConfig(provider = ProviderType.CALLMEBOT, apiKey = "123456")
        assertEquals(ProviderReadiness.Missing.CALLMEBOT_PHONE_OR_KEY,
            incomplete(providerReadiness(ProviderType.CALLMEBOT, cfg)))
    }

    @Test fun callmebot_incomplete_when_whitespace_only() {
        val cfg = SenderConfig(provider = ProviderType.CALLMEBOT, phoneNumber = "  ", apiKey = "  ")
        assertEquals(ProviderReadiness.Missing.CALLMEBOT_PHONE_OR_KEY,
            incomplete(providerReadiness(ProviderType.CALLMEBOT, cfg)))
    }

    // ── Pushover ──
    @Test fun pushover_ready_when_token_and_user() {
        val cfg = SenderConfig(provider = ProviderType.PUSHOVER, apiKey = "appToken", userKey = "userKey")
        assertEquals(ProviderReadiness.Ready, providerReadiness(ProviderType.PUSHOVER, cfg))
    }

    @Test fun pushover_incomplete_app_token_first() {
        val cfg = SenderConfig(provider = ProviderType.PUSHOVER, userKey = "userKey")  // token blank
        assertEquals(ProviderReadiness.Missing.PUSHOVER_APP_TOKEN,
            incomplete(providerReadiness(ProviderType.PUSHOVER, cfg)))
    }

    @Test fun pushover_incomplete_user_key_when_token_present() {
        val cfg = SenderConfig(provider = ProviderType.PUSHOVER, apiKey = "appToken")  // no user keys
        assertEquals(ProviderReadiness.Missing.PUSHOVER_USER_KEY,
            incomplete(providerReadiness(ProviderType.PUSHOVER, cfg)))
    }

    // ── ntfy ──
    @Test fun ntfy_ready_when_topic_set() {
        val cfg = SenderConfig(provider = ProviderType.NTFY, apiKey = "ksafe-topic-x9")
        assertEquals(ProviderReadiness.Ready, providerReadiness(ProviderType.NTFY, cfg))
    }

    @Test fun ntfy_incomplete_when_topic_blank() {
        val cfg = SenderConfig(provider = ProviderType.NTFY)
        assertEquals(ProviderReadiness.Missing.NTFY_TOPIC,
            incomplete(providerReadiness(ProviderType.NTFY, cfg)))
    }

    // ── Telegram ──
    @Test fun telegram_ready_when_token_and_chat() {
        val cfg = SenderConfig(provider = ProviderType.TELEGRAM, apiKey = "botToken", userKey = "chatId")
        assertEquals(ProviderReadiness.Ready, providerReadiness(ProviderType.TELEGRAM, cfg))
    }

    @Test fun telegram_incomplete_bot_token_first() {
        val cfg = SenderConfig(provider = ProviderType.TELEGRAM, userKey = "chatId")  // token blank
        assertEquals(ProviderReadiness.Missing.TELEGRAM_BOT_TOKEN,
            incomplete(providerReadiness(ProviderType.TELEGRAM, cfg)))
    }

    @Test fun telegram_incomplete_chat_id_when_token_present() {
        val cfg = SenderConfig(provider = ProviderType.TELEGRAM, apiKey = "botToken")  // no chat id
        assertEquals(ProviderReadiness.Missing.TELEGRAM_CHAT_ID,
            incomplete(providerReadiness(ProviderType.TELEGRAM, cfg)))
    }

    // ── default/blank config is Incomplete for every provider ──
    @Test fun blank_config_incomplete_for_all_providers() {
        for (p in ProviderType.values()) {
            assertTrue("blank $p should be Incomplete",
                providerReadiness(p, SenderConfig(provider = p)) is ProviderReadiness.Incomplete)
        }
    }

    // ── sameCredentials (verified-reset logic) ──
    @Test fun sameCredentials_true_ignoring_scope_and_timestamp() {
        val a = SenderConfig(provider = ProviderType.CALLMEBOT, phoneNumber = "34600111222", apiKey = "k",
            recipient1Alerts = RecipientAlertScope.ALL, lastSuccessfulSendMs = 123L)
        val b = a.copy(recipient1Alerts = RecipientAlertScope.EMERGENCY_ONLY, lastSuccessfulSendMs = 0L)
        assertTrue(sameCredentials(a, b))
    }

    @Test fun sameCredentials_false_when_a_credential_changes() {
        val a = SenderConfig(provider = ProviderType.TELEGRAM, apiKey = "bot", userKey = "chat")
        assertFalse(sameCredentials(a, a.copy(userKey = "chat2")))
        assertFalse(sameCredentials(a, a.copy(apiKey = "bot2")))
    }

    // ── carryForwardOnSave (what a Provider-tab save keeps vs invalidates) ──
    private val acked = SenderConfig(
        provider = ProviderType.CALLMEBOT, phoneNumber = "34600111222", apiKey = "k",
        lastSuccessfulSendMs = 123L, providerWarningAcknowledged = true,
    )

    @Test fun `a scope-only save keeps both the send stamp and the acknowledgement`() {
        val edited = acked.copy(recipient1Alerts = RecipientAlertScope.EMERGENCY_ONLY,
            lastSuccessfulSendMs = 0L, providerWarningAcknowledged = false)
        val saved = carryForwardOnSave(edited, acked)
        assertEquals(123L, saved.lastSuccessfulSendMs)
        assertTrue(saved.providerWarningAcknowledged)
    }

    // The safety case: a rider who acknowledged "no provider, stop warning me" and LATER enters
    // credentials must be warned again if those credentials are wrong — otherwise the mistake is
    // silent until the crash that needed the alert.
    @Test fun `editing a credential clears the acknowledgement and the send stamp`() {
        val saved = carryForwardOnSave(acked.copy(apiKey = "k2"), acked)
        assertEquals(0L, saved.lastSuccessfulSendMs)
        assertFalse(saved.providerWarningAcknowledged)
    }

    @Test fun `filling in a previously blank credential clears the acknowledgement`() {
        val blank = SenderConfig(provider = ProviderType.TELEGRAM, providerWarningAcknowledged = true)
        val saved = carryForwardOnSave(blank.copy(apiKey = "bot", userKey = "chat"), blank)
        assertFalse(saved.providerWarningAcknowledged)
    }

    // ── fieldSyncKey (what may re-seed the Provider-tab form) ──
    // The form's field-sync effect is keyed on this. If the key changed on a NON-credential
    // write, the effect would re-run mid-typing and copy stored values over what the rider is
    // still entering — losing it, and then persisting the loss via the 700 ms debounce. Tapping
    // the acknowledgement Switch is exactly such a write.
    @Test fun `toggling the acknowledgement does not change the field-sync key`() {
        val c = SenderConfig(provider = ProviderType.TELEGRAM, apiKey = "bot", userKey = "chat")
        assertEquals(c.fieldSyncKey(), c.copy(providerWarningAcknowledged = true).fieldSyncKey())
    }

    @Test fun `a successful-send stamp does not change the field-sync key`() {
        val c = SenderConfig(provider = ProviderType.NTFY, apiKey = "topic")
        assertEquals(c.fieldSyncKey(), c.copy(lastSuccessfulSendMs = 999L).fieldSyncKey())
    }

    @Test fun `an edited credential or scope does change the field-sync key`() {
        val c = SenderConfig(provider = ProviderType.TELEGRAM, apiKey = "bot", userKey = "chat")
        assertNotEquals(c.fieldSyncKey(), c.copy(apiKey = "bot2").fieldSyncKey())
        assertNotEquals(c.fieldSyncKey(), c.copy(recipient2Alerts = RecipientAlertScope.EMERGENCY_ONLY).fieldSyncKey())
    }

    // ── isSendStale (ride-start re-test reminder) ──
    private val window = SEND_STALENESS_WINDOW_MS
    private val now = 1_000_000_000_000L

    @Test fun never_sent_is_not_stale() {
        assertFalse(isSendStale(0L, now, window))
    }

    @Test fun recent_send_is_not_stale() {
        assertFalse(isSendStale(now - window / 2, now, window))   // 15 days ago
    }

    @Test fun old_send_is_stale() {
        assertTrue(isSendStale(now - window - 1, now, window))    // just over 30 days
        assertTrue(isSendStale(now - window * 3, now, window))    // 90 days
    }

    @Test fun exactly_window_is_not_stale() {
        assertFalse(isSendStale(now - window, now, window))       // strictly greater than window
    }
}
