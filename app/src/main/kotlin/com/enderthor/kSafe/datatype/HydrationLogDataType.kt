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
private const val COLOR_UNDONE = 0xFFB71C1C.toInt()  // red — undo confirmation flash
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
        // See CarbLogDataType.buildView — same layout-switch + center alignment
        // (tap-target field, not data display) + auto-mode text colour contract.
        val isAuto = bgColor == FIELD_COLOR_AUTO
        val layout = if (isAuto) R.layout.field_view_auto else R.layout.field_view
        val content = RemoteViews(context.packageName, layout).apply {
            if (!isAuto) setInt(R.id.field_container, "setBackgroundColor", bgColor)
            // safeTake — see CarbLogDataType.buildView for the surrogate-pair rationale.
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
            // Compound drawable on the main TextView. Setting all four to 0 explicitly
            // CLEARS any drawable from a previous LOGGED → IDLE transition.
            setTextViewCompoundDrawables(R.id.field_text_main, leftDrawableRes, 0, 0, 0)
        }
        // See CarbLogDataType — always wrap in field_tap_wrapper in non-preview mode
        // so Karoo doesn't re-attach the click handler on state transitions and rapid
        // taps stop being lost. Only the PendingIntent attachment varies by state.
        if (viewConfig.preview) return content
        val wrapper = RemoteViews(context.packageName, R.layout.field_tap_wrapper)
        if (clickable) {
            val pi = PendingIntent.getBroadcast(
                context, requestCode,
                Intent(tapAction).setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            wrapper.setOnClickPendingIntent(R.id.field_tap_wrapper, pi)
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

        val viewJob = scope.launch {
            try {
                combine(
                    HydrationLogState.flowForSlot(slot),
                    configManager.loadConfigFlow()
                ) { state, ksafeConfig ->
                    val label = labelFromConfig(ksafeConfig)
                    val ml = mlFromConfig(ksafeConfig)
                    // See CarbLogDataType — pick the dark-fill drawable when the slot's
                    // idle background is FIELD_COLOR_AUTO and the Karoo is in day mode,
                    // otherwise the white default would vanish on the host's white bg.
                    val idleIsAutoDay =
                        idleColorFromConfig(ksafeConfig) == FIELD_COLOR_AUTO &&
                        !context.isKarooNightMode()
                    val leftDrawable = if (iconFromConfig(ksafeConfig) == FUEL_BOTTLE_DRAWABLE) {
                        if (idleIsAutoDay) R.drawable.ic_fuel_bottle_dark else R.drawable.ic_fuel_bottle
                    } else 0
                    if (!config.preview && !ksafeConfig.hydrationTrackerEnabled) {
                        // Master tracker disabled — show OFF in grey. Skipped in preview
                        // so the profile-editor gallery shows the slot's configured idle
                        // colour and label, not the disabled-state grey.
                        buildView(context, config, COLOR_OFF, label, "OFF", clickable = false)
                    } else when (state) {
                        HydrationLogState.IDLE    -> buildView(context, config, idleColorFromConfig(ksafeConfig), label, "${ml}ml", leftDrawableRes = leftDrawable)
                        // LOGGED stays tappable for the ~5 s undo window so a second tap
                        // on the same slot reverses the entry. Hint advertises the action.
                        HydrationLogState.LOGGED  -> buildView(context, config, COLOR_LOGGED, "+${ml}ml", "TAP UNDO")
                        // UNDONE is the brief red "−Xml ✓" confirmation after a successful
                        // undo. Tappable so a third quick tap re-logs immediately — see
                        // CarbLogDataType for the rationale.
                        HydrationLogState.UNDONE  -> buildView(context, config, COLOR_UNDONE, "−${ml}ml", "✓")
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
