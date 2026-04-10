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

enum class ProviderType { CALLMEBOT, WHAPI, PUSHOVER }

enum class CrashSensitivity {
    LOW,    // Requires stronger impact (fewer false positives)
    MEDIUM, // Balanced
    HIGH    // Detects lighter impacts (more sensitive)
}

enum class EmergencyStatus { IDLE, COUNTDOWN, ALERTING }

enum class EmergencyReason(val label: String) {
    MANUAL_SOS("Manual SOS"),
    CRASH_DETECTED("Crash detected"),
    CHECKIN_EXPIRED("Check-in expired")
}

// ─── Config models ────────────────────────────────────────────────────────────

@Serializable
data class EmergencyContact(
    val name: String = "",
    val phone: String = "",
    val email: String = ""
) {
    val hasPhone: Boolean get() = phone.isNotBlank()
    val hasEmail: Boolean get() = email.isNotBlank()
    val isValid: Boolean get() = name.isNotBlank() && (hasPhone || hasEmail)
}

@Serializable
data class KSafeConfig(
    val isActive: Boolean = true,
    val contacts: List<EmergencyContact> = emptyList(),
    val emergencyMessage: String = "EMERGENCY: Possible incident detected. Location: {location} (Reason: {reason}) {livetrack}",
    val karooLiveKey: String = "",
    val karooLiveEnabled: Boolean = false,
    val karooLiveStartMessage: String = "Ride started! Track me live: {livetrack}",
    val activeProvider: ProviderType = ProviderType.CALLMEBOT,
    val countdownSeconds: Int = DEFAULT_COUNTDOWN_SECONDS,
    // Crash detection
    val crashDetectionEnabled: Boolean = true,
    val crashSensitivity: CrashSensitivity = CrashSensitivity.MEDIUM,
    val minSpeedForCrashKmh: Int = 10,  // 0 = always detect (useful for testing)
    // Speed-drop detection
    val speedDropDetectionEnabled: Boolean = false,
    val speedDropMinutes: Int = DEFAULT_SPEED_DROP_MINUTES,
    // Check-in timer
    val checkinEnabled: Boolean = false,
    val checkinIntervalMinutes: Int = DEFAULT_CHECKIN_INTERVAL_MINUTES,
)

@Serializable
data class SenderConfig(
    val provider: ProviderType = ProviderType.CALLMEBOT,
    val apiKey: String = "",
    val userKey: String = "",   // Pushover: user/group key (apiKey = app token)
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

// ─── Defaults ─────────────────────────────────────────────────────────────────

val defaultSenderConfigs = listOf(
    SenderConfig(ProviderType.CALLMEBOT, ""),
    SenderConfig(ProviderType.WHAPI, ""),
    SenderConfig(ProviderType.PUSHOVER, "", ""),
)

val defaultSenderConfigJson: String = Json.encodeToString(defaultSenderConfigs)
val defaultKSafeConfigJson: String = Json.encodeToString(listOf(KSafeConfig()))
val defaultEmergencyStateJson: String = Json.encodeToString(EmergencyState())
