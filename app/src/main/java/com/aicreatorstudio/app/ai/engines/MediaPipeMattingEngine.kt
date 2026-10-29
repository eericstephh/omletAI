package com.aicreatorstudio.app.ai.engines

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import com.aicreatorstudio.app.ai.AiFailure
import com.aicreatorstudio.app.ai.AiLoadOutcome
import com.aicreatorstudio.app.ai.AiModelCatalog
import com.aicreatorstudio.app.ai.AiModelInstaller
import com.aicreatorstudio.app.ai.AiModels
import com.aicreatorstudio.app.ai.AiToolEngine
import com.aicreatorstudio.app.ai.AiToolOptions
import com.aicreatorstudio.app.ai.AiToolResult
import com.aicreatorstudio.app.ai.CatalogEntry
import com.aicreatorstudio.app.ai.MattingSidecar
import com.aicreatorstudio.app.ai.MaskPostProcess
import com.aicreatorstudio.app.ai.MediaPipeNativeLib
import com.aicreatorstudio.app.ai.NativeEngineFailure
import com.aicreatorstudio.app.ai.media.SequentialFrameSampler
import com.aicreatorstudio.app.model.MattingSettings
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter.ImageSegmenterOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * **Background Remove** - on-device person matting backed by the
 * MediaPipe Tasks Vision `ImageSegmenter` (Selfie Multiclass 256x256
 * TFLite).
 *
 * ## Model lifecycle (centralized, no placeholders)
 *
 * The model is [AiModelCatalog.SELFIE_MULTICLASS]: the URL, SHA-256
 * and size are pinned in `models.lock.json`, installed through
 * [AiModelInstaller] (bundled-asset-first so the first run works
 * offline, network repair when the asset is missing/corrupt), and
 * loaded from the SHA-verified file under
 * `filesDir/models/selfie_multiclass/`. A checksum mismatch
 * surfaces as a user-visible failure — never a silent fallback,
 * never a fake READY.
 *
 * ## What it does
 *
 * For every selected clip the engine:
 *
 *  1. Ensures the model is installed (bundle copy + verify on first run).
 *  2. Samples frames across the clip via [MediaMetadataRetriever].
 *  3. Runs each frame through the segmenter; class index 0 is the
 *     background, which we invert to get the person alpha mask.
 *  4. Applies exponential-moving-average temporal smoothing across
 *     consecutive masks so edges don't flicker or jitter between
 *     sampled frames.
 *  5. 2026-10-25 — the mask PNG is written RAW; threshold soft-knee
 *     and the user's feather/expansion/reverse knobs are applied by
 *     the export pipeline (`MaskBitmaps`), so they are all live.
 *  6. 2026-10-17 — streams each mask PNG (at the bounded
 *     [ANALYSIS_MAX_SIDE] resolution) into the project sidecar as it
 *     is produced, then finalizes the manifest before reporting
 *     success. Routing id travels on
 *     [com.aicreatorstudio.app.ai.AiToolOptions.mattingProjectId]; the
 *     export pre-renderer consumes the sidecar without re-running the
 *     model (and rescales each PNG to the frame it composites).
 *
 * Background modes (Original / Blur / Color / Image / Video /
 * Transparent) are carried on [MattingSettings] and honored by the
 * preview + export composite; the engine always produces the raw
 * alpha so every mode shares one analysis pass.
 *
 * ## Threading
 *
 * The engine is `@Singleton` and lazy-loads the segmenter on the
 * first call to [process]. Frame sampling and inference run on
 * [Dispatchers.IO] + [Dispatchers.Default]. [release] closes the
 * segmenter explicitly.
 */
