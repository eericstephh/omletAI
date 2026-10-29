package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicreatorstudio.app.model.ClipAnimation
import com.aicreatorstudio.app.model.TextOverlay
import com.aicreatorstudio.app.model.TrackKind
import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.model.VideoOverlay
import com.aicreatorstudio.app.ui.components.GlassMaterial
import com.aicreatorstudio.app.ui.components.GlassSurface
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.TrackAudio
import com.aicreatorstudio.app.ui.theme.TrackOverlay
import com.aicreatorstudio.app.ui.theme.AccentBright
import com.aicreatorstudio.app.ui.theme.AccentPrimary
import com.aicreatorstudio.app.ui.theme.TrackSFX
import com.aicreatorstudio.app.ui.theme.TrackSubtitle
import com.aicreatorstudio.app.ui.theme.TrackText
import com.aicreatorstudio.app.ui.theme.TrackVideo
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSBlueBright
import com.aicreatorstudio.app.ui.theme.AccentCyan
import com.aicreatorstudio.app.ui.theme.BorderStrong
import com.aicreatorstudio.app.ui.theme.BorderSubtle
import com.aicreatorstudio.app.ui.theme.CinematicSurface
import com.aicreatorstudio.app.ui.theme.LabelTertiary
import com.aicreatorstudio.app.ui.theme.SurfaceInset
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Professional multi-layer timeline.
 *
 * Visual layers (top → bottom) — each row gets its own labelled rail and
 * a 1.0-tall horizontal lane:
 *   1. Time ruler           (with a "now" line for the playhead)
 *   2. Video                (1 lane)
 *   3. Music                (1 lane, hidden if no music)
 *   4. SFX                  (1 lane, hidden if no SFX)
 *   5. Text 1..N            (up to 5 lanes, shown when Text or Subtitles is active)
 *   6. Subtitle             (1 lane, shown when Subtitles is active)
 *   7. Overlay 1..N         (up to 3 lanes, shown when Overlay tool is active)
 *   8. FX                   (1 lane, shown when there are effects)
 *
 * Interaction:
 *   - The entire lane stack shares a single [horizontalScroll] state, so
 *     ruler + tracks always stay in lock-step.
 *   - A pinned playhead column at the **horizontal center of the visible
 *     lane** marks the current playback position. The lane scrolls
 *     underneath the playhead so the playhead itself never moves.
 *   - When the player is playing, the lane auto-scrolls to keep the
 *     playhead at the visual center — this is the CapCut-style
 *     "playhead stays still, content flows" experience.
 *   - Drag on the lane (outside the playhead hit column): horizontal
 *     scroll only. The playhead time is not changed by lane panning.
 *   - Drag inside the playhead hit column: scrub (seek with
 *     millisecond precision). The lane does not pan while the user
 *     scrubs, so the finger follows the playhead.
 *   - Pinch on lane area: zoom (`pixelsPerMs`).
 *   - Drag a clip body: move. Drag a clip edge: trim. Snaps to
 *     [EditorLayoutSpec.TIMELINE_SNAP_MS] on release.
 *   - Tap a clip: select.
 */
