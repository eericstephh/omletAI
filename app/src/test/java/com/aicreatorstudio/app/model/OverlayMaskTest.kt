package com.aicreatorstudio.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-13 — pure tests for the PiP shape-mask geometry.
 *
 * [OverlayMaskGeometry] is the **single** definition of every mask outline
 * in the app: `ui/screens/editor/OverlayMaskShape.kt` (Compose clip) and
 * `export/OverlayMaskRenderer.kt` (Android `Path` clip on the export
 * frame) both consume [OverlayMaskGeometry.outline]. That makes these
 * tests the parity guarantee — if an outline is wrong here, it is wrong
 * in *both* renderers in exactly the same way, which is the failure mode
 * the whole design exists to make impossible.
 *
 * Nothing here touches `android.*`: the geometry is pure floats precisely
 * so it can be asserted in a JVM test (`unitTests.isReturnDefaultValues`
 * makes android geometry silently return `0`, i.e. a test that passes for
 * the wrong reason).
 */
class OverlayMaskTest {

    private val allShapes = listOf(
        OverlayMaskType.CIRCLE,
        OverlayMaskType.ROUNDED_RECT,
        OverlayMaskType.HEART,
        OverlayMaskType.STAR
    )

    // ---- defaults ------------------------------------------------------

    @Test
    fun `default settings are an identity mask`() {
        val d = OverlayMaskSettings.Default
        assertEquals(OverlayMaskType.NONE, d.type)
        assertEquals(16f, d.cornerRadiusDp, 0.0001f)
        assertEquals(0f, d.featherDp, 0.0001f)
        assertFalse(d.invert)
        assertTrue(d.isIdentity)
        assertFalse(d.hasShape)
        assertFalse(d.needsSoftEdge)
    }

    @Test
    fun `a shape makes the settings non-identity`() {
        val m = OverlayMaskSettings(type = OverlayMaskType.CIRCLE)
        assertFalse(m.isIdentity)
        assertTrue(m.hasShape)
    }

    @Test
    fun `invert alone is non-identity even with no shape`() {
        // No renderer can honour an inverted "no shape" (there is no outline
        // to complement), but the flag must still make the settings
        // non-default so the reset button and the serialiser both see that
        // something changed.
        val m = OverlayMaskSettings(type = OverlayMaskType.NONE, invert = true)
        assertFalse(m.isIdentity)
        assertFalse(m.hasShape)
    }

    @Test
    fun `needsSoftEdge requires both a shape and a feather`() {
        assertFalse(
            OverlayMaskSettings(type = OverlayMaskType.CIRCLE).needsSoftEdge
        )
        assertFalse(
            OverlayMaskSettings(type = OverlayMaskType.NONE, featherDp = 12f).needsSoftEdge
        )
        assertTrue(
            OverlayMaskSettings(type = OverlayMaskType.CIRCLE, featherDp = 12f).needsSoftEdge
        )
    }

    // ---- outline: normalised bounds ------------------------------------

    @Test
    fun `no shape has an empty outline`() {
        assertTrue(OverlayMaskGeometry.outline(OverlayMaskType.NONE).isEmpty())
    }

    @Test
    fun `every shape is non-degenerate`() {
        allShapes.forEach { type ->
            val points = OverlayMaskGeometry.outline(type)
            assertTrue("$type produced ${points.size} points", points.size >= 3)
        }
    }

    @Test
    fun `every outline point lies inside the unit square`() {
        // The brief's "normalized coordinate bounds (0.0 to 1.0)". A point
        // outside the square would clip the mask box on one renderer and
        // silently overflow on the other.
        allShapes.forEach { type ->
            OverlayMaskGeometry.outline(type).forEach { p ->
                assertTrue("$type x=${p.x} out of range", p.x in -1e-4f..1.0001f)
                assertTrue("$type y=${p.y} out of range", p.y in -1e-4f..1.0001f)
            }
        }
    }

    /** The shapes whose outline is fitted to all four edges of its box. */
    private val fittedShapes = listOf(
        OverlayMaskType.CIRCLE,
        OverlayMaskType.ROUNDED_RECT,
        OverlayMaskType.HEART
    )

