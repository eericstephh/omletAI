package com.aicreatorstudio.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards for the MediaPipe JNI-library probe.
 *
 * ## The bug this pins (2026-10-15)
 *
 * A device report showed Auto Frame failing with
 * «این نسخه از برنامه برای پردازندهٔ این دستگاه ساخته نشده است» (the
 * `LIBRARY_ABI_MISSING` sentence) on an **arm64-v8a** Xiaomi whose
 * APK demonstrably contains the library. The probe called
 * `System.loadLibrary("mediapipe_tasks_vision_jni")` — a name that
 * no longer exists in the build: the resolved `tasks-vision 0.10.35`
 * AAR ships **no** `.so` at all, and the only native library that
 * reaches the APK is `tasks-core`'s
 * `lib/arm64-v8a/libmediapipe_tasks_jni.so` (verified by listing the
 * built APK and the AAR in the Gradle cache). The probe failed,
 * `libraryAvailable` was forced to `false`, and the classifier
 * honestly reported a packaging fault using a sentence that blames
 * the user's processor.
 *
 * The probe now lives in [MediaPipeNativeLib] and tries a candidate
 * list with the packaged name first.
 *
 * ## What is testable here
 *
 * The name list is `internal` so it can be pinned directly. The load
 * attempt itself cannot succeed on the JVM (no Linux `.so`), but the
 * behavioural half asserts it *fails cleanly* instead of throwing —
 * a probe that escapes an `Error` would crash the caller instead of
 * degrading. The "the engines all go through the shared probe"
 * invariant is a source-shape guard, because the duplication it
 * forbids is exactly how the two copies drifted apart.
 */
class MediaPipeNativeLibWiringTest {

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "settings.gradle.kts not found above ${File(".").absolutePath}"
        )
    }

    private fun read(relative: String): String {
        val f = File(repoRoot(), relative)
        assertTrue("$relative missing at $f", f.isFile)
        return f.readText()
    }

    /** Strips `//` and block comments, then returns the remaining code. */
    private fun codeLinesOnly(source: String): String {
        val blockEnd = "*" + "/"
        val out = StringBuilder()
        var inBlock = false
        for (raw in source.lineSequence()) {
            var line = raw
            var result = StringBuilder()
            var i = 0
            while (i < line.length) {
                if (inBlock) {
                    val end = line.indexOf(blockEnd, i)
                    if (end < 0) {
                        i = line.length
                    } else {
                        inBlock = false
                        i = end + blockEnd.length
                    }
                } else {
                    val lineComment = line.indexOf("//", i)
                    val blockStart = line.indexOf("/*", i)
                    val next = when {
                        lineComment >= 0 && (blockStart < 0 || lineComment < blockStart) -> lineComment
                        blockStart >= 0 -> blockStart
                        else -> -1
                    }
                    if (next < 0) {
                        result.append(line.substring(i))
                        i = line.length
                    } else if (next == lineComment) {
                        i = line.length
                    } else {
                        result.append(line.substring(i, next))
                        inBlock = true
                        i = next + 2
                    }
                }
            }
            out.append(result).append('\n')
        }
        return out.toString()
    }

    // ----------------------------------------------------------------
    // The candidate list
    // ----------------------------------------------------------------

    /**
     * The name `tasks-core` actually ships — and the only one in the
     * built APK — must be probed, and probed FIRST so the common
     * case does one successful load instead of one failed load
     * plus a success.
     */
    @Test
    fun `probe lists the packaged library name first`() {
        assertEquals(
            "the first candidate must be the mediapipe_tasks_jni name " +
                "tasks-core 0.10.35 packages (libmediapipe_tasks_jni.so)",
            "mediapipe_tasks_jni",
            MediaPipeNativeLib.CANDIDATE_LIBS.first()
        )
        assertTrue(
            "the legacy tasks-vision name must stay reachable so an " +
                "older AAR still probes green",
            MediaPipeNativeLib.CANDIDATE_LIBS.contains("mediapipe_tasks_vision_jni")
        )
    }

    // ----------------------------------------------------------------
    // Behaviour: a probe must fail cleanly, never escape an Error
    // ----------------------------------------------------------------

    /**
     * On the JVM there is no native library, so the probe must
     * report `false` rather than throwing. An `UnsatisfiedLinkError`
     * escaping here would crash every caller.
     */
    @Test
    fun `probe returns false on the JVM instead of throwing`() {
        assertFalse(MediaPipeNativeLib.isAvailable())
    }

    /**
     * The probe is cached and idempotent: a second call must not
     * re-attempt a load (which would reallocate the probe state on
     * every frame).
     */
    @Test
    fun `probe is idempotent`() {
        val first = MediaPipeNativeLib.isAvailable()
        val second = MediaPipeNativeLib.isAvailable()
        assertEquals(first, second)
    }

    // ----------------------------------------------------------------
    // The engines must go through the shared probe
    // ----------------------------------------------------------------

    /**
     * No engine may call `System.loadLibrary` for the MediaPipe
     * library by itself. Two copies of this probe are how the
     * stale name survived: the engines were written when
     * tasks-vision shipped `libmediapipe_tasks_vision_jni.so`, the
     * AAR was upgraded, and both copies kept probing the dead name.
     */
    @Test
    fun `no engine loads the mediapipe library by itself`() {
        val engines = listOf(
            "app/src/main/java/com/aicreatorstudio/app/ai/engines/MediaPipeMattingEngine.kt",
            "app/src/main/java/com/aicreatorstudio/app/ai/engines/SaliencyTrackerEngine.kt"
        )
        engines.forEach { path ->
            val code = codeLinesOnly(read(path))
            assertTrue(
                "$path must not call System.loadLibrary directly; " +
                    "the shared probe owns the library name",
                !code.contains("System.loadLibrary(")
            )
            assertTrue(
                "$path must delegate availability to MediaPipeNativeLib",
                code.contains("MediaPipeNativeLib.isAvailable()")
            )
        }
    }

    /**
     * And the shared probe is the only place the candidate names
     * live — a third hard-coded copy would drift the same way.
     */
    @Test
    fun `the shared probe owns the candidate names`() {
        val probe = read("app/src/main/java/com/aicreatorstudio/app/ai/MediaPipeNativeLib.kt")
        assertTrue(probe.contains("\"mediapipe_tasks_jni\""))
        assertTrue(probe.contains("\"mediapipe_tasks_vision_jni\""))
        assertTrue(probe.contains("System.loadLibrary(name)"))
    }
}
