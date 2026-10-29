package com.aicreatorstudio.app.ai.subtitle.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-09-19 — the cloud worker MAY return verbose `words[]`
 * ({word, start, end}); the parser must attach those real
 * word-level timestamps instead of dropping them, so karaoke
 * highlighting and the words-per-caption chunker work on the
 * cloud path too. When `words` is absent the reply stays
 * word-less (proportional chunking downstream covers it).
 */
class CloudWordsParsingTest {

    private val service = CloudWhisperService()

    @Test
    fun `parses optional words array with real timings`() {
        val body = """
        {
          "success": true,
          "duration_seconds": 2.0,
          "transcript": "سلام دنیا",
          "segments": [
            {"id": 0, "start": 0.0, "end": 2.0, "text": "سلام دنیا"}
          ],
          "words": [
            {"word": "سلام", "start": 0.1, "end": 0.9},
            {"word": "دنیا", "start": 1.0, "end": 1.8}
          ]
        }
        """.trimIndent()
        val outcome = service.parseSuccess(body)
        assertTrue(outcome is CloudWhisperService.Outcome.Success)
        val r = (outcome as CloudWhisperService.Outcome.Success).response
        assertEquals(2, r.words.size)
        assertEquals("سلام", r.words[0].word)
        assertEquals(100L, (r.words[0].startSec * 1000).toLong())
        assertEquals(900L, (r.words[0].endSec * 1000).toLong())
        assertEquals("دنیا", r.words[1].word)
    }

    @Test
    fun `missing words array stays word-less`() {
        val body = """
        {
          "success": true,
          "duration_seconds": 1.5,
          "transcript": "سلام",
          "segments": [
            {"id": 0, "start": 0.0, "end": 1.5, "text": "سلام"}
          ]
        }
        """.trimIndent()
        val outcome = service.parseSuccess(body)
        assertTrue(outcome is CloudWhisperService.Outcome.Success)
        val r = (outcome as CloudWhisperService.Outcome.Success).response
        assertTrue(r.words.isEmpty())
        assertEquals(1, r.segments.size)
    }

    @Test
    fun `parsed words attach to their segment in the result parser`() {
        val body = """
        {
          "success": true,
          "duration_seconds": 2.0,
          "transcript": "سلام دنیا",
          "segments": [
            {"id": 0, "start": 0.0, "end": 2.0, "text": "سلام دنیا"}
          ],
          "words": [
            {"word": "سلام", "start": 0.1, "end": 0.9},
            {"word": "دنیا", "start": 1.0, "end": 1.8}
          ]
        }
        """.trimIndent()
        val outcome = service.parseSuccess(body)
        val r = (outcome as CloudWhisperService.Outcome.Success).response
        val parser = SubtitleResultParser()
        val t = parser.toTranscription(r, audioDurationMs = 2_000L)
        assertEquals(1, t.segments.size)
        assertEquals(2, t.segments[0].words.size)
        assertEquals("سلام", t.segments[0].words[0].text)
        assertEquals(100L, t.segments[0].words[0].startTimeMs)
    }
}
