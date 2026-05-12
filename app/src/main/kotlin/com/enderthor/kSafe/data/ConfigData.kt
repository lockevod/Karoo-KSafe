package com.enderthor.kSafe.data

import android.content.Context
import com.enderthor.kSafe.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

// ─── Constants ────────────────────────────────────────────────────────────────

const val DEFAULT_COUNTDOWN_SECONDS = 30
const val DEFAULT_CHECKIN_INTERVAL_MINUTES = 120
const val DEFAULT_SPEED_DROP_MINUTES = 5
const val CHECKIN_WARNING_THRESHOLD_MINUTES = 10
const val SPEED_THRESHOLD_KMH = 5.0   // km/h — below this is considered "stopped"
const val KAROO_LIVE_BASE_URL = "https://dashboard.hammerhead.io/live/"

// ─── Schema versioning ────────────────────────────────────────────────────────

/**
 * Current KSafeConfig schema version. Increment whenever a new field is added
 * that requires preset-specific defaults different from the Kotlin data-class default.
 *
 * History:
 *  v0 (default) → v2 : minSpeedForCrashKmh and crashConfirmSpeedKmh gained preset-canonical
 *                       values (LOW=3/3, MEDIUM=10/5, HIGH=15/5).
 *  v2 → v3 : medicalEpisodeEnabled, medicalResponseLevel, wellnessEnabled, wellnessResponseLevel,
 *            wellnessHighHrThreshold, wellnessHighHrDurationMinutes added. Migration was a
 *            pure version stamp.
 *  v3 → v4 : carbs/hydration tracker fields (22 fields) added. Migration is a pure version stamp;
 *            all fields auto-fill from Kotlin defaults via kotlinx.serialization with
 *            ignoreUnknownKeys = true.
 *  v4 → v5 : per-tracker time-alert initial delay (carbTimeInitialDelayMin / hydrationTimeInitialDelayMin)
 *            and per-tracker custom alert title (carbAlertCustomTitle / hydrationAlertCustomTitle) fields
 *            added. Pure version stamp.
 *  v5 → v6 : wellness can be configured by % of the rider's Karoo-profile maxHr instead of absolute bpm
 *            (wellnessUseMaxHrPercent / wellnessHighHrPercent). Pure version stamp.
 *  v6 → v7 : three-tier wellness model — critical-HR tier (early warning) + cardiac-decoupling tier
 *            (HR / power ratio drift, requires power meter) added alongside the existing sustained-HR
 *            tier. Each tier has its own enable + parameters. Pure version stamp.
 *  v7 → v8 : EmergencyState gains reasonEnum (EmergencyReason?) so the state machine can recover
 *            the typed reason after a process restart. Legacy reads without this field get null
 *            automatically via coerceInputValues = true. Pure version stamp.
 *  v8 → v9 : carbBeepPattern / hydBeepPattern (rider-selectable beep patterns for fueling alerts).
 *            Default SINGLE_LONG preserves v8 audible behaviour. Pure version stamp.
 *  v9 → v10: wellnessBeepPattern (rider-selectable beep for all WARNING-level alerts — wellness
 *            tiers + medical when downgraded to Warning). Emergency-level paths keep the
 *            hardcoded urgent default by design. Pure version stamp.
 */
const val CONFIG_VERSION = 10

/**
 * Canonical minSpeedForCrashKmh value per preset.
 * These are the values the Settings screen sets when the user taps a preset chip.
 */
val PRESET_MIN_SPEED = mapOf(
    CrashSensitivity.LOW    to 3,
    CrashSensitivity.MEDIUM to 10,
    CrashSensitivity.HIGH   to 15,
)

/**
 * Canonical crashConfirmSpeedKmh value per preset.
 * Reflects the real-data calibration: LOW users (MTB/gravel) often crash at very low speed.
 */
val PRESET_CONFIRM_SPEED = mapOf(
    CrashSensitivity.LOW    to 3,
    CrashSensitivity.MEDIUM to 5,
    CrashSensitivity.HIGH   to 5,
)

// ─── Field colour palette ─────────────────────────────────────────────────────

/** Dark colours that look good with white text on a Karoo ride field.
 *
 *  Twelve hues organised as 6 families × 2 shades (lighter / darker), inspired by
 *  timklge/karoo-reminder's palette.
 *
 *  Reserved colours — deliberately NOT in this palette because each is hard-wired to a
 *  state in one or more DataTypes; letting riders pick them would collide with the
 *  state-machine signalling and the rider could not tell idle from alert at a glance:
 *    - 0xFFE65100 orange  — SOS COUNTDOWN, Safety Timer CANCEL, CustomMessage SENDING
 *    - 0xFFB71C1C red     — SOS ALERTING, Safety Timer EXPIRED, CustomMessage ERROR
 *    - 0xFFF57F17 amber   — Safety Timer WARNING
 *    - 0xFF1B5E20 green   — CarbLog / HydrationLog LOGGED, CustomMessage SENT (success flash)
 *    - 0xFF424242 grey    — every field's OFF / disabled state
 */
