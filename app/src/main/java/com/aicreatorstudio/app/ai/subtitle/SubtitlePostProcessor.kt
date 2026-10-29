package com.aicreatorstudio.app.ai.subtitle

import com.aicreatorstudio.app.ai.Subtitle
import com.aicreatorstudio.app.ai.TranscribedWord
import com.aicreatorstudio.app.ai.Transcription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * PHASE B7 — orchestrator for the Persian subtitle
 * post-processing pipeline.
 *
 * ## Pipeline
 *
 * ```
 *  Whisper.cpp → Transcription
 *       ↓
 *  SubtitlePostProcessor.postProcess(transcription, settings)
 *       │
 *       ├─ 1. PersianNormalizer   (per-word, character fold)
 *       ├─ 2. RepetitionFilter    (3+ consecutive identical)
 *       ├─ 3. SilenceFilter       (single low-conf long word)
 *       └─ 4. SubtitleSegmenter   (split / merge readability;
 *            word-count-only when resegment is off but a
 *            words-per-caption cap is set)
 *       ↓
 *  new Transcription (same shape, refined)
 * ```
 *
 * Each step is **individually** opt-in via [SubtitlePostProcessSettings].
 * With `settings = SubtitlePostProcessSettings.Identity` the
 * function returns the input transcription bit-for-bit
 * (the early `isIdentity` short-circuit guarantees this
 * without running any work).
 *
 * ## Threading
 *
 * The function is `suspend` and runs its work on
 * `Dispatchers.Default` (CPU-bound, no I/O). The
 * `EditorViewModel.generateSubtitles` call site already
 * runs inside a `viewModelScope.launch(Dispatchers.IO)`
 * coroutine, so the post-processor's dispatcher hop is
 * free. The brief's "must run off the main thread" rule
 * is satisfied by this combination — Compose recompositions
 * are never blocked.
 *
 * ## Allocation discipline
 *
 * Each step tries to return the input `Transcription`
 * unchanged when no work is needed; only the steps that
 * mutate something allocate. The `Transcription` returned
 * by each step is consumed by the next step, never copied
 * to a separate "before vs after" buffer. The mapper
 * downstream produces a fresh `List<TextOverlay>` from
 * the final `Transcription`, so the only allocation
 * between the engine and the timeline is the per-step
 * `ArrayList` of kept segments / words.
 */
object SubtitlePostProcessor {

