package com.aicreatorstudio.app.export

/**
 * Pure lookup maths for the Background Remove mask sidecar.
 *
 * ## Why this is a separate, tested function
 *
 * The export pre-renderer asks for a mask **once per output frame** — a
 * 60 s 30 fps clip is 1 800 lookups. `MattingSidecar.nearestTimestampPng`
 * answers each one by listing the sidecar directory and sorting every PNG
 * name, i.e. ~600 file stats + a sort, 1 800 times. That dominated export
 * time for any clip that used Background Remove.
 *
 * The fix is to build the sorted timestamp list once and binary-search it.
 * The search itself is this file: no Android types, so a JVM test can pin
 * the boundary behaviour that matters (first mask, last mask, exact hit,
 * between two masks, empty index).
 */
object MaskIndexMath {

    /**
     * Index of the first timestamp `>= target`, clamped into range.
     *
     * Picking the *next* mask (rather than the previous one) matches the
     * behaviour the export path had with `nearestTimestampPng`: the mask
     * produced at or after the frame is the one computed from a picture at
     * least as recent as the frame. Returns `-1` only for an empty index.
     *
     * @param stamps ascending mask timestamps in ms (may contain duplicates).
     * @param target frame timestamp in ms; negative values clamp to index 0.
     */
    fun nearestIndex(stamps: LongArray, target: Long): Int {
        if (stamps.isEmpty()) return -1
        var lo = 0
        var hi = stamps.size - 1
        var ans = stamps.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (stamps[mid] >= target) {
                ans = mid
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        return ans
    }
}
