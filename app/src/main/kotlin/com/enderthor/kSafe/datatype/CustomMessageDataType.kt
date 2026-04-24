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
import kotlinx.coroutines.launch
import timber.log.Timber

private val COLOR_IDLE    = 0xFF1565C0.toInt()
private val COLOR_SENDING = 0xFFE65100.toInt()
private val COLOR_SENT    = 0xFF1B5E20.toInt()
private val COLOR_ERROR   = 0xFFB71C1C.toInt()

/**
 * @param slot 1, 2 or 3 — determines which state flow and PendingIntent to use.
 */
class CustomMessageDataType(
    datatype: String,
    private val context: Context,
    private val karooSystem: KarooSystemService,
    private val slot: Int = 1,
) : DataTypeImpl("ksafe", datatype) {

    private val tapAction = when (slot) {
        2 -> FieldTapReceiver.ACTION_CUSTOM_MESSAGE_2
        3 -> FieldTapReceiver.ACTION_CUSTOM_MESSAGE_3
        else -> FieldTapReceiver.ACTION_CUSTOM_MESSAGE
    }
    // requestCode: 103 = slot1, 104 = slot2, 105 = slot3
    private val requestCode = 102 + slot

    private val configManager = ConfigurationManager(context)

    private fun titleFromConfig(config: KSafeConfig) = when (slot) {
        2 -> config.customMessage2Title.take(5).ifBlank { "MSG2" }
        3 -> config.customMessage3Title.take(5).ifBlank { "MSG3" }
        else -> config.customMessageTitle.take(5).ifBlank { "MSG" }
    }

    /** Builds a field view with optional click PendingIntent. */
    private fun buildView(context: Context, viewConfig: ViewConfig, bgColor: Int, main: String, hint: String = "", clickable: Boolean = true): RemoteViews {
        val content = RemoteViews(context.packageName, R.layout.field_view).apply {
            setInt(R.id.field_container, "setBackgroundColor", bgColor)
            setTextViewText(R.id.field_text_main, main.take(9))   // hard cap — autoSize needs room
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

        val viewJob = scope.launch {
            try {
                combine(
                    CustomMessageState.flowForSlot(slot),
                    configManager.loadConfigFlow()
                ) { state, ksafeConfig ->
                    val title = titleFromConfig(ksafeConfig)
                    when (state) {
                        CustomMessageState.IDLE    -> buildView(context, config, COLOR_IDLE, title, "tap=send")
                        CustomMessageState.SENDING -> buildView(context, config, COLOR_SENDING, title, "Sending…", clickable = false)
                        CustomMessageState.SENT    -> buildView(context, config, COLOR_SENT, title, "SENT ✓", clickable = false)
                        CustomMessageState.ERROR   -> buildView(context, config, COLOR_ERROR, title, "ERR retry")
                    }
                }.collect { view ->
                    emitter.updateView(view)
                }
            } catch (e: CancellationException) {
                // normal
            } catch (e: Exception) {
                Timber.e(e, "CustomMessageDataType slot=$slot error: ${e.message}")
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
