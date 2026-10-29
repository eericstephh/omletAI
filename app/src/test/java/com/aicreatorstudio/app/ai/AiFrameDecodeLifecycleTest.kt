package com.aicreatorstudio.app.ai

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-20 — guards the two crash fixes behind «ابزارهای هوشمند».
 *
 * ## The defects this pins
 *
 * **1. Rendering the EOS buffer.** Both hot decode loops called
 * `releaseOutputBuffer(index, true)` for *every* dequeued output,
 * including the one flagged `BUFFER_FLAG_END_OF_STREAM`. An EOS output
 * carries no picture, so the ImageReader handed back an `Image` with no
 * backing gralloc buffer. `Image.getPlanes()` builds its planes lazily,
 * so the failure only surfaced on the first `planes[0]` access inside
 * `VideoFramePipeline.imageToBitmap` — `nativeCreatePlanes` was called
 * with a null native context and CheckJNI aborted the process:
 *
 * ```
 * Abort message: 'JNI DETECTED ERROR IN APPLICATION: non-zero capacity
 *   for nullptr pointer: 1 in call to NewDirectByteBuffer from
 *   android.media.ImageReader$SurfaceImage.nativeCreatePlanes(int,int,long)'
 *   #18 VideoFramePipeline.yuv420ToBitmap
 *   #26 SequentialFrameSampler.sample
 *   #31 TesseractOcrEngine.analyzeVideo
 * ```
 *
 * That is a native abort, not an exception, so no `try/catch` anywhere
 * in Kotlin could have caught it. The two decoders that already worked
 * (`AiExportHooks`, `OverlayCompositeRenderer`) release with `!isEos`;
 * these two did not.
 *
 * **2. Holding the Image across the AI work.** `onFrame` is a
 * `suspend` callback that runs matting/OCR inference for up to seconds,
 * and `ImageReader` was created with only 3 slots. Keeping the `Image`
 * checked out across that callback starved the decoder, and the driver
 * eventually reclaimed the buffer underneath us.
 *
 * **3. Blocking acquire.** `ImageReader.acquireNextImage()` **blocks**
 * when the queue is empty — it does not return null. Calling it without
 * knowing a frame is pending hung the worker at end-of-stream.
 *
 * **4. Context-less GL probe.** `DeviceCapabilityDetector.queryGlesMajor`
 * called `GLES20.glGetString` on the upscaler's worker thread, where no
 * EGL context exists, and the driver dereferenced a null dispatch table:
 *
 * ```
 * signal 11 (SIGSEGV) in libGLESv1_CM.so (glGetString+52)
 *   #07 ai.upscale.DeviceCapabilityDetector.queryGlesMajor
 *   #15 ai.upscale.EsrganUpscalePipeline.run
 * ```
 *
 * MediaCodec and Compose cannot run under this project's JVM stack, so
 * the contract is asserted structurally against the real sources — the
 * same shape as [AiFrameAccuracyWiringTest].
 *
 * ## 2026-10-24 — every decoder left the surface behind
 *
 * `AiExportHooks`, `OverlayCompositeRenderer`, the export transcode and
 * finally [SequentialFrameSampler] all run their decoders in byte-buffer
 * mode now and convert with `codecImageToBitmap`. That is because on the
 * measured phone (Xiaomi 2107113SG, Android 14, HEVC) *no* usage flag
 * makes a reader frame readable: `getPlanes()` aborts the process and
 * `wrapHardwareBuffer` refuses the buffer. There is no reader, no
 * `pendingImages` counter and no rendered output left anywhere, so the
 * guards below assert the byte-buffer invariants instead: an EOS output is
 * never converted, every output is released exactly once, and the
 * conversion happens before the release.
 */
