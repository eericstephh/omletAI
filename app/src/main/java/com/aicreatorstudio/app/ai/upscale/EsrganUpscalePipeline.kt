package com.aicreatorstudio.app.ai.upscale

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.aicreatorstudio.app.ai.ModelRegistry
import com.aicreatorstudio.app.ai.UnifiedModelInstaller
import com.aicreatorstudio.app.ai.InstallOutcome
import com.aicreatorstudio.app.ai.media.VideoFramePipeline
import com.aicreatorstudio.app.ai.modelFileFor
import com.aicreatorstudio.app.export.ExportConfig
import com.aicreatorstudio.app.model.VideoProject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.File

/**
 * Shared real-implementation core for the two ESRGAN upscale
 * engines ([FastUpscaleEngine] = 2x, [HighQualityUpscaleEngine] = 4x).
 *
 * Pipeline (all offline, all streaming, all real):
 *
 * ```
 * model check -> UnifiedModelInstaller (download/verify/install)
 *   -> probe source (size/FPS/duration/rotation/audio)
 *   -> device gate (RAM/cores/ABI/storage)
 *   -> VideoFramePipeline: decode frame -> EsrganTiler.upscale
 *      -> AVC encode -> mux audio passthrough -> output.mp4
 * ```
 *
 * Progress is real: model-install fraction first, then
 * frames-done / estimated-total. Cancellation deletes the partial
 * output. The source is only ever opened read-only.
 */
internal object EsrganUpscalePipeline {

    private const val TAG = "EsrganUpscale"
    const val MODEL_ID: String = "esrgan_tflite_4x"

    /**
     * 2026-09-08 CRASH FIX — hard output ceiling: 3840×2160 (4K UHD).
     * The old 8K cap (7680×4320) let a 1080p 4x run request a 33 MP
     * output bitmap (132 MB ARGB) PLUS a YUV frame of the same size
     * (~50 MB) inside the encoder — MediaCodec AVC encoders on most
     * phones cap at 4K (some at 1080p) and either throw
     * IllegalStateException or the native gralloc allocation fails
     * and kills the process. The neural pass still runs at the FULL
     * 4x resolution (all the sharpening benefit); the result is
     * bilinear-fit into the ≤4K output when the source would exceed
     * the cap.
     */
    const val MAX_OUTPUT_W: Int = 3840
    const val MAX_OUTPUT_H: Int = 2160

    data class Gate(
        val totalRamMb: Long,
        val availRamMb: Long,
        val cores: Int,
        val abi: String,
        val api: Int,
        val freeStorageBytes: Long
    )