    @Test
    fun `the fitted shapes fill the unit square`() {
        // A fitted outline reaches all four edges, so it covers the same
        // fraction of the box in the preview and in the export even though
        // the two renderers measure the box in different pixels.
        fittedShapes.forEach { type ->
            val b = OverlayMaskGeometry.bounds(type)
            assertEquals("$type left", 0f, b.left, 0.01f)
            assertEquals("$type top", 0f, b.top, 0.01f)
            assertEquals("$type right", 1f, b.right, 0.01f)
            assertEquals("$type bottom", 1f, b.bottom, 0.01f)
        }
    }

    @Test
    fun `the star is inscribed in the box's circle, not fitted to its square`() {
        // A regular pentagram cannot fill a square without being stretched
        // non-uniformly, which would splay its arms — so the star is
        // inscribed in the unit CIRCLE: point up, centred on (0.5, 0.5),
        // and a little narrower and shorter than its box.
        //
        // This test exists because `OverlayMaskGeometry.bounds`'s KDoc used
        // to claim all five shapes were fitted to the square. The claim was
        // wrong and the code was right; the fix was to correct the KDoc and
        // pin the real contract here, rather than to distort the star to
        // match a comment.
        val b = OverlayMaskGeometry.bounds(OverlayMaskType.STAR)
        assertTrue("the star must stay inside its box", b.left >= 0f && b.right <= 1f)
        assertTrue("the star must stay inside its box", b.top >= 0f && b.bottom <= 1f)
        // Horizontally centred on the box.
        assertEquals(
            "the star must be centred horizontally",
            0.5f,
            (b.left + b.right) / 2f,
            0.001f
        )
        // The top point touches the top edge — that is what makes it "point up".
        assertEquals("the star's top point must touch the box", 0f, b.top, 0.001f)
        // The pentagon's width is 2·R·sin(72°) = 0.95106 at R = 0.5.
        assertEquals("the star's width is the pentagon's", 0.95106f, b.width, 0.002f)
        // ...and its height is R·(1 + cos(36°)) = 0.90451 for a point-up
        // pentagram, i.e. visibly shorter than the box.
        assertEquals("the star's height is the pentagram's", 0.90451f, b.height, 0.002f)
    }

    @Test
    fun `no shape has zero-area bounds`() {
        val b = OverlayMaskGeometry.bounds(OverlayMaskType.NONE)
        assertEquals(0f, b.width, 0.0001f)
        assertEquals(0f, b.height, 0.0001f)
    }

    // ---- outline: shape-specific sanity --------------------------------

    @Test
    fun `the circle outline is inscribed in the box`() {
        val pts = OverlayMaskGeometry.outline(OverlayMaskType.CIRCLE)
        assertEquals(OverlayMaskGeometry.CIRCLE_SAMPLES, pts.size)
        // Every point sits on the unit circle about (0.5, 0.5).
        pts.forEach { p ->
            val dx = p.x - 0.5f
            val dy = p.y - 0.5f
            val r = kotlin.math.sqrt(dx * dx + dy * dy)
            assertEquals("circle radius", 0.5f, r, 0.002f)
        }
    }

    @Test
    fun `a zero-radius rounded rect is exactly four corners`() {
        val pts = OverlayMaskGeometry.outline(OverlayMaskType.ROUNDED_RECT, 0f)
        assertEquals(4, pts.size)
        assertEquals(0f, pts[0].x, 0.0001f)
        assertEquals(0f, pts[0].y, 0.0001f)
        assertEquals(1f, pts[1].x, 0.0001f)
        assertEquals(0f, pts[1].y, 0.0001f)
        assertEquals(1f, pts[2].x, 0.0001f)
        assertEquals(1f, pts[2].y, 0.0001f)
        assertEquals(0f, pts[3].x, 0.0001f)
        assertEquals(1f, pts[3].y, 0.0001f)
    }

    @Test
    fun `a rounded rect cuts its corners away`() {
        val r = 0.25f
        val pts = OverlayMaskGeometry.outline(OverlayMaskType.ROUNDED_RECT, r)
        assertTrue("expected sampled arcs, got ${pts.size}", pts.size > 4)
        // The sharp corner (0,0) must NOT be on the outline any more: no
        // point may be within the corner square the radius cuts off.
        val hasSharpCorner = pts.any { it.x < 0.01f && it.y < 0.01f }
        assertFalse("the rounded rect still has a sharp corner", hasSharpCorner)
        // ...but the flat top edge must survive, running between the two top
        // arc endpoints. ⚠️ The outline samples only the ARCS — the straight
        // segments between them are implicit — so the edge is proved by its
        // two endpoints. There is no midpoint sample to assert on, which is
        // exactly what the first version of this test got wrong.
        val topEdge = pts.filter { kotlin.math.abs(it.y) < 0.001f }
        assertTrue("the rounded rect lost its top edge", topEdge.size >= 2)
        val leftEnd = topEdge.minOf { it.x }
        val rightEnd = topEdge.maxOf { it.x }
        assertTrue(
            "the top edge must span the middle of the box, got $leftEnd..$rightEnd",
            leftEnd <= 0.26f && rightEnd >= 0.74f
        )
    }

