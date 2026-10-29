package com.aicreatorstudio.app.ai.subtitle.corrector

/**
 * 2026-10-11 — preservation policy for the contextual homophone
 * stage. This is a **veto** list, not a correction list.
 *
 * ## The problem it solves
 *
 * [PersianConfusables] can legitimately derive `عاشق` from `آشق`.
 * But the same derivation machinery will happily propose nonsense in
 * sentences that were already correct. The brief's hardest
 * requirement is §5 — *preserve colloquial Persian, names, numbers,
 * technical terms, repeated words, punctuation and valid unusual
 * vocabulary*. A corrector that only knows "is the result a
 * lexicon word" fails that, because colloquial Persian is full of
 * words that are *not* in a formal lexicon yet are exactly what the
 * speaker meant.
 *
 * ## Design: every check is a hard stop
 *
 * [veto] returns a reason string the moment any preservation signal
 * fires. A single veto means the word is left byte-for-byte
 * untouched — no candidate is even generated. The checks are
 * ordered cheapest-and-most-certain first.
 *
 * ## Why these particular signals
 *
 *  - **Colloquial markers.** Persian spoken forms systematically
 *    drop `/n/`, `/d/`, and the `می` prefix (`میخوام`, `بدیم`,
 *    `برم`). A word carrying a colloquial suffix is the speaker's
 *    register choice; "correcting" it to the formal equivalent is
 *    exactly the meaning change the brief forbids.
 *  - **Repeated words.** A word identical to its neighbour is far
 *    more often deliberate emphasis (`بگو بگو`, `آره آره`) than a
 *    doubled-letter ASR artefact. Only the trailing-letter repeat
 *    (inside one token) is treated as a defect.
 *  - **Numeric / Latin / mixed tokens.** Numbers, brands, CLI flags
 *    and product names are not Persian orthography and must never
 *    be folded through a Persian confusion map.
 *  - **Unknown-and-not-close.** If the token shares no lexical
 *    neighbourhood at all, it is a name, a loanword or a slang term
 *    we have no evidence about. Silence is the correct output.
 */
object HomophonePolicy {

    const val REASON_ALREADY_CORRECT = "policy:already_lexicon_word"
    const val REASON_COLLOQUIAL = "policy:colloquial_register"
    const val REASON_REPEATED = "policy:deliberate_repetition"
    const val REASON_NON_PERSIAN = "policy:non_persian_token"
    const val REASON_NUMERIC = "policy:numeric_token"
    const val REASON_NEO = "policy:neo_final"
    const val REASON_NO_NEIGHBOUR = "policy:no_lexical_neighbour"
    const val REASON_CUSTOM_DICT = "policy:user_dictionary"

    /**
     * Persian colloquial *endings*. A token carrying one of these is
     * a spoken-register form rather than a mis-spelling.
     *
     * These are endings, not substrings: `بدیم`, `میرم`, `دونی`,
     * `میکنم`. Matching them anywhere in the token (as an earlier
     * revision did with «می») vetoed most of the language.
     */
    private val COLLOQUIAL_SUFFIXES = listOf(
        "یم", "ید", "ن", "ه", "م", "ی", "ا", "ش", "ت"
    )

    /**
     * Stems whose presence before a suffix marks the token as a
     * spoken verb form rather than a formal word. `می`/`نمی` prefixes
     * and the common dropped-consonant stems.
     */
    private val COLLOQUIAL_PREFIXES = listOf(
        "می‌", "نمی‌", "می", "نمی"
    )

    private val COLLOQUIAL_STEMS = listOf(
        "خوا", "دون", "تون", "گم", "گه", "گی", "رم", "ره", "ری",
        "دم", "دی", "ده", "دن", "کش", "کن", "بین", "گیر", "ذار",
        "زار", "شه", "شن", "بیا", "برو"
    )

