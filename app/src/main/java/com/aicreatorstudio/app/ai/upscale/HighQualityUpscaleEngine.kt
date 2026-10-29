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
 * High-quality (flagship) AI upscale: real ESRGAN neural 4x.
 *
 * Same verified ESRGAN TFLite model and same streaming pipeline
 * as [FastUpscaleEngine], run at full frame resolution for a true
 * 4x output (aspect/FPS/duration/audio preserved). Prefers the
 * TFLite GPU delegate and larger inference tiles; falls back to
 * 4-thread CPU automatically. Fully offline after the one-time
 * model download.
 *
 * When the device cannot safely run 4x (low free RAM, weak CPU,
 * no storage headroom) the run is refused with the concrete
 * reason instead of OOMing — see
 * [EsrganUpscalePipeline.gateRefusal]. The UI then offers the 2x
 * Fast profile, which shares the same model file (no re-download).
 */
@Singleton
class HighQualityUpscaleEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val installer: UnifiedModelInstaller,
    private val capability: DeviceCapabilityDetector
) : UpscaleEngine {

    override val id: String = UpscaleEngineId.HIGH_QUALITY.key
    override val displayName: String = "ESRGAN 4x"

    override val modelStatusText: String
        get() = if (isAvailable) {
            "Model: ESRGAN 4x · ready (offline)"
        } else {
            "Model: ESRGAN 4x · not installed"
        }

    override val isAvailable: Boolean
        get() = runCatching {
            val registry = ModelRegistry.fromAssets(context).getOrThrow()
            val spec = registry.byId(EsrganUpscalePipeline.MODEL_ID) ?: return false
            installer.isVerifiedInstalled(spec)
        }.getOrDefault(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJob = AtomicReference<Job?>(null)

    override fun start(
        project: VideoProject,
        profile: UpscaleProfile,
        config: ExportConfig
    ): UpscaleJob {
        activeJob.getAndSet(null)?.cancel()

        // 2026-10-20 — `replay = 1` so the terminal event cannot be
        // lost. See the note in [FastUpscaleEngine.start].
        val flow = MutableSharedFlow<UpscaleProgress>(replay = 1, extraBufferCapacity = 64)
        // 2026-10-20 — cross-thread visibility for the cancel flag.
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val parent = scope.launch {
            try {
                val registry = ModelRegistry.fromAssets(context).getOrThrow()
                EsrganUpscalePipeline.run(
                    context = context,
                    project = project,
                    profile = UpscaleProfile.HighQuality,
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
                    Log.w("HqUpscale", "upscale failed", t)
                    flow.tryEmit(UpscaleProgress.Failed(t.message ?: "High-quality upscale failed."))
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