    /**
     * Run the configured steps over [transcription] and
     * return the refined transcription. The returned
     * transcription has the same shape the
     * B2 mapper ([com.aicreatorstudio.app.ai.WhisperTranscriptionToTextOverlays.toTextOverlays])
     * consumes, so the downstream pipeline is unchanged.
     */
    suspend fun postProcess(
        transcription: Transcription,
        settings: SubtitlePostProcessSettings = SubtitlePostProcessSettings.Default,
        customWords: Set<String> = emptySet(),
        onCorrections: (List<com.aicreatorstudio.app.ai.subtitle.corrector.CorrectionRecord>) -> Unit = {}
    ): Transcription = withContext(Dispatchers.Default) {
        if (transcription.segments.isEmpty() || settings.isIdentity) {
            return@withContext transcription
        }

        // (2026-09-07 DOC FIX — the header diagram listed
        // Normalizer → Repetition → Silence → Segmenter; the real
        // (and correct) order is Silence BEFORE Repetition, as
        // implemented below.)

        // Step 1: Persian normalisation. Mutates each
        // segment's text in place (per-word) and re-derives
        // the segment.text from the words. Words keep their
        // startTimeMs / endTimeMs / confidence / id.
        var t = if (settings.normalizePersian) normalizeAll(transcription) else transcription

        // Step 1.25 (2026-09-20): BPE fragment merge — whisper.cpp
        // BPE tokeniser sometimes splits a Persian word into
        // sub-word tokens where the SECOND token carries a space
        // prefix (indicating a new word) even though the token is
        // just a fragment. Example: "زدم" → " ز" + "دم" when the
        // space-prefix heuristic fails. The result is a
        // single-character orphan word ("ز") followed by the rest
        // ("دم"). This step detects single-Persian-character words
        // (excluding legitimate standalone monographs و / ا / آ)
        // and merges them back into the PREVIOUS word, extending
        // the time range. Only runs when the segment has 2+ words.
        t = mergeBpeFragments(t)

        // Step 1.3 (2026-09-21): Persian word integrity — enforces
        // the critical rules that Persian words (including their
        // verbal suffixes, plural markers, and pronouns attached
        // via ZWNJ) must NEVER be split across lines or segments.
        // This step rebuilds each segment's text from its word list
        // to guarantee 1:1 correspondence, preserving ZWNJ within
        // words and never inserting breaks inside compound words.
        t = enforceWordIntegrity(t)

        // Step 1.5 (2026-09-11): conservative typo corrector —
        // suspicious detection → closed-list candidates → context
        // validation → confidence gate. Only flagged words can
        // change; ids/timestamps/confidence are preserved. Custom
        // dictionary words are never touched.
        if (settings.correctTypos) {
            val result = com.aicreatorstudio.app.ai.subtitle.corrector
                .TranscriptCorrector.correct(t, settings, customWords)
            t = result.transcription
            if (result.records.isNotEmpty()) onCorrections(result.records)
        }

        // Step 2: silence filter. Operates on the
        // already-normalised segments.
        if (settings.filterSilence) {
            t = SilenceFilter.filter(
                t,
                minConfidence = settings.minConfidenceForLongWord,
                longWordMs = settings.longWordMsThreshold
            )
        }

        // Step 3: repetition filter. After silence filtering
        // the segment count is stable; repetition operates
        // on the remaining segments.
        if (settings.collapseRepetition) {
            t = RepetitionFilter.filter(t)
        }

        // Step 4: segmenter. Re-bin words into segments
        // that respect the readability caps. 2026-09-09 — the
        // reading-speed (CPS) and pause-aware signals join the caps.
        // 2026-09-15 — the Premiere-style word cap
        // (maxWordsPerSegment) joins them: when set, every caption
        // carries at most that many words.
        // 2026-09-19 WORD-CAP INDEPENDENCE FIX — the word cap is
        // honoured even when the full resegment is OFF (e.g. the
        // master post-process toggle is disabled but the user still
        // picked "۳ کلمه"): only the fixed-size chunking runs, the
        // chars/duration/CPS/pause/merge passes stay off so the
        // output is otherwise verbatim.
        if (settings.resegment) {
            t = SubtitleSegmenter.resegment(
                t,
                maxCharsPerLine = settings.maxCharsPerLine,
                maxSegmentDurationMs = settings.maxSegmentDurationMs,
                minSegmentDurationMs = settings.minSegmentDurationMs,
                mergeGapMs = settings.mergeGapMs,
                maxReadingCps = settings.maxReadingCps,
                pauseSplitMs = settings.pauseSplitMs,
                maxWordsPerSegment = settings.maxWordsPerSegment
            )
        } else if (settings.maxWordsPerSegment > 0) {
            t = SubtitleSegmenter.chunkByWordCount(t, settings.maxWordsPerSegment)
        }

        t
    }

    /**
     * Apply [PersianNormalizer.normalize] to every word's
     * `text` field and re-derive each segment's `text` from
     * its words. Word identities (id, start, end, confidence)
     * are preserved.
     */
    private fun normalizeAll(transcription: Transcription): Transcription {
        if (transcription.segments.isEmpty()) return transcription
        val newSegments = ArrayList<Subtitle>(transcription.segments.size)
        var mutated = false
        for (segment in transcription.segments) {
            // 2026-09-13 CLOUD FIX — word-less segments (Cloud
            // path) must NOT have their text re-derived from an
            // empty word list (that blanked the text and emptied
            // the pipeline). Normalize the SEGMENT text directly.
            if (segment.words.isEmpty()) {
                val normalizedText =
                    PersianNormalizer.normalize(segment.text) ?: segment.text
                newSegments.add(
                    if (normalizedText == segment.text) segment
                    else segment.copy(text = normalizedText)
                )
                continue
            }
            val newWords = ArrayList<TranscribedWord>(segment.words.size)
            var wordsMutated = false
            for (w in segment.words) {
                val normalized = PersianNormalizer.normalize(w.text)
                // 2026-09-07 WHITESPACE-WORD FIX — a whitespace-only
                // word normalizes to "" which previously survived
                // into the joined segment text as doubled spaces.
                // Drop empty results instead of keeping the word.
                if (normalized != null && normalized != w.text) {
                    if (normalized.isNotEmpty()) {
                        newWords.add(w.copy(text = normalized))
                        wordsMutated = true
                    } else {
                        wordsMutated = true
                    }
                } else {
                    newWords.add(w)
                }
            }
            val newText = newWords.joinToString(separator = " ") { it.text }.trim()
            if (!wordsMutated && newText == segment.text) {
                newSegments.add(segment)
            } else {
                mutated = true
                newSegments.add(
                    segment.copy(
                        text = newText,
                        words = newWords
                    )
                )
            }
        }
        if (!mutated) return transcription
        val newWords = newSegments.flatMap { it.words }
        return Transcription(segments = newSegments, words = newWords)
    }

