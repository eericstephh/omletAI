package com.aicreatorstudio.app.ai.groq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests pinning the Groq (OpenAI-compatible) verbose_json
 * contract against a REAL captured response shape, plus multipart
 * body encoding and error parsing.
 */
class GroqApiTest {

    // ----------------------------------------------------------------
    // verbose_json parsing
    // ----------------------------------------------------------------

    @Test
    fun `parses real verbose_json shape with segments and words`() {
        val body = """
        {
          "id": "x-1",
          "object": "chat.completion",
          "text": "سلام دنیا Hello world",
          "duration": 4.06,
          "language": "fa",
          "segments": [
            {"id": 0, "seek": 0, "start": 0.0, "end": 2.14, "text": " سلام دنیا"},
            {"id": 1, "seek": 0, "start": 2.14, "end": 4.06, "text": " Hello world"}
          ],
          "words": [
            {"word": "سلام", "start": 0.12, "end": 0.55},
            {"word": "دنیا", "start": 0.60, "end": 1.10},
            {"word": "Hello", "start": 2.20, "end": 2.61},
            {"word": "world", "start": 2.65, "end": 3.02}
          ]
        }
        """.trimIndent()
        val r = GroqResponseParser.parseVerboseJson(body)
        assertEquals("سلام دنیا Hello world", r.text)
        assertEquals("fa", r.language)
        assertEquals(4.06, r.durationSec!!, 1e-9)
        assertEquals(2, r.segments.size)
        assertEquals(0, r.segments[0].id)
        assertEquals(0.0, r.segments[0].startSec, 1e-9)
        assertEquals(2.14, r.segments[0].endSec, 1e-9)
        assertEquals("سلام دنیا", r.segments[0].text)
        assertEquals(4, r.words.size)
        assertEquals("سلام", r.words[0].word)
        assertEquals(0.12, r.words[0].startSec, 1e-9)
    }

    @Test
    fun `tolerates missing words array`() {
        val body = """{"text":"abc","segments":[{"id":0,"start":0.0,"end":1.0,"text":"abc"}]}"""
        val r = GroqResponseParser.parseVerboseJson(body)
        assertEquals("abc", r.text)
        assertEquals(1, r.segments.size)
        assertEquals(0, r.words.size)
        assertNull(r.language)
        assertNull(r.durationSec)
    }

    @Test
    fun `parses groq error body`() {
        val body = """
        {"error": {"message": "Invalid API Key", "type": "invalid_request_error",
         "code": "invalid_api_key"}}
        """.trimIndent()
        val e = GroqResponseParser.parseError(401, body)
        assertEquals(401, e.httpCode)
        assertEquals("Invalid API Key", e.message)
        assertEquals("invalid_request_error", e.type)
        assertEquals("invalid_api_key", e.code)
    }

    @Test
    fun `non-json error body surfaces raw text`() {
        val e = GroqResponseParser.parseError(502, "<html>Bad Gateway</html>")
        assertEquals(502, e.httpCode)
        assertTrue(e.message.contains("Bad Gateway"))
    }

    // ----------------------------------------------------------------
    // multipart encoding (pure GroqMultipart object)
    // ----------------------------------------------------------------

    @Test
    fun `preamble contains contract fields in order`() {
        val preamble = String(
            GroqMultipart.buildPreamble(
                GroqWhisperClient.RequestParams(
                    apiKey = "gsk_test",
                    model = GroqWhisperClient.DEFAULT_MODEL,
                    language = "fa"
                ),
                boundary = "BOUNDARY123",
                fileName = "my-audio.mp3",
                partMime = "audio/mpeg"
            ),
            Charsets.UTF_8
        )
        val modelIdx = preamble.indexOf("name=\"model\"")
        val fmtIdx = preamble.indexOf("name=\"response_format\"")
        val gran1 = preamble.indexOf("name=\"timestamp_granularities[]\"")
        val gran2 = preamble.indexOf("name=\"timestamp_granularities[]\"", gran1 + 1)
        val fileIdx = preamble.indexOf("name=\"file\"")
        assertTrue(modelIdx in 0 until fmtIdx)
        assertTrue(fmtIdx in 0 until gran1)
        assertTrue(gran1 in 0 until gran2)
        assertTrue(gran2 in 0 until fileIdx)
        assertTrue(preamble.contains("whisper-large-v3-turbo"))
        assertTrue(preamble.contains("verbose_json"))
        assertTrue(preamble.contains("segment"))
        assertTrue(preamble.contains("word"))
        assertTrue(preamble.contains("fa"))
        assertTrue(preamble.contains("--BOUNDARY123"))
    }

    @Test
    fun `DEFAULT_MODEL is the pinned whisper-large-v3-turbo`() {
        assertEquals("whisper-large-v3-turbo", GroqWhisperClient.DEFAULT_MODEL)
        assertEquals(GroqWhisperClient.DEFAULT_MODEL, GroqWhisperClient.MODELS.first())
    }

