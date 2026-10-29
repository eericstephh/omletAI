package com.aicreatorstudio.app.ai.subtitle

/**
 * PHASE B7 — configuration for the Persian subtitle post-processor.
 *
 * The post-processor sits between
 * [com.aicreatorstudio.app.ai.WhisperEngine.generateTranscription]
 * and the B2 mapper
 * [com.aicreatorstudio.app.ai.WhisperTranscriptionToTextOverlays.toTextOverlays].
 * It is **pure data**: every step is opt-in via a boolean flag, and
 * the numeric limits are exposed so the user can dial them. There
 * is no I/O, no native call, no Compose dependency in this file.
 *
 * ## Why per-step flags
 *
 * Some users want only repetition cleanup but not the segmenter
 * (because they prefer word-level control). Others want a strict
 * 30-char cap for a vertical-video Shorts export. The flags let
 * each step be turned off without rebuilding the pipeline.
 *
 * ## Why "conservative defaults"
 *
 * [Default] is what a fresh install sees. The defaults are tuned
 * to be unobjectionable on a real Persian M4A:
 *   - [normalizePersian] on — Arabic ي/ك folding is uncontroversial.
 *   - [collapseRepetition] on — "word word word" is never useful.
 *   - [filterSilence] on — single low-confidence long words are
 *     almost always hallucination.
 *   - [resegment] on — sensible readability cap.
 *
 * A user who wants the raw whisper.cpp output verbatim can switch
 * every flag off, or use [Identity].
 *
 * ## Why "Identity" exists
 *
 * The B1/B2 tests assert the B2 mapper produces byte-identical
 * output for hand-built [Transcription]s. The mapper itself is
 * unchanged; the post-processor is what makes the difference.
 * [Identity] lets a future test pin the no-op invariant (the
 * post-processor with [Identity] must return the input
 * transcription bit-for-bit, modulo an empty-silence pass
 * which is a defensive guard, not a content change).
 *
 * @property normalizePersian apply [PersianNormalizer] to each
 *   segment text. Character folding is conservative: only Arabic
 *   ي (U+064A) → Persian ی (U+06CC) and Arabic ك (U+0643) →
 *   Persian ک (U+06A9). Whitespace is collapsed. ZWNJ (U+200C)
 *   is preserved (نیم‌فاصله is load-bearing in Persian).
 * @property collapseRepetition apply [RepetitionFilter]: drop
 *   3+ consecutive identical words. Two repetitions are left
 *   alone (they may be real "آره آره" or "می‌روم می‌روم").
 * @property filterSilence apply [SilenceFilter]: drop empty
 *   segments, and single low-confidence very-long words (likely
 *   hallucination on silence).
 * @property resegment apply [SubtitleSegmenter]: split
 *   overly-long segments, merge overly-short ones.
 * @property maxCharsPerLine split a segment when its
 *   reconstructed text would exceed this many characters.
 *   Default 42 follows the NLE convention for a single caption
 *   line. Range [20, 80].
 * @property maxSegmentDurationMs never produce a segment longer
 *   than this. Default 4 s matches the existing
 *   `WhisperEngine.MAX_SEGMENT_DURATION_MS` so the post-processor
 *   never loosens the engine's own cap.
 * @property minSegmentDurationMs merge a segment shorter than
 *   this with the next one (provided the gap is < [mergeGapMs]).
 *   Default 800 ms.
 * @property mergeGapMs maximum gap between a short segment and
 *   the next one for the merger to consider them a single
 *   caption. Default 200 ms.
 * @property minConfidenceForLongWord any single word with a
 *   duration longer than [longWordMsThreshold] AND a confidence
 *   below this value is dropped as a probable hallucination.
 *   Default 0.15. Range [0.0, 1.0].
 * @property longWordMsThreshold the "very long" duration cutoff
 *   for the silence filter's single-low-confidence-word rule.
 *   Default 5000 ms.
 */
