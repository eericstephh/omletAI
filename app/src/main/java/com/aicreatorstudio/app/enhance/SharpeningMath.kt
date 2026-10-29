package com.aicreatorstudio.app.enhance

import androidx.compose.ui.graphics.ColorMatrix
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Math primitives for the AI Video Upscaler.
 *
 * Two pieces live here:
 *
 * 1. [casColorMatrix] — a true AMD FidelityFX CAS (Contrast Adaptive
 *    Sharpening) is a 3×3 matrix that needs neighbour reads. Since the
 *    editor's live preview overlays a `ColorFilter.colorMatrix` via
 *    `saveLayer` (no per-pixel sampling), we approximate CAS as a
 *    luminance-weighted contrast steepening. At strength = 0 the
 *    matrix is identity; at strength = 1 it perceptibly sharpens
 *    mid-tones while leaving saturated colours and pure black/white
 *    alone. This is the *preview* path; the export path uses real
 *    Lanczos resampling for pixel-scale changes.
 *
 * 2. [lanczosKernel] — a 4-tap Lanczos resampling kernel used by the
 *    export engine for true 2× upscale. Pure, stateless, no Android
 *    dependencies — easy to unit-test on the JVM.
 */
internal object SharpeningMath {

    /**
     * Build a CAS-approximation [ColorMatrix] at the given strength.
     *
     * @param strength 0..1; 0 = identity (no effect), 1 = full CAS
     * @return a 4×5 Compose [ColorMatrix] safe to feed into
     *   `ColorFilter.colorMatrix(...)` and chain via `postConcat`
     */
    fun casColorMatrix(strength: Float): ColorMatrix {
        val s = strength.coerceIn(0f, 1f)
        if (s == 0f) return ColorMatrix()

        // CAS-style gain curve. Steepens mid-tone contrast while
        // leaving deep shadows and highlights alone. The matrix is a
        // standard 4×5 [R, G, B, A, 1] layout:
        //   out_R = a*R + b*G + c*B + d*A + e
        // with a, f, k being the main-diagonal scale (contrast pivot)
        // and b/c/g/h/l/m the cross-channel saturation-preserving terms.
        val contrast = 1f + 0.9f * s       // pivot > 1 = steeper slope
        val saturation = 1f + 0.35f * s    // a touch more saturation pops detail
        // Derived so that the off-diagonal entries hold
        //   sum_off = -(contrast - 1) / 3
        // which keeps the average luminance of pure-grey pixels constant
        // while moving saturated pixels outward.
        val off = (1f - contrast) / 3f
        val sat = (saturation - 1f) / 3f
        // Brightness nudge: tiny lift to compensate for the contrast
        // pivot. Without it mid-grey drifts a hair darker when
        // contrast > 1.
        val lift = 4f * s

        return ColorMatrix(
            floatArrayOf(
                contrast + sat, off + sat,        off + sat,        0f, lift,
                off + sat,        contrast + sat, off + sat,        0f, lift,
                off + sat,        off + sat,      contrast + sat,   0f, lift,
                0f,               0f,             0f,               1f, 0f
            )
        )
    }

    /**
     * Standard Lanczos resampling kernel.
     *
     *   L(x) = sinc(x) * sinc(x / a)     for |x| < a
     *   L(x) = 0                          otherwise
     *
     * `a = 3` is the common choice (Lanczos-3, 6-tap). `a = 2` (4-tap)
     * is cheaper and is what the export engine uses for real-time
     * paths; the function is parameterised so callers can pick.
     *
     * @param x sample position, typically in the range [-a, a]
     * @param a kernel radius (commonly 2 or 3)
     */
    fun lanczosKernel(x: Float, a: Float = 2f): Float {
        val ax = kotlin.math.abs(x)
        if (ax >= a) return 0f
        if (ax < 1e-6f) return 1f
        val pix: Double = PI * x.toDouble()
        val pixOverA: Double = pix / a.toDouble()
        val result: Double = (sin(pix) / pix) * (sin(pixOverA) / pixOverA)
        return result.toFloat()
    }

    /**
     * Sum of Lanczos weights for a window of [size] taps centered on
     * the origin. Used to normalise the kernel so a pass-through pixel
     * (`x = 0`) returns exactly the source value.
     */
    fun lanczosNormalization(size: Int = 4, a: Float = 2f): Float {
        var sum = 0f
        val half = size / 2
        for (i in 0 until size) {
            val x = (i - half).toFloat()
            sum += lanczosKernel(x, a)
        }
        return sum
    }

    /** Convenience: cosine of an angle in degrees, used by the vectorscope. */
    @Suppress("unused")
    fun cosDeg(degrees: Float): Float {
        val radians: Double = degrees.toDouble() * PI / 180.0
        return kotlin.math.cos(radians).toFloat()
    }

    /** Convenience: sine of an angle in degrees. */
    @Suppress("unused")
    fun sinDeg(degrees: Float): Float {
        val radians: Double = degrees.toDouble() * PI / 180.0
        return kotlin.math.sin(radians).toFloat()
    }
}
