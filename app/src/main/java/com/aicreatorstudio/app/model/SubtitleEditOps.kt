package com.aicreatorstudio.app.model

import com.aicreatorstudio.app.ai.TranscribedWord
import java.util.UUID

/**
 * Pure, side-effect-free editing operations for AI / SRT subtitle
 * overlays — the CapCut-style *editing workflow* that the generator
 * deliberately does not provide.
 *
 * ## Why this file exists
 *
 * Omelet's subtitle *generation* is complete: whisper.cpp emits real
 * per-token timings ([TranscribedWord]), `SubtitleSegmenter` groups
 * them into readable cues, and `CaptionRenderer` renders those cues
 * identically in preview and export. What was missing is the
 * counterpart every captioning product has: the ability to
 *
 *  * **split** one cue into two,
 *  * **merge** two adjacent cues into one,
 *  * **retime** a cue's boundaries, and
 *  * **nudge a single word's** timing because the recognizer got it
 *    slightly wrong.
 *
 * These four operations are the entire gap. They live here, as pure
 * functions, for three reasons:
 *
 *  1. **Testability.** The project's JVM test stack is
 *     JUnit4 + kotlinx-coroutines-test + org.json only — no
 *     Mockito/Robolectric — and Hilt ViewModels cannot be constructed
 *     off-device. Pure functions in `model/` are the one place logic
 *     can be tested for real. Everything here uses plain `String` /
 *     `Long` / `Int`, never `android.*` geometry, so
 *     `unitTests.isReturnDefaultValues = true` cannot make a test pass
 *     for the wrong reason.
 *  2. **Single ownership.** Splitting/merging timing is subtle enough
 *     that a second implementation (say, one in the ViewModel and one
 *     in the UI) would drift. There is exactly one.
 *  3. **Undo/redo for free.** Because every op returns a new immutable
 *     [TextOverlay] list, the existing `HistoryDelegate` snapshot
 *     mechanism captures it with no extra work.
 *
 * ## The coherence invariant (INV-COHERENCE)
 *
 * [TextOverlay] stores `text` and `words` as **two independent
 * representations of the same content**. The generator writes both
 * consistently; the manual text editor writes only `text`; the karaoke
 * renderer reads only `words` via [KaraokeRanges]. Every operation in
 * this file therefore upholds:
 *
 *  * a split **partitions** the word list (never duplicates a word id),
 *  * a merge **concatenates** in speech order,
 *  * a retime **never touches** `words` at all,
 *  * word text (including any interior **ZWNJ**, U+200C) survives
 *    byte-for-byte,
 *  * word timings stay **monotonic** in speech order, and
 *  * [KaraokeRanges.activeRange] keeps resolving for every surviving
 *    word index.
 *
 * ## Policy boundary
 *
 * These ops are deliberately **policy-free**. They do not re-apply
 * `SubtitleSegmenter`'s readability caps (`MAX_WORDS_PER_SEGMENT`,
 * `MAX_SEGMENT_DURATION_MS`, `MAX_WORD_GAP_MS`). A user who splits a
 * 10-word cue into 1 + 9 words did so on purpose; silently re-merging
 * would destroy their edit. The generator owns readability; the editor
 * owns user intent.
 *
 * ## Failure convention
 *
 * Invalid input is expressed as `null`, never as a thrown exception.
 * Callers (today: the ViewModel) treat `null` as "the edit was
 * rejected" and therefore do not push an undo snapshot and do not
 * persist — so a refused edit leaves no trace in history.
 */
object SubtitleEditOps {

