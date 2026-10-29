package com.aicreatorstudio.app.ai

import android.net.Uri

/**
 * Shared contract for the new AI tools (Background Remove, Auto Frame,
 * Voice Clean). Mirrors the [TranscriptionEngine] / [WhisperEngine] shape
 * so any future engine (e.g. a real MODNet / BlazeFace / DeepFilterNet
 * backend) can drop in without touching consumers.
 *
 * ## Lifecycle
 *
 * 1. The host (e.g. [EditorViewModel]) asks [AiToolRegistry] for the
 *    engine by id.
 * 2. UI checks [isAvailable]; if false the model manager is asked to
 *    download / verify / install via [ensureModelReady].
 * 3. [process] runs the engine on the given input, emitting progress
 *    0f → 1f. Long work is performed off the main thread by the
 *    implementation (callers wrap in `withContext(Dispatchers.IO)`).
 * 4. [release] frees any resources. Idempotent.
 *
 * ## Failure semantics
 *
 * Every public suspend function returns `Result.failure` with a
 * user-facing Persian message on error — never throws. Callers can
 * surface the failure message directly in the UI banner.
 */
interface AiToolEngine {

    /**
     * Stable identifier used by [EditorUiState.aiTools] keys, dock
     * routing, and logs. Examples: `"matting"`, `"autoframe"`,
     `"voiceclean"`.
     */
    val id: String

    /** Human-readable name shown in the AI tool card label. */
    val displayName: String

    /**
     * Short one-line status string shown in the panel under the
     * Generate button. Examples: "Algorithm: Lab-distance matting,
     * offline", "Model: MODNet TFLite (not installed)".
     *
     * Implementations update this whenever the model state changes so
     * the UI can show the current truth without polling.
     */
    val modelStatusText: String

    /**
     * True if [process] can run right now without any extra setup.
     * This is a fast, side-effect-free check. For the v1
     * ship-without-models policy every engine returns `true` because
     * the lightweight algorithms have no model dependency. A future
     * MODNet / DeepFilterNet backend would return `true` only when
     * its TFLite weights are present on disk.
     */
    val isAvailable: Boolean

    /**
     * Make sure any required model file is downloaded, verified, and
     * installed. For the v1 algorithms this is a no-op returning
     * `Result.success(Unit)`. A future TFLite backend would delegate
     * to [ModelManager].
     *
     * Reports progress from 0f → 1f. Returns `Result.failure` with a
     * Persian message on any error.
     */
    suspend fun ensureModelReady(onProgress: (Float) -> Unit): Result<Unit>

    /**
     * Run the tool on the selected clip. Implementations own their
     * own file I/O, frame decoding, and inference. The result type
     * [AiToolResult] is a small data class — the host writes each
     * field back onto the corresponding `VideoClip` (e.g. `matting`,
     * `cropKeyframes`, `voiceClean`).
     *
     * Reports progress from 0f → 1f. Returns `Result.failure` on any
     * error (corrupt source, OOM, cancellation).
     */
    suspend fun process(
        clipId: String,
        videoUri: Uri,
        options: AiToolOptions,
        onProgress: (Float) -> Unit
    ): Result<AiToolResult>

    /**
     * Free any cached resources. Idempotent. Safe to call when the
     * engine has never processed anything.
     */
    fun release()
}

/**
 * Per-tool options the UI collects from the user before kicking off
 * [AiToolEngine.process]. Each engine reads only the fields it cares
 * about; unknown fields are ignored. Defaults are "do nothing".
 */
