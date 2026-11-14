package com.enderthor.kSafe.fake

import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.extension.pacing.TestPolyline
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.RideState
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
}
