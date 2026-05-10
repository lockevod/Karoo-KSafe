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
import androidx.compose.material3.FilterChip
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

    var wellnessEnabled         by remember(config.wellnessEnabled)              { mutableStateOf(config.wellnessEnabled) }
    var wellnessResponseLevel   by remember(config.wellnessResponseLevel)        { mutableStateOf(coerceVisible(config.wellnessResponseLevel, IncidentResponseLevel.WARNING)) }
    var wellnessThreshold       by remember(config.wellnessHighHrThreshold)      { mutableStateOf(config.wellnessHighHrThreshold.toString()) }
    var wellnessUseMaxHrPercent by remember(config.wellnessUseMaxHrPercent)      { mutableStateOf(config.wellnessUseMaxHrPercent) }
    var wellnessHrPercent       by remember(config.wellnessHighHrPercent)        { mutableStateOf(config.wellnessHighHrPercent.toString()) }
    var wellnessDuration        by remember(config.wellnessHighHrDurationMinutes){ mutableStateOf(config.wellnessHighHrDurationMinutes.toString()) }

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
                ResponseLevelChips(
                    selected = medicalResponseLevel,
                    onSelected = {
                        medicalResponseLevel = it
                        vm.saveConfig(config.copy(medicalResponseLevel = it))
                    },
                )
            }
        }

        // ── Wellness monitor ─────────────────────────────────────────────────
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
                if (wellnessUseMaxHrPercent) {
                    OutlinedTextField(
                        value = wellnessHrPercent,
                        onValueChange = { v ->
                            wellnessHrPercent = v.filter { it.isDigit() }.take(3)
                            val parsed = wellnessHrPercent.toIntOrNull()
                            if (parsed != null && parsed in 60..100) {
                                vm.saveConfig(config.copy(wellnessHighHrPercent = parsed))
                            }
                        },
                        label = { Text(stringResource(R.string.health_wellness_threshold_pct_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                } else {
                    OutlinedTextField(
                        value = wellnessThreshold,
                        onValueChange = { v ->
                            wellnessThreshold = v.filter { it.isDigit() }.take(3)
                            val parsed = wellnessThreshold.toIntOrNull()
                            if (parsed != null && parsed in 80..250) {
                                vm.saveConfig(config.copy(wellnessHighHrThreshold = parsed))
                            }
                        },
                        label = { Text(stringResource(R.string.health_wellness_threshold_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                OutlinedTextField(
                    value = wellnessDuration,
                    onValueChange = { v ->
                        wellnessDuration = v.filter { it.isDigit() }.take(3)
                        val parsed = wellnessDuration.toIntOrNull()
                        if (parsed != null && parsed in 5..240) {
                            vm.saveConfig(config.copy(wellnessHighHrDurationMinutes = parsed))
                        }
                    },
                    label = { Text(stringResource(R.string.health_wellness_duration_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
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
 * Compact horizontal chip row for picking response level. Only WARNING / EMERGENCY are
 * exposed — SILENT does not make sense for an enabled detector and would confuse users.
 */
@Composable
private fun ResponseLevelChips(
    selected: IncidentResponseLevel,
    onSelected: (IncidentResponseLevel) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = selected == IncidentResponseLevel.WARNING,
            onClick = { onSelected(IncidentResponseLevel.WARNING) },
            label = { Text(stringResource(R.string.health_response_warning)) },
            modifier = Modifier.weight(1f),
        )
        FilterChip(
            selected = selected == IncidentResponseLevel.EMERGENCY,
            onClick = { onSelected(IncidentResponseLevel.EMERGENCY) },
            label = { Text(stringResource(R.string.health_response_emergency)) },
            modifier = Modifier.weight(1f),
        )
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
