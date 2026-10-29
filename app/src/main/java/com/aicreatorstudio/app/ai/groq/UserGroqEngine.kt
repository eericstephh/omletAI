package com.aicreatorstudio.app.ai.groq

import android.net.Uri
import com.aicreatorstudio.app.ai.SubtitleLanguage
import com.aicreatorstudio.app.ai.Transcription
import com.aicreatorstudio.app.ai.TranscriptionEngine
import com.aicreatorstudio.app.ai.subtitle.cloud.SubtitleAudioExtractor
import com.aicreatorstudio.app.ai.subtitle.cloud.SubtitleResultParser
import com.aicreatorstudio.app.ai.subtitle.cloud.TemporaryFileManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 2026-10-10 USER-KEY GROQ ENGINE — transcription through the
 * user's OWN Groq API key (stored in [GroqApiKeyStore]), talking
 * directly to `api.groq.com` via [GroqWhisperClient]. No Omelet
 * backend, no proxy, no account.
 *
 * ## Pipeline
 *
 * 1. [SubtitleAudioExtractor] remuxes the video's audio track into
 *    a temp .m4a (same proven extractor the Cloud Worker path uses
 *    — the MP4 container itself is never uploaded).
 * 2. [GroqWhisperClient] streams that file to
 *    `POST /openai/v1/audio/transcriptions` with
 *    `whisper-large-v3-turbo` and the user's key in the
 *    Authorization header only.
 * 3. [SubtitleResultParser] converts the verbose_json reply into
 *    the app's internal [Transcription] (per-word timings).
 * 4. The temp file is always deleted.
 *
 * ## Availability
 *
 * `isAvailable = hasKey()` — the engine exists only when the user
 * has saved a key in Settings («اتصال هوش مصنوعی»). Without a key,
 * [SubtitleGenerator] reports CLOUD_ENGINE_UNAVAILABLE (no silent
 * fallback to any other backend).
 *
 * ## Progress contract
 *
 * Reports the fraction scheme the ViewModel's cloud state machine
 * expects: extraction 0..0.2 · upload 0.2..0.6 · awaiting-reply ≥0.6.
 */
