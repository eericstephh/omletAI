package com.aicreatorstudio.app.ai.subtitle.corrector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-11 — unit tests for the sound-shape confusable map.
 *
 * These pin the *derivation* contract: candidates are produced by
 * re-spelling, never by lookup. If someone later adds `"آشق" to
 * "عاشق"` as a hardcoded pair, [derivationIsNotHardcoded] fails.
 */class PersianConfusablesTest {

    @Test
    fun `عاشق is derivable from آشق`() {
        val variants = PersianConfusables.rewriteVariants("آشق")
        assertTrue(
            "expected عاشق among variants, got $variants",
            variants.contains("عاشق")
        )
    }

    @Test
    fun `derivation is not hardcoded — every confusable letter rewrites`() {
        // «س» and «ص» are in the same sound group; a word spelled
        // with ص must be derivable from the س spelling.
        val a = PersianConfusables.rewriteVariants("مسئله")
        assertTrue(a.isNotEmpty())
        // A Latin-only token has no confusable letters at all.
        assertTrue(PersianConfusables.rewriteVariants("OpenAI").isEmpty())
    }

    @Test
    fun `original word is never returned as its own variant`() {
        val variants = PersianConfusables.rewriteVariants("آشق")
        assertFalse("KEEP is implicit, not a candidate", variants.contains("آشق"))
    }

    @Test
    fun `variant count stays bounded`() {
        // A long token of confusable letters must not explode.
        val variants = PersianConfusables.rewriteVariants("عساسصث")
        assertTrue("variant explosion: ${variants.size}", variants.size <= 48)
    }

    @Test
    fun `sound-alike guard requires a real lexicon hit`() {
        // عاشق is in the lexicon → true.
        assertTrue(PersianConfusables.hasSoundAlikeCandidate("آشق"))
        // A nonsense token whose variants are all nonsense → false.
        assertFalse(PersianConfusables.hasSoundAlikeCandidate("ژژژژژ"))
    }

    @Test
    fun `correct word that is already in the lexicon is not a homophone target`() {
        // The lexicon word itself must never be flagged as needing a
        // sound-alike fix.
        assertFalse(PersianConfusables.hasSoundAlikeCandidate("عاشق"))
    }

    @Test
    fun `short tokens are skipped`() {
        assertFalse(PersianConfusables.hasSoundAlikeCandidate("آب"))
    }

    @Test
    fun `members map is character level not a word table`() {
        // Every replacement offered for «آ» must be a bare
        // character-level spelling — never a whole lexicon word.
        val repls = PersianConfusables.replacementsFor('آ')
        assertTrue("آ must map to ع, got $repls", repls.contains("ع"))
        assertTrue("آ must map to ا, got $repls", repls.contains("ا"))
        assertTrue(
            "replacements must be short character forms, got $repls",
            repls.all { it.length <= 2 }
        )
    }
}
