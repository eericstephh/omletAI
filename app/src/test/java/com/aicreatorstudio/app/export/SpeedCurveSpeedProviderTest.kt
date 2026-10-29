package com.aicreatorstudio.app.export

import com.aicreatorstudio.app.model.SpeedCurve
import com.aicreatorstudio.app.model.SpeedCurvePreset
import com.aicreatorstudio.app.model.util.SpeedCurveInterpolator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SpeedCurveSpeedProvider] — the adapter between the app's
 * ramp model and Media3's `SpeedProvider` contract.
 *
 * ## Why this file exists separately from the interpolator tests
 *
 * The interpolator is app code and can be tested against itself. This
 * adapter has to agree with a **third party's** semantics, and getting
 * those wrong is bug class 3 in this project's notes ("a third-party
 * value shape assumed, not verified"). The two contract details that
 * matter, both confirmed by reading Media3 1.5.0's own sources:
 *
 * 1. `SpeedProviderUtil.getDurationAfterSpeedProviderApplied` computes
 *    the output duration as `Σ Δt / getSpeed(t)` walking
 *    `getNextSpeedChangeTimeUs`. A provider whose `getSpeed` varies
 *    *between* the boundaries it reports would make that sum disagree
 *    with what the shader renders — the audio and video would drift.
 * 2. `SpeedChangeShaderProgram` walks boundaries in a `while (next <=
 *    presentationTimeUs)` loop, so `getNextSpeedChangeTimeUs(t)` must
 *    return a time **strictly greater** than `t`. Returning `t` itself
 *    would spin forever.
 *
 * Both are asserted below rather than assumed.
 */
class SpeedCurveSpeedProviderTest {

    private fun providerFor(
        curve: SpeedCurve,
        sourceDurationUs: Long = 10_000_000L
    ) = SpeedCurveSpeedProvider(SpeedCurveInterpolator(sourceDurationUs, curve))

    // ---- contract detail 1: getSpeed is constant between boundaries ----

    /**
     * Reimplements Media3's own `SpeedProviderUtil` walk and asserts the
     * adapter's duration prediction matches the interpolator's. If
     * `getSpeed` ever changed off-boundary, these two would differ and
     * the export would be a different length from the one the timeline
     * promised.
     */
    @Test
    fun `the provider integrates to the same duration as the interpolator`() {
        val sourceUs = 10_000_000L
        listOf(
            SpeedCurvePreset.HERO,
            SpeedCurvePreset.BULLET,
            SpeedCurvePreset.MONTAGE,
            SpeedCurvePreset.FLASH_IN,
            SpeedCurvePreset.FLASH_OUT
        ).forEach { preset ->
            val interpolator = SpeedCurveInterpolator(sourceUs, SpeedCurve.ofPreset(preset))
            val provider = SpeedCurveSpeedProvider(interpolator)

            // Exactly the Media3 algorithm.
            var acc = 0.0
            var t = 0L
            var guard = 0
            while (t < sourceUs) {
                val next = provider.getNextSpeedChangeTimeUs(t)
                val nextClamped = if (next == androidx.media3.common.C.TIME_UNSET) {
                    Long.MAX_VALUE
                } else {
                    next
                }
                val segmentEnd = minOf(nextClamped, sourceUs)
                acc += (segmentEnd - t).toDouble() / provider.getSpeed(t).toDouble()
                t = segmentEnd
                // A non-advancing walk means getNextSpeedChangeTimeUs
                // returned a non-increasing value — the infinite loop
                // contract detail 2 is about.
                if (guard++ > 10_000) {
                    throw AssertionError("$preset: the boundary walk did not advance")
                }
            }
            assertTrue(
                "$preset: Media3-style $acc vs interpolator ${interpolator.totalOutputDurationUs}",
                kotlin.math.abs(acc - interpolator.totalOutputDurationUs.toDouble()) <= 2.0
            )
        }
    }

    @Test
    fun `getSpeed is constant across an entire slice`() {
        val provider = providerFor(SpeedCurve.ofPreset(SpeedCurvePreset.HERO))
        var t = 0L
        while (t < 10_000_000L) {
            val next = provider.getNextSpeedChangeTimeUs(t)
            if (next == androidx.media3.common.C.TIME_UNSET) break
            val start = provider.getSpeed(t)
            // Sample every microsecond-ish step inside the slice; a
            // provider that varied within a slice would fail here.
            var mid = t
            while (mid < next) {
                assertEquals(
                    "speed changed inside a slice at $mid",
                    start,
                    provider.getSpeed(mid),
                    1e-6f
                )
                mid += (next - t).coerceAtLeast(1L) / 8L + 1L
            }
            t = next
        }
    }

    // ---- contract detail 2: boundaries strictly increase ----

