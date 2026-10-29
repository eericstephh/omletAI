package com.aicreatorstudio.app.db

import com.aicreatorstudio.app.model.ChromaKeySettings
import com.aicreatorstudio.app.model.VideoOverlay
import com.aicreatorstudio.app.util.ChromaKeyEngine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persistence coverage for the 2026-10-13 **chroma key**.
 *
 * ## Where the values live
 *
 * There is no new column and no Room migration — the same situation (and the
 * same decision) as `OverlayMaskPersistenceTest`: the settings ride the
 * project's existing `mediaOverlaysJson` blob. Adding three fields costs no
 * schema version bump, and a project written by a build that predates the
 * feature decodes with the defaults rather than failing to open.
 *
 * The blob is the only thing standing between "the user picked blue at 60%"
 * and "the app forgot", so the two failure modes worth a test are:
 *
 *  1. the round-trip losing or corrupting a value, and
 *  2. the **write-when-non-default** rule regressing, which would grow every
 *     project's blob and make a stale value indistinguishable from a real
 *     edit.
 *
 * The third case is the one this feature makes *new*: `isIdentity` is
 * deliberately NOT `!isEnabled`. A user who tunes the colour, switches the
 * key off to check their footage and saves must not lose the tuning — but a
 * project that never touched chroma key must still write nothing at all. Both
 * halves of that are asserted below, because they pull in opposite
 * directions and a future simplification would break one of them.
 *
 * Nothing here touches `android.*`: the settings and the codec are pure.
 */
class ChromaKeyPersistenceTest {

    /** An overlay with an explicit id so the serialised bytes are stable. */
    private fun overlay(
        id: String = "ov-1",
        chroma: ChromaKeySettings = ChromaKeySettings.Default
    ) = VideoOverlay(
        id = id,
        clipId = "clip-1",
        startTimeMs = 0L,
        endTimeMs = 5_000L,
        uri = "content://media/1",
        label = "facecam",
        isVideo = true,
        chromaKeySettings = chroma
    )

    /** The single decoded overlay, or a clear failure. */
    private fun decodeOne(json: String): VideoOverlay {
        val decoded = EditorStateJson.mediaOverlaysFromJson(json)
        assertEquals("expected exactly one overlay: $json", 1, decoded.size)
        return decoded.first()
    }

    /** Key set of the first element of a serialised overlay array. */
    private fun keysOf(json: String): Set<String> {
        val obj = JSONArray(json).getJSONObject(0)
        return obj.keys().asSequence().toSet()
    }

    private fun roundTrip(chroma: ChromaKeySettings): ChromaKeySettings =
        decodeOne(EditorStateJson.mediaOverlaysToJson(listOf(overlay(chroma = chroma))))
            .chromaKeySettings

    // ---- the "write nothing" rule -------------------------------------

    @Test
    fun `an untouched overlay writes no chroma key at all`() {
        // `isIdentity` covers a project that never opened the panel. The
        // blob must be byte-identical to the pre-feature one, so reopening
        // it cannot churn the file.
        val json = EditorStateJson.mediaOverlaysToJson(listOf(overlay()))
        assertFalse("no chroma key may be written: $json", json.contains("chroma"))
    }

    @Test
    fun `an untouched overlay serialises to exactly the pre-feature key set`() {
        // Stronger than the substring check above: it pins the complete key
        // set, so a future field that forgets its own
        // write-when-non-default rule fails here rather than silently
        // growing every saved project.
        val json = EditorStateJson.mediaOverlaysToJson(listOf(overlay()))
        assertEquals(
            setOf(
                "id", "clipId", "startMs", "endMs", "x", "y", "scale",
                "opacity", "uri", "label", "isVideo"
            ),
            keysOf(json)
        )
    }

    @Test
    fun `an untouched overlay round-trips to the default`() {
        val decoded = decodeOne(EditorStateJson.mediaOverlaysToJson(listOf(overlay())))
        assertEquals(ChromaKeySettings.Default, decoded.chromaKeySettings)
        assertFalse(decoded.chromaKeySettings.isEnabled)
        assertTrue(decoded.chromaKeySettings.isIdentity)
    }

    @Test
    fun `a legacy row with no chroma key decodes to the default`() {
        // A project written by a build that predates the feature. Absent
        // keys must mean "off and untuned", not a crash and not a
        // surprising half-enabled state.
        val json = """
            [{"id":"ov-1","clipId":"clip-1","startMs":0,"endMs":5000,
              "x":0.1,"y":0.1,"scale":0.3,"opacity":1.0,
              "uri":"content://media/1","label":"facecam","isVideo":true}]
        """.trimIndent()
        val decoded = decodeOne(json)
        assertEquals(ChromaKeySettings.Default, decoded.chromaKeySettings)
        assertFalse(decoded.chromaKeySettings.isEnabled)
    }

    // ---- the round-trip -------------------------------------------------

