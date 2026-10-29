package com.aicreatorstudio.app.model

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The rectangle the source video occupies inside the canvas, in canvas pixels.
 * Fractional, because the fitted size rarely lands on whole pixels.
 */
data class LetterboxRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** A point in canvas pixel space, with **y increasing downwards** (bitmap convention). */
data class CanvasPoint(val x: Float, val y: Float)

/**
 * Pure geometry and plate generation for [CanvasBackground] — the "canvas
 * letterbox" fill colour.
 *
 * ## Why this exists
 *
 * `CanvasBackground` was the last write-only field. The **preview already
 * renders it** (`EditorScreen` paints `letterboxColor` as the canvas `Box`'s
 * background, behind the video), but the export ignored it outright —
 * `VideoExportEngine.buildEditedMediaItem` carried a
 * `@Suppress("UNUSED_PARAMETER") canvasBackground`. So a project with a
 * coloured letterbox exported with black bars. `CanvasBackground.Black` is the
 * engine's native behaviour and was already correct; this file exists for
 * [CanvasBackground.SolidColor].
 *
 * ## Painting *over* the bars is the same as painting *behind* the video
 *
 * The obvious implementation — a background layer the video is drawn on top of
 * — is not reachable: Media3's `Presentation` is the only thing that creates
 * the bars and it has no background-colour option, and `OverlayEffect` can only
 * composite *over* a frame. That turns out not to matter, because the two are
 * equivalent here. Media3's overlay blend is straight (non-premultiplied)
 * alpha:
 *
 *     out.rgb = overlay.rgb * a + video.rgb * (1 - a)
 *
 * So a plate that is **opaque in the bar region and fully transparent over the
 * video** leaves the video untouched (`a = 0`) and replaces the bars outright
 * (`a = 1`). That holds whatever the bars actually contain — Media3 documents
 * them as "black, with alpha = 0", but a full-alpha overlay would cover
 * edge-extended or opaque-black bars identically, so this does not depend on
 * that detail.
 *
 * ## The hole has to be the **posed** silhouette, not the fitted rectangle
 *
 * The preview puts the letterbox on the **canvas `Box`** and the per-clip pose
 * on the **foreground `PlayerView` inside it** (`EditorScreen`'s
 * `graphicsLayer`, which is where the transition, the motion preset and the
 * Transform panel's pan/zoom are composed). The background is therefore
 * *static in canvas space* while the video moves over it — so a plate whose
 * hole sat at the un-posed [videoRect] would be wrong in both directions:
 *
 *  - a **zoom-in** (`scaleX/Y > 1`, or `ZOOM_OUT`'s 1.3 start) grows the video
 *    past the hole, and the plate would paint over the video's own edge;
 *  - a **zoom-out** (`ZOOM_IN` starts at 0.45) shrinks the video inside the
 *    hole, and the untouched ring would show through as black.
 *
 * Both are avoided by mapping the hole through the same pose the matrix effect
 * uses — see [posedCorners], which reproduces `TransitionGeometry.matrix`
 * exactly (the pose effect runs *after* the `Presentation`, so the frame it
 * transforms is the canvas and the aspect correction is `canvasW / canvasH`).
 *
 * ## Geometry
 *
 * The `Presentation` fits the frame it is given into the canvas with
 * `LAYOUT_SCALE_TO_FIT` (a centred *contain*), so the video rectangle is
 * `min(canvasW/iw, canvasH/ih)` scaled about the centre. Two inputs matter:
 *
 *  - **`FillMode.FILL`** maps to `LAYOUT_SCALE_TO_FIT_WITH_CROP` (a *cover*),
 *    which by definition leaves no uncovered area — so [videoRect] reports the
 *    full canvas and the plate is empty *at the identity pose*. It is still
 *    built, because a shrinking pose re-exposes bars under a cover fit exactly
 *    as it does under a contain fit.
 *  - **The frame the `Presentation` receives is not always the source.** When
 *    the clip carries a user rotation, step 1's `ScaleAndRotateTransformation`
 *    grows the frame to the rotated bounding box first, so the aspect the
 *    canvas is fitted against is the *rotated* one. [rotatedBounds] computes
 *    it, and agrees with Media3's own `configure` — which derives the same
 *    extents by transforming the NDC corners through the aspect-corrected
 *    rotation matrix. Checked at 0deg, 45deg and 90deg.
 *
 * At the identity pose the bars only ever appear on one axis (a contain-fit
 * touches one axis exactly and falls short on the other), so the hole is
 * centred and axis-aligned. Under a pose it is a general rotated rectangle,
 * which is why the rasteriser below is a scanline one rather than a
 * rectangle test.
 *
 * ## Why the plate is full canvas resolution, and how it stays cheap
 *
 * The plate's interesting feature is a **hard edge** at the bar boundary. That
 * is the opposite of the vignette, whose signal is a smooth gradient and which
 * is therefore generated at a quarter resolution
 * ([VignettePlate.PLATE_DIVISOR]). A downscaled letterbox plate would put a
 * multi-pixel gradient where the preview draws a crisp Compose edge, and would
 * quantise the boundary away from the video's own edge — leaving a visible
 * sliver of the wrong colour. So this plate is generated 1:1 and drawn at the
 * overlay's default `scale = (1,1)`, which makes the boundary exactly one
 * canvas pixel.
 *
 * That would be far too slow done per pixel: a canvas-resolution buffer is
 * ~2.1 million pixels at 1080x1920, and an inverse-transform test per pixel is
 * tens of millions of scalar float ops per frame — tens of milliseconds in
 * ART, which is more than a 30fps frame budget on its own. The silhouette is a
 * **convex quadrilateral**, though, so [rasterise] intersects its four edges
 * with each row's centre line once (O(height), ~20 flops per row) and memfills
 * the pixels whose centres fall inside. The per-frame cost is one memfill of
 * the buffer plus a few thousand flops, and the caller caches on the pose so a
 * static clip pays it once.
 *
 * ## Not handled here: [CanvasBackground.Blur]
 *
 * The blurred fill is a *second video layer* — the preview renders a second
 * `PlayerView` with `RESIZE_MODE_ZOOM` and `Modifier.blur(36.dp)`. Reproducing
 * it in the export needs a `Composition` carrying two sequences and a
 * `VideoCompositor`, which is a change to the timeline model rather than a
 * field. Deliberately out of scope here and reported as such.
 */
