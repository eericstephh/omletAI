package com.aicreatorstudio.app.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SharpnessKernel] — the pure definition behind the Color Grading
 * panel's "Detail > Sharpness" slider.
 *
 * The kernel is only correct if Media3's lookup-table sampling reproduces it
 * exactly, and that depends on a pairing property of the raster positions
 * rather than on the shape of the curve. Several tests below assert that
 * property directly; without them the pass could compile, run, and quietly
 * deliver a fraction of the intended weight.
 */
class SharpnessKernelTest {

    // ---------------------------------------------------------------- active

    @Test
    fun `zero strength is not active`() {
        assertFalse(SharpnessKernel.isActive(0f))
    }

    @Test
    fun `positive strength is active`() {
        assertTrue(SharpnessKernel.isActive(0.5f))
        assertTrue(SharpnessKernel.isActive(1f))
    }

    @Test
    fun `a sliver above zero is still active`() {
        assertTrue(SharpnessKernel.isActive(0.001f))
    }

    @Test
    fun `negative strength is not active`() {
        assertFalse(SharpnessKernel.isActive(-0.5f))
        assertFalse(SharpnessKernel.isActive(-1f))
    }

    @Test
    fun `non finite strength is not active`() {
        assertFalse(SharpnessKernel.isActive(Float.NaN))
        assertFalse(SharpnessKernel.isActive(Float.POSITIVE_INFINITY))
        assertFalse(SharpnessKernel.isActive(Float.NEGATIVE_INFINITY))
    }

    // ---------------------------------------------------------------- weight

    @Test
    fun `weight is zero at rest`() {
        assertEquals(0f, SharpnessKernel.weight(0f), 0f)
    }

    @Test
    fun `weight reaches the maximum at full strength`() {
        assertEquals(SharpnessKernel.MAX_WEIGHT, SharpnessKernel.weight(1f), 1e-6f)
    }

    @Test
    fun `weight is linear in strength`() {
        assertEquals(SharpnessKernel.MAX_WEIGHT / 2f, SharpnessKernel.weight(0.5f), 1e-6f)
        assertEquals(SharpnessKernel.MAX_WEIGHT / 4f, SharpnessKernel.weight(0.25f), 1e-6f)
    }

    @Test
    fun `weight clamps outside the slider range`() {
        assertEquals(SharpnessKernel.MAX_WEIGHT, SharpnessKernel.weight(4f), 0f)
        assertEquals(0f, SharpnessKernel.weight(-3f), 0f)
    }

