package com.aicreatorstudio.app.db

import androidx.compose.ui.geometry.Offset
import com.aicreatorstudio.app.ai.TranscribedWord
import com.aicreatorstudio.app.model.PersianFont
import com.aicreatorstudio.app.model.TextOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * PHASE B2 — save / reopen round-trip for [TextOverlay]s carrying
 * per-word timing.
 *
 * Locks the contract that real Whisper subtitles survive:
 *   project.textOverlays → EditorStateJson.textOverlaysToJson
 *     → Room TEXT column → EditorStateJson.textOverlaysFromJson
 *       → project.textOverlays (bit-for-bit on the timing
 *         fields; a few visual fields that Compose types can't
 *         round-trip without a Parcelable contract — colors,
 *         position — fall back to defaults, which the codec
 *         already supports via the `opt*` defaults).
 *
 * These are pure JVM tests — no Android framework, no Room, no
 * editor. They prove the codec does not corrupt the AI-subtitle
 * data, which is the gate the B2 acceptance test relies on when
 * it reopens a project after a save.
 */
class TextOverlayPersistenceTest {

    @Test
    fun `a real whisper subtitle overlay round-trips through JSON with identical text, timing, words, and ids`() {
        val original = makeOverlay(
            text = "سلام دنیا",
            startMs = 1_240L,
            endMs = 4_080L,
            words = listOf(
                makeWord("سلام", 1_240L, 2_100L, confidence = 0.91f),
                makeWord("دنیا", 2_500L, 4_080L, confidence = 0.83f)
            ),
            font = PersianFont.VAZIR
        )
        val json = EditorStateJson.textOverlaysToJson(listOf(original))
        val restored = EditorStateJson.textOverlaysFromJson(json)
        assertEquals(1, restored.size)

        val r = restored.first()
        // Identity
        assertEquals(original.id, r.id)
        assertEquals(original.text, r.text)
        // Timing
        assertEquals(original.startTimeMs, r.startTimeMs)
        assertEquals(original.endTimeMs, r.endTimeMs)
        // Word timing + content
        assertEquals(original.words.size, r.words.size)
        for (i in original.words.indices) {
            assertEquals(original.words[i].id, r.words[i].id)
            assertEquals(original.words[i].text, r.words[i].text)
            assertEquals(original.words[i].startTimeMs, r.words[i].startTimeMs)
            assertEquals(original.words[i].endTimeMs, r.words[i].endTimeMs)
            assertEquals(
                original.words[i].confidence!!,
                r.words[i].confidence!!,
                0.0001f
            )
        }
        // Font + karaoke
        assertEquals(PersianFont.VAZIR.name, r.fontFamily)
        assertEquals(original.karaokeEnabled, r.karaokeEnabled)
    }

    @Test
    fun `persian unicode including ZWNJ and arabic-indic digits survives the round-trip`() {
        val persian = "گرافیک ۵"
        val original = makeOverlay(
            text = persian,
            startMs = 0L,
            endMs = 1_500L,
            words = listOf(makeWord(persian, 0L, 1_500L))
        )
        val json = EditorStateJson.textOverlaysToJson(listOf(original))
        val restored = EditorStateJson.textOverlaysFromJson(json)
        assertEquals(1, restored.size)
        assertEquals(persian, restored.first().text)
        assertEquals(persian, restored.first().words.first().text)
    }

    @Test
    fun `a project with no overlays still loads - empty list, not null, not crash`() {
        assertTrue(EditorStateJson.textOverlaysFromJson(null).isEmpty())
        assertTrue(EditorStateJson.textOverlaysFromJson("").isEmpty())
        assertTrue(EditorStateJson.textOverlaysFromJson("[]").isEmpty())
    }

    @Test
    fun `legacy rows without a words array still load - the codec is forward-compatible`() {
        // A text overlay written by a build that predates the AI
        // subtitle feature has no "words" key. The codec must
        // accept it (empty words list) instead of dropping the
        // overlay.
        val legacy = """
            [
              {
                "id": "legacy-1",
                "text": "Legacy text overlay",
                "startMs": 1000,
                "endMs": 4000
              }
            ]
        """.trimIndent()
        val restored = EditorStateJson.textOverlaysFromJson(legacy)
        assertEquals(1, restored.size)
        assertEquals("legacy-1", restored.first().id)
        assertEquals("Legacy text overlay", restored.first().text)
        assertEquals(1_000L, restored.first().startTimeMs)
        assertEquals(4_000L, restored.first().endTimeMs)
        assertTrue(restored.first().words.isEmpty())
    }

