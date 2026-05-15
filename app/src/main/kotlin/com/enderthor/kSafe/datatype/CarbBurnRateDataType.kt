package com.enderthor.kSafe.datatype

import android.content.Context
import android.graphics.Color
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

/**
 * Instantaneous carb burn rate in g/h, modulated by the rider's current intensity zone.
 * The field shows `---` whenever the tracker is NOT integrating (no ride, movement gate
 * blocking from a bench / traffic-light stop, GPS not yet emitting). Once integration is
 * active the live rate is shown — equal to the configured base g/h when no HR/power
 * sensors are paired (neutral multiplier = 1.0), and scaled by the intensity zone when
 * sensors are available. Coherent with the burned / deficit fields, which freeze under
 * the exact same gate.
 * Polled once per second from [com.enderthor.kSafe.extension.managers.CarbsTracker].
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
        // Passive info readout: respects the rider's per-field alignment from the
        // Karoo profile editor (LEFT / CENTER / RIGHT — default RIGHT, matching
        // native Karoo numeric fields). Text colour is set explicitly to contrast
        // with the host's day/night background — see field_view_auto.xml for why
        // we don't use ?android:attr/textColorPrimary.
        val gravity = viewConfig.fieldGravity()
        val dark = context.isKarooNightMode()
        return RemoteViews(context.packageName, R.layout.field_view_auto).apply {
            // No setBackgroundColor — let the host theme show through.
            setTextViewText(R.id.field_text_main, main.take(9))
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
                val poll = MutableStateFlow<CarbStatus?>(null)
                val poller = scope.launch {
                    while (true) {
                        val tracker = KSafeExtension.getInstance()?.carbsTrackerOrNull()
                        poll.value = tracker?.getStatus()
                        delay(1_000)
                    }
                }
                poll.collectLatest { status ->
                    // Coherent with the rest of the carb fields: show `---` whenever
                    // integration is NOT happening (no tracker, or movement gate
                    // blocking — bench tests, traffic-light stops, GPS-stale start).
                    // When integration IS happening, show the live rate regardless of
                    // whether sensors are connected: without HR/power the zone
                    // multiplier is the neutral 1.0 and the displayed value equals
                    // the configured base g/h — the same rate the burned / deficit
                    // fields are integrating with at that moment.
                    val main = when {
                        status == null         -> "---"
                        !status.isIntegrating  -> "---"
                        else                   -> "${status.burnRateGph}"
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
