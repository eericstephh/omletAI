package com.aicreatorstudio.app.viewmodel

import androidx.compose.runtime.Stable
import com.aicreatorstudio.app.model.ColorGrading
import com.aicreatorstudio.app.model.EditorUiState
import com.aicreatorstudio.app.model.VideoEffect
import com.aicreatorstudio.app.model.VideoClip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

@Stable
class AdjustmentDelegate(
    private val state: MutableStateFlow<EditorUiState>,
    private val history: HistoryDelegate
) {
    fun onBrightnessChanged(b: Float) {
        history.push(); val v = b.coerceIn(0f, 2f)
        state.update { it.copy(brightness = v) }
        clipId()?.let { id -> mutateClip(id) { copy(brightness = v) } }
    }

    fun onContrastChanged(c: Float) {
        history.push(); val v = c.coerceIn(0f, 2f)
        state.update { it.copy(contrast = v) }
        clipId()?.let { id -> mutateClip(id) { copy(contrast = v) } }
    }

    fun onSaturationChanged(s: Float) {
        history.push(); val v = s.coerceIn(0f, 2f)
        state.update { it.copy(saturation = v) }
        clipId()?.let { id -> mutateClip(id) { copy(saturation = v) } }
    }

    fun onColorGradingChanged(g: ColorGrading) {
        history.push()
        state.update { it.copy(colorGrading = g) }
        clipId()?.let { id -> mutateClip(id) { copy(colorGrading = g) } }
    }

    fun resetColorGrading() {
        history.push()
        state.update { it.copy(colorGrading = ColorGrading.Identity) }
        clipId()?.let { id -> mutateClip(id) { copy(colorGrading = ColorGrading.Identity) } }
    }

    fun resetColorGradingForSelectedClip() {
        clipId()?.let { id ->
            history.push()
            mutateClip(id) { copy(colorGrading = ColorGrading.Identity) }
        }
    }

    fun resetEffects() {
        history.push()
        state.update { it.copy(brightness = 1f, contrast = 1f, saturation = 1f) }
        clipId()?.let { id ->
            mutateClip(id) { copy(brightness = 1f, contrast = 1f, saturation = 1f) }
        }
    }

    fun selectVideoEffect(effect: VideoEffect) {
        state.update { it.copy(selectedEffect = effect) }
    }

    fun onPlaybackSpeedChanged(speed: Float) {
        state.update { it.copy(playbackSpeed = speed) }
    }

    /**
     * 2026-10-21 — write the SELECTED CLIP's base rotation, mirroring
     * [onBrightnessChanged] / [onContrastChanged] / [onSaturationChanged].
     *
     * The export reads the clip's own `rotation`
     * (`RenderGraphBuilder` → `normaliseRotation(rotation + transform.rotation)`),
     * so a rotation that only touched the global mirror never reached
     * the exported file. The base for the +90° step is the clip's own
     * rotation, so repeated taps accumulate instead of restarting from
     * a stale global value.
     */
    fun onRotationChanged(rotation: Int) {
        history.push()
        val v = com.aicreatorstudio.app.model.snapRotationToQuarterTurn(rotation)
        state.update { it.copy(rotation = v) }
        clipId()?.let { id -> mutateClip(id) { copy(rotation = v) } }
    }

    fun rotate90Degrees() {
        history.push()
        val current = state.value
        val clip = current.project?.clips?.firstOrNull { it.id == current.selectedClipId }
        val v = com.aicreatorstudio.app.model.nextQuarterTurnRotation(
            clipRotation = clip?.rotation,
            current = current.rotation
        )
        state.update { it.copy(rotation = v) }
        clipId()?.let { id -> mutateClip(id) { copy(rotation = v) } }
    }

    fun onTrimStartChanged(ms: Long) { state.update { it.copy(trimStartMs = ms) } }
    fun onTrimEndChanged(ms: Long) { state.update { it.copy(trimEndMs = ms) } }
    fun toggleTrimMode() { state.update { it.copy(isTrimMode = !it.isTrimMode) } }

    private fun clipId() = state.value.selectedClipId

    private fun mutateClip(clipId: String, transform: VideoClip.() -> VideoClip) {
        state.update { s ->
            val p = s.project ?: return@update s
            s.copy(project = p.copy(clips = p.clips.map { c ->
                if (c.id == clipId) c.transform() else c
            }))
        }
    }
}
