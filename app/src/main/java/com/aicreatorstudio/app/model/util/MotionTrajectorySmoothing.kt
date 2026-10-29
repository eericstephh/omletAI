package com.aicreatorstudio.app.model.util

import com.aicreatorstudio.app.model.CropKeyframe
import kotlin.math.sqrt

/**
 * Trajectory smoothing for Motion Tracking, built on
 * [OneEuroFilter].
 *
 * ## Ordering constraint: smooth at full rate, THEN decimate
 *
 * The filter must run on the **full-rate** trajectory — one sample per
 * analysed frame — and the keyframe list must be produced by sampling
 * the *smoothed* trace. Filtering an already-decimated list filters an
 * aliased signal: the jitter has already folded down into the
 * keyframe-rate signal and can no longer be separated from real
 * motion. `SaliencyTrackerEngine.process` already does this in the
 * correct order, and this module preserves it.
 *
 * ## Why the centre and the extent get different strengths
 *
 * A viewer is far more sensitive to an overlay's **position** jitter
 * than to its **scale** jitter. An overlay that shakes reads as broken
 * tracking; the same overlay pulsing a few percent in size reads as
 * barely anything. So the centre is filtered gently (responsiveness
 * matters — over-smoothing makes the overlay visibly lag its subject)
 * while the extent is filtered heavily (cheap, and it removes the
 * scale pumping CSRT exhibits on partial occlusion).
 *
 * ## Why the aspect ratio is held constant
 *
 * Filtering width and height independently lets the aspect drift, so
 * the user's box slowly stretches. Instead the area-equivalent uniform
 * scale is filtered and the box is reconstructed around the mean
 * aspect. This also matches how an overlay is attached in practice —
 * uniform scale plus translation, which a 3x3 affine matrix expresses
 * without shear.
 */
object MotionTrajectorySmoothing {

    /**
     * Apply 1-Euro smoothing to a raw trajectory.
     *
     * @param raw one entry per analysed frame, time-ordered.
     * @param positionSmoothing 0..1 — higher is heavier, applied to the
     *   box centre.
     * @param scaleSmoothing 0..1 — higher is heavier, applied to the
     *   box extent.
     * @param intervalMs the sample stride the raw trace was produced
     *   at. Must be the real stride or the filter's derivative estimate
     *   — and therefore its adaptive cutoff — is wrong.
     * @return a trajectory of the same size with smoothed centre and
     *   extent. [CropKeyframe.confidence] and [CropKeyframe.isFound]
     *   are passed through untouched: they are measurement metadata,
     *   not signal.
     */
    fun smooth(
        raw: List<CropKeyframe>,
        positionSmoothing: Float,
        scaleSmoothing: Float,
        intervalMs: Long = DEFAULT_INTERVAL_MS
    ): List<CropKeyframe> {
        // Fewer than three samples cannot exhibit jitter, and the
        // filter's derivative estimate is meaningless on one or two.
        if (raw.size < 3) return raw

        val dt = (intervalMs.coerceAtLeast(1L)) / 1000f
        val posFilter = OneEuroFilter(positionSmoothing, dt)
        val scaleFilter = OneEuroFilter(scaleSmoothing, dt)

        // Centre trace.
        val centres = posFilter.smooth(raw.map { it.centerX to it.centerY })

        // Uniform-scale trace, sqrt(w*h). Entries without extent (Auto
        // Frame) contribute 0 and are reconstructed as "no extent"
        // below, so the two producers cannot interfere.
        val scales = scaleFilter.smoothScalar(
            raw.map { k ->
                val w = k.width
                val h = k.height
                if (w != null && h != null && w > 0f && h > 0f) sqrt(w * h) else 0f
            }
        )

        val meanAspect = meanAspectOf(raw)

        return raw.mapIndexed { i, k ->
            val (cx, cy) = centres[i]
            val s = scales[i]
            val clampedCx = cx.coerceIn(0f, 1f)
            val clampedCy = cy.coerceIn(0f, 1f)

            if (!k.hasExtent || s <= 0f) {
                // No extent information: smooth the centre only, and
                // leave the extent fields null so a downstream reader
                // keeps taking the Auto Frame path.
                k.copy(centerX = clampedCx, centerY = clampedCy)
            } else {
                val h = (s / sqrt(meanAspect)).coerceIn(0f, 1f)
                val w = (s * sqrt(meanAspect)).coerceIn(0f, 1f)
                k.copy(
                    centerX = clampedCx,
                    centerY = clampedCy,
                    width = w,
                    height = h
                )
            }
        }
    }

