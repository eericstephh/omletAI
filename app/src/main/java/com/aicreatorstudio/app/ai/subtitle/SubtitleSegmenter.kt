package com.aicreatorstudio.app.ai.subtitle

import com.aicreatorstudio.app.ai.Subtitle
import com.aicreatorstudio.app.ai.TranscribedWord
import com.aicreatorstudio.app.ai.Transcription
import java.util.UUID

/**
 * PHASE B7 — rebalance segments against readability limits.
 *
 * Whisper's initial grouping (see
 * `WhisperEngine.groupWordsIntoSubtitlesForWhisper`) packs
 * words into segments under hard caps
 * (`MAX_SEGMENT_DURATION_MS = 4_000`,
 * `MAX_WORDS_PER_SEGMENT = 10`,
 * `MAX_WORD_GAP_MS = 1_200`). Those caps produce
 * serviceable captions, but they do not address the
 * "too long for a single caption" or "too short to read"
 * failure modes the brief calls out.
 *
 * This segmenter takes the engine's segments and:
 *
 *   1. **Split** any segment whose reconstructed text is
 *      longer than [maxCharsPerLine] OR whose duration is
 *      longer than [maxSegmentDurationMs]. The split
 *      happens at the nearest word boundary that respects
 *      the cap; the new segments' `startTimeMs` /
 *      `endTimeMs` come from the first / last word they own.
 *      No timestamp is shifted arbitrarily.
 *
 *   2. **Merge** a pair of consecutive segments when the
 *      first one is shorter than [minSegmentDurationMs]
 *      AND the gap to the second is smaller than
 *      [mergeGapMs] AND the merged text would still be
 *      under [maxCharsPerLine]. This handles the
 *      "split-on-tiny-pause" failure mode where a single
 *      phrase gets chopped into 200 ms fragments.
 *
 * Neither operation ever invents a timestamp: every output
 * segment's start/end is a real word's start/end. Every
 * output word is a real input word.
 */
object SubtitleSegmenter {

    /**
     * Whitespace-only word boundary. ZWNJ (U+200C نیم‌فاصله) is a
     * word-INTERNAL joiner, never a boundary — splitting on `\s`
     * keeps «می‌شود» / «می‌خوام» as single tokens.
     */
    private val WORD_SPLIT_REGEX = Regex("\\s+")

    /**
     * Split [text] into word tokens on whitespace runs. ZWNJ is
     * preserved verbatim inside tokens (it is not whitespace), so
     * Persian half-space words never break apart. Empty tokens are
     * dropped.
     */
    fun persianWordTokens(text: String): List<String> =
        text.split(WORD_SPLIT_REGEX).filter { it.isNotEmpty() }

    /**
     * Premiere-style word-count-only pass. Maps every segment
     * through [splitByWordCount] and rebuilds the transcription.
     * Used when the full readability resegment is OFF but the
     * user still asked for N words per caption: the ONLY change
     * is the fixed-size chunking, nothing else (no
     * chars/duration/CPS/pause/merge passes).
     *
     * Returns the input instance when nothing needed chunking.
     */
    fun chunkByWordCount(transcription: Transcription, maxWords: Int): Transcription {
        if (maxWords <= 0 || transcription.segments.isEmpty()) return transcription
        var changed = false
        val out = ArrayList<Subtitle>(transcription.segments.size)
        for (segment in transcription.segments) {
            val parts = splitByWordCount(segment, maxWords)
            if (parts.size != 1 || parts[0] !== segment) changed = true
            out.addAll(parts)
        }
        if (!changed) return transcription
        return Transcription(segments = out, words = out.flatMap { it.words })
    }

