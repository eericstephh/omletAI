package com.aicreatorstudio.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [VideoDimensions] — the rotation-aware resolution of a
 * video's **display** dimensions.
 *
 * This replaces the inline swap that used to live in
 * `VideoMetadataExtractor`, which could not be tested on the JVM (it needs
 * a real `MediaMetadataRetriever`). The swap decides the display aspect
 * ratio, which in turn drives the preview canvas, the export frame, the
 * letterbox geometry and the motion-tracking frame rect — so a wrong swap
 * moves all four at once, and it has to be pinned down here.
 *
 * Plain `Int` / `Float` only: this project's `unitTests.isReturnDefaultValues
 * = true` makes `android.*` types inert, so nothing here may touch the
 * framework.
 */
class VideoDimensionsTest {

    private val delta = 0.0001f

    // ---- Rotation normalisation ----

    @Test
    fun `rotation zero stays zero`() {
        assertEquals(0, VideoDimensions.normalizeRotation(0))
    }

    @Test
    fun `negative quarter turn normalises to 270`() {
        // Containers do hand back -90. An exact `== 90 || == 270` test
        // silently drops it and the clip renders sideways.
        assertEquals(270, VideoDimensions.normalizeRotation(-90))
    }

    @Test
    fun `270 normalises to 270`() {
        assertEquals(270, VideoDimensions.normalizeRotation(270))
    }

    @Test
    fun `450 normalises to 90`() {
        assertEquals(90, VideoDimensions.normalizeRotation(450))
    }

    @Test
    fun `full turn normalises to zero`() {
        assertEquals(0, VideoDimensions.normalizeRotation(360))
    }

    @Test
    fun `negative full turn normalises to zero`() {
        assertEquals(0, VideoDimensions.normalizeRotation(-360))
    }

    // ---- Quarter-turn predicate ----

    @Test
    fun `90 and 270 are quarter turns`() {
        assertTrue(VideoDimensions.isQuarterTurn(90))
        assertTrue(VideoDimensions.isQuarterTurn(270))
    }

    @Test
    fun `180 is not a quarter turn`() {
        // A half turn rotates the picture but does not exchange the axes,
        // so the frame shape is unchanged.
        assertFalse(VideoDimensions.isQuarterTurn(180))
    }

    @Test
    fun `zero is not a quarter turn`() {
        assertFalse(VideoDimensions.isQuarterTurn(0))
    }

    @Test
    fun `negative 90 is a quarter turn once normalised`() {
        assertTrue(VideoDimensions.isQuarterTurn(-90))
    }

    // ---- The swap itself ----

    @Test
    fun `landscape source with no rotation keeps its dims`() {
        assertEquals(1920 to 1080, VideoDimensions.resolve(1920, 1080, 0))
    }

    @Test
    fun `landscape coded frame with 90 rotation becomes portrait`() {
        // The canonical phone-video case: coded 1920x1080 plus a 90 degree
        // matrix displays as 1080x1920.
        assertEquals(1080 to 1920, VideoDimensions.resolve(1920, 1080, 90))
    }

    @Test
    fun `landscape coded frame with 270 rotation becomes portrait`() {
        assertEquals(1080 to 1920, VideoDimensions.resolve(1920, 1080, 270))
    }

    @Test
    fun `landscape coded frame with negative 90 rotation becomes portrait`() {
        assertEquals(1080 to 1920, VideoDimensions.resolve(1920, 1080, -90))
    }

    @Test
    fun `180 rotation leaves the frame shape alone`() {
        assertEquals(1920 to 1080, VideoDimensions.resolve(1920, 1080, 180))
    }

    @Test
    fun `portrait coded frame with 90 rotation becomes landscape`() {
        assertEquals(1920 to 1080, VideoDimensions.resolve(1080, 1920, 90))
    }

    // ---- Degenerate metadata is never guessed at ----

    @Test
    fun `zero width is returned unchanged`() {
        assertEquals(0 to 1080, VideoDimensions.resolve(0, 1080, 90))
    }

    @Test
    fun `zero height is returned unchanged`() {
        assertEquals(1920 to 0, VideoDimensions.resolve(1920, 0, 90))
    }

    @Test
    fun `negative width is returned unchanged`() {
        assertEquals(-1920 to 1080, VideoDimensions.resolve(-1920, 1080, 0))
    }

    @Test
    fun `unreadable metadata is not silently turned into a plausible pair`() {
        // 0x0 must stay 0x0. Inventing 1080x1920 here would make a failed
        // read indistinguishable from a correct portrait reading.
        assertEquals(0 to 0, VideoDimensions.resolve(0, 0, 90))
    }

    // ---- The decoded-frame probe ----

    @Test
    fun `probe agreeing with metadata leaves the metadata reading intact`() {
        assertEquals(
            1080 to 1920,
            VideoDimensions.resolve(1920, 1080, 90, probeWidth = 1080, probeHeight = 1920)
        )
    }

    @Test
    fun `probe proving landscape overrides a rotation swap`() {
        // THE headline fix. The container says 1920x1080 + rotation 90,
        // which would swap to 1080x1920 (portrait). But a decoded frame —
        // which has rotation already applied, so it is ground truth —
        // comes back landscape. The retriever had already folded the
        // rotation into the reported width/height, so swapping again was
        // the bug that turned a landscape clip portrait.
        assertEquals(
            1920 to 1080,
            VideoDimensions.resolve(1920, 1080, 90, probeWidth = 1920, probeHeight = 1080)
        )
    }

    @Test
    fun `probe proving portrait overrides an unrotated landscape reading`() {
        // Mirror of the headline case: the container reports an unrotated
        // landscape frame, but the decoded picture is portrait, so the
        // rotation was under-reported and the probe supplies it.
        assertEquals(
            1080 to 1920,
            VideoDimensions.resolve(1920, 1080, 0, probeWidth = 1080, probeHeight = 1920)
        )
    }

    @Test
    fun `probe always yields the containers own pixel magnitudes`() {
        // The probe decides orientation only. A probe taken from a
        // downscaled decode must not shrink the reported resolution.
        assertEquals(
            1920 to 1080,
            VideoDimensions.resolve(1920, 1080, 90, probeWidth = 640, probeHeight = 360)
        )
    }

    @Test
    fun `square probe is ignored because it has no orientation`() {
        assertEquals(
            1080 to 1920,
            VideoDimensions.resolve(1920, 1080, 90, probeWidth = 512, probeHeight = 512)
        )
    }

    @Test
    fun `missing probe falls back to the metadata reading`() {
        assertEquals(1080 to 1920, VideoDimensions.resolve(1920, 1080, 90, 0, 0))
    }

    @Test
    fun `half-specified probe falls back to the metadata reading`() {
        assertEquals(1080 to 1920, VideoDimensions.resolve(1920, 1080, 90, 1920, 0))
    }

    // ---- Aspect ratio ----

    @Test
    fun `landscape aspect ratio is width over height`() {
        assertEquals(1.7777f, VideoDimensions.aspectRatio(1920, 1080), 0.001f)
    }

    @Test
    fun `portrait aspect ratio is width over height`() {
        // 9:16 = 0.5625. The inverse (1.7778) would be the classic
        // inverted-ratio bug.
        assertEquals(0.5625f, VideoDimensions.aspectRatio(1080, 1920), delta)
    }

    @Test
    fun `unknown dimensions have no aspect ratio opinion`() {
        assertEquals(0f, VideoDimensions.aspectRatio(0, 1080), delta)
        assertEquals(0f, VideoDimensions.aspectRatio(1920, 0), delta)
        assertEquals(0f, VideoDimensions.aspectRatio(-1920, 1080), delta)
    }
}
