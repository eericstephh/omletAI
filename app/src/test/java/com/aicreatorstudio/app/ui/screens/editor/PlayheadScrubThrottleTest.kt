package com.aicreatorstudio.app.ui.screens.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-09-06 playhead overhaul — JVM tests for the throttled scrub
 * pipeline ([PlayheadScrubThrottle]) that replaced the per-frame
 * seek / per-frame ViewModel push pipeline.
 *
 * The production contract:
 *
 *   - Player seeks during a drag are throttled to
 *     [EditorLayoutSpec.SCRUB_SEEK_INTERVAL_MS] (~15 Hz) and skipped
 *     entirely when the target ms did not change (sub-pixel deltas
 *     round to the same ms constantly at high zoom).
 *   - ViewModel pushes run on their own slower cadence
 *     ([EditorLayoutSpec.SCRUB_VM_PUSH_INTERVAL_MS], ~10 Hz); the
 *     final value is pushed synchronously on release.
 *   - The 33 ms poller is gated by TWO flags: isScrubbing (finger is
 *     the source of truth) and awaitingSettle (the post-release
 *     EXACT seek has not landed yet). While settling, the player's
 *     reading is trusted only once it lands within tolerance of the
 *     requested target.
 */
class PlayheadScrubThrottleTest {

    private val seekInterval = EditorLayoutSpec.SCRUB_SEEK_INTERVAL_MS
    private val pushInterval = EditorLayoutSpec.SCRUB_VM_PUSH_INTERVAL_MS

    // -------- seek throttle ---------------------------------------

    @Test
    fun `first drag frame seeks immediately`() {
        assertTrue(
            PlayheadScrubThrottle.shouldSeek(
                nowMs = 100L, lastSeekAtMs = 0L,
                targetMs = 5_000L, lastTargetMs = -1L,
                minIntervalMs = seekInterval
            )
        )
    }

    @Test
    fun `drag frames within the seek interval are throttled`() {
        // t=100 seek issued; t=133 (next frame) must NOT seek.
        assertFalse(
            PlayheadScrubThrottle.shouldSeek(
                nowMs = 133L, lastSeekAtMs = 100L,
                targetMs = 5_100L, lastTargetMs = 5_000L,
                minIntervalMs = seekInterval
            )
        )
    }

    @Test
    fun `drag frames past the seek interval seek again`() {
        // t=100 seek issued; t=166 (66ms later, ~15Hz) may seek.
        assertTrue(
            PlayheadScrubThrottle.shouldSeek(
                nowMs = 166L, lastSeekAtMs = 100L,
                targetMs = 5_200L, lastTargetMs = 5_000L,
                minIntervalMs = seekInterval
            )
        )
    }

    @Test
    fun `unchanged target never seeks regardless of interval`() {
        // Sub-pixel deltas round to the same ms — no seek.
        assertFalse(
            PlayheadScrubThrottle.shouldSeek(
                nowMs = 10_000L, lastSeekAtMs = 0L,
                targetMs = 5_000L, lastTargetMs = 5_000L,
                minIntervalMs = seekInterval
            )
        )
    }

    @Test
    fun `seek cadence is capped near 15 Hz`() {
        // Simulate a 1-second drag at 60 fps (16.67ms frames). The
        // number of seeks must be ~15, not ~60.
        var lastSeekAt = 0L
        var lastTarget = -1L
        var seeks = 0
        var target = 0L
        for (frame in 0 until 60) {
            val now = frame * 17L
            target = frame * 1_000L
            if (PlayheadScrubThrottle.shouldSeek(now, lastSeekAt, target, lastTarget, seekInterval)) {
                seeks++
                lastSeekAt = now
                lastTarget = target
            }
        }
        assertTrue(
            "expected <= 16 seeks in a 1s drag, got $seeks",
            seeks <= 16
        )
        assertTrue("drag must still seek at least once", seeks >= 1)
    }

    // -------- ViewModel push throttle ------------------------------

    @Test
    fun `viewmodel push respects its own slower cadence`() {
        assertFalse(
            PlayheadScrubThrottle.shouldPushViewModel(
                nowMs = 140L, lastPushAtMs = 100L,
                targetMs = 5_100L, lastPushedMs = 5_000L,
                minIntervalMs = pushInterval
            )
        )
        assertTrue(
            PlayheadScrubThrottle.shouldPushViewModel(
                nowMs = 200L, lastPushAtMs = 100L,
                targetMs = 5_300L, lastPushedMs = 5_000L,
                minIntervalMs = pushInterval
            )
        )
    }

    @Test
    fun `unchanged target never pushes the viewmodel`() {
        assertFalse(
            PlayheadScrubThrottle.shouldPushViewModel(
                nowMs = 10_000L, lastPushAtMs = 0L,
                targetMs = 5_000L, lastPushedMs = 5_000L,
                minIntervalMs = pushInterval
            )
        )
    }

