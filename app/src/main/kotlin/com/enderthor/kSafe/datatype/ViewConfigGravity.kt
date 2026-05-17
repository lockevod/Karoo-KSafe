package com.enderthor.kSafe.datatype

import android.content.Context
import android.content.res.Configuration
import android.view.Gravity
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
 * Only the four passive status fields (CarbStatus, HydrationStatus, CarbBurnRate,
 * CarbsBurned) actually call this — the tap-target fields (SOS, Timer, Custom
 * Message, Webhook, Carb/Hyd Log) are always rendered CENTERED because they're
 * action surfaces, not data readouts, and following per-field alignment on them
 * makes the field look broken when laid out next to a tappable native field.
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
