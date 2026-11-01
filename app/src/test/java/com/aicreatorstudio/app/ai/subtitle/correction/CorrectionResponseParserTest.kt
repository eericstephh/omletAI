package com.aicreatorstudio.app.ai.subtitle.correction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Strict-validation tests for the model's reply.
 *
 * These are the tests that make "model output is untrusted data" a
 * checked property instead of a comment: every malformed shape the
 * brief lists (malformed JSON, missing ids, duplicated ids, unknown
 * ids, empty text, empty array, a chatty preamble) is asserted to be
 * REJECTED, and a well-formed reply is asserted to survive intact.
 */
class CorrectionResponseParserTest {

    private fun envelope(content: String): String =
        """{"id":"x","choices":[{"index":0,"message":{"role":"assistant","content":${quote(content)}}}]}"""

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun ok(content: String, ids: List<String>) =
        CorrectionResponseParser.parseSegments(content, ids)

    // ---- happy path ----

    @Test
    fun `well-formed reply is accepted in request order`() {
        val result = ok(
            """{"segments":[{"id":"a","text":"نرم‌افزار"},{"id":"b","text":"دشوار"}]}""",
            listOf("a", "b")
        )
        assertTrue(result is CorrectionResponseParser.Result.Ok)
        val segments = (result as CorrectionResponseParser.Result.Ok).segments
        assertEquals(listOf("a", "b"), segments.map { it.id })
        assertEquals("نرم‌افزار", segments[0].text)
        assertEquals("دشوار", segments[1].text)
    }

    @Test
    fun `half-space and zwnj survive validation untouched`() {
        // The whole point of the feature is Persian orthography; a
        // validator that stripped U+200C would silently undo it.
        val corrected = "کم‌کاربرد"
        val result = ok(
            """{"segments":[{"id":"a","text":"$corrected"}]}""",
            listOf("a")
        )
        val text = (result as CorrectionResponseParser.Result.Ok).segments[0].text
        assertEquals(corrected, text)
        assertTrue("ZWNJ must be preserved", text.contains('\u200C'))
    }

    @Test
    fun `chat completion envelope is unwrapped`() {
        val body = envelope("""{"segments":[{"id":"a","text":"خواهر"}]}""")
        val result = CorrectionResponseParser.parseChatCompletion(body, listOf("a"))
        assertTrue(result is CorrectionResponseParser.Result.Ok)
    }

    @Test
    fun `markdown fence is tolerated but nothing else is`() {
        val fenced = "```json\n{\"segments\":[{\"id\":\"a\",\"text\":\"تلفظ\"}]}\n```"
        assertTrue(ok(fenced, listOf("a")) is CorrectionResponseParser.Result.Ok)

        val withCommentary =
            "Sure! Here is the JSON:\n{\"segments\":[{\"id\":\"a\",\"text\":\"تلفظ\"}]}"
        assertTrue(
            "prose around the object must be rejected",
            ok(withCommentary, listOf("a")) is CorrectionResponseParser.Result.Malformed
        )
    }

    // ---- rejection cases ----

    @Test
    fun `empty response is rejected`() {
        assertTrue(ok("", listOf("a")) is CorrectionResponseParser.Result.Malformed)
        assertTrue(ok("   ", listOf("a")) is CorrectionResponseParser.Result.Malformed)
    }

    @Test
    fun `empty segments array is rejected`() {
        assertTrue(
            ok("""{"segments":[]}""", listOf("a")) is CorrectionResponseParser.Result.Malformed
        )
    }

    @Test
    fun `malformed json is rejected`() {
        assertTrue(
            ok("""{"segments":[{"id":"a","text":}]}""", listOf("a"))
                is CorrectionResponseParser.Result.Malformed
        )
        assertTrue(
            ok("not json at all", listOf("a")) is CorrectionResponseParser.Result.Malformed
        )
        // An array where an object is required.
        assertTrue(
            ok("""[{"id":"a","text":"x"}]""", listOf("a"))
                is CorrectionResponseParser.Result.Malformed
        )
    }

    @Test
    fun `missing id is rejected`() {
        val result = ok(
            """{"segments":[{"id":"a","text":"یک"}]}""",
            listOf("a", "b")
        )
        assertTrue(result is CorrectionResponseParser.Result.Malformed)
        assertTrue(
            (result as CorrectionResponseParser.Result.Malformed).reason.contains("تعداد")
        )
    }

    @Test
    fun `duplicated id is rejected`() {
        val result = ok(
            """{"segments":[{"id":"a","text":"یک"},{"id":"a","text":"دو"}]}""",
            listOf("a", "b")
        )
        assertTrue(result is CorrectionResponseParser.Result.Malformed)
    }

    @Test
    fun `unknown id is rejected`() {
        val result = ok(
            """{"segments":[{"id":"a","text":"یک"},{"id":"zzz","text":"دو"}]}""",
            listOf("a", "b")
        )
        assertTrue(result is CorrectionResponseParser.Result.Malformed)
        assertTrue(
            (result as CorrectionResponseParser.Result.Malformed).reason.contains("zzz")
        )
    }

    @Test
    fun `blank text is rejected`() {
        assertTrue(
            ok("""{"segments":[{"id":"a","text":"   "}]}""", listOf("a"))
                is CorrectionResponseParser.Result.Malformed
        )
    }

    @Test
    fun `non-string text is rejected`() {
        assertTrue(
            ok("""{"segments":[{"id":"a","text":42}]}""", listOf("a"))
                is CorrectionResponseParser.Result.Malformed
        )
    }

    @Test
    fun `missing segments key is rejected`() {
        assertTrue(
            ok("""{"result":[]}""", listOf("a")) is CorrectionResponseParser.Result.Malformed
        )
    }

    @Test
    fun `chat completion without choices is rejected`() {
        assertTrue(
            CorrectionResponseParser.parseChatCompletion("""{"error":"x"}""", listOf("a"))
                is CorrectionResponseParser.Result.Malformed
        )
        assertTrue(
            CorrectionResponseParser.parseChatCompletion("", listOf("a"))
                is CorrectionResponseParser.Result.Malformed
        )
    }

    // ---- whitespace normalisation ----

    @Test
    fun `line breaks collapse to spaces but zwnj is untouched`() {
        val result = ok(
            """{"segments":[{"id":"a","text":"کار\nآمد\tو\r\nرفت"}]}""",
            listOf("a")
        )
        val text = (result as CorrectionResponseParser.Result.Ok).segments[0].text
        assertEquals("کار آمد و رفت", text)

        assertEquals(
            "می\u200Cروم",
            CorrectionResponseParser.normalizeWhitespace("می\u200Cروم")
        )
    }
}
