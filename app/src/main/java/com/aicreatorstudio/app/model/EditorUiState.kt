package com.aicreatorstudio.app.model

import com.aicreatorstudio.app.ai.AiModelStatus
import com.aicreatorstudio.app.ai.AiToolState
import com.aicreatorstudio.app.ai.SubtitleGenerationState
import com.aicreatorstudio.app.ai.WhisperModelSpec
import com.aicreatorstudio.app.ai.upscale.UpscaleProfile
import com.aicreatorstudio.app.ai.upscale.UpscaleProgress
import com.aicreatorstudio.app.ai.vad.SilenceDetectionConfig
import com.aicreatorstudio.app.ai.vad.SilenceSegment
import com.aicreatorstudio.app.export.ExportState
import com.aicreatorstudio.app.export.UpscalerExportState

/**
 * UI state for the video editor screen.
 *
 * Holds all state needed for the timeline, playback, and clip management.
 * This is deliberately a data class with no logic — the ViewModel owns
 * all state transitions and business logic.
 */
data class EditorUiState(
    val project: VideoProject? = null,
    val isPlaying: Boolean = false,
    val playbackPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    // 2026-10-05 — add-clip flow (the "+" beside the timeline). Both are
    // transient UI-process fields: EditorStateJson persists document
    // fields only, so they never survive process death — correct, because
    // the underlying clip either lands (persisted with the project) or
    // fails (error shown and dismissed).
    val isAddingMedia: Boolean = false,
    val addMediaError: String? = null,
    // 2026-10-13 — Extract Audio from Video (استخراج صدا از ویدیو). A
    // dedicated loading flag rather than isAddingMedia: extraction runs
    // concurrently with normal editing (it is 1-2 s of disk work), so the
    // UI shows a lightweight non-blocking row instead of the blocking
    // add-media state. Transient UI-process fields like the pair above —
    // the extracted clip either lands (persisted with the project) or the
    // error is shown and dismissed.
    val isExtractingAudio: Boolean = false,
    val extractAudioError: String? = null,
    // PHASE A (Persistence Integrity) — transient, one-shot notice for
    // a core editing action that was refused (e.g. deleting the last
    // clip). Same dismissible-banner pattern as `addMediaError` so a
    // guard never reads as a silent no-op.
    val editorNotice: String? = null,
    val volume: Float = 1f,
    val isMuted: Boolean = false,
    // Video Effects (per-clip)
    val brightness: Float = 1f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val playbackSpeed: Float = 1f,
    val rotation: Int = 0,
    // Trim
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val isTrimMode: Boolean = false,
    // Text Overlays
    val textOverlays: List<TextOverlay> = emptyList(),
    val selectedTextId: String? = null,
    val isTextEditMode: Boolean = false,
    /**
     * 2026-10-13 — the user's imported custom fonts (.ttf / .otf),
     * newest first. Not part of the document: these are device-local
     * assets owned by [com.aicreatorstudio.app.data.font.CustomFontRepository],
     * so they must NOT be persisted into the project JSON. A style
     * references one by the opaque `"custom:<id>"` string stored in
     * `SubtitleStyle.fontFamily` / `TextOverlay.fontFamily`; if the
     * font is later deleted the reference degrades to the default
     * Persian font rather than breaking the project.
     */
    val customFonts: List<CustomFont> = emptyList(),
    /**
     * 2026-10-13 — transient, one-shot confirmation banner. Same
     * dismissible pattern as [editorNotice] but reserved for POSITIVE
     * outcomes (a font import that succeeded), so a confirmation and a
     * failure never contend for the same field.
     */
    val editorConfirmation: String? = null,
    // Selection
    val selectedClipId: String? = null,
    val selectedTrackType: TrackType = TrackType.VIDEO,
    /**
     * 2026-09-06 — the currently-SELECTED audio clip (Video Audio /
     * Music / SFX lane). Selecting an audio clip on the timeline
     * highlights its tile and opens the Volume panel so the user can
     * mute / fade / re-volume just that audio object.
     */
    val selectedAudioClipId: String? = null,
    // Overlays
    val overlays: List<VideoOverlay> = emptyList(),
    /**
     * 2026-10-13 — the currently-SELECTED picture-in-picture overlay.
     *
     * The mask panel and the preview gesture layer both operate on this
     * id. It is deliberately **independent of [selectedClipId]**: a
     * masked PiP belongs to a clip, but selecting the PiP to shape it is
     * a different act from selecting the clip underneath it, and the
     * mask panel must stay open while the user drags the PiP around.
     *
     * Transient UI state, never persisted — a reopened project starts
     * with no overlay selected.
     */
    val selectedOverlayId: String? = null,
    // ---- Project restoration (v5) ----
    /**
     * Clips whose backing media could not be opened at load time (dead
     * photo-picker grants, deleted files). When non-empty the screen
     * shows the "Media Missing / Relink" banner instead of failing
     * silently with a black preview. Each entry is the clip itself so
     * the UI can show [VideoClip.fileName] and the ViewModel can
     * rewrite its URI when the user relinks.
     */
    val mediaMissingClips: List<VideoClip> = emptyList(),
    /**
     * Playhead position (ms) to seek to once the restored media item
     * first reaches `STATE_READY` after reopening a project. Consumed
     * exactly once by the screen; null when there is nothing to restore
     * (e.g. a brand-new import).
     */
    val pendingRestoreSeekMs: Long? = null,
    // Famous video effect selection
    val selectedEffect: VideoEffect = VideoEffect.NONE,
    // ---- 2026-08-29 Lumetri color grading ----
    /**
     * Full Lumetri-style grading state. When no clip is selected this
     * acts as the "default" preview grade. When a clip *is* selected
     * the per-clip value (see [VideoClip.colorGrading]) wins.
     */
    val colorGrading: ColorGrading = ColorGrading.Identity,
    // AI Subtitle generation status
    /**
     * PHASE B3 — sealed state machine that drives the AI Subtitle
     * card. The UI binds directly to this state; the previous
     * free-form `subtitleStatus: String?` + `subtitleProgress: Float`
     * pair was retired because it couldn't represent a phase
     * (Preparing / Decoding / Transcribing / Cancelling) without
     * a fake progress value, and the brief forbids fake progress.
     */
    val subtitleGenerationState: SubtitleGenerationState = SubtitleGenerationState.Idle,
    /**
     * Per-model install status for every `WhisperModelSpec`
     * the AI Subtitle card exposes. The ViewModel recomputes
     * this when the project opens and after each model install
     * attempt finishes; the card reads it to label each radio
     * row (Ready / Download / Downloading / Failed). The brief
     * explicitly forbids showing a model as READY when it isn't
     * actually installed and SHA-verified.
     */
    val aiModelStatuses: Map<WhisperModelSpec, AiModelStatus> = emptyMap(),
    /**
     * Which Whisper checkpoint the user has picked in the AI
     * Subtitle card. The static default is
     * [WhisperModelSpec.SMALL]; the
     * `EditorViewModel` overrides this on init with
     * [WhisperModelSpec.defaultFor] based on the device's free
     * RAM. Small is the auto-default for every device;
     * Large-v3 is opt-in only and gated by
     * [com.aicreatorstudio.app.ai.DeviceCapabilityDetector].
     */
    val selectedWhisperModel: WhisperModelSpec = WhisperModelSpec.Default,
    /**
     * 2026-10-10 — true when a saved Groq API key makes the
     * user-key cloud engine usable. Surfaced so the Subs card can
     * show the REAL cloud readiness instead of always showing
     * "Cloud" ready, and so a missing key is explained before the
     * user taps Generate.
     */
    val hasGroqApiKey: Boolean = false,
    /**
     * 2026-09-06 — the spoken-language hint for AI subtitles. The
     * Medium / Large-v3 checkpoints are multilingual (99 languages);
     * this selection tells whisper.cpp which language to expect so
     * detection time drops and word timings stay stable. Default
     * PERSIAN (the app's original single-language behaviour).
     */
    val selectedSubtitleLanguage: com.aicreatorstudio.app.ai.SubtitleLanguage =
        com.aicreatorstudio.app.ai.SubtitleLanguage.PERSIAN,
    /**
     * 2026-09-09 — subtitle decoder ACCURACY MODE (Fast / Balanced /
     * Accurate). Real whisper.cpp parameter sets (beam size +
     * temperature fallback); persisted in Settings, defaulted to
     * BALANCED.
     */
    val subtitleAccuracyMode: com.aicreatorstudio.app.ai.WhisperEngine.AccuracyMode =
        com.aicreatorstudio.app.ai.WhisperEngine.AccuracyMode.BALANCED,
    /**
     * 2026-09-15 — Premiere-style "words per caption" for AI
     * subtitles. When > 0, the segmenter chunks every caption to at
     * most this many words (e.g. 3 or 5) before the readability
     * caps run. 0 = off (chars/duration/CPS rules alone shape the
     * captions). Persisted via SettingsRepository; restored in
     * [com.aicreatorstudio.app.viewmodel.EditorViewModel.restoreAiState].
     */
    val subtitleWordsPerCaption: Int = 0,
    /**
     * 2026-09-19 PHASE 4 — JSON template catalog state for the
     * Subtitle Studio picker. LOADING until the ViewModel's
     * one-shot IO load finishes; READY carries the mapped engine
     * templates (17 JSON entries); EMPTY is the never-crash
     * fallback (asset missing/unreadable). The picker renders a
     * stable skeleton while LOADING so no flicker occurs.
     */
    val jsonCatalogStatus: com.aicreatorstudio.app.model.template.JsonCatalogStatus =
        com.aicreatorstudio.app.model.template.JsonCatalogStatus.LOADING,
    /** Mapped JSON catalog ([JsonTemplateMapper]), empty until READY. */
    val jsonCatalogTemplates: List<SubtitleTemplate> = emptyList(),
    /**
     * 2026-09-09 — the user's custom dictionary raw text (one term
     * per line) for the Subs card's dictionary editor. Populated by
     * the ViewModel at restore time and kept in sync on save.
     */
    val customDictionaryText: String = "",
    /**
     * 2026-09-06 — per-model download progress (0..1) keyed by the
     * model's catalog id (e.g. "whisper-medium"). An entry exists
     * ONLY while that model is downloading; the AI Subtitle card
     * renders a real progress bar for rows present in this map.
     */
    val aiModelDownloadProgress: Map<String, Float> = emptyMap(),
    /**
     * Display name of the [com.aicreatorstudio.app.ai.TranscriptionEngine]
     * that produced the most recent captions. Today this is always
     * the Whisper engine. The AI Subtitle card uses this to label
     * the generation button so the user knows which backend is
     * active. `null` before the first successful generation.
     */
    val activeTranscriptionEngine: String? = null,
    // When set, the player should seek here once (e.g. jump to first new subtitle)
    val pendingSeekMs: Long? = null,
    // ---- 2026-08-29 timeline sync ----
    /**
     * Live scrub seek. While the user is dragging the playhead we
     * publish a new value every drag frame; the screen consumes it and
     * calls `ExoPlayer.seekTo(ms, SEEK_PARAM_CLOSEST_SYNC)` which is
     * the fast-seek path that snaps to the nearest sync frame (vs the
     * default "precise" seek which can stutter on long clips).
     */
    val pendingScrubSeekMs: Long? = null,
    // Animations
    val selectedAnimation: ClipAnimation? = null,
    // Export
    /**
     * State of the in-app video exporter. The EditorViewModel is the
     * single source of truth here; the dialog and the export button bind
     * directly to this field.
     */
    val exportState: ExportState = ExportState.Idle,
    // Undo / Redo availability. Updated by EditorViewModel after every
    // history-mutating call so the toolbar can disable the buttons when
    // there is nothing to undo / redo.
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    // ---- Refactor 2026-08-28 ----
    /** True when the canvas is in fullscreen (video fills the whole screen). */
    val isFullscreen: Boolean = false,
    /** Pixels per millisecond on the timeline. Drives zoom and ruler density. */
    val pixelsPerMs: Float = 0.06f,
    /** Project resolution label, e.g. "1080p" — shown in the top bar badge. */
    val resolutionLabel: String = "1080p",
    /** Project frame rate label, e.g. "30 fps". */
    val frameRateLabel: String = "30 fps",
    /** The currently-open sub-panel, or null if none. */
    val toolPanel: ToolPanel? = null,
    // ---- 2026-08-28 sub-track refactor ----
    /**
     * Which track kinds are currently visible in the timeline. The Video
     * track is always shown when the project has clips; the others are
     * toggled by dock tools (Text, Subs, Audio, Overlay).
     */
    val visibleTrackKinds: Set<TrackKind> = setOf(TrackKind.VIDEO),
    /**
     * Which track kind the user is currently focused on (last clicked,
     * or the one that corresponds to the open sub-panel). Used so the
     * timeline can highlight the active track rail.
     */
    val focusedTrackKind: TrackKind? = null,
    // ---- 2026-08-28 canvas / transform / background refactor ----
    /**
     * Project canvas preset. Controls the aspect ratio and pixel size of
     * the preview viewport and the export output.
     *
     * 2026-09-30 — defaults to [CanvasPreset.FREE] ("track the source"),
     * not `WIDE_16_9`. This is the value the preview reads *before*
     * `loadProject()` has replaced it, so a hard-coded 16:9 here meant the
     * very first frame of the editor asserted a landscape canvas
     * regardless of what had been imported. `FREE` defers to the active
     * clip's own dimensions until the project says otherwise, which is the
     * only answer that cannot be wrong.
     */
    val canvasPreset: CanvasPreset = CanvasPreset.FREE,
    /**
     * How the editor fills the letterbox region inside the canvas.
     * Preview-only; export v1 always letterboxes black.
     */
    val canvasBackground: CanvasBackground = CanvasBackground.Black,
    /**
     * 2026-10-13 — video progress bar (timeline indicator / story timer).
     *
     * Mirrored from [VideoProject.progressBarSettings] when the project
     * loads and pushed back on every edit, exactly like
     * [canvasBackground]. It sits here as well as on the project because
     * the preview and the panel both read it from UI state, while the
     * export reads it from the project — and a document field like this is
     * automatically undo/redo-aware, since `restoreDocumentFrom` copies
     * the snapshot wholesale for anything it does not list as volatile.
     */
    val progressBarSettings: ProgressBarSettings = ProgressBarSettings(),
    // ---- 2026-08-29 timeline sync refactor ----
    /**
     * Pixel offset of the vertical scroll of the timeline track stack.
     * The horizontal time ruler and the playhead column stay pinned to
     * the visible viewport; only the stack of lanes scrolls. This lets
     * us have many track kinds (Video, Audio, Text, Subtitle, Overlay,
     * FX) stacked vertically while keeping the ruler in lock-step with
     * the playhead.
     */
    val timelineVerticalScrollPx: Float = 0f,
    /**
     * True while the user is actively dragging the playhead/scrubber.
     * Used by the playhead overlay to suspend auto-scroll so the
     * lane follows the finger instead of fighting it.
     */
    val isScrubbing: Boolean = false,
    /**
     * ID of the subtitle text-overlay currently highlighted by the
     * playhead. Computed continuously from `playbackPositionMs` and
     * the [textOverlays] list so the preview can render the active
     * word in a different style in real time.
     */
    val activeSubtitleId: String? = null,
    /**
     * ID of the [com.aicreatorstudio.app.ai.TranscribedWord] currently
     * being spoken inside the [activeSubtitleId] overlay. Recomputed
     * by the ViewModel on every playhead tick; the preview reads this
     * to drive karaoke-style word highlighting.
     */
    val activeWordId: String? = null,
    /**
     * When non-null, the screen should pop the system "Save as" file
     * picker seeded with this suggested SRT file name. The ViewModel
     * sets this when the user taps "Export SRT"; the screen launches
     * the SAF intent, receives the target URI, writes the file, and
     * then calls [EditorViewModel.consumeSrtExportTrigger] to clear
     * the trigger. This keeps the storage write off the main thread
     * and uses the official Android file/document APIs.
     */
    val srtExportTrigger: SrtExportTrigger? = null,
    /**
     * PHASE B4 — one-shot trigger pushed into the state when the
     * user taps "Import SRT". The screen launches the SAF
     * `ACTION_OPEN_DOCUMENT` picker (application/x-subrip + text/plain);
     * the resulting Uri is passed back to
     * [EditorViewModel.importSrtFromUri] which reads + parses +
     * appends TextOverlays to the project. Same shape and
     * consumption pattern as [srtExportTrigger].
     */
    val srtImportTrigger: SrtImportTrigger? = null,
    /**
     * PHASE B4 — the in-flight SRT import state machine. The
     * `EditPanel` reads this to render the "Importing…" /
     * "Reading…" / "Completed (12 cues)" status. Set by
     * [EditorViewModel.importSrtFromUri] and
     * [EditorViewModel.onImportSrtClicked]; cleared by
     * [EditorViewModel.consumeSrtImportState].
     */
    val srtImportState: com.aicreatorstudio.app.ai.SrtImportState =
        com.aicreatorstudio.app.ai.SrtImportState.Idle,
    // ---- AI Video Upscaler ----
    /**
     * Global default upscaler settings. When a clip is selected the
     * per-clip [VideoClip.upscale] value wins (mirroring how
     * [colorGrading] is handled). When no clip is selected this is
     * the live preview state.
     */
    val upscale: UpscalerSettings = UpscalerSettings.Identity,
    /**
     * State of the in-app AI upscaler exporter. Separate from
     * [exportState] so a normal export and an upscale export can run
     * independently without one overwriting the other.
     */
    val upscaleExportState: UpscalerExportState = UpscalerExportState.Idle,
    // ---- 2026-10-21 Voiceover («نریشن») ----
    /**
     * The recorder's state: idle, recording, holding a captured take, or
     * failed. The panel renders its button and meter straight from this,
     * so a record button can never be shown while a recording is
     * already running.
     */
    val voiceoverState: VoiceoverState = VoiceoverState.Idle,
    /**
     * The URI of a captured take that is waiting to be placed on the
     * timeline.
     *
     * Kept beside [voiceoverState] so the panel can offer "add to
     * timeline" even after the user closed and reopened the sub-panel:
     * the take is on disk and app-owned, so it survives.
     */
    val pendingVoiceoverUri: String? = null,
    // ---- 2026-09-01 AI tools (Background Remove / Auto Frame / Voice Clean) ----
    /**
     * Live state of every registered AI tool indexed by
     * [com.aicreatorstudio.app.ai.AiToolEngine.id]. Updated by
     * `EditorViewModel.runAiTool` / `cancelAiTool`. The dock
     * sub-panels read this to render the progress bar, status
     * message, and Download / Generate / Cancel buttons. When a
     * tool's id is absent from the map the UI treats it as `Idle`
     * so legacy callers don't need to know the full set of ids.
     */
    val aiTools: Map<String, AiToolState> = emptyMap(),
    // ---- 2026-09-28 Motion Tracking ----
    /**
     * The ROI the user drew for Motion Tracking, normalized 0..1
     * against the *video frame* (not the editor container). `null`
     * until the user enters selection mode and drags a box.
     *
     * Deliberately kept out of [aiTools]: it is a UI input, not engine
     * state, and it must survive a `Cancel`/`Reset` so the user does
     * not have to re-draw the box after a failed run.
     */
    val pendingTrackingRoi: android.graphics.RectF? = null,
    /**
     * True while [com.aicreatorstudio.app.ui.screens.editor.RoiSelectorOverlay]
     * should be shown over the video canvas. The editor drops into
     * selection mode on
     * `enterTrackingSelection()` — which also pauses playback and
     * freezes the displayed frame — and leaves it on
     * `exitTrackingSelection()`.
     */
    val trackingSelectionActive: Boolean = false,
    // ---- 2026-09-21 AI Auto Silence Cut ----
    /** Live state of the auto-silence-cut operation. */
    val autoSilenceCutState: AutoSilenceCutState = AutoSilenceCutState.Idle,
    // ---- PHASE 4 Local Voice Enhancement ----
    /** Live state of the voice-enhancement preview flow (volatile). */
    val voiceEnhanceState: VoiceEnhanceState = VoiceEnhanceState.Idle,
    // ---- PHASE 5 Local Smart Reframe ----
    /** Live state of the smart-reframe preview flow (volatile). */
    val reframeState: ReframeState = ReframeState.Idle,
    // ---- PHASE 6 Local Beat Sync ----
    /** Live state of the beat-sync suggestion flow (volatile). */
    val beatSyncState: BeatSyncState = BeatSyncState.Idle,
    // ---- PHASE 7 Smart Highlights ----
    /** Live state of the highlight detection flow (volatile). */
    val highlightState: HighlightState = HighlightState.Idle,
    /**
     * Suggested filename shown in the SAF dialog when exporting the
     * composite matte-mask PNG sequence (Background Remove only).
     * One-shot trigger consumed by the screen the same way
     * [srtExportTrigger] is.
     */
    val matteExportTrigger: MatteExportTrigger? = null,
    // ---- 2026-09-01 AI Video Upscaler (Fast / High Quality / Auto) ----
    /**
     * Which upscale profile the user picked from the bottom sheet.
     * `null` when the bottom sheet has never been opened (the
     * default state for a freshly-loaded project). The bottom sheet
     * uses this to set the selected card; the ViewModel reads it on
     * `onUpscaleSelected` to drive the engine.
     */
    val selectedUpscaleProfile: UpscaleProfile? = null,
    /**
     * Live progress of the in-flight AI upscale run, surfaced as a
     * [UpscaleProgress] from the engine. Separate from
     * [upscaleExportState] (the v1 enhancement export) so the two
     * pipelines don't trample each other when the user opens both
     * panels. `null` when no upscale is running.
     */
    val upscaleProgress: UpscaleProgress? = null,
    /**
     * Best-effort ETA in milliseconds based on the last few
     * progress ticks. The bottom sheet shows this under the
     * progress bar as "≈ 1m 24s remaining". `-1L` when the ETA
     * cannot be estimated yet (e.g. the first tick has just
     * landed). The ViewModel updates this as new ticks arrive.
     */
    val upscaleEtaMs: Long = -1L,
    // ---- 2026-09-10 AI OCR ----
    /**
     * Staged AI OCR output shown by the OCR panel: the frames the
     * engine analyzed, their detected lines (text + box +
     * confidence), and the applied commit state. `null` until the
     * first Generate completes; cleared by Reset.
     */
    val ocrResult: OcrPanelResult? = null,
    /**
     * Playback progress as a fraction 0f-1f.
     *
     * Clamped to the `[0f, 1f]` range so a stale or out-of-bounds
     * `playbackPositionMs` (e.g. a seek that briefly over-shoots the
     * project duration before `onDurationChanged` catches up, or a
     * negative value left over from a misconfigured restore) cannot
     * push the timeline progress bar past its end or into the
     * negative half-plane.
     */
) {
    /**
     * Playback progress as a fraction 0f-1f.
     *
     * Clamped to the `[0f, 1f]` range so a stale or out-of-bounds
     * `playbackPositionMs` (e.g. a seek that briefly over-shoots the
     * project duration before `onDurationChanged` catches up, or a
     * negative value left over from a misconfigured restore) cannot
     * push the timeline progress bar past its end or into the
     * negative half-plane.
     */
    val progress: Float
        get() {
            if (durationMs <= 0L) return 0f
            return (playbackPositionMs.toFloat() / durationMs.toFloat())
                .coerceIn(0f, 1f)
        }

    /** Formatted current time (MM:SS). Negative positions render as `00:00`. */
    val formattedPosition: String
        get() = formatTime(playbackPositionMs)

    /** Formatted total duration (MM:SS). Negative durations render as `00:00`. */
    val formattedDuration: String
        get() = formatTime(durationMs)

    /** Get selected clip */
    val selectedClip: VideoClip?
        get() = project?.clips?.find { it.id == selectedClipId }

    /**
     * Format [ms] as `MM:SS`. Negative inputs clamp to `00:00` so a
     * transient negative position (e.g. a seek race or a stale
     * restore value) can't surface as `"00:-1"` to the user.
     *
     * For videos longer than 60 minutes the minutes field keeps
     * growing (e.g. `75:30`) — callers that need HH:MM:SS for long
     * content should use a dedicated formatter.
     */
    private fun formatTime(ms: Long): String {
        val safeMs = ms.coerceAtLeast(0L)
        val totalSeconds = safeMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }
}