    /**
     * Split [overlay] into two overlays at [wordIndex], where
     * [wordIndex] is the index of the **first word of the second
     * half**.
     *
     * ```
     * words:  [ w0  w1  w2 | w3  w4 ]
     *                 wordIndex = 3
     * left  = [w0 w1 w2]        right = [w3 w4]
     * ```
     *
     * The left overlay keeps [TextOverlay.id] of the original so the
     * timeline selection the user was holding stays valid; the right
     * overlay gets a fresh id. (Reusing one id rather than minting two
     * means the common "split, then keep working on the left part"
     * flow does not visibly jump selection.)
     *
     * Timing is derived from the **words**, not from interpolating the
     * parent window: `left.endTimeMs = words[wordIndex-1].endTimeMs`
     * and `right.startTimeMs = words[wordIndex].startTimeMs`. This
     * keeps the segment trivially faithful to the underlying speech —
     * there is a real silence gap between the halves whenever the
     * recognizer reported one.
     *
     * **Ordering with [retime] matters, and the interaction is
     * intentional.** Split and merge both re-derive the frame from the
     * words, so a parent window that a user had previously widened with
     * [retime] (a stretched tail, say) is *not* reproduced by a
     * split-then-merge round trip: the merged frame collapses back to
     * the word span. Nothing about the caption itself is lost — the
     * word list and the text come back identical — but the padding
     * does. This is the honest trade-off: split/merge are lossless with
     * respect to **speech timing**, while [retime] is the
     * **non-destructive** operation (it never touches words, so it is
     * fully reversible). A caller that wants to move a boundary should
     * use [retime]; a caller that wants to cut a cue in two should use
     * [splitAt] and accept word-derived frames.
     *
     * @param wordIndex index of the first word of the second half;
     *   must be in `1 until overlay.words.size`.
     * @return the `(left, right)` pair, or `null` when the split is
     *   not well-defined (fewer than two words, or an index at either
     *   extreme).
     */
    fun splitAt(overlay: TextOverlay, wordIndex: Int): Pair<TextOverlay, TextOverlay>? {
        val words = overlay.words
        // A split needs at least two words and a boundary strictly
        // between them — splitting at 0 or at size would produce an
        // empty half, which is a delete or a no-op, not a split.
        if (words.size < 2) return null
        if (wordIndex !in 1 until words.size) return null

        val leftWords = words.subList(0, wordIndex)
        val rightWords = words.subList(wordIndex, words.size)

        val left = overlay.copy(
            words = leftWords.toList(),
            text = joinWords(leftWords),
            startTimeMs = leftWords.first().startTimeMs,
            endTimeMs = leftWords.last().endTimeMs
        )
        val right = overlay.copy(
            id = UUID.randomUUID().toString(),
            words = rightWords.toList(),
            text = joinWords(rightWords),
            startTimeMs = rightWords.first().startTimeMs,
            endTimeMs = rightWords.last().endTimeMs
        )
        return left to right
    }

    /**
     * Merge [first] and [second] into a single overlay.
     *
     * The merged overlay keeps [first]'s [TextOverlay.id] (so the
     * selection the user was on survives) and [first]'s styling. Words
     * are concatenated in order; the frame spans
     * `min(start)` .. `max(end)` so an intervening silence is absorbed
     * rather than clipped.
     *
     * Preconditions, all enforced by returning `null`:
     *  * neither overlay may be empty of words (an empty side would
     *    make the merged timing a guess),
     *  * the two must be **adjacent** in time — `first.endTimeMs <=
     *    second.startTimeMs`, or the mirror case when the caller
     *    passes them the other way round.
     *
     * @return the merged overlay, or `null` when the inputs are not a
     *   mergeable pair.
     */
    fun merge(first: TextOverlay, second: TextOverlay): TextOverlay? {
        if (first.words.isEmpty() || second.words.isEmpty()) return null
        if (first.id == second.id) return null

        // Accept either argument order and normalise to speech order.
        val (earlier, later) = if (first.startTimeMs <= second.startTimeMs) {
            first to second
        } else {
            second to first
        }
        // Adjacency: they may touch or have a gap, but they must not
        // overlap. Overlapping cues are an authoring error, and merging
        // them would produce a segment that lies about its coverage.
        if (later.startTimeMs < earlier.endTimeMs) return null

        val mergedWords = earlier.words + later.words
        return earlier.copy(
            words = mergedWords,
            text = joinWords(mergedWords),
            startTimeMs = minOf(earlier.startTimeMs, later.startTimeMs),
            endTimeMs = maxOf(earlier.endTimeMs, later.endTimeMs)
        )
    }

