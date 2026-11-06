package com.enderthor.kSafe.fake

import com.enderthor.kSafe.activity.FieldTapReceiver
import com.enderthor.kSafe.data.EmergencyStatus
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.data.ProviderType
import com.enderthor.kSafe.data.SenderConfig
import com.enderthor.kSafe.extension.managers.EmergencyManager
import fi.nikosavola.karooext.testing.HttpResponses
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import io.hammerhead.karooext.models.RideState
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

// The calibration trail must say what happened to every emergency: a delivered alert and a
// check-in countdown dropped by a pause used to leave no row at all (2026-11-06 sweep,
// dea8f7 check-ins that never reached contacts and could not be explained from the CSV).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KSafeAlertLoggingFakeKarooTest {
    private val fakeKaroo = FakeKarooRule()
    private val ksafe = KSafeFakeKarooRule(fakeKaroo)

    @get:Rule val rules: RuleChain = RuleChain.outerRule(ksafe).around(fakeKaroo)

    private val karoo get() = fakeKaroo

    private val config = KSafeConfig(
        activeProvider = ProviderType.NTFY,
        countdownSeconds = 5,
        emergencyMessage = "SOS {reason} at {location}",
        updateCheckEnabled = false,
        calibrationLoggingEnabled = true,
    )
    private val ntfy = SenderConfig(provider = ProviderType.NTFY, apiKey = "ksafe-topic")

    private fun rows(tag: String) = ksafe.calibrationLog().lines().filter { it.split(',').getOrNull(2) == tag }

    @Test
    fun `a delivered alert leaves an ALERT_OK row`() {
        ksafe.seed(config, ntfy)
        karoo.system.setLocation(60.17, 24.94)
        karoo.system.responder = HttpResponses.success("{}".toByteArray())
        ksafe.startExtension()
        ksafe.tap(FieldTapReceiver.ACTION_SOS)

        val row = ksafe.settleUntil(1.minutes) { rows("ALERT_OK").firstOrNull() }

        assertTrue(row, row.contains("provider=NTFY"))
        assertTrue(row, row.contains("reason=Manual SOS"))
        assertTrue(row, row.contains("delivered=1,total=1"))
        assertTrue(row, Regex("send_ms=\\d+").containsMatchIn(row))
        assertTrue(rows("ALERT_FAIL").isEmpty())
    }

    @Test
    fun `a check-in countdown dropped by a pause leaves a row`() {
        ksafe.seed(config.copy(checkinEnabled = true, checkinIntervalMinutes = 10), ntfy)
        karoo.system.responder = HttpResponses.success("{}".toByteArray())
        ksafe.startExtension()
        karoo.system.setRideState(RideState.Recording)

        ksafe.settleUntil(12.minutes) {
            true.takeIf { EmergencyManager.uiState.value.status == EmergencyStatus.COUNTDOWN }
        }
        karoo.system.setRideState(RideState.Paused(auto = true))
        ksafe.settle(10.seconds)

        assertEquals(EmergencyStatus.IDLE, EmergencyManager.uiState.value.status)
        assertTrue("nothing may be sent", karoo.system.httpRequests.isEmpty())
        assertTrue("logger must be live", rows("EMERG_TRIG").any { "CHECKIN_EXPIRED" in it })
        val row = rows("INC_PAUSE").single()
        assertTrue(row, row.contains("subkind=CHECKIN_EXPIRED"))
        assertTrue(row, row.contains("auto=true"))
        assertTrue(row, Regex("how_long_ms=\\d+").containsMatchIn(row))
    }
}
