package com.aicreatorstudio.app.model.template

import com.aicreatorstudio.app.model.CaptionBackgroundKind
import com.aicreatorstudio.app.model.PersianFont
import com.aicreatorstudio.app.model.ProgressBarSpec
import com.aicreatorstudio.app.model.SubtitleAlignmentH
import com.aicreatorstudio.app.model.SubtitleAlignmentV
import com.aicreatorstudio.app.model.SubtitleStyle
import com.aicreatorstudio.app.model.SubtitleTemplate
import com.aicreatorstudio.app.model.TextOverlayAnimation
import com.aicreatorstudio.app.model.WordMotionKind
import com.aicreatorstudio.app.model.WordRevealMode

/**
 * PHASE 4 — maps the JSON catalog ([ResolvedTemplate], i.e. Base +
 * Overrides already resolved) onto the engine's [SubtitleTemplate].
 *
 * Every rule below is documented at its site, including the
 * deliberate INTERIM mappings (a JSON capability the current
 * engine cannot express yet — these are owned by phases 5-8 and
 * degrade to the closest visible behavior, never to a crash):
 *
 *  - `progressBar` highlight → colorChange look (the travelling
 *    bar itself lands in phase 6);
 *  - `letterboxBars` background → none (phase 7);
 *  - `glitchIn/glitchOut`, `slideInDirectionAware` (horizontal),
 *    `fadeInBar` → nearest calm animation (phase 8);
 *  - easing strings → parsed through (model field arrives in
 *    phase 8; the engine keeps its fixed curves meanwhile);
 *  - `paddingRatio`, gradient `angle` → reserved, unread yet.
 *
 * Pure Kotlin, JVM unit-tested.
 */
object JsonTemplateMapper {

    /** Reference sp the JSON `sizeRatio` multiplies. */
    const val BASE_FONT_SP: Float = 28f

    /** Picker group order; unknown categories trail in encounter order. */
    val CATEGORY_ORDER: List<String> =
        listOf("popular", "dynamic", "fantasy", "standard", "cinema")

    fun categoryFaLabel(raw: String): String = when (raw) {
        "dynamic" -> "داینامیک"
        "fantasy" -> "فانتزی"
        "popular" -> "محبوب"
        "standard" -> "استاندارد"
        "cinema" -> "سینمایی"
        else -> raw
    }

    /**
     * Group engine templates by raw category in [CATEGORY_ORDER],
     * unknown categories appended in encounter order. Empty groups
     * are skipped.
     */
    fun groupByCategory(
        templates: List<SubtitleTemplate>
    ): List<Pair<String, List<SubtitleTemplate>>> {
        if (templates.isEmpty()) return emptyList()
        val buckets = LinkedHashMap<String, ArrayList<SubtitleTemplate>>()
        for (t in templates) buckets.getOrPut(t.category) { ArrayList() }.add(t)
        val ordered = ArrayList<Pair<String, List<SubtitleTemplate>>>()
        for (c in CATEGORY_ORDER) buckets.remove(c)?.let { ordered.add(c to it) }
        for ((c, list) in buckets) ordered.add(c to list)
        return ordered
    }

    /** Picker-ready view of a loaded catalog state. */
    data class MappedCatalog(
        val templates: List<SubtitleTemplate>,
        val status: JsonCatalogStatus
    )

    /**
     * Map a loaded [CatalogState] to picker state. ASSET with
     * entries → READY + mapped engine templates; anything else
     * (fallback, empty) → EMPTY + empty list so the picker shows
     * its stable skeleton. Pure — this is exactly what the
     * ViewModel publishes, unit-tested here instead of in Hilt.
     */
    fun mapCatalogState(state: CatalogState): MappedCatalog {
        if (state.source != CatalogState.Source.ASSET || state.templates.isEmpty()) {
            return MappedCatalog(emptyList(), JsonCatalogStatus.EMPTY)
        }
        val mapped = state.templates.map { json ->
            toSubtitleTemplate(ResolvedTemplate.resolve(json))
        }
        return MappedCatalog(mapped, JsonCatalogStatus.READY)
    }

    /**
     * Resolve a stored id against a mapped JSON catalog: direct hit
     * first, then the legacy migration ([migrateToJsonId]), else null.
     */
    fun pickTemplate(
        mapped: List<SubtitleTemplate>,
        id: String?
    ): SubtitleTemplate? {
        if (id.isNullOrBlank()) return null
        mapped.firstOrNull { it.id == id }?.let { return it }
        val migrated = migrateToJsonId(id) ?: return null
        return mapped.firstOrNull { it.id == migrated }
    }

