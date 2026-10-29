package com.aicreatorstudio.app.export

import android.graphics.Bitmap
import androidx.media3.common.Effect
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import com.aicreatorstudio.app.model.CanvasBackground
import com.aicreatorstudio.app.model.CanvasLetterbox
import com.aicreatorstudio.app.model.FillMode
import com.aicreatorstudio.app.model.TransitionPose

/**
 * Media3 adapter for [CanvasLetterbox] — the canvas letterbox fill colour.
 *
 * The preview already renders this (`EditorScreen` paints `letterboxColor` as
 * the canvas `Box`'s background, behind the video), so **nothing on the preview
 * side changes**; this class exists to bring the export up to parity. Before it,
 * `VideoExportEngine.buildEditedMediaItem` carried a
 * `@Suppress("UNUSED_PARAMETER") canvasBackground`, so a project with a coloured
 * letterbox exported with black bars.
 *
 * See [CanvasLetterbox] for the geometry, for why painting *over* the bars is
 * equivalent to painting *behind* the video, and for the rasteriser.
 *
 * ## Placement: *after* the pose, not before it
 *
 * The pass goes after `Presentation` **and after** [ClipMotionEffects], so it
 * sits in the same relationship to the video that the preview's canvas `Box`
 * has to its foreground `PlayerView`:
 *
 * ```
 *   Presentation  ->  ClipMotionEffects  ->  this  ->  subtitle burn-in
 *      (bars)          (video pose)        (bars)      (topmost)
 * ```
 *
 * Putting it *before* the pose looks tempting — the plate would then be
 * transformed together with the video, and its hole would track the silhouette
 * for free. It is wrong, and the reason is coverage rather than alignment: a
 * pose is a rigid transform, so it moves the plate's **outer bounds** as well
 * as its hole. `ZOOM_IN` starts at `scale = 0.45`, and a push transition
 * translates by a full frame width, so the plate would vacate most of the
 * canvas and leave the very black bars it was added to remove. Compensating
 * with an oversized plate is not viable either — covering the worst case needs
 * a buffer several times the canvas in each axis, past the GL maximum texture
 * size and at absurd memory cost.
 *
 * Drawing *after* the pose inverts the problem into the easy direction: the
 * plate stays canvas-static, and the *hole* is what moves, which costs nothing
 * because the hole is recomputed from the pose anyway. This is also the order
 * the preview uses.
 *
 * Sitting after the washes is deliberate too. A fade reduces the clip's alpha,
 * so on screen the video dims toward the background while the bars — which are
 * already the background — do not change. The fade wash is painted in the canvas
 * colour for exactly that reason (see [CanvasLetterbox.fadeColorArgb]), and the
 * plate then repaints the bars on top, so they hold the chosen colour even at the
 * trough of a fade. Painting the plate last is what makes that independent of how
 * the wash was composed.
 *
 * ## No size is passed in
 *
 * `TextureOverlay.configure(Size)` supplies the canvas dimensions, so this
 * class never has to guess them — the same property that keeps
 * [VignetteOverlay] free of a `frameIsKnown` guard. The *source* dimensions and
 * the rotation do have to be passed, because those are per-clip and are what
 * determine where the video sits inside the canvas.
 *
 * ## Fades agree with the bars
 *
 * A fade reduces the clip's alpha, and the preview dims the clip toward the
 * letterbox `Box` behind it. The export reproduces that with a full-frame wash in
 * the canvas colour — see [CanvasLetterbox.fadeColorArgb] and
 * [ClipMotionEffects] — so the video and the bars dim toward the same colour
 * rather than the video dimming to black beside coloured bars. The plate is
 * painted *after* the washes, so the bars hold the chosen colour at the trough of
 * a fade either way.
 *
 * The one remaining gap is [CanvasBackground.Blur], which is not baked at all —
 * see that class's KDoc.
 */
