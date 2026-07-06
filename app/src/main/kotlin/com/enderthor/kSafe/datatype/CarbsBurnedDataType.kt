package com.enderthor.kSafe.datatype

import com.enderthor.kSafe.extension.KSafeExtension
import com.enderthor.kSafe.extension.util.CarbBurnEstimator
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.StreamState

/**
 * Cumulative carbs burned this session in grams — the integrated zone-aware target.
 * Companion to [CarbBurnRateDataType] (instantaneous) and [CarbStatusDataType] (deficit).
 *
 * Non-graphical field (`graphical="false"`): the Karoo host renders and auto-sizes the
 * number natively (KPower approach — never overflows); the field NAME is the label.
 * Searching only while nothing has ever been measured; the accrued total keeps
 * streaming (frozen, which is honest) through sensor dropouts. Push-based via `statusFlow`.
 */
class CarbsBurnedDataType(
    datatype: String,
) : DataTypeImpl("ksafe", datatype) {

    override fun startStream(emitter: Emitter<StreamState>) =
        startFuelingStream(emitter, KSafeExtension.carbsTrackerFlow, { it.statusFlow }) { s ->
            when {
                !s.masterEnabled || !s.carbsEnabled -> StreamState.NotAvailable
                s.burnConfidence == CarbBurnEstimator.Confidence.NONE && s.cumBurnedG == 0 ->
                    StreamState.Searching
                else -> streamingSingle(s.cumBurnedG)
            }
        }
}
