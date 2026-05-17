package com.enderthor.kSafe.extension.managers

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.enderthor.kSafe.activity.dataStore
import com.enderthor.kSafe.data.EmergencyState
import com.enderthor.kSafe.data.FuelingState
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.data.SenderConfig
import com.enderthor.kSafe.data.WellnessHistory
import com.enderthor.kSafe.data.defaultEmergencyStateJson
import com.enderthor.kSafe.data.defaultFuelingStateJson
import com.enderthor.kSafe.data.defaultKSafeConfigJson
import com.enderthor.kSafe.data.defaultSenderConfigJson
import com.enderthor.kSafe.data.defaultWellnessHistoryJson
import com.enderthor.kSafe.data.migrateToLatest
import com.enderthor.kSafe.extension.jsonForStorage
import com.enderthor.kSafe.extension.jsonWithUnknownKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import timber.log.Timber

class ConfigurationManager(private val context: Context) {

    private val configKey = stringPreferencesKey("ksafeconfig")
    private val senderConfigKey = stringPreferencesKey("sender")
    private val emergencyStateKey = stringPreferencesKey("emergencystate")
    private val wellnessHistoryKey = stringPreferencesKey("wellnesshistory")
    private val fuelingStateKey = stringPreferencesKey("fuelingstate")

    // ─── KSafeConfig ──────────────────────────────────────────────────────────

    suspend fun saveConfig(config: KSafeConfig) {
        context.dataStore.edit { t ->
            t[configKey] = jsonForStorage.encodeToString(listOf(config))
        }
    }

    /**
     * Shared, process-wide flow of the latest decoded [KSafeConfig]. Backed by the
     * companion's [sharedConfigStateFlow] so every caller — currently 10+ tappable
     * DataTypes plus KSafeExtension — observes the SAME decoded value. Before this
     * each DataType's `loadConfigFlow()` call ran its own `data → JSON decode →
     * migrateToLatest → distinctUntilChanged` pipeline; for any DataStore write the
     * decode pass ran N times. With the shared StateFlow it runs exactly once.
     *
     * The decoder still applies the same compatibility shims (`SIMPLEPUSH` → `NTFY`),
     * `coerceInputValues`, `ignoreUnknownKeys` and the structural-error fallback to
     * `KSafeConfig()`.
     *
     * `filterNotNull()` preserves the previous cold-flow contract — `.first()` on
     * this Flow waits for the FIRST real DataStore-backed emission, never returning
     * the placeholder seed. Without it, callers like `initializeSystem`'s
     * `loadConfigFlow().first()` could observe seed defaults (e.g. `KSafeConfig()`)
     * before the upstream collector has propagated the persisted config — a real
     * regression that the shared-flow refactor introduced.
     */
    fun loadConfigFlow(): Flow<KSafeConfig> = sharedConfigStateFlow(context).filterNotNull()

