package com.aicreatorstudio.app.ai.subtitle.cloud

import com.aicreatorstudio.app.ai.Subtitle
import com.aicreatorstudio.app.ai.TranscribedWord
import com.aicreatorstudio.app.ai.Transcription
import com.aicreatorstudio.app.ai.groq.GroqResponseParser
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 2026-09-13 GROQ REBUILD — converts Groq's verbose_json reply
 * into the app's internal [Transcription] (segments + per-word
 * timings).
 *
 * ## Priority order (timing accuracy)
 *
 * 1. **`segments` array** — Groq's verbose_json `{start,end,text}`
 *    list (real segment timestamps from whisper-large-v3-turbo).
 * 2. **Honest fallback** — text only, with NO invented per-word
 *    split: one segment spanning the REAL decoded audio duration.
 *
 * Word timings (`words[]` with the documented `word` granularity
 * — officially supported by whisper-large-v3-turbo on Groq), when
 * present, are attached to the segment whose `[start,end]` window
 * contains them. All timings are ms offsets from the start of the
 * audio (which matches the video's audio track exactly, since the
 * extraction is a pure remux), so the ViewModel's existing
 * window/anchor adaptation places them on the timeline correctly.
 *
 * ## Word synthesis fallback (2026-09-20)
 *
 * When the backend omits `words[]` (the common Cloud Worker shape
 * before `verbose_json` was requested), each segment's text is
 * tokenized on whitespace and the words are spread proportionally
 * across the segment's duration. This gives the renderer enough
 * per-word timing to drive karaoke highlight, word-by-word reveal
 * and the word-level template axes — features that silently
 * degrade without any word timings.
 *
 * Persian text is kept verbatim — no reordering, no
 * transliteration; only leading/trailing whitespace is trimmed.
 * UTF-8 is preserved end-to-end (the HTTP layer reads the body
 * as UTF-8).
 */
@Singleton
class SubtitleResultParser @Inject constructor() {

    /**
     * 2026-10-10 USER-KEY PATH — same conversion for the direct
     * [GroqWhisperClient] reply ([GroqResponseParser.GroqTranscription]),
     * which is the shape returned when the user's own API key is used
     * instead of the Cloud Worker. Delegates to the Worker-shaped
     * overload below so both paths share one tested conversion.
     */
    fun toTranscription(
        response: GroqResponseParser.GroqTranscription,
        audioDurationMs: Long
    ): Transcription = toTranscription(
        CloudWhisperService.TranscriptionResponse(
            text = response.text,
            language = response.language,
            durationSec = response.durationSec,
            segments = response.segments.map {
                CloudWhisperService.TranscriptionResponse.Segment(
                    startSec = it.startSec,
                    endSec = it.endSec,
                    text = it.text
                )
            },
            words = response.words.map {
                CloudWhisperService.TranscriptionResponse.Word(
                    word = it.word,
                    startSec = it.startSec,
                    endSec = it.endSec
                )
            },
            vtt = response.vtt
        ),
        audioDurationMs = audioDurationMs
    )

