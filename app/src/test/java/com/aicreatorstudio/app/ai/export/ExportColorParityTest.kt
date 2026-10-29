package com.aicreatorstudio.app.ai.export

import com.aicreatorstudio.app.model.ColorGrading
import com.aicreatorstudio.app.model.VideoEffect
import com.aicreatorstudio.app.ui.screens.editor.ColorGradingMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the 2026-09-10 export color parity contract: the matrix baked
 * into the exported MP4 ([ColorGradingMath.fullExportMatrix]) must be
 * identical to the preview's chain — B/C/S × effect × Lumetri — and
 * must be the exact identity when every panel input is neutral.
 *
 * The B/C/S + concat math is pure Kotlin (JVM-testable); the paths
 * that need `android.graphics` (effect presets, full Lumetri grade)
 * are covered by the device-side parity test in androidTest.
 */
class ExportColorParityTest {

    @Test
    fun `all-neutral inputs produce the identity matrix`() {
        val m = ColorGradingMath.fullExportMatrix(
            grading = ColorGrading.Identity,
            effect = VideoEffect.NONE,
            brightness = 1f,
            contrast = 1f,
            saturation = 1f
        )
        assertTrue(
            "expected identity, got ${m.joinToString()}",
            ColorGradingMath.isIdentityMatrix(m)
        )
    }

    @Test
    fun `brightness scales the diagonal`() {
        val m = ColorGradingMath.fullExportMatrix(
            grading = ColorGrading.Identity,
            effect = VideoEffect.NONE,
            brightness = 1.5f,
            contrast = 1f,
            saturation = 1f
        )
        // k = c * b * s = 1.5 — the R diagonal of the 4×5 matrix.
        assertEquals(1.5f, m[0], 1e-4f)
        assertEquals(1.5f, m[6], 1e-4f)
        assertEquals(1.5f, m[12], 1e-4f)
        assertFalse(ColorGradingMath.isIdentityMatrix(m))
    }

    @Test
    fun `isIdentityMatrix rejects wrong sizes`() {
        assertFalse(ColorGradingMath.isIdentityMatrix(FloatArray(19)))
    }

