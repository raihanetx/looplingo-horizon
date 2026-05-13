# ══════════════════════════════════════════════════════════════════════
# LoopLingo Horizon — ProGuard Rules
# ══════════════════════════════════════════════════════════════════════

# ── Media3 (ExoPlayer successor) ────────────────────────────────────
# Media3 includes consumer ProGuard rules in its AAR.
# Only add rules for reflection-based access we directly use.
-keep class androidx.media3.exoplayer.source.MediaSource { *; }
-keep class androidx.media3.exoplayer.extractor.ExtractorsFactory { *; }
-keep class androidx.media3.common.Player$Listener { *; }
-dontwarn androidx.media3.**

# ── Room ───────────────────────────────────────────────────────────────
# Room uses annotation processing to generate DAO implementations.
# Keep the Room database class and its DAO methods.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class *
-keepclassmembers class * {
    @androidx.room.Query <methods>;
    @androidx.room.Insert <methods>;
    @androidx.room.Delete <methods>;
    @androidx.room.Update <methods>;
    @androidx.room.RawQuery <methods>;
}

# ── Hilt / Dagger ──────────────────────────────────────────────────────
# Hilt generates code at compile time. These are safety rules only;
# the library includes its own consumer ProGuard rules.
-dontwarn dagger.hilt.**
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper

# ── Kotlin Coroutines ──────────────────────────────────────────────────
# Keep dispatcher factory names for proper Main dispatcher resolution.
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ── Timber ─────────────────────────────────────────────────────────────
# Strip debug/verbose/info logging in release builds.
# Keep warning and error logs for crash diagnosis in production.
-assumenosideeffects class timber.log.Timber {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# ── AndroidX / Lifecycle ───────────────────────────────────────────────
# Lifecycle ViewModel uses reflection for @HiltViewModel-annotated classes.
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# ── Navigation Component ───────────────────────────────────────────────
# Safe Args generates argument classes; keep NavHostFragment for reflection.
-keepnames class androidx.navigation.fragment.NavHostFragment

# ── Media3 Session (narrowed — only what we use) ──────────────────────
-keep class androidx.media3.session.MediaSession { *; }
-keep class androidx.media3.session.MediaSession$* { *; }
-keep class androidx.media3.session.MediaSession$Callback { *; }
-keep class androidx.media3.session.MediaSession$ConnectionResult { *; }
-keep class androidx.media3.session.MediaSession$ControllerInfo { *; }

# ── LoopLingo Entities (Room-managed) ──────────────────────────────────
# Only keep Entity-annotated classes; Room needs their fields for mapping.
-keep @androidx.room.Entity class com.looplingo.horizon.data.entity.** { *; }

# ── General Android Rules (narrowed to our app only) ──────────────────
# Keep only OUR Service/Activity/Application — not every class in the classpath
-keep public class com.looplingo.horizon.** extends android.app.Service
-keep public class com.looplingo.horizon.** extends android.app.Activity
-keep public class com.looplingo.horizon.** extends android.app.Application

# Keep View constructors (used by XML inflation via reflection)
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Remove android.util.Log calls in release builds
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
}

# ── Gson models used by GroqApiClient ─────────────────────────────────
# These private data classes are used for JSON serialization/deserialization
# via Gson. Without these rules, R8/ProGuard will strip/rename their fields
# in release builds, breaking JSON parsing at runtime.
-keepclassmembers class com.looplingo.horizon.api.GroqApiClient$Segment { *; }
-keepclassmembers class com.looplingo.horizon.api.GroqApiClient$TranscriptionResponse { *; }
-keepclassmembers class com.looplingo.horizon.api.GroqApiClient$SegmentJson { *; }
-keepclassmembers class com.looplingo.horizon.api.GroqApiClient$ErrorJson { *; }
-keepclassmembers class com.looplingo.horizon.api.GroqApiClient$AudioChunk { *; }
-keepclassmembers class com.looplingo.horizon.api.GroqApiClient$ChunkResult { *; }
-keepclassmembers class com.looplingo.horizon.api.GroqApiClient$PcmAnalysisResult { *; }

# Keep SerializedName annotations
-keepattributes *Annotation*

# Gson specific rules
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
