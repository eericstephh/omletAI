package com.aicreatorstudio.app.export

/**
 * Pure helpers for laying segments out on the **composition** timeline —
 * the timeline of the exported file, which is not the same as the
 * editor's timeline.
 *
 * In the editor, segment *i* lives at its clip's `timelineStartMs`. In
 * the exported file, segments are concatenated end-to-end, so segment
 * *i* begins at the sum of the output durations of every segment before
 * it. Caption timings (and anything else expressed in editor-timeline
 * milliseconds) have to be rebased by that difference.
 *
 * Keeping this arithmetic here — rather than inline in the export
 * engine — means it can be unit-tested without an Android device, which
 * matters because `Media3TransformerExportEngine` needs a `Context` and
 * cannot be constructed in a JVM test.
 *
 * The end-to-end layout matches [RenderGraphResult.totalOutputDurationMs],
 * which is already defined as the sum of the per-segment output
 * durations. Gaps between clips on the editor timeline are therefore not
 * reproduced in the output.
 */
object CompositionTimeline {

    /**
     * Composition-time origin (ms) of each segment, index-aligned with
     * [segments].
     *
     * The first segment always starts at `0`. Segment durations are
     * post-speed ([RenderSegment.outputDurationMs]).
     */
    fun originsMs(segments: List<RenderSegment>): List<Long> {
        var accumulated = 0L
        return segments.map { segment ->
            val origin = accumulated
            accumulated += segment.outputDurationMs
            origin
        }
    }

    /** Total output duration (ms) of the laid-out composition. */
    fun totalDurationMs(segments: List<RenderSegment>): Long =
        segments.sumOf { it.outputDurationMs }

    /**
     * The `clipTimelineStartMs` value to hand to
     * `SubtitleBurnInFactory.createOverlays` for [segment], given that
     * segment's [compositionOriginMs].
     *
     * The factory converts an overlay's editor-timeline position into
     * output time as `overlay.startTimeMs - clipTimelineStartMs`. Feeding
     * it `segment.timelineStartMs - compositionOriginMs` therefore yields
     *
     * ```
     * compositionOrigin + (overlay.startTimeMs - segment.timelineStartMs)
     * ```
     *
     * which is exactly the caption's position on the composition
     * timeline. This is what lets the factory be reused unchanged for
     * every segment instead of only the first.
     */
    fun subtitleOriginMs(segment: RenderSegment, compositionOriginMs: Long): Long =
        segment.timelineStartMs - compositionOriginMs

    /**
     * Converts an **output-clock** timestamp of [segment] into clip-local
     * source milliseconds. See the overload for the arithmetic.
     */
    fun clipLocalMsForOutput(segment: RenderSegment, outputTimeUs: Long): Long =
        clipLocalMsForOutput(outputTimeUs = outputTimeUs, speed = segment.speed)

    /**
     * Converts an output-clock timestamp into clip-local source ms.
     *
     * A transition's In/Out windows are expressed in clip-local source
     * milliseconds — that is what `TransitionEngine` compares against — but
     * Media3 hands the effect chain a **retimed** presentation timestamp.
     * A clip baked at 2× reaches a given source frame twice as fast, so:
     *
     * ```
     * clipLocalMs = outputUs / 1000 * speed
     * ```
     *
     * A non-finite or non-positive [speed] is treated as `1f`. That matters:
     * a NaN here would make every window comparison in the transition
     * engine false, silently dropping the transition from the export
     * instead of failing loudly.
     */
    fun clipLocalMsForOutput(outputTimeUs: Long, speed: Float): Long {
        val s = if (speed.isFinite() && speed > 0f) speed else 1f
        return ((outputTimeUs / 1000.0) * s).toLong()
    }

    /**
     * Maps an **editor-timeline** position onto the **composition**
     * timeline, so a piece of media the user placed at `timelineMs` in the
     * editor can be positioned in the exported file.
     *
     * This is what lets audio-only clips be mixed into the export at the
     * right moment. It is a piecewise-linear map because segments are
     * concatenated without gaps in the output:
     *
     * ```
     * timelineMs inside segment i  ->  origin(i) + progress(i) * outputDuration(i)
     * timelineMs in a gap          ->  origin(the next segment)
     * timelineMs past the last one ->  the composition's total duration
     * ```
     *
     * `progress` is measured against the segment's **editor** span
     * ([RenderSegment.sourceDurationMs], i.e. the clip's
     * `effectiveDurationMs`) and applied to its **output** span. Those two
     * differ whenever `speed != 1`, and mapping proportionally is what
     * keeps a 2× clip's midpoint landing on the midpoint of its output
     * segment rather than drifting.
     *
     * The returned value is always inside `0..totalDurationMs(segments)`.
     */
    fun outputMsForTimelineMs(segments: List<RenderSegment>, timelineMs: Long): Long {
        if (segments.isEmpty()) return 0L
        val origins = originsMs(segments)
        segments.forEachIndexed { index, segment ->
            // A position before this segment starts sits in a gap (or before
            // the first clip). Gaps are not reproduced, so it collapses onto
            // this segment's origin — the closest output position that
            // actually exists.
            if (timelineMs < segment.timelineStartMs) return origins[index]
            val editorSpan = segment.sourceDurationMs
            if (editorSpan > 0L && timelineMs < segment.timelineStartMs + editorSpan) {
                val progress =
                    (timelineMs - segment.timelineStartMs).toDouble() / editorSpan
                return origins[index] +
                    (progress * segment.outputDurationMs).toLong()
            }
        }
        // Past the end of every segment — clamp to the composition's length.
        return totalDurationMs(segments)
    }
}