    /**
     * Returns null when [word] may be considered for a contextual
     * correction, or a reason string when it must be left alone.
     *
     * @param sentenceTokens the whole segment, for the repetition
     *   check. Not mutated.
     * @param index position of [word] inside [sentenceTokens].
     */
    fun veto(
        word: String,
        sentenceTokens: List<String>,
        index: Int,
        customDictionary: Set<String>
    ): String? {
        if (word.isEmpty()) return REASON_NON_PERSIAN

        // 1. User dictionary is the highest authority in the system.
        if (customDictionary.any { it.equals(word, ignoreCase = true) }) {
            return REASON_CUSTOM_DICT
        }
        // 2. A token carrying digits, Latin letters or punctuation is
        //    outside Persian orthography entirely.
        if (word.any { !it.isPersianLetter() }) {
            return if (word.any { it.isDigit() }) REASON_NUMERIC else REASON_NON_PERSIAN
        }
        // 3. Already a real word → nothing to correct. This is the
        //    single most important guard: it makes "do not silently
        //    modify correct words" structural rather than heuristic.
        if (PersianLexicon.contains(word)) return REASON_ALREADY_CORRECT
        // 4. STRUCTURAL DEFECT — outranks every register heuristic
        //    below. A token ending in a doubled letter (هستمم، برمم،
        //    بازارر) is a decoder artefact, not a spoken form: no
        //    colloquial Persian ending is written by repeating the
        //    final letter. Letting the colloquial check veto these
        //    would break corrections that already worked.
        if (hasTrailingDoubledLetter(word)) return null
        // 5. Deliberate repetition — same token as a neighbour.
        if (isDeliberateRepetition(word, sentenceTokens, index)) {
            return REASON_REPEATED
        }
        // 6. Colloquial / spoken register.
        if (isColloquial(word)) return REASON_COLLOQUIAL
        // 7. No lexical neighbourhood at all → a name, loanword or
        //    slang term. We have no evidence; do not guess.
        if (!SuspiciousWordDetector.hasCloseLexiconNeighbor(word, maxDistance = 2)) {
            return REASON_NO_NEIGHBOUR
        }
        return null
    }

    /**
     * True when the token ends in a doubled letter — the mechanical
     * defect [SuspiciousWordDetector] rule A detects. Kept here so
     * the policy and the detector cannot disagree about what counts
     * as structural.
     */
    private fun hasTrailingDoubledLetter(word: String): Boolean =
        word.length >= 4 && word[word.length - 1] == word[word.length - 2]

    /**
     * True when the token equals an adjacent token (within one
     * position). Two identical words in a row is emphasis in speech;
     * treating any of them as an ASR artefact would erase meaning.
     */
    private fun isDeliberateRepetition(
        word: String,
        sentenceTokens: List<String>,
        index: Int
    ): Boolean {
        val prev = sentenceTokens.getOrNull(index - 1)?.trim()
        val next = sentenceTokens.getOrNull(index + 1)?.trim()
        return prev == word || next == word
    }

    /**
     * Spoken-register detector.
     *
     * A token is colloquial when EITHER:
     *   - it carries a `می`/`نمی` prefix on a known colloquial stem
     *     (`میدونم`, `نمیتونم`), or
     *   - its stem is a known colloquial stem with a dropped ending
     *     (`بدیم` from `بدن`, `میرم` from `میروم`).
     *
     * The earlier revision also vetoed anything *containing* «می»,
     * which caught `نمیدانم`-shaped formal words too. Restricting
     * the check to prefixes fixes that over-reach.
     */
    private fun isColloquial(word: String): Boolean {
        if (PersianLexicon.contains(word)) return false

        // (a) می‌/نمی prefix over a colloquial stem.
        for (prefix in COLLOQUIAL_PREFIXES) {
            if (!word.startsWith(prefix)) continue
            val rest = word.removePrefix(prefix)
            if (rest.length >= 2 && COLLOQUIAL_STEMS.any { rest.startsWith(it) }) {
                return true
            }
            // «می» + a valid lexicon verb stem is a normal conjugation.
            if (rest.length >= 2 && PersianLexicon.contains(rest)) return true
        }

        // (b) Colloquial stem + dropped ending, where the joined form
        //     is NOT a lexicon word (lexicon hits were short-circuited
        //     by the "already correct" rule upstream).
        for (stem in COLLOQUIAL_STEMS) {
            if (!word.startsWith(stem)) continue
            val remainder = word.removePrefix(stem)
            if (remainder.isNotEmpty() && remainder.length <= 2 &&
                COLLOQUIAL_SUFFIXES.any { remainder == it }
            ) {
                return true
            }
        }

        // (c) Formal stem + a spoken drop of the final «د»/«ن»: a
        //     token that becomes a lexicon word only when a consonant
        //     is restored is a spoken form, not a typo.
        for (suffix in listOf("ه", "ن", "م", "ی", "یم", "ید", "ش")) {
            if (!word.endsWith(suffix)) continue
            val stem = word.dropLast(suffix.length)
            if (stem.length >= 3 && PersianLexicon.contains(stem + "د")) return true
            if (stem.length >= 3 && PersianLexicon.contains(stem + "ن")) return true
        }
        return false
    }

    /** A character that belongs to the Persian/Arabic letter block. */
    private fun Char.isPersianLetter(): Boolean {
        val c = code
        return c in 0x0620..0x064A || c in 0x066E..0x06D3 || c == 0x200C.toInt()
    }
}
