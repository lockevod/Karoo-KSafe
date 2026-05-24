package com.enderthor.kSafe.extension.util

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
    fun `countdown without reasonEnum returns DiscardStale so KSafeExtension clears phantom legacy state`() {
        // K7 — pre-v8 persisted COUNTDOWN with no reasonEnum is unsafe to resume
        // (we don't know what kind of emergency the rider was in). Previously this
        // returned Nothing and the no-op branch left the stale record in DataStore
        // forever — observable in any backup/inspector and a potential time-bomb
        // if a future decideResume branch depended on cleaned state. Route to
        // DiscardStale so the caller wipes the record.
        val s = countdown(startMs = 1_000_000L, reasonEnum = null)
        val r = decideResume(s, nowMs = 1_010_000L)
        assertTrue("expected DiscardStale, got $r", r is EmergencyResume.DiscardStale)
    }

    @Test
    fun `countdown with status COUNTDOWN but zero startTime returns Nothing`() {
        val s = countdown(startMs = 0L)
        assertEquals(EmergencyResume.Nothing, decideResume(s, nowMs = 1_010_000L))
    }

    @Test
    fun `nowMs exactly equal to deadline falls into AfterDeadline branch`() {
        // Boundary check: decideResume uses `nowMs < deadline` (strict) for Active, so the
        // exact-deadline tick must take the else branch (AfterDeadline) — not Active and
        // not DiscardStale. If the implementation flipped to `<=` this test would catch it.
        val startMs = 1_000_000L
        val durationS = 30
        val s = countdown(startMs = startMs, durationS = durationS)
        val deadlineMs = startMs + durationS * 1_000L
        assertEquals(EmergencyResume.AfterDeadline, decideResume(s, nowMs = deadlineMs))
    }

    @Test
    fun `nowMs one millisecond before deadline returns Active with 1ms remaining`() {
        // Companion to the boundary test above: confirms the strict `<` is honoured on the
        // Active side as well. A subtle off-by-one (e.g. `nowMs <= deadline`) would still
        // pass the AfterDeadline test alone, so we need both sides asserted.
        val startMs = 1_000_000L
        val durationS = 30
        val s = countdown(startMs = startMs, durationS = durationS)
        val nowMs = startMs + durationS * 1_000L - 1L
        val r = decideResume(s, nowMs = nowMs)
        assertTrue("expected Active near boundary, got $r", r is EmergencyResume.Active)
        assertEquals(1L, (r as EmergencyResume.Active).remainingMs)
    }

    @Test
    fun `persisted ALERTING state returns DiscardAlerting so KSafeExtension clears the orphan`() {
        // H9 regression guard. A previous process was killed (low-memory killer, force-stop,
        // OS crash) while in the post-countdown ALERTING window — sendAlerts had moved
        // currentStatus to ALERTING and persisted that state, but the alertJob died with
        // the process and no longer exists. decideResume must surface this distinctly so
        // the caller can clear the orphan state, NOT try to resume a non-existent retry.
        val s = EmergencyState(
            status = EmergencyStatus.ALERTING,
            reason = EmergencyReason.CRASH_DETECTED.label,
            reasonEnum = EmergencyReason.CRASH_DETECTED,
            countdownStartTime =1_000_000L,
            countdownDurationSeconds = 30,
        )
        assertEquals(
            EmergencyResume.DiscardAlerting,
            decideResume(s, nowMs = 1_000_000L + 60_000L),
        )
    }

    @Test
    fun `ALERTING branch wins over COUNTDOWN timestamps so reordering when-chain is detectable`() {
        // The H9 fix places the ALERTING check FIRST in the when-chain. A future
        // refactor that reorders the chain (e.g. COUNTDOWN/Active before ALERTING)
        // would silently change behaviour for any persisted ALERTING state that
        // happens to also have a valid countdownStartTime — this test forces the
        // discriminator to be `status == ALERTING`, not the deadline math.
        val startMs = 1_000_000L
        val s = EmergencyState(
            status = EmergencyStatus.ALERTING,
            reason = EmergencyReason.MANUAL_SOS.label,
            reasonEnum = EmergencyReason.MANUAL_SOS,
            // Timestamps that WOULD route to Active if the status check were skipped:
            countdownStartTime =startMs,
            countdownDurationSeconds = 60,
        )
        // 30 s into the would-be countdown — Active branch would say "30 s left".
        val r = decideResume(s, nowMs = startMs + 30_000L)
        assertEquals(
            "ALERTING must short-circuit before the COUNTDOWN/Active branch",
            EmergencyResume.DiscardAlerting,
            r,
        )
    }
}
