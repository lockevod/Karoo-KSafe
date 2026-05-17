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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.material3.OutlinedButton
import com.enderthor.kSafe.R
import com.enderthor.kSafe.activity.MainViewModel
import com.enderthor.kSafe.data.CarbRidePreset
import com.enderthor.kSafe.data.FUELING_ALERT_COLORS
import com.enderthor.kSafe.data.fuelingAlertColorRes
import com.enderthor.kSafe.extension.util.safeTake

@Composable
fun FuelingScreen(vm: MainViewModel) {
    val config by vm.config.collectAsState()

    // Carbs state
    var carbsEnabled         by remember(config.carbsTrackerEnabled)        { mutableStateOf(config.carbsTrackerEnabled) }
    var carbTarget           by remember(config.carbTargetGperHour)         { mutableStateOf(config.carbTargetGperHour.toString()) }
    var carbAlertBgColor     by remember(config.carbAlertBgColor)           { mutableStateOf(config.carbAlertBgColor) }
    var carbDeficitOn        by remember(config.carbDeficitAlertEnabled)    { mutableStateOf(config.carbDeficitAlertEnabled) }
    var carbDeficitThreshold by remember(config.carbDeficitThresholdG)      { mutableStateOf(config.carbDeficitThresholdG.toString()) }
    var carbDeficitInitialDelay by remember(config.carbDeficitInitialDelayMin) { mutableStateOf(config.carbDeficitInitialDelayMin.toString()) }
    var carbTimeOn           by remember(config.carbTimeAlertEnabled)       { mutableStateOf(config.carbTimeAlertEnabled) }
    var carbTimeInterval     by remember(config.carbTimeIntervalMin)        { mutableStateOf(config.carbTimeIntervalMin.toString()) }
    var carbTimeInitialDelay by remember(config.carbTimeInitialDelayMin)    { mutableStateOf(config.carbTimeInitialDelayMin.toString()) }
    var carbCustomTitle      by remember(config.carbAlertCustomTitle)        { mutableStateOf(config.carbAlertCustomTitle) }
    var carbCustomDetail     by remember(config.carbAlertCustomDetail)       { mutableStateOf(config.carbAlertCustomDetail) }
    var carb1Label           by remember(config.carb1Label)                  { mutableStateOf(config.carb1Label) }
    var carb1Grams           by remember(config.carb1Grams)                  { mutableStateOf(config.carb1Grams.toString()) }
    var carb1Color           by remember(config.carb1Color)                  { mutableStateOf(config.carb1Color) }
    var carb1Icon            by remember(config.carb1Icon)                   { mutableStateOf(config.carb1Icon) }
    var carb2Label           by remember(config.carb2Label)                  { mutableStateOf(config.carb2Label) }
    var carb2Grams           by remember(config.carb2Grams)                  { mutableStateOf(config.carb2Grams.toString()) }
    var carb2Color           by remember(config.carb2Color)                  { mutableStateOf(config.carb2Color) }
    var carb2Icon            by remember(config.carb2Icon)                   { mutableStateOf(config.carb2Icon) }
    var carb3Label           by remember(config.carb3Label)                  { mutableStateOf(config.carb3Label) }
    var carb3Grams           by remember(config.carb3Grams)                  { mutableStateOf(config.carb3Grams.toString()) }
    var carb3Color           by remember(config.carb3Color)                  { mutableStateOf(config.carb3Color) }
    var carb3Icon            by remember(config.carb3Icon)                   { mutableStateOf(config.carb3Icon) }

    // Hydration state
    var hydEnabled           by remember(config.hydrationTrackerEnabled)        { mutableStateOf(config.hydrationTrackerEnabled) }
    var hydTarget            by remember(config.hydrationTargetMlPerHour)       { mutableStateOf(config.hydrationTargetMlPerHour.toString()) }
    var hydDynamic           by remember(config.hydrationDynamicEstimateEnabled){ mutableStateOf(config.hydrationDynamicEstimateEnabled) }
    var hydAlertBgColor      by remember(config.hydrationAlertBgColor)          { mutableStateOf(config.hydrationAlertBgColor) }
    var hydDeficitOn         by remember(config.hydrationDeficitAlertEnabled)   { mutableStateOf(config.hydrationDeficitAlertEnabled) }
    var hydDeficitThreshold  by remember(config.hydrationDeficitThresholdMl)    { mutableStateOf(config.hydrationDeficitThresholdMl.toString()) }
    var hydDeficitInitialDelay by remember(config.hydrationDeficitInitialDelayMin) { mutableStateOf(config.hydrationDeficitInitialDelayMin.toString()) }
    var hydTimeOn            by remember(config.hydrationTimeAlertEnabled)      { mutableStateOf(config.hydrationTimeAlertEnabled) }
    var hydTimeInterval      by remember(config.hydrationTimeIntervalMin)       { mutableStateOf(config.hydrationTimeIntervalMin.toString()) }
    var hydTimeInitialDelay  by remember(config.hydrationTimeInitialDelayMin)   { mutableStateOf(config.hydrationTimeInitialDelayMin.toString()) }
    var hydCustomTitle       by remember(config.hydrationAlertCustomTitle)      { mutableStateOf(config.hydrationAlertCustomTitle) }
    var hydCustomDetail      by remember(config.hydrationAlertCustomDetail)     { mutableStateOf(config.hydrationAlertCustomDetail) }
    var drink1Label          by remember(config.drink1Label)                     { mutableStateOf(config.drink1Label) }
    var drink1Ml             by remember(config.drink1Ml)                        { mutableStateOf(config.drink1Ml.toString()) }
    var drink1Color          by remember(config.drink1Color)                     { mutableStateOf(config.drink1Color) }
    var drink1Icon           by remember(config.drink1Icon)                      { mutableStateOf(config.drink1Icon) }
    var drink2Label          by remember(config.drink2Label)                     { mutableStateOf(config.drink2Label) }
    var drink2Ml             by remember(config.drink2Ml)                        { mutableStateOf(config.drink2Ml.toString()) }
    var drink2Color          by remember(config.drink2Color)                     { mutableStateOf(config.drink2Color) }
    var drink2Icon           by remember(config.drink2Icon)                      { mutableStateOf(config.drink2Icon) }

    // Post-ride summary state

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.fueling_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        // Info banner
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD)),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Text(
                text = stringResource(R.string.fueling_info_banner),
                modifier = Modifier.padding(8.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // Carbs card
        Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.fueling_carb_section),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                FuelingRow(label = stringResource(R.string.fueling_enabled_label)) {
                    Switch(
                        checked = carbsEnabled,
                        onCheckedChange = {
                            carbsEnabled = it
                            vm.saveConfig(config.copy(carbsTrackerEnabled = it))
                        }
                    )
                }
                if (carbsEnabled) {
                CarbPresetRow(
                    currentTarget = carbTarget.toIntOrNull() ?: 0,
                    onPresetSelected = { preset ->
                        carbTarget = preset.gPerHour.toString()
                        vm.saveConfig(config.copy(carbTargetGperHour = preset.gPerHour))
                    },
                )
                IntField(
                    label = stringResource(R.string.fueling_target_carb_label),
                    text = carbTarget,
                    range = 20..120,
                    onCommit = { carbTarget = it; vm.saveConfig(config.copy(carbTargetGperHour = it.toInt())) },
                    onTextChange = { carbTarget = it },
                )
                Text(
                    text = stringResource(R.string.fueling_target_carb_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                FuelingRow(label = stringResource(R.string.fueling_alert_deficit_label)) {
                    Switch(
                        checked = carbDeficitOn,
                        onCheckedChange = {
                            carbDeficitOn = it
                            vm.saveConfig(config.copy(carbDeficitAlertEnabled = it))
                        }
                    )
                }
                IntField(
                    label = stringResource(R.string.fueling_deficit_threshold_g_label),
                    text = carbDeficitThreshold,
                    range = 5..60,
                    onCommit = { carbDeficitThreshold = it; vm.saveConfig(config.copy(carbDeficitThresholdG = it.toInt())) },
                    onTextChange = { carbDeficitThreshold = it },
                )
                IntField(
                    label = stringResource(R.string.fueling_deficit_initial_delay_label),
                    text = carbDeficitInitialDelay,
                    range = 0..240,
                    onCommit = { carbDeficitInitialDelay = it; vm.saveConfig(config.copy(carbDeficitInitialDelayMin = it.toInt())) },
                    onTextChange = { carbDeficitInitialDelay = it },
                )
                FuelingRow(label = stringResource(R.string.fueling_alert_time_label)) {
                    Switch(
                        checked = carbTimeOn,
                        onCheckedChange = {
                            carbTimeOn = it
                            vm.saveConfig(config.copy(carbTimeAlertEnabled = it))
                        }
                    )
                }
                IntField(
                    label = stringResource(R.string.fueling_time_interval_label),
                    text = carbTimeInterval,
                    range = 1..60,
                    onCommit = { carbTimeInterval = it; vm.saveConfig(config.copy(carbTimeIntervalMin = it.toInt())) },
                    onTextChange = { carbTimeInterval = it },
                )
                IntField(
                    label = stringResource(R.string.fueling_initial_delay_label),
                    text = carbTimeInitialDelay,
                    range = 0..240,
                    onCommit = { carbTimeInitialDelay = it; vm.saveConfig(config.copy(carbTimeInitialDelayMin = it.toInt())) },
                    onTextChange = { carbTimeInitialDelay = it },
                )
                CustomAlertField(
                    label = "Carb alert title",
                    value = carbCustomTitle,
                    onCommit = { v -> carbCustomTitle = v; vm.saveConfig(config.copy(carbAlertCustomTitle = v)) },
                    defaultText = stringResource(R.string.fueling_carb_alert_title),
                    tokensHint = "",
                    maxLength = 30,
                )
                CustomAlertField(
                    label = "Carb alert detail",
                    value = carbCustomDetail,
                    onCommit = { v -> carbCustomDetail = v; vm.saveConfig(config.copy(carbAlertCustomDetail = v)) },
                    defaultText = stringResource(R.string.fueling_carb_alert_detail_deficit),
                    tokensHint = "Tokens: {deficit}, {elapsed}, {target}",
                    maxLength = 80,
                    singleLine = false,
                )
                BeepPatternPicker(
                    label = stringResource(R.string.fueling_beep_pattern_label),
                    selected = config.carbBeepPattern,
                    onSelected = { v -> vm.saveConfig(config.copy(carbBeepPattern = v)) },
                )
                AlertColorPicker(
                    label = stringResource(R.string.fueling_alert_bg_color_label),
                    selected = carbAlertBgColor,
                    onSelected = { v -> carbAlertBgColor = v; vm.saveConfig(config.copy(carbAlertBgColor = v)) },
                )
                HorizontalDivider()
                Text(text = stringResource(R.string.fueling_items_section), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                val gLabel = stringResource(R.string.fueling_slot_grams_label)
                SlotRow(label = "Slot 1", labelText = carb1Label, amountText = carb1Grams, unitLabel = gLabel, range = 0..100,
                    onLabel = { v -> carb1Label = v.safeTake(8); vm.saveConfig(config.copy(carb1Label = v.safeTake(8))) },
                    onAmountCommit = { v -> carb1Grams = v; vm.saveConfig(config.copy(carb1Grams = v.toInt())) },
                    onAmountText = { carb1Grams = it },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldColorPicker(label = "Colour", selected = carb1Color, modifier = Modifier.weight(1f),
                        onSelected = { v -> carb1Color = v; vm.saveConfig(config.copy(carb1Color = v)) })
                    FieldEmojiPicker(label = "Icon", selected = carb1Icon, emojis = com.enderthor.kSafe.data.FUEL_EMOJI_CARB, modifier = Modifier.weight(1f),
                        onSelected = { v -> carb1Icon = v; vm.saveConfig(config.copy(carb1Icon = v)) })
                }
                SlotRow(label = "Slot 2", labelText = carb2Label, amountText = carb2Grams, unitLabel = gLabel, range = 0..100,
                    onLabel = { v -> carb2Label = v.safeTake(8); vm.saveConfig(config.copy(carb2Label = v.safeTake(8))) },
                    onAmountCommit = { v -> carb2Grams = v; vm.saveConfig(config.copy(carb2Grams = v.toInt())) },
                    onAmountText = { carb2Grams = it },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldColorPicker(label = "Colour", selected = carb2Color, modifier = Modifier.weight(1f),
                        onSelected = { v -> carb2Color = v; vm.saveConfig(config.copy(carb2Color = v)) })
                    FieldEmojiPicker(label = "Icon", selected = carb2Icon, emojis = com.enderthor.kSafe.data.FUEL_EMOJI_CARB, modifier = Modifier.weight(1f),
                        onSelected = { v -> carb2Icon = v; vm.saveConfig(config.copy(carb2Icon = v)) })
                }
                SlotRow(label = "Slot 3", labelText = carb3Label, amountText = carb3Grams, unitLabel = gLabel, range = 0..100,
                    onLabel = { v -> carb3Label = v.safeTake(8); vm.saveConfig(config.copy(carb3Label = v.safeTake(8))) },
                    onAmountCommit = { v -> carb3Grams = v; vm.saveConfig(config.copy(carb3Grams = v.toInt())) },
                    onAmountText = { carb3Grams = it },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldColorPicker(label = "Colour", selected = carb3Color, modifier = Modifier.weight(1f),
                        onSelected = { v -> carb3Color = v; vm.saveConfig(config.copy(carb3Color = v)) })
                    FieldEmojiPicker(label = "Icon", selected = carb3Icon, emojis = com.enderthor.kSafe.data.FUEL_EMOJI_CARB, modifier = Modifier.weight(1f),
                        onSelected = { v -> carb3Icon = v; vm.saveConfig(config.copy(carb3Icon = v)) })
                }
                }  // end if (carbsEnabled)
            }
        }

        // Hydration card
        Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.fueling_hyd_section),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                FuelingRow(label = stringResource(R.string.fueling_enabled_label)) {
                    Switch(
                        checked = hydEnabled,
                        onCheckedChange = {
                            hydEnabled = it
                            vm.saveConfig(config.copy(hydrationTrackerEnabled = it))
                        }
                    )
                }
                if (hydEnabled) {
                FuelingRow(label = stringResource(R.string.fueling_hyd_dynamic_label)) {
                    Switch(
                        checked = hydDynamic,
                        onCheckedChange = {
                            hydDynamic = it
                            vm.saveConfig(config.copy(hydrationDynamicEstimateEnabled = it))
                        }
                    )
                }
                Text(
                    text = stringResource(
                        if (hydDynamic) R.string.fueling_hyd_dynamic_hint_on
                        else            R.string.fueling_hyd_dynamic_hint_off
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!hydDynamic) {
                    IntField(
                        label = stringResource(R.string.fueling_target_hyd_label),
                        text = hydTarget,
                        range = 200..1500,
                        onCommit = { hydTarget = it; vm.saveConfig(config.copy(hydrationTargetMlPerHour = it.toInt())) },
                        onTextChange = { hydTarget = it },
                    )
                    Text(
                        text = stringResource(R.string.fueling_target_hyd_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
                FuelingRow(label = stringResource(R.string.fueling_alert_deficit_label)) {
                    Switch(
                        checked = hydDeficitOn,
                        onCheckedChange = {
                            hydDeficitOn = it
                            vm.saveConfig(config.copy(hydrationDeficitAlertEnabled = it))
                        }
                    )
                }
                IntField(
                    label = stringResource(R.string.fueling_deficit_threshold_ml_label),
                    text = hydDeficitThreshold,
                    range = 50..800,
                    onCommit = { hydDeficitThreshold = it; vm.saveConfig(config.copy(hydrationDeficitThresholdMl = it.toInt())) },
                    onTextChange = { hydDeficitThreshold = it },
                )
                IntField(
                    label = stringResource(R.string.fueling_deficit_initial_delay_label),
                    text = hydDeficitInitialDelay,
                    range = 0..240,
                    onCommit = { hydDeficitInitialDelay = it; vm.saveConfig(config.copy(hydrationDeficitInitialDelayMin = it.toInt())) },
                    onTextChange = { hydDeficitInitialDelay = it },
                )
                FuelingRow(label = stringResource(R.string.fueling_alert_time_label)) {
                    Switch(
                        checked = hydTimeOn,
                        onCheckedChange = {
                            hydTimeOn = it
                            vm.saveConfig(config.copy(hydrationTimeAlertEnabled = it))
                        }
                    )
                }
                IntField(
                    label = stringResource(R.string.fueling_time_interval_label),
                    text = hydTimeInterval,
                    range = 1..60,
                    onCommit = { hydTimeInterval = it; vm.saveConfig(config.copy(hydrationTimeIntervalMin = it.toInt())) },
                    onTextChange = { hydTimeInterval = it },
                )
                IntField(
                    label = stringResource(R.string.fueling_initial_delay_label),
                    text = hydTimeInitialDelay,
                    range = 0..240,
                    onCommit = { hydTimeInitialDelay = it; vm.saveConfig(config.copy(hydrationTimeInitialDelayMin = it.toInt())) },
                    onTextChange = { hydTimeInitialDelay = it },
                )
                CustomAlertField(
                    label = "Hydration alert title",
                    value = hydCustomTitle,
                    onCommit = { v -> hydCustomTitle = v; vm.saveConfig(config.copy(hydrationAlertCustomTitle = v)) },
                    defaultText = stringResource(R.string.fueling_hyd_alert_title),
                    tokensHint = "",
                    maxLength = 30,
                )
                CustomAlertField(
                    label = "Hydration alert detail",
                    value = hydCustomDetail,
                    onCommit = { v -> hydCustomDetail = v; vm.saveConfig(config.copy(hydrationAlertCustomDetail = v)) },
                    defaultText = stringResource(R.string.fueling_hyd_alert_detail_deficit),
                    tokensHint = "Tokens: {deficit}, {elapsed}, {target}",
                    maxLength = 80,
                    singleLine = false,
                )
                BeepPatternPicker(
                    label = stringResource(R.string.fueling_beep_pattern_label),
                    selected = config.hydBeepPattern,
                    onSelected = { v -> vm.saveConfig(config.copy(hydBeepPattern = v)) },
                )
                AlertColorPicker(
                    label = stringResource(R.string.fueling_alert_bg_color_label),
                    selected = hydAlertBgColor,
                    onSelected = { v -> hydAlertBgColor = v; vm.saveConfig(config.copy(hydrationAlertBgColor = v)) },
                )
                HorizontalDivider()
                Text(text = stringResource(R.string.fueling_items_section), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                val mlLabel = stringResource(R.string.fueling_slot_ml_label)
                SlotRow(label = "Slot 1", labelText = drink1Label, amountText = drink1Ml, unitLabel = mlLabel, range = 0..1000,
                    onLabel = { v -> drink1Label = v.safeTake(8); vm.saveConfig(config.copy(drink1Label = v.safeTake(8))) },
                    onAmountCommit = { v -> drink1Ml = v; vm.saveConfig(config.copy(drink1Ml = v.toInt())) },
                    onAmountText = { drink1Ml = it },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldColorPicker(label = "Colour", selected = drink1Color, modifier = Modifier.weight(1f),
                        onSelected = { v -> drink1Color = v; vm.saveConfig(config.copy(drink1Color = v)) })
                    FieldEmojiPicker(label = "Icon", selected = drink1Icon, emojis = com.enderthor.kSafe.data.FUEL_EMOJI_DRINK, modifier = Modifier.weight(1f),
                        onSelected = { v -> drink1Icon = v; vm.saveConfig(config.copy(drink1Icon = v)) })
                }
                SlotRow(label = "Slot 2", labelText = drink2Label, amountText = drink2Ml, unitLabel = mlLabel, range = 0..1000,
                    onLabel = { v -> drink2Label = v.safeTake(8); vm.saveConfig(config.copy(drink2Label = v.safeTake(8))) },
                    onAmountCommit = { v -> drink2Ml = v; vm.saveConfig(config.copy(drink2Ml = v.toInt())) },
                    onAmountText = { drink2Ml = it },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldColorPicker(label = "Colour", selected = drink2Color, modifier = Modifier.weight(1f),
                        onSelected = { v -> drink2Color = v; vm.saveConfig(config.copy(drink2Color = v)) })
                    FieldEmojiPicker(label = "Icon", selected = drink2Icon, emojis = com.enderthor.kSafe.data.FUEL_EMOJI_DRINK, modifier = Modifier.weight(1f),
                        onSelected = { v -> drink2Icon = v; vm.saveConfig(config.copy(drink2Icon = v)) })
                }
                }  // end if (hydEnabled)
            }
        }

        // Discreet footer with the GitHub docs reference. Karoo cannot open URLs from a
        // Compose Activity, so this is plain text the rider reads and looks up later
        // on their phone — same pattern as the provider descriptions.
        Text(
            text = stringResource(R.string.fueling_full_guide_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun FuelingRow(label: String, content: @Composable () -> Unit) {
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
private fun IntField(
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
            { Text("Allowed range: ${range.first}..${range.last}") }
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
private fun SlotRow(
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
 * Carb intake quick-presets row. Tapping a preset chip writes its g/h value into the
 * config and updates the IntField below. The chip whose [CarbRidePreset.gPerHour] matches
 * the current target is rendered as selected. Manual edits in the IntField unselect all
 * chips ("Custom" state — no separate enum stored, the current target value is the only
 * source of truth).
 */
@Composable
private fun CarbPresetRow(
    currentTarget: Int,
    onPresetSelected: (CarbRidePreset) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Quick preset — tap to fill the target below",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Plain OutlinedButtons — no selected/unselected state. The target IntField
            // below is the single source of truth for the rider's actual value, so a chip
            // with a "selected" highlight is confusing when the rider then types a custom
            // value (none would highlight, looking broken). Buttons are unambiguously
            // "tap-to-fill" actions; the IntField shows whatever the current value is.
            //
            // Short single-line labels are required by the Karoo's 480 px width — long
            // labels wrap and push the row tall, leaving empty space below.
            for (preset in CarbRidePreset.entries) {
                val shortLabel = when (preset) {
                    CarbRidePreset.CASUAL    -> "Casual"
                    CarbRidePreset.ENDURANCE -> "Endur."
                    CarbRidePreset.RACE      -> "Race"
                }
                OutlinedButton(
                    onClick = { onPresetSelected(preset) },
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "$shortLabel ${preset.gPerHour}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

/**
 * Inline swatch row for picking the InRideAlert background colour of a fueling tracker.
 * Limited palette (6 entries from [FUELING_ALERT_COLORS]) so the rider sees the choices
 * at a glance without opening a dialog — a full palette picker like FieldColorPicker is
 * overkill for this one decision. Selected swatch gets a white ring.
 */
@Composable
private fun AlertColorPicker(
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
