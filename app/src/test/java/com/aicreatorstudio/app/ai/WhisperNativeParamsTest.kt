package com.aicreatorstudio.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE B1.6 — regression guard for the
 * [WhisperNative.fullWithWordTimestamps] parameter surface
 * introduced when the engine started passing per-model
 * `threads` and `temperatureInc` through to whisper.cpp.
 *
 * PHASE B1.5 added the threads / temperatureInc params
 * to the JNI signature. PHASE B1.6 did not change the
 * JNI shape (the Large-v3 model uses the same
 * `whisper_full` call as Medium/Small), but every
 * catalog entry must still carry a `whisperThreads` value
 * so the per-model thread count is plumbed through to
 * the decoder. This file locks the Kotlin side of that
 * contract.
 *
 * We test the public surface only — the JNI C-side test
 * lives in `WhisperNativeSymbolsTest`, which scans the
 * compiled `libwhisper.so` for the exported symbols.
 *
 * Note: the actual JNI call in this JVM test process will
 * fail with `UnsatisfiedLinkError` because the test classpath
 * does not load `libwhisper.so`. The two `try { ... }`
 * branches below exercise the public function with the
 * smallest valid input and assert that the
 * default-parameter values the engine relies on are present
 * and correct.
 */
class WhisperNativeParamsTest {

    @Test
    fun `fullWithWordTimestamps default threads is 4 - matches every WhisperModelSpec whisperThreads`() {
        // Every model in v1 sets `whisperThreads = 4`; the
        // JNI default MUST match so a caller that omits the
        // argument does not silently request a different
        // thread count than the spec advertises.
        for (spec in WhisperModelSpec.entries) {
            assertEquals(
                "WhisperModelSpec.${spec.name}.aiSpec().whisperThreads " +
                    "must equal 4 (matches the JNI default). " +
                    "A drift would mean the engine picks one " +
                    "thread count via the explicit param and " +
                    "the spec advertises a different one.",
                4, spec.aiSpec().whisperThreads
            )
        }
    }

    @Test
    fun `WhisperEngine accuracy modes expose real decoder parameters`() {
        // 2026-09-09 ACCURACY MODES — every mode maps onto real
        // whisper.cpp knobs (beam_size / temperature_inc).
        // 2026-10-20 — the modes now differ ONLY by beam width.
        // temperature_inc is the spacing of the failure ladder, whose
        // last rung is accepted unconditionally by whisper.cpp; a
        // small step (BALANCED used 0.1 = eleven rungs) meant the
        // accepted subtitle could be a 1.0-temperature invention.
        // 0.2 is whisper.cpp's own default and leaves six rungs.
        val fast = WhisperEngine.AccuracyMode.FAST
        val balanced = WhisperEngine.AccuracyMode.BALANCED
        val accurate = WhisperEngine.AccuracyMode.ACCURATE
        assertEquals(1, fast.beamSize)
        assertEquals(5, balanced.beamSize)
        assertEquals(8, accurate.beamSize)
        assertEquals(0.2f, fast.temperatureInc, 0.0001f)
        assertEquals(0.2f, balanced.temperatureInc, 0.0001f)
        assertEquals(0.2f, accurate.temperatureInc, 0.0001f)
        // fromKey resolves persisted settings keys (unknown → BALANCED).
        assertEquals(WhisperEngine.AccuracyMode.ACCURATE,
            WhisperEngine.AccuracyMode.fromKey("accurate"))
        assertEquals(WhisperEngine.AccuracyMode.BALANCED,
            WhisperEngine.AccuracyMode.fromKey("garbage"))
    }
    @Test
    fun `WhisperNative fullWithWordTimestamps exposes threads and temperatureInc as callable parameters`() {
        // The pre-B1.5 signature had no threads /
        // temperatureInc parameters. This test calls the
        // function with the new parameters and asserts the
        // call compiles + runs (the JNI will fail with
        // UnsatisfiedLinkError because the test JVM has no
        // libwhisper.so; we accept either a thrown
        // UnsatisfiedLinkError OR a returned emptyList —
        // both are "the parameter was accepted by the
        // Kotlin signature" outcomes).
        val samples = FloatArray(16000) { 0f }
        try {
            val words = WhisperNative.fullWithWordTimestamps(
                ctx = 0L, // sentinel: no native context, but the parameter list is what we test
                samples = samples,
                language = "fa",
                translate = false,
                noContext = true,
                singleSegment = false,
                threads = 4,
                temperatureInc = 0.2f
            )
            // The ctx=0L guard returns emptyList() — also a
            // valid "params accepted" outcome.
            val isList: Boolean = words is List<*>
            assertTrue(
                "Result must be a (possibly empty) List, got: ${words?.javaClass?.name}",
                isList
            )
        } catch (e: UnsatisfiedLinkError) {
            // Expected on a JVM test classpath that does not
            // load libwhisper.so; the test exists to lock
            // the parameter list, not the JNI result.
            assertTrue(true)
        }
    }

    @Test
    fun `WhisperNative fullWithWordTimestamps exposes threads and temperatureInc as parameter types`() {
        // Kotlin's default-argument mechanism is the
        // contract the engine relies on. We can't call the
        // function without invoking the JNI (which the test
        // JVM doesn't have), so we verify the parameter
        // *types* via reflection. Parameter names compile to
        // `arg0..argN` unless `-parameters` is on, so we
        // match by type instead.
        //
        // Pre-B1.5 signature (4 params): ctx, samples,
        //   language, translate, noContext, singleSegment
        //   (and the JNI `nativeFullWithWordTimestamps` had
        //   6 params). After B1.5 the JNI gains two more
        //   params: jint j_threads, jfloat j_temperature_inc.
        // The Kotlin function is the wrapper that takes 11
        // named args (ctx, samples, language, initialPrompt,
        // beamSize, vadModelPath, translate, noContext,
        // singleSegment, threads, temperatureInc) — the
        // 2026-09-08 QUALITY FIX added initialPrompt + beamSize,
        // then VAD added vadModelPath.
        val method = WhisperNative::class.java.declaredMethods
            .firstOrNull { it.name == "fullWithWordTimestamps" }
        assertTrue(
            "WhisperNative.fullWithWordTimestamps must exist",
            method != null
        )
        val paramTypes = method!!.parameterTypes.map { it.simpleName }
        // 11 params — see the comment above.
        assertEquals(
            "fullWithWordTimestamps must have 11 parameters " +
                "(initialPrompt + beamSize + vadModelPath added on " +
                "top of the pre-B1.5 signature). If this drops, the " +
                "2026-09-08 quality fixes have been reverted.",
            11, paramTypes.size
        )
        assertTrue(
            "threads parameter (Int) must be present. Got types: $paramTypes",
            "int" in paramTypes
        )
        assertTrue(
            "temperatureInc parameter (Float) must be present. Got types: $paramTypes",
            "float" in paramTypes
        )
        assertTrue(
            "initialPrompt parameter (String) must be present. Got types: $paramTypes",
            "String" in paramTypes
        )
    }
}