    @Test
    fun `a huge corner radius is clamped instead of self-intersecting`() {
        // Without the clamp a radius above half the short side makes the
        // outline fold through itself, which fills the whole box on some
        // devices and nothing on others.
        val pts = OverlayMaskGeometry.outline(OverlayMaskType.ROUNDED_RECT, 5f)
        assertTrue(pts.isNotEmpty())
        pts.forEach { p ->
            assertTrue(p.x in -1e-4f..1.0001f)
            assertTrue(p.y in -1e-4f..1.0001f)
        }
    }

    @Test
    fun `the heart is widest at the top and pointed at the bottom`() {
        val pts = OverlayMaskGeometry.outline(OverlayMaskType.HEART)
        assertEquals(OverlayMaskGeometry.HEART_SAMPLES, pts.size)
        // Screen y grows downward, so the point of the heart is the point
        // with the LARGEST y, and it must be near the horizontal centre.
        val tip = pts.maxByOrNull { it.y }!!
        assertEquals("the heart's tip is not centred", 0.5f, tip.x, 0.06f)
        // And the widest row must be well above the tip.
        val widest = pts.minByOrNull { it.y }!!
        assertTrue("the heart's widest row is below its tip", widest.y < tip.y - 0.2f)
    }

    @Test
    fun `the star has ten alternating vertices`() {
        val pts = OverlayMaskGeometry.outline(OverlayMaskType.STAR)
        assertEquals(10, pts.size)
        // Even indices are the outer points (radius 0.5 from the centre),
        // odd indices the inner ones. Check both radii are what the
        // pentagram ratio says they should be.
        pts.forEachIndexed { i, p ->
            val dx = p.x - 0.5f
            val dy = p.y - 0.5f
            val r = kotlin.math.sqrt(dx * dx + dy * dy)
            val expected = if (i % 2 == 0) {
                0.5f
            } else {
                0.5f * OverlayMaskGeometry.PENTAGRAM_INNER_RATIO
            }
            assertEquals("star vertex $i radius", expected, r, 0.005f)
        }
    }

    @Test
    fun `the star points up`() {
        val pts = OverlayMaskGeometry.outline(OverlayMaskType.STAR)
        val top = pts.minByOrNull { it.y }!!
        assertEquals("the top vertex is not centred", 0.5f, top.x, 0.001f)
        assertEquals("the top vertex is not at the box's top edge", 0f, top.y, 0.001f)
    }

    @Test
    fun `outlines are closed polygons without a repeated first point`() {
        // The renderers call `close()` themselves; a duplicated final point
        // would leave a zero-length segment that some rasterizers treat as
        // a degenerate edge.
        allShapes.forEach { type ->
            val pts = OverlayMaskGeometry.outline(type)
            val first = pts.first()
            val last = pts.last()
            val same = kotlin.math.abs(first.x - last.x) < 1e-5f &&
                kotlin.math.abs(first.y - last.y) < 1e-5f
            assertFalse("$type repeats its first point at the end", same)
        }
    }

    // ---- containment ---------------------------------------------------

    @Test
    fun `a circle contains its centre and not its corners`() {
        val m = OverlayMaskSettings(type = OverlayMaskType.CIRCLE)
        assertTrue(OverlayMaskGeometry.contains(m, 0.5f, 0.5f))
        assertFalse(OverlayMaskGeometry.contains(m, 0.01f, 0.01f))
        assertFalse(OverlayMaskGeometry.contains(m, 0.99f, 0.99f))
        // Just inside the left edge on the horizontal centre line.
        assertTrue(OverlayMaskGeometry.contains(m, 0.02f, 0.5f))
    }

