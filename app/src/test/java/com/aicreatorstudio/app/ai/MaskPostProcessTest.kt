package com.aicreatorstudio.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [MaskPostProcess] — the composition-time mask chain
 * (expansion → feather → reverse) added with the 2026-10-25 CapCut
 * parity work (`docs/CAPCUT_RE_BACKGROUND_REMOVAL_2026-10-25.md` §8).
 *
 * The masks are tiny synthetic arrays: a vertical soft edge and an
 * axis-aligned rectangle. Every pass is O(N) so the reference
 * implementations below can reproduce them by brute force in the
 * assertions.
 */
class MaskPostProcessTest {

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Row-major mask with a hard horizontal split at [edge]: upper = 0, lower = 1. */
    private fun verticalEdgeMask(w: Int, h: Int, edge: Int = h / 3): FloatArray =
        FloatArray(w * h) { i -> if (i / w >= edge) 1f else 0f }

    /** Index helper. */
    private fun FloatArray.at(x: Int, y: Int, w: Int) = this[y * w + x]

    /**
     * Brute-force rectangular max filter (the correctness oracle for
     * the deque implementation).
     */
    private fun bruteMax(src: FloatArray, w: Int, h: Int, r: Int): FloatArray =
        FloatArray(src.size) { i ->
            val cx = i % w
            val cy = i / w
            var best = 0f
            for (y in (cy - r)..(cy + r)) {
                if (y !in 0 until h) continue
                for (x in (cx - r)..(cx + r)) {
                    if (x !in 0 until w) continue
                    best = maxOf(best, src[y * w + x])
                }
            }
            best
        }

    /** Brute-force rectangular min filter. */
    private fun bruteMin(src: FloatArray, w: Int, h: Int, r: Int): FloatArray =
        FloatArray(src.size) { i ->
            val cx = i % w
            val cy = i / w
            var best = 1f
            for (y in (cy - r)..(cy + r)) {
                if (y !in 0 until h) continue
                for (x in (cx - r)..(cx + r)) {
                    if (x !in 0 until w) continue
                    best = minOf(best, src[y * w + x])
                }
            }
            best
        }

    // ------------------------------------------------------------------
    // identity / no-op
    // ------------------------------------------------------------------

    @Test
    fun `zero expansion zero feather no reverse is a no-op`() {
        val w = 16
        val h = 12
        val src = verticalEdgeMask(w, h)
        val expected = src.copyOf()
        val out = MaskPostProcess.apply(src.copyOf(), w, h, 0f, 0f, reverse = false)
        assertTrue(expected.contentEquals(out))
    }

    @Test
    fun `isNoOp flags the identity combo and nothing else`() {
        assertTrue(MaskPostProcess.isNoOp(0f, 0f, false))
        assertFalse(MaskPostProcess.isNoOp(0.5f, 0f, false))
        assertFalse(MaskPostProcess.isNoOp(0f, 0.01f, false))
        assertFalse(MaskPostProcess.isNoOp(0f, 0f, true))
    }

    // ------------------------------------------------------------------
    // dilate
    // ------------------------------------------------------------------

    @Test
    fun `dilate by radius 2 grows the mask exactly like brute force`() {
        val w = 24
        val h = 18
        val src = verticalEdgeMask(w, h)
        val r = 2
        val got = src.copyOf().also { MaskPostProcess.dilate(it, w, h, r) }
        val expected = bruteMax(src, w, h, r)
        for (i in expected.indices) {
            assertEquals("index $i", expected[i], got[i], 1e-6f)
        }
    }

    @Test
    fun `dilate grows the foreground into the background`() {
        val w = 20
        val h = 20
        val edge = 10
        val src = verticalEdgeMask(w, h, edge)
        val got = src.copyOf().also { MaskPostProcess.dilate(it, w, h, 3) }
        // A pixel 2 above the edge (was background) must now be inside.
        assertEquals(1f, got.at(5, edge - 2, w), 1e-6f)
        // Far away it is untouched.
        assertEquals(0f, got.at(5, 0, w), 1e-6f)
        // Deep inside unchanged.
        assertEquals(1f, got.at(5, h - 1, w), 1e-6f)
    }

    // ------------------------------------------------------------------
    // erode (soft, shader formula)
    // ------------------------------------------------------------------

    @Test
    fun `soft erode shrinks and never exceeds brute-force min`() {
        val w = 24
        val h = 18
        val r = 2
        val k = 0.5f * MaskPostProcess.EXPANSION_UI_SCALE
        val src = verticalEdgeMask(w, h)
        val got = src.copyOf().also { MaskPostProcess.softErode(it, w, h, r, k) }
        val minBrute = bruteMin(src, w, h, r)
        val maxBrute = bruteMax(src, w, h, r)
        for (i in got.indices) {
            // s = clamp(min - (max-center)*(k/5)) <= min; pow <= 1.
            assertTrue("index $i", got[i] <= minBrute[i] + 1e-6f)
            val s = (minBrute[i] - (maxBrute[i] - src[i]) * (k / 5f)).coerceIn(0f, 1f)
            assertEquals("index $i", Math.pow(s.toDouble(), (1.0 + k / 4f).toDouble()).toFloat(), got[i], 1e-5f)
        }
    }

