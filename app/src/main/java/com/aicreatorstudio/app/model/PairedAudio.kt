package com.aicreatorstudio.app.model

/**
 * The **paired-audio mirror** — the one place that defines how a
 * `VideoClip` and the `AudioClip` that represents its own soundtrack
 * must agree.
 *
 * ## Why this exists
 *
 * `VideoProject` holds two lists. `clips` is the timeline: it carries
 * the video clips *and* the audio-only ones (music, sfx, voiceover).
 * `audioClips` is the "Video Audio" lane — one [AudioClip] per video
 * clip, keyed by [AudioClip.originVideoClipId].
 *
 * The two representations of the same soundtrack are read by different
 * code and they drifted repeatedly, always in the same shape: a mutator
 * updated one side and forgot the other. `deleteClip` did it (the tile
 * outlived its clip), `splitClip` did it, `duplicateClip` did it. The
 * invariant was a comment in `MEMORY.md`, which is not enforceable.
 *
 * So it is a function now, and both directions are named:
 *
 *  - [mirrorGeometry] — **video → audio.** After any edit that moves,
 *    trims or resizes a video clip, the paired tile must occupy exactly
 *    the same window.
 *  - [volumeForVideo] / [mutedForVideo] — **audio → video.** The volume
 *    sheet binds to the [AudioClip] when the Video Audio tile is
 *    selected, but the preview mixer and the export both read
 *    `VideoClip.volume` (via `RenderSegment.volume`). Without a write-
 *    through, dragging that slider changed the tile and *nothing else*
 *    — a silent no-op in both the preview and the file.
 *
 * Everything here is pure and takes the values it needs, so it is
 * JVM-testable without a ViewModel (see `PairedAudioMirrorTest`).
 */
object PairedAudio {

    /** True when [audio] represents some video clip's own soundtrack. */
    fun isPaired(audio: AudioClip): Boolean = audio.originVideoClipId != null

    /**
     * Video → audio: make [audio] occupy exactly [video]'s timeline
     * window and read the same part of the source.
     *
     * - `startTimeMs` ← `timelineStartMs`, so the tile never drifts
     *   left/right of the clip above it.
     * - `clipOffsetMs` ← `trimStartMs`, because both mean "where inside
     *   the source this object starts". `seedVideoAudioClipsIfNeeded`
     *   leaves this at 0 and never revisits it, so a clip imported and
     *   then trimmed kept a tile that played from the untrimmed head.
     * - `durationMs` ← `effectiveDurationMs`, matching the length the
     *   timeline itself draws (`EditorTimeline` uses the same field).
     *
     * Returns [audio] unchanged when it is not paired — a standalone
     * music/SFX object is positioned by the user, not by a video clip.
     */
    fun mirrorGeometry(audio: AudioClip, video: VideoClip): AudioClip {
        if (!isPaired(audio)) return audio
        return audio.copy(
            startTimeMs = video.timelineStartMs.coerceAtLeast(0L),
            clipOffsetMs = video.trimStartMs.coerceAtLeast(0L),
            durationMs = video.effectiveDurationMs.coerceAtLeast(1L)
        )
    }

    /**
     * Audio → video: the gain the paired object applies to the video's
     * own soundtrack, or `null` when there is nothing to write through.
     *
     * `muted` wins over the remembered level, so unmuting restores the
     * volume the user had rather than snapping to unity.
     */
    fun volumeForVideo(audio: AudioClip): Float? {
        if (!isPaired(audio)) return null
        if (audio.muted) return 0f
        return audio.volume.coerceIn(0f, 2f)
    }

    /**
     * Video → audio: the tile's volume after the *video* clip's volume
     * changed. Returns [audio] unchanged when unpaired.
     *
     * The tile never keeps its own `muted` flag against the video: if
     * the video is at 0 the tile is muted, otherwise it follows the
     * level. `muted` is cleared so the two cannot disagree.
     */
    fun withVideoVolume(audio: AudioClip, videoVolume: Float): AudioClip {
        if (!isPaired(audio)) return audio
        val v = videoVolume.coerceIn(0f, 2f)
        return audio.copy(volume = v, muted = v == 0f)
    }
}