/** Sentinel values identifying bundled vector drawables in the icon palettes.
 *  Riders pick one of these entries to render the matching `R.drawable.ic_fuel_*`
 *  resource as the slot field's left compound drawable instead of an emoji prefix.
 *  Used for shapes Unicode has no good emoji for — sports gel pouches and cyclist
 *  bidón bottles. Both sentinel strings are angle-bracketed so they can never
 *  collide with a real emoji codepoint sequence and so the existing
 *  `String.replace("{$k}", v)` token substitution can't accidentally consume them. */
const val FUEL_GEL_DRAWABLE    = "<gel>"
const val FUEL_BOTTLE_DRAWABLE = "<bottle>"

/** Emoji palettes for the per-slot icon picker. The first entry is `""` (no icon),
 *  picked by riders who only want the label text — see [FieldEmojiPicker]. The Android
 *  system renders these in colour even when the surrounding TextView is white, which
 *  gives them visual punch on the coloured field backgrounds without any drawable
 *  bundling work.
 *
 *  The two palettes are deliberately disjoint and themed:
 *    - Carb palette = cyclist-typical solid food. The first non-empty entry is the
 *      [FUEL_GEL_DRAWABLE] sentinel — a real gel pouch vector drawable rather than
 *      an emoji approximation. Then 🍯 for honey / sticky gel substitutes, 🍫 for
 *      energy bars, 🍽 for a proper combo plate at a long stop, etc.
 *    - Drink palette = water / hydration / drink containers. The first non-empty
 *      entry is the [FUEL_BOTTLE_DRAWABLE] sentinel — a real cyclist bidón vector
 *      drawable rather than a generic 🥤 / 🍶. No entry appears in both pickers
 *      so the rider can't get confused which palette they're in.
 */
val FUEL_EMOJI_CARB: List<String> = listOf(
    "", FUEL_GEL_DRAWABLE, "🍌", "🥜", "🍫", "🍪", "🥨", "🍯", "⚡", "🍞", "🥪", "🍽", "🍎", "🍇",
)
val FUEL_EMOJI_DRINK: List<String> = listOf(
    "", FUEL_BOTTLE_DRAWABLE, "💧", "🥤", "🍶", "🧃", "🧊", "☕", "🍵", "💦",
)

val FIELD_COLOR_PALETTE: List<Int> = listOf(
    0xFF1565C0.toInt(),  // Blue            (default actions / webhooks)
    0xFF0D47A1.toInt(),  // Deep Blue
    0xFF00838F.toInt(),  // Teal
    0xFF004D5B.toInt(),  // Deep Teal
    0xFF2E7D32.toInt(),  // Forest Green    (default SOS / Timer; deliberately darker than success flash)
    0xFF33691E.toInt(),  // Olive Green
    0xFF6A1B9A.toInt(),  // Purple
    0xFF4A148C.toInt(),  // Deep Purple
    0xFF880E4F.toInt(),  // Pink
    0xFFAD1457.toInt(),  // Magenta
    0xFF455A64.toInt(),  // Slate
    0xFF263238.toInt(),  // Deep Slate
    // ── New distinct hues (v2.x) to widen differentiation in the picker.
    // All chosen at Material 700-900 luminance for white-text contrast ≥7:1
    // and at hue distances ≥30° from every state-driven colour (orange E65100,
    // red B71C1C, yellow F57F17, green 1B5E20, grey OFF 424242).
    0xFF283593.toInt(),  // Indigo          (cool blue-violet, distinct from Blue/Deep Blue)
    0xFF5D4037.toInt(),  // Brown           (warm earth tone, no other warm-dark hue in palette)
    0xFF006064.toInt(),  // Dark Cyan       (saturated cyan, distinct from blue-leaning Teal)
    0xFF4E0A18.toInt(),  // Burgundy        (deep wine, distinct from hot Pink / Magenta)
)

// ─── Enums ────────────────────────────────────────────────────────────────────

enum class ProviderType { CALLMEBOT, PUSHOVER, NTFY, TELEGRAM }

enum class CrashSensitivity {
    LOW,    // Requires stronger impact (fewer false positives)
    MEDIUM, // Balanced
    HIGH,   // Detects lighter impacts (more sensitive)
    CUSTOM  // User-defined threshold
}

