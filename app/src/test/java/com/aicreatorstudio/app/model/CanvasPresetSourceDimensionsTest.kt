package com.aicreatorstudio.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [CanvasPreset.forSourceDimensions] — the import-time
 * canvas detection that replaced the hard-coded `WIDE_16_9` project
 * default (portrait/Shorts compatibility fix).
 *
 * The width/height values fed here are the *rotation-corrected display
 * dimensions* [com.aicreatorstudio.app.video.VideoMetadataExtractor]
 * stores on the clip (it already swaps raw dims for 90°/270° rotation
 * metadata), so these tests cover the values the repository layer
 * actually receives at import time.
 */
class CanvasPresetSourceDimensionsTest {

    // ---- Landscape sources → WIDE_16_9 ----

    @Test
    fun `1920x1080 maps to WIDE_16_9`() {
        assertEquals(CanvasPreset.WIDE_16_9, CanvasPreset.forSourceDimensions(1920, 1080))
    }

    @Test
    fun `1280x720 maps to WIDE_16_9`() {
        assertEquals(CanvasPreset.WIDE_16_9, CanvasPreset.forSourceDimensions(1280, 720))
    }

    @Test
    fun `3840x2160 maps to WIDE_16_9`() {
        assertEquals(CanvasPreset.WIDE_16_9, CanvasPreset.forSourceDimensions(3840, 2160))
    }

    @Test
    fun `encoded 1920x1088 with 16-9 display hint snaps to WIDE_16_9`() {
        // Container frames are sometimes 1088 tall (macroblock padding);
        // 1920/1088 = 1.7647 vs 1.7778 → 0.74% off, inside the ±2.5% window.
        assertEquals(CanvasPreset.WIDE_16_9, CanvasPreset.forSourceDimensions(1920, 1088))
    }

    // ---- Portrait sources → VERTICAL_9_16 ----

    @Test
    fun `1080x1920 maps to VERTICAL_9_16`() {
        assertEquals(CanvasPreset.VERTICAL_9_16, CanvasPreset.forSourceDimensions(1080, 1920))
    }

    @Test
    fun `720x1280 maps to VERTICAL_9_16`() {
        assertEquals(CanvasPreset.VERTICAL_9_16, CanvasPreset.forSourceDimensions(720, 1280))
    }

    @Test
    fun `1440x2560 maps to VERTICAL_9_16`() {
        // Same 9:16 ratio at a different resolution must snap to the
        // canonical 9:16 preset (export bakes 1080x1920).
        assertEquals(CanvasPreset.VERTICAL_9_16, CanvasPreset.forSourceDimensions(1440, 2560))
    }

    @Test
    fun `rotation-corrected phone video dims map to VERTICAL_9_16`() {
        // A phone video whose raw encoded frame is 1920x1080 with 90°
        // rotation metadata arrives here as the display dims 1080x1920.
        assertEquals(CanvasPreset.VERTICAL_9_16, CanvasPreset.forSourceDimensions(1080, 1920))
    }

    // ---- Square / 4:5 ----

    @Test
    fun `1080x1080 maps to SQUARE_1_1`() {
        assertEquals(CanvasPreset.SQUARE_1_1, CanvasPreset.forSourceDimensions(1080, 1080))
    }

    @Test
    fun `1080x1350 maps to PORTRAIT_4_5`() {
        assertEquals(CanvasPreset.PORTRAIT_4_5, CanvasPreset.forSourceDimensions(1080, 1350))
    }

    @Test
    fun `1080x1351 still maps to PORTRAIT_4_5 within tolerance`() {
        // 1080/1351 vs 4/5 = 0.037% off — well inside the window.
        assertEquals(CanvasPreset.PORTRAIT_4_5, CanvasPreset.forSourceDimensions(1080, 1351))
    }

    // ---- Non-canonical ratios → FREE (track source) ----

    @Test
    fun `2560x1080 cinematic maps to FREE`() {
        assertEquals(CanvasPreset.FREE, CanvasPreset.forSourceDimensions(2560, 1080))
    }

    @Test
    fun `1350x1080 5-4 landscape mirror maps to FREE`() {
        // There is no 5:4 preset; the canvas must track the source
        // instead of snapping to the nearest portrait preset.
        assertEquals(CanvasPreset.FREE, CanvasPreset.forSourceDimensions(1350, 1080))
    }

    @Test
    fun `ratio just outside tolerance maps to FREE`() {
        // 1920x1160 = 1.6552 vs 1.7778 → ~6.9% off, outside ±2.5%.
        assertEquals(CanvasPreset.FREE, CanvasPreset.forSourceDimensions(1920, 1160))
    }

    @Test
    fun `9-16 ratio just outside tolerance maps to FREE`() {
        // 1080x2280 = 0.4737 vs 0.5625 → ~15.8% off — a 20.6:9-ish panel
        // recording, not Shorts footage.
        assertEquals(CanvasPreset.FREE, CanvasPreset.forSourceDimensions(1080, 2280))
    }

    // ---- Unknown dimensions → FREE ----

    @Test
    fun `zero dimensions map to FREE`() {
        assertEquals(CanvasPreset.FREE, CanvasPreset.forSourceDimensions(0, 0))
    }

    @Test
    fun `missing width maps to FREE`() {
        assertEquals(CanvasPreset.FREE, CanvasPreset.forSourceDimensions(0, 1080))
    }

    @Test
    fun `missing height maps to FREE`() {
        assertEquals(CanvasPreset.FREE, CanvasPreset.forSourceDimensions(1920, 0))
    }

    @Test
    fun `negative dimensions map to FREE`() {
        assertEquals(CanvasPreset.FREE, CanvasPreset.forSourceDimensions(-1920, 1080))
    }
}
