package com.aicreatorstudio.app.viewmodel

import com.aicreatorstudio.app.export.ExportState
import com.aicreatorstudio.app.model.EditorUiState
import com.aicreatorstudio.app.model.SubtitleStyle
import com.aicreatorstudio.app.model.TextOverlay
import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.model.VideoProject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-15 — the GLOBAL SUBTITLE FONT SIZE undo contract.
 *
 * `EditorViewModel` needs Android + Dagger and cannot be constructed
 * in a JVM unit test (see [com.aicreatorstudio.app.model.EditorStateHistoryTest]
 * for the same constraint). But the invariant the feature promises —
 * **one slider drag or one "apply" click costs exactly ONE undo step
 * that reverts the whole batch** — lives in the pieces that ARE pure:
 * [HistoryDelegate] (the stack) and [restoreDocumentFrom] (the merge).
 * The ViewModel's batch resize is literally:
 *
 *   push() → map textOverlays → (state only, no push) → undo()
 *
 * so this test replays that exact sequence against a real
 * [HistoryDelegate] and pins:
 *   1. one push + N live updates ⇒ ONE undo reverts every subtitle
 *      and leaves decorative text untouched;
 *   2. redo re-applies the whole batch in one step;
 *   3. both size channels move together (`fontSizeSp` AND
 *      `subtitleStyle.fontSizeSp`) — the preview/export parity rule;
 *   4. without the pre-change push there is nothing to undo — which
 *      is exactly why the slider must call begin…Resize before the
 *      first live update.
 */
class GlobalSubtitleFontSizeTest {

    // ---- fixtures ----

    private fun clip() = VideoClip(
        id = "clip-1",
        uri = "content://test/clip-1",
        fileName = "clip-1.mp4",
        durationMs = 10_000L,
        width = 1080,
        height = 1920,
        fileSizeBytes = 0L,
        importedAtEpochMillis = 0L,
        timelineStartMs = 0L
    )

    private fun subtitle(
        id: String,
        sizeSp: Float
    ) = TextOverlay(
        id = id,
        text = "زیرنویس $id",
        startTimeMs = 0L,
        endTimeMs = 2_000L,
        fontSizeSp = sizeSp,
        subtitleSource = "ai",
        subtitleStyle = SubtitleStyle(fontSizeSp = sizeSp)
    )

    /** A decorative title — must NEVER be touched by the batch ops. */
    private fun decorative(id: String, sizeSp: Float = 48f) = TextOverlay(
        id = id,
        text = "عنوان",
        startTimeMs = 0L,
        endTimeMs = 2_000L,
        fontSizeSp = sizeSp,
        subtitleSource = null,
        subtitleStyle = SubtitleStyle(fontSizeSp = sizeSp)
    )

    private fun flowWith(
        vararg overlays: TextOverlay
    ) = MutableStateFlow(
        EditorUiState(
            project = VideoProject(
                id = "project-1",
                name = "Test project",
                createdAtEpochMillis = 0L,
                clips = listOf(clip())
            ),
            textOverlays = overlays.toList()
        )
    )

    /**
     * The ViewModel's live batch update, verbatim in shape:
     * state-only, no history, no persist — both size channels.
     */
    private fun applyBatch(flow: MutableStateFlow<EditorUiState>, sizeSp: Float) {
        flow.update { state ->
            state.copy(
                textOverlays = state.textOverlays.map { overlay ->
                    if (overlay.subtitleSource != null) {
                        overlay.copy(
                            fontSizeSp = sizeSp,
                            subtitleStyle = overlay.subtitleStyle?.copy(fontSizeSp = sizeSp)
                        )
                    } else overlay
                }
            )
        }
    }

    /** The ViewModel's proportional scale update (state-only shape). */
    private fun applyScaleBatch(flow: MutableStateFlow<EditorUiState>, factor: Float) {
        flow.update { state ->
            state.copy(
                textOverlays = state.textOverlays.map { overlay ->
                    if (overlay.subtitleSource != null) {
                        val next = (overlay.fontSizeSp * factor).coerceIn(14f, 42f)
                        overlay.copy(
                            fontSizeSp = next,
                            subtitleStyle = overlay.subtitleStyle?.copy(fontSizeSp = next)
                        )
                    } else overlay
                }
            )
        }
    }

    // ---- 1. one drag == one undo step ----

    @Test
    fun `one push plus many live updates reverts the whole batch in one undo`() {
        val flow = flowWith(
            subtitle("s1", 20f),
            subtitle("s2", 34f),
            decorative("d1")
        )
        val history = HistoryDelegate(flow)

        // The slider path: begin (ONE snapshot) → 40 live frames.
        history.push()
        repeat(40) { frame ->
            applyBatch(flow, 20f + frame * 0.25f)
        }
        applyBatch(flow, 30f)
        assertEquals(30f, flow.value.textOverlays.first { it.id == "s1" }.fontSizeSp, 0.001f)

        // ONE undo click reverts everything.
        history.undo()
        val undone = flow.value.textOverlays
        assertEquals(20f, undone.first { it.id == "s1" }.fontSizeSp, 0.001f)
        assertEquals(34f, undone.first { it.id == "s2" }.fontSizeSp, 0.001f)
        assertEquals(
            "the mixed sizes must come back, not be flattened",
            34f,
            undone.first { it.id == "s2" }.subtitleStyle?.fontSizeSp ?: -1f,
            0.001f
        )
        // Decorative text is outside the batch scope in BOTH directions.
        assertEquals(48f, undone.first { it.id == "d1" }.fontSizeSp, 0.001f)
    }

