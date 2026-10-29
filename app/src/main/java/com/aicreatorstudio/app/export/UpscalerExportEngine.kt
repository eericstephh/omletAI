package com.aicreatorstudio.app.export

import android.content.Context
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.aicreatorstudio.app.model.UpscalerPreset
import com.aicreatorstudio.app.model.UpscalerSettings
import com.aicreatorstudio.app.model.VideoProject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Public contract for the AI Video Upscaler export engine.
 *
 * Mirrors [VideoExportEngine] but emits [UpscalerExportState] (three
 * stages: Enhancing → Reconstructing → Finalizing) instead of the
 * generic [ExportState].
 *
 * The implementation is a Media3 `Transformer`-backed pipeline that
 * applies the upscale preset's `targetScale` via a [Presentation] effect
 * and bakes in the CAS sharpening values as `Brightness`/`Contrast`
 * Media3 effects. A real ESRGAN TFLite model is a future extension
 * point — when present, the engine can use it for per-frame
 * super-resolution; when absent (today's default), the pipeline uses
 * Media3's GL sampler as the upscale mechanism.
 */
@UnstableApi
interface UpscalerExportEngine {
    /**
     * Begin an upscale export. Replaces any currently running upscale
     * export; if a previous one was in flight it is cancelled.
     */
    fun startUpscaleExport(
        project: VideoProject,
        settings: UpscalerSettings,
        config: ExportConfig
    ): Flow<UpscalerExportState>

    /** Cancel any in-flight upscale export. */
    suspend fun cancel()
}

/**
 * Media3 `Transformer`-backed implementation of [UpscalerExportEngine].
 *
 * v2 strategy (2026-09-16):
 *  - Every video clip renders into ONE [EditedMediaItemSequence] wrapped
 *    in a [Composition], laid end-to-end — the same shape
 *    [VideoExportEngine] uses. v1 called `graph.segments.first()` and
 *    silently discarded every clip after the first.
 *  - The output frame size is pinned with a [Presentation] to
 *    **source dimensions × `preset.targetScale`**, capped at 8K. This is
 *    what actually performs the upscale: `Transformer` exposes no
 *    "set output resolution" API, so the effect chain decides the size.
 *    v1 computed an upscaled size into a local `ExportConfig` and then
 *    passed it nowhere, so `ULTRA_4K` produced a source-resolution file.
 *  - Progress is split into three visual stages:
 *      0.00 – 0.15  → EnhancingFrames   (decode)
 *      0.15 – 0.85  → ReconstructingDetails (scale + effects)
 *      0.85 – 1.00  → Finalizing4K       (encode + mux)
 *
 * Media3 only reports a single encoder progress value, so we map it
 * into those three buckets with smooth handoffs. This matches the
 * existing `Media3TransformerExportEngine` pattern.
 *
 * **Known v2 limitations**, carried through honestly rather than
 * silently ignored:
 *  - Per-clip `speed`, transitions and clip animations are NOT baked
 *    here. This engine only resizes and sharpens; a project that needs
 *    those should use the normal export.
 *  - The resample is Media3's GL sampler, not Lanczos. A TFLite ESRGAN
 *    pass would replace it — see [tfliteInterpreterOrNull].
 */
@UnstableApi
@Singleton
class Media3UpscalerExportEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : UpscalerExportEngine {

    private val activeState = MutableStateFlow<UpscalerExportState>(UpscalerExportState.Idle)
    private val activeTransformer = AtomicReference<Transformer?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun startUpscaleExport(
        project: VideoProject,
        settings: UpscalerSettings,
        config: ExportConfig
    ): Flow<UpscalerExportState> {
        if (!settings.isActive) {
            emit(UpscalerExportState.Failed("Pick an upscaler preset and a non-zero strength first."))
            return activeState
        }

        // 1. Build the render graph.
        val graph = when (val result = RenderGraphBuilder.build(project)) {
            is RenderGraphResult.Success -> result
            RenderGraphResult.EmptyProject -> {
                emit(UpscalerExportState.Failed("This project has no clips to upscale."))
                return activeState
            }
            RenderGraphResult.NoRenderableClips -> {
                emit(UpscalerExportState.Failed("No renderable video clips in this project."))
                return activeState
            }
        }
        val segments = graph.segments
        // The first segment is used only to name the output file and to pin
        // the sequence's frame size. Every segment still renders.
        val reference = segments.first()
        val totalDurationMs = graph.totalOutputDurationMs

        // 2. Cancel any in-flight export before starting a new one.
        scope.launch { cancelInternal() }

        // 3. Build the output target.
        emit(UpscalerExportState.Preparing)
        val target = OutputTarget.create(context, config, reference)
            ?: run {
                emit(
                    UpscalerExportState.Failed(
                        "Could not allocate an output file. Free up some storage and try again."
                    )
                )
                return activeState
            }

        // 4. Decide the output frame size. Media3 requires ONE consistent
        //    size across a sequence, and `Transformer` has no resolution
        //    setter, so a Presentation effect has to carry it.
        //
        //    The caller (EditorViewModel) has already resolved
        //    `config.effectiveWidthPx/HeightPx` from the project's canvas
        //    preset, falling back to the preset's base quality. Those — not
        //    the source pixels — define the target frame: sizing from the
        //    source would silently discard a 9:16 or 1:1 canvas.
        val outputSize = UpscaleOutputSize.compute(
            targetWidthPx = config.effectiveWidthPx,
            targetHeightPx = config.effectiveHeightPx,
            targetScale = settings.preset.targetScale,
            sourceWidthPx = reference.sourceWidthPx,
            sourceHeightPx = reference.sourceHeightPx
        )

        // 5. Build the Transformer.
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(
                object : Transformer.Listener {
                    override fun onCompleted(
                        composition: Composition,
                        result: ExportResult
                    ) {
                        activeTransformer.set(null)
                        // Reuse the existing OutputTarget.finalize() to get
                        // an ExportState.Succeeded, then map the fields onto
                        // the upscale-specific Succeeded. RULE 9: finalize()
                        // can return ExportState.Failed for a 0-byte file —
                        // propagate that as UpscalerExportState.Failed,
                        // never as a Succeeded with a null URI.
                        val finalized = target.finalize(result.fileSizeBytes, totalDurationMs)
                        if (finalized is ExportState.Succeeded) {
                            emit(
                                UpscalerExportState.Succeeded(
                                    outputUri = finalized.outputUri,
                                    fileName = finalized.fileName,
                                    fileSizeBytes = finalized.fileSizeBytes,
                                    durationMs = finalized.durationMs
                                )
                            )
                        } else if (finalized is ExportState.Failed) {
                            emit(UpscalerExportState.Failed(finalized.message))
                        } else {
                            // Any other terminal state (Cancelled etc.) — be
                            // honest and report a failure with a clear reason
                            // rather than fabricating a Succeeded with a
                            // null URI (RULES 8 & 9).
                            emit(
                                UpscalerExportState.Failed(
                                    "Upscale export did not produce a usable output file."
                                )
                            )
                        }
                    }

                    override fun onError(
                        composition: Composition,
                        result: ExportResult,
                        exception: ExportException
                    ) {
                        activeTransformer.set(null)
                        target.cleanupOnFailure()
                        val msg = exception.localizedMessage
                            ?: exception::class.simpleName
                            ?: "Upscale export failed."
                        emit(UpscalerExportState.Failed(msg, exception))
                    }
                }
            )
            .build()

        activeTransformer.set(transformer)
        emit(UpscalerExportState.EnhancingFrames(0f))

        // 6. Build every clip into ONE sequence, so nothing is dropped. For
        //    a single-clip project this is behaviourally identical to the
        //    old `transformer.start(editedMediaItem, path)` call — Media3
        //    builds exactly this Composition internally for that overload.
        val items = segments.map { segment ->
            buildEditedMediaItem(
                segment = segment,
                settings = settings,
                outputWidthPx = outputSize?.first ?: 0,
                outputHeightPx = outputSize?.second ?: 0
            )
        }
        val sequence = EditedMediaItemSequence.Builder(items).build()
        val composition = Composition.Builder(listOf(sequence)).build()
        val outputPath = target.outputPath()
        transformer.start(composition, outputPath)

        // 7. Poll progress; map to the three-stage UpscalerExportState.
        scope.launch { pollProgress(transformer) }

        return activeState
    }

    override suspend fun cancel() {
        cancelInternal()
    }

    private fun cancelInternal() {
        val t = activeTransformer.getAndSet(null) ?: return
        try {
            t.cancel()
        } catch (_: Throwable) {
            // Best-effort; the listener reports the final state.
        }
        emit(UpscalerExportState.Cancelled)
    }

    private fun emit(state: UpscalerExportState) {
        activeState.value = state
    }

    private suspend fun pollProgress(transformer: Transformer) {
        val holder = ProgressHolder()
        // Stage boundaries (see kdoc on Media3UpscalerExportEngine).
        val stage1End = 0.15f
        val stage2End = 0.85f
        while (true) {
            val state = transformer.getProgress(holder)
            if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                val pct = (holder.progress / 100f).coerceIn(0f, 1f)
                emit(progressToState(pct, stage1End, stage2End))
            }
            val current = activeState.value
            if (current is UpscalerExportState.Succeeded ||
                current is UpscalerExportState.Failed ||
                current is UpscalerExportState.Cancelled
            ) break
            @Suppress("NON_LONG_DELAY")
            delay(500L)
        }
    }

    /**
     * Map a single Media3 progress value (0..1) to the three-stage
     * UpscalerExportState. We smooth the handoff by remapping the
     * inner progress so each stage fills its own 0..1 sub-range.
     */
    private fun progressToState(
        pct: Float,
        stage1End: Float,
        stage2End: Float
    ): UpscalerExportState {
        return when {
            pct < stage1End -> UpscalerExportState.EnhancingFrames(
                progress = (pct / stage1End).coerceIn(0f, 1f)
            )
            pct < stage2End -> UpscalerExportState.ReconstructingDetails(
                progress = ((pct - stage1End) / (stage2End - stage1End)).coerceIn(0f, 1f)
            )
            else -> UpscalerExportState.Finalizing4K(
                progress = ((pct - stage2End) / (1f - stage2End)).coerceIn(0f, 1f)
            )
        }
    }

    /**
     * Build one clip's [EditedMediaItem]: trim, then the enhancement
     * chain, then the output-frame [Presentation].
     *
     * @param outputWidthPx / [outputHeightPx] the pinned output frame size,
     *   or `0` to leave the source size alone (see [UpscaleOutputSize])
     */
    private fun buildEditedMediaItem(
        segment: RenderSegment,
        settings: UpscalerSettings,
        outputWidthPx: Int,
        outputHeightPx: Int
    ): EditedMediaItem {
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(segment.sourceStartMs)
            .setEndPositionMs(segment.sourceEndMs)
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(segment.sourceUri)
            .setClippingConfiguration(clipping)
            .build()

        val videoEffects = mutableListOf<Effect>()

        // 1) Rotation only. The clip's rotation is already baked into the
        //    source metadata, so this is a pass-through that also gives the
        //    chain a well-defined starting geometry.
        videoEffects += ScaleAndRotateTransformation.Builder()
            .setRotationDegrees(0f)
            .build()

        // 2) Bake the CAS sharpening. Media3 1.5.0 exposes Brightness
        //    and Contrast (both -1..+1) which is enough to encode a
        //    CAS-approximation gain curve. A future ESRGAN TFLite pass
        //    would replace this whole block.
        val s = settings.effectiveSharpening.coerceIn(0f, 1f)
        if (s > 0f) {
            // Contrast lift: stronger than what the normal editor applies
            // because we're targeting a wider range (0..0.85 sharpening).
            // Map [0..1] -> [-0.10..+0.30] (no darkening).
            videoEffects += Contrast((s * 0.30f - 0.10f).coerceIn(-1f, 1f))
            // Tiny brightness lift so the contrast pivot doesn't darken
            // the image perceptibly. [0..1] -> [0..+0.05].
            videoEffects += Brightness((s * 0.05f).coerceIn(-1f, 1f))
        }

        // 3) The actual upscale. Last in the chain so it resamples the fully
        //    graded frame at the final output size. The layout comes from the
        //    clip's own `fillMode`, the same way the normal export does it —
        //    FIT letterboxes a mismatch, FILL crops it. Both force the same
        //    output size, so this cannot change the frame, only what is
        //    visible inside it. See [ExportFrame].
        if (outputWidthPx > 0 && outputHeightPx > 0) {
            videoEffects += Presentation.createForWidthAndHeight(
                /* width  = */ outputWidthPx,
                /* height = */ outputHeightPx,
                /* layout = */ ExportFrame.layoutFor(segment.fillMode)
            )
        }

        val effects = Effects(
            /* audioProcessors = */ emptyList(),
            /* videoEffects    = */ videoEffects
        )

        return EditedMediaItem.Builder(mediaItem)
            .setEffects(effects)
            .build()
    }

    /**
     * Future extension point: a TFLite ESRGAN interpreter. The function
     * is wired up but always returns null today — the asset file
     * `assets/upscaler.tflite` is not shipped. When it is, replace
     * the `null` return with a lazy-loaded `Interpreter` and use it in
     * a custom Media3 `Effect` that decodes the input frame, runs the
     * model, and re-encodes.
     */
    @Suppress("unused")
    private fun tfliteInterpreterOrNull(): Any? = null
}

