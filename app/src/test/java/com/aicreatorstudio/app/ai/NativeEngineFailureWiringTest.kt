package com.aicreatorstudio.app.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-13 — regression guard for the misleading
 * "your processor is not supported" message.
 *
 * ## Why a source-shape test
 *
 * The defect was a *string* that six unrelated call sites emitted. A
 * behaviour test cannot reach those sites (they need a `Context`, a
 * loaded `.so`, and an assembled MediaPipe task), so the property that
 * actually regressed is asserted statically: **no native-load failure
 * path may hard-code a CPU-incompatibility claim**. Every one of them
 * must route through [NativeEngineFailure.classify], which is fully
 * covered behaviourally by [NativeEngineFailureTest].
 *
 * This mirrors the existing [WhisperFailurePropagationTest] and
 * [WhisperNativeNoSilentSwallowTest] approach and is honest about its
 * limits: it proves the wiring, not that inference works.
 */
class NativeEngineFailureWiringTest {

    private val misleadingSentence = "این دستگاه از پردازنده‌ی لازم پشتیبانی نمی‌کند"

    private fun locate(relative: String): File? {
        var dir: File? = File(".").absoluteFile
        repeat(8) {
            if (dir?.resolve("settings.gradle.kts")?.isFile == true) {
                val f = dir.resolve(relative)
                if (f.isFile) return f
            }
            dir = dir?.parentFile
        }
        val direct = File(relative)
        return if (direct.isFile) direct else null
    }

    private fun read(relative: String): String {
        val f = locate(relative)
            ?: error("$relative not found from cwd ${File(".").absolutePath}")
        return f.readText()
    }

    /**
     * Absolute directory of the repository root (the folder holding
     * `settings.gradle.kts`). `locate` returns a `File?` and the tree
     * walk needs a non-null root, so this resolves it once and fails
     * loudly if the working directory is wrong.
     */
    private fun repoRoot(): File {
        val settings = locate("settings.gradle.kts")
            ?: error("settings.gradle.kts not found from cwd ${File(".").absolutePath}")
        return settings.parentFile
            ?: error("settings.gradle.kts has no parent directory")
    }

    /**
     * Strip line comments and block comments from Kotlin source, keeping
     * only the executable lines.
     *
     * The guard must look at *string literals*, not prose: the fix is
     * legitimately documented in KDoc (which quotes the removed
     * sentence to explain why it was removed), and a naive
     * whole-file `contains` would flag that documentation as a
     * violation — which is exactly what happened on the first run of
     * this test.
     *
     * A line that begins with an asterisk after trimming, or with two
     * slashes, is a comment line and is dropped. Trailing comments
     * after code are left in place, which is safe: they cannot contain
     * a Kotlin string literal that the compiler would emit to the UI.
     */
    private fun codeLinesOnly(source: String): List<String> {
        val out = mutableListOf<String>()
        val blockEnd = "*" + "/"
        val blockStart = "/" + "*"
        var inBlock = false
        for (raw in source.lines()) {
            val line = raw.trim()
            if (inBlock) {
                if (line.contains(blockEnd)) inBlock = false
                continue
            }
            if (line.startsWith(blockStart)) {
                if (!line.contains(blockEnd)) inBlock = true
                continue
            }
            if (line.startsWith("*") || line.startsWith("//")) continue
            out += raw
        }
        return out
    }

    /** True when any *non-comment* line of [source] contains [needle]. */
    private fun codeContains(source: String, needle: String): Boolean =
        codeLinesOnly(source).any { it.contains(needle) }

    private val whisperEngine =
        "app/src/main/java/com/aicreatorstudio/app/ai/WhisperEngine.kt"
    private val aiFailure = "app/src/main/java/com/aicreatorstudio/app/ai/AiFailure.kt"
    private val matting =
        "app/src/main/java/com/aicreatorstudio/app/ai/engines/MediaPipeMattingEngine.kt"
    private val saliency =
        "app/src/main/java/com/aicreatorstudio/app/ai/engines/SaliencyTrackerEngine.kt"

    // ---- the misleading sentence is gone from every producer -----------

