package com.enderthor.kSafe.fake

import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.data.ProviderType
import com.enderthor.kSafe.data.SenderConfig
import com.enderthor.kSafe.extension.FailureCause
import com.enderthor.kSafe.extension.SendOutcome
import com.enderthor.kSafe.extension.Sender
import com.enderthor.kSafe.extension.managers.ConfigurationManager
import fi.nikosavola.karooext.testing.HttpResponses
import fi.nikosavola.karooext.testing.RoutingResponder
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KSafeSenderFakeKarooTest {
    private val fakeKaroo = FakeKarooRule()
    private val ksafe = KSafeFakeKarooRule(fakeKaroo)

    @get:Rule val rules: RuleChain = RuleChain.outerRule(ksafe).around(fakeKaroo)

    private val karoo get() = fakeKaroo

    private fun sender(vararg configs: SenderConfig): Sender {
        ksafe.seed(KSafeConfig(), *configs)
        val service = KarooSystemService(karoo.app)
        service.connect { }
        karoo.awaitValue { true.takeIf { service.connected } }
        return Sender(service, ConfigurationManager(karoo.app))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T : Any> Sender.awaitCall(block: suspend Sender.() -> T): T {
        val pending = CoroutineScope(Dispatchers.Default).async { block() }
        return karoo.awaitValue { pending.takeIf { it.isCompleted }?.getCompleted() }
    }

    @Test
    fun `blank Telegram credentials fail fast and put nothing on the wire`() {
        val outcome = sender(SenderConfig(provider = ProviderType.TELEGRAM)).awaitCall {
            sendAlert("SOS", ProviderType.TELEGRAM)
        }

        assertFalse(outcome.anyOk)
        assertEquals(FailureCause.NO_CREDENTIALS, outcome.cause)
        assertTrue(karoo.system.httpRequests.isEmpty())
    }

    @Test
    fun `a plus in the phone number is percent encoded in the CallMeBot request`() {
        karoo.system.responder = HttpResponses.success("Message queued".toByteArray())
        val config = SenderConfig(
            provider = ProviderType.CALLMEBOT,
            phoneNumber = "+358401234567",
            apiKey = "123456",
        )

        val outcome = sender(config).awaitCall { sendAlert("Help & rescue", ProviderType.CALLMEBOT) }

        assertEquals(SendOutcome(delivered = 1, eligible = 1), outcome)
        val request = karoo.system.httpRequests.single()
        assertEquals("GET", request.method)
        assertEquals(
            "https://api.callmebot.com/whatsapp.php?phone=%2B358401234567&text=Help%20%26%20rescue&apikey=123456",
            request.url,
        )
    }

    @Test
    fun `one contact rejecting the key makes the alert partial and the other still gets it`() {
        karoo.system.responder = RoutingResponder.build {
            path("apikey=111111", HttpResponses.success("Message Sent".toByteArray()))
            path("apikey=222222", HttpResponses.success("APIKEY_INVALID".toByteArray()))
        }
        val config = SenderConfig(
            provider = ProviderType.CALLMEBOT,
            phoneNumber = "+358401234567",
            apiKey = "111111",
            phoneNumber2 = "+358407654321",
            apiKey2 = "222222",
        )

        val outcome = sender(config).awaitCall { sendAlert("SOS", ProviderType.CALLMEBOT) }

        assertTrue(outcome.partial)
        assertEquals(1, outcome.delivered)
        assertEquals(2, outcome.eligible)
        assertEquals(2, karoo.system.httpRequests.size)
    }
}
