package com.enderthor.kSafe.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ─── Constants ────────────────────────────────────────────────────────────────

const val DEFAULT_COUNTDOWN_SECONDS = 30
const val DEFAULT_CHECKIN_INTERVAL_MINUTES = 120
const val DEFAULT_SPEED_DROP_MINUTES = 5
const val CHECKIN_WARNING_THRESHOLD_MINUTES = 10
const val SPEED_THRESHOLD_KMH = 5.0   // km/h — below this is considered "stopped"
const val KAROO_LIVE_BASE_URL = "https://dashboard.hammerhead.io/live/"

// ─── Enums ────────────────────────────────────────────────────────────────────

enum class ProviderType { CALLMEBOT, PUSHOVER, NTFY, TELEGRAM }

enum class CrashSensitivity {
    LOW,    // Requires stronger impact (fewer false positives)
    MEDIUM, // Balanced
    HIGH,   // Detects lighter impacts (more sensitive)
    CUSTOM  // User-defined threshold
}

enum class EmergencyStatus { IDLE, COUNTDOWN, ALERTING }

enum class EmergencyReason(val label: String) {
    MANUAL_SOS("Manual SOS"),
    CRASH_DETECTED("Crash detected"),
    CHECKIN_EXPIRED("Check-in expired")
}

// ─── Config models ────────────────────────────────────────────────────────────

@Serializable
data class KSafeConfig(
    val isActive: Boolean = true,
    val emergencyMessage: String = "EMERGENCY: Possible incident detected. Location: {location} (Reason: {reason}) {livetrack}",
    val karooLiveKey: String = "",
    val karooLiveEnabled: Boolean = false,
    val karooLiveStartMessage: String = "Ride started! Track me live: {livetrack}",
    val activeProvider: ProviderType = ProviderType.CALLMEBOT,
    val countdownSeconds: Int = DEFAULT_COUNTDOWN_SECONDS,
    // Crash detection
    val crashDetectionEnabled: Boolean = true,
    val crashSensitivity: CrashSensitivity = CrashSensitivity.MEDIUM,
    val minSpeedForCrashKmh: Int = 10,   // 0 = always detect (useful for testing)
    val customCrashThreshold: Int = 45, // m/s² — only used when crashSensitivity = CUSTOM (range 20–70)
    // Monitor crash even when no ride is active
    val crashMonitorOutsideRide: Boolean = false,         // uses configured minSpeedForCrashKmh
    val crashMonitorOutsideRideAnySpeed: Boolean = false, // forces minSpeed = 0 — ⚠ more false positives
    // Speed-drop detection
    val speedDropDetectionEnabled: Boolean = false,
    val speedDropMinutes: Int = DEFAULT_SPEED_DROP_MINUTES,
    // Check-in timer
    val checkinEnabled: Boolean = false,
    val checkinIntervalMinutes: Int = DEFAULT_CHECKIN_INTERVAL_MINUTES,
    // Ride end notification
    val karooLiveEndEnabled: Boolean = false,
    val karooLiveEndMessage: String = "Ride finished! 🏁",
    // Custom button message (sent on demand via BonusAction or button in app)
    val customMessageEnabled: Boolean = false,
    val customMessage: String = "I'm OK! 👍",
)

@Serializable
data class SenderConfig(
    val provider: ProviderType = ProviderType.CALLMEBOT,
    val apiKey: String = "",
    val userKey: String = "",       // Pushover: primary user key (apiKey = app token)
    val userKey2: String = "",      // Pushover: second user key (optional)
    val userKey3: String = "",      // Pushover: third user key (optional)
    val phoneNumber: String = "",   // CallMeBot: recipient WhatsApp number (with country code)
)

// ─── Emergency state (shared between extension and DataTypes via DataStore) ───

@Serializable
data class EmergencyState(
    val status: EmergencyStatus = EmergencyStatus.IDLE,
    val reason: String = "",
    val countdownStartTime: Long = 0L,
    val countdownDurationSeconds: Int = DEFAULT_COUNTDOWN_SECONDS,
    // Check-in timer
    val checkinEnabled: Boolean = false,
    val checkinStartTime: Long = 0L,
    val checkinIntervalMinutes: Int = DEFAULT_CHECKIN_INTERVAL_MINUTES,
) {
    /** Remaining countdown seconds calculated from stored start time. */
    fun countdownRemaining(): Int {
        if (status != EmergencyStatus.COUNTDOWN) return 0
        val elapsed = ((System.currentTimeMillis() - countdownStartTime) / 1000).toInt()
        return (countdownDurationSeconds - elapsed).coerceAtLeast(0)
    }

    /** Remaining check-in minutes calculated from stored start time. */
    fun checkinRemainingMinutes(): Int {
        if (!checkinEnabled) return 0
        val elapsedMinutes = ((System.currentTimeMillis() - checkinStartTime) / 60_000).toInt()
        return (checkinIntervalMinutes - elapsedMinutes).coerceAtLeast(0)
    }
}

