package com.enderthor.kSafe.datatype

import com.enderthor.kSafe.R
import com.enderthor.kSafe.extension.pacing.PacingState
import com.enderthor.kSafe.extension.pacing.PacingView
import org.junit.Assert.assertEquals
import org.junit.Test

class ReserveFrameTest {
    private fun f(preview: Boolean = false, enabled: Boolean? = true, view: PacingView?) =
        reserveFrame(preview, enabled, view, "MARGIN", "reserve")

    private fun v(state: PacingState = PacingState.KEEP, recoverW: Boolean = false, wPct: Double? = null,
                  trusted: Boolean = true, km: Double? = null, hasPower: Boolean = true, hrSource: Boolean = false) =
        PacingView(state, recoverW, wPct, trusted, km, hasPower, hrSource)

    private val waiting = ReserveFrame(R.color.pacing_blue, "---", "reserve")

    @Test fun previewAndUnseededAndNullAndUntrustedAreWaiting() {
        assertEquals(waiting, f(preview = true, enabled = false, view = v(PacingState.CONSERVE)))
        assertEquals(waiting, f(enabled = null, view = v()))
        assertEquals(waiting, f(view = null))
        assertEquals(waiting, f(view = v(trusted = false)))
        assertEquals(waiting, f(view = v(hasPower = false)))                       // no power: unknown, not OK
        assertEquals(waiting, f(view = v(PacingState.CONSERVE, hasPower = false)))
    }

    @Test fun switchOffIsGreyOffEvenWithShadowView() {
        assertEquals(ReserveFrame(null, "OFF", "reserve"), f(enabled = false, view = v(PacingState.CONSERVE)))
        assertEquals(ReserveFrame(null, "OFF", "reserve"), f(enabled = false, view = null))
    }

    @Test fun statesColoursAndHints() {
        assertEquals(ReserveFrame(R.color.pacing_amber, "CONS", "W′42"), f(view = v(PacingState.CONSERVE, wPct = 0.42)))
        assertEquals(ReserveFrame(R.color.pacing_green, "MARGIN", "3km"), f(view = v(PacingState.HIGH_MARGIN, km = 2.6)))
        assertEquals(ReserveFrame(R.color.pacing_blue, "OK", ""), f(view = v()))
        assertEquals(ReserveFrame(R.color.pacing_amber, "W′!", "W′5"), f(view = v(PacingState.CONSERVE, recoverW = true, wPct = 0.05, km = 4.0)))
    }

    @Test fun heartRateSourcedStatesShowWithoutPower() {
        assertEquals(ReserveFrame(R.color.pacing_amber, "CONS", "4km"), f(view = v(PacingState.CONSERVE, km = 4.0, hasPower = false, hrSource = true)))
        assertEquals(ReserveFrame(R.color.pacing_blue, "OK", ""), f(view = v(hasPower = false, hrSource = true)))
        assertEquals(waiting, f(view = v(hasPower = false, hrSource = true, trusted = false)))
    }
}