    /**
     * Apply the segmenter to [transcription]. Returns a new
     * transcription with the same word list, re-binned into
     * segments that respect the configured readability limits.
     *
     * 2026-09-09 READING-SPEED + PAUSE-AWARE — two new readability
     * signals join the caps:
     *
     *  - [maxReadingCps]: when a segment's text length ÷ duration
     *    exceeds this many characters-per-second, the viewer cannot
     *    keep up — the segment is split at the best word boundary
     *    (each half then re-checked against the other caps). 15 CPS
     *    is the NLE convention for comfortable adult reading.
     *  - [pauseSplitMs]: when the silence gap between two adjacent
     *    words inside a segment exceeds this, the segment is split
     *    exactly at the pause (the speaker finished a thought).
     *    Applied BEFORE the merge pass so the merger can still glue
     *    the tiny fragments back together when they are truly
     *    unreadable alone.
     */
    fun resegment(
        transcription: Transcription,
        maxCharsPerLine: Int,
        maxSegmentDurationMs: Long,
        minSegmentDurationMs: Long,
        mergeGapMs: Long,
        maxReadingCps: Float = 0f,
        pauseSplitMs: Long = 0L,
        maxWordsPerSegment: Int = 0
    ): Transcription {
        if (transcription.segments.isEmpty()) return transcription
        if (maxCharsPerLine <= 0 || maxSegmentDurationMs <= 0L) {
            return transcription
        }

        // Phase 0 (2026-09-09): pause-aware pre-split. Splitting at
        // the speaker's own pauses first gives every later cap check
        // natural cut points and produces captions that end where the
        // sentence actually ends.
        val pauseSplitSegments = ArrayList<Subtitle>(transcription.segments.size)
        for (segment in transcription.segments) {
            pauseSplitSegments.addAll(
                splitAtPauses(segment, pauseSplitMs)
            )
        }

        // Phase 0b (2026-09-15 WORD-COUNT): Premiere-style hard word
        // cap. When the user asks for "3 words" or "5 words" per
        // caption, every segment with more words than the cap is
        // chunked into fixed-size word groups BEFORE the
        // chars/duration caps run. Running it FIRST (right after
        // the pause split) makes the word count the dominant rule —
        // exactly like Premiere's "Words per caption" setting —
        // while the later passes still enforce readability on each
        // chunk. 0 disables the check.
        val wordCappedSegments = ArrayList<Subtitle>(pauseSplitSegments.size)
        for (segment in pauseSplitSegments) {
            wordCappedSegments.addAll(
                splitByWordCount(segment, maxWordsPerSegment)
            )
        }

        // Phase 1: split overly long segments (chars / duration).
        val splitSegments = ArrayList<Subtitle>(wordCappedSegments.size)
        for (segment in wordCappedSegments) {
            val parts = splitSegment(segment, maxCharsPerLine, maxSegmentDurationMs)
            splitSegments.addAll(parts)
        }

        // Phase 1b (2026-09-09): reading-speed split. A segment can
        // satisfy the character/duration caps yet still flash by too
        // fast (e.g. 40 chars in 1 s); split those at the closest
        // boundary that evens out the pace.
        val cpsSegments = ArrayList<Subtitle>(splitSegments.size)
        for (segment in splitSegments) {
            cpsSegments.addAll(splitByReadingSpeed(segment, maxReadingCps))
        }

        // Phase 2: merge too-short segments with their
        // neighbour. We walk left-to-right, accumulating a
        // "merge buffer" segment that we flush when the next
        // segment can't be merged in.
        val merged = ArrayList<Subtitle>(cpsSegments.size)
        var buffer: Subtitle? = null
        for (segment in cpsSegments) {
            val current = buffer
            if (current == null) {
                buffer = segment
                continue
            }
            val currentDuration = current.endTimeMs - current.startTimeMs
            val gap = segment.startTimeMs - current.endTimeMs
            val candidateText = current.text + " " + segment.text
            // 2026-09-07 MERGE DURATION FIX — a merge that only
            // checked text length could still produce a segment
            // LONGER than maxSegmentDurationMs (the caps are
            // supposed to hold after every pass). Also require the
            // merged duration to stay inside the cap.
            val mergedDuration = segment.endTimeMs - current.startTimeMs
            // 2026-09-09 CPS GUARD — the merger must not undo the
            // reading-speed split: a merged caption denser than
            // maxReadingCps is exactly the "flashes by unreadable"
            // failure the CPS pass exists to fix.
            val mergedChars = candidateText.length
            val mergedCps = if (mergedDuration > 0f) {
                mergedChars / (mergedDuration / 1000f)
            } else Float.MAX_VALUE
            // 2026-09-15 WORD-COUNT GUARD — the merger must not undo
            // the Premiere-style word cap: a merged caption with more
            // words than the user's setting is exactly the failure
            // the word-count split exists to fix. `words` is the
            // merged word list only when both sides carry words; for
            // word-less (cloud) segments the guard falls back to the
            // text's whitespace token count.
            val canMerge = currentDuration < minSegmentDurationMs &&
                gap in 0..mergeGapMs &&
                candidateText.length <= maxCharsPerLine &&
                mergedDuration <= maxSegmentDurationMs &&
                (maxReadingCps <= 0f || mergedCps <= maxReadingCps) &&
                (maxWordsPerSegment <= 0 || wordCountOf(current, segment) <= maxWordsPerSegment)
            if (canMerge) {
                buffer = mergeTwo(current, segment)
            } else {
                merged.add(current)
                buffer = segment
            }
        }
        buffer?.let { merged.add(it) }

        if (merged.size == transcription.segments.size &&
            merged.zip(transcription.segments).all { (a, b) -> a === b }
        ) {
            return transcription
        }
        val newWords = merged.flatMap { it.words }
        return Transcription(segments = merged, words = newWords)
    }

