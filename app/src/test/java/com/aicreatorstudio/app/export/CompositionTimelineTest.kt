package com.aicreatorstudio.app.export

import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.model.VideoProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CompositionTimeline] — the segment layout of the
 * *exported* file, which differs from the editor's timeline because
 * segments are concatenated end-to-end.
 *
 * Regression guard for the 2026-09-16 multi-clip export fix: the engine
 * used to render only `graph.segments.first()`, and caption timings were
 * rebased against the clip's raw `timelineStartMs`. Both were wrong as
 * soon as a project had more than one clip.
 *
 * These are pure functions by design — `Media3TransformerExportEngine`
 * needs a `Context` and cannot be constructed in a JVM unit test.
 */
class CompositionTimelineTest {

    // ---- fixtures ----

    private fun seg(
        timelineStartMs: Long,
        sourceStart: Long = 0L,
        sourceEnd: Long = 1_000L,
        speed: Float = 1f,
        width: Int = 1920,
        height: Int = 1080
    ) = RenderSegment(
        sourceUri = "content://test/clip",
        sourceStartMs = sourceStart,
        sourceEndMs = sourceEnd,
        speed = speed,
        brightness = 1f,
        contrast = 1f,
        saturation = 1f,
        rotationDegrees = 0,
        displayName = "clip",
        timelineStartMs = timelineStartMs,
        sourceWidthPx = width,
        sourceHeightPx = height
    )

    // ---- origins ----

    @Test
    fun `empty input yields an empty layout`() {
        assertEquals(emptyList<Long>(), CompositionTimeline.originsMs(emptyList()))
        assertEquals(0L, CompositionTimeline.totalDurationMs(emptyList()))
    }

    @Test
    fun `a single segment always starts at zero`() {
        val origins = CompositionTimeline.originsMs(listOf(seg(timelineStartMs = 7_000)))
        assertEquals(listOf(0L), origins)
    }

    @Test
    fun `segments are laid end-to-end`() {
        val segments = listOf(
            seg(timelineStartMs = 0, sourceEnd = 3_000),
            seg(timelineStartMs = 5_000, sourceEnd = 4_000),
            seg(timelineStartMs = 12_000, sourceEnd = 2_000)
        )

        assertEquals(listOf(0L, 3_000L, 7_000L), CompositionTimeline.originsMs(segments))
        assertEquals(9_000L, CompositionTimeline.totalDurationMs(segments))
    }

    @Test
    fun `origins use post-speed durations`() {
        val segments = listOf(
            // 4_000 ms of source at 2x → 2_000 ms of output.
            seg(timelineStartMs = 0, sourceEnd = 4_000, speed = 2f),
            // 2_000 ms of source at 1x → 2_000 ms of output.
            seg(timelineStartMs = 4_000, sourceEnd = 2_000, speed = 1f)
        )

        assertEquals(listOf(0L, 2_000L), CompositionTimeline.originsMs(segments))
        assertEquals(4_000L, CompositionTimeline.totalDurationMs(segments))
    }

    @Test
    fun `a slow segment pushes later origins further out`() {
        val segments = listOf(
            // 4_000 ms of source at 0.5x → 8_000 ms of output.
            seg(timelineStartMs = 0, sourceEnd = 4_000, speed = 0.5f),
            seg(timelineStartMs = 4_000, sourceEnd = 1_000, speed = 1f)
        )

        assertEquals(listOf(0L, 8_000L), CompositionTimeline.originsMs(segments))
    }

    // ---- caption rebasing ----

    @Test
    fun `a single clip keeps the legacy caption rebasing`() {
        // Before multi-clip the engine passed the clip's raw
        // timelineStartMs. With one segment the origin is 0, so the
        // value must be unchanged — this is the backward-compat guard.
        val segment = seg(timelineStartMs = 7_000)
        val origins = CompositionTimeline.originsMs(listOf(segment))

        assertEquals(7_000L, CompositionTimeline.subtitleOriginMs(segment, origins[0]))
    }

    @Test
    fun `a caption on the second clip lands at its composition origin`() {
        val segments = listOf(
            seg(timelineStartMs = 0, sourceEnd = 3_000),
            seg(timelineStartMs = 5_000, sourceEnd = 4_000)
        )
        val origins = CompositionTimeline.originsMs(segments)

        val subtitleOrigin = CompositionTimeline.subtitleOriginMs(segments[1], origins[1])

        // The factory computes `overlay.startTimeMs - subtitleOrigin`.
        // A caption starting exactly when clip 2 starts (editor ms 5000)
        // must land on clip 2's composition origin (3000), not at 0.
        val captionStartEditorMs = 5_000L
        assertEquals(3_000L, captionStartEditorMs - subtitleOrigin)
    }

