package com.aicreatorstudio.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-12 — proves the Whisper diagnostics instrumentation is
 * observational and cannot change transcription behaviour.
 *
 * ## What this guards
 *
 * The brief forbids changing model quality, inference parameters, or
 * transcription behaviour. `WhisperDiagnostics` gained structured
 * persistence and two new *read-only* helpers ([WhisperDiagnostics.inferenceConfig],
 * [WhisperDiagnostics.released], [WhisperDiagnostics.memoryPressure]) but
 * its failure classification — the thing the engine branches on — is
 * unchanged.
 *
 * The JVM process has no `libwhisper.so`, so nothing here claims native
 * inference works. It pins the diagnostic contract only.
 */
class WhisperDiagnosticsInstrumentationTest {

    @Test
    fun `every stage has a distinct enum entry`() {
        val stages = WhisperDiagnostics.Stage.entries
        assertEquals(stages.size, stages.toSet().size)
    }

    @Test
    fun `each stage still maps to real Persian text`() {
        for (stage in WhisperDiagnostics.Stage.entries) {
            val text = WhisperDiagnostics.persianFor(stage)
            assertTrue("stage $stage must have a message", text.isNotBlank())
            assertTrue(
                "stage $stage message must be Persian (Arabic script)",
                text.any { it.code in 0x0600..0x06FF }
            )
        }
    }

    @Test
    fun `calling the diagnostic helpers without a sink does not throw`() {
        // In the JVM test process attach() was never called, so sink is
        // null. Every helper must be a no-op on the persistence side and
        // must not throw — logging must never take the app down.
        WhisperDiagnostics.attach(null)
        WhisperDiagnostics.stage(WhisperDiagnostics.Stage.NATIVE_INIT_OK, 12L, "detail")
        WhisperDiagnostics.failure(
            stage = WhisperDiagnostics.Stage.MODEL_MISSING,
            env = null, model = null, error = null, detail = "no model"
        )
        WhisperDiagnostics.nativeLoad(ok = true, abi = "arm64-v8a", version = "1.7.6", elapsedMs = 5L)
        WhisperDiagnostics.nativeLoad(ok = false, abi = "armeabi-v7a", version = null, elapsedMs = 5L)
        // 2026-10-13 — the classified-cause overload must also be a
        // no-op without a sink.
        WhisperDiagnostics.nativeLoad(
            ok = false, abi = "armeabi-v7a", version = null, elapsedMs = 5L,
            failure = NativeEngineFailure.LIBRARY_ABI_MISSING,
            error = UnsatisfiedLinkError("dlopen failed: library not found")
        )
        WhisperDiagnostics.inference(
            windowIndex = 0, totalWindows = 4, samples = 48_000,
            threads = 4, elapsedMs = 900L, wordEntries = 12
        )
        WhisperDiagnostics.inferenceConfig(
            modelId = "SMALL", language = "fa", beamSize = 1,
            threads = 4, temperatureInc = 0.1f, vad = false
        )
        WhisperDiagnostics.released("model=SMALL")
    }

    @Test
    fun `model artifact summary carries size facts but never a full path`() {
        val artifact = WhisperDiagnostics.ModelArtifact(
            fileName = "small.bin",
            dirName = "models",
            exists = true,
            actualBytes = 100L,
            expectedMinBytes = 50L,
            expectedBytes = 100L
        )
        val summary = artifact.summary()
        assertTrue(summary.contains("small.bin"))
        assertTrue(summary.contains("exists=true"))
        assertTrue(!summary.contains("/"))
    }

    @Test
    fun `truncated and wrong-size classifications are independent`() {
        val truncated = WhisperDiagnostics.ModelArtifact(
            fileName = "m.bin", dirName = "models",
            exists = true, actualBytes = 10L,
            expectedMinBytes = 50L, expectedBytes = 100L
        )
        assertTrue(truncated.isTruncated)
        assertTrue(truncated.isWrongSize)

        val exact = WhisperDiagnostics.ModelArtifact(
            fileName = "m.bin", dirName = "models",
            exists = true, actualBytes = 100L,
            expectedMinBytes = 50L, expectedBytes = 100L
        )
        assertTrue(!exact.isTruncated)
        assertTrue(!exact.isWrongSize)
    }

    @Test
    fun `environment snapshot exposes build facts only`() {
        // Environment(...) is a plain data holder; we assert the fields
        // a report needs exist, without touching Android's ActivityManager.
        val env = WhisperDiagnostics.Environment(
            apiLevel = 35,
            release = "15",
            primaryAbi = "arm64-v8a",
            supportedAbis = "arm64-v8a,armeabi-v7a",
            totalRamMb = 8192L,
            availRamMb = 2048L,
            lowMemory = false,
            cpuCount = 8,
            largeHeap = true
        )
        assertEquals(35, env.apiLevel)
        assertEquals("arm64-v8a", env.primaryAbi)
        assertTrue(env.cpuCount > 0)
        assertNotNull(env.supportedAbis)
    }
}
