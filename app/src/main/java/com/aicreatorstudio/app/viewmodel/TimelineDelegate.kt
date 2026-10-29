package com.aicreatorstudio.app.viewmodel

import androidx.compose.runtime.Stable
import com.aicreatorstudio.app.model.ClipAnimation
import com.aicreatorstudio.app.model.ClipIdTransformUpdater
import com.aicreatorstudio.app.model.CropPreset
import com.aicreatorstudio.app.model.EditorUiState
import com.aicreatorstudio.app.model.FillMode
import com.aicreatorstudio.app.model.TrackKind
import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.model.VideoEffect
import com.aicreatorstudio.app.model.VideoOverlay
import com.aicreatorstudio.app.model.util.SpeedCurveInterpolator
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Manages timeline clip operations: selection, trimming, splitting,
 * duplicating, moving, volume/fade, track visibility, and overlay
 * management. Operates on the shared [_state] without owning it.
 */
@Stable
class TimelineDelegate(
    private val state: MutableStateFlow<EditorUiState>,
    private val history: HistoryDelegate
) {
    // ── Selection ──────────────────────────────────────────
    fun selectClip(clipId: String?) {
        state.update {
            it.copy(
                selectedClipId = clipId,
                selectedAudioClipId = null,
                selectedTextId = null
            )
        }
    }

    fun selectAudioClip(audioClipId: String?) {
        val isSameTile = audioClipId != null && audioClipId == state.value.selectedAudioClipId
        val effectiveId = if (isSameTile) null else audioClipId
        state.update {
            it.copy(
                selectedAudioClipId = effectiveId,
                selectedClipId = null,
                selectedTextId = null
            )
        }
    }

    // ── Clip mutations ────────────────────────────────────
    fun updateClipPosition(id: String, newStartMs: Long, pushHist: Boolean = true) {
        if (pushHist) history.push()
        state.update { s ->
            val p = s.project ?: return@update s
            s.copy(project = p.copy(clips = p.clips.map { c ->
                if (c.id == id) c.copy(timelineStartMs = newStartMs.coerceAtLeast(0L)) else c
            }))
        }
    }

    /**
     * Splits the clip at the supplied **composition timeline** position
     * (same semantics as [EditorViewModel.splitClip]: `atMs` is global
     * playhead time, not clip-local or source time). The cut point is
     * converted to a source position through the clip's existing trim so
     * both halves keep playing their own source span — the right half
     * must never restart from source 0.
     */
    fun splitClip(id: String, atMs: Long) {
        history.push()
        state.update { s ->
            val p = s.project ?: return@update s
            val clip = p.clips.firstOrNull { it.id == id } ?: return@update s
            val base = clip.timelineStartMs.coerceAtLeast(0L)
            val offsetIntoClip = atMs - base
            if (offsetIntoClip <= 0L || offsetIntoClip >= clip.effectiveDurationMs) return@update s
            val splitSourceMs = clip.trimStartMs + offsetIntoClip

            // Slice the speed ramp at the cut so each half plays only its
            // own portion of the curve (mirrors EditorViewModel.splitClip).
            val splitFraction = (offsetIntoClip.toDouble() /
                clip.effectiveDurationMs.toDouble().coerceAtLeast(1.0)).toFloat()
            val (leftCurve, rightCurve) =
                SpeedCurveInterpolator.split(clip.speedCurve, splitFraction)

            val left = clip.copy(
                trimEndMs = splitSourceMs,
                speedCurve = leftCurve.takeIf { it.isActive }
            )
            val right = clip.copy(
                id = UUID.randomUUID().toString(),
                timelineStartMs = base + offsetIntoClip,
                trimStartMs = splitSourceMs,
                trimEndMs = clip.trimEndMs,
                speedCurve = rightCurve.takeIf { it.isActive }
            )
            s.copy(project = p.copy(clips = p.clips.map { if (it.id == id) left else it } + right))
        }
    }

    fun deleteClip(clipId: String) {
        history.push()
        state.update { s ->
            val p = s.project ?: return@update s
            s.copy(
                project = p.copy(clips = p.clips.filter { it.id != clipId }),
                selectedClipId = if (s.selectedClipId == clipId) null else s.selectedClipId
            )
        }
    }

    fun duplicateClip(clipId: String) {
        history.push()
        state.update { s ->
            val p = s.project ?: return@update s
            val clip = p.clips.firstOrNull { it.id == clipId } ?: return@update s
            val dup = clip.copy(
                id = UUID.randomUUID().toString(),
                timelineStartMs = (p.clips.maxOfOrNull { it.timelineStartMs + it.effectiveDurationMs } ?: 0L)
            )
            s.copy(project = p.copy(clips = p.clips + dup))
        }
    }

    fun setClipTrim(id: String, startMs: Long, endMs: Long) {
        history.push()
        state.update { s ->
            val p = s.project ?: return@update s
            s.copy(project = p.copy(clips = p.clips.map { c ->
                if (c.id == id) c.copy(trimStartMs = startMs.coerceAtLeast(0L), trimEndMs = endMs) else c
            }))
        }
    }

    fun setClipSpeed(id: String, speed: Float) {
        history.push()
        state.update { s ->
            val p = s.project ?: return@update s
            s.copy(project = p.copy(clips = p.clips.map { c ->
                if (c.id == id) c.copy(speed = speed.coerceIn(0.25f, 4f)) else c
            }))
        }
    }

    fun updateClipAnimation(id: String, anim: ClipAnimation) {
        history.push()
        state.update { s ->
            val p = s.project ?: return@update s
            s.copy(project = p.copy(clips = p.clips.map { c ->
                if (c.id == id) c.copy(animation = anim) else c
            }))
        }
    }

    fun updateClipCrop(id: String, preset: CropPreset) {
        history.push()
        state.update { s ->
            val p = s.project ?: return@update s
            s.copy(project = p.copy(clips = p.clips.map { c ->
                if (c.id == id) c.copy(cropPreset = preset) else c
            }))
        }
    }

    fun setClipFadeInMs(id: String, fadeMs: Float) {
        history.push()
        state.update { s ->
            val p = s.project ?: return@update s
            s.copy(project = p.copy(clips = p.clips.map { c ->
                if (c.id == id) c.copy(fadeInMs = fadeMs.toLong().coerceAtLeast(0L)) else c
            }))
        }
    }

    fun setClipFadeOutMs(id: String, fadeMs: Float) {
        history.push()
        state.update { s ->
            val p = s.project ?: return@update s
            s.copy(project = p.copy(clips = p.clips.map { c ->
                if (c.id == id) c.copy(fadeOutMs = fadeMs.toLong().coerceAtLeast(0L)) else c
            }))
        }
    }

    fun setClipOpacity(id: String, opacity: Float) {
        history.push()
        state.update { s ->
            val p = s.project ?: return@update s
            s.copy(project = p.copy(clips = p.clips.map { c ->
                if (c.id == id) c.copy(opacity = opacity.coerceIn(0f, 1f)) else c
            }))
        }
    }

    fun selectVideoEffectForClip(id: String, effect: VideoEffect) {
        history.push()
        state.update { s ->
            val p = s.project ?: return@update s
            s.copy(project = p.copy(clips = p.clips.map { c ->
                if (c.id == id) c.copy(videoEffect = effect) else c
            }))
        }
    }

    // ── Volume / Audio ─────────────────────────────────────
    fun updateClipVolume(clipId: String, volume: Float) {
        history.push()
        state.update { s ->
            val p = s.project ?: return@update s
            s.copy(project = p.copy(clips = p.clips.map { c ->
                if (c.id == clipId) c.copy(volume = volume.coerceIn(0f, 2f)) else c
            }))
        }
    }

    fun updateAudioClipVolume(clipId: String, volume: Float) {
        history.push()
        state.update { s ->
            val p = s.project ?: return@update s
            s.copy(project = p.copy(audioClips = p.audioClips.map { a ->
                if (a.id == clipId) a.copy(volume = volume.coerceIn(0f, 2f)) else a
            }))
        }
    }

    fun updateAudioClipMute(clipId: String, muted: Boolean) {
        history.push()
        state.update { s ->
            val p = s.project ?: return@update s
            s.copy(project = p.copy(audioClips = p.audioClips.map { a ->
                if (a.id == clipId) a.copy(muted = muted) else a
            }))
        }
    }

    // ── Overlays ────────────────────────────────────────────
    fun addOverlay(clipId: String) {
        history.push()
        state.update { s ->
            s.copy(overlays = s.overlays + VideoOverlay(
                clipId = clipId,
                startTimeMs = s.playbackPositionMs.coerceAtLeast(0L),
                endTimeMs = s.playbackPositionMs.coerceAtLeast(0L) + 3000L
            ))
        }
    }

    fun removeOverlay(overlayId: String) {
        history.push()
        state.update { s -> s.copy(overlays = s.overlays.filter { it.id != overlayId }) }
    }

    // ── Track visibility ──────────────────────────────────
    fun toggleTrackVisibility(kind: TrackKind) {
        state.update { s ->
            val current = s.visibleTrackKinds
            s.copy(visibleTrackKinds = if (kind in current) current - kind else current + kind)
        }
    }

    fun setTrackVisibility(kind: TrackKind, visible: Boolean) {
        state.update { s ->
            val current = s.visibleTrackKinds
            s.copy(visibleTrackKinds = if (visible) current + kind else current - kind)
        }
    }

    // ── Zoom ───────────────────────────────────────────────
    fun onTimelineZoomChange(pxPerMs: Float) {
        state.update { it.copy(pixelsPerMs = pxPerMs) }
    }
}