    /**
     * Legacy migration: every pre-JSON id (the 20 `fa_*`, the 14
     * `tmpl_*`, the 10 `viral_*`) maps to its closest JSON
     * equivalent so saved projects and studio defaults keep their
     * look. Unknown ids return null.
     */
    fun migrateToJsonId(id: String): String? = when (id) {
        // fa_* (the 20 hand-built templates)
        "fa_01_clean" -> "minimal_clean"
        "fa_02_impact" -> "keyword_punch_zoom"
        "fa_03_yellow_word" -> "karaoke_classic"
        "fa_04_karaoke" -> "karaoke_classic"
        "fa_05_capsule" -> "cinema_black_pill"
        "fa_06_glass" -> "fast_zoom_reveal"
        "fa_07_creator" -> "bounce_text_pop"
        "fa_08_cinematic" -> "cinematic_bar"
        "fa_09_news" -> "cinema_black_box"
        "fa_10_podcast" -> "cinema_black_box"
        "fa_11_gaming" -> "glitch_cut"
        "fa_12_neon" -> "neon_glow"
        "fa_13_outline" -> "cinema_yellow_script"
        "fa_14_shadow_pop" -> "keyword_punch_zoom"
        "fa_15_dynamic" -> "slide_reveal"
        "fa_16_bar" -> "progress_bar_underline"
        "fa_17_modern_box" -> "cinema_dark_box"
        "fa_18_social" -> "fast_zoom_reveal"
        "fa_19_premium" -> "minimal_clean"
        "fa_20_signature" -> "karaoke_classic"
        // tmpl_* (pre-20 legacy) via their fa_* equivalents
        "tmpl_classic" -> "minimal_clean"
        "tmpl_minimal" -> "minimal_clean"
        "tmpl_cinematic" -> "cinematic_bar"
        "tmpl_reels" -> "fast_zoom_reveal"
        "tmpl_tiktok" -> "slide_reveal"
        "tmpl_word_by_word" -> "bounce_text_pop"
        "tmpl_karaoke" -> "karaoke_classic"
        "tmpl_dynamic" -> "slide_reveal"
        "tmpl_yellow_bg" -> "karaoke_classic"
        "tmpl_bold_stroke" -> "keyword_punch_zoom"
        "tmpl_neon_persian" -> "neon_glow"
        "tmpl_bounce_pop_en" -> "bounce_text_pop"
        "tmpl_neon_green" -> "neon_glow"
        "tmpl_minimal_box" -> "cinema_dark_box"
        // viral_* packs
        "viral_bounce_pop" -> "bounce_text_pop"
        "viral_typewriter" -> "typewriter_reveal"
        "viral_slide_up" -> "slide_reveal"
        "viral_fade_scale" -> "fast_zoom_reveal"
        "viral_shake" -> "glitch_cut"
        "viral_neon" -> "neon_glow"
        "viral_karaoke_fill" -> "karaoke_classic"
        "viral_rise_bounce" -> "keyword_punch_zoom"
        "viral_line_ribbon" -> "progress_bar_underline"
        "viral_frosted_pulse" -> "minimal_clean"
        else -> null
    }

