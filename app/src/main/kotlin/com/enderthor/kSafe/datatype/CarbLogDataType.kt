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
import com.enderthor.kSafe.data.FUEL_GEL_DRAWABLE
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

private const val COLOR_LOGGED = 0xFF1B5E20.toInt()  // green confirmation flash
private const val COLOR_UNDONE = 0xFFB71C1C.toInt()  // red — undo confirmation flash
private const val COLOR_OFF    = 0xFF424242.toInt()  // gray — master tracker disabled

class CarbLogDataType(
    datatype: String,
    private val context: Context,
    private val karooSystem: KarooSystemService,
    private val slot: Int = 1,
) : DataTypeImpl("ksafe", datatype) {

    private val tapAction = when (slot) {
        2 -> FieldTapReceiver.ACTION_CARB_LOG_2
        3 -> FieldTapReceiver.ACTION_CARB_LOG_3
        else -> FieldTapReceiver.ACTION_CARB_LOG_1
    }
    // requestCode: 110, 111, 112 — distinct per slot to avoid PendingIntent collisions
    private val requestCode = 109 + slot

    private val configManager = ConfigurationManager(context)

    private fun iconFromConfig(c: KSafeConfig): String = when (slot) {
        2 -> c.carb2Icon
        3 -> c.carb3Icon
        else -> c.carb1Icon
    }

    private fun labelFromConfig(c: KSafeConfig): String {
        val raw = when (slot) {
            2 -> c.carb2Label.safeTake(7).ifBlank { "Carb 2" }
            3 -> c.carb3Label.safeTake(7).ifBlank { "Carb 3" }
            else -> c.carb1Label.safeTake(7).ifBlank { "Carb 1" }
        }
        val icon = iconFromConfig(c)
        // FUEL_GEL_DRAWABLE renders as a left compound drawable on the TextView
        // (handled in buildView), so we must NOT prefix the label with the sentinel.
        return when {
            icon.isBlank()                     -> raw
            icon == FUEL_GEL_DRAWABLE          -> raw
            else                               -> "$icon $raw"
        }
    }

    private fun gramsFromConfig(c: KSafeConfig): Int = when (slot) {
        2 -> c.carb2Grams
        3 -> c.carb3Grams
        else -> c.carb1Grams
    }

    private fun idleColorFromConfig(c: KSafeConfig): Int = when (slot) {
        2 -> c.carb2Color
        3 -> c.carb3Color
        else -> c.carb1Color
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
        // Pick layout: FIELD_COLOR_AUTO sentinel → theme-aware layout (no custom bg,
        // text colour set explicitly from night-mode detection). Any real ARGB →
        // coloured layout with white text on the picked bg. State branches always
        // pass a real colour so they stay loud (white-on-state).
        // Tap-target field — always render text CENTERED. Per-field alignment from
        // ViewConfig is reserved for the four passive status / info fields (carb
        // burn rate, carbs burned, deficit fields) where it actually reads like
        // data; on action surfaces, alignment makes the field look off-balance
        // next to its neighbours.
        val isAuto = bgColor == FIELD_COLOR_AUTO
        val layout = if (isAuto) R.layout.field_view_auto else R.layout.field_view
        val content = RemoteViews(context.packageName, layout).apply {
            if (!isAuto) setInt(R.id.field_container, "setBackgroundColor", bgColor)
            // safeTake (not .take) — labels can contain rider-picked emoji from
            // FUEL_EMOJI_CARB (🍫 🍌 etc.) which are supplementary-plane code points
            // that .take() would split on a surrogate-pair boundary, producing a tofu
            // box. The label feeding into here is already safeTake'd to 7 chars in
            // labelFromConfig, so this is defensive — but the LOGGED/UNDONE branches
            // pass "+${grams}g" etc. directly, and a future hint change could include
            // emoji too. Keep both call sites surrogate-pair safe by default.
            setTextViewText(R.id.field_text_main, main.safeTake(9))
            setTextViewText(R.id.field_text_hint, hint.safeTake(9))
            setViewVisibility(R.id.field_text_hint, if (hint.isEmpty()) View.GONE else View.VISIBLE)
            setInt(R.id.field_text_main, "setGravity", Gravity.CENTER)
            setInt(R.id.field_text_hint, "setGravity", Gravity.CENTER)
            if (isAuto) {
                // Theme-aware text in auto mode — must match the host's day/night bg.
                val dark = context.isKarooNightMode()
                setTextColor(R.id.field_text_main, if (dark) Color.WHITE else Color.BLACK)
                setTextColor(R.id.field_text_hint, if (dark) 0xCCFFFFFF.toInt() else 0xCC000000.toInt())
            }
            // Compound drawables on the main TextView. Setting all four to 0 explicitly
            // CLEARS any drawable from a previous LOGGED → IDLE transition.
            setTextViewCompoundDrawables(R.id.field_text_main, leftDrawableRes, 0, 0, 0)
        }
        // Always wrap in field_tap_wrapper in non-preview mode so the structural
        // RemoteViews layout stays identical across IDLE / LOGGED / UNDONE / OFF.
        // Karoo's OS re-attaches the click handler whenever the top-level RemoteViews
        // structure changes; before this fix the OFF / UNDONE branches returned the
        // raw content (no wrapper) and rapid taps during the structural swap were
        // being lost. Only the PendingIntent attachment varies now — the wrapper
        // itself is always present.
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
                    CarbLogState.flowForSlot(slot),
                    configManager.loadConfigFlow()
                ) { state, ksafeConfig ->
                    val label = labelFromConfig(ksafeConfig)
                    val grams = gramsFromConfig(ksafeConfig)
                    // Pick the dark-fill variant when the slot's idle background is
                    // FIELD_COLOR_AUTO and the Karoo is in day mode — the host then
                    // paints a white field bg, so the white default drawable would be
                    // invisible. Night-mode + AUTO and every painted-palette colour
                    // (always dark) keep the regular white drawable.
                    val idleIsAutoDay =
                        idleColorFromConfig(ksafeConfig) == FIELD_COLOR_AUTO &&
                        !context.isKarooNightMode()
                    val leftDrawable = if (iconFromConfig(ksafeConfig) == FUEL_GEL_DRAWABLE) {
                        if (idleIsAutoDay) R.drawable.ic_fuel_gel_dark else R.drawable.ic_fuel_gel
                    } else 0
                    if (!config.preview && !ksafeConfig.carbsTrackerEnabled) {
                        // Master tracker disabled — show OFF in grey. Skipped in preview
                        // so the profile-editor gallery shows the slot's configured idle
                        // colour and label, not the disabled-state grey.
                        buildView(context, config, COLOR_OFF, label, "OFF", clickable = false)
                    } else when (state) {
                        CarbLogState.IDLE    -> buildView(context, config, idleColorFromConfig(ksafeConfig), label, "${grams}g", leftDrawableRes = leftDrawable)
                        // LOGGED stays tappable for the ~5 s undo window so a second tap
                        // on the same slot reverses the entry. Hint advertises the action.
                        CarbLogState.LOGGED  -> buildView(context, config, COLOR_LOGGED, "+${grams}g", "TAP UNDO")
                        // UNDONE is the brief red "−Xg ✓" confirmation after a successful
                        // undo. Tappable so a third quick tap re-logs (mis-tap recovery
                        // without waiting for the 1.5 s auto-reset to IDLE — handled in
                        // KSafeExtension.handleCarbLogTap by falling through to the
                        // regular log path).
                        CarbLogState.UNDONE  -> buildView(context, config, COLOR_UNDONE, "−${grams}g", "✓")
                    }
                }.collect { view -> emitter.updateView(view) }
            } catch (_: CancellationException) {
                // normal
            } catch (e: Exception) {
                Timber.e(e, "CarbLogDataType slot=$slot error: ${e.message}")
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
