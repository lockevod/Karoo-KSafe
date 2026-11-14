package com.enderthor.kSafe.datatype

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.ColorRes
import com.enderthor.kSafe.R
import com.enderthor.kSafe.extension.KSafeExtension
import com.enderthor.kSafe.extension.pacing.PacingState
import com.enderthor.kSafe.extension.pacing.PacingView
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale
import kotlin.math.roundToInt

private const val COLOR_DISABLED = 0xFF616161.toInt() // grey — pacing switch off

/** One frame of the Reserve field. [colorRes] null = the disabled grey. */
internal data class ReserveFrame(@ColorRes val colorRes: Int?, val main: String, val hint: String)

/**
 * Render rules, pure so they are unit-testable. [enabled] null = config not seeded yet
 * (waiting, not disabled). Grey OFF only for the switch being off — the manager may still
 * run in shadow for calibration logging, but the rider asked for no advice.
 */
internal fun reserveFrame(
    preview: Boolean, enabled: Boolean?, view: PacingView?, marginLabel: String, waitHint: String,
): ReserveFrame {
    val waiting = ReserveFrame(R.color.pacing_blue, "---", waitHint)
    return when {
        preview || enabled == null -> waiting
        !enabled -> ReserveFrame(null, "OFF", waitHint)
        // No fresh power = the advisor knows nothing (KEEP is only its no_power default): waiting, not OK.
        view == null || !view.trusted || !view.hasPower -> waiting
        else -> {
            val (color, main) = when (view.state) {
                PacingState.CONSERVE -> R.color.pacing_amber to "CONS"
                PacingState.HIGH_MARGIN -> R.color.pacing_green to marginLabel
                PacingState.KEEP -> R.color.pacing_blue to "OK"
            }
            val hint = when {
                view.wPct != null -> "W′${(view.wPct * 100).roundToInt()}"
                view.nextBlockKm != null -> String.format(Locale.US, "%.0fkm", view.nextBlockKm)
                else -> ""
            }
            ReserveFrame(color, if (view.recoverW) "W′!" else main, hint)
        }
    }
}

/** "Reserve" pacing status field (beta). Graphical only — no numeric stream. Structure mirrors
 *  [CarbStatusDataType]: synchronous seed frame, preview gate, paced collect, 9-char clamps. */
class ReserveDataType(
    datatype: String,
    private val context: Context,
    private val karooSystem: KarooSystemService,
) : DataTypeImpl("ksafe", datatype) {

    private fun buildView(frame: ReserveFrame): RemoteViews =
        RemoteViews(context.packageName, R.layout.field_view).apply {
            val bg = frame.colorRes?.let { context.getColor(it) } ?: COLOR_DISABLED
            setInt(R.id.field_container, "setBackgroundColor", bg)
            setTextViewText(R.id.field_text_main, frame.main.take(9))
            setTextViewText(R.id.field_text_hint, frame.hint.take(9))
            setViewVisibility(R.id.field_text_hint, if (frame.hint.isEmpty()) View.GONE else View.VISIBLE)
            setInt(R.id.field_text_main, "setGravity", android.view.Gravity.CENTER)
            setInt(R.id.field_text_hint, "setGravity", android.view.Gravity.CENTER)
        }

    private fun render(config: ViewConfig, enabled: Boolean?, view: PacingView?): RemoteViews =
        buildView(reserveFrame(
            config.preview, enabled, view,
            context.getString(R.string.field_pacing_margin), context.getString(R.string.field_pacing_hint),
        ))

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        // Installed before anything can fail or launch, so a cancel always reaches the jobs.
        emitter.setCancellable { scope.cancel() }

        // Synchronous seed with the real current state — see CarbStatusDataType for why
        // (white field while waiting; a '---' seed racing the replay drops the real frame).
        // A dead ride-app binder throws here: logged, never thrown into the service process.
        runCatching {
            emitter.updateView(render(config, KSafeExtension.pacingEnabledFlow.value,
                KSafeExtension.pacingViewFlow.value?.value))
        }.onFailure { Timber.e(it, "ReserveDataType seed failed") }
        val seedRenderMs = System.currentTimeMillis()

        scope.launch {
            runCatching {
                emitter.onNext(UpdateGraphicConfig(showHeader = false))
                emitter.onNext(ShowCustomStreamState(message = "", color = null))
            }.onFailure { Timber.e(it, "ReserveDataType config failed") }
            awaitCancellation()
        }

        scope.launch {
            try {
                val viewFlow = KSafeExtension.pacingViewFlow.filterNotNull().first()
                combine(KSafeExtension.pacingEnabledFlow, viewFlow) { e, v -> e to v }
                    .pacedForView(seedRenderMs)
                    .collect { (e, v) -> emitter.updateView(render(config, e, v)) }
            } catch (_: CancellationException) {
                // normal
            } catch (e: Exception) {
                Timber.e(e, "ReserveDataType error: ${e.message}")
            }
        }
    }
}
