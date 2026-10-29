package com.aicreatorstudio.app.model

/**
 * 2026-10-13 — Chroma Key (green/blue screen removal) settings for one
 * PiP overlay.
 *
 * ## Scope
 *
 * This file owns **only the settings**. The algorithm lives in
 * `util/ChromaKeyEngine.kt`, which is the single implementation consumed by
 * BOTH renderers — `export/OverlayCompositeRenderer.kt` (the CPU frame
 * pre-pass) and `ui/screens/editor/OverlayPipLayer.kt` (the Compose
 * preview). Neither renderer may carry a copy of the maths: two
 * implementations kept in agreement "by review" is the failure mode this
 * project keeps hitting, and the resulting mismatch only ever shows up in
 * the exported file.
 *
 * ## Why a separate settings type from [OverlayMaskSettings]
 *
 * They compose rather than compete. A mask is a **geometric** clip applied
 * to the overlay's box; a chroma key is a **per-pixel** operation applied
 * to the overlay's own frame *before* it is scaled into that box. Both can
 * be active at once — a circular facecam shot on a green screen — and the
 * renderers apply them in that order on both sides.
 */
data class ChromaKeySettings(
    /** Whether the key is applied at all. */
    val isEnabled: Boolean = false,
    /**
     * The colour to remove, as an opaque ARGB int.
     *
     * Only the RGB channels are read; [sanitize][com.aicreatorstudio.app.util.ChromaKeyEngine.sanitize]
     * forces the alpha to `0xFF` so a stored value is always fully opaque
     * and two equal colours compare equal regardless of how they were
     * written.
     */
    val keyColorArgb: Int = DEFAULT_KEY_COLOR_ARGB,
    /**
     * How close a pixel must be to [keyColorArgb] to be removed, as a
     * fraction of the maximum possible chroma distance (`0.0..1.0`).
     *
     * Below this, alpha is `0`. Raising it removes more of the screen at
     * the cost of eating into the subject — which is why it is a slider
     * and not a constant.
     */
    val similarity: Float = DEFAULT_SIMILARITY,
    /**
     * The width of the soft edge, in the same normalised distance units as
     * [similarity] (`0.0..1.0`).
     *
     * Between [similarity] and `similarity + smoothness` alpha ramps via a
     * smooth Hermite curve, which is what stops a keyed edge from looking
     * like it was cut out with scissors. `0` gives a hard, aliased edge.
     */
    val smoothness: Float = DEFAULT_SMOOTHNESS,
    /**
     * How much of the key colour's tint to remove from the pixels that
     * survive, `0.0..1.0`.
     *
     * A green screen bleeds green onto the subject's edges — hair, a white
     * shirt, glasses. Left alone that fringe is the single most obvious
     * tell that a shot was keyed. See `ChromaKeyEngine.suppressSpill` for
     * the mechanism.
     */
    val spillReduction: Float = DEFAULT_SPILL_REDUCTION
) {
    /**
     * True when this overlay's tuning is entirely at its default, i.e.
     * there is nothing worth writing to the project file.
     *
     * Note this is **not** simply `!isEnabled`: a user who tunes the colour
     * and the sliders and *then* switches the key off has still made an
     * edit, and dropping it on save would silently undo their work the
     * next time they opened the project. The rule is therefore "is there
     * anything to remember", not "is it currently doing anything" — and it
     * is still true that a project which never touched this feature writes
     * no key at all, so an old project stays byte-identical.
     */
    val isIdentity: Boolean
        get() = !isEnabled &&
            keyColorArgb == DEFAULT_KEY_COLOR_ARGB &&
            similarity == DEFAULT_SIMILARITY &&
            smoothness == DEFAULT_SMOOTHNESS &&
            spillReduction == DEFAULT_SPILL_REDUCTION

    /** True when any tuning differs from the default, enabled or not. */
    val hasTuning: Boolean
        get() = keyColorArgb != DEFAULT_KEY_COLOR_ARGB ||
            similarity != DEFAULT_SIMILARITY ||
            smoothness != DEFAULT_SMOOTHNESS ||
            spillReduction != DEFAULT_SPILL_REDUCTION

    /** True when the renderer must actually touch pixels. */
    val isActive: Boolean get() = isEnabled

    /** The RGB channels only, alpha forced opaque. */
    val opaqueKeyColorArgb: Int get() = keyColorArgb or OPAQUE_ALPHA_MASK

    companion object {
        /**
         * `#00FF00` — the chroma-key green every consumer camera targets.
         *
         * Written as the signed decimal because `const val` needs a
         * compile-time constant and `0xFF00FF00.toInt()` is not one.
         * `-16711936 == 0xFF00FF00` as a 32-bit Int.
         */
        const val DEFAULT_KEY_COLOR_ARGB: Int = -16711936 // 0xFF00FF00

        /** `#0000FF` — the blue-screen alternative. `-16776961 == 0xFF0000FF`. */
        const val BLUE_KEY_COLOR_ARGB: Int = -16776961 // 0xFF0000FF

        /** `0xFF000000` as a signed Int. */
        const val OPAQUE_ALPHA_MASK: Int = -16777216 // 0xFF000000

        const val DEFAULT_SIMILARITY: Float = 0.40f
        const val DEFAULT_SMOOTHNESS: Float = 0.10f
        const val DEFAULT_SPILL_REDUCTION: Float = 0.50f

        /** The settings a freshly picked overlay carries. */
        val Default = ChromaKeySettings()
    }
}

/**
 * The colour presets the panel offers before the user reaches for a custom
 * colour.
 *
 * Kept here rather than in the panel so the persistence layer, the tests
 * and the UI all agree on what "the green preset" means.
 */
enum class ChromaKeyPreset(val displayName: String, val argb: Int) {
    /** «سبز» — the broadcast standard, `#00FF00`. */
    GREEN("سبز", ChromaKeySettings.DEFAULT_KEY_COLOR_ARGB),

    /** «آبی» — `#0000FF`, for a blue screen. */
    BLUE("آبی", ChromaKeySettings.BLUE_KEY_COLOR_ARGB)
}
