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
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
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
import com.enderthor.kSafe.data.CrashProfileSetting
import com.enderthor.kSafe.data.CrashSensitivity
import com.enderthor.kSafe.extension.KSafeExtension
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

    var crashProfileSettings by remember(config.crashProfileSettings) { mutableStateOf(config.crashProfileSettings) }

    // Reactive: updates immediately on a Karoo profile switch, no polling loop.
    val activeProfileId by KSafeExtension.activeProfileIdFlow.collectAsState()

    // Auto-save: runs whenever any setting changes, with a short debounce for text fields.
    // Karoo Live + calibration + backup live in SettingsScreen now and own their own save loops.
    LaunchedEffect(
        emergencyMessage, countdownSeconds,
        crashEnabled, crashSensitivity, minSpeedForCrash, customThreshold, crashConfirmSpeed,
        crashOutsideRide, crashOutsideRideAny,
        speedDropEnabled, speedDropMinutes,
        checkinEnabled, checkinInterval,
        sosFieldColor, timerFieldColor,
        crashProfileSettings,
    ) {
        delay(600)
        // Merge onto the LATEST config (not this composition snapshot) so a debounced
        // Safety save can't clobber an unrelated field — e.g. the master `isActive`
        // kill-switch or the calibration toggle changed on another tab within the 600 ms
        // window. Same lost-update fix already applied to Settings/Health/Fueling.
        vm.updateConfig {
            it.copy(
                emergencyMessage        = emergencyMessage,
                // Clamp on commit — a literal "0" parses as 0, which would skip the
                // entire cancel UI loop (`for (n in 0 downTo 1)` is an empty range)
                // and fire the alert with no rider abort window. Five seconds is
                // the documented minimum the SOS overlay can usefully render; 120 s
                // is the practical maximum any real rider would set.
                countdownSeconds        = (countdownSeconds.toIntOrNull() ?: 30).coerceIn(5, 120),
                crashDetectionEnabled   = crashEnabled,
                crashSensitivity        = crashSensitivity,
                customCrashThreshold    = customThreshold,
                crashConfirmSpeedKmh    = crashConfirmSpeed.toIntOrNull() ?: 5,
                minSpeedForCrashKmh     = minSpeedForCrash.toIntOrNull() ?: 5,
                crashMonitorOutsideRide = crashOutsideRide,
                crashMonitorOutsideRideAnySpeed = crashOutsideRideAny,
                speedDropDetectionEnabled = speedDropEnabled,
                // J4 — clamp speedDropMinutes on commit. The watchdog gates a
                // zero-speed window plus a 60-s accel-stillness gate. Floor is 5
                // minutes: this is an opt-in "rider may be down" backstop, and a
                // normal long stop (café, photo, mechanical) routinely reaches
                // 2-4 min with the bike laid down motionless, so anything below
                // 5 min just produces false positives on ordinary stops. 60
                // minutes is well above any realistic rider preference. A literal
                // "0" would have the timer fire immediately on every sub-3.5 km/h
                // speed sample.
                speedDropMinutes        = (speedDropMinutes.toIntOrNull() ?: 10).coerceIn(5, 60),
                checkinEnabled          = checkinEnabled,
                // J4 — clamp checkinIntervalMinutes on commit. A literal "0"
                // would persist 0 and cause delay(0) in startCheckinJobs →
                // CHECKIN_EXPIRED fires immediately on every subsequent ride
                // start, sending a false SOS to contacts within seconds. 10 min
                // is the documented practical minimum; 24 h is the practical
                // maximum.
                checkinIntervalMinutes  = (checkinInterval.toIntOrNull() ?: 120).coerceIn(10, 1440),
                sosFieldColor           = sosFieldColor,
                timerFieldColor         = timerFieldColor,
                crashProfileSettings    = crashProfileSettings,
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

        // ── Buzzer / mute warning banner ────────────────────────────────────
        // The Karoo has a piezo buzzer (not a speaker) and the SDK does not expose
        // the system mute state. Surface this at the TOP of the Safety tab so a
        // rider relying on KSafe for emergencies knows that muting the device
        // silences every audible alert, with no detection or override path.
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = stringResource(R.string.safety_buzzer_mute_title),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = stringResource(R.string.safety_buzzer_mute_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }

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

        if (crashEnabled) {
            Text(
                text = stringResource(R.string.crash_sensitivity_label),
                style = MaterialTheme.typography.bodyMedium
            )
            // First row: Low / Medium / High
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(CrashSensitivity.LOW, CrashSensitivity.MEDIUM, CrashSensitivity.HIGH).forEach { s ->
                    FilterChip(
                        selected = crashSensitivity == s,
                        modifier = Modifier.weight(1f),
                        colors = selectedFilterChipColors(),
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
                colors = selectedFilterChipColors(),
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
                    Text(stringResource(R.string.sensitivity_scale_min), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.sensitivity_scale_max), style = MaterialTheme.typography.labelSmall,
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

            // ── Per-profile crash overrides ───────────────────────────────────
            Text(
                text = stringResource(R.string.crash_per_profile_title),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.crash_per_profile_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (crashProfileSettings.isEmpty()) {
                Text(
                    text = stringResource(R.string.crash_per_profile_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // key() keeps each card's remember{} (text-field focus/selection) bound to its
                // profile across add/remove/reorder — otherwise Compose reuses slots by position
                // and a removed profile leaks its field state to its neighbour.
                val renderCard: @Composable (CrashProfileSetting) -> Unit = { setting ->
                    androidx.compose.runtime.key(setting.profileId) {
                        CrashProfileCard(
                            setting = setting,
                            isActive = setting.profileId == activeProfileId,
                            onChange = { updated ->
                                crashProfileSettings = crashProfileSettings.map {
                                    if (it.profileId == updated.profileId) updated else it
                                }
                            },
                            onRemove = {
                                crashProfileSettings = crashProfileSettings.filterNot { it.profileId == setting.profileId }
                            },
                        )
                    }
                }

                val activeOnes = crashProfileSettings.filter { it.profileId == activeProfileId }
                val customized = crashProfileSettings.filter { it.profileId != activeProfileId && !it.useGlobal }
                val globalStubs = crashProfileSettings.filter { it.profileId != activeProfileId && it.useGlobal }

                // Prominent cards: the active profile first, then any customised profiles.
                (activeOnes + customized).forEach { renderCard(it) }

                // The rest just inherit the global config — tuck them into a collapsible group
                // so a long profile list (10+ Karoo profiles) stays manageable.
                if (globalStubs.isNotEmpty()) {
                    var globalsExpanded by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { globalsExpanded = !globalsExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.crash_per_profile_global_group, globalStubs.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Icon(
                            imageVector = if (globalsExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = null
                        )
                    }
                    if (globalsExpanded) {
                        globalStubs.forEach { renderCard(it) }
                    }
                }
            }

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
            // Onboarding: the check-in is a dead-man's-switch the rider must actively
            // reset by tapping the field — riders who don't realise this let it expire
            // and land in a live SOS countdown. Spell out the reset gesture + escalating
            // warnings right where they enable it.
            Text(
                text = stringResource(R.string.checkin_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        FieldColorPicker(
            label = stringResource(R.string.timer_field_color_label),
            selected = timerFieldColor,
            onSelected = { timerFieldColor = it }
        )
    }
}

/** Stronger selected-state colours so the chosen preset chip is obvious on the Karoo's
 *  sunlight display (the default tint is too subtle to read as "selected"). */
@Composable
private fun selectedFilterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
)

@Composable
private fun CrashProfileCard(
    setting: CrashProfileSetting,
    isActive: Boolean,
    onChange: (CrashProfileSetting) -> Unit,
    onRemove: () -> Unit,
) {
    // Active profile starts expanded; the rest collapse so a long list stays manageable.
    var expanded by remember(setting.profileId) { mutableStateOf(isActive) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Tappable header: name + active badge + (when collapsed) a one-line summary +
            // an expand chevron. Collapsing keeps a long list of profiles manageable.
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = setting.profileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (isActive) {
                        Text(
                            text = stringResource(R.string.crash_per_profile_active),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (!expanded) {
                        Text(
                            text = when {
                                setting.useGlobal -> stringResource(R.string.crash_per_profile_summary_global)
                                !setting.crashDetectionEnabled -> stringResource(R.string.crash_per_profile_summary_off)
                                else -> stringResource(
                                    R.string.crash_per_profile_summary_custom,
                                    when (setting.crashSensitivity) {
                                        CrashSensitivity.LOW -> stringResource(R.string.sensitivity_low)
                                        CrashSensitivity.MEDIUM -> stringResource(R.string.sensitivity_medium)
                                        CrashSensitivity.HIGH -> stringResource(R.string.sensitivity_high)
                                        CrashSensitivity.CUSTOM -> stringResource(R.string.sensitivity_custom)
                                    }
                                )
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null
                )
            }

            if (expanded) {
            // Use global / Custom toggle
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = setting.useGlobal,
                    onClick = { onChange(setting.copy(useGlobal = true)) },
                    colors = selectedFilterChipColors(),
                    label = { Text(stringResource(R.string.crash_per_profile_use_global)) }
                )
                FilterChip(
                    selected = !setting.useGlobal,
                    onClick = { onChange(setting.copy(useGlobal = false)) },
                    colors = selectedFilterChipColors(),
                    label = { Text(stringResource(R.string.crash_per_profile_custom)) }
                )
            }

            // Per-profile custom controls (only when not using global)
            if (!setting.useGlobal) {
                // Enable/disable crash for this profile
                SettingRow(label = stringResource(R.string.crash_detection_label)) {
                    Switch(
                        checked = setting.crashDetectionEnabled,
                        onCheckedChange = { checked ->
                            onChange(setting.copy(crashDetectionEnabled = checked))
                        }
                    )
                }

                if (setting.crashDetectionEnabled) {
                    Text(
                        text = stringResource(R.string.crash_sensitivity_label),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    // First row: Low / Medium / High
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(CrashSensitivity.LOW, CrashSensitivity.MEDIUM, CrashSensitivity.HIGH).forEach { s ->
                            FilterChip(
                                selected = setting.crashSensitivity == s,
                                modifier = Modifier.weight(1f),
                                colors = selectedFilterChipColors(),
                                onClick = {
                                    val newMinSpeed = when (s) {
                                        CrashSensitivity.LOW    -> 3
                                        CrashSensitivity.MEDIUM -> 10
                                        CrashSensitivity.HIGH   -> 15
                                        CrashSensitivity.CUSTOM -> setting.minSpeedForCrashKmh
                                    }
                                    val newConfirmSpeed = when (s) {
                                        CrashSensitivity.LOW    -> 3
                                        CrashSensitivity.MEDIUM -> 5
                                        CrashSensitivity.HIGH   -> 5
                                        CrashSensitivity.CUSTOM -> setting.crashConfirmSpeedKmh
                                    }
                                    onChange(setting.copy(crashSensitivity = s, minSpeedForCrashKmh = newMinSpeed, crashConfirmSpeedKmh = newConfirmSpeed))
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
                        selected = setting.crashSensitivity == CrashSensitivity.CUSTOM,
                        onClick = { onChange(setting.copy(crashSensitivity = CrashSensitivity.CUSTOM)) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = selectedFilterChipColors(),
                        label = { Text(stringResource(R.string.sensitivity_custom)) }
                    )

                    // Custom threshold slider
                    if (setting.crashSensitivity == CrashSensitivity.CUSTOM) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.sensitivity_custom_threshold, setting.customCrashThreshold),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = when {
                                    setting.customCrashThreshold <= 35 -> "≈ High"
                                    setting.customCrashThreshold <= 50 -> "≈ Medium"
                                    else                               -> "≈ Low"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Slider(
                            value = setting.customCrashThreshold.toFloat(),
                            onValueChange = { v -> onChange(setting.copy(customCrashThreshold = v.toInt())) },
                            valueRange = 20f..70f,
                            steps = 49,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.sensitivity_scale_min), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.sensitivity_scale_max), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // Min speed field
                    var profileMinSpeed by remember(setting.profileId, setting.minSpeedForCrashKmh) {
                        mutableStateOf(setting.minSpeedForCrashKmh.toString())
                    }
                    OutlinedTextField(
                        value = profileMinSpeed,
                        onValueChange = { v ->
                            if (v.all { c -> c.isDigit() }) {
                                profileMinSpeed = v   // allow blank as an intermediate UI state
                                // Only commit a real number — otherwise clearing the field would
                                // leave it blank while silently writing the OLD value back.
                                v.toIntOrNull()?.let { onChange(setting.copy(minSpeedForCrashKmh = it)) }
                            }
                        },
                        label = { Text(stringResource(R.string.min_speed_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = {
                            Text(
                                when (setting.crashSensitivity) {
                                    CrashSensitivity.LOW    -> stringResource(R.string.min_speed_hint_low)
                                    CrashSensitivity.MEDIUM -> stringResource(R.string.min_speed_hint_medium)
                                    CrashSensitivity.HIGH   -> stringResource(R.string.min_speed_hint_high)
                                    CrashSensitivity.CUSTOM -> stringResource(R.string.min_speed_hint_custom)
                                }
                            )
                        }
                    )

                    // Confirm speed field
                    var profileConfirmSpeed by remember(setting.profileId, setting.crashConfirmSpeedKmh) {
                        mutableStateOf(setting.crashConfirmSpeedKmh.toString())
                    }
                    OutlinedTextField(
                        value = profileConfirmSpeed,
                        onValueChange = { v ->
                            if (v.all { c -> c.isDigit() }) {
                                profileConfirmSpeed = v   // allow blank as an intermediate UI state
                                v.toIntOrNull()?.let { onChange(setting.copy(crashConfirmSpeedKmh = it)) }
                            }
                        },
                        label = { Text(stringResource(R.string.crash_confirm_speed_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = { Text(stringResource(R.string.crash_confirm_speed_hint)) }
                    )
                }
            }

            androidx.compose.material3.TextButton(onClick = onRemove) {
                Text(stringResource(R.string.crash_per_profile_remove))
            }
            }
        }
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
