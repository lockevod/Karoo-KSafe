package com.enderthor.kSafe.datatype

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.enderthor.kSafe.R
import com.enderthor.kSafe.extension.KSafeExtension
import com.enderthor.kSafe.extension.managers.CarbStatus
import io.hammerhead.karooext.KarooSystemService
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber

private const val COLOR_NEUTRAL = 0xFF263238.toInt()  // dark slate — passive info field (live AND waiting-for-data)

/**
 * Cumulative carbs burned this session in grams — i.e. the integrated zone-aware target.
 * Companion to [CarbBurnRateDataType] (instantaneous rate) and [CarbStatusDataType]
 * (deficit between burned and logged). Polled once per second from
 * [com.enderthor.kSafe.extension.managers.CarbsTracker].
 */
class CarbsBurnedDataType(
    datatype: String,
    private val context: Context,
    private val karooSystem: KarooSystemService,
) : DataTypeImpl("ksafe", datatype) {

    private fun buildView(viewConfig: ViewConfig, bgColor: Int, main: String, hint: String): RemoteViews {
        return RemoteViews(context.packageName, R.layout.field_view).apply {
            setInt(R.id.field_container, "setBackgroundColor", bgColor)
            setTextViewText(R.id.field_text_main, main.take(9))
            setTextViewText(R.id.field_text_hint, hint.take(9))
            setViewVisibility(R.id.field_text_hint, if (hint.isEmpty()) View.GONE else View.VISIBLE)
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
                val poll = MutableStateFlow<CarbStatus?>(null)
                val poller = scope.launch {
                    while (true) {
                        val tracker = KSafeExtension.getInstance()?.carbsTrackerOrNull()
                        poll.value = tracker?.getStatus()
                        delay(1_000)
                    }
                }
                poll.collectLatest { status ->
                    val view = if (status == null) {
                        // No data yet — keep COLOR_NEUTRAL, NOT the disabled-style grey.
                        // A status field waiting for data is not the same as a disabled
                        // log slot / webhook / custom-message field.
                        buildView(config, COLOR_NEUTRAL, "---", "burned")
                    } else {
                        buildView(config, COLOR_NEUTRAL, "${status.cumTargetG}g", "burned")
                    }
                    emitter.updateView(view)
                }
                poller.cancel()
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