/**
 * Track type for timeline
 */
enum class TrackType {
    VIDEO,
    AUDIO,
    OVERLAY
}

/**
 * Identifier of the open sub-panel. The dock toggles this; the screen
 * resolves the right composable for the active panel.
 */
enum class ToolPanel {
    TRIM,
    SPEED,
    VOLUME,
    TEXT,
    /** Multi-line paragraph text block with position presets + drag. */
    PARAGRAPH,
    SUBTITLES,
    AUDIO,
    FILTERS,
    ADJUST,
    EFFECTS,
    CROP,
    BACKGROUND,
    OPACITY,
    OVERLAY,
    ANIMATIONS,
    TRANSITIONS,
    /** Project canvas (aspect ratio) — 9:16 / 16:9 / 1:1 / 4:5 / Free. */
    ASPECT_RATIO,
    /** Per-clip transform — Fit / Fill / Manual pan & zoom / Rotate / Flip. */
    TRANSFORM,
    /** AI Video Upscaler / Enhancer — preset, strength, before/after compare. */
    UPSCALER,
    /** AI Background Remove — chroma matting, replaceable background. */
    AI_MATTING,
    /** AI Auto Frame — landscape → portrait subject tracking. */
    AI_AUTOFRAME,
    /**
     * Motion Tracking — attach a text/sticker/blur to a user-drawn ROI
     * and follow it per frame. Distinct from [AI_AUTOFRAME]: Auto Frame
     * reframes the *canvas* to follow a subject, Motion Tracking moves
     * an *overlay* to follow whatever the user boxed.
     */
    AI_MOTION_TRACK,
    /** AI Voice Clean — RNNoise-style spectral denoising. */
    AI_VOICE_CLEAN,
    /**
     * PHASE 4 — Local Voice Enhancement: preset normalize + denoise
     * with stats/waveform preview before Apply. Distinct from
     * [AI_VOICE_CLEAN] (immediate process-and-commit); shares its
     * engine and export swap.
     */
    AI_VOICE_ENHANCE,
    /** AI OCR — on-device text extraction (Persian/Arabic/English). */
    AI_OCR,
    /** AI Auto Silence Cut — Silero VAD-based silence removal. */
    AI_SILENCE_CUT,
    /**
     * PHASE 5 — Local Smart Reframe: analyze (existing autoframe
     * engine) → simplified crop path → preview → apply. Distinct from
     * the immediate [AI_AUTOFRAME] tool; shares its engine, settings
     * and export pre-render.
     */
    AI_REFRAME,
    /**
     * PHASE 6 — Local Beat Sync: rank on-beat cut suggestions from
     * analyzed audio beats → per-cut select → batch split apply.
     * Distinct from [BEATS] (detect + split-everything); shares its
     * detector and marker storage.
     */
    AI_BEAT_SYNC,
    /**
     * PHASE 7 — Smart Highlight Detection: rank content-rich segments
     * from local signals → per-card select → store non-destructive
     * ranges (future AutoEdit input). Never trims the timeline.
     */
    AI_HIGHLIGHTS,
    /**
     * Beat Detection — analyse an audio clip for percussive onsets and
     * drop yellow markers on the timeline, with optional auto-split.
     * Sits next to [AI_SILENCE_CUT] because both are "find structure in
     * the audio and cut to it" tools, but this one is about *rhythm*
     * while silence cut is about *gaps*.
     */
    BEATS,
    /** «زیرنویس فارسی» — template studio for Persian subtitles. */
    SUBTITLE_STUDIO,
    /**
     * 2026-10-13 — «نوار پیشرفت»: the project's video progress bar
     * (timeline indicator / story timer). Project-level, not clip-scoped —
     * one bar for the whole exported file.
     */
    PROGRESS_BAR,
    /**
     * 2026-10-13 — «تغییر صدا»: offline voice transformation presets
     * (chipmunk / deep / robot / telephone / echo) for the selected video
     * clip's own audio, or for a selected audio object.
     *
     * Clip-scoped, unlike [PROGRESS_BAR] which is project-level: the effect
     * belongs to one clip's audio, so the same timeline can carry a
     * chipmunked clip next to an untouched one.
     */
    VOICE_CHANGER,

