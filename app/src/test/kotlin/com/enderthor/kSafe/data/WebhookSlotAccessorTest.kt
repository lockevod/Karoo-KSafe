package com.enderthor.kSafe.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WebhookSlotAccessorTest {

    @Test
    fun `webhookSlot reads the right flat fields per slot`() {
        val c = KSafeConfig(
            webhook2Enabled = true, webhook2Label = "Two", webhook2Url = "u2",
            webhook3Enabled = true, webhook3Url = "u3", webhook3GeoRadiusM = 80,
            webhook4Label = "Four", webhook4AlertText = "done4",
        )
        assertEquals("Two", c.webhookSlot(2).label)
        assertEquals("u2", c.webhookSlot(2).url)
        assertEquals(true, c.webhookSlot(3).enabled)
        assertEquals(80, c.webhookSlot(3).geoRadiusM)
        assertEquals("Four", c.webhookSlot(4).label)
        assertEquals("done4", c.webhookSlot(4).alertText)
    }

    @Test
    fun `withWebhookSlot writes back only the targeted slot`() {
        val c = KSafeConfig()
        val updated = c.withWebhookSlot(3, c.webhookSlot(3).copy(enabled = true, url = "x", label = "L3"))
        assertEquals(true, updated.webhook3Enabled)
        assertEquals("x", updated.webhook3Url)
        assertEquals("L3", updated.webhook3Label)
        assertEquals(false, updated.webhook1Enabled)
        assertEquals(false, updated.webhook4Enabled)
    }

    @Test
    fun `webhookSlot rejects out-of-range slot`() {
        assertThrows(IllegalArgumentException::class.java) { KSafeConfig().webhookSlot(5) }
    }
}
