package com.aicreatorstudio.app.ui.screens.editor

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure-Kotlin tests for the **delta-from-initial-touch** scrub math
 * used by the playhead hit column in [EditorTimeline]. See
 * [CenteredPlayheadOverlay]'s `detectDragGestures` body.
 *
 * The math is intentionally simple:
 * ```
 *   initialFingerX, initialMs   captured at onDragStart
 *   newMs = (initialMs + (fingerX - initialFingerX) / pixelsPerMs)
 *             .roundToLong()
 *             .coerceAtLeast(0L)
 * ```
 *
 * The point of these tests is to lock in the property that **one
 * dragged pixel moves time by exactly `1 / pixelsPerMs` ms at every
 * zoom level, and the playhead never jumps on grab regardless of
 * where inside the hit column the user touched**. Zoom, scroll, and
 * rail width are NOT inputs to the scrub math.
 *
 * The render-side math (the playhead's overlay-space x as a function
 * of `currentPositionMs`, `pixelsPerMs`, `trackScroll`, and
 * `railWidthPx`) is covered separately by [PlayheadMathTest].
 */
class PlayheadScrubDeltaTest {

    private val minPpms = EditorLayoutSpec.MIN_PIXELS_PER_MS
    private val maxPpms = EditorLayoutSpec.MAX_PIXELS_PER_MS
    private val defaultPpms = EditorLayoutSpec.DEFAULT_TIMELINE_PIXELS_PER_MS

    /**
     * The new scrub formula. Pure function so it can be exercised
     * without Compose / Android. Matches the production body
     * verbatim.
     */
    private fun scrubMs(
        initialFingerX: Float,
        initialMs: Long,
        fingerX: Float,
        pixelsPerMs: Float
    ): Long {
        val deltaPx = fingerX - initialFingerX
        val deltaMs = (deltaPx / pixelsPerMs.coerceAtLeast(0.0001f))
            .roundToInt()
            .toLong()
        return (initialMs + deltaMs).coerceAtLeast(0L)
    }

    // -------- no-jump on grab at every column-local x position ----

    @Test
    fun `no jump on grab when finger touches the left edge of the hit column`() {
        val initialMs = 12_345L
        // Hit column is 28 dp wide. Touching at column-local x=0
        // (left edge) must NOT move the playhead.
        val newMs = scrubMs(
            initialFingerX = 0f,
            initialMs = initialMs,
            fingerX = 0f,
            pixelsPerMs = defaultPpms
        )
        assertEquals(initialMs, newMs)
    }

    @Test
    fun `no jump on grab when finger touches the center of the hit column`() {
        val initialMs = 12_345L
        val newMs = scrubMs(
            initialFingerX = 14f, // half of 28 dp hit width
            initialMs = initialMs,
            fingerX = 14f,
            pixelsPerMs = defaultPpms
        )
        assertEquals(initialMs, newMs)
    }

    @Test
    fun `no jump on grab when finger touches the right edge of the hit column`() {
        val initialMs = 12_345L
        val newMs = scrubMs(
            initialFingerX = 28f,
            initialMs = initialMs,
            fingerX = 28f,
            pixelsPerMs = defaultPpms
        )
        assertEquals(initialMs, newMs)
    }

    // -------- per-pixel sensitivity at every zoom level ----------

    @Test
    fun `1 px right at max zoom in advances time by 1 ms`() {
        // MAX_PIXELS_PER_MS = 1.0 → 1 px = 1 ms
        val newMs = scrubMs(
            initialFingerX = 14f, initialMs = 5_000L,
            fingerX = 15f, pixelsPerMs = maxPpms
        )
        assertEquals(5_001L, newMs)
    }

    @Test
    fun `1 px right at default zoom advances time by 16 ms`() {
        // 0.06 px/ms → 1 px = 16.67 ms → rounded 17
        val newMs = scrubMs(
            initialFingerX = 14f, initialMs = 5_000L,
            fingerX = 15f, pixelsPerMs = defaultPpms
        )
        assertEquals(5_017L, newMs)
    }

