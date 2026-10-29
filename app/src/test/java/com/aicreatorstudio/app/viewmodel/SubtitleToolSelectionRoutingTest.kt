package com.aicreatorstudio.app.viewmodel

import com.aicreatorstudio.app.model.EditorUiState
import com.aicreatorstudio.app.model.TextOverlay
import com.aicreatorstudio.app.model.ToolPanel
import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.model.VideoProject
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-10 SUBTITLE ROUTING FIX — selection changes must never replace
 * the active subtitle tool.
 *
 * Regression guard: with `toolPanel = SUBTITLES` open, tapping a timeline
 * clip / audio object / subtitle tile changes the contextual toolbar row
 * (`editorContext`) but must leave `toolPanel` untouched, so the subtitle
 * panel stays open. Combined with the shared `subs_ai` chip now present in
 * every context (ContextualToolbarSpecTest), the open tool always has a
 * matching selected tile instead of the row silently swapping to general
 * editing tools.
 *
 * Uses the real [TimelineDelegate] / [SubtitleDelegate] selection mutators
 * (the code paths `EditorViewModel.selectClip` / `selectAudioClip` /
 * `selectTextOverlay` delegate to) against a plain state flow — no Hilt,
 * no Robolectric.
 */
class SubtitleToolSelectionRoutingTest {

    private fun clip(id: String) = VideoClip(
        id = id,
        uri = "file:///$id.mp4",
        fileName = "$id.mp4",
        durationMs = 10_000L,
        width = 1080,
        height = 1920,
        fileSizeBytes = 0L,
        importedAtEpochMillis = 0L,
        timelineStartMs = 0L
    )

    private fun stateWithSubtitlesOpen(): MutableStateFlow<EditorUiState> {
        val flow = MutableStateFlow(
            EditorUiState(
                isLoading = false,
                project = VideoProject(
                    id = "p1",
                    name = "test",
                    createdAtEpochMillis = 0L,
                    clips = listOf(clip("a"), clip("b"))
                ),
                textOverlays = listOf(
                    TextOverlay.default("سلام", 0L, 2_000L).copy(id = "sub-1")
                ),
                // The open AI-Subtitles panel.
                toolPanel = ToolPanel.SUBTITLES
            )
        )
        return flow
    }

    @Test
    fun `selecting a video clip keeps the subtitle tool open`() {
        val flow = stateWithSubtitlesOpen()
        val timeline = TimelineDelegate(flow, HistoryDelegate(flow))

        timeline.selectClip("a")

        assertEquals("a", flow.value.selectedClipId)
        assertEquals(
            "clip selection must not replace the active subtitle tool",
            ToolPanel.SUBTITLES,
            flow.value.toolPanel
        )
    }

    @Test
    fun `deselecting the clip keeps the subtitle tool open`() {
        val flow = stateWithSubtitlesOpen()
        val timeline = TimelineDelegate(flow, HistoryDelegate(flow))
        timeline.selectClip("a")

        timeline.selectClip(null)

        assertNull(flow.value.selectedClipId)
        assertEquals(ToolPanel.SUBTITLES, flow.value.toolPanel)
    }

    @Test
    fun `selecting an audio object keeps the subtitle tool open`() {
        val flow = stateWithSubtitlesOpen()
        val timeline = TimelineDelegate(flow, HistoryDelegate(flow))

        timeline.selectAudioClip("audio-1")

        assertEquals("audio-1", flow.value.selectedAudioClipId)
        assertEquals(ToolPanel.SUBTITLES, flow.value.toolPanel)
    }

    @Test
    fun `selecting a subtitle overlay keeps the subtitle tool open`() {
        val flow = stateWithSubtitlesOpen()
        val subtitles = SubtitleDelegate(flow, HistoryDelegate(flow))

        subtitles.selectTextOverlay("sub-1")

        assertEquals("sub-1", flow.value.selectedTextId)
        assertEquals(ToolPanel.SUBTITLES, flow.value.toolPanel)
    }

    @Test
    fun `a clip selection still switches the toolbar context to CLIP_SELECTED`() {
        // The context change itself is intentional (the row shows clip
        // tools); what must NOT happen is the panel being closed or
        // swapped. Assert both halves so a future edit can't "fix" the
        // symptom by freezing the toolbar.
        val flow = stateWithSubtitlesOpen()
        val timeline = TimelineDelegate(flow, HistoryDelegate(flow))

        timeline.selectClip("b")

        assertEquals(
            com.aicreatorstudio.app.model.EditorContext.CLIP_SELECTED,
            com.aicreatorstudio.app.model.resolveEditorContext(
                selectedClipId = flow.value.selectedClipId,
                selectedAudioClipId = flow.value.selectedAudioClipId,
                selectedTextId = flow.value.selectedTextId
            )
        )
        assertEquals(ToolPanel.SUBTITLES, flow.value.toolPanel)
        // And the row for that context still offers the subtitles entry.
        val chips = com.aicreatorstudio.app.ui.screens.editor.ContextualToolbarSpec
            .chipsFor(com.aicreatorstudio.app.model.EditorContext.CLIP_SELECTED)
        assertTrue(chips.any { it.id == "subs_ai" })
    }
}