/**
 * Pure output-sizing math for the upscale export.
 *
 * `Transformer` exposes no "set output resolution" API — the output frame
 * size is decided by the effect chain — and Media3 requires ONE consistent
 * size across an [EditedMediaItemSequence]. So the engine pins the size
 * with a [Presentation], and this object decides what that size should be.
 *
 * Kept free of every Android / Media3 type so it is unit-testable on the
 * JVM: this project has no Robolectric and no mocking library, so pure
 * functions are the only testable seam.
 *
 * The contract is **the caller's target frame × `preset.targetScale`**.
 * The caller is `EditorViewModel`, which resolves
 * `ExportConfig.effectiveWidthPx/HeightPx` from the project's canvas preset
 * (9:16, 1:1, …) and falls back to the preset's base quality. Honouring
 * those — rather than the source clip's pixels — is what keeps the
 * upscaler consistent with the normal export and preserves the aspect
 * ratio the user picked.
 *
 * So `ULTRA_4K` (`targetScale = 2.0`) on a 1080x1920 canvas lands on
 * 2160x3840, and on the 4K base quality it lands on 8K.
 */
internal object UpscaleOutputSize {

    /**
     * Ceiling for the upscaled output, matching the cap documented on
     * [UpscalerExportPresets.outputBounds].
     */
    const val MAX_WIDTH_PX: Int = 7680
    const val MAX_HEIGHT_PX: Int = 4320