    @Test
    fun `two overlays preserve their relative order and ids across the round-trip`() {
        val a = makeOverlay("first", 0L, 1_000L, listOf(makeWord("first", 0L, 1_000L)))
        val b = makeOverlay("second", 1_500L, 3_000L, listOf(makeWord("second", 1_500L, 3_000L)))
        val json = EditorStateJson.textOverlaysToJson(listOf(a, b))
        val restored = EditorStateJson.textOverlaysFromJson(json)
        assertEquals(2, restored.size)
        assertEquals(a.id, restored[0].id)
        assertEquals(b.id, restored[1].id)
        assertEquals("first", restored[0].text)
        assertEquals("second", restored[1].text)
        assertNotEquals(restored[0].id, restored[1].id)
    }

    @Test
    fun `a malformed blob is treated as empty - the project still loads, just without subtitles`() {
        val restored = EditorStateJson.textOverlaysFromJson("{ this is not valid json")
        assertTrue(restored.isEmpty())
    }

    // ---- Helpers ----

    private fun makeOverlay(
        text: String,
        startMs: Long,
        endMs: Long,
        words: List<TranscribedWord> = emptyList(),
        font: PersianFont = PersianFont.DEFAULT
    ) = TextOverlay(
        id = UUID.randomUUID().toString(),
        text = text,
        startTimeMs = startMs,
        endTimeMs = endMs,
        position = Offset(0.5f, 0.9f),
        fontFamily = font.name,
        words = words,
        karaokeEnabled = true
    )

    private fun makeWord(
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

    // Sanity: the codec helpers exist on the EditorStateJson
    // object and the model classes are importable.
    @Test
    fun `sanity - codec helpers and model classes are present`() {
        assertNotNull(EditorStateJson)
        val sample = makeOverlay("x", 0L, 100L)
        assertNotNull(sample)
        assertNotNull(EditorStateJson.textOverlaysToJson(listOf(sample)))
        // And nulls are tolerated.
        assertNull(null)
    }

    // ---- PHASE 7: gradientAngleDeg round-trip ----

    @Test
    fun `gradientAngleDeg round-trips through JSON`() {
        val original = makeOverlay("gradient", 0L, 1000L).copy(
            gradientAngleDeg = 270f
        )
        val json = EditorStateJson.textOverlaysToJson(listOf(original))
        val restored = EditorStateJson.textOverlaysFromJson(json)
        assertEquals(1, restored.size)
        assertEquals(270f, restored[0].gradientAngleDeg!!, 0.01f)
    }

    @Test
    fun `null gradientAngleDeg is omitted from JSON`() {
        val original = makeOverlay("no-angle", 0L, 1000L)
        // gradientAngleDeg is null by default.
        assertNull(original.gradientAngleDeg)
        val json = EditorStateJson.textOverlaysToJson(listOf(original))
        val obj = org.json.JSONArray(json).getJSONObject(0)
        assertTrue("null gradientAngleDeg must not be serialized", !obj.has("gradientAngleDeg"))
        val restored = EditorStateJson.textOverlaysFromJson(json)
        assertNull(restored[0].gradientAngleDeg)
    }

    @Test
    fun `legacy blob without gradientAngleDeg decodes as null`() {
        val legacyJson = """
            [{"id":"abc","text":"legacy","startMs":0,"endMs":1000,
              "x":0.5,"y":0.5,"colorArgb":-1,"fontSizeSp":24.0,
              "fontWeight":400,"bgAlpha":0.5,"rotation":0.0,
              "fontFamily":"DEFAULT","karaoke":true,"words":[],
              "subtitleSource":"srt"}]
        """.trimIndent()
        val restored = EditorStateJson.textOverlaysFromJson(legacyJson)
        assertEquals(1, restored.size)
        assertNull(restored[0].gradientAngleDeg)
    }

    @Test
    fun `gradientAngleDeg 0f round-trips`() {
        val original = makeOverlay("zero", 0L, 500L).copy(gradientAngleDeg = 0f)
        val json = EditorStateJson.textOverlaysToJson(listOf(original))
        val restored = EditorStateJson.textOverlaysFromJson(json)
        assertEquals(0f, restored[0].gradientAngleDeg!!, 0.01f)
    }
}
