package com.aicreatorstudio.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TransitionGeometry] — the bridge that lets the Compose
 * preview and the Media3 export agree on what a transition looks like.
 *
 * These matter because the two renderers use completely different
 * coordinate spaces (dp vs normalized device coordinates) and cannot be
 * compared visually in a unit test. Everything shared between them is
 * pure Kotlin here, so it can be checked without a device or a GL context.
 */
class TransitionGeometryTest {

    private fun assertClose(expected: Float, actual: Float, delta: Float = 1e-4f) {
        assertEquals(expected.toDouble(), actual.toDouble(), delta.toDouble())
    }

    /** Column-major 4x4 accessor: `m[col * 4 + row]`. */
    private fun FloatArray.at(row: Int, col: Int): Float = this[col * 4 + row]

    // ---- translation fractions ----

    @Test
    fun `one reference width of travel is one full frame width`() {
        val pose = TransitionPose(translationXDp = TransitionGeometry.REFERENCE_WIDTH_DP)

        assertClose(1f, TransitionGeometry.translationFractionX(pose))
    }

    @Test
    fun `a push left travels exactly one frame width`() {
        // PUSH_LEFT is authored as `-430f * a`, i.e. one full frame width
        // at full intensity — enough to be completely off-screen.
        val pose = TransitionPose(translationXDp = -430f)

        assertClose(-1f, TransitionGeometry.translationFractionX(pose))
    }

    @Test
    fun `a push up is negative in the screen convention`() {
        val pose = TransitionPose(translationYDp = -280f)

        assertClose(-1f, TransitionGeometry.translationFractionY(pose))
    }

    @Test
    fun `an identity pose has no displacement`() {
        assertClose(0f, TransitionGeometry.translationFractionX(TransitionPose()))
        assertClose(0f, TransitionGeometry.translationFractionY(TransitionPose()))
    }

    // ---- matrix: identity and scale ----

    @Test
    fun `an identity pose yields the identity matrix`() {
        val m = TransitionGeometry.matrix(TransitionPose(), aspectRatio = 1f)

        assertEquals(16, m.size)
        assertClose(1f, m.at(0, 0))
        assertClose(1f, m.at(1, 1))
        assertClose(1f, m.at(2, 2))
        assertClose(1f, m.at(3, 3))
        // Every off-diagonal entry must be zero.
        for (row in 0..3) {
            for (col in 0..3) {
                if (row != col) assertClose(0f, m.at(row, col))
            }
        }
    }

    @Test
    fun `a uniform pose scale scales both axes equally`() {
        val m = TransitionGeometry.matrix(
            TransitionPose(scaleX = 1.5f, scaleY = 1.5f),
            aspectRatio = 1f
        )

        assertClose(1.5f, m.at(0, 0))
        assertClose(1.5f, m.at(1, 1))
        // z must stay untouched so the frame is not clipped away.
        assertClose(1f, m.at(2, 2))
    }

    // ---- matrix: translation, and the NDC sign flip ----

    @Test
    fun `one frame width of travel becomes two NDC units`() {
        // NDC spans -1..1 across the frame, so a full frame width is 2
        // units. This is the whole reason the fraction is doubled.
        val m = TransitionGeometry.matrix(
            TransitionPose(translationXDp = TransitionGeometry.REFERENCE_WIDTH_DP),
            aspectRatio = 1f
        )

        assertClose(2f, m.at(0, 3))
    }

    @Test
    fun `a push up becomes a positive NDC y translation`() {
        // The pose says "up" as a negative dp Y (screen convention, y-down).
        // Media3's NDC is y-up, so the sign must flip — otherwise an
        // exported push would travel the opposite way from the preview.
        val m = TransitionGeometry.matrix(
            TransitionPose(translationYDp = -TransitionGeometry.REFERENCE_HEIGHT_DP),
            aspectRatio = 1f
        )

        assertClose(2f, m.at(1, 3))
    }

    @Test
    fun `aspect ratio does not distort a pure translation`() {
        val wide = TransitionGeometry.matrix(
            TransitionPose(translationXDp = 215f),
            aspectRatio = 16f / 9f
        )
        val square = TransitionGeometry.matrix(
            TransitionPose(translationXDp = 215f),
            aspectRatio = 1f
        )

        assertClose(wide.at(0, 3), square.at(0, 3))
        assertClose(1f, wide.at(0, 3))
    }

    // ---- matrix: rotation ----

