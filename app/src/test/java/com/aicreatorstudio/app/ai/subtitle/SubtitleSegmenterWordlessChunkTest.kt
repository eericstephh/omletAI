package com.aicreatorstudio.app.ai.subtitle

import com.aicreatorstudio.app.ai.Subtitle
import com.aicreatorstudio.app.ai.TranscribedWord
import com.aicreatorstudio.app.ai.Transcription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-09-19 — word-less (cloud text-only) N-word chunking.
 *
 * The cloud worker returns segments WITHOUT per-word timestamps,
 * so the old `splitByWordCount` no-op left whole sentences as one
 * caption even when the user picked "۳ کلمه". These tests lock the
 * fixed behaviour:
 *
 *  - word-less segments chunk into N-word text groups with
 *    proportional time slices strictly inside the original window;
 *  - tokenization is whitespace-only: ZWNJ (نیم‌فاصله) never
 *    splits «می‌شود» into two words;
 *  - the word cap works even when the full resegment is OFF
 *    (word-count-only pass) and even on Identity settings.
 */
class SubtitleSegmenterWordlessChunkTest {

    private fun wordless(text: String, start: Long, end: Long) =
        Subtitle(text = text, startTimeMs = start, endTimeMs = end)

    private fun word(text: String, start: Long, end: Long) =
        TranscribedWord(text = text, startTimeMs = start, endTimeMs = end)

    @Test
    fun `zwnj is not a word boundary`() {
        assertEquals(listOf("می‌شود"), SubtitleSegmenter.persianWordTokens("می‌شود"))
        assertEquals(
            listOf("امروز", "می‌شود", "فردا"),
            SubtitleSegmenter.persianWordTokens("امروز  می‌شود\nفردا")
        )
    }

    @Test
    fun `word-less six tokens with a 3-word cap split into two captions`() {
        val t = Transcription(
            segments = listOf(wordless("یک دو سه چهار پنج شش", 0L, 6_000L)),
            words = emptyList()
        )
        val out = SubtitleSegmenter.resegment(
            t, 42, 4_000L, 800L, 200L,
            maxReadingCps = 0f, pauseSplitMs = 0L,
            maxWordsPerSegment = 3
        )
        assertEquals(2, out.segments.size)
        assertEquals("یک دو سه", out.segments[0].text)
        assertEquals("چهار پنج شش", out.segments[1].text)
        // Every chunk owns a real window inside the original one.
        for (s in out.segments) {
            assertTrue("end must be after start: $s", s.endTimeMs > s.startTimeMs)
            assertTrue(s.startTimeMs >= 0L)
            assertTrue(s.endTimeMs <= 6_000L)
        }
        // Coverage: first chunk starts at 0, last ends at 6000,
        // chunks are contiguous and monotonic.
        assertEquals(0L, out.segments[0].startTimeMs)
        assertEquals(6_000L, out.segments[1].endTimeMs)
        assertEquals(out.segments[0].endTimeMs, out.segments[1].startTimeMs)
        // Word-less chunks carry no invented words.
        assertTrue(out.segments.all { it.words.isEmpty() })
    }

    @Test
    fun `word-less chunking keeps zwnj words intact`() {
        val t = Transcription(
            segments = listOf(wordless("امروز می‌شود فردا می‌رویم", 1_000L, 5_000L)),
            words = emptyList()
        )
        val out = SubtitleSegmenter.resegment(
            t, 42, 4_000L, 800L, 200L,
            maxReadingCps = 0f, pauseSplitMs = 0L,
            maxWordsPerSegment = 2
        )
        assertEquals(2, out.segments.size)
        assertEquals("امروز می‌شود", out.segments[0].text)
        assertEquals("فردا می‌رویم", out.segments[1].text)
        assertTrue(out.segments[0].text.contains("می‌شود"))
        assertEquals(2, SubtitleSegmenter.persianWordTokens(out.segments[0].text).size)
        assertEquals(2, SubtitleSegmenter.persianWordTokens(out.segments[1].text).size)
    }

