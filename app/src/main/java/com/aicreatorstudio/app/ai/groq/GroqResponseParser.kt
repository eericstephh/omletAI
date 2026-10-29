package com.aicreatorstudio.app.ai.groq

import org.json.JSONObject

/**
 * Data model + parser for the Groq (OpenAI-compatible) audio
 * transcription endpoint `POST /openai/v1/audio/transcriptions`
 * with `response_format=verbose_json`.
 *
 * Parsing lives in a pure object so JVM unit tests can pin the
 * exact response shape against a real captured fixture.
 */
object GroqResponseParser {

    data class GroqWord(
        val word: String,
        val startSec: Double,
        val endSec: Double
    )

    data class GroqSegment(
        val id: Int,
        val startSec: Double,
        val endSec: Double,
        val text: String
    )

    data class GroqTranscription(
        val text: String,
        val language: String?,
        val durationSec: Double?,
        val segments: List<GroqSegment>,
        val words: List<GroqWord>,
        /** 2026-09-13 — WebVTT transcript when a backend provides
         *  one. Groq's verbose_json does not include `vtt`, so
         *  this is null on the Groq path. */
        val vtt: String? = null
    )

    /** One API failure, parsed from Groq's `{ "error": {...} }` body. */
    data class GroqError(
        val httpCode: Int,
        val message: String,
        val type: String?,
        val code: String?
    )

    /**
     * Parse a successful `verbose_json` body. Missing arrays are
     * tolerated (some models omit `words` unless the granularity
     * parameter was accepted).
     */
    fun parseVerboseJson(body: String): GroqTranscription {
        val root = JSONObject(body)
        val segments = mutableListOf<GroqSegment>()
        root.optJSONArray("segments")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                segments.add(
                    GroqSegment(
                        id = s.optInt("id", i),
                        startSec = s.optDouble("start", 0.0),
                        endSec = s.optDouble("end", 0.0),
                        text = s.optString("text", "").trim()
                    )
                )
            }
        }
        val words = mutableListOf<GroqWord>()
        root.optJSONArray("words")?.let { arr ->
            for (i in 0 until arr.length()) {
                val w = arr.optJSONObject(i) ?: continue
                words.add(
                    GroqWord(
                        word = w.optString("word", ""),
                        startSec = w.optDouble("start", 0.0),
                        endSec = w.optDouble("end", 0.0)
                    )
                )
            }
        }
        return GroqTranscription(
            text = root.optString("text", "").trim(),
            language = root.optStringOrNull("language"),
            durationSec = if (root.has("duration")) root.optDouble("duration") else null,
            segments = segments,
            words = words,
            vtt = null // Groq verbose_json has no vtt field
        )
    }

    /**
     * Parse an error body. Groq (and OpenAI-compatible proxies)
     * return `{ "error": { "message": ..., "type": ..., "code": ... } }`.
     * Non-JSON bodies fall back to a truncated raw-body message so
     * the user always sees the truth the server sent.
     */
    fun parseError(httpCode: Int, body: String): GroqError {
        return runCatching {
            val root = JSONObject(body)
            val err = root.optJSONObject("error") ?: JSONObject()
            GroqError(
                httpCode = httpCode,
                message = err.optString("message").ifBlank { body.take(300) },
                type = err.optStringOrNull("type"),
                code = err.optStringOrNull("code")
            )
        }.getOrElse {
            GroqError(
                httpCode = httpCode,
                message = body.take(300).ifBlank { "HTTP $httpCode" },
                type = null,
                code = null
            )
        }
    }

    private fun JSONObject.optStringOrNull(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name).takeIf { it.isNotBlank() }
    }
}
