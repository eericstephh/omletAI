package com.aicreatorstudio.app.ui.screens.editor

import com.aicreatorstudio.app.model.VideoClip
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistPositionTest {

    private fun clip(
        id: String,
        start: Long,
        durationMs: Long = 10_000L,
        trimStart: Long = 0L,
        trimEnd: Long = -1L
    ) = VideoClip(
        id = id, uri = "file://$id.mp4", fileName = id,
        timelineStartMs = start, durationMs = durationMs,
        trimStartMs = trimStart, trimEndMs = trimEnd,
        width = 1920, height = 1080, fileSizeBytes = 1L,
        importedAtEpochMillis = 0L
    )

    private val clips = listOf(
        clip("a", start = 0L),
        clip("b", start = 10_000L, durationMs = 8_000L),
        clip("c", start = 18_000L, durationMs = 10_000L,
            trimStart = 2_000L, trimEnd = 7_000L)
    )

    @Test fun `resolve maps positions inside each clip`() {
        assertEquals(0 to 0L, PlaylistPosition.resolve(clips, 0L))
        assertEquals(0 to 5_000L, PlaylistPosition.resolve(clips, 5_000L))
        assertEquals(1 to 0L, PlaylistPosition.resolve(clips, 10_000L))
        assertEquals(1 to 4_000L, PlaylistPosition.resolve(clips, 14_000L))
    }

    @Test fun `resolve uses window coordinates for trimmed clips`() {
        // Clip c: source 2000..7000 (5s window) drawn at timeline 18000..23000.
        assertEquals(2 to 0L, PlaylistPosition.resolve(clips, 18_000L))
        assertEquals(2 to 2_000L, PlaylistPosition.resolve(clips, 20_000L))
        assertEquals(2 to 4_999L, PlaylistPosition.resolve(clips, 22_999L))
        // The window is only 5s wide: editor 23000 is past it.
        assertEquals(2 to 4_999L, PlaylistPosition.resolve(clips, 23_000L))
    }

    @Test fun `resolve skips gaps by parking on the next clip start`() {
        // Gap between a (ends 10000) and b (starts 10000) — none here, so
        // build an explicit gap case.
        val gapped = listOf(clip("a", 0L), clip("b", 20_000L, durationMs = 4_000L))
        assertEquals(1 to 0L, PlaylistPosition.resolve(gapped, 15_000L))
    }

    @Test fun `resolve clamps before the first clip and past the last`() {
        assertEquals(0 to 0L, PlaylistPosition.resolve(clips, -5_000L))
        // Last clip's drawn span is 18000..23000 (5s window).
        assertEquals(2 to 4_999L, PlaylistPosition.resolve(clips, 60_000L))
    }

    @Test fun `resolve handles empty playlist`() {
        assertEquals(0 to 0L, PlaylistPosition.resolve(emptyList(), 1_000L))
    }

    @Test fun `editorMs reconstructs the timeline position`() {
        assertEquals(14_000L, PlaylistPosition.editorMs(clips, itemIndex = 1, windowPositionMs = 4_000L))
        // Trimmed clip: window pos is already window-relative.
        assertEquals(21_000L, PlaylistPosition.editorMs(clips, itemIndex = 2, windowPositionMs = 3_000L))
        assertEquals(0L, PlaylistPosition.editorMs(emptyList(), itemIndex = 3, windowPositionMs = 0L))
    }

    @Test fun `totalMs sums drawn spans`() {
        // a: 10s, b: 8s (ends at 18000), c: 5s window drawn at 18000 → 23000.
        assertEquals(23_000L, PlaylistPosition.totalMs(clips))
        assertEquals(0L, PlaylistPosition.totalMs(emptyList()))
    }
}
