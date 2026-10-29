package com.aicreatorstudio.app.viewmodel

import com.aicreatorstudio.app.model.EditorUiState
import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.model.VideoProject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

fun clip(id: String, fileName: String, uri: String = "file://$fileName") = VideoClip(
    id = id, uri = uri, fileName = fileName,
    durationMs = 10_000L, width = 1920, height = 1080, fileSizeBytes = 1_000_000L,
    importedAtEpochMillis = 0L
)

class HistoryDelegateTest {

    private val initialState = EditorUiState(
        project = VideoProject(
            id = "proj_1",
            name = "Test",
            createdAtEpochMillis = 0L,
            clips = listOf(clip("clip_1", "A"), clip("clip_2", "B"))
        )
    )

    @Test
    fun `push snapshots current state`() = runTest {
        val flow = MutableStateFlow(initialState)
        val delegate = HistoryDelegate(flow)

        delegate.push()
        flow.value = flow.value.copy(brightness = 0.5f)
        delegate.push()

        delegate.undo()
        assertEquals(0.5f, flow.value.brightness, 0.001f)

        delegate.undo()
        assertEquals(1f, flow.value.brightness, 0.001f)
    }

    @Test
    fun `undo restores previous state`() = runTest {
        val flow = MutableStateFlow(initialState)
        val delegate = HistoryDelegate(flow)

        delegate.push()
        flow.value = flow.value.copy(brightness = 0.5f)
        delegate.push()
        flow.value = flow.value.copy(contrast = 0.3f)

        delegate.undo()
        assertEquals(0.5f, flow.value.brightness, 0.001f)
        assertEquals(1f, flow.value.contrast, 0.001f)

        delegate.undo()
        assertEquals(1f, flow.value.brightness, 0.001f)
    }

    @Test
    fun `redo re-applies undone state`() = runTest {
        val flow = MutableStateFlow(initialState)
        val delegate = HistoryDelegate(flow)

        delegate.push()
        flow.value = flow.value.copy(brightness = 0.5f)
        delegate.undo()

        assertTrue(flow.value.brightness == 1f)

        delegate.redo()
        assertEquals(0.5f, flow.value.brightness, 0.001f)
    }

    @Test
    fun `no-op when project is null`() = runTest {
        val flow = MutableStateFlow(EditorUiState())
        val delegate = HistoryDelegate(flow)

        delegate.push()
        delegate.undo()
        delegate.redo()
        // no crash
    }

    @Test
    fun `flags reflect stack state`() = runTest {
        val flow = MutableStateFlow(initialState)
        val delegate = HistoryDelegate(flow)

        assertFalse(delegate.canUndo())
        assertFalse(delegate.canRedo())

        delegate.push()
        flow.value = flow.value.copy(brightness = 0.5f)

        assertTrue(delegate.canUndo())
        assertFalse(delegate.canRedo())

        delegate.undo()
        assertFalse(delegate.canUndo())
        assertTrue(delegate.canRedo())
    }

    @Test
    fun `push clears redo stack`() = runTest {
        val flow = MutableStateFlow(initialState)
        val delegate = HistoryDelegate(flow)

        delegate.push()
        flow.value = flow.value.copy(brightness = 0.5f)
        delegate.undo()
        assertTrue(delegate.canRedo())

        delegate.push()
        flow.value = flow.value.copy(brightness = 0.7f)
        assertFalse(delegate.canRedo())
    }
}
