package com.aicreatorstudio.app.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 2026-10-12 — the logging system's behavioral tests.
 *
 * The project's stack is JUnit4 + kotlinx-coroutines-test + org.json
 * only (no Mockito / Robolectric), so every test here exercises a **pure
 * decision** (sanitization, formatting, rotation policy, report
 * assembly) or writes to a real temporary directory. No source-text
 * assertions are used: a test that greps a file for a string would pass
 * even if the behavior were broken.
 */
class LoggingCoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- 1. Severity + record shape ----

    @Test
    fun `level ranking orders debug below error`() {
        assertTrue(LogLevel.DEBUG.rank < LogLevel.INFO.rank)
        assertTrue(LogLevel.INFO.rank < LogLevel.WARN.rank)
        assertTrue(LogLevel.WARN.rank < LogLevel.ERROR.rank)
    }

    @Test
    fun `level parses case-insensitively and falls back to info`() {
        assertEquals(LogLevel.ERROR, LogLevel.fromName("error"))
        assertEquals(LogLevel.WARN, LogLevel.fromName("Warn"))
        assertEquals(LogLevel.INFO, LogLevel.fromName("nonsense"))
        assertEquals(LogLevel.INFO, LogLevel.fromName(null))
    }

    @Test
    fun `isProblem is true only for warn and above`() {
        assertFalse(record(LogLevel.DEBUG).isProblem)
        assertFalse(record(LogLevel.INFO).isProblem)
        assertTrue(record(LogLevel.WARN).isProblem)
        assertTrue(record(LogLevel.ERROR).isProblem)
    }

    // ---- 2. JSON serialization round-trip ----

    @Test
    fun `record serializes and parses back with every field intact`() {
        val original = LogRecord(
            timestampMillis = 1_700_000_000_123L,
            level = LogLevel.WARN,
            component = "export",
            event = "export_warning",
            correlationId = "exp-42",
            message = "watchdog fired",
            fields = mapOf("elapsedMs" to "9000", "stage" to "ENCODING"),
            throwable = "IllegalStateException: boom"
        )
        val line = LogFormat.toLine(original)
        assertFalse("line must be single-line JSONL", line.contains('\n'))
        val parsed = LogFormat.fromLine(line)
        assertNotNull(parsed)
        assertEquals(original, parsed)
    }

    @Test
    fun `blank and truncated lines parse to null instead of throwing`() {
        assertNull(LogFormat.fromLine(""))
        assertNull(LogFormat.fromLine("   "))
        assertNull(LogFormat.fromLine("{\"ts\":1,\"lvl\":\"INFO\"")) // no closing brace
        assertNull(LogFormat.fromLine("not json at all"))
    }

    @Test
    fun `parseAll keeps the good lines and drops a corrupt trailing line`() {
        val body = buildString {
            appendLine(LogFormat.toLine(record(LogLevel.INFO)))
            appendLine(LogFormat.toLine(record(LogLevel.WARN)))
            append("{\"ts\":3,\"lvl\":\"ERR") // killed mid-write
        }
        val records = LogFormat.parseAll(body)
        assertEquals(2, records.size)
    }

    // ---- 3. Rotation + retention policy ----

    @Test
    fun `shouldRotate only when the incoming write would exceed the cap`() {
        assertFalse(LogFormat.shouldRotate(currentBytes = 0, incomingBytes = 10, maxFileBytes = 100))
        assertFalse(LogFormat.shouldRotate(currentBytes = 50, incomingBytes = 50, maxFileBytes = 100))
        assertTrue(LogFormat.shouldRotate(currentBytes = 50, incomingBytes = 51, maxFileBytes = 100))
    }

    @Test
    fun `filesToPrune keeps the newest within the file-count limit`() {
        val files = listOf(
            LogFile(generation = 0, sizeBytes = 100), // active = newest
            LogFile(generation = 1, sizeBytes = 100),
            LogFile(generation = 2, sizeBytes = 100),
            LogFile(generation = 3, sizeBytes = 100)
        )
        val doomed = LogFormat.filesToPrune(files, maxFiles = 3, maxTotalBytes = Long.MAX_VALUE)
        assertEquals(listOf(3), doomed.map { it.generation })
    }

    @Test
    fun `filesToPrune enforces the total byte budget oldest-first`() {
        val files = listOf(
            LogFile(0, 1_000),
            LogFile(1, 1_000),
            LogFile(2, 1_000)
        )
        val doomed = LogFormat.filesToPrune(files, maxFiles = 10, maxTotalBytes = 2_500)
        // Newest two fit (2000 <= 2500); the oldest (2) must go.
        assertEquals(listOf(2), doomed.map { it.generation })
    }

    @Test
    fun `filesToPrune is order-independent`() {
        val shuffled = listOf(LogFile(2, 10), LogFile(0, 10), LogFile(1, 10))
        val doomed = LogFormat.filesToPrune(shuffled, maxFiles = 2, maxTotalBytes = Long.MAX_VALUE)
        assertEquals(listOf(2), doomed.map { it.generation })
    }

    // ---- 4. Report assembly ----

    @Test
    fun `report carries header, counts and the latest problem`() {
        val records = listOf(
            record(LogLevel.INFO, event = "a", message = "started"),
            record(LogLevel.ERROR, event = "b", message = "boom"),
            record(LogLevel.WARN, event = "c", message = "careful")
        )
        val report = LogReportBuilder.build(
            records = records,
            deviceSummary = "app=1.0 (1) android=35 abi=arm64-v8a",
            generatedAtMillis = 1_700_000_000_000L
        )
        assertTrue(report.contains("events: total=3 error=1 warn=1"))
        assertTrue(report.contains("Latest problem"))
        assertTrue(report.contains("boom"))   // latest problem is the WARN? No — ERROR is later.
        assertTrue(report.contains("careful"))
        assertTrue(report.contains("app=1.0 (1)"))
    }

    @Test
    fun `report caps the number of events but keeps the newest`() {
        val many = (1..50).map { record(LogLevel.INFO, event = "e$it", message = "m$it") }
        val report = LogReportBuilder.build(
            records = many,
            deviceSummary = "x",
            generatedAtMillis = 0L,
            maxEvents = 10
        )
        assertTrue(report.contains("retainedInReport=10"))
        assertTrue(report.contains("e50"))   // newest kept
        assertFalse(report.contains(" e1 ")) // oldest dropped from the listing
    }

    // ---- 5. Sanitizer defense-in-depth at report time ----

    @Test
    fun `report re-sanitizes a path that slipped into a message`() {
        val leaky = LogRecord(
            timestampMillis = 1L,
            level = LogLevel.ERROR,
            component = "export",
            event = "export_failed",
            message = "failed at /storage/emulated/0/Movies/private/vacation.mp4"
        )
        val report = LogReportBuilder.build(listOf(leaky), "x", 0L)
        assertTrue(report.contains("vacation.mp4"))
        assertFalse(report.contains("/storage/emulated"))
    }

    private fun record(
        level: LogLevel,
        event: String = "evt",
        message: String = "msg"
    ) = LogRecord(
        timestampMillis = System.nanoTime(),
        level = level,
        component = "test",
        event = event,
        message = message
    )

    private fun tempLogFile(name: String): File = tmp.newFile(name)
}
