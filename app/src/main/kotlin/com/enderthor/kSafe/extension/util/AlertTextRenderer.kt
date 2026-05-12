package com.enderthor.kSafe.extension.util

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
    if (maxLength <= 0 || rendered.length <= maxLength) return rendered

    // String.take() operates on UTF-16 code units. Many of the emojis in FUEL_EMOJI_CARB /
    // FUEL_EMOJI_DRINK and the popular emojis riders may put inside their custom templates
    // are supplementary-plane and stored as surrogate pairs, so a naive `take(N - 1) + "…"`
    // can leave an unpaired high surrogate right before the ellipsis (visible as a tofu box
    // on most renderers). Snap the cut to a code-point boundary by stepping back one unit
    // when the cut would land between the two halves of a surrogate pair.
    val cutAt = maxLength - 1
    val safeCut = if (cutAt > 0 && rendered[cutAt - 1].isHighSurrogate()) cutAt - 1 else cutAt
    return rendered.take(safeCut) + "…"
}

/** Convenience overload — vararg variant for inline use at call sites. */
fun renderAlertText(template: String, vararg tokens: Pair<String, String>, maxLength: Int = -1): String =
    renderAlertText(template, tokens.toMap(), maxLength)

/**
 * Surrogate-pair-safe truncation. `String.take(N)` operates on UTF-16 code units, so an emoji
 * (supplementary-plane code point stored as a surrogate pair) can be split mid-pair, producing
 * a tofu box. This snaps the cut back one position when the would-be cut sits between two
 * halves of a surrogate pair.
 *
 * Use this in **any UI label, slot label, or alert text** that may contain rider-supplied
 * emoji — instead of plain `String.take(n)`. The popular emojis in `FUEL_EMOJI_CARB` /
 * `FUEL_EMOJI_DRINK` and any emoji a rider types into a custom message slot are all
 * supplementary-plane.
 */
fun String.safeTake(n: Int): String {
    if (n <= 0) return ""
    if (length <= n) return this
    val cutAt = if (this[n - 1].isHighSurrogate()) n - 1 else n
    return substring(0, cutAt)
}
