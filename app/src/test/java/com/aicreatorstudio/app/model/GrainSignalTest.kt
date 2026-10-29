package com.aicreatorstudio.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [GrainSignal] — the specification behind the "Detail > Grain"
 * slider.
 *
 * These carry more weight than the other model tests in this package, because
 * grain is the one feature in the project whose implementation is a *fragment
 * shader* and therefore cannot be exercised on the JVM. The shader is a
 * transcription of this object, so these tests pin the maths that the shader has
 * to reproduce — they are the only guard against a shader that quietly does the
 * wrong thing. Anything asserted here must stay true of
 * `assets/shaders/fragment_shader_grain_es2.glsl`.
 *
 * Three properties are load-bearing:
 *
 *  1. **Pure black and pure white receive no grain.** The response curve is a
 *     parabola through `(0, 0)`, `(0.5, 1)`, `(1, 0)`, and it is deliberately
 *     *not* normalised to a mean of 1 — a crushed black has to stay crushed.
 *  2. **The noise is zero-mean.** A hash of exactly `0.5` must produce no
 *     perturbation, or the grain would also shift the exposure.
 *  3. **A non-finite input never reaches the shader.** A NaN uniform blanks the
 *     whole frame, so every entry point collapses one to a harmless value.
 */
class GrainSignalTest {

    private fun assertClose(expected: Float, actual: Float, delta: Float = 1e-5f) {
        assertEquals(expected.toDouble(), actual.toDouble(), delta.toDouble())
    }

    // ------------------------------------------------------------------ active

    @Test
    fun `zero is not active`() {
        assertFalse(GrainSignal.isActive(0f))
    }

    @Test
    fun `a trace amount is not active`() {
        // The slider is continuous; without a floor a value that rounds to
        // nothing on screen would still cost a GL pass.
        assertFalse(GrainSignal.isActive(GrainSignal.EPSILON))
        assertFalse(GrainSignal.isActive(1e-9f))
    }

    @Test
    fun `a real amount is active`() {
        assertTrue(GrainSignal.isActive(0.5f))
        assertTrue(GrainSignal.isActive(1f))
    }

