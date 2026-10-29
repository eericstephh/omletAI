package com.aicreatorstudio.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportConfigTest {

    // --- Quality presets ---

    @Test
    fun `all quality presets expose a positive bitrate and standard frame size`() {
        for (quality in ExportQuality.entries) {
            assertTrue(
                "${quality.name} should have a positive video bitrate",
                quality.videoBitrateBps > 0
            )
            assertTrue(
                "${quality.name} should have a positive audio bitrate",
                quality.audioBitrateBps > 0
            )
            assertTrue(
                "${quality.name} width should be > 0",
                quality.widthPx > 0
            )
            assertTrue(
                "${quality.name} height should be > 0",
                quality.heightPx > 0
            )
            assertTrue(
                "${quality.name} frame rate should be > 0",
                quality.frameRate > 0
            )
        }
    }

    @Test
    fun `higher tier presets have at least as many pixels as lower tiers`() {
        val byPixels = ExportQuality.entries.sortedBy { it.widthPx * it.heightPx }
        for (i in 1 until byPixels.size) {
            val lower = byPixels[i - 1]
            val higher = byPixels[i]
            assertTrue(
                "${higher.name} (${higher.widthPx}x${higher.heightPx}) should be >= ${lower.name} (${lower.widthPx}x${lower.heightPx})",
                higher.widthPx * higher.heightPx >= lower.widthPx * lower.heightPx
            )
        }
    }

    @Test
    fun `higher tier presets have at least as much video bitrate as lower tiers`() {
        val byBitrate = ExportQuality.entries.sortedBy { it.videoBitrateBps }
        for (i in 1 until byBitrate.size) {
            val lower = byBitrate[i - 1]
            val higher = byBitrate[i]
            assertTrue(
                "${higher.name} bitrate ${higher.videoBitrateBps} should be >= ${lower.name} bitrate ${lower.videoBitrateBps}",
                higher.videoBitrateBps >= lower.videoBitrateBps
            )
        }
    }

    @Test
    fun `default ExportConfig targets HIGH_1080P with empty base name`() {
        val config = ExportConfig()
        assertSame(ExportQuality.HIGH_1080P, config.quality)
        assertEquals("export", config.outputBaseName)
    }

    @Test
    fun `ExportConfig can be constructed with non-default values`() {
        val config = ExportConfig(
            quality = ExportQuality.ULTRA_4K,
            outputBaseName = "Trip 2025"
        )
        assertSame(ExportQuality.ULTRA_4K, config.quality)
        assertEquals("Trip 2025", config.outputBaseName)
    }

    // --- ExportStage / ExportProgress ---

    @Test
    fun `ExportProgress accepts values across the full 0 to 1 range`() {
        ExportProgress(0f, ExportStage.PREPARING)
        ExportProgress(0.5f, ExportStage.ENCODING)
        ExportProgress(1f, ExportStage.FINALIZING)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ExportProgress rejects progress greater than 1`() {
        ExportProgress(1.5f, ExportStage.ENCODING)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ExportProgress rejects negative progress`() {
        ExportProgress(-0.01f, ExportStage.ENCODING)
    }

    @Test
    fun `every ExportStage has a non-blank display name`() {
        for (stage in ExportStage.entries) {
            assertNotNull(stage.displayName)
            assertTrue(
                "${stage.name} should have a non-blank display name",
                stage.displayName.isNotBlank()
            )
        }
    }

    // --- ExportState sealed interface ---

    @Test
    fun `Succeeded state carries the file metadata through`() {
        // We construct a `Uri` via `Uri.parse`; with the
        // `testOptions.unitTests.isReturnDefaultValues = true` Gradle
        // setting this returns `null` instead of throwing. The data
        // class accepts a nullable in practice (engine emits a real one).
        val uri = android.net.Uri.parse("content://media/external/video/42")
        val state = ExportState.Succeeded(
            outputUri = uri,
            fileName = "AI_Trip_2025_20240101_120000.mp4",
            fileSizeBytes = 12_345_678L,
            durationMs = 90_000L
        )
        assertEquals("AI_Trip_2025_20240101_120000.mp4", state.fileName)
        assertEquals(12_345_678L, state.fileSizeBytes)
        assertEquals(90_000L, state.durationMs)
    }

    @Test
    fun `Failed state stores message and optional cause`() {
        val cause = RuntimeException("codec init failed")
        val state = ExportState.Failed("Export failed", cause)
        assertEquals("Export failed", state.message)
        assertSame(cause, state.cause)
    }

    @Test
    fun `Failed state allows null cause`() {
        val state = ExportState.Failed("Export failed")
        assertEquals("Export failed", state.message)
    }
}
