package com.enderthor.kSafe.extension.util

import com.enderthor.kSafe.data.FuelingAlertButtonMode
import io.hammerhead.karooext.models.InRideAlert

enum class FuelingPresentation { OVERLAY_LOG, OVERLAY_LOG_UNDO, INRIDE_ALERT, SUPPRESS }

/** Which tracker an alert belongs to — lets the presenter route the LOG/UNDO action to the
 *  right tracker AND through the matching on-ride field-state machine. */
enum class FuelingChannel { CARB, HYDRATION }

/**
 * One fueling alert, built by a tracker. Carries the [channel] + suggested [item] (rather than
 * pre-bound log/undo closures) so the presenter (KSafeExtension) — which OWNS the on-ride
 * CarbLog/HydrationLog field-state machine — performs the log/undo itself and keeps that field
 * in sync with the tracker's accounting, exactly as a field tap does.
 */
data class FuelingAlertRequest(
    val title: String,
    val detail: String,
    /** Builds the legacy InRideAlert. A factory (not a built object) so it is only materialised
     *  on the INRIDE_ALERT branch — the overlay and SUPPRESS paths never pay for it. */
    val inRideAlert: () -> InRideAlert,
    val channel: FuelingChannel,
    /** The item the alert suggests logging (slot index + label + size), or null when no slot is
     *  usable (all sizes 0) — then no LOG button is shown and the alert is a plain InRideAlert.
     *  Carrying label+size (not a bare slot index) lets the presenter format the prompt without
     *  re-deriving them from config, and freezes the shown amount at fire time. */
    val item: FuelSlot?,
)

/** Pure decision: suppress entirely (active emergency), overlay (and which mode), or the
 *  legacy InRideAlert. */
fun decideFuelingPresentation(
    mode: FuelingAlertButtonMode,
    canDrawOverlays: Boolean,
    emergencyIdle: Boolean,
    hasUsableSlot: Boolean,
): FuelingPresentation {
    // An active emergency owns the screen — a fueling alert must add NOTHING (no overlay,
    // no InRideAlert) so it can never compete with the SOS countdown/alert on any surface.
    if (!emergencyIdle) return FuelingPresentation.SUPPRESS
    // No overlay permission, or nothing concrete to log (no usable slot) → no LOG button:
    // a button wired to a non-existent item would log a phantom 0 g/ml entry.
    if (!canDrawOverlays || !hasUsableSlot) return FuelingPresentation.INRIDE_ALERT
    return when (mode) {
        FuelingAlertButtonMode.OFF -> FuelingPresentation.INRIDE_ALERT
        FuelingAlertButtonMode.LOG -> FuelingPresentation.OVERLAY_LOG
        FuelingAlertButtonMode.LOG_UNDO -> FuelingPresentation.OVERLAY_LOG_UNDO
    }
}