    /**
     * @param response parsed Groq verbose_json.
     * @param audioDurationMs real duration of the decoded audio —
     *   only used by the no-timings fallback so even a single
     *   segment has an honest end time.
     */
    fun toTranscription(
        response: CloudWhisperService.TranscriptionResponse,
        audioDurationMs: Long
    ): Transcription {
        val words = response.words
            .filter { it.word.isNotBlank() }
            .map {
                TranscribedWord(
                    text = it.word.trim(),
                    startTimeMs = (it.startSec * 1000).toLong(),
                    endTimeMs = (it.endSec * 1000).toLong(),
                    confidence = null
                )
            }

        // ---- 1. segments array (Groq verbose_json) ----
        if (response.segments.isNotEmpty()) {
            val hasBackendWords = words.isNotEmpty()
            // 2026-10-10 BOUNDARY FIX — assign each backend word to
            // exactly ONE segment by its midpoint instead of strict
            // [start,end] containment. The old containment filter
            // dropped words that straddle a segment boundary (their
            // start < segment.start OR end > segment.end); the top-level
            // words list kept them, but the segment's own word list did
            // not, so downstream text rebuilt from segment.words lost
            // those words. Midpoint assignment guarantees every word
            // lands in exactly one segment (no loss, no duplication).
            val segmentWordLists: List<List<TranscribedWord>> =
                if (hasBackendWords) {
                    assignWordsByMidpoint(words, response.segments)
                } else {
                    emptyList()
                }
            val allSynthesizedWords = mutableListOf<TranscribedWord>()
            val segments = response.segments.mapIndexed { idx, s ->
                val startMs = (s.startSec * 1000).toLong()
                val endMs = (s.endSec * 1000).toLong()
                val segmentWords = if (hasBackendWords) {
                    segmentWordLists[idx]
                } else {
                    val synth = synthesizeWords(s.text, startMs, endMs)
                    allSynthesizedWords.addAll(synth)
                    synth
                }
                Subtitle(
                    text = s.text,
                    startTimeMs = startMs,
                    endTimeMs = endMs,
                    words = segmentWords
                )
            }
            val topWords = if (hasBackendWords) words else allSynthesizedWords
            return Transcription(segments = segments, words = topWords)
        }

        // ---- 2. honest fallback: ONE segment, REAL duration ----
        val end = maxOf(
            audioDurationMs.takeIf { it > 0 } ?: 0L,
            words.lastOrNull()?.endTimeMs ?: 0L,
            1000L
        )
        val fallbackWords = if (words.isNotEmpty()) {
            words
        } else {
            synthesizeWords(response.text, 0L, end)
        }
        return Transcription(
            segments = listOf(
                Subtitle(
                    text = response.text,
                    startTimeMs = 0L,
                    endTimeMs = end,
                    words = fallbackWords
                )
            ),
            words = fallbackWords
        )
    }

    /**
     * Tokenize [text] on whitespace and distribute the resulting
     * words evenly across [startMs]..[endMs]. Each word gets a
     * minimum of 80 ms to avoid degenerate zero-length slots.
     *
     * This is a best-effort fallback: real acoustic word timings
     * are always preferred, but synthetic proportional timing
     * still lets the karaoke/highlight renderer function.
     */
    private fun synthesizeWords(
        text: String,
        startMs: Long,
        endMs: Long
    ): List<TranscribedWord> {
        val tokens = text.trim().split(WHITESPACE).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val duration = (endMs - startMs).coerceAtLeast(80L * tokens.size)
        val slotMs = duration / tokens.size
        return tokens.mapIndexed { i, tok ->
            TranscribedWord(
                text = tok,
                startTimeMs = startMs + i * slotMs,
                endTimeMs = startMs + (i + 1) * slotMs,
                confidence = null
            )
        }
    }

    /**
     * 2026-10-10 — assign each backend word to exactly one segment
     * by its midpoint. Returns one list per segment (same order as
     * [segments]), guaranteeing every word appears in exactly one
     * segment's list (no loss, no duplication) even when a word's
     * [start,end] straddles a segment boundary.
     *
     * A word whose midpoint falls in a gap between segments is
     * attached to the nearest segment by midpoint distance.
     */
    private fun assignWordsByMidpoint(
        words: List<TranscribedWord>,
        segments: List<CloudWhisperService.TranscriptionResponse.Segment>
    ): List<List<TranscribedWord>> {
        val buckets = MutableList(segments.size) { mutableListOf<TranscribedWord>() }
        val bounds = segments.map { s ->
            (s.startSec * 1000).toLong() to (s.endSec * 1000).toLong()
        }
        for (w in words) {
            val mid = (w.startTimeMs + w.endTimeMs) / 2
            val idx = bounds.indexOfFirst { (s, e) -> mid in s..e }
            val target = if (idx >= 0) {
                idx
            } else {
                // midpoint fell in a gap — nearest segment by center distance
                bounds.indices.minByOrNull { i ->
                    val (s, e) = bounds[i]
                    val center = (s + e) / 2
                    kotlin.math.abs(mid - center)
                } ?: 0
            }
            buckets[target].add(w)
        }
        return buckets
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
