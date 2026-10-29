package com.aicreatorstudio.app.model

/**
 * The Persian no-source error shown when subtitle generation is requested
 * on a project that has nothing to transcribe (zero clips AND zero audio
 * objects).
 *
 * 2026-10-10 — this constant replaces a mojibake string that had been
 * written into `EditorViewModel.resolveGenerationTarget` as
 * `"??? ???? ????? ?? ???? ?? ????? ???? ????"` (the UTF-8 Persian text
 * was corrupted at write time, so users saw question marks instead of an
 * explanation).
 */
const val SUBTITLE_NO_SOURCE_MESSAGE_FA: String =
    "ویدیویی برای ساخت زیرنویس در پروژه وجود ندارد"

/**
 * What one subtitle-generation run will transcribe and where its captions
 * land on the timeline — the pure result of source resolution.
 *
 * Kept free of `android.*` (notably `Uri`) so the resolution rules are
 * unit-testable in the JVM test source set; `EditorViewModel` converts
 * [sourceUriString] to a `Uri` at the call site.
 */
data class SubtitleSourceWindow(
    /** URI string of the media to decode (`clip.uri` or `audio.sourceUri`). */
    val sourceUriString: String,
    /** Timeline anchor the generated captions are placed relative to. */
    val anchorMs: Long,
    /** Inclusive source-time start of the transcribe window. */
    val windowStartMs: Long,
    /** Exclusive source-time end of the transcribe window. */
    val windowEndMs: Long,
    /** Clip speed the window is scaled by (1f for audio objects). */
    val speed: Float
)

/**
 * Resolve WHAT subtitle generation transcribes and WHERE the captions land,
 * or null when the project has nothing to transcribe.
 *
 * Resolution order (same contract the old private
 * `EditorViewModel.resolveGenerationTarget` implemented, now pure and
 * testable):
 *
 *  1. The SELECTED audio object, if any — the user explicitly pointed at
 *     a music/SFX/Video-Audio file.
 *  2. The SELECTED video clip.
 *  3. Fallback: the first non-audio-only clip, then any first clip — so
 *     generation works immediately after opening a project WITHOUT a
 *     manual timeline clip tap.
 *
 * Null means the caller must surface [SUBTITLE_NO_SOURCE_MESSAGE_FA].
 */
fun resolveSubtitleSourceWindow(state: EditorUiState): SubtitleSourceWindow? {
    val selectedAudio = state.selectedAudioClipId
        ?.let { id -> state.project?.audioClips?.firstOrNull { it.id == id } }
    if (selectedAudio != null) {
        return SubtitleSourceWindow(
            sourceUriString = selectedAudio.sourceUri,
            anchorMs = selectedAudio.startTimeMs.coerceAtLeast(0L),
            windowStartMs = maxOf(
                selectedAudio.clipOffsetMs.coerceAtLeast(0L),
                selectedAudio.sourceStartMs.coerceAtLeast(0L)
            ),
            windowEndMs = listOfNotNull(
                (selectedAudio.clipOffsetMs + selectedAudio.durationMs)
                    .takeIf { selectedAudio.durationMs > 0L },
                selectedAudio.sourceEndMs.takeIf { it > 0L }
            ).minOrNull() ?: Long.MAX_VALUE,
            speed = 1f
        )
    }
    val clip = state.selectedClip
        ?: state.project?.clips?.firstOrNull { !it.isAudioOnly }
        ?: state.project?.clips?.firstOrNull()
        ?: return null
    val trimStart = clip.trimStartMs.coerceAtLeast(0L)
    val trimEnd = clip.trimEndMs.takeIf { it > 0L }
        ?: (clip.trimStartMs + clip.durationMs)
    return SubtitleSourceWindow(
        sourceUriString = clip.uri,
        anchorMs = clip.timelineStartMs.takeIf { it >= 0 } ?: 0L,
        windowStartMs = trimStart,
        windowEndMs = trimEnd,
        speed = clip.speed.takeIf { it > 0f } ?: 1f
    )
}
