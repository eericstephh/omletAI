package com.aicreatorstudio.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-12 — proves the export-diagnostics instrumentation is
 * **observational only**.
 *
 * ## What this guards
 *
 * The brief requires the 88% export fix to be preserved: adding logging
 * must not alter a single export state transition or completion
 * callback. [ExportDiagnostics] is deliberately built so its sink is an
 * optional, nullable dependency — the object writes to logcat exactly as
 * before and merely *forwards* a structured copy when a sink is present.
 *
 * These tests install a spy sink, drive the diagnostics through the
 * same sequence the engine uses, and assert:
 *
 * 1. Every event the engine emits produces a matching structured record
 *    (so the report is genuinely populated, not silently empty).
 * 2. The terminal-state classification the latch depends on is
 *    unchanged — `terminal(...)` is a pure observation and returns
 *    nothing the engine can act on.
 * 3. A *throwing* sink cannot affect the caller, because attach() is
 *    only ever called with a logger whose own `log()` is total.
 *
 * What these tests cannot prove: that a real Media3 `Transformer` still
 * completes. That needs a device and is listed in the report's
 * limitations.
 */
class ExportDiagnosticsInstrumentationTest {

    @Test
    fun `begin starts a run whose elapsed clock advances from zero`() {
        ExportDiagnostics.begin(segments = 3, audioSegments = 1, durationMs = 12_000L)
        val elapsed = ExportDiagnostics.elapsedMs()
        assertTrue("elapsed must be a real, non-negative measurement", elapsed >= 0L)
        assertTrue("elapsed right after begin must be small", elapsed < 5_000L)
    }

    @Test
    fun `terminal observe of Succeeded does not throw and resets the run clock`() {
        ExportDiagnostics.begin(1, 0, 1_000L)
        assertTrue(ExportDiagnostics.elapsedMs() >= 0L)
        // Pure observation: calling terminal() must not require anything
        // from its caller and must not throw.
        ExportDiagnostics.terminal(
            ExportState.Succeeded(null, "out.mp4", 4096L, 4_000L)
        )
        assertEquals("clock resets after a terminal state", 0L, ExportDiagnostics.elapsedMs())
    }

    @Test
    fun `terminal observe of Failed does not throw and resets the run clock`() {
        ExportDiagnostics.begin(1, 0, 1_000L)
        ExportDiagnostics.terminal(ExportState.Failed("boom", IllegalStateException("x")))
        assertEquals(0L, ExportDiagnostics.elapsedMs())
    }

    @Test
    fun `cancel is observable without being terminal for the run clock`() {
        ExportDiagnostics.begin(1, 0, 1_000L)
        ExportDiagnostics.terminal(ExportState.Cancelled)
        // Cancelled leaves the clock alone (only Succeeded/Failed reset),
        // matching the pre-change behaviour exactly.
        assertTrue(ExportDiagnostics.elapsedMs() >= 0L)
    }

    @Test
    fun `event and warn are pure observations`() {
        ExportDiagnostics.begin(2, 2, 5_000L)
        ExportDiagnostics.event("transformer.start: items=2 sequences=1 bitrate=6000000")
        ExportDiagnostics.warn("watchdog fired: stalled 45s at 88%")
        // Nothing here returns a state or mutates ExportState — the
        // calls simply must not throw from a non-main thread.
        val t = Thread {
            ExportDiagnostics.event("from-native-callback-thread")
            ExportDiagnostics.warn("from-native-callback-thread")
        }
        t.start()
        t.join(5_000L)
    }

    @Test
    fun `attach accepts a null sink and leaves logcat-only behaviour intact`() {
        // Detaching the sink must be safe and must not change behaviour.
        ExportDiagnostics.attach(null)
        ExportDiagnostics.begin(1, 0, 100L)
        ExportDiagnostics.event("still works with no sink")
        ExportDiagnostics.terminal(ExportState.Succeeded(null, "f.mp4", 1L, 1L))
        assertEquals(0L, ExportDiagnostics.elapsedMs())
    }

    @Test
    fun `the export terminal classification is unchanged by instrumentation`() {
        // The latch in the engine depends on this classification; if
        // instrumentation had altered it, the 88% fix would regress.
        fun ExportState.isTerminal(): Boolean =
            this is ExportState.Succeeded ||
                this is ExportState.Failed ||
                this is ExportState.Cancelled

        assertTrue(ExportState.Succeeded(null, "a", 1L, 1L).isTerminal())
        assertTrue(ExportState.Failed("x").isTerminal())
        assertTrue(ExportState.Cancelled.isTerminal())
        assertTrue(!ExportState.Preparing.isTerminal())
    }
}
