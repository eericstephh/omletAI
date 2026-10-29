package com.aicreatorstudio.app.ai.subtitle.corrector

import com.aicreatorstudio.app.ai.Subtitle
import com.aicreatorstudio.app.ai.TranscribedWord
import com.aicreatorstudio.app.ai.Transcription
import com.aicreatorstudio.app.ai.subtitle.SubtitlePostProcessSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stages 2-6 of the conservative Persian transcript corrector:
 * suspicious detection → candidates → context validation →
 * confidence gate → (applied | flagged | kept).
 *
 * ## Hard conservative guarantees
 *
 *  1. Only words flagged by [SuspiciousWordDetector] are ever
 *     touched — all other tokens pass through byte-for-byte.
 *  2. Replacements come ONLY from the closed candidate list
 *     (or KEEP_ORIGINAL). No word is ever invented.
 *  3. Word ids, timestamps and whisper confidence are preserved;
 *     only `text` can change.
 *  4. Segment text is re-derived by joining the (possibly
 *     corrected) words — never rewritten independently.
 *  5. Every decision — applied, flagged, and kept — is recorded in
 *     [CorrectionResult.records] for the correction log.
 *
 * ## Confidence gates (from [SubtitlePostProcessSettings])
 *
 *   confidence ≥ autoApplyThreshold   → applied
 *   review ≤ confidence < auto        → recorded, NOT applied
 *                                        (flagged for review)
 *   confidence < reviewThreshold      → recorded as kept
 */
object TranscriptCorrector {

    private val validator: ContextValidator = OfflineContextValidator()

    fun correct(
        transcription: Transcription,
        settings: SubtitlePostProcessSettings,
        customDictionary: Set<String>
    ): CorrectionResult {
        if (!settings.correctTypos || transcription.segments.isEmpty()) {
            return CorrectionResult(transcription, emptyList())
        }

        val records = ArrayList<CorrectionRecord>()
        val newSegments = ArrayList<Subtitle>(transcription.segments.size)
        var mutated = false

        for (segment in transcription.segments) {
            if (segment.words.isEmpty()) {
                newSegments.add(segment)
                continue
            }
            val tokens = segment.words.map { it.text }

            // ZWNJ whitelist normalization runs first (stage 1.5) so
            // fixed forms match the lexicon during detection. These
            // are meaning-identical and always applied when the
            // corrector is on — they are normalization, not guessing.
            //
            // 2026-10-20 — the RAW token list is kept alongside the
            // fixed one. The correction log records `original`, and it
            // used to be read off the FIXED list, so the log claimed a
            // word had been "corrected" from a form the model never
            // produced, and `original_text` was identical to
            // `normalized_text` in the persisted JSON — the audit log
            // could never show what the model actually said.
            val rawTokens = ArrayList<TranscribedWord>(segment.words.size)
            val fixedTokens = ArrayList<TranscribedWord>(segment.words.size)
            var zwnjMutated = false
            segment.words.forEachIndexed { i, w ->
                rawTokens.add(w)
                val fixed = PersianLexicon.ZWNJ_FIXES[w.text.trim()]
                if (fixed != null) {
                    zwnjMutated = true
                    fixedTokens.add(w.copy(text = fixed))
                    records.add(
                        CorrectionRecord(
                            original = w.text,
                            corrected = fixed,
                            confidence = 1f,
                            reason = "zwnj_whitelist",
                            applied = true,
                            timestampMs = w.startTimeMs
                        )
                    )
                } else {
                    fixedTokens.add(w)
                }
            }

            val fixedTexts = fixedTokens.map { it.text }
            val suspicious = SuspiciousWordDetector.find(fixedTokens, customDictionary)

            val finalWords = ArrayList<TranscribedWord>(fixedTokens.size)
            finalWords.addAll(fixedTokens)

            for (s in suspicious) {
                // 2026-10-20 — the logged "original" is the RAW token
                // (what the model produced), not the ZWNJ-normalized
                // one. `s.word` comes from the FIXED list, so reading
                // `original` off it made the log claim a correction
                // from a form the model never emitted.
                val original = s.word.text
                val rawOriginal = rawTokens.getOrNull(s.wordIndex)?.text ?: original

                // 2026-10-11 PRESERVATION GATE. Before any candidate is
                // even generated, the policy can veto the word
                // outright: user dictionary, non-Persian/numeric
                // tokens, words that are already correct, deliberate
                // repetition, colloquial register, and tokens with no
                // lexical neighbourhood. A veto is final — the word is
                // recorded as kept and never touched. This is what
                // makes "never silently modify correct words" a
                // structural property rather than a tuning exercise.
                val veto = HomophonePolicy.veto(
                    word = original,
                    sentenceTokens = fixedTexts,
                    index = s.wordIndex,
                    customDictionary = customDictionary
                )
                if (veto != null) {
                    records.add(
                        CorrectionRecord(
                            // 2026-10-20 — `original` is the RAW token
                            // (what the model said); `corrected` is what
                            // the word actually became (the normalized
                            // form it was kept as).
                            original = rawOriginal,
                            corrected = original,
                            confidence = 0f,
                            reason = "$veto · ${s.reasons.joinToString("+")}",
                            applied = false,
                            timestampMs = s.word.startTimeMs
                        )
                    )
                    continue
                }

                val candidates = CorrectionCandidateGenerator.generate(
                    original, customDictionary
                )
                if (candidates.isEmpty()) {
                    records.add(
                        CorrectionRecord(
                            original = rawOriginal,
                            corrected = original,
                            confidence = 0f,
                            reason = "no_candidates:${s.reasons.joinToString("+")}",
                            applied = false,
                            timestampMs = s.word.startTimeMs
                        )
                    )
                    continue
                }
                val decision = validator.select(
                    sentenceTokens = fixedTexts,
                    wordIndex = s.wordIndex,
                    original = original,
                    candidates = candidates,
                    whisperConfidence = s.word.confidence
                )
                val apply = decision.chosen != original &&
                    decision.confidence >= settings.autoApplyThreshold
                val flagged = !apply &&
                    decision.chosen != original &&
                    decision.confidence >= settings.reviewThreshold

                records.add(
                    CorrectionRecord(
                        // 2026-10-20 — the logged "original" is the RAW
                        // token; the decision itself is still made
                        // against the normalized word (`original`).
                        original = rawOriginal,
                        corrected = decision.chosen,
                        confidence = decision.confidence,
                        reason = "${decision.reason} · ${s.reasons.joinToString("+")}",
                        applied = apply,
                        flaggedForReview = flagged,
                        timestampMs = s.word.startTimeMs
                    )
                )
                if (apply) {
                    finalWords[s.wordIndex] = s.word.copy(text = decision.chosen)
                    mutated = true
                }
            }

            val newText = finalWords.joinToString(" ") { it.text }.trim()
            if (zwnjMutated || mutated || newText != segment.text) {
                newSegments.add(segment.copy(text = newText, words = finalWords))
            } else {
                newSegments.add(segment)
            }
        }

        if (!mutated && records.none { it.applied }) {
            return CorrectionResult(transcription, records)
        }
        val allWords = newSegments.flatMap { it.words }
        return CorrectionResult(
            Transcription(segments = newSegments, words = allWords),
            records
        )
    }
}

