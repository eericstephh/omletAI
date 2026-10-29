package com.aicreatorstudio.app.ui.screens.editor

import com.aicreatorstudio.app.export.TrackedOverlayRenderer
import com.aicreatorstudio.app.model.CropKeyframe
import com.aicreatorstudio.app.model.MotionTrackSettings
import com.aicreatorstudio.app.model.util.MotionTrackingGeometry
import com.aicreatorstudio.app.model.util.MotionTrajectorySmoothing

/**
 * Pure preview-side placement for a **tracked** text overlay.
 *
 * ## Why this exists
 *
 * Before 2026-10-17 the tracked overlay's motion existed only in the
 * export (`export/TrackedOverlayRenderer`): the editor preview kept the
 * overlay parked at its authored position, so a successful run looked
 * like a no-op to the user until they exported. This helper applies the
 * SAME placement math to the Compose preview so the live canvas moves
 * the overlay the way the exported MP4 will.
 *
 * ## Parity contract (single-source-of-truth positioning)
 *
 * The math mirrors `TrackedOverlayRenderer.drawAt`/`drawStamp` exactly:
 *   1. `MotionTrackingGeometry.interpolateKeyframe` samples the
 *      trajectory at clip-local ms (binary search + hold-across-gap).
 *   2. `MotionTrackingGeometry.confidenceAlpha` ×
 *      `MotionTrajectorySmoothing.reanchorAlpha` is the display alpha.
 *   3. The overlay's content centre is translated to
 *      [MotionTrackSettings.anchorU]/[MotionTrackSettings.anchorV]
 *      inside the interpolated tracked box.
 *   4. `scaleWithBox` scales by `boxHeight / canvasHeight /
 *      referenceBoxHeight`, clamped to the SAME `MIN_BOX_SCALE` /
 *      `MAX_BOX_SCALE` constants the export renderer publishes.
 *
 * Everything here is plain floats on normalized coordinates — no
 * `android.graphics` — so the JVM test suite can pin it.
 *
 * @param settings the clip's [MotionTrackSettings] (must be enabled).
 * @param keyframes the clip's trajectory (Motion Tracking entries carry
 *   extent; Auto Frame entries have none and yield `null` here).
 * @param clipLocalMs playhead relative to the clip's start (export
 *   subtracts `clipTimelineStartMs` from the frame PTS; the preview
 *   subtracts the clip's `timelineStartMs` from the playhead — same
 *   quantity, same coordinate system).
 * @param parentWidthPx width of the preview canvas box in px. The
 *   export maps normalized trajectory values against the post-reframe
 *   output frame; the preview canvas is the target-aspect box the
 *   letterboxed player sits in, which is the same normalized space for
 *   the common (Auto Frame disabled while tracking) case.
 * @param authoredCenterXf the overlay's content centre, normalized
 *   against the canvas box (`overlay.position.x`, since the preview
 *   centres the content and offsets by `(position - 0.5) * parent`).
 * @return placement deltas, or `null` when there is nothing tracked to
 *   apply (empty trajectory / no extent / no id binding) — callers must
 *   keep the authored position in that case.
 */
data class TrackedPreviewPlacement(
    /** Additional translation, in px, to move the content centre from its authored spot to the anchor. */
    val deltaXPx: Float,
    val deltaYPx: Float,
    /** Uniform scale about the content centre (1f when `scaleWithBox` is off). */
    val scale: Float,
    /** Final display alpha (confidence × re-anchor ramp, 0 → hide). */
    val alpha: Float
)

fun trackedPreviewPlacement(
    settings: MotionTrackSettings,
    keyframes: List<CropKeyframe>,
    clipLocalMs: Long,
    parentWidthPx: Float,
    parentHeightPx: Float,
    authoredCenterXf: Float,
    authoredCenterYf: Float
): TrackedPreviewPlacement? {
    if (keyframes.isEmpty() || parentWidthPx <= 0f || parentHeightPx <= 0f) return null
    if (!keyframes.any { it.hasExtent }) return null

    val k = MotionTrackingGeometry.interpolateKeyframe(keyframes, clipLocalMs)
    val confidenceAlpha = MotionTrackingGeometry.confidenceAlpha(k.confidence, k.isFound)
    if (confidenceAlpha <= 0f) return TrackedPreviewPlacement(0f, 0f, 1f, 0f)
    val rampAlpha = MotionTrajectorySmoothing.reanchorAlpha(keyframes, clipLocalMs)
    val alpha = (confidenceAlpha * rampAlpha).coerceIn(0f, 1f)
    if (alpha <= 0f) return TrackedPreviewPlacement(0f, 0f, 1f, 0f)

    val w = k.width ?: return null
    val h = k.height ?: return null
    if (w <= 0f || h <= 0f) return null

    // The tracked box in canvas px — float mirror of
    // TrackedOverlayRenderer.boxOf, which multiplies normalized
    // centre/size by the frame the overlay is burned into.
    val bw = (w * parentWidthPx).coerceAtLeast(2f)
    val bh = (h * parentHeightPx).coerceAtLeast(2f)
    val left = k.centerX * parentWidthPx - bw / 2f
    val top = k.centerY * parentHeightPx - bh / 2f
    if (left.isNaN() || top.isNaN()) return null

    // Anchor point inside the box (raw frame px).
    val anchorX = left + settings.anchorU.coerceIn(0f, 1f) * bw
    val anchorY = top + settings.anchorV.coerceIn(0f, 1f) * bh

    // Export scales the stamp from its top-left origin and translates
    // its content centre to the anchor; scaled about the content centre
    // with a pure translation produces the identical destination rect,
    // which is the form the Compose graphicsLayer uses.
    val scale = if (settings.scaleWithBox) {
        val ref = settings.referenceBoxHeight.coerceAtLeast(0.001f)
        (bh / parentHeightPx / ref)
            .coerceIn(TrackedOverlayRenderer.MIN_BOX_SCALE, TrackedOverlayRenderer.MAX_BOX_SCALE)
    } else {
        1f
    }

    val authoredX = authoredCenterXf * parentWidthPx
    val authoredY = authoredCenterYf * parentHeightPx
    return TrackedPreviewPlacement(
        deltaXPx = anchorX - authoredX,
        deltaYPx = anchorY - authoredY,
        scale = scale,
        alpha = alpha
    )
}
