package com.aicreatorstudio.app.ai.subtitle

/**
 * PHASE B7 — safe Persian text normaliser.
 *
 * Whisper.cpp occasionally returns Arabic-script glyphs in
 * otherwise Persian text. This object performs only the
 * transformations the brief explicitly authorises:
 *
 *   - Arabic ي (U+064A) → Persian ی (U+06CC)
 *   - Arabic ك (U+0643) → Persian ک (U+06A9)
 *   - Strip U+FEFF (BOM) and U+200B (zero-width space)
 *   - Preserve U+200C (ZWNJ / نیم‌فاصله) verbatim
 *   - Collapse runs of ASCII whitespace to a single space
 *   - Trim leading/trailing whitespace
 *
 * **What this normaliser does NOT do** (and the brief
 * specifically forbids):
 *
 *   - Does not insert punctuation
 *   - Does not translate
 *   - Does not re-order words
 *   - Does not split or join words
 *   - Does not invent, remove, or substitute any token
 *   - Does not change Persian ی (U+06CC) or ک (U+06A9)
 *
 * The output is the input with the four character-fold
 * substitutions and whitespace normalisation applied. This is
 * a 1:1 character-level transform, so the
 * [com.aicreatorstudio.app.ai.TranscribedWord] it is applied
 * to never loses its `id`, `startTimeMs`, `endTimeMs`, or
 * `confidence` — the post-processor simply calls
 * `word.copy(text = normalize(word.text))`.
 *
 * ## Why per-word, not per-segment
 *
 * Per-word normalisation preserves the
 * `TranscribedWord.text ↔ word.startTimeMs/endTimeMs` link
 * the brief calls authoritative. Per-segment normalisation
 * would force the segmenter to re-derive the word list from
 * the segment text, which is exactly the "invent timing" the
 * brief forbids.
 */
object PersianNormalizer {

    /**
     * The Arabic-script ي (U+064A ARABIC LETTER YEH) appears
     * in whisper.cpp output even when the source is Persian.
     * The Persian equivalent is U+06CC ARABIC LETTER FARSI YEH.
     * They look similar but the Persian form is what Persian
     * fonts (Vazirmatn, Sahel, …) shape correctly.
     */
    private const val ARABIC_YEH: Char = 'ي'
    private const val PERSIAN_YEH: Char = 'ی'

    /**
     * The Arabic-script ك (U+0643 ARABIC LETTER KAF) likewise
     * leaks in; the Persian equivalent is U+06A9 ARABIC LETTER
     * KEHEH.
     */
    private const val ARABIC_KAF: Char = 'ك'
    private const val PERSIAN_KEHEH: Char = 'ک'

    /** BOM (zero-width no-break space). Always stripped. */
    private const val BOM: Char = '﻿'

    /** Zero-width space. Stripped. */
    private const val ZWSP: Char = '​'

    /** ZWNJ / نیم‌فاصله. **Preserved** verbatim — the brief
     *  is explicit: do not destroy Persian نیم‌فاصله where
     *  confidently present. */
    private const val ZWNJ: Char = '‌'

    /**
     * Normalise [text] for Persian rendering. The transform
     * is idempotent: `normalize(normalize(x)) == normalize(x)`.
     * A no-op input (already-normalised text) returns the same
     * `String` instance whenever possible (the common case
     * where no character changed).
     *
     * The function is null-safe; `null` returns `null` so
     * callers can use it on nullable `word.text` without an
     * extra null guard.
     */
    fun normalize(text: String?): String? {
        if (text == null) return null
        if (text.isEmpty()) return text

        // Fast path: scan for any character that needs folding.
        // If none is found, return the original instance — no
        // allocation. This is the common case for clean Persian
        // text from a real Whisper run.
        var needsWork = false
        for (i in text.indices) {
            val c = text[i]
            if (c == ARABIC_YEH || c == ARABIC_KAF ||
                c == BOM || c == ZWSP ||
                c == ' ' && i + 1 < text.length && text[i + 1] == ' ' ||
                c == '\t' || c == '\n' || c == '\r' ||
                i == 0 && (c == ' ' || c == '\t' || c == '\n' || c == '\r') ||
                i == text.length - 1 && (c == ' ' || c == '\t' || c == '\n' || c == '\r')
            ) {
                needsWork = true
                break
            }
        }
        if (!needsWork) return text

        // Slow path: build a new String. The `seenAny` flag
        // gates the pendingSpace → ' ' emission so leading
        // whitespace is dropped while inner whitespace runs
        // collapse to exactly one space.
        val sb = StringBuilder(text.length)
        var pendingSpace = false
        var seenAny = false
        for (i in text.indices) {
            val c = text[i]
            when (c) {
                ARABIC_YEH -> {
                    if (pendingSpace && seenAny) {
                        sb.append(' ')
                    }
                    pendingSpace = false
                    seenAny = true
                    sb.append(PERSIAN_YEH)
                }
                ARABIC_KAF -> {
                    if (pendingSpace && seenAny) {
                        sb.append(' ')
                    }
                    pendingSpace = false
                    seenAny = true
                    sb.append(PERSIAN_KEHEH)
                }
                BOM, ZWSP -> {
                    // Drop the zero-width characters. ZWNJ is
                    // preserved below; this case is only BOM
                    // and U+200B.
                }
                ZWNJ -> {
                    if (pendingSpace && seenAny) {
                        sb.append(' ')
                    }
                    pendingSpace = false
                    seenAny = true
                    sb.append(ZWNJ)
                }
                ' ', '\t', '\n', '\r' -> {
                    // Collapse runs of ASCII whitespace. The
                    // pendingSpace flag is the only state we
                    // need; ZWNJ is handled above and clears
                    // the flag. Leading whitespace is dropped
                    // because seenAny gates the flush.
                    if (seenAny) pendingSpace = true
                }
                else -> {
                    if (pendingSpace) {
                        sb.append(' ')
                    }
                    pendingSpace = false
                    seenAny = true
                    sb.append(c)
                }
            }
        }
        // Trailing whitespace is dropped: pendingSpace is
        // intentionally not flushed at the end.
        return sb.toString()
    }

    /**
     * True if [text] contains at least one Persian-script code
     * point. Used by tests and by the silence filter to decide
     * whether a transcript is "Persian-shaped" (and therefore
     * worth keeping despite low confidence).
     *
     * Coverage: U+0600..U+06FF (Arabic / Persian main block),
     * U+0750..U+077F (Arabic Supplement), and the Persian
     * digits U+0660..U+0669.
     */
    fun isPersianScript(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        for (i in text.indices) {
            val c = text[i]
            val code = c.code
            if (code in 0x0600..0x06FF) return true
            if (code in 0x0750..0x077F) return true
        }
        return false
    }
}
