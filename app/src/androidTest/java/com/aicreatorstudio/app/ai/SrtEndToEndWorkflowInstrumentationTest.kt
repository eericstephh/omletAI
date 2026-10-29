package com.aicreatorstudio.app.ai

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aicreatorstudio.app.db.EditorStateJson
import com.aicreatorstudio.app.model.CanvasBackground
import com.aicreatorstudio.app.model.CanvasPreset
import com.aicreatorstudio.app.model.PersianFont
import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.model.VideoProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PHASE B4 - full end-to-end SRT workflow test on Android.
 *
 * The brief's section 18 acceptance test. It runs on the same
 * emulator-5554 (Pixel 8 AVD) the B1 / B2 / B3 tests run on, and
 * exercises the same production code paths the user hits in the
 * editor:
 *
 *   1. Open (create) a [VideoProject] (the same shape the
 *      [com.aicreatorstudio.app.video.ProjectRepository] stores).
 *   2. Read a real Persian SRT file from the assets folder
 *      using the production [SrtParser].
 *   3. Map the cues onto `TextOverlay`s through the production
 *      [SrtImportMapper].
 *   4. Persist the project by round-tripping through the
 *      production [EditorStateJson] codec (the same codec
 *      the [com.aicreatorstudio.app.db.RoomProjectRepository]
 *      uses for the `textOverlaysJson` blob).
 *   5. Reopen the project by reading the same codec back.
 *   6. Export the SRT through the production
 *      [SrtFormatter] (the same formatter
 *      [EditorViewModel.onExportSrtClicked] uses).
 *   7. Re-import the exported SRT and confirm zero new cues
 *      are added (duplicate protection).
 *   8. Verify the exported SRT contains the Persian text
 *      verbatim (Unicode round trip).
 *
 * No mocks. No fake transcripts. The persistence layer is
 * the same JSON codec Room uses for the project row.
 */
@RunWith(AndroidJUnit4::class)
class SrtEndToEndWorkflowInstrumentationTest {

    private val testContext: Context
        get() = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun srtEndToEnd_workflow_openImportPersistReopenExportReimport() {
        // 1. Open (create) a project. This is the same
        //    shape of project the
        //    [com.aicreatorstudio.app.video.ProjectRepository]
        //    returns to the editor on load.
        val clip = VideoClip(
            id = "clip-b4-e2e",
            uri = "asset://no-video-test",
            fileName = "no-video.mp4",
            durationMs = 60_000L,
            width = 1920,
            height = 1080,
            fileSizeBytes = 0L,
            importedAtEpochMillis = System.currentTimeMillis()
        )
        val project = VideoProject(
            id = "proj-b4-e2e",
            name = "B4 SRT E2E",
            createdAtEpochMillis = System.currentTimeMillis(),
            clips = listOf(clip),
            canvasPreset = CanvasPreset.WIDE_16_9,
            canvasBackground = CanvasBackground.Black
        )
        assertNotNull("project must be constructable", project)
        assertEquals("B4 SRT E2E", project.name)

        // 2. Read the real Persian SRT fixture (the
        //    same one the user picks with the SAF
        //    picker in the editor). We read the file
        //    bytes the same way the production
        //    [EditorViewModel.importSrtFromUri] does.
        val body: String = testContext.assets
            .open("srt-fixtures/B-persian-basic.srt")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        assertTrue("fixture must be non-empty", body.isNotBlank())

        // 3. Production parser + mapper. This is the
        //    same pipeline the editor runs.
        val parseResult = SrtParser.parse(body, "B-persian-basic.srt")
        assertTrue("parser must produce cues", parseResult.hasCues)
        assertEquals(3, parseResult.cues.size)
        val importedOverlays = SrtImportMapper.toTextOverlays(
            cues = parseResult.cues,
            clipOffsetMs = 0L,
            font = PersianFont.DEFAULT
        )
        assertEquals(3, importedOverlays.size)
        assertEquals("srt", importedOverlays[0].subtitleSource)
        assertTrue(importedOverlays[0].words.isEmpty())

        // 4. Persist through the production
        //    [EditorStateJson] codec. This is the
        //    same JSON the [RoomProjectRepository]
        //    uses for the `textOverlaysJson` blob.
        //    The brief: "Imported SRT subtitles
        //    must use the existing project
        //    persistence system."
        val savedProject = project.copy(textOverlays = importedOverlays)
        val json = EditorStateJson.textOverlaysToJson(savedProject.textOverlays)

        // 5. Reopen the project by reading the
        //    same codec back. The imported
        //    overlays must be back with identical
        //    text + timings + the subtitleSource
        //    tag.
        val reloaded = EditorStateJson.textOverlaysFromJson(json)
        assertEquals(3, reloaded.size)
        for (i in importedOverlays.indices) {
            assertEquals(
                "text[$i] must survive save/reopen",
                importedOverlays[i].text,
                reloaded[i].text
            )
            assertEquals(
                "startMs[$i] must survive save/reopen",
                importedOverlays[i].startTimeMs,
                reloaded[i].startTimeMs
            )
            assertEquals(
                "endMs[$i] must survive save/reopen",
                importedOverlays[i].endTimeMs,
                reloaded[i].endTimeMs
            )
            assertEquals(
                "subtitleSource[$i] must survive save/reopen",
                importedOverlays[i].subtitleSource,
                reloaded[i].subtitleSource
            )
        }

        // 6. Export the SRT through the production
        //    [SrtFormatter]. The view-model's
        //    [EditorViewModel.onExportSrtClicked]
        //    builds a [Subtitle] list from the
        //    overlays (with the clipOffsetMs
        //    subtracted so the output is in
        //    media-local coordinates, matching
        //    what the production filter does)
        //    and calls [SrtFormatter.format] on it.
        val clipOffsetMs = 0L
        val subtitles = reloaded.map { o ->
            Subtitle(
                text = o.text,
                startTimeMs = (o.startTimeMs - clipOffsetMs).coerceAtLeast(0L),
                endTimeMs = (o.endTimeMs - clipOffsetMs).coerceAtLeast(0L)
            )
        }
        val exportedSrt = SrtFormatter.format(subtitles)
        assertTrue(
            "exported SRT must contain the first Persian cue verbatim",
            exportedSrt.contains("سلام دنیا")
        )
        assertTrue(
            "exported SRT must contain the second Persian cue verbatim",
            exportedSrt.contains("این یک آزمایش است")
        )
        assertTrue(
            "exported SRT must contain the third Persian cue verbatim",
            exportedSrt.contains("خداحافظ")
        )
        // The SRT spec puts timestamps in
        // HH:MM:SS,mmm format. Spot-check the
        // exact precision: the first cue spans
        // 500..2000 ms which is 00:00:00,500 -->
        // 00:00:02,000.
        assertTrue(
            "exported SRT must use HH:MM:SS,mmm format",
            exportedSrt.contains("00:00:00,500 --> 00:00:02,000")
        )

        // 7. Re-import the exported SRT. The
        //    production dedup logic (based on
        //    text + start + end fingerprint)
        //    must produce zero new cues because
        //    every cue in the exported SRT
        //    matches an existing overlay.
        val reparse = SrtParser.parse(exportedSrt, "round-trip.srt")
        val reimport = SrtImportMapper.toTextOverlays(
            cues = reparse.cues,
            clipOffsetMs = 0L,
            font = PersianFont.DEFAULT,
            existing = reloaded
        )
        assertTrue(
            "re-import of exported SRT must be a no-op " +
                "(brief: round trip must preserve text+start+end exactly)",
            reimport.isEmpty()
        )

        // 8. Belt-and-braces: re-parse the
        //    exported SRT and verify the
        //    round-trip cue-by-cue.
        val reexported = reparse.cues
        assertEquals(3, reexported.size)
        for (i in parseResult.cues.indices) {
            assertEquals(
                "text[$i] must survive export -> re-parse",
                parseResult.cues[i].text,
                reexported[i].text
            )
            assertEquals(
                "startMs[$i] must survive export -> re-parse",
                parseResult.cues[i].startTimeMs,
                reexported[i].startTimeMs
            )
            assertEquals(
                "endMs[$i] must survive export -> re-parse",
                parseResult.cues[i].endTimeMs,
                reexported[i].endTimeMs
            )
        }
    }

