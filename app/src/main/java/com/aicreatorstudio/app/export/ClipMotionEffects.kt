package com.aicreatorstudio.app.export

import android.graphics.Bitmap
import android.graphics.Color
import androidx.media3.common.Effect
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.ConvolutionFunction1D
import androidx.media3.effect.GaussianFunction
import androidx.media3.effect.GlMatrixTransformation
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.SeparableConvolution
import com.aicreatorstudio.app.model.AnimationPose
import com.aicreatorstudio.app.model.CanvasBackground
import com.aicreatorstudio.app.model.CanvasLetterbox
import com.aicreatorstudio.app.model.ClipAnimation
import com.aicreatorstudio.app.model.ClipMotion
import com.aicreatorstudio.app.model.TransitionConfig
import com.aicreatorstudio.app.model.TransitionDir
import com.aicreatorstudio.app.model.TransitionEngine
import com.aicreatorstudio.app.model.TransitionGeometry
import com.aicreatorstudio.app.model.TransitionPose

/**
 * 2026-09-16 — bakes **per-clip motion and transparency** into the exported
 * MP4: the transition on each end of the clip, the clip's motion preset, and
 * the clip's static `opacity`.
 *
 * ## The gap this closes
 *
 * The editor ships 43 transition types and a real [TransitionEngine], and
 * the Transition panel writes the user's choice onto the clip
 * (`transitionIn` / `transitionOut`) where it is persisted. But nothing
 * ever *rendered* it: the only code that would have
 * (`EditorVideoCanvas.kt`) is orphaned with no call sites, and the export
 * had no field on [RenderSegment] to receive it. Choosing a transition
 * therefore changed nothing, on screen or in the file.
 *
 * The motion preset (`ClipAnimation`) had the mirror-image problem: the
 * preview honoured it and the export had no consumer at all, so the file
 * always played the clip dead still.
 *
 * ## Why one object and not two
 *
 * Both features are the *same* per-frame pose, evaluated from the same
 * clock and applied through the same matrix. A clip can carry a transition
 * and an animation at once, and the preview composes them component-wise —
 * scales multiply, translations and rotations add, alphas multiply. That
 * composition lives in [ClipMotion.compose] and is folded into a single
 * [TransitionPose] here, so the export transforms the frame **once**.
 * Emitting a second matrix effect instead would multiply the two matrices,
 * which agrees for rotations but silently scales a transition's travel by
 * the animation's zoom.
 *
 * ## How the pose is baked
 *
 * Media3 1.5.0 exposes three genuinely per-frame hooks, and each is used
 * for the part of a pose it can express. (Verified against the cached
 * `media3-effect` sources — `GlEffect.toGlShaderProgram` has **no**
 * timestamp parameter, so `AlphaScale` / `RgbMatrix` are static per
 * instance and cannot animate.)
 *
 * | Pose component | Mechanism |
 * |---|---|
 * | `scaleX` / `scaleY` / `translationXDp` / `translationYDp` / `rotationZDeg` / `rotationXDeg` | [ClipMotionMatrixEffect] — `GlMatrixTransformation.getGlMatrixArray(presentationTimeUs)` |
 * | `blurDp` | [ClipMotionBlurEffect] — `SeparableConvolution.getConvolution(presentationTimeUs)` |
 * | `flashWhite` / `flashBlack` / `alpha` | [ClipMotionWashOverlay] — `TextureOverlay.getOverlaySettings(presentationTimeUs).alphaScale` |
 *
 * The alpha component needs **three** overlays rather than one, because the
 * fade and the two explicit flashes differ in *colour*: the fade washes toward
 * the canvas, a black flash is black and a white flash is white. All three go
 * into a single `OverlayEffect` and are drawn in the preview's own paint
 * order — see [ClipMotionWashOverlay].
 *
 * `DefaultShaderProgram.drawFrame` re-queries the matrix every frame
 * (`matrixTransformations.get(i).getGlMatrixArray(presentationTimeUs)`), so
 * this is genuinely per-frame animation, not a static effect.
 *
 * The timestamps the chain sees are **item-relative**: each
 * `EditedMediaItem` is its own stream, and
 * `ExoAssetLoaderVideoRenderer.shouldDropInputBuffer` does
 * `inputBuffer.timeUs -= streamStartPositionUs` before handing the buffer to
 * the decoder. That is what makes "clip-local" a meaningful thing to compute
 * here, and it is why a clip that starts at 4s in the editor still animates
 * from its own first frame.
 *
 * ## Additive by construction
 *
 * A segment with neither a transition nor an animation yields an empty list
 * here and the effect chain is byte-for-byte what it was before. That is
 * deliberate: this change cannot regress an export that used neither.
 */