    /** Full mapping: resolved JSON → engine template. */
    fun toSubtitleTemplate(r: ResolvedTemplate): SubtitleTemplate {
        val highlightActive = r.wordHighlight.type == "colorChange" ||
            r.wordHighlight.type == "box" ||
            r.wordHighlight.type == "progressBar" ||
            r.wordHighlight.sync == "wordTimestampSweep"
        val motion = mapMotion(r)
        val background = mapBackground(r)
        val shadow = mapShadow(r)
        val textColor = parseColor(r.color.base, 0xFFFFFFFF.toInt())
        // Effective active color: explicit activeColor wins; for
        // progressBar (which carries no activeColor — only barColor)
        // the bar color IS the highlight color, so the interim
        // highlight stays visible until phase 6 draws the real bar.
        val hlType = r.wordHighlight.type.trim().lowercase()
        val activeRaw: String? = r.wordHighlight.activeColor
            ?: r.wordHighlight.barColor.takeIf { hlType == "progressbar" }
        // Accent channel (glow halo / pill / gradient end): the glow
        // shadow color wins when present (it IS the halo), else the
        // gradient end, else the highlight active color.
        val accent = if (r.shadow.type.trim().lowercase() == "glow") {
            parseColor(r.shadow.color, 0xFF00E5FF.toInt())
        } else {
            gradientEndColor(r)
                ?: parseColor(activeRaw, 0xFF00E5FF.toInt())
        }
        return SubtitleTemplate(
            id = r.id,
            name = r.nameFa.ifBlank { r.id },
            builtIn = true,
            category = r.category,
            nameEn = r.nameEn,
            style = SubtitleStyle(
                fontFamily = mapFontFamily(r).name,
                fontSizeSp = (BASE_FONT_SP * r.font.sizeRatio).coerceAtLeast(12f),
                fontWeightValue = FontFallback.weightValue(r.font.weight),
                textColorArgb = parseColor(r.wordHighlight.inactiveColor, textColor),
                bold = mapBold(r.font.weight),
                strokeEnabled = shadow.strokeEnabled,
                strokeColorArgb = shadow.strokeColor,
                strokeWidthSp = shadow.strokeWidth,
                shadowEnabled = shadow.dropEnabled,
                shadowColorArgb = shadow.shadowColor,
                shadowOpacity = 0.5f,
                shadowOffsetYDp = 2f,
                backgroundEnabled = background.enabled,
                backgroundColorArgb = background.color,
                backgroundOpacity = background.opacity,
                backgroundCornerRadiusDp = background.cornerRadius,
                positionY = 0.85f,
                maxTextWidthFraction = 0.9f
            ),
            activeWordColorArgb = parseColor(
                // 2026-10-15 — an "inverted chip" (box) highlight may
                // pin its own text color so the word stays readable
                // on the pill; without it the pill and the text share
                // one color and the active word vanishes into the chip.
                r.wordHighlight.boxTextColor?.takeIf { hlType == "box" }
                    ?: activeRaw,
                textColor
            ),
            // 2026-10-16 — the served catalog obeys the SAME readability
            // cap as the hand-built set: an active word may not exceed
            // 1.12x (docs/persian-subtitle-template-research.md,
            // "activeWordScale is capped at 1.12"), the rule
            // SubtitleTemplateExportParityTest enforces for BuiltIn.
            // Before this clamp the mapper defaulted highlight-active
            // templates to 1.15 and passed keywordScaleBoost straight
            // through, so 18 of the 25 served templates drifted past
            // the cap (keyword_punch_zoom at 1.4). Clamping at the
            // boundary means a future catalog entry cannot drift either.
            activeWordScale = (r.wordHighlight.keywordScaleBoost
                ?: if (highlightActive) 1.15f else 1f).coerceIn(0.8f, 1.12f),
            activeWordBold = highlightActive,
            activeWordBackgroundArgb = if (r.wordHighlight.type == "box") {
                parseColor(activeRaw, textColor)
            } else null,
            animationIn = motion.entryAnim,
            animationOut = motion.exitAnim,
            animationDurationMs = motion.durationMs,
            // PHASE 8 — easing presets from JSON.
            easingIn = motion.easingIn,
            easingOut = motion.easingOut,
            wordRevealMode = motion.reveal,
            motionKind = motion.kind,
            backgroundKind = background.kind,
            activeWordGlowRadiusDp = shadow.glowRadius,
            accentColorArgb = accent,
            // PHASE 7 — gradient angle rides the overlay channel
            // (null = legacy horizontal); the renderer honors it.
            gradientAngleDeg = r.background.angle,
            // PHASE 6 — progressBar templates carry the live spec;
            // everything else maps to null (off).
            // 2026-09-24 — `waveaccent` is the WAVE shape of the SAME
            // bar contract: identical timing sync, RTL direction
            // awareness and placement, only the stroked path differs.
            progressBar = r.wordHighlight.type.trim().lowercase()
                .takeIf { it == "progressbar" || it == "waveaccent" }
                ?.let { barType ->
                    com.aicreatorstudio.app.model.ProgressBarSpec(
                        colorArgb = parseColor(r.wordHighlight.barColor, 0xFFFF7A00.toInt()),
                        trackArgb = parseColor(r.wordHighlight.trackColor, 0x33FFFFFF),
                        heightRatio = (r.wordHighlight.barHeightRatio ?: 0.12f)
                            .coerceIn(0.02f, 0.5f),
                        directionAware = r.wordHighlight.fillDirectionAware,
                        shape = if (barType == "waveaccent") {
                            com.aicreatorstudio.app.model.ProgressBarShape.WAVE
                        } else {
                            com.aicreatorstudio.app.model.ProgressBarShape.STRAIGHT
                        }
                    )
                }
        )
    }

    // ---- axis mappers (each independent, each tested) ----

    /** Bundled-family resolution: fa map → en map → Vazirmatn. Never null. */
    fun mapFontFamily(r: ResolvedTemplate): PersianFont =
        FontFallback.templateFamily(r.fontFa, r.fontEn)

    /** regular/medium → false; semibold and up → true. */
    fun mapBold(weight: String): Boolean = when (weight.trim().lowercase()) {
        "semibold", "bold", "extrabold", "black" -> true
        else -> false
    }

    /**
     * `#RRGGBB` / `#RRGGBBAA` → ARGB Int; anything else → [fallback].
     *
     * NOTE — the catalog uses CSS order (`#RRGGBBAA`, alpha LAST:
     * `#FFFFFF33` = white at 20%, `#000000CC` = black at 80%),
     * NOT Android's `#AARRGGBB`. This is locked by unit test.
     */
    fun parseColor(hex: String?, fallback: Int): Int {
        val h = hex?.trim()?.removePrefix("#") ?: return fallback
        return runCatching {
            when (h.length) {
                6 -> (0xFF shl 24) or h.toInt(16)
                8 -> {
                    val r = h.substring(0, 2).toInt(16)
                    val g = h.substring(2, 4).toInt(16)
                    val b = h.substring(4, 6).toInt(16)
                    val a = h.substring(6, 8).toInt(16)
                    (a shl 24) or (r shl 16) or (g shl 8) or b
                }
                else -> fallback
            }
        }.getOrDefault(fallback)
    }

    internal data class MappedShadow(
        val strokeEnabled: Boolean,
        val strokeColor: Int,
        val strokeWidth: Float,
        val dropEnabled: Boolean,
        val shadowColor: Int,
        val glowRadius: Float
    )