object CanvasLetterbox {

    /** Sub-pixel slack: if the video covers the canvas to within half a pixel there is nothing to paint. */
    const val COVER_EPSILON = 0.5f

    /**
     * `0xFF000000` — Media3's native bar colour, and what a fade washes
     * toward when there is no plate. Written as a literal rather than
     * `android.graphics.Color.BLACK` so this file stays free of Android types
     * and remains a plain JVM unit test.
     */
    private const val BLACK_ARGB: Int = 0xFF000000.toInt()

    /** Below this a colour is treated as "no background". */
    private const val ALPHA_EPSILON = 0

    /**
     * Quad area below which the silhouette counts as collapsed to a line, so
     * the plate is fully opaque. Reachable if a transition scales a clip to
     * zero; `ClipMotion.compose` normalises a non-positive scale back to `1f`,
     * but a raw [TransitionPose] need not.
     */
    private const val DEGENERATE_AREA = 1e-3f

    /**
     * True when [background] needs a plate at all. Keyed on the *kind* and on
     * the colour being visible; [CanvasBackground.Black] is already the
     * engine's native bar colour, so it needs no pass.
     */
    fun isActive(background: CanvasBackground): Boolean = when (background) {
        is CanvasBackground.Black -> false
        is CanvasBackground.Blur -> false
        is CanvasBackground.SolidColor -> alphaOf(background.colorValue) > ALPHA_EPSILON
    }

    /** Alpha byte of an `0xAARRGGBB` value. */
    fun alphaOf(colorValue: Long): Int = ((colorValue ushr 24) and 0xFFL).toInt()

