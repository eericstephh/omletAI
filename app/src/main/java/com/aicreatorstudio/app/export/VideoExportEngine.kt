package com.aicreatorstudio.app.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.SpeedChangingAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.aicreatorstudio.app.model.AudioFade
import com.aicreatorstudio.app.model.CanvasBackground
import com.aicreatorstudio.app.model.CanvasPreset
import com.aicreatorstudio.app.model.FillMode
import com.aicreatorstudio.app.model.ProgressBarSettings
import com.aicreatorstudio.app.model.VoiceEffects
import com.aicreatorstudio.app.model.VideoEffect
import com.aicreatorstudio.app.model.VideoProject
import com.aicreatorstudio.app.model.util.SpeedCurveInterpolator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 2026-10-11 — lightweight structured diagnostics for the export
 * lifecycle.
 *
 * ## Why this exists
 *
 * The "stuck at 88%" report was hard to act on because the export path
 * logged almost nothing between "start" and "done", and the number the
 * UI showed could not be told apart from a hard hang. These helpers make
 * the state transitions greppable under a single tag so a logcat dump
 * answers the questions the brief lists: is it still encoding, waiting
 * on EOS, finalizing the muxer, writing/publishing the file, done, or
 * dead?
 *
 * ## What is deliberately NOT logged
 *
 * No media content, no file paths beyond the generated output name, no
 * URIs, no API keys. Only counts, timings, stages and Booleans — the
 * export touches user media, so the log must stay metadata-only.
 *
 * This reuses the existing `android.util.Log` channel rather than
 * introducing a parallel logging framework.
 */
internal object ExportDiagnostics {

    private const val TAG = "VideoExportEngine"
    private const val COMPONENT = "export"
    private val startMs = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * 2026-10-12 — optional sink into the centralized logger. Wired
     * once from DI at startup; when null (JVM unit tests, or before
     * Hilt wires the singleton) the object still writes to logcat
     * exactly as it did before. This is why routing export diagnostics
     * through the logger cannot change export behaviour: the extra call
     * is fire-and-forget and the hard-coded logcat path is preserved.
     */
    @Volatile
    private var sink: com.aicreatorstudio.app.logging.OmeletLog? = null

    /** Run id for the current export, so all its events correlate. */
    private val runId = java.util.concurrent.atomic.AtomicReference<String?>(null)

    fun attach(log: com.aicreatorstudio.app.logging.OmeletLog?) {
        sink = log
    }

    /** Marks the beginning of an export run. */
    fun begin(segments: Int, audioSegments: Int, durationMs: Long) {
        val now = System.currentTimeMillis()
        startMs.set(now)
        val id = "exp-" + now
        runId.set(id)
        val fields = mapOf(
            "videoSegments" to segments.toString(),
            "audioSegments" to audioSegments.toString(),
            "durationMs" to durationMs.toString()
        )
        android.util.Log.i(
            TAG,
            "export begin: videoSegments=$segments audioSegments=$audioSegments " +
                "durationMs=$durationMs"
        )
        sink?.info(
            COMPONENT, "export_started",
            "videoSegments=$segments audioSegments=$audioSegments durationMs=$durationMs",
            correlationId = id, fields = fields
        )
    }

    /** Elapsed milliseconds since [begin]; 0 when no run is active. */
    fun elapsedMs(): Long {
        val s = startMs.get()
        return if (s == 0L) 0L else System.currentTimeMillis() - s
    }

    /** A notable stage transition. */
    fun event(message: String) {
        android.util.Log.i(TAG, "export +${elapsedMs()}ms: $message")
        sink?.info(COMPONENT, "export_stage", message, correlationId = runId.get())
    }

    /** Something went wrong but was survivable, or the watchdog fired. */
    fun warn(message: String) {
        android.util.Log.w(TAG, "export +${elapsedMs()}ms: $message")
        sink?.warn(COMPONENT, "export_warning", message, correlationId = runId.get())
    }

    /**
     * 2026-10-12 — output validation. Records the final file's size so a
     * zero-byte or suspiciously small export is visible in a report.
     * Pure observation: nothing here changes the export result.
     */
    fun validateOutput(state: ExportState) {
        if (state !is ExportState.Succeeded) return
        val ok = state.fileSizeBytes > 0L
        val message = "output validated: bytes=${state.fileSizeBytes} " +
            "durationMs=${state.durationMs} nonEmpty=$ok"
        if (ok) {
            android.util.Log.i(TAG, "export +${elapsedMs()}ms: $message")
            sink?.info(COMPONENT, "export_output_validated", message, correlationId = runId.get())
        } else {
            android.util.Log.w(TAG, "export +${elapsedMs()}ms: $message")
            sink?.warn(COMPONENT, "export_output_empty", message, correlationId = runId.get())
        }
    }

    /** The single terminal state of this export. */
    fun terminal(state: ExportState) {
        val detail = when (state) {
            is ExportState.Succeeded ->
                "SUCCEEDED bytes=${state.fileSizeBytes} durationMs=${state.durationMs} " +
                    "fileName=${state.fileName}"
            is ExportState.Failed -> "FAILED: ${state.message}"
            ExportState.Cancelled -> "CANCELLED"
            else -> state.toString()
        }
        android.util.Log.i(TAG, "export +${elapsedMs()}ms: $detail")
        val id = runId.get()
        when (state) {
            is ExportState.Succeeded -> sink?.info(
                COMPONENT, "export_succeeded", detail, correlationId = id,
                fields = mapOf(
                    "fileSizeBytes" to state.fileSizeBytes.toString(),
                    "durationMs" to state.durationMs.toString(),
                    "elapsedMs" to elapsedMs().toString()
                )
            )
            is ExportState.Failed -> sink?.error(
                COMPONENT, "export_failed", state.message, correlationId = id,
                fields = mapOf("elapsedMs" to elapsedMs().toString()),
                throwable = state.cause
            )
            ExportState.Cancelled -> sink?.warn(
                COMPONENT, "export_cancelled", "", correlationId = id,
                fields = mapOf("elapsedMs" to elapsedMs().toString())
            )
            else -> sink?.info(COMPONENT, "export_terminal", detail, correlationId = id)
        }
        if (state is ExportState.Succeeded || state is ExportState.Failed) {
            startMs.set(0L)
            runId.set(null)
        }
    }
}

/**
 * 2026-10-11 — the percentage at which we hand the UI over to the
 * FINALIZING stage once the Transformer reports completion.
 *
 * Kept just below 1.0 rather than jumping straight to 100% because the
 * export is not finished yet at this point: the voice-clean remux (when
 * enabled) and the MediaStore publish still have to run, and the brief
 * forbids faking 100%.
 */
private const val FINALIZING_PROGRESS = 0.99f

/**
 * Public contract for the export engine.
 *
 * The interface deliberately hides all Media3 types so the rest of the
 * app doesn't need to know which library we use to render the video.
 */
interface VideoExportEngine {
    /**
     * The shared state of the current (or last) export.
     *
     * 2026-10-22 — the export runs in a foreground service
     * ([ExportService]), so the editor does NOT start it any more; it
     * only *observes* it. Both sides therefore need the same stream,
     * and this is it: the engine's own `activeState`, which
     * [startExport] returns.
     *
     * It is a `StateFlow`, so a late collector immediately receives the
     * current state instead of waiting for the next change.
     */
    val state: kotlinx.coroutines.flow.StateFlow<ExportState>

    /**
     * Announce that a NEW export is starting, resetting the shared
     * state to [ExportState.Preparing] without starting anything.
     *
     * 2026-10-22 — the service starts the export, but the editor has
     * to clear the PREVIOUS run's terminal state FIRST: the shared
     * stream replays its current value to every new collector, so
     * without this the editor would briefly show the last export's
     * "done" while the new one was still starting. It is the same
     * class of bug the 2026-09-10 fix addressed inside the engine.
     */
    fun prepare()

    /**
     * Begin an export. Replaces any currently running export; if a previous
     * export was in flight it is cancelled.
     */
    fun startExport(
        project: VideoProject,
        config: ExportConfig
    ): Flow<ExportState>

    /** Cancel any in-flight export. */
    suspend fun cancel()
}

