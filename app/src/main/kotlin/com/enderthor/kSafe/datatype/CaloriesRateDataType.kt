package com.enderthor.kSafe.datatype

import com.enderthor.kSafe.extension.KSafeExtension
import com.enderthor.kSafe.extension.util.CalorieSource
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.StreamState

/**
 * Instantaneous HR-based energy expenditure (kcal/h).
 *
 * Non-graphical field (`graphical="false"`): the Karoo host renders and auto-sizes the
 * number natively (KPower approach — never overflows); the field NAME is the label.
 * Searching when no model can fire; 0 while the movement gate blocks integration (the
 * rider is not accruing, and a frozen last rate would lie); else the live kcal/h.
 * Push-based via `statusFlow`.
 */
class CaloriesRateDataType(
    datatype: String,
) : DataTypeImpl("ksafe", datatype) {

    override fun startStream(emitter: Emitter<StreamState>) =
        startFuelingStream(emitter, KSafeExtension.carbsTrackerFlow, { it.statusFlow }) { s ->
            when {
                !s.masterEnabled || !s.caloriesEnabled -> StreamState.NotAvailable
                s.calorieSource == CalorieSource.NONE -> StreamState.Searching
                !s.isIntegrating -> streamingSingle(0)
                else -> streamingSingle(s.kcalPerHour)
            }
        }
}
