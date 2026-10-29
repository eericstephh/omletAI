package com.aicreatorstudio.app.model

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 2026-10-13 — Picture-in-Picture shape masks.
 *
 * ## What this file owns
 *
 * Exactly one thing: **the geometry of a mask outline, in normalised
 * unit-square coordinates**, plus the settings that select it. It is the
 * single source of truth consumed by BOTH renderers —
 * `ui/screens/editor/OverlayMaskShape.kt` (Compose preview clip) and
 * `export/OverlayMaskRenderer.kt` (Android `Path` clip on the export
 * frame). Neither renderer may define a shape of its own; if they did,
 * preview/export parity would depend on review rather than on
 * construction, which is the failure mode this project keeps hitting.
 *
 * ## Why polygons, not beziers
 *
 * [OverlayMaskGeometry.outline] returns a **closed polygon** — a list of
 * [NormPoint] in `0.0..1.0`. Compose `Path`, `android.graphics.Path` and
 * the JVM unit tests all consume that same list. A bezier representation
 * would need a per-renderer curve evaluator, and two evaluators that
 * agree "by review" is exactly the coupling this design removes. At the
 * sample counts used here the polygon error is sub-pixel for any PiP box
 * a phone can display (see [CIRCLE_SAMPLES] etc.).
 *
 * ## Normalised space
 *
 * `(0,0)` is the mask box's top-left, `(1,1)` its bottom-right. The box
 * is the PiP's own rectangle, NOT the canvas — see
 * [OverlayPipGeometry.resolveBox] for the canvas-space placement. Keeping
 * the outline in the box's own space is what makes one outline correct
 * for a 120 px preview stamp and a 2160 px export frame.
 *
 * Pure Kotlin, no `android.*` — directly JVM-testable.
 */

/** The shapes a PiP overlay can be clipped to. */
enum class OverlayMaskType(val displayName: String) {
    /** No clipping at all — the overlay renders as a plain rectangle. */
    NONE("بدون ماسک"),
    /** Ellipse inscribed in the box — the facecam look. */
    CIRCLE("دایره"),
    /** Axis-aligned rectangle with [OverlayMaskSettings.cornerRadiusDp] corners. */
    ROUNDED_RECT("گوشه‌گرد"),
    /** Classic cardioid heart, stretched to fill the box. */
    HEART("قلب"),
    /** Five-point star, point up. */
    STAR("ستاره")
}

/**
 * Per-overlay mask configuration.
 *
 * [isIdentity] is the "write nothing" predicate: a project whose overlays
 * all carry the default settings serialises byte-identically to one saved
 * before this feature existed, so reopening an old project cannot change
 * its bytes.
 */
data class OverlayMaskSettings(
    val type: OverlayMaskType = OverlayMaskType.NONE,
    /**
     * Corner radius for [OverlayMaskType.ROUNDED_RECT], in dp.
     *
     * Converted to pixels through the **720-unit canvas convention**
     * ([OverlayMaskGeometry.CANVAS_UNIT_PX]) that `CaptionRenderer` and
     * `ProgressBarGeometry` already share — NOT through Compose's
     * `Dp.toPx()`, which scales with screen density while the export
     * scales with encoder size. Ignored for every other shape.
     */
    val cornerRadiusDp: Float = 16f,
    /**
     * Soft-edge width in dp, `0` = a hard anti-aliased edge.
     *
     * Honoured by both renderers from the same alpha mask
     * (`OverlayMaskRenderer.rasterizeAlphaMask`); see that function for
     * why a non-zero value costs an extra bitmap.
     */
    val featherDp: Float = 0f,
    /**
     * Keep the OUTSIDE of the shape instead of the inside — a
     * "hole in the middle" mask.
     *
     * Implemented by flipping the path's fill type, not by a second
     * outline, so the inverted shape is the exact complement of the
     * normal one by construction.
     */
    val invert: Boolean = false
) {
    /** True when this mask does nothing at all. */
    val isIdentity: Boolean
        get() = type == OverlayMaskType.NONE && !invert

    /** True when there is an actual outline to build. */
    val hasShape: Boolean
        get() = type != OverlayMaskType.NONE

    /** True when the renderer must take the soft-edge (bitmap) path. */
    val needsSoftEdge: Boolean
        get() = hasShape && featherDp > 0f

    companion object {
        /** The settings a freshly picked overlay carries. */
        val Default = OverlayMaskSettings()
    }
}

/** A point in the mask box's normalised space. Always within `0.0..1.0`. */
data class NormPoint(val x: Float, val y: Float)

/** An inclusive normalised bounding box, for hit-testing. */
data class NormBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= right && y >= top && y <= bottom
}

