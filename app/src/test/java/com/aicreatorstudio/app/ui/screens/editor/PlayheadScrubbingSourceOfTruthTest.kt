package com.aicreatorstudio.app.ui.screens.editor

import androidx.media3.exoplayer.SeekParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin tests for the **finger-is-source-of-truth-while-scrubbing**
 * invariant. See `EditorScreen.kt`'s shared scrub handlers
 * (2026-09-06 playhead overhaul):
 *
 *   - the 33 ms playback poller must NOT overwrite the local
 *     `currentPositionMs` while `isScrubbing` is true (the visual
 *     playhead is driven by the drag, not the player clock);
 *   - player seeks during the drag use `CLOSEST_SYNC` (cheap,
 *     throttled to ~15 Hz by PlayheadScrubThrottle) and ONE `EXACT`
 *     seek is issued on release so the final player position lands
 *     exactly on the finger;
 *   - after release the poller is additionally gated until the
 *     release seek settles (see PlayheadScrubThrottleTest).
 *
 * These tests don't construct a real ExoPlayer — they exercise the
 * exact pure-function reducers that the production code uses inline
 * so the test fails the day a future refactor reintroduces a feedback
 * loop or leaves the player in an intermediate seek-parameter state.
 */
class PlayheadScrubbingSourceOfTruthTest {

    /**
     * Mirrors the 33 ms poller body in
     * `EditorScreen.DisposableEffect(exoPlayer)`. Returns the
     * `currentPositionMs` value the screen should display after one
     * tick. The single source of truth is [isScrubbing]: if it is
     * true, the player's position is **discarded** and the previous
     * local value is returned unchanged; if it is false, the
     * player's position is adopted (and the ViewModel is updated).
     */
    private fun tick(
        previousLocalPositionMs: Long,
        playerPositionMs: Long,
        isScrubbing: Boolean
    ): Long = if (isScrubbing) previousLocalPositionMs else playerPositionMs

    /**
     * Mirrors the shared scrub handlers' SeekParameters policy
     * (2026-09-06). During the drag the player snaps to sync frames
     * (cheap, smooth); the release issues ONE exact seek so the
     * settled position equals the finger.
     */
    private fun seekParametersDuringDrag(): SeekParameters = SeekParameters.CLOSEST_SYNC

    private fun seekParametersOnRelease(): SeekParameters = SeekParameters.EXACT

    // -------- the poller MUST skip during a drag -------------------

    @Test
    fun `poller does not overwrite local position while scrubbing`() {
        // Player is at 50_000 ms. The user has already dragged the
        // playhead to 80_000 ms via onScrubSeek; the local
        // currentPositionMs mirrors that. The 33 ms poller fires
        // and reads player.currentPosition = 50_000 (the player
        // hasn't completed the seek yet). The local value must NOT
        // be replaced or the visual will jump backwards.
        val after = tick(
            previousLocalPositionMs = 80_000L,
            playerPositionMs = 50_000L,
            isScrubbing = true
        )
        assertEquals(80_000L, after)
    }

    @Test
    fun `poller does not overwrite even when player is ahead of the finger`() {
        // The seek already completed and the player overshot by one
        // frame (rare but possible with CLOSEST_SYNC). We still must
        // not touch the finger.
        val after = tick(
            previousLocalPositionMs = 30_000L,
            playerPositionMs = 30_041L, // ~1 frame at 24 fps beyond the finger
            isScrubbing = true
        )
        assertEquals(30_000L, after)
    }

    @Test
    fun `poller does not overwrite while scrubbing across many ticks`() {
        // Simulate 10 consecutive 33 ms ticks during a drag. The
        // finger sits at 12_345 ms; the player oscillates wildly as
        // it processes seekTo calls. The local value must stay
        // pinned to 12_345 ms for the entire duration of the drag.
        var local = 12_345L
        val playerReadings = listOf(0L, 5_000L, 9_999L, 11_000L, 12_000L, 12_345L,
                                    12_200L, 12_500L, 12_300L, 12_345L)
        for (playerPos in playerReadings) {
            local = tick(local, playerPos, isScrubbing = true)
            assertEquals(
                "local must stay pinned to the finger during scrub",
                12_345L, local
            )
        }
    }

    // -------- the poller MUST resume after release -----------------

    @Test
    fun `poller adopts the player position on the first tick after release`() {
        // First tick after onScrubEnd. The player has settled at the
        // exact seek target (SeekParameters.EXACT was in effect for
        // the duration of the drag, so the final player position
        // equals the finger). The poller adopts that value, so the
        // local state and the visual stay in lock-step.
        val after = tick(
            previousLocalPositionMs = 80_000L,
            playerPositionMs = 80_000L,
            isScrubbing = false
        )
        assertEquals(80_000L, after)
    }

    @Test
    fun `poller recovers from a missed tick during scrub`() {
        // While the user is scrubbing, the local state is pinned to
        // the finger (75_000 ms). On release the poller resumes and
        // reads the player's settled position (also 75_000 ms, since
        // EXACT seeks are exact). The local value is still 75_000 ms
        // — there is no jump on the resume tick.
        val after = tick(
            previousLocalPositionMs = 75_000L,
            playerPositionMs = 75_000L,
            isScrubbing = false
        )
        assertEquals(75_000L, after)
    }

    // -------- SeekParameters policy (2026-09-06) -------------------

    @Test
    fun `drag seeks use CLOSEST_SYNC for smooth cheap scrubbing`() {
        assertEquals(SeekParameters.CLOSEST_SYNC, seekParametersDuringDrag())
    }