    internal fun mapShadow(r: ResolvedTemplate): MappedShadow = when (r.shadow.type.trim().lowercase()) {
        "outline" -> MappedShadow(
            strokeEnabled = true,
            strokeColor = parseColor(r.shadow.color, 0xFF000000.toInt()),
            strokeWidth = (r.shadow.width ?: 2f).coerceAtLeast(0.5f),
            dropEnabled = false,
            shadowColor = 0xFF000000.toInt(),
            glowRadius = 0f
        )
        "dropshadow" -> MappedShadow(
            strokeEnabled = false,
            strokeColor = 0xFF000000.toInt(),
            strokeWidth = 0f,
            dropEnabled = true,
            shadowColor = parseColor(r.shadow.color, 0xFF000000.toInt()),
            glowRadius = 0f
        )
        "glow" -> MappedShadow(
            strokeEnabled = false,
            strokeColor = 0xFF000000.toInt(),
            strokeWidth = 0f,
            dropEnabled = true,
            shadowColor = parseColor(r.shadow.color, 0xFF00E5FF.toInt()),
            glowRadius = (r.shadow.radius ?: 12f).coerceAtLeast(0f)
        )
        else -> MappedShadow(
            strokeEnabled = false,
            strokeColor = 0xFF000000.toInt(),
            strokeWidth = 0f,
            dropEnabled = false,
            shadowColor = 0xFF000000.toInt(),
            glowRadius = 0f
        )
    }

    internal data class MappedMotion(
        val kind: WordMotionKind,
        val reveal: WordRevealMode,
        val entryAnim: TextOverlayAnimation,
        val exitAnim: TextOverlayAnimation,
        val durationMs: Int,
        /** PHASE 8 — entry easing identifier (null = linear). */
        val easingIn: String? = null,
        /** PHASE 8 — exit easing identifier (null = linear). */
        val easingOut: String? = null
    )

    /**
     * Motion precedence: a per-word entry type (bounce/punch/
     * typewriter/glow/slide) wins over the highlight-default kind;
     * the highlight still owns reveal mode + colors. Unknown types
     * degrade to calm animation (phase 8 gives them real engines).
     */
    internal fun mapMotion(r: ResolvedTemplate): MappedMotion {
        val hl = r.wordHighlight.type.trim().lowercase()
        val entry = r.motion.entry.type.trim()
        val exit = r.motion.exit.type.trim()
        var kind = when (hl) {
            "colorchange" -> WordMotionKind.HIGHLIGHT_COLOR
            "box" -> WordMotionKind.HIGHLIGHT_COLOR
            "progressbar" -> WordMotionKind.HIGHLIGHT_COLOR
            // 2026-09-24 — the wave accent is the SAME per-word
            // highlight engine as the progress bar; only the stroked
            // path differs (CaptionRenderer.drawWaveAccent). Omitting
            // this fell through to NONE, so the template compiled and
            // mapped but never highlighted a word.
            "waveaccent" -> WordMotionKind.HIGHLIGHT_COLOR
            else -> WordMotionKind.NONE
        }
        if (r.wordHighlight.sync == "wordTimestampSweep") {
            kind = WordMotionKind.KARAOKE_FILL
        }
        // PHASE 8 — complete per-word entry engine mapping.
        // Each unique JSON entry type maps to the best-fit engine.
        when (entry.lowercase()) {
            "bouncescale" -> kind = WordMotionKind.BOUNCE_POP
            "punchzoomonkeyword" -> kind = WordMotionKind.BOUNCE_POP
            "characterbycharacter" -> kind = WordMotionKind.TYPEWRITER
            "glowpulsein" -> kind = WordMotionKind.NEON_GLOW
            "slideindirectionaware" -> kind = WordMotionKind.SLIDE_UP_WORD
            "glitchin" -> kind = WordMotionKind.GLITCH
            "scalein" -> kind = WordMotionKind.BOUNCE_POP // scale ≈ pop for engine
            "fadeinbar" -> kind = WordMotionKind.NONE // frame-level, not per-word
            "fadein", "fadeinup", "popin", "slideinup" -> {
                // Whole-caption animations — per-word highlight stays.
                // (kind is already set from the highlight type above.)
            }
            "none" -> {} // keep highlight kind
        }
        // Exit-type mapping (determines if engine changes on exit).
        when (exit.lowercase()) {
            "glitchout" -> kind = WordMotionKind.GLITCH
            "scaleDown" -> {} // scale-down uses same engine, just fades
        }
        val reveal = when {
            entry.lowercase() == "characterbycharacter" -> WordRevealMode.WORD_BY_WORD
            r.wordHighlight.sync == "wordTimestampSweep" -> WordRevealMode.KARAOKE
            hl == "colorchange" || hl == "box" || hl == "progressbar" ||
                hl == "waveaccent" -> WordRevealMode.HIGHLIGHT
            kind != WordMotionKind.NONE -> WordRevealMode.HIGHLIGHT
            else -> WordRevealMode.ALL_AT_ONCE
        }
        return MappedMotion(
            kind = kind,
            reveal = reveal,
            entryAnim = mapEntryAnim(entry),
            exitAnim = mapExitAnim(exit),
            durationMs = ((r.motion.entry.duration ?: 0.26) * 1000.0).toInt()
                .coerceIn(60, 1500),
            easingIn = r.motion.entry.easing,
            easingOut = r.motion.exit.easing
        )
    }

