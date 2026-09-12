package com.enderthor.kSafe.activity

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.data.KSafeBackupExport
import com.enderthor.kSafe.data.ProviderType
import com.enderthor.kSafe.data.RecipientAlertScope
import com.enderthor.kSafe.data.SenderConfig
import com.enderthor.kSafe.data.defaultSenderConfigs
import com.enderthor.kSafe.data.materializeAlertDefaults
import com.enderthor.kSafe.data.migrateToLatest
import com.enderthor.kSafe.data.toBackupExport
import com.enderthor.kSafe.data.toSenderConfigs
import com.enderthor.kSafe.extension.carryForwardOnSave
import com.enderthor.kSafe.extension.jsonForExport
import com.enderthor.kSafe.extension.jsonWithUnknownKeys
import com.enderthor.kSafe.extension.managers.ConfigurationManager
import com.enderthor.kSafe.extension.streamUserProfile
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import timber.log.Timber

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val configManager = ConfigurationManager(application)

    /**
     * Settings-screen-owned KarooSystemService so the UI can read live data from the Karoo
     * (currently only [UserProfile] for the wellness "% of max HR" resolved-bpm hint).
     *
     * Separate from the [KSafeExtension]'s own service — that one runs in the extension
     * service process and is what the ride-time managers consume. Activities and services
     * communicate with the Karoo OS via independent client instances.
     */
    private val karooSystem = KarooSystemService(application).also { it.connect { } }

    val config: StateFlow<KSafeConfig> = configManager.loadConfigFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), KSafeConfig())

    val senderConfigs: StateFlow<List<SenderConfig>> = configManager.loadSenderConfigFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Live stream of the rider's Karoo profile, used by the wellness UI to show the
     * resolved bpm threshold derived from `maxHr × pct / 100`. Null until the Karoo
     * delivers the first event (no profile set in the launcher, or the system service
     * hasn't connected yet).
     */
    val userProfile: StateFlow<UserProfile?> = karooSystem.streamUserProfile()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // ─── Config updates ───────────────────────────────────────────────────────

    fun saveConfig(config: KSafeConfig) {
        viewModelScope.launch { configManager.saveConfig(config) }
    }

    /**
     * Read-modify-write a subset of config fields against the LATEST PERSISTED config rather
     * than a composition snapshot. A debounced multi-field save in SettingsScreen otherwise
     * captures `config` at launch time and `config.copy(...)` clobbers any OTHER field
     * (e.g. a calibration toggle) saved from a different snapshot in the same window —
     * a last-writer-wins lost update.
     *
     * Reads fresh from DataStore under [settingsWriteMutex] — NOT `config.value` — for the
     * Atomic read-modify-write inside ONE DataStore transaction via
     * [ConfigurationManager.updateConfig], so it can't lose unrelated fields to a concurrent
     * writer in the service process (e.g. profile-learning, which uses the same helper) and the
     * StateFlow's WhileSubscribed(5000) seed-default footgun can't apply (it never reads
     * `config.value`). Supersedes the previous fresh-read + in-process mutex.
     */
    fun updateConfig(transform: (KSafeConfig) -> KSafeConfig) {
        viewModelScope.launch { configManager.updateConfig(transform) }
    }

    fun saveSenderConfigs(configs: List<SenderConfig>) {
        viewModelScope.launch { configManager.saveSenderConfigs(configs) }
    }

    // ─── Provider helpers ─────────────────────────────────────────────────────

    /** Serialises the read-modify-write of the provider/sender blobs so two concurrent
     *  saves (e.g. ProviderScreen's debounced field auto-save racing a provider switch)
     *  can't both read the same DataStore snapshot and clobber each other's change. */
    private val settingsWriteMutex = Mutex()

    fun updateSenderConfig(
        provider: ProviderType,
        apiKey: String,
        userKey: String = "",
        userKey2: String = "",
        userKey3: String = "",
        phoneNumber: String = "",
        apiKey2: String = "",
        phoneNumber2: String = "",
        apiKey3: String = "",
        phoneNumber3: String = "",
        recipient1Alerts: RecipientAlertScope = RecipientAlertScope.ALL,
        recipient2Alerts: RecipientAlertScope = RecipientAlertScope.ALL,
        recipient3Alerts: RecipientAlertScope = RecipientAlertScope.ALL,
    ) {
        val newConfig = SenderConfig(
            provider = provider,
            apiKey = apiKey,
            userKey = userKey,
            userKey2 = userKey2,
            userKey3 = userKey3,
            phoneNumber = phoneNumber,
            apiKey2 = apiKey2,
            phoneNumber2 = phoneNumber2,
            apiKey3 = apiKey3,
            phoneNumber3 = phoneNumber3,
            recipient1Alerts = recipient1Alerts,
            recipient2Alerts = recipient2Alerts,
            recipient3Alerts = recipient3Alerts,
        )
        // ATOMIC read-modify-write inside one dataStore.edit{} — can't lost-update a concurrent
        // service-side send stamp (markSendSucceeded), and reads the freshly-persisted list (not
        // senderConfigs.value, which WhileSubscribed(5000) may hand back as emptyList()).
        viewModelScope.launch {
            settingsWriteMutex.withLock {
                configManager.updateSenderConfigs { current ->
                    val list = current.toMutableList()
                    val idx = list.indexOfFirst { it.provider == provider }
                    if (idx >= 0) {
                        // Carry forward the trust-carrying fields (last-successful-send stamp and
                        // the provider-warning acknowledgement) only when the credentials are
                        // unchanged — editing a token/phone/key invalidates both, but a scope-only
                        // change must NOT reset the "it works" / staleness clock.
                        list[idx] = carryForwardOnSave(newConfig, list[idx])
                    } else list.add(newConfig)
                    list
                }
            }
        }
    }

    /**
     * Rider ticked "I won't use alerts — stop warning me" on the incomplete-provider banner.
     * Deliberately its own targeted write rather than a field on the credential form's save:
     * [carryForwardOnSave] preserves the STORED acknowledgement, so routing this through
     * `updateSenderConfig` would discard it.
     */
    fun acknowledgeProviderWarning(provider: ProviderType, acknowledged: Boolean) {
        viewModelScope.launch {
            settingsWriteMutex.withLock {
                configManager.updateSenderConfigs { current ->
                    val list = current.toMutableList()
                    val idx = list.indexOfFirst { it.provider == provider }
                    if (idx >= 0) list[idx] = list[idx].copy(providerWarningAcknowledged = acknowledged)
                    else list.add(SenderConfig(provider = provider, providerWarningAcknowledged = acknowledged))
                    list
                }
            }
        }
    }

    fun setActiveProvider(provider: ProviderType) {
        // Atomic read-modify-write inside one DataStore transaction (can't race a service-process
        // config writer or lose unrelated fields; no WhileSubscribed seed-default footgun).
        viewModelScope.launch { configManager.updateConfig { it.copy(activeProvider = provider) } }
    }

    // ─── Backup / Restore ─────────────────────────────────────────────────────

    /**
     * Serializes current config + sender configs to a pretty-printed JSON string.
     * Each provider has its own typed block containing only its relevant fields,
     * making the file a clean, self-documented template for manual editing.
     *
     * Empty alert-customisation fields are pre-filled with their localised default texts
     * (see [materializeAlertDefaults]) so the rider has a concrete starting point to edit
     * rather than an empty string they'd have to know how to fill.
     *
     * Re-loads from DataStore via `first()` rather than `config.value` because the StateFlow
     * uses `WhileSubscribed(5000)` — if no UI is collecting at the moment Export is tapped
     * (e.g. the rider just toggled away from the Settings tab), `.value` would return the
     * `KSafeConfig()` initial value and the rider would silently get a defaults-only file
     * written to disk. The DataStore-backed flow always emits the persisted value first.
     */
    suspend fun exportToJson(): String {
        val freshConfig = configManager.loadConfigFlow().first()
        val freshSenders = configManager.loadSenderConfigFlow().first()
        val materialized = freshConfig.materializeAlertDefaults(getApplication())
        return jsonForExport.encodeToString(freshSenders.toBackupExport(materialized))
    }

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
                // Legacy format: flat List<SenderConfig>. `ifEmpty` matters: an explicit
                // `"senderConfigs": []` decodes to emptyList (the ?: only covers
                // null/missing), and persisting a literal [] is the ONLY way the stored
                // sender list can become genuinely empty — which ProviderScreen's
                // cold-load guard then reads as "not loaded yet" and silently skips
                // every credential save thereafter.
                root["senderConfigs"]?.let {
                    jsonWithUnknownKeys.decodeFromJsonElement<List<SenderConfig>>(it)
                }?.ifEmpty { defaultSenderConfigs } ?: defaultSenderConfigs
            } else {
                // New per-provider format
                jsonWithUnknownKeys.decodeFromJsonElement<KSafeBackupExport>(root)
                    .toSenderConfigs()
            }

            // Normalise the imported blob to the current schema before persisting. An export
            // from an older app version carries a stale configVersion + pre-migration field
            // layout; without this the read path eventually migrates it, but persisting the
            // un-migrated blob is a latent footgun (e.g. a version-0 stamp re-runs the v0→v2
            // crash-speed rewrite on every read). migrateToLatest() is idempotent.
            val migrated = config.migrateToLatest()
            // Persist both blobs in ONE coroutine, sequentially, under [settingsWriteMutex] so
            // the import can't interleave with a concurrent updateConfig / updateSenderConfig /
            // setActiveProvider (e.g. an in-flight ProviderScreen debounced auto-save) that
            // would otherwise read a pre-import snapshot and clobber the imported values.
            // DataStore can't write two keys atomically, but the ordered, serialised launch
            // removes the interleave window and guarantees senders are never written before
            // the config they belong to.
            viewModelScope.launch {
                settingsWriteMutex.withLock {
                    configManager.saveConfig(migrated)
                    configManager.saveSenderConfigs(senderConfigs)
                }
            }
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to import config from JSON")
            false
        }
    }

    override fun onCleared() {
        karooSystem.disconnect()
        super.onCleared()
    }
}
