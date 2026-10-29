package com.aicreatorstudio.app.ui.screens.editor

/**
 * 2026-09-06 playhead overhaul — pure throttle / settle reducers used
 * by the shared scrub handlers in `EditorScreen`.
 *
 * The old scrub pipeline seeked ExoPlayer on every drag frame
 * (60+ seeks/sec, each switching SeekParameters to EXACT), pushed
 * every frame into the ViewModel StateFlow (two full editor
 * recompositions per frame: set + consume), and let the 33 ms poller
 * race the final seek on release — the playhead jumped backwards and
 * dragging stuttered. These helpers encode the throttled pipeline as
 * pure logic so the behavior is testable without Compose or ExoPlayer.
 */
object PlayheadScrubThrottle {

    /**
     * Should this drag frame issue an ExoPlayer seek?
     *
     * @param nowMs current monotonic clock ms.
     * @param lastSeekAtMs clock ms of the last issued seek.
     * @param targetMs the frame's requested position.
     * @param lastTargetMs the previous frame's requested position.
     * @param minIntervalMs [EditorLayoutSpec.SCRUB_SEEK_INTERVAL_MS].
     * @return true when enough time has passed since the last seek
     *   AND the target actually moved. An unchanged target never
     *   re-seeks (sub-pixel deltas round to the same ms constantly).
     */
    fun shouldSeek(
        nowMs: Long,
        lastSeekAtMs: Long,
        targetMs: Long,
        lastTargetMs: Long,
        minIntervalMs: Long
    ): Boolean {
        if (targetMs == lastTargetMs) return false
        return nowMs - lastSeekAtMs >= minIntervalMs
    }

    /**
     * Should this drag frame push the position into the ViewModel?
     * Same shape as [shouldSeek] but with its own (slower) cadence —
     * each push recomposes the editor, so it runs at ~10 Hz and the
     * final value is always pushed synchronously on release.
     */
    fun shouldPushViewModel(
        nowMs: Long,
        lastPushAtMs: Long,
        targetMs: Long,
        lastPushedMs: Long,
        minIntervalMs: Long
    ): Boolean {
        if (targetMs == lastPushedMs) return false
        return nowMs - lastPushAtMs >= minIntervalMs
    }

    /**
     * Should the 33 ms poller adopt the player's current position?
     *
     * The poller skips while the user is holding the playhead (the
     * finger is the source of truth) AND while a post-release EXACT
     * seek is still settling — adopting the player's stale position
     * before the seek completes is the "playhead jumps back after
     * release" bug. While the settle gate is held, the player's
     * reading is trusted only once it has landed within
     * [settleToleranceMs] of the requested target.
     *
     * @param isScrubbing user is holding the playhead.
     * @param awaitingSettle a release-seek is still in flight.
     * @param playerPositionMs the player's current position.
     * @param lastRequestedMs the last position the user requested.
     * @param settleToleranceMs how close the player must be to the
     *   target before its reading is trusted again.
     */
    fun shouldAdoptPlayerPosition(
        isScrubbing: Boolean,
        awaitingSettle: Boolean,
        playerPositionMs: Long,
        lastRequestedMs: Long,
        settleToleranceMs: Long
    ): Boolean {
        if (isScrubbing) return false
        if (awaitingSettle) {
            // The player already landed on the target — settle now.
            return kotlin.math.abs(playerPositionMs - lastRequestedMs) <=
                settleToleranceMs
        }
        return true
    }
}
