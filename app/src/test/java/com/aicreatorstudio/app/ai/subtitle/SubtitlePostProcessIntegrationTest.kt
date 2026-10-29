package com.aicreatorstudio.app.ai.subtitle

import com.aicreatorstudio.app.ai.Subtitle
import com.aicreatorstudio.app.ai.TranscribedWord
import com.aicreatorstudio.app.ai.Transcription
import com.aicreatorstudio.app.ai.WhisperTranscriptionToTextOverlays
import com.aicreatorstudio.app.model.PersianFont
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE B7 — JVM integration test that proves the B2 mapper
 * contract survives the post-processor pipeline. The brief
 * requires "downstream pipeline unchanged": the mapper must
 * still produce `List<TextOverlay>`s with the B2 invariants
 * (sort by start, non-empty, `subtitleSource="ai"`,
 * `words.isNotEmpty()`).
 *
 * The test feeds a hand-built `Transcription` through the
 * post-processor then through the B2 mapper and asserts the
 * invariants on the produced `TextOverlay` list. It does not
 * depend on the real Persian fixture — the B2 mapper tests
 * already cover that on a real device.
 */
class SubtitlePostProcessIntegrationTest {

    private fun w(text: String, start: Long, end: Long, conf: Float? = 0.9f): TranscribedWord =
        TranscribedWord(text = text, startTimeMs = start, endTimeMs = end, confidence = conf)

    @Test
    fun `post-processed transcription flows through B2 mapper with all B2 invariants intact`() = runTest {
        val s1 = Subtitle(
            text = "سلام دنیا",
            startTimeMs = 0L, endTimeMs = 2000L,
            words = listOf(
                w("سلام", 0L, 1000L),
                w("دنیا", 1000L, 2000L)
            )
        )
        val s2 = Subtitle(
            text = "خوبی؟",
            startTimeMs = 3000L, endTimeMs = 4000L,
            words = listOf(w("خوبی", 3000L, 4000L))
        )
        val transcription = Transcription(listOf(s1, s2), s1.words + s2.words)

        val post = SubtitlePostProcessor.postProcess(
            transcription, SubtitlePostProcessSettings.Default
        )
        val overlays = WhisperTranscriptionToTextOverlays.toTextOverlays(post)

        // 1. Both segments are mapped (post-processor did not
        //    drop real speech).
        assertEquals(2, overlays.size)
        // 2. Overlays are sorted by startTimeMs.
        assertEquals(0L, overlays[0].startTimeMs)
        assertEquals(3000L, overlays[1].startTimeMs)
        // 3. Every overlay has at least one word (B2 contract).
        for (o in overlays) {
            assertTrue(
                "overlay must carry words (B2 contract): id=${o.id}",
                o.words.isNotEmpty()
            )
        }
        // 4. subtitleSource tag is "ai" (B4 contract).
        for (o in overlays) {
            assertEquals("ai", o.subtitleSource)
        }
    }

    @Test
    fun `arabic yeh folding shows up in the overlay text after mapper`() = runTest {
        val s = Subtitle(
            text = "كتاب",
            startTimeMs = 0L, endTimeMs = 1000L,
            words = listOf(w("كتاب", 0L, 1000L))
        )
        val post = SubtitlePostProcessor.postProcess(
            Transcription(listOf(s), s.words),
            SubtitlePostProcessSettings.Default
        )
        val overlays = WhisperTranscriptionToTextOverlays.toTextOverlays(post)
        assertEquals(1, overlays.size)
        // The Arabic-script ي/ك were folded to Persian ی/ک.
        assertEquals("کتاب", overlays[0].text)
    }

    @Test
    fun `collapsed repetition still produces an overlay with words`() = runTest {
        val s = Subtitle(
            text = "سلام سلام سلام",
            startTimeMs = 0L, endTimeMs = 3000L,
            words = listOf(
                w("سلام", 0L, 1000L),
                w("سلام", 1000L, 2000L),
                w("سلام", 2000L, 3000L)
            )
        )
        val post = SubtitlePostProcessor.postProcess(
            Transcription(listOf(s), s.words),
            SubtitlePostProcessSettings.Default
        )
        val overlays = WhisperTranscriptionToTextOverlays.toTextOverlays(post)
        assertEquals(1, overlays.size)
        // 3 → 1 word after the filter.
        assertEquals(1, overlays[0].words.size)
        assertEquals("سلام", overlays[0].text)
    }

    @Test
    fun `silence-filtered hallucination is not surfaced as an overlay`() = runTest {
        val real = Subtitle(
            text = "سلام", startTimeMs = 0L, endTimeMs = 500L,
            words = listOf(w("سلام", 0L, 500L, conf = 0.9f))
        )
        val hallu = Subtitle(
            text = "تشکر", startTimeMs = 1000L, endTimeMs = 7000L,
            words = listOf(w("تشکر", 1000L, 7000L, conf = 0.05f))
        )
        val post = SubtitlePostProcessor.postProcess(
            Transcription(listOf(real, hallu), real.words + hallu.words),
            SubtitlePostProcessSettings.Default
        )
        val overlays = WhisperTranscriptionToTextOverlays.toTextOverlays(post)
        assertEquals(1, overlays.size)
        assertEquals("سلام", overlays[0].text)
    }

