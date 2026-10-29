package com.aicreatorstudio.app.model

/**
 * Lumetri-style color grading state.
 *
 * Every value is stored in a normalized range that maps directly onto a
 * slider position in the panel. The math in [toColorMatrix] expects:
 *  - tone & detail parameters in roughly -1f..+1f (with 0 = identity)
 *  - hue shifts in degrees converted to radians before use
 *  - vignette, grain, fade as 0f..1f strengths
 *
 * The same struct is shared by:
 *  - [com.aicreatorstudio.app.viewmodel.EditorViewModel]  (global / per-clip)
 *  - [com.aicreatorstudio.app.ui.screens.editor.ColorGradingPanel]  (UI)
 *  - [com.aicreatorstudio.app.export.RenderGraphBuilder]  (export bake)
 *  - [com.aicreatorstudio.app.ui.screens.editor.ColorScope]  (scope preview)
 *
 * The struct is `data class` so the panel and the ViewModel can rely on
 * structural equality for skipping recomposition / re-sampling the scope.
 */
data class ColorGrading(
    // ---- Basic tone (-1..+1, 0 = neutral) ----
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val exposure: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,

    // ---- Color / temperature ----
    val temperature: Float = 0f,    // -1 cool .. +1 warm
    val tint: Float = 0f,          // -1 green .. +1 magenta
    val saturation: Float = 0f,    // -1 desaturate .. +1 boost
    val vibrance: Float = 0f,      // -1 .. +1 (selective saturation)

    // ---- Detail & look ----
    val sharpness: Float = 0f,     // 0..+1 (no negative sharpening)
    val clarity: Float = 0f,       // -1 soften .. +1 clarity
    val vignetteAmount: Float = 0f,   // -1 brighten .. +1 darken edges
    val vignetteFeather: Float = 0.5f, // 0 sharp .. 1 very soft
    val vignetteRoundness: Float = 0.5f, // 0 oval .. 1 round
    val fade: Float = 0f,          // 0..+1 lifted blacks / film fade
    val grain: Float = 0f,         // 0..+1 film grain

    // ---- HSL: hue shift for primary colors (degrees, -180..+180) ----
    val hueRed: Float = 0f,
    val hueOrange: Float = 0f,
    val hueYellow: Float = 0f,
    val hueGreen: Float = 0f,
    val hueAqua: Float = 0f,
    val hueBlue: Float = 0f,
    val huePurple: Float = 0f,
    val hueMagenta: Float = 0f
) {
    /**
     * True if every *visually-affecting* parameter is at its neutral
     * position. `vignetteFeather` / `vignetteRoundness` are excluded
     * because they only have an effect when the vignette is actually
     * applied (amount > 0), so their default value of 0.5 is "neutral
     * but unrendered" rather than identity.
     */
    val isIdentity: Boolean
        get() = brightness == 0f && contrast == 0f && exposure == 0f &&
            highlights == 0f && shadows == 0f && whites == 0f && blacks == 0f &&
            temperature == 0f && tint == 0f && saturation == 0f && vibrance == 0f &&
            sharpness == 0f && clarity == 0f && vignetteAmount == 0f &&
            fade == 0f && grain == 0f &&
            hueRed == 0f && hueOrange == 0f && hueYellow == 0f && hueGreen == 0f &&
            hueAqua == 0f && hueBlue == 0f && huePurple == 0f && hueMagenta == 0f

    companion object {
        /** The neutral / "no grade" preset. */
        val Identity = ColorGrading()
    }
}

/**
 * Convenience extension for the [ColorGrading] -1..+1 -> 0..+1 mapping
 * used in a couple of UI labels.
 */
internal fun ColorGrading.toneAsPercent(value: Float): Int =
    (value.coerceIn(-1f, 1f) * 100f).toInt()
