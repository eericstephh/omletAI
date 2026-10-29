package com.aicreatorstudio.app.viewmodel

import androidx.compose.runtime.Stable
import com.aicreatorstudio.app.model.EditorUiState
import com.aicreatorstudio.app.model.TransitionConfig
import com.aicreatorstudio.app.model.TransitionScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Manages clip transition (In/Out/Both) configuration. Each clip on the
 * timeline can carry an entering and/or leaving transition stored as
 * [VideoClip.transitionIn] / [VideoClip.transitionOut] (encoded JSON
 * strings via [TransitionConfig.encode]).
 */
@Stable
class TransitionDelegate(
    private val state: MutableStateFlow<EditorUiState>,
    private val history: HistoryDelegate
) {
    /**
     * Apply (or remove) a transition on a clip.
     * @param clipId  target clip
     * @param scope   IN / OUT / BOTH
     * @param config  the transition config, or `null` to remove it
     */
    fun setClipTransition(
        clipId: String,
        scope: TransitionScope,
        config: TransitionConfig?
    ) {
        history.push()
        val encoded = config?.encode()
        state.update { s ->
            val p = s.project ?: return@update s
            s.copy(project = p.copy(clips = p.clips.map { clip ->
                if (clip.id != clipId) clip else when (scope) {
                    TransitionScope.IN  -> clip.copy(transitionIn = encoded)
                    TransitionScope.OUT -> clip.copy(transitionOut = encoded)
                    TransitionScope.BOTH -> clip.copy(transitionIn = encoded, transitionOut = encoded)
                }
            }))
        }
    }
}
