package com.aicreatorstudio.app.ai.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.util.Log

import com.aicreatorstudio.app.model.VideoDimensions
import java.io.File
import java.nio.ByteBuffer

/**
 * Shared frame-by-frame video pipeline for the offline AI tools
 * (Upscale, Background Remove pre-render, Auto Frame pre-render).
 *
 * Consolidates what used to be three ad-hoc decode paths
 * (`MediaMetadataRetriever` thumbnails in two engines, no shared
 * encoder at all) into one streaming implementation:
 *
 * ```
 * source.mp4 -> MediaExtractor -> MediaCodec decoder -> ImageReader
 *   -> Bitmap (rotation-corrected) -> [FrameOp] -> encoder Surface
 *   -> MediaCodec AVC encoder -> MediaMuxer (+ audio passthrough)
 *   -> output.mp4
 * ```
 *
 * ## Guarantees
 *
 * - **Memory-safe:** one decoded frame + one output frame in flight.
 *   Large/tiled work (e.g. ESRGAN tiles) recycles inside [FrameOp].
 * - **Metadata-preserving:** presentation timestamps flow straight
 *   from decoder to encoder, so FPS + duration are unchanged; the
 *   audio track is copied sample-for-sample (no re-encode), so A/V
 *   sync and unrelated audio are untouched.
 * - **Rotation-aware:** `rotation` metadata is read via
 *   [MediaMetadataRetriever] and applied to every decoded bitmap;
 *   output dimensions are swapped for 90/270 sources.
 * - **Real progress:** `onProgress(framesDone, estimatedTotal)`.
 * - **Cancellation:** [Request.cancel] is polled every frame; on
 *   cancel the partial output is deleted and codecs released.
 * - **No originals harmed:** the source is opened read-only; output
 *   goes to a caller-provided temp file that is only renamed/moved
 *   by the caller after success.
 *
 * Everything runs on the calling thread (callers use
 * `Dispatchers.IO`); nothing touches the main thread.
 */
object VideoFramePipeline {

    private const val TAG = "VideoFramePipeline"
    private const val MIME_AVC = "video/avc"
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /** Source stream facts needed to size the pipeline. */
    data class SourceInfo(
        val width: Int,
        val height: Int,
        /** 0/90/180/270 (probe() normalizes). */
        val rotation: Int,
        val durationMs: Long,
        /** May be 0 when the container omits it. */
        val frameRate: Int,
        val videoMime: String,
        val hasAudioTrack: Boolean,
        /** Rotation-corrected display size. Normalized via
         *  [VideoDimensions.isQuarterTurn] so a raw `-90`/`450` still
         *  swaps the axes exactly like the corrected rotation matrix
         *  the decode loop applies. */
        val displayWidth: Int = if (VideoDimensions.isQuarterTurn(rotation)) height else width,
        val displayHeight: Int = if (VideoDimensions.isQuarterTurn(rotation)) width else height
    )

    /**
     * Per-frame transform. Receives a rotation-corrected, caller-
     * owned `ARGB_8888` bitmap and returns the bitmap to encode
     * (may return the input, or a new bitmap — the pipeline
     * recycles the input when a different instance is returned).
     * Throwing aborts the job with the thrown cause.
     */
    fun interface FrameOp {
        fun process(src: Bitmap, presentationTimeUs: Long, frameIndex: Int): Bitmap
    }

    data class Request(
        val srcUri: Uri,
        val dstFile: File,
        /** Output size. Defaults to the source display size. */
        val outWidth: Int = 0,
        val outHeight: Int = 0,
        val frameOp: FrameOp,
        val copyAudio: Boolean = true,
        /** Target video bitrate. 0 = auto from pixel count. */
        val bitrateBps: Int = 0,
        val onProgress: (framesDone: Int, estimatedTotal: Int) -> Unit = { _, _ -> },
        /** Polled every frame; true aborts with [Cancelled]. */
        val isCancelled: () -> Boolean = { false }
    )

    sealed interface Result {
        data class Done(val frames: Int, val outWidth: Int, val outHeight: Int) : Result
        data class Failed(val cause: Throwable) : Result
        data object Cancelled : Result
    }

    class PipelineException(message: String, cause: Throwable? = null) : Exception(message, cause)
    class CancelledException : Exception("cancelled")

