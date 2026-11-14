package com.enderthor.kSafe.fake

import com.enderthor.kSafe.R
import com.enderthor.kSafe.activity.FieldTapReceiver
import com.enderthor.kSafe.data.EmergencyReason
import com.enderthor.kSafe.data.EmergencyStatus
import com.enderthor.kSafe.data.IncidentResponseLevel
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.data.ProviderType
import com.enderthor.kSafe.data.SenderConfig
import com.enderthor.kSafe.extension.KSafeExtension
import com.enderthor.kSafe.extension.managers.EmergencyManager
import com.enderthor.kSafe.extension.pacing.AlertCandidate
import com.enderthor.kSafe.extension.pacing.AlertSource
import com.enderthor.kSafe.extension.pacing.PacingAlert
import com.enderthor.kSafe.extension.pacing.PacingState
import com.enderthor.kSafe.extension.pacing.TestPolyline
import fi.nikosavola.karooext.testing.HttpResponses
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import fi.nikosavola.karooext.testing.robolectric.RobolectricPump
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.RideState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KSafePacingFakeKarooTest {
    private val fakeKaroo = FakeKarooRule()
    private val ksafe = KSafeFakeKarooRule(fakeKaroo)

    @get:Rule val rules: RuleChain = RuleChain.outerRule(ksafe).around(fakeKaroo)

    private val karoo get() = fakeKaroo

    private fun route() = OnNavigationState.NavigationState.NavigatingRoute(
        routePolyline = TestPolyline.latLng(listOf(0.0 to 0.0, 0.0 to 0.2)),
        routeDistance = 22_264.0,
        routeElevationPolyline = TestPolyline.elev(
            listOf(0.0 to 100.0, 10_000.0 to 100.0, 15_000.0 to 450.0, 22_264.0 to 450.0),
        ),
        rejoinPolyline = null, rejoinDistance = null, name = "t", reversed = false, breadcrumb = false,
        pois = emptyList(), climbs = emptyList(),
    )

    @Test
    fun `a recording ride with calibration logging on and a route logs PACE_ROUTE`() {
        ksafe.seed(KSafeConfig(updateCheckEnabled = false, calibrationLoggingEnabled = true))
        ksafe.startExtension()

        karoo.system.setRideState(RideState.Recording)
        ksafe.settle(2.seconds)
        karoo.system.setNavigation(route())

        val rows = ksafe.settleUntil(30.seconds) {
            ksafe.calibrationLog().lines().filter { it.split(',').getOrNull(2) == "PACE_ROUTE" }.takeIf { it.isNotEmpty() }
        }
        assertTrue(rows.isNotEmpty())
    }

    @Test
    fun `a mid-ride master switch flip resumes pacing without ending the ride`() {
        val on = KSafeConfig(updateCheckEnabled = false, calibrationLoggingEnabled = true)
        ksafe.seed(on)
        ksafe.startExtension()
        karoo.system.setRideState(RideState.Recording)
        ksafe.settle(2.seconds)

        ksafe.reseed(on.copy(isActive = false))
        ksafe.reseed(on)

        val tags = ksafe.calibrationLog().lines().mapNotNull { l ->
            val c = l.split(',')
            c.getOrNull(2)?.takeIf { it in setOf("PACE_START", "PACE_STOP", "PACE_END") }?.let { "$it ${c.getOrNull(3)?.trim('"')}" }
        }
        assertEquals(listOf("PACE_START reason=fresh", "PACE_STOP reason=master_off", "PACE_START reason=resume"), tags)
    }

    @Test
    fun `pacing alerts are live only with the pacing switch and never over an SOS`() {
        val pacing = KSafeConfig(updateCheckEnabled = false, pacingEnabled = true,
            activeProvider = ProviderType.NTFY, countdownSeconds = 60)
        ksafe.seed(pacing, SenderConfig(provider = ProviderType.NTFY, apiKey = "ksafe-topic"))
        ksafe.startExtension()
        val ext = KSafeExtension.getInstance()!!
        karoo.awaitValue { true.takeIf { ext.pacingManager.liveAlerts } }
        val alert = PacingAlert(AlertSource.PACING, PacingState.CONSERVE, "CONSERVE#1", 4.2, 6.0, 7.0, 210, null, null, false)
        fun pacingAlerts() = karoo.system.effectsOf<InRideAlert>().filter { it.id.startsWith("ksafe-pacing-") }

        assertTrue(ext.dispatchPacingAlert(alert))
        val shown = pacingAlerts().single()
        assertEquals("Save it for the climb", shown.title)
        assertEquals(R.color.pacing_amber, shown.backgroundColor)

        // Calibration logging alone keeps pacing in shadow.
        ksafe.reseed(pacing.copy(pacingEnabled = false, calibrationLoggingEnabled = true))
        karoo.awaitValue { true.takeIf { !ext.pacingManager.liveAlerts } }
        assertFalse(ext.pacingManager.liveAlerts)

        // An active emergency blocks the dispatch even if a pacing alert reaches the sink.
        ksafe.reseed(pacing)
        ksafe.tap(FieldTapReceiver.ACTION_SOS)
        assertEquals(EmergencyStatus.COUNTDOWN, EmergencyManager.uiState.value.status)
        assertFalse(ext.dispatchPacingAlert(alert))            // dropped: the arbiter does not record it
        assertEquals(1, pacingAlerts().size)
    }

    @Test
    fun `only a rider-visible incident gates pacing`() {
        ksafe.seed(KSafeConfig(updateCheckEnabled = false, pacingEnabled = true))
        ksafe.startExtension()
        val ext = KSafeExtension.getInstance()!!

        ext.handleSafetyIncident(EmergencyReason.WELLNESS_DECOUPLING, IncidentResponseLevel.SILENT, emptyMap())
        assertNull(ext.pacingManager.lastMedicalMs)              // log-only: msSinceWellnessOrMedical stays unset

        ext.handleSafetyIncident(EmergencyReason.WELLNESS_DECOUPLING, IncidentResponseLevel.WARNING, emptyMap())
        assertNotNull(ext.pacingManager.lastMedicalMs)
    }

    private val ntfy = SenderConfig(provider = ProviderType.NTFY, apiKey = "ksafe-topic")
    private val conserve = PacingAlert(AlertSource.PACING, PacingState.CONSERVE, "CONSERVE#1", 4.2, 6.0, 7.0, 210, null, null, false)

    /** What the pacing arbiter says right now about a pacing candidate (the manager clock is elapsedRealtime). */
    private fun pacingGap(ext: KSafeExtension) = ext.pacingManager.arbiter.evaluate(
        AlertCandidate(AlertSource.PACING, "CONSERVE#9", 0.5), android.os.SystemClock.elapsedRealtime(), false).suppressedBy

    @Test
    fun `pacing stays blocked while the SOS retries after the ALERTING window`() {
        ksafe.seed(KSafeConfig(updateCheckEnabled = false, pacingEnabled = true,
            activeProvider = ProviderType.NTFY, countdownSeconds = 5), ntfy)
        karoo.system.setLocation(60.17, 24.94)
        karoo.system.responder = HttpResponses.status(503)
        ksafe.startExtension()
        val ext = KSafeExtension.getInstance()!!
        ksafe.tap(FieldTapReceiver.ACTION_SOS)
        RobolectricPump.advanceBy(6.seconds)
        karoo.awaitValue { karoo.system.httpRequests.firstOrNull() }        // first attempt failed: backoff running
        ksafe.settleUntil(30.seconds) { true.takeIf { EmergencyManager.uiState.value.status == EmergencyStatus.IDLE } }
        assertEquals("the retry is still pending", 1, karoo.system.httpRequests.size)

        assertFalse(ext.dispatchPacingAlert(conserve))
        assertTrue(karoo.system.effectsOf<InRideAlert>().none { it.id.startsWith("ksafe-pacing-") })
        // ALERTING -> IDLE recorded the emergency: the 3-min quiet gap holds once the retry ends too.
        assertEquals("gap:EMERGENCY", pacingGap(ext))
    }

    @Test
    fun `the ride-start provider warning opens the pacing gap`() {
        ksafe.seed(KSafeConfig(updateCheckEnabled = false, pacingEnabled = true, activeProvider = ProviderType.NTFY),
            SenderConfig(provider = ProviderType.NTFY))                     // no topic: incomplete
        ksafe.startExtension()
        val ext = KSafeExtension.getInstance()!!
        karoo.system.setRideState(RideState.Recording)
        val title = karoo.app.getString(R.string.provider_warn_ridestart_title)
        ksafe.settleUntil(30.seconds) { karoo.system.effectsOf<InRideAlert>().firstOrNull { it.title == title } }
        assertEquals("gap:EMERGENCY", pacingGap(ext))
    }

    @Test
    fun `a check-in pre-warning opens the pacing gap`() {
        ksafe.seed(KSafeConfig(updateCheckEnabled = false, pacingEnabled = true, activeProvider = ProviderType.NTFY,
            checkinEnabled = true, checkinIntervalMinutes = 10), ntfy)
        ksafe.startExtension()
        val ext = KSafeExtension.getInstance()!!
        karoo.system.setRideState(RideState.Recording)
        ksafe.settleUntil(6.minutes) { karoo.system.effectsOf<InRideAlert>().firstOrNull { it.id.startsWith("ksafe-checkin-warn-") } }
        assertEquals("gap:EMERGENCY", pacingGap(ext))
    }

    @Test
    fun `a delivery-failed notice on the ride screen opens the pacing gap`() {
        ksafe.seed(KSafeConfig(updateCheckEnabled = false, pacingEnabled = true,
            activeProvider = ProviderType.NTFY, countdownSeconds = 5), ntfy)
        karoo.system.setRideState(RideState.Recording)
        karoo.system.setLocation(60.17, 24.94)
        karoo.system.responder = HttpResponses.status(503)
        ksafe.startExtension()
        val ext = KSafeExtension.getInstance()!!
        ksafe.tap(FieldTapReceiver.ACTION_SOS)
        val title = karoo.app.getString(R.string.alert_delivery_failed_title)
        ksafe.settleUntil(40.minutes) { karoo.system.effectsOf<InRideAlert>().firstOrNull { it.title == title } }
        // The ALERTING -> IDLE gap is ~30 min old by now: only the notice itself can hold it.
        assertEquals("gap:EMERGENCY", pacingGap(ext))
    }
}
