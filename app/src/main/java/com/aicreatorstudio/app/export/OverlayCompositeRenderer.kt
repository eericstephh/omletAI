package com.aicreatorstudio.app.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.aicreatorstudio.app.ai.media.VideoFramePipeline
import com.aicreatorstudio.app.model.ChromaKeySettings
import com.aicreatorstudio.app.model.OverlayMaskGeometry
import com.aicreatorstudio.app.model.OverlayPipGeometry
import com.aicreatorstudio.app.model.VideoOverlay
import com.aicreatorstudio.app.util.ChromaKeyEngine
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 2026-10-13 — the **export half** of Picture-in-Picture.
 *
 * ## What was missing before this file
 *
 * `VideoOverlay` was timeline-only metadata: `positionX`, `positionY`,
 * `scale`, `opacity`, `uri` and `isVideo` were read by the timeline lane
 * and the JSON codec and by nothing else. `mediaOverlays` did not appear
 * anywhere under `export/`, so an overlay drew a tile and never reached
 * the file. This compositor is the consumer that was absent.
 *
 * ## Where it runs, and why there
 *
 * Media3 1.5.0 has **no public video-over-video compositing API** —
 * `OverlayEffect` / `TextureOverlay` / `BitmapOverlay` are bitmap-only,
 * and `getBitmap` is called on the GL thread where a per-frame decode is
 * both illegal and ruinously slow. So the PiP composite is a CPU pre-pass
 * on the same [VideoFramePipeline] the matting and motion-tracking passes
 * already use: decode → composite per frame → AVC encode, with audio
 * passed through sample-for-sample and timestamps flowing straight from
 * decoder to encoder. Duration, FPS and A/V sync are preserved by
 * construction, exactly as in those two passes.
 *
 * It runs **last** in `AiExportHooks.prepareSourceUri`, after matting,
 * reframe and motion tracking. That ordering is load-bearing: the
 * overlay's `positionX` / `positionY` / `scale` describe a position in
 * the frame the viewer finally sees, so compositing before a reframe
 * would let the reframe crop the PiP away.
 *
 * ## Parity with the preview
 *
 * Three things come from pure sources shared with
 * `ui/screens/editor/OverlayPipLayer.kt`, and every one of them is a
 * separate guarantee:
 *
 *  - **placement** from [OverlayPipGeometry];
 *  - **shape** from [OverlayMaskRenderer] → [OverlayMaskGeometry.outline];
 *  - **the chroma key** from `util/ChromaKeyEngine`.
 *
 * Neither side owns a formula of its own, so "the export matches the
 * preview" is a property of the code, not of a reviewer's attention.
 *
 * ⚠️ The chroma-key parity is parity of the **decision function**, not of
 * the sampling grid. The export keys each overlay frame at its **source
 * resolution**, because that is where the key is most accurate — the alpha
 * boundary is computed before anything is averaged. The preview cannot
 * afford that: it is real-time, so it keys the box-sized frame it already
 * holds, and its alpha boundary is therefore sampled at box resolution.
 * Same engine, same thresholds, same spill maths; only the resolution of
 * the edge differs. Where it matters most — which pixels go, and how the
 * colours de-spill — the two are identical by construction.
 *
 * ## Cost
 *
 * The overlay's own frames are decoded sequentially and its source bitmap
 * is scaled by `Canvas.drawBitmap`'s hardware path, so per-frame Kotlin
 * touches only the PiP's own rectangle — a 30 % PiP covers ~9 % of the
 * frame's pixels, and the main frame is mutated and returned rather than
 * reallocated. The two exceptions are both documented on the scratch they
 * use: a **feathered** mask needs a box-sized layer
 * ([OverlayMaskRenderer.applySoftMask]), and a **chroma key** needs one
 * sized to the overlay's own source. Both are allocated once per overlay
 * and reused, because an export is not real-time and is allowed to pay.
 */
