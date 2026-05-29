package com.enderthor.kSafe.datatype

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import com.enderthor.kSafe.R
import com.enderthor.kSafe.extension.KSafeExtension
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ShowCustomStreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber


/**
 * Cumulative carbs burned this session in grams — i.e. the integrated zone-aware target.
 * Companion to [CarbBurnRateDataType] (instantaneous rate) and [CarbStatusDataType]
 * (deficit between burned and logged). Push-based — driven by `statusFlow` emissions
 * from [com.enderthor.kSafe.extension.managers.CarbsTracker].
 *
 * No rider-pickable colour: always inflates `field_view_auto.xml` (Karoo-theme passthrough).
 */
class CarbsBurnedDataType(
    datatype: String,
    private val context: Context,
) : DataTypeImpl("ksafe", datatype) {

    private fun buildView(viewConfig: ViewConfig, main: String, hint: String): RemoteViews {
        // See CarbBurnRateDataType — same passive-info contract (rider alignment
        // honoured, runtime-detected text colour for theme contrast).
        val gravity = viewConfig.fieldGravity()
        val dark = context.isKarooNightMode()
        return RemoteViews(context.packageName, R.layout.field_view_auto).apply {
            // take(11): "Pair HR/Pwr" is 11 chars (a take(9) clipped it to
            // "Pair HR/P"). Numeric totals are short; layout auto-sizes.
            setTextViewText(R.id.field_text_main, main.take(11))
            setTextViewText(R.id.field_text_hint, hint.take(9))
            setViewVisibility(R.id.field_text_hint, if (hint.isEmpty()) View.GONE else View.VISIBLE)
            setInt(R.id.field_text_main, "setGravity", gravity)
            setInt(R.id.field_text_hint, "setGravity", gravity)
            setTextColor(R.id.field_text_main, if (dark) Color.WHITE else Color.BLACK)
            setTextColor(R.id.field_text_hint, if (dark) 0xCCFFFFFF.toInt() else 0xCC000000.toInt())
        }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scopeJob = Job()
        val scope = CoroutineScope(Dispatchers.Default + scopeJob)

        val configJob = scope.launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            emitter.onNext(ShowCustomStreamState(message = "", color = null))
            awaitCancellation()
        }

        val viewJob = scope.launch {
            try {
                // Push-based — see CarbStatusDataType for the rationale.
                val tracker = KSafeExtension.carbsTrackerFlow.filterNotNull().first()
                tracker.statusFlow.collectLatest { status ->
                    val main = when {
                        status == null -> "---"
                        // Show "Pair HR/Pwr" ONLY when the rider has never had a
                        // sensor paired (cumBurnedG still 0). A mid-ride disconnect
                        // after some burn keeps showing the running total — flipping
                        // a 120 reading to the label would look like data loss. The
                        // total freezes while the sensor is gone (CarbsTracker drops
                        // to confidence=NONE → rate 0 → no further integration).
                        status.burnConfidence == com.enderthor.kSafe.extension.util.CarbBurnEstimator.Confidence.NONE &&
                            status.cumBurnedG == 0 ->
                            context.getString(R.string.carb_no_sensor_label)
                        // Nothing accumulated yet → "---" (unified no-data state).
                        status.cumBurnedG == 0 -> "---"
                        // Plain number — the gram unit lives in the "g" hint below,
                        // consistent with the rate fields ("g/h"). No inline suffix.
                        else -> "${status.cumBurnedG}"
                    }
                    emitter.updateView(buildView(config, main, "g"))
                }
            } catch (_: CancellationException) {
                // normal
            } catch (e: Exception) {
                Timber.e(e, "CarbsBurnedDataType error: ${e.message}")
            }
        }

        emitter.setCancellable {
            configJob.cancel()
            viewJob.cancel()
            scope.cancel()
            scopeJob.cancel()
        }
    }
}