@UnstableApi
internal object ClipMotionEffects {

    /**
     * Build the transition + motion-preset effects for [segment], in the
     * order they must be applied.
     *
     * Returns an empty list when the segment has neither, or when what it
     * has resolves to a no-op.
     *
     * **The washes are no longer sized from a caller-supplied frame size, and
     * that is the fix for a real defect.** They used to be, which meant the
     * engine had to *know* the frame before it could emit one — and there is a
     * case where it cannot: a FREE canvas with a single clip, whose frame is
     * grown to a rotated bounding box by step 1's
     * `ScaleAndRotateTransformation`. The old code skipped every wash there, so
     * a rotated clip's fade silently became a cut. Each wash now reads the size
     * from [ClipMotionWashOverlay.configure], which Media3 calls with the real
     * frame dimensions before the first draw — the same route [VignetteOverlay]
     * and [CanvasBackgroundOverlay] already use. A size that is *observed*
     * cannot be wrong; a size that is *guessed* can, and a mis-sized wash
     * covers the frame only partially (`OverlayMatrixProvider` scales by
     * `overlaySize / backgroundSize`), leaving an un-faded border.
     *
     * @param outputHeightPx the height of the frame the convolution will run
     *   on, used **only** to convert the pose's dp blur into a pixel sigma. It
     *   stays a parameter because
     *   [androidx.media3.effect.SeparableConvolution] exposes no `configure`
     *   hook to observe the real height with — so the caller must supply it, and
     *   [blurReferenceHeightPx] is the function that decides what it is. The
     *   sigma is floored, so a `0` degrades to no blur instead of throwing.
     *
     * @param canvasBackground the project's canvas fill, used as the colour of
     *   the **fade** wash. The preview fades a clip toward whatever is behind
     *   it, which is the letterbox `Box`'s colour, so the export has to fade
     *   toward the same colour — otherwise a coloured canvas shows the clip
     *   dimming to black while its own bars stay coloured. Defaults to
     *   [CanvasBackground.Black], which is both the engine's native bar colour
     *   and the value that reproduces the previous behaviour exactly. See
     *   [CanvasLetterbox.fadeColorArgb].
     */
    fun build(
        segment: RenderSegment,
        outputHeightPx: Int,
        canvasBackground: CanvasBackground = CanvasBackground.Black
    ): List<Effect> {
        val inCfg = segment.transitionIn
        val outCfg = segment.transitionOut
        val hasTransition = inCfg != null || outCfg != null
        // A zero-length clip has no span to normalise progress against, so
        // an animation on one is skipped rather than pinned to its first
        // frame — for a fade that first frame is fully transparent, which
        // would blank the segment.
        val hasAnimation =
            segment.animation != ClipAnimation.NONE && segment.sourceDurationMs > 0L
        val clipOpacity = if (segment.opacity.isFinite()) {
            segment.opacity.coerceIn(0f, 1f)
        } else {
            1f
        }
        // 2026-09-16: `opacity` was the last write-only field on this class.
        // The Opacity panel wrote it, the DB persisted it, the preview
        // multiplied it into its `graphicsLayer` alpha — and the export read
        // it nowhere, so a half-transparent clip exported fully opaque. It
        // needs no new mechanism: an MP4 has no alpha channel, so a reduced
        // clip alpha becomes a wash exactly the way a fade does.
        val darkens = clipOpacity < 1f - ClipMotion.ALPHA_EPSILON

        // 2026-09-16: the Transform panel's pan and zoom. The pan used to be
        // dropped outright, and the zoom was a silent no-op — see
        // [ClipMotion.compose] for why `ScaleAndRotateTransformation` can
        // express neither. Both are a canvas-fraction pair and a uniform
        // factor, so they fold into the same pose as the transitions and the
        // motion presets rather than needing their own pass.
        val hasGeometry = hasGeometryPose(segment)
        if (!hasGeometry && !darkens) return emptyList()

        val effects = mutableListOf<Effect>()

        // Geometry first, so the blur and the wash passes see the moved
        // frame. Skipped when the clip has no geometry at all — a clip that
        // is merely translucent needs the wash but no transform.
        if (hasGeometry) {
            effects.add(ClipMotionMatrixEffect(geometryPoseTimeline(segment)))
        }

        if (hasTransition &&
            anyOf(inCfg, outCfg) { c, d -> TransitionGeometry.needsBlur(c, d) }
        ) {
            effects.add(
                ClipMotionBlurEffect(
                    transitionIn = inCfg,
                    transitionOut = outCfg,
                    sourceDurationMs = segment.sourceDurationMs,
                    speed = segment.speed,
                    outputHeightPx = outputHeightPx
                )
            )
        }

        // Three sources can dim a clip toward the canvas: a fading
        // transition, a fading motion preset, and the `opacity` slider.
        // The preview folds all three into the single `graphicsLayer`
        // alpha it composites with, so the export folds them into one
        // *fade* wash rather than drawing one pass per source.
        val needsFadeWash =
            (hasTransition &&
                anyOf(inCfg, outCfg) { c, d -> TransitionGeometry.needsFadeOverlay(c, d) }) ||
                (hasAnimation && ClipMotion.fadesClip(segment.animation)) ||
                darkens
        // An explicit black flash is a *separate* pass with a fixed black
        // colour, drawn on top of the fade. Merging the two — which this
        // did until 2026-09-16 — is what made a fade wash toward black on
        // a coloured canvas, and it also collapsed two opacities that the
        // preview *composes* into their maximum.
        val needsBlackFlash =
            hasTransition &&
                anyOf(inCfg, outCfg) { c, d ->
                    TransitionGeometry.needsBlackFlashOverlay(c, d)
                }
        val needsWhiteWash =
            hasTransition &&
                anyOf(inCfg, outCfg) { c, d -> TransitionGeometry.needsWhiteOverlay(c, d) }

        // NOTE: a `TextureOverlay` is not itself an `Effect` — it is a
        // *component* of `OverlayEffect`. All three washes therefore go
        // into a single `OverlayEffect` rather than three chain entries.
        // One pass, three overlays, which is also cheaper than three
        // compositing passes.
        //
        // ORDER IS LOAD-BEARING. `OverlayShaderProgram` emits
        // `fragColor = getMixColor(fragColor, overlayN)` in list order, so
        // the first overlay is the bottom-most and each later one is drawn
        // over the accumulated result. The order below — fade, black
        // flash, white flash — is exactly the order the preview composites
        // in: the clip at its own alpha over the canvas, then a black
        // `Box`, then a white `Box` (`EditorScreen`, layer 2.4).
        //
        // No frame size is needed here: each overlay reads it from its own
        // `configure`. That is what makes these passes survive the one case
        // the engine cannot size for — see the `build` KDoc.
        val washes = mutableListOf<androidx.media3.effect.TextureOverlay>()
        if (needsFadeWash) {
            washes.add(
                ClipMotionWashOverlay(
                    colorArgb = CanvasLetterbox.fadeColorArgb(canvasBackground),
                    transitionIn = inCfg,
                    transitionOut = outCfg,
                    animation = if (hasAnimation) segment.animation else ClipAnimation.NONE,
                    sourceDurationMs = segment.sourceDurationMs,
                    speed = segment.speed,
                    // Carries the clip's static opacity as well as the
                    // fade ramps — all three alpha sources are already
                    // folded into the pose by [PoseTimeline].
                    clipOpacity = clipOpacity,
                    alphaOf = TransitionGeometry::fadeAlpha
                )
            )
        }
        if (needsBlackFlash) {
            washes.add(
                ClipMotionWashOverlay(
                    colorArgb = Color.BLACK,
                    transitionIn = inCfg,
                    transitionOut = outCfg,
                    animation = ClipAnimation.NONE,
                    sourceDurationMs = segment.sourceDurationMs,
                    speed = segment.speed,
                    // Deliberately 1f, exactly as for the white pass: an
                    // explicit flash is drawn ON TOP of the clip at its
                    // own opacity, and the preview does not scale it by
                    // the clip's opacity either. Only the fade pass reads
                    // `clipOpacity`, and that is where the slider lands.
                    clipOpacity = 1f,
                    alphaOf = TransitionGeometry::flashBlackAlpha
                )
            )
        }
        if (needsWhiteWash) {
            washes.add(
                ClipMotionWashOverlay(
                    colorArgb = Color.WHITE,
                    transitionIn = inCfg,
                    transitionOut = outCfg,
                    animation = ClipAnimation.NONE,
                    sourceDurationMs = segment.sourceDurationMs,
                    speed = segment.speed,
                    clipOpacity = 1f,
                    alphaOf = TransitionGeometry::whiteoutAlpha
                )
            )
        }
        if (washes.isNotEmpty()) {
            effects.add(androidx.media3.effect.OverlayEffect(washes))
        }
        return effects
    }

