package com.aicreatorstudio.app.model

/**
 * The specification for `ColorGrading.grain` — film grain, as pure functions.
 *
 * ## Why this exists
 *
 * `grain` was the **last write-only field in the project**, and the only one
 * that was write-only in *both* renderers: the Detail panel wrote it, the DB
 * persisted it, and neither the preview nor the export read it back. So unlike
 * every other field closed in this series, there was no preview behaviour to
 * match — grain had never been rendered anywhere, and the appearance had to be
 * decided rather than reproduced.
 *
 * Grain is generated per pixel in a fragment shader
 * (`assets/shaders/fragment_shader_grain_es2.glsl`), because it is
 * *high-frequency* noise and therefore cannot use the downscaled-plate trick
 * that makes the vignette cheap — at reduced resolution it becomes soft blobs.
 * See [com.aicreatorstudio.app.export.GrainEffect] for that side.
 *
 * This object is the part of the effect that **can** be unit-tested: the
 * amplitude curve, the response curve and the frame seed. The shader is a
 * transcription of it. That split is deliberate — this project has no
 * Robolectric and no GL harness, so a pure function is the only testable seam,
 * and pinning the maths here is the best available guard against a shader that
 * silently does nothing or does the wrong thing.
 *
 * ## The model
 *
 * Three decisions, all of which are visible in the result:
 *
 *  1. **Amplitude is linear in the slider**, scaled by [MAX_AMPLITUDE]. The
 *     panel shows `grain * 100`, so `0.25` on the slider is `25`.
 *  2. **The response is a mid-tone curve** ([response]): zero in the blacks and
 *     the whites, peaking at mid-grey. This is the single biggest difference
 *     between convincing grain and a fake. Flat noise across the whole range
 *     reads as television static, and it destroys clean blacks, which is where
 *     film grain is least visible in reality.
 *  3. **The noise is monochrome** — one delta applied to all three channels, as
 *     in the shader. Real grain is a density variation in a single emulsion
 *     layer, so it is achromatic; hashing each channel separately would look
 *     like chroma noise and would shift the picture's hue.
 *
 * ## Not modelled
 *
 * Real film grain's size varies with the stock (Super 8 is coarse, 35mm fine)
 * and with exposure. This is a single fixed grain size — one output pixel —
 * which is the convention every NLE uses, and the reason the slider reads as
 * "amount" rather than "size".
 */
object GrainSignal {

    /**
     * Peak luminance perturbation, in the sampled colour space, at
     * `grain == 1f`.
     *
     * The signed perturbation is half this (`noise - 0.5` spans ±0.5), so a
     * full-strength mid-tone pixel moves by ±0.125 — strong enough to be
     * unmistakable as a deliberate grade, and still short of clipping. The
     * tasteful range is around `0.2`-`0.4` on the slider.
     */
    const val MAX_AMPLITUDE = 0.25f

    /** Slider values at or below this are treated as "off". */
    const val EPSILON = 1e-4f

    /** 8-bit alpha ceiling for the preview's noise tile. */
    const val ALPHA_MAX = 255

    /** Rec. 709 luma weights — the same primaries the grade matrix works in. */
    const val LUMA_R = 0.2126f
    const val LUMA_G = 0.7152f
    const val LUMA_B = 0.0722f

    /**
     * Period the frame seed wraps on, in microseconds. One second: long enough
     * that consecutive frames get distinct seeds, short enough that the seed
     * stays in `[0, 1)` and the shader's offsets stay exactly representable even
     * in the mediump fallback.
     */
    const val SEED_PERIOD_US: Long = 1_000_000L

    /**
     * Whether [grain] is worth a GL pass. A non-finite value reads as "off" so
     * a bad grade cannot attach a broken pass.
     */
    fun isActive(grain: Float): Boolean = grain.isFinite() && grain > EPSILON

    /**
     * Slider position (`0..1`) to peak amplitude. Non-finite and negative
     * inputs collapse to `0` rather than propagating into the shader, where a
     * NaN uniform would blank the frame.
     */
    fun amplitude(grain: Float): Float {
        if (!grain.isFinite()) return 0f
        return grain.coerceIn(0f, 1f) * MAX_AMPLITUDE
    }

