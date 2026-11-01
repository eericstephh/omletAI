package com.aicreatorstudio.app.ai.subtitle.correction

import com.aicreatorstudio.app.ai.TranscribedWord
import com.aicreatorstudio.app.model.TextOverlay
import java.util.UUID

/**
 * Writes accepted corrections into the overlay list.
 *
 * ## The invariant
 *
 * **Only `text` (and, when unavoidable, the karaoke word spans) may
 * change.** Everything else — `id`, `startTimeMs`, `endTimeMs`,
 * `subtitleSource`, `subtitleStyle`, `subtitleAnchorMs`, template
 * fields, animation, position — is carried over by `copy`. Timings
 * are never recomputed from the corrected text, because the model
 * does not know the audio and any duration it implied would be
 * invented.
 *
 * ## The word-timing problem, and the fallback chosen
 *
 * `TextOverlay.words` drives karaoke highlighting: each entry is a
 * token with a real start/end measured by Whisper. A correction that
 * turns «کار آمد» into «کارآمد» changes the TOKEN COUNT, so the old
 * spans no longer line up 1:1 with the new text — and a stale
 * `words` list would highlight text that is no longer on screen.
 *
 * Three options were available:
 *
 *  1. **Keep the old words untouched.** Wrong: the overlay would
 *     display the corrected text while highlighting different words.
 *  2. **Drop `words` entirely.** Safe but destructive: the caption
 *     loses karaoke for the rest of the project, and the user only
 *     asked for a spelling fix.
 *  3. **Re-time the words inside the segment's own window**
 *     (implemented here): every token gets a slice of
 *     `[firstWord.start, lastWord.end]` proportional to its length.
 *
 * Option 3 is the safe fallback the brief asks for. It never leaves
 * the segment's real speech window, it is monotonic, and it is
 * *reported* ([CorrectionItem.retimesWords] → the review dialog warns
 * about it) so the user is never surprised by approximated karaoke.
 *
 * When the token count is unchanged — the overwhelmingly common case
 * for a spelling/orthography fix — the original per-word timings and
 * ids are preserved exactly and nothing is approximated at all.
 *
 * Pure + JVM-testable.
 */
object CorrectionApplier {

    /**
     * @property overlays the new list (a NEW list; unchanged overlays
     *   are the same instances).
     * @property changed how many overlays had their text replaced.
     * @property retimed how many of those also had their karaoke word
     *   spans redistributed (token count changed).
     */
    data class ApplyResult(
        val overlays: List<TextOverlay>,
        val changed: Int,
        val retimed: Int
    )

    /**
     * Apply [corrections] (overlay id → corrected text).
     *
     * Unknown ids in [corrections] are ignored — the preview is built
     * from a live snapshot, and the user may have deleted a caption
     * while reviewing.
     */
    fun apply(
        overlays: List<TextOverlay>,
        corrections: Map<String, String>
    ): ApplyResult {
        if (corrections.isEmpty()) return ApplyResult(overlays, 0, 0)

        var changed = 0
        var retimed = 0
        val out = ArrayList<TextOverlay>(overlays.size)

        for (overlay in overlays) {
            val correctedRaw = corrections[overlay.id]
            val corrected = correctedRaw?.trim()
            if (corrected.isNullOrEmpty() || corrected == overlay.text.trim()) {
                out.add(overlay)
                continue
            }
            changed++

            if (overlay.words.isEmpty()) {
                // SRT imports and hand-typed captions have no word
                // timings — nothing to reconcile.
                out.add(overlay.copy(text = corrected))
                continue
            }

            val tokens = tokenize(corrected)
            if (tokens.isEmpty()) {
                // Cannot happen (blank was rejected above), but a
                // token list of zero would blank the karaoke list.
                out.add(overlay.copy(text = corrected))
                continue
            }

            if (tokens.size == overlay.words.size) {
                // EXACT PATH — same number of words: keep every
                // timing and every word id, replace only the glyphs.
                val newWords = overlay.words.mapIndexed { i, w -> w.copy(text = tokens[i]) }
                out.add(overlay.copy(text = corrected, words = newWords))
            } else {
                retimed++
                out.add(
                    overlay.copy(
                        text = corrected,
                        words = redistribute(overlay.words, tokens)
                    )
                )
            }
        }

        return ApplyResult(out, changed, retimed)
    }

