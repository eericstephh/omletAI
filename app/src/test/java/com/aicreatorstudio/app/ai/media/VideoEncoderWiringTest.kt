package com.aicreatorstudio.app.ai.media

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-20 — guards the encoder selection in the AI pipeline.
 *
 * The pipeline used to probe every AVC encoder for a YUV420 color
 * format and then instantiate the platform's DEFAULT encoder, hoping
 * the two agreed. On a device whose default AVC encoder is the
 * software `c2.android.avc.encoder` — which accepts only
 * `COLOR_FormatYUV420Flexible` — the probe answered "planar" and
 * `MediaCodec.configure` threw, killing the AI Upscale / Motion
 * Tracking pipeline on its first frame.
 *
 * These are structural assertions because the pipeline itself needs a
 * `Context` and real codecs; the decision LOGIC is pinned by
 * [VideoEncoderCapabilityTest].
 */
class VideoEncoderWiringTest {

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
    fun `the pipeline selects the encoder it probed instead of the platform default`() {
        val src = source(
            "app/src/main/java/com/aicreatorstudio/app/ai/media/VideoFramePipeline.kt"
        )
        assertTrue(
            "the pipeline must choose the encoder from the capability probe",
            src.contains("VideoEncoderCapability.select(")
        )
        assertTrue(
            "the encoder must be created BY NAME — createEncoderByType hands back " +
                "a different one with different capabilities",
            src.contains("MediaCodec.createByCodecName(encoderSelection.codecName)")
        )
        assertTrue(
            "createEncoderByType must be gone from the pipeline",
            !src.contains("MediaCodec.createEncoderByType(")
        )
    }

    @Test
    fun `the old colour-format probe is gone`() {
        val src = source(
            "app/src/main/java/com/aicreatorstudio/app/ai/media/VideoFramePipeline.kt"
        )
        assertTrue(
            "the probe that answered 'planar' for a flexible-only encoder must be removed",
            !src.contains("selectEncoderColorFormat")
        )
    }

    @Test
    fun `the upscale pipeline asks the device what it can encode before rendering`() {
        val src = source(
            "app/src/main/java/com/aicreatorstudio/app/ai/upscale/EsrganUpscalePipeline.kt"
        )
        assertTrue(
            "the upscale must clamp the output size to what the device can encode " +
                "BEFORE the tiler renders every frame at the wrong size",
            src.contains("VideoEncoderCapability.select(")
        )
    }

    @Test
    fun `the gpu delegate is validated before it is trusted`() {
        val src = source(
            "app/src/main/java/com/aicreatorstudio/app/ai/upscale/EsrganTiler.kt"
        )
        assertTrue(
            "the GPU delegate must be exercised once at load time — its shaders are " +
                "compiled lazily, so a driver that advertises GLES 3.1 but has a broken " +
                "compute path only fails on the first real inference",
            src.contains("warmUp(built)")
        )
        assertTrue(
            "a failed warm-up must fall back to the CPU",
            src.contains("falling back to CPU")
        )
    }
}