    /**
     * 2026-10-21 — «نریشن» (voiceover): record a narration over the
     * video and drop it on the timeline as its own audio object.
     *
     * CapCut's voiceover is one of its signature capabilities. This
     * project had the audio lane, the mixer and the voice changer, but
     * no recorder — and no `RECORD_AUDIO` grant in the manifest.
     *
     * Project-level rather than clip-scoped: the take lands on the
     * timeline at the playhead and is edited like any other audio
     * object afterwards.
     */
    VOICEOVER,

    /**
     * 2026-10-13 — «ماسک»: shape masks for a picture-in-picture overlay
     * (circle / rounded rect / heart / star), with a corner-radius
     * slider, a feather width, and an invert toggle.
     *
     * Scoped to the **selected overlay**, not to the selected clip: two
     * PiPs on the same clip can carry different shapes. The panel
     * resolves its target through [EditorUiState.selectedOverlayId] and
     * disables itself when nothing is selected, rather than guessing.
     */
    OVERLAY_MASK,

    /**
     * 2026-10-13 — «پرده سبز»: chroma key (green/blue screen removal) for
     * a picture-in-picture overlay, with a colour picker, a similarity
     * threshold, an edge-smoothness width and a spill-reduction amount.
     *
     * A sibling of [OVERLAY_MASK] and scoped the same way — to the selected
     * overlay. The two compose: a circular facecam shot on a green screen
     * carries both, and the renderers apply the key to the overlay's own
     * frame before clipping that frame to the shape.
     */
    CHROMA_KEY
}

