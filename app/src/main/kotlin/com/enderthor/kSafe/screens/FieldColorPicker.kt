package com.enderthor.kSafe.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.enderthor.kSafe.data.FIELD_COLOR_PALETTE

/**
 * Compact colour picker inspired by timklge/karoo-reminder: a single full-width button shows
 * the current swatch + label and opens a dialog with the palette as a 4×3 grid.
 *
 * Saves vertical space vs. the previous inline row of swatches and scales naturally as the
 * palette grows.
 *
 * @param label       Text shown next to the swatch on the trigger button.
 * @param selected    Currently selected ARGB Int colour.
 * @param onSelected  Callback with the new ARGB Int when the user picks one in the dialog.
 */
@Composable
fun FieldColorPicker(
    label: String,
    selected: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    var dialogOpen by remember { mutableStateOf(false) }

    FilledTonalButton(
        modifier = modifier.height(52.dp),
        onClick = { dialogOpen = true },
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(Color(selected))
                .border(1.dp, Color(0x66FFFFFF), CircleShape)
        )
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }

    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text(label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FIELD_COLOR_PALETTE.chunked(4).forEach { rowColors ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            rowColors.forEach { colorInt ->
                                val isSelected = colorInt == selected
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(Color(colorInt))
                                        .then(
                                            if (isSelected)
                                                Modifier.border(3.dp, Color.White, CircleShape)
                                            else
                                                Modifier.border(1.dp, Color(0x44FFFFFF), CircleShape)
                                        )
                                        .clickable {
                                            onSelected(colorInt)
                                            dialogOpen = false
                                        }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { dialogOpen = false }) { Text("Close") }
            },
        )
    }
}
