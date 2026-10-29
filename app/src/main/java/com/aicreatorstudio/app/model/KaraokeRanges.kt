package com.aicreatorstudio.app.model

/**
 * 2026-09-19 — pure, JVM-testable range math for karaoke-style
 * active-word highlighting.
 *
 * The overlay's `words` list is the timing authority; the caption
 * `text` may contain irregular whitespace (double spaces,
 * newlines — common in cloud replies and user-edited captions).
 * Splitting on single spaces would shift every token index after
 * a gap and highlight the WRONG word (especially repeats).
 *
 * [activeRange] therefore:
 *  1. tokenizes [text] on whitespace RUNS, recording each token's
 *     exact `[start, end)` range — ZWNJ (U+200C) is not whitespace,
 *     so «می‌شود» stays one token and Persian shaping is untouched;
 *  2. prefers the active word's ORDINAL [wordIndex] (gap-proof);
 *  3. otherwise highlights the NTH occurrence of the word text
 *     (repeats resolve to the right instance);
 *  4. otherwise returns null — a missing highlight is always
 *     better than a wrong one.
 *
 * Ranges are LOGICAL character offsets; the UI's bidi lays them
 * out (RTL or LTR), so the highlight lands on the visually
 * correct word in both directions. No reordering, no case
 * folding, no substring surgery on the words themselves.
 */
object KaraokeRanges {

    /**
     * @param text the caption text as rendered.
     * @param wordTexts the overlay's word texts in speech order.
     * @param wordIndex ordinal index of the active word in
     *   [wordTexts] (-1 when unknown).
     * @return the character range of the active word inside
     *   [text], or null when it cannot be located safely.
     */
    fun activeRange(
        text: String,
        wordTexts: List<String>,
        wordIndex: Int
    ): IntRange? {
        if (text.isEmpty() || wordIndex < 0) return null
        val target = wordTexts.getOrNull(wordIndex)?.trim().orEmpty()
        if (target.isEmpty()) return null

        // Whitespace-run tokens with exact ranges (gap-proof).
        val tokens = ArrayList<Pair<IntRange, String>>()
        var i = 0
        while (i < text.length) {
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length) break
            val start = i
            while (i < text.length && !text[i].isWhitespace()) i++
            tokens.add((start until i) to text.substring(start, i))
        }
        if (tokens.isEmpty()) return null

        // Index-aligned path: the right token, whatever gaps precede it.
        if (wordIndex < tokens.size && tokens[wordIndex].second.trim() == target) {
            return tokens[wordIndex].first
        }

        // Nth-occurrence path: which repeat of this word is active?
        var occurrence = 0
        for (k in 0..wordIndex.coerceAtMost(wordTexts.size - 1)) {
            if (wordTexts[k].trim() == target) occurrence++
        }
        if (occurrence <= 0) return null
        var seen = 0
        for ((range, tok) in tokens) {
            if (tok.trim() == target) {
                seen++
                if (seen == occurrence) return range
            }
        }
        return null
    }
}
