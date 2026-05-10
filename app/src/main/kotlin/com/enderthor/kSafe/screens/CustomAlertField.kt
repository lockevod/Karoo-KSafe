package com.enderthor.kSafe.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Reusable input for an alert title or detail "custom override" with placeholder defaults
 * and a tokens hint underneath.
 *
 * The rider sees the built-in default in the placeholder text when the field is empty;
 * the empty state means "use the default at runtime". Typing a value overrides — clearing
 * the field reverts to default.
 *
 * @param label              Label for the input (e.g. "Custom title").
 * @param value              Current saved override (may be empty).
 * @param onCommit           Called with the new value after each edit (for `vm.saveConfig`).
 * @param defaultPlaceholder Default text shown when the field is empty (e.g. "Eat something").
 * @param tokensHint         Supporting line listing the available tokens
 *                           (e.g. "Tokens: {deficit}, {elapsed}"). May be empty.
 * @param maxLength          Hard cap on the input length (default 80).
 * @param singleLine         True for single-line title fields; false for multi-line detail fields.
 */
@Composable
fun CustomAlertField(
    label: String,
    value: String,
    onCommit: (String) -> Unit,
    defaultPlaceholder: String,
    tokensHint: String = "",
    maxLength: Int = 80,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v ->
            val trimmed = v.take(maxLength)
            onCommit(trimmed)
        },
        label = { Text(label) },
        placeholder = { Text(defaultPlaceholder) },
        supportingText = if (tokensHint.isNotBlank()) {
            { Text(tokensHint) }
        } else null,
        singleLine = singleLine,
        modifier = Modifier.fillMaxWidth(),
    )
}