/**
 * Media3 `Transformer`-backed implementation of [VideoExportEngine].
 *
 * ## Multi-clip (2026-09-16)
 *
 * Every segment from [RenderGraphBuilder] is rendered into ONE
 * [EditedMediaItemSequence] wrapped in a [Composition], laid end-to-end.
 * A single-clip project takes the same code path — Media3 builds an
 * equivalent single-sequence Composition internally for the
 * `start(EditedMediaItem, …)` overload — so behaviour there is
 * unchanged.
 *
 * **Known limitations of the multi-clip path:**
 * - Clips are concatenated back-to-back. Gaps between clips on the
 *   editor timeline are not reproduced. This matches
 *   [RenderGraphResult.totalOutputDurationMs], which is already defined
 *   as the sum of the per-segment output durations.
 * - **Transitions** are baked PER CLIP, not between clips. Each
 *   segment's own `transitionIn` / `transitionOut` pose (scale, pan,
 *   rotate, blur, fade and flash washes) is rendered by
 *   [ClipMotionEffects], so a clip's IN window shows it animating in
 *   and its OUT window shows it animating out. What is NOT rendered
 *   is a true cross-clip dissolve — there is no blend of one clip's
 *   last frame into the next clip's first, because every
 *   `EditedMediaItem` is independent in the Composition. A
 *   `CROSS_DISSOLVE` at a boundary therefore reads as a dip through
 *   the canvas colour rather than a dissolve. (This bullet used to
 *   claim transitions were not rendered at all; that was rendered
 *   false by step 5.8 below and by
 *   [ClipMotionEffects]' own history.)
 * - **Transitions are also drawn in the live preview** as of PHASE A,
 *   through the same [ClipMotion.compose] the export uses, so the two
 *   agree by construction. The preview omits blur only — a per-frame
 *   `Modifier.blur()` on the video layer is the shape that broke the
 *   Play button twice — and says so in its KDoc.
 * - **Voice Clean** is a whole-output remux, so it is applied only for
 *   single-segment exports. On a multi-clip export the original audio is
 *   kept and a warning is logged rather than corrupting the result.
 * - **Text / subtitles** ARE burned in, including for multi-clip
 *   exports, via per-segment time rebasing. Ordinary (non-subtitle)
 *   text, paragraphs and watermarks burn too, as of PHASE A — the
 *   gate used to be `subtitleSource != null`, which silently dropped
 *   every decorative overlay while still showing it on screen.
 *
 * ## v1 simplifications
 *
 * - **Background Blur** — the *canvas letterbox* blur, which is a different
 *   feature from matting's BLUR background mode (that one **is** exported;
 *   see the next bullet) — is preview-only. A true blurred letterbox needs a
 *   separate sampling pass that isn't part of the Media3 1.5.0
 *   `Presentation` surface.
 * - **Background Remove (Matting)** is the mirror image of the above:
 *   the **export composites it for real and the live preview does
 *   not.** It is not a Media3 effect at all — Media3 1.5.0 exposes no
 *   public path to upload a per-frame 2D texture (the mask bitmap) and
 *   sample it inside an OES-textured fragment shader. Instead it runs as
 *   a CPU pre-render *before* the Transformer: [AiExportHooks.prepareSourceUri]
 *   decodes the clip through
 *   [com.aicreatorstudio.app.ai.media.VideoFramePipeline], composites
 *   `mix(background, frame, alpha)` per frame from the sidecar alpha PNGs
 *   the MediaPipe `ImageSegmenter` wrote at analysis time, re-encodes, and
 *   hands the intermediate URI to the Transformer with the audio copied
 *   sample-for-sample (so duration and sync are preserved by
 *   construction). Background modes: ORIGINAL passes the frame through,
 *   BLUR is a 1/8-downscale + bilinear upscale, COLOR and IMAGE
 *   (cover-cropped) are static, VIDEO is sampled by `BgVideoSampler`;
 *   TRANSPARENT fails with a user-facing message, because an MP4 has no
 *   alpha channel to write. A missing or stale sidecar also fails loudly
 *   rather than exporting un-matted video.
 *
 *   The live preview cannot follow, and does not pretend to. Compose's
 *   graphics layer cannot sample the pixels of a `TextureView` /
 *   `SurfaceView`, and the sibling-`Box` `RenderEffect` that once tried to
 *   was inert — a `RenderNode` effect processes only its own node's
 *   rasterised content, and an empty `Box` has none (the same root cause
 *   behind the colour-tint and preview-grade fixes; see the Layer 2.5 note
 *   in `EditorScreen`). So the preview shows a "BG Remove ready" chip to
 *   confirm the sidecar exists, and the exported file shows the composite.
 *   **This is the one place where export is deliberately ahead of preview
 *   rather than in step with it.**
 * - **Output target:** API 29+ uses MediaStore
 *   (`Movies/AICreatorStudio/AI_<name>_<timestamp>.mp4`). API 24–28 falls
 *   back to the app's external Movies directory (no permission needed on
 *   app-private storage).
 *
 * ## Per-clip effects baked in v1
 *
 * | Tool         | Effect                                                        |
 * |--------------|---------------------------------------------------------------|
 * | Filters      | ALL presets baked — NOIR/SEPIA/VIVID/FADE/COLD/WARM/INVERT/…  |
 * |              | ride the unified grade matrix (2026-09-10 parity pass)        |
 * | Adjust       | B/C/S + the ENTIRE Lumetri chain (exposure, whites/blacks,    |
 * |              | highlights/shadows, temp/tint, vibrance, HSL, fade) via the   |
 * |              | same `ColorGradingMath.fullExportMatrix` the preview renders  |
 * | Detail       | sharpness → `SharpnessEffect` (separable convolution);        |
 * |              | vignette → `VignetteOverlay` (full-frame plate); grain →     |
 * |              | `GrainEffect` (custom fragment shader) — all 2026-09-16/17    |
 * | Crop         | `Presentation.createForWidthAndHeight` (output frame size)    |
 * | Fill         | `Presentation` layout via `ExportFrame.layoutFor` — FIT       |
 * |              | letterboxes, FILL crops (FILL used to be dropped)            |
 * | Transform    | rotation + flip → `ScaleAndRotateTransformation`; zoom + pan  |
 * |              | → `ClipMotionEffects` (the zoom used to cancel itself out)     |
 * | Speed        | `SpeedChangeEffect` (video) + `SpeedChangingAudioProcessor`  |
 * | Mute/Volume  | `BaseAudioProcessor` PCM-scaling audio processor              |
 * | Ratio        | `Presentation.createForWidthAndHeight` (output frame size)    |
 * | BG (color)   | `CanvasBackgroundOverlay` — a plate of the colour punched    |
 * |              | through around the *posed* silhouette (no Presentation argb   |
 * |              | overload, so it is painted over the bars instead of behind)   |
 * | BG (blur)    | preview-only (needs a second video layer, not a field)        |
 * | Matting      | **exported** — `AiExportHooks` CPU pre-render, not an effect  |
 * |              | (preview shows a chip instead; see the class KDoc above)      |
 * | Text (subs)  | burned in via `OverlayEffect` (`SubtitleBurnInFactory`)      |
 * | Text (para)  | paragraph / decorative overlays are preview-only              |
 * | Animate      | `ClipMotionEffects` — per-frame matrix + canvas-coloured fade wash |
 * | Opacity      | `ClipMotionEffects` — full-frame fade wash of `1 - opacity`     |
 * | Transitions  | `ClipMotionEffects` — pose matrix, animated blur, fade + flashes |
 *
 * ## Threading
 *
 * `Transformer` requires a single application thread. We use the main
 * thread, per Media3's own guidance: "For the majority of cases, the
 * application thread can just be the main thread of the application."
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Singleton
class Media3TransformerExportEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val aiHooks: AiExportHooks
) : VideoExportEngine {

    private val activeState = MutableStateFlow<ExportState>(ExportState.Idle)

    /**
     * 2026-10-22 — the shared stream, so the editor can observe an
     * export it did not start (the service starts it).
     */
    override val state: kotlinx.coroutines.flow.StateFlow<ExportState> get() = activeState

    /**
     * Reference to the active `Transformer`. Held so [cancel] can reach
     * it. Only mutated on the main thread.
     */
    private val activeTransformer = AtomicReference<Transformer?>(null)

    /**
     * In-flight AI pre-render job (Background Remove / Auto Frame
     * intermediates). Held so [cancel] can abort it; cleared when
     * the Transformer takes over.
     */
    private val activePreRender = AtomicReference<kotlinx.coroutines.Job?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @androidx.annotation.OptIn(UnstableApi::class)
    override fun prepare() {
        // 2026-10-22 — see the interface's note: the shared stream
        // replays its current value, so the previous run's terminal
        // state has to be cleared before a new run is announced.
        activeState.value = ExportState.Preparing
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    override fun startExport(
        project: VideoProject,
        config: ExportConfig
    ): Flow<ExportState> {
        // 0. 2026-09-10 FIX — reset the shared StateFlow FIRST.
        // `activeState` survives across runs; without this reset the
        // VM's fresh collector instantly replays the PREVIOUS run's
        // terminal state (Failed/Succeeded/Cancelled) and the new
        // export appears to "do nothing" in the UI.
        activeState.value = ExportState.Preparing
        try {
            return startExportInternal(project, config)
        } catch (t: Throwable) {
            emit(ExportState.Failed(ExportErrorDetail.describe(t, "STARTUP")))
            return activeState
        }
    }

    private fun startExportInternal(
        project: VideoProject,
        config: ExportConfig
    ): Flow<ExportState> {
        // 1. Build the render graph.
        val graph = when (val result = RenderGraphBuilder.build(project)) {
            is RenderGraphResult.Success -> result
            RenderGraphResult.EmptyProject -> {
                emit(ExportState.Failed("This project has no clips to export."))
                return activeState
            }
            RenderGraphResult.NoRenderableClips -> {
                emit(ExportState.Failed("No renderable video clips in this project."))
                return activeState
            }
        }

        // 2026-09-16 MULTI-CLIP — render EVERY segment, not just the
        // first. `RenderGraphBuilder` has always returned the full
        // ordered list; the engine used to take `.first()` and silently
        // drop the rest, which is why a 10-clip sequence exported as a
        // single clip.
        val segments = graph.segments
        val totalDurationMs = graph.totalOutputDurationMs
        val canvasPreset = graph.canvasPreset
        val canvasBackground = graph.canvasBackground
        // 2026-10-13 — the project's video progress bar (timeline
        // indicator / story timer). Read from the project rather than
        // threaded through the render graph: it is one project-level value,
        // not per-clip, and [RenderGraphBuilder] has no business knowing
        // about canvas chrome. Disabled by default, so an untouched project
        // appends no effect at all.
        val progressBarSettings = project.progressBarSettings
        // Audio-only clips, mixed in as extra audio tracks rather than
        // concatenated. Until 2026-09-16 the render graph dropped them
        // entirely, so an imported song played in the editor and vanished
        // from the exported MP4.
        val audioSegments = graph.audioSegments

        // Composition-time origin of each segment. Segments are laid
        // end-to-end, which is exactly what
        // [RenderGraphResult.totalOutputDurationMs] already assumes (the
        // sum of the per-segment output durations).
        val originsMs = CompositionTimeline.originsMs(segments)

        // 2026-09-19 MUXER GUARD — a zero/negative total duration
        // (degenerate trims, extreme speed rounding) builds a
        // composition with no samples; the muxer then fails with an
        // opaque write error. Fail loudly here instead.
        if (totalDurationMs <= 0L) {
            emit(
                ExportState.Failed(
                    "Nothing to export: the timeline has zero duration. " +
                        "Check trims and clip speeds."
                )
            )
            return activeState
        }

        // 2026-09-07 BURN-IN FIX — build the subtitle overlays. Now done
        // per segment against that segment's composition origin, so a
        // caption on clip #4 lands at the right time in the output.
        val subtitleOverlays = buildSubtitleOverlays(
            project = project,
            segments = segments,
            compositionOriginsMs = originsMs,
            canvasPreset = canvasPreset,
            config = config
        )

        // 2. Cancel any previous export silently (no Cancelled emission —
        //    the state is already Preparing and we don't want to flicker
        //    it to Cancelled mid-startup).
        cancelPreviousJobs()

        // 3. Build the output target.
        emit(ExportState.Preparing)
        ExportDiagnostics.begin(
            segments = segments.size,
            audioSegments = audioSegments.size,
            durationMs = totalDurationMs
        )
        val target = OutputTarget.create(context, config, segments.first())
            ?: run {
                emit(ExportState.Failed("Could not allocate an output file. Free up some storage and try again."))
                return activeState
            }
        ExportDiagnostics.event(
            "output target ready (api=${Build.VERSION.SDK_INT}, " +
                "cacheTwoStep=${Build.VERSION.SDK_INT >= 29})"
        )

        // 4. AI pre-render (Background Remove composite / Auto Frame
        // reframe) on IO, then build + start the Transformer on the
        // application thread. The Transformer path itself is
        // unchanged — it simply consumes the pre-rendered URI.
        emit(ExportState.Exporting(ExportProgress(0f, ExportStage.PREPARING)))
        val preJob = scope.launch(Dispatchers.IO) {
            try {
                // Run the AI pre-renders for every segment. Progress is
                // aggregated across the whole set so a 3-clip export
                // with Background Remove on each still reaches 100%.
                val count = segments.size
                val effectiveSegments = segments.mapIndexed { index, seg ->
                    val effectiveUri = aiHooks.prepareSourceUri(
                        seg,
                        // 2026-10-27 — the real canvas fill, so a
                        // TRANSPARENT Background Remove composite lands
                        // the subject on the colour behind the clip
                        // instead of an always-black guess.
                        canvasBackground = canvasBackground
                    ) { _, frac ->
                        val overall = (index + frac.coerceIn(0f, 1f)) / count.toFloat()
                        scope.launch {
                            emit(
                                ExportState.Exporting(
                                    ExportProgress(overall.coerceIn(0f, 1f), ExportStage.PREPARING)
                                )
                            )
                        }
                    }
                    seg.copy(sourceUri = effectiveUri)
                }
                kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
                    startTransformer(
                        effectiveSegments, segments, target, totalDurationMs,
                        canvasPreset, canvasBackground, subtitleOverlays,
                        audioSegments,
                        config.quality.videoBitrateBps,
                        progressBarSettings
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                emitTerminal(ExportState.Cancelled)
                runCatching { target.cleanupOnFailure() }
                scope.launch { aiHooks.cleanup() }
            } catch (t: Throwable) {
                emitTerminal(ExportState.Failed(ExportErrorDetail.describe(t, "PREPARING")))
                runCatching { target.cleanupOnFailure() }
                scope.launch { aiHooks.cleanup() }
            } finally {
                activePreRender.set(null)
            }
        }
        activePreRender.set(preJob)

        // 5. Spin a progress-polling coroutine once the transformer exists.
        scope.launch { pollProgressUntilTransformer() }

        return activeState
    }

    /**
     * Build + start the Transformer on the application thread (step 4
     * continuation). [effectiveSegments] carry the AI pre-rendered
     * URIs; [originalSegments] carry the AI settings (voiceClean) and
     * trim/clip metadata for the post-pass.
     *
     * All segments go into ONE [EditedMediaItemSequence] wrapped in a
     * [Composition], so they render end-to-end into a single file. For
     * a single-clip project this is behaviourally identical to the old
     * `transformer.start(editedMediaItem, path)` call — Media3 builds
     * exactly this Composition internally for that overload.
     *
     * Output frame size: when the project has a non-FREE canvas preset
     * we size the output to that preset. Otherwise a single clip keeps
     * its natural frame size, and a multi-clip sequence is pinned to the
     * first segment's dimensions because Media3 requires one consistent
     * output size across a sequence.
     */
    private fun startTransformer(
        effectiveSegments: List<RenderSegment>,
        originalSegments: List<RenderSegment>,
        target: OutputTarget,
        totalDurationMs: Long,
        canvasPreset: CanvasPreset,
        canvasBackground: CanvasBackground,
        subtitleOverlays: List<androidx.media3.effect.TextureOverlay> = emptyList(),
        audioSegments: List<RenderSegment> = emptyList(),
        videoBitrateBps: Int = 0,
        // 2026-10-13 — the project's video progress bar. Defaults to
        // disabled so every existing caller keeps an identical effect
        // chain.
        progressBarSettings: ProgressBarSettings = ProgressBarSettings()
    ) {
        // 2026-10-04 — the quality tier's bitrate is actually applied now.
        // It used to be defined in [ExportQuality.videoBitrateBps] and never
        // consumed: the Transformer fell back to the encoder's default
        // bitrate, so 720p/1080p/4K differed only by resolution and the
        // "High quality" setting was silently a lie.
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .apply {
                if (videoBitrateBps > 0) {
                    setEncoderFactory(
                        DefaultEncoderFactory.Builder(context)
                            .setRequestedVideoEncoderSettings(
                                VideoEncoderSettings.Builder().setBitrate(videoBitrateBps).build()
                            )
                            .build()
                    )
                }
            }
            .addListener(
                object : Transformer.Listener {
                    override fun onCompleted(
                        composition: Composition,
                        result: ExportResult
                    ) {
                        activeTransformer.set(null)
                        // 2026-10-11 — Media3 has finished muxing and
                        // closed the file, but our own finalize (voice
                        // clean remux + MediaStore publish) still has to
                        // run, and for a large file the publish is a full
                        // byte copy. Surface that as FINALIZING so the UI
                        // never sits on a frozen "Encoding 88%" while
                        // hundreds of MB are copied. Progress is left at
                        // the ceiling because the percentage that remains
                        // is genuinely unknown to us.
                        ExportDiagnostics.event(
                            "transformer completed: bytes=${result.fileSizeBytes} " +
                                "durationUs=${result.durationMs}"
                        )
                        emit(
                            ExportState.Exporting(
                                ExportProgress(
                                    FINALIZING_PROGRESS,
                                    ExportStage.FINALIZING
                                )
                            )
                        )
                        // Voice Clean post-pass (IO): remux cleaned
                        // audio when enabled, then finalize. The
                        // listener runs on the app thread, so hop.
                        scope.launch(Dispatchers.IO) {
                            try {
                                val outputPath = target.outputPath()
                                ExportDiagnostics.event(
                                    "finalization started (voiceClean=" +
                                        "${originalSegments.singleOrNull()?.voiceClean?.enabled == true})"
                                )
                                // The Voice Clean post-pass remuxes the
                                // WHOLE output, replacing its audio with
                                // one clip's cleaned WAV. That is only
                                // meaningful for a single-segment
                                // export; on a multi-clip sequence it
                                // would discard every other clip's
                                // audio, so we skip it and log rather
                                // than corrupt the result.
                                val single = originalSegments.singleOrNull()
                                val swappedSize = if (single != null) {
                                    aiHooks.maybeSwapCleanAudio(
                                        target = target,
                                        transformerOutputPath = outputPath,
                                        transformerBytes = result.fileSizeBytes,
                                        segment = single,
                                        durationMs = totalDurationMs
                                    )
                                } else {
                                    if (originalSegments.any { it.voiceClean?.enabled == true }) {
                                        android.util.Log.w(
                                            "VideoExportEngine",
                                            "Voice Clean cannot be remuxed onto a " +
                                                "${originalSegments.size}-clip export; " +
                                                "keeping the original audio."
                                        )
                                    }
                                    null
                                }
                                val final = target.finalize(
                                    swappedSize ?: result.fileSizeBytes,
                                    totalDurationMs
                                )
                                ExportDiagnostics.validateOutput(final)
                                scope.launch { aiHooks.cleanup() }
                                // Only publish a result if no other path
                                // already finished this export.
                                emitTerminal(final)
                            } catch (t: Throwable) {
                                target.cleanupOnFailure()
                                scope.launch { aiHooks.cleanup() }
                                emitTerminal(
                                    ExportState.Failed(
                                        ExportErrorDetail.describe(t, "FINALIZING")
                                    )
                                )
                            }
                        }
                    }

                    override fun onError(
                        composition: Composition,
                        result: ExportResult,
                        exception: ExportException
                    ) {
                        activeTransformer.set(null)
                        target.cleanupOnFailure()
                        scope.launch { aiHooks.cleanup() }
                        ExportDiagnostics.warn(
                            "transformer error: ${exception.errorCodeName} " +
                                "bytes=${result.fileSizeBytes}"
                        )
                        emitTerminal(
                            ExportState.Failed(
                                ExportErrorDetail.describe(exception, "TRANSFORMER"),
                                exception
                            )
                        )
                    }
                }
            )
            .build()

        activeTransformer.set(transformer)
        emit(ExportState.Exporting(ExportProgress(0f, ExportStage.ENCODING)))
        ExportDiagnostics.event(
            "encoder+muxer configured: video=${MimeTypes.VIDEO_H264} " +
                "audio=${MimeTypes.AUDIO_AAC} bitrate=$videoBitrateBps"
        )

        // 5. Construct the sequence and start the export.
        val multiClip = effectiveSegments.size > 1
        // ONE reference for the whole composition. Media3 writes a single
        // video track, so every item has to be fitted into the same frame —
        // resolving the size per clip would give a mixed-resolution project
        // a different frame per item, which the muxer rejects outright
        // (`AppendTrackFormatException`). See [ExportFrame].
        val frameReference = effectiveSegments.first()
        val frameSize = ExportFrame.resolve(canvasPreset, frameReference)
        // One size for the whole composition, resolved once. Two consumers still
        // need it up front: the `Presentation` that pins the canvas, and
        // [CanvasBackgroundOverlay]'s build-time "is there anything to paint"
        // test. The wash overlays no longer do — each observes the real frame
        // size from Media3's `TextureOverlay.configure`, which is the only route
        // that also works when this value cannot be right (a FREE canvas whose
        // frame a rotation grew). See [ClipMotionEffects.build].
        //
        // It used to be omitted entirely: `buildEditedMediaItem` declared the two
        // parameters but this call site never passed them, so they were always 0
        // and every fade, flash and `opacity` wash was silently skipped.
        val frameWidthPx = frameSize?.first ?: 0
        val frameHeightPx = frameSize?.second ?: 0
        // 2026-10-13 — composition-time origin of each segment, for the
        // progress bar. Media3 restarts `presentationTimeUs` at 0 for every
        // item in the sequence, so a bar that tracks the whole composition
        // needs each segment's cumulative output origin. Same arithmetic
        // the subtitle burn-in already uses, via the same pure helper.
        val compositionOriginsMs = CompositionTimeline.originsMs(originalSegments)
        val items = effectiveSegments.mapIndexed { index, segment ->
            buildEditedMediaItem(
                segment = segment,
                canvasPreset = canvasPreset,
                canvasBackground = canvasBackground,
                subtitleOverlays = subtitleOverlays,
                multiClip = multiClip,
                reference = frameReference,
                outputWidthPx = frameWidthPx,
                outputHeightPx = frameHeightPx,
                progressBarSettings = progressBarSettings,
                compositionOriginMs = compositionOriginsMs.getOrElse(index) { 0L },
                totalDurationMs = totalDurationMs
            )
        }
        val sequence = EditedMediaItemSequence.Builder(items).build()
        // Extra sequences are MIXED into the composition — Media3 overlaps
        // them in time rather than concatenating them — which is what turns
        // audio-only clips into a real audio track instead of audio tacked
        // onto the end of the video.
        val sequences = mutableListOf(sequence)
        sequences += buildAudioSequences(
            audioSegments = audioSegments,
            videoSegments = effectiveSegments,
            videoDurationMs = totalDurationMs
        )
        val composition = Composition.Builder(sequences).build()
        val outputPath = target.outputPath()
        ExportDiagnostics.event(
            "transformer.start: items=${effectiveSegments.size} " +
                "sequences=${sequences.size} bitrate=$videoBitrateBps"
        )
        transformer.start(composition, outputPath)
    }

    override suspend fun cancel() {
        ExportDiagnostics.event("cancel requested")
        cancelPreviousJobs()
        scope.launch { aiHooks.cleanup() }
        // Latched: if the watchdog already failed this export, a late
        // user tap must not overwrite that outcome with `Cancelled`.
        emitTerminal(ExportState.Cancelled)
    }

    /** Cancel any in-flight pre-render or Transformer job silently. */
    private fun cancelPreviousJobs() {
        activePreRender.getAndSet(null)?.cancel()
        val t = activeTransformer.getAndSet(null)
        if (t != null) {
            try { t.cancel() } catch (_: Throwable) { }
        }
    }

    private fun emit(state: ExportState) {
        activeState.value = state
    }

    /**
     * 2026-10-11 — emit a terminal state AT MOST ONCE per export.
     *
     * Guarding on the transition rather than on the value is deliberate:
     * the watchdog (in the progress poller) and Media3's own listener
     * (`onCompleted` / `onError`) can both race to finish the same
     * export. Without the latch the UI could receive `Failed(stall)`
     * followed by `Succeeded`, which is exactly the "false success" the
     * brief forbids — and it also means the caller can no longer tell
     * which outcome was real.
     *
     * @return true if this call was the one that finished the export.
     */
    private fun emitTerminal(state: ExportState): Boolean {
        while (true) {
            val current = activeState.value
            if (current.isTerminal()) return false
            if (activeState.compareAndSet(current, state)) {
                ExportDiagnostics.terminal(state)
                return true
            }
            // Lost a race with another emitter; re-read and decide again.
        }
    }

    private fun ExportState.isTerminal(): Boolean =
        this is ExportState.Succeeded ||
            this is ExportState.Failed ||
            this is ExportState.Cancelled

    /**
     * Progress poller that waits for the Transformer to exist
     * (AI pre-render may take minutes) and then polls it until a
     * terminal state lands.
     */
    private suspend fun pollProgressUntilTransformer() {
        var waits = 0
        // Max 120 seconds for the Transformer to appear (AI pre-render
        // may take time; a simple export should hand over in < 2 s).
        while (activeTransformer.get() == null && waits++ < 240) {
            val current = activeState.value
            if (current is ExportState.Succeeded ||
                current is ExportState.Failed ||
                current is ExportState.Cancelled
            ) return
            // Emit a preparing pulse so the UI doesn't freeze at 0%.
            if (waits > 1 && waits % 4 == 0) {
                emit(ExportState.Exporting(
                    ExportProgress(0.02f * (waits / 4).coerceAtMost(50), ExportStage.PREPARING)
                ))
            }
            delay(500L)
        }
        val transformer = activeTransformer.get() ?: return
        pollProgress(transformer)
    }

    private suspend fun pollProgress(transformer: Transformer) {
        val holder = ProgressHolder()
        // We only report progress for the encoding phase — there's no
        // granular signal for "decoding" vs "applying effects" inside
        // Media3 1.5.0, so we collapse them into the same stage.
        //
        // 2026-10-11 STALL FIX — Media3's progress is NOT a completion
        // signal and it is not monotonic to 100%:
        //   * `ExoPlayerAssetLoader.getProgress` returns
        //     `min(position * 100 / duration, 99)` — clamped at 99;
        //   * `TransformerInternal.updateProgressInternal` averages over
        //     every sequence, so a video+audio composition flattens in
        //     the high 80s;
        //   * it is only called from `drainExportersInternal`, so it
        //     stops entirely once the last sample is drained;
        //   * `endInternal` then RESETS progress to 0 and runs the muxer
        //     flush + file close with no progress reporting at all.
        // The old loop therefore waited forever for a value the library
        // never emits, which is the "stuck at ~88%" report. We now track
        // *when progress last changed* and fail loudly if the export goes
        // silent for longer than the matching budget, instead of hanging.
        var lastProgress = -1f
        var lastChangeMs = System.currentTimeMillis()
        var finalizingSinceMs: Long? = null
        val startedMs = lastChangeMs

        while (true) {
            val state = transformer.getProgress(holder)
            val now = System.currentTimeMillis()
            if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                val pct = (holder.progress / 100f).coerceIn(0f, 1f)
                if (pct != lastProgress) {
                    // Real progress. Bump the stall clock.
                    lastProgress = pct
                    lastChangeMs = now
                }
                // Once the library stops being able to advance (it can
                // only ever report up to 99%, and less when sequences are
                // averaged) the remaining work is finalization. Say so —
                // otherwise the UI sits on "Encoding" with a frozen
                // number and looks hung.
                if (pct >= ExportStallPolicy.FINALIZE_AT_OR_ABOVE) {
                    if (finalizingSinceMs == null) finalizingSinceMs = now
                    emit(
                        ExportState.Exporting(
                            ExportProgress(pct, ExportStage.FINALIZING)
                        )
                    )
                } else {
                    finalizingSinceMs = null
                    emit(
                        ExportState.Exporting(
                            ExportProgress(pct, ExportStage.ENCODING)
                        )
                    )
                }
            }

            val current = activeState.value
            if (current is ExportState.Succeeded ||
                current is ExportState.Failed ||
                current is ExportState.Cancelled
            ) break

            // Watchdog. Measured from the last *change*, so a genuinely
            // slow-but-moving export is never killed, while a true hang
            // surfaces as an actionable error instead of an endless spin.
            val watched = if (lastProgress < 0f) 0f else lastProgress
            val stall = ExportStallDetector.check(
                lastProgress = watched,
                lastChangeMs = lastChangeMs,
                nowMs = now
            )
            if (stall != null) {
                ExportDiagnostics.warn(
                    "watchdog fired: stalled ${stall.stalledSeconds}s at " +
                        "${(stall.progress * 100).toInt()}% " +
                        "(limit ${stall.limitMs}ms, finalize=${stall.inFinalizePhase}, " +
                        "elapsed=${now - startedMs}ms)"
                )
                // Fail loudly. Do NOT fake 100% and do NOT swallow this.
                // The listener's terminal state is latched by
                // finishOnce(), so cancelling cannot produce a second
                // terminal emission.
                activeTransformer.get()?.let { runCatching { it.cancel() } }
                emitTerminal(
                    ExportState.Failed(ExportStallDetector.messageFor(stall))
                )
                break
            }

            // API 24 doesn't have kotlin.time.Duration; Long overload is fine here.
            @Suppress("NON_LONG_DELAY")
            delay(500L)
        }
    }

    /**
     * Convert the per-clip state into a Media3 [EditedMediaItem].
     *
     * The order of effects matters, and the code below numbers the steps:
     * 0. speed → 1. geometry (`ScaleAndRotateTransformation` — rotation +
     *    flip only; zoom/pan ride the pose instead) → 2/3/4. the **unified**
     *    grade — B/C/S + famous effect + the full Lumetri chain composed
     *    into ONE `GradeRgbMatrix` by `ColorGradingMath.fullExportMatrix`
     *    (2026-09-10 export color parity) → 4.5 sharpness → 4.6 vignette →
     *    4.7 grain → 5. the output `Presentation` (canvas size + clip fit)
     *    → 5.5 matting (consumed *upstream* by the AI pre-render, not an
     *    effect in this chain) → 5.8 motion / opacity / transform / washes
     *    from [ClipMotionEffects] → 5.9 canvas background plate → 5.85
     *    progress bar → 5.7 subtitle burn-in → 6. audio processors.
     *
     * [reference] exists because the `Presentation`'s **size** is a property
     * of the composition, not of one clip: Media3 writes a single video
     * track, so every item has to be fitted into the same frame. A
     * multi-clip caller must pass the same segment for every item. It
     * defaults to [segment] so a single-clip build is unaffected. See
     * [ExportFrame].
     */
    private fun buildEditedMediaItem(
        segment: RenderSegment,
        canvasPreset: CanvasPreset,
        canvasBackground: CanvasBackground,
        subtitleOverlays: List<androidx.media3.effect.TextureOverlay> = emptyList(),
        multiClip: Boolean = false,
        reference: RenderSegment = segment,
        outputWidthPx: Int = 0,
        outputHeightPx: Int = 0,
        // 2026-10-13 — the project's video progress bar, plus the
        // composition-time bookkeeping it needs. Media3 hands the effect
        // chain an item-local `presentationTimeUs`, so the bar has to know
        // this segment's cumulative output origin to place itself on the
        // whole-composition timeline. Defaults keep a project with the bar
        // off on an identical effect chain.
        progressBarSettings: ProgressBarSettings = ProgressBarSettings(),
        compositionOriginMs: Long = 0L,
        totalDurationMs: Long = 0L
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

        // ---- 0. Speed (2026-09-16, ramps added 2026-09-29) ----
        // Bake the per-clip rate into the output. Previously
        // `RenderSegment.speed` had NO consumer in this file, so the
        // Speed panel and the live preview honoured it while the
        // exported file always played at 1× — a silent correctness
        // failure. The matching audio stretch is added in
        // [buildAudioProcessors] so picture and sound stay locked.
        // Placed first so the retimed source is what the geometry /
        // grade / canvas stages then process.
        //
        // **2026-09-29 — this is now the branching point between the
        // constant-speed path and the curve path.** Both call
        // `SpeedChangeEffect`; they differ only in which
        // `SpeedProvider` they hand it. A segment without an active
        // ramp takes the exact same call with the exact same constant
        // rate it always did, so an existing project exports
        // byte-identically. `SpeedChangeEffect(SpeedProvider)`
        // internally reaches the same GL program either way — the
        // float overload simply wraps a constant provider — so there is
        // no second code path in Media3 to diverge from.
        //
        // The curve is *scaled by* the constant speed rather than
        // replacing it: `clip.speed = 2` still means "the whole ramp,
        // twice as fast", which is what the Speed panel's chips promise
        // and what `RenderSegment.outputDurationMs` computes.
        val curveProvider = segment.buildSpeedProvider(sourceDurationUs = segment.sourceDurationMs * 1_000L)
        if (curveProvider != null) {
            videoEffects += androidx.media3.effect.SpeedChangeEffect(curveProvider)
        } else if (segment.speed != 1f && segment.speed > 0f) {
            videoEffects += androidx.media3.effect.SpeedChangeEffect(segment.speed)
        }

        // ---- 1. Geometry: rotation + flip ----
        // 2026-09-16: the uniform `t.scale` used to be folded in here, and
        // it was a **silent no-op**. `ScaleAndRotateTransformation`
        // deliberately grows the frame so that no input pixel is lost:
        // `configure` returns `Size(inputWidth * scaleX, inputHeight *
        // scaleY)` and divides the scale back out of its own matrix. The
        // downstream `Presentation` then scales that frame to the canvas
        // with `LAYOUT_SCALE_TO_FIT`, so the user's zoom was applied and
        // then immediately undone. Dragging the Transform panel's zoom
        // slider changed nothing in the exported file.
        //
        // The zoom and the pan now ride the pose instead, in
        // [ClipMotionEffects]' matrix — which reports the *input* size and
        // lets Media3 clip, so a zoom-in genuinely crops. Only the parts
        // that legitimately want the frame to grow stay here: rotation,
        // whose bounding box must keep every pixel.
        //
        // Flip stays here too, and deliberately. This effect runs before
        // the `Presentation` and before the pose matrix, so it is applied
        // *innermost* — the same nesting Compose's `graphicsLayer` uses
        // (scale innermost, translation outermost). Encoding it as a
        // negative scale on one axis leaves the matrix a pure ±1 flip: a
        // flip with no rotation maps the NDC square back onto itself, so
        // `configure` reports the input size and there is nothing for the
        // `Presentation` to undo.
        val t = segment.transform
        val needsFlipH = t.flipH
        val needsFlipV = t.flipV
        if (segment.rotationDegrees != 0 || needsFlipH || needsFlipV) {
            val rot = segment.rotationDegrees.toFloat()
            val sx = if (needsFlipH) -1f else 1f
            val sy = if (needsFlipV) -1f else 1f
            videoEffects += ScaleAndRotateTransformation.Builder()
                .setRotationDegrees(rot)
                .setScale(sx, sy)
                .build()
        }
        // Pan/offset (`t.offsetX`, `t.offsetY`) and the uniform
        // `t.scale` are baked by [ClipMotionEffects] instead — see above.

        // ---- 2/3/4 UNIFIED (2026-09-10 EXPORT COLOR PARITY) ----
        // The full on-screen color pipeline — B/C/S + famous effect
        // (ALL presets, not just NOIR/INVERT) + the entire Lumetri
        // grade (exposure, whites/blacks, highlights/shadows,
        // temp/tint, vibrance, HSL hue shifts, fade) — is composed
        // into ONE 4×5 matrix by [ColorGradingMath.fullExportMatrix]
        // (the exact same chain the preview renders) and baked into
        // the export via a custom Media3 `RgbMatrix`. Previously only
        // B/C/S + NOIR/INVERT survived the export and the rest was
        // preview-only, which users read as "the panels don't sync".
        // ---- 4.4. The complete on-screen colour chain ----
        //
        // 2026-10-21 — this is now ONE matrix built by
        // [ColorGradingMath.fullPreviewExportMatrix]: B/C/S → famous
        // effect → Lumetri grade → AI-upscaler CAS. The preview calls
        // the same function, so the screen and the file are the same
        // matrix by construction.
        //
        // The AI upscaler's CAS pass used to exist **only** in the
        // preview: the Upscaler panel showed a sharpened image and the
        // exported file carried none of it — the "normal export ignores
        // the upscaler" gap. It is now part of the shared chain.
        //
        // The 2× *pixel* upscale is a different thing entirely and does
        // not belong here — it needs a real resampling pass and lives
        // in the dedicated upscale engines
        // (`com.aicreatorstudio.app.ai.upscale`). This pass is the
        // per-pixel enhancement the preview already shows.
        val upscaleStrength = segment.upscale.effectiveSharpening
        val finalMatrix = com.aicreatorstudio.app.ui.screens.editor.ColorGradingMath
            .fullPreviewExportMatrix(
                grading = segment.colorGrading,
                effect = segment.videoEffect,
                brightness = segment.brightness,
                contrast = segment.contrast,
                saturation = segment.saturation,
                upscaleStrength = if (segment.upscale.isActive) upscaleStrength else 0f
            )
        if (!com.aicreatorstudio.app.ui.screens.editor.ColorGradingMath
                .isIdentityMatrix(finalMatrix)
        ) {
            videoEffects += GradeRgbMatrix(finalMatrix)
        }

        // ---- 4.5. Sharpness (2026-09-16) ----
        // `ColorGrading.sharpness` was the last write-only control in the
        // grade panel: the Detail slider wrote it, the DB persisted it, and
        // no renderer read it back, so the slider did nothing at all. It
        // cannot ride the 4x5 matrix above — a sharpener reads neighbouring
        // pixels, and a colour matrix is a per-pixel transform by
        // construction — so it is a real separable convolution instead. See
        // [com.aicreatorstudio.app.model.SharpnessKernel] for the kernel and
        // for why its weight function has to be a step function.
        //
        // Deliberately placed BEFORE the Presentation. The convolution then
        // runs at the *source* resolution, which is the resolution the
        // preview's effect chain sees (the preview player holds a single
        // media item and applies its effects to the decoded frame). Running
        // it after the canvas fit instead would put a one-canvas-pixel halo
        // in the file but a one-source-pixel halo on screen, so the two
        // would drift as soon as the canvas scaled the clip — the same class
        // of preview/export divergence this pass exists to remove.
        //
        // `build` returns null at rest, so an untouched project's effect
        // chain is byte-for-byte what it was before this pass existed. When
        // it is present it is frame-size-preserving, which it must be:
        // Media3 writes one video track per composition and the muxer
        // rejects a mid-stream dimension change.
        SharpnessEffect.build(segment.colorGrading.sharpness)?.let { videoEffects += it }

        // ---- 4.6. Vignette (2026-09-16) ----
        // `vignetteAmount` / `vignetteFeather` / `vignetteRoundness` were the
        // next three write-only fields in the same panel. A vignette darkens
        // (or, for a negative amount, brightens) by *position*, so like
        // sharpness it cannot ride the colour matrix; it is a full-frame
        // overlay whose alpha carries the strength. See [VignettePlate] for the
        // falloff, the blend equation, and why the plate is generated below
        // frame resolution.
        //
        // Placed here, before the Presentation, for the same reason as the
        // sharpness pass above: the overlay then lands on the *source* frame,
        // which is the frame the preview's effect chain composites onto. It also
        // means the canvas letterbox stays pure black instead of being darkened
        // twice, and the burn-in subtitles (added further down) stay on top of
        // the vignette rather than being dimmed by it.
        //
        // No size is passed in: Media3 calls `TextureOverlay.configure` with the
        // real frame dimensions, so this pass needs no `frameIsKnown` guard —
        // and neither do the fade washes any more, for the same reason.
        VignetteOverlay.build(segment.colorGrading)?.let { videoEffects += it }

        // ---- 4.7. Grain (2026-09-17) ----
        // The last write-only field in the project, and the only one that was
        // write-only in *both* renderers — so grain had never been rendered
        // anywhere and its appearance had to be decided rather than matched. See
        // [GrainSignal] for the model and [GrainEffect] for why it is a custom
        // fragment shader rather than a `BitmapOverlay` like the vignette above
        // (grain is high-frequency, so the downscaled-plate trick would turn it
        // into soft blobs, and a per-frame full-resolution bitmap upload is
        // ~250 MB/s at 30fps).
        //
        // Placed AFTER the vignette, and therefore also before the Presentation.
        // After the vignette so that grain stays visible in the darkened corners
        // rather than being multiplied down by them, which is how a film scan
        // looks; after the sharpness pass for the same reason in reverse — a
        // sharpener run over grain amplifies the noise instead of the detail.
        // Before the Presentation for the same reason as sharpness: the pass then
        // runs at the source resolution, which is the resolution the preview's
        // effect chain sees.
        //
        // `build` returns null at rest, so an untouched project's effect chain is
        // byte-for-byte what it was before this pass existed.
        GrainEffect.build(segment.colorGrading.grain)?.let { videoEffects += it }

        // ---- 5. Presentation (canvas size + clip fit) ----
        // The canvas preset drives the output frame size when not FREE; the
        // clip's own `fillMode` drives how it is fitted into that frame
        // (FIT letterboxes, FILL crops). The **size** deliberately comes
        // from the composition-wide [reference] rather than from `segment`:
        // Media3 writes one video track, so every item must agree on the
        // frame — see [ExportFrame]. The canvas *background* is a separate
        // pass and lands below, once the bars exist — see
        // [CanvasBackgroundOverlay].
        val presentation = buildPresentation(
            canvasPreset = canvasPreset,
            reference = reference,
            multiClip = multiClip,
            fillMode = segment.fillMode
        )
        if (presentation != null) {
            videoEffects += presentation
        }

        // The frame the passes below actually see. With a Presentation it is
        // exactly the resolved size. With no Presentation it is the source's own
        // size, which is what the caller resolved — *except* when step 1's
        // rotation grew the frame to the rotated bounding box, a size that is
        // not knowable from here.
        //
        // This is now only a **hint**, used by [CanvasBackgroundOverlay]'s
        // build-time "is there anything to paint at all" test. That test is a
        // pure optimisation: when the size is unknown it simply keeps the pass
        // and the plate decides per frame. It is deliberately `0` in the
        // rotation case rather than a guess, because a canvas size smaller than
        // the real frame could make the test report "the video covers the
        // canvas" for a frame that is in fact larger.
        //
        // The *washes* no longer read it. They used to, which meant that a
        // rotated clip on a FREE canvas — the one case where this is `0` —
        // silently lost every fade, flash and `opacity` pass. Each wash now
        // observes the real frame size from its own `configure`; see
        // [ClipMotionEffects.build].
        val frameIsKnown = presentation != null || segment.rotationDegrees == 0
        val hintWidthPx = if (frameIsKnown) outputWidthPx else 0
        val hintHeightPx = if (frameIsKnown) outputHeightPx else 0

        // ---- 5.5. Matting (Background Remove) ----
        // The per-clip [RenderSegment.matting] is honored by the AI
        // pre-render: [AiExportHooks.prepareSourceUri] composites the
        // sidecar alpha + chosen background into an intermediate the
        // Transformer consumes (see AiExportHooks KDoc for modes).
        // The pass-through [com.aicreatorstudio.app.ai.PersonMatteEffect]
        // below is intentionally NOT added — the composite already
        // happened upstream.
        //
        // 2026-10-17 — stale claim removed: the live preview shows only
        // a "BG Remove ready" chip (EditorScreen); it does NOT render
        // the composite. Preview compositing is not part of v1.
        if (segment.matting?.enabled == true) {
            // Consumed by the AI pre-render (see startExport step 4).
        }

        // ---- 5.8. Transitions + motion presets + opacity + transform ----
        // Until now `transitionIn` / `transitionOut` never reached the
        // render graph at all — the panel wrote them, the DB persisted
        // them, and the export had no field to receive them, so every
        // transition was silently dropped from the output file. The
        // per-clip motion preset had the mirror-image problem: the preview
        // honoured `RenderSegment.animation` and the export ignored it, so
        // the file always played the clip dead still.
        //
        // `opacity` and the Transform panel's pan/zoom were the last two
        // in the same family, and they land here for the same reason: they
        // are all *static per-clip inputs to one per-frame pose*.
        //
        // All of them are now baked as a per-frame geometry matrix, an
        // animated blur, and full-frame flash/fade washes; see
        // [ClipMotionEffects] for the mechanism and for why each pose
        // component needs a different Media3 hook. They share one matrix
        // because they are the same pose — see
        // [com.aicreatorstudio.app.model.ClipMotion.compose].
        //
        // Placed AFTER the Presentation so the transform operates on the
        // finished canvas — which is also what makes the pan's
        // canvas-fraction units mean the same thing here as they do in the
        // preview, and what keeps the flip in step 1 *inside* the pan
        // rather than mirroring it.
        //
        // The fade wash's colour is the *canvas* colour, not black. The
        // preview fades a clip by compositing it over the letterbox `Box` at a
        // reduced alpha, so it dims toward whatever is behind it; the export
        // reproduces that with a full-frame wash, and passing black there was
        // exact on the default canvas but visibly wrong on a coloured one —
        // the clip dimmed to black while the bars beside it stayed coloured.
        // `canvasBackground` therefore goes into this pass as well as into
        // [CanvasBackgroundOverlay] below; [CanvasLetterbox.fadeColorArgb] is
        // the single place that decides what colour that is, so the wash and
        // the bars cannot disagree.
        //
        // The explicit black and white flashes stay black and white and are
        // drawn on top of the fade, in the preview's paint order. Splitting
        // those out of the fade pass also fixed a second, subtler divergence:
        // the old code folded the fade's alpha and `flashBlack` into one pass
        // with `max`, where the preview *composes* them — so `GLITCH`, which
        // sets both, was under-darkened.
        //
        // No width is passed in any more: each wash observes the real frame
        // size from Media3's `TextureOverlay.configure`. The height is still
        // needed, but only to convert the pose's dp blur into a pixel sigma.
        // Previously both washes were sized from `washHeightPx`, which is `0`
        // in the FREE-canvas + rotation case — so a rotated clip's fade became
        // a cut. See [ClipMotionEffects.build].
        //
        // The height that goes in is the height of the frame the convolution
        // will actually run on, which is *not* always the resolved size —
        // [blurReferenceHeightPx] owns that decision and is unit-tested.
        // [frameIsKnown] is exactly the "the resolved size is the frame size"
        // predicate, so it feeds straight in.
        //
        // A segment with no transition, no motion preset, no transform and
        // full opacity appends nothing here, so the effect chain for an
        // untouched project is identical to before.
        videoEffects += ClipMotionEffects.build(
            segment = segment,
            outputHeightPx = blurReferenceHeightPx(
                resolvedWidthPx = outputWidthPx,
                resolvedHeightPx = outputHeightPx,
                rotationDegrees = segment.rotationDegrees,
                frameIsPinned = frameIsKnown
            ),
            canvasBackground = canvasBackground
        )

        // ---- 5.9. Canvas background (2026-09-16) ----
        // The last write-only field. `CanvasBackground` was persisted, and the
        // preview painted it (`EditorScreen` uses it as the canvas `Box`'s
        // background), but the export ignored it — this method declared
        // `canvasBackground` with a `@Suppress("UNUSED_PARAMETER")` and never
        // read it, so a project with a coloured letterbox exported black bars.
        // `CanvasBackground.Black` is the engine's native bar colour and was
        // already correct; see [CanvasBackgroundOverlay] for the SolidColor
        // case and for why `Blur` is still out of reach.
        //
        // Placed AFTER the Presentation (the bars do not exist before it) and
        // AFTER [ClipMotionEffects] (the plate has to stay static in canvas
        // space while the pose moves the video, which is the same relationship
        // the preview's canvas Box has to its posed foreground PlayerView).
        // Repainting the bars after the fade wash is deliberate too: the wash is
        // painted in the canvas colour (`CanvasLetterbox.fadeColorArgb`), so it
        // is invisible on bars that are already that colour, and this plate
        // guarantees they end up exactly the canvas colour rather than a
        // washed-over approximation of it. On screen a fade dims the video
        // toward the background and leaves the bars alone, which is what this
        // order reproduces.
        //
        // It reads the frame size from Media3's `TextureOverlay.configure`
        // rather than from the hint, so it needs no `frameIsKnown` guard. The
        // hint is passed only for the build-time "is there anything to paint"
        // test, where an unknown size safely means "keep the pass".
        CanvasBackgroundOverlay.build(
            segment = segment,
            background = canvasBackground,
            outputWidthPx = hintWidthPx,
            outputHeightPx = hintHeightPx
        )?.let { videoEffects += it }

        // ---- 5.85. Video progress bar (2026-10-13) ----
        // A full-width bar that fills 0 -> 100% across the WHOLE
        // composition, drawn inside the canvas. It sits here — after the
        // canvas plate (the bar is inside the canvas, so the letterbox bars
        // have to exist first) and before the subtitle burn-in (a caption
        // still reads on top of it) — which mirrors the preview's layer
        // order exactly.
        //
        // `compositionOriginMs` is what turns Media3's item-local
        // `presentationTimeUs` into a whole-composition position, so a
        // multi-clip project gets ONE continuous bar instead of a bar that
        // restarts at every cut. See [ProgressBarOverlay].
        //
        // `build` returns null when the bar is off (the default), so an
        // untouched project's effect chain is byte-identical to before this
        // feature existed.
        ProgressBarOverlay.build(
            settings = progressBarSettings,
            compositionOriginMs = compositionOriginMs,
            totalDurationMs = totalDurationMs
        )?.let { videoEffects += it }

        // ---- 5.7. Subtitle burn-in (2026-09-07) ----
        // Media3's OverlayEffect composites the caption bitmaps over
        // every frame on its GL thread. Overlays carry their own
        // time windows in presentation-time microseconds, so the
        // effect is a no-op for frames outside any caption.
        if (subtitleOverlays.isNotEmpty()) {
            videoEffects += androidx.media3.effect.OverlayEffect(subtitleOverlays)
        }

        // ---- 6. Audio: per-clip volume / mute ----
        val audioProcessors = buildAudioProcessors(segment)

        val effects = Effects(
            /* audioProcessors = */ audioProcessors,
            /* videoEffects    = */ videoEffects
        )

        return EditedMediaItem.Builder(mediaItem)
            .setEffects(effects)
            .build()
    }

    /**
     * Pick the output [Presentation] for the export.
     *
     * The size, the "is one needed at all" decision and the layout mapping
     * all live in [ExportFrame], which is pure and unit-tested; this method
     * only turns those answers into a Media3 effect. See that object for the
     * three canvas cases and for why the FREE canvas needs one shared
     * reference segment.
     *
     * **2026-09-16 — `fillMode` is now honoured.** Both branches used to
     * hard-code `LAYOUT_SCALE_TO_FIT`, so a clip the user had set to FILL
     * exported letterboxed and the FIT/FILL/MANUAL control looked inert
     * outside the preview. The layout now comes from the clip's own
     * [FillMode] via [ExportFrame.layoutFor].
     *
     * Note the deliberate split: the **size** is a property of the
     * composition (one shared reference, because Media3 writes a single
     * video track), while the **layout** is a property of the clip. Both
     * layouts force the output to exactly the requested size, so a per-clip
     * layout cannot break the sequence's single-format requirement.
     *
     * **Canvas background is a separate pass, not a `Presentation` option.**
     * Media3 1.5.0's `Presentation.createForWidthAndHeight(int, int, int)`
     * takes only a width / height / layout — there is no background-colour
     * overload — and there is no "draw behind the base layer" hook either.
     * [CanvasBackgroundOverlay] therefore paints the colour *over* the bars
     * once this method has created them, which is equivalent for a
     * straight-alpha overlay that is opaque in the bar region and transparent
     * over the video. Only `CanvasBackground.Blur` remains unhonoured, because
     * it needs a second video sequence rather than a plate.
     */
    private fun buildPresentation(
        canvasPreset: CanvasPreset,
        reference: RenderSegment,
        multiClip: Boolean,
        fillMode: FillMode
    ): Presentation? {
        if (!ExportFrame.needsPresentation(canvasPreset, multiClip)) return null
        val size = ExportFrame.resolve(canvasPreset, reference) ?: return null
        return Presentation.createForWidthAndHeight(
            /* width  = */ size.first,
            /* height = */ size.second,
            /* layout = */ ExportFrame.layoutFor(fillMode)
        )
    }

    /**
     * Build the caption overlays for the whole composition.
     *
     * Delegates the timeline arithmetic to
     * [CompositionTimeline.subtitleOriginMs] so the factory can be reused
     * unchanged for every segment instead of only the first.
     */
    private fun buildSubtitleOverlays(
        project: VideoProject,
        segments: List<RenderSegment>,
        compositionOriginsMs: List<Long>,
        canvasPreset: CanvasPreset,
        config: ExportConfig
    ): List<androidx.media3.effect.BitmapOverlay> = runCatching {
        // PHASE A (Persistence Integrity) — the gate used to be
        // `subtitleSource != null`, so ordinary text, paragraphs and
        // watermarks previewed on screen and then silently vanished
        // from the exported MP4. Anything with non-blank text now
        // burns; blank-text overlays carry nothing to draw. The whole
        // track is still collapsed into one overlay slot, so this
        // cannot reach Media3's 15-overlay ceiling by itself.
        if (project.textOverlays.none { it.text.isNotBlank() }) return emptyList()
        val outW = if (canvasPreset.exportWidth > 0) canvasPreset.exportWidth
        else config.effectiveWidthPx
        val outH = if (canvasPreset.exportHeight > 0) canvasPreset.exportHeight
        else config.effectiveHeightPx
        segments.flatMapIndexed { index, segment ->
            SubtitleBurnInFactory.createOverlays(
                context = context,
                textOverlays = project.textOverlays,
                clipTimelineStartMs = CompositionTimeline.subtitleOriginMs(
                    segment = segment,
                    compositionOriginMs = compositionOriginsMs[index]
                ),
                outputWidthPx = outW,
                outputHeightPx = outH,
                outputDurationMs = segment.outputDurationMs
            )
        }
    }.getOrElse { emptyList() }

    /**
     * Build the per-clip audio processor chain.
     *
     * - [VolumeScalingAudioProcessor] multiplies PCM by
     *   [RenderSegment.volume] (1f = unity, 0f = muted).
     * - [SpeedChangingAudioProcessor] time-stretches the audio to match
     *   [RenderSegment.speed] so it stays locked to the video, which is
     *   retimed by `SpeedChangeEffect` in [buildEditedMediaItem].
     * - [FadeAudioProcessor] ramps the gain at the clip's head and tail,
     *   using [com.aicreatorstudio.app.model.AudioFade] — the same curve
     *   the live preview applies.
     *
     * All three operate on float PCM; Media3 does the format plumbing. The
     * identity cases are skipped because the processors are no-ops then —
     * purely an optimisation, and the reason an untouched project exports
     * byte-identically.
     *
     * **Order matters for the fade:** it is appended LAST so it sees
     * post-speed audio. The clip-local clock it counts is then in output
     * time, which is what the user set the fade length against, and it
     * lines up with [RenderSegment.outputDurationMs]. The volume and speed
     * processors are time-invariant, so their position is free.
     */
    private fun buildAudioProcessors(segment: RenderSegment): List<AudioProcessor> {
        val processors = mutableListOf<AudioProcessor>()
        if (segment.volume != 1f) {
            processors += VolumeScalingAudioProcessor(segment.volume.coerceIn(0f, 1f))
        }

        // ---- 2026-10-13 Voice Changer ----
        //
        // Placed BEFORE the speed stage, which is the only order that
        // matches the live preview. There, a custom `AudioProcessor` runs
        // ahead of the sink's own Sonic stage (Media3 builds
        // `DefaultAudioProcessorChain` as `[custom..., silenceSkip, sonic]`),
        // so the voice transformation always sees source-rate audio and
        // the rate change is applied on top of its result. Putting these
        // after `SpeedChangingAudioProcessor` would make an echo tail a
        // different length in the exported file than in the preview —
        // and only for clips that are also speed-ramped, which is the
        // hardest possible version of that bug to notice.
        //
        // Two processors, because they do two different jobs:
        //  * `VoiceEffectAudioProcessor` runs the timbre stages (ring
        //    modulation / band-pass / echo) from the shared `VoiceEffectDsp`;
        //  * `SonicAudioProcessor` shifts pitch without changing duration.
        //    It is the same algorithm the preview's `PlaybackParameters`
        //    pitch goes through, so the two renderers agree on what
        //    "1.6× higher" sounds like.
        //
        // Both are skipped entirely for `NONE`, which is what keeps an
        // untouched project's exported audio byte-identical.
        if (!segment.voiceEffect.isIdentity) {
            VoiceEffectAudioProcessor.build(segment.voiceEffect)?.let { processors += it }
            if (VoiceEffects.needsPitchStage(segment.voiceEffect)) {
                processors += SonicAudioProcessor().apply {
                    setPitch(VoiceEffects.clampPitch(segment.voiceEffect.pitchFactor))
                }
            }
        }

        // 2026-09-29 — the curve path reuses this exact processor. There
        // is no separate "ramp audio" implementation to keep in sync:
        // `SpeedChangingAudioProcessor` already consumes a
        // `SpeedProvider`, and it already stretches through
        // `SonicAudioProcessor` (so pitch is preserved, which is what
        // `SpeedCurve.preservePitch` assumes). Handing it the same
        // provider the video effect got is what makes the two stay
        // locked — a curve evaluated twice could drift.
        //
        // 2026-10-13 — note that this is also what keeps the voice
        // changer from *breaking* pitch preservation: the pitch stage
        // above is a separate, explicit shift, and the speed stage's own
        // pitch preservation is untouched. A clip can be at 2× with a
        // ramped curve and a chipmunk voice, and all three compose.
        val curveProvider = segment.buildSpeedProvider(sourceDurationUs = segment.sourceDurationMs * 1_000L)
        if (curveProvider != null) {
            processors += SpeedChangingAudioProcessor(curveProvider)
        } else if (segment.speed != 1f && segment.speed > 0f) {
            processors += SpeedChangingAudioProcessor(ConstantSpeedProvider(segment.speed))
        }
        if (!AudioFade.isIdentity(segment.fadeInMs, segment.fadeOutMs)) {
            processors += FadeAudioProcessor(
                fadeInMs = segment.fadeInMs,
                fadeOutMs = segment.fadeOutMs,
                clipDurationMs = segment.outputDurationMs
            )
        }
        return processors
    }

    /**
     * Build **one Media3 sequence per audio-only clip**, so those clips are
     * mixed into the composition instead of being concatenated onto it.
     *
     * One sequence per clip is deliberate, not lazy: `EditedMediaItem`
     * instances inside a single sequence must **not overlap in time**, and
     * two songs layered over each other on the editor timeline is a
     * perfectly ordinary thing for a user to do. Separate sequences may
     * overlap, and Media3 mixes them — so this shape handles both the
     * "one music track" and the "music + SFX at the same moment" cases
     * without any overlap analysis.
     *
     * Each clip is preceded by an `addGap` so it lands at its editor
     * position, mapped onto the composition timeline by
     * [CompositionTimeline.outputMsForTimelineMs]. Gaps are only legal in
     * audio sequences, which is exactly what these are.
     *
     * **Units trap:** `addGap`'s KDoc says "milliseconds", but it forwards
     * its argument straight to `EditedMediaItem.Builder.setDurationUs`,
     * which is documented in **microseconds** and asserts `> 0`. So the
     * value is multiplied by 1000 here, and a zero-length gap is never
     * emitted (it would throw).
     *
     * Content past [videoDurationMs] is dropped. The video defines the
     * length of the exported file, so letting a long music track extend the
     * composition would silently change the video's duration.
     */
    private fun buildAudioSequences(
        audioSegments: List<RenderSegment>,
        videoSegments: List<RenderSegment>,
        videoDurationMs: Long
    ): List<EditedMediaItemSequence> {
        if (audioSegments.isEmpty() || videoSegments.isEmpty() || videoDurationMs <= 0L) {
            return emptyList()
        }
        return audioSegments.mapNotNull { segment ->
            val startMs = CompositionTimeline.outputMsForTimelineMs(
                segments = videoSegments,
                timelineMs = segment.timelineStartMs
            ).coerceIn(0L, videoDurationMs)
            // Entirely past the end of the video — nothing to mix in.
            if (startMs >= videoDurationMs) return@mapNotNull null

            val playMs = minOf(segment.outputDurationMs, videoDurationMs - startMs)
            if (playMs <= 0L) return@mapNotNull null

            val builder = EditedMediaItemSequence.Builder()
            if (startMs > 0L) {
                // ms -> us; see the units trap in this function's KDoc.
                builder.addGap(startMs * 1_000L)
            }
            val item = buildAudioOnlyMediaItem(segment, playMs) ?: return@mapNotNull null
            builder.addItem(item)
            builder.build()
        }
    }

    /**
     * Build the [EditedMediaItem] for one audio-only clip: the trimmed
     * source range, the shared per-clip audio chain (volume, speed, fades),
     * and **no** video effects.
     *
     * `setRemoveVideo(true)` is not merely an optimisation — an
     * `isAudioOnly` clip can still point at a container that happens to
     * carry a video track, and without this the "audio" sequence would
     * contribute a second video stream to the composition.
     *
     * @param playMs how much output time this clip gets; it is shorter than
     *   [RenderSegment.outputDurationMs] only when the clip was clamped to
     *   the end of the video. The read range and the fade-out window are
     *   both derived from it, so a clamped clip still fades out properly
     *   instead of being cut off mid-ramp.
     */
    private fun buildAudioOnlyMediaItem(
        segment: RenderSegment,
        playMs: Long
    ): EditedMediaItem? {
        val speed = if (segment.speed.isFinite() && segment.speed > 0f) segment.speed else 1f
        // Output time -> source time: a 2x clip consumes source twice as
        // fast.
        //
        // **2026-09-29 — this is `playMs * speed` only for a constant
        // rate.** With a ramp, "how much source does N ms of output
        // cover" is the *inverse* of the time map, not a multiplication:
        // a clip that spends most of its output in slow motion consumes
        // far less source per output second than a multiplication by an
        // average would suggest. Using the old formula here would make
        // the audio sequence read the wrong range and desynchronise a
        // music clip laid under a ramped video. The interpolator has
        // the correct inverse (built by bisecting its own forward map),
        // so the ramp case asks it.
        val sourceMs = if (segment.usesSpeedCurve) {
            val interpolator = SpeedCurveInterpolator(
                sourceDurationUs = segment.sourceDurationMs * 1_000L,
                curve = segment.speedCurve!!.let { c ->
                    if (speed == 1f) c else c.copy(
                        points = c.points.map { it.copy(speed = it.speed * speed) }
                    )
                }
            )
            // playMs is already an *output* offset, but it is clamped
            // to the video length above, so it can exceed this
            // clip's own output duration — the interpolator clamps.
            val sourceUs = interpolator.outputPtsToSourcePts(playMs * 1_000L)
            (sourceUs / 1_000L).coerceAtMost(segment.sourceDurationMs)
        } else {
            (playMs * speed).toLong().coerceAtMost(segment.sourceDurationMs)
        }
        val endMs = (segment.sourceStartMs + sourceMs).coerceAtMost(segment.sourceEndMs)
        // 2026-09-19 MUXER GUARD — an empty read range (extreme speed
        // rounding the source window to zero) would contribute an
        // audio track with no samples; Mp4Muxer rejects sample-less
        // tracks with an opaque write error. Drop the clip instead.
        if (endMs <= segment.sourceStartMs) return null

        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(segment.sourceStartMs)
            .setEndPositionMs(endMs)
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(segment.sourceUri)
            .setClippingConfiguration(clipping)
            .build()

        // Re-derive the segment's own output duration from the clamped range
        // so FadeAudioProcessor measures its fade-out against the audio that
        // will actually be written, not against the unclamped clip.
        val effective = segment.copy(sourceEndMs = endMs)

        return EditedMediaItem.Builder(mediaItem)
            .setRemoveVideo(true)
            .setEffects(
                Effects(
                    /* audioProcessors = */ buildAudioProcessors(effective),
                    /* videoEffects    = */ emptyList()
                )
            )
            .build()
    }
}

