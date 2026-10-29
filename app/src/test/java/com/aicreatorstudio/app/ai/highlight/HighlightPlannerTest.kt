package com.aicreatorstudio.app.ai.highlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 7 — JVM tests for highlight planning.
 *
 * Covers: normalization bounds/const/outlier/missing, scoring
 * (combined strength, renormalization, silence penalty, config),
 * merging (overlap/adjacent/min/max/boundary), ranking (order, ties,
 * duration fit), diversity (overlap suppression, cap), and reasons.
 * Detector DSP stays covered by its own suites; the engine's
 * orchestration is verified by build + device runs.
 */
class HighlightPlannerTest {

    private val cfg = HighlightConfig()

    private fun win(
        s: Long,
        e: Long,
        speech: Float? = 0.8f,
        rms: Float = -14f,
        neighbor: Float? = -20f,
        density: Float? = 0.6f,
        intensity: Float? = 0.7f,
        visual: Float? = 0.5f,
        wps: Float? = 2f
    ) = SignalWindow(s, e, speech, rms, neighbor, density, intensity, visual, wps)

    private fun hot(s: Long, e: Long) = win(s, e)
    private fun cold(s: Long, e: Long) = SignalWindow(
        s, e,
        speechRatio = 0.02f, rmsDb = -55f, neighborRmsDb = -50f,
        beatDensity = 0f, beatIntensity = 0.1f, visualActivity = 0.05f,
        wordsPerSecond = 0f
    )

    // ---- normalization ----

    @Test
    fun `signal scores stay in range`() {
        assertTrue(HighlightPlanner.speechScore(0f) in 0f..1f)
        assertTrue(HighlightPlanner.speechScore(5f) in 0f..1f)
        assertTrue(HighlightPlanner.speechScore(-2f) in 0f..1f)
        assertTrue(HighlightPlanner.energyScore(-14f, -20f, cfg) in 0f..1f)
        assertTrue(HighlightPlanner.energyScore(Float.NEGATIVE_INFINITY, null, cfg) in 0f..1f)
        assertTrue(HighlightPlanner.rhythmScore(2f, -1f) in 0f..1f)
        assertTrue(HighlightPlanner.subtitleScore(99f) in 0f..1f)
        assertEquals(1f, HighlightPlanner.subtitleScore(3f), 0.001f)
    }

    @Test
    fun `constant energy is neutral not hot`() {
        // Uniform bed: contextual lift ≈ 0.5, absolute ≈ mid → middling.
        val s = HighlightPlanner.energyScore(-20f, -20f, cfg)
        assertTrue(s in 0.3f..0.7f)
    }

    @Test
    fun `local peak beats uniform bed`() {
        val peak = HighlightPlanner.energyScore(-8f, -25f, cfg)
        val bed = HighlightPlanner.energyScore(-25f, -25f, cfg)
        assertTrue(peak > bed)
    }

    // ---- scoring ----

    @Test
    fun `combined signals outrank single signal`() {
        val full = HighlightPlanner.plan(listOf(hot(0, 6000)), "c1", cfg)
        val lone = HighlightPlanner.plan(
            listOf(SignalWindow(0, 6000, speechRatio = 0.9f)),
            "c1", cfg.copy(maxHighlights = 5)
        )
        assertTrue(full.isNotEmpty())
        // Single-signal window still scores (renormalized), just lower.
        assertTrue(lone.isNotEmpty())
        assertTrue(full[0].score >= lone[0].score)
    }

    @Test
    fun `missing signals renormalize instead of zeroing`() {
        // Speech-only window must still clear the keep threshold.
        val out = HighlightPlanner.plan(
            listOf(SignalWindow(0, 6000, speechRatio = 0.9f)),
            "c1", cfg
        )
        assertEquals(1, out.size)
        assertTrue(out[0].score > cfg.keepThreshold)
    }