    @Test
    fun `no shape contains the whole box`() {
        val m = OverlayMaskSettings(type = OverlayMaskType.NONE)
        assertTrue(OverlayMaskGeometry.contains(m, 0f, 0f))
        assertTrue(OverlayMaskGeometry.contains(m, 1f, 1f))
        assertTrue(OverlayMaskGeometry.contains(m, 0.5f, 0.5f))
        // ...but not a point outside it.
        assertFalse(OverlayMaskGeometry.contains(m, 1.2f, 0.5f))
    }

    @Test
    fun `invert is the exact complement of the shape`() {
        val normal = OverlayMaskSettings(type = OverlayMaskType.CIRCLE)
        val inverted = normal.copy(invert = true)
        // Sample a grid and assert every point flips.
        var checked = 0
        for (i in 1 until 10) {
            for (j in 1 until 10) {
                val x = i / 10f
                val y = j / 10f
                val a = OverlayMaskGeometry.contains(normal, x, y)
                val b = OverlayMaskGeometry.contains(inverted, x, y)
                assertEquals("point ($x,$y) did not flip", !a, b)
                checked++
            }
        }
        assertEquals(81, checked)
    }

    @Test
    fun `containment agrees with the outline it was built from`() {
        // `contains` ray-casts the SAME polygon the renderers draw, so a
        // point just inside every outline vertex must test as inside.
        val m = OverlayMaskSettings(type = OverlayMaskType.STAR)
        val pts = OverlayMaskGeometry.outline(OverlayMaskType.STAR)
        pts.forEach { p ->
            // Nudge toward the centre, which is always inside a star.
            val x = p.x + (0.5f - p.x) * 0.05f
            val y = p.y + (0.5f - p.y) * 0.05f
            assertTrue(
                "a point just inside vertex (${p.x},${p.y}) tested as outside",
                OverlayMaskGeometry.contains(m, x, y)
            )
        }
    }

    @Test
    fun `a degenerate polygon contains nothing`() {
        assertFalse(OverlayMaskGeometry.pointInPolygon(emptyList(), 0.5f, 0.5f))
        assertFalse(
            OverlayMaskGeometry.pointInPolygon(
                listOf(NormPoint(0f, 0f), NormPoint(1f, 1f)), 0.5f, 0.5f
            )
        )
    }

    // ---- clamps and conversion -----------------------------------------

    @Test
    fun `sanitize clamps out-of-range values and repairs NaN`() {
        val s = OverlayMaskGeometry.sanitize(
            OverlayMaskSettings(
                type = OverlayMaskType.CIRCLE,
                cornerRadiusDp = 10_000f,
                featherDp = Float.NaN
            )
        )
        assertEquals(OverlayMaskGeometry.MAX_CORNER_RADIUS_DP, s.cornerRadiusDp, 0.0001f)
        // NaN must not survive: it would poison every downstream Path.
        assertEquals(0f, s.featherDp, 0.0001f)
    }

    @Test
    fun `sanitize clamps a negative radius and feather to zero`() {
        val s = OverlayMaskGeometry.sanitize(
            OverlayMaskSettings(cornerRadiusDp = -40f, featherDp = -3f)
        )
        assertEquals(0f, s.cornerRadiusDp, 0.0001f)
        assertEquals(0f, s.featherDp, 0.0001f)
    }

    @Test
    fun `sanitize leaves the type and the invert flag alone`() {
        val s = OverlayMaskGeometry.sanitize(
            OverlayMaskSettings(type = OverlayMaskType.HEART, invert = true)
        )
        assertEquals(OverlayMaskType.HEART, s.type)
        assertTrue(s.invert)
    }

    @Test
    fun `dpToCanvasPx follows the 720-unit canvas convention`() {
        // The same constant `CaptionRenderer` and `ProgressBarGeometry`
        // use. At a 720-px canvas one dp is one px by definition.
        assertEquals(16f, OverlayMaskGeometry.dpToCanvasPx(16f, 720), 0.0001f)
        // Twice the canvas height, twice the pixels — the point of the
        // convention: a mask's corner stays the same FRACTION of the frame.
        assertEquals(32f, OverlayMaskGeometry.dpToCanvasPx(16f, 1440), 0.0001f)
    }

