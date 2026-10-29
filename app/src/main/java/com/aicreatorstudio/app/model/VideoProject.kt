package com.aicreatorstudio.app.model

/**
 * A video editing project.
 *
 * Holds the clips that have been imported into it. This is the basic shape
 * a future timeline/editor will operate on — clip ordering, trimming, and
 * arrangement all build on top of [clips] later. No editing logic lives
 * here yet.
 */
data class VideoProject(
    val id: String,
    val name: String,
    val createdAtEpochMillis: Long,
    val clips: List<VideoClip> = emptyList(),
    /**
     * The project's canvas (output frame) preset. The export pipeline
     * uses this to size the output file.
     *
     * 2026-09-30 — defaults to [CanvasPreset.FREE] ("track the source"),
     * not `WIDE_16_9`. A project constructed without an explicit preset
     * has not seen its clips yet, so it has no orientation to assert; 16:9
     * was a landscape claim that portrait footage then had to be
     * letterboxed into. `createProjectFromClip` sets this from the
     * imported clip's own display dimensions via
     * [CanvasPreset.forSourceDimensions], and the user can change it from
     * the Ratio entry.
     */
    val canvasPreset: CanvasPreset = CanvasPreset.FREE,
    /**
     * The project's canvas background (the color/blur shown behind the
     * letterbox). The editor UI mirrors this from the global
     * [EditorUiState.canvasBackground] when the project is loaded and
     * pushes back any user changes here so the value survives
     * configuration changes and process death. Defaults to black to
     * match the previous hard-coded behaviour.
     */
    val canvasBackground: CanvasBackground = CanvasBackground.Black,
    /**
     * Captions / AI-subtitle overlays owned by the project. Persisted
     * as JSON in the projects row (v5) and mirrored into the editor's
     * UI state on load so captions survive SAVE -> EXIT -> REOPEN.
     */
    val textOverlays: List<TextOverlay> = emptyList(),
    /**
     * Media overlays (picture-in-picture / layered media blocks) owned
     * by the project. Same persistence story as [textOverlays].
     */
    val mediaOverlays: List<VideoOverlay> = emptyList(),
    /**
     * Audio clips owned by the project. Imported videos with an
     * audio track contribute one [AudioClip] here paired with the
     * video clip via [AudioClip.originVideoClipId]. Pure music / sfx
     * imports contribute a clip with `originVideoClipId = null`.
     * Persisted as JSON in the projects row (v6) so the
     * video-original-audio timeline state survives SAVE -> EXIT ->
     * REOPEN.
     */
    val audioClips: List<AudioClip> = emptyList(),
    /**
     * Playhead position (ms) the editor should restore on reopen.
     * Written whenever playback pauses / a scrub ends / the editor
     * closes.
     */
    val lastPositionMs: Long = 0L,
    /**
     * 2026-10-13 — the project's video progress bar (timeline indicator /
     * story timer). Persisted as a JSON blob on the projects row (v8) and
     * mirrored into [EditorUiState.progressBarSettings] on load.
     *
     * Lives on the project rather than on a clip because the exported file
     * has exactly one bar: it tracks the whole composition, not one clip.
     * Defaults to [ProgressBarSettings.Disabled], so a project that never
     * touched the feature exports byte-identically to before.
     */
    val progressBarSettings: ProgressBarSettings = ProgressBarSettings()
)