/**
 * How an incident detector's emission should be handled.
 *
 *  - [SILENT]    — log to calibration only; no UI, no notification, no contact alert.
 *  - [WARNING]   — full-screen `InRideAlert` overlay + configurable beep
 *                  ([KSafeConfig.wellnessBeepPattern]). No countdown, no contact alert.
 *                  The alert lands on top of whatever ride screen the rider is on so it
 *                  is actually seen mid-ride — `SystemNotification` would route the
 *                  message to the Karoo OS Control Center instead, which is not visible
 *                  while riding.
 *  - [EMERGENCY] — full crash flow: cancellable countdown + contact alert.
 */
enum class IncidentResponseLevel { SILENT, WARNING, EMERGENCY }

@Serializable
enum class EmergencyStatus { IDLE, COUNTDOWN, ALERTING }

@Serializable
enum class EmergencyReason(val label: String) {
    MANUAL_SOS("Manual SOS"),
    CRASH_DETECTED("Crash detected"),
    CHECKIN_EXPIRED("Check-in expired"),
    /** HR-flatline sub-detector of [MedicalEpisodeDetector]. Same user-visible label as [MEDICAL_COLLAPSE]. */
    MEDICAL_FLATLINE("Medical episode detected"),
    /** HR-collapse sub-detector of [MedicalEpisodeDetector]. Same user-visible label as [MEDICAL_FLATLINE]. */
    MEDICAL_COLLAPSE("Medical episode detected"),
    /** Sustained HR over [KSafeConfig.wellnessHighHrThreshold] for [KSafeConfig.wellnessHighHrDurationMinutes].
     *  Originally the only wellness reason; semantically kept as the "sustained tier" of the
     *  three-tier wellness model introduced in revision 3 of the algorithm. */
    WELLNESS_HIGH_HR("Sustained high heart rate"),
    /** Critical-tier wellness alert: HR over [KSafeConfig.wellnessCriticalThresholdBpm] (or % equivalent)
     *  for [KSafeConfig.wellnessCriticalDurationMinutes]. Earlier-warning tier than WELLNESS_HIGH_HR. */
    WELLNESS_CRITICAL_HR("Heart rate critical"),
    /** Cardiac decoupling: HR/power ratio drift > [KSafeConfig.wellnessDecouplingThresholdPct] sustained
     *  for [KSafeConfig.wellnessDecouplingDurationMinutes]. Clinical indicator of dehydration / heat stress. */
    WELLNESS_DECOUPLING("Heart rate drift detected"),
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
    val customCrashThreshold: Int = 45, // m/s — only used when crashSensitivity = CUSTOM (range 20–70)
    val crashConfirmSpeedKmh: Int = 5,  // km/h — max speed to confirm crash (rider considered stopped)
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
    // Custom button messages (slots 1–3, sent on demand via data field tap or button in app)
    val customMessageEnabled: Boolean = false,
    val customMessageTitle: String = "MSG",
    val customMessage: String = "I'm OK! 👍",
    val customMessage2Enabled: Boolean = false,
    val customMessage2Title: String = "MSG2",
    val customMessage2: String = "",
    val customMessage3Enabled: Boolean = false,
    val customMessage3Title: String = "MSG3",
    val customMessage3: String = "",
    // ─── Medical episode detector (HR-based) ──────────────────────────────────
    /** Master toggle for [MedicalEpisodeDetector]. Default ON — self-gated by HR sensor presence. */
    val medicalEpisodeEnabled: Boolean = true,
    /** Response level for medical episodes. Default EMERGENCY (cardiac arrest = same severity as crash). */
    val medicalResponseLevel: IncidentResponseLevel = IncidentResponseLevel.EMERGENCY,
    /** Custom InRideAlert title/detail for medical incidents. Empty → built-in defaults. */
    val medicalCustomTitle: String = "",
    val medicalCustomDetail: String = "",
    // ─── Wellness monitor (sustained high HR) ────────────────────────────────
    /** Master toggle for [WellnessMonitor]. Default OFF — opt-in: thresholds depend on user age/fitness. */
    val wellnessEnabled: Boolean = false,
    /** When true, on the first Recording transition of a session KSafe consults the last
     *  10 stored ride wellness records and fires an InRideAlert if the rider should take
     *  it easy (high cardiac drift / many wellness fires / multiple hard rides in a row).
     *  Silent when the rider is fully recovered — no per-ride spam. Default ON. */
    val readinessAtRideStartEnabled: Boolean = true,
    /** Response level for wellness alerts. Default WARNING — on-screen only, never to contacts. */
    val wellnessResponseLevel: IncidentResponseLevel = IncidentResponseLevel.WARNING,
    /** HR threshold for the wellness monitor (bpm). User-tunable in the Health tab.
     *  Used when [wellnessUseMaxHrPercent] = false (default). */
    val wellnessHighHrThreshold: Int = 180,
    /** How long HR must stay above the effective threshold continuously before warning fires (minutes). */
    val wellnessHighHrDurationMinutes: Int = 30,
    /** Custom InRideAlert title/detail for the SUSTAINED HR tier. Empty → built-in defaults. */
    val wellnessSustainedCustomTitle: String = "",
    val wellnessSustainedCustomDetail: String = "",
    /** When true, the wellness threshold is derived as `userProfile.maxHr * wellnessHighHrPercent / 100`
     *  instead of using the absolute [wellnessHighHrThreshold]. Auto-scales with the rider's Karoo
     *  profile so the threshold matches their age / fitness without manual tuning. */
    val wellnessUseMaxHrPercent: Boolean = false,
    /** Percent of max HR used as the wellness threshold when [wellnessUseMaxHrPercent] is true. 92%
     *  sits at the top of zone 5 (VO2max) for most riders — sustained > 30 min is genuinely worth flagging. */
    val wellnessHighHrPercent: Int = 92,
    /** Sub-toggle for the sustained tier (the rule above). Allows the rider to disable just the
     *  sustained tier while keeping critical / decoupling on. Gated behind the [wellnessEnabled] master. */
    val wellnessSustainedEnabled: Boolean = true,
    // ─── Critical HR tier (tier 1 — early warning) ───────────────────────────
    /** Sub-toggle for the critical-HR tier. Fires earlier than the sustained tier — high HR for short time. */
    val wellnessCriticalEnabled: Boolean = true,
    /** Critical HR threshold in absolute bpm, used when [wellnessUseMaxHrPercent] is false. */
    val wellnessCriticalThresholdBpm: Int = 175,
    /** Critical HR threshold as % of max HR, used when [wellnessUseMaxHrPercent] is true. 95 % is the
     *  top of zone 5 (VO2max → anaerobic) — sustained more than a few minutes is real overexertion. */
    val wellnessCriticalThresholdPct: Int = 95,
    /** How long HR must stay above the critical threshold before the tier fires (minutes). */
    val wellnessCriticalDurationMinutes: Int = 5,
    /** Custom InRideAlert title/detail for the CRITICAL HR tier. Empty → built-in defaults. */
    val wellnessCriticalCustomTitle: String = "",
    val wellnessCriticalCustomDetail: String = "",
    // ─── Cardiac decoupling tier (tier 3 — heat stress / dehydration) ────────
    /** Sub-toggle for the cardiac-decoupling tier. Auto-disabled at runtime if the rider has no
     *  power meter paired (the algorithm needs HR / power ratio). Master switch must also be on. */
    val wellnessDecouplingEnabled: Boolean = true,
    /** Drift threshold as a % above the rider's baseline HR / power ratio. 7 % = early heat-stress
     *  indicator; 10 %+ = significant. Sustained drift above the threshold for [wellnessDecouplingDurationMinutes]. */
    val wellnessDecouplingThresholdPct: Int = 7,
    /** How long the drift must stay above the threshold before the tier fires (minutes). */
    val wellnessDecouplingDurationMinutes: Int = 10,
    /** Custom InRideAlert title/detail for the cardiac DECOUPLING tier. Empty → built-in defaults. */
    val wellnessDecouplingCustomTitle: String = "",
    val wellnessDecouplingCustomDetail: String = "",
    /** Beep pattern played for WARNING-level alerts (wellness sustained / critical / decoupling
     *  and any medical incident the rider downgraded to WARNING). Emergency-level alerts
     *  (crash / medical-collapse on EMERGENCY) stay on the urgent BEEP_LONG + BEEP_URGENT
     *  countdown sequence — those grab attention by design and aren't user-mutable. */
    val wellnessBeepPattern: BeepPattern = BeepPattern.SINGLE_LONG,
    // Calibration logging — writes detailed sensor events to CSV for threshold tuning
    val calibrationLoggingEnabled: Boolean = false,
    // Field colours — idle/ready background for each ride-screen widget
    val sosFieldColor: Int = 0xFF2E7D32.toInt(),       // SOS: idle=SAFE (forest green)
    val timerFieldColor: Int = 0xFF2E7D32.toInt(),     // Safety Timer: OK (forest green)
    val customMsg1Color: Int = 0xFF1565C0.toInt(),     // Custom Message 1: idle (blue)
    val customMsg2Color: Int = 0xFF1565C0.toInt(),     // Custom Message 2: idle (blue)
    val customMsg3Color: Int = 0xFF1565C0.toInt(),     // Custom Message 3: idle (blue)
    val webhook1Color: Int = 0xFF1565C0.toInt(),       // Webhook 1: idle (blue)
    val webhook2Color: Int = 0xFF1565C0.toInt(),       // Webhook 2: idle (blue)
    // Webhook actions — generic HTTP buttons assignable to Karoo hardware buttons.
    // Each action fires a single HTTP request (GET or POST) to any endpoint.
    // Compatible with Home Assistant, ntfy, IFTTT, n8n, Make, and any webhook service.
    val webhook1Enabled: Boolean = false,
    val webhook1Label: String = "Action 1",   // shown in in-ride alerts
    val webhook1Url: String = "",
    val webhook1Method: String = "POST",       // "GET" or "POST"
    val webhook1Headers: String = "",          // one header line: "Authorization: Bearer xxx"
    val webhook1Body: String = "",             // optional POST body (e.g. JSON)
    val webhook2Enabled: Boolean = false,
    val webhook2Label: String = "Action 2",
    val webhook2Url: String = "",
    val webhook2Method: String = "POST",
    val webhook2Headers: String = "",
    val webhook2Body: String = "",
    // Geo-fence for webhook triggers — when enabled the webhook only fires if the device
    // is within [webhookNGeoRadiusM] metres of the configured target coordinates.
    val webhook1GeoEnabled: Boolean = false,
    val webhook1GeoLat: Double = 0.0,
    val webhook1GeoLon: Double = 0.0,
    val webhook1GeoRadiusM: Int = 50,
    val webhook2GeoEnabled: Boolean = false,
    val webhook2GeoLat: Double = 0.0,
    val webhook2GeoLon: Double = 0.0,
    val webhook2GeoRadiusM: Int = 50,
    // Ride alert — when enabled a SystemNotification with a custom text is shown after the webhook fires.
    // Useful as an accidental-press warning: the user sees exactly what action was triggered.
    val webhook1AlertEnabled: Boolean = false,
    val webhook1AlertText: String = "",
    val webhook2AlertEnabled: Boolean = false,
    val webhook2AlertText: String = "",
    // ─── Carbs tracker (HR/power-aware nutrition) ───────────────────────────
    /** Master toggle. Opt-in feature, off by default. */
    val carbsTrackerEnabled: Boolean = false,
    /** Base carb intake target (g/h). Modulated at runtime by the IntensityZoneCalculator. */
    val carbTargetGperHour: Int = 60,
    /** When true, alert when (cumulative target − cumulative logged) exceeds threshold. */
    val carbDeficitAlertEnabled: Boolean = true,
    val carbDeficitThresholdG: Int = 25,
    /** When true, alert when too much time has passed since the last log. Combinable with deficit alert. */
    val carbTimeAlertEnabled: Boolean = false,
    val carbTimeIntervalMin: Int = 25,
    /** Initial grace period (minutes) before the time-based alert can fire for the first time
     *  in a session — most riders don't eat in the first 20 minutes of a ride. After the first
     *  alert fires or the first log, normal interval logic resumes. 0 = disabled (the first
     *  time alert fires after `carbTimeIntervalMin` from session start, original behaviour). */
    val carbTimeInitialDelayMin: Int = 30,
    /** Optional custom title shown in the InRideAlert overlay. Empty = use the default
     *  `R.string.fueling_carb_alert_title` ("Eat something"). Same for both deficit and time
     *  alert sources — pair with [carbAlertCustomDetail] for the full message. */
    val carbAlertCustomTitle: String = "",
    /** Optional custom detail template. Empty = use source-specific defaults
     *  (`fueling_carb_alert_detail_deficit` / `_time`). When set, the same template is used
     *  for both alert sources; tokens `{deficit}`, `{elapsed}`, `{target}` substituted at runtime. */
    val carbAlertCustomDetail: String = "",
    /** Beep pattern played when a carb alert fires. OFF = visual only. See [BeepPattern]
     *  for the available presets. Default keeps the v8 behaviour (single 880 Hz × 800 ms). */
    val carbBeepPattern: BeepPattern = BeepPattern.SINGLE_LONG,
    /** Three logging slots, each user-configurable label + grams + idle background colour
     *  + optional emoji prefix. Empty `carbNIcon` = no emoji, label only. */
    val carb1Label: String = "Gel",      val carb1Grams: Int = 25,    val carb1Color: Int = 0xFF1565C0.toInt(),    val carb1Icon: String = FUEL_GEL_DRAWABLE,
    val carb2Label: String = "Bar",      val carb2Grams: Int = 30,    val carb2Color: Int = 0xFF1565C0.toInt(),    val carb2Icon: String = "🍫",
    val carb3Label: String = "Fruit",    val carb3Grams: Int = 20,    val carb3Color: Int = 0xFF1565C0.toInt(),    val carb3Icon: String = "🍌",