/**
 * Animation types for clips
 */
enum class ClipAnimation(val displayName: String) {
    NONE("None"),
    FADE_IN("Fade In"),
    FADE_OUT("Fade Out"),
    FADE_IN_OUT("Fade In & Out"),
    SLIDE_LEFT("Slide Left"),
    SLIDE_RIGHT("Slide Right"),
    SLIDE_UP("Slide Up"),
    SLIDE_DOWN("Slide Down"),
    ZOOM_IN("Zoom In"),
    ZOOM_OUT("Zoom Out"),
    ROTATE("Rotate")
}

/**
 * Crop presets for different platforms
 */
enum class CropPreset(val displayName: String, val aspectRatio: Float) {
    FREE("Free", 0f),
    YOUTUBE("YouTube (16:9)", 16f / 9f),
    INSTAGRAM_POST("Instagram Post (1:1)", 1f),
    INSTAGRAM_STORY("Instagram Story (9:16)", 9f / 16f),
    TIKTOK("TikTok (9:16)", 9f / 16f),
    TWITTER("Twitter (16:9)", 16f / 9f),
    FACEBOOK("Facebook (16:9)", 16f / 9f),
    CUSTOM("Custom", 0f)
}

/**
 * Famous one-tap video effects (applied via ColorMatrix).
 */
