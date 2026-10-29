package com.aicreatorstudio.app.ai

/**
 * Per-tool UI state shown by the dock sub-panels. The host
 * ([EditorViewModel]) keeps a `Map<String, AiToolState>` indexed by
 * [AiToolEngine.id] in `EditorUiState.aiTools`.
 *
 * Mirrors the lifecycle the user requested:
 *
 *   Model Not Installed → Download Model → Verify Model → Install Model → Ready
 *   Airplane Mode → AI Tool → Processing → Result
 *
 * The state machine is deliberately simple — the UI only ever needs
 * the current status + progress + optional error / summary text.
 */
data class AiToolState(
    val toolId: String,
    val status: AiToolStatus = AiToolStatus.Idle,
    /** 0f..1f. Drives the linear progress bar under the Generate button. */
    val progress: Float = 0f,
    /** Optional user-facing message — short Persian error or summary text. */
    val message: String? = null,
    /** True while the engine has a job running and `cancel()` is allowed. */
    val cancellable: Boolean = false
)

/**
 * Coarse-grained status of an AI tool. The UI maps each value to a
 * specific colour + icon. Transitions:
 *
 *   Idle → ModelMissing → Downloading(p) → Verifying → Ready → Processing(p) → Completed
 *                                  ↓                       ↓
 *                                Failed(msg)              Failed(msg) / Cancelled
 */
enum class AiToolStatus {
    /** No model required and engine is ready to run. */
    Ready,
    /** No job running. The Generate button is enabled. */
    Idle,
    /** The selected model is not on disk. Show "Download Model" button. */
    ModelMissing,
    /** Downloading the model. progress = 0..0.8. */
    Downloading,
    /** Verifying the downloaded bytes (sha256). progress = 0.8..0.95. */
    Verifying,
    /** Installing the model into the engine's directory. progress = 0.95..1.0. */
    Installing,
    /** Running inference on the clip. progress = 0..1. */
    Processing,
    /** Job finished cleanly. The UI shows the toast + result. */
    Completed,
    /** User cancelled. message holds "Cancelled". */
    Cancelled,
    /** Job failed. message holds the user-facing reason. */
    Failed
}