    /** Rec. 709 luma of a sampled colour, each channel `0..1`. */
    fun luma(r: Float, g: Float, b: Float): Float = LUMA_R * r + LUMA_G * g + LUMA_B * b

    /**
     * The mid-tone response curve: `0` at black and at white, `1` at mid-grey.
     *
     * `4 * l * (1 - l)` is the standard form — a parabola through `(0, 0)`,
     * `(0.5, 1)`, `(1, 0)`. It is deliberately **not** normalised to a mean of
     * 1: a pixel at pure black must receive exactly zero grain, so that a
     * crushed black stays crushed.
     *
     * Out-of-range and non-finite inputs are clamped rather than extrapolated,
     * because the parabola goes negative outside `[0, 1]` and a negative
     * response would invert the grain.
     */
    fun response(luma: Float): Float {
        val l = if (luma.isFinite()) luma.coerceIn(0f, 1f) else 0f
        return 4f * l * (1f - l)
    }

    /**
     * The luminance delta for one pixel, mirroring the shader.
     *
     * @param noise the shader's hash output, `0..1` — so the signed
     *   perturbation is `noise - 0.5`. A non-finite value reads as `0.5`, i.e.
     *   no perturbation, rather than as a random one.
     */
    fun delta(luma: Float, noise: Float, amplitude: Float): Float {
        if (!amplitude.isFinite() || amplitude <= 0f) return 0f
        val n = if (noise.isFinite()) noise.coerceIn(0f, 1f) else 0.5f
        return (n - 0.5f) * amplitude * response(luma)
    }

    /**
     * The per-frame noise seed, in `[0, 1)`, from the frame's presentation
     * timestamp.
     *
     * Derived from the timestamp rather than from a frame counter so that the
     * preview and the export produce the *same* noise for the same instant —
     * a counter would depend on how many frames each had drawn, and the two
     * would drift apart. A negative timestamp (which the engine should never
     * produce) reads as `0f` instead of a negative seed.
     */
    fun seedFor(presentationTimeUs: Long): Float {
        if (presentationTimeUs <= 0L) return 0f
        return (presentationTimeUs % SEED_PERIOD_US).toFloat() / SEED_PERIOD_US.toFloat()
    }

    /**
     * 2026-10-21 — a tileable grain field for the **preview**.
     *
     * The export's grain is a fragment shader: per-pixel white noise
     * scaled by the mid-tone parabola above. A preview cannot run a
     * shader, so the preview approximates it with a small noise texture
     * that is tiled over the video.
     *
     * Two properties make the approximation read as grain rather than as
     * a grey veil:
     *
     *  - each speckle is **white or black** with an **alpha** that
     *    follows the noise's magnitude, so the overlay both lightens
     *    and darkens instead of only lifting the picture;
     *  - the tile is drawn with plain alpha compositing and NO blend
     *    mode, because the preview's video is a `TextureView` inside an
     *    `AndroidView` — its pixels are not in the Compose canvas, so a
     *    Skia blend mode would have nothing to blend against and would
     *    composite as a flat, opaque sheet.
     *
     * The mid-tone response of the shader cannot be reproduced this way
     * (it needs the backdrop's luminance), so the preview's grain is
     * slightly stronger in the blacks and whites than the file's. That
     * is a documented approximation, not the export's maths.
     *
     * Pure Kotlin (no Android types), so it is unit-testable on the JVM.
     *
     * @param size the tile's edge in pixels; clamped to 2..512.
     * @param seed the noise seed — a different seed gives a different
     *   field, which is how the preview cycles a few tiles so the grain
     *   moves instead of sitting still.
     * @return packed ARGB pixels, row-major from the top-left — the
     *   layout `android.graphics.Bitmap.createBitmap` expects.
     */
    fun noiseTile(size: Int, seed: Long): IntArray {
        val n = size.coerceIn(2, 512)
        val out = IntArray(n * n)
        val random = kotlin.random.Random(seed)
        for (i in out.indices) {
            val d = random.nextFloat() - 0.5f          // -0.5 .. 0.5
            val a = (kotlin.math.abs(d) * 2f * ALPHA_MAX).toInt().coerceIn(0, ALPHA_MAX)
            val rgb = if (d >= 0f) 0x00FFFFFF else 0x00000000
            out[i] = (a shl 24) or rgb
        }
        return out
    }
}
