package com.aicreatorstudio.app.ai

import java.util.UUID

/**
 * A single recognized word with its exact start/end time as reported by
 * the speech-recognition backend (today: whisper.cpp via
 * `whisper_full_get_word_t`). Times are millisecond offsets
 * from the beginning of the source media — they are NOT timeline-space
 * coordinates. The caller is responsible for shifting them by the source
 * clip's `timelineStartMs` before placing them on the editor timeline.
 *
 * `confidence` is the model-reported probability in `[0, 1]` when the
 * backend provides one (Whisper does), and `null` when it doesn't.
 */
data class TranscribedWord(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val confidence: Float? = null
) {
    /** True when [positionMs] falls inside `[startTimeMs, endTimeMs)`. */
    fun isActiveAt(positionMs: Long): Boolean =
        positionMs in startTimeMs until endTimeMs.coerceAtLeast(startTimeMs)
}

/**
 * A subtitle segment — one or more [TranscribedWord]s grouped into a
 * readable caption. Segment start/end are derived from the first/last
 * word's real timestamps; the segment therefore tracks the underlying
 * speech exactly and never invents duration.
 *
 * `id` is stable across edits so the timeline and preview can track
 * which segment is currently active as the playhead moves.
 */
data class Subtitle(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    /** Recognized words inside this segment, in speech order. */
    val words: List<TranscribedWord> = emptyList(),
    /** Average confidence across [words], when available. */
    val confidence: Float? = null
) {
    /** True when [positionMs] falls inside `[startTimeMs, endTimeMs)`. */
    fun isVisibleAt(positionMs: Long): Boolean =
        positionMs in startTimeMs until endTimeMs.coerceAtLeast(startTimeMs)

    /**
     * Returns the word whose `[startTimeMs, endTimeMs)` range contains
     * [positionMs], or null if the position is outside every word in
     * the segment (e.g. between two words).
     */
    fun activeWordAt(positionMs: Long): TranscribedWord? =
        words.firstOrNull { it.isActiveAt(positionMs) }
}

/**
 * Full transcription result: every recognized word plus the segments
 * they were grouped into. Returned by [SubtitleGenerator.generateTranscription]
 * and consumed by both the timeline (segments → `TextOverlay`s) and
 * the SRT exporter (segments → cue blocks).
 *
 * Both lists are sorted by `startTimeMs` ascending.
 */
data class Transcription(
    val segments: List<Subtitle>,
    val words: List<TranscribedWord>
) {
    val isEmpty: Boolean get() = segments.isEmpty() && words.isEmpty()

    companion object {
        val Empty = Transcription(segments = emptyList(), words = emptyList())
    }
}
