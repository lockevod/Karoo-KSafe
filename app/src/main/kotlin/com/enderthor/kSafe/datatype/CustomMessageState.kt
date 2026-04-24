package com.enderthor.kSafe.datatype

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Shared in-memory state for the Custom Message data fields (slots 1–3).
 * The extension updates these; the DataType observes them to render the field.
 */
enum class CustomMessageState {
    IDLE,    // ready — blue, "tap=send"
    SENDING, // in progress — orange
    SENT,    // success — green, resets to IDLE after a few seconds
    ERROR;   // failed — red, "tap=retry"

    companion object {
        private val _flow1 = MutableStateFlow(IDLE)
        private val _flow2 = MutableStateFlow(IDLE)
        private val _flow3 = MutableStateFlow(IDLE)

        val flow: StateFlow<CustomMessageState> get() = _flow1
        val flow1: StateFlow<CustomMessageState> get() = _flow1
        val flow2: StateFlow<CustomMessageState> get() = _flow2
        val flow3: StateFlow<CustomMessageState> get() = _flow3

        fun update(state: CustomMessageState) { _flow1.value = state }
        fun update(slot: Int, state: CustomMessageState) {
            when (slot) {
                1 -> _flow1.value = state
                2 -> _flow2.value = state
                3 -> _flow3.value = state
            }
        }

        fun flowForSlot(slot: Int): StateFlow<CustomMessageState> = when (slot) {
            2 -> _flow2
            3 -> _flow3
            else -> _flow1
        }
    }
}

