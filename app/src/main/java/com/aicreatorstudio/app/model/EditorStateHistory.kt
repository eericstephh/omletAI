package com.aicreatorstudio.app.model

/**
 * Applies the **document** portion of a history [snapshot] on top of the
 * live [current] editor state, leaving every live / volatile field
 * untouched.
 *
 * ## Why this exists
 *
 * The undo stack stores whole [EditorUiState] values. Assigning one back
 * wholesale also restored transport, export, AI-job and one-shot-trigger
 * fields, which produced stale-state bugs:
 *
 * - undoing while paused flipped the transport back to "playing";
 * - undoing during an export reported the export as finished while the
 *   Media3 `Transformer` was still running;
 * - a one-shot trigger (`pendingSeekMs`, `srtExportTrigger`, …) that had
 *   already been consumed could be resurrected and replayed.
 *
 * ## What is restored (the document)
 *
 * [EditorUiState.project] (clips, audio clips, captions, overlays, canvas
 * preset / background), the per-clip edit mirrors (`brightness`,
 * `contrast`, `saturation`, `rotation`, `playbackSpeed`, `trimStartMs`,
 * `trimEndMs`), `textOverlays`, `overlays`, the selection ids,
 * `selectedEffect`, `colorGrading`, `canvasPreset`, `canvasBackground`,
 * `upscale`, `selectedAnimation` and the AI-subtitle settings.
 *
 * ## What is preserved (live / volatile)
 *
 * Transport, export + AI-job progress, one-shot triggers, and transient
 * UI/viewport state — see the `current.*` assignments below.
 *
 * This is a pure function so the merge rules can be unit-tested without
 * constructing the ViewModel (which needs Android + Dagger).
 */
fun restoreDocumentFrom(
    snapshot: EditorUiState,
    current: EditorUiState
): EditorUiState = snapshot.copy(
    // --- live transport ---
    isPlaying = current.isPlaying,
    playbackPositionMs = current.playbackPositionMs,
    durationMs = current.durationMs,
    isLoading = current.isLoading,
    errorMessage = current.errorMessage,
    volume = current.volume,
    isMuted = current.isMuted,
    // --- in-flight export / AI work ---
    exportState = current.exportState,
    upscaleExportState = current.upscaleExportState,
    upscaleProgress = current.upscaleProgress,
    upscaleEtaMs = current.upscaleEtaMs,
    selectedUpscaleProfile = current.selectedUpscaleProfile,
    aiTools = current.aiTools,
    subtitleGenerationState = current.subtitleGenerationState,
    aiModelStatuses = current.aiModelStatuses,
    aiModelDownloadProgress = current.aiModelDownloadProgress,
    activeTranscriptionEngine = current.activeTranscriptionEngine,
    ocrResult = current.ocrResult,
    // PHASE 1 — silence-cut panel state is live AI-job UI (analyze →
    // preview → apply), never document: undo must restore clips while
    // leaving the panel where the user left it.
    autoSilenceCutState = current.autoSilenceCutState,
    // PHASE 4 — same volatility for the voice-enhancement preview.
    voiceEnhanceState = current.voiceEnhanceState,
    // PHASE 5 — same volatility for the smart-reframe preview.
    reframeState = current.reframeState,
    // PHASE 6 — same volatility for the beat-sync suggestions.
    beatSyncState = current.beatSyncState,
    // PHASE 7 — same volatility for highlight previews.
    highlightState = current.highlightState,
    // --- one-shot triggers (must never be replayed) ---
    pendingSeekMs = current.pendingSeekMs,
    pendingScrubSeekMs = current.pendingScrubSeekMs,
    pendingRestoreSeekMs = current.pendingRestoreSeekMs,
    srtExportTrigger = current.srtExportTrigger,
    srtImportTrigger = current.srtImportTrigger,
    srtImportState = current.srtImportState,
    matteExportTrigger = current.matteExportTrigger,
    // --- transient UI / viewport ---
    isScrubbing = current.isScrubbing,
    isFullscreen = current.isFullscreen,
    toolPanel = current.toolPanel,
    timelineVerticalScrollPx = current.timelineVerticalScrollPx,
    focusedTrackKind = current.focusedTrackKind,
    visibleTrackKinds = current.visibleTrackKinds,
    pixelsPerMs = current.pixelsPerMs,
    isTextEditMode = current.isTextEditMode,
    isTrimMode = current.isTrimMode,
    activeSubtitleId = current.activeSubtitleId,
    activeWordId = current.activeWordId,
    mediaMissingClips = current.mediaMissingClips,
    // --- history flags are owned by the caller, not the snapshot ---
    canUndo = current.canUndo,
    canRedo = current.canRedo
)
