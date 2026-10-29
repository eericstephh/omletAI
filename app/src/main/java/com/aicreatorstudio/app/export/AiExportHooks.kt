package com.aicreatorstudio.app.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import com.aicreatorstudio.app.ai.MaskBitmaps
import com.aicreatorstudio.app.ai.MaskPostProcess
import com.aicreatorstudio.app.ai.MattingSidecar
import com.aicreatorstudio.app.ai.engines.MediaPipeMattingEngine
import com.aicreatorstudio.app.ai.media.VideoFramePipeline
import com.aicreatorstudio.app.ai.reframe.ReframePlanner
import com.aicreatorstudio.app.model.CropKeyframe
import com.aicreatorstudio.app.model.CanvasLetterbox
import com.aicreatorstudio.app.model.MattingBackgroundMode
import com.aicreatorstudio.app.model.util.MotionTrackingGeometry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Real export integration for the offline AI tools.
 *
 * The Media3 Transformer path cannot run per-frame CPU composites
 * (no public per-frame texture-upload hook in 1.5.0), so AI effects
 * integrate as honest pre/post passes around the unchanged
 * Transformer pipeline:
 *
 * - **Background Remove / Auto Frame (pre):** [prepareSourceUri]
 *   renders a CPU intermediate (`decode -> composite/crop per
 *   frame -> AVC encode -> audio passthrough`) via the shared
 *   [VideoFramePipeline] and hands its URI to the Transformer.
 *   FPS, duration and audio are preserved by construction (source
 *   timestamps flow through, audio is copied sample-for-sample).
 * - **Voice Clean (post):** [maybeSwapCleanAudio] remuxes the
 *   Transformer output, copying the video track untouched and
 *   replacing the audio with the AAC-encoded cleaned WAV (same
 *   sample count, so sync and duration are exact).
 * - **AI Upscale** needs no hook: its engines already emit final
 *   MP4s through the same pipeline.
 *
 * Failures here are never silent: a missing sidecar, an
 * unsupported TRANSPARENT MP4 export, or a corrupt WAV returns a
 * user-facing message the engine surfaces as ExportState.Failed
 * (pre) or logs + keeps the original audio (post — the video is
 * already rendered, so degrading to the original track is the
 * safe choice and is logged, not hidden).
 */
