package com.aicreatorstudio.app.ai.media

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-21 — guards the native-crash fix.
 *
 * ## The crash
 *
 * Captured from a real device (Xiaomi 2107113SG, Android 14) via the
 * device's crash buffer:
 *
 * ```
 * Fatal signal 6 (SIGABRT) in tid 14694 (DefaultDispatch)
 * Abort message: 'JNI DETECTED ERROR IN APPLICATION: non-zero capacity for
 *   nullptr pointer: 1
 *     in call to NewDirectByteBuffer
 *     from android.media.ImageReader$SurfaceImage$SurfacePlane[]
 *            ImageReader$SurfaceImage.nativeCreatePlanes(int, int, long)'
 * ```
 *
 * with the application frames (from the debug build, where the names
 * are not obfuscated):
 *
 * ```
 * com.aicreatorstudio.app.ai.engines.MediaPipeMattingEngine$process$2.invokeSuspend
 *   com.aicreatorstudio.app.ai.media.SequentialFrameSampler.sample
 *     com.aicreatorstudio.app.ai.media.VideoFramePipeline.imageToBitmap
 *       com.aicreatorstudio.app.ai.media.VideoFramePipeline.yuv420ToBitmap
 * ```
 *
 * A native `abort()` cannot be caught, so the whole process died —
 * Background Remove simply made the app vanish.
 *
 * ## The cause
 *
 * AOSP's `MediaMetadataRetriever` reports
 * `METADATA_KEY_VIDEO_WIDTH/HEIGHT` as the **display** size: it swaps
 * the axes for a 90/270 rotation before returning. A MediaCodec
 * decoder, meanwhile, writes the **coded** (cropped) size from the
 * track format. The `ImageReader` was sized from the retriever, so on
 * a portrait clip the reader expected a frame the decoder never
 * produced — and `ImageReader.nativeCreatePlanes` then handed ART a
 * plane whose address was null and whose capacity was 1.
 *
 * ## The two defences
 *
 * 1. Every `ImageReader` is sized from the **track format**, which is
 *    the only authoritative statement of "what will the decoder
 *    write".
 * 2. `image.getPlanes()` is only ever called on an image whose
 *    dimensions have already been checked against the reader's size.
 *    `getWidth()`/`getHeight()` are plain accessors and never touch
 *    the native plane machinery, so a mismatch is rejected with a
 *    clear error instead of aborting the process.
 *
 * ## 2026-10-24 — the export path left the reader behind
 *
 * On the measured device no reader usage produces a readable frame
 * (plane access aborts, `wrapHardwareBuffer` refuses the buffer), so the
 * export transcode, both export samplers **and** [SequentialFrameSampler]
 * now configure their decoders in byte-buffer mode and convert through
 * `codecImageToBitmap`. Nothing in the app creates an `ImageReader` any
 * more, which is why the export sources and the sampler are guarded for
 * the opposite property below. The dimension guard is kept because
 * `imageToBitmap` is still public and is the only way to convert a
 * *surface* image if a future feature ever needs one again.
 */
class DecoderSizeGuardTest {

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("settings.gradle.kts not found above ${File(".").absolutePath}")
    }

    private fun source(relative: String): String {
        val f = File(repoRoot(), relative)
        assertTrue("missing file: $f", f.isFile)
        return f.readText()
    }

    @Test
    fun `no image reader is sized from the retriever's display size`() {
        val pipeline = source(
            "app/src/main/java/com/aicreatorstudio/app/ai/media/VideoFramePipeline.kt"
        )
        assertTrue(
            "the pipeline must size the reader from the track format",
            pipeline.contains("decoderOutputSize(srcFormat)")
        )
        assertTrue(
            "the reader must NOT be sized from the retriever's display size",
            !pipeline.contains(
                "info.width, info.height,\n                android.graphics.ImageFormat.YUV_420_888"
            )
        )
    }

    @Test
    fun `the sampler no longer owns a reader`() {
        val sampler = source(
            "app/src/main/java/com/aicreatorstudio/app/ai/media/SequentialFrameSampler.kt"
        )
        assertTrue(
            "the sampler must convert the decoder's byte-buffer image",
            sampler.contains("VideoFramePipeline.codecImageToBitmap(")
        )
        assertTrue(
            "the sampler must not create an ImageReader: no reader usage makes a decoder " +
                "frame readable on the measured device, which is why every sample came " +
                "from the MediaMetadataRetriever fallback",
            !sampler.contains("ImageReader.newInstance(")
        )
    }

    @Test
    fun `the export samplers no longer own a reader at all`() {
        for (file in listOf(
            "app/src/main/java/com/aicreatorstudio/app/export/AiExportHooks.kt",
            "app/src/main/java/com/aicreatorstudio/app/export/OverlayCompositeRenderer.kt"
        )) {
            val src = source(file)
            assertTrue(
                "$file must convert the decoder's byte-buffer image through " +
                    "codecImageToBitmap",
                src.contains("VideoFramePipeline.codecImageToBitmap(")
            )
            assertTrue(
                "$file must not create an ImageReader: on the measured device no reader " +
                    "usage makes a decoder frame readable, so sizing one was only ever a " +
                    "workaround for a surface that cannot be read back",
                !src.contains("ImageReader.newInstance(")
            )
        }
    }

    @Test
    fun `the plane access is guarded by a dimension check`() {
        val pipeline = source(
            "app/src/main/java/com/aicreatorstudio/app/ai/media/VideoFramePipeline.kt"
        )
        assertTrue(
            "the guarded conversion must exist",
            pipeline.contains("fun imageToBitmap(\n        image: Image,\n        expectedWidth: Int,")
                .orElse(pipeline.contains("expectedWidth: Int,"))
        )
        assertTrue(
            "the guard must compare the image size with the reader size",
            pipeline.contains("image.width != expectedWidth || image.height != expectedHeight")
        )
        assertTrue(
            "the surface path must not be used by the export transcode any more; it " +
                "decodes byte buffers through codecImageToBitmap instead",
            !pipeline.contains("imageToBitmap(image, readerW, readerH)")
        )
    }

    @Test
    fun `the sampler no longer uses the surface conversion`() {
        val sampler = source(
            "app/src/main/java/com/aicreatorstudio/app/ai/media/SequentialFrameSampler.kt"
        )
        assertTrue(
            "the surface policy must not be reachable from the sampler any more; its " +
                "planes are the ones that abort the process",
            !sampler.contains("VideoFramePipeline.imageToBitmap(")
        )
    }

    @Test
    fun `the probe reports the coded size so the display size is computed once`() {
        val pipeline = source(
            "app/src/main/java/com/aicreatorstudio/app/ai/media/VideoFramePipeline.kt"
        )
        assertTrue(
            "the probe must take the coded size from the track format",
            pipeline.contains("val size = decoderOutputSize(f)")
        )
    }
}

/** Small readability helper so the assertions above stay short. */
private fun Boolean.orElse(other: Boolean): Boolean = this || other
