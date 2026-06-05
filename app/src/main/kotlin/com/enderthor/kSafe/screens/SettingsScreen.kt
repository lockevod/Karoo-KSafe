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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.enderthor.kSafe.R
import com.enderthor.kSafe.activity.BackupStorage
import com.enderthor.kSafe.activity.MainViewModel
import com.enderthor.kSafe.extension.KSafeExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    val scope = rememberCoroutineScope()

    var isActive          by remember(config.isActive)                  { mutableStateOf(config.isActive) }
    var fitExportEnabled  by remember(config.fuelingFitExportEnabled)   { mutableStateOf(config.fuelingFitExportEnabled) }
    var calibrationLogging by remember(config.calibrationLoggingEnabled) { mutableStateOf(config.calibrationLoggingEnabled) }
    var updateCheck       by remember(config.updateCheckEnabled)        { mutableStateOf(config.updateCheckEnabled) }
    var buzzerOnEmergency by remember(config.buzzerOnEmergencyEnabled)  { mutableStateOf(config.buzzerOnEmergencyEnabled) }
    var calibLogInfo       by remember { mutableStateOf("") }
    var calibLogNote       by remember { mutableStateOf("") }
    var calibLogNoteIsError by remember { mutableStateOf(false) }

    // Legacy app-private dir kept ONLY as an import fallback for users whose file is still there.
    val legacyBackupDir = context.getExternalFilesDir(null)
    // API < 30 runtime-permission launcher (no-op on 30+, which uses the settings deep-link).
    val writePermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { /* result consumed on the user's next Export/Import tap via BackupStorage.hasAccess */ }

    // Returns null if backup access is ready; otherwise kicks off the grant flow and returns a
    // status string for the button to show. Lazy — only ever runs on an Export/Import tap.
    fun ensureBackupAccess(): String? {
        if (BackupStorage.hasAccess(context)) return null
        val intent = BackupStorage.allFilesSettingsIntent(context)
        return when {
            intent != null -> { context.startActivity(intent); context.getString(R.string.backup_grant_opening) }
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R -> {
                writePermLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                context.getString(R.string.backup_needs_access)
            }
            else -> context.getString(R.string.backup_grant_adb)
        }
    }

    LaunchedEffect(isActive, fitExportEnabled, buzzerOnEmergency) {
        delay(600)
        // Merge onto the LATEST config (not the captured `config` snapshot) so this
        // debounced save can't clobber an unrelated field — e.g. the calibration toggle
        // saved immediately in the same window. See MainViewModel.updateConfig.
        vm.updateConfig {
            it.copy(
                isActive                  = isActive,
                fuelingFitExportEnabled   = fitExportEnabled,
                buzzerOnEmergencyEnabled  = buzzerOnEmergency,
            )
        }
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

        // ── Buzzer-on-emergency (HAL bypass) ──────────────────────────────
        // Toggle + test button for the private-API path that routes emergency-class beeps
        // (countdown last 5s, ALERTING entry) directly to the Karoo's physical buzzer,
        // bypassing the rider's audio-alerts mute. ON by default — a safety extension
        // should be heard in a crash; riders who deliberately mute can opt out here.
        SettingRow(label = stringResource(R.string.settings_buzzer_bypass_label)) {
            Switch(checked = buzzerOnEmergency, onCheckedChange = { buzzerOnEmergency = it })
        }
        Text(
            text = stringResource(R.string.settings_buzzer_bypass_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Update-availability notice — opt-out toggle (default on). Gates the periodic,
        // ride-idle overlay that tells the rider a newer KSafe build is published.
        SettingRow(label = stringResource(R.string.update_check_label)) {
            Switch(
                checked = updateCheck,
                onCheckedChange = { newValue ->
                    updateCheck = newValue
                    // Merge onto the latest config (not the captured snapshot) so this
                    // immediate save can't clobber the debounced batch save.
                    vm.updateConfig { it.copy(updateCheckEnabled = newValue) }
                },
            )
        }

        // Diagnostic button — binds the HAL service and plays a short test tone. Useful
        // for confirming the bypass works after a Karoo OTA (Hammerhead can gate the
        // service in any future update).
        // B26: localised result strings. The button captions, the running label, and
        // the four diagnostic-message branches all resolve through R.string at compose
        // time. `isSuccess` compares against the same localised `beepOkMessage` that
        // the success branch returns, so the classification holds in any locale (no
        // substring matching against English-specific text).
        val testLabel = stringResource(R.string.settings_buzzer_test_label)
        val runningLabel = stringResource(R.string.settings_buzzer_test_running)
        val beepOkMessage = stringResource(R.string.settings_buzzer_test_beep_ok)
        val gatedMessage = stringResource(R.string.settings_buzzer_test_gated)
        val transactFailedMessage = stringResource(R.string.settings_buzzer_test_transact_failed)
        TestActionButton(
            label = testLabel,
            runningLabel = runningLabel,
            isSuccess = { it == beepOkMessage },
            onAction = {
                val client = com.enderthor.kSafe.extension.managers.BuzzerClient(context)
                try {
                    val bindDiag = client.connect()
                    // Bind is async; wait briefly for onServiceConnected. Bail out after 2s.
                    // Use the monotonic clock (`elapsedRealtime`) instead of wall-clock so an
                    // NTP step / user date change during the bind window can't make the loop
                    // exit early (negative remaining time) or spin past the intended budget.
                    val deadline = android.os.SystemClock.elapsedRealtime() + 2_000L
                    while (!client.isReady() && android.os.SystemClock.elapsedRealtime() < deadline) {
                        delay(50)
                    }
                    if (!client.isReady()) {
                        // Failure A: bind itself was refused. Most likely cause if it
                        // worked before: a Karoo OTA changed the service exports.
                        context.getString(R.string.settings_buzzer_test_bind_failed, bindDiag)
                    } else {
                        val ok = client.beep(com.enderthor.kSafe.extension.managers.BuzzerClient.TEST_PATTERN)
                        when {
                            ok -> beepOkMessage
                            client.lastResult == com.enderthor.kSafe.extension.managers.BuzzerClient.BeepResult.GATED_BY_SECURITY ->
                                gatedMessage
                            client.lastResult == com.enderthor.kSafe.extension.managers.BuzzerClient.BeepResult.TRANSACT_THREW ->
                                transactFailedMessage
                            else ->
                                context.getString(R.string.settings_buzzer_test_failed_other, client.lastResult.toString())
                        }
                    }
                } finally {
                    // Give the HAL a moment to actually emit before tearing the bind down.
                    delay(800)
                    client.disconnect()
                }
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

        // Show the persistent install ID so users can reference it when
        // reporting issues via Telegram. Visible regardless of logging state.
        val installId by produceState(initialValue = "") {
            // getInstance() can still be null when this screen first composes if
            // the extension service has not bound yet — produceState runs its
            // block only once, so keep polling until the install ID is available.
            // The coroutine is cancelled when this screen leaves composition, so
            // an unbounded loop only lives as long as the screen is visible.
            while (value.isEmpty()) {
                val id = KSafeExtension.getInstance()?.getInstallIdForUi() ?: ""
                if (id.isNotEmpty()) value = id else delay(500)
            }
        }
        if (installId.isNotEmpty()) {
            Text(
                text = stringResource(R.string.calibration_install_id_label, installId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }

        SettingRow(label = stringResource(R.string.calibration_logging_label)) {
            Switch(
                checked = calibrationLogging,
                onCheckedChange = { newValue ->
                    calibrationLogging = newValue
                    // Merge onto the latest config (not the captured snapshot) so this
                    // immediate save and the debounced isActive/fit/buzzer save can't
                    // clobber each other — fully closes the lost-update class.
                    vm.updateConfig { it.copy(calibrationLoggingEnabled = newValue) }
                    // calibLogInfo is refreshed by the LaunchedEffect below (its first
                    // iteration runs immediately) — do NOT read it here: getCalibrationLogInfo()
                    // scans the whole CSV + reads the previous file, which on this non-suspend
                    // Main-thread callback would jank the UI.
                    calibLogNote = if (newValue) "Logging enabled — data will be collected." else "Logging disabled."
                    calibLogNoteIsError = false
                }
            )
        }

        if (calibrationLogging) {
            LaunchedEffect(calibrationLogging) {
                while (calibrationLogging) {
                    // getCalibrationLogInfo() is suspend + hops to Dispatchers.IO internally
                    // (it scans the whole CSV + reads the previous file) — safe to call here.
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
                        // clearCalibrationLog() is suspend (deletes files on Dispatchers.IO);
                        // launch off the Main onClick so the delete never janks the UI.
                        scope.launch {
                            KSafeExtension.getInstance()?.clearCalibrationLog()
                            calibLogInfo = ""
                            calibLogNote = "Log cleared."
                            calibLogNoteIsError = false
                        }
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
            text = stringResource(R.string.backup_path_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Privacy notice: the export is a plaintext credential dump (bot tokens, API keys,
        // recipient numbers). Surfaced in error colour so the rider knows to keep it private.
        Text(
            text = stringResource(R.string.backup_secrets_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
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
                        ensureBackupAccess()?.let { return@TestActionButton it }
                        try {
                            val json = vm.exportToJson()
                            val target = BackupStorage.exportFile()
                            withContext(Dispatchers.IO) {
                                target.parentFile?.mkdirs()
                                target.writeText(json)
                            }
                            "Exported to /sdcard/KSafe/${BackupStorage.EXPORT_NAME}"
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
                        ensureBackupAccess()?.let { return@TestActionButton it }
                        try {
                            val file = withContext(Dispatchers.IO) {
                                BackupStorage.resolveImportFile(BackupStorage.backupDir(), legacyBackupDir)
                            }
                            if (file == null) {
                                "${BackupStorage.IMPORT_NAME} not found in /sdcard/KSafe/. See README."
                            } else {
                                val json = withContext(Dispatchers.IO) { file.readText() }
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

        // Discreet docs footer. Karoo can't open URLs from a Compose Activity, so this
        // is plain text the rider reads and looks up later on their phone.
        Text(
            text = stringResource(R.string.settings_docs_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