    /**
     * 2026-09-09 PAUSE-AWARE — split [segment] at every inter-word
     * gap longer than [pauseMs]. The gap between word[i].endTimeMs
     * and word[i+1].startTimeMs is the speaker's real pause; a cut
     * there never lands mid-thought. No-op when [pauseMs] <= 0 or
     * no gap crosses the threshold (returns the original instance).
     */
    private fun splitAtPauses(segment: Subtitle, pauseMs: Long): List<Subtitle> {
        if (pauseMs <= 0L) return listOf(segment)
        val words = segment.words
        if (words.size < 2) return listOf(segment)
        val bins = ArrayList<MutableList<TranscribedWord>>()
        var current = mutableListOf<TranscribedWord>()
        for (i in words.indices) {
            current.add(words[i])
            if (i < words.size - 1) {
                val gap = words[i + 1].startTimeMs - words[i].endTimeMs
                if (gap >= pauseMs) {
                    bins.add(current)
                    current = mutableListOf()
                }
            }
        }
        if (current.isNotEmpty()) bins.add(current)
        if (bins.size <= 1) return listOf(segment)
        return bins.map { binToSubtitle(it) }
    }

    /**
     * 2026-09-15 WORD-COUNT — Premiere-style "words per caption".
     * Splits [segment] into consecutive chunks of at most
     * [maxWords] words each. The chunks are cut in speech order at
     * the fixed size the user asked for (e.g. 3 or 5 words), so the
     * caption cadence is uniform — the behaviour the brief describes
     * ("مثلا ۳ کلمه یا ۵ کلمه مثل پریمیر"). No-op when
     * [maxWords] <= 0 or the segment is already within the cap.
     *
     * Word path: chunks own real per-word timestamps (first word's
     * start / last word's end) — no timing is invented.
     *
     * Word-less path (the cloud worker can return segments with
     * text only): the TEXT is tokenized on whitespace runs — ZWNJ
     * stays inside tokens so «می‌شود» never splits — and the
     * segment's own [startTimeMs, endTimeMs] window is divided
     * proportionally (by chunk character length) among the
     * chunks. Every chunk stays strictly inside the original
     * window with end > start, so downstream gates (mapper,
     * timeline, SRT export) hold unchanged.
     */
    private fun splitByWordCount(segment: Subtitle, maxWords: Int): List<Subtitle> {
        if (maxWords <= 0) return listOf(segment)
        val words = segment.words
        if (words.isNotEmpty()) {
            if (words.size <= maxWords) return listOf(segment)
            return words.chunked(maxWords).map { binToSubtitle(it) }
        }
        return splitWordlessByWordCount(segment, maxWords)
    }

