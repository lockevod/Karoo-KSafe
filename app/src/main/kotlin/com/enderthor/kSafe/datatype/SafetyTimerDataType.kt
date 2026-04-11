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
import com.enderthor.kSafe.data.CHECKIN_WARNING_THRESHOLD_MINUTES
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

// ─── Color constants ──────────────────────────────────────────────────────────

private val COLOR_OK       = Color(0xFF1B5E20)
private val COLOR_WARNING  = Color(0xFFF57F17)
private val COLOR_EXPIRED  = Color(0xFFB71C1C)
private val COLOR_DISABLED = Color(0xFF424242)
private val COLOR_CANCEL   = Color(0xFFE65100)
private val COLOR_TEXT     = Color.White
private val COLOR_HINT     = Color(0xCCFFFFFF)
private val COLOR_DISABLED_TEXT = Color(0xFF9E9E9E)

// ─── TextStyle constants — created once, reused on every render ───────────────

private val STYLE_TIMER = TextStyle(
    fontSize = TextUnit(14f, TextUnitType.Sp),
    fontWeight = FontWeight.Bold,
    textAlign = TextAlign.Center,
    color = ColorProvider(COLOR_TEXT, COLOR_TEXT)
)
private val STYLE_TAP_HINT = TextStyle(
    fontSize = TextUnit(8f, TextUnitType.Sp),
    textAlign = TextAlign.Center,
    color = ColorProvider(COLOR_HINT, COLOR_HINT)
)
private val STYLE_CHECKIN_LABEL = TextStyle(
    fontSize = TextUnit(12f, TextUnitType.Sp),
    fontWeight = FontWeight.Bold,
    textAlign = TextAlign.Center,
    color = ColorProvider(COLOR_TEXT, COLOR_TEXT)
)
private val STYLE_CANCEL_LABEL = TextStyle(
    fontSize = TextUnit(11f, TextUnitType.Sp),
    fontWeight = FontWeight.Bold,
    textAlign = TextAlign.Center,
    color = ColorProvider(COLOR_TEXT, COLOR_TEXT)
)
private val STYLE_CANCEL_SECONDS = TextStyle(
    fontSize = TextUnit(16f, TextUnitType.Sp),
    fontWeight = FontWeight.Bold,
    textAlign = TextAlign.Center,
    color = ColorProvider(COLOR_TEXT, COLOR_TEXT)
)
private val STYLE_DISABLED = TextStyle(
    fontSize = TextUnit(10f, TextUnitType.Sp),
    textAlign = TextAlign.Center,
    color = ColorProvider(COLOR_DISABLED_TEXT, COLOR_DISABLED_TEXT)
)

@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class SafetyTimerDataType(
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
                    // Without join(), the ticker can win a race and overwrite the reset
                    // view with the last countdown value on another Default dispatcher thread.
                    tickerJob?.cancelAndJoin()
                    tickerJob = null

                    // During any emergency countdown show cancel option
                    if (state.status == EmergencyStatus.COUNTDOWN) {
                        tickerJob = scope.launch {
                            while (true) {
                                val remaining = state.countdownRemaining()
                                emitter.updateView(renderCancelEmergency(context, config, remaining).remoteViews)
                                if (remaining <= 0) break
                                delay(1_000L)
                            }
                        }
                        return@collect
                    }

                    if (!state.checkinEnabled) {
                        emitter.updateView(renderDisabled(context, config).remoteViews)
                        return@collect
                    }

                    // Initial render after ticker stopped
                    emitter.updateView(renderTimer(context, config, state.checkinRemainingMinutes()).remoteViews)

                    // Refresh every minute — no need to update more often than check-in resolution
                    tickerJob = scope.launch {
                        while (true) {
                            delay(60_000L)
                            val remaining = state.checkinRemainingMinutes()
                            emitter.updateView(renderTimer(context, config, remaining).remoteViews)
                            if (remaining <= 0) break
                        }
                    }
                }
            } catch (e: CancellationException) {
                // normal
            } catch (e: Exception) {
                Timber.e(e, "SafetyTimerDataType error: ${e.message}")
                emitter.updateView(renderDisabled(context, config).remoteViews)
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

    private suspend fun renderTimer(context: Context, config: ViewConfig, remainingMinutes: Int) =
        glance.compose(context, DpSize.Unspecified) {
            val isExpired = remainingMinutes <= 0
            val isWarning = remainingMinutes in 1..CHECKIN_WARNING_THRESHOLD_MINUTES
            val bgColor = when {
                isExpired -> COLOR_EXPIRED
                isWarning -> COLOR_WARNING
                else      -> COLOR_OK
            }

            var modifier = GlanceModifier.fillMaxSize().background(bgColor)
            if (!config.preview) modifier = modifier.clickable(actionRunCallback<TimerActionCallback>())

            Box(modifier = modifier.padding(2.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (isExpired) {
                        Text("CHECK", style = STYLE_CHECKIN_LABEL)
                        Text("IN!", style = STYLE_CHECKIN_LABEL)
                    } else {
                        val hours = remainingMinutes / 60
                        val mins  = remainingMinutes % 60
                        val timeText = if (hours > 0) "${hours}h${mins}m" else "${mins}m"
                        Text(timeText, style = STYLE_TIMER)
                        Text("tap=ok", style = STYLE_TAP_HINT)
                    }
                }
            }
        }

    private suspend fun renderDisabled(context: Context, config: ViewConfig) =
        glance.compose(context, DpSize.Unspecified) {
            Box(
                modifier = GlanceModifier.fillMaxSize().background(COLOR_DISABLED).padding(2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Timer\nOFF", style = STYLE_DISABLED)
            }
        }

    /** Shown during any active emergency countdown — tapping cancels the alert. */
    private suspend fun renderCancelEmergency(context: Context, config: ViewConfig, secondsRemaining: Int) =
        glance.compose(context, DpSize.Unspecified) {
            var modifier = GlanceModifier.fillMaxSize().background(COLOR_CANCEL)
            if (!config.preview) modifier = modifier.clickable(actionRunCallback<TimerActionCallback>())
            Box(modifier = modifier.padding(2.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("CANCEL", style = STYLE_CANCEL_LABEL)
                    Text("${secondsRemaining}s", style = STYLE_CANCEL_SECONDS)
                }
            }
        }
}
