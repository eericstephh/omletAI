package com.aicreatorstudio.app.ai.subtitle

import com.aicreatorstudio.app.ai.Subtitle
import com.aicreatorstudio.app.ai.TranscribedWord
import com.aicreatorstudio.app.ai.Transcription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE B7 — JVM unit tests for the repetition-collapse filter.
 * Locks the contract:
 *
 *  - 3+ consecutive identical words (normalised) collapse to 1;
 *  - 2 consecutive identical words are left alone (real discourse);
 *  - The kept word keeps its `startTimeMs` / `endTimeMs` /
 *    `confidence` / `id`;
 *  - The collapsed segment gets a new `id` because its word
 *    set changed;
 *  - The Transcription.words list mirrors the surviving
 *    segments (no orphan words).
 */
class RepetitionFilterTest {

    private fun w(text: String, start: Long, end: Long, conf: Float? = 0.9f): TranscribedWord =
        TranscribedWord(text = text, startTimeMs = start, endTimeMs = end, confidence = conf)

    @Test
    fun `three consecutive identical words collapse to one`() {
        val seg = Subtitle(
            text = "سلام سلام سلام",
            startTimeMs = 0L,
            endTimeMs = 3000L,
            words = listOf(
                w("سلام", 0L, 1000L),
                w("سلام", 1000L, 2000L),
                w("سلام", 2000L, 3000L)
            )
        )
        val out = RepetitionFilter.filter(Transcription(listOf(seg), seg.words))
        assertEquals(1, out.segments.size)
        val outSeg = out.segments.first()
        assertEquals(1, outSeg.words.size)
        assertEquals("سلام", outSeg.text)
    }

    @Test
    fun `kept word preserves its original timestamps and confidence`() {
        val seg = Subtitle(
            text = "سلام سلام سلام",
            startTimeMs = 0L,
            endTimeMs = 3000L,
            words = listOf(
                w("سلام", 100L, 500L, conf = 0.42f),
                w("سلام", 500L, 900L, conf = 0.7f),
                w("سلام", 900L, 1300L, conf = 0.99f)
            )
        )
        val out = RepetitionFilter.filter(Transcription(listOf(seg), seg.words))
        val kept = out.segments.first().words.single()
        assertEquals(100L, kept.startTimeMs)
        assertEquals(500L, kept.endTimeMs)
        assertEquals(0.42f, kept.confidence!!, 0.0001f)
    }

    @Test
    fun `two consecutive identical words are not collapsed`() {
        // "آره آره" is natural discourse; the brief says
        // "Do not remove legitimate repeated words."
        val seg = Subtitle(
            text = "آره آره",
            startTimeMs = 0L,
            endTimeMs = 2000L,
            words = listOf(
                w("آره", 0L, 1000L),
                w("آره", 1000L, 2000L)
            )
        )
        val out = RepetitionFilter.filter(Transcription(listOf(seg), seg.words))
        assertEquals(seg, out.segments.first())
    }

    @Test
    fun `zwnj-different forms of the same word still collapse`() {
        // "می‌روم" with a ZWNJ between می and روم. The
        // normaliser preserves the ZWNJ. All three are equal
        // post-normalise so the run length is 3 → collapse.
        val seg = Subtitle(
            text = "می‌روم می‌روم می‌روم",
            startTimeMs = 0L,
            endTimeMs = 3000L,
            words = listOf(
                w("می‌روم", 0L, 1000L),
                w("می‌روم", 1000L, 2000L),
                w("می‌روم", 2000L, 3000L)
            )
        )
        val out = RepetitionFilter.filter(Transcription(listOf(seg), seg.words))
        assertEquals(1, out.segments.first().words.size)
        assertEquals("می‌روم", out.segments.first().words.single().text)
    }

    @Test
    fun `mixed non-repeating segment is left untouched`() {
        val seg = Subtitle(
            text = "خیلی خوب",
            startTimeMs = 0L,
            endTimeMs = 2000L,
            words = listOf(
                w("خیلی", 0L, 1000L),
                w("خوب", 1000L, 2000L)
            )
        )
        val out = RepetitionFilter.filter(Transcription(listOf(seg), seg.words))
        assertEquals(seg, out.segments.first())
    }

