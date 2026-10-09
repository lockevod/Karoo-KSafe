package com.enderthor.kSafe.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import com.enderthor.kSafe.R
import com.enderthor.kSafe.activity.MainViewModel
import com.enderthor.kSafe.data.FUELING_ALERT_COLORS
import com.enderthor.kSafe.data.FuelingAlertButtonMode
import com.enderthor.kSafe.data.fuelingAlertColorRes

@Composable
internal fun FuelingRow(label: String, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        content()
    }
}

/**
 * Numeric text field with persist-only-when-in-range commit.
 * Filters input to digits and updates the visible text on every keystroke;
 * calls [onCommit] only when the parsed integer is inside [range], so the saved
 * value never snaps to the lower bound while the user is still typing.
 */
@Composable
internal fun IntField(
    label: String,
    text: String,
    range: IntRange,
    onCommit: (String) -> Unit,
    onTextChange: (String) -> Unit,
) {
    // Out-of-range values are silently rejected by [onCommit] (DataStore stays at the
    // last valid value) — that used to mean the rider could type "1" into a 5..60 field
    // and the UI would show "1" while the actual saved value remained 20, with no
    // signal that the value wasn't being saved. Surface the rejection via `isError`
    // and a supporting line so the rider can see immediately when their input is being
    // ignored and why.
    val parsed = text.toIntOrNull()
    val outOfRange = parsed != null && parsed !in range
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            val filtered = v.filter { it.isDigit() }.take(4)
            onTextChange(filtered)
            val p = filtered.toIntOrNull()
            if (p != null && p in range) onCommit(filtered)
        },
        label = { Text(label) },
        isError = outOfRange,
        supportingText = if (outOfRange) {
            { Text(stringResource(R.string.fueling_allowed_range, range.first, range.last)) }
        } else null,
        // Numeric keypad on the Karoo's soft keyboard with an explicit Done action so the
        // rider can dismiss the IME with one tap instead of swiping it away. Done also
        // releases focus, which is the natural "commit + close" gesture for a single
        // numeric field.
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * A row with a slot name, the label text input, and the amount input. Both text fields use
 * `label = { … }` so they have the same vertical alignment — without a label the right field
 * would render slightly higher than the left because the floating-label area would be missing.
 */
@Composable
internal fun SlotRow(
    label: String,
    labelText: String,
    amountText: String,
    unitLabel: String,
    range: IntRange,
    onLabel: (String) -> Unit,
    onAmountCommit: (String) -> Unit,
    onAmountText: (String) -> Unit,
) {
    val parsedAmount = amountText.toIntOrNull()
    val amountOutOfRange = parsedAmount != null && parsedAmount !in range
    val keyboard = LocalSoftwareKeyboardController.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = labelText,
                onValueChange = { onLabel(it) },
                label = { Text(stringResource(R.string.fueling_slot_label_label)) },
                modifier = Modifier.weight(2f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
            )
            OutlinedTextField(
                value = amountText,
                onValueChange = { v ->
                    val filtered = v.filter { it.isDigit() }.take(4)
                    onAmountText(filtered)
                    val parsed = filtered.toIntOrNull()
                    if (parsed != null && parsed in range) onAmountCommit(filtered)
                },
                label = { Text(unitLabel) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                isError = amountOutOfRange,
                // Numeric keypad with Done — same UX as IntField. Same out-of-range signal
                // so the rider sees when a slot's amount value is being silently rejected.
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
            )
        }
    }
}

/**
 * Generic single-row segmented selector for a small enum: a bold [label] above a row of
 * equal-weight buttons, the [selected] one filled and the rest outlined. Fits the 480 px Karoo
 * screen for 2–3 short options. Shared by the fueling-alert-mode picker and [RiderSexRow] so the
 * Karoo segmented-control styling (weights, padding, selected look) lives in exactly one place.
 */
