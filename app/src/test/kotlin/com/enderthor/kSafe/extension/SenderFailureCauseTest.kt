package com.enderthor.kSafe.extension

import com.enderthor.kSafe.data.ProviderType
import com.enderthor.kSafe.data.SenderConfig
import com.enderthor.kSafe.extension.managers.ConfigurationManager
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verifyNoInteractions

/**
 * Covers [Sender.sendWithRetry]'s pre-flight failure classification — the [FailureCause]
 * now stamped on the `ALERT_FAIL` calibration row. Anchored on the 2026-06-03 session
 * `27baa0`, where Telegram was the active provider but had no usable credentials, so the
 * alert failed ~1 s after the countdown (NOT after the ~30 min retry budget). The fail-fast
 * paths touch no network, so they run without coroutine-test virtual time or HTTP stubbing.
 */
class SenderFailureCauseTest {

    private fun senderWith(vararg configs: SenderConfig): Pair<Sender, KarooSystemService> {
        val karoo = mock(KarooSystemService::class.java)
        val cm = mock(ConfigurationManager::class.java)
        `when`(cm.loadSenderConfigFlow()).thenReturn(flowOf(configs.toList()))
        return Sender(karoo, cm) to karoo
    }

    @Test
    fun `blank Telegram credentials fail fast as NO_CREDENTIALS without touching the network`() = runTest {
        // Provider selected, but bot token AND every chat id blank — the 27baa0 scenario.
        val (sender, karoo) = senderWith(SenderConfig(provider = ProviderType.TELEGRAM))
        val outcome = sender.sendAlert("SOS", ProviderType.TELEGRAM)

        assertFalse("must reach nobody", outcome.anyOk)
        assertEquals(FailureCause.NO_CREDENTIALS, outcome.cause)
        // Pre-flight short-circuited before any HTTP attempt — proves no retry budget was burnt.
        verifyNoInteractions(karoo)
    }

    @Test
    fun `token present but all chat ids blank still fails fast as NO_CREDENTIALS`() = runTest {
        val (sender, karoo) = senderWith(
            SenderConfig(provider = ProviderType.TELEGRAM, apiKey = "123:ABC")
        )
        val outcome = sender.sendAlert("SOS", ProviderType.TELEGRAM)

        assertFalse(outcome.anyOk)
        assertEquals(FailureCause.NO_CREDENTIALS, outcome.cause)
        verifyNoInteractions(karoo)
    }

    @Test
    fun `no config for the active provider fails as NO_CONFIG`() = runTest {
        // The sender config list has only CallMeBot; the active provider is Telegram.
        val (sender, karoo) = senderWith(SenderConfig(provider = ProviderType.CALLMEBOT))
        val outcome = sender.sendAlert("SOS", ProviderType.TELEGRAM)

        assertFalse(outcome.anyOk)
        assertEquals(FailureCause.NO_CONFIG, outcome.cause)
        verifyNoInteractions(karoo)
    }
}
