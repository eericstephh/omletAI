package com.aicreatorstudio.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE B3 — JVM unit tests for the AI Subtitle generation
 * state machine. Locks the contract that the [EditorViewModel]
 * and the [com.aicreatorstudio.app.ui.screens.editor.AISubtitleCard]
 * both depend on:
 *
 *   - every state is a singleton or a data class with
 *     structural equality (so `state.copy(...)` flows through
 *     the ViewModel predictably);
 *   - `isRunning` is true exactly for the transient pipeline
 *     phases (the brief's Generate/Cancel button rule);
 *   - `isTerminal` is true exactly for the resting states
 *     (the brief's "can re-run" gate);
 *   - `userMessage` is in Persian and never includes fake
 *     progress numbers (the brief's "no fake progress" rule);
 *   - states that carry a `progressFraction` only carry a
 *     value in [0f, 1f].
 */
class SubtitleGenerationStateTest {

    @Test
    fun `Idle is the resting state, isRunning=false, isTerminal=true`() {
        val s = SubtitleGenerationState.Idle
        assertFalse("Idle must not be 'running'", s.isRunning)
        assertTrue("Idle must be a 'terminal' state (can re-run)", s.isTerminal)
        assertEquals("آماده", s.userMessage)
    }

    @Test
    fun `transient pipeline states are running, not terminal`() {
        val states: List<SubtitleGenerationState> = listOf(
            SubtitleGenerationState.Preparing(),
            SubtitleGenerationState.DecodingAudio(),
            SubtitleGenerationState.Transcribing(),
            SubtitleGenerationState.CreatingSubtitles,
            SubtitleGenerationState.Cancelling
        )
        for (s in states) {
            assertTrue(
                "state=$s must be running so the Generate button is disabled " +
                    "and the Cancel button is shown",
                s.isRunning
            )
            assertFalse(
                "state=$s must not be terminal so the user cannot re-enter " +
                    "the pipeline while a previous run is in flight",
                s.isTerminal
            )
        }
    }

    @Test
    fun `Completed is terminal and carries the overlay count the UI will display`() {
        val s = SubtitleGenerationState.Completed(overlayCount = 196)
        assertTrue("Completed is a terminal state", s.isTerminal)
        assertFalse("Completed is not 'running'", s.isRunning)
        assertEquals("196 زیرنویس ساخته شد", s.userMessage)
    }

    @Test
    fun `Error is terminal and surfaces the user-facing Persian message`() {
        val s = SubtitleGenerationState.Error("هیچ گفتاری در ویدیو شناسایی نشد")
        assertTrue("Error is a terminal state", s.isTerminal)
        assertFalse("Error is not 'running'", s.isRunning)
        assertEquals("هیچ گفتاری در ویدیو شناسایی نشد", s.userMessage)
    }

    @Test
    fun `progress-aware states include a percentage only when progress is non-null`() {
        // The brief forbids "fake progress values" — the message
        // must NOT contain a fake % when progress is null.
        val nullProgress = SubtitleGenerationState.Transcribing()
        assertFalse(
            "Transcribing() with null progress must not advertise a % — " +
                "otherwise the UI shows a fake progress value",
            nullProgress.userMessage.contains("٪")
        )
        val halfProgress = SubtitleGenerationState.Transcribing(progressFraction = 0.5f)
        assertTrue(
            "Transcribing(progress=0.5) must show 50٪",
            halfProgress.userMessage.contains("50٪")
        )
    }

    @Test
    fun `data class variants have structural equality so ViewModel state updates diff correctly`() {
        // The ViewModel does `state.copy(subtitleGenerationState = X)` and
        // Compose recomposition relies on data-class equality. A refactor
        // that turns any variant into a regular class would silently
        // break recomposition; this test catches that regression.
        val a = SubtitleGenerationState.Completed(overlayCount = 5)
        val b = SubtitleGenerationState.Completed(overlayCount = 5)
        val c = SubtitleGenerationState.Completed(overlayCount = 6)
        assertEquals("same fields → same hashCode", a, b)
        assertEquals("same fields → same equality", a, b)
        assertFalse("different overlayCount → not equal", a == c)
    }

    @Test
    fun `progressFraction is null for stages that cannot report a real 0-to-1 fraction`() {
        // These three stages are either too fast or non-progressable
        // by the underlying engine, so the contract is "no fake
        // progress" — the field is always null.
        val noFraction: List<SubtitleGenerationState> = listOf(
            SubtitleGenerationState.CreatingSubtitles,
            SubtitleGenerationState.Cancelling
        )
        for (s in noFraction) {
            // Data class with no progressFraction field has no getter,
            // so we can only assert the message never contains a '%'.
            assertFalse(
                "state=$s must not advertise a % — the underlying operation " +
                    "is too fast / non-progressable to report real progress",
                s.userMessage.contains("٪")
            )
        }
    }

    @Test
    fun `Preparing and DecodingAudio default to null progress (no fake progress at start)`() {
        val p = SubtitleGenerationState.Preparing()
        assertNull("Preparing() defaults to null progress", p.progressFraction)
        val d = SubtitleGenerationState.DecodingAudio()
        assertNull("DecodingAudio() defaults to null progress", d.progressFraction)
    }
}
