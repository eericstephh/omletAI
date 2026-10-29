package com.aicreatorstudio.app.ui.screens.editor

import com.aicreatorstudio.app.export.TrackedOverlayRenderer
import com.aicreatorstudio.app.model.AutoFrameSettings
import com.aicreatorstudio.app.model.CropKeyframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure preview-placement helpers that keep the live
 * canvas aligned with the export renderers:
 *
 *  - [trackedPreviewPlacement] must mirror `TrackedOverlayRenderer`
 *    (boxOf + drawStamp placement math) exactly.
 *  - [autoFramePreviewPlacement] must mirror the (shared)
 *    `ReframePlanner.cropWindowFor` crop the export hook applies per
 *    frame.
 */
class AIPreviewPlacementTest {

    // ---------------------------------------------------------------
    // trackedPreviewPlacement
    // ---------------------------------------------------------------

    private fun trackSettings(
        anchorU: Float = 0.5f,
        anchorV: Float = 0.5f,
        scaleWithBox: Boolean = false,
        referenceBoxHeight: Float = 0.1f
    ) = com.aicreatorstudio.app.model.MotionTrackSettings(
        enabled = true,
        overlayId = "t1",
        anchorU = anchorU,
        anchorV = anchorV,
        scaleWithBox = scaleWithBox,
        referenceBoxHeight = referenceBoxHeight
    )

    /** A 0.2x0.2 box at the given centre, high confidence, found. */
    private fun keys(centers: List<Triple<Long, Float, Float>>): List<CropKeyframe> =
        centers.map { (t, x, y) ->
            CropKeyframe(
                timeMs = t, centerX = x, centerY = y,
                width = 0.2f, height = 0.2f,
                confidence = 0.9f, isFound = true
            )
        }

    @Test
    fun `authored centre maps onto box centre for default anchor`() {
        val k = keys(listOf(Triple(0L, 0.3f, 0.4f)))
        // Canvas 1000x2000; authored overlay centre at 0.25, 0.75.
        val p = trackedPreviewPlacement(
            trackSettings(), k, clipLocalMs = 0L,
            parentWidthPx = 1000f, parentHeightPx = 2000f,
            authoredCenterXf = 0.25f, authoredCenterYf = 0.75f
        )
        assertNotNull(p)
        // Box centre is (0.3, 0.4) * canvas; delta = target - authored*canvas.
        val expectedDx = 0.3f * 1000f - 0.25f * 1000f
        val expectedDy = 0.4f * 2000f - 0.75f * 2000f
        assertEquals(expectedDx, p!!.deltaXPx, 0.01f)
        assertEquals(expectedDy, p.deltaYPx, 0.01f)
        assertEquals(1f, p.scale, 0.0001f)
        assertTrue(p.alpha > 0f)
    }

    @Test
    fun `anchor position inside the box is honoured`() {
        val k = keys(listOf(Triple(0L, 0.5f, 0.5f)))
        // anchorU=1.0 → right edge of the box; box spans 0.4..0.6 ⇒ x=0.6.
        val p = trackedPreviewPlacement(
            trackSettings(anchorU = 1f), k, 0L, 1000f, 1000f, 0f, 0f
        )
        assertEquals(0.6f * 1000f - 0f, p!!.deltaXPx, 0.01f)
    }

    @Test
    fun `scaleWithBox follows reference box height and clamps like export`() {
        val k = keys(listOf(Triple(0L, 0.5f, 0.5f)))
        // Box height fraction 0.2 vs reference 0.1 → scale 2.0.
        val p = trackedPreviewPlacement(
            trackSettings(scaleWithBox = true, referenceBoxHeight = 0.1f),
            k, 0L, 1000f, 1000f, 0.5f, 0.5f
        )
        assertEquals(2f, p!!.scale, 0.01f)

        // reference 0.05 → scale 4.0, still within MAX_BOX_SCALE.
        val p2 = trackedPreviewPlacement(
            trackSettings(scaleWithBox = true, referenceBoxHeight = 0.05f),
            k, 0L, 1000f, 1000f, 0.5f, 0.5f
        )
        assertEquals(TrackedOverlayRenderer.MAX_BOX_SCALE, p2!!.scale, 0.001f)
    }

    @Test
    fun `not-found trajectory returns zero alpha`() {
        val lost = listOf(
            CropKeyframe(
                timeMs = 0L, centerX = 0.5f, centerY = 0.5f,
                width = 0.2f, height = 0.2f,
                confidence = 0f, isFound = false
            )
        )
        val p = trackedPreviewPlacement(
            trackSettings(), lost, 0L, 1000f, 1000f, 0.5f, 0.5f
        )
        assertNotNull(p)
        assertEquals(0f, p!!.alpha, 0.0001f)
    }

    @Test
    fun `trajectory without extent is ignored`() {
        // Auto Frame entries carry no extent — a tracked overlay must
        // NEVER attach to one (null → keep authored position).
        val afKeys = listOf(CropKeyframe(timeMs = 0L, centerX = 0.3f, centerY = 0.4f))
        assertNull(
            trackedPreviewPlacement(
                trackSettings(), afKeys, 0L, 1000f, 1000f, 0.5f, 0.5f
            )
        )
    }

    @Test
    fun `empty trajectory or nonexistent canvas returns null`() {
        assertNull(
            trackedPreviewPlacement(
                trackSettings(), emptyList(), 0L, 1000f, 1000f, 0.5f, 0.5f
            )
        )
        assertNull(
            trackedPreviewPlacement(
                trackSettings(), keys(listOf(Triple(0L, 0.5f, 0.5f))),
                0L, 0f, 0f, 0.5f, 0.5f
            )
        )
    }

