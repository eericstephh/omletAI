package com.aicreatorstudio.app.ai.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * **Frame-accurate, sequential frame sampler for the offline AI tools.**
 *
 * ## Why this exists
 *
 * Background Remove, Auto Frame and OCR used to sample frames with
 * `MediaMetadataRetriever.getFrameAtTime(t, OPTION_CLOSEST_SYNC)`. That
 * option is documented to return the closest **sync frame** — i.e. the
 * nearest keyframe — so every timestamp inside one GOP decoded to the
 * *same* picture:
 *
 *  - a 10 fps matting pass over a 2 s GOP produced 20 identical alpha
 *    masks, each written to the sidecar under a timestamp that did not
 *    match the picture it came from (masks lag the frame by up to half
 *    a GOP, so edges sit visibly off the person);
 *  - Auto Frame's 250 ms trace became a staircase, and the 1-Euro
 *    filter then smoothed a signal that never changed — the crop
 *    "jumps" every GOP instead of following the subject;
 *  - OCR read the same keyframe several times and missed every text
 *    change in between.
 *
 * Switching to `OPTION_CLOSEST` would fix the picture but re-seek and
 * re-decode from the previous keyframe on **every** sample — minutes of
 * extra work for a 600-sample pass.
 *
 * This sampler instead decodes the stream **once, in order**, and emits
 * the frame whose presentation time crosses each requested interval.
 * Decoding is therefore O(frames) instead of O(samples × GOP), and the
 * emitted timestamp is the real PTS of the emitted picture.
 *
 * ## Guarantees
 *
 * - **Frame-accurate timestamps** — `Frame.timeMs` is the decoded
 *   picture's own PTS, rebased so the first frame is `0` (clip-local,
 *   which is what the matting sidecar and `CropKeyframe` both expect).
 * - **Rotation-correct** — the display rotation is applied, so a
 *   portrait phone video arrives upright. `MediaMetadataRetriever` did
 *   *not* apply it, which silently broke ROI/crop coordinates on every
 *   rotated source.
 * - **Bounded memory** — exactly one decoded image is converted at a
 *   time and only for sampled frames; skipped frames are released
 *   without ever becoming a `Bitmap`.
 * - **Bounded cost** — `maxSide` downscales *before* the caller sees the
 *   frame, so a 4K source does not allocate 4K ARGB buffers per sample.
 * - **Never throws** — any codec/container failure returns `0` and logs.
 *   Callers keep their legacy `MediaMetadataRetriever` path as a
 *   fallback, so a sampler failure degrades quality, never availability.
 *
 * Runs synchronously on the calling thread (callers use
 * `Dispatchers.IO`).
 */
object SequentialFrameSampler {

    private const val TAG = "FrameSampler"
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /** One emitted picture. Ownership of [bitmap] passes to the caller. */
    data class Frame(val bitmap: Bitmap, val timeMs: Long)

    /**
     * Decode [uri] once and call [onFrame] for every frame at or after
     * each multiple of [intervalMs], up to [maxFrames] samples.
     *
     * @param intervalMs target spacing between samples, clamped to ≥1.
     * @param maxFrames hard cap on emitted frames (also bounds runtime).
     * @param maxSide when > 0, the emitted bitmap's longer side is
     *   downscaled to this value. 0 keeps the decoded size.
     * @param isCancelled polled between decoder operations.
     * @param onFrame invoked on the calling thread; the bitmap is the
     *   caller's to recycle. Suspending on purpose — engines run their
     *   inference directly here instead of buffering frames.
     * @return number of frames emitted. `0` means the source could not
     *   be decoded — callers must fall back, not report success.
     */
    suspend fun sample(
        context: Context,
        uri: Uri,
        intervalMs: Long,
        maxFrames: Int,
        maxSide: Int = 0,
        isCancelled: () -> Boolean = { false },
        onFrame: suspend (Frame) -> Unit
    ): Int {
        if (maxFrames <= 0) return 0
        val stepUs = intervalMs.coerceAtLeast(1L) * 1000L

        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        try {
            val info = VideoFramePipeline.probe(context, uri) ?: return 0
            if (info.width <= 0 || info.height <= 0) return 0

            val ex = MediaExtractor()
            extractor = ex
            ex.setDataSource(context, uri, null)
            var videoTrack = -1
            var srcFormat: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoTrack == -1) {
                    videoTrack = i
                    srcFormat = f
                }
            }
            if (videoTrack == -1 || srcFormat == null) return 0
            ex.selectTrack(videoTrack)

