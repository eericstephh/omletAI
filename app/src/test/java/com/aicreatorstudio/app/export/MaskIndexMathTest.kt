package com.aicreatorstudio.app.export

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 2026-10-19 — pins the mask-sidecar lookup that replaced a per-frame
 * directory listing.
 *
 * The export pre-renderer asks for a mask once per output frame; the old
 * `nearestTimestampPng` re-listed and re-sorted the sidecar on every call.
 * The binary search below must return exactly what the linear scan did:
 * the first timestamp at or after the requested frame, clamped to the last
 * mask when the frame runs past the final sample.
 */
class MaskIndexMathTest {

    private val stamps = longArrayOf(0L, 100L, 200L, 300L)

    @Test
    fun `empty index has no answer`() {
        assertEquals(-1, MaskIndexMath.nearestIndex(LongArray(0), 0L))
        assertEquals(-1, MaskIndexMath.nearestIndex(LongArray(0), 999L))
    }

    @Test
    fun `exact timestamp returns its own mask`() {
        assertEquals(0, MaskIndexMath.nearestIndex(stamps, 0L))
        assertEquals(2, MaskIndexMath.nearestIndex(stamps, 200L))
        assertEquals(3, MaskIndexMath.nearestIndex(stamps, 300L))
    }

    @Test
    fun `a frame between two masks takes the later one`() {
        assertEquals(1, MaskIndexMath.nearestIndex(stamps, 1L))
        assertEquals(1, MaskIndexMath.nearestIndex(stamps, 99L))
        assertEquals(2, MaskIndexMath.nearestIndex(stamps, 101L))
        assertEquals(3, MaskIndexMath.nearestIndex(stamps, 250L))
    }

    @Test
    fun `frames before the first and after the last mask clamp`() {
        assertEquals(0, MaskIndexMath.nearestIndex(stamps, -5_000L))
        assertEquals(3, MaskIndexMath.nearestIndex(stamps, 10_000L))
    }

    @Test
    fun `duplicate timestamps still resolve to a matching mask`() {
        val dup = longArrayOf(0L, 100L, 100L, 100L, 400L)
        val idx = MaskIndexMath.nearestIndex(dup, 100L)
        assertEquals(100L, dup[idx])
        // And a frame past them moves on to the next distinct stamp.
        assertEquals(4, MaskIndexMath.nearestIndex(dup, 101L))
    }

    @Test
    fun `every target maps onto a real mask index`() {
        // Sweep the whole span: no target may fall outside the array.
        for (t in -100L..600L step 7) {
            val idx = MaskIndexMath.nearestIndex(stamps, t)
            assert(idx in stamps.indices) { "target=$t produced idx=$idx" }
            if (t <= stamps.last()) {
                assert(stamps[idx] >= t) { "target=$t picked earlier mask ${stamps[idx]}" }
                if (idx > 0) {
                    assert(stamps[idx - 1] < t) { "target=$t could have picked ${stamps[idx - 1]}" }
                }
            }
        }
    }
}
