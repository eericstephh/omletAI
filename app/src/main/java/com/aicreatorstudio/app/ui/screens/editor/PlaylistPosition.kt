package com.aicreatorstudio.app.ui.screens.editor

import com.aicreatorstudio.app.model.VideoClip

/**
 * Pure mapping between the **editor timeline** (the domain every
 * downstream consumer — subtitle selection, split, audio-stem sync,
 * persistence — already assumes) and the **preview playlist** (the flat
 * ExoPlayer item list that drives multi-clip playback).
 *
 * 2026-10-04 — the preview used to play a single clip and report the
 * player's clip-local position as the playhead. Every consumer that
 * expected editor-timeline time (subtitles stamped at the playhead,
 * `splitClip`'s global position, `AudioMixerPlayer.syncTo`'s stem
 * starts) only worked by coincidence while the active clip sat at
 * timeline 0. The playlist preview makes the player the producer of a
 * proper editor-timeline clock; these two helpers own the conversion so
 * it can be unit-tested without an Android device.
 *
 * Domain decision: the editor timeline measures each clip by its
 * **source span** ([VideoClip.effectiveDurationMs]) — the same domain
 * `export/CompositionTimeline.outputMsForTimelineMs` assumes. Speed
 * changes playback rate, not block width; a ramped clip's block is
 * traversed faster by the playhead. Migrating the whole app to an
 * output-span (ramped) timeline would also require changing the
 * caption/audio composition mapping and is deliberately deferred.
 */
object PlaylistPosition {

    /**
     * Resolves an editor-timeline position to `(itemIndex, windowPositionMs)`
     * inside the preview playlist.
     *
     * `clips` must be the playback-ordered playlist (video clips sorted by
     * `timelineStartMs`; audio-only fallback clip last). The returned
     * window position is relative to the clip's **trimmed** window, which
     * is the coordinate space of `ExoPlayer.getCurrentPosition` /
     * `seekTo(index, positionMs)` when the item carries a
     * `ClippingConfiguration` for the trim.
     *
     * Positions inside a gap between clips resolve to the START of the
     * next clip — gaps are not reproduced in playback, matching the
     * export pipeline's documented end-to-end concatenation.
     */
    fun resolve(clips: List<VideoClip>, editorMs: Long): Pair<Int, Long> {
        if (clips.isEmpty()) return 0 to 0L
        val target = editorMs.coerceAtLeast(0L)
        for ((index, clip) in clips.withIndex()) {
            val start = clip.timelineStartMs.coerceAtLeast(0L)
            val span = clip.effectiveDurationMs.takeIf { it > 0 } ?: 1L
            if (target < start + span) {
                return index to (target - start).coerceIn(0L, span - 1)
            }
        }
        // Past the end of every clip: park on the last clip's final
        // window position (which is also STATE_ENDED territory).
        val last = clips.last()
        val lastSpan = last.effectiveDurationMs.takeIf { it > 0 } ?: 1L
        return clips.lastIndex to (lastSpan - 1).coerceAtLeast(0L)
    }

    /** Editor-timeline position of a playlist item's window position. */
    fun editorMs(clips: List<VideoClip>, itemIndex: Int, windowPositionMs: Long): Long {
        val clip = clips.getOrNull(itemIndex) ?: return windowPositionMs.coerceAtLeast(0L)
        return clip.timelineStartMs.coerceAtLeast(0L) + windowPositionMs.coerceAtLeast(0L)
    }

    /** Total editor-timeline span of the playlist. */
    fun totalMs(clips: List<VideoClip>): Long =
        clips.maxOfOrNull { clip ->
            val span = clip.effectiveDurationMs.takeIf { it > 0 } ?: 0L
            clip.timelineStartMs.coerceAtLeast(0L) + span
        } ?: 0L
}
