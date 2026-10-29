package com.aicreatorstudio.app.ai.subtitle.corrector

/**
 * Stage 4 — context-aware validation.
 *
 * ## The selector contract (brief §11)
 *
 * The validator is a SELECTOR, not a rewriter. It receives the
 * sentence context, the suspicious word, and a CLOSED candidate
 * list (+ implicit KEEP_ORIGINAL) and must return one of them. It
 * can never invent a word, reorder the sentence, or touch any other
 * token. This interface is deliberately model-agnostic: the shipped
 * implementation is fully offline and deterministic
 * ([OfflineContextValidator]); a future online LLM selector can
 * implement the same interface without touching the pipeline.
 */
interface ContextValidator {

    data class Decision(
        /** Chosen candidate text, or the original word for KEEP. */
        val chosen: String,
        /** 0..1 — the confidence that this choice is right. */
        val confidence: Float,
        /** Human-readable reason chain for the correction log. */
        val reason: String
    )

    /**
     * @param sentenceTokens the full segment's word texts (the
     *   context window) — the validator must NOT mutate them.
     * @param wordIndex index of the suspicious word inside
     *   [sentenceTokens].
     * @param original the suspicious word as whisper emitted it.
     * @param candidates the closed candidate list.
     * @param whisperConfidence whisper's word confidence (null when
     *   the backend did not provide one).
     */
    fun select(
        sentenceTokens: List<String>,
        wordIndex: Int,
        original: String,
        candidates: List<CorrectionCandidateGenerator.Candidate>,
        whisperConfidence: Float?
    ): Decision
}

/**
 * Offline deterministic selector.
 *
 * ## Scoring model (weights sum to 1.0 per component group)
 *
 *   0.45  inLexicon          — the candidate is a real Persian word
 *   0.25  contextFit         — verb-role fit vs the previous token
 *                             (`انجام …` / `رو` / `را` expect a verb;
 *                             a candidate that is a known verb form
 *                             wins here) + KEEP gets a bonus when
 *                             the PREVIOUS word is a proper-noun
 *                             signal (unknown capitalized-like token)
 *   0.15  prefixSimilarity   — structural proximity to the original
 *   0.15  editSimilarity     — 1 - dist/maxLen
 *   +0.05  lowWhisperConf    — whisper itself was unsure (≤0.5)
 *   −0.20  collapsePresent   — when a mechanical collapse candidate
 *                             exists, KEEP becomes unlikely
 *
 * KEEP_ORIGINAL's score anchors on whisper's own confidence: a
 * confident whisper output keeps the seat unless a mechanical
 * collapse or a dominant lexicon candidate beats it.
 *
 * The returned confidence is the winning score clamped to 0..1 —
 * the thresholds in [SubtitlePostProcessSettings] decide whether it
 * auto-applies, gets flagged, or is discarded.
 */
class OfflineContextValidator : ContextValidator {

    override fun select(
        sentenceTokens: List<String>,
        wordIndex: Int,
        original: String,
        candidates: List<CorrectionCandidateGenerator.Candidate>,
        whisperConfidence: Float?
    ): ContextValidator.Decision {
        val prev = sentenceTokens.getOrNull(wordIndex - 1)?.trim().orEmpty()
        val expectsVerb = VERB_EXPECTING_TOKENS.any { prev == it || prev.endsWith(it) }
        val prevUnknown = prev.isNotEmpty() &&
            !PersianLexicon.contains(prev) &&
            prev.all { it.code in 0x0600..0x06FF || it == '‌' }

        var bestScore = -1f
        var best: ContextValidator.Decision? = null
        val hasCollapse = candidates.any {
            it.origin == CorrectionCandidateGenerator.ORIGIN_COLLAPSE
        }

        // KEEP_ORIGINAL baseline.
        val keepScore = run {
            var s = 0f
            s += (whisperConfidence ?: 0.5f) * 0.45f
            if (!expectsVerb) s += 0.15f
            if (prevUnknown) s += 0.20f          // maybe a name we don't know
            if (hasCollapse) s -= 0.20f          // mechanical error wins
            s.coerceIn(0f, 1f)
        }
        bestScore = keepScore
        best = ContextValidator.Decision(
            chosen = original,
            confidence = keepScore,
            reason = "keep_original: whisper_conf=" +
                (whisperConfidence?.toString() ?: "n/a")
        )

        for (cand in candidates) {
            var s = 0f
            s += 0.45f.takeIf { PersianLexicon.contains(cand.text) } ?: 0f
            s += contextFit(cand.text, expectsVerb) * 0.25f
            s += prefixSimilarity(original, cand.text) * 0.15f
            val d = SuspiciousWordDetector.levenshtein(original, cand.text, 3)
            val maxLen = maxOf(original.length, cand.text.length).coerceAtLeast(1)
            s += (1f - d.toFloat() / maxLen) * 0.15f
            if (whisperConfidence != null && whisperConfidence <= 0.5f) s += 0.05f
            // A mechanical trailing-repeat collapse whose result is a
            // real lexicon word is near-certain (هستمم/برمم/بازارر).
            if (cand.origin == CorrectionCandidateGenerator.ORIGIN_COLLAPSE) s += 0.20f
            if (cand.origin == CorrectionCandidateGenerator.ORIGIN_MI_PREFIX) s += 0.05f
            // 2026-10-11 CONTEXTUAL HOMOPHONE. A confusable rewrite is
            // NEVER granted the mechanical bonuses above. It has to
            // earn its place from sentence evidence: the previous
            // token must make a noun/phrase head plausible. Without
            // that evidence the candidate scores below the 0.45
            // in-lexicon floor and can never reach the auto-apply
            // threshold, which is what keeps «آشق» → «عاشق» from
            // becoming an unconditional global replacement.
            if (cand.origin == CorrectionCandidateGenerator.ORIGIN_HOMOPHONE) {
                s += if (contextSupportsNoun(prev)) 0.12f else -0.30f
            }
            s = s.coerceIn(0f, 1f)
            if (s > bestScore) {
                bestScore = s
                best = ContextValidator.Decision(
                    chosen = cand.text,
                    confidence = s,
                    reason = "${cand.origin} + lexicon=${PersianLexicon.contains(cand.text)}" +
                        " + contextFit=${"%.2f".format(contextFit(cand.text, expectsVerb))}"
                )
            }
        }
        return best!!
    }

