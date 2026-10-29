package com.aicreatorstudio.app.export

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ConvolutionFunction1D
import androidx.media3.effect.SeparableConvolution
import com.aicreatorstudio.app.model.SharpnessKernel

/**
 * Media3 adapter for [SharpnessKernel] — the "Detail > Sharpness" slider of
 * the Color Grading panel, as a real separable convolution.
 *
 * `sharpness` was write-only until 2026-09-16: the panel wrote it, the DB
 * persisted it, and no renderer read it back. It cannot ride the 4x5 colour
 * matrix the rest of the grade uses (it needs neighbourhood reads), so it is
 * a genuine GL pass instead. See [SharpnessKernel] for the kernel, for why
 * the weight function is a step function, and for why the cross-shaped 3x3
 * sharpen is not reachable through this API.
 *
 * ## Two usages, one math (preview == export)
 *
 *  - **Export** ([VideoExportEngine]): a fresh instance per segment, with the
 *    clip's own strength (immutable use).
 *  - **Live preview** (editor `PlayerView` via `ExoPlayer.setVideoEffects`):
 *    ONE instance lives for the life of the screen and [strength] is written
 *    per slider tick. Changing it does **not** rebuild the GL pipeline —
 *    `SeparableConvolutionShaderProgram` re-uploads the lookup texture only
 *    when the function it is handed stops comparing equal, and this class
 *    hands back a value-equal instance for an unchanged strength. So a slider
 *    drag costs one small texture upload, not a pipeline rebuild. This is the
 *    same trick [GradeRgbMatrix] uses for the colour matrix.
 *
 * ## Frame size
 *
 * The base `SeparableConvolution()` constructor sets `scaleWidth` and
 * `scaleHeight` to `1`, so the pass is frame-size-preserving. That matters:
 * Media3 writes a single video track per composition and the muxer rejects a
 * mid-stream dimension change, so a size-changing effect here would break
 * every multi-clip export.
 *
 * ## Border handling
 *
 * The shader clamps its support window to the frame (`max(0, ...)`) and
 * divides by the weight it actually accumulated, so the renormalisation keeps
 * edge pixels correct even though fewer taps are in range.
 */
@UnstableApi
class SharpnessEffect(
    @Volatile var strength: Float = 0f
) : SeparableConvolution() {

    /**
     * The function handed out last. Held so an unchanged strength returns the
     * *same instance* and Media3's equality check skips the LUT rebuild
     * entirely, instead of allocating one object per frame.
     *
     * Only ever touched from the GL thread, which is the only caller of
     * [getConvolution].
     */
    private var lastFunction: SharpeningFunction? = null

    override fun getConvolution(presentationTimeUs: Long): ConvolutionFunction1D {
        val weight = SharpnessKernel.weight(strength)
        val cached = lastFunction
        if (cached != null && cached.weight == weight) return cached
        return SharpeningFunction(weight).also { lastFunction = it }
    }

    companion object {
        /**
         * The effect to append, or `null` when the slider is at rest.
         *
         * Returning `null` rather than an identity effect keeps the GL chain
         * for an untouched project byte-for-byte what it was before this pass
         * existed — no extra passes, no extra textures.
         */
        fun build(strength: Float): Effect? =
            if (SharpnessKernel.isActive(strength)) SharpnessEffect(strength) else null
    }
}

/**
 * The kernel as Media3 wants it: a position -> weight function plus its
 * domain, which the shader program rasterises into a lookup texture.
 *
 * [equals] is load-bearing rather than incidental — the shader program
 * compares the function it is given against the previous one to decide
 * whether the lookup texture needs re-uploading.
 */
@UnstableApi
private class SharpeningFunction(val weight: Float) : ConvolutionFunction1D {

    override fun domainStart(): Float = SharpnessKernel.DOMAIN_START

    override fun domainEnd(): Float = SharpnessKernel.DOMAIN_END

    override fun value(samplePosition: Float): Float =
        SharpnessKernel.value(samplePosition, weight)

    override fun equals(other: Any?): Boolean =
        other is SharpeningFunction && other.weight == weight

    override fun hashCode(): Int = weight.hashCode()
}
