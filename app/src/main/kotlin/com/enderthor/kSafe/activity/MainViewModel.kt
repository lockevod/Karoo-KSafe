package com.enderthor.kSafe.activity

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.enderthor.kSafe.data.EmergencyContact
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.data.ProviderType
import com.enderthor.kSafe.data.SenderConfig
import com.enderthor.kSafe.extension.managers.ConfigurationManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    // ─── Contact helpers ──────────────────────────────────────────────────────

    fun addContact(contact: EmergencyContact) {
        val current = config.value
        if (current.contacts.size >= 3) return
        saveConfig(current.copy(contacts = current.contacts + contact))
    }

    fun removeContact(index: Int) {
        val current = config.value
        saveConfig(current.copy(contacts = current.contacts.toMutableList().also { it.removeAt(index) }))
    }

    fun updateContact(index: Int, contact: EmergencyContact) {
        val current = config.value
        saveConfig(current.copy(contacts = current.contacts.toMutableList().also { it[index] = contact }))
    }

    // ─── Provider helpers ─────────────────────────────────────────────────────

    fun updateSenderApiKey(provider: ProviderType, apiKey: String, userKey: String = "") {
        val updated = senderConfigs.value.toMutableList()
        val idx = updated.indexOfFirst { it.provider == provider }
        val newConfig = SenderConfig(provider, apiKey, userKey)
        if (idx >= 0) updated[idx] = newConfig else updated.add(newConfig)
        saveSenderConfigs(updated)
    }

    fun setActiveProvider(provider: ProviderType) {
        saveConfig(config.value.copy(activeProvider = provider))
    }
}