@Singleton
class UserGroqEngine @Inject constructor(
    private val apiKeyStore: GroqApiKeyStore,
    private val groqClient: GroqWhisperClient,
    private val audioExtractor: SubtitleAudioExtractor,
    private val resultParser: SubtitleResultParser,
    private val tempFiles: TemporaryFileManager
) : TranscriptionEngine {

    override val id: String = ENGINE_ID

    override val displayName: String = "Whisper Large V3 Turbo (کلید شخصی)"

    /** True only when the user has saved a Groq API key. */
    override val isAvailable: Boolean
        get() = apiKeyStore.hasKey()

    /** زبان انتخابی کاربر برای زیرنویس (توسط SubtitleGenerator تنظیم می‌شود) */
    var selectedLanguage: SubtitleLanguage? = SubtitleLanguage.PERSIAN

    override suspend fun initializeModel(onProgress: (Float) -> Unit): Result<Unit> {
        if (!apiKeyStore.hasKey()) {
            return Result.failure(Exception(MISSING_KEY_MESSAGE))
        }
        onProgress(1f)
        return Result.success(Unit)
    }

    override suspend fun generateTranscription(
        videoUri: Uri,
        onProgress: (Float) -> Unit
    ): Result<Transcription> {
        // 2026-10-20 — a blank/whitespace key used to be accepted here
        // (the check was `load() ?: fail`, which only rejects null) and
        // then reached the wire WITHOUT an Authorization header,
        // producing a 401 that was reported as "کلید API نامعتبر است".
        val apiKey = apiKeyStore.load()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return Result.failure(Exception(MISSING_KEY_MESSAGE))

        val hostJob = currentCoroutineContext()[Job]

        // ---- Phase 1: extract the audio track (0..0.2) ----
        val extracted = try {
            audioExtractor.extract(
                sourceUri = videoUri,
                onProgress = { onProgress(it.coerceIn(0f, 1f) * 0.2f) },
                checkCancelled = {
                    if (hostJob?.isActive != true) throw CancellationException("cancelled")
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(
                Exception(e.message ?: "فایل صوتی از ویدیو استخراج نشد.")
            )
        }

        val audioFile = extracted.file
        try {
            if (audioFile.length() <= 0L) {
                return Result.failure(Exception("فایل صوتی استخراج‌شده خالی است."))
            }

            // ---- Phase 2+3: upload + transcribe on Groq (0.2..1) ----
            val outcome = groqClient.transcribe(
                uri = Uri.fromFile(audioFile),
                params = GroqWhisperClient.RequestParams(
                    apiKey = apiKey,
                    model = apiKeyStore.modelId(),
                    language = selectedLanguage?.code,
                    // 2026-10-20 — use the STORED endpoint, not the
                    // hard-coded Groq constant. The connection tester
                    // already derives its models URL from the same
                    // stored value, so before this line the "test" and
                    // the "use" could never disagree: the tester
                    // pointed at a custom server while the
                    // transcription still went to api.groq.com.
                    endpoint = apiKeyStore.endpoint()
                ),
                listener = object : GroqWhisperClient.Listener {
                    override fun onPhase(phase: GroqWhisperClient.Phase, progress: Float) {
                        when (phase) {
                            // engine upload 0..1 → global 0.2..0.6
                            GroqWhisperClient.Phase.UPLOADING ->
                                onProgress(0.2f + progress.coerceIn(0f, 1f) * 0.4f)
                            // reply pending — indeterminate band marker
                            GroqWhisperClient.Phase.TRANSCRIBING -> onProgress(0.65f)
                            GroqWhisperClient.Phase.DONE -> onProgress(1f)
                        }
                    }
                }
            )

            return when (outcome) {
                is GroqWhisperClient.Outcome.Success -> {
                    val g = outcome.transcription
                    // The parser always emits at least one segment (an
                    // honest blank fallback), so "no speech" must be
                    // judged on the RAW reply, not the mapped result.
                    if (g.text.isBlank() && g.segments.isEmpty()) {
                        Result.failure(
                            Exception("متنی در ویدیو تشخیص داده نشد. فایل صوتی دیگری امتحان کنید.")
                        )
                    } else {
                        val durationMs = ((g.durationSec ?: 0.0) * 1000).toLong()
                        Result.success(resultParser.toTranscription(g, durationMs))
                    }
                }

                is GroqWhisperClient.Outcome.ApiError ->
                    Result.failure(Exception(persianMessageFor(outcome.error)))

                is GroqWhisperClient.Outcome.NetworkError ->
                    Result.failure(Exception(outcome.message))

                is GroqWhisperClient.Outcome.Cancelled ->
                    Result.failure(Exception("عملیات لغو شد."))
            }
        } finally {
            tempFiles.delete(audioFile)
        }
    }

    override fun release() {
        // Pure HTTP client — nothing to release.
    }

    companion object {
        const val ENGINE_ID = "groq_user_key"

        /** Shown whenever the engine is asked to run without a saved key. */
        const val MISSING_KEY_MESSAGE =
            "کلید API در «اتصال هوش مصنوعی» تنظیمات ثبت نشده است. ابتدا کلید Groq خود را وارد و تست کنید."

        /**
         * Persian, actionable messages for the documented Groq HTTP
         * error codes. Falls back to the server's own message so the
         * user always sees the truth.
         */
        internal fun persianMessageFor(error: GroqResponseParser.GroqError): String =
            when (error.httpCode) {
                400 -> "درخواست نامعتبر بود: ${error.message}"
                401, 403 ->
                    "کلید API نامعتبر است یا دسترسی ندارد. کلید را در «اتصال هوش مصنوعی» بررسی کنید."
                413 ->
                    "حجم فایل صوتی بیش از حد مجاز است (حداکثر ۲۵ مگابایت). ویدیوی کوتاه‌تری انتخاب کنید."
                429 ->
                    "محدودیت نرخ Groq. چند لحظه بعد دوباره تلاش کنید."
                in 500..599 ->
                    "سرویس Groq موقتاً در دسترس نیست (HTTP ${error.httpCode}). بعداً دوباره تلاش کنید."
                else -> error.message
            }
    }
}
