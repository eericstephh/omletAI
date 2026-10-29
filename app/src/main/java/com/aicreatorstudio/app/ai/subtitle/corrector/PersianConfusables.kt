package com.aicreatorstudio.app.ai.subtitle.corrector

/**
 * 2026-10-11 — Persian *sound-shape* confusable map for the
 * contextual homophone stage.
 *
 * ## Why this exists (and why it is not a replacement table)
 *
 * Whisper is an acoustic model: it never hears orthography, it
 * hears phonemes and then picks the most likely spelling of what it
 * heard. When the audio is muddled — a noisy room, a fast speaker,
 * overlapping speech — the spelling it picks can be a real-looking
 * but wrong word. The canonical example is «عاشق» (lover) coming
 * out as «آشق»: the model heard the right phoneme sequence but
 * attached the wrong leading glottal vowel because Persian «ع» and
 * «آ» are near-indistinguishable at the start of a word.
 *
 * A naive fix would be a global `replace("آشق", "عاشق")`. That is
 * exactly what the brief forbids, and it would be wrong anyway:
 * there are legitimate tokens that contain the same run.
 *
 * The mechanism here is different and deliberately narrower:
 *
 *   1. This map lists **character-level** confusions only. It never
 *      contains a whole word.
 *   2. [rewriteVariants] *derives* candidate spellings by
 *      re-spelling the input through those character confusions,
 *      bounded to [MAX_VARIANTS] edits. Nothing is hardcoded
 *      per word.
 *   3. A derived spelling only becomes a candidate if the **lexicon
 *      already contains it**. This map cannot invent vocabulary.
 *   4. Whether that candidate is *applied* is decided by
 *      [HomophonePolicy] from sentence evidence — never by the map.
 *
 * So «آشق» → «عاشق» arises because «آ»↔«عا» is a listed confusion
 * AND «عاشق» is a real lexicon word AND the sentence gives evidence.
 * Any of those three failing means the word is left alone.
 *
 * ## Confusions vs. the normalizer
 *
 * [com.aicreatorstudio.app.ai.subtitle.PersianNormalizer] folds the
 * *unambiguous* Arabic/Persian glyph pairs (ي→ی, ك→ک). Those are
 * spelling variants of the same letter and are folded
 * unconditionally. The pairs below are **not** spelling variants —
 * they are different letters that merely sound alike. They are only
 * ever used to *propose* a candidate.
 *
 * ## Licensing
 *
 * Hand-curated, no external data. No dictionary file is bundled
 * beyond the existing [PersianLexicon].
 */
object PersianConfusables {

    /**
     * Character-level sound-alike groups. Every letter inside one
     * string is a plausible mis-hearing of every other letter in
     * that string. `ع` and `آ`/`ا`/`أ` are the highest-value group
     * for Persian ASR because the glottal phonemes collapse under
     * noise; the sibilant and emphatic groups cover the rest.
     */
    /**
     * Character-level 1:1 sound-alike groups. Every letter inside one
     * string is a plausible mis-hearing of every other letter in that
     * string. These are applied as single-character substitutions.
     *
     * Note «ه» deliberately sits in its own group rather than with
     * the glottals: Persian «ه» is a *consonant* /h/ in most
     * positions, whereas «ع»/«آ»/«ا» are vowels/pharyngeals. Merging
     * them produced nonsense derivations.
     */
    private val GROUPS: List<String> = listOf(
        "عآاأإ",    // glottal / alef family — includes آ (U+0622)
        "سصث",      // /s/ sibilants
        "زذضظ",     // /z/ sibilants
        "تط",       // /t/ emphatic
        "حه",       // /h/ pharyngeal vs plain
        "قغخ",      // /q/ vs /gh/ vs /kh/
        "کگ"        // /k/ vs /g/ voicing
    )

    /**
     * 1:2 expansions. These are the confusions where one written
     * letter corresponds to two written letters in the other
     * spelling — the single most important case being Persian
     * word-initial «آ» standing in for «عا».
     *
     * This is exactly the «آشق» → «عاشق» relationship, and it is
     * expressed as a **character** rule. No word pair from the
     * lexicon appears anywhere in this file.
     */
    private val EXPANSIONS: Map<String, List<String>> = mapOf(
        "آ" to listOf("عا", "ا"),   // آشق → عاشق  (the canonical case)
        "ا" to listOf("عا", "آ"),
        "ع" to listOf("آ", "ا"),
        "ه" to listOf("ح")
    )

    /** Per-position variant expansion budget — keeps derivation O(1). */
    private const val MAX_VARIANTS = 48

