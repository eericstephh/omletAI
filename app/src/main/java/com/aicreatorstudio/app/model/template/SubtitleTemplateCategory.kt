package com.aicreatorstudio.app.model.template

import com.aicreatorstudio.app.model.CaptionBackgroundKind
import com.aicreatorstudio.app.model.SubtitleTemplate
import com.aicreatorstudio.app.model.WordMotionKind
import com.aicreatorstudio.app.model.WordRevealMode

/**
 * 2026-10-16 — the EIGHT authoring categories the subtitle-template
 * product brief names, exposed as a browse taxonomy over the shipped
 * catalog.
 *
 * ## Why this is a separate layer (and not a new JSON key)
 *
 * The shipped `assets/subtitle_templates.json` already carries a
 * 5-value `category` key (`popular`, `dynamic`, `fantasy`,
 * `standard`, `cinema`) that is read by
 * [JsonTemplateMapper.groupByCategory] and pinned by
 * `JsonTemplateMapperTest`. That key is the catalog's *authoring*
 * grouping; it is deliberately left untouched here.
 *
 * This enum is the *presentation* taxonomy: a deterministic, derived
 * classification of a [SubtitleTemplate] onto the brief's eight
 * buckets. It adds no field to the data model, changes no JSON, and
 * therefore cannot alter any saved project or any existing render.
 * [classify] is a pure function of the template's already-resolved
 * rendering properties.
 *
 * ## Ordering
 *
 * Declaration order IS the on-screen group order, matching the brief
 * (1 … 8). [SubtitleTemplateTaxonomy.group] preserves it.
 */
enum class SubtitleTemplateCategory(
    /** Stable machine key (never localized, safe to persist/log). */
    val key: String,
    /** Persian picker label (RTL, first-class). */
    val displayNameFa: String,
    /** English mirror for the picker's secondary line. */
    val displayNameEn: String
) {
    /** 1 — Viral bold captions: big, punchy, motion-driven. */
    VIRAL_BOLD("viral_bold", "وایرال و درشت", "Viral Bold Captions"),

    /** 2 — Word-by-word karaoke: progressive, word-synced reveal. */
    KARAOKE_WORD_BY_WORD("karaoke_word_by_word", "کارائوکه کلمه‌به‌کلمه", "Word-by-Word Karaoke"),

    /** 3 — Podcast and interview captions: restrained lower-third, boxed. */
    PODCAST_INTERVIEW("podcast_interview", "پادکست و مصاحبه", "Podcast & Interview"),

    /** 4 — Minimal and cinematic captions. */
    MINIMAL_CINEMATIC("minimal_cinematic", "مینیمال و سینمایی", "Minimal & Cinematic"),

    /** 5 — Educational and explanatory captions. */
    EDUCATIONAL("educational", "آموزشی و توضیحی", "Educational"),

    /** 6 — Keyword-emphasis captions: one spoken word is lifted. */
    KEYWORD_EMPHASIS("keyword_emphasis", "تأکید بر کلیدواژه", "Keyword Emphasis"),

    /** 7 — Clean Persian-first captions: maximum legibility, no gimmick. */
    CLEAN_PERSIAN("clean_persian", "فارسی تمیز و خوانا", "Clean Persian-First"),

    /** 8 — Rounded-background and outlined-text captions. */
    ROUNDED_OUTLINED("rounded_outlined", "پس‌زمینه گرد و حاشیه‌دار", "Rounded & Outlined");
}

/**
 * Deterministic classifier + grouper for [SubtitleTemplateCategory].
 *
 * Pure Kotlin, no Android, no I/O — fully JVM unit-tested. Used by the
 * Subtitle Studio picker (display only) and by the taxonomy tests.
 */
object SubtitleTemplateTaxonomy {

