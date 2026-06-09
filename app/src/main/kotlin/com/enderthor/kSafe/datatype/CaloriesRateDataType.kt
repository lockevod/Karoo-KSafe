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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Instantaneous HR-based energy expenditure (kcal/h). Push-based — driven by
 * `statusFlow` from [com.enderthor.kSafe.extension.managers.CarbsTracker]. Passive
 * info field (Karoo-theme passthrough). Shows `Pair HR/Pwr` when no model can fire,
 * `---` when the tracker is not integrating, else the live kcal/h.
 */
class CaloriesRateDataType(
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
                tracker.statusFlow.collectLatest { status ->
                    val main = when {
                        status == null -> "---"
                        // Feature disabled → neutral, never a stale/live number.
                        !status.caloriesEnabled -> "---"
                        status.calorieSource == CalorieSource.NONE && status.kcalTotal == 0 ->
                            context.getString(R.string.carb_no_sensor_label)
                        status.calorieSource == CalorieSource.NONE -> "---"
                        !status.isIntegrating -> "---"
                        else -> "${status.kcalPerHour}"
                    }
                    emitter.updateView(buildView(config, main, "kcal/h"))
                }
            } catch (_: CancellationException) {
                // normal
            } catch (e: Exception) {
                Timber.e(e, "CaloriesRateDataType error: ${e.message}")
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