    /**
     * Move [overlay]'s boundaries to `[newStartMs, newEndMs)` **without
     * touching its words**.
     *
     * This is the non-destructive retime: the frame alone changes, so
     * `isVisibleAt` immediately reflects the new window while
     * `activeWordAt` keeps working off the untouched word timings.
     * Crucially this is *reversible* — a user who drags a boundary too
     * far and drags it back recovers the original caption exactly. An
     * implementation that also clamped/lifted words into the new window
     * would destroy that timing permanently.
     *
     * The range is clamped so it can never invert or collapse to zero:
     * a segment is always at least [MIN_DURATION_MS] long and its start
     * never goes negative.
     *
     * @return a copy with the new frame. Never `null` — retiming is
     *   always well-defined.
     */
    fun retime(overlay: TextOverlay, newStartMs: Long, newEndMs: Long): TextOverlay {
        val start = newStartMs.coerceAtLeast(0L)
        val end = newEndMs.coerceAtLeast(start + MIN_DURATION_MS)
        return overlay.copy(startTimeMs = start, endTimeMs = end)
    }

    /**
     * Move a **single word's** boundaries inside [overlay].
     *
     * Recognizers routinely mis-time short words — a clitic attaches to
     * the wrong neighbour, or a one-letter word gets a zero-length
     * window. This lets the user correct that one word without
     * retyping the caption or moving the whole segment.
     *
     * Guard rails, all enforced:
     *  * the word's own range cannot invert and is at least
     *    [MIN_DURATION_MS] long,
     *  * the word may not start before its predecessor's start, and may
     *    not end after its successor's end, so speech order stays
     *    monotonic,
     *  * the word's `text`, `id` and `confidence` are untouched.
     *
     * The parent segment frame is widened if the corrected word now
     * sits outside it — otherwise the word would be permanently
     * invisible for the very range the user just declared.
     *
     * @return the updated overlay, or `null` when [wordIndex] is out of
     *   range.
     */
    fun retimeWord(overlay: TextOverlay, wordIndex: Int, newStartMs: Long, newEndMs: Long): TextOverlay? {
        val words = overlay.words
        if (wordIndex !in words.indices) return null

        val word = words[wordIndex]
        val floor = if (wordIndex == 0) 0L else words[wordIndex - 1].startTimeMs
        val ceiling = if (wordIndex == words.lastIndex) Long.MAX_VALUE else words[wordIndex + 1].endTimeMs

        val start = newStartMs.coerceIn(floor, ceiling)
        val end = newEndMs.coerceIn(start + MIN_DURATION_MS, maxOf(ceiling, start + MIN_DURATION_MS))

        val updatedWords = words.toMutableList().also { list ->
            list[wordIndex] = word.copy(startTimeMs = start, endTimeMs = end)
        }

        return overlay.copy(
            words = updatedWords,
            // Keep the segment frame a superset of its words, or the
            // corrected word would render invisible.
            startTimeMs = minOf(overlay.startTimeMs, start),
            endTimeMs = maxOf(overlay.endTimeMs, end)
        )
    }

    /**
     * Rebuild [overlay]'s `text` from its `words`, restoring
     * INV-COHERENCE after an out-of-band text edit made the two
     * representations disagree.
     *
     * `updateSelectedTextContent` intentionally writes `text` alone (so
     * a user can fix a typo without losing timings), which means a
     * caption's `text` and `words` can legitimately diverge. Calling
     * this re-synchronises them from the authoritative word list.
     *
     * Returns [overlay] unchanged when there are no words — a
     * decorative text overlay has nothing to rebuild from and its
     * `text` is its only truth.
     */
    fun rebuildTextFromWords(overlay: TextOverlay): TextOverlay {
        if (overlay.words.isEmpty()) return overlay
        return overlay.copy(text = joinWords(overlay.words))
    }

    /**
     * The shortest a caption or word may be after an edit. Mirrors the
     * `maxOf(end, start + 1L)` guard already used by
     * `updateSelectedTextDuration`, but as a named constant so the
     * intent is legible and testable.
     */
    const val MIN_DURATION_MS: Long = 1L

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Join words the way the generator does — a single ASCII space.
     *
     * Whitespace convention matters more than it looks: karaoke resolves
     * a word by searching for its text starting at the previous match's
     * end, so rebuilding `text` with a different separator would shift
     * every subsequent character offset and light up the wrong glyph.
     * One space anywhere there are two words, no leading/trailing space.
     */
    private fun joinWords(words: List<TranscribedWord>): String =
        words.joinToString(separator = " ") { it.text }
}
