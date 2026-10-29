package com.aicreatorstudio.app.export

import com.aicreatorstudio.app.model.TransitionConfig
import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.model.VideoProject

/**
 * Result of attempting to turn a [VideoProject] into a render graph.
 *
 * The build can fail in two distinct ways: the project itself is missing
 * ([EmptyProject]), or all clips turned out to be unusable after applying
 * the per-clip transformations ([NoRenderableClips]). Splitting these makes
 * the UI's error messaging precise.
 *
 * The [Success] variant carries the project's canvas preset and canvas
 * background so the engine can size and color the output frame. These
 * are project-level, not per-clip, so they live outside [RenderSegment]
 * (which is per-clip).
 */
sealed interface RenderGraphResult {
    data class Success(
        val segments: List<RenderSegment>,
        val canvasPreset: com.aicreatorstudio.app.model.CanvasPreset =
            com.aicreatorstudio.app.model.CanvasPreset.FREE,
        val canvasBackground: com.aicreatorstudio.app.model.CanvasBackground =
            com.aicreatorstudio.app.model.CanvasBackground.Black,
        /**
         * Audio-only clips, kept separate from [segments] because they are
         * **mixed in as an additional audio track** rather than
         * concatenated onto the video. Empty when the project has none.
         *
         * **2026-09-16:** these used to be filtered out of the render graph
         * entirely — the editor played them through `AudioMixerPlayer` and
         * the exported MP4 simply had no music in it.
         */
        val audioSegments: List<RenderSegment> = emptyList()
    ) : RenderGraphResult {
        /**
         * Video-only length. Audio is clamped to this, so adding a music
         * track never changes how long the exported video is.
         */
        val totalOutputDurationMs: Long
            get() = segments.sumOf { it.outputDurationMs }
    }

    data object EmptyProject : RenderGraphResult
    data object NoRenderableClips : RenderGraphResult
}

/**
 * Pure function turning a [VideoProject] into an ordered [RenderGraphResult]
 * suitable for the export engine.
 *
 * **Audio-only clips are mixed in, not dropped (2026-09-16).** They come
 * back in [RenderGraphResult.Success.audioSegments] and the engine gives
 * each one its own Media3 sequence, so overlapping clips mix instead of
 * colliding — items inside a single sequence are not allowed to overlap.
 *
 * **v1 simplification:** `trimStartMs` and `trimEndMs` are honoured. A
 * `trimEndMs` of `-1` or `0` (your model's "no trim" sentinel) is treated
 * as "end of the source".
 *
 * **v1 simplification:** clips are emitted in `timelineStartMs` order
 * with no overlap resolution — overlap is itself a Phase 2 concept.
 */
object RenderGraphBuilder {

    fun build(project: VideoProject?): RenderGraphResult {
        if (project == null) return RenderGraphResult.EmptyProject
        if (project.clips.isEmpty()) return RenderGraphResult.EmptyProject

        val ordered = project.clips.sortedBy { it.timelineStartMs }
        // Carry the project id into the per-clip mapping so the
        // engine can scope sidecar files / mask lookups to the
        // project that owns them.
        val projectId = project.id
        // 2026-09-28: resolve Motion Tracking overlay ids to actual
        // overlays ONCE, here, rather than per clip or per frame. A
        // clip whose `overlayId` no longer resolves (the user deleted
        // the overlay) simply exports its mask and burns no text,
        // instead of throwing mid-export.
        val overlaysById = project.textOverlays.associateBy { it.id }
        // 2026-10-13 — PiP overlays are carried on every video segment and
        // filtered by TIME inside `OverlayCompositeRenderer`, not here. An
        // overlay's window is on the editor timeline while a segment only
        // knows its own clip, so the overlap test needs the segment's
        // resolved timeline span — which exists only after this mapping.
        val mediaOverlays = project.mediaOverlays
        val segments = ordered
            .filter { !it.isAudioOnly }
            .map { it.toRenderSegment(projectId, overlaysById, mediaOverlays) }
            // A segment with zero-length source range is useless.
            .filter { it.sourceDurationMs > 0L }

        if (segments.isEmpty()) return RenderGraphResult.NoRenderableClips

        // Audio-only clips ride along as a mixed-in audio track. They keep
        // their editor-timeline positions (the engine maps them onto the
        // composition timeline) and are deliberately NOT concatenated onto
        // the video, which would push them to the end of the file.
        val audioSegments = ordered
            .filter { it.isAudioOnly }
            .map { it.toRenderSegment(projectId, overlaysById, emptyList()) }
            .filter { it.sourceDurationMs > 0L }

        return RenderGraphResult.Success(
            segments = segments,
            canvasPreset = project.canvasPreset,
            canvasBackground = project.canvasBackground,
            audioSegments = audioSegments
        )
    }

