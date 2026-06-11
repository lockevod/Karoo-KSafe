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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import timber.log.Timber

private const val COLOR_COUNTDOWN = 0xFFE65100.toInt()
private const val COLOR_ALERTING  = 0xFFB71C1C.toInt()

/** Margin over the Karoo host's ~170 ms updateView coalescing window (keeps only the
 *  first frame inside it) — renders held until this has passed are never dropped. */
internal const val COALESCE_GUARD_MS = 250L

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
        // Synchronous seed frame BEFORE launching any coroutine. Without it Karoo
        // paints the host theme background while waiting for the first
        // Dispatchers.Default emission, which in day mode shows as a blank white
        // field (white text on white host bg) — see CarbStatusDataType. SOS is the
        // safety-critical field, so the seed mirrors the ACTUAL current state read
        // synchronously from the canonical StateFlow (not a blind "SAFE"): on
        // coalescing firmware the host may keep this first frame, so it must be
        // correct even if a countdown/alert is already running when the field
        // re-attaches (page swap, ride-app restart). The configured idle colour is
        // only available asynchronously (DataStore), so the IDLE seed uses the
        // default green and the real colour lands on the colorFlow emission below.
        val startViewAtMs = System.currentTimeMillis()
        val seedState = EmergencyManager.uiState.value
        when (seedState.status) {
            EmergencyStatus.COUNTDOWN -> emitter.updateView(buildView(
                context, config, COLOR_COUNTDOWN,
                context.getString(R.string.sos_countdown, seedState.countdownRemaining()),
                context.getString(R.string.sos_tap_cancel),
            ))
            EmergencyStatus.ALERTING -> emitter.updateView(buildView(
                context, config, COLOR_ALERTING,
                context.getString(R.string.sos_alerting),
                clickable = false,
            ))
            else -> emitter.updateView(buildView(
                context, config, 0xFF1B5E20.toInt(),
                context.getString(R.string.sos_safe),
                context.getString(R.string.sos_field_tap_sos),
            ))
        }

        val scopeJob = Job()
        val scope = CoroutineScope(Dispatchers.Default + scopeJob)

        val configJob = scope.launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            emitter.onNext(ShowCustomStreamState(message = "", color = null))
            awaitCancellation()
        }

        val viewJob = scope.launch {
            try {
                // Last updateView CALL time — the coalescing guard below is anchored to
                // this, NOT to attach time: a guarded frame held to t=250 ms would
                // otherwise open a NEW ~170 ms window of its own, and a colour emission
                // landing right after it (cold DataStore, 250-420 ms) was dropped with
                // renderedColor already advanced — configured colour lost for the ride.
                // Anchoring to the last render guarantees every emitted frame is ≥250 ms
                // after the previous one, so no frame we emit can ever be coalesced away.
                // Seeded with the synchronous seed frame's timestamp.
                var lastRenderMs = startViewAtMs
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
                            // Coalescing guard: the host keeps only the FIRST updateView
                            // inside its ~170 ms window. The DataStore colour emission
                            // typically lands within that window of the seed frame, so the
                            // re-render carrying the rider's configured colour (or AUTO)
                            // was dropped — and with renderedColor already advanced, the
                            // merge below never re-emitted while IDLE: the field kept the
                            // default green for the rest of the ride. Holding any IDLE
                            // render until ≥250 ms after the PREVIOUS render guarantees it
                            // sticks (and colorFlow is read AFTER the hold, so the frame
                            // carries the freshest colour). Zero steady-state cost.
                            val sinceLast = System.currentTimeMillis() - lastRenderMs
                            if (sinceLast < COALESCE_GUARD_MS) {
                                kotlinx.coroutines.delay(COALESCE_GUARD_MS - sinceLast)
                            }
                            val renderedColor = colorFlow.value
                            lastRenderMs = System.currentTimeMillis()
                            emitter.updateView(buildView(
                                context, config, renderedColor,
                                context.getString(R.string.sos_safe),
                                context.getString(R.string.sos_field_tap_sos),
                            ))
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
                            // No guard here (a countdown must paint immediately; the 1 Hz
                            // tick self-heals a coalesced frame within a second) but the
                            // anchor IS updated so the next IDLE render can't land inside
                            // this frame's window.
                            lastRenderMs = System.currentTimeMillis()
                            emitter.updateView(buildView(
                                context, config, COLOR_COUNTDOWN,
                                context.getString(R.string.sos_countdown, secs),
                                context.getString(R.string.sos_tap_cancel),
                            ))
                            kotlinx.coroutines.delay(1_000L)
                        }
                        EmergencyStatus.ALERTING -> {
                            lastRenderMs = System.currentTimeMillis()
                            emitter.updateView(buildView(
                                context, config, COLOR_ALERTING,
                                context.getString(R.string.sos_alerting),
                                clickable = false,
                            ))
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
