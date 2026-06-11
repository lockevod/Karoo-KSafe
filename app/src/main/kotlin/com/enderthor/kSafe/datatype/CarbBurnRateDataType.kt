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

    // Standard-Karoo readout: units on top, big value below, sized from the host's
    // ViewConfig.textSize. Respects the rider's per-field alignment. See
    // [buildReadoutView] for the shared rendering contract.
    private fun buildView(viewConfig: ViewConfig, main: String, hint: String): RemoteViews =
        context.buildReadoutView(viewConfig, main, hint, R.drawable.ic_readout_carbs)

    // Published as a numeric stream (instantaneous g/h) — see [startFuelingStream].
    // Searching whenever confidence is NONE (never paired OR sensor died mid-ride):
    // the instantaneous rate must reflect LIVE data only, same rule as the view's
    // `---`. 0 while the movement gate blocks integration (not accruing).
    override fun startStream(emitter: Emitter<StreamState>) =
        startFuelingStream(emitter, KSafeExtension.carbsTrackerFlow, { it.statusFlow }) { s ->
            when {
                !s.masterEnabled || !s.carbsEnabled -> StreamState.NotAvailable
                s.burnConfidence == CarbBurnEstimator.Confidence.NONE -> StreamState.Searching
                !s.isIntegrating -> streamingSingle(0)
                else -> streamingSingle(s.burnRateGph)
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
                // Merged with nightModeFlow (see its KDoc): this is a Karoo-theme
                // passthrough field whose text colour is baked in at build time, so a
                // sunset/sunrise theme flip needs a re-render — statusFlow alone only
                // re-emits on a CHANGED status (stuck black-on-black while autopaused).
                combine(tracker.statusFlow, KSafeExtension.nightModeFlow) { s, _ -> s }
                    .collectLatest { status ->
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
                        // Profile-editor gallery: neutral waiting frame, never live/OFF/stale data.
                        config.preview -> "---"
                        status == null -> "---"
                        // Master switch OFF → explicit disabled state. Without this the
                        // last snapshot's branches below win (often "Pair HR/Pwr"), which
                        // reads as a sensor problem instead of "extension is off".
                        !status.masterEnabled -> context.getString(R.string.fueling_field_off)
                        // Carb feature off (calories-only session): the monitor runs and
                        // the live rate exists, but the cumulative/deficit siblings are
                        // frozen at 0 — render neutral so the family agrees (mirror of
                        // the caloriesEnabled gate on the calorie fields).
                        !status.carbsEnabled -> "---"
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
