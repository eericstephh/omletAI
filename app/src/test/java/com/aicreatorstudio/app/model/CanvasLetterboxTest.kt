package com.aicreatorstudio.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tests for [CanvasLetterbox] — the pure definition behind the BG panel's
 * letterbox fill.
 *
 * The plate is the whole effect as a pixel buffer, so these tests cover the
 * full behaviour of the pass. Three properties are load-bearing and are
 * asserted directly rather than implied:
 *
 *  1. the hole is the **posed** silhouette, so a clip that zooms or pans never
 *     gets painted over and never leaves an unpainted sliver;
 *  2. the mask is **binary** and uses the pixel-centre rule, which is what makes
 *     its boundary land on the same pixels as the video's own rasterised edge;
 *  3. [pixels] and [needsPlate] agree, since the export uses the latter to
 *     decide whether the pass is worth adding at all.
 *
 * A fourth property is asserted since 2026-09-16: [CanvasLetterbox.fadeColorArgb]
 * and the plate agree on the colour. They are two consumers of one decision —
 * the plate paints the bars, the fade wash dims the clip toward them — so if
 * they ever drifted, a fade would visibly dim toward a different colour than the
 * bars beside it.
 */
class CanvasLetterboxTest {

    private val fill = 0xFF3366CCL
    private val fillRgb = 0x3366CC

    // ---------------------------------------------------------------- active

    @Test
    fun `black is never active`() {
        assertFalse(CanvasLetterbox.isActive(CanvasBackground.Black))
    }

    @Test
    fun `blur is never active`() {
        // Not implemented in the export: it needs a second video layer, not a
        // plate. Reported as a limitation rather than half-done here.
        assertFalse(CanvasLetterbox.isActive(CanvasBackground.Blur))
    }

    @Test
    fun `an opaque solid colour is active`() {
        assertTrue(CanvasLetterbox.isActive(CanvasBackground.SolidColor(fill)))
    }

    @Test
    fun `a partially transparent colour is still active`() {
        assertTrue(CanvasLetterbox.isActive(CanvasBackground.SolidColor(0x80112233L)))
    }

    @Test
    fun `a fully transparent colour is not active`() {
        // Nothing to paint: the plate would be invisible anyway.
        assertFalse(CanvasLetterbox.isActive(CanvasBackground.SolidColor(0x00112233L)))
    }

    @Test
    fun `alphaOf reads the top byte`() {
        assertEquals(0xFF, CanvasLetterbox.alphaOf(0xFF112233L))
        assertEquals(0x00, CanvasLetterbox.alphaOf(0x00112233L))
        assertEquals(0x80, CanvasLetterbox.alphaOf(0x80112233L))
    }

    // ---------------------------------------------------------- fade colour

    @Test
    fun `black fades toward black`() {
        assertEquals(0xFF000000.toInt(), CanvasLetterbox.fadeColorArgb(CanvasBackground.Black))
    }

    @Test
    fun `blur fades toward black because its bars are black in the file`() {
        // The blurred fill is not baked, so the bars a fade dims toward really
        // are black. Returning the panel colour here would make the fade
        // disagree with the bars right next to it.
        assertEquals(0xFF000000.toInt(), CanvasLetterbox.fadeColorArgb(CanvasBackground.Blur))
    }

    @Test
    fun `a solid colour fades toward that colour`() {
        assertEquals(
            0xFF3366CC.toInt(),
            CanvasLetterbox.fadeColorArgb(CanvasBackground.SolidColor(fill))
        )
    }

    @Test
    fun `the fade colour is opaque even when the swatch is not`() {
        // A letterbox is a fill, not a tint — the same reason the plate forces
        // full opacity. Honouring a partial alpha here would blend the wash
        // against Media3's transparent bars and come out darker than the
        // preview's `Color(colorValue)` over an opaque surface.
        assertEquals(
            0xFF112233.toInt(),
            CanvasLetterbox.fadeColorArgb(CanvasBackground.SolidColor(0x80112233L))
        )
    }

