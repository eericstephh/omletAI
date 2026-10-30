package com.aicreatorstudio.app.ai

import kotlin.math.roundToInt

/**
 * Builds the **preview plate** for the matting composite: a bitmap of the
 * replacement background whose alpha is the *inverse* of the mask, so
 * drawing it over the untouched video leaves the subject visible and
 * covers everything the mask called background.
 *
 * ## Why the preview needs a plate at all
 *
 * Until 2026-10-30 the mask was applied at export only, so a user who ran
 * Background Remove and pressed play saw the original clip. The panel
 * said so in a hint, but the honest reading of that screen is "the
 * feature does not work". The export composites by *replacing* the frame
 * outside the mask; the preview cannot do that to a `TextureView`, but it
 * can do the equivalent thing with one extra layer drawn on top, which is
 * what this plate is.
 *
 * ## Scope, stated plainly
 *
 * Only the **solid-fill** modes are previewed — [TRANSPARENT][com.aicreatorstudio.app.model.MattingBackgroundMode.TRANSPARENT]
 * and [COLOR][com.aicreatorstudio.app.model.MattingBackgroundMode.COLOR] — because both are
 * "one colour where the mask is 0", which a single bitmap expresses
 * exactly. `BLUR`, `IMAGE` and `VIDEO` need the background's own content
 * (a blurred copy of the frame, a decoded still, a second decoder) and
 * are still export-only; the panel hint covers those.
 *
 * The functions here are pure integer/float maths so the geometry and the
 * alpha inversion are pinned by JVM tests rather than by eye.
 */
object MattingPreviewPlate {

    /**
     * Extracts the alpha channel of [argb] into 0..1 floats.
     *
     * @param out reused output buffer, or null to allocate.
     */
    fun alphaOf(argb: IntArray, out: FloatArray? = null): FloatArray {
        val dst = out?.takeIf { it.size >= argb.size } ?: FloatArray(argb.size)
        for (i in argb.indices) {
            dst[i] = ((argb[i] ushr 24) and 0xFF) / 255f
        }
        return dst
    }

    /**
     * Turns a subject mask into the plate that hides everything else.
     *
     * `alpha' = 1 - maskAlpha` so a fully-background pixel becomes an
     * opaque fill and a fully-subject pixel becomes fully transparent.
     * The RGB is constant [fillArgb], so the result is a flat colour with
     * a hole where the subject is — exactly what the export's composite
     * paints for the removed area.
     *
     * @param alpha subject alpha, 0..1, row-major `w*h`. Modified in
     *   place by the caller's post-process chain before it gets here.
     * @param out reused output buffer, or null to allocate.
     */
    fun plateOf(alpha: FloatArray, fillArgb: Int, out: IntArray? = null): IntArray {
        val rgb = fillArgb and 0x00FFFFFF
        val dst = out?.takeIf { it.size >= alpha.size } ?: IntArray(alpha.size)
        for (i in alpha.indices) {
            val inv = 1f - alpha[i]
            val a = (inv * 255f).roundToInt().coerceIn(0, 255)
            dst[i] = (a shl 24) or rgb
        }
        return dst
    }

    /**
     * True when the preview can show this mode as a flat plate. Kept here
     * rather than at the call site so the composable and its test agree
     * on which modes are covered.
     */
    fun isPreviewable(mode: com.aicreatorstudio.app.model.MattingBackgroundMode): Boolean =
        mode == com.aicreatorstudio.app.model.MattingBackgroundMode.TRANSPARENT ||
            mode == com.aicreatorstudio.app.model.MattingBackgroundMode.COLOR

    /**
     * Source-local timestamp (ms) of the frame being shown, which is what
     * the sidecar PNGs are named after.
     *
     * The preview's playhead is a **project** position
     * (`PlaylistPosition.editorMs`), while the engine wrote each mask
     * under the **source** timestamp of the frame it analysed. Getting
     * this wrong is not cosmetic: a clip that starts at 4 s would show
     * the mask from 4 s earlier in the source.
     *
     * @param projectPositionMs playhead in project time.
     * @param timelineStartMs where the clip sits on the timeline.
     * @param trimStartMs first source ms the clip shows.
     */
    fun sourcePositionMs(
        projectPositionMs: Long,
        timelineStartMs: Long,
        trimStartMs: Long
    ): Long = (projectPositionMs - timelineStartMs.coerceAtLeast(0L) + trimStartMs)
        .coerceAtLeast(0L)

    /**
     * Quantises a timestamp so the preview decodes one mask per sidecar
     * frame instead of one per recomposition. The engine samples at
     * 10 fps, so 100 ms is the native step; rounding to it means a mask
     * is decoded at most ~10 times per second.
     */
    fun quantise(ms: Long, stepMs: Long = 100L): Long {
        if (stepMs <= 0L) return ms
        return (ms / stepMs) * stepMs
    }
}
