package com.aicreatorstudio.app.ai.vad

import com.aicreatorstudio.app.ai.TestContext
import com.aicreatorstudio.app.model.AutoSilenceCutState
import com.aicreatorstudio.app.model.EditorUiState
import com.aicreatorstudio.app.model.SilencePreviewClip
import com.aicreatorstudio.app.model.SilencePreviewSegment
import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.model.VideoProject
import com.aicreatorstudio.app.model.restoreDocumentFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * PHASE 2 — JVM tests for Remove Silence with per-cut control.
 *
 * Covers: normalize (overlap / adjacent / clamp / drop), selection
 * model (toggle / all / none / durations), selective apply via
 * [SilenceCutter.filterSelected] + [SilenceCutter.buildCutClips],
 * preview source-key staleness, and history/persistence wiring
 * (panel state stays volatile, document restores).
 */
class SilenceRemoveTest {

    private lateinit var cutter: SilenceCutter

    @Before
    fun setUp() {
        val tempDir = Files.createTempDirectory("silence-remove-test").toFile()
        cutter = SilenceCutter(TestContext(tempDir))
    }

    // ---- helpers ----

    private fun clip(
        id: String,
        durationMs: Long,
        trimStartMs: Long = 0L,
        trimEndMs: Long = -1L,
        timelineStartMs: Long = -1L,
        audioOnly: Boolean = false
    ) = VideoClip(
        id = id,
        uri = "file:///$id.mp4",
        fileName = "$id.mp4",
        durationMs = durationMs,
        width = 1920,
        height = 1080,
        fileSizeBytes = 1_000L,
        importedAtEpochMillis = 0L,
        trimStartMs = trimStartMs,
        trimEndMs = trimEndMs,
        timelineStartMs = timelineStartMs,
        isAudioOnly = audioOnly
    )

    private fun analysis(
        clipId: String,
        vararg ranges: Pair<Long, Long>,
        durationMs: Long = 10_000L
    ) = SilenceCutter.ClipSilenceAnalysis(
        clipId = clipId,
        fileName = "$clipId.mp4",
        clipDurationMs = durationMs,
        segments = ranges.map { (s, e) -> SilenceSegment(s, e, 0.9f) },
        analyzedDurationMs = durationMs
    )

    private fun seg(id: String, clipId: String, s: Long, e: Long, selected: Boolean = true) =
        SilencePreviewSegment(id, clipId, s, e, 0.9f, selected)

    // ---- normalize ----

    @Test
    fun `overlapping segments merge with weighted confidence`() {
        val out = SilenceSegmentMapper.normalize(
            listOf(SilenceSegment(0, 1000, 1.0f), SilenceSegment(500, 1500, 0.5f)),
            10_000L
        )
        assertEquals(1, out.size)
        assertEquals(0L, out[0].startMs)
        assertEquals(1500L, out[0].endMs)
        // (1.0*1000 + 0.5*1000) / 2000 = 0.75
        assertEquals(0.75f, out[0].confidence, 0.001f)
    }

    @Test
    fun `touching segments merge, gapped segments stay separate`() {
        val out = SilenceSegmentMapper.normalize(
            listOf(SilenceSegment(0, 500, 0.9f), SilenceSegment(500, 1000, 0.9f), SilenceSegment(2000, 2500, 0.9f)),
            10_000L
        )
        assertEquals(2, out.size)
        assertEquals(0L, out[0].startMs)
        assertEquals(1000L, out[0].endMs)
        assertEquals(2000L, out[1].startMs)
    }

    @Test
    fun `out-of-bounds segments clamp to clip duration`() {
        val out = SilenceSegmentMapper.normalize(
            listOf(SilenceSegment(-500, 1000, 0.9f), SilenceSegment(9000, 20_000, 0.9f)),
            10_000L
        )
        assertEquals(2, out.size)
        assertEquals(0L, out[0].startMs)
        assertEquals(1000L, out[0].endMs)
        assertEquals(9000L, out[1].startMs)
        assertEquals(10_000L, out[1].endMs)
    }

