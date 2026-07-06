package com.enderthor.kSafe.datatype

import android.content.Context
import android.content.res.Configuration

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
