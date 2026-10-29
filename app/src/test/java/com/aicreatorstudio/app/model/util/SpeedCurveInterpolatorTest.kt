package com.aicreatorstudio.app.model.util

import com.aicreatorstudio.app.model.SpeedCurve
import com.aicreatorstudio.app.model.SpeedCurvePreset
import com.aicreatorstudio.app.model.SpeedPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SpeedCurveInterpolator] — the time-mapping engine
 * behind the Speed Ramping feature.
 *
 * These are the tests that matter most in this feature, because the
 * interpolator is where the three consumers have to agree: the timeline
 * length the editor draws, the PTS Media3 writes into the file, and the
 * inverse map the UI scrubs with. A bug here is invisible in a
 * screenshot and only shows up as a file that is the wrong length, so
 * every property is asserted directly.
 *
 * The interpolator is deliberately free of Android and Media3 imports
 * (see its KDoc), so all of this runs on the JVM with no stubs.
 */
class SpeedCurveInterpolatorTest {

    private fun curveOf(vararg pairs: Pair<Float, Float>): SpeedCurve =
        SpeedCurve(points = pairs.map { SpeedPoint(it.first, it.second) })

    // ---- the integral is a harmonic mean, not an arithmetic one ----

    @Test
    fun `a flat 1x curve leaves the duration untouched`() {
        val interp = SpeedCurveInterpolator(10_000_000L, SpeedCurve.Flat)
        assertEquals(10_000_000L, interp.totalOutputDurationUs)
    }

    @Test
    fun `a flat 2x curve halves the duration`() {
        val interp = SpeedCurveInterpolator(10_000_000L, curveOf(0f to 2f, 1f to 2f))
        assertEquals(5_000_000L, interp.totalOutputDurationUs)
    }

    @Test
    fun `a flat 0_5x curve doubles the duration`() {
        val interp = SpeedCurveInterpolator(10_000_000L, curveOf(0f to 0.5f, 1f to 0.5f))
        assertEquals(20_000_000L, interp.totalOutputDurationUs)
    }

    /**
     * The single most important test in this file, and the one that
     * encodes the design decision documented on the class.
     *
     * A ramp that is half at 0.5x and half at 2x is **not** the same
     * length as the source: `∫dt/v` averages the *reciprocal*, so
     * `0.5·(1/0.5) + 0.5·(1/2) = 1.25`, i.e. 25% longer. Anyone who
     * "simplifies" this to a mean speed breaks the timeline, so the
     * 1.25 factor is pinned here as a regression guard.
     */
    @Test
    fun `a half-slow half-fast ramp is longer than the source`() {
        // A true step needs two points at the same time, which
        // sanitising forbids — so the step is approximated by a very
        // short splice at the midpoint. The staircase then holds each
        // rate for (almost) exactly half the source.
        val interp = SpeedCurveInterpolator(
            10_000_000L,
            curveOf(0f to 0.5f, 0.4999f to 0.5f, 0.5001f to 2f, 1f to 2f)
        )
        // 5s at 0.5x = 10s out; 5s at 2x = 2.5s out; total 12.5s.
        val expected = 12_500_000L
        val actual = interp.totalOutputDurationUs
        // The splice spans 2ms of source, so allow a slice of slack.
        assertTrue(
            "expected ~$expected but was $actual",
            kotlin.math.abs(actual - expected) < 100_000L
        )
        // The point of the test: it is LONGER, not equal.
        assertTrue("harmonic mean must exceed the source here", actual > 10_000_000L)
    }

    @Test
    fun `a symmetric slow-fast ramp is longer than one uniform slow average`() {
        // 1x -> 0.5x -> 1x. Time spent slow dominates because it
        // contributes 1/0.5 = 2 per unit while the fast parts
        // contribute less.
        val interp = SpeedCurveInterpolator(10_000_000L, curveOf(0f to 1f, 0.5f to 0.5f, 1f to 1f))
        assertTrue(
            "a slow dip must lengthen the clip",
            interp.totalOutputDurationUs > 10_000_000L
        )
    }

    @Test
    fun `a symmetric fast bump is shorter than the source`() {
        val interp = SpeedCurveInterpolator(10_000_000L, curveOf(0f to 1f, 0.5f to 2f, 1f to 1f))
        assertTrue(
            "a fast bump must shorten the clip",
            interp.totalOutputDurationUs < 10_000_000L
        )
    }

