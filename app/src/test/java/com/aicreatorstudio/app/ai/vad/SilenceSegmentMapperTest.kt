package com.aicreatorstudio.app.ai.vad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 1 — JVM tests for [SilenceSegmentMapper] (pure, no Android/ONNX).
 *
 * Frame geometry: 1 probability = 32 ms.
 */
class SilenceSegmentMapperTest {

    private fun probs(vararg values: Float): FloatArray = floatArrayOf(*values)

    private fun silenceFrames(n: Int, p: Float = 0.1f) = FloatArray(n) { p }
    private fun speechFrames(n: Int, p: Float = 0.9f) = FloatArray(n) { p }

    @Test
    fun `empty input yields no segments`() {
        assertTrue(SilenceSegmentMapper.map(probs()).isEmpty())
    }

    @Test
    fun `all speech yields no segments`() {
        val out = SilenceSegmentMapper.map(
            speechFrames(40),
            SilenceDetectionConfig(threshold = 0.5f, minSilenceMs = 300f, padBeforeMs = 0f, padAfterMs = 0f)
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `long silence yields one scored segment`() {
        // 20 frames × 32 ms = 640 ms ≥ 500 ms minimum.
        val out = SilenceSegmentMapper.map(
            silenceFrames(20),
            SilenceDetectionConfig(threshold = 0.5f, minSilenceMs = 500f, padBeforeMs = 0f, padAfterMs = 0f)
        )
        assertEquals(1, out.size)
        assertEquals(0L, out[0].startMs)
        assertEquals(640L, out[0].endMs)
        assertEquals(640L, out[0].durationMs)
        // Confidence = 1 − mean prob = 1 − 0.1 = 0.9 (± float noise).
        assertEquals(0.9f, out[0].confidence, 0.001f)
    }

    @Test
    fun `short silence below minimum is kept intact`() {
        // 5 silence frames = 160 ms < 500 ms minimum.
        val input = speechFrames(10) + silenceFrames(5) + speechFrames(10)
        val out = SilenceSegmentMapper.map(
            input,
            SilenceDetectionConfig(threshold = 0.5f, minSilenceMs = 500f, padBeforeMs = 0f, padAfterMs = 0f)
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `separate pre and post padding shrink silence asymmetrically`() {
        // One speech frame at index 10; pad 2 frames before, 0 after.
        val input = silenceFrames(30).also { it[10] = 0.9f }
        val out = SilenceSegmentMapper.map(
            input,
            SilenceDetectionConfig(
                threshold = 0.5f,
                minSilenceMs = 64f, // 2 frames
                padBeforeMs = 64f, // 2 frames → covers 8..10
                padAfterMs = 0f
            )
        )
        assertEquals(2, out.size)
        // Pre-speech silence ends where the pre-pad starts: frame 8 = 256 ms.
        assertEquals(0L, out[0].startMs)
        assertEquals(256L, out[0].endMs)
        // Post-speech silence resumes right after the speech frame.
        assertEquals(352L, out[1].startMs) // frame 11 × 32
        assertEquals(960L, out[1].endMs) // frame 30 × 32
    }

    @Test
    fun `symmetric legacy config matches both sides`() {
        val input = silenceFrames(30).also { it[10] = 0.9f }
        val out = SilenceSegmentMapper.map(
            input,
            SilenceDetectionConfig(threshold = 0.5f, minSilenceMs = 64f, speechPadMs = 64f)
        )
        assertEquals(2, out.size)
        assertEquals(256L, out[0].endMs) // pad covers 8..12
        assertEquals(416L, out[1].startMs) // frame 13 × 32
    }

    @Test
    fun `confidence stays in range and segments are sorted`() {
        val input = FloatArray(60) { i -> if (i % 3 == 0) 0.9f else 0.05f }
        val out = SilenceSegmentMapper.map(
            input,
            SilenceDetectionConfig(threshold = 0.5f, minSilenceMs = 32f, padBeforeMs = 0f, padAfterMs = 0f)
        )
        assertTrue(out.isNotEmpty())
        out.forEach { assertTrue(it.confidence in 0f..1f) }
        for (i in 1 until out.size) {
            assertTrue(out[i - 1].startMs <= out[i].startMs)
        }
    }

    @Test
    fun `uncertain audio yields lower confidence than dead silence`() {
        val dead = silenceFrames(20, p = 0.0f)
        val mush = silenceFrames(20, p = 0.4f)
        val cfg = SilenceDetectionConfig(threshold = 0.5f, minSilenceMs = 500f, padBeforeMs = 0f, padAfterMs = 0f)
        val deadConf = SilenceSegmentMapper.map(dead, cfg).single().confidence
        val mushConf = SilenceSegmentMapper.map(mush, cfg).single().confidence
        assertTrue(deadConf > mushConf)
    }
}
