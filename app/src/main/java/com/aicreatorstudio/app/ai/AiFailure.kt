package com.aicreatorstudio.app.ai

/**
 * Five distinct failure modes the AI subsystem reports.
 *
 * The brief requires these errors be surfaced separately to the UI
 * so the user can tell whether they need to (a) wait for a download,
 * (b) check storage, (c) retry because the bytes were tampered, or
 * (d) accept that this device cannot run Whisper at all. Previously
 * every failure was collapsed to a generic
 * `"هیچ موتور رونویسی در دسترس نیست"` which made the failure
 * undiagnosable.
 *
 * | Failure              | When                                                  |
 * |----------------------|-------------------------------------------------------|
 * | ENGINE_UNAVAILABLE   | The native library (.so / TFLite runtime) failed to   |
 * |                      | load. This is a *hardware / ABI* problem, not a       |
 * |                      | user-actionable retry.                                |
 * | MODEL_DOWNLOAD_FAILED| HTTPS request returned non-2xx, or the network        |
 * |                      | timed out, or the storage write failed. Retryable.    |
 * | MODEL_VERIFICATION_  | The file landed on disk but its SHA-256 doesn't match |
 * |  FAILED              | the manifest. The bytes were tampered or the          |
 * |                      | publisher changed the artifact. Retryable; the user  |
 * |                      | should NOT continue with a mismatched file.           |
 * | MODEL_LOAD_FAILED    | The verified file exists but the runtime could not    |
 * |                      | load it (e.g. TFLite Interpreter threw on a corrupt  |
 * |                      | or unsupported model). Retryable.                    |
 * | TRANSCRIPTION_FAILED | The model loaded but inference threw mid-run (e.g.    |
 * |                      | unsupported audio format). Retryable.                |
 * | BUNDLED_ASSET_MISSING| Kept for API stability (2026-09-06).  The bundled asset is |
 * |                      | missing and no download URL could repair it. The |
 * |                      | normal install path now falls back to an in-app  |
 * |                      | download instead of failing with this kind.      |
 */
enum class AiFailure {
    ENGINE_UNAVAILABLE,
    MODEL_DOWNLOAD_FAILED,
    MODEL_VERIFICATION_FAILED,
    MODEL_LOAD_FAILED,
    TRANSCRIPTION_FAILED,
    INSUFFICIENT_STORAGE,
    NETWORK_TIMEOUT,
    /**
     * Kept for API stability (2026-09-06). The install path no
     * longer hard-fails on a missing bundled asset — it falls
     * back to the in-app download from models.lock.json. This
     * kind remains for environments where no URL is configured
     * and the bundled asset is gone.
     */
    BUNDLED_ASSET_MISSING;

    /**
     * One-line Persian message for the UI. Stable across releases.
     *
     * 2026-10-13 — the [ENGINE_UNAVAILABLE] arm no longer says
     * «این دستگاه از پردازنده‌ی لازم پشتیبانی نمی‌کند». That sentence
     * was emitted from six unrelated call sites (missing `.so`, JNI
     * symbol mismatch, model-init failure, real OOM) and blamed the
     * user's processor for all of them. Every one of those sites now
     * routes through [NativeEngineFailure.classify], which produces a
     * specific message; this arm is only reached when the caller has
     * no better information, so it points at the report instead of
     * asserting a cause it cannot know.
     */
    fun persianMessage(): String = when (this) {
        ENGINE_UNAVAILABLE ->
            "موتور بومی بارگذاری نشد. لطفاً دوباره تلاش کنید؛ اگر تکرار شد برنامه را باز و بسته کنید."
        MODEL_DOWNLOAD_FAILED ->
            "دانلود مدل ناموفق بود. اتصال اینترنت را بررسی کنید و دوباره تلاش کنید."
        MODEL_VERIFICATION_FAILED ->
            "تأیید صحت مدل ناموفق بود. فایل دریافت‌شده با نسخه‌ی رسمی مطابقت ندارد."
        MODEL_LOAD_FAILED ->
            "بارگذاری مدل ناموفق بود. فایل ممکن است خراب شده باشد؛ دوباره دانلود کنید."
        TRANSCRIPTION_FAILED ->
            "پردازش صدا ناموفق بود. فرمت صوتی ممکن است پشتیبانی نشود."
        INSUFFICIENT_STORAGE ->
            "فضای ذخیره‌سازی کافی نیست. چند پروژه را حذف کنید و دوباره تلاش کنید."
        NETWORK_TIMEOUT ->
            "پاسخ‌گویی شبکه طولانی شد. اتصال اینترنت را بررسی کنید و دوباره تلاش کنید."
        BUNDLED_ASSET_MISSING ->
            "مدل داخلی برنامه یافت نشد. بسته‌ی نصب ممکن است خراب شده باشد؛ لطفاً برنامه را دوباره نصب کنید."
    }
}

