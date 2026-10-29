package com.aicreatorstudio.app.ai.subtitle

import com.aicreatorstudio.app.ai.Subtitle
import com.aicreatorstudio.app.ai.TranscribedWord
import com.aicreatorstudio.app.ai.Transcription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-09-15 — tests for the Premiere-style words-per-caption cap
 * in [SubtitleSegmenter] ([SubtitleSegmenter.resegment] with
 * `maxWordsPerSegment`). The cap chunks every segment to at most N
 * words in speech order BEFORE the readability caps run, and the
 * merger never glues chunks back over the cap.
 */
class SubtitleSegmenterWordCountTest {

    private fun word(text: String, start: Long, end: Long) =
        TranscribedWord(text = text, startTimeMs = start, endTimeMs = end)

    private fun seg(words: List<TranscribedWord>): Subtitle {
        val text = words.joinToString(" ") { it.text }
        return Subtitle(
            id = "s",
            text = text,
            startTimeMs = words.first().startTimeMs,
            endTimeMs = words.last().endTimeMs,
            words = words
        )
    }

    @Test
    fun `six words with a 3-word cap split into two 3-word captions`() {
        val w = listOf(
            word("یک", 0, 300),
            word("دو", 300, 600),
            word("سه", 600, 900),
            word("چهار", 900, 1200),
            word("پنج", 1200, 1500),
            word("شش", 1500, 1800)
        )
        val t = Transcription(listOf(seg(w)), w)

        val out = SubtitleSegmenter.resegment(
            t, 42, 4000, 800, 200,
            maxReadingCps = 0f, pauseSplitMs = 0L,
            maxWordsPerSegment = 3
        )
        assertEquals(2, out.segments.size)
        assertEquals(3, out.segments[0].words.size)
        assertEquals(3, out.segments[1].words.size)
        // Speech order preserved, nothing invented or lost.
        assertEquals(w, out.segments.flatMap { it.words })
    }

    @Test
    fun `five words with a 3-word cap split into 3 plus 2`() {
        val w = listOf(
            word("یک", 0, 300),
            word("دو", 300, 600),
            word("سه", 600, 900),
            word("چهار", 900, 1200),
            word("پنج", 1200, 1500)
        )
        val t = Transcription(listOf(seg(w)), w)

        val out = SubtitleSegmenter.resegment(
            t, 42, 4000, 800, 200,
            maxReadingCps = 0f, pauseSplitMs = 0L,
            maxWordsPerSegment = 3
        )
        assertEquals(2, out.segments.size)
        assertEquals(3, out.segments[0].words.size)
        assertEquals(2, out.segments[1].words.size)
    }

    @Test
    fun `cap of zero disables the word count check`() {
        val w = listOf(
            word("یک", 0, 300),
            word("دو", 300, 600),
            word("سه", 600, 900),
            word("چهار", 900, 1200)
        )
        val t = Transcription(listOf(seg(w)), w)

        val out = SubtitleSegmenter.resegment(
            t, 42, 4000, 800, 200,
            maxReadingCps = 0f, pauseSplitMs = 0L,
            maxWordsPerSegment = 0
        )
        assertEquals(1, out.segments.size)
    }

    @Test
    fun `segment already within the cap is left unchanged`() {
        val w = listOf(word("سلام", 0, 400), word("دنیا", 400, 800))
        val t = Transcription(listOf(seg(w)), w)

        val out = SubtitleSegmenter.resegment(
            t, 42, 4000, 800, 200,
            maxReadingCps = 0f, pauseSplitMs = 0L,
            maxWordsPerSegment = 5
        )
        assertEquals(1, out.segments.size)
        // The untouched fast-path returns the SAME instance.
        assertTrue(out.segments[0] === t.segments[0])
    }

    @Test
    fun `chunk timings come from the first and last word they own`() {
        val w = listOf(
            word("یک", 100, 400),
            word("دو", 400, 700),
            word("سه", 700, 1000),
            word("چهار", 1000, 1300)
        )
        val t = Transcription(listOf(seg(w)), w)

        val out = SubtitleSegmenter.resegment(
            t, 42, 4000, 800, 200,
            maxReadingCps = 0f, pauseSplitMs = 0L,
            maxWordsPerSegment = 2
        )
        assertEquals(2, out.segments.size)
        // Chunk 1 owns [یک, دو] → 100..700; chunk 2 owns [سه, چهار]
        // → starts at "سه"'s 700 (NOT "چهار"'s 1000 — the chunk's
        // first word sets the start, the last word sets the end).
        assertEquals(100L, out.segments[0].startTimeMs)
        assertEquals(700L, out.segments[0].endTimeMs)
        assertEquals(700L, out.segments[1].startTimeMs)
        assertEquals(1300L, out.segments[1].endTimeMs)
    }

    @Test
    fun `merger never exceeds the word cap for short segments`() {
        // Four 1-word segments, each 300 ms (< the 800 ms merge
        // floor), tight gaps, tiny texts — without the word-count
        // guard the merger would glue them into one 4-word caption.
        val s1 = seg(listOf(word("یک", 0, 300)))
        val s2 = seg(listOf(word("دو", 300, 600)))
        val s3 = seg(listOf(word("سه", 600, 900)))
        val s4 = seg(listOf(word("چهار", 900, 1200)))
        val words = listOf(s1, s2, s3, s4).flatMap { it.words }
        val t = Transcription(listOf(s1, s2, s3, s4), words)

        val out = SubtitleSegmenter.resegment(
            t, 42, 4000, 800, 200,
            maxReadingCps = 0f, pauseSplitMs = 0L,
            maxWordsPerSegment = 2
        )
        // Every caption must carry at most 2 words.
        assertTrue(
            "a caption exceeded the word cap: ${out.segments.map { it.words.size }}",
            out.segments.all { it.words.size <= 2 }
        )
        assertEquals(words, out.segments.flatMap { it.words })
    }

    @Test
    fun `word cap applies after the pause split`() {
        // A 1500 ms pause naturally cuts the segment in half; each
        // half still has 3 words, and a 2-word cap must chunk each
        // half into 2 + 1 → four captions of at most 2 words.
        val w = listOf(
            word("یک", 0, 400),
            word("دو", 400, 800),
            word("سه", 800, 1200),
            word("چهار", 2700, 3100),
            word("پنج", 3100, 3500),
            word("شش", 3500, 3900)
        )
        val t = Transcription(listOf(seg(w)), w)

        val out = SubtitleSegmenter.resegment(
            t, 42, 4000, 800, 200,
            maxReadingCps = 0f, pauseSplitMs = 700L,
            maxWordsPerSegment = 2
        )
        // 2 halves × (2 + 1 words) = 4 captions.
        assertEquals(4, out.segments.size)
        assertTrue(out.segments.all { it.words.size <= 2 })
        assertEquals(w, out.segments.flatMap { it.words })
    }
}
