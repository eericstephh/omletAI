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
 * End-to-end conservative-corrector tests pinning the brief's
 * examples exactly, plus the over-correction guards.
 */
class TranscriptCorrectorTest {

    private fun word(
        text: String,
        conf: Float? = null,
        start: Long = 0L
    ): TranscribedWord = TranscribedWord(
        text = text,
        startTimeMs = start,
        endTimeMs = start + 300,
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

    // ----------------------------------------------------------------
    // The brief's exact examples
    // ----------------------------------------------------------------

    @Test
    fun `انجام بدیمن becomes انجام بدیم with high confidence`() {
        val t = transcriptionOf(
            word("ما"), word("می‌خوایم"), word("این"), word("کار"),
            word("رو"), word("انجام"), word("بدیمن", start = 2100L)
        )
        val res = TranscriptCorrector.correct(t, settings, emptySet())
        val applied = res.records.filter { it.applied }
        assertTrue(
            "expected بدیمن→بدیم applied, got: ${res.records}",
            applied.any { it.original == "بدیمن" && it.corrected == "بدیم" }
        )
        val rec = applied.first { it.original == "بدیمن" }
        assertTrue(
            "confidence ${rec.confidence} must be >=0.90",
            rec.confidence >= 0.90f
        )
        assertFalse(res.transcription.segments[0].text.contains("بدیمن"))
    }

    @Test
    fun `هستمم collapses to هستم`() {
        val t = transcriptionOf(word("من"), word("خسته"), word("هستمم"))
        val res = TranscriptCorrector.correct(t, settings, emptySet())
        val applied = res.records.filter { it.applied }
        assertTrue(
            "expected هستمم→هستم, got ${res.records}",
            applied.any { it.original == "هستمم" && it.corrected == "هستم" }
        )
    }

    @Test
    fun `برمم collapses to برم`() {
        val t = transcriptionOf(word("می‌خوام"), word("برمم"), word("خونه"))
        val res = TranscriptCorrector.correct(t, settings, emptySet())
        assertTrue(
            "got ${res.records}",
            res.records.any { it.applied && it.original == "برمم" && it.corrected == "برم" }
        )
    }

    @Test
    fun `میخوام gains نیم‌فاصله via whitelist`() {
        val t = transcriptionOf(word("من"), word("میخوام"), word("برم"))
        val res = TranscriptCorrector.correct(t, settings, emptySet())
        assertTrue(
            "got ${res.records}",
            res.records.any { it.original == "میخوام" && it.corrected == "می‌خوام" && it.applied }
        )
        assertTrue(res.transcription.segments[0].text.contains("می‌خوام"))
    }

    @Test
    fun `نمیدونم gains نیم‌فاصله`() {
        val t = transcriptionOf(word("من"), word("نمیدونم"))
        val res = TranscriptCorrector.correct(t, settings, emptySet())
        assertTrue(res.transcription.segments[0].text.contains("نمی‌دونم"))
    }

    // ----------------------------------------------------------------
    // Over-correction guards (brief §5, §9)
    // ----------------------------------------------------------------

    @Test
    fun `healthy sentence is untouched`() {
        val t = transcriptionOf(
            word("من"), word("دیروز"), word("رفتم"), word("بازار")
        )
        val res = TranscriptCorrector.correct(t, settings, emptySet())
        assertTrue("no corrections expected", res.records.none { it.applied })
        assertEquals("من دیروز رفتم بازار", res.transcription.segments[0].text)
        assertEquals(t, res.transcription)
    }

    @Test
    fun `بازارر collapses to بازار`() {
        val t = transcriptionOf(word("من"), word("رفتم"), word("بازارر"))
        val res = TranscriptCorrector.correct(t, settings, emptySet())
        assertTrue(
            "got ${res.records}",
            res.records.any { it.applied && it.original == "بازارر" && it.corrected == "بازار" }
        )
    }

    @Test
    fun `custom dictionary words are never corrected`() {
        val custom = setOf("ChatGPT", "کویرا")
        val t = transcriptionOf(
            word("ChatGPT"), word("رو"), word("دوست"), word("دارم"),
            word("کویرا")
        )
        val res = TranscriptCorrector.correct(t, settings, custom)
        assertTrue(
            "ChatGPT must survive",
            res.transcription.words.any { it.text == "ChatGPT" }
        )
        assertTrue(
            "کویرا must survive",
            res.transcription.words.any { it.text == "کویرا" }
        )
    }

    @Test
    fun `latin words are never flagged`() {
        val t = transcriptionOf(word("OpenAI"), word("ساخت"), word("GPT"))
        val res = TranscriptCorrector.correct(t, settings, emptySet())
        assertTrue(res.records.none { it.applied })
    }

    @Test
    fun `unknown word with no close neighbor stays`() {
        // A plausible name — no lexicon neighbor within distance 2.
        val t = transcriptionOf(word("خزرآبادکار"), word("رفت"))
        val res = TranscriptCorrector.correct(t, settings, emptySet())
        assertTrue(res.records.none { it.applied })
    }

    @Test
    fun `nothing below auto threshold may apply`() {
        val t = transcriptionOf(word("خونه"), word("بدیمه"))
        val res = TranscriptCorrector.correct(t, settings, emptySet())
        res.records.filter { !it.applied }.forEach {
            assertTrue(
                "non-applied must be below auto threshold: ${it.confidence}",
                it.confidence < settings.autoApplyThreshold
            )
        }
    }

    @Test
    fun `flagged corrections stay in the review band`() {
        // A suffix-junk word in a neutral context (no verb signal).
        val t = transcriptionOf(word("دیشب"), word("بارون"), word("اومد"), word("زیادم"))
        val res = TranscriptCorrector.correct(t, settings, emptySet())
        res.records.filter { it.flaggedForReview }.forEach { flagged ->
            assertTrue(
                "flagged must be in review band: ${flagged.confidence}",
                flagged.confidence >= settings.reviewThreshold &&
                    flagged.confidence < settings.autoApplyThreshold
            )
            assertFalse(flagged.applied)
        }
    }

    @Test
    fun `timestamps and ids survive correction`() {
        val w = word("بدیمن", conf = 0.4f, start = 5000L)
        val t = transcriptionOf(word("انجام"), w)
        val res = TranscriptCorrector.correct(t, settings, emptySet())
        val corrected = res.transcription.words.first { it.text != "انجام" }
        assertEquals(w.id, corrected.id)
        assertEquals(w.startTimeMs, corrected.startTimeMs)
        assertEquals(w.endTimeMs, corrected.endTimeMs)
        assertEquals(w.confidence, corrected.confidence)
    }

    @Test
    fun `identity settings return transcription bit-for-bit`() {
        val t = transcriptionOf(word("بدیمن"), word("هستمم"))
        val res = TranscriptCorrector.correct(
            t,
            SubtitlePostProcessSettings.Identity,
            emptySet()
        )
        assertEquals(t, res.transcription)
        assertTrue(res.records.isEmpty())
    }

    // ----------------------------------------------------------------
    // Detector + generator units
    // ----------------------------------------------------------------

    @Test
    fun `detector flags the right words only`() {
        val words = listOf(
            word("ChatGPT"), word("هستمم"), word("بدیمن"),
            word("خونه"), word("GPT"), word("کویرا")
        )
        val found = SuspiciousWordDetector.find(words, setOf("کویرا"))
        val flaggedTexts = found.map { it.word.text }
        assertTrue(flaggedTexts.contains("هستمم"))
        assertTrue(flaggedTexts.contains("بدیمن"))
        assertFalse("ChatGPT protected by latin skip", flaggedTexts.contains("ChatGPT"))
        assertFalse("lexicon word not flagged", flaggedTexts.contains("خونه"))
        assertFalse("custom dict not flagged", flaggedTexts.contains("کویرا"))
    }

    @Test
    fun `generator proposes بدیم for بدیمن`() {
        val cands = CorrectionCandidateGenerator.generate("بدیمن", emptySet())
        assertTrue(cands.any { it.text == "بدیم" })
        assertTrue(cands.any { it.text == "بدین" })
    }

    @Test
    fun `generator proposes هستم for هستمم via collapse`() {
        val cands = CorrectionCandidateGenerator.generate("هستمم", emptySet())
        assertTrue(
            cands.any {
                it.text == "هستم" &&
                    it.origin == CorrectionCandidateGenerator.ORIGIN_COLLAPSE
            }
        )
    }

    // ----------------------------------------------------------------
    // Log JSON
    // ----------------------------------------------------------------

    @Test
    fun `correction log json contains the brief shape`() {
        val json = CorrectionLogJson.encode(
            originalText = "ما میخوایم این کار رو انجام بدیمن",
            normalizedText = "ما می‌خوایم این کار رو انجام بدیمن",
            finalText = "ما می‌خوایم این کار رو انجام بدیم",
            records = listOf(
                CorrectionRecord(
                    original = "میخوایم",
                    corrected = "می‌خوایم",
                    confidence = 1f,
                    reason = "zwnj_whitelist",
                    applied = true,
                    timestampMs = 120L
                ),
                CorrectionRecord(
                    original = "بدیمن",
                    corrected = "بدیم",
                    confidence = 0.96f,
                    reason = "collapse_repeat + lexicon=true · suffix_junk",
                    applied = true,
                    timestampMs = 900L
                )
            )
        )
        assertTrue(json.contains("\"original\": \"بدیمن\""))
        assertTrue(json.contains("\"corrected\": \"بدیم\""))
        assertTrue(json.contains("0.96"))
        assertTrue(json.contains("\"final_text\""))
        assertTrue(json.contains("zwnj_whitelist"))
    }
}
