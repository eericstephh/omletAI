package com.aicreatorstudio.app.ai.subtitle.cloud

import android.util.Log
import com.aicreatorstudio.app.ai.groq.GroqUploadTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * 2026-10-10 ACCOUNT-FREE — this Cloudflare Worker service is no
 * longer part of the active subtitle flow (the user-key Groq
 * engine replaced it). Kept only for its [TranscriptionResponse]
 * data types (shared with the Groq response parser) and for the
 * dormant Worker contract tests. The Supabase session dependency
 * was removed; the optional [tokenProvider] defaults to a mock
 * so JVM tests still construct it.
 */
class CloudWhisperService(
    private val tokenProvider: () -> String?
) {

    // Internal no-arg constructor for JVM unit tests.
    internal constructor() : this({ "mock_test_token" })

    interface Listener {
        fun onUploadProgress(fraction: Float)
        fun onAwaitingResponse()
    }

    data class TranscriptionResponse(
        val text: String,
        val language: String?,
        val durationSec: Double?,
        val segments: List<Segment>,
        val words: List<Word>,
        val vtt: String?
    ) {
        data class Segment(val startSec: Double, val endSec: Double, val text: String)
        data class Word(val word: String, val startSec: Double, val endSec: Double)
    }

    data class TranscriptionError(
        val userMessage: String,
        val httpCode: Int? = null,
        val rawBody: String? = null,
        val cause: Exception? = null
    )

    sealed interface Outcome {
        data class Success(val response: TranscriptionResponse) : Outcome
        data class Failure(val error: TranscriptionError) : Outcome
    }

    suspend fun transcribe(
        audioFile: File,
        apiKey: String? = null,
        mimeType: String,
        language: String?,
        listener: Listener
    ): Outcome = withContext(Dispatchers.IO) {
        val token = tokenProvider()
        if (token.isNullOrBlank()) {
            return@withContext Outcome.Failure(
                TranscriptionError(
                    userMessage = "برای رونویسی ابری باید کلید Groq تنظیم شود."
                )
            )
        }

        val size = audioFile.length()
        if (size <= 0L) {
            return@withContext Outcome.Failure(
                TranscriptionError(userMessage = "فایل صوتی خالی است و قابل ارسال نیست.")
            )
        }
        if (size > MAX_UPLOAD_BYTES) {
            return@withContext Outcome.Failure(
                TranscriptionError(
                    userMessage =
                        "فایل صوتی بسیار بزرگ است و قابل ارسال نیست " +
                            "(${size / (1024 * 1024)} MB)."
                )
            )
        }

        val rawName = audioFile.name
        val sniffed = runCatching {
            audioFile.inputStream().use { input ->
                val head = ByteArray(16)
                var filled = 0
                while (filled < head.size) {
                    val n = input.read(head, filled, head.size - filled)
                    if (n <= 0) break
                    filled += n
                }
                if (filled == 0) null else GroqUploadTypes.sniffExtension(head.copyOf(filled))
            }
        }.getOrNull()

        val fileName = GroqUploadTypes.resolveUploadFileName(
            rawName = rawName,
            mimeType = mimeType,
            pathSuffixExtension = GroqUploadTypes.extensionOf(rawName),
            sniffedExtension = sniffed
        ) ?: "audio.wav"

        val partMime = GroqUploadTypes.mimeTypeForExtension(
            GroqUploadTypes.extensionOf(fileName)
        ) ?: mimeType

        var connection: HttpURLConnection? = null
        val startedAt = android.os.SystemClock.elapsedRealtime()

        try {
            val boundary = "AICS${System.currentTimeMillis().toString(16)}"
            val preamble = buildPreamble(boundary, fileName, partMime, language)
            val closing = closingBoundary(boundary)
            val contentLength = preamble.size.toLong() + size + closing.size.toLong()

            Log.i(
                TAG,
                "Cloud Whisper upload: worker=$ENDPOINT bytes=$size (fileName=$fileName)"
            )

            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("Content-Length", contentLength.toString())
                setRequestProperty("Accept", "application/json")
                setFixedLengthStreamingMode(contentLength)
                connection = this
            }

            listener.onUploadProgress(0f)
            conn.outputStream.use { out ->
                out.write(preamble)
                audioFile.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        if (currentCoroutineContext()[Job]?.isActive != true) {
                            runCatching { conn.disconnect() }
                            return@withContext cancelled()
                        }
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        written += n
                        listener.onUploadProgress(
                            (written.toFloat() / size).coerceIn(0f, 1f)
                        )
                    }
                }
                out.write(closing)
                out.flush()
            }
            listener.onAwaitingResponse()

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""

            Log.i(
                TAG,
                "Worker response: http=$code elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt}"
            )

            if (code in 200..299) {
                parseSuccess(body)
            } else {
                Outcome.Failure(mapHttpError(code, body))
            }
        } catch (e: SocketTimeoutException) {
            Outcome.Failure(
                TranscriptionError(
                    userMessage = "پاسخی از سرور دریافت نشد (Timeout). اتصال اینترنت را بررسی کنید.",
                    cause = e
                )
            )
        } catch (e: UnknownHostException) {
            Outcome.Failure(
                TranscriptionError(
                    userMessage = "اتصال اینترنت برقرار نشد. اتصال خود را بررسی کنید یا شبکه را تغییر دهید.",
                    cause = e
                )
            )
        } catch (e: java.net.ConnectException) {
            Outcome.Failure(
                TranscriptionError(
                    userMessage = "اتصال به سرور برقرار نشد. دوباره تلاش کنید یا شبکه را عوض کنید.",
                    cause = e
                )
            )
        } catch (e: IOException) {
            if (currentCoroutineContext()[Job]?.isActive != true) {
                cancelled()
            } else {
                Log.w(TAG, "Network failure", e)
                Outcome.Failure(
                    TranscriptionError(
                        userMessage = "خطای شبکه در ارسال: ${e.message ?: e::class.java.simpleName}",
                        cause = e
                    )
                )
            }
        } catch (e: Exception) {
            if (currentCoroutineContext()[Job]?.isActive != true) {
                cancelled()
            } else {
                Log.w(TAG, "Request failed", e)
                Outcome.Failure(
                    TranscriptionError(
                        userMessage = "خطای غیرمنتظره: ${e.message ?: e::class.java.simpleName}",
                        cause = e
                    )
                )
            }
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun cancelled(): Outcome =
        Outcome.Failure(TranscriptionError(userMessage = "درخواست لغو شد."))

    internal fun buildPreamble(
        boundary: String,
        fileName: String,
        mimeType: String,
        language: String?
    ): ByteArray {
        val sb = StringBuilder()
        fun field(name: String, value: String) {
            sb.append("--").append(boundary).append(CRLF)
            sb.append("Content-Disposition: form-data; name=\"")
                .append(name).append("\"").append(CRLF).append(CRLF)
            sb.append(value).append(CRLF)
        }
        field("model", "whisper-large-v3-turbo")
        field("response_format", "verbose_json")
        field("timestamp_granularities[]", "segment")
        field("timestamp_granularities[]", "word")
        // 2026-10-20 — see GroqWhisperClient.buildPreamble: the
        // `prompt` field is imitated, not obeyed, so the old
        // instruction block was a hallucination source. Single
        // source of truth is WhisperPrompt.PRIMER.
        field("prompt", com.aicreatorstudio.app.ai.audio.WhisperPrompt.PRIMER)
        language?.takeIf { it.isNotBlank() }?.let { field("language", it) }

        sb.append("--").append(boundary).append(CRLF)
        sb.append("Content-Disposition: form-data; name=\"audio\"; ")
            .append("filename=\"").append(fileName).append("\"").append(CRLF)
        sb.append("Content-Type: ").append(mimeType).append(CRLF).append(CRLF)
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    internal fun closingBoundary(boundary: String): ByteArray =
        "$CRLF--$boundary--$CRLF".toByteArray(Charsets.UTF_8)

    internal fun parseSuccess(body: String): Outcome {
        if (body.isBlank()) {
            return Outcome.Failure(
                TranscriptionError(
                    userMessage = "پاسخ سرور خالی بود.",
                    rawBody = body
                )
            )
        }
        return try {
            val root = JSONObject(body)
            val text = (if (root.has("transcript")) root.optString("transcript", "") else root.optString("text", "")).trim()
            val segments = ArrayList<TranscriptionResponse.Segment>()
            root.optJSONArray("segments")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    val t = s.optString("text", "").trim()
                    if (t.isEmpty()) continue
                    segments.add(
                        TranscriptionResponse.Segment(
                            startSec = s.optDouble("start", 0.0),
                            endSec = s.optDouble("end", 0.0),
                            text = t
                        )
                    )
                }
            }

            val durationSec = if (root.has("duration_seconds")) root.optDouble("duration_seconds", 0.0) else null
            val detectedLang = root.optString("language").takeIf { it.isNotBlank() }

            // 2026-09-19 WORD TIMINGS — parse the optional verbose
            // `words[]` array ({word, start, end}) when the backend
            // provides it. Absent (the common worker shape) ⇒ empty
            // list, and the pipeline falls back to proportional
            // word-less chunking downstream. Never invented here:
            // only real backend timestamps are attached.
            val words = ArrayList<TranscriptionResponse.Word>()
            root.optJSONArray("words")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val w = arr.optJSONObject(i) ?: continue
                    val t = w.optString("word", "").trim()
                    if (t.isEmpty()) continue
                    val start = w.optDouble("start", 0.0)
                    val end = w.optDouble("end", 0.0)
                    if (end <= start) continue
                    words.add(
                        TranscriptionResponse.Word(
                            word = t,
                            startSec = start,
                            endSec = end
                        )
                    )
                }
            }

            Outcome.Success(
                TranscriptionResponse(
                    text = text,
                    language = detectedLang,
                    durationSec = durationSec,
                    segments = segments,
                    words = words,
                    vtt = null
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing worker response", e)
            Outcome.Failure(
                TranscriptionError(
                    userMessage = "خطا در تجزیه پاسخ سرور.",
                    rawBody = body,
                    cause = e
                )
            )
        }
    }

    internal fun mapHttpError(code: Int, body: String): TranscriptionError {
        val parsedMsg = runCatching {
            JSONObject(body).optString("error", "")
        }.getOrNull().orEmpty()

        val msg = when (code) {
            401 -> "کلید Groq نامعتبر یا منقضی شده است. کلید خود را دوباره وارد کنید."
            429 -> "محدودیت نرخ درخواست (تعداد زیاد در دقیقه) یا اعتبار ناکافی."
            400 -> if (parsedMsg.isNotBlank()) parsedMsg else "درخواست نامعتبر یا فایل صوتی پشتیبانی‌نشده."
            502, 503 -> "سرور موقتاً پاسخ نمی‌دهد. چند لحظه بعد دوباره تلاش کنید."
            else -> if (parsedMsg.isNotBlank()) parsedMsg else "خطای سرور با کد $code"
        }
        return TranscriptionError(userMessage = msg, httpCode = code, rawBody = body)
    }

    companion object {
        private const val TAG = "CloudWhisperService"
        private const val CRLF = "\r\n"
        internal const val ENDPOINT = "https://aierix-cut-backend.eeric-stephh.workers.dev/api/whisper/transcribe"
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 90_000
        const val MAX_UPLOAD_BYTES = 50 * 1024 * 1024L
    }
}
