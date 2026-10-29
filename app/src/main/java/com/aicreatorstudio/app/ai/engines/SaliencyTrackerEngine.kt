package com.aicreatorstudio.app.ai.engines

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import com.aicreatorstudio.app.ai.AiFailure
import com.aicreatorstudio.app.ai.AiModelCatalog
import com.aicreatorstudio.app.ai.AiModelInstaller
import com.aicreatorstudio.app.ai.AiModels
import com.aicreatorstudio.app.ai.AiToolEngine
import com.aicreatorstudio.app.ai.AiToolOptions
import com.aicreatorstudio.app.ai.AiToolResult
import com.aicreatorstudio.app.ai.MediaPipeNativeLib
import com.aicreatorstudio.app.ai.NativeEngineFailure
import com.aicreatorstudio.app.ai.media.SequentialFrameSampler
import com.aicreatorstudio.app.ai.media.VideoFramePipeline
import com.aicreatorstudio.app.model.AutoFrameSettings
import com.aicreatorstudio.app.model.CropKeyframe
import com.aicreatorstudio.app.model.VideoDimensions
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * **Auto Frame** — real person tracking for reframing.
 *
 * Primary backend is MediaPipe **Pose Landmarker (Lite)**
 * ([AiModelCatalog.POSE_LANDMARKER_LITE], pinned in
 * `models.lock.json`, installed via [AiModelInstaller]
 * bundle-first so it works offline). Per sampled frame the
 * landmarker returns 33 body landmarks for the most prominent
 * person (stable primary-subject selection, including multi-person
 * scenes); the crop target blends the face center (nose + eyes)
 * with the torso center (shoulders) so 9:16 keeps the head framed
 * instead of center-cropping the video.
 *
 * Fallback backend is Sobel-saliency + 1-Euro filtering (the v1
 * algorithm), used only for frames where no pose is detected
 * (temporary detection loss, no person in frame) and blended
 * through the same smoothing filter so the camera never jumps.
 *
 * Pipeline per clip:
 *
 * ```
 * Video -> pose per 250 ms sample -> subject point trace
 *   -> 1-Euro smoothing -> crop windows for the target aspect
 *   -> keyframes every 200 ms -> export pre-renderer
 * ```
 *
 * Supported conversions: landscape <-> portrait, either -> square,
 * plus 4:5. FPS/duration/audio are untouched (keyframes only drive
 * the crop; the export pre-renderer re-encodes with passthrough
 * audio and source timestamps).
 */
