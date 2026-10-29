package com.aicreatorstudio.app.model

import com.aicreatorstudio.app.ai.MaskPostProcess

/**
 * Per-clip settings for the **Background Remove** tool.
 *
 * `Identity` means "no matting applied" — the clip falls back to the
 * global behaviour and the export pipeline leaves the foreground and
 * background untouched.
 *
 * ## v1 algorithm
 *
 * Lab-distance matting from border strips (no model weights). See
 * `ChromaMattingEngine` KDoc for the full description. The
 * `feather` and `threshold` fields are the two user-visible knobs
 * the algorithm exposes.
 *
 * ## Upgrade path
 *
 * A future MODNet TFLite backend reads the same `MattingSettings` —
 * `enabled`, `threshold`, `feather`, `replacementBackgroundUri`
 * stay meaningful. Only the `id` field would change to e.g. `"modnet"`.
 *
 * @property enabled whether the export pipeline should composite the
 *   clip with matting applied. False is the identity-equivalent
 *   state and is the default.
 * @property threshold 0..1 — distance-from-background below which a
 *   pixel is treated as foreground. Lower = stricter (only the most
 *   distinct subject survives). Default 0.20.
 * @property feather 0..1 **normalized** edge softness (Gaussian sigma
 *   applied to the alpha mask). Applied at **composition time**, not
 *   baked into the sidecar PNGs — so changing it takes effect at the
 *   next export/preview with no model re-run. `feather = 1` is a
 *   sigma of `maskShortSide / 60` px (CapCut's `inputEdgeSmooth = 1`
 *   shader mapping; see `MaskPostProcess`). Old projects whose blobs
 *   predate this field load with `feather = 0`, because their sidecar
 *   PNGs already carry the old generation-time feather. Fresh
 *   settings default to [DEFAULT_FEATHER].
 * @property expansion −1..+1 normalized mask expand/contract.
 *   Positive grows the subject (dilate), negative shrinks it (the
 *   reference shader's soft erode), 0 is the no-op. `±1` reaches
 *   `maskShortSide / 20` px (CapCut's ±100 slider mapping). Applied
 *   at composition time — also never requires a model re-run.
 * @property reverse when true the sprite alpha is inverted
 *   (`mask ← 1 − mask`): the **background** becomes the kept region
 *   and the subject is removed. Applied at composition time.
 * @property replacementBackgroundUri content URI of the image /
 *   video to composite under the foreground, or `null` for
 *   transparent (checkerboard) preview.
 * @property algorithm stable id of the algorithm that produced this
 *   matting — `"lab-distance-v1"` for the chroma-key v1 backend,
 *   `"mediapipe-selfie-multiclass-v1"` for the on-device person
 *   segmentation backend. Forward-compatible — unknown tags are
 *   treated as "best effort" by the export pipeline.
 */
data class MattingSettings(
    val enabled: Boolean = false,
    val threshold: Float = 0.20f,
    val feather: Float = MaskPostProcess.DEFAULT_FEATHER,
    val expansion: Float = 0f,
    val reverse: Boolean = false,
    val replacementBackgroundUri: String? = null,
    val algorithm: String = ALGORITHM_LAB_DISTANCE_V1,
    /**
     * Background replacement mode. Defaults to [MattingBackgroundMode.ORIGINAL]
     * so projects saved before modes existed reopen unchanged.
     */
    val backgroundMode: MattingBackgroundMode = MattingBackgroundMode.ORIGINAL,
    /** ARGB solid color for [MattingBackgroundMode.COLOR]. Default dark. */
    val backgroundColor: Int = 0xFF111111.toInt()
) {
    companion object {
        /** The identity value — no matting, no background replacement. */
        val Identity = MattingSettings(enabled = false)
        val Default = MattingSettings()

        /** v1 algorithm tag — chroma-keyed Lab distance, no model weights. */
        const val ALGORITHM_LAB_DISTANCE_V1: String = "lab-distance-v1"

        /**
         * v2 algorithm tag — MediaPipe Tasks Vision Selfie Multiclass
         * (256×256 TFLite). Round-tripped through JSON exactly as
         * written so a project saved with v1 settings still loads
         * cleanly under v2 (we treat unknown tags as "best effort").
         */
        const val ALGORITHM_MEDIAPIPE_SELFIE: String = "mediapipe-selfie-multiclass-v1"

        /**
         * 2026-10-27 — the background mode a Background Remove run
         * commits when the user has not picked one themselves.
         *
         * Pressing the button means "the subject stays and the
         * background is gone" — the CapCut behaviour — so the untouched
         * `ORIGINAL` default (a mask-only pass-through that visibly did
         * nothing) no longer survives a fresh run. Any mode the user
         * deliberately chose (Blur, Color, Image, Video — or an explicit
         * ORIGINAL re-selection) is kept; only the never-touched
         * default flips.
         */
        fun runDefaultBackgroundMode(previous: MattingBackgroundMode): MattingBackgroundMode =
            if (previous == MattingBackgroundMode.ORIGINAL) MattingBackgroundMode.TRANSPARENT
            else previous
    }
}

