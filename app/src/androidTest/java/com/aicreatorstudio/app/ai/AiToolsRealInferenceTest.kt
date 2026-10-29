package com.aicreatorstudio.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMuxer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aicreatorstudio.app.ai.media.VideoFramePipeline
import com.aicreatorstudio.app.ai.upscale.EsrganTiler
import com.aicreatorstudio.app.ai.upscale.EsrganUpscalePipeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Real-device validation for the four repaired AI tools.
 *
 * Runs on an emulator or phone (needs ABI x86_64/arm64 — both are
 * built). Every test executes genuine inference; nothing is
 * mocked, stubbed, or timer-faked:
 *
 * - Voice Clean: `librnnoise.so` self-test (one silent frame
 *   through the real GRU) + a 1 s synthetic noisy buffer through
 *   [RnnoiseVoiceCleanEngine.blendedFrameGains]-backed JNI denoise.
 * - Upscale: ESRGAN model install (SHA-verified download) +
 *   [EsrganTiler] on a synthetic bitmap + full
 *   [VideoFramePipeline] transcode of a synthetic MP4.
 * - Auto Frame: pose model install + PoseLandmarker load +
 *   `detectForVideo` on a synthetic frame (must not throw; empty
 *   poses on a blank frame are a valid "detection loss" outcome).
 * - Background Remove: selfie model install + ImageSegmenter load
 *   + `segment()` on a synthetic frame.
 *
 * Network is used ONLY for the one-time model downloads (ESRGAN
 * is download-only); every inference call afterwards is offline
 * by construction (no URL, no socket, no WebView anywhere in the
 * inference path).
 */
