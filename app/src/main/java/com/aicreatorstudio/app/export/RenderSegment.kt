package com.aicreatorstudio.app.export

import com.aicreatorstudio.app.model.CanvasBackground
import com.aicreatorstudio.app.model.CanvasPreset
import com.aicreatorstudio.app.model.ClipAnimation
import com.aicreatorstudio.app.model.ClipTransform
import com.aicreatorstudio.app.model.ColorGrading
import com.aicreatorstudio.app.model.CropPreset
import com.aicreatorstudio.app.model.FillMode
import com.aicreatorstudio.app.model.AutoFrameSettings
import com.aicreatorstudio.app.model.CropKeyframe
import com.aicreatorstudio.app.model.MattingSettings
import com.aicreatorstudio.app.model.MotionTrackSettings
import com.aicreatorstudio.app.model.SpeedCurve
import com.aicreatorstudio.app.model.TextOverlay
import com.aicreatorstudio.app.model.TransitionConfig
import com.aicreatorstudio.app.model.VideoEffect
import com.aicreatorstudio.app.model.VoiceCleanSettings

/**
 * One segment of the render graph.
 *
 * A render graph is just an ordered list of these, concatenated end-to-end
 * to form the final output. This is the *only* shape the export engine
 * consumes — the engine never reaches back into [com.aicreatorstudio.app.model.VideoClip]
 * or [com.aicreatorstudio.app.model.VideoProject]. This keeps the engine
 * testable and means UI/data changes don't ripple into the render path.
 *
 * All values are pre-resolved: trim ranges are absolute (startMs/endMs in
 * the source clip's timeline, not the project's), and the segment already
 * knows which speed factor, color adjustment, and rotation to apply.
 *
 * @param sourceUri the `content://` URI of the source clip on the device
 * @param sourceStartMs where in the source clip this segment begins (ms)
 * @param sourceEndMs where in the source clip this segment ends (ms)
 * @param speed playback speed multiplier for this segment (1.0 = normal)
 * @param brightness linear RGB brightness multiplier (1.0 = no change)
 * @param contrast linear RGB contrast multiplier (1.0 = no change)
 * @param saturation linear RGB saturation multiplier (1.0 = no change)
 * @param rotationDegrees 0, 90, 180, or 270 — only multiples of 90 are
 *   reliably supported by Media3's [androidx.media3.effect.ScaleAndRotateTransformation]
 * @param displayName human-readable name of the underlying clip, used in
 *   logging and progress UI
 * @param fillMode how the source fills the output canvas (FIT, FILL,
 *   MANUAL). FIT preserves the source aspect ratio; FILL covers the
 *   canvas (crops as needed); MANUAL defers to [transform].
 * @param transform freeform pan/zoom/rotate/flip applied on top of the
 *   fill mode. Only meaningful in MANUAL mode. The engine splits it across
 *   two stages: `rotation` and the `flipH`/`flipV` flags drive
 *   [androidx.media3.effect.ScaleAndRotateTransformation], while the
 *   uniform `scale` and the `offsetX`/`offsetY` pan are folded into the
 *   per-frame pose in
 *   [com.aicreatorstudio.app.export.ClipMotionEffects]. The scale cannot
 *   live in `ScaleAndRotateTransformation` — it grows the frame and the
 *   later `Presentation` shrinks it back, cancelling the zoom out — and
 *   that effect has no translation primitive for the pan at all. See
 *   [com.aicreatorstudio.app.model.ClipMotion.compose].
 * @param videoEffect famous-effect filter (SEPIA, NOIR, …). The engine
 *   converts this to a Media3 [androidx.media3.effect.RgbMatrix] when
 *   the value is not [VideoEffect.NONE].
 * @param colorGrading the Lumetri-style grade. The engine bakes the
 *   full grading chain (exposure, B/C/S, whites, blacks, H/S, temp/tint,
 *   saturation, vibrance, hue shifts, fade, clarity) when this is not
 *   [ColorGrading.Identity].
 * @param cropPreset the user-selected crop preset (1:1, 9:16, …) — only
 *   used to source the aspect ratio below; the engine doesn't read
 *   preset names, only the numeric ratio.
 * @param cropAspectRatio the user-selected crop aspect ratio. 0f means
 *   "no crop" (FREE). When non-zero the engine resizes the output
 *   frame to the source-aspect × this ratio via Media3's
 *   [androidx.media3.effect.Presentation].
 * @param opacity 1f = opaque, 0f = transparent. An MP4 has no alpha
 *   channel, so a reduced opacity is reproduced as a full-frame black
 *   wash of `1 - opacity` — the same mechanism the fade family and the
 *   fading motion presets use. All three sources are folded into one
 *   alpha by
 *   [com.aicreatorstudio.app.model.ClipMotion.compose] and one pass by
 *   [com.aicreatorstudio.app.export.ClipMotionEffects]. The white flash
 *   pass deliberately ignores this, matching the preview, which draws its
 *   flash overlays on top of the clip rather than through its alpha.
 * @param volume 1f = unity gain, 0f = muted. The engine routes this
 *   through a [androidx.media3.common.audio.AudioProcessor] that
 *   multiplies PCM by this value. v1 limitation: only the first
 *   audio processor in the chain is honored — per-clip volume is
 *   baked, not mixed with the global timeline volume.
 * @param fadeInMs audio fade-in length in clip-local **output** ms
 *   (i.e. after [speed] is applied). `0` means no fade. Baked into the
 *   export by a ramping [androidx.media3.common.audio.AudioProcessor]
 *   that shares its curve with the preview via
 *   [com.aicreatorstudio.app.model.AudioFade].
 * @param fadeOutMs audio fade-out length, measured back from the end of
 *   the clip's output duration. See [fadeInMs].
 * @param animation per-clip motion preset (fade / slide / zoom /
 *   rotate). Evaluated through
 *   [com.aicreatorstudio.app.model.ClipMotion] — the same curve the live
 *   preview uses — and baked into the export by
 *   [com.aicreatorstudio.app.export.ClipMotionEffects] as a per-frame
 *   geometry matrix plus, for the fade family, a full-frame wash in the
 *   canvas colour (an MP4 has no alpha channel to write). The preset ramps
 *   once across the clip's source span rather than looping.
 *   [com.aicreatorstudio.app.model.ClipAnimation.NONE] appends nothing to
 *   the effect chain.
 * @param canvasBackground the canvas letterbox/background color. The
 *   engine uses this to pick a [androidx.media3.effect.Presentation]
 *   background color for the output. Blur backgrounds are preview-only
 *   in v1.
 * @param canvasPreset the canvas aspect ratio the project renders to.
 *   The engine sizes the output [androidx.media3.effect.Presentation]
 *   to `canvasPreset.exportWidth` × `canvasPreset.exportHeight` when
 *   the preset is not [CanvasPreset.FREE]. FREE means "use the source
 *   frame size" (legacy behavior).
 * @param matting optional per-clip matting settings. When `enabled`
 *   the export composites the foreground over the chosen replacement
 *   background — as a CPU pre-render
 *   ([com.aicreatorstudio.app.export.AiExportHooks.prepareSourceUri] →
 *   `compositeMatting`), **not** a Media3 effect: the Transformer
 *   consumes the already-composited intermediate. The live preview
 *   does not composite (it shows a "BG Remove ready" chip), so for
 *   this field the export is ahead of the preview.
 *   [com.aicreatorstudio.app.ai.PersonMatteEffect] is a superseded
 *   stub nobody instantiates. `null` /
 *   [MattingSettings.Identity] is a no-op.
 * @param autoFrame optional per-clip auto-frame settings. When
 *   `enabled` and [cropKeyframes] is non-empty the export pipeline
 *   pre-renders a reframed intermediate that keeps the tracked
 *   subject inside the target aspect. `null` / Identity is a no-op.
 * @param cropKeyframes time-keyed crop windows from the Auto Frame
 *   engine. Empty means "no tracking output".
 * @param voiceClean optional per-clip denoise settings. When
 *   `enabled` and the engine's cleaned WAV exists in the voiceclean
 *   cache, the export pipeline swaps the segment audio for the
 *   cleaned track (same duration/rate/channels). `null` / Identity
 *   is a no-op.
 * @param projectId id of the project this segment belongs to. Used by
 *   the matting sidecar writer to scope the per-frame mask PNGs to a
 *   project directory. `null` for segments that were built without
 *   project context (used only by unit tests).
 * @param clipId id of the source clip. Used together with
 *   [projectId] to scope matting sidecar files. `null` when not
 *   applicable.
 */
