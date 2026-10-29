package com.aicreatorstudio.app.model.util

import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.unit.IntSize
import com.aicreatorstudio.app.model.CropKeyframe
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure geometry for Motion Tracking: ROI selection, coordinate
 * conversion, box clamping, trajectory interpolation and smoothing.
 *
 * ## Why this is a single pure module
 *
 * Every coordinate bug in a tracked-overlay feature traces back to one
 * of four conversions, and each of them is easy to get *plausibly*
 * wrong rather than obviously wrong:
 *
 *  1. container pixels -> the letterboxed video rect
 *  2. the video rect     -> normalized 0..1 frame coordinates
 *  3. normalized         -> source pixels (for the tracker's init call)
 *  4. normalized         -> output pixels (for the export's blit)
 *
 * Keeping them in one file with no Android runtime dependency beyond
 * the `graphics`/`ui.geometry` value types means the whole set is
 * exercisable from the JVM test suite (see `MotionTrackingGeometryTest`).
 * Per the project convention, new testable logic lives in pure
 * top-level declarations rather than inside a Hilt ViewModel, which
 * cannot be instantiated in a JVM test.
 *
 * ## The rotation trap this module exists to prevent
 *
 * `VideoFramePipeline` emits **rotation-corrected** bitmaps: for a
 * 90deg/270deg source, `SourceInfo.displayWidth/displayHeight` are
 * swapped relative to the container's stored width/height. A raw
 * `MediaMetadataRetriever.getFrameAtTime` call, by contrast, returns
 * the *unrotated* frame. Normalized coordinates are therefore only
 * comparable when both producer and consumer normalize against the
 * **display** size. Every entry point below takes an explicit
 * `displayWidthPx` / `displayHeightPx` for exactly that reason — so a
 * caller cannot accidentally pass the container size and get a
 * silently 90deg-off box.
 */
object MotionTrackingGeometry {

    /**
     * The largest a single-step centre jump may be, as a fraction of
     * the frame per 100 ms, before it is treated as a tracker
     * re-acquisition rather than real motion.
     *
     * At 30 fps this is ~2.4 screen-widths/second, which no handheld
     * subject achieves. A larger step is a lock loss, and smoothing it
     * would produce a visible swoop rather than hiding it — see
     * [isDiscontinuity].
     */
    const val DISCONTINUITY_MAX_STEP_PER_100MS: Float = 0.8f

    /**
     * A tracked box smaller than this (in normalized units, per side)
     * is not a trackable target — the correlation filter has too few
     * pixels to lock onto and the output is noise.
     */
    const val MIN_ROI_SIDE: Float = 0.02f

    // ------------------------------------------------------------------
    // 1. container -> letterboxed video rect
    // ------------------------------------------------------------------

    /**
     * Compute the letterboxed video rect inside a [container],
     * honouring the display aspect of the (rotation-corrected) source.
     *
     * This is the function every coordinate bug traces back to, so it
     * is unit-tested for all four aspect relations.
     *
     * The video is centred inside the container and scaled to fit —
     * matching `FillMode.FIT`, which is the default the editor uses
     * while a ROI is being drawn. Returns a zero rect for degenerate
     * inputs rather than throwing, so a zero-sized layout during the
     * first composition frame degrades to "no ROI overlay" instead of
     * crashing the editor.
     */
    fun videoFrameRect(
        container: IntSize,
        displayWidthPx: Int,
        displayHeightPx: Int
    ): Rect {
        val f = videoFrameBox(
            container.width, container.height, displayWidthPx, displayHeightPx
        )
        return Rect(
            f.left.toInt(), f.top.toInt(), f.right.toInt(), f.bottom.toInt()
        )
    }

    /**
     * The letterbox math on **plain floats** — the single
     * implementation, which the `IntSize` and Compose variants both
     * delegate to.
     *
     * Split out for testability, and not for tidiness: `IntSize` and
     * `android.graphics.Rect` both read back as zeros under this
     * module's `unitTests.isReturnDefaultValues = true`, so a test
     * written against them asserts nothing. The whole letterbox
     * relation is float arithmetic and belongs here, where the JVM
     * suite can actually pin all four aspect cases.
     */
    fun videoFrameBox(
        containerWidth: Int,
        containerHeight: Int,
        displayWidthPx: Int,
        displayHeightPx: Int
    ): NormBox {
        if (containerWidth <= 0 || containerHeight <= 0) return ZERO_BOX
        if (displayWidthPx <= 0 || displayHeightPx <= 0) return ZERO_BOX

        val srcAspect = displayWidthPx.toFloat() / displayHeightPx
        val boxAspect = containerWidth.toFloat() / containerHeight

        return if (srcAspect > boxAspect) {
            // Source is wider than the container: full width,
            // letterbox bars top and bottom.
            val h = containerWidth / srcAspect
            val top = (containerHeight - h) / 2f
            NormBox(0f, top, containerWidth.toFloat(), top + h)
        } else {
            // Source is taller: full height, pillarbox bars left and
            // right.
            val w = containerHeight * srcAspect
            val left = (containerWidth - w) / 2f
            NormBox(left, 0f, left + w, containerHeight.toFloat())
        }
    }

    /**
     * Compose-facing variant of [videoFrameRect].
     *
     * The editor canvas measures itself in Compose coordinates, while
     * every tracker/`FrameOp` entry point speaks `android.graphics.Rect`.
     * Rather than making the UI layer convert (and risk the classic
     * half-pixel letterbox drift), the conversion lives here where it is
     * unit-tested alongside the four canonical conversions.
     */
    fun videoFrameComposeRect(
        container: IntSize,
        displayWidthPx: Int,
        displayHeightPx: Int
    ): ComposeRect {
        val f = videoFrameBox(
            container.width, container.height, displayWidthPx, displayHeightPx
        )
        return ComposeRect(f.left, f.top, f.right, f.bottom)
    }

    // ------------------------------------------------------------------
    // 2. container pixels <-> normalized frame coordinates
    // ------------------------------------------------------------------

    /**
     * Convert a point in container pixels to normalized 0..1 frame
     * coordinates. Values outside the video rect are clamped, so a
     * touch that starts on a letterbox bar still yields a valid in-frame
     * coordinate rather than a negative one.
     */
    fun toNormalized(pt: Offset, frame: ComposeRect): Offset {
        if (frame.width <= 0f || frame.height <= 0f) return Offset(0.5f, 0.5f)
        return Offset(
            ((pt.x - frame.left) / frame.width).coerceIn(0f, 1f),
            ((pt.y - frame.top) / frame.height).coerceIn(0f, 1f)
        )
    }

    /**
     * Convert normalized frame coordinates back to container pixels.
     * Not clamped — a caller rendering a box that is partly outside the
     * frame should see it clipped by the canvas, not snapped inward.
     */
    fun toContainer(norm: Offset, frame: ComposeRect): Offset =
        Offset(frame.left + norm.x * frame.width, frame.top + norm.y * frame.height)

    /** Convert a normalized rect to container pixels. */
    fun rectToContainer(norm: RectF, frame: ComposeRect): RectF = RectF(
        frame.left + norm.left * frame.width,
        frame.top + norm.top * frame.height,
        frame.left + norm.right * frame.width,
        frame.top + norm.bottom * frame.height
    )

    /** Convert a container-pixel rect to normalized frame coordinates. */
    fun rectToNormalized(px: RectF, frame: ComposeRect): RectF {
        if (frame.width <= 0f || frame.height <= 0f) return DEFAULT_ROI
        return RectF(
            ((px.left - frame.left) / frame.width).coerceIn(0f, 1f),
            ((px.top - frame.top) / frame.height).coerceIn(0f, 1f),
            ((px.right - frame.left) / frame.width).coerceIn(0f, 1f),
            ((px.bottom - frame.top) / frame.height).coerceIn(0f, 1f)
        ).let(::normalizeEdges)
    }

    // ------------------------------------------------------------------
    // 3 & 4. normalized -> pixels
    // ------------------------------------------------------------------

    /**
     * Convert a normalized ROI to a source-pixel rectangle, clamped to
     * the frame with a guaranteed non-zero area. Used for the
     * tracker's `init` call.
     *
     * The 2px inset guard matters: a box exactly on the frame edge
     * produces a zero-width rect after rounding on some sizes, and
     * OpenCV's `init` then throws rather than returning false.
     */
    fun toSourceRect(norm: RectF, widthPx: Int, heightPx: Int): Rect {
        val w = max(widthPx, 2)
        val h = max(heightPx, 2)
        val left = (norm.left * w).roundToInt().coerceIn(0, w - 2)
        val top = (norm.top * h).roundToInt().coerceIn(0, h - 2)
        val right = (norm.right * w).roundToInt().coerceIn(left + 2, w)
        val bottom = (norm.bottom * h).roundToInt().coerceIn(top + 2, h)
        return Rect(left, top, right, bottom)
    }

    /** Convert a source-pixel rect back to normalized coordinates. */
    fun toNormalizedRect(px: Rect, widthPx: Int, heightPx: Int): RectF {
        if (widthPx <= 0 || heightPx <= 0) return DEFAULT_ROI
        return RectF(
            px.left.toFloat() / widthPx,
            px.top.toFloat() / heightPx,
            px.right.toFloat() / widthPx,
            px.bottom.toFloat() / heightPx
        ).let(::normalizeEdges)
    }

    // ------------------------------------------------------------------
    // Box manipulation
    // ------------------------------------------------------------------

    /**
     * A normalized 0..1 box as **four plain floats**.
     *
     * ## Why this exists alongside `RectF`
     *
     * `android.graphics.RectF` is an Android-runtime class. This project
     * sets `unitTests.isReturnDefaultValues = true`, under which every
     * `android.*` method returns `0` / `0f` — so a `RectF` created in a
     * JVM unit test reads back as `(0,0,0,0)` and **every box assertion
     * silently passes or fails for the wrong reason**. All the box
     * arithmetic therefore lives here on plain floats and is
     * JVM-testable, while [RectF] overloads below remain the currency
     * at the tracker/UI boundary, where an Android runtime is present.
     *
     * Reads are by construction (there are no derived accessors that
     * could disagree), but the class is still a named type rather than a
     * bare 4-tuple so a `width`/`height` parameter pair cannot be passed
     * in the wrong order.
     */
    data class NormBox(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
    }

    /** [RectF] -> [NormBox]. */
    fun of(r: RectF): NormBox = NormBox(r.left, r.top, r.right, r.bottom)

    /** [NormBox] -> [RectF]. */
    fun toRectF(b: NormBox): RectF = RectF(b.left, b.top, b.right, b.bottom)

    /** A centred default ROI — 30% of the frame, in the middle. */
    val DEFAULT_ROI: RectF = RectF(0.35f, 0.35f, 0.65f, 0.65f)

    /** [DEFAULT_ROI] in the pure representation. */
    val DEFAULT_NORM_BOX: NormBox = NormBox(0.35f, 0.35f, 0.65f, 0.65f)

    /** The degenerate box returned for unusable inputs. */
    val ZERO_BOX: NormBox = NormBox(0f, 0f, 0f, 0f)

    /**
     * True when [roi] is large enough to be worth tracking and lies
     * inside the frame. Rejecting up front is far better than emitting
     * a trajectory full of noise — the UI surfaces a Persian hint
     * instead.
     */
    fun isTrackable(roi: RectF): Boolean = isTrackable(of(roi))

    fun isTrackable(b: NormBox): Boolean =
        b.width >= MIN_ROI_SIDE && b.height >= MIN_ROI_SIDE &&
            b.left >= -EPS && b.top >= -EPS &&
            b.right <= 1f + EPS && b.bottom <= 1f + EPS

    /** Order the edges so `left <= right` and `top <= bottom`. */
    fun normalizeEdges(r: RectF): RectF = toRectF(normalizeEdges(of(r)))

    fun normalizeEdges(b: NormBox): NormBox = NormBox(
        min(b.left, b.right),
        min(b.top, b.bottom),
        max(b.left, b.right),
        max(b.top, b.bottom)
    )

    /**
     * Translate [roi] by a normalized delta, clamping so the box stays
     * entirely inside the frame. An unclamped box that walks off the
     * edge makes the attached sticker leave the screen, which users
     * read as a crash.
     */
    fun clampedTranslate(roi: RectF, dx: Float, dy: Float): RectF =
        toRectF(clampedTranslate(of(roi), dx, dy))

    fun clampedTranslate(b: NormBox, dx: Float, dy: Float): NormBox {
        val w = b.width.coerceAtLeast(0f)
        val h = b.height.coerceAtLeast(0f)
        val nl = (b.left + dx).coerceIn(0f, max(0f, 1f - w))
        val nt = (b.top + dy).coerceIn(0f, max(0f, 1f - h))
        return NormBox(nl, nt, nl + w, nt + h)
    }

    /**
     * Clamp an arbitrary ROI into the unit square while preserving its
     * size where possible. Used on every tracker output before it
     * becomes a keyframe.
     */
    fun clampNorm(roi: RectF): RectF = toRectF(clampNorm(of(roi)))

    fun clampNorm(b: NormBox): NormBox {
        val r = normalizeEdges(b)
        val w = r.width.coerceIn(0f, 1f)
        val h = r.height.coerceIn(0f, 1f)
        val left = r.left.coerceIn(0f, max(0f, 1f - w))
        val top = r.top.coerceIn(0f, max(0f, 1f - h))
        return NormBox(left, top, left + w, top + h)
    }

    /**
     * Grow [roi] by [factor] about its centre, clamped to the frame.
     *
     * This produces the tracker's **search window**. A crop grown by
     * ~1.6x gives the correlation filter enough context to survive the
     * target moving between samples, while staying far smaller than
     * the full frame — which is what keeps the per-frame cost at
     * ~3-6 ms instead of the 15-25 ms a full-frame update costs.
     */
    fun expand(roi: RectF, factor: Float): RectF = toRectF(expand(of(roi), factor))

    fun expand(b: NormBox, factor: Float): NormBox {
        val r = normalizeEdges(b)
        val hw = r.width / 2f * factor
        val hh = r.height / 2f * factor
        return NormBox(
            (r.centerX - hw).coerceIn(0f, 1f),
            (r.centerY - hh).coerceIn(0f, 1f),
            (r.centerX + hw).coerceIn(0f, 1f),
            (r.centerY + hh).coerceIn(0f, 1f)
        )
    }

    /**
     * Cap how far a box may scale in one step.
     *
     * CSRT can jump scale by 2x in a single frame during a partial
     * occlusion. Smoothing cannot undo that because it is a *shrink*
     * of an already-wrong measurement; a hard per-step cap prevents the
     * irreversible blown-up state that no later frame can recover from.
     */
    fun capScaleStep(previous: RectF, current: RectF, maxRatio: Float = 1.15f): RectF =
        toRectF(capScaleStep(of(previous), of(current), maxRatio))

    fun capScaleStep(previous: NormBox, current: NormBox, maxRatio: Float = 1.15f): NormBox {
        val pw = previous.width.coerceAtLeast(EPS)
        val ph = previous.height.coerceAtLeast(EPS)
        val cw = current.width.coerceAtLeast(EPS)
        val ch = current.height.coerceAtLeast(EPS)

        val ratioW = (cw / pw).coerceIn(1f / maxRatio, maxRatio)
        val ratioH = (ch / ph).coerceIn(1f / maxRatio, maxRatio)
        val nw = pw * ratioW
        val nh = ph * ratioH

        return NormBox(
            (current.centerX - nw / 2f).coerceIn(0f, max(0f, 1f - nw)),
            (current.centerY - nh / 2f).coerceIn(0f, max(0f, 1f - nh)),
            (current.centerX + nw / 2f).coerceIn(nw, 1f),
            (current.centerY + nh / 2f).coerceIn(nh, 1f)
        )
    }

    // ------------------------------------------------------------------
    // Trajectory
    // ------------------------------------------------------------------

    /**
     * Linearly interpolate a trajectory at [tsMs], including the extent
     * and confidence channels.
     *
     * ## Why extent is not interpolated across a tracking gap
     *
     * Between two *found* samples, lerping the box is correct — the
     * subject really did move smoothly. Across a gap, both endpoints
     * are forward-filled placeholders, and lerping between them would
     * **invent a smooth motion through an occlusion that never
     * happened**, producing a sticker that glides along a path the
     * subject never took. So the last real box is held instead, and
     * [CropKeyframe.isFound] carries the truth to the consumer, which
     * fades the overlay out.
     *
     * Binary search rather than a linear scan: this runs once per
     * displayed or exported frame, and a 60 s clip carries ~1800
     * entries at 30 fps. The list is time-ordered by construction.
     */
    fun interpolateKeyframe(keys: List<CropKeyframe>, tsMs: Long): CropKeyframe {
        if (keys.isEmpty()) return CropKeyframe(0L, 0.5f, 0.5f)
        if (tsMs <= keys.first().timeMs) return keys.first()
        if (tsMs >= keys.last().timeMs) return keys.last()

        var lo = 0
        var hi = keys.size - 1
        while (lo + 1 < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[mid].timeMs <= tsMs) lo = mid else hi = mid
        }
        val a = keys[lo]
        val b = keys[hi]
        val span = (b.timeMs - a.timeMs).coerceAtLeast(1L)
        val f = ((tsMs - a.timeMs).toFloat() / span).coerceIn(0f, 1f)
        val bothFound = a.isFound && b.isFound

        return CropKeyframe(
            timeMs = tsMs,
            centerX = a.centerX + (b.centerX - a.centerX) * f,
            centerY = a.centerY + (b.centerY - a.centerY) * f,
            zoom = a.zoom + (b.zoom - a.zoom) * f,
            width = lerpNullable(a.width, b.width, f, bothFound),
            height = lerpNullable(a.height, b.height, f, bothFound),
            confidence = a.confidence + (b.confidence - a.confidence) * f,
            isFound = bothFound
        )
    }

    /**
     * Interpolate two nullable extents.
     *
     * Returns `null` when **either** side lacks an extent, and holds
     * `a` when [found] is false (hold across a tracking gap).
     *
     * The `null`-wins rule is load-bearing, not defensive. An Auto
     * Frame keyframe carries no extent; if a Motion Track entry were
     * lerped against it, returning `b` would **fabricate a box for a
     * trajectory that never had one** and the Auto Frame reader would
     * silently start seeing a tracked box. Since the whole reason
     * `CropKeyframe` was widened additively (rather than forked) is to
     * keep the two producers mutually invisible, the only correct
     * answer when the two disagree about having an extent is "no
     * extent".
     */
    private fun lerpNullable(a: Float?, b: Float?, f: Float, found: Boolean): Float? {
        if (a == null || b == null) return null
        if (!found) return a
        return a + (b - a) * f
    }

    /**
     * True when the step from [prev] to [next] is too large to be real
     * motion — i.e. it is a re-acquisition.
     *
     * Smoothing cannot fix a discontinuity: a 0.3 -> 0.7 jump spread
     * over a few frames is a fast swoop, not a smooth move. The fix
     * belongs in the consumer as an alpha ramp, and this predicate is
     * how the consumer finds the boundary to ramp across. See
     * [MotionTrajectorySmoothing.reanchorAlpha].
     */
    fun isDiscontinuity(prev: CropKeyframe, next: CropKeyframe): Boolean {
        val dtMs = (next.timeMs - prev.timeMs).coerceAtLeast(1L)
        val dx = next.centerX - prev.centerX
        val dy = next.centerY - prev.centerY
        val dist = sqrt(dx * dx + dy * dy)
        return (dist / dtMs * 100f) > DISCONTINUITY_MAX_STEP_PER_100MS
    }

    /**
     * Convert a tracker confidence into an overlay alpha.
     *
     * A not-found entry fades out rather than disappearing: an abrupt
     * disappearance and re-appearance is the single most noticeable
     * artefact of a tracked overlay, and a short ramp hides both the
     * loss and the re-acquisition.
     */
    fun confidenceAlpha(confidence: Float, isFound: Boolean): Float {
        if (!isFound) return 0f
        val c = confidence.coerceIn(0f, 1f)
        // Below the "probably lost" line, ramp rather than cut.
        return if (c >= LOST_CONFIDENCE) 1f
        else (c / LOST_CONFIDENCE).coerceIn(0f, 1f)
    }

    /** Confidence below which an overlay begins to fade. */
    const val LOST_CONFIDENCE: Float = 0.45f

    private const val EPS = 1e-4f
}