/**
 * Full structured result of an install+load attempt. Replaces the
 * legacy [ModelInstallResult] sealed interface for any caller that
 * wants per-failure precision. The legacy `ModelInstallResult` is
 * preserved for the existing tests; new code should use
 * [AiLoadOutcome] instead.
 *
 * The [AiFailure.kind] field is null when [phase] == READY.
 */
data class AiLoadOutcome(
    val spec: CatalogEntry,
    val phase: AiPhase,
    val failure: AiFailure? = null,
    /** User-facing detail message; not localized (the kind is). */
    val detail: String? = null
) {
    val isReady: Boolean get() = phase == AiPhase.READY && failure == null
    val isFailed: Boolean get() = failure != null

    companion object {
        fun ready(spec: CatalogEntry) = AiLoadOutcome(spec, AiPhase.READY)
        fun failed(spec: CatalogEntry, phase: AiPhase, kind: AiFailure, detail: String? = null) =
            AiLoadOutcome(spec, phase, kind, detail)
    }
}

/**
 * The lifecycle states the brief requires. Distinct from
 * [com.aicreatorstudio.app.ai.AiToolStatus] (which is the editor-side
 * UI state) — these are the runtime lifecycle phases the installer
 * reports.
 */
enum class AiPhase {
    NOT_INSTALLED,
    DOWNLOADING,
    VERIFYING,
    INSTALLING,
    LOADING,
    READY
}

/**
 * Listener for phase transitions. Called from
 * [ModelInstaller.installWithManifest] before each phase change so
 * the UI can paint the right status text. [onProgress] (0f..1f) is
 * called frequently within a phase.
 */
fun interface AiPhaseListener {
    fun onPhase(phase: AiPhase, progress: Float)
}

/**
 * A Whisper failure with a **machine-readable stage** attached.
 *
 * ## Why this type exists (2026-10-11 compatibility audit)
 *
 * Before this, every offline-Whisper failure reached the UI as a bare
 * `Exception(<Persian prose>)`. That made a user report undiagnosable,
 * because at least five genuinely different faults — missing file,
 * truncated file, SHA mismatch, native init returning `NULL`, and a
 * native allocation failure — all produced the same sentence
 * ("the file may be corrupt; download it again"). A user with a
 * memory-pressure problem would be told to re-download 465 MB, which
 * cannot possibly help.
 *
 * [stage] is the diagnosis. [message] is the user-facing Persian text.
 * The pair travels together through `Result.failure`, so the ViewModel
 * can show the right sentence *and* a bug report can say exactly which
 * stage died.
 *
 * This is deliberately a plain [Exception] subclass so every existing
 * `catch (e: Exception)` / `Result.failure(e)` call site keeps working
 * unchanged — no cascade is introduced by adopting it.
 *
 * Visibility is `internal` because [stage] is typed by the
 * `internal` [WhisperDiagnostics.Stage] enum and because every consumer
 * lives in this package. It is still a normal [Exception], so the
 * `ViewModel` and the UI keep handling it through the ordinary
 * `Exception` path with no change.
 */
internal class WhisperFailure(
    val stage: WhisperDiagnostics.Stage,
    message: String,
    cause: Throwable? = null,
    /**
     * Extra machine-readable context for a bug report. Metadata only —
     * never a path, never media content, never a secret.
     */
    val detail: String? = null
) : Exception(message, cause) {

    override fun toString(): String =
        "WhisperFailure(stage=$stage, message=${message ?: "?"}, detail=$detail)"
}