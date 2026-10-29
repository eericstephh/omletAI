package com.aicreatorstudio.app.model

import com.aicreatorstudio.app.export.ExportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [restoreDocumentFrom] — the merge that undo / redo uses
 * to re-apply a history snapshot.
 *
 * The undo stack stores whole [EditorUiState] values, so restoring one
 * wholesale also restored transport, export, AI-job and one-shot-trigger
 * fields. These tests pin the split: **document** fields come from the
 * snapshot, **live** fields stay on the current state.
 *
 * The merge is a pure function precisely so it can be tested here —
 * `EditorViewModel` needs Android + Dagger and cannot be constructed in a
 * JVM unit test.
 */
class EditorStateHistoryTest {

    // ---- fixtures ----

    private fun clip(
        id: String,
        startMs: Long = 0L,
        durationMs: Long = 1_000L
    ) = VideoClip(
        id = id,
        uri = "content://test/$id",
        fileName = "$id.mp4",
        durationMs = durationMs,
        width = 1920,
        height = 1080,
        fileSizeBytes = 0L,
        importedAtEpochMillis = 0L,
        timelineStartMs = startMs
    )

    private fun state(
        clips: List<VideoClip>,
        selectedClipId: String? = null
    ) = EditorUiState(
        project = VideoProject(
            id = "project-1",
            name = "Test project",
            createdAtEpochMillis = 0L,
            clips = clips
        ),
        selectedClipId = selectedClipId
    )

    // ---- document fields ARE restored ----

    @Test
    fun `undo restores the pre-edit clip list from the snapshot`() {
        val beforeSplit = state(clips = listOf(clip("a")))
        // The user split clip "a", leaving two clips behind.
        val afterSplit = state(
            clips = listOf(clip("a", durationMs = 500L), clip("b", startMs = 500L))
        )

        val restored = restoreDocumentFrom(beforeSplit, afterSplit)

        assertEquals(listOf("a"), restored.project!!.clips.map { it.id })
    }

    @Test
    fun `undo restores the document selection from the snapshot`() {
        val beforeSplit = state(clips = listOf(clip("a")), selectedClipId = "a")
        val afterSplit = state(
            clips = listOf(clip("a"), clip("b", startMs = 500L)),
            selectedClipId = "b"
        )

        val restored = restoreDocumentFrom(beforeSplit, afterSplit)

        assertEquals("a", restored.selectedClipId)
    }

    @Test
    fun `undo restores per-clip edit properties`() {
        val beforeEdit = state(clips = listOf(clip("a").copy(speed = 1f, opacity = 1f)))
        val afterEdit = state(clips = listOf(clip("a").copy(speed = 2f, opacity = 0.4f)))

        val restored = restoreDocumentFrom(beforeEdit, afterEdit)

        val restoredClip = restored.project!!.clips.single()
        assertEquals(1f, restoredClip.speed, 1e-6f)
        assertEquals(1f, restoredClip.opacity, 1e-6f)
    }

    @Test
    fun `undo restores document-level grading mirrors and canvas`() {
        val beforeEdit = state(clips = listOf(clip("a"))).copy(
            brightness = 1.4f,
            rotation = 90,
            canvasPreset = CanvasPreset.SQUARE_1_1,
            canvasBackground = CanvasBackground.Blur
        )
        val afterEdit = state(clips = listOf(clip("a"))).copy(
            brightness = 1f,
            rotation = 0,
            canvasPreset = CanvasPreset.VERTICAL_9_16,
            canvasBackground = CanvasBackground.Black
        )

        val restored = restoreDocumentFrom(beforeEdit, afterEdit)

        assertEquals(1.4f, restored.brightness, 1e-6f)
        assertEquals(90, restored.rotation)
        assertEquals(CanvasPreset.SQUARE_1_1, restored.canvasPreset)
        assertEquals(CanvasBackground.Blur, restored.canvasBackground)
    }

    // ---- live fields are NOT restored ----

    @Test
    fun `undo does not resurrect stale transport state`() {
        // Snapshot was taken while the preview was playing from 0.
        val snapshot = state(clips = listOf(clip("a")))
            .copy(isPlaying = true, playbackPositionMs = 0L)
        // The user has since paused 5s in.
        val current = state(clips = listOf(clip("a"), clip("b", startMs = 500L)))
            .copy(isPlaying = false, playbackPositionMs = 5_000L)

        val restored = restoreDocumentFrom(snapshot, current)

        assertTrue("undo must not resume playback", !restored.isPlaying)
        assertEquals(5_000L, restored.playbackPositionMs)
    }

    @Test
    fun `undo does not report a running export as finished`() {
        val snapshot = state(clips = listOf(clip("a")))
            .copy(exportState = ExportState.Idle)
        val current = state(clips = listOf(clip("a"), clip("b", startMs = 500L)))
            .copy(exportState = ExportState.Preparing)

        val restored = restoreDocumentFrom(snapshot, current)

        assertEquals(ExportState.Preparing, restored.exportState)
    }

    @Test
    fun `undo never resurrects an already-consumed one-shot trigger`() {
        val snapshot = state(clips = listOf(clip("a")))
            .copy(pendingSeekMs = 1_234L, srtExportTrigger = SrtExportTrigger("x.srt", "body"))
        val current = state(clips = listOf(clip("a"), clip("b", startMs = 500L)))
            .copy(pendingSeekMs = null, srtExportTrigger = null)

        val restored = restoreDocumentFrom(snapshot, current)

        assertNull("a consumed seek trigger must not replay", restored.pendingSeekMs)
        assertNull("a consumed SRT trigger must not replay", restored.srtExportTrigger)
    }

    @Test
    fun `undo leaves the open panel zoom and fullscreen untouched`() {
        val snapshot = state(clips = listOf(clip("a")))
            .copy(toolPanel = null, pixelsPerMs = 0.06f, isFullscreen = false)
        val current = state(clips = listOf(clip("a"), clip("b", startMs = 500L)))
            .copy(toolPanel = ToolPanel.SPEED, pixelsPerMs = 0.25f, isFullscreen = true)

        val restored = restoreDocumentFrom(snapshot, current)

        assertEquals(ToolPanel.SPEED, restored.toolPanel)
        assertEquals(0.25f, restored.pixelsPerMs, 1e-6f)
        assertTrue(restored.isFullscreen)
    }

    @Test
    fun `undo leaves the error banner and loading flag untouched`() {
        val snapshot = state(clips = listOf(clip("a")))
        val current = state(clips = listOf(clip("a"), clip("b", startMs = 500L)))
            .copy(isLoading = true, errorMessage = "media missing")

        val restored = restoreDocumentFrom(snapshot, current)

        assertTrue(restored.isLoading)
        assertEquals("media missing", restored.errorMessage)
    }

    // ---- history flags are owned by the caller ----

    @Test
    fun `history flags come from the live state not the snapshot`() {
        val snapshot = state(clips = listOf(clip("a")))
            .copy(canUndo = false, canRedo = false)
        val current = state(clips = listOf(clip("a")))
            .copy(canUndo = true, canRedo = true)

        val restored = restoreDocumentFrom(snapshot, current)

        assertTrue(restored.canUndo)
        assertTrue(restored.canRedo)
    }

    // ---- round trip ----

    @Test
    fun `a delete followed by undo round trips the clip list`() {
        val original = state(clips = listOf(clip("a"), clip("b", startMs = 1_000L)))
        val afterDelete = state(clips = listOf(clip("a")))

        val restored = restoreDocumentFrom(original, afterDelete)

        assertEquals(listOf("a", "b"), restored.project!!.clips.map { it.id })
    }
}
