package com.enderthor.kSafe.datatype

import com.enderthor.kSafe.extension.KSafeExtension
import com.enderthor.kSafe.extension.util.CarbBurnEstimator
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.StreamState

/**
 * Instantaneous carb burn rate in g/h from the physiological burn estimator (v18+).
 *
 * Non-graphical field (`graphical="false"` in `extension_info.xml`): the Karoo host
 * renders and auto-sizes the number natively — the same approach KPower uses so the
 * value never overflows the cell. The field NAME (host header) is the label; there is
 * no custom view. Stream-state semantics are mapped in [startFuelingStream]:
 *  - NotAvailable (host shows `---`) when master / carbs off.
 *  - Searching when no sensor can drive any tier of [CarbBurnEstimator].
 *  - 0 while the movement gate blocks integration (not accruing).
 *  - the live g/h rate otherwise.
 *
 * Push-based — driven by `statusFlow` from
 * [com.enderthor.kSafe.extension.managers.CarbsTracker].
 */
class CarbBurnRateDataType(
    datatype: String,
) : DataTypeImpl("ksafe", datatype) {

    override fun startStream(emitter: Emitter<StreamState>) =
        startFuelingStream(emitter, KSafeExtension.carbsTrackerFlow, { it.statusFlow }) { s ->
            when {
                !s.masterEnabled || !s.carbsEnabled -> StreamState.NotAvailable
                s.burnConfidence == CarbBurnEstimator.Confidence.NONE -> StreamState.Searching
                !s.isIntegrating -> streamingSingle(0)
                else -> streamingSingle(s.burnRateGph)
            }
        }
}