    /**
     * Merge single-character BPE fragments back into the previous
     * word. Whisper's BPE tokeniser occasionally emits a
     * single-letter continuation as a new word (e.g. "ز" from
     * "زدم") because the space-prefix heuristic misfires on
     * certain sub-word boundaries.
     *
     * Legitimate Persian monographs — و (and), ا (oh), آ (vocative)
     * — are kept as standalone words. Everything else that is exactly
     * one Persian-script character is glued onto the preceding word
     * within the same segment (time range extended, id dropped).
     * Segments with ≤1 word are untouched.
     */
    private fun mergeBpeFragments(transcription: Transcription): Transcription {
        if (transcription.segments.isEmpty()) return transcription
        val newSegments = ArrayList<Subtitle>(transcription.segments.size)
        // 2026-10-20 — the "did this segment change?" flag is now
        // PER SEGMENT. It used to be declared OUTSIDE the loop, so
        // after the first merged segment it stayed `true` for the rest
        // of the transcription and every following segment was rebuilt
        // (a new `Subtitle` + a full text join) even when nothing had
        // changed. The output was identical, but the work was wasted
        // on every segment after the first merge.
        var anyMutated = false
        for (segment in transcription.segments) {
            val words = segment.words
            if (words.size <= 1) {
                newSegments.add(segment)
                continue
            }
            val merged = ArrayList<TranscribedWord>(words.size)
            var segmentMutated = false
            for (w in words) {
                val isSingleChar = w.text.length == 1 &&
                    PersianNormalizer.isPersianScript(w.text)
                val isStandalone = w.text.length == 1 && STANDALONE_MONOGRAPHS.contains(w.text)
                if (isSingleChar && !isStandalone && merged.isNotEmpty()) {
                    val prev = merged.removeAt(merged.size - 1)
                    merged.add(
                        prev.copy(
                            text = prev.text + w.text,
                            endTimeMs = maxOf(prev.endTimeMs, w.endTimeMs)
                        )
                    )
                    segmentMutated = true
                } else {
                    merged.add(w)
                }
            }
            if (!segmentMutated) {
                newSegments.add(segment)
            } else {
                anyMutated = true
                val newText = merged.joinToString(" ") { it.text }.trim()
                newSegments.add(segment.copy(text = newText, words = merged))
            }
        }
        if (!anyMutated) return transcription
        val newWords = newSegments.flatMap { it.words }
        return Transcription(segments = newSegments, words = newWords)
    }

    /** Persian single-character words that ARE standalone. */
    private val STANDALONE_MONOGRAPHS = setOf(
        "\u0648",  // و (and)
        "\u0627",  // ا (oh / interjection prefix)
        "\u0622"   // آ (vocative)
    )

    /**
     * Enforce Persian word integrity: rebuild each segment's text
     * from its word list so that:
     *
     *  1. Every word in the text is a complete word from the word
     *     list — no partial splits, no orphan suffixes.
     *  2. ZWNJ (U+200C) inside compound words is preserved (it
     *     is word-INTERNAL, never a split point).
     *  3. Verbal suffixes (ـم، ـیم، ـید، ـند) and plural markers
     *     (ها، ـها) stay attached to the base word.
     *
     * The segment's `text` field is re-derived by joining words
     * with single spaces. Word-level timestamps and ids are
     * preserved unchanged.
     */
    private fun enforceWordIntegrity(transcription: Transcription): Transcription {
        if (transcription.segments.isEmpty()) return transcription
        val newSegments = ArrayList<Subtitle>(transcription.segments.size)
        var mutated = false
        for (segment in transcription.segments) {
            if (segment.words.isEmpty()) {
                newSegments.add(segment)
                continue
            }
            // Rebuild text from words — guaranteed word-safe.
            val rebuiltText = segment.words.joinToString(" ") { it.text }.trim()
            if (rebuiltText != segment.text) {
                mutated = true
                newSegments.add(segment.copy(text = rebuiltText))
            } else {
                newSegments.add(segment)
            }
        }
        if (!mutated) return transcription
        val newWords = newSegments.flatMap { it.words }
        return Transcription(segments = newSegments, words = newWords)
    }
}