@UnstableApi
internal class CanvasBackgroundOverlay(
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val rotationDegrees: Float,
    private val fillMode: FillMode,
    private val timeline: PoseTimeline,
    @Volatile var background: CanvasBackground = CanvasBackground.Black
) : BitmapOverlay() {

    private var frameWidth = 0
    private var frameHeight = 0

    /** Canvas-sized plate, reused across frames. See [getBitmap]. */
    private var fullPlate: Bitmap? = null

    /** 1x1 fully transparent stand-in for "nothing to paint". */
    private var emptyPlate: Bitmap? = null

    /** The plate [getBitmap] last handed out, and what it was built for. */
    private var plate: Bitmap? = null
    private var platePose: TransitionPose? = null
    private var plateBackground: CanvasBackground? = null
    private var builtFrameWidth = -1
    private var builtFrameHeight = -1

    /**
     * Dimensions of the bitmap [getBitmap] last handed out — **not** the frame
     * dimensions. The two differ for the transparent stand-in, and conflating
     * them is how the overlay scale silently becomes wrong.
     */
    private var textureWidth = 0
    private var textureHeight = 0

    private var settings: OverlaySettings? = null
    private var settingsTextureWidth = -1
    private var settingsTextureHeight = -1

    /** Called by Media3 with the canvas dimensions, on the GL thread. */
    override fun configure(videoSize: Size) {
        frameWidth = videoSize.width
        frameHeight = videoSize.height
    }

    /**
     * Rebuilt when — and only when — the canvas size, the chosen colour or the
     * **pose** changes. The pose is what makes this a per-frame pass for an
     * animated clip and a once-only pass for a static one; comparing the whole
     * [TransitionPose] by value keeps the check to one `equals` and allocates
     * nothing.
     *
     * The canvas-sized bitmap is mutated in place with `setPixels` rather than
     * reallocated, so a clip that animates for 30 seconds still owns exactly one
     * full-frame buffer. `BitmapOverlay` re-uploads on `getGenerationId()`, which
     * `setPixels` bumps, so a rebuilt plate is always re-uploaded and an
     * unchanged one never is.
     *
     * When there is nothing to paint — the silhouette covers the canvas, or the
     * inputs are degenerate — this returns a 1x1 transparent bitmap. That draws
     * nothing while still satisfying the inherited `getTextureSize()` contract,
     * which does `checkNotNull(lastBitmap)`.
     */
    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        if (frameWidth <= 0 || frameHeight <= 0) return publish(empty())

        val current = background
        val pose = timeline.poseAt(presentationTimeUs)
        val cached = plate
        if (cached != null &&
            current == plateBackground &&
            pose == platePose &&
            frameWidth == builtFrameWidth &&
            frameHeight == builtFrameHeight
        ) {
            return cached
        }

        val colorValue = (current as? CanvasBackground.SolidColor)?.colorValue ?: 0L
        val pixels = CanvasLetterbox.pixels(
            canvasWidth = frameWidth,
            canvasHeight = frameHeight,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            rotationDegrees = rotationDegrees,
            fillMode = fillMode,
            colorValue = colorValue,
            pose = pose
        )

        val fresh = if (pixels == null) {
            empty()
        } else {
            val target = fullPlate?.takeIf {
                !it.isRecycled && it.width == frameWidth && it.height == frameHeight
            } ?: Bitmap
                .createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
                .also { fullPlate = it }
            target.setPixels(pixels, 0, frameWidth, 0, 0, frameWidth, frameHeight)
            target
        }

        plate = fresh
        platePose = pose
        plateBackground = current
        builtFrameWidth = frameWidth
        builtFrameHeight = frameHeight
        return publish(fresh)
    }

    /**
     * Maps the plate onto the frame 1:1.
     *
     * The plate is already at canvas resolution, so the scale is `(1, 1)` — but
     * it is computed rather than hard-coded because the empty 1x1 stand-in needs
     * the opposite treatment (stretched to the frame) and both share this path.
     *
     * Depends on [getBitmap] having run first: `OverlayShaderProgram.drawFrame`
     * calls `getTextureId` (which calls `getBitmap`) before `getOverlaySettings`,
     * so the plate dimensions are always populated here. Cached, because the
     * frame size and the plate size change together and neither changes per
     * frame for a static clip.
     */
    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val cached = settings
        if (cached != null &&
            textureWidth == settingsTextureWidth &&
            textureHeight == settingsTextureHeight
        ) {
            return cached
        }
        // A canvas-sized plate maps 1:1; the 1x1 stand-in is stretched over the
        // whole frame. Both are correct — the stand-in is fully transparent, so
        // either size draws nothing — but keying the scale off the *bitmap*
        // rather than the frame is what keeps that true if the stand-in ever
        // stops being a single pixel.
        val scaleX = if (textureWidth > 0) frameWidth.toFloat() / textureWidth else 1f
        val scaleY = if (textureHeight > 0) frameHeight.toFloat() / textureHeight else 1f
        val fresh = OverlaySettings.Builder().setScale(scaleX, scaleY).build()
        settings = fresh
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
        emptyPlate = null
        plate = null
        platePose = null
        plateBackground = null
        settings = null
    }

    private fun empty(): Bitmap =
        emptyPlate ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            .also { emptyPlate = it }

    companion object {
        /**
         * The effect to append, or `null` when there is nothing for it to do.
         *
         * Three rejections are knowable here:
         *
         *  - a [CanvasBackground.Black] letterbox is already the engine's native
         *    bar colour, and a [CanvasBackground.Blur] one is unimplemented (see
         *    [CanvasLetterbox]);
         *  - a clip that **cannot move** and whose video already covers the
         *    canvas can never show a bar. This needs the expected frame size,
         *    which is why [outputWidthPx] / [outputHeightPx] are passed in;
         *    without them a FILL clip would get a full-frame plate that repaints
         *    the frame with nothing on every single frame. They are a **hint,
         *    not a requirement**: a `0` simply keeps the pass, which is the safe
         *    direction, and the plate still decides per frame in [getBitmap].
         *    The caller passes `0` for a FREE canvas whose frame a rotation grew,
         *    because there the resolved size is smaller than the real one;
         *  - a clip that cannot move and *does* letterbox still needs the plate,
         *    so it is not rejected.
         *
         * The remaining case — a canvas whose aspect happens to match the clip,
         * when the clip *can* move — is only knowable per frame, and is handled
         * by the transparent 1x1 plate in [getBitmap].
         */
        fun build(
            segment: RenderSegment,
            background: CanvasBackground,
            outputWidthPx: Int,
            outputHeightPx: Int
        ): Effect? {
            if (!CanvasLetterbox.isActive(background)) return null

            val sourceWidth = segment.sourceWidthPx
            val sourceHeight = segment.sourceHeightPx
            val rotationDegrees = segment.rotationDegrees.toFloat()
            val fillMode = segment.fillMode

            // A clip that can never move cannot expose a bar it does not already
            // have, so a video that covers the canvas is the end of the story.
            if (!ClipMotionEffects.hasGeometryPose(segment) &&
                outputWidthPx > 0 && outputHeightPx > 0 &&
                !CanvasLetterbox.needsPlate(
                    canvasWidth = outputWidthPx,
                    canvasHeight = outputHeightPx,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    rotationDegrees = rotationDegrees,
                    fillMode = fillMode
                )
            ) {
                return null
            }

            return OverlayEffect(
                listOf(
                    CanvasBackgroundOverlay(
                        sourceWidth = sourceWidth,
                        sourceHeight = sourceHeight,
                        rotationDegrees = rotationDegrees,
                        fillMode = fillMode,
                        timeline = ClipMotionEffects.geometryPoseTimeline(segment),
                        background = background
                    )
                )
            )
        }
    }
}
