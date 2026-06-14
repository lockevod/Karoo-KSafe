package com.enderthor.kSafe.datatype

import com.enderthor.kSafe.data.WEBHOOK_SLOT_COUNT
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Shared in-memory state for the Webhook data fields (slots 1–4).
 * Carries an optional [message] shown as the field hint in ERROR/FIRING/SUCCESS states.
 */
data class WebhookStateData(
    val state: WebhookState,
    val message: String = "",
)

enum class WebhookState {
    IDLE,      // ready — configured colour, "tap"
    FIRING,    // HTTP request in progress — orange
    SUCCESS,   // fired OK — green, resets to IDLE after a few seconds
    ERROR;     // failed — red, "tap=retry"

    companion object {
        private const val SLOT_COUNT = WEBHOOK_SLOT_COUNT
        private val flows = Array(SLOT_COUNT) { MutableStateFlow(WebhookStateData(IDLE)) }

        fun flowForSlot(slot: Int): StateFlow<WebhookStateData> =
            flows[(slot - 1).coerceIn(0, SLOT_COUNT - 1)]

        fun update(slot: Int, state: WebhookState, message: String = "") {
            val idx = (slot - 1).coerceIn(0, SLOT_COUNT - 1)
            flows[idx].value = WebhookStateData(state, message)
        }
    }
}
