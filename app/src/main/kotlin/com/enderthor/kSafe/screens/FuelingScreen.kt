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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.enderthor.kSafe.R
import com.enderthor.kSafe.activity.MainViewModel

@Composable
fun FuelingScreen(vm: MainViewModel) {
    val config by vm.config.collectAsState()

    // Carbs state
    var carbsEnabled         by remember(config.carbsTrackerEnabled)        { mutableStateOf(config.carbsTrackerEnabled) }
    var carbTarget           by remember(config.carbTargetGperHour)         { mutableStateOf(config.carbTargetGperHour.toString()) }
    var carbDeficitOn        by remember(config.carbDeficitAlertEnabled)    { mutableStateOf(config.carbDeficitAlertEnabled) }
    var carbDeficitThreshold by remember(config.carbDeficitThresholdG)      { mutableStateOf(config.carbDeficitThresholdG.toString()) }
    var carbTimeOn           by remember(config.carbTimeAlertEnabled)       { mutableStateOf(config.carbTimeAlertEnabled) }
    var carbTimeInterval     by remember(config.carbTimeIntervalMin)        { mutableStateOf(config.carbTimeIntervalMin.toString()) }
    var carbTimeInitialDelay by remember(config.carbTimeInitialDelayMin)    { mutableStateOf(config.carbTimeInitialDelayMin.toString()) }
    var carbCustomTitle      by remember(config.carbAlertCustomTitle)        { mutableStateOf(config.carbAlertCustomTitle) }
    var carb1Label           by remember(config.carb1Label)                  { mutableStateOf(config.carb1Label) }
    var carb1Grams           by remember(config.carb1Grams)                  { mutableStateOf(config.carb1Grams.toString()) }
    var carb2Label           by remember(config.carb2Label)                  { mutableStateOf(config.carb2Label) }
    var carb2Grams           by remember(config.carb2Grams)                  { mutableStateOf(config.carb2Grams.toString()) }
    var carb3Label           by remember(config.carb3Label)                  { mutableStateOf(config.carb3Label) }
    var carb3Grams           by remember(config.carb3Grams)                  { mutableStateOf(config.carb3Grams.toString()) }

    // Hydration state
    var hydEnabled           by remember(config.hydrationTrackerEnabled)        { mutableStateOf(config.hydrationTrackerEnabled) }
    var hydTarget            by remember(config.hydrationTargetMlPerHour)       { mutableStateOf(config.hydrationTargetMlPerHour.toString()) }
    var hydDeficitOn         by remember(config.hydrationDeficitAlertEnabled)   { mutableStateOf(config.hydrationDeficitAlertEnabled) }
    var hydDeficitThreshold  by remember(config.hydrationDeficitThresholdMl)    { mutableStateOf(config.hydrationDeficitThresholdMl.toString()) }
    var hydTimeOn            by remember(config.hydrationTimeAlertEnabled)      { mutableStateOf(config.hydrationTimeAlertEnabled) }
    var hydTimeInterval      by remember(config.hydrationTimeIntervalMin)       { mutableStateOf(config.hydrationTimeIntervalMin.toString()) }
    var hydTimeInitialDelay  by remember(config.hydrationTimeInitialDelayMin)   { mutableStateOf(config.hydrationTimeInitialDelayMin.toString()) }
    var hydCustomTitle       by remember(config.hydrationAlertCustomTitle)      { mutableStateOf(config.hydrationAlertCustomTitle) }
    var drink1Label          by remember(config.drink1Label)                     { mutableStateOf(config.drink1Label) }
    var drink1Ml             by remember(config.drink1Ml)                        { mutableStateOf(config.drink1Ml.toString()) }
    var drink2Label          by remember(config.drink2Label)                     { mutableStateOf(config.drink2Label) }
    var drink2Ml             by remember(config.drink2Ml)                        { mutableStateOf(config.drink2Ml.toString()) }

    // Post-ride summary state
    var summaryEnabled by remember(config.fuelingPostRideSummaryEnabled) { mutableStateOf(config.fuelingPostRideSummaryEnabled) }

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
                IntField(
                    label = stringResource(R.string.fueling_target_carb_label),
                    text = carbTarget,
                    range = 30..120,
                    onCommit = { carbTarget = it; vm.saveConfig(config.copy(carbTargetGperHour = it.toInt())) },
                    onTextChange = { carbTarget = it },
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
                    range = 5..60,
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
                OutlinedTextField(
                    value = carbCustomTitle,
                    onValueChange = { v ->
                        val trimmed = v.take(30)
                        carbCustomTitle = trimmed
                        vm.saveConfig(config.copy(carbAlertCustomTitle = trimmed))
                    },
                    label = { Text(stringResource(R.string.fueling_custom_title_label)) },
                    placeholder = { Text(stringResource(R.string.fueling_custom_title_carb_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider()
                Text(text = stringResource(R.string.fueling_items_section), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                val gLabel = stringResource(R.string.fueling_slot_grams_label)
                SlotRow(label = "Slot 1", labelText = carb1Label, amountText = carb1Grams, unitLabel = gLabel, range = 0..100,
                    onLabel = { v -> carb1Label = v.take(8); vm.saveConfig(config.copy(carb1Label = v.take(8))) },
                    onAmountCommit = { v -> carb1Grams = v; vm.saveConfig(config.copy(carb1Grams = v.toInt())) },
                    onAmountText = { carb1Grams = it },
                )
                SlotRow(label = "Slot 2", labelText = carb2Label, amountText = carb2Grams, unitLabel = gLabel, range = 0..100,
                    onLabel = { v -> carb2Label = v.take(8); vm.saveConfig(config.copy(carb2Label = v.take(8))) },
                    onAmountCommit = { v -> carb2Grams = v; vm.saveConfig(config.copy(carb2Grams = v.toInt())) },
                    onAmountText = { carb2Grams = it },
                )
                SlotRow(label = "Slot 3", labelText = carb3Label, amountText = carb3Grams, unitLabel = gLabel, range = 0..100,
                    onLabel = { v -> carb3Label = v.take(8); vm.saveConfig(config.copy(carb3Label = v.take(8))) },
                    onAmountCommit = { v -> carb3Grams = v; vm.saveConfig(config.copy(carb3Grams = v.toInt())) },
                    onAmountText = { carb3Grams = it },
                )
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
                IntField(
                    label = stringResource(R.string.fueling_target_hyd_label),
                    text = hydTarget,
                    range = 200..1500,
                    onCommit = { hydTarget = it; vm.saveConfig(config.copy(hydrationTargetMlPerHour = it.toInt())) },
                    onTextChange = { hydTarget = it },
                )
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
                    range = 5..60,
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
                OutlinedTextField(
                    value = hydCustomTitle,
                    onValueChange = { v ->
                        val trimmed = v.take(30)
                        hydCustomTitle = trimmed
                        vm.saveConfig(config.copy(hydrationAlertCustomTitle = trimmed))
                    },
                    label = { Text(stringResource(R.string.fueling_custom_title_label)) },
                    placeholder = { Text(stringResource(R.string.fueling_custom_title_hyd_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider()
                Text(text = stringResource(R.string.fueling_items_section), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                val mlLabel = stringResource(R.string.fueling_slot_ml_label)
                SlotRow(label = "Slot 1", labelText = drink1Label, amountText = drink1Ml, unitLabel = mlLabel, range = 0..1000,
                    onLabel = { v -> drink1Label = v.take(8); vm.saveConfig(config.copy(drink1Label = v.take(8))) },
                    onAmountCommit = { v -> drink1Ml = v; vm.saveConfig(config.copy(drink1Ml = v.toInt())) },
                    onAmountText = { drink1Ml = it },
                )
                SlotRow(label = "Slot 2", labelText = drink2Label, amountText = drink2Ml, unitLabel = mlLabel, range = 0..1000,
                    onLabel = { v -> drink2Label = v.take(8); vm.saveConfig(config.copy(drink2Label = v.take(8))) },
                    onAmountCommit = { v -> drink2Ml = v; vm.saveConfig(config.copy(drink2Ml = v.toInt())) },
                    onAmountText = { drink2Ml = it },
                )
            }
        }

        // Post-ride summary
        FuelingRow(label = stringResource(R.string.fueling_post_ride_summary_label)) {
            Switch(
                checked = summaryEnabled,
                onCheckedChange = {
                    summaryEnabled = it
                    vm.saveConfig(config.copy(fuelingPostRideSummaryEnabled = it))
                }
            )
        }
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
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            val filtered = v.filter { it.isDigit() }.take(4)
            onTextChange(filtered)
            val parsed = filtered.toIntOrNull()
            if (parsed != null && parsed in range) onCommit(filtered)
        },
        label = { Text(label) },
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
            )
        }
    }
}
