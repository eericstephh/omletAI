package com.aicreatorstudio.app.ai

/**
 * The Whisper model checkpoints the user can pick in the AI
 * Subtitle card.
 *
 * Three sizes, all sourced from the official
 * `ggerganov/whisper.cpp` Hugging Face mirror (the same
 * publisher the bundled assets came from):
 *
 * - [SMALL]    — 488 MB f16. The SPEED default: ~3x faster than
 *   Medium with good Persian accuracy. Download-only (bundled:
 *   false). NOTE: the q5_1 quantized variant was tried first
 *   (2026-09-08) and produced per-character BPE tokens on
 *   Persian — the f16 build is required.
 *
 * 2026-09-21 MEDIUM REMOVED — Whisper Medium (~1.5 GB) removed
 * from the model picker entirely. Users who had Medium selected
 * are migrated to Small. Medium's CPU-only inference was 1-2x
 * realtime on real phones (5+ min for a 5-min clip).
 *
 * 2026-09-08 SPEED RESTORE — B1.6 removed Tiny/Small after a
 * quality audit, but real devices then showed Medium's CPU-only
 * inference is 1-2x realtime (5+ min for a 5-min clip), which
 * users read as "it doesn't work". Small f16 + flash attention
 * transcribes fast enough to feel responsive.
 *
 * RAM is INFORMATIONAL ONLY. A device with less free RAM than
 * [minRamMb] may still download, install and keep the model;
 * if the device genuinely cannot initialize it, the engine
 * reports a clear load error instead of crashing or silently
 * switching models.
 *
 * Per entry:
 *
 * - `displayName` — UI label in the model picker.
 * - `language` — BCP-47 hint passed to `whisper_full(...)` so
 *   the decoder does not waste time on language detection.
 *   Hardcoded to "fa" because the only documented use case
 *   today is Persian subtitles; the multilingual model still
 *   handles other languages if the user adds the
 *   corresponding UI later.
 * - `subtitle` — short user-visible tagline under the label.
 * - `minRamMb` — documented working-set floor, shown in the
 *   UI as information ("Requires approximately X RAM").
 *
 * The download URL + SHA-256 are NOT stored here — they live
 * in `app/ai-models/models.lock.json` and are read at runtime
 * from the bundled `assets/ai-models/manifest.json` (see
 * [AiModels.sha256For]). Adding a new size is a 4-line edit
 * here + a `models.lock.json` entry + a download URL in
 * [com.aicreatorstudio.app.ai.AiModelInstaller.downloadUrlFor].
 */

/**
 * 2026-09-24 — the EXACT Persian wording shown ABOVE the bottom tool
 * dock whenever [WhisperModelSpec.CLOUD_API] is the selected model.
 *
 * Cloud transcription posts the audio to Groq's API, which an
 * Iranian IP cannot reach, so the user has to enable their
 * sanctions-bypass route (VPN) before Generate can succeed. This is a
 * PRECONDITION, not a hint.
 *
 * Single-sourced here and pinned by a JVM test, so the model picker,
 * the banner and any future surface cannot drift apart.
 */
const val CLOUD_MODEL_NETWORK_NOTICE_FA =
    "برای استفاده از این مدل باید تحریم شکن روشن کنید"

