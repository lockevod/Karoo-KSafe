package com.enderthor.kSafe.datatype

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import com.enderthor.kSafe.R
import com.enderthor.kSafe.extension.KSafeExtension
import com.enderthor.kSafe.extension.util.CarbBurnEstimator
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
 * Instantaneous carb burn rate in g/h from the physiological burn estimator
 * (v18+). The field shows:
 *  - `---` whenever the tracker is NOT integrating (no ride, movement gate
 *    blocking from a bench / traffic-light stop, GPS not yet emitting).
 *  - `Pair HR/Pwr` when no sensor can drive any tier of [CarbBurnEstimator]
 *    (POWER / KEYTEL / SWAIN all unavailable) AND the rider has not yet
 *    accumulated any burn this session. A mid-ride sensor disconnect that
 *    leaves a non-zero `cumBurnedG` does NOT flip to this label — that
 *    would look like data loss.
 *  - The live g/h rate otherwise.
 *
 * Push-based — driven by `statusFlow` emissions from
 * [com.enderthor.kSafe.extension.managers.CarbsTracker].
 *
 * No rider-pickable colour: this is a passive info field, so it always inflates
 * `field_view_auto.xml` (Karoo-theme passthrough — black/white auto day/night, matches
 * native Karoo data fields and KDouble's neutral fields).
 */
class CarbBurnRateDataType(
    datatype: String,
    private val context: Context,
) : DataTypeImpl("ksafe", datatype) {

    private fun buildView(viewConfig: ViewConfig, main: String, hint: String): RemoteViews {
        // Passive info readout: respects the rider's per-field alignment from the
        // Karoo profile editor (LEFT / CENTER / RIGHT — default RIGHT, matching
        // native Karoo numeric fields). Text colour is set explicitly to contrast
        // with the host's day/night background — see field_view_auto.xml for why
        // we don't use ?android:attr/textColorPrimary.
        val gravity = viewConfig.fieldGravity()
        val dark = context.isKarooNightMode()
        return RemoteViews(context.packageName, R.layout.field_view_auto).apply {
            // No setBackgroundColor — let the host theme show through.
            // take(11): the numeric values are short (≤"90"), but the
            // "Pair HR/Pwr" label is 11 chars — a take(9) clipped it to
            // "Pair HR/P". The layout auto-sizes (6–22sp) and wraps to 2 lines,
            // so 11 fits without overflow.
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
                    // v18: explicit "Pair HR/Pwr" label when neither power nor HR
                    // can drive the estimator. The estimator returns gph=0 and
                    // confidence=NONE in that case — showing "0" would mislead
                    // the rider into thinking they're burning nothing instead of
                    // realising the model can't compute anything from what's
                    // paired. Tier ordering is checked centrally in
                    // [CarbBurnEstimator.estimate].
                    //
                    // Guarded by `cumBurnedG == 0` so a sensor that disconnects
                    // mid-ride (HR battery dies, BLE drops) doesn't replace a
                    // legitimate running rate with a "Pair HR/Pwr" message that
                    // reads like data loss. Once the rider has accumulated some
                    // burn this session we keep showing a numeric rate (which
                    // will be 0 until the sensor reconnects, or the rate from
                    // whichever tier can still run).
                    val main = when {
                        status == null -> "---"
                        // Never had a usable sensor this session → prompt to pair.
                        status.burnConfidence == CarbBurnEstimator.Confidence.NONE &&
                            status.cumBurnedG == 0 ->
                            context.getString(R.string.carb_no_sensor_label)
                        // Had a sensor but it died / went stale mid-ride: the
                        // instantaneous rate must reflect LIVE data only — never
                        // freeze on the last value. CarbsTracker now drops to
                        // confidence=NONE once HR/power exceed SENSOR_STALE_MS, so
                        // this branch fires the moment the live rate goes away.
                        // (The cumulative + average fields keep their accumulated
                        // value — see CarbsBurned / CarbAvgBurnRate.)
                        status.burnConfidence == CarbBurnEstimator.Confidence.NONE -> "---"
                        !status.isIntegrating -> "---"
                        else -> "${status.burnRateGph}"
                    }
                    emitter.updateView(buildView(config, main, "g/h"))
                }
            } catch (_: CancellationException) {
                // normal
            } catch (e: Exception) {
                Timber.e(e, "CarbBurnRateDataType error: ${e.message}")
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
