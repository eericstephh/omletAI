package com.aicreatorstudio.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-09-13 — pins the CLOUD transcription flow end-to-end:
 * the Whisper API (Cloud) path frequently returns segments (or
 * VTT cues) WITHOUT per-word timings, and the pipeline must
 * still produce overlays — the "هیچ گفتاری در ویدیو شناسایی
 * نشد" bug was three word-only gates dropping these results:
 *
 *  1. EditorViewModel.adaptToWindow  — filtered words only
 *  2. WhisperTranscriptionToTextOverlays — dropped word-less
 *     segments
 *  3. SilenceFilter / RepetitionFilter — dropped word-less
 *     segments
 *
 * These tests lock the word-less path through the post-processor
 * and the mapper (steps 2 + 3). The window adaptation (step 1)
 * lives in the ViewModel and is verified by the segments
 * assertions here at the data level.
 */
class CloudWordlessTranscriptionTest {

    private fun cloudTranscription(): Transcription {
        // Exactly what SubtitleResultParser produces from a
        // Groq reply with segments but NO words.
        val segments = listOf(
            Subtitle(text = "سلام دوستان", startTimeMs = 0L, endTimeMs = 2_500L),
            Subtitle(text = "امروز می‌خواهیم یک ویدیو بسازیم", startTimeMs = 2_500L, endTimeMs = 7_200L),
            Subtitle(text = "با ویسپر ابری", startTimeMs = 7_200L, endTimeMs = 9_000L)
        )
        return Transcription(segments = segments, words = emptyList())
    }

    @Test
    fun `mapper keeps word-less segments as overlays`() {
        val overlays = WhisperTranscriptionToTextOverlays.toTextOverlays(
            cloudTranscription(),
            clipOffsetMs = 0L
        )
        assertEquals(3, overlays.size)
        assertEquals("سلام دوستان", overlays[0].text)
        assertEquals(0L, overlays[0].startTimeMs)
        assertEquals(2_500L, overlays[0].endTimeMs)
        // Karaoke needs words — must be OFF for word-less overlays.
        assertTrue(!overlays[0].karaokeEnabled)
        // The ai-source tag survives so export picks them up.
        assertEquals("ai", overlays[0].subtitleSource)
    }

    @Test
    fun `mapper applies the clip offset to word-less segments`() {
        val overlays = WhisperTranscriptionToTextOverlays.toTextOverlays(
            cloudTranscription(),
            clipOffsetMs = 5_000L
        )
        assertEquals(5_000L, overlays[0].startTimeMs)
        assertEquals(7_500L, overlays[0].endTimeMs)
        assertEquals(14_000L, overlays[2].endTimeMs)
    }

    @Test
    fun `silence filter keeps word-less segments with real text`() {
        val filtered = com.aicreatorstudio.app.ai.subtitle.SilenceFilter.filter(
            cloudTranscription(),
            minConfidence = 0.15f,
            longWordMs = 5_000L
        )
        assertEquals(3, filtered.segments.size)
        assertEquals("سلام دوستان", filtered.segments[0].text)
    }

    @Test
    fun `silence filter still drops a blank word-less segment`() {
        val t = Transcription(
            segments = listOf(
                Subtitle(text = "  ", startTimeMs = 0L, endTimeMs = 3_000L),
                Subtitle(text = "واقعی", startTimeMs = 3_000L, endTimeMs = 5_000L)
            ),
            words = emptyList()
        )
        val filtered = com.aicreatorstudio.app.ai.subtitle.SilenceFilter.filter(t)
        assertEquals(1, filtered.segments.size)
        assertEquals("واقعی", filtered.segments[0].text)
    }

    @Test
    fun `repetition filter keeps word-less segments`() {
        val filtered = com.aicreatorstudio.app.ai.subtitle.RepetitionFilter.filter(
            cloudTranscription()
        )
        assertEquals(3, filtered.segments.size)
    }

    @Test
    fun `full post-processor preserves cloud segments`() {
        val post = kotlinx.coroutines.runBlocking {
            com.aicreatorstudio.app.ai.subtitle.SubtitlePostProcessor.postProcess(
                cloudTranscription(),
                settings = com.aicreatorstudio.app.ai.subtitle.SubtitlePostProcessSettings.Default
            )
        }
        // All real-text segments survive the whole pipeline.
        assertTrue(post.segments.size >= 3)
        assertEquals("سلام دوستان", post.segments.first().text)
        // And the mapper still accepts the result.
        val overlays = WhisperTranscriptionToTextOverlays.toTextOverlays(post)
        assertTrue(overlays.isNotEmpty())
    }
}
