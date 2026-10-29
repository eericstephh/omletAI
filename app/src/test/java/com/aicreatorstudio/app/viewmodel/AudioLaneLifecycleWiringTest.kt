package com.aicreatorstudio.app.viewmodel

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-19 — guards that the "Video Audio" lane follows its video clip
 * through every lifecycle action.
 *
 * ## The defect this pins
 *
 * A video clip's soundtrack is a separate `AudioClip` keyed by
 * `originVideoClipId`. Three actions reshaped `project.clips` only, so the
 * audio lane drifted one step behind the video lane every time:
 *
 *  - **split** — one tile kept spanning both halves (fixed earlier today,
 *    `AudioClipSplit` + `splitPairedAudioClips`);
 *  - **delete** — the tile outlived the clip it belonged to, which is the
 *    bug the user reported ("ویدیو پاک میشه ولی صدا پاک نمیشه");
 *  - **duplicate** — the copy had no tile at all until a project reload, at
 *    which point `seedVideoAudioClipsIfNeeded` invented one.
 *
 * Compose/Hilt cannot be driven under this project's JVM stack, so the
 * wiring is asserted structurally against the real source — the same shape
 * as `ExtractAudioWiringTest` / `ProgressBarWiringTest`. The behavioural
 * arithmetic lives in `AudioClipSplitTest`.
 *
 * Every assertion is a POSITIVE token match inside a slice that runs from
 * one declaration to the next, so a comment cannot satisfy it and a
 * refactor that renames the call fails loudly.
 */
