package com.aicreatorstudio.app.model

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 2026-10-13 — canvas-space placement for Picture-in-Picture overlays.
 *
 * ## Why this is a separate, pure file
 *
 * Before this file existed, [VideoOverlay.positionX], `positionY` and
 * `scale` had **no consumer anywhere in the app**. The overlay lane
 * rendered tiles on the timeline, the codec persisted the numbers, and
 * nothing turned them into pixels — there was no preview renderer and no
 * export compositor at all.
 *
 * The moment there ARE two consumers (the Compose preview layer and the
 * export `FrameOp`), the box maths becomes a parity contract: if the two
 * compute the placement independently they will disagree, and the
 * disagreement shows up only in the exported file. So the placement lives
 * here, once, as pure float maths over the canvas rectangle, and both
 * renderers call it.
 *
 * ## Anchor convention
 *
 * `positionX` / `positionY` are the box's **top-left corner**, normalised
 * to the canvas (`0.0..1.0`), and `scale` is the box's width as a
 * fraction of the canvas width. That reading is what makes the model's
 * own defaults (`0.1, 0.1, 0.3`) a sensible top-left inset; a
 * centre-anchor reading would push the default box off the canvas edge.
 *
 * Pure Kotlin, no `android.*` — directly JVM-testable.
 */

/**
 * A PiP overlay's rectangle in **canvas pixels**.
 *
 * Deliberately not a Compose `Rect` or an `android.graphics.RectF`: this
 * project's JVM tests run with `unitTests.isReturnDefaultValues = true`,
 * which makes `android.*` geometry silently return `0`, so a test built
 * on those types can pass for the wrong reason.
 */
data class PipBox(
    val leftPx: Float,
    val topPx: Float,
    val widthPx: Float,
    val heightPx: Float
) {
    val rightPx: Float get() = leftPx + widthPx
    val bottomPx: Float get() = topPx + heightPx
    val centerXPx: Float get() = leftPx + widthPx / 2f
    val centerYPx: Float get() = topPx + heightPx / 2f

    /** True when the box has a positive area and can be drawn at all. */
    val isRenderable: Boolean
        get() = widthPx > 0.5f && heightPx > 0.5f

    /** Top-left in canvas-normalised space — the inverse of placement. */
    fun normalizedLeft(canvasWidthPx: Int): Float =
        if (canvasWidthPx <= 0) 0f else (leftPx / canvasWidthPx).coerceIn(0f, 1f)

    /** See [normalizedLeft]. */
    fun normalizedTop(canvasHeightPx: Int): Float =
        if (canvasHeightPx <= 0) 0f else (topPx / canvasHeightPx).coerceIn(0f, 1f)

    /** Normalised point inside this box, for hit-testing against a mask. */
    fun toMaskSpace(x: Float, y: Float): Pair<Float, Float> {
        if (widthPx <= 0f || heightPx <= 0f) return 0f to 0f
        return ((x - leftPx) / widthPx) to ((y - topPx) / heightPx)
    }
}

/** Pure placement maths for PiP overlays. */
object OverlayPipGeometry {

    /** A PiP narrower than this fraction of the canvas is unusable. */
    const val MIN_SCALE: Float = 0.08f

    /** A PiP wider than this fraction of the canvas stops being a PiP. */
    const val MAX_SCALE: Float = 1f

    /** Aspect ratio used when a caller has none (a square box). */
    const val DEFAULT_ASPECT_RATIO: Float = 1f

    /**
     * Aspect ratios outside this range are treated as corrupt and replaced
     * by [DEFAULT_ASPECT_RATIO]. A persisted `0` or `NaN` would otherwise
     * divide into an infinite box height and poison every downstream
     * `RectF`.
     */
    const val MIN_ASPECT_RATIO: Float = 0.05f
    const val MAX_ASPECT_RATIO: Float = 20f

    /** Clamp a user-driven scale into the usable range. */
    fun clampScale(scale: Float): Float =
        scale.takeIf { it.isFinite() }?.coerceIn(MIN_SCALE, MAX_SCALE) ?: 0.3f

    /** Clamp a persisted aspect ratio into the usable range. */
    fun clampAspect(aspect: Float): Float =
        aspect.takeIf { it.isFinite() && it > 0f }
            ?.coerceIn(MIN_ASPECT_RATIO, MAX_ASPECT_RATIO)
            ?: DEFAULT_ASPECT_RATIO

    /** Clamp a normalised canvas position into `0.0..1.0`. */
    fun clampNorm(value: Float): Float =
        value.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f

