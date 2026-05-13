package com.enderthor.kSafe.datatype

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
 */
internal fun ViewConfig.fieldGravity(): Int = when (alignment) {
    ViewConfig.Alignment.LEFT   -> Gravity.START  or Gravity.CENTER_VERTICAL
    ViewConfig.Alignment.CENTER -> Gravity.CENTER
    ViewConfig.Alignment.RIGHT  -> Gravity.END    or Gravity.CENTER_VERTICAL
}
