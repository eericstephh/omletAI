package com.aicreatorstudio.app.ai.media

import com.aicreatorstudio.app.model.VideoDimensions
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The [VideoFramePipeline.SourceInfo] display-size defaults must be
 * decision-identical to the rotation matrix the decode loop applies.
 *
 * Historically the defaults compared the RAW container rotation
 * (`rotation == 90 || rotation == 270`) while `rotationMatrix`
 * normalized it (`(rotation % 360 + 360) % 360`), so a container that
 * reports `-90` (or `450`, etc.) decoded rotation-corrected portrait
 * frames into a pipeline sized as landscape: the matting composite and
 * the Auto Frame re-frame then scaled/masked the wrong orientation.
 * These tests pin the normalized behaviour on both sides of the class.
 */
class SourceInfoDisplaySizeTest {

    private fun info(rotation: Int): VideoFramePipeline.SourceInfo =
        VideoFramePipeline.SourceInfo(
            width = 1920, height = 1080,
            rotation = rotation,
            durationMs = 1000L, frameRate = 30,
            videoMime = "video/avc", hasAudioTrack = false
        )

    @Test
    fun `quarter turn swaps axes`() {
        val i = info(90)
        assertEquals(1080, i.displayWidth)
        assertEquals(1920, i.displayHeight)
    }

    @Test
    fun `half turn does not swap axes`() {
        val i = info(180)
        assertEquals(1920, i.displayWidth)
        assertEquals(1080, i.displayHeight)
    }

    @Test
    fun `no rotation keeps axes`() {
        val i = info(0)
        assertEquals(1920, i.displayWidth)
        assertEquals(1080, i.displayHeight)
    }

    @Test
    fun `negative quarter turn normalizes and swaps axes`() {
        // -90 == 270 normalized: rows the decode loop still rotates.
        val i = info(-90)
        assertEquals(1080, i.displayWidth)
        assertEquals(1920, i.displayHeight)
    }

    @Test
    fun `out-of-range rotation normalizes (450 == 90)`() {
        val i = info(450)
        assertEquals(1080, i.displayWidth)
        assertEquals(1920, i.displayHeight)
    }

    @Test
    fun `normalize() is the convention the defaults must match`() {
        // Guard against the two helpers drifting apart again.
        for (raw in listOf(-450, -270, -90, 0, 90, 180, 270, 450)) {
            val r = VideoDimensions.normalizeRotation(raw)
            val expectedSwap = r == 90 || r == 270
            assertEquals(
                "rotation=$raw",
                expectedSwap,
                VideoDimensions.isQuarterTurn(raw)
            )
        }
    }

    @Test
    fun `portrait container uses swapped defaults on construction`() {
        // A portrait-coded stream (1080x1920) with a quarter turn has a
        // landscape display size.
        val i = VideoFramePipeline.SourceInfo(
            width = 1080, height = 1920,
            rotation = 90,
            durationMs = 1000L, frameRate = 30,
            videoMime = "video/avc", hasAudioTrack = false
        )
        assertEquals(1920, i.displayWidth)
        assertEquals(1080, i.displayHeight)
    }
}
