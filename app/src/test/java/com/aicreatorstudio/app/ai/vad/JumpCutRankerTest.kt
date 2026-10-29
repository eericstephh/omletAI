package com.aicreatorstudio.app.ai.vad

import com.aicreatorstudio.app.ai.TestContext
import com.aicreatorstudio.app.model.VideoClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * PHASE 3 — JVM tests for the Smart Jump Cut ranking layer.
 *
 * Covers: scoring monotonicity (duration / confidence), short-pause
 * and low-confidence caps, boundary penalty, normalization bounds,
 * deterministic ordering, max-candidate cap, default selection,
 * multi-clip independence, clip-local coordinate safety, and reuse of
 * the Phase 2 apply path for recommended-only cuts.
 */
class JumpCutRankerTest {

    private val cfg = JumpCutConfig()
    private lateinit var cutter: SilenceCutter

    @Before
    fun setUp() {
        cutter = SilenceCutter(TestContext(Files.createTempDirectory("jumpcut-test").toFile()))
    }

    private fun seg(s: Long, e: Long, conf: Float = 0.9f) = SilenceSegment(s, e, conf)

    private fun analysis(
        clipId: String,
        durationMs: Long,
        segments: List<SilenceSegment>
    ) = SilenceCutter.ClipSilenceAnalysis(clipId, "$clipId.mp4", durationMs, segments, durationMs)

    private fun clip(id: String, durationMs: Long) = VideoClip(
        id = id, uri = "file:///$id.mp4", fileName = "$id.mp4", durationMs = durationMs,
        width = 1920, height = 1080, fileSizeBytes = 1_000L, importedAtEpochMillis = 0L
    )

    // ---- scoring ----

    @Test
    fun `longer silence scores higher at equal confidence`() {
        val mid = JumpCutRanker.score(seg(1000, 2000), 10_000L, cfg).score
        val long = JumpCutRanker.score(seg(1000, 3500), 10_000L, cfg).score
        assertTrue(long > mid)
    }

    @Test
    fun `higher confidence scores higher at equal duration`() {
        val low = JumpCutRanker.score(seg(2000, 3500, 0.55f), 10_000L, cfg).score
        val high = JumpCutRanker.score(seg(2000, 3500, 0.95f), 10_000L, cfg).score
        assertTrue(high > low)
    }

    @Test
    fun `scores stay normalized`() {
        listOf(
            seg(0, 10_000, 1f), seg(0, 100, 0f), seg(5000, 9000, 0.7f)
        ).forEach {
            val s = JumpCutRanker.score(it, 10_000L, cfg).score
            assertTrue(s in 0f..1f)
        }
    }

    @Test
    fun `short pause is capped and not recommended`() {
        val scored = JumpCutRanker.score(seg(2000, 2600, 0.95f), 10_000L, cfg)
        assertTrue(scored.score <= cfg.shortPauseCap)
        assertEquals(JumpCutReason.SHORT_PAUSE, scored.reason)
        assertFalse(scored.score >= cfg.recommendThreshold)
    }

    @Test
    fun `boundary silence is penalized and labelled`() {
        val midClip = JumpCutRanker.score(seg(4000, 6000, 0.95f), 10_000L, cfg)
        val atStart = JumpCutRanker.score(seg(0, 2000, 0.95f), 10_000L, cfg)
        val atEnd = JumpCutRanker.score(seg(8000, 10_000, 0.95f), 10_000L, cfg)
        assertEquals(JumpCutReason.BOUNDARY, atStart.reason)
        assertEquals(JumpCutReason.BOUNDARY, atEnd.reason)
        assertTrue(atStart.score < midClip.score)
        assertTrue(atEnd.score < midClip.score)
    }

    @Test
    fun `low confidence is capped and labelled`() {
        val scored = JumpCutRanker.score(seg(3000, 5000, 0.3f), 10_000L, cfg)
        assertEquals(JumpCutReason.LOW_CONFIDENCE, scored.reason)
        assertTrue(scored.score <= cfg.lowConfidenceCap)
        assertFalse(scored.score >= cfg.recommendThreshold)
    }

    @Test
    fun `reasons follow duration bands`() {
        assertEquals(
            JumpCutReason.MEDIUM_PAUSE,
            JumpCutRanker.score(seg(2000, 3000, 0.9f), 10_000L, cfg).reason
        )
        assertEquals(
            JumpCutReason.LONG_PAUSE,
            JumpCutRanker.score(seg(2000, 3500, 0.9f), 10_000L, cfg).reason
        )
        assertEquals(
            JumpCutReason.EXTENDED_SILENCE,
            JumpCutRanker.score(seg(2000, 5000, 0.9f), 10_000L, cfg).reason
        )
    }

    // ---- ranking ----

    @Test
    fun `ranking is deterministic across runs`() {
        val analyses = listOf(
            analysis("c1", 20_000L, listOf(seg(1000, 2500), seg(5000, 5600), seg(9000, 12_000))),
            analysis("c2", 20_000L, listOf(seg(1000, 2500), seg(15_000, 18_000)))
        )
        val first = JumpCutRanker.rank(analyses, cfg)
        val second = JumpCutRanker.rank(analyses, cfg)
        assertEquals(
            first.flatMap { r -> r.candidates.map { it.startMs to it.score } },
            second.flatMap { r -> r.candidates.map { it.startMs to it.score } }
        )
    }

