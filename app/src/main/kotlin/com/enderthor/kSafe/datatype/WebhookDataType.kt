package com.enderthor.kSafe.datatype

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.enderthor.kSafe.R
import com.enderthor.kSafe.activity.FieldTapReceiver
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.extension.managers.ConfigurationManager
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

private const val COLOR_DISABLED = 0xFF616161.toInt() // grey — not configured
private const val COLOR_FIRING   = 0xFFE65100.toInt() // orange — in progress
private const val COLOR_SUCCESS  = 0xFF1B5E20.toInt() // green  — fired OK
private const val COLOR_ERROR    = 0xFFB71C1C.toInt() // red    — failed

/**
 * Tappable data field for webhook slot 1 or 2.
 * Shows the configured label and fires the webhook when tapped.
 * Mirrors CustomMessageDataType: uses combine(WebhookState, configFlow) so the
 * initial view is rendered immediately (StateFlow emits synchronously) and the
 * tap PendingIntent is always registered.
 *
 * @param slot 1 or 2
 * requestCode: 106 = slot1, 107 = slot2
 */
class WebhookDataType(
    datatype: String,
    private val context: Context,
    private val karooSystem: KarooSystemService,
    private val slot: Int = 1,
) : DataTypeImpl("ksafe", datatype) {

    private val tapAction = if (slot == 1)
        FieldTapReceiver.ACTION_WEBHOOK_1
    else
        FieldTapReceiver.ACTION_WEBHOOK_2

    private val requestCode = 105 + slot  // 106 for slot1, 107 for slot2

    private val configManager = ConfigurationManager(context)

    private fun labelFromConfig(config: KSafeConfig) = if (slot == 1)
        config.webhook1Label.ifBlank { "WH1" }.take(7)
    else
        config.webhook2Label.ifBlank { "WH2" }.take(7)

    private fun isEnabled(config: KSafeConfig) = if (slot == 1)
        config.webhook1Enabled
    else
        config.webhook2Enabled

    private fun idleColorFromConfig(config: KSafeConfig) = if (slot == 1)
        config.webhook1Color
    else
        config.webhook2Color

    private fun buildView(
        context: Context,
        viewConfig: ViewConfig,
        bgColor: Int,
        main: String,
        hint: String = "",
        clickable: Boolean = true,
    ): RemoteViews {
        val content = RemoteViews(context.packageName, R.layout.field_view).apply {
            setInt(R.id.field_container, "setBackgroundColor", bgColor)
            setTextViewText(R.id.field_text_main, main.take(9))
            setTextViewText(R.id.field_text_hint, hint.take(9))
            setViewVisibility(R.id.field_text_hint, if (hint.isEmpty()) View.GONE else View.VISIBLE)
        }
        if (!viewConfig.preview && clickable) {
            val pi = PendingIntent.getBroadcast(
                context, requestCode,
                Intent(tapAction).setPackage(context.packageName),
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

        // Immediate initial clickable view with real config — ensures PendingIntent is always
        // registered from the first frame without showing a generic "WH1/WH2" placeholder.
        scope.launch {
            val initialConfig = runCatching { configManager.loadConfigFlow().first() }.getOrNull()
            if (initialConfig != null) {
                val label     = labelFromConfig(initialConfig)
                val enabled   = isEnabled(initialConfig)
                val idleColor = idleColorFromConfig(initialConfig)
                val bgColor   = if (enabled) idleColor else COLOR_DISABLED
                val hint      = if (enabled) "tap" else "off"
                emitter.updateView(buildView(context, config, bgColor, label, hint))
            }
            // If DataStore hasn't emitted yet (very rare), the combine below will render the first view
        }

        val viewJob = scope.launch {
            try {
                combine(
                    WebhookState.flowForSlot(slot),
                    configManager.loadConfigFlow()
                ) { stateData, ksafeConfig ->
                    val label     = labelFromConfig(ksafeConfig)
                    val enabled   = isEnabled(ksafeConfig)
                    val idleColor = idleColorFromConfig(ksafeConfig)
                    when (stateData.state) {
                        WebhookState.IDLE    -> {
                            val bgColor = if (enabled) idleColor else COLOR_DISABLED
                            val hint    = if (enabled) "tap" else "off"
                            buildView(context, config, bgColor, label, hint)
                        }
                        WebhookState.FIRING  -> buildView(context, config, COLOR_FIRING,  label, "firing…", clickable = false)
                        WebhookState.SUCCESS -> buildView(context, config, COLOR_SUCCESS,  label, stateData.message.ifBlank { "OK ✓" }, clickable = false)
                        WebhookState.ERROR   -> buildView(context, config, COLOR_ERROR,    label, stateData.message.ifBlank { "ERR retry" })
                    }
                }.collect { view ->
                    emitter.updateView(view)
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
}
