package com.aicreatorstudio.app.ai

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aicreatorstudio.app.db.EditorStateJson
import com.aicreatorstudio.app.model.PersianFont
import com.aicreatorstudio.app.model.SubtitleStyle
import com.aicreatorstudio.app.model.TextOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PHASE B5 — production subtitle styling. Real-device
 * instrumentation test on the same emulator-5554 (Pixel 8
 * AVD x86_64) that B1+B2+B3+B4 use.
 *
 * Exercises the full real-device path:
 *   - SRT parser (production)
 *   - SrtImportMapper (production)
 *   - EditorStateJson (production — same codec Room uses
 *     for `textOverlaysJson`)
 *   - SubtitleStyle.Bold and SubtitleStyle.Clean (the two
 *     presets the test applies)
 *   - SrtFormatter (production — verifies the export filter
 *     invariant from B4 still holds: style is internal; SRT
 *     export is unchanged)
 *
 * No mocks. No fake transcripts. The SRT fixture is the
 * same `B-persian-basic.srt` real Persian file from B4.
 */
@RunWith(AndroidJUnit4::class)
class SubtitleStyleInstrumentationTest {

    /**
     * Read an asset file by name. The brief is explicit that
     * instrumentation tests should pull from the test
     * instrumentation context's `assets` folder, not the host
     * app context, so a release-build apk doesn't have to
     * bundle every fixture.
     */
    private fun readAsset(name: String): String {
        val ctx: Context = InstrumentationRegistry.getInstrumentation().context
        return ctx.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    @Test
    fun applyBoldToAllSubtitles_persistsAndSurvivesReopen_textAndTimingUntouched() {
        // 1. Read the real Persian SRT fixture (same one B4
        //    uses).
        val body = readAsset("srt-fixtures/B-persian-basic.srt")
        assertTrue("fixture must be non-empty", body.isNotBlank())

        // 2. Production parser + mapper.
        val parseResult = SrtParser.parse(body, "B-persian-basic.srt")
        assertTrue("parser must produce cues", parseResult.hasCues)
        assertEquals(3, parseResult.cues.size)
        val imported = SrtImportMapper.toTextOverlays(
            cues = parseResult.cues,
            clipOffsetMs = 0L,
            font = PersianFont.DEFAULT
        )
        assertEquals(3, imported.size)
        // Every overlay has subtitleSource = "srt".
        for (o in imported) {
            assertEquals("srt", o.subtitleSource)
            assertNull("no style on import", o.subtitleStyle)
        }

        // 3. Apply SubtitleStyle.Bold to all 3 (the
        //    ViewModel's "apply to all subtitles" code path).
        val bolded = imported.map { it.copy(subtitleStyle = SubtitleStyle.Bold) }
        assertEquals(3, bolded.size)
        for (o in bolded) {
            assertNotNull(o.subtitleStyle)
            assertEquals(SubtitleStyle.Bold.fontSizeSp, o.subtitleStyle!!.fontSizeSp, 0.001f)
            assertEquals(SubtitleStyle.Bold.strokeWidthSp, o.subtitleStyle!!.strokeWidthSp, 0.001f)
            assertTrue(o.subtitleStyle!!.bold)
        }

        // 4. Persist through the production EditorStateJson
        //    codec (the same path Room uses for the
        //    `textOverlaysJson` blob).
        val json = EditorStateJson.textOverlaysToJson(bolded)
        val reloaded = EditorStateJson.textOverlaysFromJson(json)
        assertEquals(3, reloaded.size)

        // 5. Every overlay's style is now Bold (Bold is NOT
        //    Default, so it must be serialized).
        for (o in reloaded) {
            assertNotNull("style must survive save/reopen", o.subtitleStyle)
            assertEquals(32f, o.subtitleStyle!!.fontSizeSp, 0.001f)
            assertEquals(3f, o.subtitleStyle!!.strokeWidthSp, 0.001f)
            assertTrue(o.subtitleStyle!!.bold)
        }

        // 6. Text and timing are byte-for-byte unchanged.
        for (i in imported.indices) {
            assertEquals(
                "text[$i] must survive style + persistence",
                imported[i].text, reloaded[i].text
            )
            assertEquals(
                "startMs[$i] must survive style + persistence",
                imported[i].startTimeMs, reloaded[i].startTimeMs
            )
            assertEquals(
                "endMs[$i] must survive style + persistence",
                imported[i].endTimeMs, reloaded[i].endTimeMs
            )
        }
    }

    @Test
    fun applyBoldToAllThenCleanToOne_persistsIndividuallyOnAndroidProcess() {
        val body = readAsset("srt-fixtures/B-persian-basic.srt")
        val parseResult = SrtParser.parse(body)
        val imported = SrtImportMapper.toTextOverlays(parseResult.cues)

        // Step 1: Bold all 3.
        val bolded = imported.map { it.copy(subtitleStyle = SubtitleStyle.Bold) }
        // Step 2: Apply Clean to just the second one (the
        // ViewModel's "apply to selected" path).
        val mixed = bolded.mapIndexed { i, o ->
            if (i == 1) o.copy(subtitleStyle = SubtitleStyle.Clean) else o
        }
        // Step 3: Persist + reload.
        val json = EditorStateJson.textOverlaysToJson(mixed)
        val reloaded = EditorStateJson.textOverlaysFromJson(json)
        assertEquals(3, reloaded.size)
        // Bold on #0, Clean on #1, Bold on #2.
        assertEquals(32f, reloaded[0].subtitleStyle!!.fontSizeSp, 0.001f)
        assertTrue(reloaded[0].subtitleStyle!!.bold)
        // Clean: stroke disabled, shadow enabled (these are
        // the fields Clean actually changes — bold is left
        // at the default value of true).
        assertEquals(24f, reloaded[1].subtitleStyle!!.fontSizeSp, 0.001f)
        assertFalse("Clean disables stroke", reloaded[1].subtitleStyle!!.strokeEnabled)
        assertTrue("Clean enables shadow", reloaded[1].subtitleStyle!!.shadowEnabled)
        assertEquals(32f, reloaded[2].subtitleStyle!!.fontSizeSp, 0.001f)
        assertTrue(reloaded[2].subtitleStyle!!.bold)
    }

    @Test
    fun srtExportIsUnchangedAfterStyling_textAndTimingOnly() {
        // The B4 invariant: the SRT export contains ONLY
        // text + start + end. Styling must not leak into
        // the exported SRT.
        val body = readAsset("srt-fixtures/B-persian-basic.srt")
        val parseResult = SrtParser.parse(body)
        val imported = SrtImportMapper.toTextOverlays(parseResult.cues)

        // Apply a stylized preset that is *very* different
        // from the default (Shorts has its own font size,
        // background, position). If any field leaks into
        // the SRT output this test will fail.
        val styled = imported.map { it.copy(subtitleStyle = SubtitleStyle.Shorts) }
        // Re-round-trip to make sure the persistence layer
        // didn't accidentally drop the style.
        val persisted = EditorStateJson.textOverlaysFromJson(
            EditorStateJson.textOverlaysToJson(styled)
        )
        for (o in persisted) {
            assertNotNull("style must survive", o.subtitleStyle)
            assertEquals(36f, o.subtitleStyle!!.fontSizeSp, 0.001f)
        }

        // Export through the production SrtFormatter.
        val exportedSrt = SrtFormatter.format(persisted.map { o ->
            Subtitle(
                text = o.text,
                startTimeMs = o.startTimeMs,
                endTimeMs = o.endTimeMs
            )
        })
        // The exported SRT must still contain the original
        // Persian text verbatim and the original timestamps.
        assertTrue(exportedSrt.contains("سلام دنیا"))
        assertTrue(exportedSrt.contains("این یک آزمایش است"))
        assertTrue(exportedSrt.contains("خداحافظ"))
        assertTrue(exportedSrt.contains("00:00:00,500 --> 00:00:02,000"))
        // No font/style/color metadata should leak into the
        // SRT output. The SRT spec is text + HH:MM:SS,mmm
        // only. Spot-check that the SRT does not contain
        // "font", "color", "stroke", or "position" tokens.
        for (forbidden in listOf("font", "color", "stroke", "position")) {
            assertFalse(
                "SRT must not contain style field '$forbidden'",
                exportedSrt.contains(forbidden, ignoreCase = true)
            )
        }
    }

    @Test
    fun legacyBlobWithoutSubtitleStyle_decodesAsNullOnAndroidProcess() {
        // An older project that pre-dates B5 must still
        // load on a real Android process. The codec decodes
        // the missing key as null, the renderer falls back
        // to SubtitleStyle.Default at draw time.
        val legacyJson = """
            [{"id":"legacy-1","text":"legacy","startMs":0,"endMs":1000,
              "x":0.5,"y":0.5,"colorArgb":-1,"fontSizeSp":24.0,
              "fontWeight":400,"bgAlpha":0.5,"rotation":0.0,
              "fontFamily":"DEFAULT","karaoke":true,"words":[],
              "subtitleSource":"srt"}]
        """.trimIndent()
        val reloaded = EditorStateJson.textOverlaysFromJson(legacyJson)
        assertEquals(1, reloaded.size)
        assertNull(reloaded[0].subtitleStyle)
        // The renderer uses SubtitleStyle.Default when null;
        // verify the default values are sane on a real
        // Android process.
        val d = SubtitleStyle.Default
        assertEquals(24f, d.fontSizeSp, 0.001f)
        assertTrue(d.bold)
        assertTrue(d.strokeEnabled)
        assertEquals(0.85f, d.positionY, 0.001f)
    }
}
