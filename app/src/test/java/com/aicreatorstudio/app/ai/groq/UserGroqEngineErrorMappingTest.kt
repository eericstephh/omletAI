package com.aicreatorstudio.app.ai.groq

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [UserGroqEngine.persianMessageFor]'s HTTP-status → Persian
 * message contract (pure, no network).
 */
class UserGroqEngineErrorMappingTest {

    private fun err(code: Int) = GroqResponseParser.GroqError(
        httpCode = code,
        message = "server says something",
        type = null,
        code = null
    )

    @Test
    fun `401 and 403 point the user at the settings path`() {
        listOf(401, 403).forEach { code ->
            val msg = UserGroqEngine.persianMessageFor(err(code))
            assertTrue("code $code", msg.contains("کلید API نامعتبر"))
            assertTrue("code $code", msg.contains("اتصال هوش مصنوعی"))
        }
    }

    @Test
    fun `413 mentions the 25MB limit`() {
        val msg = UserGroqEngine.persianMessageFor(err(413))
        assertTrue(msg.contains("۲۵"))
    }

    @Test
    fun `429 is a rate-limit message`() {
        val msg = UserGroqEngine.persianMessageFor(err(429))
        assertTrue(msg.contains("محدودیت نرخ"))
    }

    @Test
    fun `5xx carries the status code`() {
        listOf(500, 502, 503).forEach { code ->
            val msg = UserGroqEngine.persianMessageFor(err(code))
            assertTrue("code $code", msg.contains(code.toString()))
        }
    }

    @Test
    fun `400 and unknown codes fall back to the server message`() {
        assertTrue(UserGroqEngine.persianMessageFor(err(400)).contains("server says something"))
        assertTrue(UserGroqEngine.persianMessageFor(err(418)).contains("server says something"))
    }
}
