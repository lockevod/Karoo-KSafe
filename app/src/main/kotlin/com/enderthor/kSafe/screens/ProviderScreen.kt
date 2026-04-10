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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.enderthor.kSafe.R
import com.enderthor.kSafe.activity.MainViewModel
import com.enderthor.kSafe.data.ProviderType
import com.enderthor.kSafe.extension.KSafeExtension
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
    var testStatus by remember { mutableStateOf("") }

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

        // Provider selector chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ProviderType.entries.forEach { provider ->
                FilterChip(
                    selected = activeProvider == provider,
                    onClick = {
                        vm.setActiveProvider(provider)
                        val s = senderConfigs.find { it.provider == provider }
                        apiKey = s?.apiKey ?: ""
                    },
                    label = {
                        Text(
                            text = when (provider) {
                                ProviderType.CALLMEBOT -> "CallMeBot"
                                ProviderType.WHAPI -> "Whapi"
                                ProviderType.PUSHOVER -> "Pushover"
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // Provider description
        Text(
            text = when (activeProvider) {
                ProviderType.CALLMEBOT -> "WhatsApp via CallMeBot. Free API key at callmebot.com."
                ProviderType.WHAPI -> "WhatsApp via Whapi Cloud. Requires a paid plan at whapi.cloud."
                ProviderType.PUSHOVER -> "Push notification via Pushover. Enter your app token and user key."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = {
                Text(
                    if (activeProvider == ProviderType.PUSHOVER)
                        stringResource(R.string.pushover_app_token_hint)
                    else
                        stringResource(R.string.api_key_hint)
                )
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        if (activeProvider == ProviderType.PUSHOVER) {
            OutlinedTextField(
                value = userKey,
                onValueChange = { userKey = it },
                label = { Text(stringResource(R.string.pushover_user_key_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        Button(
            onClick = {
                vm.updateSenderApiKey(activeProvider, apiKey, userKey)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.save))
        }

        // Test send — sends a test message to the first configured contact
        Button(
            onClick = {
                val firstContact = config.contacts.firstOrNull()
                if (firstContact == null) {
                    testStatus = "No contacts configured"
                    return@Button
                }
                testStatus = "Sending..."
                coroutineScope.launch {
                    val ext = KSafeExtension.getInstance()
                    if (ext == null) {
                        testStatus = "Extension not running (open a ride first)"
                        return@launch
                    }
                    // Save current API key before sending
                    vm.updateSenderApiKey(activeProvider, apiKey, userKey)
                    ext.sendTestMessage(firstContact, activeProvider)
                    testStatus = "Test sent to ${firstContact.name}! Check your phone."
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
                color = if (testStatus.contains("Error") || testStatus.contains("not running") || testStatus.contains("No contacts"))
                    androidx.compose.ui.graphics.Color.Red
                else
                    MaterialTheme.colorScheme.primary
            )
        }
    }
}
