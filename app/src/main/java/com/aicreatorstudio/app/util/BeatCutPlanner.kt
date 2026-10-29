package com.aicreatorstudio.app.util

/**
 * Pure cut planner for the beat-synced auto-split (2026-10-05).
 *
 * Extracted verbatim from `EditorViewModel.autoSplitOnBeats` so the
 * selection rules are unit-testable without constructing the ViewModel
 * (whose constructor needs Android context + Hilt graph).
 *
 * Rules (unchanged behaviour):
 *  - only cuts STRICTLY inside `(clipStartMs, clipEndMs)` — the clip's own
 *    boundaries already exist and splitting at them is a no-op;
 *  - duplicates removed (two audio clips can emit the same instant);
 *  - returned DESCENDING, because the caller applies cuts last-to-first so
 *    every position stays valid while the clip list re-numbers under it.
 */
object BeatCutPlanner {

    /**
     * @param markersTimelineMs beat markers in TIMELINE time, any order.
     * @param clipStartMs clip start on the timeline (>= 0).
     * @param clipEndMs clip end on the timeline (start + effective span).
     */
    fun planCuts(
        markersTimelineMs: List<Long>,
        clipStartMs: Long,
        clipEndMs: Long
    ): List<Long> {
        if (markersTimelineMs.isEmpty() || clipEndMs <= clipStartMs) return emptyList()
        return markersTimelineMs
            .filter { it > clipStartMs && it < clipEndMs }
            .distinct()
            .sortedDescending()
    }
}
