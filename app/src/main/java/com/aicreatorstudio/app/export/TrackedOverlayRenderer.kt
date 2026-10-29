package com.aicreatorstudio.app.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.aicreatorstudio.app.model.CropKeyframe
import com.aicreatorstudio.app.model.MotionTrackSettings
import com.aicreatorstudio.app.model.SubtitleStyle
import com.aicreatorstudio.app.model.TextOverlay
import com.aicreatorstudio.app.model.util.MotionTrackingGeometry
import com.aicreatorstudio.app.model.util.MotionTrajectorySmoothing
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders a **tracked overlay** onto a decoded frame, for the export's
 * per-frame `FrameOp` pass.
 *
 * ## Why this is a CPU blit and not a Media3 `TextureOverlay`
 *
 * A Media3 `BitmapOverlay` could animate a bitmap per frame through
 * `getOverlaySettings(presentationTimeUs)` — [ClipMotionWashOverlay]
 * does exactly that for the fade washes. It is the wrong tool here for
 * two reasons:
 *
 *  1. **The overlay must be rasterized by the shared text renderer.**
 *     `CaptionRenderer.render` is what implements Persian shaping, RTL
 *     bidi layout, stroke/shadow geometry and the karaoke/viral motion
 *     engine. Its result is a full-frame bitmap, not a sticker image,
 *     and `getBitmap` is expected to return a stable bitmap rather than
 *     one re-laid-out per frame. Rendering the text ourselves and
 *     compositing it is what guarantees the *export* looks like the
 *     *preview*.
 *  2. **Text layout is not available in the GL thread.** `getBitmap`
 *     is called on Media3's GL thread, where a `StaticLayout` pass per
 *     frame is both illegal and ruinously slow.
 *
 * So the export rasterizes the overlay through the same renderer the
 * preview uses, then blits it at the tracked position. One renderer,
 * two consumers, guaranteed pixel parity for everything except
 * position — and position comes from one shared trajectory.
 *
 * ## Cost
 *
 * The overlay bitmap is rasterized **once** ([prepare]) and then
 * blitted per frame. Only the blit is per-frame, and it touches the
 * overlay's own bounding box rather than the frame: a 400x200 sticker
 * on a 4K frame is ~0.4% of the pixels. Hardware-accelerated
 * `Canvas.drawBitmap` with a destination rect does the scaling in the
 * GPU rasterizer, so there is no per-pixel Kotlin at all.
 */
