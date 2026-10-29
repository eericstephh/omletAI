package com.aicreatorstudio.app.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-11 Whisper compatibility investigation — §6 test that a
 * native failure cannot be flattened into a generic transcription
 * failure on its way to the UI.
 *
 * ## Why a source-shape test
 *
 * The behaviour under test lives in `WhisperEngine.generateTranscription`
 * and `initializeModelFor`, both of which require a live `Context`, a
 * coroutine dispatcher, a `WhisperNative` object with a real loaded
 * `.so`, and a decoded PCM buffer. None of that exists in a JVM unit
 * test, and this project's test stack deliberately has no mocking
 * framework.
 *
 * The property that actually matters — and that regressed before — is
 * **ordering**: the `catch (e: WhisperFailure)` arm must appear BEFORE
 * the generic `catch (e: Exception)` arm. Kotlin picks the first
 * matching arm, so if the generic arm comes first the precise stage is
 * lost and the user gets the old "the file may be corrupt, re-download
 * 465 MB" sentence for what may be a memory problem.
 *
 * That ordering is a static property of the source, so it is asserted
 * statically. This mirrors the existing
 * [WhisperNativeNoSilentSwallowTest] approach, and it is honest about
 * what it is: the test cannot prove inference works, only that the
 * error path is wired in the right order.
 */
class WhisperFailurePropagationTest {

    private fun engineSource(): String {
        val file = locate("app/src/main/java/com/aicreatorstudio/app/ai/WhisperEngine.kt")
            ?: error("WhisperEngine.kt not found from cwd ${File(".").absolutePath}")
        return file.readText()
    }

    @Test
    fun `WhisperFailure catch arm precedes the generic Exception arm`() {
        val source = engineSource()
        val specific = source.indexOf("catch (e: WhisperFailure)")
        val generic = source.indexOf("catch (e: Exception)")

        assertTrue(
            "Expected a `catch (e: WhisperFailure)` arm in WhisperEngine — " +
                "without it the precise failure stage is lost.",
            specific >= 0
        )
        assertTrue("Expected a generic `catch (e: Exception)` arm", generic >= 0)
        assertTrue(
            "The `catch (e: WhisperFailure)` arm must come BEFORE " +
                "`catch (e: Exception)` at index $generic, but it is at " +
                "index $specific. Kotlin takes the FIRST matching arm, so " +
                "in this order the generic arm swallows the stage and the " +
                "user gets a misleading message.",
            specific < generic
        )
    }

    @Test
    fun `the WhisperFailure arm returns the failure without rewrapping`() {
        val source = engineSource()
        val start = source.indexOf("catch (e: WhisperFailure)")
        assertTrue(start >= 0)
        val tail = source.substring(start, minOf(start + 800, source.length))
        assertTrue(
            "The WhisperFailure arm must propagate the original failure " +
                "(Result.failure(e)) so the stage survives. Got:\n$tail",
            tail.contains("Result.failure(e)")
        )
        assertFalse(
            "The WhisperFailure arm must NOT construct a NEW generic " +
                "Exception — that is the flattening bug. Got:\n$tail",
            Regex("""Result\.failure\(\s*Exception\(""").containsMatchIn(tail)
        )
    }

    @Test
    fun `every thrown engine failure names a stage`() {
        val source = engineSource()
        // Count the WhisperFailure constructions. If inference, decode,
        // install and native-init paths all name a stage, we expect
        // several. A count of 0 or 1 would mean the diagnosis surface
        // was gutted.
        val constructions = Regex("""WhisperFailure\(""").findAll(source).count()
        assertTrue(
            "Expected multiple WhisperFailure constructions (install, " +
                "native init, decode, inference). Found: $constructions",
            constructions >= 4
        )
    }

