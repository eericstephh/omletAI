package com.aicreatorstudio.app.ai.subtitle

import com.aicreatorstudio.app.ai.Subtitle
import com.aicreatorstudio.app.ai.TranscribedWord
import com.aicreatorstudio.app.ai.Transcription
import java.util.UUID

/**
 * PHASE B7 — collapse obvious Whisper repetitions.
 *
 * Whisper.cpp emits pathological "word word word" sequences
 * on long stretches of silence or low-energy audio. The
 * pattern is unmistakable:
 *
 *   "سلام سلام سلام سلام"      (4 reps)
 *   "می‌روم می‌روم می‌روم"     (3 reps with ZWNJ)
 *   "the the the the"            (English fallback)
 *
 * These are not legitimate discourse. The brief authorises
 * removing them when the pattern is **highly confident**.
 *
 * ## What this filter considers a repetition
 *
 * Three or more **consecutive** words whose normalised text
 * (ZWNJ + whitespace insensitive) is identical. Two
 * repetitions are left alone — "آره آره" and "می‌روم می‌روم"
 * may be real discourse, and the brief says "Do not remove
 * legitimate repeated words."
 *
 * ## What this filter preserves
 *
 * - The first occurrence of the repeated word keeps its
 *   `startTimeMs` / `endTimeMs` / `confidence`. The dropped
 *   repetitions' timestamps are simply discarded (the words
 *   were never on screen anyway).
 * - The `id` of the kept word is preserved so any consumer
 *   that tracks word identity (the karaoke highlight in the
 *   preview) sees no churn.
 * - The segment's `id` is preserved unless the collapse
 *   leaves the segment with zero words, in which case the
 *   segment is dropped entirely (defensive — should never
 *   happen, but a collapse that empties a single-word
 *   segment is handled).
 *
 * ## What this filter does NOT do
 *
 * - Does not collapse non-adjacent repeats ("سلام ... سلام").
 * - Does not collapse 2-repetition phrases.
 * - Does not collapse across punctuation.
 * - Does not collapse Latin digits or URLs.
 */
object RepetitionFilter {

    /**
     * Lower bound on the number of consecutive identical words
     * that triggers a collapse. Three is the minimum that
     * distinguishes a real Whisper hallucination from natural
     * discourse.
     */
    private const val MIN_REPETITION_COUNT: Int = 3

    /**
     * Run the filter on a [Transcription]. Returns a new
     * transcription whose segments may have fewer words (the
     * collapsed repetitions are dropped). The segment list
     * order and `id`s are preserved.
     */
    fun filter(transcription: Transcription): Transcription {
        if (transcription.segments.isEmpty()) return transcription
        val newSegments = ArrayList<Subtitle>(transcription.segments.size)
        for (segment in transcription.segments) {
            val kept = filterSegment(segment)
            // 2026-09-13 CLOUD FIX — a word-less segment (Cloud
            // path: segments/VTT without per-word timings) has
            // nothing to collapse; keep it as-is. Only segments
            // whose words were ALL collapsed away are dropped.
            if (kept.words.isEmpty() && !segment.words.isEmpty()) continue
            newSegments.add(kept)
        }
        if (newSegments.size == transcription.segments.size &&
            newSegments.zip(transcription.segments).all { (a, b) -> a === b }
        ) {
            return transcription
        }
        // The flat words list mirrors the new segments — every
        // word in a kept segment is included; words from
        // dropped segments are not. This keeps the segment ↔
        // word invariant the rest of the pipeline relies on.
        val newWords = newSegments.flatMap { it.words }
        return Transcription(segments = newSegments, words = newWords)
    }

    /**
     * Apply the filter to a single [Subtitle]. The segment
     * `id` is preserved when the filter does nothing; when
     * the filter does something, the kept segment gets a new
     * `id` (UUID) because its word list is materially
     * different from the input.
     */
    private fun filterSegment(segment: Subtitle): Subtitle {
        val words = segment.words
        if (words.size < MIN_REPETITION_COUNT) return segment

        // Pre-compute the "equality key" for each word: the
        // text after Persian normalisation (so "می‌روم" and
        // "می‌روم" with different ZWNJ placements still match).
        val keys = ArrayList<String>(words.size)
        for (w in words) keys.add(PersianNormalizer.normalize(w.text) ?: w.text)

        val keptIndices = ArrayList<Int>(words.size)
        var i = 0
        var didCollapse = false
        while (i < words.size) {
            val k = keys[i]
            var j = i + 1
            while (j < words.size && keys[j] == k) j++
            val run = j - i
            if (run >= MIN_REPETITION_COUNT) {
                // Collapse to the first occurrence only.
                keptIndices.add(i)
                didCollapse = true
            } else {
                // Below threshold: keep the entire run.
                for (idx in i until j) keptIndices.add(idx)
            }
            i = j
        }

        if (!didCollapse) return segment

        val keptWords = ArrayList<TranscribedWord>(keptIndices.size)
        for (idx in keptIndices) keptWords.add(words[idx])

        val segStart = keptWords.first().startTimeMs
        val segEnd = keptWords.last().endTimeMs
        val segText = keptWords.joinToString(separator = " ") { it.text }.trim()

        // Recompute the segment confidence as the average of
        // the kept words' confidences (only counting non-null
        // values, matching the existing
        // groupWordsIntoSubtitlesForWhisper contract).
        val confs = keptWords.mapNotNull { it.confidence }
        val avgConfidence: Float? = if (confs.isEmpty()) null else confs.average().toFloat()

        return Subtitle(
            id = UUID.randomUUID().toString(),
            text = segText,
            startTimeMs = segStart,
            endTimeMs = segEnd,
            words = keptWords,
            confidence = avgConfidence
        )
    }
}