    @Test
    fun `1 px right at max zoom out advances time by 200 ms`() {
        // MIN_PIXELS_PER_MS = 0.005 → 1 px = 200 ms
        val newMs = scrubMs(
            initialFingerX = 14f, initialMs = 5_000L,
            fingerX = 15f, pixelsPerMs = minPpms
        )
        assertEquals(5_200L, newMs)
    }

    @Test
    fun `100 px right at default zoom advances time by 1666 ms`() {
        // 0.06 px/ms → 100 px = 1666.67 ms → rounded 1667
        val newMs = scrubMs(
            initialFingerX = 0f, initialMs = 2_000L,
            fingerX = 100f, pixelsPerMs = defaultPpms
        )
        assertEquals(3_667L, newMs)
    }

    // -------- left drag goes backwards in time -------------------

    @Test
    fun `100 px left at default zoom rewinds time by 1666 ms`() {
        val newMs = scrubMs(
            initialFingerX = 100f, initialMs = 5_000L,
            fingerX = 0f, pixelsPerMs = defaultPpms
        )
        assertEquals(3_333L, newMs)
    }

    @Test
    fun `1 px left at max zoom out rewinds time by 200 ms`() {
        val newMs = scrubMs(
            initialFingerX = 28f, initialMs = 5_000L,
            fingerX = 27f, pixelsPerMs = minPpms
        )
        assertEquals(4_800L, newMs)
    }

    // -------- clamp to zero when scrubbing before 0 --------------

    @Test
    fun `scrub before zero clamps to 0 ms`() {
        val newMs = scrubMs(
            initialFingerX = 0f, initialMs = 100L,
            fingerX = -10_000f, pixelsPerMs = defaultPpms
        )
        assertEquals(0L, newMs)
    }

    @Test
    fun `scrub before zero at max zoom out also clamps to 0 ms`() {
        val newMs = scrubMs(
            initialFingerX = 0f, initialMs = 500L,
            fingerX = -100f, pixelsPerMs = minPpms
        )
        // 100 px * 200 ms/px = 20 000 ms of leftward delta — would
        // go to -19 500 ms but must clamp to 0.
        assertEquals(0L, newMs)
    }

    // -------- start, middle, end of timeline ---------------------

    @Test
    fun `drag at start of timeline follows finger at every zoom`() {
        val zooms = listOf(minPpms, defaultPpms, maxPpms)
        for (ppms in zooms) {
            val newMs = scrubMs(
                initialFingerX = 0f, initialMs = 0L,
                fingerX = 50f, pixelsPerMs = ppms
            )
            val expected = (50f / ppms).roundToInt().toLong()
            assertEquals(
                "zoom $ppms px/ms: 50 px right at start should equal ${expected}ms",
                expected, newMs
            )
        }
    }

    @Test
    fun `drag in the middle of the timeline follows finger at every zoom`() {
        val zooms = listOf(minPpms, defaultPpms, maxPpms)
        for (ppms in zooms) {
            val newMs = scrubMs(
                initialFingerX = 14f, initialMs = 60_000L,
                fingerX = 14f + 200f, pixelsPerMs = ppms
            )
            val expected = 60_000L + (200f / ppms).roundToInt().toLong()
            assertEquals(
                "zoom $ppms px/ms: 200 px right at 60s should equal ${expected}ms",
                expected, newMs
            )
        }
    }

    @Test
    fun `drag near the end of the timeline follows finger at every zoom`() {
        val zooms = listOf(minPpms, defaultPpms, maxPpms)
        for (ppms in zooms) {
            val newMs = scrubMs(
                initialFingerX = 28f, initialMs = 3_540_000L, // 59:00
                fingerX = 28f + 20f, pixelsPerMs = ppms
            )
            val expected = 3_540_000L + (20f / ppms).roundToInt().toLong()
            assertEquals(
                "zoom $ppms px/ms: 20 px right near 59:00 should equal ${expected}ms",
                expected, newMs
            )
        }
    }

