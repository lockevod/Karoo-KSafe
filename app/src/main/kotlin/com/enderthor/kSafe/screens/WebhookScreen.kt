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
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.enderthor.kSafe.R
import com.enderthor.kSafe.activity.MainViewModel
import com.enderthor.kSafe.data.WEBHOOK_SLOT_COUNT
import com.enderthor.kSafe.data.WebhookSlot
import com.enderthor.kSafe.data.webhookSlot
import com.enderthor.kSafe.data.withWebhookSlot
import com.enderthor.kSafe.extension.KSafeExtension
import com.enderthor.kSafe.extension.util.safeTake
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Locale-tolerant coordinate parse. On comma-decimal locales (the es translation shipped)
 * a KeyboardType.Decimal field emits "41,38"; plain `toDoubleOrNull()` rejected it, so the
 * geo-fence target silently persisted as 0.0/0.0 (Null Island) and a geo-fenced webhook
 * never fired. Comma is normalised to dot; NaN/Inf rejected.
 */
internal fun String.toGeoDoubleOrNull(): Double? =
    trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

/**
 * Save-time coordinate resolution. Blank = the rider cleared the field → 0.0 (unset,
 * the storage convention). Unparseable NON-blank = a typo / edit in progress (isError
 * is showing) → KEEP the previously stored coordinate: persisting 0.0 here destroyed a
 * valid geo-fence target mid-edit, and the `remember(config.…)` re-seed then wiped the
 * rider's typed text in place.
 */
internal fun String.geoOrStored(stored: Double): Double =
    if (isBlank()) 0.0 else toGeoDoubleOrNull() ?: stored

/**
 * UI-layer state holder for a single webhook slot. Mirrors [WebhookSlot] but keeps the
 * geo-coordinate Doubles and the radius Int as String fields so they can be edited in text
 * fields without lossy round-trips (e.g. an empty radius field mid-edit must not snap to 1).
 */
private data class WebhookUiSlot(
    val slot: WebhookSlot,
    val geoLatText: String,
    val geoLonText: String,
    val geoRadiusText: String,
)

private fun WebhookSlot.toUiSlot() = WebhookUiSlot(
    slot = this,
    geoLatText = if (geoLat == 0.0) "" else geoLat.toString(),
    geoLonText = if (geoLon == 0.0) "" else geoLon.toString(),
    geoRadiusText = geoRadiusM.toString(),
)

