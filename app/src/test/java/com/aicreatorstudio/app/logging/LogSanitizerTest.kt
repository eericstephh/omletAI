package com.aicreatorstudio.app.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-12 — redaction tests for [LogSanitizer].
 *
 * These are the tests that keep the promise "no secrets reach disk". A
 * bug here is a privacy incident, so each rule has a positive case (the
 * secret is removed) and — where the shape could over-match — a negative
 * case (ordinary text survives).
 */
class LogSanitizerTest {

    @Test
    fun `unix path collapses to its basename`() {
        val out = LogSanitizer.sanitize("opened /storage/emulated/0/Movies/holiday.mp4 ok")
        assertFalse(out.contains("/storage/emulated"))
        assertTrue(out.contains("holiday.mp4"))
    }

    @Test
    fun `windows path collapses to its basename`() {
        val out = LogSanitizer.sanitize("""opened C:\Users\eric\Videos\holiday.mp4 ok""")
        assertFalse(out.contains("C:\\Users"))
        assertTrue(out.contains("holiday.mp4"))
    }

    @Test
    fun `file and content uris collapse to the last segment`() {
        val out = LogSanitizer.sanitize("src=content://media/external/video/media/12345")
        assertFalse(out.contains("content://"))
        assertTrue(out.contains("12345"))
    }

    @Test
    fun `bearer tokens are redacted`() {
        val out = LogSanitizer.sanitize("Authorization: Bearer sk-abcdef1234567890abcdef")
        assertFalse(out.contains("sk-abcdef1234567890abcdef"))
        assertTrue(out.contains(LogSanitizer.REDACTED))
    }

    @Test
    fun `key-value secrets keep the key and drop the value`() {
        val out = LogSanitizer.sanitize("api_key=SUPERSECRETVALUE123 and token: ABCDEF123456")
        assertFalse(out.contains("SUPERSECRETVALUE123"))
        assertFalse(out.contains("ABCDEF123456"))
        assertTrue(out.contains("api_key="))
        assertTrue(out.contains(LogSanitizer.REDACTED))
    }

    @Test
    fun `explicit registered secrets are removed`() {
        val out = LogSanitizer.sanitize(
            "using key MYLITERALKEY99 now",
            extraSecrets = listOf("MYLITERALKEY99")
        )
        assertFalse(out.contains("MYLITERALKEY99"))
        assertTrue(out.contains(LogSanitizer.REDACTED))
    }

    @Test
    fun `long hex blobs are redacted`() {
        val sha = "a".repeat(64)
        val out = LogSanitizer.sanitize("sha256=$sha")
        assertFalse(out.contains(sha))
        assertTrue(out.contains(LogSanitizer.REDACTED))
    }

    @Test
    fun `ordinary diagnostics text is untouched`() {
        val text = "export_stage: finalization started (voiceClean=true)"
        assertEquals(text, LogSanitizer.sanitize(text))
    }

    @Test
    fun `oversize messages are bounded`() {
        val huge = "x".repeat(LogSanitizer.MAX_FIELD_CHARS + 500)
        val out = LogSanitizer.sanitize(huge)
        assertTrue(out.length <= LogSanitizer.MAX_FIELD_CHARS + 1) // +1 for the ellipsis
    }

    @Test
    fun `sanitizeFields drops blank keys and sanitizes values`() {
        val out = LogSanitizer.sanitizeFields(
            mapOf(
                " " to "ignored",
                "path" to "/data/user/0/app/files/omelet.log",
                "count" to "3"
            )
        )
        assertFalse(out.containsKey(" "))
        assertEquals("omelet.log", out["path"])
        assertEquals("3", out["count"])
    }

    @Test
    fun `describeThrowable yields class name plus sanitized message`() {
        val t = IllegalStateException("failed reading /data/user/0/app/files/model.bin")
        val desc = LogSanitizer.describeThrowable(t)
        assertTrue(desc!!.startsWith("IllegalStateException:"))
        assertFalse(desc.contains("/data/user/0"))
        assertTrue(desc.contains("model.bin"))
    }

    @Test
    fun `describeThrowable returns null for a null throwable and a bare class when message is blank`() {
        assertEquals(null, LogSanitizer.describeThrowable(null))
        assertEquals(
            "IllegalStateException",
            LogSanitizer.describeThrowable(IllegalStateException())
        )
    }
}