    @Test
    fun `a fully transparent swatch fades toward black, matching its bars`() {
        // `isActive` reports false at alpha 0, so no plate is painted and the
        // bars stay Media3's native black. The fade has to agree with them.
        assertEquals(
            0xFF000000.toInt(),
            CanvasLetterbox.fadeColorArgb(CanvasBackground.SolidColor(0x00112233L))
        )
    }

    @Test
    fun `the fade colour matches the colour the plate paints`() {
        // The wash and the bars are two consumers of one decision. If they ever
        // drifted apart, a fade would visibly dim toward a different colour
        // than the bars sitting beside it.
        val pixels = CanvasLetterbox.pixels(
            canvasWidth = 100,
            canvasHeight = 100,
            sourceWidth = 200,
            sourceHeight = 100,
            rotationDegrees = 0f,
            fillMode = FillMode.FIT,
            colorValue = fill
        )

        assertNotNull(pixels)
        // A 2:1 source contain-fitted into a 1:1 canvas leaves a bar at the
        // top-left corner, so pixel 0 is plate, not video.
        assertEquals(
            CanvasLetterbox.fadeColorArgb(CanvasBackground.SolidColor(fill)),
            pixels!![0]
        )
    }

    // -------------------------------------------------------- rotated bounds

    @Test
    fun `no rotation leaves the bounds alone`() {
        assertEquals(1920f to 1080f, CanvasLetterbox.rotatedBounds(1920, 1080, 0f))
    }

    @Test
    fun `a right angle swaps the bounds`() {
        assertEquals(1080f to 1920f, CanvasLetterbox.rotatedBounds(1920, 1080, 90f))
        assertEquals(1080f to 1920f, CanvasLetterbox.rotatedBounds(1920, 1080, -90f))
        assertEquals(1920f to 1080f, CanvasLetterbox.rotatedBounds(1920, 1080, 180f))
    }

    @Test
    fun `forty five degrees grows a square by root two`() {
        val (w, h) = CanvasLetterbox.rotatedBounds(100, 100, 45f)
        val expected = 100f * 1.4142135f
        assertTrue(abs(w - expected) < 0.01f)
        assertTrue(abs(h - expected) < 0.01f)
    }

    @Test
    fun `the bounds ignore the sign of the angle`() {
        // Media3's own `configure` computes a *signed* extent and would go
        // negative past 90deg; the slider spans -180..180.
        assertEquals(
            CanvasLetterbox.rotatedBounds(1920, 1080, 135f),
            CanvasLetterbox.rotatedBounds(1920, 1080, -135f)
        )
    }

    @Test
    fun `a non finite angle reads as no rotation`() {
        assertEquals(1920f to 1080f, CanvasLetterbox.rotatedBounds(1920, 1080, Float.NaN))
        assertEquals(
            1920f to 1080f,
            CanvasLetterbox.rotatedBounds(1920, 1080, Float.POSITIVE_INFINITY)
        )
    }

    // ------------------------------------------------------------- videoRect

    @Test
    fun `a landscape source in a portrait canvas letterboxes top and bottom`() {
        val rect = CanvasLetterbox.videoRect(1080, 1920, 1920, 1080, 0f, FillMode.FIT)
        assertNotNull(rect)
        rect!!
        assertEquals(0f, rect.left, 0.01f)
        assertEquals(1080f, rect.right, 0.01f)
        assertEquals(656.25f, rect.top, 0.01f)
        assertEquals(1263.75f, rect.bottom, 0.01f)
    }

    @Test
    fun `a source that matches the canvas aspect reports the whole canvas`() {
        // Deliberately NOT null. "Covers the canvas" used to mean "nothing to
        // paint", which is wrong once a pose exists: ZOOM_IN shrinks this clip
        // to 45% on its first frame and exposes bars.
        val rect = CanvasLetterbox.videoRect(1000, 1000, 1000, 1000, 0f, FillMode.FIT)
        assertNotNull(rect)
        assertEquals(0f, rect!!.left, 0.01f)
        assertEquals(1000f, rect.right, 0.01f)
        assertEquals(0f, rect.top, 0.01f)
        assertEquals(1000f, rect.bottom, 0.01f)
    }