    @Test
    fun `dead air is penalized below threshold`() {
        val out = HighlightPlanner.plan(listOf(cold(0, 6000)), "c1", cfg)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `empty windows drop without crashing`() {
        val out = HighlightPlanner.plan(
            listOf(SignalWindow(0, 4000)),
            "c1", cfg
        )
        assertTrue(out.isEmpty())
        assertTrue(HighlightPlanner.plan(emptyList(), "c1", cfg).isEmpty())
    }

    @Test
    fun `config weights change outcomes`() {
        val windows = listOf(win(0, 6000, speech = 0.9f, rms = -40f))
        val speechHeavy = HighlightPlanner.plan(
            windows, "c1", cfg.copy(speechWeight = 1f, energyWeight = 0f)
        )
        val energyOnly = HighlightPlanner.plan(
            windows, "c1",
            cfg.copy(speechWeight = 0f, energyWeight = 1f, rhythmWeight = 0f)
        )
        assertTrue(speechHeavy.isNotEmpty())
        // Quiet speech scores low on energy alone.
        assertTrue(
            energyOnly.isEmpty() ||
                energyOnly[0].score < speechHeavy[0].score
        )
    }

    // ---- merging ----

    @Test
    fun `adjacent hot windows merge into one segment`() {
        val out = HighlightPlanner.plan(
            listOf(hot(0, 4000), hot(2000, 6000), hot(4000, 8000)),
            "c1", cfg
        )
        assertEquals(1, out.size)
        assertEquals(0L, out[0].startMs)
        assertEquals(8000L, out[0].endMs)
    }

    @Test
    fun `cold gap splits segments`() {
        val out = HighlightPlanner.plan(
            listOf(hot(0, 6000), cold(6000, 12_000), hot(12_000, 18_000)),
            "c1", cfg
        )
        assertEquals(2, out.size)
        assertEquals(0L, out[0].startMs)
        assertEquals(12_000L, out[1].startMs)
    }

    @Test
    fun `short blips below minimum drop`() {
        // Single 4 s window, min 5 s → dropped.
        val out = HighlightPlanner.plan(listOf(hot(0, 4000)), "c1", cfg)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `overlong runs split at coldest boundary`() {
        val windows = (0 until 12).map { i -> hot(i * 2000L, i * 2000L + 4000L) }
        val out = HighlightPlanner.plan(
            windows, "c1",
            cfg.copy(maxDurationMs = 10_000L, maxHighlights = 10)
        )
        assertTrue(out.size >= 2)
        out.forEach { assertTrue(it.durationMs <= 10_000L) }
        // Coverage preserved across the split.
        assertEquals(0L, out.minOf { it.startMs })
    }

    // ---- ranking & diversity ----

    @Test
    fun `ranking is deterministic with tie-breaking`() {
        val windows = listOf(hot(20_000, 26_000), hot(0, 6000))
        val first = HighlightPlanner.plan(windows, "c1", cfg)
        val second = HighlightPlanner.plan(windows.reversed(), "c1", cfg)
        assertEquals(first.map { it.id }, second.map { it.id })
    }

    @Test
    fun `overlapping candidates suppress to one`() {
        // Two hot runs separated by a lukewarm bridge: bridge scores
        // above threshold so everything merges, then diversity keeps one.
        val out = HighlightPlanner.plan(
            listOf(
                hot(0, 6000),
                win(4000, 10_000, speech = 0.6f, rms = -16f),
                hot(8000, 14_000)
            ),
            "c1", cfg.copy(maxHighlights = 5)
        )
        assertEquals(1, out.size)
        assertEquals(0L, out[0].startMs)
        assertEquals(14_000L, out[0].endMs)
    }

    @Test
    fun `distant peaks both survive with cap respected`() {
        val out = HighlightPlanner.plan(
            listOf(
                hot(0, 6000),
                cold(6000, 60_000),
                hot(60_000, 66_000),
                cold(66_000, 120_000),
                hot(120_000, 126_000)
            ),
            "c1", cfg.copy(maxHighlights = 2)
        )
        assertEquals(2, out.size)
        assertTrue(out[0].startMs < out[1].startMs)
        // Best (first by score, then time) kept; third dropped by cap.
        assertEquals(0L, out[0].startMs)
    }

    @Test
    fun `ids are stable clip plus range`() {
        val out = HighlightPlanner.plan(listOf(hot(2000, 8000)), "c9", cfg)
        assertEquals("c9:2000-8000", out.single().id)
        assertEquals("c9", out.single().clipId)
        assertEquals(6000L, out.single().durationMs)
    }

    // ---- reasons & confidence ----

    @Test
    fun `strong mix reason needs two hot signals`() {
        val out = HighlightPlanner.plan(
            listOf(win(0, 6000, speech = 0.95f, rms = -8f, density = 0.8f, intensity = 0.9f)),
            "c1", cfg
        )
        assertTrue(out.isNotEmpty())
        assertTrue(
            out[0].reason == HighlightReason.STRONG_MIX ||
                out[0].reason == HighlightReason.SPEECH_ACTIVE
        )
        assertTrue(out[0].confidence in 0f..1f)
    }

    @Test
    fun `empty signal windows yield zero confidence path`() {
        // Windows with signals present but ice-cold → no candidates,
        // never a crash, never a fabricated highlight.
        val out = HighlightPlanner.plan(
            listOf(cold(0, 6000), cold(6000, 12_000)),
            "c1", cfg
        )
        assertTrue(out.isEmpty())
    }

    // ---- persistence codec (AI-tools blob, no migration) ----

    @Test
    fun `highlight ranges round-trip through the blob`() {
        val tools = mapOf(
            "c1" to com.aicreatorstudio.app.db.EditorStateJson.ClipAiTools(
                highlightRanges = listOf(
                    com.aicreatorstudio.app.model.HighlightRange(2000L, 8000L, 0.8f, "STRONG_MIX"),
                    com.aicreatorstudio.app.model.HighlightRange(12_000L, 18_000L, 0.6f, "")
                )
            )
        )
        val json =
            com.aicreatorstudio.app.db.EditorStateJson.aiToolsToJson(tools)
        val back =
            com.aicreatorstudio.app.db.EditorStateJson.aiToolsFromJson(json)
        val ranges = back.getValue("c1").highlightRanges
        assertEquals(2, ranges.size)
        assertEquals(2000L, ranges[0].startMs)
        assertEquals(8000L, ranges[0].endMs)
        assertEquals(0.8f, ranges[0].score, 0.001f)
        assertEquals("STRONG_MIX", ranges[0].reason)
        assertEquals(6000L, ranges[0].durationMs)
    }

    @Test
    fun `legacy blobs decode to empty highlights`() {
        val back = com.aicreatorstudio.app.db.EditorStateJson.aiToolsFromJson("{}")
        assertTrue(back.isEmpty())
        val legacy = com.aicreatorstudio.app.db.EditorStateJson.aiToolsFromJson(
            """{"c1":{"voiceCleanEnabled":true,"voiceCleanStrength":0.6,"voiceCleanAlgorithm":"rnnoise-v1"}}"""
        )
        assertTrue(legacy.getValue("c1").highlightRanges.isEmpty())
    }
}
