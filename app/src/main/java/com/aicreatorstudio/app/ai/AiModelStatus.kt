package com.aicreatorstudio.app.ai

import android.content.Context

/**
 * PHASE B3 — per-model install status for the AI Subtitle card.
 *
 * The brief explicitly forbids showing a model as "READY" when
 * it isn't actually installed and verified. This enum is the
 * single source of truth that the AI Subtitle card uses to
 * label each model row (Ready / Download / Failed) and to
 * decide whether a Generate click should kick off the installer
 * first.
 *
 * ## Why four states (not two)
 *
 * The pipeline has four distinct user-visible states:
 *   - **NOT_INSTALLED** — the bundled asset is missing AND the
 *     disk file is missing. The user must download.
 *   - **INSTALLED** — the disk file is present AND its SHA-256
 *     matches the bundled manifest. The model is ready to use.
 *   - **DOWNLOADING** — the user has kicked off a download /
 *     install. This state is set by the ViewModel when the
 *     installer pipeline begins; it is never derived from disk
 *     state because a download is in flight.
 *   - **FAILED** — the install was attempted and either the
 *     network call failed, the SHA check failed, or the disk
 *     write failed. The user can retry.
 *
 * ## How the UI uses it
 *
 * The AI Subtitle card renders a small status pill on each
 * `WhisperModelSpec` row, with this color/label mapping:
 *   - INSTALLED   → green pill, "Ready"
 *   - NOT_INSTALLED → neutral pill, "Download"
 *   - DOWNLOADING → blue pill with a small spinner, "Downloading..."
 *   - FAILED      → red pill, "Failed — tap to retry"
 *   - CLOUD_READY → tertiary pill, "Cloud" (2026-09-13 — the cloud
 *     row never downloads anything, so it never shows Download /
 *     Downloading / Failed affordances)
 *
 * Pressing Generate on a NOT_INSTALLED or FAILED model triggers
 * the install pipeline first; only once the model reports
 * INSTALLED does the actual Whisper inference begin.
 */
enum class AiModelStatus {
    NOT_INSTALLED,
    INSTALLED,
    DOWNLOADING,
    FAILED,
    /**
     * 2026-09-13 CLOUD FIX — the always-ready state for
     * [WhisperModelSpec.CLOUD_API]. There is nothing on disk to
     * verify (the model runs on Groq's cloud), so the cloud row's
     * status is derived from the spec itself, NOT from
     * `AiModels.isVerifiedInstalled` (which pre-fix read the
     * Whisper Small file and made the cloud row show "Download").
     */
    CLOUD_READY;

    /**
     * Short user-facing label. The AI Subtitle card's status
     * pill renders this directly.
     */
    val pillLabel: String
        get() = when (this) {
            INSTALLED -> "Ready"
            NOT_INSTALLED -> "Download"
            DOWNLOADING -> "Downloading…"
            FAILED -> "Failed"
            CLOUD_READY -> "Cloud"
        }

    companion object {
        /**
         * Compute the current install status for a Whisper
         * model by reading the disk and the bundled manifest.
         *
         * Delegates to [AiModels.isVerifiedInstalled] which
         * already does the SHA-256 check. If the file is
         * missing or the SHA is wrong this returns
         * [NOT_INSTALLED] (the user's next action is to
         * download); [FAILED] is reserved for in-flight errors
         * the installer reports.
         *
         * 2026-09-13 CLOUD FIX — a cloud spec ([spec.isCloud])
         * has no local file at all, so disk state is meaningless
         * for it: it is always ready the moment the Groq API key
         * is configured. Reading the (Small-mapped) disk file
         * here was the pre-fix bug that made the cloud row show
         * a "Download" pill + download button.
         */
        fun forSpec(
            context: Context,
            spec: WhisperModelSpec
        ): AiModelStatus =
            if (spec.isCloud) {
                CLOUD_READY
            } else if (AiModels.isVerifiedInstalled(context, spec.aiSpec())) {
                INSTALLED
            } else {
                NOT_INSTALLED
            }
    }
}