    /**
     * Chunk a word-less [segment] (text only, no per-word timings)
     * into N-word text groups with proportional time slices.
     * Returns the original instance when the text already fits the
     * cap. Tokenization is whitespace-only: ZWNJ (U+200C) is
     * preserved inside tokens.
     */
    private fun splitWordlessByWordCount(segment: Subtitle, maxWords: Int): List<Subtitle> {
        val tokens = persianWordTokens(segment.text)
        if (tokens.size <= maxWords) return listOf(segment)
        val chunks = tokens.chunked(maxWords)
        val segStart = segment.startTimeMs
        // Guarantee at least 1 ms per chunk even for degenerate
        // zero-length windows.
        val segEnd = segment.endTimeMs.coerceAtLeast(segStart + chunks.size.toLong())
        val totalDur = (segEnd - segStart).coerceAtLeast(chunks.size.toLong())
        // Weight by character length so longer chunks own a
        // proportionally longer slice (even reading pace).
        val weights = chunks.map { chunk ->
            chunk.sumOf { it.length }.toLong().coerceAtLeast(1L)
        }
        val totalWeight = weights.sum().coerceAtLeast(1L)
        val out = ArrayList<Subtitle>(chunks.size)
        var cursor = segStart
        chunks.forEachIndexed { index, chunk ->
            val isLast = index == chunks.size - 1
            val targetEnd = if (isLast) segEnd
            else segStart + (totalDur * weights.take(index + 1).sum() / totalWeight)
            // Monotonic progress inside the window: end is at
            // least 1 ms after the chunk start and never past the
            // segment end.
            val end = targetEnd.coerceIn((cursor + 1L).coerceAtMost(segEnd), segEnd)
            val start = cursor.coerceAtMost((end - 1L).coerceAtLeast(segStart))
            out.add(
                Subtitle(
                    id = UUID.randomUUID().toString(),
                    text = chunk.joinToString(separator = " "),
                    startTimeMs = start,
                    endTimeMs = end,
                    words = emptyList(),
                    confidence = segment.confidence
                )
            )
            cursor = end
        }
        return out
    }

    /**
     * 2026-09-15 WORD-COUNT — merged word count for the merge guard.
     * Uses the real per-word lists when both sides carry them;
     * otherwise estimates from the concatenated text's whitespace
     * tokens via [persianWordTokens] (cloud segments carry text
     * only). The split is whitespace-only so ZWNJ never counts as
     * a boundary («می‌شود» is one word).
     */
    private fun wordCountOf(a: Subtitle, b: Subtitle): Int {
        val totalWords = a.words.size + b.words.size
        if (totalWords > 0) return totalWords
        return persianWordTokens(a.text + " " + b.text).size
    }

    /**
     * 2026-09-09 READING SPEED — split [segment] when its text is
     * too dense for its duration (chars/sec > [maxCps]). The split
     * point is chosen to balance the two halves' CPS as closely as
     * possible (best word boundary). No-op when [maxCps] <= 0 or the
     * segment is already readable (or too short to split further).
     */
    private fun splitByReadingSpeed(segment: Subtitle, maxCps: Float): List<Subtitle> {
        if (maxCps <= 0f) return listOf(segment)
        val words = segment.words
        if (words.size < 2) return listOf(segment)
        val durationSec = (segment.endTimeMs - segment.startTimeMs) / 1000f
        if (durationSec <= 0f) return listOf(segment)
        val cps = segment.text.length / durationSec
        if (cps <= maxCps) return listOf(segment)

        // Find the boundary index (split BEFORE index k) minimizing
        // max(cpsLeft, cpsRight) — the most balanced cut.
        var bestK = -1
        var bestWorstCps = Float.MAX_VALUE
        for (k in 1 until words.size) {
            val left = words.subList(0, k)
            val right = words.subList(k, words.size)
            val leftText = left.joinToString(" ") { it.text }.length
            val rightText = right.joinToString(" ") { it.text }.length
            val leftDur = (left.last().endTimeMs - left.first().startTimeMs) / 1000f
            val rightDur = (right.last().endTimeMs - right.first().startTimeMs) / 1000f
            if (leftDur < 0.2f || rightDur < 0.2f) continue // avoid degenerate cuts
            val worst = maxOf(leftText / leftDur, rightText / rightDur)
            if (worst < bestWorstCps) {
                bestWorstCps = worst
                bestK = k
            }
        }
        if (bestK <= 0) return listOf(segment)
        return listOf(
            binToSubtitle(words.subList(0, bestK)),
            binToSubtitle(words.subList(bestK, words.size))
        )
    }