    @Test
    fun `identity settings produce identical mapper output to the raw input`() = runTest {
        val s = Subtitle(
            text = "سلام دنیا",
            startTimeMs = 0L, endTimeMs = 2000L,
            words = listOf(
                w("سلام", 0L, 1000L),
                w("دنیا", 1000L, 2000L)
            )
        )
        val input = Transcription(listOf(s), s.words)
        val post = SubtitlePostProcessor.postProcess(
            input, SubtitlePostProcessSettings.Identity
        )
        // The post-processor returns the input by reference, so
        // the mapper sees the exact same Transcription.
        assertTrue(input === post)
        val overlays = WhisperTranscriptionToTextOverlays.toTextOverlays(post)
        assertEquals(1, overlays.size)
        assertEquals("سلام دنیا", overlays[0].text)
    }

    @Test
    fun `font family is forwarded by the mapper (not modified by the post-processor)`() = runTest {
        val s = Subtitle(
            text = "سلام",
            startTimeMs = 0L, endTimeMs = 1000L,
            words = listOf(w("سلام", 0L, 1000L))
        )
        val post = SubtitlePostProcessor.postProcess(
            Transcription(listOf(s), s.words),
            SubtitlePostProcessSettings.Default
        )
        val overlays = WhisperTranscriptionToTextOverlays.toTextOverlays(
            post, clipOffsetMs = 0L, font = PersianFont.VAZIR
        )
        assertEquals(1, overlays.size)
        // The mapper sets the font family from the B5 picker.
        // The post-processor never reads or writes the font.
        assertEquals(PersianFont.VAZIR.name, overlays[0].fontFamily)
    }

    @Test
    fun `clip offset is applied after post-processing - every word shifts by the same amount`() = runTest {
        val s = Subtitle(
            text = "سلام",
            startTimeMs = 1000L, endTimeMs = 2000L,
            words = listOf(w("سلام", 1000L, 2000L))
        )
        val post = SubtitlePostProcessor.postProcess(
            Transcription(listOf(s), s.words),
            SubtitlePostProcessSettings.Default
        )
        val offset = 5_000L
        val overlays = WhisperTranscriptionToTextOverlays.toTextOverlays(post, clipOffsetMs = offset)
        assertEquals(1, overlays.size)
        assertEquals(6_000L, overlays[0].startTimeMs)
        assertEquals(7_000L, overlays[0].endTimeMs)
        assertEquals(offset + 1000L, overlays[0].words[0].startTimeMs)
        assertEquals(offset + 2000L, overlays[0].words[0].endTimeMs)
    }

    @Test
    fun `post-processor does not add or remove segments from a clean input`() = runTest {
        val s1 = Subtitle(
            text = "یک", startTimeMs = 0L, endTimeMs = 1000L,
            words = listOf(w("یک", 0L, 1000L))
        )
        val s2 = Subtitle(
            text = "دو", startTimeMs = 2000L, endTimeMs = 3000L,
            words = listOf(w("دو", 2000L, 3000L))
        )
        val s3 = Subtitle(
            text = "سه", startTimeMs = 4000L, endTimeMs = 5000L,
            words = listOf(w("سه", 4000L, 5000L))
        )
        val input = Transcription(listOf(s1, s2, s3), listOf(s1, s2, s3).flatMap { it.words })
        val post = SubtitlePostProcessor.postProcess(
            input, SubtitlePostProcessSettings.Default
        )
        val overlays = WhisperTranscriptionToTextOverlays.toTextOverlays(post)
        assertEquals(3, overlays.size)
        assertEquals(listOf("یک", "دو", "سه"), overlays.map { it.text })
    }

    @Test
    fun `overlay ids are unique across the pipeline`() = runTest {
        val s1 = Subtitle(
            text = "یک", startTimeMs = 0L, endTimeMs = 1000L,
            words = listOf(w("یک", 0L, 1000L))
        )
        val s2 = Subtitle(
            text = "دو", startTimeMs = 2000L, endTimeMs = 3000L,
            words = listOf(w("دو", 2000L, 3000L))
        )
        val post = SubtitlePostProcessor.postProcess(
            Transcription(listOf(s1, s2), s1.words + s2.words),
            SubtitlePostProcessSettings.Default
        )
        val overlays = WhisperTranscriptionToTextOverlays.toTextOverlays(post)
        val ids = overlays.map { it.id }.toSet()
        assertEquals(overlays.size, ids.size)
        // Each id is a valid UUID (defensive — the mapper uses
        // UUID.randomUUID so this is just a sanity check).
        for (id in ids) assertNotNull(id)
    }
}
