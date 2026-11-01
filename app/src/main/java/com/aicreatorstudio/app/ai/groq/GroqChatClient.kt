package com.aicreatorstudio.app.ai.groq

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal client for Groq's **OpenAI-compatible chat-completions**
 * endpoint.
 *
 * ## Why a separate client, not a reuse of [GroqWhisperClient]
 *
 * They share nothing but the host. `GroqWhisperClient` streams a
 * multipart body from a `content://` URI and parses `verbose_json`
 * transcriptions; this one posts a small JSON document and reads
 * `choices[0].message.content`. Folding them together would mean one
 * class that is wrong for both callers. What they DO share — the
 * stored endpoint, the key, the error taxonomy — is reused rather
 * than duplicated: the URL comes from [GroqEndpoints.chatUrl] applied
 * to the same stored endpoint the transcriber uses, so "the server
 * that transcribed" and "the server that corrects" can never
 * disagree.
 *
 * ## Security
 *
 * - The key travels only in `Authorization: Bearer`.
 * - Neither the key nor the subtitle text is logged. The only log
 *   line carries the model id, the HTTP code and the elapsed time.
 * - No retry loop: a 429 must NOT be hammered (the brief forbids
 *   automatic retries that burn quota).
 *
 * ## Cancellation
 *
 * `conn.responseCode` is a blocking socket read that coroutine
 * cancellation does not interrupt, so the connection is torn down
 * from a completion handler on the calling job — the same technique
 * [GroqWhisperClient] uses for the 300 s transcription wait.
 */