enum class VideoEffect(val displayName: String) {
    NONE("Normal"),
    NOIR("Noir"),
    SEPIA("Sepia"),
    VIVID("Vivid"),
    FADE("Fade"),
    COLD("Cold"),
    WARM("Warm"),
    INVERT("Invert"),
    DREAM("Dream"),
    CYBER("Cyber"),
    VHS("VHS")
}

/**
 * Video overlay for picture-in-picture or layered content.
 *
 * ## 2026-10-13 — these fields now have consumers
 *
 * Until this round the whole type was **timeline-only metadata**:
 * `positionX` / `positionY` / `scale` / `opacity` / `uri` / `isVideo`
 * appeared in the codec and on the timeline lane and nowhere else. There
 * was no player for overlay media and no compositor in the export, so an
 * overlay drew a tile and nothing more.
 *
 * The PiP feature added the two missing halves —
 * `ui/screens/editor/OverlayPipLayer.kt` (preview) and
 * `export/OverlayCompositeRenderer.kt` (export) — and they read the
 * placement through the **shared** [OverlayPipGeometry] so the preview
 * and the file cannot disagree. See that file for the anchor convention.
 *
 * @param positionX box top-left X, normalised to the canvas (0..1).
 * @param positionY box top-left Y, normalised to the canvas (0..1).
 * @param scale box width as a fraction of the canvas width (0..1).
 * @param aspectRatio box width ÷ height. Probed from the media at import
 *   time ([OverlayPipGeometry.aspectRatioFor]); `1f` (a square box) when
 *   the media could not be probed, which is the only value that cannot
 *   distort an unknown source. **Appended 2026-10-13** — a landscape
 *   clip dropped into a square PiP would otherwise be centre-cropped
 *   with no way for the user to see why.
 * @param rotationDegrees clockwise rotation about the box centre.
 *   **Appended 2026-10-13** for the pinch/rotate gesture the mask brief
 *   asks for.
 * @param maskSettings the shape the overlay is clipped to.
 *   **Appended 2026-10-13**; [OverlayMaskSettings.isIdentity] means the
 *   overlay renders as a plain rectangle exactly as it did before.
 */
