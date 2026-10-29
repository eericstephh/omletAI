package com.aicreatorstudio.app.export

import com.aicreatorstudio.app.model.ColorGrading
import com.aicreatorstudio.app.model.VideoEffect
import com.aicreatorstudio.app.ui.screens.editor.ColorGradingMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins the 4×5 → 4×4 conversion that feeds Media3's `RgbMatrix`.
 *
 * **Regression guarded (2026-09-19):** `GradeRgbMatrix.getMatrix` used
 * to return the app's raw 4×5 row-major `ColorMatrix` — 20 floats.
 * Media3's `DefaultShaderProgram.updateMatrixCache` asserts
 * `newMatrix.length == 16` ("A 4x4 transformation matrix must have 16
 * elements"), and because a 20-element array can never equal the
 * 16-element cache the assertion fired on the first frame. It is thrown
 * *outside* `drawFrame`'s try block, so
 * `VideoFrameProcessingTaskExecutor.handleException` caught it, set
 * `shouldCancelTasks` and cancelled the whole video pipeline. The
 * brightness / contrast / saturation sliders moved the label and the
 * thumb and changed no pixel — in the preview **and** in the export,
 * because both renderers share this one object.
 *
 * These are **guards, not reproductions**: a JVM unit test cannot load
 * `DefaultShaderProgram` and cannot execute GL, so the `length == 16`
 * invariant can only be asserted against Media3's documented contract,
 * which the first test does. Every fact below was read out of
 * `media3-effect-1.5.0-sources.jar` and the shipped GLSL assets rather
 * than recalled:
 *
 *  - `RgbMatrix.getMatrix` KDoc — "Returns the 4x4 RGB transformation
 *    matrix to apply to the color values of each pixel".
 *  - `DefaultShaderProgram.updateMatrixCache` — the 16-element
 *    `checkState`, plus `new float[rgbMatrices.size()][16]` for the
 *    cache.
 *  - `fragment_shader_transformation_es2.glsl` —
 *    `gl_FragColor = uRgbMatrix * vec4(inputColor.rgb, 1)`, i.e. a
 *    column-major `mat4 * vec4` on colours normalised to 0..1, which is
 *    where the `1.0` constant term and the ÷255 rescale come from.
 */
class GradeRgbMatrixTest {

    @Test
    fun `the identity 4x5 becomes Media3's 16-element 4x4 identity`() {
        val m = toMedia3RgbMatrix4x4(GradeRgbMatrix.IDENTITY_4X5)
        assertEquals(16, m.size)
        assertArrayEquals(
            floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f
            ),
            m,
            1e-6f
        )
    }

    @Test
    fun `an input that is not a 4x5 matrix is rejected loudly`() {
        for (size in intArrayOf(16, 19, 21)) {
            try {
                toMedia3RgbMatrix4x4(FloatArray(size))
                fail("expected an IllegalArgumentException for a $size-element input")
            } catch (expected: IllegalArgumentException) {
                // expected — a wrong shape must fail at the boundary,
                // not silently reach the GL thread.
            }
        }
    }

    @Test
    fun `the colour block is transposed into column-major order`() {
        // R and G swapped. In the source's row-major layout, row 0 is
        // "output R = 1×G", so input G's coefficient for output R sits
        // at index 1; column-major Media3 wants that at index 4.
        val swapped = floatArrayOf(
            0f, 1f, 0f, 0f, 0f,
            1f, 0f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
        val m = toMedia3RgbMatrix4x4(swapped)
        assertEquals("input G -> output R", 1f, m[4], 1e-6f)
        assertEquals("input R -> output G", 1f, m[1], 1e-6f)
        assertEquals("input R -> output R", 0f, m[0], 1e-6f)
        assertEquals("input G -> output G", 0f, m[5], 1e-6f)
        assertEquals("output B is untouched", 1f, m[10], 1e-6f)
    }

    @Test
    fun `the offset column is rescaled from 0-255 to 0-1`() {
        // Brightness +64 on every channel — the shape the preview's
        // B/C/S base matrix produces (`translateRGB(g.brightness * 64f)`).
        val brightness = floatArrayOf(
            1f, 0f, 0f, 0f, 64f,
            0f, 1f, 0f, 0f, 64f,
            0f, 0f, 1f, 0f, 64f,
            0f, 0f, 0f, 1f, 0f
        )
        val m = toMedia3RgbMatrix4x4(brightness)
        assertEquals(64f / 255f, m[12], 1e-6f)
        assertEquals(64f / 255f, m[13], 1e-6f)
        assertEquals(64f / 255f, m[14], 1e-6f)
        assertEquals(1f, m[15], 1e-6f)
        // The diagonal must survive the rescale untouched.
        assertEquals(1f, m[0], 1e-6f)
        assertEquals(1f, m[5], 1e-6f)
        assertEquals(1f, m[10], 1e-6f)
    }

    @Test
    fun `the effect never hands Media3 anything but 16 floats`() {
        // The exact invariant DefaultShaderProgram asserts. This is the
        // assertion that would have caught the original bug.
        val effect = GradeRgbMatrix()
        assertEquals(16, effect.getMatrix(0L, false).size)

        effect.matrix = floatArrayOf(
            1.2f, 0f, 0f, 0f, 12f,
            0f, 0.8f, 0f, 0f, -30f,
            0f, 0f, 1.1f, 0f, 5f,
            0f, 0f, 0f, 1f, 0f
        )
        assertEquals(16, effect.getMatrix(1_000_000L, false).size)
    }

    @Test
    fun `assigning a matrix changes what the next frame reads`() {
        // The live-preview path: one instance, matrix swapped per
        // slider tick, re-queried by Media3 every frame.
        val effect = GradeRgbMatrix()
        val before = effect.getMatrix(0L, false)
        effect.matrix = floatArrayOf(
            2f, 0f, 0f, 0f, 0f,
            0f, 2f, 0f, 0f, 0f,
            0f, 0f, 2f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
        val after = effect.getMatrix(0L, false)
        assertEquals(1f, before[0], 1e-6f)
        assertEquals(2f, after[0], 1e-6f)
        assertEquals(2f, after[5], 1e-6f)
        assertEquals(2f, after[10], 1e-6f)
    }

    @Test
    fun `a neutral grade converts to Media3's identity so the shader is a no-op`() {
        // The cross-renderer invariant: the same pipeline the export
        // bakes must reduce to the identity at rest.
        val export = ColorGradingMath.fullExportMatrix(
            grading = ColorGrading.Identity,
            effect = VideoEffect.NONE,
            brightness = 1f,
            contrast = 1f,
            saturation = 1f
        )
        assertArrayEquals(
            floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f
            ),
            toMedia3RgbMatrix4x4(export),
            1e-6f
        )
    }
}