@Singleton
class SaliencyTrackerEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelInstaller: AiModelInstaller
) : AiToolEngine {

    override val id: String = "autoframe"

    override val displayName: String = "Auto Frame"

    override val modelStatusText: String
        get() = when {
            // 2026-10-20 — the prefix no longer says "Model integrity
            // check failed": the model file was verified byte-for-byte
            // (the bundled asset's SHA-256 matches the lockfile), so an
            // integrity problem was never the cause — the tracker
            // failed to initialise. The technical detail follows in
            // brackets so the diagnostic report carries the real cause.
            lastLoadError != null -> buildString {
                append("موتور ردیابی اجرا نشد: ")
                append(lastLoadError)
                lastLoadErrorDetail?.let { append(" (").append(it).append(')') }
            }
            !modelInstaller.isInstalled(POSE_SPEC) ->
                "Model: Pose Landmarker (not installed)"
            !modelInstaller.isInstalled(FACE_SPEC) ->
                "Model: Pose Landmarker ready · face fallback missing"
            // 2026-10-20 BUG 3 FIX — a detection error is not a model
            // fault, so it must NOT disable the engine (isAvailable
            // only looks at lastLoadError). It is surfaced here so the
            // last run's failure stays visible on the model card
            // instead of vanishing.
            poseErrorCount > 0 ->
                "Model: Pose + Face detection ready · last run hit $poseErrorCount detection error(s)"
            else -> "Model: Pose + Face detection ready · saliency fallback armed"
        }

    override val isAvailable: Boolean
        get() = modelInstaller.isInstalled(POSE_SPEC) && lastLoadError == null

    @Volatile
    private var lastLoadError: String? = null

    /**
     * 2026-10-20 — the TECHNICAL cause, kept next to the classified
     * sentence so the diagnostic report carries the real reason.
     */
    @Volatile
    private var lastLoadErrorDetail: String? = null

    @Volatile
    private var landmarker: PoseLandmarker? = null
    private val landmarkerLock = Any()

    // ----------------------------------------------------------------
    // 2026-09-06 Auto-Frame fixes
    // ----------------------------------------------------------------

    /**
     * BUG 1 FIX — monotonic-timestamp reset. `PoseLandmarker` runs in
     * `RunningMode.VIDEO`, which REQUIRES strictly increasing
     * timestamps across calls. The engine is a Singleton: on the
     * second run (regenerate / another clip) the sample timestamps
     * restart from 0, every `detectForVideo` call throws
     * "timestamp going backwards", the blanket catch mapped each frame
     * to null, and ALL frames fell into the saliency fallback — the
     * person tracking silently died. We now track the last issued
     * timestamp and re-base the stream whenever the video clock would
     * go backwards.
     */
    /**
     * 2026-10-20 BUG 2 FIX — the monotonic MediaPipe timestamp is an
     * [AtomicLong] now, not a `@Volatile var`.
     *
     * The old code read, compared and wrote the counter in three
     * separate steps:
     *
     * ```
     * var tsUs = timestampMs * 1000L
     * if (tsUs <= lastPoseTimestampUs) { tsUs = lastPoseTimestampUs + 1L }
     * lastPoseTimestampUs = tsUs
     * ```
     *
     * `@Volatile` makes the *access* visible, not the
     * read-modify-write atomic. Two overlapping runs (the engine is a
     * `@Singleton`, so a "regenerate" started while the first run is
     * still finishing is normal) could interleave between the read and
     * the write and hand MediaPipe the SAME timestamp twice — or a
     * smaller one than the previous call. `RunningMode.VIDEO` rejects
     * that ("timestamp going backwards"), the landmarker throws, and
     * the throw was swallowed by the blanket catch — so the whole run
     * silently degraded to the saliency fallback.
     *
     * The atomic update below makes the read-modify-write a single
     * CAS loop, so the counter is correct under concurrency.
     */
    private val lastPoseTimestampUs = AtomicLong(-1L)

    // ----------------------------------------------------------------
    // 2026-10-20 BUG 3 FIX — visible detection failures
    // ----------------------------------------------------------------

    /**
     * The pose/face inference used to swallow EVERY exception and
     * report "no detection". A MediaPipe graph-init failure, a
     * "timestamp going backwards" error or a native error therefore
     * looked exactly like an empty scene: the run reported success,
     * every frame fell back to Sobel saliency, and the user was told
     * "ردیابی بدن: 0٪" with no hint that anything had failed.
     *
     * The failures are counted here instead. The counters are reset at
     * the start of every run and reported in the run's summary, so a
     * degraded run is visible instead of silent.
     *
     * The per-frame resilience is deliberately kept: a single failed
     * frame must not abort the whole run.
     */
    private val detectionErrorLock = Any()

    @Volatile
    private var poseErrorCount: Int = 0

    @Volatile
    private var faceErrorCount: Int = 0

    @Volatile
    private var lastPoseError: String? = null

    @Volatile
    private var lastFaceError: String? = null

    /**
     * Record a failed pose inference. Called from the inference catch
     * block only — never from the "there is no pose in this frame"
     * path, which returns null without an exception.
     */
    private fun recordPoseError(t: Throwable) {
        synchronized(detectionErrorLock) {
            poseErrorCount++
            lastPoseError = describeDetectionError(t)
        }
    }

    /** Record a failed face inference. See [recordPoseError]. */
    private fun recordFaceError(t: Throwable) {
        synchronized(detectionErrorLock) {
            faceErrorCount++
            lastFaceError = describeDetectionError(t)
        }
    }

    /** One-line, human-readable description of a detection failure. */
    private fun describeDetectionError(t: Throwable): String {
        val type = t.javaClass.simpleName.ifEmpty { t.javaClass.name }
        val msg = t.message?.trim().orEmpty()
        return if (msg.isEmpty()) type else "$type: $msg"
    }

    /**
     * BUG 2 FIX — Face fallback for close-ups. When the body is out
     * of frame (close-up / selfie shot) the Pose Landmarker finds no
     * pose and the crop fell back to raw Sobel saliency, which locks
     * onto the brightest edge — often NOT the person. BlazeFace
     * (230 KB) keeps the crop locked onto the face.
     */
    @Volatile
    private var faceDetector: com.google.mediapipe.tasks.vision.facedetector.FaceDetector? = null
    private val faceDetectorLock = Any()

    override suspend fun ensureModelReady(onProgress: (Float) -> Unit): Result<Unit> {
        val outcome = modelInstaller.installWithManifest(POSE_SPEC, onProgress = onProgress)
        if (outcome.failure != null) {
            val msg = outcome.detail ?: outcome.failure.persianMessage()
            lastLoadError = msg
            return Result.failure(Exception(msg))
        }
        // Face fallback model: best-effort. If it is missing (old APK)
        // the engine still works — pose → saliency chain as before.
        runCatching {
            modelInstaller.installWithManifest(FACE_SPEC)
        }
        ensureFaceDetectorLocked()
        ensureLandmarkerLocked()
        return if (lastLoadError == null) Result.success(Unit)
        else Result.failure(Exception(lastLoadError))
    }

    override suspend fun process(
        clipId: String,
        videoUri: Uri,
        options: AiToolOptions,
        onProgress: (Float) -> Unit
    ): Result<AiToolResult> = withContext(Dispatchers.IO) {
        try {
            onProgress(0f)
            val durationMs = probeDurationMs(videoUri)
            if (durationMs <= 0) {
                return@withContext Result.failure(
                    Exception("مدت زمان ویدیو قابل تشخیص نیست")
                )
            }
            val marker = ensureLandmarkerLocked()
                ?: return@withContext Result.failure(Exception(modelStatusText))
            coroutineContext[Job]?.ensureActive()

            // Step 1 — sample frames, track the subject per sample.
            val sampleInterval = SAMPLE_INTERVAL_MS
            val sampleCount = max(1, (durationMs / sampleInterval).toInt())
            var poseHits = 0
            var faceHits = 0
            var fallbackHits = 0
            // BUG 1 FIX: reset the monotonic timestamp stream per run
            // (see lastPoseTimestampUs KDoc).
            lastPoseTimestampUs.set(-1L)
            // 2026-10-20 BUG 3 FIX — the detection-error counters are
            // per run as well, so a previous run's failure is not
            // reported again.
            synchronized(detectionErrorLock) {
                poseErrorCount = 0
                faceErrorCount = 0
                lastPoseError = null
                lastFaceError = null
            }
            val faceDetectorRef = ensureFaceDetectorLocked()

            val samples = ArrayList<Pair<Float, Float>>(sampleCount)
            // ---- Multi-person stable target selection ----
            // All detected poses feed a per-sample scoring
            // function (prominence = size + visibility +
            // temporal continuity with the locked target).
            // Once a target locks on, the tracker follows IT
            // instead of blindly taking poses[0], and only
            // re-evaluates after TARGET_SWITCH_PATIENCE
            // consecutive frames of worse matches.
            var lockedTarget: TargetState? = null
            var lossStreak = 0

            /**
             * One analysis sample. Split out of the decode loop so the
             * frame-accurate sampler and the legacy retriever fallback
             * share exactly the same scoring path.
             */
            suspend fun consumeFrame(bmp: Bitmap, tsMs: Long) {
                val posePoint = withContext(Dispatchers.Default) {
                    poseSubjectPointMulti(
                        marker, bmp, tsMs, lockedTarget
                    ) { newState -> lockedTarget = newState }
                }
                val subjectPoint = posePoint ?: run {
                    // Face fallback — close-ups where the
                    // body is out of frame.
                    val facePoint = if (faceDetectorRef != null) {
                        withContext(Dispatchers.Default) {
                            faceSubjectPoint(faceDetectorRef, bmp)
                        }
                    } else null
                    if (facePoint != null) faceHits++
                    facePoint
                }
                if (subjectPoint != null) {
                    samples.add(subjectPoint)
                    if (posePoint != null) poseHits++
                    lossStreak = 0
                } else {
                    // ---- Tracking loss recovery ----
                    // Forward-fill the last locked position
                    // for up to LOSS_RECOVERY_SAMPLES; keep
                    // counting the streak so a genuinely
                    // empty scene eventually falls back to
                    // saliency instead of tracking a ghost.
                    samples.lastOrNull()?.let { samples.add(it) }
                        ?: samples.add(saliencyCentre(bmp))
                    lossStreak++
                    if (lossStreak > LOSS_RECOVERY_SAMPLES) {
                        samples[samples.lastIndex] = saliencyCentre(bmp)
                        fallbackHits++
                        // Drop the lock so the next detection
                        // re-runs full target selection.
                        lockedTarget = null
                    } else {
                        fallbackHits++
                    }
                }
            }

            // 2026-10-19 FIX — this loop used to call
            // `getFrameAtTime(..., OPTION_CLOSEST_SYNC)`, which returns a
            // **keyframe**: every 250 ms sample inside one GOP analysed the
            // same picture, so the subject trace was a staircase and the
            // 1-Euro filter smoothed a signal that had not changed — the
            // crop jumped once per GOP. It also returned un-rotated frames,
            // so on a rotated (portrait) source the normalized crop centre
            // was computed in the wrong orientation. The sequential sampler
            // reports each picture's real PTS and applies the display
            // rotation; the retriever path remains only as a fallback.
            val ownerJob = coroutineContext[Job]
            var sampledIndex = 0
            val sampledSequentially = SequentialFrameSampler.sample(
                context = context,
                uri = videoUri,
                intervalMs = sampleInterval,
                maxFrames = sampleCount,
                // Pose/face detection runs at 256-512 px internally; a
                // full-resolution ARGB copy per sample is pure overhead.
                maxSide = ANALYSIS_MAX_SIDE,
                isCancelled = { ownerJob?.isActive == false }
            ) { frame ->
                // 2026-10-20 BUG 3 FIX — the frame callback swallowed
                // failures as well; record them so a broken frame
                // shows up in the summary instead of vanishing.
                runCatching { consumeFrame(frame.bitmap, frame.timeMs) }
                    .onFailure { recordPoseError(it) }
                frame.bitmap.recycle()
                sampledIndex++
                onProgress(0.1f + 0.6f * (sampledIndex.toFloat() / sampleCount))
            }

            if (sampledSequentially == 0) {
                // Legacy fallback: worse temporal accuracy (keyframe-only),
                // but it still produces a trace for containers MediaCodec
                // cannot open.
                //
                // 2026-10-20 BUG 1 FIX — `MediaMetadataRetriever` does NOT
                // apply the video's display rotation (the sequential
                // sampler path does — see
                // `SequentialFrameSampler.applyRotation` and
                // `VideoFramePipeline.probe`). The normalized landmark /
                // face coordinates this path produced were therefore
                // relative to a DIFFERENT orientation than the export's
                // frame space, and the crop centre ended up 90° off for
                // every portrait source.
                //
                // The picture is now rotated into the DISPLAY orientation
                // before inference, and the normalization is done against
                // the DISPLAY dimensions (`info.displayWidth` /
                // `info.displayHeight`): after the rotation the bitmap's
                // shape IS the display shape, so the landmarks and the
                // face box live in the same space the export's crop
                // window is computed in.
                //
                // When the metadata cannot be read (probe returns null)
                // there is no rotation to apply and the picture's own
                // dimensions are used, exactly as before.
                val info = VideoFramePipeline.probe(context, videoUri)
                val rotation = info?.rotation ?: 0
                val displayWidth = info?.displayWidth ?: 0
                val displayHeight = info?.displayHeight ?: 0
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, videoUri)
                    for (i in 0 until sampleCount) {
                        coroutineContext[Job]?.ensureActive()
                        val tsMs = i * sampleInterval
                        val raw = retriever.getFrameAtTime(
                            tsMs * 1000L,
                            MediaMetadataRetriever.OPTION_CLOSEST
                        )
                        if (raw != null) {
                            // The rotation decision needs the FULL-SIZE
                            // picture (the shape check compares it with
                            // the display size), so it is taken before
                            // the downscale — that way a 4K source never
                            // needs a second 4K ARGB copy.
                            val rotate = needsDisplayRotation(
                                raw, rotation, displayWidth, displayHeight
                            )
                            var bmp = boundToMaxSide(raw, ANALYSIS_MAX_SIDE)
                            if (rotate) bmp = rotateBitmap(bmp, rotation)
                            consumeFrame(bmp, tsMs)
                            bmp.recycle()
                        } else {
                            samples.add(samples.lastOrNull() ?: Pair(0.5f, 0.5f))
                            fallbackHits++
                        }
                        onProgress(0.1f + 0.6f * (i.toFloat() / sampleCount))
                    }
                } finally {
                    runCatching { retriever.release() }
                }
            }
            val rawTrace = samples
            coroutineContext[Job]?.ensureActive()

            // Step 2 — smooth with 1-Euro filter (kills crop jitter).
            val smoothing = options.strength.coerceIn(0f, 1f).takeIf { it > 0f }
                ?: 0.5f
            val smoothed = OneEuroFilter(smoothing).smooth(rawTrace)

            // 2026-10-20 — an empty trace used to crash with
            // "Cannot coerce value to an empty range": the guard
            // `coerceIn(0, smoothed.size - 1)` becomes
            // `coerceIn(0, -1)` when every sampled frame failed
            // (the per-frame callback swallowed the errors, so the
            // failure was invisible). Report it as a clear failure.
            if (smoothed.isEmpty()) {
                return@withContext Result.failure(
                    Exception("هیچ سوژه‌ای در ویدیو پیدا نشد — ردیابی ممکن نیست.")
                )
            }

            // Step 3 — emit keyframes at KEYFRAME_INTERVAL_MS spacing.
            val keyframes = ArrayList<CropKeyframe>()
            val stepMs = KEYFRAME_INTERVAL_MS
            var t = 0L
            while (t <= durationMs) {
                val idx = (t.toFloat() / sampleInterval).toInt()
                    .coerceIn(0, smoothed.size - 1)
                val (cx, cy) = smoothed[idx]
                keyframes.add(
                    CropKeyframe(
                        timeMs = t,
                        centerX = cx.coerceIn(0f, 1f),
                        centerY = cy.coerceIn(0f, 1f),
                        zoom = 1f
                    )
                )
                t += stepMs
            }
            onProgress(0.95f)

            val posePct = if (poseHits + fallbackHits > 0) {
                (poseHits * 100) / (poseHits + fallbackHits)
            } else 0
            val facePct = if (faceHits + fallbackHits > 0) {
                (faceHits * 100) / (faceHits + fallbackHits)
            } else 0
            // 2026-10-20 BUG 3 FIX — the detection failures are visible
            // now: the summary states how many frames failed and why,
            // instead of silently reporting a saliency-only run.
            val detectionErrorNote = buildString {
                if (poseErrorCount > 0) {
                    append(" ⚠ خطای تشخیص بدن در $poseErrorCount فریم")
                    lastPoseError?.let { append(" ($it)") }
                }
                if (faceErrorCount > 0) {
                    if (isNotEmpty()) append("؛ ")
                    append("⚠ خطای تشخیص چهره در $faceErrorCount فریم")
                    lastFaceError?.let { append(" ($it)") }
                }
            }
            val trackingSummary = when {
                posePct >= 50 -> "ردیابی بدن: $posePct٪"
                facePct > 0 -> "ردیابی چهره: $facePct٪ (بدن خارج از کادر)"
                else -> "fallback تشخیص صحنه (بدن/چهره پیدا نشد)"
            }
            Result.success(
                AiToolResult(
                    autoFrame = AutoFrameSettings(
                        enabled = options.commitToClip,
                        targetAspectWidth = options.targetAspectWidth,
                        targetAspectHeight = options.targetAspectHeight,
                        smoothing = smoothing,
                        algorithm = AutoFrameSettings.ALGORITHM_POSE_LANDMARKER_V1
                    ),
                    cropKeyframes = keyframes,
                    summary = "${keyframes.size} نقطه کلیدی روی ${durationMs / 1000} ثانیه " +
                        "تولید شد ($trackingSummary)" + detectionErrorNote
                )
            ).also { onProgress(1f) }
        } catch (e: CancellationException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun release() {
        synchronized(landmarkerLock) {
            runCatching { landmarker?.close() }
            landmarker = null
        }
        synchronized(faceDetectorLock) {
            runCatching { faceDetector?.close() }
            faceDetector = null
        }
    }

    // ----------------------------------------------------------------
    // Pose backend
    // ----------------------------------------------------------------

    private fun ensureLandmarkerLocked(): PoseLandmarker? {
        synchronized(landmarkerLock) {
            landmarker?.let { return it }
            if (!modelInstaller.isInstalled(POSE_SPEC)) return null
            // Native probe BEFORE touching any MediaPipe class: this
            // APK packages `libmediapipe_tasks_jni.so` for arm64-v8a
            // only (see NativeEngineFailure.PACKAGED_ABIS), so on an
            // emulator x86_64 build — or any device without a
            // matching ABI — the static initializer of PoseLandmarker
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
                // 2026-10-13 — classify instead of emitting the generic
                // "your CPU is unsupported" sentence, which blamed
                // hardware for what is almost always a packaging fault
                // (this APK ships arm64-v8a only).
                val failure = NativeEngineFailure.classify(
                    error = MediaPipeNativeLib.lastLoadError,
                    primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
                    libraryAvailable = false
                )
                lastLoadError = failure.persianMessage()
                return null
            }
            return try {
                val modelPath = AiModels.modelFile(context, POSE_SPEC).absolutePath
                // 2026-10-20 — EXPLICIT DELEGATE WITH A CPU FALLBACK.
                //
                // Same reasoning as the matting engine: MediaPipe's
                // default delegate needs a working OpenGL ES context,
                // and on some devices it cannot get one — the
                // landmarker then fails to initialise and Auto Frame
                // is dead on that phone. The CPU delegate (XNNPACK)
                // has no such dependency.
                val primary = runCatching {
                    PoseLandmarker.createFromOptions(
                        context,
                        buildPoseOptions(modelPath, delegate = null)
                    )
                }
                val markerOrNull = primary.getOrNull() ?: run {
                    val primaryError = primary.exceptionOrNull()
                    Log.w(
                        TAG,
                        "MediaPipe pose landmarker default delegate failed; retrying on CPU",
                        primaryError
                    )
                    val cpu = runCatching {
                        PoseLandmarker.createFromOptions(
                            context,
                            buildPoseOptions(modelPath, delegate = Delegate.CPU)
                        )
                    }
                    if (cpu.isFailure) {
                        primaryError?.let { throw it }
                        throw cpu.exceptionOrNull()
                            ?: IllegalStateException("Pose landmarker could not be created")
                    }
                    cpu.getOrThrow()
                }
                landmarker = markerOrNull
                lastLoadError = null
                lastLoadErrorDetail = null
                markerOrNull
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Throwable on purpose: a failed MediaPipe static-init
                // surfaces as UnsatisfiedLinkError / NoClassDefFoundError
                // (java.lang.Error subtypes), which catch(Exception)
                // silently lets escape and crash the app.
                //
                // 2026-10-13 — classify rather than reusing the raw
                // dlopen message, which the card used to render to the
                // user verbatim.
                //
                // 2026-10-20 — AND KEEP THE TECHNICAL DETAIL, so the
                // diagnostic report says WHICH call failed instead of
                // only "close background apps and try again".
                val failure = NativeEngineFailure.classify(
                    error = t,
                    primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
                    libraryAvailable = nativeLibAvailable()
                )
                lastLoadError = failure.persianMessage()
                lastLoadErrorDetail = describeFailure(t)
                Log.w(TAG, "Pose landmarker creation failed: ${describeFailure(t)}", t)
                null
            }
        }
    }

    /** The landmarker options, with the delegate left to MediaPipe when null. */
    private fun buildPoseOptions(
        modelPath: String,
        delegate: Delegate?
    ): PoseLandmarker.PoseLandmarkerOptions {
        val base = BaseOptions.builder().setModelAssetPath(modelPath)
        if (delegate != null) base.setDelegate(delegate)
        return PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(base.build())
            .setRunningMode(RunningMode.VIDEO)
            // 2026-09-10 multi-person: 2 slots so a second
            // person walking through frame can't hijack the
            // crop silently — the target selector scores both.
            .setNumPoses(NUM_POSES)
            .setMinPoseDetectionConfidence(0.4f)
            .setMinPosePresenceConfidence(0.4f)
            .setMinTrackingConfidence(0.4f)
            .build()
    }

    /**
     * A short, technical description of a failure: the exception
     * class plus its message and its cause, bounded so a pathological
     * message cannot bloat the status line or the log.
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
     * Subject point from pose landmarks, or null on detection loss.
     *
     * Returns normalized (x, y): 65% face center (nose + eye
     * midpoint) + 35% torso center (shoulder midpoint), so portrait
     * crops keep the head in frame.
     *
     * 2026-09-06 FIXES:
     *  - Timestamp rebasing: `RunningMode.VIDEO` demands strictly
     *    increasing timestamps; a Singleton engine re-running on a
     *    new clip/attempt would otherwise throw on EVERY frame and
     *    silently degrade the whole run to saliency fallback.
     *  - Visibility check is now tolerant: some MediaPipe builds
     *    report visibility ≈ 0 for every landmark even when the pose
     *    is solid. We only REJECT when visibility data is present
     *    AND low — missing/zero-everywhere data passes through, the
     *    geometric sanity checks below still guard quality.
     */
    internal fun poseSubjectPoint(
        marker: PoseLandmarker,
        bmp: Bitmap,
        timestampMs: Long
    ): Pair<Float, Float>? {
        return poseSubjectPointMulti(marker, bmp, timestampMs, null) { }
    }

    /**
     * **Multi-person stable target selection.**
     *
     * Runs the landmarker with [NUM_POSES] slots and scores every
     * detected person with a real cost function:
     *
     *   cost = visibility-weighted detection quality
     *        + size prominence (torso pixel span)
     *        + temporal continuity with the currently-locked target
     *
     * The lowest-cost candidate becomes the subject. Once a target
     * is locked (see [TargetState]), continuity dominates, which is
     * what prevents the crop from jumping between people when a
     * second person enters frame or two people cross paths.
     */
    internal fun poseSubjectPointMulti(
        marker: PoseLandmarker,
        bmp: Bitmap,
        timestampMs: Long,
        locked: TargetState?,
        onTargetUpdate: (TargetState) -> Unit
    ): Pair<Float, Float>? {
        return try {
            val mpImage = BitmapImageBuilder(bmp).build()
            // Re-base the stream when the video clock goes backwards
            // (new run on a reused Singleton) or duplicates a tick.
            //
            // 2026-10-20 BUG 2 FIX — the read-modify-write of the
            // counter is atomic now (see lastPoseTimestampUs), so two
            // overlapping runs can no longer hand MediaPipe the same
            // timestamp twice, nor a smaller one than the previous
            // call — which `RunningMode.VIDEO` rejects with
            // "timestamp going backwards".
            val tsUs = lastPoseTimestampUs.updateAndGet { prev ->
                val base = timestampMs * 1000L
                if (base <= prev) prev + 1L else base
            }
            val result = marker.detectForVideo(mpImage, tsUs)
            val poses = result.landmarks()
            if (poses.isEmpty()) return null

            // Score every candidate pose; pick the best.
            var bestCost = Float.MAX_VALUE
            var best: Pair<Float, Float>? = null
            var bestState: TargetState? = null
            for (lm in poses) {
                if (lm.size < 33) continue
                fun px(i: Int): Float = lm[i].x()
                fun py(i: Int): Float = lm[i].y()
                fun vis(i: Int): Float = lm[i].visibility().orElse(0f)
                val anchors = intArrayOf(0, 2, 5, 11, 12)
                val meanVis = anchors.map { vis(it) }.average().toFloat()
                // Reject only when visibility is REAL data AND low.
                if (meanVis.isFinite() && meanVis > 0f && meanVis < MIN_VISIBILITY) continue
                val faceX = (px(0) + px(2) + px(5)) / 3f
                val faceY = (py(0) + py(2) + py(5)) / 3f
                val bodyX = (px(11) + px(12)) / 2f
                val bodyY = (py(11) + py(12)) / 2f
                if (!faceX.isFinite() || !faceY.isFinite() || !bodyX.isFinite() || !bodyY.isFinite()) {
                    continue
                }
                // Geometric sanity: landmarks must live inside the frame.
                if (faceX < -0.2f || faceX > 1.2f || faceY < -0.2f || faceY > 1.2f) continue
                // Subject anchor (face-heavy so portrait crops keep the head).
                val cx = (0.65f * faceX + 0.35f * bodyX).coerceIn(0f, 1f)
                val cy = (0.65f * faceY + 0.35f * bodyY).coerceIn(0f, 1f)
                // Torso span (normalized) as the size-prominence signal.
                val shoulderSpan = abs(px(11) - px(12)).coerceAtLeast(0.01f)
                // Temporal continuity vs the locked target.
                val continuity = locked?.let { lock ->
                    abs(cx - lock.cx) + abs(cy - lock.cy)
                }
                // Cost: LOWER is better.
                val cost = 1f - meanVis.coerceIn(0f, 1f) +
                    (1f - shoulderSpan.coerceIn(0f, 1f)) * 0.5f +
                    (continuity ?: 0.15f)
                if (cost < bestCost) {
                    bestCost = cost
                    best = Pair(cx, cy)
                    bestState = TargetState(
                        cx = cx,
                        cy = cy,
                        shoulderSpan = shoulderSpan,
                        anchorOffsets = floatArrayOf(
                            cx - faceX, cy - faceY, bodyX - cx, bodyY - cy
                        ),
                        lastSeenMs = timestampMs
                    )
                }
            }
            val state = bestState
            if (state != null) onTargetUpdate(state)
            best
        } catch (e: Exception) {
            // 2026-10-20 BUG 3 FIX — this used to be
            // `catch (_: Exception) { null }`, which turned EVERY
            // failure (a MediaPipe graph-init error, a "timestamp going
            // backwards" error, a native error) into "no pose
            // detected". The run then reported success with a 100 %
            // saliency fallback and the user was told "ردیابی بدن: 0٪"
            // with no indication that anything had failed.
            //
            // A frame that simply contains no person still returns
            // null WITHOUT an exception, so only real errors reach
            // this block — they are recorded and surfaced in the run's
            // summary. The per-frame resilience is kept on purpose: one
            // broken frame must not abort the whole run.
            recordPoseError(e)
            null
        }
    }

    /**
     * BUG 2 FIX — face fallback. Center of the largest detected face
     * (normalized 0..1), biased slightly UP so a 9:16 crop keeps the
     * whole head instead of chin-cropping it. Null when the detector
     * is unavailable or finds no face.
     */
    internal fun faceSubjectPoint(
        detector: com.google.mediapipe.tasks.vision.facedetector.FaceDetector,
        bmp: Bitmap
    ): Pair<Float, Float>? {
        return try {
            val mpImage = BitmapImageBuilder(bmp).build()
            val result = detector.detect(mpImage)
            val detections = result.detections()
            if (detections.isEmpty()) return null
            // Largest bounding box = most prominent face.
            val best = detections.maxByOrNull { d ->
                val b = d.boundingBox()
                (b.width().toLong()) * b.height().toLong()
            } ?: return null
            val box = best.boundingBox()
            // 2026-10-20 BUG 1 FIX — the bitmap handed to the detector
            // is already rotated into the DISPLAY orientation (see
            // needsDisplayRotation), so its shape IS the display shape
            // and normalizing the box by it is normalizing by the
            // display dimensions (`info.displayWidth` /
            // `info.displayHeight`).
            val cx = ((box.left + box.width() / 2f) / bmp.width.toFloat()).coerceIn(0f, 1f)
            // Bias the crop anchor toward the upper part of the face
            // box (the eyes) so the head stays framed after cropping.
            val cy = ((box.top + box.height() * 0.4f) / bmp.height.toFloat()).coerceIn(0f, 1f)
            Pair(cx, cy)
        } catch (e: Exception) {
            // 2026-10-20 BUG 3 FIX — same as the pose path: a real
            // failure is recorded instead of being disguised as
            // "no face in this frame".
            recordFaceError(e)
            null
        }
    }

    /**
     * Lazily build (or rebuild) the BlazeFace detector. Returns null
     * when the model is not installed or the native library is
     * absent — the caller then falls straight through to saliency.
     */
    private fun ensureFaceDetectorLocked(): com.google.mediapipe.tasks.vision.facedetector.FaceDetector? {
        synchronized(faceDetectorLock) {
            faceDetector?.let { return it }
            if (!nativeLibAvailable()) return null
            if (!modelInstaller.isInstalled(FACE_SPEC)) return null
            return try {
                val modelPath = AiModels.modelFile(context, FACE_SPEC).absolutePath
                val opts =
                    com.google.mediapipe.tasks.vision.facedetector.FaceDetector.FaceDetectorOptions.builder()
                        .setBaseOptions(
                            BaseOptions.builder().setModelAssetPath(modelPath).build()
                        )
                        .setRunningMode(com.google.mediapipe.tasks.vision.core.RunningMode.IMAGE)
                        .setMinDetectionConfidence(0.35f)
                        .build()
                val d = com.google.mediapipe.tasks.vision.facedetector.FaceDetector
                    .createFromOptions(context, opts)
                faceDetector = d
                d
            } catch (t: Throwable) {
                null
            }
        }
    }

    // ----------------------------------------------------------------
    // 2026-10-20 BUG 1 FIX — display rotation for the retriever path
    // ----------------------------------------------------------------

    /**
     * Whether [src] still has to be rotated into the DISPLAY
     * orientation.
     *
     * `MediaMetadataRetriever` returns the picture in the container's
     * CODED orientation — the display rotation lives in the metadata
     * and the retriever does not apply it. A quarter turn (90° / 270°)
     * swaps the frame's axes, so the picture has to be rotated before
     * it is handed to the landmarker; otherwise the normalized
     * coordinates belong to a different space than the export's crop
     * window and the crop centre ends up 90° off for portrait sources.
     *
     * The one exception is a build whose retriever already applied the
     * rotation: there the decoded picture already HAS the display
     * shape and rotating again would turn the clip upside down. The
     * shape is therefore compared with the metadata-derived display
     * size (`VideoFramePipeline.probe`'s `displayWidth` /
     * `displayHeight`). A square source is never treated as "already
     * rotated" — its shape cannot tell the two apart.
     */
    private fun needsDisplayRotation(
        src: Bitmap,
        rotationDegrees: Int,
        displayWidth: Int,
        displayHeight: Int
    ): Boolean {
        val r = VideoDimensions.normalizeRotation(rotationDegrees)
        if (r == 0) return false
        // 180° does not change the shape, so it is always applied.
        if (!VideoDimensions.isQuarterTurn(r)) return true
        if (displayWidth <= 0 || displayHeight <= 0) return true
        if (displayWidth == displayHeight) return true
        return !(src.width == displayWidth && src.height == displayHeight)
    }

    /**
     * Rotate [src] by [rotationDegrees] (0/90/180/270). The input is
     * recycled when a new bitmap was created, so the caller only ever
     * has to recycle the RESULT.
     */
    private fun rotateBitmap(src: Bitmap, rotationDegrees: Int): Bitmap {
        val r = VideoDimensions.normalizeRotation(rotationDegrees)
        if (r == 0) return src
        return try {
            val matrix = Matrix().apply { postRotate(r.toFloat()) }
            val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
            if (out !== src) src.recycle()
            out
        } catch (_: Throwable) {
            // A rotation that cannot be applied must not kill the run
            // — the frame is simply analysed unrotated.
            src
        }
    }

    /**
     * Downscale [src] so its longest side is at most [maxSide]. Same
     * convention as `SequentialFrameSampler.boundToMaxSide`: the input
     * is recycled when a scaled copy was made, so the caller only ever
     * has to recycle the RESULT.
     */
    private fun boundToMaxSide(src: Bitmap, maxSide: Int): Bitmap {
        if (maxSide <= 0) return src
        val longest = max(src.width, src.height)
        if (longest <= maxSide) return src
        val ratio = maxSide.toFloat() / longest
        val w = (src.width * ratio).roundToInt().coerceAtLeast(1)
        val h = (src.height * ratio).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        if (scaled !== src) src.recycle()
        return scaled
    }

    /**
     * Compute the crop window (in source pixels) for a target
     * aspect centered on [center]. Pure function — unit-tested.
     *
     * The window is the largest rect with aspect `targetW:targetH`
     * that fits inside `srcW x srcH`; the center is clamped so the
     * window never leaves the frame (no black bars, no stretching).
     */
    internal fun cropWindowFor(
        srcW: Int,
        srcH: Int,
        targetW: Float,
        targetH: Float,
        center: Pair<Float, Float>
    ): com.aicreatorstudio.app.model.CropWindow {
        val tw = targetW.coerceAtLeast(0.01f)
        val th = targetH.coerceAtLeast(0.01f)
        val targetAspect = tw / th
        val srcAspect = srcW.toFloat() / srcH
        val (cw, ch) = if (srcAspect > targetAspect) {
            // Source wider: full height, crop sides.
            val h = srcH
            val w = (h * targetAspect).toInt().coerceIn(1, srcW)
            w to h
        } else {
            // Source taller: full width, crop top/bottom.
            val w = srcW
            val h = (w / targetAspect).toInt().coerceIn(1, srcH)
            w to h
        }
        val cxPx = (center.first.coerceIn(0f, 1f) * srcW).toInt()
        val cyPx = (center.second.coerceIn(0f, 1f) * srcH).toInt()
        val left = (cxPx - cw / 2).coerceIn(0, (srcW - cw).coerceAtLeast(0))
        val top = (cyPx - ch / 2).coerceIn(0, (srcH - ch).coerceAtLeast(0))
        return com.aicreatorstudio.app.model.CropWindow(left, top, cw, ch)
    }

    /**
     * Compute the saliency-weighted centre of mass of [bmp] (the
     * input is downscaled in place by the caller). Fallback path
     * for frames with no detected pose. Exposed `internal` for
     * unit testing.
     */
    internal fun saliencyCentre(bmp: Bitmap): Pair<Float, Float> {
        val w = SAMPLE_WIDTH
        val h = (bmp.height * w / bmp.width.coerceAtLeast(1)).coerceAtLeast(1)
        val small = if (bmp.width != w) Bitmap.createScaledBitmap(bmp, w, h, true) else bmp
        try {
            val lum = IntArray(w * h)
            val pixels = IntArray(w * h)
            small.getPixels(pixels, 0, w, 0, 0, w, h)
            for (i in 0 until w * h) {
                val c = pixels[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                lum[i] = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
            }

            val energy = FloatArray(w * h)
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val gx =
                        -lum[(y - 1) * w + (x - 1)] + lum[(y - 1) * w + (x + 1)] +
                        -2 * lum[y * w + (x - 1)] + 2 * lum[y * w + (x + 1)] +
                        -lum[(y + 1) * w + (x - 1)] + lum[(y + 1) * w + (x + 1)]
                    val gy =
                        -lum[(y - 1) * w + (x - 1)] - 2 * lum[(y - 1) * w + x] - lum[(y - 1) * w + (x + 1)] +
                        lum[(y + 1) * w + (x - 1)] + 2 * lum[(y + 1) * w + x] + lum[(y + 1) * w + (x + 1)]
                    energy[y * w + x] = abs(gx) + abs(gy).toFloat()
                }
            }

            var sumW = 0f
            var sumX = 0f
            var sumY = 0f
            val cxBias = (w - 1) * 0.5f
            val cyBias = (h - 1) * 0.5f
            val sigma2 = (w * w + h * h) * 0.05f
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val dx = x - cxBias
                    val dy = y - cyBias
                    val bias = exp(-(dx * dx + dy * dy) / (2f * sigma2))
                    val wXy = energy[y * w + x] + 0.1f * bias
                    sumW += wXy
                    sumX += wXy * x
                    sumY += wXy * y
                }
            }
            if (sumW < 1e-6f) return Pair(0.5f, 0.5f)
            val nx = (sumX / sumW / (w - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
            val ny = (sumY / sumW / (h - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
            return Pair(nx, ny)
        } finally {
            if (small !== bmp) small.recycle()
        }
    }

    private fun probeDurationMs(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * 1-Euro filter — see Casiez et al. 2012. Unchanged from v1:
     * high smoothing value = heavier smoothing (no camera jumps).
     *
     * 2026-09-28 — the implementation moved to
     * [com.aicreatorstudio.app.model.util.OneEuroFilter] so Motion
     * Tracking can reuse it, and so it is finally unit-testable: it
     * used to be a nested class inside this Hilt `@Singleton`, which
     * cannot be constructed in a JVM unit test.
     *
     * The alias that preserves the existing call site —
     * `OneEuroFilter(smoothing).smooth(trace)` — is declared at file
     * scope, because Kotlin does not permit a nested `typealias`. The
     * extracted class defaults `dtSeconds` to [SAMPLE_INTERVAL_MS]
     * expressed in seconds (250 ms), which is this engine's stride, so
     * the smoothing behaviour is bit-for-bit what it was.
     */

    companion object {
        private const val TAG = "SaliencyTracker"

        /** Frame sampling stride (ms). 250 ms = 4 fps analysis rate. */
        const val SAMPLE_INTERVAL_MS: Long = 250L

        /** Output keyframe spacing (ms). 200 ms = 5 keyframes / second. */
        const val KEYFRAME_INTERVAL_MS: Long = 200L

        /** Width of the analysis bitmap (height is computed from AR). */
        const val SAMPLE_WIDTH: Int = 160

        /**
         * Longest side of a frame handed to the pose/face landmarkers.
         * The models resize to 256/128 internally; this only bounds the
         * YUV→ARGB conversion cost per sample, so it stays well above the
         * model input to preserve small-subject accuracy.
         */
        const val ANALYSIS_MAX_SIDE: Int = 720

        /**
         * Minimum mean visibility over the anchor landmarks for a
         * pose to count as a detection. Below this the frame falls
         * back to saliency instead of trusting a ghost skeleton.
         */
        const val MIN_VISIBILITY: Float = 0.3f

        /** Centralized pose model spec (see class KDoc). */
        val POSE_SPEC = AiModelCatalog.POSE_LANDMARKER_LITE

        /** 2026-09-06 — face fallback model spec (BlazeFace). */
        val FACE_SPEC = AiModelCatalog.BLAZE_FACE_SHORT_RANGE

        /**
         * PoseLandmarker multi-person slot count. Two is the sweet
         * spot for a reframer: enough to catch a second person
         * walking through frame, cheap enough to stay realtime-ish
         * on mid-range CPU.
         */
        const val NUM_POSES: Int = 2

        /**
         * Consecutive samples without a detection before the
         * tracker drops its lock and falls back to saliency
         * (250 ms samples → 2 s of loss tolerance). Below this the
         * last locked position is forward-filled, which is what
         * keeps the camera from jumping when the subject briefly
         * leaves frame.
         */
        const val LOSS_RECOVERY_SAMPLES: Int = 8
    }
}

/**
 * Per-run locked-target state for multi-person tracking. Lives for
 * the duration of one Auto Frame run (one clip); a new run starts
 * with `locked = null` and re-runs full target selection on the
 * first frame that carries a pose.
 */
data class TargetState(
    /** Last subject anchor (normalized 0..1). */
    val cx: Float,
    val cy: Float,
    /** Torso span — the size-prominence signal. */
    val shoulderSpan: Float,
    /** Face/body anchor offsets captured at lock time. */
    val anchorOffsets: FloatArray,
    /** Sample timestamp of the last confident detection. */
    val lastSeenMs: Long
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * File-scope alias for the extracted 1-Euro filter.
 *
 * Kotlin forbids a nested `typealias`, so this lives outside
 * [SaliencyTrackerEngine]. It exists so the filter's call site inside
 * the engine — `OneEuroFilter(smoothing).smooth(trace)` — did not have
 * to change when the implementation moved to
 * [com.aicreatorstudio.app.model.util.OneEuroFilter] on 2026-09-28.
 */
private typealias OneEuroFilter = com.aicreatorstudio.app.model.util.OneEuroFilter
