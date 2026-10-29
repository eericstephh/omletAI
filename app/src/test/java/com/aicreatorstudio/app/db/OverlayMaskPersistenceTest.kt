package com.aicreatorstudio.app.db

import com.aicreatorstudio.app.model.OverlayMaskGeometry
import com.aicreatorstudio.app.model.OverlayMaskSettings
import com.aicreatorstudio.app.model.OverlayMaskType
import com.aicreatorstudio.app.model.VideoOverlay
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persistence coverage for the 2026-10-13 **PiP shape masks**.
 *
 * ## Where the values live
 *
 * There is no `maskType` column and no Room migration. An overlay's mask
 * rides the project's existing `mediaOverlaysJson` blob, which is a single
 * `TEXT` column — so adding three fields costs no schema version bump, and
 * a project written by a build that predates the feature decodes with the
 * defaults rather than failing to open.
 *
 * The blob is the *only* thing standing between "the user picked a heart"
 * and "the app forgot". So the two failure modes worth a test are exactly
 * these:
 *
 *  1. the round-trip losing or corrupting a value, and
 *  2. the **write-when-non-default** rule regressing, which would grow
 *     every project's blob and make a stale value indistinguishable from a
 *     real edit.
 *
 * (The third — `RoomProjectRepository` actually *calling* the codec — is a
 * one-line wiring risk with no JVM surface, and is guarded by
 * `OverlayMaskWiringTest`'s source-shape assertion instead. Room itself
 * needs Robolectric, which this project does not have.)
 *
 * Nothing here touches `android.*`: `OverlayMaskGeometry` and the codec are
 * pure, so these assertions run against the real implementations rather
 * than a stub. That matters because `unitTests.isReturnDefaultValues = true`
 * makes `android.*` silently return `0`/`0f` in this source set — a test
 * that leaned on an `android.graphics.Path` would pass for the wrong
 * reason.
 */
class OverlayMaskPersistenceTest {