    @Test
    fun `a portrait source in a square canvas letterboxes left and right`() {
        val rect = CanvasLetterbox.videoRect(10, 10, 8, 10, 0f, FillMode.FIT)
        assertNotNull(rect)
        assertEquals(1f, rect!!.left, 0.001f)
        assertEquals(9f, rect.right, 0.001f)
        assertEquals(0f, rect.top, 0.001f)
        assertEquals(10f, rect.bottom, 0.001f)
    }

    @Test
    fun `FILL covers the canvas whatever the source aspect`() {
        val rect = CanvasLetterbox.videoRect(1080, 1920, 1920, 1080, 0f, FillMode.FILL)
        assertNotNull(rect)
        assertEquals(0f, rect!!.left, 0.001f)
        assertEquals(1080f, rect.right, 0.001f)
        assertEquals(0f, rect.top, 0.001f)
        assertEquals(1920f, rect.bottom, 0.001f)
    }

    @Test
    fun `a ninety degree rotation fills the canvas it now matches`() {
        // The frame the Presentation receives is the rotated bounding box, so
        // a 1920x1080 source rotated 90deg is 1080x1920 — exactly the canvas.
        val rect = CanvasLetterbox.videoRect(1080, 1920, 1920, 1080, 90f, FillMode.FIT)
        assertNotNull(rect)
        assertEquals(0f, rect!!.left, 0.01f)
        assertEquals(0f, rect.top, 0.01f)
        assertEquals(1080f, rect.right, 0.01f)
        assertEquals(1920f, rect.bottom, 0.01f)
    }

    @Test
    fun `a degenerate canvas has no rectangle`() {
        assertNull(CanvasLetterbox.videoRect(0, 1920, 1920, 1080, 0f, FillMode.FIT))
        assertNull(CanvasLetterbox.videoRect(1080, 0, 1920, 1080, 0f, FillMode.FIT))
    }

    @Test
    fun `a degenerate source has no rectangle under FIT`() {
        assertNull(CanvasLetterbox.videoRect(1080, 1920, 0, 1080, 0f, FillMode.FIT))
        assertNull(CanvasLetterbox.videoRect(1080, 1920, 1920, 0, 0f, FillMode.FIT))
    }

    // --------------------------------------------------------- posedCorners

    @Test
    fun `the identity pose leaves the corners where they were`() {
        val rect = LetterboxRect(10f, 20f, 110f, 220f)
        val quad = CanvasLetterbox.posedCorners(rect, 200, 400, TransitionPose())
        assertEquals(10f, quad[0].x, 0.01f)
        assertEquals(20f, quad[0].y, 0.01f)
        assertEquals(110f, quad[1].x, 0.01f)
        assertEquals(20f, quad[1].y, 0.01f)
        assertEquals(110f, quad[2].x, 0.01f)
        assertEquals(220f, quad[2].y, 0.01f)
        assertEquals(10f, quad[3].x, 0.01f)
        assertEquals(220f, quad[3].y, 0.01f)
    }

    @Test
    fun `a horizontal pan of one frame width moves the silhouette by the canvas width`() {
        val rect = LetterboxRect(0f, 0f, 200f, 400f)
        val quad = CanvasLetterbox.posedCorners(
            rect, 200, 400,
            TransitionPose(translationXDp = TransitionGeometry.REFERENCE_WIDTH_DP)
        )
        assertEquals(200f, quad[0].x, 0.01f)
        assertEquals(400f, quad[1].x, 0.01f)
        assertEquals(0f, quad[0].y, 0.01f)
    }

    @Test
    fun `a positive vertical translation moves the silhouette downwards`() {
        // Screen convention: positive Y is down, which is the opposite of the
        // NDC the matrix works in. Getting the sign wrong here would put every
        // slide transition's bar on the wrong side.
        val rect = LetterboxRect(0f, 0f, 200f, 400f)
        val quad = CanvasLetterbox.posedCorners(
            rect, 200, 400,
            TransitionPose(translationYDp = TransitionGeometry.REFERENCE_HEIGHT_DP)
        )
        assertEquals(400f, quad[0].y, 0.01f)
        assertEquals(800f, quad[2].y, 0.01f)
    }