    @Test
    fun `word-less segment within the cap is left unchanged`() {
        val s = wordless("سلام دوستان", 0L, 2_500L)
        val t = Transcription(listOf(s), emptyList())
        val out = SubtitleSegmenter.resegment(
            t, 42, 4_000L, 800L, 200L,
            maxReadingCps = 0f, pauseSplitMs = 0L,
            maxWordsPerSegment = 5
        )
        assertEquals(1, out.segments.size)
        assertSame(s, out.segments[0])
    }

    @Test
    fun `word-less cap of zero disables chunking`() {
        val s = wordless("یک دو سه چهار پنج شش هفت", 0L, 7_000L)
        val t = Transcription(listOf(s), emptyList())
        val out = SubtitleSegmenter.resegment(
            t, 42, 4_000L, 800L, 200L,
            maxReadingCps = 0f, pauseSplitMs = 0L,
            maxWordsPerSegment = 0
        )
        assertEquals(1, out.segments.size)
        assertSame(s, out.segments[0])
    }

    @Test
    fun `chunkByWordCount handles mixed word and word-less segments`() {
        val w = listOf(
            word("یک", 0, 300),
            word("دو", 300, 600),
            word("سه", 600, 900),
            word("چهار", 900, 1200)
        )
        val wordSeg = Subtitle(
            id = "w", text = "یک دو سه چهار",
            startTimeMs = 0L, endTimeMs = 1_200L, words = w
        )
        val cloudSeg = wordless("پنج شش هفت هشت", 1_200L, 5_200L)
        val t = Transcription(listOf(wordSeg, cloudSeg), w)
        val out = SubtitleSegmenter.chunkByWordCount(t, 2)
        assertEquals(4, out.segments.size)
        assertEquals("یک دو", out.segments[0].text)
        assertEquals("سه چهار", out.segments[1].text)
        assertEquals("پنج شش", out.segments[2].text)
        assertEquals("هفت هشت", out.segments[3].text)
        // Word timings survive verbatim on the word path.
        assertEquals(w, out.segments.flatMap { it.words })
    }

    @Test
    fun `identity with a word cap is not identity and still chunks`() {
        val settings = SubtitlePostProcessSettings.Identity.copy(maxWordsPerSegment = 3)
        assertFalse(settings.isIdentity)
        val w = listOf(
            word("یک", 0, 300),
            word("دو", 300, 600),
            word("سه", 600, 900),
            word("چهار", 900, 1200),
            word("پنج", 1200, 1500),
            word("شش", 1500, 1800)
        )
        val s = Subtitle(
            id = "s", text = w.joinToString(" ") { it.text },
            startTimeMs = 0L, endTimeMs = 1_800L, words = w
        )
        val out = kotlinx.coroutines.runBlocking {
            SubtitlePostProcessor.postProcess(Transcription(listOf(s), w), settings)
        }
        assertEquals(2, out.segments.size)
        assertEquals(3, out.segments[0].words.size)
        assertEquals(3, out.segments[1].words.size)
        // Word-count-only: text untouched apart from the split.
        assertEquals("یک دو سه", out.segments[0].text)
        assertEquals("چهار پنج شش", out.segments[1].text)
    }

    @Test
    fun `word cap applies to word-less cloud text even with every step off`() {
        val settings = SubtitlePostProcessSettings(
            normalizePersian = false,
            collapseRepetition = false,
            filterSilence = false,
            resegment = false,
            correctTypos = false,
            maxWordsPerSegment = 3
        )
        val s = wordless("سلام دوستان امروز می‌خواهیم یک ویدیو بسازیم", 0L, 9_000L)
        val out = kotlinx.coroutines.runBlocking {
            SubtitlePostProcessor.postProcess(Transcription(listOf(s), emptyList()), settings)
        }
        assertTrue("expected chunking, got ${out.segments.size}", out.segments.size > 1)
        assertTrue(out.segments.all {
            SubtitleSegmenter.persianWordTokens(it.text).size <= 3
        })
        // No text lost or invented: rejoining reproduces the source.
        assertEquals(
            s.text,
            out.segments.joinToString(" ") { it.text }
        )
        for (seg in out.segments) {
            assertTrue(seg.endTimeMs > seg.startTimeMs)
        }
    }
}
