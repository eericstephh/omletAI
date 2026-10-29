package com.aicreatorstudio.app.db

import com.aicreatorstudio.app.db.EditorStateJson.ClipAiTools
import com.aicreatorstudio.app.model.ClipLookSettings
import com.aicreatorstudio.app.model.ClipTransform
import com.aicreatorstudio.app.model.ColorGrading
import com.aicreatorstudio.app.model.FillMode
import com.aicreatorstudio.app.model.VideoEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE A (Persistence Integrity) — round-trip tests for the per-clip
 * "look" block that used to be dropped entirely on save→reopen.
 *
 * The bug class: `VideoClipEntity` carried no column and `ClipAiTools`
 * no field for `videoEffect`, `colorGrading`, `opacity`, `fillMode`,
 * `transform`, `fadeInMs` or `fadeOutMs`, so a user's filters, grades,
 * fades and transforms survived for the session and then vanished.
 *
 * This suite pins three contracts:
 *  1. every look field survives a blob round-trip;
 *  2. an identity block is not persisted at all (blob stays small);
 *  3. legacy / hostile blobs degrade to Identity instead of throwing.
 */
class ClipLookPersistenceTest {

    private fun tools(look: ClipLookSettings): Map<String, ClipAiTools> =
        mapOf("c1" to ClipAiTools(look = look))

    private fun roundTrip(look: ClipLookSettings): ClipLookSettings {
        val json = EditorStateJson.aiToolsToJson(tools(look))
        return EditorStateJson.aiToolsFromJson(json).getValue("c1").look
    }

    private val full = ClipLookSettings(
        videoEffect = VideoEffect.NOIR,
        colorGrading = ColorGrading(
            brightness = 0.25f,
            contrast = -0.4f,
            exposure = 0.1f,
            shadows = 0.7f,
            temperature = 0.6f,
            saturation = -0.2f,
            vignetteAmount = 0.5f,
            grain = 0.3f,
            hueBlue = 42f
        ),
        opacity = 0.65f,
        fillMode = FillMode.FILL,
        transform = ClipTransform(
            scale = 1.75f,
            offsetX = -0.1f,
            offsetY = 0.2f,
            rotation = 270,
            flipH = true,
            flipV = false
        ),
        fadeInMs = 1_500L,
        fadeOutMs = 2_200L
    )

    // ---- 1. everything survives ----

    @Test
    fun `every look field survives a round trip`() {
        val back = roundTrip(full)
        assertEquals(VideoEffect.NOIR, back.videoEffect)
        assertEquals(0.65f, back.opacity, 0.001f)
        assertEquals(FillMode.FILL, back.fillMode)
        assertEquals(1_500L, back.fadeInMs)
        assertEquals(2_200L, back.fadeOutMs)
    }

    @Test
    fun `the transform survives with every component`() {
        val t = roundTrip(full).transform
        assertEquals(1.75f, t.scale, 0.001f)
        assertEquals(-0.1f, t.offsetX, 0.001f)
        assertEquals(0.2f, t.offsetY, 0.001f)
        assertEquals(270, t.rotation)
        assertTrue(t.flipH)
        assertFalse(t.flipV)
    }

    @Test
    fun `the colour grading survives with every component`() {
        val g = roundTrip(full).colorGrading
        assertEquals(0.25f, g.brightness, 0.001f)
        assertEquals(-0.4f, g.contrast, 0.001f)
        assertEquals(0.1f, g.exposure, 0.001f)
        assertEquals(0.7f, g.shadows, 0.001f)
        assertEquals(0.6f, g.temperature, 0.001f)
        assertEquals(-0.2f, g.saturation, 0.001f)
        assertEquals(0.5f, g.vignetteAmount, 0.001f)
        assertEquals(0.3f, g.grain, 0.001f)
        assertEquals(42f, g.hueBlue, 0.001f)
    }

    @Test
    fun `a lone fade survives even with nothing else changed`() {
        val back = roundTrip(
            ClipLookSettings(fadeInMs = 900L, fadeOutMs = 300L)
        )
        assertEquals(900L, back.fadeInMs)
        assertEquals(300L, back.fadeOutMs)
        assertEquals(VideoEffect.NONE, back.videoEffect)
        assertEquals(FillMode.FIT, back.fillMode)
        assertTrue(back.transform.isIdentity)
    }

    // ---- 2. identity is not persisted ----

    @Test
    fun `an identity block is not written at all`() {
        val json = EditorStateJson.aiToolsToJson(
            mapOf("c1" to ClipAiTools())
        )
        // The clip must still exist, but carry no "look" object: an
        // identity block would grow every project's blob and let a
        // stale crop masquerade as a real edit on reopen.
        assertTrue(json.contains("\"c1\""))
        assertFalse(json.contains("\"look\""))
        assertTrue(EditorStateJson.aiToolsFromJson(json).getValue("c1").look.isIdentity)
    }

    @Test
    fun `a clip whose only change is a cleared fade keeps its effect`() {
        // Regression guard: partial blocks must round-trip as partial
        // blocks, not be rejected wholesale.
        val look = ClipLookSettings(videoEffect = VideoEffect.VIVID, opacity = 0.4f)
        val back = roundTrip(look)
        assertEquals(VideoEffect.VIVID, back.videoEffect)
        assertEquals(0.4f, back.opacity, 0.001f)
        assertEquals(0L, back.fadeInMs)
        assertEquals(FillMode.FIT, back.fillMode)
    }

