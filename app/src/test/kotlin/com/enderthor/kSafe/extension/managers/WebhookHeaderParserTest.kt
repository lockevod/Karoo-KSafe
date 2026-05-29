package com.enderthor.kSafe.extension.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [parseWebhookHeaders] — the rider's free-text "Key: Value" lines
 * to a `Map<String, String>` for `karooSystem.httpRequest`. The parser is
 * silent on malformed input (treats lines without a colon as no-ops); this
 * test pins the contract so a regression that drops a critical
 * `Authorization` header would surface immediately rather than only on a
 * real device.
 */
class WebhookHeaderParserTest {

    @Test
    fun `blank input returns empty map`() {
        assertEquals(emptyMap<String, String>(), parseWebhookHeaders(""))
        assertEquals(emptyMap<String, String>(), parseWebhookHeaders("   "))
        assertEquals(emptyMap<String, String>(), parseWebhookHeaders("\n\n"))
    }

    @Test
    fun `single line with colon parses key and value with both sides trimmed`() {
        val result = parseWebhookHeaders("Authorization: Bearer xyz")
        assertEquals(mapOf("Authorization" to "Bearer xyz"), result)
    }

    @Test
    fun `multiple lines all parse into the same map`() {
        val raw = "Authorization: Bearer xyz\nContent-Type: application/json"
        val result = parseWebhookHeaders(raw)
        assertEquals(2, result.size)
        assertEquals("Bearer xyz", result["Authorization"])
        assertEquals("application/json", result["Content-Type"])
    }

    @Test
    fun `value containing a colon survives unchanged`() {
        // Bearer tokens often contain colons; we split on the FIRST colon only,
        // so the rest of the value stays intact.
        val result = parseWebhookHeaders("Authorization: Bearer a:b:c")
        assertEquals("Bearer a:b:c", result["Authorization"])
    }

    @Test
    fun `lines without a colon are silently dropped not errored`() {
        // The rider may have pasted a comment or a header continuation. We do
        // not want a partial paste to fail the entire webhook send — better to
        // proceed with the headers that DO parse than to refuse outright.
        val raw = "Authorization: Bearer xyz\nThisIsACommentLine\nContent-Type: application/json"
        val result = parseWebhookHeaders(raw)
        assertEquals(2, result.size)
        assertTrue("comment line must not have created a key", result.none { it.key.contains("Comment") })
    }

    @Test
    fun `blank lines between headers are skipped`() {
        val raw = "Authorization: Bearer xyz\n\n\nContent-Type: application/json\n"
        val result = parseWebhookHeaders(raw)
        assertEquals(2, result.size)
    }

    @Test
    fun `lines with leading whitespace still parse`() {
        // A rider may copy-paste from a code block with indentation.
        val raw = "  Authorization: Bearer xyz\n\tContent-Type: application/json"
        val result = parseWebhookHeaders(raw)
        assertEquals("Bearer xyz", result["Authorization"])
        assertEquals("application/json", result["Content-Type"])
    }

    @Test
    fun `line starting with a colon is silently dropped`() {
        // `colonIdx > 0` guard — a leading colon means no key. Treated as
        // malformed and dropped. Document the contract so a future change
        // doesn't accidentally start emitting empty-key entries.
        val result = parseWebhookHeaders(":no key")
        assertEquals(emptyMap<String, String>(), result)
    }

    @Test
    fun `duplicate keys collapse to last-value-wins (Map semantics)`() {
        val raw = "Authorization: Bearer first\nAuthorization: Bearer second"
        val result = parseWebhookHeaders(raw)
        assertEquals(1, result.size)
        assertEquals("Bearer second", result["Authorization"])
    }

    @Test
    fun `empty value after colon parses to empty string not dropped`() {
        // `Key:` with no value is a valid HTTP header per the spec (empty
        // value). The parser preserves the key with an empty string so the
        // downstream `karooSystem.httpRequest` sees the rider's intent.
        val result = parseWebhookHeaders("X-Empty:")
        assertEquals(1, result.size)
        assertEquals("", result["X-Empty"])
    }

    @Test
    fun `key with no value but trailing whitespace also yields empty value`() {
        val result = parseWebhookHeaders("X-Empty:   ")
        assertEquals("", result["X-Empty"])
    }
}
