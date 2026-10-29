package com.aicreatorstudio.app.debugtools.showcase

import com.aicreatorstudio.app.ai.TranscribedWord
import com.aicreatorstudio.app.model.BuiltInSubtitleTemplates
import com.aicreatorstudio.app.model.SubtitleTemplate
import com.aicreatorstudio.app.model.TextOverlay
import com.aicreatorstudio.app.model.withTemplate

/**
 * PHASE 5 — deterministic caption fixtures for the debug subtitle showcase.
 *
 * ## Why hand-authored text and timing
 *
 * The showcase must exercise the real Persian shaping, wrapping and
 * word-highlight code paths with *repeatable* input, so the same template
 * can be compared across states and across builds. Nothing here comes from
 * speech recognition: these are authored strings with authored timings. No
 * accuracy claim of any kind is made from them, and the timings are
 * deliberately UNEVEN so the motion engines see a realistic active-word
 * sequence instead of a synthetic equal division.
 *
 * ## What the three fixtures cover
 *
 * | Fixture | Lines | Coverage |
 * |---|---|---|
 * | [ShowcaseCaption.SHORT] | 1 | ZWNJ, joined letters, Persian digits, `!` |
 * | [ShowcaseCaption.LONG] | 2 | ZWNJ, Persian digits, mixed Latin, `.` |
 * | [ShowcaseCaption.MULTILINE] | 3 | `«»`, `()`, `٪`, Persian digits, ZWNJ |
 *
 * ## Why the ZWNJ is written as `\u200C`
 *
 * A literal half-space is invisible, and in this repository it has already
 * been destroyed once in transit (the QA phase-4 fixture had to be rebuilt
 * byte-by-byte). Writing it as an explicit escape makes the half-space
 * survive any editor, copy/paste or encoding step, and lets
 * `ShowcaseFixtureTest` assert that the fixture really contains U+200C
 * instead of trusting that it looks right.
 *
 * The caption [ShowcaseCue.text] is DERIVED from the token list rather than
 * typed twice, so text and per-word timings cannot drift apart.
 *
 * This file is pure Kotlin (no Android APIs) so it is unit-testable from
 * `src/testDebug`.
 */

/** The three caption lengths the showcase can display. */
enum class ShowcaseCaption(val label: String, val detail: String) {
    /** One line. The common case for a punchy viral caption. */
    SHORT("Short", "1 line · 4 words"),

    /** Two lines. The common case for a full sentence. */
    LONG("Long", "2 lines · 14 words"),

    /** Three lines. The worst case for wrapping and bottom safe area. */
    MULTILINE("Multiline", "3 lines · 8 words")
}

/**
 * One deterministic showcase caption: the exact [text] including its own
 * line breaks, the per-word timing, and the window the playhead scrubs.
 *
 * [text] and [words] are the *same* tokens in the same order — that is
 * what `KaraokeRanges` relies on to highlight the spoken word, and what
 * the showcase's line-count assertions pin.
 */
data class ShowcaseCue(
    val variant: ShowcaseCaption,
    val text: String,
    val words: List<TranscribedWord>,
    val durationMs: Long
) {
    /** Number of whitespace-separated tokens, i.e. the word count. */
    val tokenCount: Int get() = words.size

    /** Logical line count as rendered (`\n` separated). */
    val lineCount: Int get() = text.count { it == '\n' } + 1

    /**
     * Index of the word being spoken at [ms], or null between words. This
     * is the same authority the renderer uses (`TextOverlay.activeWordAt`
     * → `KaraokeRanges.activeRange`).
     */
    fun activeTokenIndex(ms: Long): Int? =
        words.indexOfFirst { it.isActiveAt(ms) }.takeIf { it >= 0 }
}

/** The three fixtures, and the registry the showcase enumerates. */
object ShowcaseFixture {

    /** Zero-width non-joiner — the Persian half-space. */
    const val ZWNJ: String = "\u200C"

    private data class Tok(val text: String, val start: Long, val end: Long)

    /**
     * Build a cue from its tokens. [lineStarts] holds the token indices that
     * begin a new line (always including 0), so [ShowcaseCue.text] is the
     * tokens joined by a space with `\n` at those indices — text and timing
     * are the same data, not two copies of it.
     */
    private fun cue(
        variant: ShowcaseCaption,
        durationMs: Long,
        lineStarts: Set<Int>,
        spec: List<Tok>
    ): ShowcaseCue {
        val words = spec.mapIndexed { i, t ->
            TranscribedWord(
                id = "showcase-${variant.name.lowercase()}-w$i",
                text = t.text,
                startTimeMs = t.start,
                endTimeMs = t.end
            )
        }
        val sb = StringBuilder()
        spec.forEachIndexed { i, t ->
            if (i > 0) sb.append(if (i in lineStarts) '\n' else ' ')
            sb.append(t.text)
        }
        return ShowcaseCue(variant, sb.toString(), words, durationMs)
    }