    // PHASE 8 — complete entry animation mapping (all 13 JSON types).
    private fun mapEntryAnim(type: String): TextOverlayAnimation = when (type.lowercase()) {
        "fadein" -> TextOverlayAnimation.FADE_IN
        "scalein", "bouncescale", "punchzoomonkeyword", "popin" -> TextOverlayAnimation.POP
        "fadeinup", "slideinup", "slideindirectionaware" -> TextOverlayAnimation.SLIDE_UP
        "fadeout" -> TextOverlayAnimation.FADE_OUT // edge case: fadeOut as entry
        "glitchin" -> TextOverlayAnimation.GLITCH_IN
        "characterbycharacter" -> TextOverlayAnimation.NONE // per-word engine handles it
        "fadeinbar" -> TextOverlayAnimation.FADE_IN // whole-caption fade
        "glowpulsein" -> TextOverlayAnimation.FADE_IN // glow is per-word engine
        "none" -> TextOverlayAnimation.NONE
        else -> TextOverlayAnimation.NONE
    }

    // PHASE 8 — complete exit animation mapping (all 7 JSON types).
    private fun mapExitAnim(type: String): TextOverlayAnimation = when (type.lowercase()) {
        "fadeout", "scaledown", "slideoutdirectionaware", "glowfadeout", "fadeoutbar" ->
            TextOverlayAnimation.FADE_OUT
        "glitchout" -> TextOverlayAnimation.GLITCH_OUT
        "none" -> TextOverlayAnimation.NONE
        else -> TextOverlayAnimation.NONE
    }

    internal data class MappedBackground(
        val kind: CaptionBackgroundKind,
        val enabled: Boolean,
        val color: Int,
        val opacity: Float,
        val cornerRadius: Float
    )

    internal fun mapBackground(r: ResolvedTemplate): MappedBackground {
        val b = r.background
        return when (b.type.trim().lowercase()) {
            "solidbox" -> MappedBackground(
                kind = CaptionBackgroundKind.SOLID_ROUNDED,
                enabled = true,
                color = parseColor(b.color, 0xFF000000.toInt()),
                opacity = 0.85f,
                cornerRadius = b.cornerRadius ?: 8f
            )
            "gradientbox" -> {
                MappedBackground(
                    kind = CaptionBackgroundKind.GRADIENT,
                    enabled = true,
                    color = parseColor(b.colors.getOrNull(0), 0xFF000000.toInt()),
                    opacity = 0.85f,
                    cornerRadius = 12f
                )
            }
            "blurvideo" -> MappedBackground(
                kind = CaptionBackgroundKind.BLUR,
                enabled = true,
                color = 0xFF000000.toInt(),
                opacity = b.overlayOpacity ?: 0.35f,
                // 2026-10-15 — honor an explicit cornerRadius (the
                // frosted-glass templates pin their own radius);
                // absent → the historical 16f.
                cornerRadius = b.cornerRadius ?: 16f
            )
            // letterboxBars is a real engine kind now (PHASE 7):
            // frame-level scope bars; the caption box itself stays
            // style-clean so text floats on video between the bars.
            "letterboxbars" -> MappedBackground(
                kind = CaptionBackgroundKind.LETTERBOX_BARS,
                enabled = false,
                color = parseColor(b.color, 0xFF000000.toInt()),
                opacity = 1f,
                cornerRadius = 0f
            )
            // 2026-09-24 — filmstrip: a caption-box plate with two
            // rows of perforations (box-level, unlike letterboxBars).
            "filmstrip" -> MappedBackground(
                kind = CaptionBackgroundKind.FILMSTRIP,
                enabled = true,
                color = parseColor(b.color, 0xFF0A0A0A.toInt()),
                opacity = b.overlayOpacity ?: 0.9f,
                cornerRadius = b.cornerRadius ?: 10f
            )
            // letterboxBars has no engine yet (phase 7) → honest none.
            else -> MappedBackground(
                kind = CaptionBackgroundKind.NONE,
                enabled = false,
                color = 0xFF000000.toInt(),
                opacity = 0f,
                cornerRadius = 0f
            )
        }
    }

    /** Gradient end color for the accent channel (phase 7 owns `angle`). */
    fun gradientEndColor(r: ResolvedTemplate): Int? {
        if (r.background.type.trim().lowercase() != "gradientbox") return null
        val second = r.background.colors.getOrNull(1) ?: return null
        return parseColor(second, 0xFF000000.toInt())
    }
}

/**
 * PHASE 11A — apply a font-axis override onto a working-copy
 * template. Touches ONLY font-derived style fields (fontFamily,
 * fontWeightValue, bold, fontSizeSp); color, shadow, highlight,
 * background, motion, position are left byte-identical. A null
 * override returns the template untouched.
 */
