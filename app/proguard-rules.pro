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

# ── Keep Exception class names readable (for error reporting) ──────────
-keepnames class * extends java.lang.Exception

# ── VAD Engine (silence midpoint detection — no neural network) ─────────
-keepclassmembers class com.looplingo.horizon.vad.VadEngine$RefinedSegment { *; }

# ── Jetpack Compose ─────────────────────────────────────────────────────
# Compose uses runtime reflection for certain operations.
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Keep Compose runtime
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class androidx.compose.runtime.** { *; }

# Keep Compose UI
-keep class androidx.compose.ui.** { *; }
-keepclassmembers class androidx.compose.ui.** { *; }

# Keep Compose Material3
-keep class androidx.compose.material3.** { *; }
-keepclassmembers class androidx.compose.material3.** { *; }

# Keep Compose Navigation
-keep class androidx.navigation.compose.** { *; }
-keepclassmembers class androidx.navigation.compose.** { *; }

# Keep Hilt Navigation Compose
-keep class androidx.hilt.navigation.compose.** { *; }
-keepclassmembers class androidx.hilt.navigation.compose.** { *; }

# Keep Compose ViewModels
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class * extends androidx.lifecycle.AndroidViewModel { *; }

# Keep all ViewModels in our app
-keep class com.looplingo.horizon.ui.**ViewModel { *; }
-keepclassmembers class com.looplingo.horizon.ui.**ViewModel { *; }

# Keep Composable functions (they're used at runtime)
-keep @androidx.compose.runtime.Composable class * { *; }
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# Keep UiState data classes (used for state management)
-keep class com.looplingo.horizon.ui.**UiState { *; }
-keepclassmembers class com.looplingo.horizon.ui.**UiState { *; }

# Keep Entity data classes
-keep class com.looplingo.horizon.data.local.entity.** { *; }
-keepclassmembers class com.looplingo.horizon.data.local.entity.** { *; }

# Keep Domain model classes
-keep class com.looplingo.horizon.domain.model.** { *; }
-keepclassmembers class com.looplingo.horizon.domain.model.** { *; }