    @Test
    fun `the removed cpu-blame sentence appears in no production source file`() {
        // Walk the whole main source tree. If the sentence comes back
        // in an executable line anywhere, a user can be told their
        // processor is unsupported for a reason we have not evidenced.
        //
        // Comment lines are skipped on purpose: the KDoc that explains
        // this very fix quotes the removed sentence, and flagging that
        // documentation would be a false positive.
        val root = repoRoot().resolve("app/src/main/java/com/aicreatorstudio/app")
        if (!root.isDirectory) {
            error("main source root not found at ${root.absolutePath}")
        }
        val offenders = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                if (codeContains(file.readText(), misleadingSentence)) {
                    offenders += file.relativeTo(repoRoot())
                        .path.replace('\\', '/')
                }
            }
        assertTrue(
            "The generic CPU-blame sentence is still emitted from: $offenders. " +
                "Every native-load failure must route through " +
                "NativeEngineFailure.classify instead.",
            offenders.isEmpty()
        )
    }

    @Test
    fun `AiFailure ENGINE_UNAVAILABLE no longer asserts a hardware cause`() {
        val source = read(aiFailure)
        assertFalse(
            "AiFailure.ENGINE_UNAVAILABLE must not claim the CPU is " +
                "unsupported — it has no evidence for that.",
            codeContains(source, misleadingSentence)
        )
    }

    // ---- every native-load path classifies -----------------------------

    @Test
    fun `WhisperEngine classifies before reporting a native failure`() {
        val source = read(whisperEngine)
        assertTrue(
            "WhisperEngine must use NativeEngineFailure.classify on the " +
                "native-load path",
            source.contains("NativeEngineFailure.classify(")
        )
        // The library-unavailable branch must carry the classified cause,
        // not the generic stage sentence.
        val branch = source.indexOf("InitResult.LibraryUnavailable ->")
        assertTrue("expected the LibraryUnavailable branch", branch >= 0)
        val body = source.substring(branch, minOf(branch + 900, source.length))
        assertTrue(
            "The LibraryUnavailable branch must report the classified " +
                "cause. Got:\n$body",
            body.contains("NativeEngineFailure.classify(")
        )
        assertFalse(
            "The LibraryUnavailable branch must not fall back to the " +
                "generic stage sentence. Got:\n$body",
            body.contains("persianFor(WhisperDiagnostics.Stage.NATIVE_SELFTEST)")
        )
    }

    @Test
    fun `WhisperEngine records a classified failure for later UI use`() {
        val source = read(whisperEngine)
        assertTrue(
            "WhisperEngine must expose the classified failure so the " +
                "ViewModel can render a specific message",
            source.contains("lastNativeFailure")
        )
    }

    @Test
    fun `native returned null is split into memory and init causes`() {
        val source = read(whisperEngine)
        val idx = source.indexOf("NativeReturnedNull ->")
        assertTrue(idx >= 0)
        val body = source.substring(idx, minOf(idx + 1600, source.length))
        assertTrue(
            "A null native context with a byte-exact file must be " +
                "attributable to either memory pressure or a native init " +
                "failure. Got:\n$body",
            body.contains("NativeEngineFailure.INSUFFICIENT_MEMORY") &&
                body.contains("NativeEngineFailure.NATIVE_INIT_FAILED")
        )
    }

    @Test
    fun `matting engine classifies both the probe failure and the init throwable`() {
        val source = read(matting)
        val classifications =
            Regex("""NativeEngineFailure\.classify\(""").findAll(source).count()
        assertTrue(
            "MediaPipeMattingEngine must classify on BOTH the missing-library " +
                "probe and the createFromOptions throwable. Found " +
                "$classifications call(s).",
            classifications >= 2
        )
        assertFalse(
            "MediaPipeMattingEngine must not reuse the generic " +
                "ENGINE_UNAVAILABLE sentence",
            source.contains("AiFailure.ENGINE_UNAVAILABLE.persianMessage()")
        )
    }

    @Test
    fun `saliency engine classifies both the probe failure and the init throwable`() {
        val source = read(saliency)
        val classifications =
            Regex("""NativeEngineFailure\.classify\(""").findAll(source).count()
        assertTrue(
            "SaliencyTrackerEngine must classify on BOTH the missing-library " +
                "probe and the createFromOptions throwable. Found " +
                "$classifications call(s).",
            classifications >= 2
        )
        assertFalse(
            "SaliencyTrackerEngine must not reuse the generic " +
                "ENGINE_UNAVAILABLE sentence",
            source.contains("AiFailure.ENGINE_UNAVAILABLE.persianMessage()")
        )
    }

    @Test
    fun `mediapipe engines no longer surface a raw dlopen message to the user`() {
        // `lastLoadError = t.message` rendered a raw dlopen string in the
        // card. That must be replaced by a classified Persian sentence.
        val matting2 = read(matting)
        val saliency2 = read(saliency)
        assertFalse(
            "MediaPipeMattingEngine must not assign the raw throwable " +
                "message to lastLoadError",
            matting2.contains("lastLoadError = t.message")
        )
        assertFalse(
            "SaliencyTrackerEngine must not assign the raw throwable " +
                "message to lastLoadError",
            saliency2.contains("lastLoadError = t.message")
        )
    }

    @Test
    fun `the ai package exposes exactly one packaged-abi declaration`() {
        // Two disagreeing ABI lists is how the 2026-10-11 bug happened
        // (DeviceCapabilityDetector said three ABIs, the build shipped
        // one). NativeEngineFailure.PACKAGED_ABIS is the new authority;
        // DeviceCapabilityDetector.SUPPORTED_ABIS must agree with it.
        val classifier = read(
            "app/src/main/java/com/aicreatorstudio/app/ai/NativeEngineFailure.kt"
        )
        val detector = read(
            "app/src/main/java/com/aicreatorstudio/app/ai/DeviceCapabilityDetector.kt"
        )
        assertTrue(
            "NativeEngineFailure must declare the packaged ABIs",
            classifier.contains("PACKAGED_ABIS")
        )
        assertTrue(
            "DeviceCapabilityDetector must still declare its advisory set",
            detector.contains("SUPPORTED_ABIS")
        )
        // Both must contain exactly one ABI literal, and it must be arm64-v8a.
        assertTrue(
            "NativeEngineFailure.PACKAGED_ABIS must be arm64-v8a only",
            classifier.contains("""setOf("arm64-v8a")""")
        )
        assertTrue(
            "DeviceCapabilityDetector.SUPPORTED_ABIS must be arm64-v8a only",
            detector.contains("""setOf("arm64-v8a")""")
        )
    }
}