    /**
     * True when [predicate] holds for whichever of the two transitions the
     * segment actually has. A transition can be present on either end
     * independently, so both are probed.
     */
    private inline fun anyOf(
        inCfg: TransitionConfig?,
        outCfg: TransitionConfig?,
        predicate: (TransitionConfig, TransitionDir) -> Boolean
    ): Boolean =
        (inCfg != null && predicate(inCfg, TransitionDir.IN)) ||
            (outCfg != null && predicate(outCfg, TransitionDir.OUT))

    /**
     * Whether [segment] carries any per-frame pose at all — a transition, a
     * motion preset, or the Transform panel's pan/zoom.
     *
     * Extracted so the decision cannot drift between the pass that *applies*
     * the pose ([build]'s `hasGeometry`) and the pass that has to *track* it
     * ([CanvasBackgroundOverlay], which needs to know whether the video can
     * move at all before it decides to skip its plate).
     *
     * A zero-length clip's animation is ignored, matching [build]: there is no
     * span to normalise progress against, so the preset is dropped rather than
     * pinned to its first frame.
     */
    internal fun hasGeometryPose(segment: RenderSegment): Boolean {
        if (segment.transitionIn != null || segment.transitionOut != null) return true
        if (segment.animation != ClipAnimation.NONE && segment.sourceDurationMs > 0L) return true
        val t = segment.transform
        val panX = if (t.offsetX.isFinite()) t.offsetX else 0f
        val panY = if (t.offsetY.isFinite()) t.offsetY else 0f
        return panX != 0f || panY != 0f || t.scale != 1f
    }

