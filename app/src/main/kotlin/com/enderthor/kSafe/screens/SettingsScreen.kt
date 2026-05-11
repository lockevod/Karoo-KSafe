package com.enderthor.kSafe.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.enderthor.kSafe.R
import com.enderthor.kSafe.activity.MainViewModel
import com.enderthor.kSafe.extension.KSafeExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Settings tab — global configuration and maintenance actions.
 *
 * Contents:
 *   - Master enable switch (kill-switch for the whole extension)
 *   - Test alerts (Simulate Crash with confirmation)
 *   - FIT export toggle (writes fueling totals into the recorded FIT)
 *   - Calibration logging (opt-in sensor capture for algorithm tuning)
 *   - Backup & restore (config JSON via app-specific external storage)
 *
 * Per-feature toggles live on their feature tab — disable crash detection in Safety,
 * disable Health in Health, etc. The master switch here disables EVERYTHING at once.
 */
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val config by vm.config.collectAsState()
    val context = LocalContext.current

    var isActive          by remember(config.isActive)                  { mutableStateOf(config.isActive) }
    var fitExportEnabled  by remember(config.fuelingFitExportEnabled)   { mutableStateOf(config.fuelingFitExportEnabled) }
    var calibrationLogging by remember(config.calibrationLoggingEnabled) { mutableStateOf(config.calibrationLoggingEnabled) }
    var calibLogInfo       by remember { mutableStateOf("") }
    var calibLogNote       by remember { mutableStateOf("") }
    var calibLogNoteIsError by remember { mutableStateOf(false) }

    val exportFile = java.io.File(context.getExternalFilesDir(null), "ksafe_export.json")
    val importFile = java.io.File(context.getExternalFilesDir(null), "ksafe_import.json")

    LaunchedEffect(isActive, fitExportEnabled) {
        delay(600)
        vm.saveConfig(
            config.copy(
                isActive                = isActive,
                fuelingFitExportEnabled = fitExportEnabled,
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_screen_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        // ── Master enable ─────────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_section_master),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                SettingRow(label = stringResource(R.string.active_label)) {
                    Switch(checked = isActive, onCheckedChange = { isActive = it })
                }
                Text(
                    text = stringResource(R.string.settings_master_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider()

        // ── Test alerts ───────────────────────────────────────────────────
        Text(
            text = stringResource(R.string.settings_section_tests),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Real-world emergency message via the active provider. Confirm dialog avoids gloved
        // double-taps during a settings review from firing a real alert.
        TestActionButton(
            label = stringResource(R.string.simulate_crash_label),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
            isSuccess = { it.startsWith("Test alert sent") },
            requireConfirmation = ConfirmConfig(
                title = stringResource(R.string.simulate_crash_confirm_title),
                body  = stringResource(R.string.simulate_crash_confirm_body),
                confirmLabel = stringResource(R.string.dialog_confirm_send),
                cancelLabel  = stringResource(R.string.dialog_cancel),
            ),
            onAction = {
                val ext = KSafeExtension.getInstance()
                    ?: return@TestActionButton "Extension not connected — wait a moment and try again."
                ext.simulateCrash()
            }
        )

        HorizontalDivider()

        // ── FIT export ────────────────────────────────────────────────────
        Text(
            text = stringResource(R.string.settings_section_fit),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SettingRow(label = stringResource(R.string.fueling_fit_export_label)) {
            Switch(checked = fitExportEnabled, onCheckedChange = { fitExportEnabled = it })
        }
        Text(
            text = stringResource(R.string.fueling_fit_export_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider()

        // ── Calibration logging ───────────────────────────────────────────
        Text(
            text = stringResource(R.string.section_calibration),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.calibration_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SettingRow(label = stringResource(R.string.calibration_logging_label)) {
            Switch(
                checked = calibrationLogging,
                onCheckedChange = { newValue ->
                    calibrationLogging = newValue
                    vm.saveConfig(config.copy(calibrationLoggingEnabled = newValue))
                    calibLogInfo = KSafeExtension.getInstance()?.getCalibrationLogInfo() ?: ""
                    calibLogNote = if (newValue) "Logging enabled — data will be collected." else "Logging disabled."
                    calibLogNoteIsError = false
                }
            )
        }

        if (calibrationLogging) {
            LaunchedEffect(calibrationLogging) {
                while (calibrationLogging) {
                    calibLogInfo = KSafeExtension.getInstance()?.getCalibrationLogInfo() ?: ""
                    delay(5_000L)
                }
            }
            if (calibLogInfo.isNotEmpty()) {
                Text(
                    text = calibLogInfo,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    TestActionButton(
                        label = stringResource(R.string.calibration_send),
                        isSuccess = { it.contains("✓") },
                        onAction = {
                            val ext = KSafeExtension.getInstance()
                                ?: return@TestActionButton "Extension not running."
                            ext.sendCalibrationLog()
                        }
                    )
                }
                Button(
                    onClick = {
                        KSafeExtension.getInstance()?.clearCalibrationLog()
                        calibLogInfo = ""
                        calibLogNote = "Log cleared."
                        calibLogNoteIsError = false
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) { Text(stringResource(R.string.calibration_clear)) }
            }

            if (calibLogNote.isNotEmpty()) {
                Text(
                    text = calibLogNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (calibLogNoteIsError) Color(0xFFB71C1C) else Color(0xFF2E7D32)
                )
            }

            Text(
                text = stringResource(R.string.calibration_adb_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider()

        // ── Backup / Restore ──────────────────────────────────────────────
        Text(
            text = stringResource(R.string.settings_section_backup),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Export → ksafe_export.json  |  Import ← ksafe_import.json",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                TestActionButton(
                    label = stringResource(R.string.backup_export),
                    runningLabel = "Exporting…",
                    isSuccess = { it.startsWith("Exported") },
                    onAction = {
                        try {
                            val json = vm.exportToJson()
                            withContext(Dispatchers.IO) {
                                exportFile.parentFile?.mkdirs()
                                exportFile.writeText(json)
                            }
                            "Exported to ksafe_export.json"
                        } catch (e: Exception) {
                            "Export failed: ${e.message}"
                        }
                    }
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                TestActionButton(
                    label = stringResource(R.string.backup_import),
                    runningLabel = "Importing…",
                    isSuccess = { it == "Imported successfully." },
                    onAction = {
                        try {
                            val exists = withContext(Dispatchers.IO) { importFile.exists() }
                            if (!exists) {
                                "ksafe_import.json not found. See README."
                            } else {
                                val json = withContext(Dispatchers.IO) { importFile.readText() }
                                val ok = vm.importFromJson(json)
                                if (ok) "Imported successfully." else "Import failed — invalid file."
                            }
                        } catch (e: Exception) {
                            "Import failed: ${e.message}"
                        }
                    }
                )
            }
        }
    }
}