@Singleton
class MediaPipeMattingEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelInstaller: AiModelInstaller
) : AiToolEngine {

    override val id: String = "matting"

    override val displayName: String = "Background Remove"

    /**
     * Live status text. Re-evaluated every access - the panel binds
     * to this property via Compose, so changes show up immediately.
     *
     * 2026-10-20 — the prefix no longer says "Model integrity check
     * failed". The model file was verified byte-for-byte (the bundled
     * asset's SHA-256 matches the lockfile), so an integrity problem
     * was never the cause; the failure was MediaPipe failing to
     * initialise. The wording now says what actually happened, and
     * the technical detail follows it in brackets so the diagnostic
     * report carries the real cause.
     */
    override val modelStatusText: String
        get() = when {
            lastLoadError != null -> buildString {
                append("موتور تشخیص سوژه اجرا نشد: ")
                append(lastLoadError)
                lastLoadErrorDetail?.let { append(" (").append(it).append(')') }
            }
            !modelInstaller.isInstalled(MATTING_SPEC) ->
                "Model: MediaPipe Selfie Multiclass (not installed)"
            else -> "Model: MediaPipe Selfie Multiclass ready"
        }

    override val isAvailable: Boolean
        get() = modelInstaller.isInstalled(MATTING_SPEC) && lastLoadError == null

    /**
     * Set when the segmenter fails to load. Cleared on the next
     * successful [ensureSegmenterLocked] call.
     */
    @Volatile
    private var lastLoadError: String? = null

    /**
     * 2026-10-20 — the TECHNICAL cause, kept next to the classified
     * sentence.
     *
     * The classification is a one-liner written for the user
     * ("close background apps and try again"); on its own it told the
     * user nothing true and left the device report with no technical
     * content. This field is the raw exception, and it travels into
     * the diagnostic report and the status line.
     */
    @Volatile
    private var lastLoadErrorDetail: String? = null

    /**
     * Lazily loaded segmenter. Guarded by [segmenterLock] so a
     * concurrent [process] and [release] cannot race. Cleared by
     * [release] and on a load error.
     */
    @Volatile
    private var segmenter: ImageSegmenter? = null
    private val segmenterLock = Any()

    /**
     * 2026-10-20 — the temporal-EMA state is now PER RUN, not a field
     * on this `@Singleton`.
     *
     * The previous frame's mask used to live in a `@Volatile` field on
     * the engine, so two overlapping runs (the user starts a second
     * clip while the first is still going) interleaved their EMA
     * state: clip B's mask was smoothed against clip A's previous
     * frame. `release()` also nulled the field while a run was in
     * flight.
     *
     * The state now travels with the run — see [MattingEmaState] —
     * so a run can only ever see its own previous frame.
     */
    private class MattingEmaState {
        /** Previous frame's mask (model-resolution floats). */
        var prev: FloatArray? = null
        var width: Int = 0
        var height: Int = 0

        fun reset() {
            prev = null
            width = 0
            height = 0
        }
    }

    override suspend fun ensureModelReady(onProgress: (Float) -> Unit): Result<Unit> {
        // installWithManifest: bundled-asset-first (offline), then
        // network repair, SHA-verify, atomic install. Progress
        // already spans 0..1 across download/verify/install.
        val outcome: AiLoadOutcome = modelInstaller.installWithManifest(MATTING_SPEC, onProgress = onProgress)
        if (outcome.failure != null) {
            val msg = outcome.detail ?: outcome.failure.persianMessage()
            lastLoadError = msg
            return Result.failure(Exception(msg))
        }
        // Force a segmenter build on success so the next [process]
        // call doesn't pay the load cost, and so a corrupt file
        // surfaces NOW (LOADING phase) instead of mid-render.
        ensureSegmenterLocked()
        return if (lastLoadError == null) Result.success(Unit)
        else Result.failure(Exception(lastLoadError))
    }

    override suspend fun process(
        clipId: String,
        videoUri: Uri,
        options: AiToolOptions,
        onProgress: (Float) -> Unit
    ): Result<AiToolResult> = withContext(Dispatchers.IO) {
        // 2026-10-17 — sidecar routing. The engine (not the host)
        // streams each mask PNG to disk at its analysis resolution as
        // soon as it is produced: bounded memory (one mask alive at a
        // time instead of up to MAX_FRAMES full-resolution bitmaps in
        // the result) and the success state is honest by construction
        // (the manifest is finalized BEFORE Result.success).
        // Missing routing info is a loud refusal, never a fake result.
        // NOTE: the pass itself (which wipes the previous sidecar)
        // begins only AFTER the segmenter and metadata are verified,
        // so a failed run can never destroy an existing applied
        // result.
        val sidecarProjectId = options.mattingProjectId
            ?: return@withContext Result.failure(
                Exception("Matting run without a sidecar project id — refusing to fake a result")
            )
        var sidecarDir: File? = null
        val writtenFrames = ArrayList<MattingSidecar.WrittenFrame>(MAX_FRAMES)
        val timing = PerfTimings()
        try {
            onProgress(0f)
            coroutineContext[Job]?.ensureActive()

            // Fresh temporal state per run.
            //
            // 2026-10-20 — the state belongs to the RUN, not to the
            // engine. It used to be a `@Volatile` field on this
            // `@Singleton`, so two overlapping runs interleaved their
            // EMA history and clip B's mask was smoothed against clip
            // A's previous frame.
            val ema = MattingEmaState()

            // Step 1 - prepare the segmenter. Refuse to run if the
            // model isn't ready (shows Download instead of faking it).
            // The singleton caches the segmenter across runs — Apply
            // does NOT re-initialize the model.
            val seg = ensureSegmenterLocked()
                ?: return@withContext Result.failure(
                    Exception(modelStatusText)
                )

            // Step 2 - probe source video size and duration.
            val retriever = MediaMetadataRetriever()
            val (durationMs, sourceW, sourceH) = try {
                retriever.setDataSource(context, videoUri)
                val dur = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: 0L
                val w = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                )?.toIntOrNull() ?: 0
                val h = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                )?.toIntOrNull() ?: 0
                Triple(dur, w, h)
            } finally {
                runCatching { retriever.release() }
            }
            if (durationMs <= 0L || sourceW <= 0 || sourceH <= 0) {
                return@withContext Result.failure(
                    Exception("Cannot read video metadata for matting")
                )
            }
            coroutineContext[Job]?.ensureActive()
            onProgress(0.05f)

            // Step 2.5 — now that the run is definitely viable, start
            // the sidecar pass.
            //
            // 2026-10-20 — the pass is ATOMIC: the frames are written
            // into a staging directory and swapped in by
            // [MattingSidecar.finalizeManifest]. The previously applied
            // masks stay on disk (and valid) for the whole run, so a
            // crash or an OOM can no longer destroy the user's
            // Background Remove result.
            sidecarDir = MattingSidecar.beginWritePass(context, sidecarProjectId, clipId)

            // Step 3 - sample frames. ~10 fps capped at MAX_FRAMES so
            // long videos stretch the interval instead of exploding
            // the sidecar. Temporal EMA (below) bridges the gaps.
            //
            // 2026-10-19 FIX — sampling used to go through
            // `MediaMetadataRetriever.getFrameAtTime(..., OPTION_CLOSEST_SYNC)`,
            // which only ever returns a **keyframe**. Inside one GOP every
            // requested timestamp decoded to the same picture, so a 10 fps
            // pass over a 2 s GOP produced 20 identical masks *labelled*
            // with timestamps that did not match the picture — masks lagged
            // the frame by up to half a GOP and the cutout visibly slid off
            // the person. [SequentialFrameSampler] decodes the stream once
            // and reports each picture's real PTS (rebased to clip-local),
            // so every mask now belongs to the frame it was computed from.
            // The retriever path is kept strictly as a fallback for
            // containers the MediaCodec path cannot open.
            // 2026-10-23 — the fps floor the comment above always claimed.
            // Without it `durationMs / MAX_FRAMES` produced 59 ms for a
            // 36 s clip (≈17 fps), so this pass ran 600 inferences where
            // 359 carry the same information: measured on a Xiaomi
            // 2107113SG, one frame costs ≈0.32 s of MediaPipe inference
            // plus ≈0.33 s of retriever decoding, and the 16 fps pass
            // took 7 minutes for a 36 s clip. Masks are temporally
            // smoothed and interpolated by timestamp, so 10 fps loses no
            // visible detail and cuts the run by ~40%.
            val intervalMs = (durationMs / MAX_FRAMES)
                .coerceAtLeast(MIN_SAMPLE_INTERVAL_MS)
            val sampleCount = (durationMs / intervalMs).toInt().coerceAtLeast(1)
                .coerceAtMost(MAX_FRAMES)

            var sampled = 0
            // 2026-10-20 — count per-frame inference failures so a
            // pass that failed on most frames is reported as a
            // failure instead of silently writing a broken sidecar.
            var frameFailures = 0
            val ownerJob = coroutineContext[Job]

            suspend fun consume(bmp: Bitmap, timeMs: Long) {
                val alpha = withContext(Dispatchers.Default) {
                    val tInfer = System.nanoTime()
                    // 2026-10-20 — the EMA state is this RUN's, passed
                    // in explicitly, so a concurrent run on another
                    // clip cannot interleave with it.
                    val mask = segmentForFrame(seg, bmp, options, ema)
                    timing.inferMs += (System.nanoTime() - tInfer) / 1_000_000L
                    mask
                }
                val tWrite = System.nanoTime()
                // The sidecar key is the picture's own PTS, never the
                // requested index: the export pre-renderer picks the
                // nearest mask by timestamp.
                writtenFrames += MattingSidecar.writeFramePng(
                    requireNotNull(sidecarDir), timeMs, alpha
                )
                timing.writeMs += (System.nanoTime() - tWrite) / 1_000_000L
                alpha.recycle()
            }

            val sampledSequentially = SequentialFrameSampler.sample(
                context = context,
                uri = videoUri,
                intervalMs = intervalMs,
                maxFrames = sampleCount,
                // The segmenter ingests 256x256; keeping the working frame
                // near ANALYSIS_MAX_SIDE bounds the YUV->ARGB conversion
                // without touching the model's effective input.
                maxSide = ANALYSIS_MAX_SIDE,
                isCancelled = { ownerJob?.isActive == false }
            ) { frame ->
                // The sampler hands ownership of the bitmap to us.
                //
                // 2026-10-20 — `runCatching` swallowed
                // CancellationException (a Throwable), so a cancelled
                // matting run kept consuming frames. It also hid
                // genuine inference failures
                // ("Segmenter returned no category mask"), so a pass
                // that failed on most frames still wrote a sidecar and
                // reported SUCCESS.
                try {
                    consume(frame.bitmap, frame.timeMs)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "matting frame failed at ${frame.timeMs}ms", t)
                    frameFailures++
                }
                frame.bitmap.recycle()
                sampled++
                onProgress(
                    0.05f + 0.85f * (sampled.toFloat() / sampleCount)
                )
            }

            if (sampledSequentially == 0) {
                // Fallback: the legacy retriever path. Frame-accuracy is
                // worse here (keyframe-only), which is exactly why it is
                // no longer the default.
                val retriever2 = MediaMetadataRetriever()
                try {
                    retriever2.setDataSource(context, videoUri)
                    for (i in 0 until sampleCount) {
                        coroutineContext[Job]?.ensureActive()
                        val ts = (i.toLong() * intervalMs * 1000L).coerceAtLeast(0L)
                        val bmp = retriever2.getFrameAtTime(
                            ts,
                            MediaMetadataRetriever.OPTION_CLOSEST
                        )
                        if (bmp != null) {
                            consume(bmp, i * intervalMs)
                            bmp.recycle()
                        }
                        onProgress(0.05f + 0.85f * ((i + 1).toFloat() / sampleCount))
                    }
                } finally {
                    runCatching { retriever2.release() }
                }
            }

            if (writtenFrames.isEmpty()) {
                return@withContext Result.failure(
                    Exception("No frames could be sampled from the clip")
                )
            }
            // 2026-10-20 — a pass where the segmenter failed on most
            // frames must NOT be reported as success: the sidecar would
            // be full of blank masks and the export would composite
            // garbage. The old code swallowed the per-frame errors
            // entirely, so this state was invisible.
            if (sampled > 0 && frameFailures * 2 > sampled) {
                return@withContext Result.failure(
                    Exception(
                        "تشخیص سوژه در $frameFailures از $sampled فریم ناموفق بود — " +
                            "لطفاً دوباره تلاش کنید."
                    )
                )
            }
            onProgress(0.95f)

            // Step 4 - finalize the sidecar, THEN report success. The
            // threshold travels on the settings; the export
            // pre-renderer rescales each PNG to the frame it composites
            // and applies the soft knee per frame.
            val threshold = options.strength.coerceIn(0.05f, 0.95f).takeIf { it > 0f }
                ?: 0.5f
            val algorithm = MattingSettings.ALGORITHM_MEDIAPIPE_SELFIE
            val modelSha = MattingSidecar.modelSha256OrNull(context, MATTING_SPEC)
            try {
                MattingSidecar.finalizeManifest(
                    dir = requireNotNull(sidecarDir),
                    algorithm = algorithm,
                    modelSha256 = modelSha,
                    sourceWidth = sourceW,
                    sourceHeight = sourceH,
                    intervalMs = intervalMs,
                    frames = writtenFrames
                )
            } catch (t: Throwable) {
                return@withContext Result.failure(
                    Exception("Could not write matting sidecar: ${t.message ?: t::class.java.simpleName}", t)
                )
            }
            Log.i(
                TAG,
                "matting pass: frames=${writtenFrames.size} decode=${timing.decodeMs}ms " +
                    "infer=${timing.inferMs}ms write=${timing.writeMs}ms " +
                    "src=${sourceW}x$sourceH interval=$intervalMs"
            )
            val result = AiToolResult(
                matting = MattingSettings(
                    enabled = options.commitToClip,
                    threshold = threshold,
                    // A fresh run replaces the sidecar with RAW masks,
                    // so the composition-time feather must be re-warmed
                    // to its default too (old projects that already had
                    // a feathered sidecar migrate to 0 in the JSON
                    // reader — raw masks would come out hard-edged).
                    // expansion / reverse stay user-owned and are never
                    // written here.
                    feather = MaskPostProcess.DEFAULT_FEATHER,
                    replacementBackgroundUri = options.replacementBackgroundUri,
                    algorithm = algorithm
                ),
                mattingIntervalMs = intervalMs,
                mattingSourceWidth = sourceW,
                mattingSourceHeight = sourceH,
                summary = "پس‌زمینه در ${writtenFrames.size} فریم حذف شد " +
                    "(${sourceW}×${sourceH}، حدود ${(1000L / intervalMs.coerceAtLeast(1L))} فریم در ثانیه)"
            )
            onProgress(1f)
            Result.success(result)
        } catch (e: CancellationException) {
            // A cancelled pass leaves PNGs with no manifest — delete
            // them so storage does not fill with unusable partials and
            // the next pass starts clean (it also cleans, this just
            // makes the cancel cheap to trace).
            if (sidecarDir != null) {
                runCatching { MattingSidecar.delete(context, sidecarProjectId, clipId) }
            }
            Result.failure(e)
        } catch (e: Exception) {
            // Same partial-sidecar hygiene on inference/write failures:
            // without a manifest the PNGs are unusable and would make
            // the next export fail confusingly until a fresh pass runs.
            if (sidecarDir != null) {
                runCatching { MattingSidecar.delete(context, sidecarProjectId, clipId) }
            }
            Result.failure(e)
        }
    }

    override fun release() {
        synchronized(segmenterLock) {
            runCatching { segmenter?.close() }
            segmenter = null
        }
        // 2026-10-20 — there is no engine-level temporal state to
        // clear any more: the EMA buffer belongs to a single run (see
        // [MattingEmaState]) and is dropped when that run ends.
    }

    /**
     * Per-stage wall-clock accumulators for one pass. Logged once at
     * the end ([Log.i]) so a slow Apply can be diagnosed as decode vs
     * inference vs sidecar-write without a profiler attached.
     */
    private class PerfTimings {
        var decodeMs: Long = 0L
        var inferMs: Long = 0L
        var writeMs: Long = 0L
    }

    // ---- internals ----

    /**
     * Lazily build (or rebuild) the [ImageSegmenter]. Returns the
     * live instance, or `null` if the model is missing or failed to
     * load (with [lastLoadError] set to the real cause).
     */
    private fun ensureSegmenterLocked(): ImageSegmenter? {
        synchronized(segmenterLock) {
            segmenter?.let { return it }
            if (!modelInstaller.isInstalled(MATTING_SPEC)) {
                return null
            }
            // Native probe BEFORE touching any MediaPipe class: this
            // APK packages `libmediapipe_tasks_jni.so` for arm64-v8a
            // only (see NativeEngineFailure.PACKAGED_ABIS), so on an
            // emulator x86_64 build — or any device without a
            // matching ABI — the static initializer of ImageSegmenter
            // throws UnsatisfiedLinkError, an Error rather than an
            // Exception. Probing the library ourselves turns that
            // into a classified failure instead of a hard crash.
            //
            // 2026-10-15 — the probe lives in
            // [com.aicreatorstudio.app.ai.MediaPipeNativeLib] and is
            // a candidate list. The name this engine used to probe
            // (`mediapipe_tasks_vision_jni`) is not in the APK at
            // all since the resolver moved to tasks-vision 0.10.35
            // (whose AAR ships no .so; tasks-core ships
            // `mediapipe_tasks_jni`), so the old probe reported
            // LIBRARY_ABI_MISSING on fully supported hardware.
            if (!nativeLibAvailable()) {
                // 2026-10-13 — report the *classified* cause. The old
                // code always emitted the generic "your CPU is not
                // supported" sentence, but this branch is reached far
                // more often because the APK simply has no library for
                // the device ABI (the app ships arm64-v8a only).
                val failure = NativeEngineFailure.classify(
                    error = MediaPipeNativeLib.lastLoadError,
                    primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
                    libraryAvailable = false
                )
                lastLoadError = failure.persianMessage()
                return null
            }
            return try {
                val modelFile = AiModels.modelFile(context, MATTING_SPEC)
                // 2026-10-20 — the model file's existence is checked
                // SEPARATELY. "The file is not there" and "the graph
                // would not initialise" are different faults, and
                // MediaPipe reports the second as an opaque init
                // error, so the app used to blame the device for a
                // missing file.
                if (!modelFile.exists() || modelFile.length() <= 0L) {
                    lastLoadError = "فایل مدل روی دستگاه نصب نیست: ${MATTING_SPEC.fileName}"
                    lastLoadErrorDetail = "model file missing: ${modelFile.absolutePath}"
                    Log.w(TAG, "matting model file missing: ${modelFile.absolutePath}")
                    return null
                }
                val modelPath = modelFile.absolutePath
                // 2026-10-20 — THE DELEGATE IS EXPLICIT, WITH A CPU
                // FALLBACK.
                //
                // The engine used to leave the delegate unset, so
                // MediaPipe picked its own default. On a device whose
                // OpenGL ES stack cannot give MediaPipe a working
                // context (a known problem on some MIUI/Redmi
                // hardware), `createFromOptions` throws and the whole
                // tool is dead on that phone — with the report saying
                // only "close background apps and try again", which is
                // not what happened.
                //
                // The CPU delegate (XNNPACK) has no such dependency.
                // The engine now tries the default first (fast when it
                // works) and falls back to the CPU delegate when the
                // default cannot initialise.
                val primary = runCatching {
                    ImageSegmenter.createFromOptions(
                        context,
                        buildSegmenterOptions(modelPath, delegate = null)
                    )
                }
                val segmenterOrNull = primary.getOrNull() ?: run {
                    val primaryError = primary.exceptionOrNull()
                    Log.w(TAG, "MediaPipe default delegate failed; retrying on CPU", primaryError)
                    val cpu = runCatching {
                        ImageSegmenter.createFromOptions(
                            context,
                            buildSegmenterOptions(modelPath, delegate = Delegate.CPU)
                        )
                    }
                    if (cpu.isFailure) {
                        // Report the ORIGINAL failure — it is the
                        // interesting one — with the CPU attempt as
                        // extra context.
                        primaryError?.let { throw it }
                        throw cpu.exceptionOrNull()
                            ?: IllegalStateException("MediaPipe segmenter could not be created")
                    }
                    cpu.getOrThrow()
                }
                segmenter = segmenterOrNull
                lastLoadError = null
                lastLoadErrorDetail = null
                segmenterOrNull
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Throwable on purpose: a failed MediaPipe static-init
                // surfaces as UnsatisfiedLinkError / NoClassDefFoundError
                // (java.lang.Error subtypes), which catch(Exception)
                // silently lets escape and crash the app.
                //
                // 2026-10-13 — classify rather than blindly reusing the
                // raw throwable message. `t.message` on an
                // UnsatisfiedLinkError is a dlopen string, which the
                // card rendered verbatim to the user.
                //
                // 2026-10-20 — AND KEEP THE TECHNICAL DETAIL.
                //
                // The classification only produces a one-line
                // sentence ("close background apps and try again"),
                // which is NOT what was wrong and left the device
                // report with no technical content at all. The original
                // exception (class + message) is now kept alongside
                // it and travels into the diagnostic report, so the
                // next report says exactly which MediaPipe call
                // failed and why.
                val failure = NativeEngineFailure.classify(
                    error = t,
                    primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
                    libraryAvailable = nativeLibAvailable()
                )
                lastLoadError = failure.persianMessage()
                lastLoadErrorDetail = describeFailure(t)
                Log.w(TAG, "MediaPipe segmenter creation failed: ${describeFailure(t)}", t)
                null
            }
        }
    }

    /**
     * The segmenter options, with the delegate left to MediaPipe when
     * [delegate] is null.
     */
    private fun buildSegmenterOptions(
        modelPath: String,
        delegate: Delegate?
    ): ImageSegmenterOptions {
        val base = BaseOptions.builder().setModelAssetPath(modelPath)
        if (delegate != null) base.setDelegate(delegate)
        return ImageSegmenterOptions.builder()
            .setBaseOptions(base.build())
            .setRunningMode(RunningMode.IMAGE)
            // Category mask (1 byte/px class index) is 4x smaller +
            // faster than confidence masks and is all
            // foreground/background separation needs.
            .setOutputCategoryMask(true)
            .setOutputConfidenceMasks(false)
            .build()
    }

    /**
     * A short, technical description of a failure: the exception class
     * plus its message, bounded so a pathological message cannot bloat
     * the status line or the log.
     */
    private fun describeFailure(t: Throwable): String {
        val name = t.javaClass.simpleName.ifBlank { t.javaClass.name }
        val msg = t.message?.take(200)?.trim().orEmpty()
        val cause = t.cause?.let {
            val cname = it.javaClass.simpleName.ifBlank { it.javaClass.name }
            val cmsg = it.message?.take(120)?.trim().orEmpty()
            if (cmsg.isBlank()) cname else "$cname: $cmsg"
        }
        return buildString {
            append(name)
            if (msg.isNotBlank()) append(": ").append(msg)
            if (cause != null) append(" <- ").append(cause)
        }
    }

    /**
     * True when the MediaPipe JNI library is present for this
     * device's ABI. Shared process-wide probe — see
     * [com.aicreatorstudio.app.ai.MediaPipeNativeLib] for why this is
     * a candidate list rather than one fixed library name.
     */
    private fun nativeLibAvailable(): Boolean = MediaPipeNativeLib.isAvailable()

    /**
     * Run the segmenter on one bitmap and return a raw ARGB alpha
     * bitmap **at the analysis resolution** — NOT upscaled to
     * the source size any more (2026-10-17: the sidecar PNG carries
     * the mask resolution and the export pre-renderer performs the
     * single resample to the frame it composites).
     *
     * The input is uniformly scaled down to at most
     * [ANALYSIS_MAX_SIDE] on the longer side before inference. The
     * selfie-multiclass model itself ingests 256×256, so this only
     * bounds the mask/EMA working size — never the model's
     * effective input — while cutting the decode→tensor conversion,
     * per-frame float arrays, and PNG compression cost proportionally.
     *
     * The selfie-multiclass category mask holds per-pixel class
     * indices (0 = background, 1..5 = person-part classes: hair,
     * body-skin, face-skin, clothes, others). Nonzero is treated as
     * foreground so the whole silhouette stays. The raw mask is
     * temporally smoothed with an EMA against the previous frame's
     * mask ([TEMPORAL_ALPHA]) to kill edge flicker. 2026-10-25: the
     * feather/expansion/reverse post-process moved out of this
     * function entirely — it now runs at composition time.
     */
    private fun segmentForFrame(
        seg: ImageSegmenter,
        frame: Bitmap,
        options: AiToolOptions,
        ema: MattingEmaState
    ): Bitmap {
        // Bound the working resolution once per frame: uniform bilinear
        // downscale. The model ingests 256×256 internally, so this only
        // bounds the EMA/PNG stages. Constant for the whole run
        // (frames come back identically sized per source), so temporal
        // state stays consistent.
        //
        // 2026-10-20 — OWNERSHIP FIX. The frame belongs to the CALLER
        // (the sampler hands it over and recycles it after `consume`).
        // The old code did `val copied = if (frame.config == ARGB_8888)
        // frame else frame.copy(...)` and then recycled `copied` after
        // scaling it — which recycled the CALLER'S bitmap whenever the
        // source frame was already ARGB_8888 and larger than the
        // analysis bound (the fallback retriever path is not
        // pre-bounded, so this branch WAS reachable).
        val alreadyRgb = frame.config == Bitmap.Config.ARGB_8888
        val owned = !alreadyRgb
        val copied = if (alreadyRgb) frame else frame.copy(Bitmap.Config.ARGB_8888, false)
        val longest = maxOf(copied.width, copied.height)
        val analysis = if (longest <= ANALYSIS_MAX_SIDE) {
            copied
        } else {
            val ratio = ANALYSIS_MAX_SIDE.toFloat() / longest
            val scaled = Bitmap.createScaledBitmap(
                copied,
                (copied.width * ratio).roundToInt().coerceAtLeast(1),
                (copied.height * ratio).roundToInt().coerceAtLeast(1),
                true
            )
            // Only recycle what WE allocated.
            if (scaled !== copied && owned) copied.recycle()
            scaled
        }
        val mpImage = BitmapImageBuilder(analysis).build()
        val result = seg.segment(mpImage)
        val optMask = result.categoryMask()
        if (!optMask.isPresent) {
            // Fail loudly rather than silently writing a fully
            // transparent mask (which would remove the person while
            // still reporting success). `ImageSegmenter.segment`
            // always populates the category mask when requested, so
            // reaching this point means MediaPipe returned an
            // unexpected result — treat it as an inference failure.
            throw IllegalStateException(
                "Segmenter returned no category mask (frame ${analysis.width}x${analysis.height})"
            )
        }
        val mask = optMask.get()
        val maskW = mask.width
        val maskH = mask.height
        val byteBuffer = ByteBufferExtractor.extract(mask)
        val maskBytes = ByteArray(maskW * maskH)
        byteBuffer.rewind()
        byteBuffer.get(maskBytes)

        // Class -> foreground probability, then temporal EMA.
        val fg = FloatArray(maskW * maskH)
        for (i in 0 until maskW * maskH) {
            val cls = maskBytes[i].toInt() and 0xFF
            fg[i] = if (cls == 0) 0f else 1f
        }
        val prev = ema.prev
        if (prev != null && prev.size == fg.size && ema.width == maskW && ema.height == maskH) {
            for (i in fg.indices) {
                fg[i] = TEMPORAL_ALPHA * fg[i] + (1f - TEMPORAL_ALPHA) * prev[i]
            }
        }
        ema.prev = fg.copyOf()
        ema.width = maskW
        ema.height = maskH

        // Threshold + soft knee so hair/soft edges survive as
        // partial alpha instead of a hard binary cut.
        val alphaPixels = IntArray(maskW * maskH)
        for (i in 0 until maskW * maskH) {
            val a = ((fg[i] * 255f).roundToInt()).coerceIn(0, 255)
            alphaPixels[i] = (a shl 24) or 0x00FFFFFF
        }
        val maskBitmap = Bitmap.createBitmap(maskW, maskH, Bitmap.Config.ARGB_8888)
        maskBitmap.setPixels(alphaPixels, 0, maskW, 0, 0, maskW, maskH)
        // 2026-10-25 — the sidecar carries the RAW model mask.
        // Feather/expansion/reverse are user-owned composition-time
        // knobs applied by the export pipeline (`MaskBitmaps`), which
        // is what makes them live: moving a slider no longer requires
        // re-running this pass. (The old code feathered here and baked
        // it into the PNGs.)
        return maskBitmap
    }

    companion object {
        private const val TAG = "MediaPipeMattingEngine"

        /**
         * Cap on the analysis side length. The selfie-multiclass model
         * ingests 256×256 regardless, so this only bounds the EMA,
         * feather, and PNG-write working surfaces (a 4K frame would
         * otherwise allocate ~33 MB of mask + temp buffers per sample).
         * The sidecar PNG keeps this resolution; the export pre-render
         * bilinearly rescales each PNG to the frame it composites —
         * one resample, the same single resample the old full-
         * resolution path performed at engine time.
         */
        const val ANALYSIS_MAX_SIDE: Int = 720

        /**
         * Number of frames sampled per clip. 10 fps over a 60 s
         * clip is 600 frames; we cap at 600 to keep the sidecar
         * bounded. For longer clips the interval stretches out
         * proportionally (temporal EMA bridges the gaps).
         */
        const val MAX_FRAMES: Int = 600

        /**
         * 2026-10-23 — slowest acceptable sampling step (10 fps).
         *
         * The pass is O(frames) in MediaPipe inference, so the step has
         * to have a floor: `duration / MAX_FRAMES` shrinks without bound
         * on short clips and produced ~17 fps here. 10 fps matching the
         * documented design and the retriever fallback's own cadence.
         */
        const val MIN_SAMPLE_INTERVAL_MS: Long = 100L

        /**
         * Temporal EMA weight for the current frame's mask.
         * 0.65 current / 0.35 history kills single-frame flicker
         * without visible trailing on normal motion.
         */
        const val TEMPORAL_ALPHA: Float = 0.65f

        /**
         * The centralized model spec consumed by [AiModelInstaller].
         * URL + SHA-256 + size are pinned in `models.lock.json`
         * (Google's official MediaPipe bucket); kept as a companion
         * alias so the ViewModel's sidecar writer can reference the
         * exact spec the engine installed.
         */
        val MATTING_SPEC: CatalogEntry = AiModelCatalog.SELFIE_MULTICLASS
    }
}
