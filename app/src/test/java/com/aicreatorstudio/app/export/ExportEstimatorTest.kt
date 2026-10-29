package com.aicreatorstudio.app.export

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportEstimatorTest {

    @Test
    fun `size estimate uses the real quality bitrates`() {
        // 10 s at 1080p: (6_000_000 + 192_000) bps × 10 s / 8 = 7.74 MB
        val bytes = ExportEstimator.estimatedFileSizeBytes(
            durationMs = 10_000L,
            quality = ExportQuality.HIGH_1080P
        )
        assertEquals(7_740_000L, bytes)
    }

    @Test
    fun `zero or negative duration estimates to zero`() {
        assertEquals(0L, ExportEstimator.estimatedFileSizeBytes(0L, ExportQuality.HIGH_1080P))
        assertEquals(0L, ExportEstimator.estimatedFileSizeBytes(-5_000L, ExportQuality.STANDARD_720P))
    }

    @Test
    fun `sizes format in KB MB and GB`() {
        assertEquals("845 KB", ExportEstimator.formatSize(865_280L))
        assertEquals("12.3 MB", ExportEstimator.formatSize(12_897_280L))
        assertEquals("1.25 GB", ExportEstimator.formatSize(1_342_177_280L))
        assertEquals("—", ExportEstimator.formatSize(0L))
    }

    @Test
    fun `durations format as m-s`() {
        assertEquals("0:08", ExportEstimator.formatDuration(8_100L))
        assertEquals("1:23", ExportEstimator.formatDuration(83_000L))
        assertEquals("12:05", ExportEstimator.formatDuration(725_000L))
        assertEquals("—", ExportEstimator.formatDuration(0L))
    }

    @Test
    fun `a one minute reel at 1080p estimates under 50 MB`() {
        val bytes = ExportEstimator.estimatedFileSizeBytes(60_000L, ExportQuality.HIGH_1080P)
        // 46.4 MB — comfortably inside the platform upload caps and a
        // number the picker can honestly show.
        assertEquals(46_440_000L, bytes)
    }
}
