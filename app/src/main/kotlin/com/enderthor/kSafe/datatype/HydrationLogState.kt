package com.enderthor.kSafe.datatype

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/**
 * Per-slot tap-feedback state for the Hydration Log data fields (slots 1, 2).
 * Mirrors [CarbLogState] — see that file for the design rationale (carrying the
 * actual amount with the state so the UNDO flash matches the entry being reversed
 * even when the rider edited the slot's ml config in between).
 */
sealed class HydrationLogState {
    object IDLE : HydrationLogState()
    data class LOGGED(val ml: Int) : HydrationLogState()
    data class UNDONE(val ml: Int) : HydrationLogState()

    companion object {
        private val _flow1 = MutableStateFlow<HydrationLogState>(IDLE)
        private val _flow2 = MutableStateFlow<HydrationLogState>(IDLE)

        fun update(slot: Int, state: HydrationLogState) {
            when (slot) {
                1 -> _flow1.value = state
                2 -> _flow2.value = state
                else -> Timber.w("HydrationLogState.update: invalid slot=$slot (state=$state)")
            }
        }

        fun flowForSlot(slot: Int): StateFlow<HydrationLogState> = when (slot) {
            2 -> _flow2
            else -> _flow1
        }
    }
}
