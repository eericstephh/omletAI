package com.aicreatorstudio.app.ai.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the decoder prompt builder.
 *
 * The primer is not cosmetic: whisper treats `initial_prompt` as
 * text it already produced, so anything instruction-shaped in it
 * becomes another sentence the model can echo instead of
 * transcribing the audio. These tests lock that property.
 */
class WhisperPromptTest {

    @Test
    fun `primer carries no instruction block`() {
        // The previous primer ended with "قواعد: کلمات فارسی را
        // شکست ندهید…" — whisper cannot follow instructions, it
        // imitates text, so that block was a hallucination source.
        assertFalse(
            "primer must not contain an instruction heading",
            WhisperPrompt.PRIMER.contains("قواعد")
        )
        assertFalse(WhisperPrompt.PRIMER.contains(":"))
        assertFalse(WhisperPrompt.PRIMER.contains("\n"))
    }

    @Test
    fun `primer establishes spacing and zwnj compounds`() {
        assertTrue("must contain spaces", WhisperPrompt.PRIMER.contains(" "))
        assertTrue(
            "must contain a ZWNJ compound so the model writes نیم‌فاصله",
            WhisperPrompt.PRIMER.contains('\u200C')
        )
        assertTrue("must be Persian", WhisperPrompt.PRIMER.any { it in '\u0600'..'\u06FF' })
    }

    @Test
    fun `empty dictionary and no context yields the bare primer`() {
        assertEquals(WhisperPrompt.PRIMER, WhisperPrompt.build(emptyList()))
    }

    @Test
    fun `dictionary words are appended`() {
        val p = WhisperPrompt.build(listOf("تپسل", "آپدیت"))
        assertTrue(p.startsWith(WhisperPrompt.PRIMER))
        assertTrue(p.contains("تپسل"))
        assertTrue(p.contains("آپدیت"))
    }

    @Test
    fun `blank dictionary entries are dropped`() {
        val p = WhisperPrompt.build(listOf("  ", "", "ایریکس"))
        assertTrue(p.contains("ایریکس"))
        assertFalse(p.contains("  "))
    }

    @Test
    fun `carried context is appended after the primer`() {
        val p = WhisperPrompt.build(emptyList(), "در آپدیت جدید چه چیزی عوض شده")
        assertTrue(p.startsWith(WhisperPrompt.PRIMER))
        assertTrue(p.endsWith("در آپدیت جدید چه چیزی عوض شده"))
    }

    @Test
    fun `confident window text is carried forward`() {
        val carry = WhisperPrompt.carryOverFrom("سلام به همه", meanConfidence = 0.9f)
        assertEquals("سلام به همه", carry)
    }

    @Test
    fun `low confidence window is not carried forward`() {
        val carry = WhisperPrompt.carryOverFrom("در رابطه ایتجایزش", meanConfidence = 0.2f)
        assertEquals("", carry)
    }

    @Test
    fun `unknown confidence is still carried`() {
        assertEquals("سلام", WhisperPrompt.carryOverFrom("سلام", meanConfidence = null))
    }

    @Test
    fun `long text is trimmed to a word boundary`() {
        val text = "یک دو سه چهار پنج شش هفت هشت نه ده یازده دوازده"
        val carry = WhisperPrompt.carryOverFrom(text, 0.9f, maxChars = 20)
        assertTrue("carry must be a suffix of the text", text.endsWith(carry))
        assertTrue("carry must be shorter than the input", carry.length <= 20)
        val startIdx = text.length - carry.length
        assertTrue(
            "carry must not start mid-word (char before was '${text[startIdx - 1]}')",
            text[startIdx - 1].isWhitespace()
        )
    }

    @Test
    fun `blank window text carries nothing`() {
        assertEquals("", WhisperPrompt.carryOverFrom("   ", 0.9f))
        assertEquals("", WhisperPrompt.carryOverFrom("", null))
    }

    @Test
    fun `mean confidence ignores unset probabilities`() {
        assertEquals(0.6f, WhisperPrompt.meanConfidence(listOf(0.5f, 0f, 0.7f))!!, 1e-4f)
        assertNull(WhisperPrompt.meanConfidence(emptyList()))
        assertNull(WhisperPrompt.meanConfidence(listOf(0f, 0f)))
    }
}