    @Test
    fun `soft erode keeps deep background and deep foreground`() {
        val w = 20
        val h = 30
        val edge = 15 // mask: bottom half foreground
        val src = verticalEdgeMask(w, h, edge)
        val got = src.copyOf().also { MaskPostProcess.softErode(it, w, h, 3, 6f) }
        // Deep background (r=3 window fully background): min=max=c=0 → 0^γ = 0.
        assertEquals(0f, got.at(5, edge - 8, w), 1e-6f)
        // Deep foreground: min=max=c=1 → s=1 → 1^γ = 1.
        assertEquals(1f, got.at(5, edge + 8, w), 1e-6f)
        // Just above the edge (was 0, window sees foreground): eroded down, still background.
        assertTrue(got.at(5, edge - 1, w) <= 1e-4f)
        // Just below the edge: pulled below full-white → shrunk edge.
        assertNotEquals(1f, got.at(5, edge, w))
    }

    @Test
    fun `expansion radius mapping reaches a fifth of the short side at full scale`() {
        assertEquals(20, MaskPostProcess.expansionRadiusPx(1f, 400))
        assertEquals(10, MaskPostProcess.expansionRadiusPx(0.5f, 400))
        assertEquals(0, MaskPostProcess.expansionRadiusPx(0f, 400))
        // Resolution independence: same fraction at both sizes.
        assertEquals(
            MaskPostProcess.expansionRadiusPx(0.6f, 200) * 2,
            MaskPostProcess.expansionRadiusPx(0.6f, 400)
        )
    }

    @Test
    fun `feather sigma mapping is linear in fraction and short side`() {
        assertEquals(12f, MaskPostProcess.featherSigmaPx(1f, 720), 1e-4f)
        assertEquals(6f, MaskPostProcess.featherSigmaPx(0.5f, 720), 1e-4f)
        assertEquals(0f, MaskPostProcess.featherSigmaPx(0f, 720), 1e-4f)
    }

    // ------------------------------------------------------------------
    // feather
    // ------------------------------------------------------------------

    @Test
    fun `feather turns a hard edge into a monotone ramp`() {
        // Tall enough that the borders sit far outside the Gaussian
        // tail: edge is 17 rows from the border while sigma is ~3, so
        // a correct clamped blur cannot spill measurably there.
        val w = 60
        val h = 51
        val edge = 17
        val src = verticalEdgeMask(w, h, edge)
        val got = src.copyOf().also {
            MaskPostProcess.gaussianFeather(it, w, h, sigmaPx = 3f)
        }
        // Read a column crossing the edge; the transition must now
        // span several rows (not jump 0→1).
        var rampRows = 0
        for (y in 0 until h) {
            val a = got.at(5, y, w)
            if (a > 1e-3f && a < 1f - 1e-3f) rampRows++
        }
        assertTrue("expected a ramp, got $rampRows partial rows", rampRows >= 3)
        // The edge must land partial and the transition is monotone.
        assertTrue(got.at(5, edge, w) in 0.2f..0.8f)
        var prev = -1f
        for (y in 0 until h) {
            val a = got.at(5, y, w)
            assertTrue("non-monotone at row $y ($prev -> $a)", a >= prev - 1e-5f)
            prev = a
        }
        // Clamped borders: flat far from the edge stays flat (energy
        // conservation, proc_border semantics).
        assertEquals(0f, got.at(5, 0, w), 1e-3f)
        assertEquals(1f, got.at(5, h - 1, w), 1e-3f)
    }

    // ------------------------------------------------------------------
    // reverse
    // ------------------------------------------------------------------

    @Test
    fun `invert flips alpha for every pixel`() {
        val w = 8
        val h = 8
        val src = verticalEdgeMask(w, h)
        val expected = FloatArray(src.size) { 1f - src[it] }
        val got = src.copyOf().also { MaskPostProcess.invert(it) }
        assertTrue(expected.contentEquals(got))
    }

    @Test
    fun `apply with reverse puts the background under the foreground`() {
        val w = 12
        val h = 12
        val src = verticalEdgeMask(w, h, edge = 4) // rows 4..11 foreground
        val got = MaskPostProcess.apply(src.copyOf(), w, h, 0f, 0f, reverse = true)
        assertEquals(1f, got.at(2, 0, w), 1e-6f)  // was background → now kept
        assertEquals(0f, got.at(2, 11, w), 1e-6f) // was foreground → now removed
    }

    // ------------------------------------------------------------------
    // full chain ordering (expand BEFORE feather, reverse LAST)
    // ------------------------------------------------------------------

    @Test
    fun `apply runs expansion before feather so the grown edge is soft`() {
        // Sizes chosen so BOTH stages clear their minimums:
        // shortSide 200 → radius(0.5) = 5px, sigma(0.5) = 1.67px.
        val w = 200
        val h = 200
        val src = verticalEdgeMask(w, h) // split at 66
        val edge = h / 3
        val got = MaskPostProcess.apply(src.copyOf(), w, h, 0.5f, 0.5f, false)
        // Expansion moved the boundary upward to ~edge−5; feather then
        // softened it — so a row between the old and new boundary must
        // be partial alpha, not a binary jump.
        val a = got.at(5, edge - 3, w)
        assertTrue("expected partial alpha at the moved edge, got $a", a > 0.05f && a < 0.95f)
    }
}
