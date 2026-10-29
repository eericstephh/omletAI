package com.aicreatorstudio.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 2026-10-20 — guards the matting sidecar's on-disk contract.
 *
 * ## The write pass must be ATOMIC
 *
 * The old pass deleted the previous PNGs up front and wrote straight
 * into the live directory, so a crash / OOM / process death between
 * the first and the last frame left the project with **no masks at
 * all** — the previously applied Background Remove result was
 * destroyed by a failed re-run, and the export then failed with
 * "masks are missing or outdated".
 *
 * The pass now stages into a sibling directory and swaps it in with
 * two renames.
 *
 * ## A manifest with NO frames is not a valid sidecar
 *
 * The verification loop over an empty frame list completed trivially
 * and returned `true`, so an empty sidecar was treated as "masks are
 * ready" and the export composited nothing at all.
 *
 * ## The lookup helper's name must match its behaviour
 *
 * `nearestTimestampPng` returned the CEILING (the first mask at or
 * after the target), not the nearest. The export path relies on the
 * ceiling, so the behaviour was right and the name was wrong — a trap
 * for any new caller. It is now
 * [MattingSidecar.firstTimestampAtOrAfterPng].
 */
class MattingSidecarTest {

    private fun context(): Pair<android.content.Context, File> {
        val dir = Files.createTempDirectory("matting-sidecar").toFile()
        return com.aicreatorstudio.app.ai.TestContext(dir) to dir
    }

    private fun writePng(dir: File, timestampMs: Long): MattingSidecar.WrittenFrame {
        val file = File(dir, "$timestampMs.png")
        file.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        return MattingSidecar.WrittenFrame(
            timestampMs = timestampMs,
            fileName = file.name,
            sha256 = "sha-$timestampMs"
        )
    }

    @Test
    fun `the write pass stages into a sibling directory and leaves the live one intact`() {
        val (ctx, _) = context()
        val projectId = "p1"
        val clipId = "c1"

        // A first, successful pass.
        val staging1 = MattingSidecar.beginWritePass(ctx, projectId, clipId)
        val frames1 = listOf(writePng(staging1, 0L), writePng(staging1, 100L))
        MattingSidecar.finalizeManifest(
            dir = staging1,
            algorithm = "test",
            modelSha256 = null,
            sourceWidth = 100,
            sourceHeight = 100,
            intervalMs = 100L,
            frames = frames1
        )

        val live = MattingSidecar.directoryFor(ctx, projectId, clipId)
        assertTrue("the promoted pass must land in the live directory", File(live, "0.png").exists())
        assertTrue(File(live, "100.png").exists())
        assertTrue(File(live, "manifest.json").exists())

        // A SECOND pass starts staging — the live masks must survive.
        val staging2 = MattingSidecar.beginWritePass(ctx, projectId, clipId)
        assertTrue(
            "the staging directory must be a SIBLING of the live one, not a child",
            staging2.parentFile == live.parentFile && staging2.name != live.name
        )
        assertTrue(
            "the previously applied masks must still be on disk while the new pass runs",
            File(live, "0.png").exists() && File(live, "100.png").exists()
        )
        assertTrue(
            "the previous manifest must still be readable while the new pass runs",
            File(live, "manifest.json").exists()
        )
    }

