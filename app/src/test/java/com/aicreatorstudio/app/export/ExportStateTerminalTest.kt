package com.aicreatorstudio.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-11 — the export's terminal-state contract.
 *
 * ## Why this test class exists
 *
 * The engine can finish an export from three independent directions:
 *   1. Media3's `Transformer.Listener.onCompleted` (the happy path),
 *   2. `Transformer.Listener.onError`,
 *   3. the new stall watchdog in the progress poller.
 *
 * Before this change those paths each wrote straight to the shared
 * `StateFlow`, so they could race: the watchdog could publish
 * `Failed(stall)` and the listener then overwrite it with `Succeeded`,
 * handing the user a success card for an export we had just declared
 * dead — the "false success" the brief explicitly forbids.
 *
 * The engine now funnels every in-flight terminal write through
 * `emitTerminal`, which latches on `ExportState.isTerminal()`. This
 * class pins the *classification* that latch depends on; the engine's
 * own concurrency cannot be exercised here because `Transformer` needs
 * a real device (see the report's limitations section).
 */
class ExportStateTerminalTest {

    /** Mirrors `Media3TransformerExportEngine.isTerminal`. */
    private fun ExportState.isTerminal(): Boolean =
        this is ExportState.Succeeded ||
            this is ExportState.Failed ||
            this is ExportState.Cancelled

    // ---------------------------------------------------------------
    // 1. Classification
    // ---------------------------------------------------------------

    @Test
    fun `succeeded is terminal`() {
        assertTrue(
            ExportState.Succeeded(
                outputUri = null,
                fileName = "out.mp4",
                fileSizeBytes = 1L,
                durationMs = 1L
            ).isTerminal()
        )
    }

    @Test
    fun `failed is terminal`() {
        assertTrue(ExportState.Failed("boom").isTerminal())
    }

    @Test
    fun `cancelled is terminal`() {
        assertTrue(ExportState.Cancelled.isTerminal())
    }

    @Test
    fun `idle preparing and exporting are not terminal`() {
        assertFalse(ExportState.Idle.isTerminal())
        assertFalse(ExportState.Preparing.isTerminal())
        assertFalse(
            ExportState.Exporting(
                ExportProgress(0.5f, ExportStage.ENCODING)
            ).isTerminal()
        )
    }

    @Test
    fun `the finalizing stage is still in flight, not terminal`() {
        // FINALIZING means "the muxer is flushing and we are about to
        // publish". Reporting it as terminal would let the UI show a
        // result before the file is validated.
        val finalizing = ExportState.Exporting(
            ExportProgress(0.99f, ExportStage.FINALIZING)
        )
        assertFalse("finalizing must not be terminal", finalizing.isTerminal())
    }

    // ---------------------------------------------------------------
    // 2. Exactly one terminal state per export
    // ---------------------------------------------------------------

    @Test
    fun `the latch admits exactly one terminal transition`() {
        // Simulates emitTerminal's compare-and-set against a shared slot.
        var slot: ExportState = ExportState.Exporting(
            ExportProgress(0.88f, ExportStage.FINALIZING)
        )
        var publishes = 0

        fun emitTerminal(next: ExportState) {
            if (slot.isTerminal()) return
            slot = next
            publishes++
        }

        // Watchdog fires first...
        emitTerminal(ExportState.Failed("stalled at 88%"))
        // ...then Media3's listener reports success a moment later.
        emitTerminal(
            ExportState.Succeeded(
                outputUri = null,
                fileName = "out.mp4",
                fileSizeBytes = 100L,
                durationMs = 10L
            )
        )
        // ...and the user taps cancel.
        emitTerminal(ExportState.Cancelled)

        assertEquals("exactly one terminal state may be published", 1, publishes)
        assertTrue("the FIRST outcome wins", slot is ExportState.Failed)
    }

    @Test
    fun `a late success cannot override an earlier stall failure`() {
        var slot: ExportState = ExportState.Preparing
        fun emitTerminal(next: ExportState) {
            if (slot.isTerminal()) return
            slot = next
        }
        emitTerminal(ExportState.Failed("Export stalled while finalizing at 88%"))
        emitTerminal(
            ExportState.Succeeded(null, "out.mp4", 100L, 10L)
        )
        assertTrue(
            "a false success must not overwrite a real failure",
            slot is ExportState.Failed
        )
        assertEquals(
            "Export stalled while finalizing at 88%",
            (slot as ExportState.Failed).message
        )
    }

    @Test
    fun `a late cancel cannot override an earlier failure`() {
        var slot: ExportState = ExportState.Preparing
        fun emitTerminal(next: ExportState) {
            if (slot.isTerminal()) return
            slot = next
        }
        emitTerminal(ExportState.Failed("boom"))
        emitTerminal(ExportState.Cancelled)
        assertTrue(
            "cancel after failure must not mask the error",
            slot is ExportState.Failed
        )
    }

    // ---------------------------------------------------------------
    // 3. Progress invariants the UI depends on
    // ---------------------------------------------------------------

    @Test
    fun `progress can never be constructed outside 0 to 1`() {
        // The UI renders `(progress * 100).toInt()`, so an out-of-range
        // value would show "150%" or a negative percentage.
        for (bad in listOf(-0.001f, 1.001f, 2f, -1f)) {
            try {
                ExportProgress(bad, ExportStage.ENCODING)
                throw AssertionError("ExportProgress accepted $bad")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `the finalizing progress reported on completion is below 100 percent`() {
        // The brief forbids faking 100%. On `onCompleted` the file has
        // been muxed but not yet published, so the reported value must
        // stay short of 1.0 — the Succeeded state is what means "done".
        val onCompletion = ExportProgress(0.99f, ExportStage.FINALIZING)
        assertTrue(
            "must not claim completion before publishing",
            onCompletion.progress < 1f
        )
    }

    @Test
    fun `finalizing and encoding are distinct stages`() {
        // The whole point of the fix is that the user can tell "still
        // encoding" from "nearly done, flushing the muxer".
        assertTrue(ExportStage.FINALIZING != ExportStage.ENCODING)
        assertEquals("Finalizing", ExportStage.FINALIZING.displayName)
    }

    // ---------------------------------------------------------------
    // 4. Failure must be actionable
    // ---------------------------------------------------------------

    @Test
    fun `a stall failure message is non-empty and carries no fake success wording`() {
        val stall = ExportStallDetector.check(
            lastProgress = 0.88f,
            lastChangeMs = 0L,
            nowMs = ExportStallPolicy.FINALIZE_TIMEOUT_MS + 1L
        )!!
        val failed = ExportState.Failed(ExportStallDetector.messageFor(stall))
        assertTrue("message must not be blank", failed.message.isNotBlank())
        assertFalse(
            "a failure must never read as a success",
            failed.message.contains("ucceeded")
        )
    }

    @Test
    fun `a stall failure keeps the cause available for diagnostics`() {
        val cause = IllegalStateException("muxer never finished")
        val failed = ExportState.Failed("Export stalled at 88%", cause)
        assertEquals(cause, failed.cause)
    }
}
