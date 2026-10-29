package com.aicreatorstudio.app.model.util

import kotlin.math.PI
import kotlin.math.abs

/**
 * **1-Euro filter** — Casiez, Roussel & Vogel, CHI 2012
 * ("1e Filter: A Simple Speed-based Low-pass Filter for Noisy Input
 * in Interactive Systems").
 *
 * ## Why this and not an EMA or a Kalman filter
 *
 * A plain EMA (exponential moving average) has a **fixed cutoff
 * frequency**, so it forces a choice between two failures: heavy
 * smoothing that visibly lags fast motion, or light smoothing that
 * leaves the jitter in. There is no setting that both kills jitter at
 * rest and tracks a fast pan, because the requirement is
 * frequency-dependent and the filter is not.
 *
 * A 1-D Kalman filter can express this, but needs a per-axis
 * process/measurement noise model, and a badly tuned Kalman is worse
 * than an EMA. It also assumes a motion model (typically
 * constant-velocity) that a handheld subject does not obey.
 *
 * The 1-Euro filter solves the actual problem: **the cutoff rises
 * with the estimated signal speed.** When the signal is nearly still
 * the cutoff is low and smoothing is aggressive (jitter dies); when
 * the signal is moving fast the cutoff rises and lag collapses. That
 * is precisely the requirement "smooth when still, responsive when
 * moving".
 *
 * ## Usage
 *
 * ```kotlin
 * val filtered = OneEuroFilter(smoothing = 0.5f, dtSeconds = 1f / 30f)
 *     .smooth(rawSamples)
 * ```
 *
 * ## History
 *
 * Extracted from `SaliencyTrackerEngine` (where it lived as a nested
 * `internal class`) on 2026-09-28 so Motion Tracking can reuse it.
 * Beyond reuse, the move makes it **unit-testable**: previously it sat
 * inside an `@Singleton` Hilt engine with an Android `Context`
 * dependency, so it could not be exercised in the JVM test suite.
 * It is now a pure class with no Android imports — per the project
 * rule that new testable logic lives in a pure top-level declaration.
 *
 * The arithmetic is unchanged from the engine's version, deliberately:
 * Auto Frame's smoothing behaviour is already tuned and validated, and
 * a "cleanup" refactor of the maths here would silently retune a
 * shipped feature.
 *
 * @param smoothing 0f..1f. **Higher = heavier smoothing** (matching
 *   the UI slider's wording, which reads "more smoothing = smoother").
 *   `0` yields the minimum cutoff and behaves as a light filter;
 *   `1` yields a heavy one. Values outside the range are clamped.
 * @param dtSeconds time between samples, in seconds. This must match
 *   the actual sample stride or the derivative estimate — and
 *   therefore the adaptive cutoff — is wrong. Defaults to the
 *   Auto Frame stride of 250 ms.
 */
class OneEuroFilter(
    smoothing: Float,
    private val dtSeconds: Float = DEFAULT_DT_SECONDS
) {

    private val smoothing: Float = smoothing.coerceIn(0f, 1f)

    /**
     * Minimum cutoff frequency, Hz. Falls as smoothing rises: a low
     * cutoff means slow response (heavy smoothing).
     */
    private val minCutoff: Float = 0.4f + 1.6f * (1f - this.smoothing)

    /**
     * Speed coefficient. Rises as smoothing falls, so a light setting
     * reacts more strongly to measured speed.
     */
    private val beta: Float = 0.02f + 0.18f * (1f - this.smoothing)

    /** Cutoff for the derivative estimate itself. */
    private val dCutoff: Float = 1.0f

    /** Derived from the primary smoothing parameter. */
    val cutoffs: Pair<Float, Float> get() = minCutoff to beta

    /**
     * Smooth a sequence of 2-D samples (e.g. a normalized centre
     * trace), returning a list of the same size. The first sample is
     * used as the initial state and comes back unchanged in effect.
     */
    fun smooth(samples: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (samples.isEmpty()) return samples

        var xPrev = samples[0].first
        var xDxPrev = 0f
        var yPrev = samples[0].second
        var yDyPrev = 0f

        val out = ArrayList<Pair<Float, Float>>(samples.size)
        val alphaD = smoothingFactor(dtSeconds, dCutoff)

        for ((x, y) in samples) {
            // ---- X axis ----
            val xDx = (x - xPrev) / dtSeconds
            val xDxHat = xDxPrev + alphaD * (xDx - xDxPrev)
            val xAlpha = smoothingFactor(dtSeconds, minCutoff + beta * abs(xDxHat))
            val xHat = xPrev + xAlpha * (x - xPrev)
            xPrev = xHat
            xDxPrev = xDxHat

            // ---- Y axis ----
            val yDy = (y - yPrev) / dtSeconds
            val yDyHat = yDyPrev + alphaD * (yDy - yDyPrev)
            val yAlpha = smoothingFactor(dtSeconds, minCutoff + beta * abs(yDyHat))
            val yHat = yPrev + yAlpha * (y - yPrev)
            yPrev = yHat
            yDyPrev = yDyHat

            out.add(xHat to yHat)
        }
        return out
    }

    /**
     * Smooth a sequence of scalar samples. Convenience for the box
     * extent trace, which is a single value per frame rather than a
     * pair. Implemented on top of [smooth] so the two cannot drift.
     */
    fun smoothScalar(samples: List<Float>): List<Float> =
        smooth(samples.map { it to it }).map { it.first }

    /**
     * The low-pass blend factor for a given [cutoff] at [dt]. Standard
     * 1-Euro formulation: `alpha = r / (r + 1)` with `r = 2*pi*fc*dt`.
     * Approaches 1 (no smoothing) as the cutoff rises.
     */
    private fun smoothingFactor(dt: Float, cutoff: Float): Float {
        val r = 2f * PI.toFloat() * cutoff * dt
        return r / (r + 1f)
    }

    companion object {
        /** Auto Frame's historical sample stride. */
        const val DEFAULT_DT_SECONDS: Float = 0.25f
    }
}