    @Test
    fun `concat matches postConcat semantics for offsets`() {
        // A = brightness offset +0.5 on R; B = identity.
        val a = floatArrayOf(
            1f, 0f, 0f, 0f, 0.5f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
        val id = FloatArray(20) { i -> if (i % 5 == i / 5) 1f else 0f }
        val c = ColorGradingMath.concatColorMatrix(a, id)
        assertTrue(ColorGradingMath.isIdentityMatrix(id))
        assertEquals(0.5f, c[4], 1e-6f)
        // Scaled identity: diagonal preserved, offset preserved.
        assertEquals(1f, c[0], 1e-6f)
    }

    @Test
    fun `concat of two scales multiplies diagonals`() {
        val a = FloatArray(20) { i -> if (i % 5 == i / 5) 2f else 0f }
        val b = FloatArray(20) { i -> if (i % 5 == i / 5) 3f else 0f }
        val c = ColorGradingMath.concatColorMatrix(a, b)
        assertEquals(6f, c[0], 1e-5f)
        assertEquals(6f, c[6], 1e-5f)
        assertEquals(6f, c[12], 1e-5f)
    }

    // ----------------------------------------------------------------
    // PHASE A (Persistence Integrity) — preview/export parity.
    //
    // The preview and the export used to compute their OWN B/C/S
    // matrices: the preview a brightness offset of (b-1)*128 on a
    // diagonal contrast scale with a saturation *lerp*, the export a
    // pivot gain `k = c·b·s`. Both blocks claimed parity. They agreed
    // only at saturation == 1f, and only at mid-grey — so
    // `brightness = 1.5` previewed as `R + 64` and exported as
    // `1.5·R`. These tests pin the preview to the export's math.
    // ----------------------------------------------------------------

    /** Every input combination the panels can produce. */
    private val bcsSamples = listOf(
        Triple(1f, 1f, 1f),
        Triple(1.5f, 1f, 1f),
        Triple(0.5f, 1f, 1f),
        Triple(1f, 1.5f, 1f),
        Triple(1f, 0.5f, 1f),
        Triple(1f, 1f, 1.5f),
        Triple(1f, 1f, 0.5f),
        Triple(1f, 1f, 0f),
        Triple(1.2f, 1.3f, 1.1f),
        Triple(0.7f, 0.6f, 1.4f),
        Triple(2f, 2f, 2f),
        Triple(0f, 1f, 1f)
    )

    @Test
    fun `the preview matrix is byte-identical to the export matrix`() {
        // Effect matrices need `android.graphics` (covered device-side
        // per this class's KDoc), so parity is pinned with the base
        // B/C/S chain — which is exactly where the two formulas used
        // to diverge.
        for ((b, c, s) in bcsSamples) {
            val expected = ColorGradingMath.fullExportMatrix(
                grading = ColorGrading.Identity,
                effect = VideoEffect.NONE,
                brightness = b,
                contrast = c,
                saturation = s
            )
            val preview = ColorGradingMath.previewColorMatrixArray(VideoEffect.NONE, b, c, s)
            assertEquals(
                "preview ≠ export for b=$b c=$c s=$s",
                expected.joinToString(), preview.joinToString()
            )
            assertEquals(expected.size, preview.size)
        }
    }

    @Test
    fun `the preview uses the pivot-gain formula not the old offset one`() {
        // Regression guard against reintroducing the divergence: the
        // deleted preview formula produced a diagonal of `c` with an
        // offset of `(b-1)*128`. The shared one produces
        // `k = c·b·s` and `off = 127.5·(1-c)·b`.
        for ((b, c, s) in bcsSamples) {
            val m = ColorGradingMath.previewColorMatrixArray(VideoEffect.NONE, b, c, s)
            assertEquals("diagonal for b=$b c=$c s=$s", c * b * s, m[0], 1e-3f)
            assertEquals("diagonal R for b=$b c=$c s=$s", c * b * s, m[6], 1e-3f)
            assertEquals("diagonal B for b=$b c=$c s=$s", c * b * s, m[12], 1e-3f)
            assertEquals("offset for b=$b c=$c s=$s", 127.5f * (1f - c) * b, m[4], 1e-1f)
            // And NOT the old numbers. `b == 1 && c == 1` is excluded:
            // both formulas are the identity there, so the case is
            // genuinely ambiguous rather than evidence of a regression.
            if (s == 1f && !(b == 1f && c == 1f)) {
                assertFalse(
                    "must not use the old offset formula for b=$b c=$c",
                    m[0] == c && m[4] == (b - 1f) * 128f
                )
            }
        }
    }

    @Test
    fun `a neutral preview is the identity matrix`() {
        val preview = ColorGradingMath.previewColorMatrixArray(VideoEffect.NONE, 1f, 1f, 1f)
        assertTrue(
            "expected identity, got ${preview.joinToString()}",
            ColorGradingMath.isIdentityMatrix(preview)
        )
    }

    @Test
    fun `saturation no longer renders differently in preview and export`() {
        // The exact case that used to diverge: a controlled
        // desaturation read as a luminance lerp in the preview and a
        // proportional gain in the export.
        val preview = ColorGradingMath.previewColorMatrixArray(VideoEffect.NONE, 1f, 1f, 0.5f)
        val export = ColorGradingMath.fullExportMatrix(
            grading = ColorGrading.Identity,
            effect = VideoEffect.NONE,
            brightness = 1f,
            contrast = 1f,
            saturation = 0.5f
        )
        assertEquals(export.joinToString(), preview.joinToString())
        // And the preview is NOT a saturation lerp toward luminance.
        assertEquals(0.5f, preview[0], 1e-3f)
    }
}
