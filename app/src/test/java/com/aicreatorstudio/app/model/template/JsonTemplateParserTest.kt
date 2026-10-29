package com.aicreatorstudio.app.model.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 2 — the catalog JSON decodes 1:1 with safe defaults, and
 * the shipped asset loads all 25 templates verbatim (19 originals
 * + the 2026-10-15 six-pro viral set).
 */
class JsonTemplateParserTest {

    @Test
    fun `shipped asset loads exactly 25 templates`() {
        // Unit-test working dir == the app/ module dir.
        val file = java.io.File("src/main/assets/subtitle_templates.json")
        assertTrue("asset missing: ${file.absolutePath}", file.exists())
        val result = JsonTemplateParser.parse(file.readText())
        assertEquals("3.0", result.catalog.schemaVersion)
        assertEquals(25, result.catalog.templates.size)
        assertTrue(result.skipped.isEmpty())
        val ids = result.catalog.templates.map { it.id }
        assertEquals(25, ids.toSet().size)
        listOf(
            "fast_zoom_reveal", "glitch_cut", "bounce_text_pop",
            "karaoke_classic", "progress_bar_underline", "typewriter_reveal",
            "slide_reveal", "minimal_clean", "neon_glow", "cinematic_bar",
            "keyword_punch_zoom", "cinema_black_box", "cinema_yellow_script",
            "cinema_red_box", "cinema_dark_box", "cinema_black_pill",
            "cinema_plain_white",
            // 2026-09-24: Wave / Filmstrip axes added for the 20-template request.
            "wave_accent", "filmstrip_frame",
            // 2026-10-15: the six-pro viral set.
            "fa_pro_hormozi_viral", "fa_pro_neon_cyberpunk",
            "fa_pro_glass_minimal", "fa_pro_comic_pop",
            "fa_pro_golden_lux", "fa_pro_red_punch"
        ).forEach { assertTrue("$it missing", ids.contains(it)) }
    }

    @Test
    fun `karaoke classic decodes with all six axes`() {
        val file = java.io.File("src/main/assets/subtitle_templates.json")
        val catalog = JsonTemplateParser.parse(file.readText()).catalog
        val t = catalog.templates.first { it.id == "karaoke_classic" }
        assertEquals("کارائوکه کلاسیک", t.nameFa)
        assertEquals("Karaoke Classic", t.nameEn)
        assertEquals("popular", t.category)
        assertEquals("Vazirmatn", t.language.fontMapFa)
        assertEquals("Montserrat", t.language.fontMapEn)
        assertEquals("rtl", t.language.directionFa)
        assertEquals("ltr", t.language.directionEn)
        assertEquals("bold", t.font.weight)
        assertEquals("#FFFFFF", t.color.base)
        assertEquals("outline", t.shadow.type)
        assertEquals("colorChange", t.wordHighlight.type)
        assertEquals("#FFD700", t.wordHighlight.activeColor)
        assertEquals("wordTimestampSweep", t.wordHighlight.sync)
        assertEquals("none", t.motion.entry.type)
        assertEquals("none", t.background.type)
    }

    @Test
    fun `progress bar template keeps its direction-aware extras`() {
        val file = java.io.File("src/main/assets/subtitle_templates.json")
        val catalog = JsonTemplateParser.parse(file.readText()).catalog
        val t = catalog.templates.first { it.id == "progress_bar_underline" }
        assertEquals("progressBar", t.wordHighlight.type)
        assertEquals("#FF7A00", t.wordHighlight.barColor)
        assertEquals("#FFFFFF33", t.wordHighlight.trackColor)
        assertEquals(0.12f, t.wordHighlight.barHeightRatio ?: -1f, 0.0001f)
        assertTrue(t.wordHighlight.fillDirectionAware)
    }

    @Test
    fun `sparse entry decodes with safe defaults`() {
        val result = JsonTemplateParser.parse(
            """{"schemaVersion":"3.0","templates":[{"id":"x"}]}"""
        )
        assertTrue(result.skipped.isEmpty())
        val t = result.catalog.templates.single()
        assertEquals("x", t.id)
        assertEquals("", t.nameFa)
        assertEquals("regular", t.font.weight)
        assertEquals(1f, t.font.sizeRatio, 0.0001f)
        assertEquals("none", t.shadow.type)
        assertEquals("none", t.wordHighlight.type)
        assertEquals("none", t.motion.entry.type)
        assertEquals("none", t.background.type)
        assertEquals("rtl", t.language.directionFa)
        assertEquals("ltr", t.language.directionEn)
        assertTrue(t.wordHighlight.fillDirectionAware)
    }

    @Test
    fun `blank ids are skipped and malformed documents stay safe`() {
        val withBlank = JsonTemplateParser.parse(
            """{"templates":[{"id":"  "},{"id":"ok"}]}"""
        )
        assertEquals(1, withBlank.catalog.templates.size)
        assertEquals("ok", withBlank.catalog.templates.single().id)
        assertEquals(1, withBlank.skipped.size)

        val broken = JsonTemplateParser.parse("not json{{{")
        assertTrue(broken.catalog.templates.isEmpty())
        assertFalse(broken.skipped.isEmpty())

        val empty = JsonTemplateParser.parse("{}")
        assertTrue(empty.catalog.templates.isEmpty())
        assertEquals("", empty.catalog.schemaVersion)
    }
}
