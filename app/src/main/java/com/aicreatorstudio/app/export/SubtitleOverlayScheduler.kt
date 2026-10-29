package com.aicreatorstudio.app.export

/**
 * 2026-09-19 — pure scheduler for the single dynamic subtitle
 * overlay.
 *
 * Media3's `OverlayShaderProgram` rejects more than 15 SDR
 * overlays in one `OverlayEffect` instance, so the burn-in can no
 * longer mint one `BitmapOverlay` per caption (3-word chunking
 * routinely produces dozens). Instead ALL captions feed ONE
 * dynamic overlay which, per frame, renders only the caption(s)
 * whose `[startUs, endUs)` window contains `presentationTimeUs`.
 *
 * This object is the frame→caption lookup: pure Kotlin, no
 * Android types, JVM unit-tested. The GL-thread renderer
 * ([DynamicSubtitleOverlay]) only draws what this selects.
 */
object SubtitleOverlayScheduler {

    /** True when [presentationTimeUs] falls inside `[startUs, endUs)`. */
    fun isActive(startUs: Long, endUs: Long, presentationTimeUs: Long): Boolean =
        presentationTimeUs in startUs until endUs.coerceAtLeast(startUs)

    /**
     * Indices of [windows] active at [presentationTimeUs], in list
     * order (speech order — the composite draws them in this order
     * so a later caption paints over an earlier one on overlap).
     *
     * Each window is `startUs to endUs` (half-open `[start, end)`).
     * Adjacent chunks share an exact boundary instant (`end == next
     * start`); the half-open rule assigns that instant to the NEXT
     * caption only, so no frame ever draws both.
     */
    fun activeIndices(
        windows: List<Pair<Long, Long>>,
        presentationTimeUs: Long
    ): List<Int> {
        if (windows.isEmpty()) return emptyList()
        // Linear scan: O(n) Long compares per frame — negligible
        // next to a single bitmap render, and obviously correct
        // with overlaps (unlike a binary search).
        val out = ArrayList<Int>(2)
        for (i in windows.indices) {
            val (startUs, endUs) = windows[i]
            if (isActive(startUs, endUs, presentationTimeUs)) out.add(i)
        }
        return out
    }
}
