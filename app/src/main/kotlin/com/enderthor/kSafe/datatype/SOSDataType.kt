package com.enderthor.kSafe.datatype

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.background
import androidx.glance.action.clickable
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.enderthor.kSafe.data.EmergencyStatus
import com.enderthor.kSafe.extension.managers.EmergencyManager
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

// ─── Color constants — created once, reused on every render ──────────────────

private val COLOR_SAFE      = Color(0xFF1B5E20)
private val COLOR_COUNTDOWN = Color(0xFFE65100)
private val COLOR_ALERTING  = Color(0xFFB71C1C)
private val COLOR_TEXT      = Color.White
private val COLOR_HINT      = Color(0xCCFFFFFF)

// ─── TextStyle constants — avoids allocating new objects on every render ──────

private val STYLE_SAFE = TextStyle(
    fontSize = TextUnit(14f, TextUnitType.Sp),
    fontWeight = FontWeight.Bold,
    textAlign = TextAlign.Center,
    color = ColorProvider(COLOR_TEXT, COLOR_TEXT)
)
private val STYLE_HINT = TextStyle(
    fontSize = TextUnit(8f, TextUnitType.Sp),
    textAlign = TextAlign.Center,
    color = ColorProvider(COLOR_HINT, COLOR_HINT)
)
private val STYLE_SOS_LABEL = TextStyle(
    fontSize = TextUnit(11f, TextUnitType.Sp),
    fontWeight = FontWeight.Bold,
    textAlign = TextAlign.Center,
    color = ColorProvider(COLOR_TEXT, COLOR_TEXT)
)
private val STYLE_COUNTDOWN = TextStyle(
    fontSize = TextUnit(16f, TextUnitType.Sp),
    fontWeight = FontWeight.Bold,
    textAlign = TextAlign.Center,
    color = ColorProvider(COLOR_TEXT, COLOR_TEXT)
)
private val STYLE_CANCEL_HINT = TextStyle(
    fontSize = TextUnit(7f, TextUnitType.Sp),
    textAlign = TextAlign.Center,
    color = ColorProvider(COLOR_HINT, COLOR_HINT)
)
private val STYLE_ALERT = TextStyle(
    fontSize = TextUnit(12f, TextUnitType.Sp),
    fontWeight = FontWeight.Bold,
    textAlign = TextAlign.Center,
    color = ColorProvider(COLOR_TEXT, COLOR_TEXT)
)

@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class SOSDataType(
    datatype: String,
    private val context: Context,
    private val karooSystem: KarooSystemService,
) : DataTypeImpl("ksafe", datatype) {

    private val glance = GlanceRemoteViews()

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scopeJob = Job()
        // Dispatchers.Default: computation work (Glance composition), not blocking I/O
        val scope = CoroutineScope(Dispatchers.Default + scopeJob)

        val configJob = scope.launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            emitter.onNext(ShowCustomStreamState(message = "", color = null))
            awaitCancellation()
        }

        val viewJob = scope.launch {
            var tickerJob: Job? = null
            try {
                EmergencyManager.uiState.collect { state ->
                    // Wait for the ticker to fully stop before rendering the new state.
                    // Without join(), the ticker can win a race and overwrite SAFE with
                    // the last countdown value on a different Default dispatcher thread.
                    tickerJob?.cancelAndJoin()
                    tickerJob = null

                    when (state.status) {
                        EmergencyStatus.IDLE ->
                            emitter.updateView(renderSafe(context, config).remoteViews)
                        EmergencyStatus.COUNTDOWN -> {
                            tickerJob = scope.launch {
                                while (true) {
                                    val remaining = state.countdownRemaining()
                                    emitter.updateView(
                                        renderCountdown(context, config, remaining, state.reason).remoteViews
                                    )
                                    if (remaining <= 0) break
                                    delay(1_000L)
                                }
                            }
                        }
                        EmergencyStatus.ALERTING ->
                            emitter.updateView(renderAlerting(context, config).remoteViews)
                    }
                }
            } catch (e: CancellationException) {
                // normal cancellation
            } catch (e: Exception) {
                Timber.e(e, "SOSDataType error: ${e.message}")
                emitter.updateView(renderSafe(context, config).remoteViews)
            } finally {
                tickerJob?.cancel()
            }
        }

        emitter.setCancellable {
            configJob.cancel()
            viewJob.cancel()
            scope.cancel()
            scopeJob.cancel()
        }
    }

    private suspend fun renderSafe(context: Context, config: ViewConfig) =
        glance.compose(context, DpSize.Unspecified) {
            var modifier = GlanceModifier.fillMaxSize().background(COLOR_SAFE)
            if (!config.preview) modifier = modifier.clickable(actionRunCallback<SOSActionCallback>())
            Box(modifier = modifier.padding(2.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("SAFE", style = STYLE_SAFE)
                    Text("tap=SOS", style = STYLE_HINT)
                }
            }
        }

    private suspend fun renderCountdown(context: Context, config: ViewConfig, seconds: Int, reason: String) =
        glance.compose(context, DpSize.Unspecified) {
            var modifier = GlanceModifier.fillMaxSize().background(COLOR_COUNTDOWN)
            if (!config.preview) modifier = modifier.clickable(actionRunCallback<SOSActionCallback>())
            Box(modifier = modifier.padding(2.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("SOS", style = STYLE_SOS_LABEL)
                    Text("${seconds}s", style = STYLE_COUNTDOWN)
                    Text("tap=cancel", style = STYLE_CANCEL_HINT)
                }
            }
        }

    private suspend fun renderAlerting(context: Context, config: ViewConfig) =
        glance.compose(context, DpSize.Unspecified) {
            Box(
                modifier = GlanceModifier.fillMaxSize().background(COLOR_ALERTING).padding(2.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ALERT", style = STYLE_ALERT)
                    Text("SENT", style = STYLE_ALERT)
                }
            }
        }
}
