package com.aicreatorstudio.app.db

import com.aicreatorstudio.app.model.ProgressBarGeometry
import com.aicreatorstudio.app.model.ProgressBarPosition
import com.aicreatorstudio.app.model.ProgressBarSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for the `progressBarSettingsJson` blob (projects
 * table, schema v8).
 *
 * This codec runs on **every** project open and every project save, so the
 * two properties that matter are:
 *
 *  1. a value written by one build reads back identical on the next
 *     (otherwise the bar silently changes when the user reopens the
 *     project), and
 *  2. a `null` / blank / `"{}"` blob — which is exactly what a v7 row
 *     carries after `MIGRATION_7_8` — decodes to the **disabled** default
 *     rather than throwing or turning the bar on.
 *
 * Property 2 is the one that protects installed databases: an existing
 * project must not grow a progress bar just because the column appeared.
 */
class ProgressBarSettingsJsonTest {

    private val default = ProgressBarSettings.Disabled

    @Test
    fun `the default settings round-trip unchanged`() {
        val json = EditorStateJson.progressBarSettingsToJson(default)
        assertEquals(default, EditorStateJson.progressBarSettingsFromJson(json))
    }

    @Test
    fun `a fully customised bar round-trips unchanged`() {
        val custom = ProgressBarSettings(
            isEnabled = true,
            position = ProgressBarPosition.TOP,
            heightDp = 7.5f,
            colorArgb = 0xFF00E5FF.toInt(),
            backgroundColorArgb = 0xB3FFFFFF.toInt(),
            cornerRadiusDp = 6f,
            marginDp = 18f
        )
        val json = EditorStateJson.progressBarSettingsToJson(custom)
        assertEquals(custom, EditorStateJson.progressBarSettingsFromJson(json))
    }

    @Test
    fun `every position value survives the round-trip`() {
        ProgressBarPosition.entries.forEach { position ->
            val settings = ProgressBarSettings(isEnabled = true, position = position)
            val restored = EditorStateJson.progressBarSettingsFromJson(
                EditorStateJson.progressBarSettingsToJson(settings)
            )
            assertEquals(position, restored.position)
        }
    }

    @Test
    fun `a negative argb survives the round-trip`() {
        // 0xFFFF0055 as an Int is negative. Storing it through a JSON
        // number and reading it back with `optInt` must not sign-flip or
        // clamp it to zero — the default fill colour is exactly this value.
        val json = EditorStateJson.progressBarSettingsToJson(default)
        val restored = EditorStateJson.progressBarSettingsFromJson(json)
        assertEquals(0xFFFF0055.toInt(), restored.colorArgb)
        assertTrue("the default fill colour must stay negative", restored.colorArgb < 0)
    }

    @Test
    fun `a null or blank blob decodes to the disabled default`() {
        // A v7 row after the additive migration carries `"{}"`; a row that
        // somehow carries nothing at all must behave the same way.
        listOf(null, "", "   ").forEach { blank ->
            assertEquals(
                "blank=$blank should decode to the disabled default",
                default,
                EditorStateJson.progressBarSettingsFromJson(blank)
            )
        }
    }

    @Test
    fun `an empty object decodes to the disabled default`() {
        assertEquals(default, EditorStateJson.progressBarSettingsFromJson("{}"))
    }

    @Test
    fun `the migration default literally decodes to a disabled bar`() {
        // `MIGRATION_7_8` adds the column with `DEFAULT '{}'`. If this ever
        // stops being true, every installed project grows a bar on the next
        // app update — so the SQL default, the entity default and the
        // decoder are pinned together here.
        val freshRow = VideoProjectEntity(
            id = "p",
            name = "n",
            createdAtEpochMillis = 0L,
            updatedAtEpochMillis = 0L
        )
        assertEquals("{}", freshRow.progressBarSettingsJson)
        val decoded = EditorStateJson.progressBarSettingsFromJson(
            freshRow.progressBarSettingsJson
        )
        assertFalse(decoded.isEnabled)
        assertEquals(default, decoded)
    }