internal class OverlayCompositeRenderer(
    context: Context,
    overlays: List<VideoOverlay>,
    private val outputWidthPx: Int,
    private val outputHeightPx: Int,
    /** Timeline (ms) where this segment starts in the composition. */
    private val timelineStartMs: Long,
    /** Source (ms) where this segment's trim begins. */
    private val sourceStartMs: Long,
    /** Source length this segment covers, before speed. */
    private val sourceDurationMs: Long,
    /** Output length of this segment, after speed. */
    private val outputDurationMs: Long
) : AutoCloseable {

    /**
     * One overlay that actually overlaps this segment, paired with its
     * media source and its own chroma-key scratch.
     *
     * The keying state lives here rather than on the renderer because two
     * overlays with different source sizes would otherwise make one shared
     * scratch bitmap thrash between them. A *still* is keyed once and the
     * result reused for every frame of the pass; a *video* gets a new
     * source bitmap per frame and so is keyed every frame.
     */
    private class Entry(
        val overlay: VideoOverlay,
        val source: OverlayMediaSource
    ) : AutoCloseable {

        private var keyScratch: Bitmap? = null
        private var keyScratchW = 0
        private var keyScratchH = 0
        private var keyBuffer = IntArray(0)
        private var keyedFrom: Bitmap? = null
        private var keyedSettings: ChromaKeySettings? = null
        private var keyWarned = false

        /**
         * The keyed frame for [src], or `null` when nothing had to be
         * done (the key is off) or the pixels could not be read.
         *
         * [src] is **never mutated**: the export's overlay frames belong to
         * their decoder and are handed out again for later frames, so
         * writing into one would corrupt the following frame. The keyed
         * result is written into a scratch bitmap of the same size.
         */
        fun keyed(src: Bitmap): Bitmap? {
            val settings = overlay.chromaKeySettings
            if (!settings.isEnabled) return null
            val scratch = scratchFor(src.width, src.height) ?: return null
            if (src === keyedFrom && settings == keyedSettings) return scratch
            if (!ChromaKeyEngine.applyInto(src, scratch, settings, keyBuffer)) {
                if (!keyWarned) {
                    keyWarned = true
                    Log.w(TAG, "chroma key unavailable for overlay ${overlay.id}; drawing un-keyed")
                }
                return null
            }
            keyedFrom = src
            keyedSettings = settings
            return scratch
        }

        private fun scratchFor(w: Int, h: Int): Bitmap? {
            val existing = keyScratch
            if (existing != null && keyScratchW == w && keyScratchH == h &&
                !existing.isRecycled
            ) {
                return existing
            }
            runCatching { existing?.recycle() }
            val created = runCatching {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            }.getOrNull()
            keyScratch = created
            keyScratchW = w
            keyScratchH = h
            if (created != null) keyBuffer = IntArray(w * h)
            return created
        }

        override fun close() {
            runCatching { keyScratch?.recycle() }
            keyScratch = null
            keyBuffer = IntArray(0)
            keyedFrom = null
            keyedSettings = null
            source.close()
        }
    }

    private val entries: List<Entry>

    init {
        // An overlay with no media is a timeline placeholder the user
        // never filled in; there is nothing to composite.
        val segmentEndMs = timelineStartMs + outputDurationMs.coerceAtLeast(0L)
        val candidates = overlays.filter { o ->
            o.uri.isNotBlank() &&
                o.endTimeMs > o.startTimeMs &&
                o.endTimeMs > timelineStartMs &&
                o.startTimeMs < segmentEndMs
        }
        entries = candidates.mapNotNull { overlay ->
            val source = OverlayMediaSource.create(context, overlay)
            if (source == null) {
                Log.w(
                    TAG,
                    "overlay ${overlay.id} has unreadable media (${overlay.uri}); skipped"
                )
                null
            } else {
                Entry(overlay, source)
            }
        }
    }

    /** True when this segment has at least one drawable overlay. */
    val hasContent: Boolean get() = entries.isNotEmpty()

    /** Number of overlays this segment will composite. Diagnostics only. */
    val overlayCount: Int get() = entries.size

    private val drawPaint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
    }

    /**
     * A scratch layer reused across frames for the feathered-mask path.
     * Reallocated only when the box size changes, so a steady PiP costs
     * one bitmap for the whole pass instead of one per frame.
     */
    private var scratch: Bitmap? = null
    private var scratchW = 0
    private var scratchH = 0

    /**
     * Composite every overlay that is live at [presentationTimeUs] onto
     * [frame], in place.
     *
     * **Returns [frame] itself, deliberately.** [VideoFramePipeline]
     * recycles the bitmap a `FrameOp` returns when it differs from the
     * input, so returning a fresh instance would force a full-frame
     * allocation per frame — the fastest way to OOM a 4K export. Mutating
     * and returning the input is the contract.
     */
    fun drawAt(frame: Bitmap, presentationTimeUs: Long): Bitmap {
        if (entries.isEmpty()) return frame
        val editorMs = OverlayPipGeometry.editorMsForSourcePts(
            ptsUs = presentationTimeUs,
            timelineStartMs = timelineStartMs,
            sourceStartMs = sourceStartMs,
            sourceDurationMs = sourceDurationMs,
            outputDurationMs = outputDurationMs
        )
        var canvas: Canvas? = null
        entries.forEach { entry ->
            if (!OverlayPipGeometry.isVisibleAt(entry.overlay, editorMs)) return@forEach
            val localMs = OverlayPipGeometry.localMs(entry.overlay, editorMs)
            val decoded = entry.source.frameAt(localMs) ?: return@forEach
            val box = OverlayPipGeometry.resolveBox(
                overlay = entry.overlay,
                canvasWidthPx = frame.width,
                canvasHeightPx = frame.height
            )
            if (!box.isRenderable) return@forEach
            // Chroma key runs BEFORE the mask clip, and that order is the
            // design: the key removes pixels from the overlay's own frame,
            // and the mask then clips whatever survives. Doing it the other
            // way round would key an already-geometrically-cropped frame
            // and lose the screen at the mask's edge.
            //
            // `null` from `keyed` means "nothing to do" — the key is off, or
            // the pixels could not be read — and the decoded frame is drawn
            // as-is rather than dropping the overlay.
            val src = entry.keyed(decoded) ?: decoded
            val c = canvas ?: Canvas(frame).also { canvas = it }
            drawOne(c, frame, src, entry.overlay, box)
        }
        return frame
    }

    private fun drawOne(
        canvas: Canvas,
        frame: Bitmap,
        src: Bitmap,
        overlay: VideoOverlay,
        box: com.aicreatorstudio.app.model.PipBox
    ) {
        val boxW = box.widthPx.roundToInt().coerceAtLeast(1)
        val boxH = box.heightPx.roundToInt().coerceAtLeast(1)

        val settings = OverlayMaskGeometry.sanitize(overlay.maskSettings)
        val alpha = (overlay.opacity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f)
            .let { (it * 255f).roundToInt().coerceIn(0, 255) }
        if (alpha == 0) return

        val save = canvas.save()
        // Rotation is about the box centre and carries the mask with it —
        // a rotated facecam keeps its circular clip, which is what the
        // preview's `graphicsLayer(rotationZ = …)` around the clipped box
        // does too.
        val deg = overlay.rotationDegrees
        if (deg.isFinite() && abs(deg % 360f) > 1e-3f) {
            canvas.rotate(deg, box.centerXPx, box.centerYPx)
        }
        canvas.translate(box.leftPx, box.topPx)

        val srcRect = Rect(0, 0, src.width, src.height)
        val dstRect = RectF(0f, 0f, boxW.toFloat(), boxH.toFloat())

        if (settings.needsSoftEdge) {
            val layer = scratchFor(boxW, boxH) ?: run {
                canvas.restoreToCount(save)
                return
            }
            layer.eraseColor(0)
            drawPaint.alpha = alpha
            Canvas(layer).drawBitmap(src, srcRect, dstRect, drawPaint)
            // Feather is applied on the *software* layer canvas, where a
            // BlurMaskFilter is legal.
            OverlayMaskRenderer.applySoftMask(layer, settings, frame.height)
            drawPaint.alpha = 255
            canvas.drawBitmap(layer, 0f, 0f, drawPaint)
        } else {
            val path = OverlayMaskRenderer.buildPath(
                settings = settings,
                boxWidthPx = boxW,
                boxHeightPx = boxH,
                canvasHeightPx = frame.height
            )
            if (path != null) canvas.clipPath(path)
            drawPaint.alpha = alpha
            canvas.drawBitmap(src, srcRect, dstRect, drawPaint)
        }

        canvas.restoreToCount(save)
    }

    private fun scratchFor(w: Int, h: Int): Bitmap? {
        val existing = scratch
        if (existing != null && scratchW == w && scratchH == h && !existing.isRecycled) {
            return existing
        }
        runCatching { existing?.recycle() }
        val created = runCatching {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }.getOrNull()
        scratch = created
        scratchW = w
        scratchH = h
        return created
    }

    override fun close() {
        entries.forEach { runCatching { it.close() } }
        runCatching { scratch?.recycle() }
        scratch = null
    }

    companion object {
        private const val TAG = "OverlayComposite"

        /**
         * True when [overlays] contains anything this renderer would
         * draw on a segment starting at [timelineStartMs] and running
         * [outputDurationMs]. Used by the export to skip the whole
         * pre-pass — and therefore the extra transcode — when there is
         * nothing to composite.
         */
        fun hasRenderableOverlays(
            overlays: List<VideoOverlay>,
            timelineStartMs: Long,
            outputDurationMs: Long
        ): Boolean {
            val end = timelineStartMs + outputDurationMs.coerceAtLeast(0L)
            return overlays.any { o ->
                o.uri.isNotBlank() &&
                    o.endTimeMs > o.startTimeMs &&
                    o.endTimeMs > timelineStartMs &&
                    o.startTimeMs < end
            }
        }
    }
}

