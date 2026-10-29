package com.aicreatorstudio.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards [AudioFade], the shared gain curve behind per-clip audio fades.
 *
 * Why this matters: `fadeInMs` / `fadeOutMs` were write-only for the entire
 * life of the Audio panel — the sliders persisted them, and neither the
 * export's audio chain nor the preview ever read them back. Both renderers
 * now call this object, so these tests are the single place the fade
 * behaviour is defined. A drift here is audible in the exported file.
 */
class AudioFadeTest {

    // --- Identity ---

    @Test
    fun `no fades means unity gain everywhere`() {
        assertTrue(AudioFade.isIdentity(fadeInMs = 0L, fadeOutMs = 0L))
        for (ms in listOf(-100.0, 0.0, 250.0, 1_000.0, 5_000.0)) {
            assertEquals(
                "gain at $ms should be unity when no fade is configured",
                1f,
                AudioFade.gainAt(ms, clipDurationMs = 1_000L, fadeInMs = 0L, fadeOutMs = 0L),
                0.0001f
            )
        }
    }

    @Test
    fun `a single non-zero fade is not identity`() {
        assertFalse(AudioFade.isIdentity(fadeInMs = 1L, fadeOutMs = 0L))
        assertFalse(AudioFade.isIdentity(fadeInMs = 0L, fadeOutMs = 1L))
    }

    // --- Fade in ---

    @Test
    fun `fade in ramps linearly from silence to unity`() {
        // 400 ms ramp on a 1 s clip.
        fun g(ms: Double) =
            AudioFade.gainAt(ms, clipDurationMs = 1_000L, fadeInMs = 400L, fadeOutMs = 0L)

        assertEquals(0f, g(0.0), 0.0001f)
        assertEquals(0.25f, g(100.0), 0.0001f)
        assertEquals(0.5f, g(200.0), 0.0001f)
        assertEquals(0.75f, g(300.0), 0.0001f)
        // At the end of the window the ramp is complete.
        assertEquals(1f, g(400.0), 0.0001f)
        assertEquals(1f, g(700.0), 0.0001f)
    }

    @Test
    fun `fade in is monotonic across its window`() {
        var previous = -1f
        var ms = 0.0
        while (ms <= 400.0) {
            val gain = AudioFade.gainAt(
                ms, clipDurationMs = 1_000L, fadeInMs = 400L, fadeOutMs = 0L
            )
            assertTrue("gain must not decrease (at $ms ms)", gain >= previous)
            previous = gain
            ms += 10.0
        }
        assertEquals(1f, previous, 0.0001f)
    }

    // --- Fade out ---

    @Test
    fun `fade out ramps linearly from unity down to silence at the clip end`() {
        fun g(ms: Double) =
            AudioFade.gainAt(ms, clipDurationMs = 1_000L, fadeInMs = 0L, fadeOutMs = 400L)

        assertEquals(1f, g(0.0), 0.0001f)
        // The window opens 400 ms before the end.
        assertEquals(1f, g(600.0), 0.0001f)
        assertEquals(0.5f, g(800.0), 0.0001f)
        assertEquals(0.25f, g(900.0), 0.0001f)
        assertEquals(0f, g(1_000.0), 0.0001f)
    }

    @Test
    fun `fade out is monotonic non-increasing across its window`() {
        var previous = 2f
        var ms = 600.0
        while (ms <= 1_000.0) {
            val gain = AudioFade.gainAt(
                ms, clipDurationMs = 1_000L, fadeInMs = 0L, fadeOutMs = 400L
            )
            assertTrue("gain must not increase (at $ms ms)", gain <= previous)
            previous = gain
            ms += 10.0
        }
        assertEquals(0f, previous, 0.0001f)
    }

    // --- Both ends at once ---

    @Test
    fun `independent head and tail fades leave a unity plateau between them`() {
        fun g(ms: Double) =
            AudioFade.gainAt(ms, clipDurationMs = 1_000L, fadeInMs = 200L, fadeOutMs = 200L)

        assertEquals(0f, g(0.0), 0.0001f)
        assertEquals(0.5f, g(100.0), 0.0001f)
        assertEquals(1f, g(200.0), 0.0001f)
        assertEquals(1f, g(500.0), 0.0001f)
        assertEquals(1f, g(800.0), 0.0001f)
        assertEquals(0.5f, g(900.0), 0.0001f)
        assertEquals(0f, g(1_000.0), 0.0001f)
    }