/**
 * Background replacement mode for [MattingSettings].
 *
 * Persisted as the enum name in project JSON (unknown names load
 * as [ORIGINAL] so old projects always open).
 */
enum class MattingBackgroundMode {
    /** Keep the original background (mask preview only). */
    ORIGINAL,

    /** Gaussian-blurred copy of the source frame. */
    BLUR,

    /** Solid [MattingSettings.backgroundColor]. */
    COLOR,

    /** Still image from [MattingSettings.replacementBackgroundUri]. */
    IMAGE,

    /** Looping video from [MattingSettings.replacementBackgroundUri]. */
    VIDEO,

    /**
     * 2026-10-27 — "background fully removed": the subject stays and the
     * removed area is filled with the **project's canvas background
     * colour** (`CanvasLetterbox.fadeColorArgb` — the same colour the
     * letterbox bars are painted with, so the exported result matches
     * what sits behind the clip in the editor).
     *
     * MP4/HEVC has no alpha channel and no offline muxer here writes
     * one, so "transparent" is a real composite over that canvas fill —
     * black on the default project, the user-picked swatch on a coloured
     * canvas, black on the (unimplemented) blurred canvas, exactly like
     * the letterbox bars themselves.
     */
    TRANSPARENT;

    companion object {
        fun fromName(name: String?): MattingBackgroundMode =
            runCatching { valueOf(name ?: "ORIGINAL") }.getOrDefault(ORIGINAL)
    }
}

/**
 * Pixel crop window in source-frame coordinates.
 *
 * Plain data (not `android.graphics.Rect`) so the geometry stays
 * JVM-unit-testable — framework `Rect` is stubbed on the JVM.
 * Call sites convert with `Rect(l, t, l + w, t + h)` on-device.
 */
data class CropWindow(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
}

/**
 * Per-clip settings for the **Auto Frame** tool.
 *
 * `Identity` means "no auto-frame tracking" — the clip's transform
 * is whatever the user has set manually (or FIT by default).
 *
 * @property enabled whether the export pipeline should use the
 *   generated `cropKeyframes` to drive the clip's per-frame crop.
 * @property targetAspectWidth width of the target frame (e.g. 9 for 9:16).
 * @property targetAspectHeight height of the target frame.
 * @property smoothing 0..1 — strength of the 1-Euro filter. 0 = no
 *   smoothing (raw saliency output), 1 = very heavy smoothing.
 *   Default 0.5.
 * @property algorithm stable id of the tracker that produced the
 *   keyframes — `"pose-landmarker-v1"` (MediaPipe pose, primary)
 *   with `"saliency-v1"` as the documented no-person fallback.
 */
data class AutoFrameSettings(
    val enabled: Boolean = false,
    val targetAspectWidth: Float = 9f,
    val targetAspectHeight: Float = 16f,
    val smoothing: Float = 0.5f,
    val algorithm: String = ALGORITHM_POSE_LANDMARKER_V1
) {
    companion object {
        val Identity = AutoFrameSettings(enabled = false)
        val Default = AutoFrameSettings()

        /** MediaPipe Pose Landmarker primary tracker. */
        const val ALGORITHM_POSE_LANDMARKER_V1: String = "pose-landmarker-v1"

        /** Sobel-saliency fallback when no person is detected. */
        const val ALGORITHM_SALIENCY_V1: String = "saliency-v1"
    }
}

