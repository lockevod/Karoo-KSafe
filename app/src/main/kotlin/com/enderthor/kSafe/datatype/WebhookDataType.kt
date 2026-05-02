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
import kotlinx.coroutines.launch
import timber.log.Timber

private const val COLOR_IDLE    = 0xFF1565C0.toInt()  // blue — ready
private const val COLOR_DISABLED = 0xFF616161.toInt() // grey — not configured

/**
 * Tappable data field for webhook slot 1 or 2.
 * Shows the configured label and fires the webhook when tapped.
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
        config.webhook1Label.ifBlank { "WH1" }.take(5)
    else
        config.webhook2Label.ifBlank { "WH2" }.take(5)

    private fun isEnabled(config: KSafeConfig) = if (slot == 1)
        config.webhook1Enabled && config.webhook1Url.isNotBlank()
    else
        config.webhook2Enabled && config.webhook2Url.isNotBlank()

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

        val viewJob = scope.launch {
            try {
                configManager.loadConfigFlow().collect { ksafeConfig ->
                    val label = labelFromConfig(ksafeConfig)
                    val enabled = isEnabled(ksafeConfig)
                    val view = if (enabled) {
                        buildView(context, config, COLOR_IDLE, label, "tap")
                    } else {
                        buildView(context, config, COLOR_DISABLED, label, "off", clickable = false)
                    }
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

