package com.enderthor.kSafe.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.enderthor.kSafe.data.LastHydrationRide
import com.enderthor.kSafe.data.SweatSodiumProfile
import com.enderthor.kSafe.extension.util.CalibrationInput
import com.enderthor.kSafe.extension.util.CalibrationRejection
import com.enderthor.kSafe.extension.util.CalibrationResult
import com.enderthor.kSafe.extension.util.sodiumAdvice
import java.util.Date
import com.enderthor.kSafe.R
import com.enderthor.kSafe.activity.MainViewModel
import com.enderthor.kSafe.extension.KSafeExtension
import com.enderthor.kSafe.extension.util.ALERT_DETAIL_MAX_CHARS
import com.enderthor.kSafe.extension.util.carbsFromVolume
import com.enderthor.kSafe.extension.util.safeTake

@Composable
fun HydrationScreen(vm: MainViewModel) {
    val config by vm.config.collectAsState()

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
    var hydMult              by remember(config.hydrationSweatMultiplierPct)    { mutableStateOf(config.hydrationSweatMultiplierPct.toString()) }
    var hydRepl              by remember(config.hydrationReplacementPct)        { mutableStateOf(config.hydrationReplacementPct.toString()) }
    var saltMeasured         by remember(config.sweatSodiumMeasuredMmolL)       { mutableStateOf(config.sweatSodiumMeasuredMmolL.toString()) }
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.tab_hydration),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

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
                if (hydDynamic) {
                    IntField(
                        label = stringResource(R.string.fueling_hyd_repl_label),
                        text = hydRepl,
                        range = 50..100,
                        onCommit = { hydRepl = it; vm.updateConfig { cfg -> cfg.copy(hydrationReplacementPct = it.toInt()) } },
                        onTextChange = { hydRepl = it },
                    )
                    Text(
                        text = stringResource(R.string.fueling_hyd_repl_hint),
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
                Text(
                    text = stringResource(R.string.fueling_deficit_threshold_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (hydDeficitOn) {
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
                }
                FuelingRow(label = stringResource(R.string.fueling_alert_time_label)) {
                    Switch(
                        checked = hydTimeOn,
                        onCheckedChange = {
                            hydTimeOn = it
                            vm.updateConfig { cfg -> cfg.copy(hydrationTimeAlertEnabled = it) }
                        }
                    )
                }
                if (hydTimeOn) {
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
                }
                if (hydDeficitOn || hydTimeOn) {
                    CustomAlertField(
                        label = "Hydration alert title",
                        value = hydCustomTitle,
                        onCommit = { v -> hydCustomTitle = v; vm.updateConfig { cfg -> cfg.copy(hydrationAlertCustomTitle = v) } },
                        defaultText = stringResource(R.string.fueling_hyd_alert_title),
                        tokensHint = "",
                        maxLength = 30,
                    )
                    if (hydTimeOn) {
                        CustomAlertField(
                            label = "Hydration alert detail (time)",
                            value = hydCustomDetailTime,
                            onCommit = { v -> hydCustomDetailTime = v; vm.updateConfig { cfg -> cfg.copy(hydrationAlertCustomDetailTime = v) } },
                            defaultText = stringResource(R.string.fueling_hyd_alert_detail_time),
                            tokensHint = "Tokens: {deficit}, {elapsed}, {target}",
                            maxLength = ALERT_DETAIL_MAX_CHARS,
                            singleLine = false,
                        )
                    }
                    if (hydDeficitOn) {
                        CustomAlertField(
                            label = "Hydration alert detail (deficit)",
                            value = hydCustomDetailDeficit,
                            onCommit = { v -> hydCustomDetailDeficit = v; vm.updateConfig { cfg -> cfg.copy(hydrationAlertCustomDetailDeficit = v) } },
                            defaultText = stringResource(R.string.fueling_hyd_alert_detail_deficit),
                            tokensHint = "Tokens: {deficit}, {elapsed}, {target}",
                            maxLength = ALERT_DETAIL_MAX_CHARS,
                            singleLine = false,
                        )
                    }
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
                }
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
                if (hydDeficitOn || hydTimeOn) {
                    TestActionButton(
                        label = stringResource(R.string.fueling_preview_label),
                        runningLabel = stringResource(R.string.fueling_preview_label),
                        onAction = {
                            KSafeExtension.getInstance()?.simulateFuelingAlert(
                                com.enderthor.kSafe.extension.util.FuelingChannel.HYDRATION
                            ) ?: "Extension not connected — wait a moment and try again."
                        },
                    )
                }
                }  // end if (hydEnabled)
            }
        }

        // Combined logging card. One tap logs a drink + its carbs together. The carb
        // concentration is set once; each button's carbs auto-fill from its volume via
        // carbsFromVolume but stay editable as a manual override. No icon picker — the
        // combined field's icon is fixed.
        // Sits next to the drink slots (it is set up as a bottle) and only shows when it can
        // actually log something: carbs and/or hydration tracking on.
        if (hydEnabled || config.carbsTrackerEnabled) {
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
        }

        if (hydEnabled) {
            YourSweatCard(
                vm = vm, hydMult = hydMult, onMultChange = { hydMult = it },
                profile = config.sweatSodiumProfile, saltMeasured = saltMeasured, onSaltChange = { saltMeasured = it },
            )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaltinessChips(selected: SweatSodiumProfile, onSelected: (SweatSodiumProfile) -> Unit) {
    // 2x2: four labels in one row are too narrow for readable text on the 480 px Karoo screen.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SweatSodiumProfile.entries.chunked(2).forEach { row ->
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                row.forEachIndexed { index, p ->
                    SegmentedButton(
                        selected = selected == p,
                        onClick = { onSelected(p) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = row.size),
                    ) {
                        Text(
                            text = stringResource(when (p) {
                                SweatSodiumProfile.LIGHT -> R.string.fueling_hyd_salt_light
                                SweatSodiumProfile.TYPICAL -> R.string.fueling_hyd_salt_typical
                                SweatSodiumProfile.SALTY -> R.string.fueling_hyd_salt_salty
                                SweatSodiumProfile.MEASURED -> R.string.fueling_hyd_salt_measured
                            }),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

private fun rejectionRes(r: CalibrationRejection): Int = when (r) {
    CalibrationRejection.NO_RIDE -> R.string.fueling_calib_reject_no_ride
    CalibrationRejection.RIDE_CHANGED -> R.string.fueling_calib_reject_ride_changed
    CalibrationRejection.ALREADY_CALIBRATED -> R.string.fueling_calib_reject_already_calibrated
    CalibrationRejection.TOO_OLD -> R.string.fueling_calib_reject_too_old
    CalibrationRejection.URINATED -> R.string.fueling_calib_reject_urinated
    CalibrationRejection.NO_RIDE_TIME -> R.string.fueling_calib_reject_no_ride_time
    CalibrationRejection.TOO_SHORT -> R.string.fueling_calib_reject_too_short
    CalibrationRejection.LOW_COVERAGE -> R.string.fueling_calib_reject_low_coverage
    CalibrationRejection.IMPLAUSIBLE_WEIGHT -> R.string.fueling_calib_reject_implausible_weight
    CalibrationRejection.TOO_LITTLE_SWEAT -> R.string.fueling_calib_reject_too_little_sweat
    CalibrationRejection.RATIO_OUT_OF_RANGE -> R.string.fueling_calib_reject_ratio_out_of_range
}

/** "Your sweat": multiplier + weigh-in calibration, saltiness question and last-ride summary. */
@Composable
private fun YourSweatCard(
    vm: MainViewModel,
    hydMult: String,
    onMultChange: (String) -> Unit,
    profile: SweatSodiumProfile,
    saltMeasured: String,
    onSaltChange: (String) -> Unit,
) {
    val ride by vm.lastHydrationRide.collectAsState()
    val r = ride
    val small = MaterialTheme.typography.bodyMedium
    val muted = MaterialTheme.colorScheme.onSurface
    var calibOpen by remember { mutableStateOf(false) }
    var lastOpen by remember { mutableStateOf(false) }
    var resetArmed by remember { mutableStateOf(false) }
    LaunchedEffect(resetArmed) {
        if (resetArmed) { delay(3_000); resetArmed = false }
    }
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.fueling_sweat_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            IntField(
                label = stringResource(R.string.fueling_hyd_mult_label),
                text = hydMult,
                range = 50..200,
                onCommit = { onMultChange(it); vm.updateConfig { cfg -> cfg.copy(hydrationSweatMultiplierPct = it.toInt()) } },
                onTextChange = onMultChange,
            )
            // Hint, buttons, form and divider share a tighter 4 dp column: the buttons keep
            // their 48 dp touch box (gloved touchscreen), and its invisible 4 dp padding plus
            // this 4 dp spacing gives the same 8 dp visual rhythm as the rest of the card.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = stringResource(R.string.fueling_hyd_mult_hint), style = small, color = muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Equal weights: without them the long Calibrate label took the whole row and squeezed
                    // Reset to ~0 width, wrapping its text one letter per line into a tall blank block.
                    OutlinedButton(onClick = { calibOpen = !calibOpen }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.fueling_calib_title))
                    }
                    // Reset wipes the calibration, so it takes a second tap within 3 s.
                    TextButton(onClick = {
                        if (resetArmed) { resetArmed = false; vm.resetHydrationCalibration() } else resetArmed = true
                    }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(if (resetArmed) R.string.fueling_calib_reset_confirm else R.string.fueling_calib_reset))
                    }
                }
                if (calibOpen) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (r == null) Text(stringResource(R.string.fueling_lastride_empty), style = small, color = muted)
                        else CalibrationForm(vm, r)
                    }
                }
                HorizontalDivider()
            }
            Text(text = stringResource(R.string.fueling_hyd_salt_label), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            SaltinessChips(
                selected = profile,
                onSelected = { v -> vm.updateConfig { cfg -> cfg.copy(sweatSodiumProfile = v) } },
            )
            Text(text = stringResource(R.string.fueling_hyd_salt_hint), style = small, color = muted)
            if (profile == SweatSodiumProfile.MEASURED) {
                IntField(
                    label = stringResource(R.string.fueling_hyd_salt_measured_label),
                    text = saltMeasured,
                    range = 10..90,
                    onCommit = { onSaltChange(it); vm.updateConfig { cfg -> cfg.copy(sweatSodiumMeasuredMmolL = it.toInt()) } },
                    onTextChange = onSaltChange,
                )
            }
            HorizontalDivider()
            if (r == null) {
                Text(stringResource(R.string.fueling_lastride_empty), style = small, color = muted)
            } else {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val advice = remember(r) { sodiumAdvice(r) }
                val date = remember(r.endedAtMs) { android.text.format.DateFormat.getMediumDateFormat(ctx).format(Date(r.endedAtMs)) }
                val minutes = (r.rideTimeMs / 60_000L).toInt()
                val loc = java.util.Locale.getDefault()
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { lastOpen = !lastOpen },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            R.string.fueling_lastride_summary, date, minutes / 60, minutes % 60,
                            String.format(loc, "%.1f", r.cumSweatMl / 1000.0),
                            String.format(loc, "%.1f", advice.mgLost / 1000.0),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(if (lastOpen) "▴" else "▾", style = MaterialTheme.typography.bodyMedium)
                }
                if (lastOpen) {
                    Text(stringResource(R.string.fueling_lastride_fluids, r.cumSweatMl.toInt(), r.cumLoggedMl), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.fueling_lastride_sodium, advice.mgLost, advice.mgPerHour), style = MaterialTheme.typography.bodyMedium)
                    if (advice.partial) {
                        Text(stringResource(R.string.fueling_lastride_partial), style = small, color = MaterialTheme.colorScheme.error)
                    }
                    if (advice.fluidLimited) {
                        Text(stringResource(R.string.fueling_lastride_fluid_limited), style = small, color = muted)
                    } else if (advice.bottleMgPerL != null) {
                        Text(
                            stringResource(
                                if (advice.recommended) R.string.fueling_lastride_bottle_recommended else R.string.fueling_lastride_bottle_optional,
                                advice.bottleMgPerL,
                            ),
                            style = small, color = muted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalibrationForm(vm: MainViewModel, r: LastHydrationRide) {
    val scope = rememberCoroutineScope()
    val small = MaterialTheme.typography.bodyMedium
    val muted = MaterialTheme.colorScheme.onSurface
    // Hoisted above the calibrated early-return: the DataStore write flips r.calibrated before
    // this composable sees the result, so the success text must survive that recomposition.

    var result by remember(r.rideId) { mutableStateOf<CalibrationResult?>(null) }
    if (r.calibrated) {
        Text(stringResource(R.string.fueling_lastride_calibrated), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        (result as? CalibrationResult.Accepted)?.let {
            Text(
                stringResource(R.string.fueling_calib_result_ok, it.ratio.toDouble(), it.newMultiplierPct),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }
    var pre by remember(r.rideId) { mutableStateOf("") }
    var post by remember(r.rideId) { mutableStateOf("") }
    var drink by remember(r.rideId) { mutableStateOf(r.cumLoggedMl.toString()) }
    var food by remember(r.rideId) { mutableStateOf("0") }
    var urinated by remember(r.rideId) { mutableStateOf(false) }
    val preKg = pre.replace(',', '.').toDoubleOrNull()
    val postKg = post.replace(',', '.').toDoubleOrNull()

    Text(stringResource(R.string.fueling_calib_instructions), style = small, color = muted)
    DecimalField(stringResource(R.string.fueling_calib_pre_label), pre) { pre = it }
    DecimalField(stringResource(R.string.fueling_calib_post_label), post) { post = it }
    OutlinedTextField(
        value = drink,
        onValueChange = { drink = it.filter { c -> c.isDigit() }.take(5) },
        label = { Text(stringResource(R.string.fueling_calib_drink_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = food,
        onValueChange = { food = it.filter { c -> c.isDigit() }.take(4) },
        label = { Text(stringResource(R.string.fueling_calib_food_label)) },
        supportingText = { Text(stringResource(R.string.fueling_calib_food_hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    FuelingRow(label = stringResource(R.string.fueling_calib_urinated_label)) {
        Checkbox(checked = urinated, onCheckedChange = { urinated = it })
    }
    // Drink is required (type 0 if nothing): a blank field silently counting as 0 biases the ratio low.
    val drinkMl = drink.toIntOrNull()
    Button(
        enabled = preKg != null && postKg != null && drinkMl != null,
        onClick = {
            val input = CalibrationInput(
                preKg = preKg!!, postKg = postKg!!,
                drinkMl = drinkMl!!, foodG = food.toIntOrNull() ?: 0,
                urinated = urinated, nowMs = System.currentTimeMillis(),
            )
            scope.launch { result = vm.calibrateHydration(r.rideId, input) }
        },
    ) { Text(stringResource(R.string.fueling_calib_button)) }
    when (val res = result) {
        is CalibrationResult.Accepted -> Text(
            stringResource(R.string.fueling_calib_result_ok, res.ratio.toDouble(), res.newMultiplierPct),
            style = MaterialTheme.typography.bodyMedium,
        )
        is CalibrationResult.Rejected -> Text(
            stringResource(rejectionRes(res.reason)),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
        )
        null -> {}
    }
}