    /**
     * The single construction of the geometry pose for [segment], shared by
     * [ClipMotionMatrixEffect] and [CanvasBackgroundOverlay].
     *
     * Deliberately reads only the **geometry** inputs. `clipOpacity` is left at
     * its `1f` default because opacity rides the wash pass, not the matrix, and
     * folding it in here would make the plate's silhouette disagree with the
     * frame the matrix actually produces.
     *
     * The pan is normalised to `0f` when non-finite, matching [build]'s own
     * guard: a NaN offset would otherwise propagate into every pose sample and
     * blank the export instead of failing loudly.
     */
    internal fun geometryPoseTimeline(segment: RenderSegment): PoseTimeline = PoseTimeline(
        transitionIn = segment.transitionIn,
        transitionOut = segment.transitionOut,
        animation = if (segment.animation != ClipAnimation.NONE &&
            segment.sourceDurationMs > 0L
        ) {
            segment.animation
        } else {
            ClipAnimation.NONE
        },
        sourceDurationMs = segment.sourceDurationMs,
        speed = segment.speed,
        transformScale = segment.transform.scale,
        panXFraction = if (segment.transform.offsetX.isFinite()) {
            segment.transform.offsetX
        } else {
            0f
        },
        panYFraction = if (segment.transform.offsetY.isFinite()) {
            segment.transform.offsetY
        } else {
            0f
        }
    )
}