    private fun VideoClip.toRenderSegment(
        projectId: String,
        overlaysById: Map<String, com.aicreatorstudio.app.model.TextOverlay>,
        mediaOverlays: List<com.aicreatorstudio.app.model.VideoOverlay>
    ): RenderSegment {
        val end = if (trimEndMs <= 0L) durationMs else trimEndMs
        // Combined rotation = base rotation + transform rotation, both
        // normalised to multiples of 90. The transform is identity by
        // default so this collapses to the previous behaviour for any
        // clip that hasn't been touched by the Transform panel.
        val combinedRotation = normaliseRotation(rotation + transform.rotation)
        return RenderSegment(
            sourceUri = uri,
            sourceStartMs = trimStartMs.coerceAtLeast(0L),
            sourceEndMs = end.coerceAtMost(durationMs),
            speed = speed.takeIf { it > 0f } ?: 1f,
            brightness = brightness,
            contrast = contrast,
            saturation = saturation,
            rotationDegrees = combinedRotation,
            displayName = fileName,
            fillMode = fillMode,
            transform = transform,
            videoEffect = videoEffect,
            colorGrading = colorGrading,
            // 2026-10-21 — the AI upscaler settings now reach the
            // render graph. They used to be preview-only: the panel
            // showed a sharpened image and the exported file came out
            // unsharpened.
            upscale = upscale,
            cropPreset = cropPreset,
            cropAspectRatio = cropAspectRatio,
            opacity = opacity,
            volume = volume,
            // 2026-09-16: audio fades were write-only — the Audio panel
            // persisted them and nothing rendered them. Carried here so
            // the engine can bake them; the curve itself lives in
            // `model/AudioFade.kt` and is shared with the preview.
            fadeInMs = fadeInMs.coerceAtLeast(0L),
            fadeOutMs = fadeOutMs.coerceAtLeast(0L),
            animation = animation,
            matting = matting.takeIf { it.enabled },
            autoFrame = autoFrame.takeIf { it.enabled },
            cropKeyframes = cropKeyframes,
            // 2026-09-28 Motion Tracking. The trajectory is the same
            // `cropKeyframes` list; `motionTrack` is what tells the
            // export engine to bind rather than crop. The overlay is
            // resolved here so a dangling id degrades to "mask only"
            // instead of failing the export.
            motionTrack = motionTrack.takeIf { it.enabled },
            trackingOverlay = motionTrack
                .takeIf { it.enabled }
                ?.overlayId
                ?.let { overlaysById[it] },
            voiceClean = voiceClean.takeIf { it.enabled },
            // 2026-09-16: decode the stored `TYPE:duration:intensity:EASING`
            // strings into configs the engine can evaluate per frame. A
            // malformed or absent value decodes to `null`, which the engine
            // treats as "no transition" — so this cannot regress an export
            // that never had one.
            transitionIn = TransitionConfig.decode(transitionIn),
            transitionOut = TransitionConfig.decode(transitionOut),
            projectId = projectId,
            clipId = id,
            // Normalise the "auto layout" sentinel (< 0) so the engine
            // can rebase caption times without re-deriving it.
            timelineStartMs = timelineStartMs.takeIf { it >= 0L } ?: 0L,
            sourceWidthPx = width,
            sourceHeightPx = height,
            // 2026-09-29 Speed Ramping. Passed through whether or not it
            // is active: `RenderSegment.usesSpeedCurve` is the single
            // predicate the engine branches on, so a curve that does
            // not actually vary still takes the constant-speed path and
            // the exported bytes do not change.
            speedCurve = speedCurve,
            // 2026-10-13 Voice Changer. Carried per segment so the export
            // applies it to the clip's own audio, in the same place in the
            // chain the preview does. NONE appends no processor at all.
            voiceEffect = voiceEffect,
            // 2026-10-13 PiP shape masks. The whole overlay list rides on
            // every video segment; `OverlayCompositeRenderer` narrows it to
            // the overlays whose window overlaps this segment's timeline
            // span. Audio-only segments get an empty list on purpose — a
            // picture-in-picture has no meaning on an audio track.
            mediaOverlays = mediaOverlays
        )
    }

    /**
     * Media3's `ScaleAndRotateTransformation` only reliably applies
     * multiples of 90°. Normalise here so the engine can pass the value
     * through directly.
     *
     * 2026-10-21 — the rule now lives in
     * [com.aicreatorstudio.app.model.snapRotationToQuarterTurn], next
     * to the model that stores the value, so the editor (which writes
     * it) and the export (which reads it) cannot drift apart. The
     * strategy is unchanged: snap *down* to the lower multiple of 90.
     * The alternative (round-to-nearest) is fine in isolation but
     * creates surprising behaviour with off-screen interactions — e.g.
     * a user who tilts to 89° expecting 0° gets an unexpected 90°.
     */
    private fun normaliseRotation(deg: Int): Int =
        com.aicreatorstudio.app.model.snapRotationToQuarterTurn(deg)
}
