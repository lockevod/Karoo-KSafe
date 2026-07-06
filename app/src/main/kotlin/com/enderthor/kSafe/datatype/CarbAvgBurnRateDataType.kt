package com.enderthor.kSafe.datatype

import com.enderthor.kSafe.extension.KSafeExtension
import com.enderthor.kSafe.extension.util.CarbBurnEstimator
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.StreamState

/**
 * Session-average carb burn rate in g/h, averaged over the time the tracker has been
 * actively integrating this session (café / traffic-light stops excluded). Companion
 * to [CarbBurnRateDataType] (instantaneous).
 *
 * Non-graphical field (`graphical="false"`): the Karoo host renders and auto-sizes the
 * number natively (KPower approach — never overflows); the field NAME is the label.
 * Stream-state semantics map in [startFuelingStream]:
 *  - NotAvailable (host `---`) when master / carbs off.
 *  - Searching while the estimator has no usable inputs and nothing has accumulated.
 *  - the average g/h otherwise (0 until the first tick of integration).
 *
 * Push-based via `statusFlow`.
 */
class CarbAvgBurnRateDataType(
    datatype: String,
) : DataTypeImpl("ksafe", datatype) {

    override fun startStream(emitter: Emitter<StreamState>) =
        startFuelingStream(emitter, KSafeExtension.carbsTrackerFlow, { it.statusFlow }) { s ->
            when {
                !s.masterEnabled || !s.carbsEnabled -> StreamState.NotAvailable
                s.burnConfidence == CarbBurnEstimator.Confidence.NONE && s.cumBurnedG == 0 ->
                    StreamState.Searching
                else -> streamingSingle(s.avgBurnRateGph)
            }
        }
}