            // 2026-10-24 — byte-buffer output, no Surface/ImageReader.
            //
            // This sampler used a decoder -> ImageReader surface, and on the
            // measured phone (Xiaomi 2107113SG, Android 14, HEVC) that path
            // cannot deliver a single readable frame: `Image.getPlanes()`
            // aborts the process in `ImageReader$SurfaceImage.nativeCreatePlanes`
            // with "non-zero capacity for nullptr pointer", and
            // `Bitmap.wrapHardwareBuffer` refuses the buffer. Every sample
            // therefore came from MediaMetadataRetriever's fallback (~0.33 s
            // per frame) instead of from the decoder. See
            // VideoFramePipeline.transcode for the full measurements.
            //
            // With a null Surface the decoder hands out its own ByteBuffers and
            // [VideoFramePipeline.codecImageToBitmap] reads them as ordinary
            // direct buffers. The picture is converted and its output buffer
            // released before the suspending [onFrame] runs, so inference never
            // holds a codec buffer.
            srcFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            val dec = MediaCodec.createDecoderByType(info.videoMime)
            decoder = dec
            dec.configure(srcFormat, null, null, 0)
            dec.start()
            Log.i(TAG, "sequential sampler: ${info.videoMime} byte-buffer decode")

            val rotation = rotationMatrix(info.rotation)
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var emitted = 0
            var basePtsUs = -1L
            var nextTargetUs = 0L
            // Bounded wait: after the input is drained a decoder that keeps
            // returning INFO_TRY_AGAIN_LATER must not spin forever.
            var idlePasses = 0

            while (emitted < maxFrames) {
                if (isCancelled()) break

                // 1. Feed the decoder.
                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = dec.getInputBuffer(inIdx)!!
                        val n = ex.readSampleData(inBuf, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(
                                inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }

                // 2. Drain the decoder's byte-buffer output.
                //
                //    A picture that does not land on a sample instant is
                //    released **without conversion** — that is where the
                //    saving comes from: a 100 ms pass converts roughly one
                //    of every three pictures instead of all of them.
                var drained = 0
                while (drained < 4 && !outputDone) {
                    val timeout = if (inputDone) DEQUEUE_TIMEOUT_US else 0L
                    val st = dec.dequeueOutputBuffer(bufferInfo, timeout)
                    when {
                        st == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        st == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> break
                        st >= 0 -> {
                            drained++
                            val eos = bufferInfo.flags and
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            if (eos) {
                                // 2026-10-20 CRASH FIX — the EOS output
                                // carries no picture. It is released, never
                                // converted; the old code expressed the same
                                // rule as `releaseOutputBuffer(st, !eos)` on
                                // a reader surface.
                                runCatching { dec.releaseOutputBuffer(st, false) }
                                outputDone = true
                                break
                            }
                            val ptsUs =
                                bufferInfo.presentationTimeUs.coerceAtLeast(0L)
                            if (basePtsUs < 0L) basePtsUs = ptsUs
                            if (ptsUs < nextTargetUs) {
                                // Not a sample instant: no conversion at all.
                                runCatching { dec.releaseOutputBuffer(st, false) }
                            } else {
                                // Convert BEFORE releasing: the Image is only
                                // valid until `releaseOutputBuffer`.
                                var bmp: Bitmap? = null
                                try {
                                    val img = dec.getOutputImage(st)
                                    if (img != null) {
                                        try {
                                            bmp = VideoFramePipeline.codecImageToBitmap(img)
                                        } finally {
                                            runCatching { img.close() }
                                        }
                                    }
                                } finally {
                                    runCatching { dec.releaseOutputBuffer(st, false) }
                                }
                                val decoded = bmp
                                if (decoded == null) {
                                    // No picture for this buffer: skip it but
                                    // move the target so the stream progresses.
                                    nextTargetUs = ptsUs + stepUs
                                } else {
                                    var frameBitmap = applyRotation(decoded, rotation)
                                    frameBitmap = boundToMaxSide(frameBitmap, maxSide)
                                    nextTargetUs = ptsUs + stepUs
                                    val frame = Frame(
                                        frameBitmap,
                                        (ptsUs - basePtsUs) / 1000L
                                    )
                                    // Ownership of the bitmap passes to the
                                    // caller only when the callback returned;
                                    // if it throws or is cancelled, reclaim it.
                                    var delivered = false
                                    try {
                                        onFrame(frame)
                                        delivered = true
                                    } finally {
                                        if (!delivered) {
                                            runCatching { frame.bitmap.recycle() }
                                        }
                                    }
                                    emitted++
                                }
                            }
                        }
                        else -> break
                    }
                }

                // 3. Bounded wait and completion: everything the decoder
                //    produced has been consumed above, so once both sides are
                //    done there is nothing left. If the input is drained and
                //    the decoder still returns nothing for ~5 s, stop instead
                //    of spinning (a stuck driver must not hang the caller).
                if (drained == 0) {
                    if (inputDone && ++idlePasses > 500) break
                } else {
                    idlePasses = 0
                }
                if (inputDone && outputDone) break
            }
            return emitted
        } catch (t: Throwable) {
            Log.w(TAG, "sequential sampling failed: ${t.message}", t)
            return 0
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor?.release() }
        }
    }

    private fun rotationMatrix(rotation: Int): Matrix? {
        val r = ((rotation % 360) + 360) % 360
        if (r == 0) return null
        return Matrix().apply { postRotate(r.toFloat()) }
    }

    private fun applyRotation(src: Bitmap, matrix: Matrix?): Bitmap {
        if (matrix == null) return src
        return try {
            val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
            if (out !== src) src.recycle()
            out
        } catch (_: Throwable) {
            src
        }
    }

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
}