    @Test
    fun `a half scale shrinks the silhouette about the canvas centre`() {
        val rect = LetterboxRect(0f, 0f, 1000f, 1000f)
        val quad = CanvasLetterbox.posedCorners(
            rect, 1000, 1000,
            TransitionPose(scaleX = 0.5f, scaleY = 0.5f)
        )
        assertEquals(250f, quad[0].x, 0.01f)
        assertEquals(250f, quad[0].y, 0.01f)
        assertEquals(750f, quad[2].x, 0.01f)
        assertEquals(750f, quad[2].y, 0.01f)
    }

    @Test
    fun `a right angle rotation turns clockwise on screen`() {
        // Positive rotationZDeg is clockwise in Compose, which is what the
        // preview's graphicsLayer applies. Top-left must land on top-right.
        val rect = LetterboxRect(0f, 0f, 1000f, 1000f)
        val quad = CanvasLetterbox.posedCorners(
            rect, 1000, 1000,
            TransitionPose(rotationZDeg = 90f)
        )
        assertEquals(1000f, quad[0].x, 0.01f)
        assertEquals(0f, quad[0].y, 0.01f)
    }

    @Test
    fun `rotation is applied in an aspect corrected space`() {
        // Without the aspect correction the rotated rectangle would shear. The
        // check is that a 200x100 canvas rotated 90deg maps to a *congruent*
        // 100x200 rectangle, still centred.
        val rect = LetterboxRect(0f, 0f, 200f, 100f)
        val quad = CanvasLetterbox.posedCorners(
            rect, 200, 100,
            TransitionPose(rotationZDeg = 90f)
        )
        val expected = listOf(
            150f to -50f,
            150f to 150f,
            50f to 150f,
            50f to -50f
        )
        quad.forEachIndexed { i, point ->
            assertTrue(
                "corner $i was (${point.x}, ${point.y})",
                abs(point.x - expected[i].first) < 0.01f &&
                    abs(point.y - expected[i].second) < 0.01f
            )
        }
    }

    @Test
    fun `a non finite rotation reads as no rotation`() {
        val rect = LetterboxRect(0f, 0f, 100f, 100f)
        val quad = CanvasLetterbox.posedCorners(rect, 100, 100, TransitionPose(rotationZDeg = Float.NaN))
        assertEquals(0f, quad[0].x, 0.01f)
        assertEquals(0f, quad[0].y, 0.01f)
    }

    // --------------------------------------------------------- coversCanvas

    @Test
    fun `a letterboxed rectangle does not cover the canvas`() {
        val rect = CanvasLetterbox.videoRect(1080, 1920, 1920, 1080, 0f, FillMode.FIT)!!
        val quad = CanvasLetterbox.posedCorners(rect, 1080, 1920, TransitionPose())
        assertFalse(CanvasLetterbox.coversCanvas(quad, 1080, 1920))
    }

    @Test
    fun `a full canvas rectangle covers the canvas`() {
        val rect = LetterboxRect(0f, 0f, 1000f, 1000f)
        val quad = CanvasLetterbox.posedCorners(rect, 1000, 1000, TransitionPose())
        assertTrue(CanvasLetterbox.coversCanvas(quad, 1000, 1000))
    }

    @Test
    fun `a shrinking pose uncovers a canvas filling clip`() {
        // The reason `videoRect` no longer reports null for a covering rect:
        // this clip has no bars at rest and a great many a moment later.
        val rect = LetterboxRect(0f, 0f, 1000f, 1000f)
        val quad = CanvasLetterbox.posedCorners(
            rect, 1000, 1000,
            TransitionPose(scaleX = 0.45f, scaleY = 0.45f)
        )
        assertFalse(CanvasLetterbox.coversCanvas(quad, 1000, 1000))
    }

    @Test
    fun `a growing pose still covers the canvas`() {
        val rect = LetterboxRect(0f, 0f, 1000f, 1000f)
        val quad = CanvasLetterbox.posedCorners(
            rect, 1000, 1000,
            TransitionPose(scaleX = 4f, scaleY = 4f)
        )
        assertTrue(CanvasLetterbox.coversCanvas(quad, 1000, 1000))
    }

