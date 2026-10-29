package com.aicreatorstudio.app.ai.groq

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Real HTTP client for the Groq (OpenAI-compatible) audio
 * transcription API — the ONLINE Whisper test path.
 *
 * Endpoint: `POST https://api.groq.com/openai/v1/audio/transcriptions`
 *
 * Request (multipart/form-data, exactly what the documented
 * OpenAI-compatible contract expects):
 *
 * ```
 * Authorization: Bearer <user key — stored encrypted, never hardcoded>
 * model                  = whisper-large-v3-turbo
 * response_format        = verbose_json
 * timestamp_granularities[] = segment
 * timestamp_granularities[] = word
 * language               = fa | en | ar   (omitted for auto-detect)
 * file                   = <binary part; filename carries the REAL
 *                          Groq-supported extension (.mp3/.m4a/.wav/…)
 *                          and the truthful Content-Type>
 * ```
 *
 * ## Progress + cancellation
 *
 * The body is written with fixed-length streaming so upload
 * progress is REAL (bytes written / body length) and cancellation
 * is checked between chunks — the socket is closed on cancel.
 *
 * ## No mocks
 *
 * Everything here hits the real network and parses the real
 * response. Failures carry the HTTP code and the server's error
 * message verbatim.
 */
@Singleton
class GroqWhisperClient @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** Callbacks the ViewModel forwards into UI state. */
    interface Listener {
        fun onPhase(phase: Phase, progress: Float)
    }

    enum class Phase { UPLOADING, TRANSCRIBING, DONE }

    data class RequestParams(
        val apiKey: String,
        val model: String,
        /** null = let the API auto-detect. */
        val language: String?,
        /**
         * Full transcription endpoint URL.
         *
         * Defaults to Groq. The value comes from
         * [GroqApiKeyStore.endpoint] — the SAME stored endpoint the
         * connection tester derives its models URL from, so the
         * "test" and the "use" can never point at different servers.
         *
         * 2026-10-20 — the old KDoc promised "the API-test form lets
         * the user point it at any OpenAI-compatible server", but
         * there is no such form in the UI: nothing in the app ever
         * calls `saveEndpoint()`, so the stored value is always the
         * Groq default. The promise was false and would have sent
         * someone hunting for a field that does not exist.
         */
        val endpoint: String = ENDPOINT
    )

    sealed interface Outcome {
        data class Success(val transcription: GroqResponseParser.GroqTranscription) : Outcome
        data class ApiError(val error: GroqResponseParser.GroqError) : Outcome
        data class NetworkError(val message: String) : Outcome
        data object Cancelled : Outcome
    }

    /**
     * Stream the content [uri] straight into the multipart body —
     * no intermediate copy of the (possibly large) media file.
     */
    suspend fun transcribe(
        uri: Uri,
        params: RequestParams,
        listener: Listener
    ): Outcome = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val size = querySize(uri)
            if (size <= 0) {
                return@withContext Outcome.NetworkError(
                    "حجم فایل قابل خواندن نیست — فایل را دوباره انتخاب کنید."
                )
            }
            if (size > MAX_UPLOAD_BYTES) {
                return@withContext Outcome.NetworkError(
                    "حجم فایل ${(size / (1024 * 1024))} MB است؛ محدودیت Groq حدود " +
                        "${MAX_UPLOAD_BYTES / (1024 * 1024)} MB است. یک فایل کوچک‌تر انتخاب کنید."
                )
            }
            // 2026-09-13 FILENAME FIX — Groq detects the format from
            // the filename EXTENSION in the multipart part, so the
            // part must carry the real, supported extension. The
            // old code sent filename="audio" (no extension) which
            // the API rejected with "file must be one of the
            // following types: [...]". Resolution: display name →
            // content-resolver MIME → path suffix → magic bytes.
            val rawName = queryDisplayName(uri) ?: uri.lastPathSegment
            val resolverMime = queryMimeType(uri)
            val pathSuffix = GroqUploadTypes.extensionOf(rawName)
                ?: GroqUploadTypes.extensionOf(uri.lastPathSegment)
            val sniffed = sniffFileExtension(uri)
            val fileName = GroqUploadTypes.resolveUploadFileName(
                rawName = rawName,
                mimeType = resolverMime,
                pathSuffixExtension = pathSuffix,
                sniffedExtension = sniffed
            )
            if (fileName == null) {
                return@withContext Outcome.NetworkError(
                    "فرمت فایل برای Groq قابل تشخیص نیست (پسوند پشتیبانی‌شده لازم است: " +
                        "mp3, m4a, mp4, wav, flac, ogg, webm). یک فایل با فرمت استاندارد انتخاب کنید."
                )
            }
            val partMime = GroqUploadTypes.mimeTypeForExtension(
                GroqUploadTypes.extensionOf(fileName)
            ) ?: "application/octet-stream"
            // Debug log BEFORE the request — real filename + the
            // exact Content-Type the file part carries (never the
            // API key, never the Authorization header).
            Log.i(
                TAG,
                "upload part: fileName=$fileName contentType=$partMime " +
                    "bytes=$size (display=${rawName ?: "?"} resolverMime=" +
                    "${resolverMime ?: "?"} sniffed=${sniffed ?: "none"})"
            )
            // 2026-10-10 DIAGNOSTICS — safe request summary: engine
            // model, endpoint HOST only (no path/query), byte size.
            // Never the API key, Authorization header, audio bytes,
            // or transcript text.
            Log.i(
                TAG,
                "request: model=${params.model} host=${hostOf(params.endpoint)} " +
                    "bytes=$size language=${params.language ?: "auto"}"
            )
            val requestStartMs = System.currentTimeMillis()
            val boundary = "AICS${System.currentTimeMillis().toHexString()}"

            // Multipart preamble (every field except the file part).
            val preamble = buildPreamble(params, boundary, fileName, partMime)
            val closing = closingBoundary(boundary)
            val contentLength = preamble.size.toLong() + size + closing.size.toLong()

            val conn = (URL(params.endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 300_000
                doOutput = true
                // 2026-09-13 — the Groq API always needs the key;
                // the blank-key branch below stays only for custom
                // OpenAI-compatible test endpoints.
                if (params.apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${params.apiKey}")
                }
                setRequestProperty(
                    "Content-Type", "multipart/form-data; boundary=$boundary"
                )
                setRequestProperty("Content-Length", contentLength.toString())
                setFixedLengthStreamingMode(contentLength)
                connection = this
            }

            // 2026-10-20 — CANCELLABLE SERVER WAIT.
            //
            // `conn.responseCode` is a blocking socket read and
            // coroutine cancellation does NOT interrupt it. The only
            // `disconnect()` used to live in the upload loop, which
            // had already exited — so a user who pressed Cancel while
            // Groq was transcribing waited out the full 300 s read
            // timeout, and because `Cancelling` is not a terminal
            // state the Generate button stayed disabled the whole
            // time.
            //
            // Hooking the socket teardown onto the coroutine's
            // completion aborts the blocked read immediately.
            val hostJob = currentCoroutineContext()[Job]
            hostJob?.invokeOnCompletion { runCatching { conn.disconnect() } }

            // ---- stream the body ----
            listener.onPhase(Phase.UPLOADING, 0f)
            conn.outputStream.use { rawOut ->
                val out = DataOutputStream(rawOut)
                out.write(preamble)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        if (hostJob?.isActive != true) {
                            runCatching { conn.disconnect() }
                            return@withContext Outcome.Cancelled
                        }
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        written += n
                        listener.onPhase(
                            Phase.UPLOADING,
                            (written.toFloat() / size).coerceIn(0f, 1f) * 0.95f
                        )
                    }
                } ?: run {
                    return@withContext Outcome.NetworkError(
                        "باز کردن فایل انتخاب‌شده ناموفق بود (دسترسی داده لغو شده؟)"
                    )
                }
                out.write(closing)
                out.flush()
            }
            listener.onPhase(Phase.TRANSCRIBING, 1f)

            // ---- read the response ----
            val code = conn.responseCode
            val elapsedMs = System.currentTimeMillis() - requestStartMs
            Log.i(
                TAG,
                "response: http=$code elapsedMs=$elapsedMs model=${params.model} " +
                    "host=${hostOf(params.endpoint)}"
            )
            // 2026-10-20 — the cancellation hook above aborts the
            // socket, but check the job too so a cancel that landed
            // during the read is reported as a cancel (and not as a
            // bogus HTTP error).
            if (hostJob?.isActive == false) {
                return@withContext Outcome.Cancelled
            }
            val bodyStream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = bodyStream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code in 200..299) {
                // 2026-10-20 — a 2xx reply that is not JSON is an
                // API problem, not a network problem. The old code let
                // `JSONObject(body)` throw out of the parser and the
                // generic catch turned it into "خطای شبکه: … اینترنت
                // را بررسی کنید" — telling the user to fix their
                // internet when the server had already answered. The
                // body is now surfaced so the cause is visible.
                runCatching { GroqResponseParser.parseVerboseJson(body) }
                    .fold(
                        onSuccess = { Outcome.Success(it) },
                        onFailure = { parseError ->
                            Log.w(TAG, "Groq returned a non-JSON 2xx body", parseError)
                            Outcome.ApiError(
                                GroqResponseParser.GroqError(
                                    httpCode = code,
                                    message = "پاسخ سرور قابل خواندن نبود: " +
                                        body.take(300).ifBlank { "(بدون محتوا)" },
                                    code = null,
                                    type = null
                                )
                            )
                        }
                    )
            } else {
                val parsed = GroqResponseParser.parseError(code, body)
                // 2026-09-10 — 404 almost always means the endpoint URL
                // is wrong/incomplete (verified live: POSTing to the
                // bare domain or a mistyped path 404s; the real path
                // 401s/200s). Attach an actionable hint + the URL that
                // was actually requested so the user can self-correct.
                val message = if (code == 404) {
                    "${parsed.message}\nآدرس endpoint اشتباه است. مسیر کامل لازم است، مثلا:\n" +
                        "$ENDPOINT\n(درخواست به ${params.endpoint} ارسال شد)"
                } else parsed.message
                Outcome.ApiError(parsed.copy(message = message))
            }
        } catch (e: IOException) {
            if (currentCoroutineContext()[Job]?.isActive != true) {
                Outcome.Cancelled
            } else {
                Log.w(TAG, "Groq request network failure", e)
                Outcome.NetworkError(
                    "خطای شبکه: ${e.message ?: e::class.java.simpleName} " +
                        "(اینترنت و دسترسی به api.groq.com را بررسی کنید)"
                )
            }
        } catch (e: Exception) {
            if (currentCoroutineContext()[Job]?.isActive != true) {
                Outcome.Cancelled
            } else {
                Log.w(TAG, "Groq request failed", e)
                Outcome.NetworkError(e.message ?: e::class.java.simpleName)
            }
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    // ---- multipart encoding (pure — unit-tested) ----

    internal fun buildPreamble(
        params: RequestParams,
        boundary: String,
        fileName: String,
        partMime: String
    ): ByteArray = GroqMultipart.buildPreamble(params, boundary, fileName, partMime)

    internal fun closingBoundary(boundary: String): ByteArray =
        GroqMultipart.closingBoundary(boundary)

    // ---- content-resolver helpers ----

    /**
     * Byte length of the content behind [uri].
     *
     * 2026-10-20 — CRITICAL FIX. The old implementation asked the
     * content resolver for an `AssetFileDescriptor` and read its
     * `length`. For a `file://` URI the platform ALWAYS reports
     * `AssetFileDescriptor.UNKNOWN_LENGTH` (-1) — see
     * `ContentResolver.openAssetFileDescriptor`, which builds
     * `new AssetFileDescriptor(pfd, 0, -1)` for the `file` scheme.
     *
     * The cloud path hands the client a local temp file
     * (`Uri.fromFile(audioFile)`), so `querySize` returned -1 for EVERY
     * request and `transcribe` bailed out before touching the network
     * with "حجم فایل قابل خواندن نیست". The Groq cloud subtitle
     * feature was therefore 100% non-functional.
     *
     * The fix reads the real file length for `file://`, and falls back
     * to the file descriptor's `statSize` when the asset descriptor
     * reports an unknown length for any other scheme.
     */
    internal fun querySize(uri: Uri): Long = querySize(context, uri)

    internal fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    /** The content resolver's MIME type for [uri], when it knows one. */
    internal fun queryMimeType(uri: Uri): String? = runCatching {
        context.contentResolver.getType(uri)
    }.getOrNull()

    /**
     * Read the first bytes of [uri] and identify the container by
     * magic numbers (real detection — the last honest resort
     * when name/MIME carry no supported extension).
     */
    internal fun sniffFileExtension(uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
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

    private fun Long.toHexString(): String = java.lang.Long.toHexString(this)

    /** Host only (no path/query) for safe diagnostics — never the full URL. */
    private fun hostOf(url: String): String = runCatching {
        URL(url).host ?: "invalid"
    }.getOrDefault("invalid")

    companion object {
        private const val TAG = "GroqWhisperClient"
        const val ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"

        /**
         * Byte length of the content behind [uri].
         *
         * Split out of the instance method so it can be unit-tested
         * without a live `ContentResolver` — the `file://` branch is
         * exactly the one that was broken, and it is the one that
         * matters (the cloud engine always uploads a local temp file).
         *
         * 2026-10-20 — for a `file://` URI the platform's
         * `ContentResolver.openAssetFileDescriptor` ALWAYS reports
         * `AssetFileDescriptor.UNKNOWN_LENGTH` (-1): it builds
         * `new AssetFileDescriptor(pfd, 0, -1)` for the `file` scheme.
         * The old code read that length, so every cloud request died
         * at the size check before touching the network.
         */
        internal fun querySize(context: Context?, uri: Uri): Long {
            // 1. Local file — ask the filesystem for the true length.
            if (uri.scheme == null || uri.scheme.equals("file", ignoreCase = true)) {
                val path = uri.path ?: return -1L
                val len = localFileSize(path)
                if (len > 0) return len
            }
            // 2. Anything else — content provider.
            val resolver = context?.contentResolver ?: return -1L
            return runCatching {
                val afd = resolver.openAssetFileDescriptor(uri, "r")
                if (afd != null) {
                    afd.use {
                        if (it.length > 0) {
                            it.length
                        } else {
                            // UNKNOWN_LENGTH — ask the descriptor itself.
                            runCatching {
                                resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                                    pfd.statSize
                                }
                            }.getOrNull() ?: -1L
                        }
                    }
                } else {
                    runCatching {
                        resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
                    }.getOrNull() ?: -1L
                }
            }.getOrDefault(-1L)
        }

        /**
         * Real byte length of the local file at [path], or -1 when it
         * cannot be read.
         *
         * Split out so it is JVM-testable: `android.net.Uri` is
         * stubbed to `null` in the unit-test stack, so a test cannot
         * build the `file://` Uri the cloud engine actually uses.
         */
        internal fun localFileSize(path: String): Long =
            runCatching { java.io.File(path).length() }.getOrDefault(-1L)

        /** Groq's documented audio upload ceiling (25 MB free tier). */
        const val MAX_UPLOAD_BYTES: Long = 25L * 1024 * 1024

        /**
         * 2026-10-10 — the PINNED production model for every
         * user-key transcription request. Deterministic: the
         * [com.aicreatorstudio.app.ai.groq.GroqApiKeyStore.modelId]
         * resolver falls back to this id whenever the stored value
         * is blank or not a known Groq Whisper model, so an unknown
         * / stale id can never reach the wire.
         */
        const val DEFAULT_MODEL = "whisper-large-v3-turbo"

        /** Models offered by the in-app picker (docs: both are
         *  official Groq speech-to-text model ids). */
        val MODELS = listOf(
            DEFAULT_MODEL,
            "whisper-large-v3"
        )

        /**
         * 2026-10-10 — resolve a (possibly stale/blank) stored model id
         * to a value that is safe to send: the stored id when it is a
         * known Groq Whisper model, otherwise the pinned
         * [DEFAULT_MODEL]. Pure — JVM-testable.
         */
        fun resolveModelId(stored: String?): String {
            val trimmed = stored?.trim()
            return if (!trimmed.isNullOrEmpty() && trimmed in MODELS) {
                trimmed
            } else {
                DEFAULT_MODEL
            }
        }

        private const val CRLF = "\r\n"
    }
}

