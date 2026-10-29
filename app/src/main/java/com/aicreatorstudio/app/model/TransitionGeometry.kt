package com.aicreatorstudio.app.model

import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure geometry bridge between [TransitionPose] and the two renderers that
 * consume it: the Compose live preview and the Media3 export pipeline.
 *
 * ## Why this exists
 *
 * [TransitionEngine] expresses a transition as a [TransitionPose] whose
 * translation is in **dp** (`translationXDp`). That unit only means
 * something relative to a canvas of a known size, and the preview and the
 * export have completely different canvases — Compose lays out in dp, while
 * Media3's vertex shader works in normalized device coordinates (NDC).
 *
 * This object converts the pose into **frame fractions**, which are unit-
 * independent, and then into the 4×4 NDC matrix Media3 needs. Both
 * renderers therefore agree on what "push left" means.
 *
 * ## The authoring reference (430 × 280 dp)
 *
 * The pose translations are not arbitrary: reading them out of
 * [TransitionEngine]'s transition table shows they were authored against a
 * ~430 × 280 dp canvas —
 *
 * - `PUSH_LEFT` / `PUSH_RIGHT` move by `±430f * a`
 * - `PUSH_UP` / `PUSH_DOWN` move by `±280f * a`
 *
 * At full intensity (`a == 1`) those are exactly one canvas width / height,
 * i.e. the frame is displaced precisely far enough to be fully off-screen.
 * That is the only self-consistent reading of the numbers, so it is the
 * reference used here. A consequence worth stating: because the result is a
 * *fraction of the frame*, the mapping is aspect-ratio independent — a
 * "push" covers the full frame whether the canvas is 16:9 or 9:16.
 *
 * ## Sign conventions
 *
 * [translationFractionX] / [translationFractionY] are expressed in the
 * **screen** convention, matching Compose: positive Y is **down**.
 *
 * [matrix] converts to Media3's convention, which is the opposite. Media3's
 * vertex shader is
 *
 * ```
 * gl_Position = uTransformationMatrix * aFramePosition;
 * ```
 *
 * and the frame's top maps to `aFramePosition.y == +1`, so **NDC positive Y
 * is up**. [matrix] therefore negates the Y translation and both rotation
 * components, which is what makes an exported push travel the same
 * direction as the preview.
 */
object TransitionGeometry {

    /**
     * Canvas width, in dp, that the transition translation values were
     * authored against. See the class KDoc for the derivation.
     */
    const val REFERENCE_WIDTH_DP: Float = 430f

    /**
     * Canvas height, in dp, that the transition translation values were
     * authored against. See the class KDoc for the derivation.
     */
    const val REFERENCE_HEIGHT_DP: Float = 280f

    /**
     * Horizontal displacement as a fraction of the frame width.
     *
     * `+1f` is one full frame width to the right; `-1f` is one full frame
     * width to the left (i.e. fully off-screen for a push).
     */
    fun translationFractionX(pose: TransitionPose): Float =
        pose.translationXDp / REFERENCE_WIDTH_DP

    /**
     * Vertical displacement as a fraction of the frame height, in the
     * screen convention where positive is **down**.
     */
    fun translationFractionY(pose: TransitionPose): Float =
        pose.translationYDp / REFERENCE_HEIGHT_DP