    // ─── Hydration tracker (flat target by time, no sensor input) ───────────
    val hydrationTrackerEnabled: Boolean = false,
    val hydrationTargetMlPerHour: Int = 750,
    /** When true, the hydration tracker ignores [hydrationTargetMlPerHour] and computes a
     *  dynamic per-hour target from HR, power, weight, ambient temperature and humidity via
     *  the SweatEstimator (Keytel HR-derived metabolic rate × WBGT heat factor × weight
     *  scaling). Temperature is sourced from the `karoo-headwind` extension stream when
     *  installed (real meteo data) or from the Karoo onboard sensor as fallback (sesgo
     *  device-heat +3-8 °C). Humidity comes from Headwind only; without it the estimator
     *  assumes 50 % RH. Opt-in: default OFF so existing riders keep the fixed target. */
    val hydrationDynamicEstimateEnabled: Boolean = false,
    val hydrationDeficitAlertEnabled: Boolean = true,
    val hydrationDeficitThresholdMl: Int = 300,
    val hydrationTimeAlertEnabled: Boolean = false,
    val hydrationTimeIntervalMin: Int = 20,
    /** Same semantics as `carbTimeInitialDelayMin`. 0 = disabled. */
    val hydrationTimeInitialDelayMin: Int = 30,
    /** Optional custom title shown in the InRideAlert overlay. Empty = use the default
     *  `R.string.fueling_hyd_alert_title` ("Drink something"). Pair with [hydrationAlertCustomDetail]. */
    val hydrationAlertCustomTitle: String = "",
    /** Optional custom detail template. Empty = use source-specific defaults
     *  (`fueling_hyd_alert_detail_deficit` / `_time`). Tokens `{deficit}`, `{elapsed}`, `{target}`. */
    val hydrationAlertCustomDetail: String = "",
    /** Beep pattern played when a hydration alert fires. OFF = visual only. */
    val hydBeepPattern: BeepPattern = BeepPattern.SINGLE_LONG,
    val drink1Label: String = "Sip",     val drink1Ml: Int = 100,    val drink1Color: Int = 0xFF1565C0.toInt(),    val drink1Icon: String = "💧",
    val drink2Label: String = "Bottle",  val drink2Ml: Int = 500,    val drink2Color: Int = 0xFF1565C0.toInt(),    val drink2Icon: String = FUEL_BOTTLE_DRAWABLE,

