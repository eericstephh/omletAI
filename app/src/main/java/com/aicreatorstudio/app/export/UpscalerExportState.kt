package com.aicreatorstudio.app.export

import android.net.Uri

/**
 * Sealed state of an in-flight AI upscale / enhance export, exposed by
 * [UpscalerExportEngine] as a flow. The progress UI binds to this
 * directly via `EditorUiState.upscaleExportState`.
 *
 * The three numbered stages mirror the brief's "AI Enhancing Frames…"
 * → "Reconstructing High-Res Details…" → "Finalizing 4K Video…"
 * sequence. Each stage carries its own 0f..1f progress so the UI
 * can animate smoothly across the handoff.
 */
sealed interface UpscalerExportState {
    /** No upscale export is running and none has finished. */
    data object Idle : UpscalerExportState

    /** Stage 1 — decoding source frames, running per-frame enhancement (CAS / TFLite). */
    data class EnhancingFrames(val progress: Float) : UpscalerExportState {
        init { require(progress in 0f..1f) }
    }

    /** Stage 2 — super-resolution / Lanczos resampling pass. */
    data class ReconstructingDetails(val progress: Float) : UpscalerExportState {
        init { require(progress in 0f..1f) }
    }

    /** Stage 3 — encoding the output to disk. */
    data class Finalizing4K(val progress: Float) : UpscalerExportState {
        init { require(progress in 0f..1f) }
    }

    /**
     * Engine is preparing inputs (e.g. resolving MediaStore URIs,
     * downloading a model if not cached). Reported before any of the
     * three numbered stages start.
     */
    data object Preparing : UpscalerExportState

    /**
     * Export completed successfully.
     *
     * [outputUri] is `null` only in unit tests that exercise this data
     * class without an Android framework; the engine always supplies
     * a real `content://` or `file://` URI.
     */
    data class Succeeded(
        val outputUri: Uri?,
        val fileName: String,
        val fileSizeBytes: Long,
        val durationMs: Long
    ) : UpscalerExportState

    /** Export failed. [message] is user-facing; [cause] is the underlying throwable, if any. */
    data class Failed(val message: String, val cause: Throwable? = null) : UpscalerExportState

    /** User cancelled the export. */
    data object Cancelled : UpscalerExportState

    companion object {
        /**
         * Display label for the three numbered stages, in the order
         * the progress dialog will show them. Used by
         * [com.aicreatorstudio.app.ui.screens.editor.UpscaleProgressDialog].
         */
        val stageLabels: List<String> = listOf(
            "AI Enhancing Frames…",
            "Reconstructing High-Res Details…",
            "Finalizing 4K Video…"
        )
    }
}
