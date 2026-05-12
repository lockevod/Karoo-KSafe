package com.enderthor.kSafe.datatype

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.enderthor.kSafe.R
import com.enderthor.kSafe.activity.FieldTapReceiver
import com.enderthor.kSafe.data.FUEL_BOTTLE_DRAWABLE
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
import kotlinx.coroutines.launch
import timber.log.Timber

private const val COLOR_LOGGED = 0xFF1B5E20.toInt()
private const val COLOR_OFF    = 0xFF424242.toInt()

class HydrationLogDataType(
    datatype: String,
    private val context: Context,
    private val karooSystem: KarooSystemService,
    private val slot: Int = 1,
) : DataTypeImpl("ksafe", datatype) {

    private val tapAction = when (slot) {
        2 -> FieldTapReceiver.ACTION_HYDRATION_LOG_2
        else -> FieldTapReceiver.ACTION_HYDRATION_LOG_1
    }
    // requestCode: 120, 121
    private val requestCode = 119 + slot

    private val configManager = ConfigurationManager(context)

    private fun iconFromConfig(c: KSafeConfig): String = when (slot) {
        2 -> c.drink2Icon
        else -> c.drink1Icon
    }

    private fun labelFromConfig(c: KSafeConfig): String {
        val raw = when (slot) {
            2 -> c.drink2Label.safeTake(7).ifBlank { "Drink 2" }
            else -> c.drink1Label.safeTake(7).ifBlank { "Drink 1" }
        }
        val icon = iconFromConfig(c)
        // FUEL_BOTTLE_DRAWABLE renders as a left compound drawable on the TextView
        // (handled in buildView), so we must NOT prefix the label with the sentinel.
        return when {
            icon.isBlank()                      -> raw
            icon == FUEL_BOTTLE_DRAWABLE        -> raw
            else                                -> "$icon $raw"
        }
    }

    private fun mlFromConfig(c: KSafeConfig): Int = when (slot) {
        2 -> c.drink2Ml
        else -> c.drink1Ml
    }

    private fun idleColorFromConfig(c: KSafeConfig): Int = when (slot) {
        2 -> c.drink2Color
        else -> c.drink1Color
    }

    private fun buildView(
        context: Context,
        viewConfig: ViewConfig,
        bgColor: Int,
        main: String,
        hint: String = "",
        clickable: Boolean = true,
        leftDrawableRes: Int = 0,   // 0 = no compound drawable
    ): RemoteViews {
        val content = RemoteViews(context.packageName, R.layout.field_view).apply {
            setInt(R.id.field_container, "setBackgroundColor", bgColor)
            setTextViewText(R.id.field_text_main, main.take(9))
            setTextViewText(R.id.field_text_hint, hint.take(9))
            setViewVisibility(R.id.field_text_hint, if (hint.isEmpty()) View.GONE else View.VISIBLE)
            // Compound drawable on the main TextView. Setting all four to 0 explicitly
            // CLEARS any drawable from a previous LOGGED → IDLE transition.
            setTextViewCompoundDrawables(R.id.field_text_main, leftDrawableRes, 0, 0, 0)
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
                    HydrationLogState.flowForSlot(slot),
                    configManager.loadConfigFlow()
                ) { state, ksafeConfig ->
                    val label = labelFromConfig(ksafeConfig)
                    val ml = mlFromConfig(ksafeConfig)
                    val leftDrawable = if (iconFromConfig(ksafeConfig) == FUEL_BOTTLE_DRAWABLE) {
                        R.drawable.ic_fuel_bottle
                    } else 0
                    if (!ksafeConfig.hydrationTrackerEnabled) {
                        // Slot disabled — no drawable, just the OFF label.
                        buildView(context, config, COLOR_OFF, label, "OFF", clickable = false)
                    } else when (state) {
                        HydrationLogState.IDLE   -> buildView(context, config, idleColorFromConfig(ksafeConfig), label, "${ml}ml", leftDrawableRes = leftDrawable)
                        // LOGGED is the brief green "+500ml ✓" success flash — no drawable.
                        HydrationLogState.LOGGED -> buildView(context, config, COLOR_LOGGED, "+${ml}ml", "✓", clickable = false)
                    }
                }.collect { view -> emitter.updateView(view) }
            } catch (_: CancellationException) {
                // normal
            } catch (e: Exception) {
                Timber.e(e, "HydrationLogDataType slot=$slot error: ${e.message}")
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
