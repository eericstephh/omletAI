package com.aicreatorstudio.app.ai.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE B7 — JVM unit tests for the Persian character-folding
 * normaliser. Locks the contract:
 *
 *  - Arabic ي (U+064A) folds to Persian ی (U+06CC);
 *  - Arabic ك (U+0643) folds to Persian ک (U+06A9);
 *  - ZWNJ (U+200C) is preserved (never stripped);
 *  - zero-width space and BOM are stripped;
 *  - whitespace runs collapse to a single ASCII space;
 *  - Latin / digits / URLs are passed through unchanged;
 *  - normalize is idempotent (apply twice == apply once);
 *  - null input returns null.
 *
 * The normaliser is **not** a translator and **not** a
 * paraphraser. It only folds characters whisper.cpp may have
 * emitted in their Arabic-form variants.
 */
class PersianNormalizerTest {

    // -------- Arabic → Persian folding --------

    @Test
    fun `arabic yeh folds to persian yeh`() {
        // The fixture uses an Arabic ي at index 0.
        assertEquals("ی", PersianNormalizer.normalize("ي"))
    }

    @Test
    fun `arabic keheh folds to persian keheh`() {
        assertEquals("ک", PersianNormalizer.normalize("ك"))
    }

    @Test
    fun `mixed arabic-persian yeh and keheh are both folded in a phrase`() {
        // "كتاب" written with Arabic ي+ك (the very common Arabic-script
        // mix that leaks from Arabic-trained Whisper when the user
        // speaks Persian).
        assertEquals("کیاب", PersianNormalizer.normalize("كياب"))
    }

    @Test
    fun `persian yeh and keheh are left untouched`() {
        assertEquals("کتاب", PersianNormalizer.normalize("کتاب"))
    }

    @Test
    fun `zwnj between words is preserved`() {
        // "می‌روم" — the ZWNJ is the Persian "نیم‌فاصله" and
        // MUST survive normalisation. Brief: "preserve نیم‌فاصله
        // where confidently present".
        val input = "می‌روم"
        assertEquals(input, PersianNormalizer.normalize(input))
    }

    @Test
    fun `zwnj is preserved through whitespace collapse`() {
        // The ZWNJ sits between two words; collapsing surrounding
        // spaces must not touch it.
        val input = "می   ‌   روم"
        val expected = "می ‌ روم"
        assertEquals(expected, PersianNormalizer.normalize(input))
    }

    // -------- zero-width stripping --------

    @Test
    fun `bom is stripped`() {
        assertEquals("سلام", PersianNormalizer.normalize("﻿سلام"))
    }

    @Test
    fun `zero-width space is stripped`() {
        assertEquals("سلام", PersianNormalizer.normalize("س​لام"))
    }

    @Test
    fun `multiple zwsp and bom are stripped in one pass`() {
        assertEquals(
            "سلام دنیا",
            PersianNormalizer.normalize("﻿س​لام​ دن​یا")
        )
    }

    // -------- whitespace --------

    @Test
    fun `runs of spaces collapse to a single ascii space`() {
        assertEquals("سلام دنیا", PersianNormalizer.normalize("سلام    دنیا"))
    }

    @Test
    fun `tabs and newlines collapse to a single ascii space`() {
        assertEquals("a b c", PersianNormalizer.normalize("a\tb\nc"))
    }

    @Test
    fun `leading and trailing whitespace is trimmed`() {
        assertEquals("سلام", PersianNormalizer.normalize("   سلام   "))
    }

    // -------- pass-through --------

    @Test
    fun `pure latin text is passed through unchanged`() {
        assertEquals("Hello World", PersianNormalizer.normalize("Hello World"))
    }

    @Test
    fun `pure digits are passed through unchanged`() {
        assertEquals("12345", PersianNormalizer.normalize("12345"))
    }

    @Test
    fun `url is passed through unchanged`() {
        val url = "https://example.com/path?x=1"
        assertEquals(url, PersianNormalizer.normalize(url))
    }

    @Test
    fun `latin punctuation is preserved`() {
        // The brief: "don't invent Persian punctuation; only fold
        // what whisper.cpp already produced."
        assertEquals("Hello, world!", PersianNormalizer.normalize("Hello, world!"))
    }

    @Test
    fun `persian punctuation is preserved`() {
        // Persian comma and question mark are already correct.
        assertEquals("سلام، دنیا؟", PersianNormalizer.normalize("سلام، دنیا؟"))
    }

    // -------- edge cases --------

    @Test
    fun `empty string returns empty string`() {
        assertEquals("", PersianNormalizer.normalize(""))
    }

    @Test
    fun `null input returns null`() {
        assertNull(PersianNormalizer.normalize(null))
    }

    @Test
    fun `normalize is idempotent`() {
        val input = "كتاب می‌روم"
        val once = PersianNormalizer.normalize(input)
        val twice = PersianNormalizer.normalize(once)
        assertEquals(once, twice)
    }

    @Test
    fun `isPersianScript true for arabic-block text`() {
        assertTrue(PersianNormalizer.isPersianScript("سلام"))
        assertTrue(PersianNormalizer.isPersianScript("كتاب می روم"))
    }

    @Test
    fun `isPersianScript false for latin only`() {
        assertFalse(PersianNormalizer.isPersianScript("Hello World"))
        assertFalse(PersianNormalizer.isPersianScript(""))
        assertFalse(PersianNormalizer.isPersianScript(null))
    }

    @Test
    fun `isPersianScript true for mixed script when at least one arabic-block char present`() {
        // "Hello سلام" is a mix but the segment still contains
        // Persian script, so the segment is in scope for normalisation.
        assertTrue(PersianNormalizer.isPersianScript("Hello سلام"))
    }
}
