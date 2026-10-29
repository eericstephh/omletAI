package com.aicreatorstudio.app.ai

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aicreatorstudio.app.ai.groq.GroqWhisperClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Real network test** — fires an actual request at the live
 * Groq endpoint. Without a key the server MUST answer HTTP 401
 * with the documented error shape; this pins the whole client
 * path (DNS → TLS → multipart → response parse) against the real
 * API. No mock server is involved.
 *
 * To exercise the full 200 path, enter a real key in the
 * Settings → "تست آنلاین Whisper" panel and run it manually.
 */
@RunWith(AndroidJUnit4::class)
class GroqApiInstrumentationTest {

    @Test
    fun realEndpointRespondsWithAuthErrorWithoutKey() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = GroqWhisperClient(context)

        // Build a real (tiny) in-memory WAV: 44-byte header + 100 ms
        // of silence. The server rejects it at the AUTH layer before
        // looking at the audio, which is exactly what this asserts.
        val wav = makeTinyWav()
        val dir = context.cacheDir.resolve("groqtest").apply { mkdirs() }
        val file = java.io.File(dir, "silence.wav").apply { writeBytes(wav) }

        val outcome = runBlocking {
            client.transcribe(
                uri = android.net.Uri.fromFile(file),
                params = GroqWhisperClient.RequestParams(
                    apiKey = "gsk_invalid_key_for_auth_probe",
                    model = "whisper-large-v3",
                    language = null
                ),
                listener = object : GroqWhisperClient.Listener {
                    override fun onPhase(phase: GroqWhisperClient.Phase, progress: Float) {}
                }
            )
        }
        file.delete()
        // The real server's answer must be a parsed API error —
        // anything else (network error, success) fails the test.
        assertTrue(
            "expected a real API error, got: $outcome",
            outcome is GroqWhisperClient.Outcome.ApiError
        )
        val err = (outcome as GroqWhisperClient.Outcome.ApiError).error
        assertTrue(
            "expected 401/403 from the real endpoint, got ${err.httpCode}",
            err.httpCode == 401 || err.httpCode == 403
        )
        assertTrue(err.message.isNotBlank())
    }

    /** 44-byte PCM WAV header + 100 ms of 16-bit mono 16 kHz silence. */
    private fun makeTinyWav(): ByteArray {
        val sampleRate = 16000
        val samples = sampleRate / 10
        val dataSize = samples * 2
        val out = java.io.ByteArrayOutputStream()
        fun le32(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        out.write("RIFF".toByteArray()); le32(36 + dataSize)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16)
        le16(1); le16(1); le32(sampleRate); le32(sampleRate * 2); le16(2); le16(16)
        out.write("data".toByteArray()); le32(dataSize)
        repeat(samples) { le16(0) }
        return out.toByteArray()
    }
}
