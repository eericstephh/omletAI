package com.aicreatorstudio.app.ai

import android.net.Uri
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin façade over the [TranscriptionEngine] list (today: Whisper
 * only — the Vosk backend was removed 2026-08-30).
 *
 * Clean 2026-09-08 rebuild: only the methods with real callers
 * survive. The ViewModel drives the whole pipeline through
 * [initializeModelFor] + [generateTranscription]; model presence,
 * selection and SRT formatting live in their own classes.
 *
 * Every failure returned here carries a user-facing Persian message
 * (from [AiFailure]) so the UI can surface it verbatim.
 */
@Singleton
class SubtitleGenerator @Inject constructor(
    engines: @JvmSuppressWildcards List<TranscriptionEngine>
) {

    private val engines: List<TranscriptionEngine> = engines

    /** Resolved lazily on first use; null when no engine can load. */
    private val activeEngine: TranscriptionEngine? by lazy {
        engines.firstOrNull { it.isAvailable }
    }

    /**
     * 2026-10-10 — the model picked in [initializeModelFor].
     * [WhisperModelSpec.CLOUD_API] routes generateTranscription to
     * the user-key engine (Groq cloud); SMALL/MEDIUM stay
     * fully offline.
     */
    @Volatile
    private var selectedSpec: WhisperModelSpec? = null

    /**
     * 2026-10-10 USER-KEY PATH — engine backed by the user's own
     * Groq API key ([com.aicreatorstudio.app.ai.groq.UserGroqEngine]).
     * Available only when a key is saved in Settings. The legacy
     * Cloud Worker engine is NEVER used as a fallback for
     * [WhisperModelSpec.CLOUD_API] (see [resolveCloudEngine]).
     */
    private val userKeyEngine: TranscriptionEngine? by lazy {
        engines.firstOrNull { it.id == com.aicreatorstudio.app.ai.groq.UserGroqEngine.ENGINE_ID }
    }

    /**
     * Resolve the cloud engine for [WhisperModelSpec.CLOUD_API].
     *
     * 2026-10-10 STRICT ROUTING — cloud mode uses ONLY the user's
     * own Groq key engine ([com.aicreatorstudio.app.ai.groq.UserGroqEngine]).
     * The legacy Cloud Worker engine is no longer a silent fallback:
     * if no key is saved, cloud is UNAVAILABLE and the caller
     * surfaces [CLOUD_ENGINE_UNAVAILABLE_MESSAGE]. A failure inside
     * the chosen engine is never retried against another backend.
     */
    private fun resolveCloudEngine(): TranscriptionEngine? =
        userKeyEngine?.takeIf { it.isAvailable }

    /**
     * True when a saved Groq API key makes the user-key engine usable.
     * Surfaced to the UI so the cloud row's status reflects the real
     * precondition instead of always showing "Cloud" ready.
     */
    fun hasUserGroqKey(): Boolean = userKeyEngine?.isAvailable == true

    /**
     * True when the user-key cloud engine can serve
     * [WhisperModelSpec.CLOUD_API] right now.
     */
    fun isCloudEngineAvailable(): Boolean = resolveCloudEngine() != null

    /**
     * The engine [generateTranscription] will invoke for the current
     * [selectedSpec]. Exposed `internal` so deterministic JVM tests can
     * prove routing without constructing an `android.net.Uri`.
     */
    internal fun engineThatWillGenerate(): TranscriptionEngine? =
        if (selectedSpec == WhisperModelSpec.CLOUD_API) {
            resolveCloudEngine()
        } else {
            activeEngine
        }

    /** Engine label for the Subs card ("Whisper (Whisper Medium)"). */
    val activeEngineDisplayName: String?
        get() = if (selectedSpec == WhisperModelSpec.CLOUD_API) {
            resolveCloudEngine()?.displayName ?: "Whisper Large V3 Turbo (Groq)"
        } else {
            activeEngine?.displayName
        }

    /**
     * True when [spec]'s model is on disk and ready to run.
     *
     * 2026-10-20 — the CLOUD FIX.
     *
     * The old implementation forwarded every spec to the local
     * engine, and `WhisperModelSpec.CLOUD_API.aiSpec()` maps to
     * `WHISPER_SMALL` (it has to return something non-null for the
     * JVM catalog audits). So `isModelDownloaded(CLOUD_API)` reported
     * **"the Small model is on disk"** — the cloud row would claim to
     * be downloaded/installed even though the cloud backend has no
     * model file at all.
     *
     * A cloud spec is never "downloaded": it is ready when the user's
     * API key is saved.
     */
    fun isModelDownloaded(spec: WhisperModelSpec): Boolean {
        if (spec.isCloud) return hasUserGroqKey()
        return (activeEngine as? WhisperEngine)?.isModelDownloaded(spec) == true
    }

    /**
     * Install + load [spec]: bundled copy first, network repair when
     * needed, SHA-verified, then whisper.cpp native load. Reports a
     * REAL 0..1 fraction (install 0.05..0.8, load 0.8..1).
     *
     * [WhisperModelSpec.CLOUD_API] has NO local model — this only
     * validates the Groq API key (a real failure when it is unset).
     */
    suspend fun initializeModelFor(
        spec: WhisperModelSpec,
        onProgress: (Float) -> Unit = {}
    ): Result<Unit> {
        selectedSpec = spec
        if (spec == WhisperModelSpec.CLOUD_API) {
            val cloud = resolveCloudEngine()
                ?: return Result.failure(
                    Exception(CLOUD_ENGINE_UNAVAILABLE_MESSAGE)
                )
            return cloud.initializeModel(onProgress)
        }
        val engine = activeEngine
            ?: return Result.failure(
                Exception(AiFailure.ENGINE_UNAVAILABLE.persianMessage())
            )
        return when (engine) {
            is WhisperEngine -> engine.initializeModelFor(spec, onProgress)
            else -> engine.initializeModel(onProgress)
        }
    }

    /** Push the user's spoken-language selection onto the engines. */
    fun setSelectedLanguage(language: SubtitleLanguage?) {
        (activeEngine as? WhisperEngine)?.selectedLanguage = language
        // 2026-10-10 ACCOUNT-FREE — the legacy Worker engine is no
        // longer registered; only the user-key Groq engine needs the
        // hint so Groq receives language=fa (or the user's pick) as
        // the documented ISO-639-1 parameter.
        (userKeyEngine as? com.aicreatorstudio.app.ai.groq.UserGroqEngine)?.selectedLanguage =
            language
    }

    /**
     * Transcribe [videoUri]'s audio (or the engine's configured
     * audio-source override) into a full [Transcription].
     *
     * [onProgress] — REAL transcription fraction (whisper windows).
     * [onDecodeProgress] — REAL audio-extraction fraction
     * (MediaCodec PTS / track duration, remux included).
     */
    suspend fun generateTranscription(
        videoUri: Uri,
        onProgress: (Float) -> Unit = {},
        onDecodeProgress: (Float) -> Unit = {}
    ): Result<Transcription> {
        // 2026-09-11 — CLOUD_API never touches the local whisper path.
        if (selectedSpec == WhisperModelSpec.CLOUD_API) {
            val cloud = resolveCloudEngine()
                ?: return Result.failure(
                    Exception(CLOUD_ENGINE_UNAVAILABLE_MESSAGE)
                )
            return cloud.generateTranscription(videoUri, onProgress)
        }
        val engine = activeEngine
            ?: return Result.failure(
                Exception(AiFailure.ENGINE_UNAVAILABLE.persianMessage())
            )
        return when (engine) {
            is WhisperEngine ->
                engine.generateTranscription(videoUri, onProgress, onDecodeProgress)
            else -> engine.generateTranscription(videoUri, onProgress)
        }
    }

    /** Release native resources. Safe when no engine is active. */
    fun release() {
        activeEngine?.release()
    }

    companion object {
        /**
         * 2026-10-10 — shown when CLOUD_API is selected but no saved
         * Groq API key is available. Names the exact settings path
         * AND the deliberate offline alternative so the user can
         * self-fix either way.
         */
        const val CLOUD_ENGINE_UNAVAILABLE_MESSAGE =
            "برای زیرنویس ابری، کلید Groq خود را در «تنظیمات ← اتصال هوش مصنوعی» وارد کنید. همچنین می‌توانید موتور آفلاین (Whisper Small) را انتخاب کنید."
    }
}
