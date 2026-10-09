package com.enderthor.kSafe.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.enderthor.kSafe.R
import com.enderthor.kSafe.activity.MainViewModel
import com.enderthor.kSafe.extension.KSafeExtension
import com.enderthor.kSafe.data.RiderSex
import com.enderthor.kSafe.extension.util.ALERT_DETAIL_MAX_CHARS
import com.enderthor.kSafe.extension.util.safeTake

@Composable
fun CarbsScreen(vm: MainViewModel) {
    val config by vm.config.collectAsState()

    // Rider physiology (v18) — drives the CarbBurnEstimator
    var riderAge             by remember(config.riderAge)                   { mutableStateOf(if (config.riderAge > 0) config.riderAge.toString() else "") }
    var riderSex             by remember(config.riderSex)                   { mutableStateOf(config.riderSex) }

    // Carbs state
    var carbsEnabled         by remember(config.carbsTrackerEnabled)        { mutableStateOf(config.carbsTrackerEnabled) }
    var caloriesEnabled      by remember(config.hrCaloriesEnabled)          { mutableStateOf(config.hrCaloriesEnabled) }
    var carbAlertBgColor     by remember(config.carbAlertBgColor)           { mutableStateOf(config.carbAlertBgColor) }
    var carbDeficitOn        by remember(config.carbDeficitAlertEnabled)    { mutableStateOf(config.carbDeficitAlertEnabled) }
    var carbDeficitThreshold by remember(config.carbDeficitThresholdG)      { mutableStateOf(config.carbDeficitThresholdG.toString()) }
    var carbDeficitInitialDelay by remember(config.carbDeficitInitialDelayMin) { mutableStateOf(config.carbDeficitInitialDelayMin.toString()) }
    var carbTimeOn           by remember(config.carbTimeAlertEnabled)       { mutableStateOf(config.carbTimeAlertEnabled) }
    var carbTimeInterval     by remember(config.carbTimeIntervalMin)        { mutableStateOf(config.carbTimeIntervalMin.toString()) }
    var carbTimeInitialDelay by remember(config.carbTimeInitialDelayMin)    { mutableStateOf(config.carbTimeInitialDelayMin.toString()) }
    var carbCustomTitle      by remember(config.carbAlertCustomTitle)        { mutableStateOf(config.carbAlertCustomTitle) }
    var carbCustomDetailTime    by remember(config.carbAlertCustomDetailTime)    { mutableStateOf(config.carbAlertCustomDetailTime) }
    var carbCustomDetailDeficit by remember(config.carbAlertCustomDetailDeficit) { mutableStateOf(config.carbAlertCustomDetailDeficit) }
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.tab_carbs),
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
                style = MaterialTheme.typography.bodyMedium,
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
                            vm.updateConfig { cfg -> cfg.copy(carbsTrackerEnabled = it) }
                        }
                    )
                }
                if (carbsEnabled) {
                HorizontalDivider()
                FuelingRow(label = stringResource(R.string.fueling_alert_deficit_label)) {
                    Switch(
                        checked = carbDeficitOn,
                        onCheckedChange = {
                            carbDeficitOn = it
                            vm.updateConfig { cfg -> cfg.copy(carbDeficitAlertEnabled = it) }
                        }
                    )
                }
                IntField(
                    label = stringResource(R.string.fueling_deficit_threshold_g_label),
                    text = carbDeficitThreshold,
                    range = 5..60,
                    onCommit = { carbDeficitThreshold = it; vm.updateConfig { cfg -> cfg.copy(carbDeficitThresholdG = it.toInt()) } },
                    onTextChange = { carbDeficitThreshold = it },
                )
                Text(
                    text = stringResource(R.string.fueling_deficit_threshold_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (carbDeficitOn) {
                    IntField(
                        label = stringResource(R.string.fueling_deficit_initial_delay_label),
                        text = carbDeficitInitialDelay,
                        range = 0..240,
                        onCommit = { carbDeficitInitialDelay = it; vm.updateConfig { cfg -> cfg.copy(carbDeficitInitialDelayMin = it.toInt()) } },
                        onTextChange = { carbDeficitInitialDelay = it },
                    )
                    // Discrete picker (5/10/15/30 min) rather than free-text — those four
                    // are the only values that make sense for an alert cooldown on
                    // endurance rides: too fast and the rider gets nagged, too slow and a
                    // sustained deficit goes silent. Off-grid persisted values (from a
                    // legacy build that exposed the free-text input, or a manual edit)
                    // snap visually to the nearest grid point but are NOT silently
                    // rewritten; the first deliberate tap commits a valid value.
                    MinutesPickerRow(
                        label = stringResource(R.string.fueling_deficit_reminder_interval_label),
                        hint = stringResource(R.string.fueling_deficit_reminder_interval_hint),
                        selected = config.carbDeficitReminderIntervalMin,
                        onSelected = { vm.updateConfig { cfg -> cfg.copy(carbDeficitReminderIntervalMin = it) } },
                    )
                }
                FuelingRow(label = stringResource(R.string.fueling_alert_time_label)) {
                    Switch(
                        checked = carbTimeOn,
                        onCheckedChange = {
                            carbTimeOn = it
                            vm.updateConfig { cfg -> cfg.copy(carbTimeAlertEnabled = it) }
                        }
                    )
                }
                if (carbTimeOn) {
                    IntField(
                        label = stringResource(R.string.fueling_time_interval_label),
                        text = carbTimeInterval,
                        range = 1..60,
                        onCommit = { carbTimeInterval = it; vm.updateConfig { cfg -> cfg.copy(carbTimeIntervalMin = it.toInt()) } },
                        onTextChange = { carbTimeInterval = it },
                    )
                    IntField(
                        label = stringResource(R.string.fueling_initial_delay_label),
                        text = carbTimeInitialDelay,
                        range = 0..240,
                        onCommit = { carbTimeInitialDelay = it; vm.updateConfig { cfg -> cfg.copy(carbTimeInitialDelayMin = it.toInt()) } },
                        onTextChange = { carbTimeInitialDelay = it },
                    )
                }
                if (carbDeficitOn || carbTimeOn) {
                    CustomAlertField(
                        label = "Carb alert title",
                        value = carbCustomTitle,
                        onCommit = { v -> carbCustomTitle = v; vm.updateConfig { cfg -> cfg.copy(carbAlertCustomTitle = v) } },
                        defaultText = stringResource(R.string.fueling_carb_alert_title),
                        tokensHint = "",
                        maxLength = 30,
                    )
                    if (carbTimeOn) {
                        CustomAlertField(
                            label = "Carb alert detail (time)",
                            value = carbCustomDetailTime,
                            onCommit = { v -> carbCustomDetailTime = v; vm.updateConfig { cfg -> cfg.copy(carbAlertCustomDetailTime = v) } },
                            defaultText = stringResource(R.string.fueling_carb_alert_detail_time),
                            tokensHint = "Tokens: {deficit}, {elapsed}, {target}",
                            maxLength = ALERT_DETAIL_MAX_CHARS,
                            singleLine = false,
                        )
                    }
                    if (carbDeficitOn) {
                        CustomAlertField(
                            label = "Carb alert detail (deficit)",
                            value = carbCustomDetailDeficit,
                            onCommit = { v -> carbCustomDetailDeficit = v; vm.updateConfig { cfg -> cfg.copy(carbAlertCustomDetailDeficit = v) } },
                            defaultText = stringResource(R.string.fueling_carb_alert_detail_deficit),
                            tokensHint = "Tokens: {deficit}, {elapsed}, {target}",
                            maxLength = ALERT_DETAIL_MAX_CHARS,
                            singleLine = false,
                        )
                    }
                    BeepPatternPicker(
                        label = stringResource(R.string.fueling_beep_pattern_label),
                        selected = config.carbBeepPattern,
                        onSelected = { v -> vm.updateConfig { cfg -> cfg.copy(carbBeepPattern = v) } },
                    )
                    AlertColorPicker(
                        label = stringResource(R.string.fueling_alert_bg_color_label),
                        selected = carbAlertBgColor,
                        onSelected = { v -> carbAlertBgColor = v; vm.updateConfig { cfg -> cfg.copy(carbAlertBgColor = v) } },
                    )
                }
                HorizontalDivider()
                Text(text = stringResource(R.string.fueling_items_section), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                val gLabel = stringResource(R.string.fueling_slot_grams_label)
                SlotRow(label = "Slot 1", labelText = carb1Label, amountText = carb1Grams, unitLabel = gLabel, range = 0..999,
                    onLabel = { v -> carb1Label = v.safeTake(8); vm.updateConfig { cfg -> cfg.copy(carb1Label = v.safeTake(8)) } },
                    onAmountCommit = { v -> carb1Grams = v; vm.updateConfig { cfg -> cfg.copy(carb1Grams = v.toInt()) } },
                    onAmountText = { carb1Grams = it },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldColorPicker(label = stringResource(R.string.fueling_color_label), selected = carb1Color, modifier = Modifier.weight(1f),
                        onSelected = { v -> carb1Color = v; vm.updateConfig { cfg -> cfg.copy(carb1Color = v) } })
                    FieldEmojiPicker(label = "Icon", selected = carb1Icon, emojis = com.enderthor.kSafe.data.FUEL_EMOJI_CARB, modifier = Modifier.weight(1f),
                        onSelected = { v -> carb1Icon = v; vm.updateConfig { cfg -> cfg.copy(carb1Icon = v) } })
                }
                SlotRow(label = "Slot 2", labelText = carb2Label, amountText = carb2Grams, unitLabel = gLabel, range = 0..999,
                    onLabel = { v -> carb2Label = v.safeTake(8); vm.updateConfig { cfg -> cfg.copy(carb2Label = v.safeTake(8)) } },
                    onAmountCommit = { v -> carb2Grams = v; vm.updateConfig { cfg -> cfg.copy(carb2Grams = v.toInt()) } },
                    onAmountText = { carb2Grams = it },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldColorPicker(label = stringResource(R.string.fueling_color_label), selected = carb2Color, modifier = Modifier.weight(1f),
                        onSelected = { v -> carb2Color = v; vm.updateConfig { cfg -> cfg.copy(carb2Color = v) } })
                    FieldEmojiPicker(label = "Icon", selected = carb2Icon, emojis = com.enderthor.kSafe.data.FUEL_EMOJI_CARB, modifier = Modifier.weight(1f),
                        onSelected = { v -> carb2Icon = v; vm.updateConfig { cfg -> cfg.copy(carb2Icon = v) } })
                }
                SlotRow(label = "Slot 3", labelText = carb3Label, amountText = carb3Grams, unitLabel = gLabel, range = 0..999,
                    onLabel = { v -> carb3Label = v.safeTake(8); vm.updateConfig { cfg -> cfg.copy(carb3Label = v.safeTake(8)) } },
                    onAmountCommit = { v -> carb3Grams = v; vm.updateConfig { cfg -> cfg.copy(carb3Grams = v.toInt()) } },
                    onAmountText = { carb3Grams = it },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldColorPicker(label = stringResource(R.string.fueling_color_label), selected = carb3Color, modifier = Modifier.weight(1f),
                        onSelected = { v -> carb3Color = v; vm.updateConfig { cfg -> cfg.copy(carb3Color = v) } })
                    FieldEmojiPicker(label = "Icon", selected = carb3Icon, emojis = com.enderthor.kSafe.data.FUEL_EMOJI_CARB, modifier = Modifier.weight(1f),
                        onSelected = { v -> carb3Icon = v; vm.updateConfig { cfg -> cfg.copy(carb3Icon = v) } })
                }
                if (carbDeficitOn || carbTimeOn) {
                    TestActionButton(
                        label = stringResource(R.string.fueling_preview_label),
                        runningLabel = stringResource(R.string.fueling_preview_label),
                        onAction = {
                            KSafeExtension.getInstance()?.simulateFuelingAlert(
                                com.enderthor.kSafe.extension.util.FuelingChannel.CARB
                            ) ?: "Extension not connected — wait a moment and try again."
                        },
                    )
                }
                }  // end if (carbsEnabled)
            }
        }

        // Calories card — independent of the carb tracker. The estimate uses your
        // power meter when paired (most accurate) and only falls back to HR, so it
        // does NOT replace power; HR just covers the no-power-meter case.
        Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.fueling_calories_section),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                FuelingRow(label = stringResource(R.string.fueling_enabled_label)) {
                    Switch(
                        checked = caloriesEnabled,
                        onCheckedChange = {
                            caloriesEnabled = it
                            vm.updateConfig { cfg -> cfg.copy(hrCaloriesEnabled = it) }
                        }
                    )
                }
                if (caloriesEnabled) {
                    Text(
                        text = stringResource(R.string.fueling_calories_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Rider physiology — shared input. Power is used when paired; otherwise age +
        // sex feed Keytel for BOTH carb burn AND the calorie estimate, so show it
        // whenever either feature is on.
        if (carbsEnabled || caloriesEnabled) {
        Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.fueling_physiology_section),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.fueling_physiology_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IntField(
                    label = stringResource(R.string.fueling_rider_age_label),
                    text = riderAge,
                    range = 12..99,
                    onCommit = { riderAge = it; vm.updateConfig { cfg -> cfg.copy(riderAge = it.toInt()) } },
                    onTextChange = { riderAge = it },
                )
                RiderSexRow(
                    selected = riderSex,
                    onSelected = { riderSex = it; vm.updateConfig { cfg -> cfg.copy(riderSex = it) } },
                )
            }
        }
        }

        FuelingAlertButtonModeCard(vm, config.fuelingAlertButtonMode)

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

/**
 * v18 — Rider biological sex selector. Three short OutlinedButtons (Male / Female / Not set) so the
 * rider can toggle between them without opening a dialog. Sex is consumed by [CarbBurnEstimator]'s
 * Keytel tier (separate male/female regressions); "Not set" disables Keytel and falls the tracker
 * back to the Swain HRR METs tier. Stored in [KSafeConfig.riderSex].
 */
@Composable
private fun RiderSexRow(
    selected: RiderSex,
    onSelected: (RiderSex) -> Unit,
) {
    EnumSegmentedRow(
        label = stringResource(R.string.fueling_rider_sex_label),
        entries = RiderSex.entries,
        selected = selected,
        labelOf = { option ->
            when (option) {
                RiderSex.MALE    -> stringResource(R.string.fueling_rider_sex_male)
                RiderSex.FEMALE  -> stringResource(R.string.fueling_rider_sex_female)
                RiderSex.NOT_SET -> stringResource(R.string.fueling_rider_sex_unset)
            }
        },
        onSelected = onSelected,
    )
}