    /**
     * Build the review list from a finished run.
     *
     * Only segments whose text ACTUALLY changed become rows. A model
     * that returns 40 identical strings is a success, not 40 empty
     * diffs to scroll through — but the count is still reported so
     * "nothing to fix" is distinguishable from "the run produced
     * nothing".
     *
     * Corrected ids that no longer exist (the user deleted a caption
     * during the run) are dropped rather than resurrected.
     */
    fun buildPreview(
        overlays: List<TextOverlay>,
        corrected: List<CorrectedSegment>,
        failedBatches: List<Int> = emptyList(),
        failureMessage: String? = null,
        modelId: String = ""
    ): CorrectionPreview {
        val byId = overlays.associateBy { it.id }
        val items = ArrayList<CorrectionItem>()
        var unchanged = 0

        for (segment in corrected) {
            val overlay = byId[segment.id] ?: continue
            val original = overlay.text.trim()
            val next = segment.text.trim()
            if (next.isEmpty()) continue
            if (next == original) {
                unchanged++
                continue
            }
            items.add(
                CorrectionItem(
                    overlayId = overlay.id,
                    original = original,
                    corrected = next,
                    wordsBefore = tokenize(original).size,
                    wordsAfter = tokenize(next).size
                )
            )
        }

        return CorrectionPreview(
            items = items,
            unchangedCount = unchanged,
            failedBatches = failedBatches,
            failureMessage = failureMessage,
            modelId = modelId
        )
    }

    /**
     * Split [text] into display tokens on whitespace.
     *
     * Deliberately simple: Persian words are separated by spaces, and
     * ZWNJ (U+200C) is word-INTERNAL — it must never split a token.
     * Java's `\s` does not match U+200C, so this is already correct;
     * the explicit predicate documents why.
     */
    fun tokenize(text: String): List<String> =
        text.split(' ', '\t', '\n', '\r', '\u00A0')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /**
     * Give [tokens] spans inside the segment's original speech window,
     * proportional to token length.
     *
     * The window is `[first.startTimeMs, last.endTimeMs]` — a real
     * measured range, not a guess. Ids are reused where an old word
     * exists at the same index (so any word selection that survives a
     * count change keeps pointing at something), and `confidence` is
     * cleared because it described a token that no longer exists.
     */
    fun redistribute(
        words: List<TranscribedWord>,
        tokens: List<String>
    ): List<TranscribedWord> {
        if (tokens.isEmpty()) return words
        if (words.isEmpty()) return emptyList()

        val windowStart = words.first().startTimeMs
        val windowEnd = words.last().endTimeMs
        // Guarantee at least 1 ms per token so the spans stay strictly
        // increasing even for a zero-length or inverted source window.
        val span = (windowEnd - windowStart).coerceAtLeast(tokens.size.toLong())

        val weights = tokens.map { it.length.coerceAtLeast(1) }
        val totalWeight = weights.sum().coerceAtLeast(1)

        val result = ArrayList<TranscribedWord>(tokens.size)
        var accumulated = 0L
        for (i in tokens.indices) {
            val startOffset = span * accumulated / totalWeight
            accumulated += weights[i]
            val endOffset = span * accumulated / totalWeight
            val start = windowStart + startOffset
            // Never let a span collapse: `isActiveAt` uses
            // `start until end`, so end == start would make the word
            // unhittable for the whole frame.
            val end = (windowStart + endOffset).coerceAtLeast(start + 1)
            result.add(
                TranscribedWord(
                    id = words.getOrNull(i)?.id ?: UUID.randomUUID().toString(),
                    text = tokens[i],
                    startTimeMs = start,
                    endTimeMs = end,
                    // The old confidence described a different token.
                    confidence = null
                )
            )
        }
        return result
    }
}
