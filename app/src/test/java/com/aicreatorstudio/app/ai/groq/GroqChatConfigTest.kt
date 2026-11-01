package com.aicreatorstudio.app.ai.groq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Model resolution and endpoint derivation for the chat side.
 *
 * Both are pure, and both have a failure mode that looks like a network
 * problem to the user: an unknown model id produces a 404, and a wrong
 * chat URL produces a 404 too. Pinning them here keeps "the feature is
 * broken" from being the first symptom.
 */
class GroqChatConfigTest {

    // ---- model catalog ----

    @Test
    fun `default is the brief's preferred model`() {
        assertEquals("openai/gpt-oss-20b", GroqChatModels.DEFAULT)
        assertEquals(GroqChatModels.DEFAULT, GroqChatModels.resolveModelId(null))
        assertEquals(GroqChatModels.DEFAULT, GroqChatModels.resolveModelId(""))
        assertEquals(GroqChatModels.DEFAULT, GroqChatModels.resolveModelId("   "))
    }

    @Test
    fun `a known model is honoured`() {
        assertEquals(
            "llama-3.3-70b-versatile",
            GroqChatModels.resolveModelId("llama-3.3-70b-versatile")
        )
        assertTrue(GroqChatModels.isKnown("openai/gpt-oss-120b"))
    }

    @Test
    fun `an unknown or stale model falls back instead of reaching the wire`() {
        assertEquals(
            GroqChatModels.DEFAULT,
            GroqChatModels.resolveModelId("some/retired-model")
        )
        assertFalse(GroqChatModels.isKnown("some/retired-model"))
        // A transcription model id must never be forwarded to a chat
        // endpoint — that is exactly the mistake the brief forbids.
        assertEquals(
            GroqChatModels.DEFAULT,
            GroqChatModels.resolveModelId("whisper-large-v3-turbo")
        )
    }

    @Test
    fun `every catalog entry has a label and a hint`() {
        for (model in GroqChatModels.CATALOG) {
            assertTrue(model.id.isNotBlank())
            assertTrue(model.label.isNotBlank())
            assertTrue(model.hint.isNotBlank())
        }
    }

    @Test
    fun `entryFor resolves to a real catalog entry`() {
        assertEquals(
            GroqChatModels.DEFAULT,
            GroqChatModels.entryFor("nonsense").id
        )
    }

    // ---- chat URL derivation ----

    @Test
    fun `the groq transcription endpoint derives the groq chat endpoint`() {
        assertEquals(
            "https://api.groq.com/openai/v1/chat/completions",
            GroqEndpoints.chatUrl("https://api.groq.com/openai/v1/audio/transcriptions")
        )
    }

    @Test
    fun `a custom endpoint derives its own chat url`() {
        assertEquals(
            "https://example.test/v1/chat/completions",
            GroqEndpoints.chatUrl("https://example.test/v1/audio/transcriptions")
        )
        assertEquals(
            "https://example.test:8443/v1/chat/completions",
            GroqEndpoints.chatUrl("https://example.test:8443/v1/transcriptions")
        )
    }

    @Test
    fun `a bare path gets the chat suffix appended`() {
        assertEquals(
            "https://example.test/custom/chat/completions",
            GroqEndpoints.chatUrl("https://example.test/custom")
        )
    }

    @Test
    fun `malformed or blank endpoints fall back to the groq default`() {
        assertEquals(GroqEndpoints.DEFAULT_CHAT_URL, GroqEndpoints.chatUrl(""))
        assertEquals(GroqEndpoints.DEFAULT_CHAT_URL, GroqEndpoints.chatUrl("   "))
        assertEquals(GroqEndpoints.DEFAULT_CHAT_URL, GroqEndpoints.chatUrl("not a url"))
        assertEquals(GroqEndpoints.DEFAULT_CHAT_URL, GroqEndpoints.chatUrl("ftp://x/y"))
    }

    @Test
    fun `chat url is never the transcription url`() {
        val chat = GroqEndpoints.chatUrl(GroqWhisperClient.ENDPOINT)
        assertFalse(chat.contains("/audio/transcriptions"))
        assertTrue(chat.endsWith("/chat/completions"))
    }
}
