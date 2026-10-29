package com.aicreatorstudio.app.ai.subtitle

import com.aicreatorstudio.app.ai.Subtitle
import com.aicreatorstudio.app.ai.TranscribedWord
import com.aicreatorstudio.app.ai.Transcription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-09-09 — tests for the two new readability signals in
 * [SubtitleSegmenter]: reading-speed (CPS) splitting and
 * pause-aware splitting. Written in a separate file purely so the
 * Persian word fixtures stay byte-clean.
 */
class SubtitleSegmenterCpsPauseTest {

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

    // ---- reading speed ----

    @Test
    fun `segment denser than max CPS is split`() {
        // 33 chars over 2.5 s = 13.2 CPS → with a 6 CPS cap it must split.
        val w = listOf(
            word("این", 0, 200),
            word("جمله", 200, 400),
            word("بسیار", 400, 600),
            word("سریع", 600, 2500)
        )
        val t = Transcription(listOf(seg(w)), w)

        val untouched = SubtitleSegmenter.resegment(
            t, 42, 4000, 800, 200, maxReadingCps = 0f
        )
        assertEquals(1, untouched.segments.size)

        val split = SubtitleSegmenter.resegment(
            t, 42, 4000, 800, 200, maxReadingCps = 6f
        )
        assertTrue("expected a CPS split", split.segments.size >= 2)
        // No word invented, no word lost.
        assertEquals(w, split.segments.flatMap { it.words })
    }

    @Test
    fun `segment already under max CPS is left unchanged`() {
        val w = listOf(word("سلام", 0, 1000), word("دنیا", 1000, 2000))
        val t = Transcription(listOf(seg(w)), w)
        val out = SubtitleSegmenter.resegment(
            t, 42, 4000, 800, 200, maxReadingCps = 15f
        )
        assertEquals(1, out.segments.size)
    }

    // ---- pause-aware ----

    @Test
    fun `segment splits exactly at a long inter-word pause`() {
        val w = listOf(
            word("اول", 0, 500),
            word("دوم", 500, 1000),
            word("سوم", 2500, 3000) // 1500 ms pause before this word
        )
        val t = Transcription(listOf(seg(w)), w)
        val out = SubtitleSegmenter.resegment(
            t, 42, 4000, 800, 200,
            maxReadingCps = 0f, pauseSplitMs = 1000L
        )
        assertEquals(2, out.segments.size)
        assertEquals(listOf("اول", "دوم"), out.segments[0].words.map { it.text })
        assertEquals(listOf("سوم"), out.segments[1].words.map { it.text })
        // The pause belongs to NEITHER caption.
        assertEquals(1000L, out.segments[0].endTimeMs)
        assertEquals(2500L, out.segments[1].startTimeMs)
    }

    @Test
    fun `short pause does not split the segment`() {
        val w = listOf(word("اول", 0, 500), word("دوم", 600, 1100))
        val t = Transcription(listOf(seg(w)), w)
        val out = SubtitleSegmenter.resegment(
            t, 42, 4000, 800, 200,
            maxReadingCps = 0f, pauseSplitMs = 700L
        )
        assertEquals(1, out.segments.size)
    }
}