    /**
     * Builds the 4×4 transformation matrix Media3's
     * `GlMatrixTransformation` expects, in **column-major** order (the
     * layout OpenGL and `android.opengl.Matrix` use, so
     * `matrix[col * 4 + row]`).
     *
     * The composition is
     *
     * ```
     * M = A⁻¹ · T · Rz · Rx · S · A
     * ```
     *
     * where `A = diag(aspect, 1, 1)` maps the rectangular frame into NDC's
     * square (-1..1 on both axes) so that rotation does not shear, `S` is
     * the pose scale, `Rz`/`Rx` are the rotations and `T` is the
     * translation. This mirrors what Media3's own
     * `ScaleAndRotateTransformation.configure` does to keep a rotated
     * rectangle from being squashed.
     *
     * @param aspectRatio the input frame's `width / height`. Non-finite or
     *   non-positive values are treated as `1f` so a bad `configure` cannot
     *   produce a NaN matrix (which would silently blank the whole export).
     */
    fun matrix(pose: TransitionPose, aspectRatio: Float): FloatArray {
        val a = if (aspectRatio.isFinite() && aspectRatio > 0f) aspectRatio else 1f

        // Pose rotations, converted from the screen convention (Compose:
        // positive = clockwise / top-tilts-away) to NDC (positive = CCW in
        // a Y-up space). See the class KDoc.
        val thetaZ = Math.toRadians((-pose.rotationZDeg).toDouble())
        val thetaX = Math.toRadians((-pose.rotationXDeg).toDouble())
        val c1 = cos(thetaZ).toFloat()
        val s1 = sin(thetaZ).toFloat()
        val c2 = cos(thetaX).toFloat()
        val s2 = sin(thetaX).toFloat()

        val sx = pose.scaleX
        val sy = pose.scaleY

        // NDC spans -1..1 (two units) across the frame, so one full frame
        // width of travel is 2 NDC units.
        val ndcX = translationFractionX(pose) * 2f
        val ndcY = -translationFractionY(pose) * 2f

        val m = FloatArray(16)
        // Column 0.
        m[0] = c1 * sx
        m[1] = s1 * sx * a
        m[2] = 0f
        m[3] = 0f
        // Column 1.
        m[4] = -s1 * c2 * sy / a
        m[5] = c1 * c2 * sy
        m[6] = s2 * sy
        m[7] = 0f
        // Column 2.
        m[8] = -s1 * s2 / a
        m[9] = c1 * s2
        m[10] = c2
        m[11] = 0f
        // Column 3 (translation).
        m[12] = ndcX
        m[13] = ndcY
        m[14] = 0f
        m[15] = 1f
        return m
    }

    /**
     * Alpha the pose wants applied to the whole frame (`< 1` for the fade
     * family). Exposed so the export can turn it into a wash instead — see
     * [fadeAlpha].
     */
    fun frameAlpha(pose: TransitionPose): Float = pose.alpha.coerceIn(0f, 1f)

    /**
     * Opacity of the **fade** wash — how far the clip has been dimmed toward
     * whatever sits behind it.
     *
     * The preview composites the clip over the canvas at
     * `graphicsLayer { alpha = frameAlpha }`, so `alpha = 0.3f` is the same
     * picture as painting 70% of the canvas colour on top of it. An MP4 has
     * no alpha channel, so an `AlphaScale` on the base layer would be
     * discarded by the encoder — a full-frame overlay is the only route that
     * survives, and its opacity is this value.
     *
     * The wash's **colour** is the canvas colour, which is why this is a
     * separate pass from [flashBlackAlpha]: the two are painted one after
     * the other, in the same order the preview paints them, so their
     * opacities *compose* rather than compete. See
     * [CanvasLetterbox.fadeColorArgb] for the colour and
     * [com.aicreatorstudio.app.export.ClipMotionEffects] for the ordering.
     */
    fun fadeAlpha(pose: TransitionPose): Float = 1f - frameAlpha(pose)

    /**
     * Opacity of an explicit **black flash** — the black spike inside
     * `GLITCH` / `PIXEL_GLITCH` and the whole of `FLASH_BLACK`.
     *
     * Deliberately *not* merged with [fadeAlpha]. The preview draws the two
     * as separate passes (the clip's own alpha, then a black box on top), so
     * they compose. Folding them with a `max` — which is what this used to
     * do, as `blackoutAlpha` — under-darkens any transition that sets both,
     * and `GLITCH` is exactly that case: `alpha = 1 - 0.08a` *and*
     * `flashBlack = 0.12a`.
     */
    fun flashBlackAlpha(pose: TransitionPose): Float =
        pose.flashBlack.coerceIn(0f, 1f)

