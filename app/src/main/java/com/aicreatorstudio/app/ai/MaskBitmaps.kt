package com.aicreatorstudio.app.ai

import android.graphics.Bitmap
import kotlin.math.roundToInt

/**
 * Bitmap bridge for [MaskPostProcess] — the pure math lives in the
 * object (JVM-testable); this file only reads/writes the alpha channel.
 *
 * The Background Remove post-process (expansion / feather / reverse)
 * is applied at **composition time**, not at sidecar-generation time.
 * This is deliberately different from the old generation-time
 * `applyFeatherAlpha`: CapCut stores the raw mask and re-renders its
 * edge at render time (`docs/CAPCUT_RE_BACKGROUND_REMOVAL_2026-
 * 10-25.md` §8/§9), which is what makes its expansion/feather/reverse
 * sliders live. Once the same knobs are applied at export/preview
 * time here, the user gets the same property: moving a slider never
 * requires re-running the model.
 */
object MaskBitmaps {

    /**
     * Applies the shared mask post-process chain ([expansion] −1..+1,
     * [feather] 0..1, [reverse]) to the **alpha channel** of [mask],
     * in place. The RGB channels are untouched. Returns [mask] for
     * fluency.
     *
     * @param shortSideOverride the mask's short side used for the
     *   normalized→pixel mapping; pass `null` to use [mask]'s own
     *   short side. (Callers that post-process the native sidecar
     *   resolution but composite at the frame resolution rely on NOT
     *   overriding: the effect scales with the mask, exactly like the
     *   reference shader's UV-space math.)
     */
    fun applyPostProcess(
        mask: Bitmap,
        expansion: Float,
        feather: Float,
        reverse: Boolean,
        shortSideOverride: Int? = null
    ): Bitmap {
        if (MaskPostProcess.isNoOp(expansion, feather, reverse)) return mask
        val w = mask.width
        val h = mask.height
        val shortSide = shortSideOverride ?: minOf(w, h)
        if (w <= 0 || h <= 0) return mask
        val pixels = IntArray(w * h)
        mask.getPixels(pixels, 0, w, 0, 0, w, h)
        val alpha = FloatArray(w * h)
        for (i in pixels.indices) alpha[i] = ((pixels[i] ushr 24) and 0xFF) / 255f
        MaskPostProcess.apply(alpha, w, h, expansion, feather, reverse)
        for (i in pixels.indices) {
            val a = (alpha[i] * 255f).roundToInt().coerceIn(0, 255)
            pixels[i] = (a shl 24) or (pixels[i] and 0x00FFFFFF)
        }
        mask.setPixels(pixels, 0, w, 0, 0, w, h)
        return mask
    }
}
