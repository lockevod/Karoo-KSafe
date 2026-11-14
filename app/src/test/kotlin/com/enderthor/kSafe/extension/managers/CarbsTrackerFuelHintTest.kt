package com.enderthor.kSafe.extension.managers

import android.content.Context
import com.enderthor.kSafe.data.KSafeConfig
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock

/** A shown pacing fuel hint replaces the carb deficit reminder: its cooldown restarts from the hint. */
@OptIn(ExperimentalCoroutinesApi::class)
class CarbsTrackerFuelHintTest {
    private fun tracker() = CarbsTracker(
        scope = TestScope(StandardTestDispatcher()),   // never advanced: no ticks run
        karooSystem = mock(KarooSystemService::class.java),
        context = mock(Context::class.java),
        onFuelingAlert = {},
        isEmergencyActive = { false },
    )

    @Test fun `a shown hint stamps the deficit cooldown`() {
        val t = tracker(); t.start(KSafeConfig(carbsTrackerEnabled = true))
        val now = System.currentTimeMillis() + 1_000
        t.onFuelHintShown(now)
        assertEquals(now, t.getPersistableState().lastDeficitAlertFireMs)
        t.onFuelHintShown(now - 5_000)   // never moves the cooldown backwards
        assertEquals(now, t.getPersistableState().lastDeficitAlertFireMs)
    }

    @Test fun `no stamp when the tracker is not running or the deficit alert is off`() {
        val idle = tracker()
        idle.onFuelHintShown(System.currentTimeMillis())
        assertEquals(0L, idle.getPersistableState().lastDeficitAlertFireMs)
        val off = tracker(); off.start(KSafeConfig(carbsTrackerEnabled = true, carbDeficitAlertEnabled = false))
        off.onFuelHintShown(System.currentTimeMillis())
        assertEquals(0L, off.getPersistableState().lastDeficitAlertFireMs)
    }
}
