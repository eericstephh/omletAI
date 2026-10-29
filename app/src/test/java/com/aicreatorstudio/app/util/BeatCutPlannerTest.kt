package com.aicreatorstudio.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class BeatCutPlannerTest {

    @Test
    fun `keeps only cuts strictly inside the clip span`() {
        val cuts = BeatCutPlanner.planCuts(
            markersTimelineMs = listOf(500L, 2_000L, 5_000L, 9_000L),
            clipStartMs = 1_000L,
            clipEndMs = 8_000L
        )
        // 500 (before) and 9_000 (after) are outside; 1_000 and 8_000 would
        // be boundary no-ops — none of them appear.
        assertEquals(listOf(5_000L, 2_000L), cuts)
    }

    @Test
    fun `boundary positions are excluded`() {
        val cuts = BeatCutPlanner.planCuts(
            markersTimelineMs = listOf(1_000L, 4_000L, 8_000L),
            clipStartMs = 1_000L,
            clipEndMs = 8_000L
        )
        assertEquals(listOf(4_000L), cuts)
    }

    @Test
    fun `duplicates collapse and order is descending`() {
        val cuts = BeatCutPlanner.planCuts(
            markersTimelineMs = listOf(6_000L, 2_000L, 6_000L, 4_000L, 2_000L),
            clipStartMs = 0L,
            clipEndMs = 10_000L
        )
        assertEquals(listOf(6_000L, 4_000L, 2_000L), cuts)
    }

    @Test
    fun `empty markers or degenerate clip yield no cuts`() {
        assertEquals(emptyList<Long>(), BeatCutPlanner.planCuts(emptyList(), 0L, 5_000L))
        assertEquals(emptyList<Long>(), BeatCutPlanner.planCuts(listOf(1_000L), 3_000L, 3_000L))
        assertEquals(emptyList<Long>(), BeatCutPlanner.planCuts(listOf(1_000L), 5_000L, 1_000L))
    }
}
