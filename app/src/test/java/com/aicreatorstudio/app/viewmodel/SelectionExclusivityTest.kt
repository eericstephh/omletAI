package com.aicreatorstudio.app.viewmodel

import com.aicreatorstudio.app.model.EditorUiState
import com.aicreatorstudio.app.model.resolveEditorContext
import com.aicreatorstudio.app.model.EditorContext
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the **selection mutual-exclusion invariant** that the contextual
 * toolbar depends on.
 *
 * At most one of `selectedClipId` / `selectedAudioClipId` / `selectedTextId`
 * may be non-null at any time. Before the 2026-09-28 IA audit,
 * `SubtitleDelegate.selectTextOverlay` cleared nothing, while
 * `TimelineDelegate.selectClip` / `selectAudioClip` cleared the other two — so
 * a text tap could leave a video clip selected underneath. That was harmless
 * while nothing read more than one id, but [resolveEditorContext] reads all
 * three, so a violation would show the clip's tool set for a selected
 * subtitle.
 *
 * These delegates take only a [MutableStateFlow] (no Android or Hilt
 * dependencies), so unlike `EditorViewModel` they can be driven directly in
 * the JVM test source set.
 */
class SelectionExclusivityTest {

    private fun newState() = MutableStateFlow(EditorUiState())

    private fun fixture(): Triple<MutableStateFlow<EditorUiState>, TimelineDelegate, SubtitleDelegate> {
        val state = newState()
        val history = HistoryDelegate(state)
        return Triple(
            state,
            TimelineDelegate(state, history),
            SubtitleDelegate(state, history)
        )
    }

    // ---- selecting a clip clears the other two ----

    @Test
    fun `selecting a video clip clears audio and text`() {
        val (state, timeline, subtitles) = fixture()

        subtitles.selectTextOverlay("text-1")
        timeline.selectAudioClip("audio-1")
        timeline.selectClip("clip-1")

        val s = state.value
        assertEquals("clip-1", s.selectedClipId)
        assertNull(s.selectedAudioClipId)
        assertNull(s.selectedTextId)
        assertEquals(EditorContext.CLIP_SELECTED, resolveEditorContext(s.selectedClipId, s.selectedAudioClipId, s.selectedTextId))
    }

    // ---- selecting audio clears the other two ----

    @Test
    fun `selecting an audio clip clears clip and text`() {
        val (state, timeline, subtitles) = fixture()

        subtitles.selectTextOverlay("text-1")
        timeline.selectClip("clip-1")
        timeline.selectAudioClip("audio-1")

        val s = state.value
        assertNull(s.selectedClipId)
        assertEquals("audio-1", s.selectedAudioClipId)
        assertNull(s.selectedTextId)
        assertEquals(EditorContext.AUDIO_SELECTED, resolveEditorContext(s.selectedClipId, s.selectedAudioClipId, s.selectedTextId))
    }

    // ---- selecting text clears the other two (the 2026-09-28 fix) ----

    @Test
    fun `selecting a text overlay clears clip and audio`() {
        val (state, timeline, subtitles) = fixture()

        timeline.selectClip("clip-1")
        timeline.selectAudioClip("audio-1")
        timeline.selectClip("clip-1")
        subtitles.selectTextOverlay("text-1")

        val s = state.value
        assertNull(s.selectedClipId)
        assertNull(s.selectedAudioClipId)
        assertEquals("text-1", s.selectedTextId)
        assertEquals(EditorContext.TEXT_SELECTED, resolveEditorContext(s.selectedClipId, s.selectedAudioClipId, s.selectedTextId))
    }

    @Test
    fun `selecting a text overlay does not open a tool panel`() {
        // Q2 of the approved IA: a text tap selects only. The panel used to be
        // force-opened (TEXT / SUBTITLES / PARAGRAPH depending on the source),
        // which covered the video preview. The contextual toolbar now surfaces
        // the text tools instead.
        val (state, timeline, subtitles) = fixture()

        timeline.selectClip("clip-1")
        subtitles.selectTextOverlay("text-1")

        assertEquals("text-1", state.value.selectedTextId)
        assertNull(state.value.toolPanel)
    }

    // ---- deselecting text must NOT disturb the other selections ----

    @Test
    fun `clearing the text selection leaves the other ids alone`() {
        val (state, timeline, subtitles) = fixture()

        timeline.selectClip("clip-1")
        subtitles.selectTextOverlay(null)

        val s = state.value
        assertEquals("clip-1", s.selectedClipId)
        assertNull(s.selectedTextId)
    }

    @Test
    fun `selectTextOverlay with null does not resurrect a cleared selection`() {
        val (state, _timeline, subtitles) = fixture()

        subtitles.selectTextOverlay(null)

        val s = state.value
        assertNull(s.selectedClipId)
        assertNull(s.selectedAudioClipId)
        assertNull(s.selectedTextId)
        assertEquals(EditorContext.ROOT, resolveEditorContext(s.selectedClipId, s.selectedAudioClipId, s.selectedTextId))
    }

    // ---- the round trip a real user performs ----

    @Test
    fun `tapping text then clip then audio leaves exactly one selection each time`() {
        val (state, timeline, subtitles) = fixture()

        subtitles.selectTextOverlay("text-1")
        assertOneSelection(state.value)

        timeline.selectClip("clip-1")
        assertOneSelection(state.value)

        timeline.selectAudioClip("audio-1")
        assertOneSelection(state.value)
    }

    /** Fails with the offending id triplet when more than one id is set. */
    private fun assertOneSelection(s: EditorUiState) {
        val set = listOfNotNull(s.selectedClipId, s.selectedAudioClipId, s.selectedTextId)
        assertEquals(
            "expected exactly one selection, got $set (clip=${s.selectedClipId} " +
                "audio=${s.selectedAudioClipId} text=${s.selectedTextId})",
            1,
            set.size
        )
    }
}