    @Test
    fun `every field survives the round-trip`() {
        val original = ChromaKeySettings(
            isEnabled = true,
            keyColorArgb = ChromaKeySettings.BLUE_KEY_COLOR_ARGB,
            similarity = 0.62f,
            smoothness = 0.28f,
            spillReduction = 0.75f
        )
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun `the default key colour survives the round-trip`() {
        // Written by omission, so it is restored from the model default
        // rather than from a stored value — the two must agree.
        val original = ChromaKeySettings(isEnabled = true)
        assertEquals(
            ChromaKeySettings.DEFAULT_KEY_COLOR_ARGB,
            roundTrip(original).keyColorArgb
        )
    }

    @Test
    fun `each tuned level is written and restored on its own`() {
        // Assert the four keys are genuinely independent — a shared
        // write/read bug would surface as one field clobbering another.
        for (level in listOf(0f, 0.17f, 0.5f, 0.83f, 1f)) {
            val tuned = listOf(
                ChromaKeySettings(isEnabled = true, similarity = level),
                ChromaKeySettings(isEnabled = true, smoothness = level),
                ChromaKeySettings(isEnabled = true, spillReduction = level)
            )
            for (original in tuned) {
                val back = roundTrip(original)
                assertEquals("similarity", original.similarity, back.similarity, 1e-6f)
                assertEquals("smoothness", original.smoothness, back.smoothness, 1e-6f)
                assertEquals("spillReduction", original.spillReduction, back.spillReduction, 1e-6f)
                assertEquals("enabled", original.isEnabled, back.isEnabled)
            }
        }
    }

    @Test
    fun `several overlays in one blob round-trip independently`() {
        val overlays = listOf(
            overlay(id = "ov-a", chroma = ChromaKeySettings(isEnabled = true)),
            overlay(
                id = "ov-b",
                chroma = ChromaKeySettings(
                    isEnabled = true,
                    keyColorArgb = ChromaKeySettings.BLUE_KEY_COLOR_ARGB,
                    similarity = 0.71f, smoothness = 0.33f, spillReduction = 0.19f
                )
            )
        )
        val decoded = EditorStateJson.mediaOverlaysFromJson(
            EditorStateJson.mediaOverlaysToJson(overlays)
        )
        assertEquals(overlays, decoded)
    }

    // ---- the disable-keeps-tuning rule --------------------------------

    @Test
    fun `disabling the key preserves the tuning`() {
        // The whole reason `isIdentity` is not `!isEnabled`. A user turns
        // the effect off to check their raw footage and saves; the tuning
        // must come back with it.
        val original = ChromaKeySettings(
            isEnabled = false,
            keyColorArgb = ChromaKeySettings.BLUE_KEY_COLOR_ARGB,
            similarity = 0.77f,
            smoothness = 0.42f,
            spillReduction = 0.9f
        )
        assertFalse("off but tuned is not identity", original.isIdentity)
        val json = EditorStateJson.mediaOverlaysToJson(listOf(overlay(chroma = original)))
        assertTrue("a tuned-off key still writes something: $json", json.contains("chroma"))
        assertEquals(original, decodeOne(json).chromaKeySettings)
    }

    @Test
    fun `a disabled default key writes nothing`() {
        // Off AND untuned is the pre-feature state, so it must not be
        // persisted — this is what keeps `isIdentity` honest.
        val original = ChromaKeySettings(isEnabled = false)
        assertTrue(original.isIdentity)
        val json = EditorStateJson.mediaOverlaysToJson(listOf(overlay(chroma = original)))
        assertFalse("nothing to store: $json", json.contains("chroma"))
        assertEquals(original, decodeOne(json).chromaKeySettings)
    }

    @Test
    fun `a custom key colour is written explicitly`() {
        // Written only when it differs from the default, so a green screen
        // project stores nothing and a custom colour does.
        val green = ChromaKeySettings(
            isEnabled = true, keyColorArgb = ChromaKeySettings.DEFAULT_KEY_COLOR_ARGB
        )
        val json = EditorStateJson.mediaOverlaysToJson(listOf(overlay(chroma = green)))
        assertFalse("the default colour is implied: $json", json.contains("chromaColor"))
        assertEquals(ChromaKeySettings.DEFAULT_KEY_COLOR_ARGB, roundTrip(green).keyColorArgb)

        val custom = green.copy(keyColorArgb = 0xFFFF00FF.toInt())
        val json2 = EditorStateJson.mediaOverlaysToJson(listOf(overlay(chroma = custom)))
        assertTrue("a custom colour must be stored: $json2", json2.contains("chromaColor"))
        assertEquals(custom.keyColorArgb, roundTrip(custom).keyColorArgb)
    }

    // ---- hostile input -------------------------------------------------

    @Test
    fun `an out-of-range level is clamped on read`() {
        // Hand-edited or corrupt blob. `sanitize` on the way in is what
        // keeps a similarity above 1 from meaning "key everything" and a
        // negative smoothness from inverting the ramp. Note it clamps to
        // the slider range — NOT to the model default; only a non-finite
        // value falls back (see the next test).
        val json = """
            [{"id":"ov-1","clipId":"clip-1","startMs":0,"endMs":5000,
              "x":0.1,"y":0.1,"scale":0.3,"opacity":1.0,
              "uri":"content://media/1","label":"facecam","isVideo":true,
              "chromaOn":true,"chromaSimilarity":5.0,"chromaSmoothness":-3.0,
              "chromaSpill":99.0}]
        """.trimIndent()
        val back = decodeOne(json).chromaKeySettings
        assertEquals(ChromaKeyEngine.MAX_LEVEL, back.similarity, 1e-6f)
        assertEquals(ChromaKeyEngine.MIN_LEVEL, back.smoothness, 1e-6f)
        assertEquals(ChromaKeyEngine.MAX_LEVEL, back.spillReduction, 1e-6f)
    }

    @Test
    fun `a non-finite level falls back to the working default on read`() {
        // A corrupt similarity of 0 would mean "key nothing", i.e. the user
        // would see the feature appear broken. The default works.
        val json = """
            [{"id":"ov-1","clipId":"clip-1","startMs":0,"endMs":5000,
              "x":0.1,"y":0.1,"scale":0.3,"opacity":1.0,
              "uri":"content://media/1","label":"facecam","isVideo":true,
              "chromaOn":true,"chromaSimilarity":1e999}]
        """.trimIndent()
        val back = decodeOne(json).chromaKeySettings
        assertEquals(ChromaKeySettings.DEFAULT_SIMILARITY, back.similarity, 1e-6f)
    }

    @Test
    fun `a transparent key colour is forced opaque on read`() {
        // Two equal colours must compare equal, and the metric must never
        // read a channel that does not participate in the key.
        val json = """
            [{"id":"ov-1","clipId":"clip-1","startMs":0,"endMs":5000,
              "x":0.1,"y":0.1,"scale":0.3,"opacity":1.0,
              "uri":"content://media/1","label":"facecam","isVideo":true,
              "chromaOn":true,"chromaColor":65536}]
        """.trimIndent()
        val back = decodeOne(json).chromaKeySettings
        assertEquals(ChromaKeySettings.OPAQUE_ALPHA_MASK, back.keyColorArgb and ChromaKeySettings.OPAQUE_ALPHA_MASK)
    }

    @Test
    fun `a null or blank blob decodes to an empty list`() {
        assertTrue(EditorStateJson.mediaOverlaysFromJson(null).isEmpty())
        assertTrue(EditorStateJson.mediaOverlaysFromJson("").isEmpty())
        assertTrue(EditorStateJson.mediaOverlaysFromJson("   ").isEmpty())
    }

    @Test
    fun `a truncated blob decodes rather than throwing`() {
        // A half-written project must open, not crash on relaunch.
        val json = """
            [{"id":"ov-1","clipId":"clip-1","startMs":0,"endMs":5000,
              "x":0.1,"y":0.1,"scale":0.3,"opacity":1.0,
              "uri":"content://media/1","label":"facecam","isVideo":true,
              "chromaOn":true,"chromaSimilarity"
        """.trimIndent()
        val decoded = EditorStateJson.mediaOverlaysFromJson(json)
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun `a non-boolean enable flag decodes as off`() {
        // `optBoolean` coerces anything unrecognised, and "off" is the safe
        // direction: a corrupted flag must not enable an effect the user
        // never turned on.
        val json = """
            [{"id":"ov-1","clipId":"clip-1","startMs":0,"endMs":5000,
              "x":0.1,"y":0.1,"scale":0.3,"opacity":1.0,
              "uri":"content://media/1","label":"facecam","isVideo":true,
              "chromaOn":"yes"}]
        """.trimIndent()
        assertFalse(decodeOne(json).chromaKeySettings.isEnabled)
    }

    @Test
    fun `the stored colour is an int, so alpha is never confused with it`() {
        // Round-trips a colour whose red channel alone would exceed a byte
        // if the codec chose a wider type.
        val original = ChromaKeySettings(
            isEnabled = true, keyColorArgb = 0xFF123456.toInt()
        )
        assertEquals(original.keyColorArgb, roundTrip(original).keyColorArgb)
    }

    @Test
    fun `the key set is exactly what was written`() {
        // Pins that a fully tuned key writes exactly the four keys plus the
        // shared ones — so an extra key cannot appear silently.
        val chroma = ChromaKeySettings(
            isEnabled = true,
            keyColorArgb = ChromaKeySettings.BLUE_KEY_COLOR_ARGB,
            similarity = 0.62f, smoothness = 0.28f, spillReduction = 0.75f
        )
        val json = EditorStateJson.mediaOverlaysToJson(listOf(overlay(chroma = chroma)))
        val obj: JSONObject = JSONArray(json).getJSONObject(0)
        assertEquals(
            setOf("chromaOn", "chromaColor", "chromaSimilarity", "chromaSmoothness", "chromaSpill"),
            obj.keys().asSequence().filter { it.startsWith("chroma") }.toSet()
        )
    }
}
