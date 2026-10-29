package com.aicreatorstudio.app.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-12 — the diagnostics screen's filter + summary logic.
 *
 * This is the pure model the Settings «گزارش‌های فنی» section renders.
 * It is a top-level `data class` (not a ViewModel member) precisely so
 * it is testable without Hilt — the project's JVM test stack cannot
 * construct a Hilt ViewModel.
 */
class DiagnosticsSummaryTest {

    private fun record(level: LogLevel, ts: Long, event: String = "e") = LogRecord(
        timestampMillis = ts,
        level = level,
        component = "c",
        event = event,
        message = "m"
    )

    @Test
    fun `summarize orders records oldest first`() {
        val summary = DiagnosticsSummary.summarize(
            listOf(record(LogLevel.INFO, 300), record(LogLevel.INFO, 100), record(LogLevel.INFO, 200)),
            problemsOnly = false
        )
        assertEquals(listOf(100L, 200L, 300L), summary.records.map { it.timestampMillis })
    }

    @Test
    fun `counts split errors and warnings correctly`() {
        val summary = DiagnosticsSummary.summarize(
            listOf(
                record(LogLevel.DEBUG, 1),
                record(LogLevel.INFO, 2),
                record(LogLevel.WARN, 3),
                record(LogLevel.ERROR, 4),
                record(LogLevel.ERROR, 5)
            ),
            problemsOnly = false
        )
        assertEquals(5, summary.totalCount)
        assertEquals(2, summary.errorCount)
        assertEquals(1, summary.warnCount)
    }

    @Test
    fun `latest problem is the newest warn or error, not the newest INFO`() {
        val summary = DiagnosticsSummary.summarize(
            listOf(
                record(LogLevel.WARN, 10, "old-warn"),
                record(LogLevel.INFO, 20, "later-info"),
                record(LogLevel.ERROR, 15, "newer-error")
            ),
            problemsOnly = false
        )
        assertEquals("newer-error", summary.latestProblem?.event)
    }

    @Test
    fun `latest problem is null when there is only info and debug`() {
        val summary = DiagnosticsSummary.summarize(
            listOf(record(LogLevel.INFO, 1), record(LogLevel.DEBUG, 2)),
            problemsOnly = false
        )
        assertNull(summary.latestProblem)
    }

    @Test
    fun `problemsOnly filter keeps exactly the warnings and errors`() {
        val all = listOf(
            record(LogLevel.DEBUG, 1),
            record(LogLevel.INFO, 2),
            record(LogLevel.WARN, 3),
            record(LogLevel.ERROR, 4)
        )
        val summary = DiagnosticsSummary.summarize(all, problemsOnly = true)
        assertEquals(2, summary.visibleRecords.size)
        assertTrue(summary.visibleRecords.all { it.isProblem })
        // The unfiltered count is preserved so the header still shows the total.
        assertEquals(4, summary.totalCount)
    }

    @Test
    fun `visibleRecords returns everything when the filter is off`() {
        val all = listOf(record(LogLevel.DEBUG, 1), record(LogLevel.ERROR, 2))
        val summary = DiagnosticsSummary.summarize(all, problemsOnly = false)
        assertEquals(2, summary.visibleRecords.size)
    }
}
