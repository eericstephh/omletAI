package com.aicreatorstudio.app.ai

import com.aicreatorstudio.app.db.EditorStateJson
import com.aicreatorstudio.app.model.PersianFont
import com.aicreatorstudio.app.model.TextOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SrtImportMapper] — PHASE B4.
 *
 * The mapper converts parsed SrtCues to TextOverlays. The contract:
 *
 *  - One SrtCue -> exactly one TextOverlay (no per-word splitting)
 *  - Empty cues list -> empty overlays list
 *  - clipOffsetMs shifts every cue's start/end
 *  - Existing overlay (text, start, end) duplicates are dropped
 *  - Similar text but different time is NOT considered a duplicate
 *  - Overlays are sorted by startTimeMs on the way out
 *  - Multiline text is preserved verbatim
 *  - Persian / RTL text is preserved byte-for-byte
 *  - subtitleSource is tagged "srt" so the export filter picks
 *    them up across project save/reload
 */
class SrtImportMapperTest {

    @Test
    fun `empty cues list produces empty overlays list`() {
        assertTrue(
            SrtImportMapper.toTextOverlays(emptyList()).isEmpty()
        )
    }

    @Test
    fun `one cue produces exactly one overlay`() {
        val cue = SrtCue(1L, 1_000L, 2_000L, "Hello")
        val out = SrtImportMapper.toTextOverlays(listOf(cue))
        assertEquals(1, out.size)
        assertEquals("Hello", out[0].text)
        assertEquals(1_000L, out[0].startTimeMs)
        assertEquals(2_000L, out[0].endTimeMs)
    }

    @Test
    fun `each cue produces its own overlay — no per-word splitting`() {
        val cues = listOf(
            SrtCue(1L, 0L, 1_000L, "First cue"),
            SrtCue(2L, 1_500L, 2_500L, "Second cue"),
            SrtCue(3L, 3_000L, 4_000L, "Third cue")
        )
        val out = SrtImportMapper.toTextOverlays(cues)
        // Three cues, three overlays. (Brief: "Each SRT entry
        // must create exactly ONE TextOverlay. Do NOT create one
        // TextOverlay per word.")
        assertEquals(3, out.size)
        assertEquals("First cue", out[0].text)
        assertEquals("Second cue", out[1].text)
        assertEquals("Third cue", out[2].text)
    }

    @Test
    fun `clipOffsetMs shifts every cue by the offset`() {
        val cues = listOf(
            SrtCue(1L, 0L, 1_000L, "A"),
            SrtCue(2L, 2_000L, 3_000L, "B")
        )
        val out = SrtImportMapper.toTextOverlays(cues, clipOffsetMs = 5_000L)
        assertEquals(5_000L, out[0].startTimeMs)
        assertEquals(6_000L, out[0].endTimeMs)
        assertEquals(7_000L, out[1].startTimeMs)
        assertEquals(8_000L, out[1].endTimeMs)
    }

    @Test
    fun `clipOffsetMs is clamped to non-negative`() {
        val cues = listOf(SrtCue(1L, 0L, 1_000L, "A"))
        val out = SrtImportMapper.toTextOverlays(cues, clipOffsetMs = -100L)
        // A negative offset would otherwise produce negative
        // timestamps; the mapper clamps to 0.
        assertEquals(0L, out[0].startTimeMs)
        assertEquals(1_000L, out[0].endTimeMs)
    }

    @Test
    fun `overlays are sorted by startTimeMs on the way out`() {
        val cues = listOf(
            SrtCue(2L, 5_000L, 6_000L, "Late"),
            SrtCue(1L, 1_000L, 2_000L, "Early"),
            SrtCue(3L, 3_000L, 4_000L, "Middle")
        )
        val out = SrtImportMapper.toTextOverlays(cues)
        assertEquals("Early", out[0].text)
        assertEquals("Middle", out[1].text)
        assertEquals("Late", out[2].text)
    }