    @Test
    fun `interpolates along the trajectory between samples`() {
        val k = keys(listOf(Triple(0L, 0.2f, 0.2f), Triple(1000L, 0.6f, 0.4f)))
        val p = trackedPreviewPlacement(
            trackSettings(), k, clipLocalMs = 500L, 1000f, 1000f, 0.5f, 0.5f
        )
        assertNotNull(p)
        // Midpoint of the box centre travel.
        val expectedCx = 0.4f
        val expectedCy = 0.3f
        val expectedDx = expectedCx * 1000f - 0.5f * 1000f
        val expectedDy = expectedCy * 1000f - 0.5f * 1000f
        assertEquals(expectedDx, p!!.deltaXPx, 0.01f)
        assertEquals(expectedDy, p.deltaYPx, 0.01f)
    }

    // ---------------------------------------------------------------
    // autoFramePreviewPlacement
    // ---------------------------------------------------------------

    private fun afSettings(
        tw: Float = 9f, th: Float = 16f,
        enabled: Boolean = true
    ) = AutoFrameSettings(
        enabled = enabled,
        targetAspectWidth = tw, targetAspectHeight = th
    )

    private fun afKeys(centers: List<Triple<Long, Float, Float>>): List<CropKeyframe> =
        centers.map { (t, x, y) -> CropKeyframe(timeMs = t, centerX = x, centerY = y) }

    @Test
    fun `disabled or empty input returns null`() {
        assertNull(
            autoFramePreviewPlacement(
                afKeys(listOf(Triple(0L, 0.5f, 0.5f))),
                afSettings(enabled = false), 0L, 16f / 9f, 360f, 640f
            )
        )
        assertNull(
            autoFramePreviewPlacement(
                emptyList(), afSettings(), 0L, 16f / 9f, 360f, 640f
            )
        )
    }

    @Test
    fun `9x16 crop of a landscape source fills a 9x16 canvas`() {
        // 1920x1080 source (aspect 1.7778), canvas 540x960 (9:16). The
        // landscape source is wider than the target: the crop window is
        // full crop height, width = height*aspect ⇒ cwFrac ≈ 0.317,
        // chFrac = 1. Displayed source rect (FIT) inside the canvas is
        // 540x303.75, so the crop must be blown up by min(540/171,
        // 960/303.75) ≈ 3.15 to cover the canvas.
        val p = autoFramePreviewPlacement(
            afKeys(listOf(Triple(0L, 0.5f, 0.5f))),
            afSettings(), 0L, 16f / 9f, 540f, 960f
        )
        assertNotNull(p)
        val scale = p!!.scale
        assertTrue("scale should be ~3.15, got $scale", scale in 3.0f..3.4f)
        // Subject dead centre → zero translation on both axes.
        assertEquals(0f, p.translationXPx, 2f)
        assertEquals(0f, p.translationYPx, 2f)
    }

    @Test
    fun `off-centre subject shifts the video by the expected translate`() {
        // Landscape source (1.7778) cropped to 9:16 on a 9:16 canvas:
        // the ~0.317-wide crop window really can move, so a subject at
        // x=0.25 keeps the window clamped to the left of full frame and
        // the video must shift RIGHT.
        val p = autoFramePreviewPlacement(
            afKeys(listOf(Triple(0L, 0.25f, 0.5f))),
            afSettings(), 0L, 16f / 9f, 540f, 960f
        )
        assertNotNull(p)
        assertTrue("expected shift, got ${p!!.translationXPx}", p.translationXPx > 100f)
        // Vertical: subject centred, crop window is full height → 0 shift.
        assertEquals(0f, p.translationYPx, 1.5f)
    }

    @Test
    fun `interpolates crop centre between keyframes`() {
        val keys = afKeys(listOf(Triple(0L, 0.2f, 0.5f), Triple(1000L, 0.8f, 0.5f)))
        val mid = autoFramePreviewPlacement(
            keys, afSettings(), 500L, 16f / 9f, 540f, 960f
        )
        val atStart = autoFramePreviewPlacement(
            keys, afSettings(), 0L, 16f / 9f, 540f, 960f
        )
        assertNotNull(mid); assertNotNull(atStart)
        // Midpoint must lie strictly between the two endpoints' x-shifts
        // (crop can travel across the wide source; the LEFT endpoint keeps
        // the window left ⇒ video shifted RIGHT (= larger tx); the right
        // endpoint the reverse).
        assertTrue(mid!!.translationXPx < atStart!!.translationXPx)
    }

    @Test
    fun `motion-track style keys with extent are ignored for auto frame`() {
        // Auto Frame and Motion Tracking normalize-space trajectories
        // share CropKeyframe; Auto Frame preview only consumes ONE made
        // by the reframe analyzer — extent entries would distort the
        // aspect math, so they are filtered upstream (the ViewModel
        // clears one when the other runs). The math must still hold if
        // extents are present: the placement is still a valid crop.
        val mixed = listOf(
            CropKeyframe(
                timeMs = 0L, centerX = 0.5f, centerY = 0.5f,
                width = 0.2f, height = 0.2f, confidence = 0.9f, isFound = true
            )
        )
        val p = autoFramePreviewPlacement(
            mixed, afSettings(), 0L, 16f / 9f, 540f, 960f
        )
        // The crop math ignores the extents by design; it must NOT
        // produce nonsense scale (zero/negative/NaN).
        assertFalse(p!!.scale < 1f)
        assertTrue(p.scale.isFinite())
    }
}
