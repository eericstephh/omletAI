package com.aicreatorstudio.app.export

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.aicreatorstudio.app.model.OverlayMaskGeometry
import com.aicreatorstudio.app.model.OverlayMaskSettings

/**
 * 2026-10-13 — turns [OverlayMaskSettings] into an `android.graphics.Path`
 * and applies it to a frame.
 *
 * ## Parity contract
 *
 * This class builds its path from
 * [OverlayMaskGeometry.outline] — the SAME normalised polygon the Compose
 * preview consumes through `ui/screens/editor/OverlayMaskShape.kt`. There
 * is no shape definition in this file: only the normalised → pixel
 * mapping (`point.x * boxWidthPx`) and the fill-type flip for
 * [OverlayMaskSettings.invert]. Two renderers, one outline.
 *
 * ## Hard edge vs soft edge
 *
 * A zero feather is a plain `clipPath`, which is anti-aliased by the
 * rasterizer and costs nothing. A non-zero feather cannot be expressed as
 * a clip — a clip has no partial coverage — so it takes the bitmap route
 * in [applySoftMask]: render the overlay into a scratch layer, then
 * knock out the outside with a blurred `DST_IN` path. That allocates one
 * bitmap per frame, which is why [OverlayMaskSettings.needsSoftEdge]
 * gates it and the default is a hard edge.
 *
 * `BlurMaskFilter` only works on a **software** canvas. `Canvas(bitmap)`
 * is software, so the mask layer is fine; the hardware-accelerated canvas
 * that draws the final frame never sees a mask filter.
 */
internal object OverlayMaskRenderer {

    /**
     * The mask outline in **box-local pixels**: `(0,0)` is the mask box's
     * top-left, `(boxWidthPx, boxHeightPx)` its bottom-right.
     *
     * Returns `null` for a mask that does not clip anything — no shape, or
     * a degenerate box — which callers must read as "draw the overlay
     * unclipped", never as "draw nothing".
     *
     * @param canvasHeightPx the **output frame** height, not the box
     *   height. The corner radius is a dp length and dp→px goes through
     *   the 720-unit canvas convention, so it needs the canvas it is
     *   being laid out on. Passing the box height instead would make a
     *   small PiP's corners proportionally rounder than a large one's.
     */
    fun buildPath(
        settings: OverlayMaskSettings,
        boxWidthPx: Int,
        boxHeightPx: Int,
        canvasHeightPx: Int
    ): Path? {
        val s = OverlayMaskGeometry.sanitize(settings)
        if (!s.hasShape) return null
        if (boxWidthPx <= 0 || boxHeightPx <= 0) return null

        val radiusFraction = if (s.type == com.aicreatorstudio.app.model.OverlayMaskType.ROUNDED_RECT) {
            OverlayMaskGeometry.cornerRadiusFraction(
                cornerRadiusDp = s.cornerRadiusDp,
                boxWidthPx = boxWidthPx,
                boxHeightPx = boxHeightPx,
                canvasHeightPx = canvasHeightPx
            )
        } else {
            0f
        }

        val points = OverlayMaskGeometry.outline(s.type, radiusFraction)
        if (points.size < 3) return null

        val w = boxWidthPx.toFloat()
        val h = boxHeightPx.toFloat()
        val path = Path()
        points.forEachIndexed { index, p ->
            val x = p.x * w
            val y = p.y * h
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        // Inversion is a fill-type flip, so the inverted shape is the exact
        // complement of the normal one — not a second outline that could
        // drift out of agreement with the first.
        if (s.invert) path.fillType = Path.FillType.INVERSE_EVEN_ODD
        return path
    }

    /**
     * Knock the outside of [settings]'s shape out of [layer], **in
     * place**, with a blurred edge.
     *
     * `DST_IN` keeps `layer` where the drawn shape is opaque; `DST_OUT`
     * (the inverted case) removes it there. The blur is applied by the
     * paint's mask filter, so the coverage falls off across
     * `featherDp`-worth of pixels on both sides of the outline — the
     * outward half lands on already-transparent pixels, which makes the
     * visible result a purely inward feather.
     *
     * A no-op when there is no shape or no feather; safe to call
     * unconditionally.
     */
    fun applySoftMask(
        layer: Bitmap,
        settings: OverlayMaskSettings,
        canvasHeightPx: Int
    ) {
        val s = OverlayMaskGeometry.sanitize(settings)
        if (!s.hasShape) return
        val path = buildPath(s, layer.width, layer.height, canvasHeightPx) ?: return
        val featherPx = OverlayMaskGeometry.dpToCanvasPx(s.featherDp, canvasHeightPx)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(
                if (s.invert) PorterDuff.Mode.DST_OUT else PorterDuff.Mode.DST_IN
            )
            if (featherPx > 0.5f) {
                maskFilter = BlurMaskFilter(featherPx, BlurMaskFilter.Blur.NORMAL)
            }
        }
        Canvas(layer).drawPath(path, paint)
    }

