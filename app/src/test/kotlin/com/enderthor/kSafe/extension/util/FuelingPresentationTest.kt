package com.enderthor.kSafe.extension.util

import com.enderthor.kSafe.data.FuelingAlertButtonMode
import org.junit.Assert.assertEquals
import org.junit.Test

class FuelingPresentationTest {
    @Test fun `off mode always uses inride alert`() {
        assertEquals(FuelingPresentation.INRIDE_ALERT,
            decideFuelingPresentation(FuelingAlertButtonMode.OFF, canDrawOverlays = true, emergencyIdle = true))
    }
    @Test fun `no overlay permission falls back to inride alert`() {
        assertEquals(FuelingPresentation.INRIDE_ALERT,
            decideFuelingPresentation(FuelingAlertButtonMode.LOG, canDrawOverlays = false, emergencyIdle = true))
    }
    @Test fun `active emergency suppresses the fueling alert entirely`() {
        // No overlay AND no InRideAlert during an emergency — must not compete with the SOS.
        assertEquals(FuelingPresentation.SUPPRESS,
            decideFuelingPresentation(FuelingAlertButtonMode.LOG_UNDO, canDrawOverlays = true, emergencyIdle = false))
    }
    @Test fun `active emergency suppresses even in OFF mode (no InRideAlert)`() {
        // Emergency takes priority over the OFF-mode InRideAlert fallback too.
        assertEquals(FuelingPresentation.SUPPRESS,
            decideFuelingPresentation(FuelingAlertButtonMode.OFF, canDrawOverlays = false, emergencyIdle = false))
    }
    @Test fun `log mode with permission and idle uses overlay log`() {
        assertEquals(FuelingPresentation.OVERLAY_LOG,
            decideFuelingPresentation(FuelingAlertButtonMode.LOG, canDrawOverlays = true, emergencyIdle = true))
    }
    @Test fun `log_undo mode with permission and idle uses overlay log_undo`() {
        assertEquals(FuelingPresentation.OVERLAY_LOG_UNDO,
            decideFuelingPresentation(FuelingAlertButtonMode.LOG_UNDO, canDrawOverlays = true, emergencyIdle = true))
    }
}
