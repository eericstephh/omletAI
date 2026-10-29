package com.aicreatorstudio.app.model

/**
 * Pure motion-preset math, shared by the two renderers that need it: the
 * Compose live preview and the Media3 export pipeline.
 *
 * ## Why this exists
 *
 * [ClipAnimation] was **write-only**. The Motion panel stored the chosen
 * preset on the clip and the DB persisted it, but the only code that
 * rendered it lived in the editor's Compose tree, and the export had no
 * consumer for `RenderSegment.animation` at all — so picking "Zoom In" or
 * "Fade In" changed the preview and nothing else. The exported file always
 * played the clip dead still.
 *
 * The mapping below used to live in
 * `ui/screens/editor/EffectPresets.kt` as `motionPoseAt`, where the export
 * could not reach it without dragging Compose types into the render path.
 * Moving it here — into the same UI-free `model/` layer as
 * [TransitionEngine] and [AudioFade] — is what lets both renderers evaluate
 * the identical curve, so a preview and its exported file cannot drift
 * apart.
 *
 * ## What "progress" means
 *
 * [poseAt] takes a normalised `0f..1f` progress through the clip and ramps
 * **once**. It does not loop: `FADE_IN` reaches full opacity exactly at the
 * end of the clip, and `ROTATE` turns exactly one full revolution over it.
 * Callers convert a clock into that progress with [progressAt], which
 * clamps rather than wraps — a playhead parked before or after the clip
 * freezes the pose at its first or last frame instead of snapping back to
 * the start.
 *
 * ## Units
 *
 * [AnimationPose.translationXDp] / [translationYDp] are **dp**, matching
 * Compose's `graphicsLayer`. The preview converts them with the device
 * density; the export reads them through the same 430 × 280 dp reference
 * canvas [TransitionGeometry] uses for transition travel, so the two
 * renderers agree on a slide's direction and on its rough size. The export
 * is the resolution-independent reading of the two, which is the one you
 * want baked into a file that may be played back at any size.
 */
object ClipMotion {

    /**
     * Horizontal travel of the slide presets, in dp. Authored against the
     * same reference canvas as [TransitionGeometry] — see [AnimationPose].
     */
    const val SLIDE_X_DP: Float = 24f

    /** Vertical travel of the slide presets, in dp. See [SLIDE_X_DP]. */
    const val SLIDE_Y_DP: Float = 20f

    /** Scale `ZOOM_IN` starts from, growing to `1f` by the end of the clip. */
    const val ZOOM_IN_START_SCALE: Float = 0.45f

    /** Scale `ZOOM_OUT` starts from, shrinking to `1f` by the end of the clip. */
    const val ZOOM_OUT_START_SCALE: Float = 1.3f

    /**
     * Alpha below which a dip is worth a compositing pass.
     *
     * Matches the epsilon `TransitionGeometry` uses for the same decision, so
     * a clip hovering at ~1.0 does not pay for a wash it cannot show.
     */
    const val ALPHA_EPSILON: Float = 0.01f

