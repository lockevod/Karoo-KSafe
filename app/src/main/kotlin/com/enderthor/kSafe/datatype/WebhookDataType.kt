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
import com.enderthor.kSafe.data.FIELD_COLOR_AUTO
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.data.webhookSlot
import com.enderthor.kSafe.extension.managers.ConfigurationManager
import com.enderthor.kSafe.extension.util.safeTake
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import timber.log.Timber

private const val COLOR_DISABLED = 0xFF616161.toInt() // grey — not configured
private const val COLOR_FIRING   = 0xFFE65100.toInt() // orange — in progress
private const val COLOR_SUCCESS  = 0xFF1B5E20.toInt() // green  — fired OK
private const val COLOR_ERROR    = 0xFFB71C1C.toInt() // red    — failed

/**
 * Tappable data field for webhook slots 1..4.
 * Shows the configured label and fires the webhook when tapped.
 * Mirrors CustomMessageDataType: uses combine(WebhookState, configFlow) so the
 * initial view is rendered immediately (StateFlow emits synchronously) and the
 * tap PendingIntent is always registered.
 *
 * @param slot 1..4
 * requestCode: 106 = slot1, 107 = slot2, 108 = slot3, 109 = slot4
 */
class WebhookDataType(
    datatype: String,
    private val context: Context,
    private val karooSystem: KarooSystemService,
    private val slot: Int = 1,
) : DataTypeImpl("ksafe", datatype) {

    private val tapAction = when (slot) {
        1 -> FieldTapReceiver.ACTION_WEBHOOK_1
        2 -> FieldTapReceiver.ACTION_WEBHOOK_2
        3 -> FieldTapReceiver.ACTION_WEBHOOK_3
        else -> FieldTapReceiver.ACTION_WEBHOOK_4
    }

    private val requestCode = 105 + slot  // 106..109 for slots 1..4

    // Cached PendingIntent — see CarbLogDataType.
    @Volatile private var cachedPi: PendingIntent? = null
    private fun pendingIntentFor(context: Context): PendingIntent {
        cachedPi?.let { return it }
        return PendingIntent.getBroadcast(
            context, requestCode,
            Intent(tapAction).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        ).also { cachedPi = it }
    }

    private val configManager = ConfigurationManager(context)

    private fun labelFromConfig(config: KSafeConfig) =
        config.webhookSlot(slot).label.ifBlank { "WH$slot" }.safeTake(7)

    private fun isEnabled(config: KSafeConfig) = config.webhookSlot(slot).enabled

    private fun idleColorFromConfig(config: KSafeConfig) = config.webhookSlot(slot).color

    private fun buildView(
        context: Context,
        viewConfig: ViewConfig,
        bgColor: Int,
        main: String,
        hint: String = "",
        clickable: Boolean = true,
    ): RemoteViews {
        // See CarbLogDataType.buildView — same layout-switch + center alignment
        // (tap-target field) + auto-mode text colour contract.
        val isAuto = bgColor == FIELD_COLOR_AUTO
        val layout = if (isAuto) R.layout.field_view_auto else R.layout.field_view
        val content = RemoteViews(context.packageName, layout).apply {
            if (!isAuto) setInt(R.id.field_container, "setBackgroundColor", bgColor)
            setTextViewText(R.id.field_text_main, main.safeTake(9))
            setTextViewText(R.id.field_text_hint, hint.safeTake(9))
            setViewVisibility(R.id.field_text_hint, if (hint.isEmpty()) View.GONE else View.VISIBLE)
            setInt(R.id.field_text_main, "setGravity", Gravity.CENTER)
            setInt(R.id.field_text_hint, "setGravity", Gravity.CENTER)
            if (isAuto) {
                val dark = context.isKarooNightMode()
                setTextColor(R.id.field_text_main, if (dark) Color.WHITE else Color.BLACK)
                setTextColor(R.id.field_text_hint, if (dark) 0xCCFFFFFF.toInt() else 0xCC000000.toInt())
            }
        }
        // Always wrap in field_tap_wrapper in non-preview mode so the structural
        // RemoteViews layout stays identical across IDLE / FIRING / OFF. Karoo's
        // OS re-attaches the click handler whenever the top-level RemoteViews
        // structure changes; returning raw content on the non-clickable branches
        // would lose rapid taps during the structural swap. See CarbLogDataType.
        if (viewConfig.preview) return content
        val wrapper = RemoteViews(context.packageName, R.layout.field_tap_wrapper)
        if (clickable) {
            wrapper.setOnClickPendingIntent(R.id.field_tap_wrapper, pendingIntentFor(context))
        }
        wrapper.addView(R.id.field_tap_wrapper, content)
        return wrapper
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scopeJob = Job()
        val scope = CoroutineScope(Dispatchers.Default + scopeJob)

        val configJob = scope.launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            emitter.onNext(ShowCustomStreamState(message = "", color = null))
            awaitCancellation()
        }

        // NOTE: no separate "seed" coroutine here. A previous parallel `scope.launch`
        // that read `loadConfigFlow().first()` and emitted an initial frame raced the
        // `viewJob` combine below — both called `emitter.updateView` on Dispatchers.Default
        // with no ordering guarantee, so the slower seed could overwrite a fresher combine
        // frame and it bypassed distinctUntilChanged. The combine path already renders the
        // real config (label + idle colour) and a clickable IDLE frame — which registers the
        // tap PendingIntent — on its FIRST emission, because WebhookState.flowForSlot is a
        // StateFlow seeded with IDLE so the combine fires as soon as configFlow emits (the
        // same trigger the seed used). Dropping the seed removes the race, the redundant
        // build+IPC, and matches the "let the data flow's first emission be the only early
        // updateView" rule for coalescing Karoo firmware.
        val viewJob = scope.launch {
            try {
                // See CarbLogDataType — Frame + distinctUntilChanged dedups identical frames
                // so an unrelated config edit doesn't force a wasted buildView + IPC.
                combine(
                    WebhookState.flowForSlot(slot),
                    configManager.loadConfigFlow(),
                    com.enderthor.kSafe.extension.KSafeExtension.nightModeFlow,
                ) { stateData, ksafeConfig, dark ->
                    val label     = labelFromConfig(ksafeConfig)
                    // In preview always render as enabled — see note in the primer above.
                    val enabled   = config.preview || isEnabled(ksafeConfig)
                    val idleColor = idleColorFromConfig(ksafeConfig)
                    val frame = when (stateData.state) {
                        WebhookState.IDLE -> {
                            val bgColor = if (enabled) idleColor else COLOR_DISABLED
                            val hint    = if (enabled) context.getString(R.string.field_state_webhook_tap)
                                          else context.getString(R.string.field_state_webhook_disabled)
                            // Only wire the tap when enabled (parity with CustomMessageDataType):
                            // a disabled field should be inert, not flash a red "disabled" alert
                            // mid-ride when accidentally tapped.
                            Frame(bgColor, label, hint, clickable = enabled)
                        }
                        WebhookState.FIRING  -> Frame(COLOR_FIRING,  label, context.getString(R.string.field_state_webhook_firing), clickable = false)
                        WebhookState.SUCCESS -> Frame(COLOR_SUCCESS, label, stateData.message.ifBlank { context.getString(R.string.field_state_webhook_ok) }, clickable = false)
                        WebhookState.ERROR   -> Frame(COLOR_ERROR,   label, stateData.message.ifBlank { context.getString(R.string.field_state_err_retry) }, clickable = true)
                    }
                    // See CarbLogDataType — pair with `dark` so a theme flip re-renders.
                    frame to dark
                }.distinctUntilChanged().collect { (f, _) ->
                    emitter.updateView(buildView(context, config, f.bgColor, f.main, f.hint, f.clickable))
                }
            } catch (_: CancellationException) {
                // normal
            } catch (e: Exception) {
                Timber.e(e, "WebhookDataType slot=$slot error: ${e.message}")
            }
        }

        emitter.setCancellable {
            configJob.cancel()
            viewJob.cancel()
            scope.cancel()
            scopeJob.cancel()
        }
    }

    /** See [CarbLogDataType.Frame] — dedup snapshot for the upstream `combine`. */
    private data class Frame(
        val bgColor: Int,
        val main: String,
        val hint: String,
        val clickable: Boolean,
    )
}
