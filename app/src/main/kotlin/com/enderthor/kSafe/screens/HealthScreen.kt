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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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
 * Health monitoring tab — exposes the user-configurable knobs for [MedicalEpisodeDetector]
 * and [WellnessMonitor]. Internal thresholds (HR_FLATLINE_MAX_BPM, HR_COLLAPSE_DROP_FRACTION,
 * etc.) are NOT exposed; they are calibrated in code from real ride data.
 */
@Composable
fun HealthScreen(vm: MainViewModel) {
    val config by vm.config.collectAsState()

    var medicalEnabled       by remember(config.medicalEpisodeEnabled)        { mutableStateOf(config.medicalEpisodeEnabled) }
    var medicalResponseLevel by remember(config.medicalResponseLevel)         { mutableStateOf(config.medicalResponseLevel) }

    var wellnessEnabled        by remember(config.wellnessEnabled)              { mutableStateOf(config.wellnessEnabled) }
    var wellnessResponseLevel  by remember(config.wellnessResponseLevel)        { mutableStateOf(config.wellnessResponseLevel) }
    var wellnessThreshold      by remember(config.wellnessHighHrThreshold)      { mutableStateOf(config.wellnessHighHrThreshold.toString()) }
    var wellnessDuration       by remember(config.wellnessHighHrDurationMinutes){ mutableStateOf(config.wellnessHighHrDurationMinutes.toString()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.health_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        // ── HR sensor warning banner ─────────────────────────────────────────
        // Always shown for now: persistent reminder that these features need a HR sensor.
        // A future iteration may hide it once HR data is observed in the current session.
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
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.health_medical_section),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.health_medical_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                HealthRow(label = stringResource(R.string.health_enabled_label)) {
                    Switch(
                        checked = medicalEnabled,
                        onCheckedChange = {
                            medicalEnabled = it
                            vm.saveConfig(config.copy(medicalEpisodeEnabled = it))
                        }
                    )
                }
                HealthRow(label = stringResource(R.string.health_response_level_label)) {
                    ResponseLevelDropdown(
                        selected = medicalResponseLevel,
                        onSelected = {
                            medicalResponseLevel = it
                            vm.saveConfig(config.copy(medicalResponseLevel = it))
                        }
                    )
                }
            }
        }

        // ── Wellness monitor ─────────────────────────────────────────────────
        Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.health_wellness_section),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.health_wellness_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                HealthRow(label = stringResource(R.string.health_enabled_label)) {
                    Switch(
                        checked = wellnessEnabled,
                        onCheckedChange = {
                            wellnessEnabled = it
                            vm.saveConfig(config.copy(wellnessEnabled = it))
                        }
                    )
                }
                HealthRow(label = stringResource(R.string.health_response_level_label)) {
                    ResponseLevelDropdown(
                        selected = wellnessResponseLevel,
                        onSelected = {
                            wellnessResponseLevel = it
                            vm.saveConfig(config.copy(wellnessResponseLevel = it))
                        }
                    )
                }
                OutlinedTextField(
                    value = wellnessThreshold,
                    onValueChange = { v ->
                        wellnessThreshold = v.filter { it.isDigit() }.take(3)
                        wellnessThreshold.toIntOrNull()?.let {
                            vm.saveConfig(config.copy(wellnessHighHrThreshold = it.coerceIn(80, 250)))
                        }
                    },
                    label = { Text(stringResource(R.string.health_wellness_threshold_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = wellnessDuration,
                    onValueChange = { v ->
                        wellnessDuration = v.filter { it.isDigit() }.take(3)
                        wellnessDuration.toIntOrNull()?.let {
                            vm.saveConfig(config.copy(wellnessHighHrDurationMinutes = it.coerceIn(5, 240)))
                        }
                    },
                    label = { Text(stringResource(R.string.health_wellness_duration_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun HealthRow(label: String, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResponseLevelDropdown(
    selected: IncidentResponseLevel,
    onSelected: (IncidentResponseLevel) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val labels = mapOf(
        IncidentResponseLevel.SILENT    to stringResource(R.string.health_response_silent),
        IncidentResponseLevel.WARNING   to stringResource(R.string.health_response_warning),
        IncidentResponseLevel.EMERGENCY to stringResource(R.string.health_response_emergency),
    )
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        TextField(
            value = labels[selected] ?: selected.name,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            IncidentResponseLevel.entries.forEach { lvl ->
                DropdownMenuItem(
                    text = { Text(labels[lvl] ?: lvl.name) },
                    onClick = {
                        onSelected(lvl)
                        expanded = false
                    }
                )
            }
        }
    }
}
