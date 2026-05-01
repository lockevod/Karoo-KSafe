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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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

    var crashEnabled         by remember(config.crashDetectionEnabled)     { mutableStateOf(config.crashDetectionEnabled) }
    var crashSensitivity     by remember(config.crashSensitivity)          { mutableStateOf(config.crashSensitivity) }
    var minSpeedForCrash     by remember(config.minSpeedForCrashKmh)       { mutableStateOf(config.minSpeedForCrashKmh.toString()) }
    var customThreshold      by remember(config.customCrashThreshold)      { mutableStateOf(config.customCrashThreshold) }
    var crashConfirmSpeed    by remember(config.crashConfirmSpeedKmh)      { mutableStateOf(config.crashConfirmSpeedKmh.toString()) }
    var crashOutsideRide     by remember(config.crashMonitorOutsideRide)   { mutableStateOf(config.crashMonitorOutsideRide) }
    var crashOutsideRideAny  by remember(config.crashMonitorOutsideRideAnySpeed) { mutableStateOf(config.crashMonitorOutsideRideAnySpeed) }

    var speedDropEnabled   by remember(config.speedDropDetectionEnabled)   { mutableStateOf(config.speedDropDetectionEnabled) }
    var speedDropMinutes   by remember(config.speedDropMinutes)            { mutableStateOf(config.speedDropMinutes.toString()) }

    var checkinEnabled     by remember(config.checkinEnabled)              { mutableStateOf(config.checkinEnabled) }
    var checkinInterval    by remember(config.checkinIntervalMinutes)      { mutableStateOf(config.checkinIntervalMinutes.toString()) }

    var karooLiveEnabled      by remember(config.karooLiveEnabled)         { mutableStateOf(config.karooLiveEnabled) }
    var karooLiveKey          by remember(config.karooLiveKey)             { mutableStateOf(config.karooLiveKey) }
    var karooLiveStartMessage by remember(config.karooLiveStartMessage)    { mutableStateOf(config.karooLiveStartMessage) }
    var karooLiveEndEnabled   by remember(config.karooLiveEndEnabled)      { mutableStateOf(config.karooLiveEndEnabled) }
    var karooLiveEndMessage   by remember(config.karooLiveEndMessage)      { mutableStateOf(config.karooLiveEndMessage) }

    var calibrationLogging    by remember(config.calibrationLoggingEnabled) { mutableStateOf(config.calibrationLoggingEnabled) }


    var simulateStatus      by remember { mutableStateOf("") }
    var simulateIsError     by remember { mutableStateOf(false) }
    var rideStartStatus     by remember { mutableStateOf("") }
    var rideStartIsError    by remember { mutableStateOf(false) }
    var rideEndStatus       by remember { mutableStateOf("") }
    var rideEndIsError      by remember { mutableStateOf(false) }
    var calibLogStatus      by remember { mutableStateOf("") }
    var calibLogIsError     by remember { mutableStateOf(false) }
    var calibLogInfo        by remember { mutableStateOf("") }
    var backupStatus        by remember { mutableStateOf("") }
    var backupIsError       by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // Separate files for export and import — no permissions needed (app-specific external storage)
    val exportFile = java.io.File(context.getExternalFilesDir(null), "ksafe_export.json")
    val importFile = java.io.File(context.getExternalFilesDir(null), "ksafe_import.json")

    // Auto-save: runs whenever any setting changes, with a short debounce for text fields
    LaunchedEffect(
        isActive, emergencyMessage, countdownSeconds,
        crashEnabled, crashSensitivity, minSpeedForCrash, customThreshold, crashConfirmSpeed,
        crashOutsideRide, crashOutsideRideAny,
        speedDropEnabled, speedDropMinutes,
        checkinEnabled, checkinInterval,
        karooLiveEnabled, karooLiveKey, karooLiveStartMessage,
        karooLiveEndEnabled, karooLiveEndMessage,
        calibrationLogging,
    ) {
        delay(600)
        vm.saveConfig(
            config.copy(
                isActive                = isActive,
                emergencyMessage        = emergencyMessage,
                countdownSeconds        = countdownSeconds.toIntOrNull() ?: 30,
                crashDetectionEnabled   = crashEnabled,
                crashSensitivity        = crashSensitivity,
                customCrashThreshold    = customThreshold,
                crashConfirmSpeedKmh    = crashConfirmSpeed.toIntOrNull() ?: 5,
                minSpeedForCrashKmh     = minSpeedForCrash.toIntOrNull() ?: 5,
                crashMonitorOutsideRide = crashOutsideRide,
                crashMonitorOutsideRideAnySpeed = crashOutsideRideAny,
                speedDropDetectionEnabled = speedDropEnabled,
                speedDropMinutes        = speedDropMinutes.toIntOrNull() ?: 5,
                checkinEnabled          = checkinEnabled,
                checkinIntervalMinutes  = checkinInterval.toIntOrNull() ?: 120,
                karooLiveEnabled        = karooLiveEnabled,
                karooLiveKey            = karooLiveKey.trim(),
                karooLiveStartMessage   = karooLiveStartMessage,
                karooLiveEndEnabled     = karooLiveEndEnabled,
                karooLiveEndMessage     = karooLiveEndMessage,
                calibrationLoggingEnabled = calibrationLogging,
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

        // ── Karoo Live ────────────────────────────────────────────────────────
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
                    text = "Karoo Live",
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

                // ── Ride end notification ─────────────────────────────────────
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

        Text(
            text = stringResource(R.string.section_safety),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

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
            // First row: Low / Medium / High
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(CrashSensitivity.LOW, CrashSensitivity.MEDIUM, CrashSensitivity.HIGH).forEach { s ->
                    FilterChip(
                        selected = crashSensitivity == s,
                        onClick = {
                            crashSensitivity = s
                            minSpeedForCrash = when (s) {
                                CrashSensitivity.LOW    -> "3"
                                CrashSensitivity.MEDIUM -> "10"
                                CrashSensitivity.HIGH   -> "15"
                                CrashSensitivity.CUSTOM -> minSpeedForCrash
                            }
                            crashConfirmSpeed = when (s) {
                                CrashSensitivity.LOW    -> "3"
                                CrashSensitivity.MEDIUM -> "5"
                                CrashSensitivity.HIGH   -> "5"
                                CrashSensitivity.CUSTOM -> crashConfirmSpeed
                            }
                        },
                        label = {
                            Text(
                                when (s) {
                                    CrashSensitivity.LOW    -> stringResource(R.string.sensitivity_low)
                                    CrashSensitivity.MEDIUM -> stringResource(R.string.sensitivity_medium)
                                    CrashSensitivity.HIGH   -> stringResource(R.string.sensitivity_high)
                                    CrashSensitivity.CUSTOM -> ""
                                }
                            )
                        }
                    )
                }
            }
            // Second row: Custom (full width)
            FilterChip(
                selected = crashSensitivity == CrashSensitivity.CUSTOM,
                onClick = {
                    crashSensitivity = CrashSensitivity.CUSTOM
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.sensitivity_custom)) }
            )

            // Description for selected level
            Text(
                text = when (crashSensitivity) {
                    CrashSensitivity.LOW    -> stringResource(R.string.sensitivity_low_desc)
                    CrashSensitivity.MEDIUM -> stringResource(R.string.sensitivity_medium_desc)
                    CrashSensitivity.HIGH   -> stringResource(R.string.sensitivity_high_desc)
                    CrashSensitivity.CUSTOM -> stringResource(R.string.sensitivity_custom_desc)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Custom threshold slider
            if (crashSensitivity == CrashSensitivity.CUSTOM) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.sensitivity_custom_threshold, customThreshold),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = when {
                            customThreshold <= 35 -> "≈ High"
                            customThreshold <= 50 -> "≈ Medium"
                            else                  -> "≈ Low"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Slider(
                    value = customThreshold.toFloat(),
                    onValueChange = { customThreshold = it.toInt() },
                    valueRange = 20f..70f,
                    steps = 49,   // 1 m/s² steps between 20 and 70
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("20 m/s² (very sensitive)", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("70 m/s² (hard impacts only)", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            OutlinedTextField(
                value = minSpeedForCrash,
                onValueChange = { if (it.all { c -> c.isDigit() }) minSpeedForCrash = it },
                label = { Text(stringResource(R.string.min_speed_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = {
                    Text(
                        when (crashSensitivity) {
                            CrashSensitivity.LOW    -> stringResource(R.string.min_speed_hint_low)
                            CrashSensitivity.MEDIUM -> stringResource(R.string.min_speed_hint_medium)
                            CrashSensitivity.HIGH   -> stringResource(R.string.min_speed_hint_high)
                            CrashSensitivity.CUSTOM -> stringResource(R.string.min_speed_hint_custom)
                        }
                    )
                }
            )

            OutlinedTextField(
                value = crashConfirmSpeed,
                onValueChange = { if (it.all { c -> c.isDigit() }) crashConfirmSpeed = it },
                label = { Text(stringResource(R.string.crash_confirm_speed_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text(stringResource(R.string.crash_confirm_speed_hint)) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ── Monitor crash outside of ride ─────────────────────────────────
            SettingRow(label = stringResource(R.string.crash_outside_ride_label)) {
                Switch(
                    checked = crashOutsideRide,
                    onCheckedChange = {
                        crashOutsideRide = it
                        if (it) crashOutsideRideAny = false // mutual exclusion: any speed takes priority
                    }
                )
            }
            if (crashOutsideRide && !crashOutsideRideAny) {
                Text(
                    text = stringResource(R.string.crash_outside_ride_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SettingRow(label = stringResource(R.string.crash_outside_ride_any_speed_label)) {
                Switch(
                    checked = crashOutsideRideAny,
                    onCheckedChange = {
                        crashOutsideRideAny = it
                        if (it) crashOutsideRide = false // any speed supersedes the standard option
                    }
                )
            }
            if (crashOutsideRideAny) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFFF3E0) // amber-50
                    )
                ) {
                    Text(
                        text = stringResource(R.string.crash_outside_ride_any_speed_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF7B3800),
                        modifier = Modifier.padding(10.dp)
                    )
                }
            } else if (crashOutsideRide) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFFF8E1) // yellow-50
                    )
                ) {
                    Text(
                        text = stringResource(R.string.crash_outside_ride_warning, minSpeedForCrash.toIntOrNull() ?: 5),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF5D4037),
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
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

        // Test ride end notification
        Button(
            onClick = {
                rideEndStatus = "Sending…"
                rideEndIsError = false
                coroutineScope.launch {
                    val ext = KSafeExtension.getInstance()
                    if (ext == null) {
                        rideEndStatus = "Extension not connected — wait a moment and try again."
                        rideEndIsError = true
                    } else {
                        val msg = ext.sendTestRideEnd()
                        rideEndIsError = !msg.startsWith("Ride end message sent")
                        rideEndStatus = msg
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.test_ride_end_notification))
        }

        if (rideEndStatus.isNotEmpty()) {
            Text(
                text = rideEndStatus,
                style = MaterialTheme.typography.bodySmall,
                color = if (rideEndIsError) Color(0xFFB71C1C) else Color(0xFF2E7D32)
            )
        }

        // Simulate crash — sends the emergency message directly (no countdown)
        Button(
            onClick = {
                simulateStatus = "Sending…"
                simulateIsError = false
                coroutineScope.launch {
                    val ext = KSafeExtension.getInstance()
                    if (ext == null) {
                        simulateStatus = "Extension not connected — wait a moment and try again."
                        simulateIsError = true
                    } else {
                        val msg = ext.simulateCrash()
                        simulateStatus = msg
                        simulateIsError = !msg.startsWith("Test alert sent")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C))
        ) {
            Text("Simulate Crash (send alert now)")
        }

        if (simulateStatus.isNotEmpty()) {
            Text(
                text = simulateStatus,
                style = MaterialTheme.typography.bodySmall,
                color = if (simulateIsError) Color(0xFFB71C1C) else Color(0xFF2E7D32)
            )
        }

        HorizontalDivider()

        // ── Calibration logging ───────────────────────────────────────────────
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
                onCheckedChange = {
                    calibrationLogging = it
                    calibLogInfo = KSafeExtension.getInstance()?.getCalibrationLogInfo() ?: ""
                    calibLogStatus = if (it) "Logging enabled — data will be collected." else "Logging disabled."
                    calibLogIsError = false
                }
            )
        }

        if (calibrationLogging) {
            // Refresh entry count
            LaunchedEffect(calibrationLogging) {
                while (calibrationLogging) {
                    calibLogInfo = KSafeExtension.getInstance()?.getCalibrationLogInfo() ?: ""
                    kotlinx.coroutines.delay(5_000L)
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
                Button(
                    onClick = {
                        calibLogStatus = "Sending…"
                        calibLogIsError = false
                        coroutineScope.launch {
                            val ext = KSafeExtension.getInstance()
                            if (ext == null) {
                                calibLogStatus = "Extension not running."
                                calibLogIsError = true
                                return@launch
                            }
                            val result = ext.sendCalibrationLog()
                            calibLogStatus = result
                            calibLogIsError = !result.contains("✓")
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.calibration_send)) }

                Button(
                    onClick = {
                        KSafeExtension.getInstance()?.clearCalibrationLog()
                        calibLogStatus = "Log cleared."
                        calibLogIsError = false
                        calibLogInfo = ""
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) { Text(stringResource(R.string.calibration_clear)) }
            }

            Text(
                text = stringResource(R.string.calibration_adb_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (calibLogStatus.isNotEmpty()) {
            Text(
                text = calibLogStatus,
                style = MaterialTheme.typography.bodySmall,
                color = if (calibLogIsError) Color(0xFFB71C1C) else Color(0xFF2E7D32)
            )
        }

        HorizontalDivider()

        // Backup / Restore
        Text(
            text = "Export → ksafe_export.json  |  Import ← ksafe_import.json",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (backupStatus.isNotEmpty()) {
            Text(
                text = backupStatus,
                style = MaterialTheme.typography.bodySmall,
                color = if (backupIsError) Color(0xFFB71C1C) else Color(0xFF2E7D32)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    backupStatus = "Exporting…"
                    backupIsError = false
                    coroutineScope.launch {
                        try {
                            val json = vm.exportToJson()
                            withContext(Dispatchers.IO) {
                                exportFile.parentFile?.mkdirs()
                                exportFile.writeText(json)
                            }
                            backupStatus = "Exported to ksafe_export.json"
                            backupIsError = false
                        } catch (e: Exception) {
                            backupStatus = "Export failed: ${e.message}"
                            backupIsError = true
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.backup_export)) }

            Button(
                onClick = {
                    backupStatus = "Importing…"
                    backupIsError = false
                    coroutineScope.launch {
                        try {
                            val exists = withContext(Dispatchers.IO) { importFile.exists() }
                            if (!exists) {
                                backupStatus = "ksafe_import.json not found. See README."
                                backupIsError = true
                                return@launch
                            }
                            val json = withContext(Dispatchers.IO) { importFile.readText() }
                            val ok = vm.importFromJson(json)
                            backupStatus = if (ok) "Imported successfully." else "Import failed — invalid file."
                            backupIsError = !ok
                        } catch (e: Exception) {
                            backupStatus = "Import failed: ${e.message}"
                            backupIsError = true
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.backup_import)) }
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