/**
 * Resolves the clip-local pose at an output-clock timestamp, with the
 * clip's motion preset, static opacity, and the Transform panel's
 * scale/pan folded in.
 *
 * Shared by all three effect kinds so they cannot disagree about where the
 * motion is — a mismatch here would show up as a blur that peaks at a
 * different moment than the zoom it belongs to, or a wash that lags the
 * fade it is standing in for.
 *
 * The three static inputs each default to identity — [clipOpacity] to
 * `1f`, [transformScale] to `1f`, and the pan to `0f` — because only some
 * passes read them. The matrix pass reads geometry and ignores alpha, the
 * blur pass reads neither, and the wash pass reads alpha but is drawn in
 * *screen* space, so it must not pan with the clip it is washing. A caller
 * therefore threads only what its pass actually looks at, rather than
 * every pass carrying every field.
 *
 * `internal` rather than `private` since 2026-09-16: [CanvasBackgroundOverlay]
 * needs the *same* pose to punch its plate out around the posed video
 * silhouette, and a second copy of this arithmetic is exactly the kind of
 * duplication that drifts. The two callers must agree frame for frame — a
 * mismatch would show up as a seam between the painted bar and the video's own
 * edge — so they share one evaluator and, via [geometryPoseTimeline], one
 * construction of it.
 */
@UnstableApi
internal class PoseTimeline(
    private val transitionIn: TransitionConfig?,
    private val transitionOut: TransitionConfig?,
    private val animation: ClipAnimation,
    private val sourceDurationMs: Long,
    private val speed: Float,
    private val clipOpacity: Float = 1f,
    private val transformScale: Float = 1f,
    private val panXFraction: Float = 0f,
    private val panYFraction: Float = 0f
) {
    fun poseAt(presentationTimeUs: Long): TransitionPose {
        val clipLocalMs = CompositionTimeline.clipLocalMsForOutput(presentationTimeUs, speed)
        val base = TransitionEngine.poseForClipLocal(
            clipLocalMs = clipLocalMs,
            clipDurationMs = sourceDurationMs,
            transitionIn = transitionIn,
            transitionOut = transitionOut
        )
        // The fast path: nothing to fold, so hand back the transition pose
        // untouched and skip the allocation.
        //
        // Deliberately written as exact comparisons against the identity
        // values rather than against [ClipMotion]'s normalised ones. That
        // makes the guard one-sided: it can only fire for inputs that are
        // already exactly identity, so it can never *skip* a fold that
        // [ClipMotion.compose] would have applied. A non-finite or
        // non-positive scale (which `compose` normalises back to `1f`) simply
        // fails the `== 1f` test and takes the slow path to the same answer,
        // costing one allocation instead of a wrong frame.
        if (animation == ClipAnimation.NONE &&
            clipOpacity >= 1f &&
            transformScale == 1f &&
            panXFraction == 0f &&
            panYFraction == 0f
        ) {
            return base
        }
        val motion = if (animation == ClipAnimation.NONE) {
            AnimationPose()
        } else {
            ClipMotion.poseAt(
                animation = animation,
                progress = ClipMotion.progressAt(clipLocalMs, sourceDurationMs)
            )
        }
        return ClipMotion.compose(
            transition = base,
            motion = motion,
            clipOpacity = clipOpacity,
            transformScale = transformScale,
            panXFraction = panXFraction,
            panYFraction = panYFraction
        )
    }
}

