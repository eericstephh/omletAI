package com.aicreatorstudio.app.model

/**
 * Which contextual toolbar the editor should show, derived purely from
 * the current selection.
 *
 * The editor used to render one fixed tool palette regardless of what the
 * user had tapped, so every tool was always two taps plus a horizontal
 * scroll away and nothing signalled "what can I do *to this thing*".
 * [resolveEditorContext] is the single input that drives the dock's
 * composition instead.
 *
 * See `docs/UX_IA_AUDIT_AND_PROPOSAL.md` §4 for the full IA.
 */
enum class EditorContext {
    /** Nothing selected — the five root tools (Templates, Text, Audio, Canvas, Overlay). */
    ROOT,

    /** A video clip is selected — Split / Speed / Smart AI / Volume / Filters / Trim. */
    CLIP_SELECTED,

    /** An audio object is selected — Beat Detection / Volume & Fade / Split. */
    AUDIO_SELECTED,

    /** A text or subtitle overlay is selected — Fonts / Animation & Styles. */
    TEXT_SELECTED
}

/**
 * Maps the three mutually-exclusive selection ids onto a single
 * [EditorContext].
 *
 * **Priority is text > audio > clip**, which mirrors the visual stacking
 * order (a text overlay renders on top of the video, audio is a sibling
 * lane). The ordering is *defensive only*: [EditorUiState] enforces that at
 * most one selection is ever non-null — see the selection mutators in
 * `TimelineDelegate` and `SubtitleDelegate`. Should that invariant ever be
 * violated, the topmost object wins so the toolbar never disagrees with
 * what the user sees on the canvas.
 *
 * Pure and dependency-free by design so it is unit-testable in the JVM test
 * source set (no Hilt, no `android.*`).
 */
fun resolveEditorContext(
    selectedClipId: String?,
    selectedAudioClipId: String?,
    selectedTextId: String?
): EditorContext = when {
    selectedTextId != null -> EditorContext.TEXT_SELECTED
    selectedAudioClipId != null -> EditorContext.AUDIO_SELECTED
    selectedClipId != null -> EditorContext.CLIP_SELECTED
    else -> EditorContext.ROOT
}

/**
 * Convenience accessor so UI code can read `uiState.editorContext` without
 * repeating the three ids at every call site.
 */
val EditorUiState.editorContext: EditorContext
    get() = resolveEditorContext(
        selectedClipId = selectedClipId,
        selectedAudioClipId = selectedAudioClipId,
        selectedTextId = selectedTextId
    )

/**
 * How close two clip edges must be to count as touching, in ms.
 *
 * Mirrors the tolerance the timeline already uses to decide whether to draw a
 * transition handle between two clips (`EditorTimeline`'s `gapMs in
 * -80L..80L`), so the conditional Transitions chip appears exactly when a
 * transition handle is available.
 */
const val ADJACENT_CLIP_TOLERANCE_MS: Long = 80L

/**
 * True when the clip [clipId] touches another clip on the timeline — the only
 * situation in which a transition can be applied.
 *
 * Drives the conditional Transitions chip in the clip toolbar (Q7 of the
 * approved IA): with nothing to join, offering a transition is a dead tap, so
 * the chip is withheld and the tool stays reachable from Root → Overlay
 * instead.
 *
 * Pure and order-tolerant: it reads the explicit `timelineStartMs` of the
 * neighbours rather than assuming the list index implies adjacency.
 */
fun hasAdjacentClip(clips: List<VideoClip>, clipId: String?): Boolean {
    if (clipId == null) return false
    val index = clips.indexOfFirst { it.id == clipId }
    if (index < 0) return false

    val clip = clips[index]
    val start = clip.timelineStartMs.takeIf { it >= 0 } ?: 0L
    val end = start + clip.effectiveDurationMs

    fun VideoClip.startOrZero(): Long = timelineStartMs.takeIf { it >= 0 } ?: 0L

    val touchesPrevious = clips.getOrNull(index - 1)
        ?.let { previous -> kotlin.math.abs(start - (previous.startOrZero() + previous.effectiveDurationMs)) <= ADJACENT_CLIP_TOLERANCE_MS }
        ?: false

    val touchesNext = clips.getOrNull(index + 1)
        ?.let { next -> kotlin.math.abs(next.startOrZero() - end) <= ADJACENT_CLIP_TOLERANCE_MS }
        ?: false

    return touchesPrevious || touchesNext
}
