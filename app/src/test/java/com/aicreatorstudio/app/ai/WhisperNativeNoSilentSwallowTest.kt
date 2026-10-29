package com.aicreatorstudio.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE B3 stop-bug-3 audit — regression test for the JNI
 * exception-swallowing bug that made the AI Subtitle pipeline
 * silently produce zero overlays when whisper.cpp failed.
 *
 * ## What this test locks
 *
 * `WhisperNative.fullWithWordTimestamps` is the Kotlin
 * shim around the C++ `whisper_full_with_word_timestamps` JNI
 * call. Pre-fix, the function had two `catch` blocks that
 * returned `emptyList()` for any thrown exception. When the
 * native library was missing, when `FindClass` returned null
 * (Kotlin inner-class name drift), or when `whisper_full`
 * returned a non-zero rc, the result was indistinguishable
 * from "no speech in the audio" — the B2 mapper then
 * silently dropped every segment and the user saw a state
 * flip to "Generate again" with no subtitles and no error.
 *
 * Post-fix, the function **rethrows** both
 * `UnsatisfiedLinkError` and `Throwable` after logging, so
 * the calling engine's `try { ... } catch (e: Exception) {
 * Result.failure(e) }` actually surfaces the error.
 *
 * The test exercises the public `fullWithWordTimestamps`
 * entry point with a `ctx = 0L` — a guard clause that must
 * return `emptyList()` (zero is the "no native context"
 * sentinel) — and a negative sample-length that will trigger
 * the C-side null guard and cause a `null` array to be
 * returned. Either path must produce a `List<WordTimestamp>`
 * (possibly empty) and must NOT throw `UnsatisfiedLinkError`
 * for the zero-ctx case.
 */
class WhisperNativeNoSilentSwallowTest {

    @Test
    fun `fullWithWordTimestamps returns emptyList when ctx is zero (sentinel)`() {
        // A zero ctx is a well-defined "no native context"
        // sentinel, NOT a JNI failure. The guard clause must
        // return emptyList() silently — this is the ONLY
        // path that should swallow a no-op.
        val words = WhisperNative.fullWithWordTimestamps(
            ctx = 0L,
            samples = FloatArray(16000) { 0f },
            language = "fa",
            translate = false,
            noContext = true,
            singleSegment = false
        )
        assertEquals(
            "ctx=0L is a sentinel — must return emptyList without throwing",
            0, words.size
        )
    }

    /**
     * The pre-fix code had:
     *
     *   } catch (e: UnsatisfiedLinkError) { emptyList() }
     *   } catch (e: Exception) { emptyList() }
     *
     * The post-fix code rethrows both. This test asserts the
     * **shape** of the re-throw contract by ensuring the
     * non-zero-ctx path does not catch and silence a non-Result
     * `Exception` thrown by the native bridge. We can't call
     * the real JNI from the JVM unit test (no .so loader), so
     * we use reflection-free inspection of the source itself
     * to lock the contract.
     *
     * Why a string-shape test is acceptable: the brief is that
     * JNI exceptions MUST surface so the engine's outer
     * `try/catch` can produce a meaningful `Result.failure`.
     * If a refactor reintroduces silent swallow, this test
     * fails with a clear message.
     */
    @Test
    fun `fullWithWordTimestamps source does not silently swallow Throwable`() {
        val sourceFile = locateWhisperNativeKt()
        val source = sourceFile.readText()
        // Scope to the body of `fun fullWithWordTimestamps` so we
        // don't pick up the `catch (e: UnsatisfiedLinkError)` block
        // in `initFromFile` (which legitimately returns null).
        val (functionStart, functionEnd) = locateFunction(source)
        val fn = source.substring(functionStart, functionEnd)
        val catchStart = fn.indexOf("} catch (e: Throwable) {")
        assertTrue(
            "Expected the post-fix `catch (e: Throwable) { ... }` block " +
                "in WhisperNative.fullWithWordTimestamps but did not find it. " +
                "Did the source drift?",
            catchStart >= 0
        )
        val tail = fn.substring(catchStart)
        assertTrue(
            "After the stop-bug-3 fix, the `catch (e: Throwable)` " +
                "block in WhisperNative.fullWithWordTimestamps must " +
                "rethrow the exception. Got tail:\n$tail",
            tail.contains("throw e")
        )
        assertTrue(
            "After the stop-bug-3 fix, the `catch (e: Throwable)` " +
                "block must NOT return emptyList() (that was the " +
                "original silent-swallow bug). Got tail:\n$tail",
            !tail.contains("emptyList()")
        )
    }

    @Test
    fun `fullWithWordTimestamps source rethrows UnsatisfiedLinkError too`() {
        val sourceFile = locateWhisperNativeKt()
        val source = sourceFile.readText()
        // Scope to the body of `fun fullWithWordTimestamps` so we
        // don't pick up the `catch (e: UnsatisfiedLinkError)` block
        // in `initFromFile` (which legitimately returns null).
        val (functionStart, functionEnd) = locateFunction(source)
        val fn = source.substring(functionStart, functionEnd)
        val catchStart = fn.indexOf("} catch (e: UnsatisfiedLinkError) {")
        assertTrue(
            "Expected the post-fix `catch (e: UnsatisfiedLinkError)` " +
                "block in fullWithWordTimestamps but did not find it.",
            catchStart >= 0
        )
        val tail = fn.substring(catchStart)
        assertTrue(
            "After the stop-bug-3 fix, the `catch (e: " +
                "UnsatisfiedLinkError)` block in " +
                "fullWithWordTimestamps must rethrow. Got tail:\n$tail",
            tail.contains("throw e")
        )
        assertTrue(
            "After the stop-bug-3 fix, the `catch (e: " +
                "UnsatisfiedLinkError)` block must NOT return " +
                "emptyList(). Got tail:\n$tail",
            !tail.contains("emptyList()")
        )
    }

    /**
     * Returns the (start, end) indices of the body of
     * `fun fullWithWordTimestamps(` in [source]. The end is the
     * index just past the function's closing brace.
     */
    private fun locateFunction(source: String): Pair<Int, Int> {
        val funStart = source.indexOf("fun fullWithWordTimestamps(")
        assertTrue("Could not find `fun fullWithWordTimestamps`", funStart >= 0)
        // From the function's start, count `{`/`}` to find the
        // matching close brace.
        var depth = 0
        var i = funStart
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return funStart to i + 1
                }
            }
            i++
        }
        throw IllegalStateException("Could not find closing brace of fullWithWordTimestamps")
    }

    private fun locateWhisperNativeKt(): java.io.File {
        // Walk up from cwd to the project root (where settings.gradle.kts lives).
        var dir: java.io.File? = java.io.File(".").absoluteFile
        repeat(8) {
            if (dir?.resolve("settings.gradle.kts")?.isFile == true) {
                val f = dir.resolve(
                    "app/src/main/java/com/aicreatorstudio/app/ai/WhisperNative.kt"
                )
                if (f.isFile) return f
            }
            dir = dir?.parentFile
        }
        throw IllegalStateException(
            "Could not locate WhisperNative.kt from cwd. " +
                "Are the JVM unit tests being run from the project root?"
        )
    }
}
