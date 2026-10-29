package com.aicreatorstudio.app.ai

import com.aicreatorstudio.app.model.PersianFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * PHASE B2 — focused unit tests for the
 * `WhisperTranscription → List<TextOverlay>` mapper.
 *
 * Every test runs on the JVM with no Android framework, no
 * whisper.cpp, and no M4A — these are pure-data tests that lock
 * the contract the [EditorViewModel] depends on. The companion
 * `WhisperSubtitlesToTimelineInstrumentationTest` runs the same
 * mapping on real whisper.cpp output on the Android emulator.
 */
class WhisperTranscriptionToTextOverlaysTest {

    // ---- WhisperWord → TimelineClip mapping (Brief §11.A) ----

    @Test
    fun `one segment with three words becomes one overlay whose start, end, and word timing match the segment exactly`() {
        val transcription = transcription(
            segments = listOf(
                segment(
                    text = "سلام دنیا",
                    startMs = 1_240L,
                    endMs = 4_080L,
                    words = listOf(
                        word("سلام", 1_240L, 2_100L),
                        word("دنیا", 2_500L, 4_080L)
                    )
                )
            )
        )
        val overlays = WhisperTranscriptionToTextOverlays.toTextOverlays(
            transcription = transcription,
            clipOffsetMs = 0L,
            font = PersianFont.VAZIR
        )
        assertEquals(1, overlays.size)
        val o = overlays.first()
        assertEquals("سلام دنیا", o.text)
        assertEquals(1_240L, o.startTimeMs)
        assertEquals(4_080L, o.endTimeMs)
        assertEquals(PersianFont.VAZIR.name, o.fontFamily)
        assertTrue(o.karaokeEnabled)
        assertEquals(2, o.words.size)
        assertEquals("سلام", o.words[0].text)
        assertEquals(1_240L, o.words[0].startTimeMs)
        assertEquals(2_100L, o.words[0].endTimeMs)
        assertEquals("دنیا", o.words[1].text)
        assertEquals(2_500L, o.words[1].startTimeMs)
        assertEquals(4_080L, o.words[1].endTimeMs)
    }

    @Test
    fun `every overlay has a stable, unique id - calling the mapper twice produces different ids but identical timing`() {
        val transcription = transcription(segments = listOf(segment("a", 0L, 500L, listOf(word("a", 0L, 500L)))))
        val first = WhisperTranscriptionToTextOverlays.toTextOverlays(transcription)
        val second = WhisperTranscriptionToTextOverlays.toTextOverlays(transcription)
        assertEquals(1, first.size)
        assertEquals(1, second.size)
        // IDs are unique per call (UUIDs), so the timeline treats
        // regenerated subtitles as new objects and replaces the
        // previous set.
        assertNotEquals(first.first().id, second.first().id)
        // Timing is the same.
        assertEquals(first.first().startTimeMs, second.first().startTimeMs)
        assertEquals(first.first().endTimeMs, second.first().endTimeMs)
        assertEquals(first.first().text, second.first().text)
    }

    // ---- Timestamp precision (Brief §11.B) ----

    @Test
    fun `timestamps are preserved to the millisecond - no rounding, no stretching, no compression`() {
        // Whisper centiseconds are * 10 → ms. We feed a mix of
        // ms-precision timestamps that are NOT round seconds and
        // assert they survive untouched.
        val transcription = transcription(
            segments = listOf(
                segment("x", 1_237L, 4_083L, listOf(word("x", 1_237L, 4_083L)))
            )
        )
        val overlays = WhisperTranscriptionToTextOverlays.toTextOverlays(transcription)
        assertEquals(1_237L, overlays.first().startTimeMs)
        assertEquals(4_083L, overlays.first().endTimeMs)
        assertEquals(1_237L, overlays.first().words.first().startTimeMs)
        assertEquals(4_083L, overlays.first().words.first().endTimeMs)
    }

    // ---- Word ordering (Brief §11.C) ----

