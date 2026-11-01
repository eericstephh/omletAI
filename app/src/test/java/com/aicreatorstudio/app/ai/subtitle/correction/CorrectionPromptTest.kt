package com.aicreatorstudio.app.ai.subtitle.correction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the behavioural rules of the correction prompt.
 *
 * ## Why a prompt deserves a test
 *
 * The rules ARE the feature. A model told to "correct spelling" and
 * nothing else will happily summarise, formalise colloquial speech, or
 * translate English technical terms — and none of those failures is
 * visible in the app until a user reads a caption that no longer says
 * what they said. These assertions fail loudly if a future edit drops
 * a rule.
 */
class CorrectionPromptTest {

    private val prompt = CorrectionPrompt.SYSTEM

    @Test
    fun `prompt declares the proofreader role`() {
        assertTrue(prompt.contains("Persian subtitle proofreader", ignoreCase = true))
        assertTrue(prompt.contains("automatic speech recognition", ignoreCase = true))
    }

    @Test
    fun `prompt preserves colloquial speech`() {
        assertTrue(prompt.contains("Preserve colloquial Persian", ignoreCase = true))
        assertTrue(
            "formalising spoken Persian must be explicitly forbidden",
            prompt.contains("Do not convert spoken colloquial", ignoreCase = true)
        )
    }

    @Test
    fun `prompt forbids rewriting and summarising`() {
        assertTrue(prompt.contains("Do not summarize", ignoreCase = true))
        assertTrue(prompt.contains("paraphrase", ignoreCase = true))
        assertTrue(
            "adding or removing words must be forbidden",
            prompt.contains("Do not add information or remove words", ignoreCase = true)
        )
    }

    @Test
    fun `prompt protects names numbers and english terms`() {
        assertTrue(prompt.contains("English words", ignoreCase = true))
        assertTrue(prompt.contains("technical terms", ignoreCase = true))
        assertTrue(prompt.contains("numbers", ignoreCase = true))
    }

    @Test
    fun `prompt requires half-spaces explicitly`() {
        // نیم‌فاصله is the single most common Persian orthography
        // defect in ASR output; naming it is not optional.
        assertTrue(prompt.contains("half-spaces", ignoreCase = true))
        assertTrue(prompt.contains("نیم‌فاصله"))
    }

    @Test
    fun `prompt keeps homophones conservative`() {
        assertTrue(prompt.contains("homophones", ignoreCase = true))
        assertTrue(
            "uncertain corrections must keep the original",
            prompt.contains("If a correction is uncertain", ignoreCase = true)
        )
        assertTrue(prompt.contains("Never invent words", ignoreCase = true))
    }

    @Test
    fun `prompt demands structured output and nothing else`() {
        assertTrue(prompt.contains("Return only the requested structured output", ignoreCase = true))
        assertTrue(prompt.contains("\"segments\""))
        assertTrue(prompt.contains("Do not invent ids", ignoreCase = true))
        assertTrue(prompt.contains("Do not merge or split segments", ignoreCase = true))
    }

    // ---- user message ----

    @Test
    fun `user message carries every segment id and text`() {
        val batch = CorrectionBatch(
            index = 0,
            segments = listOf(
                CorrectionInputSegment("a", "نرمفزار"),
                CorrectionInputSegment("b", "دوشوار")
            )
        )
        val message = CorrectionPrompt.userMessage(batch)
        assertTrue(message.contains("\"id\":\"a\""))
        assertTrue(message.contains("نرمفزار"))
        assertTrue(message.contains("\"id\":\"b\""))
        assertTrue(message.contains("دوشوار"))
    }

    @Test
    fun `context is marked reference-only and kept out of the id list`() {
        val batch = CorrectionBatch(
            index = 1,
            segments = listOf(CorrectionInputSegment("a", "متن اصلی")),
            contextBefore = "جمله قبل",
            contextAfter = "جمله بعد"
        )
        val message = CorrectionPrompt.userMessage(batch)
        assertTrue(message.contains("Context BEFORE"))
        assertTrue(message.contains("Context AFTER"))
        assertTrue(message.contains("do NOT correct"))
        // Exactly one id in the whole message.
        val ids = Regex("\"id\":\"([^\"]+)\"").findAll(message).toList()
        assertTrue("context must not contribute ids", ids.size == 1)
    }

    @Test
    fun `user message omits absent context`() {
        val batch = CorrectionBatch(0, listOf(CorrectionInputSegment("a", "متن")))
        val message = CorrectionPrompt.userMessage(batch)
        assertFalse(message.contains("Context BEFORE"))
        assertFalse(message.contains("Context AFTER"))
    }

    @Test
    fun `json encoder escapes quotes and keeps zwnj raw`() {
        val encoded = CorrectionPrompt.quote("می\u200Cگویم \"سلام\"")
        assertTrue(encoded.startsWith("\""))
        assertTrue(encoded.endsWith("\""))
        assertTrue(encoded.contains("\\\""))
        // ZWNJ must be emitted as the real character, never \u200c,
        // or the model reproduces the escape literally.
        assertTrue(encoded.contains('\u200C'))
        assertFalse(encoded.contains("\\u200c"))
    }
}
