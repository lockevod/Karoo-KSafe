package com.enderthor.kSafe.datatype

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Shared in-memory state for the Custom Message data field.
 * The extension updates this; the DataType observes it to render the field.
 */
enum class CustomMessageState {
    IDLE,    // ready — blue, "tap=send"
    SENDING, // in progress — orange
    SENT,    // success — green, resets to IDLE after a few seconds
    ERROR;   // failed — red, "tap=retry"

    companion object {
        private val _flow = MutableStateFlow(IDLE)
        val flow: StateFlow<CustomMessageState> get() = _flow

        fun update(state: CustomMessageState) { _flow.value = state }
    }
}