    /**
     * One line, four words. `میروم` carries a real half-space so the
     * shaping of a joined prefix is visible; `۳` is a Persian-indic digit.
     */
    private val SHORT = cue(
        variant = ShowcaseCaption.SHORT,
        durationMs = 2_100L,
        lineStarts = setOf(0),
        spec = listOf(
            Tok("می" + ZWNJ + "روم", 120L, 560L),
            Tok("بیرون،", 620L, 1040L),
            Tok("۳", 1120L, 1340L),
            Tok("دقیقه!", 1420L, 1880L)
        )
    )

    /**
     * Two lines, fourteen words. Stresses half-spaces (`پیشفرضهای`,
     * `حرفهای`, `همهچیز`, `همگامسازی`), Persian digits in a multi-part
     * version number, and a Latin run (`ChatGPT`) inside RTL text — the
     * classic bidi hazard.
     */
    private val LONG = cue(
        variant = ShowcaseCaption.LONG,
        durationMs = 4_800L,
        lineStarts = setOf(0, 6),
        spec = listOf(
            Tok("پیش" + ZWNJ + "فرض" + ZWNJ + "های", 100L, 480L),
            Tok("ویدئوسازی", 520L, 900L),
            Tok("حرفه" + ZWNJ + "ای", 940L, 1240L),
            Tok("در", 1300L, 1420L),
            Tok("نسخهٔ", 1480L, 1720L),
            Tok("۱.۲.۳", 1780L, 2140L),
            Tok("آماده", 2260L, 2560L),
            Tok("است", 2620L, 2820L),
            Tok("و", 2880L, 2940L),
            Tok("همه" + ZWNJ + "چیز", 3000L, 3300L),
            Tok("با", 3360L, 3480L),
            Tok("ChatGPT", 3540L, 3980L),
            Tok("همگام" + ZWNJ + "سازی", 4020L, 4360L),
            Tok("شد.", 4400L, 4560L)
        )
    )

    /**
     * Three lines, eight words. Adds Persian guillemets, parentheses and the
     * percent sign, so punctuation-adjacent joining is covered, and gives
     * wrapping a genuine worst case.
     */
    private val MULTILINE = cue(
        variant = ShowcaseCaption.MULTILINE,
        durationMs = 3_300L,
        lineStarts = setOf(0, 3, 5),
        spec = listOf(
            Tok("«زیرنویس", 120L, 620L),
            Tok("فارسی»", 680L, 1040L),
            Tok("را", 1120L, 1240L),
            Tok("می" + ZWNJ + "توانم", 1320L, 1700L),
            Tok("۱۰۰٪", 1760L, 2060L),
            Tok("تنظیم", 2120L, 2400L),
            Tok("کنم", 2460L, 2660L),
            Tok("(امشب)!", 2720L, 3100L)
        )
    )

    /** The three fixtures in display order. */
    val cues: List<ShowcaseCue> = listOf(SHORT, LONG, MULTILINE)

    fun cueFor(variant: ShowcaseCaption): ShowcaseCue = cues.first { it.variant == variant }

    /**
     * The production registry the showcase enumerates — derived, never
     * re-typed.
     *
     * [jsonCatalog] is the mapped `assets/subtitle_templates.json`
     * catalogue, i.e. exactly what the Subtitle Studio picker serves
     * (`EditorScreen`: `catalogTemplates = uiState.jsonCatalogTemplates`).
     * [BuiltInSubtitleTemplates.ALL] is the hand-built catalogue.
     *
     * Six `fa_pro_*` ids exist in BOTH sources. The JSON entry wins and the
     * built-in is dropped, mirroring `EditorViewModel.restoreJsonCatalog`'s
     * resolution order, so the showcase shows the same template a user
     * would actually get.
     */
    fun combinedRegistry(jsonCatalog: List<SubtitleTemplate>): List<SubtitleTemplate> {
        val jsonIds = jsonCatalog.mapTo(HashSet()) { it.id }
        return jsonCatalog + BuiltInSubtitleTemplates.ALL.filter { it.id !in jsonIds }
    }

    /**
     * Bind one fixture to one template — the same `withTemplate` call the
     * editor makes when a preset is picked, so every template field (style,
     * highlight, motion, background, progress bar) reaches the renderer
     * exactly as it does in production.
     *
     * The overlay id is stable so the preview's `remember` key does not
     * churn on recomposition.
     */
    fun overlayFor(template: SubtitleTemplate, cue: ShowcaseCue): TextOverlay =
        TextOverlay(
            id = "showcase-${template.id}-${cue.variant.name}",
            text = cue.text,
            startTimeMs = 0L,
            endTimeMs = cue.durationMs,
            words = cue.words
        ).withTemplate(template)
}