@Composable
fun EditorTimeline(
    clips: List<VideoClip>,
    overlays: List<VideoOverlay>,
    textOverlays: List<TextOverlay>,
    // 2026-09-06 — audio objects (Video Audio / Music / SFX) rendered
    // as selectable tiles on their lanes.
    audioClips: List<com.aicreatorstudio.app.model.AudioClip> = emptyList(),
    selectedAudioClipId: String? = null,
    onAudioClipSelected: (String?) -> Unit = {},
    /**
     * 2026-10-20 — drag-to-move for an audio tile, `(audioClipId, deltaMs)`.
     * Video clips have had `onClipMoved` since 2026-09-06; the audio lane's
     * tiles only ever had `clickable`, so a soundtrack could be selected but
     * never repositioned. The value is a delta because the tile's gesture
     * cannot read a live start position (see [AudioLaneRow]). Default no-op
     * keeps preview/test callers compiling.
     */
    onAudioClipMoved: (String, Long) -> Unit = { _, _ -> },
    /**
     * 2026-10-20 — fired when a clip/audio drag gesture RELEASES (never on
     * start). The editor uses it to clear its undo-coalescing latch
     * (`lastDraggedClipId` / `lastDraggedAudioId`), which otherwise stays
     * latched for the whole session: after an undo, re-dragging the same
     * object would skip `historyDelegate.push()` and the move would not be
     * undoable at all. `lastTrimmedClipId` has always been cleared this way
     * (`onClipTrimEnd`); the drag latches simply never got one.
     */
    onDragEnd: () -> Unit = {},
    activeSubtitleId: String?,
    selectedClipId: String?,
    // PHASE B3 — the Subtitle track row now uses this to
    // highlight the currently-selected AI subtitle, and the
    // dedicated `onTextOverlaySelected` callback (not
    // onClipSelected) to route a tap. Pre-B3 the SUBTITLE
    // branch reused onClipSelected, which either no-op'd or
    // selected the wrong video clip.
    selectedTextId: String? = null,
    currentPositionMs: Long,
    durationMs: Long,
    pixelsPerMs: Float,
    isPlaying: Boolean,
    // 2026-09-06: passed to the playhead overlay's auto-scroll gate
    // so the lane follows the finger while the user drag-scrubs.
    isScrubbing: Boolean = false,
    visibleTrackKinds: Set<TrackKind>,
    focusedTrackKind: TrackKind?,
    onClipSelected: (String?) -> Unit,
    onClipMoved: (String, Long) -> Unit,
    onClipTrimmed: (String, Long, Long) -> Unit,
    // 2026-10-04 — fired once when a trim drag ends, with the trimmed
    // clip's id. Default no-op so existing preview/test callers compile.
    onClipTrimEnd: (String) -> Unit = {},
    // 2026-10-05 — "+" header button: append a picked video to the project.
    onAddClip: () -> Unit = {},
    isAddingClip: Boolean = false,
    onSeek: (Long) -> Unit,
    onScrubStart: () -> Unit,
    onScrubSeek: (Long) -> Unit,
    onScrubEnd: () -> Unit,
    onZoomChange: (Float) -> Unit,
    onToggleTrack: (TrackKind) -> Unit,
    // PHASE B3 — Subtitle-lane tap target. Defaults to a
    // no-op so unrelated test/preview callers keep working.
    onTextOverlaySelected: (String) -> Unit = {},
    /**
     * 2026-09-29 — every detected beat onset in the project, in timeline
     * time and ascending. Threaded into the clip lanes so a drag snaps to
     * the music; empty by default so callers that predate the feature (and
     * previews / tests) are unaffected.
     */
    beatMarkers: List<Long> = emptyList(),
    // 2026-10-05 — real per-clip PCM peaks, keyed by
    // `AudioClip.waveformCacheKey ?: id`, computed by the ViewModel.
    // Empty map = the lanes draw the loading placeholder.
    audioWaveforms: Map<String, List<com.aicreatorstudio.app.model.AudioWaveformPeak>> = emptyMap(),
    // 2026-10-05 — real filmstrip frames per video clip, keyed
    // "<clipId>@<uri>" (matches the ViewModel's cache key).
    clipThumbnails: Map<String, List<androidx.compose.ui.graphics.ImageBitmap>> = emptyMap(),
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current

    val totalDuration = max(
        durationMs,
        clips.maxOfOrNull { clip ->
            val start = clip.timelineStartMs.takeIf { it >= 0 } ?: 0L
            start + clip.effectiveDurationMs
        } ?: 0L
    ).coerceAtLeast(60_000L)

    val timelineWidthPx = with(density) {
        (totalDuration * pixelsPerMs).toFloat()
    }
    val timelineWidthDp = with(density) { timelineWidthPx.toDp() }

    // A single scroll state drives the ruler and every track so they
    // stay perfectly aligned horizontally.
    val trackScroll = rememberScrollState()
    // Vertical scroll state — the entire stack of track rows scrolls
    // up/down independently of the pinned time ruler and the pinned
    // center playhead. The playhead is drawn in an overlay anchored to
    // the visible viewport, so it never moves when the user scrolls
    // vertically; only the lanes underneath move.
    val laneVerticalScroll = rememberScrollState()

    // True while the user is actively dragging a clip body or trim
    // edge. The auto-scroll-while-playing effect below reads this so
    // the lane does not scroll out from under the user's finger mid-
    // drag.
    var isDraggingClip by remember { mutableStateOf(false) }

    // Every lane reports drag state through this one lambda so the
    // auto-scroll gate and the editor's undo latch cannot drift apart.
    val setDraggingClip: (Boolean) -> Unit = { dragging ->
        isDraggingClip = dragging
        if (!dragging) onDragEnd()
    }

    GlassSurface(
        shape = RoundedCornerShape(EditorLayoutSpec.TimelineCornerRadius),
        material = GlassMaterial.Thin,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            TimelineHeader(
                pixelsPerMs = pixelsPerMs,
                visibleTrackKinds = visibleTrackKinds,
                focusedTrackKind = focusedTrackKind,
                onZoomChange = onZoomChange,
                onToggleTrack = onToggleTrack,
                onAddClip = onAddClip,
                isAddingClip = isAddingClip
            )
            Spacer(Modifier.height(6.dp))

            // Visual rail column width — same constant used by every track
            // so labels line up vertically with the ruler's left margin.
            val railWidth = EditorLayoutSpec.TimelineLabelColumnWidth

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                val stageWidthDp = maxWidth
                val laneWidthDp = stageWidthDp - railWidth
                val laneWidthPx = with(density) { laneWidthDp.toPx() }

                // --- Track-stack data (computed up-front so the
                // viewport height below can wrap short stacks and
                // cap tall ones) ----------------------------------
                val videoClips = clips.filter { !it.isAudioOnly }
                val musicClips = clips.filter { it.isAudioOnly && it.audioCategory == "music" }
                val sfxClips = clips.filter { it.isAudioOnly && it.audioCategory == "sfx" }
                // 2026-09-06 — audio objects belonging to video clips
                // (paired audio), rendered on their own "Video Audio"
                // lane below the video.
                val videoAudioClips = audioClips
                    .filter { it.originVideoClipId != null }
                    .sortedBy { it.startTimeMs }

                val activeTrackKinds: List<TrackKind> = buildList {
                    add(TrackKind.VIDEO) // always
                    // 2026-09-06 — the "Video Audio" lane shows whenever
                    // the project carries at least one audio object for
                    // a video clip (seeded automatically on project
                    // load). Always visible right under the video so
                    // the paired audio is obviously selectable.
                    if (videoAudioClips.isNotEmpty()) {
                        add(TrackKind.VIDEO_AUDIO)
                    }
                    if (visibleTrackKinds.contains(TrackKind.AUDIO_MUSIC) && musicClips.isNotEmpty()) {
                        add(TrackKind.AUDIO_MUSIC)
                    }
                    if (visibleTrackKinds.contains(TrackKind.AUDIO_SFX) && sfxClips.isNotEmpty()) {
                        add(TrackKind.AUDIO_SFX)
                    }
                    if (visibleTrackKinds.contains(TrackKind.TEXT)) {
                        add(TrackKind.TEXT)
                    }
                    if (visibleTrackKinds.contains(TrackKind.SUBTITLE)) {
                        add(TrackKind.SUBTITLE)
                    }
                    if (visibleTrackKinds.contains(TrackKind.OVERLAY)) {
                        repeat(TrackKind.OVERLAY.maxLayers) { add(TrackKind.OVERLAY) }
                    }
                    if (visibleTrackKinds.contains(TrackKind.EFFECTS) &&
                        clips.any {
                            it.cropPreset.name != "FREE" || it.rotation != 0 ||
                                it.animation != ClipAnimation.NONE
                        }
                    ) {
                        add(TrackKind.EFFECTS)
                    }
                }

                // 2026-09-16 VERTICAL-SCROLL FIX — the lane viewport
                // wraps short stacks and caps tall ones at
                // TimelineMaxVisibleRows so the verticalScroll below
                // actually engages. Previously the stack Column had
                // NO height bound: it measured every row at full
                // height, the Box grew unbounded, and up/down scroll
                // never worked (tracks just ran off-screen).
                val rowsHeightDp =
                    EditorLayoutSpec.TimelineTrackRowHeight * activeTrackKinds.size
                val stackViewportHeight = rowsHeightDp.coerceAtMost(
                    EditorLayoutSpec.TimelineTrackRowHeight *
                        EditorLayoutSpec.TimelineMaxVisibleRows
                )

                Column(modifier = Modifier.fillMaxWidth()) {
                // --- Ruler row (rail + scrollable ruler, pinned) ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(EditorLayoutSpec.TimelineRulerHeight)
                ) {
                    Box(
                        modifier = Modifier
                            .width(railWidth)
                            .fillMaxHeight()
                            .background(Color.White.copy(alpha = 0.03f))
                            .border(1.dp, HairlineSubtle, RoundedCornerShape(0.dp))
                    )
                    Box(
                        modifier = Modifier
                            .width(laneWidthDp)
                            .fillMaxHeight()
                            .horizontalScroll(trackScroll)
                    ) {
                        Box(modifier = Modifier.width(timelineWidthDp)) {
                            TimeRuler(
                                totalDurationMs = totalDuration,
                                pixelsPerMs = pixelsPerMs,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                // --- Scrollable track stack (bounded viewport) --------
                // The rows live in a verticalScroll Column inside a
                // FIXED-height viewport (see stackViewportHeight
                // above): short stacks wrap, tall stacks scroll. The
                // ruler above stays pinned, and the playhead overlay
                // below fills the viewport — never the full content —
                // so it always spans exactly the visible rows.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(stackViewportHeight)
                ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(laneVerticalScroll)
                ) {
                    activeTrackKinds.forEachIndexed { rowIndex, kind ->
                        when (kind) {
                            TrackKind.VIDEO -> TrackRow(
                                label = "Video",
                                accent = TrackVideo,
                                icon = Icons.Filled.Movie,
                                clips = videoClips,
                                selectedClipId = selectedClipId,
                                pixelsPerMs = pixelsPerMs,
                                pixelsPerMsState = pixelsPerMs,
                                onPixelsPerMsChange = onZoomChange,
                                onClipSelected = onClipSelected,
                                onClipMoved = onClipMoved,
                                onClipTrimmed = onClipTrimmed,
                                onClipTrimEnd = onClipTrimEnd,
                                audioWaveforms = audioWaveforms,
                                hScroll = trackScroll,
                                railWidth = railWidth,
                                laneWidthDp = laneWidthDp,
                                trackWidthDp = timelineWidthDp,
                                showWaveform = false,
                                showThumbnails = true,
                                clipThumbnails = clipThumbnails,
                                isFocused = focusedTrackKind == kind,
                                onLaneScrub = onSeek,
                                onLaneZoom = onZoomChange,
                                onClipDragStateChange = setDraggingClip,
                                beatMarkers = beatMarkers,
                                modifier = Modifier.fillMaxWidth()
                            )
                            // 2026-09-06 — Video Audio lane: the paired
                            // audio of the imported video(s), rendered
                            // as SELECTABLE audio tiles with waveform.
                            TrackKind.VIDEO_AUDIO -> AudioLaneRow(
                                label = "Video Audio",
                                accent = TrackAudio,
                                icon = Icons.AutoMirrored.Filled.VolumeUp,
                                audioClips = videoAudioClips,
                                selectedAudioClipId = selectedAudioClipId,
                                pixelsPerMs = pixelsPerMs,
                                railWidth = railWidth,
                                laneWidthDp = laneWidthDp,
                                trackWidthDp = timelineWidthDp,
                                hScroll = trackScroll,
                                onAudioClipSelected = onAudioClipSelected,
                                onAudioClipMoved = onAudioClipMoved,
                                // Reuses the clip-drag gate: while an audio
                                // tile is being dragged the playhead must not
                                // auto-scroll the lane out from under it.
                                onAudioClipDragStateChange = setDraggingClip,
                                isFocused = focusedTrackKind == kind,
                                audioWaveforms = audioWaveforms,
                                modifier = Modifier.fillMaxWidth()
                            )
                            TrackKind.AUDIO_MUSIC -> TrackRow(
                                label = "Music",
                                accent = TrackAudio,
                                icon = Icons.Filled.MusicNote,
                                clips = musicClips,
                                selectedClipId = selectedClipId,
                                pixelsPerMs = pixelsPerMs,
                                pixelsPerMsState = pixelsPerMs,
                                onPixelsPerMsChange = onZoomChange,
                                onClipSelected = onClipSelected,
                                onClipMoved = onClipMoved,
                                onClipTrimmed = onClipTrimmed,
                                onClipTrimEnd = onClipTrimEnd,
                                audioWaveforms = audioWaveforms,
                                hScroll = trackScroll,
                                railWidth = railWidth,
                                laneWidthDp = laneWidthDp,
                                trackWidthDp = timelineWidthDp,
                                showWaveform = true,
                                showThumbnails = false,
                                isFocused = focusedTrackKind == kind,
                                onLaneScrub = onSeek,
                                onLaneZoom = onZoomChange,
                                onClipDragStateChange = setDraggingClip,
                                beatMarkers = beatMarkers,
                                modifier = Modifier.fillMaxWidth()
                            )
                            TrackKind.AUDIO_SFX -> TrackRow(
                                label = "SFX",
                                accent = TrackSFX,
                                icon = Icons.Filled.GraphicEq,
                                clips = sfxClips,
                                selectedClipId = selectedClipId,
                                pixelsPerMs = pixelsPerMs,
                                pixelsPerMsState = pixelsPerMs,
                                onPixelsPerMsChange = onZoomChange,
                                onClipSelected = onClipSelected,
                                onClipMoved = onClipMoved,
                                onClipTrimmed = onClipTrimmed,
                                onClipTrimEnd = onClipTrimEnd,
                                audioWaveforms = audioWaveforms,
                                hScroll = trackScroll,
                                railWidth = railWidth,
                                laneWidthDp = laneWidthDp,
                                trackWidthDp = timelineWidthDp,
                                showWaveform = true,
                                showThumbnails = false,
                                isFocused = focusedTrackKind == kind,
                                onLaneScrub = onSeek,
                                onLaneZoom = onZoomChange,
                                onClipDragStateChange = setDraggingClip,
                                beatMarkers = beatMarkers,
                                modifier = Modifier.fillMaxWidth()
                            )
                            TrackKind.TEXT -> {
                                val textLaneOverlays = textOverlays.filter {
                                    it.subtitleSource == null && it.words.isEmpty()
                                }
                                TextTrackLayer(
                                    layerIndex = 0,
                                    overlays = textLaneOverlays,
                                    pixelsPerMs = pixelsPerMs,
                                    hScroll = trackScroll,
                                    railWidth = railWidth,
                                    laneWidthDp = laneWidthDp,
                                    trackWidthDp = timelineWidthDp,
                                    isFocused = focusedTrackKind == kind,
                                    onSeek = onSeek,
                                    // PHASE B5.5 — re-wire Text-lane
                                    // tap from the video-clip selector
                                    // to the dedicated text-overlay
                                    // selector. The pre-B5.5 wiring
                                    // silently routed Text-track taps
                                    // to `onClipSelected`, which left
                                    // the TextOverlay uneditable.
                                    onTextOverlaySelected = onTextOverlaySelected,
                                    selectedTextId = selectedTextId,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            TrackKind.SUBTITLE -> SubtitleTrackRow(
                                // 2026-09-07 LANE FIX — the Subs lane shows
                                // ONLY real subtitles (AI captions with
                                // per-word timestamps OR SRT imports tagged
                                // subtitleSource = "srt"). Decorative
                                // user-authored Text overlays
                                // (subtitleSource == null, words empty)
                                // belong exclusively to the Text lane.
                                // Previously `filterNot { words.isNotEmpty() }`
                                // sent SRT imports and decorative text here
                                // too, duplicating SRT across both lanes and
                                // leaving AI captions stranded on the Text
                                // lane only.
                                textOverlays = textOverlays.filter {
                                    it.words.isNotEmpty() || it.subtitleSource != null
                                },
                                activeSubtitleId = activeSubtitleId,
                                // PHASE B3 — highlight the
                                // currently-selected AI subtitle
                                // on the timeline.
                                selectedTextId = selectedTextId,
                                pixelsPerMs = pixelsPerMs,
                                hScroll = trackScroll,
                                railWidth = railWidth,
                                laneWidthDp = laneWidthDp,
                                trackWidthDp = timelineWidthDp,
                                isFocused = focusedTrackKind == kind,
                                onSeek = onSeek,
                                // PHASE B3 — subtitle taps now
                                // route to the dedicated text
                                // selector, NOT onClipSelected.
                                // The previous wiring was a silent
                                // bug.
                                onClickOverlay = onTextOverlaySelected,
                                modifier = Modifier.fillMaxWidth()
                            )
                            TrackKind.OVERLAY -> {
                                val layerIndex = activeTrackKinds.subList(0, rowIndex)
                                    .count { it == TrackKind.OVERLAY }
                                val perLayer = distributeAcrossLayers(
                                    overlays.size, TrackKind.OVERLAY.maxLayers
                                )
                                val start = perLayer.take(layerIndex).sum()
                                val end = start + perLayer.getOrElse(layerIndex) { 0 }
                                OverlayTrackLayer(
                                    layerIndex = layerIndex,
                                    overlays = overlays.drop(start).take(end - start),
                                    pixelsPerMs = pixelsPerMs,
                                    hScroll = trackScroll,
                                    railWidth = railWidth,
                                    laneWidthDp = laneWidthDp,
                                    trackWidthDp = timelineWidthDp,
                                    isFocused = focusedTrackKind == kind,
                                    onClickOverlay = onClipSelected,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            TrackKind.EFFECTS -> EffectsTrackRow(
                                clips = clips,
                                pixelsPerMs = pixelsPerMs,
                                hScroll = trackScroll,
                                railWidth = railWidth,
                                laneWidthDp = laneWidthDp,
                                trackWidthDp = timelineWidthDp,
                                isFocused = focusedTrackKind == kind,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // --- Playhead column -------------------------------------
                // The playhead is drawn on top of every track so it
                // always wins. Its horizontal position is derived from
                // [currentPositionMs] (the single source of truth) and
                // from [trackScroll] (the lane's horizontal scroll
                // state), so the playhead moves with time and stays
                // in lock-step with the lane.
                //
                // Bidirectional sync:
                //   - playback -> timeline: when [isPlaying] the lane
                //     auto-scrolls only when the playhead would leave
                //     a 20%–80% deadband inside the visible viewport.
                //   - timeline -> playback: dragging the playhead (or
                //     tapping anywhere on the lane) calls
                //     [onScrubStart] once, [onScrubSeek] on every drag
                //     frame (millisecond-accurate), and [onScrubEnd]
                //     on release. The ViewModel mirrors the position
                //     into `pendingScrubSeekMs` which the screen
                //     forwards to ExoPlayer via
                //     `seekTo(ms, SEEK_PARAM_CLOSEST_SYNC)`.
                CenteredPlayheadOverlay(
                    currentPositionMs = currentPositionMs,
                    pixelsPerMs = pixelsPerMs,
                    isPlaying = isPlaying,
                    isScrubbing = isScrubbing,
                    railWidthDp = railWidth,
                    trackScroll = trackScroll,
                    // 2026-09-16 VERTICAL-SCROLL FIX — the overlay maps
                    // taps to rows in CONTENT space (viewport y + this
                    // offset); without it taps on scrolled rows select
                    // the wrong lane.
                    laneVerticalScroll = laneVerticalScroll,
                    laneWidthPx = laneWidthPx,
                    timelineWidthPx = timelineWidthPx,
                    // 2026-09-06: touches BELOW the last track row pan
                    // the timeline left/right (the dead zone fix).
                    rowsHeightPx = with(density) {
                        (activeTrackKinds.size * EditorLayoutSpec.TimelineTrackRowHeight.toPx())
                    },
                    // 2026-09-06 RELIABLE TAP-TO-SELECT: the overlay
                    // resolves taps into (row, time) → object.
                    rowKinds = activeTrackKinds,
                    lanesData = TimelineLaneData(
                        videoClips = videoClips,
                        musicClips = musicClips,
                        sfxClips = sfxClips,
                        audioClips = videoAudioClips + audioClips.filter { it.originVideoClipId == null },
                        textOverlays = textOverlays,
                        selectedAudioClipId = selectedAudioClipId,
                        selectedClipId = selectedClipId,
                        selectedTextId = selectedTextId
                    ),
                    onTapObject = { ref ->
                        when (ref.rowKind) {
                            com.aicreatorstudio.app.model.TrackKind.VIDEO,
                            com.aicreatorstudio.app.model.TrackKind.AUDIO_MUSIC,
                            com.aicreatorstudio.app.model.TrackKind.AUDIO_SFX ->
                                onClipSelected(ref.id)
                            com.aicreatorstudio.app.model.TrackKind.VIDEO_AUDIO ->
                                onAudioClipSelected(ref.id)
                            com.aicreatorstudio.app.model.TrackKind.SUBTITLE,
                            com.aicreatorstudio.app.model.TrackKind.TEXT ->
                                ref.id?.let { onTextOverlaySelected(it) }
                            else -> Unit
                        }
                    },
                    onScrubStart = onScrubStart,
                    onScrubSeek = onScrubSeek,
                    onScrubEnd = onScrubEnd,
                    isDraggingClip = isDraggingClip,
                    // The playhead line fills the bounded viewport
                    // (a sibling of the scrollable column inside the
                    // viewport Box), so it always spans exactly the
                    // visible rows — even when the lane stack has been
                    // scrolled vertically.
                    modifier = Modifier.fillMaxSize()
                )
                } // viewport Box
            } // outer Column (ruler + viewport)
        } // BoxWithConstraints
    } // screen Column
    } // GlassSurface content
} // EditorTimeline

/** Distribute [total] items into [layers] buckets, left-padded. */
private fun distributeAcrossLayers(total: Int, layers: Int): List<Int> {
    if (layers <= 0) return emptyList()
    val base = total / layers
    val remainder = total % layers
    return List(layers) { i -> base + if (i < remainder) 1 else 0 }
}

/**
 * 2026-09-06 — resolve a TAP on a timeline row into the object under
 * the finger. [timeMs] is the content-space time; the object whose
 * span contains it wins (top-most on layered lanes).
 */
private fun resolveTimelineTap(
    kind: com.aicreatorstudio.app.model.TrackKind,
    data: TimelineLaneData,
    timeMs: Long
): TimelineObjectRef {
    fun inSpan(start: Long, dur: Long): Boolean =
        timeMs >= start && timeMs < start + dur.coerceAtLeast(1L)

    return when (kind) {
        com.aicreatorstudio.app.model.TrackKind.VIDEO -> {
            val hit = data.videoClips.lastOrNull { clip ->
                val start = clip.timelineStartMs.takeIf { it >= 0 } ?: 0L
                inSpan(start, clip.effectiveDurationMs)
            }
            TimelineObjectRef(kind, hit?.id)
        }
        com.aicreatorstudio.app.model.TrackKind.AUDIO_MUSIC -> {
            val hit = data.musicClips.lastOrNull { clip ->
                val start = clip.timelineStartMs.takeIf { it >= 0 } ?: 0L
                inSpan(start, clip.effectiveDurationMs)
            }
            TimelineObjectRef(kind, hit?.id)
        }
        com.aicreatorstudio.app.model.TrackKind.AUDIO_SFX -> {
            val hit = data.sfxClips.lastOrNull { clip ->
                val start = clip.timelineStartMs.takeIf { it >= 0 } ?: 0L
                inSpan(start, clip.effectiveDurationMs)
            }
            TimelineObjectRef(kind, hit?.id)
        }
        com.aicreatorstudio.app.model.TrackKind.VIDEO_AUDIO -> {
            val hit = data.audioClips.lastOrNull { audio ->
                inSpan(audio.startTimeMs.coerceAtLeast(0L), audio.durationMs)
            }
            TimelineObjectRef(kind, hit?.id)
        }
        com.aicreatorstudio.app.model.TrackKind.SUBTITLE -> {
            // 2026-09-07 LANE FIX — resolve taps against the SAME
            // filtered set the Subs lane renders (words-bearing AI
            // captions + SRT imports). The old unfiltered lookup let
            // a tap on the Subs lane select a decorative Text overlay
            // that is not visibly rendered there.
            val hit = data.textOverlays.lastOrNull { o ->
                (o.words.isNotEmpty() || o.subtitleSource != null) &&
                    timeMs >= o.startTimeMs && timeMs < o.endTimeMs
            }
            TimelineObjectRef(kind, hit?.id)
        }
        com.aicreatorstudio.app.model.TrackKind.TEXT -> {
            // 2026-09-07 LANE FIX — mirror the Text lane's rendered
            // set: decorative overlays only (no subtitleSource, no
            // word timings).
            val hit = data.textOverlays.lastOrNull { o ->
                o.subtitleSource == null && o.words.isEmpty() &&
                    timeMs >= o.startTimeMs && timeMs < o.endTimeMs
            }
            TimelineObjectRef(kind, hit?.id)
        }
        else -> TimelineObjectRef(kind, null)
    }
}

// =====================================================================
// Header
// =====================================================================

@Composable
private fun TimelineHeader(
    pixelsPerMs: Float,
    visibleTrackKinds: Set<TrackKind>,
    focusedTrackKind: TrackKind?,
    onZoomChange: (Float) -> Unit,
    onToggleTrack: (TrackKind) -> Unit,
    // 2026-10-05 — the "+" that finally makes multi-clip editing reachable:
    // opens the system picker and appends the video at the end of the
    // timeline. Default no-op so preview/test callers keep compiling.
    onAddClip: () -> Unit = {},
    isAddingClip: Boolean = false
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Timeline",
                style = MaterialTheme.typography.labelLarge,
                color = LabelPrimary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            // Add-clip button — sits LEFT of the zoom cluster and reads as
            // "add media", distinct from the zoom "+" which only changes
            // pixels-per-ms. Disabled (not hidden) while an import is in
            // flight so the affordance stays where the user learned it.
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(iOSBlue.copy(alpha = if (isAddingClip) 0.32f else 0.18f))
                    .border(
                        1.dp,
                        iOSBlue.copy(alpha = 0.45f),
                        RoundedCornerShape(9.dp)
                    )
                    .clickable(enabled = !isAddingClip, onClick = onAddClip),
                contentAlignment = Alignment.Center
            ) {
                if (isAddingClip) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = iOSBlueBright
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.LibraryAdd,
                        contentDescription = "افزودن کلیپ",
                        tint = iOSBlueBright,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(Modifier.size(10.dp))
            ZoomButton(
                icon = Icons.Filled.Remove,
                onClick = {
                    onZoomChange(
                        (pixelsPerMs * 0.8f).coerceIn(
                            EditorLayoutSpec.MIN_PIXELS_PER_MS,
                            EditorLayoutSpec.MAX_PIXELS_PER_MS
                        )
                    )
                }
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = "Zoom",
                style = MaterialTheme.typography.labelSmall,
                color = LabelSecondary
            )
            Spacer(Modifier.size(6.dp))
            ZoomButton(
                icon = Icons.Filled.Add,
                onClick = {
                    onZoomChange(
                        (pixelsPerMs * 1.25f).coerceIn(
                            EditorLayoutSpec.MIN_PIXELS_PER_MS,
                            EditorLayoutSpec.MAX_PIXELS_PER_MS
                        )
                    )
                }
            )
        }
        // Track toggles — CapCut-style chips so the user can see
        // exactly which lanes are open.
        Spacer(Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            listOf(
                TrackKind.TEXT to TrackText,
                TrackKind.SUBTITLE to TrackSubtitle,
                TrackKind.AUDIO_MUSIC to TrackAudio,
                TrackKind.AUDIO_SFX to TrackSFX,
                TrackKind.OVERLAY to TrackOverlay
            ).forEach { (kind, color) ->
                TrackToggleChip(
                    label = kind.displayName,
                    accent = color,
                    isOn = visibleTrackKinds.contains(kind),
                    isFocused = focusedTrackKind == kind,
                    onClick = { onToggleTrack(kind) }
                )
            }
        }
    }
}

@Composable
private fun TrackToggleChip(
    label: String,
    accent: Color,
    isOn: Boolean,
    isFocused: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isOn) accent.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.04f)
            )
            .border(
                width = if (isFocused) 1.2.dp else 1.dp,
                color = if (isOn) accent.copy(alpha = 0.8f) else HairlineSubtle,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            color = if (isOn) LabelPrimary else LabelSecondary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun ZoomButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, HairlineSubtle, RoundedCornerShape(percent = 50))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = LabelPrimary,
            modifier = Modifier.size(14.dp)
        )
    }
}

