# ─── App entry points declared in AndroidManifest ────────────────────────────
-keep class com.enderthor.kSafe.KSafeApplication { *; }
-keep class com.enderthor.kSafe.extension.KSafeExtension { *; }
-keep class com.enderthor.kSafe.activity.MainActivity { *; }
-keep class com.enderthor.kSafe.activity.CancelEmergencyActivity { *; }
-keep class com.enderthor.kSafe.activity.FieldTapReceiver { *; }

# ─── DataTypes registered via extension_info.xml ─────────────────────────────
# All 16 DataTypes declared in res/xml/extension_info.xml (SOS, Safety Timer,
# Custom Message ×3, Webhook ×2, Carb Log ×3, Carb Status, Carb Burn Rate,
# Carbs Burned, Hydration Log ×2, Hydration Status) plus the shared state
# objects and Glance ActionCallback subclasses living in the same package.
# The wildcard covers slot variants so adding a new slot or a new sibling
# DataType in this package does not require a new rule.
-keep class com.enderthor.kSafe.datatype.** { *; }

# ─── Glance ActionCallbacks (referenced by class name via actionRunCallback) ──
-keep class * extends androidx.glance.appwidget.action.ActionCallback { *; }

# ─── Timber — strip VERBOSE/DEBUG/INFO calls entirely in release ──────────────
# R8 removes the call AND its arguments (including string construction) from
# bytecode. WARN and ERROR are kept — they reach the release tree in Application.
-assumenosideeffects class timber.log.Timber {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}

# ─── Kotlinx Serialization ────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
# Keep serializable data classes and their generated $$serializer companions
-keep @kotlinx.serialization.Serializable class com.enderthor.kSafe.** {
    <fields>;
    *** Companion;
}
-keep class com.enderthor.kSafe.**$$serializer { *; }
-dontwarn kotlinx.serialization.**

# Standard enum rule — kotlinx.serialization's auto-generated enum serializers and the
# `coerceInputValues = true` fallback (Json instance in extension/Extensions.kt) both
# need these to map JSON strings to enum constants and to enumerate valid values when
# coercing an unknown input back to the constructor default. Applies project-wide rather
# than just to com.enderthor.kSafe.** so SDK enums passed across IPC are also safe.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ─── Hammerhead karoo-ext — AIDL stubs (IPC with Karoo system) ───────────────
# Small package (~5 interfaces), safe to keep entirely
-keep class io.hammerhead.karooext.aidl.** { *; }

# ─── Hammerhead karoo-ext — base classes our code extends ────────────────────
-keep class io.hammerhead.karooext.extension.KarooExtension { *; }
-keep class io.hammerhead.karooext.extension.DataTypeImpl { *; }
-keep class io.hammerhead.karooext.KarooSystemService { *; }
-keep class io.hammerhead.karooext.internal.ViewEmitter { *; }

# ─── Hammerhead karoo-ext — models used in dispatch / addConsumer calls ───────
# Keep field names and constructors (needed for IPC serialization/reflection)
# but allow R8 to remove unused model classes
-keepnames class io.hammerhead.karooext.models.** { }
-keepclassmembers class io.hammerhead.karooext.models.** {
    <fields>;
    <init>(...);
}

# ─── Kotlin coroutines ────────────────────────────────────────────────────────
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**
-dontwarn org.jetbrains.annotations.**
