package com.aicreatorstudio.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-09-19 — preview/export parity lives or dies by the shared
 * renderer's NORMALIZED metrics: every size derives from the
 * caller-supplied canvas, never from screen pixels. These tests
 * lock the single definitions both renderers share.
 */
class CaptionRendererParityTest {

    @Test
    fun `font size scales linearly with canvas height`() {
        // 24sp on the 720-unit reference canvas.
        assertEquals(48f, CaptionRenderer.fontSizePx(24f, 720), 0.001f)
        // Same caption on a 1080p frame: exactly 1.5x.
        assertEquals(72f, CaptionRenderer.fontSizePx(24f, 1080), 0.001f)
        // A 200px panel card: proportionally identical caption.
        assertEquals(
            CaptionRenderer.fontSizePx(24f, 1080) / 1080f * 200f,
            CaptionRenderer.fontSizePx(24f, 200),
            0.001f
        )
    }

    @Test
    fun `max width is a pure canvas fraction`() {
        assertEquals(1728, CaptionRenderer.maxTextWidthPx(0.9f, 1920))
        assertEquals(324, CaptionRenderer.maxTextWidthPx(0.9f, 360))
    }

    @Test
    fun `dp fields scale off the 720 reference height`() {
        assertEquals(2f, CaptionRenderer.dpToPx(2f, 720), 0.001f)
        assertEquals(3f, CaptionRenderer.dpToPx(2f, 1080), 0.001f)
    }

    @Test
    fun `anchors match the exported placement bands`() {
        val style = com.aicreatorstudio.app.model.SubtitleStyle.Default
        val (x, y) = CaptionRenderer.anchorFractions(style)
        assertEquals(0.5f, x, 0.0001f)
        assertEquals(0.88f, y, 0.0001f)
    }

    @Test
    fun `centered box lands in the bottom band`() {
        // 1920x1080 canvas, 800x100 box, no drag.
        val (left, top) = CaptionRenderer.placeBox(
            canvasWidthPx = 1920, canvasHeightPx = 1080,
            anchorX = 0.5f, anchorY = 0.88f,
            dragX = 0.5f, dragY = 0.5f,
            boxWidthPx = 800f, boxHeightPx = 100f
        )
        assertEquals(560f, left, 0.001f)
        assertEquals(0.88f * 1080f - 50f, top, 0.001f)
    }

    @Test
    fun `drag moves the box in canvas fractions`() {
        val (leftA, _) = CaptionRenderer.placeBox(
            1000, 1000, 0.5f, 0.5f, 0.5f, 0.5f, 200f, 100f
        )
        val (leftB, _) = CaptionRenderer.placeBox(
            1000, 1000, 0.5f, 0.5f, 0.75f, 0.5f, 200f, 100f
        )
        // dragX 0.5 -> 0.75 shifts right by a quarter canvas.
        assertEquals(250f, leftB - leftA, 0.001f)
    }

    @Test
    fun `oversized box pins to frame instead of crashing`() {
        // Box wider AND taller than the canvas: coerceIn with an
        // inverted range would throw; the renderer must pin to 0.
        val (left, top) = CaptionRenderer.placeBox(
            canvasWidthPx = 400, canvasHeightPx = 300,
            anchorX = 0.5f, anchorY = 0.5f,
            dragX = 0.5f, dragY = 0.5f,
            boxWidthPx = 900f, boxHeightPx = 800f
        )
        assertEquals(0f, left, 0.001f)
        assertEquals(0f, top, 0.001f)
    }

    @Test
    fun `rtl detection separates persian from latin`() {
        assertTrue(CaptionRenderer.isRtl("سلام دنیا"))
        assertTrue(CaptionRenderer.isRtl("Hello سلام"))
        assertFalse(CaptionRenderer.isRtl("Hello world"))
    }

    @Test
    fun `karaoke lerp hits both endpoints`() {
        val from = 0xFFFFFFFF.toInt()
        val to = 0xFFFFD60A.toInt()
        assertEquals(from, CaptionRenderer.lerpArgb(from, to, 0f))
        assertEquals(to, CaptionRenderer.lerpArgb(from, to, 1f))
    }
}
