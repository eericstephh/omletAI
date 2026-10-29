package com.aicreatorstudio.app.viewmodel

import com.aicreatorstudio.app.model.EditorUiState
import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.model.VideoProject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineDelegateTest {

    private fun stateWith(clips: List<VideoClip> = listOf(CLIP_A, CLIP_B)) = EditorUiState(
        project = VideoProject(id = "proj_1", name = "Test", createdAtEpochMillis = 0L, clips = clips)
    )
    private val flow = MutableStateFlow(stateWith())
    private val history = HistoryDelegate(flow)
    private val delegate = TimelineDelegate(flow, history)

    companion object {
        val CLIP_A = VideoClip(id = "a", uri = "file://a.mp4", fileName = "A",
            timelineStartMs = 0L, durationMs = 10_000L, width = 1920, height = 1080,
            fileSizeBytes = 1_000_000L, importedAtEpochMillis = 0L)
        val CLIP_B = VideoClip(id = "b", uri = "file://b.mp4", fileName = "B",
            timelineStartMs = 10_000L, durationMs = 5_000L, width = 1920, height = 1080,
            fileSizeBytes = 1_000_000L, importedAtEpochMillis = 0L)
    }

    @Test fun `selectClip sets selectedClipId and clears others`() = runTest {
        delegate.selectClip("a")
        assertEquals("a", flow.value.selectedClipId)
        assertNull(flow.value.selectedAudioClipId)
        assertNull(flow.value.selectedTextId)
    }

    @Test fun `splitClip splits at position`() = runTest {
        delegate.splitClip("a", 3_000L)
        val clips = flow.value.project?.clips ?: emptyList()
        assertEquals(3, clips.size)
    }

    @Test fun `splitClip halves keep their own source spans`() = runTest {
        // Untrimmed 10s clip split at timeline 3s: left keeps source 0..3000,
        // right keeps source 3000..10000 — the right half must NOT restart
        // from source 0.
        delegate.splitClip("a", 3_000L)
        val left = flow.value.project!!.clips.first { it.id == "a" }
        val right = flow.value.project!!.clips.first { it.id != "a" && it.uri == "file://a.mp4" }
        assertEquals(0L, left.trimStartMs)
        assertEquals(3_000L, left.trimEndMs)
        assertEquals(3_000L, right.trimStartMs)
        assertEquals(-1L, right.trimEndMs)
        assertEquals(3_000L, right.timelineStartMs)
        assertEquals(7_000L, right.effectiveDurationMs)
    }

    @Test fun `splitClip on a trimmed clip splits source space, not timeline space`() = runTest {
        // Source 2000..8000 placed at timeline 0. Split at timeline 3000
        // means source position 5000 — not 3000.
        flow.value = stateWith(listOf(CLIP_A.copy(trimStartMs = 2_000L, trimEndMs = 8_000L), CLIP_B))
        delegate.splitClip("a", 3_000L)
        val left = flow.value.project!!.clips.first { it.id == "a" }
        val right = flow.value.project!!.clips.first { it.id != "a" && it.uri == "file://a.mp4" }
        assertEquals(2_000L, left.trimStartMs)
        assertEquals(5_000L, left.trimEndMs)
        assertEquals(5_000L, right.trimStartMs)
        assertEquals(8_000L, right.trimEndMs)
        assertEquals(3_000L, left.effectiveDurationMs)
        assertEquals(3_000L, right.effectiveDurationMs)
    }

    @Test fun `splitClip outside the clip span is a no-op`() = runTest {
        delegate.splitClip("a", 15_000L)
        val clips = flow.value.project?.clips ?: emptyList()
        assertEquals(2, clips.size)
        assertEquals("a", clips.first { it.uri == "file://a.mp4" }.id)
    }

    @Test fun `deleteClip removes clip`() = runTest {
        delegate.deleteClip("a")
        assertEquals(1, flow.value.project?.clips?.size)
    }

    @Test fun `duplicateClip adds copy`() = runTest {
        delegate.duplicateClip("a")
        assertEquals(3, flow.value.project?.clips?.size)
    }

    @Test fun `setClipTrim clamps to zero`() = runTest {
        delegate.setClipTrim("a", 0L, 8_000L)
        val clip = flow.value.project?.clips?.find { it.id == "a" }
        assertNotNull(clip)
        assertEquals(0L, clip?.trimStartMs)
        assertEquals(8_000L, clip?.trimEndMs)
    }

    @Test fun `setClipSpeed clamps range`() = runTest {
        delegate.setClipSpeed("a", 5f)
        assertEquals(4f, flow.value.project!!.clips.first { it.id == "a" }.speed, 0.001f)

        delegate.setClipSpeed("a", 0.1f)
        assertEquals(0.25f, flow.value.project!!.clips.first { it.id == "a" }.speed, 0.001f)
    }

    @Test fun `selectClip does not push history`() = runTest {
        assertFalse(history.canUndo())
        delegate.selectClip("a")
        assertFalse(history.canUndo())
    }

    @Test fun `splitClip pushes history`() = runTest {
        assertFalse(history.canUndo())
        delegate.splitClip("a", 3_000L)
        assertTrue(history.canUndo())
    }
}