    /**
     * The colour a clip fades toward — i.e. the colour of the canvas behind it
     * — as an **opaque** ARGB int.
     *
     * The preview fades a clip by compositing it over the canvas at a reduced
     * alpha (`EditorScreen`'s `graphicsLayer { alpha = … }` over the letterbox
     * `Box`), so the colour it dims toward is the letterbox colour. An MP4 has
     * no alpha channel, so the export reproduces the same picture with a
     * full-frame wash instead — and this is the colour that wash must be.
     * Passing black regardless, which the export used to do, is exact on a
     * black canvas and visibly wrong on a [CanvasBackground.SolidColor] one:
     * the clip dimmed toward black while the bars beside it stayed coloured.
     *
     * Opaque for the same reason [rasterise] forces opacity: a letterbox is a
     * fill, not a tint, and the panel's swatches are opaque. A colour whose own
     * alpha is zero is not a background at all ([isActive] reports `false`), so
     * the bars stay Media3's native black and this returns black to match.
     *
     * [CanvasBackground.Blur] also returns black. The blurred fill is not baked
     * by the export (see the class KDoc), so the bars a fade would wash toward
     * genuinely *are* black in the file; returning anything else would make the
     * fade disagree with the bars right next to it.
     */
    fun fadeColorArgb(background: CanvasBackground): Int = when (background) {
        is CanvasBackground.SolidColor ->
            if (isActive(background)) {
                (background.colorValue and 0x00FFFFFFL).toInt() or (0xFF shl 24)
            } else {
                BLACK_ARGB
            }
        is CanvasBackground.Black, is CanvasBackground.Blur -> BLACK_ARGB
    }

    /**
     * The axis-aligned bounds of a `sourceWidth x sourceHeight` rectangle
     * rotated by [rotationDegrees]. Independent of the sign of the angle, which
     * is what makes it correct across the whole `-180..180` slider range —
     * Media3's own `configure` computes a *signed* extent and would return a
     * negative size past 90deg.
     */
    fun rotatedBounds(
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Float
    ): Pair<Float, Float> {
        val radians = if (rotationDegrees.isFinite()) {
            Math.toRadians(rotationDegrees.toDouble())
        } else {
            0.0
        }
        val c = abs(cos(radians)).toFloat()
        val s = abs(sin(radians)).toFloat()
        val w = sourceWidth.toFloat()
        val h = sourceHeight.toFloat()
        return (w * c + h * s) to (w * s + h * c)
    }

    /**
     * The region the video occupies inside a `canvasWidth x canvasHeight`
     * canvas **before any pose**, or `null` when the inputs are degenerate.
     *
     * Note this does *not* return `null` for a video that fills the canvas.
     * That used to be the "no bars, nothing to paint" signal, and it is wrong
     * once a pose exists: a clip whose source matches the canvas aspect has no
     * bars at rest, but `ZOOM_IN` immediately shrinks it to 45% and exposes
     * them. Whether bars are actually visible is decided on the posed
     * silhouette, by [coversCanvas].
     */
    fun videoRect(
        canvasWidth: Int,
        canvasHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Float,
        fillMode: FillMode
    ): LetterboxRect? {
        if (canvasWidth <= 0 || canvasHeight <= 0) return null
        val canvasW = canvasWidth.toFloat()
        val canvasH = canvasHeight.toFloat()
        // FILL is a cover: every canvas pixel is video, until a pose moves it.
        if (fillMode == FillMode.FILL) return LetterboxRect(0f, 0f, canvasW, canvasH)
        if (sourceWidth <= 0 || sourceHeight <= 0) return null

        val (inputWidth, inputHeight) = rotatedBounds(sourceWidth, sourceHeight, rotationDegrees)
        if (inputWidth <= 0f || inputHeight <= 0f) return null

        // Centred contain-fit, mirroring Presentation's LAYOUT_SCALE_TO_FIT.
        val scale = min(canvasW / inputWidth, canvasH / inputHeight)
        val videoWidth = inputWidth * scale
        val videoHeight = inputHeight * scale

        val left = (canvasW - videoWidth) / 2f
        val top = (canvasH - videoHeight) / 2f
        return LetterboxRect(left, top, left + videoWidth, top + videoHeight)
    }

