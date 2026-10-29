package com.aicreatorstudio.app.ai.upscale

import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.ceil
import kotlin.math.min

/**
 * Tiled ESRGAN inference for video frames.
 *
 * Runs the official TensorFlow ESRGAN TFLite model
 * (`models.lock.json` → `esrgan_tflite_4x`, scale 4x, float32
 * RGB 0..255 in/out per the reference Android demo) on arbitrary
 * frame sizes by splitting each frame into overlapping tiles,
 * running the network per tile, and crossfading the overlap band
 * so tile seams never show.
 *
 * ## Why tiles
 *
 * A 1080p frame upscaled 4x is a 33 MP float output (~400 MB) —
 * far beyond a phone's per-allocation budget. Tiles (default
 * 256 px input → 1024 px output) keep every allocation small and
 * let low-RAM devices run with smaller tiles.
 *
 * ## Threads / backend
 *
 * GPU delegate when [CompatibilityList] supports this device,
 * else 4 CPU threads (the reference demo's `kThreadNum`). The
 * active backend is exposed via [backendName] for the progress UI
 * and the final report — never invented.
 *
 * ## Lifecycle
 *
 * One instance per upscale job: [load], then [upscale] per frame,
 * then [close]. All heavy work runs on the caller's thread
 * (`Dispatchers.IO` upstream). Not thread-safe by design — video
 * frames are processed strictly sequentially.
 */
