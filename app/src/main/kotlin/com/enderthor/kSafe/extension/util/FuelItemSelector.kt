package com.enderthor.kSafe.extension.util

/** A fueling slot considered for a suggestion. [size] is grams (carbs) or ml (hydration). */
data class FuelSlot(val slot: Int, val label: String, val size: Int)

/**
 * Picks the best-fit item to suggest. Pool = the channel's slots with size > 0.
 * - Numeric [deficit]: slot minimising |size − deficit|, tiebreak lowest slot index.
 * - Null [deficit] (time-based alert): first usable slot.
 * - No usable slot: null.
 */
fun pickFuelItem(deficit: Int?, slots: List<FuelSlot>): FuelSlot? {
    val usable = slots.filter { it.size > 0 }
    if (usable.isEmpty()) return null
    if (deficit == null) return usable.minByOrNull { it.slot }
    return usable.sortedBy { it.slot }.minByOrNull { kotlin.math.abs(it.size - deficit) }
}