enum class WhisperModelSpec(
    val displayName: String,
    val language: String,
    val subtitle: String,
    val minRamMb: Long
) {
    SMALL(
        displayName = "آفلاین — Whisper Small",
        language = "fa",
        subtitle = "پردازش روی دستگاه · بدون اینترنت",
        minRamMb = 2_048L
    ),
    /**
     * 2026-09-13 GROQ REBUILD — cloud transcription runs
     * `whisper-large-v3-turbo` on the official Groq API
     * (`POST /openai/v1/audio/transcriptions`). NO model download,
     * no on-device inference, no RAM floor — only the user's Groq
     * API key (encrypted via Android Keystore) from Settings.
     */
    CLOUD_API(
        displayName = "ابری — Groq Whisper Large V3 Turbo",
        language = "fa",
        subtitle = "نیازمند کلید Groq و اینترنت",
        minRamMb = 0L
    );

    /**
     * 2026-09-13 CLOUD FIX — True only for [CLOUD_API]: the model
     * runs on Groq's cloud, so there is NO local file to download,
     * install, hash, or load. The model picker, the install-status
     * computation, and the download action all branch on this
     * flag so the cloud row can never inherit the Small model's
     * disk state (the pre-fix bug: the cloud row showed a
     * "دانلود" button that actually downloaded 488 MB of Whisper
     * Small).
     */
    val isCloud: Boolean
        get() = this == CLOUD_API

    /**
     * 2026-09-24 — the network precondition to show the user while
     * this model is selected, or `null` when there is none.
     *
     * Non-null only for [CLOUD_API]: it is the one model that leaves
     * the device, so it is the one model whose reachability depends on
     * the user's network route. The on-device models never touch the
     * network and therefore never carry a notice.
     *
     * Lives on the SPEC rather than on the picker, so every surface
     * that shows the selected model shows the same requirement — and
     * adding a future cloud model automatically gets the notice
     * without touching the UI.
     */
    val networkNoticeFa: String?
        get() = if (isCloud) CLOUD_MODEL_NETWORK_NOTICE_FA else null

    /**
     * Map this UI enum onto the canonical [CatalogEntry] used by
     * the installer. Returns the [AiModelCatalog] entry whose `id`
     * is the canonical id.
     *
     * 2026-09-13 CLOUD FIX — callers must NOT call this for a cloud
     * spec: [CLOUD_API] has no local model, so any disk/download
     * decision derived from `aiSpec()` is wrong by definition.
     * [SubtitleGenerator] routes CLOUD_API to the cloud engine
     * before the local path can ever run. The SMALL mapping below
     * remains only as a non-null signature for the JVM tests that
     * iterate every spec's catalog plumbing (RAM/thread audits);
     * production cloud flows never read it.
     */
    fun aiSpec(): CatalogEntry = when (this) {
        SMALL -> AiModelCatalog.WHISPER_SMALL
        CLOUD_API -> AiModelCatalog.WHISPER_SMALL
    }

    /**
     * True if the device's free RAM meets this model's
     * `minRamMb` floor. ADVISORY ONLY — callers must not use
     * this to hide models, disable downloads, or reject a
     * manual selection. RAM is displayed as information; only
     * a real native-load failure blocks inference, and that
     * surfaces as a clear error, never a silent switch.
     */
    fun fitsInRam(freeRamMb: Long): Boolean = freeRamMb >= minRamMb

    /**
     * Short RAM amount, e.g. "4 GB" or "1.5 GB". Used by
     * [ramRequirementText] and by engine error messages.
     */
    fun ramRequirementAmount(): String =
        if (minRamMb % 1024L == 0L) {
            "${minRamMb / 1024L} GB"
        } else {
            "%.1f GB".format(minRamMb / 1024f)
        }

    /**
     * User-facing RAM requirement, e.g.
     * "Requires approximately 4 GB RAM". Rendered on every
     * model-picker row as information only — never as a
     * disable/hide reason.
     */
    fun ramRequirementText(): String =
        "حداقل به ${ramRequirementAmount()} RAM نیاز دارد"

    companion object {
        /**
         * Default model: [SMALL] on every device (2026-09-08
         * SPEED RESTORE). Medium/Large are opt-in for users who
         * explicitly prioritize accuracy over speed; the old
         * auto-default to Medium made a 5-minute clip take 5+
         * minutes on real phones, which read as "not working".
         *
         * The caller (typically the ViewModel's default
         * constructor) passes the device's free-RAM reading in
         * MB; we keep the signature pure so JVM unit tests can
         * exercise the policy without touching the
         * [android.app.ActivityManager] probe.
         */
        fun defaultFor(freeRamMb: Long): WhisperModelSpec = SMALL

        /**
         * Migration map for saved model selections. Current ids
         * resolve to themselves; pre-B1.6 ids (TINY, SMALL and
         * their casings/prefixes) stay in the Small class, and
         * the brief-lived 2026-09-08 `SMALL_Q5` id maps to
         * [SMALL] too (the q5_1 build was withdrawn for Persian
         * quality). Unknown / blank ids default to [SMALL].
         */
        fun migrateSavedId(savedId: String?): WhisperModelSpec = when (savedId) {
            // 2026-09-21 MEDIUM REMOVED — migrate to Small.
            "MEDIUM", "WHISPER_MEDIUM", "medium",
            "whisper-medium" -> SMALL
            // 2026-09-11 — LOCAL Large-v3 was removed; users who had
            // it selected migrate to the Cloud API (same Large-class
            // accuracy, no download).
            "LARGE_V3", "WHISPER_LARGE_V3", "large_v3", "LARGE-V3",
            "large-v3", "whisper-large-v3" -> CLOUD_API
            "CLOUD_API", "WHISPER_CLOUD", "whisper_api", "cloud" -> CLOUD_API
            "SMALL", "WHISPER_SMALL", "small", "whisper-small",
            "SMALL_Q5", "WHISPER_SMALL_Q5", "whisper-small-q5" -> SMALL
            null, "" -> SMALL
            else -> SMALL
        }

        /**
         * Backwards-compat: callers that want a static default
         * get [SMALL]. The device-aware default lives at
         * [defaultFor] and should be preferred on app start.
         */
        val Default: WhisperModelSpec = SMALL
    }
}
