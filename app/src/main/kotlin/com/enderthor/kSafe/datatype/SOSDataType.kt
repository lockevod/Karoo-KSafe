package com.enderthor.kSafe.datatype

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.enderthor.kSafe.R
import com.enderthor.kSafe.activity.FieldTapReceiver
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

private val COLOR_SAFE      = 0xFF1B5E20.toInt()
private val COLOR_COUNTDOWN = 0xFFE65100.toInt()
private val COLOR_ALERTING  = 0xFFB71C1C.toInt()

class SOSDataType(
    datatype: String,
    private val context: Context,
    private val karooSystem: KarooSystemService,
) : DataTypeImpl("ksafe", datatype) {

    /** Builds a field view with optional click PendingIntent (requestCode 101 = SOS). */
    private fun buildView(context: Context, config: ViewConfig, bgColor: Int, main: String, hint: String = "", clickable: Boolean = true): RemoteViews {
        val content = RemoteViews(context.packageName, R.layout.field_view).apply {
            setInt(R.id.field_container, "setBackgroundColor", bgColor)
            setTextViewText(R.id.field_text_main, main.take(9))
            setTextViewText(R.id.field_text_hint, hint.take(9))
            setViewVisibility(R.id.field_text_hint, if (hint.isEmpty()) View.GONE else View.VISIBLE)
        }
        if (!config.preview && clickable) {
            val pi = PendingIntent.getBroadcast(
                context, 101,
                Intent(FieldTapReceiver.ACTION_SOS).setPackage(context.packageName),
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
                while (true) {
                    val state = EmergencyManager.uiState.value
                    when (state.status) {
                        EmergencyStatus.IDLE -> {
                            emitter.updateView(buildView(context, config, COLOR_SAFE, "SAFE", "tap=SOS"))
                            EmergencyManager.uiState.first { it.status != EmergencyStatus.IDLE }
                        }
                        EmergencyStatus.COUNTDOWN -> {
                            val secs = state.countdownRemaining()
                            emitter.updateView(buildView(context, config, COLOR_COUNTDOWN, "SOS ${secs}s", "tap=cancel"))
                            delay(1_000L)
                        }
                        EmergencyStatus.ALERTING -> {
                            emitter.updateView(buildView(context, config, COLOR_ALERTING, "ALERT\nSENT", clickable = false))
                            EmergencyManager.uiState.first { it.status != EmergencyStatus.ALERTING }
                        }
                    }
                }
            } catch (e: CancellationException) {
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
