package com.enderthor.kSafe.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.enderthor.kSafe.data.FIELD_COLOR_PALETTE

/**
 * A compact row of colour swatches for choosing the idle background colour of a ride field.
 *
 * @param label       Short label shown above the swatches (e.g. "SOS field colour").
 * @param selected    Currently selected ARGB Int colour.
 * @param onSelected  Callback with the new ARGB Int when user taps a swatch.
 */
@Composable
fun FieldColorPicker(
    label: String,
    selected: Int,
    onSelected: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FIELD_COLOR_PALETTE.forEach { colorInt ->
                val isSelected = colorInt == selected
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(colorInt))
                        .then(
                            if (isSelected)
                                Modifier.border(2.5.dp, Color.White, CircleShape)
                            else
                                Modifier.border(1.dp, Color(0x44FFFFFF), CircleShape)
                        )
                        .clickable { onSelected(colorInt) }
                )
            }
        }
    }
}

