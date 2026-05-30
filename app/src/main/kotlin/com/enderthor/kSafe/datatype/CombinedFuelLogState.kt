package com.enderthor.kSafe.datatype

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/**
 * Per-slot tap-feedback state for the Combined fuel-log fields (slots 1, 2). Carries BOTH the
 * ml and grams actually logged (either may be 0 if that tracker was off), so the LOGGED/UNDONE
 * flash shows exactly what happened and the undo reverses exactly those amounts even if the
 * rider edited the button's config in between. Mirrors [HydrationLogState].
 */
sealed class CombinedFuelLogState {
    object IDLE : CombinedFuelLogState()
    data class LOGGED(val ml: Int, val grams: Int) : CombinedFuelLogState()
    data class UNDONE(val ml: Int, val grams: Int) : CombinedFuelLogState()

    companion object {
        private val _flow1 = MutableStateFlow<CombinedFuelLogState>(IDLE)
        private val _flow2 = MutableStateFlow<CombinedFuelLogState>(IDLE)

        fun update(slot: Int, state: CombinedFuelLogState) {
            when (slot) {
                1 -> _flow1.value = state
                2 -> _flow2.value = state
                else -> Timber.w("CombinedFuelLogState.update: invalid slot=$slot (state=$state)")
            }
        }

        fun flowForSlot(slot: Int): StateFlow<CombinedFuelLogState> = when (slot) {
            2 -> _flow2
            else -> _flow1
        }
    }
}
