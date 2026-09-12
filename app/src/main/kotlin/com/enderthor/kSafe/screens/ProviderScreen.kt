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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.enderthor.kSafe.R
import com.enderthor.kSafe.activity.MainViewModel
import com.enderthor.kSafe.data.ProviderType
import com.enderthor.kSafe.data.SenderConfig
import com.enderthor.kSafe.extension.ProviderReadiness
import com.enderthor.kSafe.extension.fieldSyncKey
import com.enderthor.kSafe.extension.providerReadiness
import com.enderthor.kSafe.data.RecipientAlertScope
import com.enderthor.kSafe.extension.KSafeExtension
import com.enderthor.kSafe.extension.util.accepts
import kotlinx.coroutines.delay
import timber.log.Timber

@Composable
fun ProviderScreen(vm: MainViewModel) {
    val config by vm.config.collectAsState()
    val senderConfigs by vm.senderConfigs.collectAsState()

    val activeProvider = config.activeProvider
    val activeSender = senderConfigs.find { it.provider == activeProvider }

    // `fieldsProvider` tracks which provider the current field values belong to.
    // It is updated synchronously in onProviderClick so the auto-save LaunchedEffect
    // always writes to the correct provider, even if DataStore hasn't propagated yet.
    var fieldsProvider by remember { mutableStateOf(activeProvider) }
    var apiKey       by remember { mutableStateOf(activeSender?.apiKey       ?: "") }
    var userKey      by remember { mutableStateOf(activeSender?.userKey      ?: "") }
    var userKey2     by remember { mutableStateOf(activeSender?.userKey2     ?: "") }
    var userKey3     by remember { mutableStateOf(activeSender?.userKey3     ?: "") }
    var phoneNumber  by remember { mutableStateOf(activeSender?.phoneNumber  ?: "") }
    var apiKey2      by remember { mutableStateOf(activeSender?.apiKey2      ?: "") }
    var phoneNumber2 by remember { mutableStateOf(activeSender?.phoneNumber2 ?: "") }
    var apiKey3      by remember { mutableStateOf(activeSender?.apiKey3      ?: "") }
    var phoneNumber3 by remember { mutableStateOf(activeSender?.phoneNumber3 ?: "") }
    var recipient1Alerts by remember { mutableStateOf(activeSender?.recipient1Alerts ?: RecipientAlertScope.ALL) }
    var recipient2Alerts by remember { mutableStateOf(activeSender?.recipient2Alerts ?: RecipientAlertScope.ALL) }
    var recipient3Alerts by remember { mutableStateOf(activeSender?.recipient3Alerts ?: RecipientAlertScope.ALL) }
    var scopeErrorSlot by remember { mutableStateOf<Int?>(null) }

    // Sync fieldsProvider + fields when DataStore loads the real active provider on first
    // composition (e.g. stored provider is PUSHOVER but default is CALLMEBOT) or after
    // an import from another screen.  onProviderClick keeps fieldsProvider in sync
    // immediately, so when activeProvider catches up from DataStore this guard is a no-op.
    LaunchedEffect(activeProvider) {
        if (activeProvider != fieldsProvider) {
            val s = senderConfigs.find { it.provider == activeProvider }
            fieldsProvider = activeProvider
            apiKey       = s?.apiKey       ?: ""
            userKey      = s?.userKey      ?: ""
            userKey2     = s?.userKey2     ?: ""
            userKey3     = s?.userKey3     ?: ""
            phoneNumber  = s?.phoneNumber  ?: ""
            apiKey2      = s?.apiKey2      ?: ""
            phoneNumber2 = s?.phoneNumber2 ?: ""
            apiKey3      = s?.apiKey3      ?: ""
            phoneNumber3 = s?.phoneNumber3 ?: ""
            recipient1Alerts = s?.recipient1Alerts ?: RecipientAlertScope.ALL
            recipient2Alerts = s?.recipient2Alerts ?: RecipientAlertScope.ALL
            recipient3Alerts = s?.recipient3Alerts ?: RecipientAlertScope.ALL
        }
    }

    // Refresh fields when the active sender's data changes in DataStore (e.g. after an
    // import or a save from another screen). The `fieldsProvider` guard prevents
    // overwriting fields mid-switch when activeProvider and fieldsProvider diverge.
    // Keyed on the MIRRORED fields only ([fieldSyncKey]), not on the whole SenderConfig: a write
    // that changes nothing the form displays (a successful-send stamp, or the acknowledgement
    // Switch below) must not re-seed the fields while the rider is still typing into them.
    LaunchedEffect(activeSender?.fieldSyncKey()) {
        activeSender?.let { sender ->
            if (sender.provider == fieldsProvider) {
                if (sender.apiKey       != apiKey)       apiKey       = sender.apiKey
                if (sender.userKey      != userKey)      userKey      = sender.userKey
                if (sender.userKey2     != userKey2)     userKey2     = sender.userKey2
                if (sender.userKey3     != userKey3)     userKey3     = sender.userKey3
                if (sender.phoneNumber  != phoneNumber)  phoneNumber  = sender.phoneNumber
                if (sender.apiKey2      != apiKey2)      apiKey2      = sender.apiKey2
                if (sender.phoneNumber2 != phoneNumber2) phoneNumber2 = sender.phoneNumber2
                if (sender.apiKey3      != apiKey3)      apiKey3      = sender.apiKey3
                if (sender.phoneNumber3 != phoneNumber3) phoneNumber3 = sender.phoneNumber3
                if (sender.recipient1Alerts != recipient1Alerts) recipient1Alerts = sender.recipient1Alerts
                if (sender.recipient2Alerts != recipient2Alerts) recipient2Alerts = sender.recipient2Alerts
                if (sender.recipient3Alerts != recipient3Alerts) recipient3Alerts = sender.recipient3Alerts
            }
        }
    }

    // Auto-save with debounce — uses fieldsProvider (always in sync with the fields)
    LaunchedEffect(
        apiKey, userKey, userKey2, userKey3, phoneNumber, apiKey2, phoneNumber2, apiKey3, phoneNumber3,
        recipient1Alerts, recipient2Alerts, recipient3Alerts,
    ) {
        scopeErrorSlot = null
        delay(700)
        // Cold-load guard: senderConfigs' stateIn initial is emptyList() and the fields above
        // were seeded from a null activeSender → all blank. This effect fires on FIRST
        // composition, so if DataStore's first sender emission takes longer than the 700 ms
        // debounce (cold first visit to the tab), the save below would faithfully persist
        // all-blank fields over the active provider's stored entry — silently wiping the
        // emergency-contact credentials. The persisted list is never empty (defaults carry
        // all four providers), so an empty live value means "not loaded yet": skip. Once the
        // real emission lands, the field sync re-keys this effect and saves normally.
        if (vm.senderConfigs.value.isEmpty()) {
            Timber.d("Sender auto-save skipped — DataStore not loaded yet")
            return@LaunchedEffect
        }
        vm.updateSenderConfig(
            fieldsProvider, apiKey, userKey, userKey2, userKey3,
            phoneNumber, apiKey2, phoneNumber2, apiKey3, phoneNumber3,
            recipient1Alerts, recipient2Alerts, recipient3Alerts,
        )
    }

    // N4 — auto-dismiss the "must keep an emergency contact" banner. A rejected tap leaves the
    // chip on its previous (valid) value, and a SegmentedButton does NOT re-fire onScopeChange
    // when the rider taps the already-selected segment — so without a timeout the only way to
    // clear the banner is to edit another field. Clearing it on a valid scope change still
    // happens immediately (setScope / the auto-save effect above); this only bounds the error.
    LaunchedEffect(scopeErrorSlot) {
        if (scopeErrorSlot != null) {
            delay(4000)
            scopeErrorSlot = null
        }
    }

    // Apply a new alert scope to one recipient slot, enforcing the invariant that — for the
    // active provider's configured recipients — at least one still accepts emergency alerts.
    // Rejecting (rather than silently allowing) avoids a config where a crash reaches nobody.
    fun setScope(slot: Int, newScope: RecipientAlertScope) {
        val keys = listOf(userKey, userKey2, userKey3)
        val phones = listOf(phoneNumber, phoneNumber2, phoneNumber3)
        val apiKeys = listOf(apiKey, apiKey2, apiKey3)
        val configured = (0..2).filter {
            when (fieldsProvider) {
                ProviderType.CALLMEBOT -> phones[it].isNotBlank() && apiKeys[it].isNotBlank()
                ProviderType.NTFY      -> it == 0 && apiKey.isNotBlank()
                else                   -> keys[it].isNotBlank()
            }
        }
        val candidate = { i: Int ->
            if (i == slot) newScope
            else listOf(recipient1Alerts, recipient2Alerts, recipient3Alerts)[i]
        }
        if (configured.none { candidate(it).accepts(isEmergency = true) }) {
            scopeErrorSlot = slot   // reject — would leave no emergency contact
            return
        }
        scopeErrorSlot = null
        when (slot) {
            0 -> recipient1Alerts = newScope
            1 -> recipient2Alerts = newScope
            else -> recipient3Alerts = newScope
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
            text = stringResource(R.string.provider_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        // Provider selector chips — CallMeBot + Pushover in first row, ntfy + Telegram below
        val onProviderClick = { provider: ProviderType ->
            // Save the CURRENT provider's fields immediately before switching,
            // so no data is lost if the debounce timer hasn't fired yet.
            // Same cold-load guard as the debounced auto-save: before the first DataStore
            // emission the fields are blank seeds, and persisting them here would wipe the
            // stored credentials of the provider being switched away from.
            if (vm.senderConfigs.value.isNotEmpty()) {
                vm.updateSenderConfig(
                    fieldsProvider, apiKey, userKey, userKey2, userKey3,
                    phoneNumber, apiKey2, phoneNumber2, apiKey3, phoneNumber3,
                    recipient1Alerts, recipient2Alerts, recipient3Alerts,
                )
            }
            // Load the new provider's saved values and update fieldsProvider atomically.
            val s = senderConfigs.find { it.provider == provider }
            fieldsProvider = provider
            apiKey       = s?.apiKey       ?: ""
            userKey      = s?.userKey      ?: ""
            userKey2     = s?.userKey2     ?: ""
            userKey3     = s?.userKey3     ?: ""
            phoneNumber  = s?.phoneNumber  ?: ""
            apiKey2      = s?.apiKey2      ?: ""
            phoneNumber2 = s?.phoneNumber2 ?: ""
            apiKey3      = s?.apiKey3      ?: ""
            phoneNumber3 = s?.phoneNumber3 ?: ""
            recipient1Alerts = s?.recipient1Alerts ?: RecipientAlertScope.ALL
            recipient2Alerts = s?.recipient2Alerts ?: RecipientAlertScope.ALL
            recipient3Alerts = s?.recipient3Alerts ?: RecipientAlertScope.ALL
            scopeErrorSlot = null
            // Switch active provider last so DataStore propagates after fields are ready.
            vm.setActiveProvider(provider)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(ProviderType.CALLMEBOT, ProviderType.PUSHOVER).forEach { provider ->
                FilterChip(
                    selected = fieldsProvider == provider,
                    onClick = { onProviderClick(provider) },
                    modifier = Modifier.weight(1f),
                    label = {
                        Text(
                            text = if (provider == ProviderType.CALLMEBOT) "CallMeBot (WhatsApp)" else "Pushover",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(ProviderType.NTFY, ProviderType.TELEGRAM).forEach { provider ->
                FilterChip(
                    selected = fieldsProvider == provider,
                    onClick = { onProviderClick(provider) },
                    modifier = Modifier.weight(1f),
                    label = {
                        Text(
                            text = if (provider == ProviderType.NTFY) "Ntfy" else "Telegram",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.provider_active_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )

        // Provider description
        Text(
            text = when (fieldsProvider) {
                ProviderType.CALLMEBOT  -> stringResource(R.string.callmebot_description)
                ProviderType.PUSHOVER   -> stringResource(R.string.pushover_description)
                ProviderType.NTFY       -> stringResource(R.string.ntfy_description)
                ProviderType.TELEGRAM   -> stringResource(R.string.telegram_description)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Provider-incomplete warning. Computed from the LIVE field values (not the saved
        // config) so it updates as the rider types. Same predicate as the Sender fast-fail
        // ([providerReadiness]) → the banner and "alert won't send" behaviour can't disagree.
        val readiness = providerReadiness(
            fieldsProvider,
            SenderConfig(
                provider = fieldsProvider,
                apiKey = apiKey, userKey = userKey, userKey2 = userKey2, userKey3 = userKey3,
                phoneNumber = phoneNumber, apiKey2 = apiKey2, phoneNumber2 = phoneNumber2,
                apiKey3 = apiKey3, phoneNumber3 = phoneNumber3,
            ),
        )
        if (readiness is ProviderReadiness.Incomplete) {
            val reason = when (readiness.missing) {
                ProviderReadiness.Missing.CALLMEBOT_PHONE_OR_KEY -> stringResource(R.string.provider_missing_callmebot)
                ProviderReadiness.Missing.PUSHOVER_APP_TOKEN     -> stringResource(R.string.provider_missing_pushover_token)
                ProviderReadiness.Missing.PUSHOVER_USER_KEY      -> stringResource(R.string.provider_missing_pushover_user)
                ProviderReadiness.Missing.NTFY_TOPIC             -> stringResource(R.string.provider_missing_ntfy_topic)
                ProviderReadiness.Missing.TELEGRAM_BOT_TOKEN     -> stringResource(R.string.provider_missing_telegram_token)
                ProviderReadiness.Missing.TELEGRAM_CHAT_ID       -> stringResource(R.string.provider_missing_telegram_chat)
            }
            Text(
                text = "⚠ $reason ${stringResource(R.string.provider_warn_banner_suffix)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            // "I won't use alerts — stop warning me": silences ONLY the per-ride warning, and only
            // for this provider. Reads the SAVED flag (not the live fields) and writes straight
            // through, so it does not depend on the credential form being saved. Offered only
            // while the provider is incomplete, so a configured rider never sees a way to switch
            // the warning off by accident.
            val ackedNow = senderConfigs.find { it.provider == fieldsProvider }?.providerWarningAcknowledged == true
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.provider_warn_ack_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = ackedNow,
                    onCheckedChange = { vm.acknowledgeProviderWarning(fieldsProvider, it) },
                )
            }
        } else {
            // Soft nudge: credentials look complete, but no send has ever succeeded for them yet
            // — encourage (don't force) verifying. The timestamp comes from the SAVED config (set
            // on any successful send, reset to 0 when credentials change).
            val everSent = (senderConfigs.find { it.provider == fieldsProvider }?.lastSuccessfulSendMs ?: 0L) > 0L
            if (!everSent) {
                Text(
                    text = "⚠ ${stringResource(R.string.provider_warn_unverified)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }
        }

        // CallMeBot: recipient 1 phone number (the API key field below is recipient 1's key)
        if (fieldsProvider == ProviderType.CALLMEBOT) {
            OutlinedTextField(
                value = phoneNumber,
                onValueChange = { phoneNumber = it },
                label = { Text(stringResource(R.string.callmebot_phone_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        // API key / app token / channel key / bot token
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = {
                Text(
                    when (fieldsProvider) {
                        ProviderType.CALLMEBOT  -> stringResource(R.string.callmebot_apikey_hint)
                        ProviderType.PUSHOVER   -> stringResource(R.string.pushover_app_token_hint)
                        ProviderType.NTFY       -> stringResource(R.string.ntfy_topic_hint)
                        ProviderType.TELEGRAM   -> stringResource(R.string.telegram_bot_token_hint)
                    }
                )
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // NTFY has a single recipient (the topic in `apiKey`); CallMeBot recipient 1 is
        // phoneNumber + this apiKey, so its slot-0 selector also belongs here once the phone is set.
        if (fieldsProvider == ProviderType.NTFY && apiKey.isNotBlank()) {
            RecipientAlertScopeSelector(
                scope = recipient1Alerts,
                onScopeChange = { setScope(0, it) },
                showError = scopeErrorSlot == 0
            )
        }
        if (fieldsProvider == ProviderType.CALLMEBOT && phoneNumber.isNotBlank() && apiKey.isNotBlank()) {
            RecipientAlertScopeSelector(
                scope = recipient1Alerts,
                onScopeChange = { setScope(0, it) },
                showError = scopeErrorSlot == 0
            )
        }

        // CallMeBot: optional second recipient (each recipient needs its own phone + API key
        // because CallMeBot can't fan-out a single request to multiple WhatsApp numbers).
        if (fieldsProvider == ProviderType.CALLMEBOT) {
            OutlinedTextField(
                value = phoneNumber2,
                onValueChange = { phoneNumber2 = it },
                label = { Text(stringResource(R.string.callmebot_phone2_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = apiKey2,
                onValueChange = { apiKey2 = it },
                label = { Text(stringResource(R.string.callmebot_apikey2_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            if (phoneNumber2.isNotBlank() && apiKey2.isNotBlank()) {
                RecipientAlertScopeSelector(
                    scope = recipient2Alerts,
                    onScopeChange = { setScope(1, it) },
                    showError = scopeErrorSlot == 1
                )
            }
            OutlinedTextField(
                value = phoneNumber3,
                onValueChange = { phoneNumber3 = it },
                label = { Text(stringResource(R.string.callmebot_phone3_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = apiKey3,
                onValueChange = { apiKey3 = it },
                label = { Text(stringResource(R.string.callmebot_apikey3_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            if (phoneNumber3.isNotBlank() && apiKey3.isNotBlank()) {
                RecipientAlertScopeSelector(
                    scope = recipient3Alerts,
                    onScopeChange = { setScope(2, it) },
                    showError = scopeErrorSlot == 2
                )
            }
        }

        // Pushover user keys (up to 3 recipients)
        if (fieldsProvider == ProviderType.PUSHOVER) {
            OutlinedTextField(
                value = userKey,
                onValueChange = { userKey = it },
                label = { Text(stringResource(R.string.pushover_user_key_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            if (userKey.isNotBlank()) {
                RecipientAlertScopeSelector(
                    scope = recipient1Alerts,
                    onScopeChange = { setScope(0, it) },
                    showError = scopeErrorSlot == 0
                )
            }
            OutlinedTextField(
                value = userKey2,
                onValueChange = { userKey2 = it },
                label = { Text(stringResource(R.string.pushover_user_key2_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            if (userKey2.isNotBlank()) {
                RecipientAlertScopeSelector(
                    scope = recipient2Alerts,
                    onScopeChange = { setScope(1, it) },
                    showError = scopeErrorSlot == 1
                )
            }
            OutlinedTextField(
                value = userKey3,
                onValueChange = { userKey3 = it },
                label = { Text(stringResource(R.string.pushover_user_key3_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            if (userKey3.isNotBlank()) {
                RecipientAlertScopeSelector(
                    scope = recipient3Alerts,
                    onScopeChange = { setScope(2, it) },
                    showError = scopeErrorSlot == 2
                )
            }
        }

        // Telegram chat IDs (up to 3 recipients)
        if (fieldsProvider == ProviderType.TELEGRAM) {
            OutlinedTextField(
                value = userKey,
                onValueChange = { userKey = it },
                label = { Text(stringResource(R.string.telegram_chat_id_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            if (userKey.isNotBlank()) {
                RecipientAlertScopeSelector(
                    scope = recipient1Alerts,
                    onScopeChange = { setScope(0, it) },
                    showError = scopeErrorSlot == 0
                )
            }
            OutlinedTextField(
                value = userKey2,
                onValueChange = { userKey2 = it },
                label = { Text(stringResource(R.string.telegram_chat_id2_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            if (userKey2.isNotBlank()) {
                RecipientAlertScopeSelector(
                    scope = recipient2Alerts,
                    onScopeChange = { setScope(1, it) },
                    showError = scopeErrorSlot == 1
                )
            }
            OutlinedTextField(
                value = userKey3,
                onValueChange = { userKey3 = it },
                label = { Text(stringResource(R.string.telegram_chat_id3_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            if (userKey3.isNotBlank()) {
                RecipientAlertScopeSelector(
                    scope = recipient3Alerts,
                    onScopeChange = { setScope(2, it) },
                    showError = scopeErrorSlot == 2
                )
            }
        }

        // Test send
        TestActionButton(
            label = stringResource(R.string.test_send),
            onAction = {
                val ext = KSafeExtension.getInstance()
                    ?: return@TestActionButton "Extension not connected — wait a moment and try again."
                // Flush any pending auto-save before testing.
                vm.updateSenderConfig(
                    fieldsProvider, apiKey, userKey, userKey2, userKey3,
                    phoneNumber, apiKey2, phoneNumber2, apiKey3, phoneNumber3,
                    recipient1Alerts, recipient2Alerts, recipient3Alerts,
                )
                ext.sendTestMessage(fieldsProvider)
            }
        )
    }
}

/**
 * Per-contact alert-scope picker: three mutually-exclusive [FilterChip]s (All / Emergencies only /
 * Info only) bound to one recipient slot. [onScopeChange] funnels through `setScope`, which enforces
 * the "at least one emergency contact" invariant, so a rejected change leaves [scope] unchanged.
 */
@Composable
private fun RecipientAlertScopeSelector(
    scope: RecipientAlertScope,
    onScopeChange: (RecipientAlertScope) -> Unit,
    modifier: Modifier = Modifier,
    showError: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.recipient_alerts_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val options = listOf(
                RecipientAlertScope.ALL to R.string.recipient_alerts_all,
                RecipientAlertScope.EMERGENCY_ONLY to R.string.recipient_alerts_emergency,
                RecipientAlertScope.INFO_ONLY to R.string.recipient_alerts_info,
            )
            options.forEach { (option, labelRes) ->
                // No weight: each chip sizes to its own label so "Emergency" fits on one line
                // and "All" / "Info" don't waste a full third of the row each.
                FilterChip(
                    selected = scope == option,
                    onClick = { onScopeChange(option) },
                    label = {
                        Text(
                            text = stringResource(labelRes),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                )
            }
        }
        if (showError) {
            Text(
                text = stringResource(R.string.recipient_alerts_need_emergency),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
