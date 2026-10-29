package com.aicreatorstudio.app.ai

import com.aicreatorstudio.app.model.PersianFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE B4 — round-trip integration test.
 *
 * This is the acceptance test the brief calls out: a SubRip file
 * imported via the editor's pipeline should, after one export
 * pass, produce an SRT body that, when re-imported, yields the
 * exact same text + start + end.
 *
 * The pipeline is:
 *
 *   SRT bytes
 *     -> SrtParser.parse            (string -> List<SrtCue>)
 *     -> SrtImportMapper.toTextOverlays  (List<SrtCue> -> List<TextOverlay>)
 *     -> SrtFormatter.format        (List<Subtitle> -> SRT bytes)
 *     -> SrtParser.parse            (SRT bytes -> List<SrtCue>)
 *
 * Each arrow is a contract this test pins down for a handful of
 * representative payloads.
 */
class SrtRoundTripTest {

    @Test
    fun `English single line round trip`() {
        val original = """
            1
            00:00:01,000 --> 00:00:02,500
            Hello world
        """.trimIndent()
        roundTrip(original, expectedCount = 1)
    }

    @Test
    fun `Persian round trip preserves text byte-for-byte`() {
        val original = """
            1
            00:00:00,500 --> 00:00:02,000
            سلام دنیا

            2
            00:00:02,500 --> 00:00:04,000
            خداحافظ
        """.trimIndent()
        roundTrip(original, expectedCount = 2)
    }

    @Test
    fun `multiline round trip preserves line breaks`() {
        val original = """
            1
            00:00:00,000 --> 00:00:05,000
            First line
            Second line
            Third line
        """.trimIndent()
        roundTrip(original, expectedCount = 1)
    }

    @Test
    fun `mixed Persian and English round trip`() {
        val original = """
            1
            00:00:01,000 --> 00:00:03,000
            Hello سلام World دنیا
        """.trimIndent()
        roundTrip(original, expectedCount = 1)
    }

    @Test
    fun `re-import round trip — second import of exported file produces zero new cues`() {
        // Build a project state with the first import.
        val cues = listOf(
            SrtCue(1L, 0L, 1_000L, "A"),
            SrtCue(2L, 1_500L, 2_500L, "B"),
            SrtCue(3L, 3_000L, 4_000L, "C")
        )
        val firstOverlays = SrtImportMapper.toTextOverlays(cues)
        // Export the project as SRT.
        val srt = SrtFormatter.format(firstOverlays.map { o ->
            Subtitle(
                text = o.text,
                startTimeMs = o.startTimeMs,
                endTimeMs = o.endTimeMs
            )
        })
        // Re-import the exported SRT; nothing should be added
        // because every cue is a (text, start, end) duplicate
        // of an existing overlay.
        val reimportCues = SrtParser.parse(srt).cues
        val secondOverlays = SrtImportMapper.toTextOverlays(
            cues = reimportCues,
            existing = firstOverlays
        )
        assertTrue(
            "second import must be a no-op",
            secondOverlays.isEmpty()
        )
    }

    @Test
    fun `clip offset is reversed on the way out — exported SRT is media-local`() {
        // Simulate a multi-clip project where the only clip
        // starts at timeline ms 10,000. SRT times are
        // expected to be in media-local coordinates, so the
        // export subtracts the offset and the re-import
        // (which sees clipOffsetMs=0 for a single-clip
        // project) lands on the right timeline positions.
        val cues = listOf(
            SrtCue(1L, 0L, 1_000L, "A"),
            SrtCue(2L, 2_000L, 3_000L, "B")
        )
        val overlays = SrtImportMapper.toTextOverlays(
            cues = cues,
            clipOffsetMs = 10_000L
        )
        // Overlays now sit at 10_000..11_000 and 12_000..13_000.
        val mediaLocal = overlays.map { o ->
            Subtitle(
                text = o.text,
                startTimeMs = o.startTimeMs - 10_000L,
                endTimeMs = o.endTimeMs - 10_000L
            )
        }
        val srt = SrtFormatter.format(mediaLocal)
        val reimported = SrtParser.parse(srt).cues
        // Re-imported as a fresh single-clip project (offset 0)
        // -> back to 0..1_000 and 2_000..3_000, matching the
        // original media-local times.
        assertEquals(2, reimported.size)
        assertEquals(0L, reimported[0].startTimeMs)
        assertEquals(1_000L, reimported[0].endTimeMs)
        assertEquals(2_000L, reimported[1].startTimeMs)
        assertEquals(3_000L, reimported[1].endTimeMs)
    }

    @Test
    fun `font applied at import time is preserved through round trip`() {
        // The font name round-trips with the TextOverlay JSON
        // separately from the SRT body; this test confirms
        // the SRT round trip itself doesn't depend on the
        // font and is therefore reusable across font choices.
        val cues = listOf(SrtCue(1L, 0L, 1_000L, "Hello"))
        val overlays = SrtImportMapper.toTextOverlays(
            cues = cues,
            font = PersianFont.SAHEL
        )
        assertEquals(PersianFont.SAHEL.name, overlays[0].fontFamily)
        val srt = SrtFormatter.format(overlays.map { o ->
            Subtitle(
                text = o.text,
                startTimeMs = o.startTimeMs,
                endTimeMs = o.endTimeMs
            )
        })
        val reimported = SrtParser.parse(srt).cues
        assertEquals(1, reimported.size)
        assertEquals("Hello", reimported[0].text)
        assertEquals(0L, reimported[0].startTimeMs)
        assertEquals(1_000L, reimported[0].endTimeMs)
    }

    // ---- helpers ----

    /**
     * Run the full import -> export -> re-import pipeline on
     * the given SRT body and confirm the text + start + end of
     * every cue is preserved exactly.
     */
    private fun roundTrip(body: String, expectedCount: Int) {
        val first = SrtParser.parse(body).cues
        assertEquals(expectedCount, first.size)
        val overlays = SrtImportMapper.toTextOverlays(first)
        val srt = SrtFormatter.format(overlays.map { o ->
            Subtitle(
                text = o.text,
                startTimeMs = o.startTimeMs,
                endTimeMs = o.endTimeMs
            )
        })
        val second = SrtParser.parse(srt).cues
        assertEquals(first.size, second.size)
        for (i in first.indices) {
            assertEquals("text[$i]", first[i].text, second[i].text)
            assertEquals("start[$i]", first[i].startTimeMs, second[i].startTimeMs)
            assertEquals("end[$i]", first[i].endTimeMs, second[i].endTimeMs)
        }
    }
}
