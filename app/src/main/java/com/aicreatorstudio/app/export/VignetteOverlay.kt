package com.aicreatorstudio.app.export

import android.graphics.Bitmap
import androidx.media3.common.Effect
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import com.aicreatorstudio.app.model.ColorGrading
import com.aicreatorstudio.app.model.VignettePlate

/**
 * Media3 adapter for [VignettePlate] — the "Detail > Vignette" sliders of the
 * Color Grading panel, as a real overlay pass.
 *
 * `vignetteAmount`, `vignetteFeather` and `vignetteRoundness` were write-only
 * until 2026-09-16: the panel wrote them, the DB persisted them, and no
 * renderer read them back. See [VignettePlate] for the falloff maths, for the
 * blend equation, and for why the plate can safely be generated below frame
 * resolution.
 *
 * ## Two usages, one math (preview == export)
 *
 *  - **Export** ([VideoExportEngine]): a fresh instance per segment, with the
 *    clip's own grade (immutable use).
 *  - **Live preview** (the editor's canvas): 2026-10-21. The preview used to
 *    be *missing* the vignette altogether — the class KDoc claimed a live
 *    preview instance existed, but the `ExoPlayer.setVideoEffects` path that
 *    would have carried it was removed on 2026-09-19, so the panel's slider
 *    moved and the screen did not change. The preview now builds its plate
 *    with the same [VignettePlate.pixels] call, at the same 1/4 resolution,
 *    and places it on the video's rect with the same over-cover — so the
 *    falloff, the alpha and the edge behaviour are the export's.
 *
 *    It is a **sibling overlay**, not a filter on the video layer: the
 *    editor's video is a `TextureView` inside an `AndroidView`, so a Skia
 *    blend mode would have nothing to blend against, and the Play-button
 *    history in `EditorScreen` is a standing warning about filters on that
 *    layer.
 *
 * ## Why no frame size has to be passed in
 *
 * `TextureOverlay.configure(Size)` is called by Media3 with the dimensions of
 * the frame this overlay will be composited onto, before the first draw and
 * again whenever those dimensions change. That is the *exact* answer, so this
 * class never has to be told — and every full-frame overlay in the project now
 * works the same way, including the fade washes in
 * [com.aicreatorstudio.app.export.ClipMotionEffects]. Those washes used to be
 * sized by the engine and skipped when the engine could not supply the size;
 * the one case where it genuinely could not is a rotation that grows the frame
 * to a bounding box *after* the output size has been resolved, and a skipped
 * wash there means a silently lost fade. Observing is the only correct option,
 * so no frame size is passed into a wash any more.
 *
 * [com.aicreatorstudio.app.export.CanvasBackgroundOverlay] is the sole
 * remaining taker of an expected size, and only as a build-time *hint* for
 * deciding whether to emit a pass at all; its plate still decides per frame.
 *
 * Because the vignette is applied *before* the `Presentation`, the size handed
 * to [configure] is the source frame — the same frame the preview's effect
 * chain sees, which is what keeps the two in step.
 *
 * ## Grain is deliberately *not* here
 *
 * `ColorGrading.grain` used to be the last write-only field in the project, and
 * this class is where it was most tempting to put it: the two are neighbours in
 * the same panel and both are "Detail" controls. It went elsewhere on purpose.
 * Grain is *high-frequency* noise, so the downscaled-plate trick that makes this
 * class cheap would turn it into soft blobs rather than grain. The alternatives
 * were a per-frame full-resolution `BitmapOverlay` upload (prohibitively
 * expensive — ~8 MB per frame at 1080p, so ~250 MB/s at 30fps), a single static
 * noise texture (free per frame, but it reads as dirt on the lens rather than
 * film, because real grain moves), or `ByteBufferGlEffect` (custom GL drawing
 * *plus* a per-frame CPU pass over every pixel).
 *
 * It is a custom fragment shader instead — see
 * [com.aicreatorstudio.app.export.GrainEffect]. That is the project's only
 * hand-written shader, and the reason the two passes look nothing alike.
 */
@UnstableApi
class VignetteOverlay(
    @Volatile var grading: ColorGrading = ColorGrading.Identity
) : BitmapOverlay() {

    private var frameWidth = 0
    private var frameHeight = 0

    private var plate: Bitmap? = null
    private var plateGrading: ColorGrading? = null
    private var plateWidth = -1
    private var plateHeight = -1

    private var settings: OverlaySettings? = null
    private var settingsWidth = -1
    private var settingsHeight = -1

    /**
     * Called by Media3 before the first draw and on every change of input
     * dimensions, on the GL thread. Only records the size — the plate itself is
     * built lazily in [getBitmap] so that a configured-but-never-drawn overlay
     * allocates nothing.
     */
    override fun configure(videoSize: Size) {
        frameWidth = videoSize.width
        frameHeight = videoSize.height
    }

    /**
     * Rebuilt only when the grade or the frame size changed. Compared by value
     * rather than by string key so the per-frame call allocates nothing.
     *
     * With unknown dimensions this yields a 1x1 fully transparent plate, which
     * is a no-op rather than a crash.
     */
    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val current = grading
        val cached = plate
        if (cached != null &&
            current == plateGrading &&
            frameWidth == plateWidth &&
            frameHeight == plateHeight
        ) {
            return cached
        }
        val (plateW, plateH) = VignettePlate.plateSize(frameWidth, frameHeight)
        val pixels = VignettePlate.pixels(
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            amount = current.vignetteAmount,
            feather = current.vignetteFeather,
            roundness = current.vignetteRoundness
        )
        val fresh = Bitmap.createBitmap(pixels, plateW, plateH, Bitmap.Config.ARGB_8888)
        plate = fresh
        plateGrading = current
        plateWidth = frameWidth
        plateHeight = frameHeight
        return fresh
    }

    /**
     * Stretches the reduced plate back over the whole frame. See
     * [VignettePlate.overlayScale].
     */
    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val cached = settings
        if (cached != null && frameWidth == settingsWidth && frameHeight == settingsHeight) {
            return cached
        }
        val (scaleX, scaleY) = VignettePlate.overlayScale(frameWidth, frameHeight)
        val fresh = OverlaySettings.Builder().setScale(scaleX, scaleY).build()
        settings = fresh
        settingsWidth = frameWidth
        settingsHeight = frameHeight
        return fresh
    }

    override fun release() {
        super.release()
        plate = null
        plateGrading = null
        settings = null
    }

    companion object {
        /**
         * The effect to append, or `null` when the vignette is at rest.
         *
         * Returning `null` rather than an identity overlay keeps the GL chain
         * for an untouched project byte-for-byte what it was before this pass
         * existed.
         */
        fun build(grading: ColorGrading): Effect? =
            if (VignettePlate.isActive(grading.vignetteAmount)) {
                OverlayEffect(listOf(VignetteOverlay(grading)))
            } else {
                null
            }
    }
}
