package com.aicreatorstudio.app.export

import com.aicreatorstudio.app.model.UpscalerPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards [UpscaleOutputSize], the pure sizing math behind the AI upscaler
 * export.
 *
 * Why this matters: `Transformer` has no "set output resolution" API, so
 * the upscale only happens if a `Presentation` effect carries the size.
 * Before 2026-09-16 the engine computed an upscaled size into a local
 * `ExportConfig` and passed it to nothing — `ULTRA_4K` produced a
 * source-resolution file.
 *
 * These tests pin two things that are easy to get backwards:
 *  1. the **target frame** (canvas preset, or the preset's base quality)
 *     is what gets multiplied — not the source clip's pixels, which would
 *     silently discard a 9:16 or 1:1 canvas;
 *  2. degenerate input (zero, negative, NaN, odd) never reaches the
 *     encoder as an unusable frame size.
 */
class UpscaleOutputSizeTest {

    // --- The user-facing contract, expressed through the real presets ---

    @Test
    fun `a 1080p canvas under ULTRA_4K lands on 4K`() {
        // ULTRA_4K is documented as "True 2x upscale to 4K", so this is the
        // single most important assertion in the file.
        val size = UpscaleOutputSize.compute(
            targetWidthPx = 1920,
            targetHeightPx = 1080,
            targetScale = UpscalerPreset.ULTRA_4K.targetScale,
            sourceWidthPx = 0,
            sourceHeightPx = 0
        )
        assertEquals(3840 to 2160, size)
    }

    @Test
    fun `a 9x16 canvas under ULTRA_4K stays portrait`() {
        // The canvas aspect must survive the upscale. Sizing from the source
        // clip instead of the canvas is exactly how this regresses.
        val size = UpscaleOutputSize.compute(
            targetWidthPx = 1080,
            targetHeightPx = 1920,
            targetScale = UpscalerPreset.ULTRA_4K.targetScale,
            sourceWidthPx = 1920,
            sourceHeightPx = 1080
        )
        assertEquals(2160 to 3840, size)
    }

    @Test
    fun `the 4K base quality under ULTRA_4K lands on 8K`() {
        // `UpscalerExportPresets.ULTRA_4K_BASE` uses ExportQuality.ULTRA_4K
        // (3840x2160), and the engine multiplies that by targetScale — so the
        // FREE-canvas path genuinely targets 8K. Pinned here so the behaviour
        // is visible rather than discovered on a device; the clamp below is
        // what keeps it from going further.
        val size = UpscaleOutputSize.compute(
            targetWidthPx = ExportQuality.ULTRA_4K.widthPx,
            targetHeightPx = ExportQuality.ULTRA_4K.heightPx,
            targetScale = UpscalerPreset.ULTRA_4K.targetScale,
            sourceWidthPx = 0,
            sourceHeightPx = 0
        )
        assertEquals(7680 to 4320, size)
    }

    @Test
    fun `the 1x presets keep the target dimensions`() {
        // SMART_HD and FACE_CLARITY are "enhancement only" — targetScale 1.0
        // means the pixel grid must not change, only the sharpening.
        for (preset in listOf(UpscalerPreset.SMART_HD_1080P, UpscalerPreset.FACE_CLARITY_BOOST)) {
            assertEquals(
                "preset ${preset.name} should not resize",
                1920 to 1080,
                UpscaleOutputSize.compute(
                    targetWidthPx = 1920,
                    targetHeightPx = 1080,
                    targetScale = preset.targetScale,
                    sourceWidthPx = 0,
                    sourceHeightPx = 0
                )
            )
        }
    }

    // --- Fallbacks and degenerate input ---

    @Test
    fun `the target frame wins over the source geometry`() {
        // The source is 4K, the canvas is 1080p. The canvas must win, or the
        // user's aspect/quality choice is silently overridden.
        val size = UpscaleOutputSize.compute(
            targetWidthPx = 1920,
            targetHeightPx = 1080,
            targetScale = 1f,
            sourceWidthPx = 3840,
            sourceHeightPx = 2160
        )
        assertEquals(1920 to 1080, size)
    }

    @Test
    fun `no target frame falls back to the source geometry`() {
        val size = UpscaleOutputSize.compute(
            targetWidthPx = 0,
            targetHeightPx = 0,
            targetScale = 2f,
            sourceWidthPx = 1080,
            sourceHeightPx = 1920
        )
        assertEquals(2160 to 3840, size)
    }

    @Test
    fun `nothing known yields null so the caller omits the Presentation`() {
        assertNull(
            UpscaleOutputSize.compute(
                targetWidthPx = 0,
                targetHeightPx = 0,
                targetScale = 2f,
                sourceWidthPx = 0,
                sourceHeightPx = 0
            )
        )
        // A negative dimension is as unusable as a zero one.
        assertNull(
            UpscaleOutputSize.compute(
                targetWidthPx = -1920,
                targetHeightPx = -1080,
                targetScale = 2f,
                sourceWidthPx = -1,
                sourceHeightPx = -1
            )
        )
    }

    @Test
    fun `a non-finite or non-positive scale is treated as 1x`() {
        for (scale in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -2f)) {
            assertEquals(
                "scale $scale should fall back to 1x",
                1920 to 1080,
                UpscaleOutputSize.compute(
                    targetWidthPx = 1920,
                    targetHeightPx = 1080,
                    targetScale = scale,
                    sourceWidthPx = 0,
                    sourceHeightPx = 0
                )
            )
        }
    }

    // --- The 8K ceiling ---

    @Test
    fun `overshooting the ceiling is clamped uniformly so the aspect survives`() {
        // A 4:3 frame is used deliberately: clamping each axis independently
        // would still land on 5760x4320 by coincidence for a 16:9 frame,
        // whereas for 4:3 an independent clamp would visibly distort.
        val size = UpscaleOutputSize.compute(
            targetWidthPx = 4000,
            targetHeightPx = 3000,
            targetScale = 2f,
            sourceWidthPx = 0,
            sourceHeightPx = 0
        )
        assertEquals(5760 to 4320, size)
        val (w, h) = requireNotNull(size)
        // 5760 / 4320 == 4 / 3 — the original aspect, undistorted.
        assertEquals(4f / 3f, w.toFloat() / h.toFloat(), 0.0001f)
    }

    @Test
    fun `an 8K frame doubled clamps to exactly the 8K ceiling`() {
        val size = UpscaleOutputSize.compute(
            targetWidthPx = 7680,
            targetHeightPx = 4320,
            targetScale = 2f,
            sourceWidthPx = 0,
            sourceHeightPx = 0
        )
        assertEquals(7680 to 4320, size)
    }

    @Test
    fun `a frame below the ceiling is not clamped at all`() {
        val size = UpscaleOutputSize.compute(
            targetWidthPx = 1920,
            targetHeightPx = 1080,
            targetScale = 2f,
            sourceWidthPx = 0,
            sourceHeightPx = 0
        )
        assertEquals(3840 to 2160, size)
    }

    // --- Encoder safety ---

    @Test
    fun `odd dimensions round down to even for yuv420p`() {
        val size = UpscaleOutputSize.compute(
            targetWidthPx = 1921,
            targetHeightPx = 1081,
            targetScale = 1f,
            sourceWidthPx = 0,
            sourceHeightPx = 0
        )
        assertEquals(1920 to 1080, size)
        val (w, h) = requireNotNull(size)
        assertEquals(0, w % 2)
        assertEquals(0, h % 2)
    }

    @Test
    fun `a degenerate tiny frame still yields a usable even size`() {
        // even(1) would round down to 0, which no encoder accepts — the
        // helper floors at 2.
        assertEquals(
            2 to 2,
            UpscaleOutputSize.compute(
                targetWidthPx = 1,
                targetHeightPx = 1,
                targetScale = 1f,
                sourceWidthPx = 0,
                sourceHeightPx = 0
            )
        )
        assertEquals(
            2 to 2,
            UpscaleOutputSize.compute(
                targetWidthPx = 1,
                targetHeightPx = 1,
                targetScale = 2f,
                sourceWidthPx = 0,
                sourceHeightPx = 0
            )
        )
    }

    @Test
    fun `a realistic 4x3 frame at 1x is returned unchanged`() {
        // Regression guard for the clamp: capFactor must stay 1.0 for any
        // frame already inside the ceiling.
        val size = UpscaleOutputSize.compute(
            targetWidthPx = 1440,
            targetHeightPx = 1080,
            targetScale = 1f,
            sourceWidthPx = 0,
            sourceHeightPx = 0
        )
        assertEquals(1440 to 1080, size)
    }
}
