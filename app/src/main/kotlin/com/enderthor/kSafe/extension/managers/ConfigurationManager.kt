package com.enderthor.kSafe.extension.managers

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.enderthor.kSafe.activity.dataStore
import com.enderthor.kSafe.data.EmergencyState
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.data.SenderConfig
import com.enderthor.kSafe.data.WellnessHistory
import com.enderthor.kSafe.data.defaultEmergencyStateJson
import com.enderthor.kSafe.data.defaultKSafeConfigJson
import com.enderthor.kSafe.data.defaultSenderConfigJson
import com.enderthor.kSafe.data.defaultWellnessHistoryJson
import com.enderthor.kSafe.data.migrateToLatest
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
    private val wellnessHistoryKey = stringPreferencesKey("wellnesshistory")

    // ─── KSafeConfig ──────────────────────────────────────────────────────────

    suspend fun saveConfig(config: KSafeConfig) {
        context.dataStore.edit { t ->
            t[configKey] = Json.encodeToString(listOf(config))
        }
    }

    fun loadConfigFlow(): Flow<KSafeConfig> {
        return context.dataStore.data.map { prefs ->
            val raw = (prefs[configKey] ?: defaultKSafeConfigJson)
                .replace("\"SIMPLEPUSH\"", "\"NTFY\"") // migration: SIMPLEPUSH renamed to NTFY
            try {
                (jsonWithUnknownKeys.decodeFromString<List<KSafeConfig>>(raw)
                    .firstOrNull() ?: KSafeConfig())
                    .migrateToLatest()
            } catch (e: Throwable) {
                // The catch block is the LAST resort — `jsonWithUnknownKeys` is configured
                // with `coerceInputValues = true` and `ignoreUnknownKeys = true`, so a
                // single stale enum value or removed field can no longer wipe the whole
                // config to defaults. Anything that still throws here is a structural JSON
                // problem (malformed bytes, type-of-collection change, etc.) that we
                // genuinely cannot recover from automatically. Log enough detail to
                // diagnose without dumping potentially-personal contact info.
                val snippet = raw.take(200).replace("\n", " ")
                Timber.e(e, "Failed to read KSafeConfig (%s: %s) — raw[0..200] = %s",
                    e.javaClass.simpleName, e.message, snippet)
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
            val raw = (prefs[senderConfigKey] ?: defaultSenderConfigJson)
                .replace("\"SIMPLEPUSH\"", "\"NTFY\"") // migration: SIMPLEPUSH renamed to NTFY
            try {
                jsonWithUnknownKeys.decodeFromString<List<SenderConfig>>(raw)
            } catch (e: Throwable) {
                val snippet = raw.take(200).replace("\n", " ")
                Timber.e(e, "Failed to read SenderConfig (%s: %s) — raw[0..200] = %s",
                    e.javaClass.simpleName, e.message, snippet)
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
            val raw = prefs[emergencyStateKey] ?: defaultEmergencyStateJson
            try {
                jsonWithUnknownKeys.decodeFromString<EmergencyState>(raw)
            } catch (e: Throwable) {
                val snippet = raw.take(200).replace("\n", " ")
                Timber.e(e, "Failed to read EmergencyState (%s: %s) — raw[0..200] = %s",
                    e.javaClass.simpleName, e.message, snippet)
                EmergencyState()
            }
        }
    }

    // ─── WellnessHistory ──────────────────────────────────────────────────────

    suspend fun saveWellnessHistory(history: WellnessHistory) {
        context.dataStore.edit { prefs ->
            prefs[wellnessHistoryKey] = Json.encodeToString(history)
        }
    }

    fun loadWellnessHistoryFlow(): Flow<WellnessHistory> {
        return context.dataStore.data.map { prefs ->
            val raw = prefs[wellnessHistoryKey] ?: defaultWellnessHistoryJson
            try {
                jsonWithUnknownKeys.decodeFromString<WellnessHistory>(raw)
            } catch (e: Throwable) {
                val snippet = raw.take(200).replace("\n", " ")
                Timber.e(e, "Failed to read WellnessHistory (%s: %s) — raw[0..200] = %s",
                    e.javaClass.simpleName, e.message, snippet)
                WellnessHistory()
            }
        }.distinctUntilChanged()
    }
}
