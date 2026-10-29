package com.aicreatorstudio.app.ai.subtitle.cloud

import com.aicreatorstudio.app.ai.groq.GroqResponseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the [SubtitleResultParser.toTranscription] overload that
 * accepts the DIRECT [GroqWhisperClient] reply shape
 * ([GroqResponseParser.GroqTranscription]) — the user-own-key path.
 *
 * Must produce the same output as the Worker-shaped overload for
 * the same underlying data (both delegate to one conversion).
 */
class SubtitleResultParserGroqOverloadTest {

    private val parser = SubtitleResultParser()

    @Test
    fun `maps segments and words to millisecond transcription`() {
        val g = GroqResponseParser.GroqTranscription(
            text = "سلام دنیا",
            language = "fa",
            durationSec = 2.5,
            segments = listOf(
                GroqResponseParser.GroqSegment(id = 0, startSec = 0.0, endSec = 2.5, text = "سلام دنیا")
            ),
            words = listOf(
                GroqResponseParser.GroqWord(word = "سلام", startSec = 0.1, endSec = 0.9),
                GroqResponseParser.GroqWord(word = "دنیا", startSec = 1.0, endSec = 2.0)
            ),
            vtt = null
        )
        val t = parser.toTranscription(g, audioDurationMs = 2500L)

        assertEquals(1, t.segments.size)
        assertEquals("سلام دنیا", t.segments[0].text)
        assertEquals(0L, t.segments[0].startTimeMs)
        assertEquals(2500L, t.segments[0].endTimeMs)

        assertEquals(2, t.words.size)
        assertEquals("سلام", t.words[0].text)
        assertEquals(100L, t.words[0].startTimeMs)
        assertEquals(900L, t.words[0].endTimeMs)
        assertEquals(2000L, t.words[1].endTimeMs)
    }

    @Test
    fun `wordless response synthesizes proportional words`() {
        val g = GroqResponseParser.GroqTranscription(
            text = "یک دو سه",
            language = "fa",
            durationSec = 3.0,
            segments = listOf(
                GroqResponseParser.GroqSegment(id = 0, startSec = 0.0, endSec = 3.0, text = "یک دو سه")
            ),
            words = emptyList(),
            vtt = null
        )
        val t = parser.toTranscription(g, audioDurationMs = 3000L)

        assertEquals(1, t.segments.size)
        assertEquals(3, t.words.size)
        assertEquals("یک", t.words[0].text)
        // synthesized words must span the real segment window
        assertEquals(0L, t.words[0].startTimeMs)
        assertEquals(3000L, t.words[2].endTimeMs)
    }

    @Test
    fun `empty response falls back to one honest blank segment, not a fake empty result`() {
        val g = GroqResponseParser.GroqTranscription(
            text = "",
            language = null,
            durationSec = null,
            segments = emptyList(),
            words = emptyList(),
            vtt = null
        )
        val t = parser.toTranscription(g, audioDurationMs = 0L)
        // The worker path never returns isEmpty: it always emits one
        // segment spanning the real (here: floored to 1s) duration.
        assertEquals(1, t.segments.size)
        assertEquals("", t.segments[0].text)
        assertEquals(0L, t.segments[0].startTimeMs)
        assertEquals(1000L, t.segments[0].endTimeMs)
        assertTrue(t.words.isEmpty())
    }

    @Test
    fun `word straddling a segment boundary is kept in exactly one segment - no loss`() {
        // Regression guard for the 2026-10-10 midpoint-assignment fix:
        // "کتاب" starts at 1.9s but ends at 2.3s, straddling the 2.0s
        // segment boundary. The old strict-containment filter dropped it
        // from BOTH segments' word lists (top-level words kept it, but
        // downstream text rebuilt from segment.words lost it). The fix
        // must assign it to exactly one segment (midpoint 2.1s → segment 1).
        val g = GroqResponseParser.GroqTranscription(
            text = "من کتاب خواندم",
            language = "fa",
            durationSec = 4.0,
            segments = listOf(
                GroqResponseParser.GroqSegment(id = 0, startSec = 0.0, endSec = 2.0, text = "من"),
                GroqResponseParser.GroqSegment(id = 1, startSec = 2.0, endSec = 4.0, text = "کتاب خواندم")
            ),
            words = listOf(
                GroqResponseParser.GroqWord(word = "من", startSec = 0.1, endSec = 0.5),
                GroqResponseParser.GroqWord(word = "کتاب", startSec = 1.9, endSec = 2.3),
                GroqResponseParser.GroqWord(word = "خواندم", startSec = 2.5, endSec = 3.5)
            ),
            vtt = null
        )
        val t = parser.toTranscription(g, audioDurationMs = 4000L)

        assertEquals(2, t.segments.size)
        // top-level words all present
        assertEquals(3, t.words.size)
        // "کتاب" must appear in exactly one segment's word list
        val containing = t.segments.count { seg ->
            seg.words.any { it.text == "کتاب" }
        }
        assertEquals(
            "straddling word must be in exactly one segment, found in $containing",
            1,
            containing
        )
        // and no word is duplicated across segments
        val allSegmentWords = t.segments.flatMap { it.words }.map { it.text }
        assertEquals(3, allSegmentWords.size)
        assertEquals(allSegmentWords.sorted(), allSegmentWords.distinct().sorted())
    }