/**
 * One decision of the corrector — the exact JSON shape the brief
 * requests (`original` / `corrected` / `confidence` / `reason`).
 */
data class CorrectionRecord(
    val original: String,
    val corrected: String,
    val confidence: Float,
    val reason: String,
    val applied: Boolean,
    /** 0.70–0.90 band: a real candidate existed but was not auto-applied. */
    val flaggedForReview: Boolean = false,
    val timestampMs: Long = -1L
)

data class CorrectionResult(
    val transcription: Transcription,
    val records: List<CorrectionRecord>
) {
    val appliedCount: Int get() = records.count { it.applied }
    val flaggedCount: Int get() = records.count { it.flaggedForReview }
}

/** Serializer for the correction log (debug + quality review). */
object CorrectionLogJson {

    fun encode(
        originalText: String,
        normalizedText: String,
        finalText: String,
        records: List<CorrectionRecord>
    ): String {
        val root = JSONObject()
        root.put("original_text", originalText)
        root.put("normalized_text", normalizedText)
        val arr = JSONArray()
        records.forEach { r ->
            arr.put(
                JSONObject().apply {
                    put("original", r.original)
                    put("corrected", r.corrected)
                    // Rounded to 2 decimals — floats like 0.96f are
                    // 0.9599999785… as doubles and would pollute the log.
                    put("confidence", Math.round(r.confidence * 100.0) / 100.0)
                    put("reason", r.reason)
                    put("applied", r.applied)
                    put("flagged", r.flaggedForReview)
                    put("timestamp_ms", r.timestampMs)
                }
            )
        }
        root.put("corrections", arr)
        root.put("final_text", finalText)
        return root.toString(2)
    }
}
