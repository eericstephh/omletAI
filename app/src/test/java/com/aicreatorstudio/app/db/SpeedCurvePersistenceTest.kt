package com.aicreatorstudio.app.db

import com.aicreatorstudio.app.model.SpeedCurve
import com.aicreatorstudio.app.model.SpeedCurvePreset
import com.aicreatorstudio.app.model.SpeedPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persistence tests for the Speed Ramping curve, which rides the
 * AI-tools JSON blob.
 *
 * These matter more than the average codec test because of the failure
 * mode they guard. A curve that fails to round-trip does not throw — the
 * project simply reopens with a constant-speed clip, which reads to the
 * user as "the app forgot my ramp". That is exactly the bug class this
 * project has hit repeatedly with `motionTrack` and the transitions, so
 * the round trip is asserted directly rather than assumed.
 *
 * `EditorStateJson` uses `org.json`, which under `isReturnDefaultValues
 * = true` would stub out… except that `org.json` is *not* part of
 * `android.*` — it is a real dependency on the unit-test classpath (see
 * MEMORY.md's test-stack note), so these exercise real parsing.
 */
class SpeedCurvePersistenceTest {

    private fun roundTrip(curve: SpeedCurve?): SpeedCurve? {
        val blob = EditorStateJson.aiToolsToJson(
            mapOf("clip-1" to EditorStateJson.ClipAiTools(speedCurve = curve))
        )
        return EditorStateJson.aiToolsFromJson(blob)["clip-1"]?.speedCurve
    }

    // ---- the happy path ----

    @Test
    fun `an active curve survives a round trip`() {
        val original = SpeedCurve.ofPreset(SpeedCurvePreset.HERO)
        val decoded = roundTrip(original)
        assertNotNull("the ramp must survive the save", decoded)
        assertEquals(original.points.size, decoded!!.points.size)
        assertEquals(SpeedCurvePreset.HERO, decoded.preset)
        assertTrue(decoded.preservePitch)
        original.points.forEachIndexed { i, p ->
            assertEquals(p.normalizedTime, decoded.points[i].normalizedTime, 1e-5f)
            assertEquals(p.speed, decoded.points[i].speed, 1e-5f)
        }
    }

    @Test
    fun `every preset survives a round trip`() {
        SpeedCurvePreset.entries
            .filter { it != SpeedCurvePreset.CUSTOM }
            .forEach { preset ->
                val decoded = roundTrip(SpeedCurve.ofPreset(preset))
                assertNotNull("$preset was lost", decoded)
                assertEquals("$preset changed shape", preset, decoded!!.preset)
            }
    }

    @Test
    fun `a hand-edited curve survives with its CUSTOM preset`() {
        val custom = SpeedCurve(
            points = listOf(
                SpeedPoint(0f, 0.3f),
                SpeedPoint(0.37f, 5.5f),
                SpeedPoint(0.62f, 0.15f),
                SpeedPoint(1f, 1f)
            )
        )
        val decoded = roundTrip(custom)
        assertNotNull(decoded)
        assertEquals(SpeedCurvePreset.CUSTOM, decoded!!.preset)
        assertEquals(4, decoded.points.size)
        assertEquals(0.37f, decoded.points[1].normalizedTime, 1e-5f)
        assertEquals(5.5f, decoded.points[1].speed, 1e-5f)
    }

    @Test
    fun `a false preservePitch survives`() {
        val decoded = roundTrip(
            SpeedCurve(
                points = listOf(SpeedPoint(0f, 2f), SpeedPoint(1f, 2f)),
                preservePitch = false
            )
        )
        assertNotNull(decoded)
        assertFalse(decoded!!.preservePitch)
    }

    // ---- what must NOT be persisted ----

    /**
     * The editor opens on a flat `1f` ramp, and a user who looks at the
     * curve panel and closes it must not thereby put their project on
     * the curve code path. Writing nothing is how that is guaranteed.
     */
    @Test
    fun `an all-ones curve is not persisted at all`() {
        val flat = SpeedCurve(
            points = listOf(
                SpeedPoint(0f, 1f), SpeedPoint(0.5f, 1f), SpeedPoint(1f, 1f)
            )
        )
        assertNull("a non-ramp must not be written", roundTrip(flat))
    }

    @Test
    fun `a null curve stays null`() {
        assertNull(roundTrip(null))
    }

    @Test
    fun `a single knot is not persisted`() {
        // Not a ramp, and the decoder rejects anything under two knots.
        assertNull(roundTrip(SpeedCurve(points = listOf(SpeedPoint(0.5f, 4f)))))
    }

    @Test
    fun `an empty curve is not persisted`() {
        assertNull(roundTrip(SpeedCurve(points = emptyList())))
    }

    @Test
    fun `the blob does not grow when there is no ramp`() {
        val without = EditorStateJson.aiToolsToJson(
            mapOf("clip-1" to EditorStateJson.ClipAiTools())
        )
        assertFalse(
            "an untouched clip must not carry a speedCurve key",
            without.contains("speedCurve")
        )
    }

    // ---- hostile and legacy input ----

    @Test
    fun `a legacy blob with no speedCurve key decodes to null`() {
        // This is what every project saved before today looks like.
        val legacy = """{"clip-1":{"mattingEnabled":false,"voiceCleanEnabled":false}}"""
        val tools = EditorStateJson.aiToolsFromJson(legacy)["clip-1"]
        assertNotNull(tools)
        assertNull("legacy rows must load as constant speed", tools!!.speedCurve)
    }

    @Test
    fun `a null blob decodes to an empty map without throwing`() {
        assertTrue(EditorStateJson.aiToolsFromJson(null).isEmpty())
        assertTrue(EditorStateJson.aiToolsFromJson("").isEmpty())
        assertTrue(EditorStateJson.aiToolsFromJson("not json at all").isEmpty())
    }

    @Test
    fun `a curve with a corrupt points array is dropped rather than crashing`() {
        val blob = """
            {"clip-1":{"speedCurve":{"points":"not-an-array","preset":"HERO"}}}
        """.trimIndent()
        val tools = EditorStateJson.aiToolsFromJson(blob)["clip-1"]
        assertNotNull("the clip must still load", tools)
        assertNull("an unusable ramp must decode to null", tools!!.speedCurve)
    }

    @Test
    fun `an unknown preset name decodes as CUSTOM instead of throwing`() {
        val blob = """
            {"clip-1":{"speedCurve":{"points":[{"t":0,"v":2},{"t":1,"v":2}],
            "preset":"SOMETHING_FROM_THE_FUTURE"}}}
        """.trimIndent()
        val decoded = EditorStateJson.aiToolsFromJson(blob)["clip-1"]?.speedCurve
        assertNotNull(decoded)
        assertEquals(SpeedCurvePreset.CUSTOM, decoded!!.preset)
    }

    @Test
    fun `a stored zero or negative speed is repaired so the mapping cannot stall`() {
        val blob = """
            {"clip-1":{"speedCurve":{"points":[{"t":0,"v":0},{"t":0.5,"v":-3},{"t":1,"v":2}]}}}
        """.trimIndent()
        val decoded = EditorStateJson.aiToolsFromJson(blob)["clip-1"]?.speedCurve
        assertNotNull(decoded)
        assertTrue(
            "a non-positive stored rate must be repaired",
            decoded!!.points.all { it.speed > 0f }
        )
    }

    @Test
    fun `stored knot times outside zero to one are clamped on decode`() {
        val blob = """
            {"clip-1":{"speedCurve":{"points":[{"t":-9,"v":1},{"t":4,"v":3}]}}}
        """.trimIndent()
        val decoded = EditorStateJson.aiToolsFromJson(blob)["clip-1"]?.speedCurve
        assertNotNull(decoded)
        assertTrue(decoded!!.points.all { it.normalizedTime in 0f..1f })
    }

    // ---- coexistence with the other per-clip AI fields ----

    /**
     * The curve shares a blob with matting / auto frame / motion track /
     * voice clean / OCR. Adding it must not disturb any of them — this
     * is the "one field dropped from the map" bug class, asserted
     * against the real codec.
     */
    @Test
    fun `a curve coexists with the other AI tool fields`() {
        val original = EditorStateJson.ClipAiTools(
            matting = com.aicreatorstudio.app.model.MattingSettings(
                enabled = true, threshold = 0.33f
            ),
            autoFrame = com.aicreatorstudio.app.model.AutoFrameSettings(
                enabled = true, smoothing = 0.7f
            ),
            cropKeyframes = listOf(
                com.aicreatorstudio.app.model.CropKeyframe(
                    timeMs = 1_200L, centerX = 0.4f, centerY = 0.6f, zoom = 1.4f,
                    width = 0.2f, height = 0.3f
                )
            ),
            motionTrack = com.aicreatorstudio.app.model.MotionTrackSettings(
                enabled = true,
                overlayId = "ov-9",
                maskMode = com.aicreatorstudio.app.model.MotionTrackSettings.MaskMode.BLUR,
                maskStrength = 0.8f
            ),
            voiceClean = com.aicreatorstudio.app.model.VoiceCleanSettings(
                enabled = true, strength = 0.9f
            ),
            ocrResult = """{"text":"سلام"}""",
            speedCurve = SpeedCurve.ofPreset(SpeedCurvePreset.BULLET)
        )
        val blob = EditorStateJson.aiToolsToJson(mapOf("clip-1" to original))
        val decoded = EditorStateJson.aiToolsFromJson(blob)["clip-1"]!!

        assertNotNull("speedCurve was dropped", decoded.speedCurve)
        assertTrue("matting was dropped", decoded.matting.enabled)
        assertEquals(0.33f, decoded.matting.threshold, 1e-3f)
        assertTrue("autoFrame was dropped", decoded.autoFrame.enabled)
        assertEquals(1, decoded.cropKeyframes.size)
        assertEquals(0.4f, decoded.cropKeyframes[0].centerX, 1e-3f)
        assertEquals(
            "motionTrack was dropped",
            com.aicreatorstudio.app.model.MotionTrackSettings.MaskMode.BLUR,
            decoded.motionTrack.maskMode
        )
        assertEquals("ov-9", decoded.motionTrack.overlayId)
        assertTrue("voiceClean was dropped", decoded.voiceClean.enabled)
        assertEquals("""{"text":"سلام"}""", decoded.ocrResult)
    }

    @Test
    fun `a clip with only a ramp still decodes`() {
        val blob = EditorStateJson.aiToolsToJson(
            mapOf("clip-1" to EditorStateJson.ClipAiTools(
                speedCurve = SpeedCurve.ofPreset(SpeedCurvePreset.MONTAGE)
            ))
        )
        assertEquals(1, EditorStateJson.aiToolsFromJson(blob).size)
    }
}
