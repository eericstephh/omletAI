package com.aicreatorstudio.app.ai

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SubtitleGenerator]'s engine-selection / fallback
 * behaviour (clean 2026-09-08 facade surface).
 *
 * The façade holds a list of [TranscriptionEngine]s in priority
 * order and routes every call to the first one whose
 * [TranscriptionEngine.isAvailable] is `true`. Covered:
 *
 * 1. Whisper available (priority 1) → its label is surfaced.
 * 2. Empty / unavailable list → failure with the Persian
 *    ENGINE_UNAVAILABLE message, null label.
 * 3. `initializeModelFor(spec)` proxies to the active engine.
 * 4. `release()` forwards to the active engine.
 * 5. `isModelDownloaded(spec)` is false with no engine.
 *
 * All tests use a [FakeTranscriptionEngine] rather than
 * instantiating [WhisperEngine] (the real engine needs an Android
 * Context + JNI).
 */
class TranscriptionEngineFallbackTest {

    @Test
    fun `whisper label is surfaced when it is the first available engine`() {
        val fakeWhisper = FakeTranscriptionEngine(
            id = "whisper",
            displayName = "Whisper (test)",
            isAvailable = true
        )
        val gen = SubtitleGenerator(engines = listOf(fakeWhisper))
        assertEquals("Whisper (test)", gen.activeEngineDisplayName)
    }

    @Test
    fun `no active engine when every engine is unavailable`() {
        val fakeWhisper = FakeTranscriptionEngine(
            id = "whisper", displayName = "Whisper", isAvailable = false
        )
        val gen = SubtitleGenerator(engines = listOf(fakeWhisper))
        assertNull(gen.activeEngineDisplayName)
    }

    @Test
    fun `empty engine list leaves the facade with no active engine`() {
        val gen = SubtitleGenerator(engines = emptyList())
        assertNull(gen.activeEngineDisplayName)
    }

    @Test
    fun `initializeModelFor returns Persian failure when no engine is available`() = runTest {
        val gen = SubtitleGenerator(engines = emptyList())
        val result = gen.initializeModelFor(WhisperModelSpec.SMALL) { /* no-op */ }
        assertTrue("expected failure, got $result", result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("موتور") == true
        )
    }

    @Test
    fun `release is a no-op when no engine is available`() {
        val gen = SubtitleGenerator(engines = emptyList())
        // Must not throw.
        gen.release()
    }

    @Test
    fun `release forwards to the active engine when one is available`() {
        val fakeWhisper = FakeTranscriptionEngine(
            id = "whisper", displayName = "Whisper (test)", isAvailable = true
        )
        val gen = SubtitleGenerator(engines = listOf(fakeWhisper))
        gen.release()
        assertEquals(1, fakeWhisper.releaseCount)
    }

    @Test
    fun `isModelDownloaded is false when no engine is active`() {
        val gen = SubtitleGenerator(engines = emptyList())
        // Must not throw; must report false.
        assertEquals(false, gen.isModelDownloaded(WhisperModelSpec.SMALL))
    }
}

/**
 * Lightweight test double for [TranscriptionEngine]. Captures the
 * arguments it was called with so tests can assert on them, and
 * returns a configurable transcription result without touching
 * disk, network, or JNI.
 */
private class FakeTranscriptionEngine(
    override val id: String,
    override val displayName: String,
    override val isAvailable: Boolean,
    private val transcriptionResult: Result<Transcription> =
        Result.success(Transcription.Empty),
    private val initializeResult: Result<Unit> = Result.success(Unit)
) : TranscriptionEngine {

    var releaseCount: Int = 0
        private set

    override suspend fun initializeModel(onProgress: (Float) -> Unit): Result<Unit> {
        onProgress(1f)
        return initializeResult
    }

    override suspend fun generateTranscription(
        videoUri: android.net.Uri,
        onProgress: (Float) -> Unit
    ): Result<Transcription> {
        onProgress(0.5f)
        onProgress(1f)
        return transcriptionResult
    }

    override fun release() {
        releaseCount += 1
    }
}