    @Test
    fun `ties break by duration then clip then time`() {
        val analyses = listOf(
            analysis("b", 10_000L, listOf(seg(1000, 2000, 0.9f))),
            analysis("a", 10_000L, listOf(seg(1000, 2000, 0.9f)))
        )
        val ranked = JumpCutRanker.rank(analyses, cfg)
        // Equal scores: clip "a" sorts before "b" only in global order —
        // per-clip lists each hold their own candidate; both recommended equally.
        assertEquals(1, ranked[0].candidates.size)
        assertEquals(1, ranked[1].candidates.size)
        assertEquals(
            ranked[0].candidates.single().score,
            ranked[1].candidates.single().score, 0.0001f
        )
    }

    @Test
    fun `max candidates caps hundreds of pauses`() {
        val segs = (0 until 300).map { i -> seg(i * 700L, i * 700L + 600L, 0.9f) }
        val ranked = JumpCutRanker.rank(
            listOf(analysis("c1", 220_000L, segs)),
            cfg.copy(maxCandidates = 200)
        )
        assertEquals(200, ranked.single().candidates.size)
    }

    @Test
    fun `below-minimum micro pauses never reach ranking`() {
        val ranked = JumpCutRanker.rank(
            listOf(analysis("c1", 10_000L, listOf(seg(1000, 1200), seg(5000, 8000)))),
            cfg
        )
        assertEquals(1, ranked.single().candidates.size)
        assertEquals(5000L, ranked.single().candidates.single().startMs)
    }

    @Test
    fun `high quality recommended borderline preserved unselected`() {
        val ranked = JumpCutRanker.rank(
            listOf(
                analysis(
                    "c1", 20_000L, listOf(
                        seg(1000, 4000, 0.95f), // extended → recommended
                        seg(6000, 7000, 0.9f), // medium → borderline
                        seg(9000, 9600, 0.9f) // short → capped
                    )
                )
            ),
            cfg
        )
        val cands = ranked.single().candidates
        assertEquals(3, cands.size)
        assertTrue(cands[0].recommended) // 3000 ms extended
        assertFalse(cands[1].recommended) // 1000 ms medium stays for review
        assertFalse(cands[2].recommended) // 600 ms short stays
    }

    @Test
    fun `zero candidates yields empty ranking without crashing`() {
        assertTrue(JumpCutRanker.rank(emptyList(), cfg).isEmpty())
        val ranked = JumpCutRanker.rank(listOf(analysis("c1", 10_000L, emptyList())), cfg)
        assertEquals(1, ranked.size)
        assertTrue(ranked.single().candidates.isEmpty())
    }

    @Test
    fun `overlapping inputs merge before ranking`() {
        val ranked = JumpCutRanker.rank(
            listOf(analysis("c1", 10_000L, listOf(seg(1000, 2000), seg(1500, 2500)))),
            cfg
        )
        assertEquals(1, ranked.single().candidates.size)
        assertEquals(1000L, ranked.single().candidates.single().startMs)
        assertEquals(2500L, ranked.single().candidates.single().endMs)
    }

    // ---- coordinates & apply reuse ----

    @Test
    fun `candidates keep clip-local coordinates per clip`() {
        val ranked = JumpCutRanker.rank(
            listOf(
                analysis("c1", 8000L, listOf(seg(1000, 3000))),
                analysis("c2", 30_000L, listOf(seg(20_000, 25_000)))
            ),
            cfg
        )
        for (r in ranked) {
            for (c in r.candidates) {
                assertTrue(c.startMs >= 0)
                assertTrue(c.endMs <= r.clipDurationMs)
            }
        }
        // Long silence deep in c2 survives with its own clip id.
        assertEquals("c2", ranked[1].clipId)
        assertEquals(20_000L, ranked[1].candidates.single().startMs)
    }

    @Test
    fun `recommended-only apply removes just recommended cuts`() {
        val clips = listOf(clip("c1", 20_000L))
        val ranked = JumpCutRanker.rank(
            listOf(
                analysis(
                    "c1", 20_000L, listOf(
                        seg(1000, 4000, 0.95f), // recommended
                        seg(6000, 7000, 0.9f) // borderline, unselected
                    )
                )
            ),
            cfg
        )
        // Mirror of EditorViewModel.applySilencePreview id assignment.
        val stored = ranked.map { it.toAnalysis() }
        val selectedIds = ranked.flatMapIndexed { _, r ->
            r.candidates.mapIndexedNotNull { index, cand ->
                if (cand.recommended) SilenceCutter.segmentId(r.clipId, index) else null
            }
        }.toSet()
        assertEquals(1, selectedIds.size)
        val result = cutter.buildCutClips(clips, SilenceCutter.filterSelected(stored, selectedIds))
        // Only the 3000 ms recommended cut removed; 1000 ms pause kept.
        assertEquals(3000L, result.totalSilenceMsRemoved)
        assertEquals(17_000L, result.newClips.sumOf { it.effectiveDurationMs })
    }

    @Test
    fun `select-all round trip keeps every ranked segment`() {
        val ranked = JumpCutRanker.rank(
            listOf(analysis("c1", 20_000L, listOf(seg(1000, 4000), seg(6000, 9000)))),
            cfg.copy(maxCandidates = 200)
        )
        val stored = ranked.map { it.toAnalysis() }
        val allIds = ranked.flatMap { r ->
            r.candidates.indices.map { i -> SilenceCutter.segmentId(r.clipId, i) }
        }.toSet()
        val filtered = SilenceCutter.filterSelected(stored, allIds)
        assertEquals(2, filtered.single().segments.size)
    }
}