class AiFrameDecodeLifecycleTest {

    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "settings.gradle.kts not found above ${File(".").absolutePath}"
        )
    }

    private fun source(relative: String): String {
        val f = File(repoRoot(), relative)
        assertTrue("missing file: $f", f.isFile)
        return f.readText()
    }

    private fun sampler(): String = source(
        "app/src/main/java/com/aicreatorstudio/app/ai/media/SequentialFrameSampler.kt"
    )

    private fun pipeline(): String = source(
        "app/src/main/java/com/aicreatorstudio/app/ai/media/VideoFramePipeline.kt"
    )

    @Test
    fun `the sampler never renders or converts the end of stream buffer`() {
        val src = sampler()
        assertTrue(
            "the sampler must release the EOS output without rendering it",
            src.contains("runCatching { dec.releaseOutputBuffer(st, false) }")
        )
        assertTrue(
            "the sampler must never render an output into a surface",
            !src.contains("releaseOutputBuffer(st, true)")
        )
        assertTrue(
            "the sampler must convert byte-buffer images through codecImageToBitmap",
            src.contains("VideoFramePipeline.codecImageToBitmap(img)")
        )
    }

    /**
     * 2026-10-24 — the export pipeline no longer decodes into a surface at
     * all (see [com.aicreatorstudio.app.ai.media.ByteBufferDecodeTest]).
     * The two invariants that still matter there are: an EOS output is
     * never rendered, and every output buffer is released exactly once,
     * *after* its Image has been converted.
     */
    @Test
    fun `the pipeline never renders an output buffer`() {
        val src = pipeline()
        assertTrue(
            "the pipeline must detect the end-of-stream output",
            src.contains("BUFFER_FLAG_END_OF_STREAM")
        )
        assertTrue(
            "the pipeline must release every output without rendering it; a rendered " +
                "EOS image has no backing buffer and aborts the process",
            src.contains("decoder.releaseOutputBuffer(st, false)")
        )
        assertTrue(
            "no decode output may be rendered into a surface any more",
            !src.contains("releaseOutputBuffer(st, true)")
        )
        assertTrue(
            "the byte-buffer image must be converted through codecImageToBitmap",
            src.contains("codecImageToBitmap(img)")
        )
    }

    @Test
    fun `the sampler decodes byte buffers instead of acquiring reader images`() {
        val src = sampler()
        assertTrue(
            "the sampler must configure its decoder without a Surface",
            src.contains("dec.configure(srcFormat, null, null, 0)")
        )
        assertTrue(
            "the sampler must not acquire from an ImageReader any more " +
                "(acquireNextImage blocks forever on an empty queue)",
            !src.contains("acquireNextImage")
        )
        assertTrue(
            "the sampler must not create an ImageReader: no reader usage makes a " +
                "decoder frame readable on the measured device",
            !src.contains("ImageReader.newInstance(")
        )
    }

    @Test
    fun `the decode image is closed before the frame reaches the AI callback`() {
        val src = sampler()
        val closeAt = src.indexOf("runCatching { img.close() }")
        val deliverAt = src.indexOf("onFrame(frame)")
        assertTrue("sampler must close the decoded Image", closeAt >= 0)
        assertTrue("sampler must still deliver frames", deliverAt >= 0)
        assertTrue(
            "the Image must be closed BEFORE the suspending onFrame runs, " +
                "otherwise the codec buffer is held across seconds of inference",
            closeAt < deliverAt
        )
    }

    @Test
    fun `the pipeline closes the image before running the frame op`() {
        val src = pipeline()
        // Byte-buffer images are closed through the `img` alias; there is
        // no surface Image any more.
        val closeAt = src.indexOf("img.close()")
        val processAt = src.indexOf("req.frameOp.process(")
        assertTrue("pipeline must close the decoded Image", closeAt >= 0)
        assertTrue("pipeline must still run the frame op", processAt >= 0)
        assertTrue(
            "the Image must be closed BEFORE frameOp (ESRGAN/matting take " +
                "hundreds of ms per frame)",
            closeAt < processAt
        )
    }

    @Test
    fun `the upscaler probes GLES version without touching a GL context`() {
        val src = source(
            "app/src/main/java/com/aicreatorstudio/app/ai/upscale/DeviceCapabilityDetector.kt"
        )
        assertTrue(
            "no OpenGL import may come back — glGetString without a current " +
                "EGL context is a SIGSEGV, and runCatching cannot catch it",
            !src.contains("import android.opengl")
        )
        assertTrue(
            "the version must come from the platform feature table",
            src.contains("reqGlEsVersion")
        )
    }

    @Test
    fun `the capability probe still degrades to the safe profile`() {
        val src = source(
            "app/src/main/java/com/aicreatorstudio/app/ai/upscale/DeviceCapabilityDetector.kt"
        )
        assertTrue(
            "an unreadable probe must fall back to 1, which keeps FAST",
            src.contains(".getOrDefault(1)")
        )
        assertTrue(
            "compute support is still the 3+ threshold",
            src.contains("val compute = gles >= 3")
        )
    }
}