fun SubtitleTemplate.withFontOverride(
    o: FontOverride?
): SubtitleTemplate {
    if (o == null) return this
    val newFamily = o.family ?: style.fontFamily
    val newBold = when (o.weight) {
        "bold", "black", "heavy" -> true
        "regular", "light", "thin" -> false
        null -> style.bold
        else -> style.bold
    }
    val newWeight = when (o.weight) {
        "thin" -> 100
        "light" -> 300
        "regular" -> 400
        "medium" -> 500
        "semibold" -> 600
        "bold" -> 700
        "black", "heavy" -> 900
        null -> style.fontWeightValue
        else -> style.fontWeightValue
    }
    val newSize = o.sizeRatio?.let {
        val base = 36f
        (base * it.coerceIn(0.5f, 2.0f)).coerceIn(12f, 72f)
    } ?: style.fontSizeSp
    return copy(
        style = style.copy(
            fontFamily = newFamily,
            bold = newBold,
            fontWeightValue = newWeight,
            fontSizeSp = newSize
        )
    )
}

/**
 * PHASE 11A — apply a color-axis override onto a working-copy
 * template. Touches ONLY color-derived fields (textColorArgb +
 * activeWordColorArgb); font, shadow, highlight, background,
 * motion, position are left byte-identical. A null override
 * returns the template untouched.
 */
fun SubtitleTemplate.withColorOverride(
    o: ColorOverride?
): SubtitleTemplate {
    if (o == null) return this
    val newColor = o.base?.let {
        JsonTemplateMapper.parseColor(it, style.textColorArgb)
    } ?: return this
    return copy(
        style = style.copy(textColorArgb = newColor),
        activeWordColorArgb = newColor
    )
}

/**
 * PHASE 6 — apply a highlight-axis override onto a working-copy
 * template. Touches ONLY highlight-derived engine fields (motion
 * engine + reveal + active colors + pill + progress spec); font,
 * base color, shadow, background, entry/exit animations, durations
 * and scales are left byte-identical. A null override returns the
 * template untouched.
 *
 * Note: `motionKind` intentionally follows the highlight type (a
 * highlight NEEDS its engine); the Motion tab owns
 * entry/exit/durations/scales and never writes colors or bars.
 */
fun SubtitleTemplate.withHighlightOverride(
    o: HighlightOverride?
): SubtitleTemplate {
    if (o == null) return this
    val type = (o.type ?: when (motionKind) {
        WordMotionKind.HIGHLIGHT_COLOR -> "colorChange"
        WordMotionKind.KARAOKE_FILL -> "colorChange"
        WordMotionKind.NONE -> "none"
        else -> "colorChange"
    }).trim().lowercase()
    val active = o.activeColor?.let {
        JsonTemplateMapper.parseColor(it, activeWordColorArgb)
    } ?: activeWordColorArgb
    // PHASE 10C — inactive color maps to textColorArgb (non-active
    // words' foreground).
    val inactive = o.inactiveColor?.let {
        JsonTemplateMapper.parseColor(it, style.textColorArgb)
    }
    val barColor = o.barColor?.let {
        JsonTemplateMapper.parseColor(it, active)
    }
    val trackColor = o.trackColor?.let {
        JsonTemplateMapper.parseColor(it, 0x33FFFFFF)
    }
    return when (type) {
        "none" -> copy(
            motionKind = WordMotionKind.NONE,
            wordRevealMode = WordRevealMode.ALL_AT_ONCE,
            progressBar = null
        )
        "box" -> copy(
            motionKind = WordMotionKind.HIGHLIGHT_COLOR,
            wordRevealMode = WordRevealMode.HIGHLIGHT,
            // 2026-10-15 — boxTextColor (when set) is the word's TEXT
            // color; the pill stays [active]. Same contract as the
            // catalog mapping above, so the Highlight tab and the
            // catalog agree on the inverted-chip shape.
            activeWordColorArgb = o.boxTextColor?.let {
                JsonTemplateMapper.parseColor(it, active)
            } ?: active,
            activeWordBackgroundArgb = o.activeColor?.let {
                JsonTemplateMapper.parseColor(it, active)
            } ?: activeWordBackgroundArgb,
            style = if (inactive != null) style.copy(textColorArgb = inactive) else style,
            progressBar = null
        )
        "progressbar", "waveaccent" -> copy(
            motionKind = WordMotionKind.HIGHLIGHT_COLOR,
            wordRevealMode = WordRevealMode.HIGHLIGHT,
            activeWordColorArgb = active,
            style = if (inactive != null) style.copy(textColorArgb = inactive) else style,
            progressBar = (progressBar ?: ProgressBarSpec(
                colorArgb = active,
                trackArgb = trackColor ?: 0x33FFFFFF,
                heightRatio = 0.12f,
                directionAware = true
            )).let { bar ->
                bar.copy(
                    colorArgb = barColor ?: bar.colorArgb,
                    trackArgb = trackColor ?: bar.trackArgb,
                    // 2026-09-24 — the chosen accent shape wins, so
                    // picking the wave accent actually yields a wave.
                    shape = if (type == "waveaccent") {
                        com.aicreatorstudio.app.model.ProgressBarShape.WAVE
                    } else {
                        com.aicreatorstudio.app.model.ProgressBarShape.STRAIGHT
                    }
                )
            }
        )
        else -> copy( // colorChange (+ anything unknown)
            motionKind = WordMotionKind.HIGHLIGHT_COLOR,
            wordRevealMode = WordRevealMode.HIGHLIGHT,
            activeWordColorArgb = active,
            style = if (inactive != null) style.copy(textColorArgb = inactive) else style,
            progressBar = null
        )
    }
}