    /**
     * Longest single-confusion rewrite, in characters. `آ` → `عا`
     * is a 1→2 expansion, so the budget must allow insertion.
     */
    private const val MAX_EXPANSION = 1

    private val MEMBERS: Map<Char, List<String>> by lazy {
        val m = HashMap<Char, MutableList<String>>()
        for (group in GROUPS) {
            for (c in group) {
                // Every member of the group is a replacement for every
                // OTHER member. `map { it.toString() }` is what keeps
                // this a List<String> — without it Kotlin infers a
                // heterogeneous List<Any> and the group itself leaks
                // into the replacement list.
                val others = group.filter { it != c }.map { it.toString() }
                m.getOrPut(c) { mutableListOf() }.addAll(others)
            }
        }
        // A char may appear in two groups (خ is in /q/ and /kh/), so
        // de-duplicate once at the end.
        val out = HashMap<Char, List<String>>(m.size)
        for ((c, v) in m) out[c] = v.distinct()
        out
    }

    /**
     * 1:2 expansions, keyed by the single character being replaced.
     * Kept separate from [MEMBERS] because a replacement longer than
     * one character cannot be expressed as a same-length substring
     * swap in the same way. These are the highest-value confusions
     * («آ» → «عا») so they are applied first, before the cap.
     */
    private val EXPANSION_MAP: Map<Char, List<String>> by lazy {
        val m = HashMap<Char, List<String>>()
        for ((k, v) in EXPANSIONS) {
            if (k.length != 1) continue
            m[k[0]] = v
        }
        m
    }

    /**
     * True when [word] has at least one derived spelling that is a
     * real lexicon word and differs from [word]. Cheap guard used by
     * [SuspiciousWordDetector] rule D so the expensive
     * candidate/validation path is only entered for words that can
     * actually go somewhere.
     *
     * A word that is *already* in the lexicon is never a homophone
     * target — there is nothing to fix. That is checked by the caller
     * (the detector hard-skips lexicon words), and again here.
     */
    fun hasSoundAlikeCandidate(word: String): Boolean {
        if (word.length < 3) return false
        if (PersianLexicon.contains(word)) return false
        for (v in rewriteVariants(word)) {
            if (v == word) continue
            if (PersianLexicon.contains(v)) return true
        }
        return false
    }

    /**
     * Every spelling of [word] reachable by applying 1..2 character
     * confusions, in deterministic order. The original word is
     * **not** included — callers treat it as the implicit KEEP.
     *
     * Cost: at most `16 variants × 16 variants` intermediate strings
     * before the [MAX_VARIANTS] cap, all on short tokens. No
     * allocation happens for words whose letters have no confusions.
     */
    fun rewriteVariants(word: String): List<String> {
        if (word.isEmpty()) return emptyList()
        // A word with no confusable character yields nothing.
        val hasExpansion = word.any { EXPANSION_MAP.containsKey(it) }
        if (!hasExpansion && word.none { MEMBERS.containsKey(it) }) return emptyList()

        val oneEdit = LinkedHashSet<String>(MAX_VARIANTS)
        for (i in word.indices) {
            // 1:2 expansions first — highest value, must beat the cap.
            for (e in EXPANSION_MAP[word[i]].orEmpty()) {
                oneEdit.add(word.substring(0, i) + e + word.substring(i + 1))
                if (oneEdit.size >= MAX_VARIANTS) break
            }
            if (oneEdit.size >= MAX_VARIANTS) break
            for (r in MEMBERS[word[i]].orEmpty()) {
                oneEdit.add(word.substring(0, i) + r + word.substring(i + 1))
                if (oneEdit.size >= MAX_VARIANTS) break
            }
            if (oneEdit.size >= MAX_VARIANTS) break
        }

        // Second edit, applied on top of the first set. Bounded.
        val twoEdit = LinkedHashSet<String>(MAX_VARIANTS)
        for (base in oneEdit) {
            twoEdit.add(base)
        }
        outer@ for (base in oneEdit) {
            for (i in base.indices) {
                val repls = MEMBERS[base[i]] ?: continue
                for (r in repls) {
                    val candidate = base.substring(0, i) + r + base.substring(i + 1)
                    if (candidate.length > word.length + MAX_EXPANSION) continue
                    twoEdit.add(candidate)
                    if (twoEdit.size >= MAX_VARIANTS) break@outer
                }
            }
        }
        twoEdit.remove(word)
        return twoEdit.toList()
    }

    /** Exposed for tests: the confusion group a character belongs to. */
    internal fun replacementsFor(c: Char): List<String> = MEMBERS[c].orEmpty()
}