/**
 * Animates the frame's geometry by supplying a fresh NDC matrix per frame.
 *
 * `configure` returns the **input** size unchanged, which is the crucial
 * detail. Media3's `MatrixTransformation` convention — followed by
 * `ScaleAndRotateTransformation` — is to *grow* the output so that no input
 * pixel is lost. That is the opposite of what a transition or a zoom needs:
 * a zoom must crop and a push must let the frame leave the canvas.
 * Implementing `GlMatrixTransformation` directly (rather than
 * `MatrixTransformation`) and reporting the input size keeps Media3's
 * clipping behaviour, so anything moved outside NDC is clipped instead of
 * shrinking the frame to fit.
 *
 * That clipping behaviour is also what lets the Transform panel's zoom and
 * pan live here instead of in a `ScaleAndRotateTransformation`. That effect
 * grows the frame to preserve every input pixel, and the downstream
 * `Presentation` then scales it back — which cancels the user's zoom
 * outright and has no term for the pan at all. Here the same two inputs
 * arrive as one more factor and one more offset in the pose, and a zoom-in
 * genuinely crops.
 */
@UnstableApi
private class ClipMotionMatrixEffect(
    private val timeline: PoseTimeline
) : GlMatrixTransformation {

    /**
     * Frame aspect ratio, captured in [configure]. Rotation has to be
     * applied in an aspect-corrected space or a rotated rectangle shears —
     * see [TransitionGeometry.matrix].
     */
    private var aspectRatio: Float = 1f

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        aspectRatio = if (inputWidth > 0 && inputHeight > 0) {
            inputWidth.toFloat() / inputHeight.toFloat()
        } else {
            1f
        }
        return Size(inputWidth, inputHeight)
    }

    override fun getGlMatrixArray(presentationTimeUs: Long): FloatArray =
        TransitionGeometry.matrix(timeline.poseAt(presentationTimeUs), aspectRatio)
}

/**
 * Animates the frame's blur by returning a fresh Gaussian kernel per frame.
 *
 * Outside the transition window the pose's `blurDp` is `0`, which
 * [TransitionGeometry.blurSigmaPx] reports as `0f`; the sigma is then
 * floored to a sub-pixel value because `GaussianFunction` rejects a
 * non-positive sigma outright. A 0.1px kernel is visually
 * indistinguishable from no blur, so the pass costs a little GPU time
 * across the rest of the clip but does not soften the picture.
 *
 * Motion presets carry no blur, so a clip with an animation but no
 * transition never reaches this class — [ClipMotionEffects.build] only adds
 * it when a transition actually blurs.
 */
/**
 * The frame height the dp-authored blur must be scaled against.
 *
 * [ClipMotionBlurEffect] turns a pose's `blurDp` into a pixel sigma using the
 * frame height, so it needs the height of the frame the convolution will
 * actually run on — and that is **not** always the size
 * [ExportFrame.resolve] returned:
 *
 *  - With a `Presentation` — a canvas preset carrying explicit export
 *    dimensions, or any multi-clip project — the frame is pinned to the
 *    resolved size, so the resolved height is already right.
 *  - With no `Presentation` and no rotation, step 1 leaves the frame alone, so
 *    the resolved height is right again.
 *  - With no `Presentation` **and** a rotation — a FREE canvas holding a single
 *    rotated clip — step 1 has already grown the frame to the rotated bounding
 *    box, so the resolved height under-scales the blur by the rotation factor.
 *
 * That last case is why this is a function rather than a straight pass-through.
 * It used to be documented as a bounded approximation; the value is in fact
 * knowable, because the frame entering step 1 *is* the resolved size.
 *
 * [CanvasLetterbox.rotatedBounds] supplies the geometry, deliberately: it is the
 * same function the canvas plate uses, so the two passes agree on what "the
 * frame" is. A second, independent notion of the rotated frame is exactly how
 * they would drift apart.
 *
 * A non-positive resolved height is returned unchanged so the sigma stays `0`
 * and the blur is skipped, rather than producing a NaN sigma.
 *
 * [rotationDegrees] is the clip transform's rotation, which is always a
 * quarter-turn — `0` / `90` / `180` / `270`, per [RenderSegment] — hence `Int`.
 * [CanvasLetterbox.rotatedBounds] takes a `Float` because a *pose* can rotate by
 * an arbitrary angle; the frame geometry here only ever turns by a multiple of
 * 90, so the bounding box is exact and no rounding error can creep in.
 */
