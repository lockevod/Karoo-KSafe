package com.enderthor.kSafe.extension.managers

import com.enderthor.kSafe.data.EmergencyReason
import com.enderthor.kSafe.data.EmergencyState
import com.enderthor.kSafe.data.EmergencyStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyResumeDecisionTest {

    private fun countdown(
        startMs: Long = 0L,
        durationS: Int = 30,
        reasonEnum: EmergencyReason? = EmergencyReason.CRASH_DETECTED,
    ) = EmergencyState(
        status = EmergencyStatus.COUNTDOWN,
        reason = "test",
        reasonEnum = reasonEnum,
        countdownStartTime = startMs,
        countdownDurationSeconds = durationS,
    )

    @Test
    fun `idle state returns Nothing`() {
        val s = EmergencyState()
        assertEquals(EmergencyResume.Nothing, decideResume(s, nowMs = 1_000L))
    }

    @Test
    fun `countdown active, now before deadline returns Active with remaining`() {
        val s = countdown(startMs = 1_000_000L, durationS = 30)
        val now = 1_010_000L            // 10s into the 30s countdown
        val r = decideResume(s, nowMs = now)
        assertTrue(r is EmergencyResume.Active)
        assertEquals(20_000L, (r as EmergencyResume.Active).remainingMs)
    }

    @Test
    fun `countdown deadline passed less than 24h returns AfterDeadline`() {
        val s = countdown(startMs = 1_000_000L, durationS = 30)
        val now = 1_000_000L + 30_000L + 60_000L  // 1 minute past deadline
        assertEquals(EmergencyResume.AfterDeadline, decideResume(s, nowMs = now))
    }

    @Test
    fun `countdown deadline more than 24h ago returns DiscardStale`() {
        val s = countdown(startMs = 1_000_000L, durationS = 30)
        val now = 1_000_000L + 30_000L + (25L * 3_600_000L)  // 25h past deadline
        val r = decideResume(s, nowMs = now)
        assertTrue(r is EmergencyResume.DiscardStale)
    }

    @Test
    fun `countdown without reasonEnum returns Nothing (legacy state)`() {
        val s = countdown(startMs = 1_000_000L, reasonEnum = null)
        assertEquals(EmergencyResume.Nothing, decideResume(s, nowMs = 1_010_000L))
    }

    @Test
    fun `countdown with status COUNTDOWN but zero startTime returns Nothing`() {
        val s = countdown(startMs = 0L)
        assertEquals(EmergencyResume.Nothing, decideResume(s, nowMs = 1_010_000L))
    }
}
