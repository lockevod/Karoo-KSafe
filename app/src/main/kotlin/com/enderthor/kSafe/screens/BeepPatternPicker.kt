package com.enderthor.kSafe.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.enderthor.kSafe.R
import com.enderthor.kSafe.data.BeepPattern

/**
 * Compact picker for [BeepPattern], same UX as [FieldColorPicker]:
 *
 *   - Full-width button shows the current pattern's localised name.
 *   - Tap opens a dialog listing all patterns; tap a row to select and dismiss.
 *
 * 5 options are short enough that they could also fit in a `SingleChoiceSegmentedButtonRow`
 * on the wider phone settings screen, but the dialog pattern reads better next to the
 * existing colour pickers in the same Fueling card.
 */
@Composable
fun BeepPatternPicker(
    label: String,
    selected: BeepPattern,
    onSelected: (BeepPattern) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    var dialogOpen by remember { mutableStateOf(false) }
    val patternLabel = beepPatternLabel(selected)

    FilledTonalButton(
        modifier = modifier.height(48.dp),
        onClick = { dialogOpen = true },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            text = "$label · $patternLabel",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
        )
    }

    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text(label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BeepPattern.entries.forEach { pattern ->
                        val isSelected = pattern == selected
                        Text(
                            text = (if (isSelected) "• " else "   ") + beepPatternLabel(pattern),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelected(pattern)
                                    dialogOpen = false
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { dialogOpen = false }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun beepPatternLabel(pattern: BeepPattern): String = when (pattern) {
    BeepPattern.OFF -> stringResource(R.string.beep_pattern_off)
    BeepPattern.SINGLE_LONG -> stringResource(R.string.beep_pattern_single_long)
    BeepPattern.DOUBLE_SHORT -> stringResource(R.string.beep_pattern_double_short)
    BeepPattern.RISING_TRIPLE -> stringResource(R.string.beep_pattern_rising_triple)
    BeepPattern.URGENT_PULSE -> stringResource(R.string.beep_pattern_urgent_pulse)
}