// ─── Backup ───────────────────────────────────────────────────────────────────

// ── Per-provider export configs ──────────────────────────────────────────────
// Each class only contains the fields that the provider actually uses,
// with human-readable names so the exported file works as a clear template.

/** CallMeBot (WhatsApp) — needs an API key + the recipient's WhatsApp phone number. */
@Serializable
data class CallMeBotConfig(
    val apiKey: String = "",       // API key obtained from callmebot.com
    val phoneNumber: String = "",  // Recipient WhatsApp number with country code (e.g. +34612345678)
)

/** Pushover — app token (from pushover.net) + up to 3 recipient user/group keys. */
@Serializable
data class PushoverConfig(
    val appToken: String = "",     // Application token from pushover.net
    val userKey: String = "",      // Primary recipient user/group key
    val userKey2: String = "",     // Optional: second recipient user/group key
    val userKey3: String = "",     // Optional: third recipient user/group key
)

/** ntfy.sh — only needs a topic name. Free, no account required, unlimited messages. */
@Serializable
data class NtfyConfig(
    val topic: String = "",    // Topic name chosen by you (e.g. "my-ksafe-alerts"). Anyone who knows it can subscribe.
)

/** Telegram — bot token (from @BotFather) + up to 3 chat/channel/group IDs. */
@Serializable
data class TelegramConfig(
    val botToken: String = "",     // Bot token from @BotFather
    val chatId: String = "",       // Primary chat / channel / group ID
    val chatId2: String = "",      // Optional: second chat ID
    val chatId3: String = "",      // Optional: third chat ID
)

/**
 * Full configuration snapshot used for export/import.
 *
 * Each messaging provider has its own typed block with only the fields it actually
 * uses, so the exported file is a clean, self-documented template.
 *
 * Import is forward/backward compatible:
 *  - Missing fields within each block fall back to empty string (app default).
 *  - Unknown fields are silently ignored (see [jsonWithUnknownKeys]).
 *  - Old exports that used a flat [senderConfigs] list are handled transparently
 *    in the import logic (see MainViewModel.importFromJson).
 */
@Serializable
data class KSafeBackupExport(
    val config: KSafeConfig = KSafeConfig(),
    val callmebot: CallMeBotConfig = CallMeBotConfig(),
    val pushover: PushoverConfig = PushoverConfig(),
    val ntfy: NtfyConfig = NtfyConfig(),
    val telegram: TelegramConfig = TelegramConfig(),
)

/** Converts this export snapshot back to the flat [SenderConfig] list used internally. */
fun KSafeBackupExport.toSenderConfigs(): List<SenderConfig> = listOf(
    SenderConfig(ProviderType.CALLMEBOT,
        apiKey = callmebot.apiKey,
        phoneNumber = callmebot.phoneNumber),
    SenderConfig(ProviderType.PUSHOVER,
        apiKey = pushover.appToken,
        userKey = pushover.userKey,
        userKey2 = pushover.userKey2,
        userKey3 = pushover.userKey3),
    SenderConfig(ProviderType.NTFY,
        apiKey = ntfy.topic),
    SenderConfig(ProviderType.TELEGRAM,
        apiKey = telegram.botToken,
        userKey = telegram.chatId,
        userKey2 = telegram.chatId2,
        userKey3 = telegram.chatId3),
)

/** Builds a [KSafeBackupExport] from the current [config] and flat sender config list. */
fun List<SenderConfig>.toBackupExport(config: KSafeConfig): KSafeBackupExport {
    fun find(p: ProviderType) = firstOrNull { it.provider == p } ?: SenderConfig(p)
    val cmb = find(ProviderType.CALLMEBOT)
    val po  = find(ProviderType.PUSHOVER)
    val sp  = find(ProviderType.NTFY)
    val tg  = find(ProviderType.TELEGRAM)
    return KSafeBackupExport(
        config     = config,
        callmebot  = CallMeBotConfig(apiKey = cmb.apiKey, phoneNumber = cmb.phoneNumber),
        pushover   = PushoverConfig(appToken = po.apiKey, userKey = po.userKey, userKey2 = po.userKey2, userKey3 = po.userKey3),
        ntfy       = NtfyConfig(topic = sp.apiKey),
        telegram   = TelegramConfig(botToken = tg.apiKey, chatId = tg.userKey, chatId2 = tg.userKey2, chatId3 = tg.userKey3),
    )
}

// ─── Defaults ─────────────────────────────────────────────────────────────────

val defaultSenderConfigs = listOf(
    SenderConfig(ProviderType.CALLMEBOT),
    SenderConfig(ProviderType.PUSHOVER),
    SenderConfig(ProviderType.NTFY),
    SenderConfig(ProviderType.TELEGRAM),
)

val defaultSenderConfigJson: String = Json.encodeToString(defaultSenderConfigs)
val defaultKSafeConfigJson: String = Json.encodeToString(listOf(KSafeConfig()))
val defaultEmergencyStateJson: String = Json.encodeToString(EmergencyState())
