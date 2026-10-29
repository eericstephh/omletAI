package com.aicreatorstudio.app.model

import kotlin.math.abs

/**
 * Project-level canvas presets.
 *
 * The canvas is the *output* frame: the rectangular viewport the user sees
 * in the editor preview, and the rectangle the exporter writes to. Picking
 * a preset resizes both. The "Free / Original" option ([FREE]) tracks the
 * source clip's own dimensions — there is no canvas override and the
 * preview follows whatever was imported.
 *
 * The numeric [aspectRatio] is `width / height` (so 9:16 = 0.5625f and
 * 16:9 = 1.7778f). [exportWidth] / [exportHeight] are the output pixel
 * dimensions the export engine targets when this preset is active. They
 * are 0 for [FREE] because the export falls back to the chosen
 * [com.aicreatorstudio.app.export.ExportQuality] in that case.
 *
 * @param displayName shown in the Ratio dock panel
 * @param aspectRatio `width / height`; `0f` means "track source"
 * @param exportWidth output pixel width, 0 = use ExportQuality default
 * @param exportHeight output pixel height, 0 = use ExportQuality default
 */
enum class CanvasPreset(
    val displayName: String,
    val aspectRatio: Float,
    val exportWidth: Int,
    val exportHeight: Int
) {
    FREE("Free / Original", 0f, 0, 0),
    VERTICAL_9_16("9:16", 9f / 16f, 1080, 1920),
    WIDE_16_9("16:9", 16f / 9f, 1920, 1080),
    SQUARE_1_1("1:1", 1f, 1080, 1080),
    PORTRAIT_4_5("4:5", 4f / 5f, 1080, 1350);

    /**
     * Resolve the effective aspect ratio for this preset given the source
     * clip's natural ratio. [FREE] returns [sourceAspect] (falling back to
     * 16:9 if the source is unknown); all other presets return their own
     * fixed ratio.
     */
    fun effectiveAspectRatio(sourceAspect: Float): Float =
        if (this == FREE) sourceAspect.takeIf { it > 0f } ?: (16f / 9f)
        else aspectRatio

    companion object {
        /**
         * Relative tolerance (±2.5%) when matching an imported clip's
         * aspect ratio against a preset's ratio. Encoded frames are not
         * always pixel-exact for their display ratio (e.g. 1920×1088
         * container frames carrying a 16:9 crop hint), so exact equality
         * would misclassify common clips.
         */
        private const val RATIO_MATCH_TOLERANCE = 0.025f

        /**
         * Derive the project canvas preset from an imported clip's
         * rotation-corrected display dimensions — i.e. the `width` /
         * `height` [com.aicreatorstudio.app.model.VideoClip] carries
         * after [com.aicreatorstudio.app.video.VideoMetadataExtractor]
         * has already swapped them for 90°/270° rotation metadata.
         *
         * This replaces the previous hard-coded `WIDE_16_9` project
         * default that dropped every portrait/Shorts-style import into
         * a 16:9 canvas (wrong letterbox in the preview and a wrong
         * output frame in the export).
         *
         * Near matches snap to the canonical preset so the export
         * engine bakes its matching `exportWidth` × `exportHeight`;
         * anything else — cinematic 2.39:1, unusual sensors, or unknown
         * (≤ 0) dimensions — returns [FREE] so the canvas tracks the
         * source 1:1 and the user's original aspect is preserved until
         * they intentionally change it from the Ratio panel.
         */
        fun forSourceDimensions(width: Int, height: Int): CanvasPreset {
            if (width <= 0 || height <= 0) return FREE
            val sourceRatio = width.toFloat() / height.toFloat()
            return entries.firstOrNull { preset ->
                preset != FREE &&
                    abs(preset.aspectRatio - sourceRatio) <=
                    preset.aspectRatio * RATIO_MATCH_TOLERANCE
            } ?: FREE
        }
    }
}