    // ---- presets are well-formed and behave as described ----

    @Test
    fun `every preset is active and produces a finite duration`() {
        SpeedCurvePreset.entries
            .filter { it != SpeedCurvePreset.CUSTOM }
            .forEach { preset ->
                val curve = SpeedCurve.ofPreset(preset)
                assertTrue("$preset must be usable", curve.isActive)
                val interp = SpeedCurveInterpolator(10_000_000L, curve)
                assertTrue(
                    "$preset produced a non-positive duration",
                    interp.totalOutputDurationUs > 0L
                )
            }
    }

    @Test
    fun `HERO contains both a fast and a slow section`() {
        val speeds = SpeedCurve.ofPreset(SpeedCurvePreset.HERO).points.map { it.speed }
        assertTrue("HERO must have a fast beat", speeds.any { it > 2f })
        assertTrue("HERO must have a slow landing", speeds.any { it < 0.5f })
        assertEquals("HERO must start at normal speed", 1f, speeds.first(), 1e-6f)
        assertEquals("HERO must end at normal speed", 1f, speeds.last(), 1e-6f)
    }

    @Test
    fun `BULLET starts and ends at normal with a sustained slow middle`() {
        val pts = SpeedCurve.ofPreset(SpeedCurvePreset.BULLET).points
        assertEquals(1f, pts.first().speed, 1e-6f)
        assertEquals(1f, pts.last().speed, 1e-6f)
        val slow = pts.filter { it.speed < 0.5f }
        assertTrue("BULLET must have a slow section", slow.isNotEmpty())
        // "Sustained" is the point of the preset: more than one knot.
        assertTrue("BULLET's slow section must be sustained", slow.size >= 2)
    }

    @Test
    fun `FLASH_IN starts fast and settles to normal`() {
        val pts = SpeedCurve.ofPreset(SpeedCurvePreset.FLASH_IN).points
        assertTrue("FLASH_IN must open fast", pts.first().speed > 1f)
        assertEquals(1f, pts.last().speed, 1e-6f)
    }

    @Test
    fun `FLASH_OUT is normal then fast at the tail`() {
        val pts = SpeedCurve.ofPreset(SpeedCurvePreset.FLASH_OUT).points
        assertEquals(1f, pts.first().speed, 1e-6f)
        assertTrue("FLASH_OUT must close fast", pts.last().speed > 1f)
    }

    @Test
    fun `MONTAGE is a single shallow speed-up`() {
        val pts = SpeedCurve.ofPreset(SpeedCurvePreset.MONTAGE).points
        assertEquals(3, pts.size)
        assertTrue("MONTAGE must speed up", pts[1].speed > 1f)
        // Shallow: a montage pass should not look like a ramp.
        assertTrue("MONTAGE must stay gentle", pts[1].speed <= 2f)
    }

    @Test
    fun `CUSTOM preset has no knots and yields the flat curve`() {
        assertEquals(null, SpeedCurvePreset.CUSTOM.points)
        val curve = SpeedCurve.ofPreset(SpeedCurvePreset.CUSTOM)
        assertFalse("CUSTOM must not activate a ramp", curve.isActive)
    }

    @Test
    fun `preset knots are preset in ascending time order`() {
        SpeedCurvePreset.entries
            .filter { it != SpeedCurvePreset.CUSTOM }
            .forEach { preset ->
                val times = preset.points!!.map { it.normalizedTime }
                assertEquals(
                    "$preset's knots must ascend in time",
                    times.sorted(),
                    times
                )
            }
    }

    // ---- the forward map is monotonic ----

    @Test
    fun `source to output is monotonically non-decreasing for a complex ramp`() {
        val interp = SpeedCurveInterpolator(
            10_000_000L,
            SpeedCurve.ofPreset(SpeedCurvePreset.HERO)
        )
        var previous = Long.MIN_VALUE
        var t = 0L
        while (t <= 10_000_000L) {
            val out = interp.sourcePtsToOutputPts(t)
            assertTrue(
                "monotonicity broke at ${t}us: $out < $previous",
                out >= previous
            )
            previous = out
            t += 25_000L
        }
    }