/**
 * Pure multipart/form-data encoder for the Groq transcription
 * request. Kept dependency-free (no Android types) so JVM unit
 * tests pin the exact wire format.
 *
 * 2026-09-13 FILENAME FIX — [buildPreamble] takes the REAL file
 * name (with a Groq-supported extension, resolved by
 * [GroqUploadTypes]) and the truthful part Content-Type. Groq
 * detects the format from the filename extension — the old
 * hardcoded `filename="audio"` + `application/octet-stream` was
 * rejected with "file must be one of the following types: [...]".
 */
internal object GroqMultipart {

    private const val CRLF = "\r\n"

    fun buildPreamble(
        params: GroqWhisperClient.RequestParams,
        boundary: String,
        fileName: String,
        partMime: String
    ): ByteArray {
        val sb = StringBuilder()
        fun field(name: String, value: String) {
            sb.append("--").append(boundary).append(CRLF)
            sb.append("Content-Disposition: form-data; name=\"").append(name).append("\"")
                .append(CRLF).append(CRLF)
            sb.append(value).append(CRLF)
        }
        field("model", params.model)
        field("response_format", "verbose_json")
        field("timestamp_granularities[]", "segment")
        field("timestamp_granularities[]", "word")
        // 2026-10-20 — this used to be an instruction block
        // ("قواعد فارسی: کلمات فارسی را شکست ندهید…"). The API's
        // `prompt` field is the same initial_prompt mechanism as
        // whisper.cpp: the decoder treats it as text it already
        // wrote and IMITATES it, it does not obey it. On a
        // low-confidence stretch that instruction text became the
        // output. The natural primer establishes spacing and ZWNJ
        // without giving the model a sentence to echo.
        field("prompt", com.aicreatorstudio.app.ai.audio.WhisperPrompt.PRIMER)
        params.language?.takeIf { it.isNotBlank() }?.let { field("language", it) }
        // The file part header (binary payload is streamed by the caller).
        sb.append("--").append(boundary).append(CRLF)
        sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
            .append(fileName).append("\"").append(CRLF)
        sb.append("Content-Type: ").append(partMime).append(CRLF).append(CRLF)
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    fun closingBoundary(boundary: String): ByteArray =
        "$CRLF--$boundary--$CRLF".toByteArray(Charsets.UTF_8)
}