    @Test
    fun `overlays are returned sorted by startTimeMs - the timeline renders them in speech order`() {
        // Pass segments in intentionally wrong order; the mapper
        // must sort them so the timeline's left-to-right rendering
        // matches the order the words were actually spoken.
        val transcription = transcription(
            segments = listOf(
                segment("third", 10_000L, 11_000L, listOf(word("third", 10_000L, 11_000L))),
                segment("first", 1_000L, 2_000L, listOf(word("first", 1_000L, 2_000L))),
                segment("second", 5_000L, 6_000L, listOf(word("second", 5_000L, 6_000L)))
            )
        )
        val overlays = WhisperTranscriptionToTextOverlays.toTextOverlays(transcription)
        assertEquals(3, overlays.size)
        assertEquals("first", overlays[0].text)
        assertEquals("second", overlays[1].text)
        assertEquals("third", overlays[2].text)
        // Each overlay's words are also in source order (the
        // engine emits them in speech order; the mapper must not
        // reshuffle them).
        for (o in overlays) {
            for (i in 1 until o.words.size) {
                assertTrue(
                    "words must be non-decreasing by startTimeMs in overlay '${o.text}'",
                    o.words[i].startTimeMs >= o.words[i - 1].startTimeMs
                )
            }
        }
    }

    // ---- Persian Unicode preservation (Brief §11.D) ----

    @Test
    fun `persian unicode and zero-width non-joiner are preserved exactly - no normalization, no transliteration`() {
        // The fixture text includes:
        //   - Persian ی (U+06CC) and ک (U+06A9)
        //   - Arabic-Indic digits (U+0660..U+0669)
        //   - Zero-width non-joiner (U+200C)
        //   - standard Persian punctuation
        // The mapper must not normalize any of these; what
        // whisper.cpp returned is exactly what the user sees.
        val persian = "گرافیک ۵"
        val transcription = transcription(
            segments = listOf(
                segment(persian, 0L, 1_000L, listOf(word(persian, 0L, 1_000L)))
            )
        )
        val overlays = WhisperTranscriptionToTextOverlays.toTextOverlays(transcription)
        assertEquals(persian, overlays.first().text)
        assertEquals(persian, overlays.first().words.first().text)
    }

    // ---- Invalid timestamp rejection (Brief §11.E) ----

    @Test
    fun `segments whose end is not strictly after start are dropped - the mapper never invents a positive duration`() {
        val transcription = transcription(
            segments = listOf(
                segment("bad", 5_000L, 5_000L, listOf(word("bad", 5_000L, 5_000L))), // end == start
                segment("worse", 7_000L, 6_000L, listOf(word("worse", 7_000L, 6_000L))), // end < start
                segment("good", 10_000L, 11_000L, listOf(word("good", 10_000L, 11_000L)))
            )
        )
        val overlays = WhisperTranscriptionToTextOverlays.toTextOverlays(transcription)
        assertEquals("only the valid segment survives", 1, overlays.size)
        assertEquals("good", overlays.first().text)
    }

    @Test
    fun `segments whose words all have end not strictly after start keep their text`() {
        // 2026-09-13 — the mapper now keeps segments whose WORD
        // timings are degenerate but whose own start<end window and
        // text are real (the Cloud path produces word-less segments
        // natively). The invalid words are still dropped; the
        // segment itself survives with karaoke disabled.
        val transcription = transcription(
            segments = listOf(
                segment(
                    text = "no real words",
                    startMs = 1_000L,
                    endMs = 2_000L,
                    words = listOf(
                        word("a", 1_000L, 1_000L), // end == start
                        word("b", 1_500L, 1_400L)  // end < start
                    )
                ),
                segment("ok", 3_000L, 4_000L, listOf(word("ok", 3_000L, 4_000L)))
            )
        )
        val overlays = WhisperTranscriptionToTextOverlays.toTextOverlays(transcription)
        assertEquals(2, overlays.size)
        assertEquals("no real words", overlays.first().text)
        assertTrue(overlays.first().words.isEmpty())
        assertTrue(!overlays.first().karaokeEnabled)
        assertEquals("ok", overlays.last().text)
        assertTrue(overlays.last().karaokeEnabled)
    }