    /** Read-only probe. Never throws — returns null on failure. */
    fun probe(context: Context, uri: Uri): SourceInfo? {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val rot = VideoDimensions.normalizeRotation(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        ?.toIntOrNull() ?: 0
                )
                // Frame rate + mime come from the extractor (more reliable).
                var mime = "video/avc"
                var fps = 0
                var hasAudio = false
                // 2026-10-21 — the CODED size comes from the track
                // format, NOT from MediaMetadataRetriever.
                //
                // AOSP's retriever reports METADATA_KEY_VIDEO_WIDTH /
                // _HEIGHT as the **display** size: FFmpegMetadataRetriever
                // swaps them for a 90/270 rotation before returning.
                // The decoder, meanwhile, writes the **coded** (and
                // cropped) size. Sizing the ImageReader from the retriever
                // therefore produced a reader whose planes did not match
                // the buffer the decoder wrote — and on a portrait clip
                // that mismatch made
                // `ImageReader.nativeCreatePlanes` hand ART a plane with
                // a null address and a capacity of 1, which CheckJNI
                // turns into an immediate `abort()`:
                //
                //   "JNI DETECTED ERROR IN APPLICATION: non-zero capacity
                //    for nullptr pointer: 1 in call to NewDirectByteBuffer"
                //
                // A native abort cannot be caught, so the whole process
                // died — Background Remove simply vanished the app on
                // the affected device. The track format is the only
                // authoritative source for "what will the decoder
                // write", so it is the only source used here.
                var codedW = 0
                var codedH = 0
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(context, uri, null)
                    for (i in 0 until ex.trackCount) {
                        val f = ex.getTrackFormat(i)
                        val m = f.getString(MediaFormat.KEY_MIME) ?: ""
                        when {
                            m.startsWith("video/") && fps == 0 -> {
                                mime = m
                                fps = runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE) }
                                    .getOrDefault(0)
                                val size = decoderOutputSize(f)
                                codedW = size.first
                                codedH = size.second
                            }
                            m.startsWith("audio/") -> hasAudio = true
                        }
                    }
                } finally {
                    runCatching { ex.release() }
                }
                // Fall back to the retriever only when the container
                // exposes no usable track format (rare).
                if (codedW <= 0 || codedH <= 0) {
                    val rw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        ?.toIntOrNull() ?: 0
                    val rh = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        ?.toIntOrNull() ?: 0
                    if (rw <= 0 || rh <= 0) return null
                    // The retriever's values are the DISPLAY size; undo
                    // the quarter-turn swap to get back the coded size.
                    codedW = if (VideoDimensions.isQuarterTurn(rot)) rh else rw
                    codedH = if (VideoDimensions.isQuarterTurn(rot)) rw else rh
                }
                SourceInfo(codedW, codedH, rot, dur, fps, mime, hasAudio)
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrNull()
    }

    /**
     * The size a decoder writes for a given track format: the **coded**
     * size, reduced by the track's crop when it declares one.
     *
     * The crop is what the decoder actually renders — a track that
     * declares `KEY_CROP_*` produces frames of the cropped size even
     * though `KEY_WIDTH`/`KEY_HEIGHT` still carry the coded size.
     */
    fun decoderOutputSize(format: MediaFormat): Pair<Int, Int> {
        var w = runCatching { format.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(0)
        var h = runCatching { format.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(0)
        val hasCrop = format.containsKey(MediaFormat.KEY_CROP_LEFT) &&
            format.containsKey(MediaFormat.KEY_CROP_RIGHT) &&
            format.containsKey(MediaFormat.KEY_CROP_TOP) &&
            format.containsKey(MediaFormat.KEY_CROP_BOTTOM)
        if (hasCrop) {
            val l = runCatching { format.getInteger(MediaFormat.KEY_CROP_LEFT) }.getOrDefault(0)
            val t = runCatching { format.getInteger(MediaFormat.KEY_CROP_TOP) }.getOrDefault(0)
            val r = runCatching { format.getInteger(MediaFormat.KEY_CROP_RIGHT) }.getOrDefault(0)
            val b = runCatching { format.getInteger(MediaFormat.KEY_CROP_BOTTOM) }.getOrDefault(0)
            if (r > l && b > t) {
                w = r - l
                h = b - t
            }
        }
        return w to h
    }

    /**
     * Run the full transcode. The caller owns [Request.dstFile]'s
     * parent directory; on [Result.Failed]/[Result.Cancelled] the
     * partial file is deleted.
     */
    fun transcode(context: Context, req: Request): Result {
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        val extractor = MediaExtractor()
        try {
            val info = probe(context, req.srcUri)
                ?: return Result.Failed(PipelineException("Cannot read video metadata"))
            if (req.isCancelled()) return Result.Cancelled

            val requestedW = (if (req.outWidth > 0) req.outWidth else info.displayWidth)
                .coerceAtLeast(2) and 1.inv()
            val requestedH = (if (req.outHeight > 0) req.outHeight else info.displayHeight)
                .coerceAtLeast(2) and 1.inv()

            // ---- encoder selection ----
            //
            // 2026-10-20 — the encoder, its color format and the
            // output size are now chosen as ONE decision, against the
            // device's real capabilities.
            //
            // The old code probed every AVC encoder for a YUV420
            // format, then instantiated the platform's DEFAULT AVC
            // encoder and configured it with whatever the probe
            // returned. On a device whose default AVC encoder is the
            // software `c2.android.avc.encoder` (flexible-format
            // only) the probe answered "planar" and the configure call
            // threw — the AI Upscale / Motion Tracking pipeline died
            // on its first frame. The resolution was never checked at
            // all, so a 4K request also threw on encoders capped at
            // 1080p.
            //
            // See [VideoEncoderCapability] for the full rationale.
            val encoderSelection = VideoEncoderCapability.select(
                options = VideoEncoderCapability.listEncoders(MIME_AVC),
                requestedWidth = requestedW,
                requestedHeight = requestedH
            ) ?: return Result.Failed(
                PipelineException(
                    "No AVC encoder on this device accepts YUV420 byte-buffer input"
                )
            )
            val outW = encoderSelection.width
            val outH = encoderSelection.height
            if (outW != requestedW || outH != requestedH) {
                Log.w(
                    TAG,
                    "encoder ${encoderSelection.codecName} cannot do " +
                        "${requestedW}x$requestedH; clamped to ${outW}x$outH"
                )
            }

            // ---- decoder setup (ImageReader output) ----
            extractor.setDataSource(context, req.srcUri, null)
            var videoTrack = -1
            var srcFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (m.startsWith("video/") && videoTrack == -1) {
                    videoTrack = i
                    srcFormat = f
                }
            }
            if (videoTrack == -1 || srcFormat == null) {
                return Result.Failed(PipelineException("No video track found"))
            }
            extractor.selectTrack(videoTrack)

            // ---- decoder setup: ByteBuffer output, no Surface ----
            //
            // 2026-10-24 — this used to be a decoder -> ImageReader
            // surface. Measured on a Xiaomi 2107113SG (Android 14, HEVC
            // through `c2.qti.hevc.decoder`) that path cannot deliver a
            // single readable frame:
            //
            // - default reader usage: `Image.getHardwareBuffer()` exists
            //   but `Bitmap.wrapHardwareBuffer` refuses it ("usage flags
            //   must contain USAGE_GPU_SAMPLED_IMAGE"), and
            //   `Image.getPlanes()` aborts the process inside
            //   `ImageReader$SurfaceImage.nativeCreatePlanes`
            //   ("non-zero capacity for nullptr pointer: 1");
            // - explicit `USAGE_GPU_SAMPLED_IMAGE or USAGE_CPU_READ_OFTEN`
            //   (the reader's allocated usage was 0x103): the hardware
            //   buffer is now null, and the plane path *still* aborts with
            //   the same JNI error - so no usage flag makes a surface
            //   frame readable on that driver.
            //
            // Byte-buffer output (a null Surface) does not go through
            // `SurfaceImage` at all: the decoder hands out its own
            // ByteBuffers and `Image.getPlanes()` on those is an ordinary
            // direct-buffer read. [codecImageToBitmap] converts them; it
            // deliberately does not use [imageToBitmap], whose GPU/surface
            // policy is about surface images.
            val (decoderW, decoderH) = decoderOutputSize(srcFormat)
            val readerW = if (decoderW > 0) decoderW else info.width
            val readerH = if (decoderH > 0) decoderH else info.height
            if (readerW != info.width || readerH != info.height) {
                Log.w(
                    TAG,
                    "decoder size from the track format: ${readerW}x$readerH " +
                        "(retriever reported ${info.width}x${info.height})"
                )
            }
            decoder = MediaCodec.createDecoderByType(info.videoMime)
            try {
                // `getOutputImage()` only supports flexible YUV, so ask
                // for it explicitly instead of accepting whatever the
                // driver defaults to (a planar/semi-planar default would
                // make `getOutputImage()` throw on every frame).
                srcFormat.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )
                decoder.configure(srcFormat, null, null, 0)
                decoder.start()
            } catch (t: Throwable) {
                throw PipelineException(
                    "decoder on this device cannot deliver frames through a ByteBuffer " +
                        "(${info.videoMime}): ${t.message ?: t::class.java.simpleName}",
                    t
                )
            }
            Log.i(
                TAG,
                "decoder ${info.videoMime} in byte-buffer mode " +
                    "(coded ${readerW}x$readerH)"
            )

            // ---- encoder setup (ByteBuffer input: explicit
            // presentation timestamps keep A/V sync exact even when
            // per-frame AI work runs slower than realtime; a Surface
            // + Canvas path would timestamp frames with wall-clock
            // time and corrupt duration/sync) ----
            val fps = info.frameRate.takeIf { it in 1..120 } ?: 30
            val bitrate = req.bitrateBps.takeIf { it > 0 }
                ?: autoBitrate(outW, outH, fps)
            val yuvFormat = encoderSelection.colorFormat
            val encFormat = MediaFormat.createVideoFormat(MIME_AVC, outW, outH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, yuvFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            // Instantiate the encoder we actually probed — NOT
            // `createEncoderByType`, which would hand back a different
            // one with different capabilities.
            encoder = try {
                MediaCodec.createByCodecName(encoderSelection.codecName)
            } catch (t: Throwable) {
                throw PipelineException(
                    "رمزگذار ویدیوی این دستگاه قابل استفاده نیست " +
                        "(${encoderSelection.codecName}): ${t.message ?: t::class.java.simpleName}",
                    t
                )
            }
            try {
                encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()
            } catch (t: Throwable) {
                // 2026-10-20 — a configure failure here is almost
                // always a CAPABILITY mismatch (the encoder refused
                // the color format or the dimensions), not a broken
                // platform. The old code let the raw framework message
                // ("unsupported color format") reach the user.
                throw PipelineException(
                    "رمزگذار ویدیوی این دستگاه ابعاد ${outW}×$outH یا فرمت رنگ " +
                        "را نپذیرفت: ${t.message ?: t::class.java.simpleName}",
                    t
                )
            }

            muxer = MediaMuxer(req.dstFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerRef = muxer

            // Audio passthrough extractor (separate instance so video
            // decoding position is unaffected).
            val audioEx = if (req.copyAudio && info.hasAudioTrack) MediaExtractor() else null
            var audioTrackIdx = -1
            var audioFormat: MediaFormat? = null
            if (audioEx != null) {
                try {
                    audioEx.setDataSource(context, req.srcUri, null)
                    for (i in 0 until audioEx.trackCount) {
                        val f = audioEx.getTrackFormat(i)
                        val m = f.getString(MediaFormat.KEY_MIME) ?: ""
                        if (m.startsWith("audio/") && audioTrackIdx == -1) {
                            audioTrackIdx = i
                            audioFormat = f
                        }
                    }
                    if (audioTrackIdx != -1) audioEx.selectTrack(audioTrackIdx)
                    else {
                        runCatching { audioEx.release() }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "audio track setup failed; continuing video-only", t)
                    runCatching { audioEx.release() }
                }
                if (audioTrackIdx == -1) {
                    // release the non-null-but-useless extractor
                    runCatching { audioEx.release() }
                }
            }
            // Normalize: null when unusable.
            val audioExtractor: MediaExtractor? =
                if (audioEx != null && audioTrackIdx != -1) audioEx else null

            val estimatedTotal = estimateFrameCount(info)
            val rotMatrix = rotationMatrix(info.rotation)

            req.onProgress(0, estimatedTotal)

            // Structured decode->process->encode loop (single dequeue path).
            return runLoop(
                req = req,
                info = info,
                outW = outW,
                outH = outH,
                yuvFormat = yuvFormat,
                extractor = extractor,
                decoder = decoder,
                encoder = encoder,
                muxer = muxerRef,
                audioExtractor = audioExtractor,
                audioFormat = audioFormat,
                estimatedTotal = estimatedTotal,
                rotMatrix = rotMatrix
            ).also {
                // runLoop owns completion; release local refs so the
                // finally block below doesn't double-stop codecs it
                // already stopped (all stops are runCatching anyway).
                decoder = null
                encoder = null
                muxer = null
            }
        } catch (e: CancelledException) {
            runCatching { req.dstFile.delete() }
            Log.i(TAG, "transcode cancelled; partial deleted")
            return Result.Cancelled
        } catch (t: Throwable) {
            Log.w(TAG, "transcode failed", t)
            runCatching { req.dstFile.delete() }
            return Result.Failed(
                if (t is PipelineException) t
                else PipelineException(t.message ?: "Video processing failed", t)
            )
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    // ----------------------------------------------------------------
    // internals
    // ----------------------------------------------------------------

    private fun runLoop(
        req: Request,
        info: SourceInfo,
        outW: Int,
        outH: Int,
        yuvFormat: Int,
        extractor: MediaExtractor,
        decoder: MediaCodec,
        encoder: MediaCodec,
        muxer: MediaMuxer,
        audioExtractor: MediaExtractor?,
        audioFormat: MediaFormat?,
        estimatedTotal: Int,
        rotMatrix: Matrix?
    ): Result {
        var videoTrackOut = -1
        var audioTrackOut = -1
        var muxerStarted = false
        var framesDone = 0
        var decoderEosSent = false
        var decoderDone = false
        var encoderEosSent = false
        var encoderDone = false
        val bufInfo = MediaCodec.BufferInfo()
        val encInfo = MediaCodec.BufferInfo()
        var framesQueuedToEncoder = 0

        // Drain encoder outputs into the muxer.
        fun drainEncoder(block: Boolean) {
            var progress = true
            while (progress) {
                progress = false
                val st = encoder.dequeueOutputBuffer(encInfo, if (block) DEQUEUE_TIMEOUT_US else 0)
                when {
                    st == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    st == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (videoTrackOut == -1) {
                            videoTrackOut = muxer.addTrack(encoder.outputFormat)
                            if (audioExtractor != null && audioFormat != null && audioTrackOut == -1) {
                                audioTrackOut = muxer.addTrack(audioFormat)
                            }
                            muxer.start()
                            muxerStarted = true
                            // Mux the untouched audio track now, in
                            // one pass, so video/audio interleave
                            // from timestamp zero.
                            if (audioExtractor != null && audioTrackOut != -1) {
                                muxAudio(audioExtractor, muxer, audioTrackOut)
                            }
                        }
                    }
                    st >= 0 -> {
                        val eos = encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (encInfo.size > 0 && muxerStarted && videoTrackOut != -1) {
                            val buf = encoder.getOutputBuffer(st)!!
                            muxer.writeSampleData(videoTrackOut, buf, encInfo)
                        }
                        encoder.releaseOutputBuffer(st, false)
                        if (eos) encoderDone = true
                        progress = !encoderDone
                    }
                    else -> Unit
                }
                if (req.isCancelled()) throw CancelledException()
            }
        }

        try {
            while (!encoderDone) {
                if (req.isCancelled()) throw CancelledException()

                // 1. Feed decoder input.
                if (!decoderEosSent) {
                    val inIdx = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(inBuf, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(
                                inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            decoderEosSent = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // 2. Pull decoded frames straight from the decoder's
                // byte-buffer output and run the AI op.
                //
                // There is no reader queue and no PTS queue any more:
                // `BufferInfo.presentationTimeUs` is read at the moment the
                // frame is converted, so A/V sync and duration survive even
                // when the AI work runs at 2 fps.
                var pulled = 0
                while (pulled < 4 && !decoderDone) {
                    val st = decoder.dequeueOutputBuffer(bufInfo, 0)
                    when {
                        st == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        st == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = decoder.outputFormat
                            Log.i(
                                TAG,
                                "decoder output: " +
                                    f.getString(MediaFormat.KEY_MIME) +
                                    " ${f.getInteger(MediaFormat.KEY_WIDTH)}x" +
                                    "${f.getInteger(MediaFormat.KEY_HEIGHT)}" +
                                    " color=0x" +
                                    runCatching {
                                        f.getInteger(MediaFormat.KEY_COLOR_FORMAT)
                                    }.getOrDefault(-1).toString(16)
                            )
                            break
                        }
                        st >= 0 -> {
                            val eos = bufInfo.flags and
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            if (eos) {
                                // Never convert the EOS buffer: it has no
                                // frame data. This used to abort the
                                // process through the reader; releasing it
                                // without rendering is the whole fix here.
                                decoder.releaseOutputBuffer(st, false)
                                decoderDone = true
                                break
                            }
                            val ptsUs = bufInfo.presentationTimeUs.coerceAtLeast(0)
                            // Convert BEFORE releasing the buffer: the
                            // Image is only valid until
                            // `releaseOutputBuffer`.
                            var bmp: Bitmap? = null
                            try {
                                val img = decoder.getOutputImage(st)
                                    ?: throw PipelineException(
                                        "decoder returned no image for output buffer $st"
                                    )
                                try {
                                    bmp = codecImageToBitmap(img)
                                } finally {
                                    runCatching { img.close() }
                                }
                            } finally {
                                decoder.releaseOutputBuffer(st, false)
                            }
                            var frame = bmp!!
                            if (rotMatrix != null) {
                                val rotated = Bitmap.createBitmap(
                                    frame, 0, 0, frame.width, frame.height, rotMatrix, true
                                )
                                if (rotated !== frame) frame.recycle()
                                frame = rotated
                            }
                            val out = req.frameOp.process(frame, ptsUs, framesDone)
                            try {
                                queueFrameToEncoder(encoder, out, outW, outH, yuvFormat, ptsUs)
                                framesQueuedToEncoder++
                            } finally {
                                if (out !== frame) frame.recycle()
                                if (out.isRecycled.not()) out.recycle()
                            }
                            framesDone++
                            if ((framesDone and 3) == 0 || decoderDone) {
                                req.onProgress(framesDone, estimatedTotal)
                            }
                            if (req.isCancelled()) throw CancelledException()
                            pulled++
                        }
                        else -> break
                    }
                }

                // 3. When the decoder is drained, terminate the encoder
                // stream.
                if (decoderDone && !encoderEosSent) {
                    val inIdx = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIdx >= 0) {
                        encoder.queueInputBuffer(
                            inIdx, 0, 0, framesQueuedToEncoder * 1_000_000L / 30,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        encoderEosSent = true
                    }
                }

                // 4. Drain encoder outputs.
                drainEncoder(false)
            }
            // Final blocking drain to EOS.
            var guard = 0
            while (!encoderDone && guard++ < 500) {
                drainEncoder(true)
            }
            if (!encoderDone) throw PipelineException("encoder did not terminate")
        } finally {
            runCatching { audioExtractor?.release() }
        }

        if (!muxerStarted || framesDone == 0) {
            runCatching { req.dstFile.delete() }
            return Result.Failed(PipelineException("No frames were produced"))
        }
        // stop() finalizes the MP4 boxes; without it the file is corrupt.
        runCatching { muxer.stop() }
        req.onProgress(framesDone, framesDone.coerceAtLeast(estimatedTotal))
        return Result.Done(framesDone, outW, outH)
    }

    /**
     * Convert an Image that came from **MediaCodec's byte-buffer output**
     * (`MediaCodec.getOutputImage`) to an owned ARGB_8888 [Bitmap].
     *
     * This is deliberately NOT [imageToBitmap]: that function encodes the
     * policy for *surface* images (try the HardwareBuffer, never touch
     * planes), which exists because a surface image's planes are built
     * lazily in native code and abort the process when the buffer cannot
     * be mapped. A byte-buffer image has no `SurfaceImage` behind it - its
     * planes are plain direct buffers over the codec's output buffer - so
     * reading them is an ordinary memory read and the only conversion the
     * device allows (measured: surface frames abort both through
     * `getPlanes()` and through `wrapHardwareBuffer` on the Xiaomi
     * 2107113SG).
     *
     * `getOutputImage()` only supports `COLOR_FormatYUV420Flexible`, which
     * the decoder is configured for in [transcode]; the format check below
     * turns an unexpected output into a normal, catchable export failure.
     */
    fun codecImageToBitmap(image: Image): Bitmap =
        when (image.format) {
            android.graphics.ImageFormat.YUV_420_888,
            android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible -> {
                if (image.planes.size < 3) {
                    throw PipelineException(
                        "decoder output is not 3-plane YUV (planes=${image.planes.size}, " +
                            "format=0x${image.format.toString(16)})"
                    )
                }
                yuv420ToBitmap(image)
            }
            android.graphics.PixelFormat.RGBA_8888 -> rgbaToBitmap(image)
            else -> throw PipelineException(
                "decoder output format 0x${image.format.toString(16)} is not supported " +
                    "in byte-buffer mode"
            )
        }

    /**
     * Convert [bmp] (any size) to YUV420 at exactly [outW]x[outH]
     * (center-fit on black) and queue it to the encoder with the
     * source frame's real [ptsUs].
     */
    private fun queueFrameToEncoder(
        encoder: MediaCodec,
        bmp: Bitmap,
        outW: Int,
        outH: Int,
        yuvFormat: Int,
        ptsUs: Long
    ) {
        val yuv = bitmapToYuv420Fit(bmp, outW, outH, yuvFormat)
        val inIdx = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (inIdx < 0) throw PipelineException("encoder input starved")
        val buf = encoder.getInputBuffer(inIdx)!!
        buf.clear()
        if (buf.remaining() < yuv.size) {
            throw PipelineException("encoder input too small (${buf.remaining()} < ${yuv.size})")
        }
        buf.put(yuv)
        encoder.queueInputBuffer(inIdx, 0, yuv.size, ptsUs.coerceAtLeast(0), 0)
    }

    /**
     * ARGB bitmap -> YUV420 (planar I420 or semi-planar NV12)
     * centered on a black [outW]x[outH] canvas. BT.601
     * limited-range conversion. Pure Kotlin; the only per-frame
     * allocation is the output array.
     */
    fun bitmapToYuv420Fit(bmp: Bitmap, outW: Int, outH: Int, yuvFormat: Int): ByteArray {
        val planar = android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        val semiPlanar = yuvFormat != planar
        val ySize = outW * outH
        val uvSize = ySize / 4
        val out = ByteArray(ySize + uvSize * 2)
        // Scale factor to fit-center.
        val scale = minOf(outW.toFloat() / bmp.width, outH.toFloat() / bmp.height)
        val dw = (bmp.width * scale).toInt().coerceAtLeast(1)
        val dh = (bmp.height * scale).toInt().coerceAtLeast(1)
        val ox = (outW - dw) / 2
        val oy = (outH - dh) / 2
        val scaled = if (bmp.width == dw && bmp.height == dh) bmp
        else Bitmap.createScaledBitmap(bmp, dw, dh, true)
        val px = IntArray(dw * dh)
        scaled.getPixels(px, 0, dw, 0, 0, dw, dh)
        if (scaled !== bmp) scaled.recycle()
        // Fill Y with 16 (black), UV with 128 (neutral).
        out.fill(16.toByte(), 0, ySize)
        out.fill(128.toByte(), ySize, out.size)
        for (y in 0 until dh) {
            val oyY = oy + y
            if (oyY !in 0 until outH) continue
            for (x in 0 until dw) {
                val oxX = ox + x
                if (oxX !in 0 until outW) continue
                val c = px[y * dw + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val yy = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)
                val uu = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                val vv = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)
                out[oyY * outW + oxX] = yy.toByte()
                if ((y and 1) == 0 && (x and 1) == 0) {
                    val uvx = oxX shr 1
                    val uvy = oyY shr 1
                    if (semiPlanar) {
                        val o = ySize + uvy * outW + uvx * 2
                        if (o + 1 < out.size) {
                            out[o] = uu.toByte()
                            out[o + 1] = vv.toByte()
                        }
                    } else {
                        val ou = ySize + uvy * (outW shr 1) + uvx
                        val ov = ySize + uvSize + uvy * (outW shr 1) + uvx
                        if (ov < out.size) {
                            out[ou] = uu.toByte()
                            out[ov] = vv.toByte()
                        }
                    }
                }
            }
        }
        return out
    }

    private fun muxAudio(audioEx: MediaExtractor, muxer: MediaMuxer, trackOut: Int) {
        val info = MediaCodec.BufferInfo()
        val maxChunk = 256 * 1024
        while (true) {
            val chunk = ByteBuffer.allocateDirect(maxChunk)
            val n = audioEx.readSampleData(chunk, 0)
            if (n < 0) break
            info.set(0, n, audioEx.sampleTime, audioEx.sampleFlags)
            muxer.writeSampleData(trackOut, chunk, info)
            audioEx.advance()
        }
    }

    private fun rotationMatrix(rotation: Int): Matrix? {
        val r = ((rotation % 360) + 360) % 360
        if (r == 0) return null
        return Matrix().apply { postRotate(r.toFloat()) }
    }

    private fun estimateFrameCount(info: SourceInfo): Int {
        val fps = info.frameRate.takeIf { it in 1..120 } ?: 30
        return ((info.durationMs.coerceAtLeast(1) * fps) / 1000).toInt().coerceAtLeast(1)
    }

    private fun autoBitrate(w: Int, h: Int, fps: Int): Int {
        // ~0.12 bits/pixel/frame, clamped to a sane mobile range.
        val px = w.toLong() * h.toLong()
        return (px * fps * 0.12).toLong().coerceIn(1_500_000L, 40_000_000L).toInt()
    }

    // ----------------------------------------------------------------
    // Image -> Bitmap (YUV_420_888 or RGBA_8888 or JPEG fallback)
    // ----------------------------------------------------------------

    /**
     * Convert a decoder [Image] to an owned `ARGB_8888` [Bitmap].
     * Handles the two formats decoders actually emit into an
     * ImageReader (`YUV_420_888`, `RGBA_8888`); anything else
     * throws a [PipelineException] with the real format.
     *
     * ## 2026-10-21 — the size guard that makes the abort impossible
     *
     * `Image.getPlanes()` is NOT a safe call on an ImageReader image:
     * the planes are built lazily, in native code, from the buffer the
     * producer actually wrote. When that buffer is smaller than the
     * reader was sized for, `nativeCreatePlanes` produces a plane
     * whose address is null while its capacity is 1, and ART's JNI
     * check aborts the **process**:
     *
     * ```
     * Abort message: 'JNI DETECTED ERROR IN APPLICATION: non-zero capacity
     *   for nullptr pointer: 1 in call to NewDirectByteBuffer'
     * ```
     *
     * A native abort cannot be caught, so no `try/catch` helps — the
     * only defence is not to make the call. `Image.getWidth()` /
     * `getHeight()` are plain accessors set when the image was
     * acquired and never touch the native plane machinery, so they
     * are safe to read.
     *
     * This overload therefore checks the image's dimensions against
     * the size the reader was created with BEFORE touching
     * `getPlanes()`. A mismatch means the decoder wrote a different
     * size than expected — the frame is rejected with a clear error
     * instead of taking the process down.
     */
    fun imageToBitmap(image: Image): Bitmap =
        imageToBitmap(image, allowPlaneFallback = false)

    /**
     * 2026-10-23 - **the split policy**, after the native abort below.
     *
     * API 29+ first reads the frame through `Image.getHardwareBuffer()`
     * and never through `Image.getPlanes()`. That is not a preference: on
     * a Xiaomi 2107113SG (Android 14, HEVC via `c2.qti.hevc.decoder`) the
     * image matched the reader exactly, and `planes[0]` still called
     * `nativeCreatePlanes` with a null address and a capacity of 1, so
     * ART's JNI check killed the process before any Kotlin frame could
     * run -
     *
     * ```
     * JNI DETECTED ERROR IN APPLICATION: non-zero capacity for
     * nullptr pointer: 1 in call to NewDirectByteBuffer
     *   ... ImageReader$SurfaceImage.nativeCreatePlanes
     *   ... VideoFramePipeline.imageToBitmap
     *   ... SequentialFrameSampler.sample
     * ```
     *
     * A native abort cannot be caught, so [allowPlaneFallback] decides
     * what happens when the hardware-buffer readback is unavailable
     * (measured on that device: `wrapHardwareBuffer failed: usage flags
     * must contain USAGE_GPU_SAMPLED_IMAGE`, because the public
     * `ImageReader` allocates `YUV_420_888` buffers without GPU usage):
     *
     * - `false` - throw a **catchable** [PipelineException]. Only callers
     *   with a real fallback may pass this: the matting sampler
     *   ([SequentialFrameSampler]) drops into
     *   `MediaPipeMattingEngine`'s `MediaMetadataRetriever` path, which
     *   is how Background Remove works on that device today.
     * - `true` - keep the plane path this code has always used. The
     *   export-side consumers ([com.aicreatorstudio.app.export.AiExportHooks],
     *   [com.aicreatorstudio.app.export.OverlayCompositeRenderer]) have
     *   no retriever fallback of their own, so refusing planes there
     *   would turn a working export into a failed one. They keep the
     *   pre-existing behaviour (and the pre-existing risk) instead.
     *
     * The verdict is cached: after the first rejected buffer the plane
     * path is chosen immediately, so a 600-frame export does not pay a
     * failed `wrapHardwareBuffer` per frame.
     */
    fun imageToBitmap(image: Image, allowPlaneFallback: Boolean): Bitmap {
        if (!hardwareBufferRejected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            hardwareBitmap(image)?.let {
                if (!hardwareBufferVerified) {
                    hardwareBufferVerified = true
                    Log.i(
                        TAG,
                        "frame readback via HardwareBuffer works " +
                            "(${it.width}x${it.height} ARGB_8888)"
                    )
                }
                return it
            }
            hardwareBufferRejected = true
            val mode = if (allowPlaneFallback) {
                "plane fallback enabled"
            } else {
                "plane fallback disabled - caller must fall back to its retriever"
            }
            Log.w(TAG, "hardware-buffer readback unavailable on this device: $mode")
        }
        if (!allowPlaneFallback && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            throw PipelineException(
                "decoder frame ${image.width}x${image.height} is not readable through " +
                    "a hardware buffer; refusing the plane path that aborts the process"
            )
        }
        return when (image.format) {
            android.graphics.ImageFormat.YUV_420_888 -> yuv420ToBitmap(image)
            android.graphics.PixelFormat.RGBA_8888 -> rgbaToBitmap(image)
            else -> throw PipelineException("unsupported decoder image format: ${image.format}")
        }
    }

    /**
     * Cached result of the first hardware-buffer attempt. Volatile
     * because the AI engines and the export run on different
     * dispatchers; a stale `false` only costs one extra failed wrap.
     */
    @Volatile
    private var hardwareBufferRejected = false

    /**
     * Set once the hardware-buffer readback has produced a frame, so the
     * log states the negative result *and* the positive one exactly once
     * per process.
     */
    @Volatile
    private var hardwareBufferVerified = false

    /**
     * Rasterise [image] through its [android.hardware.HardwareBuffer].
     *
     * Returns `null` — never throws — when the buffer cannot be obtained
     * or wrapped, so the caller can report a normal failure. The
     * `HardwareBuffer` reference taken here is always closed; only the
     * software copy escapes.
     */
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.Q)
    private fun hardwareBitmap(image: Image): Bitmap? {
        val buffer = try {
            image.hardwareBuffer
        } catch (t: Throwable) {
            Log.w(TAG, "image.hardwareBuffer unavailable: ${t.message}")
            null
        } ?: return null
        return try {
            val wrapped = try {
                // `null` colour space keeps the buffer's own dataspace,
                // which is what the codec tagged (BT.709 limited for
                // camera HEVC); forcing sRGB here shifts the colours.
                Bitmap.wrapHardwareBuffer(buffer, null)
            } catch (t: Throwable) {
                Log.w(TAG, "wrapHardwareBuffer failed: ${t.message}")
                null
            } ?: return null
            try {
                // A HARDWARE bitmap cannot be read on the CPU; this copy
                // is the GPU readback into the ARGB_8888 frame every
                // engine and the encoder expect.
                wrapped.copy(Bitmap.Config.ARGB_8888, false)
            } finally {
                wrapped.recycle()
            }
        } finally {
            runCatching { buffer.close() }
        }
    }

    /**
     * The guarded overload: [expectedWidth]/[expectedHeight] are the
     * size the reader was created with. Pass `0` to skip the check
     * (only safe when the caller has already established that the
     * producer writes the reader's size).
     *
     * 2026-10-23 - this overload is what
     * [SequentialFrameSampler] uses, and it passes
     * `allowPlaneFallback = false`: the sampler is the caller that has a
     * real fallback (the retriever path in `MediaPipeMattingEngine`), and
     * it is the caller on which `getPlanes()` was measured to abort the
     * process.
     */
    fun imageToBitmap(
        image: Image,
        expectedWidth: Int,
        expectedHeight: Int
    ): Bitmap {
        if (expectedWidth > 0 && expectedHeight > 0 &&
            (image.width != expectedWidth || image.height != expectedHeight)
        ) {
            // Do NOT call image.planes here — that is the call that
            // aborts the process.
            throw PipelineException(
                "decoder frame size ${image.width}x${image.height} does not match the " +
                    "reader size ${expectedWidth}x$expectedHeight"
            )
        }
        return imageToBitmap(image, allowPlaneFallback = false)
    }

    private fun rgbaToBitmap(image: Image): Bitmap {
        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (rowStride == w * 4) {
            val tmp = ByteArray(w * h * 4)
            buf.get(tmp)
            val px = IntArray(w * h)
            for (i in px.indices) {
                val r = tmp[i * 4].toInt() and 0xFF
                val g = tmp[i * 4 + 1].toInt() and 0xFF
                val b = tmp[i * 4 + 2].toInt() and 0xFF
                val a = tmp[i * 4 + 3].toInt() and 0xFF
                px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            bmp.setPixels(px, 0, w, 0, 0, w, h)
        } else {
            // Strided copy row by row.
            val row = ByteArray(rowStride)
            val px = IntArray(w)
            for (y in 0 until h) {
                buf.get(row)
                for (x in 0 until w) {
                    val r = row[x * 4].toInt() and 0xFF
                    val g = row[x * 4 + 1].toInt() and 0xFF
                    val b = row[x * 4 + 2].toInt() and 0xFF
                    val a = row[x * 4 + 3].toInt() and 0xFF
                    px[x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
                bmp.setPixels(px, 0, w, 0, y, w, 1)
            }
        }
        return bmp
    }

    private fun yuv420ToBitmap(image: Image): Bitmap {
        val w = image.width
        val h = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride

        val px = IntArray(w * h)
        val yRow = ByteArray(yRowStride)
        // Read U/V rows once per 2 image rows.
        val uRow = ByteArray(uvRowStride)
        val vRow = ByteArray(vRowStride)
        var uvRowCached = -1
        for (y in 0 until h) {
            readPlaneRow(yBuf, y * yRowStride, yRow)
            val uvRow = y shr 1
            if (uvRow != uvRowCached) {
                readPlaneRow(uBuf, uvRow * uvRowStride, uRow)
                readPlaneRow(vBuf, uvRow * vRowStride, vRow)
                uvRowCached = uvRow
            }
            for (x in 0 until w) {
                val yVal = if (x < yRowStride) (yRow[x].toInt() and 0xFF) else 0
                val uvx = (x shr 1) * uvPixelStride
                val uVal = (if (uvx < uRow.size) (uRow[uvx].toInt() and 0xFF) else 128) - 128
                val vVal = (if (uvx < vRow.size) (vRow[uvx].toInt() and 0xFF) else 128) - 128
                // BT.601 full-range-ish integer conversion.
                var r = (yVal + 1.402f * vVal).toInt()
                var g = (yVal - 0.344f * uVal - 0.714f * vVal).toInt()
                var b = (yVal + 1.772f * uVal).toInt()
                r = r.coerceIn(0, 255)
                g = g.coerceIn(0, 255)
                b = b.coerceIn(0, 255)
                px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Copy one row of a decoded plane into [dst], clamped to the plane's
     * own `limit()`.
     *
     * 2026-10-24 — a **byte-buffer** decoder image ends at the last byte
     * the codec wrote, so its final chroma row is shorter than
     * `rowStride`. The historical `buffer.position(offset); get(dst)` read
     * threw `BufferUnderflowException` there, which surfaced as
     * "Video processing failed" on the first frame of an export. The bytes
     * past the limit are not part of the frame, so they are zero-filled
     * (Y) / neutralised by the caller (U/V).
     *
     * Surface images are unaffected: their planes are full-size, so the
     * limit never truncates a row.
     */
    private fun readPlaneRow(src: ByteBuffer, offset: Int, dst: ByteArray) {
        if (offset < 0 || offset >= src.limit()) {
            dst.fill(0)
            return
        }
        src.position(offset)
        val n = minOf(dst.size, src.limit() - offset)
        src.get(dst, 0, n)
        if (n < dst.size) dst.fill(0, n, dst.size)
    }

    // ----------------------------------------------------------------
    // helpers exposed for tests / engines
    // ----------------------------------------------------------------

    /**
     * Decode a single thumbnail-ish frame at [timeMs] (used for
     * analysis sampling, not full transcodes). Rotation-corrected.
     * Returns null when no frame could be retrieved.
     */
    fun sampleFrame(context: Context, uri: Uri, timeMs: Long): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val bmp = retriever.getFrameAtTime(
                timeMs * 1000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: return null
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val m = rotationMatrix(rot)
            if (m == null) bmp
            else {
                val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (out !== bmp) bmp.recycle()
                out
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Decode a content/image URI to a bitmap (for bg-image mode). */
    fun decodeImageUri(context: Context, uri: Uri, maxDim: Int = 1920): Bitmap? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                // Need bytes twice; buffer them (bg images are small).
                val bytes = ins.readBytes()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                var sample = 1
                val maxSide = maxOf(opts.outWidth, opts.outHeight).coerceAtLeast(1)
                while (maxSide / sample > maxDim) sample *= 2
                val real = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, real)
            }
        }.getOrNull()
    }
}