    /**
     * Resolve an overlay's canvas-space rectangle.
     *
     * The box is placed from the top-left and then **translated back
     * inside the canvas** if it overhangs — dragging a PiP to the right
     * edge should pin it to the edge, not let half of it leave the
     * picture. When the box is wider or taller than the canvas the origin
     * clamps to `0` and the box simply overflows to the bottom/right,
     * which is the only honest behaviour for an oversized PiP.
     *
     * Returns a box with `isRenderable == false` for a degenerate canvas,
     * so callers can bail without a second guard.
     */
    fun resolveBox(
        positionX: Float,
        positionY: Float,
        scale: Float,
        aspectRatio: Float,
        canvasWidthPx: Int,
        canvasHeightPx: Int
    ): PipBox {
        if (canvasWidthPx <= 0 || canvasHeightPx <= 0) {
            return PipBox(0f, 0f, 0f, 0f)
        }
        val w = canvasWidthPx.toFloat()
        val h = canvasHeightPx.toFloat()
        val s = clampScale(scale)
        val aspect = clampAspect(aspectRatio)

        val boxW = (w * s).coerceAtLeast(1f)
        // A square box for aspect == 1; wider for a landscape source.
        val boxH = (boxW / aspect).coerceAtLeast(1f)

        val maxLeft = (w - boxW).coerceAtLeast(0f)
        val maxTop = (h - boxH).coerceAtLeast(0f)

        val left = (clampNorm(positionX) * w).coerceIn(0f, maxLeft)
        val top = (clampNorm(positionY) * h).coerceIn(0f, maxTop)

        return PipBox(left, top, boxW, boxH)
    }

    /** [resolveBox] against a [VideoOverlay]'s own fields. */
    fun resolveBox(
        overlay: VideoOverlay,
        canvasWidthPx: Int,
        canvasHeightPx: Int
    ): PipBox = resolveBox(
        positionX = overlay.positionX,
        positionY = overlay.positionY,
        scale = overlay.scale,
        aspectRatio = overlay.aspectRatio,
        canvasWidthPx = canvasWidthPx,
        canvasHeightPx = canvasHeightPx
    )

    // ---- time ---------------------------------------------------------

    /** True when [editorMs] falls inside the overlay's window. */
    fun isVisibleAt(overlay: VideoOverlay, editorMs: Long): Boolean {
        if (overlay.uri.isBlank()) return false
        val end = overlay.endTimeMs
        if (end <= overlay.startTimeMs) return false
        return editorMs >= overlay.startTimeMs && editorMs < end
    }

    /**
     * Where inside the overlay's own media the playhead sits, clamped to
     * the window. Negative means "not started yet", which callers treat
     * as "park at 0".
     */
    fun localMs(overlay: VideoOverlay, editorMs: Long): Long =
        (editorMs - overlay.startTimeMs).coerceAtLeast(0L)

    /**
     * Map a decoded frame's presentation timestamp onto the **editor
     * timeline**, for the export's per-frame pass.
     *
     * The pre-pass transcode in `AiExportHooks` hands the `FrameOp` the
     * *source* PTS (audio and timestamps flow through 1:1), so the frame
     * that belongs at editor time `t` carries `sourceStartMs + (t −
     * timelineStartMs) × speed`. Inverting that gives this function.
     *
     * The retime is expressed as a single linear map from
     * `[sourceStartMs, sourceStartMs + sourceDurationMs]` onto
     * `[timelineStartMs, timelineStartMs + outputDurationMs]`. For a
     * constant-speed segment that is exact. For a **speed ramp** it is
     * the linear approximation of a non-linear map — the same
     * approximation `rampedPreviewSpeed` makes for the live preview, so
     * the two agree with each other even though both differ from the
     * ramp's instantaneous rate. Documented rather than hidden: an
     * overlay pinned to a ramped clip drifts by at most the ramp's
     * excursion from its own average, never by the whole ramp.
     */
    fun editorMsForSourcePts(
        ptsUs: Long,
        timelineStartMs: Long,
        sourceStartMs: Long,
        sourceDurationMs: Long,
        outputDurationMs: Long
    ): Long {
        val ptsMs = ptsUs / 1000L
        if (sourceDurationMs <= 0L) return timelineStartMs
        val offset = ptsMs - sourceStartMs
        if (outputDurationMs <= 0L) return timelineStartMs + offset
        // Integer maths would truncate a sub-ms offset to zero on every
        // frame of a short segment, so scale in Long before dividing.
        val scaled = offset * outputDurationMs / sourceDurationMs
        return timelineStartMs + scaled
    }

    // ---- hit testing --------------------------------------------------

    /**
     * Which overlay the mask panel and the preview chrome should address.
     *
     * Resolution order, and why:
     *
     *  1. **An explicit selection**, when it still exists. Once the user
     *     has tapped a PiP, that is the one they mean — even if the
     *     playhead then moves out of its window, because a selection that
     *     evaporates while you are dragging a slider is worse than one
     *     that outlives its window.
     *  2. **The topmost overlay live at the playhead.** A freshly opened
     *     panel with nothing selected should address the PiP the user can
     *     actually see, which is the last one in draw order that overlaps
     *     [editorMs].
     *  3. **The selected clip's topmost overlay.** Covers the case where
     *     the playhead sits in a gap but the user has clearly picked a
     *     clip whose PiP they want to shape.
     *
     * Deliberately has **no final catch-all**: when none of the three
     * applies, the panel reports that it has nothing to edit rather than
     * silently reshaping an overlay the user never pointed at.
     *
     * Both the panel and the preview chrome call this, so the highlighted
     * PiP and the one being edited can never be two different objects.
     */
    fun resolveTarget(
        overlays: List<VideoOverlay>,
        selectedOverlayId: String?,
        editorMs: Long,
        clipId: String?
    ): VideoOverlay? {
        if (overlays.isEmpty()) return null
        if (selectedOverlayId != null) {
            overlays.firstOrNull { it.id == selectedOverlayId }?.let { return it }
        }
        overlays.lastOrNull { isVisibleAt(it, editorMs) }?.let { return it }
        if (clipId != null) {
            overlays.lastOrNull { it.clipId == clipId }?.let { return it }
        }
        return null
    }