// =====================================================================
// Ruler
// =====================================================================

/** Time ruler. Vertical ticks every "interval" ms; longer ticks every 1s. */
@Composable
private fun TimeRuler(
    totalDurationMs: Long,
    pixelsPerMs: Float,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val intervals = listOf(
        33L, 67L, 100L, 200L, 500L,
        1_000L, 2_000L, 5_000L, 10_000L, 15_000L,
        30_000L, 60_000L, 120_000L, 300_000L, 600_000L
    )
    val minTickSpacingPx = with(density) { 60.dp.toPx() }
    val intervalMs = intervals.firstOrNull {
        it * pixelsPerMs >= minTickSpacingPx
    } ?: 600_000L
    val labelTextSizePx = with(density) { 11.sp.toPx() }
    val rulerPaint = remember(labelTextSizePx) {
        android.graphics.Paint().apply {
            color = android.graphics.Color.argb(140, 148, 163, 184)
            isAntiAlias = true
            textSize = labelTextSizePx
            typeface = android.graphics.Typeface.MONOSPACE
        }
    }

    BoxWithConstraints(
        modifier = modifier
    ) {
        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            var t = 0L
            while (t <= totalDurationMs) {
                val x = t * pixelsPerMs
                if (x > size.width) break
                val isMajor = t % (intervalMs * 5) == 0L
                val isMedium = t % intervalMs == 0L && !isMajor
                val tickHeight = when {
                    isMajor -> size.height * 0.62f
                    isMedium -> size.height * 0.38f
                    else -> size.height * 0.22f
                }
                val tickColor = when {
                    isMajor -> LabelTertiary
                    isMedium -> BorderStrong
                    else -> BorderSubtle
                }
                drawLine(
                    color = tickColor,
                    start = Offset(x, size.height - tickHeight),
                    end = Offset(x, size.height),
                    strokeWidth = if (isMajor) 1.2f else 0.8f
                )
                if (isMajor) {
                    val label = formatRulerTime(t, intervalMs)
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawText(
                            label,
                            x + 4f,
                            size.height - tickHeight - 3f,
                            rulerPaint
                        )
                    }
                }
                t += intervalMs / 2
            }
        }
    }
}

private fun formatRulerTime(ms: Long, intervalMs: Long): String = when {
    intervalMs < 1_000L -> "%dms".format(ms)
    intervalMs < 60_000L -> "%.1fs".format(ms / 1000f)
    else -> "%d:%02d".format(ms / 60_000L, (ms % 60_000L) / 1_000L)
}

// =====================================================================
// Track rows
// =====================================================================

/**
 * A single timeline track. Renders a rail label on the left and a
 * horizontally-scrollable clip lane on the right.
 *
 * When [isFocused], the rail border is highlighted in the track colour
 * so the user can see which lane the active sub-panel is editing.
 *
 * Lane interactions:
 *   - Drag on empty area: horizontal scroll of the lane
 *     ([hScroll]). The playhead itself lives in a separate narrow
 *     hit column drawn on top by [CenteredPlayheadOverlay]; only
 *     drags that start inside that column scrub the playhead.
 *   - Pinch: zoom ([onLaneZoom]).
 *   - Drag a clip body: move. Drag a clip edge: trim. Snaps to
 *     [EditorLayoutSpec.TIMELINE_SNAP_MS] on release.
 *   - Tap a clip: select.
 *
 * [onLaneScrub] is preserved as a parameter for backwards
 * compatibility with the [EditorTimeline] call-site signature but is
 * intentionally not invoked: timeline dragging is a viewport
 * operation, not a seek.
 */
