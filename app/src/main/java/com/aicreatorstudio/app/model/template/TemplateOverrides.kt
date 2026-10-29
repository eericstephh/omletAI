package com.aicreatorstudio.app.model.template

/**
 * PHASE 2 — the 6 independent template axes plus the Base +
 * Overrides resolution model.
 *
 * Invariant: editing ONE axis never touches the other five.
 * `JsonTemplate` is the immutable base; [TemplateOverrides] holds
 * only what the user changed (null = untouched); [ResolvedTemplate]
 * is base-with-overrides applied. Resetting is structural:
 * [TemplateOverrides.cleared] drops one axis,
 * [TemplateOverrides.clearedAll] drops everything — the base is
 * never mutated, so a template switch can never destroy user work
 * by accident (the overrides object simply travels or resets
 * explicitly).
 *
 * Pure data, no Android — JVM unit-tested. Engine mapping (fonts,
 * motions, backgrounds) lands in later phases; this file only
 * decides *what* wins per axis.
 */
enum class TemplateAxis {
    FONT,
    COLOR,
    SHADOW,
    WORD_HIGHLIGHT,
    BACKGROUND,
    MOTION,
    /** PHASE 9 — position + scale axis (new 7th axis). */
    POSITION
}

/** Per-axis sparse overrides; every null means "keep the base". */
data class FontOverride(
    val family: String? = null,
    val weight: String? = null,
    val sizeRatio: Float? = null,
    /**
     * PHASE 9 — whole-caption scale multiplier (0.5x–2.0x, null =
     * keep base). Applied as a multiplier on top of fontSizeSp so
     * template proportions are preserved.
     */
    val scaleRatio: Float? = null
)

data class ColorOverride(
    val base: String? = null
)

data class ShadowOverride(
    val type: String? = null,
    val color: String? = null,
    val width: Float? = null,
    val radius: Float? = null
)

data class HighlightOverride(
    val type: String? = null,
    val activeColor: String? = null,
    val inactiveColor: String? = null,
    val barColor: String? = null,
    val trackColor: String? = null,
    /**
     * 2026-10-15 — box-only: the active word's TEXT color when the
     * highlight draws a pill behind it (inverted chip). Null keeps
     * the historical single-color contract.
     */
    val boxTextColor: String? = null
)

data class BackgroundOverride(
    val type: String? = null,
    val color: String? = null,
    val colors: List<String>? = null,
    val cornerRadius: Float? = null,
    val paddingRatio: Float? = null,
    val angle: Float? = null,
    val blurRadius: Float? = null,
    val overlayOpacity: Float? = null
)

data class MotionOverride(
    val entryType: String? = null,
    val entryDuration: Double? = null,
    val entryEasing: String? = null,
    val exitType: String? = null,
    val exitDuration: Double? = null,
    val exitEasing: String? = null
)

/**
 * PHASE 9 — position + scale axis override.
 * Normalized coordinates [0..1] for X/Y (frame-relative) and
 * optional scale ratio [0.5..2.0] for whole-caption sizing.
 */
data class PositionOverride(
    val positionX: Float? = null,
    val positionY: Float? = null,
    val scaleRatio: Float? = null
)

data class TemplateOverrides(
    val font: FontOverride? = null,
    val color: ColorOverride? = null,
    val shadow: ShadowOverride? = null,
    val wordHighlight: HighlightOverride? = null,
    val background: BackgroundOverride? = null,
    val motion: MotionOverride? = null,
    /** PHASE 9 — position + scale axis. */
    val position: PositionOverride? = null
) {
    /** True when the user changed nothing. */
    fun isEmpty(): Boolean =
        font == null && color == null && shadow == null &&
            wordHighlight == null && background == null &&
            motion == null && position == null

    /** Axes the user touched. */
    fun axes(): Set<TemplateAxis> = buildSet {
        if (font != null) add(TemplateAxis.FONT)
        if (color != null) add(TemplateAxis.COLOR)
        if (shadow != null) add(TemplateAxis.SHADOW)
        if (wordHighlight != null) add(TemplateAxis.WORD_HIGHLIGHT)
        if (background != null) add(TemplateAxis.BACKGROUND)
        if (motion != null) add(TemplateAxis.MOTION)
        if (position != null) add(TemplateAxis.POSITION)
    }

    /** Drop a single axis back to the base (reset-friendly). */
    fun cleared(axis: TemplateAxis): TemplateOverrides = when (axis) {
        TemplateAxis.FONT -> copy(font = null)
        TemplateAxis.COLOR -> copy(color = null)
        TemplateAxis.SHADOW -> copy(shadow = null)
        TemplateAxis.WORD_HIGHLIGHT -> copy(wordHighlight = null)
        TemplateAxis.BACKGROUND -> copy(background = null)
        TemplateAxis.MOTION -> copy(motion = null)
        TemplateAxis.POSITION -> copy(position = null)
    }

    /** Drop everything back to the base template. */
    fun clearedAll(): TemplateOverrides = TemplateOverrides()

    companion object {
        val Empty: TemplateOverrides = TemplateOverrides()
    }
}

/** Fully-resolved, render-ready view of Base + Overrides. */
data class ResolvedFont(
    val weight: String,
    val sizeRatio: Float
)

data class ResolvedColor(
    val base: String
)

data class ResolvedShadow(
    val type: String,
    val color: String?,
    val width: Float?,
    val radius: Float?
)