internal fun blurReferenceHeightPx(
    resolvedWidthPx: Int,
    resolvedHeightPx: Int,
    rotationDegrees: Int,
    frameIsPinned: Boolean
): Int {
    if (frameIsPinned) return resolvedHeightPx
    if (resolvedWidthPx <= 0 || resolvedHeightPx <= 0) return resolvedHeightPx
    return Math.round(
        CanvasLetterbox.rotatedBounds(
            sourceWidth = resolvedWidthPx,
            sourceHeight = resolvedHeightPx,
            rotationDegrees = rotationDegrees.toFloat()
        ).second
    )
}

@UnstableApi
private class ClipMotionBlurEffect(
    transitionIn: TransitionConfig?,
    transitionOut: TransitionConfig?,
    sourceDurationMs: Long,
    speed: Float,
    private val outputHeightPx: Int
) : SeparableConvolution() {

    private val timeline = PoseTimeline(
        transitionIn = transitionIn,
        transitionOut = transitionOut,
        animation = ClipAnimation.NONE,
        sourceDurationMs = sourceDurationMs,
        speed = speed
    )

    override fun getConvolution(presentationTimeUs: Long): ConvolutionFunction1D {
        val sigma = TransitionGeometry
            .blurSigmaPx(timeline.poseAt(presentationTimeUs), outputHeightPx)
            .coerceAtLeast(MIN_SIGMA_PX)
        return GaussianFunction(sigma, NUM_STANDARD_DEVIATIONS)
    }

    private companion object {
        /** Smallest sigma `GaussianFunction` accepts. */
        const val MIN_SIGMA_PX = 0.1f
        /** Kernel half-width, in standard deviations. Matches `GaussianBlur`'s default. */
        const val NUM_STANDARD_DEVIATIONS = 2.0f
    }
}

/**
 * Paints a full-frame solid-colour wash whose opacity animates per frame —
 * the mechanism behind the flash family, the fade family, the fading motion
 * presets, and the per-clip `opacity` slider.
 *
 * ## Why an overlay and not `AlphaScale`
 *
 * `AlphaScale` would be the obvious tool, but it is a `GlEffect` and
 * `GlEffect.toGlShaderProgram(Context, boolean)` carries no timestamp, so
 * an instance is static. It would also be invisible on the base layer: the
 * encoder writes no alpha channel, so scaling the frame's alpha only
 * affects how it composites *against something else*. A full-frame overlay
 * is the only route that both animates and survives encoding.
 *
 * ## The colour and the opacity are both chosen by the caller
 *
 * [colorArgb] is fixed per instance and [alphaOf] extracts the animated
 * opacity from the pose, because the export needs **three** distinct washes
 * and only the pair differs:
 *
 * | Pass | Colour | `alphaOf` |
 * |---|---|---|
 * | fade — transition alpha, fading preset, `opacity` slider | the canvas colour | [TransitionGeometry.fadeAlpha] |
 * | explicit black flash | black | [TransitionGeometry.flashBlackAlpha] |
 * | explicit white flash | white | [TransitionGeometry.whiteoutAlpha] |
 *
 * All three are, in the preview, a full-frame pass with a per-frame alpha, so
 * all three are this class; only the two constants differ. They are drawn in
 * list order by `OverlayShaderProgram`, so the fade sits *underneath* the
 * flashes — the same paint order the preview uses.
 *
 * The fade's colour is the **canvas** colour rather than black because the
 * preview fades a clip by compositing it over the letterbox `Box` at a reduced
 * alpha: it dims toward whatever is behind it, not toward black. See
 * [CanvasLetterbox.fadeColorArgb]. Until 2026-09-16 this class hardcoded black
 * and folded `flashBlack` into the same pass — exact on the default canvas,
 * visibly wrong on a coloured one, and an under-darkening wherever a
 * transition sets both.
 *
 * The bitmap is created lazily on the first frame the GL thread asks for
 * it, so a clip whose transition turns out never to flash pays nothing.
 *
 * ## The frame size is observed, not passed in
 *
 * `OverlayShaderProgram.configure` calls `TextureOverlay.configure(videoSize)`
 * with the **real** frame dimensions before the first `drawFrame`, so
 * [configure] records them and [getBitmap] sizes the bitmap from that record.
 * It is rebuilt if the size ever changes, which is what keeps a mid-stream
 * resize from leaving a stale texture.
 *
 * That is not a style preference — it is the fix for a case where the size is
 * simply not knowable in advance. A FREE canvas with a single clip has no
 * `Presentation`, and step 1's `ScaleAndRotateTransformation` grows the frame
 * to a rotated bounding box; the engine cannot compute that size. When these
 * washes were sized from a caller-supplied value the engine therefore had to
 * skip them there, and a rotated clip's fade silently became a cut. Observing
 * the size removes the guess, and a size that is observed cannot be wrong.
 */
