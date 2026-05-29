package com.enderthor.kSafe.extension.util

import com.enderthor.kSafe.data.EmergencyState
import com.enderthor.kSafe.data.EmergencyStatus

sealed class EmergencyResume {
    data object Nothing : EmergencyResume()
    data class Active(val remainingMs: Long) : EmergencyResume()
    data object AfterDeadline : EmergencyResume()
    data class DiscardStale(val ageMs: Long) : EmergencyResume()
    /**
     * H9 — Process was killed while the visible state had rolled back to IDLE but the
     * alertJob was still retrying in the background. The persisted state is ALERTING;
     * the in-flight retry is gone (it lived in the killed process); we must clear the
     * stale state so the next ride starts in IDLE. Distinct from [DiscardStale] which
     * tracks a STALE COUNTDOWN past its deadline.
     */
    data object DiscardAlerting : EmergencyResume()
}

private const val STALE_THRESHOLD_MS: Long = 24L * 60L * 60L * 1_000L

fun decideResume(state: EmergencyState, nowMs: Long): EmergencyResume {
    val deadline = state.countdownDeadlineMs()
    return when {
        // H9 — persisted ALERTING with no live alertJob means a previous process
        // was killed mid-dispatch (low-memory killer, force-stop). Surface it
        // explicitly so KSafeExtension can clear DataStore on resume rather than
        // leaving phantom state hanging across rides.
        state.status == EmergencyStatus.ALERTING  -> EmergencyResume.DiscardAlerting
        state.status != EmergencyStatus.COUNTDOWN -> EmergencyResume.Nothing
        // K7 — pre-v8 persisted COUNTDOWN with no reasonEnum is unsafe to resume
        // (we can't tell the rider why their countdown re-armed). Previously this
        // returned Nothing → KSafeExtension's no-op branch left the stale record
        // in DataStore forever. Treat it like an OLD persisted state: route to
        // DiscardStale so KSafeExtension clears it. Use a sentinel ageMs of 0
        // since we genuinely don't know how old the record is.
        state.reasonEnum == null                  -> EmergencyResume.DiscardStale(0L)
        deadline == 0L                            -> EmergencyResume.Nothing
        nowMs < deadline                          -> EmergencyResume.Active(deadline - nowMs)
        nowMs - deadline > STALE_THRESHOLD_MS     -> EmergencyResume.DiscardStale(nowMs - deadline)
        else                                      -> EmergencyResume.AfterDeadline
    }
}
