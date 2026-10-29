package com.aicreatorstudio.app.ai

/**
 * PHASE B3 — state machine for the AI Subtitle generation pipeline.
 *
 * Replaces the free-form `EditorUiState.subtitleStatus: String?` +
 * `subtitleProgress: Float` pair that was used pre-B3. The UI now
 * binds directly to this state so the visible UI always reflects
 * the actual pipeline phase, never a hand-written string.
 *
 * ## Why a sealed class (not an enum)
 *
 * [Completed] and [Error] need to carry a payload (the success
 * summary or the failure message). A sealed class lets each
 * variant carry its own data while still being exhaustively
 * pattern-matchable by the UI and the ViewModel.
 *
 * ## Honesty about progress
 *
 * The brief explicitly forbids "fake progress values." The
 * `progressFraction: Float?` field is therefore nullable:
 *   - During [DecodingAudio], [UploadingAudio], [Transcribing],
 *     [ProcessingByWhisper] the engine emits real 0..1 progress,
 *     so the field is non-null.
 *   - During [Preparing], [CreatingSubtitles], [Cancelling], and
 *     all terminal states the field is `null` and the UI must
 *     show either a stage label or an indeterminate indicator
 *     — **never** a fake percentage.
 *
 * ## Cloud pipeline states (2026-09-12)
 *
 * The Whisper Cloud (Groq) path has distinct, REAL phases:
 *   [DecodingAudio]  — extracting the audio track (remux, real %)
 *   [UploadingAudio] — POSTing the audio bytes (real bytes-sent %)
 *   [ProcessingByWhisper] — Groq is running whisper-large-v3-turbo
 *     (no client-side fraction exists — indeterminate, no fake %)
 *
 * ## Cancellation
 *
 * Cancellation is honored at whisper.cpp's 30-second window
 * boundary (see `WhisperEngine.generateTranscription`, which calls
 * `coroutineContext[Job]?.ensureActive()` between windows) and
 * between uploaded chunks on the cloud path. The state sequence
 * on cancel is:
 *
 *   running state → Cancelling → Idle (no partial commit)
 */
sealed class SubtitleGenerationState {

    /** Nothing happening. The Generate button is enabled. */
    object Idle : SubtitleGenerationState()

    /**
     * The pipeline is starting up — selecting clip, computing the
     * timeline offset, and (if necessary) installing the selected
     * Whisper model. The progress fraction is whatever the
     * installer reports (0..1) or `null` while it is copying
     * the bundled asset.
     */
    data class Preparing(
        val progressFraction: Float? = null
    ) : SubtitleGenerationState()

    /**
     * The first audio track is being extracted (offline path:
     * MediaCodec decode; cloud path: remux into .m4a). Real
     * progress: sample PTS / track duration.
     */
    data class DecodingAudio(
        val progressFraction: Float? = null
    ) : SubtitleGenerationState()

    /**
     * 2026-09-12 CLOUD — the extracted audio file's BYTES are
     * being POSTed to the Groq API. Real progress:
     * bytes written / file size (fixed-length streaming).
     */
    data class UploadingAudio(
        val progressFraction: Float? = null
    ) : SubtitleGenerationState()

    /**
     * Real whisper.cpp inference is running in 30-second windows
     * (offline path). The engine reports real progress on every
     * window.
     */
    data class Transcribing(
        val progressFraction: Float? = null
    ) : SubtitleGenerationState()

    /**
     * 2026-09-12 CLOUD — Groq has the audio and
     * `whisper-large-v3-turbo` is running server-side.
     * No client-side fraction can exist here, so the UI shows an
     * indeterminate indicator — never a fake percentage.
     */
    object ProcessingByWhisper : SubtitleGenerationState()

    /**
     * The B2 mapper is converting the Whisper
     * [Transcription] into a list of [com.aicreatorstudio.app.model.TextOverlay]s.
     * This is fast (pure-data map over a few hundred elements) so
     * the progress fraction is always `null`.
     */
    object CreatingSubtitles : SubtitleGenerationState()

