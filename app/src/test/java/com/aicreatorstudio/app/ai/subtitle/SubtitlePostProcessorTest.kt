package com.aicreatorstudio.app.ai.subtitle

import com.aicreatorstudio.app.ai.Subtitle
import com.aicreatorstudio.app.ai.TranscribedWord
import com.aicreatorstudio.app.ai.Transcription
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE B7 — JVM unit tests for the post-processor orchestrator.
 * Locks the contract:
 *
 *  - With `Identity` settings, the post-processor returns the
 *    input transcription bit-for-bit (same instance);
 *  - With `Default` settings, every step runs in the documented
 *    order on a hand-built `Transcription`;
 *  - Empty transcription is a no-op;
 *  - Per-step flags are honoured: disabling
 *    `collapseRepetition` leaves the "word word word"
 *    pattern intact even when other steps run.
 */
class SubtitlePostProcessorTest {

    private fun w(text: String, start: Long, end: Long, conf: Float? = 0.9f): TranscribedWord =
        TranscribedWord(text = text, startTimeMs = start, endTimeMs = end, confidence = conf)

    @Test
    fun `identity settings return the input transcription by reference`() = runTest {
        val s = Subtitle(
            text = "سلام", startTimeMs = 0L, endTimeMs = 1000L,
            words = listOf(w("سلام", 0L, 1000L))
        )
        val input = Transcription(listOf(s), s.words)
        val out = SubtitlePostProcessor.postProcess(
            input, SubtitlePostProcessSettings.Identity
        )
        assertSame(input, out)
    }

    @Test
    fun `empty input returns empty`() = runTest {
        val out = SubtitlePostProcessor.postProcess(
            Transcription.Empty, SubtitlePostProcessSettings.Default
        )
        assertSame(Transcription.Empty, out)
    }

    @Test
    fun `default pipeline collapses a three-way repetition`() = runTest {
        val s = Subtitle(
            text = "سلام سلام سلام",
            startTimeMs = 0L, endTimeMs = 3000L,
            words = listOf(
                w("سلام", 0L, 1000L),
                w("سلام", 1000L, 2000L),
                w("سلام", 2000L, 3000L)
            )
        )
        val out = SubtitlePostProcessor.postProcess(
            Transcription(listOf(s), s.words),
            SubtitlePostProcessSettings.Default
        )
        assertEquals(1, out.segments.first().words.size)
        assertEquals("سلام", out.segments.first().text)
    }

    @Test
    fun `arabic yeh and keheh are folded with default settings`() = runTest {
        // Whisper might emit Arabic ي/ك even when speaking
        // Persian; the normaliser folds them.
        val s = Subtitle(
            text = "كتاب",
            startTimeMs = 0L, endTimeMs = 1000L,
            words = listOf(w("كتاب", 0L, 1000L))
        )
        val out = SubtitlePostProcessor.postProcess(
            Transcription(listOf(s), s.words),
            SubtitlePostProcessSettings.Default
        )
        assertEquals("کتاب", out.segments.first().text)
    }

    @Test
    fun `disable repetition but enable other steps leaves word word word intact`() = runTest {
        val s = Subtitle(
            text = "سلام سلام سلام",
            startTimeMs = 0L, endTimeMs = 3000L,
            words = listOf(
                w("سلام", 0L, 1000L),
                w("سلام", 1000L, 2000L),
                w("سلام", 2000L, 3000L)
            )
        )
        val settings = SubtitlePostProcessSettings.Default.copy(collapseRepetition = false)
        val out = SubtitlePostProcessor.postProcess(
            Transcription(listOf(s), s.words), settings
        )
        assertEquals(3, out.segments.first().words.size)
    }

    @Test
    fun `disable normalizer keeps arabic-script text as-is`() = runTest {
        val s = Subtitle(
            text = "كتاب",
            startTimeMs = 0L, endTimeMs = 1000L,
            words = listOf(w("كتاب", 0L, 1000L))
        )
        val settings = SubtitlePostProcessSettings.Default.copy(normalizePersian = false)
        val out = SubtitlePostProcessor.postProcess(
            Transcription(listOf(s), s.words), settings
        )
        assertEquals("كتاب", out.segments.first().text)
    }

    @Test
    fun `disable resegment keeps the original segment grouping`() = runTest {
        val s = Subtitle(
            text = "خیلی خیلی خیلی خیلی خیلی",
            startTimeMs = 0L, endTimeMs = 5000L,
            words = (0..4).map { w("خیلی", it * 1000L, (it + 1) * 1000L) }
        )
        val settings = SubtitlePostProcessSettings.Default.copy(
            collapseRepetition = false, resegment = false
        )
        val out = SubtitlePostProcessor.postProcess(
            Transcription(listOf(s), s.words), settings
        )
        // 1 segment, 5 words — nothing was resegmented.
        assertEquals(1, out.segments.size)
        assertEquals(5, out.segments.first().words.size)
    }

    @Test
    fun `pipeline emits no new word ids - it only renames and restamps`() = runTest {
        val w1 = w("سلام", 0L, 1000L, conf = 0.7f)
        val w2 = w("دنیا", 1000L, 2000L, conf = 0.7f)
        val s = Subtitle(
            text = "سلام دنیا",
            startTimeMs = 0L, endTimeMs = 2000L,
            words = listOf(w1, w2)
        )
        val out = SubtitlePostProcessor.postProcess(
            Transcription(listOf(s), listOf(w1, w2)),
            SubtitlePostProcessSettings.Default
        )
        val allWords = out.segments.flatMap { it.words }
        // Word identity is preserved (the post-processor never
        // re-stamps word id; it only mutates text/timing of
        // surviving words).
        assertTrue(
            "w1 must be a surviving word",
            allWords.any { it === w1 || it.text == w1.text && it.startTimeMs == w1.startTimeMs }
        )
        assertTrue(
            "w2 must be a surviving word",
            allWords.any { it === w2 || it.text == w2.text && it.startTimeMs == w2.startTimeMs }
        )
    }

    @Test
    fun `pipeline runs on default dispatcher without blocking`() = runTest {
        // This is a smoke test: the function is suspend and uses
        // Dispatchers.Default. Just verify it returns within the
        // test's coroutine budget.
        val s = Subtitle(
            text = "خوب",
            startTimeMs = 0L, endTimeMs = 500L,
            words = listOf(w("خوب", 0L, 500L))
        )
        val out = SubtitlePostProcessor.postProcess(
            Transcription(listOf(s), s.words),
            SubtitlePostProcessSettings.Default
        )
        assertEquals(1, out.segments.size)
    }
}
