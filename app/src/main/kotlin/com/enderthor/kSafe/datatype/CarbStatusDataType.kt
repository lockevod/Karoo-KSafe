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

private const val COLOR_AHEAD  = 0xFF1565C0.toInt()  // blue — surplus, no concern
private const val COLOR_OK     = 0xFF2E7D32.toInt()  // green — within margin
private const val COLOR_AMBER  = 0xFFE65100.toInt()  // amber — approaching threshold
private const val COLOR_RED    = 0xFFB71C1C.toInt()  // red — over threshold

class CarbStatusDataType(
    datatype: String,
    private val context: Context,
    private val karooSystem: KarooSystemService,
) : DataTypeImpl("ksafe", datatype) {

    private fun colorFor(deficit: Int, threshold: Int): Int = when {
        deficit < 0                 -> COLOR_AHEAD
        deficit < threshold / 2     -> COLOR_OK
        deficit < threshold         -> COLOR_AMBER
        else                        -> COLOR_RED
    }

    private fun displayMain(deficit: Int): String = when {
        deficit > 0  -> "−${deficit}g"
        deficit < 0  -> "+${-deficit}g"
        else         -> "0g"
    }

    private fun buildView(viewConfig: ViewConfig, bgColor: Int, main: String, hint: String): RemoteViews {
        val gravity = viewConfig.fieldGravity()
        return RemoteViews(context.packageName, R.layout.field_view).apply {
            setInt(R.id.field_container, "setBackgroundColor", bgColor)
            setTextViewText(R.id.field_text_main, main.take(9))
            setTextViewText(R.id.field_text_hint, hint.take(9))
            setViewVisibility(R.id.field_text_hint, if (hint.isEmpty()) View.GONE else View.VISIBLE)
            setInt(R.id.field_text_main, "setGravity", gravity)
            setInt(R.id.field_text_hint, "setGravity", gravity)
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

        // Poll the tracker once a second. The tracker publishes its state on its own ticks (5s)
        // and on every logEntry, so the field reflects logs immediately on tap.
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
                        // Tracker not running yet (extension still booting, or no ride
                        // started). Show '---' so the rider doesn't read 'off' as
                        // 'I disabled this', but keep COLOR_OK — this field is not
                        // *disabled*, it's *waiting for data*. The disabled-style grey
                        // belongs to fields that actually got turned off in config
                        // (webhooks, custom messages, log slots).
                        buildView(config, COLOR_OK, "---", "carbs")
                    } else {
                        val color = colorFor(status.deficitG, status.deficitThresholdG)
                        buildView(config, color, displayMain(status.deficitG), "carbs")
                    }
                    emitter.updateView(view)
                }
                poller.cancel()
            } catch (_: CancellationException) {
                // normal
            } catch (e: Exception) {
                Timber.e(e, "CarbStatusDataType error: ${e.message}")
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
