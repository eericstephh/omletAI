package com.aicreatorstudio.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * PHASE B3 — JVM unit tests for the per-model install status.
 *
 * The brief explicitly forbids showing a model as "READY" when
 * it isn't actually installed and verified. These tests lock
 * down the [AiModelStatus] enum surface so a future refactor
 * cannot quietly change "INSTALLED" to mean something looser
 * (e.g. "file present, no SHA check").
 *
 * They also lock the per-status pill labels so the AI Subtitle
 * card's UI strings cannot drift silently.
 */
class AiModelStatusTest {

    @Test
    fun `every AiModelStatus has a non-blank pillLabel that the UI can render directly`() {
        for (status in AiModelStatus.entries) {
            assertNotNull("status=$status must have a pillLabel", status.pillLabel)
            assertTrue(
                "status=$status must have a non-blank pillLabel " +
                    "so the card never renders an empty pill",
                status.pillLabel.isNotBlank()
            )
        }
    }

    @Test
    fun `pillLabel is stable - the card's per-row string is locked`() {
        // If anyone renames these the AI Subtitle card will start
        // showing the wrong status without a test failure; this
        // test forces an explicit update.
        assertEquals("Ready", AiModelStatus.INSTALLED.pillLabel)
        assertEquals("Download", AiModelStatus.NOT_INSTALLED.pillLabel)
        assertEquals("Downloading…", AiModelStatus.DOWNLOADING.pillLabel)
        assertEquals("Failed", AiModelStatus.FAILED.pillLabel)
        // 2026-09-13 CLOUD FIX — the cloud row's own pill label.
        assertEquals("Cloud", AiModelStatus.CLOUD_READY.pillLabel)
    }

    @Test
    fun `the five AiModelStatus values cover the user-visible install lifecycle`() {
        // The AI Subtitle card maps each row to one of these
        // pills. If a future refactor drops a value the card will
        // have a hole; this test forces a re-think.
        // 2026-09-13 — CLOUD_READY joined: the cloud row is always
        // ready (no local file), never "Download"/"Failed".
        val expected = setOf(
            AiModelStatus.INSTALLED,
            AiModelStatus.NOT_INSTALLED,
            AiModelStatus.DOWNLOADING,
            AiModelStatus.FAILED,
            AiModelStatus.CLOUD_READY
        )
        assertEquals(expected, AiModelStatus.entries.toSet())
    }

    @Test
    fun `forSpec returns CLOUD_READY for the cloud spec regardless of disk state`() {
        // 2026-09-13 CLOUD FIX — the cloud spec's status must NOT
        // be derived from the Small catalog mapping's disk file:
        // even with a bare TestContext (no models on disk), and
        // even with Whisper Small fully installed, the cloud row
        // reports CLOUD_READY. This pins the pre-fix bug where the
        // cloud row showed "Download" (and its button downloaded
        // 488 MB of Small).
        val ctx = TestContext(File(createTempDirectory().toFile(), "cloud-status").apply { mkdirs() })
        assertEquals(AiModelStatus.CLOUD_READY, AiModelStatus.forSpec(ctx, WhisperModelSpec.CLOUD_API))
        // Local specs still consult the disk (nothing installed →
        // NOT_INSTALLED; the disk-verified path stays untouched).
        assertEquals(
            AiModelStatus.NOT_INSTALLED,
            AiModelStatus.forSpec(ctx, WhisperModelSpec.SMALL)
        )
    }

    @Test
    fun `isCloud is true only for CLOUD_API`() {
        // The flag every download/pill/hint decision branches on.
        assertTrue(WhisperModelSpec.CLOUD_API.isCloud)
        assertFalse(WhisperModelSpec.SMALL.isCloud)
        // 2026-09-24 — WhisperModelSpec.MEDIUM was removed from
        // production on 2026-09-21 (users migrated to Small), which
        // left this reference stale and broke the whole JVM test
        // source set. SMALL above already covers the local-spec case.
    }

    @Test
    fun `AiModelStatus is an enum - the ViewModel uses it as a Map value`() {
        // The EditorUiState stores `aiModelStatuses: Map<WhisperModelSpec, AiModelStatus>`.
        // That requires a stable identity for each value, which only
        // an enum gives. If someone refactors this to a sealed class
        // the ViewModel collection will silently fail to dedupe.
        val first = AiModelStatus.INSTALLED
        val second = AiModelStatus.INSTALLED
        assertTrue("enum entries must be reference-equal", first === second)
    }
}
