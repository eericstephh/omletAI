package com.aicreatorstudio.app.ai.subtitle.corrector

import com.aicreatorstudio.app.ai.Subtitle
import com.aicreatorstudio.app.ai.TranscribedWord
import com.aicreatorstudio.app.ai.Transcription
import com.aicreatorstudio.app.ai.subtitle.SubtitlePostProcessSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-11 — the preservation / false-positive battery.
 *
 * The brief's hard requirements, one test class each:
 *   §4  contextual correction only when the sentence justifies it
 *   §5  preserve colloquial, names, numbers, technical terms,
 *       repeated words, punctuation, unusual vocabulary
 *   §6  preserve timestamps, segment boundaries, word alignment;
 *       never drop or invent a word
 *
 * Every test here is a *negative* test by design. The interesting
 * claim is not "it can fix a typo" but "it refuses to break a
 * correct sentence".
 */
class TranscriptCorrectorPreservationTest {

    private fun word(
        text: String,
        conf: Float? = null,
        start: Long = 0L,
        dur: Long = 300L
    ): TranscribedWord = TranscribedWord(
        text = text,
        startTimeMs = start,
        endTimeMs = start + dur,
        confidence = conf
    )

    private fun transcriptionOf(vararg words: TranscribedWord): Transcription {
        val w = words.toList()
        return Transcription(
            segments = listOf(
                Subtitle(
                    text = w.joinToString(" ") { it.text },
                    startTimeMs = w.first().startTimeMs,
                    endTimeMs = w.last().endTimeMs,
                    words = w
                )
            ),
            words = w
        )
    }

    private val settings = SubtitlePostProcessSettings(
        normalizePersian = false,
        collapseRepetition = false,
        filterSilence = false,
        resegment = false,
        correctTypos = true
    )

    // ================================================================
    // §4 — contextual correction, evidence-gated
    // ================================================================

    @Test
    fun `آشق is NOT corrected without sentence evidence`() {
        // Bare sentence, no noun slot before the word. Even though
        // عاشق is derivable, there is no evidence the speaker meant
        // it. The word must survive.
        val t = transcriptionOf(word("آشق", conf = 0.4f), word("بود"))
        val res = TranscriptCorrector.correct(t, settings, emptySet())
        assertFalse(
            "must not apply without evidence, got ${res.records}",
            res.records.any { it.applied && it.original == "آشق" }
        )
    }

    @Test
    fun `آشق stays when the context does not open a noun slot`() {
        // «رفتم آشق» — a verb before it, so no noun slot. No apply.
        val t = transcriptionOf(word("رفتم"), word("آشق", conf = 0.4f))
        val res = TranscriptCorrector.correct(t, settings, emptySet())
        assertFalse(
            "verb context must not justify the rewrite, got ${res.records}",
            res.records.any { it.applied && it.original == "آشق" }
        )
    }

    @Test
    fun `no unconditional global replacement for آشق`() {
        // The whole point of §4: the same token in a context that
        // does not justify it must come out unchanged.
        val t = transcriptionOf(word("آشق", conf = 0.9f))
        val res = TranscriptCorrector.correct(t, settings, emptySet())
        assertEquals("آشق", res.transcription.segments[0].text)
    }

    // ================================================================
    // §5 — preservation
    // ================================================================

    @Test
    fun `colloquial verbs are not formalised`() {
        // Spoken register must survive. Note «میرم» is deliberately
        // re-spelled with a نیم‌فاصله by the pre-existing ZWNJ
        // whitelist — that is a spelling fix for the SAME word, not
        // a register change. What must never happen is the word
        // becoming formal Persian (میروم / بدهم / میدانی).
        val t = transcriptionOf(
            word("من"), word("میرم"), word("خونه"), word("بدیم"), word("دونی")
        )
        val res = TranscriptCorrector.correct(t, settings, emptySet())
        val text = res.transcription.segments[0].text
        // Same words, same order, same count.
        assertEquals(5, res.transcription.segments[0].words.size)
        assertTrue("colloquial ِرم lost: $text", text.contains("رم"))
        assertTrue("خونه must stay colloquial: $text", text.contains("خونه"))
        assertTrue("بدیم must stay colloquial: $text", text.contains("بدیم"))
        assertTrue("دونی must stay colloquial: $text", text.contains("دونی"))
        // The formal equivalents must NOT be substituted in.
        assertFalse("formalised: $text", text.contains("میروم"))
        assertFalse("formalised: $text", text.contains("خانه"))
        assertFalse("formalised: $text", text.contains("بدهم"))
    }

