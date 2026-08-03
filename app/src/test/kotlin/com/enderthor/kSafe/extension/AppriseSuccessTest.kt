package com.enderthor.kSafe.extension

import com.enderthor.kSafe.extension.managers.ConfigurationManager
import io.hammerhead.karooext.KarooSystemService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * Tests for [Sender.isAppriseSuccess], the predicate that decides whether an
 * Apprise-API stateless `/notify` response counts as a delivered alert.
 *
 * The apprise-api reports delivery differently depending on the server version:
 *  - Modern servers answer HTTP 200 on success and non-2xx on failure
 *    (`400` bad request, `424` "one or more notifications could not be sent",
 *    and `204` No Content, which means the notification URL could not be
 *    parsed even though 204 is a 2xx, so a bare `statusCode in 200..299`
 *    check would wrongly treat it as sent).
 *  - Older servers always answer HTTP 200 and carry the result in the body:
 *    `{"ok": true}` on success, `{"ok": false, "errors": [...]}` on failure.
 *
 * The predicate handles both. The Sender class is heavyweight, but
 * isAppriseSuccess is pure, so we test it with mocks like
 * [CallMeBotSuccessTest].
 */
class AppriseSuccessTest {

    private val sender: Sender = Sender(
        karooSystem = mock(KarooSystemService::class.java),
        configManager = mock(ConfigurationManager::class.java),
    )

    // ── Modern server: HTTP status is the discriminator ─────────────────────

    @Test
    fun `200 with modern JSON response is success`() {
        assertTrue(sender.isAppriseSuccess(200, """{"error": null, "details": []}"""))
        assertTrue(sender.isAppriseSuccess(200, """{"error":null,"details":[["INFO","...","sent to ntfy"]]}"""))
    }

    @Test
    fun `204 no-content (invalid URL) is NOT success`() {
        // Modern apprise-api: 204 means "There was no valid URLs provided to
        // notify", i.e. the notification URL could not be parsed. It is a 2xx,
        // so the old statusCode-in-200..299 check reported it as sent, which is
        // exactly the false positive this predicate exists to prevent.
        assertFalse(sender.isAppriseSuccess(204, ""))
        assertFalse(sender.isAppriseSuccess(204, "no content"))
    }

    @Test
    fun `400 bad request is not success`() {
        assertFalse(sender.isAppriseSuccess(400, """{"error": "Payload lacks minimum requirements"}"""))
    }

    @Test
    fun `424 failed dependency is not success`() {
        // Modern apprise-api returns 424 when one or more notifications could
        // not be delivered (e.g. bad credentials in the URL).
        assertFalse(sender.isAppriseSuccess(424, """{"error": "One or more notifications could not be sent", "details": []}"""))
    }

    @Test
    fun `other non-200 statuses are not success`() {
        assertFalse(sender.isAppriseSuccess(404, "not found"))
        assertFalse(sender.isAppriseSuccess(500, "internal server error"))
        assertFalse(sender.isAppriseSuccess(301, ""))
    }

    // ── Legacy server: body `ok` field is the discriminator ─────────────────

    @Test
    fun `legacy 200 with ok true is success`() {
        assertTrue(sender.isAppriseSuccess(200, """{"ok": true}"""))
        assertTrue(sender.isAppriseSuccess(200, """{"ok": true, "errors": []}"""))
    }

    @Test
    fun `legacy 200 with ok false is NOT success`() {
        assertFalse(sender.isAppriseSuccess(200, """{"ok": false, "errors": ["mailtos:// ... failed"]}"""))
    }

    @Test
    fun `ok false matches case-insensitively`() {
        assertFalse(sender.isAppriseSuccess(200, """{"ok": FALSE, "errors": []}"""))
        assertFalse(sender.isAppriseSuccess(200, """{"ok": False, "errors": []}"""))
    }
}