    @Test
    fun `redo re-applies the batch in one step`() {
        val flow = flowWith(subtitle("s1", 24f), decorative("d1"))
        val history = HistoryDelegate(flow)

        history.push()
        applyBatch(flow, 36f)
        history.undo()
        history.redo()

        val redone = flow.value.textOverlays
        assertEquals(36f, redone.first { it.id == "s1" }.fontSizeSp, 0.001f)
        assertEquals(48f, redone.first { it.id == "d1" }.fontSizeSp, 0.001f)
    }

    // ---- 2. both size channels move together ----

    @Test
    fun `the batch moves fontSizeSp and subtitleStyle together`() {
        val flow = flowWith(subtitle("s1", 24f))
        val history = HistoryDelegate(flow)
        history.push()
        applyBatch(flow, 30f)

        val after = flow.value.textOverlays.single()
        assertEquals(30f, after.fontSizeSp, 0.001f)
        assertEquals(
            "preview/export parity: the decorative renderer reads subtitleStyle",
            30f,
            after.subtitleStyle?.fontSizeSp ?: -1f,
            0.001f
        )

        history.undo()
        val before = flow.value.textOverlays.single()
        assertEquals(24f, before.fontSizeSp, 0.001f)
        assertEquals(24f, before.subtitleStyle?.fontSizeSp ?: -1f, 0.001f)
    }

    // ---- 3. proportional scale keeps ratios and respects the band ----

    @Test
    fun `proportional scale keeps mixed size ratios`() {
        val flow = flowWith(subtitle("small", 20f), subtitle("large", 34f))
        val history = HistoryDelegate(flow)

        history.push()
        applyScaleBatch(flow, 1.1f)
        val scaled = flow.value.textOverlays
        assertEquals(22f, scaled.first { it.id == "small" }.fontSizeSp, 0.001f)
        assertEquals(37.4f, scaled.first { it.id == "large" }.fontSizeSp, 0.001f)
        val ratioBefore = 34f / 20f
        val ratioAfter =
            scaled.first { it.id == "large" }.fontSizeSp / scaled.first { it.id == "small" }.fontSizeSp
        assertEquals("ratios survive the batch", ratioBefore, ratioAfter, 0.0001f)

        history.undo()
        assertEquals(20f, flow.value.textOverlays.first { it.id == "small" }.fontSizeSp, 0.001f)
    }

    @Test
    fun `repeated scale taps cannot leave the safe band`() {
        val flow = flowWith(subtitle("s1", 40f))
        val history = HistoryDelegate(flow)
        // +10% twice: 40 → 42 (clamped), stays 42.
        history.push()
        applyScaleBatch(flow, 1.1f)
        applyScaleBatch(flow, 1.1f)
        assertEquals(
            42f,
            flow.value.textOverlays.single().fontSizeSp,
            0.001f
        )
        history.undo()
        assertEquals(40f, flow.value.textOverlays.single().fontSizeSp, 0.001f)
    }

    // ---- 4. why the begin call exists ----

    @Test
    fun `live updates without a push leave nothing to undo`() {
        val flow = flowWith(subtitle("s1", 24f))
        val history = HistoryDelegate(flow)
        assertFalse(history.canUndo())

        // The bug class this test pins: a slider that skips
        // begin…Resize must not fabricate history out of thin air.
        applyBatch(flow, 30f)
        assertFalse("no snapshot ⇒ no undo", history.canUndo())
    }

    @Test
    fun `history flags track the resize lifecycle`() {
        val flow = flowWith(subtitle("s1", 24f))
        val history = HistoryDelegate(flow)
        assertFalse(history.canUndo())

        history.push() // begin
        assertTrue(history.canUndo())
        applyBatch(flow, 30f) // 40 live frames
        assertTrue(history.canUndo())
        history.undo() // end of the world: exactly one step back
        assertFalse("the whole drag must be ONE undo step", history.canUndo())
    }

    // ---- transport isolation ----

    @Test
    fun `undo of a resize leaves non-document fields alone`() {
        val flow = flowWith(subtitle("s1", 24f))
        val history = HistoryDelegate(flow)
        flow.update { it.copy(exportState = ExportState.Preparing) }
        history.push()
        applyBatch(flow, 30f)
        history.undo()
        // The batch resize must not resurrect a stale export state
        // (restoreDocumentFrom keeps live fields on the current state).
        assertEquals(ExportState.Preparing, flow.value.exportState)
    }
}
