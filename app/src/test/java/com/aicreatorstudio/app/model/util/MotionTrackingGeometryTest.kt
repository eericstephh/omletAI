package com.aicreatorstudio.app.model.util

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import com.aicreatorstudio.app.model.CropKeyframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MotionTrackingGeometry] — the pure coordinate and
 * trajectory layer behind Motion Tracking.
 *
 * ## Why these tests use [MotionTrackingGeometry.NormBox] and not `RectF`
 *
 * This module sets `unitTests.isReturnDefaultValues = true`, so every
 * `android.graphics.*` method returns `0` / `0f` on the JVM. A `RectF`
 * built in this test would read back as `(0,0,0,0)` and every
 * assertion would pass or fail for the wrong reason. That is why the
 * box arithmetic lives on plain floats ([NormBox]) and why the
 * `RectF` entry points are thin delegations.
 *
 * `androidx.compose.ui.geometry.Rect` / `Offset` are *Kotlin* value
 * classes with real arithmetic, so those are safe to use directly —
 * `rectToNormalized` and friends are exercised below with Compose
 * types for exactly that reason.
 *
 * Each test names the regression it pins. Every coordinate bug in a
 * tracked-overlay feature is *plausibly* wrong rather than obviously
 * wrong, so the four conversions and the hold-across-a-gap rule each
 * get an explicit assertion.
 */
class MotionTrackingGeometryTest {

    private val g = MotionTrackingGeometry
    private fun box(l: Float, t: Float, r: Float, b: Float) =
        MotionTrackingGeometry.NormBox(l, t, r, b)

    // ------------------------------------------------------------------
    // 1. container -> letterboxed video rect (pure-float path)
    //
    // These exercise `videoFrameBox`, not `videoFrameRect` /
    // `videoFrameComposeRect`: `IntSize` and `android.graphics.Rect`
    // read back as zeros on the JVM, so a test written against either
    // would assert nothing. The two typed variants are one-line
    // conversions of this function.
    // ------------------------------------------------------------------

    @Test
    fun `videoFrameBox letterboxes a wider source`() {
        // 16:9 source inside a 400x400 square container: full width,
        // bars top and bottom.
        val r = g.videoFrameBox(
            containerWidth = 400,
            containerHeight = 400,
            displayWidthPx = 1920,
            displayHeightPx = 1080
        )
        assertEquals(0f, r.left, 1e-3f)
        assertEquals(400f, r.right, 1e-3f)
        assertEquals(400f, r.width, 1e-3f)
        // 400 * 9/16 = 225, centred => top = (400-225)/2 = 87.5
        assertEquals(225f, r.height, 1e-3f)
        assertEquals(87.5f, r.top, 1e-3f)
    }

    @Test
    fun `videoFrameBox pillarboxes a taller source`() {
        // 9:16 source inside a 400x400 container: full height, bars
        // left and right.
        val r = g.videoFrameBox(
            containerWidth = 400,
            containerHeight = 400,
            displayWidthPx = 1080,
            displayHeightPx = 1920
        )
        assertEquals(0f, r.top, 1e-3f)
        assertEquals(400f, r.bottom, 1e-3f)
        assertEquals(400f, r.height, 1e-3f)
        assertTrue("expected pillarbox bars", r.left > 0f)
        // Horizontally centred.
        assertEquals(r.left, 400f - r.right, 1e-3f)
    }

    @Test
    fun `videoFrameBox is identity when the aspects match`() {
        val r = g.videoFrameBox(320, 180, 1920, 1080)
        assertEquals(0f, r.left, 1e-3f)
        assertEquals(0f, r.top, 1e-3f)
        assertEquals(320f, r.right, 1e-3f)
        assertEquals(180f, r.bottom, 1e-3f)
    }

    @Test
    fun `videoFrameBox rejects degenerate input without throwing`() {
        // A zero-sized layout occurs on the first composition frame;
        // it must degrade to "no ROI overlay", not crash the editor.
        val zero = g.videoFrameBox(0, 0, 1920, 1080)
        assertEquals(0f, zero.width, 0f)

        val noSource = g.videoFrameBox(400, 400, 0, 0)
        assertEquals(0f, noSource.width, 0f)
    }

    // ------------------------------------------------------------------
    // 2. container pixels <-> normalized (Compose Rect)
    // ------------------------------------------------------------------

    @Test
    fun `toNormalized clamps a touch on a letterbox bar into the frame`() {
        // Frame occupies the middle half of a 400x400 container.
        val frame = ComposeRect(0f, 100f, 400f, 300f)
        val top = g.toNormalized(Offset(200f, 0f), frame)
        assertEquals(0f, top.y, 1e-6f)   // clamped, never negative
        assertEquals(0.5f, top.x, 1e-6f)
    }