    @Test
    fun `output to source is monotonically non-decreasing for a complex ramp`() {
        val interp = SpeedCurveInterpolator(
            10_000_000L,
            SpeedCurve.ofPreset(SpeedCurvePreset.HERO)
        )
        var previous = Long.MIN_VALUE
        var t = 0L
        val end = interp.totalOutputDurationUs
        while (t <= end) {
            val src = interp.outputPtsToSourcePts(t)
            assertTrue(
                "inverse monotonicity broke at ${t}us: $src < $previous",
                src >= previous
            )
            previous = src
            t += 25_000L
        }
    }

    @Test
    fun `forward map is strictly increasing because no rate reaches zero`() {
        // The inverse is only a function because MIN_SPEED > 0. A knot
        // at 0 would be clamped up to MIN_SPEED rather than collapsing
        // the map, which is what makes the inverse safe.
        val interp = SpeedCurveInterpolator(1_000_000L, curveOf(0f to 0f, 1f to 1f))
        val a = interp.sourcePtsToOutputPts(400_000L)
        val b = interp.sourcePtsToOutputPts(600_000L)
        assertTrue("a zero knot must be clamped, not honoured", b > a)
        assertEquals(SpeedCurve.MIN_SPEED, interp.curve.points.minOf { it.speed }, 1e-6f)
    }

    // ---- clamping at the ends ----

    @Test
    fun `source map clamps before the start and past the end`() {
        val interp = SpeedCurveInterpolator(10_000_000L, curveOf(0f to 2f, 1f to 2f))
        assertEquals(0L, interp.sourcePtsToOutputPts(-5_000_000L))
        assertEquals(0L, interp.sourcePtsToOutputPts(0L))
        assertEquals(interp.totalOutputDurationUs, interp.sourcePtsToOutputPts(10_000_000L))
        assertEquals(interp.totalOutputDurationUs, interp.sourcePtsToOutputPts(99_000_000L))
    }

    @Test
    fun `output map clamps before the start and past the end`() {
        val interp = SpeedCurveInterpolator(10_000_000L, curveOf(0f to 2f, 1f to 2f))
        assertEquals(0L, interp.outputPtsToSourcePts(-1L))
        assertEquals(0L, interp.outputPtsToSourcePts(0L))
        assertEquals(10_000_000L, interp.outputPtsToSourcePts(interp.totalOutputDurationUs))
        assertEquals(10_000_000L, interp.outputPtsToSourcePts(99_000_000L))
    }

    // ---- the two directions are inverses ----

    @Test
    fun `source to output to source round-trips within tolerance`() {
        val interp = SpeedCurveInterpolator(
            10_000_000L,
            SpeedCurve.ofPreset(SpeedCurvePreset.HERO)
        )
        var t = 0L
        while (t <= 10_000_000L) {
            val roundTrip = interp.outputPtsToSourcePts(interp.sourcePtsToOutputPts(t))
            assertTrue(
                "round trip drifted at ${t}us: got $roundTrip",
                SpeedCurveInterpolator.approximatelyEqual(t, roundTrip)
            )
            t += 50_000L
        }
    }

    @Test
    fun `output to source to output round-trips within tolerance`() {
        val interp = SpeedCurveInterpolator(
            10_000_000L,
            SpeedCurve.ofPreset(SpeedCurvePreset.BULLET)
        )
        var t = 0L
        val end = interp.totalOutputDurationUs
        while (t <= end) {
            val roundTrip = interp.sourcePtsToOutputPts(interp.outputPtsToSourcePts(t))
            assertTrue(
                "round trip drifted at ${t}us: got $roundTrip",
                SpeedCurveInterpolator.approximatelyEqual(t, roundTrip)
            )
            t += 50_000L
        }
    }

    @Test
    fun `the endpoints agree with the total duration`() {
        val interp = SpeedCurveInterpolator(
            10_000_000L,
            SpeedCurve.ofPreset(SpeedCurvePreset.MONTAGE)
        )
        assertEquals(
            interp.totalOutputDurationUs,
            interp.sourcePtsToOutputPts(10_000_000L)
        )
        assertEquals(
            10_000_000L,
            interp.outputPtsToSourcePts(interp.totalOutputDurationUs)
        )
    }

