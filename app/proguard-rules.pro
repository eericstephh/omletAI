# ── JNI / native bridges (must never be obfuscated) ──────────────
-keep class com.aicreatorstudio.app.ai.WhisperNative { *; }
-keep class com.aicreatorstudio.app.ai.RnnoiseNative { *; }
-keep class com.aicreatorstudio.app.ai.whisper.** { *; }

# ── TensorFlow Lite JNI ─────────────────────────────────────────
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }
-keep class org.tensorflow.lite.support.** { *; }
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options$GpuBackend
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options

# ── MediaPipe Tasks Vision ───────────────────────────────────────
# 2026-10-22 — THE WHOLE MediaPipe TREE MUST BE KEPT, not just
# `tasks.**` and `framework.**`.
#
# The protobuf-generated message classes live in
# `com.google.mediapipe.proto`, `com.google.mediapipe.calculator.proto`,
# `com.google.mediapipe.formats.proto`, `com.google.mediapipe.util.proto`
# and `com.google.mediapipe.gpu` — and protobuf's runtime looks their
# fields up **BY NAME** (`platform_`, ...). R8 renamed them, and the
# release build then died inside `ImageSegmenter.createFromOptions` with
#
#   RuntimeException: Field platform_ for c9.i0 not found.
#   Known fields are [public static final c9.i0 c9.i0.e, ...]
#
# where `c9.i0` is `com.google.mediapipe.proto.MediaPipeLoggingProto$SystemInfo`
# — confirmed by reading the release R8 mapping. 85 protobuf classes
# across the five packages above were renamed, so this was not a
# one-class fluke: EVERY MediaPipe protobuf message was broken, and the
# first one the runtime happened to touch is the one that killed the
# feature.
#
# The symptom reached the user as "the AI engine could not run the
# model — close background apps and try again", which is not what
# happened.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.mediapipe.framework.** { *; }

# 2026-10-22 — the same failure mode for any protobuf message the app
# loads: protobuf's generated code resolves a message's fields by NAME
# at class-init time, so R8 must not rename them. `GeneratedMessageV3`
# is the base of every full-runtime message (MediaPipe's and TFLite
# Support's).
-keep class com.google.protobuf.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageV3 { <fields>; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
# The same hazard, in the one remaining `*.proto` package the release
# build still renames: Firebase's encoder messages. They are only used
# by the analytics transport, but the failure mode is identical, and a
# field keep is cheaper than a size regression.
-keepclassmembers class com.google.firebase.encoders.proto.** { <fields>; }
-dontwarn com.google.mediapipe.proto.**
-dontwarn com.google.mediapipe.framework.GraphProfiler
# 2026-10-05 — mediapipe 0.10.35 references the AutoValue @Memoized
# annotation from MPImageProperties.hashCode(); it is compile-time only.
-dontwarn com.google.auto.value.extension.memoized.**

# ── Google Flogger (MediaPipe's logging backend) ─────────────────
# 2026-10-23 — THE bug that made every MediaPipe feature fail on a
# real phone, including Background Remove.
#
# `com.google.mediapipe.framework.Graph.<clinit>` logs through Google
# Flogger. Flogger identifies its caller by **walking the stack trace
# and matching class names**; it is documented as requiring a keep
# rule, and without one R8 renames the Flogger classes and inlines the
# caller frames. The stack walk then finds nothing and Flogger throws:
#
#   IllegalStateException: no caller found on the stack for: j8.c
#
# `j8.c` is `com.google.common.flogger.FluentLogger` in the release
# R8 mapping, and the throw happens inside `Graph.<clinit>`, so the
# class-init failure surfaces as
#
#   ExceptionInInitializerError -> "MediaPipe default delegate failed;
#   retrying on CPU" -> `ImageSegmenter.createFromOptions` dies
#
# i.e. the segmenter can never be created, `isAvailable` stays false
# and the panel reports a model/engine problem that has nothing to do
# with the model. Keeping the Flogger tree (a handful of classes) is
# the documented fix and restores the stack walk.
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**

# ── Tesseract4Android OCR ────────────────────────────────────────
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
-dontwarn javax.lang.model.**

# ── Room database (keep entities + DAOs) ─────────────────────────
-keep class com.aicreatorstudio.app.db.** { *; }
-keep class com.aicreatorstudio.app.model.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# ── Media3 / ExoPlayer JNI (shaders, codecs) ────────────────────
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ── Hilt / Dagger ────────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# ── Kotlin serialization / reflection ────────────────────────────
-keepattributes *Annotation*, InnerClasses, EnclosingMethod
-keepattributes Signature, ExceptionAttribute
-keep class kotlin.Metadata { *; }
# 2026-10-04 — the Gson SerializedName keep rule was removed: there is no
# Gson dependency in the build (the app serialises with hand-rolled
# org.json in `db/EditorStateJson.kt`), so the rule could never match.

# ── Keep Compose @Stable / @Immutable classes for recomposition ──
-keepclassmembers class * {
    @androidx.compose.runtime.Stable <fields>;
    @androidx.compose.runtime.Immutable <fields>;
}

# ── Media3 shaders and effect classes (loaded reflectively) ──────
-keep class * extends androidx.media3.common.Effect { *; }
-keep class * implements androidx.media3.effect.RgbMatrix { *; }
-keep class * extends androidx.media3.effect.BaseGlShaderProgram { *; }
