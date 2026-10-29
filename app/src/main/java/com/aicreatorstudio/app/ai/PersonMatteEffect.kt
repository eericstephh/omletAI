package com.aicreatorstudio.app.ai

import android.content.Context
import android.graphics.Bitmap
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.util.concurrent.Executor

/**
 * Superseded stub for the per-frame alpha + background composite used by the
 * **Background Remove** export path.
 *
 * **Nothing instantiates this type**, and the export path does not go through
 * it. It is kept only as the documented landing site for a hypothetical GL
 * implementation; see below for why that implementation is not needed.
 *
 * ## Status (corrected 2026-09-17)
 *
 * This KDoc previously described the export as "preview-only" and the live
 * preview as "fully working". Both were backwards, and the record is worth
 * stating plainly because it is easy to re-invert:
 *
 * - **The export composites matting for real** — but *not here*. It is a CPU
 *   pre-render in [com.aicreatorstudio.app.export.AiExportHooks]
 *   (`prepareSourceUri` → `compositeMatting`): decode the clip through
 *   `VideoFramePipeline`, blend `mix(background, frame, alpha)` per frame from
 *   the sidecar alpha PNGs, re-encode, and hand the intermediate URI to the
 *   Transformer with audio copied sample-for-sample. Background modes are
 *   ORIGINAL / BLUR / COLOR / IMAGE / VIDEO; TRANSPARENT fails with a
 *   user-facing message because an MP4 has no alpha channel to write.
 * - **The live preview does not composite at all.** It shows a "BG Remove
 *   ready" chip. Compose's graphics layer cannot sample the pixels of a
 *   `TextureView` / `SurfaceView`, and the sibling-`Box` `RenderEffect` that
 *   once tried to was inert — a `RenderNode` effect processes only its own
 *   node's rasterised content, and an empty `Box` has none. See the Layer 2.5
 *   note in `EditorScreen`.
 *
 * So for this one feature **export is ahead of preview**, not in step with it.
 *
 * ## Why this is a stub rather than a real effect
 *
 * Media3 1.5.0's `Transformer` pipeline exposes no clean way to upload a
 * per-frame 2D texture (the mask bitmap) and sample it inside an OES-textured
 * fragment shader. A real `GlEffect` would need:
 *
 *  - An OES external texture for the foreground (Media3 provides this)
 *  - A 2D texture uploaded from the mask bitmap on every frame
 *  - A 2D texture uploaded from the replacement background
 *  - A `BaseGlShaderProgram` sampling all three to write `mix(bg, fg, a)`
 *
 * That is a meaningful chunk of GL plumbing (texture pool, executor threading,
 * frame-input listener wiring) that would duplicate a composite the CPU path
 * already performs correctly — with the further advantage that the CPU path
 * can also swap in a video background and preserve audio by construction.
 * Shipping it would therefore add risk, not capability.
 *
 * The nested `PassThroughShaderProgram` is a **no-op**: `drawFrame`
 * deliberately draws nothing, leaving the output frame equal to the input. Do
 * not mistake it for a working effect.
 *
 * @param maskLoader callback that would return the alpha bitmap for the current
 *   frame, or `null` for "no mask available". Unused.
 * @param backgroundBitmap optional static background to composite the
 *   foreground over. Unused.
 * @param threshold 0..1 — lower bound for treating a mask pixel as foreground.
 *   Unused *here*; the value that actually takes effect is
 *   [com.aicreatorstudio.app.model.MattingSettings.threshold], read by
 *   `AiExportHooks.compositeFrame` as a soft knee on the mask alpha
 *   (`[0.6t, 0.6t + 0.4]`, smoothstepped) rather than a hard cut.
 * @param featherPx edge feathering in pixels. Unused *here*, and note that
 *   feathering is not a generation-time constant any more either —
 *   `MattingSettings.feather` (0..1, normalized) is applied at export
 *   time by `MaskBitmaps.applyPostProcess` together with the expansion
 *   and reverse knobs (2026-10-25 parity change).
 */
@UnstableApi
class PersonMatteEffect(
    @Suppress("unused") private val maskLoader: (outputTimeMs: Long) -> Bitmap?,
    @Suppress("unused") private val backgroundBitmap: Bitmap?,
    @Suppress("unused") private val threshold: Float,
    @Suppress("unused") private val featherPx: Float
) : GlEffect {
    // A no-op GlEffect, and deliberately unattached. The working composite is
    // the CPU pre-render in [com.aicreatorstudio.app.export.AiExportHooks]
    // (`compositeMatting`), which consumes the sidecar alpha PNGs before the
    // Transformer ever sees the clip. The
    // [com.aicreatorstudio.app.export.RenderSegment.matting] field is what that
    // pre-render reads, so the composite can be re-run from disk without
    // re-analysing the clip.

    override fun toGlShaderProgram(
        context: Context,
        useHdr: Boolean
    ): GlShaderProgram = PassThroughShaderProgram()

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false

    /**
     * Minimal pass-through shader program. A real implementation
     * would override [drawFrame] to:
     *
     *   1. Bind a 2D texture uploaded from the current mask bitmap
     *      (provided by [maskLoader]).
     *   2. Bind another 2D texture for the replacement background
     *      (provided by [backgroundBitmap]).
     *   3. Run a fragment shader that samples the foreground OES
     *      texture, the mask, and the background, then writes
     *      `mix(bg, fg, a)`.
     *
     * The base implementation is left alone, so attaching this effect would
     * make the Transformer copy the source video unchanged. Nothing attaches
     * it — see the class KDoc for why the CPU pre-render made this
     * unnecessary.
     */
    private class PassThroughShaderProgram :
        BaseGlShaderProgram(
            /* useHighPrecisionColorComponents = */ false,
            /* texturePoolCapacity = */ 1
        ) {
        override fun configure(
            inputWidth: Int,
            inputHeight: Int
        ): Size = Size(inputWidth, inputHeight)

        override fun drawFrame(
            inputTexId: Int,
            presentationTimeUs: Long
        ) {
            // v1: pass-through. The base class handles FBO bind,
            // viewport setup, and texture pool accounting; we
            // just don't draw anything extra, which leaves the
            // output frame equal to the input frame.
        }

        @Throws(VideoFrameProcessingException::class)
        override fun release() {
            // Base class manages its own texture pool.
        }
    }
}

/**
 * Trivial [Executor] used by the [GlEffect] interface when callers
 * don't supply their own. Provided here so a future v2
 * implementation that needs to schedule GL work has a stable home.
 */
@Suppress("unused")
internal class SingleThreadGlExecutor : Executor {
    private val delegate = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "PersonMatteGl").apply { isDaemon = true }
    }
    override fun execute(command: Runnable) = delegate.execute(command)
}