/**
 * PHASE 7 — apply a background-axis override onto a working-copy
 * template. Touches ONLY background-derived engine fields
 * (backgroundKind + style.backgroundColorArgb + gradientAngleDeg +
 * style.backgroundEnabled); font, color, shadow, highlight, motion
 * are left byte-identical. A null override returns the template
 * untouched.
 *
 * This is the Background tab's counterpart to withHighlightOverride.
 */
fun SubtitleTemplate.withBackgroundOverride(
    o: com.aicreatorstudio.app.model.template.BackgroundOverride?
): SubtitleTemplate {
    if (o == null) return this
    val type = o.type?.trim()?.lowercase()
    val newKind = when (type) {
        "none", "nonebox" -> CaptionBackgroundKind.NONE
        "solidbox" -> CaptionBackgroundKind.SOLID_ROUNDED
        "gradientbox" -> CaptionBackgroundKind.GRADIENT
        "blurvideo" -> CaptionBackgroundKind.BLUR
        "letterboxbars" -> CaptionBackgroundKind.LETTERBOX_BARS
        "perlinehighlight" -> CaptionBackgroundKind.PER_LINE_HIGHLIGHT
        "filmstrip" -> CaptionBackgroundKind.FILMSTRIP
        else -> backgroundKind
    }
    val colorParsed = o.color?.let {
        JsonTemplateMapper.parseColor(it, style.backgroundColorArgb)
    } ?: style.backgroundColorArgb
    val enabled = when (newKind) {
        CaptionBackgroundKind.NONE -> false
        else -> true
    }
    val angle = o.angle ?: gradientAngleDeg
    // PHASE 10D — map remaining BackgroundOverride fields that
    // have corresponding SubtitleStyle columns.  Fields without a
    // style counterpart (blurRadius, overlayOpacity, colors) are
    // silently accepted but not applied — no crash.
    val corner = o.cornerRadius?.coerceIn(0f, 40f)
    val opacity = o.overlayOpacity?.coerceIn(0f, 1f)
    return copy(
        backgroundKind = newKind,
        style = style.copy(
            backgroundEnabled = enabled,
            backgroundColorArgb = colorParsed,
            backgroundCornerRadiusDp = corner ?: style.backgroundCornerRadiusDp,
            backgroundOpacity = opacity ?: style.backgroundOpacity
        ),
        gradientAngleDeg = angle
    )
}

/**
 * PHASE 8 — apply a motion-axis override onto a working-copy
 * template. Touches ONLY motion-derived engine fields
 * (animationIn + animationOut + animationDurationMs + easingIn +
 * easingOut); font, color, shadow, highlight, background are left
 * byte-identical. A null override returns the template untouched.
 *
 * Note: `motionKind` intentionally does NOT change here — the
 * Motion tab owns entry/exit/easing/durations; the Highlight tab
 * owns the per-word engine (HIGHLIGHT_COLOR, BOUNCE_POP, etc.).
 */
fun SubtitleTemplate.withMotionOverride(
    o: com.aicreatorstudio.app.model.template.MotionOverride?
): SubtitleTemplate {
    if (o == null) return this
    val entryType = o.entryType?.trim()?.lowercase()
    val exitType = o.exitType?.trim()?.lowercase()
    val newEntryAnim = when (entryType) {
        "fadein", "fadein" -> TextOverlayAnimation.FADE_IN
        "pop", "popin", "scalein", "bouncescale", "punchzoomonkeyword" ->
            TextOverlayAnimation.POP
        "slidein", "slideinup", "fadeinup", "slideindirectionaware" ->
            TextOverlayAnimation.SLIDE_UP
        "glitchin" -> TextOverlayAnimation.GLITCH_IN
        "none" -> TextOverlayAnimation.NONE
        else -> animationIn
    }
    val newExitAnim = when (exitType) {
        "fadeout", "scaledown", "slideoutdirectionaware", "glowfadeout", "fadeoutbar" ->
            TextOverlayAnimation.FADE_OUT
        "glitchout" -> TextOverlayAnimation.GLITCH_OUT
        "none" -> TextOverlayAnimation.NONE
        else -> animationOut
    }
    val newDuration = o.entryDuration?.let {
        (it * 1000.0).toInt().coerceIn(60, 1500)
    } ?: animationDurationMs
    val newExitDuration = o.exitDuration?.let {
        (it * 1000.0).toInt().coerceIn(60, 1500)
    }
    val newEasingIn = o.entryEasing ?: easingIn
    val newEasingOut = o.exitEasing ?: easingOut
    return copy(
        animationIn = newEntryAnim,
        animationOut = newExitAnim,
        animationDurationMs = newDuration,
        animationDurationMsOut = newExitDuration ?: animationDurationMsOut,
        easingIn = newEasingIn,
        easingOut = newEasingOut
    )
}