    /**
     * Curated assignment for every id shipped today (the 25 JSON
     * catalog ids and the 26 hand-built `fa_*` ids). An explicit table
     * is used because the visual intent of a preset cannot always be
     * recovered from its resolved properties alone (e.g. two presets
     * can share `colorChange` yet target different viewers).
     */
    private val EXPLICIT: Map<String, SubtitleTemplateCategory> = mapOf(
        // ---- JSON catalog (assets/subtitle_templates.json) ----
        "fast_zoom_reveal" to SubtitleTemplateCategory.VIRAL_BOLD,
        "glitch_cut" to SubtitleTemplateCategory.VIRAL_BOLD,
        "bounce_text_pop" to SubtitleTemplateCategory.VIRAL_BOLD,
        "slide_reveal" to SubtitleTemplateCategory.VIRAL_BOLD,
        "fa_pro_hormozi_viral" to SubtitleTemplateCategory.VIRAL_BOLD,
        "fa_pro_comic_pop" to SubtitleTemplateCategory.VIRAL_BOLD,
        "fa_pro_red_punch" to SubtitleTemplateCategory.VIRAL_BOLD,

        "karaoke_classic" to SubtitleTemplateCategory.KARAOKE_WORD_BY_WORD,
        "progress_bar_underline" to SubtitleTemplateCategory.KARAOKE_WORD_BY_WORD,
        "wave_accent" to SubtitleTemplateCategory.KARAOKE_WORD_BY_WORD,
        "typewriter_reveal" to SubtitleTemplateCategory.KARAOKE_WORD_BY_WORD,

        "cinema_black_box" to SubtitleTemplateCategory.PODCAST_INTERVIEW,
        "cinema_dark_box" to SubtitleTemplateCategory.PODCAST_INTERVIEW,
        "cinema_red_box" to SubtitleTemplateCategory.PODCAST_INTERVIEW,

        "minimal_clean" to SubtitleTemplateCategory.MINIMAL_CINEMATIC,
        "cinematic_bar" to SubtitleTemplateCategory.MINIMAL_CINEMATIC,
        "filmstrip_frame" to SubtitleTemplateCategory.MINIMAL_CINEMATIC,
        "fa_pro_golden_lux" to SubtitleTemplateCategory.MINIMAL_CINEMATIC,

        "cinema_yellow_script" to SubtitleTemplateCategory.EDUCATIONAL,

        "keyword_punch_zoom" to SubtitleTemplateCategory.KEYWORD_EMPHASIS,
        "neon_glow" to SubtitleTemplateCategory.KEYWORD_EMPHASIS,
        "fa_pro_neon_cyberpunk" to SubtitleTemplateCategory.KEYWORD_EMPHASIS,

        "cinema_plain_white" to SubtitleTemplateCategory.CLEAN_PERSIAN,
        "fa_pro_glass_minimal" to SubtitleTemplateCategory.CLEAN_PERSIAN,

        "cinema_black_pill" to SubtitleTemplateCategory.ROUNDED_OUTLINED,

        // ---- hand-built catalog (model/BuiltInSubtitleTemplates.kt) ----
        "fa_01_clean" to SubtitleTemplateCategory.CLEAN_PERSIAN,
        "fa_02_impact" to SubtitleTemplateCategory.VIRAL_BOLD,
        "fa_03_yellow_word" to SubtitleTemplateCategory.KEYWORD_EMPHASIS,
        "fa_04_karaoke" to SubtitleTemplateCategory.KARAOKE_WORD_BY_WORD,
        "fa_05_capsule" to SubtitleTemplateCategory.ROUNDED_OUTLINED,
        "fa_06_glass" to SubtitleTemplateCategory.CLEAN_PERSIAN,
        "fa_07_creator" to SubtitleTemplateCategory.VIRAL_BOLD,
        "fa_08_cinematic" to SubtitleTemplateCategory.MINIMAL_CINEMATIC,
        "fa_09_news" to SubtitleTemplateCategory.PODCAST_INTERVIEW,
        "fa_10_podcast" to SubtitleTemplateCategory.PODCAST_INTERVIEW,
        "fa_11_gaming" to SubtitleTemplateCategory.VIRAL_BOLD,
        "fa_12_neon" to SubtitleTemplateCategory.KEYWORD_EMPHASIS,
        "fa_13_outline" to SubtitleTemplateCategory.ROUNDED_OUTLINED,
        "fa_14_shadow_pop" to SubtitleTemplateCategory.VIRAL_BOLD,
        "fa_15_dynamic" to SubtitleTemplateCategory.VIRAL_BOLD,
        "fa_16_bar" to SubtitleTemplateCategory.KARAOKE_WORD_BY_WORD,
        "fa_17_modern_box" to SubtitleTemplateCategory.ROUNDED_OUTLINED,
        "fa_18_social" to SubtitleTemplateCategory.VIRAL_BOLD,
        "fa_19_premium" to SubtitleTemplateCategory.MINIMAL_CINEMATIC,
        "fa_20_signature" to SubtitleTemplateCategory.KARAOKE_WORD_BY_WORD
    )

