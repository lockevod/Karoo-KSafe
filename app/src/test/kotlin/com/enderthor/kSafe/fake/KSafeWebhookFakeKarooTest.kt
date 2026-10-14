package com.enderthor.kSafe.fake

import com.enderthor.kSafe.R
import com.enderthor.kSafe.activity.FieldTapReceiver
import com.enderthor.kSafe.data.KSafeConfig
import fi.nikosavola.karooext.testing.HttpResponses
import fi.nikosavola.karooext.testing.RoutingResponder
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import fi.nikosavola.karooext.testing.robolectric.RobolectricPump
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.RideState
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
class KSafeWebhookFakeKarooTest {
    private val fakeKaroo = FakeKarooRule()
    private val ksafe = KSafeFakeKarooRule(fakeKaroo)

    @get:Rule val rules: RuleChain = RuleChain.outerRule(ksafe).around(fakeKaroo)

    private val karoo get() = fakeKaroo
    private val webhookUrl = "https://hooks.example.com/garage"

    private val webhookConfig = KSafeConfig(
        updateCheckEnabled = false,
        webhook1Enabled = true,
        webhook1Label = "Garage",
        webhook1Url = webhookUrl,
        webhook1Method = "POST",
        webhook1Headers = "Authorization: Bearer secret\n\nContent-Type: application/json",
        webhook1Body = """{"open":true}""",
    )

    @Test
    fun `webhook button posts the configured request and confirms with a notification`() {
        ksafe.seed(webhookConfig)
        karoo.system.responder = RoutingResponder.build {
            path(webhookUrl, HttpResponses.success("ok".toByteArray()))
        }
        val host = ksafe.startExtension()

        host.bonusAction("trigger-webhook-1")

        val note = karoo.awaitEffect<SystemNotification> { it.header == "Garage" }
        assertEquals("Garage ✓", note.message)
        val request = karoo.system.httpRequests.single()
        assertEquals("POST", request.method)
        assertEquals(webhookUrl, request.url)
        assertEquals(
            mapOf("Authorization" to "Bearer secret", "Content-Type" to "application/json"),
            request.headers,
        )
        assertEquals("""{"open":true}""", request.body?.toString(Charsets.UTF_8))
    }

    @Test
    fun `webhook confirmation is an in-ride alert while recording and uses real colour resources`() {
        ksafe.seed(webhookConfig.copy(webhook1AlertEnabled = true, webhook1AlertText = "Door is open"))
        karoo.system.responder = HttpResponses.success("ok".toByteArray())
        karoo.system.setRideState(RideState.Recording)
        val host = ksafe.startExtension()

        host.bonusAction("trigger-webhook-1")

        val alerts = karoo.awaitValue {
            karoo.system.effectsOf<InRideAlert>().filter { it.title == "Garage" }.takeIf { it.size >= 2 }
        }
        assertEquals(listOf("Garage ✓", "Door is open"), alerts.map { it.detail })
        assertTrue(karoo.system.effectsOf<SystemNotification>().isEmpty())
        assertEquals(R.color.alert_green, alerts[0].backgroundColor)
        // The ride app resolves these through Context.getColor; a packed ARGB int would crash it.
        alerts.forEach { karoo.app.getColor(it.backgroundColor) }
    }

    @Test
    fun `a failing endpoint reaches the rider as an error with the status code`() {
        ksafe.seed(webhookConfig)
        karoo.system.responder = HttpResponses.status(500)
        val host = ksafe.startExtension()

        host.bonusAction("trigger-webhook-1")

        val note = karoo.awaitEffect<SystemNotification> { it.header == "Garage" }
        assertEquals("HTTP 500: error", note.message)
    }

    @Test
    fun `an unreachable endpoint reaches the rider as a failure`() {
        ksafe.seed(webhookConfig)
        karoo.system.responder = HttpResponses.failure("no route")
        val host = ksafe.startExtension()

        host.bonusAction("trigger-webhook-1")

        val note = karoo.awaitEffect<SystemNotification> { it.header == "Garage" }
        // WebhookManager ignores the transport error text, so the rider sees a bare status 0.
        assertEquals("HTTP 0: error", note.message)
    }

    @Test
    fun `master switch off blocks a field tap before any request is made`() {
        ksafe.seed(webhookConfig.copy(isActive = false))
        ksafe.startExtension()

        ksafe.tap(FieldTapReceiver.ACTION_WEBHOOK_1)

        val note = karoo.awaitEffect<SystemNotification> { it.header == "Garage" }
        assertEquals(karoo.app.getString(R.string.webhook_blocked_master_off), note.message)
        assertTrue(karoo.system.httpRequests.isEmpty())
    }

    @Test
    fun `master switch off silences the hardware button entirely`() {
        ksafe.seed(webhookConfig.copy(isActive = false))
        val host = ksafe.startExtension()

        host.bonusAction("trigger-webhook-1")
        RobolectricPump.advanceBy(5.seconds)

        assertTrue(karoo.system.httpRequests.isEmpty())
        assertTrue(karoo.system.effectsOf<SystemNotification>().none { it.header == "Garage" })
        assertEquals(0, karoo.system.pendingHttpCount)
    }
}