data class VideoOverlay(
    val id: String = java.util.UUID.randomUUID().toString(),
    val clipId: String,
    val startTimeMs: Long = 0L,
    val endTimeMs: Long = 5000L,
    val positionX: Float = 0.1f, // 0-1 normalized
    val positionY: Float = 0.1f, // 0-1 normalized
    val scale: Float = 0.3f, // 0-1 (percentage of screen)
    val opacity: Float = 1f,
    // Media picked for this overlay ("image/*" or "video/*" content)
    val uri: String = "",
    val label: String = "",
    val isVideo: Boolean = false,
    /** See the class KDoc. Appended last so positional call sites keep compiling. */
    val aspectRatio: Float = 1f,
    /** See the class KDoc. Appended last so positional call sites keep compiling. */
    val rotationDegrees: Float = 0f,
    /** See the class KDoc. Appended last so positional call sites keep compiling. */
    val maskSettings: OverlayMaskSettings = OverlayMaskSettings.Default,
    /**
     * 2026-10-13 — green/blue screen removal for this overlay.
     *
     * Appended last so positional call sites keep compiling. Composes with
     * [maskSettings]: the key removes pixels from the overlay's own frame,
     * the mask then clips the result to a shape. Both renderers apply them
     * in that order from the one implementation in
     * `util/ChromaKeyEngine.kt`.
     */
    val chromaKeySettings: ChromaKeySettings = ChromaKeySettings.Default
)

/**
 * Kinds of timeline track. Each kind has its own row (or set of rows for
 * multi-layer variants like Text and Overlay) and a distinct accent
 * colour so the user can read the layer hierarchy at a glance.
 *
 * The refactor exposes these so the dock can toggle which rows are
 * expanded — Text reveals up to 5 text lanes, Audio reveals music + sfx
 * lanes, Overlay reveals up to 3 image/video lanes, Subtitles reveals
 * a single subtitle lane populated by [com.aicreatorstudio.app.ai.SubtitleGenerator].
 */
enum class TrackKind(val maxLayers: Int, val displayName: String) {
    /** The main video clip lane. */
    VIDEO(maxLayers = 1, displayName = "Video"),
    /** 2026-09-06 — the paired audio track of the imported video(s). */
    VIDEO_AUDIO(maxLayers = 1, displayName = "Video Audio"),
    /** Imported background-music audio clips. */
    AUDIO_MUSIC(maxLayers = 1, displayName = "Music"),
    /** Built-in / one-shot sound effects. */
    AUDIO_SFX(maxLayers = 1, displayName = "SFX"),
    /** Up to 5 stacked layers of user-authored text overlays. */
    TEXT(maxLayers = 5, displayName = "Text"),
    /** AI-generated subtitle captions. */
    SUBTITLE(maxLayers = 1, displayName = "Subtitles"),
    /** Picture-in-picture / image / video overlays. */
    OVERLAY(maxLayers = 3, displayName = "Overlay"),
    /** Per-clip effects summary lane. */
    EFFECTS(maxLayers = 1, displayName = "FX")
}