    /**
     * Assign exactly one category to [template].
     *
     * Curated ids win; any other template (a future catalog entry or a
     * user-saved one) is classified from its resolved rendering
     * properties so the picker never drops it into an "unknown"
     * bucket. Total and deterministic — never throws.
     */
    fun classify(template: SubtitleTemplate): SubtitleTemplateCategory =
        EXPLICIT[template.id] ?: classifyByProperties(template)

    /**
     * Property-based fallback for ids the curated table does not know.
     * Ordered by specificity: a rounded/pill surface or an outlined
     * treatment is a stronger signal than the motion vocabulary.
     */
    private fun classifyByProperties(t: SubtitleTemplate): SubtitleTemplateCategory {
        val style = t.style
        val roundedSurface =
            t.backgroundKind == CaptionBackgroundKind.SOLID_ROUNDED ||
                t.backgroundKind == CaptionBackgroundKind.PER_LINE_HIGHLIGHT
        val outlinedText = style.strokeEnabled &&
            !style.backgroundEnabled &&
            t.backgroundKind == CaptionBackgroundKind.NONE
        val karaokeish = t.wordRevealMode == WordRevealMode.KARAOKE ||
            t.wordRevealMode == WordRevealMode.WORD_BY_WORD ||
            t.motionKind == WordMotionKind.KARAOKE_FILL ||
            t.motionKind == WordMotionKind.TYPEWRITER
        val viralish = t.motionKind == WordMotionKind.BOUNCE_POP ||
            t.motionKind == WordMotionKind.GLITCH ||
            t.motionKind == WordMotionKind.SLIDE_UP_WORD ||
            t.motionKind == WordMotionKind.FADE_SCALE ||
            t.motionKind == WordMotionKind.RISE_BOUNCE ||
            t.motionKind == WordMotionKind.SHAKE_WIGGLE
        val keywordish = t.motionKind == WordMotionKind.NEON_GLOW ||
            t.motionKind == WordMotionKind.HIGHLIGHT_COLOR
        return when {
            roundedSurface || outlinedText -> SubtitleTemplateCategory.ROUNDED_OUTLINED
            karaokeish -> SubtitleTemplateCategory.KARAOKE_WORD_BY_WORD
            viralish -> SubtitleTemplateCategory.VIRAL_BOLD
            keywordish -> SubtitleTemplateCategory.KEYWORD_EMPHASIS
            else -> SubtitleTemplateCategory.CLEAN_PERSIAN
        }
    }

    /**
     * Group [templates] into the eight categories, in declaration
     * order, skipping empty buckets. Within a bucket the input order
     * is preserved (the picker's stable, author-controlled order).
     */
    fun group(
        templates: List<SubtitleTemplate>
    ): List<Pair<SubtitleTemplateCategory, List<SubtitleTemplate>>> {
        if (templates.isEmpty()) return emptyList()
        val buckets = LinkedHashMap<SubtitleTemplateCategory, ArrayList<SubtitleTemplate>>()
        for (t in templates) buckets.getOrPut(classify(t)) { ArrayList() }.add(t)
        return SubtitleTemplateCategory.entries.mapNotNull { c ->
            buckets[c]?.let { c to it.toList() }
        }
    }

    /** Non-empty category → number of templates, in declaration order. */
    fun coverage(templates: List<SubtitleTemplate>): Map<SubtitleTemplateCategory, Int> =
        group(templates).associate { (c, list) -> c to list.size }
}