    /**
     * The user pressed Cancel. The in-flight whisper.cpp window
     * (or upload chunk) may still be finishing; once it returns
     * the state advances to [Idle] without committing any overlays.
     */
    object Cancelling : SubtitleGenerationState()

    /**
     * The generation finished successfully. [overlayCount] is the
     * number of subtitle clips the pipeline just created.
     */
    data class Completed(
        val overlayCount: Int
    ) : SubtitleGenerationState()

    /**
     * The generation failed. [message] is the user-facing Persian
     * message (kept short — the underlying stack trace is logged
     * to logcat but never rendered). The pipeline is safe to
     * re-run after the user dismisses the error.
     */
    data class Error(
        val message: String
    ) : SubtitleGenerationState()

    // ---- Convenience accessors for the UI ----

    /**
     * True if the state is a transient "running" state. The
     * Generate button must be disabled and the Cancel button
     * shown whenever this is true.
     */
    val isRunning: Boolean
        get() = this is Preparing ||
            this is DecodingAudio ||
            this is UploadingAudio ||
            this is Transcribing ||
            this is ProcessingByWhisper ||
            this is CreatingSubtitles ||
            this is Cancelling

    /**
     * True if the state is a terminal "done" state — either
     * [Completed], [Error], or [Idle] (which is the resting
     * state). A new generation is only allowed from a terminal
     * state.
     */
    val isTerminal: Boolean
        get() = this is Idle || this is Completed || this is Error

    /**
     * The real 0..1 progress fraction for the current state, or
     * `null` if the state does not expose a real number (idle,
     * completed, error, cancel, or a no-progress phase).
     * The UI binds a `LinearProgressIndicator` to this value and
     * **only** shows a progress bar when the value is non-null.
     * The brief forbids fake progress.
     */
    val currentProgressFraction: Float?
        get() = when (this) {
            is Preparing -> this.progressFraction
            is DecodingAudio -> this.progressFraction
            is UploadingAudio -> this.progressFraction
            is Transcribing -> this.progressFraction
            Idle, CreatingSubtitles, ProcessingByWhisper, Cancelling,
            is Completed, is Error -> null
        }

    /**
     * True when the current phase has no real fraction — the UI
     * must show an INDETERMINATE bar (not a fake percentage).
     */
    val isIndeterminate: Boolean
        get() = this is ProcessingByWhisper

    /**
     * User-facing label for the current state. The UI uses this
     * for the small caption under the progress indicator. All
     * strings are in Persian to match the rest of the editor.
     *
     * 2026-09-08 — the percentage shown is the PIPELINE-GLOBAL
     * fraction (the ViewModel pre-weights each phase onto
     * 0..15 / 15..35 / 35..100), so the number under the label and
     * the bar above it always agree and the whole job reads as one
     * continuous 0→100% run.
     */
    val userMessage: String
        get() = when (this) {
            Idle -> "آماده"
            is Preparing -> if (progressFraction != null) {
                "در حال آماده‌سازی... ${(progressFraction * 100).toInt()}٪"
            } else {
                "در حال آماده‌سازی..."
            }
            is DecodingAudio -> if (progressFraction != null) {
                "در حال استخراج صدا... ${(progressFraction * 100).toInt()}٪"
            } else {
                "در حال استخراج صدا..."
            }
            is UploadingAudio -> if (progressFraction != null) {
                "در حال ارسال فایل صوتی... ${(progressFraction * 100).toInt()}٪"
            } else {
                "در حال ارسال فایل صوتی..."
            }
            is Transcribing -> if (progressFraction != null) {
                "در حال رونویسی... ${(progressFraction * 100).toInt()}٪"
            } else {
                "در حال رونویسی..."
            }
            ProcessingByWhisper -> "در حال پردازش توسط Whisper AI..."
            CreatingSubtitles -> "در حال ساخت زیرنویس..."
            Cancelling -> "در حال لغو..."
            is Completed -> "$overlayCount زیرنویس ساخته شد"
            is Error -> message
        }
}