    @Test
    fun `zero-duration and fully out-of-range segments drop`() {
        val out = SilenceSegmentMapper.normalize(
            listOf(
                SilenceSegment(1000, 1000, 0.9f),
                SilenceSegment(3000, 2000, 0.9f),
                SilenceSegment(20_000, 30_000, 0.9f)
            ),
            10_000L
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `normalize sorts by start`() {
        val out = SilenceSegmentMapper.normalize(
            listOf(SilenceSegment(5000, 6000, 0.9f), SilenceSegment(1000, 2000, 0.9f)),
            10_000L
        )
        assertEquals(1000L, out[0].startMs)
        assertEquals(5000L, out[1].startMs)
    }

    // ---- selection model ----

    private fun previewWith(vararg segs: SilencePreviewSegment) =
        AutoSilenceCutState.Preview(
            clips = listOf(SilencePreviewClip("c1", "c1.mp4", 10_000L, segs.toList())),
            totalSegments = segs.size,
            totalSilenceMs = segs.sumOf { it.durationMs }
        )

    @Test
    fun `selected duration sums only selected cuts`() {
        val p = previewWith(
            seg("c1:0", "c1", 1000, 2000, true),
            seg("c1:1", "c1", 4000, 6000, false),
            seg("c1:2", "c1", 8000, 8500, true)
        )
        assertEquals(2, p.selectedCount)
        assertEquals(1500L, p.selectedMs)
        assertEquals(3, p.totalSegments)
    }

    @Test
    fun `deselect-all yields zero selected duration`() {
        val p = previewWith(seg("c1:0", "c1", 1000, 2000, false))
        assertEquals(0, p.selectedCount)
        assertEquals(0L, p.selectedMs)
    }

    @Test
    fun `toggling one segment leaves others untouched`() {
        val p = previewWith(
            seg("c1:0", "c1", 1000, 2000, true),
            seg("c1:1", "c1", 4000, 6000, true)
        )
        // Mirror of EditorViewModel.toggleSilenceSegment.
        val toggled = p.copy(
            clips = p.clips.map { c ->
                c.copy(segments = c.segments.map { s ->
                    if (s.id == "c1:1") s.copy(selected = !s.selected) else s
                })
            }
        )
        assertEquals(1, toggled.selectedCount)
        assertEquals(1000L, toggled.selectedMs)
        assertTrue(toggled.clips.single().segments[0].selected)
        assertFalse(toggled.clips.single().segments[1].selected)
    }

    // ---- selective apply ----

    @Test
    fun `filterSelected keeps only chosen indices`() {
        val analyses = listOf(analysis("c1", 1000L to 2000L, 4000L to 6000L))
        val out = SilenceCutter.filterSelected(analyses, setOf(SilenceCutter.segmentId("c1", 1)))
        assertEquals(1, out.single().segments.size)
        assertEquals(4000L, out.single().segments.single().startMs)
    }

    @Test
    fun `applying a subset removes only selected silence`() {
        val clips = listOf(clip("c1", 10_000L))
        val analyses = listOf(analysis("c1", 1000L to 2000L, 4000L to 6000L))
        val filtered = SilenceCutter.filterSelected(analyses, setOf(SilenceCutter.segmentId("c1", 0)))
        val result = cutter.buildCutClips(clips, filtered)
        // Removed 1000 ms of the 3000 ms total silence.
        assertEquals(1000L, result.totalSilenceMsRemoved)
        assertEquals(9000L, result.newClips.sumOf { it.effectiveDurationMs })
    }

    @Test
    fun `empty selection keeps every clip whole`() {
        val clips = listOf(clip("c1", 10_000L), clip("c2", 8000L))
        val analyses = listOf(
            analysis("c1", 1000L to 2000L),
            analysis("c2", 1000L to 2000L, durationMs = 8000L)
        )
        val filtered = SilenceCutter.filterSelected(analyses, emptySet())
        val result = cutter.buildCutClips(clips, filtered)
        assertEquals(0L, result.totalSilenceMsRemoved)
        assertEquals(18_000L, result.newClips.sumOf { it.effectiveDurationMs })
        assertEquals(2, result.newClips.size)
    }

    @Test
    fun `entire-clip silence drops the clip without negative durations`() {
        val clips = listOf(clip("c1", 5000L))
        val analyses = listOf(analysis("c1", 0L to 5000L, durationMs = 5000L))
        val filtered = SilenceCutter.filterSelected(analyses, setOf(SilenceCutter.segmentId("c1", 0)))
        val result = cutter.buildCutClips(clips, filtered)
        assertTrue(result.newClips.isEmpty())
        assertEquals(5000L, result.totalSilenceMsRemoved)
    }

    @Test
    fun `out-of-bounds silence can never exceed the clip`() {
        val clips = listOf(clip("c1", 10_000L))
        val analyses = listOf(analysis("c1", -500L to 20_000L))
        val filtered = SilenceCutter.filterSelected(analyses, setOf(SilenceCutter.segmentId("c1", 0)))
        val result = cutter.buildCutClips(clips, filtered)
        assertTrue(result.newClips.isEmpty())
        assertEquals(10_000L, result.totalSilenceMsRemoved)
    }

    @Test
    fun `multiple clips are processed independently`() {
        val clips = listOf(clip("c1", 10_000L), clip("c2", 10_000L))
        val analyses = listOf(
            analysis("c1", 1000L to 2000L),
            analysis("c2", 1000L to 2000L, 3000L to 4000L)
        )
        // Select c1's only cut; leave c2 untouched.
        val filtered = SilenceCutter.filterSelected(analyses, setOf(SilenceCutter.segmentId("c1", 0)))
        val result = cutter.buildCutClips(clips, filtered)
        assertEquals(1000L, result.totalSilenceMsRemoved)
        // c1 → 9000 ms speech, c2 → full 10000 ms.
        assertEquals(19_000L, result.newClips.sumOf { it.effectiveDurationMs })
        result.newClips.forEach {
            assertTrue(it.effectiveDurationMs > 0)
            assertTrue(it.trimStartMs >= 0)
            assertTrue(it.trimEndMs > it.trimStartMs)
        }
    }

    // ---- staleness ----

    @Test
    fun `source key changes when timeline is edited`() {
        val before = listOf(clip("c1", 10_000L), clip("c2", 8000L))
        val keyBefore = SilenceCutter.previewSourceKey(before)
        assertEquals(keyBefore, SilenceCutter.previewSourceKey(before))
        // Trim edit.
        assertFalse(keyBefore == SilenceCutter.previewSourceKey(listOf(clip("c1", 10_000L, trimStartMs = 500L), clip("c2", 8000L))))
        // Clip removed.
        assertFalse(keyBefore == SilenceCutter.previewSourceKey(listOf(clip("c1", 10_000L))))
        // Duration changed (re-import / different source).
        assertFalse(keyBefore == SilenceCutter.previewSourceKey(listOf(clip("c1", 9000L), clip("c2", 8000L))))
    }

    // ---- history / persistence wiring ----

    @Test
    fun `undo restores document but keeps live preview state`() {
        val project = VideoProject(
            id = "p1",
            name = "test",
            createdAtEpochMillis = 0L,
            clips = listOf(clip("c1", 10_000L))
        )
        val snapshot = EditorUiState(
            project = project,
            autoSilenceCutState = AutoSilenceCutState.Preview(
                clips = listOf(
                    SilencePreviewClip("c1", "c1.mp4", 10_000L, listOf(seg("c1:0", "c1", 1000, 2000))))
            )
        )
        val current = EditorUiState(
            project = project.copy(clips = listOf(clip("c1", 10_000L), clip("c2", 5000L))),
            autoSilenceCutState = AutoSilenceCutState.Idle
        )
        val restored = restoreDocumentFrom(snapshot, current)
        // Document comes from snapshot…
        assertEquals(1, restored.project!!.clips.size)
        // …live panel state stays with current (volatile, never persisted).
        assertTrue(restored.autoSilenceCutState is AutoSilenceCutState.Idle)
    }
}
