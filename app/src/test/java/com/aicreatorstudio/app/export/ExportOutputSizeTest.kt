package com.aicreatorstudio.app.export

import com.aicreatorstudio.app.model.CanvasPreset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [ExportFrame.resolveOutputSize] — the frame the encoder,
 * the upscaler and the subtitle burn-in all work against.
 *
 * The bug this pins down: both export entry points used
 * `if (preset.exportWidth > 0) preset.exportWidth else quality.widthPx`, and
 * every [ExportQuality] is defined **landscape**. A [CanvasPreset.FREE]
 * project was therefore framed 16:9 no matter what had been imported — a
 * portrait source got its captions laid out for 1920x1080 and its upscale
 * grid sized the same way, while a landscape source happened to come out
 * right. Same orientation asymmetry as a hard-coded 9:16, mirrored.
 *
 * `HIGH_1080P` = 1920x1080 and `STANDARD_720P` = 1280x720 are used as the
 * tiers below so the numbers are readable; the values are asserted through
 * [ExportQuality] itself, not hard-coded, so a tier change cannot silently
 * invalidate these tests.
 */
class ExportOutputSizeTest {

    private val hdW = ExportQuality.HIGH_1080P.widthPx   // 1920
    private val hdH = ExportQuality.HIGH_1080P.heightPx  // 1080
    private val sdW = ExportQuality.STANDARD_720P.widthPx  // 1280
    private val sdH = ExportQuality.STANDARD_720P.heightPx // 720

    private fun free(sourceW: Int, sourceH: Int, qualityW: Int = hdW, qualityH: Int = hdH) =
        ExportFrame.resolveOutputSize(
            canvasPreset = CanvasPreset.FREE,
            sourceWidthPx = sourceW,
            sourceHeightPx = sourceH,
            qualityWidthPx = qualityW,
            qualityHeightPx = qualityH
        )

    // ---- A sized preset always wins ----

    @Test
    fun `vertical preset wins over a landscape source`() {
        assertEquals(
            1080 to 1920,
            ExportFrame.resolveOutputSize(
                canvasPreset = CanvasPreset.VERTICAL_9_16,
                sourceWidthPx = 1920,
                sourceHeightPx = 1080,
                qualityWidthPx = hdW,
                qualityHeightPx = hdH
            )
        )
    }

    @Test
    fun `wide preset wins over a portrait source`() {
        assertEquals(
            1920 to 1080,
            ExportFrame.resolveOutputSize(
                canvasPreset = CanvasPreset.WIDE_16_9,
                sourceWidthPx = 1080,
                sourceHeightPx = 1920,
                qualityWidthPx = hdW,
                qualityHeightPx = hdH
            )
        )
    }

    // ---- FREE tracks the source's ORIENTATION, not the tier's ----

    @Test
    fun `free landscape source keeps its dims at 1080p`() {
        // Unchanged from the old behaviour, and must stay so.
        assertEquals(1920 to 1080, free(1920, 1080))
    }

    @Test
    fun `free portrait source stays portrait at 1080p`() {
        // Was 1920x1080 before the fix — the source's orientation was
        // thrown away and every caption/upscale target was framed 16:9.
        assertEquals(1080 to 1920, free(1080, 1920))
    }

    @Test
    fun `free portrait source stays portrait at 720p`() {
        // Long side (1920) exceeds the 720p budget (1280), so it scales
        // down — but on the SOURCE's axis, not the tier's.
        assertEquals(720 to 1280, free(1080, 1920, sdW, sdH))
    }

    @Test
    fun `free landscape source scales down to the 720p budget`() {
        assertEquals(1280 to 720, free(1920, 1080, sdW, sdH))
    }

    // ---- Budget preservation ----

    @Test
    fun `free 4K landscape scales down to 1080p`() {
        assertEquals(1920 to 1080, free(3840, 2160))
    }

    @Test
    fun `free 4K portrait scales down to 1080p`() {
        assertEquals(1080 to 1920, free(2160, 3840))
    }

    @Test
    fun `free source smaller than the tier is never upscaled`() {
        assertEquals(640 to 360, free(640, 360))
    }

    @Test
    fun `free portrait source smaller than the tier is never upscaled`() {
        assertEquals(360 to 640, free(360, 640))
    }

    // ---- Non-canonical ratios keep their own shape ----

    @Test
    fun `free cinematic 2_39 to 1 keeps its shape when it fits the budget`() {
        assertEquals(1920 to 804, free(1920, 804))
    }

    @Test
    fun `free cinematic 2_39 to 1 scales down without becoming 16 to 9`() {
        // 2560x1080 scaled by 1920/2560 = 0.75 -> 1920x810.
        assertEquals(1920 to 810, free(2560, 1080))
    }

    @Test
    fun `free square source stays square`() {
        assertEquals(1080 to 1080, free(1080, 1080))
    }

    // ---- Unknown source falls back to the tier ----

    @Test
    fun `free with unknown source falls back to the quality tier`() {
        assertEquals(hdW to hdH, free(0, 0))
    }

    @Test
    fun `free with a zero height falls back to the quality tier`() {
        assertEquals(hdW to hdH, free(1920, 0))
    }

    @Test
    fun `free with a negative width falls back to the quality tier`() {
        assertEquals(hdW to hdH, free(-1920, 1080))
    }

    // ---- Encoder constraints ----

    @Test
    fun `output dimensions are always even`() {
        // H.264 rejects odd dimensions. 1079 is odd and passes through the
        // no-upscale branch untouched, so `evenSize` has to catch it.
        val (w, h) = free(1920, 1079)
        assertEquals(0, w % 2)
        assertEquals(0, h % 2)
    }

    @Test
    fun `output dimensions never drop below two`() {
        val (w, h) = free(1, 1)
        assertEquals(2, w)
        assertEquals(2, h)
    }

    // ---- The symmetry property, stated once ----

    @Test
    fun `swapping the source axes swaps the resolved output axes`() {
        // This is the whole point of the fix: resolution must be decided by
        // the source's orientation and nothing else. If this holds for a
        // tier, no tier can prefer landscape.
        val landscape = free(1920, 1080)
        val portrait = free(1080, 1920)
        assertEquals(landscape.first to landscape.second, portrait.second to portrait.first)

        val landscapeSd = free(1920, 1080, sdW, sdH)
        val portraitSd = free(1080, 1920, sdW, sdH)
        assertEquals(landscapeSd.first to landscapeSd.second, portraitSd.second to portraitSd.first)
    }
}
