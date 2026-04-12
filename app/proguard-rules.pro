# ─── App entry points declared in AndroidManifest ────────────────────────────
-keep class com.enderthor.kSafe.KSafeApplication { *; }
-keep class com.enderthor.kSafe.extension.KSafeExtension { *; }
-keep class com.enderthor.kSafe.activity.MainActivity { *; }
-keep class com.enderthor.kSafe.activity.CancelEmergencyActivity { *; }
-keep class com.enderthor.kSafe.datatype.SOSDataType { *; }
-keep class com.enderthor.kSafe.datatype.SafetyTimerDataType { *; }

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