    // ---- the staircase Media3 consumes ----

    @Test
    fun `the speed grid starts at zero and ascends`() {
        val interp = SpeedCurveInterpolator(
            10_000_000L,
            SpeedCurve.ofPreset(SpeedCurvePreset.HERO)
        )
        val grid = interp.speedChangeGridUs()
        assertEquals(interp.sliceCount, grid.size)
        assertEquals(0L, grid.first())
        assertEquals(grid.sorted(), grid)
    }

    @Test
    fun `every slice speed is finite and above the minimum`() {
        val interp = SpeedCurveInterpolator(
            10_000_000L,
            SpeedCurve.ofPreset(SpeedCurvePreset.HERO)
        )
        for (i in 0 until interp.sliceCount) {
            val v = interp.speedForSlice(i)
            assertTrue("slice $i speed must be finite", v.isFinite())
            assertTrue("slice $i speed must be positive", v > 0f)
            assertTrue("slice $i speed must be >= MIN", v >= SpeedCurve.MIN_SPEED - 1e-6f)
            assertTrue("slice $i speed must be <= MAX", v <= SpeedCurve.MAX_SPEED + 1e-6f)
        }
    }

    @Test
    fun `an out of range slice index is clamped rather than throwing`() {
        val interp = SpeedCurveInterpolator(1_000_000L, SpeedCurve.ofPreset(SpeedCurvePreset.HERO))
        assertEquals(interp.speedForSlice(0), interp.speedForSlice(-7))
        assertEquals(
            interp.speedForSlice(interp.sliceCount - 1),
            interp.speedForSlice(interp.sliceCount + 99)
        )
    }

    /**
     * The duration predicted here must equal what Media3's own
     * `SpeedProviderUtil.getDurationAfterSpeedProviderApplied` would
     * compute from the same provider — that is the whole reason the
     * engine reproduces the staircase instead of integrating the smooth
     * curve. This test integrates the *grid* here exactly the way
     * `SpeedProviderUtil` does, so a divergence between the two shows
     * up as a failure rather than as a file of the wrong length.
     */
    @Test
    fun `total duration matches a piecewise-constant integration of the grid`() {
        val sourceUs = 10_000_000L
        val interp = SpeedCurveInterpolator(
            sourceUs,
            SpeedCurve.ofPreset(SpeedCurvePreset.HERO)
        )
        val grid = interp.speedChangeGridUs()
        var acc = 0.0
        for (i in 0 until interp.sliceCount) {
            val next = if (i + 1 < interp.sliceCount) grid[i + 1] else sourceUs
            acc += (next - grid[i]).toDouble() / interp.speedForSlice(i).toDouble()
        }
        val media3Style = Math.round(acc)
        assertTrue(
            "predicted ${interp.totalOutputDurationUs} vs Media3-style $media3Style",
            kotlin.math.abs(interp.totalOutputDurationUs - media3Style) <= 2L
        )
    }

    // ---- reading speeds back out ----

    @Test
    fun `speedAtFraction holds the end knots outside their range`() {
        val curve = curveOf(0.25f to 2f, 0.75f to 0.5f)
        assertEquals(2f, curve.speedAt(0f), 1e-6f)
        assertEquals(2f, curve.speedAt(0.25f), 1e-6f)
        assertEquals(0.5f, curve.speedAt(1f), 1e-6f)
    }

    @Test
    fun `speedAtFraction interpolates linearly between knots`() {
        val curve = curveOf(0f to 1f, 1f to 3f)
        assertEquals(2f, curve.speedAt(0.5f), 1e-6f)
        assertEquals(1.5f, curve.speedAt(0.25f), 1e-6f)
    }

    @Test
    fun `speedAtFraction on an empty curve reads as normal speed`() {
        assertEquals(1f, SpeedCurve(points = emptyList()).speedAt(0.5f), 1e-6f)
    }

    @Test
    fun `speedAtOutputUs agrees with the inverse mapping`() {
        val interp = SpeedCurveInterpolator(
            10_000_000L,
            SpeedCurve.ofPreset(SpeedCurvePreset.HERO)
        )
        val outputUs = interp.totalOutputDurationUs / 3
        val expected = interp.speedAtSourceUs(interp.outputPtsToSourcePts(outputUs))
        assertEquals(expected, interp.speedAtOutputUs(outputUs), 1e-6f)
    }

