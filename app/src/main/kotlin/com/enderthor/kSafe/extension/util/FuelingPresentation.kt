package com.enderthor.kSafe.extension.util

import com.enderthor.kSafe.data.FuelingAlertButtonMode
import io.hammerhead.karooext.models.InRideAlert

enum class FuelingPresentation { OVERLAY_LOG, OVERLAY_LOG_UNDO, INRIDE_ALERT }

/**
 * One fueling alert, built by a tracker. The closures capture the tracker + selected slot,
 * so the presenter (KSafeExtension) never needs a tracker reference.
 */
data class FuelingAlertRequest(
    val title: String,
    val detail: String,
    val inRideAlert: InRideAlert,
    val onLog: () -> Unit,
    val onUndo: () -> Unit,
)

/** Pure decision: overlay (and which mode) vs the legacy InRideAlert. */
fun decideFuelingPresentation(
    mode: FuelingAlertButtonMode,
    canDrawOverlays: Boolean,
    emergencyIdle: Boolean,
): FuelingPresentation {
    if (!canDrawOverlays || !emergencyIdle) return FuelingPresentation.INRIDE_ALERT
    return when (mode) {
        FuelingAlertButtonMode.OFF -> FuelingPresentation.INRIDE_ALERT
        FuelingAlertButtonMode.LOG -> FuelingPresentation.OVERLAY_LOG
        FuelingAlertButtonMode.LOG_UNDO -> FuelingPresentation.OVERLAY_LOG_UNDO
    }
}
