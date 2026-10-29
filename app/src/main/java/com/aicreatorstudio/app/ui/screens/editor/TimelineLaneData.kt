package com.aicreatorstudio.app.ui.screens.editor

import com.aicreatorstudio.app.model.AudioClip
import com.aicreatorstudio.app.model.TrackKind
import com.aicreatorstudio.app.model.VideoClip

/**
 * 2026-09-06 — snapshot of everything the timeline overlay needs to
 * resolve a TAP into a selectable object (video clip / audio clip /
 * text overlay), without relying on pointer pass-through to the tiles
 * underneath the overlay.
 */
data class TimelineLaneData(
    val videoClips: List<VideoClip> = emptyList(),
    val musicClips: List<VideoClip> = emptyList(),
    val sfxClips: List<VideoClip> = emptyList(),
    val audioClips: List<AudioClip> = emptyList(),
    val textOverlays: List<com.aicreatorstudio.app.model.TextOverlay> = emptyList(),
    val selectedAudioClipId: String? = null,
    val selectedClipId: String? = null,
    val selectedTextId: String? = null
)

/**
 * A resolved tap target on the timeline:
 * [rowKind] is which lane was tapped; [id] is the object under the
 * finger (null = empty lane — deselect).
 */
data class TimelineObjectRef(
    val rowKind: TrackKind,
    val id: String?
)