@Composable
internal fun <T> EnumSegmentedRow(
    label: String,
    entries: List<T>,
    selected: T,
    labelOf: @Composable (T) -> String,
    onSelected: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // Short labels chosen so 2–3 buttons don't wrap on the smaller K3 screen.
            for (option in entries) {
                val text = labelOf(option)
                val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                if (option == selected) {
                    Button(onClick = { onSelected(option) }, modifier = Modifier.weight(1f), contentPadding = pad) {
                        Text(text = text, style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    OutlinedButton(onClick = { onSelected(option) }, modifier = Modifier.weight(1f), contentPadding = pad) {
                        Text(text = text, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/**
 * Discrete picker for the deficit-reminder repeat interval. Mirrors
 * [RiderSexRow]'s outlined-when-unselected / filled-when-selected pattern but
 * over a fixed 4-value minute grid (5 / 10 / 15 / 30). The grid points are the
 * only values that make sense for a "you're still behind" reminder cadence on
 * endurance rides — anything finer-grained nags the rider, anything coarser
 * makes a sustained deficit go too quiet.
 *
 * Off-grid `selected` (a legacy persisted value like 12 from when this was a
 * free-text field) snaps to the nearest grid point for HIGHLIGHTING only —
 * the persisted value isn't silently rewritten, so a user who never opens
 * Settings keeps their old value. The first deliberate tap commits a valid
 * grid value via [onSelected].
 */
@Composable
internal fun MinutesPickerRow(
    label: String,
    hint: String,
    selected: Int,
    options: List<Int> = listOf(5, 10, 15, 30),
    onSelected: (Int) -> Unit,
) {
    val highlighted = remember(selected) {
        options.minByOrNull { kotlin.math.abs(it - selected) } ?: options.first()
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (option in options) {
                val display = "${option}m"
                if (option == highlighted) {
                    Button(
                        onClick = { onSelected(option) },
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                    ) {
                        Text(text = display, style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    OutlinedButton(
                        onClick = { onSelected(option) },
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                    ) {
                        Text(text = display, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Inline swatch row for picking the InRideAlert background colour of a fueling tracker.
 * Limited palette (6 entries from [FUELING_ALERT_COLORS]) so the rider sees the choices
 * at a glance without opening a dialog — a full palette picker like FieldColorPicker is
 * overkill for this one decision. Selected swatch gets a white ring.
 */
@Composable
internal fun AlertColorPicker(
    label: String,
    selected: Int,
    onSelected: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        // Swatches share row width via weight(1f). With N entries each takes 1/N of the
        // available width minus gaps — this scales automatically as the palette grows or
        // shrinks (no per-size manual tuning needed) and guarantees the row never
        // overflows the 480 dp Karoo screen no matter how many colours we ship.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (sentinel in FUELING_ALERT_COLORS) {
                val isSelected = sentinel == selected
                val swatchColor = colorResource(id = fuelingAlertColorRes(sentinel))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .background(color = swatchColor, shape = CircleShape)
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) Color.White else Color(0x33000000),
                            shape = CircleShape,
                        )
                        .clickable { onSelected(sentinel) },
                )
            }
        }
    }
}

/** Decimal weight field: accepts "69.4" or "69,4". */
@Composable
internal fun DecimalField(label: String, text: String, onTextChange: (String) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = text,
        onValueChange = { v -> onTextChange(v.filter { it.isDigit() || it == '.' || it == ',' }.take(6)) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Alert-mode card. Controls whether fueling alerts (carbs + hydration) show a one-tap log
 * button, a log+undo pair, or no button at all. One config field shared by both channels,
 * so the same card appears on the Carbs and the Hydration tab.
 */
@Composable
internal fun FuelingAlertButtonModeCard(vm: MainViewModel, selected: FuelingAlertButtonMode) {
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EnumSegmentedRow(
                label = stringResource(R.string.fueling_alert_button_mode_label),
                entries = FuelingAlertButtonMode.entries,
                selected = selected,
                labelOf = { mode ->
                    when (mode) {
                        FuelingAlertButtonMode.OFF      -> stringResource(R.string.fueling_alert_button_off)
                        FuelingAlertButtonMode.LOG      -> stringResource(R.string.fueling_alert_button_log)
                        FuelingAlertButtonMode.LOG_UNDO -> stringResource(R.string.fueling_alert_button_log_undo)
                    }
                },
                onSelected = { mode -> vm.updateConfig { it.copy(fuelingAlertButtonMode = mode) } },
            )
            Text(
                text = stringResource(R.string.fueling_alert_button_mode_shared_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
