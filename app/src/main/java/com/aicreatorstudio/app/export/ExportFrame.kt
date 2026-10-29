package com.aicreatorstudio.app.export

import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import com.aicreatorstudio.app.model.CanvasPreset
import com.aicreatorstudio.app.model.FillMode

/**
 * Pure decisions about the export's **output frame**: how big it is, when it
 * has to be pinned, and how each clip is fitted into it.
 *
 * ## Why this exists
 *
 * Three answers the rest of the pipeline depends on were previously either
 * computed in the wrong place or not computed at all:
 *
 * 1. **`fillMode` never reached the `Presentation`.** The FIT/FILL/MANUAL
 *    segmented control wrote the clip's `fillMode`, the DB persisted it, and
 *    the preview honoured it (`RESIZE_MODE_FIT` / `RESIZE_MODE_ZOOM`), but
 *    both `buildPresentation` branches hard-coded `LAYOUT_SCALE_TO_FIT`. A
 *    FILL clip therefore exported letterboxed, and the panel looked inert.
 * 2. **The output size was never handed to the wash passes.** Historically
 *    [ClipMotionEffects.build] skipped its full-frame washes when the output
 *    size was unknown, and at the time it had to: an overlay is scaled by
 *    `overlaySize / backgroundSize` (see Media3's `OverlayMatrixProvider`), so
 *    a mis-sized wash covers the frame only partially and leaves an un-faded
 *    border. `buildEditedMediaItem` declared `outputWidthPx` /
 *    `outputHeightPx` parameters, but its single caller never passed them —
 *    they were always `0`, so **every** fade, flash and `opacity` wash was
 *    skipped.
 *
 *    That was first fixed by threading the size through, and then fixed
 *    *properly* on 2026-09-16 by removing the requirement altogether: each wash
 *    now observes the frame size from Media3's `TextureOverlay.configure`. A
 *    size that is *observed* cannot be wrong, whereas a size that is *guessed*
 *    can — and there is a case, described below, where it is not knowable at
 *    all. [resolve] is still the single owner of the frame size for everything
 *    that genuinely needs to know it up front: the `Presentation`, and
 *    [CanvasBackgroundOverlay]'s build-time "is there anything to paint" test.
 *    Neither can be wrong if this is right.
 *
 * Both are the same failure shape: a value that two places must agree on,
 * with no single place that owns it. This object owns it, and is free of
 * every Android type so it can be unit-tested on the JVM — this project has
 * no Robolectric and no mocking library, so pure functions are the only
 * testable seam.
 *
 * ## The frame, in the three canvas cases
 *
 * | Canvas preset | Frame size | `Presentation`? |
 * |---|---|---|
 * | any sized preset (9:16, 16:9, 1:1, 4:5) | `exportWidth × exportHeight` | yes |
 * | [CanvasPreset.FREE], one clip | the clip's own pixels | no — nothing to pin |
 * | [CanvasPreset.FREE], several clips | the reference clip's pixels | yes |
 *
 * The FREE single-clip row carries one caveat, and it shaped the design above:
 * with a **user rotation**, step 1's `ScaleAndRotateTransformation` grows the
 * frame to the rotated bounding box *after* this resolution runs, so the real
 * frame is larger than the clip's pixels. [resolve] therefore cannot be the last
 * word on the frame size in that case — which is precisely why the full-frame
 * washes observe their size from `TextureOverlay.configure` instead of being
 * told it, and why [CanvasBackgroundOverlay] treats this size as a hint rather
 * than a fact. A wrong size here would have made the fades vanish.
 *
 * ## Why the FREE case needs one *shared* reference segment
 *
 * Media3 writes **one** video track for the whole composition, so the frame
 * has to be a single size. `MuxerWrapper` throws `AppendTrackFormatException`
 * outright on a width or height change mid-stream, and a re-encode cannot
 * emit a track whose dimensions change either. Sizing from each clip in turn
 * would give a mixed-resolution project a different frame per item, so
 * [resolve] is always called with the **same** reference for every clip.
 */
@UnstableApi
internal object ExportFrame {

    /**
     * The pixel size of the exported frame, or `null` when nothing usable is
     * known — in which case the caller omits the `Presentation` and Media3
     * keeps the source's natural size.
     *
     * A sized preset wins outright; [CanvasPreset.FREE] (`exportWidth` /
     * `exportHeight` are `0`) falls back to the reference clip's real pixels.
     *
     * @param reference the clip whose source pixels stand in for the canvas
     *   when the preset is FREE. Must be the **same** segment for every clip
     *   in a composition — see the class KDoc.
     */
    fun resolve(canvasPreset: CanvasPreset, reference: RenderSegment): Pair<Int, Int>? {
        val w = canvasPreset.exportWidth
        val h = canvasPreset.exportHeight
        if (w > 0 && h > 0) return w to h
        val rw = reference.sourceWidthPx
        val rh = reference.sourceHeightPx
        if (rw <= 0 || rh <= 0) return null
        return rw to rh
    }