    /**
     * Map [rect] through [pose], giving the video's silhouette in canvas pixels
     * as four points in the rect's own corner order (top-left, top-right,
     * bottom-right, bottom-left).
     *
     * This reproduces `TransitionGeometry.matrix` term for term, because the
     * plate has to agree with the matrix the pose effect bakes — a mismatch
     * would show up as a seam between the painted bar and the video's edge. The
     * matrix composes `A^-1 * T * Rz * S * A` with `A = diag(aspect, 1, 1)`, so
     * in NDC the mapping is
     *
     *     u' =  cos(theta)*sx*u - sin(theta)*sy/a*v + ndcX
     *     v' =  sin(theta)*sx*a*u + cos(theta)*sy*v + ndcY
     *
     * where `a` is the frame aspect, `theta` is the pose's Z rotation in NDC's
     * counter-clockwise sense (hence the negation of the screen-convention
     * [TransitionPose.rotationZDeg]), and `ndcY` is negated because NDC is
     * Y-up while [CanvasPoint] is Y-down.
     *
     * Only the Z rotation is read. The preview's `graphicsLayer` sets
     * `rotationZ` alone and never `rotationX`, so honouring a tilt here would
     * make the export disagree with the screen — the same reasoning
     * `TransitionGeometry.matrix`'s callers use.
     */
    fun posedCorners(
        rect: LetterboxRect,
        canvasWidth: Int,
        canvasHeight: Int,
        pose: TransitionPose
    ): List<CanvasPoint> {
        val canvasW = canvasWidth.toFloat()
        val canvasH = canvasHeight.toFloat()
        val aspect = if (canvasHeight > 0 && canvasWidth > 0) canvasW / canvasH else 1f

        // Every input is sanitised rather than trusted, because a non-finite
        // one does not fail loudly here — it fails *invisibly*. A NaN corner
        // makes `contains` report "outside" for every point, so `coversCanvas`
        // says no bars, and makes `rowSpan` report no crossing for every row,
        // so the plate comes out **fully opaque**: the whole frame painted in
        // the background colour with the video hidden underneath. Falling back
        // to the identity value degrades to the un-posed letterbox instead.
        val rotation = if (pose.rotationZDeg.isFinite()) pose.rotationZDeg else 0f
        val thetaZ = Math.toRadians((-rotation).toDouble())
        val c1 = cos(thetaZ).toFloat()
        val s1 = sin(thetaZ).toFloat()
        val sx = if (pose.scaleX.isFinite()) pose.scaleX else 1f
        val sy = if (pose.scaleY.isFinite()) pose.scaleY else 1f
        val ndcX = orZero(TransitionGeometry.translationFractionX(pose)) * 2f
        val ndcY = -orZero(TransitionGeometry.translationFractionY(pose)) * 2f

        fun map(x: Float, y: Float): CanvasPoint {
            // Canvas pixels -> NDC (Y up).
            val u = 2f * x / canvasW - 1f
            val v = 1f - 2f * y / canvasH
            val uOut = c1 * sx * u - s1 * sy / aspect * v + ndcX
            val vOut = s1 * sx * aspect * u + c1 * sy * v + ndcY
            // NDC -> canvas pixels (Y down).
            return CanvasPoint((uOut + 1f) * canvasW / 2f, (1f - vOut) * canvasH / 2f)
        }

        return listOf(
            map(rect.left, rect.top),
            map(rect.right, rect.top),
            map(rect.right, rect.bottom),
            map(rect.left, rect.bottom)
        )
    }