    @Test
    fun `getNextSpeedChangeTimeUs is strictly greater than the query time`() {
        val provider = providerFor(SpeedCurve.ofPreset(SpeedCurvePreset.BULLET))
        // Ask exactly *on* boundaries, which is the case Media3's own
        // KDoc calls out and the case that would loop forever.
        var t = 0L
        var guard = 0
        while (guard++ < 10_000) {
            val next = provider.getNextSpeedChangeTimeUs(t)
            if (next == androidx.media3.common.C.TIME_UNSET) break
            assertTrue(
                "boundary $next must be strictly after $t",
                next > t
            )
            t = next
        }
    }

    @Test
    fun `the last slice reports no further change`() {
        val provider = providerFor(SpeedCurve.ofPreset(SpeedCurvePreset.HERO))
        assertEquals(
            androidx.media3.common.C.TIME_UNSET,
            provider.getNextSpeedChangeTimeUs(10_000_000L)
        )
    }

    @Test
    fun `a query past the end reports no further change`() {
        val provider = providerFor(SpeedCurve.ofPreset(SpeedCurvePreset.HERO))
        assertEquals(
            androidx.media3.common.C.TIME_UNSET,
            provider.getNextSpeedChangeTimeUs(999_000_000L)
        )
    }

    // ---- the value Media3 actually divides by ----

    @Test
    fun `getSpeed is always finite and strictly positive`() {
        // A zero here is not an error Media3 reports — it is a division
        // by zero inside the shader's PTS maths, i.e. corrupt output.
        val provider = providerFor(
            SpeedCurve(
                points = listOf(
                    com.aicreatorstudio.app.model.SpeedPoint(0f, 0f),
                    com.aicreatorstudio.app.model.SpeedPoint(0.5f, -2f),
                    com.aicreatorstudio.app.model.SpeedPoint(1f, 1f)
                )
            )
        )
        var t = 0L
        while (t <= 10_000_000L) {
            val v = provider.getSpeed(t)
            assertTrue("non-finite speed at $t", v.isFinite())
            assertTrue("non-positive speed at $t: $v", v > 0f)
            t += 97_000L
        }
    }

    @Test
    fun `a negative query time is answered, not rejected`() {
        val provider = providerFor(SpeedCurve.ofPreset(SpeedCurvePreset.HERO))
        assertTrue(provider.getSpeed(-1_000L) > 0f)
        // Media3 can ask about a timestamp before the clip starts (a
        // composition whose first item is preceded by a gap does
        // exactly this). There *is* a next change — the first slice
        // boundary at t=0 — so reporting it is right; returning
        // TIME_UNSET would tell Media3 the rate never changes again,
        // which would bake slice-0's rate over the whole clip.
        assertEquals(0L, provider.getNextSpeedChangeTimeUs(-5_000L))
    }

    // ---- a constant ramp must behave exactly like the constant path ----

    /**
     * A curve that is flat at 2x has to produce the same numbers, and
     * therefore the same file, as the constant 2x path. This is the
     * bridge that makes the constant-speed path safe to keep.
     */
    @Test
    fun `a flat two-x ramp matches the constant provider's every value`() {
        val curveProvider = providerFor(
            SpeedCurve(
                points = listOf(
                    com.aicreatorstudio.app.model.SpeedPoint(0f, 2f),
                    com.aicreatorstudio.app.model.SpeedPoint(1f, 2f)
                )
            )
        )
        val constant = ConstantSpeedProviderForTest(2f)
        var t = 0L
        while (t <= 10_000_000L) {
            assertEquals(constant.getSpeed(t), curveProvider.getSpeed(t), 1e-6f)
            t += 250_000L
        }
        // And the duration halves, which is what the constant path does
        // via `(sourceDurationMs / speed)`.
        val interp = SpeedCurveInterpolator(
            10_000_000L,
            SpeedCurve(
                points = listOf(
                    com.aicreatorstudio.app.model.SpeedPoint(0f, 2f),
                    com.aicreatorstudio.app.model.SpeedPoint(1f, 2f)
                )
            )
        )
        assertEquals(5_000_000L, interp.totalOutputDurationUs)
    }

    @Test
    fun `the adapter describes itself for the export log`() {
        val s = providerFor(SpeedCurve.ofPreset(SpeedCurvePreset.HERO)).toString()
        assertNotEquals("", s)
        assertTrue("expected the preset in '$s'", s.contains("HERO"))
    }

    /**
     * Minimal stand-in for the file-private `ConstantSpeedProvider`, so
     * this test can compare against the constant path's semantics
     * without reaching into the engine's private scope.
     */
    private class ConstantSpeedProviderForTest(private val speed: Float) {
        fun getSpeed(timeUs: Long): Float = speed
    }
}
