package com.enderthor.kSafe.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.enderthor.kSafe.R
import com.enderthor.kSafe.data.BeepPattern
import com.enderthor.kSafe.extension.KSafeExtension

/**
 * Compact picker for [BeepPattern]. The trigger renders as a settings-row-style
 * [OutlinedCard] (icon + 2-line label/value + chevron) so it reads clearly as a tappable
 * control alongside the colour pickers in the same Fueling / Health cards.
 *
 * Tapping the trigger opens a dialog with one radio row per preset. **Tapping any row
 * dispatches the corresponding [io.hammerhead.karooext.models.PlayBeepPattern]** through
 * the running [KSafeExtension]'s [io.hammerhead.karooext.KarooSystemService] so the rider
 * hears the preset they're considering — and selects it at the same time. The dialog stays
 * open so the rider can A/B test multiple presets without re-tapping the trigger; "Done"
 * closes it once they're happy.
 *
 * If the extension service isn't bound (rare in practice — the Karoo binds it on install),
 * preview silently no-ops. Selection still works because it round-trips through DataStore
 * via the picker's `onSelected` callback regardless of audio availability.
 */
@Composable
fun BeepPatternPicker(
    label: String,
    selected: BeepPattern,
    onSelected: (BeepPattern) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    var dialogOpen by remember { mutableStateOf(false) }
    val currentLabel = beepPatternLabel(selected)

    // ─── Trigger: settings-row-style card ───────────────────────────────────
    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable { dialogOpen = true },
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = true)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = currentLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }

    // ─── Dialog: radio rows, tap = preview + select ─────────────────────────
    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text(label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BeepPattern.entries.forEach { pattern ->
                        val isSelected = pattern == selected
                        val rowBackground = if (isSelected)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surface
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(rowBackground)
                                .clickable {
                                    onSelected(pattern)
                                    // Preview through the running extension service. Silently
                                    // no-ops if the service isn't bound or the preset is OFF.
                                    pattern.toPlayBeepPattern()?.let { beep ->
                                        KSafeExtension.getInstance()?.karooSystem?.dispatch(beep)
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = null,  // row absorbs the click
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = beepPatternLabel(pattern),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.weight(1f, fill = true),
                                )
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { dialogOpen = false }) {
                    Text(stringResource(R.string.beep_pattern_done))
                }
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