    /**
     * @param targetWidthPx / [targetHeightPx] the frame the caller resolved
     *   from the canvas preset or the preset's base quality
     * @param targetScale `preset.targetScale`; non-finite or `<= 0` falls
     *   back to `1f` rather than producing a degenerate frame
     * @param sourceWidthPx / [sourceHeightPx] the reference clip's real
     *   pixel size, consulted **only** when the caller supplied no target
     *   frame at all
     * @return `width to height`, or `null` when nothing usable is known, in
     *   which case the caller omits the [Presentation] and Media3 keeps the
     *   source size
     */
    fun compute(
        targetWidthPx: Int,
        targetHeightPx: Int,
        targetScale: Float,
        sourceWidthPx: Int,
        sourceHeightPx: Int
    ): Pair<Int, Int>? {
        val scale = if (targetScale.isFinite() && targetScale > 0f) targetScale else 1f
        val baseW = targetWidthPx.takeIf { it > 0 } ?: sourceWidthPx
        val baseH = targetHeightPx.takeIf { it > 0 } ?: sourceHeightPx
        if (baseW <= 0 || baseH <= 0) return null

        val rawW = baseW * scale
        val rawH = baseH * scale
        // One shared clamp factor, so a frame that overshoots the ceiling is
        // scaled down uniformly instead of being squashed on a single axis.
        val capFactor = minOf(1f, MAX_WIDTH_PX / rawW, MAX_HEIGHT_PX / rawH)
        return even((rawW * capFactor).toInt()) to even((rawH * capFactor).toInt())
    }

