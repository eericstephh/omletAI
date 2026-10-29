package com.aicreatorstudio.app.db

import com.aicreatorstudio.app.model.CropKeyframe
import com.aicreatorstudio.app.model.MattingBackgroundMode
import com.aicreatorstudio.app.model.MattingSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip pins for the AI trajectory/settings JSON codec
 * ([EditorStateJson]) that go beyond the coexistence test: the
 * not-found placeholder semantics and the matting background mode.
 *
 * These are exactly the fields the Auto Frame / Motion Tracking /
 * Background Remove consumers branch on, so a codec regression here
 * manufactures phantom behaviour (a "found" box where tracking failed,
 * an ORIGINAL composite where the user chose BLUR).
 */
class AiToolsTrajectoryRoundtripTest {

    @Test
    fun `isFound false survives the round trip`() {
        val keys = listOf(
            CropKeyframe(
                timeMs = 0L, centerX = 0.4f, centerY = 0.5f,
                width = 0.2f, height = 0.2f, confidence = 0.9f, isFound = true
            ),
            CropKeyframe(
                timeMs = 500L, centerX = 0.4f, centerY = 0.5f,
                width = 0.2f, height = 0.2f, confidence = 0f, isFound = false
            )
        )
        val blob = EditorStateJson.aiToolsToJson(
            mapOf("c1" to EditorStateJson.ClipAiTools(cropKeyframes = keys))
        )
        val decoded = EditorStateJson.aiToolsFromJson(blob)["c1"]!!.cropKeyframes
        assertEquals(2, decoded.size)
        assertTrue("first entry must stay found", decoded[0].isFound)
        assertFalse("lost entry must come back not-found", decoded[1].isFound)
    }

    @Test
    fun `reframe keys without extent decode as null extents`() {
        val keys = listOf(
            CropKeyframe(timeMs = 0L, centerX = 0.3f, centerY = 0.4f),
            CropKeyframe(timeMs = 700L, centerX = 0.6f, centerY = 0.4f)
        )
        val blob = EditorStateJson.aiToolsToJson(
            mapOf("c1" to EditorStateJson.ClipAiTools(cropKeyframes = keys))
        )
        val decoded = EditorStateJson.aiToolsFromJson(blob)["c1"]!!.cropKeyframes
        assertEquals(2, decoded.size)
        assertNull(decoded[0].width)
        assertNull(decoded[0].height)
        // Auto Frame kept its true `isFound` on the analysis side.
        assertTrue(decoded[0].isFound)
    }

    @Test
    fun `matting background mode survives the round trip when enabled`() {
        val original = EditorStateJson.ClipAiTools(
            matting = MattingSettings(
                enabled = true,
                threshold = 0.4f,
                backgroundMode = MattingBackgroundMode.COLOR,
                backgroundColor = 0xFF00FF00L.toInt()
            )
        )
        val blob = EditorStateJson.aiToolsToJson(mapOf("c1" to original))
        val decoded = EditorStateJson.aiToolsFromJson(blob)["c1"]!!
        assertEquals(MattingBackgroundMode.COLOR, decoded.matting.backgroundMode)
        assertEquals(0xFF00FF00L.toInt(), decoded.matting.backgroundColor)
    }

    @Test
    fun `matting transparent mode survives the round trip when enabled`() {
        // 2026-10-27 — TRANSPARENT is the mode every fresh run commits.
        // A project saved with it must reopen with it, never degrade to
        // the ORIGINAL pass-through (which would silently resurrect the
        // removed background).
        val original = EditorStateJson.ClipAiTools(
            matting = MattingSettings(
                enabled = true,
                backgroundMode = MattingBackgroundMode.TRANSPARENT
            )
        )
        val blob = EditorStateJson.aiToolsToJson(mapOf("c1" to original))
        val decoded = EditorStateJson.aiToolsFromJson(blob)["c1"]!!
        assertEquals(MattingBackgroundMode.TRANSPARENT, decoded.matting.backgroundMode)
    }

    @Test
    fun `matting identity does not manufacture an enabled state`() {
        val blob = EditorStateJson.aiToolsToJson(
            mapOf("c1" to EditorStateJson.ClipAiTools())
        )
        val decoded = EditorStateJson.aiToolsFromJson(blob)["c1"]!!
        assertFalse("disabled matting must not flip on", decoded.matting.enabled)
        assertEquals(MattingBackgroundMode.ORIGINAL, decoded.matting.backgroundMode)
    }

    // ---- 2026-10-25: composition-time mask knobs ----

    @Test
    fun `matting expansion feather and reverse survive the round trip`() {
        val original = EditorStateJson.ClipAiTools(
            matting = MattingSettings(
                enabled = true,
                threshold = 0.3f,
                feather = 0.45f,
                expansion = -0.7f,
                reverse = true
            )
        )
        val blob = EditorStateJson.aiToolsToJson(mapOf("c1" to original))
        val decoded = EditorStateJson.aiToolsFromJson(blob)["c1"]!!
        assertEquals(0.45f, decoded.matting.feather, 1e-4f)
        assertEquals(-0.7f, decoded.matting.expansion, 1e-4f)
        assertTrue(decoded.matting.reverse)
    }

    @Test
    fun `a legacy blob without mattingFeather loads with feather zero`() {
        // The old writer stored `mattingFeatherPx` because the
        // generation-time feather was baked into the sidecar PNGs.
        // The composition-time knob must load as 0 so the baked
        // feather is not applied a second time (double feathering).
        val legacy = """{"c1":{
            "mattingEnabled":true,
            "mattingThreshold":0.3,
            "mattingFeatherPx":2.0,
            "mattingBg":null,
            "mattingAlgorithm":"mediapipe-selfie-multiclass-v1",
            "mattingBgMode":"COLOR",
            "mattingBgColor":255
        }}"""
        val decoded = EditorStateJson.aiToolsFromJson(legacy)["c1"]!!
        assertTrue(decoded.matting.enabled)
        assertEquals(0f, decoded.matting.feather, 1e-6f)
        assertEquals(0f, decoded.matting.expansion, 1e-6f)
        assertFalse(decoded.matting.reverse)
    }

    @Test
    fun `a reverse-only tweak persists without being enabled`() {
        // The knobs are user-owned; moving them must survive a
        // save/reopen even when the clip has not run the tool yet.
        val original = EditorStateJson.ClipAiTools(
            matting = MattingSettings(expansion = 0.2f, reverse = true)
        )
        val blob = EditorStateJson.aiToolsToJson(mapOf("c1" to original))
        val decoded = EditorStateJson.aiToolsFromJson(blob)["c1"]!!
        assertFalse("a knob tweak must not enable matting", decoded.matting.enabled)
        assertEquals(0.2f, decoded.matting.expansion, 1e-4f)
        assertTrue(decoded.matting.reverse)
    }
}