    /**
     * Whether a canvas-space point lands on [overlay], honouring its
     * rotation and — when it has one — its shape mask.
     *
     * Rotation is applied by rotating the *point* into the box's own
     * frame, which is the inverse of rotating the box. That keeps the
     * test identical to what the renderers draw without needing a matrix
     * type, and it is exact for the arbitrary angles the gesture layer
     * produces (the export rounds rotation to the nearest degree only for
     * the drawn output, not for this test).
     *
     * A masked overlay is only tappable **inside its visible shape**: a
     * circle facecam must not swallow taps in its own transparent
     * corners, or the user cannot reach the video underneath.
     */
    fun hitTest(
        overlay: VideoOverlay,
        canvasXPx: Float,
        canvasYPx: Float,
        canvasWidthPx: Int,
        canvasHeightPx: Int
    ): Boolean {
        val box = resolveBox(overlay, canvasWidthPx, canvasHeightPx)
        if (!box.isRenderable) return false

        // Undo the rotation about the box centre.
        var px = canvasXPx
        var py = canvasYPx
        val deg = overlay.rotationDegrees
        if (deg.isFinite() && abs(deg % 360f) > 1e-3f) {
            val rad = Math.toRadians((-deg).toDouble())
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            val dx = px - box.centerXPx
            val dy = py - box.centerYPx
            px = box.centerXPx + dx * c - dy * s
            py = box.centerYPx + dx * s + dy * c
        }

        val (nx, ny) = box.toMaskSpace(px, py)
        return OverlayMaskGeometry.contains(overlay.maskSettings, nx, ny)
    }

    /**
     * Aspect ratio from raw media dimensions, rotation-corrected.
     * Returns [DEFAULT_ASPECT_RATIO] when either dimension is unknown, so
     * an unprobed overlay is square rather than infinite.
     */
    fun aspectRatioFor(widthPx: Int, heightPx: Int, rotationDegrees: Int): Float {
        if (widthPx <= 0 || heightPx <= 0) return DEFAULT_ASPECT_RATIO
        val rot = ((rotationDegrees % 360) + 360) % 360
        val swapped = rot == 90 || rot == 270
        val w = if (swapped) heightPx else widthPx
        val h = if (swapped) widthPx else heightPx
        return clampAspect(w.toFloat() / h.toFloat())
    }

    /**
     * The box that a **new** overlay should get so it is centred and
     * comfortably inset, independent of the canvas size. Used by the
     * "add overlay" path so a fresh PiP never lands half off-screen.
     */
    fun defaultPlacement(): Triple<Float, Float, Float> = Triple(0.12f, 0.12f, 0.34f)

    /** Largest a PiP may be, as a fraction of the canvas, for a given aspect. */
    fun maxScaleFor(aspectRatio: Float): Float {
        val a = clampAspect(aspectRatio)
        // A very tall overlay cannot fill the canvas width without
        // overflowing vertically, so the cap is aspect-aware.
        return if (a >= 1f) MAX_SCALE else max(MIN_SCALE, a).coerceAtMost(MAX_SCALE)
    }

    /** Smallest legal gap between two PiP edges, used by snapping. */
    const val SNAP_TOLERANCE_NORM: Float = 0.02f

    /** Snap a normalised coordinate to 0, 0.5 or 1 when it is close enough. */
    fun snapNorm(value: Float): Float {
        val targets = floatArrayOf(0f, 0.5f, 1f)
        var best = value
        var bestDist = SNAP_TOLERANCE_NORM
        targets.forEach { t ->
            val d = abs(value - t)
            if (d < bestDist) {
                bestDist = d
                best = t
            }
        }
        return best.coerceIn(0f, 1f)
    }

    /** Aspect ratio of the canvas itself, guarding a zero height. */
    fun canvasAspect(canvasWidthPx: Int, canvasHeightPx: Int): Float {
        if (canvasWidthPx <= 0 || canvasHeightPx <= 0) return DEFAULT_ASPECT_RATIO
        return canvasWidthPx.toFloat() / canvasHeightPx.toFloat()
    }

    /** Convenience: the smaller of two ints, clamped at zero. */
    fun shorterSide(a: Int, b: Int): Int = max(0, min(a, b))
}
