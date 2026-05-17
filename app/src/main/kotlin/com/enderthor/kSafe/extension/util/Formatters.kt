package com.enderthor.kSafe.extension.util

import java.util.Locale

/**
 * Locale-independent variant of Kotlin's built-in `String.format(vararg)` extension.
 * The standard extension uses the JVM default Locale, which on Spanish / French /
 * German devices renders `%.1f` of `1.5` as `"1,5"`. That breaks two things:
 *  - The calibration CSV uses comma as a field separator, so a Locale-default
 *    decimal turns one column into two and every column to the right shifts.
 *  - Numeric values embedded in user-visible messages (distance, speed) are read
 *    by riders who expect a fixed format regardless of phone Locale.
 *
 * Use `"%.1f".formatUs(value)` everywhere a number lands in a CSV row, a log line
 * destined for export, or a UI string the rider may share. Keep the JVM default
 * `.format()` only for purely internal Timber traces that nobody parses.
 */
fun String.formatUs(vararg args: Any?): String = String.format(Locale.US, this, *args)