    @Test
    fun `a collapsed silhouette does not count as covering`() {
        // A zero scale means the video has vanished, so the plate must paint
        // everything — reporting "covered" would skip the pass and leave black.
        val rect = LetterboxRect(0f, 0f, 1000f, 1000f)
        val quad = CanvasLetterbox.posedCorners(
            rect, 1000, 1000,
            TransitionPose(scaleX = 0f, scaleY = 0f)
        )
        assertFalse(CanvasLetterbox.coversCanvas(quad, 1000, 1000))
    }

    @Test
    fun `a degenerate canvas counts as covered`() {
        // Nothing to paint on a zero-sized frame; the caller must skip.
        val rect = LetterboxRect(0f, 0f, 100f, 100f)
        val quad = CanvasLetterbox.posedCorners(rect, 100, 100, TransitionPose())
        assertTrue(CanvasLetterbox.coversCanvas(quad, 0, 0))
    }

    // ----------------------------------------------------------- needsPlate

    @Test
    fun `bars at rest need a plate`() {
        assertTrue(CanvasLetterbox.needsPlate(1080, 1920, 1920, 1080, 0f, FillMode.FIT))
    }

    @Test
    fun `a covering clip at rest needs no plate`() {
        assertFalse(CanvasLetterbox.needsPlate(1000, 1000, 1000, 1000, 0f, FillMode.FIT))
    }

    @Test
    fun `a covering clip that shrinks does need a plate`() {
        assertTrue(
            CanvasLetterbox.needsPlate(
                1000, 1000, 1000, 1000, 0f, FillMode.FIT,
                pose = TransitionPose(scaleX = 0.5f, scaleY = 0.5f)
            )
        )
    }

    @Test
    fun `a FILL clip at rest needs no plate`() {
        assertFalse(CanvasLetterbox.needsPlate(1080, 1920, 1920, 1080, 0f, FillMode.FILL))
    }

    @Test
    fun `a FILL clip that shrinks does need a plate`() {
        assertTrue(
            CanvasLetterbox.needsPlate(
                1080, 1920, 1920, 1080, 0f, FillMode.FILL,
                pose = TransitionPose(scaleX = 0.6f, scaleY = 0.6f)
            )
        )
    }

    @Test
    fun `needsPlate and pixels agree`() {
        val cases = listOf(
            Triple(1080, 1920, FillMode.FIT),
            Triple(1000, 1000, FillMode.FIT),
            Triple(1080, 1920, FillMode.FILL)
        )
        val poses = listOf(
            TransitionPose(),
            TransitionPose(scaleX = 0.5f, scaleY = 0.5f),
            TransitionPose(translationXDp = TransitionGeometry.REFERENCE_WIDTH_DP),
            TransitionPose(rotationZDeg = 33f)
        )
        for ((w, h, mode) in cases) {
            for (pose in poses) {
                val needed = CanvasLetterbox.needsPlate(w, h, 1920, 1080, 0f, mode, pose)
                val built = CanvasLetterbox.pixels(w, h, 1920, 1080, 0f, mode, fill, pose)
                assertEquals("needsPlate=$needed for $w x $h $mode $pose", needed, built != null)
            }
        }
    }

    // --------------------------------------------------------------- pixels

    @Test
    fun `a covering clip produces no plate`() {
        assertNull(CanvasLetterbox.pixels(1000, 1000, 1000, 1000, 0f, FillMode.FIT, fill))
    }

    @Test
    fun `a degenerate canvas produces no plate`() {
        assertNull(CanvasLetterbox.pixels(0, 0, 1000, 1000, 0f, FillMode.FIT, fill))
    }

    @Test
    fun `the plate is the size of the canvas`() {
        val pixels = CanvasLetterbox.pixels(1080, 1920, 1920, 1080, 0f, FillMode.FIT, fill)
        assertNotNull(pixels)
        assertEquals(1080 * 1920, pixels!!.size)
    }

