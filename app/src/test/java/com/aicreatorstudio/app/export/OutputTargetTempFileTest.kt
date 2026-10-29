package com.aicreatorstudio.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-09-19 — two-step safe export: the Transformer renders into
 * an app-private cache temp (never a MediaStore `fd:` path — that
 * dies with EROFS inside the muxer), and stale temps are swept
 * without ever touching user content.
 */
class OutputTargetTempFileTest {

    @Test
    fun `temp names embed the timestamp and stay mp4`() {
        assertEquals(
            "export_temp_1700000000000.mp4",
            OutputTarget.tempExportFileName(1_700_000_000_000L)
        )
    }

    @Test
    fun `sweep recognises exactly our temps`() {
        assertTrue(OutputTarget.isTempExportFile("export_temp_1700000000000.mp4"))
        assertFalse(OutputTarget.isTempExportFile("AI_MyClip_20260919_120000.mp4"))
        assertFalse(OutputTarget.isTempExportFile("export_temp_notes.txt"))
        assertFalse(OutputTarget.isTempExportFile("random.mp4"))
        assertFalse(OutputTarget.isTempExportFile(""))
    }
}