    @Test
    fun `weight collapses non finite input to zero`() {
        assertEquals(0f, SharpnessKernel.weight(Float.NaN), 0f)
        assertEquals(0f, SharpnessKernel.weight(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0f, SharpnessKernel.weight(Float.NEGATIVE_INFINITY), 0f)
    }

    @Test
    fun `weight never exceeds the maximum across the whole slider`() {
        var s = 0f
        while (s <= 1f) {
            val w = SharpnessKernel.weight(s)
            assertTrue("weight $w out of range at strength $s", w in 0f..SharpnessKernel.MAX_WEIGHT)
            s += 0.05f
        }
    }

    // ------------------------------------------------------------------ taps

    @Test
    fun `taps sum to one so mean luminance is preserved`() {
        for (strength in listOf(0f, 0.1f, 0.25f, 0.5f, 0.75f, 1f)) {
            val k = SharpnessKernel.weight(strength)
            assertEquals(
                "taps must sum to 1 at strength $strength",
                1f,
                SharpnessKernel.tapSum(k),
                1e-5f
            )
        }
    }

    @Test
    fun `taps are symmetric around the centre`() {
        for (strength in listOf(0.2f, 0.6f, 1f)) {
            val taps = SharpnessKernel.taps(SharpnessKernel.weight(strength))
            assertEquals(3, taps.size)
            assertEquals("outer taps must match at strength $strength", taps[0], taps[2], 0f)
        }
    }

    @Test
    fun `the centre tap is the heaviest and the neighbours pull negative`() {
        val taps = SharpnessKernel.taps(SharpnessKernel.weight(0.5f))
        assertTrue("centre must dominate", taps[1] > taps[0])
        assertTrue("neighbours must be negative to sharpen", taps[0] < 0f)
        assertTrue("neighbours must be negative to sharpen", taps[2] < 0f)
    }

    @Test
    fun `zero weight is the identity kernel`() {
        val taps = SharpnessKernel.taps(0f)
        assertArrayEquals(floatArrayOf(0f, 1f, 0f), taps, 0f)
    }

    // ----------------------------------------------------------------- value

    @Test
    fun `value is the centre weight near zero`() {
        val k = SharpnessKernel.weight(0.4f)
        assertEquals(1f + 2f * k, SharpnessKernel.value(0f, k), 1e-6f)
        assertEquals(1f + 2f * k, SharpnessKernel.value(0.4f, k), 1e-6f)
        assertEquals(1f + 2f * k, SharpnessKernel.value(-0.4f, k), 1e-6f)
    }

    @Test
    fun `value is the negative neighbour weight in the ring`() {
        val k = SharpnessKernel.weight(0.4f)
        for (p in listOf(-1.4f, -1f, -0.6f, 0.6f, 1f, 1.4f)) {
            assertEquals("position $p", -k, SharpnessKernel.value(p, k), 1e-6f)
        }
    }

    @Test
    fun `value is zero outside the domain`() {
        val k = SharpnessKernel.weight(0.4f)
        for (p in listOf(-3f, -1.6f, 1.6f, 3f, 100f)) {
            assertEquals("position $p", 0f, SharpnessKernel.value(p, k), 0f)
        }
    }

    @Test
    fun `value collapses a non finite position to zero`() {
        val k = SharpnessKernel.weight(0.4f)
        assertEquals(0f, SharpnessKernel.value(Float.NaN, k), 0f)
        assertEquals(0f, SharpnessKernel.value(Float.POSITIVE_INFINITY, k), 0f)
    }

    // -------------------------------------------- the LUT pairing invariant

    @Test
    fun `each sampled pair returns one weight so the LUT interpolation is exact`() {
        // GL_LINEAR averages the two raster samples bracketing each lookup.
        // If a pair disagreed, the tap would come out at the average of the
        // two rather than at the weight the kernel intends.
        for (strength in listOf(0.05f, 0.3f, 0.5f, 0.8f, 1f)) {
            val k = SharpnessKernel.weight(strength)
            for ((a, b) in SharpnessKernel.SAMPLE_POSITIONS) {
                assertEquals(
                    "positions $a and $b disagree at strength $strength, so the tap " +
                        "would be attenuated by the linear interpolation",
                    SharpnessKernel.value(a, k),
                    SharpnessKernel.value(b, k),
                    0f
                )
            }
        }
    }

    @Test
    fun `the sampled positions reconstruct exactly the discrete tap kernel`() {
        val k = SharpnessKernel.weight(1f)
        // What the shader actually accumulates: the shared value of each pair.
        val sampled = SharpnessKernel.SAMPLE_POSITIONS
            .map { (a, b) -> (SharpnessKernel.value(a, k) + SharpnessKernel.value(b, k)) / 2f }
            .toFloatArray()
        // taps(k) plus the throwaway tap one texel past the support.
        assertArrayEquals(SharpnessKernel.taps(k) + 0f, sampled, 1e-6f)
    }

    @Test
    fun `the throwaway tap contributes nothing`() {
        val k = SharpnessKernel.weight(1f)
        val (a, b) = SharpnessKernel.SAMPLE_POSITIONS.last()
        assertEquals(0f, SharpnessKernel.value(a, k), 0f)
        assertEquals(0f, SharpnessKernel.value(b, k), 0f)
    }

    @Test
    fun `the three real taps lie inside the domain`() {
        val real = SharpnessKernel.SAMPLE_POSITIONS.dropLast(1)
        assertEquals(3, real.size)
        for ((a, b) in real) {
            assertTrue(
                "$a is outside [${SharpnessKernel.DOMAIN_START}, ${SharpnessKernel.DOMAIN_END}]",
                a >= SharpnessKernel.DOMAIN_START && a <= SharpnessKernel.DOMAIN_END
            )
            assertTrue(
                "$b is outside [${SharpnessKernel.DOMAIN_START}, ${SharpnessKernel.DOMAIN_END}]",
                b >= SharpnessKernel.DOMAIN_START && b <= SharpnessKernel.DOMAIN_END
            )
        }
    }

    // ---------------------------------------------------------------- domain

    @Test
    fun `the domain is symmetric with width three`() {
        assertEquals(3f, SharpnessKernel.DOMAIN_WIDTH, 0f)
        assertEquals(-SharpnessKernel.DOMAIN_START, SharpnessKernel.DOMAIN_END, 0f)
    }

    // -------------------------------------------------------------- behaviour

    @Test
    fun `convolving a flat signal is a no op`() {
        val flat = FloatArray(9) { 0.42f }
        for (strength in listOf(0f, 0.25f, 0.5f, 1f)) {
            val out = convolve(flat, SharpnessKernel.taps(SharpnessKernel.weight(strength)))
            out.forEachIndexed { i, v ->
                assertEquals("sample $i at strength $strength", 0.42f, v, 1e-5f)
            }
        }
    }

    @Test
    fun `convolving a step overshoots and undershoots`() {
        // The signature of sharpening: the edge gains ringing, so the output
        // leaves the input's range. A blur (all-positive weights) never can.
        val step = floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f)
        val out = convolve(step, SharpnessKernel.taps(SharpnessKernel.weight(0.5f)))
        assertTrue("must overshoot above the step, got max ${out.max()}", out.max() > 1f)
        assertTrue("must undershoot below the step, got min ${out.min()}", out.min() < 0f)
    }

    @Test
    fun `a stronger weight sharpens harder`() {
        val step = floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f)
        val weak = convolve(step, SharpnessKernel.taps(SharpnessKernel.weight(0.2f))).max()
        val strong = convolve(step, SharpnessKernel.taps(SharpnessKernel.weight(1f))).max()
        assertTrue("stronger must overshoot further ($weak vs $strong)", strong > weak)
    }

    // ----------------------------------------------------------------- helper

    /**
     * 1-D convolution with the signal clamped at the edges. This mirrors what
     * the shader does at a border: it runs out of taps, so it accumulates
     * fewer samples and the shader program divides by the reduced total.
     */
    private fun convolve(signal: FloatArray, taps: FloatArray): FloatArray {
        val half = taps.size / 2
        return FloatArray(signal.size) { i ->
            var acc = 0f
            for (t in taps.indices) {
                val idx = (i + t - half).coerceIn(0, signal.size - 1)
                acc += taps[t] * signal[idx]
            }
            acc
        }
    }
}