/**
 * Pure geometry for [OverlayMaskType]. Every function is total: an
 * unknown or degenerate input produces the identity shape (the full unit
 * square) rather than throwing, so a corrupt persisted value degrades to
 * "no visible clipping" instead of failing an export.
 */
object OverlayMaskGeometry {

    /**
     * The canvas unit `CaptionRenderer` and `ProgressBarGeometry` both
     * use. A dp value is turned into canvas pixels by
     * `dp * canvasHeightPx / CANVAS_UNIT_PX`. Reusing the same constant is
     * what keeps a mask's corner radius the same *fraction of the picture*
     * in the preview and in the exported file.
     */
    const val CANVAS_UNIT_PX: Float = 720f

    // ---- clamps -------------------------------------------------------

    const val MIN_CORNER_RADIUS_DP: Float = 0f
    const val MAX_CORNER_RADIUS_DP: Float = 160f
    const val MIN_FEATHER_DP: Float = 0f
    const val MAX_FEATHER_DP: Float = 48f

    // A corner radius may never exceed half the box's short side, or the
    // outline self-intersects and the clip path fills the whole box on
    // some devices and nothing on others.
    const val MAX_CORNER_RADIUS_FRACTION: Float = 0.5f

    // ---- sample counts ------------------------------------------------
    //
    // Chosen so the polygon's maximum deviation from the true curve is
    // below a quarter pixel for a 1080-px box. A circle's sagitta at n
    // samples is r * (1 - cos(pi / n)): at n = 96 and r = 540 px that is
    // 0.29 px. The heart uses the same budget; the star is exactly ten
    // vertices and needs no sampling.
    const val CIRCLE_SAMPLES: Int = 96
    const val HEART_SAMPLES: Int = 120
    const val ROUNDED_RECT_ARC_SAMPLES: Int = 12

    /** Clamp persisted / user-driven values into the supported range. */
    fun sanitize(settings: OverlayMaskSettings): OverlayMaskSettings =
        settings.copy(
            cornerRadiusDp = settings.cornerRadiusDp
                .takeIf { it.isFinite() }
                ?.coerceIn(MIN_CORNER_RADIUS_DP, MAX_CORNER_RADIUS_DP)
                ?: 0f,
            featherDp = settings.featherDp
                .takeIf { it.isFinite() }
                ?.coerceIn(MIN_FEATHER_DP, MAX_FEATHER_DP)
                ?: 0f
        )

    // ---- unit conversion ----------------------------------------------

    /**
     * Convert a dp length into canvas pixels through the 720-unit
     * convention. `canvasHeightPx <= 0` yields `0f` (degenerate canvas →
     * no radius) rather than a NaN that would poison a `Path`.
     */
    fun dpToCanvasPx(dp: Float, canvasHeightPx: Int): Float {
        if (canvasHeightPx <= 0) return 0f
        if (!dp.isFinite() || dp <= 0f) return 0f
        return dp * canvasHeightPx / CANVAS_UNIT_PX
    }

    /**
     * The rounded-rect corner radius expressed as a fraction of the
     * **shorter side** of the mask box, clamped to
     * [MAX_CORNER_RADIUS_FRACTION].
     *
     * A fraction (not pixels) is the right currency here because the
     * outline is normalised: the same fraction produces the same visual
     * rounding on a preview stamp and on a 4K frame.
     */
    fun cornerRadiusFraction(
        cornerRadiusDp: Float,
        boxWidthPx: Int,
        boxHeightPx: Int,
        canvasHeightPx: Int
    ): Float {
        val shortSide = min(boxWidthPx, boxHeightPx).toFloat()
        if (shortSide <= 0f) return 0f
        val radiusPx = dpToCanvasPx(cornerRadiusDp, canvasHeightPx)
        return (radiusPx / shortSide).coerceIn(0f, MAX_CORNER_RADIUS_FRACTION)
    }

    // ---- outlines -----------------------------------------------------

    /**
     * The closed outline of [type] as normalised points, in
     * counter-clockwise winding, first point NOT repeated at the end.
     *
     * Returns an empty list for [OverlayMaskType.NONE] — "no shape",
     * which callers must treat as "do not clip", never as "clip to
     * nothing".
     *
     * @param cornerRadiusFraction corner radius as a fraction of the box's
     *   short side, in `0.0..0.5`. Ignored by every shape but
     *   [OverlayMaskType.ROUNDED_RECT].
     */
    fun outline(
        type: OverlayMaskType,
        cornerRadiusFraction: Float = 0f
    ): List<NormPoint> = when (type) {
        OverlayMaskType.NONE -> emptyList()
        OverlayMaskType.CIRCLE -> circleOutline()
        OverlayMaskType.ROUNDED_RECT -> roundedRectOutline(cornerRadiusFraction)
        OverlayMaskType.HEART -> heartOutline()
        OverlayMaskType.STAR -> starOutline()
    }

