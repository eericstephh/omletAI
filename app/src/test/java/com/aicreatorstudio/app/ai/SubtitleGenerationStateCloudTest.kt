package com.aicreatorstudio.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-09-12 — pins the CLOUD additions to the subtitle state
 * machine: the new states report honest progress (real fraction
 * or explicitly indeterminate) and the right Persian labels.
 */
class SubtitleGenerationStateCloudTest {

    @Test
    fun `uploading state carries a real fraction and label`() {
        val s = SubtitleGenerationState.UploadingAudio(0.5f)
        assertTrue(s.isRunning)
        assertFalse(s.isTerminal)
        assertEquals(0.5f, s.currentProgressFraction!!)
        assertFalse(s.isIndeterminate)
        assertTrue(s.userMessage.contains("در حال ارسال فایل صوتی"))
        assertTrue(s.userMessage.contains("50"))
    }

    @Test
    fun `uploading state without a fraction shows the label only`() {
        val s = SubtitleGenerationState.UploadingAudio(null)
        assertNull(s.currentProgressFraction)
        assertEquals("در حال ارسال فایل صوتی...", s.userMessage)
    }

    @Test
    fun `processing by whisper is indeterminate - never a fake percentage`() {
        val s = SubtitleGenerationState.ProcessingByWhisper
        assertTrue(s.isRunning)
        assertNull(s.currentProgressFraction)
        assertTrue(s.isIndeterminate)
        assertEquals("در حال پردازش توسط Whisper AI...", s.userMessage)
    }

    @Test
    fun `every cloud state is a running state and cancel still works`() {
        val states = listOf(
            SubtitleGenerationState.Preparing(),
            SubtitleGenerationState.DecodingAudio(0f),
            SubtitleGenerationState.UploadingAudio(0f),
            SubtitleGenerationState.ProcessingByWhisper,
            SubtitleGenerationState.CreatingSubtitles
        )
        states.forEach { assertTrue("must be running: $it", it.isRunning) }
        assertTrue(SubtitleGenerationState.Cancelling.isRunning)
        assertFalse(SubtitleGenerationState.Completed(3).isRunning)
    }
}
