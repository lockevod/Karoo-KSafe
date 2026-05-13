package com.enderthor.kSafe.datatype

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.enderthor.kSafe.R
import com.enderthor.kSafe.extension.KSafeExtension
import com.enderthor.kSafe.extension.managers.CarbStatus
import com.enderthor.kSafe.extension.util.ZoneSource
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

/**
 * Instantaneous carb burn rate in g/h, modulated by the rider's current intensity zone.
 * Until the first HR/power sample arrives the zone source is [ZoneSource.NONE] and the
 * field shows `---` instead of `base_target × 1.0`, which used to confuse riders into
 * thinking the body was already burning the configured per-hour target before the ride
 * had even started. Polled once per second from [com.enderthor.kSafe.extension.managers.CarbsTracker].
 *
 * No rider-pickable colour: this is a passive info field, so it always inflates
 * `field_view_auto.xml` (Karoo-theme passthrough — black/white auto day/night, matches
 * native Karoo data fields and KDouble's neutral fields).
 */
class CarbBurnRateDataType(
    datatype: String,
    private val context: Context,
    private val karooSystem: KarooSystemService,
) : DataTypeImpl("ksafe", datatype) {

    private fun buildView(viewConfig: ViewConfig, main: String, hint: String): RemoteViews {
        val gravity = viewConfig.fieldGravity()
        return RemoteViews(context.packageName, R.layout.field_view_auto).apply {
            // No setBackgroundColor — let the host theme show through.
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
                    // Status field — always Karoo-themed (no rider-pickable colour).
                    // Three "no real value yet" cases all render '---' identically; only the
                    // last branch shows the live number once a zone snapshot is available.
                    val main = when {
                        status == null -> "---"
                        status.zoneSnapshot.source == ZoneSource.NONE -> "---"
                        else -> "${status.burnRateGph}"
                    }
                    emitter.updateView(buildView(config, main, "carb/h"))
                }
                poller.cancel()
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