    /**
     * Apply the shape mask directly to a full-size frame, in place —
     * the "mask the canvas itself" case (a letterbox cut-out, a hole in
     * the picture). Not used by the PiP compositor, which masks the
     * overlay layer instead, but kept here so every mask application in
     * the export goes through one implementation.
     */
    fun clipCanvasToMask(
        canvas: Canvas,
        settings: OverlayMaskSettings,
        leftPx: Float,
        topPx: Float,
        boxWidthPx: Int,
        boxHeightPx: Int,
        canvasHeightPx: Int
    ): Path? {
        val path = buildPath(settings, boxWidthPx, boxHeightPx, canvasHeightPx) ?: return null
        canvas.save()
        canvas.translate(leftPx, topPx)
        canvas.clipPath(path)
        return path
    }

    /** True when [settings] describes anything this renderer would do. */
    fun hasEffect(settings: OverlayMaskSettings): Boolean =
        OverlayMaskGeometry.sanitize(settings).hasShape

    /**
     * A standalone **alpha mask**: an ARGB bitmap that is opaque white
     * inside [settings]'s shape (soft-edged by `featherDp`) and fully
     * transparent outside it. Returns `null` for a mask with no shape.
     *
     * Exists so the Compose preview can feather with the *same* raster the
     * export does. The preview cannot use [applySoftMask] — that mutates a
     * rendered layer with a `PorterDuff` xfermode, which has no Compose
     * equivalent — but it can draw this bitmap with `BlendMode.DstIn`,
     * which composites identically. Same path, same blur radius, same
     * result.
     *
     * The bitmap is freshly allocated per call: it is cached by the caller
     * (the preview remembers it per box size), not by this object, which
     * is stateless on purpose.
     */
    fun rasterizeAlphaMask(
        settings: OverlayMaskSettings,
        widthPx: Int,
        heightPx: Int,
        canvasHeightPx: Int
    ): Bitmap? {
        val s = OverlayMaskGeometry.sanitize(settings)
        if (!s.hasShape) return null
        if (widthPx <= 0 || heightPx <= 0) return null
        val path = buildPath(s, widthPx, heightPx, canvasHeightPx) ?: return null
        val featherPx = OverlayMaskGeometry.dpToCanvasPx(s.featherDp, canvasHeightPx)
        return runCatching {
            val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                // INVERSE fill type + a white fill means "paint outside the
                // shape", which is exactly the inverted mask.
                if (s.invert) path.fillType = Path.FillType.INVERSE_EVEN_ODD
                if (featherPx > 0.5f) {
                    maskFilter = BlurMaskFilter(featherPx, BlurMaskFilter.Blur.NORMAL)
                }
            }
            Canvas(bmp).drawPath(path, paint)
            bmp
        }.getOrNull()
    }
}
