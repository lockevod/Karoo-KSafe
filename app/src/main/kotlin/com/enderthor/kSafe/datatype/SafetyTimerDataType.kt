package com.enderthor.kSafe.datatype

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.enderthor.kSafe.R
import com.enderthor.kSafe.activity.FieldTapReceiver
import com.enderthor.kSafe.data.CHECKIN_WARNING_THRESHOLD_MINUTES
import com.enderthor.kSafe.data.EmergencyStatus
import com.enderthor.kSafe.extension.managers.ConfigurationManager
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

private const val COLOR_WARNING  = 0xFFF57F17.toInt()
private const val COLOR_EXPIRED  = 0xFFB71C1C.toInt()
private const val COLOR_DISABLED = 0xFF424242.toInt()
private const val COLOR_CANCEL   = 0xFFE65100.toInt()

class SafetyTimerDataType(
    datatype: String,
    private val context: Context,
    private val karooSystem: KarooSystemService,
) : DataTypeImpl("ksafe", datatype) {

    private val configManager = ConfigurationManager(context)

    /** Builds a field view with optional click PendingIntent (requestCode 102 = Timer). */
    private fun buildView(context: Context, config: ViewConfig, bgColor: Int, main: String, hint: String = "", clickable: Boolean = true): RemoteViews {
        val content = RemoteViews(context.packageName, R.layout.field_view).apply {
            setInt(R.id.field_container, "setBackgroundColor", bgColor)
            setTextViewText(R.id.field_text_main, main.take(9))
            setTextViewText(R.id.field_text_hint, hint.take(9))
            setViewVisibility(R.id.field_text_hint, if (hint.isEmpty()) View.GONE else View.VISIBLE)
        }
        if (!config.preview && clickable) {
            val pi = PendingIntent.getBroadcast(
                context, 102,
                Intent(FieldTapReceiver.ACTION_TIMER).setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val wrapper = RemoteViews(context.packageName, R.layout.field_tap_wrapper)
            wrapper.setOnClickPendingIntent(R.id.field_tap_wrapper, pi)
            wrapper.addView(R.id.field_tap_wrapper, content)
            return wrapper
        }
        return content
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
                var okColor = 0xFF1B5E20.toInt()
                launch {
                    configManager.loadConfigFlow().collect { c -> okColor = c.timerFieldColor }
                }
                while (true) {
                    val state = EmergencyManager.uiState.value
                    when {
                        state.status == EmergencyStatus.COUNTDOWN -> {
                            val secs = state.countdownRemaining()
                            emitter.updateView(buildView(context, config, COLOR_CANCEL, "CANCEL\n${secs}s"))
                            delay(1_000L)
                        }
                        !state.checkinEnabled -> {
                            emitter.updateView(buildView(context, config, COLOR_DISABLED, "Timer\nOFF", clickable = false))
                            EmergencyManager.uiState.first { it != state }
                        }
                        else -> {
                            val remaining = state.checkinRemainingMinutes()
                            val isExpired = remaining <= 0
                            val isWarning = remaining in 1..CHECKIN_WARNING_THRESHOLD_MINUTES
                            val bgColor = when {
                                isExpired -> COLOR_EXPIRED
                                isWarning -> COLOR_WARNING
                                else      -> okColor
                            }
                            val mainText = when {
                                isExpired -> "CHECK\nIN!"
                                else -> {
                                    val h = remaining / 60
                                    val m = remaining % 60
                                    if (h > 0) "${h}h${m}m" else "${m}m"
                                }
                            }
                            val hintText = if (isExpired) "" else "tap=ok"
                            emitter.updateView(buildView(context, config, bgColor, mainText, hintText))
                            withTimeoutOrNull(30_000L) {
                                EmergencyManager.uiState.first { it != state }
                            }
                        }
                    }
                }
            } catch (_: CancellationException) {
                // normal
            } catch (e: Exception) {
                Timber.e(e, "SafetyTimerDataType error: ${e.message}")
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
