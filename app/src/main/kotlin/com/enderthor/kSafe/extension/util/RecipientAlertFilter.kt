package com.enderthor.kSafe.extension.util

import com.enderthor.kSafe.data.RecipientAlertScope
import com.enderthor.kSafe.data.SenderConfig

/** True if a recipient with this scope should receive a message of the given category. */
fun RecipientAlertScope.accepts(isEmergency: Boolean): Boolean = when (this) {
    RecipientAlertScope.ALL            -> true
    RecipientAlertScope.EMERGENCY_ONLY -> isEmergency
    RecipientAlertScope.INFO_ONLY      -> !isEmergency
}

/** Scope for recipient index [i] (0=recipient1, 1=recipient2, 2=recipient3).
 *  Any out-of-range index clamps to recipient3 (index 2). */
fun SenderConfig.scopeForSlot(i: Int): RecipientAlertScope = when (i) {
    0 -> recipient1Alerts
    1 -> recipient2Alerts
    else -> recipient3Alerts
}

/**
 * The recipient slot indices a send should be delivered to. Applies the per-recipient
 * scope filter; for an emergency, falls back to ALL [configuredSlots] when the filter
 * would leave zero recipients (KSafe's safety invariant — an emergency always reaches a
 * contact). Info sends have no fallback (they may legitimately reach nobody).
 */
fun recipientsToSend(
    configuredSlots: List<Int>,
    scopeForSlot: (Int) -> RecipientAlertScope,
    isEmergency: Boolean,
): List<Int> {
    val filtered = configuredSlots.filter { scopeForSlot(it).accepts(isEmergency) }
    return if (isEmergency && filtered.isEmpty()) configuredSlots else filtered
}