@Composable
private fun TrackRow(
    label: String,
    accent: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    clips: List<VideoClip>,
    selectedClipId: String?,
    pixelsPerMs: Float,
    pixelsPerMsState: Float,
    onPixelsPerMsChange: (Float) -> Unit,
    onClipSelected: (String?) -> Unit,
    onClipMoved: (String, Long) -> Unit,
    onClipTrimmed: (String, Long, Long) -> Unit,
    // 2026-10-04 — fired once when a trim drag ends on one of this
    // lane's clips (not per frame). Default no-op for older callers.
    onClipTrimEnd: (String) -> Unit = {},
    // 2026-10-05 — real waveform peaks for this lane's clips.
    audioWaveforms: Map<String, List<com.aicreatorstudio.app.model.AudioWaveformPeak>> = emptyMap(),
    // 2026-10-05 — filmstrip frames for video lane tiles.
    clipThumbnails: Map<String, List<androidx.compose.ui.graphics.ImageBitmap>> = emptyMap(),
    hScroll: androidx.compose.foundation.ScrollState,
    railWidth: androidx.compose.ui.unit.Dp,
    laneWidthDp: androidx.compose.ui.unit.Dp,
    trackWidthDp: androidx.compose.ui.unit.Dp,
    showWaveform: Boolean,
    showThumbnails: Boolean,
    isFocused: Boolean,
    onLaneScrub: (Long) -> Unit,
    onLaneZoom: (Float) -> Unit,
    onClipDragStateChange: (Boolean) -> Unit = {},
    /**
     * 2026-09-29 — detected beat onsets in timeline time, ascending. Empty
     * when no audio clip has been analysed, which makes the beat snap below
     * a no-op. Passed down rather than read from the ViewModel here so the
     * timeline stays a pure function of its inputs.
     */
    beatMarkers: List<Long> = emptyList(),
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val rowHeight = EditorLayoutSpec.TimelineTrackRowHeight

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(rowHeight)
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            TrackRailLabel(
                label = label,
                accent = accent,
                icon = icon,
                isFocused = isFocused
            )
            // Lane — horizontal scroll + pinch zoom. A drag that
            // starts anywhere on the lane is consumed by
            // horizontalScroll; only a drag that starts inside the
            // narrow playhead hit column (drawn on top by
            // CenteredPlayheadOverlay) is routed to the scrub handler.
            // As a result, dragging on empty lane area scrolls the
            // timeline, and only grabbing the playhead moves it.
            //
            // 2026-09-06 FIX — horizontal scroll was DEAD. The old
            // `detectTransformGestures` zoom modifier consumes EVERY
            // pointer event (including plain 1-finger pans), so the
            // sibling `horizontalScroll` never received a drag and the
            // lane could only scroll programmatically (auto-scroll /
            // playhead follow). The zoom gesture is now an
            // `awaitEachGesture` that stays passive while fewer than
            // two pointers are down — 1-finger drags fall through to
            // horizontalScroll, real 2-finger pinches still zoom.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(hScroll)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            // Wait for the second finger WITHOUT
                            // consuming anything — a 1-finger drag
                            // simply never satisfies this and its
                            // events keep flowing to horizontalScroll.
                            awaitTwoDownOrFail()
                            // Two fingers: this is a pinch. Track
                            // span and consume changes so scroll
                            // does not fight the zoom.
                            var previousSpan = twoPointerSpan()
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.size < 2) break
                                val span = twoPointerSpan()
                                val zoom = span / previousSpan.coerceAtLeast(0.001f)
                                previousSpan = span
                                if (abs(zoom - 1f) > 0.001f) {
                                    val next = (pixelsPerMsState * zoom).coerceIn(
                                        EditorLayoutSpec.MIN_PIXELS_PER_MS,
                                        EditorLayoutSpec.MAX_PIXELS_PER_MS
                                    )
                                    if (abs(next - pixelsPerMsState) > 0.0001f) {
                                        onPixelsPerMsChange(next)
                                    }
                                }
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
            ) {
                Box(modifier = Modifier.width(trackWidthDp).fillMaxHeight()) {
                    // Lane background
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White.copy(alpha = 0.03f))
                            .border(1.dp, HairlineSubtle, RoundedCornerShape(0.dp))
                    )
                    clips.forEachIndexed { idx, clip ->
                        val startMs = clip.timelineStartMs.takeIf { it >= 0 } ?: 0L
                        // PHASE A (Persistence Integrity) — a zero-length
                        // clip used to be drawn at a fake 5 000 ms width,
                        // which both lied about the timeline (the block
                        // claimed five seconds that play as zero) and
                        // hid the invalid state from the user. Now it
                        // renders a thin sliver at the same minimum the
                        // gestures use, so it is visibly broken and can
                        // be grabbed, trimmed or deleted instead of
                        // being mistaken for a normal clip.
                        val durMs = clip.effectiveDurationMs
                            .takeIf { it > 0 } ?: ZERO_LENGTH_TILE_MS
                        // Real peaks for THIS tile. Music/SFX clips are
                        // keyed by their VideoClip id; video-lane ids miss
                        // the map and get the placeholder.
                        val clipPeaks = audioWaveforms[clip.id].orEmpty()
                        // Filmstrip frames for video tiles ("id@uri" key —
                        // audio-only ids miss and render the flat tile).
                        val clipFrames = clipThumbnails["${clip.id}@${clip.uri}"].orEmpty()
                        val startDp = with(density) { (startMs * pixelsPerMs).toFloat().toDp() }
                        val widthDp = with(density) { (durMs * pixelsPerMs).toFloat().toDp() }
                        val isSelected = clip.id == selectedClipId
                        ClipBlock(
                            startDp = startDp,
                            widthDp = widthDp,
                            pixelsPerMs = pixelsPerMs,
                            color = accent,
                            isSelected = isSelected,
                            label = clip.fileName,
                            waveform = showWaveform,
                            thumbnails = showThumbnails,
                            onClick = { onClipSelected(clip.id) },
                            // 2026-09-29 — beat ticks are drawn on the VIDEO
                            // lane as well as the audio lanes: the markers are
                            // only useful where the cuts land, and reading them
                            // off the video block is what makes auto-split's
                            // result legible before running it.
                            beatMarkers = beatMarkers,
                            clipStartMs = startMs,
                            clipDurationMs = durMs,
                            highlightRanges = clip.highlightRanges,
                            waveformPeaks = clipPeaks,
                            thumbnailFrames = clipFrames,
                            // 2026-09-29 — beat snap. Both the move and the
                            // left-edge trim snap the *edge being dragged* to
                            // a detected onset before the edit is committed,
                            // which is what makes "cut on the kick" land
                            // exactly rather than approximately. `beatMarkers`
                            // is empty unless the user ran Detect, so this is
                            // a no-op for every project that never used the
                            // feature.
                            onMove = { deltaMs ->
                                val rawStart = (startMs + deltaMs).coerceAtLeast(0L)
                                val newStart = snapToBeatMarkers(rawStart, beatMarkers)
                                onClipMoved(clip.id, newStart)
                            },
                            onTrim = { leftDelta, rightDelta ->
                                val rawStart = (startMs + leftDelta).coerceAtLeast(0L)
                                val newEnd = startMs + durMs + rightDelta
                                onClipTrimmed(
                                    clip.id,
                                    snapToBeatMarkers(rawStart, beatMarkers),
                                    if (rightDelta != 0L) {
                                        snapToBeatMarkers(newEnd, beatMarkers)
                                    } else {
                                        newEnd
                                    }
                                )
                            },
                            onDragStateChange = onClipDragStateChange,
                            // 2026-10-04 — end-of-gesture signal: the
                            // screen resets its undo latch and bakes the
                            // new trim window into the preview playlist.
                            onGestureEnd = { wasTrim ->
                                if (wasTrim) onClipTrimEnd(clip.id)
                            }
                        )
                        // ── Transition handle between consecutive clips ──
                        if (idx < clips.size - 1) {
                            val nextClip = clips[idx + 1]
                            val nextStartMs = nextClip.timelineStartMs.takeIf { it >= 0 } ?: 0L
                            val gapMs = nextStartMs - (startMs + durMs)
                            // Only show handle when clips are adjacent (gap ≈ 0)
                            if (gapMs in -80L..80L) {
                                val handleX = with(density) {
                                    ((startMs + durMs) * pixelsPerMs - 9.dp.toPx()).toDp()
                                }
                                Box(
                                    modifier = Modifier
                                        .offset { androidx.compose.ui.unit.IntOffset(handleX.toPx().toInt(), 0) }
                                        .padding(vertical = 8.dp)
                                        .width(18.dp)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(iOSBlueBright.copy(alpha = 0.25f))
                                        .border(1.dp, iOSBlueBright.copy(alpha = 0.5f), RoundedCornerShape(5.dp))
                                        .clickable {
                                            onClipSelected(clip.id)
                                            // The transition panel is already wired to
                                            // selectedClip via ToolPanel.TRANSITIONS
                                        }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(2.dp)
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(iOSBlueBright, iOSBlue)
                                                ),
                                                RoundedCornerShape(3.dp)
                                            )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Label / icon column on the very left of a track row. */
@Composable
private fun TrackRailLabel(
    label: String,
    accent: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isFocused: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .width(EditorLayoutSpec.TimelineLabelColumnWidth)
            .fillMaxHeight()
            .background(
                if (isFocused) accent.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.03f)
            )
            .border(
                width = if (isFocused) 1.5.dp else 1.dp,
                color = if (isFocused) accent.copy(alpha = 0.6f) else HairlineSubtle,
                shape = RoundedCornerShape(0.dp)
            )
            .padding(horizontal = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(accent.copy(alpha = 0.20f))
                .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(12.dp)
            )
        }
        Spacer(Modifier.size(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isFocused) accent else LabelPrimary,
            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

/**
 * A clip block. Supports tap to select, drag the body to move, and drag
 * either edge to trim. Snaps to [EditorLayoutSpec.TIMELINE_SNAP_MS] on
 * release.
 */
@Composable
private fun ClipBlock(
    startDp: androidx.compose.ui.unit.Dp,
    widthDp: androidx.compose.ui.unit.Dp,
    pixelsPerMs: Float,
    color: Color,
    isSelected: Boolean,
    label: String,
    waveform: Boolean,
    thumbnails: Boolean,
    onClick: () -> Unit,
    onMove: (Long) -> Unit,
    onTrim: (leftDeltaMs: Long, rightDeltaMs: Long) -> Unit,
    onDragStateChange: (Boolean) -> Unit = {},
    // 2026-10-04 — fired once when the gesture ends. `wasTrim` is true
    // when the last action was an edge trim (not a body move). Used by
    // the editor screen to coalesce trim undo history and rebuild the
    // preview playlist.
    onGestureEnd: (wasTrim: Boolean) -> Unit = {},
    // 2026-10-05 — real PCM peaks for waveform tiles; empty draws the
    // loading placeholder.
    waveformPeaks: List<com.aicreatorstudio.app.model.AudioWaveformPeak> = emptyList(),
    // 2026-10-05 — real filmstrip frames; empty keeps the flat tile.
    thumbnailFrames: List<androidx.compose.ui.graphics.ImageBitmap> = emptyList(),
    /**
     * 2026-09-29 — beat onsets (timeline time) to paint across this block,
     * plus this block's own span so the strip can filter and shift them into
     * block-local offsets. Empty when nothing has been analysed.
     */
    beatMarkers: List<Long> = emptyList(),
    clipStartMs: Long = 0L,
    clipDurationMs: Long = 0L,
    /**
     * 2026-10-19 — Smart Highlights ranges for THIS clip, clip-local ms.
     *
     * `applyHighlights` has always written `VideoClip.highlightRanges`, but
     * nothing read them: the panel reported "N ranges applied" and the
     * timeline showed no trace of them, so the feature looked like a no-op.
     * They are painted as a band on the clip tile now, which is also the
     * only place the user can see *which* parts of the clip were chosen.
     */
    highlightRanges: List<com.aicreatorstudio.app.model.HighlightRange> = emptyList()
) {
    val density = LocalDensity.current

    val touchSlopPx = LocalViewConfiguration.current.touchSlop

    // 2026-10-20 — DRAG FIX. The gesture below lives in a `pointerInput` whose
    // key is `label`, which does not change while a clip is dragged, so every
    // value that block captures is frozen at the composition that created it.
    // `onMove` is [TrackRow]'s lambda, and it computes `startMs + deltaMs` from
    // an *incremental* delta — so a frozen `startMs` made each frame resolve to
    // `originalStart + oneFrameOfMovement`, i.e. the tile jittered around where
    // it started instead of following the finger. Reading the callbacks through
    // `rememberUpdatedState` keeps the gesture on the live lambda (and therefore
    // the live start), which is the documented fix for this bug class.
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnTrim by rememberUpdatedState(onTrim)
    val currentOnDragStateChange by rememberUpdatedState(onDragStateChange)
    val currentOnGestureEnd by rememberUpdatedState(onGestureEnd)

    // Audio tiles get a dark gradient so the waveform pops on top.
    // Video tiles get a flat rich fill with subtle top highlight.
    val tileBackground: Brush = if (waveform) {
        Brush.verticalGradient(
            colors = listOf(
                color.copy(alpha = if (isSelected) 0.65f else 0.45f),
                SurfaceInset
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                color.copy(alpha = if (isSelected) 0.88f else 0.58f),
                color.copy(alpha = if (isSelected) 0.78f else 0.48f)
            )
        )
    }

    Box(
        modifier = Modifier
            .offset { androidx.compose.ui.unit.IntOffset(startDp.toPx().toInt(), 0) }
            .width(widthDp.coerceAtLeast(EditorLayoutSpec.TimelineClipMinWidthDp))
            .fillMaxHeight()
            .padding(horizontal = 1.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(EditorLayoutSpec.TimelineClipCornerRadius))
            .background(tileBackground)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) AccentBright else color.copy(alpha = 0.55f),
                shape = RoundedCornerShape(EditorLayoutSpec.TimelineClipCornerRadius)
            )
            .clickable { onClick() }
            .pointerInput(label) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downX = down.position.x
                    val edgeWidthPx = with(density) { 10.dp.toPx() }
                    var edge = when {
                        downX < edgeWidthPx -> TrimEdge.LEFT
                        downX > (widthDp.toPx() - edgeWidthPx) -> TrimEdge.RIGHT
                        else -> TrimEdge.BODY
                    }
                    // Last action performed in this gesture, reported via
                    // onGestureEnd when the pointer lifts.
                    var lastActionWasTrim = false
                    var dragStartPx = downX
                    var accDx = 0f
                    var accDy = 0f
                    var yielded = false
                    currentOnDragStateChange(true)
                    while (true) {
                        val event = awaitPointerEvent()
                        val changes = event.changes
                        val allUp = changes.all { !it.pressed }
                        if (allUp) break
                        val change = changes.firstOrNull { it.pressed } ?: continue
                        val dx = change.position.x - change.previousPosition.x
                        val dy = change.position.y - change.previousPosition.y
                        accDx += dx
                        accDy += dy
                        if (!yielded &&
                            abs(accDy) > touchSlopPx &&
                            abs(accDy) > abs(accDx)
                        ) {
                            // Vertical-dominant swipe: yield to
                            // lane stack's verticalScroll. No
                            // consume, no move/trim.
                            yielded = true
                            currentOnDragStateChange(false)
                            // Keep consuming no events; the lane
                            // Column's verticalScroll picks up
                            // unhandled vertical pointer events.
                            continue
                        }
                        if (!yielded) {
                            val deltaPx = change.position.x - dragStartPx
                            dragStartPx = change.position.x
                            val deltaMs =
                                (deltaPx / pixelsPerMs.coerceAtLeast(0.0001f))
                                    .roundToInt().toLong()
                            when (edge) {
                                TrimEdge.LEFT -> {
                                    currentOnTrim(deltaMs, 0L)
                                    lastActionWasTrim = true
                                }
                                TrimEdge.RIGHT -> {
                                    currentOnTrim(0L, deltaMs)
                                    lastActionWasTrim = true
                                }
                                else -> currentOnMove(deltaMs)
                            }
                            change.consume()
                        }
                    }
                    currentOnDragStateChange(false)
                    currentOnGestureEnd(lastActionWasTrim)
                }
            }
    ) {
        if (waveform) {
            // Soft top sheen — light falling on the glass tile.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.45f)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.10f),
                                Color.Transparent
                            )
                        )
                    )
            )
            Waveform(
                seed = label,
                color = color,
                isSelected = isSelected,
                peaks = waveformPeaks,
                modifier = Modifier.fillMaxSize()
            )
        } else if (thumbnailFrames.isNotEmpty()) {
            // 2026-10-05 — REAL filmstrip: the clip's own decoded frames
            // tiled edge to edge (each frame crops to its slice), with a
            // darkening scrim so the label stays readable. Replaces the
            // flat colour tile the video lane used to show.
            Row(modifier = Modifier.fillMaxSize()) {
                thumbnailFrames.forEach { frame ->
                    androidx.compose.foundation.Image(
                        bitmap = frame,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.15f),
                                Color.Black.copy(alpha = 0.45f)
                            )
                        )
                    )
            )
        }
        val showLabel = !waveform || widthDp > 32.dp
        if (showLabel) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label.take(14),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
        }

        // 2026-09-29 — beat ticks, drawn above the waveform / thumbnail and
        // below the trim handles so the handles stay grabbable. Uses the
        // absolute marker list (not a per-clip model) because a video clip has
        // no `beatData` of its own — the music it is cut to lives on a
        // different lane entirely.
        VideoBeatMarkerStrip(
            beatMarkers = beatMarkers,
            clipStartMs = clipStartMs,
            clipDurationMs = clipDurationMs,
            pixelsPerMs = pixelsPerMs
        )

        // 2026-10-19 — Smart Highlights band. Drawn under the beat ticks so a
        // clip that has both still reads clearly: the band says "this stretch
        // was scored as a highlight", the ticks say "the music hits here".
        HighlightRangeStrip(
            ranges = highlightRanges,
            clipDurationMs = clipDurationMs,
            pixelsPerMs = pixelsPerMs
        )

        // Left trim handle
        TrimHandle(isLeft = true, isSelected = isSelected, modifier = Modifier.align(Alignment.CenterStart))
        // Right trim handle
        TrimHandle(isLeft = false, isSelected = isSelected, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

private enum class TrimEdge { LEFT, RIGHT, BODY }

@Composable
private fun TrimHandle(isLeft: Boolean, isSelected: Boolean, modifier: Modifier = Modifier) {
    val visible = isSelected
    Box(
        modifier = modifier
            .width(5.dp)
            .fillMaxHeight()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(
                if (visible) Color.White.copy(alpha = 0.85f)
                else Color.White.copy(alpha = 0.35f)
            )
            .then(
                if (isLeft) Modifier.padding(start = 1.dp)
                else Modifier.padding(end = 1.dp)
            )
    )
}

/**
 * Modern audio waveform for timeline clips (Music / SFX / Video Audio).
 *
 * Chic CapCut-style look: pill-shaped bars with rounded caps, a vertical
 * gradient that glows bright at the center line and fades to transparent
 * at the tips, a faint center baseline. Pure Canvas, one gradient brush
 * for all bars.
 *
 * 2026-10-05 — REAL data first: when [peaks] is non-empty the bars are
 * the clip's actual PCM envelope (computed by the ViewModel through
 * `WaveformGenerator`), resampled to the visible bar count. The old
 * sine+noise envelope remains ONLY as the placeholder drawn while peaks
 * are still being computed — it is a loading state, never presented as
 * the clip's real audio shape.
 *
 * @param dimmed chic muted treatment: desaturated, low alpha.
 */
@Composable
private fun Waveform(
    seed: String,
    color: Color,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    isSelected: Boolean = false,
    peaks: List<com.aicreatorstudio.app.model.AudioWaveformPeak> = emptyList()
) {
    val hash = remember(seed) { seed.hashCode() }
    Canvas(modifier = modifier.padding(horizontal = 5.dp, vertical = 3.dp)) {
        val midY = size.height / 2f
        val maxHalf = size.height / 2f

        // Faint center baseline — the "audio spine".
        drawLine(
            color = Color.White.copy(alpha = if (dimmed) 0.08f else 0.16f),
            start = Offset(4f, midY),
            end = Offset(size.width - 4f, midY),
            strokeWidth = 1f
        )

        // One shared vertical gradient brush: transparent tips → bright core.
        val coreAlpha = if (dimmed) 0.30f else if (isSelected) 1f else 0.92f
        val edgeAlpha = if (dimmed) 0.30f else if (isSelected) 0.85f else 0.70f
        val brush = Brush.verticalGradient(
            colors = listOf(
                color.copy(alpha = 0f),
                color.copy(alpha = edgeAlpha),
                Color.White.copy(alpha = coreAlpha),
                color.copy(alpha = edgeAlpha),
                color.copy(alpha = 0f)
            ),
            startY = 0f,
            endY = size.height
        )

        val barCount = max(8, min(180, (size.width / 3.5f).toInt()))
        val barWidth = size.width / barCount
        val stroke = (barWidth * 0.62f).coerceIn(1.5f, 5f)
        // Slow phases so swells span several bars, like real phrases.
        val phase1 = (hash % 360) * 0.017f
        val phase2 = ((hash / 7) % 360) * 0.017f

        // Real envelope: map each bar onto its bucket window and take the
        // loudest |sample| inside (max-of-abs keeps transient peaks).
        val loudest: ((Int) -> Float)? = if (peaks.isEmpty()) null else { bar ->
            val start = (bar.toLong() * peaks.size / barCount).toInt()
                .coerceIn(0, peaks.size - 1)
            val end = (((bar + 1).toLong() * peaks.size / barCount).toInt())
                .coerceAtMost(peaks.size)
            var m = 0f
            var j = start
            while (j < end) {
                val p = peaks[j]
                if (p.max > m) m = p.max
                if (p.min > m) m = p.min
                j++
            }
            if (m <= 0f) 0f else m
        }
        var prevAmp = 0f
        for (i in 0 until barCount) {
            val t = i / barCount.toFloat()
            val noise = (((hash xor (i * 2654435761.toInt())) and 0x7fffffff) % 1000) / 1000f
            // Organic envelope: two beating swells → loud / quiet passages.
            val swell = abs(sin(t * 9.4f + phase1) * 0.65f + sin(t * 23f + phase2) * 0.35f)
            val fakeAmp = (0.10f + 0.90f * (0.30f * noise + 0.70f * swell)) * maxHalf
            val raw = (loudest?.invoke(i)?.times(maxHalf)) ?: fakeAmp
            // Light temporal smoothing so neighbours flow into each other.
            val amp = (0.55f * raw + 0.45f * prevAmp).coerceAtLeast(1.5f)
            prevAmp = raw
            val x = i * barWidth + barWidth / 2f
            drawLine(
                brush = brush,
                start = Offset(x, midY - amp),
                end = Offset(x, midY + amp),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

// =====================================================================
// Text / Subtitle / Overlay rows
// =====================================================================

/**
 * A single text-overlay layer lane. Up to 5 of these can appear when
 * the Text tool is active.
 */
@Composable
private fun TextTrackLayer(
    layerIndex: Int,
    overlays: List<TextOverlay>,
    pixelsPerMs: Float,
    hScroll: androidx.compose.foundation.ScrollState,
    railWidth: androidx.compose.ui.unit.Dp,
    laneWidthDp: androidx.compose.ui.unit.Dp,
    trackWidthDp: androidx.compose.ui.unit.Dp,
    isFocused: Boolean,
    onSeek: (Long) -> Unit,
    // PHASE B5.5 — per-overlay selection visualization. The
    // matching block gets a 2 dp yellow border so the user can
    // see which TextOverlay is currently selected for editing.
    selectedTextId: String? = null,
    // PHASE B5.5 — re-wire the click from `onClipSelected` to
    // a dedicated text-overlay callback. The pre-B5.5
    // `onClickOverlay: (String?) -> Unit` was wired to the
    // video-clip selector and was a latent pre-B3 bug: tapping
    // a Text-overlay block silently deselected any selected
    // clip and routed the user into the clip selector with a
    // null id, leaving the TextOverlay uneditable. B5.5 fixes
    // this with a dedicated callback.
    onTextOverlaySelected: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val rowHeight = EditorLayoutSpec.TimelineTrackRowHeight
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(rowHeight)
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            TrackRailLabel(
                label = if (layerIndex == 0) "Text" else "Text·${layerIndex + 1}",
                accent = TrackText,
                icon = Icons.Filled.TextFields,
                isFocused = isFocused
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(hScroll)
            ) {
                Box(modifier = Modifier.width(trackWidthDp).fillMaxHeight()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White.copy(alpha = 0.03f))
                            .border(1.dp, HairlineSubtle, RoundedCornerShape(0.dp))
                    )
                    overlays.forEach { overlay ->
                        val startDp = with(density) {
                            (overlay.startTimeMs * pixelsPerMs).toFloat().toDp()
                        }
                        val durMs = (overlay.endTimeMs - overlay.startTimeMs)
                            .takeIf { it > 0 } ?: 2_000L
                        val widthDp = with(density) {
                            (durMs * pixelsPerMs).toFloat().toDp()
                        }
                        // PHASE B5.5 — same selection rule as the
                        // Subtitle lane: yellow 2 dp border on the
                        // selected overlay, white 1.5 dp on the
                        // active (playhead-over) one, 1 dp default.
                        val isSelected = overlay.id == selectedTextId
                        Box(
                            modifier = Modifier
                                .offset {
                                    androidx.compose.ui.unit.IntOffset(
                                        startDp.toPx().toInt(), 0
                                    )
                                }
                                .width(widthDp.coerceAtLeast(EditorLayoutSpec.TimelineClipMinWidthDp))
                                .fillMaxHeight()
                                .padding(horizontal = 1.dp, vertical = 4.dp)
                                .clip(RoundedCornerShape(EditorLayoutSpec.TimelineClipCornerRadius))
                                .background(TrackText.copy(alpha = 0.55f))
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) Color.Yellow else TrackText,
                                    shape = RoundedCornerShape(EditorLayoutSpec.TimelineClipCornerRadius)
                                )
                                .clickable {
                                    onTextOverlaySelected(overlay.id)
                                    onSeek(overlay.startTimeMs)
                                }
                                .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = overlay.text.take(20),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitleTrackRow(
    textOverlays: List<TextOverlay>,
    activeSubtitleId: String?,
    // PHASE B3 — id of the currently-selected text overlay.
    // Used to draw a selection ring on the matching clip.
    selectedTextId: String? = null,
    pixelsPerMs: Float,
    hScroll: androidx.compose.foundation.ScrollState,
    railWidth: androidx.compose.ui.unit.Dp,
    laneWidthDp: androidx.compose.ui.unit.Dp,
    trackWidthDp: androidx.compose.ui.unit.Dp,
    isFocused: Boolean,
    onSeek: (Long) -> Unit,
    // PHASE B3 — non-nullable now. The previous signature
    // was `(String?) -> Unit` because the timeline reused
    // the same callback for clip selection. Subtitles have
    // a dedicated callback in B3.
    onClickOverlay: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val rowHeight = EditorLayoutSpec.TimelineTrackRowHeight
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(rowHeight)
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            TrackRailLabel(
                label = "Subs",
                accent = TrackSubtitle,
                icon = Icons.Filled.Subtitles,
                isFocused = isFocused
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(hScroll)
            ) {
                Box(modifier = Modifier.width(trackWidthDp).fillMaxHeight()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White.copy(alpha = 0.03f))
                            .border(1.dp, HairlineSubtle, RoundedCornerShape(0.dp))
                    )
                    textOverlays.forEach { overlay ->
                        val startDp = with(density) {
                            (overlay.startTimeMs * pixelsPerMs).toFloat().toDp()
                        }
                        val durMs = (overlay.endTimeMs - overlay.startTimeMs)
                            .takeIf { it > 0 } ?: 2_000L
                        val widthDp = with(density) {
                            (durMs * pixelsPerMs).toFloat().toDp()
                        }
                        // The block whose id matches the active
                        // subtitle is highlighted brighter so the
                        // user can see which word is currently being
                        // spoken as the playhead passes over it.
                        val isActive = overlay.id == activeSubtitleId
                        Box(
                            modifier = Modifier
                                .offset {
                                    androidx.compose.ui.unit.IntOffset(
                                        startDp.toPx().toInt(), 0
                                    )
                                }
                                .width(widthDp.coerceAtLeast(EditorLayoutSpec.TimelineClipMinWidthDp))
                                .fillMaxHeight()
                                .padding(horizontal = 1.dp, vertical = 4.dp)
                                .clip(RoundedCornerShape(EditorLayoutSpec.TimelineClipCornerRadius))
                                .background(
                                    if (isActive) TrackSubtitle.copy(alpha = 0.95f)
                                    else TrackSubtitle.copy(alpha = 0.55f)
                                )
                                .border(
                                    // PHASE B3 — selected overlays
                                    // get a thicker white border so
                                    // the user can see what the
                                    // in-card editor is editing.
                                    width = if (overlay.id == selectedTextId) 2.dp
                                            else if (isActive) 1.5.dp else 1.dp,
                                    color = if (overlay.id == selectedTextId) Color.Yellow
                                            else if (isActive) Color.White else TrackSubtitle,
                                    shape = RoundedCornerShape(EditorLayoutSpec.TimelineClipCornerRadius)
                                )
                                .clickable {
                                    onClickOverlay(overlay.id)
                                    onSeek(overlay.startTimeMs)
                                }
                                .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = overlay.text.take(20),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                                ),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A single overlay layer lane. Up to 3 of these can appear. */
@Composable
private fun OverlayTrackLayer(
    layerIndex: Int,
    overlays: List<VideoOverlay>,
    pixelsPerMs: Float,
    hScroll: androidx.compose.foundation.ScrollState,
    railWidth: androidx.compose.ui.unit.Dp,
    laneWidthDp: androidx.compose.ui.unit.Dp,
    trackWidthDp: androidx.compose.ui.unit.Dp,
    isFocused: Boolean,
    onClickOverlay: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val rowHeight = EditorLayoutSpec.TimelineTrackRowHeight
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(rowHeight)
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            TrackRailLabel(
                label = if (layerIndex == 0) "Overlay" else "Ov·${layerIndex + 1}",
                accent = TrackOverlay,
                icon = Icons.Filled.ViewInAr,
                isFocused = isFocused
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(hScroll)
            ) {
                Box(modifier = Modifier.width(trackWidthDp).fillMaxHeight()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White.copy(alpha = 0.03f))
                            .border(1.dp, HairlineSubtle, RoundedCornerShape(0.dp))
                    )
                    overlays.forEach { overlay ->
                        val startDp = with(density) {
                            (overlay.startTimeMs * pixelsPerMs).toFloat().toDp()
                        }
                        val durMs = (overlay.endTimeMs - overlay.startTimeMs)
                            .takeIf { it > 0 } ?: 3_000L
                        val widthDp = with(density) {
                            (durMs * pixelsPerMs).toFloat().toDp()
                        }
                        Box(
                            modifier = Modifier
                                .offset {
                                    androidx.compose.ui.unit.IntOffset(
                                        startDp.toPx().toInt(), 0
                                    )
                                }
                                .width(widthDp.coerceAtLeast(EditorLayoutSpec.TimelineClipMinWidthDp))
                                .fillMaxHeight()
                                .padding(horizontal = 1.dp, vertical = 4.dp)
                                .clip(RoundedCornerShape(EditorLayoutSpec.TimelineClipCornerRadius))
                                .background(TrackOverlay.copy(alpha = 0.55f))
                                .border(
                                    1.dp,
                                    TrackOverlay,
                                    RoundedCornerShape(EditorLayoutSpec.TimelineClipCornerRadius)
                                )
                                .clickable { onClickOverlay(overlay.id) }
                                .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = overlay.label.take(14).ifBlank { "Overlay" },
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EffectsTrackRow(
    clips: List<VideoClip>,
    pixelsPerMs: Float,
    hScroll: androidx.compose.foundation.ScrollState,
    railWidth: androidx.compose.ui.unit.Dp,
    laneWidthDp: androidx.compose.ui.unit.Dp,
    trackWidthDp: androidx.compose.ui.unit.Dp,
    isFocused: Boolean,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val rowHeight = EditorLayoutSpec.TimelineTrackRowHeight
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(rowHeight)
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            TrackRailLabel(
                label = "FX",
                accent = TrackSFX,
                icon = Icons.Filled.Tune,
                isFocused = isFocused
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(hScroll)
            ) {
                Box(modifier = Modifier.width(trackWidthDp).fillMaxHeight()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White.copy(alpha = 0.03f))
                            .border(1.dp, HairlineSubtle, RoundedCornerShape(0.dp))
                    )
                    clips.filter {
                        it.cropPreset.name != "FREE" || it.rotation != 0 ||
                            it.animation != ClipAnimation.NONE
                    }.forEach { clip ->
                        val startMs = clip.timelineStartMs.takeIf { it >= 0 } ?: 0L
                        val durMs = clip.effectiveDurationMs.takeIf { it > 0 } ?: 5_000L
                        val startDp = with(density) { (startMs * pixelsPerMs).toFloat().toDp() }
                        val widthDp = with(density) { (durMs * pixelsPerMs).toFloat().toDp() }
                        Box(
                            modifier = Modifier
                                .offset {
                                    androidx.compose.ui.unit.IntOffset(
                                        startDp.toPx().toInt(), 0
                                    )
                                }
                                .width(widthDp.coerceAtLeast(EditorLayoutSpec.TimelineClipMinWidthDp))
                                .fillMaxHeight()
                                .padding(horizontal = 1.dp, vertical = 4.dp)
                                .clip(RoundedCornerShape(EditorLayoutSpec.TimelineClipCornerRadius))
                                .background(TrackSFX.copy(alpha = 0.55f))
                                .border(
                                    1.dp,
                                    TrackSFX,
                                    RoundedCornerShape(EditorLayoutSpec.TimelineClipCornerRadius)
                                )
                                .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = clip.animation.displayName.take(14),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

// =====================================================================
// Centered playhead overlay
// =====================================================================

/**
 * The playhead overlay. Drawn as the very last layer so it sits above
 * every track. The playhead's horizontal position is derived purely
 * from [currentPositionMs] and the lane's [trackScroll] (the overlay's
 * own offset is `currentPositionMs * pixelsPerMs - trackScroll.value +
 * railWidthPx`), so the playhead moves with time and the lane scrolls
 * underneath it before the playhead leaves the visible viewport.
 *
 * Bidirectional sync is achieved with two effects:
 *
 *   - **Playback → timeline**: when [isPlaying] is true, we drive
 *     [trackScroll] only when the playhead would leave a 20%–80%
 *     deadband inside the visible lane. At the natural reading speed
 *     the playhead moves freely; the lane scrolls forward when the
 *     playhead approaches the right edge, and scrolls back when the
 *     user seeks behind the left edge. This is the conventional
 *     "playhead follows the content" experience used by Premiere,
 *     Final Cut, and most NLEs.
 *
 *   - **Timeline → playback**: a narrow `pointerInput` column centered
 *     on the playhead line is the *only* hit area that scrubs. Touches
 *     that start inside this column are routed to [onScrubStart] /
 *     [onScrubSeek] / [onScrubEnd]; touches that start anywhere else
 *     on the lane fall through to the lane's [horizontalScroll] and
 *     never touch the playhead. We translate the finger x to
 *     milliseconds and call [onScrubSeek] with millisecond precision
 *     on every drag frame. The ViewModel suspends the auto-position
 *     mirror (33 ms poller) via `isScrubbing` so the lane moves with
 *     the finger while a scrub is in progress.
 */
@Composable
private fun CenteredPlayheadOverlay(
    currentPositionMs: Long,
    pixelsPerMs: Float,
    isPlaying: Boolean,
    // 2026-09-06: while the user drag-scrubs (playback now pauses for
    // the duration of the drag) the auto-scroll keeps the playhead
    // column inside the visible lane so the finger never loses it on
    // long timelines.
    isScrubbing: Boolean = false,
    railWidthDp: androidx.compose.ui.unit.Dp,
    trackScroll: androidx.compose.foundation.ScrollState,
    // 2026-09-16 VERTICAL-SCROLL FIX — the lane stack scrolls
    // vertically under this static overlay; taps resolve rows in
    // CONTENT space (viewport y + this offset).
    laneVerticalScroll: androidx.compose.foundation.ScrollState,
    laneWidthPx: Float,
    timelineWidthPx: Float,
    onScrubStart: () -> Unit,
    onScrubSeek: (Long) -> Unit,
    onScrubEnd: () -> Unit,
    isDraggingClip: Boolean = false,
    // 2026-09-06: total pixel height of the visible track rows.
    // Touches BELOW this line are the "dead zone" under the rows and
    // are owned by the horizontal-pan gesture in this overlay.
    rowsHeightPx: Float = 0f,
    // 2026-09-06 — RELIABLE SELECTION. Relying on the click event to
    // pass through this overlay to the tiles underneath proved
    // fragile (this overlay sits on TOP of every lane). Instead the
    // overlay resolves the tap itself: it converts the touch position
    // into (track row, content-space time) and asks the timeline
    // which object lives there. The clip-block `clickable`s stay as
    // a second chance, but they no longer have to win the pointer
    // race for selection to work.
    rowKinds: List<com.aicreatorstudio.app.model.TrackKind> = emptyList(),
    lanesData: TimelineLaneData = TimelineLaneData(),
    onTapObject: (TimelineObjectRef) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current
    val railWidthPx = with(density) { railWidthDp.toPx() }

    // Auto-scroll: keep the playhead inside the visible lane while the
    // video is playing. We only scroll the lane when the playhead would
    // otherwise leave a comfortable deadband near the edges — that way
    // the playhead moves with time at the natural reading speed, and
    // the lane scrolls underneath it before it falls off-screen.
    //
    // 2026-09-06 playhead rebuild:
    //   - While SCRUBBING the auto-scroll is suspended: the playhead
    //     line is glued to the finger 1:1 in viewport space, so any
    //     lane scroll would visually detach the line from the finger.
    //   - On RELEASE (idle, paused) a one-shot visibility snap brings
    //     a playhead that was dragged past the viewport edge back
    //     inside the deadband.
    //   - The old `snapshotFlow { currentPositionMs }` captured a
    //     plain Long PARAMETER, not a State — snapshotFlow has nothing
    //     observable to read, so the flow emitted exactly once and the
    //     follow-while-playing was effectively dead. The position now
    //     flows through rememberUpdatedState, which snapshotFlow CAN
    //     observe.
    val deadbandLeft = laneWidthPx * 0.20f
    val deadbandRight = laneWidthPx * 0.80f
    val positionRef = rememberUpdatedState(currentPositionMs)
    LaunchedEffect(isPlaying, isScrubbing, laneWidthPx, timelineWidthPx, pixelsPerMs, isDraggingClip) {
        if (isDraggingClip) return@LaunchedEffect
        if (isScrubbing) return@LaunchedEffect
        if (!isPlaying) {
            // One-shot visibility snap after a drag release / seek.
            val ms = positionRef.value
            val x = ms * pixelsPerMs - trackScroll.value
            val desiredScroll: Int = when {
                x > deadbandRight -> (ms * pixelsPerMs - deadbandRight).toInt()
                x < deadbandLeft && trackScroll.value > 0 ->
                    (ms * pixelsPerMs - deadbandLeft).coerceAtLeast(0f).toInt()
                else -> trackScroll.value
            }
            val maxScroll = (timelineWidthPx - laneWidthPx).coerceAtLeast(0f).toInt()
            val clamped = desiredScroll.coerceIn(0, maxScroll)
            if (clamped != trackScroll.value) {
                trackScroll.animateScrollTo(clamped)
            }
            return@LaunchedEffect
        }
        snapshotFlow { positionRef.value }.collect { ms ->
            val playheadXInViewport = (ms * pixelsPerMs) - trackScroll.value
            val desiredScroll: Int = when {
                // Playhead has crossed the right edge of the deadband
                // — push the lane forward so the playhead sits at the
                // right edge of the deadband.
                playheadXInViewport > deadbandRight -> {
                    val target = (ms * pixelsPerMs) - deadbandRight
                    target.toInt()
                }
                // Playhead is before the left edge of the deadband
                // (e.g. the user seeked back) — pull the lane back so
                // the playhead sits at the left edge of the deadband.
                playheadXInViewport < deadbandLeft && trackScroll.value > 0 -> {
                    val target = (ms * pixelsPerMs) - deadbandLeft
                    target.coerceAtLeast(0f).toInt()
                }
                else -> trackScroll.value
            }
            val maxScroll = (timelineWidthPx - laneWidthPx).coerceAtLeast(0f).toInt()
            val clamped = desiredScroll.coerceIn(0, maxScroll)
            if (clamped != trackScroll.value) {
                trackScroll.animateScrollTo(clamped)
            }
        }
    }

    // Playhead x in the overlay's local coordinate space. The overlay
    // spans the whole BoxWithConstraints width, so its x=0 starts at
    // the rail's left edge. The playhead's x within the lane is
    // (currentPositionMs * pixelsPerMs) which is a *content-space*
    // offset; subtracting the current scroll value gives the
    // viewport-space offset, then we add the rail width to convert
    // back to overlay-local space.
    val playheadXInOverlay: Float =
        (currentPositionMs.coerceAtLeast(0L) * pixelsPerMs) - trackScroll.value + railWidthPx

    val hitWidthPx = with(density) { EditorLayoutSpec.TimelinePlayheadHitWidth.toPx() }

    // Live refs for the scrub gesture (see the pointerInput below).
    val pixelsPerMsRef = rememberUpdatedState(pixelsPerMs)
    val railWidthPxRef = rememberUpdatedState(railWidthPx)
    val hitWidthPxRef = rememberUpdatedState(hitWidthPx)
    val scrubStartRef = rememberUpdatedState(onScrubStart)
    val scrubSeekRef = rememberUpdatedState(onScrubSeek)
    val scrubEndRef = rememberUpdatedState(onScrubEnd)
    val draggingClipRef = rememberUpdatedState(isDraggingClip)
    val rowKindsRef = rememberUpdatedState(rowKinds)
    val lanesDataRef = rememberUpdatedState(lanesData)
    val onTapObjectRef = rememberUpdatedState(onTapObject)
    // Live vertical offset of the lane stack (content space = viewport y + this).
    val laneVScrollRef = rememberUpdatedState(laneVerticalScroll)

    Box(
        modifier = modifier
            .fillMaxWidth()
            // ── Vertical scroll passthrough ───────────────────
            // First handler: vertical-dominant drags dispatch to the
            // lane stack's verticalScroll before tap-to-select or
            // scrub handlers block them.
            .pointerInput(Unit) {
                val vConfig = viewConfiguration
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var accDy = 0f
                    var accDx = 0f
                    var yielded = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        val change = pressed[0]
                        val dy = change.position.y - change.previousPosition.y
                        val dx = change.position.x - change.previousPosition.x
                        accDy += dy
                        accDx += dx
                        if (!yielded && abs(accDy) > vConfig.touchSlop && abs(accDy) > abs(accDx)) {
                            yielded = true
                        }
                        if (yielded) {
                            if (dy != 0f) laneVerticalScroll.dispatchRawDelta(-dy)
                            change.consume()
                        }
                        // When not yielded: stay in the loop but don't
                        // consume — the tap/scrub handlers below will
                        // also see these events and can claim them.
                    }
                }
            }
            // ----------------------------------------------------------------
            // 2026-09-06 HORIZONTAL-PAN FIX — the "dead zone" below the
            // track rows: drags under the last row pan the lane via
            // dispatchRawDelta. (unchanged)
            // ----------------------------------------------------------------
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downY = down.position.y
                    // Dead zone is BELOW the last row in CONTENT space
                    // (viewport y + vertical scroll offset).
                    val contentY = downY + laneVScrollRef.value.value
                    if (contentY <= rowsHeightPx) return@awaitEachGesture
                    if (draggingClipRef.value) return@awaitEachGesture
                    val pxms = pixelsPerMsRef.value.coerceAtLeast(0.0001f)
                    val playheadXNow =
                        positionRef.value.coerceAtLeast(0L) * pxms -
                            trackScroll.value + railWidthPxRef.value
                    if (abs(down.position.x - playheadXNow) <= hitWidthPxRef.value / 2f) {
                        // Inside the playhead column — the scrub
                        // gesture below owns this touch.
                        return@awaitEachGesture
                    }
                    down.consume()
                    var lastX = down.position.x
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        val change = pressed[0]
                        val dx = change.position.x - lastX
                        lastX = change.position.x
                        if (dx != 0f) {
                            // dispatchRawDelta is non-suspending and
                            // clamps to the scroll bounds internally.
                            trackScroll.dispatchRawDelta(-dx)
                        }
                        event.changes.forEach { if (it.pressed) it.consume() }
                    }
                }
            }
            // ----------------------------------------------------------------
            // 2026-09-06 RELIABLE TAP-TO-SELECT — the overlay resolves
            // taps itself (see TimelineLaneData KDoc). A TAP (down→up
            // within touch slop, outside the playhead column, on a
            // track row) is resolved into (row, timeMs) → the timeline
            // selects whatever object lives there. Drags (past slop)
            // are left unconsumed for the row scroll / clip gestures.
            // ----------------------------------------------------------------
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downX = down.position.x
                    val downY = down.position.y
                    // Content-space Y so taps on vertically-scrolled
                    // rows resolve to the correct lane.
                    val contentDownY = downY + laneVScrollRef.value.value
                    if (contentDownY > rowsHeightPx) return@awaitEachGesture
                    if (draggingClipRef.value) return@awaitEachGesture
                    val pxms = pixelsPerMsRef.value.coerceAtLeast(0.0001f)
                    val playheadXNow =
                        positionRef.value.coerceAtLeast(0L) * pxms -
                            trackScroll.value + railWidthPxRef.value
                    if (abs(downX - playheadXNow) <= hitWidthPxRef.value / 2f) {
                        return@awaitEachGesture
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        // A child (ClipBlock / audio tile / subtitle
                        // tile) that handled the tap marks the up as
                        // consumed — in that case it already selected,
                        // so the overlay must not select AGAIN (a
                        // double-fire would break toggle-to-deselect).
                        val upConsumedByChild =
                            event.changes.any { it.isConsumed }
                        if (pressed.isEmpty()) {
                            if (upConsumedByChild) break
                            // Released without dragging → TAP.
                            val timeMs = ((downX - railWidthPxRef.value +
                                trackScroll.value) / pxms)
                                .roundToInt().toLong().coerceAtLeast(0L)
                            val rowIndex = (contentDownY /
                                EditorLayoutSpec.TimelineTrackRowHeight.toPx())
                                .toInt().coerceIn(0, rowKindsRef.value.size - 1)
                            val kind = rowKindsRef.value.getOrNull(rowIndex)
                            if (kind != null) {
                                onTapObjectRef.value(
                                    resolveTimelineTap(kind, lanesDataRef.value, timeMs)
                                )
                            }
                            break
                        }
                        val change = pressed[0]
                        val dxMoved = change.position.x - downX
                        val dyMoved = change.position.y - downY
                        if (dxMoved * dxMoved + dyMoved * dyMoved >
                            viewConfiguration.touchSlop * viewConfiguration.touchSlop
                        ) {
                            // It's a scroll / clip drag — abort the tap
                            // and let the row gestures own the rest.
                            break
                        }
                    }
                }
            }
            // ----------------------------------------------------------------
            // 2026-09-06 playhead rebuild — the scrub gesture.
            //
            // The old gesture lived on a hit COLUMN that was OFFSET to
            // the playhead's current x. That column MOVES during the
            // drag (every applied delta re-offsets it), and pointer
            // positions are reported in the node's own space — so the
            // delta math fought its own coordinate system: the playhead
            // lagged the finger, jittered, and snapped back ("moves a
            // bit, then stops").
            //
            // The new gesture is anchored to this STATIC overlay Box:
            //   - Hit-test at touch-down: the scrub only starts when
            //     the finger lands within the playhead's hit column
            //     (± hitWidth/2 around the line). Everywhere else the
            //     events are left unconsumed for the lane's
            //     horizontalScroll / clip gestures.
            //   - Delta math runs in viewport space, which never moves
            //     under the finger: 1 px = exactly 1/ppms ms at every
            //     zoom level, no feedback, no jump on grab.
            //   - Once begun, the gesture consumes all changes until
            //     release — wandering outside the column keeps scrubbing.
            // ----------------------------------------------------------------
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downX = down.position.x
                    val pxms = pixelsPerMsRef.value.coerceAtLeast(0.0001f)
                    val playheadXNow =
                        positionRef.value.coerceAtLeast(0L) * pxms -
                            trackScroll.value + railWidthPxRef.value
                    val insideColumn =
                        abs(downX - playheadXNow) <= hitWidthPxRef.value / 2f
                    if (!insideColumn || draggingClipRef.value) {
                        // Outside the playhead (or a clip drag is in
                        // flight): never consume — the lane scroll and
                        // clip gestures own this touch.
                        return@awaitEachGesture
                    }
                    down.consume()
                    scrubStartRef.value()
                    val initialMs = positionRef.value
                    val initialFingerX = downX
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        val change = pressed[0]
                        val deltaPx = change.position.x - initialFingerX
                        val deltaMs = (deltaPx /
                            pixelsPerMsRef.value.coerceAtLeast(0.0001f))
                            .roundToInt()
                            .toLong()
                        scrubSeekRef.value((initialMs + deltaMs).coerceAtLeast(0L))
                        // Consume every pressed change so the lane
                        // scroll / clip gestures cannot steal mid-drag.
                        event.changes.forEach { if (it.pressed) it.consume() }
                    }
                    scrubEndRef.value()
                }
            }
    ) {
        // Playhead visuals: rounded capsule handle + neon glowing line
        // ── Handle: rounded pill shape ─────────────────────
        Box(
            modifier = Modifier
                .offset {
                    androidx.compose.ui.unit.IntOffset(
                        (playheadXInOverlay - 10f).toInt(),
                        0
                    )
                }
                .size(20.dp, EditorLayoutSpec.TimelinePlayheadHandleHeight)
                .clip(RoundedCornerShape(5.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color.White, AccentBright.copy(alpha = 0.9f))
                    )
                )
        )
        // ── Glowing vertical line ──────────────────────────
        Box(
            modifier = Modifier
                .offset {
                    androidx.compose.ui.unit.IntOffset(
                        (playheadXInOverlay -
                            EditorLayoutSpec.TimelinePlayheadWidth.toPx() / 2f).toInt(),
                        EditorLayoutSpec.TimelinePlayheadHandleHeight.roundToPx()
                    )
                }
                .width(EditorLayoutSpec.TimelinePlayheadWidth)
                .fillMaxHeight()
                .background(
                    Brush.verticalGradient(
                        listOf(AccentCyan, AccentPrimary)
                    )
                )
        )
        // ── Neon glow halo ─────────────────────────────────
        Box(
            modifier = Modifier
                .offset {
                    androidx.compose.ui.unit.IntOffset(
                        (playheadXInOverlay - 5f).toInt(),
                        EditorLayoutSpec.TimelinePlayheadHandleHeight.roundToPx()
                    )
                }
                .width(10.dp)
                .fillMaxHeight()
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, AccentCyan.copy(alpha = 0.15f), Color.Transparent)
                    )
                )
        )
    }
}

