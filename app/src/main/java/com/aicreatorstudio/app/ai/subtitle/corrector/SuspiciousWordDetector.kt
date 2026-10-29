package com.aicreatorstudio.app.ai.subtitle.corrector

import com.aicreatorstudio.app.ai.TranscribedWord

/**
 * Stage 2 of the corrector pipeline — flags words that LOOK like
 * whisper typos without ever claiming they ARE typos.
 *
 * A word is suspicious when at least one rule fires:
 *
 *  - **TRAILING_REPEAT** — the word ends in a doubled letter
 *    (`هستمم`, `برمم`, `بازارر`). Whisper's decoder occasionally
 *    emits one extra final letter.
 *  - **SUFFIX_JUNK** — stripping one trailing Persian letter
 *    (ن/م/ی/ت/ش/د/ا/ه) yields a lexicon word (`بدیمن` → `بدیم`).
 *  - **FUZZY_LOWCONF** — the word is not in any dictionary AND is
 *    within edit distance 2 of a lexicon word AND whisper reported
 *    a confidence below [LOW_CONF] for it. High-confidence unknowns
 *    (names, brands, slang) are intentionally NOT flagged here;
 *    they are protected upstream by the custom dictionary and the
 *    Latin-script skip.
 *
 * Explicitly NOT suspicious (hard skips, in priority order):
 *  1. Latin/ASCII or digit tokens (English words, brands, "ChatGPT").
 *  2. Anything in the user's custom dictionary (highest authority).
 *  3. Anything in the built-in lexicon.
 *  4. Words shorter than 3 characters.
 */
object SuspiciousWordDetector {

    const val LOW_CONF: Float = 0.60f

    /** Reason codes surfaced in the correction log. */
    const val REASON_TRAILING_REPEAT = "trailing_repeat"
    const val REASON_SUFFIX_JUNK = "suffix_junk"
    const val REASON_FUZZY_LOWCONF = "fuzzy_low_conf"
    const val REASON_HOMOPHONE = "homophone_shape"

    data class Suspicious(
        val wordIndex: Int,
        val word: TranscribedWord,
        val reasons: List<String>
    )

    private val SUFFIX_STRIPS = listOf("ن", "م", "ی", "ت", "ش", "د", "ا", "ه")

    private val ARABIC = Regex("[\\u0600-\\u06FF\\u200C]")

    fun find(
        words: List<TranscribedWord>,
        customDictionary: Set<String>
    ): List<Suspicious> {
        val out = ArrayList<Suspicious>()
        for ((index, w) in words.withIndex()) {
            val raw = w.text.trim()
            if (raw.length < 3) continue
            // Hard skip 1: Latin / digits / mixed → brand or English.
            if (raw.any { it.code < 0x0600 }) continue
            // Hard skip 2: user dictionary (protects brands, names,
            // jargon — authority above everything else).
            if (customDictionary.any { it.equals(raw, ignoreCase = true) }) continue
            val word = raw
            // Hard skip 3: known lexicon word (ZWNJ forms included).
            if (PersianLexicon.contains(word)) continue

            val reasons = ArrayList<String>(2)
            // Rule A — trailing doubled letter. Structural defect: the
            // doubled letter makes the token unpronounceable as-is, so it
            // is safe to flag even when whisper is confident.
            if (word.length >= 4 && word[word.length - 1] == word[word.length - 2]) {
                reasons.add(REASON_TRAILING_REPEAT)
            }
            // Rule B — stripping a trailing letter yields a known word.
            if (reasons.isEmpty() && word.length >= 4) {
                for (suffix in SUFFIX_STRIPS) {
                    if (word.endsWith(suffix)) {
                        val stem = word.dropLast(1)
                        if (stem.length >= 2 && PersianLexicon.contains(stem)) {
                            reasons.add(REASON_SUFFIX_JUNK)
                            break
                        }
                    }
                }
            }
            // Rule C (was the only confidence-gated rule) — unknown +
            // fuzzy neighbor + whisper says unsure.
            if (reasons.isEmpty() &&
                (w.confidence == null || w.confidence < LOW_CONF)
            ) {
                if (hasCloseLexiconNeighbor(word, maxDistance = 2)) {
                    reasons.add(REASON_FUZZY_LOWCONF)
                }
            }
            // Rule D — 2026-10-11 CONTEXTUAL HOMOPHONE. A word that is
            // FLUENT (not in the lexicon) but whose *sound-shape*
            // collides with a lexicon entry under the Persian
            // confusable map. This is the «آشق» → «عاشق» class: the
            // doubled ـشقـ run is the tell. The rule only FLAGS; the
            // context validator + the homophone policy decides whether
            // any correction is justified. Confidence-gated for the
            // same conservative reason as rule C.
            if (reasons.isEmpty() &&
                (w.confidence == null || w.confidence < LOW_CONF)
            ) {
                if (PersianConfusables.hasSoundAlikeCandidate(word)) {
                    reasons.add(REASON_HOMOPHONE)
                }
            }
            if (reasons.isNotEmpty()) {
                out.add(Suspicious(index, w, reasons))
            }
        }
        return out
    }

    /**
     * Bounded neighbor probe for rule C: the candidate must share
     * the first letter and have a length within ±2, then exact
     * Levenshtein ≤ [maxDistance]. Cheap and precise enough for a
     * ~1k-entry lexicon.
     */
    internal fun hasCloseLexiconNeighbor(word: String, maxDistance: Int): Boolean {
        for (entry in PersianLexicon.WORDS) {
            if (entry.isEmpty()) continue
            if (entry[0] != word[0]) continue
            val lenDiff = kotlin.math.abs(entry.length - word.length)
            if (lenDiff > maxDistance) continue
            if (levenshtein(word, entry, maxDistance) <= maxDistance) return true
        }
        return false
    }

    /** Levenshtein with early exit once the budget is exceeded. */
    internal fun levenshtein(a: String, b: String, budget: Int): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > budget) return budget + 1
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            var rowMin = curr[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,        // deletion
                    curr[j - 1] + 1,    // insertion
                    prev[j - 1] + cost  // substitution
                )
                if (curr[j] < rowMin) rowMin = curr[j]
            }
            if (rowMin > budget) return budget + 1
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }
}
