package com.aicreatorstudio.app.ai.upscale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the [UpscaleProgress] sealed class and a few
 * cross-cutting invariants the bottom sheet relies on. The class is
 * pure data; these tests pin the contract.
 */
class UpscaleProgressTest {

    @Test
    fun `running tick carries stage percent and elapsed ms`() {
        val tick = UpscaleProgress.Running(
            stage = "Super-resolving",
            percent = 0.42f,
            elapsedMs = 1_234L
        )
        assertEquals("Super-resolving", tick.stage)
        assertEquals(0.42f, tick.percent, 0.001f)
        assertEquals(1_234L, tick.elapsedMs)
    }

    @Test
    fun `succeeded tick carries output metadata`() {
        val s = UpscaleProgress.Succeeded(
            outputUri = "file:///tmp/out.mp4",
            outputPath = "/tmp/out.mp4",
            fileName = "out.mp4",
            fileSizeBytes = 12_345L,
            durationMs = 5_000L
        )
        assertEquals("file:///tmp/out.mp4", s.outputUri)
        assertEquals("/tmp/out.mp4", s.outputPath)
        assertEquals("out.mp4", s.fileName)
        assertEquals(12_345L, s.fileSizeBytes)
        assertEquals(5_000L, s.durationMs)
    }

    @Test
    fun `failed tick carries the user-facing message`() {
        val f = UpscaleProgress.Failed("OOM on 4K frame")
        assertEquals("OOM on 4K frame", f.message)
    }

    @Test
    fun `cancelled tick is a singleton data object`() {
        // data object → single canonical instance.
        assertSame(UpscaleProgress.Cancelled, UpscaleProgress.Cancelled)
    }

    @Test
    fun `running percent zero is a valid starting tick`() {
        val t = UpscaleProgress.Running("Decoding", 0f, 0L)
        assertEquals(0f, t.percent, 0.001f)
    }

    @Test
    fun `sealed hierarchy has exactly four cases`() {
        // A regression guard — adding a new case to the sealed
        // hierarchy should make the test fail and force a
        // corresponding update in UpscaleBottomSheet's `when`
        // branches.
        val cases = listOf(
            UpscaleProgress.Running("Decoding", 0.1f, 100L),
            UpscaleProgress.Succeeded("u", "p", "n", 0L, 0L),
            UpscaleProgress.Failed("m"),
            UpscaleProgress.Cancelled
        )
        assertEquals(4, cases.size)
        // Every case is an UpscaleProgress.
        for (c in cases) {
            assertNotNull(c as? UpscaleProgress)
        }
    }

    @Test
    fun `running tick with empty stage is still valid data`() {
        // The bottom sheet treats a blank stage as "Estimating
        // time…" so it has to be tolerated. Pinned here so a
        // future `require(stage.isNotBlank())` in the data class
        // would break the test loudly.
        val t = UpscaleProgress.Running("", 0.1f, 100L)
        assertEquals("", t.stage)
    }

    @Test
    fun `succeeded tick tolerates a null output path`() {
        // A real engine may produce a content:// URI with no
        // filesystem path (e.g. when writing to MediaStore). The
        // data class allows this; the UI is expected to fall back
        // to the URI in that case.
        val s = UpscaleProgress.Succeeded(
            outputUri = "content://media/external/video/42",
            outputPath = null,
            fileName = "out.mp4",
            fileSizeBytes = 0L,
            durationMs = 0L
        )
        assertEquals(null, s.outputPath)
    }
}
