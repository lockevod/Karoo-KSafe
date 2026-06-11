package com.enderthor.kSafe.datatype

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import com.enderthor.kSafe.R
import io.hammerhead.karooext.models.ViewConfig

/**
 * Map the rider's per-field alignment choice (set in the Karoo profile editor and
 * delivered via [ViewConfig.alignment] since karoo-ext 1.1.2) to an Android
 * [Gravity] flag suitable for `RemoteViews.setInt(viewId, "setGravity", …)`.
 *
 * Vertical centering is always added so the text sits in the middle of the field
 * regardless of horizontal alignment — same convention native Karoo fields use.
 *
 * Default on older SDKs (or when the rider has not changed alignment) is RIGHT,
 * which matches the karoo-ext documented default and what native fields show.
 *
 * Only the three passive numeric fields (CarbBurnRate, CarbAvgBurnRate,
 * CarbsBurned) actually call this — they render a pure number where the
 * rider's alignment choice matters. CarbStatus / HydrationStatus are
 * semaphore-style status fields and explicitly centre their text regardless
 * of profile alignment (a left-aligned semaphore icon next to a centred one
 * looks broken). The tap-target fields (SOS, Timer, Custom Message,
 * Webhook, Carb/Hyd Log) are also always rendered CENTERED because they're
 * action surfaces, not data readouts, and following per-field alignment on
 * them makes the field look broken when laid out next to a tappable native
 * field.
 */
internal fun ViewConfig.fieldGravity(): Int = when (alignment) {
    ViewConfig.Alignment.LEFT   -> Gravity.START  or Gravity.CENTER_VERTICAL
    ViewConfig.Alignment.CENTER -> Gravity.CENTER
    ViewConfig.Alignment.RIGHT  -> Gravity.END    or Gravity.CENTER_VERTICAL
}

/**
 * True when the Karoo's system-wide UI mode is set to night. Used by the
 * Karoo-theme passthrough layout (`field_view_auto.xml`) to pick a text colour
 * that contrasts with the host's day/night background — white text on black at
 * night, black text on white during the day.
 *
 * Why we don't rely on `?android:attr/textColorPrimary` baked into the XML: the
 * theme attribute resolves against whatever theme the host inflates the
 * RemoteViews with, which on Karoo did not match the actual rendered background
 * in testing — riders reported white text on a white field (invisible). Reading
 * `Configuration.UI_MODE_NIGHT_MASK` from the extension's context is system-wide
 * and matches the bg that the Karoo OS will draw underneath the field.
 */
internal fun Context.isKarooNightMode(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/** Accent tint for the readout metric icon. A teal that stays legible on both the
 *  white (day) and black (night) Karoo-theme backgrounds — same idea as the teal icons
 *  KDouble / native fields use. */
private const val READOUT_ICON_ACCENT = 0xFF0097A7.toInt()

/**
 * Build the standard-Karoo readout view (`field_view_readout.xml`) shared by the five
 * passive numeric info fields: an accent-tinted [iconRes] + units/label on top, the big
 * bold value centred in the cell below — the native / KDouble data-field shape.
 *
 * The value font size comes from [ViewConfig.textSize] — the per-cell size the Karoo
 * host hands every native field and what KDouble uses — so our numbers render at the
 * same size as native fields instead of the old 22sp auto-size cap that made them look
 * small. The long "Pair HR/Pwr" placeholder (and any other word-y state) is rendered
 * smaller so it still fits the cell width; pure numbers and the `---` placeholder use
 * the full host size. The value is centred vertically in the whole cell (not the lower
 * region) so it doesn't look bottom-heavy. Horizontal gravity follows the rider's
 * per-field alignment (see [fieldGravity]); the units line shares it.
 */
internal fun Context.buildReadoutView(
    viewConfig: ViewConfig,
    value: String,
    units: String,
    iconRes: Int,
): RemoteViews {
    val gravity = viewConfig.fieldGravity()
    val dark = isKarooNightMode()
    // A word-y state (e.g. "Pair HR/Pwr") carries a space; numbers and "---" do not.
    // Shrink only the word-y states so they fit the width; numbers get the full size.
    val hostSize = viewConfig.textSize.coerceAtLeast(1)
    // Only the LONG word-y placeholder ("Pair HR/Pwr") needs to shrink to fit the
    // width. Detect it by an ASCII letter — NOT by a space, because a real value like
    // the avg-burn "ø 76" carries a space (and "ø" itself is a Unicode letter) — AND
    // by length: short word states like "OFF" fit at full size, and shrinking them to
    // 42 % next to full-size '---' siblings looked broken.
    val wordy = value.length > 4 && value.any { it in 'a'..'z' || it in 'A'..'Z' }
    val valueSizeSp = if (wordy) hostSize * 0.42f else hostSize.toFloat()
    return RemoteViews(packageName, R.layout.field_view_readout).apply {
        if (iconRes != 0) {
            setImageViewResource(R.id.field_icon, iconRes)
            setInt(R.id.field_icon, "setColorFilter", READOUT_ICON_ACCENT)
            setViewVisibility(R.id.field_icon, View.VISIBLE)
        } else {
            setViewVisibility(R.id.field_icon, View.GONE)
        }
        setTextViewText(R.id.field_text_hint, units.take(9))
        setViewVisibility(R.id.field_text_hint, if (units.isEmpty()) View.GONE else View.VISIBLE)
        setTextViewText(R.id.field_text_main, value.take(11))
        setTextViewTextSize(R.id.field_text_main, TypedValue.COMPLEX_UNIT_SP, valueSizeSp)
        setInt(R.id.field_text_main, "setGravity", gravity)
        setInt(R.id.field_text_hint, "setGravity", gravity)
        setTextColor(R.id.field_text_main, if (dark) Color.WHITE else Color.BLACK)
        setTextColor(R.id.field_text_hint, if (dark) 0xCCFFFFFF.toInt() else 0xCC000000.toInt())
    }
}