/**
 * One frame in the Auto Frame crop animation **or** the Motion
 * Tracking trajectory. Stored on [VideoClip.cropKeyframes] and
 * consumed by the export pipeline.
 *
 * ## 2026-09-28 — generalized from "crop centre" to "tracked box"
 *
 * Auto Frame only ever needed to know *where* the subject is, because
 * the crop size is derived from the target aspect ratio rather than
 * from the subject. Motion Tracking additionally needs the subject's
 * **extent** (so a sticker can be scaled with its target and can be
 * pinned to a corner rather than the centre) and a **quality signal**
 * (so a failed track is handled as a failure rather than as data).
 *
 * Both are additive with defaults that reproduce the Auto Frame
 * behaviour exactly, so every existing producer, consumer, serializer
 * and test keeps working unchanged.
 *
 * ## Why one type and not two
 *
 * A clip carries either an Auto Frame crop or a Motion Track
 * trajectory, never both, and the consumer
 * ([com.aicreatorstudio.app.export.AiExportHooks]) is genuinely the
 * same shape — interpolate over time, then act per frame. Two
 * parallel lists would need two [com.aicreatorstudio.app.export.RenderSegment]
 * fields, two JSON paths, two Room columns, and would let the preview
 * and the export disagree about which one is authoritative. That
 * divergence is this project's most expensive bug class; one type
 * makes it unrepresentable.
 *
 * @property timeMs clip-local time this keyframe applies to.
 * @property centerX normalized 0..1 horizontal center of the crop
 *   window (0 = left edge of source frame, 1 = right edge).
 * @property centerY normalized 0..1 vertical center.
 * @property zoom 1.0 = no zoom in. > 1.0 zooms in (subject fills
 *   more of the target frame). Default 1.0.
 * @property width normalized 0..1 box width, or `null` when the
 *   producer is Auto Frame (which does not model extent — its crop
 *   size comes from the target aspect, not from the subject).
 *   **Deliberately nullable rather than defaulted to a value**: a
 *   non-null default would invite a future consumer to start reading
 *   extent and silently change existing Auto Frame behaviour. `null`
 *   means "no extent information" and forces the reader to handle it.
 * @property height normalized 0..1 box height, or `null`. See [width].
 * @property confidence 0..1 quality of the track at this instant.
 *   Defaults to 1f, which is the correct reading for Auto Frame (a
 *   keyframe it emits is, by construction, a sample it accepted) and
 *   keeps every existing consumer's behaviour identical.
 * @property isFound `false` when this entry is a FORWARD-FILLED
 *   placeholder for a frame where tracking failed. Consumers must
 *   decay or hold rather than treat it as a measurement.
 */
data class CropKeyframe(
    val timeMs: Long,
    val centerX: Float,
    val centerY: Float,
    val zoom: Float = 1f,
    val width: Float? = null,
    val height: Float? = null,
    val confidence: Float = 1f,
    val isFound: Boolean = true
) {
    /** Normalized left edge, or `null` when extent is unknown. */
    val left: Float? get() = width?.let { centerX - it / 2f }

    /** Normalized top edge, or `null` when extent is unknown. */
    val top: Float? get() = height?.let { centerY - it / 2f }

    /**
     * True when this entry carries usable box extent — i.e. it was
     * produced by a tracker that models the subject's size.
     */
    val hasExtent: Boolean get() = width != null && height != null

    companion object {
        val Empty = emptyList<CropKeyframe>()
    }
}

/**
 * Per-clip settings for the **Motion Tracking** tool.
 *
 * `Identity` means "no tracked overlay is attached to this clip" —
 * the export pipeline renders no tracked overlay pass.
 *
 * @property enabled whether the export pipeline should bake the
 *   attached overlay using [VideoClip.cropKeyframes] as the
 *   trajectory.
 * @property overlayId id of the [TextOverlay] that follows the
 *   track, or `null` when the user has not attached one yet. Only
 *   text/sticker overlays are bindable; a tracked blur or mosaic is
 *   expressed by [maskMode] instead.
 * @property maskMode which mask pass (if any) rides the trajectory.
 *   Blur and mosaic are **export-only** in v1 — see
 *   [MaskMode] and the preview note in the implementation guide.
 * @property maskStrength 0..1 blur radius / mosaic cell density.
 * @property anchorU normalized 0..1 position within the tracked box
 *   that the overlay is glued to. 0.5 = centre, 0 = left edge,
 *   1 = right edge.
 * @property anchorV normalized 0..1 vertical anchor. See [anchorU].
 * @property scaleWithBox when true the overlay's size is multiplied
 *   by the tracked box's size relative to [referenceBoxHeight], so a
 *   sticker on a hand shrinks as the hand moves away from camera.
 * @property referenceBoxHeight the tracked box height at the moment
 *   the overlay was attached, used as the 1.0 reference for
 *   [scaleWithBox].
 * @property positionSmoothing 0..1 — 1-Euro strength applied to the
 *   box CENTRE. Lower than [scaleSmoothing] by design: position lag
 *   is far more visible to a viewer than scale lag.
 * @property scaleSmoothing 0..1 — 1-Euro strength applied to the box
 *   EXTENT.
 * @property minConfidence confidence below which a tracker sample is
 *   treated as a miss and the entry is emitted with `isFound = false`.
 * @property algorithm stable id of the tracker that produced the
 *   trajectory — see the `ALGORITHM_*` constants.
 */