@Singleton
class GroqChatClient @Inject constructor() :
    com.aicreatorstudio.app.ai.subtitle.correction.ChatCompletionClient {

    /**
     * One request.
     *
     * @property endpoint full chat-completions URL; the caller derives
     *   it from the stored transcription endpoint.
     * @property maxTokens completion cap. A batch reply is ~1.3× the
     *   input length, so this only needs to be generous, not huge.
     */
    data class Request(
        val apiKey: String,
        val model: String,
        val systemPrompt: String,
        val userPrompt: String,
        val endpoint: String = GroqEndpoints.DEFAULT_CHAT_URL,
        val maxTokens: Int = 2_048
    )

    sealed interface Outcome {
        /** The raw `message.content` string. Unvalidated — see the parser. */
        data class Success(val content: String) : Outcome

        /** Non-2xx. [httpCode] is 0 when the failure had no HTTP status. */
        data class ApiError(val httpCode: Int, val message: String) : Outcome

        data class NetworkError(val message: String) : Outcome

        data object Cancelled : Outcome
    }

    override suspend fun complete(request: Request): Outcome = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val body = buildBody(request)
            val startedAt = System.currentTimeMillis()

            val conn = (URL(request.endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                if (request.apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${request.apiKey}")
                }
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                connection = this
            }

            // Cancellation hook — aborts the blocked read immediately.
            val hostJob = currentCoroutineContext()[Job]
            hostJob?.invokeOnCompletion { runCatching { conn.disconnect() } }

            conn.outputStream.use { out ->
                out.write(body.toByteArray(Charsets.UTF_8))
                out.flush()
            }

            if (hostJob?.isActive == false) return@withContext Outcome.Cancelled

            val code = conn.responseCode
            val elapsed = System.currentTimeMillis() - startedAt
            // Safe diagnostics only: model, status, timing. Never the
            // key, never the subtitle text.
            Log.i(TAG, "chat: model=${request.model} http=$code elapsedMs=$elapsed")

            if (hostJob?.isActive == false) return@withContext Outcome.Cancelled

            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val responseBody = stream?.bufferedReader()?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                return@withContext Outcome.ApiError(code, errorMessage(code, responseBody))
            }

            val content = CorrectionContentExtractor.extract(responseBody)
            if (content == null) {
                return@withContext Outcome.ApiError(
                    code,
                    "پاسخ سرور قابل خواندن نبود: " +
                        responseBody.take(200).ifBlank { "(بدون محتوا)" }
                )
            }
            Outcome.Success(content)
        } catch (e: IOException) {
            if (currentCoroutineContext()[Job]?.isActive != true) {
                Outcome.Cancelled
            } else {
                Log.w(TAG, "chat request network failure", e)
                Outcome.NetworkError(
                    "خطای شبکه: ${e.message ?: e::class.java.simpleName} " +
                        "(اینترنت و دسترسی به api.groq.com را بررسی کنید)"
                )
            }
        } catch (e: Exception) {
            if (currentCoroutineContext()[Job]?.isActive != true) {
                Outcome.Cancelled
            } else {
                Log.w(TAG, "chat request failed", e)
                Outcome.NetworkError(e.message ?: e::class.java.simpleName)
            }
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /**
     * The request body.
     *
     * `response_format = {"type":"json_object"}` is JSON mode: broadly
     * supported across Groq's chat models, and it removes the most
     * common failure (a chatty preamble). It is NOT a guarantee — the
     * reply is still parsed strictly by
     * `CorrectionResponseParser`, because a schema-shaped request is
     * a request, not a contract.
     *
     * `temperature = 0` because this is a proofreading task: the same
     * input must produce the same output, or a re-run would silently
     * disagree with the preview the user just approved.
     */
    internal fun buildBody(request: Request): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", request.systemPrompt))
        messages.put(JSONObject().put("role", "user").put("content", request.userPrompt))

        return JSONObject()
            .put("model", request.model)
            .put("messages", messages)
            .put("temperature", 0)
            .put("max_tokens", request.maxTokens)
            .put("response_format", JSONObject().put("type", "json_object"))
            .toString()
    }

    /**
     * Map a non-2xx reply to a user-facing Persian message.
     *
     * 404 and a 400 that mentions the model are both "this model is
     * not available to you" — the two cases Groq uses depending on
     * whether the id is unknown or merely unentitled. Both tell the
     * user to pick another model rather than to check their internet.
     */
    internal fun errorMessage(code: Int, body: String): String {
        val server = extractServerMessage(body)
        val looksLikeModelProblem =
            body.contains("model", ignoreCase = true) &&
                (body.contains("not found", ignoreCase = true) ||
                    body.contains("does not exist", ignoreCase = true) ||
                    body.contains("decommissioned", ignoreCase = true) ||
                    body.contains("not supported", ignoreCase = true))

        return when {
            code == 401 || code == 403 ->
                "کلید API نامعتبر است یا دسترسی ندارد. کلید را در «اتصال هوش مصنوعی» بررسی کنید."
            code == 404 || (code == 400 && looksLikeModelProblem) ->
                "این مدل روی حساب شما در دسترس نیست. مدل دیگری از فهرست انتخاب کنید." +
                    (server?.let { " ($it)" } ?: "")
            code == 429 ->
                "محدودیت نرخ Groq. کمی صبر کنید و دوباره تلاش کنید."
            code == 413 ->
                "حجم متن ارسالی بیش از حد مجاز است."
            code in 500..599 ->
                "سرویس Groq موقتاً در دسترس نیست (HTTP $code). بعداً دوباره تلاش کنید."
            else -> server ?: "خطای سرور (HTTP $code)"
        }
    }

    /** `error.message` from the OpenAI-compatible error envelope. */
    private fun extractServerMessage(body: String): String? = runCatching {
        val root = JSONObject(body)
        root.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    companion object {
        private const val TAG = "GroqChatClient"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 90_000
    }
}

/**
 * Pulls `choices[0].message.content` out of a chat-completions body.
 *
 * Split out so [GroqChatClient] has no parsing logic of its own and
 * the extraction can be reused by the strict parser.
 */
internal object CorrectionContentExtractor {

    fun extract(body: String): String? = runCatching {
        val root = JSONObject(body)
        val choices = root.optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null
        val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return null
        message.optString("content", "").takeIf { it.isNotBlank() }
    }.getOrNull()
}