data class AiToolOptions(
    /**
     * 0f..1f — strength slider value. Default 0f means "disabled".
     *
     * 2026-10-20 — the OCR tool is the one EXCEPTION: its panel sends
     * the selected language packs as a BIT MASK through this field
     * (`1·fas + 2·ara + 4·eng`, `0` = "every installed pack"). That
     * mapping was previously a dead 0.33/0.66 ratio because the panel
     * always sent `1f`, which meant "all three languages" no matter
     * what the user picked. Use [ocrLanguages] instead — it is
     * explicit and cannot be misread.
     */
    val strength: Float = 0f,
    /**
     * 2026-10-20 — the Tesseract language packs the user selected,
     * as Tesseract codes (`"fas"`, `"ara"`, `"eng"`).
     *
     * Empty = the engine picks every installed pack. The OCR engine
     * prefers this field over the [strength] bit mask; the mask exists
     * only as a fallback for older call sites.
     */
    val ocrLanguages: Set<String> = emptySet(),
    /**
     * Optional background media picked by the user for matting
     * replacement. Null = transparent / checkerboard preview.
     */
    val replacementBackgroundUri: String? = null,
    /**
     * Target aspect ratio for Auto Frame (e.g. 9f / 16f for 9:16).
     * Width / Height. Ignored by tools that don't change aspect.
     */
    val targetAspectWidth: Float = 9f,
    val targetAspectHeight: Float = 16f,
    /**
     * 2026-10-25 — removed `featherPx`: the mask feather (+ expansion /
     * reverse) is now a composition-time user knob on
     * `MattingSettings`, applied by the export pipeline over the raw
     * sidecar masks (see `MaskBitmaps` / `MaskPostProcess`). The
     * engine writes raw masks and no longer receives edge knobs.
     *
     * When true the engine writes back its result onto the selected
     * clip. When false the engine still runs but the result is
     * discarded (used by the "Preview" button that doesn't commit).
     */
    val commitToClip: Boolean = true,

    // ------------------------------------------------------------------
    // 2026-09-28 — Motion Tracking options.
    //
    // Grouped here rather than in a nested object because every other
    // tool's inputs are flat on this class, and a reader looking up
    // "what can the UI send" should find one list.
    // ------------------------------------------------------------------

    /**
     * The user-drawn ROI to track, in **normalized 0..1 frame
     * coordinates** (0,0 = top-left of the video frame, 1,1 =
     * bottom-right). Null means "nothing selected" — the engine
     * refuses rather than guessing a region.
     *
     * Normalized rather than pixels because three different
     * resolutions are in play (the UI container, the 360p analysis
     * frame, the export frame) and only the normalized form is valid in
     * all three.
     */
    val trackingRoi: android.graphics.RectF? = null,

    // ------------------------------------------------------------------
    // 2026-10-17 — Matting sidecar routing.
    //
    // The Background Remove engine streams its per-frame masks straight
    // to the project sidecar while the analysis loop runs, instead of
    // returning up to MAX_FRAMES full-resolution bitmaps in the result
    // (the old shape held gigabytes and the ViewModel compressed the
    // PNGs after success was already shown). Only the ViewModel knows
    // the project id, so it fills this in for `runAiTool("matting")`.
    // Null = the engine refuses to run matting (no silent fake sidecar).
    // ------------------------------------------------------------------
    /** Id of the project that owns the matting sidecar on disk. */
    val mattingProjectId: String? = null,

    /** Id of the [com.aicreatorstudio.app.model.TextOverlay] that
     *  follows the track, or null when only a mask is used. */
    val trackingOverlayId: String? = null,

    /** Which mask pass rides the trajectory, if any. */
    val trackingMaskMode: com.aicreatorstudio.app.model.MotionTrackSettings.MaskMode =
        com.aicreatorstudio.app.model.MotionTrackSettings.MaskMode.NONE,

    /** 0..1 — blur radius / mosaic density for the mask pass. */
    val trackingMaskStrength: Float = 0.5f,

    /** Normalized anchor inside the tracked box. 0.5 = centre. */
    val trackingAnchorU: Float = 0.5f,
    val trackingAnchorV: Float = 0.5f,

    /** Scale the overlay with the tracked box's size. */
    val trackingScaleWithBox: Boolean = false,

    /** 0..1 — 1-Euro strength applied to the box centre. */
    val positionSmoothing: Float = 0.5f,

    /** 0..1 — 1-Euro strength applied to the box extent. Heavier than
     *  [positionSmoothing] by default: position lag is far more visible
     *  to a viewer than scale lag. */
    val scaleSmoothing: Float = 0.7f,

    /** Confidence below which a tracker sample is treated as a miss. */
    val minConfidence: Float = 0.35f,

    // ------------------------------------------------------------------
    // PHASE 4 — Voice Enhancement options.
    //
    // Flat on this class like every other tool input. Defaults are
    // "do nothing", so existing Voice Clean callers are unaffected.
    // ------------------------------------------------------------------

    /**
     * When true the voice engine peak-normalizes the final cleaned mix
     * toward [normalizeTargetPeak] (make-up capped at +12 dB, hard
     * clamped — cannot clip). False = legacy behavior: denoise only,
     * output loudness untouched.
     */
    val normalizeOutput: Boolean = false,
    /** Peak target for [normalizeOutput], linear 0..1 (0.89 ≈ −1 dBFS). */
    val normalizeTargetPeak: Float = 0.89f
)

