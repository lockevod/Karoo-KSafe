package com.enderthor.kSafe.activity

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.enderthor.kSafe.data.KSafeBackup
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.data.ProviderType
import com.enderthor.kSafe.data.SenderConfig
import com.enderthor.kSafe.extension.jsonWithUnknownKeys
import com.enderthor.kSafe.extension.managers.ConfigurationManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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

    fun updateSenderConfig(provider: ProviderType, apiKey: String, userKey: String = "", phoneNumber: String = "") {
        val updated = senderConfigs.value.toMutableList()
        val idx = updated.indexOfFirst { it.provider == provider }
        val newConfig = SenderConfig(provider, apiKey, userKey, phoneNumber)
        if (idx >= 0) updated[idx] = newConfig else updated.add(newConfig)
        saveSenderConfigs(updated)
    }

    fun setActiveProvider(provider: ProviderType) {
        saveConfig(config.value.copy(activeProvider = provider))
    }

    // ─── Backup / Restore ─────────────────────────────────────────────────────

    /** Serializes current config + sender configs to a JSON string ready to export. */
    fun exportToJson(): String =
        Json.encodeToString(KSafeBackup(config.value, senderConfigs.value))

    /**
     * Parses [json] and overwrites stored config + sender configs.
     * Returns true on success, false if the JSON is invalid.
     */
    fun importFromJson(json: String): Boolean {
        return try {
            val backup = jsonWithUnknownKeys.decodeFromString<KSafeBackup>(json)
            saveConfig(backup.config)
            saveSenderConfigs(backup.senderConfigs)
            true
        } catch (e: Exception) {
            false
        }
    }
}