@Singleton
class AiExportHooks @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val sessionTemps = mutableListOf<File>()

    /**
     * Build the effective source URI for [segment], running AI
     * pre-renders when enabled. Returns the (possibly rewritten)
     * URI string. Throws [AiExportException] with a user-facing
     * message when a required AI artifact is missing or
     * unsupported.
     */
    suspend fun prepareSourceUri(
        segment: RenderSegment,
        /**
         * 2026-10-27 — the project's canvas fill, threaded here so a
         * TRANSPARENT Background Remove composite can lay the subject
         * over the exact colour the letterbox bars are painted with.
         * The segment's own field is always the model default —
         * [com.aicreatorstudio.app.export.RenderGraphBuilder] puts the
         * real value at the graph level.
         */
        canvasBackground: com.aicreatorstudio.app.model.CanvasBackground =
            com.aicreatorstudio.app.model.CanvasBackground.Black,
        onStage: (stage: String, frac: Float) -> Unit = { _, _ -> }
    ): String = withContext(Dispatchers.IO) {
        var uri = segment.sourceUri
        val matting = segment.matting
        if (matting?.enabled == true) {
            onStage("AI background", 0f)
            uri = compositeMatting(segment, matting, canvasBackground, uri) { f ->
                onStage("AI background", f)
            }
        }
        val auto = segment.autoFrame
        if (auto?.enabled == true && segment.cropKeyframes.isNotEmpty()) {
            onStage("AI reframe", 0f)
            uri = reframe(segment, uri) { f ->
                onStage("AI reframe", f)
            }
        }
        // ---- 2026-09-28 Motion Tracking ----
        // ORDER IS LOAD-BEARING. This runs AFTER the reframe, and must.
        // The tracked box describes a position in the frame the viewer
        // finally sees; binding before a reframe would let the reframe
        // crop the overlay away — a sticker pinned to a face that
        // vanishes because Auto Frame cropped to that face reads to a
        // user as data loss.
        //
        // It also runs after matting for the same reason: the matting
        // composite changes the picture's content, so the pixels a
        // tracked box points at only exist after it.
        if (TrackedOverlayRenderer.canRender(segment.motionTrack, segment.trackingOverlay) &&
            segment.cropKeyframes.isNotEmpty()
        ) {
            onStage("AI tracking", 0f)
            uri = burnTrackedOverlay(segment, uri) { f ->
                onStage("AI tracking", f)
            }
        }
        // ---- 2026-10-13 PiP shape masks ----
        // ORDER IS LOAD-BEARING, and this is last on purpose. An overlay's
        // `positionX` / `positionY` / `scale` describe a position in the
        // frame the viewer finally sees, so the PiP must be composited
        // after every pass that changes the picture's content or geometry
        // (matting, reframe) and after the tracking burn-in, which can
        // itself draw over the frame.
        //
        // Skipped entirely when no overlay overlaps this segment: the
        // pre-pass costs a full re-transcode, and a project with no
        // overlays must export through exactly the path it did before.
        if (OverlayCompositeRenderer.hasRenderableOverlays(
                segment.mediaOverlays,
                segment.timelineStartMs,
                segment.outputDurationMs
            )
        ) {
            onStage("Overlay", 0f)
            uri = burnMediaOverlays(segment, uri) { f ->
                onStage("Overlay", f)
            }
        }
        uri
    }

    /** Delete every intermediate file created by [prepareSourceUri]. */
    suspend fun cleanup() = withContext(Dispatchers.IO) {
        sessionTemps.toList().forEach { runCatching { it.delete() } }
        sessionTemps.clear()
    }

    /**
     * Post-Transformer audio swap. Returns the final file size, or
     * null when no swap applied (original output kept). Never
     * throws: any failure is logged and the original audio stays.
     */
    suspend fun maybeSwapCleanAudio(
        target: OutputTarget,
        transformerOutputPath: String,
        transformerBytes: Long,
        segment: RenderSegment,
        durationMs: Long
    ): Long? = withContext(Dispatchers.IO) {
        try {
            if (segment.voiceClean?.enabled != true) return@withContext null
            val clipId = segment.clipId ?: return@withContext null
            val wav = File(context.cacheDir, "voiceclean/$clipId.wav")
            if (!wav.exists() || wav.length() <= 44) {
                Log.w(TAG, "voiceclean cache missing for $clipId; keeping original audio")
                return@withContext null
            }
            val srcUri = readBackUri(target, transformerOutputPath) ?: return@withContext null
            val tmp = newTemp("voiceclean-$clipId")
            val ok = remuxWithCleanAudio(srcUri, wav, tmp)
            if (!ok) {
                runCatching { tmp.delete() }
                return@withContext null
            }
            val outSize = tmp.length()
            if (outSize <= 0 || !replaceTargetWithFile(target, tmp)) {
                return@withContext null
            }
            outSize
        } catch (t: Throwable) {
            Log.w(TAG, "voiceclean post-swap failed; keeping original audio", t)
            null
        }
    }

    // ----------------------------------------------------------------
    // pre: matting composite
    // ----------------------------------------------------------------

    /**
     * Cancel-aware guard for the frame pipeline's decode loop.
     *
     * Every AI pre-render hook below used to hardcode
     * `isCancelled = { false }`, which left the blocking decode loop
     * running to completion (potentially minutes per segment) after
     * the user cancelled the export. The pipeline is a non-suspending
     * loop, so this predicate is the only way in: it consults the
     * calling coroutine's [Job], which `VideoExportEngine` cancels on
     * user cancel. Result.Cancelled surfaces as
     * `AiExportException("Export cancelled.")` and the existing
     * outer handlers already map that to `ExportState.Cancelled`.
     */
    private suspend fun pipelineCancelCheck(): () -> Boolean {
        val job = kotlin.coroutines.coroutineContext[Job]
        return { job?.isActive == false }
    }

    private suspend fun compositeMatting(
        segment: RenderSegment,
        settings: com.aicreatorstudio.app.model.MattingSettings,
        canvasBackground: com.aicreatorstudio.app.model.CanvasBackground,
        srcUri: String,
        onProgress: (Float) -> Unit
    ): String = withContext(Dispatchers.IO) {
        // 2026-10-27 — TRANSPARENT is no longer rejected. It is the
        // committed default of every fresh Background Remove run (the
        // CapCut behaviour: the subject stays, the background is gone)
        // and composites over the project's canvas background colour —
        // the same fill `CanvasLetterbox.fadeColorArgb` paints the
        // letterbox bars with, so the exported result matches what
        // sits behind the clip in the editor. MP4 has no alpha
        // channel; this is the honest baked equivalent.
        val projectId = segment.projectId
        val clipId = segment.clipId
        if (projectId == null || clipId == null) {
            throw AiExportException("Background Remove data is missing project context. Re-run the tool.")
        }
        val modelSha = MattingSidecar.modelSha256OrNull(context, MediaPipeMattingEngine.MATTING_SPEC)
        if (!MattingSidecar.verify(context, projectId, clipId, modelSha)) {
            throw AiExportException(
                "Background Remove masks are missing or outdated. " +
                    "Re-run Background Remove on this clip, then export."
            )
        }
        val sidecarDir = MattingSidecar.directoryFor(context, projectId, clipId)
        val info = VideoFramePipeline.probe(context, Uri.parse(srcUri))
            ?: throw AiExportException("Cannot read video metadata for Background Remove export.")
        // TRANSPARENT resolves against the PROJECT canvas fill (the
        // same colour the letterbox bars use), which is what the
        // editor already paints behind the clip.
        val bg = prepareBackground(
            settings,
            CanvasLetterbox.fadeColorArgb(canvasBackground),
            info.displayWidth,
            info.displayHeight
        )
        // 2026-10-25 — the settings object `maskFor` post-processes
        // against (expansion/feather/reverse). Fixed for the whole
        // pass and cleared with the mask cache so a later pass never
        // inherits stale knobs.
        activeMatting = settings

        val tmp = newTemp("matting-$clipId")
        try {
            val res = VideoFramePipeline.transcode(
                context,
                VideoFramePipeline.Request(
                    srcUri = Uri.parse(srcUri),
                    dstFile = tmp,
                    outWidth = info.displayWidth,
                    outHeight = info.displayHeight,
                    frameOp = VideoFramePipeline.FrameOp { src, ptsUs, _ ->
                        val tsMs = ptsUs / 1000L
                        val mask = maskFor(sidecarDir, tsMs, info.displayWidth, info.displayHeight)
                        compositeFrame(src, mask, bg, settings, tsMs)
                        // maskFor returns the shared cached bitmap;
                        // never recycle it here.
                    },
                    copyAudio = true,
                    onProgress = { done, total -> onProgress(done.toFloat() / total.coerceAtLeast(1)) },
                    isCancelled = pipelineCancelCheck()
                )
            )
            // Release shared caches (mask + bg sources own their bitmaps).
            activeMatting = null
            dropMaskCache()
            bg.close()
            when (res) {
                is VideoFramePipeline.Result.Done -> Uri.fromFile(tmp).toString()
                is VideoFramePipeline.Result.Cancelled -> throw AiExportException("Export cancelled.")
                is VideoFramePipeline.Result.Failed ->
                    throw AiExportException(res.cause.message ?: "Background composite failed.")
            }
        } catch (t: AiExportException) {
            activeMatting = null
            runCatching { dropMaskCache() }
            runCatching { bg.close() }
            runCatching { tmp.delete() }
            throw t
        } catch (t: Throwable) {
            activeMatting = null
            runCatching { dropMaskCache() }
            runCatching { bg.close() }
            runCatching { tmp.delete() }
            throw AiExportException(t.message ?: "Background composite failed.")
        }
    }

    /** Mask PNG nearest [tsMs], scaled to the frame size (cached). */
    private var maskCacheTs: Long = Long.MIN_VALUE
    private var maskCacheBmp: Bitmap? = null

    /**
     * 2026-10-25 — the matting settings of the pass currently running.
     * Read by [maskFor] to decide+apply the shared composition-time
     * post-process (expansion → feather → reverse). Null when no
     * matting pass is active.
     */
    private var activeMatting: com.aicreatorstudio.app.model.MattingSettings? = null

    // 2026-10-19 — the sidecar directory is indexed ONCE per pass. The old
    // path called `MattingSidecar.nearestTimestampPng` for every output
    // frame, which re-listed and re-sorted the whole directory each time
    // (~600 file stats per lookup, 1 800 lookups for a 60 s 30 fps clip) and
    // dominated export time.
    private var maskIndexDir: File? = null
    private var maskIndexStamps: LongArray = LongArray(0)
    private var maskIndexFiles: Array<File> = emptyArray()

    private fun dropMaskCache() {
        maskCacheBmp?.recycle()
        maskCacheBmp = null
        maskCacheTs = Long.MIN_VALUE
        maskIndexDir = null
        maskIndexStamps = LongArray(0)
        maskIndexFiles = emptyArray()
    }

    /**
     * Build (or reuse) the sorted timestamp index for [dir]. Returns the
     * number of masks available; 0 means the sidecar has no usable PNGs.
     */
    private fun ensureMaskIndex(dir: File): Int {
        val cachedDir = maskIndexDir
        if (cachedDir == dir && maskIndexStamps.isNotEmpty()) return maskIndexStamps.size
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".png") }
            ?: emptyArray()
        val pairs = ArrayList<Pair<Long, File>>(files.size)
        for (f in files) {
            val ts = f.nameWithoutExtension.toLongOrNull() ?: continue
            pairs.add(ts to f)
        }
        pairs.sortBy { it.first }
        maskIndexStamps = LongArray(pairs.size) { pairs[it].first }
        maskIndexFiles = Array(pairs.size) { pairs[it].second }
        maskIndexDir = dir
        return pairs.size
    }

    private fun maskFor(sidecarDir: File, tsMs: Long, w: Int, h: Int): Bitmap? {
        if (ensureMaskIndex(sidecarDir) == 0) return null
        val idx = MaskIndexMath.nearestIndex(maskIndexStamps, tsMs)
        if (idx < 0) return null
        val png = maskIndexFiles[idx]
        val key = maskIndexStamps[idx]
        val cached = maskCacheBmp
        if (cached != null && !cached.isRecycled && key == maskCacheTs) return cached
        maskCacheBmp?.recycle()
        val decoded = BitmapFactory.decodeFile(png.absolutePath) ?: return null
        // 2026-10-25 — the shared mask post-process (expansion →
        // feather → reverse) runs at the sidecar's NATIVE resolution,
        // before the single resample to the frame. This is the parity
        // chain from `docs/CAPCUT_RE_BACKGROUND_REMOVAL_2026-10-25.md`
        // §8, implemented by `MaskPostProcess`/`MaskBitmaps`. Doing it
        // here (once per distinct mask, cached) instead of per output
        // frame keeps it O(masks) rather than O(frames), and mapping
        // the normalized knobs against the mask's own short side makes
        // the look resolution-independent — the resample then carries
        // the processed edge to the frame, exactly like the reference
        // shader's UV-space math.
        var processed = decoded
        val s = activeMatting
        if (s != null && !MaskPostProcess.isNoOp(s.expansion, s.feather, s.reverse)) {
            processed = MaskBitmaps.applyPostProcess(
                decoded,
                s.expansion.coerceIn(-1f, 1f),
                s.feather.coerceIn(0f, 1f),
                s.reverse
            )
        }
        val sized = if (processed.width == w && processed.height == h) processed
        else {
            val s2 = Bitmap.createScaledBitmap(processed, w, h, true)
            if (s2 !== processed) processed.recycle()
            s2
        }
        maskCacheTs = key
        maskCacheBmp = sized
        return sized
    }

    private interface BgSource : AutoCloseable {
        /**
         * Background frame for source timestamp [tsMs], exactly
         * [w]x[h]. Ownership: BLUR returns a fresh bitmap the caller
         * must recycle; all other modes return a cached bitmap the
         * caller must NOT recycle (owned by the source, freed by
         * [close]).
         */
        fun frameFor(tsMs: Long, w: Int, h: Int): Bitmap

        /** True when [frameFor] returns caller-owned bitmaps. */
        val callerOwnsFrames: Boolean get() = false
    }

    private fun prepareBackground(
        settings: com.aicreatorstudio.app.model.MattingSettings,
        canvasColorArgb: Int,
        w: Int,
        h: Int
    ): BgSource {
        return when (settings.backgroundMode) {
            MattingBackgroundMode.ORIGINAL -> object : BgSource {
                override fun frameFor(tsMs: Long, w: Int, h: Int): Bitmap {
                    // Unused (composite passes src through when
                    // ORIGINAL); return a black placeholder to keep
                    // the contract total.
                    return Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                }
                override fun close() {}
            }
            MattingBackgroundMode.BLUR -> object : BgSource {
                override val callerOwnsFrames: Boolean = true
                override fun frameFor(tsMs: Long, w: Int, h: Int): Bitmap {
                    // Real blur happens in compositeFrame (needs the
                    // source frame); this source is unused.
                    return Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                }
                override fun close() {}
            }
            MattingBackgroundMode.COLOR -> {
                val color = settings.backgroundColor
                object : BgSource {
                    var cached: Bitmap? = null
                    override fun frameFor(tsMs: Long, w: Int, h: Int): Bitmap {
                        val c = cached
                        if (c != null && !c.isRecycled && c.width == w && c.height == h) return c
                        c?.recycle()
                        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        b.eraseColor(color)
                        cached = b
                        return b
                    }
                    override fun close() {
                        cached?.recycle()
                        cached = null
                    }
                }
            }
            MattingBackgroundMode.IMAGE -> {
                val uri = settings.replacementBackgroundUri
                    ?: throw AiExportException("Background image is not set. Pick an image first.")
                val bmp = VideoFramePipeline.decodeImageUri(context, Uri.parse(uri))
                    ?: throw AiExportException("Cannot read the background image.")
                object : BgSource {
                    var cached: Bitmap? = null
                    var cachedKey = ""
                    override fun frameFor(tsMs: Long, w: Int, h: Int): Bitmap {
                        val key = "$w x $h"
                        val c = cached
                        if (c != null && !c.isRecycled && cachedKey == key) return c
                        c?.recycle()
                        // Cover: scale to fill, then center-crop (no
                        // stretching, no bars).
                        val scale = maxOf(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
                        val sw = (bmp.width * scale).toInt().coerceAtLeast(w)
                        val sh = (bmp.height * scale).toInt().coerceAtLeast(h)
                        val scaled = Bitmap.createScaledBitmap(bmp, sw, sh, true)
                        val cropped = Bitmap.createBitmap(
                            scaled, (sw - w) / 2, (sh - h) / 2, w, h
                        )
                        if (cropped !== scaled) scaled.recycle()
                        cached = cropped
                        cachedKey = key
                        return cropped
                    }
                    override fun close() {
                        cached?.recycle()
                        cached = null
                        bmp.recycle()
                    }
                }
            }
            MattingBackgroundMode.VIDEO -> {
                val uri = settings.replacementBackgroundUri
                    ?: throw AiExportException("Background video is not set. Pick a video first.")
                val sampler = BgVideoSampler(context, Uri.parse(uri))
                object : BgSource {
                    var cached: Bitmap? = null
                    var cachedKey = ""
                    override fun frameFor(tsMs: Long, w: Int, h: Int): Bitmap {
                        val raw = sampler.frameAt(tsMs)
                            ?: throw AiExportException("Cannot decode the background video.")
                        val key = "$w x $h @${System.identityHashCode(raw)}"
                        val c = cached
                        if (c != null && !c.isRecycled && cachedKey == key) return c
                        c?.recycle()
                        val scaled = if (raw.width == w && raw.height == h) {
                            // Copy: the sampler owns `raw` and may
                            // recycle it on the next frame.
                            raw.copy(Bitmap.Config.ARGB_8888, false) ?: raw
                        } else {
                            Bitmap.createScaledBitmap(raw, w, h, true)
                        }
                        cached = scaled
                        cachedKey = key
                        return scaled
                    }
                    override fun close() {
                        cached?.recycle()
                        cached = null
                        sampler.close()
                    }
                }
            }
            MattingBackgroundMode.TRANSPARENT -> {
                // 2026-10-27 — the removed background is filled with the
                // project's canvas colour (black by default; the user's
                // swatch on a coloured canvas; black on the un-baked
                // blurred canvas — see `CanvasLetterbox.fadeColorArgb`,
                // the same resolver the letterbox bars use). The cache
                // shape is identical to COLOR's.
                val color = canvasColorArgb
                object : BgSource {
                    var cached: Bitmap? = null
                    override fun frameFor(tsMs: Long, w: Int, h: Int): Bitmap {
                        val c = cached
                        if (c != null && !c.isRecycled && c.width == w && c.height == h) return c
                        c?.recycle()
                        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        b.eraseColor(color)
                        cached = b
                        return b
                    }
                    override fun close() {
                        cached?.recycle()
                        cached = null
                    }
                }
            }
        }
    }

    private fun compositeFrame(
        src: Bitmap,
        mask: Bitmap?,
        bg: BgSource,
        settings: com.aicreatorstudio.app.model.MattingSettings,
        tsMs: Long
    ): Bitmap {
        // ORIGINAL is the mask-preview-only mode (no replacement is
        // drawn) and a missing mask is a fail-open pass-through for
        // every mode — painting a mask-less frame with the canvas fill
        // would read as a black flash, not as a background removal.
        // TRANSPARENT reaches the composite path since 2026-10-27.
        if (settings.backgroundMode == MattingBackgroundMode.ORIGINAL || mask == null) {
            return src // pass-through (owned by pipeline; do not copy)
        }
        val w = src.width
        val h = src.height
        val bgFrame: Bitmap = when (settings.backgroundMode) {
            MattingBackgroundMode.BLUR -> fastBlur(src, w, h)
            else -> bg.frameFor(tsMs, w, h)
        }
        try {
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val srcPx = IntArray(w * h)
            val bgPx = IntArray(w * h)
            val maskPx = IntArray(w * h)
            src.getPixels(srcPx, 0, w, 0, 0, w, h)
            bgFrame.getPixels(bgPx, 0, w, 0, 0, w, h)
            mask.getPixels(maskPx, 0, w, 0, 0, w, h)
            val t = settings.threshold.coerceIn(0.05f, 0.95f)
            // Soft knee around the threshold: [t*0.6, t*0.6+0.4]
            // keeps hair/soft edges as partial alpha.
            val lo = t * 0.6f
            val hi = (lo + 0.4f).coerceAtMost(1f)
            val outPx = IntArray(w * h)
            for (i in srcPx.indices) {
                val a = ((maskPx[i] ushr 24) and 0xFF) / 255f
                val k = ((a - lo) / (hi - lo).coerceAtLeast(1e-3f)).coerceIn(0f, 1f)
                val kk = k * k * (3f - 2f * k) // smoothstep
                val s = srcPx[i]
                val b = bgPx[i]
                val r = (((s shr 16) and 0xFF) * kk + ((b shr 16) and 0xFF) * (1f - kk)).toInt()
                val g = (((s shr 8) and 0xFF) * kk + ((b shr 8) and 0xFF) * (1f - kk)).toInt()
                val bl = ((s and 0xFF) * kk + (b and 0xFF) * (1f - kk)).toInt()
                outPx[i] = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or
                    (g.coerceIn(0, 255) shl 8) or bl.coerceIn(0, 255)
            }
            out.setPixels(outPx, 0, w, 0, 0, w, h)
            return out
        } finally {
            // BLUR frames are caller-owned; every other mode's
            // frames are cached inside the BgSource.
            if (bg.callerOwnsFrames) bgFrame.recycle()
        }
    }

    /** Cheap real blur: 1/8 downscale + bilinear upscale. */
    private fun fastBlur(src: Bitmap, w: Int, h: Int): Bitmap {
        val small = Bitmap.createScaledBitmap(src, (w / 8).coerceAtLeast(2), (h / 8).coerceAtLeast(2), true)
        val back = Bitmap.createScaledBitmap(small, w, h, true)
        small.recycle()
        return back
    }

    // ----------------------------------------------------------------
    // pre: motion-tracking overlay burn-in
    // ----------------------------------------------------------------

    /**
     * Bake the tracked overlay (and/or blur/mosaic mask) into the
     * segment, using the **same** [VideoFramePipeline] the reframe pass
     * uses.
     *
     * ## Why `FrameOp` and not FFmpeg or a Media3 overlay
     *
     * - **FFmpeg** would need an `overlay` filter per keyframe interval
     *   (hundreds of filters) or a `sendcmd` script, plus a second
     *   decode path whose frame space would have to be *proven*
     *   identical to the tracker's. It is not even a dependency of this
     *   project.
     * - **A Media3 `BitmapOverlay`** is the elegant option and is what
     *   the fade washes use, but it forces the overlay to be a static
     *   bitmap and calls `getBitmap` on the GL thread — where
     *   `CaptionRenderer`'s `StaticLayout` pass cannot legally run. The
     *   text would then be rasterized by a *different* renderer than
     *   the preview's, breaking Persian shaping and RTL parity.
     *
     * `FrameOp` avoids both: it runs on an IO thread, it can call the
     * shared Persian-aware renderer, and it operates on frames from the
     * exact decoder the tracker analysed with — so analysis-time and
     * bake-time coordinates cannot diverge.
     *
     * ## Why the whole file is re-transcoded
     *
     * The overlay must be composited per frame, so there is no shortcut
     * around a full pass. Audio is passed through sample-for-sample and
     * presentation timestamps flow straight from decoder to encoder, so
     * duration, FPS and A/V sync are preserved by construction — the
     * same guarantee the reframe and matting passes rely on.
     */
    private suspend fun burnTrackedOverlay(
        segment: RenderSegment,
        srcUri: String,
        onProgress: (Float) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val track = segment.motionTrack ?: return@withContext srcUri
        val keys = segment.cropKeyframes.sortedBy { it.timeMs }
        if (keys.isEmpty()) return@withContext srcUri

        val info = VideoFramePipeline.probe(context, Uri.parse(srcUri))
            ?: throw AiExportException("Cannot read video metadata for Motion Tracking export.")

        val renderer = TrackedOverlayRenderer(
            context = context,
            overlay = segment.trackingOverlay
                ?: EMPTY_TRACKING_OVERLAY,
            settings = track,
            keyframes = keys,
            outputWidthPx = info.displayWidth,
            outputHeightPx = info.displayHeight
        )

        val tmp = newTemp("track-${segment.clipId}")
        try {
            val res = VideoFramePipeline.transcode(
                context,
                VideoFramePipeline.Request(
                    srcUri = Uri.parse(srcUri),
                    dstFile = tmp,
                    outWidth = info.displayWidth,
                    outHeight = info.displayHeight,
                    frameOp = VideoFramePipeline.FrameOp { src, ptsUs, _ ->
                        // Mutates and returns `src`. Returning a new
                        // bitmap would make the pipeline recycle the
                        // original and allocate a full-size replacement
                        // every frame — the fastest way to OOM a 4K
                        // export.
                        renderer.drawAt(src, ptsUs)
                    },
                    copyAudio = true,
                    onProgress = { done, total ->
                        onProgress(done.toFloat() / total.coerceAtLeast(1))
                    },
                    isCancelled = pipelineCancelCheck()
                )
            )
            when (res) {
                is VideoFramePipeline.Result.Done -> Uri.fromFile(tmp).toString()
                is VideoFramePipeline.Result.Cancelled ->
                    throw AiExportException("Export cancelled.")
                is VideoFramePipeline.Result.Failed ->
                    throw AiExportException(
                        res.cause.message ?: "Motion Tracking render failed."
                    )
            }
        } catch (t: AiExportException) {
            runCatching { tmp.delete() }
            throw t
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            throw AiExportException(t.message ?: "Motion Tracking render failed.")
        }
    }

    // ----------------------------------------------------------------
    // pre: picture-in-picture overlays (with shape masks)
    // ----------------------------------------------------------------

    /**
     * 2026-10-13 — composite every picture-in-picture overlay onto the
     * segment, per frame, through the same [VideoFramePipeline] the
     * matting, reframe and tracking passes use.
     *
     * ## Why this is the only honest route
     *
     * Media3 1.5.0 has no public video-over-video compositing API.
     * `OverlayEffect` / `TextureOverlay` / `BitmapOverlay` take a
     * **bitmap**, and `getBitmap` runs on the GL thread — where decoding
     * the overlay's own video per frame is both illegal and ruinously
     * slow. A `GlEffect` with a second sampler would mean writing a GL
     * shader program and a second decoder feeding it; `FrameOp` reaches
     * the same result with code this project already trusts, on an IO
     * thread, with audio passed through sample-for-sample.
     *
     * ## Parity
     *
     * Placement and shape come from [OverlayPipGeometry] and
     * [OverlayMaskRenderer], which read the same normalised outline the
     * Compose preview reads. Nothing here re-derives geometry.
     *
     * The pass is a strict no-op when [RenderSegment.mediaOverlays] has
     * nothing overlapping the segment — `prepareSourceUri` does not even
     * call it in that case.
     */
    private suspend fun burnMediaOverlays(
        segment: RenderSegment,
        srcUri: String,
        onProgress: (Float) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val info = VideoFramePipeline.probe(context, Uri.parse(srcUri))
            ?: throw AiExportException("Cannot read video metadata for the overlay export.")

        val renderer = OverlayCompositeRenderer(
            context = context,
            overlays = segment.mediaOverlays,
            outputWidthPx = info.displayWidth,
            outputHeightPx = info.displayHeight,
            timelineStartMs = segment.timelineStartMs,
            sourceStartMs = segment.sourceStartMs,
            sourceDurationMs = segment.sourceDurationMs,
            outputDurationMs = segment.outputDurationMs
        )
        if (!renderer.hasContent) {
            renderer.close()
            return@withContext srcUri
        }

        val tmp = newTemp("overlay-${segment.clipId}")
        try {
            val res = VideoFramePipeline.transcode(
                context,
                VideoFramePipeline.Request(
                    srcUri = Uri.parse(srcUri),
                    dstFile = tmp,
                    outWidth = info.displayWidth,
                    outHeight = info.displayHeight,
                    frameOp = VideoFramePipeline.FrameOp { src, ptsUs, _ ->
                        // Mutates and returns `src`. See `burnTrackedOverlay`
                        // for why returning a new bitmap would OOM a 4K
                        // export.
                        renderer.drawAt(src, ptsUs)
                    },
                    copyAudio = true,
                    onProgress = { done, total ->
                        onProgress(done.toFloat() / total.coerceAtLeast(1))
                    },
                    isCancelled = pipelineCancelCheck()
                )
            )
            when (res) {
                is VideoFramePipeline.Result.Done -> Uri.fromFile(tmp).toString()
                is VideoFramePipeline.Result.Cancelled ->
                    throw AiExportException("Export cancelled.")
                is VideoFramePipeline.Result.Failed ->
                    throw AiExportException(
                        res.cause.message ?: "Overlay render failed."
                    )
            }
        } catch (t: AiExportException) {
            runCatching { tmp.delete() }
            throw t
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            throw AiExportException(t.message ?: "Overlay render failed.")
        } finally {
            // The sampler holds a decoder and up to one bitmap; the export
            // can run this pass once per segment, so leaving them open
            // would leak a codec per clip.
            runCatching { renderer.close() }
        }
    }

    // ----------------------------------------------------------------
    // pre: auto-frame reframe
    // ----------------------------------------------------------------

    private suspend fun reframe(
        segment: RenderSegment,
        srcUri: String,
        onProgress: (Float) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val keys = segment.cropKeyframes.sortedBy { it.timeMs }
        if (keys.isEmpty()) return@withContext srcUri
        val auto = segment.autoFrame ?: return@withContext srcUri
        val info = VideoFramePipeline.probe(context, Uri.parse(srcUri))
            ?: throw AiExportException("Cannot read video metadata for Auto Frame export.")
        val (outW, outH) = reframeOutputSize(
            info.displayWidth, info.displayHeight,
            auto.targetAspectWidth, auto.targetAspectHeight
        )
        val tmp = newTemp("reframe-${segment.clipId}")
        try {
            val res = VideoFramePipeline.transcode(
                context,
                VideoFramePipeline.Request(
                    srcUri = Uri.parse(srcUri),
                    dstFile = tmp,
                    outWidth = outW,
                    outHeight = outH,
                    frameOp = VideoFramePipeline.FrameOp { src, ptsUs, _ ->
                        val center = interpolateKeys(keys, ptsUs / 1000L)
                        val win = cropWindowFor(
                            src.width, src.height,
                            auto.targetAspectWidth, auto.targetAspectHeight, center
                        )
                        val cropped = Bitmap.createBitmap(src, win.left, win.top, win.width, win.height)
                        // Scale to the exact output (equals crop size
                        // by construction; scale is a no-op guard).
                        if (cropped.width == outW && cropped.height == outH) cropped
                        else {
                            val s = Bitmap.createScaledBitmap(cropped, outW, outH, true)
                            cropped.recycle()
                            s
                        }
                    },
                    copyAudio = true,
                    onProgress = { done, total -> onProgress(done.toFloat() / total.coerceAtLeast(1)) },
                    isCancelled = pipelineCancelCheck()
                )
            )
            when (res) {
                is VideoFramePipeline.Result.Done -> Uri.fromFile(tmp).toString()
                is VideoFramePipeline.Result.Cancelled -> throw AiExportException("Export cancelled.")
                is VideoFramePipeline.Result.Failed ->
                    throw AiExportException(res.cause.message ?: "Auto Frame render failed.")
            }
        } catch (t: AiExportException) {
            runCatching { tmp.delete() }
            throw t
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            throw AiExportException(t.message ?: "Auto Frame render failed.")
        }
    }

    /**
     * Interpolate a trajectory's centre at [tsMs].
     *
     * 2026-09-28 — now delegates to
     * [com.aicreatorstudio.app.model.util.MotionTrackingGeometry.interpolateKeyframe]
     * rather than carrying its own copy of the arithmetic.
     *
     * There were two reasons to collapse these. First, the reframe pass
     * and the tracked-overlay pass must agree frame for frame about
     * where the subject is: if a tracked overlay is attached to a
     * *reframed* clip, the box the overlay uses has to be the same box
     * the reframe cropped to, or the sticker sits off the subject by
     * exactly the interpolation difference. Second, the shared function
     * also interpolates extent and confidence, which the Auto Frame
     * path does not read — so one implementation genuinely serves both
     * without either growing a branch.
     *
     * The public shape is kept as a `Pair` so the existing `reframe`
     * call site and its tests are unaffected.
     */
    internal fun interpolateKeys(keys: List<CropKeyframe>, tsMs: Long): Pair<Float, Float> {
        if (keys.isEmpty()) return Pair(0.5f, 0.5f)
        val k = MotionTrackingGeometry.interpolateKeyframe(keys, tsMs)
        return Pair(k.centerX, k.centerY)
    }

    internal fun reframeOutputSize(srcW: Int, srcH: Int, targetW: Float, targetH: Float): Pair<Int, Int> {
        val tw = targetW.coerceAtLeast(0.01f)
        val th = targetH.coerceAtLeast(0.01f)
        val aspect = tw / th
        val srcAspect = srcW.toFloat() / srcH
        val (w, h) = if (srcAspect > aspect) {
            val hh = srcH
            val ww = (hh * aspect).toInt()
            ww to hh
        } else {
            val ww = srcW
            val hh = (ww / aspect).toInt()
            ww to hh
        }
        // Encoders need even dimensions.
        return (w.coerceAtLeast(2) and 1.inv()) to (h.coerceAtLeast(2) and 1.inv())
    }

    internal fun cropWindowFor(
        srcW: Int,
        srcH: Int,
        targetW: Float,
        targetH: Float,
        center: Pair<Float, Float>
    ): com.aicreatorstudio.app.model.CropWindow {
        // 2026-10-17 — the body moved to
        // [ReframePlanner.cropWindowFor] so the Compose preview and this
        // hook share ONE crop implementation instead of mirrors that
        // could drift (the planner KDoc already admitted the split was
        // a known cost). Semantics unchanged; an empty/invalid source
        // keeps this hook's tolerant "no crop" result (the planner's
        // version throws by contract — probe() already rejects those).
        if (srcW <= 0 || srcH <= 0) {
            return com.aicreatorstudio.app.model.CropWindow(0, 0, srcW.coerceAtLeast(1), srcH.coerceAtLeast(1))
        }
        val w = ReframePlanner.cropWindowFor(
            srcW, srcH, targetW, targetH, center.first, center.second
        )
        return com.aicreatorstudio.app.model.CropWindow(w.left, w.top, w.width, w.height)
    }

    // ----------------------------------------------------------------
    // post: voice-clean audio swap
    // ----------------------------------------------------------------

    private data class WavInfo(val sampleRate: Int, val channels: Int, val pcm: ByteArray)

    private fun readWav(file: File): WavInfo? {
        return runCatching {
            val bytes = file.readBytes()
            if (bytes.size <= 44) return null
            fun strAt(o: Int): String = String(bytes, o, 4, Charsets.US_ASCII)
            if (strAt(0) != "RIFF" || strAt(8) != "WAVE") return null
            var pos = 12
            var rate = 0
            var ch = 0
            var bits = 0
            var format = 0
            var dataOff = -1
            var dataLen = 0
            while (pos + 8 <= bytes.size) {
                val id = strAt(pos)
                val len = ((bytes[pos + 4].toInt() and 0xFF)) or
                    ((bytes[pos + 5].toInt() and 0xFF) shl 8) or
                    ((bytes[pos + 6].toInt() and 0xFF) shl 16) or
                    ((bytes[pos + 7].toInt() and 0xFF) shl 24)
                if (id == "fmt " && len >= 16) {
                    format = (bytes[pos + 8].toInt() and 0xFF) or ((bytes[pos + 9].toInt() and 0xFF) shl 8)
                    ch = (bytes[pos + 10].toInt() and 0xFF) or ((bytes[pos + 11].toInt() and 0xFF) shl 8)
                    rate = (bytes[pos + 12].toInt() and 0xFF) or
                        ((bytes[pos + 13].toInt() and 0xFF) shl 8) or
                        ((bytes[pos + 14].toInt() and 0xFF) shl 16) or
                        ((bytes[pos + 15].toInt() and 0xFF) shl 24)
                    bits = (bytes[pos + 22].toInt() and 0xFF) or ((bytes[pos + 23].toInt() and 0xFF) shl 8)
                } else if (id == "data") {
                    dataOff = pos + 8
                    dataLen = min(len, bytes.size - dataOff)
                }
                pos += 8 + len + (len and 1)
            }
            if (format != 1 || bits != 16 || rate <= 0 || ch <= 0 || dataOff < 0 || dataLen <= 0) return null
            WavInfo(rate, ch, bytes.copyOfRange(dataOff, dataOff + dataLen))
        }.getOrNull()
    }

    /**
     * Remux: copy the video track from [srcVideoUri] untouched,
     * AAC-encode the cleaned [wav], interleave by PTS. Returns true
     * on success (output at [dst]).
     */
    private fun remuxWithCleanAudio(srcVideoUri: Uri, wav: File, dst: File): Boolean {
        val info = readWav(wav) ?: run {
            Log.w(TAG, "cannot parse cleaned wav ${wav.absolutePath}")
            return false
        }
        val vEx = MediaExtractor()
        var vCodec: MediaCodec? = null
        var aCodec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            vEx.setDataSource(context, srcVideoUri, null)
            var vTrack = -1
            for (i in 0 until vEx.trackCount) {
                val f = vEx.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/") && vTrack == -1) {
                    vTrack = i
                }
            }
            if (vTrack == -1) return false
            vEx.selectTrack(vTrack)
            val vFormat = vEx.getTrackFormat(vTrack)

            // AAC encoder for the cleaned PCM.
            val aFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, info.sampleRate, info.channels
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_AAC_PROFILE,
                    android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }
            aCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            aCodec.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            aCodec.start()

            muxer = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vOut = muxer.addTrack(vFormat)
            val aOut = muxer.addTrack(aCodec.outputFormat)
            muxer.start()

            // Feed all cleaned PCM into the AAC encoder first,
            // collecting output; then interleave video + audio by PTS.
            val encodedAudio = ArrayList<Pair<Long, ByteArray>>()
            var pcmOff = 0
            var audioPtsUs = 0L
            val bytesPerFrame = info.channels * 2
            val bytesPerSec = info.sampleRate.toLong() * bytesPerFrame
            var encoderEos = false
            val bInfo = MediaCodec.BufferInfo()
            // Prime: queue all input.
            while (pcmOff < info.pcm.size) {
                val idx = aCodec.dequeueInputBuffer(10_000)
                if (idx < 0) break
                val buf = aCodec.getInputBuffer(idx)!!
                buf.clear()
                val n = min(buf.remaining(), info.pcm.size - pcmOff)
                buf.put(info.pcm, pcmOff, n)
                val frames = n / bytesPerFrame
                val durUs = frames * 1_000_000L / info.sampleRate
                val flags = if (pcmOff + n >= info.pcm.size) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                aCodec.queueInputBuffer(idx, 0, n, audioPtsUs, flags)
                audioPtsUs += durUs
                pcmOff += n
                if (flags != 0) break
            }
            if (pcmOff >= info.pcm.size && pcmOff == 0) {
                // Empty WAV (shouldn't happen — guarded upstream).
                return false
            }
            // If input wasn't fully queued (starved), finish it now.
            while (pcmOff < info.pcm.size) {
                val idx = aCodec.dequeueInputBuffer(10_000)
                if (idx < 0) continue
                val buf = aCodec.getInputBuffer(idx)!!
                buf.clear()
                val n = min(buf.remaining(), info.pcm.size - pcmOff)
                buf.put(info.pcm, pcmOff, n)
                val frames = n / bytesPerFrame
                val durUs = frames * 1_000_000L / info.sampleRate
                val flags = if (pcmOff + n >= info.pcm.size) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                aCodec.queueInputBuffer(idx, 0, n, audioPtsUs, flags)
                audioPtsUs += durUs
                pcmOff += n
            }
            // Drain AAC output.
            while (!encoderEos) {
                val st = aCodec.dequeueOutputBuffer(bInfo, 10_000)
                when {
                    st == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    st == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    st >= 0 -> {
                        if (bInfo.size > 0) {
                            val buf = aCodec.getOutputBuffer(st)!!
                            val bytes = ByteArray(bInfo.size)
                            buf.get(bytes)
                            encodedAudio += Pair(bInfo.presentationTimeUs, bytes)
                        }
                        if (bInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderEos = true
                        aCodec.releaseOutputBuffer(st, false)
                    }
                    else -> Unit
                }
            }

            // Interleave video + AAC by PTS.
            val vInfo = MediaCodec.BufferInfo()
            var vDone = false
            var aIdx = 0
            val vChunk = ByteBuffer.allocateDirect(512 * 1024)
            while (!vDone || aIdx < encodedAudio.size) {
                val vPts = if (!vDone) {
                    vChunk.clear()
                    val n = vEx.readSampleData(vChunk, 0)
                    if (n < 0) {
                        vDone = true
                        Long.MAX_VALUE
                    } else {
                        vInfo.set(0, n, vEx.sampleTime, vEx.sampleFlags)
                        vEx.sampleTime
                    }
                } else Long.MAX_VALUE
                val aPts = if (aIdx < encodedAudio.size) encodedAudio[aIdx].first else Long.MAX_VALUE
                if (aPts <= vPts && aIdx < encodedAudio.size) {
                    val (pts, bytes) = encodedAudio[aIdx++]
                    val bb = ByteBuffer.wrap(bytes)
                    bInfo.set(0, bytes.size, pts, 0)
                    muxer.writeSampleData(aOut, bb, bInfo)
                } else if (!vDone) {
                    vChunk.flip()
                    // vChunk position handling: readSampleData wrote
                    // n bytes from 0; duplicate a sized view.
                    vChunk.position(0)
                    vChunk.limit(vInfo.size)
                    muxer.writeSampleData(vOut, vChunk, vInfo)
                    vEx.advance()
                } else break
            }
            runCatching { muxer.stop() }
            return dst.length() > 0
        } catch (t: Throwable) {
            Log.w(TAG, "remuxWithCleanAudio failed", t)
            return false
        } finally {
            runCatching { vCodec?.stop() }
            runCatching { vCodec?.release() }
            runCatching { aCodec?.stop() }
            runCatching { aCodec?.release() }
            runCatching { muxer?.release() }
            runCatching { vEx.release() }
        }
    }

    private fun readBackUri(target: OutputTarget, outputPath: String): Uri? {
        target.outputUriOrNull()?.let { return it }
        return runCatching { Uri.fromFile(File(outputPath)) }.getOrNull()
    }

    private suspend fun replaceTargetWithFile(target: OutputTarget, tmp: File): Boolean {
        return try {
            val ok = target.replaceWithFile(tmp)
            runCatching { tmp.delete() }
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "replaceTargetWithFile failed", t)
            false
        }
    }

    private fun newTemp(prefix: String): File {
        val dir = File(context.cacheDir, "ai-export").apply { mkdirs() }
        val f = File(dir, "$prefix-${System.currentTimeMillis()}.mp4")
        sessionTemps += f
        return f
    }

    companion object {
        private const val TAG = "AiExportHooks"

        /**
         * Stand-in overlay for a **mask-only** track.
         *
         * [TrackedOverlayRenderer] needs an overlay object to construct,
         * but a blur/mosaic track has no text. An overlay with blank
         * text renders nothing through `CaptionRenderer` (it returns an
         * empty bitmap early), so the renderer's stamp is null and only
         * the mask pass draws. Passing null instead would force a
         * nullable field through the whole renderer for no benefit.
         */
        private val EMPTY_TRACKING_OVERLAY =
            com.aicreatorstudio.app.model.TextOverlay(
                text = "",
                startTimeMs = 0L,
                endTimeMs = Long.MAX_VALUE
            )
    }
}

