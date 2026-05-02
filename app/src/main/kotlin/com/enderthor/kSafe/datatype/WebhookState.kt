package com.enderthor.kSafe.datatype

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Shared in-memory state for the Webhook data fields (slots 1–2).
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
        private val _flow1 = MutableStateFlow(WebhookStateData(IDLE))
        private val _flow2 = MutableStateFlow(WebhookStateData(IDLE))

        fun flowForSlot(slot: Int): StateFlow<WebhookStateData> = if (slot == 2) _flow2 else _flow1

        fun update(slot: Int, state: WebhookState, message: String = "") {
            val data = WebhookStateData(state, message)
            if (slot == 2) _flow2.value = data else _flow1.value = data
        }
    }
}
