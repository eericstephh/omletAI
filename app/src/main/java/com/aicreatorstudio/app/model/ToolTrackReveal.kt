package com.aicreatorstudio.app.model

/**
 * The timeline lanes a tool panel should reveal when it opens, **in
 * priority order** — the first entry becomes the focused lane.
 *
 * ## Why this is a top-level pure function
 *
 * This mapping used to exist twice and the two copies disagreed:
 * `EditorViewModel.revealTracksForTool` mapped `SUBTITLES → SUBTITLE` and
 * `AUDIO → AUDIO_MUSIC`, while an inline `when` in `EditorScreen`'s dock
 * callback mapped `SUBTITLES → SUBTITLE + TEXT` and `AUDIO → AUDIO_MUSIC +
 * AUDIO_SFX`. Both ran (the ViewModel's via `setToolPanel`, the screen's via
 * the dock's `onPanelSelected`), so the revealed *set* was their union but
 * the *focused* lane was decided by write order — and the screen ran second,
 * so opening AI Subtitles left the **Text** lane focused rather than the
 * Subtitle lane the panel actually edits.
 *
 * Consolidating here (a) removes the drift, (b) makes the rule unit-testable
 * without Hilt or `android.*`, and (c) fixes the focus to name the lane that
 * owns the tool. The revealed sets are the exact union of the two former
 * implementations — no panel gained or lost a lane.
 *
 * Panels absent from this mapping reveal nothing; that is the deliberate
 * default (a clip-only tool such as `FILTERS` has no dedicated lane).
 */
fun tracksForTool(panel: ToolPanel): List<TrackKind> = when (panel) {
    // Audio tools — reveal both audio lanes so a music/SFX object is
    // visible regardless of which one the user actually wants.
    ToolPanel.AUDIO -> listOf(TrackKind.AUDIO_MUSIC, TrackKind.AUDIO_SFX)

    // Text-domain tools — the caption lane and the decorative-text lane
    // travel together, because a user reaching for either is usually
    // working with both. Focus the lane that owns the tool.
    ToolPanel.SUBTITLES -> listOf(TrackKind.SUBTITLE, TrackKind.TEXT)
    ToolPanel.SUBTITLE_STUDIO -> listOf(TrackKind.SUBTITLE, TrackKind.TEXT)
    ToolPanel.TEXT -> listOf(TrackKind.TEXT)
    ToolPanel.PARAGRAPH -> listOf(TrackKind.TEXT)

    // Picture-in-picture overlays get their own lane.
    ToolPanel.OVERLAY -> listOf(TrackKind.OVERLAY)

    // Per-clip effect summary lane. Transitions live on clip boundaries but
    // are surfaced in the same FX lane so the user can see the result.
    ToolPanel.EFFECTS -> listOf(TrackKind.EFFECTS)
    ToolPanel.TRANSITIONS -> listOf(TrackKind.EFFECTS)

    else -> emptyList()
}
