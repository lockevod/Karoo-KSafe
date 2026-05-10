package com.enderthor.kSafe.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enderthor.kSafe.R
import com.enderthor.kSafe.data.FUEL_BOTTLE_DRAWABLE
import com.enderthor.kSafe.data.FUEL_GEL_DRAWABLE

/** Maps a sentinel string from a fuel-icon palette to its drawable resource id, or `null`
 *  if the entry is just an emoji. Centralises the mapping so both the trigger preview and
 *  the dialog grid render bundled drawables consistently. */
private fun drawableForSentinel(s: String): Int? = when (s) {
    FUEL_GEL_DRAWABLE    -> R.drawable.ic_fuel_gel
    FUEL_BOTTLE_DRAWABLE -> R.drawable.ic_fuel_bottle
    else -> null
}

/**
 * Compact emoji picker — same compact-button-opens-dialog pattern as [FieldColorPicker].
 *
 * @param label       Text shown next to the current emoji on the trigger button.
 * @param selected    Currently chosen emoji (a single grapheme) or `""` for "no icon".
 * @param emojis      Palette to offer. The first entry is rendered with a hyphen swatch
 *                    so riders can clearly choose "no icon".
 * @param onSelected  Callback when the user picks one (passes the new emoji or `""`).
 */
@Composable
fun FieldEmojiPicker(
    label: String,
    selected: String,
    emojis: List<String>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    var dialogOpen by remember { mutableStateOf(false) }

    FilledTonalButton(
        modifier = modifier.height(48.dp),
        onClick = { dialogOpen = true },
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val sentinelDrawable = drawableForSentinel(selected)
            when {
                selected.isBlank()        -> Text("—", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                sentinelDrawable != null  -> Image(
                    painter = painterResource(sentinelDrawable),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                )
                else                      -> Text(selected, fontSize = 16.sp)
            }
        }
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
                    emojis.chunked(4).forEach { rowEmojis ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            rowEmojis.forEach { e ->
                                val isSelected = e == selected
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .border(
                                            if (isSelected) 2.dp else 1.dp,
                                            if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outline,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .clickable {
                                            onSelected(e)
                                            dialogOpen = false
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val cellDrawable = drawableForSentinel(e)
                                    when {
                                        e.isBlank()              -> Text("—", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        cellDrawable != null     -> Image(
                                            painter = painterResource(cellDrawable),
                                            contentDescription = null,
                                            modifier = Modifier.size(28.dp),
                                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                                        )
                                        else                     -> Text(e, fontSize = 24.sp)
                                    }
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
