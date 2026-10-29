package com.aicreatorstudio.app.ai.upscale

import android.content.Context
import android.util.Log
import com.aicreatorstudio.app.ai.ModelRegistry
import com.aicreatorstudio.app.ai.UnifiedModelInstaller
import com.aicreatorstudio.app.export.ExportConfig
import com.aicreatorstudio.app.model.VideoProject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fast (low-end) AI upscale: real ESRGAN neural 2x.
 *
 * Runs the verified ESRGAN TFLite model on a 0.5x bilinear
 * prefilter of every frame (net exactly 2x, aspect/FPS/duration/
 * audio preserved) through the shared [VideoFramePipeline]:
 * decode → per-frame tiled inference → AVC encode → audio
 * passthrough mux. Fully offline after the one-time model
 * download; frame-by-frame so RAM stays flat on long videos.
 *
 * Device tiers come from [DeviceCapabilityDetector] (real
 * RAM/core/ABI/storage signals, never the GPU marketing name):
 * low-RAM devices get smaller inference tiles automatically.
 */
@Singleton
class FastUpscaleEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val installer: UnifiedModelInstaller,
    private val capability: DeviceCapabilityDetector
) : UpscaleEngine {

    override val id: String = UpscaleEngineId.FAST.key
    override val displayName: String = "Fast SR (ESRGAN 2x)"

    override val isAvailable: Boolean
        get() = runCatching {
            val registry = ModelRegistry.fromAssets(context).getOrThrow()
            val spec = registry.byId(EsrganUpscalePipeline.MODEL_ID) ?: return false
            installer.isVerifiedInstalled(spec)
        }.getOrDefault(false)

    override val modelStatusText: String
        get() = if (isAvailable) "Model: ESRGAN 2x · ready (offline)"
        else "Model: ESRGAN 2x · not installed"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJob = AtomicReference<Job?>(null)

    override fun start(
        project: VideoProject,
        profile: UpscaleProfile,
        config: ExportConfig
    ): UpscaleJob {
        activeJob.getAndSet(null)?.cancel()

        // 2026-10-20 — `replay = 1` so the terminal event (Succeeded /
        // Failed / Cancelled) cannot be lost. The old `replay = 0` +
        // `tryEmit` combination dropped any event emitted before the
        // collector subscribed AND silently dropped events once the
        // 64-slot buffer filled — which is exactly what happened to
        // the final `Succeeded` after a long job: the UI stayed on the
        // last progress value forever and the dialog never closed.
        val flow = MutableSharedFlow<UpscaleProgress>(replay = 1, extraBufferCapacity = 64)
        // 2026-10-20 — the flag is read on the worker thread and
        // written on the UI thread; a plain `var` is not guaranteed to
        // be visible across that boundary.
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val parent = scope.launch {
            try {
                val registry = ModelRegistry.fromAssets(context).getOrThrow()
                EsrganUpscalePipeline.run(
                    context = context,
                    project = project,
                    profile = UpscaleProfile.Fast,
                    config = config,
                    engineId = id,
                    displayName = displayName,
                    registry = registry,
                    installer = installer,
                    capability = capability,
                    sink = flow
                )
            } catch (t: Throwable) {
                if (cancelled.get() || t is kotlinx.coroutines.CancellationException) {
                    flow.tryEmit(UpscaleProgress.Cancelled)
                } else {
                    Log.w("FastUpscale", "upscale failed", t)
                    flow.tryEmit(UpscaleProgress.Failed(t.message ?: "Fast upscale failed."))
                }
            }
        }
        activeJob.set(parent)
        return object : UpscaleJob {
            override val progress: Flow<UpscaleProgress> = flow.asSharedFlow()
            override val isActive: Boolean
                get() = activeJob.get()?.isActive == true && !cancelled.get()
            override fun cancel() {
                cancelled.set(true)
                activeJob.getAndSet(null)?.cancel()
                flow.tryEmit(UpscaleProgress.Cancelled)
            }
        }
    }
}
