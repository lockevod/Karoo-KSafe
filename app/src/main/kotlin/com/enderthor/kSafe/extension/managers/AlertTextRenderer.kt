package com.enderthor.kSafe.extension.managers

/**
 * Hard cap on the rendered alert title length. The Karoo `InRideAlert` title must fit in a
 * single bold line at the top of the popup; anything past ~40 chars gets cut off. Riders can
 * type up to 30 chars in the UI input, so this constant only matters when token substitution
 * adds a few chars to push the rendered title slightly over the input cap.
 */
const val ALERT_TITLE_MAX_CHARS = 40

/**
 * Hard cap on the rendered alert detail. The Karoo popup wraps the detail to ~2 lines, which
 * fits roughly 80–90 chars depending on autoSize. We cap at 90 with an ellipsis so even the
 * worst-case rider template (80 chars input + token expansion) cannot exceed what the popup
 * can render.
 */
const val ALERT_DETAIL_MAX_CHARS = 90

/**
 * Substitutes `{token}` placeholders in alert title/detail templates with current data.
 *
 * Example:
 *   render("Behind by {deficit}g — {elapsed} min ago", mapOf("deficit" to "30", "elapsed" to "15"))
 *     → "Behind by 30g — 15 min ago"
 *
 * Tokens that aren't supplied are left as `{token}` literally rather than blanked, so a
 * misuse is visible to the rider rather than producing oddly-truncated text. This is by
 * design — riders authoring custom templates need the feedback when they typo a token.
 *
 * @param template  The template string. May be a custom rider template or a built-in default.
 * @param tokens    Values to substitute. Keys are token names without the curly braces.
 * @param maxLength If > 0, the rendered string is capped at this many characters. When the
 *                  rendered text exceeds the cap it is truncated and an ellipsis (`…`) is
 *                  appended in the last position. Use [ALERT_TITLE_MAX_CHARS] /
 *                  [ALERT_DETAIL_MAX_CHARS] at the call site so the rider's wording cannot
 *                  outrun the InRideAlert popup's visible area.
 */
fun renderAlertText(template: String, tokens: Map<String, String>, maxLength: Int = -1): String {
    val rendered = tokens.entries.fold(template) { acc, (k, v) -> acc.replace("{$k}", v) }
    return if (maxLength > 0 && rendered.length > maxLength) {
        rendered.take(maxLength - 1) + "…"
    } else {
        rendered
    }
}

/** Convenience overload — vararg variant for inline use at call sites. */
fun renderAlertText(template: String, vararg tokens: Pair<String, String>, maxLength: Int = -1): String =
    renderAlertText(template, tokens.toMap(), maxLength)
