package com.aicreatorstudio.app.ai.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the OCR text pipeline pieces that do not need
 * Android runtime: normalization, RTL dominance detection, line
 * geometry, and the committed-result JSON round trip.
 *
 * Real Tesseract inference is covered by the instrumentation test
 * (TesseractOcrInstrumentationTest) on a live device/emulator.
 */
class OcrPipelineTest {

    // ----------------------------------------------------------------
    // OcrTextNormalizer
    // ----------------------------------------------------------------

    @Test
    fun `arabic yeh folds to persian yeh`() {
        val input = "سلام عليکم" // Arabic ي in عليكم
        val out = OcrTextNormalizer.normalizeWord(input)
        assertTrue("expected Persian ی", out.contains('ی'))
        assertFalse("Arabic ي must be folded", out.contains('ي'))
    }

    @Test
    fun `arabic kaf folds to persian keheh`() {
        val out = OcrTextNormalizer.normalizeWord("كتاب")
        assertTrue(out.contains('ک'))
        assertFalse(out.contains('ك'))
    }

    @Test
    fun `tatweel is stripped`() {
        val out = OcrTextNormalizer.normalizeWord("کتاـــب")
        assertEquals("کتاب", out)
    }

    @Test
    fun `zwnj preserved`() {
        val input = "می‌روم"
        val out = OcrTextNormalizer.normalizeWord(input)
        assertTrue("ZWNJ (نیم‌فاصله) must survive", out.contains('‌'))
    }

    @Test
    fun `presentation forms fold to canonical letters`() {
        // U+FEF3 (ARABIC LETTER FARSI YEH ISOLATED FORM) — an
        // Arabic presentation form Tesseract sometimes emits. NFKC
        // folding must canonicalize it away from the raw form.
        val raw = "ﻯ"
        val out = OcrTextNormalizer.normalizeWord(raw)
        assertNotEquals(raw, out)
    }

    @Test
    fun `whitespace collapsed`() {
        assertEquals("a b", OcrTextNormalizer.normalizeWord("a   b"))
    }

    @Test
    fun `blank returns empty`() {
        assertEquals("", OcrTextNormalizer.normalizeWord("   "))
    }

    // ----------------------------------------------------------------
    // Line geometry + confidence
    // ----------------------------------------------------------------

    @Test
    fun `ocr line confidence percent clamps`() {
        val line = OcrLine(text = "x", left = 0f, top = 0f, right = 1f, bottom = 1f, confidence = 0.85f)
        assertEquals(85, line.confidencePercent)
        val low = OcrLine("x", 0f, 0f, 1f, 1f, confidence = 1.5f)
        assertEquals(100, low.confidencePercent)
    }

    @Test
    fun `normalized rect stays within unit range`() {
        val line = OcrLine("متن", 0.1f, 0.2f, 0.9f, 0.4f, 0.9f)
        assertTrue(line.left in 0f..1f && line.right in 0f..1f)
        assertTrue(line.top in 0f..1f && line.bottom in 0f..1f)
        assertTrue(line.right > line.left)
        assertTrue(line.bottom > line.top)
    }

    // ----------------------------------------------------------------
    // Committed-result JSON round trip
    // ----------------------------------------------------------------

    @Test
    fun `ocr clip result json round trip`() {
        val result = OcrClipResult(
            text = "سلام دنیا\nHello 123",
            timestampMs = 12_345L,
            sourceWidth = 1920,
            sourceHeight = 1080,
            lineCount = 2,
            confidence = 0.87f,
            createdAtMs = 999L
        )
        val json = OcrClipResultJson.encode(result)
        val decoded = OcrClipResultJson.decode(json)
        assertEquals(result, decoded)
    }

    @Test
    fun `ocr clip result json null-safe decode`() {
        assertEquals(null, OcrClipResultJson.decode(null))
        assertEquals(null, OcrClipResultJson.decode(""))
        assertEquals(null, OcrClipResultJson.decode("not json"))
    }

    // ----------------------------------------------------------------
    // Reading order (2026-10-20)
    //
    // The old helper reversed the WHOLE line as soon as one Arabic
    // word was present, so the order of the Latin words was destroyed
    // as well — the exact case its own KDoc claimed to preserve.
    // ----------------------------------------------------------------

    @Test
    fun `a pure persian line is still reversed as a whole`() {
        val out = OcrReadingOrder.reorder(listOf("سلام", "دنیا"))
        assertEquals(listOf("دنیا", "سلام"), out)
    }

    @Test
    fun `latin words keep their order inside a persian line`() {
        val out = OcrReadingOrder.reorder(listOf("نام", "APP", "PRO", "خوب"))
        assertEquals(listOf("نام", "APP", "PRO", "خوب"), out)
    }

    @Test
    fun `only the arabic run is reversed`() {
        val out = OcrReadingOrder.reorder(listOf("نام", "خوب", "APP", "PRO"))
        assertEquals(listOf("خوب", "نام", "APP", "PRO"), out)
    }

    @Test
    fun `a number between persian words travels with them`() {
        val out = OcrReadingOrder.reorder(listOf("سلام", "123", "دنیا"))
        assertEquals(listOf("دنیا", "123", "سلام"), out)
    }

    @Test
    fun `a latin only line is untouched`() {
        val words = listOf("APP", "PRO", "X")
        assertEquals(words, OcrReadingOrder.reorder(words))
    }

    @Test
    fun `rtl dominance really counts the scripts`() {
        assertTrue(OcrReadingOrder.isRtlDominant(listOf("نام", "خوب")))
        assertFalse(OcrReadingOrder.isRtlDominant(listOf("APP", "PRO", "X")))
        // One Persian word among five English words is NOT dominant.
        assertFalse(
            OcrReadingOrder.isRtlDominant(listOf("نام", "A", "B", "C", "D", "E"))
        )
        // The old "any Arabic at all" predicate is still available.
        assertTrue(
            OcrReadingOrder.containsArabicScript(listOf("نام", "A", "B", "C", "D", "E"))
        )
    }

    // ----------------------------------------------------------------
    // Language pack encoding (2026-10-20)
    // ----------------------------------------------------------------

    @Test
    fun `the language mask round trips`() {
        val mask = OcrLanguagePacks.maskOf(listOf("fas", "eng"))
        assertEquals(5, mask)
        assertEquals(listOf("fas", "eng"), OcrLanguagePacks.codesOf(mask))
        assertEquals(mask, OcrLanguagePacks.maskOfStrength(mask.toFloat()))
    }

    @Test
    fun `an empty mask means every installed pack`() {
        assertEquals(0, OcrLanguagePacks.maskOfStrength(0f))
        assertEquals(emptyList<String>(), OcrLanguagePacks.codesOf(0))
        // A fractional value is not a mask.
        assertEquals(0, OcrLanguagePacks.maskOfStrength(0.5f))
        assertEquals(
            listOf("fas", "ara", "eng"),
            OcrLanguagePacks.codesOf(OcrLanguagePacks.maskOf(OcrLanguagePacks.ALL_CODES))
        )
    }
}