    @Test
    fun `a malformed blob decodes to the default instead of throwing`() {
        listOf(
            "not json",
            "[1,2,3]",
            "{\"enabled\":",
            "\"a string\"",
            "{\"heightDp\":\"tall\"}"
        ).forEach { garbage ->
            val restored = runCatching {
                EditorStateJson.progressBarSettingsFromJson(garbage)
            }.getOrElse { error ->
                throw AssertionError("blob <$garbage> threw $error", error)
            }
            assertEquals("blob <$garbage>", default, restored)
        }
    }

    @Test
    fun `an unknown position name falls back to the bottom edge`() {
        val json = "{\"enabled\":true,\"position\":\"SIDEWAYS\"}"
        val restored = EditorStateJson.progressBarSettingsFromJson(json)
        assertEquals(ProgressBarPosition.BOTTOM, restored.position)
        // The rest of the blob is still honoured — one bad key must not
        // discard the whole object.
        assertTrue(restored.isEnabled)
    }

    @Test
    fun `an out-of-range height is clamped on decode`() {
        // A hand-edited (or future-build) blob must not be able to hand the
        // renderers geometry that throws inside `Canvas.drawRoundRect`.
        val json = "{\"enabled\":true,\"heightDp\":9999}"
        val restored = EditorStateJson.progressBarSettingsFromJson(json)
        assertEquals(ProgressBarGeometry.MAX_HEIGHT_DP, restored.heightDp, 1e-3f)
        assertTrue(restored.isEnabled)
    }

    @Test
    fun `out-of-range radius and margin are clamped on decode`() {
        val restored = EditorStateJson.progressBarSettingsFromJson(
            "{\"enabled\":true,\"cornerRadiusDp\":-50,\"marginDp\":100000}"
        )
        assertEquals(0f, restored.cornerRadiusDp, 1e-3f)
        assertEquals(ProgressBarGeometry.MAX_MARGIN_DP, restored.marginDp, 1e-3f)
    }

    @Test
    fun `a missing key falls back to the model default`() {
        // Only `enabled` is present; every other knob must come back at the
        // model default so a partially-written blob is still usable.
        val restored = EditorStateJson.progressBarSettingsFromJson("{\"enabled\":true}")
        assertEquals(default.heightDp, restored.heightDp, 1e-3f)
        assertEquals(default.cornerRadiusDp, restored.cornerRadiusDp, 1e-3f)
        assertEquals(default.marginDp, restored.marginDp, 1e-3f)
        assertEquals(default.colorArgb, restored.colorArgb)
        assertEquals(default.backgroundColorArgb, restored.backgroundColorArgb)
        assertEquals(default.position, restored.position)
        assertTrue(restored.isEnabled)
    }

    @Test
    fun `decoding is stable across two passes`() {
        // Decode → encode → decode must be a fixed point, or a project
        // would drift a little on every save/reopen cycle.
        val custom = ProgressBarSettings(
            isEnabled = true,
            position = ProgressBarPosition.TOP,
            heightDp = 9f,
            colorArgb = 0xFFFFE600.toInt(),
            backgroundColorArgb = 0x66FFFFFF,
            cornerRadiusDp = 4.5f,
            marginDp = 30f
        )
        val once = EditorStateJson.progressBarSettingsFromJson(
            EditorStateJson.progressBarSettingsToJson(custom)
        )
        val twice = EditorStateJson.progressBarSettingsFromJson(
            EditorStateJson.progressBarSettingsToJson(once)
        )
        assertEquals(custom, once)
        assertEquals(once, twice)
    }

    @Test
    fun `the encoded blob is a single JSON object with the expected keys`() {
        val json = EditorStateJson.progressBarSettingsToJson(
            ProgressBarSettings(isEnabled = true)
        )
        val obj = org.json.JSONObject(json)
        assertTrue(obj.has("enabled"))
        assertTrue(obj.has("position"))
        assertTrue(obj.has("heightDp"))
        assertTrue(obj.has("colorArgb"))
        assertTrue(obj.has("bgColorArgb"))
        assertTrue(obj.has("cornerRadiusDp"))
        assertTrue(obj.has("marginDp"))
        assertEquals(7, obj.length())
    }
}
