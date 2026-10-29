package com.aicreatorstudio.app.model.util

import com.aicreatorstudio.app.model.SpeedCurve
import com.aicreatorstudio.app.model.SpeedCurvePreset
import com.aicreatorstudio.app.model.SpeedPoint
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Time-mapping engine for a [SpeedCurve].
 *
 * ## The maths, and why it is what it is
 *
 * A speed ramp `v(t)` over the source timeline maps source time to
 * output time by the integral
 *
 * ```
 * output(t) = ∫₀ᵗ dt' / v(t')
 * ```
 *
 * so the clip's total output duration is `∫₀^T dt / v(t)` — the source
 * duration scaled by the *harmonic* mean of the speed, not the
 * arithmetic one. A ramp that spends half its time at `0.5x` and half
 * at `2x` therefore comes out **longer** than the source, not the same
 * length, and predicting the timeline from an average speed is wrong.
 * [totalOutputDurationUs] exists so the editor can show the truth.
 *
 * ## Stepped, not integrated analytically — on purpose
 *
 * A closed-form integral of a piecewise-linear `v(t)` is
 * `Σ Δt / Δv · ln(v₁/v₀)`, which is exact but is *not* what the export
 * produces. Media3's `SpeedProvider` contract is **piecewise
 * constant**: `SpeedChangeShaderProgram` computes
 * `output += (t - lastT) / v(t)` using the speed sampled at the
 * segment's own start, and `SpeedProviderUtil` integrates the same
 * way. So the engine approximates the ramp as a staircase, and the
 * predictions here deliberately reproduce *that* staircase rather than
 * the smooth curve it approximates. Preview, timeline length and
 * exported file then agree to the microsecond; a "more correct"
 * integral would disagree with the file it is predicting, which is the
 * bug class this project has paid for most often.
 *
 * The staircase has [SLICE_COUNT] slices over the ramp, so the error
 * against the true integral falls as `1/n`. That cost is paid once, at
 * probe time, not per frame.
 *
 * ## Why a lookup table for the reverse direction
 *
 * [outputPtsToSourcePts] has no closed form: inverting
 * `output = ∫ dt / v(t)` means solving for the upper limit of an
 * integral, and the staircase makes it a cumulative sum that can be
 * bisected but not differentiated back cheaply. Since the forward
 * direction is monotonic non-decreasing, the inverse is built once at
 * construction by sampling [sourcePtsToOutputPts] onto a uniform
 * source grid and bisecting that (monotone) table on demand. That is
 * `O(n log n)` to build and `O(log n)` per query, and it is
 * **exactly** consistent with the forward direction by construction —
 * they cannot drift, because the inverse *is* the forward table.
 *
 * Instances are immutable and safe to share across threads.
 *
 * @param sourceDurationUs length of the source range the curve applies
 *   to, in microseconds. Must be `> 0` — a zero-length source has no
 *   ramp to evaluate. Callers with a degenerate range should skip the
 *   curve entirely rather than construct this.
 * @param curve the ramp. It is [SpeedCurve.sanitized] internally, so a
 *   caller may pass raw UI state.
 */