    @Test
    fun `resolveModelId keeps known models and rejects stale or blank values`() {
        // known ids pass through
        assertEquals(
            "whisper-large-v3-turbo",
            GroqWhisperClient.resolveModelId("whisper-large-v3-turbo")
        )
        assertEquals(
            "whisper-large-v3",
            GroqWhisperClient.resolveModelId("whisper-large-v3")
        )
        // blank / unknown / stale values fall back to the pinned turbo
        assertEquals(
            GroqWhisperClient.DEFAULT_MODEL,
            GroqWhisperClient.resolveModelId(null)
        )
        assertEquals(
            GroqWhisperClient.DEFAULT_MODEL,
            GroqWhisperClient.resolveModelId("")
        )
        assertEquals(
            GroqWhisperClient.DEFAULT_MODEL,
            GroqWhisperClient.resolveModelId("   ")
        )
        assertEquals(
            GroqWhisperClient.DEFAULT_MODEL,
            GroqWhisperClient.resolveModelId("whisper-large-v2")
        )
        assertEquals(
            GroqWhisperClient.DEFAULT_MODEL,
            GroqWhisperClient.resolveModelId("gpt-4o-transcribe")
        )
        // surrounding whitespace is trimmed before the check
        assertEquals(
            "whisper-large-v3",
            GroqWhisperClient.resolveModelId("  whisper-large-v3  ")
        )
    }

    @Test
    fun `preamble serializes the model field with the exact id from RequestParams`() {
        val preamble = String(
            GroqMultipart.buildPreamble(
                GroqWhisperClient.RequestParams(
                    apiKey = "gsk_test",
                    model = GroqWhisperClient.DEFAULT_MODEL,
                    language = "fa"
                ),
                boundary = "B",
                fileName = "a.m4a",
                partMime = "audio/mp4"
            ),
            Charsets.UTF_8
        )
        // the model field value must be exactly whisper-large-v3-turbo
        assertTrue(
            preamble.contains("name=\"model\"\r\n\r\nwhisper-large-v3-turbo\r\n")
        )
    }

    @Test
    fun `preamble serializes language=fa as its own field when set`() {
        val preamble = String(
            GroqMultipart.buildPreamble(
                GroqWhisperClient.RequestParams(
                    apiKey = "gsk_test",
                    model = GroqWhisperClient.DEFAULT_MODEL,
                    language = "fa"
                ),
                boundary = "B",
                fileName = "a.m4a",
                partMime = "audio/mp4"
            ),
            Charsets.UTF_8
        )
        assertTrue(preamble.contains("name=\"language\"\r\n\r\nfa\r\n"))
    }

    @Test
    fun `file part carries the real filename and truthful content type`() {
        // 2026-09-13 FILENAME FIX — the regression behind Groq's
        // "file must be one of the following types" error: the part
        // used to be filename="audio" + application/octet-stream.
        val preamble = String(
            GroqMultipart.buildPreamble(
                GroqWhisperClient.RequestParams(
                    apiKey = "gsk_test",
                    model = "whisper-large-v3-turbo",
                    language = "fa"
                ),
                boundary = "B",
                fileName = "subtitle_audio_1760000000000.m4a",
                partMime = "audio/mp4"
            ),
            Charsets.UTF_8
        )
        assertTrue(preamble.contains("filename=\"subtitle_audio_1760000000000.m4a\""))
        assertTrue(preamble.contains("Content-Type: audio/mp4\r\n"))
        assertTrue(!preamble.contains("filename=\"audio\""))
        assertTrue(!preamble.contains("application/octet-stream"))
    }

    @Test
    fun `auto language omits the field entirely`() {
        val preamble = String(
            GroqMultipart.buildPreamble(
                GroqWhisperClient.RequestParams(
                    apiKey = "gsk_test",
                    model = "whisper-large-v3-turbo",
                    language = null
                ),
                boundary = "B",
                fileName = "a.mp3",
                partMime = "audio/mpeg"
            ),
            Charsets.UTF_8
        )
        assertTrue(!preamble.contains("name=\"language\""))
    }

    @Test
    fun `closing boundary terminates the multipart body`() {
        val closing = String(GroqMultipart.closingBoundary("B"), Charsets.UTF_8)
        assertEquals("\r\n--B--\r\n", closing)
    }

    // ----------------------------------------------------------------
    // endpoint normalization (user-editable API URL form)
    // ----------------------------------------------------------------

    @Test
    fun `blank url falls back to groq endpoint`() {
        assertEquals(
            GroqWhisperClient.ENDPOINT,
            GroqEndpoints.normalize("   ")
        )
    }

    @Test
    fun `valid https url is kept verbatim`() {
        val url = "https://api.openai.com/v1/audio/transcriptions"
        assertEquals(url, GroqEndpoints.normalize("  $url "))
    }

    @Test
    fun `malformed urls are rejected`() {
        assertNull(GroqEndpoints.normalize("not a url"))
        assertNull(GroqEndpoints.normalize("ftp://x"))
        assertNull(GroqEndpoints.normalize("https://"))
    }

    @Test
    fun `bare domain without path is rejected - real 404 cause`() {
        // Verified live: POST to the bare domain 404s; these must be
        // rejected at Apply time instead of silently persisting.
        assertNull(GroqEndpoints.normalize("https://api.groq.com"))
        assertNull(GroqEndpoints.normalize("https://api.groq.com/"))
    }

    @Test
    fun `full path is accepted`() {
        assertEquals(
            GroqWhisperClient.ENDPOINT,
            GroqEndpoints.normalize(GroqWhisperClient.ENDPOINT)
        )
    }
}
