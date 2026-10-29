package com.aicreatorstudio.app.ai.engines

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import com.aicreatorstudio.app.ai.AiToolEngine
import com.aicreatorstudio.app.ai.AiToolOptions
import com.aicreatorstudio.app.ai.AiToolResult
import com.aicreatorstudio.app.ai.media.VideoFramePipeline
import com.aicreatorstudio.app.ai.tracking.RoiTracker
import com.aicreatorstudio.app.ai.tracking.RoiTrackerFactory
import com.aicreatorstudio.app.model.CropKeyframe
import com.aicreatorstudio.app.model.MotionTrackSettings
import com.aicreatorstudio.app.model.util.MotionTrackingGeometry
import com.aicreatorstudio.app.model.util.MotionTrajectorySmoothing
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

/**
 * **Motion Tracking** — attaches an overlay to a moving target.
 *
 * Given an initial frame and a user-drawn ROI, this engine produces a
 * per-frame trajectory of [`CropKeyframe`]s tracking that region, which
 * the export pipeline then uses to bind a text/sticker/mask to the
 * target (`AiExportHooks`, via `RenderSegment.cropKeyframes`).
 *
 * ## Architecture — why there is no new pipeline here
 *
 * The frame feed is [VideoFramePipeline], the **same** streaming
 * decoder/encoder the export uses. That is not a convenience: if the
 * analysis pass and the bake pass decoded the source differently —
 * a different rotation handling, a different scale, a different
 * chroma conversion — every coordinate would be slightly wrong and the
 * overlay would sit a few pixels off, or 90 degrees off, with no
 * obvious cause. Analysing through the export's own frame space makes
 * that class of bug unrepresentable.
 *
 * In particular this rules out:
 *  - `cv::VideoCapture` — software decode on Android, no rotation
 *    metadata handling, no hardware acceleration;
 *  - per-sample `MediaMetadataRetriever.getFrameAtTime` — correct for
 *    Auto Frame's 250 ms sampling, but it snaps to keyframes via
 *    `OPTION_CLOSEST_SYNC`, so a per-frame track would see duplicate
 *    frames and miss real motion entirely.
 *
 * ## Pipeline
 *
 * ```
 * source.mp4
 *   -> VideoFramePipeline.transcode (hardware decode, rotation-corrected)
 *      -> FrameOp: downscale to 360p -> ROI tracker on a 1.6x crop
 *         -> confidence -> accumulate full-rate trace
 *   -> 1-Euro smooth (centre gently, extent heavily)
 *   -> decimate to keyframes
 *   -> List<CropKeyframe> (with width/height/confidence/isFound)
 * ```
 *
 * ## The two work-reduction decisions that make it viable
 *
 * 1. **360p analysis.** The tracker runs on a downscaled frame. This is
 *    not only ~9x cheaper than 1080p; it is also *more accurate*,
 *    because at full resolution a small box contains fine texture
 *    (skin pores, fabric weave) that moves independently of the object
 *    and makes the correlation response noisy.
 * 2. **ROI crop, not full frame.** The tracker is initialised on a crop
 *    of the box grown by `CONTEXT_SCALE` (1.6x). CSRT's cost is
 *    dominated by the correlation FFT, which scales with the search
 *    window area rather than the frame area, so this is what brings a
 *    15-25 ms full-frame update down to roughly 3-6 ms.
 *
 * ## Failure handling
 *
 * Tracking failure is *reported*, not hidden. A frame where the tracker
 * misses is emitted as a keyframe with `isFound = false` and a decayed
 * confidence, and the consumer fades the overlay rather than freezing
 * it in place — a frozen overlay that snaps back is more obviously
 * broken than one that briefly fades. See
 * [MotionTrackingGeometry.confidenceAlpha].
 *
 * ## Why the encoder is not skipped
 *
 * `transcode` re-encodes the frames it decodes, and this engine throws
 * the result away. That is deliberate: there is no decode-only entry
 * point on [VideoFramePipeline] today, and adding one would mean a
 * second code path diverging from the export's. The wasted encode is
 * hardware and runs at the decoder's rate, so the analysis pass costs
 * roughly 2x a theoretical decode-only pass. The TODO below records the
 * optimisation for when it is worth the risk.
 *
 * TODO(perf): add a `decodeOnly` flag to [VideoFramePipeline.Request]
 *   that skips encoder setup and returns after `frameOp`. Roughly 40
 *   lines in `runLoop`. Halves this pass. Not done yet because it
 *   touches the shared pipeline used by matting/upscale/reframe.
 */