    @Test
    fun `inference errors are never swallowed into an empty result`() {
        val source = engineSource()
        // The window loop must catch Throwable around the native call
        // and RETURN a failure — not continue with zero words, which is
        // the silent-empty bug class this project already fixed once.
        val inferCatch = source.indexOf("stage = WhisperDiagnostics.Stage.INFERENCE_ERROR")
        assertTrue(
            "Expected the window loop to report INFERENCE_ERROR",
            inferCatch >= 0
        )
        val tail = source.substring(inferCatch, minOf(inferCatch + 1200, source.length))
        assertTrue(
            "The inference catch must return Result.failure, not fall " +
                "through to an empty word list. Got:\n$tail",
            tail.contains("Result.failure(")
        )
        assertTrue(
            "The inference catch must carry the original cause so the " +
                "native error is not discarded. Got:\n$tail",
            tail.contains("cause = e")
        )
    }

    @Test
    fun `audio decode failure names the AUDIO_DECODE stage`() {
        val source = engineSource()
        assertTrue(
            "Expected the decode path to report Stage.AUDIO_DECODE",
            source.contains("Stage.AUDIO_DECODE")
        )
    }

    @Test
    fun `empty pcm is distinguished from a decode error`() {
        val source = engineSource()
        assertTrue(
            "An empty PCM buffer is a different fault from a decode " +
                "exception and must report INFERENCE_EMPTY",
            source.contains("Stage.INFERENCE_EMPTY")
        )
    }

    @Test
    fun `the model file is inspected before the native load is attempted`() {
        val source = engineSource()
        val fnStart = source.indexOf("private fun ensureNativeContext(")
        assertTrue(fnStart >= 0)
        val fn = extractFunctionBody(source, fnStart)

        val inspect = fn.indexOf("WhisperDiagnostics.inspectModel(")
        val load = fn.indexOf("WhisperNative.initFromFileDetailed(")

        assertTrue("ensureNativeContext must inspect the model file", inspect >= 0)
        assertTrue("ensureNativeContext must call initFromFileDetailed", load >= 0)
        assertTrue(
            "The file must be inspected BEFORE the native load so that a " +
                "missing/truncated file is named as such instead of being " +
                "blamed on the native layer.",
            inspect < load
        )
    }

    @Test
    fun `native init null is disambiguated by the on-disk size`() {
        val source = engineSource()
        val fnStart = source.indexOf("private fun ensureNativeContext(")
        assertTrue(fnStart >= 0)
        // Scope to the function body by brace matching rather than a
        // fixed character window — a window is brittle and silently
        // stops covering the code the moment the function grows.
        val fn = extractFunctionBody(source, fnStart)
        assertTrue(
            "A NULL from native init must be split into MODEL_SHA_MISMATCH " +
                "(wrong size) vs NATIVE_INIT_NULL (size fine) rather than " +
                "always blaming the file.",
            fn.contains("Stage.MODEL_SHA_MISMATCH") &&
                fn.contains("Stage.NATIVE_INIT_NULL")
        )
        assertTrue(
            "The decision must be driven by the inspected artefact's " +
                "isWrongSize, not by a guess.",
            fn.contains("isWrongSize")
        )
    }

    /**
     * Return the text of the function whose declaration starts at
     * [declStart], found by matching braces. Falls back to a generous
     * window if brace matching fails, so the test reports a real
     * assertion failure rather than throwing.
     */
    private fun extractFunctionBody(source: String, declStart: Int): String {
        var depth = 0
        var i = declStart
        var seenBrace = false
        while (i < source.length) {
            when (source[i]) {
                '{' -> {
                    depth++
                    seenBrace = true
                }
                '}' -> {
                    depth--
                    if (seenBrace && depth == 0) {
                        return source.substring(declStart, i + 1)
                    }
                }
            }
            i++
        }
        return source.substring(declStart, minOf(declStart + 8000, source.length))
    }

    @Test
    fun `thread count is capped rather than taken from the raw cpu count`() {
        val source = engineSource()
        assertTrue(
            "effectiveThreads must apply the project-validated cap so an " +
                "8-core phone does not get 8 decoder threads.",
            source.contains("Threads.REQUESTED_CAP")
        )
        assertFalse(
            "effectiveThreads must not simply return the reported CPU count",
            source.contains("return Runtime.getRuntime().availableProcessors()")
        )
    }

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
}
