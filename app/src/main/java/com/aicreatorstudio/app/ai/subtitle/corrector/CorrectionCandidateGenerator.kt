package com.aicreatorstudio.app.ai.subtitle.corrector

/**
 * Stage 3 — candidate generation for one suspicious word.
 *
 * Only STRUCTURAL repairs are proposed (never rewrites):
 *
 *  1. **COLLAPSE** — drop the trailing doubled letter
 *     (`هستمم` → `هستم`, `بازارر` → `بازار`).
 *  2. **STRIP_SUFFIX** — remove one junk trailing letter when the
 *     stem is a lexicon word (`بدیمن` → `بدیم`).
 *  3. **EDIT1/EDIT2** — lexicon neighbors within edit distance 1-2
 *     that preserve the first letter (Levenshtein, early-exit).
 *  4. **ADD_MI_PREFIX** — the classic dropped-می error
 *     (`دونستم` → `می‌دونستم`) when the joined form is in the
 *     lexicon.
 *  5. **HOMOPHONE** (2026-10-11) — a spelling derived by re-spelling
 *     the input through [PersianConfusables] character confusions
 *     (`آشق` → `عاشق`). Derived, never hardcoded; only accepted when
 *     the result is already a lexicon word.
 *
 * `KEEP_ORIGINAL` is always implicitly available to the validator —
 * the generator never forces a change.
 */
object CorrectionCandidateGenerator {

    data class Candidate(
        val text: String,
        val origin: String
    )

    const val ORIGIN_COLLAPSE = "collapse_repeat"
    const val ORIGIN_STRIP = "strip_suffix"
    const val ORIGIN_EDIT = "edit_distance"
    const val ORIGIN_MI_PREFIX = "add_mi_prefix"
    const val ORIGIN_HOMOPHONE = "confusable_rewrite"

    /** Origins whose proposal is a same-sound re-spelling. */
    val CONTEXTUAL_ORIGINS = setOf(ORIGIN_HOMOPHONE)

    private val SUFFIX_STRIPS = listOf("ن", "م", "ی", "ت", "ش", "د", "ا", "ه")

    fun generate(
        word: String,
        customDictionary: Set<String>
    ): List<Candidate> {
        val out = LinkedHashMap<String, Candidate>()

        fun add(text: String, origin: String) {
            if (text.isBlank() || text == word) return
            if (out.containsKey(text)) return
            // Never propose a "fix" that equals a protected custom
            // word — that would mean the original was right all along.
            if (customDictionary.any { it.equals(text, ignoreCase = true) }) return
            out[text] = Candidate(text, origin)
        }

        // 1. Collapse trailing repeats (strongest, most mechanical).
        if (word.length >= 4 && word.last() == word[word.length - 2]) {
            val collapsed = word.dropLast(1)
            add(collapsed, ORIGIN_COLLAPSE)
            // A triple repeat collapses twice (سالاممم → سالام).
            if (collapsed.length >= 4 && collapsed.last() == collapsed[collapsed.length - 2] &&
                PersianLexicon.contains(collapsed.dropLast(1))
            ) {
                add(collapsed.dropLast(1), ORIGIN_COLLAPSE)
            }
        }

        // 2. Strip one junk trailing letter → known stem.
        if (word.length >= 4) {
            for (suffix in SUFFIX_STRIPS) {
                if (word.endsWith(suffix)) {
                    val stem = word.dropLast(1)
                    if (stem.length >= 2 && PersianLexicon.contains(stem)) {
                        add(stem, ORIGIN_STRIP)
                    }
                }
            }
        }

        // 3. Add the dropped می‌ prefix.
        if (!word.startsWith("می‌")) {
            val joined = "می‌$word"
            if (PersianLexicon.contains(joined)) {
                add(joined, ORIGIN_MI_PREFIX)
            }
        }

        // 4. Edit-distance neighbors from the lexicon (≤2), first
        // letter preserved, closest first. Capped for determinism.
        val neighbors = ArrayList<Pair<String, Int>>(8)
        for (entry in PersianLexicon.WORDS) {
            if (entry.length < 2) continue
            if (entry[0] != word[0]) continue
            val lenDiff = kotlin.math.abs(entry.length - word.length)
            if (lenDiff > 2) continue
            val d = SuspiciousWordDetector.levenshtein(word, entry, 2)
            if (d in 1..2) neighbors.add(entry to d)
        }
        neighbors.sortedBy { it.second }.take(3).forEach { (text, d) ->
            add(text, "$ORIGIN_EDIT$d")
        }

        // 5. Sound-shape rewrites (2026-10-11). Derived from
        //    PersianConfusables, accepted only when the lexicon
        //    already contains the derived spelling. This is what
        //    turns «آشق» into a `عاشق` candidate — the pair is never
        //    written down anywhere as a pair.
        for (variant in PersianConfusables.rewriteVariants(word)) {
            if (PersianLexicon.contains(variant)) {
                add(variant, ORIGIN_HOMOPHONE)
            }
        }

        return out.values.toList()
    }
}