data class SubtitlePostProcessSettings(
    val normalizePersian: Boolean = true,
    val collapseRepetition: Boolean = true,
    val filterSilence: Boolean = true,
    val resegment: Boolean = true,
    val maxCharsPerLine: Int = 42,
    val maxSegmentDurationMs: Long = 4_000L,
    val minSegmentDurationMs: Long = 800L,
    val mergeGapMs: Long = 200L,
    val minConfidenceForLongWord: Float = 0.15f,
    val longWordMsThreshold: Long = 5_000L,
    /**
     * 2026-09-09 READING SPEED — max characters-per-second a caption
     * may demand from the viewer. A segment denser than this is
     * split at the most balanced word boundary. 0 disables the
     * check. 15 CPS is the broadcast/NLE comfort convention; range
     * [0, 40].
     */
    val maxReadingCps: Float = 15f,
    /**
     * 2026-09-09 PAUSE-AWARE — inter-word silence longer than this
     * (ms) splits the segment exactly at the pause (the speaker
     * finished a thought). 0 disables. 700 ms is a natural spoken
     * sentence-break; range [0, 5000].
     */
    val pauseSplitMs: Long = 700L,
    /**
     * 2026-09-15 WORD-COUNT — Premiere-style "words per caption".
     * When > 0, every caption carries at most this many words:
     * segments are chunked in speech order at this fixed size
     * (e.g. 3 or 5 words) before the readability caps run. When
     * 0, the cap is off and the chars/duration/CPS rules alone
     * shape the captions (the historical behaviour). Range
     * [0, 12]; 12 keeps a hard ceiling well above the engine's
     * own MAX_WORDS_PER_SEGMENT while staying readable on a
     * single caption line.
     */
    val maxWordsPerSegment: Int = 0,
    /**
     * 2026-09-11 CONSERVATIVE TYPO CORRECTOR — run the
     * [com.aicreatorstudio.app.ai.subtitle.corrector.TranscriptCorrector]
     * after normalization: suspicious-word detection, closed-list
     * candidate generation, context-aware selection, confidence
     * gating. Only flagged words can ever change; everything else
     * passes through byte-for-byte.
     */
    val correctTypos: Boolean = true,
    /**
     * Confidence ≥ this auto-applies the correction. Range [0.5, 1.0].
     * The brief's suggestion: 0.90.
     */
    val autoApplyThreshold: Float = 0.90f,
    /**
     * Confidence in [reviewThreshold, autoApplyThreshold) marks the
     * correction as "flagged for review" (recorded, not applied).
     * Below this the word is kept untouched. Range [0.3, 0.95].
     */
    val reviewThreshold: Float = 0.70f,
    /**
     * Feed whisper's per-word confidence into the selector (brief §7):
     * low-confidence words are cheaper to correct, high-confidence
     * ones lean toward KEEP_ORIGINAL.
     */
    val typoUseWhisperConfidence: Boolean = true
) {
    init {
        require(maxCharsPerLine in 20..200) {
            "maxCharsPerLine must be in 20..200 (got $maxCharsPerLine)"
        }
        require(maxSegmentDurationMs in 1_000L..10_000L) {
            "maxSegmentDurationMs must be in 1000..10000 (got $maxSegmentDurationMs)"
        }
        require(minSegmentDurationMs in 100L..2_000L) {
            "minSegmentDurationMs must be in 100..2000 (got $minSegmentDurationMs)"
        }
        require(mergeGapMs in 0L..1_000L) {
            "mergeGapMs must be in 0..1000 (got $mergeGapMs)"
        }
        require(minConfidenceForLongWord in 0.0f..1.0f) {
            "minConfidenceForLongWord must be in 0.0..1.0 (got $minConfidenceForLongWord)"
        }
        require(longWordMsThreshold in 1_000L..30_000L) {
            "longWordMsThreshold must be in 1000..30000 (got $longWordMsThreshold)"
        }
        require(maxReadingCps in 0f..40f) {
            "maxReadingCps must be in 0..40 (got $maxReadingCps)"
        }
        require(pauseSplitMs in 0L..5_000L) {
            "pauseSplitMs must be in 0..5000 (got $pauseSplitMs)"
        }
        require(maxWordsPerSegment in 0..12) {
            "maxWordsPerSegment must be in 0..12 (got $maxWordsPerSegment)"
        }
        require(autoApplyThreshold in 0.5f..1.0f) {
            "autoApplyThreshold must be in 0.5..1.0 (got $autoApplyThreshold)"
        }
        require(reviewThreshold in 0.3f..0.95f) {
            "reviewThreshold must be in 0.3..0.95 (got $reviewThreshold)"
        }
        require(reviewThreshold <= autoApplyThreshold) {
            "reviewThreshold ($reviewThreshold) must be <= autoApplyThreshold " +
                "($autoApplyThreshold)"
        }
    }

    /**
     * Every step disabled AND no words-per-caption cap. A non-zero
     * [maxWordsPerSegment] is itself work to do (the segmenter must
     * still chunk), so it breaks identity even when every boolean
     * flag is off. Useful for tests that want to assert
     * the raw mapper contract survives untouched, and for users
     * who explicitly want the verbatim whisper.cpp output.
     */
    val isIdentity: Boolean
        get() = !normalizePersian && !collapseRepetition && !filterSilence &&
            !resegment && !correctTypos && maxWordsPerSegment == 0

    companion object {
        /**
         * All steps disabled, all numeric limits at permissive
         * values. With [Identity] the post-processor must return
         * the input [com.aicreatorstudio.app.ai.Transcription]
         * bit-for-bit (modulo the empty-segment defensive
         * filter, which never mutates content).
         */
        val Identity: SubtitlePostProcessSettings = SubtitlePostProcessSettings(
            normalizePersian = false,
            collapseRepetition = false,
            filterSilence = false,
            resegment = false,
            correctTypos = false
        )

        /**
         * Production defaults: every step on, every limit at a
         * safe value. The fresh-install experience.
         */
        val Default: SubtitlePostProcessSettings = SubtitlePostProcessSettings()
    }
}
