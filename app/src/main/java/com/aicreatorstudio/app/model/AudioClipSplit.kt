package com.aicreatorstudio.app.model

import java.util.UUID

/**
 * 2026-10-19 — split an [AudioClip] at a timeline position.
 *
 * ## Why this exists
 *
 * The editor's «برش» (Split) action only ever touched `project.clips`
 * ([VideoClip]). Cutting a video left its soundtrack — the paired
 * [AudioClip] on the "Video Audio" lane — as one long tile spanning both
 * halves, so the two lanes disagreed about where the cut was and the
 * per-piece volume / mute / fades could not address the second half.
 *
 * Splitting an audio object is not the same operation as splitting a
 * video clip: an [AudioClip] has no trim pair, it has a timeline
 * `startTimeMs` + `durationMs` window and a single source read offset
 * ([AudioClip.clipOffsetMs]). The right half therefore has to advance
 * `clipOffsetMs` by exactly the length of the left half, or it would
 * replay the beginning of the file.
 *
 * Kept as a pure function (no ViewModel, no Android types) so the
 * arithmetic is unit-testable in the JVM source set — the same reason
 * [SpeedCurveInterpolator] and [SubtitleEditOps] live here.
 *
 * ## What is preserved and what is reset
 *
 * - `volume`, `muted`, `voiceEffect`, `cachedPcmPath`, `beatData`,
 *   `sourceUri`, `fileName`, `sourceStartMs` / `sourceEndMs` are carried
 *   onto both halves unchanged. They describe the *source*, not the span.
 * - `beatData` stays on both halves on purpose: markers are stored in
 *   **source** time, and the renderer filters them through
 *   `clipOffsetMs .. clipOffsetMs + durationMs`, so each half paints only
 *   its own ticks without any re-analysis.
 * - `fadeInMs` stays on the left half only and `fadeOutMs` on the right
 *   half only. A cut must never introduce a fade at the join — that would
 *   make the two pieces audibly dip exactly where the user cut.
 * - `originVideoClipId` of the right half is retargeted when the caller
 *   splits the owning video too (see [rightOriginVideoClipId]); leaving
 *   both halves pointing at the same video would break the
 *   one-audio-object-per-video invariant that
 *   `seedVideoAudioClipsIfNeeded` relies on and would seed a duplicate
 *   object on the next project load.
 */
object AudioClipSplit {

    /**
     * Split [clip] at the **global timeline** position [atTimelineMs].
     *
     * @param atTimelineMs playhead position, not clip-local and not source
     *   time. Must fall strictly inside `startTimeMs until endTimeMs`.
     * @param rightHalfId id for the right piece. Injectable so tests can
     *   assert on a stable value.
     * @param rightOriginVideoClipId `originVideoClipId` for the right
     *   piece — the id of the second video clip when the caller split a
     *   paired video, otherwise the original value.
     * @return the (left, right) pair, or `null` when the position is on or
     *   outside an edge. A cut exactly on an edge would produce a
     *   zero-length piece, which is never what the user meant.
     */
    fun split(
        clip: AudioClip,
        atTimelineMs: Long,
        rightHalfId: String = UUID.randomUUID().toString(),
        rightOriginVideoClipId: String? = clip.originVideoClipId
    ): Pair<AudioClip, AudioClip>? {
        val start = clip.startTimeMs.coerceAtLeast(0L)
        val localMs = atTimelineMs - start
        if (localMs <= 0L || localMs >= clip.durationMs) return null

        val left = clip.copy(
            // The left half keeps the original id and its own fade-in; the
            // cut point must not fade out.
            durationMs = localMs,
            fadeOutMs = 0L
        )
        val right = clip.copy(
            id = rightHalfId,
            startTimeMs = start + localMs,
            durationMs = clip.durationMs - localMs,
            // The right half starts reading where the left half stopped.
            clipOffsetMs = clip.clipOffsetMs + localMs,
            fadeInMs = 0L,
            originVideoClipId = rightOriginVideoClipId
        )
        return left to right
    }
}
