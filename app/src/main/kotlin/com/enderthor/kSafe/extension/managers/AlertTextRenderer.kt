package com.enderthor.kSafe.extension.managers

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
 */
fun renderAlertText(template: String, tokens: Map<String, String>): String =
    tokens.entries.fold(template) { acc, (k, v) -> acc.replace("{$k}", v) }

/** Convenience overload — vararg variant for inline use at call sites. */
fun renderAlertText(template: String, vararg tokens: Pair<String, String>): String =
    renderAlertText(template, tokens.toMap())