/**
 * Build the Media3 [SpeedProvider] for a segment, or `null` when the
 * segment plays at a constant rate.
 *
 * **The single place where a ramp and the constant `speed` are
 * combined.** Both the video effect and the audio processor call this, so
 * the two cannot end up with different rates, and
 * [RenderSegment.outputDurationMs] performs the same multiplication when
 * it predicts the composition length. Three consumers, one formula.
 *
 * The constant speed is folded into the curve's knots rather than
 * applied as a second effect. Two chained `SpeedChangeEffect`s would
 * also work in principle, but Media3 composes speed effects by
 * retiming one after the other, and stacking them makes the *duration*
 * arithmetic (which `RenderSegment` already does analytically) harder
 * to verify than a single pre-scaled ramp.
 *
 * @param sourceDurationUs length of the source range this segment
 *   covers, in microseconds. Must match [RenderSegment.sourceDurationMs]
 *   or the provider's time axis will not line up with the frames
 *   Media3 hands it.
 */
private fun RenderSegment.buildSpeedProvider(sourceDurationUs: Long): SpeedProvider? {
    val curve = speedCurve ?: return null
    if (!curve.isActive || sourceDurationUs <= 0L) return null
    // A zero or negative constant speed is nonsense the clip model
    // already rejects; treat it as 1× rather than letting it collapse
    // every knot to zero and stall the mapping.
    val constant = if (speed.isFinite() && speed > 0f) speed else 1f
    val scaled = if (constant == 1f) {
        curve
    } else {
        curve.copy(points = curve.points.map { it.copy(speed = it.speed * constant) })
    }
    return SpeedCurveSpeedProvider(SpeedCurveInterpolator(sourceDurationUs, scaled))
}

