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

private const val COLOR_SENDING = 0xFFE65100.toInt()
private const val COLOR_SENT    = 0xFF1B5E20.toInt()
private const val COLOR_ERROR   = 0xFFB71C1C.toInt()
private const val COLOR_OFF     = 0xFF424242.toInt()  // gray — slot disabled in Settings

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

    private fun titleFromConfig(config: KSafeConfig) = when (slot) {
        2 -> config.customMessage2Title.safeTake(7).ifBlank { "MSG2" }
        3 -> config.customMessage3Title.safeTake(7).ifBlank { "MSG3" }
        else -> config.customMessageTitle.safeTake(7).ifBlank { "MSG" }
    }

    private fun idleColorFromConfig(config: KSafeConfig) = when (slot) {
        2 -> config.customMsg2Color
        3 -> config.customMsg3Color
        else -> config.customMsg1Color
    }

    private fun enabledFromConfig(config: KSafeConfig) = when (slot) {
        2 -> config.customMessage2Enabled
        3 -> config.customMessage3Enabled
        else -> config.customMessageEnabled
    }

    /** Builds a field view with optional click PendingIntent. */
    private fun buildView(context: Context, viewConfig: ViewConfig, bgColor: Int, main: String, hint: String = "", clickable: Boolean = true): RemoteViews {
        // See CarbLogDataType.buildView — same layout-switch + center alignment
        // (tap-target field) + auto-mode text colour contract.
        val isAuto = bgColor == FIELD_COLOR_AUTO
        val layout = if (isAuto) R.layout.field_view_auto else R.layout.field_view
        val content = RemoteViews(context.packageName, layout).apply {
            if (!isAuto) setInt(R.id.field_container, "setBackgroundColor", bgColor)
            setTextViewText(R.id.field_text_main, main.take(9))   // hard cap — autoSize needs room
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
        if (!viewConfig.preview && clickable) {
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
                // See CarbLogDataType — Frame + distinctUntilChanged dedups identical frames
                // so an unrelated config edit doesn't force a wasted buildView + IPC.
                combine(
                    CustomMessageState.flowForSlot(slot),
                    configManager.loadConfigFlow()
                ) { state, ksafeConfig ->
                    val title = titleFromConfig(ksafeConfig)
                    val idleColor = idleColorFromConfig(ksafeConfig)
                    when {
                        !config.preview && !enabledFromConfig(ksafeConfig) ->
                            // Slot disabled in Actions tab — show OFF in grey. Skipped in
                            // preview so the profile-editor gallery shows the slot's
                            // configured idle colour and title, not the disabled-state grey.
                            Frame(COLOR_OFF, title, "OFF", clickable = false)
                        state == CustomMessageState.SENDING ->
                            Frame(COLOR_SENDING, title, "Sending…", clickable = false)
                        state == CustomMessageState.SENT ->
                            Frame(COLOR_SENT, title, "SENT ✓", clickable = false)
                        state == CustomMessageState.ERROR ->
                            Frame(COLOR_ERROR, title, "ERR retry", clickable = true)
                        else -> // IDLE
                            Frame(idleColor, title, "tap=send", clickable = true)
                    }
                }.distinctUntilChanged().collect { f ->
                    emitter.updateView(buildView(context, config, f.bgColor, f.main, f.hint, f.clickable))
                }
            } catch (_: CancellationException) {
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

    /** See [CarbLogDataType.Frame] — dedup snapshot for the upstream `combine`. */
    private data class Frame(
        val bgColor: Int,
        val main: String,
        val hint: String,
        val clickable: Boolean,
    )
}
