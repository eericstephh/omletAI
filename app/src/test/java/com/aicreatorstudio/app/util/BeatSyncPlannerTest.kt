package com.aicreatorstudio.app.util

import com.aicreatorstudio.app.model.AudioClip
import com.aicreatorstudio.app.model.VideoClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 6 — JVM tests for beat-sync planning.
 *
 * Covers: beat validation (negative/OOB/duplicate/close/non-monotonic),
 * scoring (intensity + spacing + determinism + ties), density modes
 * (caps + gaps), candidate generation (boundaries, min pieces, dedupe,
 * multi-beat), source-key staleness, and cut normalization for apply.
 * Detector DSP itself stays covered by BeatDetectorTest.
 */
class BeatSyncPlannerTest {

    private fun marker(t: Long, intensity: Float = 0.9f) =
        BeatDetector.BeatMarker(timestampMs = t, intensity = intensity)

    private fun video(id: String = "v1", start: Long = 0L, duration: Long = 30_000L) =
        VideoClip(
            id = id, uri = "file:///$id.mp4", fileName = "$id.mp4", durationMs = duration,
            width = 1920, height = 1080, fileSizeBytes = 1_000L, importedAtEpochMillis = 0L,
            timelineStartMs = start
        )

    private fun audio(
        id: String = "a1",
        markers: List<BeatDetector.BeatMarker> = emptyList(),
        bpm: Int? = 120
    ) = AudioClip(
        id = id, sourceUri = "file:///$id.mp3", fileName = "$id.mp3",
        startTimeMs = 0L, durationMs = 30_000L,
        beatData = BeatDetector.AudioBeatData(markers, bpm)
    )

    /** Steady 120 BPM grid: beat every 500 ms. */
    private fun grid120(from: Long = 0L, until: Long = 30_000L, intensity: Float = 0.9f) =
        generateSequence(from) { it + 500L }.takeWhile { it <= until }
            .map { marker(it, intensity) }.toList()

    // ---- validation ----

    @Test
    fun `negative and out-of-range beats are dropped`() {
        val beats = listOf(marker(-500L), marker(1000L), marker(29_900L), marker(60_000L))
        val out = BeatSyncPlanner.plan(beats, video(), BeatSyncConfig())
        val cuts = out.map { it.cutTimelineMs }
        assertFalse(cuts.contains(-500L))
        assertFalse(cuts.contains(60_000L))
        assertTrue(cuts.contains(1000L))
    }

    @Test
    fun `edge margin excludes boundary slivers`() {
        // Beats within 500 ms of clip edges never become candidates.
        val beats = listOf(marker(100L), marker(400L), marker(29_700L), marker(5000L))
        val out = BeatSyncPlanner.plan(beats, video(), BeatSyncConfig())
        val cuts = out.map { it.cutTimelineMs }
        assertFalse(cuts.contains(100L))
        assertFalse(cuts.contains(400L))
        assertFalse(cuts.contains(29_700L))
        assertTrue(cuts.contains(5000L))
    }

    @Test
    fun `duplicate and very close beats collapse`() {
        val beats = listOf(marker(5000L), marker(5000L), marker(5050L), marker(10_000L))
        val out = BeatSyncPlanner.plan(beats, video(), BeatSyncConfig())
        assertEquals(listOf(5000L, 10_000L), out.map { it.cutTimelineMs })
    }

    @Test
    fun `empty and degenerate inputs yield nothing`() {
        assertTrue(BeatSyncPlanner.plan(emptyList(), video()).isEmpty())
        assertTrue(
            BeatSyncPlanner.plan(
                grid120(),
                video(duration = 0L)
            ).isEmpty()
        )
    }

    // ---- scoring ----

    @Test
    fun `stronger beats rank higher at equal spacing`() {
        val beats = listOf(
            marker(5000L, 0.95f),
            marker(10_000L, 0.4f),
            marker(15_000L, 0.95f),
            marker(20_000L, 0.4f)
        )
        val out = BeatSyncPlanner.plan(
            beats, video(),
            BeatSyncConfig(mode = BeatSyncMode.DYNAMIC)
        )
        val byTime = out.sortedBy { it.cutTimelineMs }
        assertTrue(byTime[0].score > byTime[1].score)
        assertTrue(byTime[0].selected)
    }

    @Test
    fun `ranking is deterministic across runs`() {
        val beats = grid120(0L, 60_000L, 0.8f)
        val v = video(duration = 60_000L)
        val first = BeatSyncPlanner.plan(beats, v, BeatSyncConfig())
        val second = BeatSyncPlanner.plan(beats, v, BeatSyncConfig())
        assertEquals(
            first.map { it.id to it.selected },
            second.map { it.id to it.selected }
        )
    }

    @Test
    fun `all candidates sit exactly on beats`() {
        val out = BeatSyncPlanner.plan(grid120(), video(), BeatSyncConfig())
        assertTrue(out.isNotEmpty())
        out.forEach {
            assertEquals(it.beatTimelineMs, it.cutTimelineMs)
            assertEquals(0L, it.offsetMs)
            assertTrue(it.score in 0f..1f)
            assertTrue(it.beatStrength in 0f..1f)
        }
        // Ascending output for the panel list.
        for (i in 1 until out.size) {
            assertTrue(out[i].cutTimelineMs > out[i - 1].cutTimelineMs)
        }
    }

    // ---- density ----