    @Test
    fun `push cadence is capped near 10 Hz`() {
        var lastPushAt = 0L
        var lastPushed = -1L
        var pushes = 0
        for (frame in 0 until 60) {
            val now = frame * 17L
            val target = frame * 1_000L
            if (PlayheadScrubThrottle.shouldPushViewModel(now, lastPushAt, target, lastPushed, pushInterval)) {
                pushes++
                lastPushAt = now
                lastPushed = target
            }
        }
        assertTrue("expected <= 11 VM pushes in a 1s drag, got $pushes", pushes <= 11)
    }

    // -------- poller settle gate ------------------------------------

    private val tolerance = 250L

    @Test
    fun `poller never adopts the player position during a drag`() {
        assertFalse(
            PlayheadScrubThrottle.shouldAdoptPlayerPosition(
                isScrubbing = true, awaitingSettle = false,
                playerPositionMs = 5_000L, lastRequestedMs = 9_999L,
                settleToleranceMs = tolerance
            )
        )
    }

    @Test
    fun `poller adopts the player position in normal playback`() {
        assertTrue(
            PlayheadScrubThrottle.shouldAdoptPlayerPosition(
                isScrubbing = false, awaitingSettle = false,
                playerPositionMs = 5_000L, lastRequestedMs = 9_999L,
                settleToleranceMs = tolerance
            )
        )
    }

    @Test
    fun `poller rejects stale player position while the release seek settles`() {
        // Release at 80_000; the player still reads its pre-seek
        // position of 50_000 — adopting that was the jump-back bug.
        assertFalse(
            PlayheadScrubThrottle.shouldAdoptPlayerPosition(
                isScrubbing = false, awaitingSettle = true,
                playerPositionMs = 50_000L, lastRequestedMs = 80_000L,
                settleToleranceMs = tolerance
            )
        )
    }

    @Test
    fun `poller accepts the player position once it lands on the target`() {
        assertTrue(
            PlayheadScrubThrottle.shouldAdoptPlayerPosition(
                isScrubbing = false, awaitingSettle = true,
                playerPositionMs = 80_000L, lastRequestedMs = 80_000L,
                settleToleranceMs = tolerance
            )
        )
    }

    @Test
    fun `poller accepts a near-target player position within tolerance`() {
        assertTrue(
            PlayheadScrubThrottle.shouldAdoptPlayerPosition(
                isScrubbing = false, awaitingSettle = true,
                playerPositionMs = 80_100L, lastRequestedMs = 80_000L,
                settleToleranceMs = tolerance
            )
        )
        assertFalse(
            PlayheadScrubThrottle.shouldAdoptPlayerPosition(
                isScrubbing = false, awaitingSettle = true,
                playerPositionMs = 80_400L, lastRequestedMs = 80_000L,
                settleToleranceMs = tolerance
            )
        )
    }

    @Test
    fun `full drag lifecycle has no backward jump after release`() {
        // End-to-end reducer simulation:
        //   drag: poller gated by isScrubbing (finger wins)
        //   release: EXACT seek issued; poller gated until the player
        //            lands on the final target (or timeout clears it)
        val finger = 80_000L
        var local = 79_000L
        var awaitingSettle = false
        var lastRequested = 0L

        // --- drag: 10 ticks while the player races the seek storm ---
        awaitingSettle = false
        lastRequested = finger
        for (playerPos in listOf(60_000L, 65_000L, 70_000L)) {
            val adopt = PlayheadScrubThrottle.shouldAdoptPlayerPosition(
                isScrubbing = true, awaitingSettle = awaitingSettle,
                playerPositionMs = playerPos, lastRequestedMs = lastRequested,
                settleToleranceMs = tolerance
            )
            if (adopt) local = playerPos
        }
        assertEquals("finger must win during the drag", 79_000L, local)

        // --- release: EXACT seek to the finger; poller gated ---
        local = finger
        awaitingSettle = true
        lastRequested = finger

        // Tick 1: player still at the pre-seek position (60_000).
        // Old bug: adopted 60_000 -> visible backwards jump.
        val tick1 = PlayheadScrubThrottle.shouldAdoptPlayerPosition(
            isScrubbing = false, awaitingSettle = awaitingSettle,
            playerPositionMs = 60_000L, lastRequestedMs = lastRequested,
            settleToleranceMs = tolerance
        )
        assertFalse("stale pre-seek position must be rejected", tick1)

        // Tick 2: seek completed, player reports the target.
        val tick2 = PlayheadScrubThrottle.shouldAdoptPlayerPosition(
            isScrubbing = false, awaitingSettle = awaitingSettle,
            playerPositionMs = finger, lastRequestedMs = lastRequested,
            settleToleranceMs = tolerance
        )
        assertTrue("settled target must be adopted", tick2)

        // --- timeout fallback: gate cleared, player == target ---
        awaitingSettle = false
        val tick3 = PlayheadScrubThrottle.shouldAdoptPlayerPosition(
            isScrubbing = false, awaitingSettle = awaitingSettle,
            playerPositionMs = finger, lastRequestedMs = lastRequested,
            settleToleranceMs = tolerance
        )
        assertTrue(tick3)
        assertEquals(finger, local)
    }
}
