package com.aicreatorstudio.app.model

/**
 * AI Video Upscaler / Enhancer — preset modes and per-clip settings.
 *
 * The "AI" framing here is the real-time preview approximation (a
 * contrast-adaptive ColorMatrix) plus a high-quality Lanczos resample
 * during export. A real ESRGAN TFLite model is a future extension — see
 * [com.aicreatorstudio.app.enhance.UpscalerExportEngine].
 *
 * Persistence mirrors [ColorGrading]: each clip carries its own
 * [UpscalerSettings] on [VideoClip.upscale] and a global default lives
 * on `EditorUiState.upscale`.
 */

/**
 * Which upscale / enhancement mode the user picked.
 *
 * @param displayName short label shown in the dock chip / sub-panel
 * @param description one-line tooltip / sub-label
 * @param targetScale output pixel multiplier (1.0 = no upscale; 2.0 = 2×).
 *   The export engine uses this to size the output buffer relative to
 *   the source. 1.0 means "no pixel upscale, just enhancement".
 * @param sharpeningAmount 0..1 — how aggressively the CAS ColorMatrix
 *   steepens midtone contrast at strength = 1.0. The live preview and
 *   the export path both consume this (scaled by user strength).
 */
enum class UpscalerPreset(
    val displayName: String,
    val description: String,
    val targetScale: Float,
    val sharpeningAmount: Float
) {
    /** No upscale; preview is identity, export passes through. */
    OFF(
        displayName = "Off",
        description = "No enhancement",
        targetScale = 1.0f,
        sharpeningAmount = 0f
    ),
    /**
     * 1080p smart upscale — fast, social-media friendly. No pixel-scale
     * change; the "smart" part is the contrast-adaptive sharpening +
     * saturation pop, which recovers perceived detail on already-decent
     * 720p / 1080p sources.
     */
    SMART_HD_1080P(
        displayName = "Smart HD",
        description = "1080p · Fast enhancement for social media",
        targetScale = 1.0f,
        sharpeningAmount = 0.55f
    ),
    /**
     * True 2× upscale to 4K, paired with deep detail reconstruction.
     * The export pipeline uses Lanczos resampling (a TFLite ESRGAN
     * model, if present in `assets/upscaler.tflite`, would replace
     * the Lanczos pass).
     */
    ULTRA_4K(
        displayName = "Ultra 4K",
        description = "Deep detail reconstruction · 2× upscale",
        targetScale = 2.0f,
        sharpeningAmount = 0.85f
    ),
    /**
     * Same upscale as 1080p but tuned for facial detail: stronger
     * midtone sharpening, slight contrast lift, and de-blur bias.
     */
    FACE_CLARITY_BOOST(
        displayName = "Face & Clarity",
        description = "Enhance facial details · de-blur · adaptive sharpen",
        targetScale = 1.0f,
        sharpeningAmount = 0.95f
    );

    /** Convenience: true when any enhancement is engaged. */
    val isEnhancing: Boolean get() = this != OFF
}

/**
 * Per-clip upscaler state. Distinct from a global setting so the
 * effect travels with the clip across selection changes and persists
 * with the project.
 *
 * @param preset which [UpscalerPreset] is active
 * @param strength user-controlled intensity 0..1 (multiplies
 *   `preset.sharpeningAmount`). 0 disables the effect entirely
 *   (even with a non-OFF preset) so the slider can act as a kill switch.
 * @param splitPosition 0..1 — position of the before/after divider on
 *   the preview, only relevant while the user is in compare mode.
 *   Persisted so the user's compare position survives a recomposition
 *   but not so it interrupts the export.
 */
data class UpscalerSettings(
    val preset: UpscalerPreset = UpscalerPreset.OFF,
    val strength: Float = 0.5f,
    val splitPosition: Float = 0.5f
) {
    /** True when the live preview + export should apply enhancement. */
    val isActive: Boolean
        get() = preset.isEnhancing && strength > 0f

    /** Effective sharpening factor (0..1) for the preview matrix. */
    val effectiveSharpening: Float
        get() = (preset.sharpeningAmount * strength).coerceIn(0f, 1f)

    companion object {
        /** Identity (no effect) settings. */
        val Identity = UpscalerSettings()
    }
}
