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
 * Session-average carb burn rate in g/h, averaged over the time the tracker
 * has been actively integrating this session (NOT total elapsed time — café
 * and traffic-light stops are excluded so the displayed number reflects
 * average effort, not ride-rhythm luck).
 *
 * Companion to [CarbBurnRateDataType] (instantaneous) — riders typically want
 * BOTH on the screen during a long ride: instantaneous to react to intensity
 * changes, average to gauge how much they've been working over the ride.
 *
 * Display rules (same contract as the other carb fields):
 *  - `---` while no tracker is published yet (extension is starting up).
 *  - "Pair HR/Pwr" when the burn estimator has no usable inputs
 *    ([CarbBurnEstimator.Confidence.NONE]).
 *  - The average g/h otherwise. Stays at 0 until at least one tick of
 *    integration has happened, then climbs as the rider works.
 *
 * Push-based via `statusFlow` — see [CarbStatusDataType] for the rationale.
 * No rider-pickable colour: passive info field, inflates `field_view_auto.xml`
 * (Karoo-theme passthrough — auto day / night).
 */
class CarbAvgBurnRateDataType(
    datatype: String,
    private val context: Context,
) : DataTypeImpl("ksafe", datatype) {

    private fun buildView(viewConfig: ViewConfig, main: String, hint: String): RemoteViews {
        val gravity = viewConfig.fieldGravity()
        val dark = context.isKarooNightMode()
        return RemoteViews(context.packageName, R.layout.field_view_auto).apply {
            // take(11): "Pair HR/Pwr" is 11 chars (a take(9) clipped it to
            // "Pair HR/P"). Numeric values ("ø 90") are short; layout auto-sizes.
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
                val tracker = KSafeExtension.carbsTrackerFlow.filterNotNull().first()
                tracker.statusFlow.collectLatest { status ->
                    val main = when {
                        status == null -> "---"
                        // "Pair HR/Pwr" ONLY when the rider has never had a sensor
                        // paired this session (cumBurnedG == 0).
                        status.burnConfidence == CarbBurnEstimator.Confidence.NONE &&
                            status.cumBurnedG == 0 ->
                            context.getString(R.string.carb_no_sensor_label)
                        // Nothing accumulated yet → "---" (unified no-data state
                        // across all three carb fields). Once burn has accumulated
                        // the average holds the value the rider earned and FREEZES
                        // if the sensor later dies (CarbIntegrator stops advancing
                        // active-integration time when the live rate is 0).
                        status.cumBurnedG == 0 -> "---"
                        // "ø" marks this as the session average vs the instantaneous
                        // rate field, which shares the same "g/h" unit hint.
                        else -> "ø ${status.avgBurnRateGph}"
                    }
                    // Hint also carries "ø": the field header is hidden
                    // (showHeader = false), so the instantaneous-rate field and this
                    // average field would otherwise show an identical "g/h" hint and
                    // be distinguishable only by the value prefix. "ø g/h" makes the
                    // unit line itself unambiguous at a glance.
                    emitter.updateView(buildView(config, main, "ø g/h"))
                }
            } catch (_: CancellationException) {
                // normal
            } catch (e: Exception) {
                Timber.e(e, "CarbAvgBurnRateDataType error: ${e.message}")
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
