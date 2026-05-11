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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import com.enderthor.kSafe.data.IncidentResponseLevel

/**
 * Health monitoring tab — exposes the user-configurable knobs for MedicalEpisodeDetector
 * and WellnessMonitor. Internal thresholds (HR_FLATLINE_MAX_BPM, HR_COLLAPSE_DROP_FRACTION,
 * etc.) are NOT exposed; they are calibrated in code from real ride data.
 *
 * The response-level UI exposes only WARNING and EMERGENCY. SILENT remains in the enum
 * (used by tests / future programmatic dispatch) but does not make sense for an *enabled*
 * detector — if you don't want any notification, the master switch already covers that.
 */
@Composable
fun HealthScreen(vm: MainViewModel) {
    val config by vm.config.collectAsState()

    var medicalEnabled       by remember(config.medicalEpisodeEnabled)        { mutableStateOf(config.medicalEpisodeEnabled) }
    var medicalResponseLevel by remember(config.medicalResponseLevel)         { mutableStateOf(coerceVisible(config.medicalResponseLevel, IncidentResponseLevel.EMERGENCY)) }
    var medicalCustomTitle   by remember(config.medicalCustomTitle)           { mutableStateOf(config.medicalCustomTitle) }
    var medicalCustomDetail  by remember(config.medicalCustomDetail)          { mutableStateOf(config.medicalCustomDetail) }

    var wellnessEnabled         by remember(config.wellnessEnabled)              { mutableStateOf(config.wellnessEnabled) }
    var readinessAtRideStartEnabled by remember(config.readinessAtRideStartEnabled) { mutableStateOf(config.readinessAtRideStartEnabled) }
    var wellnessResponseLevel   by remember(config.wellnessResponseLevel)        { mutableStateOf(coerceVisible(config.wellnessResponseLevel, IncidentResponseLevel.WARNING)) }
    var wellnessUseMaxHrPercent by remember(config.wellnessUseMaxHrPercent)      { mutableStateOf(config.wellnessUseMaxHrPercent) }
    // Sustained tier (existing fields)
    var wSustainedOn            by remember(config.wellnessSustainedEnabled)     { mutableStateOf(config.wellnessSustainedEnabled) }
    var wSustainedThresholdBpm  by remember(config.wellnessHighHrThreshold)      { mutableStateOf(config.wellnessHighHrThreshold.toString()) }
    var wSustainedThresholdPct  by remember(config.wellnessHighHrPercent)        { mutableStateOf(config.wellnessHighHrPercent.toString()) }
    var wSustainedDuration      by remember(config.wellnessHighHrDurationMinutes){ mutableStateOf(config.wellnessHighHrDurationMinutes.toString()) }
    var wSustainedCustomTitle   by remember(config.wellnessSustainedCustomTitle) { mutableStateOf(config.wellnessSustainedCustomTitle) }
    var wSustainedCustomDetail  by remember(config.wellnessSustainedCustomDetail){ mutableStateOf(config.wellnessSustainedCustomDetail) }
    // Critical tier
    var wCriticalOn             by remember(config.wellnessCriticalEnabled)         { mutableStateOf(config.wellnessCriticalEnabled) }
    var wCriticalThresholdBpm   by remember(config.wellnessCriticalThresholdBpm)    { mutableStateOf(config.wellnessCriticalThresholdBpm.toString()) }
    var wCriticalThresholdPct   by remember(config.wellnessCriticalThresholdPct)    { mutableStateOf(config.wellnessCriticalThresholdPct.toString()) }
    var wCriticalDuration       by remember(config.wellnessCriticalDurationMinutes) { mutableStateOf(config.wellnessCriticalDurationMinutes.toString()) }
    var wCriticalCustomTitle    by remember(config.wellnessCriticalCustomTitle)     { mutableStateOf(config.wellnessCriticalCustomTitle) }
    var wCriticalCustomDetail   by remember(config.wellnessCriticalCustomDetail)    { mutableStateOf(config.wellnessCriticalCustomDetail) }
    // Decoupling tier
    var wDecouplingOn           by remember(config.wellnessDecouplingEnabled)        { mutableStateOf(config.wellnessDecouplingEnabled) }
    var wDecouplingThreshold    by remember(config.wellnessDecouplingThresholdPct)   { mutableStateOf(config.wellnessDecouplingThresholdPct.toString()) }
    var wDecouplingDuration     by remember(config.wellnessDecouplingDurationMinutes){ mutableStateOf(config.wellnessDecouplingDurationMinutes.toString()) }
    var wDecouplingCustomTitle  by remember(config.wellnessDecouplingCustomTitle)    { mutableStateOf(config.wellnessDecouplingCustomTitle) }
    var wDecouplingCustomDetail by remember(config.wellnessDecouplingCustomDetail)   { mutableStateOf(config.wellnessDecouplingCustomDetail) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.health_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        // ── HR sensor warning banner ─────────────────────────────────────────
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD)),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = stringResource(R.string.health_no_hr_warning_title),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF856404)
                )
                Text(
                    text = stringResource(R.string.health_no_hr_warning_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF664D03)
                )
            }
        }

        // ── Medical episode detection ────────────────────────────────────────
        Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SectionHeader(stringResource(R.string.health_medical_section))
                EnableRow(
                    label = stringResource(R.string.health_enabled_label),
                    checked = medicalEnabled,
                    onCheckedChange = {
                        medicalEnabled = it
                        vm.saveConfig(config.copy(medicalEpisodeEnabled = it))
                    },
                )
                if (medicalEnabled) {
                    ResponseLevelChips(
                        selected = medicalResponseLevel,
                        onSelected = {
                            medicalResponseLevel = it
                            vm.saveConfig(config.copy(medicalResponseLevel = it))
                        },
                    )
                    CustomAlertField(
                        label = "Custom title",
                        value = medicalCustomTitle,
                        onCommit = { v -> medicalCustomTitle = v; vm.saveConfig(config.copy(medicalCustomTitle = v)) },
                        defaultText = stringResource(R.string.warning_medical_title),
                        maxLength = 30,
                    )
                    CustomAlertField(
                        label = "Custom detail",
                        value = medicalCustomDetail,
                        onCommit = { v -> medicalCustomDetail = v; vm.saveConfig(config.copy(medicalCustomDetail = v)) },
                        defaultText = stringResource(R.string.warning_medical_detail),
                        tokensHint = "Tokens: {bpm}",
                        maxLength = 80,
                        singleLine = false,
                    )
                }
            }
        }

        // ── Wellness monitor (three-tier model) ──────────────────────────────
        Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SectionHeader(stringResource(R.string.health_wellness_section))
                EnableRow(
                    label = stringResource(R.string.health_enabled_label),
                    checked = wellnessEnabled,
                    onCheckedChange = {
                        wellnessEnabled = it
                        vm.saveConfig(config.copy(wellnessEnabled = it))
                    },
                )
                if (wellnessEnabled) {
                ResponseLevelChips(
                    selected = wellnessResponseLevel,
                    onSelected = {
                        wellnessResponseLevel = it
                        vm.saveConfig(config.copy(wellnessResponseLevel = it))
                    },
                )
                EnableRow(
                    label = stringResource(R.string.health_wellness_use_pct_label),
                    checked = wellnessUseMaxHrPercent,
                    onCheckedChange = {
                        wellnessUseMaxHrPercent = it
                        vm.saveConfig(config.copy(wellnessUseMaxHrPercent = it))
                    },
                )

                // ── Tier 1: Critical HR ─────────────────────────────────────
                androidx.compose.material3.HorizontalDivider()
                EnableRow(
                    label = stringResource(R.string.health_wellness_tier_critical),
                    checked = wCriticalOn,
                    onCheckedChange = {
                        wCriticalOn = it
                        vm.saveConfig(config.copy(wellnessCriticalEnabled = it))
                    },
                )
                if (wCriticalOn) {
                if (wellnessUseMaxHrPercent) {
                    OutlinedTextField(
                        value = wCriticalThresholdPct,
                        onValueChange = { v ->
                            wCriticalThresholdPct = v.filter { it.isDigit() }.take(3)
                            wCriticalThresholdPct.toIntOrNull()?.let { p ->
                                if (p in 60..100) vm.saveConfig(config.copy(wellnessCriticalThresholdPct = p))
                            }
                        },
                        label = { Text(stringResource(R.string.health_wellness_critical_threshold_pct_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                } else {
                    OutlinedTextField(
                        value = wCriticalThresholdBpm,
                        onValueChange = { v ->
                            wCriticalThresholdBpm = v.filter { it.isDigit() }.take(3)
                            wCriticalThresholdBpm.toIntOrNull()?.let { p ->
                                if (p in 80..250) vm.saveConfig(config.copy(wellnessCriticalThresholdBpm = p))
                            }
                        },
                        label = { Text(stringResource(R.string.health_wellness_critical_threshold_bpm_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                OutlinedTextField(
                    value = wCriticalDuration,
                    onValueChange = { v ->
                        wCriticalDuration = v.filter { it.isDigit() }.take(3)
                        wCriticalDuration.toIntOrNull()?.let { p ->
                            if (p in 1..60) vm.saveConfig(config.copy(wellnessCriticalDurationMinutes = p))
                        }
                    },
                    label = { Text(stringResource(R.string.health_wellness_critical_duration_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                CustomAlertField(
                    label = "Critical alert title",
                    value = wCriticalCustomTitle,
                    onCommit = { v -> wCriticalCustomTitle = v; vm.saveConfig(config.copy(wellnessCriticalCustomTitle = v)) },
                    defaultText = stringResource(R.string.warning_wellness_critical_hr_title),
                    maxLength = 30,
                )
                CustomAlertField(
                    label = "Critical alert detail",
                    value = wCriticalCustomDetail,
                    onCommit = { v -> wCriticalCustomDetail = v; vm.saveConfig(config.copy(wellnessCriticalCustomDetail = v)) },
                    defaultText = stringResource(R.string.warning_wellness_critical_hr_detail),
                    tokensHint = "Tokens: {bpm}, {threshold}, {minutes}",
                    maxLength = 80,
                    singleLine = false,
                )
                }  // end if (wCriticalOn)

                // ── Tier 2: Sustained HR ────────────────────────────────────
                androidx.compose.material3.HorizontalDivider()
                EnableRow(
                    label = stringResource(R.string.health_wellness_tier_sustained),
                    checked = wSustainedOn,
                    onCheckedChange = {
                        wSustainedOn = it
                        vm.saveConfig(config.copy(wellnessSustainedEnabled = it))
                    },
                )
                if (wSustainedOn) {
                if (wellnessUseMaxHrPercent) {
                    OutlinedTextField(
                        value = wSustainedThresholdPct,
                        onValueChange = { v ->
                            wSustainedThresholdPct = v.filter { it.isDigit() }.take(3)
                            wSustainedThresholdPct.toIntOrNull()?.let { p ->
                                if (p in 60..100) vm.saveConfig(config.copy(wellnessHighHrPercent = p))
                            }
                        },
                        label = { Text(stringResource(R.string.health_wellness_threshold_pct_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                } else {
                    OutlinedTextField(
                        value = wSustainedThresholdBpm,
                        onValueChange = { v ->
                            wSustainedThresholdBpm = v.filter { it.isDigit() }.take(3)
                            wSustainedThresholdBpm.toIntOrNull()?.let { p ->
                                if (p in 80..250) vm.saveConfig(config.copy(wellnessHighHrThreshold = p))
                            }
                        },
                        label = { Text(stringResource(R.string.health_wellness_threshold_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                OutlinedTextField(
                    value = wSustainedDuration,
                    onValueChange = { v ->
                        wSustainedDuration = v.filter { it.isDigit() }.take(3)
                        wSustainedDuration.toIntOrNull()?.let { p ->
                            if (p in 5..240) vm.saveConfig(config.copy(wellnessHighHrDurationMinutes = p))
                        }
                    },
                    label = { Text(stringResource(R.string.health_wellness_duration_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                CustomAlertField(
                    label = "Sustained alert title",
                    value = wSustainedCustomTitle,
                    onCommit = { v -> wSustainedCustomTitle = v; vm.saveConfig(config.copy(wellnessSustainedCustomTitle = v)) },
                    defaultText = stringResource(R.string.warning_wellness_high_hr_title),
                    maxLength = 30,
                )
                CustomAlertField(
                    label = "Sustained alert detail",
                    value = wSustainedCustomDetail,
                    onCommit = { v -> wSustainedCustomDetail = v; vm.saveConfig(config.copy(wellnessSustainedCustomDetail = v)) },
                    defaultText = stringResource(R.string.warning_wellness_high_hr_detail),
                    tokensHint = "Tokens: {bpm}, {threshold}, {minutes}",
                    maxLength = 80,
                    singleLine = false,
                )
                }  // end if (wSustainedOn)

                // ── Tier 3: Cardiac decoupling (requires power) ─────────────
                androidx.compose.material3.HorizontalDivider()
                EnableRow(
                    label = stringResource(R.string.health_wellness_tier_decoupling),
                    checked = wDecouplingOn,
                    onCheckedChange = {
                        wDecouplingOn = it
                        vm.saveConfig(config.copy(wellnessDecouplingEnabled = it))
                    },
                )
                if (wDecouplingOn) {
                Text(
                    text = stringResource(R.string.health_wellness_decoupling_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = wDecouplingThreshold,
                    onValueChange = { v ->
                        wDecouplingThreshold = v.filter { it.isDigit() }.take(2)
                        wDecouplingThreshold.toIntOrNull()?.let { p ->
                            if (p in 3..30) vm.saveConfig(config.copy(wellnessDecouplingThresholdPct = p))
                        }
                    },
                    label = { Text(stringResource(R.string.health_wellness_decoupling_threshold_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = wDecouplingDuration,
                    onValueChange = { v ->
                        wDecouplingDuration = v.filter { it.isDigit() }.take(3)
                        wDecouplingDuration.toIntOrNull()?.let { p ->
                            if (p in 1..60) vm.saveConfig(config.copy(wellnessDecouplingDurationMinutes = p))
                        }
                    },
                    label = { Text(stringResource(R.string.health_wellness_decoupling_duration_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                CustomAlertField(
                    label = "Decoupling alert title",
                    value = wDecouplingCustomTitle,
                    onCommit = { v -> wDecouplingCustomTitle = v; vm.saveConfig(config.copy(wellnessDecouplingCustomTitle = v)) },
                    defaultText = stringResource(R.string.warning_wellness_decoupling_title),
                    maxLength = 30,
                )
                CustomAlertField(
                    label = "Decoupling alert detail",
                    value = wDecouplingCustomDetail,
                    onCommit = { v -> wDecouplingCustomDetail = v; vm.saveConfig(config.copy(wellnessDecouplingCustomDetail = v)) },
                    defaultText = stringResource(R.string.warning_wellness_decoupling_detail),
                    tokensHint = "Tokens: {drift}, {minutes}",
                    maxLength = 80,
                    singleLine = false,
                )
                }  // end if (wDecouplingOn)

                // ── Readiness advice at ride start (cross-tier — uses ALL wellness data) ─
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                EnableRow(
                    label = stringResource(R.string.readiness_at_ride_start_label),
                    checked = readinessAtRideStartEnabled,
                    onCheckedChange = {
                        readinessAtRideStartEnabled = it
                        vm.saveConfig(config.copy(readinessAtRideStartEnabled = it))
                    },
                )
                Text(
                    text = stringResource(R.string.readiness_at_ride_start_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                }  // end if (wellnessEnabled)
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun EnableRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Compact horizontal segmented selector for response level. Only WARNING / EMERGENCY are
 * exposed — SILENT does not make sense for an enabled detector.
 *
 * `SingleChoiceSegmentedButtonRow` is preferred over `FilterChip` because:
 *  - the buttons share width equally without any internal checkmark padding shifting the label,
 *  - the click target is the full button bounds (FilterChip's icon padding can cause the user
 *    to click on what looks like one chip but registers on the other),
 *  - the typography is naturally tighter, so labels like "Emergency" fit in one line on the
 *    Karoo's narrow screen without wrapping.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResponseLevelChips(
    selected: IncidentResponseLevel,
    onSelected: (IncidentResponseLevel) -> Unit,
) {
    val options = listOf(IncidentResponseLevel.WARNING, IncidentResponseLevel.EMERGENCY)
    val labels = mapOf(
        IncidentResponseLevel.WARNING   to stringResource(R.string.health_response_warning),
        IncidentResponseLevel.EMERGENCY to stringResource(R.string.health_response_emergency),
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, level ->
            SegmentedButton(
                selected = selected == level,
                onClick = { onSelected(level) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(
                    text = labels[level] ?: level.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * If a stored config has SILENT (from the previous UI version that exposed it), coerce to
 * a sensible default on first display so the chip row always shows a selection. The stored
 * value is left alone until the user explicitly picks a chip.
 */
private fun coerceVisible(
    stored: IncidentResponseLevel,
    fallback: IncidentResponseLevel,
): IncidentResponseLevel = when (stored) {
    IncidentResponseLevel.SILENT -> fallback
    else -> stored
}