    /**
     * Mean aspect ratio (w/h) over the entries that carry extent.
     * Returns 1f when none do.
     */
    private fun meanAspectOf(raw: List<CropKeyframe>): Float {
        var sum = 0f
        var n = 0
        for (k in raw) {
            val w = k.width
            val h = k.height
            if (w != null && h != null && h > 0f && w > 0f) {
                sum += w / h
                n++
            }
        }
        if (n == 0) return 1f
        val m = sum / n
        return if (m.isFinite() && m > 0f) m else 1f
    }

    /**
     * Decimate a full-rate smoothed trajectory to keyframes at
     * [intervalMs] spacing.
     *
     * The last sample is always included: a trajectory that stops short
     * of the clip's end makes the overlay freeze at the final
     * *keyframe* and then jump when the exported file ends, or — worse
     * — the consumer clamps to the last entry and holds a stale box.
     */
    fun decimate(
        smoothed: List<CropKeyframe>,
        intervalMs: Long,
        durationMs: Long
    ): List<CropKeyframe> {
        if (smoothed.isEmpty()) return smoothed
        val step = intervalMs.coerceAtLeast(1L)
        val out = ArrayList<CropKeyframe>((durationMs / step + 2).toInt())
        var t = 0L
        while (t <= durationMs) {
            out.add(nearestTo(smoothed, t))
            t += step
        }
        val last = smoothed.last()
        if (out.isEmpty() || out.last().timeMs != last.timeMs) out.add(last)
        return out
    }

    /**
     * The smoothed sample nearest to [tsMs]. A nearest-neighbour pick
     * rather than an interpolation, because the trace is already at
     * full rate — interpolation here would smooth twice.
     */
    private fun nearestTo(smoothed: List<CropKeyframe>, tsMs: Long): CropKeyframe {
        var best = smoothed[0]
        var bestDelta = kotlin.math.abs(best.timeMs - tsMs)
        for (k in smoothed) {
            val d = kotlin.math.abs(k.timeMs - tsMs)
            if (d < bestDelta) {
                best = k
                bestDelta = d
            }
            // Time-ordered: once we pass the target, we can stop.
            if (k.timeMs > tsMs && bestDelta < d) break
        }
        return best
    }

    /**
     * Alpha multiplier to hide a re-acquisition jump.
     *
     * A discontinuity cannot be smoothed away (see
     * [MotionTrackingGeometry.isDiscontinuity]). The overlay instead
     * fades out just before the jump and back in just after, so the
     * jump happens while nothing is visible. Returns 1f when
     * [timeMs] is outside every ramp window, which is the common case.
     *
     * @param keys time-ordered trajectory, including not-found entries.
     * @param rampMs half-width of each ramp.
     */
    fun reanchorAlpha(
        keys: List<CropKeyframe>,
        timeMs: Long,
        rampMs: Long = DEFAULT_REANCHOR_RAMP_MS
    ): Float {
        if (keys.size < 2 || rampMs <= 0L) return 1f
        for (i in 0 until keys.size - 1) {
            val a = keys[i]
            val b = keys[i + 1]
            val jumpAt = b.timeMs
            if (timeMs < jumpAt - rampMs) continue
            if (timeMs > jumpAt + rampMs) continue
            if (!MotionTrackingGeometry.isDiscontinuity(a, b)) continue
            val d = kotlin.math.abs(timeMs - jumpAt).toFloat()
            return (d / rampMs).coerceIn(0f, 1f)
        }
        return 1f
    }

    /** Default sample stride assumed when a caller omits one (30 fps). */
    const val DEFAULT_INTERVAL_MS: Long = 33L

    /** Half-width of the fade wrapped around a re-acquisition jump. */
    const val DEFAULT_REANCHOR_RAMP_MS: Long = 150L
}
