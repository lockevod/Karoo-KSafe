package com.enderthor.kSafe.datatype

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Per-slot tap-feedback state for the Carb Log data fields (slots 1, 2, 3).
 * Mirrors [CustomMessageState]. The CarbLogDataType collects [flowForSlot] to render the field;
 * KSafeExtension transitions the state to LOGGED → IDLE on a tap.
 */
enum class CarbLogState {
    IDLE,    // ready — shows configured label
    LOGGED;  // briefly green "+Xg" confirmation, auto-resets to IDLE

    companion object {
        private val _flow1 = MutableStateFlow(IDLE)
        private val _flow2 = MutableStateFlow(IDLE)
        private val _flow3 = MutableStateFlow(IDLE)

        fun update(slot: Int, state: CarbLogState) {
            when (slot) {
                1 -> _flow1.value = state
                2 -> _flow2.value = state
                3 -> _flow3.value = state
            }
        }

        fun flowForSlot(slot: Int): StateFlow<CarbLogState> = when (slot) {
            2 -> _flow2
            3 -> _flow3
            else -> _flow1
        }
    }
}