    @Test
    fun `two non-adjacent repetitions are not collapsed`() {
        // "سلام ... سلام ... سلام" — non-adjacent, real
        // discourse pattern, must be left alone.
        val seg = Subtitle(
            text = "سلام خوبی سلام عالیه سلام",
            startTimeMs = 0L,
            endTimeMs = 4000L,
            words = listOf(
                w("سلام", 0L, 500L),
                w("خوبی", 500L, 1500L),
                w("سلام", 1500L, 2000L),
                w("عالیه", 2000L, 3500L),
                w("سلام", 3500L, 4000L)
            )
        )
        val out = RepetitionFilter.filter(Transcription(listOf(seg), seg.words))
        assertEquals(5, out.segments.first().words.size)
    }

    @Test
    fun `arabic-persian fold does not create false repetitions`() {
        // Whisper might emit "ي" then "ی"; they are the same
        // word after normalisation. The filter MUST NOT
        // collapse two of them (run is only 2).
        val seg = Subtitle(
            text = "ي ی",
            startTimeMs = 0L,
            endTimeMs = 2000L,
            words = listOf(
                w("ي", 0L, 1000L),
                w("ی", 1000L, 2000L)
            )
        )
        val out = RepetitionFilter.filter(Transcription(listOf(seg), seg.words))
        assertEquals(2, out.segments.first().words.size)
    }

    @Test
    fun `collapsed segment gets a new id`() {
        val seg = Subtitle(
            id = "fixed-id-1234",
            text = "سلام سلام سلام",
            startTimeMs = 0L,
            endTimeMs = 3000L,
            words = listOf(
                w("سلام", 0L, 1000L),
                w("سلام", 1000L, 2000L),
                w("سلام", 2000L, 3000L)
            )
        )
        val out = RepetitionFilter.filter(Transcription(listOf(seg), seg.words))
        assertNotEquals("fixed-id-1234", out.segments.first().id)
    }

    @Test
    fun `untouched segment keeps its id`() {
        val seg = Subtitle(
            id = "untouched",
            text = "خوب",
            startTimeMs = 0L,
            endTimeMs = 1000L,
            words = listOf(w("خوب", 0L, 1000L))
        )
        val out = RepetitionFilter.filter(Transcription(listOf(seg), seg.words))
        assertEquals("untouched", out.segments.first().id)
    }

    @Test
    fun `transcription words list mirrors surviving segments`() {
        val seg = Subtitle(
            text = "سلام سلام سلام",
            startTimeMs = 0L,
            endTimeMs = 3000L,
            words = listOf(
                w("سلام", 0L, 1000L),
                w("سلام", 1000L, 2000L),
                w("سلام", 2000L, 3000L)
            )
        )
        val out = RepetitionFilter.filter(Transcription(listOf(seg), seg.words))
        assertEquals(out.segments.flatMap { it.words }, out.words)
        assertEquals(1, out.words.size)
    }

    @Test
    fun `empty transcription returns empty transcription`() {
        val out = RepetitionFilter.filter(Transcription.Empty)
        assertTrue(out.isEmpty)
    }

    @Test
    fun `single-word segments are never collapsed (below the 3-word threshold)`() {
        // Pathological case: one segment with one word, repeated
        // three times across segments. The filter operates
        // per-segment, so the "3 consecutive words" rule does
        // not apply across segments. Each single-word segment
        // is left alone.
        val seg1 = Subtitle(
            text = "سلام", startTimeMs = 0L, endTimeMs = 1000L,
            words = listOf(w("سلام", 0L, 1000L))
        )
        val seg2 = Subtitle(
            text = "سلام", startTimeMs = 1000L, endTimeMs = 2000L,
            words = listOf(w("سلام", 1000L, 2000L))
        )
        val seg3 = Subtitle(
            text = "سلام", startTimeMs = 2000L, endTimeMs = 3000L,
            words = listOf(w("سلام", 2000L, 3000L))
        )
        val input = Transcription(listOf(seg1, seg2, seg3), listOf(seg1, seg2, seg3).flatMap { it.words })
        val out = RepetitionFilter.filter(input)
        assertEquals(3, out.segments.size)
        assertEquals(1, out.segments[0].words.size)
        assertEquals(1, out.segments[1].words.size)
        assertEquals(1, out.segments[2].words.size)
    }
}
