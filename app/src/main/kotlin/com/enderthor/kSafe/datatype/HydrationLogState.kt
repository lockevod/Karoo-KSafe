package com.enderthor.kSafe.datatype

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Per-slot tap-feedback state for the Hydration Log data fields (slots 1, 2).
 * Same pattern as [CarbLogState].
 */
enum class HydrationLogState {
    IDLE,
    LOGGED,   // green "+Xml" confirmation; field remains tappable for ~5 s as an undo window
    UNDONE;   // red "−Xml" confirmation after a successful undo, auto-resets to IDLE

    companion object {
        private val _flow1 = MutableStateFlow(IDLE)
        private val _flow2 = MutableStateFlow(IDLE)

        fun update(slot: Int, state: HydrationLogState) {
            when (slot) {
                1 -> _flow1.value = state
                2 -> _flow2.value = state
            }
        }

        fun flowForSlot(slot: Int): StateFlow<HydrationLogState> = when (slot) {
            2 -> _flow2
            else -> _flow1
        }
    }
}
