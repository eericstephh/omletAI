package com.aicreatorstudio.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-11 — the export stall watchdog.
 *
 * ## Why this test class exists
 *
 * The reported bug was "export reaches ~88% and stops forever". The
 * cause is that Media3's `Transformer.getProgress` is a *position
 * ratio*, not a completion signal: it is clamped to 99%, it is averaged
 * across every sequence (so a video+audio composition plateaus lower),
 * it stops being recomputed once the last sample is drained, and during
 * muxer finalization the library resets it to 0 outright. The engine
 * therefore cannot wait for progress to reach 100% — it has to notice
 * that progress stopped *changing*.
 *
 * [ExportStallDetector] is that decision, kept as a pure function so it
 * is testable here without an Android `Transformer`, a codec or a
 * device. These tests pin the behaviour that turns an infinite hang
 * into a bounded, actionable failure.
 */
class ExportStallDetectorTest {

    // ---------------------------------------------------------------
    // 1. It must NOT fire while work is advancing
    // ---------------------------------------------------------------

    @Test
    fun `no stall while progress is being reported recently`() {
        // 10 s of silence mid-encode is completely normal.
        val stall = ExportStallDetector.check(
            lastProgress = 0.40f,
            lastChangeMs = 1_000L,
            nowMs = 11_000L
        )
        assertNull("must not fail a healthy export", stall)
    }

    @Test
    fun `no stall just below the base budget boundary`() {
        // The limit is the first instant at which we give up, so one
        // millisecond earlier must still be tolerated.
        val stall = ExportStallDetector.check(
            lastProgress = 0.30f,
            lastChangeMs = 0L,
            nowMs = ExportStallPolicy.BASE_TIMEOUT_MS - 1L
        )
        assertNull("must not fire before the budget is spent", stall)
    }

    @Test
    fun `fires at exactly the base budget boundary`() {
        // Reaching the budget in full is enough to declare a stall — we
        // would rather report one millisecond early than hang forever.
        val stall = ExportStallDetector.check(
            lastProgress = 0.30f,
            lastChangeMs = 0L,
            nowMs = ExportStallPolicy.BASE_TIMEOUT_MS
        )
        assertNotNull("the budget being fully spent means stalled", stall)
        assertEquals(ExportStallPolicy.BASE_TIMEOUT_MS, stall!!.limitMs)
    }

    @Test
    fun `no stall in the finalize band before its longer budget elapses`() {
        // The muxer flush legitimately reports nothing. 4 minutes of
        // silence at 92% must still be tolerated.
        val stall = ExportStallDetector.check(
            lastProgress = 0.92f,
            lastChangeMs = 0L,
            nowMs = 240_000L
        )
        assertNull("finalize phase needs the generous budget", stall)
    }

    @Test
    fun `a slow but genuinely progressing large export never trips the watchdog`() {
        // 4K long export: progress crawls up by 1% every 90 s. Each step
        // resets the clock, so the watchdog must stay quiet the whole way.
        var lastChange = 0L
        var progress = 0.10f
        var now = 0L
        while (progress < 0.90f) {
            now += 90_000L          // the slow step
            progress += 0.01f
            lastChange = now        // progress DID change -> clock resets
            assertNull(
                "watchdog killed a slow-but-moving export at $progress",
                ExportStallDetector.check(progress, lastChange, now)
            )
        }
    }

    // ---------------------------------------------------------------
    // 2. It MUST fire on a real hang
    // ---------------------------------------------------------------

    @Test
    fun `fires after the base budget is exceeded while encoding`() {
        val stall = ExportStallDetector.check(
            lastProgress = 0.40f,
            lastChangeMs = 0L,
            nowMs = ExportStallPolicy.BASE_TIMEOUT_MS + 1L
        )
        assertNotNull("a genuine encode hang must be reported", stall)
        assertEquals(0.40f, stall!!.progress, 0.0001f)
        assertEquals(ExportStallPolicy.BASE_TIMEOUT_MS, stall.limitMs)
        assertFalse(stall.inFinalizePhase)
    }

    @Test
    fun `fires after the finalize budget is exceeded at a high plateau`() {
        val stall = ExportStallDetector.check(
            lastProgress = 0.88f,
            lastChangeMs = 0L,
            nowMs = ExportStallPolicy.FINALIZE_TIMEOUT_MS + 1L
        )
        assertNotNull("a hang at the 88% plateau must be reported", stall)
        assertEquals(0.88f, stall!!.progress, 0.0001f)
        assertTrue("88% is in the finalize band", stall.inFinalizePhase)
        assertEquals(ExportStallPolicy.FINALIZE_TIMEOUT_MS, stall.limitMs)
    }

    @Test
    fun `fires when progress never moves at all`() {
        // The deadlock case: start() ran but no sample ever emerged.
        val stall = ExportStallDetector.check(
            lastProgress = 0f,
            lastChangeMs = 0L,
            nowMs = ExportStallPolicy.BASE_TIMEOUT_MS + 60_000L
        )
        assertNotNull(stall)
        assertEquals(0f, stall!!.progress, 0.0001f)
    }

