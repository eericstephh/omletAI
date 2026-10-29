package com.aicreatorstudio.app.ai.subtitle.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for CloudWhisperService validating the Cloudflare Worker contract:
 * multipart preamble, response parsing, error mapping, and boundary packaging.
 */
class CloudWhisperServiceTest {

    private val service = CloudWhisperService()

    @Test
    fun `preamble has audio part and language`() {
        val bytes = service.buildPreamble(
            boundary = "AICSdeadbeef",
            fileName = "subtitle_audio_1760000000000.m4a",
            mimeType = "audio/mp4",
            language = "fa"
        )
        val s = String(bytes, Charsets.UTF_8)
        assertTrue(s.contains("name=\"language\""))
        assertTrue(s.contains("fa"))
        assertTrue(s.contains("name=\"audio\""))
        assertTrue(s.contains("filename=\"subtitle_audio_1760000000000.m4a\""))
        assertTrue(s.contains("Content-Type: audio/mp4"))
        assertTrue(s.contains("name=\"model\""))
        assertTrue(s.contains("whisper-large-v3-turbo"))
        assertTrue(s.contains("response_format"))
        assertTrue(s.contains("verbose_json"))
        assertTrue(s.contains("timestamp_granularities"))
        assertTrue(s.endsWith("\r\n\r\n"))
    }

    @Test
    fun `preamble omits language when null`() {
        val bytes = service.buildPreamble(
            boundary = "AICSdeadbeef",
            fileName = "subtitle_audio.wav",
            mimeType = "audio/wav",
            language = null
        )
        val s = String(bytes, Charsets.UTF_8)
        assertTrue(!s.contains("name=\"language\""))
        assertTrue(s.contains("name=\"audio\""))
    }

    @Test
    fun `closing boundary wraps multipart terminator`() {
        assertEquals(
            "\r\n--AICSdeadbeef--\r\n",
            String(service.closingBoundary("AICSdeadbeef"), Charsets.UTF_8)
        )
    }

    @Test
    fun `endpoint points to cloudflare worker`() {
        assertTrue(CloudWhisperService.ENDPOINT.contains("workers.dev"))
        assertTrue(CloudWhisperService.ENDPOINT.endsWith("/api/whisper/transcribe"))
    }

    @Test
    fun `max upload matches 50MB limit`() {
        assertEquals(50L * 1024 * 1024, CloudWhisperService.MAX_UPLOAD_BYTES)
    }

    @Test
    fun `parses success response with segments and transcript`() {
        val body = """
        {
          "success": true,
          "duration_seconds": 3.5,
          "transcript": "سلام دنیا",
          "segments": [
            {"id": 0, "start": 0.0, "end": 1.5, "text": "سلام"},
            {"id": 1, "start": 1.5, "end": 3.5, "text": "دنیا"}
          ]
        }
        """.trimIndent()
        val outcome = service.parseSuccess(body)
        assertTrue(outcome is CloudWhisperService.Outcome.Success)
        val r = (outcome as CloudWhisperService.Outcome.Success).response
        assertEquals("سلام دنیا", r.text)
        assertEquals(2, r.segments.size)
        assertEquals(0.0, r.segments[0].startSec, 1e-9)
        assertEquals(1.5, r.segments[0].endSec, 1e-9)
        assertEquals("سلام", r.segments[0].text)
        assertEquals(3.5, r.durationSec!!, 1e-9)
    }

    @Test
    fun `blank body returns failure`() {
        val outcome = service.parseSuccess("   ")
        assertTrue(outcome is CloudWhisperService.Outcome.Failure)
    }

    @Test
    fun `invalid json returns failure with rawBody`() {
        val outcome = service.parseSuccess("<html>502 Bad Gateway</html>")
        assertTrue(outcome is CloudWhisperService.Outcome.Failure)
        assertNotNull((outcome as CloudWhisperService.Outcome.Failure).error.rawBody)
    }

    @Test
    fun `401 maps to unauthorized Persian message`() {
        val e = service.mapHttpError(401, """{"error":"Unauthorized"}""")
        assertTrue(e.userMessage.contains("کلید Groq نامعتبر"))
    }

    @Test
    fun `429 maps to quota exceeded Persian message`() {
        val e = service.mapHttpError(429, """{"error":"Quota exceeded"}""")
        assertTrue(e.userMessage.contains("محدودیت نرخ درخواست"))
    }

    @Test
    fun `502 and 503 map to temporary error`() {
        val e502 = service.mapHttpError(502, "")
        assertTrue(e502.userMessage.contains("سرور موقتاً پاسخ نمی‌دهد"))

        val e503 = service.mapHttpError(503, "")
        assertTrue(e503.userMessage.contains("سرور موقتاً پاسخ نمی‌دهد"))
    }
}