/**
 * A [SpeedProvider] that reports the same rate for every timestamp.
 *
 * Media3 only exposes time-varying speed providers for this hook, so
 * baking a clip's single constant speed needs this trivial adapter.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private class ConstantSpeedProvider(private val speed: Float) : SpeedProvider {
    override fun getSpeed(timeUs: Long): Float = speed
    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
}

/**
 * Audio processor that multiplies every float PCM sample by a fixed
 * gain. Used to bake the per-clip volume (and the Mute toggle) into
 * the exported MP4.
 *
 * Operates on interleaved 32-bit float PCM in [-1, 1]. Media3 takes
 * care of converting from the source encoding (typically 16-bit
 * signed integer PCM at 44.1/48 kHz) via its built-in resamplers
 * and the encoder writes the result as AAC.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private class VolumeScalingAudioProcessor(
    private val gain: Float
) : BaseAudioProcessor() {

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat):
            AudioProcessor.AudioFormat {
        // Pass through the input sample rate and channel count, but
        // require float PCM encoding on the way in (Media3 will
        // resample and convert to float before reaching us). The
        // output format is the same as the input format — gain is
        // applied sample-by-sample without changing the layout.
        outputAudioFormat = AudioProcessor.AudioFormat(
            inputAudioFormat.sampleRate,
            inputAudioFormat.channelCount,
            C.ENCODING_PCM_FLOAT
        )
        return outputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return
        val sampleCount = remaining / 4
        // View the input as little-endian floats; do NOT mutate the
        // caller's buffer position until we have finished reading.
        val inView = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
        val outBuffer = replaceOutputBuffer(sampleCount * 4)
        val outView = outBuffer.order(ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
        for (i in 0 until sampleCount) {
            val s = inView.get() * gain
            // Clamp to [-1, 1] to keep downstream encoders happy
            // (a true integer path would saturate; float just clips).
            outView.put(s.coerceIn(-1f, 1f))
        }
        outBuffer.position(0)
        outBuffer.limit(sampleCount * 4)
        // We've consumed the entire input buffer.
        inputBuffer.position(inputBuffer.limit())
    }
}

/**
 * Audio processor that ramps the gain at the head and tail of a clip.
 *
 * Backs the Audio panel's Fade In / Fade Out sliders, which until
 * 2026-09-16 were write-only: the values were persisted and nothing in
 * either renderer ever read them back. The curve comes from
 * [com.aicreatorstudio.app.model.AudioFade], shared with the live preview,
 * so the two cannot drift apart.
 *
 * Clip-local time is tracked by **counting input frames** rather than by
 * asking Media3 for a timestamp — `AudioProcessor.queueInput` carries no
 * time information at all. That is exact for a linear export: the processor
 * is constructed per segment, so it sees that segment's audio from its very
 * first frame, and [onConfigure] / [onFlush] restart the clock whenever the
 * stream restarts.
 *
 * Operates on interleaved 32-bit float PCM in [-1, 1], exactly like
 * [VolumeScalingAudioProcessor].
 */
