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
import androidx.compose.material3.OutlinedTextField
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
 * Tools tab — non-safety configuration and one-shot actions.
 *
 * Contents:
 *   - Karoo Live notifications (start/end ride messages with optional live-tracking key)
 *   - Notification tests (ride start, ride end, simulate crash with confirmation)
 *   - FIT export toggle (moved from FuelingScreen — conceptually about ride export, not fueling)
 *   - Calibration logging (opt-in sensor capture for algorithm tuning)
 *   - Backup & restore (export / import config JSON)
 *
 * The dangerous "Simulate Crash" button uses [TestActionButton]'s [ConfirmConfig] hook so a
 * gloved tap can't fire a real emergency message by accident.
 */
@Composable
fun ToolsScreen(vm: MainViewModel) {
    val config by vm.config.collectAsState()
    val context = LocalContext.current

    // ── Karoo Live state ──────────────────────────────────────────────────
    var karooLiveEnabled      by remember(config.karooLiveEnabled)         { mutableStateOf(config.karooLiveEnabled) }
    var karooLiveKey          by remember(config.karooLiveKey)             { mutableStateOf(config.karooLiveKey) }
    var karooLiveStartMessage by remember(config.karooLiveStartMessage)    { mutableStateOf(config.karooLiveStartMessage) }
    var karooLiveEndEnabled   by remember(config.karooLiveEndEnabled)      { mutableStateOf(config.karooLiveEndEnabled) }
    var karooLiveEndMessage   by remember(config.karooLiveEndMessage)      { mutableStateOf(config.karooLiveEndMessage) }

    // ── FIT export state ──────────────────────────────────────────────────
    var fitExportEnabled by remember(config.fuelingFitExportEnabled) { mutableStateOf(config.fuelingFitExportEnabled) }

    // ── Calibration logging state ────────────────────────────────────────
    var calibrationLogging by remember(config.calibrationLoggingEnabled) { mutableStateOf(config.calibrationLoggingEnabled) }
    var calibLogInfo       by remember { mutableStateOf("") }
    var calibLogNote       by remember { mutableStateOf("") }
    var calibLogNoteIsError by remember { mutableStateOf(false) }

    // ── Backup files ─────────────────────────────────────────────────────
    val exportFile = java.io.File(context.getExternalFilesDir(null), "ksafe_export.json")
    val importFile = java.io.File(context.getExternalFilesDir(null), "ksafe_import.json")

    // Auto-save Karoo Live + FIT toggle (calibrationLogging saves on change directly).
    LaunchedEffect(
        karooLiveEnabled, karooLiveKey, karooLiveStartMessage,
        karooLiveEndEnabled, karooLiveEndMessage,
        fitExportEnabled,
    ) {
        delay(600)
        vm.saveConfig(
            config.copy(
                karooLiveEnabled        = karooLiveEnabled,
                karooLiveKey            = karooLiveKey.trim(),
                karooLiveStartMessage   = karooLiveStartMessage,
                karooLiveEndEnabled     = karooLiveEndEnabled,
                karooLiveEndMessage     = karooLiveEndMessage,
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
            text = stringResource(R.string.tools_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        // ── Karoo Live ────────────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.tools_section_karoo_live),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                SettingRow(label = stringResource(R.string.karoo_live_label)) {
                    Switch(checked = karooLiveEnabled, onCheckedChange = { karooLiveEnabled = it })
                }
                if (karooLiveEnabled) {
                    Text(
                        text = stringResource(R.string.karoo_live_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = karooLiveKey,
                        onValueChange = { karooLiveKey = it },
                        label = { Text(stringResource(R.string.karoo_live_key_label)) },
                        placeholder = { Text("e.g. 3738Ag") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = { Text(stringResource(R.string.karoo_live_supporting)) }
                    )
                    OutlinedTextField(
                        value = karooLiveStartMessage,
                        onValueChange = { karooLiveStartMessage = it },
                        label = { Text(stringResource(R.string.karoo_live_message_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        supportingText = { Text(stringResource(R.string.karoo_live_message_hint)) }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                SettingRow(label = stringResource(R.string.karoo_live_end_label)) {
                    Switch(checked = karooLiveEndEnabled, onCheckedChange = { karooLiveEndEnabled = it })
                }
                if (karooLiveEndEnabled) {
                    OutlinedTextField(
                        value = karooLiveEndMessage,
                        onValueChange = { karooLiveEndMessage = it },
                        label = { Text(stringResource(R.string.karoo_live_end_message_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        supportingText = { Text(stringResource(R.string.karoo_live_end_message_hint)) }
                    )
                }
            }
        }

        HorizontalDivider()

        // ── Notification tests ────────────────────────────────────────────
        Text(
            text = stringResource(R.string.tools_section_tests),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        TestActionButton(
            label = "Test ride start notification",
            isSuccess = { it.startsWith("Ride start message sent") },
            onAction = {
                val ext = KSafeExtension.getInstance()
                    ?: return@TestActionButton "Extension not connected — wait a moment and try again."
                ext.sendTestRideStart()
            }
        )

        TestActionButton(
            label = stringResource(R.string.test_ride_end_notification),
            isSuccess = { it.startsWith("Ride end message sent") },
            onAction = {
                val ext = KSafeExtension.getInstance()
                    ?: return@TestActionButton "Extension not connected — wait a moment and try again."
                ext.sendTestRideEnd()
            }
        )

        // Simulate Crash — sends the real emergency message; guard with a confirmation dialog
        // so a gloved tap during a settings review can't fire it accidentally.
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
            text = stringResource(R.string.tools_section_fit),
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
            // Refresh entry count every 5s while logging is on.
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
                // Send button has its own status, so it's its own TestActionButton column.
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
            text = stringResource(R.string.tools_section_backup),
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