    /**
     * Split a single [segment] into 1..n parts such that each
     * part satisfies both `text.length <= maxChars` and
     * `endTimeMs - startTimeMs <= maxDurationMs`. Words
     * belong to exactly one output part. If the segment
     * already satisfies both caps it is returned as a
     * single-element list (the original instance).
     */
    private fun splitSegment(
        segment: Subtitle,
        maxChars: Int,
        maxDurationMs: Long
    ): List<Subtitle> {
        val words = segment.words
        if (words.isEmpty()) return listOf(segment)
        if (words.size == 1) {
            // Single-word segment: only split if the duration
            // itself is over the cap. We can't shrink a single
            // word's duration; the segment is kept as-is.
            return listOf(segment)
        }

        // Greedy bin-packing. Walk the words, accumulate into
        // the current bin, and flush whenever adding the next
        // word would push us over either cap.
        val bins = ArrayList<MutableList<TranscribedWord>>()
        var currentBin = mutableListOf<TranscribedWord>()
        var currentTextLen = 0
        for (w in words) {
            val wText = w.text
            val wLen = wText.length
            val wouldExceedChars = currentTextLen > 0 &&
                currentTextLen + 1 + wLen > maxChars
            val currentDuration = if (currentBin.isEmpty()) 0L
                else w.endTimeMs - currentBin.first().startTimeMs
            val wouldExceedDuration = currentBin.isNotEmpty() &&
                currentDuration > maxDurationMs
            if (currentBin.isNotEmpty() && (wouldExceedChars || wouldExceedDuration)) {
                bins.add(currentBin)
                currentBin = mutableListOf()
                currentTextLen = 0
            }
            currentBin.add(w)
            currentTextLen += if (currentTextLen == 0) wLen else 1 + wLen
        }
        if (currentBin.isNotEmpty()) bins.add(currentBin)

        if (bins.size == 1) return listOf(segment)
        return bins.map { binToSubtitle(it) }
    }

    /**
     * Materialise a fresh [Subtitle] from a bin of
     * [TranscribedWord]s. The first word's start / last
     * word's end set the segment timing; the text is the
     * space-joined words. The id is a fresh UUID because
     * the segment's word set has changed.
     */
    private fun binToSubtitle(words: List<TranscribedWord>): Subtitle {
        require(words.isNotEmpty()) { "binToSubtitle requires a non-empty bin" }
        val text = words.joinToString(separator = " ") { it.text }.trim()
        val confs = words.mapNotNull { it.confidence }
        val avgConfidence: Float? = if (confs.isEmpty()) null else confs.average().toFloat()
        return Subtitle(
            id = UUID.randomUUID().toString(),
            text = text,
            startTimeMs = words.first().startTimeMs,
            endTimeMs = words.last().endTimeMs,
            words = words.toList(),
            confidence = avgConfidence
        )
    }

    /**
     * Merge two consecutive segments into one. The merged
     * segment inherits [a]'s `id` (the timeline tracks the
     * earlier segment's id) and concatenates the words in
     * speech order. Timing is the first word's start and
     * the last word's end — no stretching.
     */
    private fun mergeTwo(a: Subtitle, b: Subtitle): Subtitle {
        val mergedWords = a.words + b.words
        val confs = mergedWords.mapNotNull { it.confidence }
        val avgConfidence: Float? = if (confs.isEmpty()) null else confs.average().toFloat()
        val text = (a.text + " " + b.text).trim()
        return Subtitle(
            id = a.id,
            text = text,
            startTimeMs = a.startTimeMs,
            endTimeMs = b.endTimeMs,
            words = mergedWords,
            confidence = avgConfidence
        )
    }
}