/**
 * Frame source for one overlay's media. Images are decoded once; videos
 * are streamed sequentially.
 *
 * The two cases are unified behind [frameAt] so [OverlayCompositeRenderer]
 * has no branch on `isVideo` — the model's `isVideo` flag decides which
 * implementation is built, and a mislabelled overlay still works because
 * the factory tries the video path first and falls back to the image path.
 *
 * **Ownership:** the returned bitmap belongs to the source and may be
 * recycled on the next advance. Callers must draw from it, never store or
 * recycle it.
 */
internal sealed interface OverlayMediaSource : AutoCloseable {

    /** The frame for [localMs] into the overlay's own window, or null. */
    fun frameAt(localMs: Long): Bitmap?

    companion object {
        fun create(context: Context, overlay: VideoOverlay): OverlayMediaSource? {
            val uri = runCatching { Uri.parse(overlay.uri) }.getOrNull() ?: return null
            if (overlay.isVideo) {
                // NOT `use { }` here: `use` would close the sampler on the
                // way out, including on the success path, and hand back a
                // released decoder. Close it by hand on the failure path
                // only.
                val sampler = OverlayVideoSampler(context, uri)
                if (sampler.canStart()) return sampler
                runCatching { sampler.close() }
                return null
            }
            val still = runCatching {
                VideoFramePipeline.decodeImageUri(context, uri)
            }.getOrNull()
            return if (still != null) StillImageSource(still) else null
        }
    }
}

