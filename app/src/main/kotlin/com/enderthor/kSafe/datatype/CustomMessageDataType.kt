package com.enderthor.kSafe.datatype

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.enderthor.kSafe.R
import com.enderthor.kSafe.activity.FieldTapReceiver
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
import kotlinx.coroutines.launch
import timber.log.Timber

private val COLOR_IDLE    = 0xFF1565C0.toInt()
private val COLOR_SENDING = 0xFFE65100.toInt()
private val COLOR_SENT    = 0xFF1B5E20.toInt()
private val COLOR_ERROR   = 0xFFB71C1C.toInt()

class CustomMessageDataType(
    datatype: String,
    private val context: Context,
    private val karooSystem: KarooSystemService,
) : DataTypeImpl("ksafe", datatype) {

    /** Builds a field view with optional click PendingIntent (requestCode 103 = Custom Message). */
    private fun buildView(context: Context, config: ViewConfig, bgColor: Int, main: String, hint: String = "", clickable: Boolean = true): RemoteViews {
        val content = RemoteViews(context.packageName, R.layout.field_view).apply {
            setInt(R.id.field_container, "setBackgroundColor", bgColor)
            setTextViewText(R.id.field_text_main, main)
            setTextViewText(R.id.field_text_hint, hint)
            setViewVisibility(R.id.field_text_hint, if (hint.isEmpty()) View.GONE else View.VISIBLE)
        }
        if (!config.preview && clickable) {
            val pi = PendingIntent.getBroadcast(
                context, 103,
                Intent(FieldTapReceiver.ACTION_CUSTOM_MESSAGE).setPackage(context.packageName),
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
                CustomMessageState.flow.collect { state ->
                    emitter.updateView(when (state) {
                        CustomMessageState.IDLE    -> buildView(context, config, COLOR_IDLE, "MSG", "tap=send")
                        CustomMessageState.SENDING -> buildView(context, config, COLOR_SENDING, "Sending…", clickable = false)
                        CustomMessageState.SENT    -> buildView(context, config, COLOR_SENT, "MSG", "SENT ✓", clickable = false)
                        CustomMessageState.ERROR   -> buildView(context, config, COLOR_ERROR, "MSG", "ERROR tap=retry")
                    })
                }
            } catch (e: CancellationException) {
                // normal
            } catch (e: Exception) {
                Timber.e(e, "CustomMessageDataType error: ${e.message}")
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