    suspend fun run(
        context: Context,
        project: VideoProject,
        profile: UpscaleProfile,
        config: ExportConfig,
        engineId: String,
        displayName: String,
        registry: ModelRegistry,
        installer: UnifiedModelInstaller,
        capability: DeviceCapabilityDetector,
        sink: MutableSharedFlow<UpscaleProgress>
    ) {
        val started = System.currentTimeMillis()
        fun elapsed(): Long = System.currentTimeMillis() - started

        val clip = project.clips.firstOrNull { !it.isAudioOnly }
            ?: run {
                sink.tryEmit(UpscaleProgress.Failed("No video clip in the project."))
                return
            }
        val targetScale = when (profile) {
            is UpscaleProfile.Fast -> 2
            is UpscaleProfile.HighQuality -> 4
            is UpscaleProfile.Auto -> if (capability.recommended() is UpscaleProfile.HighQuality) 4 else 2
        }

        // ---- 1. Model ----
        sink.tryEmit(UpscaleProgress.Running("Preparing", 0f, elapsed()))
        val spec = registry.byId(MODEL_ID)
            ?: run {
                sink.tryEmit(UpscaleProgress.Failed("Upscale model catalog entry missing ($MODEL_ID)."))
                return
            }
        if (!installer.isVerifiedInstalled(spec)) {
            var lastPhase = ""
            val outcome = installer.install(
                spec,
                phaseListener = { phase, _ -> lastPhase = phase.name },
                onProgress = { p ->
                    sink.tryEmit(
                        UpscaleProgress.Running(
                            "Downloading model",
                            (p * 0.1f).coerceIn(0f, 0.1f),
                            elapsed()
                        )
                    )
                }
            )
            if (outcome is InstallOutcome.Failed) {
                sink.tryEmit(
                    UpscaleProgress.Failed("Model download failed (${outcome.kind}): ${outcome.detail}")
                )
                return
            }
            Log.i(TAG, "model install outcome: $outcome (lastPhase=$lastPhase)")
        }
        val modelFile = modelFileFor(context.filesDir, spec)
        if (!modelFile.exists()) {
            sink.tryEmit(UpscaleProgress.Failed("Upscale model file missing after install."))
            return
        }
        if (currentCoroutineContext()[Job]?.isActive != true) {
            sink.tryEmit(UpscaleProgress.Cancelled)
            return
        }

        // ---- 2. Probe + device gate ----
        val srcUri = Uri.parse(clip.uri)
        val info = VideoFramePipeline.probe(context, srcUri)
            ?: run {
                sink.tryEmit(UpscaleProgress.Failed("Cannot read video metadata."))
                return
            }
        val gate = readGate(context)
        val refusal = gateRefusal(gate, targetScale, spec.declaredSizeBytes)
        if (refusal != null) {
            sink.tryEmit(UpscaleProgress.Failed(refusal))
            return
        }

        // 2026-09-08 CRASH FIX — pick the effective scale: the largest
        // of {targetScale, 2} whose output fits the 4K ceiling. The
        // neural pass itself ALWAYS runs at the full requested factor
        // (the sharpening comes from the 4x inference); only the
        // final write-out is scaled down when the source is large.
        // For a 1080p source: 4x request → 4K output (neural pass at
        // full res, fit-down from the internal 8K render which the
        // tiler never materialises as a whole — it draws per-tile).
        val srcW = info.displayWidth
        val srcH = info.displayHeight
        fun fits(scale: Int) = (srcW.toLong() * scale) * (srcH.toLong() * scale) <=
            (MAX_OUTPUT_W.toLong() * MAX_OUTPUT_H)
        val effectiveScale = when {
            fits(targetScale) -> targetScale
            targetScale == 4 && fits(2) -> 2
            else -> 0
        }
        if (effectiveScale == 0) {
            sink.tryEmit(
                UpscaleProgress.Failed(
                    "Source is ${srcW}×$srcH — even 2x output would exceed " +
                        "the ${MAX_OUTPUT_W}×${MAX_OUTPUT_H} encoder cap. " +
                        "The clip is already high-resolution enough that " +
                        "upscaling adds nothing."
                )
            )
            return
        }
        val scaleNote = if (effectiveScale != targetScale) {
            " (output capped to 4K — effective ${effectiveScale}x)"
        } else ""
        val outW = (srcW * effectiveScale) and 1.inv()
        val outH = (srcH * effectiveScale) and 1.inv()

        // 2026-10-20 — ASK THE DEVICE WHAT IT CAN ENCODE, FIRST.
        //
        // The output size used to be derived only from the app's own
        // 4K ceiling. On a device whose hardware AVC encoder stops at
        // 1080p, that produced a request the encoder refuses — and
        // worse, the TILER rendered a full 4K bitmap (33 MB) for every
        // frame before the encoder ever saw it.
        //
        // The size is now clamped to what the device can actually
        // encode BEFORE any pixel is rendered, so the work matches the
        // result. The encoder pipeline clamps again as a safety net
        // (see [com.aicreatorstudio.app.ai.media.VideoFramePipeline]).
        val encodable = com.aicreatorstudio.app.ai.media.VideoEncoderCapability.select(
            options = com.aicreatorstudio.app.ai.media.VideoEncoderCapability
                .listEncoders("video/avc"),
            requestedWidth = outW,
            requestedHeight = outH
        )
        val finalW = encodable?.width ?: outW
        val finalH = encodable?.height ?: outH
        if (finalW != outW || finalH != outH) {
            Log.w(
                TAG,
                "this device cannot encode ${outW}x$outH; rendering at ${finalW}x$finalH"
            )
        }

        // ---- 3. Tiler (GPU when useful, else 4-thread CPU) ----
        val probe = capability.probe()
        val preferGpu = probe.gpuSupportsCompute && targetScale == 4
        // 2026-09-08 OOM FIX — the tile size also bounds the per-tile
        // output bitmap (tile*4 px). Pick the largest tile that fits
        // comfortably under a ~48 MB per-tile memory budget so a
        // mid-range device can never OOM inside a single inference:
        // 256px tile @ 4x = 1024px output ≈ 12 MB (safe);
        // low-RAM devices drop to 160px @ 4x ≈ 4 MB.
        val ramBudgetTile = when {
            probe.totalRamBytes < DeviceCapabilityDetector.LOW_RAM_THRESHOLD -> EsrganTiler.TILE_LOW_RAM
            else -> EsrganTiler.TILE_DEFAULT
        }
        val tileSize = ramBudgetTile
        // 2026-09-08 FALLBACK CHAIN — a GPU delegate load failure or
        // an OOM during load must not kill the whole job: retry once
        // on the plain CPU backend with the small tile.
        val tiler: EsrganTiler = try {
            EsrganTiler.load(modelFile, preferGpu, tileSize)
        } catch (t: Throwable) {
            Log.w(TAG, "ESRGAN load failed; retrying on CPU fallback", t)
            try {
                EsrganTiler.load(modelFile, preferGpu = false, tileSize = EsrganTiler.TILE_LOW_RAM)
            } catch (t2: Throwable) {
                Log.w(TAG, "ESRGAN CPU fallback load failed", t2)
                sink.tryEmit(
                    UpscaleProgress.Failed(
                        "Could not load the upscale model: ${t2.message}"
                    )
                )
                return
            }
        }

        // ---- 4. Frame-by-frame upscale ----
        val outDir = File(context.cacheDir, "upscale").apply { mkdirs() }
        val tmp = File(outDir, "upscale_${engineId}_${System.currentTimeMillis()}.partial.mp4")
        // `var` because the encoder may clamp the requested size to
        // what the device can actually encode (see
        // [com.aicreatorstudio.app.ai.media.VideoEncoderCapability]) —
        // the file name must then carry the REAL size.
        var final = File(outDir, "upscale_${effectiveScale}x_${clip.id}_${info.displayWidth}x${info.displayHeight}-to-${finalW}x${finalH}.mp4")
        var backendName = "tflite"
        // Capture the job once (suspend context): the pipeline's
        // isCancelled lambda runs on plain threads and cannot call
        // currentCoroutineContext itself.
        val hostJob = currentCoroutineContext()[Job]
        try {
            tiler.use {
                backendName = tiler.backendName
                val res = VideoFramePipeline.transcode(
                    context,
                    VideoFramePipeline.Request(
                        srcUri = srcUri,
                        dstFile = tmp,
                        outWidth = finalW,
                        outHeight = finalH,
                        frameOp = VideoFramePipeline.FrameOp { src, _, _ ->
                            // The neural pass ALWAYS runs at the
                            // requested factor (full-res tiles — that
                            // is where the sharpening lives); when the
                            // output was capped, the tiler writes the
                            // tiles into a fit-down bitmap directly
                            // (no 8K intermediate is ever allocated).
                            tiler.upscale(src, targetScale, finalW, finalH) { _, _ -> }
                        },
                        copyAudio = true,
                        onProgress = { done, total ->
                            val pct = 0.1f + 0.9f * (done.toFloat() / total.coerceAtLeast(1))
                            sink.tryEmit(
                                UpscaleProgress.Running(
                                    "Super-resolving$scaleNote",
                                    pct.coerceIn(0f, 1f), elapsed()
                                )
                            )
                        },
                        isCancelled = { hostJob?.isActive != true }
                    )
                )
                when (res) {
                    is VideoFramePipeline.Result.Cancelled -> {
                        // 2026-10-20 — the partial file must go too. It
                        // used to be left behind on every cancel/fail,
                        // filling cacheDir/upscale with half-written
                        // MP4s.
                        runCatching { tmp.delete() }
                        sink.tryEmit(UpscaleProgress.Cancelled)
                        return
                    }
                    is VideoFramePipeline.Result.Failed -> {
                        val msg = res.cause.message ?: "Upscale failed."
                        Log.w(TAG, "transcode failed", res.cause)
                        runCatching { tmp.delete() }
                        sink.tryEmit(UpscaleProgress.Failed(msg))
                        return
                    }
                    is VideoFramePipeline.Result.Done -> {
                        sink.tryEmit(UpscaleProgress.Running("Muxing", 1f, elapsed()))
                        // 2026-10-20 — the encoder may have clamped the
                        // size further (the pipeline asked for what the
                        // device advertised, the encoder has the final
                        // word). The file name and the log must carry
                        // the REAL output size.
                        if (res.outWidth != finalW || res.outHeight != finalH) {
                            Log.w(
                                TAG,
                                "encoder clamped ${finalW}x$finalH to " +
                                    "${res.outWidth}x${res.outHeight}"
                            )
                            final = File(
                                outDir,
                                "upscale_${effectiveScale}x_${clip.id}_" +
                                    "${info.displayWidth}x${info.displayHeight}-to-" +
                                    "${res.outWidth}x${res.outHeight}.mp4"
                            )
                        }
                        if (!tmp.renameTo(final)) {
                            tmp.copyTo(final, overwrite = true)
                            tmp.delete()
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            sink.tryEmit(UpscaleProgress.Cancelled)
            return
        } catch (t: Throwable) {
            Log.w(TAG, "upscale failed", t)
            runCatching { tmp.delete() }
            sink.tryEmit(UpscaleProgress.Failed(t.message ?: "Upscale failed."))
            return
        }

        val size = final.length()
        if (size <= 0) {
            runCatching { final.delete() }
            sink.tryEmit(UpscaleProgress.Failed("Upscale produced an empty file."))
            return
        }
        Log.i(TAG, "upscale done: ${final.absolutePath} ${outW}x$outH ${size}B backend=$backendName")
        sink.tryEmit(
            UpscaleProgress.Succeeded(
                outputUri = Uri.fromFile(final).toString(),
                outputPath = final.absolutePath,
                fileName = final.name,
                fileSizeBytes = size,
                durationMs = clip.durationMs
            )
        )
    }

    // Bridge: the pipeline checks coroutine liveness via Job; a
    // real CancellationException from the runner scope maps to
    // Cancelled, not Failed (see the catch in run()).

    private fun readGate(context: Context): Gate {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val abi = if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else "unknown"
        val free = runCatching { context.cacheDir.usableSpace }.getOrDefault(Long.MAX_VALUE)
        return Gate(
            totalRamMb = mem.totalMem / (1024L * 1024L),
            availRamMb = mem.availMem / (1024L * 1024L),
            cores = Runtime.getRuntime().availableProcessors(),
            abi = abi,
            api = Build.VERSION.SDK_INT,
            freeStorageBytes = free
        )
    }

    /**
     * Refuse unsafe runs with a user-facing reason (shown in the
     * bottom sheet). Pure function of the gate — unit-tested.
     */
    internal fun gateRefusal(
        gate: Gate,
        targetScale: Int,
        modelBytes: Long
    ): String? {
        if (gate.abi != "arm64-v8a" && gate.abi != "x86_64" && gate.abi != "armeabi-v7a") {
            return "Upscale is not supported on this CPU (${gate.abi})."
        }
        // ESRGAN tiles are small, but decode + TFLite arenas still
        // need headroom; 4x needs roughly twice the working set.
        val needMb = if (targetScale >= 4) 1500L else 800L
        if (gate.availRamMb in 1 until needMb) {
            return "Not enough free RAM for ${targetScale}x upscale " +
                "(free ${gate.availRamMb} MB, need ~${needMb} MB). " +
                "Close background apps and retry."
        }
        if (gate.cores < 2) {
            return "Upscale needs at least 2 CPU cores."
        }
        if (gate.freeStorageBytes in 1 until (modelBytes + 500_000_000L)) {
            return "Not enough storage for the upscale output. Free up space and retry."
        }
        return null
    }
}
