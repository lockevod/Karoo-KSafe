package com.enderthor.kSafe.datatype

import android.content.Context
import android.widget.RemoteViews
import com.enderthor.kSafe.R
import com.enderthor.kSafe.extension.KSafeExtension
import com.enderthor.kSafe.extension.util.CalorieSource
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Cumulative HR-based calories this session (kcal). Push-based — driven by
 * `statusFlow` from [com.enderthor.kSafe.extension.managers.CarbsTracker]. Passive
 * info field (Karoo-theme passthrough). An estimate computed from HR (Keytel / Swain
 * / %HRmax) — independent of, and shown alongside, the Karoo's power-based calories.
 */
class CaloriesTotalDataType(
    datatype: String,
    private val context: Context,
) : DataTypeImpl("ksafe", datatype) {

    // Standard-Karoo readout: units on top, big value below, sized from the host's
    // ViewConfig.textSize. See [buildReadoutView] for the shared rendering contract.
    private fun buildView(viewConfig: ViewConfig, main: String, hint: String): RemoteViews =
        context.buildReadoutView(viewConfig, main, hint, R.drawable.ic_readout_calories)

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
                        // Master OFF → explicit disabled state — see CarbBurnRateDataType.
                        !status.masterEnabled -> context.getString(R.string.fueling_field_off)
                        // Feature disabled → neutral, never a stale/live number.
                        !status.caloriesEnabled -> "---"
                        // No HR (or no weight) → no estimate possible yet.
                        status.calorieSource == CalorieSource.NONE && status.kcalTotal == 0 ->
                            context.getString(R.string.carb_no_sensor_label)
                        status.kcalTotal == 0 -> "---"
                        else -> "${status.kcalTotal}"
                    }
                    // "CALORIES" (not "kcal") — matches the Karoo's native calories field
                    // label so the readout reads as the same metric family.
                    emitter.updateView(buildView(config, main, "CALORIES"))
                }
            } catch (_: CancellationException) {
                // normal
            } catch (e: Exception) {
                Timber.e(e, "CaloriesTotalDataType error: ${e.message}")
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
