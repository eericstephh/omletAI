package com.aicreatorstudio.app.viewmodel

import androidx.compose.runtime.Stable
import com.aicreatorstudio.app.export.ExportConfig
import com.aicreatorstudio.app.export.ExportFrame
import com.aicreatorstudio.app.export.ExportQuality
import com.aicreatorstudio.app.export.ExportState
import com.aicreatorstudio.app.export.VideoExportEngine
import com.aicreatorstudio.app.model.EditorUiState
import com.aicreatorstudio.app.model.CanvasBackground
import com.aicreatorstudio.app.model.CanvasPreset
import com.aicreatorstudio.app.model.VideoProject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns the export lifecycle: start, cancel, and progress observation.
 * Delegates to [VideoExportEngine] for the actual Media3 Transformer work.
 */
@Stable
class ExportDelegate(
    private val state: MutableStateFlow<EditorUiState>,
    private val exportEngine: VideoExportEngine,
    private val scope: CoroutineScope
) {
    /**
     * Kick off an export. Progress is observed via [EditorUiState.exportState].
     */
    fun onExportClicked(
        quality: ExportQuality = ExportQuality.HIGH_1080P,
        baseName: String = state.value.project?.name.orEmpty()
    ) {
        val s = state.value
        val project = s.project ?: run {
            state.update { it.copy(exportState = ExportState.Failed("No project loaded.")) }
            return
        }
        // Sync overlays that may lag behind the UI state.
        val exportProject = if (project.textOverlays != s.textOverlays) {
            project.copy(textOverlays = s.textOverlays)
        } else project
        val preset = s.canvasPreset
        // 2026-09-30 — resolve the frame from the SOURCE for FREE rather
        // than from the quality tier. `ExportQuality` is defined landscape,
        // so `quality.widthPx/heightPx` framed a FREE project 16:9 whatever
        // had been imported. See `ExportFrame.resolveOutputSize`.
        val referenceClip = exportProject.clips.firstOrNull { !it.isAudioOnly }
        val (outW, outH) = ExportFrame.resolveOutputSize(
            canvasPreset = preset,
            sourceWidthPx = referenceClip?.width ?: 0,
            sourceHeightPx = referenceClip?.height ?: 0,
            qualityWidthPx = quality.widthPx,
            qualityHeightPx = quality.heightPx
        )
        val config = ExportConfig(
            quality = quality,
            outputBaseName = baseName,
            outputWidthPx = outW,
            outputHeightPx = outH,
            canvasPreset = preset,
            canvasBackground = s.canvasBackground
        )
        state.update { it.copy(exportState = ExportState.Preparing) }
        val flow = exportEngine.startExport(exportProject, config)
        scope.launch {
            flow.collect { newState -> state.update { it.copy(exportState = newState) } }
        }
    }

    /** Cancel any in-flight export. */
    fun onExportCancelClicked() {
        scope.launch { exportEngine.cancel() }
    }

    /** Reset the export result after the user dismisses the dialog. */
    fun consumeExportResult() {
        state.update { it.copy(exportState = ExportState.Idle) }
    }
}