class EsrganTiler private constructor(
    private val interpreter: Interpreter,
    private val gpuDelegate: GpuDelegate?,
    val inputTile: Int,
    /** Native network scale (output / input, e.g. 4). */
    val netScale: Int,
    val backendName: String,
    /**
     * 2026-10-20 — the model's **native input edge in pixels**, or 0
     * when the graph exposes a dynamic shape.
     *
     * The bundled `ESRGAN.tflite` is hard-fixed at
     * `input_0: FLOAT32 [1,50,50,3]` → `Identity: FLOAT32 [1,200,200,3]`
     * (verified by parsing the flatbuffer: there is no `shape_signature`,
     * so the input dims are constant). The network therefore only ever
     * sees 50×50 source pixels.
     *
     * That single fact is what makes the tiling geometry load-bearing:
     * a tile larger than 50 px is *downscaled* into the network, so the
     * network's 200×200 answer carries only 50×50 worth of real
     * detail. Tiling at the native edge is the only way to get a
     * genuine per-pixel 4× — but it costs one inference per 50 px
     * block, which is far too many for full-HD video. [resolveTileEdge]
     * picks the native edge when the frame is small enough to afford
     * it and degrades to the device-sized tile otherwise.
     */
    val nativeEdge: Int
) : AutoCloseable {

    companion object {
        private const val TAG = "EsrganTiler"

        /** Default input tile edge (px) for mid/high-end devices. */
        const val TILE_DEFAULT: Int = 256

        /** Input tile edge (px) for low-RAM devices. */
        const val TILE_LOW_RAM: Int = 160

        /**
         * Overlap between adjacent tiles (px, input space). ESRGAN's
         * receptive field bleeds ~10 px, so an 8–16 px crossfade
         * band hides seams without doubling the tile count.
         */
        const val OVERLAP_DEFAULT: Int = 16

        /**
         * 2026-10-20 — upper bound on network invocations per frame.
         *
         * Tiling at the model's native edge is the only way to get a
         * true per-pixel 4× (the graph is fixed at 50×50), but it
         * costs one inference per 50 px block: a 1080p frame would
         * need ~1000 of them, which is minutes per frame. When the
         * native tiling exceeds this budget the tile is grown (and the
         * network sees a downscaled patch) so the job stays
         * interactive. The result is softer but geometrically correct.
         */
        const val MAX_TILES_PER_FRAME: Int = 256

        /**
         * Load the model from [modelFile] and probe its tensors.
         * Tries the GPU delegate first, falls back to 4-thread CPU.
         * Throws on any failure (missing file, bad flatbuffer,
         * unsupported ops) with the real cause — never a silent null.
         *
         * 2026-10-20 — THE GPU DELEGATE IS VALIDATED BEFORE USE.
         *
         * The GPU delegate was previously trusted on the strength of
         * `CompatibilityList().isDelegateSupportedOnThisDevice`, which
         * only reports the device's **OpenGL ES version**. It says
         * nothing about whether the delegate's compute path actually
         * WORKS on that GPU: the shaders are compiled lazily on the
         * first inference, and on a driver that advertises GLES 3.1+
         * but has a broken compute path the first `run()` fails — and
         * in the GPU delegate that failure is frequently a native
         * abort, which no `try/catch` can recover.
         *
         * The delegate is therefore **exercised once, at load time**,
         * with a real inference at the model's own input shape (so the
         * same shaders get compiled as in production). If that warm-up
         * throws, the delegate is dropped and the interpreter is
         * rebuilt on the CPU. The failure therefore costs one wasted
         * load instead of the whole upscale run.
         *
         * This matters because the GPU delegate is used **only on
         * flagships**: `EsrganUpscalePipeline` enables it when
         * `gpuSupportsCompute && targetScale == 4`, which is exactly
         * the "powerful phone" profile. A driver-specific failure
         * there is invisible on every other device.
         */
        fun load(modelFile: File, preferGpu: Boolean, tileSize: Int = TILE_DEFAULT): EsrganTiler {
            require(modelFile.exists() && modelFile.length() > 0) {
                "ESRGAN model missing: ${modelFile.absolutePath}"
            }
            val buffer = mapFile(modelFile)

            // ---- attempt 1: with the GPU delegate ----
            if (preferGpu) {
                val supported = runCatching {
                    CompatibilityList().use { it.isDelegateSupportedOnThisDevice }
                }.getOrDefault(false)
                if (!supported) {
                    Log.i(TAG, "GPU delegate unsupported on this device; using CPU")
                } else {
                    val gpu = runCatching { GpuDelegate() }.getOrNull()
                    if (gpu == null) {
                        Log.i(TAG, "GPU delegate init failed; using CPU")
                    } else {
                        val options = Interpreter.Options().apply { addDelegate(gpu) }
                        val built = runCatching { Interpreter(buffer, options) }.getOrNull()
                        if (built == null) {
                            runCatching { gpu.close() }
                            Log.i(TAG, "GPU-backed interpreter could not be built; using CPU")
                        } else {
                            val warmed = runCatching { warmUp(built) }
                            if (warmed.isSuccess) {
                                Log.i(TAG, "GPU delegate validated by a warm-up inference")
                                return finishLoad(
                                    modelFile = modelFile,
                                    buffer = buffer,
                                    interpreter = built,
                                    delegate = gpu,
                                    backend = "tflite-gpu",
                                    tileSize = tileSize
                                )
                            }
                            Log.w(
                                TAG,
                                "GPU delegate failed its warm-up inference; falling back to CPU",
                                warmed.exceptionOrNull()
                            )
                            runCatching { built.close() }
                            runCatching { gpu.close() }
                        }
                    }
                }
            }

            // ---- attempt 2: plain CPU ----
            val options = Interpreter.Options().apply { setNumThreads(4) }
            val interpreter = try {
                Interpreter(buffer, options)
            } catch (t: Throwable) {
                throw t
            }
            return finishLoad(
                modelFile = modelFile,
                buffer = buffer,
                interpreter = interpreter,
                delegate = null,
                backend = "tflite-cpu-4t",
                tileSize = tileSize
            )
        }

        /**
         * Run ONE inference at the model's own input shape so the
         * delegate compiles the same shaders the real run will use.
         *
         * A zero-filled input is enough: the point is to exercise the
         * delegate's shader compilation and dispatch, not to produce a
         * meaningful image.
         */
        private fun warmUp(interpreter: Interpreter): Unit {
            val inShape = interpreter.getInputTensor(0).shape()
            require(inShape.size == 4) { "unexpected ESRGAN input rank ${inShape.toList()}" }
            val batch = inShape[0].coerceAtLeast(1)
            val h = inShape[1]
            val w = inShape[2]
            val c = inShape[3]
            require(h > 0 && w > 0 && c > 0) {
                "ESRGAN input shape is dynamic (${inShape.toList()}) — cannot warm up"
            }
            val outShape = interpreter.getOutputTensor(0).shape()
            val outBytes = outShape.fold(1) { acc, d -> acc * d.coerceAtLeast(1) } * 4
            val input = ByteBuffer
                .allocateDirect(batch * h * w * c * 4)
                .order(ByteOrder.nativeOrder())
            // Zero-filled: `allocateDirect` already zeroes, but be
            // explicit so the intent survives a refactor.
            repeat(batch * h * w * c) { input.putFloat(0f) }
            input.rewind()
            val output = ByteBuffer.allocateDirect(outBytes).order(ByteOrder.nativeOrder())
            interpreter.run(input, output)
            output.rewind()
        }

        /**
         * Finish the load: probe the tensors, derive the scale and
         * the native edge, and build the tiler.
         */
        private fun finishLoad(
            modelFile: File,
            buffer: MappedByteBuffer,
            interpreter: Interpreter,
            delegate: GpuDelegate?,
            backend: String,
            tileSize: Int
        ): EsrganTiler {
            try {
                val inShape = interpreter.getInputTensor(0).shape() // [1,h,w,3]
                val outShape = interpreter.getOutputTensor(0).shape()
                require(inShape.size == 4 && outShape.size == 4) {
                    "unexpected ESRGAN tensor rank in=${inShape.toList()} out=${outShape.toList()}"
                }
                require(interpreter.getInputTensor(0).dataType() == DataType.FLOAT32) {
                    "ESRGAN input must be FLOAT32"
                }
                val inH = inShape[1]
                val inW = inShape[2]
                val outH = outShape[1]
                val outW = outShape[2]
                val scaleH = if (inH > 0) outH.toFloat() / inH else 4f
                val scaleW = if (inW > 0) outW.toFloat() / inW else 4f
                val scale = min(scaleH, scaleW).toInt().coerceAtLeast(2)
                // 2026-10-20 — the native input edge, when the graph
                // pins it. `inShape` carries a concrete number only for
                // a fixed-shape graph; a dynamic graph reports -1.
                val nativeEdge = if (inW > 0 && inH > 0 && inW == inH) inW else 0
                Log.i(TAG, "ESRGAN loaded: in=${inShape.toList()} out=${outShape.toList()} " +
                    "scale=$scale nativeEdge=$nativeEdge backend=$backend")
                val tile = tileSize.coerceAtLeast(maxOf(32, inW.takeIf { it > 0 } ?: 32))
                return EsrganTiler(interpreter, delegate, tile, scale, backend, nativeEdge)
            } catch (t: Throwable) {
                runCatching { interpreter.close() }
                runCatching { delegate?.close() }
                throw t
            }
        }

        private fun mapFile(file: File): MappedByteBuffer {
            FileInputStream(file).use { fis ->
                return fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
            }
        }
    }

    /**
     * Resolve the tile edge to use for a frame of [w]×[h].
     *
     * 2026-10-20 — see [nativeEdge]. Prefers the model's native input
     * edge (true 4×, one inference per 50 px block) and degrades to a
     * larger, downscaled tile when the native tiling would need more
     * than [MAX_TILES_PER_FRAME] network calls.
     */
    private fun resolveTileEdge(w: Int, h: Int, overlap: Int): Int {
        val deviceTile = inputTile
        if (nativeEdge <= 0 || nativeEdge >= deviceTile) return deviceTile
        // Grow the tile by doubling until the call count fits.
        var t = nativeEdge
        while (t < deviceTile) {
            if (tileCount(w, h, t, overlap) <= MAX_TILES_PER_FRAME) return t
            t *= 2
        }
        return deviceTile
    }

    /** Number of tiles a [w]×[h] frame needs at the given tile edge. */
    private fun tileCount(w: Int, h: Int, tile: Int, overlap: Int): Int {
        val ov = overlap.coerceAtMost(tile / 4)
        val step = (tile - ov).coerceAtLeast(1)
        val cols = ceil((w - ov).toFloat() / step).toInt().coerceAtLeast(1)
        val rows = ceil((h - ov).toFloat() / step).toInt().coerceAtLeast(1)
        return cols * rows
    }

    /**
     * Upscale [src] by [targetScale] (2 or 4) with the network.
     *
     * - 4x: direct tiled ESRGAN.
     * - 2x: bilinear-prefilter the frame to 0.5x, then ESRGAN 4x
     *   (net exactly 2x, still fully neural — the prefilter only
     *   sets the operating resolution, exactly how the 2x profile
     *   is documented in the UI).
     *
     * 2026-09-08 CRASH FIX — [fitOutW]/[fitOutH] (both 0 by default)
     * cap the returned bitmap. When set, the neural pass still runs
     * at the FULL factor (the sharpening comes from the inference)
     * but tiles are drawn fit-down into the capped bitmap — no
     * oversized intermediate is ever allocated. This is what lets a
     * 1080p 4x run land on a 4K output without OOM or an 8K encoder.
     *
     * Returns a new bitmap sized `src * targetScale` (or fitOut×fitH
     * when capped; aspect preserved). Recycles nothing the caller
     * owns; tile scratch bitmaps are recycled before return.
     *
     * @param onTile called after each tile with (done, total) for
     *   real progress.
     */
    fun upscale(
        src: Bitmap,
        targetScale: Int,
        fitOutW: Int = 0,
        fitOutH: Int = 0,
        overlap: Int = OVERLAP_DEFAULT,
        onTile: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Bitmap {
        require(targetScale == 2 || targetScale == 4) { "targetScale must be 2 or 4" }
        val capped = fitOutW > 0 && fitOutH > 0
        val work: Bitmap
        val netOut: Bitmap
        if (targetScale == netScale) {
            work = src
            netOut = if (capped) upscaleNativeCapped(src, fitOutW, fitOutH, overlap, onTile)
            else upscaleNative(work, overlap, onTile)
        } else if (targetScale == 2 && netScale == 4) {
            // 0.5x prefilter, then 4x network = net 2x.
            val half = Bitmap.createScaledBitmap(
                src, (src.width / 2).coerceAtLeast(16),
                (src.height / 2).coerceAtLeast(16), true
            )
            try {
                netOut = if (capped) upscaleNativeCapped(half, fitOutW, fitOutH, overlap, onTile)
                else upscaleNative(half, overlap, onTile)
            } finally {
                half.recycle()
            }
            work = src // (kept for readability of the contract)
        } else {
            // A future model with a different native scale: run the
            // network, then exact-scale to the target. Still neural.
            val native = if (capped) upscaleNativeCapped(src, fitOutW, fitOutH, overlap, onTile)
            else upscaleNative(src, overlap, onTile)
            try {
                netOut = Bitmap.createScaledBitmap(
                    native,
                    if (capped) fitOutW else src.width * targetScale,
                    if (capped) fitOutH else src.height * targetScale,
                    true
                )
            } finally {
                native.recycle()
            }
        }
        return netOut
    }

    /**
     * Capped variant: the neural pass runs per-tile at full factor,
     * each tile result is bilinear-fit into its rectangle of the
     * CAPPED output bitmap. Never allocates anything larger than
     * [fitOutW]×[fitOutH] plus one tile result.
     */
    private fun upscaleNativeCapped(
        src: Bitmap,
        fitOutW: Int,
        fitOutH: Int,
        overlap: Int,
        onTile: (done: Int, total: Int) -> Unit
    ): Bitmap {
        val out = Bitmap.createBitmap(fitOutW, fitOutH, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or
                android.graphics.Paint.ANTI_ALIAS_FLAG)

        val w = src.width
        val h = src.height
        // 2026-10-20 — the tile edge is resolved per frame so the
        // model's native resolution is used when the frame is small
        // enough to afford it.
        val tileEdge = resolveTileEdge(w, h, overlap)
        // 2026-10-20 — the overlap must stay a sensible fraction of
        // the tile: a fixed 16 px band on a 50 px tile would eat a
        // third of every tile.
        val ov = overlap.coerceAtMost(tileEdge / 4)
        val step = (tileEdge - ov).coerceAtLeast(1)
        val cols = ceil((w - ov).toFloat() / step).toInt().coerceAtLeast(1)
        val rows = ceil((h - ov).toFloat() / step).toInt().coerceAtLeast(1)
        val total = cols * rows
        var done = 0

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val x0 = min(c * step, w - tileEdge.coerceAtMost(w))
                val y0 = min(r * step, h - tileEdge.coerceAtMost(h))
                val tw = min(tileEdge, w - x0)
                val th = min(tileEdge, h - y0)
                if (tw <= 0 || th <= 0) { done++; onTile(done, total); continue }
                val tile = Bitmap.createBitmap(src, x0, y0, tw, th)
                try {
                    val up = runTile(tile)
                    try {
                        // Edge flags for the crossfade (frame-boundary
                        // edges stay opaque).
                        val fadeLeft = x0 > 0
                        val fadeTop = y0 > 0
                        val fadeRight = (x0 + tw) < w
                        val fadeBottom = (y0 + th) < h
                        val overlapOut = (ov * netScale).coerceAtLeast(1)
                        // 2026-10-20 — read the dimensions BEFORE the
                        // bitmap is recycled. The old code called
                        // `up.recycle()` and then `up.width`, which
                        // throws IllegalStateException("Can't call
                        // getWidth() on a recycled bitmap") on every
                        // single tile — the whole AI Upscale feature
                        // died on its first tile.
                        val upW = up.width
                        val upH = up.height
                        val alphaMask = buildAlphaMask(
                            upW, upH, overlapOut,
                            fadeLeft, fadeTop, fadeRight, fadeBottom
                        )
                        val masked = up.copy(Bitmap.Config.ARGB_8888, true)
                        up.recycle()
                        val px = IntArray(masked.width * masked.height)
                        masked.getPixels(px, 0, masked.width, 0, 0, masked.width, masked.height)
                        for (i in px.indices) {
                            px[i] = (px[i] and 0x00FFFFFF) or (alphaMask[i] shl 24)
                        }
                        masked.setPixels(px, 0, masked.width, 0, 0, masked.width, masked.height)
                        // Map this tile's source rect to its rect in
                        // the CAPPED output (uniform scale + centered
                        // letterbox is not needed — fit uses the same
                        // aspect, so a pure scale factor applies).
                        //
                        // 2026-10-20 — the drawn size is the TILE's
                        // neural output size (`tw * netScale`), NOT the
                        // raw network output width. The old code used
                        // `up.width`, which is the network's own output
                        // (200 px for the bundled model) — that made
                        // every tile ~5x too small and left most of the
                        // output frame empty.
                        val sx = fitOutW.toFloat() / (w * netScale)
                        val sy = fitOutH.toFloat() / (h * netScale)
                        val s = minOf(sx, sy)
                        val drawW = tw * netScale * s
                        val drawH = th * netScale * s
                        val dx = x0 * netScale * s
                        val dy = y0 * netScale * s
                        // 1px overlap bleed: draw tiles 1 output-px
                        // larger so bilinear rounding never leaves
                        // hairline gaps at tile joints.
                        canvas.drawBitmap(
                            masked, null,
                            android.graphics.RectF(
                                dx, dy,
                                dx + drawW + 1f,
                                dy + drawH + 1f
                            ),
                            paint
                        )
                        masked.recycle()
                    } finally {
                        up.recycle()
                    }
                } finally {
                    tile.recycle()
                }
                done++
                onTile(done, total)
            }
        }
        return out
    }

    /**
     * Raw network pass: [src] -> [src]*[netScale] via overlap tiles.
     *
     * 2026-09-08 OOM FIX — the old stitcher allocated FOUR
     * FloatArrays of `outW*outH` (for a 1080p 4x frame that is
     * 35 MP x 4 arrays x 4 bytes ≈ 560 MB) and the per-tile pixel
     * scratch IntArray on top, which OOM-crashed mid-run on every
     * real device. The stitcher now draws each upscaled tile
     * DIRECTLY into the output bitmap with a Paint alpha crossfade
     * (peak extra memory = one tile's output bitmap, ~4 MB for the
     * default 256px tile at 4x). Seams are still hidden because the
     * crossfade weight is applied to the tile bitmap's alpha before
     * drawing.
     */
    private fun upscaleNative(
        src: Bitmap,
        overlap: Int,
        onTile: (done: Int, total: Int) -> Unit
    ): Bitmap {
        val w = src.width
        val h = src.height
        val outW = w * netScale
        val outH = h * netScale
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val outCanvas = android.graphics.Canvas(out)
        val tilePaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)

        // 2026-10-20 — per-frame tile edge + a tile-proportional
        // overlap (a fixed 16 px band on a 50 px native tile would eat
        // a third of every tile).
        val tileEdge = resolveTileEdge(w, h, overlap)
        val ov = overlap.coerceAtMost(tileEdge / 4)
        val step = (tileEdge - ov).coerceAtLeast(1)
        val cols = ceil((w - ov).toFloat() / step).toInt().coerceAtLeast(1)
        val rows = ceil((h - ov).toFloat() / step).toInt().coerceAtLeast(1)
        val total = cols * rows
        var done = 0
        val overlapOut = (ov * netScale).coerceAtLeast(1)

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val x0 = min(c * step, w - tileEdge.coerceAtMost(w))
                val y0 = min(r * step, h - tileEdge.coerceAtMost(h))
                val tw = min(tileEdge, w - x0)
                val th = min(tileEdge, h - y0)
                if (tw <= 0 || th <= 0) { done++; onTile(done, total); continue }
                val tile = Bitmap.createBitmap(src, x0, y0, tw, th)
                try {
                    val up = runTile(tile) // tw*netScale x th*netScale
                    try {
                        // Edge flags: a tile edge facing the FRAME
                        // boundary (no neighbour beyond) stays fully
                        // opaque so the frame edge never fades out.
                        val fadeLeft = x0 > 0
                        val fadeTop = y0 > 0
                        val fadeRight = (x0 + tw) < w
                        val fadeBottom = (y0 + th) < h
                        // Build a crossfade alpha mask: opaque in the
                        // tile center, linearly fading to transparent
                        // at the tile's output-space edges so overlaps
                        // between neighbours blend instead of seam.
                        val alphaMask = buildAlphaMask(
                            up.width, up.height, overlapOut,
                            fadeLeft, fadeTop, fadeRight, fadeBottom
                        )
                        val masked = up.copy(Bitmap.Config.ARGB_8888, true)
                        up.recycle()
                        val px = IntArray(masked.width * masked.height)
                        masked.getPixels(px, 0, masked.width, 0, 0, masked.width, masked.height)
                        for (i in px.indices) {
                            px[i] = (px[i] and 0x00FFFFFF) or (alphaMask[i] shl 24)
                        }
                        masked.setPixels(px, 0, masked.width, 0, 0, masked.width, masked.height)
                        outCanvas.drawBitmap(
                            masked, null,
                            android.graphics.RectF(
                                x0 * netScale.toFloat(),
                                y0 * netScale.toFloat(),
                                (x0 + tw) * netScale.toFloat(),
                                (y0 + th) * netScale.toFloat()
                            ),
                            tilePaint
                        )
                        masked.recycle()
                    } catch (inner: Throwable) {
                        runCatching { up.recycle() }
                        throw inner
                    }
                } finally {
                    tile.recycle()
                }
                done++
                onTile(done, total)
            }
        }
        return out
    }

    /**
     * Per-tile alpha mask (0..255 per pixel): full opacity in the
     * interior, linear ramp to 0 across the [ov] band at each tile
     * edge — EXCEPT the edges flagged with `fade*=false`, which face
     * the frame boundary (no neighbour beyond) and stay fully opaque
     * so the frame edge never fades to black.
     */
    private fun buildAlphaMask(
        w: Int,
        h: Int,
        ov: Int,
        fadeLeft: Boolean,
        fadeTop: Boolean,
        fadeRight: Boolean,
        fadeBottom: Boolean
    ): IntArray {
        val mask = IntArray(w * h) { 255 }
        val o = ov.coerceAtLeast(1)
        for (y in 0 until h) {
            val fy = when {
                !fadeTop || y >= o -> 255
                else -> (255f * y / o).toInt().coerceIn(0, 255)
            }
            val fyEnd = when {
                !fadeBottom || y < h - o -> 255
                else -> (255f * (h - 1 - y) / o).toInt().coerceIn(0, 255)
            }
            val rowY = min(fy, fyEnd)
            for (x in 0 until w) {
                val fx = when {
                    !fadeLeft || x >= o -> 255
                    else -> (255f * x / o).toInt().coerceIn(0, 255)
                }
                val fxEnd = when {
                    !fadeRight || x < w - o -> 255
                    else -> (255f * (w - 1 - x) / o).toInt().coerceIn(0, 255)
                }
                mask[y * w + x] = min(rowY, min(fx, fxEnd))
            }
        }
        return mask
    }

    /**
     * Single network invocation on a small bitmap. Matches the
     * reference demo exactly: float32 RGB 0..255 in, clipped
     * float32 RGB 0..255 out.
     *
     * 2026-09-08 STABILITY FIX — the old path called
     * `resizeInput + allocateTensors` for EVERY edge tile whose size
     * differed from the baked shape. TFLite re-allocates all arena
     * buffers on every resize; combined with 40+ tiles per frame
     * this fragmented native memory and crashed long jobs. Now:
     * edge tiles are scaled UP to the baked input shape with a
     * bilinear pre-scale (identical to what the network would see
     * after its own scale, and the stitcher scales the result back
     * down via the contract guard below) — so the interpreter runs
     * the ENTIRE job at ONE fixed tensor shape. Zero re-allocations
     * after the first tile.
     */
    private fun runTile(tile: Bitmap): Bitmap {
        val inTensor = interpreter.getInputTensor(0)
        val shape = inTensor.shape()
        val wantH = shape[1]
        val wantW = shape[2]
        // 2026-10-20 — the OUTPUT size the stitcher expects is the
        // ORIGINAL tile's neural size, not the size of the resized
        // working patch. The old code computed `expectW = iw * netScale`
        // from the *resized* bitmap (50 px for the bundled model), so
        // the contract it returned was 200 px for a 256 px tile — and
        // the stitcher then stretched that 200 px over a 1024 px
        // region, which is why the output was geometrically wrong.
        val expectW = tile.width * netScale
        val expectH = tile.height * netScale
        val work: Bitmap
        val recycleWork: Boolean
        if (wantH > 0 && wantW > 0 && (tile.width != wantW || tile.height != wantH)) {
            // Fixed-shape path: bilinear-scale the tile to the
            // baked shape. The stitcher then scales the neural answer
            // back up to the tile's true (tw*netScale) size.
            work = Bitmap.createScaledBitmap(tile, wantW, wantH, true)
            recycleWork = true
        } else {
            work = tile
            recycleWork = false
        }
        try {
            val iw = work.width
            val ih = work.height
            val inPx = IntArray(iw * ih)
            work.getPixels(inPx, 0, iw, 0, 0, iw, ih)
            val input = ByteBuffer.allocateDirect(iw * ih * 3 * 4).order(ByteOrder.nativeOrder())
            for (p in inPx) {
                input.putFloat(((p shr 16) and 0xFF).toFloat())
                input.putFloat(((p shr 8) and 0xFF).toFloat())
                input.putFloat((p and 0xFF).toFloat())
            }
            input.rewind()
            val outShape = interpreter.getOutputTensor(0).shape()
            val ow = outShape[2]
            val oh = outShape[1]
            require(ow > 0 && oh > 0) { "dynamic output shape unsupported: ${outShape.toList()}" }
            val output = ByteBuffer.allocateDirect(ow * oh * 3 * 4).order(ByteOrder.nativeOrder())
            interpreter.run(input, output)
            output.rewind()
            val outPx = IntArray(ow * oh)
            for (i in outPx.indices) {
                val r = output.float.toInt().coerceIn(0, 255)
                val g = output.float.toInt().coerceIn(0, 255)
                val b = output.float.toInt().coerceIn(0, 255)
                outPx[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            val raw = Bitmap.createBitmap(outPx, ow, oh, Bitmap.Config.ARGB_8888)
            // Contract guard: the stitcher assumes the tile's
            // (tw*netScale) output. When the baked shape differs from
            // the tile, scale the neural answer to that exact size.
            if (raw.width == expectW && raw.height == expectH) return raw
            val fixed = Bitmap.createScaledBitmap(raw, expectW, expectH, true)
            raw.recycle()
            return fixed
        } finally {
            if (recycleWork) work.recycle()
        }
    }

    override fun close() {
        runCatching { interpreter.close() }
        runCatching { gpuDelegate?.close() }
    }
}
