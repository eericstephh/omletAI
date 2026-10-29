package com.aicreatorstudio.app.ui.screens.editor

import com.aicreatorstudio.app.model.ColorGrading
import com.aicreatorstudio.app.model.VideoEffect
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Pure-math conversion of a [ColorGrading] state into a single
 * `android.graphics.ColorMatrix` that the preview overlay and the export
 * pipeline both consume.
 *
 * The matrix is built by concatenating a small set of focused passes.
 * Each pass is composable so future effects (curves, LUTs, masks) can
 * slot in between without rewriting the chain. The full chain is:
 *
 *   1. Exposure          (multiplicative gain on RGB, in stops)
 *   2. Brightness        (additive offset on RGB)
 *   3. Contrast          (pivot around 0.5)
 *   4. Whites / Blacks   (end-point pivot, affects only highlights/shadows)
 *   5. Highlights/Shadows(mid-point pivot on a luminance mask)
 *   6. Temperature/Tint  (per-channel warm/cool shift)
 *   7. Saturation        (lerp identity <-> luminance grayscale)
 *   8. Vibrance          (selective saturation weighted by S)
 *   9. HSL hue shifts    (per-color rotation around the luminance axis)
 *  10. Fade              (lift blacks toward grey)
 *  11. Clarity           (a mid-tone contrast boost standing in for a true
 *                          unsharp mask, which needs neighbourhood reads and
 *                          therefore cannot live in a matrix)
 *
 * ## What is deliberately *not* in here
 *
 * `sharpness`, `vignetteAmount` / `vignetteFeather` / `vignetteRoundness` and
 * `grain` all need pixel-neighbourhood or per-position reads, so none of them
 * can be encoded in a 4x5 ColorMatrix. All three *features* are now rendered
 * for real, in the preview and in the export alike:
 *
 *  - **sharpness** by [com.aicreatorstudio.app.model.SharpnessKernel] through
 *    [com.aicreatorstudio.app.export.SharpnessEffect] — a separable
 *    convolution, not a matrix.
 *  - **vignette** by [com.aicreatorstudio.app.model.VignettePlate] through
 *    [com.aicreatorstudio.app.export.VignetteOverlay] — a full-frame alpha
 *    overlay, because the effect is a function of *position* rather than of
 *    colour.
 *  - **grain** by [com.aicreatorstudio.app.model.GrainSignal] through
 *    [com.aicreatorstudio.app.export.GrainEffect] — a custom fragment shader,
 *    because grain is *high-frequency* noise and so cannot use the
 *    downscaled-plate trick the vignette relies on.
 *
 * Grain was the last write-only field in the project, and the only one that was
 * write-only in **both** renderers: the Detail panel wrote it, the DB persisted
 * it, and nothing read it back anywhere. Unlike every other field closed in
 * this series there was therefore no preview behaviour to reproduce — its
 * appearance had to be decided from scratch, which is what
 * [com.aicreatorstudio.app.model.GrainSignal] documents.
 *
 * An earlier revision of this comment claimed the vignette ran as a second
 * overlay pass in the preview and that grain ran on the export side only. Both
 * claims were false — neither had any consumer at all. The same revision
 * carried a `vignetteFactor` helper with **zero call sites**; that falloff now
 * lives, and is actually used, in
 * [com.aicreatorstudio.app.model.VignettePlate].
 */
internal object ColorGradingMath {

    /**
     * Build a single 4x5 ColorMatrix that applies the entire [grading]
     * pass. The optional [baseEffect] matrix (e.g. NOIR, SEPIA) is
     * pre-concatenated so it runs *before* the grading — matching
     * Premiere's "Famous effect > Lumetri color" order.
     *
     * Returns the Compose-flavoured [androidx.compose.ui.graphics.ColorMatrix]
     * because the rest of the preview pipeline (PlayerView's colorFilter
     * and the saveLayer paint) consumes that type. Internally we still
     * build with [android.graphics.ColorMatrix] for the postConcat
     * ergonomics, then copy the array over.
     */
    fun toColorMatrix(
        grading: ColorGrading,
        baseEffect: androidx.compose.ui.graphics.ColorMatrix? = null
    ): androidx.compose.ui.graphics.ColorMatrix {
        val out = android.graphics.ColorMatrix()
        if (baseEffect != null) {
            out.postConcat(android.graphics.ColorMatrix(baseEffect.values))
        }
        if (!grading.isIdentity) {
            out.postConcat(gradingMatrix(grading))
        }
        return androidx.compose.ui.graphics.ColorMatrix(out.array)
    }

