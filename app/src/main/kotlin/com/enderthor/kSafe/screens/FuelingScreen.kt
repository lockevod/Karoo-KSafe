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
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import com.enderthor.kSafe.R
import com.enderthor.kSafe.activity.MainViewModel
import com.enderthor.kSafe.data.FUELING_ALERT_COLORS
import com.enderthor.kSafe.data.FuelingAlertButtonMode
import com.enderthor.kSafe.data.RiderSex
import com.enderthor.kSafe.data.fuelingAlertColorRes
import com.enderthor.kSafe.extension.util.ALERT_DETAIL_MAX_CHARS
import com.enderthor.kSafe.extension.util.carbsFromVolume
import com.enderthor.kSafe.extension.util.safeTake

@Composable
fun FuelingScreen(vm: MainViewModel) {
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
    var hydCustomDetailTime    by remember(config.hydrationAlertCustomDetailTime)    { mutableStateOf(config.hydrationAlertCustomDetailTime) }
    var hydCustomDetailDeficit by remember(config.hydrationAlertCustomDetailDeficit) { mutableStateOf(config.hydrationAlertCustomDetailDeficit) }
    var drink1Label          by remember(config.drink1Label)                     { mutableStateOf(config.drink1Label) }
    var drink1Ml             by remember(config.drink1Ml)                        { mutableStateOf(config.drink1Ml.toString()) }
    var drink1Color          by remember(config.drink1Color)                     { mutableStateOf(config.drink1Color) }
    var drink1Icon           by remember(config.drink1Icon)                      { mutableStateOf(config.drink1Icon) }
    var drink2Label          by remember(config.drink2Label)                     { mutableStateOf(config.drink2Label) }
    var drink2Ml             by remember(config.drink2Ml)                        { mutableStateOf(config.drink2Ml.toString()) }
    var drink2Color          by remember(config.drink2Color)                     { mutableStateOf(config.drink2Color) }
    var drink2Icon           by remember(config.drink2Icon)                      { mutableStateOf(config.drink2Icon) }

    // Combined logging state
    var combinedConcentration by remember(config.combinedCarbConcentrationPer500ml) { mutableStateOf(config.combinedCarbConcentrationPer500ml.toString()) }
    var combined1Label       by remember(config.combined1Label)                   { mutableStateOf(config.combined1Label) }
    var combined1Ml          by remember(config.combined1Ml)                      { mutableStateOf(config.combined1Ml.toString()) }
    var combined1Carbs       by remember(config.combined1Carbs)                   { mutableStateOf(config.combined1Carbs.toString()) }
    var combined1Color       by remember(config.combined1Color)                   { mutableStateOf(config.combined1Color) }
    var combined2Label       by remember(config.combined2Label)                   { mutableStateOf(config.combined2Label) }
    var combined2Ml          by remember(config.combined2Ml)                      { mutableStateOf(config.combined2Ml.toString()) }
    var combined2Carbs       by remember(config.combined2Carbs)                   { mutableStateOf(config.combined2Carbs.toString()) }
    var combined2Color       by remember(config.combined2Color)                   { mutableStateOf(config.combined2Color) }

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
                FuelingRow(label = stringResource(R.string.fueling_alert_time_label)) {
                    Switch(
                        checked = carbTimeOn,
                        onCheckedChange = {
                            carbTimeOn = it
                            vm.updateConfig { cfg -> cfg.copy(carbTimeAlertEnabled = it) }
                        }
                    )
                }
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
                CustomAlertField(
                    label = "Carb alert title",
                    value = carbCustomTitle,
                    onCommit = { v -> carbCustomTitle = v; vm.updateConfig { cfg -> cfg.copy(carbAlertCustomTitle = v) } },
                    defaultText = stringResource(R.string.fueling_carb_alert_title),
                    tokensHint = "",
                    maxLength = 30,
                )
                CustomAlertField(
                    label = "Carb alert detail (time)",
                    value = carbCustomDetailTime,
                    onCommit = { v -> carbCustomDetailTime = v; vm.updateConfig { cfg -> cfg.copy(carbAlertCustomDetailTime = v) } },
                    defaultText = stringResource(R.string.fueling_carb_alert_detail_time),
                    tokensHint = "Tokens: {deficit}, {elapsed}, {target}",
                    maxLength = ALERT_DETAIL_MAX_CHARS,
                    singleLine = false,
                )
                CustomAlertField(
                    label = "Carb alert detail (deficit)",
                    value = carbCustomDetailDeficit,
                    onCommit = { v -> carbCustomDetailDeficit = v; vm.updateConfig { cfg -> cfg.copy(carbAlertCustomDetailDeficit = v) } },
                    defaultText = stringResource(R.string.fueling_carb_alert_detail_deficit),
                    tokensHint = "Tokens: {deficit}, {elapsed}, {target}",
                    maxLength = ALERT_DETAIL_MAX_CHARS,
                    singleLine = false,
                )
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
                            vm.updateConfig { cfg -> cfg.copy(hydrationTrackerEnabled = it) }
                        }
                    )
                }
                if (hydEnabled) {
                FuelingRow(label = stringResource(R.string.fueling_hyd_dynamic_label)) {
                    Switch(
                        checked = hydDynamic,
                        onCheckedChange = {
                            hydDynamic = it
                            vm.updateConfig { cfg -> cfg.copy(hydrationDynamicEstimateEnabled = it) }
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
                        onCommit = { hydTarget = it; vm.updateConfig { cfg -> cfg.copy(hydrationTargetMlPerHour = it.toInt()) } },
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
                            vm.updateConfig { cfg -> cfg.copy(hydrationDeficitAlertEnabled = it) }
                        }
                    )
                }
                IntField(
                    label = stringResource(R.string.fueling_deficit_threshold_ml_label),
                    text = hydDeficitThreshold,
                    range = 50..800,
                    onCommit = { hydDeficitThreshold = it; vm.updateConfig { cfg -> cfg.copy(hydrationDeficitThresholdMl = it.toInt()) } },
                    onTextChange = { hydDeficitThreshold = it },
                )
                IntField(
                    label = stringResource(R.string.fueling_deficit_initial_delay_label),
                    text = hydDeficitInitialDelay,
                    range = 0..240,
                    onCommit = { hydDeficitInitialDelay = it; vm.updateConfig { cfg -> cfg.copy(hydrationDeficitInitialDelayMin = it.toInt()) } },
                    onTextChange = { hydDeficitInitialDelay = it },
                )
                MinutesPickerRow(
                    label = stringResource(R.string.fueling_deficit_reminder_interval_label),
                    hint = stringResource(R.string.fueling_deficit_reminder_interval_hint),
                    selected = config.hydrationDeficitReminderIntervalMin,
                    onSelected = { vm.updateConfig { cfg -> cfg.copy(hydrationDeficitReminderIntervalMin = it) } },
                )
                FuelingRow(label = stringResource(R.string.fueling_alert_time_label)) {
                    Switch(
                        checked = hydTimeOn,
                        onCheckedChange = {
                            hydTimeOn = it
                            vm.updateConfig { cfg -> cfg.copy(hydrationTimeAlertEnabled = it) }
                        }
                    )
                }
                IntField(
                    label = stringResource(R.string.fueling_time_interval_label),
                    text = hydTimeInterval,
                    range = 1..60,
                    onCommit = { hydTimeInterval = it; vm.updateConfig { cfg -> cfg.copy(hydrationTimeIntervalMin = it.toInt()) } },
                    onTextChange = { hydTimeInterval = it },
                )
                IntField(
                    label = stringResource(R.string.fueling_initial_delay_label),
                    text = hydTimeInitialDelay,
                    range = 0..240,
                    onCommit = { hydTimeInitialDelay = it; vm.updateConfig { cfg -> cfg.copy(hydrationTimeInitialDelayMin = it.toInt()) } },
                    onTextChange = { hydTimeInitialDelay = it },
                )
                CustomAlertField(
                    label = "Hydration alert title",
                    value = hydCustomTitle,
                    onCommit = { v -> hydCustomTitle = v; vm.updateConfig { cfg -> cfg.copy(hydrationAlertCustomTitle = v) } },
                    defaultText = stringResource(R.string.fueling_hyd_alert_title),
                    tokensHint = "",
                    maxLength = 30,
                )
                CustomAlertField(
                    label = "Hydration alert detail (time)",
                    value = hydCustomDetailTime,
                    onCommit = { v -> hydCustomDetailTime = v; vm.updateConfig { cfg -> cfg.copy(hydrationAlertCustomDetailTime = v) } },
                    defaultText = stringResource(R.string.fueling_hyd_alert_detail_time),
                    tokensHint = "Tokens: {deficit}, {elapsed}, {target}",
                    maxLength = ALERT_DETAIL_MAX_CHARS,
                    singleLine = false,
                )
                CustomAlertField(
                    label = "Hydration alert detail (deficit)",
                    value = hydCustomDetailDeficit,
                    onCommit = { v -> hydCustomDetailDeficit = v; vm.updateConfig { cfg -> cfg.copy(hydrationAlertCustomDetailDeficit = v) } },
                    defaultText = stringResource(R.string.fueling_hyd_alert_detail_deficit),
                    tokensHint = "Tokens: {deficit}, {elapsed}, {target}",
                    maxLength = ALERT_DETAIL_MAX_CHARS,
                    singleLine = false,
                )
                BeepPatternPicker(
                    label = stringResource(R.string.fueling_beep_pattern_label),
                    selected = config.hydBeepPattern,
                    onSelected = { v -> vm.updateConfig { cfg -> cfg.copy(hydBeepPattern = v) } },
                )
                AlertColorPicker(
                    label = stringResource(R.string.fueling_alert_bg_color_label),
                    selected = hydAlertBgColor,
                    onSelected = { v -> hydAlertBgColor = v; vm.updateConfig { cfg -> cfg.copy(hydrationAlertBgColor = v) } },
                )
                HorizontalDivider()
                Text(text = stringResource(R.string.fueling_items_section), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                val mlLabel = stringResource(R.string.fueling_slot_ml_label)
                SlotRow(label = "Slot 1", labelText = drink1Label, amountText = drink1Ml, unitLabel = mlLabel, range = 0..1000,
                    onLabel = { v -> drink1Label = v.safeTake(8); vm.updateConfig { cfg -> cfg.copy(drink1Label = v.safeTake(8)) } },
                    onAmountCommit = { v -> drink1Ml = v; vm.updateConfig { cfg -> cfg.copy(drink1Ml = v.toInt()) } },
                    onAmountText = { drink1Ml = it },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldColorPicker(label = stringResource(R.string.fueling_color_label), selected = drink1Color, modifier = Modifier.weight(1f),
                        onSelected = { v -> drink1Color = v; vm.updateConfig { cfg -> cfg.copy(drink1Color = v) } })
                    FieldEmojiPicker(label = "Icon", selected = drink1Icon, emojis = com.enderthor.kSafe.data.FUEL_EMOJI_DRINK, modifier = Modifier.weight(1f),
                        onSelected = { v -> drink1Icon = v; vm.updateConfig { cfg -> cfg.copy(drink1Icon = v) } })
                }
                SlotRow(label = "Slot 2", labelText = drink2Label, amountText = drink2Ml, unitLabel = mlLabel, range = 0..1000,
                    onLabel = { v -> drink2Label = v.safeTake(8); vm.updateConfig { cfg -> cfg.copy(drink2Label = v.safeTake(8)) } },
                    onAmountCommit = { v -> drink2Ml = v; vm.updateConfig { cfg -> cfg.copy(drink2Ml = v.toInt()) } },
                    onAmountText = { drink2Ml = it },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldColorPicker(label = stringResource(R.string.fueling_color_label), selected = drink2Color, modifier = Modifier.weight(1f),
                        onSelected = { v -> drink2Color = v; vm.updateConfig { cfg -> cfg.copy(drink2Color = v) } })
                    FieldEmojiPicker(label = "Icon", selected = drink2Icon, emojis = com.enderthor.kSafe.data.FUEL_EMOJI_DRINK, modifier = Modifier.weight(1f),
                        onSelected = { v -> drink2Icon = v; vm.updateConfig { cfg -> cfg.copy(drink2Icon = v) } })
                }
                }  // end if (hydEnabled)
            }
        }

        // Alert-mode card. Controls whether fueling alerts (carbs + hydration) show a
        // one-tap log button, a log+undo pair, or no button at all. Placed here so it
        // reads as "global fueling-alert behaviour" and is not buried inside the Combined
        // logging section where it would appear to only affect combined entries.
        Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.fueling_alert_button_mode_label),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (mode in FuelingAlertButtonMode.entries) {
                            val label = when (mode) {
                                FuelingAlertButtonMode.OFF      -> stringResource(R.string.fueling_alert_button_off)
                                FuelingAlertButtonMode.LOG      -> stringResource(R.string.fueling_alert_button_log)
                                FuelingAlertButtonMode.LOG_UNDO -> stringResource(R.string.fueling_alert_button_log_undo)
                            }
                            val selected = config.fuelingAlertButtonMode == mode
                            val onClick = { vm.updateConfig { it.copy(fuelingAlertButtonMode = mode) } }
                            if (selected) {
                                Button(
                                    onClick = onClick,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                                ) {
                                    Text(label, style = MaterialTheme.typography.labelSmall)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = onClick,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                                ) {
                                    Text(label, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Combined logging card. One tap logs a drink + its carbs together. The carb
        // concentration is set once; each button's carbs auto-fill from its volume via
        // carbsFromVolume but stay editable as a manual override. No icon picker — the
        // combined field's icon is fixed.
        Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.fueling_combined_section),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.fueling_combined_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IntField(
                    label = stringResource(R.string.fueling_combined_concentration_label),
                    text = combinedConcentration,
                    range = 0..200,
                    onCommit = { v ->
                        combinedConcentration = v
                        val conc = v.toIntOrNull()?.coerceIn(0, 200) ?: config.combinedCarbConcentrationPer500ml
                        // Derive from the LOCAL ml field state (what the rider currently sees), not the
                        // DataStore snapshot, so display and persisted value agree even if the ml field
                        // was just edited but hasn't round-tripped through config yet.
                        val ml1 = combined1Ml.toIntOrNull()?.coerceIn(0, 1000) ?: config.combined1Ml
                        val ml2 = combined2Ml.toIntOrNull()?.coerceIn(0, 1000) ?: config.combined2Ml
                        vm.updateConfig { cfg ->
                            cfg.copy(
                                combinedCarbConcentrationPer500ml = conc,
                                combined1Carbs = carbsFromVolume(ml1, conc),
                                combined2Carbs = carbsFromVolume(ml2, conc),
                            )
                        }
                        combined1Carbs = carbsFromVolume(ml1, conc).toString()
                        combined2Carbs = carbsFromVolume(ml2, conc).toString()
                    },
                    onTextChange = { combinedConcentration = it },
                )
                HorizontalDivider()
                Text(text = stringResource(R.string.fueling_items_section), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                val combinedMlLabel = stringResource(R.string.fueling_slot_ml_label)
                val combinedGLabel = stringResource(R.string.fueling_slot_grams_label)
                // Button 1
                SlotRow(label = "Slot 1", labelText = combined1Label, amountText = combined1Ml, unitLabel = combinedMlLabel, range = 0..1000,
                    onLabel = { v -> combined1Label = v.safeTake(8); vm.updateConfig { cfg -> cfg.copy(combined1Label = v.safeTake(8)) } },
                    onAmountCommit = { v ->
                        combined1Ml = v
                        val ml = (v.toIntOrNull() ?: 0).coerceIn(0, 1000)
                        val conc = combinedConcentration.toIntOrNull()?.coerceIn(0, 200) ?: config.combinedCarbConcentrationPer500ml
                        combined1Carbs = carbsFromVolume(ml, conc).toString()
                        vm.updateConfig { cfg -> cfg.copy(combined1Ml = ml, combined1Carbs = carbsFromVolume(ml, conc)) }
                    },
                    onAmountText = { combined1Ml = it },
                )
                IntField(
                    label = combinedGLabel,
                    text = combined1Carbs,
                    range = 0..999,
                    onCommit = { v -> combined1Carbs = v; vm.updateConfig { it.copy(combined1Carbs = (v.toIntOrNull() ?: 0).coerceIn(0, 999)) } },
                    onTextChange = { combined1Carbs = it },
                )
                FieldColorPicker(label = stringResource(R.string.fueling_color_label), selected = combined1Color,
                    onSelected = { v -> combined1Color = v; vm.updateConfig { it.copy(combined1Color = v) } })
                // Button 2
                SlotRow(label = "Slot 2", labelText = combined2Label, amountText = combined2Ml, unitLabel = combinedMlLabel, range = 0..1000,
                    onLabel = { v -> combined2Label = v.safeTake(8); vm.updateConfig { cfg -> cfg.copy(combined2Label = v.safeTake(8)) } },
                    onAmountCommit = { v ->
                        combined2Ml = v
                        val ml = (v.toIntOrNull() ?: 0).coerceIn(0, 1000)
                        val conc = combinedConcentration.toIntOrNull()?.coerceIn(0, 200) ?: config.combinedCarbConcentrationPer500ml
                        combined2Carbs = carbsFromVolume(ml, conc).toString()
                        vm.updateConfig { cfg -> cfg.copy(combined2Ml = ml, combined2Carbs = carbsFromVolume(ml, conc)) }
                    },
                    onAmountText = { combined2Ml = it },
                )
                IntField(
                    label = combinedGLabel,
                    text = combined2Carbs,
                    range = 0..999,
                    onCommit = { v -> combined2Carbs = v; vm.updateConfig { it.copy(combined2Carbs = (v.toIntOrNull() ?: 0).coerceIn(0, 999)) } },
                    onTextChange = { combined2Carbs = it },
                )
                FieldColorPicker(label = stringResource(R.string.fueling_color_label), selected = combined2Color,
                    onSelected = { v -> combined2Color = v; vm.updateConfig { it.copy(combined2Color = v) } })
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
 * v18 — Rider biological sex selector. Three short OutlinedButtons (Male /
 * Female / Not set) so the rider can toggle between them without opening a
 * dialog. Sex is consumed by [CarbBurnEstimator]'s Keytel tier (separate
 * male/female regressions); "Not set" disables Keytel and falls the tracker
 * back to the Swain HRR METs tier. Stored in [KSafeConfig.riderSex].
 */
@Composable
private fun RiderSexRow(
    selected: RiderSex,
    onSelected: (RiderSex) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.fueling_rider_sex_label),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Three buttons fit in the 480 px Karoo screen comfortably. Short labels
            // chosen so they don't wrap on the smaller K3 screen.
            for (option in RiderSex.entries) {
                val label = when (option) {
                    RiderSex.MALE    -> stringResource(R.string.fueling_rider_sex_male)
                    RiderSex.FEMALE  -> stringResource(R.string.fueling_rider_sex_female)
                    RiderSex.NOT_SET -> stringResource(R.string.fueling_rider_sex_unset)
                }
                if (option == selected) {
                    Button(
                        onClick = { onSelected(option) },
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                    ) {
                        Text(text = label, style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    OutlinedButton(
                        onClick = { onSelected(option) },
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                    ) {
                        Text(text = label, style = MaterialTheme.typography.labelSmall)
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
private fun MinutesPickerRow(
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
