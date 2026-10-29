package com.aicreatorstudio.app.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.media3.common.Effect
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import com.aicreatorstudio.app.model.ProgressBarGeometry
import com.aicreatorstudio.app.model.ProgressBarSettings

/**
 * 2026-10-13 — burns the project's **video progress bar** (timeline
 * indicator / story timer) into the exported MP4.
 *
 * The preview already draws the bar (inside the canvas `Box` in
 * `EditorVideoPreviewSection`); this class exists so the exported file has
 * the same bar in the same place. Before it, `ProgressBarSettings` would
 * have been a preview-only setting — bug class 1, "written but never
 * rendered", which is the exact defect this project has fixed repeatedly.
 *
 * ## Parity is structural, not by review
 *
 * Every number this class paints comes from
 * [ProgressBarGeometry.layout], and the preview calls the *same* function
 * with the *same* inputs. Neither renderer has its own copy of the
 * arithmetic, so the two cannot drift: the only difference between them is
 * that one draws with `DrawScope` and the other with
 * `android.graphics.Canvas`.
 *
 * The dp → px conversion is likewise shared
 * ([ProgressBarGeometry.dpToPx] against `CANVAS_UNIT_PX = 720f`), which is
 * why the bar is the same *fraction* of the frame at 720p, 1080p and 4K.
 * Using Compose's own `Dp.toPx()` on the preview side would have made the
 * on-screen bar depend on the phone's screen density while the exported
 * one depended on the encoder size — a mismatch that only shows up in the
 * exported file.
 *
 * ## Time base: composition time, not clip-local time
 *
 * Media3 hands the effect chain a **per-item** `presentationTimeUs` — it
 * restarts at 0 for every clip in the sequence (this is the same property
 * [SubtitleBurnInFactory] rebases for). A bar that tracks the *whole*
 * composition therefore has to add each segment's cumulative output
 * origin, which is why [compositionOriginMs] is a constructor parameter:
 *
 * ```
 * compositionMs = compositionOriginMs + presentationTimeUs / 1000
 * progress      = compositionMs / totalDurationMs
 * ```
 *
 * The caller passes the value from
 * [CompositionTimeline.originsMs], so a bar on a 5-clip project is
 * continuous across every cut instead of restarting at each one.
 *
 * ## Placement: after the canvas plate, before the subtitles
 *
 * ```
 *   Presentation -> ClipMotionEffects -> CanvasBackgroundOverlay
 *     -> this -> SubtitleBurnIn
 * ```
 *
 * The bar belongs *inside* the canvas, so it must run after
 * [CanvasBackgroundOverlay] (the letterbox bars do not exist before it)
 * and after `ClipMotionEffects` (a pose moves the video, not the chrome —
 * the same relationship the preview's canvas `Box` has to its posed
 * foreground `PlayerView`). It runs *before* the subtitle burn-in so a
 * caption still reads on top of it, matching the preview's layer order.
 *
 * ## One full-frame plate, reused
 *
 * The plate is a canvas-sized `ARGB_8888` bitmap, mutated in place with a
 * reused `Canvas` and two reused `Paint`s, so a 30-second export still
 * owns exactly one buffer and allocates nothing per frame. It is erased
 * with `eraseColor` rather than partially cleared: the track colour is
 * semi-transparent by default (`0x33FFFFFF`), so painting the track over
 * the previous frame's fill would leave the old fill showing through.
 * `BitmapOverlay` re-uploads whenever `getGenerationId()` changes, which
 * any pixel write bumps, so a rebuilt plate is always re-uploaded.
 *
 * A canvas-sized plate is the same choice [CanvasBackgroundOverlay] and
 * the subtitle overlays already make, and it keeps the overlay scale at
 * `(1, 1)`: no anchor arithmetic to get wrong, which is what makes
 * "the bar lands where the preview drew it" true by construction.
 */
