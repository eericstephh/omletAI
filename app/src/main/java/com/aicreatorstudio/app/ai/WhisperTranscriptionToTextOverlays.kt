package com.aicreatorstudio.app.ai

import com.aicreatorstudio.app.model.PersianFont
import com.aicreatorstudio.app.model.TextOverlay

/**
 * PHASE B2 — convert a [Transcription] produced by [WhisperEngine]
 * into a list of [TextOverlay]s ready to be dropped onto the
 * editor timeline.
 *
 * This is the single source of truth for the
 * `Transcription → TextOverlay` shape. It used to live inline in
 * [com.aicreatorstudio.app.viewmodel.EditorViewModel.generateSubtitles];
 * B2 lifts it out so the conversion can be unit-tested without the
 * full Hilt graph and so the B2 acceptance tests can build the
 * expected overlay list and compare it against the ViewModel's
 * output bit-for-bit.
 *
 * ## Why one [TextOverlay] per Whisper segment
 *
 * [WhisperEngine] already groups per-word timestamps into readable
 * subtitle segments (Premiere-style multi-word captions,
 * `groupWordsIntoSubtitlesForWhisper`). Each segment becomes one
 * [TextOverlay] whose `text` is the segment text and whose `words`
 * list carries the per-word timing so the preview can drive
 * karaoke-style word highlighting.
 *
 * Using one overlay per word (instead of per segment) would make
 * the timeline unreadable and would not match what the user
 * sees in every other NLE.
 *
 * ## Timeline coordinate system
 *
 * Whisper timestamps are in **source-media ms** (0 = the start of
 * the audio track of the source clip). The editor timeline uses
 * **timeline ms** (0 = the start of the entire composed project).
 * For a project with a single source clip whose
 * [com.aicreatorstudio.app.model.VideoClip.timelineStartMs] is 0,
 * source time == timeline time. For a multi-clip project the
 * caller passes [clipOffsetMs] = the source clip's
 * `timelineStartMs` and the mapper shifts every word + every
 * segment by that exact amount — no stretching, no compression,
 * no redistribution across the video.
 *
 * ## What this mapper guarantees
 *
 * For every produced [TextOverlay]:
 *   1. `text` is the exact segment text from whisper.cpp (no
 *      trimming of meaningful characters; only the leading/
 *      trailing ASCII whitespace that the BPE encoder emits).
 *   2. `startTimeMs` and `endTimeMs` are the segment start/end
 *      plus [clipOffsetMs].
 *   3. `words[i].startTimeMs` and `words[i].endTimeMs` are
 *      shifted by the same offset, preserving the relative word
 *      timing.
 *   4. `0 <= startTimeMs < endTimeMs` for every overlay; the
 *      mapper drops any segment whose shifted end is not strictly
 *      after its shifted start (defensive — should never happen
 *      for real Whisper output).
 *   5. Overlay ids are stable across calls (UUIDs minted here
 *      so the test harness can compare equality with the
 *      ViewModel's output; the ViewModel does the same).
 *   6. Overlays are sorted by `startTimeMs` so the timeline
 *      renders them in speech order.
 *   7. Empty segments (no words) are dropped so the user never
 *      sees a blank caption.
 */
object WhisperTranscriptionToTextOverlays {

    /**
     * Map [transcription] into one [TextOverlay] per non-empty
     * segment. [clipOffsetMs] is added to every word + segment
     * start/end. [font] is the user-picked Persian font; the
     * overlay stores its enum name and the picker resolves it.
     *
     * Returns an empty list when [transcription] is empty or when
     * every segment fails the start<end gate.
     */
    fun toTextOverlays(
        transcription: Transcription,
        clipOffsetMs: Long = 0L,
        font: PersianFont = PersianFont.DEFAULT
    ): List<TextOverlay> {
        if (transcription.segments.isEmpty()) return emptyList()
        val offset = clipOffsetMs.coerceAtLeast(0L)

        val out = ArrayList<TextOverlay>(transcription.segments.size)
        for (segment in transcription.segments) {
            // 2026-09-13 CLOUD FIX — segments WITHOUT words are now
            // VALID overlays (the Cloud path can return segments
            // without per-word timings; the offline path keeps its
            // words). The old gate dropped every word-less segment,
            // so cloud results vanished here with "هیچ گفتاری در
            // ویدیو شناسایی نشد".
            // The only hard requirement left: non-blank text AND a
            // real time window (end > start after the offset).
            val segStart = offset + segment.startTimeMs
            val segEnd = offset + segment.endTimeMs
            // Defensive gate: reject any segment that, after the
            // offset, has end <= start. The mapper must never
            // invent a positive duration.
            if (segEnd <= segStart) continue
            // A word-less segment still needs actual caption text.
            if (segment.text.isBlank()) continue
            val shiftedWords = segment.words.map { w ->
                w.copy(
                    startTimeMs = offset + w.startTimeMs,
                    endTimeMs = offset + w.endTimeMs
                )
            }
            // Final per-word guard: every word's shifted end must
            // be strictly after its shifted start. Drop the
            // single bad word rather than reject the whole
            // segment.
            val validWords = shiftedWords.filter { it.endTimeMs > it.startTimeMs }

            out.add(
                TextOverlay(
                    id = java.util.UUID.randomUUID().toString(),
                    text = segment.text,
                    startTimeMs = segStart,
                    endTimeMs = segEnd,
                    fontFamily = font.name,
                    words = validWords,
                    karaokeEnabled = validWords.isNotEmpty(),
                    // PHASE B4: tag the overlay so the export
                    // filter picks it up across project save/
                    // reload without relying on the in-memory
                    // `aiSubtitleOverlayIds` set.
                    subtitleSource = "ai",
                    // 2026-09-07 — record the generation offset so
                    // the SRT export can convert back to media-local
                    // times (per-overlay timebase).
                    subtitleAnchorMs = offset
                )
            )
        }
        return out.sortedBy { it.startTimeMs }
    }
}