    /** An overlay with an explicit id so the serialised bytes are stable. */
    private fun overlay(
        id: String = "ov-1",
        mask: OverlayMaskSettings = OverlayMaskSettings.Default,
        aspectRatio: Float = 1f,
        rotationDegrees: Float = 0f
    ) = VideoOverlay(
        id = id,
        clipId = "clip-1",
        startTimeMs = 0L,
        endTimeMs = 5_000L,
        uri = "content://media/1",
        label = "facecam",
        isVideo = true,
        aspectRatio = aspectRatio,
        rotationDegrees = rotationDegrees,
        maskSettings = mask
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

    // ---- the "write nothing" rule ------------------------------------

    @Test
    fun `an untouched overlay writes no mask key at all`() {
        // The whole point of `isIdentity`. A project whose overlays all
        // carry the default must serialise to exactly the bytes it did
        // before this feature existed, so reopening it cannot churn the
        // file — the same rule `voiceEffect`, `speedCurve` and `look`
        // already follow.
        val json = EditorStateJson.mediaOverlaysToJson(listOf(overlay()))

        assertFalse("no mask key may be written: $json", json.contains("mask"))
        assertFalse("no aspect key may be written: $json", json.contains("\"aspect\""))
        assertFalse("no rot key may be written: $json", json.contains("\"rot\""))
    }

    @Test
    fun `an untouched overlay serialises to exactly the pre-feature key set`() {
        // Stronger than the substring checks above: it pins the complete
        // key set, so a future field that forgets its own
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
    fun `an untouched overlay round-trips unchanged`() {
        val original = overlay()
        val decoded = decodeOne(EditorStateJson.mediaOverlaysToJson(listOf(original)))
        assertEquals(original, decoded)
        assertEquals(OverlayMaskSettings.Default, decoded.maskSettings)
        assertEquals(OverlayMaskType.NONE, decoded.maskSettings.type)
        assertTrue(decoded.maskSettings.isIdentity)
    }

    // ---- shape round-trip --------------------------------------------

    @Test
    fun `every mask type survives the round-trip`() {
        // Built with the DEFAULT corner radius so exact equality holds for
        // every shape — see the radius test below for why a non-rounded
        // shape deliberately does not carry one.
        val overlays = OverlayMaskType.entries.map { type ->
            overlay(
                id = "ov-${type.name}",
                mask = OverlayMaskSettings(type = type)
            )
        }
        val decoded = EditorStateJson.mediaOverlaysFromJson(
            EditorStateJson.mediaOverlaysToJson(overlays)
        )
        assertEquals(overlays.size, decoded.size)
        OverlayMaskType.entries.forEachIndexed { index, type ->
            assertEquals(
                "$type did not survive the overlay round-trip",
                type,
                decoded[index].maskSettings.type
            )
        }
        assertEquals(overlays, decoded)
    }

    @Test
    fun `the rounded rect radius survives the round-trip`() {
        val mask = OverlayMaskSettings(
            type = OverlayMaskType.ROUNDED_RECT,
            cornerRadiusDp = 42f
        )
        val json = EditorStateJson.mediaOverlaysToJson(listOf(overlay(mask = mask)))
        assertTrue(
            "the radius must be written for a rounded rect: $json",
            json.contains("\"maskRadius\":42")
        )
        assertEquals(42f, decodeOne(json).maskSettings.cornerRadiusDp)
    }

    @Test
    fun `a non-rounded shape does not persist a corner radius`() {
        // Deliberate, not an oversight: the radius has no effect on a
        // circle, a heart or a star, so writing it would grow the blob
        // with a value no renderer reads. The cost is that the radius is
        // not remembered across a shape switch and a save — which is
        // invisible, because switching back to ROUNDED_RECT re-seeds it
        // from the model default.
        OverlayMaskType.entries
            .filter { it != OverlayMaskType.NONE && it != OverlayMaskType.ROUNDED_RECT }
            .forEach { type ->
                val json = EditorStateJson.mediaOverlaysToJson(
                    listOf(overlay(mask = OverlayMaskSettings(type = type, cornerRadiusDp = 42f)))
                )
                assertFalse(
                    "a $type mask must not carry a corner radius: $json",
                    json.contains("maskRadius")
                )
                assertEquals(
                    "a $type mask must decode with the default radius",
                    OverlayMaskSettings.Default.cornerRadiusDp,
                    decodeOne(json).maskSettings.cornerRadiusDp
                )
            }
    }

    @Test
    fun `feather is written only when it is non-zero`() {
        val noFeather = EditorStateJson.mediaOverlaysToJson(
            listOf(overlay(mask = OverlayMaskSettings(type = OverlayMaskType.CIRCLE)))
        )
        assertFalse("a zero feather must not be written: $noFeather", noFeather.contains("maskFeather"))

        val feathered = OverlayMaskSettings(type = OverlayMaskType.CIRCLE, featherDp = 12f)
        val json = EditorStateJson.mediaOverlaysToJson(listOf(overlay(mask = feathered)))
        assertTrue("a real feather must be written: $json", json.contains("\"maskFeather\":12"))
        assertEquals(12f, decodeOne(json).maskSettings.featherDp)
    }

    @Test
    fun `invert is written only when it is true`() {
        val off = EditorStateJson.mediaOverlaysToJson(
            listOf(overlay(mask = OverlayMaskSettings(type = OverlayMaskType.CIRCLE)))
        )
        assertFalse("a false invert must not be written: $off", off.contains("maskInvert"))

        val on = OverlayMaskSettings(type = OverlayMaskType.CIRCLE, invert = true)
        val json = EditorStateJson.mediaOverlaysToJson(listOf(overlay(mask = on)))
        assertTrue("a true invert must be written: $json", json.contains("\"maskInvert\":true"))
        assertTrue(decodeOne(json).maskSettings.invert)
    }

    @Test
    fun `an inverted NONE mask is persisted, because it is not the identity`() {
        // The edge case the `isIdentity` definition creates: `NONE` alone
        // does nothing, but `NONE` + `invert` is a full-frame knockout.
        // Treating it as the default would silently drop a visible edit.
        val mask = OverlayMaskSettings(type = OverlayMaskType.NONE, invert = true)
        assertFalse("NONE + invert is not the identity", mask.isIdentity)

        val json = EditorStateJson.mediaOverlaysToJson(listOf(overlay(mask = mask)))
        assertTrue("the type must be written: $json", json.contains("\"maskType\":\"NONE\""))
        assertTrue("the invert must be written: $json", json.contains("\"maskInvert\":true"))

        val decoded = decodeOne(json).maskSettings
        assertEquals(OverlayMaskType.NONE, decoded.type)
        assertTrue(decoded.invert)
        assertEquals(mask, decoded)
    }

    @Test
    fun `every field of a fully loaded mask survives together`() {
        val mask = OverlayMaskSettings(
            type = OverlayMaskType.ROUNDED_RECT,
            cornerRadiusDp = 24f,
            featherDp = 8f,
            invert = true
        )
        val original = overlay(mask = mask, aspectRatio = 1.7777f, rotationDegrees = 90f)
        val decoded = decodeOne(EditorStateJson.mediaOverlaysToJson(listOf(original)))

        assertEquals(mask, decoded.maskSettings)
        assertEquals(1.7777f, decoded.aspectRatio)
        assertEquals(90f, decoded.rotationDegrees)
        assertEquals(original, decoded)
    }

    @Test
    fun `the box geometry fields round-trip`() {
        // `aspect` / `rot` are not mask fields, but they were appended to
        // `VideoOverlay` in the same change and are written by the same
        // rule — an untouched overlay omits them, an edited one keeps them.
        val original = overlay(aspectRatio = 0.5625f, rotationDegrees = -45f)
        val json = EditorStateJson.mediaOverlaysToJson(listOf(original))
        assertTrue("the aspect must be written: $json", json.contains("\"aspect\":0.5625"))
        assertTrue("the rotation must be written: $json", json.contains("\"rot\":-45"))

        val decoded = decodeOne(json)
        assertEquals(0.5625f, decoded.aspectRatio)
        assertEquals(-45f, decoded.rotationDegrees)
    }

    // ---- decoding a foreign or damaged value -------------------------

    @Test
    fun `a row from an older build decodes to no mask`() {
        // Hand-crafted: a row written before this feature existed. It has
        // no `maskType`, `maskRadius`, `maskFeather`, `maskInvert`,
        // `aspect` or `rot` key at all, and must load with the defaults
        // rather than throwing or producing a half-built overlay.
        val legacy = JSONArray().put(
            JSONObject().apply {
                put("id", "legacy")
                put("clipId", "clip-legacy")
                put("startMs", 0L)
                put("endMs", 2_000L)
                put("x", 0.1)
                put("y", 0.1)
                put("scale", 0.3)
                put("opacity", 1.0)
                put("uri", "content://media/legacy")
                put("label", "legacy")
                put("isVideo", true)
            }
        ).toString()

        val decoded = decodeOne(legacy)
        assertEquals(OverlayMaskSettings.Default, decoded.maskSettings)
        assertEquals(1f, decoded.aspectRatio)
        assertEquals(0f, decoded.rotationDegrees)
        assertTrue(decoded.maskSettings.isIdentity)
    }

    @Test
    fun `an unknown mask type decodes to NONE rather than throwing`() {
        // A project written by a NEWER build that added a sixth shape must
        // still open, just without the mask — the same forward-compatible
        // rule `VoiceEffect` uses. The alternative (throwing) would make
        // the whole project unopenable, and the alternative of guessing a
        // shape would silently change what the user sees.
        val json = JSONArray().put(
            JSONObject().apply {
                put("id", "ov-1")
                put("clipId", "clip-1")
                put("maskType", "HEXAGON")
            }
        ).toString()

        assertEquals(
            "an unreadable shape must fall back to no mask",
            OverlayMaskType.NONE,
            decodeOne(json).maskSettings.type
        )
        // And the pure decoder agrees, since that is what the codec calls.
        assertEquals(OverlayMaskType.NONE, OverlayMaskGeometry.typeFromName("HEXAGON"))
        assertEquals(OverlayMaskType.NONE, OverlayMaskGeometry.typeFromName(""))
        assertEquals(OverlayMaskType.NONE, OverlayMaskGeometry.typeFromName(null))
    }

    @Test
    fun `an out-of-range persisted radius is clamped on read`() {
        // A hand-edited or corrupt blob must not be able to hand the
        // geometry a radius that self-intersects the outline.
        val json = JSONArray().put(
            JSONObject().apply {
                put("id", "ov-1")
                put("clipId", "clip-1")
                put("maskType", "ROUNDED_RECT")
                put("maskRadius", 9_999.0)
            }
        ).toString()
        assertEquals(
            OverlayMaskGeometry.MAX_CORNER_RADIUS_DP,
            decodeOne(json).maskSettings.cornerRadiusDp
        )
    }

    @Test
    fun `an out-of-range persisted feather is clamped on read`() {
        val json = JSONArray().put(
            JSONObject().apply {
                put("id", "ov-1")
                put("clipId", "clip-1")
                put("maskType", "CIRCLE")
                put("maskFeather", 9_999.0)
            }
        ).toString()
        assertEquals(
            OverlayMaskGeometry.MAX_FEATHER_DP,
            decodeOne(json).maskSettings.featherDp
        )
    }

    @Test
    fun `a negative persisted radius is clamped to zero`() {
        val json = JSONArray().put(
            JSONObject().apply {
                put("id", "ov-1")
                put("clipId", "clip-1")
                put("maskType", "ROUNDED_RECT")
                put("maskRadius", -50.0)
            }
        ).toString()
        assertEquals(0f, decodeOne(json).maskSettings.cornerRadiusDp)
    }

    @Test
    fun `a non-boolean invert value does not invent an inversion`() {
        // org.json's optBoolean is lenient about `"true"` and a number;
        // what must never happen is a crash, or an inversion appearing
        // where the user never asked for one.
        val json = JSONArray().put(
            JSONObject().apply {
                put("id", "ov-1")
                put("clipId", "clip-1")
                put("maskType", "STAR")
                put("maskInvert", "not a boolean")
            }
        ).toString()
        val decoded = decodeOne(json)
        assertEquals(OverlayMaskType.STAR, decoded.maskSettings.type)
        assertFalse(
            "an unreadable invert must decode to false",
            decoded.maskSettings.invert
        )
    }

    // ---- degenerate inputs -------------------------------------------

    @Test
    fun `a null or blank blob decodes to an empty list`() {
        assertTrue(EditorStateJson.mediaOverlaysFromJson(null).isEmpty())
        assertTrue(EditorStateJson.mediaOverlaysFromJson("").isEmpty())
        assertTrue(EditorStateJson.mediaOverlaysFromJson("   ").isEmpty())
    }

    @Test
    fun `an empty overlay list serialises to an empty array`() {
        val json = EditorStateJson.mediaOverlaysToJson(emptyList())
        assertTrue(EditorStateJson.mediaOverlaysFromJson(json).isEmpty())
    }

    @Test
    fun `a truncated blob decodes to an empty list rather than crashing`() {
        assertTrue(EditorStateJson.mediaOverlaysFromJson("[{\"id\":").isEmpty())
    }

    @Test
    fun `a mask round-trips for every overlay in a multi-overlay project`() {
        // Guards against an encoder that writes the first element's mask
        // into every element — the kind of off-by-one a loop variable
        // captures.
        val overlays = listOf(
            overlay(id = "a", mask = OverlayMaskSettings(type = OverlayMaskType.CIRCLE)),
            overlay(id = "b", mask = OverlayMaskSettings(type = OverlayMaskType.HEART, featherDp = 6f)),
            overlay(id = "c"),
            overlay(
                id = "d",
                mask = OverlayMaskSettings(
                    type = OverlayMaskType.ROUNDED_RECT,
                    cornerRadiusDp = 20f,
                    invert = true
                )
            )
        )
        val decoded = EditorStateJson.mediaOverlaysFromJson(
            EditorStateJson.mediaOverlaysToJson(overlays)
        )
        assertEquals(overlays, decoded)
        assertEquals(OverlayMaskType.CIRCLE, decoded[0].maskSettings.type)
        assertEquals(OverlayMaskType.HEART, decoded[1].maskSettings.type)
        assertEquals(OverlayMaskType.NONE, decoded[2].maskSettings.type)
        assertEquals(OverlayMaskType.ROUNDED_RECT, decoded[3].maskSettings.type)
        assertEquals(20f, decoded[3].maskSettings.cornerRadiusDp)
        assertTrue(decoded[3].maskSettings.invert)
    }
}
