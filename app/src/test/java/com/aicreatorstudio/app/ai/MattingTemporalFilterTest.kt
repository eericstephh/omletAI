package com.aicreatorstudio.app.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [MattingTemporalFilter] — the median-of-three + scene-cut reset
 * that replaced the fixed `TEMPORAL_ALPHA = 0.65` EMA on 2026-10-29.
 *
 * The assertions are about the two properties that matter and that the old
 * EMA could not have:
 *
 *  - a measurement that exists for exactly one frame is **discarded**, not
 *    dimmed;
 *  - a measurement that persists is reproduced **exactly** one frame later,
 *    with no fade and no trail.
 *
 * The filter is pure Kotlin with no Android imports, which is why this can
 * run as a plain JVM test.
 */
class MattingTemporalFilterTest {

    private fun zeros(n: Int) = FloatArray(n) { 0f }

    private fun ones(n: Int) = FloatArray(n) { 1f }

    /** A w×h mask that is foreground from column [fromCol] to the right edge. */
    private fun rightBlock(w: Int, h: Int, fromCol: Int): FloatArray {
        val f = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in fromCol until w) f[y * w + x] = 1f
        }
        return f
    }

    // ---------------------------------------------------------------- basics

    @Test
    fun `first frame passes through unchanged`() {
        val f = MattingTemporalFilter()
        val input = floatArrayOf(0f, 1f, 0f, 1f, 1f)
        val out = f.apply(input, 5, 1)
        assertArrayEquals(input, out, 0f)
        assertEquals(0f, f.lastChangeFraction, 0f)
        assertFalse(f.lastWasSceneCut)
    }

    @Test
    fun `input buffer is never mutated`() {
        val f = MattingTemporalFilter()
        f.apply(zeros(100), 10, 10)
        val second = ones(100)
        val snapshot = second.copyOf()
        f.apply(second, 10, 10)
        assertArrayEquals("apply() must not write into the caller's buffer", snapshot, second, 0f)
    }

    @Test
    fun `soft confidence values are accepted and preserved`() {
        // A future confidence-mask path emits real probabilities instead of
        // 0/1. A constant 0.4 must stay 0.4, not drift.
        val f = MattingTemporalFilter()
        val v = FloatArray(100) { 0.4f }
        f.apply(v, 10, 10)
        val out = f.apply(v, 10, 10)
        for (x in out) assertEquals(0.4f, x, 1e-6f)
    }

    // ------------------------------------------------ isolated-change kill

    @Test
    fun `a single-frame false positive is rejected entirely`() {
        val f = MattingTemporalFilter()
        val w = 10
        val h = 10
        f.apply(zeros(w * h), w, h)

        // Four pixels pop in for exactly one frame.
        val blip = zeros(w * h)
        blip[33] = 1f
        blip[34] = 1f
        blip[43] = 1f
        blip[44] = 1f
        val out = f.apply(blip, w, h)

        for (i in out.indices) {
            assertEquals(
                "pixel $i must be 0: a one-frame blob is a minority of the median",
                0f, out[i], 1e-6f
            )
        }
    }

    @Test
    fun `a single-frame false negative is rejected too`() {
        // The inverse case, which an average handles even worse: the subject
        // is solid, one frame drops a hole. The hole must not appear.
        val f = MattingTemporalFilter()
        val w = 10
        val h = 10
        f.apply(ones(w * h), w, h)

        val holed = ones(w * h)
        holed[33] = 0f
        holed[34] = 0f
        val out = f.apply(holed, w, h)

        assertEquals(1f, out[33], 1e-6f)
        assertEquals(1f, out[34], 1e-6f)
    }

    @Test
    fun `a persistent change is exact on the second frame`() {
        val f = MattingTemporalFilter()
        val w = 10
        val h = 10
        f.apply(zeros(w * h), w, h)

        val blob = zeros(w * h)
        blob[33] = 1f
        blob[34] = 1f
        blob[43] = 1f
        blob[44] = 1f

        val held = f.apply(blob, w, h)
        assertEquals("the first frame of a change is held back", 0f, held[33], 1e-6f)

        // Second frame: the new value is now a majority of three, so it is
        // reproduced exactly — no 0.7 / 0.91 / 0.97 ramp.
        val committed = f.apply(blob, w, h)
        assertArrayEquals("a persistent change must arrive in full", blob, committed, 1e-6f)
    }

    // ------------------------------------------------------------- motion

    @Test
    fun `a moving edge lags exactly one frame and never trails`() {
        val f = MattingTemporalFilter()
        val w = 10
        val h = 10
        f.apply(rightBlock(w, h, 5), w, h)

        // The edge jumps two columns right: 20 % of the pixels change.
        val moved = rightBlock(w, h, 7)
        val lagging = f.apply(moved, w, h)
        assertTrue("change should be 0.2, was ${f.lastChangeFraction}", f.lastChangeFraction > 0.19f)
        assertFalse(f.lastWasSceneCut)

        // One frame of lag on the vacated column — the median needs two
        // agreeing measurements before it drops a value.
        assertEquals(1f, lagging[5], 1e-6f)

        // And then it is exact. This is the assertion the first cut of this
        // class failed: an EMA on the output turned this one-frame lag into
        // a four-frame fade (1.0 → 0.3 → 0.09 …).
        val settled = f.apply(moved, w, h)
        assertArrayEquals("after one frame the mask must be exact", moved, settled, 1e-6f)
    }

    // --------------------------------------------------------- scene cut

    @Test
    fun `a scene cut switches immediately and leaves no ghost`() {
        val f = MattingTemporalFilter()
        val w = 10
        val h = 10
        f.apply(zeros(w * h), w, h)

        // Everything flips at once — a cut, not motion.
        val out = f.apply(ones(w * h), w, h)
        assertTrue("a 100 % change is a scene cut", f.lastWasSceneCut)
        assertEquals(1f, f.lastChangeFraction, 1e-6f)
        for (x in out) assertEquals(1f, x, 1e-6f)

        // The history must be collapsed to the new frame, so the next frame
        // is judged against the new subject: all-zero reads as a cut again,
        // not as a slow fade from 1.
        val back = f.apply(zeros(w * h), w, h)
        assertTrue(f.lastWasSceneCut)
        for (x in back) assertEquals(0f, x, 1e-6f)
    }

    // ------------------------------------------------------- state hygiene

    @Test
    fun `a size change resets the history instead of blending grids`() {
        val f = MattingTemporalFilter()
        f.apply(ones(100), 10, 10)
        val out = f.apply(zeros(16), 4, 4)
        assertArrayEquals(zeros(16), out, 0f)
        assertEquals(0f, f.lastChangeFraction, 0f)
    }

    @Test
    fun `reset drops the history`() {
        val f = MattingTemporalFilter()
        f.apply(ones(100), 10, 10)
        f.apply(ones(100), 10, 10)
        f.reset()
        val out = f.apply(zeros(100), 10, 10)
        assertArrayEquals(zeros(100), out, 0f)
    }

    @Test
    fun `output never leaves the 0 to 1 range`() {
        val f = MattingTemporalFilter()
        val w = 8
        val h = 8
        val patterns = listOf(
            zeros(w * h),
            ones(w * h),
            FloatArray(w * h) { if (it % 3 == 0) 1f else 0f },
            FloatArray(w * h) { (it % 5) / 4f },
            zeros(w * h),
            ones(w * h)
        )
        for (p in patterns) {
            for (x in f.apply(p, w, h)) {
                assertTrue("value out of range: $x", x in 0f..1f)
            }
        }
    }

    @Test
    fun `an impossible scene-cut threshold is refused at construction`() {
        for (bad in listOf(0f, -0.1f, 1.01f)) {
            var threw = false
            try {
                MattingTemporalFilter(sceneCutDiff = bad)
            } catch (e: IllegalArgumentException) {
                threw = true
            }
            assertTrue("expected IllegalArgumentException for $bad", threw)
        }
    }

    @Test
    fun `a non-positive size is refused`() {
        val f = MattingTemporalFilter()
        var threw = false
        try {
            f.apply(FloatArray(0), 0, 0)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("expected IllegalArgumentException", threw)
    }
}
