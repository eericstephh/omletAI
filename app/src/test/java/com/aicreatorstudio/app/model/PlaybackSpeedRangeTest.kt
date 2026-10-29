package com.aicreatorstudio.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PlaybackSpeedRange] — the single source of truth for
 * the playback-speed bounds shared by the Speed panel presets, the
 * Effects-panel slider and the ExoPlayer rate applied in `EditorScreen`.
 *
 * Regression guard: the bounds used to be copy-pasted across the
 * ViewModel, the preview effects and the slider range, so the UI could
 * offer a rate the player then rejected.
 */
class PlaybackSpeedRangeTest {

    @Test
    fun `min is 0_25x and max is 4x`() {
        assertEquals(0.25f, PlaybackSpeedRange.MIN, 0f)
        assertEquals(4f, PlaybackSpeedRange.MAX, 0f)
    }

    // ---- in-range values pass through untouched ----

    @Test
    fun `clamp keeps in-range speeds untouched`() {
        listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 4f).forEach { speed ->
            assertEquals(speed, PlaybackSpeedRange.clamp(speed), 1e-6f)
        }
    }

    @Test
    fun `the documented 0_25x to 2x core range is fully inside the clamp`() {
        listOf(0.25f, 0.5f, 1f, 1.5f, 2f).forEach { speed ->
            assertEquals(speed, PlaybackSpeedRange.clamp(speed), 1e-6f)
        }
    }

    // ---- out-of-range values are pinned to the bounds ----

    @Test
    fun `clamp lifts sub-minimum speeds to MIN`() {
        assertEquals(0.25f, PlaybackSpeedRange.clamp(0f), 1e-6f)
        assertEquals(0.25f, PlaybackSpeedRange.clamp(-3f), 1e-6f)
        assertEquals(0.25f, PlaybackSpeedRange.clamp(0.1f), 1e-6f)
    }

    @Test
    fun `clamp caps above-maximum speeds at MAX`() {
        assertEquals(4f, PlaybackSpeedRange.clamp(8f), 1e-6f)
        assertEquals(4f, PlaybackSpeedRange.clamp(100f), 1e-6f)
    }

    // ---- non-finite input must never reach ExoPlayer ----

    @Test
    fun `clamp maps NaN to neutral 1x instead of propagating it`() {
        val clamped = PlaybackSpeedRange.clamp(Float.NaN)
        assertEquals(1f, clamped, 1e-6f)
        assertTrue("NaN must not survive the clamp", !clamped.isNaN())
    }

    @Test
    fun `clamp pins infinities to the bounds`() {
        assertEquals(4f, PlaybackSpeedRange.clamp(Float.POSITIVE_INFINITY), 1e-6f)
        assertEquals(0.25f, PlaybackSpeedRange.clamp(Float.NEGATIVE_INFINITY), 1e-6f)
    }

    @Test
    fun `clamp always returns a strictly positive finite rate`() {
        val hostile = listOf(
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            0f,
            -0f,
            -1f,
            Float.MIN_VALUE,
            Float.MAX_VALUE
        )
        hostile.forEach { input ->
            val clamped = PlaybackSpeedRange.clamp(input)
            assertTrue("clamp($input) must be finite", clamped.isFinite())
            assertTrue("clamp($input) must be > 0", clamped > 0f)
        }
    }

    // ---- presets ----

    @Test
    fun `presets cover the documented 0_25x to 2x core range`() {
        assertTrue(PlaybackSpeedRange.PRESETS.contains(0.25f))
        assertTrue(PlaybackSpeedRange.PRESETS.contains(0.5f))
        assertTrue(PlaybackSpeedRange.PRESETS.contains(1f))
        assertTrue(PlaybackSpeedRange.PRESETS.contains(1.5f))
        assertTrue(PlaybackSpeedRange.PRESETS.contains(2f))
    }

    @Test
    fun `presets are ascending so the chip row reads slow to fast`() {
        assertEquals(
            PlaybackSpeedRange.PRESETS.sorted(),
            PlaybackSpeedRange.PRESETS
        )
    }

    @Test
    fun `every preset survives a clamp round trip`() {
        PlaybackSpeedRange.PRESETS.forEach { preset ->
            assertEquals(preset, PlaybackSpeedRange.clamp(preset), 1e-6f)
        }
    }
}