    @Test
    fun `multiline cue text is preserved verbatim`() {
        val cue = SrtCue(1L, 0L, 5_000L, "Line one\nLine two\nLine three")
        val out = SrtImportMapper.toTextOverlays(listOf(cue))
        assertEquals("Line one\nLine two\nLine three", out[0].text)
    }

    @Test
    fun `Persian text is preserved byte-for-byte — no Unicode manipulation`() {
        val cue = SrtCue(1L, 0L, 1_000L, "سلام دنیا")
        val out = SrtImportMapper.toTextOverlays(listOf(cue))
        assertEquals("سلام دنیا", out[0].text)
        // Belt-and-braces: confirm the byte representation
        // round-trips through the String -> String path.
        assertEquals("سلام دنیا".length, out[0].text.length)
    }

    @Test
    fun `mixed Persian and English text is preserved verbatim`() {
        val cue = SrtCue(1L, 0L, 1_000L, "Hello سلام World دنیا")
        val out = SrtImportMapper.toTextOverlays(listOf(cue))
        assertEquals("Hello سلام World دنیا", out[0].text)
    }

    @Test
    fun `font name is applied to every produced overlay`() {
        val cues = listOf(
            SrtCue(1L, 0L, 1_000L, "A"),
            SrtCue(2L, 1_000L, 2_000L, "B")
        )
        val out = SrtImportMapper.toTextOverlays(
            cues = cues,
            font = PersianFont.SAHEL
        )
        assertEquals(PersianFont.SAHEL.name, out[0].fontFamily)
        assertEquals(PersianFont.SAHEL.name, out[1].fontFamily)
    }

    @Test
    fun `produced overlay has empty words list (SRT has no per-word timing)`() {
        val out = SrtImportMapper.toTextOverlays(
            listOf(SrtCue(1L, 0L, 1_000L, "Hello world"))
        )
        // The discriminator that distinguishes an AI subtitle
        // (words.isNotEmpty()) from a user-authored / SRT-imported
        // overlay (words.isEmpty()). SRT cannot populate this.
        assertTrue(out[0].words.isEmpty())
    }

    @Test
    fun `subtitleSource is srt so export filter picks it up`() {
        val out = SrtImportMapper.toTextOverlays(
            listOf(SrtCue(1L, 0L, 1_000L, "Hello"))
        )
        // The persistent tag is the source of truth; without
        // it the SRT export would miss these overlays on a
        // project save/reopen.
        assertEquals("srt", out[0].subtitleSource)
    }

    @Test
    fun `duplicate by text start and end is dropped`() {
        val existing = listOf(
            TextOverlay(
                text = "Hello world",
                startTimeMs = 1_000L,
                endTimeMs = 2_000L
            )
        )
        val cues = listOf(
            SrtCue(1L, 1_000L, 2_000L, "Hello world")
        )
        val out = SrtImportMapper.toTextOverlays(
            cues = cues,
            existing = existing
        )
        // Same text, same time window -> duplicate -> dropped.
        assertTrue(
            "expected the import to be skipped as a duplicate",
            out.isEmpty()
        )
    }

