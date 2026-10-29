package com.aicreatorstudio.app.ai.groq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests pinning [GroqConnectionTester.interpret]'s status-code
 * contract. Pure — no network, no Android.
 */
class GroqConnectionTesterTest {

    private val tester = GroqConnectionTester()

    @Test
    fun `2xx is success and counts models`() {
        val body = """{"data":[{"id":"whisper-large-v3-turbo"},{"id":"llama-3"}]}"""
        val v = tester.interpret(200, body)
        assertTrue("expected Success, got $v", v is GroqConnectionTester.Verdict.Success)
        assertEquals(2, (v as GroqConnectionTester.Verdict.Success).modelCount)
        assertTrue(v.message.contains("معتبر"))
    }

    @Test
    fun `2xx with unparseable body is still success with zero count`() {
        val v = tester.interpret(204, "")
        assertTrue(v is GroqConnectionTester.Verdict.Success)
        assertEquals(0, (v as GroqConnectionTester.Verdict.Success).modelCount)
    }

    @Test
    fun `401 is invalid key`() {
        val v = tester.interpret(401, """{"error":{"message":"Invalid API Key"}}""")
        assertTrue(v is GroqConnectionTester.Verdict.InvalidKey)
        assertTrue(v.message.contains("نامعتبر"))
    }

    @Test
    fun `403 is invalid key`() {
        val v = tester.interpret(403, "")
        assertTrue(v is GroqConnectionTester.Verdict.InvalidKey)
    }

    @Test
    fun `429 is rate limited - key is valid`() {
        val v = tester.interpret(429, "")
        assertTrue(v is GroqConnectionTester.Verdict.RateLimited)
        assertTrue(v.message.contains("محدودیت"))
    }

    @Test
    fun `500 and 503 are server errors carrying the code`() {
        listOf(500, 502, 503).forEach { code ->
            val v = tester.interpret(code, "")
            assertTrue("code $code", v is GroqConnectionTester.Verdict.ServerError)
            assertTrue(v.message.contains(code.toString()))
        }
    }

    @Test
    fun `other codes fall through to unexpected`() {
        val v = tester.interpret(404, "")
        assertTrue(v is GroqConnectionTester.Verdict.Unexpected)
        assertTrue(v.message.contains("404"))
    }

    @Test
    fun `models endpoint is the documented https url`() {
        assertEquals("https://api.groq.com/openai/v1/models", GroqConnectionTester.MODELS_ENDPOINT)
    }

    @Test
    fun `no verdict message ever contains a gsk-style key`() {
        // interpret never receives the key; this pins the contract
        // that messages are built only from code + body.
        val samples = listOf(200, 401, 403, 429, 500, 503, 418)
        samples.forEach { code ->
            val msg = tester.interpret(code, "{}").message
            assertTrue("code $code", !msg.contains("gsk_"))
        }
    }
}
