package com.aicreatorstudio.app.ai.subtitle.correction

/**
 * Data model for the **Persian Subtitle AI Correction** stage.
 *
 * ## Where this sits in the pipeline
 *
 * ```
 * Whisper (unchanged) -> Transcription -> post-process -> TextOverlay list
 *                                                              |
 *                        user taps "اصلاح با هوش مصنوعی"  <-----+
 *                                                              |
 *                     SubtitleCorrectionBatcher.plan(...)      |
 *                              |                               |
 *                    GroqChatClient.complete(...)  (per batch) |
 *                              |                               |
 *                  CorrectionResponseParser (strict)           |
 *                              |                               |
 *                       CorrectionPreview  --> user reviews    |
 *                              |                               |
 *                   CorrectionApplier.apply(...)               |
 *                              v                               |
 *                    TextOverlay list, text replaced only
 * ```
 *
 * Nothing here touches the audio, the media, or the Whisper model.
 * Timings are copied, never recomputed, and the preview stands
 * between the network and the editing state so a correction can be
 * refused without side effects.
 */

/**
 * One subtitle segment as it is sent to the model.
 *
 * @property id the overlay id. It is the correlation key of the whole
 *   exchange: the model echoes it back, and a reply that does not
 *   contain exactly this id is rejected. Using the overlay id (rather
 *   than an index) means a reordered or partially-filled reply can
 *   never be applied to the wrong caption.
 * @property text the ORIGINAL text. Never the corrected one — the
 *   preview needs both sides.
 */
data class CorrectionInputSegment(
    val id: String,
    val text: String
)

/**
 * A contiguous run of segments sent in one request.
 *
 * @property index 0-based batch number, for progress and retry.
 * @property segments the segments to correct, in subtitle order.
 * @property contextBefore the tail of the preceding segments, sent as
 *   read-only context. Included because Persian homophones
 *   (خواهر/خاهر, استنباط/استنبات) can only be resolved from the
 *   surrounding sentence, and a segment is often a single clause.
 * @property contextAfter the head of the following segments, same role.
 */
data class CorrectionBatch(
    val index: Int,
    val segments: List<CorrectionInputSegment>,
    val contextBefore: String? = null,
    val contextAfter: String? = null
)

/** One corrected segment, exactly as the model returned it. */
data class CorrectedSegment(val id: String, val text: String)

/**
 * Why a batch failed. The UI turns this into one actionable Persian
 * sentence; the retry logic uses it to decide whether continuing with
 * the remaining batches is even worth the quota.
 */
enum class CorrectionFailureKind {
    /** No saved key, or the key was rejected (401/403). */
    AUTH,

    /** The chosen model is not available on this account (400/404). */
    MODEL_UNAVAILABLE,

    /** HTTP 429 — stop, do not hammer. */
    RATE_LIMITED,

    /** 5xx. */
    SERVER,

    /** Timeout / DNS / no connectivity. */
    NETWORK,

    /** The reply was not the requested JSON, or failed validation. */
    MALFORMED,

    /** Any other non-2xx. */
    UNKNOWN
}

/** Outcome of one batch request. */
sealed interface CorrectionBatchOutcome {
    data class Corrected(val segments: List<CorrectedSegment>) : CorrectionBatchOutcome

    data class Failed(
        val kind: CorrectionFailureKind,
        /** Server-supplied or locally-generated detail. Never the API key. */
        val detail: String
    ) : CorrectionBatchOutcome
}

/**
 * Result of a whole correction run.
 *
 * @property segments the corrected text, keyed by overlay id. Contains
 *   an entry ONLY for segments that were actually corrected; a failed
 *   batch contributes nothing, so the applier leaves those overlays
 *   untouched (the brief's "preserve the original text for that
 *   batch").
 * @property failedBatches batch indices that failed, for retry.
 * @property failureMessage the first failure's message, or null when
 *   every batch succeeded.
 * @property failureKind the first failure's kind.
 * @property cancelled true when the user cancelled mid-run. Corrected
 *   batches collected before the cancel are still returned, but the
 *   caller discards them (a partial run is not presented as a full
 *   one).
 */
data class CorrectionRunResult(
    val segments: List<CorrectedSegment>,
    val failedBatches: List<Int> = emptyList(),
    val failureMessage: String? = null,
    val failureKind: CorrectionFailureKind? = null,
    val cancelled: Boolean = false,
    val totalBatches: Int = 0
)

/** One row of the review list: what the model wants to change. */
data class CorrectionItem(
    /** The `TextOverlay.id` this row would rewrite. */
    val overlayId: String,
    val original: String,
    val corrected: String,
    /** Word count of [original] / [corrected], for the karaoke note. */
    val wordsBefore: Int,
    val wordsAfter: Int
) {
    /**
     * True when applying this row has to re-time the karaoke words,
     * because the word count changed and the old per-word spans no
     * longer line up 1:1 with the new tokens.
     */
    val retimesWords: Boolean get() = wordsBefore > 0 && wordsBefore != wordsAfter
}

/**
 * The reviewable result of a run. Held in `EditorUiState` until the
 * user accepts or rejects it — nothing has been written to the
 * project yet.
 *
 * @property items ONLY the segments whose text actually changed.
 * @property unchangedCount how many segments the model returned
 *   unchanged (useful reassurance: "۴۲ مورد بررسی شد، ۷ مورد اصلاح").
 * @property failedBatches batches that failed; their overlays keep
 *   their original text.
 * @property modelId the model that produced this preview, shown in
 *   the dialog so the user knows what to blame.
 */
data class CorrectionPreview(
    val items: List<CorrectionItem>,
    val unchangedCount: Int,
    val failedBatches: List<Int> = emptyList(),
    val failureMessage: String? = null,
    val modelId: String = ""
) {
    val isEmpty: Boolean get() = items.isEmpty()
    val totalReviewed: Int get() = items.size + unchangedCount
    val retimedCount: Int get() = items.count { it.retimesWords }
}

/**
 * The correction stage's UI state machine.
 *
 * Mirrors the shape of `SubtitleGenerationState` (a sealed hierarchy
 * the card renders from), but is deliberately a SEPARATE channel: a
 * correction run must never make the Generate button look busy, and a
 * Whisper run must never make the correction row look busy.
 */
sealed interface AiCorrectionState {

    /** Nothing has run, or the last result was dismissed. */
    data object Idle : AiCorrectionState

    /** A run is in flight. */
    data class Running(
        val completedBatches: Int,
        val totalBatches: Int
    ) : AiCorrectionState {
        /** Real 0..1 fraction, or null when the batch count is unknown. */
        val fraction: Float?
            get() = if (totalBatches <= 0) null
            else (completedBatches.toFloat() / totalBatches).coerceIn(0f, 1f)

        val label: String
            get() = if (totalBatches <= 0) "در حال اصلاح زیرنویس…"
            else "در حال اصلاح زیرنویس… ($completedBatches از $totalBatches)"
    }

    /** A preview is waiting for the user's decision. */
    data class Review(val preview: CorrectionPreview) : AiCorrectionState

    /**
     * The run failed with nothing to review (e.g. the first batch was
     * rejected). The original subtitles are untouched.
     */
    data class Error(
        val message: String,
        val kind: CorrectionFailureKind,
        val retryable: Boolean = true
    ) : AiCorrectionState

    /** Corrections were committed to the editing state. */
    data class Applied(val changed: Int, val retimed: Int) : AiCorrectionState
}
