package com.aicreatorstudio.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-09-19 — the single dynamic burn-in overlay may only draw the
 * caption(s) whose window contains the frame time. These tests lock
 * the frame→caption lookup, including the exact-boundary rule that
 * keeps adjacent 3-word chunks from ever double-drawing.
 */
class SubtitleOverlaySchedulerTest {

    @Test
    fun `isActive uses half-open windows`() {
        assertFalse(SubtitleOverlayScheduler.isActive(1000L, 2000L, 999L))
        assertTrue(SubtitleOverlayScheduler.isActive(1000L, 2000L, 1000L))
        assertTrue(SubtitleOverlayScheduler.isActive(1000L, 2000L, 1999L))
        assertFalse(SubtitleOverlayScheduler.isActive(1000L, 2000L, 2000L))
    }

    @Test
    fun `boundary instant belongs to the next caption only`() {
        val windows = listOf(0L to 1000L, 1000L to 2000L, 2000L to 3000L)
        assertEquals(listOf(0), SubtitleOverlayScheduler.activeIndices(windows, 0L))
        assertEquals(listOf(0), SubtitleOverlayScheduler.activeIndices(windows, 999L))
        // Exact boundary: only the next caption draws — never both.
        assertEquals(listOf(1), SubtitleOverlayScheduler.activeIndices(windows, 1000L))
        assertEquals(listOf(2), SubtitleOverlayScheduler.activeIndices(windows, 2000L))
        assertEquals(emptyList<Int>(), SubtitleOverlayScheduler.activeIndices(windows, 3000L))
    }

    @Test
    fun `overlapping captions all draw in speech order`() {
        val windows = listOf(0L to 2000L, 1000L to 3000L)
        assertEquals(listOf(0), SubtitleOverlayScheduler.activeIndices(windows, 500L))
        assertEquals(listOf(0, 1), SubtitleOverlayScheduler.activeIndices(windows, 1500L))
        assertEquals(listOf(1), SubtitleOverlayScheduler.activeIndices(windows, 2500L))
    }

    @Test
    fun `empty and degenerate windows select nothing`() {
        assertEquals(
            emptyList<Int>(),
            SubtitleOverlayScheduler.activeIndices(emptyList(), 1000L)
        )
        // Degenerate (end <= start) windows never activate.
        assertEquals(
            emptyList<Int>(),
            SubtitleOverlayScheduler.activeIndices(listOf(2000L to 1000L), 1500L)
        )
    }

    @Test
    fun `hundreds of chunk windows resolve to exactly one caption`() {
        // A 10-minute video at ~2s per 3-word caption: every frame
        // must pick exactly one renderer while the single dynamic
        // overlay keeps Media3's 15-overlay ceiling untouched.
        val windows = (0 until 300).map { i ->
            (i * 2000L) to ((i + 1) * 2000L)
        }
        for (t in listOf(0L, 1L, 1999L, 2000L, 123456L, 599999L)) {
            val active = SubtitleOverlayScheduler.activeIndices(windows, t)
            assertEquals(1, active.size)
            assertEquals((t / 2000L).toInt(), active[0])
        }
        assertEquals(
            emptyList<Int>(),
            SubtitleOverlayScheduler.activeIndices(windows, 600000L)
        )
    }
}