    /**
     * Opacity of a full-frame **white** overlay for the pose's white flash.
     */
    fun whiteoutAlpha(pose: TransitionPose): Float =
        pose.flashWhite.coerceIn(0f, 1f)

    /**
     * Gaussian sigma (in pixels) for the pose's blur, or `0f` when the
     * transition has no blur component.
     *
     * The pose's `blurDp` is authored against the same 430 × 280 reference
     * canvas, so it is scaled to the real output height here. The result is
     * clamped because a zero sigma is degenerate in a Gaussian kernel.
     */
    fun blurSigmaPx(pose: TransitionPose, outputHeightPx: Int): Float {
        if (pose.blurDp <= 0f || outputHeightPx <= 0) return 0f
        val scale = outputHeightPx.toFloat() / REFERENCE_HEIGHT_DP
        return (pose.blurDp * scale).coerceAtLeast(0.1f)
    }

    /**
     * Number of pose samples used by [needsWhiteOverlay] /
     * [needsFadeOverlay] / [needsBlackFlashOverlay].
     */
    const val OVERLAY_PROBE_SAMPLES: Int = 16

    /** Pose alpha below which the frame is visibly darkened. */
    private const val ALPHA_EPSILON: Float = 0.01f

    /** Blur, in dp, below which the effect is not worth a GL pass. */
    private const val BLUR_EPSILON_DP: Float = 0.01f

    /**
     * Whether [config] ever blurs the frame. See [needsWhiteOverlay] for
     * why this is probed rather than looked up from a list of type names.
     */
    fun needsBlur(
        config: TransitionConfig,
        dir: TransitionDir,
        samples: Int = OVERLAY_PROBE_SAMPLES
    ): Boolean = probe(config, dir, samples) { pose -> pose.blurDp > BLUR_EPSILON_DP }

    /**
     * Whether [config] ever paints a visible **white** wash over the frame.
     *
     * Probed by sampling the pose across the transition window rather than
     * looked up from a hard-coded set of transition names. That way a newly
     * added flashing transition is covered the moment it is added to
     * `TransitionType`, with no second place to update and no way for the
     * two to drift apart.
     */
    fun needsWhiteOverlay(
        config: TransitionConfig,
        dir: TransitionDir,
        samples: Int = OVERLAY_PROBE_SAMPLES
    ): Boolean = probe(config, dir, samples) { pose -> pose.flashWhite > ALPHA_EPSILON }

    /**
     * Whether [config] ever dims the frame toward the canvas — i.e. whether
     * its pose reaches an alpha below `1f`.
     *
     * The export has no alpha channel to write, so a reduced alpha has to
     * become a wash of the canvas colour — see [fadeAlpha].
     */
    fun needsFadeOverlay(
        config: TransitionConfig,
        dir: TransitionDir,
        samples: Int = OVERLAY_PROBE_SAMPLES
    ): Boolean = probe(config, dir, samples) { pose -> pose.alpha < 1f - ALPHA_EPSILON }

    /**
     * Whether [config] ever paints an explicit **black flash** over the
     * frame.
     *
     * Split from [needsFadeOverlay] rather than merged into one "does it get
     * darker" probe, because the two washes differ in **colour** and the
     * preview draws them as two passes. Merging them — which is what the
     * export used to do — both hid the fade's colour (it washed toward black
     * even on a coloured canvas) and collapsed two composed opacities into
     * their maximum.
     */
    fun needsBlackFlashOverlay(
        config: TransitionConfig,
        dir: TransitionDir,
        samples: Int = OVERLAY_PROBE_SAMPLES
    ): Boolean = probe(config, dir, samples) { pose -> pose.flashBlack > ALPHA_EPSILON }

    private inline fun probe(
        config: TransitionConfig,
        dir: TransitionDir,
        samples: Int,
        predicate: (TransitionPose) -> Boolean
    ): Boolean {
        if (samples <= 1) return false
        for (i in 0 until samples) {
            val progress = i.toFloat() / (samples - 1).toFloat()
            if (predicate(TransitionEngine.evaluate(config, dir, progress))) return true
        }
        return false
    }
}