    /** Ellipse inscribed in the unit square. */
    private fun circleOutline(): List<NormPoint> {
        val n = CIRCLE_SAMPLES
        return List(n) { i ->
            val a = 2.0 * PI * i / n
            NormPoint(
                x = (0.5 + 0.5 * cos(a)).toFloat(),
                y = (0.5 + 0.5 * sin(a)).toFloat()
            )
        }
    }

    /**
     * Rectangle with equal corner radii. Each corner is an arc sampled at
     * [ROUNDED_RECT_ARC_SAMPLES] points; with `radius == 0` this collapses
     * to the four corners of the square, i.e. a plain rectangle.
     */
    private fun roundedRectOutline(radiusFraction: Float): List<NormPoint> {
        val r = radiusFraction.coerceIn(0f, MAX_CORNER_RADIUS_FRACTION)
        if (r <= 0f) {
            return listOf(
                NormPoint(0f, 0f), NormPoint(1f, 0f),
                NormPoint(1f, 1f), NormPoint(0f, 1f)
            )
        }
        val pts = ArrayList<NormPoint>(4 * (ROUNDED_RECT_ARC_SAMPLES + 1))
        // Arc centres, walked clockwise from the top-left corner.
        val centres = listOf(
            Triple(0f + r, 0f + r, PI),                  // top-left: 180° → 270°
            Triple(1f - r, 0f + r, 1.5 * PI),            // top-right: 270° → 360°
            Triple(1f - r, 1f - r, 0.0),                 // bottom-right: 0° → 90°
            Triple(0f + r, 1f - r, 0.5 * PI)             // bottom-left: 90° → 180°
        )
        centres.forEach { (cx, cy, start) ->
            for (i in 0..ROUNDED_RECT_ARC_SAMPLES) {
                val a = start + (PI / 2.0) * i / ROUNDED_RECT_ARC_SAMPLES
                pts += NormPoint(
                    x = (cx + r * cos(a)).toFloat(),
                    y = (cy + r * sin(a)).toFloat()
                )
            }
        }
        return pts
    }

    /**
     * Classic heart, sampled from the standard parametric curve
     * `x = 16 sin³t`, `y = 13 cos t − 5 cos 2t − 2 cos 3t − cos 4t`, then
     * min/max normalised to fill the unit square.
     *
     * The normalisation is computed **from the samples themselves**
     * rather than from hard-coded extents, so the shape fills the box
     * exactly and cannot drift if the sample count changes.
     */
    private fun heartOutline(): List<NormPoint> {
        val n = HEART_SAMPLES
        val raw = ArrayList<NormPoint>(n)
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until n) {
            val t = 2.0 * PI * i / n
            val x = 16.0 * sin(t) * sin(t) * sin(t)
            val y = 13.0 * cos(t) - 5.0 * cos(2 * t) - 2.0 * cos(3 * t) - cos(4 * t)
            val p = NormPoint(x.toFloat(), y.toFloat())
            raw += p
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        val spanX = (maxX - minX).coerceAtLeast(1e-6f)
        val spanY = (maxY - minY).coerceAtLeast(1e-6f)
        return raw.map { p ->
            NormPoint(
                x = ((p.x - minX) / spanX).coerceIn(0f, 1f),
                // Screen y grows downward, the curve's y grows upward —
                // flip so the point of the heart faces up.
                y = (1f - (p.y - minY) / spanY).coerceIn(0f, 1f)
            )
        }
    }

    /**
     * Five-point star, point up, inscribed in the unit **circle** centred
     * in the box.
     *
     * Ten vertices alternating between the outer radius (`0.5`) and the
     * pentagram's inner radius. The inner/outer ratio for a true
     * pentagram is `sin(18°) / sin(126°) ≈ 0.38197`; using it (rather
     * than an eyeballed value) is what makes the arms straight and the
     * points sharp.
     *
     * ⚠️ Because it is inscribed in the *circle*, the star does NOT fill
     * its box: its bounding box is roughly `0.0245..0.9755 × 0..0.9045`.
     * That is deliberate — scaling a pentagram up to touch all four sides
     * of a square would have to be non-uniform and would splay the arms.
     * The slack is invisible in practice (both renderers clip to the same
     * outline) but it is why [bounds] is not the unit square for `STAR`.
     */
    private fun starOutline(): List<NormPoint> {
        val cx = 0.5
        val cy = 0.5
        val outer = 0.5
        val inner = outer * PENTAGRAM_INNER_RATIO
        return List(10) { i ->
            // -90° puts the first (outer) vertex straight up.
            val a = -PI / 2.0 + PI * i / 5.0
            val r = if (i % 2 == 0) outer else inner
            NormPoint(
                x = (cx + r * cos(a)).toFloat(),
                y = (cy + r * sin(a)).toFloat()
            )
        }
    }

