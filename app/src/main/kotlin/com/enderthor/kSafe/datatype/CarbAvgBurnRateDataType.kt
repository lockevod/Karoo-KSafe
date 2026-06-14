package com.enderthor.kSafe.datatype

import android.content.Context
import android.widget.RemoteViews
import com.enderthor.kSafe.R
import com.enderthor.kSafe.extension.KSafeExtension
import com.enderthor.kSafe.extension.util.CarbBurnEstimator
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ShowCustomStreamState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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

    // Standard-Karoo readout: units on top, big value below, sized from the host's
    // ViewConfig.textSize. See [buildReadoutView] for the shared rendering contract.
    private fun buildView(viewConfig: ViewConfig, main: String, hint: String): RemoteViews =
        context.buildReadoutView(viewConfig, main, hint, R.drawable.ic_readout_carbs)

    // Published as a numeric stream (session-average g/h) — see [startFuelingStream].
    // Accrued value: keeps streaming through sensor dropouts once any burn exists,
    // Searching only while nothing has ever been measured (mirrors the view).
    override fun startStream(emitter: Emitter<StreamState>) =
        startFuelingStream(emitter, KSafeExtension.carbsTrackerFlow, { it.statusFlow }) { s ->
            when {
                !s.masterEnabled || !s.carbsEnabled -> StreamState.NotAvailable
                s.burnConfidence == CarbBurnEstimator.Confidence.NONE && s.cumBurnedG == 0 ->
                    StreamState.Searching
                else -> streamingSingle(s.avgBurnRateGph)
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
                // Merged with nightModeFlow — see CarbBurnRateDataType (theme passthrough
                // text colour is baked at build time; a day/night flip needs a re-render).
                combine(tracker.statusFlow, KSafeExtension.nightModeFlow) { s, _ -> s }
                    .collectLatest { status ->
                    val main = when {
                        // Profile-editor gallery: neutral waiting frame, never live/OFF/stale data.
                        config.preview -> "---"
                        status == null -> "---"
                        // Master OFF / carb feature off — see CarbBurnRateDataType.
                        !status.masterEnabled -> context.getString(R.string.fueling_field_off)
                        !status.carbsEnabled -> "---"
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
                        // Plain number — the "average" marker lives in the unit line
                        // ("ø g/h"), not as a "ø " value prefix. A prefix forced the big
                        // value off-centre (symbol + space + number) and looked broken in
                        // a wide field; keeping it in the hint renders the number clean.
                        else -> "${status.avgBurnRateGph}"
                    }
                    // Hint carries the "ø" average marker: the field header is hidden
                    // (showHeader = false), so the instantaneous-rate field and this
                    // average field would otherwise show an identical "g/h" hint. "ø g/h"
                    // makes the unit line itself unambiguous at a glance.
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
