package com.aicreatorstudio.app.ai.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-20 — guards the encoder selection.
 *
 * The pipeline used to probe EVERY AVC encoder for a YUV420 color
 * format and then instantiate the platform's DEFAULT encoder, hoping
 * the two agreed. On a device whose default AVC encoder is the
 * software `c2.android.avc.encoder` — which accepts only
 * `COLOR_FormatYUV420Flexible` — the probe answered "planar" and
 * `MediaCodec.configure` threw, killing the AI Upscale / Motion
 * Tracking pipeline on its first frame. The requested resolution was
 * never checked at all, so a 4K request also threw on encoders
 * capped at 1080p.
 *
 * Which devices failed depended on the vendor's codec ordering, so
 * the app worked on one phone and died on another.
 */
class VideoEncoderCapabilityTest {

    private fun option(
        name: String,
        formats: Set<Int>,
        maxW: Int = 4096,
        maxH: Int = 4096,
        minW: Int = 64,
        minH: Int = 64,
        supportedWidthsFor: (Int) -> IntRange? = { h -> minW..maxW },
        sizeSupported: ((Int, Int) -> Boolean)? = null
    ) = VideoEncoderCapability.EncoderOption(
        codecName = name,
        colorFormats = formats,
        widthRange = minW..maxW,
        heightRange = minH..maxH,
        widthsForHeight = supportedWidthsFor,
        isSizeSupported = sizeSupported
    )

    // -----------------------------------------------------------------
    // The bug: a flexible-only encoder was probed as "planar".
    // -----------------------------------------------------------------

    @Test
    fun `an encoder that only accepts flexible is never selected`() {
        // The software encoder: flexible only, no planar, no
        // semi-planar. It cannot take YUV bytes through a ByteBuffer.
        val software = option(
            name = "c2.android.avc.encoder",
            formats = setOf(VideoEncoderCapability.COLOR_FLEXIBLE)
        )
        val selection = VideoEncoderCapability.select(
            options = listOf(software),
            requestedWidth = 1920,
            requestedHeight = 1080
        )
        assertNull(
            "a flexible-only encoder cannot be driven with a ByteBuffer — " +
                "selecting it is what made configure() throw",
            selection
        )
    }

    @Test
    fun `the color format belongs to the encoder that was selected`() {
        // A device with a software (flexible-only) encoder AND a
        // hardware encoder that takes planar. The selection must be
        // the hardware one, and its format must be planar.
        val software = option(
            name = "c2.android.avc.encoder",
            formats = setOf(VideoEncoderCapability.COLOR_FLEXIBLE)
        )
        val hardware = option(
            name = "OMX.qcom.video.encoder.avc",
            formats = setOf(
                VideoEncoderCapability.COLOR_PLANAR,
                VideoEncoderCapability.COLOR_SEMI_PLANAR
            )
        )
        val selection = VideoEncoderCapability.select(
            options = listOf(software, hardware),
            requestedWidth = 1920,
            requestedHeight = 1080
        )
        assertNotNull(selection)
        assertEquals("OMX.qcom.video.encoder.avc", selection?.codecName)
        assertEquals(
            "the format must be one the SELECTED encoder accepts",
            VideoEncoderCapability.COLOR_PLANAR,
            selection?.colorFormat
        )
    }

    @Test
    fun `semi planar is used when the encoder has no planar format`() {
        val semiOnly = option(
            name = "OMX.exynos.avc.encoder",
            formats = setOf(VideoEncoderCapability.COLOR_SEMI_PLANAR)
        )
        val selection = VideoEncoderCapability.select(
            options = listOf(semiOnly),
            requestedWidth = 1280,
            requestedHeight = 720
        )
        assertEquals(
            VideoEncoderCapability.COLOR_SEMI_PLANAR,
            selection?.colorFormat
        )
    }

    // -----------------------------------------------------------------
    // The bug: the resolution was never checked.
    // -----------------------------------------------------------------

    @Test
    fun `a 4k request on a 1080p-only encoder is clamped, not failed`() {
        val capped = option(
            name = "OMX.qcom.video.encoder.avc",
            formats = setOf(VideoEncoderCapability.COLOR_PLANAR),
            maxW = 1920,
            maxH = 1080
        )
        val selection = VideoEncoderCapability.select(
            options = listOf(capped),
            requestedWidth = 3840,
            requestedHeight = 2160
        )
        assertNotNull(
            "a device that cannot encode 4K must degrade, not fail the run",
            selection
        )
        assertEquals(1920, selection?.width)
        assertEquals(1080, selection?.height)
    }