data class RenderSegment(
    val sourceUri: String,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val speed: Float,
    val brightness: Float,
    val contrast: Float,
    val saturation: Float,
    val rotationDegrees: Int,
    val displayName: String,
    val fillMode: FillMode = FillMode.FIT,
    val transform: ClipTransform = ClipTransform(),
    val videoEffect: VideoEffect = VideoEffect.NONE,
    val colorGrading: ColorGrading = ColorGrading.Identity,
    /**
     * 2026-10-21 — the AI upscaler / enhancer settings for this
     * segment.
     *
     * The Upscaler panel showed a contrast-adaptive sharpening pass in
     * the preview, but the export had no field to receive it — so the
     * exported file never carried the enhancement the user could see on
     * screen. The engine now bakes the same CAS matrix the preview
     * renders; see [com.aicreatorstudio.app.export.VideoExportEngine].
     *
     * Identity (preset = OFF) means "no enhancement", which is the
     * default for every untouched clip.
     */
    val upscale: com.aicreatorstudio.app.model.UpscalerSettings =
        com.aicreatorstudio.app.model.UpscalerSettings.Identity,
    val cropPreset: CropPreset = CropPreset.FREE,
    val cropAspectRatio: Float = 0f,
    val opacity: Float = 1f,
    val volume: Float = 1f,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    val animation: ClipAnimation = ClipAnimation.NONE,
    val canvasBackground: CanvasBackground = CanvasBackground.Black,
    val canvasPreset: CanvasPreset = CanvasPreset.FREE,
    val matting: MattingSettings? = null,
    val autoFrame: AutoFrameSettings? = null,
    val cropKeyframes: List<CropKeyframe> = emptyList(),
    /**
     * 2026-09-28 — Motion Tracking settings for this segment.
     *
     * When `enabled` the export pipeline binds [trackingOverlay] (a
     * text/sticker overlay) and/or a blur/mosaic mask to the moving
     * target described by [cropKeyframes]. See
     * [com.aicreatorstudio.app.export.AiExportHooks] and
     * `docs/MOTION_TRACKING_IMPLEMENTATION.md`.
     *
     * Ordering note: this pass runs **after** the Auto Frame reframe,
     * because a tracked box describes a position in the frame the
     * viewer finally sees. Binding before a reframe would let the
     * reframe crop the overlay away.
     */
    val motionTrack: MotionTrackSettings? = null,
    /**
     * The overlay that follows the track, resolved by the caller from
     * [MotionTrackSettings.overlayId]. Null when the track drives only
     * a mask, or when the referenced overlay no longer exists (the
     * user deleted it) — in which case the mask still renders and no
     * overlay is burned.
     */
    val trackingOverlay: TextOverlay? = null,
    val voiceClean: VoiceCleanSettings? = null,
    /**
     * Entering transition for this segment, or `null` for none. Decoded
     * from the owning clip's `transitionIn` string.
     *
     * **2026-09-16:** until now transitions never reached the render graph
     * at all — the clip carried them, the DB persisted them, and the
     * export had no field to receive them, so every transition was
     * silently dropped from the output file. The engine now bakes them as
     * a per-frame geometry matrix plus a flash/fade wash; see
     * [com.aicreatorstudio.app.export.ClipMotionEffects].
     */
    val transitionIn: TransitionConfig? = null,
    /** Leaving transition for this segment, or `null`. See [transitionIn]. */
    val transitionOut: TransitionConfig? = null,
    val projectId: String? = null,
    val clipId: String? = null,
    /**
     * Timeline position (ms) of the owning clip, normalised so an
     * "auto layout" sentinel (`< 0`) reads as `0`. The export engine
     * uses this to rebase caption timings onto the composition
     * timeline: a segment's clips start at its own
     * [timelineStartMs] in the editor but at its cumulative output
     * origin in the exported file.
     */
    val timelineStartMs: Long = 0L,
    /**
     * Rotation-corrected display width of the source in pixels, or `0`
     * when unknown. Only consulted for the `FREE` ("Original") canvas
     * preset with more than one segment, where Media3 needs one
     * consistent output size across the whole sequence.
     */
    val sourceWidthPx: Int = 0,
    /** Rotation-corrected display height of the source, or `0`. See [sourceWidthPx]. */
    val sourceHeightPx: Int = 0,
    /**
     * 2026-09-29 — variable-speed ramp for this segment, or `null` for
     * the constant [speed] path.
     *
     * When this is non-null **and** [com.aicreatorstudio.app.model.SpeedCurve.isActive]
     * the engine retimes the segment with a curve-backed
     * `SpeedProvider` instead of a constant one, and the audio is
     * stretched by the matching `SpeedChangingAudioProcessor`. The
     * constant [speed] is *not* ignored in that case — it multiplies the
     * ramp, so the old control keeps meaning "2× the whole thing".
     *
     * `null` or inactive is a strict no-op: the export takes the same
     * code path it always did, which is what keeps every pre-existing
     * project byte-identical.
     */
    val speedCurve: SpeedCurve? = null,
    /**
     * 2026-10-13 — Voice Changer effect for this segment's audio, or
     * [com.aicreatorstudio.app.model.VoiceEffect.NONE].
     *
     * Applied by [VoiceEffectAudioProcessor] **before** the speed
     * processor, which is the only order that matches the live preview:
     * there, a custom `AudioProcessor` sits ahead of the sink's own Sonic
     * stage (see `DefaultAudioSink.DefaultAudioProcessorChain`), so the
     * voice transformation always runs on source-rate audio and the rate
     * change is applied to its result. Reversing the two here would make
     * a ramped clip's echo tail the wrong length **in the exported file
     * only** — precisely the preview/export mismatch this project keeps
     * having to fix.
     *
     * [com.aicreatorstudio.app.model.VoiceEffect.NONE] appends nothing to
     * the chain, so a project that never opened the panel exports
     * byte-identically.
     */
    val voiceEffect: com.aicreatorstudio.app.model.VoiceEffect =
        com.aicreatorstudio.app.model.VoiceEffect.NONE,
    /**
     * 2026-10-13 — picture-in-picture overlays that may cover this
     * segment.
     *
     * This is the **project's whole overlay list**, not a pre-filtered
     * subset: [RenderSegment] is built per clip and an overlay's window
     * is expressed on the editor timeline, so which overlays actually
     * land on this segment is only knowable once the segment's own
     * timeline span is known. `OverlayCompositeRenderer` filters by that
     * span at construction.
     *
     * Empty is a strict no-op — the pre-pass is skipped entirely, so a
     * project with no overlays exports through exactly the code path it
     * did before this feature existed.
     */
    val mediaOverlays: List<com.aicreatorstudio.app.model.VideoOverlay> = emptyList()
) {
    /** Duration of the *source* range this segment covers. */
    val sourceDurationMs: Long
        get() = (sourceEndMs - sourceStartMs).coerceAtLeast(0L)

    /**
     * Duration of the *output* (after speed is applied).
     *
     * **2026-09-29 — the ramp is honoured here, not just in the
     * engine.** This property is the composition's clock: it feeds
     * `RenderGraphResult.totalOutputDurationMs`, `CompositionTimeline`
     * (which rebases every caption), and the per-segment progress
     * fractions. A ramped segment that reported its un-ramped length
     * would put every later clip's subtitles at the wrong time and
     * make the export progress bar lie, so the ramp has to be counted
     * here even though Media3 would still render it correctly on its
     * own.
     *
     * Delegates to [com.aicreatorstudio.app.model.util.SpeedCurveInterpolator],
     * which reproduces Media3's own piecewise-constant integration, so
     * this number and the file agree by construction rather than by
     * luck. See that class's KDoc.
     */
    val outputDurationMs: Long
        get() {
            val source = sourceDurationMs
            if (source <= 0L) return 0L
            val curve = speedCurve
            val effectiveSpeed = if (speed > 0f) speed else 1f
            if (curve == null || !curve.isActive) {
                return if (effectiveSpeed != 1f) (source / effectiveSpeed).toLong() else source
            }
            // The constant `speed` multiplies the ramp, exactly as it
            // does in the engine's provider — otherwise this clock and
            // the rendered file would disagree by the constant factor.
            val combined = curve.copy(
                points = curve.points.map {
                    it.copy(speed = it.speed * effectiveSpeed)
                }
            )
            return com.aicreatorstudio.app.model.util.SpeedCurveInterpolator
                .outputDurationUs(source * 1_000L, combined) / 1_000L
        }

    /**
     * Whether this segment is driven by a ramp rather than a constant
     * rate. The single predicate the engine branches on, so the two
     * render paths cannot disagree about when to switch.
     */
    val usesSpeedCurve: Boolean
        get() = speedCurve?.isActive == true
}