    // ---- sanitising, so the UI and a stale blob cannot break the maths ----

    @Test
    fun `unsorted knots are sorted`() {
        val curve = curveOf(0.9f to 2f, 0.1f to 0.5f, 0.5f to 1f).sanitized()
        val times = curve.points.map { it.normalizedTime }
        assertEquals(listOf(0.1f, 0.5f, 0.9f), times)
    }

    @Test
    fun `duplicate timestamps collapse instead of dividing by zero`() {
        val curve = curveOf(0.5f to 1f, 0.5f to 3f, 1f to 1f).sanitized()
        val times = curve.points.map { it.normalizedTime }
        assertEquals(times.distinct(), times)
        // The later knot wins, matching "the user's latest drag".
        assertEquals(3f, curve.points.first { it.normalizedTime == 0.5f }.speed, 1e-6f)
    }

    @Test
    fun `knots outside zero to one are clamped`() {
        val curve = curveOf(-4f to 1f, 5f to 1f).sanitized()
        assertTrue(curve.points.all { it.normalizedTime in 0f..1f })
    }

    @Test
    fun `non-finite speeds fall back to normal instead of poisoning the integral`() {
        val curve = curveOf(0f to Float.NaN, 0.5f to Float.POSITIVE_INFINITY, 1f to 1f).sanitized()
        assertTrue(curve.points.all { it.speed.isFinite() })
        // Both NaN and the infinities take the "not finite" branch, so
        // both land on 1f rather than being clamped to MAX_SPEED. That
        // is deliberate: `isFinite()` is false for an infinity, and
        // silently turning a corrupt value into the *fastest legal rate*
        // would be the more surprising repair. Neutral is the safe
        // reading of "this number means nothing".
        assertEquals(1f, curve.points.first().speed, 1e-6f)
        assertEquals(1f, curve.points[1].speed, 1e-6f)
        assertEquals(10f, SpeedCurve.MAX_SPEED, 1e-6f)
    }

    @Test
    fun `a finite out-of-range speed is clamped rather than neutralised`() {
        // The complement of the test above: a *real* number outside the
        // bounds is a value the user meant, so it is clamped to the
        // legal range instead of being discarded.
        val curve = curveOf(0f to 250f, 0.5f to 0.0001f, 1f to 1f).sanitized()
        assertEquals(SpeedCurve.MAX_SPEED, curve.points.first().speed, 1e-6f)
        assertEquals(SpeedCurve.MIN_SPEED, curve.points[1].speed, 1e-6f)
    }

    @Test
    fun `sanitising is idempotent`() {
        val once = curveOf(0.8f to 200f, 0.2f to -3f).sanitized()
        assertEquals(once, once.sanitized())
    }

    // ---- activation, the backward-compatibility gate ----

    @Test
    fun `a curve of all ones is inactive so the constant path is untouched`() {
        val flatFive = SpeedCurve(
            points = listOf(
                SpeedPoint(0f, 1f), SpeedPoint(0.25f, 1f), SpeedPoint(0.5f, 1f),
                SpeedPoint(0.75f, 1f), SpeedPoint(1f, 1f)
            )
        )
        assertFalse("a flat editor curve must not activate", flatFive.isActive)
        assertEquals(
            "an inactive curve must not resize the clip",
            10_000_000L,
            SpeedCurveInterpolator.outputDurationUs(10_000_000L, flatFive)
        )
    }

    @Test
    fun `a single knot is not enough to be a ramp`() {
        assertFalse(SpeedCurve(points = listOf(SpeedPoint(0.5f, 4f))).isActive)
    }

    @Test
    fun `a null curve leaves the duration alone`() {
        assertEquals(
            7_000_000L,
            SpeedCurveInterpolator.outputDurationUs(7_000_000L, null)
        )
    }

    @Test
    fun `a zero duration is passed through rather than throwing`() {
        assertEquals(0L, SpeedCurveInterpolator.outputDurationUs(0L, SpeedCurve.ofPreset(SpeedCurvePreset.HERO)))
    }