    // ---- 3. legacy and hostile blobs ----

    @Test
    fun `a legacy blob with no look key decodes to identity`() {
        val back = EditorStateJson.aiToolsFromJson(
            """{"c1":{"voiceCleanEnabled":true,"voiceCleanStrength":0.6,"voiceCleanAlgorithm":"rnnoise-v1"}}"""
        )
        assertTrue(back.getValue("c1").look.isIdentity)
    }

    @Test
    fun `an empty blob decodes to an empty map`() {
        assertTrue(EditorStateJson.aiToolsFromJson("{}").isEmpty())
        assertTrue(EditorStateJson.aiToolsFromJson(null).isEmpty())
        assertTrue(EditorStateJson.aiToolsFromJson("not json").isEmpty())
    }

    @Test
    fun `an unknown effect name falls back to NONE rather than throwing`() {
        val back = EditorStateJson.aiToolsFromJson("""{"c1":{"look":{"effect":"NOT_A_REAL_EFFECT"}}}""")
        assertEquals(VideoEffect.NONE, back.getValue("c1").look.videoEffect)
    }

    @Test
    fun `an unknown fill mode falls back to FIT rather than throwing`() {
        val back = EditorStateJson.aiToolsFromJson("""{"c1":{"look":{"fill":"STRETCH"}}}""")
        assertEquals(FillMode.FIT, back.getValue("c1").look.fillMode)
    }

    @Test
    fun `out-of-range numbers are clamped on decode`() {
        val back = EditorStateJson.aiToolsFromJson(
            """{"c1":{"look":{"opacity":42.0,"fadeIn":-10,"fadeOut":99999999,"transform":{"sc":99.0,"ox":-9.0,"oy":9.0,"rt":45}}}}"""
        )
        val look = back.getValue("c1").look
        assertEquals(1f, look.opacity, 0.001f)
        assertEquals(0L, look.fadeInMs)
        assertEquals(600_000L, look.fadeOutMs)
        assertEquals(6f, look.transform.scale, 0.001f)
        assertEquals(-0.5f, look.transform.offsetX, 0.001f)
        assertEquals(0.5f, look.transform.offsetY, 0.001f)
        assertEquals(45, look.transform.rotation)
    }

    @Test
    fun `out-of-range grading numbers are clamped on decode`() {
        val back = EditorStateJson.aiToolsFromJson(
            """{"c1":{"look":{"grade":{"bri":5.0,"con":-9.0,"hr":4000.0}}}}"""
        )
        val g = back.getValue("c1").look.colorGrading
        assertEquals(1f, g.brightness, 0.001f)
        assertEquals(-1f, g.contrast, 0.001f)
        assertEquals(180f, g.hueRed, 0.001f)
    }

    @Test
    fun `a corrupt look object degrades without throwing`() {
        val back = EditorStateJson.aiToolsFromJson("""{"c1":{"look":42}}""")
        // `optJSONObject` on a number returns null → Identity.
        assertTrue(back.getValue("c1").look.isIdentity)
    }

    @Test
    fun `a hostile or corrupt row does not poison the sibling clips`() {
        val back = EditorStateJson.aiToolsFromJson(
            """{"c1":{"look":{"opacity":42.0}},"c2":{"look":"not-an-object"},"c3":{}}"""
        )
        assertEquals(1f, back.getValue("c1").look.opacity, 0.001f)
        assertTrue(back.getValue("c2").look.isIdentity)
        assertTrue(back.getValue("c3").look.isIdentity)
    }

    @Test
    fun `the look block coexists with the other ai tool fields`() {
        // The regression guard from the report: a clip carrying a ramp,
        // highlights and a look block must round-trip all three, so a
        // future field cannot silently drop a neighbour.
        val look = ClipLookSettings(videoEffect = VideoEffect.WARM, fadeInMs = 400L)
        val json = EditorStateJson.aiToolsToJson(
            mapOf(
                "c1" to ClipAiTools(
                    look = look,
                    highlightRanges = listOf(
                        com.aicreatorstudio.app.model.HighlightRange(1000L, 4000L, 0.8f, "X")
                    ),
                    speedCurve = com.aicreatorstudio.app.model.SpeedCurve(
                        points = listOf(
                            com.aicreatorstudio.app.model.SpeedPoint(0f, 1f),
                            com.aicreatorstudio.app.model.SpeedPoint(1f, 2f)
                        )
                    )
                )
            )
        )
        val back = EditorStateJson.aiToolsFromJson(json).getValue("c1")
        assertEquals(VideoEffect.WARM, back.look.videoEffect)
        assertEquals(400L, back.look.fadeInMs)
        assertEquals(1, back.highlightRanges.size)
        // The ramp's own contract survives: a varying curve is kept,
        // and its second knot proves the values decoded.
        val curve = back.speedCurve
        assertTrue("an active ramp must survive alongside the look block", curve != null)
        assertEquals(2f, curve!!.points.last().speed, 0.001f)
    }
}
