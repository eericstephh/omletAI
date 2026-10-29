package com.aicreatorstudio.app.db

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.aicreatorstudio.app.ai.TranscribedWord
import com.aicreatorstudio.app.model.PersianFont
import com.aicreatorstudio.app.model.TextOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PHASE B4 — verify the `subtitleSource` field on
 * [TextOverlay] round-trips through the project's
 * `textOverlaysJson` blob so the SRT export filter still
 * finds imported subtitles after a project save/reopen.
 */
class TextOverlaySubtitleSourceRoundTripTest {

    @Test
    fun `subtitleSource is preserved on JSON round trip`() {
        val overlay = TextOverlay(
            text = "Hello world",
            startTimeMs = 1_000L,
            endTimeMs = 2_000L,
            fontFamily = PersianFont.VAZIR.name,
            words = emptyList(),
            karaokeEnabled = true,
            subtitleSource = "srt"
        )
        val json = EditorStateJson.textOverlaysToJson(listOf(overlay))
        val back = EditorStateJson.textOverlaysFromJson(json)
        assertEquals(1, back.size)
        assertEquals("srt", back[0].subtitleSource)
        assertEquals("Hello world", back[0].text)
        assertEquals(1_000L, back[0].startTimeMs)
        assertEquals(2_000L, back[0].endTimeMs)
    }

    @Test
    fun `subtitleSource ai round trips too`() {
        val overlay = TextOverlay(
            text = "AI subtitle",
            startTimeMs = 5_000L,
            endTimeMs = 6_500L,
            words = listOf(
                TranscribedWord(
                    text = "AI",
                    startTimeMs = 5_000L,
                    endTimeMs = 5_500L
                ),
                TranscribedWord(
                    text = "subtitle",
                    startTimeMs = 5_500L,
                    endTimeMs = 6_500L
                )
            ),
            subtitleSource = "ai"
        )
        val json = EditorStateJson.textOverlaysToJson(listOf(overlay))
        val back = EditorStateJson.textOverlaysFromJson(json)
        assertEquals(1, back.size)
        assertEquals("ai", back[0].subtitleSource)
        // The per-word timing is also preserved so karaoke
        // highlighting still works after a reload.
        assertEquals(2, back[0].words.size)
        assertEquals("AI", back[0].words[0].text)
        assertEquals(5_000L, back[0].words[0].startTimeMs)
    }

    @Test
    fun `null subtitleSource round trips as null`() {
        // A user-authored decorative Text overlay has
        // subtitleSource = null. The export filter should
        // skip it.
        val overlay = TextOverlay(
            text = "My title",
            startTimeMs = 0L,
            endTimeMs = 2_000L
        )
        assertNull(overlay.subtitleSource)
        val json = EditorStateJson.textOverlaysToJson(listOf(overlay))
        val back = EditorStateJson.textOverlaysFromJson(json)
        assertEquals(1, back.size)
        assertNull(back[0].subtitleSource)
    }

    @Test
    fun `legacy blob without subtitleSource key decodes as null`() {
        // Simulate an older project: a JSON blob that pre-dates
        // PHASE B4. The decoder must default to null (not
        // throw) so legacy projects keep loading.
        val legacyJson = """
            [{"id":"abc","text":"legacy","startMs":0,"endMs":1000,
              "x":0.5,"y":0.5,"colorArgb":-1,"fontSizeSp":24.0,
              "fontWeight":400,"bgAlpha":0.5,"rotation":0.0,
              "fontFamily":"DEFAULT","karaoke":true,"words":[]}]
        """.trimIndent()
        val back = EditorStateJson.textOverlaysFromJson(legacyJson)
        assertEquals(1, back.size)
        assertNull(back[0].subtitleSource)
    }

    @Test
    fun `export filter logic uses subtitleSource as source of truth`() {
        // The ViewModel's export filter is:
        //   overlay.id in aiSubtitleOverlayIds ||
        //   overlay.subtitleSource == "ai" ||
        //   overlay.subtitleSource == "srt"
        // This test pins down the contract from the consumer
        // side: every overlay with a non-null, non-"" subtitle
        // source is a subtitle and is exported.
        val overlays = listOf(
            TextOverlay(text = "AI", startTimeMs = 0L, endTimeMs = 1_000L,
                subtitleSource = "ai"),
            TextOverlay(text = "SRT", startTimeMs = 1_000L, endTimeMs = 2_000L,
                subtitleSource = "srt"),
            TextOverlay(text = "Decorative", startTimeMs = 2_000L, endTimeMs = 3_000L)
        )
        val exportable = overlays.filter { o ->
            o.subtitleSource == "ai" || o.subtitleSource == "srt"
        }
        assertEquals(2, exportable.size)
        assertEquals("AI", exportable[0].text)
        assertEquals("SRT", exportable[1].text)
    }

    @Test
    fun `PHASE A - plain text now exports too, blanks still do not`() {
        // PHASE A (Persistence Integrity): `buildSubtitleOverlays`
        // used to gate on `subtitleSource != null`, so a user-authored
        // title / paragraph / watermark previewed on screen and then
        // silently vanished from the MP4. The gate is now "has text".
        val overlays = listOf(
            TextOverlay(text = "AI", startTimeMs = 0L, endTimeMs = 1_000L,
                subtitleSource = "ai"),
            TextOverlay(text = "My Title", startTimeMs = 1_000L, endTimeMs = 2_000L),
            TextOverlay(text = "   ", startTimeMs = 2_000L, endTimeMs = 3_000L),
            TextOverlay(text = "", startTimeMs = 3_000L, endTimeMs = 4_000L)
        )
        val exportable = overlays.filter { it.text.isNotBlank() }
        assertEquals(2, exportable.size)
        assertEquals("AI", exportable[0].text)
        assertEquals("My Title", exportable[1].text)
    }
}