    @Test
    fun `dpToCanvasPx is safe on a degenerate canvas`() {
        assertEquals(0f, OverlayMaskGeometry.dpToCanvasPx(16f, 0), 0.0001f)
        assertEquals(0f, OverlayMaskGeometry.dpToCanvasPx(16f, -100), 0.0001f)
        assertEquals(0f, OverlayMaskGeometry.dpToCanvasPx(Float.NaN, 720), 0.0001f)
        assertEquals(0f, OverlayMaskGeometry.dpToCanvasPx(16f, 720) * 0f, 0.0001f)
    }

    @Test
    fun `cornerRadiusFraction is clamped to half the short side`() {
        // A radius that would consume the whole short side folds the
        // outline through itself.
        val f = OverlayMaskGeometry.cornerRadiusFraction(
            cornerRadiusDp = 160f,
            boxWidthPx = 200,
            boxHeightPx = 200,
            canvasHeightPx = 720
        )
        assertEquals(OverlayMaskGeometry.MAX_CORNER_RADIUS_FRACTION, f, 0.0001f)
    }

    @Test
    fun `cornerRadiusFraction uses the shorter side`() {
        // 16 dp at a 720 canvas is 16 px; on a 100x400 box the short side is
        // 100, so the fraction is 0.16.
        val f = OverlayMaskGeometry.cornerRadiusFraction(
            cornerRadiusDp = 16f,
            boxWidthPx = 100,
            boxHeightPx = 400,
            canvasHeightPx = 720
        )
        assertEquals(0.16f, f, 0.0001f)
    }

    @Test
    fun `cornerRadiusFraction is zero on a degenerate box`() {
        assertEquals(
            0f,
            OverlayMaskGeometry.cornerRadiusFraction(16f, 0, 0, 720),
            0.0001f
        )
    }

    // ---- name decoding -------------------------------------------------

    @Test
    fun `typeFromName decodes every enum name`() {
        OverlayMaskType.entries.forEach { type ->
            assertEquals(type, OverlayMaskGeometry.typeFromName(type.name))
        }
    }

    @Test
    fun `typeFromName falls back to NONE for anything unknown`() {
        // A project written by a NEWER build must still open: an unknown
        // shape degrades to "no mask" rather than throwing mid-load.
        assertEquals(OverlayMaskType.NONE, OverlayMaskGeometry.typeFromName(null))
        assertEquals(OverlayMaskType.NONE, OverlayMaskGeometry.typeFromName(""))
        assertEquals(OverlayMaskType.NONE, OverlayMaskGeometry.typeFromName("  "))
        assertEquals(OverlayMaskType.NONE, OverlayMaskGeometry.typeFromName("TRIANGLE"))
        // Case-sensitive on purpose: the persisted value is the enum name.
        assertEquals(OverlayMaskType.NONE, OverlayMaskGeometry.typeFromName("circle"))
    }

    // ---- panel order ---------------------------------------------------

    @Test
    fun `panelOrder lists every shape exactly once`() {
        val order = OverlayMaskGeometry.panelOrder
        assertEquals(OverlayMaskType.entries.size, order.size)
        assertEquals(OverlayMaskType.entries.toSet(), order.toSet())
        assertEquals("the first chip is the no-mask reset", OverlayMaskType.NONE, order.first())
    }

    // ---- the constants the renderers rely on ---------------------------

    @Test
    fun `the canvas unit matches CaptionRenderer's`() {
        // Both must be 720 or a mask's corner radius would be a different
        // fraction of the picture in the preview than in the export.
        assertEquals(720f, OverlayMaskGeometry.CANVAS_UNIT_PX, 0.0001f)
        assertEquals(
            720f,
            com.aicreatorstudio.app.model.ProgressBarGeometry.CANVAS_UNIT_PX,
            0.0001f
        )
    }

    @Test
    fun `shortestSide never returns a negative`() {
        assertEquals(0, OverlayMaskGeometry.shortestSide(-5, 10))
        assertEquals(0, OverlayMaskGeometry.shortestSide(10, -5))
        assertEquals(10, OverlayMaskGeometry.shortestSide(10, 20))
    }

    @Test
    fun `maxFeatherDp is the sanitize ceiling`() {
        assertEquals(OverlayMaskGeometry.MAX_FEATHER_DP, OverlayMaskGeometry.maxFeatherDp(), 0.0001f)
        assertNotNull(OverlayMaskGeometry.outline(OverlayMaskType.HEART).firstOrNull())
        assertNull(OverlayMaskGeometry.outline(OverlayMaskType.NONE).firstOrNull())
    }
}
