package com.aicreatorstudio.app.export

import androidx.media3.effect.RgbMatrix

/**
 * 2026-09-16 — shared Media3 RGB-matrix color effect for the full
 * grade pipeline (B/C/S + famous effect + Lumetri grade + optional
 * upscaler CAS), composed by
 * [com.aicreatorstudio.app.ui.screens.editor.ColorGradingMath] into a
 * single 4×5 row-major matrix.
 *
 * Two usages, one math (preview == export):
 *
 *  - **Export** ([VideoExportEngine]): constructed fresh per segment
 *    with the segment's baked matrix (immutable use).
 *  - **Live preview** (editor `PlayerView` via
 *    `ExoPlayer.setVideoEffects`): a SINGLE instance is kept for the
 *    life of the screen and [matrix] is swapped per slider tick.
 *    Media3 queries [getMatrix] for every output frame on the GL
 *    thread, so swapping the array reference updates the shader
 *    uniforms with zero pipeline rebuild, zero playback disturbance.
 *    The [Volatile] reference swap (never in-place mutation) keeps
 *    the GL-thread read race-free.
 *
 * ## This class is a layout boundary (2026-09-19)
 *
 * It is the seam between two incompatible matrix conventions, and
 * Media3 does **not** convert for you:
 *
 *  - the app's world — `android.graphics.ColorMatrix` /
 *    `androidx.compose.ui.graphics.ColorMatrix`: 4×5 **row-major**,
 *    20 floats, the 5th column being an additive offset in 0..255
 *    units;
 *  - Media3's [RgbMatrix] — 4×4 **column-major**, exactly 16 floats,
 *    offsets in 0..1 because the shader works on normalised colour.
 *
 * Passing the raw 4×5 array to Media3 is precisely the bug this file
 * used to have, and it fails *invisibly* in the worst way:
 * `DefaultShaderProgram.updateMatrixCache` asserts
 * `newMatrix.length == 16` ("A 4x4 transformation matrix must have 16
 * elements"). A 20-element array never equals the 16-element cache, so
 * the assertion fires on the GL thread on the first frame and
 * `VideoFrameProcessingTaskExecutor.handleException` cancels the entire
 * video pipeline. The sliders moved, the matrix was computed correctly,
 * and no pixel ever changed — in the preview *and* in the export.
 *
 * [toMedia3RgbMatrix4x4] is the fix. It is pure Kotlin and unit-tested
 * (`GradeRgbMatrixTest`), because GL is the one thing a JVM unit test
 * cannot exercise.
 */
class GradeRgbMatrix(colorMatrix4x5: FloatArray = IDENTITY_4X5) : RgbMatrix {

    /**
     * The 4×4 column-major matrix Media3 actually reads, derived from
     * [matrix] by [toMedia3RgbMatrix4x4]. Read on the GL thread for
     * every frame, hence `@Volatile` — the reference is swapped, never
     * mutated in place.
     *
     * Declared before [matrix] so its initializer runs first and the
     * two can never disagree during construction.
     */
    @Volatile
    private var media3Matrix: FloatArray = toMedia3RgbMatrix4x4(colorMatrix4x5)

    /**
     * The app-wide 4×5 row-major `ColorMatrix` (20 floats). Assigning
     * it re-derives [media3Matrix], so callers keep working in the
     * layout the rest of the app uses and never have to know about
     * Media3's 4×4 requirement.
     */
    var matrix: FloatArray = colorMatrix4x5
        set(value) {
            field = value
            media3Matrix = toMedia3RgbMatrix4x4(value)
        }

    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray =
        media3Matrix

    companion object {
        val IDENTITY_4X5 = floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    }
}

/**
 * Convert a 4×5 row-major `ColorMatrix` (20 floats — the layout both
 * `android.graphics.ColorMatrix` and
 * `androidx.compose.ui.graphics.ColorMatrix` use) into the 4×4
 * **column-major** matrix Media3's [RgbMatrix] requires (16 floats).
 *
 * Media3's fragment shaders evaluate `uRgbMatrix * vec4(rgb, 1.0)` with
 * `rgb` normalised to 0..1, so indexing the result as `col * 4 + row`:
 *
 *  - `[0..3]`  — coefficients of input **R** for output R/G/B/A
 *  - `[4..7]`  — coefficients of input **G**
 *  - `[8..11]` — coefficients of input **B**
 *  - `[12..15]`— the constant term. The shader supplies a literal `1.0`
 *    as the 4th component, so the source's 5th column lands here, and
 *    it must be divided by 255: the source offsets are in 0..255 units
 *    while the shader's colours are 0..1.
 *
 * The 3×3 colour block is copied straight across (it is the same
 * numbers, just transposed into column-major); only the offset column
 * needs the rescale.
 *
 * Pure Kotlin on purpose: this is the arithmetic that was wrong, so it
 * is the part that must be covered by a JVM test.
 */
internal fun toMedia3RgbMatrix4x4(colorMatrix4x5: FloatArray): FloatArray {
    require(colorMatrix4x5.size == 20) {
        "a 4x5 ColorMatrix has 20 floats, got ${colorMatrix4x5.size}"
    }
    return floatArrayOf(
        // column 0 — input R
        colorMatrix4x5[0], colorMatrix4x5[5], colorMatrix4x5[10], colorMatrix4x5[15],
        // column 1 — input G
        colorMatrix4x5[1], colorMatrix4x5[6], colorMatrix4x5[11], colorMatrix4x5[16],
        // column 2 — input B
        colorMatrix4x5[2], colorMatrix4x5[7], colorMatrix4x5[12], colorMatrix4x5[17],
        // column 3 — the constant term, rescaled 0..255 -> 0..1
        colorMatrix4x5[4] / 255f,
        colorMatrix4x5[9] / 255f,
        colorMatrix4x5[14] / 255f,
        colorMatrix4x5[18]
    )
}
