package com.aicreatorstudio.app.ai

import android.net.Uri

/**
 * Abstraction over a speech-to-text engine. The rest of the app
 * (SubtitleGenerator façade, EditorViewModel, SRT exporter, timeline)
 * talks only to this interface so a new engine can be added without
 * touching any consumer.
 *
 * Implementations:
 * - [WhisperEngine] — current production engine. Offline, real
 *   per-word timestamps from whisper.cpp's
 *   `whisper_full_get_word_t`, two multilingual checkpoints
 *   (Small / Medium) selectable from the UI.
 *
 * Engine selection: [SubtitleGenerator] holds a list of engines in
 * priority order and uses the first one whose [isAvailable] is true.
 * If no engine is available the façade returns a clear Persian
 * error instead of crashing.
 *
 * All long-running work (model download, audio decode, inference)
 * MUST run off the main thread. The façade ([SubtitleGenerator])
 * wraps these calls in `withContext(Dispatchers.IO)`.
 */
interface TranscriptionEngine {

    /**
     * Short stable id used in logs and `EditorUiState` snapshots.
     * Example: `"whisper"`.
     */
    val id: String

    /**
     * Human-readable name shown in the AI Subtitle card label so the
     * user knows which engine produced the captions.
     */
    val displayName: String

    /**
     * True when the engine can be used on the current device. This is
     * a fast, side-effect-free check: native libs loaded, model file
     * present, enough free RAM, etc. The façade calls this once on
     * each engine to pick the active one.
     *
     * Implementations should return `false` rather than throw if any
     * precondition is missing (e.g. model not yet downloaded counts as
     * "not available" until [initializeModel] succeeds).
     */
    val isAvailable: Boolean

    /**
     * Makes the engine ready for transcription. Downloads the model
     * if needed and loads it into memory.
     *
     * Must be idempotent — calling it twice is a no-op. The façade
     * serializes calls per engine so concurrent [initializeModel] on
     * the same engine is safe.
     *
     * Reports progress from 0f to 1f through [onProgress] so the UI
     * can show a download / load bar. Returns `Result.failure` with a
     * user-facing Persian message if the model cannot be obtained
     * (network error, bad checksum, unsupported device, …).
     */
    suspend fun initializeModel(onProgress: (Float) -> Unit): Result<Unit>

    /**
     * Transcribe the audio track of [videoUri] into a [Transcription]
     * (per-word timestamps + grouped segments).
     *
     * The engine owns its own audio decoding — different engines have
     * different sample-rate and channel requirements. Callers must
     * not pre-decode; pass the source `Uri` and let the engine pick
     * the right pipeline.
     *
     * Reports progress from 0f to 1f through [onProgress] (decoding
     * 0 → 0.5, inference 0.5 → 1f is the conventional split but not
     * enforced). Returns `Result.failure` with a Persian message on
     * any error.
     */
    suspend fun generateTranscription(
        videoUri: Uri,
        onProgress: (Float) -> Unit
    ): Result<Transcription>

    /**
     * Release native resources (loaded model, native handles). After
     * `release()` the engine must be re-initialised before it can
     * transcribe again.
     */
    fun release()
}