    /**
     * Sentence evidence for a same-sound rewrite: the token before
     * the candidate must be a determiner, a possessive, a
     * preposition or a discourse marker — i.e. a slot that expects a
     * noun phrase. `مثل عاشق`, `یه عاشق`, `عاشقش`. A verb or an
     * already-complete phrase gives no such support.
     */
    private fun contextSupportsNoun(prev: String): Boolean {
        if (prev.isEmpty()) return false
        if (NOUN_SLOT_MARKERS.contains(prev)) return true
        // A determiner/quantifier immediately before a noun.
        return NOUN_SLOT_MARKERS.any { prev.endsWith(it) && prev.length <= it.length + 2 }
    }

    /**
     * 1.0 when the candidate is a known verb form and the previous
     * token expects a verb (`انجام …`, `رو/را` object markers), 0.6
     * when it's a lexicon verb without the signal, 0.3 for plain
     * lexicon words, 0 for unknown candidates.
     */
    private fun contextFit(candidate: String, expectsVerb: Boolean): Float {
        val isVerbLike = PersianLexicon.contains(candidate) && isVerbForm(candidate)
        return when {
            expectsVerb && isVerbLike -> 1.0f
            expectsVerb && PersianLexicon.contains(candidate) -> 0.6f
            isVerbLike -> 0.6f
            PersianLexicon.contains(candidate) -> 0.3f
            else -> 0f
        }
    }

    /** Common-prefix ratio, 0..1. */
    private fun prefixSimilarity(a: String, b: String): Float {
        var i = 0
        val n = minOf(a.length, b.length)
        while (i < n && a[i] == b[i]) i++
        return i.toFloat() / maxOf(a.length, b.length, 1).coerceAtLeast(1)
    }

    private fun isVerbForm(w: String): Boolean {
        if (!PersianLexicon.contains(w)) return false
        // Heuristic on Persian verb endings for the lexicon's verb set.
        return w.endsWith("م") || w.endsWith("ی") || w.endsWith("د") ||
            w.endsWith("یم") || w.endsWith("ید") || w.endsWith("ند") ||
            w.startsWith("می‌") || w.startsWith("نمی‌") || w.startsWith("ب")
    }

    companion object {
        private val VERB_EXPECTING_TOKENS = listOf(
            "رو", "را", "انجام", "باید", "نباید", "می‌خوام", "می‌خوایم",
            "می‌خواد", "نمی‌خوام", "داره", "داشتم", "قراره", "شروع"
        )

        /**
         * Tokens that open a noun-phrase slot. Used only by the
         * homophone evidence check — a confusable rewrite is
         * justified when the surrounding sentence puts a noun there.
         */
        private val NOUN_SLOT_MARKERS = listOf(
            "یه", "یک", "این", "اون", "آن", "هر", "چه", "مثل", "مثه",
            "مثلِ", "قشنگ", "خیلی", "همه", "هیچ", "چند", "دو", "سه",
            "با", "برای", "از", "به", "بدون", "کنار", "داخل", "روی",
            "دوست", "دوستِ", "آدم", "یکی", "بچه", "اسم", "صدای"
        )
    }
}
