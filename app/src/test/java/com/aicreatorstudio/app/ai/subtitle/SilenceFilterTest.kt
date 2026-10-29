package com.aicreatorstudio.app.ai.subtitle

import com.aicreatorstudio.app.ai.Subtitle
import com.aicreatorstudio.app.ai.TranscribedWord
import com.aicreatorstudio.app.ai.Transcription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE B7 — JVM unit tests for the silence / hallucination
 * filter. Locks the contract:
 *
 *  - Empty segment (no words) is dropped;
 *  - Single-word segment with low confidence AND long duration
 *    is dropped (the "thanks for watching" pattern);
 *  - Multi-word low-confidence segment is KEPT (real but
 *    uncertain speech);
 *  - Single-word normal-confidence short segment is KEPT;
 *  - Single-word null-confidence segment is kept unless the
 *    duration is more than 2× the longWordMs threshold.
 */
class SilenceFilterTest {

    private fun w(text: String, start: Long, end: Long, conf: Float? = 0.9f): TranscribedWord =
        TranscribedWord(text = text, startTimeMs = start, endTimeMs = end, confidence = conf)

    private fun seg(id: String, words: List<TranscribedWord>, text: String = words.firstOrNull()?.text ?: ""): Subtitle =
        Subtitle(
            id = id,
            text = text,
            startTimeMs = words.firstOrNull()?.startTimeMs ?: 0L,
            endTimeMs = words.lastOrNull()?.endTimeMs ?: 0L,
            words = words
        )

    @Test
    fun `empty segment is dropped`() {
        val s = seg("empty", emptyList())
        val out = SilenceFilter.filter(Transcription(listOf(s), emptyList()))
        assertTrue(out.segments.isEmpty())
    }

    @Test
    fun `single-word low-confidence long segment is dropped`() {
        // 1 word, conf 0.05 < 0.15, duration 6000ms > 5000ms.
        val s = seg("h1", listOf(w("تشکر", 0L, 6000L, conf = 0.05f)))
        val out = SilenceFilter.filter(Transcription(listOf(s), s.words))
        assertTrue(out.segments.isEmpty())
    }

    @Test
    fun `single-word low-confidence short segment is kept`() {
        // Real word, short duration, just low confidence.
        val s = seg("real1", listOf(w("سلام", 0L, 500L, conf = 0.05f)))
        val out = SilenceFilter.filter(Transcription(listOf(s), s.words))
        assertEquals(1, out.segments.size)
    }

    @Test
    fun `single-word high-confidence long segment is kept`() {
        // "خوب" is real and the model is sure of it; the
        // long duration is the user's hesitation, not silence.
        val s = seg("real2", listOf(w("خوب", 0L, 6000L, conf = 0.95f)))
        val out = SilenceFilter.filter(Transcription(listOf(s), s.words))
        assertEquals(1, out.segments.size)
    }

    @Test
    fun `multi-word low-confidence segment is kept`() {
        // A real Persian phrase rarely scores 0 confidence on
        // every word. Better to surface the segment and let the
        // user decide.
        val s = seg(
            "phrase",
            listOf(
                w("سلام", 0L, 500L, conf = 0.05f),
                w("دنیا", 500L, 1000L, conf = 0.05f)
            )
        )
        val out = SilenceFilter.filter(Transcription(listOf(s), s.words))
        assertEquals(1, out.segments.size)
    }

    @Test
    fun `single-word null-confidence short segment is kept`() {
        // whisper.cpp might emit null confidence for a real word.
        val s = seg("nc-short", listOf(w("سلام", 0L, 500L, conf = null)))
        val out = SilenceFilter.filter(Transcription(listOf(s), s.words))
        assertEquals(1, out.segments.size)
    }

    @Test
    fun `single-word null-confidence very long segment is dropped`() {
        // Null confidence + duration > 2× the longWordMs
        // threshold (default 5_000ms → 10_000ms) = drop.
        val s = seg("nc-long", listOf(w("تشکر", 0L, 15_000L, conf = null)))
        val out = SilenceFilter.filter(Transcription(listOf(s), s.words))
        assertTrue(out.segments.isEmpty())
    }

    @Test
    fun `mixed real and hallucinated segments keeps the real one`() {
        val real = seg("real", listOf(w("سلام", 0L, 500L, conf = 0.8f)))
        val hallu = seg("hallu", listOf(w("تشکر", 1000L, 7000L, conf = 0.05f)))
        val out = SilenceFilter.filter(
            Transcription(listOf(real, hallu), real.words + hallu.words)
        )
        assertEquals(1, out.segments.size)
        assertEquals("real", out.segments.first().id)
    }

    @Test
    fun `empty input returns empty output`() {
        val out = SilenceFilter.filter(Transcription.Empty)
        assertTrue(out.segments.isEmpty())
    }

    @Test
    fun `custom thresholds are honoured`() {
        // Tighter min confidence (0.5) + tighter long word (1000ms):
        // a 0.3-confidence 1500ms single-word segment is now
        // considered a hallucination.
        val s = seg("tight", listOf(w("خوب", 0L, 1500L, conf = 0.3f)))
        val out = SilenceFilter.filter(
            Transcription(listOf(s), s.words),
            minConfidence = 0.5f,
            longWordMs = 1_000L
        )
        assertTrue(out.segments.isEmpty())
    }

    @Test
    fun `transcription words list mirrors surviving segments`() {
        val real = seg("real", listOf(w("سلام", 0L, 500L, conf = 0.8f)))
        val hallu = seg("hallu", listOf(w("تشکر", 1000L, 7000L, conf = 0.05f)))
        val out = SilenceFilter.filter(
            Transcription(listOf(real, hallu), real.words + hallu.words)
        )
        assertEquals(out.segments.flatMap { it.words }, out.words)
        assertEquals(1, out.words.size)
    }
}
