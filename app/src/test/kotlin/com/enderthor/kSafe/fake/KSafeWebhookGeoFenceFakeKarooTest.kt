package com.enderthor.kSafe.fake

import com.enderthor.kSafe.R
import com.enderthor.kSafe.data.KSafeConfig
import fi.nikosavola.karooext.testing.HttpResponses
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import io.hammerhead.karooext.models.SystemNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KSafeWebhookGeoFenceFakeKarooTest {
    private val fakeKaroo = FakeKarooRule()
    private val ksafe = KSafeFakeKarooRule(fakeKaroo)

    @get:Rule val rules: RuleChain = RuleChain.outerRule(ksafe).around(fakeKaroo)

    private val karoo get() = fakeKaroo
    private val target = 60.17 to 24.94

    private val base = KSafeConfig(
        updateCheckEnabled = false,
        webhook1Enabled = true,
        webhook1Label = "Garage",
        webhook1Url = "https://hooks.example.com/garage",
        webhook1Method = "POST",
        webhook1Body = """{"open":true}""",
        webhook1GeoEnabled = true,
        webhook1GeoLat = target.first,
        webhook1GeoLon = target.second,
        webhook1GeoRadiusM = 50,
    )

    @Test
    fun `a geo-fenced tap inside the radius fires the request`() {
        ksafe.seed(base)
        karoo.system.responder = HttpResponses.success("ok".toByteArray())
        karoo.system.setLocation(target.first, target.second)
        val host = ksafe.startExtension()

        host.bonusAction("trigger-webhook-1")

        val note = karoo.awaitEffect<SystemNotification> { it.header == "Garage" }
        assertEquals("Garage ✓", note.message)
        assertEquals(1, karoo.system.httpRequests.size)
    }

    @Test
    fun `a geo-fenced tap outside the radius is blocked before any request`() {
        ksafe.seed(base)
        karoo.system.setLocation(60.20, 24.94) // several km north of the target
        val host = ksafe.startExtension()

        host.bonusAction("trigger-webhook-1")

        val note = karoo.awaitEffect<SystemNotification> { it.header == "Garage" }
        assertTrue("expected a too-far block, got: ${note.message}", note.message.contains("max 50m"))
        assertTrue(karoo.system.httpRequests.isEmpty())
    }

    @Test
    fun `a geo-fenced tap without a GPS fix is blocked`() {
        ksafe.seed(base)
        val host = ksafe.startExtension()

        host.bonusAction("trigger-webhook-1")

        // getFreshFix waits out its 3 s one-shot timeout before reporting no fix.
        val note = ksafe.settleUntil(20.seconds) {
            karoo.system.effectsOf<SystemNotification>().firstOrNull { it.header == "Garage" }
        }
        assertEquals(karoo.app.getString(R.string.webhook_blocked_no_gps), note.message)
        assertTrue(karoo.system.httpRequests.isEmpty())
    }

    @Test
    fun `a geo-fenced tap with no target configured is blocked`() {
        ksafe.seed(base.copy(webhook1GeoLat = 0.0, webhook1GeoLon = 0.0))
        karoo.system.setLocation(target.first, target.second)
        val host = ksafe.startExtension()

        host.bonusAction("trigger-webhook-1")

        val note = karoo.awaitEffect<SystemNotification> { it.header == "Garage" }
        assertEquals(karoo.app.getString(R.string.webhook_blocked_no_target), note.message)
        assertTrue(karoo.system.httpRequests.isEmpty())
    }

    @Test
    fun `a disabled webhook slot is blocked before any request`() {
        ksafe.seed(base.copy(webhook1Enabled = false))
        val host = ksafe.startExtension()

        host.bonusAction("trigger-webhook-1")

        val note = karoo.awaitEffect<SystemNotification> { it.header == "Garage" }
        assertEquals(karoo.app.getString(R.string.webhook_blocked_disabled), note.message)
        assertTrue(karoo.system.httpRequests.isEmpty())
    }

    @Test
    fun `a webhook with no URL is blocked before any request`() {
        ksafe.seed(base.copy(webhook1Url = ""))
        val host = ksafe.startExtension()

        host.bonusAction("trigger-webhook-1")

        val note = karoo.awaitEffect<SystemNotification> { it.header == "Garage" }
        assertEquals(karoo.app.getString(R.string.webhook_blocked_no_url), note.message)
        assertTrue(karoo.system.httpRequests.isEmpty())
    }
}