data class ResolvedHighlight(
    val type: String,
    val activeColor: String?,
    val inactiveColor: String?,
    val sync: String,
    val barColor: String?,
    val trackColor: String?,
    val barHeightRatio: Float?,
    val fillDirectionAware: Boolean,
    val keywordScaleBoost: Float?,
    /** 2026-10-15 — box-only inverted-chip text color (see HighlightOverride). */
    val boxTextColor: String? = null
)

data class ResolvedBackground(
    val type: String,
    val color: String?,
    val colors: List<String>,
    val cornerRadius: Float?,
    val paddingRatio: Float?,
    val angle: Float?,
    val blurRadius: Float?,
    val overlayOpacity: Float?
)

data class ResolvedMotionPhase(
    val type: String,
    val duration: Double?,
    val easing: String?,
    val charDelay: Double?,
    val overshoot: Double?
)

data class ResolvedMotion(
    val entry: ResolvedMotionPhase,
    val exit: ResolvedMotionPhase
)

data class ResolvedTemplate(
    val id: String,
    val nameFa: String,
    val nameEn: String,
    val category: String,
    /** Effective font per script ("" = engine fallback decides). */
    val fontFa: String,
    val fontEn: String,
    /** Effective direction, always a safe rtl/ltr pair. */
    val directionFa: String,
    val directionEn: String,
    val font: ResolvedFont,
    val color: ResolvedColor,
    val shadow: ResolvedShadow,
    val wordHighlight: ResolvedHighlight,
    val background: ResolvedBackground,
    val motion: ResolvedMotion
) {
    /**
     * Resolve [base] with [overrides]: every non-null override
     * field wins, everything else falls through to the base.
     * Directions always resolve to a safe rtl/ltr pair even when
     * the base ships blanks.
     */
    companion object {
        fun resolve(
            base: JsonTemplate,
            overrides: TemplateOverrides = TemplateOverrides.Empty
        ): ResolvedTemplate {
            val o = overrides
            return ResolvedTemplate(
                id = base.id,
                nameFa = base.nameFa,
                nameEn = base.nameEn,
                category = base.category,
                fontFa = o.font?.family ?: base.language.fontMapFa,
                fontEn = o.font?.family ?: base.language.fontMapEn,
                directionFa = base.language.directionFa.ifBlank { "rtl" },
                directionEn = base.language.directionEn.ifBlank { "ltr" },
                font = ResolvedFont(
                    weight = o.font?.weight ?: base.font.weight,
                    sizeRatio = o.font?.sizeRatio ?: base.font.sizeRatio
                ),
                color = ResolvedColor(
                    base = o.color?.base ?: base.color.base
                ),
                shadow = ResolvedShadow(
                    type = o.shadow?.type ?: base.shadow.type,
                    color = o.shadow?.color ?: base.shadow.color,
                    width = o.shadow?.width ?: base.shadow.width,
                    radius = o.shadow?.radius ?: base.shadow.radius
                ),
                wordHighlight = ResolvedHighlight(
                    type = o.wordHighlight?.type ?: base.wordHighlight.type,
                    activeColor = o.wordHighlight?.activeColor
                        ?: base.wordHighlight.activeColor,
                    inactiveColor = o.wordHighlight?.inactiveColor
                        ?: base.wordHighlight.inactiveColor,
                    sync = base.wordHighlight.sync,
                    barColor = o.wordHighlight?.barColor
                        ?: base.wordHighlight.barColor,
                    trackColor = o.wordHighlight?.trackColor
                        ?: base.wordHighlight.trackColor,
                    barHeightRatio = base.wordHighlight.barHeightRatio,
                    fillDirectionAware = base.wordHighlight.fillDirectionAware,
                    keywordScaleBoost = base.wordHighlight.keywordScaleBoost,
                    boxTextColor = o.wordHighlight?.boxTextColor
                        ?: base.wordHighlight.boxTextColor
                ),
                background = ResolvedBackground(
                    type = o.background?.type ?: base.background.type,
                    color = o.background?.color ?: base.background.color,
                    colors = o.background?.colors ?: base.background.colors,
                    cornerRadius = o.background?.cornerRadius
                        ?: base.background.cornerRadius,
                    paddingRatio = o.background?.paddingRatio
                        ?: base.background.paddingRatio,
                    angle = o.background?.angle ?: base.background.angle,
                    blurRadius = o.background?.blurRadius
                        ?: base.background.blurRadius,
                    overlayOpacity = o.background?.overlayOpacity
                        ?: base.background.overlayOpacity
                ),
                motion = ResolvedMotion(
                    entry = ResolvedMotionPhase(
                        type = o.motion?.entryType ?: base.motion.entry.type,
                        duration = o.motion?.entryDuration
                            ?: base.motion.entry.duration,
                        easing = o.motion?.entryEasing
                            ?: base.motion.entry.easing,
                        charDelay = base.motion.entry.charDelay,
                        overshoot = base.motion.entry.overshoot
                    ),
                    exit = ResolvedMotionPhase(
                        type = o.motion?.exitType ?: base.motion.exit.type,
                        duration = o.motion?.exitDuration
                            ?: base.motion.exit.duration,
                        easing = o.motion?.exitEasing
                            ?: base.motion.exit.easing,
                        charDelay = base.motion.exit.charDelay,
                        overshoot = base.motion.exit.overshoot
                    )
                )
            )
        }
    }
}
