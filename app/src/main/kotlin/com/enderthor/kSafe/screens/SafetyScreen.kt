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
import androidx.compose.runtime.mutableIntStateOf
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
import com.enderthor.kSafe.data.CrashSensitivity
import kotlinx.coroutines.delay

/**
 * Safety tab — crash detection, speed-drop, check-in, emergency message, countdown, SOS color.
 *
 * Tools-style content (Karoo Live, calibration log, FIT export, backup/restore, Simulate Crash)
 * lives in [SettingsScreen] so this screen stays focused on "things that protect the rider".
 */
@Composable
fun SafetyScreen(vm: MainViewModel) {
    val config by vm.config.collectAsState()

    var emergencyMessage   by remember(config.emergencyMessage)            { mutableStateOf(config.emergencyMessage) }
    var countdownSeconds   by remember(config.countdownSeconds)            { mutableStateOf(config.countdownSeconds.toString()) }

    var crashEnabled         by remember(config.crashDetectionEnabled)     { mutableStateOf(config.crashDetectionEnabled) }
    var crashSensitivity     by remember(config.crashSensitivity)          { mutableStateOf(config.crashSensitivity) }
    var minSpeedForCrash     by remember(config.minSpeedForCrashKmh)       { mutableStateOf(config.minSpeedForCrashKmh.toString()) }
    var customThreshold      by remember(config.customCrashThreshold)      { mutableIntStateOf(config.customCrashThreshold) }
    var crashConfirmSpeed    by remember(config.crashConfirmSpeedKmh)      { mutableStateOf(config.crashConfirmSpeedKmh.toString()) }
    var crashOutsideRide     by remember(config.crashMonitorOutsideRide)   { mutableStateOf(config.crashMonitorOutsideRide) }
    var crashOutsideRideAny  by remember(config.crashMonitorOutsideRideAnySpeed) { mutableStateOf(config.crashMonitorOutsideRideAnySpeed) }

    var speedDropEnabled   by remember(config.speedDropDetectionEnabled)   { mutableStateOf(config.speedDropDetectionEnabled) }
    var speedDropMinutes   by remember(config.speedDropMinutes)            { mutableStateOf(config.speedDropMinutes.toString()) }

    var checkinEnabled     by remember(config.checkinEnabled)              { mutableStateOf(config.checkinEnabled) }
    var checkinInterval    by remember(config.checkinIntervalMinutes)      { mutableStateOf(config.checkinIntervalMinutes.toString()) }

    var sosFieldColor   by remember(config.sosFieldColor)   { mutableStateOf(config.sosFieldColor) }
    var timerFieldColor by remember(config.timerFieldColor) { mutableStateOf(config.timerFieldColor) }

    // Auto-save: runs whenever any setting changes, with a short debounce for text fields.
    // Karoo Live + calibration + backup live in SettingsScreen now and own their own save loops.
    LaunchedEffect(
        emergencyMessage, countdownSeconds,
        crashEnabled, crashSensitivity, minSpeedForCrash, customThreshold, crashConfirmSpeed,
        crashOutsideRide, crashOutsideRideAny,
        speedDropEnabled, speedDropMinutes,
        checkinEnabled, checkinInterval,
        sosFieldColor, timerFieldColor,
    ) {
        delay(600)
        vm.saveConfig(
            config.copy(
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
                sosFieldColor           = sosFieldColor,
                timerFieldColor         = timerFieldColor,
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

        FieldColorPicker(
            label = stringResource(R.string.sos_field_color_label),
            selected = sosFieldColor,
            onSelected = { sosFieldColor = it }
        )

        HorizontalDivider()

        // Crash detection
        SettingRow(label = stringResource(R.string.crash_detection_label)) {
            Switch(checked = crashEnabled, onCheckedChange = { crashEnabled = it })
        }
        // The Karoo buzzer is the only audio output; if the rider mutes the device,
        // alerts go silent and the SDK does not expose the mute state, so we cannot
        // detect or override it at runtime. Surface the limitation here so the rider
        // can make an informed choice when relying on KSafe for emergencies.
        Text(
            text = stringResource(R.string.safety_buzzer_mute_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

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

        FieldColorPicker(
            label = stringResource(R.string.timer_field_color_label),
            selected = timerFieldColor,
            onSelected = { timerFieldColor = it }
        )
    }
}

@Composable
internal fun SettingRow(label: String, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        content()
    }
}