/**
 * Engine output. Each field is populated only by the engine that
 * owns it; the host writes the populated fields onto the matching
 * `VideoClip` properties. All other fields are `null` / empty so
 * engines can't accidentally overwrite each other's state.
 */
data class AiToolResult(
    val matting: com.aicreatorstudio.app.model.MattingSettings? = null,
    val autoFrame: com.aicreatorstudio.app.model.AutoFrameSettings? = null,
    val cropKeyframes: List<com.aicreatorstudio.app.model.CropKeyframe> = emptyList(),
    val voiceClean: com.aicreatorstudio.app.model.VoiceCleanSettings? = null,
    /**
     * 2026-09-28 — Motion Tracking settings.
     *
     * Populated **only** by
     * [com.aicreatorstudio.app.ai.engines.MotionTrackingEngine]; every
     * other engine leaves it null so results cannot cross-contaminate.
     *
     * Note this engine also writes [cropKeyframes] — the trajectory is
     * the same model Auto Frame uses (see `CropKeyframe`'s KDoc for why
     * one type serves both). The two are told apart by which *settings*
     * block is present, not by which list is populated.
     */
    val motionTrack: com.aicreatorstudio.app.model.MotionTrackSettings? = null,
    /**
     * 2026-10-17 — **legacy, always `emptyList()`.** The matting
     * engine now streams its masks straight into the project sidecar
     * while the analysis loop runs (see
     * [com.aicreatorstudio.app.ai.AiToolOptions.mattingProjectId]); the
     * old shape held up to MAX_FRAMES full-resolution bitmaps in the
     * result and the host compressed them after success had already
     * been reported — gigabytes held + minutes of post-hoc PNG work
     * that made Apply feel broken. The field remains so old callers/
     * tests keep compiling; no engine may populate it again without
     * revisiting that memory bound.
     */
    val mattingFrames: List<MattingFrame> = emptyList(),
    /** Sampling interval of the last matting pass in milliseconds. */
    val mattingIntervalMs: Long = 0L,
    /** True source width of the last matting pass (sidecar metadata). */
    val mattingSourceWidth: Int = 0,
    /** True source height of the last matting pass (sidecar metadata). */
    val mattingSourceHeight: Int = 0,
    /** Short human-readable summary shown in the success toast. */
    val summary: String = "",
    /**
     * 2026-09-10 — AI OCR payload. Populated only by
     * [com.aicreatorstudio.app.ai.ocr.TesseractOcrEngine]; every
     * other engine leaves it null so results can't cross-contaminate.
     */
    val ocr: OcrToolPayload? = null,
    /**
     * PHASE 4 — Voice enhancement preview payload. Populated only by
     * the voice engine; every other engine leaves it null. Volatile
     * (preview UI only, never persisted).
     */
    val voiceStats: com.aicreatorstudio.app.ai.voice.VoiceEnhancementStats? = null
)

/**
 * OCR-specific engine output carried inside [AiToolResult]. Holds
 * every analyzed frame's lines plus the best frame (the one with
 * the most detected lines) that the UI shows by default.
 */
data class OcrToolPayload(
    val frames: List<com.aicreatorstudio.app.ai.ocr.OcrAnalysisResult>,
    /** Language combination the engine actually loaded, e.g. "fas+eng". */
    val languages: String,
    /** Frame with the most detected lines — the default UI result. */
    val best: com.aicreatorstudio.app.ai.ocr.OcrAnalysisResult?
)

/**
 * One alpha bitmap produced by the matting engine. [timestampMs] is
 * the source-clip-local time at which the bitmap was sampled. The
 * bitmap is ARGB_8888 with the alpha channel holding the
 * confidence-weighted person mask; RGB are unused (zero).
 */
data class MattingFrame(
    val timestampMs: Long,
    val bitmap: android.graphics.Bitmap
)