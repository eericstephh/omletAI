package com.aicreatorstudio.app.ai.upscale

import android.net.Uri
import com.aicreatorstudio.app.export.ExportConfig
import com.aicreatorstudio.app.model.VideoProject
import kotlinx.coroutines.flow.Flow

/**
 * Public contract for the AI Video Upscaler module.
 *
 * Every implementation owns its own decode → super-resolve → encode
 * pipeline. The interface deliberately keeps the contract small:
 *
 *  - The engine has a stable [id] (matches [UpscaleEngineId]) so the
 *    [UpscaleEngineRegistry] can route a profile to the right impl.
 *  - [isAvailable] is a fast, side-effect-free check the UI can use
 *    to dim the "High Quality" card on a low-end device without
 *    needing the full probe.
 *  - [start] is the only "heavy" entry point; it returns a flow so
 *    the bottom sheet can render a live progress bar.
 *  - [cancel] is best-effort. It must be idempotent and never throw.
 *
 * Implementations are free to interpret the contract liberally:
 *
 *  - The "Fast" implementation runs a Media3 Lanczos + 2x pipeline.
 *  - The "High Quality" implementation runs a frame-by-frame
 *    super-resolution loop using the bundled TFLite / ONNX model
 *    (or the shader fallback) and reuses [ExportConfig] for the
 *    output resolution.
 *
 * Implementations must not retain a reference to the project or
 * the source URIs beyond the lifetime of the [UpscaleJob] they
 * return; the host ViewModel is the single owner of project state.
 */
interface UpscaleEngine {

    /**
     * Engine id, e.g. `UpscaleEngineId.FAST.key`. The registry uses
     * this to route profile → engine. Must be stable across app
     * versions.
     */
    val id: String

    /**
     * Short user-visible name. Shown in the bottom sheet's status
     * row (e.g. "Engine: TFLite CPU", "Engine: Real-ESRGAN GPU").
     */
    val displayName: String

    /**
     * True when the engine can run right now without any extra
     * setup. A TFLite engine returns false if the .tflite model file
     * isn't downloaded yet. A shader-based engine is always true.
     *
     * Implementations should make this check cheap — the UI calls
     * it once per recomposition.
     */
    val isAvailable: Boolean

    /**
     * One-line status text shown in the bottom sheet under the
     * profile cards. Examples: "Engine: Lanczos 2x · ready",
     * "Engine: Real-ESRGAN 4x · model not installed". The default
     * in the interface is "Engine: <displayName>" so engines
     * without a special status can omit the override.
     */
    val modelStatusText: String
        get() = "Engine: $displayName"

    /**
     * Begin an upscale job. The returned [UpscaleJob] is a live
     * handle: callers observe [UpscaleJob.progress] for the progress
     * flow and call [UpscaleJob.cancel] (or [cancel]) to abort.
     *
     * Engines may cancel any in-flight job before starting a new
     * one. The [Flow] returned by [UpscaleJob.progress] is
     * single-collector; collecting twice concurrently will throw.
     */
    fun start(
        project: VideoProject,
        profile: UpscaleProfile,
        config: ExportConfig
    ): UpscaleJob
}

/**
 * One in-flight upscale run. Hosts read [progress] for the UI and
 * call [cancel] to abort.
 *
 * The contract mirrors `Job` from kotlinx-coroutines — engines
 * that wrap the long-running work in a coroutine should expose the
 * underlying job so [cancel] can call `Job.cancel()`. Engines that
 * run on a background thread (e.g. a `HandlerThread`) can wrap that
 * thread in a CompletableJob internally.
 */
interface UpscaleJob {
    /**
     * Live progress flow. Emits [UpscaleProgress] values from 0f
     * (just started) through 1f (succeeded). On failure the flow
     * emits a final [UpscaleProgress.Failed]; on cancel it emits
     * [UpscaleProgress.Cancelled].
     */
    val progress: Flow<UpscaleProgress>

    /**
     * True while the engine is still producing frames. Becomes
     * false as soon as a terminal state (Succeeded / Failed /
     * Cancelled) is emitted on the [progress] flow.
     */
    val isActive: Boolean

    /**
     * Best-effort cancellation. Safe to call from any thread; the
     * implementation is responsible for stopping the encode loop,
     * releasing the output file, and emitting [UpscaleProgress.Cancelled]
     * on the [progress] flow.
     */
    fun cancel()
}

/**
 * One tick of progress emitted by [UpscaleJob.progress]. Designed
 * so the bottom sheet can compute both an overall percentage and
 * an estimated time remaining in a single read.
 *
 * [stage] is the human-readable label for the current pipeline
 * stage (e.g. "Decoding", "Super-resolving", "Encoding"). The
 * strings are stable across engines so the bottom sheet can map
 * them to localised labels.
 *
 * [percent] is a 0..1 float for the current stage. The bottom
 * sheet derives the *overall* percentage by combining it with the
 * stage boundary, and the *estimated time remaining* by tracking
 * the rate of `percent` increase since the last tick.
 */
sealed class UpscaleProgress {

    /** The pipeline is doing meaningful work. */
    data class Running(
        val stage: String,
        val percent: Float,
        /**
         * Approximate milliseconds already spent on the *current*
         * stage. The host can derive overall ETA by tracking the
         * deltas between successive ticks.
         */
        val elapsedMs: Long
    ) : UpscaleProgress()

    /**
     * The upscale finished successfully. [outputUri] is the
     * content/file URI of the produced MP4; [outputPath] is the
     * local filesystem path (nullable when the engine wrote to a
     * `content://` URI only).
     */
    data class Succeeded(
        val outputUri: String,
        val outputPath: String?,
        val fileName: String,
        val fileSizeBytes: Long,
        val durationMs: Long
    ) : UpscaleProgress()

    /** The upscale failed. [message] is user-facing. */
    data class Failed(val message: String) : UpscaleProgress()

    /** The user cancelled. */
    data object Cancelled : UpscaleProgress()
}