@UnstableApi
private class ClipMotionWashOverlay(
    private val colorArgb: Int,
    transitionIn: TransitionConfig?,
    transitionOut: TransitionConfig?,
    animation: ClipAnimation,
    sourceDurationMs: Long,
    speed: Float,
    clipOpacity: Float,
    // Which of the pose's wash opacities this instance paints. A function
    // reference rather than an enum, so the mapping stays next to the
    // arithmetic that defines it in [TransitionGeometry] and a fourth wash
    // needs no change here.
    private val alphaOf: (TransitionPose) -> Float
) : BitmapOverlay() {

    private val timeline = PoseTimeline(
        transitionIn = transitionIn,
        transitionOut = transitionOut,
        animation = animation,
        sourceDurationMs = sourceDurationMs,
        speed = speed,
        clipOpacity = clipOpacity
    )

    /** Frame size as reported by [configure]. See the class KDoc. */
    private var frameWidth = 0
    private var frameHeight = 0

    private var frame: Bitmap? = null
    private var builtWidth = -1
    private var builtHeight = -1

    override fun configure(videoSize: Size) {
        frameWidth = videoSize.width
        frameHeight = videoSize.height
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val w = frameWidth
        val h = frameHeight
        val cached = frame
        if (cached != null && w == builtWidth && h == builtHeight) return cached

        val created = if (w > 0 && h > 0) {
            Bitmap
                .createBitmap(w, h, Bitmap.Config.ARGB_8888)
                .also { it.eraseColor(colorArgb) }
        } else {
            // A fully transparent stand-in, so a size-less frame degrades to
            // "no wash" rather than throwing out of `createBitmap`. Unreachable
            // in practice: Media3 calls `configure` before the first
            // `drawFrame`. The old bitmap is deliberately not recycled — Media3
            // may still hold its texture, and the GC handles it.
            Bitmap
                .createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                .also { it.eraseColor(Color.TRANSPARENT) }
        }
        frame = created
        builtWidth = w
        builtHeight = h
        return created
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val alpha = alphaOf(timeline.poseAt(presentationTimeUs))
        // Both anchors at (0,0) with the default scale of 1 draws the bitmap
        // 1:1 from the frame's top-left, so a frame-sized bitmap covers it
        // exactly. No `setScale` is needed, and none should be added: the
        // bitmap is already the frame.
        return OverlaySettings.Builder()
            .setBackgroundFrameAnchor(0f, 0f)
            .setOverlayFrameAnchor(0f, 0f)
            .setAlphaScale(alpha)
            .build()
    }
}
