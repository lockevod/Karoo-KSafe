package com.enderthor.kSafe.extension.managers

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.enderthor.kSafe.activity.dataStore
import com.enderthor.kSafe.data.EmergencyState
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.data.SenderConfig
import com.enderthor.kSafe.data.defaultEmergencyStateJson
import com.enderthor.kSafe.data.defaultKSafeConfigJson
import com.enderthor.kSafe.data.defaultSenderConfigJson
import com.enderthor.kSafe.extension.jsonWithUnknownKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

class ConfigurationManager(private val context: Context) {

    private val configKey = stringPreferencesKey("ksafeconfig")
    private val senderConfigKey = stringPreferencesKey("sender")
    private val emergencyStateKey = stringPreferencesKey("emergencystate")

    // ─── KSafeConfig ──────────────────────────────────────────────────────────

    suspend fun saveConfig(config: KSafeConfig) {
        context.dataStore.edit { t ->
            t[configKey] = Json.encodeToString(listOf(config))
        }
    }

    fun loadConfigFlow(): Flow<KSafeConfig> {
        return context.dataStore.data.map { prefs ->
            try {
                jsonWithUnknownKeys.decodeFromString<List<KSafeConfig>>(
                    prefs[configKey] ?: defaultKSafeConfigJson
                ).firstOrNull() ?: KSafeConfig()
            } catch (e: Throwable) {
                Timber.e(e, "Failed to read KSafeConfig")
                KSafeConfig()
            }
        }.distinctUntilChanged()
    }

    // ─── SenderConfig ─────────────────────────────────────────────────────────

    suspend fun saveSenderConfigs(configs: List<SenderConfig>) {
        context.dataStore.edit { prefs ->
            prefs[senderConfigKey] = Json.encodeToString(configs)
        }
    }

    fun loadSenderConfigFlow(): Flow<List<SenderConfig>> {
        return context.dataStore.data.map { prefs ->
            try {
                jsonWithUnknownKeys.decodeFromString<List<SenderConfig>>(
                    prefs[senderConfigKey] ?: defaultSenderConfigJson
                )
            } catch (e: Throwable) {
                Timber.e(e, "Failed to read SenderConfig")
                emptyList()
            }
        }.distinctUntilChanged()
    }

    // ─── EmergencyState ───────────────────────────────────────────────────────

    suspend fun saveEmergencyState(state: EmergencyState) {
        context.dataStore.edit { prefs ->
            prefs[emergencyStateKey] = Json.encodeToString(state)
        }
    }

    fun loadEmergencyStateFlow(): Flow<EmergencyState> {
        // No distinctUntilChanged — emergency state changes are infrequent and
        // missing an IDLE emission (e.g. due to a concurrent DataStore write) would
        // leave the data fields stuck showing the last countdown value.
        return context.dataStore.data.map { prefs ->
            try {
                jsonWithUnknownKeys.decodeFromString<EmergencyState>(
                    prefs[emergencyStateKey] ?: defaultEmergencyStateJson
                )
            } catch (e: Throwable) {
                Timber.e(e, "Failed to read EmergencyState")
                EmergencyState()
            }
        }
    }
}