    @Test
    fun `a quarter turn clockwise matches the screen convention`() {
        // The pose's rotationZ is positive-clockwise (Compose). In a Y-up
        // NDC space that is a negative mathematical angle, so the matrix
        // must come out as R(-90), not R(+90).
        val m = TransitionGeometry.matrix(TransitionPose(rotationZDeg = 90f), aspectRatio = 1f)

        assertClose(0f, m.at(0, 0))
        assertClose(-1f, m.at(1, 0))
        assertClose(1f, m.at(0, 1))
        assertClose(0f, m.at(1, 1))
    }

    @Test
    fun `rotation is aspect-corrected so a wide frame does not shear`() {
        val aspect = 16f / 9f
        val m = TransitionGeometry.matrix(TransitionPose(rotationZDeg = 90f), aspectRatio = aspect)

        // The x column carries the aspect factor and the y column its
        // reciprocal, which is what keeps a rotated rectangle rectangular.
        assertClose(-aspect, m.at(1, 0))
        assertClose(1f / aspect, m.at(0, 1))
    }

    // ---- matrix: degenerate input ----

    @Test
    fun `a bad aspect ratio cannot produce a NaN matrix`() {
        // `configure` reads the frame size, which is 0 before the first
        // frame is configured. A NaN matrix would silently blank the whole
        // export, so this is a real failure mode rather than a nicety.
        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val m = TransitionGeometry.matrix(TransitionPose(scaleX = 1.2f), aspectRatio = bad)
            for (value in m) {
                assertFalse("matrix contained $value for aspect=$bad", value.isNaN())
                assertFalse("matrix contained $value for aspect=$bad", value.isInfinite())
            }
            assertClose(1.2f, m.at(0, 0))
        }
    }

    // ---- frame alpha -> fade wash ----

    @Test
    fun `a reduced frame alpha becomes an equally opaque fade wash`() {
        // The preview fades the clip toward the canvas behind it; the
        // export has no alpha channel, so 30% opaque becomes a 70% wash
        // painted in the canvas colour.
        assertClose(0.7f, TransitionGeometry.fadeAlpha(TransitionPose(alpha = 0.3f)))
    }

    @Test
    fun `an opaque frame needs no fade wash`() {
        assertClose(0f, TransitionGeometry.fadeAlpha(TransitionPose(alpha = 1f)))
    }

    @Test
    fun `the fade wash ignores an explicit black flash`() {
        // They are separate passes with different colours, so neither
        // opacity may leak into the other.
        assertClose(0f, TransitionGeometry.fadeAlpha(TransitionPose(flashBlack = 0.8f)))
    }

    @Test
    fun `a black flash is passed through unchanged`() {
        assertClose(0.8f, TransitionGeometry.flashBlackAlpha(TransitionPose(flashBlack = 0.8f)))
        assertClose(0f, TransitionGeometry.flashBlackAlpha(TransitionPose(alpha = 0.5f)))
    }

    @Test
    fun `the fade and the black flash compose rather than compete`() {
        // This is the 2026-09-16 behaviour change. The two used to be folded
        // into one pass with `max`, which under-darkens GLITCH — the one
        // built-in that sets both (`alpha = 1 - 0.08a`, `flashBlack = 0.12a`).
        // The preview composites the clip at its own alpha and *then* draws
        // the black box on top, so at full intensity the residual brightness
        // is 0.92 * 0.88.
        val pose = TransitionPose(alpha = 0.92f, flashBlack = 0.12f)
        assertClose(0.08f, TransitionGeometry.fadeAlpha(pose))
        assertClose(0.12f, TransitionGeometry.flashBlackAlpha(pose))

        val residual = (1f - TransitionGeometry.fadeAlpha(pose)) *
            (1f - TransitionGeometry.flashBlackAlpha(pose))
        assertClose(0.8096f, residual)
        // The old single-pass `max` would have left 0.88 — brighter than the
        // preview, which is what the split fixes.
        assertTrue("the split must darken more than the old fold", residual < 0.88f)
    }

    @Test
    fun `white flash is passed through unchanged`() {
        assertClose(0.55f, TransitionGeometry.whiteoutAlpha(TransitionPose(flashWhite = 0.55f)))
        assertClose(0f, TransitionGeometry.whiteoutAlpha(TransitionPose()))
    }

    // ---- blur sigma ----

    @Test
    fun `a pose with no blur yields no sigma`() {
        assertClose(0f, TransitionGeometry.blurSigmaPx(TransitionPose(blurDp = 0f), 1080))
    }

    @Test
    fun `an unknown output height yields no sigma`() {
        assertClose(0f, TransitionGeometry.blurSigmaPx(TransitionPose(blurDp = 16f), 0))
    }

    @Test
    fun `blur is scaled from the reference canvas to the real output`() {
        // blurDp is authored against the 430x280 reference canvas.
        assertClose(16f, TransitionGeometry.blurSigmaPx(TransitionPose(blurDp = 16f), 280))
        assertClose(32f, TransitionGeometry.blurSigmaPx(TransitionPose(blurDp = 16f), 560))
    }

    @Test
    fun `a vanishing blur is clamped above zero`() {
        // GaussianFunction rejects a non-positive sigma outright, and a
        // transition's blur legitimately decays to 0 at the end of its
        // window — so the clamp is load-bearing, not cosmetic.
        val sigma = TransitionGeometry.blurSigmaPx(TransitionPose(blurDp = 0.01f), 1080)

        assertTrue("sigma must stay positive, was $sigma", sigma > 0f)
        assertClose(0.1f, sigma)
    }

    // ---- overlay necessity probes ----

    @Test
    fun `a white flash type needs a white overlay`() {
        assertTrue(
            TransitionGeometry.needsWhiteOverlay(
                TransitionConfig(TransitionType.FLASH_WHITE),
                TransitionDir.IN
            )
        )
    }

    @Test
    fun `a black flash type needs a black flash overlay`() {
        assertTrue(
            TransitionGeometry.needsBlackFlashOverlay(
                TransitionConfig(TransitionType.FLASH_BLACK),
                TransitionDir.IN
            )
        )
    }

    @Test
    fun `the fade family needs a fade wash because alpha cannot be encoded`() {
        assertTrue(
            TransitionGeometry.needsFadeOverlay(
                TransitionConfig(TransitionType.FADE_IN),
                TransitionDir.IN
            )
        )
    }

    @Test
    fun `the fade probe and the black flash probe are independent`() {
        // FADE_IN dims but never flashes black; FLASH_BLACK flashes but never
        // dims. Merging the two probes — which the export did until
        // 2026-09-16 — is exactly what made a fade wash toward black on a
        // coloured canvas, and what hid the black flash inside a single pass.
        val fade = TransitionConfig(TransitionType.FADE_IN)
        assertTrue(TransitionGeometry.needsFadeOverlay(fade, TransitionDir.IN))
        assertFalse(TransitionGeometry.needsBlackFlashOverlay(fade, TransitionDir.IN))

        val flash = TransitionConfig(TransitionType.FLASH_BLACK)
        assertTrue(TransitionGeometry.needsBlackFlashOverlay(flash, TransitionDir.IN))
        assertFalse(TransitionGeometry.needsFadeOverlay(flash, TransitionDir.IN))
    }

    @Test
    fun `a transition that sets both needs both washes`() {
        // GLITCH is the only built-in that does: `alpha = 1 - 0.08a` *and*
        // `flashBlack = 0.12a`. This is the case the old folded probe could
        // not represent.
        val glitch = TransitionConfig(TransitionType.GLITCH)

        assertTrue(TransitionGeometry.needsFadeOverlay(glitch, TransitionDir.IN))
        assertTrue(TransitionGeometry.needsBlackFlashOverlay(glitch, TransitionDir.IN))
    }

    @Test
    fun `a purely geometric transition needs no overlay at all`() {
        val zoom = TransitionConfig(TransitionType.ZOOM_IN)

        assertFalse(TransitionGeometry.needsWhiteOverlay(zoom, TransitionDir.IN))
        assertFalse(TransitionGeometry.needsFadeOverlay(zoom, TransitionDir.IN))
        assertFalse(TransitionGeometry.needsBlackFlashOverlay(zoom, TransitionDir.IN))
    }

    @Test
    fun `a blur transition is detected as needing the blur pass`() {
        assertTrue(
            TransitionGeometry.needsBlur(TransitionConfig(TransitionType.MOTION_BLUR), TransitionDir.IN)
        )
        assertFalse(
            TransitionGeometry.needsBlur(TransitionConfig(TransitionType.ZOOM_IN), TransitionDir.IN)
        )
    }

    @Test
    fun `the probes cover every built-in transition without throwing`() {
        // Guards the sampling loop against a transition type whose pose
        // returns something degenerate. Not asserting which ones fire —
        // only that evaluating the whole library is safe.
        for (type in TransitionType.entries) {
            for (dir in TransitionDir.entries) {
                TransitionGeometry.needsWhiteOverlay(TransitionConfig(type), dir)
                TransitionGeometry.needsFadeOverlay(TransitionConfig(type), dir)
                TransitionGeometry.needsBlackFlashOverlay(TransitionConfig(type), dir)
                TransitionGeometry.needsBlur(TransitionConfig(type), dir)
            }
        }
    }
}