/**
 * Suspends until a second pointer lands, or returns `false` when the
 * gesture ends with a single pointer (lift / cancel). Never consumes
 * any event, so a 1-finger drag keeps flowing to the lane's
 * horizontalScroll while we wait.
 */
private suspend fun AwaitPointerEventScope.awaitTwoDownOrFail(): Boolean {
    awaitFirstDown(requireUnconsumed = false)
    while (true) {
        val event = awaitPointerEvent()
        val pressed = event.changes.filter { it.pressed }
        if (pressed.size >= 2) return true
        if (pressed.isEmpty()) return false
    }
}

/** Distance between the first two pressed pointers. */
private fun AwaitPointerEventScope.twoPointerSpan(): Float {
    val pressed = currentEvent.changes.filter { it.pressed }
    if (pressed.size < 2) return 0f
    val p0 = pressed[0].position
    val p1 = pressed[1].position
    return hypot(
        (p1.x - p0.x).toDouble(),
        (p1.y - p0.y).toDouble()
    ).toFloat()
}

// =====================================================================
// 2026-09-06 — Video Audio lane (selectable paired-audio tiles)
// =====================================================================

/**
 * A horizontally-scrollable lane rendering the project's audio
 * objects that belong to video clips (the paired audio of every
 * import) as SELECTABLE tiles with a static waveform bar.
 *
 * Selection: tapping a tile calls [onAudioClipSelected] with its id
 * (null to deselect). The selected tile gets a bright accent border
 * + fill so the user can see exactly which audio object the Volume
 * panel's mute / fade / volume controls will act on.
 *
 * 2026-09-29 — Beat markers. Each tile that carries
 * [com.aicreatorstudio.app.model.AudioClip.beatData] also paints a row of
 * yellow ticks at its detected onsets, drawn *inside* the tile so a tick can
 * never appear over a neighbouring clip. Markers are stored in source time
 * and mapped through [BeatDetector.toTimelineMarkers] here, then converted
 * back to tile-local offsets, which is why the code subtracts `startMs`
 * rather than using the raw timestamp.
 */