    @Test
    fun `the promoted pass replaces the previous frames`() {
        val (ctx, _) = context()
        val projectId = "p2"
        val clipId = "c2"

        val s1 = MattingSidecar.beginWritePass(ctx, projectId, clipId)
        MattingSidecar.finalizeManifest(
            dir = s1, algorithm = "test", modelSha256 = null,
            sourceWidth = 100, sourceHeight = 100, intervalMs = 100L,
            frames = listOf(writePng(s1, 0L), writePng(s1, 100L))
        )

        val s2 = MattingSidecar.beginWritePass(ctx, projectId, clipId)
        MattingSidecar.finalizeManifest(
            dir = s2, algorithm = "test", modelSha256 = null,
            sourceWidth = 100, sourceHeight = 100, intervalMs = 50L,
            frames = listOf(writePng(s2, 0L), writePng(s2, 50L))
        )

        val live = MattingSidecar.directoryFor(ctx, projectId, clipId)
        assertTrue("the new frame must be present", File(live, "50.png").exists())
        assertTrue("the shared timestamp must be present", File(live, "0.png").exists())
        assertFalse(
            "the old frame from the previous pass must be gone — a stale mask would " +
                "be picked up by the export's directory index",
            File(live, "100.png").exists()
        )
        assertFalse(
            "the staging directory must be cleaned up",
            File(live.parentFile, "${live.name}.staging").exists()
        )
    }

    @Test
    fun `a manifest with no frames is not a valid sidecar`() {
        val (ctx, _) = context()
        val projectId = "p3"
        val clipId = "c3"

        val staging = MattingSidecar.beginWritePass(ctx, projectId, clipId)
        MattingSidecar.finalizeManifest(
            dir = staging, algorithm = "test", modelSha256 = null,
            sourceWidth = 100, sourceHeight = 100, intervalMs = 100L,
            frames = emptyList()
        )

        assertFalse(
            "an empty sidecar must be rejected — the old check looped over an empty " +
                "list, completed trivially and returned true",
            MattingSidecar.verify(ctx, projectId, clipId, null)
        )
    }

    @Test
    fun `the lookup returns the first mask at or after the target`() {
        val (ctx, _) = context()
        val projectId = "p4"
        val clipId = "c4"
        val staging = MattingSidecar.beginWritePass(ctx, projectId, clipId)
        listOf(0L, 100L, 200L).forEach { writePng(staging, it) }
        val live = MattingSidecar.directoryFor(ctx, projectId, clipId)
        listOf(0L, 100L, 200L).forEach { writePng(live, it) }

        assertEquals(
            "150 ms must resolve to the 200 ms mask (the ceiling), not the 100 ms one",
            "200.png",
            MattingSidecar.firstTimestampAtOrAfterPng(live, 150L)?.name
        )
        assertEquals(
            "an exact hit resolves to itself",
            "100.png",
            MattingSidecar.firstTimestampAtOrAfterPng(live, 100L)?.name
        )
        assertEquals(
            "before the first mask resolves to the first mask",
            "0.png",
            MattingSidecar.firstTimestampAtOrAfterPng(live, 0L)?.name
        )
        assertEquals(
            "after the last mask resolves to the last mask",
            "200.png",
            MattingSidecar.firstTimestampAtOrAfterPng(live, 9999L)?.name
        )
        assertNull(
            "an empty directory resolves to null",
            MattingSidecar.firstTimestampAtOrAfterPng(
                File(live.parentFile, "empty-dir"), 0L
            )
        )
    }

    @Test
    fun `the lookup sorts by the parsed timestamp, not by the file name`() {
        val (ctx, _) = context()
        val projectId = "p5"
        val clipId = "c5"
        val live = MattingSidecar.directoryFor(ctx, projectId, clipId)
        // "1000" sorts before "900" as a STRING — the old sort put the
        // 900 ms mask after the 1000 ms one.
        writePng(live, 1000L)
        writePng(live, 900L)

        assertEquals(
            "900.png",
            MattingSidecar.firstTimestampAtOrAfterPng(live, 0L)?.name
        )
    }

    @Test
    fun `a stray non numeric file is ignored by the lookup`() {
        val (ctx, _) = context()
        val projectId = "p6"
        val clipId = "c6"
        val live = MattingSidecar.directoryFor(ctx, projectId, clipId)
        writePng(live, 500L)
        File(live, "notes.png").writeBytes(byteArrayOf(1))

        val picked = MattingSidecar.firstTimestampAtOrAfterPng(live, 0L)
        assertNotNull(picked)
        assertEquals("500.png", picked?.name)
    }
}
