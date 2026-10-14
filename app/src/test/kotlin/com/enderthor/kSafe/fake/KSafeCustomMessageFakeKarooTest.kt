package com.enderthor.kSafe.fake

import com.enderthor.kSafe.activity.FieldTapReceiver
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.data.ProviderType
import com.enderthor.kSafe.data.RecipientAlertScope
import com.enderthor.kSafe.data.SenderConfig
import com.enderthor.kSafe.datatype.CustomMessageState
import fi.nikosavola.karooext.testing.HttpResponses
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
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
class KSafeCustomMessageFakeKarooTest {
    private val fakeKaroo = FakeKarooRule()
    private val ksafe = KSafeFakeKarooRule(fakeKaroo)

    @get:Rule val rules: RuleChain = RuleChain.outerRule(ksafe).around(fakeKaroo)

    private val karoo get() = fakeKaroo

    private val config = KSafeConfig(
        activeProvider = ProviderType.NTFY,
        customMessageEnabled = true,
        customMessage = "I'm OK at {location}",
        updateCheckEnabled = false,
    )
    private val ntfy = SenderConfig(provider = ProviderType.NTFY, apiKey = "ksafe-topic")

    private fun startWithLocation() {
        ksafe.seed(config, ntfy)
        karoo.system.setLocation(60.17, 24.94)
        ksafe.startExtension()
    }

    private fun slotState() = CustomMessageState.flowForSlot(1).value

    @Test
    fun `a custom message tap sends the resolved text and marks the slot sent`() {
        karoo.system.responder = HttpResponses.success("ok".toByteArray())
        startWithLocation()

        ksafe.tap(FieldTapReceiver.ACTION_CUSTOM_MESSAGE)

        val request = karoo.awaitValue { karoo.system.httpRequests.firstOrNull() }
        assertEquals("POST", request.method)
        assertEquals("https://ntfy.sh/ksafe-topic", request.url)
        assertEquals(
            "I'm OK at https://maps.google.com/?q=60.17,24.94",
            request.body?.toString(Charsets.UTF_8),
        )
        assertEquals(CustomMessageState.SENT, karoo.awaitValue { slotState().takeIf { it == CustomMessageState.SENT } })
    }

    @Test
    fun `a disabled custom message puts nothing on the wire`() {
        startWithLocation()
        ksafe.reseed(config.copy(customMessageEnabled = false))

        ksafe.tap(FieldTapReceiver.ACTION_CUSTOM_MESSAGE)

        assertEquals(CustomMessageState.ERROR, karoo.awaitValue { slotState().takeIf { it == CustomMessageState.ERROR } })
        assertTrue(karoo.system.httpRequests.isEmpty())
    }

    @Test
    fun `a blank custom message puts nothing on the wire`() {
        startWithLocation()
        ksafe.reseed(config.copy(customMessage = "   "))

        ksafe.tap(FieldTapReceiver.ACTION_CUSTOM_MESSAGE)

        assertEquals(CustomMessageState.ERROR, karoo.awaitValue { slotState().takeIf { it == CustomMessageState.ERROR } })
        assertTrue(karoo.system.httpRequests.isEmpty())
    }

    @Test
    fun `a message every recipient filters out is not sent or reported as sent`() {
        ksafe.seed(config, ntfy.copy(recipient1Alerts = RecipientAlertScope.EMERGENCY_ONLY))
        karoo.system.setLocation(60.17, 24.94)
        ksafe.startExtension()

        ksafe.tap(FieldTapReceiver.ACTION_CUSTOM_MESSAGE)

        assertEquals(CustomMessageState.ERROR, karoo.awaitValue { slotState().takeIf { it == CustomMessageState.ERROR } })
        assertTrue(karoo.system.httpRequests.isEmpty())
    }

    @Test
    fun `the master switch off blocks a custom message`() {
        startWithLocation()
        ksafe.reseed(config.copy(isActive = false))

        ksafe.tap(FieldTapReceiver.ACTION_CUSTOM_MESSAGE)

        assertEquals(CustomMessageState.ERROR, karoo.awaitValue { slotState().takeIf { it == CustomMessageState.ERROR } })
        assertTrue(karoo.system.httpRequests.isEmpty())
    }
}