    @Test
    fun `the bars carry the colour and the video area is transparent`() {
        val pixels = CanvasLetterbox.pixels(1080, 1920, 1920, 1080, 0f, FillMode.FIT, fill)!!
        // Top bar.
        assertEquals(0xFF shl 24 or fillRgb, pixels[10 * 1080 + 540])
        // Middle of the video.
        assertEquals(0, pixels[960 * 1080 + 540])
        // Bottom bar.
        assertEquals(0xFF shl 24 or fillRgb, pixels[1900 * 1080 + 540])
    }

    @Test
    fun `the colour is painted fully opaque whatever alpha it carries`() {
        // The panel's swatches are opaque and a letterbox is a fill, not a
        // tint: honouring a partial alpha would blend against Media3's
        // transparent bars and come out darker than the preview.
        val pixels = CanvasLetterbox.pixels(10, 10, 8, 10, 0f, FillMode.FIT, 0x003366CCL)!!
        assertEquals(0xFF shl 24 or fillRgb, pixels[0])
    }

    @Test
    fun `the mask is binary`() {
        // No anti-aliasing by design — see the rasteriser's KDoc. A partial
        // alpha here would wash colour across an edge the video draws hard.
        val pixels = CanvasLetterbox.pixels(1080, 1920, 1920, 1080, 0f, FillMode.FIT, fill)!!
        val opaque = 0xFF shl 24 or fillRgb
        assertTrue(pixels.all { it == 0 || it == opaque })
    }

    @Test
    fun `the bar boundary uses the pixel centre rule`() {
        // The rectangle spans y 656.25..1263.75, so the pixel centres inside it
        // are 656.5..1263.5 — rows 656..1263. Matching OpenGL's rasterisation
        // rule is what keeps the plate's edge on the same pixels as the video's.
        val pixels = CanvasLetterbox.pixels(1080, 1920, 1920, 1080, 0f, FillMode.FIT, fill)!!
        assertEquals(0xFF shl 24 or fillRgb, pixels[655 * 1080 + 540])
        assertEquals(0, pixels[656 * 1080 + 540])
        assertEquals(0, pixels[1263 * 1080 + 540])
        assertEquals(0xFF shl 24 or fillRgb, pixels[1264 * 1080 + 540])
    }

    @Test
    fun `a fractional side bar is rounded to the nearest pixel centre`() {
        // Canvas 10x10, source 8x10: the video spans x 1..9, so the pixel
        // centres inside are 1.5..8.5 — columns 1..8.
        val pixels = CanvasLetterbox.pixels(10, 10, 8, 10, 0f, FillMode.FIT, fill)!!
        assertEquals(0xFF shl 24 or fillRgb, pixels[5 * 10 + 0])
        assertEquals(0, pixels[5 * 10 + 1])
        assertEquals(0, pixels[5 * 10 + 8])
        assertEquals(0xFF shl 24 or fillRgb, pixels[5 * 10 + 9])
    }

    @Test
    fun `a shrinking pose punches a smaller hole`() {
        val pixels = CanvasLetterbox.pixels(
            10, 10, 10, 10, 0f, FillMode.FIT, fill,
            pose = TransitionPose(scaleX = 0.5f, scaleY = 0.5f)
        )!!
        val opaque = 0xFF shl 24 or fillRgb
        // The silhouette is the central half of the canvas — x 2.5..7.5 and
        // y 2.5..7.5 — so its pixel centres are 2.5..6.5 on both axes: rows
        // 2..6 and columns 2..6 are the video, everything else is bar.
        //
        // This is also the case that would be *black* without a pose-aware
        // plate: at rest the clip covers the canvas, so there is no fitted
        // rectangle to derive a hole from at all.
        assertEquals(opaque, pixels[0 * 10 + 0])
        assertEquals(opaque, pixels[1 * 10 + 5])
        assertEquals(0, pixels[2 * 10 + 2])
        assertEquals(0, pixels[2 * 10 + 6])
        assertEquals(0, pixels[6 * 10 + 6])
        assertEquals(opaque, pixels[5 * 10 + 1])
        assertEquals(opaque, pixels[8 * 10 + 8])
        assertEquals(opaque, pixels[9 * 10 + 9])
    }