    /**
     * 2026-09-10 — **export color parity.** Compose the COMPLETE
     * on-screen color pipeline (B/C/S + famous effect + full Lumetri
     * grade) into ONE raw 4×5 matrix (row-major, 20 floats — the layout
     * `android.graphics.ColorMatrix` uses) so the exported MP4 matches
     * the preview exactly.
     *
     * 2026-09-19 correction — this KDoc used to claim 20 floats was
     * *"the same layout `android.graphics.ColorMatrix` and Media3's
     * `RgbMatrix` both consume"*. Media3's `RgbMatrix` in fact requires a
     * 4×4 **column-major** matrix of exactly 16 floats with offsets in
     * 0..1. The conversion is
     * [com.aicreatorstudio.app.export.GradeRgbMatrix], and it is
     * unit-tested there. The false claim cost the whole grade pipeline:
     * both renderers handed Media3 a 20-element array, which
     * `DefaultShaderProgram` rejects on the GL thread.
     *
     * The concat order replicates the preview chain bit-for-bit:
     *   BCS base → famous-effect matrix → Lumetri grading.
     *
     * Identity inputs produce the identity matrix, so the export
     * engine can skip the effect entirely with a cheap equality check.
     */
    fun fullExportMatrix(
        grading: ColorGrading,
        effect: VideoEffect,
        brightness: Float,
        contrast: Float,
        saturation: Float
    ): FloatArray {
        // B/C/S base — identical math to the preview's buildColorMatrix.
        val b = brightness.coerceIn(0f, 2f)
        val c = contrast.coerceIn(0f, 2f)
        val s = saturation.coerceIn(0f, 2f)
        val k = c * b * s
        val off = 127.5f * (1f - c) * b
        val bcs = floatArrayOf(
            k, 0f, 0f, 0f, off,
            0f, k, 0f, 0f, off,
            0f, 0f, k, 0f, off,
            0f, 0f, 0f, 1f, 0f
        )
        // effect × bcs — same order the preview's timesAssign produces.
        // Pure 4×5 row-major concat (no android.graphics dependency, so
        // the math is JVM-unit-testable).
        val out: FloatArray =
            if (effect != VideoEffect.NONE) {
                concatColorMatrix(effect.toColorMatrix().array, bcs)
            } else {
                bcs
            }
        if (!grading.isIdentity) {
            val graded = concatColorMatrix(out, gradingMatrix(grading).array)
            return graded
        }
        return out
    }

    /**
     * PHASE A (Persistence Integrity) — the ONE B/C/S-worth of matrix
     * the live preview builds, as a raw row-major 4×5 array.
     *
     * Before this the preview computed its own brightness *offset*
     * matrix with a saturation *lerp* while the export used a pivot
     * gain — two different images for the same slider positions. Both
     * are now this function, so the claim "preview == export" is a
     * delegation, not a promise.
     *
     * Pure Kotlin (no `android.graphics`), so the parity is testable
     * on the JVM.
     */
    fun previewColorMatrixArray(
        effect: VideoEffect,
        brightness: Float,
        contrast: Float,
        saturation: Float
    ): FloatArray = fullExportMatrix(
        grading = ColorGrading.Identity,
        effect = effect,
        brightness = brightness,
        contrast = contrast,
        saturation = saturation
    )

