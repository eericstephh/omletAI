package com.aicreatorstudio.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-09-19 — every one of the exactly-20 shipped «زیرنویس
 * فارسی» templates pins a bundled Persian font family (Vazirmatn &
 * co.): the old DEFAULT fontFamily fell back to the system font,
 * which shapes Persian words broken/disjoint in the word-by-word
 * viral renderers.
 *
 * Vazirmatn also carries Latin glyphs, so mixed Persian/English
 * captions still render correctly.
 */
class BuiltInSubtitleTemplatesFaFontTest {

    @Test
    fun `catalog holds exactly 26 templates`() {
        assertEquals(26, BuiltInSubtitleTemplates.ALL.size)
        assertEquals(26, BuiltInSubtitleTemplates.ALL.map { it.id }.toSet().size)
    }

    @Test
    fun `every built-in template uses a bundled Persian font`() {
        val bad = BuiltInSubtitleTemplates.ALL.filter { t ->
            PersianFont.from(t.style.fontFamily) !in PersianFont.PersianFamilies
        }
        assertTrue(
            "templates without a Persian font: ${bad.map { it.id }}",
            bad.isEmpty()
        )
    }

    @Test
    fun `word highlight templates keep their reveal modes`() {
        assertEquals(
            WordRevealMode.HIGHLIGHT,
            BuiltInSubtitleTemplates.byId("fa_03_yellow_word")!!.wordRevealMode
        )
        assertEquals(
            WordRevealMode.KARAOKE,
            BuiltInSubtitleTemplates.byId("fa_04_karaoke")!!.wordRevealMode
        )
        assertEquals(
            WordRevealMode.WORD_BY_WORD,
            BuiltInSubtitleTemplates.byId("fa_07_creator")!!.wordRevealMode
        )
        assertEquals(
            WordRevealMode.KARAOKE,
            BuiltInSubtitleTemplates.byId("fa_20_signature")!!.wordRevealMode
        )
    }

    @Test
    fun `signature hero template carries its identity`() {
        val t = BuiltInSubtitleTemplates.byId("fa_20_signature")!!
        assertEquals("سیگنچر", t.name)
        assertEquals("شاخص", t.category)
        assertEquals(PersianFont.VAZIR.name, t.style.fontFamily)
        assertEquals(CaptionBackgroundKind.GRADIENT, t.backgroundKind)
        assertTrue(t.activeWordGlowRadiusDp > 0f)
    }
}