@Composable
fun ActionsScreen(vm: MainViewModel) {
    val config by vm.config.collectAsState()

    // ── Custom messages ───────────────────────────────────────────────────────
    var customMessageEnabled  by remember(config.customMessageEnabled)  { mutableStateOf(config.customMessageEnabled) }
    var customMessageTitle    by remember(config.customMessageTitle)    { mutableStateOf(config.customMessageTitle) }
    var customMessage         by remember(config.customMessage)         { mutableStateOf(config.customMessage) }
    var customMessage2Enabled by remember(config.customMessage2Enabled) { mutableStateOf(config.customMessage2Enabled) }
    var customMessage2Title   by remember(config.customMessage2Title)   { mutableStateOf(config.customMessage2Title) }
    var customMessage2        by remember(config.customMessage2)        { mutableStateOf(config.customMessage2) }
    var customMessage3Enabled by remember(config.customMessage3Enabled) { mutableStateOf(config.customMessage3Enabled) }
    var customMessage3Title   by remember(config.customMessage3Title)   { mutableStateOf(config.customMessage3Title) }
    var customMessage3        by remember(config.customMessage3)        { mutableStateOf(config.customMessage3) }

    // ── Custom message field colours ─────────────────────────────────────────
    var customMsg1Color by remember(config.customMsg1Color) { mutableStateOf(config.customMsg1Color) }
    var customMsg2Color by remember(config.customMsg2Color) { mutableStateOf(config.customMsg2Color) }
    var customMsg3Color by remember(config.customMsg3Color) { mutableStateOf(config.customMsg3Color) }

    // ── Webhook slots 1–4 ─────────────────────────────────────────────────────
    // Reseed ONLY when a webhook slot's STORED content actually changes (WebhookSlot is a
    // data class → structural equality), NOT on every unrelated config save. Keying on the
    // whole `config` object would reseed the list whenever an unrelated field on this screen
    // (custom message, Karoo Live) debounce-saved, wiping the rider's in-flight webhook edits.
    // This mirrors the per-field `remember(config.X)` scoping used elsewhere on this screen.
    val webhookSlots: SnapshotStateList<WebhookUiSlot> = remember(
        config.webhookSlot(1), config.webhookSlot(2),
        config.webhookSlot(3), config.webhookSlot(4),
    ) {
        (1..WEBHOOK_SLOT_COUNT).map { i -> config.webhookSlot(i).toUiSlot() }.toMutableStateList()
    }

    // ── Karoo Live notifications ──────────────────────────────────────────────
    var karooLiveEnabled      by remember(config.karooLiveEnabled)         { mutableStateOf(config.karooLiveEnabled) }
    var karooLiveKey          by remember(config.karooLiveKey)             { mutableStateOf(config.karooLiveKey) }
    var karooLiveStartMessage by remember(config.karooLiveStartMessage)    { mutableStateOf(config.karooLiveStartMessage) }
    var karooLiveEndEnabled   by remember(config.karooLiveEndEnabled)      { mutableStateOf(config.karooLiveEndEnabled) }
    var karooLiveEndMessage   by remember(config.karooLiveEndMessage)      { mutableStateOf(config.karooLiveEndMessage) }

    // Auto-save with debounce — all fields managed by this screen.
    // webhookSlots[*] is spread individually so each mutation triggers the effect.
    val ws0 = webhookSlots.getOrNull(0)
    val ws1 = webhookSlots.getOrNull(1)
    val ws2 = webhookSlots.getOrNull(2)
    val ws3 = webhookSlots.getOrNull(3)
    LaunchedEffect(
        customMessageEnabled, customMessage, customMessageTitle,
        customMessage2Enabled, customMessage2, customMessage2Title,
        customMessage3Enabled, customMessage3, customMessage3Title,
        customMsg1Color, customMsg2Color, customMsg3Color,
        ws0, ws1, ws2, ws3,
        karooLiveEnabled, karooLiveKey, karooLiveStartMessage,
        karooLiveEndEnabled, karooLiveEndMessage,
    ) {
        delay(600)
        // Route through updateConfig (fresh-read under settingsWriteMutex) instead of
        // saveConfig(config.copy(...)): the composition `config` snapshot can be stale and an
        // unguarded full-blob write would clobber fields owned by OTHER screens and race the
        // mutex-guarded writes. Applying the copy to the freshly-read `current` preserves them.
        vm.updateConfig { current ->
            // Start with custom messages + karoo live fields
            var updated = current.copy(
                customMessageEnabled    = customMessageEnabled,
                customMessageTitle      = customMessageTitle.safeTake(7).ifBlank { "MSG" },
                customMessage           = customMessage,
                customMessage2Enabled   = customMessage2Enabled,
                customMessage2Title     = customMessage2Title.safeTake(7).ifBlank { "MSG2" },
                customMessage2          = customMessage2,
                customMessage3Enabled   = customMessage3Enabled,
                customMessage3Title     = customMessage3Title.safeTake(7).ifBlank { "MSG3" },
                customMessage3          = customMessage3,
                customMsg1Color         = customMsg1Color,
                customMsg2Color         = customMsg2Color,
                customMsg3Color         = customMsg3Color,
                karooLiveEnabled        = karooLiveEnabled,
                karooLiveKey            = karooLiveKey.trim(),
                karooLiveStartMessage   = karooLiveStartMessage,
                karooLiveEndEnabled     = karooLiveEndEnabled,
                karooLiveEndMessage     = karooLiveEndMessage,
            )
            // Fold webhook slots 1–4 into the config via the slot writer
            for (i in 0 until WEBHOOK_SLOT_COUNT) {
                val ui = webhookSlots.getOrNull(i) ?: continue
                val storedSlot = current.webhookSlot(i + 1)
                val resolved = ui.slot.copy(
                    geoLat     = ui.geoLatText.geoOrStored(storedSlot.geoLat),
                    geoLon     = ui.geoLonText.geoOrStored(storedSlot.geoLon),
                    geoRadiusM = ui.geoRadiusText.toIntOrNull()?.coerceAtLeast(1) ?: 50,
                )
                updated = updated.withWebhookSlot(i + 1, resolved)
            }
            updated
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
            text = stringResource(R.string.tab_actions),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        // ── Karoo Live ────────────────────────────────────────────────────────
        // Lives in Actions because "notify contacts on ride start/end" is conceptually a
        // notification action, paired with the test buttons that fire those notifications.
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
                    text = stringResource(R.string.actions_section_karoo_live),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                ActionSettingRow(label = stringResource(R.string.karoo_live_label)) {
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

                ActionSettingRow(label = stringResource(R.string.karoo_live_end_label)) {
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

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Tests pair with the Karoo Live card — they fire the same start/end messages
                // through the active provider so the rider can verify the channel works.
                Text(
                    text = stringResource(R.string.actions_section_karoo_live_tests),
                    style = MaterialTheme.typography.bodySmall,
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
            }
        }

        // ── Custom Messages card ──────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.custom_message_section),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = stringResource(R.string.custom_message_send_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // ── Slot 1 ──────────────────────────────────────────────────
                ActionSettingRow(label = stringResource(R.string.custom_message_label)) {
                    Switch(checked = customMessageEnabled, onCheckedChange = { customMessageEnabled = it })
                }
                if (customMessageEnabled) {
                    OutlinedTextField(
                        value = customMessageTitle,
                        onValueChange = { if (it.length <= 7) customMessageTitle = it },
                        label = { Text(stringResource(R.string.custom_message_title_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = { Text(stringResource(R.string.custom_message_title_desc)) }
                    )
                    OutlinedTextField(
                        value = customMessage,
                        onValueChange = { customMessage = it },
                        label = { Text(stringResource(R.string.custom_message_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                    FieldColorPicker(
                        label = stringResource(R.string.field_color_label),
                        selected = customMsg1Color,
                        onSelected = { customMsg1Color = it }
                    )
                    TestActionButton(
                        label = stringResource(R.string.custom_message_send_label),
                        onAction = {
                            val ext = KSafeExtension.getInstance()
                                ?: return@TestActionButton "Extension not connected — wait a moment and try again."
                            ext.sendCustomMessage(1)
                        }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                // ── Slot 2 ──────────────────────────────────────────────────
                ActionSettingRow(label = stringResource(R.string.custom_message_2_label)) {
                    Switch(checked = customMessage2Enabled, onCheckedChange = { customMessage2Enabled = it })
                }
                if (customMessage2Enabled) {
                    OutlinedTextField(
                        value = customMessage2Title,
                        onValueChange = { if (it.length <= 7) customMessage2Title = it },
                        label = { Text(stringResource(R.string.custom_message_title_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = { Text(stringResource(R.string.custom_message_title_desc)) }
                    )
                    OutlinedTextField(
                        value = customMessage2,
                        onValueChange = { customMessage2 = it },
                        label = { Text(stringResource(R.string.custom_message_2_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                    FieldColorPicker(
                        label = stringResource(R.string.field_color_label),
                        selected = customMsg2Color,
                        onSelected = { customMsg2Color = it }
                    )
                    TestActionButton(
                        label = stringResource(R.string.custom_message_2_send_label),
                        onAction = {
                            val ext = KSafeExtension.getInstance()
                                ?: return@TestActionButton "Extension not connected — wait a moment and try again."
                            ext.sendCustomMessage(2)
                        }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                // ── Slot 3 ──────────────────────────────────────────────────
                ActionSettingRow(label = stringResource(R.string.custom_message_3_label)) {
                    Switch(checked = customMessage3Enabled, onCheckedChange = { customMessage3Enabled = it })
                }
                if (customMessage3Enabled) {
                    OutlinedTextField(
                        value = customMessage3Title,
                        onValueChange = { if (it.length <= 7) customMessage3Title = it },
                        label = { Text(stringResource(R.string.custom_message_title_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = { Text(stringResource(R.string.custom_message_title_desc)) }
                    )
                    OutlinedTextField(
                        value = customMessage3,
                        onValueChange = { customMessage3 = it },
                        label = { Text(stringResource(R.string.custom_message_3_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                    FieldColorPicker(
                        label = stringResource(R.string.field_color_label),
                        selected = customMsg3Color,
                        onSelected = { customMsg3Color = it }
                    )
                    TestActionButton(
                        label = stringResource(R.string.custom_message_3_send_label),
                        onAction = {
                            val ext = KSafeExtension.getInstance()
                                ?: return@TestActionButton "Extension not connected — wait a moment and try again."
                            ext.sendCustomMessage(3)
                        }
                    )
                }
            }
        }

        // ── Webhook Actions ───────────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.webhook_section),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Text(
                    text = stringResource(R.string.webhook_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val webhookEnableLabels = listOf(
                    stringResource(R.string.webhook_1_label),
                    stringResource(R.string.webhook_2_label),
                    stringResource(R.string.webhook_3_label),
                    stringResource(R.string.webhook_4_label),
                )
                val webhookTestLabel = stringResource(R.string.webhook_test)

                // Always mutate from the CURRENT list element, never a composition-time capture:
                // reading `webhookSlots[idx]` fresh inside the lambda prevents a stale-`uiSlot`
                // closure from clobbering a sibling field's just-applied edit on rapid input.
                fun mutateSlot(idx: Int, transform: (WebhookUiSlot) -> WebhookUiSlot) {
                    webhookSlots[idx] = transform(webhookSlots[idx])
                }

                for (slotIndex in 0 until WEBHOOK_SLOT_COUNT) {
                    if (slotIndex > 0) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                    }
                    val slotNumber = slotIndex + 1
                    val uiSlot = webhookSlots[slotIndex]
                    WebhookSlotFields(
                        enabled = uiSlot.slot.enabled,
                        onEnabledChange = { mutateSlot(slotIndex) { s -> s.copy(slot = s.slot.copy(enabled = it)) } },
                        enableLabel = webhookEnableLabels[slotIndex],
                        label = uiSlot.slot.label,
                        onLabelChange = { mutateSlot(slotIndex) { s -> s.copy(slot = s.slot.copy(label = it)) } },
                        url = uiSlot.slot.url,
                        onUrlChange = { mutateSlot(slotIndex) { s -> s.copy(slot = s.slot.copy(url = it)) } },
                        method = uiSlot.slot.method,
                        onMethodChange = { mutateSlot(slotIndex) { s -> s.copy(slot = s.slot.copy(method = it)) } },
                        headers = uiSlot.slot.headers,
                        onHeadersChange = { mutateSlot(slotIndex) { s -> s.copy(slot = s.slot.copy(headers = it)) } },
                        body = uiSlot.slot.body,
                        onBodyChange = { mutateSlot(slotIndex) { s -> s.copy(slot = s.slot.copy(body = it)) } },
                        geoEnabled = uiSlot.slot.geoEnabled,
                        onGeoEnabledChange = { mutateSlot(slotIndex) { s -> s.copy(slot = s.slot.copy(geoEnabled = it)) } },
                        geoLat = uiSlot.geoLatText,
                        onGeoLatChange = { mutateSlot(slotIndex) { s -> s.copy(geoLatText = it) } },
                        geoLon = uiSlot.geoLonText,
                        onGeoLonChange = { mutateSlot(slotIndex) { s -> s.copy(geoLonText = it) } },
                        geoRadius = uiSlot.geoRadiusText,
                        onGeoRadiusChange = { mutateSlot(slotIndex) { s -> s.copy(geoRadiusText = it) } },
                        alertEnabled = uiSlot.slot.alertEnabled,
                        onAlertEnabledChange = { mutateSlot(slotIndex) { s -> s.copy(slot = s.slot.copy(alertEnabled = it)) } },
                        alertText = uiSlot.slot.alertText,
                        onAlertTextChange = { mutateSlot(slotIndex) { s -> s.copy(slot = s.slot.copy(alertText = it)) } },
                        fieldColor = uiSlot.slot.color,
                        onFieldColorChange = { mutateSlot(slotIndex) { s -> s.copy(slot = s.slot.copy(color = it)) } },
                        onTest = {
                            val ext = KSafeExtension.getInstance()
                                ?: return@WebhookSlotFields "Extension not connected — wait a moment."
                            ext.testWebhook(slotNumber)
                        },
                        testButtonLabel = "$webhookTestLabel Webhook $slotNumber"
                    )
                }
            }
        }

    }
}

@Composable
private fun WebhookSlotFields(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    enableLabel: String,
    label: String,
    onLabelChange: (String) -> Unit,
    url: String,
    onUrlChange: (String) -> Unit,
    method: String,
    onMethodChange: (String) -> Unit,
    headers: String,
    onHeadersChange: (String) -> Unit,
    body: String,
    onBodyChange: (String) -> Unit,
    geoEnabled: Boolean,
    onGeoEnabledChange: (Boolean) -> Unit,
    geoLat: String,
    onGeoLatChange: (String) -> Unit,
    geoLon: String,
    onGeoLonChange: (String) -> Unit,
    geoRadius: String,
    onGeoRadiusChange: (String) -> Unit,
    alertEnabled: Boolean,
    onAlertEnabledChange: (Boolean) -> Unit,
    alertText: String,
    onAlertTextChange: (String) -> Unit,
    fieldColor: Int,
    onFieldColorChange: (Int) -> Unit,
    onTest: suspend () -> String,
    testButtonLabel: String,
) {
    val coroutineScope = rememberCoroutineScope()

    ActionSettingRow(label = enableLabel) {
        Switch(checked = enabled, onCheckedChange = onEnabledChange)
    }
    if (enabled) {
        OutlinedTextField(
            value = label,
            onValueChange = onLabelChange,
            label = { Text(stringResource(R.string.webhook_label_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            label = { Text(stringResource(R.string.webhook_url_hint)) },
            placeholder = { Text(stringResource(R.string.webhook_url_example), style = MaterialTheme.typography.bodySmall) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = method == "GET", onClick = { onMethodChange("GET") }, label = { Text("GET") })
            FilterChip(selected = method == "POST", onClick = { onMethodChange("POST") }, label = { Text("POST") })
        }
        OutlinedTextField(
            value = headers,
            onValueChange = onHeadersChange,
            label = { Text(stringResource(R.string.webhook_headers_hint)) },
            placeholder = { Text(stringResource(R.string.webhook_headers_example), style = MaterialTheme.typography.bodySmall) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        if (method == "POST") {
            OutlinedTextField(
                value = body,
                onValueChange = onBodyChange,
                label = { Text(stringResource(R.string.webhook_body_hint)) },
                placeholder = { Text(stringResource(R.string.webhook_body_example), style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
        }

        // ── Geo-fence ─────────────────────────────────────────────────────
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        ActionSettingRow(label = stringResource(R.string.webhook_geo_label)) {
            Switch(checked = geoEnabled, onCheckedChange = onGeoEnabledChange)
        }
        if (geoEnabled) {
            Text(
                text = stringResource(R.string.webhook_geo_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // "Use current location" button
            Button(
                onClick = {
                    coroutineScope.launch {
                        val ext = KSafeExtension.getInstance()
                        if (ext != null) {
                            val (lat, lon) = ext.getCurrentLocation()
                            if (lat != 0.0 || lon != 0.0) {
                                onGeoLatChange(lat.toBigDecimal().toPlainString())
                                onGeoLonChange(lon.toBigDecimal().toPlainString())
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Text(stringResource(R.string.webhook_geo_use_current))
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = geoLat,
                    onValueChange = onGeoLatChange,
                    label = { Text(stringResource(R.string.webhook_geo_lat)) },
                    placeholder = { Text(stringResource(R.string.webhook_geo_lat_placeholder), style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    // Non-blank but unparseable (even after comma normalisation) would
                    // persist as 0.0 — surface it instead of failing silently.
                    isError = geoLat.isNotBlank() && geoLat.toGeoDoubleOrNull() == null
                )
                OutlinedTextField(
                    value = geoLon,
                    onValueChange = onGeoLonChange,
                    label = { Text(stringResource(R.string.webhook_geo_lon)) },
                    placeholder = { Text(stringResource(R.string.webhook_geo_lon_placeholder), style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = geoLon.isNotBlank() && geoLon.toGeoDoubleOrNull() == null
                )
            }
            OutlinedTextField(
                value = geoRadius,
                onValueChange = { if (it.all { c -> c.isDigit() }) onGeoRadiusChange(it) },
                label = { Text(stringResource(R.string.webhook_geo_radius)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text(stringResource(R.string.webhook_geo_radius_hint)) }
            )
            // Show currently stored coords if set
            if (geoLat.isNotBlank() && geoLon.isNotBlank()) {
                Text(
                    text = "📍 ${geoLat.take(10)}, ${geoLon.take(11)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        // ─────────────────────────────────────────────────────────────────

        // ── Ride alert ────────────────────────────────────────────────────
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        ActionSettingRow(label = stringResource(R.string.webhook_alert_label)) {
            Switch(checked = alertEnabled, onCheckedChange = onAlertEnabledChange)
        }
        if (alertEnabled) {
            OutlinedTextField(
                value = alertText,
                onValueChange = onAlertTextChange,
                label = { Text(stringResource(R.string.webhook_alert_text_hint)) },
                placeholder = { Text(stringResource(R.string.webhook_alert_text_example), style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text(stringResource(R.string.webhook_alert_text_desc)) }
            )
        }
        // ─────────────────────────────────────────────────────────────────

        FieldColorPicker(
            label = stringResource(R.string.field_color_label),
            selected = fieldColor,
            onSelected = onFieldColorChange
        )

        TestActionButton(
            label = testButtonLabel,
            onAction = onTest,
        )
    }
}

@Composable
private fun ActionSettingRow(label: String, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        content()
    }
}
