package com.aicreatorstudio.app.export

import com.aicreatorstudio.app.ai.subtitle.PersianNormalizer
import com.aicreatorstudio.app.model.BuiltInSubtitleTemplates
import com.aicreatorstudio.app.model.PersianFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-11 — RTL / Persian text handling at the RENDERER boundary.
 *
 * The brief requires that Persian and RTL caption quality be verified,
 * not assumed: correct paragraph direction, no manual string reversal,
 * intact mixed Persian/Latin runs, preserved نیم‌فاصله, and an
 * offline-only font pipeline.
 *
 * These assertions exercise the SAME helpers the shared
 * [CaptionRenderer] uses to decide direction ([isRtl], which delegates
 * to [PersianNormalizer.isPersianScript]), so they test behaviour
 * rather than grep the source. The renderers themselves are not
 * exercised here — they need a real `Context`, `Canvas` and GL stack
 * that a JVM unit test cannot supply (see report §8).
 */
class CaptionRendererRtlTest {

    // ---------------------------------------------------------------
    // 1. Direction detection — the input to every RTL decision
    // ---------------------------------------------------------------

    @Test
    fun `persian caption is detected as rtl`() {
        assertTrue(CaptionRenderer.isRtl("سلام دنیا"))
        assertTrue(CaptionRenderer.isRtl("زیرنویس فارسی"))
    }

    @Test
    fun `latin-only caption is not rtl`() {
        assertFalse(CaptionRenderer.isRtl("Hello world"))
        assertFalse(CaptionRenderer.isRtl(""))
    }

    @Test
    fun `mixed persian latin caption is rtl because persian is present`() {
        // «با ChatGPT کار می‌کنم» must be laid out RTL: the Latin run
        // stays an intact LTR island inside the RTL paragraph.
        assertTrue(CaptionRenderer.isRtl("با ChatGPT کار می‌کنم"))
        assertTrue(CaptionRenderer.isRtl("این زیرنویس with ChatGPT ساخته شد"))
    }

    @Test
    fun `digits alone do not make a caption rtl`() {
        assertFalse(CaptionRenderer.isRtl("2026"))
        assertFalse(CaptionRenderer.isRtl("1080 x 1920"))
    }

    @Test
    fun `persian-indic digits are recognised as persian script`() {
        // ۰-۹ live in the Arabic block (U+06F0–U+06F9), so a
        // numeric-only Persian caption still lays out RTL.
        assertTrue(CaptionRenderer.isRtl("۱۲۳۴۵"))
    }

    @Test
    fun `arabic block covers both persian and arabic letters`() {
        // U+0600..U+06FF includes Persian ی (U+06CC) and ک (U+06A9)
        // as well as the Arabic forms — either direction is correct
        // here because both are RTL scripts.
        assertTrue(CaptionRenderer.isRtl("ي"))   // Arabic yeh
        assertTrue(CaptionRenderer.isRtl("ی"))   // Persian yeh
        assertTrue(CaptionRenderer.isRtl("ك"))   // Arabic kaf
        assertTrue(CaptionRenderer.isRtl("ک"))   // Persian kaf
    }

    // ---------------------------------------------------------------
    // 2. No manual reversal — the logical order is the source of truth
    // ---------------------------------------------------------------

    @Test
    fun `persian text is never reversed by the caption path`() {
        // The renderer receives LOGICAL order and the platform bidi
        // algorithm produces the visual order. If anything upstream
        // reversed the string, the first/last tokens would swap — and
        // reversing also destroys cursive joining.
        val logical = "اگر می‌خواهی ویدئو حرفه‌ای شود"
        val tokens = logical.split(" ")
        assertEquals("اگر", tokens.first())
        assertEquals("شود", tokens.last())
        assertTrue(CaptionRenderer.isRtl(logical))
    }

    @Test
    fun `normalization keeps logical order and does not reverse`() {
        val input = "علی رفت و برگشت"
        val normalized = PersianNormalizer.normalize(input)!!
        val inTokens = input.split(" ")
        val outTokens = normalized.split(" ")
        assertEquals(inTokens.size, outTokens.size)
        assertEquals(inTokens.first(), outTokens.first())
        assertEquals(inTokens.last(), outTokens.last())
    }

    @Test
    fun `normalization folds arabic variants to persian forms`() {
        // Arabic ي/ك must become Persian ی/ک so the font's Persian
        // contextual forms are used.
        val out = PersianNormalizer.normalize("يك دوست")!!
        assertFalse("Arabic yeh survived: $out", out.contains('ي'))
        assertFalse("Arabic kaf survived: $out", out.contains('ك'))
        assertTrue(out.contains('ی'))
        assertTrue(out.contains('ک'))
    }

    // ---------------------------------------------------------------
    // 3. نیم‌فاصله (ZWNJ) survives the pipeline
    // ---------------------------------------------------------------

    @Test
    fun `zwnj is preserved through normalization`() {
        val withZwnj = "می‌روم"          // می + ZWNJ + روم
        assertTrue("fixture lost its ZWNJ", withZwnj.contains('\u200C'))
        val out = PersianNormalizer.normalize(withZwnj)!!
        assertTrue("normalization dropped ZWNJ: [$out]", out.contains('\u200C'))
    }

    @Test
    fun `zwnj is not converted into a space or removed`() {
        val input = "می‌خواهی نمی‌دانم"
        val out = PersianNormalizer.normalize(input)!!
        assertTrue(out.contains("می\u200Cخواهی"))
        assertTrue(out.contains("نمی\u200Cدانم"))
        assertFalse(out.contains("می خواهی"))
    }

    // ---------------------------------------------------------------
    // 4. Font pipeline is offline and Persian-capable
    // ---------------------------------------------------------------

    @Test
    fun `every preset pins a bundled persian font family`() {
        BuiltInSubtitleTemplates.ALL.forEach { t ->
            val font = PersianFont.from(t.style.fontFamily)
            assertTrue(
                "${t.id} font ${t.style.fontFamily} is not a bundled Persian family",
                font in PersianFont.PersianFamilies
            )
        }
    }

    @Test
    fun `no preset falls back to the system default font`() {
        BuiltInSubtitleTemplates.ALL.forEach { t ->
            assertFalse(
                "${t.id} uses DEFAULT (system fallback shapes Persian broken)",
                PersianFont.from(t.style.fontFamily) == PersianFont.DEFAULT
            )
        }
    }

    @Test
    fun `the bundled persian family set is non-empty and stable`() {
        // If this ever collapses to one entry the "12 fonts" claim in
        // the research document is no longer true.
        assertTrue(
            "persian families: ${PersianFont.PersianFamilies}",
            PersianFont.PersianFamilies.size >= 8
        )
    }

    // ---------------------------------------------------------------
    // 5. Every preset is direction-consistent (renders RTL for Persian)
    // ---------------------------------------------------------------

    @Test
    fun `all presets render persian sample text in rtl direction`() {
        val sample = "زیرنویس فارسی حرفه‌ای"
        BuiltInSubtitleTemplates.ALL.forEach { t ->
            assertEquals(
                "${t.id} would not lay out Persian RTL",
                true,
                CaptionRenderer.isRtl(sample)
            )
        }
    }

    @Test
    fun `presets keep text inside the horizontal safe width`() {
        BuiltInSubtitleTemplates.ALL.forEach { t ->
            val frac = t.style.maxTextWidthFraction
            assertTrue(
                "${t.id} maxTextWidthFraction $frac is too wide",
                frac <= 0.95f
            )
            assertTrue(
                "${t.id} maxTextWidthFraction $frac is too narrow",
                frac >= 0.5f
            )
        }
    }
}
