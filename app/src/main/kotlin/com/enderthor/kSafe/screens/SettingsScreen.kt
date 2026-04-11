package com.enderthor.kSafe.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.enderthor.kSafe.R
import com.enderthor.kSafe.activity.MainViewModel
import com.enderthor.kSafe.data.CrashSensitivity
import com.enderthor.kSafe.extension.KSafeExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val config by vm.config.collectAsState()

    var isActive           by remember(config.isActive)                   { mutableStateOf(config.isActive) }
    var emergencyMessage   by remember(config.emergencyMessage)            { mutableStateOf(config.emergencyMessage) }
    var countdownSeconds   by remember(config.countdownSeconds)            { mutableStateOf(config.countdownSeconds.toString()) }

    var crashEnabled       by remember(config.crashDetectionEnabled)       { mutableStateOf(config.crashDetectionEnabled) }
    var crashSensitivity   by remember(config.crashSensitivity)            { mutableStateOf(config.crashSensitivity) }
    var minSpeedForCrash   by remember(config.minSpeedForCrashKmh)         { mutableStateOf(config.minSpeedForCrashKmh.toString()) }

    var speedDropEnabled   by remember(config.speedDropDetectionEnabled)   { mutableStateOf(config.speedDropDetectionEnabled) }
    var speedDropMinutes   by remember(config.speedDropMinutes)            { mutableStateOf(config.speedDropMinutes.toString()) }

    var checkinEnabled     by remember(config.checkinEnabled)              { mutableStateOf(config.checkinEnabled) }
    var checkinInterval    by remember(config.checkinIntervalMinutes)      { mutableStateOf(config.checkinIntervalMinutes.toString()) }

    var karooLiveEnabled      by remember(config.karooLiveEnabled)         { mutableStateOf(config.karooLiveEnabled) }
    var karooLiveKey          by remember(config.karooLiveKey)             { mutableStateOf(config.karooLiveKey) }
    var karooLiveStartMessage by remember(config.karooLiveStartMessage)    { mutableStateOf(config.karooLiveStartMessage) }

    var simulateStatus      by remember { mutableStateOf("") }
    var simulateIsError     by remember { mutableStateOf(false) }
    var rideStartStatus     by remember { mutableStateOf("") }
    var rideStartIsError    by remember { mutableStateOf(false) }
    var backupStatus        by remember { mutableStateOf("") }
    var backupIsError       by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // Export: user picks where to save the .json file
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            try {
                val json = vm.exportToJson()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                }
                backupStatus = "Configuration exported successfully."
                backupIsError = false
            } catch (e: Exception) {
                backupStatus = "Export failed: ${e.message}"
                backupIsError = true
            }
        }
    }

    // Import: user picks a previously exported .json file
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                } ?: throw IllegalStateException("Could not read file")
                val ok = vm.importFromJson(json)
                if (ok) {
                    backupStatus = "Configuration imported successfully."
                    backupIsError = false
                } else {
                    backupStatus = "Import failed — invalid file format."
                    backupIsError = true
                }
            } catch (e: Exception) {
                backupStatus = "Import failed: ${e.message}"
                backupIsError = true
            }
        }
    }

    // Auto-save: runs whenever any setting changes, with a short debounce for text fields
    LaunchedEffect(
        isActive, emergencyMessage, countdownSeconds,
        crashEnabled, crashSensitivity, minSpeedForCrash,
        speedDropEnabled, speedDropMinutes,
        checkinEnabled, checkinInterval,
        karooLiveEnabled, karooLiveKey, karooLiveStartMessage
    ) {
        delay(600)
        vm.saveConfig(
            config.copy(
                isActive                = isActive,
                emergencyMessage        = emergencyMessage,
                countdownSeconds        = countdownSeconds.toIntOrNull() ?: 30,
                crashDetectionEnabled   = crashEnabled,
                crashSensitivity        = crashSensitivity,
                minSpeedForCrashKmh     = minSpeedForCrash.toIntOrNull() ?: 10,
                speedDropDetectionEnabled = speedDropEnabled,
                speedDropMinutes        = speedDropMinutes.toIntOrNull() ?: 5,
                checkinEnabled          = checkinEnabled,
                checkinIntervalMinutes  = checkinInterval.toIntOrNull() ?: 120,
                karooLiveEnabled        = karooLiveEnabled,
                karooLiveKey            = karooLiveKey.trim(),
                karooLiveStartMessage   = karooLiveStartMessage,
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
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        // Extension active toggle
        SettingRow(label = stringResource(R.string.active_label)) {
            Switch(checked = isActive, onCheckedChange = { isActive = it })
        }

        // Karoo Live — right after active toggle so the ride-start feature is grouped with it
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

        HorizontalDivider()

        // Emergency message
        OutlinedTextField(
            value = emergencyMessage,
            onValueChange = { emergencyMessage = it },
            label = { Text(stringResource(R.string.emergency_message_label)) },
            placeholder = { Text(stringResource(R.string.emergency_message_hint), style = MaterialTheme.typography.bodySmall) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )

        // Countdown seconds
        OutlinedTextField(
            value = countdownSeconds,
            onValueChange = { if (it.all { c -> c.isDigit() }) countdownSeconds = it },
            label = { Text(stringResource(R.string.countdown_seconds_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        HorizontalDivider()

        // Crash detection
        SettingRow(label = stringResource(R.string.crash_detection_label)) {
            Switch(checked = crashEnabled, onCheckedChange = { crashEnabled = it })
        }

        if (crashEnabled) {
            Text(
                text = stringResource(R.string.crash_sensitivity_label),
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CrashSensitivity.entries.forEach { s ->
                    FilterChip(
                        selected = crashSensitivity == s,
                        onClick = { crashSensitivity = s },
                        label = {
                            Text(
                                when (s) {
                                    CrashSensitivity.LOW    -> stringResource(R.string.sensitivity_low)
                                    CrashSensitivity.MEDIUM -> stringResource(R.string.sensitivity_medium)
                                    CrashSensitivity.HIGH   -> stringResource(R.string.sensitivity_high)
                                }
                            )
                        }
                    )
                }
            }

            OutlinedTextField(
                value = minSpeedForCrash,
                onValueChange = { if (it.all { c -> c.isDigit() }) minSpeedForCrash = it },
                label = { Text("Min. speed to detect crash (km/h, 0 = always)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text("0 = detect at any speed (for testing). Recommended: 10") }
            )
        }

        HorizontalDivider()

        // Speed drop detection
        SettingRow(label = stringResource(R.string.speed_drop_label)) {
            Switch(checked = speedDropEnabled, onCheckedChange = { speedDropEnabled = it })
        }

        if (speedDropEnabled) {
            OutlinedTextField(
                value = speedDropMinutes,
                onValueChange = { if (it.all { c -> c.isDigit() }) speedDropMinutes = it },
                label = { Text(stringResource(R.string.speed_drop_minutes_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        HorizontalDivider()

        // Check-in timer
        SettingRow(label = stringResource(R.string.checkin_timer_label)) {
            Switch(checked = checkinEnabled, onCheckedChange = { checkinEnabled = it })
        }

        if (checkinEnabled) {
            OutlinedTextField(
                value = checkinInterval,
                onValueChange = { if (it.all { c -> c.isDigit() }) checkinInterval = it },
                label = { Text(stringResource(R.string.checkin_interval_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        HorizontalDivider()

        Text(
            text = "Testing",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Test ride start notification
        Button(
            onClick = {
                rideStartStatus = "Sending…"
                rideStartIsError = false
                coroutineScope.launch {
                    val ext = KSafeExtension.getInstance()
                    if (ext == null) {
                        rideStartStatus = "Extension not connected — wait a moment and try again."
                        rideStartIsError = true
                    } else {
                        val msg = ext.sendTestRideStart()
                        rideStartIsError = !msg.startsWith("Ride start message sent")
                        rideStartStatus = msg
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Test ride start notification")
        }

        if (rideStartStatus.isNotEmpty()) {
            Text(
                text = rideStartStatus,
                style = MaterialTheme.typography.bodySmall,
                color = if (rideStartIsError) Color(0xFFB71C1C) else Color(0xFF2E7D32)
            )
        }

        // Simulate crash — triggers the full emergency countdown flow
        Button(
            onClick = {
                val ext = KSafeExtension.getInstance()
                if (ext == null) {
                    simulateStatus = "Extension not connected — wait a moment and try again."
                    simulateIsError = true
                } else {
                    val msg = ext.simulateCrash()
                    simulateStatus = msg
                    simulateIsError = msg.startsWith("Extension is disabled")
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C))
        ) {
            Text("Simulate Crash (test countdown)")
        }

        if (simulateStatus.isNotEmpty()) {
            Text(
                text = simulateStatus,
                style = MaterialTheme.typography.bodySmall,
                color = if (simulateIsError) Color(0xFFB71C1C) else Color(0xFF2E7D32)
            )
        }

        HorizontalDivider()

        // Backup / Restore
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { exportLauncher.launch("ksafe_backup.json") },
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.backup_export)) }

            Button(
                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.backup_import)) }
        }

        if (backupStatus.isNotEmpty()) {
            Text(
                text = backupStatus,
                style = MaterialTheme.typography.bodySmall,
                color = if (backupIsError) Color(0xFFB71C1C) else Color(0xFF2E7D32)
            )
        }
    }
}

@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        content()
    }
}
