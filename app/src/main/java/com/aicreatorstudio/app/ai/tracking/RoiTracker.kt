package com.aicreatorstudio.app.ai.tracking

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF

/**
 * Result of one tracker update.
 *
 * @property roi normalized 0..1 box in the frame coordinate space the
 *   tracker was initialised against.
 * @property confidence 0..1. **Derived, not measured** — see the note
 *   on [RoiTracker]. OpenCV's `Tracker` API exposes no score for KCF
 *   or CSRT, so implementations must synthesise one.
 */
data class RoiTrackerResult(
    val roi: RectF,
    val confidence: Float
)

/**
 * Short-term, appearance-based single-object tracker — the "Tier B" of
 * the two-tier hybrid described in
 * `docs/MOTION_TRACKING_IMPLEMENTATION.md` section 1.1.
 *
 * ## Why this interface exists
 *
 * The OpenCV-vs-vendored-vs-pure-Kotlin decision for the appearance
 * tracker must stay reversible and must stay out of the engine and the
 * UI. Putting it behind this interface means the engine consumes a
 * box and a confidence and does not care how they were produced, so a
 * device without the OpenCV tracking module degrades to
 * [NccRoiTracker] instead of failing.
 *
 * ## Contract
 *
 *  - [init] is called once, on the frozen selection frame, with a box
 *    in **normalized 0..1 frame coordinates**. Implementations convert
 *    to their own working resolution internally.
 *  - [update] is called once per analysed frame with a frame at the
 *    **same** resolution as [init].
 *  - Implementations are stateful and **not** thread-safe: one
 *    instance per tracking run, always on the same thread.
 *  - [close] must release native handles. An OpenCV tracker holds a
 *    native object and leaks megabytes per run if it is not released.
 *
 * ## Confidence is derived
 *
 * Neither `TrackerKCF` nor `TrackerCSRT` returns a confidence: their
 * `update` yields a `bool` and writes a `Rect`. A boolean is not
 * enough to detect the failure that actually ruins an export — the
 * tracker that is *confidently wrong*, locked onto the background and
 * moving smoothly. Implementations therefore derive a score from a
 * template residual and a motion prior. See [NccRoiTracker.deriveConfidence]
 * for the reference implementation.
 */
interface RoiTracker : AutoCloseable {

    /**
     * Initialise on the first frame. Returns false when the region is
     * not trackable (too small, or featureless enough that the
     * correlation is undefined) — the caller should then report an
     * honest failure rather than emit a noisy trajectory.
     *
     * @param frame a frame at the analysis resolution the caller will
     *   keep using for [update].
     * @param roi normalized 0..1 box to track.
     */
    fun init(frame: Bitmap, roi: RectF): Boolean

    /**
     * Refine the box on the next frame. Returns null on total tracking
     * failure (the caller then falls back to its semantic tier, or
     * marks the keyframe `isFound = false`).
     */
    fun update(frame: Bitmap): RoiTrackerResult?

    /** Human-readable backend name for the status line. */
    val backendName: String

    /**
     * Clip a normalized ROI to a pixel [Rect] inside [frame], leaving
     * at least a 2px header so correlation is defined. Shared by every
     * implementation so they cannot disagree about the working region.
     */
    fun roiToPixels(roi: RectF, frame: Bitmap): Rect {
        val w = frame.width.coerceAtLeast(2)
        val h = frame.height.coerceAtLeast(2)
        val left = (roi.left * w).toInt().coerceIn(0, w - 2)
        val top = (roi.top * h).toInt().coerceIn(0, h - 2)
        val right = (roi.right * w).toInt().coerceIn(left + 2, w)
        val bottom = (roi.bottom * h).toInt().coerceIn(top + 2, h)
        return Rect(left, top, right, bottom)
    }
}