@UnstableApi
internal class ProgressBarOverlay(
    private val settings: ProgressBarSettings,
    private val compositionOriginMs: Long,
    private val totalDurationMs: Long
) : BitmapOverlay() {

    private var frameWidth = 0
    private var frameHeight = 0

    /** Canvas-sized plate, reused across frames. See the class KDoc. */
    private var fullPlate: Bitmap? = null

    /**
     * The `Canvas` bound to [fullPlate]. Always assigned together with it,
     * so the two can never disagree about which buffer they target.
     */
    private var plateCanvas: Canvas? = null

    /** 1x1 fully transparent stand-in for "nothing to paint". */
    private var emptyPlate: Bitmap? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /**
     * Dimensions of the bitmap [getBitmap] last handed out — **not** the
     * frame dimensions. The two differ for the transparent stand-in, and
     * conflating them is how the overlay scale silently becomes wrong.
     */
    private var textureWidth = 0
    private var textureHeight = 0

    private var cachedSettings: OverlaySettings? = null
    private var settingsTextureWidth = -1
    private var settingsTextureHeight = -1

    /** Called by Media3 with the canvas dimensions, on the GL thread. */
    override fun configure(videoSize: Size) {
        frameWidth = videoSize.width
        frameHeight = videoSize.height
    }

    /**
     * Paints the bar for the frame at [presentationTimeUs].
     *
     * The timestamp is **item-local**, so the segment's composition origin
     * is added before the fraction is taken — see the class KDoc.
     */
    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        if (frameWidth <= 0 || frameHeight <= 0) return publish(empty())

        val progress = ProgressBarGeometry.progressFraction(
            positionMs = compositionOriginMs + presentationTimeUs / 1000L,
            durationMs = totalDurationMs
        )
        val layout = ProgressBarGeometry.layout(
            settings = settings,
            canvasWidthPx = frameWidth.toFloat(),
            canvasHeightPx = frameHeight.toFloat(),
            progress = progress
        )
        if (layout.isEmpty) return publish(empty())

        var plate = fullPlate
        var canvas = plateCanvas
        if (plate == null || plate.isRecycled ||
            plate.width != frameWidth || plate.height != frameHeight
        ) {
            plate = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
            canvas = Canvas(plate)
            fullPlate = plate
            plateCanvas = canvas
        }
        // Read through non-null locals so the smart casts hold.
        val target = plate ?: return publish(empty())
        val surface = canvas ?: Canvas(target).also { plateCanvas = it }

        // The track is semi-transparent, so the previous frame's fill has
        // to go before the new one is drawn — see the class KDoc.
        target.eraseColor(Color.TRANSPARENT)

        trackPaint.color = layout.trackColorArgb
        surface.drawRoundRect(
            layout.trackLeft,
            layout.trackTop,
            layout.trackRight,
            layout.trackBottom,
            layout.cornerRadiusPx,
            layout.cornerRadiusPx,
            trackPaint
        )

        if (layout.isFillVisible) {
            fillPaint.color = layout.fillColorArgb
            surface.drawRoundRect(
                layout.fillLeft,
                layout.fillTop,
                layout.fillRight,
                layout.fillBottom,
                layout.cornerRadiusPx,
                layout.cornerRadiusPx,
                fillPaint
            )
        }

        return publish(target)
    }

    /**
     * Maps the plate onto the frame 1:1.
     *
     * The plate is already at canvas resolution, so the scale is `(1, 1)` —
     * but it is computed rather than hard-coded because the empty 1x1
     * stand-in needs the opposite treatment (stretched to the frame) and
     * both share this path.
     *
     * Depends on [getBitmap] having run first: `OverlayShaderProgram`
     * calls `getTextureId` (which calls `getBitmap`) before
     * `getOverlaySettings`, so the plate dimensions are always populated
     * here.
     */
    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val cached = cachedSettings
        if (cached != null &&
            textureWidth == settingsTextureWidth &&
            textureHeight == settingsTextureHeight
        ) {
            return cached
        }
        // Keying the scale off the *bitmap* rather than the frame is what
        // keeps a frame-sized plate at 1:1 and stretches the 1x1 stand-in
        // over the whole frame. Both draw correctly — the stand-in is fully
        // transparent — but only one of the two is the intended mapping.
        val scaleX = if (textureWidth > 0) frameWidth.toFloat() / textureWidth else 1f
        val scaleY = if (textureHeight > 0) frameHeight.toFloat() / textureHeight else 1f
        val fresh = OverlaySettings.Builder().setScale(scaleX, scaleY).build()
        cachedSettings = fresh
        settingsTextureWidth = textureWidth
        settingsTextureHeight = textureHeight
        return fresh
    }

    /** Records the dimensions of the bitmap about to be handed to Media3. */
    private fun publish(bitmap: Bitmap): Bitmap {
        textureWidth = bitmap.width
        textureHeight = bitmap.height
        return bitmap
    }

    override fun release() {
        super.release()
        fullPlate = null
        plateCanvas = null
        emptyPlate = null
        cachedSettings = null
    }

    private fun empty(): Bitmap =
        emptyPlate ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            .also { emptyPlate = it }

    companion object {
        /**
         * The effect to append, or `null` when there is nothing for it to
         * do.
         *
         * Three rejections are knowable here, and each one exists so an
         * untouched project's effect chain is **identical** to before this
         * feature shipped:
         *
         *  - the bar is disabled (the default for every project);
         *  - the height is zero / non-finite, so there is no bar to draw;
         *  - the composition has no duration, so progress is undefined.
         *
         * The settings are clamped on the way in, so a value written by a
         * future build with a wider range still produces drawable geometry
         * instead of throwing inside `Canvas.drawRoundRect` — which would
         * abort the export rather than merely look wrong.
         */
        fun build(
            settings: ProgressBarSettings,
            compositionOriginMs: Long,
            totalDurationMs: Long
        ): Effect? {
            if (!settings.isEnabled) return null
            if (!settings.heightDp.isFinite() || settings.heightDp <= 0f) return null
            if (totalDurationMs <= 0L) return null
            return OverlayEffect(
                listOf(
                    ProgressBarOverlay(
                        settings = settings.clamped(),
                        compositionOriginMs = compositionOriginMs.coerceAtLeast(0L),
                        totalDurationMs = totalDurationMs
                    )
                )
            )
        }
    }
}
