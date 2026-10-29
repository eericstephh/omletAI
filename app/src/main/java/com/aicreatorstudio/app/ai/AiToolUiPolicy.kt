package com.aicreatorstudio.app.ai

import com.aicreatorstudio.app.ai.upscale.UpscaleProgress

/**
 * Pure UI predicates for the AI tool dock panels (Background
 * Remove, Auto Frame, AI Upscale, Voice Clean).
 *
 * Every Generate / Download / Cancel button in the panels binds
 * to exactly one of these functions, so the JVM unit tests can
 * pin the button-visibility contract without a Compose host:
 *
 *   - model NOT installed → show Download, Generate disabled
 *   - model DOWNLOADING / VERIFYING / INSTALLING → real
 *     progress, Generate disabled
 *   - model READY → Generate enabled
 *   - PROCESSING → real progress + Cancel
 *   - COMPLETED / FAILED / CANCELLED → result + Apply/Reset
 *
 * RAM never appears in these predicates: download eligibility
 * is storage + network, and inference eligibility is a real
 * engine load — never a free-RAM comparison.
 */
object AiToolUiPolicy {

    /** True while a model install is in flight. */
    fun isDownloading(status: AiToolStatus): Boolean =
        status == AiToolStatus.Downloading ||
            status == AiToolStatus.Verifying ||
            status == AiToolStatus.Installing

    /**
     * Show the Download button. Shown whenever the model is not
     * locally available and no install is running — including
     * the initial [AiToolStatus.Idle] / [AiToolStatus.ModelMissing]
     * states and after failure / cancellation so the user can
     * retry.
     *
     * 2026-09-06: the bundled engines (Auto Frame pose landmarker,
     * Background Remove selfie multiclass, AI Upscale ESRGAN) show
     * the same button — the install runs offline from the bundled
     * asset when the APK carries it, and falls back to a real
     * network download when it doesn't.
     *
     * 2026-10-20 — the [spec] parameter was REMOVED. It was never
     * read, and the KDoc claimed it "preserves the existing 2-arg
     * overload signature" — there is no 2-arg overload, only this one
     * function with a defaulted third parameter. A parameter that is
     * accepted and ignored invites callers to believe it does
     * something. The one caller passes the ESRGAN spec; it is now
     * simply not needed.
     */
    fun showDownload(
        status: AiToolStatus,
        modelAvailable: Boolean
    ): Boolean {
        return !modelAvailable && !isDownloading(status) &&
            status != AiToolStatus.Processing
    }

    /**
     * Enable the Generate button. Requires the model to be
     * available AND no install / run in flight. Completed,
     * Failed, Cancelled, Idle, Ready and ModelMissing-with-model
     * (engine already has what it needs) all allow a run; only
     * the in-flight states and a missing model disable it.
     */
    fun generateEnabled(status: AiToolStatus, modelAvailable: Boolean): Boolean =
        modelAvailable && !isDownloading(status) &&
            status != AiToolStatus.Processing

    /** Show the Cancel button (and the live progress bar). */
    fun showCancel(status: AiToolStatus): Boolean =
        status == AiToolStatus.Processing

    /**
     * Upscale variant. The ESRGAN run state lives in
     * [UpscaleProgress] (separate from the model-install
     * [AiToolStatus]), so Generate is enabled when the model
     * is ready, no install is running, and no upscale run is
     * in flight.
     */
    fun upscaleGenerateEnabled(
        modelStatus: AiToolStatus,
        modelReady: Boolean,
        run: UpscaleProgress?
    ): Boolean =
        modelReady && !isDownloading(modelStatus) && run !is UpscaleProgress.Running

    /** Upscale variant: show Cancel while an ESRGAN run is in flight. */
    fun upscaleShowCancel(run: UpscaleProgress?): Boolean =
        run is UpscaleProgress.Running
}
