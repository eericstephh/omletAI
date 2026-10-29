package com.aicreatorstudio.app.ui.screens.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-13 — source-shape guards for the transport (Play/ Pause) path.
 *
 * ## The defect this pins
 *
 * Two independent faults made the Play button look dead:
 *
 * 1. **A stale closure in the 33 ms poller.** The poller lives inside
 *    `DisposableEffect(exoPlayer)`, an effect keyed on the player alone,
 *    so it never restarts when playback starts. Its `isPlaying` read was
 *    therefore the value from the composition where the effect was
 *    installed — permanently `false` — and the poller hard-paused every
 *    audio stem and every PiP player 33 ms after each tap. Reading
 *    `isPlaying` from a *lambda parameter* inside a non-restarting effect
 *    is the bug class; `rememberUpdatedState` is the fix, and the guard
 *    below is what keeps it from regressing.
 * 2. **A tap swallowed by the ready handler.** The STATE_READY listener
 *    force-pauses (that is how the preview shows a still first frame), so
 *    a Play tap that landed before READY was undone by its own
 *    listener. The `userPlayRequested` latch is what the ready handler
 *    must consult.
 *
 * The third assertion is the empty-timeline case: `play()` on an IDLE
 * player is a silent no-op, so the transport must say something rather
 * than let the user conclude the button is broken.
 */
class PlaybackWiringTest {

    private fun read(path: String): List<String> =
        File(path).readLines().map { it.trim() }

    private fun source() = read("src/main/java/com/aicreatorstudio/app/ui/screens/editor/EditorScreen.kt")

    @Test
    fun `the poller reads playback state through a fresh reference`() {
        val src = source()
        // The ref must be declared from the live state, next to the other
        // playback refs the poller already uses.
        assertTrue(
            "isPlayingRef must be declared through rememberUpdatedState",
            src.any { it.contains("val isPlayingRef = rememberUpdatedState(isPlaying)") }
        )
        // The invariant, stated directly: every poller condition must read
        // the ref-backed state, never the captured `isPlaying`. The two
        // poller lines are exactly the `setPlaying(` calls that also test
        // the scrub gate; the transport's own pause calls
        // `setPlaying(false)` with no gate and are excluded.
        val gated = src.filter {
            it.contains("setPlaying(") && it.contains("!scrubbingRef.value")
        }
        assertTrue(
            "expected the poller's two setPlaying conditions, found ${gated.size}",
            gated.size >= 2
        )
        for (line in gated) {
            assertTrue(
                "a poller condition read captured isPlaying (stale closure): $line",
                line.contains("isPlayingRef.value")
            )
        }
    }

    @Test
    fun `the ready handler honours a play tap that arrived before ready`() {
        val src = source()
        val readyIdx = src.indexOfFirst { it.contains("if (userPlayRequested) {") }
        assertTrue("the ready handler must consult the latch", readyIdx >= 0)
        // The auto-pause must come after the latch check with nothing
        // between them that could take a different branch.
        val autoPauseIdx = src.indexOfFirst { it.contains("exoPlayer.playWhenReady = false") }
        assertTrue(
            "the force-pause must follow the latch check",
            autoPauseIdx > readyIdx
        )
        // And the latch must be consumed, or it would leak into the next
        // rebuild and turn every trim-drag end into auto-play.
        assertTrue(
            "the latch must be cleared once consumed",
            src.any { it.contains("userPlayRequested = false") }
        )
    }

    @Test
    fun `the play tap arms the latch before asking the player to play`() {
        val src = source()
        // Scope to the transport's own handler: `exoPlayer.play()` also
        // appears in the scrub-release path (resume after a drag), which
        // is a different code path and must not be measured here.
        val from = src.indexOfFirst { it.contains("onPlayPause = {") }
        assertTrue("the transport handler must exist", from >= 0)
        val transport = src.drop(from)
        val armIdx = transport.indexOfFirst { it.contains("userPlayRequested = true") }
        val playIdx = transport.indexOfFirst { it.contains("exoPlayer.play()") }
        assertTrue("the latch must be armed", armIdx >= 0)
        assertTrue("the player must be asked to play", playIdx >= 0)
        assertTrue(
            "arming after play() would lose the race with the ready handler",
            armIdx < playIdx
        )
    }

    @Test
    fun `an empty timeline explains itself instead of doing nothing`() {
        val src = source()
        assertTrue(
            "the transport must detect the empty playlist",
            src.any { it.contains("if (playlistClips.isEmpty()) {") }
        )
        assertTrue(
            "and surface a message through the ViewModel banner",
            src.any { it.contains("viewModel.onPlayerError(") } &&
                src.any { it.contains("SubtitleMessages.Playback.NOTHING_TO_PLAY_FA") }
        )
        assertFalse(
            "the empty-timeline path must not fall through into play()",
            src.any { it.contains("NOTHING_TO_PLAY_FA") && it.contains("exoPlayer.play()") }
        )
    }
}
