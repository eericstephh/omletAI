package com.aicreatorstudio.app.model.template

import com.aicreatorstudio.app.model.BuiltInSubtitleTemplates
import com.aicreatorstudio.app.model.CaptionBackgroundKind
import com.aicreatorstudio.app.model.SubtitleTemplate
import com.aicreatorstudio.app.model.WordMotionKind
import com.aicreatorstudio.app.model.WordRevealMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-16 — the EIGHT product categories the subtitle brief names
 * (`docs/subtitles/implementation-plan.md` §4) are a derived browse
 * taxonomy over the shipped catalog.
 *
 * These tests pin the contract the picker relies on:
 *  - every requested category exists and carries a Persian label;
 *  - the category the Subtitle Studio actually serves (the JSON
 *    catalog) fills all eight, with ≥20 presets in total;
 *  - classification is TOTAL (no template is ever dropped) and
 *    DETERMINISTIC (same input → same bucket);
 *  - unknown / future / user templates still land in a sensible
 *    bucket instead of an "unknown" hole.
 */
class SubtitleTemplateTaxonomyTest {

    private fun assetTemplates(): List<SubtitleTemplate> {
        // Unit-test working dir == the app/ module dir (see
        // JsonTemplateParserTest), so the real shipped asset is read.
        val file = File("src/main/assets/subtitle_templates.json")
        assertTrue("asset missing: ${file.absolutePath}", file.exists())
        val parsed = JsonTemplateParser.parse(file.readText())
        return parsed.catalog.templates.map {
            JsonTemplateMapper.toSubtitleTemplate(ResolvedTemplate.resolve(it))
        }
    }

    // ---- taxonomy metadata ----

    @Test
    fun `all eight brief categories exist with unique keys and persian labels`() {
        val all = SubtitleTemplateCategory.entries
        assertEquals(8, all.size)
        assertEquals(8, all.map { it.key }.toSet().size)
        all.forEach { c ->
            assertTrue("blank key", c.key.isNotBlank())
            assertTrue("blank fa label for ${c.key}", c.displayNameFa.isNotBlank())
            assertTrue("blank en label for ${c.key}", c.displayNameEn.isNotBlank())
        }
    }

    // ---- the served catalog covers every category ----

    @Test
    fun `the JSON catalog fills all eight categories with at least twenty presets`() {
        val templates = assetTemplates()
        assertEquals(25, templates.size)

        val coverage = SubtitleTemplateTaxonomy.coverage(templates)
        assertEquals(
            "every brief category must be non-empty in the served catalog",
            SubtitleTemplateCategory.entries.toSet(),
            coverage.keys
        )
        assertTrue(
            "brief requires >=20 presets across the categories",
            templates.size >= 20
        )
        assertEquals(templates.size, coverage.values.sum())
    }

    @Test
    fun `the hand built catalog is classified totally too`() {
        val builtIn = BuiltInSubtitleTemplates.ALL
        assertEquals(26, builtIn.size)
        val coverage = SubtitleTemplateTaxonomy.coverage(builtIn)
        assertEquals(builtIn.size, coverage.values.sum())
        // HONEST GAP: the 26 hand-built designs predate the brief and
        // have no dedicated *educational* preset, so that is the one
        // category they do not fill. Recorded here so the gap is
        // visible in CI rather than discovered by a user.
        assertEquals(
            SubtitleTemplateCategory.entries.toSet() -
                setOf(SubtitleTemplateCategory.EDUCATIONAL),
            coverage.keys
        )
    }

    // ---- curated assignments ----

    @Test
    fun `curated ids map to the intended categories`() {
        fun classify(id: String): SubtitleTemplateCategory? =
            assetTemplates().firstOrNull { it.id == id }
                ?.let(SubtitleTemplateTaxonomy::classify)

        assertEquals(SubtitleTemplateCategory.KARAOKE_WORD_BY_WORD, classify("karaoke_classic"))
        assertEquals(SubtitleTemplateCategory.VIRAL_BOLD, classify("fa_pro_hormozi_viral"))
        assertEquals(SubtitleTemplateCategory.PODCAST_INTERVIEW, classify("cinema_black_box"))
        assertEquals(SubtitleTemplateCategory.MINIMAL_CINEMATIC, classify("minimal_clean"))
        assertEquals(SubtitleTemplateCategory.EDUCATIONAL, classify("cinema_yellow_script"))
        assertEquals(SubtitleTemplateCategory.KEYWORD_EMPHASIS, classify("keyword_punch_zoom"))
        assertEquals(SubtitleTemplateCategory.CLEAN_PERSIAN, classify("fa_pro_glass_minimal"))
        assertEquals(SubtitleTemplateCategory.ROUNDED_OUTLINED, classify("cinema_black_pill"))
    }

