package com.aicreatorstudio.app.logging

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-20 — the diagnostic report must carry the device's ENCODER
 * capabilities.
 *
 * The AI pipeline picks its video encoder, its color format and its
 * output size from the device's advertised capabilities. When the app
 * misbehaves on one phone but not another, that choice is the
 * evidence — so a report that only says "the AI feature does not work
 * on this phone" is not actionable.
 */
class LogReportMediaSummaryTest {

    private fun report(media: String?): String = LogReportBuilder.build(
        records = listOf(
            LogRecord(
                timestampMillis = 1_700_000_000_000L,
                level = LogLevel.ERROR,
                component = "ai_tool",
                event = "run_failed",
                message = "upscale failed"
            )
        ),
        deviceSummary = "app=1.4.0 (4) android=35 abi=arm64-v8a device=Test",
        generatedAtMillis = 1_700_000_000_000L,
        mediaSummary = media
    )

    @Test
    fun `the media line is present when a summary is supplied`() {
        val text = report(
            "encoders=OMX.qcom.video.encoder.avc[1920-4096x1080-4096 fmt=I420+NV12]"
        )
        assertTrue(
            "the report must carry the encoder capabilities",
            text.contains("media: encoders=OMX.qcom.video.encoder.avc")
        )
    }

    @Test
    fun `the report is still valid without a media summary`() {
        val text = report(null)
        assertTrue("the device line must still be there", text.contains("device: app=1.4.0"))
        assertTrue("the events must still be there", text.contains("upscale failed"))
    }

    @Test
    fun `a blank media summary is omitted rather than printed empty`() {
        val text = report("   ")
        assertTrue(
            "an empty media line is noise",
            !text.contains("media: ")
        )
    }
}