internal class TrackedOverlayRenderer(
    private val context: Context,
    private val overlay: TextOverlay,
    private val settings: MotionTrackSettings,
    private val keyframes: List<CropKeyframe>,
    private val outputWidthPx: Int,
    private val outputHeightPx: Int,
    /**
     * The segment's timeline origin. Subtracted from a frame's output
     * PTS so the trajectory — which is clip-local — is sampled at the
     * right instant. The preview player holds one media item and is
     * already clip-local; the export is item-relative and needs this.
     */
    private val clipTimelineStartMs: Long = 0L
) {

    private val typefaceCache = HashMap<String, android.graphics.Typeface?>()

    /**
     * The overlay rasterized at output size, rendered ONCE.
     *
     * Its position within the bitmap is the overlay's own authored
     * position, so the blit must translate by the *difference* between
     * the tracked anchor and the overlay's authored position. Treating
     * the bitmap as a sticker and drawing it centred on the track would
     * throw away the user's layout — see [drawAt].
     */
    private val stamp: Bitmap? by lazy {
        if (overlay.text.isBlank()) return@lazy null
        runCatching {
            CaptionRenderer.render(
                context = context,
                overlay = overlay,
                style = overlay.subtitleStyle ?: SubtitleStyle.Default,
                canvasWidthPx = outputWidthPx,
                canvasHeightPx = outputHeightPx,
                // Static raster: entrance/viral animation is evaluated
                // at the overlay's own start time so the sticker's shape
                // is stable frame to frame. A *moving* overlay is
                // movement, not re-layout.
                frameTimeMs = overlay.startTimeMs,
                typefaceCache = typefaceCache
            )
        }.getOrNull()
    }

    /** Bounding box of the non-transparent content, in stamp pixels. */
    private val contentBounds: Rect? by lazy { stamp?.let { findContentBounds(it) } }

    private val paint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
    }

    /** True when this renderer has anything to draw at all. */
    val hasContent: Boolean
        get() = stamp != null && contentBounds != null &&
            (keyframes.isNotEmpty() || settings.maskMode != MotionTrackSettings.MaskMode.NONE)

    /**
     * Composite the tracked overlay (and/or mask) onto [frame], in place.
     *
     * **Returns [frame] itself, deliberately.** The pipeline recycles
     * the bitmap a `FrameOp` returns when it differs from the input, so
     * returning a fresh instance would force a full-frame allocation
     * per frame — the fastest way to OOM a 4K export. Mutating and
     * returning the input is the contract.
     *
     * @param presentationTimeUs the frame's output PTS.
     */
    fun drawAt(frame: Bitmap, presentationTimeUs: Long): Bitmap {
        if (keyframes.isEmpty()) return frame
        val clipLocalMs = presentationTimeUs / 1000L - clipTimelineStartMs
        val k = MotionTrackingGeometry.interpolateKeyframe(keyframes, clipLocalMs)

        // Two independent alpha sources multiply: the track's own
        // confidence (does the optimizer trust this position?) and the
        // re-anchor ramp (are we crossing a discontinuous jump?).
        val confidenceAlpha = MotionTrackingGeometry.confidenceAlpha(
            k.confidence, k.isFound
        )
        if (confidenceAlpha <= ALPHA_EPSILON) return frame
        val rampAlpha = MotionTrajectorySmoothing.reanchorAlpha(keyframes, clipLocalMs)
        val alpha = (confidenceAlpha * rampAlpha).coerceIn(0f, 1f)
        if (alpha <= ALPHA_EPSILON) return frame

        val box = boxOf(k, frame.width, frame.height)
        if (box == null || box.width() <= 0f || box.height() <= 0f) return frame

        val canvas = Canvas(frame)

        // ---- mask first, so the overlay sits on top of it ----
        if (settings.maskMode != MotionTrackSettings.MaskMode.NONE) {
            drawMask(canvas, frame, box, alpha)
        }

        // ---- overlay ----
        val s = stamp ?: return frame
        val cb = contentBounds ?: return frame
        drawStamp(canvas, frame, s, cb, box, alpha)
        return frame
    }

    // ------------------------------------------------------------------
    // geometry
    // ------------------------------------------------------------------

    /**
     * The tracked box in **frame pixels**, or null when the keyframe
     * carries no extent (an Auto Frame trajectory, which has none).
     *
     * A null result is not an error: with no extent there is no box to
     * attach to, and drawing nothing is the only honest response.
     */
    private fun boxOf(k: CropKeyframe, frameW: Int, frameH: Int): RectF? {
        val w = k.width ?: return null
        val h = k.height ?: return null
        if (w <= 0f || h <= 0f) return null
        val cx = k.centerX * frameW
        val cy = k.centerY * frameH
        val bw = (w * frameW).coerceAtLeast(2f)
        val bh = (h * frameH).coerceAtLeast(2f)
        return RectF(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f)
    }

    /**
     * Blit the stamp so the overlay's **authored position** lands on the
     * tracked anchor point.
     *
     * The stamp is a full-frame raster whose content sits wherever the
     * user placed it. The correct transform is therefore a *translation
     * of the content's own offset plus the anchor offset*, not a
     * re-centring. Re-centring would move every tracked overlay's text
     * to the middle of the box regardless of how the user laid it out —
     * which reads as the text jumping on the first tracked frame.
     */
    private fun drawStamp(
        canvas: Canvas,
        frame: Bitmap,
        s: Bitmap,
        cb: Rect,
        box: RectF,
        alpha: Float
    ) {
        // Where the overlay's authored position sits, in frame pixels.
        // The stamp is rendered at output size, so its coordinates and
        // the frame's are the same space.
        val authoredCx = cb.exactCenterX()
        val authoredCy = cb.exactCenterY()

        // Where it must move to: the anchor point inside the tracked box.
        val anchorX = box.left + settings.anchorU.coerceIn(0f, 1f) * box.width()
        val anchorY = box.top + settings.anchorV.coerceIn(0f, 1f) * box.height()

        val scale = if (settings.scaleWithBox) {
            val ref = settings.referenceBoxHeight.coerceAtLeast(0.001f)
            // Compare the tracked box height against the height it had
            // when the overlay was attached. Clamped hard: an
            // unclamped scale from a bad keyframe turns a sticker into
            // a full-screen smear.
            (box.height() / frame.height / ref).coerceIn(MIN_BOX_SCALE, MAX_BOX_SCALE)
        } else {
            1f
        }

        val dx = anchorX - authoredCx * scale
        val dy = anchorY - authoredCy * scale

        paint.alpha = (alpha * 255f).roundToInt().coerceIn(0, 255)

        val src = Rect(cb)
        val dst = RectF(
            dx + src.left * scale,
            dy + src.top * scale,
            dx + src.right * scale,
            dy + src.bottom * scale
        )
        // drawBitmap(Bitmap, Rect src, RectF dst, Paint) is the
        // hardware-accelerated scaled blit — no per-pixel Kotlin.
        runCatching { canvas.drawBitmap(s, src, dst, paint) }
    }

    /**
     * Draw the tracked blur or mosaic mask.
     *
     * **Export-only by design.** The live preview cannot show this: the
     * video composites through a `TextureView` outside the View raster,
     * so a View-level blur over the video surface is a no-op (the same
     * constraint that makes `sharpness` / `vignette` / `grain`
     * export-only). Fighting that would mean a real GL pass; showing a
     * placeholder in the preview and baking the true mask here is the
     * consistent choice.
     *
     * The mask is drawn by sampling the frame's own pixels inside [box]
     * — blur by a cheap box-downscale/upscale, mosaic by the same
     * operation with a coarser factor. Both are GPU-rasterized; neither
     * walks pixels in Kotlin.
     */
    private fun drawMask(canvas: Canvas, frame: Bitmap, box: RectF, alpha: Float) {
        val left = max(0, box.left.toInt())
        val top = max(0, box.top.toInt())
        val right = min(frame.width, box.right.toInt())
        val bottom = min(frame.height, box.bottom.toInt())
        val w = right - left
        val h = bottom - top
        if (w < 4 || h < 4) return

        val strength = settings.maskStrength.coerceIn(0f, 1f)

        // Downscale factor: 0 -> negligible, 1 -> very coarse. Mosaic
        // is intentionally far coarser than blur for the same slider
        // position, because that is what distinguishes the two effects
        // visually.
        val divisor = when (settings.maskMode) {
            MotionTrackSettings.MaskMode.MOSAIC -> (6f + 26f * strength)
            else -> (3f + 9f * strength)   // BLUR
        }
        val sw = max(2, (w / divisor).toInt())
        val sh = max(2, (h / divisor).toInt())

        val patch = runCatching {
            Bitmap.createBitmap(frame, left, top, w, h)
        }.getOrNull() ?: return

        val small = runCatching {
            Bitmap.createScaledBitmap(patch, sw, sh, true)
        }.getOrNull()

        patch.recycle()
        if (small == null) return

        paint.alpha = (alpha * 255f).roundToInt().coerceIn(0, 255)
        // Nearest-neighbour on the way back up is what makes it read as
        // a mosaic rather than a blur; `true` would smear the cells.
        val filterBack = settings.maskMode != MotionTrackSettings.MaskMode.MOSAIC
        val big = runCatching {
            Bitmap.createScaledBitmap(small, w, h, filterBack)
        }.getOrNull()
        small.recycle()
        if (big == null) return

        runCatching { canvas.drawBitmap(big, left.toFloat(), top.toFloat(), paint) }
        big.recycle()
        paint.alpha = 255
    }

    /**
     * Tight bounding box of the non-transparent content in [bmp].
     *
     * Bounded to a **stride-sampled** scan: a full-resolution alpha walk
     * on a 4K stamp is 8M reads for something that runs once. The
     * stride is 2 px, and the result is grown by the stride so no content
     * is clipped.
     */
    private fun findContentBounds(bmp: Bitmap): Rect? {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return null
        val px = IntArray(w * h)
        runCatching { bmp.getPixels(px, 0, w, 0, 0, w, h) }.getOrNull() ?: return null

        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        var y = 0
        while (y < h) {
            val row = y * w
            var x = 0
            while (x < w) {
                if ((px[row + x] ushr 24) > ALPHA_CONTENT_THRESHOLD) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                x += BOUNDS_STRIDE
            }
            y += BOUNDS_STRIDE
        }
        if (maxX < 0 || maxY < 0) return null
        return Rect(
            max(0, minX - BOUNDS_STRIDE),
            max(0, minY - BOUNDS_STRIDE),
            min(w, maxX + BOUNDS_STRIDE + 1),
            min(h, maxY + BOUNDS_STRIDE + 1)
        )
    }

    companion object {
        private const val ALPHA_EPSILON = 0.01f
        private const val ALPHA_CONTENT_THRESHOLD = 8
        private const val BOUNDS_STRIDE = 2

        // Public (not `private`): the live preview's placement helper
        // (ui/screens/editor/TrackedOverlayPreview.kt) reuses these so
        // preview and export can never drift apart on the clamps.
        const val MIN_BOX_SCALE = 0.25f
        const val MAX_BOX_SCALE = 4f

        /**
         * True when [settings] describes a render this class can
         * perform — either a resolvable overlay or a mask mode.
         */
        fun canRender(settings: MotionTrackSettings?, overlay: TextOverlay?): Boolean {
            if (settings?.enabled != true) return false
            val hasMask = settings.maskMode != MotionTrackSettings.MaskMode.NONE
            val hasOverlay = overlay != null && overlay.text.isNotBlank()
            return hasMask || hasOverlay
        }

        /** Convenience for the mask-only case's strength→radius hint. */
        fun blurRadiusPx(strength: Float, frameHeightPx: Int): Float {
            val f = strength.coerceIn(0f, 1f)
            return (frameHeightPx * 0.004f * (1f + 9f * f)).coerceAtLeast(1f)
        }
    }
}