/**
 * One-shot trigger pushed into [EditorUiState] when the user asks to
 * export the current transcription as SRT. The screen reads the
 * trigger, launches the system "Save as" file picker (Android SAF
 * `Intent.ACTION_CREATE_DOCUMENT`), receives the chosen `content://`
 * URI back from the OS, and writes the SRT bytes to it on a
 * background dispatcher.
 *
 * Modeled on the existing [EditorUiState.pendingSeekMs] one-shot
 * pattern: the ViewModel sets the field, the screen consumes it,
 * and the ViewModel then clears it.
 */
data class SrtExportTrigger(
    /** Suggested file name shown in the SAF dialog, e.g. "my_video.srt". */
    val suggestedFileName: String,
    /** Pre-rendered SRT body the screen writes into the chosen file. */
    val srtBody: String
)

/**
 * PHASE B4 — one-shot trigger pushed into [EditorUiState] when the
 * user taps "Import SRT". The screen launches a SAF
 * `ACTION_OPEN_DOCUMENT` picker (MIME application/x-subrip +
 * text/plain); the resulting Uri is passed back to
 * [EditorViewModel.importSrtFromUri]. Same shape / consumption
 * pattern as [SrtExportTrigger].
 */
data class SrtImportTrigger(
    /**
     * The token used to correlate the picker callback with the
     * trigger. The screen reads it from the trigger, hands it
     * to the launcher, and passes it back unchanged to the
     * ViewModel on callback. Mostly a sanity guard so a stale
     * callback from a previous import does not get re-processed
     * after the user dismissed the picker.
     */
    val requestId: Long
)

/**
 * One-shot trigger pushed into [EditorUiState] when the user asks to
 * export the active clip's matting mask as a PNG sequence (Background
 * Remove only). Same shape and consumption pattern as
 * [SrtExportTrigger] — the ViewModel sets the trigger, the screen
 * launches a SAF folder picker, writes the frames on a background
 * dispatcher, then clears the trigger via a
 * `consumeMatteExportTrigger` call.
 *
 * The mask itself is rendered on demand by the export pipeline —
 * the trigger only carries the metadata (clip id + suggested file
 * name) so the screen can scope its work to one clip.
 */
data class MatteExportTrigger(
    val clipId: String,
    val suggestedFolderName: String,
    val frameCount: Int,
    val frameRateLabel: String
)

/**
 * 2026-09-10 — staged AI OCR panel state (see
 * [EditorUiState.ocrResult]).
 */
data class OcrPanelResult(
    /**
     * 2026-10-20 — the clip this result was produced from.
     *
     * The staged result used to carry no clip identity, so the
     * panel's "Apply" button resolved its target from the CURRENT
     * selection. Running OCR on clip A, tapping clip B on the
     * timeline and then hitting Apply wrote the text to **clip B**
     * while clip A kept nothing. The id is now captured when the run
     * finishes and used to resolve the target.
     */
    val clipId: String = "",
    /** Every frame that produced at least one detected line. */
    val frames: List<com.aicreatorstudio.app.ai.ocr.OcrAnalysisResult>,
    /** Language combination the engine loaded, e.g. "fas+eng". */
    val languages: String,
    /** Text the user committed via Apply (null until applied). */
    val appliedText: String? = null,
    /** Frame timestamp of the committed text. */
    val appliedTimestampMs: Long = -1L
)

/**
 * State machine for the AI Auto Silence Cut feature.
 *
 * PHASE 1 flow: Idle → Processing (analyze) → Preview → (Apply →
 * Completed | Discard → Idle). Preview shows what WOULD be removed
 * before the timeline is touched; Completed is the legacy one-shot
 * result kept for [EditorViewModel.autoCutSilence].
 */
sealed interface AutoSilenceCutState {
    data object Idle : AutoSilenceCutState
    data class Processing(
        val progress: Float = 0f,
        val message: String = ""
    ) : AutoSilenceCutState
    /**
     * Analysis finished, nothing cut yet. [clips] carries per-clip
     * silence segments with independent selection; applying is explicit
     * via user confirmation. [sourceKey] fingerprints the timeline the
     * analysis was generated from — apply/toggle verify it first and
     * refuse stale previews (see PHASE 2, requirement 10).
     */
    data class Preview(
        val clips: List<SilencePreviewClip> = emptyList(),
        val config: SilenceDetectionConfig = SilenceDetectionConfig(),
        val totalSegments: Int = 0,
        val totalSilenceMs: Long = 0L,
        val sourceKey: String = "",
        /** PHASE 3 — which analysis produced this preview. */
        val mode: SilencePreviewMode = SilencePreviewMode.REMOVE_SILENCE
    ) : AutoSilenceCutState {
        /** All segments across clips (detection order). */
        val allSegments: List<SilencePreviewSegment>
            get() = clips.flatMap { it.segments }

        /** Only the user-selected cuts. */
        val selectedSegments: List<SilencePreviewSegment>
            get() = allSegments.filter { it.selected }

        val selectedCount: Int get() = selectedSegments.size
        val selectedMs: Long get() = selectedSegments.sumOf { it.durationMs }
    }
    data class Completed(
        val silencesRemoved: Int,
        val silenceMsRemoved: Long
    ) : AutoSilenceCutState
    data class Error(val message: String) : AutoSilenceCutState
}

/**
 * PHASE 3 — which engine produced a silence preview. Classic mode
 * pre-selects everything; jump-cut mode pre-selects only ranked
 * recommendations. Toggle/apply/stale logic is mode-agnostic.
 */
enum class SilencePreviewMode {
    REMOVE_SILENCE,
    SMART_JUMP_CUT
}

/**
 * PHASE 4 — Local Voice Enhancement preview state machine.
 *
 * Idle → Processing (decode → normalize → denoise → cache) → Preview
 * (stats + waveform, timeline untouched) → (Apply → Completed |
 * Discard → Idle). [sourceKey] fingerprints the exact clip bytes the
 * preview was generated from; Apply re-validates it first.
 */