    @Test
    fun `deliberate repetition is preserved`() {
        // Emphasis: «بگو بگو», «آره آره». RepetitionFilter handles
        // 3+ runs; the corrector must never touch the 2-run case.
        val t = transcriptionOf(word("بگو"), word("بگو"), word("آره"), word("آره"))
        val res = TranscriptCorrector.correct(t, settings, emptySet())
        assertEquals("بگو بگو آره آره", res.transcription.segments[0].text)
    }

    @Test
    fun `numbers and digits are preserved`() {
        val t = transcriptionOf(
            word("قیمت"), word("۱۲۳"), word("تومان"), word("۲.۵"), word("درصد")
        )
        val res = TranscriptCorrector.correct(t, settings, emptySet())
        assertEquals("قیمت ۱۲۳ تومان ۲.۵ درصد", res.transcription.segments[0].text)
    }

    @Test
    fun `ascii digits are preserved`() {
        val t = transcriptionOf(word("نسخه"), word("2"), word("اومد"))
        val res = TranscriptCorrector.correct(t, settings, emptySet())
        assertEquals("نسخه 2 اومد", res.transcription.segments[0].text)
    }

    @Test
    fun `technical terms and latin brands are preserved`() {
        val t = transcriptionOf(
            word("با"), word("Kotlin"), word("و"), word("Jetpack"), word("Compose")
        )
        val res = TranscriptCorrector.correct(t, settings, emptySet())
        assertEquals("با Kotlin و Jetpack Compose", res.transcription.segments[0].text)
    }

    @Test
    fun `proper nouns from the user dictionary are preserved`() {
        val custom = setOf("آرش", "کویرا", "ایلان")
        val t = transcriptionOf(
            word("آرش"), word("به"), word("کویرا"), word("گفت"), word("ایلان")
        )
        val res = TranscriptCorrector.correct(t, settings, custom)
        assertEquals("آرش به کویرا گفت ایلان", res.transcription.segments[0].text)
    }

    @Test
    fun `unknown proper noun with no neighbour is preserved`() {
        // A name we have never seen. Silence is correct.
        val t = transcriptionOf(word("دکتر"), word("خزرآبادکار"), word("اومد"))
        val res = TranscriptCorrector.correct(t, settings, emptySet())
        assertEquals("دکتر خزرآبادکار اومد", res.transcription.segments[0].text)
    }

    @Test
    fun `punctuation attached to tokens is preserved`() {
        val t = transcriptionOf(
            word("سلام،"), word("چطوری؟"), word("خوبم.")
        )
        val res = TranscriptCorrector.correct(t, settings, emptySet())
        assertEquals("سلام، چطوری؟ خوبم.", res.transcription.segments[0].text)
    }

    @Test
    fun `valid unusual vocabulary is preserved`() {
        // Words the lexicon does not carry but that are real Persian.
        val t = transcriptionOf(
            word("شبنم"), word("روی"), word("گلبرگ"), word("نشست")
        )
        val res = TranscriptCorrector.correct(t, settings, emptySet())
        assertEquals("شبنم روی گلبرگ نشست", res.transcription.segments[0].text)
    }

    @Test
    fun `half-space inside a compound word is preserved`() {
        val t = transcriptionOf(word("کتاب‌های"), word("من"), word("نایاب‌اند"))
        val res = TranscriptCorrector.correct(t, settings, emptySet())
        assertTrue(
            "ZWNJ must survive",
            res.transcription.segments[0].text.contains("کتاب‌های")
        )
    }

    // ================================================================
    // §6 — timestamps, boundaries, alignment
    // ================================================================

    @Test
    fun `word timestamps are preserved exactly on correction`() {
        val w = word("بدیمن", conf = 0.4f, start = 7000L, dur = 450L)
        val t = transcriptionOf(word("انجام", start = 6500L), w)
        val res = TranscriptCorrector.correct(t, settings, emptySet())
        val out = res.transcription.words.last()
        assertEquals(7000L, out.startTimeMs)
        assertEquals(7450L, out.endTimeMs)
        assertEquals(w.id, out.id)
        assertEquals(0.4f, out.confidence!!, 0.0001f)
    }

