package com.aicreatorstudio.app.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure generation of the vignette plate — the "Detail > Vignette" sliders of
 * the Color Grading panel ([ColorGrading.vignetteAmount],
 * [ColorGrading.vignetteFeather], [ColorGrading.vignetteRoundness]).
 *
 * All three were write-only until 2026-09-16: the panel wrote them, the DB
 * persisted them, and no renderer read them back. The falloff maths below is
 * lifted verbatim from the `vignetteFactor` helper that used to sit (unused,
 * zero call sites) in
 * [com.aicreatorstudio.app.ui.screens.editor.ColorGradingMath], so the
 * darkening a user finally sees is exactly the one that helper always
 * described.
 *
 * ## How the plate is applied
 *
 * A vignette is a *spatial* darkening, so it cannot be a colour matrix. It is
 * rendered as a Media3 `BitmapOverlay`: a bitmap whose alpha carries the
 * strength and whose RGB is the colour to blend toward. Media3's overlay
 * fragment shader does straight (non-premultiplied) alpha blending:
 *
 *     output.rgb = overlay.rgb * overlay.a + video.rgb * (1 - overlay.a)
 *
 * so a **black** plate darkens (`video * (1 - a)`) and a **white** plate
 * brightens (`a + video * (1 - a)`). That is how this file serves
 * `vignetteAmount`'s full `-1 brighten .. +1 darken` range with one code path:
 * the sign picks the plate colour and the magnitude picks the alpha.
 *
 * ## Why the plate is smaller than the frame
 *
 * The overlay is *not* stretched to fill the frame — Media3 scales it by
 * `overlaySize / backgroundSize`, i.e. it is drawn at its own pixel size,
 * centred. A quarter-size plate would therefore appear as a small vignette in
 * the middle of the frame. [overlayScale] compensates by asking
 * `OverlaySettings.setScale` for exactly `frameSize / plateSize`, which
 * restores full coverage.
 *
 * Downscaling is safe *for this signal specifically* because a vignette is a
 * low-frequency gradient: its shape depends only on normalised position, so
 * resolution changes nothing but smoothness, and GL's linear filtering
 * interpolates it back.
 *
 * A high-frequency signal — film **grain** — cannot be treated this way: a
 * downscaled plate comes back as soft blobs, not grain. That is why grain is
 * not a plate at all but a per-pixel fragment shader, specified by
 * [GrainSignal] and rendered by
 * [com.aicreatorstudio.app.export.GrainEffect]. See the class KDoc of
 * [com.aicreatorstudio.app.export.VignetteOverlay] for why the plate trick
 * stops at low frequencies.
 *
 * ## Under-covering is the dangerous direction
 *
 * Media3 returns `vec4(0,0,0,0)` for overlay texture coordinates outside
 * `[0,1]`, which leaves that pixel untouched. A plate that fails to cover the
 * frame therefore leaves an un-vignetted border — the artifact would be most
 * visible exactly at the corners, where the vignette is strongest. Over-covering
 * is harmless (the extra is simply never sampled), so [OVERCOVER] deliberately
 * errs on that side.
 */
object VignettePlate {

    /**
     * The plate is built at `1 / PLATE_DIVISOR` of the frame in each axis, so
     * the aspect ratio — and therefore the ellipse's shape — is preserved.
     */
    const val PLATE_DIVISOR = 4

    /**
     * Deliberate over-cover factor. `frameSize / plateSize` is a float
     * division that will not land exactly on the frame edge, and rounding a
     * plate dimension down can leave a sub-pixel gap; a 1% margin makes the
     * plate a hair larger than the frame instead, which is free.
     */
    const val OVERCOVER = 1.01f

    /** 8-bit alpha ceiling. */
    const val ALPHA_MAX = 255

    /** Below this the sliders are treated as at rest. */
    const val EPSILON = 1e-4f

    /**
     * True when the vignette should be rendered at all.
     *
     * Keyed on the amount alone: `vignetteFeather` and `vignetteRoundness`
     * only shape the falloff, so on their own they have nothing to shape —
     * which is why [ColorGrading.isIdentity] excludes them too.
     */
    fun isActive(amount: Float): Boolean =
        amount.isFinite() && abs(amount) > EPSILON