    @Test
    fun `a pixel centre exactly on the far edge counts as outside`() {
        // The "top-left" fill rule OpenGL uses, applied on both axes: a centre
        // landing exactly on the silhouette's right or bottom edge is not
        // covered, while one landing on the top or left edge is. Both axes have
        // to agree or the plate would sit a pixel off on one of them.
        val pixels = CanvasLetterbox.pixels(
            10, 10, 10, 10, 0f, FillMode.FIT, fill,
            pose = TransitionPose(scaleX = 0.5f, scaleY = 0.5f)
        )!!
        val opaque = 0xFF shl 24 or fillRgb
        // Top edge (2.5) inclusive, bottom edge (7.5) exclusive.
        assertEquals(0, pixels[2 * 10 + 5])
        assertEquals(opaque, pixels[7 * 10 + 5])
        // Left edge (2.5) inclusive, right edge (7.5) exclusive.
        assertEquals(0, pixels[5 * 10 + 2])
        assertEquals(opaque, pixels[5 * 10 + 7])
    }

    @Test
    fun `a pose that pushes the video off the canvas paints the whole frame`() {
        // A full-frame push leaves nothing of the clip inside the canvas, so
        // every pixel is bar. The plate must cover it rather than skip.
        val pixels = CanvasLetterbox.pixels(
            10, 10, 10, 10, 0f, FillMode.FIT, fill,
            pose = TransitionPose(translationXDp = TransitionGeometry.REFERENCE_WIDTH_DP)
        )!!
        val opaque = 0xFF shl 24 or fillRgb
        assertTrue(pixels.all { it == opaque })
    }

    @Test
    fun `a rotated silhouette is punched out as a quadrilateral`() {
        // Canvas 200x100, source matching, rotated 45deg: the silhouette's
        // bounding box is larger than the canvas, so the corners are covered
        // while the centre is video.
        val pixels = CanvasLetterbox.pixels(
            200, 100, 200, 100, 0f, FillMode.FIT, fill,
            pose = TransitionPose(rotationZDeg = 45f)
        )!!
        val opaque = 0xFF shl 24 or fillRgb
        assertEquals(0, pixels[50 * 200 + 100])
        assertEquals(opaque, pixels[0])
        assertEquals(opaque, pixels[99 * 200 + 199])
    }

    @Test
    fun `FILL with a pose produces a plate where the cover no longer holds`() {
        assertNull(CanvasLetterbox.pixels(100, 100, 400, 100, 0f, FillMode.FILL, fill))
        assertNotNull(
            CanvasLetterbox.pixels(
                100, 100, 400, 100, 0f, FillMode.FILL, fill,
                pose = TransitionPose(scaleX = 0.5f, scaleY = 0.5f)
            )
        )
    }

    @Test
    fun `a rotation that fills the canvas produces no plate`() {
        // A 1920x1080 source rotated 90deg exactly matches a 1080x1920 canvas.
        assertNull(CanvasLetterbox.pixels(1080, 1920, 1920, 1080, 90f, FillMode.FIT, fill))
    }

    @Test
    fun `the plate never paints over the middle of the video`() {
        // The single most important property of the pass: the hole must track
        // the video, or a zoom would get a coloured border burnt into it.
        val poses = listOf(
            TransitionPose(),
            TransitionPose(scaleX = 0.6f, scaleY = 0.6f),
            TransitionPose(scaleX = 1.4f, scaleY = 1.4f),
            TransitionPose(translationXDp = 120f, translationYDp = 60f),
            TransitionPose(rotationZDeg = 12f),
            TransitionPose(scaleX = 1.2f, scaleY = 1.2f, rotationZDeg = -30f)
        )
        for (pose in poses) {
            val pixels = CanvasLetterbox.pixels(
                200, 200, 200, 200, 0f, FillMode.FIT, fill, pose = pose
            ) ?: continue
            // The canvas centre is always inside the silhouette: every pose is
            // a transform about the canvas centre, so the video's centre stays
            // put.
            assertEquals("centre painted for $pose", 0, pixels[100 * 200 + 100])
        }
    }
}
