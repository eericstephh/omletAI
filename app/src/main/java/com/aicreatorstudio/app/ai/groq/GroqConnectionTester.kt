package com.aicreatorstudio.app.ai.groq

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real connection test for a user-supplied Groq API key.
 *
 * Uses `GET <models-url>` - the documented list-models endpoint. It
 * authenticates with the key but does NOT consume transcription
 * quota, so the user can verify their key before ever generating
 * subtitles.
 *
 * ## 2026-10-20 - the tested endpoint is the STORED endpoint
 *
 * The tester used to hard-code `https://api.groq.com/openai/v1/models`
 * while the transcription request used the (user-configurable)
 * endpoint stored in [GroqApiKeyStore]. That made the "test" and the
 * "use" impossible to disagree about, but it also made the test
 * meaningless for any non-Groq server: a key could be reported as
 * valid against Groq while the real transcription went somewhere
 * else entirely.
 *
 * The tester now derives its list-models URL from the SAME stored
 * endpoint that the transcription request uses
 * ([GroqApiKeyStore.endpoint] -> [GroqEndpoints.modelsUrl]), so the
 * two always talk to the same server. With the default endpoint the
 * derived URL is exactly [MODELS_ENDPOINT] - the previous behaviour.
 *
 * ## Security
 *
 * - The key is sent ONLY in the `Authorization: Bearer` header and
 *   never in a URL. The default endpoint is HTTPS; a user-configured
 *   plain-http endpoint is honoured but logged as a warning (the key
 *   then travels in clear text).
 * - The key is never logged, never put in a URL, and never stored
 *   by this class (persistence is [GroqApiKeyStore]'s job).
 *
 * ## Testability
 *
 * [interpret] is a pure function from (HTTP code, body) to a
 * [Verdict], so every status-code branch is JVM-testable without
 * a network. [testConnection] is the thin HTTP shell around it.
 * The models URL is resolved through a provider lambda so the class
 * can still be constructed in a plain JVM unit test (no Keystore,
 * no Android context).
 */
@Singleton
class GroqConnectionTester private constructor(
    /**
     * Resolves the list-models URL to test against. Injected as a
     * lambda so JVM tests can construct the tester without a
     * Keystore-backed [GroqApiKeyStore].
     */
    private val modelsUrlProvider: () -> String
) {

    /**
     * Hilt/Dagger entry point.
     *
     * 2026-10-20 - the tester honours the endpoint the user
     * configured instead of always hitting the Groq constant, so
     * "test connection" and the real transcription request always
     * target the same server.
     */
    @Inject
    constructor(apiKeyStore: GroqApiKeyStore) : this({
        GroqEndpoints.modelsUrl(apiKeyStore.endpoint())
    })

    /** Test-only constructor: always the default Groq endpoint. */
    constructor() : this({ MODELS_ENDPOINT })

    /** Outcome of a connection test, with a user-facing Persian message. */
    sealed interface Verdict {
        val message: String

        /** Key authenticated successfully (HTTP 2xx). */
        data class Success(val modelCount: Int) : Verdict {
            override val message: String =
                "اتصال برقرار است. کلید معتبر است ($modelCount مدل در دسترس)."
        }

        /** Key missing/invalid (HTTP 401 or 403). */
        data class InvalidKey(val httpCode: Int) : Verdict {
            override val message: String =
                "کلید API نامعتبر است یا دسترسی ندارد. کلید را بررسی و دوباره وارد کنید."
        }

        /** Rate limited (HTTP 429) - the key itself is fine. */
        object RateLimited : Verdict {
            override val message: String =
                "درخواست زیاد است (محدودیت نرخ Groq). کلید معتبر است؛ کمی بعد دوباره تست کنید."
        }

        /** Server-side failure (HTTP 5xx). */
        data class ServerError(val httpCode: Int) : Verdict {
            override val message: String =
                "سرویس Groq موقتاً در دسترس نیست (HTTP $httpCode). بعداً دوباره تست کنید."
        }

        /** Any other non-2xx response. */
        data class Unexpected(val httpCode: Int) : Verdict {
            override val message: String =
                "پاسخ غیرمنتظره از سرور (HTTP $httpCode). کلید و اتصال شبکه را بررسی کنید."
        }

        /** Network unreachable / timeout / DNS. */
        data class Network(val reason: String) : Verdict {
            override val message: String =
                "خطای شبکه: $reason — اینترنت و تحریم‌شکن را بررسی کنید."
        }
    }

    /**
     * Perform the live connection test with [apiKey].
     *
     * @throws IllegalStateException when [apiKey] is blank (caller
     *   must validate first - a blank key is a programming error,
     *   not a network condition).
     */
    suspend fun testConnection(apiKey: String): Verdict = withContext(Dispatchers.IO) {
        check(apiKey.isNotBlank()) { "testConnection called with a blank key" }
        val target = modelsUrl()
        var connection: HttpURLConnection? = null
        try {
            val conn = (URL(target).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "application/json")
                connection = this
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            interpret(code, body)
        } catch (e: SocketTimeoutException) {
            Verdict.Network("مهلت زمانی تمام شد")
        } catch (e: IOException) {
            Verdict.Network(e.message ?: e::class.java.simpleName)
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /**
     * The list-models URL this tester hits right now.
     *
     * 2026-10-20 - derived from the user's stored endpoint (the
     * default Groq endpoint yields exactly [MODELS_ENDPOINT]). Read
     * on every call so a changed endpoint takes effect immediately.
     */
    internal fun modelsUrl(): String {
        val url = try {
            modelsUrlProvider()
        } catch (t: Throwable) {
            Log.w(TAG, "Could not resolve the stored endpoint; falling back to the Groq default", t)
            MODELS_ENDPOINT
        }
        val resolved = url.trim().ifBlank { MODELS_ENDPOINT }
        if (!resolved.startsWith("https://")) {
            // The key would travel in clear text - only possible when
            // the user explicitly stored a plain-http endpoint.
            Log.w(TAG, "Connection test against a non-HTTPS endpoint: $resolved")
        }
        return resolved
    }

    /**
     * Pure mapping of an HTTP response to a [Verdict]. Never sees
     * the API key, so it cannot leak it into a message.
     *
     * Branches per the task contract:
     * 2xx -> Success - 401/403 -> InvalidKey - 429 -> RateLimited
     * (key is valid) - 5xx -> ServerError - else -> Unexpected.
     */
    internal fun interpret(httpCode: Int, body: String): Verdict = when {
        httpCode in 200..299 -> Verdict.Success(modelCount = countModels(body))
        httpCode == 401 || httpCode == 403 -> Verdict.InvalidKey(httpCode)
        httpCode == 429 -> Verdict.RateLimited
        httpCode in 500..599 -> Verdict.ServerError(httpCode)
        else -> Verdict.Unexpected(httpCode)
    }

    /** Count entries in the list-models `{"data":[{...},...]}` body. */
    private fun countModels(body: String): Int = runCatching {
        val root = org.json.JSONObject(body)
        root.optJSONArray("data")?.length() ?: 0
    }.getOrDefault(0)

    companion object {
        private const val TAG = "GroqConnectionTester"

        /** Groq's documented list-models endpoint (GET, quota-free). */
        const val MODELS_ENDPOINT = "https://api.groq.com/openai/v1/models"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
    }
}