package com.aicreatorstudio.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/**
 * PHASE 6 — direction-aware progress bar math: continuous fill
 * from the REAL word-timing range, growing from the reading edge
 * (right for RTL when direction-aware, else left).
 *
 * 2026-09-24 — also the pure WAVE accent math behind the «موج رنگی»
 * template: the same bar contract, stroked as a travelling sine.
 */
class CaptionProgressBarTest {

    @Test
    fun `fraction tracks the word window continuously`() {
        // Words span 1000..3000.
        assertEquals(0f, CaptionRenderer.progressFraction(1000L, 3000L, 0L), 0.0001f)
        assertEquals(0f, CaptionRenderer.progressFraction(1000L, 3000L, 1000L), 0.0001f)
        assertEquals(0.5f, CaptionRenderer.progressFraction(1000L, 3000L, 2000L), 0.0001f)
        assertEquals(1f, CaptionRenderer.progressFraction(1000L, 3000L, 3000L), 0.0001f)
        assertEquals(1f, CaptionRenderer.progressFraction(1000L, 3000L, 9999L), 0.0001f)
    }

    @Test
    fun `degenerate ranges snap instead of dividing by zero`() {
        assertEquals(0f, CaptionRenderer.progressFraction(2000L, 2000L, 1000L), 0.0001f)
        assertEquals(1f, CaptionRenderer.progressFraction(2000L, 2000L, 2000L), 0.0001f)
        assertEquals(1f, CaptionRenderer.progressFraction(3000L, 1000L, 5000L), 0.0001f)
    }

    @Test
    fun `fill edge follows script direction only when aware`() {
        // Persian + aware → from the right.
        assertTrue(CaptionRenderer.progressFillRtl(true, true))
        // Persian but unaware → LTR fill.
        assertFalse(CaptionRenderer.progressFillRtl(true, false))
        // Latin → always from the left.
        assertFalse(CaptionRenderer.progressFillRtl(false, true))
        assertFalse(CaptionRenderer.progressFillRtl(false, false))
    }

    // ---- 2026-09-24 WAVE accent (the «موج رنگی» template) ----

    @Test
    fun `wave offset is a sine with a crest and trough per wavelength`() {
        val amp = 10f
        val wl = 100f
        // phase 0 ⇒ a zero crossing at x = 0 ...
        assertEquals(0f, CaptionRenderer.waveOffsetY(0f, amp, wl, 0f), 0.0001f)
        // ... and at every whole wavelength after it (periodicity).
        assertEquals(0f, CaptionRenderer.waveOffsetY(wl, amp, wl, 0f), 0.0001f)
        assertEquals(0f, CaptionRenderer.waveOffsetY(wl * 2f, amp, wl, 0f), 0.0001f)
        // A quarter wavelength in ⇒ the crest.
        assertEquals(amp, CaptionRenderer.waveOffsetY(wl / 4f, amp, wl, 0f), 0.0001f)
        // Three quarters in ⇒ the trough.
        assertEquals(-amp, CaptionRenderer.waveOffsetY(wl * 0.75f, amp, wl, 0f), 0.0001f)
    }

    @Test
    fun `wave never exceeds its amplitude at any x or phase`() {
        // Guards the layout promise: the accent stays inside the
        // band reserved for it (barH), so it can never grow up into
        // the glyphs or off the bottom of the caption box.
        val amp = 7.5f
        val wl = 40f
        var x = 0f
        while (x < wl * 3f) {
            var phase = 0f
            while (phase < 6.5f) {
                val y = CaptionRenderer.waveOffsetY(x, amp, wl, phase)
                assertTrue(
                    "|$y| exceeded amplitude $amp at x=$x phase=$phase",
                    abs(y) <= amp + 0.0001f
                )
                phase += 0.37f
            }
            x += 1.3f
        }
    }

    @Test
    fun `advancing the phase travels the wave instead of jittering it`() {
        // A +90° phase shift must be IDENTICAL to sliding the wave a
        // quarter wavelength along x. That identity is what makes the
        // animation read as travel; if the phase were applied to the
        // polyline instead of the sine it would not hold.
        val amp = 5f
        val wl = 80f
        val x = 13f
        val phaseShifted = CaptionRenderer.waveOffsetY(x, amp, wl, (PI / 2).toFloat())
        val movedAlong = CaptionRenderer.waveOffsetY(x + wl / 4f, amp, wl, 0f)
        assertEquals(movedAlong, phaseShifted, 0.0001f)
    }

    @Test
    fun `wave is continuous across the cycle seam`() {
        // At the end of a cycle the phase approaches 2π, where
        // sin(2π - ε) → 0 — the same value it takes at the start of
        // the next cycle. A wrap that snapped to a non-zero value
        // would show as a visible jump once every 3.6 s.
        val amp = 6f
        val wl = 50f
        val justBefore = CaptionRenderer.wavePhaseAt(CaptionRenderer.WAVE_CYCLE_MS - 1L)
        val atWrap = CaptionRenderer.wavePhaseAt(CaptionRenderer.WAVE_CYCLE_MS)
        assertEquals(0f, atWrap, 0.0001f)
        assertEquals(
            0f,
            CaptionRenderer.waveOffsetY(11f, amp, wl, atWrap) -
                CaptionRenderer.waveOffsetY(11f, amp, wl, justBefore),
            0.05f
        )
    }

    @Test
    fun `wave phase wraps once per cycle and stays inside a single turn`() {
        assertEquals(0f, CaptionRenderer.wavePhaseAt(0L), 0.0001f)
        // Exactly one full cycle later the phase is back to 0.
        assertEquals(0f, CaptionRenderer.wavePhaseAt(CaptionRenderer.WAVE_CYCLE_MS), 0.0001f)
        // Half a cycle ⇒ half a turn.
        assertEquals(
            PI.toFloat(),
            CaptionRenderer.wavePhaseAt(CaptionRenderer.WAVE_CYCLE_MS / 2L),
            0.0001f
        )
        // Never leaves [0, 2π), so the renderer can hand it straight
        // to sin() with no normalisation.
        var t = 0L
        while (t <= CaptionRenderer.WAVE_CYCLE_MS * 3L) {
            val p = CaptionRenderer.wavePhaseAt(t)
            assertTrue("phase $p out of range at t=$t", p >= 0f && p < 2f * PI.toFloat())
            t += 7L
        }
    }

    @Test
    fun `wave phase only ever moves forward inside a cycle`() {
        // One-directional drift; a sign error would make the wave
        // stutter back and forth on alternate frames.
        var previous = -1f
        var t = 0L
        while (t < CaptionRenderer.WAVE_CYCLE_MS) {
            val p = CaptionRenderer.wavePhaseAt(t)
            assertTrue("phase went backwards at t=$t", p > previous)
            previous = p
            t += 50L
        }
    }

    @Test
    fun `degenerate wave inputs collapse to a flat line instead of NaN`() {
        // A zero/negative wavelength or zero amplitude must never
        // reach the Path builder as NaN — a NaN point silently drops
        // the whole path on some GPU drivers.
        assertEquals(0f, CaptionRenderer.waveOffsetY(12f, 8f, 0f, 1.2f), 0.0001f)
        assertEquals(0f, CaptionRenderer.waveOffsetY(12f, 8f, -4f, 1.2f), 0.0001f)
        assertEquals(0f, CaptionRenderer.waveOffsetY(12f, 0f, 40f, 1.2f), 0.0001f)
        assertFalse(CaptionRenderer.waveOffsetY(12f, 8f, 40f, 1.2f).isNaN())
    }
}