    @Test
    fun `constructing with a non-positive duration is rejected loudly`() {
        var threw = false
        try {
            SpeedCurveInterpolator(0L, SpeedCurve.ofPreset(SpeedCurvePreset.HERO))
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("a degenerate source must fail fast, not mis-map frames", threw)
    }

    // ---- average speed helper, used by the preview chip ----

    @Test
    fun `average speed is the reciprocal of the duration ratio`() {
        val curve = curveOf(0f to 2f, 1f to 2f)
        assertEquals(2f, SpeedCurveInterpolator.averageSpeed(10_000_000L, curve), 1e-3f)
    }

    @Test
    fun `average speed of an inactive curve is exactly one`() {
        assertEquals(
            1f,
            SpeedCurveInterpolator.averageSpeed(10_000_000L, SpeedCurve.Flat),
            0f
        )
        assertEquals(1f, SpeedCurveInterpolator.averageSpeed(10_000_000L, null), 0f)
    }

    @Test
    fun `average speed is not the arithmetic mean of the knots`() {
        // 0.5x then 2x: the mean of the knots is 1.25 but the true
        // effective rate is 10/12.5 = 0.8. Asserting the *wrong* answer
        // is absent protects against someone "fixing" the helper.
        val interp = SpeedCurveInterpolator(
            10_000_000L,
            curveOf(0f to 0.5f, 0.4999f to 0.5f, 0.5001f to 2f, 1f to 2f)
        )
        val avg = SpeedCurveInterpolator.averageSpeed(10_000_000L, interp.curve)
        assertNotEquals("the arithmetic mean would be 1.25", 1.25f, avg, 1e-2f)
        assertEquals("the true effective rate is 0.8x", 0.8f, avg, 1e-2f)
    }

    // ---- splitting a clip that carries a ramp ----

    @Test
    fun `splitting a ramp keeps both halves continuous and monotonic in time`() {
        val (left, right) = SpeedCurveInterpolator.split(
            SpeedCurve.ofPreset(SpeedCurvePreset.HERO),
            0.5f
        )
        // The value at the cut must match on both sides, or a split
        // clip would show a speed jump at the join.
        assertEquals(left.speedAt(1f), right.speedAt(0f), 1e-4f)
    }

    @Test
    fun `split halves are both renormalised to zero to one`() {
        val (left, right) = SpeedCurveInterpolator.split(
            SpeedCurve.ofPreset(SpeedCurvePreset.BULLET),
            0.4f
        )
        listOf(left, right).forEach { half ->
            assertTrue(half.points.all { it.normalizedTime in 0f..1f })
            assertEquals(0f, half.points.first().normalizedTime, 1e-6f)
            assertEquals(1f, half.points.last().normalizedTime, 1e-6f)
        }
    }

    @Test
    fun `splitting an inactive curve yields two flat halves`() {
        val (left, right) = SpeedCurveInterpolator.split(null, 0.5f)
        assertFalse(left.isActive)
        assertFalse(right.isActive)
    }

    @Test
    fun `a degenerate split does not produce an empty curve on both sides`() {
        val (leftAtZero, rightAtZero) = SpeedCurveInterpolator.split(
            SpeedCurve.ofPreset(SpeedCurvePreset.HERO),
            0f
        )
        assertTrue("the whole ramp must survive", leftAtZero.isActive || rightAtZero.isActive)
    }

    // ---- the shipped presets predict sensible durations ----

    @Test
    fun `shipped presets predict durations within a sane band`() {
        SpeedCurvePreset.entries
            .filter { it != SpeedCurvePreset.CUSTOM }
            .forEach { preset ->
                val out = SpeedCurveInterpolator.outputDurationUs(
                    10_000_000L,
                    SpeedCurve.ofPreset(preset)
                )
                assertTrue(
                    "$preset predicted an absurd duration of ${out}us",
                    out in 1_000_000L..60_000_000L
                )
            }
    }

    @Test
    fun `BULLET lengthens a clip because it dwells in slow motion`() {
        val out = SpeedCurveInterpolator.outputDurationUs(
            10_000_000L,
            SpeedCurve.ofPreset(SpeedCurvePreset.BULLET)
        )
        assertTrue("BULLET must be longer than the source, was $out", out > 10_000_000L)
    }
}
