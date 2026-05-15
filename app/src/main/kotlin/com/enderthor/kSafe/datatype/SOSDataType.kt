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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import timber.log.Timber

private const val COLOR_COUNTDOWN = 0xFFE65100.toInt()
private const val COLOR_ALERTING  = 0xFFB71C1C.toInt()

class SOSDataType(
    datatype: String,
    private val context: Context,
    private val karooSystem: KarooSystemService,
) : DataTypeImpl("ksafe", datatype) {

    private val configManager = ConfigurationManager(context)

    // Cached PendingIntent — see CarbLogDataType for the rationale (PI identity is
    // stable across emissions, so build once and reuse).
    @Volatile private var cachedPi: PendingIntent? = null
    private fun pendingIntentFor(context: Context): PendingIntent {
        cachedPi?.let { return it }
        return PendingIntent.getBroadcast(
            context, 101,
            Intent(FieldTapReceiver.ACTION_SOS).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        ).also { cachedPi = it }
    }

    /** Builds a field view with optional click PendingIntent (requestCode 101 = SOS). */
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
                // Track config-driven idle colour in its own StateFlow so the IDLE branch
                // can suspend on `merge(uiState, colorFlow)` instead of polling every 5 s.
                // Previous code did `withTimeoutOrNull(5_000L) { uiState.first { ≠ IDLE } }`
                // — woke the coroutine every 5 s for the whole ride just to redraw on a
                // colour change. With the merge below, a colour change in config produces
                // its own emission and the IDLE branch redraws exactly when it needs to.
                val colorFlow = MutableStateFlow(0xFF1B5E20.toInt())
                launch {
                    configManager.loadConfigFlow().collect { c -> colorFlow.value = c.sosFieldColor }
                }
                while (true) {
                    val state = EmergencyManager.uiState.value
                    when (state.status) {
                        EmergencyStatus.IDLE -> {
                            val renderedColor = colorFlow.value
                            emitter.updateView(buildView(context, config, renderedColor, "SAFE", "tap=SOS"))
                            // Suspend until EITHER the emergency state changes OR the
                            // configured idle colour changes — no timeout-based wakeups.
                            // `filter { it != snapshot }` makes the wait race-free: a state
                            // transition that already happened between rendering and the
                            // subscribe call still triggers an immediate wake-up (StateFlow
                            // emits the current value on subscription, which the predicate
                            // catches when it differs from our snapshot). Without this guard
                            // a `drop(1)`-based variant could leave the IDLE branch stuck
                            // displaying SAFE while the countdown is already running.
                            merge(
                                EmergencyManager.uiState.filter { it != state }.map { Unit },
                                colorFlow.filter { it != renderedColor }.map { Unit },
                            ).first()
                        }
                        EmergencyStatus.COUNTDOWN -> {
                            val secs = state.countdownRemaining()
                            emitter.updateView(buildView(context, config, COLOR_COUNTDOWN, "SOS ${secs}s", "tap=cancel"))
                            kotlinx.coroutines.delay(1_000L)
                        }
                        EmergencyStatus.ALERTING -> {
                            emitter.updateView(buildView(context, config, COLOR_ALERTING, "ALERT\nSENT", clickable = false))
                            EmergencyManager.uiState.first { it.status != EmergencyStatus.ALERTING }
                        }
                    }
                }
            } catch (_: CancellationException) {
                // normal
            } catch (e: Exception) {
                Timber.e(e, "SOSDataType error: ${e.message}")
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
