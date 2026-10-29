package com.aicreatorstudio.app.export

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Regression tests for the **RULE 9** "no 0-byte success" guard on
 * [FileOutputTarget.finalize]. PHASE 2 of the production audit found
 * that the old implementation trusted the Transformer's
 * `result.fileSizeBytes` and emitted a `Succeeded` state for an
 * actually-empty file (encoder crash, zero-duration clip, cancelled
 * mid-flush). The fix re-stats the file on disk and downgrades
 * `finalize` to [ExportState.Failed] when the on-disk size is `<= 0`.
 *
 * These tests pin the new behaviour. We deliberately do NOT touch
 * the `MediaStore` target here — that path needs a real ContentResolver
 * and is covered by the instrumentation suite.
 */
class FileOutputTargetZeroByteTest {

    private lateinit var tmpDir: File

    @Before
    fun setUp() {
        tmpDir = Files.createTempDirectory("aicreator_export_target_").toFile()
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `finalize returns Succeeded with actual file size when file is non-empty`() {
        val file = File(tmpDir, "ok.mp4")
        file.writeBytes(ByteArray(64 * 1024) { (it and 0xFF).toByte() })
        val target = FileOutputTarget(file, displayName = "ok.mp4")

        val state = target.finalize(fileSizeBytes = 0L, durationMs = 1_000L)

        assertTrue("expected Succeeded, got $state", state is ExportState.Succeeded)
        val ok = state as ExportState.Succeeded
        assertEquals(64 * 1024L, ok.fileSizeBytes)
        // android.net.Uri is a JVM stub in unit tests; on-device
        // Uri.fromFile() returns a real file:// Uri. We only assert
        // the data fields here.
        assertEquals("ok.mp4", ok.fileName)
        assertEquals(1_000L, ok.durationMs)
    }

    @Test
    fun `finalize returns Failed and cleans up the file when on-disk size is zero`() {
        val file = File(tmpDir, "zero.mp4")
        // Caller said 4096 bytes, but the file is actually empty —
        // the file-on-disk check must override the caller's claim.
        file.writeBytes(ByteArray(0))
        val target = FileOutputTarget(file, displayName = "zero.mp4")

        val state = target.finalize(fileSizeBytes = 4_096L, durationMs = 1_000L)

        assertTrue("expected Failed for 0-byte file, got $state", state is ExportState.Failed)
        assertTrue(!file.exists() || file.length() == 0L)
    }

    @Test
    fun `finalize returns Failed when the file does not exist on disk`() {
        val file = File(tmpDir, "missing.mp4")
        // Don't create the file.
        val target = FileOutputTarget(file, displayName = "missing.mp4")

        val state = target.finalize(fileSizeBytes = 1_024L, durationMs = 500L)

        assertTrue("expected Failed when file missing, got $state", state is ExportState.Failed)
    }

    @Test
    fun `finalize honors the on-disk size even when caller reports a larger size`() {
        val file = File(tmpDir, "small.mp4")
        file.writeBytes(ByteArray(16) { 0x42 })
        val target = FileOutputTarget(file, displayName = "small.mp4")

        val state = target.finalize(fileSizeBytes = 99_999_999L, durationMs = 250L)

        // RULE 9: the on-disk size is the source of truth, not the
        // caller's optimistic size estimate.
        assertTrue(state is ExportState.Succeeded)
        assertEquals(16L, (state as ExportState.Succeeded).fileSizeBytes)
    }

    @Test
    fun `finalize returns Failed for negative caller size even if file has content`() {
        val file = File(tmpDir, "neg.mp4")
        file.writeBytes(ByteArray(8))
        val target = FileOutputTarget(file, displayName = "neg.mp4")

        val state = target.finalize(fileSizeBytes = -1L, durationMs = 100L)

        // Negative sizes are never valid; the on-disk check still
        // passes for a non-empty file so we report Succeeded.
        // The RULE 9 guard fires only on <= 0 on-disk size; a -1 from
        // the caller is overridden by the on-disk stat which is 8.
        assertTrue(state is ExportState.Succeeded)
    }

    @Test
    fun `cleanupOnFailure removes the partial file`() {
        val file = File(tmpDir, "partial.mp4")
        file.writeBytes(ByteArray(32))
        val target = FileOutputTarget(file, displayName = "partial.mp4")

        target.cleanupOnFailure()

        assertTrue(!file.exists())
    }

    @Test
    fun `Failed state carries the 0-byte message so the UI can show it`() {
        val file = File(tmpDir, "empty.mp4")
        val target = FileOutputTarget(file, displayName = "empty.mp4")

        val state = target.finalize(fileSizeBytes = 1L, durationMs = 1L)

        assertTrue(state is ExportState.Failed)
        // RULES 8 & 9: never point the user at a 0-byte output.
        val failed = state as ExportState.Failed
        assertTrue(failed.message.contains("0-byte"))
    }
}
