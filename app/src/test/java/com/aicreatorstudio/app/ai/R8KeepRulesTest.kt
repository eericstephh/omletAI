package com.aicreatorstudio.app.ai

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-22 — the R8 keep rules that the AI engines depend on.
 *
 * ## The bug this guards
 *
 * The release build died inside `ImageSegmenter.createFromOptions` with
 *
 * ```
 * RuntimeException: Field platform_ for c9.i0 not found.
 *   Known fields are [public static final c9.i0 c9.i0.e,
 *                     public static volatile com.google.protobuf.h0 c9.i0.f]
 * ```
 *
 * `c9.i0` is `com.google.mediapipe.proto.MediaPipeLoggingProto$SystemInfo`
 * — read out of the release R8 mapping. The cause: the project's
 * ProGuard file kept `com.google.mediapipe.tasks.**` and
 * `com.google.mediapipe.framework.**` but **not** the protobuf
 * packages, so R8 renamed the fields of MediaPipe's generated
 * messages — and protobuf's runtime resolves a message's fields **by
 * name**. 85 classes across five packages were renamed, so every
 * MediaPipe protobuf message was broken, not just the one that
 * happened to be touched first.
 *
 * The user-visible symptom was "the AI engine could not run the model
 * — close background apps and try again", which is not what happened.
 *
 * ## Why a source-shape test
 *
 * The rule that matters is about the *built artefact* (whether a class
 * ends up renamed), and that can only be checked against the release
 * mapping — which needs a release build. This test pins the rule
 * itself, so the intent cannot be lost by an edit that "tidies" the
 * file; the mapping is checked separately by
 * `ReleaseMappingGuardTest`, when a release build exists.
 */
class R8KeepRulesTest {

    private fun repoRoot(): File {
        var dir: File = File(".").absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: throw IllegalStateException("settings.gradle.kts not found")
        }
    }

    private fun proguard(): String =
        File(repoRoot(), "app/proguard-rules.pro").readText()

    @Test
    fun `the whole mediapipe tree is kept`() {
        val rules = proguard()
        assertTrue(
            "the whole MediaPipe tree must be kept — the protobuf " +
                "packages live outside `tasks.**` and `framework.**` and " +
                "their field names must survive R8",
            rules.contains("-keep class com.google.mediapipe.** { *; }")
        )
        assertTrue(
            "the narrow `tasks.**` rule must not be the only one — it " +
                "leaves the protobuf packages obfuscated",
            !rules.contains("-keep class com.google.mediapipe.tasks.** { *; }")
        )
    }

    @Test
    fun `the protobuf runtime and its message fields are kept`() {
        val rules = proguard()
        assertTrue(
            "protobuf's runtime must be kept",
            rules.contains("-keep class com.google.protobuf.** { *; }")
        )
        assertTrue(
            "a full-runtime message's fields must not be renamed",
            rules.contains(
                "-keepclassmembers class * extends " +
                    "com.google.protobuf.GeneratedMessageV3 { <fields>; }"
            )
        )
        assertTrue(
            "a lite message's fields must not be renamed",
            rules.contains(
                "-keepclassmembers class * extends " +
                    "com.google.protobuf.GeneratedMessageLite { <fields>; }"
            )
        )
    }

    /**
     * 2026-10-23 — the second half of the same release-only failure.
     *
     * Once the protobuf fields survived, the next class-init in the
     * same call chain died: `com.google.mediapipe.framework.Graph.<clinit>`
     * logs through Google Flogger, and Flogger finds its caller by
     * walking the stack and matching class names. R8 renamed
     * `com.google.common.flogger.FluentLogger` to `j8.c` and inlined
     * the frames, so the walk threw
     *
     * ```
     * IllegalStateException: no caller found on the stack for: j8.c
     * ```
     *
     * which surfaced as `ExceptionInInitializerError` from
     * `TaskRunner.create` — every MediaPipe feature failed, and the
     * panel implied the model was at fault. Flogger's own
     * documentation asks for this keep rule.
     */
    @Test
    fun `google flogger is kept so its stack walk can find the caller`() {
        val rules = proguard()
        assertTrue(
            "Flogger resolves its caller by class name off the stack; " +
                "without a keep rule R8 renames it and MediaPipe's " +
                "Graph.<clinit> throws `no caller found on the stack`",
            rules.contains("-keep class com.google.common.flogger.** { *; }")
        )
        assertTrue(
            "Flogger references optional backends that are absent here",
            rules.contains("-dontwarn com.google.common.flogger.**")
        )
    }

    @Test
    fun `the other engines' keep rules are still present`() {
        val rules = proguard()
        // The other AI engines have their own native bridges; dropping
        // any of these rules breaks them in the same way.
        for (rule in listOf(
            "-keep class org.tensorflow.lite.** { *; }",
            "-keep class com.googlecode.tesseract.android.** { *; }",
            "-keep class androidx.media3.** { *; }",
            "-keep class com.aicreatorstudio.app.ai.whisper.** { *; }"
        )) {
            assertTrue("missing keep rule: $rule", rules.contains(rule))
        }
    }
}