    /**
     * True when the posed silhouette swallows the whole canvas, so there is no
     * bar left to paint and the pass can be skipped.
     *
     * Exact rather than sampled: the quad is convex and so is the canvas, so
     * testing the canvas's four corners for containment is equivalent to
     * testing the whole canvas. A collapsed quad reports `false` — the video
     * has vanished, which means the plate must cover everything, not nothing.
     */
    fun coversCanvas(quad: List<CanvasPoint>, canvasWidth: Int, canvasHeight: Int): Boolean {
        if (quad.size < 4) return false
        if (canvasWidth <= 0 || canvasHeight <= 0) return true
        if (quadArea(quad) < DEGENERATE_AREA) return false
        val w = canvasWidth.toFloat()
        val h = canvasHeight.toFloat()
        return contains(quad, 0f, 0f) &&
            contains(quad, w, 0f) &&
            contains(quad, w, h) &&
            contains(quad, 0f, h)
    }

    /**
     * Whether there is a bar to paint at all, given a canvas and a pose.
     *
     * Split out from [pixels] because the caller sometimes needs the *answer*
     * without the buffer: [com.aicreatorstudio.app.export.CanvasBackgroundOverlay]
     * uses it to decide whether a clip that can never move is worth an overlay
     * pass, and allocating a full-frame plate to find that out would defeat the
     * point. [pixels] delegates here, so the two cannot disagree.
     */
    fun needsPlate(
        canvasWidth: Int,
        canvasHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Float,
        fillMode: FillMode,
        pose: TransitionPose = TransitionPose()
    ): Boolean {
        if (canvasWidth <= 0 || canvasHeight <= 0) return false
        val rect = videoRect(
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            rotationDegrees = rotationDegrees,
            fillMode = fillMode
        ) ?: return false
        val quad = posedCorners(rect, canvasWidth, canvasHeight, pose)
        return !coversCanvas(quad, canvasWidth, canvasHeight)
    }

    /**
     * The plate as packed ARGB pixels, row-major from the top-left: the
     * background colour outside the posed video silhouette, fully transparent
     * inside it.
     *
     * Returns `null` when there is nothing to paint — either the inputs are
     * degenerate or the silhouette already covers the canvas — so the caller
     * can skip the pass rather than upload an empty plate.
     *
     * The colour is painted **fully opaque** regardless of the alpha in
     * [colorValue]. The panel's swatches are opaque, and a letterbox is a fill,
     * not a tint — honouring a partial alpha here would blend the colour
     * against Media3's transparent bars and come out darker than the preview's
     * `Color(colorValue)` over an opaque surface.
     *
     * @param pose defaults to the identity, which is the "no transition, no
     *   motion preset, no pan/zoom" case and the one a static clip always hits.
     */
    fun pixels(
        canvasWidth: Int,
        canvasHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Float,
        fillMode: FillMode,
        colorValue: Long,
        pose: TransitionPose = TransitionPose()
    ): IntArray? {
        val needed = needsPlate(
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            rotationDegrees = rotationDegrees,
            fillMode = fillMode,
            pose = pose
        )
        if (!needed) return null

        val rect = videoRect(
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            rotationDegrees = rotationDegrees,
            fillMode = fillMode
        ) ?: return null
        val quad = posedCorners(rect, canvasWidth, canvasHeight, pose)
        return rasterise(canvasWidth, canvasHeight, quad, colorValue)
    }

