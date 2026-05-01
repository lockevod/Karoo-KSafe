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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.enderthor.kSafe.R
import com.enderthor.kSafe.activity.MainViewModel
import com.enderthor.kSafe.extension.KSafeExtension
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    // ── Webhooks ──────────────────────────────────────────────────────────────
    var webhook1Enabled  by remember(config.webhook1Enabled)  { mutableStateOf(config.webhook1Enabled) }
    var webhook1Label    by remember(config.webhook1Label)    { mutableStateOf(config.webhook1Label) }
    var webhook1Url      by remember(config.webhook1Url)      { mutableStateOf(config.webhook1Url) }
    var webhook1Method   by remember(config.webhook1Method)   { mutableStateOf(config.webhook1Method) }
    var webhook1Headers  by remember(config.webhook1Headers)  { mutableStateOf(config.webhook1Headers) }
    var webhook1Body     by remember(config.webhook1Body)     { mutableStateOf(config.webhook1Body) }
    var webhook1GeoEnabled  by remember(config.webhook1GeoEnabled)  { mutableStateOf(config.webhook1GeoEnabled) }
    var webhook1GeoLat      by remember(config.webhook1GeoLat)      { mutableStateOf(if (config.webhook1GeoLat == 0.0) "" else config.webhook1GeoLat.toString()) }
    var webhook1GeoLon      by remember(config.webhook1GeoLon)      { mutableStateOf(if (config.webhook1GeoLon == 0.0) "" else config.webhook1GeoLon.toString()) }
    var webhook1GeoRadius   by remember(config.webhook1GeoRadiusM)  { mutableStateOf(config.webhook1GeoRadiusM.toString()) }
    var webhook2Enabled  by remember(config.webhook2Enabled)  { mutableStateOf(config.webhook2Enabled) }
    var webhook2Label    by remember(config.webhook2Label)    { mutableStateOf(config.webhook2Label) }
    var webhook2Url      by remember(config.webhook2Url)      { mutableStateOf(config.webhook2Url) }
    var webhook2Method   by remember(config.webhook2Method)   { mutableStateOf(config.webhook2Method) }
    var webhook2Headers  by remember(config.webhook2Headers)  { mutableStateOf(config.webhook2Headers) }
    var webhook2Body     by remember(config.webhook2Body)     { mutableStateOf(config.webhook2Body) }
    var webhook2GeoEnabled  by remember(config.webhook2GeoEnabled)  { mutableStateOf(config.webhook2GeoEnabled) }
    var webhook2GeoLat      by remember(config.webhook2GeoLat)      { mutableStateOf(if (config.webhook2GeoLat == 0.0) "" else config.webhook2GeoLat.toString()) }
    var webhook2GeoLon      by remember(config.webhook2GeoLon)      { mutableStateOf(if (config.webhook2GeoLon == 0.0) "" else config.webhook2GeoLon.toString()) }
    var webhook2GeoRadius   by remember(config.webhook2GeoRadiusM)  { mutableStateOf(config.webhook2GeoRadiusM.toString()) }

    // ── Status states ─────────────────────────────────────────────────────────
    var customMsgStatus   by remember { mutableStateOf("") }
    var customMsgIsError  by remember { mutableStateOf(false) }
    var customMsg2Status  by remember { mutableStateOf("") }
    var customMsg2IsError by remember { mutableStateOf(false) }
    var customMsg3Status  by remember { mutableStateOf("") }
    var customMsg3IsError by remember { mutableStateOf(false) }
    var webhook1Status    by remember { mutableStateOf("") }
    var webhook1IsError   by remember { mutableStateOf(false) }
    var webhook2Status    by remember { mutableStateOf("") }
    var webhook2IsError   by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    // Auto-save with debounce — all fields managed by this screen
    LaunchedEffect(
        customMessageEnabled, customMessage, customMessageTitle,
        customMessage2Enabled, customMessage2, customMessage2Title,
        customMessage3Enabled, customMessage3, customMessage3Title,
        webhook1Enabled, webhook1Label, webhook1Url, webhook1Method, webhook1Headers, webhook1Body,
        webhook1GeoEnabled, webhook1GeoLat, webhook1GeoLon, webhook1GeoRadius,
        webhook2Enabled, webhook2Label, webhook2Url, webhook2Method, webhook2Headers, webhook2Body,
        webhook2GeoEnabled, webhook2GeoLat, webhook2GeoLon, webhook2GeoRadius,
    ) {
        delay(600)
        vm.saveConfig(
            config.copy(
                customMessageEnabled    = customMessageEnabled,
                customMessageTitle      = customMessageTitle.take(5).ifBlank { "MSG" },
                customMessage           = customMessage,
                customMessage2Enabled   = customMessage2Enabled,
                customMessage2Title     = customMessage2Title.take(5).ifBlank { "MSG2" },
                customMessage2          = customMessage2,
                customMessage3Enabled   = customMessage3Enabled,
                customMessage3Title     = customMessage3Title.take(5).ifBlank { "MSG3" },
                customMessage3          = customMessage3,
                webhook1Enabled  = webhook1Enabled,
                webhook1Label    = webhook1Label,
                webhook1Url      = webhook1Url,
                webhook1Method   = webhook1Method,
                webhook1Headers  = webhook1Headers,
                webhook1Body     = webhook1Body,
                webhook1GeoEnabled  = webhook1GeoEnabled,
                webhook1GeoLat      = webhook1GeoLat.toDoubleOrNull() ?: 0.0,
                webhook1GeoLon      = webhook1GeoLon.toDoubleOrNull() ?: 0.0,
                webhook1GeoRadiusM  = webhook1GeoRadius.toIntOrNull()?.coerceAtLeast(1) ?: 50,
                webhook2Enabled  = webhook2Enabled,
                webhook2Label    = webhook2Label,
                webhook2Url      = webhook2Url,
                webhook2Method   = webhook2Method,
                webhook2Headers  = webhook2Headers,
                webhook2Body     = webhook2Body,
                webhook2GeoEnabled  = webhook2GeoEnabled,
                webhook2GeoLat      = webhook2GeoLat.toDoubleOrNull() ?: 0.0,
                webhook2GeoLon      = webhook2GeoLon.toDoubleOrNull() ?: 0.0,
                webhook2GeoRadiusM  = webhook2GeoRadius.toIntOrNull()?.coerceAtLeast(1) ?: 50,
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
            text = stringResource(R.string.tab_actions),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

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
                        onValueChange = { if (it.length <= 5) customMessageTitle = it },
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
                    Button(
                        onClick = {
                            customMsgStatus = "Sending…"
                            customMsgIsError = false
                            coroutineScope.launch {
                                val ext = KSafeExtension.getInstance()
                                if (ext == null) {
                                    customMsgStatus = "Extension not connected — wait a moment and try again."
                                    customMsgIsError = true
                                } else {
                                    val msg = ext.sendCustomMessage(1)
                                    customMsgIsError = !msg.contains("✓")
                                    customMsgStatus = msg
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.custom_message_send_label)) }
                    if (customMsgStatus.isNotEmpty()) {
                        Text(
                            text = customMsgStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (customMsgIsError) Color(0xFFB71C1C) else Color(0xFF2E7D32)
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                // ── Slot 2 ──────────────────────────────────────────────────
                ActionSettingRow(label = stringResource(R.string.custom_message_2_label)) {
                    Switch(checked = customMessage2Enabled, onCheckedChange = { customMessage2Enabled = it })
                }
                if (customMessage2Enabled) {
                    OutlinedTextField(
                        value = customMessage2Title,
                        onValueChange = { if (it.length <= 5) customMessage2Title = it },
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
                    Button(
                        onClick = {
                            customMsg2Status = "Sending…"
                            customMsg2IsError = false
                            coroutineScope.launch {
                                val ext = KSafeExtension.getInstance()
                                if (ext == null) {
                                    customMsg2Status = "Extension not connected — wait a moment and try again."
                                    customMsg2IsError = true
                                } else {
                                    val msg = ext.sendCustomMessage(2)
                                    customMsg2IsError = !msg.contains("✓")
                                    customMsg2Status = msg
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.custom_message_2_send_label)) }
                    if (customMsg2Status.isNotEmpty()) {
                        Text(
                            text = customMsg2Status,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (customMsg2IsError) Color(0xFFB71C1C) else Color(0xFF2E7D32)
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                // ── Slot 3 ──────────────────────────────────────────────────
                ActionSettingRow(label = stringResource(R.string.custom_message_3_label)) {
                    Switch(checked = customMessage3Enabled, onCheckedChange = { customMessage3Enabled = it })
                }
                if (customMessage3Enabled) {
                    OutlinedTextField(
                        value = customMessage3Title,
                        onValueChange = { if (it.length <= 5) customMessage3Title = it },
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
                    Button(
                        onClick = {
                            customMsg3Status = "Sending…"
                            customMsg3IsError = false
                            coroutineScope.launch {
                                val ext = KSafeExtension.getInstance()
                                if (ext == null) {
                                    customMsg3Status = "Extension not connected — wait a moment and try again."
                                    customMsg3IsError = true
                                } else {
                                    val msg = ext.sendCustomMessage(3)
                                    customMsg3IsError = !msg.contains("✓")
                                    customMsg3Status = msg
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.custom_message_3_send_label)) }
                    if (customMsg3Status.isNotEmpty()) {
                        Text(
                            text = customMsg3Status,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (customMsg3IsError) Color(0xFFB71C1C) else Color(0xFF2E7D32)
                        )
                    }
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

                // ── Webhook 1 ─────────────────────────────────────────────
                WebhookSlotFields(
                    enabled = webhook1Enabled,
                    onEnabledChange = { webhook1Enabled = it },
                    enableLabel = stringResource(R.string.webhook_1_label),
                    label = webhook1Label,
                    onLabelChange = { webhook1Label = it },
                    url = webhook1Url,
                    onUrlChange = { webhook1Url = it },
                    method = webhook1Method,
                    onMethodChange = { webhook1Method = it },
                    headers = webhook1Headers,
                    onHeadersChange = { webhook1Headers = it },
                    body = webhook1Body,
                    onBodyChange = { webhook1Body = it },
                    geoEnabled = webhook1GeoEnabled,
                    onGeoEnabledChange = { webhook1GeoEnabled = it },
                    geoLat = webhook1GeoLat,
                    onGeoLatChange = { webhook1GeoLat = it },
                    geoLon = webhook1GeoLon,
                    onGeoLonChange = { webhook1GeoLon = it },
                    geoRadius = webhook1GeoRadius,
                    onGeoRadiusChange = { webhook1GeoRadius = it },
                    testStatus = webhook1Status,
                    testIsError = webhook1IsError,
                    onTest = {
                        webhook1Status = "Testing…"
                        webhook1IsError = false
                        coroutineScope.launch {
                            val ext = KSafeExtension.getInstance()
                            if (ext == null) {
                                webhook1Status = "Extension not connected — wait a moment."
                                webhook1IsError = true
                            } else {
                                val msg = ext.testWebhook(1)
                                webhook1IsError = !msg.contains("✓")
                                webhook1Status = msg
                            }
                        }
                    },
                    testButtonLabel = "${stringResource(R.string.webhook_test)} Webhook 1"
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                // ── Webhook 2 ─────────────────────────────────────────────
                WebhookSlotFields(
                    enabled = webhook2Enabled,
                    onEnabledChange = { webhook2Enabled = it },
                    enableLabel = stringResource(R.string.webhook_2_label),
                    label = webhook2Label,
                    onLabelChange = { webhook2Label = it },
                    url = webhook2Url,
                    onUrlChange = { webhook2Url = it },
                    method = webhook2Method,
                    onMethodChange = { webhook2Method = it },
                    headers = webhook2Headers,
                    onHeadersChange = { webhook2Headers = it },
                    body = webhook2Body,
                    onBodyChange = { webhook2Body = it },
                    geoEnabled = webhook2GeoEnabled,
                    onGeoEnabledChange = { webhook2GeoEnabled = it },
                    geoLat = webhook2GeoLat,
                    onGeoLatChange = { webhook2GeoLat = it },
                    geoLon = webhook2GeoLon,
                    onGeoLonChange = { webhook2GeoLon = it },
                    geoRadius = webhook2GeoRadius,
                    onGeoRadiusChange = { webhook2GeoRadius = it },
                    testStatus = webhook2Status,
                    testIsError = webhook2IsError,
                    onTest = {
                        webhook2Status = "Testing…"
                        webhook2IsError = false
                        coroutineScope.launch {
                            val ext = KSafeExtension.getInstance()
                            if (ext == null) {
                                webhook2Status = "Extension not connected — wait a moment."
                                webhook2IsError = true
                            } else {
                                val msg = ext.testWebhook(2)
                                webhook2IsError = !msg.contains("✓")
                                webhook2Status = msg
                            }
                        }
                    },
                    testButtonLabel = "${stringResource(R.string.webhook_test)} Webhook 2"
                )
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
    testStatus: String,
    testIsError: Boolean,
    onTest: () -> Unit,
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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(
                    value = geoLon,
                    onValueChange = onGeoLonChange,
                    label = { Text(stringResource(R.string.webhook_geo_lon)) },
                    placeholder = { Text(stringResource(R.string.webhook_geo_lon_placeholder), style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
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

        Button(onClick = onTest, modifier = Modifier.fillMaxWidth()) { Text(testButtonLabel) }
        if (testStatus.isNotEmpty()) {
            Text(
                text = testStatus,
                style = MaterialTheme.typography.bodySmall,
                color = if (testIsError) Color(0xFFB71C1C) else Color(0xFF2E7D32)
            )
        }
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