    /**
     * Whether a `Presentation` is needed at all.
     *
     * A sized preset always needs one, because the effect chain is the only
     * mechanism that resizes the output — `Transformer` has no "set output
     * resolution" API. A FREE canvas needs one only for a multi-clip export,
     * where the sequence requires a single consistent size; a single FREE
     * clip is left alone, which is the pre-existing behaviour and keeps an
     * untouched project's effect chain unchanged.
     */
    fun needsPresentation(canvasPreset: CanvasPreset, multiClip: Boolean): Boolean =
        (canvasPreset.exportWidth > 0 && canvasPreset.exportHeight > 0) || multiClip

    /**
     * The output pixel size to hand to [ExportConfig] — i.e. the frame the
     * *encoder*, the upscaler and the subtitle burn-in all work against.
     *
     * ## Why this is not just `preset.exportWidth ?: quality.widthPx`
     *
     * It used to be exactly that, in both export entry points, and for
     * [CanvasPreset.FREE] it produced the chosen [ExportQuality]'s own
     * dimensions. Every `ExportQuality` is defined **landscape**
     * (`HIGH_1080P` is 1920×1080), so a FREE project was framed landscape
     * no matter what had been imported: a portrait source got its captions
     * laid out for a 1920×1080 frame and its upscale grid sized the same
     * way, while a landscape source happened to come out right. That is the
     * same orientation asymmetry as a hard-coded 9:16, mirrored.
     *
     * A sized preset still wins outright. For FREE the source's own
     * dimensions decide, scaled down to the quality tier's **long side**
     * when the source exceeds it:
     *
     * | Source | Quality | Output |
     * |---|---|---|
     * | 1920×1080 | 1080p | 1920×1080 (unchanged) |
     * | 1080×1920 | 1080p | 1080×1920 (was 1920×1080) |
     * | 3840×2160 | 1080p | 1920×1080 |
     * | 2160×3840 | 1080p | 1080×1920 |
     * | 1920×804 (2.39:1) | 1080p | 1920×804 |
     *
     * Two properties this buys, and why they matter:
     *
     *  - **Orientation-symmetric.** The long side is the budget, whichever
     *    axis it is on, so a portrait source is never squeezed into a
     *    landscape frame (or the reverse).
     *  - **Budget-preserving.** The tier still caps the resolution, and a
     *    source smaller than the tier is never upscaled — so this cannot
     *    turn a 1080p export into a 4K one.
     *
     * @param sourceWidthPx / [sourceHeightPx] the reference clip's
     *   rotation-corrected display dimensions, or `<= 0` when unknown.
     * @return `width to height`, both even (encoders require it) and at
     *   least 2.
     */
    fun resolveOutputSize(
        canvasPreset: CanvasPreset,
        sourceWidthPx: Int,
        sourceHeightPx: Int,
        qualityWidthPx: Int,
        qualityHeightPx: Int
    ): Pair<Int, Int> {
        if (canvasPreset.exportWidth > 0 && canvasPreset.exportHeight > 0) {
            return canvasPreset.exportWidth to canvasPreset.exportHeight
        }
        if (sourceWidthPx <= 0 || sourceHeightPx <= 0) {
            return qualityWidthPx to qualityHeightPx
        }
        val budget = maxOf(qualityWidthPx, qualityHeightPx)
        val sourceLongSide = maxOf(sourceWidthPx, sourceHeightPx)
        if (budget <= 0 || sourceLongSide <= budget) {
            return evenSize(sourceWidthPx, sourceHeightPx)
        }
        val scale = budget.toFloat() / sourceLongSide.toFloat()
        return evenSize(
            (sourceWidthPx * scale).toInt(),
            (sourceHeightPx * scale).toInt()
        )
    }

    /** Encoders reject odd dimensions; floor both to even and never below 2. */
    private fun evenSize(width: Int, height: Int): Pair<Int, Int> =
        (width.coerceAtLeast(2) and 1.inv()) to (height.coerceAtLeast(2) and 1.inv())

    /**
     * The `Presentation` layout that fits a clip into the frame the same way
     * the preview's `PlayerView` does.
     *
     * The two layouts are near mirror images, which is why this is named
     * rather than inlined: `LAYOUT_SCALE_TO_FIT` *shrinks* one axis and adds
     * bars, while `LAYOUT_SCALE_TO_FIT_WITH_CROP` *grows* one axis and lets
     * Media3 clip the overflow. Both force the output to exactly the
     * requested size, so this choice can never change the frame size — only
     * what is visible inside it.
     *
     * - [FillMode.FIT] → `LAYOUT_SCALE_TO_FIT`. Letterbox; every source pixel
     *   survives. The default, and the pre-existing behaviour.
     * - [FillMode.FILL] → `LAYOUT_SCALE_TO_FIT_WITH_CROP`. Cover; the
     *   overflow is cropped. The analogue of the preview's
     *   `RESIZE_MODE_ZOOM`, and the case that used to be silently dropped.
     * - [FillMode.MANUAL] → `LAYOUT_SCALE_TO_FIT`. The user's pan/zoom is
     *   applied *on top* by [ClipMotionEffects], so the baseline has to be a
     *   fit. Matches the preview, which also picks `RESIZE_MODE_FIT` for
     *   MANUAL and lets its `graphicsLayer` do the transform.
     */
    fun layoutFor(fillMode: FillMode): Int = when (fillMode) {
        FillMode.FIT, FillMode.MANUAL -> Presentation.LAYOUT_SCALE_TO_FIT
        FillMode.FILL -> Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
    }
}