    /**
     * H.264 in yuv420p needs even dimensions; an odd width or height makes
     * some hardware encoders reject the format outright. Rounding *down*
     * keeps the result inside the ceiling already applied by [compute].
     */
    private fun even(v: Int): Int = (v - (v and 1)).coerceAtLeast(2)
}

/**
 * Recommended [ExportConfig] presets for the AI upscaler pipeline.
 *
 * The view-model can pass one of these directly into
 * [Media3UpscalerExportEngine.startUpscaleExport] so the user always
 * gets a sensible default for the chosen [UpscalerPreset].
 *
 * Note on sizing: the engine takes `ExportConfig.effectiveWidthPx/HeightPx`
 * — which `EditorViewModel` resolves from the project's canvas preset, or
 * from these presets' `quality` when the canvas is FREE — and multiplies it
 * by `preset.targetScale` (see [UpscaleOutputSize]). So `quality` here does
 * drive the upscaled resolution, via the canvas fallback.
 */
object UpscalerExportPresets {
    val SMART_HD_BASE = ExportConfig(
        quality = ExportQuality.HIGH_1080P,
        outputBaseName = "upscale_smart_hd"
    )
    val ULTRA_4K_BASE = ExportConfig(
        quality = ExportQuality.ULTRA_4K,
        outputBaseName = "upscale_ultra_4k"
    )
    val FACE_CLARITY_BASE = ExportConfig(
        quality = ExportQuality.HIGH_1080P,
        outputBaseName = "upscale_face_clarity"
    )

    /**
     * Pick a sensible [ExportConfig] for a given [UpscalerPreset].
     * The caller is free to override any field.
     */
    fun forPreset(preset: UpscalerPreset): ExportConfig = when (preset) {
        UpscalerPreset.OFF -> ExportConfig()
        UpscalerPreset.SMART_HD_1080P -> SMART_HD_BASE
        UpscalerPreset.ULTRA_4K -> ULTRA_4K_BASE
        UpscalerPreset.FACE_CLARITY_BOOST -> FACE_CLARITY_BASE
    }

    /**
     * For documentation: the minimum/maximum output dimensions for a
     * given preset, used by the progress dialog's title. Computed
     * lazily because [ExportConfig] instances are cheap.
     */
    @Suppress("unused")
    fun outputBounds(preset: UpscalerPreset): Pair<Int, Int> {
        val base = forPreset(preset)
        val w = max(1, (base.effectiveWidthPx * preset.targetScale).toInt())
        val h = max(1, (base.effectiveHeightPx * preset.targetScale).toInt())
        return min(w, 7680) to min(h, 4320) // cap at 8K
    }
}
