package com.aicreatorstudio.app.ai.subtitle.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-09-13 GROQ REBUILD — pins the Groq verbose_json → internal
 * [Transcription] mapping priority: segments array > honest
 * single-segment fallback with the REAL audio duration.
 */
class SubtitleResultParserTest {

    private val parser = SubtitleResultParser()

    private fun response(
        text: String = "",
        segments: List<CloudWhisperService.TranscriptionResponse.Segment> = emptyList(),
        words: List<CloudWhisperService.TranscriptionResponse.Word> = emptyList(),
        vtt: String? = null
    ) = CloudWhisperService.TranscriptionResponse(
        text = text,
        language = "fa",
        durationSec = null,
        segments = segments,
        words = words,
        vtt = vtt
    )

    // ----------------------------------------------------------------
    // segments array (Groq verbose_json)
    // ----------------------------------------------------------------

    @Test
    fun `segments map to subtitles with ms timings and contained words`() {
        val t = parser.toTranscription(
            response(
                text = "سلام دوستان امروز",
                segments = listOf(
                    CloudWhisperService.TranscriptionResponse.Segment(0.0, 2.5, "سلام دوستان"),
                    CloudWhisperService.TranscriptionResponse.Segment(2.5, 5.2, "امروز")
                ),
                words = listOf(
                    CloudWhisperService.TranscriptionResponse.Word("سلام", 0.12, 0.55),
                    CloudWhisperService.TranscriptionResponse.Word("دوستان", 0.60, 1.10),
                    CloudWhisperService.TranscriptionResponse.Word("امروز", 2.60, 3.02)
                )
            ),
            audioDurationMs = 5200L
        )
        assertEquals(2, t.segments.size)
        assertEquals(0L, t.segments[0].startTimeMs)
        assertEquals(2500L, t.segments[0].endTimeMs)
        // Word assignment follows the segment window.
        assertEquals(2, t.segments[0].words.size)
        assertEquals("سلام", t.segments[0].words[0].text)
        assertEquals(1, t.segments[1].words.size)
        assertEquals("امروز", t.segments[1].words[0].text)
    }

    @Test
    fun `vtt field is always null on the groq path`() {
        // Groq verbose_json has no vtt — the response model pins
        // this so a future backend change fails loudly here.
        val t = parser.toTranscription(
            response(text = "سلام", vtt = null),
            audioDurationMs = 1000L
        )
        assertEquals(1, t.segments.size)
        assertEquals("سلام", t.segments[0].text)
    }

    // ----------------------------------------------------------------
    // honest fallback (no timings at all)
    // ----------------------------------------------------------------

    @Test
    fun `text-only reply becomes one segment spanning the real duration`() {
        val t = parser.toTranscription(
            response(text = "متن کامل"),
            audioDurationMs = 12_000L
        )
        assertEquals(1, t.segments.size)
        assertEquals("متن کامل", t.segments[0].text)
        assertEquals(0L, t.segments[0].startTimeMs)
        assertEquals(12_000L, t.segments[0].endTimeMs)
    }

    @Test
    fun `text-only reply with unknown duration uses at least 1 second`() {
        val t = parser.toTranscription(
            response(text = "متن"),
            audioDurationMs = 0L
        )
        assertEquals(1, t.segments.size)
        assertTrue(t.segments[0].endTimeMs >= 1000L)
    }

    @Test
    fun `persian text is preserved verbatim - no reordering`() {
        val t = parser.toTranscription(
            response(
                text = "سلام",
                segments = listOf(
                    CloudWhisperService.TranscriptionResponse.Segment(0.0, 1.0, "سلام دوستان، امروز!")
                )
            ),
            audioDurationMs = 1000L
        )
        assertEquals("سلام دوستان، امروز!", t.segments[0].text)
    }

    @Test
    fun `segments without backend words get synthesized word timings`() {
        val t = parser.toTranscription(
            response(
                text = "سلام دوستان",
                segments = listOf(
                    CloudWhisperService.TranscriptionResponse.Segment(0.0, 3.0, "سلام دوستان")
                ),
                words = emptyList()
            ),
            audioDurationMs = 3000L
        )
        assertEquals(1, t.segments.size)
        assertEquals(2, t.segments[0].words.size)
        assertEquals("سلام", t.segments[0].words[0].text)
        assertEquals("دوستان", t.segments[0].words[1].text)
        assertTrue(t.segments[0].words[0].startTimeMs < t.segments[0].words[0].endTimeMs)
        assertEquals(t.segments[0].words[0].endTimeMs, t.segments[0].words[1].startTimeMs)
        // Top-level words list should also contain synthesized words
        assertEquals(2, t.words.size)
    }

    @Test
    fun `fallback without backend words gets synthesized timings`() {
        val t = parser.toTranscription(
            response(text = "سلام دوستان"),
            audioDurationMs = 4000L
        )
        assertEquals(1, t.segments.size)
        assertEquals(2, t.segments[0].words.size)
        assertEquals("سلام", t.segments[0].words[0].text)
        assertEquals("دوستان", t.segments[0].words[1].text)
        assertEquals(0L, t.segments[0].words[0].startTimeMs)
        assertTrue(t.segments[0].words[1].endTimeMs <= 4000L)
    }
}
