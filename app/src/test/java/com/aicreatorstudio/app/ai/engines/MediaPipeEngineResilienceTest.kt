package com.aicreatorstudio.app.ai.engines

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-20 — guards the fixes for a real device report.
 *
 * The report came from a Xiaomi 2107113SG (Android 14) where
 * Background Remove and Auto Frame failed with:
 *
 * ```
 * Model integrity check failed: موتور هوش مصنوعی نتوانست مدل را اجرا کرد...
 * ```
 *
 * Three separate defects were behind that one line:
 *
 *  1. **The model was never the problem.** The bundled asset's SHA-256
 *     matches the lockfile byte-for-byte, and the installer SUCCEEDED
 *     — the failure was MediaPipe's `createFromOptions` throwing. The
 *     status line said "integrity check failed" anyway.
 *  2. **The real cause was discarded.** The classification produced a
 *     one-line sentence for the user ("close background apps and try
 *     again") and the original exception was thrown away, so the
 *     report carried no technical content at all.
 *  3. **There was no fallback.** MediaPipe's default delegate needs a
 *     working OpenGL ES context, which some devices cannot provide;
 *     the CPU delegate has no such dependency.
 */
class MediaPipeEngineResilienceTest {

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("settings.gradle.kts not found above ${File(".").absolutePath}")
    }

    private fun source(relative: String): String {
        val f = File(repoRoot(), relative)
        assertTrue("missing file: $f", f.isFile)
        return f.readText()
    }

    @Test
    fun `the matting engine retries on the cpu delegate`() {
        val src = source(
            "app/src/main/java/com/aicreatorstudio/app/ai/engines/MediaPipeMattingEngine.kt"
        )
        assertTrue(
            "the segmenter must be retried on the CPU delegate when the default one " +
                "cannot initialise",
            src.contains("buildSegmenterOptions(modelPath, delegate = Delegate.CPU)")
        )
        assertTrue(
            "the delegate must be settable",
            src.contains("base.setDelegate(delegate)")
        )
    }

    @Test
    fun `the pose engine retries on the cpu delegate`() {
        val src = source(
            "app/src/main/java/com/aicreatorstudio/app/ai/engines/SaliencyTrackerEngine.kt"
        )
        assertTrue(
            "the landmarker must be retried on the CPU delegate",
            src.contains("buildPoseOptions(modelPath, delegate = Delegate.CPU)")
        )
        assertTrue(
            "the delegate must be settable",
            src.contains("base.setDelegate(delegate)")
        )
    }

    @Test
    fun `the technical cause is kept, not just the classified sentence`() {
        for (file in listOf(
            "app/src/main/java/com/aicreatorstudio/app/ai/engines/MediaPipeMattingEngine.kt",
            "app/src/main/java/com/aicreatorstudio/app/ai/engines/SaliencyTrackerEngine.kt"
        )) {
            val src = source(file)
            assertTrue(
                "$file must keep the raw exception next to the classified sentence",
                src.contains("lastLoadErrorDetail")
            )
            assertTrue(
                "$file must record the failure description",
                src.contains("describeFailure(t)")
            )
        }
    }

    @Test
    fun `the status line no longer blames the model integrity`() {
        for (file in listOf(
            "app/src/main/java/com/aicreatorstudio/app/ai/engines/MediaPipeMattingEngine.kt",
            "app/src/main/java/com/aicreatorstudio/app/ai/engines/SaliencyTrackerEngine.kt"
        )) {
            val src = source(file)
            assertTrue(
                "$file must not claim an integrity failure — the asset is verified " +
                    "byte-for-byte and the fault is the engine initialisation",
                !src.contains("Model integrity check failed")
            )
        }
    }

    @Test
    fun `a missing model file is reported as a missing file`() {
        val src = source(
            "app/src/main/java/com/aicreatorstudio/app/ai/engines/MediaPipeMattingEngine.kt"
        )
        assertTrue(
            "the model file's existence must be checked before MediaPipe is asked to " +
                "load it — MediaPipe reports a missing file as an opaque init error",
            src.contains("!modelFile.exists()")
        )
    }
}