    @Test
    fun `an exact size is honoured when the encoder supports it`() {
        val full = option(
            name = "OMX.qcom.video.encoder.avc",
            formats = setOf(VideoEncoderCapability.COLOR_PLANAR),
            maxW = 4096,
            maxH = 4096
        )
        val selection = VideoEncoderCapability.select(
            options = listOf(full),
            requestedWidth = 3840,
            requestedHeight = 2160
        )
        assertEquals(3840, selection?.width)
        assertEquals(2160, selection?.height)
    }

    @Test
    fun `the clamped size keeps the aspect ratio`() {
        // 1080p source asked to become 4K on a 1080p-only encoder: the
        // result must still be 16:9, not 1920x1080 letterboxed wrongly.
        val capped = option(
            name = "enc",
            formats = setOf(VideoEncoderCapability.COLOR_PLANAR),
            maxW = 1920,
            maxH = 1080
        )
        val selection = VideoEncoderCapability.select(
            options = listOf(capped),
            requestedWidth = 3840,
            requestedHeight = 2160
        )
        val aspect = selection!!.width.toDouble() / selection.height
        assertTrue(
            "the clamped size must keep the aspect ratio (was $aspect)",
            kotlin.math.abs(aspect - 3840.0 / 2160.0) < 0.02
        )
    }

    @Test
    fun `a size the framework rejects is skipped even when the range allows it`() {
        // The range says 64..4096, but the framework's own answer is
        // authoritative: this encoder only really does 1080p.
        val lying = option(
            name = "enc",
            formats = setOf(VideoEncoderCapability.COLOR_PLANAR),
            maxW = 4096,
            maxH = 4096,
            sizeSupported = { w, h -> w <= 1920 && h <= 1080 }
        )
        val selection = VideoEncoderCapability.select(
            options = listOf(lying),
            requestedWidth = 3840,
            requestedHeight = 2160
        )
        assertNotNull(selection)
        assertTrue(
            "the framework's answer must win over the advertised range " +
                "(was ${selection?.width}x${selection?.height})",
            selection!!.width <= 1920 && selection.height <= 1080
        )
    }

    // -----------------------------------------------------------------
    // The shape of the answer.
    // -----------------------------------------------------------------

    @Test
    fun `every dimension is even because h264 requires it`() {
        val odd = option(
            name = "enc",
            formats = setOf(VideoEncoderCapability.COLOR_PLANAR),
            maxW = 4096,
            maxH = 4096
        )
        val selection = VideoEncoderCapability.select(
            options = listOf(odd),
            requestedWidth = 1921,
            requestedHeight = 1081
        )
        assertNotNull(selection)
        assertEquals(0, selection!!.width % 2)
        assertEquals(0, selection.height % 2)
    }

    @Test
    fun `no encoder means no selection`() {
        assertNull(
            VideoEncoderCapability.select(
                options = emptyList(),
                requestedWidth = 1920,
                requestedHeight = 1080
            )
        )
    }

    @Test
    fun `a degenerate request is rejected`() {
        val enc = option(
            name = "enc",
            formats = setOf(VideoEncoderCapability.COLOR_PLANAR)
        )
        assertNull(
            VideoEncoderCapability.select(
                options = listOf(enc),
                requestedWidth = 0,
                requestedHeight = 1080
            )
        )
    }

    @Test
    fun `the largest usable size is preferred when no exact match exists`() {
        // Two encoders: a 1080p one and a 720p one. The 1080p one
        // must win — degrading to the SMALLER picture would be a
        // silent quality loss.
        val small = option(
            name = "enc720",
            formats = setOf(VideoEncoderCapability.COLOR_PLANAR),
            maxW = 1280,
            maxH = 720
        )
        val large = option(
            name = "enc1080",
            formats = setOf(VideoEncoderCapability.COLOR_PLANAR),
            maxW = 1920,
            maxH = 1080
        )
        val selection = VideoEncoderCapability.select(
            options = listOf(small, large),
            requestedWidth = 3840,
            requestedHeight = 2160
        )
        assertEquals("enc1080", selection?.codecName)
        assertEquals(1920, selection?.width)
    }
}