    /**
     * Clip-local progress (`0f..1f`) for a position [clipLocalMs] inside a
     * clip of [clipDurationMs].
     *
     * Clamped, not wrapped. A `%` here would make every preset loop for the
     * whole length of the clip — a `FADE_IN` would pulse, and a playhead
     * parked outside the clip would report a meaningless mid-ramp phase.
     * Clamping instead freezes the pose at the clip's first or last frame,
     * which is what a one-shot animation should do.
     *
     * A non-positive [clipDurationMs] yields `0f`: there is no span to
     * normalise against, and `0f` is the pose the clip starts in.
     */
    fun progressAt(clipLocalMs: Long, clipDurationMs: Long): Float {
        if (clipDurationMs <= 0L) return 0f
        val progress = clipLocalMs.toDouble() / clipDurationMs.toDouble()
        return progress.coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * The pose [animation] wants at [progress] through the clip.
     *
     * `progress` is clamped, so a caller that passes a raw ratio gets the
     * end-of-clip pose rather than an extrapolated one — a `ROTATE` at
     * `progress = 2f` is `360°`, not `720°`. A non-finite `progress` reads
     * as `0f` so a bad clock cannot produce a NaN pose (which would blank
     * the whole export rather than fail loudly).
     */
    fun poseAt(animation: ClipAnimation, progress: Float): AnimationPose {
        val t = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
        // Triangle wave: 0 -> 1 -> 0 across the clip. Used by FADE_IN_OUT.
        val pingPong = if (t < 0.5f) t * 2f else (1f - t) * 2f
        return when (animation) {
            ClipAnimation.NONE -> AnimationPose()
            ClipAnimation.FADE_IN -> AnimationPose(alpha = t)
            ClipAnimation.FADE_OUT -> AnimationPose(alpha = 1f - t)
            ClipAnimation.FADE_IN_OUT -> AnimationPose(alpha = pingPong)
            ClipAnimation.SLIDE_LEFT ->
                AnimationPose(translationXDp = lerp(SLIDE_X_DP, -SLIDE_X_DP, t))
            ClipAnimation.SLIDE_RIGHT ->
                AnimationPose(translationXDp = lerp(-SLIDE_X_DP, SLIDE_X_DP, t))
            ClipAnimation.SLIDE_UP ->
                AnimationPose(translationYDp = lerp(SLIDE_Y_DP, -SLIDE_Y_DP, t))
            ClipAnimation.SLIDE_DOWN ->
                AnimationPose(translationYDp = lerp(-SLIDE_Y_DP, SLIDE_Y_DP, t))
            ClipAnimation.ZOOM_IN -> {
                val s = ZOOM_IN_START_SCALE + (1f - ZOOM_IN_START_SCALE) * t
                AnimationPose(scaleX = s, scaleY = s)
            }
            ClipAnimation.ZOOM_OUT -> {
                val s = ZOOM_OUT_START_SCALE - (ZOOM_OUT_START_SCALE - 1f) * t
                AnimationPose(scaleX = s, scaleY = s)
            }
            ClipAnimation.ROTATE -> AnimationPose(rotationZ = 360f * t)
        }
    }

    /**
     * Whether [animation] ever fades the frame out — i.e. whether its pose
     * reaches an alpha below `1f`.
     *
     * This matters only on the export side. The preview fades the clip with
     * `graphicsLayer { alpha = … }` toward whatever sits behind it, but an
     * MP4 has no alpha channel to write, so the export has to reproduce the
     * fade as a full-frame wash. See
     * [com.aicreatorstudio.app.export.ClipMotionEffects].
     *
     * The slide, zoom and rotate presets leave alpha at `1f` and therefore
     * cost no extra compositing pass.
     */
    fun fadesClip(animation: ClipAnimation): Boolean = when (animation) {
        ClipAnimation.FADE_IN,
        ClipAnimation.FADE_OUT,
        ClipAnimation.FADE_IN_OUT -> true
        else -> false
    }

    /**
     * Whether a clip with this motion preset and this static [clipOpacity]
     * ever renders below full opacity — and so needs a full-frame wash on
     * export.
     *
     * Two independent sources can darken a clip, and the export needs to
     * know about **either**:
     *
     * - the fade presets, which ramp alpha across the clip ([fadesClip]);
     * - the per-clip `opacity` slider, which holds it down for the clip's
     *   whole length.
     *
     * They are asked together rather than separately because the answer
     * decides whether a GL pass is built at all — a clip that is neither
     * fading nor translucent must cost nothing. The preview already
     * multiplies the two into one `graphicsLayer` alpha, so this mirrors the
     * decision the preview has already made.
     */
    fun darkensClip(animation: ClipAnimation, clipOpacity: Float): Boolean =
        fadesClip(animation) || clipOpacity.isFinite() && clipOpacity < 1f - ALPHA_EPSILON

    /**
     * Folds the static clip inputs — the motion preset [motion], the
     * `opacity` slider, and the Transform panel's [transformScale] and
     * canvas-fraction pan — onto a transition [transition].
     *
     * This is the same composition the preview performs in its
     * `graphicsLayer`: scales multiply, translations and rotations add,
     * alphas multiply. Composing here — rather than emitting one matrix per
     * source — means a clip that uses several at once is transformed
     * **once**, exactly as the preview does. Stacked matrices would instead
     * multiply, which agrees for rotations but silently scales one source's
     * travel by another's zoom.
     *
     * ## Why the Transform panel's scale and pan are folded in here
     *
     * They used to be handled by Media3's `ScaleAndRotateTransformation`,
     * which was wrong in two different ways:
     *
     * - **The scale was a silent no-op.** That effect's `configure` returns
     *   `Size(inputWidth * scaleX, inputHeight * scaleY)` and then
     *   `postScale(1/scaleX, 1/scaleY)` — it deliberately *grows* the frame
     *   so no input pixel is lost. The later `Presentation` scales the frame
     *   back to the canvas with `LAYOUT_SCALE_TO_FIT`, exactly undoing the
     *   user's zoom.
     * - **The pan had no route at all**, so it was simply dropped.
     *
     * [com.aicreatorstudio.app.export.ClipMotionEffects]' matrix clips rather
     * than growing, so a zoom-in crops and a zoom-out letterboxes — which is
     * what the preview's `graphicsLayer` does. Rotation and flip stay in
     * `ScaleAndRotateTransformation`, because for those, growing the frame to
     * keep every pixel *is* correct. Moving only the uniform scale here does
     * not change the composite order: a uniform scale commutes with both the
     * rotation and the flip.
     *
     * ## Units
     *
     * [panXFraction] / [panYFraction] are **canvas fractions**, matching
     * `ClipTransform.offsetX` / `offsetY` (`-0.5f..+0.5f`, clamped by
     * `EditorViewModel.setClipTransform`). They are converted into the
     * reference-dp space the pose carries, so they share one translation
     * field with the transition and preset contributions.
     *
     * `blurDp` / `flashWhite` / `flashBlack` are transition-only and pass
     * through untouched. A non-finite [clipOpacity] reads as `1f` and a
     * non-finite or non-positive [transformScale] reads as `1f`, so a bad
     * value cannot blank or invert a segment.
     */
    fun compose(
        transition: TransitionPose,
        motion: AnimationPose,
        clipOpacity: Float = 1f,
        transformScale: Float = 1f,
        panXFraction: Float = 0f,
        panYFraction: Float = 0f
    ): TransitionPose {
        val opacity = if (clipOpacity.isFinite()) clipOpacity.coerceIn(0f, 1f) else 1f
        val scale = if (transformScale.isFinite() && transformScale > 0f) transformScale else 1f
        val panX = if (panXFraction.isFinite()) panXFraction else 0f
        val panY = if (panYFraction.isFinite()) panYFraction else 0f
        return transition.copy(
            alpha = (transition.alpha * motion.alpha * opacity).coerceIn(0f, 1f),
            scaleX = transition.scaleX * motion.scaleX * scale,
            scaleY = transition.scaleY * motion.scaleY * scale,
            translationXDp = transition.translationXDp +
                motion.translationXDp +
                panX * TransitionGeometry.REFERENCE_WIDTH_DP,
            translationYDp = transition.translationYDp +
                motion.translationYDp +
                panY * TransitionGeometry.REFERENCE_HEIGHT_DP,
            rotationZDeg = transition.rotationZDeg + motion.rotationZ
        )
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}

/**
 * A motion preset evaluated at one instant, in the same shape as the
 * preview's `graphicsLayer` parameters.
 *
 * [translationXDp] / [translationYDp] are dp and use the **screen**
 * convention — positive Y is down — matching Compose. The export reads them
 * through [TransitionGeometry], which is where the flip into Media3's Y-up
 * normalized device coordinates happens.
 */
data class AnimationPose(
    val alpha: Float = 1f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val translationXDp: Float = 0f,
    val translationYDp: Float = 0f,
    val rotationZ: Float = 0f
)