    @Test
    fun `captions stay inside their own clip after rebasing`() {
        val segments = listOf(
            seg(timelineStartMs = 0, sourceEnd = 3_000),
            seg(timelineStartMs = 5_000, sourceEnd = 4_000)
        )
        val origins = CompositionTimeline.originsMs(segments)
        val subtitleOrigin = CompositionTimeline.subtitleOriginMs(segments[1], origins[1])

        // A caption 1_500 ms into clip 2 (editor ms 6500) maps to
        // 3000 + 1500 = 4500 in the output.
        val captionStartEditorMs = 6_500L
        assertEquals(4_500L, captionStartEditorMs - subtitleOrigin)

        // ...and it must sit within clip 2's output window [3000, 7000).
        val mapped = captionStartEditorMs - subtitleOrigin
        val clipStart = origins[1]
        val clipEnd = clipStart + segments[1].outputDurationMs
        assertEquals(true, mapped in clipStart until clipEnd)
    }

    @Test
    fun `rebasing accounts for a sped-up preceding clip`() {
        val segments = listOf(
            // 4_000 ms of source at 2x → clip 2 starts at 2_000 in output.
            seg(timelineStartMs = 0, sourceEnd = 4_000, speed = 2f),
            seg(timelineStartMs = 4_000, sourceEnd = 1_000, speed = 1f)
        )
        val origins = CompositionTimeline.originsMs(segments)
        val subtitleOrigin = CompositionTimeline.subtitleOriginMs(segments[1], origins[1])

        assertEquals(2_000L, 4_000L - subtitleOrigin)
    }

    // ---- output clock -> clip-local time (2026-09-16, transitions) ----

    @Test
    fun `an unretimed clip maps output time one-to-one`() {
        assertEquals(
            1_000L,
            CompositionTimeline.clipLocalMsForOutput(outputTimeUs = 1_000_000L, speed = 1f)
        )
    }

    @Test
    fun `a sped-up clip reaches a given source frame sooner`() {
        // At 2x the output clock advances through the source twice as fast,
        // so one second of OUTPUT is two seconds of clip-local time.
        assertEquals(
            2_000L,
            CompositionTimeline.clipLocalMsForOutput(outputTimeUs = 1_000_000L, speed = 2f)
        )
    }

    @Test
    fun `a slowed-down clip reaches a given source frame later`() {
        assertEquals(
            500L,
            CompositionTimeline.clipLocalMsForOutput(outputTimeUs = 1_000_000L, speed = 0.5f)
        )
    }

    @Test
    fun `a zero or non-finite speed falls back to real time`() {
        // A NaN here would make every comparison inside the transition
        // engine false, silently dropping the transition from the export.
        // Falling back to 1x keeps the transition visible instead.
        assertEquals(
            1_000L,
            CompositionTimeline.clipLocalMsForOutput(outputTimeUs = 1_000_000L, speed = 0f)
        )
        assertEquals(
            1_000L,
            CompositionTimeline.clipLocalMsForOutput(outputTimeUs = 1_000_000L, speed = Float.NaN)
        )
        assertEquals(
            1_000L,
            CompositionTimeline.clipLocalMsForOutput(
                outputTimeUs = 1_000_000L,
                speed = Float.NEGATIVE_INFINITY
            )
        )
    }

    @Test
    fun `the segment overload reads the segment speed`() {
        val segment = seg(timelineStartMs = 0, sourceEnd = 4_000, speed = 2f)

        assertEquals(
            CompositionTimeline.clipLocalMsForOutput(outputTimeUs = 500_000L, speed = 2f),
            CompositionTimeline.clipLocalMsForOutput(segment, outputTimeUs = 500_000L)
        )
    }