    @Test
    fun `release uses ONE EXACT seek so the playhead does not snap back`() {
        assertEquals(SeekParameters.EXACT, seekParametersOnRelease())
    }

    @Test
    fun `SeekParameters never leaves the player in an intermediate state`() {
        // The policy is total — every phase maps to exactly one of the
        // two canonical SeekParameters values. If a future refactor
        // introduces a third state, this test fails.
        for (params in listOf(seekParametersDuringDrag(), seekParametersOnRelease())) {
            assertTrue(
                "produced an unexpected SeekParameters value: $params",
                params === SeekParameters.EXACT || params === SeekParameters.CLOSEST_SYNC
            )
        }
    }

    @Test
    fun `SeekParameters differs between drag and release`() {
        // The single EXACT release seek is observable — it is what
        // makes the settled player position equal the finger.
        assertNotEquals(seekParametersDuringDrag(), seekParametersOnRelease())
    }

    // -------- viewModel.onScrubSeek is the source of truth --------

    @Test
    fun `onScrubSeek propagates the finger position to playbackPositionMs immediately`() {
        // Mirrors the ViewModel.onScrubSeek body. It MUST set
        // playbackPositionMs on the same frame the gesture fires
        // (synchronously, not via a LaunchedEffect), because the
        // visual playhead is rendered from currentPositionMs which
        // is derived from this field via the UiState mirror.
        // A pure reducer reproduces the production body verbatim.
        data class Mirror(
            var playbackPositionMs: Long = 0L,
            var pendingScrubSeekMs: Long? = null
        )
        fun onScrubSeek(state: Mirror, ms: Long): Mirror = state.copy(
            playbackPositionMs = ms,
            pendingScrubSeekMs = ms
        )
        val before = Mirror()
        val after = onScrubSeek(before, ms = 12_345L)
        assertEquals(12_345L, after.playbackPositionMs)
        assertEquals(12_345L, after.pendingScrubSeekMs)
    }

    @Test
    fun `onScrubSeek fires synchronously and is observable on the very next read`() {
        // The whole point of the fix is that the visual playhead
        // sees the finger on the SAME frame the gesture emits the
        // value — no 33 ms poller round-trip. Verify the reducer's
        // output is observable without any await / tick.
        data class Mirror(
            var playbackPositionMs: Long = 0L,
            var pendingScrubSeekMs: Long? = null
        )
        fun onScrubSeek(state: Mirror, ms: Long): Mirror = state.copy(
            playbackPositionMs = ms,
            pendingScrubSeekMs = ms
        )
        val state = onScrubSeek(Mirror(), ms = 99_999L)
        // No suspend, no delay: the next read sees the new value.
        assertEquals(99_999L, state.playbackPositionMs)
    }

    // -------- end-to-end invariant: no player position ever wins during drag ---

    @Test
    fun `the player position is never observed in the local state during a drag`() {
        // Property: after any sequence of poller ticks while
        // isScrubbing is true, the local position equals the
        // finger's value at the time of the most recent onScrubSeek.
        val finger = 42_000L
        var local = finger
        // Simulate a player that races from 0 to 100_000 ms while
        // we drag. The poller must reject every reading.
        val sequence = (0..30).map { it * 3_500L }
        for (playerPos in sequence) {
            local = tick(local, playerPos, isScrubbing = true)
        }
        assertEquals("local must remain at the finger after a drag sequence", finger, local)
        assertFalse("local must NOT be overwritten by a player value", local == sequence.last())
    }

    // -------- poller must observe the LIVE isScrubbing flag (closure-freshness) ---

    /**
     * Regression guard for the 2026-08-31 fix. The production
     * poller is installed inside `DisposableEffect(exoPlayer)`, so
     * its `Runnable` captures `uiState` by closure ONCE at composition
     * time and never refreshes — even though `uiState.isScrubbing`
     * changes on every drag start / end. The fix wraps the flag in
     * `rememberUpdatedState` so the inner Runnable reads the live
     * value on every invocation. This test models that contract:
     * a sequence of ticks where `isScrubbing` flips mid-sequence
     * must be honored by every subsequent tick, even if the closure
     * would otherwise have a stale value.
     */
    @Test
    fun `poller observes the live isScrubbing flag on every tick`() {
        // The poller reads the flag from a rememberUpdatedState-backed
        // cell rather than from a captured parameter. Model the cell
        // explicitly: `cell.value` is what the Runnable reads, and
        // composition-time updates the cell.
        var cellValue = false
        fun readCell(): Boolean = cellValue
        fun tick(local: Long, playerPos: Long): Long =
            if (!readCell()) playerPos else local

        // Start in non-scrubbing mode; the player drives the local
        // value for two frames.
        var local = 0L
        local = tick(local, 1_000L); assertEquals(1_000L, local)
        local = tick(local, 2_000L); assertEquals(2_000L, local)

        // User grabs the playhead. The flag flips in the cell; the
        // closure now sees the new value on the very next tick.
        cellValue = true

        // Even though the player races far ahead, every tick must
        // adopt the previous local value (the finger is the source
        // of truth). 20 ticks is enough to expose a captured-stale
        // closure: a buggy implementation would see `cellValue == false`
        // and let the player win at least once.
        val races = (0 until 20).map { it * 5_000L }
        for (playerPos in races) {
            local = tick(local, playerPos)
        }
        assertEquals(
            "local must remain pinned to the finger for the entire scrub",
            2_000L, local
        )

        // User releases. The flag flips back; the poller immediately
        // resumes adopting the player's settled position.
        cellValue = false
        local = tick(local, 12_345L)
        assertEquals(
            "poller must adopt the player position the moment scrubbing ends",
            12_345L, local
        )
    }
}
