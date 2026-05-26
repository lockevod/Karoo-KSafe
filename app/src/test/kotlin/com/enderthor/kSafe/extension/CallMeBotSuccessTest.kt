package com.enderthor.kSafe.extension

import com.enderthor.kSafe.extension.managers.ConfigurationManager
import io.hammerhead.karooext.KarooSystemService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * Tests for [Sender.isCallMeBotSuccess] — the predicate that decides whether
 * a CallMeBot HTTP response represents a real delivery success. Three
 * historical bugs the function exists to prevent:
 *
 *  1. **Substring "ok" false positive (K1)** — the previous version's whitelist
 *     contained the bare 2-char "ok" which also matched inside `tOKen`,
 *     `revOKed`, `looKup`. Failure bodies like "Token expired" silently
 *     returned true.
 *  2. **Empty-body false positive** — `body.isBlank()` used to count as success,
 *     but an empty 2xx is the tell-tale signature of a captive-portal /
 *     proxy intercept, never a real CallMeBot success.
 *  3. **Non-2xx false negative** — anything outside the 200-299 range is a
 *     failure regardless of body shape.
 *
 * The Sender class is heavyweight (Context + KarooSystemService deps) but the
 * isCallMeBotSuccess method is pure — we instantiate the surrounding class with
 * mocks just to access the internal helper.
 */
class CallMeBotSuccessTest {

    private val sender: Sender = Sender(
        karooSystem = mock(KarooSystemService::class.java),
        configManager = mock(ConfigurationManager::class.java),
    )

    // ── Success cases ────────────────────────────────────────────────────────

    @Test
    fun `200 with explicit success phrase returns true`() {
        assertTrue(sender.isCallMeBotSuccess(200, "Message Sent"))
        assertTrue(sender.isCallMeBotSuccess(200, "Message queued"))
        // Substring match: "message queued" appears verbatim inside a longer line.
        assertTrue(sender.isCallMeBotSuccess(200, "Status: Message queued — id 12345"))
    }

    @Test
    fun `success phrase matches case-insensitively`() {
        assertTrue(sender.isCallMeBotSuccess(200, "MESSAGE SENT"))
        assertTrue(sender.isCallMeBotSuccess(200, "message sent"))
        assertTrue(sender.isCallMeBotSuccess(204, "Message Queued"))
    }

    @Test
    fun `standalone OK matches trim-equals not substring`() {
        assertTrue(sender.isCallMeBotSuccess(200, "OK"))
        assertTrue(sender.isCallMeBotSuccess(200, "  OK  "))
        assertTrue(sender.isCallMeBotSuccess(200, "ok"))   // case-insensitive
    }

    // ── False positives the function specifically prevents ──────────────────

    @Test
    fun `K1 — token expired does NOT match the bare ok substring`() {
        // Pre-K1 the whitelist contained literal "ok" which also matched
        // `tOKen` (case-insensitive). A failure body like "Token expired"
        // silently returned true. The fix requires standalone-OK via trim
        // + equals AND adds blacklist patterns; both apply here.
        assertFalse(sender.isCallMeBotSuccess(200, "Token expired"))
        assertFalse(sender.isCallMeBotSuccess(200, "API key revoked"))
        assertFalse(sender.isCallMeBotSuccess(200, "lookup failed"))
    }

    @Test
    fun `empty body is NOT success (captive-portal signature)`() {
        assertFalse(sender.isCallMeBotSuccess(200, ""))
        assertFalse(sender.isCallMeBotSuccess(200, "   "))
        assertFalse(sender.isCallMeBotSuccess(200, "\n"))
    }

    @Test
    fun `non-2xx status is never success regardless of body`() {
        assertFalse(sender.isCallMeBotSuccess(404, "Message Sent"))
        assertFalse(sender.isCallMeBotSuccess(500, "OK"))
        assertFalse(sender.isCallMeBotSuccess(429, "Message queued"))
        assertFalse(sender.isCallMeBotSuccess(301, "OK"))     // redirect
    }

    @Test
    fun `known failure phrases are caught`() {
        // Each of these is from a documented CallMeBot failure response
        // (the API returns HTTP 200 even on failure — body is the discriminator).
        assertFalse(sender.isCallMeBotSuccess(200, "ERROR: invalid phone"))
        assertFalse(sender.isCallMeBotSuccess(200, "APIKEY_INVALID"))
        assertFalse(sender.isCallMeBotSuccess(200, "You need to authorize this number"))
        assertFalse(sender.isCallMeBotSuccess(200, "Number not found"))
        assertFalse(sender.isCallMeBotSuccess(200, "Forbidden"))
        assertFalse(sender.isCallMeBotSuccess(200, "Daily limit reached"))
        assertFalse(sender.isCallMeBotSuccess(200, "Access denied"))
    }

    @Test
    fun `failure phrase wins over success phrase if both present`() {
        // The blacklist runs BEFORE the whitelist — a body that mixes both
        // (defensive against a future API change that includes both markers)
        // is treated as failure. Bias toward safety.
        assertFalse(sender.isCallMeBotSuccess(200, "Message Sent but limit reached"))
        assertFalse(sender.isCallMeBotSuccess(200, "OK error follows"))
    }

    @Test
    fun `random non-matching body is not success`() {
        // Anything that doesn't hit a known success phrase nor matches "OK"
        // standalone falls through to false — conservative default.
        assertFalse(sender.isCallMeBotSuccess(200, "<html>some unexpected page</html>"))
        assertFalse(sender.isCallMeBotSuccess(200, "yes"))
    }
}