    @Test
    fun `the 88 percent plateau specifically times out instead of hanging forever`() {
        // This is the reported bug, encoded as a test. Before the fix the
        // poller waited for a value > 88 that Media3 never emits.
        val at88 = 0.88f
        // Just before the budget: still waiting (correct — it may be
        // finalizing a huge file).
        assertNull(
            ExportStallDetector.check(
                at88,
                lastChangeMs = 0L,
                nowMs = ExportStallPolicy.FINALIZE_TIMEOUT_MS - 1L
            )
        )
        // Past the budget: must fail, not hang.
        val stall = ExportStallDetector.check(
            at88,
            lastChangeMs = 0L,
            nowMs = ExportStallPolicy.FINALIZE_TIMEOUT_MS + 1L
        )
        assertNotNull("88% must not hang indefinitely", stall)
    }

    @Test
    fun `stalled seconds are reported for the message`() {
        val stall = ExportStallDetector.check(
            lastProgress = 0.2f,
            lastChangeMs = 1_000L,
            nowMs = 1_000L + ExportStallPolicy.BASE_TIMEOUT_MS + 5_000L
        )!!
        assertEquals((ExportStallPolicy.BASE_TIMEOUT_MS + 5_000L) / 1000L, stall.stalledSeconds)
    }

    // ---------------------------------------------------------------
    // 3. Clock-skew safety
    // ---------------------------------------------------------------

    @Test
    fun `a backwards clock cannot produce a spurious stall`() {
        // nowMs < lastChangeMs can happen if the wall clock is adjusted.
        // Must be treated as "0 ms elapsed", not as a negative that
        // compares as a huge silence.
        val stall = ExportStallDetector.check(
            lastProgress = 0.5f,
            lastChangeMs = 10_000L,
            nowMs = 1_000L
        )
        assertNull("clock skew must not kill an export", stall)
    }

    // ---------------------------------------------------------------
    // 4. Budget selection
    // ---------------------------------------------------------------

    @Test
    fun `the finalize budget is strictly more generous than the base budget`() {
        assertTrue(
            "finalization must be allowed to take longer than an encode step",
            ExportStallPolicy.FINALIZE_TIMEOUT_MS > ExportStallPolicy.BASE_TIMEOUT_MS
        )
    }

    @Test
    fun `budget switches at the documented threshold`() {
        assertEquals(
            ExportStallPolicy.BASE_TIMEOUT_MS,
            ExportStallPolicy.timeoutForMs(ExportStallPolicy.FINALIZE_AT_OR_ABOVE - 0.01f)
        )
        assertEquals(
            ExportStallPolicy.FINALIZE_TIMEOUT_MS,
            ExportStallPolicy.timeoutForMs(ExportStallPolicy.FINALIZE_AT_OR_ABOVE)
        )
        assertEquals(
            ExportStallPolicy.FINALIZE_TIMEOUT_MS,
            ExportStallPolicy.timeoutForMs(0.99f)
        )
    }

    @Test
    fun `the finalize threshold sits below Media3's own 99 percent clamp`() {
        // Media3 clamps its reported progress to 99%. If our threshold
        // were above that, the generous budget would never be selected
        // and a large export could be killed mid-finalize.
        assertTrue(
            "threshold must be reachable under Media3's clamp",
            ExportStallPolicy.FINALIZE_AT_OR_ABOVE < 0.99f
        )
    }

    // ---------------------------------------------------------------
    // 5. Messages must be actionable
    // ---------------------------------------------------------------

    @Test
    fun `the encoding message says encoding and quotes the percentage and delay`() {
        val stall = ExportStallDetector.check(
            lastProgress = 0.42f,
            lastChangeMs = 0L,
            nowMs = ExportStallPolicy.BASE_TIMEOUT_MS + 7_000L
        )!!
        val msg = ExportStallDetector.messageFor(stall)
        assertTrue("must name the phase: $msg", msg.contains("encoding"))
        assertTrue("must quote the percentage: $msg", msg.contains("42%"))
        assertTrue("must quote the delay: $msg", msg.contains("127"))
        assertTrue("must be actionable: $msg", msg.contains("Try again"))
    }

    @Test
    fun `the finalizing message says finalizing, not encoding`() {
        val stall = ExportStallDetector.check(
            lastProgress = 0.88f,
            lastChangeMs = 0L,
            nowMs = ExportStallPolicy.FINALIZE_TIMEOUT_MS + 1L
        )!!
        val msg = ExportStallDetector.messageFor(stall)
        assertTrue("must name finalization: $msg", msg.contains("finalizing"))
        assertTrue("must quote the percentage: $msg", msg.contains("88%"))
        assertFalse("must not claim encoding: $msg", msg.contains("while encoding"))
    }

    @Test
    fun `no message ever claims the export succeeded`() {
        // The brief forbids a fabricated success. Every stalled outcome
        // must read as a failure.
        for (p in listOf(0f, 0.1f, 0.5f, 0.88f, 0.99f)) {
            val stall = ExportStallDetector.check(p, 0L, 1_000_000L)
            assertNotNull("should have stalled at $p", stall)
            val msg = ExportStallDetector.messageFor(stall!!)
            assertTrue("must say stalled: $msg", msg.contains("stalled"))
            assertFalse("must not say succeeded: $msg", msg.contains("ucceeded"))
        }
    }
}
