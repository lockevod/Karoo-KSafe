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

    // Bands tuned after field reports of the field sitting in amber/red most of the
    // ride. The deficit threshold is the rider-configured alert level; the green band
    // now covers everything BELOW that threshold (the "you're fine" range), amber is
    // a 50 % overshoot warning zone, and red is the genuinely-behind territory.
    // Blue = surplus (logged more than the cumulative target).
    private fun colorFor(deficit: Int, threshold: Int): Int = when {
        deficit < 0                     -> COLOR_AHEAD
        deficit < threshold             -> COLOR_OK
        deficit < threshold * 3 / 2     -> COLOR_AMBER
        else                            -> COLOR_RED
    }

    private fun displayMain(deficit: Int): String = when {
        deficit > 0  -> "−${deficit}g"
        deficit < 0  -> "+${-deficit}g"
        else         -> "0g"
    }

    private fun buildView(viewConfig: ViewConfig, bgColor: Int, main: String, hint: String): RemoteViews {
        // Centered: matches the rest of the toggleable / state-driven fields.
        // Only the two always-Karoo-theme info readouts (CarbBurnRate, CarbsBurned)
        // honour the rider's per-field alignment — those read as data; this field
        // reads as a coloured semaphore-style status indicator.
        return RemoteViews(context.packageName, R.layout.field_view).apply {
            setInt(R.id.field_container, "setBackgroundColor", bgColor)
            setTextViewText(R.id.field_text_main, main.take(9))
            setTextViewText(R.id.field_text_hint, hint.take(9))
            setViewVisibility(R.id.field_text_hint, if (hint.isEmpty()) View.GONE else View.VISIBLE)
            setInt(R.id.field_text_main, "setGravity", android.view.Gravity.CENTER)
            setInt(R.id.field_text_hint, "setGravity", android.view.Gravity.CENTER)
        }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        // Synchronous seed frame BEFORE launching any coroutine. See HydrationStatusDataType
        // for the rationale — without this, Karoo paints the host theme background while
        // waiting for the first Dispatchers.Default emission, which in day mode shows up
        // as a fully white field (white text on white host bg).
        emitter.updateView(buildView(config, COLOR_OK, "---", "carbs"))

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