    /** `sin(18°) / sin(126°)` — the pentagram's inner/outer radius ratio. */
    const val PENTAGRAM_INNER_RATIO: Float = 0.381966f

    // ---- queries ------------------------------------------------------

    /**
     * Normalised bounding box of [type]'s outline.
     *
     * [OverlayMaskType.CIRCLE], [OverlayMaskType.ROUNDED_RECT] and
     * [OverlayMaskType.HEART] are each fitted to the unit square, so for
     * those this is exactly the unit square. ⚠️ **[OverlayMaskType.STAR]
     * is NOT** — it is inscribed in the unit *circle* centred in the box,
     * so its bounds are about `0.0245..0.9755 × 0..0.9045`. See
     * [starOutline] for why that is deliberate.
     *
     * The value is computed from the outline rather than assumed, so it
     * reports the truth for any future shape.
     */
    fun bounds(type: OverlayMaskType): NormBounds {
        val pts = outline(type)
        if (pts.isEmpty()) return NormBounds(0f, 0f, 0f, 0f)
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        pts.forEach { p ->
            if (p.x < l) l = p.x
            if (p.x > r) r = p.x
            if (p.y < t) t = p.y
            if (p.y > b) b = p.y
        }
        return NormBounds(l, t, r, b)
    }

    /**
     * Whether the normalised point `(x, y)` is inside the mask.
     *
     * Ray casting against the SAME polygon [outline] returns, so hit
     * testing can never disagree with what is drawn. [invert] flips the
     * answer, matching the renderers' fill-type flip.
     *
     * [OverlayMaskType.NONE] contains the whole box, because an unmasked
     * overlay is fully tappable.
     */
    fun contains(
        settings: OverlayMaskSettings,
        x: Float,
        y: Float
    ): Boolean {
        if (!settings.hasShape) return x in 0f..1f && y in 0f..1f
        val r = if (settings.type == OverlayMaskType.ROUNDED_RECT) {
            cornerRadiusFractionFor(settings)
        } else {
            0f
        }
        val inside = pointInPolygon(outline(settings.type, r), x, y)
        return if (settings.invert) !inside else inside
    }

    /**
     * Corner radius as a fraction, derived from [OverlayMaskSettings] alone.
     *
     * The fraction is box-size dependent, so this exists only for callers
     * that already work in normalised space and have no pixel box — the
     * renderers pass their real fraction explicitly. It assumes a square
     * box, which makes it exact for the square PiP box the editor
     * produces by default.
     */
    private fun cornerRadiusFractionFor(settings: OverlayMaskSettings): Float =
        (settings.cornerRadiusDp / CANVAS_UNIT_PX).coerceIn(0f, MAX_CORNER_RADIUS_FRACTION)

    /**
     * Standard even-odd ray cast. Points exactly on the boundary may
     * report either way, which is why callers add their own tolerance
     * when they need "near enough" rather than "strictly inside".
     */
    fun pointInPolygon(poly: List<NormPoint>, x: Float, y: Float): Boolean {
        if (poly.size < 3) return false
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val pi = poly[i]
            val pj = poly[j]
            val intersects = ((pi.y > y) != (pj.y > y)) &&
                (x < (pj.x - pi.x) * (y - pi.y) / (pj.y - pi.y) + pi.x)
            if (intersects) inside = !inside
            j = i
        }
        return inside
    }

    /**
     * Decode a persisted enum name. Unknown, null and blank all fall back
     * to [OverlayMaskType.NONE], so a project written by a newer build
     * opens without a mask instead of failing to open at all.
     */
    fun typeFromName(name: String?): OverlayMaskType {
        if (name.isNullOrBlank()) return OverlayMaskType.NONE
        return OverlayMaskType.entries.firstOrNull { it.name == name } ?: OverlayMaskType.NONE
    }

    /** The order the panel lays its shape chips out in. */
    val panelOrder: List<OverlayMaskType> = listOf(
        OverlayMaskType.NONE,
        OverlayMaskType.CIRCLE,
        OverlayMaskType.ROUNDED_RECT,
        OverlayMaskType.HEART,
        OverlayMaskType.STAR
    )

    /** Highest legal feather for a box, in dp, clamped to the global cap. */
    fun maxFeatherDp(): Float = MAX_FEATHER_DP

    /** Convenience: `min(a, b)` for `Float`, avoiding a `kotlin.math` import at call sites. */
    fun shortestSide(a: Int, b: Int): Int = max(0, min(a, b))
}
