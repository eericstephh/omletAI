package com.aicreatorstudio.app.ai.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-24 — the frame-readback guard.
 *
 * On a Xiaomi 2107113SG (Android 14, HEVC through `c2.qti.hevc.decoder`)
 * **every** frame delivered through an `ImageReader` surface is unreadable:
 *
 * - default reader usage: `Bitmap.wrapHardwareBuffer` refuses the buffer
 *   ("usage flags must contain USAGE_GPU_SAMPLED_IMAGE") and
 *   `Image.getPlanes()` aborts the process in
 *   `ImageReader$SurfaceImage.nativeCreatePlanes`
 *   ("non-zero capacity for nullptr pointer: 1");
 * - explicit `USAGE_GPU_SAMPLED_IMAGE or USAGE_CPU_READ_OFTEN` (allocated
 *   usage 0x103, logged): `Image.getHardwareBuffer()` returns null and the
 *   plane path **still** aborts with the same JNI error.
 *
 * A native abort cannot be caught, so the export could not even report a
 * failure - the app died. The fix is to stop using a surface for the
 * decoders whose frames must be read on the CPU: with `configure(format,
 * null, null, 0)` MediaCodec hands out its own ByteBuffers and
 * `getOutputImage()` gives planes that are ordinary direct buffers.
 *
 * This is a platform behaviour that no JVM unit test can execute, so the
 * rule is pinned at the source level: every decoder in the export path
 * must be configured **without** a surface, and the conversion must go
 * through `codecImageToBitmap` (which never calls the surface-only
 * `imageToBitmap` policy).
 */
class ByteBufferDecodeTest {

    private fun repoRoot(): File {
        var dir: File = File(".").absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: throw IllegalStateException("settings.gradle.kts not found")
        }
    }

    private fun source(relative: String): String = File(repoRoot(), relative).readText()

    private val pipeline by lazy {
        source("app/src/main/java/com/aicreatorstudio/app/ai/media/VideoFramePipeline.kt")
    }
    private val exportHooks by lazy {
        source("app/src/main/java/com/aicreatorstudio/app/export/AiExportHooks.kt")
    }
    private val overlayRenderer by lazy {
        source("app/src/main/java/com/aicreatorstudio/app/export/OverlayCompositeRenderer.kt")
    }
    private val sampler by lazy {
        source("app/src/main/java/com/aicreatorstudio/app/ai/media/SequentialFrameSampler.kt")
    }

    @Test
    fun `the transcode decoder runs in byte-buffer mode`() {
        assertTrue(
            "VideoFramePipeline.transcode must configure its decoder without a Surface: " +
                "`decoder.configure(srcFormat, null, null, 0)`. A surface decoder's frames " +
                "abort the process in ImageReader\$SurfaceImage.nativeCreatePlanes on the " +
                "measured device.",
            pipeline.contains("decoder.configure(srcFormat, null, null, 0)")
        )
        assertFalse(
            "The transcode decoder must not be attached to an ImageReader surface any more",
            pipeline.contains("decoder.configure(srcFormat, reader")
        )
    }

    @Test
    fun `every decoder that the AI and export paths read decodes without a Surface`() {
        for ((name, src) in listOf(
            "AiExportHooks.kt" to exportHooks,
            "OverlayCompositeRenderer.kt" to overlayRenderer
        )) {
            assertTrue(
                "$name must configure its decoder without a Surface " +
                    "(`it.configure(trackFormat, null, null, 0)`)",
                src.contains("it.configure(trackFormat, null, null, 0)")
            )
            assertFalse(
                "$name must not create an ImageReader for decoder output: that is the path " +
                    "that aborts the process on the measured device",
                src.contains("ImageReader.newInstance(")
            )
        }
        assertTrue(
            "SequentialFrameSampler (matting / auto-frame / OCR) must decode without a " +
                "Surface too, otherwise every sample pays the retriever fallback",
            sampler.contains("dec.configure(srcFormat, null, null, 0)")
        )
        assertFalse(
            "SequentialFrameSampler must not create an ImageReader",
            sampler.contains("ImageReader.newInstance(")
        )
    }

    @Test
    fun `it asks for flexible YUV, the only format getOutputImage supports`() {
        assertTrue(
            "The decoder must request COLOR_FormatYUV420Flexible before configure(), " +
                "otherwise getOutputImage() throws on a planar default",
            pipeline.contains("COLOR_FormatYUV420Flexible")
        )
        assertTrue(
            "codecImageToBitmap must be reachable from the export samplers",
            exportHooks.contains("VideoFramePipeline.codecImageToBitmap(") &&
                overlayRenderer.contains("VideoFramePipeline.codecImageToBitmap(")
        )
    }

    @Test
    fun `the byte-buffer conversion clamps each plane row to its limit`() {
        assertTrue(
            "readPlaneRow must clamp to the plane's limit: a byte-buffer image's last chroma " +
                "row is shorter than rowStride and a full-row read threw " +
                "BufferUnderflowException (measured on the first exported frame)",
            pipeline.contains("private fun readPlaneRow(") &&
                pipeline.contains("if (offset < 0 || offset >= src.limit())")
        )
        assertTrue(
            "yuv420ToBitmap must read rows through readPlaneRow",
            pipeline.contains("readPlaneRow(yBuf, y * yRowStride, yRow)")
        )
    }

    @Test
    fun `no AI or export path converts surface images any more`() {
        for ((name, src) in listOf(
            "AiExportHooks.kt" to exportHooks,
            "OverlayCompositeRenderer.kt" to overlayRenderer,
            "SequentialFrameSampler.kt" to sampler
        )) {
            assertFalse(
                "$name still calls imageToBitmap(): its surface policy is the one that " +
                    "aborts on this device class",
                src.contains("VideoFramePipeline.imageToBitmap(")
            )
        }
    }
}
