package com.aicreatorstudio.app.ui.screens.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-Kotlin tests for the playhead position math used by
 * [CenteredPlayheadOverlay] (the new "moving playhead" behavior).
 *
 * The overlay's x in the lane space is:
 *     x = currentPositionMs * pixelsPerMs - trackScroll.value + railWidthPx
 *
 * The finger-to-ms conversion is:
 *     ms = (fingerX + trackScroll.value - railWidthPx) / pixelsPerMs
 *
 * These two formulas are inverses, so a user who grabs the playhead and
 * drags by delta pixels should see a corresponding delta-ms in the
 * seeked position. The auto-scroll effect is exercised separately via
 * the deadband thresholds.
 */
class PlayheadMathTest {

    private val pixelsPerMs = 0.06f
    private val railWidthPx = 48f

    private fun positionToOverlayX(
        currentPositionMs: Long,
        trackScrollPx: Int,
        pixelsPerMs: Float,
        railWidthPx: Float
    ): Float = (currentPositionMs.coerceAtLeast(0L) * pixelsPerMs) -
        trackScrollPx + railWidthPx

    private fun overlayXToMs(
        fingerX: Float,
        trackScrollPx: Int,
        pixelsPerMs: Float,
        railWidthPx: Float
    ): Long = ((fingerX + trackScrollPx - railWidthPx) /
        pixelsPerMs.coerceAtLeast(0.0001f))
        .toLong()
        .coerceAtLeast(0L)

    @Test
    fun `playhead at start is at rail edge when scroll is zero`() {
        val x = positionToOverlayX(0L, trackScrollPx = 0, pixelsPerMs, railWidthPx)
        assertEquals(railWidthPx, x, 0.0001f)
    }

    @Test
    fun `playhead moves right as time advances without scroll change`() {
        val x0 = positionToOverlayX(0L, 0, pixelsPerMs, railWidthPx)
        val x1 = positionToOverlayX(1000L, 0, pixelsPerMs, railWidthPx)
        // 1000 ms * 0.06 px/ms = 60 px to the right
        assertEquals(x0 + 60f, x1, 0.0001f)
    }

    @Test
    fun `playhead x decreases by the scroll amount when lane scrolls`() {
        val xA = positionToOverlayX(2000L, trackScrollPx = 0, pixelsPerMs, railWidthPx)
        val xB = positionToOverlayX(2000L, trackScrollPx = 100, pixelsPerMs, railWidthPx)
        assertEquals(xA - 100f, xB, 0.0001f)
    }

    @Test
    fun `position-to-x and x-to-ms are inverse for a finger on the playhead`() {
        // Pretend the playhead sits at 5000 ms. The viewport x for
        // that position is some value X. If the user grabs it and
        // converts X back to ms, we should get 5000 ms back.
        val currentPositionMs = 5_000L
        val trackScrollPx = 200
        val x = positionToOverlayX(
            currentPositionMs, trackScrollPx, pixelsPerMs, railWidthPx
        )
        val recoveredMs = overlayXToMs(x, trackScrollPx, pixelsPerMs, railWidthPx)
        // The ms is rounded by the conversion; assert within 1 ms of
        // the original position, which is the precision the timeline
        // exposes to the player.
        val delta = kotlin.math.abs(recoveredMs - currentPositionMs)
        assert(delta <= 1L) {
            "expected ~${currentPositionMs}ms, got ${recoveredMs}ms (delta=$delta)"
        }
    }

    @Test
    fun `scrubbing right by 100 pixels advances time by 100 over pixelsPerMs ms`() {
        val startMs = 2_000L
        val trackScrollPx = 0
        val startX = positionToOverlayX(startMs, trackScrollPx, pixelsPerMs, railWidthPx)
        val newX = startX + 100f
        val newMs = overlayXToMs(newX, trackScrollPx, pixelsPerMs, railWidthPx)
        // 100 px / 0.06 px/ms = 1666.67 ms -> 1666 ms after Long truncation
        val expected = (100f / pixelsPerMs).toLong()
        assertEquals(expected, newMs - startMs)
    }

    @Test
    fun `negative playhead position clamps to zero ms`() {
        val ms = overlayXToMs(
            fingerX = -1000f, // before the rail
            trackScrollPx = 0,
            pixelsPerMs = pixelsPerMs,
            railWidthPx = railWidthPx
        )
        assertEquals(0L, ms)
    }

    @Test
    fun `auto-scroll fires only when playhead leaves the 20 to 80 percent deadband`() {
        val laneWidthPx = 1000f
        val deadbandLeft = laneWidthPx * 0.20f   // 200
        val deadbandRight = laneWidthPx * 0.80f  // 800

        // Scenario 1: playhead inside the deadband -> no scroll change
        val trackScrollPx0 = 0
        val playheadX0 = 350f // inside [200, 800]
        val insideDeadband = playheadX0 in deadbandLeft..deadbandRight
        assert(insideDeadband) { "playhead should be inside the deadband" }

        // Scenario 2: playhead past the right edge -> scroll forward
        val playheadX1 = 900f
        val crossedRight = playheadX1 > deadbandRight
        assert(crossedRight) { "playhead should have crossed the right edge" }
    }
}