@androidx.annotation.OptIn(UnstableApi::class)
private class FadeAudioProcessor(
    private val fadeInMs: Long,
    private val fadeOutMs: Long,
    private val clipDurationMs: Long
) : BaseAudioProcessor() {

    /** Samples emitted since the stream (re)started. */
    private var samplesProcessed = 0L
    private var sampleRate = 0
    private var channelCount = 1

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat):
            AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        samplesProcessed = 0L
        outputAudioFormat = AudioProcessor.AudioFormat(
            inputAudioFormat.sampleRate,
            inputAudioFormat.channelCount,
            C.ENCODING_PCM_FLOAT
        )
        return outputAudioFormat
    }

    /** A flush means a new stream starts here, so clip-local time restarts. */
    override fun onFlush() {
        samplesProcessed = 0L
    }

    override fun onReset() {
        samplesProcessed = 0L
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return
        val sampleCount = remaining / 4
        // View the input as little-endian floats; do NOT mutate the
        // caller's buffer position until we have finished reading.
        val inView = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
        val outBuffer = replaceOutputBuffer(sampleCount * 4)
        val outView = outBuffer.order(ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()

        // Gain is computed per sample, not once per buffer, so the ramp is
        // sample-accurate instead of stepping in ~20 ms chunks.
        val rate = sampleRate.toDouble()
        for (i in 0 until sampleCount) {
            val frame = (samplesProcessed + i) / channelCount
            val clipLocalMs = if (rate > 0.0) frame * 1000.0 / rate else 0.0
            val gain = AudioFade.gainAt(
                clipLocalMs = clipLocalMs,
                clipDurationMs = clipDurationMs,
                fadeInMs = fadeInMs,
                fadeOutMs = fadeOutMs
            )
            val s = inView.get() * gain
            // Clamp to [-1, 1] to keep downstream encoders happy
            // (a true integer path would saturate; float just clips).
            outView.put(s.coerceIn(-1f, 1f))
        }
        samplesProcessed += sampleCount
        outBuffer.position(0)
        outBuffer.limit(sampleCount * 4)
        // We've consumed the entire input buffer.
        inputBuffer.position(inputBuffer.limit())
    }
}

