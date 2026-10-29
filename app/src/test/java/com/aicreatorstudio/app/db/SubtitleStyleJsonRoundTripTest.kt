package com.aicreatorstudio.app.db

import android.graphics.Color as AndroidColor
import com.aicreatorstudio.app.model.SubtitleAlignmentH
import com.aicreatorstudio.app.model.SubtitleAlignmentV
import com.aicreatorstudio.app.model.SubtitleStyle
import com.aicreatorstudio.app.model.TextOverlay
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE B5 — JSON round-trip tests for [SubtitleStyle].
 *
 * Verifies:
 *  - The new `subtitleStyle` field survives the `textOverlaysJson`
 *    blob used by [com.aicreatorstudio.app.video.ProjectRepository].
 *  - A fully-populated style round-trips byte-for-byte.
 *  - A partially-disabled style (stroke off, background off) still
 *    round-trips.
 *  - The Default style is NOT written to the blob (keeps legacy
 *    blobs small and indistinguishable from pre-B5).
 *  - A legacy blob without the `subtitleStyle` key decodes as null
 *    (the renderer falls back to [SubtitleStyle.Default]).
 */
class SubtitleStyleJsonRoundTripTest {

    @Test
    fun `subtitleStyle with all fields set round trips byte-for-byte`() {
        val style = SubtitleStyle(
            fontFamily = "VAZIR",
            fontSizeSp = 32f,
            textColorArgb = AndroidColor.argb(255, 255, 240, 0),
            bold = true,
            italic = true,
            strokeEnabled = true,
            strokeColorArgb = AndroidColor.RED,
            strokeWidthSp = 3.5f,
            shadowEnabled = true,
            shadowColorArgb = AndroidColor.BLUE,
            shadowOpacity = 0.42f,
            shadowOffsetYDp = 1.7f,
            backgroundEnabled = true,
            backgroundColorArgb = AndroidColor.argb(255, 32, 32, 32),
            backgroundOpacity = 0.78f,
            backgroundCornerRadiusDp = 11f,
            horizontalAlignment = SubtitleAlignmentH.LEFT,
            verticalAlignment = SubtitleAlignmentV.TOP,
            positionX = 0.20f,
            positionY = 0.15f,
            maxTextWidthFraction = 0.85f
        )
        val overlay = TextOverlay(
            text = "Round-trip me",
            startTimeMs = 500L,
            endTimeMs = 2_500L,
            subtitleSource = "ai",
            subtitleStyle = style
        )
        val json = EditorStateJson.textOverlaysToJson(listOf(overlay))
        val reloaded = EditorStateJson.textOverlaysFromJson(json)
        assertEquals(1, reloaded.size)
        val back = reloaded[0].subtitleStyle
        assertNotNull("subtitleStyle must round trip", back)
        // Every field matches.
        assertEquals(style.fontFamily, back!!.fontFamily)
        assertEquals(style.fontSizeSp, back.fontSizeSp, 0.001f)
        assertEquals(style.textColorArgb, back.textColorArgb)
        assertEquals(style.bold, back.bold)
        assertEquals(style.italic, back.italic)
        assertEquals(style.strokeEnabled, back.strokeEnabled)
        assertEquals(style.strokeColorArgb, back.strokeColorArgb)
        assertEquals(style.strokeWidthSp, back.strokeWidthSp, 0.001f)
        assertEquals(style.shadowEnabled, back.shadowEnabled)
        assertEquals(style.shadowColorArgb, back.shadowColorArgb)
        assertEquals(style.shadowOpacity, back.shadowOpacity, 0.001f)
        assertEquals(style.shadowOffsetYDp, back.shadowOffsetYDp, 0.001f)
        assertEquals(style.backgroundEnabled, back.backgroundEnabled)
        assertEquals(style.backgroundColorArgb, back.backgroundColorArgb)
        assertEquals(style.backgroundOpacity, back.backgroundOpacity, 0.001f)
        assertEquals(style.backgroundCornerRadiusDp, back.backgroundCornerRadiusDp, 0.001f)
        assertEquals(style.horizontalAlignment, back.horizontalAlignment)
        assertEquals(style.verticalAlignment, back.verticalAlignment)
        assertEquals(style.positionX, back.positionX, 0.001f)
        assertEquals(style.positionY, back.positionY, 0.001f)
        assertEquals(style.maxTextWidthFraction, back.maxTextWidthFraction, 0.001f)
        // Text and timings are preserved.
        assertEquals("Round-trip me", reloaded[0].text)
        assertEquals(500L, reloaded[0].startTimeMs)
        assertEquals(2_500L, reloaded[0].endTimeMs)
    }

    @Test
    fun `subtitleStyle with stroke disabled round trips`() {
        val style = SubtitleStyle.Default.copy(strokeEnabled = false)
        val overlay = TextOverlay(
            text = "No stroke",
            startTimeMs = 0L,
            endTimeMs = 1_000L,
            subtitleSource = "srt",
            subtitleStyle = style
        )
        val json = EditorStateJson.textOverlaysToJson(listOf(overlay))
        val reloaded = EditorStateJson.textOverlaysFromJson(json)
        assertNotNull(reloaded[0].subtitleStyle)
        assertFalse("stroke must remain disabled", reloaded[0].subtitleStyle!!.strokeEnabled)
    }

    @Test
    fun `subtitleStyle with background disabled round trips`() {
        val style = SubtitleStyle.Default.copy(backgroundEnabled = false)
        val overlay = TextOverlay(
            text = "No bg",
            startTimeMs = 0L,
            endTimeMs = 1_000L,
            subtitleSource = "srt",
            subtitleStyle = style
        )
        val json = EditorStateJson.textOverlaysToJson(listOf(overlay))
        val reloaded = EditorStateJson.textOverlaysFromJson(json)
        assertNotNull(reloaded[0].subtitleStyle)
        assertFalse("background must remain disabled", reloaded[0].subtitleStyle!!.backgroundEnabled)
    }

