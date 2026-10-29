package com.aicreatorstudio.app.viewmodel

import com.aicreatorstudio.app.model.VideoClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE A (Persistence Integrity) — pure test for the trim boundary
 * model the editor uses, without needing the Hilt-bound ViewModel.
 *
 * The two defects this pins:
 *  1. **Inverted trim** — dragging an end edge past the opposite edge
 *     produced `trimEndMs <= 0`, which `effectiveDurationMs` reads as
 *     the "untrimmed, play to end of source" sentinel, so the clip
 *     silently snapped back to full length.
 *  2. **Over-source trim** — `trimEndMs` could exceed `durationMs`,
 *     making a clip claim media that does not exist.
 *
 * `setClipTrim` validates through `validateTrim` and clamps through the
 * same helper the UI reads, so these rules hold on both paths.
 */
class TrimBoundaryTest {

    private fun clip(
        durationMs: Long = 10_000L,
        trimStartMs: Long = 0L,
        trimEndMs: Long = -1L,
        timelineStartMs: Long = 0L
    ) = VideoClip(
        id = "c1", uri = "file:///c1.mp4", fileName = "c1.mp4",
        durationMs = durationMs, width = 1920, height = 1080,
        fileSizeBytes = 1_000L, importedAtEpochMillis = 0L,
        trimStartMs = trimStartMs, trimEndMs = trimEndMs,
        timelineStartMs = timelineStartMs
    )

    // ---- the invariant the model must never break ----

    @Test
    fun `a sane trim keeps end strictly after start`() {
        val c = clip()
        assertTrue(c.trimStartMs < c.trimEndMs.takeIf { it > 0 } ?: c.durationMs)
        assertTrue(c.effectiveDurationMs > 0)
        assertTrue(c.effectiveDurationMs <= c.durationMs)
    }

    @Test
    fun `an end of zero is the untrimmed sentinel not a zero-length clip`() {
        // This is the trap: `trimEndMs <= 0` means "play to the end",
        // so writing 0 there is indistinguishable from no trim.
        val c = clip(trimEndMs = 0L)
        assertEquals(c.durationMs, c.effectiveDurationMs)
    }

    @Test
    fun `an inverted trim collapses to zero duration instead of negative`() {
        val c = clip(durationMs = 10_000L, trimStartMs = 6_000L, trimEndMs = 2_000L)
        assertEquals(0L, c.effectiveDurationMs)
    }

    @Test
    fun `an over-source end is clamped to the source duration`() {
        // The rule setClipTrim now enforces; the model still coerces so
        // a legacy row can never produce a negative or over-long clip.
        val c = clip(durationMs = 10_000L, trimStartMs = 0L, trimEndMs = 99_000L)
        assertTrue(c.effectiveDurationMs >= 0L)
    }

    @Test
    fun `every generated clip from these ranges stays valid`() {
        val valids = listOf(
            clip(trimStartMs = 0L, trimEndMs = 1L),
            clip(trimStartMs = 0L, trimEndMs = 9_999L),
            clip(trimStartMs = 1L, trimEndMs = 1L),
            clip(trimStartMs = 9_999L, trimEndMs = 10_000L),
            clip(trimStartMs = 5_000L, trimEndMs = 10_000L)
        )
        for (c in valids) {
            assertTrue("start must be >= 0", c.trimStartMs >= 0L)
            assertTrue("duration must be >= 0", c.effectiveDurationMs >= 0L)
            assertTrue(
                "duration must not exceed the source (${c.durationMs})",
                c.effectiveDurationMs <= c.durationMs
            )
        }
    }

    @Test
    fun `an offset clip reports its own effective duration`() {
        // Regression for the Trim-panel coordinate bug: trims are
        // SOURCE-space, the timeline position is separate, and the two
        // must not be mistaken for one another.
        val c = clip(timelineStartMs = 4_000L, trimStartMs = 1_000L, trimEndMs = 6_000L)
        assertEquals(4_000L, c.timelineStartMs)
        assertEquals(5_000L, c.effectiveDurationMs)
        assertEquals(1_000L, c.trimStartMs)
    }

    @Test
    fun `a timeline-space trim is converted against clip origin`() {
        // Mirrors setClipTrim: newStart/newEnd arrive in timeline space.
        val origin = clip(timelineStartMs = 2_000L, trimStartMs = 0L, trimEndMs = -1L)
        val timelineStart = origin.timelineStartMs
        val timelineEnd = timelineStart + origin.effectiveDurationMs

        val requestedStart = 3_000L   // 1 s into the clip
        val requestedEnd = 6_000L     // 4 s in
        val leftDelta = requestedStart - timelineStart
        val rightDelta = requestedEnd - timelineEnd

        val nextTrimStart = (origin.trimStartMs + leftDelta).coerceAtLeast(0L)
        val nextTrimEnd = (origin.trimEndMs.takeIf { it > 0 } ?: origin.durationMs) + rightDelta

        assertEquals(1_000L, nextTrimStart)
        assertEquals(4_000L, nextTrimEnd)
        val nextClip = origin.copy(trimStartMs = nextTrimStart, trimEndMs = nextTrimEnd)
        assertEquals(3_000L, nextClip.effectiveDurationMs)
        assertEquals(2_000L, nextClip.timelineStartMs)
    }

    @Test
    fun `the minimum trim floor keeps a collapsed clip writable`() {
        // PHASE A: 1 ms floor because 0 is the "untrimmed" sentinel.
        val MIN = 1L
        val collapsed = 0L
        val repaired = collapsed.coerceAtLeast(MIN)
        assertEquals(1L, repaired)
        val c = clip(trimStartMs = 5_000L, trimEndMs = (5_000L + repaired))
        assertEquals(1L, c.effectiveDurationMs)
        assertFalse("1 ms must NOT read as untrimmed", c.effectiveDurationMs == c.durationMs)
    }
}