class AudioLaneLifecycleWiringTest {

    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("settings.gradle.kts not found above ${File(".").absolutePath}")
    }

    private fun viewModelSource(): String {
        val f = File(
            repoRoot(),
            "app/src/main/java/com/aicreatorstudio/app/viewmodel/EditorViewModel.kt"
        )
        assertTrue("missing file: $f", f.isFile)
        return f.readText()
    }

    /** Slice from [marker] up to the next occurrence of [terminator]. */
    private fun slice(src: String, marker: String, terminator: String): String {
        val start = src.indexOf(marker)
        assertTrue("marker not found: $marker", start >= 0)
        val from = start + marker.length
        val end = src.indexOf(terminator, from)
        assertTrue("terminator not found after $marker: $terminator", end >= 0)
        return src.substring(from, end)
    }

    private fun deleteClipBody(src: String): String =
        slice(src, "fun deleteClip(clipId: String): Boolean {", "\n    /**")

    private fun duplicateClipBody(src: String): String =
        slice(src, "fun duplicateClip(clipId: String) {", "\n    // Per-clip properties")

    private fun splitClipBody(src: String): String =
        slice(src, "    fun splitClip(", "\n    /**")

    private fun updateClipPositionBody(src: String): String =
        slice(src, "fun updateClipPosition(", "\n    /** Imports an audio")

    private fun deleteAudioClipBody(src: String): String =
        slice(
            src,
            "fun deleteAudioClip(audioClipId: String): AudioDeleteOutcome {",
            "\n    /**"
        )

    private fun moveAudioClipBody(src: String): String =
        slice(src, "fun moveAudioClip(", "\n    /**")

    private fun timelineSource(): String {
        val f = File(
            repoRoot(),
            "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/EditorTimeline.kt"
        )
        assertTrue("missing file: $f", f.isFile)
        return f.readText()
    }

    private fun editorScreenSource(): String {
        val f = File(
            repoRoot(),
            "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/EditorScreen.kt"
        )
        assertTrue("missing file: $f", f.isFile)
        return f.readText()
    }

    @Test
    fun `deleting a clip also deletes its paired audio object`() {
        val body = deleteClipBody(viewModelSource())
        assertTrue(
            "deleteClip must drop the AudioClip whose originVideoClipId is the deleted clip",
            body.contains("originVideoClipId != clipId")
        )
        assertTrue(
            "deleteClip must hand the pruned list to the project",
            body.contains("audioClips = audioClips")
        )
        assertTrue(
            "the audio selection must not outlive its object",
            body.contains("selectedAudioClipId")
        )
    }

    @Test
    fun `deleting a clip persists the audio table, not just the clip table`() {
        // `audioClipsJson` is a different column from the clip table; without
        // this write a reload resurrects the orphan tile.
        val body = deleteClipBody(viewModelSource())
        assertTrue(
            "deleteClip must call persistProjectMeta() when it removed audio",
            body.contains("persistProjectMeta()")
        )
        assertTrue("deleteClip must still persist the clip table", body.contains("persistClips()"))
    }

    @Test
    fun `duplicating a clip also duplicates its paired audio object`() {
        val body = duplicateClipBody(viewModelSource())
        assertTrue(
            "duplicateClip must clone the AudioClip of the source clip",
            body.contains("originVideoClipId == clipId")
        )
        assertTrue(
            "the cloned audio must point at the NEW clip id, or the seeder duplicates on reload",
            body.contains("originVideoClipId = duplicatedClip.id")
        )
        assertTrue(
            "the clone must be shifted by the same delta as the video",
            body.contains("audio.startTimeMs + shift")
        )
        assertTrue(
            "duplicateClip must persist the audio table too",
            body.contains("persistProjectMeta()")
        )
    }

    @Test
    fun `splitting a clip also splits its paired audio object`() {
        val body = splitClipBody(viewModelSource())
        assertTrue(
            "splitClip must cut the audio lane as well",
            body.contains("splitPairedAudioClips(")
        )
        assertTrue(
            "splitClip must persist the audio table when it split audio",
            body.contains("persistProjectMeta()")
        )
    }

    @Test
    fun `dragging a clip carries its paired audio object`() {
        // Same drift, different gesture: a drag rewrites `timelineStartMs`
        // only, so the soundtrack tile stayed at the old position and the two
        // lanes described different edits.
        val body = updateClipPositionBody(viewModelSource())
        assertTrue(
            "updateClipPosition must find the audio object of the dragged clip",
            body.contains("originVideoClipId == clipId")
        )
        assertTrue(
            "the audio tile must move by the same delta as the video",
            body.contains("audio.startTimeMs + delta")
        )
        assertTrue(
            "updateClipPosition must persist the audio table when it shifted audio",
            body.contains("persistProjectMeta()")
        )
    }

    @Test
    fun `deleting an audio object removes it from the audio table`() {
        // 2026-10-20 — the reverse of the bug above. `deleteClip` covered the
        // video side, but nothing removed an audio object, and the audio
        // toolbar shipped no delete action at all, so an imported music track
        // could be split but never removed.
        val body = deleteAudioClipBody(viewModelSource())
        assertTrue(
            "deleteAudioClip must drop the object from project.audioClips",
            body.contains("audioClips = p.audioClips.filter { it.id != audioClipId }")
        )
        assertTrue(
            "the audio selection must not outlive its own object",
            body.contains("selectedAudioClipId")
        )
        assertTrue(
            "audioClips live in the project meta blob, so that table must be written",
            body.contains("persistProjectMeta()")
        )
    }

    @Test
    fun `deleting a paired audio object is refused with a reason`() {
        // A paired tile is only a *view* of the clip's own soundtrack.
        // `seedVideoAudioClipsIfNeeded` rebuilds a paired row for every video
        // clip that lacks one, and the exporter re-extracts the soundtrack
        // from the video source — so deleting the row cannot stick. Muting is
        // the supported way to silence a clip, and the refusal must be stated
        // rather than leaving the tile untouched with no feedback.
        val body = deleteAudioClipBody(viewModelSource())
        assertTrue(
            "deleteAudioClip must tell the two cases apart via originVideoClipId",
            body.contains("target.originVideoClipId != null")
        )
        assertTrue(
            "a paired object must be reported, not silently dropped",
            body.contains("AudioDeleteOutcome.PairedAudio")
        )
        assertTrue(
            "the paired branch must return BEFORE any mutation or history push",
            body.indexOf("AudioDeleteOutcome.PairedAudio") < body.indexOf("historyDelegate.push()")
        )
        assertTrue(
            "muting the owner was rejected — the row would be re-seeded anyway",
            !body.contains("clip.copy(volume = 0f)")
        )
    }

    @Test
    fun `the paired refusal reaches the user`() {        val src = viewModelSource()
        val body = slice(src, "fun notifyPairedAudioNotDeletable() {", "\n    /**")
        assertTrue(
            "the refusal must set an editor notice, or the button reads as broken",
            body.contains("editorNotice")
        )
        val screen = File(
            repoRoot(),
            "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/EditorScreen.kt"
        )
        assertTrue("missing file: $screen", screen.isFile)
        val branch = slice(screen.readText(), "\"delete\" -> {", "\"duplicate\" ->")
        assertTrue(
            "the dock must call the notifier when the delete is refused",
            branch.contains("notifyPairedAudioNotDeletable()")
        )
    }

    @Test
    fun `the delete action branches on the audio selection`() {
        // The audio context clears `selectedClipId`, so the old handler
        // (`selectedClipId?.let { deleteClip(it) }`) was a guaranteed no-op
        // there — the button only ever acted on the video timeline.
        val f = File(
            repoRoot(),
            "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/EditorScreen.kt"
        )
        assertTrue("missing file: $f", f.isFile)
        val src = f.readText()
        val branch = slice(src, "\"delete\" -> {", "\"duplicate\" ->")
        assertTrue(
            "the delete branch must read the audio selection",
            branch.contains("uiState.selectedAudioClipId")
        )
        assertTrue(
            "the delete branch must call deleteAudioClip",
            branch.contains("viewModel.deleteAudioClip(")
        )
        assertTrue(
            "the video path must still exist",
            branch.contains("viewModel.deleteClip(")
        )
    }

    // ---- drag-to-move (2026-10-20) ----

    @Test
    fun `an audio drag resolves its delta against live state`() {
        // The tile's `pointerInput` is keyed on the clip id, so it never
        // restarts during a drag and its own `startTimeMs` is frozen. An
        // absolute position computed in the gesture would be
        // `frozenStart + oneFrame` — the tile jitters in place. The delta must
        // therefore be resolved here, against the authoritative state.
        val body = moveAudioClipBody(viewModelSource())
        assertTrue(
            "moveAudioClip must take a delta, not an absolute start",
            body.contains("deltaMs")
        )
        assertTrue(
            "the new position must be derived from the live start",
            body.contains("audio.startTimeMs + deltaMs")
        )
        assertTrue(
            "a drag must never push the tile before zero",
            body.contains("coerceAtLeast(0L)")
        )
        assertTrue(
            "audioClips live in the project meta blob, so that table must be written",
            body.contains("persistProjectMeta()")
        )
    }

    @Test
    fun `dragging a paired audio tile moves the clip it belongs to`() {
        // The two lanes are one object. Moving only the audio row would desync
        // the soundtrack from its clip — the drift this whole test class exists
        // to prevent — so a paired drag delegates to the clip mutator that
        // already carries its audio along.
        val body = moveAudioClipBody(viewModelSource())
        assertTrue(
            "a paired object must be detected",
            body.contains("audio.originVideoClipId")
        )
        assertTrue(
            "a paired drag must move the owning clip, not just the row",
            body.contains("updateClipPosition(ownerId,")
        )
        assertTrue(
            "the owning clip's current start must come from live state too",
            body.contains("clip.timelineStartMs")
        )
    }

    @Test
    fun `the audio lane tile has a real drag gesture wired to the callback`() {
        val src = timelineSource()
        assertTrue(
            "the audio tile must pass the per-frame delta to the callback",
            src.contains("currentOnMoved(audio.id, deltaMs)")
        )
        assertTrue(
            "the lane must forward the move callback to the tile",
            src.contains("onAudioClipMoved = onAudioClipMoved")
        )
        assertTrue(
            "the tile must not steal a tap: nothing is consumed before the slop",
            src.contains("if (abs(accDx) <= touchSlopPx) continue")
        )
        assertTrue(
            "a vertical swipe must still scroll the lane stack",
            src.contains("abs(accDy) > abs(accDx)")
        )
    }

    @Test
    fun `clip drags read their callbacks through rememberUpdatedState`() {
        // `ClipBlock`'s gesture is keyed on `label`, which is constant while a
        // clip is dragged, so its `onMove` was frozen at the composition that
        // created it — and `onMove` closes over the clip's start. A frozen
        // start turns an incremental delta into "originalStart + oneFrame",
        // i.e. the tile jitters instead of following the finger.
        val src = timelineSource()
        assertTrue(
            "ClipBlock must read onMove live",
            src.contains("val currentOnMove by rememberUpdatedState(onMove)")
        )
        assertTrue(
            "the gesture must call the live lambda",
            src.contains("currentOnMove(deltaMs)")
        )
        assertTrue(
            "trim must be live as well",
            src.contains("val currentOnTrim by rememberUpdatedState(onTrim)")
        )
    }

    @Test
    fun `a drag release clears the undo coalescing latches`() {
        // The latch used to be set on the first drag frame and never cleared,
        // so dragging the SAME object a second time — in particular after an
        // undo, which popped the entry the latch believed was still there —
        // skipped `historyDelegate.push()` and the move was not undoable.
        // `lastTrimmedClipId` always had a reset (`onClipTrimEnd`); the drag
        // latches did not.
        val timeline = timelineSource()
        assertTrue(
            "the timeline must expose a drag-release callback",
            timeline.contains("onDragEnd: () -> Unit = {}")
        )
        assertTrue(
            "release must be routed through the shared drag-state lambda",
            timeline.contains("val setDraggingClip: (Boolean) -> Unit = { dragging ->") &&
                timeline.contains("if (!dragging) onDragEnd()")
        )
        assertTrue(
            "every lane must report through setDraggingClip, not raw state writes",
            !timeline.contains("onClipDragStateChange = { isDraggingClip = it }") &&
                !timeline.contains("onAudioClipDragStateChange = { isDraggingClip = it }")
        )

        val screen = editorScreenSource()
        assertTrue(
            "the editor must clear both drag latches on release",
            screen.contains("lastDraggedAudioId = null") &&
                screen.contains("lastDraggedClipId = null")
        )
        assertTrue(
            "the reset must actually be wired to the timeline",
            screen.contains("onDragEnd = onDragEnd")
        )
    }
}
