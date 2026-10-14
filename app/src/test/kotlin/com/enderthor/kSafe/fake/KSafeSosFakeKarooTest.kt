package com.enderthor.kSafe.fake

import com.enderthor.kSafe.R
import com.enderthor.kSafe.activity.FieldTapReceiver
import com.enderthor.kSafe.data.EmergencyStatus
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.data.ProviderType
import com.enderthor.kSafe.data.SenderConfig
import com.enderthor.kSafe.extension.managers.EmergencyManager
import fi.nikosavola.karooext.testing.HttpResponder
import fi.nikosavola.karooext.testing.HttpResponses
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import fi.nikosavola.karooext.testing.robolectric.RobolectricPump
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.SystemNotification
import io.hammerhead.karooext.models.TurnScreenOn
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KSafeSosFakeKarooTest {
    private val fakeKaroo = FakeKarooRule()
    private val ksafe = KSafeFakeKarooRule(fakeKaroo)

    @get:Rule val rules: RuleChain = RuleChain.outerRule(ksafe).around(fakeKaroo)

    private val karoo get() = fakeKaroo

    private val config = KSafeConfig(
        activeProvider = ProviderType.NTFY,
        countdownSeconds = 5,
        emergencyMessage = "SOS {reason} at {location}",
        // The startup update check sends its own requests, which would count as alert retries.
        updateCheckEnabled = false,
    )
    private val ntfy = SenderConfig(provider = ProviderType.NTFY, apiKey = "ksafe-topic")

    private fun startWithSos() {
        ksafe.seed(config, ntfy)
        karoo.system.setLocation(60.17, 24.94)
        ksafe.startExtension()
        ksafe.tap(FieldTapReceiver.ACTION_SOS)
    }

    @Test
    fun `sos tap beeps through the countdown and then sends the alert`() {
        // Sampled when the request arrives: the status leaves ALERTING once the response is handled.
        val statusAtSend = AtomicReference<EmergencyStatus>()
        karoo.system.responder = HttpResponder { request ->
            statusAtSend.compareAndSet(null, EmergencyManager.uiState.value.status)
            HttpResponses.success("{}".toByteArray()).respond(request)
        }
        startWithSos()

        karoo.awaitEffect<TurnScreenOn>()
        karoo.awaitEffect<PlayBeepPattern>()
        assertEquals(EmergencyStatus.COUNTDOWN, EmergencyManager.uiState.value.status)

        ksafe.settle(3.seconds, steps = 3)
        assertTrue("nothing may be sent before the countdown ends", karoo.system.httpRequests.isEmpty())

        RobolectricPump.advanceBy(2.seconds)
        val request = karoo.awaitValue { karoo.system.httpRequests.firstOrNull() }
        assertEquals("POST", request.method)
        assertEquals("https://ntfy.sh/ksafe-topic", request.url)
        assertEquals("KSafe Emergency", request.headers["Title"])
        assertEquals("urgent", request.headers["Priority"])
        assertEquals(
            "SOS Manual SOS at https://maps.google.com/?q=60.17,24.94",
            request.body?.toString(Charsets.UTF_8),
        )
        assertEquals(EmergencyStatus.ALERTING, karoo.awaitValue { statusAtSend.get() })

        RobolectricPump.advanceBy(6.seconds)
        assertEquals(EmergencyStatus.IDLE, EmergencyManager.uiState.value.status)
        assertEquals(1, karoo.system.httpRequests.size)
    }

    @Test
    fun `cancel button during the countdown sends nothing`() {
        startWithSos()
        karoo.awaitEffect<TurnScreenOn>()
        RobolectricPump.advanceBy(2.seconds)
        assertEquals(EmergencyStatus.COUNTDOWN, EmergencyManager.uiState.value.status)

        ksafe.host.bonusAction("cancel-emergency")
        ksafe.settle(30.seconds)

        assertEquals(EmergencyStatus.IDLE, EmergencyManager.uiState.value.status)
        assertTrue(karoo.system.httpRequests.isEmpty())
        assertEquals(0, karoo.system.pendingHttpCount)
    }

    @Test
    fun `a failed first send is retried after the backoff`() {
        ksafe.seed(config, ntfy)
        karoo.system.setLocation(60.17, 24.94)
        val responder = HttpResponses.sequence(HttpResponses.status(503), HttpResponses.success("{}".toByteArray()))
        karoo.system.responder = responder
        ksafe.startExtension()
        ksafe.tap(FieldTapReceiver.ACTION_SOS)

        ksafe.settleUntil(30.seconds) { karoo.system.httpRequests.firstOrNull() }
        assertEquals("only one attempt before the backoff", 1, karoo.system.httpRequests.size)
        ksafe.settle(30.seconds)
        assertEquals("no immediate retry", 1, karoo.system.httpRequests.size)

        ksafe.settleUntil(30.seconds) { karoo.system.httpRequests.getOrNull(1) }
        assertEquals(0, responder.remainingResponses)
        assertEquals(
            karoo.system.httpRequests[0].body?.toString(Charsets.UTF_8),
            karoo.system.httpRequests[1].body?.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `an alert that never gets through tells the rider after the last retry`() {
        ksafe.seed(config, ntfy)
        karoo.system.setLocation(60.17, 24.94)
        karoo.system.responder = HttpResponses.status(503)
        ksafe.startExtension()
        ksafe.tap(FieldTapReceiver.ACTION_SOS)

        val title = karoo.app.getString(R.string.alert_delivery_failed_title)
        val note = ksafe.settleUntil(40.minutes) {
            karoo.system.effectsOf<SystemNotification>().firstOrNull { it.header == title }
        }

        assertEquals(karoo.app.getString(R.string.alert_delivery_failed_detail, "NTFY"), note.message)
        // 3 cycles of 3 attempts, no more once the budget is spent.
        assertEquals(9, karoo.system.httpRequests.size)
        val descending = karoo.system.effectsOf<PlayBeepPattern>().last()
        assertEquals(600, descending.tones.first().frequency)
    }
}