    @Test
    fun `hand built ids map to the intended categories`() {
        fun classify(id: String): SubtitleTemplateCategory? =
            BuiltInSubtitleTemplates.ALL.firstOrNull { it.id == id }
                ?.let(SubtitleTemplateTaxonomy::classify)

        assertEquals(SubtitleTemplateCategory.PODCAST_INTERVIEW, classify("fa_10_podcast"))
        assertEquals(SubtitleTemplateCategory.ROUNDED_OUTLINED, classify("fa_13_outline"))
        assertEquals(SubtitleTemplateCategory.KARAOKE_WORD_BY_WORD, classify("fa_04_karaoke"))
        assertEquals(SubtitleTemplateCategory.KEYWORD_EMPHASIS, classify("fa_03_yellow_word"))
        assertEquals(SubtitleTemplateCategory.CLEAN_PERSIAN, classify("fa_01_clean"))
    }

    // ---- property fallback for unknown / future / user templates ----

    private fun unknown(
        id: String = "user_custom_${System.nanoTime()}",
        motion: WordMotionKind = WordMotionKind.NONE,
        background: CaptionBackgroundKind = CaptionBackgroundKind.NONE,
        reveal: WordRevealMode = WordRevealMode.ALL_AT_ONCE
    ) = SubtitleTemplate(
        id = id,
        name = "قالب کاربر",
        motionKind = motion,
        backgroundKind = background,
        wordRevealMode = reveal
    )

    @Test
    fun `unknown ids fall back to properties and never throw`() {
        assertEquals(
            SubtitleTemplateCategory.VIRAL_BOLD,
            SubtitleTemplateTaxonomy.classify(unknown(motion = WordMotionKind.BOUNCE_POP))
        )
        assertEquals(
            SubtitleTemplateCategory.KARAOKE_WORD_BY_WORD,
            SubtitleTemplateTaxonomy.classify(unknown(reveal = WordRevealMode.KARAOKE))
        )
        assertEquals(
            SubtitleTemplateCategory.ROUNDED_OUTLINED,
            SubtitleTemplateTaxonomy.classify(unknown(background = CaptionBackgroundKind.SOLID_ROUNDED))
        )
        assertEquals(
            SubtitleTemplateCategory.KEYWORD_EMPHASIS,
            SubtitleTemplateTaxonomy.classify(unknown(motion = WordMotionKind.NEON_GLOW))
        )
        // Nothing matches → the safest readable default.
        assertEquals(
            SubtitleTemplateCategory.CLEAN_PERSIAN,
            SubtitleTemplateTaxonomy.classify(unknown())
        )
    }

    // ---- grouping contract ----

    @Test
    fun `groups follow declaration order and skip empty buckets`() {
        val groups = SubtitleTemplateTaxonomy.group(assetTemplates())
        val order = groups.map { it.first }
        assertEquals(order.sortedBy { SubtitleTemplateCategory.entries.indexOf(it) }, order)
        assertTrue(SubtitleTemplateTaxonomy.group(emptyList()).isEmpty())
        groups.forEach { (_, list) -> assertFalse("empty bucket", list.isEmpty()) }
    }

    @Test
    fun `classification is deterministic and partitions the catalog`() {
        val templates = assetTemplates() + BuiltInSubtitleTemplates.ALL
        val first = templates.map(SubtitleTemplateTaxonomy::classify)
        val second = templates.map(SubtitleTemplateTaxonomy::classify)
        assertEquals(first, second)

        // Grouping loses nothing and duplicates nothing.
        val grouped = SubtitleTemplateTaxonomy.group(templates).flatMap { it.second }
        assertEquals(templates.size, grouped.size)
        assertEquals(templates.map { it.id }.toSet(), grouped.map { it.id }.toSet())
    }
}
