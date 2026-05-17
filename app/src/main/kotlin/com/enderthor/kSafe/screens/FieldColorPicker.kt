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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.PaddingValues
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
import com.enderthor.kSafe.data.FIELD_COLOR_AUTO
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
        modifier = modifier.height(48.dp),
        onClick = { dialogOpen = true },
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    ) {
        SwatchPreview(colorInt = selected, sizeDp = 22)
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
        )
    }

    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text(label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // AUTO sits alone on the first row so it reads as the special "Karoo
                    // default" option rather than getting visually lumped with the warm-earth
                    // hues that lead the painted palette. Cached: the palette is a top-level
                    // constant — partition + chunked produce the same result every
                    // recomposition, no need to recompute on each profile-editor redraw.
                    val rows: List<List<Int>> = remember {
                        val (auto, painted) = FIELD_COLOR_PALETTE.partition { it == FIELD_COLOR_AUTO }
                        auto.map { listOf(it) } + painted.chunked(4)
                    }
                    rows.forEach { rowColors ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            rowColors.forEach { colorInt ->
                                val isSelected = colorInt == selected
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
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
                                ) {
                                    SwatchFill(colorInt)
                                }
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

/**
 * The 38 dp circular fill used inside each swatch in the dialog. Renders the
 * [FIELD_COLOR_AUTO] sentinel as a half-white / half-black split ("day on the
 * left, night on the right") so it reads at a glance as "auto theme" without
 * any text label; every other entry is a solid colour fill.
 */
@Composable
private fun SwatchFill(colorInt: Int) {
    if (colorInt == FIELD_COLOR_AUTO) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight().background(Color.White))
            Box(Modifier.weight(1f).fillMaxHeight().background(Color.Black))
        }
    } else {
        Box(Modifier.fillMaxSize().background(Color(colorInt)))
    }
}

/**
 * Small swatch shown in the trigger button next to the picker label. Mirrors
 * [SwatchFill] but sized for the inline button and wrapped in the original
 * shadow + border styling.
 */
@Composable
private fun SwatchPreview(colorInt: Int, sizeDp: Int) {
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .shadow(3.dp, CircleShape)
            .clip(CircleShape)
            .border(1.dp, Color(0x66FFFFFF), CircleShape)
    ) {
        SwatchFill(colorInt)
    }
}