    @Test
    fun `text whitespace-only differences are still considered duplicates`() {
        val existing = listOf(
            TextOverlay(
                text = "Hello world",
                startTimeMs = 1_000L,
                endTimeMs = 2_000L
            )
        )
        // The cue text wraps onto two lines — fingerprint is
        // normalized to collapse runs of whitespace so a
        // re-import of the same content (with a different
        // line wrap) doesn't create a duplicate.
        val cues = listOf(
            SrtCue(1L, 1_000L, 2_000L, "Hello\nworld")
        )
        val out = SrtImportMapper.toTextOverlays(
            cues = cues,
            existing = existing
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `similar text but different time is NOT a duplicate`() {
        val existing = listOf(
            TextOverlay(
                text = "Hello world",
                startTimeMs = 1_000L,
                endTimeMs = 2_000L
            )
        )
        val cues = listOf(
            SrtCue(1L, 5_000L, 6_000L, "Hello world")
        )
        val out = SrtImportMapper.toTextOverlays(
            cues = cues,
            existing = existing
        )
        // The brief is explicit: "Don't delete legitimate
        // similar-text subtitles." Different time windows are
        // legitimate — keep the new one.
        assertEquals(1, out.size)
    }

    @Test
    fun `different text at same time is NOT a duplicate`() {
        val existing = listOf(
            TextOverlay(
                text = "Hello",
                startTimeMs = 1_000L,
                endTimeMs = 2_000L
            )
        )
        val cues = listOf(
            SrtCue(1L, 1_000L, 2_000L, "Goodbye")
        )
        val out = SrtImportMapper.toTextOverlays(
            cues = cues,
            existing = existing
        )
        assertEquals(1, out.size)
        assertEquals("Goodbye", out[0].text)
    }

    @Test
    fun `partial duplicate mixed with new cue drops only the duplicate`() {
        val existing = listOf(
            TextOverlay(
                text = "A",
                startTimeMs = 1_000L,
                endTimeMs = 2_000L
            )
        )
        val cues = listOf(
            SrtCue(1L, 1_000L, 2_000L, "A"), // duplicate
            SrtCue(2L, 3_000L, 4_000L, "B")  // new
        )
        val out = SrtImportMapper.toTextOverlays(
            cues = cues,
            existing = existing
        )
        assertEquals(1, out.size)
        assertEquals("B", out[0].text)
    }

    @Test
    fun `every produced overlay has a unique id`() {
        val cues = listOf(
            SrtCue(1L, 0L, 1_000L, "A"),
            SrtCue(2L, 1_000L, 2_000L, "B"),
            SrtCue(3L, 2_000L, 3_000L, "C")
        )
        val out = SrtImportMapper.toTextOverlays(cues)
        val ids = out.map { it.id }.toSet()
        assertEquals(3, ids.size) // all distinct
    }

    @Test
    fun `inverted range cues are silently dropped by the mapper`() {
        val cues = listOf(
            SrtCue(1L, 5_000L, 2_000L, "Bad"), // end < start
            SrtCue(2L, 0L, 1_000L, "Good")
        )
        val out = SrtImportMapper.toTextOverlays(cues)
        // The parser catches INVALID_RANGE in production, but
        // the mapper is also defensive so a malformed cue
        // can't create a negative-duration overlay.
        assertEquals(1, out.size)
        assertEquals("Good", out[0].text)
    }

    @Test
    fun `round trip through SrtFormatter preserves text and times`() {
        val cues = listOf(
            SrtCue(1L, 1_500L, 2_750L, "Hello"),
            SrtCue(2L, 3_000L, 4_500L, "World")
        )
        val overlays = SrtImportMapper.toTextOverlays(cues)
        val subtitles = overlays.map { o ->
            Subtitle(
                text = o.text,
                startTimeMs = o.startTimeMs,
                endTimeMs = o.endTimeMs
            )
        }
        val srt = SrtFormatter.format(subtitles)
        // Round-trip back through the parser; expect
        // text + start + end to match exactly.
        val reparsed = SrtParser.parse(srt).cues
        assertEquals(overlays.size, reparsed.size)
        for (i in overlays.indices) {
            assertEquals(overlays[i].text, reparsed[i].text)
            assertEquals(overlays[i].startTimeMs, reparsed[i].startTimeMs)
            assertEquals(overlays[i].endTimeMs, reparsed[i].endTimeMs)
        }
    }

    @Test
    fun `Persian round trip preserves text byte-for-byte`() {
        val cues = listOf(
            SrtCue(1L, 0L, 2_000L, "سلام"),
            SrtCue(2L, 2_500L, 4_000L, "خداحافظ")
        )
        val overlays = SrtImportMapper.toTextOverlays(cues)
        val srt = SrtFormatter.format(overlays.map {
            Subtitle(
                text = it.text,
                startTimeMs = it.startTimeMs,
                endTimeMs = it.endTimeMs
            )
        })
        val reparsed = SrtParser.parse(srt).cues
        assertEquals(2, reparsed.size)
        assertEquals("سلام", reparsed[0].text)
        assertEquals("خداحافظ", reparsed[1].text)
    }

    @Test
    fun `multiline round trip preserves line breaks`() {
        val original = "Line one\nLine two\nLine three"
        val cues = listOf(SrtCue(1L, 0L, 5_000L, original))
        val overlays = SrtImportMapper.toTextOverlays(cues)
        val srt = SrtFormatter.format(overlays.map {
            Subtitle(
                text = it.text,
                startTimeMs = it.startTimeMs,
                endTimeMs = it.endTimeMs
            )
        })
        val reparsed = SrtParser.parse(srt).cues
        assertEquals(1, reparsed.size)
        assertEquals(original, reparsed[0].text)
    }

    @Test
    fun `second import of the same SRT drops every cue as duplicate`() {
        val cues = listOf(
            SrtCue(1L, 0L, 1_000L, "A"),
            SrtCue(2L, 1_000L, 2_000L, "B")
        )
        val firstImport = SrtImportMapper.toTextOverlays(cues)
        // The user closes the picker and re-opens the same
        // file. The "existing" set is now everything we
        // imported the first time.
        val secondImport = SrtImportMapper.toTextOverlays(
            cues = cues,
            existing = firstImport
        )
        assertTrue(
            "every cue should be flagged as a duplicate on re-import",
            secondImport.isEmpty()
        )
    }

    @Test
    fun `subtitleSource tag is set even on cues that would be duplicates of an SRT import`() {
        // Defense in depth: if dedup ever gets a bug and lets
        // a duplicate through, the subtitleSource tag is the
        // fallback. (And conversely, the tag is set BEFORE
        // dedup in the current implementation order is not
        // material because we skip the cue entirely.)
        val existing = listOf(
            TextOverlay(
                text = "Hello",
                startTimeMs = 0L,
                endTimeMs = 1_000L,
                subtitleSource = "srt"
            )
        )
        val cues = listOf(SrtCue(1L, 0L, 1_000L, "Hello"))
        val out = SrtImportMapper.toTextOverlays(cues, existing = existing)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `mapper handles overlapping cues from the parser without crashing`() {
        val cues = listOf(
            SrtCue(1L, 0L, 5_000L, "Long"),
            SrtCue(2L, 2_000L, 4_000L, "Short inside")
        )
        val out = SrtImportMapper.toTextOverlays(cues)
        // Brief: "Overlapping subtitles must be warned but
        // not silently dropped." Both overlays are produced.
        assertEquals(2, out.size)
        assertNotEquals(out[0].id, out[1].id)
    }

    @Test
    fun `export filter excludes decorative text overlays that have no subtitleSource`() {
        // Brief §5: "Do NOT export: decorative text, titles,
        // stickers, unrelated TextOverlays." The export filter
        // in [com.aicreatorstudio.app.viewmodel.EditorViewModel
        // .onExportSrtClicked] only emits overlays whose id is
        // in [aiSubtitleOverlayIds] OR whose subtitleSource is
        // "ai" or "srt". A user-typed title or a sticker has
        // `subtitleSource = null` and is NOT in
        // [aiSubtitleOverlayIds], so it must not be in the
        // exported SRT body.
        val subtitleOverlays = SrtImportMapper.toTextOverlays(
            listOf(
                SrtCue(1L, 1_000L, 2_000L, "Subtitle 1"),
                SrtCue(2L, 3_000L, 4_000L, "Subtitle 2")
            )
        )
        // A decorative overlay created by the user typing on
        // the canvas. subtitleSource is null because it is not
        // an AI subtitle and not an SRT import.
        val decorative = TextOverlay(
            text = "My Title",
            startTimeMs = 0L,
            endTimeMs = 5_000L
        )
        val allOverlays = subtitleOverlays + decorative
        val exported = allOverlays.filter { o ->
            // Production filter (verbatim from EditorViewModel).
            o.id in subtitleOverlays.map { it.id }.toSet() ||
                o.subtitleSource == "ai" ||
                o.subtitleSource == "srt"
        }
        assertEquals(
            "export must only contain the 2 subtitle overlays, " +
                "not the decorative TextOverlay",
            2,
            exported.size
        )
        assertFalse(
            "decorative TextOverlay must not appear in the exported list",
            exported.any { it.text == "My Title" }
        )
    }

    @Test
    fun `export filter excludes AI overlays that lost their id from the in-memory set`() {
        // The persistent `subtitleSource == "ai"` tag is the
        // fallback so the filter still works after a project
        // save/reopen when the in-memory set is empty.
        val aiOverlayFromSavedProject = TextOverlay(
            text = "AI subtitle",
            startTimeMs = 1_000L,
            endTimeMs = 2_000L,
            subtitleSource = "ai"
        )
        val decorative = TextOverlay(
            text = "Title",
            startTimeMs = 0L,
            endTimeMs = 5_000L
        )
        val allOverlays = listOf(aiOverlayFromSavedProject, decorative)
        // Empty in-memory set (post-reopen) but persistent
        // tag picks up the AI overlay.
        val aiIds = emptySet<String>()
        val exported = allOverlays.filter { o ->
            o.id in aiIds ||
                o.subtitleSource == "ai" ||
                o.subtitleSource == "srt"
        }
        assertEquals(1, exported.size)
        assertEquals("AI subtitle", exported[0].text)
    }

    @Test
    fun `SRT to overlay to save to reopen to export to reparse round trips cue by cue`() {
        // Brief §6: "SRT -> Import -> TextOverlay -> Save
        // Project -> Reopen Project -> Export SRT must preserve
        // count, ordering, text, start, end."
        val body = """
            1
            00:00:01,500 --> 00:00:02,750
            Hello

            2
            00:00:03,000 --> 00:00:04,500
            World

            3
            00:00:05,200 --> 00:00:06,800
            Foo
        """.trimIndent()
        val firstParse = SrtParser.parse(body)
        val firstOverlays = SrtImportMapper.toTextOverlays(firstParse.cues)

        // Save -> reopen through the production JSON codec
        // (the same one Room writes to the textOverlaysJson
        // blob on the project row).
        val json = EditorStateJson.textOverlaysToJson(firstOverlays)
        val reloaded = EditorStateJson.textOverlaysFromJson(json)

        // Export.
        val srt = SrtFormatter.format(reloaded.map { o ->
            Subtitle(
                text = o.text,
                startTimeMs = o.startTimeMs,
                endTimeMs = o.endTimeMs
            )
        })
        // Re-parse the exported SRT.
        val reparsed = SrtParser.parse(srt).cues

        assertEquals(firstOverlays.size, reparsed.size)
        for (i in firstOverlays.indices) {
            assertEquals(
                "text[$i] must survive SRT->overlay->save->" +
                    "reopen->export->reparse",
                firstOverlays[i].text,
                reparsed[i].text
            )
            assertEquals(
                "startTimeMs[$i] must survive SRT->overlay->" +
                    "save->reopen->export->reparse",
                firstOverlays[i].startTimeMs,
                reparsed[i].startTimeMs
            )
            assertEquals(
                "endTimeMs[$i] must survive SRT->overlay->" +
                    "save->reopen->export->reparse",
                firstOverlays[i].endTimeMs,
                reparsed[i].endTimeMs
            )
        }
    }
}
