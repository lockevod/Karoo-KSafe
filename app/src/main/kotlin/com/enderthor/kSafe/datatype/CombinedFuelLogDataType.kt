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

private const val COLOR_LOGGED = 0xFF1B5E20.toInt()
private const val COLOR_UNDONE = 0xFFB71C1C.toInt()
private const val COLOR_OFF    = 0xFF424242.toInt()

class CombinedFuelLogDataType(
    datatype: String,
    private val context: Context,
    private val karooSystem: KarooSystemService,
    private val slot: Int = 1,
) : DataTypeImpl("ksafe", datatype) {

    private val tapAction = when (slot) {
        2 -> FieldTapReceiver.ACTION_COMBINED_LOG_2
        else -> FieldTapReceiver.ACTION_COMBINED_LOG_1
    }
    private val requestCode = 129 + slot

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

    private fun labelFromConfig(c: KSafeConfig): String = when (slot) {
        2 -> c.combined2Label.safeTake(7).ifBlank { "Combo 2" }
        else -> c.combined1Label.safeTake(7).ifBlank { "Combo 1" }
    }

    private fun mlFromConfig(c: KSafeConfig): Int = if (slot == 2) c.combined2Ml else c.combined1Ml
    private fun carbsFromConfig(c: KSafeConfig): Int = if (slot == 2) c.combined2Carbs else c.combined1Carbs
    private fun idleColorFromConfig(c: KSafeConfig): Int = if (slot == 2) c.combined2Color else c.combined1Color

    /** "250ml · 30g" / "250ml" / "30g" depending on which trackers are on. */
    private fun amountsHint(c: KSafeConfig): String {
        val parts = buildList {
            if (c.hydrationTrackerEnabled) add("${mlFromConfig(c)}ml")
            if (c.carbsTrackerEnabled) add("${carbsFromConfig(c)}g")
        }
        return parts.joinToString(" · ")
    }

    private fun buildView(
        context: Context,
        viewConfig: ViewConfig,
        bgColor: Int,
        main: String,
        hint: String = "",
        clickable: Boolean = true,
        leftDrawableRes: Int = 0,
    ): RemoteViews {
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
            setTextViewCompoundDrawables(R.id.field_text_main, leftDrawableRes, 0, 0, 0)
        }
        if (viewConfig.preview) return content
        val wrapper = RemoteViews(context.packageName, R.layout.field_tap_wrapper)
        if (clickable) wrapper.setOnClickPendingIntent(R.id.field_tap_wrapper, pendingIntentFor(context))
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
        val viewJob = scope.launch {
            try {
                combine(
                    CombinedFuelLogState.flowForSlot(slot),
                    configManager.loadConfigFlow(),
                    com.enderthor.kSafe.extension.KSafeExtension.nightModeFlow,
                ) { state, ksafeConfig, dark ->
                    val label = labelFromConfig(ksafeConfig)
                    val bothOff = !ksafeConfig.carbsTrackerEnabled && !ksafeConfig.hydrationTrackerEnabled
                    val idleIsAutoDay = idleColorFromConfig(ksafeConfig) == FIELD_COLOR_AUTO && !dark
                    val leftDrawable = if (idleIsAutoDay) R.drawable.ic_fuel_combined_dark else R.drawable.ic_fuel_combined
                    val frame = when {
                        !config.preview && bothOff ->
                            Frame(COLOR_OFF, label, context.getString(R.string.field_state_off), clickable = false, leftDrawableRes = 0)
                        state is CombinedFuelLogState.LOGGED ->
                            Frame(COLOR_LOGGED, loggedText(state.ml, state.grams), context.getString(R.string.field_state_tap_undo), clickable = true, leftDrawableRes = 0)
                        state is CombinedFuelLogState.UNDONE ->
                            Frame(COLOR_UNDONE, undoneText(state.ml, state.grams), "✓", clickable = true, leftDrawableRes = 0)
                        else ->
                            Frame(idleColorFromConfig(ksafeConfig), label, amountsHint(ksafeConfig), clickable = true, leftDrawableRes = leftDrawable)
                    }
                    frame to dark
                }.distinctUntilChanged().collect { (f, _) ->
                    emitter.updateView(buildView(context, config, f.bgColor, f.main, f.hint, f.clickable, f.leftDrawableRes))
                }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                Timber.e(e, "CombinedFuelLogDataType slot=$slot error: ${e.message}")
            }
        }
        emitter.setCancellable {
            configJob.cancel(); viewJob.cancel(); scope.cancel(); scopeJob.cancel()
        }
    }

    private fun loggedText(ml: Int, grams: Int): String = listOfNotNull(
        if (ml > 0) "+${ml}ml" else null, if (grams > 0) "+${grams}g" else null
    ).joinToString(" ").ifBlank { "+" }

    private fun undoneText(ml: Int, grams: Int): String = listOfNotNull(
        if (ml > 0) "−${ml}ml" else null, if (grams > 0) "−${grams}g" else null
    ).joinToString(" ").ifBlank { "−" }

    private data class Frame(val bgColor: Int, val main: String, val hint: String, val clickable: Boolean, val leftDrawableRes: Int)
}
