package com.aicreatorstudio.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tests for [VignettePlate] — the pure definition behind the Color Grading
 * panel's "Detail > Vignette" sliders.
 *
 * The plate is the whole vignette as a pixel buffer, so these tests cover the
 * full behaviour of the effect. Two properties are load-bearing and are
 * asserted directly rather than implied: the plate must **always cover the
 * frame** once scaled (an under-covering plate leaves an un-vignetted border,
 * most visible at the corners where the vignette is strongest), and the
 * strength must rise **monotonically** from the centre outward.
 */
class VignettePlateTest {

    // ---------------------------------------------------------------- active

    @Test
    fun `zero amount is not active`() {
        assertFalse(VignettePlate.isActive(0f))
    }

    @Test
    fun `a non zero amount is active in both directions`() {
        assertTrue(VignettePlate.isActive(0.5f))
        assertTrue(VignettePlate.isActive(1f))
        // Negative means "brighten the edges", which is just as much a change.
        assertTrue(VignettePlate.isActive(-0.5f))
        assertTrue(VignettePlate.isActive(-1f))
    }

    @Test
    fun `a sliver above zero is still active`() {
        assertTrue(VignettePlate.isActive(0.001f))
    }

    @Test
    fun `non finite amount is not active`() {
        assertFalse(VignettePlate.isActive(Float.NaN))
        assertFalse(VignettePlate.isActive(Float.POSITIVE_INFINITY))
        assertFalse(VignettePlate.isActive(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun `feather and roundness alone never activate the vignette`() {
        // Both only shape a falloff that has no strength to shape, which is
        // why ColorGrading.isIdentity excludes them from its check too.
        assertFalse(VignettePlate.isActive(0f))
        val g = ColorGrading(vignetteAmount = 0f, vignetteFeather = 1f, vignetteRoundness = 0f)
        assertFalse(VignettePlate.isActive(g.vignetteAmount))
    }

    // ------------------------------------------------------------ plate size

    @Test
    fun `plate size is the frame divided by the divisor`() {
        assertEquals(270 to 480, VignettePlate.plateSize(1080, 1920))
        assertEquals(960 to 540, VignettePlate.plateSize(3840, 2160))
    }

    @Test
    fun `plate size is at least two pixels`() {
        val (w, h) = VignettePlate.plateSize(4, 4)
        assertTrue("plate must stay renderable, got ${w}x$h", w >= 2 && h >= 2)
    }

    @Test
    fun `plate size collapses unknown frame dimensions to one by one`() {
        assertEquals(1 to 1, VignettePlate.plateSize(0, 0))
        assertEquals(1 to 1, VignettePlate.plateSize(-100, 200))
        assertEquals(1 to 1, VignettePlate.plateSize(200, -100))
    }

    @Test
    fun `plate size roughly preserves the frame aspect ratio`() {
        for ((fw, fh) in listOf(1080 to 1920, 1920 to 1080, 1080 to 1080, 3840 to 2160)) {
            val (pw, ph) = VignettePlate.plateSize(fw, fh)
            val frameAspect = fw.toFloat() / fh
            val plateAspect = pw.toFloat() / ph
            assertTrue(
                "aspect drifted for ${fw}x$fh: frame $frameAspect vs plate $plateAspect",
                abs(frameAspect - plateAspect) / frameAspect < 0.05f
            )
        }
    }

    // ---------------------------------------------------------- overlay scale

    @Test
    fun `the scaled plate always covers the frame`() {
        // The dangerous direction. Media3 leaves a pixel untouched when the
        // overlay's texture coordinate falls outside [0,1], so a plate that
        // comes up short leaves an un-vignetted border.
        for ((fw, fh) in listOf(1080 to 1920, 1920 to 1080, 1080 to 1080, 3840 to 2160, 720 to 1280)) {
            val (pw, ph) = VignettePlate.plateSize(fw, fh)
            val (sx, sy) = VignettePlate.overlayScale(fw, fh)
            assertTrue(
                "plate ${pw}x$ph scaled by $sx does not reach width $fw",
                pw * sx >= fw.toFloat()
            )
            assertTrue(
                "plate ${pw}x$ph scaled by $sy does not reach height $fh",
                ph * sy >= fh.toFloat()
            )
        }
    }

    @Test
    fun `the scaled plate does not overshoot wildly`() {
        for ((fw, fh) in listOf(1080 to 1920, 1920 to 1080, 3840 to 2160)) {
            val (pw, ph) = VignettePlate.plateSize(fw, fh)
            val (sx, sy) = VignettePlate.overlayScale(fw, fh)
            assertTrue("overshoot too large on x", pw * sx <= fw.toFloat() * 1.05f)
            assertTrue("overshoot too large on y", ph * sy <= fh.toFloat() * 1.05f)
        }
    }

    @Test
    fun `overlay scale never collapses to zero for an unknown frame`() {
        // Degenerate input must stay a harmless no-op, not a divide by zero.
        val (sx, sy) = VignettePlate.overlayScale(0, 0)
        assertFalse(sx.isNaN() || sy.isNaN())
    }

    // --------------------------------------------------------------- falloff

    @Test
    fun `the centre is untouched`() {
        assertEquals(0f, VignettePlate.falloff(0.5f, 0.5f, 0.5f, 0.5f), 1e-6f)
    }

    @Test
    fun `the corners reach full strength at low feather`() {
        for ((nx, ny) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)) {
            assertEquals(
                "corner $nx,$ny",
                1f,
                VignettePlate.falloff(nx, ny, 0f, 0.5f),
                1e-6f
            )
        }
    }

    @Test
    fun `falloff rises monotonically from the centre outward`() {
        var previous = -1f
        var t = 0f
        while (t <= 1f) {
            val v = VignettePlate.falloff(0.5f + 0.5f * t, 0.5f, 0.5f, 0.5f)
            assertTrue("falloff dropped at t=$t ($previous -> $v)", v >= previous - 1e-6f)
            previous = v
            t += 0.02f
        }
    }

    @Test
    fun `falloff is symmetric in both axes`() {
        val a = VignettePlate.falloff(0.8f, 0.5f, 0.5f, 0.5f)
        val b = VignettePlate.falloff(0.2f, 0.5f, 0.5f, 0.5f)
        val c = VignettePlate.falloff(0.5f, 0.8f, 0.5f, 0.5f)
        val d = VignettePlate.falloff(0.5f, 0.2f, 0.5f, 0.5f)
        assertEquals(a, b, 1e-6f)
        assertEquals(c, d, 1e-6f)
        assertEquals(a, c, 1e-6f)
    }

    @Test
    fun `roundness changes the contour shape`() {
        // At roundness 0.5 both axes carry the same bias, so the contour is
        // circular; away from 0.5 one axis is compressed and the two differ.
        val midX = VignettePlate.falloff(0.85f, 0.5f, 0.5f, 0.5f)
        val midY = VignettePlate.falloff(0.5f, 0.85f, 0.5f, 0.5f)
        assertEquals("roundness 0.5 must be circular", midX, midY, 1e-6f)

        val round = VignettePlate.falloff(0.9f, 0.6f, 0.5f, 1f)
        val oval = VignettePlate.falloff(0.9f, 0.6f, 0.5f, 0f)
        assertTrue("roundness must change the contour", abs(round - oval) > 1e-4f)
    }

    @Test
    fun `more feather softens the transition`() {
        // Feather widens the ramp while narrowing the clear core, so a fixed
        // sample point is not comparable across feathers — at low feather it
        // may sit inside the core and read exactly 0. The measure of "softer"
        // is the steepest step across the whole diagonal, which must fall.
        fun steepestStep(feather: Float): Float {
            var previous = VignettePlate.falloff(0.5f, 0.5f, feather, 0.5f)
            var steepest = 0f
            var t = 0.005f
            while (t <= 1f) {
                val v = VignettePlate.falloff(0.5f + 0.5f * t, 0.5f + 0.5f * t, feather, 0.5f)
                val step = abs(v - previous)
                if (step > steepest) steepest = step
                previous = v
                t += 0.005f
            }
            return steepest
        }
        val sharp = steepestStep(0f)
        val medium = steepestStep(0.5f)
        val soft = steepestStep(1f)
        assertTrue("feather 0.5 ($medium) must be gentler than 0 ($sharp)", medium < sharp)
        assertTrue("feather 1 ($soft) must be gentler than 0.5 ($medium)", soft < medium)
    }

    @Test
    fun `falloff stays within zero and one across a grid`() {
        for (feather in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            for (roundness in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                var y = 0f
                while (y <= 1f) {
                    var x = 0f
                    while (x <= 1f) {
                        val v = VignettePlate.falloff(x, y, feather, roundness)
                        assertTrue("falloff $v out of range at $x,$y", v in 0f..1f)
                        x += 0.1f
                    }
                    y += 0.1f
                }
            }
        }
    }

    @Test
    fun `non finite shape inputs fall back to neutral instead of poisoning the plate`() {
        val nan = VignettePlate.falloff(0.9f, 0.5f, Float.NaN, Float.NaN)
        val neutral = VignettePlate.falloff(0.9f, 0.5f, 0.5f, 0.5f)
        assertFalse("NaN leaked into the falloff", nan.isNaN())
        assertEquals(neutral, nan, 1e-6f)
    }

    @Test
    fun `shape inputs are clamped to the slider range`() {
        assertEquals(
            VignettePlate.falloff(0.9f, 0.5f, 1f, 1f),
            VignettePlate.falloff(0.9f, 0.5f, 5f, 5f),
            1e-6f
        )
        assertEquals(
            VignettePlate.falloff(0.9f, 0.5f, 0f, 0f),
            VignettePlate.falloff(0.9f, 0.5f, -3f, -3f),
            1e-6f
        )
    }

    // --------------------------------------------------------------- alphaAt

    @Test
    fun `alpha is zero at rest and zero at the centre`() {
        assertEquals(0f, VignettePlate.alphaAt(0.9f, 0.9f, 0f, 0.5f, 0.5f), 0f)
        assertEquals(0f, VignettePlate.alphaAt(0.5f, 0.5f, 1f, 0.5f, 0.5f), 0f)
    }

    @Test
    fun `alpha depends on magnitude not sign`() {
        val dark = VignettePlate.alphaAt(1f, 1f, 0.7f, 0.5f, 0.5f)
        val light = VignettePlate.alphaAt(1f, 1f, -0.7f, 0.5f, 0.5f)
        assertEquals(dark, light, 0f)
    }

    @Test
    fun `alpha never exceeds the requested amount`() {
        for (amount in listOf(0.1f, 0.5f, 1f)) {
            assertEquals(amount, VignettePlate.alphaAt(1f, 1f, amount, 0f, 0.5f), 1e-5f)
        }
    }

    @Test
    fun `non finite amount yields no alpha`() {
        assertEquals(0f, VignettePlate.alphaAt(1f, 1f, Float.NaN, 0.5f, 0.5f), 0f)
    }

    // ---------------------------------------------------------------- pixels

    @Test
    fun `pixels has one entry per plate pixel`() {
        val (pw, ph) = VignettePlate.plateSize(1080, 1920)
        val px = VignettePlate.pixels(1080, 1920, 0.5f, 0.5f, 0.5f)
        assertEquals(pw * ph, px.size)
    }

    @Test
    fun `a darkening amount blends toward black and a brightening one toward white`() {
        val dark = VignettePlate.pixels(400, 400, 0.8f, 0.5f, 0.5f)
        val light = VignettePlate.pixels(400, 400, -0.8f, 0.5f, 0.5f)
        for (p in dark) {
            assertEquals("darkening plate must be black", 0x000000, p and 0x00FFFFFF)
        }
        for (p in light) {
            assertEquals("brightening plate must be white", 0xFFFFFF, p and 0x00FFFFFF)
        }
    }

    @Test
    fun `alpha is packed into the top byte`() {
        val px = VignettePlate.pixels(400, 400, 1f, 0f, 0.5f)
        // The top-left pixel is a corner, so at zero feather it is full strength.
        assertEquals(VignettePlate.ALPHA_MAX, (px[0] ushr 24) and 0xFF)
    }

    @Test
    fun `a zero amount produces a fully transparent plate`() {
        val px = VignettePlate.pixels(400, 400, 0f, 0.5f, 0.5f)
        for ((i, p) in px.withIndex()) {
            assertEquals("pixel $i must be transparent", 0, (p ushr 24) and 0xFF)
        }
    }

    @Test
    fun `the centre pixel is transparent and the corners are the strongest`() {
        val (pw, ph) = VignettePlate.plateSize(400, 400)
        val px = VignettePlate.pixels(400, 400, 1f, 0.5f, 0.5f)
        val centre = (px[(ph / 2) * pw + pw / 2] ushr 24) and 0xFF
        val corner = (px[0] ushr 24) and 0xFF
        assertEquals("centre must be untouched", 0, centre)
        assertEquals("corner must be full strength", VignettePlate.ALPHA_MAX, corner)
    }

    @Test
    fun `alpha rises monotonically along the diagonal`() {
        val (pw, ph) = VignettePlate.plateSize(400, 400)
        val px = VignettePlate.pixels(400, 400, 1f, 0.5f, 0.5f)
        var previous = -1
        var step = 0
        while (step < pw / 2) {
            val x = pw / 2 + step
            val y = ph / 2 + step
            val a = (px[y * pw + x] ushr 24) and 0xFF
            assertTrue("alpha dropped at step $step ($previous -> $a)", a >= previous)
            previous = a
            step += 1
        }
        assertTrue("alpha must actually rise", previous > 0)
    }

    @Test
    fun `a stronger amount darkens harder at the same pixel`() {
        val weak = VignettePlate.pixels(400, 400, 0.3f, 0.5f, 0.5f)[0] ushr 24 and 0xFF
        val strong = VignettePlate.pixels(400, 400, 1f, 0.5f, 0.5f)[0] ushr 24 and 0xFF
        assertTrue("stronger must darken more ($weak vs $strong)", strong > weak)
    }

    @Test
    fun `an out of range amount is clamped rather than overflowing the alpha byte`() {
        for (amount in listOf(5f, -5f, 100f)) {
            val px = VignettePlate.pixels(400, 400, amount, 0f, 0.5f)
            for ((i, p) in px.withIndex()) {
                val a = (p ushr 24) and 0xFF
                assertTrue("pixel $i alpha $a out of range for amount $amount", a in 0..255)
            }
        }
    }

    @Test
    fun `non finite amount produces a transparent plate`() {
        val px = VignettePlate.pixels(400, 400, Float.NaN, 0.5f, 0.5f)
        for ((i, p) in px.withIndex()) {
            assertEquals("pixel $i must be transparent", 0, (p ushr 24) and 0xFF)
        }
    }

    @Test
    fun `an unknown frame size still produces a valid single pixel plate`() {
        val px = VignettePlate.pixels(0, 0, 1f, 0.5f, 0.5f)
        assertEquals(1, px.size)
        assertEquals(0, (px[0] ushr 24) and 0xFF)
    }

    // ------------------------------------------------------------- behaviour

    @Test
    fun `the visible multiplier darkens monotonically outward`() {
        // What the viewer actually sees: the overlay blends
        // `video * (1 - alpha)`, so this is the multiplier on the image.
        //
        // Sampled along the diagonal, not along an axis. At feather 0.5 the
        // clear core reaches d = 0.625 while the frame's x-axis only reaches
        // d = 0.75, so an axis sample would land in the core and read exactly
        // the same as the centre — a comparison that can never discriminate.
        val amount = 0.8f
        val centre = 1f - VignettePlate.alphaAt(0.5f, 0.5f, amount, 0.5f, 0.5f)
        val mid = 1f - VignettePlate.alphaAt(0.9f, 0.9f, amount, 0.5f, 0.5f)
        val corner = 1f - VignettePlate.alphaAt(1f, 1f, amount, 0.5f, 0.5f)
        assertEquals("centre must be untouched", 1f, centre, 1e-6f)
        assertTrue("mid must be darker than centre, got $mid", mid < centre)
        assertTrue("corner must be darker than mid, got $corner", corner < mid)
    }

    @Test
    fun `a negative amount brightens the edges instead of darkening them`() {
        // The blend is `overlay.rgb * a + video.rgb * (1 - a)`, so a white
        // plate with the same alpha lifts the image where a black one would
        // darken it. Only the plate's RGB differs between the two signs.
        val amount = -0.8f
        assertEquals(
            "centre must be untouched",
            0f,
            VignettePlate.alphaAt(0.5f, 0.5f, amount, 0.5f, 0.5f),
            1e-6f
        )
        val cornerAlpha = VignettePlate.alphaAt(1f, 1f, amount, 0.5f, 0.5f)
        assertEquals(
            "a negative amount must reach the same strength as the positive",
            VignettePlate.alphaAt(1f, 1f, 0.8f, 0.5f, 0.5f),
            cornerAlpha,
            1e-6f
        )
        assertTrue("corner must be lifted", cornerAlpha > 0f)
        // And the plate it produces is white, not black.
        val px = VignettePlate.pixels(400, 400, amount, 0.5f, 0.5f)
        assertEquals(0xFFFFFF, px[0] and 0x00FFFFFF)
    }
}
