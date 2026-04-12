package com.enderthor.kSafe.activity

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.data.KSafeBackupExport
import com.enderthor.kSafe.data.ProviderType
import com.enderthor.kSafe.data.SenderConfig
import com.enderthor.kSafe.data.defaultSenderConfigs
import com.enderthor.kSafe.data.toBackupExport
import com.enderthor.kSafe.data.toSenderConfigs
import com.enderthor.kSafe.extension.jsonForExport
import com.enderthor.kSafe.extension.jsonWithUnknownKeys
import com.enderthor.kSafe.extension.managers.ConfigurationManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import timber.log.Timber

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val configManager = ConfigurationManager(application)

    val config: StateFlow<KSafeConfig> = configManager.loadConfigFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), KSafeConfig())

    val senderConfigs: StateFlow<List<SenderConfig>> = configManager.loadSenderConfigFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ─── Config updates ───────────────────────────────────────────────────────

    fun saveConfig(config: KSafeConfig) {
        viewModelScope.launch { configManager.saveConfig(config) }
    }

    fun saveSenderConfigs(configs: List<SenderConfig>) {
        viewModelScope.launch { configManager.saveSenderConfigs(configs) }
    }

    // ─── Provider helpers ─────────────────────────────────────────────────────

    fun updateSenderConfig(
        provider: ProviderType,
        apiKey: String,
        userKey: String = "",
        userKey2: String = "",
        userKey3: String = "",
        phoneNumber: String = ""
    ) {
        val updated = senderConfigs.value.toMutableList()
        val idx = updated.indexOfFirst { it.provider == provider }
        val newConfig = SenderConfig(provider, apiKey, userKey, userKey2, userKey3, phoneNumber)
        if (idx >= 0) updated[idx] = newConfig else updated.add(newConfig)
        saveSenderConfigs(updated)
    }

    fun setActiveProvider(provider: ProviderType) {
        saveConfig(config.value.copy(activeProvider = provider))
    }

    // ─── Backup / Restore ─────────────────────────────────────────────────────

    /**
     * Serializes current config + sender configs to a pretty-printed JSON string.
     * Each provider has its own typed block containing only its relevant fields,
     * making the file a clean, self-documented template for manual editing.
     */
    fun exportToJson(): String =
        jsonForExport.encodeToString(senderConfigs.value.toBackupExport(config.value))

    /**
     * Parses [json] and overwrites stored config + sender configs.
     *
     * Tolerant import — handles two formats transparently:
     *  1. New per-provider format: `{ "config":{…}, "callmebot":{…}, "pushover":{…}, … }`
     *  2. Legacy flat format:      `{ "config":{…}, "senderConfigs":[…] }`
     *
     * In both cases:
     *  - Extra/unknown keys are silently ignored (forward-compat with future fields).
     *  - Missing sections fall back to app defaults (backward-compat with older exports).
     *
     * Returns true on success, false if the JSON is structurally invalid.
     */
    fun importFromJson(json: String): Boolean {
        return try {
            val root = jsonWithUnknownKeys.parseToJsonElement(json) as? JsonObject
                ?: return false

            val config = root["config"]?.let {
                jsonWithUnknownKeys.decodeFromJsonElement<KSafeConfig>(it)
            } ?: KSafeConfig()

            val senderConfigs = if ("senderConfigs" in root) {
                // Legacy format: flat List<SenderConfig>
                root["senderConfigs"]?.let {
                    jsonWithUnknownKeys.decodeFromJsonElement<List<SenderConfig>>(it)
                } ?: defaultSenderConfigs
            } else {
                // New per-provider format
                jsonWithUnknownKeys.decodeFromJsonElement<KSafeBackupExport>(root)
                    .toSenderConfigs()
            }

            saveConfig(config)
            saveSenderConfigs(senderConfigs)
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to import config from JSON")
            false
        }
    }
}