    /** Write per-second cumulative carbs (g) and hydration (ml) into the FIT file as
     *  developer fields, plus the totals into the session message. Default ON because
     *  the cost is negligible (~0.05% battery over 5 h, no perceptible CPU). Riders
     *  who don't want extra columns in their FIT can turn it off. */
    val fuelingFitExportEnabled: Boolean = true,
    /**
     * Config schema version — used to detect stale saved configs and apply migrations.
     * Default 0 ensures that any pre-versioning config (JSON without this field) triggers migration.
     * New installs also start at 0 and migrate on first load, which is a no-op for most presets.
     */
    val configVersion: Int = 0,
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
    val reasonEnum: EmergencyReason? = null,         // null on legacy reads; set by startCountdown
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

    /** Absolute deadline epoch-ms derived from persisted fields; 0 if not in a countdown. */
    fun countdownDeadlineMs(): Long =
        if (status == EmergencyStatus.COUNTDOWN && countdownStartTime > 0)
            countdownStartTime + countdownDurationSeconds * 1_000L
        else 0L
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
val defaultKSafeConfigJson: String = Json.encodeToString(listOf(KSafeConfig(configVersion = CONFIG_VERSION)))
val defaultEmergencyStateJson: String = Json.encodeToString(EmergencyState())

// ─── Wellness ride history (consumed by ReadinessDecision) ────────────────────

/**
 * Snapshot of one ride's wellness summary, persisted at the Recording → Idle transition.
 * Field set mirrors `WellnessMonitor.WellnessSummary` plus a wall-clock timestamp so
 * recency rules can be evaluated independently of the rider's timezone.
 */
@Serializable
data class RideWellnessRecord(
    val endedAtMs: Long = 0L,
    val maxHrBpm: Int = 0,
    val cumMsCriticalAbove: Long = 0L,
    val cumMsSustainedAbove: Long = 0L,
    val maxDriftPct: Float = 0f,
    val criticalFires: Int = 0,
    val sustainedFires: Int = 0,
    val decouplingFires: Int = 0,
) {
    val totalFires: Int get() = criticalFires + sustainedFires + decouplingFires
}

/**
 * Rolling history of the last 10 wellness summaries, newest first. Consumed by
 * `decideReadiness` to advise the rider at the start of the next ride.
 */
@Serializable
data class WellnessHistory(
    val records: List<RideWellnessRecord> = emptyList(),
) {
    /** Returns a new history with `record` prepended and trimmed to the most recent [MAX_SIZE]. */
    fun append(record: RideWellnessRecord): WellnessHistory =
        WellnessHistory(records = (listOf(record) + records).take(MAX_SIZE))

    companion object { const val MAX_SIZE = 10 }
}

val defaultWellnessHistoryJson: String = Json.encodeToString(WellnessHistory())

// ─── Config migration ─────────────────────────────────────────────────────────

/**
 * Migrates a deserialized [KSafeConfig] to the current schema version. Idempotent.
 *
 * The migrated config is returned in-memory; it is persisted the next time the user
 * saves settings (or any automatic config write), so no separate DataStore write is needed.
 *
 * ## v0 → v2 (current)
 * `minSpeedForCrashKmh` and `crashConfirmSpeedKmh` were originally added with generic Kotlin
 * defaults (10 and 5 respectively), not preset-specific values. A user who had LOW/HIGH preset
 * selected before this migration was introduced may still have those stale generic defaults
 * stored. This step replaces them with the correct canonical preset values **only if** the
 * stored value matches the old generic default — deliberate customisations are preserved.
 *
 * | Preset | minSpeed canonical | confirmSpeed canonical |
 * |--------|-------------------|----------------------|
 * | LOW    | 3 km/h            | 3 km/h               |
 * | MEDIUM | 10 km/h (= old default, no change) | 5 km/h (= old default, no change) |
 * | HIGH   | 15 km/h           | 5 km/h (= old default, no change) |
 */
fun KSafeConfig.migrateToLatest(): KSafeConfig {
    var c = this
    val originalVersion = c.configVersion

    if (c.configVersion < 2) {
        val preset = c.crashSensitivity
        if (preset != CrashSensitivity.CUSTOM) {
            val canonMin     = PRESET_MIN_SPEED[preset]     ?: c.minSpeedForCrashKmh
            val canonConfirm = PRESET_CONFIRM_SPEED[preset] ?: c.crashConfirmSpeedKmh
            // Only touch values that are still at the old generic defaults (10 / 5).
            // If the user had already customised them, leave them alone.
            val newMin     = if (c.minSpeedForCrashKmh == 10)  canonMin     else c.minSpeedForCrashKmh
            val newConfirm = if (c.crashConfirmSpeedKmh == 5) canonConfirm else c.crashConfirmSpeedKmh
            c = c.copy(minSpeedForCrashKmh = newMin, crashConfirmSpeedKmh = newConfirm, configVersion = 2)
        } else {
            c = c.copy(configVersion = 2)
        }
        if (c.minSpeedForCrashKmh != minSpeedForCrashKmh || c.crashConfirmSpeedKmh != crashConfirmSpeedKmh) {
            Timber.i(
                "KSafeConfig migrated v%d→v2: minSpeed %d→%d, confirmSpeed %d→%d (preset=%s)",
                originalVersion,
                minSpeedForCrashKmh, c.minSpeedForCrashKmh,
                crashConfirmSpeedKmh, c.crashConfirmSpeedKmh,
                preset
            )
        }
    }

    if (c.configVersion < 3) {
        // v2 → v3: new HR-based detector fields all carry safe defaults via the data class.
        // Only the version stamp needs updating; deserialization auto-fills missing fields.
        c = c.copy(configVersion = 3)
        Timber.i("KSafeConfig migrated v%d→v3 (medical/wellness fields added)", originalVersion)
    }

    if (c.configVersion < 4) {
        // v3 → v4: fueling tracker fields all carry safe defaults via the data class.
        // Only the version stamp needs updating; deserialization auto-fills missing fields.
        c = c.copy(configVersion = 4)
        Timber.i("KSafeConfig migrated v%d→v4 (fueling tracker fields added)", originalVersion)
    }

    if (c.configVersion < 5) {
        // v4 → v5: per-tracker time-alert initial delay + custom alert title fields added.
        // All carry safe defaults; pure version stamp.
        c = c.copy(configVersion = 5)
        Timber.i("KSafeConfig migrated v%d→v5 (initial delay + custom title)", originalVersion)
    }

    if (c.configVersion < 6) {
        // v5 → v6: wellnessUseMaxHrPercent + wellnessHighHrPercent. Defaults preserve previous
        // behaviour (UseMaxHrPercent = false → still uses the absolute wellnessHighHrThreshold).
        c = c.copy(configVersion = 6)
        Timber.i("KSafeConfig migrated v%d→v6 (wellness %% of max HR)", originalVersion)
    }

    if (c.configVersion < 7) {
        // v6 → v7: critical-HR + cardiac-decoupling tiers added to wellness. Sustained tier
        // unchanged; the new tiers default to enabled with conservative parameters.
        c = c.copy(configVersion = 7)
        Timber.i("KSafeConfig migrated v%d→v7 (wellness three-tier model)", originalVersion)
    }

    if (c.configVersion < 8) {
        // v7 → v8: EmergencyState.reasonEnum (EmergencyReason?) added. No data transform needed;
        // kotlinx.serialization + coerceInputValues = true fills missing field with null automatically.
        c = c.copy(configVersion = 8)
        Timber.i("KSafeConfig migrated v%d→v8 (EmergencyState reasonEnum added)", originalVersion)
    }

    if (c.configVersion < 9) {
        // v8 → v9: carbBeepPattern + hydBeepPattern added. Defaults to SINGLE_LONG so existing
        // riders hear exactly the same 880 Hz × 800 ms beep they had before. Pure version stamp;
        // coerceInputValues fills the missing enum field with its declared default.
        c = c.copy(configVersion = 9)
        Timber.i("KSafeConfig migrated v%d→v9 (carb/hyd beep pattern picker)", originalVersion)
    }

    if (c.configVersion < 10) {
        // v9 → v10: wellnessBeepPattern added for WARNING-level alerts. Same SINGLE_LONG
        // default → previous behaviour preserved. Emergency-level beeps stay hardcoded.
        c = c.copy(configVersion = 10)
        Timber.i("KSafeConfig migrated v%d→v10 (wellness beep pattern picker)", originalVersion)
    }

    return c
}

/**
 * Returns a copy of this config with empty alert-customisation fields pre-filled with their
 * current localised default strings. Used by the JSON export so the user sees the actual
 * default texts in the file and can edit them in place — easier than guessing what the
 * default looks like before changing it.
 *
 * Only customisation fields with an `ifBlank { context.getString(R.string.*) }` runtime
 * fall-back are materialised here. Fields with non-empty literal defaults (e.g.
 * `emergencyMessage`, `karooLiveStartMessage`, `customMessage`) already serialise visibly.
 *
 * After import the materialised strings are stored as literal field values. The runtime
 * fall-back code (`config.xxx.ifBlank { … }`) keeps working — the `ifBlank` branch is just
 * never taken because the field is no longer blank. Tradeoff: a user who imports an exported
 * config no longer picks up future changes to the default strings (e.g. translation updates,
 * typo fixes) for the materialised fields. That's the same situation as anyone who
 * explicitly customised the alert, which is the intent here.
 */
fun KSafeConfig.materializeAlertDefaults(context: Context): KSafeConfig = copy(
    // ── Fueling alerts ─────────────────────────────────────────────────────
    carbAlertCustomTitle =
        carbAlertCustomTitle.ifBlank { context.getString(R.string.fueling_carb_alert_title) },
    carbAlertCustomDetail =
        carbAlertCustomDetail.ifBlank { context.getString(R.string.fueling_carb_alert_detail_deficit) },
    hydrationAlertCustomTitle =
        hydrationAlertCustomTitle.ifBlank { context.getString(R.string.fueling_hyd_alert_title) },
    hydrationAlertCustomDetail =
        hydrationAlertCustomDetail.ifBlank { context.getString(R.string.fueling_hyd_alert_detail_deficit) },
    // ── Medical incidents ──────────────────────────────────────────────────
    medicalCustomTitle =
        medicalCustomTitle.ifBlank { context.getString(R.string.warning_medical_title) },
    medicalCustomDetail =
        medicalCustomDetail.ifBlank { context.getString(R.string.warning_medical_detail) },
    // ── Wellness, three tiers ──────────────────────────────────────────────
    wellnessSustainedCustomTitle =
        wellnessSustainedCustomTitle.ifBlank { context.getString(R.string.warning_wellness_high_hr_title) },
    wellnessSustainedCustomDetail =
        wellnessSustainedCustomDetail.ifBlank { context.getString(R.string.warning_wellness_high_hr_detail) },
    wellnessCriticalCustomTitle =
        wellnessCriticalCustomTitle.ifBlank { context.getString(R.string.warning_wellness_critical_hr_title) },
    wellnessCriticalCustomDetail =
        wellnessCriticalCustomDetail.ifBlank { context.getString(R.string.warning_wellness_critical_hr_detail) },
    wellnessDecouplingCustomTitle =
        wellnessDecouplingCustomTitle.ifBlank { context.getString(R.string.warning_wellness_decoupling_title) },
    wellnessDecouplingCustomDetail =
        wellnessDecouplingCustomDetail.ifBlank { context.getString(R.string.warning_wellness_decoupling_detail) },
)

