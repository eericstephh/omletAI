package com.aicreatorstudio.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [tracksForTool] — the single source of truth for which
 * timeline lanes a tool panel reveals.
 *
 * Regression guard: this mapping previously existed twice (in
 * `EditorViewModel.revealTracksForTool` and as an inline `when` in
 * `EditorScreen`'s dock callback) and the copies disagreed. Both ran, so the
 * *focused* lane depended on write order and opening AI Subtitles left the
 * Text lane focused instead of the Subtitle lane. These assertions pin the
 * unified behaviour.
 */
class ToolTrackRevealTest {

    @Test
    fun `audio tools reveal both audio lanes with music focused`() {
        assertEquals(
            listOf(TrackKind.AUDIO_MUSIC, TrackKind.AUDIO_SFX),
            tracksForTool(ToolPanel.AUDIO)
        )
    }

    @Test
    fun `subtitles reveal the subtitle lane first and the text lane second`() {
        assertEquals(
            listOf(TrackKind.SUBTITLE, TrackKind.TEXT),
            tracksForTool(ToolPanel.SUBTITLES)
        )
    }

    @Test
    fun `the subtitle studio reveals the same lanes as the AI subtitles panel`() {
        // The studio edits the same captions, so it must not reveal a
        // different set — that divergence is exactly what this file guards.
        assertEquals(
            tracksForTool(ToolPanel.SUBTITLES),
            tracksForTool(ToolPanel.SUBTITLE_STUDIO)
        )
    }

    @Test
    fun `opening a subtitle tool focuses the subtitle lane not the text lane`() {
        // The old inline implementation called setTrackVisibility(SUBTITLE)
        // then setTrackVisibility(TEXT), and the latter wins the focus — so
        // the AI Subtitles panel focused the decorative-text lane. First
        // entry is the focus now.
        assertEquals(TrackKind.SUBTITLE, tracksForTool(ToolPanel.SUBTITLES).first())
        assertEquals(TrackKind.SUBTITLE, tracksForTool(ToolPanel.SUBTITLE_STUDIO).first())
    }

    @Test
    fun `text tools reveal only the text lane`() {
        assertEquals(listOf(TrackKind.TEXT), tracksForTool(ToolPanel.TEXT))
        assertEquals(listOf(TrackKind.TEXT), tracksForTool(ToolPanel.PARAGRAPH))
    }

    @Test
    fun `overlay reveals only the overlay lane`() {
        assertEquals(listOf(TrackKind.OVERLAY), tracksForTool(ToolPanel.OVERLAY))
    }

    @Test
    fun `effects and transitions both reveal the fx lane`() {
        assertEquals(listOf(TrackKind.EFFECTS), tracksForTool(ToolPanel.EFFECTS))
        assertEquals(listOf(TrackKind.EFFECTS), tracksForTool(ToolPanel.TRANSITIONS))
    }

    // ---- the deliberate default ----

    @Test
    fun `clip-only tools reveal nothing`() {
        // A clip-scoped tool with no dedicated lane must not surprise the
        // user by expanding an unrelated track.
        listOf(
            ToolPanel.FILTERS,
            ToolPanel.ADJUST,
            ToolPanel.TRIM,
            ToolPanel.SPEED,
            ToolPanel.VOLUME,
            ToolPanel.CROP,
            ToolPanel.TRANSFORM,
            ToolPanel.OPACITY,
            ToolPanel.ANIMATIONS,
            ToolPanel.ASPECT_RATIO,
            ToolPanel.BACKGROUND
        ).forEach { panel ->
            assertTrue(
                "expected $panel to reveal no lanes, got ${tracksForTool(panel)}",
                tracksForTool(panel).isEmpty()
            )
        }
    }

    @Test
    fun `every ToolPanel resolves without throwing`() {
        // Exhaustiveness guard: a new enum value must not crash the dock.
        ToolPanel.entries.forEach { tracksForTool(it) }
    }

    @Test
    fun `no mapping ever returns a duplicate lane`() {
        ToolPanel.entries.forEach { panel ->
            val kinds = tracksForTool(panel)
            assertEquals(
                "duplicate lane in $panel -> $kinds",
                kinds.size,
                kinds.toSet().size
            )
        }
    }
}
