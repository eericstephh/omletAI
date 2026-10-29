package com.aicreatorstudio.app.ai.subtitle

import com.aicreatorstudio.app.ai.Subtitle
import com.aicreatorstudio.app.ai.Transcription

/**
 * PHASE B7 — drop whisper.cpp segments that look like
 * hallucinations on silence or low-energy audio.
 *
 * Whisper.cpp can emit a single word on a long stretch of
 * silence — typically a high-probability token it cannot
 * suppress (a common failure mode is hallucinating
 * "thanks for watching" on music). The output looks like
 * a single word with a huge `endTimeMs - startTimeMs`
 * span and a low `confidence` value. The brief authorises
 * dropping these "suspicious segments caused by silence
 * or very low-energy audio" without inventing replacement
 * text — and explicitly forbids creating a visible
 * subtitle clip for "empty or clearly invalid
 * transcription."
 *
 * ## Conservative rules
 *
 * A segment is dropped when **any** of these is true:
 *
 *  1. The segment has zero words (the mapper already
 *     handles this, but we drop here so the post-processor
 *     output is self-consistent and downstream tests can
 *     assert on it).
 *  2. The segment has exactly one word AND that word has
 *     a confidence below [minConfidence] AND its duration
 *     is longer than [longWordMs]. This is the "single
 *     hallucinated word on silence" pattern.
 *  3. The segment has exactly one word, no confidence
 *     (`null`) reported by whisper.cpp, AND its duration
 *     is longer than [longWordMs]. The "no confidence"
 *     case is treated as a softer signal — the word is
 *     only dropped if the duration is also unreasonably
 *     long, which protects normal real speech whose
 *     whisper.cpp probability happens to be 0.
 *
 * Multi-word low-confidence segments are **kept**
 * unconditionally. A real Persian phrase is rarely
 * confidence-0 across the board; if whisper.cpp is
 * uncertain about every word in a multi-word segment,
 * the conservative choice is to surface the segment
 * and let the user decide.
 */
object SilenceFilter {

    /**
     * Apply the filter to [transcription]. Returns a new
     * [Transcription] with hallucination-pattern segments
     * removed. The order of remaining segments is preserved.
     */
    fun filter(
        transcription: Transcription,
        minConfidence: Float = 0.15f,
        longWordMs: Long = 5_000L
    ): Transcription {
        if (transcription.segments.isEmpty()) return transcription
        val newSegments = ArrayList<Subtitle>(transcription.segments.size)
        for (segment in transcription.segments) {
            if (!isHallucination(segment, minConfidence, longWordMs)) {
                newSegments.add(segment)
            }
        }
        if (newSegments.size == transcription.segments.size) {
            return transcription
        }
        val newWords = newSegments.flatMap { it.words }
        return Transcription(segments = newSegments, words = newWords)
    }

    /**
     * Returns true when [segment] matches the "single
     * hallucinated word on silence" pattern that the brief
     * authorises dropping.
     */
    private fun isHallucination(
        segment: Subtitle,
        minConfidence: Float,
        longWordMs: Long
    ): Boolean {
        val words = segment.words
        // 2026-09-13 CLOUD FIX — word-less segments (Cloud path can
        // return segments without per-word timings) are NOT
        // hallucinations by definition: the hallucination rules
        // need a word's confidence + duration to judge. A real
        // text-bearing segment from the cloud engine passes.
        if (words.isEmpty()) return segment.text.isBlank()
        if (words.size != 1) return false
        val w = words.first()
        val duration = w.endTimeMs - w.startTimeMs
        if (duration <= longWordMs) return false
        // Single word, long duration. Confidence is the
        // deciding factor:
        //   - non-null and below minConfidence  → hallucination
        //   - null (whisper.cpp reported 0 probability)
        //     AND duration very long           → treat as
        //     hallucination, but only when the duration is
        //     at least 2× the longWordMs threshold, to avoid
        //     deleting a long real word that simply had no
        //     probability emitted.
        val conf = w.confidence
        return when {
            conf != null && conf < minConfidence -> true
            conf == null && duration > 2L * longWordMs -> true
            else -> false
        }
    }
}
