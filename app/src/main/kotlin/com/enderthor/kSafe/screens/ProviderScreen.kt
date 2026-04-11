package com.enderthor.kSafe.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.enderthor.kSafe.R
import com.enderthor.kSafe.activity.MainViewModel
import com.enderthor.kSafe.data.ProviderType
import com.enderthor.kSafe.extension.KSafeExtension
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ProviderScreen(vm: MainViewModel) {
    val config by vm.config.collectAsState()
    val senderConfigs by vm.senderConfigs.collectAsState()

    val activeProvider = config.activeProvider
    val activeSender = senderConfigs.find { it.provider == activeProvider }
    val coroutineScope = rememberCoroutineScope()

    var apiKey by remember(activeProvider, activeSender) {
        mutableStateOf(activeSender?.apiKey ?: "")
    }
    var userKey by remember(activeProvider, activeSender) {
        mutableStateOf(activeSender?.userKey ?: "")
    }
    var phoneNumber by remember(activeProvider, activeSender) {
        mutableStateOf(activeSender?.phoneNumber ?: "")
    }
    var testStatus by remember { mutableStateOf("") }
    var testIsError by remember { mutableStateOf(false) }

    // Auto-save with debounce whenever credential fields change
    LaunchedEffect(apiKey, userKey, phoneNumber) {
        delay(700)
        vm.updateSenderConfig(activeProvider, apiKey, userKey, phoneNumber)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.provider_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        // Provider selector chips — CallMeBot + Pushover in first row, SimplePush full-width below
        val onProviderClick = { provider: ProviderType ->
            vm.setActiveProvider(provider)
            val s = senderConfigs.find { it.provider == provider }
            apiKey = s?.apiKey ?: ""
            userKey = s?.userKey ?: ""
            phoneNumber = s?.phoneNumber ?: ""
            testStatus = ""
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(ProviderType.CALLMEBOT, ProviderType.PUSHOVER).forEach { provider ->
                FilterChip(
                    selected = activeProvider == provider,
                    onClick = { onProviderClick(provider) },
                    label = {
                        Text(
                            text = if (provider == ProviderType.CALLMEBOT) "CallMeBot" else "Pushover",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
            }
        }
        FilterChip(
            selected = activeProvider == ProviderType.SIMPLEPUSH,
            onClick = { onProviderClick(ProviderType.SIMPLEPUSH) },
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text("SimplePush", style = MaterialTheme.typography.labelSmall)
            }
        )

        Spacer(Modifier.height(4.dp))

        // Provider description
        Text(
            text = when (activeProvider) {
                ProviderType.CALLMEBOT  -> stringResource(R.string.callmebot_description)
                ProviderType.PUSHOVER   -> stringResource(R.string.pushover_description)
                ProviderType.SIMPLEPUSH -> stringResource(R.string.simplepush_description)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // CallMeBot: recipient phone number
        if (activeProvider == ProviderType.CALLMEBOT) {
            OutlinedTextField(
                value = phoneNumber,
                onValueChange = { phoneNumber = it },
                label = { Text(stringResource(R.string.callmebot_phone_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        // API key / app token / channel key
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = {
                Text(
                    when (activeProvider) {
                        ProviderType.PUSHOVER   -> stringResource(R.string.pushover_app_token_hint)
                        ProviderType.SIMPLEPUSH -> stringResource(R.string.simplepush_key_hint)
                        else                    -> stringResource(R.string.api_key_hint)
                    }
                )
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // Pushover user/group key
        if (activeProvider == ProviderType.PUSHOVER) {
            OutlinedTextField(
                value = userKey,
                onValueChange = { userKey = it },
                label = { Text(stringResource(R.string.pushover_user_key_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        // Test send
        Button(
            onClick = {
                testStatus = "Sending…"
                testIsError = false
                coroutineScope.launch {
                    val ext = KSafeExtension.getInstance()
                    if (ext == null) {
                        testStatus = "Extension not connected — wait a moment and try again."
                        testIsError = true
                        return@launch
                    }
                    // Flush any pending auto-save before testing
                    vm.updateSenderConfig(activeProvider, apiKey, userKey, phoneNumber)
                    val ok = ext.sendTestMessage(activeProvider)
                    if (ok) {
                        testStatus = "Test sent successfully! Check your device."
                        testIsError = false
                    } else {
                        testStatus = "Send failed — check your API key / token and try again."
                        testIsError = true
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.test_send))
        }

        if (testStatus.isNotEmpty()) {
            Text(
                text = testStatus,
                style = MaterialTheme.typography.bodySmall,
                color = if (testIsError) Color(0xFFB71C1C) else Color(0xFF2E7D32)
            )
        }
    }
}
