package com.enderthor.kSafe.extension.util

import com.enderthor.kSafe.data.EmergencyState
import com.enderthor.kSafe.data.EmergencyStatus

sealed class EmergencyResume {
    data object Nothing : EmergencyResume()
    data class Active(val remainingMs: Long) : EmergencyResume()
    data object AfterDeadline : EmergencyResume()
    data class DiscardStale(val ageMs: Long) : EmergencyResume()
}

private const val STALE_THRESHOLD_MS: Long = 24L * 60L * 60L * 1_000L

fun decideResume(state: EmergencyState, nowMs: Long): EmergencyResume {
    val deadline = state.countdownDeadlineMs()
    return when {
        state.status != EmergencyStatus.COUNTDOWN -> EmergencyResume.Nothing
        state.reasonEnum == null                  -> EmergencyResume.Nothing   // pre-migration state
        deadline == 0L                            -> EmergencyResume.Nothing
        nowMs < deadline                          -> EmergencyResume.Active(deadline - nowMs)
        nowMs - deadline > STALE_THRESHOLD_MS     -> EmergencyResume.DiscardStale(nowMs - deadline)
        else                                      -> EmergencyResume.AfterDeadline
    }
}