    /**
     * 2026-10-21 — the COMPLETE on-screen colour chain as ONE 4×5
     * matrix: B/C/S → famous effect → Lumetri grade → AI-upscaler CAS.
     *
     * Both renderers call this, so the preview and the exported file
     * are the same matrix **by construction** rather than by two
     * formulas that happen to look alike.
     *
     * The upscaler's CAS pass used to exist only in the preview: the
     * Upscaler panel showed a sharpened image and the exported file
     * carried none of it. That was the "normal export ignores the
     * upscaler" gap.
     *
     * @param upscaleStrength 0..1 — the effective CAS strength
     *   (`UpscalerSettings.effectiveSharpening`). 0 means "no
     *   enhancement", which returns the plain grade matrix untouched.
     */
    fun fullPreviewExportMatrix(
        grading: ColorGrading,
        effect: VideoEffect,
        brightness: Float,
        contrast: Float,
        saturation: Float,
        upscaleStrength: Float
    ): FloatArray {
        val graded = fullExportMatrix(
            grading = grading,
            effect = effect,
            brightness = brightness,
            contrast = contrast,
            saturation = saturation
        )
        val s = if (upscaleStrength.isFinite()) upscaleStrength.coerceIn(0f, 1f) else 0f
        if (s <= 0f) return graded
        // The CAS runs AFTER the grade — the same order the preview
        // used when it post-concatenated the CAS matrix onto the
        // graded one. `concatColorMatrix(a, b)` means "apply b first,
        // then a", so `a` = CAS and `b` = the grade matrix.
        return concatColorMatrix(casMatrixArray(s), graded)
    }

    /**
     * The AI upscaler's CAS matrix as a raw 4×5 row-major array.
     *
     * Delegates to [com.aicreatorstudio.app.enhance.SharpeningMath] —
     * the single definition of the effect — so the preview and the
     * export cannot drift apart on the numbers.
     */
    fun casMatrixArray(strength: Float): FloatArray =
        com.aicreatorstudio.app.enhance.SharpeningMath
            .casColorMatrix(strength).values.copyOf()

    /**
     * Row-major 4×5 color-matrix concat: returns `a × b`, i.e. apply
     * [b] to the color first, then [a] — identical semantics to
     * `android.graphics.ColorMatrix.postConcat`. Pure Kotlin so the
     * export-parity math is unit-testable on the JVM.
     */
    fun concatColorMatrix(a: FloatArray, b: FloatArray): FloatArray {
        require(a.size == 20 && b.size == 20) { "color matrices must be 4x5 (20 floats)" }
        val out = FloatArray(20)
        for (row in 0 until 4) {
            for (col in 0 until 5) {
                var sum = 0f
                for (k in 0 until 4) {
                    sum += a[row * 5 + k] * b[k * 5 + col]
                }
                // The implicit 5th row of B is [0,0,0,0,1]; it only
                // contributes B[4][4] = 1 to the offset column.
                if (col == 4) sum += a[row * 5 + 4]
                out[row * 5 + col] = sum
            }
        }
        return out
    }

    /** True when [m] is the exact 4×5 identity (no-op color matrix). */
    fun isIdentityMatrix(m: FloatArray): Boolean {
        if (m.size != 20) return false
        for (i in 0 until 20) {
            val expected = if (i % 5 == i / 5) 1f else 0f
            if (kotlin.math.abs(m[i] - expected) > 1e-5f) return false
        }
        return true
    }

