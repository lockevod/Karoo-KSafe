package com.enderthor.kSafe.datatype

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import com.enderthor.kSafe.R
import com.enderthor.kSafe.activity.FieldTapReceiver
import com.enderthor.kSafe.data.CHECKIN_WARNING_THRESHOLD_MINUTES
import com.enderthor.kSafe.data.EmergencyStatus
import com.enderthor.kSafe.data.FIELD_COLOR_AUTO
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

    // Cached PendingIntent — see CarbLogDataType. Particularly impactful here because
    // the countdown branch emits a new view every 1 s for 30 s on every emergency.
    @Volatile private var cachedPi: PendingIntent? = null
    private fun pendingIntentFor(context: Context): PendingIntent {
        cachedPi?.let { return it }
        return PendingIntent.getBroadcast(
            context, 102,
            Intent(FieldTapReceiver.ACTION_TIMER).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        ).also { cachedPi = it }
    }

    /** Builds a field view with optional click PendingIntent (requestCode 102 = Timer). */
    private fun buildView(context: Context, config: ViewConfig, bgColor: Int, main: String, hint: String = "", clickable: Boolean = true): RemoteViews {
        // See CarbLogDataType.buildView — same layout-switch + center alignment
        // (tap-target field) + auto-mode text colour contract.
        val isAuto = bgColor == FIELD_COLOR_AUTO
        val layout = if (isAuto) R.layout.field_view_auto else R.layout.field_view
        val content = RemoteViews(context.packageName, layout).apply {
            if (!isAuto) setInt(R.id.field_container, "setBackgroundColor", bgColor)
            setTextViewText(R.id.field_text_main, main.take(9))
            setTextViewText(R.id.field_text_hint, hint.take(9))
            setViewVisibility(R.id.field_text_hint, if (hint.isEmpty()) View.GONE else View.VISIBLE)
            setInt(R.id.field_text_main, "setGravity", Gravity.CENTER)
            setInt(R.id.field_text_hint, "setGravity", Gravity.CENTER)
            if (isAuto) {
                val dark = context.isKarooNightMode()
                setTextColor(R.id.field_text_main, if (dark) Color.WHITE else Color.BLACK)
                setTextColor(R.id.field_text_hint, if (dark) 0xCCFFFFFF.toInt() else 0xCC000000.toInt())
            }
        }
        if (!config.preview && clickable) {
            val wrapper = RemoteViews(context.packageName, R.layout.field_tap_wrapper)
            wrapper.setOnClickPendingIntent(R.id.field_tap_wrapper, pendingIntentFor(context))
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
                // Cache the last emitted "frame" key so we skip the RemoteViews build +
                // updateView IPC when neither the displayed text nor the colour changed.
                // Common case: the idle branch's 30 s re-evaluate timeout falls through
                // with the same minute count (or the countdown branch's 1 Hz tick lands
                // on the same integer second after sub-second drift). Worth ~1 emit/min
                // saved continuously across long rides.
                var lastEmitKey: String? = null
                fun emit(bgColor: Int, main: String, hint: String, clickable: Boolean) {
                    val key = "$bgColor|$main|$hint|$clickable"
                    if (key == lastEmitKey) return
                    lastEmitKey = key
                    emitter.updateView(buildView(context, config, bgColor, main, hint, clickable))
                }
                while (true) {
                    val state = EmergencyManager.uiState.value
                    when {
                        state.status == EmergencyStatus.COUNTDOWN -> {
                            val secs = state.countdownRemaining()
                            emit(COLOR_CANCEL, "CANCEL\n${secs}s", "", clickable = true)
                            delay(1_000L)
                        }
                        !state.checkinEnabled -> {
                            emit(COLOR_DISABLED, "Timer\nOFF", "", clickable = false)
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
                            emit(bgColor, mainText, hintText, clickable = true)
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