/** User-facing export failure with a precise cause. */
class AiExportException(message: String) : Exception(message)

/**
 * Sequential background-video sampler for the VIDEO bg mode.
 * Decodes the looping background once, serving rotation-corrected
 * bitmaps at requested source timestamps (modulo bg duration).
 * Returned bitmaps are owned by the sampler (recycled by [close]
 * or replaced on advance); callers must copy, not recycle.
 */
private class BgVideoSampler(
    private val context: Context,
    private val uri: Uri
) : AutoCloseable {
    private val extractor = MediaExtractor()
    private var decoder: MediaCodec? = null
    private var trackFormat: MediaFormat? = null
    private var bgW = 0
    private var bgH = 0
    private var rotation = 0
    private var bgDurationMs = 0L
    private var eos = false
    private var nextPtsUs = 0L
    private var lastBmp: Bitmap? = null
    private var lastPtsMs = -1L
    private var started = false

    fun frameAt(tsMs: Long): Bitmap? {
        ensureStarted() ?: return null
        val wantUs = if (bgDurationMs > 0) (tsMs % bgDurationMs) * 1000L else tsMs * 1000L
        // 2026-10-28 — real looping. The old sampler served the LAST
        // frame forever after one pass: `eos` latched and the wrap
        // ("tsMs % bgDurationMs" jumps BACK to ~0) satisfied
        // `nextPtsUs < wantUs` instantly, so the advance loop never ran
        // again. Now a demand that lands behind the consumed position
        // (only the wrap can do that — export timestamps are monotonic)
        // rewinds the extractor + flushes the decoder and the pass
        // restarts from keyframe 0.
        val wrapped = wantUs + WRAP_TOLERANCE_US < nextPtsUs
        if (eos) {
            // Tail gap (wantUs just past the last frame): keeping the
            // final frame beats rewinding for a <1-frame sample band.
            if (wrapped) rewind() else return lastBmp
        } else if (wrapped) {
            rewind()
        }
        // Advance while the decoder lags behind the target.
        var guard = 0
        while (!eos && nextPtsUs < wantUs && guard++ < 400) {
            step() ?: break
        }
        // Starve-proof: a freshly started decoder produces no output for
        // its first ~few input buffers; keep stepping (bounded) so an
        // early frameAt near t=0 returns a frame instead of null (null
        // fails the whole export).
        while (lastBmp == null && !eos && guard++ < 400) {
            step() ?: break
        }
        return lastBmp
    }

    /** Restart the decode pass from keyframe 0 (decoder flush, seek 0). */
    private fun rewind() {
        val dec = decoder ?: return
        runCatching { dec.flush() }
        runCatching { extractor.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC) }
        eos = false
        nextPtsUs = 0L
    }

    private companion object {
        /**
         * Demands never step backwards mid-pass; only the loop wrap does
         * (a jump to ~0). Anything smaller than this is timestamp jitter
         * around the last decoded frame, not a wrap — rewinding for it
         * would restart a whole decode pass per output frame.
         */
        const val WRAP_TOLERANCE_US = 100_000L
    }

    private fun ensureStarted(): Unit? {
        if (started) return Unit
        return try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                bgW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                bgH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
                bgDurationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
            } finally {
                runCatching { retriever.release() }
            }
            if (bgW <= 0 || bgH <= 0) return null
            extractor.setDataSource(context, uri, null)
            var vt = -1
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/") && vt == -1) {
                    vt = i
                    trackFormat = f
                }
            }
            if (vt == -1) return null
            extractor.selectTrack(vt)
            val mime = trackFormat!!.getString(MediaFormat.KEY_MIME)!!
            // 2026-10-24 — byte-buffer output, no Surface/ImageReader.
            //
            // The reader path cannot produce a readable frame on a Xiaomi
            // 2107113SG (Android 14, HEVC): `Image.getPlanes()` aborts the
            // process inside `ImageReader$SurfaceImage.nativeCreatePlanes`
            // and `wrapHardwareBuffer` refuses the buffer. Byte-buffer
            // output hands out the codec's own ByteBuffers, which
            // `VideoFramePipeline.codecImageToBitmap` converts normally.
            // See `VideoFramePipeline.transcode` for the measurements.
            trackFormat!!.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            decoder = MediaCodec.createDecoderByType(mime).also {
                it.configure(trackFormat, null, null, 0)
                it.start()
            }
            started = true
            Unit
        } catch (_: Throwable) {
            null
        }
    }

    private fun step(): Unit? {
        val dec = decoder ?: return null
        val info = MediaCodec.BufferInfo()
        val inIdx = dec.dequeueInputBuffer(10_000)
        if (inIdx >= 0) {
            val buf = dec.getInputBuffer(inIdx)!!
            val n = extractor.readSampleData(buf, 0)
            if (n < 0) {
                dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            } else {
                dec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                extractor.advance()
            }
        }
        val st = dec.dequeueOutputBuffer(info, 10_000)
        if (st >= 0) {
            val isEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            if (isEos) {
                // Release without rendering: the EOS buffer carries no frame.
                dec.releaseOutputBuffer(st, false)
                eos = true
                return Unit
            }
            // 2026-10-24 — convert before releasing: the Image is only
            // valid until `releaseOutputBuffer`.
            var decoded: Bitmap? = null
            try {
                val img = dec.getOutputImage(st)
                if (img != null) {
                    try {
                        decoded = VideoFramePipeline.codecImageToBitmap(img)
                    } finally {
                        img.close()
                    }
                }
            } finally {
                dec.releaseOutputBuffer(st, false)
            }
            var bmp = decoded ?: return Unit
            if (rotation != 0) {
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (r !== bmp) bmp.recycle()
                bmp = r
            }
            lastBmp?.recycle()
            lastBmp = bmp
            nextPtsUs = info.presentationTimeUs
            lastPtsMs = nextPtsUs / 1000L
            return Unit
        }
        return Unit
    }

    override fun close() {
        runCatching { lastBmp?.recycle() }
        lastBmp = null
        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
        runCatching { extractor.release() }
    }
}