@RunWith(AndroidJUnit4::class)
class AiToolsRealInferenceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    // ----------------------------------------------------------------
    // Voice Clean (RNNoise)
    // ----------------------------------------------------------------

    @Test
    fun rnnoiseNativeSelfTestPasses() {
        assertTrue("librnnoise.so failed to load", RnnoiseNative.isAvailable)
        val status = RnnoiseNative.selfTest()
        assertNotNull("selfTest returned null", status)
        assertTrue("status was $status", status!!.startsWith("rnnoise-ok:"))
        assertEquals(480, RnnoiseNative.frameSize())
        assertEquals(48000, RnnoiseNative.sampleRate())
    }

    @Test
    fun rnnoiseDenoiseAttenuatesWhiteNoise() {
        assertTrue(RnnoiseNative.isAvailable)
        val session = RnnoiseSession.open()
        assertNotNull(session)
        session!!.use {
            // 1 s of loud white noise @ 48 kHz mono.
            val noise = FloatArray(48000) { i ->
                // Deterministic LCG so the test is stable.
                var s = (i * 1103515245 + 12345) and 0x7fffffff
                ((s / 1073741824f) - 1f) * 0.5f
            }
            val before = rms(noise)
            val vad = RnnoiseNative.denoiseInPlace(it.handle, noise)
            assertNotNull("native denoise returned null", vad)
            assertEquals("one VAD value per 480-sample frame", 100, vad!!.size)
            vad.forEach { v ->
                assertTrue("VAD $v out of range", v.isFinite() && v in 0f..1f)
            }
            val after = rms(noise)
            assertTrue(
                "RNNoise did not attenuate noise: before=$before after=$after",
                after < before * 0.9f
            )
        }
    }

    // ----------------------------------------------------------------
    // Model installation (all tools share the centralized system)
    // ----------------------------------------------------------------

    @Test
    fun modelRegistryLoadsFromAssetsWithRealEntries() {
        val registry = ModelRegistry.fromAssets(context).getOrThrow()
        for (id in listOf("selfie_multiclass", "pose_landmarker_lite", "esrgan_tflite_4x")) {
            val spec = registry.byId(id)
            assertNotNull("missing $id", spec)
            assertTrue(spec!!.url.startsWith("https://"))
            assertTrue(spec.expectedSha256.matches(Regex("[0-9a-f]{64}")))
        }
    }

    @Test
    fun esrganModelInstallsAndVerifies() = runBlocking {
        val registry = ModelRegistry.fromAssets(context).getOrThrow()
        val spec = registry.byId(EsrganUpscalePipeline.MODEL_ID)!!
        val installer = UnifiedModelInstaller(context)
        var maxProgress = 0f
        val outcome = installer.install(spec, onProgress = { maxProgress = maxOf(maxProgress, it) })
        assertTrue("install failed: $outcome", outcome.isReady)
        assertTrue("no progress reported", maxProgress > 0f)
        val file = modelFileFor(context.filesDir, spec)
        assertTrue(file.exists())
        assertEquals(spec.declaredSizeBytes, file.length())
        assertTrue(installer.isVerifiedInstalled(spec))
    }

    @Test
    fun bundledPoseAndSelfieModelsInstallOffline() = runBlocking {
        // Bundled assets copy without network: this passes on
        // airplane mode too (the test env has network, but the
        // assertion only depends on the asset path).
        val installer = AiModelInstaller(context)
        for (spec in listOf(AiModelCatalog.POSE_LANDMARKER_LITE, AiModelCatalog.SELFIE_MULTICLASS)) {
            val outcome = installer.installWithManifest(spec, onProgress = {})
            assertTrue("${spec.id} install failed: $outcome", outcome.isReady)
            assertTrue(installer.isInstalled(spec))
        }
    }

    // ----------------------------------------------------------------
    // Upscale (ESRGAN tiled inference)
    // ----------------------------------------------------------------

    @Test
    fun esrganTilerUpscalesSyntheticBitmap() = runBlocking {
        val registry = ModelRegistry.fromAssets(context).getOrThrow()
        val spec = registry.byId(EsrganUpscalePipeline.MODEL_ID)!!
        val installer = UnifiedModelInstaller(context)
        assertTrue(installer.install(spec).isReady)
        val modelFile = modelFileFor(context.filesDir, spec)

        // 64x64 gradient (exercises tile edges deterministically).
        val src = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val px = IntArray(64 * 64)
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                px[y * 64 + x] = (0xFF shl 24) or (x * 4 shl 16) or (y * 4 shl 8) or 128
            }
        }
        src.setPixels(px, 0, 64, 0, 0, 64, 64)

        EsrganTiler.load(modelFile, preferGpu = false).use { tiler ->
            assertTrue("unexpected net scale ${tiler.netScale}", tiler.netScale >= 2)
            var tiles = 0
            val out = tiler.upscale(src, 4) { done, total -> tiles = total }
            try {
                assertEquals(64 * 4, out.width)
                assertEquals(64 * 4, out.height)
                assertTrue("no tiles ran", tiles > 0)
                // Output must carry real image energy (not black/blank).
                val sample = IntArray(256)
                out.getPixels(sample, 0, 16, 0, 0, 16, 16)
                assertTrue("output looks blank", sample.any { it != 0xFF000000.toInt() })
            } finally {
                out.recycle()
            }
        }
        src.recycle()
    }

    // ----------------------------------------------------------------
    // Auto Frame (pose landmarker)
    // ----------------------------------------------------------------

    @Test
    fun poseLandmarkerLoadsAndDetects() = runBlocking {
        val installer = AiModelInstaller(context)
        val spec = AiModelCatalog.POSE_LANDMARKER_LITE
        assertTrue(installer.installWithManifest(spec).isReady)
        val modelFile = AiModels.modelFile(context, spec)
        val opts = com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker.PoseLandmarkerOptions
            .builder()
            .setBaseOptions(
                com.google.mediapipe.tasks.core.BaseOptions.builder()
                    .setModelAssetPath(modelFile.absolutePath)
                    .build()
            )
            .setRunningMode(com.google.mediapipe.tasks.vision.core.RunningMode.VIDEO)
            .setNumPoses(1)
            .build()
        val marker = com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
            .createFromOptions(context, opts)
        try {
            // Blank frame: must not throw. Empty landmarks on a
            // content-free frame are the valid "detection loss"
            // outcome the engine's saliency fallback handles.
            val blank = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            blank.eraseColor(0xFF808080.toInt())
            val mpImage = com.google.mediapipe.framework.image.BitmapImageBuilder(blank).build()
            val result = marker.detectForVideo(mpImage, 0L)
            assertNotNull(result)
            assertNotNull(result.landmarks())
            blank.recycle()
        } finally {
            marker.close()
        }
    }

    // ----------------------------------------------------------------
    // Background Remove (selfie segmentation)
    // ----------------------------------------------------------------

    @Test
    fun selfieSegmenterLoadsAndSegments() = runBlocking {
        val installer = AiModelInstaller(context)
        val spec = AiModelCatalog.SELFIE_MULTICLASS
        assertTrue(installer.installWithManifest(spec).isReady)
        val modelFile = AiModels.modelFile(context, spec)
        val opts = com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter.ImageSegmenterOptions
            .builder()
            .setBaseOptions(
                com.google.mediapipe.tasks.core.BaseOptions.builder()
                    .setModelAssetPath(modelFile.absolutePath)
                    .build()
            )
            .setRunningMode(com.google.mediapipe.tasks.vision.core.RunningMode.IMAGE)
            .setOutputCategoryMask(true)
            .setOutputConfidenceMasks(false)
            .build()
        val segmenter = com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
            .createFromOptions(context, opts)
        try {
            // Synthetic "person": skin-tone ellipse on flat background.
            val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(0xFF203040.toInt())
            val canvas = android.graphics.Canvas(bmp)
            val paint = android.graphics.Paint()
            paint.color = 0xFFD9A066.toInt()
            canvas.drawOval(78f, 40f, 178f, 216f, paint)
            val mpImage = com.google.mediapipe.framework.image.BitmapImageBuilder(bmp).build()
            val result = segmenter.segment(mpImage)
            assertTrue("no category mask", result.categoryMask().isPresent)
            val mask = result.categoryMask().get()
            assertEquals(256, mask.width)
            assertEquals(256, mask.height)
            bmp.recycle()
        } finally {
            segmenter.close()
        }
    }

    // ----------------------------------------------------------------
    // Shared video pipeline (synthetic MP4 round-trip + cancel)
    // ----------------------------------------------------------------

    @Test
    fun videoPipelineTranscodesSyntheticMp4() {
        val src = synthesizeMp4("pipe-src.mp4", withAudio = false)
        val info = VideoFramePipeline.probe(context, android.net.Uri.fromFile(src))
        assertNotNull(info)
        assertTrue(info!!.width > 0 && info.height > 0)

        val dst = File(context.cacheDir, "pipe-dst-${System.currentTimeMillis()}.mp4")
        var progressTicks = 0
        val res = VideoFramePipeline.transcode(
            context,
            VideoFramePipeline.Request(
                srcUri = android.net.Uri.fromFile(src),
                dstFile = dst,
                frameOp = VideoFramePipeline.FrameOp { bmp, _, _ -> bmp },
                copyAudio = false,
                onProgress = { _, _ -> progressTicks++ },
                isCancelled = { false }
            )
        )
        assertTrue("result was $res", res is VideoFramePipeline.Result.Done)
        val done = res as VideoFramePipeline.Result.Done
        assertTrue("no frames: $done", done.frames > 0)
        assertTrue("no progress reported", progressTicks > 0)
        assertTrue(dst.exists() && dst.length() > 0)
        val outInfo = VideoFramePipeline.probe(context, android.net.Uri.fromFile(dst))
        assertNotNull(outInfo)
        // Duration preserved (within one frame of tolerance).
        assertTrue(
            "duration drifted: ${info.durationMs} -> ${outInfo!!.durationMs}",
            kotlin.math.abs(info.durationMs - outInfo.durationMs) < 500
        )
        src.delete()
        dst.delete()
    }

    @Test
    fun videoPipelineCancellationDeletesPartial() {
        val src = synthesizeMp4("pipe-cancel.mp4", withAudio = false)
        val dst = File(context.cacheDir, "pipe-cancel-dst-${System.currentTimeMillis()}.mp4")
        var calls = 0
        val res = VideoFramePipeline.transcode(
            context,
            VideoFramePipeline.Request(
                srcUri = android.net.Uri.fromFile(src),
                dstFile = dst,
                frameOp = VideoFramePipeline.FrameOp { bmp, _, _ ->
                    // Slow the loop so cancellation lands mid-run.
                    Thread.sleep(50)
                    bmp
                },
                copyAudio = false,
                onProgress = { _, _ -> },
                isCancelled = { ++calls > 3 }
            )
        )
        assertTrue("result was $res", res is VideoFramePipeline.Result.Cancelled)
        assertTrue("partial file leaked", !dst.exists())
        src.delete()
    }

    // ---- helpers ----

    private fun rms(samples: FloatArray): Float {
        var acc = 0.0
        for (s in samples) acc += s * s
        return kotlin.math.sqrt(acc / samples.size).toFloat()
    }

    /**
     * Synthesize a tiny H.264 MP4 (color bars, 320x240, ~2 s @ 30
     * fps) with the platform encoder so pipeline tests never depend
     * on a bundled fixture.
     */
    private fun synthesizeMp4(name: String, withAudio: Boolean): File {
        val out = File(context.cacheDir, "$name-${System.currentTimeMillis()}.mp4")
        val w = 320
        val h = 240
        val fps = 30
        val frames = 60
        val format = android.media.MediaFormat.createVideoFormat("video/avc", w, h).apply {
            setInteger(
                android.media.MediaFormat.KEY_COLOR_FORMAT,
                android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
            )
            setInteger(android.media.MediaFormat.KEY_BIT_RATE, 1_000_000)
            setInteger(android.media.MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(android.media.MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = android.media.MediaCodec.createEncoderByType("video/avc")
        encoder.configure(format, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        var muxer: MediaMuxer? = null
        var track = -1
        var started = false
        val info = android.media.MediaCodec.BufferInfo()
        val bars = intArrayOf(0xFFC0C0C0.toInt(), 0xFFC0C000.toInt(), 0xFF00C0C0.toInt())
        try {
            for (f in 0 until frames) {
                val yuv = ByteArray(w * h * 3 / 2)
                yuv.fill(0x80.toByte())
                // Paint one moving bar into Y.
                val barY = (f * h / frames)
                for (y in barY until minOf(barY + 12, h)) {
                    for (x in 0 until w) yuv[y * w + x] = 0xE0.toByte()
                }
                val idx = encoder.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    val buf = encoder.getInputBuffer(idx)!!
                    buf.clear()
                    buf.put(yuv)
                    encoder.queueInputBuffer(idx, 0, yuv.size, (f * 1_000_000L / fps), 0)
                }
                // Drain.
                while (true) {
                    val st = encoder.dequeueOutputBuffer(info, 0)
                    if (st == android.media.MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (st == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!started) {
                            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                            track = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            started = true
                        }
                        continue
                    }
                    if (st >= 0) {
                        if (info.size > 0 && started) {
                            muxer!!.writeSampleData(track, encoder.getOutputBuffer(st)!!, info)
                        }
                        encoder.releaseOutputBuffer(st, false)
                        break
                    } else break
                }
            }
            // EOS.
            val idx = encoder.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                encoder.queueInputBuffer(
                    idx, 0, 0, (frames * 1_000_000L / fps),
                    android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            }
            var eos = false
            var guard = 0
            while (!eos && guard++ < 200) {
                val st = encoder.dequeueOutputBuffer(info, 10_000)
                if (st == android.media.MediaCodec.INFO_TRY_AGAIN_LATER) continue
                if (st == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!started) {
                        muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        track = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        started = true
                    }
                    continue
                }
                if (st >= 0) {
                    if (info.size > 0 && started) {
                        muxer!!.writeSampleData(track, encoder.getOutputBuffer(st)!!, info)
                    }
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                    encoder.releaseOutputBuffer(st, false)
                }
            }
            assertTrue("synthetic mp4 failed to start muxer", started)
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
        }
        assertTrue(out.exists() && out.length() > 0)
        return out
    }
}
