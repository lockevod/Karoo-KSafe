package com.enderthor.kSafe.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Reusable input for an alert title or detail "custom override".
 *
 * UX choice: when the saved config value is empty, the input is **pre-filled with the
 * built-in default text** so the rider can see, copy and edit a fully-formed proposal.
 * (Material3 `OutlinedTextField` only shows `placeholder` while the field is focused, so
 * the rider would otherwise see a blank box — they explicitly asked for the default to
 * be visible at rest.)
 *
 * The empty config value is preserved as "follow the default" — both for blank input and
 * for input that exactly matches the default — so future default-string changes still
 * propagate to riders who didn't customise. Concretely:
 *  - saved=""  + edited to "X"        → saved="X"
 *  - saved="X" + edited to default    → saved="" (re-tracks default)
 *  - saved="X" + cleared              → saved="" (display falls back to default)
 *
 * The runtime renderer (extension/managers/AlertTextRenderer.kt) only sees the saved
 * config value, so this purely-cosmetic display layer doesn't affect alert dispatch.
 *
 * @param label             Label for the input (e.g. "Custom title").
 * @param value             Current saved override (may be empty = "use default").
 * @param onCommit          Called with the value to persist. Receives "" when the rider's
 *                          edit matches the default verbatim, or whatever they typed.
 * @param defaultText       Built-in default text shown when nothing is saved.
 * @param tokensHint        Supporting line listing the available tokens
 *                          (e.g. "Tokens: {deficit}, {elapsed}"). May be empty.
 * @param maxLength         Hard cap on the input length (default 80).
 * @param singleLine        True for single-line title fields; false for multi-line detail.
 */
@Composable
fun CustomAlertField(
    label: String,
    value: String,
    onCommit: (String) -> Unit,
    defaultText: String,
    tokensHint: String = "",
    maxLength: Int = 80,
    singleLine: Boolean = true,
) {
    val displayValue = value.ifBlank { defaultText }
    OutlinedTextField(
        value = displayValue,
        onValueChange = { v ->
            val clipped = v.take(maxLength)  // length-cap only — does NOT trim whitespace
            // Re-tracking the default when the rider's text exactly matches it keeps the
            // config field empty so future default-string changes still propagate.
            onCommit(if (clipped == defaultText) "" else clipped)
        },
        label = { Text(label) },
        supportingText = if (tokensHint.isNotBlank()) {
            { Text(tokensHint) }
        } else null,
        singleLine = singleLine,
        modifier = Modifier.fillMaxWidth(),
    )
}