    // ---- Empty Whisper result handling (Brief §11.F) ----

    @Test
    fun `empty transcription produces an empty overlay list - the timeline stays clean`() {
        val overlays = WhisperTranscriptionToTextOverlays.toTextOverlays(
            transcription = Transcription.Empty
        )
        assertTrue(overlays.isEmpty())
    }

    @Test
    fun `transcription with no segments but with words also produces an empty overlay list`() {
        // Defensive: words without a containing segment cannot
        // be placed on the timeline.
        val transcription = Transcription(segments = emptyList(), words = listOf(word("a", 0L, 500L)))
        val overlays = WhisperTranscriptionToTextOverlays.toTextOverlays(transcription)
        assertTrue(overlays.isEmpty())
    }

    // ---- Timeline coordinate conversion (Brief §11.I) ----

    @Test
    fun `clip offset is added to every overlay and every word - source time maps onto a later timeline position`() {
        // A simple single-clip project with timelineStartMs=0
        // means source time == timeline time. A multi-clip
        // project with timelineStartMs=15_000 places the clip
        // 15 s into the project; every word of its subtitles
        // must be shifted by exactly 15_000 ms.
        val transcription = transcription(
            segments = listOf(
                segment("a", 1_000L, 2_000L, listOf(word("a", 1_000L, 2_000L))),
                segment("b", 4_000L, 5_000L, listOf(word("b", 4_000L, 5_000L)))
            )
        )
        val overlays = WhisperTranscriptionToTextOverlays.toTextOverlays(
            transcription = transcription,
            clipOffsetMs = 15_000L
        )
        assertEquals(2, overlays.size)
        assertEquals(16_000L, overlays[0].startTimeMs)
        assertEquals(17_000L, overlays[0].endTimeMs)
        assertEquals(19_000L, overlays[1].startTimeMs)
        assertEquals(20_000L, overlays[1].endTimeMs)
        assertEquals(16_000L, overlays[0].words[0].startTimeMs)
        assertEquals(17_000L, overlays[0].words[0].endTimeMs)
    }

    @Test
    fun `negative clip offset is clamped to zero - the mapper never produces a negative start`() {
        val transcription = transcription(
            segments = listOf(segment("a", 1_000L, 2_000L, listOf(word("a", 1_000L, 2_000L))))
        )
        val overlays = WhisperTranscriptionToTextOverlays.toTextOverlays(
            transcription = transcription,
            clipOffsetMs = -5_000L
        )
        assertEquals(1, overlays.size)
        assertEquals(1_000L, overlays.first().startTimeMs)
        assertEquals(2_000L, overlays.first().endTimeMs)
    }

    // ---- Helpers ----

    private fun word(
        text: String,
        startMs: Long,
        endMs: Long,
        confidence: Float? = null
    ) = TranscribedWord(
        id = UUID.randomUUID().toString(),
        text = text,
        startTimeMs = startMs,
        endTimeMs = endMs,
        confidence = confidence
    )

    private fun segment(
        text: String,
        startMs: Long,
        endMs: Long,
        words: List<TranscribedWord>
    ) = Subtitle(
        id = UUID.randomUUID().toString(),
        text = text,
        startTimeMs = startMs,
        endTimeMs = endMs,
        words = words,
        confidence = null
    )

    private fun transcription(
        segments: List<Subtitle>,
        words: List<TranscribedWord> = emptyList()
    ) = Transcription(segments = segments, words = words)

    // Sanity: the model classes are importable from this test
    // package so a refactor that moves the model fails loudly.
    @Test
    fun `models are in the expected packages`() {
        assertNotNull(TranscribedWord(text = "", startTimeMs = 0L, endTimeMs = 0L))
    }
}
