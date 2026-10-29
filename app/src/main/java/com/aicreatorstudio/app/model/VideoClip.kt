package com.aicreatorstudio.app.model

/**
 * A single video file that has been imported into the app.
 *
 * This is deliberately just data — file location + metadata. It's the unit
 * a future timeline/editor will arrange and cut, and what future AI
 * features (auto-captioning, scene detection, etc.) will analyze. No
 * editing or processing logic lives on this model.
 *
 * @param uri Content URI of the source file, as a string (e.g. from the
 *   system photo/video picker). Kept as [String] rather than [android.net.Uri]
 *   so this model has no Android framework dependency and stays trivially
 *   testable/serializable.
 * @param durationMs Playback duration in milliseconds.
 * @param width Display width in pixels (rotation-corrected).
 * @param height Display height in pixels (rotation-corrected).
 * @param fileSizeBytes Size of the underlying file in bytes.
 */
data class VideoClip(
    val id: String,
    val uri: String,
    val fileName: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fileSizeBytes: Long,
    val importedAtEpochMillis: Long,
    // Edit properties
    val volume: Float = 1f,
    val speed: Float = 1f,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = -1L,
    // Timeline placement — negative means "auto layout after previous clip"
    val timelineStartMs: Long = -1L,
    val isAudioOnly: Boolean = false,
    // For audio-only clips: "music" (imported files) or "sfx" (built-in effects).
    // Each category gets its own lane on the timeline.
    val audioCategory: String = "music",
    // Transitions — encoded TransitionConfig strings (null = none)
    val transitionIn: String? = null,
    val transitionOut: String? = null,
    val animation: ClipAnimation = ClipAnimation.NONE,
    val cropPreset: CropPreset = CropPreset.FREE,
    val cropAspectRatio: Float = 0f,
    val brightness: Float = 1f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val rotation: Int = 0,
    // How the clip fills its canvas. The default (FIT) preserves the
    // pre-existing "letterbox if needed" behaviour. The Transform panel
    // toggles between FIT / FILL / MANUAL; MANUAL uses [transform].
    val fillMode: FillMode = FillMode.FIT,
    // User-applied pan / zoom / rotate / flip. Only meaningful when
    // [fillMode] is MANUAL, but the field is always present so the
    // transform state survives mode toggles (Fit → Manual returns to
    // the same pan/zoom the user left behind).
    val transform: ClipTransform = ClipTransform(),
    // Audio fade in/out, expressed in clip-local milliseconds. The
    // Audio panel wires these to the dock chip. 0 = no fade.
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    // Per-clip opacity 0..1. The Opacity dock chip / sub-panel
    // drives this. Applied to the foreground `PlayerView` via
    // `Modifier.graphicsLayer { alpha = ... }` and baked by the
    // export pipeline.
    val opacity: Float = 1f,
    // Per-clip "famous effect" LUT (ColorMatrix). Distinct from the
    // global `EditorUiState.selectedEffect` — the Filters panel now
    // stores the chosen effect on the active clip so it survives
    // selection changes and is part of the persisted project.
    val videoEffect: VideoEffect = VideoEffect.NONE,
    // ---- 2026-08-29 Lumetri color grading ----
    /**
     * Full Lumetri-style grading state for this clip. Identity means
     * "no grade applied" — the clip falls back to the global
     * `EditorUiState.colorGrading` when no clip is selected, or to its
     * own value when a clip is selected. Persisted with the clip.
     */
    val colorGrading: ColorGrading = ColorGrading.Identity,
    // ---- AI Video Upscaler ----
    /**
     * Per-clip AI upscaler / enhancer settings. Identity (preset = OFF)
     * means "no enhancement" and the clip falls back to the global
     * `EditorUiState.upscale` when no clip is selected. When a clip is
     * selected, this value wins for both the live preview and the
     * export pipeline.
     */
    val upscale: UpscalerSettings = UpscalerSettings.Identity,
    // ---- AI Background Remove ----
    /**
     * Per-clip Background Remove (matting) settings. Identity means
     * "no matting" — the export pipeline composites nothing. When
     * enabled the pipeline builds an alpha mask per frame from the
     * configured [MattingSettings] and replaces the background with
     * [MattingSettings.replacementBackgroundUri] (or transparent).
     */
    val matting: MattingSettings = MattingSettings.Identity,
    // ---- AI Auto Frame ----
    /**
     * Per-clip Auto Frame settings. Identity = no tracking. When
     * enabled the export pipeline drives the clip's crop window
     * from [cropKeyframes] so the subject stays centered through
     * the target aspect ratio (e.g. 9:16).
     */
    val autoFrame: AutoFrameSettings = AutoFrameSettings.Identity,
    /**
     * Time-keyed crop windows produced by the Auto Frame engine. The
     * export pipeline reads these for clips with `autoFrame.enabled
     * == true`. An empty list means "no tracking output yet" — the
     * pipeline falls back to the clip's [transform] / [fillMode].
     */
    val cropKeyframes: List<CropKeyframe> = CropKeyframe.Empty,
    // ---- AI Motion Tracking ----
    /**
     * 2026-09-28 — per-clip Motion Tracking settings. Identity = no
     * tracked overlay is attached to this clip.
     *
     * When enabled, the export pipeline binds an overlay (or a
     * blur/mosaic mask) to the moving target described by
     * [cropKeyframes]. Note the trajectory shares the `cropKeyframes`
     * list with Auto Frame — see `CropKeyframe`'s KDoc for why one
     * types serves both, and why [autoFrame] and this field are the
     * discriminator rather than the list itself.
     *
     * A clip carries either an Auto Frame crop or a Motion Track
     * trajectory in practice, never both: enabling one clears the
     * other's trajectory so a stale track cannot drive a crop.
     */
    val motionTrack: MotionTrackSettings = MotionTrackSettings.Identity,
    // ---- AI Voice Clean / Enhancement ----
    /**
     * Per-clip Voice Clean (denoise) settings. Identity = no
     * denoising — the export pipeline leaves the audio track alone.
     * When enabled the pipeline swaps in the cleaned WAV cached at
     * `cacheDir/voiceclean/<clipId>.wav` by
     * `RnnoiseVoiceCleanEngine` (or the spectral fallback when native
     * RNNoise is unavailable).
     */
    val voiceClean: VoiceCleanSettings = VoiceCleanSettings.Identity,
    // ---- AI OCR ----
    /**
     * 2026-09-10 — committed AI OCR result. Kept as a plain data
     * holder (String JSON) so this model stays framework-free and
     * trivially serializable; encoding/decoding lives in
     * `com.aicreatorstudio.app.ai.ocr.OcrClipResultJson`. `null`
     * means no OCR output has been applied to this clip.
     */
    val ocrResult: String? = null,
    // ---- Speed Ramping / Curves ----
    /**
     * 2026-09-29 — variable-speed ramp for this clip, or `null` for
     * none.
     *
     * **This does not replace [speed].** A null curve, or a curve that
     * does not actually vary (see [SpeedCurve.isActive]), leaves the
     * constant-speed path completely untouched — same Room column, same
     * `SpeedChangeEffect`, same bytes out. Only an active ramp diverts
     * to the curve-driven speed provider in the export engine.
     *
     * The two fields are mutually exclusive in effect but both persist:
     * clearing the ramp restores whatever constant speed the user last
     * dialled in, which is what lets the curve editor be opened and
     * closed without side effects.
     */
    val speedCurve: SpeedCurve? = null,
    // ---- PHASE 7 Smart Highlights ----
    /**
     * User-approved highlight ranges (Phase 7+ future AutoEdit input).
     * Clip-local ms within the effective span. Non-destructive: export
     * ignores them; empty = none. Persisted in the AI-tools blob.
     */
    val highlightRanges: List<HighlightRange> = HighlightRange.Empty,
    // ---- 2026-10-13 Voice Changer ----
    /**
     * The offline voice transformation applied to this clip's own audio
     * track, or [VoiceEffect.NONE] for none.
     *
     * **Declared last on purpose.** Appending a parameter is source- and
     * binary-compatible with every existing named-argument call site and
     * with every positional one, whereas inserting it next to the other
     * audio fields would silently re-map any positional `VideoClip(...)`
     * or `copy(...)` in the codebase.
     *
     * It is an *audio* setting but it does not live in the `AudioClip`
     * paired to this clip: that pairing is broken whenever the user
     * detaches or replaces the audio, and the effect belongs to the
     * picture it was chosen for. The export reads it from the render
     * segment, and the preview reads it from the clip that is playing.
     *
     * Persisted in the per-clip AI-tools blob rather than a new Room
     * column — the same choice `speedCurve`, `look` and `highlightRanges`
     * already made, for the same reason: a column would need a schema
     * migration for a value whose default is "off".
     */
    val voiceEffect: VoiceEffect = VoiceEffect.NONE
) {
    /** Effective duration after trim */
    val effectiveDurationMs: Long
        get() = (trimEndMs.takeIf { it > 0 }?.let { it - trimStartMs } ?: durationMs - trimStartMs).coerceAtLeast(0)

    /**
     * 2026-09-29 — duration after trim **and** the speed ramp, i.e. what
     * the clip actually occupies on the editor timeline.
     *
     * [effectiveDurationMs] is the *source* span; this is the *output*
     * span. They are the same value for every clip without an active
     * ramp, which is why nothing that predates this feature needs to
     * change — but a timeline that drew `effectiveDurationMs` for a
     * ramped clip would show the wrong length, so the render graph and
     * the timeline should prefer this one.
     *
     * The ramp is applied on top of the constant speed rather than
     * instead of it, matching the export: `SpeedChangeEffect` receives
     * a provider whose rates are the product of the two. See
     * [com.aicreatorstudio.app.model.util.SpeedCurveInterpolator].
     */
    val rampedDurationMs: Long
        get() {
            val source = effectiveDurationMs
            if (source <= 0L) return 0L
            val curve = speedCurve
            if (curve == null || !curve.isActive) {
                return if (speed > 0f && speed != 1f) (source / speed).toLong() else source
            }
            val scaled = curve.copy(
                points = curve.points.map { it.copy(speed = it.speed * speed.coerceAtLeast(0.01f)) }
            )
            return com.aicreatorstudio.app.model.util.SpeedCurveInterpolator
                .outputDurationUs(source * 1000L, scaled) / 1000L
        }
}