sealed interface VoiceEnhanceState {
    data object Idle : VoiceEnhanceState
    data class Processing(
        val progress: Float = 0f,
        val message: String = ""
    ) : VoiceEnhanceState
    data class Preview(
        val clipId: String,
        val fileName: String,
        val preset: com.aicreatorstudio.app.ai.voice.VoiceEnhancementPreset,
        val stats: com.aicreatorstudio.app.ai.voice.VoiceEnhancementStats,
        /** Settings to commit on Apply (enabled flips true there). */
        val pendingSettings: VoiceCleanSettings,
        val sourceKey: String
    ) : VoiceEnhanceState
    data class Completed(
        val fileName: String,
        val noiseReductionDb: Float
    ) : VoiceEnhanceState
    data class Error(val message: String) : VoiceEnhanceState
}

/**
 * PHASE 5 — Local Smart Reframe preview state machine.
 *
 * Idle → Processing (detect → track → smooth → simplify) → Preview
 * (stats + crop-path strip, timeline untouched) → (Apply → Completed
 * | Discard → Idle). [sourceKey] covers clip bytes, geometry and the
 * target aspect; Apply re-validates it first.
 */
sealed interface ReframeState {
    data object Idle : ReframeState
    data class Processing(
        val progress: Float = 0f,
        val message: String = ""
    ) : ReframeState
    data class Preview(
        val clipId: String,
        val fileName: String,
        val targetLabel: String,
        val targetAspectWidth: Float,
        val targetAspectHeight: Float,
        val keys: List<com.aicreatorstudio.app.model.CropKeyframe>,
        val stats: com.aicreatorstudio.app.ai.reframe.ReframeStats,
        /** Settings to commit on Apply (enabled flips true there). */
        val pendingSettings: com.aicreatorstudio.app.model.AutoFrameSettings,
        val sourceKey: String
    ) : ReframeState
    data class Completed(
        val fileName: String,
        val keyframes: Int
    ) : ReframeState
    data class Error(val message: String) : ReframeState
}

/**
 * PHASE 6 — Local Beat Sync suggestion state machine.
 *
 * Idle → Processing (map beats → rank candidates) → Preview
 * (per-cut accept/reject, timeline untouched) → (Apply → Completed
 * | Discard → Idle). [sourceKey] covers video geometry, audio
 * identity/beat content and mode; Apply re-validates it first.
 */
sealed interface BeatSyncState {
    data object Idle : BeatSyncState
    data class Processing(
        val progress: Float = 0f,
        val message: String = ""
    ) : BeatSyncState
    data class Preview(
        val videoClipId: String,
        val videoFileName: String,
        val audioClipId: String,
        val audioFileName: String,
        val bpm: Int?,
        val beatCount: Int,
        val mode: com.aicreatorstudio.app.util.BeatSyncMode,
        val candidates: List<com.aicreatorstudio.app.util.BeatSyncCandidate> = emptyList(),
        val sourceKey: String
    ) : BeatSyncState {
        val selectedCandidates: List<com.aicreatorstudio.app.util.BeatSyncCandidate>
            get() = candidates.filter { it.selected }
        val selectedCount: Int get() = selectedCandidates.size
    }
    data class Completed(
        val fileName: String,
        val cutsApplied: Int
    ) : BeatSyncState
    data class Error(val message: String) : BeatSyncState
}

/**
 * PHASE 7 — Smart Highlight Detection state machine.
 *
 * Idle → Processing (decode → VAD/energy → visual → rank) → Preview
 * (ranked cards, timeline untouched) → (Apply → Completed |
 * Discard → Idle). [sourceKey] covers clip bytes/geometry, subtitle
 * revision, beat content and config; Apply re-validates it first.
 * Apply stores SELECTED ranges on the clip (non-destructive markers
 * for future AutoEdit) — never trims the timeline.
 */
sealed interface HighlightState {
    data object Idle : HighlightState
    data class Processing(
        val progress: Float = 0f,
        val message: String = ""
    ) : HighlightState
    data class Preview(
        val clipId: String,
        val fileName: String,
        val candidates: List<com.aicreatorstudio.app.ai.highlight.HighlightCandidate> = emptyList(),
        val signalsAvailable: Set<com.aicreatorstudio.app.ai.highlight.HighlightSignal> = emptySet(),
        val sourceKey: String
    ) : HighlightState {
        val selectedCandidates: List<com.aicreatorstudio.app.ai.highlight.HighlightCandidate>
            get() = candidates.filter { it.selected }
        val selectedCount: Int get() = selectedCandidates.size
    }
    data class Completed(
        val fileName: String,
        val highlightsStored: Int
    ) : HighlightState
    data class Error(val message: String) : HighlightState
}

/**
 * PHASE 2 — one proposed silence cut with independent selection state.
 *
 * [id] is deterministic (`"$clipId:$index"`, index = sorted position at
 * analyze time) so checkbox toggles stay stable without extra ID
 * bookkeeping. Toggling only flips [selected] in preview state — the
 * timeline is untouched until Apply.
 *
 * PHASE 3 adds [score]/[reason]: ranked recommendation data. Classic
 * remove-silence previews leave them at 0/[NONE].
 */
data class SilencePreviewSegment(
    val id: String,
    val clipId: String,
    val startMs: Long,
    val endMs: Long,
    val confidence: Float = 1f,
    val selected: Boolean = true,
    val score: Float = 0f,
    val reason: com.aicreatorstudio.app.ai.vad.JumpCutReason? = null
) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * PHASE 1 — per-clip silence preview. Framework-free snapshot of
 * [com.aicreatorstudio.app.ai.vad.SilenceCutter.ClipSilenceAnalysis]
 * for the UI layer.
 */
data class SilencePreviewClip(
    val clipId: String,
    val fileName: String,
    val clipDurationMs: Long,
    val segments: List<SilencePreviewSegment> = emptyList()
) {
    val silenceMs: Long get() = segments.sumOf { it.durationMs }
    val selectedMs: Long get() = segments.filter { it.selected }.sumOf { it.durationMs }
}