@Singleton
class MotionTrackingEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : AiToolEngine {

    override val id: String = TOOL_ID

    override val displayName: String = "Motion Tracking"

    override val modelStatusText: String
        get() = RoiTrackerFactory.statusText()

    /**
     * Always available: the NCC fallback has no model and no native
     * dependency, so this feature cannot be blocked by a missing
     * download. The two-tier design guarantees a working path.
     */
    override val isAvailable: Boolean = true

    /** No model file — identical to the other model-free tools. */
    override suspend fun ensureModelReady(onProgress: (Float) -> Unit): Result<Unit> {
        onProgress(1f)
        return Result.success(Unit)
    }

    override suspend fun process(
        clipId: String,
        videoUri: Uri,
        options: AiToolOptions,
        onProgress: (Float) -> Unit
    ): Result<AiToolResult> = withContext(Dispatchers.IO) {
        val roi = options.trackingRoi
        if (roi == null) {
            return@withContext Result.failure(
                Exception("ناحیه ردیابی انتخاب نشده است. ابتدا کادر را روی سوژه بکشید.")
            )
        }
        if (!MotionTrackingGeometry.isTrackable(roi)) {
            return@withContext Result.failure(
                Exception(
                    "ناحیه انتخابی برای ردیابی مناسب نیست. " +
                        "کادر بزرگ‌تری روی بخش پرجزئیات ویدیو بکشید."
                )
            )
        }

        try {
            onProgress(0f)
            val info = VideoFramePipeline.probe(context, videoUri)
                ?: return@withContext Result.failure(
                    Exception("اطلاعات ویدیو قابل خواندن نیست")
                )

            // ---- analysis resolution ----
            // Tier B runs on a downscale. See the class KDoc for why
            // this is faster AND more accurate rather than a trade.
            val analysisW = min(TRACK_WORK_WIDTH, info.displayWidth).coerceAtLeast(64)
            val analysisH = max(
                64,
                (info.displayHeight.toFloat() / info.displayWidth * analysisW).toInt()
            )

            val tracker = RoiTrackerFactory.create()
                ?: return@withContext Result.failure(Exception(modelStatusText))

            // ---- run the tracking pass ----
            val rawTrace = ArrayList<CropKeyframe>()
            // 2026-10-20 — the cancel flag was NEVER SET. The
            // `AtomicBoolean` was created and read by
            // `VideoFramePipeline`'s per-frame poll, but nothing ever
            // flipped it, so the poll always answered "not cancelled".
            // The pipeline's `transcode` is a blocking call, so
            // coroutine cancellation could not interrupt it either —
            // the user pressed Cancel and the job kept re-encoding the
            // whole clip for minutes before it unwound.
            //
            // The flag is now driven by the host coroutine, exactly as
            // `EsrganUpscalePipeline` does it.
            val hostJob = coroutineContext[Job]
            val cancelled = AtomicBoolean(false)
            // Flip the flag the moment the host coroutine ends, so the
            // blocking transcode loop notices even between polls.
            hostJob?.invokeOnCompletion { cancelled.set(true) }
            var initFailed = false

            val tmp = File(context.cacheDir, "mtrack-probe-${System.currentTimeMillis()}.mp4")
            try {
                val req = VideoFramePipeline.Request(
                    srcUri = videoUri,
                    dstFile = tmp,
                    // Analysis size: the encoder output is discarded, so
                    // keep it small — this is pure overhead.
                    outWidth = analysisW,
                    outHeight = analysisH,
                    frameOp = TrackingFrameOp(
                        tracker = tracker,
                        initialRoi = roi,
                        analysisWidth = analysisW,
                        analysisHeight = analysisH,
                        minConfidence = options.minConfidence,
                        out = rawTrace,
                        onInitFailure = { initFailed = true }
                    ),
                    copyAudio = false,
                    onProgress = { done, total ->
                        onProgress(0.05f + 0.75f * (done.toFloat() / total.coerceAtLeast(1)))
                    },
                    isCancelled = { cancelled.get() || hostJob?.isActive == false }
                )
                val res = VideoFramePipeline.transcode(context, req)
                when (res) {
                    is VideoFramePipeline.Result.Failed -> {
                        return@withContext Result.failure(
                            Exception(res.cause.message ?: "ردیابی ناموفق بود")
                        )
                    }
                    VideoFramePipeline.Result.Cancelled -> {
                        return@withContext Result.failure(CancellationException("cancelled"))
                    }
                    is VideoFramePipeline.Result.Done -> Unit
                }
            } finally {
                // The tracker holds a native handle (OpenCV) — release
                // it even on the failure paths.
                runCatching { tracker.close() }
                runCatching { tmp.delete() }
            }

            coroutineContext[Job]?.ensureActive()

            if (initFailed || rawTrace.isEmpty()) {
                return@withContext Result.failure(
                    Exception(
                        "ردیاب نتوانست سوژه را در ناحیه انتخابی پیدا کند. " +
                            "کادر را روی بخش پرجزئیات‌تری بکشید."
                    )
                )
            }

            // ---- smooth at FULL rate, then decimate ----
            // Order matters: filtering an already-decimated list would
            // be filtering an aliased signal. This mirrors
            // SaliencyTrackerEngine.process, which smooths the 250 ms
            // trace before emitting keyframes.
            onProgress(0.85f)
            val intervalMs = estimateFrameIntervalMs(rawTrace)
            val smoothed = MotionTrajectorySmoothing.smooth(
                raw = rawTrace,
                positionSmoothing = options.positionSmoothing,
                scaleSmoothing = options.scaleSmoothing,
                intervalMs = intervalMs
            )
            val keyframes = MotionTrajectorySmoothing.decimate(
                smoothed = smoothed,
                intervalMs = KEYFRAME_INTERVAL_MS,
                durationMs = info.durationMs
            )

            val foundCount = keyframes.count { it.isFound }
            val foundPct = if (keyframes.isEmpty()) 0 else foundCount * 100 / keyframes.size
            val lostRuns = countLossRuns(keyframes)

            onProgress(1f)
            Result.success(
                AiToolResult(
                    // Auto Frame settings are NOT populated: this engine
                    // owns no crop. Only the trajectory + its own
                    // settings block are written back, so a user can
                    // track without accidentally enabling a reframe.
                    autoFrame = null,
                    cropKeyframes = keyframes,
                    motionTrack = options.commitToClip.takeIf { it }?.let {
                        buildSettings(options, roi)
                    },
                    summary = buildSummary(
                        keyframes.size, foundPct, lostRuns, intervalMs
                    )
                )
            )
        } catch (e: CancellationException) {
            Result.failure(e)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override fun release() {
        // Stateless: the tracker instances are owned by each run and
        // closed in the run's `finally`. Nothing to cache.
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    /**
     * The per-frame tracker driver, run as a [VideoFramePipeline.FrameOp].
     *
     * Returns `src` unchanged: this pass analyses, it does not render.
     * Allocating a new bitmap per frame here would make the pipeline
     * recycle the original and allocate a full-size replacement, which
     * is the fastest way to OOM a long clip.
     */
    private class TrackingFrameOp(
        private val tracker: RoiTracker,
        private val initialRoi: RectF,
        private val analysisWidth: Int,
        private val analysisHeight: Int,
        private val minConfidence: Float,
        private val out: MutableList<CropKeyframe>,
        private val onInitFailure: () -> Unit
    ) : VideoFramePipeline.FrameOp {

        private var inited = false
        private var attemptedInit = false
        private var lastBox: RectF = initialRoi
        private var lastConfidence = 1f
        private var missStreak = 0
        private var frameIndex = 0

        override fun process(src: Bitmap, ptsUs: Long, idx: Int): Bitmap {
            val timeMs = ptsUs / 1000L

            // 2026-10-20 — THE ANALYSIS RESOLUTION IS NOW REAL.
            //
            // `analysisWidth` / `analysisHeight` were declared but
            // NEVER USED: the tracker received the full-resolution
            // frame. The class KDoc claimed a "~360p analysis" that is
            // "~9x cheaper than 1080p", and that claim was false —
            // the OpenCV CSRT backend ran on 1080p frames.
            //
            // The ROI is NORMALIZED 0..1 (see [RoiTracker.init]), so
            // down scaling the picture is resolution-independent: the
            // same box describes the same region of the frame.
            val analysis = downscaleForAnalysis(src)
            try {
                processAnalysisFrame(analysis, timeMs)
            } finally {
                if (analysis !== src) analysis.recycle()
            }
            return src
        }

        /**
         * The tracking step, on a frame that is already at the
         * analysis resolution.
         */
        private fun processAnalysisFrame(src: Bitmap, timeMs: Long) {
            // ---- first frame: initialise ----
            if (!attemptedInit) {
                attemptedInit = true
                inited = try {
                    tracker.init(src, lastBox)
                } catch (t: Throwable) {
                    Log.w(TAG, "tracker init threw", t)
                    false
                }
                if (!inited) {
                    onInitFailure()
                    return
                }
            }

            // ---- update ----
            var found = false
            if (inited) {
                val result = try {
                    tracker.update(src)
                } catch (t: Throwable) {
                    Log.w(TAG, "tracker update threw", t)
                    null
                }
                if (result != null && result.confidence >= minConfidence) {
                    // Clamp and cap the per-step scale change before
                    // accepting: an unclamped box that walks off the
                    // edge flings the overlay off screen, and CSRT can
                    // jump scale 2x in a single frame on a partial
                    // occlusion, which no later frame can undo.
                    val clamped = MotionTrackingGeometry.clampNorm(result.roi)
                    lastBox = MotionTrackingGeometry.capScaleStep(lastBox, clamped)
                    lastConfidence = result.confidence
                    missStreak = 0
                    found = true
                } else {
                    missStreak++
                    // Decay rather than zero: an immediate drop to 0
                    // makes the overlay blink on a single missed frame,
                    // which is far more visible than a brief dip.
                    lastConfidence = decayConfidence(lastConfidence, missStreak)
                    if (missStreak > MAX_MISS_FRAMES) {
                        // Permanent loss: stop the attempt. Continuing
                        // would emit a box that describes nothing.
                        inited = false
                        attemptedInit = true
                        out.add(
                            CropKeyframe(
                                timeMs = timeMs,
                                centerX = lastBox.centerX(),
                                centerY = lastBox.centerY(),
                                zoom = 1f,
                                width = lastBox.width(),
                                height = lastBox.height(),
                                confidence = 0f,
                                isFound = false
                            )
                        )
                        return
                    }
                }
            }

            // ---- emit EVERY frame ----
            // Including the not-found entries. Omitting them would
            // compress the consumer's timeline and silently time-shift
            // the overlay.
            out.add(
                CropKeyframe(
                    timeMs = timeMs,
                    centerX = lastBox.centerX().coerceIn(0f, 1f),
                    centerY = lastBox.centerY().coerceIn(0f, 1f),
                    zoom = 1f,
                    width = lastBox.width().coerceIn(0f, 1f),
                    height = lastBox.height().coerceIn(0f, 1f),
                    confidence = lastConfidence,
                    isFound = found
                )
            )
            frameIndex++
        }

        /**
         * Down scale [src] to the analysis resolution.
         *
         * Returns [src] itself when it is already small enough, so
         * the common case allocates nothing. The caller owns the
         * returned bitmap and must recycle it when it is not [src].
         */
        private fun downscaleForAnalysis(src: Bitmap): Bitmap {
            val w = analysisWidth
            val h = analysisHeight
            if (w <= 0 || h <= 0) return src
            if (src.width <= w && src.height <= h) return src
            val scaled = try {
                Bitmap.createScaledBitmap(src, w, h, true)
            } catch (t: Throwable) {
                Log.w(TAG, "analysis downscale failed; using the full-res frame", t)
                return src
            }
            return if (scaled.width == w && scaled.height == h) scaled else src
        }

        private fun decayConfidence(conf: Float, streak: Int): Float =
            (conf * (1f - CONFIDENCE_DECAY_PER_MISS * streak)).coerceIn(0f, 1f)
    }

    private fun buildSettings(options: AiToolOptions, roi: RectF): MotionTrackSettings =
        MotionTrackSettings(
            enabled = true,
            overlayId = options.trackingOverlayId,
            maskMode = options.trackingMaskMode,
            maskStrength = options.trackingMaskStrength.coerceIn(0f, 1f),
            anchorU = options.trackingAnchorU.coerceIn(0f, 1f),
            anchorV = options.trackingAnchorV.coerceIn(0f, 1f),
            scaleWithBox = options.trackingScaleWithBox,
            referenceBoxHeight = (roi.bottom - roi.top).coerceAtLeast(0.01f),
            positionSmoothing = options.positionSmoothing.coerceIn(0f, 1f),
            scaleSmoothing = options.scaleSmoothing.coerceIn(0f, 1f),
            minConfidence = options.minConfidence.coerceIn(0f, 1f),
            algorithm = RoiTrackerFactory.backendId()
        )

    private fun buildSummary(
        total: Int,
        foundPct: Int,
        lostRuns: Int,
        intervalMs: Long
    ): String {
        val fps = if (intervalMs > 0) (1000 / intervalMs) else 0
        val quality = when {
            foundPct >= 90 -> "ردیابی پایدار"
            foundPct >= 60 -> "ردیابی نسبی (پوشیدگی جزئی)"
            else -> "ردیابی ضعیف — سوژه را دستی تنظیم کنید"
        }
        val lostNote = if (lostRuns > 0) " · $lostRuns قطعی مسیر" else ""
        return "$total نقطه کلیدی روی ${fps} فریم‌بر‌ثانیه تولید شد " +
            "($quality $foundPct٪)$lostNote"
    }

    /**
     * Infer the real sample stride from the trace. The pipeline emits
     * one callback per decoded frame, so consecutive timestamps are
     * exactly the frame interval — and the 1-Euro filter's derivative
     * estimate is only correct if it is told the truth about `dt`.
     */
    private fun estimateFrameIntervalMs(trace: List<CropKeyframe>): Long {
        if (trace.size < 2) return MotionTrajectorySmoothing.DEFAULT_INTERVAL_MS
        // Median of the first few deltas: robust against a single
        // out-of-order timestamp without a full sort.
        val deltas = ArrayList<Long>(min(trace.size - 1, 30))
        for (i in 1 until min(trace.size, 31)) {
            val d = trace[i].timeMs - trace[i - 1].timeMs
            if (d > 0) deltas.add(d)
        }
        if (deltas.isEmpty()) return MotionTrajectorySmoothing.DEFAULT_INTERVAL_MS
        deltas.sort()
        return deltas[deltas.size / 2].coerceIn(4L, 1000L)
    }

    /** Count contiguous runs of not-found entries — surfaced in the summary. */
    private fun countLossRuns(keys: List<CropKeyframe>): Int {
        var runs = 0
        var inRun = false
        for (k in keys) {
            if (!k.isFound) {
                if (!inRun) {
                    runs++
                    inRun = true
                }
            } else {
                inRun = false
            }
        }
        return runs
    }

    companion object {
        private const val TAG = "MotionTrackingEngine"

        /** Stable id for the dock, `aiTools` map, and logs. */
        const val TOOL_ID: String = "motiontrack"

        /**
         * Analysis width for the tracker. See the class KDoc for why
         * 360p is both faster and more accurate.
         */
        const val TRACK_WORK_WIDTH: Int = 360

        /**
         * Output keyframe spacing. 33 ms ~= 30 fps: an overlay must be
         * updated per displayed frame, because the 5 fps Auto Frame
         * rate is visibly steppy for a sticker even though it is fine
         * for a slow crop.
         */
        const val KEYFRAME_INTERVAL_MS: Long = 33L

        /**
         * Consecutive missed frames before the track is abandoned.
         * ~1.5 s at 30 fps. Below this the box is held and the
         * confidence decays, which is what lets a brief occlusion pass
         * without the overlay ever visibly breaking.
         */
        const val MAX_MISS_FRAMES: Int = 45

        /** Confidence lost per consecutive miss, before clamping. */
        const val CONFIDENCE_DECAY_PER_MISS: Float = 0.05f
    }
}
