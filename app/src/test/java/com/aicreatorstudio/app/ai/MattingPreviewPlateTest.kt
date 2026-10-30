package com.aicreatorstudio.app.ai

import com.aicreatorstudio.app.model.MattingBackgroundMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-30 — pins the preview plate's maths and its time base.
 *
 * The plate is the whole reason Background Remove now shows up in the
 * preview, so the two things that can silently make it *wrong* rather
 * than absent are asserted here: the alpha inversion, and the conversion
 * from a project playhead to the source timestamp the sidecar PNGs are
 * named after.
 */
class MattingPreviewPlateTest {

    @Test
    fun `alphaOf reads the high byte`() {
        val argb = intArrayOf(
            0xFF000000.toInt(), // opaque
            0x00000000,         // transparent
            0x80FFFFFF.toInt(), // half
            0x00FFFFFF          // transparent white
        )
        val a = MattingPreviewPlate.alphaOf(argb)
        assertEquals(1f, a[0], 1e-6f)
        assertEquals(0f, a[1], 1e-6f)
        assertEquals(128f / 255f, a[2], 1e-6f)
        assertEquals(0f, a[3], 1e-6f)
    }

    @Test
    fun `the plate inverts the mask so the subject stays visible`() {
        val subject = floatArrayOf(1f, 0f, 0.5f)
        val plate = MattingPreviewPlate.plateOf(subject, 0xFF112233.toInt())

        assertEquals("subject pixel must not be covered", 0, (plate[0] ushr 24) and 0xFF)
        assertEquals("background pixel must be fully covered", 255, (plate[1] ushr 24) and 0xFF)
        assertEquals("half-mask must be half covered", 128, (plate[2] ushr 24) and 0xFF)
    }

    @Test
    fun `the plate carries the fill colour and nothing else`() {
        val plate = MattingPreviewPlate.plateOf(floatArrayOf(0f, 1f), 0xFF112233.toInt())
        for (px in plate) {
            assertEquals(
                "RGB must be the fill colour exactly",
                0x112233,
                px and 0x00FFFFFF
            )
        }
    }

    @Test
    fun `the plate ignores a translucent fill's own alpha`() {
        // The fill supplies a COLOUR; the coverage comes from the mask.
        // A fill that happens to be translucent (a themed swatch) must not
        // dim the result, or a 50%-alpha swatch would show the original
        // background through the plate.
        val plate = MattingPreviewPlate.plateOf(floatArrayOf(0f), 0x80FF0000.toInt())
        assertEquals(255, (plate[0] ushr 24) and 0xFF)
        assertEquals(0xFF0000, plate[0] and 0x00FFFFFF)
    }

    @Test
    fun `only the solid-fill modes are previewable`() {
        assertTrue(MattingPreviewPlate.isPreviewable(MattingBackgroundMode.TRANSPARENT))
        assertTrue(MattingPreviewPlate.isPreviewable(MattingBackgroundMode.COLOR))
        assertFalse(
            "BLUR needs a blurred copy of the frame, not a colour",
            MattingPreviewPlate.isPreviewable(MattingBackgroundMode.BLUR)
        )
        assertFalse(
            "IMAGE needs the decoded still",
            MattingPreviewPlate.isPreviewable(MattingBackgroundMode.IMAGE)
        )
        assertFalse(
            "VIDEO needs a second decoder",
            MattingPreviewPlate.isPreviewable(MattingBackgroundMode.VIDEO)
        )
        assertFalse(
            "ORIGINAL is the pass-through: there is nothing to composite",
            MattingPreviewPlate.isPreviewable(MattingBackgroundMode.ORIGINAL)
        )
    }

    @Test
    fun `sourcePosition maps the project playhead onto the source`() {
        // A clip that starts at 4 s on the timeline and is trimmed to begin
        // 1 s into the file: at project 5 s the source frame is at 2 s.
        assertEquals(
            2_000L,
            MattingPreviewPlate.sourcePositionMs(
                projectPositionMs = 5_000L,
                timelineStartMs = 4_000L,
                trimStartMs = 1_000L
            )
        )
    }

    @Test
    fun `sourcePosition never goes negative`() {
        // Scrubbing to a position before the clip start (or a clip whose
        // timelineStartMs is the -1 "auto layout" sentinel) must clamp, or
        // the lookup would ask for a timestamp no PNG can have.
        assertEquals(
            0L,
            MattingPreviewPlate.sourcePositionMs(
                projectPositionMs = 0L,
                timelineStartMs = -1L,
                trimStartMs = 0L
            )
        )
    }

    @Test
    fun `quantise snaps to the sidecar step`() {
        // The engine samples at 10 fps; decoding per recomposition would
        // mean decoding per frame for the same mask.
        assertEquals(1_200L, MattingPreviewPlate.quantise(1_299L))
        assertEquals(1_200L, MattingPreviewPlate.quantise(1_200L))
        assertEquals(0L, MattingPreviewPlate.quantise(99L))
        assertEquals(1_300L, MattingPreviewPlate.quantise(1_300L, 100L))
    }

    @Test
    fun `quantise with a non-positive step is the identity`() {
        // Guards the divide-by-zero that a caller-supplied step of 0 would
        // otherwise cause.
        assertEquals(1_234L, MattingPreviewPlate.quantise(1_234L, 0L))
        assertEquals(1_234L, MattingPreviewPlate.quantise(1_234L, -5L))
    }

    @Test
    fun `a reused output buffer is written, not reallocated`() {
        // The cache path reuses buffers; a helper that silently allocated
        // would make that pointless and would also break the caller's
        // identity assumption.
        val alphaOut = FloatArray(2)
        val a = MattingPreviewPlate.alphaOf(intArrayOf(0xFF000000.toInt(), 0), alphaOut)
        assertTrue("must reuse the buffer", a === alphaOut)

        val plateOut = IntArray(2)
        val p = MattingPreviewPlate.plateOf(floatArrayOf(1f, 0f), 0xFF00FF00.toInt(), plateOut)
        assertTrue("must reuse the buffer", p === plateOut)
    }
}