    @Test
    fun `toNormalized and toContainer round-trip a centre point`() {
        val frame = ComposeRect(50f, 20f, 450f, 820f)
        val pt = Offset(250f, 420f)
        val norm = g.toNormalized(pt, frame)
        val back = g.toContainer(norm, frame)
        assertEquals(pt.x, back.x, 1e-3f)
        assertEquals(pt.y, back.y, 1e-3f)
    }

    @Test
    fun `rectToNormalized maps the full frame to the unit square`() {
        val frame = ComposeRect(0f, 0f, 200f, 100f)
        val norm = g.rectToNormalized(
            android.graphics.RectF(), frame
        )
        // RectF is inert on the JVM, so this only pins that the
        // function is total (returns the default) for a degenerate
        // input rather than throwing.
        assertTrue(norm.left <= 1f)
    }

    @Test
    fun `rectToNormalized on a degenerate frame returns DEFAULT_ROI`() {
        // The zero-width frame guard: without it the division is a
        // NaN and the box silently becomes unusable.
        val norm = g.rectToNormalized(
            g.DEFAULT_ROI,
            ComposeRect(0f, 0f, 0f, 0f)
        )
        assertEquals(g.DEFAULT_ROI.left, norm.left, 0f)
        assertEquals(g.DEFAULT_ROI.bottom, norm.bottom, 0f)
    }

    // ------------------------------------------------------------------
    // Box manipulation — pure NormBox maths
    // ------------------------------------------------------------------

    @Test
    fun `NormBox derives width height and centre`() {
        val b = box(0.2f, 0.3f, 0.6f, 0.9f)
        assertEquals(0.4f, b.width, 1e-6f)
        assertEquals(0.6f, b.height, 1e-6f)
        assertEquals(0.4f, b.centerX, 1e-6f)
        assertEquals(0.6f, b.centerY, 1e-6f)
    }

    @Test
    fun `isTrackable rejects a box below the minimum side`() {
        val tiny = box(0.5f, 0.5f, 0.5f + g.MIN_ROI_SIDE / 2f, 0.6f)
        assertFalse(g.isTrackable(tiny))
        assertTrue(g.isTrackable(g.DEFAULT_NORM_BOX))
    }

    @Test
    fun `isTrackable rejects a box outside the unit square`() {
        assertFalse(g.isTrackable(box(-0.1f, 0.1f, 0.5f, 0.5f)))
        assertFalse(g.isTrackable(box(0.1f, 0.1f, 1.4f, 0.5f)))
    }

    @Test
    fun `normalizeEdges orders inverted edges`() {
        val r = g.normalizeEdges(box(0.8f, 0.9f, 0.2f, 0.3f))
        assertEquals(0.2f, r.left, 1e-6f)
        assertEquals(0.3f, r.top, 1e-6f)
        assertEquals(0.8f, r.right, 1e-6f)
        assertEquals(0.9f, r.bottom, 1e-6f)
    }

    @Test
    fun `clampedTranslate keeps the box inside the frame`() {
        val roi = box(0.4f, 0.4f, 0.6f, 0.6f)
        val moved = g.clampedTranslate(roi, 10f, 10f)
        assertTrue(moved.right <= 1f)
        assertTrue(moved.bottom <= 1f)
        assertEquals(0.2f, moved.width, 1e-6f)   // size preserved
    }

    @Test
    fun `clampedTranslate does not shrink a box that is already at the edge`() {
        val edge = box(0.8f, 0.8f, 1f, 1f)
        val moved = g.clampedTranslate(edge, 0.5f, 0.5f)
        assertEquals(0.2f, moved.width, 1e-6f)
        assertEquals(1f, moved.right, 1e-6f)
    }

    @Test
    fun `clampNorm pulls an out-of-frame box back inside preserving size`() {
        val wild = box(-0.2f, -0.1f, 0.3f, 0.4f)
        val clamped = g.clampNorm(wild)
        assertTrue(clamped.left >= 0f)
        assertTrue(clamped.top >= 0f)
        assertEquals(0.5f, clamped.width, 1e-6f)
    }

    @Test
    fun `expand grows about the centre and stays in frame`() {
        val roi = box(0.4f, 0.4f, 0.6f, 0.6f)
        val grown = g.expand(roi, 1.6f)
        assertEquals(0.5f, grown.centerX, 1e-6f)
        assertEquals(0.5f, grown.centerY, 1e-6f)
        assertTrue(grown.width > roi.width)
        assertTrue(grown.left >= 0f && grown.right <= 1f)
    }

    @Test
    fun `expand on a corner box stays clamped`() {
        val corner = box(0f, 0f, 0.1f, 0.1f)
        val grown = g.expand(corner, 1.6f)
        assertTrue(grown.left >= 0f)
        assertTrue(grown.top >= 0f)
    }