    @Test
    fun `a non-finite amount is not active`() {
        assertFalse(GrainSignal.isActive(Float.NaN))
        assertFalse(GrainSignal.isActive(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `a negative amount is not active`() {
        assertFalse(GrainSignal.isActive(-0.5f))
    }

    // --------------------------------------------------------------- amplitude

    @Test
    fun `amplitude is zero at rest`() {
        assertClose(0f, GrainSignal.amplitude(0f))
    }

    @Test
    fun `amplitude peaks at the declared maximum`() {
        assertClose(GrainSignal.MAX_AMPLITUDE, GrainSignal.amplitude(1f))
    }

    @Test
    fun `amplitude is linear in the slider`() {
        // The panel shows `grain * 100`, so this is what makes "50" mean
        // "half the grain".
        assertClose(GrainSignal.MAX_AMPLITUDE * 0.5f, GrainSignal.amplitude(0.5f))
        assertClose(GrainSignal.MAX_AMPLITUDE * 0.25f, GrainSignal.amplitude(0.25f))
    }

    @Test
    fun `amplitude clamps out-of-range sliders`() {
        assertClose(GrainSignal.MAX_AMPLITUDE, GrainSignal.amplitude(3f))
        assertClose(0f, GrainSignal.amplitude(-1f))
    }

    @Test
    fun `amplitude collapses a non-finite slider to zero`() {
        // A NaN uniform would blank the frame rather than fail loudly.
        assertClose(0f, GrainSignal.amplitude(Float.NaN))
        assertClose(0f, GrainSignal.amplitude(Float.NEGATIVE_INFINITY))
    }

    // -------------------------------------------------------------------- luma

    @Test
    fun `luma weights are Rec 709 and sum to one`() {
        assertClose(1f, GrainSignal.LUMA_R + GrainSignal.LUMA_G + GrainSignal.LUMA_B)
        assertClose(GrainSignal.LUMA_R, GrainSignal.luma(1f, 0f, 0f))
        assertClose(GrainSignal.LUMA_G, GrainSignal.luma(0f, 1f, 0f))
        assertClose(GrainSignal.LUMA_B, GrainSignal.luma(0f, 0f, 1f))
    }

    @Test
    fun `luma of black and white are the ends of the range`() {
        assertClose(0f, GrainSignal.luma(0f, 0f, 0f))
        assertClose(1f, GrainSignal.luma(1f, 1f, 1f))
    }

    // ---------------------------------------------------------------- response

    @Test
    fun `pure black receives no grain`() {
        // Load-bearing: flat noise across the range would lift a crushed black,
        // which is the most obvious tell of fake grain.
        assertClose(0f, GrainSignal.response(0f))
    }

    @Test
    fun `pure white receives no grain`() {
        // The mirror case — grain must not blow out a clipped highlight.
        assertClose(0f, GrainSignal.response(1f))
    }

    @Test
    fun `mid grey receives the full response`() {
        assertClose(1f, GrainSignal.response(0.5f))
    }

    @Test
    fun `the response curve is symmetric about mid grey`() {
        for (l in listOf(0.1f, 0.2f, 0.35f, 0.45f)) {
            assertClose(GrainSignal.response(l), GrainSignal.response(1f - l))
        }
    }

    @Test
    fun `the response rises to mid grey and falls away after`() {
        assertTrue(GrainSignal.response(0.25f) > GrainSignal.response(0.1f))
        assertTrue(GrainSignal.response(0.5f) > GrainSignal.response(0.25f))
        // Past mid grey it falls: 0.75 gives 0.75, 0.9 gives only 0.36.
        assertTrue(GrainSignal.response(0.75f) > GrainSignal.response(0.9f))
    }

    @Test
    fun `the response never goes negative outside the range`() {
        // The parabola is negative past 1, which would invert the grain. Values
        // are clamped rather than extrapolated.
        for (l in listOf(-1f, -0.001f, 1.001f, 4f)) {
            assertTrue("response($l) must be in 0..1", GrainSignal.response(l) in 0f..1f)
        }
    }

    @Test
    fun `a non-finite luma reads as no response`() {
        assertClose(0f, GrainSignal.response(Float.NaN))
        assertClose(0f, GrainSignal.response(Float.POSITIVE_INFINITY))
    }

    // ------------------------------------------------------------------- delta

    @Test
    fun `a neutral hash produces no perturbation`() {
        // Zero-mean noise: 0.5 is the centre of the hash's 0..1 range, so it
        // must move the pixel by exactly nothing. Otherwise the grain would
        // also shift the exposure.
        assertClose(0f, GrainSignal.delta(luma = 0.5f, noise = 0.5f, amplitude = 0.25f))
    }

    @Test
    fun `the extremes of the hash are symmetric`() {
        val up = GrainSignal.delta(luma = 0.5f, noise = 1f, amplitude = 0.25f)
        val down = GrainSignal.delta(luma = 0.5f, noise = 0f, amplitude = 0.25f)
        assertClose(0.125f, up)
        assertClose(-0.125f, down)
        assertClose(0f, up + down)
    }

    @Test
    fun `the delta is scaled by the response curve`() {
        // At mid-grey the response is 1, so the delta is half the amplitude. At
        // a quarter tone it is 0.75 of that.
        assertClose(0.125f, GrainSignal.delta(0.5f, 1f, 0.25f))
        assertClose(0.125f * 0.75f, GrainSignal.delta(0.25f, 1f, 0.25f))
    }

    @Test
    fun `black and white are immune to the hash`() {
        for (noise in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            assertClose(0f, GrainSignal.delta(0f, noise, GrainSignal.MAX_AMPLITUDE))
            assertClose(0f, GrainSignal.delta(1f, noise, GrainSignal.MAX_AMPLITUDE))
        }
    }

    @Test
    fun `a zero or non-finite amplitude produces no delta`() {
        assertClose(0f, GrainSignal.delta(0.5f, 1f, 0f))
        assertClose(0f, GrainSignal.delta(0.5f, 1f, -1f))
        assertClose(0f, GrainSignal.delta(0.5f, 1f, Float.NaN))
    }

    @Test
    fun `a non-finite hash reads as neutral`() {
        assertClose(0f, GrainSignal.delta(0.5f, Float.NaN, 0.25f))
    }

    @Test
    fun `the delta never exceeds half the amplitude`() {
        // The shader clamps after adding, so a larger delta would flatten
        // mid-tones toward the ends of the range.
        for (noise in listOf(0f, 0.1f, 0.9f, 1f)) {
            val d = GrainSignal.delta(0.5f, noise, GrainSignal.MAX_AMPLITUDE)
            assertTrue("delta $d exceeded the bound", kotlin.math.abs(d) <= GrainSignal.MAX_AMPLITUDE * 0.5f + 1e-6f)
        }
    }

    // -------------------------------------------------------------------- seed

    @Test
    fun `the seed is zero at the start`() {
        assertClose(0f, GrainSignal.seedFor(0L))
    }

    @Test
    fun `a negative timestamp reads as zero rather than a negative seed`() {
        assertClose(0f, GrainSignal.seedFor(-1L))
        assertClose(0f, GrainSignal.seedFor(Long.MIN_VALUE))
    }

    @Test
    fun `the seed always lands in the unit interval`() {
        // The shader multiplies this by a few hundred before adding it to
        // `gl_FragCoord`, so it has to stay small enough for the mediump
        // fallback to represent the sum exactly.
        var t = 0L
        while (t < 5_000_000L) {
            val s = GrainSignal.seedFor(t)
            assertTrue("seed $s out of range at t=$t", s >= 0f && s < 1f)
            t += 33_333L
        }
    }

    @Test
    fun `consecutive frames get different seeds`() {
        // Static grain reads as dirt on the lens, so the whole point of the
        // seed is that it moves.
        val a = GrainSignal.seedFor(1_000_000L / 30L)
        val b = GrainSignal.seedFor(2L * 1_000_000L / 30L)
        assertTrue("seeds must differ, both were $a", a != b)
    }

    @Test
    fun `the seed wraps once per second`() {
        // Derived from the timestamp modulo the period, so the same instant in
        // a later second reuses the seed. Harmless — the pattern also depends on
        // the pixel position — and it is what keeps the value in range.
        assertClose(
            GrainSignal.seedFor(250_000L),
            GrainSignal.seedFor(250_000L + GrainSignal.SEED_PERIOD_US)
        )
    }

    @Test
    fun `the seed advances within a second`() {
        assertTrue(GrainSignal.seedFor(500_000L) > GrainSignal.seedFor(100_000L))
    }
}