data class MotionTrackSettings(
    val enabled: Boolean = false,
    val overlayId: String? = null,
    val maskMode: MaskMode = MaskMode.NONE,
    val maskStrength: Float = 0.5f,
    val anchorU: Float = 0.5f,
    val anchorV: Float = 0.5f,
    val scaleWithBox: Boolean = false,
    val referenceBoxHeight: Float = 0.1f,
    val positionSmoothing: Float = 0.5f,
    val scaleSmoothing: Float = 0.7f,
    val minConfidence: Float = 0.35f,
    val algorithm: String = ALGORITHM_CSRT_V1
) {
    /** Which mask pass rides the trajectory, if any. */
    enum class MaskMode {
        /** No mask — only an overlay (text/sticker) follows the track. */
        NONE,

        /** Gaussian blur inside the tracked box. Export-only in v1. */
        BLUR,

        /** Pixelated mosaic inside the tracked box. Export-only in v1. */
        MOSAIC
    }

    companion object {
        val Identity = MotionTrackSettings(enabled = false)
        val Default = MotionTrackSettings()

        /** OpenCV CSRT — the primary appearance tracker. */
        const val ALGORITHM_CSRT_V1: String = "opencv-csrt-v1"

        /** OpenCV KCF — fallback when the tracking module lacks CSRT. */
        const val ALGORITHM_KCF_V1: String = "opencv-kcf-v1"

        /**
         * No OpenCV on the device / module missing. A pure-Kotlin
         * normalised-cross-correlation tracker over the ROI crop.
         */
        const val ALGORITHM_NCC_V1: String = "ncc-template-v1"
    }
}

/**
 * Per-clip settings for the **Voice Clean** tool.
 *
 * `Identity` means "no denoising" — the export pipeline uses the
 * original audio track.
 *
 * @property enabled whether the export pipeline should replace the
 *   clip's audio with the spectral-gated version.
 * @property strength 0..1 — over-subtraction factor. Higher = more
 *   aggressive noise removal at the cost of more speech artefacts
 *   (musical noise, slight muffling). Default 0.6.
 * @property algorithm stable id — `"spectral-gate-mmse-v1"` today.
 */
/**
 * PHASE A (Persistence Integrity) — per-clip "look" settings that used
 * to be lost on save→reopen because `VideoClipEntity` carried no
 * column and `ClipAiTools` no field for them.
 *
 * Grouping them in one settings object (rather than seven flat blob
 * keys) follows the same convention as `matting` / `autoFrame` /
 * `speedCurve`: the whole block is dropped when it holds an identity
 * value, keeping the persisted blob small, and the whole block is
 * restored in ONE read so a partially-written row can never leave a
 * clip in a half-transformed state.
 *
 * Rides the AI-tools blob, so no Room migration is needed — exactly
 * the `speedCurve` precedent.
 */
data class ClipLookSettings(
    val videoEffect: VideoEffect = VideoEffect.NONE,
    val colorGrading: ColorGrading = ColorGrading.Identity,
    val opacity: Float = 1f,
    val fillMode: FillMode = FillMode.FIT,
    val transform: ClipTransform = ClipTransform(),
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    /**
     * 2026-10-21 — the per-clip AI upscaler / enhancer settings.
     *
     * They used to live only in memory: [com.aicreatorstudio.app.model.VideoClip.upscale]
     * was written by the Upscaler panel and read by the preview, but
     * there was nowhere in the persisted blob to put it — so reopening
     * the project silently dropped the user's choice, and the export
     * (which reads the project) never saw it at all.
     *
     * They ride the AI-tools blob for the same reason [speedCurve]
     * documents: the value is optional, defaults to "off", and a Room
     * column would cost a schema migration to store what is almost
     * always the default.
     */
    val upscale: UpscalerSettings = UpscalerSettings.Identity
) {
    /** True when nothing the user could have changed is off-default. */
    val isIdentity: Boolean
        get() = videoEffect == VideoEffect.NONE &&
            colorGrading.isIdentity &&
            opacity == 1f &&
            fillMode == FillMode.FIT &&
            transform.isIdentity &&
            fadeInMs == 0L &&
            fadeOutMs == 0L &&
            upscale == UpscalerSettings.Identity

    companion object {
        val Identity = ClipLookSettings()
    }
}

data class VoiceCleanSettings(
    val enabled: Boolean = false,
    val strength: Float = 0.6f,
    val algorithm: String = "spectral-gate-mmse-v1"
) {
    companion object {
        val Identity = VoiceCleanSettings(enabled = false)
        val Default = VoiceCleanSettings()
    }
}

/**
 * PHASE 7 — one user-approved highlight range. Clip-local ms (offset
 * into the clip's effective/post-trim span), so trims move it
 * correctly and export never needs it. Metadata only.
 *
 * Persisted in the AI-tools blob (no Room migration, like
 * `speedCurve`); empty list = no highlights.
 */
data class HighlightRange(
    val startMs: Long,
    val endMs: Long,
    val score: Float = 0f,
    val reason: String = ""
) {
    val durationMs: Long get() = endMs - startMs

    companion object {
        val Empty: List<HighlightRange> = emptyList()
    }
}