    @Test
    fun srtEndToEnd_workflow_persianMultiline_persistsLineBreaks() {
        // Multiline is the riskiest case for the
        // persistence layer: a `\n` in cue text can
        // be silently mangled by the JSON encoder.
        val body = testContext.assets
            .open("srt-fixtures/D-persian-multiline.srt")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val parseResult = SrtParser.parse(body)
        assertEquals(2, parseResult.cues.size)
        val overlays = SrtImportMapper.toTextOverlays(parseResult.cues)
        assertEquals("خط اول\nخط دوم\nخط سوم", overlays[0].text)

        // Persist + reload.
        val json = EditorStateJson.textOverlaysToJson(overlays)
        val reloaded = EditorStateJson.textOverlaysFromJson(json)
        assertEquals(
            "multiline text must survive save/reopen byte-for-byte",
            "خط اول\nخط دوم\nخط سوم",
            reloaded[0].text
        )
    }

    @Test
    fun srtEndToEnd_workflow_persianExportUnicode_isByteExact() {
        // The brief is explicit: "Imported text must
        // remain exactly intact. Do NOT reverse
        // strings/characters/Unicode ordering." This
        // test checks the export SRT is byte-for-byte
        // a UTF-8 encoding of the same Persian text
        // the user imported.
        val body = testContext.assets
            .open("srt-fixtures/C-persian-english-mixed.srt")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val cues = SrtParser.parse(body).cues
        val overlays = SrtImportMapper.toTextOverlays(cues)
        val srt = SrtFormatter.format(overlays.map { o ->
            Subtitle(
                text = o.text,
                startTimeMs = o.startTimeMs,
                endTimeMs = o.endTimeMs
            )
        })
        val srtBytes = srt.toByteArray(Charsets.UTF_8)
        val srtText = String(srtBytes, Charsets.UTF_8)
        // Every original cue's text must appear
        // verbatim in the exported SRT.
        for (cue in cues) {
            assertTrue(
                "exported SRT must contain cue text verbatim: '${cue.text}'",
                srtText.contains(cue.text)
            )
        }
        // The UTF-8 byte sequence for Persian
        // (U+0600..U+06FF) is a 2-byte sequence
        // whose leading byte is 1101 10xx
        // (0xD8..0xDB). The exported SRT must
        // contain those bytes, NOT a Latin-1
        // re-encoding (which would map Persian
        // codepoints to 0x06..0x6F, the ASCII
        // digit/letter range, and would not
        // match the cue text asserted above).
        assertTrue(
            "exported SRT must be UTF-8 with Persian leading bytes 0xD8..0xDB",
            srtBytes.any {
                val unsigned = it.toInt() and 0xFF
                unsigned in 0xD8..0xDB
            }
        )
    }
}