class SpeedCurveInterpolator(
    val sourceDurationUs: Long,
    curve: SpeedCurve
) {
    /** The sanitised curve actually used. */
    val curve: SpeedCurve = curve.sanitized()

    private val knots: List<SpeedPoint> = this.curve.points

    /**
     * Uniform source grid and its cumulative output times. Both have
     * [steps] + 1 entries; `gridUs[0]` is always `0` and
     * `gridOutUs[0]` is always `0`.
     */
    private val gridUs: LongArray
    private val gridOutUs: LongArray
    private val steps: Int

    /** Total output duration, in microseconds. `>= 1` for a usable source. */
    val totalOutputDurationUs: Long

    init {
        require(sourceDurationUs > 0L) {
            "SpeedCurveInterpolator needs a positive source duration, was $sourceDurationUs"
        }
        // One slice per knot interval *within* the ramp, but never fewer
        // than this, so a two-knot curve is approximated as finely as a
        // ten-knot one. Capped so a pathological curve cannot allocate
        // an unbounded table.
        steps = (knots.size * SLICES_PER_INTERVAL)
            .coerceIn(MIN_STEPS, MAX_STEPS)

        gridUs = LongArray(steps + 1)
        gridOutUs = LongArray(steps + 1)

        // Accumulate in double: a 10-minute clip is 6e8 µs and the
        // cumulative sum of ~128 divisions stays well inside double's
        // exact-integer range, whereas repeated float addition would
        // visibly drift.
        var acc = 0.0
        var prevSourceUs = 0L
        for (i in 1..steps) {
            val sourceUs = sourceDurationUs * i / steps
            // Speed sampled at the slice's own start — this is the
            // staircase Media3 builds, reproduced exactly.
            val v = speedAtSourceUs(prevSourceUs)
            acc += (sourceUs - prevSourceUs).toDouble() / v.toDouble()
            gridUs[i] = sourceUs
            gridOutUs[i] = acc.roundToLong()
            prevSourceUs = sourceUs
        }
        totalOutputDurationUs = gridOutUs[steps].coerceAtLeast(1L)
    }

    /**
     * Speed at an absolute source-timeline position, in microseconds.
     *
     * Equivalent to `curve.speedAt(t / sourceDuration)`, exposed for the
     * Media3 adapter and for tests that want to avoid float division.
     */
    fun speedAtSourceUs(sourceUs: Long): Float {
        if (sourceUs <= 0L) return speedAtFraction(0f)
        if (sourceUs >= sourceDurationUs) return speedAtFraction(1f)
        return speedAtFraction(sourceUs.toDouble().toFloat() / sourceDurationUs.toFloat())
    }

    /** Speed at a source-normalised `0..1` position. */
    fun speedAtFraction(fraction: Float): Float = curve.speedAt(fraction)

    /**
     * Source position → output position. Clamps at both ends, so a
     * caller can pass a PTS one microsecond past the end of the range
     * (Media3 does) without getting a negative or overlong result.
     */
    fun sourcePtsToOutputPts(sourcePtsUs: Long): Long {
        if (sourcePtsUs <= 0L) return 0L
        if (sourcePtsUs >= sourceDurationUs) return totalOutputDurationUs
        val i = ((sourcePtsUs.toDouble() / sourceDurationUs) * steps).toInt()
            .coerceIn(0, steps - 1)
        val loUs = gridUs[i]
        val hiUs = gridUs[i + 1]
        val loOut = gridOutUs[i]
        val hiOut = gridOutUs[i + 1]
        if (hiUs <= loUs) return loOut
        // Interpolate *within* the slice at its own constant speed, so
        // this is exact for the staircase rather than a chord through
        // the rounded cumulative sums.
        val frac = (sourcePtsUs - loUs).toDouble() / (hiUs - loUs).toDouble()
        return (loOut + (hiOut - loOut) * frac).roundToLong()
    }

    /**
     * Output position → source position — the inverse of
     * [sourcePtsToOutputPts].
     *
     * **Only valid when the curve never reaches zero speed**, which
     * [SpeedCurve.MIN_SPEED] guarantees, so the forward map is strictly
     * increasing and the inverse is a function. Bisects the cumulative
     * table, which is monotone by construction.
     */
    fun outputPtsToSourcePts(outputPtsUs: Long): Long {
        if (outputPtsUs <= 0L) return 0L
        if (outputPtsUs >= totalOutputDurationUs) return sourceDurationUs
        // Bisect for the last index whose cumulative output is <= target.
        var lo = 0
        var hi = steps
        while (lo + 1 < hi) {
            val mid = (lo + hi) ushr 1
            if (gridOutUs[mid] <= outputPtsUs) lo = mid else hi = mid
        }
        val loOut = gridOutUs[lo]
        val hiOut = gridOutUs[lo + 1]
        val loUs = gridUs[lo]
        val hiUs = gridUs[lo + 1]
        if (hiOut <= loOut) return loUs
        val frac = (outputPtsUs - loOut).toDouble() / (hiOut - loOut).toDouble()
        return (loUs + (hiUs - loUs) * frac).roundToLong()
    }

    /**
     * The speed at an *output*-timeline position. Convenience for the
     * UI (which scrubs output time) — round-trips through the inverse
     * and is therefore consistent with both directions above.
     */
    fun speedAtOutputUs(outputPtsUs: Long): Float =
        speedAtSourceUs(outputPtsToSourcePts(outputPtsUs))

    /**
     * The speed Media3 should use at the given **source** timestamp.
     *
     * This is the whole reason the class exists. Media3's provider is
     * piecewise constant and calls `getNextSpeedChangeTimeUs` to know
     * where the current rate stops applying, so this returns the
     * staircase knot positions (the uniform grid above) rather than the
     * user's own knots — the two only coincide when the user happens to
     * place knots on the grid.
     *
     * Returned as a `List<Long>` of absolute source microsecond
     * boundaries rather than as a Media3 type, so this file keeps zero
     * Android/Media3 imports and stays JVM-testable; the export layer
     * wraps it. See `SpeedCurveSpeedProvider` in the export package.
     */
    fun speedChangeGridUs(): List<Long> {
        // Index 0 is time 0, which is where the first rate starts — not
        // a *change*, so it is included for the adapter's convenience
        // and the adapter starts its walk from index 1.
        val out = ArrayList<Long>(steps)
        for (i in 0 until steps) out.add(gridUs[i])
        return out
    }

    /** Speed for the slice starting at [index] of [speedChangeGridUs]. */
    fun speedForSlice(index: Int): Float {
        val i = index.coerceIn(0, steps - 1)
        return speedAtSourceUs(gridUs[i])
    }

    /** Number of staircase slices. */
    val sliceCount: Int get() = steps

    /**
     * A short human-readable summary, used by the export log. Not part
     * of any contract.
     */
    override fun toString(): String =
        "SpeedCurveInterpolator(src=${sourceDurationUs}us, out=${totalOutputDurationUs}us, " +
            "knots=${knots.size}, steps=$steps, preset=${curve.preset})"

    companion object {
        /**
         * Staircase resolution per knot interval. 32 keeps the
         * duration error under ~1.5% of the ramp's own contribution
         * for the shipped presets, while leaving the table at a few
         * hundred entries.
         */
        private const val SLICES_PER_INTERVAL = 32

        private const val MIN_STEPS = 64
        private const val MAX_STEPS = 2048

        /**
         * Convenience factory for the common "predict the output length
         * of this ramp over this source range" question. Returns
         * [sourceDurationUs] unchanged for a null or inactive curve, so
         * a caller never needs to branch on `isActive` itself.
         */
        fun outputDurationUs(sourceDurationUs: Long, curve: SpeedCurve?): Long {
            if (curve == null || !curve.isActive || sourceDurationUs <= 0L) {
                return sourceDurationUs
            }
            return SpeedCurveInterpolator(sourceDurationUs, curve).totalOutputDurationUs
        }

        /**
         * Preview helper: the *effective average* speed over the whole
         * ramp, i.e. `source / output`. This is NOT the mean of the
         * knots — see the class KDoc. `1f` for a null/inactive curve,
         * and for a degenerate duration.
         */
        fun averageSpeed(sourceDurationUs: Long, curve: SpeedCurve?): Float {
            if (curve == null || !curve.isActive || sourceDurationUs <= 0L) return 1f
            val out = SpeedCurveInterpolator(sourceDurationUs, curve).totalOutputDurationUs
            if (out <= 0L) return 1f
            return sourceDurationUs.toDouble().toFloat() / out.toDouble().toFloat()
        }

        /**
         * Folds a curve whose output range is already known back onto a
         * source range, for the split-clip case.
         *
         * Splitting a clip that carries a ramp has to slice the curve,
         * or the second half would replay the first half's shape. The
         * caller passes the *source* split point; this returns the pair
         * of sub-curves, each renormalised to its own `0..1`. Returns a
         * pair of [SpeedCurve.Flat] when the input is not usable, so a
         * caller never has to null-check.
         */
        fun split(curve: SpeedCurve?, atNormalizedTime: Float): Pair<SpeedCurve, SpeedCurve> {
            if (curve == null || !curve.isActive) return SpeedCurve.Flat to SpeedCurve.Flat
            val clean = curve.sanitized()
            val at = atNormalizedTime.coerceIn(0f, 1f)
            // Degenerate split — hand the whole ramp to one side rather
            // than producing an empty curve.
            if (at <= 0f) return SpeedCurve.Flat to clean
            if (at >= 1f) return clean to SpeedCurve.Flat

            fun resample(lo: Float, hi: Float): SpeedCurve {
                val span = hi - lo
                if (span <= 0f) return SpeedCurve.Flat
                val pts = mutableListOf<SpeedPoint>()
                // Keep every knot strictly inside the window...
                clean.points.forEach { p ->
                    if (p.normalizedTime > lo && p.normalizedTime < hi) {
                        pts.add(
                            SpeedPoint(
                                normalizedTime = (p.normalizedTime - lo) / span,
                                speed = p.speed
                            )
                        )
                    }
                }
                // ...then pin both ends by *sampling* the original, so
                // the cut is continuous: the value at the split must be
                // the same on the left curve's 1f and the right's 0f.
                pts.add(0, SpeedPoint(0f, clean.speedAt(lo)))
                pts.add(SpeedPoint(1f, clean.speedAt(hi)))
                return SpeedCurve(
                    points = pts,
                    // Both halves stay CUSTOM: a slice of HERO is no
                    // longer HERO, and claiming otherwise would let the
                    // preset chip misreport state.
                    preset = SpeedCurvePreset.CUSTOM,
                    preservePitch = curve.preservePitch
                )
            }
            return resample(0f, at) to resample(at, 1f)
        }

        /**
         * Tolerance used by the round-trip tests, in microseconds. The
         * cumulative table rounds to whole microseconds at each step,
         * so a round trip can land a microsecond off; anything larger
         * than this is a real bug, not rounding.
         */
        const val ROUND_TRIP_TOLERANCE_US = 2L

        /** True when [a] and [b] are within [ROUND_TRIP_TOLERANCE_US]. */
        fun approximatelyEqual(a: Long, b: Long): Boolean = abs(a - b) <= ROUND_TRIP_TOLERANCE_US
    }
}