/**
 * Encapsulates the output destination. On API 29+ we render to a
 * MediaStore-owned file inside `Movies/AICreatorStudio/`. On legacy
 * storage we render to the app's external Movies directory. The file
 * always lives on disk before we hand the URI to the user; in the
 * MediaStore case the file is owned by MediaStore from the start.
 *
 * Public (not internal) because [AiExportHooks] — the AI pre/post
 * passes — consumes it; both live in the same module.
 */
sealed interface OutputTarget {
    /** Absolute path on disk, consumed by `Transformer.start(...)`. */
    fun outputPath(): String
    /**
     * Build the final [ExportState.Succeeded] once the file is on disk.
     *
     * **RULE 9 guard:** callers must only invoke this after confirming
     * the rendered file is present and non-zero. The default
     * implementation here trusts the caller's [fileSizeBytes] but the
     * two `*OutputTarget` overrides independently verify the file on
     * disk and downgrade a 0-byte outcome to [ExportState.Failed] so
     * the UI never advertises a success card backed by an empty MP4.
     */
    fun finalize(fileSizeBytes: Long, durationMs: Long): ExportState
    /**
     * Extract the output URI the file was written to, if known.
     * `null` for the legacy `FileOutputTarget` (the URI is rebuilt in
     * [finalize] via [Uri.fromFile]).
     */
    fun outputUriOrNull(): Uri? = null
    /** Remove any partially-written file. */
    fun cleanupOnFailure()