@Composable
private fun AudioLaneRow(
    label: String,
    accent: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    audioClips: List<com.aicreatorstudio.app.model.AudioClip>,
    selectedAudioClipId: String?,
    pixelsPerMs: Float,
    railWidth: androidx.compose.ui.unit.Dp,
    laneWidthDp: androidx.compose.ui.unit.Dp,
    trackWidthDp: androidx.compose.ui.unit.Dp,
    hScroll: androidx.compose.foundation.ScrollState,
    onAudioClipSelected: (String?) -> Unit,
    /**
     * 2026-10-20 — drag-to-move for an audio tile, `(audioClipId, deltaMs)`.
     *
     * A **delta**, unlike the video lane's `onClipMoved`: this tile's gesture is
     * never restarted during a drag, so its own `startTimeMs` is frozen inside
     * the pointer block and an absolute position computed here would jitter in
     * place. The ViewModel resolves the delta against live state.
     */
    onAudioClipMoved: (String, Long) -> Unit = { _, _ -> },
    /**
     * 2026-10-20 — true while an audio tile is being dragged. The timeline
     * feeds this into the same auto-scroll gate as a clip drag, so the lane
     * does not fight the finger.
     */
    onAudioClipDragStateChange: (Boolean) -> Unit = {},
    isFocused: Boolean,
    // 2026-10-05 — real waveform peaks for these audio tiles.
    audioWaveforms: Map<String, List<com.aicreatorstudio.app.model.AudioWaveformPeak>> = emptyMap(),
    modifier: Modifier = Modifier
) {
    val rowHeight = EditorLayoutSpec.TimelineTrackRowHeight
    val touchSlopPx = LocalViewConfiguration.current.touchSlop
    // 2026-10-20 — same rule as ClipBlock: the tile's `pointerInput` key is the
    // audio id, which does not change during a drag, so anything the gesture
    // block captures is frozen. The callbacks close over `startMs` in the loop
    // below and compute an absolute position from it, so they must be read live.
    val currentOnMoved by rememberUpdatedState(onAudioClipMoved)
    val currentOnDragState by rememberUpdatedState(onAudioClipDragStateChange)
    Box(modifier = modifier.fillMaxWidth().height(rowHeight)) {
        Row(modifier = Modifier.fillMaxSize()) {
            TrackRailLabel(
                label = label,
                accent = accent,
                icon = icon,
                isFocused = isFocused
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(hScroll)
            ) {
                Box(modifier = Modifier.width(trackWidthDp).fillMaxHeight()) {
                    // Lane background
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White.copy(alpha = 0.03f))
                            .border(1.dp, HairlineSubtle, RoundedCornerShape(0.dp))
                    )
                    audioClips.forEach { audio ->
                        val startMs = audio.startTimeMs.coerceAtLeast(0L)
                        val durMs = audio.durationMs.coerceAtLeast(1L)
                        val startDp = with(LocalDensity.current) {
                            (startMs * pixelsPerMs).toFloat().toDp()
                        }
                        val widthDp = with(LocalDensity.current) {
                            (durMs * pixelsPerMs).toFloat().toDp()
                        }
                        val isSelected = audio.id == selectedAudioClipId
                        val isMuted = audio.muted
                        Box(
                            contentAlignment = Alignment.CenterStart,
                            modifier = Modifier
                                .offset {
                                    androidx.compose.ui.unit.IntOffset(
                                        startDp.toPx().toInt(), 0
                                    )
                                }
                                .width(widthDp.coerceAtLeast(EditorLayoutSpec.TimelineClipMinWidthDp))
                                .fillMaxHeight()
                                .padding(horizontal = 1.dp, vertical = 4.dp)
                                .clip(RoundedCornerShape(EditorLayoutSpec.TimelineClipCornerRadius))
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            accent.copy(alpha = if (isSelected) 0.62f else 0.42f),
                                            CinematicSurface.copy(alpha = 0.96f)
                                        )
                                    )
                                )
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) Color.White
                                    else accent.copy(alpha = 0.65f),
                                    shape = RoundedCornerShape(EditorLayoutSpec.TimelineClipCornerRadius)
                                )
                                .clickable {
                                    onAudioClipSelected(
                                        if (isSelected) null else audio.id
                                    )
                                }
                                // 2026-10-20 — DRAG-TO-MOVE. The audio lane
                                // was the only draggable-looking lane whose
                                // tiles could not be moved: they had
                                // `clickable` and nothing else. The gesture
                                // mirrors `ClipBlock`'s body drag (no trim
                                // edges — an audio object has no trim window),
                                // including the vertical-dominant yield so the
                                // lane stack still scrolls.
                                //
                                // Nothing is consumed until the press travels
                                // past the touch slop, so a tap still reaches
                                // `clickable` above and selects the tile.
                                .pointerInput(audio.id, pixelsPerMs) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        var dragStartX = down.position.x
                                        var accDx = 0f
                                        var accDy = 0f
                                        var yielded = false
                                        var dragging = false
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val changes = event.changes
                                            if (changes.all { !it.pressed }) break
                                            val change = changes.firstOrNull { it.pressed }
                                                ?: continue
                                            accDx += change.position.x -
                                                change.previousPosition.x
                                            accDy += change.position.y -
                                                change.previousPosition.y
                                            if (!yielded &&
                                                abs(accDy) > touchSlopPx &&
                                                abs(accDy) > abs(accDx)
                                            ) {
                                                yielded = true
                                                if (dragging) {
                                                    dragging = false
                                                    currentOnDragState(false)
                                                }
                                                continue
                                            }
                                            if (yielded) continue
                                            if (!dragging) {
                                                if (abs(accDx) <= touchSlopPx) continue
                                                dragging = true
                                                currentOnDragState(true)
                                            }
                                            val deltaPx = change.position.x - dragStartX
                                            dragStartX = change.position.x
                                            val deltaMs = (
                                                deltaPx /
                                                    pixelsPerMs.coerceAtLeast(0.0001f)
                                                ).roundToInt().toLong()
                                            // The DELTA is passed, not an absolute
                                            // start: `startMs` belongs to the
                                            // composition that created this
                                            // gesture block and is frozen for the
                                            // whole drag. The ViewModel resolves
                                            // it against live state instead.
                                            if (deltaMs != 0L) {
                                                currentOnMoved(audio.id, deltaMs)
                                            }
                                            change.consume()
                                        }
                                        if (dragging) currentOnDragState(false)
                                    }
                                }
                                .padding(horizontal = 6.dp)
                        ) {
                            // Modern glowing waveform (shared renderer) + chic
                            // muted treatment: desaturated bars with a slim
                            // rounded strike through the middle.
                            Box(modifier = Modifier.fillMaxSize()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .fillMaxHeight(0.45f)
                                        .background(
                                            Brush.verticalGradient(
                                                colors = listOf(
                                                    Color.White.copy(alpha = 0.10f),
                                                    Color.Transparent
                                                )
                                            )
                                        )
                                )
                                Waveform(
                                    seed = audio.id,
                                    color = if (isMuted) Color.Gray else accent,
                                    dimmed = isMuted,
                                    isSelected = isSelected,
                                    peaks = audioWaveforms[audio.waveformCacheKey ?: audio.id].orEmpty(),
                                    modifier = Modifier.fillMaxSize()
                                )
                                if (isMuted) {
                                    androidx.compose.foundation.Canvas(
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        drawLine(
                                            color = Color.White.copy(alpha = 0.85f),
                                            start = Offset(8f, size.height / 2f),
                                            end = Offset(size.width - 8f, size.height / 2f),
                                            strokeWidth = 4f,
                                            cap = StrokeCap.Round
                                        )
                                    }
                                }
                                // 2026-09-29 — beat ticks, painted last so
                                // they sit above both the waveform and the
                                // mute strike. Nothing is drawn when the clip
                                // was never analysed.
                                BeatMarkerStrip(
                                    beatData = audio.beatData,
                                    clipStartMs = startMs,
                                    clipOffsetMs = audio.clipOffsetMs,
                                    clipDurationMs = durMs,
                                    pixelsPerMs = pixelsPerMs
                                )
                            }
                            Text(
                                text = if (isMuted) "🔇 ${audio.fileName.take(12)}"
                                else audio.fileName.take(14),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Yellow beat ticks for one audio tile, in 2026-09-29's Audio Beat Detection
 * feature.
 *
 * The strip is a single [Canvas] covering the whole tile rather than one
 * composable per marker: a dense track can carry several hundred onsets, and
 * one draw pass keeps that flat where a composable per tick would not.
 *
 * Time mapping is deliberately done in two steps. [BeatDetector.toTimelineMarkers]
 * yields absolute timeline positions; subtracting `clipStartMs` converts them
 * back to tile-local offsets. Doing the subtraction after the helper (rather
 * than reimplementing the offset maths against raw source timestamps) keeps
 * one authority for "which markers belong to this clip", which is the step
 * that has to agree with the exporter.
 *
 * @param beatData null = never analysed (draw nothing). A non-null empty list
 *   draws nothing either, but for a different reason — there is nothing to
 *   show rather than nothing known. The two are visually identical on purpose;
 *   the panel is where the distinction is surfaced.
 */
@Composable
private fun BeatMarkerStrip(
    beatData: com.aicreatorstudio.app.util.BeatDetector.AudioBeatData?,
    clipStartMs: Long,
    clipOffsetMs: Long,
    clipDurationMs: Long,
    pixelsPerMs: Float
) {
    val markers = com.aicreatorstudio.app.util.BeatDetector.toTimelineMarkers(
        beatData = beatData,
        clipStartMs = clipStartMs,
        clipOffsetMs = clipOffsetMs,
        clipDurationMs = clipDurationMs
    )
    if (markers.isEmpty()) return

    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
        val height = size.height
        // Thicker than a hairline so a tick survives at the minimum zoom; a
        // 1px tick disappears on a 1080p panel's 3x density.
        val strokeWidth = 2f
        val fadeOutY = height * 0.18f
        for (marker in markers) {
            val localMs = marker.timestampMs - clipStartMs
            // Belt-and-braces: the helper already drops out-of-span markers,
            // but a canvas that draws outside its own tile would bleed onto a
            // neighbour, which is worse than a missing tick.
            if (localMs < 0L || localMs > clipDurationMs) continue
            val x = (localMs * pixelsPerMs).toFloat()
            if (x < 0f || x > size.width) continue
            val alpha = com.aicreatorstudio.app.util.BeatDetector.markerAlpha(marker.intensity)
            val top = fadeOutY +
                (1f - com.aicreatorstudio.app.util.BeatDetector.markerHeightFraction(marker.intensity)) *
                (height - fadeOutY)
            drawLine(
                color = TimelineBeatYellow.copy(alpha = alpha),
                start = Offset(x, top),
                end = Offset(x, height - 1f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
        }
    }
}

/**
 * Marker colour. Mirrored from `BeatPanel.BeatMarkerYellow` — kept as its own
 * constant here so the timeline module carries no dependency on the panel
 * module; the values must stay in sync.
 */
private val TimelineBeatYellow = androidx.compose.ui.graphics.Color(0xFFFFD54A)

/**
 * Smart Highlights band colour. Emerald, deliberately distinct from the
 * beat-tick yellow and the blue transition handles: the three overlays can
 * coexist on one tile and must not be confused at a glance.
 */
private val TimelineHighlightGreen = androidx.compose.ui.graphics.Color(0xFF34D399)

/**
 * Beat ticks across a VIDEO clip block, driven by the project-wide marker
 * list rather than a per-clip model.
 *
 * The difference from [BeatMarkerStrip] is not cosmetic: an audio tile carries
 * its own [com.aicreatorstudio.app.model.AudioClip.beatData] and only has to
 * subtract its own offset, whereas a video clip has no beats of its own — the
 * music it is being cut to is usually a separate lane. So this reads the
 * flattened, deduplicated project list and does a range filter, which is also
 * why it must never assume the list is sorted.
 *
 * Drawn full-height and flat-topped (no per-marker intensity ramp): on the
 * video lane the tick is a *cut guide*, so a consistent, easily-aligned edge
 * matters more than showing which onsets were loud.
 */
@Composable
private fun VideoBeatMarkerStrip(
    beatMarkers: List<Long>,
    clipStartMs: Long,
    clipDurationMs: Long,
    pixelsPerMs: Float
) {
    if (beatMarkers.isEmpty() || clipDurationMs <= 0L) return
    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
        val height = size.height
        val strokeWidth = 2f
        val insetTop = height * 0.16f
        for (marker in beatMarkers) {
            val localMs = marker - clipStartMs
            // Strictly inside: a tick exactly on a block boundary would be
            // drawn twice (once by each neighbouring block) and read as a
            // thicker line, which is misleading about where the beat is.
            if (localMs <= 0L || localMs >= clipDurationMs) continue
            val x = (localMs * pixelsPerMs).toFloat()
            if (x < 0f || x > size.width) continue
            drawLine(
                color = TimelineBeatYellow.copy(alpha = 0.9f),
                start = Offset(x, insetTop),
                end = Offset(x, height - 1f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
        }
    }
}

/** Snap radius for beat markers. Mirrors `EditorViewModel.BEAT_SNAP_MS`. */
private const val TIMELINE_BEAT_SNAP_MS = 18L

/**
 * Smart Highlights ranges as a band along the bottom of the clip tile.
 *
 * `HighlightRange.startMs/endMs` are clip-local, so no origin shift is
 * needed (unlike the beat ticks, which are timeline-absolute). Opacity
 * tracks the planner score, which is the only visual hint about *why* a
 * stretch was picked — a low-score range still shows, just fainter.
 */
@Composable
private fun HighlightRangeStrip(
    ranges: List<com.aicreatorstudio.app.model.HighlightRange>,
    clipDurationMs: Long,
    pixelsPerMs: Float
) {
    if (ranges.isEmpty() || clipDurationMs <= 0L) return
    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
        val bandTop = size.height * 0.72f
        val bandHeight = (size.height - bandTop).coerceAtLeast(3f)
        for (range in ranges) {
            val startMs = range.startMs.coerceIn(0L, clipDurationMs)
            val endMs = range.endMs.coerceIn(startMs, clipDurationMs)
            if (endMs <= startMs) continue
            val x0 = (startMs * pixelsPerMs).toFloat()
            val x1 = (endMs * pixelsPerMs).toFloat()
            if (x1 < 0f || x0 > size.width) continue
            val alpha = 0.35f + 0.55f * range.score.coerceIn(0f, 1f)
            drawRect(
                color = TimelineHighlightGreen.copy(alpha = alpha),
                topLeft = Offset(x0.coerceAtLeast(0f), bandTop),
                size = androidx.compose.ui.geometry.Size(
                    (x1 - x0).coerceAtLeast(1.5f),
                    bandHeight
                )
            )
        }
    }
}

/**
 * PHASE A (Persistence Integrity) — width used to draw a clip whose
 * effective duration collapsed to zero (an inverted trim).
 *
 * The previous value was 5 000 ms, i.e. a tile that claimed to be five
 * seconds long while playing nothing at all. 1 ms matches the [.MIN]
 * trim floor the model enforces, so the tile is a visible sliver the
 * user can grab and fix rather than a convincing-looking clip.
 */
private const val ZERO_LENGTH_TILE_MS = 1L

/**
 * Snap [positionMs] onto the nearest beat onset within
 * [TIMELINE_BEAT_SNAP_MS], or return it unchanged.
 *
 * Deliberately tighter than the 80 ms clip-boundary snap: the value of
 * snapping to a beat is precision, and a cut 40 ms off the kick is the exact
 * defect the feature exists to remove.
 *
 * The search itself is [BeatDetector.nearestMarker] rather than a local scan.
 * That function is already unit-tested (including against a brute-force
 * cross-check) and is the one authority on "which marker is nearest", so
 * duplicating the arithmetic here would create a second implementation that
 * could silently disagree with the ViewModel's own snap path. The marker
 * list is adapted into a throwaway [BeatDetector.AudioBeatData] because the
 * helper works on the persisted model — the alternative was a second
 * overload of the search for a UI-only shape.
 *
 * [markers] is expected ascending (`allBeatMarkersOnTimeline` guarantees it);
 * since the underlying search is a binary search, a mis-ordered list would
 * give wrong answers rather than merely being slower, which is why the
 * ordering contract is stated here and asserted in the ViewModel's KDoc.
 */
private fun snapToBeatMarkers(positionMs: Long, markers: List<Long>): Long {
    if (markers.isEmpty()) return positionMs
    val beatData = com.aicreatorstudio.app.util.BeatDetector.AudioBeatData(
        markers = markers.map { com.aicreatorstudio.app.util.BeatDetector.BeatMarker(it) }
    )
    val hit = com.aicreatorstudio.app.util.BeatDetector.nearestMarker(
        beatData = beatData,
        positionMs = positionMs,
        toleranceMs = TIMELINE_BEAT_SNAP_MS
    )
    return hit?.timestampMs ?: positionMs
}