    /**
     * Plate dimensions for a frame, aspect-preserving and at least 2x2.
     * Non-positive or unknown frame dimensions collapse to 1x1, which produces
     * a fully transparent plate — a safe no-op rather than a crash.
     */
    fun plateSize(frameWidth: Int, frameHeight: Int): Pair<Int, Int> {
        if (frameWidth <= 0 || frameHeight <= 0) return 1 to 1
        return max(2, frameWidth / PLATE_DIVISOR) to max(2, frameHeight / PLATE_DIVISOR)
    }

    /**
     * The `OverlaySettings.scale` that stretches the plate back over the whole
     * frame. See the class KDoc for why this is needed and why it over-covers.
     */
    fun overlayScale(frameWidth: Int, frameHeight: Int): Pair<Float, Float> {
        val (pw, ph) = plateSize(frameWidth, frameHeight)
        return (frameWidth.toFloat() / pw * OVERCOVER) to
            (frameHeight.toFloat() / ph * OVERCOVER)
    }

    /**
     * The falloff at a normalised frame position, `0` at the centre rising to
     * `1` at full strength. Independent of resolution, so the plate can be
     * generated at any size.
     *
     * `roundness` biases the radius between the axes (0.5 is circular).
     * `feather` is the softness of the transition: it **narrows** the clear
     * core (`radius` falls from 0.80 to 0.45 as feather goes 0 to 1) while
     * **widening** the ramp (`softness` rises from 0.15 to 0.70). So more
     * feather both reaches further in and blends more gradually — the vignette
     * gets softer but larger, not simply weaker.
     *
     * Non-finite inputs fall back to the neutral 0.5 rather than poisoning the
     * whole plate with NaN.
     */
    fun falloff(nx: Float, ny: Float, feather: Float, roundness: Float): Float {
        val f = if (feather.isFinite()) feather.coerceIn(0f, 1f) else 0.5f
        val r = if (roundness.isFinite()) roundness.coerceIn(0f, 1f) else 0.5f
        // Distance from canvas centre, biased by roundness.
        val dx = (nx - 0.5f) * (1f + r)
        val dy = (ny - 0.5f) * (1f + (1f - r))
        val d = sqrt(dx * dx + dy * dy)
        val radius = 0.45f + 0.35f * (1f - f)
        val softness = 0.15f + 0.55f * f
        val t = ((d - radius) / softness).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)     // smoothstep
    }

    /** Plate alpha at a normalised position, `0..1`. */
    fun alphaAt(nx: Float, ny: Float, amount: Float, feather: Float, roundness: Float): Float {
        if (!amount.isFinite()) return 0f
        return (abs(amount).coerceIn(0f, 1f) * falloff(nx, ny, feather, roundness))
            .coerceIn(0f, 1f)
    }

    /**
     * The plate as packed ARGB pixels, row-major from the top-left — the
     * layout `android.graphics.Bitmap.createBitmap` expects.
     *
     * Kept as a plain `IntArray` so the whole vignette is JVM-unit-testable;
     * only the `Bitmap` wrapper lives on the Android side.
     */
    fun pixels(
        frameWidth: Int,
        frameHeight: Int,
        amount: Float,
        feather: Float,
        roundness: Float
    ): IntArray {
        val (pw, ph) = plateSize(frameWidth, frameHeight)
        val out = IntArray(pw * ph)
        // Negative amount brightens toward white, positive darkens toward
        // black; see the class KDoc for the blend equation this serves.
        val channel = if (amount > 0f) 0x00 else 0xFF
        val rgb = (channel shl 16) or (channel shl 8) or channel
        val magnitude = if (amount.isFinite()) abs(amount).coerceIn(0f, 1f) else 0f
        for (y in 0 until ph) {
            val ny = (y + 0.5f) / ph
            val row = y * pw
            for (x in 0 until pw) {
                val nx = (x + 0.5f) / pw
                val a = (magnitude * falloff(nx, ny, feather, roundness) * ALPHA_MAX)
                    .roundToInt()
                    .coerceIn(0, ALPHA_MAX)
                out[row + x] = (a shl 24) or rgb
            }
        }
        return out
    }
}
