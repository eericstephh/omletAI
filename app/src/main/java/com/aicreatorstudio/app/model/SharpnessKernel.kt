package com.aicreatorstudio.app.model

import kotlin.math.abs

/**
 * Pure math for the Color Grading panel's **Detail > Sharpness** slider
 * ([ColorGrading.sharpness]).
 *
 * ## Why this file exists
 *
 * `sharpness` was write-only until 2026-09-16: the panel wrote it, the DB
 * persisted it, and no renderer ever read it back — so dragging the slider
 * changed nothing, on screen or in the exported file. It cannot ride the
 * 4x5 colour matrix the rest of the grade uses
 * ([com.aicreatorstudio.app.ui.screens.editor.ColorGradingMath]) because it
 * needs *neighbourhood* reads, not a per-pixel transform.
 *
 * It is rendered instead as a real separable convolution, in the preview and
 * in the export alike, from this single definition — the same "one math, two
 * renderers" rule the rest of the grade follows.
 *
 * ## Not to be confused with
 *
 * [com.aicreatorstudio.app.enhance.SharpeningMath] — that object approximates
 * the AI upscaler's CAS pass as a colour matrix and is driven by
 * [UpscalerSettings], not by the grade panel. The two are unrelated controls
 * that happen to share a word.
 *
 * ## The kernel
 *
 * A 1-D unsharp mask over three source texels:
 *
 *     h = [ -k, 1 + 2k, -k ]        (k >= 0)
 *
 * Its weights sum to `1`, so the convolution preserves mean luminance: flat
 * regions come back unchanged and only edges move. Media3's
 * `SeparableConvolution` applies [value] horizontally and then vertically,
 * which yields the outer product `h x h`:
 *
 *     [  k^2        -k(1+2k)    k^2      ]
 *     [ -k(1+2k)    (1+2k)^2   -k(1+2k)  ]
 *     [  k^2        -k(1+2k)    k^2      ]
 *
 * That is the classic 3x3 sharpen with its corner terms filled in. The
 * cross-shaped variant `[[0,-k,0],[-k,1+4k,-k],[0,-k,0]]` has matrix rank 2
 * and is therefore **not** separable — it cannot be expressed through this
 * API at all. The difference is a small positive weight at the four corners,
 * which reads as a milder halo, not a different kind of operation.
 *
 * The support is one texel, so the halo radius is one pixel at whatever
 * resolution the convolution runs at. A wider radius would need a wider
 * domain and a correspondingly wider tap set.
 *
 * ## Why [value] is a step function
 *
 * `SeparableConvolution` does not call this function once per tap. It
 * **rasterises** it into a 1-D lookup texture — 5 samples per texel
 * (`RASTER_SAMPLES_PER_TEXEL`) — and the fragment shader samples that texture
 * with `GL_LINEAR`, i.e. it *linearly interpolates between the two raster
 * samples that bracket the lookup position*.
 *
 * With a domain of `[-1.5, 1.5]` the raster is `ceil(width * 5 + 2 * 5) = 25`
 * texels whose samples land on `-1.5 + i * 0.2`. The shader's lookups for
 * source-texel offsets `-1, 0, +1, +2` land exactly **half way between two
 * adjacent raster samples**:
 *
 *     offset  -1  ->  samples at -1.1 and -0.9
 *     offset   0  ->  samples at -0.1 and +0.1
 *     offset  +1  ->  samples at +0.9 and +1.1
 *     offset  +2  ->  samples at +1.9 and +2.1   (outside the domain)
 *
 * Because each lookup is the *average of a pair*, the kernel comes out exact
 * only if [value] returns the same number for both members of every pair.
 * That is precisely what a step function with breakpoints at `0.5` and `1.5`
 * does, and it is why those breakpoints sit strictly *between* the sampled
 * positions rather than on them. A continuous kernel — linear, Gaussian, or
 * otherwise — would be sampled off-centre and come out attenuated.
 *
 * [SAMPLE_POSITIONS] pins those derived positions so a test can assert the
 * pairing invariant directly instead of trusting this comment.
 *
 * ## Cost
 *
 * The kernel is constant for a whole clip, so Media3 builds the lookup
 * texture once and reuses it: `SeparableConvolutionShaderProgram` only
 * re-uploads when the returned `ConvolutionFunction1D` stops comparing equal.
 * Two extra GL passes per frame, no per-frame allocation.
 */
object SharpnessKernel {

    /** The largest 1-D weight, used at `sharpness == 1f`. */
    const val MAX_WEIGHT = 0.5f

    /** Convolution support, in source texels. Symmetric, so the width is 3. */
    const val DOMAIN_START = -1.5f
    const val DOMAIN_END = 1.5f
    const val DOMAIN_WIDTH = DOMAIN_END - DOMAIN_START

    /** Slider values at or below this are treated as "off". */
    const val EPSILON = 1e-4f

    /**
     * The raster positions `SeparableConvolution` actually reads, derived
     * from `DOMAIN_START + i * (1 / RASTER_SAMPLES_PER_TEXEL)`.
     *
     * Grouped by the tap each pair implements. The fourth pair is the
     * throwaway tap the shader samples one texel beyond the support; both of
     * its values must be `0` or it would add an unearned weight (and the
     * shader's normalisation would divide by the wrong total).
     */
    val SAMPLE_POSITIONS: List<Pair<Float, Float>> = listOf(
        -1.1f to -0.9f,   // tap -1
        -0.1f to 0.1f,    // tap  0
        0.9f to 1.1f,     // tap +1
        1.9f to 2.1f      // one texel beyond the support; must contribute 0
    )

    /** True when [strength] should produce an effect at all. */
    fun isActive(strength: Float): Boolean =
        strength.isFinite() && strength > EPSILON

    /**
     * Slider position (0..1) to kernel weight. Non-finite and negative inputs
     * collapse to `0` rather than propagating into the lookup table.
     */
    fun weight(strength: Float): Float {
        if (!strength.isFinite()) return 0f
        return strength.coerceIn(0f, 1f) * MAX_WEIGHT
    }

    /** The three non-zero tap weights, in source-texel order `[-1, 0, +1]`. */
    fun taps(weight: Float): FloatArray =
        floatArrayOf(-weight, 1f + 2f * weight, -weight)

    /**
     * Sum of [taps]. Always `1` (to within float rounding), i.e. mean
     * luminance is preserved and a flat region survives untouched.
     */
    fun tapSum(weight: Float): Float = taps(weight).sum()

    /**
     * The 1-D kernel, as a function of position in source texels. This is the
     * definition Media3 rasterises; see the class KDoc for why it is a step
     * function and not a continuous curve.
     *
     * At `weight == 0` this degenerates to a delta kernel, so the pass is an
     * exact no-op rather than an approximation of one.
     */
    fun value(samplePosition: Float, weight: Float): Float {
        if (!samplePosition.isFinite()) return 0f
        val d = abs(samplePosition)
        return when {
            d < 0.5f -> 1f + 2f * weight
            d < 1.5f -> -weight
            else -> 0f
        }
    }
}