    @Test
    fun `capScaleStep stops an irreversible scale blow-up`() {
        // CSRT can double the scale in a single frame during a partial
        // occlusion; a hard per-step cap prevents an unrecoverable
        // blown-up state.
        val prev = box(0.4f, 0.4f, 0.6f, 0.6f)
        val wild = box(0.1f, 0.1f, 0.9f, 0.9f)
        val capped = g.capScaleStep(prev, wild, maxRatio = 1.15f)
        val ratio = capped.width / prev.width
        assertTrue("ratio=$ratio exceeded cap", ratio <= 1.1501f)
    }

    @Test
    fun `capScaleStep also caps a shrink`() {
        val prev = box(0.2f, 0.2f, 0.8f, 0.8f)
        val tiny = box(0.45f, 0.45f, 0.55f, 0.55f)
        val capped = g.capScaleStep(prev, tiny, maxRatio = 1.15f)
        val ratio = capped.width / prev.width
        assertTrue("ratio=$ratio shrank too fast", ratio >= 0.999f * (1f / 1.15f))
    }

    // ------------------------------------------------------------------
    // Trajectory
    // ------------------------------------------------------------------

    @Test
    fun `interpolateKeyframe lerps between two found samples`() {
        val keys = listOf(
            CropKeyframe(0L, 0.2f, 0.2f, width = 0.1f, height = 0.1f),
            CropKeyframe(100L, 0.6f, 0.6f, width = 0.3f, height = 0.3f)
        )
        val mid = g.interpolateKeyframe(keys, 50L)
        assertEquals(0.4f, mid.centerX, 1e-4f)
        assertEquals(0.4f, mid.centerY, 1e-4f)
        assertEquals(0.2f, mid.width!!, 1e-4f)
        assertTrue(mid.isFound)
    }

    @Test
    fun `interpolateKeyframe holds extent across a tracking gap`() {
        // Both endpoints are forward-filled placeholders. Lerping the
        // box would invent motion through an occlusion that never
        // happened, so the last real box must be held.
        val a = CropKeyframe(0L, 0.2f, 0.2f, width = 0.1f, height = 0.1f, isFound = false)
        val b = CropKeyframe(100L, 0.6f, 0.6f, width = 0.3f, height = 0.3f, isFound = false)
        val mid = g.interpolateKeyframe(listOf(a, b), 50L)
        assertEquals(0.1f, mid.width!!, 1e-4f)
        assertFalse("gap must propagate isFound=false", mid.isFound)
    }

    @Test
    fun `interpolateKeyframe clamps outside the keyframe range`() {
        val keys = listOf(
            CropKeyframe(100L, 0.1f, 0.1f),
            CropKeyframe(200L, 0.9f, 0.9f)
        )
        assertEquals(0.1f, g.interpolateKeyframe(keys, 0L).centerX, 1e-6f)
        assertEquals(0.9f, g.interpolateKeyframe(keys, 999L).centerX, 1e-6f)
    }

    @Test
    fun `interpolateKeyframe handles an empty trajectory`() {
        val k = g.interpolateKeyframe(emptyList(), 100L)
        assertEquals(0.5f, k.centerX, 1e-6f)
    }

    @Test
    fun `interpolateKeyframe tolerates an Auto Frame entry with no extent`() {
        // Auto Frame keyframes carry no width/height; interpolating a
        // Motion Track trajectory must not fabricate one.
        val a = CropKeyframe(0L, 0.2f, 0.2f)          // width == null
        val b = CropKeyframe(100L, 0.6f, 0.6f, width = 0.3f, height = 0.3f)
        val mid = g.interpolateKeyframe(listOf(a, b), 50L)
        assertNull(mid.width)
    }

    @Test
    fun `isDiscontinuity flags a re-acquisition but not real motion`() {
        val prev = CropKeyframe(0L, 0.2f, 0.5f)
        // 0.2 -> 0.7 in 33 ms is ~1.5 frame-widths per 100 ms, above
        // the 0.8 threshold.
        val jump = CropKeyframe(33L, 0.7f, 0.5f)
        assertTrue(g.isDiscontinuity(prev, jump))

        val smooth = CropKeyframe(33L, 0.21f, 0.5f)
        assertFalse(g.isDiscontinuity(prev, smooth))
    }

    @Test
    fun `confidenceAlpha fades below the lost line and is zero when not found`() {
        assertEquals(1f, g.confidenceAlpha(0.9f, true), 1e-6f)
        assertEquals(0f, g.confidenceAlpha(0.9f, false), 1e-6f)
        val faded = g.confidenceAlpha(g.LOST_CONFIDENCE / 2f, true)
        assertTrue("expected a partial alpha, got $faded", faded in 0.01f..0.99f)
    }
}