    /**
     * Paint the complement of [quad] at canvas resolution, one row at a time.
     *
     * Each row intersects the quad's four edges with the row's **centre line**
     * to get an x-span, then memfills the pixels whose centres fall inside it.
     * Both axes therefore use the pixel-centre rule — the same rule OpenGL uses
     * to rasterise the video's own geometry — which is the whole point: it makes
     * the plate's boundary land on the *same* pixels as the edge of the video
     * underneath, so there is neither a sliver of un-painted bar nor a fringe of
     * colour over the picture.
     *
     * That is why the mask is **binary** rather than anti-aliased. A coverage
     * ramp here would be *worse*, not better: the video's edge is rasterised
     * with one sample per pixel, so a half-transparent mask pixel would show as
     * a 50% colour wash straddling an edge that is actually hard. Matching the
     * rule is what keeps the two coincident — including on a rotated silhouette,
     * where both sides produce the identical staircase.
     *
     * Cost is O(height) edge intersections plus one memfill per row, with no
     * per-pixel arithmetic — see the class KDoc for why that matters.
     */
    private fun rasterise(
        canvasWidth: Int,
        canvasHeight: Int,
        quad: List<CanvasPoint>,
        colorValue: Long
    ): IntArray {
        val rgb = (colorValue and 0x00FFFFFFL).toInt()
        val opaque = rgb or (0xFF shl 24)
        val out = IntArray(canvasWidth * canvasHeight)
        java.util.Arrays.fill(out, opaque)

        for (y in 0 until canvasHeight) {
            // Null means the row's centre line misses the quad entirely, so the
            // row is bar and keeps the colour it was filled with.
            val span = rowSpan(quad, y + 0.5f) ?: continue
            val xMin = span.first
            val xMax = span.second
            if (xMax <= xMin) continue

            // Pixels whose centre (x + 0.5) lies within [xMin, xMax).
            //
            // Half-open on the far edge, matching the "top-left" fill rule
            // OpenGL uses and the half-open edge crossing [rowSpan] applies
            // vertically. Both axes therefore treat a pixel centre that lands
            // *exactly* on the silhouette's far edge as outside, which is what
            // the video's own rasterisation does. The two only ever disagree
            // when that coordinate is exactly an integer, so this costs nothing
            // in the general case and removes an asymmetry between the axes.
            val first = ceil(xMin - 0.5f).toInt()
            val last = ceil(xMax - 0.5f).toInt() - 1
            val from = first.coerceAtLeast(0)
            val to = last.coerceAtMost(canvasWidth - 1)
            if (to >= from) {
                val row = y * canvasWidth
                java.util.Arrays.fill(out, row + from, row + to + 1, 0)
            }
        }
        return out
    }

    /** Horizontal extent of a convex quad on the line `y = yc`, or `null` if it misses. */
    private fun rowSpan(quad: List<CanvasPoint>, yc: Float): Pair<Float, Float>? {
        var xMin = Float.MAX_VALUE
        var xMax = -Float.MAX_VALUE
        var hits = 0
        for (i in 0 until 4) {
            val p = quad[i]
            val q = quad[(i + 1) % 4]
            // Half-open test, so a vertex exactly on the line is counted once.
            if ((p.y <= yc) == (q.y <= yc)) continue
            val t = (yc - p.y) / (q.y - p.y)
            val x = p.x + t * (q.x - p.x)
            if (x < xMin) xMin = x
            if (x > xMax) xMax = x
            hits++
        }
        return if (hits >= 2) xMin to xMax else null
    }

    /** A non-finite value reads as zero, so a bad clock cannot poison the geometry. */
    private fun orZero(value: Float): Float = if (value.isFinite()) value else 0f

    /** Shoelace area of a quad, absolute. */
    private fun quadArea(quad: List<CanvasPoint>): Float {
        var sum = 0f
        for (i in 0 until 4) {
            val p = quad[i]
            val q = quad[(i + 1) % 4]
            sum += p.x * q.y - q.x * p.y
        }
        return abs(sum) / 2f
    }

    /**
     * Containment for a convex quad in corner order: a point is inside when it
     * falls on the same side of all four directed edges. Degenerate quads (all
     * cross products zero) count as *not* containing anything, which is what
     * [coversCanvas] needs — a collapsed silhouette must leave the plate
     * opaque rather than skip it.
     */
    private fun contains(quad: List<CanvasPoint>, px: Float, py: Float): Boolean {
        var positive = false
        var negative = false
        for (i in 0 until 4) {
            val p = quad[i]
            val q = quad[(i + 1) % 4]
            val cross = (q.x - p.x) * (py - p.y) - (q.y - p.y) * (px - p.x)
            if (cross > 0f) positive = true
            if (cross < 0f) negative = true
        }
        if (!positive && !negative) return false
        return !(positive && negative)
    }
}