    /**
     * Replace the target content with [file] (used by the Voice
     * Clean post-pass, which remuxes cleaned audio into a temp
     * file). Returns true on success.
     */
    suspend fun replaceWithFile(file: File): Boolean

    companion object {
        /**
         * Pick the right output target for the current API level. Returns
         * `null` when no writable destination exists.
         *
         * 2026-09-19 TWO-STEP SAFE EXPORT — the Transformer NEVER
         * writes to MediaStore directly: Media3 1.5.0 has no `fd:`
         * output support, so the old `"fd:${pfd.fd}"` path was opened
         * as a literal file and died with
         * `EROFS (Read-only file system)` inside the muxer. Now:
         *   1. Transformer renders into an app-private cache file
         *      (guaranteed writable on every API level, no
         *      permission, never EROFS);
         *   2. on success the file is published to the gallery via
         *      MediaStore + `openOutputStream(uri, "w")`, then the
         *      temp is deleted.
         */
        fun create(
            context: Context,
            config: ExportConfig,
            segment: RenderSegment
        ): OutputTarget? {
            val baseName = sanitiseBaseName(config.outputBaseName, segment)
            val fileName = "$baseName.mp4"

            return if (Build.VERSION.SDK_INT >= 29) {
                val dir = File(context.cacheDir, "exports")
                runCatching { if (!dir.exists()) dir.mkdirs() }.getOrNull()
                if (!dir.exists() && !dir.mkdirs()) return null
                // Sweep stale temps from crashed runs (best-effort;
                // the live export always owns a fresh timestamped name).
                runCatching {
                    dir.listFiles { f -> f.isFile && isTempExportFile(f.name) }
                        ?.forEach { runCatching { it.delete() } }
                }
                val file = File(dir, tempExportFileName(System.currentTimeMillis()))
                CacheFileOutputTarget(
                    context = context,
                    file = file,
                    displayName = fileName
                )
            } else {
                @Suppress("DEPRECATION")
                val moviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                if (moviesDir == null) return null
                if (!moviesDir.exists()) moviesDir.mkdirs()
                val file = File(moviesDir, fileName)
                FileOutputTarget(file, displayName = fileName)
            }
        }

        private fun sanitiseBaseName(baseName: String, segment: RenderSegment): String {
            val now = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val projectPart = baseName
                .ifBlank { segment.displayName.substringBeforeLast('.', segment.displayName) }
                .replace(Regex("[^A-Za-z0-9_\\-]"), "_")
                .take(40)
            return "AI_${projectPart}_$now"
        }

        /**
         * 2026-09-19 — temp-file contract for the two-step export:
         * the name embeds the creation time (unique per run) and
         * [isTempExportFile] recognises exactly these files so the
         * stale-temp sweep can never touch user content.
         */
        internal fun tempExportFileName(nowMs: Long): String =
            "export_temp_$nowMs.mp4"

        internal fun isTempExportFile(name: String): Boolean =
            name.startsWith("export_temp_") && name.endsWith(".mp4")
    }
}