    @Test
    fun `overlapping windows dip in the middle instead of cancelling out`() {
        // 800 + 800 on a 1 s clip overlaps heavily. The smaller gain wins,
        // so the clip ducks through the middle — rather than the Out window
        // being silently ignored (or the In window winning outright).
        fun g(ms: Double) =
            AudioFade.gainAt(ms, clipDurationMs = 1_000L, fadeInMs = 800L, fadeOutMs = 800L)

        assertEquals(0f, g(0.0), 0.0001f)
        assertEquals(0.25f, g(200.0), 0.0001f)
        // At the midpoint both ramps agree, and that is the peak.
        assertEquals(0.625f, g(500.0), 0.0001f)
        assertEquals(0.25f, g(800.0), 0.0001f)
        assertEquals(0f, g(1_000.0), 0.0001f)

        // The peak must never exceed what a single ramp would allow there.
        assertTrue(g(500.0) < 1f)
        // Symmetry: the curve is its own mirror image.
        assertEquals(g(300.0), g(700.0), 0.0001f)
    }

    @Test
    fun `the dip is symmetric around the midpoint`() {
        var offset = 0.0
        while (offset <= 500.0) {
            val left = AudioFade.gainAt(
                500.0 - offset, clipDurationMs = 1_000L, fadeInMs = 800L, fadeOutMs = 800L
            )
            val right = AudioFade.gainAt(
                500.0 + offset, clipDurationMs = 1_000L, fadeInMs = 800L, fadeOutMs = 800L
            )
            assertEquals("asymmetric at offset $offset", left, right, 0.0001f)
            offset += 25.0
        }
    }

    // --- Degenerate input ---

    @Test
    fun `a window longer than the clip is clamped to the clip`() {
        // 5 s fade on a 0.5 s clip: the ramp must span the clip and finish
        // at its last frame, not stall at 10% gain.
        fun g(ms: Double) =
            AudioFade.gainAt(ms, clipDurationMs = 500L, fadeInMs = 5_000L, fadeOutMs = 0L)

        assertEquals(0f, g(0.0), 0.0001f)
        assertEquals(0.2f, g(100.0), 0.0001f)
        assertEquals(0.5f, g(250.0), 0.0001f)
        assertEquals(1f, g(500.0), 0.0001f)
    }

    @Test
    fun `a zero-length clip yields unity rather than dividing by zero`() {
        assertEquals(
            1f,
            AudioFade.gainAt(0.0, clipDurationMs = 0L, fadeInMs = 400L, fadeOutMs = 400L),
            0.0001f
        )
        assertEquals(
            1f,
            AudioFade.gainAt(100.0, clipDurationMs = -50L, fadeInMs = 400L, fadeOutMs = 400L),
            0.0001f
        )
    }

    @Test
    fun `positions outside the clip are clamped to its bounds`() {
        // Before the clip: still the start of the fade-in (silent).
        assertEquals(
            0f,
            AudioFade.gainAt(-5_000.0, clipDurationMs = 1_000L, fadeInMs = 400L, fadeOutMs = 0L),
            0.0001f
        )
        // After the clip: still the end of the fade-out (silent).
        assertEquals(
            0f,
            AudioFade.gainAt(99_999.0, clipDurationMs = 1_000L, fadeInMs = 0L, fadeOutMs = 400L),
            0.0001f
        )
        // ...but with no fade-out, running past the end is still unity.
        assertEquals(
            1f,
            AudioFade.gainAt(99_999.0, clipDurationMs = 1_000L, fadeInMs = 0L, fadeOutMs = 0L),
            0.0001f
        )
    }

    @Test
    fun `a non-finite position yields unity instead of propagating NaN`() {
        // The export feeds this from a frame counter, but the preview feeds
        // it from a player clock — a NaN there must not silence the clip or
        // poison `exoPlayer.volume`.
        assertEquals(
            1f,
            AudioFade.gainAt(Double.NaN, clipDurationMs = 1_000L, fadeInMs = 400L, fadeOutMs = 400L),
            0.0001f
        )
    }

    @Test
    fun `gain never leaves the 0 to 1 range for any realistic configuration`() {
        val durations = listOf(1L, 500L, 1_000L, 60_000L)
        val fades = listOf(0L, 1L, 250L, 1_000L, 99_999L)
        for (duration in durations) {
            for (fadeIn in fades) {
                for (fadeOut in fades) {
                    var ms = -1_000.0
                    while (ms <= duration + 1_000.0) {
                        val gain = AudioFade.gainAt(ms, duration, fadeIn, fadeOut)
                        assertTrue(
                            "gain $gain out of range at $ms ms " +
                                "(duration=$duration in=$fadeIn out=$fadeOut)",
                            gain in 0f..1f
                        )
                        ms += 37.0
                    }
                }
            }
        }
    }
}
