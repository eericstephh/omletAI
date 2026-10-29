package com.aicreatorstudio.app.ai

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [SubtitleGenerator]'s STRICT CLOUD_API routing (2026-10-10):
 * cloud mode uses ONLY the user-key engine. A missing key makes
 * cloud UNAVAILABLE (no silent Worker fallback). A failure inside
 * the chosen engine is never retried against another backend.
 *
 * Uses fake engines (no Android, no network, no Keystore).
 *
 * NOTE: engine SELECTION is asserted via
 * [SubtitleGenerator.engineThatWillGenerate] (internal, same source
 * set) and [SubtitleGenerator.activeEngineDisplayName], plus which
 * fake actually receives initializeModel. The generateTranscription
 * path is not exercised here because it requires a real
 * `android.net.Uri`, which the JVM test source set cannot construct
 * (`unitTests.isReturnDefaultValues` stubs `Uri.parse` to null).
 * That path uses the identical resolver (see engineThatWillGenerate).
 */
class SubtitleGeneratorCloudRoutingTest {

    @Test
    fun `cloud + key routes to the user-key engine, not the worker or local`() = runTest {
        val userKey = FakeEngine(UserGroqEngineId, "کلید شخصی", isAvailable = true)
        val worker = FakeEngine("whisper_cloud", "Worker", isAvailable = true)
        val local = FakeEngine("whisper", "Local", isAvailable = true)
        val gen = SubtitleGenerator(engines = listOf(local, userKey, worker))

        val result = gen.initializeModelFor(WhisperModelSpec.CLOUD_API) {}

        assertTrue(result.isSuccess)
        assertEquals(1, userKey.initCount)
        assertEquals(0, worker.initCount)
        assertEquals(0, local.initCount)
        assertEquals(userKey, gen.engineThatWillGenerate())
        assertEquals("کلید شخصی", gen.activeEngineDisplayName)
        assertTrue(gen.hasUserGroqKey())
        assertTrue(gen.isCloudEngineAvailable())
    }

    @Test
    fun `cloud without key is unavailable, no silent worker fallback`() = runTest {
        val userKey = FakeEngine(UserGroqEngineId, "کلید شخصی", isAvailable = false)
        val worker = FakeEngine("whisper_cloud", "Worker", isAvailable = true)
        val gen = SubtitleGenerator(engines = listOf(userKey, worker))

        val result = gen.initializeModelFor(WhisperModelSpec.CLOUD_API) {}

        assertTrue(result.isFailure)
        assertEquals(0, userKey.initCount)
        assertEquals(0, worker.initCount)
        assertNull(gen.engineThatWillGenerate())
        assertFalse(gen.hasUserGroqKey())
        assertFalse(gen.isCloudEngineAvailable())
        val msg = result.exceptionOrNull()?.message ?: ""
        assertTrue(
            "message should name the settings path, got: $msg",
            msg.contains("اتصال هوش مصنوعی")
        )
    }

    @Test
    fun `cloud init failure does not retry local or worker, other engines untouched`() = runTest {
        val userKey = FakeEngine(
            UserGroqEngineId, "کلید شخصی",
            isAvailable = true, failInit = true
        )
        val worker = FakeEngine("whisper_cloud", "Worker", isAvailable = true)
        val local = FakeEngine("whisper", "Local", isAvailable = true)
        val gen = SubtitleGenerator(engines = listOf(local, userKey, worker))

        val result = gen.initializeModelFor(WhisperModelSpec.CLOUD_API) {}

        assertTrue(result.isFailure)
        assertEquals(1, userKey.initCount)
        assertEquals(0, worker.initCount)
        assertEquals(0, local.initCount)
    }

    @Test
    fun `no engines at all fails with the settings message`() = runTest {
        val gen = SubtitleGenerator(engines = emptyList())
        val result = gen.initializeModelFor(WhisperModelSpec.CLOUD_API) {}
        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()?.message ?: ""
        assertTrue(
            "message should name the settings path, got: $msg",
            msg.contains("اتصال هوش مصنوعی")
        )
    }

    @Test
    fun `small path still resolves to local engine, not the user-key cloud engine`() = runTest {
        // Regression guard: a saved API key must NOT hijack the
        // offline SMALL path via activeEngine (the user-key engine
        // must stay second in the DI list so the local engine wins).
        val userKey = FakeEngine(UserGroqEngineId, "کلید شخصی", isAvailable = true)
        val local = FakeEngine("whisper", "Local", isAvailable = true)
        val gen = SubtitleGenerator(engines = listOf(local, userKey))

        val result = gen.initializeModelFor(WhisperModelSpec.SMALL) {}

        assertTrue(result.isSuccess)
        assertEquals(1, local.initCount)
        assertEquals(0, userKey.initCount)
        assertEquals(local, gen.engineThatWillGenerate())
        assertEquals("Local", gen.activeEngineDisplayName)
    }

    private companion object {
        const val UserGroqEngineId = "groq_user_key"
    }
}

/**
 * Minimal [TranscriptionEngine] double capturing call counts.
 * [failInit] makes initializeModel return a failure so tests can
 * prove no cross-backend retry happens after a cloud failure.
 */
private class FakeEngine(
    override val id: String,
    override val displayName: String,
    override val isAvailable: Boolean,
    private val failInit: Boolean = false
) : TranscriptionEngine {

    var initCount = 0
        private set

    override suspend fun initializeModel(onProgress: (Float) -> Unit): Result<Unit> {
        initCount += 1
        if (failInit) return Result.failure(Exception("engine init failed"))
        onProgress(1f)
        return Result.success(Unit)
    }

    override suspend fun generateTranscription(
        videoUri: android.net.Uri,
        onProgress: (Float) -> Unit
    ): Result<Transcription> {
        onProgress(1f)
        return Result.success(Transcription.Empty)
    }

    override fun release() {}
}