/**
 * 2026-09-19 TWO-STEP SAFE EXPORT target (API 29+).
 *
 * Step 1: the Transformer renders straight into [file] under the
 * app-private cache — a REAL filesystem path it can always open
 * (no `fd:` hack, no Scoped-Storage friction, never EROFS).
 *
 * Step 2 ([finalize], only after the bytes are verified on disk):
 * the file is published to `Movies/AICreatorStudio` via a
 * MediaStore insert + `openOutputStream(uri, "w")` copy, the
 * `IS_PENDING` flag is cleared, and the temp is deleted.
 * Anything failing along the way deletes its own partial output,
 * so the gallery never shows a broken entry.
 */
private class CacheFileOutputTarget(
    private val context: Context,
    private val file: File,
    private val displayName: String
) : OutputTarget {

    override fun outputPath(): String = file.absolutePath

    override fun finalize(fileSizeBytes: Long, durationMs: Long): ExportState {
        // RULE 9: re-stat the file ourselves before declaring success.
        val onDiskSize = if (file.exists()) file.length() else 0L
        ExportDiagnostics.event(
            "finalize: onDiskSize=$onDiskSize reportedSize=$fileSizeBytes"
        )
        if (onDiskSize <= 0L) {
            runCatching { file.delete() }
            return ExportState.Failed(
                "Export produced a 0-byte file. " +
                    "Try again with a longer source clip."
            )
        }
        val publishStart = System.currentTimeMillis()
        ExportDiagnostics.event("publishing to gallery ($onDiskSize bytes)…")
        val uri = publishToGallery() ?: return ExportState.Failed(
            "The video rendered but could not be saved to the gallery. " +
                "Check free storage and try again."
        )
        ExportDiagnostics.event(
            "published in ${System.currentTimeMillis() - publishStart}ms"
        )
        runCatching { file.delete() }
        return ExportState.Succeeded(
            outputUri = uri,
            fileName = displayName,
            fileSizeBytes = onDiskSize,
            durationMs = durationMs
        )
    }

    /**
     * Copy the rendered file into MediaStore and return its
     * `content://` URI, or null when anything fails (the placeholder
     * row is removed so no broken entry remains).
     */
    private fun publishToGallery(): Uri? {
        return try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/AICreatorStudio"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY
            )
            val uri: Uri = resolver.insert(collection, values) ?: return null
            try {
                resolver.openOutputStream(uri, "w")?.use { out ->
                    file.inputStream().use { ins -> ins.copyTo(out) }
                } ?: run {
                    runCatching { resolver.delete(uri, null, null) }
                    return null
                }
            } catch (_: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                return null
            }
            // Verify the bytes really landed before clearing pending.
            val written = runCatching {
                resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
            }.getOrNull()
            if (written == null || written <= 0L) {
                runCatching { resolver.delete(uri, null, null) }
                return null
            }
            try {
                resolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    },
                    null, null
                )
            } catch (_: Throwable) {
                // Non-fatal: the file is written and readable.
            }
            uri
        } catch (_: Throwable) {
            null
        }
    }

    override fun cleanupOnFailure() {
        runCatching { file.delete() }
    }

    override suspend fun replaceWithFile(file: File): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                file.copyTo(this@CacheFileOutputTarget.file, overwrite = true)
                true
            }.getOrDefault(false)
        }
}

internal class FileOutputTarget(
    private val file: File,
    private val displayName: String
) : OutputTarget {

    override fun outputPath(): String = file.absolutePath

    override fun finalize(fileSizeBytes: Long, durationMs: Long): ExportState {
        // RULE 9: re-stat the file before declaring success. A 0-byte
        // file is a real failure mode (encoder crash, zero-duration
        // clip, cancelled mid-flush). Reporting "Succeeded" here would
        // hand the user a 0-byte MP4 the gallery can't play.
        val onDiskSize = if (file.exists()) file.length() else 0L
        if (onDiskSize <= 0L) {
            runCatching { file.delete() }
            return ExportState.Failed(
                "Export produced a 0-byte file. " +
                    "Try again with a longer source clip."
            )
        }
        return ExportState.Succeeded(
            outputUri = Uri.fromFile(file),
            fileName = displayName,
            fileSizeBytes = onDiskSize,
            durationMs = durationMs
        )
    }

    override fun cleanupOnFailure() {
        if (file.exists()) {
            file.delete()
        }
    }

    override suspend fun replaceWithFile(file: File): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                file.copyTo(this@FileOutputTarget.file, overwrite = true)
                true
            }.getOrDefault(false)
        }
}

/**
 * 2026-09-10 — bake the FULL preview color pipeline (B/C/S + famous
 * effect + Lumetri grade) into the exported video. The exact matrix the
 * preview renders is handed to the GL color pass — preview == export,
 * for every panel value.
 *
 * Implemented by the shared [GradeRgbMatrix] (also used mutable by the
 * live preview); see its KDoc.
 *
 * 2026-09-19 correction — this KDoc used to claim *"Media3's `RgbMatrix`
 * interface accepts a raw 4×5 row-major matrix (the same layout
 * `android.graphics.ColorMatrix` produces)"*. That is false. Media3
 * requires a 4×4 **column-major** matrix of exactly 16 floats with
 * offsets in 0..1; the conversion lives in [GradeRgbMatrix] and is
 * unit-tested in `GradeRgbMatrixTest`. The wrong assumption made the
 * grade fail at the GL boundary in the export as well as the preview —
 * see `GradeRgbMatrix`'s KDoc for the full mechanism.
 */