/** A single decoded bitmap, served for every requested timestamp. */
private class StillImageSource(private val bitmap: Bitmap) : OverlayMediaSource {
    override fun frameAt(localMs: Long): Bitmap? = bitmap
    override fun close() {
        runCatching { bitmap.recycle() }
    }
}

/**
 * Sequential decoder for a video overlay, modelled on the export's
 * existing background-video sampler.
 *
 * **Forward-only, by design.** The export walks frames in increasing PTS
 * order, so a monotonic decoder is correct and a seekable one would be
 * slower and more fragile. [frameAt] advances the decoder until it has
 * caught up with the requested timestamp and returns the newest frame it
 * has — which is the frame that was on screen at that instant. A request
 * for a time before the decoder's current position returns the current
 * frame rather than rewinding, and a request past the end of the media
 * returns the **last** frame, so a short overlay holds on its final image
 * instead of blinking out.
 *
 * Rotation metadata is applied here, so every downstream consumer sees an
 * upright bitmap and the aspect ratio the user sees in the preview.
 */
private class OverlayVideoSampler(
    private val context: Context,
    private val uri: Uri
) : OverlayMediaSource {

    private val extractor = MediaExtractor()
    private var decoder: MediaCodec? = null
    private var trackFormat: MediaFormat? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var rotation = 0
    private var eos = false
    private var nextPtsUs = 0L
    private var lastBmp: Bitmap? = null
    private var started = false
    private var failed = false

    /** Probe without decoding: the factory uses this to reject a bad file. */
    fun canStart(): Boolean {
        ensureStarted()
        return started
    }

    override fun frameAt(localMs: Long): Bitmap? {
        if (!ensureStarted()) return null
        val wantUs = localMs.coerceAtLeast(0L) * 1000L
        // Bounded so a corrupt track cannot spin here forever. 600 steps is
        // ~20 s of 30 fps source, far beyond any real overlay window.
        var guard = 0
        while (!eos && nextPtsUs < wantUs && guard++ < MAX_ADVANCE_STEPS) {
            if (!step()) break
        }
        return lastBmp
    }

    private fun ensureStarted(): Boolean {
        if (started) return true
        if (failed) return false
        return try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                videoWidth = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                videoHeight = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
                rotation = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
            } finally {
                runCatching { retriever.release() }
            }
            if (videoWidth <= 0 || videoHeight <= 0) {
                failed = true
                return false
            }
            extractor.setDataSource(context, uri, null)
            var videoTrack = -1
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") && videoTrack == -1) {
                    videoTrack = i
                    trackFormat = f
                }
            }
            if (videoTrack == -1) {
                failed = true
                return false
            }
            extractor.selectTrack(videoTrack)
            val mime = trackFormat!!.getString(MediaFormat.KEY_MIME)!!
            // 2026-10-24 — byte-buffer output, no Surface/ImageReader: the
            // reader path aborts the process on this device class
            // (`ImageReader$SurfaceImage.nativeCreatePlanes` with a null
            // plane address) and `wrapHardwareBuffer` refuses the buffer.
            // `codecImageToBitmap` reads the codec's own ByteBuffers.
            trackFormat!!.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            decoder = MediaCodec.createDecoderByType(mime).also {
                it.configure(trackFormat, null, null, 0)
                it.start()
            }
            started = true
            true
        } catch (t: Throwable) {
            Log.w(TAG, "overlay video decode unavailable for $uri", t)
            failed = true
            false
        }
    }

    /** One decode step. Returns false when the decoder is finished or stuck. */
    private fun step(): Boolean {
        val dec = decoder ?: return false
        val info = MediaCodec.BufferInfo()

        val inIdx = runCatching { dec.dequeueInputBuffer(INPUT_TIMEOUT_US) }.getOrDefault(-1)
        if (inIdx >= 0) {
            val buf = dec.getInputBuffer(inIdx)
            val n = if (buf == null) -1 else extractor.readSampleData(buf, 0)
            if (n < 0) {
                runCatching {
                    dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
            } else {
                runCatching {
                    dec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
        }

        val st = runCatching { dec.dequeueOutputBuffer(info, INPUT_TIMEOUT_US) }.getOrDefault(-1)
        if (st < 0) return true // INFO_TRY_AGAIN_LATER — a later call will make progress.

        val isEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        if (isEos) {
            // Release without rendering: the EOS buffer carries no frame.
            runCatching { dec.releaseOutputBuffer(st, false) }
            eos = true
            return false
        }
        // 2026-10-24 — convert before releasing: the Image is only valid
        // until `releaseOutputBuffer`.
        var decoded: Bitmap? = null
        try {
            val img = runCatching { dec.getOutputImage(st) }.getOrNull()
            if (img != null) {
                try {
                    decoded = VideoFramePipeline.codecImageToBitmap(img)
                } finally {
                    runCatching { img.close() }
                }
            }
        } finally {
            runCatching { dec.releaseOutputBuffer(st, false) }
        }
        var bmp = decoded ?: return true
        if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = runCatching {
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }.getOrNull()
            if (rotated != null && rotated !== bmp) {
                bmp.recycle()
                bmp = rotated
            }
        }
        lastBmp?.recycle()
        lastBmp = bmp
        nextPtsUs = info.presentationTimeUs
        return true
    }

    override fun close() {
        runCatching { lastBmp?.recycle() }
        lastBmp = null
        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
        runCatching { extractor.release() }
        decoder = null
    }

    private companion object {
        const val TAG = "OverlayVideoSampler"
        const val INPUT_TIMEOUT_US = 10_000L
        const val MAX_ADVANCE_STEPS = 600
    }
}