/**
 * PHASE 10B — apply a shadow-axis override onto a working-copy
 * template. Touches ONLY shadow/stroke/glow derived engine fields
 * (strokeEnabled, strokeColorArgb, strokeWidthSp, shadowEnabled,
 * shadowColorArgb, shadowOpacity, shadowOffsetYDp,
 * activeWordGlowRadiusDp, accentColorArgb); font, color, highlight,
 * background, motion, position are left byte-identical. A null
 * override returns the template untouched.
 *
 * The `type` field maps to one of three visual families:
 *  - "outline"  -> stroke-only (stroke enabled, shadow off)
 *  - "dropshadow" -> shadow-only (shadow enabled, stroke off)
 *  - "glow"     -> neon glow behind the active word
 *  - "none"     -> both stroke and shadow disabled
 */
fun SubtitleTemplate.withShadowOverride(
    o: com.aicreatorstudio.app.model.template.ShadowOverride?
): SubtitleTemplate {
    if (o == null) return this
    val type = o.type?.trim()?.lowercase() ?: return this
    val colorParsed = o.color?.let {
        JsonTemplateMapper.parseColor(it, style.strokeColorArgb)
    }
    val width = o.width?.coerceIn(0f, 8f)
    val glowRadius = o.radius?.coerceIn(0f, 20f)
    return when (type) {
        "none" -> copy(
            style = style.copy(
                strokeEnabled = false,
                shadowEnabled = false
            ),
            activeWordGlowRadiusDp = 0f
        )
        "outline" -> copy(
            style = style.copy(
                strokeEnabled = true,
                strokeColorArgb = colorParsed ?: style.strokeColorArgb,
                strokeWidthSp = width ?: style.strokeWidthSp,
                shadowEnabled = false
            ),
            activeWordGlowRadiusDp = 0f
        )
        "dropshadow" -> copy(
            style = style.copy(
                strokeEnabled = false,
                shadowEnabled = true,
                shadowColorArgb = colorParsed ?: style.shadowColorArgb
            ),
            activeWordGlowRadiusDp = 0f
        )
        "glow" -> copy(
            style = style.copy(
                strokeEnabled = true,
                shadowEnabled = false
            ),
            activeWordGlowRadiusDp = glowRadius ?: activeWordGlowRadiusDp.coerceAtLeast(1f),
            accentColorArgb = colorParsed ?: accentColorArgb
        )
        else -> this
    }
}

// ---- PHASE 9 — position safe margins ----

/** Safe margin constants for social media overlays (normalized). */
object PositionSafeMargins {
    /** Top margin — avoid platform headers (0 = canvas top). */
    const val TOP: Float = 0.08f
    /** Bottom margin — avoid TikTok/Reels icons, comments bar. */
    const val BOTTOM: Float = 0.18f
    /** Left/right margin — avoid like/share buttons. */
    const val HORIZONTAL: Float = 0.06f

    /**
     * Clamps a Y position into the safe zone [TOP..1-BOTTOM].
     * Pure function — no Android deps.
     */
    fun clampSafeY(y: Float): Float = y.coerceIn(TOP, 1f - BOTTOM)

    /**
     * Clamps an X position into the safe zone [HORIZONTAL..1-HORIZONTAL].
     * Pure function — no Android deps.
     */
    fun clampSafeX(x: Float): Float = x.coerceIn(HORIZONTAL, 1f - HORIZONTAL)

    /**
     * Returns true if [y] is within the danger zone (below safe
     * bottom or above safe top). Used to highlight the slider.
     */
    fun isInDangerZone(y: Float): Boolean = y < TOP || y > (1f - BOTTOM)
}

/**
 * PHASE 9 — apply a position-axis override onto a working-copy
 * template. Touches ONLY position-derived fields (style.positionX +
 * style.positionY + style.fontSizeSp via scaleRatio); all other
 * axes (font family/weight, color, shadow, highlight, background,
 * motion) are left byte-identical. A null override returns the
 * template unchanged.
 */
fun SubtitleTemplate.withPositionOverride(
    o: PositionOverride?
): SubtitleTemplate {
    if (o == null) return this
    val newX = o.positionX?.let { PositionSafeMargins.clampSafeX(it) }
        ?: style.positionX
    val newY = o.positionY?.let { PositionSafeMargins.clampSafeY(it) }
        ?: style.positionY
    // Scale ratio: multiply the CURRENT fontSizeSp (which already
    // reflects the template's base sizeRatio) by the user's
    // multiplier, clamped to [0.5, 2.0].
    val newFontSize = o.scaleRatio?.let { ratio ->
        val clamped = ratio.coerceIn(0.5f, 2.0f)
        (style.fontSizeSp * clamped).coerceIn(12f, 72f)
    } ?: style.fontSizeSp
    // Determine alignment bands from the clamped position.
    val hAlign = when {
        newX < 0.3f -> SubtitleAlignmentH.LEFT
        newX > 0.7f -> SubtitleAlignmentH.RIGHT
        else -> SubtitleAlignmentH.CENTER
    }
    val vAlign = when {
        newY < 0.3f -> SubtitleAlignmentV.TOP
        newY > 0.7f -> SubtitleAlignmentV.BOTTOM
        else -> SubtitleAlignmentV.CENTER
    }
    return copy(
        style = style.copy(
            positionX = newX,
            positionY = newY,
            fontSizeSp = newFontSize,
            verticalAlignment = vAlign,
            horizontalAlignment = hAlign
        )
    )
}