    @Test
    fun `Default subtitleStyle is not serialized to JSON`() {
        // An overlay whose subtitleStyle == Default must NOT
        // produce a `subtitleStyle` key in the JSON blob.
        // That keeps the B5-upgraded blob byte-identical to
        // the B4 blob for unmodified overlays.
        val overlay = TextOverlay(
            text = "No style yet",
            startTimeMs = 0L,
            endTimeMs = 1_000L,
            subtitleSource = "ai",
            subtitleStyle = SubtitleStyle.Default
        )
        val json = EditorStateJson.textOverlaysToJson(listOf(overlay))
        val obj = JSONArray(json).getJSONObject(0)
        assertFalse(
            "Default style must not be serialized",
            obj.has("subtitleStyle")
        )
    }

    @Test
    fun `null subtitleStyle is not serialized to JSON`() {
        val overlay = TextOverlay(
            text = "Decorative",
            startTimeMs = 0L,
            endTimeMs = 1_000L
            // subtitleStyle is null by default
        )
        val json = EditorStateJson.textOverlaysToJson(listOf(overlay))
        val obj = JSONArray(json).getJSONObject(0)
        assertFalse("null style must not be serialized", obj.has("subtitleStyle"))
    }

    @Test
    fun `legacy blob without subtitleStyle key decodes as null`() {
        // Simulate a pre-B5 project: a JSON blob that has no
        // `subtitleStyle` key on any overlay. The decoder
        // must default to null so legacy projects keep
        // loading unchanged.
        val legacyJson = """
            [{"id":"abc","text":"legacy","startMs":0,"endMs":1000,
              "x":0.5,"y":0.5,"colorArgb":-1,"fontSizeSp":24.0,
              "fontWeight":400,"bgAlpha":0.5,"rotation":0.0,
              "fontFamily":"DEFAULT","karaoke":true,"words":[],
              "subtitleSource":"srt"}]
        """.trimIndent()
        val reloaded = EditorStateJson.textOverlaysFromJson(legacyJson)
        assertEquals(1, reloaded.size)
        assertNull(reloaded[0].subtitleStyle)
    }

    @Test
    fun `partial style blob with missing fields falls back to defaults`() {
        // Simulate a partial save: only some fields present.
        val partialJson = """
            [{"id":"abc","text":"partial","startMs":0,"endMs":1000,
              "x":0.5,"y":0.5,"colorArgb":-1,"fontSizeSp":24.0,
              "fontWeight":400,"bgAlpha":0.5,"rotation":0.0,
              "fontFamily":"DEFAULT","karaoke":true,"words":[],
              "subtitleSource":"ai",
              "subtitleStyle":{"fontSizeSp":40.0,"bold":false}}]
        """.trimIndent()
        val reloaded = EditorStateJson.textOverlaysFromJson(partialJson)
        assertEquals(1, reloaded.size)
        val s = reloaded[0].subtitleStyle
        assertNotNull(s)
        // Provided field:
        assertEquals(40f, s!!.fontSizeSp, 0.001f)
        assertEquals(false, s.bold)
        // Missing fields fall back to SubtitleStyle.Default.
        assertEquals(SubtitleStyle.Default.textColorArgb, s.textColorArgb)
        assertEquals(SubtitleStyle.Default.strokeEnabled, s.strokeEnabled)
        assertEquals(SubtitleStyle.Default.positionX, s.positionX, 0.001f)
    }

    @Test
    fun `subtitleStyle helper writes every field`() {
        // Sanity: subtitleStyleToJson emits all 21 fields.
        val json = EditorStateJson.subtitleStyleToJson(SubtitleStyle.Bold)
        val keys = listOf(
            "fontFamily", "fontSizeSp", "textColorArgb", "bold", "italic",
            "strokeEnabled", "strokeColorArgb", "strokeWidthSp",
            "shadowEnabled", "shadowColorArgb", "shadowOpacity", "shadowOffsetYDp",
            "backgroundEnabled", "backgroundColorArgb", "backgroundOpacity",
            "backgroundCornerRadiusDp", "horizontalAlignment", "verticalAlignment",
            "positionX", "positionY", "maxTextWidthFraction"
        )
        for (k in keys) {
            assertTrue("missing key $k", json.has(k))
        }
    }

    @Test
    fun `subtitleStyleFromJson returns null when the object is null`() {
        assertNull(EditorStateJson.subtitleStyleFromJson(null))
    }

    @Test
    fun `fontWeightValue round trips and defaults to 400 on legacy blobs`() {
        val style = SubtitleStyle.Default.copy(fontWeightValue = 900)
        val overlay = TextOverlay(
            text = "Black weight",
            startTimeMs = 0L,
            endTimeMs = 1_000L,
            subtitleSource = "ai",
            subtitleStyle = style
        )
        val json = EditorStateJson.textOverlaysToJson(listOf(overlay))
        val reloaded = EditorStateJson.textOverlaysFromJson(json)
        assertEquals(900, reloaded[0].subtitleStyle!!.fontWeightValue)
        // Legacy blob without the key → 400 (regular), never crash.
        val legacy = org.json.JSONObject(
            """{"fontFamily":"VAZIR","fontSizeSp":24.0}"""
        )
        assertEquals(
            400,
            EditorStateJson.subtitleStyleFromJson(legacy)!!.fontWeightValue
        )
    }
}