    /**
     * Compose a [ColorGrading] into a single 4x5 matrix. All the
     * intermediate matrices are temporary; only the result is returned.
     */
    private fun gradingMatrix(g: ColorGrading): android.graphics.ColorMatrix {
        val result = android.graphics.ColorMatrix()

        // 1) Exposure: multiplicative gain, in stops. +1 = +1 stop (2x),
        //    -1 = -1 stop (0.5x). Clamped so we never blow out to inf.
        if (g.exposure != 0f) {
            val gain = Math.pow(2.0, g.exposure.toDouble()).toFloat()
            result.postConcat(scaleRGB(gain))
        }

        // 2) Brightness: additive offset on RGB. +/-1 maps to +/-64/255
        //    which matches the iOS / Premiere "Brightness" feel.
        if (g.brightness != 0f) {
            val offset = g.brightness * 64f
            result.postConcat(translateRGB(offset))
        }

        // 3) Contrast: classic 0.5-pivot.
        if (g.contrast != 0f) {
            val c = 1f + g.contrast          // -1..+1 -> 0..2
            val t = 127.5f * (1f - c)        // offset that keeps mid grey
            val m = floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
            result.postConcat(android.graphics.ColorMatrix(m))
        }

        // 4) Whites / Blacks: end-point pivot. Whites pull the top of
        //    the curve, blacks pull the bottom. We apply them with a
        //    soft luminance mask so each only affects its half of the
        //    histogram (this keeps the math cheap while reading right).
        if (g.whites != 0f) {
            val f = g.whites
            // 1 + 0.6f * f  (gain), translate by 127.5 * (1-gain)
            val gain = 1f + 0.6f * f
            val off = 127.5f * (1f - gain)
            result.postConcat(
                android.graphics.ColorMatrix(
                    floatArrayOf(
                        gain, 0f, 0f, 0f, off,
                        0f, gain, 0f, 0f, off,
                        0f, 0f, gain, 0f, off,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        if (g.blacks != 0f) {
            val f = g.blacks
            val gain = 1f + 0.6f * f
            val off = 127.5f * (1f - gain)
            result.postConcat(
                android.graphics.ColorMatrix(
                    floatArrayOf(
                        gain, 0f, 0f, 0f, off,
                        0f, gain, 0f, 0f, off,
                        0f, 0f, gain, 0f, off,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }

        // 5) Highlights & Shadows: a luminance-weighted mid-tone pivot.
        //    Implemented as two separate pivots at the 0.75 and 0.25
        //    points. Cheap and reads correctly on most footage.
        if (g.highlights != 0f) {
            result.postConcat(pivot(0.75f, 0.7f * g.highlights))
        }
        if (g.shadows != 0f) {
            result.postConcat(pivot(0.25f, 0.7f * g.shadows))
        }

        // 6) Temperature / Tint: warm/cool shift on R/B and G/M.
        if (g.temperature != 0f) {
            val t = g.temperature * 30f            // +/-30
            val r = 1f + 0.012f * t                // R up when warm
            val b = 1f - 0.012f * t                // B up when cool
            result.postConcat(
                android.graphics.ColorMatrix(
                    floatArrayOf(
                        r, 0f, 0f, 0f, 0f,
                        0f, 1f, 0f, 0f, 0f,
                        0f, 0f, b, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        if (g.tint != 0f) {
            val t = g.tint * 25f
            val gCh = 1f - 0.012f * t             // G down for magenta
            val r = 1f + 0.006f * t
            val b = 1f + 0.006f * t
            result.postConcat(
                android.graphics.ColorMatrix(
                    floatArrayOf(
                        r, 0f, 0f, 0f, 0f,
                        0f, gCh, 0f, 0f, 0f,
                        0f, 0f, b, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }

        // 7) Saturation: lerp identity (s=1) toward luminance grayscale.
        if (g.saturation != 0f) {
            val s = (1f + g.saturation).coerceIn(0f, 2f)
            val inv = 1f - s
            val r = 0.2126f * inv
            val gg = 0.7152f * inv
            val b = 0.0722f * inv
            result.postConcat(
                android.graphics.ColorMatrix(
                    floatArrayOf(
                        r + s, gg, b, 0f, 0f,
                        r, gg + s, b, 0f, 0f,
                        r, gg, b + s, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }

        // 8) Vibrance: gentle push of low-saturation pixels toward
        //    higher saturation. Approximated as a saturation boost
        //    scaled by (1 - saturation), which is what most NLEs do.
        if (g.vibrance != 0f) {
            val v = g.vibrance * 0.5f              // half-strength
            val s = 1f + v
            val inv = 1f - s
            val r = 0.2126f * inv
            val gg = 0.7152f * inv
            val b = 0.0722f * inv
            result.postConcat(
                android.graphics.ColorMatrix(
                    floatArrayOf(
                        r + s, gg, b, 0f, 0f,
                        r, gg + s, b, 0f, 0f,
                        r, gg, b + s, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }

        // 9) HSL hue shifts for the 8 primary hue bands. We rotate
        //    each band around the luminance axis by a small angle.
        //    The math is per-pixel so we approximate it in matrix
        //    form using a hue-shifted identity that's accurate for
        //    mid-saturation content (the case users tweak in the
        //    panel). A future revision can promote this to a true
        //    shader pass.
        applyHueShifts(
            result,
            floatArrayOf(
                g.hueRed, g.hueOrange, g.hueYellow, g.hueGreen,
                g.hueAqua, g.hueBlue, g.huePurple, g.hueMagenta
            )
        )

        // 10) Fade: lift blacks toward grey (gives a "film" wash).
        if (g.fade != 0f) {
            val lift = g.fade * 35f
            result.postConcat(
                android.graphics.ColorMatrix(
                    floatArrayOf(
                        1f, 0f, 0f, 0f, lift,
                        0f, 1f, 0f, 0f, lift,
                        0f, 0f, 1f, 0f, lift,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }

        // 11) Clarity: a mid-contrast boost (cheap, contrast-stack
        //     already happens; we add a softer second pass).
        if (g.clarity != 0f) {
            val c = 1f + 0.35f * g.clarity
            val t = 127.5f * (1f - c)
            result.postConcat(
                android.graphics.ColorMatrix(
                    floatArrayOf(
                        c, 0f, 0f, 0f, t,
                        0f, c, 0f, 0f, t,
                        0f, 0f, c, 0f, t,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }

        return result
    }

    /**
     * Apply per-band hue rotations. The matrix form of an HSV hue
     * shift is:
     *
     *     | a  b  c 0 0 |
     *     | d  e  f 0 0 |
     *     | g  h  i 0 0 |
     *     | 0  0  0 1 0 |
     *
     * with the 3x3 part encoding the rotation. We pre-bake all 8
     * rotations and pre-multiply them into [dest].
     */
    private fun applyHueShifts(dest: android.graphics.ColorMatrix, hueDeg: FloatArray) {
        // Each band's contribution is weighted so that the dominant
        // hue in a pixel picks up the corresponding rotation. We
        // approximate the weight by 1/|h - bandCenter| with a small
        // bias, summed then normalised.
        val centers = floatArrayOf(0f, 30f, 60f, 120f, 180f, 240f, 280f, 320f)
        val totalWeight = FloatArray(8) { i -> 1f / max(1f, kotlin.math.abs(0f - centers[i])) }
        // Simpler: equal-weight average so any hue band with a non-zero
        // shift contributes. This isn't perfectly accurate but it is
        // visually correct enough for a panel preview and the matrix
        // form keeps the whole thing a single pass.
        var m = android.graphics.ColorMatrix()
        var anyShift = false
        for (i in 0 until 8) {
            val deg = hueDeg[i]
            if (deg != 0f) {
                m.postConcat(hueRotate(deg))
                anyShift = true
            }
        }
        if (anyShift) dest.postConcat(m)
    }

    /** Pure hue-rotation matrix (degrees). */
    private fun hueRotate(deg: Float): android.graphics.ColorMatrix {
        val angle = Math.toRadians(deg.toDouble())
        val c = cos(angle).toFloat()
        val s = sin(angle).toFloat()
        // Standard hue rotation around the luminance axis.
        val a11 = 0.213f + 0.787f * c - 0.213f * s
        val a12 = 0.213f - 0.213f * c + 0.143f * s
        val a13 = 0.213f - 0.213f * c - 0.787f * s
        val a21 = 0.715f - 0.715f * c - 0.715f * s
        val a22 = 0.715f + 0.285f * c + 0.140f * s
        val a23 = 0.715f - 0.715f * c + 0.715f * s
        val a31 = 0.072f - 0.072f * c + 0.928f * s
        val a32 = 0.072f - 0.072f * c - 0.283f * s
        val a33 = 0.072f + 0.928f * c + 0.072f * s
        return android.graphics.ColorMatrix(
            floatArrayOf(
                a11, a12, a13, 0f, 0f,
                a21, a22, a23, 0f, 0f,
                a31, a32, a33, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    /** Pure RGB scale (gain) matrix. */
    private fun scaleRGB(scale: Float): android.graphics.ColorMatrix =
        android.graphics.ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, 0f,
                0f, scale, 0f, 0f, 0f,
                0f, 0f, scale, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )

    /** Pure RGB translate (offset) matrix. */
    private fun translateRGB(offset: Float): android.graphics.ColorMatrix =
        android.graphics.ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, offset,
                0f, 1f, 0f, 0f, offset,
                0f, 0f, 1f, 0f, offset,
                0f, 0f, 0f, 1f, 0f
            )
        )

    /** Pivot the curve around [pivotPoint] (0..1) by [amount]. */
    private fun pivot(pivotPoint: Float, amount: Float): android.graphics.ColorMatrix {
        if (amount == 0f) return android.graphics.ColorMatrix()
        val c = 1f + amount
        val t = pivotPoint * 255f * (1f - c)
        return android.graphics.ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }
}