    @Test
    fun `segment boundaries are preserved`() {
        val t = transcriptionOf(word("بدیمن", start = 100L))
        val res = TranscriptCorrector.correct(t, settings, emptySet())
        assertEquals(100L, res.transcription.segments[0].startTimeMs)
        assertEquals(400L, res.transcription.segments[0].endTimeMs)
    }

    @Test
    fun `word count is never changed — nothing dropped or invented`() {
        val t = transcriptionOf(
            word("ما"), word("میخوایم"), word("این"), word("کار"),
            word("رو"), word("انجام"), word("بدیمن")
        )
        val before = t.segments[0].words.size
        val res = TranscriptCorrector.correct(t, settings, emptySet())
        assertEquals(before, res.transcription.segments[0].words.size)
        assertEquals(before, res.transcription.words.size)
    }

    @Test
    fun `segment text re-derives from words only — no independent rewrite`() {
        val t = transcriptionOf(word("ما"), word("بدیمن"))
        val res = TranscriptCorrector.correct(t, settings, emptySet())
        val derived = res.transcription.segments[0].words.joinToString(" ") { it.text }
        assertEquals(derived, res.transcription.segments[0].text)
    }

    @Test
    fun `multiple segments each keep their own boundaries`() {
        val s1 = Subtitle(
            text = "بدیمن",
            startTimeMs = 0L, endTimeMs = 500L,
            words = listOf(word("بدیمن", start = 0L))
        )
        val s2 = Subtitle(
            text = "هستمم",
            startTimeMs = 900L, endTimeMs = 1400L,
            words = listOf(word("هستمم", start = 900L))
        )
        val t = Transcription(segments = listOf(s1, s2), words = s1.words + s2.words)
        val res = TranscriptCorrector.correct(t, settings, emptySet())
        assertEquals(2, res.transcription.segments.size)
        assertEquals(900L, res.transcription.segments[1].startTimeMs)
        assertEquals(1400L, res.transcription.segments[1].endTimeMs)
    }

    // ================================================================
    // §5 — the word is a lexicon word: never a candidate
    // ================================================================

    @Test
    fun `every lexicon word passes through the policy untouched`() {
        // A representative slice of the lexicon. Whatever the
        // surrounding context, a real word is never rewritten.
        val samples = listOf(
            "کتاب", "عاشق", "خانه", "بازار", "انجام", "شروع", "کمک",
            "ممنون", "خداحافظ", "دیروز", "فردا", "برنامه"
        )
        for (s in samples) {
            val t = transcriptionOf(word("یه"), word(s), word("بود"))
            val res = TranscriptCorrector.correct(t, settings, emptySet())
            assertTrue(
                "lexicon word $s must survive, got ${res.records}",
                res.transcription.words.any { it.text == s }
            )
        }
    }

    @Test
    fun `every non-applied decision carries an auditable reason`() {
        val t = transcriptionOf(word("یه"), word("آشق", conf = 0.4f))
        val res = TranscriptCorrector.correct(t, settings, emptySet())
        val rec = res.records.firstOrNull { it.original == "آشق" }
        assertTrue("آشق must produce a record", rec != null)
        assertFalse("آشق must not auto-apply here", rec!!.applied)
        assertTrue(
            "reason must be auditable, got: ${rec.reason}",
            rec.reason.isNotBlank()
        )
    }

    @Test
    fun `آشق in a noun slot yields a عاشق candidate`() {
        // «یه» opens a noun slot, so the homophone candidate gets the
        // context bonus. Whether it auto-applies depends on the
        // threshold; what we pin here is that the candidate is
        // DERIVED (confusable_rewrite) rather than hardcoded.
        val t = transcriptionOf(word("یه"), word("آشق", conf = 0.4f))
        val res = TranscriptCorrector.correct(t, settings, emptySet())
        val rec = res.records.firstOrNull { it.original == "آشق" }
        assertTrue(
            "expected a عاشق candidate, got: ${rec?.reason} / corrected=${rec?.corrected}",
            rec != null && rec.corrected == "عاشق"
        )
        assertTrue(
            "candidate must be derived: ${rec!!.reason}",
            rec.reason.contains(CorrectionCandidateGenerator.ORIGIN_HOMOPHONE)
        )
    }
}