    @Test
    fun `every backend word lands in exactly one segment even with multiple boundaries`() {
        val g = GroqResponseParser.GroqTranscription(
            text = "یک دو سه چهار",
            language = "fa",
            durationSec = 4.0,
            segments = listOf(
                GroqResponseParser.GroqSegment(id = 0, startSec = 0.0, endSec = 1.5, text = "یک دو"),
                GroqResponseParser.GroqSegment(id = 1, startSec = 1.5, endSec = 3.0, text = "سه"),
                GroqResponseParser.GroqSegment(id = 2, startSec = 3.0, endSec = 4.0, text = "چهار")
            ),
            words = listOf(
                GroqResponseParser.GroqWord(word = "یک", startSec = 0.0, endSec = 0.5),
                GroqResponseParser.GroqWord(word = "دو", startSec = 0.6, endSec = 1.4),
                GroqResponseParser.GroqWord(word = "سه", startSec = 1.45, endSec = 1.55), // straddles 1.5
                GroqResponseParser.GroqWord(word = "چهار", startSec = 2.9, endSec = 3.8)  // straddles 3.0
            ),
            vtt = null
        )
        val t = parser.toTranscription(g, audioDurationMs = 4000L)

        assertEquals(3, t.segments.size)
        val allSegmentWords = t.segments.flatMap { it.words }.map { it.text }
        // total count must equal the backend word count — nothing lost, nothing duplicated
        assertEquals(4, allSegmentWords.size)
        assertEquals(4, allSegmentWords.distinct().size)
        // top-level list also complete
        assertEquals(4, t.words.size)
    }

    @Test
    fun `word in a gap between segments attaches to the nearest segment`() {
        val g = GroqResponseParser.GroqTranscription(
            text = "سلام دنیا",
            language = "fa",
            durationSec = 3.0,
            segments = listOf(
                GroqResponseParser.GroqSegment(id = 0, startSec = 0.0, endSec = 1.0, text = "سلام"),
                GroqResponseParser.GroqSegment(id = 1, startSec = 2.0, endSec = 3.0, text = "دنیا")
            ),
            words = listOf(
                GroqResponseParser.GroqWord(word = "سلام", startSec = 0.1, endSec = 0.9),
                // midpoint 1.5s falls in the gap (1.0..2.0) — must still be assigned
                GroqResponseParser.GroqWord(word = "میان", startSec = 1.2, endSec = 1.8),
                GroqResponseParser.GroqWord(word = "دنیا", startSec = 2.1, endSec = 2.9)
            ),
            vtt = null
        )
        val t = parser.toTranscription(g, audioDurationMs = 3000L)

        val allSegmentWords = t.segments.flatMap { it.words }.map { it.text }
        assertEquals("no word may be dropped, got $allSegmentWords", 3, allSegmentWords.size)
        assertEquals(3, allSegmentWords.distinct().size)
    }

    @Test
    fun `matches worker-shaped overload for identical data`() {
        val g = GroqResponseParser.GroqTranscription(
            text = "متن آزمایشی",
            language = "fa",
            durationSec = 1.5,
            segments = listOf(
                GroqResponseParser.GroqSegment(id = 0, startSec = 0.2, endSec = 1.4, text = "متن آزمایشی")
            ),
            words = listOf(
                GroqResponseParser.GroqWord(word = "متن", startSec = 0.2, endSec = 0.6),
                GroqResponseParser.GroqWord(word = "آزمایشی", startSec = 0.7, endSec = 1.4)
            ),
            vtt = null
        )
        val viaOverload = parser.toTranscription(g, 1500L)
        val viaWorker = parser.toTranscription(
            CloudWhisperService.TranscriptionResponse(
                text = g.text,
                language = g.language,
                durationSec = g.durationSec,
                segments = g.segments.map {
                    CloudWhisperService.TranscriptionResponse.Segment(it.startSec, it.endSec, it.text)
                },
                words = g.words.map {
                    CloudWhisperService.TranscriptionResponse.Word(it.word, it.startSec, it.endSec)
                },
                vtt = null
            ),
            1500L
        )
        // Subtitle/TranscribedWord carry random UUID ids, so compare
        // the meaningful fields rather than whole-object equality.
        assertEquals(viaWorker.segments.size, viaOverload.segments.size)
        assertEquals(
            viaWorker.segments.map { Triple(it.text, it.startTimeMs, it.endTimeMs) },
            viaOverload.segments.map { Triple(it.text, it.startTimeMs, it.endTimeMs) }
        )
        assertEquals(
            viaWorker.words.map { Quadruple(it.text, it.startTimeMs, it.endTimeMs, it.confidence) },
            viaOverload.words.map { Quadruple(it.text, it.startTimeMs, it.endTimeMs, it.confidence) }
        )
    }

    private data class Quadruple<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
