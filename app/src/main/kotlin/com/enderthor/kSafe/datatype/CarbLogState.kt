package com.enderthor.kSafe.datatype

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/**
 * Per-slot tap-feedback state for the Carb Log data fields (slots 1, 2, 3).
 *
 * `LOGGED` and `UNDONE` carry the actual grams that were added / reversed so the
 * data field can display the exact amount even if the rider edits the slot's
 * grams config between the log tap and the undo tap. Reading the live config
 * here would otherwise show a "−30g" flash for an entry that actually added 25.
 *
 * Mirrors [HydrationLogState]. The CarbLogDataType collects [flowForSlot] to render
 * the field; KSafeExtension transitions the state to LOGGED → IDLE on a tap.
 */
sealed class CarbLogState {
    /** Ready — shows the configured label and per-tap grams. */
    object IDLE : CarbLogState()
    /** Green "+Xg" confirmation; field remains tappable for the 8 s undo window.
     *  [grams] is the amount actually added (frozen at log time). */
    data class LOGGED(val grams: Int) : CarbLogState()
    /** Red "−Xg ✓" confirmation after a successful undo; tappable to re-log.
     *  [grams] is the amount actually reversed. */
    data class UNDONE(val grams: Int) : CarbLogState()

    companion object {
        private val _flow1 = MutableStateFlow<CarbLogState>(IDLE)
        private val _flow2 = MutableStateFlow<CarbLogState>(IDLE)
        private val _flow3 = MutableStateFlow<CarbLogState>(IDLE)

        fun update(slot: Int, state: CarbLogState) {
            when (slot) {
                1 -> _flow1.value = state
                2 -> _flow2.value = state
                3 -> _flow3.value = state
                else -> Timber.w("CarbLogState.update: invalid slot=$slot (state=$state)")
            }
        }

        fun flowForSlot(slot: Int): StateFlow<CarbLogState> = when (slot) {
            2 -> _flow2
            3 -> _flow3
            else -> _flow1
        }
    }
}
