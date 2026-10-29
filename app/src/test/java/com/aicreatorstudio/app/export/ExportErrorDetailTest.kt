package com.aicreatorstudio.app.export

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-09-19 — export failures must carry the full causal chain
 * (stage + every nested cause) so one run pinpoints a muxer-style
 * failure instead of showing a bare "Muxer error".
 */
class ExportErrorDetailTest {

    @Test
    fun `describe includes stage and top message`() {
        val msg = ExportErrorDetail.describe(
            RuntimeException("Muxer error: failed to write sample"),
            "TRANSFORMER"
        )
        assertTrue(msg.contains("TRANSFORMER"))
        assertTrue(msg.contains("Muxer error: failed to write sample"))
    }

    @Test
    fun `describe walks the whole cause chain`() {
        val root = IllegalStateException("timestampUs -500 < lastTimestampUs 0")
        val mid = RuntimeException("audio track write failed", root)
        val top = RuntimeException("Muxer error", mid)
        val msg = ExportErrorDetail.describe(top, "ENCODING")
        assertTrue(msg.contains("caused by [1] RuntimeException: audio track write failed"))
        assertTrue(
            msg.contains(
                "caused by [2] IllegalStateException: timestampUs -500 < lastTimestampUs 0"
            )
        )
    }

    @Test
    fun `deepestMessage returns the root cause text`() {
        val root = IllegalStateException("timestampUs -500 < lastTimestampUs 0")
        val top = RuntimeException("Muxer error", RuntimeException("wrap", root))
        assertTrue(
            ExportErrorDetail.deepestMessage(top)
                .contains("timestampUs -500 < lastTimestampUs 0")
        )
    }

    @Test
    fun `describe never throws on messageless throwables`() {
        val msg = ExportErrorDetail.describe(NullPointerException(), "STARTUP")
        assertTrue(msg.contains("NullPointerException"))
        assertTrue(msg.contains("STARTUP"))
    }
}