    @Test
    fun `zero output time is zero clip-local time at any speed`() {
        for (speed in listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 4f)) {
            assertEquals(
                0L,
                CompositionTimeline.clipLocalMsForOutput(outputTimeUs = 0L, speed = speed)
            )
        }
    }

    // ---- editor timeline -> composition timeline ----
    //
    // Added 2026-09-16 for multi-track audio: audio-only clips keep their
    // editor positions, so they have to be mapped onto the composition
    // timeline the video segments define.

    @Test
    fun `an empty layout maps everything to zero`() {
        assertEquals(
            0L,
            CompositionTimeline.outputMsForTimelineMs(emptyList(), timelineMs = 5_000L)
        )
    }

    @Test
    fun `a single segment starting at zero maps one to one`() {
        val segments = listOf(seg(timelineStartMs = 0, sourceEnd = 4_000))
        for (ms in listOf(0L, 1_000L, 2_500L, 3_999L)) {
            assertEquals(
                "editor $ms should map to itself",
                ms,
                CompositionTimeline.outputMsForTimelineMs(segments, ms)
            )
        }
    }

    @Test
    fun `a clip offset on the editor timeline collapses onto the composition origin`() {
        // Segments are concatenated without gaps, so a clip the user placed
        // at 7 s in the editor still starts at 0 in the output.
        val segments = listOf(seg(timelineStartMs = 7_000, sourceEnd = 2_000))
        assertEquals(0L, CompositionTimeline.outputMsForTimelineMs(segments, 7_000L))
        assertEquals(1_000L, CompositionTimeline.outputMsForTimelineMs(segments, 8_000L))
    }

    @Test
    fun `later segments are offset by the output duration of the earlier ones`() {
        val segments = listOf(
            seg(timelineStartMs = 0, sourceEnd = 4_000),
            seg(timelineStartMs = 4_000, sourceEnd = 6_000)
        )
        // Second segment's origin is 4000 (the first one's output length).
        assertEquals(4_000L, CompositionTimeline.outputMsForTimelineMs(segments, 4_000L))
        assertEquals(5_000L, CompositionTimeline.outputMsForTimelineMs(segments, 5_000L))
        assertEquals(9_999L, CompositionTimeline.outputMsForTimelineMs(segments, 9_999L))
    }

    @Test
    fun `a speed-changed segment maps proportionally onto its shorter output span`() {
        // 4 s of source at 2x occupies only 2 s of output. A position halfway
        // through the clip must land halfway through the output segment, not
        // 2 s in (which would run past the segment's end).
        val segments = listOf(seg(timelineStartMs = 0, sourceEnd = 4_000, speed = 2f))
        assertEquals(0L, CompositionTimeline.outputMsForTimelineMs(segments, 0L))
        assertEquals(1_000L, CompositionTimeline.outputMsForTimelineMs(segments, 2_000L))
        assertEquals(1_999L, CompositionTimeline.outputMsForTimelineMs(segments, 3_999L))
    }

    @Test
    fun `the mapping is monotonic across a multi-segment layout`() {
        // Output lengths: 3000, then 5000 source at 2x = 2500, then 1000.
        // Total 6500.
        val segments = listOf(
            seg(timelineStartMs = 0, sourceEnd = 3_000),
            seg(timelineStartMs = 3_000, sourceEnd = 5_000, speed = 2f),
            seg(timelineStartMs = 9_000, sourceStart = 9_000, sourceEnd = 10_000)
        )
        assertEquals(6_500L, CompositionTimeline.totalDurationMs(segments))
        var previous = -1L
        var ms = 0L
        while (ms <= 12_000L) {
            val mapped = CompositionTimeline.outputMsForTimelineMs(segments, ms)
            assertTrue(
                "mapping must not go backwards (editor $ms -> $mapped, was $previous)",
                mapped >= previous
            )
            previous = mapped
            ms += 50L
        }
        assertEquals(6_500L, previous)
    }

    @Test
    fun `the result never exceeds the composition duration`() {
        val segments = listOf(
            seg(timelineStartMs = 1_000, sourceEnd = 2_000),
            seg(timelineStartMs = 5_000, sourceEnd = 3_000)
        )
        val total = CompositionTimeline.totalDurationMs(segments)
        assertEquals(5_000L, total)
        for (ms in listOf(-10_000L, 0L, 3_000L, 8_000L, 99_999L)) {
            val mapped = CompositionTimeline.outputMsForTimelineMs(segments, ms)
            assertTrue("mapped $mapped out of range for editor $ms", mapped in 0L..total)
        }
    }

    @Test
    fun `a position in a gap between clips collapses onto the next segment`() {
        // Clip 1: editor 0..2000. Clip 2: editor 5000..6000. The 2000..5000
        // gap is not reproduced, so anything inside it maps to clip 2's
        // origin in the output.
        val segments = listOf(
            seg(timelineStartMs = 0, sourceEnd = 2_000),
            seg(timelineStartMs = 5_000, sourceEnd = 1_000)
        )
        assertEquals(2_000L, CompositionTimeline.outputMsForTimelineMs(segments, 2_000L))
        assertEquals(2_000L, CompositionTimeline.outputMsForTimelineMs(segments, 3_500L))
        assertEquals(2_000L, CompositionTimeline.outputMsForTimelineMs(segments, 4_999L))
        assertEquals(2_000L, CompositionTimeline.outputMsForTimelineMs(segments, 5_000L))
    }

    @Test
    fun `a zero-length segment does not divide by zero`() {
        val segments = listOf(
            seg(timelineStartMs = 0, sourceStart = 0, sourceEnd = 0),
            seg(timelineStartMs = 0, sourceEnd = 1_000)
        )
        // The degenerate segment contributes no span and no duration, so the
        // following segment still maps cleanly.
        assertEquals(0L, CompositionTimeline.outputMsForTimelineMs(segments, 0L))
        assertEquals(1_000L, CompositionTimeline.outputMsForTimelineMs(segments, 1_000L))
    }
}