    private fun decodeConfig(raw: String): KSafeConfig {
        val migrated = raw.replace("\"SIMPLEPUSH\"", "\"NTFY\"") // migration: SIMPLEPUSH renamed
        return try {
            (jsonWithUnknownKeys.decodeFromString<List<KSafeConfig>>(migrated)
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
            val snippet = migrated.take(200).replace("\n", " ")
            Timber.e(e, "Failed to read KSafeConfig (%s: %s) — raw[0..200] = %s",
                e.javaClass.simpleName, e.message, snippet)
            KSafeConfig()
        }
    }

    // ─── SenderConfig ─────────────────────────────────────────────────────────

    suspend fun saveSenderConfigs(configs: List<SenderConfig>) {
        context.dataStore.edit { prefs ->
            prefs[senderConfigKey] = jsonForStorage.encodeToString(configs)
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
            prefs[emergencyStateKey] = jsonForStorage.encodeToString(state)
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

    // ─── FuelingState (carbs/hydration accumulator persistence) ───────────────

    /**
     * Persist a snapshot of both fueling trackers. Called every ~30 s from
     * KSafeExtension while a ride is recording so an extension crash never
     * loses more than ~30 s of integrated target / logged intake.
     */
    suspend fun saveFuelingState(state: FuelingState) {
        context.dataStore.edit { prefs ->
            prefs[fuelingStateKey] = jsonForStorage.encodeToString(state)
        }
    }

    /**
     * One-shot read of the persisted [FuelingState]. Returns a default-initialised
     * instance when nothing has been saved yet (fresh install, or after [clearFuelingState]).
     * Decode failures are logged and also coerced to the default — better to lose
     * an hour of fueling totals than to crash the extension on boot due to a stale
     * JSON shape.
     */
    suspend fun loadFuelingState(): FuelingState {
        return try {
            val prefs = context.dataStore.data.first()
            val raw = prefs[fuelingStateKey] ?: defaultFuelingStateJson
            jsonWithUnknownKeys.decodeFromString<FuelingState>(raw)
        } catch (e: Throwable) {
            Timber.e(e, "Failed to read FuelingState — using defaults")
            FuelingState()
        }
    }

    /** Removes the persisted snapshot. Called on the Recording → Idle transition
     *  so the next ride starts with a clean accumulator instead of resuming the
     *  previous ride's totals. */
    suspend fun clearFuelingState() {
        context.dataStore.edit { prefs -> prefs.remove(fuelingStateKey) }
    }

    // ─── WellnessHistory ──────────────────────────────────────────────────────

    suspend fun saveWellnessHistory(history: WellnessHistory) {
        context.dataStore.edit { prefs ->
            prefs[wellnessHistoryKey] = jsonForStorage.encodeToString(history)
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

    // ─── Shared KSafeConfig flow (process-wide singleton) ─────────────────────
    companion object {
        /**
         * Single shared scope for the process-wide [KSafeConfig] StateFlow. Lives for the
         * lifetime of the extension process — DataStore's own Flow is process-bound, so
         * keeping one upstream collector alive for the same lifetime has no leak risk.
         * `Dispatchers.Default` because the only work on this scope is JSON decode +
         * migrateToLatest, both CPU-bound.
         */
        private val sharedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Seeded with `null` ("no real value emitted yet") so callers can wait via
         *  `.filterNotNull().first()` instead of accidentally returning [KSafeConfig]
         *  defaults before the upstream collector has propagated the persisted state.
         *  See [loadConfigFlow] for the public-API rationale. */
        @Volatile private var sharedState: StateFlow<KSafeConfig?>? = null
        @Volatile private var sharedJob: kotlinx.coroutines.Job? = null
        private val sharedLock = Any()

        /**
         * Returns the process-wide [StateFlow] of the decoded [KSafeConfig]. Lazy: the
         * first caller wires up the upstream `dataStore.data` collector + the JSON decode
         * + migration. Subsequent callers see the cached state immediately and receive
         * the same updates as everyone else — one decode per DataStore write, regardless
         * of how many DataTypes are subscribed.
         *
         * Started eagerly on first call so the extension can rely on a fresh value being
         * available from the moment it queries — same lifecycle as the previous
         * `loadConfigFlow().first()` pattern but without re-decoding on every call.
         */
        internal fun sharedConfigStateFlow(context: Context): StateFlow<KSafeConfig?> {
            sharedState?.let { return it }
            return synchronized(sharedLock) {
                sharedState ?: run {
                    val mgr = ConfigurationManager(context.applicationContext)
                    // Nullable seed — see [sharedState] doc. `loadConfigFlow()` does the
                    // `.filterNotNull()` so external callers never observe the null.
                    val seed = MutableStateFlow<KSafeConfig?>(null)
                    sharedJob = sharedScope.launch {
                        context.applicationContext.dataStore.data
                            .map { prefs ->
                                mgr.decodeConfig(prefs[mgr.configKey] ?: defaultKSafeConfigJson)
                            }
                            .distinctUntilChanged()
                            .collect { seed.value = it }
                    }
                    seed.asStateFlow().also { sharedState = it }
                }
            }
        }

        /**
         * Test / teardown hook — cancels the upstream collector and resets the cached
         * shared flow so the next call to [sharedConfigStateFlow] re-wires it. Without
         * the explicit cancel, repeated resets would leak parallel collectors that all
         * write to orphaned MutableStateFlows. Production never invokes this; the
         * process-bound DataStore Flow handles its own lifecycle.
         */
        @Suppress("unused")
        internal fun resetSharedConfigStateForTesting() {
            synchronized(sharedLock) {
                sharedJob?.cancel()
                sharedJob = null
                sharedState = null
            }
        }
    }
}