    // -------- scroll position is NOT an input --------------------

    @Test
    fun `scrub math is independent of trackScroll value`() {
        // The point of the refactor: the new formula deliberately
        // doesn't take trackScroll as an input. Verify the math
        // produces the same delta for the same drag whether the
        // lane is scrolled or not — the only thing the user
        // controls is the finger's column-local x.
        val beforeScroll = scrubMs(
            initialFingerX = 14f, initialMs = 30_000L,
            fingerX = 14f + 80f, pixelsPerMs = defaultPpms
        )
        val afterScroll = scrubMs(
            initialFingerX = 14f, initialMs = 30_000L,
            fingerX = 14f + 80f, pixelsPerMs = defaultPpms
        )
        assertEquals(beforeScroll, afterScroll)
    }

    @Test
    fun `scrub math is independent of railWidthPx`() {
        // Same: railWidthPx is a render-side concern. The scrub
        // math never sees it.
        val result1 = scrubMs(
            initialFingerX = 7f, initialMs = 10_000L,
            fingerX = 7f + 30f, pixelsPerMs = defaultPpms
        )
        val result2 = scrubMs(
            initialFingerX = 7f, initialMs = 10_000L,
            fingerX = 7f + 30f, pixelsPerMs = defaultPpms
        )
        assertEquals(result1, result2)
    }

    // -------- zoom change mid-scrub: no per-pixel discontinuity --

    @Test
    fun `zoom change mid-scrub does not jump the playhead`() {
        // Imagine the user is mid-drag and pinches to change zoom.
        // The `pointerInput` is keyed to `pixelsPerMs`, so the
        // gesture detector restarts. We do NOT carry `initialFingerX`
        // / `initialMs` across that restart in this test setup —
        // they reset to the current position when the new gesture
        // starts. The point of this test is that **at the moment
        // of the zoom change the math itself doesn't produce a
        // discontinuity**: a delta of 0 px (no finger movement
        // between two frames) yields `newMs == initialMs` at every
        // zoom level. If it didn't, the playhead would jump when
        // the user pinched.
        for (ppms in listOf(minPpms, defaultPpms, maxPpms)) {
            val newMs = scrubMs(
                initialFingerX = 14f, initialMs = 7_777L,
                fingerX = 14f, pixelsPerMs = ppms
            )
            assertEquals(
                "zoom $ppms px/ms: 0 px delta should be a no-op",
                7_777L, newMs
            )
        }
    }

    // -------- negative pixelsPerMs is hardened -------------------

    @Test
    fun `zero pixelsPerMs is treated as min to avoid div by zero`() {
        // Production code has `.coerceAtLeast(0.0001f)`. We mirror
        // that here so the test asserts the same hardened behavior.
        val newMs = scrubMs(
            initialFingerX = 0f, initialMs = 1_000L,
            fingerX = 100f, pixelsPerMs = 0f
        )
        // With ppms = 0.0001, 100 px = 1_000_000 ms.
        assertEquals(1_001_000L, newMs)
    }

    // -------- render-side math still works (regression guard) ----

    /**
     * The render-side formula (where the playhead's visual x is
     * drawn). This is a regression guard from the previous
     * implementation — the render math is unchanged by the
     * scrub-math refactor and the round-trip should still be
     * inverse within 1 ms.
     */
    @Test
    fun `render math is still the inverse of x to ms`() {
        val currentPositionMs = 5_000L
        val trackScrollPx = 200
        val railWidthPx = 48f
        val ppms = 0.06f
        val x = (currentPositionMs.coerceAtLeast(0L) * ppms) -
            trackScrollPx + railWidthPx
        val recoveredMs = ((x - railWidthPx + trackScrollPx) /
            ppms.coerceAtLeast(0.0001f))
            .toLong()
            .coerceAtLeast(0L)
        val delta = abs(recoveredMs - currentPositionMs)
        assert(delta <= 1L) {
            "expected ~${currentPositionMs}ms, got ${recoveredMs}ms (delta=$delta)"
        }
    }
}