    @Test
    fun `minimal mode caps count and spacing`() {
        val out = BeatSyncPlanner.plan(
            grid120(0L, 120_000L),
            video(duration = 120_000L),
            BeatSyncConfig(mode = BeatSyncMode.MINIMAL)
        )
        val selected = out.filter { it.selected }
        assertTrue(selected.size <= BeatSyncMode.MINIMAL.maxCuts)
        for (i in 1 until selected.size) {
            assertTrue(selected[i].cutTimelineMs - selected[i - 1].cutTimelineMs >= 4000L)
        }
    }

    @Test
    fun `balanced selects roughly every second beat without excess`() {
        val out = BeatSyncPlanner.plan(grid120(), video(), BeatSyncConfig(mode = BeatSyncMode.BALANCED))
        val selected = out.filter { it.selected }
        assertTrue(selected.isNotEmpty())
        assertTrue(selected.size <= BeatSyncMode.BALANCED.maxCuts)
        assertTrue(selected.size < 60) // never one-cut-per-beat on a 60-beat grid
        for (i in 1 until selected.size) {
            assertTrue(selected[i].cutTimelineMs - selected[i - 1].cutTimelineMs >= 2000L)
        }
    }

    @Test
    fun `dynamic allows denser cuts than balanced`() {
        val beats = grid120()
        val balanced = BeatSyncPlanner.plan(beats, video(), BeatSyncConfig(mode = BeatSyncMode.BALANCED))
            .count { it.selected }
        val dynamic = BeatSyncPlanner.plan(beats, video(), BeatSyncConfig(mode = BeatSyncMode.DYNAMIC))
            .count { it.selected }
        assertTrue(dynamic >= balanced)
    }

    @Test
    fun `weak beats stay visible but unselected`() {
        val beats = listOf(marker(5000L, 0.15f), marker(15_000L, 0.95f))
        val out = BeatSyncPlanner.plan(beats, video(), BeatSyncConfig(mode = BeatSyncMode.BALANCED))
        assertEquals(2, out.size)
        val weak = out.first { it.cutTimelineMs == 5000L }
        assertFalse(weak.selected)
        assertEquals(BeatSyncReason.WEAK_BEAT, weak.reason)
        assertTrue(out.first { it.cutTimelineMs == 15_000L }.selected)
    }

    @Test
    fun `candidate ids are deterministic clip plus time`() {
        val out = BeatSyncPlanner.plan(listOf(marker(5000L)), video("v9"), BeatSyncConfig())
        assertEquals("v9:5000", out.single().id)
        assertEquals("v9", out.single().clipId)
    }

    // ---- source key ----

    @Test
    fun `source key detects video audio mode changes`() {
        val v = video()
        val a = audio(markers = grid120(), bpm = 120)
        val key = beatSyncSourceKey(v, a, BeatSyncMode.BALANCED)
        assertEquals(key, beatSyncSourceKey(v.copy(), a.copy(), BeatSyncMode.BALANCED))
        assertFalse(key == beatSyncSourceKey(v.copy(trimStartMs = 500L), a, BeatSyncMode.BALANCED))
        assertFalse(key == beatSyncSourceKey(v, a.copy(durationMs = 20_000L), BeatSyncMode.BALANCED))
        assertFalse(key == beatSyncSourceKey(v, a, BeatSyncMode.MINIMAL))
        // Re-analysis with different beat content invalidates.
        val reanalyzed = audio(markers = grid120(0L, 15_000L), bpm = 120)
        assertFalse(key == beatSyncSourceKey(v, reanalyzed, BeatSyncMode.BALANCED))
        // BPM change invalidates.
        assertFalse(
            key == beatSyncSourceKey(
                v, audio(markers = grid120(), bpm = 100), BeatSyncMode.BALANCED
            )
        )
    }

    // ---- normalization for apply ----

    @Test
    fun `normalizeCuts sorts dedupes bounds and spaces`() {
        val out = BeatSyncPlanner.normalizeCuts(
            listOf(9000L, 1000L, 1000L, 200L, 29_900L, 9500L, 400L),
            clipStartMs = 0L,
            clipEndMs = 30_000L
        )
        // 200/400 inside 500 ms margin → dropped; 29900 too close to
        // the end → dropped; 9500 within 2000 ms of 9000 → gap-dropped.
        assertEquals(listOf(1000L, 9000L), out)
        for (i in 1 until out.size) assertTrue(out[i] > out[i - 1])
    }

    @Test
    fun `normalizeCuts rejects degenerate ranges`() {
        assertTrue(BeatSyncPlanner.normalizeCuts(listOf(1000L), 5000L, 5000L).isEmpty())
        assertTrue(BeatSyncPlanner.normalizeCuts(listOf(1000L), 5000L, 4000L).isEmpty())
        assertTrue(BeatSyncPlanner.normalizeCuts(emptyList(), 0L, 30_000L).isEmpty())
    }

    @Test
    fun `selected-only cuts keep every piece above sliver size`() {
        val out = BeatSyncPlanner.plan(grid120(), video(), BeatSyncConfig(mode = BeatSyncMode.BALANCED))
        val cuts = BeatSyncPlanner.normalizeCuts(
            out.filter { it.selected }.map { it.cutTimelineMs }, 0L, 30_000L
        )
        val bounds = listOf(0L) + cuts + listOf(30_000L)
        for (i in 1 until bounds.size) {
            // Edge margin (500) + min gap (2000) guarantee pieces ≥ 500 ms.
            assertTrue(bounds[i] - bounds[i - 1] >= 500L)
        }
    }
}
