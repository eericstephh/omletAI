package com.aicreatorstudio.app.ai.subtitle

import com.aicreatorstudio.app.ai.Subtitle
import com.aicreatorstudio.app.ai.TranscribedWord
import com.aicreatorstudio.app.ai.Transcription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE B7 — JVM unit tests for the readability segmenter.
 * Locks the contract:
 *
 *  - Long segments (text > maxCharsPerLine) are split at word
 *    boundaries; each new segment's start/end come from the
 *    first / last word it owns;
 *  - Long segments (duration > maxSegmentDurationMs) are also
 *    split, even if the text is short;
 *  - Short segments are merged with their neighbour when the
 *    neighbour is within `mergeGapMs` AND the merged text still
 *    fits in `maxCharsPerLine`;
 *  - Word timestamps are preserved 1:1 (no word is invented
 *    and no word's timing is shifted);
 *  - The output `Transcription.words` is the union of the
 *    output segments' words.
 */
class SubtitleSegmenterTest {

    private fun w(text: String, start: Long, end: Long, conf: Float? = 0.9f): TranscribedWord =
        TranscribedWord(text = text, startTimeMs = start, endTimeMs = end, confidence = conf)

    private fun seg(
        id: String = "seg",
        text: String,
        start: Long,
        end: Long,
        words: List<TranscribedWord>
    ): Subtitle = Subtitle(id = id, text = text, startTimeMs = start, endTimeMs = end, words = words)

    @Test
    fun `single segment under both caps is left unchanged`() {
        val s = seg(text = "سلام دنیا", start = 0L, end = 1500L, words = listOf(
            w("سلام", 0L, 700L), w("دنیا", 700L, 1500L)
        ))
        val out = SubtitleSegmenter.resegment(
            Transcription(listOf(s), s.words),
            maxCharsPerLine = 42, maxSegmentDurationMs = 4000L,
            minSegmentDurationMs = 800L, mergeGapMs = 200L
        )
        assertEquals(1, out.segments.size)
        assertEquals(s, out.segments.first())
    }

    @Test
    fun `long text splits at word boundary respecting char cap`() {
        // 5 words × ~5 chars = 25 chars text, cap = 12 →
        // first split must happen at the first word boundary
        // that would push the bin over 12 chars.
        val words = listOf(
            w("سلام", 0L, 500L),
            w("خوبی", 500L, 1000L),
            w("دنیا", 1000L, 1500L),
            w("این", 1500L, 2000L),
            w("تست", 2000L, 2500L)
        )
        val s = seg(text = words.joinToString(" ") { it.text }, start = 0L, end = 2500L, words = words)
        val out = SubtitleSegmenter.resegment(
            Transcription(listOf(s), s.words),
            maxCharsPerLine = 12, maxSegmentDurationMs = 30_000L,
            minSegmentDurationMs = 0L, mergeGapMs = 0L
        )
        assertEquals(2, out.segments.size)
        // Total words preserved.
        val allWords = out.segments.flatMap { it.words }
        assertEquals(words, allWords)
    }

    @Test
    fun `long duration splits even when text is short`() {
        val words = listOf(
            w("یک", 0L, 3000L),
            w("دو", 3000L, 6000L)
        )
        val s = seg(text = "یک دو", start = 0L, end = 6000L, words = words)
        val out = SubtitleSegmenter.resegment(
            Transcription(listOf(s), s.words),
            maxCharsPerLine = 100, maxSegmentDurationMs = 3500L,
            minSegmentDurationMs = 0L, mergeGapMs = 0L
        )
        assertEquals(2, out.segments.size)
    }

    @Test
    fun `two short segments with small gap are merged`() {
        val s1 = seg(id = "a", text = "سلام", start = 0L, end = 400L, words = listOf(
            w("سلام", 0L, 400L)
        ))
        val s2 = seg(id = "b", text = "دنیا", start = 500L, end = 900L, words = listOf(
            w("دنیا", 500L, 900L)
        ))
        val out = SubtitleSegmenter.resegment(
            Transcription(listOf(s1, s2), s1.words + s2.words),
            maxCharsPerLine = 100, maxSegmentDurationMs = 30_000L,
            minSegmentDurationMs = 500L, mergeGapMs = 200L
        )
        assertEquals(1, out.segments.size)
        assertEquals(2, out.segments.first().words.size)
        // Merged segment inherits first segment's id.
        assertEquals("a", out.segments.first().id)
    }

    @Test
    fun `two short segments with large gap are not merged`() {
        val s1 = seg(id = "a", text = "سلام", start = 0L, end = 400L, words = listOf(
            w("سلام", 0L, 400L)
        ))
        val s2 = seg(id = "b", text = "دنیا", start = 5000L, end = 5400L, words = listOf(
            w("دنیا", 5000L, 5400L)
        ))
        val out = SubtitleSegmenter.resegment(
            Transcription(listOf(s1, s2), s1.words + s2.words),
            maxCharsPerLine = 100, maxSegmentDurationMs = 30_000L,
            minSegmentDurationMs = 500L, mergeGapMs = 200L
        )
        assertEquals(2, out.segments.size)
    }

    @Test
    fun `merge refuses when the merged text would exceed char cap`() {
        val s1 = seg(id = "a", text = "abcdefghij", start = 0L, end = 400L, words = listOf(
            w("abcdefghij", 0L, 400L)
        ))
        val s2 = seg(id = "b", text = "klmnopqrst", start = 500L, end = 900L, words = listOf(
            w("klmnopqrst", 500L, 900L)
        ))
        val out = SubtitleSegmenter.resegment(
            Transcription(listOf(s1, s2), s1.words + s2.words),
            maxCharsPerLine = 15, maxSegmentDurationMs = 30_000L,
            minSegmentDurationMs = 500L, mergeGapMs = 200L
        )
        // 10 + 1 (space) + 10 = 21 > 15 cap → do not merge.
        assertEquals(2, out.segments.size)
    }

    @Test
    fun `merged segment timing is min start to max end`() {
        val s1 = seg(id = "a", text = "سلام", start = 100L, end = 500L, words = listOf(
            w("سلام", 100L, 500L)
        ))
        val s2 = seg(id = "b", text = "دنیا", start = 600L, end = 1000L, words = listOf(
            w("دنیا", 600L, 1000L)
        ))
        val out = SubtitleSegmenter.resegment(
            Transcription(listOf(s1, s2), s1.words + s2.words),
            maxCharsPerLine = 100, maxSegmentDurationMs = 30_000L,
            minSegmentDurationMs = 600L, mergeGapMs = 200L
        )
        val merged = out.segments.single()
        assertEquals(100L, merged.startTimeMs)
        assertEquals(1000L, merged.endTimeMs)
    }

    @Test
    fun `empty transcription is left empty`() {
        val out = SubtitleSegmenter.resegment(
            Transcription.Empty,
            maxCharsPerLine = 42, maxSegmentDurationMs = 4000L,
            minSegmentDurationMs = 800L, mergeGapMs = 200L
        )
        assertTrue(out.segments.isEmpty())
    }

    @Test
    fun `no word is invented or lost across the round trip`() {
        val s = seg(
            text = "یک دو سه چهار پنج شش هفت هشت نه ده",
            start = 0L, end = 10_000L,
            words = (0..9).map { i ->
                val labels = listOf("یک", "دو", "سه", "چهار", "پنج", "شش", "هفت", "هشت", "نه", "ده")
                w(labels[i], i * 1000L, (i + 1) * 1000L)
            }
        )
        val out = SubtitleSegmenter.resegment(
            Transcription(listOf(s), s.words),
            maxCharsPerLine = 12, maxSegmentDurationMs = 3500L,
            minSegmentDurationMs = 0L, mergeGapMs = 0L
        )
        val allWords = out.segments.flatMap { it.words }
        assertEquals(s.words, allWords)
    }

    @Test
    fun `split segments all sit within the duration cap`() {
        val s = seg(
            text = "یک دو سه چهار پنج",
            start = 0L, end = 6000L,
            words = listOf(
                w("یک", 0L, 1500L), w("دو", 1500L, 3000L),
                w("سه", 3000L, 4500L), w("چهار", 4500L, 5000L), w("پنج", 5000L, 6000L)
            )
        )
        val out = SubtitleSegmenter.resegment(
            Transcription(listOf(s), s.words),
            maxCharsPerLine = 100, maxSegmentDurationMs = 3000L,
            minSegmentDurationMs = 0L, mergeGapMs = 0L
        )
        for (segment in out.segments) {
            assertTrue(
                "segment duration must be ≤ cap: " +
                    "${segment.endTimeMs - segment.startTimeMs} > 3000",
                segment.endTimeMs - segment.startTimeMs <= 3000L
            )
        }
    }
}