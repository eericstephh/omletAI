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
     * [feather] 0..1, [reverse]) to the **alpha channel** of [mask].
     * The RGB channels are untouched.
     *
     * Returns [mask] itself when the chain is a no-op, otherwise a
     * **new mutable bitmap** — the input is never written to.
     *
     * ### Why the input must not be mutated (2026-10-29)
     *
     * This used to write straight back into [mask] with
     * `mask.setPixels(...)`. The only caller passes the bitmap it just
     * got from `BitmapFactory.decodeFile`, and **decoded bitmaps are
     * immutable**, so `setPixels` threw
     * `IllegalStateException: Cannot modify a immutable bitmap` the
     * moment any slider was off zero — the export died at PREPARING
     * with the useless message "Video processing failed". The no-op
     * path never touched the bitmap, which is why the defect survived
     * until someone actually moved a slider on a device.
     *
     * The contract is now the same as [MattingTemporalFilter]: pure in,
     * new buffer out. Callers own the result and must recycle it if
     * they replace their own bitmap with it.
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
        if (w <= 0 || h <= 0) return mask
        val shortSide = shortSideOverride ?: minOf(w, h)
        val pixels = IntArray(w * h)
        mask.getPixels(pixels, 0, w, 0, 0, w, h)
        val alpha = FloatArray(w * h)
        for (i in pixels.indices) alpha[i] = ((pixels[i] ushr 24) and 0xFF) / 255f
        MaskPostProcess.apply(alpha, w, h, expansion, feather, reverse, shortSide)
        for (i in pixels.indices) {
            val a = (alpha[i] * 255f).roundToInt().coerceIn(0, 255)
            pixels[i] = (a shl 24) or (pixels[i] and 0x00FFFFFF)
        }
        // `copy(..., true)` forces a MUTABLE ARGB_8888 bitmap whatever the
        // source config was, which is the whole point: the source is a
        // decoded (immutable) PNG.
        val out = mask.copy(Bitmap.Config.ARGB_8888, true)
            ?: return mask
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }
}
