package com.enderthor.kSafe.datatype

import com.enderthor.kSafe.extension.KSafeExtension
import com.enderthor.kSafe.extension.util.CalorieSource
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.StreamState

/**
 * Cumulative HR-based calories this session (kcal). An estimate from HR (Keytel / Swain
 * / %HRmax), independent of and shown alongside the Karoo's power-based calories.
 *
 * Non-graphical field (`graphical="false"`): the Karoo host renders and auto-sizes the
 * number natively (KPower approach — never overflows); the field NAME is the label.
 * Searching while no source has produced a value; the accrued total then keeps streaming
 * (frozen through dropouts, which is honest). Push-based via `statusFlow`.
 */
class CaloriesTotalDataType(
    datatype: String,
) : DataTypeImpl("ksafe", datatype) {

    override fun startStream(emitter: Emitter<StreamState>) =
        startFuelingStream(emitter, KSafeExtension.carbsTrackerFlow, { it.statusFlow }) { s ->
            when {
                !s.masterEnabled || !s.caloriesEnabled -> StreamState.NotAvailable
                s.calorieSource == CalorieSource.NONE && s.kcalTotal == 0 -> StreamState.Searching
                else -> streamingSingle(s.kcalTotal)
            }
        }
}
